package net.palaya.chessanalyzer.data

import android.content.res.AssetManager
import java.io.File
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.palaya.chessanalyzer.engine.BundledNetProvider
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
 * `analyze()` — before a verified NNUE net exists on disk. [BundledNetProvider.ensureNet] copies
 * it out of the APK's assets on first use and verifies it; on a device that already has a valid
 * net it returns without touching the file (see [FirstRunSetup], which normally does the copy up
 * front so the user sees a progress bar for it).
 */
class EngineController(filesDir: File, assets: AssetManager) {

    private val engine = StockfishEngine()
    private val netProvider = BundledNetProvider(filesDir, assets)
    private val prepMutex = Mutex()

    @Volatile private var isReady = false

    /** True once [ensureReady] has completed successfully at least once. */
    val ready: Boolean get() = isReady

    /** Cheap, no hashing: true when a net of exactly the pinned size is already in `filesDir`. */
    fun isNetPresent(): Boolean = netProvider.isNetPresent()

    /**
     * Copies the net out of the APK if it is missing or damaged and returns the verified file.
     * Idempotent and cheap once done. Used by [FirstRunSetup] and, inside [ensureReady], as the
     * structural gate in front of the engine.
     */
    suspend fun ensureNet(onProgress: (Float) -> Unit = {}): File = netProvider.ensureNet(onProgress)

    /**
     * Makes sure the net is installed, starts the native engine, configures it, and loads the net
     * — all guarded by [prepMutex] so concurrent callers (e.g. a rotated Activity re-collecting
     * the same in-flight analysis) don't double-start the process. Idempotent: a second call after
     * success returns the same engine instantly.
     */
    suspend fun ensureReady(
        threads: Int = defaultThreads(),
        hashMb: Int = defaultHashMb(),
    ): StockfishEngine = prepMutex.withLock {
        if (!isReady) {
            val net = netProvider.ensureNet()
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
