package net.palaya.chessanalyzer.video

import net.palaya.chessanalyzer.core.analysis.GameReport
import net.palaya.chessanalyzer.core.analysis.MoveAnnotation
import net.palaya.chessanalyzer.core.analysis.MoveClassification
import net.palaya.chessanalyzer.core.analysis.PlayerReport
import net.palaya.chessanalyzer.core.chess.Color
import net.palaya.chessanalyzer.core.narration.BoardDirective
import net.palaya.chessanalyzer.core.narration.NarrationOptions
import net.palaya.chessanalyzer.core.narration.ScriptSegment
import net.palaya.chessanalyzer.core.narration.SegmentKind
import net.palaya.chessanalyzer.core.narration.VideoGameHeader
import net.palaya.chessanalyzer.core.narration.VideoScript
import net.palaya.chessanalyzer.core.narration.VideoScriptGenerator
import net.palaya.chessanalyzer.core.pgn.PgnParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R6c: the title cards' pure parts. What the intro and the final numbers print (each fact once,
 * whole-percent accuracy, names isolated), where text may go, and the title / paragraph fit rules that
 * keep a long name from running under the caption bar. Drawing is checked on the device
 * (`CardFrameFitInstrumentedTest`); this is everything the drawing decides *with*.
 */
class CardLayoutTest {

    private val fsi = 0x2068.toChar()
    private val pdi = 0x2069.toChar()

    /** A fake measurer: every character is 0.6 em wide, bold or not. */
    private val measure = { text: String, size: Float -> text.length * size * 0.6f }

    // -----------------------------------------------------------------------
    // The words: each fact once
    // -----------------------------------------------------------------------

    private val header = VideoGameHeader(
        whiteName = "MorphyFan1857", blackName = "DukeAndCount", whiteRating = 1500, blackRating = 1450,
        result = "1-0", openingName = "Philidor Defense", openingEco = "C41",
    )

    private fun segment(kind: SegmentKind, heading: String, lines: List<String>, caption: String = "cap") = ScriptSegment(
        index = 0, kind = kind, ply = null, narration = "n", caption = caption,
        board = BoardDirective.Card(heading, lines), estimatedSpeechMs = 1_000L,
    )

    private fun script(header: VideoGameHeader? = this.header) = VideoScript(
        title = "${header?.whiteName ?: "A"} vs ${header?.blackName ?: "B"}", subtitle = "s",
        segments = emptyList(), chapters = emptyList(), totalEstimatedMs = 0L, userColor = null, header = header,
    )

    private val introLines = listOf("1-0 · 17 moves · Philidor Defense (C41)", "White 84% · Black 79%")

    private fun intro(script: VideoScript = script(), lines: List<String> = introLines) =
        CardContents.forSegment(script, SegmentKind.INTRO, script.title, lines)

    private fun occurrences(text: String, needle: String) = text.windowed(needle.length).count { it == needle }

    @Test
    fun `the intro card says each fact once`() {
        val card = intro()
        val all = card.allText.joinToString("\n")
        for (fact in listOf("MorphyFan1857", "DukeAndCount", "Philidor Defense", "C41", "1-0", "17 moves", "(1500)", "(1450)", "84%", "79%")) {
            assertEquals("'$fact' in\n$all", 1, occurrences(all, fact))
        }
        // No labels left over from the old sub-lines.
        assertFalse(all.contains("Result:"))
        assertFalse(all.contains("White:"))
        assertFalse(all.contains("Black:"))
    }

    @Test
    fun `the intro is a title, one subtitle line and one accuracy line`() {
        val card = intro()
        assertTrue(card.title is CardTitle.Versus)
        assertEquals(listOf(CardParagraph(introLines[0], maxLines = 1)), card.body)
        assertEquals(listOf(introLines[1]), card.facts)
    }

