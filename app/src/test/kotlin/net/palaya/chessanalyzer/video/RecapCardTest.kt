package net.palaya.chessanalyzer.video

import net.palaya.chessanalyzer.core.analysis.MoveClassification
import net.palaya.chessanalyzer.core.chess.Color
import net.palaya.chessanalyzer.core.narration.RECAP_COUNT_CLASSES
import net.palaya.chessanalyzer.core.narration.RecapCount
import net.palaya.chessanalyzer.core.narration.RecapMoment
import net.palaya.chessanalyzer.core.narration.RecapSide
import net.palaya.chessanalyzer.core.narration.VideoRecap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.ceil

/**
 * R6b: the recap end card's pure parts. Words, number format, duration, the text-fit rule that keeps
 * a long name from clipping, and the chip flow. Drawing it is checked on the device
 * (`VideoRecapExportInstrumentedTest`); this is everything the drawing decides *with*.
 */
class RecapCardTest {

    // -----------------------------------------------------------------------
    // Accuracy: the Summary screen's format and colours
    // -----------------------------------------------------------------------

    @Test
    fun `accuracy is written as the Summary writes it, whole percent with a direction mark`() {
        for (accuracy in listOf(0.0, 4.5, 64.99, 86.5, 86.49, 97.3, 100.0)) {
            // The Summary: LRM + "%.0f%%".
            assertEquals("‎" + "%.0f%%".format(accuracy), recapAccuracyText(accuracy))
        }
        assertEquals("‎87%", recapAccuracyText(86.5))
        assertEquals("‎97%", recapAccuracyText(97.3))
    }

    @Test
    fun `the colour bands switch exactly at 85 and 65`() {
        assertEquals(AccuracyBand.GOOD, accuracyBand(100.0))
        assertEquals(AccuracyBand.GOOD, accuracyBand(85.0))
        assertEquals(AccuracyBand.MID, accuracyBand(84.99))
        assertEquals(AccuracyBand.MID, accuracyBand(65.0))
        assertEquals(AccuracyBand.LOW, accuracyBand(64.99))
        assertEquals(AccuracyBand.LOW, accuracyBand(0.0))
    }

    // -----------------------------------------------------------------------
    // Duration: 4 to 6 seconds, outside the pacing budget
    // -----------------------------------------------------------------------

    @Test
    fun `the card stays up between four and six seconds and longer text stays longer`() {
        assertEquals(4_000L, recapDurationMs(0))
        assertEquals(4_000L, recapDurationMs(-5))
        assertEquals(4_000L, recapDurationMs(10))
        assertEquals(5_500L, recapDurationMs(25))
        assertEquals(6_000L, recapDurationMs(30))
        assertEquals(6_000L, recapDurationMs(10_000))
        var previous = 0L
        for (words in 0..60) {
            val ms = recapDurationMs(words)
            assertTrue("$words words: $ms", ms in RECAP_MIN_MS..RECAP_MAX_MS && ms >= previous)
            previous = ms
        }
    }

    // -----------------------------------------------------------------------
    // The words on the card
    // -----------------------------------------------------------------------

    private fun side(
        color: Color, name: String, accuracy: Double, isUser: Boolean = false,
        counts: List<RecapCount> = emptyList(),
    ) = RecapSide(color, name, isUser, accuracy, counts)

    private val recap = VideoRecap(
        white = side(Color.WHITE, "MorphyFan1857", 91.4, isUser = true, counts = listOf(RecapCount(MoveClassification.BRILLIANT, 1), RecapCount(MoveClassification.INACCURACY, 2))),
        black = side(Color.BLACK, "DukeAndCount", 62.0, counts = listOf(RecapCount(MoveClassification.MISTAKE, 1), RecapCount(MoveClassification.BLUNDER, 3))),
        summary = "Black was fine until move 11, then a blunder decided it.",
        biggestMoment = RecapMoment(11, Color.BLACK, "Nf6", MoveClassification.BLUNDER),
    )

    @Test
    fun `the card holds both players, their accuracy, the sentence, the moment and the counts`() {
        val card = RecapCardContent.from(recap, RecapLabels.ENGLISH)

        assertEquals("Game recap", card.heading)
        assertEquals("Accuracy", card.accuracyLabel)
        assertEquals(listOf(true, false), card.sides.map { it.isWhite })
        assertEquals(listOf("MorphyFan1857", "DukeAndCount"), card.sides.map { it.name })
        assertEquals(listOf("(you)", null), card.sides.map { it.youMarker })
        assertEquals(listOf("‎91%", "‎62%"), card.sides.map { it.accuracyText })
        assertEquals(listOf(AccuracyBand.GOOD, AccuracyBand.LOW), card.sides.map { it.band })
        assertEquals(listOf("‎Brilliant ×1", "‎Inaccuracy ×2"), card.sides[0].chips.map { it.text })
        assertEquals(listOf("‎Mistake ×1", "‎Blunder ×3"), card.sides[1].chips.map { it.text })
        assertEquals(
            listOf(MoveClassification.MISTAKE, MoveClassification.BLUNDER), card.sides[1].chips.map { it.classification },
        )
        assertEquals("Black was fine until move 11, then a blunder decided it.", card.summary)
        // The SAN keeps its own direction (LRI..PDI) whatever the sentence around it does.
        assertEquals("Biggest moment: move 11, ⁦Nf6⁩ (Blunder by Black)", card.momentLine)
        assertEquals(MoveClassification.BLUNDER, card.momentClassification)
    }

