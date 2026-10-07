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
import net.palaya.chessanalyzer.ui.model.AnalysisProgress
import net.palaya.chessanalyzer.ui.model.EngineSettings

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
     */
    suspend fun analyze(
        pgnText: String,
        gameIndex: Int = 0,
        username: String,
        settings: EngineSettings,
        onProgress: (AnalysisProgress) -> Unit = {},
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
        diagnostics?.log(
            TAG,
            "analysis start: depth ${settings.depth}, multipv ${settings.multiPv}, node budget ${budget.nodes}, " +
                "time cap ${budget.movetimeMs} ms, threads ${EngineController.defaultThreads()}, " +
                "hash ${EngineController.defaultHashMb()} MB, user side ${userColor ?: "unknown"}\n" +
                formatGameForLog(game),
        )

        // The budget is part of the key: a cached result is reused only under identical limits.
        val cacheKey = gameRepository.cacheKey(pgnText, settings.depth, settings.multiPv, budget.cacheKeyPart)
        // D2e: the eval cache has one folder per net; a result is reused only from the active net's.
        var evals = gameRepository.loadEvalCache(cacheKey, gameRepository.currentNetPrefix())
        if (evals != null) diagnostics?.log(TAG, "found in the eval cache (${evals.size} positions)")

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

            // Resume from a previous cancelled/killed run. The cache key already encodes the PGN,
            // depth and MultiPV, so any prefix stored under it was computed with identical
            // settings — but verify FEN alignment per index anyway rather than trusting the key,
            // because feeding mismatched evals into the analyzer would silently produce a wrong
            // report rather than an error.
            val resumable = gameRepository.loadPartialEvalCache(cacheKey, runPrefix).orEmpty()
            val computed = ArrayList<PositionEval>(total)
                .apply { addAll(usableResumePrefix(positions, resumable)) }
            val startIndex = computed.size
            // This run's own search totals, for the time-left estimate (spec §8.4).
            var runNodes = 0L
            var runSearchMs = 0L
            var runPositions = 0
            if (startIndex > 0) {
                diagnostics?.log(TAG, "resuming from the checkpoint: $startIndex of $total positions already done")
                onProgress(
                    AnalysisProgress(
                        phase = AnalysisPhase.ANALYZING_MOVES,
                        currentMoveIndex = startIndex,
                        totalMoves = total,
                        fractionComplete = 0.3f + 0.68f * startIndex / total,
                    )
                )
            }

            try {
                for ((index, fen) in positions.withIndex()) {
                    if (index < startIndex) continue
                    coroutineContext.ensureActive()
                    // A checkmated or stalemated position has nothing to search. Asking the engine
                    // anyway is pure latency at best, and the last ply of most annotated games IS
                    // such a position, so short-circuit it here rather than paying for a search
                    // whose answer we already know from the rules.
                    val terminal = terminalEvalOrNull(fen, settings.depth)
                    val san = if (index == 0) null else game.moves.getOrNull(index - 1)?.san
                    if (terminal != null) {
                        computed.add(terminal)
                        val best = terminal.best
                        diagnostics?.log(
                            TAG,
                            formatPositionLine(
                                PositionLogLine(index, san, settings.depth, settings.depth, 0, 0, false, scoreText(best?.scoreCp, best?.mateIn)),
                            ),
                        )
                    } else {
                        val startedAt = nowMs()
                        var shownDepth = 0
                        val searching = AnalysisProgress(
                            phase = AnalysisPhase.ANALYZING_MOVES,
                            currentMoveIndex = index,
                            totalMoves = total,
                            fractionComplete = 0.3f + 0.68f * index / total,
                            targetDepth = settings.depth,
                            positionStartedAtMs = startedAt,
                            runNodes = runNodes,
                            runSearchMs = runSearchMs,
                            runPositionsSearched = runPositions,
                        )
                        onProgress(searching)
                        engine.setPosition(fen = fen)
                        val result = engine.analyze(
                            multiPv = settings.multiPv,
                            depth = settings.depth,
                            movetimeMs = budget.movetimeMs,
                            nodes = budget.nodes,
                            onProgress = { p ->
                                // One update per new depth: the screen shows "depth 15 of 18", nothing finer.
                                if (p.depth != shownDepth) {
                                    shownDepth = p.depth
                                    onProgress(searching.copy(searchDepth = p.depth))
                                }
                            },
                        )
                        val eval = toPositionEval(fen, result, settings.depth)
                        computed.add(eval)
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
                                    san = san,
                                    requestedDepth = settings.depth,
                                    reachedDepth = result.depth,
                                    nodes = result.nodes,
                                    timeMs = wallMs,
                                    capped = eval.isCapped,
                                    score = scoreText(best?.scoreCp, best?.mateIn),
                                ),
                            ),
                        )
                    }
                    onProgress(
                        AnalysisProgress(
                            phase = AnalysisPhase.ANALYZING_MOVES,
                            currentMoveIndex = index + 1,
                            totalMoves = total,
                            fractionComplete = 0.3f + 0.68f * (index + 1) / total,
                            runNodes = runNodes,
                            runSearchMs = runSearchMs,
                            runPositionsSearched = runPositions,
                        )
                    )
                    // Checkpoint so a process kill (rather than a clean cancel) leaves the work
                    // recoverable: Android may kill a backgrounded app during a long Deep analysis.
                    if ((index + 1) % CHECKPOINT_EVERY_PLIES == 0) {
                        withContext(NonCancellable) {
                            gameRepository.savePartialEvalCache(cacheKey, computed, runPrefix)
                        }
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                // Persist what was computed before giving up. NonCancellable is required: this
                // coroutine is already cancelled, so an ordinary suspend call here would throw
                // immediately and we would lose the very work we are trying to save.
                withContext(NonCancellable) {
                    gameRepository.savePartialEvalCache(cacheKey, computed, runPrefix)
                }
                diagnostics?.log(TAG, "cancelled after ${computed.size} of $total positions (checkpoint saved)")
                return Outcome.Cancelled
            } catch (e: Exception) {
                withContext(NonCancellable) {
                    gameRepository.savePartialEvalCache(cacheKey, computed, runPrefix)
                }
                diagnostics?.error(TAG, "engine failed at position ${computed.size} of $total", e)
                return Outcome.EngineError(Failure.ANALYSIS, e.message)
            }
            evals = computed
            gameRepository.saveEvalCache(cacheKey, computed, runPrefix)
            // The full cache supersedes the prefix; drop it so it cannot go stale.
            gameRepository.clearPartialEvalCache(cacheKey, runPrefix)
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
        diagnostics?.log(TAG, "analysis done: ${allEvals.size} positions, $capped capped")
        return Outcome.Success(game, report, userColor, otherGamesInFile = games.size - 1, cappedPositions = capped)
    }

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