    @Test
    fun `the title carries both names, ratings and the script's own word between them, each isolated`() {
        val title = intro().title as CardTitle.Versus
        assertEquals("${fsi}MorphyFan1857$pdi (1500)", title.white)
        assertEquals(" vs ", title.versus)
        assertEquals("${fsi}DukeAndCount$pdi (1450)", title.black)

        // A rating the PGN does not have is not invented.
        val unrated = CardContents.versusTitle("A vs B", header.copy(whiteName = "A", blackName = "B", whiteRating = null, blackRating = null))
        assertEquals("${fsi}A$pdi", unrated.white)
        assertEquals("${fsi}B$pdi", unrated.black)

        // The word between the names is whatever the script's title says (another language, say).
        val other = CardContents.versusTitle("Alice contre Bob", header.copy(whiteName = "Alice", blackName = "Bob"))
        assertEquals(" contre ", other.versus)

        // A Hebrew name keeps the rating outside its isolate, so the brackets and digits are not reordered.
        val hebrew = CardContents.versusTitle("דוד vs משה", header.copy(whiteName = "דוד", blackName = "משה", whiteRating = 1800, blackRating = null))
        assertEquals("${fsi}דוד$pdi (1800) vs ${fsi}משה$pdi", hebrew.text)
    }

    @Test
    fun `a script without a header keeps the generator's heading, and a lone line is not made green`() {
        val bare = intro(script(header = null), lines = listOf("only line"))
        assertEquals(CardTitle.Plain("A vs B"), bare.title)
        assertEquals(listOf(CardParagraph("only line", maxLines = 1)), bare.body)
        assertTrue(bare.facts.isEmpty())
    }

    @Test
    fun `the final numbers and the lesson cards keep their lines and add nothing`() {
        val s = script()
        val lines = listOf("${fsi}MorphyFan1857$pdi — 84%, est. 1650", "${fsi}DukeAndCount$pdi — 79%, est. 1500", "Blunders 1–2 · Mistakes 0–1", "1-0")
        val outro = CardContents.forSegment(s, SegmentKind.OUTRO_SUMMARY, "Final numbers", lines)
        assertEquals(CardTitle.Plain("Final numbers"), outro.title)
        assertEquals(lines, outro.body.map { it.text })
        assertTrue("no structured sub-lines repeating the accuracy", outro.facts.isEmpty())
        // The accuracy and each name appear once on the card.
        val all = outro.allText.joinToString("\n")
        assertEquals(1, occurrences(all, "84%"))
        assertEquals(1, occurrences(all, "MorphyFan1857"))

        val lesson = CardContents.forSegment(s, SegmentKind.OUTRO_LESSONS, "What to work on", listOf("Look for forks."))
        assertEquals(listOf(CardParagraph("Look for forks.")), lesson.body)
        assertTrue(lesson.facts.isEmpty())
    }

    @Test
    fun `the intro and the final numbers have no caption bar, the lessons keep theirs`() {
        assertEquals("", CardContents.captionFor(SegmentKind.INTRO, "A vs B"))
        assertEquals("", CardContents.captionFor(SegmentKind.OUTRO_SUMMARY, "White 84% (1650) · Black 79% (1500)"))
        assertEquals("Takeaway 1 of 3", CardContents.captionFor(SegmentKind.OUTRO_LESSONS, "Takeaway 1 of 3"))
    }

    // -----------------------------------------------------------------------
    // Accuracy: the same whole percent as the Summary and the recap, end to end
    // -----------------------------------------------------------------------

    private fun annotation(ply: Int) = MoveAnnotation(
        ply = ply, moveNumber = (ply + 1) / 2, color = if (ply % 2 == 1) Color.WHITE else Color.BLACK,
        san = "m$ply", uci = "a2a3", fenBefore = "", fenAfter = "", classification = MoveClassification.GOOD,
        loss = 0.0, winPercentBefore = 55.0, winPercentAfter = 55.0, evalBeforeCp = 0, evalAfterCp = 0,
    )

    private fun generated(white: String, black: String, whiteAcc: Double, blackAcc: Double): VideoScript {
        fun player(color: Color, name: String, acc: Double) =
            PlayerReport(color, name, acc, 1500, false, emptyMap(), emptyList(), emptyList())
        val report = GameReport(
            white = player(Color.WHITE, white, whiteAcc), black = player(Color.BLACK, black, blackAcc),
            annotations = (1..10).map { annotation(it) }, openingName = "Philidor Defense", openingEco = "C41",
            result = "1-0", evalGraph = List(11) { 50.0 }, keyMoments = emptyList(), analysisDepth = 12,
        )
        val pgn = PgnParser.parse("[White \"$white\"]\n[Black \"$black\"]\n[Result \"1-0\"]\n\n1. e4 e5 1-0").single()
        return VideoScriptGenerator(null).generate(report, pgn, NarrationOptions())
    }

