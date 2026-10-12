package net.palaya.chessanalyzer.ui.model

import net.palaya.chessanalyzer.R
import net.palaya.chessanalyzer.core.chess.Color
import net.palaya.chessanalyzer.ui.components.insightStringRes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import net.palaya.chessanalyzer.ui.model.WaitingTestSupport as T

/**
 * Every live insight is an exact derivation (CLAUDE.md, "Generated text is a claim"). Each test builds a game and
 * the evaluations by hand, works the expected numbers out on paper, and pins the whole sentence as the app prints it
 * (the real strings.xml template with the real arguments).
 */
class LiveInsightsTest {

    private fun lines(insights: List<LiveInsight>) = insights.map { T.renderInsight(it) }

    // plies:  1 e4  2 e5  3 Nf3  4 Nc6  5 Bc4  6 Bc5  7 O-O  8 Nf6  9 d3  10 d6
    // White-relative cp after ply 0..10. Ply 6 (Black's ...Bc5) gives away 270 cp, everything else is steady.
    private val cps = listOf(20, 30, 20, 40, 20, 30, 300, 280, 100, 110, 100)
    private val evals by lazy { T.evalsOf(T.ITALIAN, cps) }

    @Test
    fun theWholeListForAGameWithNoSideChosen() {
        val got = lines(liveInsights(T.ITALIAN, evals, userColor = null, book = T.miniBook()))
        assertEquals(
            listOf(
                "Opening: Italian Game: Giuoco Piano (C54)",
                "Biggest swing so far: 3…Bc5 (White's view: +0.3 → +3.0)",
                "Material after 5…d6: level",
                "Longest precise run so far: White, 5 moves in a row (moves 1 to 5), each losing under 2 points of win chance",
                "Longest precise run so far: Black, 2 moves in a row (moves 1 to 2), each losing under 2 points of win chance",
                "Best evaluation for White so far: +3.0 (after 3…Bc5)",
                "Checks in the moves analysed so far: White 0, Black 0",
                "Captures in the moves analysed so far: White 0, Black 0",
                "The game left the opening book with 4.O-O",
                "White castled kingside on move 4",
                "Black did not castle in this game",
                "Moves in this game: White 5, Black 5",
                "Analysed 11 of 11 positions",
            ),
            got,
        )
    }

    @Test
    fun withASideChosenTheSwingAndTheRunsSpeakToTheUser() {
        val asWhite = lines(liveInsights(T.ITALIAN, evals, Color.WHITE, T.miniBook()))
        assertTrue("Biggest swing so far: 3…Bc5, your opponent's move (White's view: +0.3 → +3.0)" in asWhite)
        assertTrue("Your longest precise run so far: 5 moves in a row (moves 1 to 5), each losing under 2 points of win chance" in asWhite)
        assertTrue("Your opponent's longest precise run so far: 2 moves in a row (moves 1 to 2), each losing under 2 points of win chance" in asWhite)

        val asBlack = lines(liveInsights(T.ITALIAN, evals, Color.BLACK, T.miniBook()))
        assertTrue("Biggest swing so far: 3…Bc5, your move (White's view: +0.3 → +3.0)" in asBlack)
        assertTrue("Your longest precise run so far: 2 moves in a row (moves 1 to 2), each losing under 2 points of win chance" in asBlack)
        assertTrue("Your opponent's longest precise run so far: 5 moves in a row (moves 1 to 5), each losing under 2 points of win chance" in asBlack)
        // Black never had a positive score here, so there is no "best evaluation for Black".
        assertFalse(asBlack.any { it.startsWith("Best evaluation for Black") })
    }

