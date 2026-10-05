package net.palaya.chessanalyzer.core.narration

import net.palaya.chessanalyzer.core.analysis.GameReport
import net.palaya.chessanalyzer.core.analysis.MoveAnnotation
import net.palaya.chessanalyzer.core.analysis.MoveClassification
import net.palaya.chessanalyzer.core.analysis.PlayerReport
import net.palaya.chessanalyzer.core.chess.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ANALYSIS_SPEC §12: the one-sentence game summary. Synthetic reports pin every rule at its
 * boundary; the two recorded real games prove the sentence on genuine analyses, for every side
 * choice.
 */
class GameSummarySentenceTest {

    // -----------------------------------------------------------------------
    // Builders
    // -----------------------------------------------------------------------

    private fun move(
        ply: Int,
        loss: Double = 0.0,
        classification: MoveClassification = MoveClassification.GOOD,
        winBefore: Double = 50.0,
        san: String = "Nf3"
    ): MoveAnnotation = MoveAnnotation(
        ply = ply,
        moveNumber = (ply + 1) / 2,
        color = if (ply % 2 == 1) Color.WHITE else Color.BLACK,
        san = san,
        uci = "g1f3",
        fenBefore = "",
        fenAfter = "",
        classification = classification,
        loss = loss,
        winPercentBefore = winBefore,
        winPercentAfter = (winBefore - loss).coerceAtLeast(0.0),
        evalBeforeCp = 0,
        evalAfterCp = 0
    )

    private fun player(color: Color) = PlayerReport(color, null, 80.0, 1500, false, emptyMap(), emptyList(), emptyList())

    /** [plies] quiet moves, with [overrides] replacing the move at the given ply. */
    private fun report(
        result: String,
        plies: Int = 40,
        overrides: Map<Int, MoveAnnotation> = emptyMap(),
        graph: List<Double>? = null
    ): GameReport {
        val moves = (1..plies).map { overrides[it] ?: move(it) }
        return GameReport(
            white = player(Color.WHITE), black = player(Color.BLACK), annotations = moves,
            openingName = null, openingEco = null, result = result,
            evalGraph = graph ?: List(plies + 1) { 50.0 }, keyMoments = emptyList(), analysisDepth = 12
        )
    }

    private fun text(report: GameReport, user: Color? = null, notMe: Boolean = false) =
        GameSummarySentence.text(report, user, notMe)

    private val blunder = MoveClassification.BLUNDER

    // -----------------------------------------------------------------------
    // The cases
    // -----------------------------------------------------------------------

    @Test
    fun `a decisive turning point, framed three ways`() {
        // Black, on move 11 (ply 22), blunders from a fine position (55 percent) and loses.
        val r = report("1-0", overrides = mapOf(22 to move(22, loss = 30.0, classification = blunder, winBefore = 55.0)))
        assertEquals("Black was fine until move 11, then a blunder decided it.", text(r))
        assertEquals("You were fine until move 11, then a blunder decided it.", text(r, Color.BLACK))
        assertEquals("Your opponent was fine until move 11, then a blunder decided it.", text(r, Color.WHITE))
        // "Not me" wins over a remembered colour.
        assertEquals("Black was fine until move 11, then a blunder decided it.", text(r, Color.BLACK, notMe = true))
    }

    @Test
    fun `the error word follows the class of the turning move`() {
        fun with(c: MoveClassification) =
            text(report("1-0", overrides = mapOf(22 to move(22, 25.0, c, 55.0))))
        assertEquals("Black was fine until move 11, then a mistake decided it.", with(MoveClassification.MISTAKE))
        assertEquals("Black was fine until move 11, then a big swing decided it.", with(MoveClassification.GOOD))
    }

    @Test
    fun `a loser who was already worse is sealed, not fine`() {
        val r = report("1-0", overrides = mapOf(22 to move(22, loss = 25.0, classification = blunder, winBefore = 39.9)))
        assertEquals("Black was already under pressure, and a blunder on move 11 sealed it.", text(r))
        assertEquals("You were already under pressure, and a blunder on move 11 sealed it.", text(r, Color.BLACK))
    }

