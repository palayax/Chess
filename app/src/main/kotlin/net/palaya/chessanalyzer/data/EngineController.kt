package net.palaya.chessanalyzer.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import net.palaya.chessanalyzer.data.models.EngineBusyException
import net.palaya.chessanalyzer.data.models.NetActivationGate
import net.palaya.chessanalyzer.engine.NetNotInstalledException
import net.palaya.chessanalyzer.engine.NetStore
import net.palaya.chessanalyzer.engine.StockfishEngine

/**
 * Owns the single [StockfishEngine] instance for the whole app.
 *
 * Per CLAUDE.md / RUN_LOG: the JNI bridge `dup2`s the process-global stdin/stdout onto pipes,
 * so two concurrent [StockfishEngine]s would corrupt each other. This class exists specifically
 * to make "exactly one engine, ever" structurally true — it is held as a single instance on
 * [net.palaya.chessanalyzer.ChessAnalyzerApplication] (outliving any one Activity/ViewModel, so
 * it survives configuration changes) and every caller goes through [ensureReady] rather than
 * constructing a [StockfishEngine] itself.
 *
 * [ensureReady] is also the net gate: the app must not start the engine — let alone call
 * `analyze()` — before a verified NNUE net exists on disk. The net is downloaded once by the Setup
 * flow (`ModelSetup`, docs/MODEL_DOWNLOAD_DESIGN.md); here it is only verified
 * ([NetStore.verifiedNetOrNull]: pinned size and full SHA-256) and, when it is missing or damaged,
 * [ensureReady] throws [NetNotInstalledException] (the analysis then reports `SETUP_REQUIRED`). Nothing
 * is ever downloaded or copied here. The `setEvalFile()` guards in [StockfishEngine] apply on top.
 *
 * **Switching the net (D2e, docs/MODEL_DOWNLOAD_DESIGN.md §4.2).** An update's net is tried on THIS engine
 * ([switchNet] / [trialLocked]): no second engine is ever made. The switch hands the engine only what
 * `NetStore.verifiedNetOrNull()` returns (the active identity, full SHA-256) and runs a depth-1 search.
 * Analyses register with [beginAnalysis]/[endAnalysis]; [exclusive] refuses to start while one runs and
 * keeps new ones waiting until it is done (both go through [prepMutex]).
 */
