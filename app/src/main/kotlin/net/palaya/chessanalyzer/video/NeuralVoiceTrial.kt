package net.palaya.chessanalyzer.video

import java.io.File
import kotlin.math.sqrt
import net.palaya.chessanalyzer.data.models.VoiceTrial
import net.palaya.chessanalyzer.data.models.VoiceTrialResult
import net.palaya.chessanalyzer.ui.model.NeuralVoiceTier

/**
 * The voice update's trial (docs/MODEL_DOWNLOAD_DESIGN.md §4.3, D2e): a throwaway [NeuralTtsProvider] on
 * the unpacked-but-not-installed directory must load (at least one speaker, a sample rate) and speak one
 * fixed sentence that is long enough and NOT silent. sherpa-onnx and onnxruntime throw on a model they
 * cannot load rather than exiting, so unlike the engine this is a real validator; the RMS floor is not
 * optional, because a valid-but-silent WAV is the failure this project has been burned by (Round 5).
 *
 * The floors are the instrumented evidence tests' (`NeuralTtsProviderInstrumentedTest`: RMS > 200 on
 * 16-bit samples) and the design's (> 300 ms).
 */
class NeuralVoiceTrial(
    /** Where the throwaway WAV goes (deleted afterwards): the app's cache directory. */
    private val scratchDir: File,
    private val log: (String) -> Unit = {},
) : VoiceTrial {

    companion object {
        const val SENTENCE = "Knight to f3. White develops and keeps an eye on the centre."
        const val MIN_DURATION_MS = 300L
        const val MIN_RMS = 200.0

        /** The verdict on a synthesized clip, pure (host-tested). */
        fun verdict(durationMs: Long, rms: Double): Boolean = durationMs > MIN_DURATION_MS && rms > MIN_RMS

        fun rms(samples: ShortArray): Double {
            if (samples.isEmpty()) return 0.0
            var sum = 0.0
            for (s in samples) sum += s.toDouble() * s.toDouble()
            return sqrt(sum / samples.size)
        }
    }

    override suspend fun run(modelDir: File): VoiceTrialResult {
        val provider = NeuralTtsProvider(NeuralVoiceTier.KOKORO, modelDir, voiceVersionId = "trial")
        val out = File(scratchDir, "voice_update_trial.wav")
        try {
            if (!provider.prepare()) return VoiceTrialResult(false, "the model did not load")
            val speakers = provider.speakerCount
            val rate = provider.modelSampleRate
            if (speakers < 1 || rate <= 0) return VoiceTrialResult(false, "speakers $speakers, sample rate $rate")
            scratchDir.mkdirs()
            val result = provider.synthesize(SENTENCE, out)
            if (result !is SynthesisResult.Success) return VoiceTrialResult(false, "synthesis failed: $result")
            val info = WavUtil.readHeader(out) ?: return VoiceTrialResult(false, "not a readable WAV")
            val rms = rms(WavUtil.readAsMono16(out, info.sampleRate))
            val detail = "speakers $speakers, ${info.sampleRate} Hz, ${info.durationMs} ms, RMS ${"%.0f".format(java.util.Locale.ROOT, rms)}"
            log("voice trial: $detail")
            return VoiceTrialResult(verdict(info.durationMs, rms), detail)
        } finally {
            provider.release()
            out.delete()
        }
    }
}
