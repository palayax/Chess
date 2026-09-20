package net.palaya.chessanalyzer.engine

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Coroutine-friendly wrapper around the native Stockfish UCI engine
 * ([NativeBridge]).
 *
 * Usage:
 * ```
 * val engine = StockfishEngine()
 * engine.start()
 * engine.uci()
 * engine.isReady()
 * engine.setEvalFile(netFile.absolutePath)
 * engine.newGame()
 * engine.setPosition(fen = null, moves = listOf("e2e4"))
 * val result = engine.analyze(multiPv = 3, depth = 18)
 * engine.shutdown()
 * ```
 *
 * All UCI commands are serialized through [commandMutex]: only one command
 * is "in flight" (waiting on engine output) at a time, matching the UCI
 * protocol's own single-conversation model. This makes the engine safe to
 * reuse sequentially across many positions, but it is not designed for
 * concurrent callers issuing commands at once — callers coordinate through
 * a single [StockfishEngine] instance per active analysis session, per the
 * one-native-stdin/stdout-per-process design of [NativeBridge].
 */
class StockfishEngine {

    private val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val commandMutex = Mutex()

    // Every line read from the engine goes onto this unlimited channel. It's
    // unlimited (never drops, never suspends the reader) specifically so
    // that a command's response can never race the command function's call
    // to receive() — unlike e.g. a SharedFlow, values posted before a
    // consumer starts receiving are not lost.
    private val engineOutput = Channel<String>(capacity = Channel.UNLIMITED)

    private val _rawLines = MutableSharedFlow<String>(extraBufferCapacity = 256)

    /**
     * Best-effort feed of every raw line the engine writes (for logging/
     * debugging UIs). Unlike [engineOutput], this is a broadcast: lines can
     * be dropped for slow/absent collectors, so it must not be relied on
     * for protocol correctness — only the internal command functions do that.
     */
    val lines: SharedFlow<String> get() = _rawLines.asSharedFlow()

    @Volatile private var started = false
    private var readerJob: Job? = null
    private var currentMultiPv: Int = 1

    /** Starts the native engine process and its background output reader. Idempotent. */
    fun start() {
        if (started) return
        started = true
        NativeBridge.nativeInit()
        readerJob = engineScope.launch {
            while (isActive) {
                val line = NativeBridge.nativeReadLine() ?: break
                _rawLines.tryEmit(line)
                engineOutput.trySend(line)
            }
            engineOutput.close()
        }
    }

    /** Sends "uci" and suspends until the engine replies "uciok". */
    suspend fun uci() {
        commandMutex.withLock {
            ensureStarted()
            NativeBridge.nativeWriteLine("uci")
            waitForLine { it.trim() == "uciok" }
        }
    }

    /** Sends "isready" and suspends until the engine replies "readyok". Returns true. */
    suspend fun isReady(): Boolean {
        commandMutex.withLock {
            ensureStarted()
            NativeBridge.nativeWriteLine("isready")
            waitForLine { it.trim() == "readyok" }
        }
        return true
    }

    /** Sends "setoption name <name> value <value>". */
    suspend fun setOption(name: String, value: String) {
        commandMutex.withLock {
            ensureStarted()
            NativeBridge.nativeWriteLine("setoption name $name value $value")
        }
    }

    /**
     * Points the engine at a downloaded NNUE net file (see [NetworkProvider]),
     * via the standard "EvalFile" UCI option.
     *
     * **This guard is not optional.** We build Stockfish with `NNUE_EMBEDDING_OFF`, so it has no
     * built-in net. If it is handed a path it cannot load, `nnue/network.cpp` prints
     * "The engine will be terminated now." and calls `exit(EXIT_FAILURE)` — and because the
     * engine runs *in our own process* on a JNI thread, that exit kills the entire app with no
     * exception to catch and nothing shown to the user. Prevention before the fact is the only
     * defence available, so we refuse to pass a path that does not look like a real net.
     *
     * @throws InvalidNetworkFileException if the file is missing, unreadable or the wrong size.
     */
    suspend fun setEvalFile(path: String) {
        val file = java.io.File(path)
        if (!file.isFile) {
            throw InvalidNetworkFileException("Network file does not exist: $path")
        }
        if (!file.canRead()) {
            throw InvalidNetworkFileException("Network file is not readable: $path")
        }
        // A truncated download is the realistic failure mode; anything far below the real net
        // size cannot possibly load, and passing it through would be fatal.
        if (file.length() < MIN_PLAUSIBLE_NET_BYTES) {
            throw InvalidNetworkFileException(
                "Network file looks truncated (${file.length()} bytes): $path"
            )
        }
        setOption("EvalFile", path)
        // Force the engine to actually parse the net now, while we are still in a position to
        // report a problem, rather than at the first `go` in the middle of an analysis run.
        isReady()
        evalFilePath = path
    }

    /** The net currently loaded, or null if none has been successfully set. */
    @Volatile
    var evalFilePath: String? = null
        private set

    /** Sends "ucinewgame", telling the engine to discard any cross-game state (e.g. TT). */
    suspend fun newGame() {
        commandMutex.withLock {
            ensureStarted()
            NativeBridge.nativeWriteLine("ucinewgame")
        }
    }

