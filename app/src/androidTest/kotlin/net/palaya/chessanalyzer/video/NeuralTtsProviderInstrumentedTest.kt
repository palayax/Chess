package net.palaya.chessanalyzer.video

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlin.math.sqrt
import kotlinx.coroutines.runBlocking
import net.palaya.chessanalyzer.ui.model.NeuralVoiceTier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Proves the on-device neural narration path (sherpa-onnx via [NeuralTtsProvider]/
 * [VoiceModelProvisioner]) is real, not just code that compiles:
 *
 *  1. [VoiceModelProvisioner] really SHA-256-verifies and extracts a real model archive into a
 *     working sherpa-onnx model directory.
 *  2. [NeuralTtsProvider] loads that model and produces a WAV whose duration is plausible for the
 *     word count and whose PCM is NOT silence (RMS above a floor) — the exact failure mode a
 *     duration-only check would miss.
 *  3. [NeuralTtsProvider.prepare] fails cleanly (false, no throw) when the model is absent, and
 *     [NarrationCoordinator] falls back to the device voice with no silent gap when that happens —
 *     the mandatory-fallback contract [NarrationCoordinator]'s own doc describes.
 *
 * The Piper-tier archive is expected pre-staged on the device at [PIPER_ARCHIVE_ON_DEVICE] (`adb
 * push` ahead of the test run — see `docs/` for the exact command) rather than downloaded here:
 * downloading 20+ MB from GitHub on every `connectedDebugAndroidTest` run would make this test
 * slow and flaky on a bad connection, exactly the problem `scripts/push_test_net.sh` already
 * solves for the Stockfish net (see that script's doc and [net.palaya.chessanalyzer.engine]'s
 * instrumented tests for the established pattern this mirrors). This test does NOT use
 * `assumeTrue` to skip when the archive is missing — per this project's testing standard
 * (`CLAUDE.md`: "assumeTrue... can pass vacuously... always check skipped=0"), a missing archive
 * is a real, loud test failure, not a silent skip.
 */
@RunWith(AndroidJUnit4::class)
class NeuralTtsProviderInstrumentedTest {

    /** ~12 words — long enough for a meaningful duration/RMS check, short enough to stay fast. */
    private val sampleText = "White pushes the pawn to e4. Black replies symmetrically with e5."

    @Test
    fun provisionerInstallsAPushedArchiveAndProviderSynthesizesRealNonSilentAudio(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val archive = File(PIPER_ARCHIVE_ON_DEVICE)
        assertTrue(
            "Expected the Piper model archive pre-staged at $PIPER_ARCHIVE_ON_DEVICE — run " +
                "`adb push vits-piper-en_US-ljspeech-medium-int8.tar.bz2 $PIPER_ARCHIVE_ON_DEVICE` " +
                "(and `adb shell chmod 644 $PIPER_ARCHIVE_ON_DEVICE`) before this test, the same " +
                "way scripts/push_test_net.sh stages the Stockfish net.",
            archive.isFile,
        )

        val provisioner = VoiceModelProvisioner(freshDir(context, "neural_tts_test_files"))
        try {
            val result = provisioner.provisionFromLocalArchiveForTesting(NeuralVoiceTier.PIPER, archive)
            check(result is ProvisioningResult.Success) { "expected provisioning to succeed, got $result" }
            assertTrue("provisioner should report the tier installed after a successful provision", provisioner.isInstalled(NeuralVoiceTier.PIPER))
            assertTrue("installed model should occupy real disk space", provisioner.installedSizeBytes(NeuralVoiceTier.PIPER) > 1_000_000L)

            val provider = NeuralTtsProvider(NeuralVoiceTier.PIPER, provisioner.modelDir(NeuralVoiceTier.PIPER))
            try {
                val ready = provider.prepare()
                assertTrue("NeuralTtsProvider.prepare() should succeed against a freshly-provisioned model", ready)

                val outFile = File(freshDir(context, "neural_tts_test_output"), "sample.wav")
                val synthResult = provider.synthesize(sampleText, outFile)
                check(synthResult is SynthesisResult.Success) { "expected real synthesis, got $synthResult" }

                // Copy to app-specific external storage (no permission needed, unlike filesDir)
                // purely so a human/CI step can `adb pull` this exact synthesized file afterward
                // as evidence — production code never writes here.
                context.getExternalFilesDir(null)?.let { extDir ->
                    outFile.copyTo(File(extDir, "neural_tts_evidence_sample.wav"), overwrite = true)
                }

                val info = WavUtil.readHeader(outFile)
                assertNotNull("synthesized output should be a readable WAV", info)
                info!!

                val wordCount = sampleText.split(Regex("\\s+")).size
                android.util.Log.i(
                    "NeuralTtsProviderTest",
                    "synthesized $wordCount words -> durationMs=${info.durationMs} sampleRate=${info.sampleRate} " +
                        "fileBytes=${outFile.length()}",
                )
                // Loose bounds deliberately: real speech rate varies by model, but ~12 words can
                // never plausibly be near-zero (a silent/near-empty clip) nor multiple minutes long
                // (a runaway/garbled synthesis).
                assertTrue(
                    "duration (${info.durationMs}ms) should be plausible for $wordCount words of speech",
                    info.durationMs in 500L..15_000L,
                )

                val samples = WavUtil.readAsMono16(outFile, info.sampleRate)
                assertTrue("synthesized WAV should contain samples", samples.isNotEmpty())
                val rms = rms(samples)
                android.util.Log.i("NeuralTtsProviderTest", "RMS amplitude=$rms (of max 32767)")
                assertTrue(
                    "synthesized audio must not be silence — RMS ($rms) should be well above a " +
                        "near-zero floor; a silent WAV that merely has a plausible duration is " +
                        "exactly the false-pass this check guards against",
                    rms > 200.0,
                )
            } finally {
                provider.release()
            }
        } finally {
            provisioner.delete(NeuralVoiceTier.PIPER)
        }
    }

