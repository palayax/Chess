package net.palaya.chessanalyzer.video

import android.content.Context
import java.io.File

/**
 * The floor [NarrationVoiceProvider]: free, fully offline, no download needed, works for every
 * user out of the box. A thin adapter over [NarrationSynthesizer]'s per-segment API — all the
 * actual `TextToSpeech` handling (engine init, language checks, `synthesizeToFile`, timeouts)
 * lives there and is unchanged/still covered by the existing export test.
 *
 * This is also what [NarrationCoordinator] falls back to whenever the selected provider (today
 * [NeuralTtsProvider]) fails for any reason — so it must stay independently reliable.
 */
class DeviceTtsProvider(context: Context) : NarrationVoiceProvider {
    override val displayName: String = "Device voice"

    private val synthesizer = NarrationSynthesizer(context)

    /** Pass-through for diagnosability — see [NarrationSynthesizer.selectedVoiceInfo]. */
    val selectedVoiceInfo: NarrationSynthesizer.SelectedVoiceInfo?
        get() = synthesizer.selectedVoiceInfo

    override suspend fun prepare(): Boolean = synthesizer.prepareEngine()

    override suspend fun synthesize(text: String, outFile: File): SynthesisResult {
        // estimatedMs is only used for the Silent-fallback duration, which the caller
        // (NarrationCoordinator) already tracks separately — 0 here is never surfaced.
        return when (val result = synthesizer.synthesizeSegment(text, outFile, estimatedMs = 0L)) {
            is NarrationSynthesizer.Result.Synthesized -> SynthesisResult.Success(result.wavFile, result.durationMs)
            is NarrationSynthesizer.Result.Silent -> SynthesisResult.Failure(result.reason)
        }
    }

    override fun release() = synthesizer.release()
}
