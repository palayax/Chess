package net.palaya.chessanalyzer.video

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlin.math.sqrt
import kotlinx.coroutines.runBlocking
import net.palaya.chessanalyzer.ui.model.NeuralVoiceTier
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The *composition* proof for the "exported video was narrated by the device voice" defect:
 * [VideoExporterProviderRegressionTest] shows that whatever a provider returns reaches the MP4's
 * audio track (with a synthetic tone, which is what makes that assertion sharp), and
 * [NeuralTtsProviderInstrumentedTest] shows the real Kokoro model produces speech. Neither shows
 * the two working *together* — which is precisely the thing that was broken, and precisely the
 * thing that was assumed rather than observed for three rounds.
 *
 * So this exports the same script twice from the real pipeline — once through a real
 * [NeuralTtsProvider] (Kokoro) and once with `null`, the old behaviour — decodes both MP4s' AAC
 * tracks back to PCM, and writes three WAVs into the app's external files dir:
 *
 *  - `reference_kokoro.wav`  — one sentence straight out of [NeuralTtsProvider], never muxed.
 *  - `exported_neural.wav`   — the audio actually inside the provider-backed MP4.
 *  - `exported_device.wav`   — the audio actually inside the `null`-provider MP4.
 *
 * **The on-device assertions here are deliberately the weak half of the evidence.** Per this
 * project's standard ("a test that measures itself is not independent evidence... where an
 * artifact can be pulled off the device and checked on the host, pull it"), the real judgement is
 * made on the host: `reference_kokoro.wav` is cross-correlated against both exports, and only the
 * neural one may match. That comparison cannot be faked by a passing assertion in this file.
 *
 * Both MP4s are written into the app's external files dir too, so `adb pull` can reach the
 * containers themselves without root — see `CLAUDE.md` on running this via `am instrument`
 * rather than `connectedDebugAndroidTest`, which uninstalls the app and deletes all of it.
 */
@RunWith(AndroidJUnit4::class)
class VideoExportNeuralVoiceEvidenceTest {

