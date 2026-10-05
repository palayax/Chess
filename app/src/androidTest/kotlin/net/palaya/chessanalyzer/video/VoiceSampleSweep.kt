package net.palaya.chessanalyzer.video

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlin.math.sqrt
import kotlinx.coroutines.runBlocking
import net.palaya.chessanalyzer.ManualEvidenceTool
import net.palaya.chessanalyzer.TestApp
import net.palaya.chessanalyzer.ui.model.NeuralVoiceTier
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Renders the same chess-narration paragraph through **all eleven** Kokoro v0.19 speakers (the
 * bundled voice; Piper no longer ships), and sweeps `length_scale` for the chosen speaker. The WAVs are pulled off
 * the device into `docs/voice_samples/` so the owner can pick the narration voice **by ear** —
 * which is the only way this particular decision can honestly be made. Everything a machine can
 * measure (duration, RMS, effective words-per-minute) is logged alongside, and the same numbers
 * are re-derived on the host from the pulled files rather than trusted from here.
 *
 * Not part of the automated suite — see [ManualEvidenceTool]. Run it with:
 * ```
 * adb shell am instrument -w -r \
 *   -e class 'net.palaya.chessanalyzer.video.VoiceSampleSweep' \
 *   net.palaya.chessanalyzer.test/androidx.test.runner.AndroidJUnitRunner
 * adb pull /sdcard/Android/data/net.palaya.chessanalyzer/files/voice_samples docs/
 * ```
 * The Kokoro model is bundled in the APK; the sweep installs it through the app's own first-run
 * setup, so nothing has to be staged on the device.
 */
@RunWith(AndroidJUnit4::class)
class VoiceSampleSweep {

    /**
     * Real narration in the app's own voice and register (compare `VideoScriptGenerator`'s COACH
     * output): coordinate-heavy, two-figure accuracy, a question, and a piece name — the exact
     * things that expose a weak TTS voice. Long enough that a words-per-minute figure derived
     * from it means something.
     */
    private val sampleText = "This is where the game turned. " +
        "Black had a knight fork on f2 that wins the queen, but played h6 instead. " +
        "Can you see why that was the losing move? " +
        "White finished with 91.4 percent accuracy."

    @ManualEvidenceTool
    @Test
    fun renderEveryKokoroSpeaker(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val outDir = File(context.getExternalFilesDir(null), "voice_samples").apply {
            deleteRecursively()
            mkdirs()
        }
        val report = StringBuilder()
        fun line(s: String) {
            android.util.Log.i(TAG, s)
            report.appendLine(s)
        }

        // ---- Kokoro, every speaker ------------------------------------------------------
        val kokoroDir = TestApp.installedVoiceDir()

        for (sid in 0 until KokoroVoices.EXPECTED_SPEAKER_COUNT) {
            val provider = NeuralTtsProvider(
                tier = NeuralVoiceTier.KOKORO,
                modelDir = kokoroDir,
                speakerId = sid,
                lengthScale = NeuralVoiceTier.KOKORO.lengthScale,
            )
            line(renderWith(provider, "kokoro_%02d_%s".format(sid, KokoroVoices.nameFor(sid)), outDir))
        }

        // ---- length_scale sweep on the chosen speaker -----------------------------------
        // Pacing has to be justified by measured duration, not by taste. Rendering the same
        // paragraph at several scales makes the trade-off audible AND countable.
        for (scale in listOf(0.9f, 1.0f, 1.1f, 1.2f, 1.3f)) {
            val provider = NeuralTtsProvider(
                tier = NeuralVoiceTier.KOKORO,
                modelDir = kokoroDir,
                speakerId = NeuralVoiceTier.KOKORO.speakerId,
                lengthScale = scale,
            )
            val name = "pace_kokoro_%02d_ls%s".format(
                NeuralVoiceTier.KOKORO.speakerId,
                "%.2f".format(scale).replace('.', '_'),
            )
            line(renderWith(provider, name, outDir))
        }

        File(outDir, "measurements.txt").writeText(report.toString())
        android.util.Log.i(TAG, "wrote ${outDir.listFiles()?.size ?: 0} files to ${outDir.absolutePath}")
    }