    /**
     * The Kokoro mirror of the test above, and the one that matters: until this ran, the Kokoro
     * tier was code that compiled and had never once produced a sample. It is a genuinely
     * different shape from Piper — a different sherpa-onnx config class
     * ([com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig]), a `voices.bin` speaker bank instead
     * of a single-speaker VITS graph, and eleven speakers where Piper has one — so nothing Piper
     * proves carries over to it.
     *
     * Checks three things Piper's test cannot:
     *  - the model really exposes [KokoroVoices.EXPECTED_SPEAKER_COUNT] speakers, so the
     *    non-zero default speaker id is addressing a speaker that exists;
     *  - the configured non-zero speaker actually synthesizes (a bad `sid` yields silence, not an
     *    error, from sherpa-onnx);
     *  - the output is at Kokoro's own 24 kHz rate, i.e. the Kokoro branch of the config builder
     *    is the one that ran, not a Piper config that happened to load.
     */
    @Test
    fun kokoroProvisionsFromAPushedArchiveAndSynthesizesRealNonSilentAudio(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val archive = File(KOKORO_ARCHIVE_ON_DEVICE)
        assertTrue(
            "Expected the Kokoro model archive pre-staged at $KOKORO_ARCHIVE_ON_DEVICE — run " +
                "`adb push kokoro-int8-en-v0_19.tar.bz2 $KOKORO_ARCHIVE_ON_DEVICE` " +
                "(and `adb shell chmod 644 $KOKORO_ARCHIVE_ON_DEVICE`) before this test. As with " +
                "the Piper case above this deliberately fails rather than skipping: a vacuous " +
                "pass here is exactly how the Kokoro tier stayed unverified for so long.",
            archive.isFile,
        )

        val provisioner = VoiceModelProvisioner(freshDir(context, "kokoro_tts_test_files"))
        try {
            val result = provisioner.provisionFromLocalArchiveForTesting(NeuralVoiceTier.KOKORO, archive)
            check(result is ProvisioningResult.Success) { "expected provisioning to succeed, got $result" }
            assertTrue("provisioner should report KOKORO installed", provisioner.isInstalled(NeuralVoiceTier.KOKORO))
            // The extracted model is ~150 MB; anything under 100 MB means the archive did not
            // unpack fully, which would otherwise surface as a confusing load failure.
            assertTrue(
                "extracted Kokoro model should be ~150 MB, was ${provisioner.installedSizeBytes(NeuralVoiceTier.KOKORO)}",
                provisioner.installedSizeBytes(NeuralVoiceTier.KOKORO) > 100_000_000L,
            )

            val modelDir = provisioner.modelDir(NeuralVoiceTier.KOKORO)
            assertTrue(
                "Kokoro needs its espeak-ng-data phonemizer directory, not just the .onnx",
                File(modelDir, NeuralTtsProvider.ESPEAK_DATA_DIR).isDirectory,
            )

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

                // Evidence for the host-side check — see the Piper test above for why the WAV is
                // copied here and why it has to be pulled off the device rather than trusted.
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
        } finally {
            provisioner.delete(NeuralVoiceTier.KOKORO)
        }
    }

    @Test
    fun prepareFailsCleanlyWhenModelDirectoryIsAbsent(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val missingDir = File(freshDir(context, "neural_tts_missing_model"), "does_not_exist")
        val provider = NeuralTtsProvider(NeuralVoiceTier.PIPER, missingDir)

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
            val primary = NeuralTtsProvider(NeuralVoiceTier.PIPER, missingModelDir)
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
        const val PIPER_ARCHIVE_ON_DEVICE = "/data/local/tmp/vits-piper-en_US-ljspeech-medium-int8.tar.bz2"
        const val KOKORO_ARCHIVE_ON_DEVICE = "/data/local/tmp/kokoro-int8-en-v0_19.tar.bz2"

        /** Left in app-specific external storage for `adb pull` — see the Piper test's comment. */
        const val KOKORO_EVIDENCE_WAV = "kokoro_tts_evidence_sample.wav"
    }
}