    @Test
    fun exportWithTheRealNeuralProviderCarriesNeuralAudioAndLeavesPullableEvidence(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val script = TestScripts.syntheticScript()

        val archive = File(NeuralTtsProviderInstrumentedTest.KOKORO_ARCHIVE_ON_DEVICE)
        // Not assumeTrue: a missing archive is a loud failure, never a silent skip (CLAUDE.md).
        assertTrue(
            "Expected the Kokoro model archive pre-staged at " +
                "${NeuralTtsProviderInstrumentedTest.KOKORO_ARCHIVE_ON_DEVICE} — run " +
                "scripts/push_voice_models.sh before this test.",
            archive.isFile,
        )

        val evidenceDir = File(context.getExternalFilesDir(null), "export_voice_evidence")
            .apply { deleteRecursively(); mkdirs() }

        val modelRoot = File(context.filesDir, "export_voice_evidence_models")
            .apply { deleteRecursively(); mkdirs() }
        val provisioner = VoiceModelProvisioner(modelRoot)
        val provisioned = provisioner.provisionFromLocalArchiveForTesting(NeuralVoiceTier.KOKORO, archive)
        check(provisioned is ProvisioningResult.Success) { "expected Kokoro provisioning to succeed, got $provisioned" }
        val modelDir = provisioner.modelDir(NeuralVoiceTier.KOKORO)

        // ---- (1) Reference: one sentence straight from the model, never near the muxer. ----
        // A separate provider instance from the one the exporter drives, so releasing this one
        // cannot disturb the export's own prepare()/release() lifecycle.
        val referenceText = script.segments.first().narration
        val referenceWav = File(evidenceDir, "reference_kokoro.wav")
        val referenceProvider = NeuralTtsProvider(NeuralVoiceTier.KOKORO, modelDir)
        try {
            assertTrue("reference provider should prepare against the provisioned model", referenceProvider.prepare())
            val synth = referenceProvider.synthesize(referenceText, referenceWav)
            assertTrue("reference synthesis should succeed, got $synth", synth is SynthesisResult.Success)
        } finally {
            referenceProvider.release()
        }
        assertTrue("reference WAV should be real audio", referenceWav.length() > 10_000)

        // ---- (2) The export under test: a real neural provider, all the way through. ----
        val neuralProvider = NeuralTtsProvider(NeuralVoiceTier.KOKORO, modelDir)
        val neuralMp4 = VideoExporter(context).let { exporter ->
            val out = exporter.export(script, "evidence_neural_export", neuralProvider)
            val state = exporter.state.value
            check(state is VideoExporter.State.Completed) { "neural export did not complete: $state" }
            assertTrue("the neural export must report spoken narration", state.narrationWasSpoken)
            android.util.Log.i(TAG, "neural export: ${out.length()} bytes, notice=${state.narrationNotice}")
            out
        }

        // ---- (3) The old behaviour, same script, for contrast. ----
        val deviceMp4 = VideoExporter(context).let { exporter ->
            val out = exporter.export(script, "evidence_device_export", null)
            val state = exporter.state.value
            check(state is VideoExporter.State.Completed) { "device export did not complete: $state" }
            android.util.Log.i(TAG, "device export: ${out.length()} bytes, spoken=${state.narrationWasSpoken}")
            out
        }

        val neuralPcm = ExportedAudioProbe.decodeAudioTrack(neuralMp4)
        val devicePcm = ExportedAudioProbe.decodeAudioTrack(deviceMp4)
        writeWav(File(evidenceDir, "exported_neural.wav"), neuralPcm)
        writeWav(File(evidenceDir, "exported_device.wav"), devicePcm)
        neuralMp4.copyTo(File(evidenceDir, "exported_neural.mp4"), overwrite = true)
        deviceMp4.copyTo(File(evidenceDir, "exported_device.mp4"), overwrite = true)

        val neuralRms = rms(neuralPcm.mono)
        val deviceRms = rms(devicePcm.mono)
        android.util.Log.i(
            TAG,
            "neural: ${neuralPcm.mono.size} samples @${neuralPcm.sampleRate} rms=${"%.1f".format(neuralRms)} | " +
                "device: ${devicePcm.mono.size} samples @${devicePcm.sampleRate} rms=${"%.1f".format(deviceRms)} | " +
                "evidence in ${evidenceDir.absolutePath}",
        )

        // On-device assertions, kept to what is unambiguous: the provider-backed export must
        // contain real, non-silent audio, and it must not be byte-identical to the device path.
        // Anything sharper than this (is it *Kokoro's* voice?) is decided on the host.
        assertTrue("neural export should decode to a non-trivial amount of audio", neuralPcm.mono.size > neuralPcm.sampleRate)
        assertTrue("neural export audio must not be silence, rms=$neuralRms", neuralRms > 200.0)
        assertNotEquals(
            "the provider-backed export and the null-provider export must not produce identical audio",
            neuralPcm.mono.toList().hashCode(),
            devicePcm.mono.toList().hashCode(),
        )
    }

    private fun rms(samples: ShortArray): Double {
        if (samples.isEmpty()) return 0.0
        var sum = 0.0
        for (s in samples) sum += s.toDouble() * s.toDouble()
        return sqrt(sum / samples.size)
    }

    /** 16-bit mono PCM out to a RIFF file the host can read with nothing but `wave` + `numpy`. */
    private fun writeWav(file: File, pcm: ExportedAudioProbe.Pcm) {
        file.outputStream().use { out ->
            out.write(WavUtil.buildWavHeader(pcm.mono.size * 2, pcm.sampleRate, channels = 1, bitsPerSample = 16))
            val bytes = ByteArray(pcm.mono.size * 2)
            for (i in pcm.mono.indices) {
                val v = pcm.mono[i].toInt()
                bytes[i * 2] = (v and 0xFF).toByte()
                bytes[i * 2 + 1] = ((v shr 8) and 0xFF).toByte()
            }
            out.write(bytes)
        }
    }

    private companion object {
        const val TAG = "ExportVoiceEvidence"
    }
}
