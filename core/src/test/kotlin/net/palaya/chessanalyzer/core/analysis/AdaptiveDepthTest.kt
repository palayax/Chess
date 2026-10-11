package net.palaya.chessanalyzer.core.analysis

import net.palaya.chessanalyzer.core.analysis.AdaptiveDepth.Config
import net.palaya.chessanalyzer.core.analysis.AdaptiveDepth.Reason
import net.palaya.chessanalyzer.core.chess.Color
import net.palaya.chessanalyzer.core.chess.Position
import net.palaya.chessanalyzer.core.pgn.PgnGame
import net.palaya.chessanalyzer.core.pgn.PgnParser
import net.palaya.chessanalyzer.core.tactics.StaticExchangeEvaluator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.StringReader

/**
 * Auto-depth's policy (ANALYSIS_SPEC §8.5): one test per rule, its threshold on both sides, and the rules
 * about both sides of a move, forced/book/terminal positions, the cap, rounds and determinism. Evaluations are
 * hand-built; no engine.
 */
class AdaptiveDepthTest {

    private val classifier = MoveClassifier(StaticExchangeEvaluator())

    /** 8 plies, 9 positions; no book is passed, so every move is judged by the engine's numbers. */
    private val quiet: PgnGame = PgnParser.parse("1. e4 e5 2. Nf3 Nc6 3. Bc4 Bc5 4. c3 Nf6 *").single()

    private fun fens(game: PgnGame) = listOf(game.moves.first().positionFenBefore) + game.moves.map { it.positionFenAfter }

    /**
     * One evaluation per position from White-relative centipawns. Line 1 is the played move (so every move is Best
     * unless [best] says otherwise), line 2 is [secondGapCp] worse for the side to move.
     */
    private fun evals(
        game: PgnGame,
        whiteCp: List<Int>,
        best: Map<Int, String> = emptyMap(),
        secondGapCp: Map<Int, Int> = emptyMap(),
        mate: Map<Int, Int> = emptyMap(),
    ): List<PositionEval> = fens(game).mapIndexed { i, fen ->
        val position = Position.fromFen(fen)
        if (position.legalMoves().isEmpty()) {
            return@mapIndexed PositionEval(fen, listOf(EngineLineInput(1, null, 0, 14, emptyList())), 14, 14)
        }
        val stm = if (position.sideToMove == Color.WHITE) 1 else -1
        val cp = whiteCp[i] * stm
        val played = game.moves.getOrNull(i)?.uci
        val firstMove = best[i] ?: played ?: position.legalMoves().first().toUci()
        val line1 = if (mate[i] != null) EngineLineInput(1, null, mate[i], 14, listOf(firstMove))
        else EngineLineInput(1, cp, null, 14, listOf(firstMove))
        val otherMove = position.legalMoves().map { it.toUci() }.firstOrNull { it != firstMove }
            ?: return@mapIndexed PositionEval(fen, listOf(line1), 14, 14)
        val line2 = EngineLineInput(2, cp - (secondGapCp[i] ?: 15), null, 14, listOf(otherMove))
        PositionEval(fen, listOf(line1, line2), 14, 14)
    }

    private fun flat(game: PgnGame, cp: Int = 20) = List(game.moves.size + 1) { cp }

    private fun plan(
        game: PgnGame,
        base: List<PositionEval>,
        current: List<PositionEval> = base,
        deepened: Set<Int> = emptySet(),
        round: Int = 1,
        // The 25% cap would hide most rules in a 9-position game; the cap has its own tests.
        config: Config = Config(maxFraction = 1.0),
        book: OpeningBook? = null,
    ) = AdaptiveDepth.plan(game, base, current, deepened, round, book, classifier, config)

    private fun lossOf(e: List<PositionEval>, m: Int, mover: Color) = WinProbability.loss(e[m - 1], e[m], mover)

    @Test
    fun aQuietGameWhereEveryMoveIsBestNeedsNothingDeeper() {
        assertEquals(emptyList<AdaptiveDepth.Deepening>(), plan(quiet, evals(quiet, flat(quiet))))
    }

    @Test
    fun aMistakeDeepensBothOfItsPositions() {
        // 2.Nf3 (ply 3, positions 2 -> 3) drops White from +0.2 to -1.5 although Bc4 was best.
        val cp = flat(quiet).toMutableList().also { for (i in 3..8) it[i] = -150 }
        val e = evals(quiet, cp, best = mapOf(2 to "f1c4"))
        assertTrue(lossOf(e, 3, Color.WHITE) >= 10.0)
        val picks = plan(quiet, e)
        assertEquals(listOf(2, 3), picks.map { it.index })
        assertTrue(picks.all { Reason.MISTAKE in it.reasons })
        assertTrue(picks.all { it.tier == AdaptiveDepth.Tier.DEEP })
    }