    @Test
    fun aMoveThatLosesMoreThanTwoPointsOfWinChanceBreaksTheRun() {
        // 100 -> 20 cp is a drop of about 5.4 win-percent points: above the 2.0 bound, far below any "blunder" bound.
        val steady = listOf(20, 30, 20, 40, 20, 30, 300, 280, 100, 20, 100)
        val got = lines(liveInsights(T.ITALIAN, T.evalsOf(T.ITALIAN, steady), null))
        assertTrue(got.toString(), "Longest precise run so far: White, 4 moves in a row (moves 1 to 4), each losing under 2 points of win chance" in got)
        assertTrue(got.toString(), "Longest precise run so far: Black, 2 moves in a row (moves 1 to 2), each losing under 2 points of win chance" in got)
        assertFalse(got.any { it.startsWith("Longest precise run so far: White, 5") })
    }

    @Test
    fun withOnlyTheFirstEvaluationsKnownTheCountsStopThere() {
        val partial = evals.filterKeys { it <= 3 }
        val got = lines(liveInsights(T.ITALIAN, partial, null, T.miniBook()))
        assertTrue("Analysed 4 of 11 positions" in got)
        assertTrue("Material after 2.Nf3: level" in got)
        // plies 1..3 move the score by 10, 10 and 20 cp: the biggest is White's 2.Nf3, +0.2 to +0.4
        assertTrue(got.toString(), "Biggest swing so far: 2.Nf3 (White's view: +0.2 → +0.4)" in got)
        assertTrue("Best evaluation for White so far: +0.4 (after 2.Nf3)" in got)
        // Black has one measurable move so far (2...e5): one move is not a run
        assertFalse(got.any { it.startsWith("Longest precise run so far: Black") })
        assertTrue("Longest precise run so far: White, 2 moves in a row (moves 1 to 2), each losing under 2 points of win chance" in got)
    }

    @Test
    fun noEvaluationsYetGivesOnlyTheFactsOfTheMoves() {
        val got = lines(liveInsights(T.ITALIAN, emptyMap(), null, T.miniBook()))
        assertEquals(
            listOf(
                "Opening: Italian Game: Giuoco Piano (C54)",
                "The game left the opening book with 4.O-O",
                "White castled kingside on move 4",
                "Black did not castle in this game",
                "Moves in this game: White 5, Black 5",
                "Analysed 0 of 11 positions",
            ),
            got,
        )
    }

    @Test
    fun withoutABookThereAreNoOpeningInsights() {
        val kinds = liveInsights(T.ITALIAN, evals, null, book = null).map { it.kind }
        assertFalse(InsightKind.OPENING in kinds)
        assertFalse(InsightKind.LEFT_BOOK in kinds)
    }

    @Test
    fun materialAheadChecksAndCapturesAreCountedFromTheMoves() {
        val sans = listOf("e4", "d5", "exd5", "Qxd5", "Nc3", "Qa5", "Bb5+")
        val cp = listOf(30, 40, 20, 90, 60, 50, 50, 120)
        // after ply 3 (exd5) White is a pawn up; evaluations known for plies 0..3
        val atThree = lines(liveInsights(sans, T.evalsOf(sans, cp).filterKeys { it <= 3 }, null))
        assertTrue(atThree.toString(), "Material after 2.exd5: White +1" in atThree)
        assertTrue("Captures in the moves analysed so far: White 1, Black 0" in atThree)
        assertTrue("Checks in the moves analysed so far: White 0, Black 0" in atThree)
        // after ply 7 (Bb5+) the pawn has been taken back; White has given one check and each side has captured once
        val all = lines(liveInsights(sans, T.evalsOf(sans, cp), null))
        assertTrue(all.toString(), "Material after 4.Bb5+: level" in all)
        assertTrue("Captures in the moves analysed so far: White 1, Black 1" in all)
        assertTrue("Checks in the moves analysed so far: White 1, Black 0" in all)
    }

    @Test
    fun materialAheadNamesTheSideAndThePoints() {
        // 1.e4 d5 2.exd5 Qxd5 3.Nc3 Qxg2?? is not legal here; use a queen won for a rook: counts by teaching points
        val sans = listOf("e4", "e5", "Qh5", "Nc6", "Qxe5+", "Nxe5")
        val got = lines(liveInsights(sans, T.evalsOf(sans, listOf(30, 20, 40, 20, 60, -800, -800)), null))
        // White took a pawn (1) and lost the queen (9): after ...Nxe5 Black is ahead by 9 - 1 = 8
        assertTrue(got.toString(), "Material after 3…Nxe5: Black +8" in got)
    }

