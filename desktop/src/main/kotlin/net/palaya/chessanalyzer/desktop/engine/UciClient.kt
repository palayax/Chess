package net.palaya.chessanalyzer.desktop.engine

import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Any failure talking to the engine process: it died, timed out, or refused a command. */
class EngineException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/**
 * Drives the official Stockfish Windows binary over UCI via [ProcessBuilder] — the desktop
 * counterpart of `engine/.../StockfishEngine.kt` (docs/PC_PRODUCER_DESIGN.md §4).
 *
 * **stdin stays open until [close].** Stockfish treats EOF on stdin as `quit` and silently aborts a
 * running search: the first PC smoke test printed `bestmove a2a3` with no search output at all
 * (RUN_LOG, Round 12). So the writer is a [BufferedWriter] that is flushed after every command and
 * never closed until `quit`.
 *
 * Output is drained by a daemon thread into an unbounded queue, for the same reason
 * `StockfishEngine` uses an unlimited channel (`StockfishEngine.kt:50-55`): a response must never
 * race the caller starting to read it, and a full OS pipe must never stall the engine.
 *
 * Not thread-safe: one caller issues commands sequentially, exactly like UCI itself.
 */
class UciClient(
    private val binary: Path,
    /** Optional sink for every raw line in both directions (">> cmd", "<< output"). */
    private val transcript: ((String) -> Unit)? = null,
) : AutoCloseable {

    private val process: Process
    private val writer: BufferedWriter
    private val output = LinkedBlockingQueue<String>()
    private val readerThread: Thread
    private var currentMultiPv = 1
    private var closed = false

    /** `id name ...` from the `uci` handshake, e.g. "Stockfish 19". */
    var engineName: String = "unknown"
        private set

    /** Number of `go` commands sent — lets tests prove a terminal position was never searched. */
    var searchesIssued: Int = 0
        private set

    /** How many parseable `info` lines the last [analyze] call received. */
    var lastSearchInfoLines: Int = 0
        private set

    /** Last `nps` the engine reported in the previous search, if any. */
    var lastNps: Long? = null
        private set

    init {
        if (!Files.isRegularFile(binary)) {
            throw EngineException("Stockfish binary not found: $binary")
        }
        process = try {
            ProcessBuilder(binary.toString()).redirectErrorStream(true).start()
        } catch (e: IOException) {
            throw EngineException("Could not start Stockfish at $binary: ${e.message}", e)
        }
        writer = BufferedWriter(OutputStreamWriter(process.outputStream, Charsets.US_ASCII))
        readerThread = Thread({
            try {
                BufferedReader(InputStreamReader(process.inputStream, Charsets.US_ASCII)).use { r ->
                    while (true) {
                        val line = r.readLine() ?: break
                        output.put(line)
                    }
                }
            } catch (_: IOException) {
                // Stream closed under us during shutdown; the EOF marker below still goes out.
            } finally {
                output.put(EOF)
            }
        }, "stockfish-stdout").apply { isDaemon = true; start() }
    }

    /**
     * `uci` → `uciok`; Threads/Hash/MultiPV; `ucinewgame`; `isready` → `readyok` (design §4).
     * MultiPV is set here once and afterwards only when it changes, as `StockfishEngine.kt:207-210`.
     */
    fun start(threads: Int, hashMb: Int, multiPv: Int) {
        send("uci")
        readUntil(HANDSHAKE_TIMEOUT_MS) { line ->
            if (line.startsWith("id name ")) engineName = line.removePrefix("id name ").trim()
            line == "uciok"
        }
        setOption("Threads", threads.toString())
        setOption("Hash", hashMb.toString())
        setOption("MultiPV", multiPv.toString())
        currentMultiPv = multiPv
        newGame()
    }

    fun setOption(name: String, value: String) = send("setoption name $name value $value")

    fun newGame() {
        send("ucinewgame")
        isReady()
    }

    fun isReady() {
        send("isready")
        readUntil(HANDSHAKE_TIMEOUT_MS) { it == "readyok" }
    }

    fun setPosition(fen: String) = send("position fen $fen")

    /**
     * One search of the current position, mirroring `StockfishEngine.analyze` (`:196-249`):
     * `go depth D movetime T` when both are given ("whichever limit is hit first"), the latest
     * `info` per MultiPV slot kept until `bestmove`, result depth = deepest line.
     *
     * @param timeoutMs wall-clock guard on waiting for `bestmove`; on expiry a `stop` is sent and
     *   the bestmove drained, then [EngineException] is thrown.
     */
    fun analyze(multiPv: Int, depth: Int?, movetimeMs: Long?, timeoutMs: Long = DEFAULT_SEARCH_TIMEOUT_MS): AnalysisResult {
        require(depth != null || movetimeMs != null) { "Provide depth and/or movetimeMs" }
        require(multiPv >= 1) { "multiPv must be >= 1" }
        if (multiPv != currentMultiPv) {
            setOption("MultiPV", multiPv.toString())
            currentMultiPv = multiPv
        }
        val goCmd = buildString {
            append("go")
            depth?.let { append(" depth ").append(it) }
            movetimeMs?.let { append(" movetime ").append(it) }
        }
        val collector = SearchCollector()
        send(goCmd)
        searchesIssued++
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (true) {
            val remaining = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())
            val line = if (remaining > 0) output.poll(remaining, TimeUnit.MILLISECONDS) else null
            if (line == null) {
                // Timed out: stop the search and drain its bestmove so the pipe stays clean.
                send("stop")
                readUntil(HANDSHAKE_TIMEOUT_MS) { UciLineParser.parseBestMove(it) != null }
                throw EngineException("No bestmove within $timeoutMs ms for '$goCmd'")
            }
            if (line === EOF) throw died("while searching ('$goCmd')")
            transcript?.invoke("<< $line")
            val result = collector.accept(line)
            if (result != null) {
                lastSearchInfoLines = collector.infoLines
                lastNps = collector.lastNps
                return result
            }
        }
    }

    /** True while the engine process is running. */
    val isAlive: Boolean get() = process.isAlive

    /** `quit`, then close stdin and wait; force-kill if it lingers. Idempotent. */
    override fun close() {
        if (closed) return
        closed = true
        try {
            if (process.isAlive) send("quit")
        } catch (_: Exception) {
            // Already gone.
        }
        try {
            writer.close()
        } catch (_: IOException) {
        }
        if (!process.waitFor(5, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            process.waitFor(5, TimeUnit.SECONDS)
        }
    }

    private fun send(cmd: String) {
        if (!process.isAlive) throw died("before '$cmd'")
        transcript?.invoke(">> $cmd")
        try {
            writer.write(cmd)
            writer.newLine()
            writer.flush()
        } catch (e: IOException) {
            throw EngineException("Writing '$cmd' to Stockfish failed: ${e.message}", e)
        }
    }

    private fun readUntil(timeoutMs: Long, predicate: (String) -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (true) {
            val remaining = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())
            val line = (if (remaining > 0) output.poll(remaining, TimeUnit.MILLISECONDS) else null)
                ?: throw EngineException("Stockfish did not answer within $timeoutMs ms")
            if (line === EOF) throw died("during handshake")
            transcript?.invoke("<< $line")
            if (predicate(line)) return
        }
    }

    private fun died(context: String): EngineException {
        val code = try {
            if (process.waitFor(2, TimeUnit.SECONDS)) process.exitValue().toString() else "still running"
        } catch (_: Exception) {
            "unknown"
        }
        return EngineException("Stockfish exited unexpectedly $context (exit code $code)")
    }

    companion object {
        /** Identity-compared end-of-stream marker from the reader thread. */
        private val EOF = String(charArrayOf('\u0000'))
        const val HANDSHAKE_TIMEOUT_MS = 30_000L
        const val DEFAULT_SEARCH_TIMEOUT_MS = 10 * 60_000L
    }
}

/**
 * Folds one search's output into an [AnalysisResult], exactly as `StockfishEngine.analyze`
 * (`:218-232`): the *latest* `info` per MultiPV slot is kept until `bestmove`, lines are ordered
 * by slot, and the result depth is the deepest line. Split out of [UciClient] so a captured
 * transcript can be replayed through the same code in a test.
 */
class SearchCollector {
    private val latestByPv = LinkedHashMap<Int, EngineLine>()

    /** Parseable `info` lines seen so far. */
    var infoLines = 0
        private set

    /** The most recent `nps` reported. */
    var lastNps: Long? = null
        private set

    /** Feeds one output line; returns the result once [line] is the `bestmove`, else null. */
    fun accept(line: String): AnalysisResult? {
        UciLineParser.parseBestMove(line)?.let { (best, ponder) ->
            val ordered = latestByPv.values.sortedBy { it.multiPv }
            val maxDepth = ordered.maxOfOrNull { it.depth } ?: 0
            return AnalysisResult(ordered, best, ponder, maxDepth)
        }
        UciLineParser.parseInfo(line)?.let { info ->
            latestByPv[info.multiPv] = info
            infoLines++
            info.nps?.let { lastNps = it }
        }
        return null
    }
}