    @Test
    fun aLargeSwingInTheMoversFavourCountsFromTheThresholdUp() {
        // White's 2.Nf3 GAINS: position 2 was underrated. No loss, so only the swing rule can see it.
        val cp = flat(quiet).toMutableList().also { it[0] = -150; it[1] = -150; it[2] = -150 }
        val e = evals(quiet, cp)
        val swing = WinProbability.winPercentForColor(e[3], Color.WHITE) - WinProbability.winPercentForColor(e[2], Color.WHITE)
        assertTrue("swing $swing", swing in 15.0..17.0)
        val at = plan(quiet, e, config = Config(swingWin = swing - 0.01, maxFraction = 1.0))
        assertEquals(listOf(2, 3), at.map { it.index })
        assertEquals(setOf(Reason.SWING), at.first().reasons)
        assertEquals(emptyList<AdaptiveDepth.Deepening>(), plan(quiet, e, config = Config(swingWin = swing + 0.01, maxFraction = 1.0)))
    }

    @Test
    fun aLossNearAClassificationBoundaryIsDeepenedAndOneBetweenBoundariesIsNot() {
        // 2.Nf3 not best; White ends at -0.29: a loss of about 4.5, Good, within 25% of the 5.0 boundary.
        val near = flat(quiet).toMutableList().also { for (i in 3..8) it[i] = -29 }
        val e = evals(quiet, near, best = mapOf(2 to "f1c4"))
        val loss = lossOf(e, 3, Color.WHITE)
        assertTrue("loss $loss", loss in 4.2..4.8)
        val picks = plan(quiet, e)
        assertEquals(listOf(2, 3), picks.map { it.index })
        assertEquals(setOf(Reason.BOUNDARY), picks.first().reasons)
        // A loss of about 3.1 is more than 25% from both 2 and 5 (2.5 .. 3.75 is between the margins).
        val between = flat(quiet).toMutableList().also { for (i in 3..8) it[i] = -14 }
        val e2 = evals(quiet, between, best = mapOf(2 to "f1c4"))
        assertTrue(lossOf(e2, 3, Color.WHITE) in 2.6..3.7)
        assertEquals(emptyList<AdaptiveDepth.Deepening>(), plan(quiet, e2))
        // Margin 0 switches the rule off.
        assertEquals(emptyList<AdaptiveDepth.Deepening>(), plan(quiet, e, config = Config(boundaryMargin = 0.0, maxFraction = 1.0)))
    }

    @Test
    fun aClearGreatMoveIsNotDeepenedByDefaultButIsWhenTheGreatRuleIsOn() {
        // A gap of about 32 win%: far from the 10-point boundary. The GREAT rule was measured and is off (spec §8.5).
        val e = evals(quiet, flat(quiet), secondGapCp = mapOf(4 to 400))
        assertEquals(emptyList<AdaptiveDepth.Deepening>(), plan(quiet, e))
        val picks = plan(quiet, e, config = Config(rules = AdaptiveDepth.DEFAULT_RULES + Reason.GREAT, maxFraction = 1.0))
        assertEquals(listOf(4, 5), picks.map { it.index })
        assertEquals(setOf(Reason.GREAT), picks.first().reasons)
    }

    @Test
    fun theDefaultRulesAreTheMeasuredOnes() {
        assertEquals(setOf(Reason.MISTAKE, Reason.MATE, Reason.SWING, Reason.UNSTABLE, Reason.BOUNDARY), AdaptiveDepth.DEFAULT_RULES)
        assertEquals(0.25, Config().maxFraction, 0.0)
        assertEquals(Reason.BOUNDARY, Config().priority.first())
    }

    @Test
    fun aGreatGapNearItsBoundaryIsDeepenedEvenWhenTheMoveIsNotGreat() {
        // Gap of about 8 win% (Great needs 10): within the 3-point margin.
        val e = evals(quiet, flat(quiet), secondGapCp = mapOf(4 to 90))
        val gap = WinProbability.winPercentOfLine(e[4].best!!) - WinProbability.winPercentOfLine(e[4].secondBest!!)
        assertTrue("gap $gap", gap in 7.0..9.9)
        val picks = plan(quiet, e)
        assertEquals(listOf(4, 5), picks.map { it.index })
        assertEquals(setOf(Reason.BOUNDARY), picks.first().reasons)
        assertEquals(emptyList<AdaptiveDepth.Deepening>(), plan(quiet, e, config = Config(greatGapMarginWin = (10.0 - gap) - 0.01, maxFraction = 1.0)))
    }

