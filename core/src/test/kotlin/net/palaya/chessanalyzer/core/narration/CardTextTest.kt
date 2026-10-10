package net.palaya.chessanalyzer.core.narration

import net.palaya.chessanalyzer.core.chess.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale
import kotlin.math.roundToInt

/**
 * R6c: the words on the exported video's title card and final-numbers card.
 *
 * The generator owns the card text; the app only lays it out. Two rules are pinned here, on the
 * recorded real games: every fact is printed once on the intro card (the title carries the names, the
 * lines carry the rest), and every accuracy printed on a card is whole percent, as the Summary
 * screen writes it.
 */
class CardTextTest {

    private val games = listOf(
        "scholar's mate" to RealGameFixture.scholars,
        "Opera Game" to RealGameFixture.chesscom,
        "Immortal Game" to RealGameFixture.immortal,
        "Byrne-Fischer" to RealGameFixture.byrneFischer,
    )

    private fun script(g: RealGameFixture.Game, user: Color? = null): VideoScript =
        VideoScriptGenerator(user).generate(g.report(user), g.pgn, NarrationOptions())

    private fun card(script: VideoScript, kind: SegmentKind): BoardDirective.Card =
        script.segments.first { it.kind == kind }.board as BoardDirective.Card

    private fun occurrences(text: String, needle: String): Int =
        if (needle.isEmpty()) 0 else text.windowed(needle.length).count { it == needle }

    @Test
    fun `the intro card is a subtitle line and an accuracy line, each fact once`() {
        for ((name, g) in games) {
            val s = script(g)
            val h = s.header!!
            val intro = card(s, SegmentKind.INTRO)
            assertEquals("$name: [subtitle, accuracy]", 2, intro.lines.size)
            val subtitle = intro.lines[0]
            val accuracy = intro.lines[1]

            // The result and the move count come first, then the opening, all on the one line.
            assertTrue("$name: result first in '$subtitle'", subtitle.startsWith(h.result))
            assertTrue("$name: 'moves' in '$subtitle'", subtitle.contains(" move"))
            h.openingName?.let { assertTrue("$name: opening in '$subtitle'", subtitle.contains(it)) }
            h.openingEco?.let { assertTrue("$name: ECO in '$subtitle'", subtitle.contains("($it)")) }

            // Each fact appears exactly once in everything the card prints.
            val all = (listOf(intro.heading) + intro.lines).joinToString("\n")
            assertEquals("$name: result in\n$all", 1, occurrences(all, h.result))
            h.openingName?.let { assertEquals("$name: opening in\n$all", 1, occurrences(all, it)) }
            h.openingEco?.let { assertEquals("$name: ECO in\n$all", 1, occurrences(all, it)) }
            // (A game with no name tags falls back to the colour words, which the accuracy line also uses.)
            if (h.whiteName != "White") assertEquals("$name: White's name in\n$all", 1, occurrences(all, h.whiteName))
            if (h.blackName != "Black") assertEquals("$name: Black's name in\n$all", 1, occurrences(all, h.blackName))
            assertEquals("$name: accuracy line has both sides once", 1, occurrences(accuracy, "White"))
            assertEquals("$name: accuracy line has both sides once", 1, occurrences(accuracy, "Black"))
            // No sub-line labels any more ("White: ..", "Result: ..").
            assertFalse("$name: no 'Result:' label", all.contains("Result:"))
        }
    }

    @Test
    fun `accuracy on the cards is whole percent, the same number the summary prints`() {
        val decimal = Regex("\\d+\\.\\d+\\s*%")
        for ((name, g) in games) for (user in listOf<Color?>(null, Color.WHITE, Color.BLACK)) {
            val s = script(g, user)
            val w = s.whiteAccuracy!!
            val b = s.blackAccuracy!!
            val whole = { v: Double -> "%.0f".format(Locale.ROOT, v) }
            val intro = card(s, SegmentKind.INTRO)
            assertEquals("$name: intro accuracy line", "White ${whole(w)}% · Black ${whole(b)}%", intro.lines[1])

            val outro = card(s, SegmentKind.OUTRO_SUMMARY)
            val lines = outro.lines.joinToString("\n")
            assertTrue("$name: White's final line in\n$lines", outro.lines[0].contains("${whole(w)}%"))
            assertTrue("$name: Black's final line in\n$lines", outro.lines[1].contains("${whole(b)}%"))
            val outroSegment = s.segments.first { it.kind == SegmentKind.OUTRO_SUMMARY }
            assertTrue("$name: outro caption '${outroSegment.caption}'", outroSegment.caption.contains("${whole(w)}%") && outroSegment.caption.contains("${whole(b)}%"))

            // Nothing a card or a caption prints has a decimal accuracy.
            for (text in intro.lines + outro.lines + outroSegment.caption) {
                assertFalse("$name: decimal accuracy in '$text'", decimal.containsMatchIn(text))
            }
        }
    }