    @Test
    fun mateAndTheEvaluationSignsAreWrittenAsTheAppWritesThem() {
        val sans = listOf("f3", "e5", "g4", "Qh4#")
        val evals = mapOf(
            0 to T.evalAt(sans, 0, cpWhite = 30),
            1 to T.evalAt(sans, 1, cpWhite = 20),
            2 to T.evalAt(sans, 2, cpWhite = -10),
            3 to T.evalAt(sans, 3, mateWhite = -1),
            4 to T.evalAt(sans, 4, mateWhite = 0),
        )
        val got = lines(liveInsights(sans, evals, null))
        // the mate score saturates to +-9950 cp, so 2.g4 (-0.1 -> -M1) is the biggest swing; the mating move itself only adds 50 cp
        assertTrue(got.toString(), "Biggest swing so far: 2.g4 (White's view: -0.1 → -M1)" in got)
        assertTrue(got.toString(), "Best evaluation for White so far: +0.2 (after 1.f3)" in got)
        assertTrue(got.toString(), "Best evaluation for Black so far: # (after 2…Qh4#)" in got)
        assertTrue("Times the evaluation has switched between White and Black so far: 1" in got)
        assertTrue("Checks in the moves analysed so far: White 0, Black 1" in got)
        assertTrue("Material after 2…Qh4#: level" in got)
        assertTrue("Analysed 5 of 5 positions" in got)
        assertTrue("Moves in this game: White 2, Black 2" in got)
    }

    @Test
    fun changesOfSideSkipZeroReadings() {
        val sans = listOf("e4", "e5", "Nf3", "Nc6", "Bb5", "a6")
        // +0.3, -0.2, -0.4, 0.0 (skipped), +0.6, +0.7, +0.7: two switches
        val got = lines(liveInsights(sans, T.evalsOf(sans, listOf(30, -20, -40, 0, 60, 70, 70)), null))
        assertTrue(got.toString(), "Times the evaluation has switched between White and Black so far: 2" in got)
        // a game that never switches says nothing
        val steady = lines(liveInsights(sans, T.evalsOf(sans, listOf(30, 20, 30, 20, 30, 20, 30)), null))
        assertFalse(steady.any { it.startsWith("Times the evaluation") })
    }

    @Test
    fun castlingOfTheWholeGameIsNamedByMoveNumber() {
        // 1.e4 e5 2.Nf3 Nc6 3.Bc4 Nf6 4.d3 Bc5 5.Bg5 h6 6.Bxf6 Qxf6 7.Nc3 d6 8.Qd2 Be6 9.O-O-O O-O
        val sans = listOf(
            "e4", "e5", "Nf3", "Nc6", "Bc4", "Nf6", "d3", "Bc5", "Bg5", "h6", "Bxf6", "Qxf6",
            "Nc3", "d6", "Qd2", "Be6", "O-O-O", "O-O",
        )
        val got = lines(liveInsights(sans, emptyMap(), null))
        assertEquals(
            listOf(
                "White castled queenside on move 9",
                "Black castled kingside on move 9",
                "Moves in this game: White 9, Black 9",
                "Analysed 0 of 19 positions",
            ),
            got,
        )
    }

    @Test
    fun promotionsAreCountedPerSide() {
        // 1.a4 b5 2.axb5 a5 3.b6 a4 4.b7 a3 5.bxa8=Q axb2 6.Qxb2 (White promoted once, Black never)
        val sans = listOf("a4", "b5", "axb5", "a5", "b6", "a4", "b7", "a3", "bxa8=Q", "axb2", "Qxb2")
        val got = lines(liveInsights(sans, emptyMap(), null))
        assertTrue(got.toString(), "Pawn promotions: White 1, Black 0" in got)
    }