    @Test
    fun aMateScoreDeepensTheMovesIntoAndOutOfThePosition() {
        val e = evals(quiet, flat(quiet), mate = mapOf(5 to 4))
        val picks = plan(quiet, e)
        assertEquals(setOf(4, 5, 6), picks.map { it.index }.toSet())
        assertTrue(picks.all { Reason.MATE in it.reasons })
    }

    @Test
    fun aCheckmateOnTheBoardIsNeverSearchedAgain() {
        val mate = PgnParser.parse("1. e4 e5 2. Bc4 Nc6 3. Qh5 Nf6 4. Qxf7# 1-0").single()
        val e = evals(mate, flat(mate), mate = mapOf(6 to 1))
        val picks = plan(mate, e)
        assertTrue(picks.isNotEmpty())
        assertTrue("the final, checkmated position (7) is exact", picks.none { it.index == 7 })
        assertTrue(picks.any { it.index == 6 })
    }

    @Test
    fun aForcedMoveIsNotDeepenedHoweverMuchTheNumbersMove() {
        val forced = PgnParser.parse("[SetUp \"1\"]\n[FEN \"7k/8/6K1/8/8/8/8/7Q b - - 0 1\"]\n\n1... Kg8 *").single()
        val e = evals(forced, listOf(0, 900))
        assertEquals(emptyList<AdaptiveDepth.Deepening>(), plan(forced, e))
    }

    @Test
    fun aBookMoveIsNotDeepenedButTheFirstMoveOutOfBookIs() {
        val book = OpeningBook.load(StringReader("eco\tname\tpgn\nC50\tItalian Game\t1. e4 e5 2. Nf3 Nc6 3. Bc4\n"))
        // A big swing on 2.Nf3 (in book) and on 3...Bc5 (out of book).
        val cp = flat(quiet).toMutableList().also { it[3] = 300; it[4] = 300; it[5] = 300; it[6] = -300; it[7] = -300; it[8] = -300 }
        val picks = plan(quiet, evals(quiet, cp), book = book)
        assertEquals(setOf(5, 6), picks.map { it.index }.toSet())
    }

    @Test
    fun theCapStopsThePicksAndAMoveIsDeepenedOnBothSidesOrNotAtAll() {
        // Every move swings by 20 win%.
        val cp = List(quiet.moves.size + 1) { if (it % 2 == 0) 20 else -250 }
        val e = evals(quiet, cp)
        val all = plan(quiet, e, config = Config(maxFraction = 1.0))
        assertEquals((0..8).toList(), all.map { it.index }.sorted())
        // 9 searchable positions x 0.25 = 2: the first move's pair fits, and every other move would need a third.
        assertEquals(listOf(0, 1), plan(quiet, e, config = Config(maxFraction = 0.25)).map { it.index })
        // x 0.34 = 3: the second move shares position 1 with the first, so its other side still fits; the third
        // move would need two fresh positions and gets none (never one side alone).
        assertEquals(listOf(0, 1, 2), plan(quiet, e, config = Config(maxFraction = 0.34)).map { it.index })
        // Positions already deepened count against the cap.
        assertEquals(emptyList<AdaptiveDepth.Deepening>(), plan(quiet, e, deepened = setOf(0, 1, 2), config = Config(maxFraction = 0.34)))
    }

    @Test
    fun underTheCapAMoveNearABoundaryBeatsAClearBlunder() {
        // 2.Nf3 (ply 3) loses about 4.5 (near the 5.0 boundary); 4.c3 (ply 7) loses about 45, a Blunder far from any
        // boundary (its label is the least likely to change). Room for one pair: the boundary move gets it.
        val cp = flat(quiet).toMutableList().also { for (i in 3..8) it[i] = -29; it[7] = -900; it[8] = -900 }
        val e = evals(quiet, cp, best = mapOf(2 to "f1c4", 6 to "d2d3"))
        assertTrue(lossOf(e, 7, Color.WHITE) > 30.0)
        val picks = plan(quiet, e, config = Config(maxFraction = 0.25))
        assertEquals(listOf(2, 3), picks.map { it.index })
        assertEquals(setOf(Reason.BOUNDARY), picks.first().reasons)
        // With the blunder ranked first it wins instead.
        val blunderFirst = Config(maxFraction = 0.25, priority = listOf(Reason.MISTAKE, Reason.BOUNDARY))
        assertEquals(listOf(6, 7), plan(quiet, e, config = blunderFirst).map { it.index })
    }