class EngineController(
    /** Where the downloaded net lives (`filesDir/nets/`). */
    val netStore: NetStore,
    /**
     * Receives the engine's own diagnostics: every "info string" line (net loaded, threads, errors)
     * and anything that is not UCI search output. For the diagnostic log; must not block.
     */
    private val engineLog: (String) -> Unit = {},
) : NetActivationGate {

    private val engine = StockfishEngine()
    private val logScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val prepMutex = Mutex()

    @Volatile private var isReady = false
    private var logCollectorStarted = false
    private var engineStarted = false
    private val inFlight = AtomicInteger(0)
    private val _analysisInFlight = MutableStateFlow(false)

    /** True while an analysis that needs the engine runs (Settings disables "Check for updates" then). */
    val analysisInFlight: StateFlow<Boolean> = _analysisInFlight.asStateFlow()

    /** The file name of the net loaded into the engine, null before the first successful load. */
    @Volatile var loadedNetName: String? = null
        private set

    /** True once [ensureReady] has completed successfully at least once. */
    val ready: Boolean get() = isReady

    /** Cheap, no hashing: true when a net of exactly the pinned size is installed. */
    fun isNetPresent(): Boolean = netStore.activeNetOrNull() != null

    /**
     * Checks the downloaded net (size and SHA-256), starts the native engine, configures it, and
     * loads the net — all guarded by [prepMutex] so concurrent callers (e.g. a rotated Activity
     * re-collecting the same in-flight analysis) don't double-start the process. Idempotent: a second
     * call after success returns the same engine instantly.
     *
     * @throws NetNotInstalledException when no verified net is installed (Setup has not finished);
     *   the engine is not started in that case
     */
    suspend fun ensureReady(
        threads: Int = defaultThreads(),
        hashMb: Int = defaultHashMb(),
    ): StockfishEngine = prepMutex.withLock {
        if (!isReady) {
            val net = withContext(Dispatchers.IO) { netStore.verifiedNetOrNull() }
                ?: throw NetNotInstalledException()
            startEngineLocked(threads, hashMb)
            engine.setEvalFile(net.absolutePath)
            engine.newGame()
            loadedNetName = net.name
            isReady = true
        }
        engine
    }

    /** Starts the native engine once per process: the process, the log feed, `uci` and the options. */
    private suspend fun startEngineLocked(threads: Int = defaultThreads(), hashMb: Int = defaultHashMb()) {
        if (engineStarted) return
        engine.start()
        // Best-effort feed (see StockfishEngine.lines): the search output itself is logged per
        // position by AnalysisService; here only what the engine says about itself.
        if (!logCollectorStarted) {
            logCollectorStarted = true
            logScope.launch {
                // Stockfish repeats its set-up lines ("Using 4 threads", "NNUE evaluation using
                // ...") at every `go`: each distinct line is logged once per process.
                val seen = HashSet<String>()
                engine.lines.collect { line ->
                    if (isEngineDiagnostic(line) && (seen.size < MAX_DISTINCT_ENGINE_LINES && seen.add(line))) engineLog(line)
                }
            }
        }
        engine.uci()
        engine.setOption("Threads", threads.toString())
        engine.setOption("Hash", hashMb.toString())
        engineStarted = true
    }

    /**
     * An analysis that needs the engine starts. Waits while a net switch runs ([exclusive]); pair every
     * call with [endAnalysis] in a `finally`.
     */
    suspend fun beginAnalysis() {
        prepMutex.withLock {
            inFlight.incrementAndGet()
            _analysisInFlight.value = true
        }
    }

    fun endAnalysis() {
        if (inFlight.decrementAndGet() <= 0) {
            inFlight.set(0)
            _analysisInFlight.value = false
        }
    }

    /** [NetActivationGate]: holds the engine's preparation lock for [block]; refuses while an analysis runs. */
    override suspend fun <T> exclusive(block: suspend () -> T): T = prepMutex.withLock {
        if (inFlight.get() > 0) throw EngineBusyException()
        block()
    }

    /**
     * [NetActivationGate]: only inside [exclusive]. Loads `netStore.verifiedNetOrNull()` (whatever the
     * active identity now is: the update being tried, or the old net when rolling back) into the one engine
     * through the unchanged `setEvalFile` guards, then searches the start position to depth 1. A search
     * that returns no move throws. If the engine cannot load the net it `exit()`s: the journal handles that.
     */
    override suspend fun trialLocked(): String {
        isReady = false
        val net = withContext(Dispatchers.IO) { netStore.verifiedNetOrNull() } ?: throw NetNotInstalledException()
        startEngineLocked()
        engine.setEvalFile(net.absolutePath)
        engine.newGame()
        engine.setPosition(fen = null)
        val result = engine.analyze(multiPv = 1, depth = 1)
        check(result.bestMoveUci.isNotBlank() && !result.isTerminal && result.lines.isNotEmpty()) {
            "the depth-1 trial search gave no move (${result.bestMoveUci})"
        }
        engine.newGame()
        loadedNetName = net.name
        isReady = true
        return "${net.name}: bestmove ${result.bestMoveUci} at depth ${result.depth}, ${result.nodes} nodes"
    }

    /** Re-loads the active net into the engine with a depth-1 check ([exclusive] + [trialLocked]). */
    suspend fun switchNet(): String = exclusive { trialLocked() }

    /** The engine instance, if [ensureReady] has already succeeded — null otherwise. */
    fun engineOrNull(): StockfishEngine? = if (isReady) engine else null

    /** Tears down the native process. Called from Application.onTerminate in practice (best-effort). */
    fun shutdown() {
        if (isReady || engineStarted) {
            engine.shutdown()
            isReady = false
            engineStarted = false
        }
    }

    companion object {
        /** Distinct engine diagnostic lines logged per process, at most (a runaway engine cannot flood the log). */
        const val MAX_DISTINCT_ENGINE_LINES = 200

        /**
         * True for an engine line worth logging: "info string ..." and anything that is not routine
         * UCI chatter (search info, bestmove, uciok/readyok, id/option lists).
         */
        fun isEngineDiagnostic(line: String): Boolean {
            val t = line.trim()
            if (t.isEmpty()) return false
            if (t.startsWith("info string")) return true
            val routine = listOf("info ", "bestmove", "uciok", "readyok", "id ", "option ")
            return routine.none { t.startsWith(it) }
        }

        /** Threads sized for a phone: leave headroom for the UI thread and system work. */
        fun defaultThreads(): Int = (Runtime.getRuntime().availableProcessors()).coerceIn(1, 4)

        /** 64-128MB hash, sized conservatively for a phone's shared RAM budget. */
        fun defaultHashMb(): Int = 96
    }
}