    @Test
    fun `whole percent here is the summary's rounding for every value`() {
        // The generator rounds with roundToInt, the Summary screen with "%.0f": the same on 0..100.
        var v = 0.0
        while (v <= 100.0) {
            assertEquals("at $v", "%.0f".format(Locale.ROOT, v), v.roundToInt().toString())
            v += 0.01
        }
        for (edge in listOf(0.5, 1.5, 2.5, 84.5, 84.49999999999999, 84.50000000000001, 99.5, 100.0)) {
            assertEquals("at $edge", "%.0f".format(Locale.ROOT, edge), edge.roundToInt().toString())
        }
    }

    @Test
    fun `names on the final-numbers card are bidi-isolated so the numbers around them keep their order`() {
        val fsi = 0x2068.toChar()
        val pdi = 0x2069.toChar()
        for ((name, g) in games) {
            val s = script(g)
            val h = s.header!!
            val outro = card(s, SegmentKind.OUTRO_SUMMARY)
            assertTrue("$name: ${outro.lines[0]}", outro.lines[0].startsWith("$fsi${h.whiteName}$pdi"))
            assertTrue("$name: ${outro.lines[1]}", outro.lines[1].startsWith("$fsi${h.blackName}$pdi"))
            // The spoken sentences never carry an isolate.
            assertTrue(s.segments.none { it.narration.contains(fsi) || it.narration.contains(pdi) })
        }
        assertNotNull(games)
    }

    // -----------------------------------------------------------------------
    // V4: the intro on the board, and the final numbers' move-quality table
    // -----------------------------------------------------------------------

    @Test
    fun `the intro card is drawn on the game's starting position, with the engine's number for it`() {
        for ((name, g) in games) {
            val s = script(g)
            val introSegment = s.segments.first()
            assertEquals(name, SegmentKind.INTRO, introSegment.kind)
            val intro = introSegment.board as BoardDirective.Card
            val report = g.report()
            assertEquals(name, report.annotations.first().fenBefore, intro.boardFen)
            assertNotNull("$name: an eval bar from the first frame", introSegment.eval)
            // Still a title card: the same words as before V4.
            assertEquals(name, 2, intro.lines.size)
        }
    }

    @Test
    fun `the final-numbers table counts every class but Forced for each side, in the app's order`() {
        for ((name, g) in games) for (user in listOf<Color?>(null, Color.WHITE, Color.BLACK)) {
            val report = g.report(user)
            val s = VideoScriptGenerator(user).generate(report, g.pgn, NarrationOptions())
            assertEquals(name, QUALITY_TABLE_CLASSES, s.qualityCounts.map { it.classification })
            assertEquals(name, 10, s.qualityCounts.size)
            for (row in s.qualityCounts) {
                // Each count is the number of that side's moves with that class: nothing else feeds it.
                val white = report.annotations.count { it.color == Color.WHITE && it.classification == row.classification }
                val black = report.annotations.count { it.color == Color.BLACK && it.classification == row.classification }
                assertEquals("$name ${row.classification} White", white, row.white)
                assertEquals("$name ${row.classification} Black", black, row.black)
            }
            // The counts the table does not show are exactly the forced moves.
            val shown = s.qualityCounts.sumOf { it.white + it.black }
            val forced = report.annotations.count { it.classification == net.palaya.chessanalyzer.core.analysis.MoveClassification.FORCED }
            assertEquals(name, report.annotations.size, shown + forced)
            // The card's old counts line is gone: the table is the one place the counts are printed.
            val outro = card(s, SegmentKind.OUTRO_SUMMARY)
            assertTrue(name, outro.lines.none { it.contains("Blunders") || it.contains("Mistakes") })
        }
    }
}
