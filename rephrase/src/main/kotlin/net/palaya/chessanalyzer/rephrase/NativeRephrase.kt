package net.palaya.chessanalyzer.rephrase

/** The native calls [LlamaRephraser] makes, behind an interface so the host tests can fake them. */
interface RephraseNative {
    /** 0 when the model cannot be loaded. */
    fun load(path: String, threads: Int, ctxTokens: Int): Long
    fun free(handle: Long)
    /** UTF-8 bytes of the generated text, null on failure or cancellation. */
    fun complete(handle: Long, prefix: String, suffix: String, maxTokens: Int): ByteArray?
    fun cancel(handle: Long)
    fun tokenCount(handle: Long, text: String): Int
    /** [prefixTokens, prefixReused (0/1), promptTokens, promptMicros, generatedTokens, generatedMicros]. */
    fun lastStats(handle: Long): LongArray
}

/**
 * The JNI surface of librephrase.so (src/main/cpp/jni_bridge.cpp). The symbol names are
 * `Java_net_palaya_chessanalyzer_rephrase_NativeRephrase_native*`: rephrase/consumer-rules.pro keeps this class and
 * its natives through R8. One model per process (the owner of the handle is the app's backend holder), and calls
 * on a handle must not overlap; [cancel] may come from any thread.
 */
object NativeRephrase : RephraseNative {

    /** True once the library is loaded; false when it is not packaged for this ABI or failed to load. */
    val loaded: Boolean by lazy {
        try {
            System.loadLibrary("rephrase")
            true
        } catch (e: UnsatisfiedLinkError) {
            false
        }
    }

    override fun load(path: String, threads: Int, ctxTokens: Int): Long = if (!loaded) 0L else nativeLoad(path, threads, ctxTokens)
    override fun free(handle: Long) = nativeFree(handle)
    override fun complete(handle: Long, prefix: String, suffix: String, maxTokens: Int): ByteArray? = nativeComplete(handle, prefix, suffix, maxTokens)
    override fun cancel(handle: Long) = nativeCancel(handle)
    override fun tokenCount(handle: Long, text: String): Int = nativeTokenCount(handle, text)
    override fun lastStats(handle: Long): LongArray = nativeLastStats(handle) ?: LongArray(6)

    private external fun nativeLoad(path: String, threads: Int, ctxTokens: Int): Long
    private external fun nativeFree(handle: Long)
    private external fun nativeComplete(handle: Long, prefix: String, suffix: String, maxTokens: Int): ByteArray?
    private external fun nativeCancel(handle: Long)
    private external fun nativeTokenCount(handle: Long, text: String): Int
    private external fun nativeLastStats(handle: Long): LongArray?
}
