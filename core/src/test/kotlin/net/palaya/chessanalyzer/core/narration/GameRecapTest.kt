package net.palaya.chessanalyzer.core.narration

import net.palaya.chessanalyzer.core.analysis.GameReport
import net.palaya.chessanalyzer.core.analysis.MoveAnnotation
import net.palaya.chessanalyzer.core.analysis.MoveClassification
import net.palaya.chessanalyzer.core.analysis.PlayerReport
import net.palaya.chessanalyzer.core.chess.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ANALYSIS_SPEC 9.7, "Recap card": the facts on the exported video's end card, and the R6b
 * wording rule that the video says "moves", never "plies".
 *
 * The recap may claim only what the report holds, so every field is checked against the report it
 * came from: the recorded real games prove it on genuine analyses, a synthetic report pins the
 * boundaries.
 */
class GameRecapTest {

    private val games: List<Pair<String, RealGameFixture.Game>> by lazy {
        listOf(
            "scholar's mate" to RealGameFixture.scholars,
            "Opera Game" to RealGameFixture.chesscom,
            "Immortal Game" to RealGameFixture.immortal,
            "Byrne-Fischer" to RealGameFixture.byrneFischer,
        )
    }

    private fun script(g: RealGameFixture.Game, user: Color?): Pair<GameReport, VideoScript> {
        val report = g.report(user)
        return report to VideoScriptGenerator(user).generate(report, g.pgn, NarrationOptions())
    }

    // -----------------------------------------------------------------------
    // Content, against the report
    // -----------------------------------------------------------------------

    @Test
    fun `every field of the recap is the report's own number or sentence`() {
        for ((name, g) in games) for (user in listOf<Color?>(null, Color.WHITE, Color.BLACK)) {
            val (report, script) = script(g, user)
            val recap = script.recap
            assertNotNull("$name $user: no recap", recap)
            recap!!
            val header = script.header!!

            assertEquals(header.whiteName, recap.white.name)
            assertEquals(header.blackName, recap.black.name)
            assertEquals(Color.WHITE, recap.white.color)
            assertEquals(Color.BLACK, recap.black.color)
            assertEquals(report.white.accuracy, recap.white.accuracy, 0.0)
            assertEquals(report.black.accuracy, recap.black.accuracy, 0.0)
            assertEquals("$name: the user's side is marked", user == Color.WHITE, recap.white.isUser)
            assertEquals("$name: the user's side is marked", user == Color.BLACK, recap.black.isUser)

            // The sentence is the Summary screen's, word for word.
            assertEquals("$name $user", GameSummarySentence.text(report, user, false), recap.summary)

            // Each count is the number of that side's moves with that classification, non-zero only.
            for ((side, color) in listOf(recap.white to Color.WHITE, recap.black to Color.BLACK)) {
                val expected = RECAP_COUNT_CLASSES.mapNotNull { cls ->
                    report.annotations.count { it.color == color && it.classification == cls }
                        .takeIf { it > 0 }?.let { RecapCount(cls, it) }
                }
                assertEquals("$name $color counts", expected, side.counts)
                assertTrue(side.counts.all { it.count > 0 })
                assertEquals(side.counts.map { it.classification }, side.counts.map { it.classification }.sortedBy { RECAP_COUNT_CLASSES.indexOf(it) })
            }
        }
    }

    @Test
    fun `the biggest moment is the move the summary sentence names, and only a big one`() {
        var sawMoment = false
        for ((name, g) in games) for (user in listOf<Color?>(null, Color.WHITE, Color.BLACK)) {
            val (report, script) = script(g, user)
            val recap = script.recap!!
            val turning = GameSummarySentence.turningPoint(report.annotations)
            val moment = recap.biggestMoment
            if (turning == null || turning.loss < GameSummarySentence.BIG_SWING) {
                assertNull("$name: a small swing is not a biggest moment", moment)
                continue
            }
            sawMoment = true
            moment!!
            // It is a real annotation of the game, with the spec's own verdict.
            val annotation = report.annotations.single { it.moveNumber == moment.moveNumber && it.color == moment.color }
            assertEquals(turning.ply, annotation.ply)
            assertEquals(annotation.san, moment.san)
            assertEquals(annotation.classification, moment.classification)
            assertTrue("$name: it is the largest loss of the game", report.annotations.none { it.loss > annotation.loss })
            // And it is the move the summary sentence says, whenever that sentence names one.
            GameSummarySentence.build(report, user)?.moveNumber?.let {
                assertEquals("$name $user", it, moment.moveNumber)
            }
        }
        assertTrue("the fixtures should include a game with a biggest moment", sawMoment)
    }