    @Test
    fun `the intro and the outro print the accuracy the way the Summary and the recap do`() {
        val decimal = Regex("\\d+\\.\\d+\\s*%")
        for ((w, b) in listOf(84.1 to 79.5, 100.0 to 0.4, 85.0 to 64.99, 7.2 to 86.5)) {
            val s = generated("MorphyFan1857", "DukeAndCount", w, b)
            val wText = recapAccuracyText(w).trimStart(0x200E.toChar())
            val bText = recapAccuracyText(b).trimStart(0x200E.toChar())

            val introSegment = s.segments.first { it.kind == SegmentKind.INTRO }
            val introCard = (introSegment.board as BoardDirective.Card)
            val content = CardContents.forSegment(s, SegmentKind.INTRO, introCard.heading, introCard.lines)
            assertEquals("White $wText · Black $bText", content.facts.single())

            val outroSegment = s.segments.first { it.kind == SegmentKind.OUTRO_SUMMARY }
            val outroCard = outroSegment.board as BoardDirective.Card
            val outro = CardContents.forSegment(s, SegmentKind.OUTRO_SUMMARY, outroCard.heading, outroCard.lines)
            assertTrue(outro.body[0].text.contains("— $wText,"))
            assertTrue(outro.body[1].text.contains("— $bText,"))
            assertTrue(outroSegment.caption.contains(wText) && outroSegment.caption.contains(bText))

            for (text in content.allText + outro.allText + outroSegment.caption) {
                assertFalse("decimal accuracy in '$text'", decimal.containsMatchIn(text))
            }
            // The recap card writes the same number.
            assertEquals(recapAccuracyText(w), RecapSideContent(true, "n", null, w, recapAccuracyText(w), accuracyBand(w), emptyList()).accuracyText)
        }
    }

    @Test
    fun `an intro generated from the real generator says each fact once, for Latin and Hebrew names`() {
        for ((w, b) in listOf("MorphyFan1857" to "DukeAndCount", "דוד בן־גוריון" to "משה דיין", "Magnus Carlsen" to "Hikaru Nakamura")) {
            val s = generated(w, b, 84.1, 79.4)
            val c = (s.segments.first { it.kind == SegmentKind.INTRO }.board as BoardDirective.Card)
            val card = CardContents.forSegment(s, SegmentKind.INTRO, c.heading, c.lines)
            val all = card.allText.joinToString("\n")
            for (fact in listOf(w, b, "Philidor Defense", "C41", "1-0", "84%", "79%")) {
                assertEquals("'$fact' in\n$all", 1, occurrences(all, fact))
            }
        }
        assertNotNull(generated("A", "B", 50.0, 50.0))
    }

    // -----------------------------------------------------------------------
    // Where text may go
    // -----------------------------------------------------------------------

    @Test
    fun `the text column and the caption bar zone never overlap`() {
        for (h in listOf(360f, 720f, 1080f, 2160f)) {
            val captionTop = h - h * CAPTION_BAR_FRACTION
            assertTrue("$h: content ends above the caption bar", CardGeometry.contentBottom(h) < captionTop)
            assertTrue("$h: content starts below the chapter bar", CardGeometry.contentTop(h) > h * CardGeometry.CHAPTER_BAR)
            assertTrue(CardGeometry.contentTop(h) < CardGeometry.contentBottom(h))
        }
        for (w in listOf(640f, 1280f, 1920f)) {
            assertEquals("symmetric margins", CardGeometry.textLeft(w), w - (CardGeometry.textLeft(w) + CardGeometry.textWidth(w)), 0.001f)
            assertTrue("past the accent bar", CardGeometry.textLeft(w) > w * 0.015f)
        }
    }

    // -----------------------------------------------------------------------
    // Title fit
    // -----------------------------------------------------------------------

    private fun layout(title: CardTitle, width: Float = 1024f) =
        layoutTitle(title, width, maxSize = 64f, minSingleSize = 42f, stackedMaxSize = 54f, minSize = 29f, measure = measure)

    private fun versus(white: String, black: String) = CardTitle.Versus(white, " vs ", black)

