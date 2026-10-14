package net.palaya.chessanalyzer.video

import net.palaya.chessanalyzer.core.chess.Position
import net.palaya.chessanalyzer.core.narration.BoardDirective
import net.palaya.chessanalyzer.core.narration.Delivery
import net.palaya.chessanalyzer.core.narration.ScriptSegment
import net.palaya.chessanalyzer.core.narration.SegmentKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** NC (ANALYSIS_SPEC 9.10): the delivery is in every narration cache key, and a neutral one changes no existing key. */
class NarrationKeysTest {

    private val name = "Natural voice (Kokoro)"
    private val fp = "KOKORO@v1/sid3/ls1.00/pr1"

    private fun segment(text: String, delivery: Delivery) = ScriptSegment(
        index = 0, kind = SegmentKind.NORMAL_MOVE, ply = null, narration = text, caption = "",
        board = BoardDirective.Hold(Position.STANDARD_START_FEN), estimatedSpeechMs = 1, delivery = delivery,
    )

    @Test
    fun `a neutral delivery leaves every key as it was, so old caches stay valid`() {
        val text = "White to play. Pause the video."
        val old = NarrationKeys.hash(text, name, fp)
        assertEquals(old, NarrationKeys.segmentKey(text, name, fp, Delivery.NEUTRAL))
        assertEquals(old, NarrationKeys.sentenceKey(text, name, fp, Delivery.NEUTRAL))
        assertEquals(old, NarrationKeys.segmentKey(segment(text, Delivery.NEUTRAL), name, fp))
        // the hash itself is the one NarrationStore.keyFor always computed: SHA-256 over name 0 fingerprint 0 text
        val md = java.security.MessageDigest.getInstance("SHA-256")
        md.update(name.toByteArray()); md.update(0); md.update(fp.toByteArray()); md.update(0); md.update(text.toByteArray())
        assertEquals(md.digest().joinToString("") { "%02x".format(it) }, old)
    }

    @Test
    fun `speed and gap scale each change the segment key, only the speed changes the sentence key`() {
        val text = "Knight takes the pawn."
        val neutral = NarrationKeys.segmentKey(text, name, fp, Delivery.NEUTRAL)
        val slow = NarrationKeys.segmentKey(text, name, fp, Delivery(speed = 0.9f))
        val slowGap = NarrationKeys.segmentKey(text, name, fp, Delivery(speed = 0.9f, gapScale = 1.4f))
        val gapOnly = NarrationKeys.segmentKey(text, name, fp, Delivery(gapScale = 1.4f))
        assertEquals(4, setOf(neutral, slow, slowGap, gapOnly).size)
        // a sentence clip is one utterance: gaps are not in it
        assertEquals(
            NarrationKeys.sentenceKey(text, name, fp, Delivery(speed = 0.9f)),
            NarrationKeys.sentenceKey(text, name, fp, Delivery(speed = 0.9f, gapScale = 1.4f)),
        )
        assertNotEquals(NarrationKeys.sentenceKey(text, name, fp, Delivery(speed = 0.9f)), NarrationKeys.sentenceKey(text, name, fp, Delivery.NEUTRAL))
        assertEquals(NarrationKeys.hash(text, name, fp), NarrationKeys.sentenceKey(text, name, fp, Delivery(gapScale = 1.4f)))
    }

    @Test
    fun `the suffix is locale independent with two decimals`() {
        val saved = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale("de", "DE"))
            assertEquals("/spd0.94", NarrationKeys.speedSuffix(0.94f))
            assertEquals("/gap1.25", NarrationKeys.gapSuffix(1.25f))
            assertEquals("", NarrationKeys.speedSuffix(1f))
            assertEquals("", NarrationKeys.gapSuffix(1.00001f))
        } finally {
            java.util.Locale.setDefault(saved)
        }
    }

    @Test
    fun `a segment key function agrees with its parts for every named delivery`() {
        val text = "Back to the game now."
        for (d in listOf(Delivery.CALM, Delivery.SETUP, Delivery.BRISK, Delivery.WEIGHTY)) {
            assertEquals(NarrationKeys.segmentKey(text, name, fp, d), NarrationKeys.segmentKey(segment(text, d), name, fp))
            assertTrue(NarrationKeys.segmentKey(text, name, fp, d) != NarrationKeys.hash(text, name, fp))
        }
        // the four named deliveries are four different clips
        val keys = listOf(Delivery.CALM, Delivery.SETUP, Delivery.BRISK, Delivery.WEIGHTY).map { NarrationKeys.segmentKey(text, name, fp, it) }
        assertEquals(4, keys.toSet().size)
    }
}
