package net.palaya.chessanalyzer.video

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Proves the device-narration naturalness work is real, not just code that exists but is never
 * exercised:
 *  1. [NarrationSynthesizer.prepareEngine] actually selects a voice and reports it (rather than
 *     silently leaving the engine on whatever its own default was).
 *  2. Sentence-paced synthesis of multi-sentence narration produces measurably MORE total audio
 *     than the exact same words spoken as one flat utterance — proof the inter-sentence silence
 *     is really being inserted at runtime, not just present in source but unreachable.
 */
@RunWith(AndroidJUnit4::class)
class NarrationSynthesizerInstrumentedTest {

    /** Three sentences, the last a question — exercises both the normal and question-gap paths. */
    private val multiSentenceNarration =
        "White pushes the pawn to e4. Black replies symmetrically with e5. Can you already see the idea?"

    @Test
    fun prepareEngineSelectsAndReportsAVoice(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val synthesizer = NarrationSynthesizer(context)
        try {
            val ready = synthesizer.prepareEngine()
            assertTrue(
                "TTS engine should initialize on this emulator (Google TTS is installed) — if " +
                    "this fails, the emulator image itself has no usable voice, which is an " +
                    "environment gap, not something this test can distinguish from a real defect",
                ready,
            )

            val voice = synthesizer.selectedVoiceInfo
            assertNotNull("prepareEngine() should have selected and reported a voice", voice)
            assertTrue("reported voice name should not be blank", voice!!.name.isNotBlank())

            android.util.Log.i(
                "NarrationSynthesizerTest",
                "selected voice: name=${voice.name} locale=${voice.locale} " +
                    "quality=${voice.quality} networkRequired=${voice.isNetworkConnectionRequired}",
            )
        } finally {
            synthesizer.release()
        }
    }

    @Test
    fun sentencePacedSynthesisIsLongerThanOneFlatUtteranceOfTheSameWords(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val outputDir = File(context.cacheDir, "narration_pacing_test").apply { mkdirs() }

        // ---- Paced: the real narration, split into 3 sentences by NarrationSynthesizer and
        // ---- stitched back together with inter-sentence silence (see synthesizePaced).
        val pacedSynthesizer = NarrationSynthesizer(context)
        val pacedReady = pacedSynthesizer.prepareEngine()
        assertTrue("this test needs a usable TTS engine/voice to prove the pacing is real", pacedReady)
        val pacedFile = File(outputDir, "paced.wav")
        val pacedResult = pacedSynthesizer.synthesizeSegment(multiSentenceNarration, pacedFile, estimatedMs = 3_000L)
        pacedSynthesizer.release()

        // ---- Flat: the exact same words, but with the sentence-terminal punctuation swapped for
        // ---- commas so NarrationSynthesizer.splitIntoSentences sees it as a single "sentence" and
        // ---- takes the single-utterance path — same engine, same voice, same words, zero inserted
        // ---- gaps. This exercises the real public synthesizeSegment() entry point on both sides
        // ---- (a black-box comparison) rather than reaching into private synthesis internals.
        val flatText = multiSentenceNarration.replace(".", ",").replace("?", ",").trimEnd(',').plus(".")
        assertTrue(
            "sanity check: the flat text must collapse to one sentence for this test to be valid",
            NarrationSynthesizer(context).splitIntoSentences(flatText).size == 1,
        )
        val flatSynthesizer = NarrationSynthesizer(context)
        val flatReady = flatSynthesizer.prepareEngine()
        assertTrue(flatReady)
        val flatFile = File(outputDir, "flat.wav")
        val flatResult = flatSynthesizer.synthesizeSegment(flatText, flatFile, estimatedMs = 3_000L)
        flatSynthesizer.release()

        check(pacedResult is NarrationSynthesizer.Result.Synthesized) {
            "expected real synthesis for the paced narration, got $pacedResult"
        }
        check(flatResult is NarrationSynthesizer.Result.Synthesized) {
            "expected real synthesis for the flat narration, got $flatResult"
        }
        val pacedDurationMs = (pacedResult as NarrationSynthesizer.Result.Synthesized).durationMs
        val flatDurationMs = (flatResult as NarrationSynthesizer.Result.Synthesized).durationMs

        android.util.Log.i(
            "NarrationSynthesizerTest",
            "pacedDurationMs=$pacedDurationMs flatDurationMs=$flatDurationMs " +
                "diffMs=${pacedDurationMs - flatDurationMs} " +
                "(expected ~${NarrationSynthesizerPacingConstantsForTest.expectedMinGapMs}ms from 2 inserted gaps)",
        )

        // A comfortable margin below the ~440ms of gaps two sentence boundaries should insert
        // (220ms + 220ms, per NarrationSynthesizer's SENTENCE_GAP_MS), so real engine jitter in
        // natural speech length can't produce a false pass, but well above measurement noise.
        val minimumExpectedDifferenceMs = 250L
        assertTrue(
            "sentence-paced synthesis (${pacedDurationMs}ms) should be at least " +
                "${minimumExpectedDifferenceMs}ms longer than the same words as one flat " +
                "utterance (${flatDurationMs}ms) — the difference should be the inserted " +
                "inter-sentence silence, proving the pacing is actually applied at synthesis time",
            pacedDurationMs - flatDurationMs >= minimumExpectedDifferenceMs,
        )
    }
}

/** Documents the expected gap total for the log line above without hardcoding the private constants. */
private object NarrationSynthesizerPacingConstantsForTest {
    const val expectedMinGapMs = 440L
}
