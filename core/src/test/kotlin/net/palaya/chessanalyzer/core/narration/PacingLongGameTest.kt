package net.palaya.chessanalyzer.core.narration

import net.palaya.chessanalyzer.core.analysis.EngineLineInput
import net.palaya.chessanalyzer.core.analysis.GameAnalyzer
import net.palaya.chessanalyzer.core.analysis.GameReport
import net.palaya.chessanalyzer.core.analysis.MoveAnnotation
import net.palaya.chessanalyzer.core.analysis.MoveClassification
import net.palaya.chessanalyzer.core.analysis.MoveClassifier
import net.palaya.chessanalyzer.core.analysis.PositionEval
import net.palaya.chessanalyzer.core.analysis.SeeEvaluator
import net.palaya.chessanalyzer.core.analysis.TacticInstance
import net.palaya.chessanalyzer.core.analysis.TacticType
import net.palaya.chessanalyzer.core.analysis.TacticsDetector
import net.palaya.chessanalyzer.core.chess.Color
import net.palaya.chessanalyzer.core.chess.Move
import net.palaya.chessanalyzer.core.chess.Position
import net.palaya.chessanalyzer.core.chess.Square
import net.palaya.chessanalyzer.core.chess.moveToSan
import net.palaya.chessanalyzer.core.pgn.PgnGame
import net.palaya.chessanalyzer.core.pgn.PgnParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ANALYSIS_SPEC §9.7: "a 40-move game stays at or under about 12 minutes".
 *
 * Neither fixture game is long, so this builds the worst case on purpose: Byrne-Fischer, 1956 (41
 * moves, 82 plies, ends in mate) with a drama planted every few plies - a puzzle-worthy blunder
 * with an eight-ply line, a great move, a found mating net - so every tier is fed more than the
 * budget can hold. What the budget has to do is pick, not pad and not overrun.
 */
class PacingLongGameTest {

    private val pgnText = """
        [Event "Third Rosenwald Trophy"]
        [Site "New York"]
        [Date "1956.10.17"]
        [White "Donald Byrne"]
        [Black "Robert James Fischer"]
        [Result "0-1"]

        1. Nf3 Nf6 2. c4 g6 3. Nc3 Bg7 4. d4 O-O 5. Bf4 d5 6. Qb3 dxc4 7. Qxc4 c6 8. e4 Nbd7
        9. Rd1 Nb6 10. Qc5 Bg4 11. Bg5 Na4 12. Qa3 Nxc3 13. bxc3 Nxe4 14. Bxe7 Qb6 15. Bc4 Nxc3
        16. Bc5 Rfe8+ 17. Kf1 Be6 18. Bxb6 Bxc4+ 19. Kg1 Ne2+ 20. Kf1 Nxd4+ 21. Kg1 Ne2+
        22. Kf1 Nc3+ 23. Kg1 axb6 24. Qb4 Ra4 25. Qxb6 Nxd1 26. h3 Rxa2 27. Kh2 Nxf2 28. Re1 Rxe1
        29. Qd8+ Bf8 30. Nxe1 Bd5 31. Nf3 Ne4 32. Qb8 b5 33. h4 h5 34. Ne5 Kg7 35. Kg1 Bc5+
        36. Kf1 Ng3+ 37. Ke1 Bb4+ 38. Kd1 Bb3+ 39. Kc1 Ne2+ 40. Kb1 Nc3+ 41. Kc1 Rc2# 0-1
    """.trimIndent()

    private val game: PgnGame = PgnParser.parse(pgnText).single()

    private class NoSee : SeeEvaluator {
        override fun see(position: Position, move: Move): Int = 0
        override fun isHanging(position: Position, square: Square): Boolean = false
    }

    private class NoTactics : TacticsDetector {
        override fun detect(position: Position, move: Move, pvUci: List<String>): List<TacticInstance> = emptyList()
    }

    /** Flat evaluations: every move is "best", nothing is a tactic, the game is a clean slate. */
    private fun baseline(): GameReport {
        val evals = game.moves.map { m ->
            PositionEval(m.positionFenBefore, listOf(EngineLineInput(1, 0, null, 18, listOf(m.uci))), 18)
        } + PositionEval(
            game.moves.last().positionFenAfter,
            listOf(EngineLineInput(1, 0, null, 18, emptyList())),
            18
        )
        return GameAnalyzer(MoveClassifier(NoSee()), NoTactics()).analyze(game, evals, null, null)
    }

    /** A quiet legal alternative and an eight-ply line starting with it, for the "should have played" walk. */
    private fun alternative(a: MoveAnnotation): Pair<String, List<String>> {
        var pos = Position.fromFen(a.fenBefore)
        val first = pos.legalMoves().first { it.toUci() != a.uci && !it.isCapture && it.promotion == null }
        val sans = ArrayList<String>()
        var move = first
        while (sans.size < 8) {
            sans.add(pos.moveToSan(move))
            pos = pos.makeMove(move)
            move = pos.legalMoves().firstOrNull { !it.isCapture && it.promotion == null } ?: break
        }
        return first.toUci() to sans
    }

