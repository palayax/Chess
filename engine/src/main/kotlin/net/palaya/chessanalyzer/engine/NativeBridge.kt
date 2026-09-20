package net.palaya.chessanalyzer.engine

/**
 * Thin 1:1 wrapper over the JNI functions exported by libstockfish.so
 * (see engine/src/main/cpp/jni_bridge.cpp). Not for direct use outside this
 * module — [StockfishEngine] is the public, coroutine-friendly API.
 *
 * The native side runs Stockfish's real UCI loop (Stockfish::UCIEngine::loop())
 * on a detached pthread against two pipes dup2'd onto the process's
 * stdin/stdout; [nativeWriteLine] and [nativeReadLine] are the two ends of
 * that pipe pair as seen from the JVM.
 */
internal object NativeBridge {
    init {
        System.loadLibrary("stockfish")
    }

    /** Starts the engine: redirects stdin/stdout to pipes and launches the UCI loop thread. */
    external fun nativeInit()

    /** Sends one UCI command line to the engine (a trailing '\n' is added by the native side). */
    external fun nativeWriteLine(line: String)

    /**
     * Blocking read of the next line of engine output. Returns null once the
     * engine has been shut down (or its output pipe hit EOF) — callers
     * should stop reading when they see null rather than busy-loop.
     */
    external fun nativeReadLine(): String?

    /** Sends "quit" to the engine and tears down the pipes. Safe to call more than once. */
    external fun nativeShutdown()
}
