package net.palaya.chessanalyzer.data

import android.content.Context
import android.os.SystemClock
import java.io.BufferedReader
import java.io.InputStreamReader
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import net.palaya.chessanalyzer.core.analysis.AdaptiveDepth
import net.palaya.chessanalyzer.core.analysis.EngineLineInput
import net.palaya.chessanalyzer.core.analysis.GameAnalyzer
import net.palaya.chessanalyzer.core.analysis.GameReport as CoreGameReport
import net.palaya.chessanalyzer.core.analysis.MoveClassifier
import net.palaya.chessanalyzer.core.analysis.OpeningBook
import net.palaya.chessanalyzer.core.analysis.PositionEval
import net.palaya.chessanalyzer.core.chess.Color
import net.palaya.chessanalyzer.core.chess.Position
import net.palaya.chessanalyzer.core.pgn.PgnGame
import net.palaya.chessanalyzer.core.pgn.PgnParseException
import net.palaya.chessanalyzer.core.pgn.PgnParser
import net.palaya.chessanalyzer.core.tactics.MotifDetector
import net.palaya.chessanalyzer.core.tactics.StaticExchangeEvaluator
import net.palaya.chessanalyzer.diagnostics.AppDiagnostics
import net.palaya.chessanalyzer.diagnostics.DiagnosticLog
import net.palaya.chessanalyzer.diagnostics.PositionLogLine
import net.palaya.chessanalyzer.diagnostics.formatGameForLog
import net.palaya.chessanalyzer.diagnostics.formatPositionLine
import net.palaya.chessanalyzer.diagnostics.scoreText
import net.palaya.chessanalyzer.engine.AnalysisResult
import net.palaya.chessanalyzer.engine.NetNotInstalledException
import net.palaya.chessanalyzer.engine.NetStore
import net.palaya.chessanalyzer.ui.model.AnalysisPhase
import net.palaya.chessanalyzer.ui.model.AnalysisFraction
import net.palaya.chessanalyzer.ui.model.AnalysisProgress
import net.palaya.chessanalyzer.ui.model.AutoDepthBudget
import net.palaya.chessanalyzer.ui.model.EngineSettings
import net.palaya.chessanalyzer.ui.model.SearchBudget

/**
 * The seam between raw PGN text and a finished [CoreGameReport]: parse -> pick game/user color
 * -> eval cache, or ensure engine+net -> analyze every ply (cached) -> [GameAnalyzer]. This is the "Analysis
 * service" called for in the integration brief; it is deliberately plain Kotlin (no ViewModel
 * base class) so it can be driven from a ViewModel's `viewModelScope` and cancelled the normal
 * coroutine way — cancelling the caller's `Job` propagates into the suspended
 * `StockfishEngine.analyze()` call (see that class's cancellation handling) and this function
 * simply stops resuming.
 *
 * Not thread-confined by itself: call it from a background dispatcher (a ViewModel's
 * `viewModelScope.launch(Dispatchers.Default) { ... }` is the intended caller) so the per-ply
 * engine loop and the CPU-heavy [GameAnalyzer.analyze] pass never run on the main thread.
 */