    /**
     * Synthesizes [sampleText] the way production does — **one utterance per sentence**, stitched
     * with the pipeline's own 220 ms / 320 ms gaps (see `NarrationSynthesizer`) — so the sample the
     * owner listens to is the real thing, and the duration it yields is the real timeline cost
     * rather than a run-on paragraph's.
     *
     * The returned line reports the effective words-per-minute to feed back into
     * `NarrationOptions.speechWpm`, inverted from `VideoScriptGenerator.estimateSpeechMs`'s own
     * formula (`words * 60000 / wpm + sentences * 240`) so the number is directly usable there
     * instead of being a raw speaking rate the estimator would then double-count pauses on top of.
     */
    private suspend fun measure(provider: NeuralTtsProvider, name: String, outDir: File): String {
        val sentences = NarrationSynthesizer.splitIntoSentences(sampleText)
        val scratch = File(outDir, ".scratch").apply { mkdirs() }
        val parts = ArrayList<ShortArray>()
        var sampleRate = -1
        for ((i, sentence) in sentences.withIndex()) {
            val f = File(scratch, "s$i.wav")
            val r = provider.synthesize(sentence, f)
            check(r is SynthesisResult.Success) { "$name failed on sentence $i: $r" }
            val header = WavUtil.readHeader(f) ?: error("$name sentence $i produced an unreadable WAV")
            if (sampleRate < 0) sampleRate = header.sampleRate
            parts.add(WavUtil.readAsMono16(f, sampleRate))
            if (i < sentences.size - 1) {
                val gapMs = if (sentence.trimEnd().endsWith("?")) {
                    NarrationSynthesizer.QUESTION_GAP_MS
                } else {
                    NarrationSynthesizer.SENTENCE_GAP_MS
                }
                parts.add(ShortArray(((gapMs * sampleRate) / 1000L).toInt()))
            }
            f.delete()
        }
        scratch.deleteRecursively()

        val total = parts.sumOf { it.size }
        val merged = ShortArray(total)
        var at = 0
        for (p in parts) {
            p.copyInto(merged, at)
            at += p.size
        }
        val outFile = File(outDir, "$name.wav")
        writeMono16(merged, sampleRate, outFile)

        val durationMs = total.toLong() * 1000L / sampleRate
        val words = sampleText.split(Regex("\\s+")).count { it.isNotBlank() }
        val pauseMs = sentences.size * 240L
        val effectiveWpm = if (durationMs > pauseMs) (words * 60_000L) / (durationMs - pauseMs) else -1L
        return "%-34s duration=%6d ms  rate=%5d Hz  rms=%8.1f  peak=%6d  words=%d  effectiveWpm=%d  bytes=%d".format(
            name, durationMs, sampleRate, rms(merged), merged.maxOfOrNull { kotlin.math.abs(it.toInt()) } ?: 0,
            words, effectiveWpm, outFile.length(),
        )
    }

    /** Minimal WAV writer for the sweep's stitched output — [WavUtil] only builds headers. */
    private fun writeMono16(samples: ShortArray, sampleRate: Int, outFile: File) {
        val dataSize = samples.size * 2
        val buffer = java.nio.ByteBuffer.allocate(dataSize).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        samples.forEach { buffer.putShort(it) }
        outFile.outputStream().use { out ->
            out.write(WavUtil.buildWavHeader(dataSize, sampleRate, channels = 1, bitsPerSample = 16))
            out.write(buffer.array())
        }
    }

    private fun rms(samples: ShortArray): Double {
        if (samples.isEmpty()) return 0.0
        var sum = 0.0
        for (s in samples) sum += s.toDouble() * s.toDouble()
        return sqrt(sum / samples.size)
    }

    /** prepare -> [measure] -> release, so no sweep entry can leak a loaded native engine. */
    private suspend fun renderWith(provider: NeuralTtsProvider, name: String, outDir: File): String =
        try {
            assertTrue("$name must load", provider.prepare())
            measure(provider, name, outDir)
        } finally {
            provider.release()
        }

    private companion object {
        const val TAG = "VoiceSampleSweep"
    }
}