    @Test
    fun `fine and comeback boundaries are exact`() {
        fun kindAt(before: Double) = GameSummarySentence.build(
            report("1-0", overrides = mapOf(22 to move(22, 30.0, blunder, before))), null
        )!!.kind
        assertEquals(SummaryKind.SEALED_BY_ERROR, kindAt(GameSummarySentence.FINE_FLOOR - 0.1))
        assertEquals(SummaryKind.DECIDED_BY_ERROR, kindAt(GameSummarySentence.FINE_FLOOR))
        assertEquals(SummaryKind.DECIDED_BY_ERROR, kindAt(GameSummarySentence.COMEBACK_LEAD - 0.1))
        assertEquals(SummaryKind.COMEBACK, kindAt(GameSummarySentence.COMEBACK_LEAD))
    }

    @Test
    fun `a comeback is about the side that was behind`() {
        val r = report("1-0", overrides = mapOf(22 to move(22, loss = 35.0, classification = blunder, winBefore = 72.0)))
        assertEquals("White was behind when Black's blunder on move 11 turned the game around.", text(r))
        assertEquals("You were behind when your opponent's blunder on move 11 turned the game around.", text(r, Color.WHITE))
        assertEquals("Your opponent was behind when your blunder on move 11 turned the game around.", text(r, Color.BLACK))
    }

    @Test
    fun `a winner who made the biggest error still won`() {
        // White blunders on move 15 (ply 29) and White still wins.
        val r = report("1-0", overrides = mapOf(29 to move(29, loss = 28.0, classification = blunder, winBefore = 60.0)))
        assertEquals("White won, even after a blunder on move 15.", text(r))
        assertEquals("You won, even after a blunder on move 15.", text(r, Color.WHITE))
        assertEquals("Your opponent won, even after a blunder on move 15.", text(r, Color.BLACK))
    }

    @Test
    fun `the swing threshold is exact`() {
        fun kind(loss: Double) = GameSummarySentence.build(
            report("1-0", graph = List(41) { if (it > 30) 80.0 else 50.0 },
                overrides = mapOf(22 to move(22, loss, blunder, 55.0))), null
        )!!.kind
        assertEquals(SummaryKind.DECIDED_BY_ERROR, kind(GameSummarySentence.BIG_SWING))
        assertEquals(SummaryKind.CLEAN_WIN, kind(GameSummarySentence.BIG_SWING - 0.1))
    }

    @Test
    fun `a game that ends in mate with no big swing says so`() {
        val r = report("1-0", plies = 59, overrides = mapOf(59 to move(59, san = "Qxf7#")),
            graph = List(60) { if (it >= 59) 100.0 else if (it > 40) 80.0 else 50.0 })
        assertEquals("White won by checkmate on move 30, and neither side made a big mistake.", text(r))
        assertEquals("You won by checkmate on move 30, and neither side made a big mistake.", text(r, Color.WHITE))
        assertEquals("Your opponent won by checkmate on move 30, and neither side made a big mistake.", text(r, Color.BLACK))
    }

    @Test
    fun `a mate in a game that stayed close still names the mate`() {
        val r = report("1-0", plies = 59, overrides = mapOf(59 to move(59, san = "Qxf7#")),
            graph = List(60) { if (it >= 59) 100.0 else 55.0 })
        assertTrue(text(r)!!.contains("checkmate on move 30"))
    }

    @Test
    fun `a decisive result without a mate and without a big swing`() {
        val r = report("0-1", graph = List(41) { if (it > 30) 15.0 else 50.0 })
        assertEquals("Black won, and neither side made a big mistake.", text(r))
        assertEquals("You won, and neither side made a big mistake.", text(r, Color.BLACK))
    }

    @Test
    fun `a clean close game`() {
        val decisive = report("1-0", graph = List(41) { 45.0 })
        assertEquals("A close game: neither side made a big mistake.", text(decisive))
        val draw = report("1/2-1/2", graph = List(41) { 20.0 })
        assertEquals("A close game: neither side made a big mistake.", text(draw))
        assertEquals("A close game: neither side made a big mistake.", text(draw, Color.WHITE))
    }

    @Test
    fun `the close band is exact`() {
        fun text(edge: Double) = GameSummarySentence.text(
            report("1-0", graph = List(41) { if (it == 20) edge else 50.0 }), null
        )!!
        assertTrue(text(GameSummarySentence.CLOSE_LOW).startsWith("A close game"))
        assertTrue(text(GameSummarySentence.CLOSE_HIGH).startsWith("A close game"))
        assertEquals("White won, and neither side made a big mistake.", text(GameSummarySentence.CLOSE_HIGH + 0.1))
        assertEquals("White won, and neither side made a big mistake.", text(GameSummarySentence.CLOSE_LOW - 0.1))
    }