class AnalysisService(
    private val context: Context,
    private val engineController: EngineController,
    private val gameRepository: GameRepository,
    /** The diagnostic log (F1); null in tests that do not care. */
    private val diagnostics: DiagnosticLog? = null,
    /** Monotonic clock for the progress timestamps. */
    private val nowMs: () -> Long = { SystemClock.elapsedRealtime() },
) {

    /**
     * Why an analysis could not produce a report. Deliberately not a message: the service has no
     * business owning user-facing English, so the UI maps each reason to a string resource and
     * the raw exception text (in [Outcome.ParseError.detail] / [Outcome.EngineError.detail]) is
     * only ever logged.
     */
    enum class Failure {
        /** The text parsed but held no games. */
        NO_GAMES,
        /** The text is not PGN this app can read. */
        PARSE,
        /** The engine could not be prepared. */
        ENGINE_PREPARE,
        /**
         * No verified engine net is installed: the first-run download (Setup) has not finished, or the
         * net on disk failed its SHA-256 check. The fix is the Setup screen (D2c), never a retry.
         */
        SETUP_REQUIRED,
        /** The engine object was not available after preparation. */
        ENGINE_START,
        /** The engine failed part-way through the game. */
        ANALYSIS,
        /** The pending game text was no longer registered (e.g. after process death). */
        GAME_TEXT_LOST,
    }

    sealed class Outcome {
        data class Success(
            val game: PgnGame,
            val report: CoreGameReport,
            val userColor: Color?,
            val otherGamesInFile: Int,
            /** Positions whose search the budget stopped before the requested depth (spec §8.1). */
            val cappedPositions: Int = 0,
        ) : Outcome()

        data class ParseError(val reason: Failure, val detail: String? = null) : Outcome()
        data class EngineError(val reason: Failure, val detail: String? = null) : Outcome()
        data object Cancelled : Outcome()
    }

    /**
     * @param gameIndex which game to analyze when [pgnText] contains multiple PGN games
     *   (defaults to the first). The picker UI for "which game" is a follow-up; the count of
     *   the other games found is still surfaced on [Outcome.Success] so a caller can offer it.
     * @param onPositionEvaluated called once per position (index 0 = the start position) with its FINAL
     *   evaluation, including positions served from the eval cache or a checkpoint. For Auto-depth
     *   ([EngineSettings.autoDepth], ANALYSIS_SPEC §8.5) it is called again for a position the second pass searched
     *   again, with the deeper evaluation that replaces the first one. Plain Kotlin, on the analysis coroutine:
     *   keep it quick.
     */
    suspend fun analyze(
        pgnText: String,
        gameIndex: Int = 0,
        username: String,
        settings: EngineSettings,
        onProgress: (AnalysisProgress) -> Unit = {},
        onPositionEvaluated: (index: Int, eval: PositionEval) -> Unit = { _, _ -> },
    ): Outcome {
        val games = try {
            PgnParser.parse(pgnText)
        } catch (e: PgnParseException) {
            diagnostics?.error(TAG, "PGN could not be read", e)
            return Outcome.ParseError(Failure.PARSE, e.message)
        } catch (e: Exception) {
            diagnostics?.error(TAG, "PGN could not be read", e)
            return Outcome.ParseError(Failure.PARSE, e.message)
        }
        if (games.isEmpty()) {
            diagnostics?.error(TAG, "PGN held no games")
            return Outcome.ParseError(Failure.NO_GAMES)
        }
        val game = games.getOrElse(gameIndex) { games[0] }
        val userColor = detectUserColor(game, username)
        val budget = settings.searchBudget
        val auto = settings.autoDepth
        val deepStrength = AutoDepthBudget.deep
        val autoLog = if (auto) {
            ", auto-depth: critical positions again at depth ${deepStrength.depth}, node budget " +
                "${deepStrength.budget.nodes}, time cap ${deepStrength.budget.movetimeMs} ms, policy ${AdaptiveDepth.POLICY_VERSION}"
        } else ""
        diagnostics?.log(
            TAG,
            "analysis start: depth ${settings.depth}, multipv ${settings.multiPv}, node budget ${budget.nodes}, " +
                "time cap ${budget.movetimeMs} ms, threads ${EngineController.defaultThreads()}, " +
                "hash ${EngineController.defaultHashMb()} MB, user side ${userColor ?: "unknown"}$autoLog\n" +
                formatGameForLog(game),
        )

        // The budget is part of the key: a cached result is reused only under identical limits. The first pass
        // (every strength) is stored under [baseKey]; Auto's assembled result under its own key, which also
        // carries the deep tier and the policy version (spec §8.5).
        val baseKey = gameRepository.cacheKey(pgnText, settings.depth, settings.multiPv, budget.cacheKeyPart)
        val finalKey = if (auto) {
            gameRepository.cacheKey(pgnText, settings.depth, settings.multiPv, AutoDepthBudget.cacheKeyPart)
        } else baseKey
        // D2e: the eval cache has one folder per net; a result is reused only from the active net's.
        var evals = gameRepository.loadEvalCache(finalKey, gameRepository.currentNetPrefix())
        if (evals != null) {
            diagnostics?.log(TAG, "found in the eval cache (${evals.size} positions)")
            evals.forEachIndexed { i, e -> onPositionEvaluated(i, e) }
        }

        if (evals == null) {
          // D2e: registered with the engine, so a net update cannot switch the net under this run (and a
          // run started during an update's trial waits for it).
          engineController.beginAnalysis()
          try {
            try {
                onProgress(AnalysisProgress(phase = AnalysisPhase.PREPARING_ENGINE, fractionComplete = 0.02f))
                engineController.ensureReady(
                    threads = EngineController.defaultThreads(),
                    hashMb = EngineController.defaultHashMb(),
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                diagnostics?.log(TAG, "cancelled while preparing the engine")
                return Outcome.Cancelled
            } catch (e: NetNotInstalledException) {
                // Only an analysis that needs the engine needs the net: a game in the eval cache
                // opens without it.
                diagnostics?.error(TAG, "engine: no verified net installed, setup required")
                return Outcome.EngineError(Failure.SETUP_REQUIRED, e.message)
            } catch (e: Exception) {
                diagnostics?.error(TAG, "engine could not be prepared", e)
                return Outcome.EngineError(Failure.ENGINE_PREPARE, e.message)
            }
            val engine = engineController.engineOrNull() ?: run {
                diagnostics?.error(TAG, "engine missing after preparation")
                return Outcome.EngineError(Failure.ENGINE_START)
            }
            // Every result of this run is the loaded net's, so the checkpoint and the result go to its folder.
            val runPrefix = engineController.loadedNetName?.let { NetStore.prefixOf(it) } ?: gameRepository.currentNetPrefix()

            val positions = plyFens(game)
            val total = positions.size
            val expectedDeep = if (auto) AnalysisFraction.expectedDeepPositions(total) else 0
            val bar = AnalysisFraction(
                total = total,
                autoDepth = auto,
                baseTypicalNodes = budget.typicalNodes,
                deepTypicalNodes = AutoDepthBudget.TYPICAL_DEEPENED_NODES,
                expectedDeepPositions = expectedDeep,
            )
            // This run's own search totals, for the time-left estimate (spec §8.4).
            var runNodes = 0L
            var runSearchMs = 0L
            var runPositions = 0

            // One engine search of [fen] at [depth] / [limits], with the "depth d of D" progress updates on top of
            // [searching], counted into this run's totals and logged (the spec §8.1 diagnostic line).
            suspend fun searchPosition(index: Int, fen: String, depth: Int, limits: SearchBudget, searching: AnalysisProgress): PositionEval {
                val startedAt = nowMs()
                var shownDepth = 0
                val started = searching.copy(
                    targetDepth = depth,
                    positionStartedAtMs = startedAt,
                    runNodes = runNodes,
                    runSearchMs = runSearchMs,
                    runPositionsSearched = runPositions,
                )
                onProgress(started)
                engine.setPosition(fen = fen)
                val result = engine.analyze(
                    multiPv = settings.multiPv,
                    depth = depth,
                    movetimeMs = limits.movetimeMs,
                    nodes = limits.nodes,
                    onProgress = { p ->
                        // One update per new depth: the screen shows "depth 15 of 18", nothing finer.
                        if (p.depth != shownDepth) {
                            shownDepth = p.depth
                            onProgress(started.copy(searchDepth = p.depth))
                        }
                    },
                )
                val eval = toPositionEval(fen, result, depth)
                val wallMs = nowMs() - startedAt
                runNodes += result.nodes
                runSearchMs += wallMs
                runPositions++
                val best = eval.best
                diagnostics?.log(
                    TAG,
                    formatPositionLine(
                        PositionLogLine(
                            index = index,
                            san = sanBefore(game, index),
                            requestedDepth = depth,
                            reachedDepth = result.depth,
                            nodes = result.nodes,
                            timeMs = wallMs,
                            capped = eval.isCapped,
                            score = scoreText(best?.scoreCp, best?.mateIn),
                        ),
                    ),
                )
                return eval
            }

            // What a kill or a cancel must leave behind: the pass in progress decides (the first pass: its prefix
            // under the base key; Auto's second pass: the assembled evaluations under the Auto key).
            var saveCheckpoint: suspend () -> Unit = {}
            var positionsDone: () -> Int = { 0 }
            try {
                // ---- Pass 1 (every strength): every position at settings.depth with the strength's budget.
                val cachedBase = if (auto) {
                    gameRepository.loadEvalCache(baseKey, runPrefix)?.takeIf { usableResumePrefix(positions, it).size == total }
                } else null
                val base: List<PositionEval>
                if (cachedBase != null) {
                    // Auto's first pass IS a Standard analysis: a Standard result of this game is reused as it is.
                    diagnostics?.log(TAG, "first pass found in the eval cache ($total positions)")
                    cachedBase.forEachIndexed { i, e -> onPositionEvaluated(i, e) }
                    base = cachedBase
                } else {
                    // Resume from a previous cancelled/killed run. The cache key already encodes the PGN,
                    // depth and MultiPV, so any prefix stored under it was computed with identical
                    // settings — but verify FEN alignment per index anyway rather than trusting the key,
                    // because feeding mismatched evals into the analyzer would silently produce a wrong
                    // report rather than an error.
                    val resumable = gameRepository.loadPartialEvalCache(baseKey, runPrefix).orEmpty()
                    val computed = ArrayList<PositionEval>(total)
                        .apply { addAll(usableResumePrefix(positions, resumable)) }
                    saveCheckpoint = { gameRepository.savePartialEvalCache(baseKey, computed, runPrefix) }
                    positionsDone = { computed.size }
                    computed.forEachIndexed { i, e -> onPositionEvaluated(i, e) }
                    val startIndex = computed.size
                    if (startIndex > 0) {
                        diagnostics?.log(TAG, "resuming from the checkpoint: $startIndex of $total positions already done")
                        onProgress(
                            AnalysisProgress(
                                phase = AnalysisPhase.ANALYZING_MOVES,
                                currentMoveIndex = startIndex,
                                totalMoves = total,
                                fractionComplete = bar.firstPass(startIndex),
                                deepPassExpected = expectedDeep,
                            )
                        )
                    }
                    for ((index, fen) in positions.withIndex()) {
                        if (index < startIndex) continue
                        coroutineContext.ensureActive()
                        // A checkmated or stalemated position has nothing to search. Asking the engine
                        // anyway is pure latency at best, and the last ply of most annotated games IS
                        // such a position, so short-circuit it here rather than paying for a search
                        // whose answer we already know from the rules.
                        val terminal = terminalEvalOrNull(fen, settings.depth)
                        val eval = if (terminal != null) {
                            val best = terminal.best
                            diagnostics?.log(
                                TAG,
                                formatPositionLine(
                                    PositionLogLine(index, sanBefore(game, index), settings.depth, settings.depth, 0, 0, false, scoreText(best?.scoreCp, best?.mateIn)),
                                ),
                            )
                            terminal
                        } else {
                            searchPosition(
                                index, fen, settings.depth, budget,
                                AnalysisProgress(
                                    phase = AnalysisPhase.ANALYZING_MOVES,
                                    currentMoveIndex = index,
                                    totalMoves = total,
                                    fractionComplete = bar.firstPass(index),
                                    deepPassExpected = expectedDeep,
                                ),
                            )
                        }
                        computed.add(eval)
                        onPositionEvaluated(index, eval)
                        onProgress(
                            AnalysisProgress(
                                phase = AnalysisPhase.ANALYZING_MOVES,
                                currentMoveIndex = index + 1,
                                totalMoves = total,
                                fractionComplete = bar.firstPass(index + 1),
                                runNodes = runNodes,
                                runSearchMs = runSearchMs,
                                runPositionsSearched = runPositions,
                                deepPassExpected = expectedDeep,
                            )
                        )
                        // Checkpoint so a process kill (rather than a clean cancel) leaves the work
                        // recoverable: Android may kill a backgrounded app during a long Deep analysis.
                        if ((index + 1) % CHECKPOINT_EVERY_PLIES == 0) {
                            withContext(NonCancellable) {
                                gameRepository.savePartialEvalCache(baseKey, computed, runPrefix)
                            }
                        }
                    }
                    // The full cache supersedes the prefix; drop it so it cannot go stale. For Auto this is a
                    // Standard result too, so a later Standard analysis of this game is instant.
                    gameRepository.saveEvalCache(baseKey, computed, runPrefix)
                    gameRepository.clearPartialEvalCache(baseKey, runPrefix)
                    base = computed
                }

                // ---- Pass 2 (Auto only, spec §8.5): the critical positions again with the Deep preset's search.
                evals = if (!auto) base else {
                    val deepKey = gameRepository.cacheKey(pgnText, deepStrength.depth, settings.multiPv, deepStrength.budget.cacheKeyPart)
                    val known = AutoDepthPass.knownDeepEvals(
                        positions,
                        deepStrength.depth,
                        gameRepository.loadPartialEvalCache(finalKey, runPrefix),
                        gameRepository.loadEvalCache(deepKey, runPrefix),
                        gameRepository.loadPartialEvalCache(deepKey, runPrefix),
                    )
                    if (known.isNotEmpty()) {
                        diagnostics?.log(TAG, "auto-depth: ${known.size} deep results available from the checkpoint or a Deep analysis")
                    }
                    val assembled = base.toMutableList()
                    var deepDone = 0
                    saveCheckpoint = { gameRepository.savePartialEvalCache(finalKey, assembled, runPrefix) }
                    positionsDone = { total + deepDone }
                    val pass = AutoDepthPass(game, openingBook(), MoveClassifier(StaticExchangeEvaluator()))
                    val result = pass.run(
                        base = base,
                        known = known,
                        search = { index, done, picked ->
                            searchPosition(
                                index, positions[index], deepStrength.depth, deepStrength.budget,
                                AnalysisProgress(
                                    phase = AnalysisPhase.ANALYZING_MOVES,
                                    currentMoveIndex = total,
                                    totalMoves = total,
                                    fractionComplete = bar.secondPass(done, picked),
                                    deepPassDone = done,
                                    deepPassTotal = picked,
                                ),
                            )
                        },
                        onRound = { round, picks ->
                            diagnostics?.log(
                                TAG,
                                "auto-depth round $round: ${picks.size} positions to search again: " +
                                    picks.joinToString { p -> "${p.index} (${p.reasons.joinToString(", ") { it.logName }})" },
                            )
                        },
                        onDeepened = { d ->
                            assembled[d.index] = d.eval
                            deepDone = d.done
                            onPositionEvaluated(d.index, d.eval)
                            val why = d.reasons.joinToString(", ") { it.logName }
                            if (d.reused) {
                                val best = d.eval.best
                                diagnostics?.log(
                                    TAG,
                                    formatPositionLine(
                                        PositionLogLine(
                                            d.index, sanBefore(game, d.index), d.eval.requestedDepth ?: deepStrength.depth, d.eval.depth,
                                            0, 0, d.eval.isCapped, scoreText(best?.scoreCp, best?.mateIn),
                                            note = "deeper ($why), from the cache",
                                        ),
                                    ),
                                )
                            } else {
                                diagnostics?.log(TAG, "pos ${d.index} was searched deeper ($why)")
                            }
                            onProgress(
                                AnalysisProgress(
                                    phase = AnalysisPhase.ANALYZING_MOVES,
                                    currentMoveIndex = total,
                                    totalMoves = total,
                                    fractionComplete = bar.secondPass(d.done, d.picked),
                                    runNodes = runNodes,
                                    runSearchMs = runSearchMs,
                                    runPositionsSearched = runPositions,
                                    deepPassDone = d.done,
                                    deepPassTotal = d.picked,
                                )
                            )
                        },
                        // Every position, like the first pass (spec §8.3): a kill costs at most the search in progress.
                        checkpoint = { _ ->
                            withContext(NonCancellable) { gameRepository.savePartialEvalCache(finalKey, assembled, runPrefix) }
                        },
                    )
                    diagnostics?.log(
                        TAG,
                        "auto-depth: ${result.deepened.size} of $total positions searched again in ${result.rounds} rounds " +
                            "(${result.deepened.count { it.reused }} from the cache)",
                    )
                    result.evals
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                // Persist what was computed before giving up. NonCancellable is required: this
                // coroutine is already cancelled, so an ordinary suspend call here would throw
                // immediately and we would lose the very work we are trying to save.
                withContext(NonCancellable) { saveCheckpoint() }
                diagnostics?.log(TAG, "cancelled after ${positionsDone()} positions of $total (checkpoint saved)")
                return Outcome.Cancelled
            } catch (e: Exception) {
                withContext(NonCancellable) { saveCheckpoint() }
                diagnostics?.error(TAG, "engine failed after ${positionsDone()} positions of $total", e)
                return Outcome.EngineError(Failure.ANALYSIS, e.message)
            }
            if (auto) {
                gameRepository.saveEvalCache(finalKey, checkNotNull(evals), runPrefix)
                // The full cache supersedes the Auto checkpoint; drop it so it cannot go stale.
                gameRepository.clearPartialEvalCache(finalKey, runPrefix)
            }
          } finally {
            engineController.endAnalysis()
          }
        } else {
            onProgress(
                AnalysisProgress(
                    phase = AnalysisPhase.ANALYZING_MOVES,
                    currentMoveIndex = evals.size,
                    totalMoves = evals.size,
                    fractionComplete = 0.98f,
                )
            )
        }

        // Both branches above leave evals set (the engine branch returns on every failure).
        val allEvals: List<PositionEval> = checkNotNull(evals)
        val book = openingBook()
        val report = withContext(Dispatchers.Default) {
            val see = StaticExchangeEvaluator()
            val analyzer = GameAnalyzer(MoveClassifier(see), MotifDetector(see))
            analyzer.analyze(game, allEvals, userColor, book)
        }

        onProgress(AnalysisProgress(phase = AnalysisPhase.DONE, fractionComplete = 1f))
        val capped = allEvals.count { it.isCapped }
        val deeper = if (auto) allEvals.count { it.requestedDepth == deepStrength.depth } else 0
        diagnostics?.log(TAG, "analysis done: ${allEvals.size} positions, $capped capped" + (if (auto) ", $deeper searched deeper" else ""))
        return Outcome.Success(game, report, userColor, otherGamesInFile = games.size - 1, cappedPositions = capped)
    }

    /** The SAN of the move that led to position [index], null for the start position. */
    private fun sanBefore(game: PgnGame, index: Int): String? = if (index == 0) null else game.moves.getOrNull(index - 1)?.san

    /** FEN before ply 1, FEN after each subsequent ply — i.e. `moves.size + 1` positions. */
    private fun plyFens(game: PgnGame): List<String> {
        if (game.moves.isEmpty()) {
            return listOf(game.startFen ?: Position.STANDARD_START_FEN)
        }
        val fens = ArrayList<String>(game.moves.size + 1)
        fens.add(game.moves.first().positionFenBefore)
        game.moves.forEach { fens.add(it.positionFenAfter) }
        return fens
    }

    private fun detectUserColor(game: PgnGame, username: String): Color? {
        if (username.isBlank()) return null
        val white = game.tags["White"].orEmpty()
        val black = game.tags["Black"].orEmpty()
        return when {
            white.equals(username, ignoreCase = true) -> Color.WHITE
            black.equals(username, ignoreCase = true) -> Color.BLACK
            else -> null
        }
    }

    /**
     * Builds the evaluation for a position with no legal moves, without consulting the engine.
     *
     * Checkmate is a loss for the side to move, so it is reported as `mate 0` from that side's
     * perspective; stalemate is a dead draw at 0 centipawns. Returns null for any position that
     * still has a legal move, which is the normal case.
     */
    private fun terminalEvalOrNull(fen: String, depth: Int): PositionEval? {
        val position = Position.fromFen(fen)
        if (position.legalMoves().isNotEmpty()) return null
        val line = if (position.isInCheck()) {
            EngineLineInput(multiPv = 1, scoreCp = null, mateIn = 0, depth = depth, pvUci = emptyList())
        } else {
            EngineLineInput(multiPv = 1, scoreCp = 0, mateIn = null, depth = depth, pvUci = emptyList())
        }
        return PositionEval(fen = fen, lines = listOf(line), depth = depth, requestedDepth = depth)
    }

    /**
     * The longest prefix of [cached] that is safe to reuse for [positions].
     *
     * Stops at the first index whose FEN does not match the game being analysed. The cache key
     * already encodes the PGN, depth and MultiPV, so a mismatch should be impossible — but reusing
     * misaligned evals would produce a confidently **wrong** report rather than an error, which is
     * the worst failure mode available here, so it is checked rather than assumed.
     */
    internal fun usableResumePrefix(
        positions: List<String>,
        cached: List<PositionEval>,
    ): List<PositionEval> = cached.withIndex()
        .takeWhile { (i, eval) -> i < positions.size && positions[i] == eval.fen }
        .map { it.value }

    /**
     * [result]'s lines all come from one depth (`StockfishEngine.analyze` guarantees it, spec §8.2),
     * so [PositionEval.depth] is that depth and the position is capped when it is below
     * [requestedDepth].
     */
    internal fun toPositionEval(fen: String, result: AnalysisResult, requestedDepth: Int): PositionEval {
        val lines = result.lines.map {
            EngineLineInput(multiPv = it.multiPv, scoreCp = it.scoreCp, mateIn = it.mateIn, depth = it.depth, pvUci = it.pvUci)
        }
        val effectiveLines = if (lines.isEmpty()) {
            listOf(EngineLineInput(multiPv = 1, scoreCp = null, mateIn = null, depth = result.depth, pvUci = listOf(result.bestMoveUci)))
        } else lines
        return PositionEval(fen = fen, lines = effectiveLines, depth = result.depth, requestedDepth = requestedDepth)
    }

    private suspend fun openingBook(): OpeningBook = withContext(Dispatchers.IO) {
        cachedBook ?: synchronized(bookLock) {
            cachedBook ?: run {
                val reader = BufferedReader(InputStreamReader(context.assets.open("openings.tsv"), Charsets.UTF_8))
                OpeningBook.load(reader).also { cachedBook = it }
            }
        }
    }

    companion object {
        /**
         * Plies between partial-cache checkpoints: every ply (F1). A Deep position can take minutes
         * on a phone and Android may kill a backgrounded app at any point; the write is one small
         * JSON file (tens of KB, a temp file then a rename) against seconds of engine work per ply.
         */
        internal const val CHECKPOINT_EVERY_PLIES = 1

        private const val TAG = AppDiagnostics.TAG_ANALYSIS


        private val bookLock = Any()
        @Volatile private var cachedBook: OpeningBook? = null
    }
}