    @Test
    fun theFirstPositionWithoutQueensIsNamedByTheMoveThatCreatedIt() {
        // 1.e4 e5 2.Qh5 Qh4 3.Qxe5+ Qe7 4.Qxe7+ Bxe7: only the last capture removes the second queen
        val sans = listOf("e4", "e5", "Qh5", "Qh4", "Qxe5+", "Qe7", "Qxe7+", "Bxe7")
        assertTrue(lines(liveInsights(sans, emptyMap(), null)).toString(), "First position without queens: after 4…Bxe7" in lines(liveInsights(sans, emptyMap(), null)))
        // without the last move one queen is still on the board
        val oneQueenLeft = lines(liveInsights(sans.dropLast(1), emptyMap(), null))
        assertFalse(oneQueenLeft.any { it.startsWith("First position without queens") })
    }

    @Test
    fun theSignatureIgnoresTheProgressCounterOnly() {
        val base = listOf(LiveInsight(InsightKind.CHECKS, listOf(1, 0)), LiveInsight(InsightKind.PROGRESS, listOf(5, 20)))
        val later = listOf(LiveInsight(InsightKind.CHECKS, listOf(1, 0)), LiveInsight(InsightKind.PROGRESS, listOf(6, 20)))
        val changed = listOf(LiveInsight(InsightKind.CHECKS, listOf(2, 0)), LiveInsight(InsightKind.PROGRESS, listOf(6, 20)))
        assertEquals(insightsSignature(base), insightsSignature(later))
        assertNotEquals(insightsSignature(base), insightsSignature(changed))
    }

    @Test
    fun anIllegalMoveStopsTheReplayInsteadOfCrashing() {
        val got = liveInsights(listOf("e4", "e5", "Qh8"), emptyMap(), null)
        assertTrue(got.any { it.kind == InsightKind.PROGRESS })
        assertEquals(listOf<Any>(0, 4), got.single { it.kind == InsightKind.PROGRESS }.args)
    }

    @Test
    fun insightPagesWrapAroundTheList() {
        val all = (1..7).map { LiveInsight(InsightKind.CHECKS, listOf(it, 0)) }
        assertEquals(all.subList(0, 3), insightsPage(all, 0))
        assertEquals(all.subList(3, 6), insightsPage(all, 1))
        assertEquals(listOf(all[6], all[0], all[1]), insightsPage(all, 2))
        assertEquals(all.subList(0, 2), insightsPage(all.subList(0, 2), 5))
        assertTrue(insightsPage(emptyList(), 3).isEmpty())
    }

    @Test
    fun everyKindHasATemplateAndTheComposableMapsToTheSameOne() {
        val fields = R.string::class.java
        for (kind in InsightKind.entries) {
            assertTrue("${kind.resName} is missing from strings.xml", T.hasString(kind.resName))
            assertEquals("${kind.name}: the composable's mapping", fields.getField(kind.resName).getInt(null), insightStringRes(kind))
        }
    }

    @Test
    fun moveLabelsFollowTheChessNotationForBothSides() {
        assertEquals("17.Qxd4", moveLabel(33, "Qxd4"))
        assertEquals("17…Qxd4", moveLabel(34, "Qxd4!?"))
        assertEquals("1.e4", moveLabel(1, "e4"))
    }

    @Test
    fun noInsightUsesAJudgementWord() {
        // The text of an insight states numbers and moves only (CLAUDE.md: no judgement the numbers do not prove).
        val banned = Regex("(?i)\\b(great|brilliant|blunder|mistake|lucky|unlucky|dominant|crushing|excellent|terrible|amazing|best move)\\b")
        for (kind in InsightKind.entries) {
            val text = T.render(kind.resName, 1, 1, 1, 1, 1, 1)
            assertFalse("${kind.resName}: '$text'", banned.containsMatchIn(text))
        }
    }
}