    @Test
    fun `no biggest moment and no summary mean no such line, never a placeholder`() {
        val card = RecapCardContent.from(recap.copy(summary = null, biggestMoment = null), RecapLabels.ENGLISH)
        assertNull(card.summary)
        assertNull(card.momentLine)
        assertNull(card.momentClassification)
        assertEquals(0, card.readingWords)
        assertEquals(RECAP_MIN_MS, recapDurationMs(card.readingWords))
        // A blank summary is the same as none.
        assertNull(RecapCardContent.from(recap.copy(summary = "  "), RecapLabels.ENGLISH).summary)
    }

    @Test
    fun `a side with no counted moves draws no chips and a clean game has none at all`() {
        val clean = recap.copy(white = recap.white.copy(counts = emptyList()), black = recap.black.copy(counts = emptyList()))
        val card = RecapCardContent.from(clean, RecapLabels.ENGLISH)
        assertTrue(card.sides.all { it.chips.isEmpty() })
    }

    @Test
    fun `the reading words are those of the two sentences, and set the time`() {
        val card = RecapCardContent.from(recap, RecapLabels.ENGLISH)
        // "Black was fine until move 11, then a blunder decided it." = 11 words;
        // "Biggest moment: move 11, <Nf6> (Blunder by Black)" = 8 words.
        assertEquals(11 + 8, card.readingWords)
        assertEquals(4_900L, recapDurationMs(card.readingWords))
    }

    @Test
    fun `the labels are resolved by the caller, so a translation changes every word on the card`() {
        val hebrewLike = RecapLabels(
            heading = "H", accuracy = "A", youMarker = "Y",
            side = { if (it == Color.WHITE) "W" else "B" },
            className = { "c:${it.name}" },
            biggestMoment = { n, san, by -> "$by/$n/$san" },
            qualityBy = { c, s -> "$s>$c" },
            countChip = { c, n -> "$n*$c" },
        )
        val card = RecapCardContent.from(recap, hebrewLike)
        assertEquals("H", card.heading)
        assertEquals("A", card.accuracyLabel)
        assertEquals(listOf("Y", null), card.sides.map { it.youMarker })
        assertEquals("B>c:BLUNDER/11/⁦Nf6⁩", card.momentLine)
        assertEquals("‎3*c:BLUNDER", card.sides[1].chips.last().text)
    }

    @Test
    fun `the counted classes are the six the core recap names`() {
        assertEquals(6, RECAP_COUNT_CLASSES.size)
        assertFalse(MoveClassification.BOOK in RECAP_COUNT_CLASSES)
        assertFalse(MoveClassification.FORCED in RECAP_COUNT_CLASSES)
    }

    // -----------------------------------------------------------------------
    // Text fit: the shortest and the longest realistic names
    // -----------------------------------------------------------------------

    /** A monospaced stand-in for `Paint.measureText`: every char is 0.55 of the size wide. */
    private val measure: (String, Float) -> Float = { text, size -> text.length * size * 0.55f }

    // The card's own numbers at 1280x720: the name box is 40 percent of the width less the dot and "(you)".
    private val nameBox = 1280f * 0.40f - 2 * 14.4f - 10.8f - 70f
    private val maxName = 720f * 0.056f
    private val minName = 720f * 0.032f

    @Test
    fun `the shortest name is drawn at full size, untouched`() {
        for (name in listOf("A", "Bo", "אב", "Li", "White")) {
            val fit = fitLine(name, nameBox, maxName, minName, measure)
            assertEquals(name, fit.text)
            assertEquals(maxName, fit.size, 0.0f)
            assertFalse(fit.ellipsized)
        }
    }

    @Test
    fun `a longer name shrinks before it is cut`() {
        // 19 characters at 40.3 px would be 421 px: too wide for a 402 px box, so it steps down to 38.3 px.
        val fit = fitLine("MorphyFan1857_Chess", nameBox, maxName, minName, measure)
        assertFalse(fit.ellipsized)
        assertEquals("MorphyFan1857_Chess", fit.text)
        assertTrue(fit.size < maxName && fit.size >= minName)
        assertTrue(measure(fit.text, fit.size) <= nameBox)
        // One pixel bigger would not have fitted: the largest size that does is chosen.
        assertTrue(measure(fit.text, fit.size + 1f) > nameBox)
    }

