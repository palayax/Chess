package net.palaya.chessanalyzer.rephrase

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import net.palaya.chessanalyzer.core.text.RephrasePrompt
import net.palaya.chessanalyzer.core.text.RephraseRequest
import net.palaya.chessanalyzer.core.text.RephraseResult
import net.palaya.chessanalyzer.data.models.VoiceTrialResult
import java.io.File

/**
 * The one llama.cpp model of the process (docs/LLM_REPHRASE_DESIGN.md §2.2: "one model per process", like the
 * engine). Hands out the [LlamaRephraser] for the installed, verified model, loads it lazily, and frees it on memory
 * pressure ([release]) and a minute after the last job ([scheduleIdleRelease]). An update's trial goes through
 * [trial], which frees the model in use first.
 */
class RephraseBackend(
    private val store: RephraseModelStore,
    private val availability: () -> RephraseSupport.Availability,
    private val threads: () -> Int,
    private val scope: CoroutineScope,
    private val log: (String) -> Unit = {},
) {
    private val mutex = Mutex()
    private var current: LlamaRephraser? = null
    @Volatile private var idleJob: Job? = null

    /** The rephraser id the cache reads under, without loading or hashing anything; null when there is no model. */
    fun activeId(): String? =
        if (availability() != RephraseSupport.Availability.AVAILABLE || !store.isInstalled()) null
        else "${store.activePins().id}@p${RephrasePrompt.VERSION}"

    /** The backend for the active model (verified once per process: about 5-10 s for 1.1 GB, off the main thread). */
    suspend fun get(): LlamaRephraser? = mutex.withLock {
        idleJob?.cancel()
        if (availability() != RephraseSupport.Availability.AVAILABLE) return@withLock null
        val pins = store.activePins()
        val file = withContext(Dispatchers.IO) { store.verifiedFileOrNull() }
        if (file == null) {
            current?.release()
            current = null
            log("rephrase: no verified wording model")
            return@withLock null
        }
        val c = current
        if (c != null && c.id == "${pins.id}@p${RephrasePrompt.VERSION}") return@withLock c
        c?.release()
        LlamaRephraser(file, pins.id, LlamaRephraser.familyOf(pins.id), threads(), journal = store).also { current = it }
    }

    /** Frees the model (onTrimMemory, idle, before an update's trial). The next [get] loads it again. */
    suspend fun release() = mutex.withLock {
        current?.let {
            it.release()
            log("rephrase: model released")
        }
        current = null
    }

    fun scheduleIdleRelease(afterMs: Long = IDLE_RELEASE_MS) {
        idleJob?.cancel()
        idleJob = scope.launch {
            delay(afterMs)
            release()
        }
    }

    /**
     * An update's trial (ModelActivator.activateRephrase): load [model] and reword the prompt's first example; the
     * result must pass the checker (accepted or unchanged).
     */
    suspend fun trial(model: File, modelId: String): VoiceTrialResult = mutex.withLock {
        current?.release()
        current = null
        val r = LlamaRephraser(model, modelId, LlamaRephraser.familyOf(modelId), threads())
        try {
            val e = RephrasePrompt.EXAMPLES.first()
            when (val result = r.rephrase(RephraseRequest(e.surface, e.text))) {
                is RephraseResult.Accepted, RephraseResult.Unchanged -> VoiceTrialResult(true, "trial rephrase ${result.javaClass.simpleName.lowercase()}")
                is RephraseResult.Rejected -> VoiceTrialResult(false, "the trial rephrase was rejected (${result.reason})")
                is RephraseResult.Unavailable -> VoiceTrialResult(false, result.cause)
            }
        } finally {
            r.release()
        }
    }

    companion object {
        const val IDLE_RELEASE_MS = 60_000L
    }
}
