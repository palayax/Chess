package net.palaya.chessanalyzer.data

import java.io.File
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.palaya.chessanalyzer.engine.EngineUpdateInfo
import net.palaya.chessanalyzer.engine.NetworkProvider
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
 * [ensureReady] is also the net-download gate: per the integration brief, the app must not
 * start the engine — let alone call `analyze()` — before a verified NNUE net exists on disk.
 * [NetworkProvider.ensureNet] downloads it only if it isn't already there (and it may already
 * be there: see the DONE-CONDITION note about pre-seeding the emulator's files dir), so on a
 * device that already has a valid net this resolves immediately with no network traffic.
 */
class EngineController(private val filesDir: File) {

    private val engine = StockfishEngine()
    private val networkProvider = NetworkProvider(filesDir)
    private val prepMutex = Mutex()

    @Volatile private var isReady = false

    /** True once [ensureReady] has completed successfully at least once. */
    val ready: Boolean get() = isReady

    /**
     * Downloads/verifies the net (if needed), starts the native engine, configures it, and
     * loads the net — all guarded by [prepMutex] so concurrent callers (e.g. a rotated Activity
     * re-collecting the same in-flight analysis) don't double-start the process. Idempotent:
     * a second call after success returns the same engine instantly.
     */
    suspend fun ensureReady(
        threads: Int = defaultThreads(),
        hashMb: Int = defaultHashMb(),
        onNetProgress: (Float) -> Unit = {},
    ): StockfishEngine = prepMutex.withLock {
        if (!isReady) {
            val net = networkProvider.ensureNet(onNetProgress)
            engine.start()
            engine.uci()
            engine.setOption("Threads", threads.toString())
            engine.setOption("Hash", hashMb.toString())
            engine.setEvalFile(net.absolutePath)
            engine.newGame()
            isReady = true
        }
        engine
    }

    /** The engine instance, if [ensureReady] has already succeeded — null otherwise. */
    fun engineOrNull(): StockfishEngine? = if (isReady) engine else null

    suspend fun checkForEngineUpdate(): EngineUpdateInfo? = networkProvider.checkForEngineUpdate()

    /** Tears down the native process. Called from Application.onTerminate in practice (best-effort). */
    fun shutdown() {
        if (isReady) {
            engine.shutdown()
            isReady = false
        }
    }

    companion object {
        /** Threads sized for a phone: leave headroom for the UI thread and system work. */
        fun defaultThreads(): Int = (Runtime.getRuntime().availableProcessors()).coerceIn(1, 4)

        /** 64-128MB hash, sized conservatively for a phone's shared RAM budget. */
        fun defaultHashMb(): Int = 96
    }
}
