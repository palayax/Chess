package net.palaya.chessanalyzer.video

import java.io.File
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import net.palaya.chessanalyzer.core.narration.VideoScript

/**
 * Records a script's narration in one voice into [store] (RF, the owner's item 3: "Change voice for analyzed games
 * not works"). The in-app player ([net.palaya.chessanalyzer.ui.video.VideoPlayerController]) never synthesises: it plays
 * the WAVs already in [NarrationStore] for its voice and spoke every other segment with the live device voice, so a voice
 * picked after the review was never heard. This is the one place the narration is recorded on purpose, used by the
 * processing run's "Recording the narration voice" stage and by the Video screen's inline preparation; both go through
 * [NarrationCoordinator], the same path as "Save video", so the player and the export find the same files.
 */
class NarrationPreparer(
    private val store: NarrationStore,
    /** Makes the device voice, the coordinator's mandatory per-segment fallback (its engine starts only if it is used). */
    private val fallback: () -> NarrationVoiceProvider,
    private val workDir: File,
) {

    /** Segments of [script] with narration but no recording in [provider]'s voice (cheap: file checks only). */
    fun missingSegments(script: VideoScript, provider: NarrationVoiceProvider): Int {
        val fingerprint = provider.narrationCacheFingerprint()
        return script.segments.count { segment ->
            segment.narration.isNotBlank() &&
                store.get(store.keyFor(segment.narration, provider.displayName, fingerprint)) == null
        }
    }

    /**
     * Records every missing segment of [script] in [provider]'s voice. [onProgress] gets (segments done, segments in the
     * script) as the coordinator moves; cancel the calling coroutine to stop (the segments done stay in the store, and
     * the coordinator returns at the next segment).
     */
    suspend fun prepare(
        script: VideoScript,
        provider: NarrationVoiceProvider,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): NarrationCoordinator.Outcome = coroutineScope {
        val coordinator = NarrationCoordinator(provider, fallback(), store)
        val watcher = launch { coordinator.progress.collect { onProgress(it.completed, it.total) } }
        val dir = File(workDir, "prep_${System.nanoTime()}")
        try {
            coordinator.synthesizeAll(script, dir)
        } finally {
            coordinator.cancel()
            watcher.cancel()
            dir.deleteRecursively()
        }
    }
}