    @Test
    fun `a draw with one big swing names it`() {
        val r = report("1/2-1/2", overrides = mapOf(27 to move(27, loss = 24.0, classification = MoveClassification.MISTAKE, winBefore = 50.0)))
        assertEquals("It ended in a draw, but White's mistake on move 14 was the big swing.", text(r))
        assertEquals("It ended in a draw, but your mistake on move 14 was the big swing.", text(r, Color.WHITE))
        assertEquals("It ended in a draw, but your opponent's mistake on move 14 was the big swing.", text(r, Color.BLACK))
    }

    @Test
    fun `a short game is a short game, whatever else happened in it`() {
        val mate = report("0-1", plies = 16, overrides = mapOf(16 to move(16, san = "Qxf2#"),
            9 to move(9, loss = 60.0, classification = blunder, winBefore = 50.0)))
        assertEquals("A short game: Black mated White in 8 moves.", text(mate))
        assertEquals("A short game: you mated your opponent in 8 moves.", text(mate, Color.BLACK))
        assertEquals("A short game: your opponent mated you in 8 moves.", text(mate, Color.WHITE))
        assertEquals("A short game: Black won in 8 moves.", text(report("0-1", plies = 16)))
        assertEquals("A short game: it ended in a draw after 9 moves.", text(report("1/2-1/2", plies = 17)))
        // One ply short of the limit is short, the limit itself is not.
        val limit = GameSummarySentence.SHORT_GAME_PLIES
        assertTrue(text(report("1-0", plies = limit - 1))!!.startsWith("A short game"))
        assertTrue(!text(report("1-0", plies = limit))!!.startsWith("A short game"))
    }

    @Test
    fun `one move is spoken as one move`() {
        assertEquals("A short game: White won in one move.", text(report("1-0", plies = 1)))
    }

    @Test
    fun `a game with no result says so and claims nothing else`() {
        val r = report("*", plies = 45, overrides = mapOf(22 to move(22, loss = 40.0, classification = blunder)))
        assertEquals("The game stops after 23 moves without a result.", text(r))
        assertEquals("The game stops after 23 moves without a result.", text(r, Color.WHITE))
    }

    @Test
    fun `no moves means no sentence`() {
        assertNull(GameSummarySentence.build(report("1-0", plies = 0), null))
        assertNull(text(report("1-0", plies = 0)))
    }

    // -----------------------------------------------------------------------
    // The turning point
    // -----------------------------------------------------------------------

    @Test
    fun `a tie for the biggest swing goes to the later move`() {
        val r = report("1-0", overrides = mapOf(
            10 to move(10, loss = 30.0, classification = blunder, winBefore = 55.0),
            22 to move(22, loss = 30.0, classification = blunder, winBefore = 55.0)
        ))
        assertEquals("Black was fine until move 11, then a blunder decided it.", text(r))
    }

    @Test
    fun `book and forced moves are never the turning point`() {
        val r = report("1-0", overrides = mapOf(
            6 to move(6, loss = 45.0, classification = MoveClassification.BOOK),
            8 to move(8, loss = 45.0, classification = MoveClassification.FORCED),
            22 to move(22, loss = 30.0, classification = blunder, winBefore = 55.0)
        ))
        assertEquals("Black was fine until move 11, then a blunder decided it.", text(r))
    }

    @Test
    fun `the biggest swing is the one named, not the first or the last`() {
        val r = report("0-1", overrides = mapOf(
            9 to move(9, loss = 22.0, classification = blunder, winBefore = 50.0),
            21 to move(21, loss = 41.0, classification = blunder, winBefore = 60.0),
            31 to move(31, loss = 25.0, classification = blunder, winBefore = 20.0)
        ))
        assertEquals("White was fine until move 11, then a blunder decided it.", text(r))
    }

    // -----------------------------------------------------------------------
    // Never contradicts the result, never invents a fact
    // -----------------------------------------------------------------------