    /**
     * Blunders with a missed rook-winning fork every eighth ply from 12 (ten of them, losses 20-38),
     * a great move with a found mating net on the plies in between, and an inaccuracy after each.
     */
    private fun dramatic(): GameReport {
        val base = baseline()
        val annotations = base.annotations.map { a ->
            val last = a.ply == base.annotations.size
            when {
                last -> a.copy(classification = MoveClassification.GREAT, evalBeforeCp = 0, evalAfterCp = 50)
                a.ply >= 12 && a.ply % 8 == 4 -> {
                    val (alt, line) = alternative(a)
                    val sign = if (a.color == Color.WHITE) -1 else 1
                    a.copy(
                        classification = MoveClassification.BLUNDER,
                        loss = 20.0 + (a.ply % 19), winPercentBefore = 70.0, winPercentAfter = 40.0,
                        evalBeforeCp = 0, evalAfterCp = sign * 400,
                        bestMoveUci = alt, bestMoveSan = line.first(), bestLineSan = line,
                        tacticsMissed = listOf(TacticInstance(TacticType.FORK, a.color, alt, materialSwing = 500, confidence = 0.95))
                    )
                }
                a.ply >= 12 && a.ply % 8 == 6 -> a.copy(
                    classification = MoveClassification.GREAT, evalBeforeCp = 0, evalAfterCp = 120,
                    tacticsFound = listOf(TacticInstance(TacticType.MATE_NET, a.color, a.uci, materialSwing = 10_000, confidence = 0.95))
                )
                a.ply >= 12 && a.ply % 8 == 7 -> a.copy(
                    classification = MoveClassification.INACCURACY, loss = 6.0, evalBeforeCp = 0, evalAfterCp = 90
                )
                else -> a
            }
        }
        return base.copy(
            annotations = annotations,
            keyMoments = emptyList(),
            white = base.white.copy(tacticsFound = emptyList(), tacticsMissed = emptyList()),
            black = base.black.copy(tacticsFound = emptyList(), tacticsMissed = emptyList())
        )
    }

    private fun budgetMs(plies: Int): Long = VideoScriptGenerator.budgetMs((plies + 1) / 2)

    @Test
    fun `a forty-one move game stays under twelve minutes however much happens in it`() {
        val report = dramatic()
        val s = VideoScriptGenerator(null).generate(report, game, NarrationOptions())
        assertEquals(82, report.annotations.size)
        assertTrue("ran ${s.totalEstimatedMs / 1000}s", s.totalEstimatedMs <= 720_000)
        assertTrue(
            "ran ${s.totalEstimatedMs / 1000}s against a ${budgetMs(82) / 1000}s budget",
            s.totalEstimatedMs <= budgetMs(82)
        )
        // The plan is a pick, not a blackout: the story is still told.
        assertTrue("only ${s.segments.size} beats", s.segments.size >= 12)
        assertTrue(s.segments.any { it.ply == 82 })
    }

    @Test
    fun `the unconstrained plan really does overrun, so the budget is what held it`() {
        // EVERY_MOVE ignores the budget; at the same speech rate it shows how much drama was planted.
        val report = dramatic()
        val all = VideoScriptGenerator(null).generate(report, game, NarrationOptions(depth = NarrationDepth.EVERY_MOVE))
        val highlights = VideoScriptGenerator(null).generate(report, game, NarrationOptions())
        assertTrue("every-move ${all.totalEstimatedMs}", all.totalEstimatedMs > 720_000)
        assertTrue("highlights ${highlights.totalEstimatedMs} vs ${all.totalEstimatedMs}", highlights.totalEstimatedMs < all.totalEstimatedMs)
    }

    @Test
    fun `at most three full moments, and every other puzzle-worthy blunder is a short variation`() {
        val report = dramatic()
        val s = VideoScriptGenerator(null).generate(report, game, NarrationOptions())
        val puzzles = s.segments.filter { it.kind == SegmentKind.PUZZLE_PROMPT }.mapNotNull { it.ply }.distinct()
        assertTrue("puzzles at $puzzles", puzzles.size <= 3)
        for (ply in s.segments.mapNotNull { it.ply }.distinct()) {
            val walked = s.segments.count { it.ply == ply && it.kind == SegmentKind.MISSED_TACTIC && it.board is BoardDirective.PlayMove }
            if (ply !in puzzles) assertTrue("ply $ply walked $walked plies without a puzzle", walked <= 4)
            assertTrue(walked <= 8)
        }
    }

    @Test
    fun `the turning point of the long game is told in full`() {
        val report = dramatic()
        val tp = report.annotations.filter { it.loss > 0.5 }.maxWithOrNull(compareBy({ it.loss }, { -it.ply }))!!
        val s = VideoScriptGenerator(null).generate(report, game, NarrationOptions())
        assertTrue(s.segments.any { it.kind == SegmentKind.TURNING_POINT && it.ply == tp.ply })
        assertTrue(s.segments.any { it.kind == SegmentKind.PUZZLE_PROMPT && it.ply == tp.ply })
    }
}