    @Test
    fun `a short title is one line at the full size`() {
        val l = layout(versus("Al", "Bo"))
        assertFalse(l.stacked)
        assertEquals(1, l.lines.size)
        assertEquals(64f, l.lines[0].size, 0f)
        assertEquals("Al vs Bo", l.lines[0].text)
    }

    @Test
    fun `a medium title shrinks on one line before it stacks`() {
        // 30 characters: 64 px is 1152 wide, 56 px or less fits.
        val l = layout(versus("MorphyFan1857Abcdef", "DukeAndCount"))
        assertFalse(l.stacked)
        assertTrue(l.lines[0].size in 42f..63f)
        assertTrue(measure(l.lines[0].text, l.lines[0].size) <= 1024f)
        assertFalse(l.lines[0].ellipsized)
    }

    @Test
    fun `long names stack on two lines and keep both names`() {
        val white = "ABCDEFGHIJKLMNOPQRSTUVWXY"
        val black = "SomeVeryLongUsername_1234567890"
        val l = layout(versus(white, black))
        assertTrue(l.stacked)
        assertEquals(2, l.lines.size)
        assertEquals(white, l.lines[0].text)
        assertEquals("vs $black", l.lines[1].text)
        assertEquals("one size for both lines", l.lines[0].size, l.lines[1].size, 0f)
        assertTrue(l.lines.all { measure(it.text, it.size) <= 1024f && !it.ellipsized })
    }

    @Test
    fun `an overlong name is cut with an ellipsis at the smallest size, never wider than the column`() {
        for ((white, black) in listOf("ש".repeat(60) to "ת".repeat(45), "W".repeat(80) to "B".repeat(80), "x".repeat(300) to "y")) {
            val l = layout(versus(white, black))
            assertTrue(l.stacked)
            for (line in l.lines) {
                assertTrue("${line.text.length} chars at ${line.size}", measure(line.text, line.size) <= 1024f)
                assertTrue(line.size >= 29f)
            }
        }
        val plain = layout(CardTitle.Plain("y".repeat(300)))
        assertTrue(plain.lines.single().ellipsized)
        assertTrue(measure(plain.lines.single().text, plain.lines.single().size) <= 1024f)
        assertFalse(plain.stacked)
    }

    @Test
    fun `no title, short to absurd, is ever wider than the column or smaller than the minimum`() {
        var len = 1
        while (len <= 400) {
            val l = layout(versus("a".repeat(len), "b".repeat((len / 2).coerceAtLeast(1))))
            for (line in l.lines) {
                assertTrue("$len: ${measure(line.text, line.size)}", measure(line.text, line.size) <= 1024f)
                assertTrue(line.size in 29f..64f)
            }
            len += 7
        }
    }

    // -----------------------------------------------------------------------
    // Paragraph fit to the room left
    // -----------------------------------------------------------------------

    @Test
    fun `a paragraph takes the largest size whose height fits the room`() {
        // 100 characters of 0.5 em at 800 px: lines = ceil(100 * 0.5 * size / 800); a line is 1.2 em.
        fun lines(size: Float) = Math.ceil(100 * 0.5 * size / 800.0).toInt()
        fun height(size: Float) = lines(size) * size * 1.2f
        val fit = fitParagraphToHeight(36f, 24f, 200f, ::height, { it * 1.2f }, ::lines)
        assertFalse(fit.truncated)
        assertTrue(height(fit.size) <= 200f)
        if (fit.size < 36f) assertTrue("one pixel larger would not fit", height(fit.size + 1f) > 200f)
    }

    @Test
    fun `a paragraph too long even at the smallest size is cut to the lines that fit`() {
        fun lines(size: Float) = Math.ceil(2000 * 0.5 * size / 800.0).toInt()
        fun height(size: Float) = lines(size) * size * 1.2f
        val fit = fitParagraphToHeight(36f, 24f, 200f, ::height, { it * 1.2f }, ::lines)
        assertTrue(fit.truncated)
        assertEquals(24f, fit.size, 0f)
        assertTrue(fit.lineCount * 24f * 1.2f <= 200f)
        assertEquals((200f / (24f * 1.2f)).toInt(), fit.lineCount)
    }

    @Test
    fun `a room smaller than one line still shows one line`() {
        val fit = fitParagraphToHeight(36f, 24f, 5f, { 100f }, { 30f }, { 4 })
        assertTrue(fit.truncated)
        assertEquals(1, fit.lineCount)
    }
}
