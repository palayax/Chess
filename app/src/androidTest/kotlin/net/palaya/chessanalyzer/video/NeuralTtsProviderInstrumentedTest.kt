package net.palaya.chessanalyzer.video

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlin.math.sqrt
import kotlinx.coroutines.runBlocking
import net.palaya.chessanalyzer.TestApp
import net.palaya.chessanalyzer.ui.model.NeuralVoiceTier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Proves the on-device neural narration path (sherpa-onnx via [NeuralTtsProvider]) is real, not
 * just code that compiles, against the Kokoro model **bundled in the APK**:
 *
 *  1. The model installed from the APK's assets (through the app's own first-run setup) is a
 *     working sherpa-onnx model directory.
 *  2. [NeuralTtsProvider] loads it and produces a WAV whose duration is plausible for the word
 *     count and whose PCM is NOT silence (RMS above a floor), the exact failure mode a
 *     duration-only check would miss. The WAV is also left in app-specific external storage so a
 *     host step can `adb pull` it and measure it independently.
 *  3. [NeuralTtsProvider.prepare] fails cleanly (false, no throw) when the model is absent, and
 *     [NarrationCoordinator] falls back to the device voice with no silent gap when that happens,
 *     the mandatory-fallback contract [NarrationCoordinator]'s own doc describes.
 *
 * Nothing is staged on the device and nothing skips itself: the install either works from the APK
 * or the test fails loudly (CLAUDE.md: `assumeTrue` can pass vacuously; check `skipped="0"`).
 */
@RunWith(AndroidJUnit4::class)
class NeuralTtsProviderInstrumentedTest {

    /** ~12 words — long enough for a meaningful duration/RMS check, short enough to stay fast. */
    private val sampleText = "White pushes the pawn to e4. Black replies symmetrically with e5."

    /**
     * Kokoro, installed from the APK, synthesizes real speech. Checks:
     *  - the model really exposes [KokoroVoices.EXPECTED_SPEAKER_COUNT] speakers, so the
     *    non-zero default speaker id is addressing a speaker that exists;
     *  - the configured non-zero speaker actually synthesizes (a bad `sid` yields silence, not an
     *    error, from sherpa-onnx);
     *  - the output is at Kokoro's own 24 kHz rate, i.e. the Kokoro branch of the config builder
     *    is the one that ran.
     */
    @Test
    fun kokoroInstalledFromTheApkSynthesizesRealNonSilentAudio(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        // Install from the APK exactly as a first launch does (the app's own installer, which
        // verifies the pinned hash and extracts atomically). Nothing is staged on the device.
        val modelDir = TestApp.installedVoiceDir()
        val installer = TestApp.app.voiceInstaller
        assertTrue("the installer must report the voice installed", installer.isInstalled())
        // The extracted model is ~150 MB; anything under 100 MB means the archive did not unpack
        // fully, which would otherwise surface as a confusing load failure.
        val installedBytes = modelDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        assertTrue("extracted Kokoro model should be ~150 MB, was $installedBytes", installedBytes > 100_000_000L)
        assertTrue(
            "Kokoro needs its espeak-ng-data phonemizer directory, not just the .onnx",
            File(modelDir, NeuralTtsProvider.ESPEAK_DATA_DIR).isDirectory,
        )

        run {
            val provider = NeuralTtsProvider(NeuralVoiceTier.KOKORO, modelDir)
            try {
                assertTrue(
                    "NeuralTtsProvider.prepare() must load the Kokoro model — this is the assertion " +
                        "that had never been made before",
                    provider.prepare(),
                )
                assertEquals(
                    "kokoro-int8-en-v0_19 must expose 11 speakers; a different count means the " +
                        "pinned archive changed and the speaker ids in KokoroVoices are stale",
                    KokoroVoices.EXPECTED_SPEAKER_COUNT,
                    provider.speakerCount,
                )
                assertEquals("Kokoro synthesizes at 24 kHz", 24_000, provider.modelSampleRate)
                assertTrue(
                    "the default speaker must not be 0 — 'af' is an averaged blend, and picking it " +
                        "by default would be an accident rather than the deliberate choice this " +
                        "tier is supposed to make",
                    NeuralVoiceTier.KOKORO.speakerId > 0,
                )

                val outFile = File(freshDir(context, "kokoro_tts_test_output"), "sample.wav")
                val synthResult = provider.synthesize(sampleText, outFile)
                check(synthResult is SynthesisResult.Success) { "expected real synthesis, got $synthResult" }

                // Evidence for the host-side check: the WAV is copied here so it can be pulled off the
                // device and measured independently rather than trusted from this test.
                context.getExternalFilesDir(null)?.let { extDir ->
                    outFile.copyTo(File(extDir, KOKORO_EVIDENCE_WAV), overwrite = true)
                }

                val info = WavUtil.readHeader(outFile)
                assertNotNull("synthesized output should be a readable WAV", info)
                info!!
                assertEquals("WAV header must carry Kokoro's own rate", 24_000, info.sampleRate)

                val wordCount = sampleText.split(Regex("\\s+")).size
                val samples = WavUtil.readAsMono16(outFile, info.sampleRate)
                val rms = rms(samples)
                android.util.Log.i(
                    "NeuralTtsProviderTest",
                    "KOKORO sid=${NeuralVoiceTier.KOKORO.speakerId} " +
                        "(${KokoroVoices.nameFor(NeuralVoiceTier.KOKORO.speakerId)}) " +
                        "lengthScale=${NeuralVoiceTier.KOKORO.lengthScale} words=$wordCount " +
                        "durationMs=${info.durationMs} sampleRate=${info.sampleRate} rms=$rms " +
                        "fileBytes=${outFile.length()}",
                )
                assertTrue(
                    "duration (${info.durationMs}ms) should be plausible for $wordCount words of speech",
                    info.durationMs in 500L..15_000L,
                )
                assertTrue("synthesized WAV should contain samples", samples.isNotEmpty())
                assertTrue(
                    "Kokoro audio must not be silence — RMS ($rms) should be well above a near-zero " +
                        "floor. A valid-but-silent WAV with a plausible duration is the precise " +
                        "failure this guards against, and is what a wrong speaker id would produce.",
                    rms > 200.0,
                )
            } finally {
                provider.release()
            }
        }
    }

