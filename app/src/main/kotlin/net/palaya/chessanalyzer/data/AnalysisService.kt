package net.palaya.chessanalyzer.data

import android.content.Context
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
import net.palaya.chessanalyzer.engine.AnalysisResult
import net.palaya.chessanalyzer.engine.BundledNetDamagedException
import net.palaya.chessanalyzer.engine.InsufficientNetStorageException
import net.palaya.chessanalyzer.ui.model.AnalysisPhase
import net.palaya.chessanalyzer.ui.model.AnalysisProgress
import net.palaya.chessanalyzer.ui.model.EngineSettings

/**
 * The seam between raw PGN text and a finished [CoreGameReport]: parse -> pick game/user color
 * -> one-time setup -> ensure engine+net -> analyze every ply (cached) -> [GameAnalyzer]. This is the "Analysis
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
    private val firstRunSetup: FirstRunSetup,
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
        /** Not enough free space for the one-time setup (net and voice copied out of the APK). */
        SETUP_STORAGE,
        /** The engine or voice files bundled in the APK failed verification: reinstall the app. */
        SETUP_DAMAGED,
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
            return Outcome.ParseError(Failure.PARSE, e.message)
        } catch (e: Exception) {
            return Outcome.ParseError(Failure.PARSE, e.message)
        }
        if (games.isEmpty()) return Outcome.ParseError(Failure.NO_GAMES)
        val game = games.getOrElse(gameIndex) { games[0] }
        val userColor = detectUserColor(game, username)

        // One-time setup (net + voice copied out of the APK) runs after the parse, so a bad paste
        // fails fast, and BEFORE the eval-cache lookup, so a game reopened from cache after an
        // upgrade still gets the voice installed. Returns at once, with no progress, when done.
        try {
            val setup = firstRunSetup.ensure { fraction ->
                onProgress(
                    AnalysisProgress(
                        phase = AnalysisPhase.FIRST_RUN_SETUP,
                        fractionComplete = 0.02f + fraction * 0.28f,
                    )
                )
            }
            when (setup) {
                SetupResult.Done -> Unit
                is SetupResult.InsufficientStorage ->
                    return Outcome.EngineError(Failure.SETUP_STORAGE, "needs ${setup.neededBytes} bytes")
                is SetupResult.Damaged -> return Outcome.EngineError(Failure.SETUP_DAMAGED, setup.detail)
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            return Outcome.Cancelled
        } catch (e: Exception) {
            return Outcome.EngineError(Failure.ENGINE_PREPARE, e.message)
        }

        val cacheKey = gameRepository.cacheKey(pgnText, settings.depth, settings.multiPv)
        var evals = gameRepository.loadEvalCache(cacheKey)

        if (evals == null) {
            try {
                onProgress(AnalysisProgress(phase = AnalysisPhase.PREPARING_ENGINE, fractionComplete = 0.02f))
                engineController.ensureReady(
                    threads = EngineController.defaultThreads(),
                    hashMb = EngineController.defaultHashMb(),
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                return Outcome.Cancelled
            } catch (e: InsufficientNetStorageException) {
                return Outcome.EngineError(Failure.SETUP_STORAGE, e.message)
            } catch (e: BundledNetDamagedException) {
                return Outcome.EngineError(Failure.SETUP_DAMAGED, e.message)
            } catch (e: Exception) {
                return Outcome.EngineError(Failure.ENGINE_PREPARE, e.message)
            }
            val engine = engineController.engineOrNull()
                ?: return Outcome.EngineError(Failure.ENGINE_START)

            val positions = plyFens(game)
            val total = positions.size

            // Resume from a previous cancelled/killed run. The cache key already encodes the PGN,
            // depth and MultiPV, so any prefix stored under it was computed with identical
            // settings — but verify FEN alignment per index anyway rather than trusting the key,
            // because feeding mismatched evals into the analyzer would silently produce a wrong
            // report rather than an error.
            val resumable = gameRepository.loadPartialEvalCache(cacheKey).orEmpty()
            val computed = ArrayList<PositionEval>(total)
                .apply { addAll(usableResumePrefix(positions, resumable)) }
            val startIndex = computed.size
            if (startIndex > 0) {
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
                    if (terminal != null) {
                        computed.add(terminal)
                    } else {
                        engine.setPosition(fen = fen)
                        val result = engine.analyze(multiPv = settings.multiPv, depth = settings.depth)
                        computed.add(toPositionEval(fen, result, settings.depth))
                    }
                    onProgress(
                        AnalysisProgress(
                            phase = AnalysisPhase.ANALYZING_MOVES,
                            currentMoveIndex = index + 1,
                            totalMoves = total,
                            fractionComplete = 0.3f + 0.68f * (index + 1) / total,
                        )
                    )
                    // Checkpoint periodically so a process kill (rather than a clean cancel) still
                    // leaves most of the work recoverable.
                    if ((index + 1) % CHECKPOINT_EVERY_PLIES == 0) {
                        withContext(NonCancellable) {
                            gameRepository.savePartialEvalCache(cacheKey, computed)
                        }
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                // Persist what was computed before giving up. NonCancellable is required: this
                // coroutine is already cancelled, so an ordinary suspend call here would throw
                // immediately and we would lose the very work we are trying to save.
                withContext(NonCancellable) {
                    gameRepository.savePartialEvalCache(cacheKey, computed)
                }
                return Outcome.Cancelled
            } catch (e: Exception) {
                withContext(NonCancellable) {
                    gameRepository.savePartialEvalCache(cacheKey, computed)
                }
                return Outcome.EngineError(Failure.ANALYSIS, e.message)
            }
            evals = computed
            gameRepository.saveEvalCache(cacheKey, computed)
            // The full cache supersedes the prefix; drop it so it cannot go stale.
            gameRepository.clearPartialEvalCache(cacheKey)
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

        val book = openingBook()
        val report = withContext(Dispatchers.Default) {
            val see = StaticExchangeEvaluator()
            val analyzer = GameAnalyzer(MoveClassifier(see), MotifDetector(see))
            analyzer.analyze(game, evals, userColor, book)
        }

        onProgress(AnalysisProgress(phase = AnalysisPhase.DONE, fractionComplete = 1f))
        return Outcome.Success(game, report, userColor, otherGamesInFile = games.size - 1)
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
        return PositionEval(fen = fen, lines = listOf(line), depth = depth)
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

    private fun toPositionEval(fen: String, result: AnalysisResult, depth: Int): PositionEval {
        val lines = result.lines.map {
            EngineLineInput(multiPv = it.multiPv, scoreCp = it.scoreCp, mateIn = it.mateIn, depth = it.depth, pvUci = it.pvUci)
        }
        val effectiveLines = if (lines.isEmpty()) {
            listOf(EngineLineInput(multiPv = 1, scoreCp = null, mateIn = null, depth = depth, pvUci = listOf(result.bestMoveUci)))
        } else lines
        return PositionEval(fen = fen, lines = effectiveLines, depth = result.depth)
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
         * Plies between partial-cache checkpoints. Small enough that a process kill loses only
         * seconds of engine work, large enough that the JSON write is not a per-ply cost.
         */
        private const val CHECKPOINT_EVERY_PLIES = 5


        private val bookLock = Any()
        @Volatile private var cachedBook: OpeningBook? = null
    }
}
