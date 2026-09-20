package net.palaya.chessanalyzer.video

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Guards the sentence splitter that drives both narration pacing and the per-sentence audio cache.
 *
 * The splitter decides where the voice pauses AND what the cache is keyed on, so a bad split is
 * heard by the user *and* poisons cache reuse. The decimal case below was a real defect: accuracy
 * is spoken to one decimal place, and splitting on every '.' cut straight through the number.
 */
@RunWith(AndroidJUnit4::class)
class SentenceSplitTest {

    private fun split(text: String) = NarrationSynthesizer.splitIntoSentences(text)

    /** The regression: "59.9" must stay one number, not become "59." + "9 percent…". */
    @Test
    fun decimalPointIsNotTreatedAsASentenceEnd() {
        val sentences = split("You finished on 59.9 percent accuracy. Black managed 81.5 percent.")

        assertEquals("expected exactly two sentences", 2, sentences.size)
        assertTrue(
            "the decimal must survive intact, got: ${sentences[0]}",
            sentences[0].contains("59.9"),
        )
        assertTrue(
            "the second decimal must survive intact, got: ${sentences[1]}",
            sentences[1].contains("81.5"),
        )
        // And the number must not have been orphaned into its own fragment.
        assertTrue("no fragment may start with a bare decimal digit", sentences.none { it.startsWith("9 ") })
    }

    @Test
    fun ordinarySentencesStillSplitOnEveryTerminator() {
        val sentences = split("Knight takes e5. Is that good? Yes! It wins a pawn.")
        assertEquals(4, sentences.size)
        assertTrue(sentences[1].endsWith("?"))
        assertTrue(sentences[2].endsWith("!"))
    }

    @Test
    fun aSingleSentenceWithNoTerminatorIsKept() {
        val sentences = split("Back in the real game, though, that got played instead")
        assertEquals(1, sentences.size)
        assertTrue(sentences[0].startsWith("Back in the real game"))
    }

    @Test
    fun multipleDecimalsInOneSentenceAllSurvive() {
        val sentences = split("White 95.1 percent, Black 81.5 percent, a gap of 13.6 points.")
        assertEquals("this is one sentence", 1, sentences.size)
        listOf("95.1", "81.5", "13.6").forEach {
            assertTrue("lost decimal $it in: ${sentences[0]}", sentences[0].contains(it))
        }
    }

    @Test
    fun emptyAndBlankInputProduceNoSentences() {
        assertTrue(split("").isEmpty())
        assertTrue(split("   ").isEmpty())
    }
}