    @Test
    fun prepareFailsCleanlyWhenModelDirectoryIsAbsent(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val missingDir = File(freshDir(context, "neural_tts_missing_model"), "does_not_exist")
        val provider = NeuralTtsProvider(NeuralVoiceTier.KOKORO, missingDir)

        val ready = provider.prepare()
        assertFalse("prepare() must return false (never throw) when the model directory is absent", ready)

        val outFile = File(freshDir(context, "neural_tts_missing_model_output"), "sample.wav")
        val result = provider.synthesize(sampleText, outFile)
        assertTrue("synthesize() after a failed prepare() must fail cleanly, not throw or hang", result is SynthesisResult.Failure)
        assertFalse("no output file should be left behind on failure", outFile.exists() && outFile.length() > 0)
    }

    @Test
    fun coordinatorFallsBackToDeviceVoiceWithNoSilentGapWhenNeuralModelIsMissing(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val script = TwoGameNarrationFixture.script(context, TwoGameNarrationFixture.CHESSCOM_PGN, TwoGameNarrationFixture.CHESSCOM_OVERRIDES)
        val store = NarrationStore.forDirectory(freshDir(context, "neural_fallback_store"))
        val outputDir = freshDir(context, "neural_fallback_scratch")
        val missingModelDir = File(freshDir(context, "neural_fallback_missing_model"), "does_not_exist")
        val spokenSegments = script.segments.count { it.narration.isNotBlank() }

        try {
            val primary = NeuralTtsProvider(NeuralVoiceTier.KOKORO, missingModelDir)
            val outcome = NarrationCoordinator(primary, DeviceTtsProvider(context), store).synthesizeAll(script, outputDir)

            android.util.Log.i(
                "NeuralTtsProviderTest",
                "fallback: primaryUsedCount=${outcome.primaryUsedCount} fallbackCount=${outcome.fallbackCount} " +
                    "spokenSegments=$spokenSegments notice=${outcome.notice}",
            )

            assertEquals("the neural provider has no model, so it must never be the one used", 0, outcome.primaryUsedCount)
            assertEquals("every spoken segment must fall back to the device voice", spokenSegments, outcome.fallbackCount)
            val silentSpokenSegments = outcome.results.count { result ->
                result is NarrationSynthesizer.Result.Silent &&
                    script.segments.getOrNull(result.segmentIndex)?.narration?.isNotBlank() == true
            }
            assertEquals(
                "no spoken segment should be left silent — the mandatory device-voice fallback " +
                    "must cover every one of them",
                0,
                silentSpokenSegments,
            )
        } finally {
            store.dir.deleteRecursively()
            outputDir.deleteRecursively()
        }
    }

    private fun rms(samples: ShortArray): Double {
        if (samples.isEmpty()) return 0.0
        var sumSquares = 0.0
        for (s in samples) sumSquares += s.toDouble() * s.toDouble()
        return sqrt(sumSquares / samples.size)
    }

    private fun freshDir(context: android.content.Context, name: String): File =
        File(context.filesDir, name).apply { deleteRecursively(); mkdirs() }

    companion object {
        /** Left in app-specific external storage so a host step can `adb pull` and measure it. */
        const val KOKORO_EVIDENCE_WAV = "kokoro_tts_evidence_sample.wav"
    }
}
