package net.palaya.chessanalyzer.video

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.ui.graphics.toArgb
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.FileOutputStream
import net.palaya.chessanalyzer.core.analysis.GameReport
import net.palaya.chessanalyzer.core.analysis.MoveAnnotation
import net.palaya.chessanalyzer.core.analysis.MoveClassification
import net.palaya.chessanalyzer.core.analysis.PlayerReport
import net.palaya.chessanalyzer.core.chess.Color
import net.palaya.chessanalyzer.core.narration.BoardDirective
import net.palaya.chessanalyzer.core.narration.NarrationOptions
import net.palaya.chessanalyzer.core.narration.ScriptSegment
import net.palaya.chessanalyzer.core.narration.SegmentKind
import net.palaya.chessanalyzer.core.narration.VideoScript
import net.palaya.chessanalyzer.core.narration.VideoScriptGenerator
import net.palaya.chessanalyzer.core.pgn.PgnParser
import net.palaya.chessanalyzer.ui.board.BoardOrientation
import net.palaya.chessanalyzer.ui.theme.SurfaceDark
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * R6c: the title cards (intro, final numbers, lessons) drawn at 1280x720 with the shortest and the
 * longest realistic names, Latin, Hebrew and mixed, and checked by looking at pixels, not at the
 * layout's own arithmetic. The same 8 name pairs as the recap card's test.
 *
 * Every card goes through the real path: the real script generator writes the card's words, the real
 * [SegmentFrameBuilder] turns the segment into the instruction, the real renderer draws it. Then
 *  - outside the text column (10 percent in from each side), above the content box and in the caption
 *    bar's zone at the bottom, every pixel is still the background;
 *  - the card did draw something in the content box (a blank card passes the margin checks too).
 */
