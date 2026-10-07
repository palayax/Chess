package net.palaya.chessanalyzer.video

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import kotlinx.coroutines.runBlocking
import net.palaya.chessanalyzer.ManualEvidenceTool
import net.palaya.chessanalyzer.TestApp
import net.palaya.chessanalyzer.ui.model.NeuralVoiceTier
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * V1 evidence: the voice picker's own sample sentence ([VoiceSamplePlayer.SAMPLE_TEXT]) through every
 * Kokoro speaker, with the app's own engine call ([NeuralTtsProvider.synthesizeAs], the one "Play sample"
 * uses), left in app-external storage to be pulled and measured on the host (duration, RMS, peak, pitch,
 * spectral centroid; RUN_LOG V1). Not part of the suite ([ManualEvidenceTool]):
 * ```
 * adb shell am instrument -w -r -e class 'net.palaya.chessanalyzer.video.NarratorVoiceEvidence' \
 *   net.palaya.chessanalyzer.test/androidx.test.runner.AndroidJUnitRunner
 * adb pull /sdcard/Android/data/net.palaya.chessanalyzer/files/v1_voices docs/voice_samples/
 * ```
 */
@RunWith(AndroidJUnit4::class)
class NarratorVoiceEvidence {

    @ManualEvidenceTool
    @Test
    fun renderTheSampleSentenceWithEverySpeaker(): Unit = runBlocking {
        val app = TestApp.app
        val modelDir = TestApp.installedVoiceDir()
        val out = File(app.getExternalFilesDir(null), "v1_voices").apply { deleteRecursively(); mkdirs() }
        val provider = NeuralTtsProvider(NeuralVoiceTier.KOKORO, modelDir, voiceVersionId = app.voiceStore.installedVersionId())
        assertTrue(provider.prepare())
        try {
            for (s in KokoroVoices.SPEAKERS) {
                val f = File(out, "v1_sid%02d_%s.wav".format(s.sid, s.upstreamName))
                val r = provider.synthesizeAs(s.sid, VoiceSamplePlayer.SAMPLE_TEXT, f)
                android.util.Log.i("NarratorVoiceEvidence", "sid ${s.sid} ${s.upstreamName}: $r")
                assertTrue("sid ${s.sid}: $r", r is SynthesisResult.Success)
            }
        } finally {
            provider.release()
        }
    }
}