    @Test
    fun `the longest realistic names end in an ellipsis at the smallest size and never overflow`() {
        // chess.com allows 25 characters; PGN tags in the wild run to 40 and beyond.
        val names = listOf(
            "ABCDEFGHIJKLMNOPQRSTUVWXY",
            "SomeVeryLongUsername_1234567890_extra",
            "Magnus Carlsen (Norway) - World Champion",
            "ש".repeat(60),
            "Ünïcödé-Namé ".repeat(6),
        )
        for (name in names) {
            val fit = fitLine(name, nameBox, maxName, minName, measure)
            assertTrue("$name -> ${fit.text} at ${fit.size}: ${measure(fit.text, fit.size)} > $nameBox", measure(fit.text, fit.size) <= nameBox)
            assertTrue(fit.size in minName..maxName)
            if (fit.ellipsized) {
                assertEquals(minName, fit.size, 0.0f)
                assertTrue(fit.text.endsWith("…"))
                assertTrue(name.startsWith(fit.text.removeSuffix("…")))
            }
        }
        assertTrue(fitLine(names[2], nameBox, maxName, minName, measure).ellipsized)
    }

    @Test
    fun `whatever the length, a name fits its box and the text is an honest prefix`() {
        for (length in 0..120) {
            val name = "x".repeat(length)
            val fit = fitLine(name, nameBox, maxName, minName, measure)
            assertTrue("length $length", measure(fit.text, fit.size) <= nameBox)
            assertTrue(fit.size in minName..maxName)
            assertEquals("length $length", fit.ellipsized, fit.text != name)
            if (fit.ellipsized) {
                // As many characters as fit: one more would be too wide.
                val kept = fit.text.removeSuffix("…").length
                assertTrue(measure(name.substring(0, kept + 1) + "…", minName) > nameBox)
            }
        }
    }

    @Test
    fun `a box too small for even the ellipsis still returns the ellipsis rather than failing`() {
        val fit = fitLine("Someone", 1f, 40f, 20f, measure)
        assertEquals("…", fit.text)
        assertTrue(fit.ellipsized)
    }

    // ---- the paragraph ----

    /** Lines a text wraps to at [size] in [width], with the same monospaced stand-in. */
    private fun lines(text: String, size: Float, width: Float) =
        ceil(text.length * size * 0.55f / width).toInt().coerceAtLeast(1)

    @Test
    fun `a short sentence keeps the big size and a long one steps down until it fits its lines`() {
        val width = 1024f
        val short = "Black was fine until move 11, then a blunder decided it."
        val fitShort = fitParagraph(32.4f, 24.5f, 3) { lines(short, it, width) }
        assertEquals(32.4f, fitShort.size, 0f)
        assertFalse(fitShort.truncated)

        val longish = listOf(short, short, short, short).joinToString(" ")
        val fit = fitParagraph(32.4f, 24.5f, 3) { lines(longish, it, width) }
        assertTrue(fit.size < 32.4f)
        assertTrue(fit.lineCount <= 3)
        assertFalse(fit.truncated)
        assertTrue(lines(longish, fit.size + 1f, width) > 3 || fit.size + 1f > 32.4f)
    }

    @Test
    fun `a sentence too long for three lines at the smallest size is truncated, not shrunk without end`() {
        val text = "word ".repeat(200)
        val fit = fitParagraph(32.4f, 24.5f, 3) { lines(text, it, 1024f) }
        assertEquals(24.5f, fit.size, 0f)
        assertEquals(3, fit.lineCount)
        assertTrue(fit.truncated)
    }

    // -----------------------------------------------------------------------
    // Chip flow
    // -----------------------------------------------------------------------

    @Test
    fun `chips fill a row and wrap to the next, in order, nothing dropped`() {
        assertEquals(emptyList<List<Int>>(), flowChips(emptyList(), 512f, 10f))
        assertEquals(listOf(listOf(0, 1, 2)), flowChips(listOf(150f, 150f, 150f), 512f, 10f))
        // 3 x 160 + 2 gaps = 500 fits; 3 x 170 + 2 = 530 does not.
        assertEquals(listOf(listOf(0, 1, 2)), flowChips(listOf(160f, 160f, 160f), 512f, 10f))
        assertEquals(listOf(listOf(0, 1), listOf(2)), flowChips(listOf(170f, 170f, 170f), 512f, 10f))
        // Exactly full is allowed; one pixel over wraps.
        assertEquals(listOf(listOf(0, 1)), flowChips(listOf(251f, 251f), 512f, 10f))
        assertEquals(listOf(listOf(0), listOf(1)), flowChips(listOf(252f, 251f), 512f, 10f))
    }

    @Test
    fun `a chip wider than the row gets a row of its own and the rest carry on`() {
        assertEquals(listOf(listOf(0), listOf(1, 2)), flowChips(listOf(600f, 100f, 100f), 512f, 10f))
        val six = flowChips(List(6) { 190f }, 512f, 12f)
        assertEquals(listOf(listOf(0, 1), listOf(2, 3), listOf(4, 5)), six)
        assertEquals((0..5).toList(), six.flatten())
        for (row in six) assertTrue(row.size * 190f + (row.size - 1) * 12f <= 512f)
    }

    @Test
    fun `at the worst case every chip of both sides fits three rows`() {
        // Six classes, each at most "Inaccuracy ×12" in a bold face: about 215 px at 23 px.
        val widths = List(6) { 215f }
        assertTrue(flowChips(widths, 1280f * 0.40f, 8.6f).size <= 3)
        assertNotNull(RECAP_COUNT_CLASSES)
    }
}