@RunWith(AndroidJUnit4::class)
class CardFrameFitInstrumentedTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val w = VideoExporter.VIDEO_WIDTH
    private val h = VideoExporter.VIDEO_HEIGHT
    private val background = SurfaceDark.toArgb()

    private val names = listOf(
        "A" to "B",
        "Bo" to "Li",
        "MorphyFan1857" to "DukeAndCount",
        "ABCDEFGHIJKLMNOPQRSTUVWXY" to "SomeVeryLongUsername_1234567890_extra",
        "Magnus Carlsen (Norway) - World Champion" to "Hikaru Nakamura (USA) - Grandmaster, streamer",
        "דוד בן־גוריון" to "משה דיין",
        "ש".repeat(60) to "ת".repeat(45),
        "יוסי Cohen-לוי" to "Dr. עמית 1234567890 Levi-Strauss",
    )

    private fun annotation(ply: Int) = MoveAnnotation(
        ply = ply, moveNumber = (ply + 1) / 2, color = if (ply % 2 == 1) Color.WHITE else Color.BLACK,
        san = "m$ply", uci = "a2a3", fenBefore = "", fenAfter = "", classification = MoveClassification.GOOD,
        loss = 0.0, winPercentBefore = 55.0, winPercentAfter = 55.0, evalBeforeCp = 0, evalAfterCp = 0,
    )

    private fun scriptFor(white: String, black: String, openingName: String = "Sicilian Defense: Najdorf Variation, English Attack"): VideoScript {
        fun player(color: Color, name: String, acc: Double) =
            PlayerReport(color, name, acc, 1650, false, mapOf(MoveClassification.GOOD to 17), emptyList(), emptyList())
        val report = GameReport(
            white = player(Color.WHITE, white, 84.1), black = player(Color.BLACK, black, 79.5),
            annotations = (1..34).map { annotation(it) }, openingName = openingName, openingEco = "B90",
            result = "1-0", evalGraph = List(35) { 50.0 }, keyMoments = emptyList(), analysisDepth = 12,
        )
        val tag = { s: String -> s.replace("\"", "'") }
        val pgn = PgnParser.parse(
            "[White \"${tag(white)}\"]\n[Black \"${tag(black)}\"]\n[WhiteElo \"1812\"]\n[BlackElo \"1799\"]\n[Result \"1-0\"]\n\n1. e4 c5 1-0",
        ).single()
        return VideoScriptGenerator(null).generate(report, pgn, NarrationOptions())
    }

    /**
     * The card's words drawn as a card. Since V4 the intro is drawn on the starting position; its words are the
     * same fitted card ([BoardFrameRenderer.BoardFrameSpec.titleCard]), checked here on their own as before, and
     * on the board by [theIntroIsTheStartingPositionWithItsTitleOnTheBoard].
     */
    private fun renderSegment(
        script: VideoScript,
        segment: ScriptSegment,
        withCaption: Boolean = true,
        chapter: String? = null,
        width: Int = w,
        height: Int = h,
    ): Bitmap {
        val (content, caption) = when (val instruction = SegmentFrameBuilder.build(script, segment, 0L, BoardOrientation.WHITE_DOWN)) {
            is RenderInstruction.Card -> instruction.content to instruction.caption
            is RenderInstruction.Board -> instruction.spec.titleCard!! to instruction.spec.caption
        }
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        BoardFrameRenderer.renderCardFrame(
            Canvas(bitmap), width, height, content, if (withCaption) caption else "", chapter,
        )
        return bitmap
    }

    private fun save(bitmap: Bitmap, name: String) {
        val dir = File(context.getExternalFilesDir(null), "card_frames").apply { mkdirs() }
        FileOutputStream(File(dir, "$name.png")).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** Pixels in the rectangle that are not the background colour. */
    private fun painted(bitmap: Bitmap, left: Int, top: Int, right: Int, bottom: Int): Int {
        val width = (right - left).coerceAtLeast(0)
        val height = (bottom - top).coerceAtLeast(0)
        if (width == 0 || height == 0) return 0
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, left, top, width, height)
        return pixels.count { it != background }
    }

    private fun assertInsideTheBox(bitmap: Bitmap, tag: String) {
        val w = bitmap.width
        val h = bitmap.height
        val barEnd = (w * 0.015f).toInt() + 1
        val textLeft = (w * CardGeometry.TEXT_LEFT).toInt()
        val textRight = (w * (CardGeometry.TEXT_LEFT + CardGeometry.TEXT_WIDTH)).toInt()
        val top = CardGeometry.contentTop(h.toFloat()).toInt()
        val bottom = CardGeometry.contentBottom(h.toFloat()).toInt()
        val captionTop = (h - BoardFrameRenderer.captionBarHeight(h)).toInt()

        // Left and right of the column: nothing, a few pixels of slack for a glyph's overhang.
        assertEquals("$tag: left margin", 0, painted(bitmap, barEnd, 0, textLeft - 4, h))
        assertEquals("$tag: right margin", 0, painted(bitmap, textRight + 4, 0, w, h))
        // Above the content box and below it, down to the bottom edge, which takes in the caption bar's zone.
        assertEquals("$tag: top margin", 0, painted(bitmap, barEnd, 0, w, top - 2))
        assertEquals("$tag: bottom margin", 0, painted(bitmap, barEnd, bottom + 2, w, h))
        assertEquals("$tag: caption bar zone", 0, painted(bitmap, barEnd, captionTop, w, h))
        // And the card is not blank.
        assertTrue("$tag: card is blank", painted(bitmap, textLeft, top, textRight, bottom) > 2_000)
    }

    @Test
    fun theIntroCardStaysInsideTheBoxForEveryNamePair() {
        for ((i, pair) in names.withIndex()) {
            val (white, black) = pair
            val script = scriptFor(white, black)
            val intro = script.segments.first { it.kind == SegmentKind.INTRO }
            val bitmap = renderSegment(script, intro, withCaption = true)
            save(bitmap, "intro_${i}")
            assertInsideTheBox(bitmap, "intro \"$white\" vs \"$black\"")
            bitmap.recycle()
        }
    }

    @Test
    fun theFinalNumbersCardStaysInsideTheBoxForEveryNamePair() {
        for ((i, pair) in names.withIndex()) {
            val (white, black) = pair
            val script = scriptFor(white, black)
            val outro = script.segments.first { it.kind == SegmentKind.OUTRO_SUMMARY }
            val bitmap = renderSegment(script, outro, withCaption = true)
            save(bitmap, "outro_${i}")
            assertInsideTheBox(bitmap, "outro \"$white\" vs \"$black\"")
            bitmap.recycle()
        }
    }

    @Test
    fun aLongOpeningNameStaysInsideTheBoxToo() {
        val name = "Queen's Gambit Declined: Orthodox Defense, Rubinstein Attack, Carlsbad Variation, Main Line"
        val script = scriptFor("MorphyFan1857", "DukeAndCount", openingName = name)
        val bitmap = renderSegment(script, script.segments.first { it.kind == SegmentKind.INTRO })
        save(bitmap, "intro_long_opening")
        assertInsideTheBox(bitmap, "long opening")
        bitmap.recycle()
    }

    @Test
    fun theLessonCardsStayInsideTheBoxEvenWithAVeryLongLesson() {
        val script = scriptFor("MorphyFan1857", "DukeAndCount")
        val lesson = script.segments.first { it.kind == SegmentKind.OUTRO_LESSONS }
        val longText = (1..60).joinToString(" ") { "word$it" } + " " + "x".repeat(40)
        val tall = lesson.copy(board = BoardDirective.Card("What to work on", listOf(longText)))
        for ((name, segment) in listOf("lesson" to lesson, "lesson_long" to tall)) {
            // Without its caption: a lesson card keeps "Takeaway 1 of 3" in the caption bar, which is the zone checked.
            val bitmap = renderSegment(script, segment, withCaption = false)
            save(bitmap, name)
            assertInsideTheBox(bitmap, name)
            bitmap.recycle()
        }
    }

    @Test
    fun withAChapterLabelTheCardStaysBelowTheChapterBar() {
        val script = scriptFor("ש".repeat(60), "ת".repeat(45))
        val intro = script.segments.first { it.kind == SegmentKind.INTRO }
        val bitmap = renderSegment(script, intro, chapter = "Intro")
        save(bitmap, "intro_with_chapter_bar")
        val chapterBottom = (h * CardGeometry.CHAPTER_BAR).toInt()
        val top = CardGeometry.contentTop(h.toFloat()).toInt()
        assertEquals("between the chapter bar and the card", 0, painted(bitmap, (w * 0.015f).toInt() + 1, chapterBottom + 1, w, top - 2))
        bitmap.recycle()
    }

    @Test
    fun theIntroCardWithNoCaptionStillKeepsTheCaptionZoneClear() {
        // The intro has no caption bar at all (the card is its own title), and the zone is kept anyway,
        // so the card looks the same whether a caption is drawn or not.
        val script = scriptFor("MorphyFan1857", "DukeAndCount")
        val intro = script.segments.first { it.kind == SegmentKind.INTRO }
        val withCaption = renderSegment(script, intro, withCaption = true)
        val without = renderSegment(script, intro, withCaption = false)
        val a = IntArray(w * h)
        val b = IntArray(w * h)
        withCaption.getPixels(a, 0, w, 0, 0, w, h)
        without.getPixels(b, 0, w, 0, 0, w, h)
        assertTrue("an intro card draws no caption bar, so the two frames are identical", a.contentEquals(b))
        withCaption.recycle()
        without.recycle()
    }

    // -----------------------------------------------------------------------
    // V4: the intro on the board, the final numbers' table, and the player's portrait frame
    // -----------------------------------------------------------------------

    @Test
    fun theIntroIsTheStartingPositionWithItsTitleOnTheBoard() {
        for ((i, pair) in names.withIndex()) {
            val (white, black) = pair
            val script = scriptFor(white, black)
            val intro = script.segments.first { it.kind == SegmentKind.INTRO }
            val spec = (SegmentFrameBuilder.build(script, intro, 0L, BoardOrientation.WHITE_DOWN) as RenderInstruction.Board).spec
            // The board is there at the first frame: the starting position, all 32 pieces.
            assertEquals(32, spec.boardState.pieces.size)
            assertTrue(spec.titleCard != null)
            val withTitle = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            BoardFrameRenderer.renderBoardFrame(Canvas(withTitle), w, h, spec)
            val plain = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            BoardFrameRenderer.renderBoardFrame(Canvas(plain), w, h, spec.copy(titleCard = null))
            save(withTitle, "intro_on_board_$i")
            // The title changes pixels only on the board itself (the band over its middle), never the panel, the
            // eval bar or the caption bar, and it leaves the top and bottom ranks of the board visible.
            val captionHeight = BoardFrameRenderer.captionBarHeight(h)
            val margin = h * 0.025f
            val boardSize = h - captionHeight - 2 * margin
            val boardLeft = margin + h * 0.06f + h * 0.015f
            val a = IntArray(w * h)
            val b = IntArray(w * h)
            withTitle.getPixels(a, 0, w, 0, 0, w, h)
            plain.getPixels(b, 0, w, 0, 0, w, h)
            var outside = 0
            var inside = 0
            for (y in 0 until h) for (x in 0 until w) {
                if (a[y * w + x] == b[y * w + x]) continue
                val onBoard = x >= boardLeft - 1 && x <= boardLeft + boardSize + 1 && y >= margin - 1 && y <= margin + boardSize + 1
                val inEdgeRanks = y < margin + boardSize / 8f || y > margin + boardSize * 7f / 8f
                if (!onBoard || inEdgeRanks) outside++ else inside++
            }
            assertEquals("intro \"$white\" vs \"$black\": pixels changed off the band", 0, outside)
            assertTrue("the title is drawn", inside > 5_000)
            withTitle.recycle()
            plain.recycle()
        }
    }

    @Test
    fun theFinalNumbersTableIsOnTheCardAndFitsInThePortraitPlayerToo() {
        val script = scriptFor("MorphyFan1857", "DukeAndCount")
        val outro = script.segments.first { it.kind == SegmentKind.OUTRO_SUMMARY }
        val card = SegmentFrameBuilder.build(script, outro, 0L, BoardOrientation.WHITE_DOWN) as RenderInstruction.Card
        val table = card.content.table!!
        assertEquals(10, table.rows.size)
        // The synthetic game is 34 GOOD moves: 17 each (the report says so), every other class zero.
        assertEquals(17, table.rows.single { it.classification == MoveClassification.GOOD }.white)
        assertEquals(17, table.rows.single { it.classification == MoveClassification.GOOD }.black)
        assertEquals(0, table.rows.filter { it.classification != MoveClassification.GOOD }.sumOf { it.white + it.black })
        // The class names are the app's own words.
        assertEquals(context.getString(net.palaya.chessanalyzer.R.string.classification_blunder), table.rows.last().label)
        // At the player's portrait size (3:4) every card still fits its box.
        for ((name, segment) in listOf("outro" to outro, "intro" to script.segments.first { it.kind == SegmentKind.INTRO })) {
            val bitmap = renderSegment(script, segment, withCaption = true, width = 1080, height = 1440)
            save(bitmap, "portrait_$name")
            assertInsideTheBox(bitmap, "portrait $name")
            bitmap.recycle()
        }
    }

    @Test
    fun thePortraitFrameDrawsTheBoardAsWideAsTheFrame() {
        val script = scriptFor("MorphyFan1857", "DukeAndCount")
        val intro = script.segments.first { it.kind == SegmentKind.INTRO }
        val spec = (SegmentFrameBuilder.build(script, intro, 0L, BoardOrientation.WHITE_DOWN) as RenderInstruction.Board).spec
        val pw = 1080
        val ph = 1440
        val bitmap = Bitmap.createBitmap(pw, ph, Bitmap.Config.ARGB_8888)
        BoardFrameRenderer.renderBoardFrame(Canvas(bitmap), pw, ph, spec.copy(titleCard = null))
        save(bitmap, "portrait_board")
        // The light and dark squares of the board's top rank span more than 85 percent of the frame's width.
        val row = IntArray(pw)
        var best = 0
        for (y in (ph * 0.08f).toInt() until (ph * 0.2f).toInt() step 4) {
            bitmap.getPixels(row, 0, pw, 0, y, pw, 1)
            val light = 0xFFEBECD0.toInt()
            val dark = 0xFF739552.toInt()
            val first = row.indexOfFirst { it == light || it == dark }
            val last = row.indexOfLast { it == light || it == dark }
            if (first >= 0) best = maxOf(best, last - first)
        }
        assertTrue("board width $best px of $pw", best > pw * 0.85)
        bitmap.recycle()
    }

    @Test
    fun theCapturedPiecesAndTheLeadAreDrawnBesideThePlayersInBothFrames() {
        // The Immortal Game after 20... Na6: White is missing two pawns, a bishop, both rooks; Black three pawns.
        val fen = "r1b1k1nr/p2p1ppp/n2B4/1p1NPN1P/6P1/3P1Q2/P1P1K3/q5b1 w kq - 2 21"
        val material = net.palaya.chessanalyzer.core.analysis.MaterialBalance.fromFen(fen)
        val spec = BoardFrameRenderer.BoardFrameSpec(
            boardState = net.palaya.chessanalyzer.data.mapper.fenToBoardState(fen),
            header = net.palaya.chessanalyzer.core.narration.VideoGameHeader("Adolf Anderssen", "Lionel Kieseritzky", result = "1-0"),
            caption = "20... Na6",
            evalWinPercentWhite = 99.0, evalCp = 900,
        )
        for ((tag, size) in listOf("landscape" to (w to h), "portrait" to (1080 to 1440))) {
            val (fw, fh) = size
            val with = Bitmap.createBitmap(fw, fh, Bitmap.Config.ARGB_8888)
            BoardFrameRenderer.renderBoardFrame(Canvas(with), fw, fh, spec.copy(material = material))
            val without = Bitmap.createBitmap(fw, fh, Bitmap.Config.ARGB_8888)
            BoardFrameRenderer.renderBoardFrame(Canvas(without), fw, fh, spec)
            save(with, "material_$tag")
            val a = IntArray(fw * fh)
            val b = IntArray(fw * fh)
            with.getPixels(a, 0, fw, 0, 0, fw, fh)
            without.getPixels(b, 0, fw, 0, 0, fw, fh)
            // The material is drawn (a few hundred pixels of icons and "+N"), and only beside the names: never on the board.
            val changed = a.indices.count { a[it] != b[it] }
            assertTrue("$tag: $changed pixels of material", changed > 800)
            with.recycle()
            without.recycle()
        }
        assertEquals(13, material.lead(Color.BLACK))
    }
}
