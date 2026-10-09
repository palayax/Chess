package net.palaya.chessanalyzer.rephrase

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import net.palaya.chessanalyzer.core.text.ClaimChecker
import net.palaya.chessanalyzer.core.text.RephrasePrompt
import net.palaya.chessanalyzer.core.text.RephraseRequest
import net.palaya.chessanalyzer.core.text.RephraseResult
import net.palaya.chessanalyzer.core.text.RephraseVerdicts
import net.palaya.chessanalyzer.core.text.Rephraser
import java.io.File
import kotlin.coroutines.coroutineContext

/**
 * The llama.cpp backend (docs/LLM_REPHRASE_DESIGN.md §2.2): greedy decoding of the versioned prompt, the fixed
 * prefix kept in the KV cache across calls, every output judged by [RephraseVerdicts] (so the checker sits between
 * the model and the app, always).
 *
 * The model is loaded lazily on the first request and freed by [release] (the app calls it on memory pressure and
 * after a minute idle). Loading goes through [journal]: it is marked before `load()` and cleared after the first
 * successful completion, so a native abort while loading or generating is found at the next start and the model
 * file is treated as damaged (§6.4).
 *
 * @param modelId the pinned id (`qwen2.5-1.5b-instruct-q4_k_m`); the rephraser id adds the prompt version.
 */
class LlamaRephraser(
    private val modelFile: File,
    modelId: String,
    private val family: RephrasePrompt.Family = RephrasePrompt.Family.QWEN2,
    private val threads: Int = 4,
    private val ctxTokens: Int = DEFAULT_CTX_TOKENS,
    private val native: RephraseNative = NativeRephrase,
    private val journal: LoadJournal? = null,
) : Rephraser {

    /** Marks a load in progress on disk, so a crash inside it is visible at the next start. */
    interface LoadJournal {
        fun begin()
        fun clear()
    }

    /** What the last completion cost, from the native side (design §5.5's latency columns). */
    data class Stats(
        val prefixTokens: Long, val prefixReused: Boolean, val promptTokens: Long, val promptMs: Double,
        val generatedTokens: Long, val generatedMs: Double, val loadMs: Long,
    )

    override val id: String = "$modelId@p${RephrasePrompt.VERSION}"

    private val mutex = Mutex()
    @Volatile private var handle = 0L
    @Volatile private var journalOpen = false
    private var loadMs = 0L

    @Volatile
    var lastStats: Stats? = null
        private set

    /** The model's raw text of the last completion (the measurement records it next to the verdict). */
    @Volatile
    var lastRaw: String? = null
        private set

    val isLoaded: Boolean get() = handle != 0L

    override suspend fun rephrase(request: RephraseRequest): RephraseResult = mutex.withLock {
        withContext(Dispatchers.Default) {
            val h = ensureLoaded() ?: return@withContext RephraseResult.Unavailable("the wording model could not be loaded")
            val tokens = native.tokenCount(h, ClaimChecker.normalize(request.text))
            if (tokens < 0) return@withContext RephraseResult.Unavailable("tokenizer failed")
            val job = coroutineContext[Job]
            // A watcher on another thread turns the caller's cancellation (Skip, a timeout) into a native cancel.
            val bytes = coroutineScope {
                var finished = false
                val watcher = launch {
                    try {
                        awaitCancellation()
                    } finally {
                        if (!finished) native.cancel(h)
                    }
                }
                try {
                    native.complete(h, RephrasePrompt.prefix(family), RephrasePrompt.suffix(request.text, request.surface, family), RephrasePrompt.maxTokens(tokens))
                } finally {
                    finished = true
                    watcher.cancel()
                }
            }
            val s = native.lastStats(h)
            lastStats = Stats(s[0], s[1] == 1L, s[2], s[3] / 1000.0, s[4], s[5] / 1000.0, loadMs)
            if (bytes == null) {
                if (job?.isCancelled == true) throw CancellationException("rephrase cancelled")
                return@withContext RephraseResult.Unavailable("generation failed")
            }
            if (journalOpen) {
                journal?.clear()
                journalOpen = false
            }
            val raw = String(bytes, Charsets.UTF_8)
            lastRaw = raw
            RephraseVerdicts.judge(request, raw)
        }
    }

    private fun ensureLoaded(): Long? {
        if (handle != 0L) return handle
        if (!modelFile.isFile) return null
        journal?.begin()
        journalOpen = true
        val t0 = System.nanoTime()
        val h = native.load(modelFile.absolutePath, threads, ctxTokens)
        loadMs = (System.nanoTime() - t0) / 1_000_000
        if (h == 0L) {
            // A clean failure (llama.cpp returned null): the journal stays, so the caller treats the file as damaged.
            return null
        }
        handle = h
        return h
    }

    /** Frees the model and its context (memory pressure, idle). The next request loads it again. */
    suspend fun release() = mutex.withLock {
        val h = handle
        if (h != 0L) {
            handle = 0L
            native.free(h)
        }
    }

    /** Stops a running completion at its next token (the Skip button); the caller's coroutine sees Unavailable/cancel. */
    fun cancelCurrent() {
        val h = handle
        if (h != 0L) native.cancel(h)
    }

    companion object {
        /** Prefix (~700 tokens) + the longest beat's suffix and answer, with room: 2048 x 28 KB of f16 KV on the 1.5B. */
        const val DEFAULT_CTX_TOKENS = 2048

        /** The Qwen family a model id belongs to (Qwen3 needs its thinking switched off). */
        fun familyOf(modelId: String): RephrasePrompt.Family =
            if (modelId.lowercase().startsWith("qwen3")) RephrasePrompt.Family.QWEN3 else RephrasePrompt.Family.QWEN2
    }
}