    @Test
    fun `a mate that disagrees with the result tag is not claimed`() {
        // The tag says 1-0 but the last move, by Black, is "mate": the tag wins, no mate is claimed.
        val r = report("1-0", plies = 40, overrides = mapOf(40 to move(40, san = "Qh4#")), graph = List(41) { if (it > 30) 80.0 else 50.0 })
        val summary = GameSummarySentence.build(r, null)!!
        assertEquals(SummaryKind.CLEAN_WIN, summary.kind)
        assertTrue(!summary.byMate)
        assertTrue(!text(r)!!.contains("checkmate"))
    }

    @Test
    fun `the winner named is always the winner in the result tag`() {
        for (result in listOf("1-0", "0-1")) {
            val winner = if (result == "1-0") Color.WHITE else Color.BLACK
            for (ply in listOf(10, 11, 22, 23, 30)) {
                for (cls in listOf(blunder, MoveClassification.MISTAKE, MoveClassification.MISS)) {
                    for (before in listOf(10.0, 45.0, 80.0)) {
                        val r = report(result, overrides = mapOf(ply to move(ply, 30.0, cls, before)))
                        val s = GameSummarySentence.build(r, null)!!
                        when (s.kind) {
                            SummaryKind.DECIDED_BY_ERROR, SummaryKind.SEALED_BY_ERROR ->
                                assertEquals("the one who erred lost: $s", winner.opposite(), s.subject!!.color)
                            SummaryKind.COMEBACK, SummaryKind.WON_DESPITE_ERROR ->
                                assertEquals("the one named won: $s", winner, s.subject!!.color)
                            else -> Unit
                        }
                        assertTrue("no side may be named in a draw sentence for $result: ${text(r)}", s.kind != SummaryKind.DRAW_WITH_SWING)
                    }
                }
            }
        }
    }

    @Test
    fun `a draw never names a winner or a mate`() {
        val r = report("1/2-1/2", overrides = mapOf(22 to move(22, loss = 50.0, classification = blunder)))
        val t = text(r)!!
        assertTrue(t, t.startsWith("It ended in a draw"))
        for (word in listOf("won", "mate", "decided", "sealed", "turned")) assertTrue("'$word' in: $t", !t.contains(word))
    }

    @Test
    fun `only numbers the report holds appear in the sentence`() {
        val cases = listOf(
            report("1-0", overrides = mapOf(22 to move(22, 30.0, blunder, 55.0))),
            report("1-0", overrides = mapOf(22 to move(22, 30.0, blunder, 80.0))),
            report("1/2-1/2", overrides = mapOf(27 to move(27, 30.0, blunder, 50.0))),
            report("0-1", plies = 16),
            report("*", plies = 33),
            report("1-0", plies = 59, overrides = mapOf(59 to move(59, san = "Qxf7#")))
        )
        for (r in cases) {
            val allowed = r.annotations.map { it.moveNumber }.toSet() + ((r.annotations.size + 1) / 2)
            val numbers = Regex("\\d+").findAll(text(r)!!).map { it.value.toInt() }.toList()
            assertTrue("$numbers not in the report: ${text(r)}", numbers.all { it in allowed })
            assertTrue("a percentage in: ${text(r)}", !text(r)!!.contains('%'))
        }
    }

    // -----------------------------------------------------------------------
    // Side framing and grammar
    // -----------------------------------------------------------------------

    @Test
    fun `the viewer is second person and carries their gender, the opponent never does`() {
        val r = report("1-0", overrides = mapOf(22 to move(22, 30.0, blunder, 55.0)))
        val s = GameSummarySentence.build(r, Color.BLACK, viewerGender = Gender.FEMININE)!!
        assertEquals(Subject(Color.BLACK, Person.SECOND, Gender.FEMININE), s.subject)
        assertEquals(Subject(Color.WHITE, Person.THIRD, Gender.UNSPECIFIED), s.opponent)
        assertTrue(s.viewerKnown)
        val none = GameSummarySentence.build(r, null, viewerGender = Gender.FEMININE)!!
        assertEquals(Subject(Color.BLACK, Person.THIRD, Gender.UNSPECIFIED), none.subject)
        assertTrue(!none.viewerKnown)
        val notMe = GameSummarySentence.build(r, Color.BLACK, notMe = true, viewerGender = Gender.FEMININE)!!
        assertEquals(none, notMe)
    }