    @Test
    fun higherPriorityMovesArePickedFirst() {
        // A boundary loss on 2.Nf3 (ply 3) and a mistake on 4.c3 (ply 7).
        val cp = flat(quiet).toMutableList().also { for (i in 3..8) it[i] = -29; it[7] = -250; it[8] = -250 }
        val e = evals(quiet, cp, best = mapOf(2 to "f1c4", 6 to "d2d3"))
        // Room for one pair: the mistake (also near the 20 boundary, with the larger swing) gets it.
        val picks = plan(quiet, e, config = Config(maxFraction = 0.25))
        assertEquals(listOf(6, 7), picks.map { it.index })
        assertTrue(Reason.MISTAKE in picks[0].reasons)
        // With room for both, they come back in game order.
        assertEquals(listOf(2, 3, 6, 7), plan(quiet, e).map { it.index })
    }

    @Test
    fun anUnstablePositionDeepensTheOtherSideOfBothItsMovesInTheNextRound() {
        val base = evals(quiet, flat(quiet))
        // Position 3 came back from the deep search 6 win% better for White: below the swing threshold.
        val current = base.toMutableList().also { it[3] = evals(quiet, flat(quiet).toMutableList().also { c -> c[3] = 86 })[3] }
        val moved = WinProbability.winPercentForColor(current[3], Color.WHITE) - WinProbability.winPercentForColor(base[3], Color.WHITE)
        assertTrue("moved $moved", moved in 5.5..7.9)
        val picks = plan(quiet, base, current, deepened = setOf(3), round = 2)
        assertEquals(listOf(2, 4), picks.map { it.index }.sorted())
        assertTrue(picks.all { it.reasons == setOf(Reason.UNSTABLE) })
        // Below the threshold nothing moves.
        assertEquals(emptyList<AdaptiveDepth.Deepening>(), plan(quiet, base, current, setOf(3), 2, Config(unstableWin = moved + 0.01, maxFraction = 1.0)))
    }

    @Test
    fun aDeepenedPositionIsNeverPickedAgain() {
        val cp = flat(quiet).toMutableList().also { for (i in 3..8) it[i] = -150 }
        val e = evals(quiet, cp, best = mapOf(2 to "f1c4"))
        assertEquals(emptyList<AdaptiveDepth.Deepening>(), plan(quiet, e, deepened = setOf(2, 3), round = 2, config = Config(unstableWin = 101.0, maxFraction = 1.0)))
    }

    @Test
    fun theCloseChoiceRuleIsOffByDefaultAndPicksBestMovesWithACloseSecondLineWhenOn() {
        val e = evals(quiet, flat(quiet))
        assertEquals(emptyList<AdaptiveDepth.Deepening>(), plan(quiet, e))
        val on = Config(closeGapWin = 2.0, maxFraction = 1.0, rules = AdaptiveDepth.DEFAULT_RULES + Reason.CLOSE)
        val picks = plan(quiet, e, config = on)
        assertEquals((0..8).toList(), picks.map { it.index }.sorted())
        assertTrue(picks.all { Reason.CLOSE in it.reasons })
        // Not in the rule set: off even with a gap.
        assertEquals(emptyList<AdaptiveDepth.Deepening>(), plan(quiet, e, config = Config(closeGapWin = 2.0, maxFraction = 1.0)))
        // A decided position does not count.
        val won = evals(quiet, flat(quiet, cp = 900))
        assertEquals(emptyList<AdaptiveDepth.Deepening>(), plan(quiet, won, config = on))
    }

    @Test
    fun thePlanIsDeterministicAndStopsAfterTheLastRound() {
        val cp = List(quiet.moves.size + 1) { if (it % 2 == 0) 20 else -250 }
        val e = evals(quiet, cp)
        assertEquals(plan(quiet, e), plan(quiet, e))
        assertEquals(emptyList<AdaptiveDepth.Deepening>(), plan(quiet, e, round = Config().maxRounds + 1))
        assertEquals(emptyList<AdaptiveDepth.Deepening>(), plan(quiet, e, round = 0))
    }
}