    /**
     * Sends a "position" command.
     *
     * @param fen FEN string, or null for the standard start position.
     * @param moves UCI moves to play from that position (e.g. "e2e4").
     */
    suspend fun setPosition(fen: String? = null, moves: List<String> = emptyList()) {
        commandMutex.withLock {
            ensureStarted()
            val base = if (fen != null) "position fen $fen" else "position startpos"
            val cmd = if (moves.isEmpty()) base else "$base moves ${moves.joinToString(" ")}"
            NativeBridge.nativeWriteLine(cmd)
        }
    }

    /**
     * Runs a search on the current position (set via [setPosition]) and suspends until the
     * engine's "bestmove" line arrives.
     *
     * At least one of [depth] or [movetimeMs] must be given. When both are given, both are
     * passed to the engine ("go depth D movetime T"), so the search stops at whichever limit
     * is hit first.
     *
     * Cancellable: if the calling coroutine is cancelled while a search is in flight, a "stop"
     * is sent to the engine and its (now-unwanted) bestmove is drained internally, so the pipe
     * is left in a clean state for the next command — cancellation does not leak a stray
     * "bestmove" line into a later call.
     */
    suspend fun analyze(
        multiPv: Int = 1,
        depth: Int? = null,
        movetimeMs: Long? = null,
    ): AnalysisResult {
        require(depth != null || movetimeMs != null) { "Provide depth and/or movetimeMs" }
        require(multiPv >= 1) { "multiPv must be >= 1" }
        // Searching without a loaded net is the other route into Stockfish's fatal exit path.
        // Fail as an ordinary Kotlin exception the caller can show the user.
        if (evalFilePath == null) {
            throw InvalidNetworkFileException(
                "No NNUE network loaded. Call setEvalFile() with a verified net before analysing."
            )
        }

        return commandMutex.withLock {
            ensureStarted()

            if (multiPv != currentMultiPv) {
                NativeBridge.nativeWriteLine("setoption name MultiPV value $multiPv")
                currentMultiPv = multiPv
            }

            val goCmd = buildString {
                append("go")
                depth?.let { append(" depth ").append(it) }
                movetimeMs?.let { append(" movetime ").append(it) }
            }

            val latestByPv = LinkedHashMap<Int, EngineLine>()
            var result: AnalysisResult? = null
            NativeBridge.nativeWriteLine(goCmd)
            try {
                while (result == null) {
                    val line = engineOutput.receive()
                    UciLineParser.parseBestMove(line)?.let { (best, ponder) ->
                        val orderedLines = latestByPv.values.sortedBy { it.multiPv }
                        val maxDepth = orderedLines.maxOfOrNull { it.depth } ?: 0
                        result = AnalysisResult(orderedLines, best, ponder, maxDepth)
                    }
                    if (result == null) {
                        UciLineParser.parseInfo(line)?.let { info -> latestByPv[info.multiPv] = info }
                    }
                }
                result!!
            } finally {
                if (result == null) {
                    // We got here via cancellation (or an engine crash closing the channel)
                    // before a bestmove arrived. Ask the engine to stop and drain its
                    // response so a stray "bestmove" doesn't corrupt the next command.
                    withContext(NonCancellable) {
                        NativeBridge.nativeWriteLine("stop")
                        while (true) {
                            val line = engineOutput.receiveCatching().getOrNull() ?: break
                            if (UciLineParser.parseBestMove(line) != null) break
                        }
                    }
                }
            }
        }
    }

    /**
     * Sends "stop" to interrupt an in-progress search. Fire-and-forget: does not itself wait
     * for the resulting "bestmove" — call this from outside the coroutine that's suspended in
     * [analyze] (that coroutine will observe the stop via the bestmove it unblocks).
     */
    fun stop() {
        if (started) NativeBridge.nativeWriteLine("stop")
    }

    /** Sends "quit", tears down the native engine and stops the background reader. Idempotent. */
    fun shutdown() {
        if (!started) return
        started = false
        NativeBridge.nativeShutdown()
        readerJob?.cancel()
        engineOutput.close()
        engineScope.cancel()
    }

    private suspend fun waitForLine(predicate: (String) -> Boolean) {
        while (coroutineContext.isActive) {
            val line = engineOutput.receive()
            if (predicate(line)) return
        }
    }

    private fun ensureStarted() {
        check(started) { "StockfishEngine.start() must be called before issuing commands" }
    }

    companion object {
        /**
         * Floor for a plausible NNUE net, in bytes. The real SF19 net is 98,511,183 bytes; this
         * is deliberately loose (it only has to catch truncated or empty downloads) so that a
         * future net of a different size does not trip it.
         */
        const val MIN_PLAUSIBLE_NET_BYTES: Long = 1_000_000L
    }
}

/**
 * Thrown instead of letting Stockfish terminate the process over a missing or corrupt net.
 * See [StockfishEngine.setEvalFile] for why this exists.
 */
class InvalidNetworkFileException(message: String) : IllegalStateException(message)