    @Test
    fun `english never says you was or your opponent were`() {
        val reports = listOf(
            report("1-0", overrides = mapOf(22 to move(22, 30.0, blunder, 55.0))),
            report("1-0", overrides = mapOf(22 to move(22, 30.0, blunder, 20.0))),
            report("1-0", overrides = mapOf(22 to move(22, 30.0, blunder, 80.0))),
            report("1-0", overrides = mapOf(29 to move(29, 30.0, blunder, 60.0))),
            report("1/2-1/2", overrides = mapOf(27 to move(27, 30.0, blunder, 50.0)))
        )
        for (r in reports) for (user in listOf<Color?>(null, Color.WHITE, Color.BLACK)) {
            val t = text(r, user)!!
            assertTrue(t, !Regex("\\byou was\\b|\\byour opponent were\\b|\\bYou was\\b|\\bYour opponent were\\b").containsMatchIn(t))
            assertTrue(t, t.endsWith(".") && !t.contains("..") && !t.contains("  "))
            assertTrue(t, t[0].isUpperCase())
        }
    }

    @Test
    fun `it is deterministic`() {
        val r = report("1-0", overrides = mapOf(22 to move(22, 30.0, blunder, 55.0)))
        assertEquals(text(r, Color.WHITE), text(r, Color.WHITE))
    }

    @Test
    fun `every registered language renders every kind`() {
        for (strings in NarrationLocales.all) {
            for (sentence in NarrationCatalogue.samples().filterIsInstance<Sentence.GameSummary>()) {
                val variants = strings.render(sentence, NarrationStyle.COACH)
                assertTrue(variants.isNotEmpty())
                assertTrue("${sentence.kind} rendered blank", variants.all { it.isNotBlank() })
            }
        }
        val kinds = NarrationCatalogue.samples().filterIsInstance<Sentence.GameSummary>().map { it.kind }.toSet()
        assertEquals("the catalogue must sample every kind", SummaryKind.entries.toSet(), kinds)
    }

    // -----------------------------------------------------------------------
    // Recorded real games
    // -----------------------------------------------------------------------

    @Test
    fun `real games produce one clean sentence for every side choice`() {
        for ((name, game) in listOf("immortal" to RealGameFixture.immortal, "chesscom" to RealGameFixture.chesscom)) {
            val report = game.report()
            val fullMoves = (report.annotations.size + 1) / 2
            val allowed = report.annotations.map { it.moveNumber }.toSet() + fullMoves
            for ((label, user, notMe) in listOf(
                Triple("no side", null, false), Triple("white", Color.WHITE, false),
                Triple("black", Color.BLACK, false), Triple("not me", Color.WHITE, true)
            )) {
                val t = text(report, user, notMe)
                assertNotNull("$name/$label", t)
                t!!
                assertTrue("$name/$label: [$t]", t.endsWith(".") && !t.contains("..") && !t.contains("  ") && t[0].isUpperCase())
                assertTrue("$name/$label: one sentence plus at most a colon clause: [$t]", t.count { it == '.' } == 1)
                val numbers = Regex("\\d+").findAll(t).map { it.value.toInt() }.toList()
                assertTrue("$name/$label: numbers $numbers not in the report: [$t]", numbers.all { it in allowed })
            }
            // The result is 1-0 in both recordings; nothing may claim the other side won.
            val none = GameSummarySentence.build(report, null)!!
            if (none.subject != null && none.kind in setOf(SummaryKind.CLEAN_WIN, SummaryKind.WON_DESPITE_ERROR, SummaryKind.COMEBACK)) {
                assertEquals("$name: the winner", Color.WHITE, none.subject!!.color)
            }
        }
    }

    @Test
    fun `real games name the move the report calls the turning point`() {
        for (game in listOf(RealGameFixture.immortal, RealGameFixture.chesscom)) {
            val report = game.report()
            val s = GameSummarySentence.build(report, null)!!
            val turning = GameSummarySentence.turningPoint(report.annotations)
            if (s.moveNumber != null) {
                assertEquals(turning!!.moveNumber, s.moveNumber)
                val top = report.annotations.filter {
                    it.loss > 0.5 && it.classification != MoveClassification.BOOK && it.classification != MoveClassification.FORCED
                }
                assertEquals("it must be the largest loss", top.maxOf { it.loss }, turning.loss, 0.0)
            }
        }
    }
}