    @Test
    fun `a game with no big swing names no biggest moment, a bigger one does`() {
        fun move(ply: Int, loss: Double, cls: MoveClassification) = MoveAnnotation(
            ply = ply, moveNumber = (ply + 1) / 2, color = if (ply % 2 == 1) Color.WHITE else Color.BLACK,
            san = "Nf3", uci = "g1f3", fenBefore = "", fenAfter = "", classification = cls, loss = loss,
            winPercentBefore = 50.0, winPercentAfter = 50.0 - loss, evalBeforeCp = 0, evalAfterCp = 0
        )
        fun report(biggest: Double, cls: MoveClassification): GameReport {
            val moves = (1..40).map { if (it == 22) move(it, biggest, cls) else move(it, 0.0, MoveClassification.GOOD) }
            fun player(c: Color) = PlayerReport(
                c, null, 80.0, 1500, false,
                mapOf(MoveClassification.GOOD to 20) + if (c == Color.BLACK) mapOf(cls to 1) else emptyMap(),
                emptyList(), emptyList()
            )
            return GameReport(
                white = player(Color.WHITE), black = player(Color.BLACK), annotations = moves, openingName = null,
                openingEco = null, result = "1-0", evalGraph = List(41) { 50.0 }, keyMoments = emptyList(), analysisDepth = 12
            )
        }

        // One win-percent under the line: no claim. Exactly on it: the claim, with that move's verdict.
        assertNull(GameRecap.build(report(19.9, MoveClassification.MISTAKE), "A", "B", null)!!.biggestMoment)
        val on = GameRecap.build(report(GameSummarySentence.BIG_SWING, MoveClassification.BLUNDER), "A", "B", Color.BLACK)!!
        assertEquals(RecapMoment(11, Color.BLACK, "Nf3", MoveClassification.BLUNDER), on.biggestMoment)
        assertTrue(on.black.isUser)
        assertFalse(on.white.isUser)
        // Black's single blunder is the only chip the recap draws, and only on Black's side.
        assertEquals(emptyList<RecapCount>(), on.white.counts)
        assertEquals(listOf(RecapCount(MoveClassification.BLUNDER, 1)), on.black.counts)
    }

    @Test
    fun `a report with no moves has no recap`() {
        val empty = GameReport(
            white = PlayerReport(Color.WHITE, null, 0.0, 0, true, emptyMap(), emptyList(), emptyList()),
            black = PlayerReport(Color.BLACK, null, 0.0, 0, true, emptyMap(), emptyList(), emptyList()),
            annotations = emptyList(), openingName = null, openingEco = null, result = "*",
            evalGraph = emptyList(), keyMoments = emptyList(), analysisDepth = 12
        )
        assertNull(GameRecap.build(empty, "A", "B", null))
    }

    // -----------------------------------------------------------------------
    // The recap is outside the script's counting and its pacing budget
    // -----------------------------------------------------------------------

    @Test
    fun `the recap is not a segment and is not in the estimated length`() {
        for ((name, g) in games) {
            val (_, script) = script(g, null)
            assertNotNull(script.recap)
            assertEquals(
                "$name: the budgeted length is the segments' alone",
                script.segments.sumOf { it.estimatedSpeechMs + it.leadInMs + it.holdAfterMs }, script.totalEstimatedMs
            )
            assertEquals("$name: segment indexes run 0..n-1 with no recap among them", script.segments.indices.toList(), script.segments.map { it.index })
        }
    }

    @Test
    fun `without addressing the user the recap uses colours even when the side is known`() {
        val g = RealGameFixture.immortal
        val report = g.report(Color.WHITE)
        val script = VideoScriptGenerator(Color.WHITE).generate(report, g.pgn, NarrationOptions(addressUserAsYou = false))
        val recap = script.recap!!
        assertFalse(recap.white.isUser)
        assertEquals(GameSummarySentence.text(report, null, false), recap.summary)
    }

    // -----------------------------------------------------------------------
    // "moves", not "plies" (R6b)
    // -----------------------------------------------------------------------

    @Test
    fun `the title card counts full moves and no sentence anywhere says plies`() {
        for ((name, g) in games) {
            val (report, script) = script(g, null)
            val fullMoves = (report.annotations.size + 1) / 2
            val intro = script.segments.first { it.kind == SegmentKind.INTRO }
            val lines = (intro.board as BoardDirective.Card).lines
            assertTrue("$name: $lines", lines.first().startsWith("${report.result} · $fullMoves moves"))

            val everything = script.segments.flatMap { s ->
                listOf(s.narration, s.caption) + ((s.board as? BoardDirective.Card)?.let { listOf(it.heading) + it.lines } ?: emptyList())
            } + script.title + script.subtitle + (script.recap?.summary ?: "")
            for (text in everything) {
                assertFalse("$name: \"$text\"", Regex("\\bplies\\b|\\bply\\b", RegexOption.IGNORE_CASE).containsMatchIn(text))
            }
        }
    }

    @Test
    fun `the move count reads as a player counts, singular for one and rounded up for an odd game`() {
        fun line(fullMoves: Int) = EnglishNarration.render(Sentence.CardResultLine("1-0", fullMoves), NarrationStyle.COACH).single()
        assertEquals("1-0 · 1 move", line(1))
        assertEquals("1-0 · 2 moves", line(2))
        assertEquals("1-0 · 17 moves", line(17))
        // 33 plies is the figure the poster used to print; a player calls it 17 moves, and so does the Summary.
        val g = RealGameFixture.chesscom.firstPlies(33)
        val report = g.report()
        val script = VideoScriptGenerator().generate(report, g.pgn, NarrationOptions())
        val card = script.segments.first { it.kind == SegmentKind.INTRO }.board as BoardDirective.Card
        assertTrue(card.lines.toString(), card.lines.any { it.contains("· 17 moves") })
        // 1 ply is one move (White's), not "0.5".
        val one = RealGameFixture.chesscom.firstPlies(1)
        val oneScript = VideoScriptGenerator().generate(one.report(), one.pgn, NarrationOptions())
        val oneCard = oneScript.segments.first { it.kind == SegmentKind.INTRO }.board as BoardDirective.Card
        assertTrue(oneCard.lines.toString(), oneCard.lines.any { it.contains("· 1 move") })
    }
}
