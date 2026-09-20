package net.palaya.chessanalyzer.core.analysis

import net.palaya.chessanalyzer.core.chess.Color
import net.palaya.chessanalyzer.core.chess.Move
import net.palaya.chessanalyzer.core.chess.Position
import net.palaya.chessanalyzer.core.chess.Square
import net.palaya.chessanalyzer.core.chess.moveToSan
import net.palaya.chessanalyzer.core.chess.parseSan
import net.palaya.chessanalyzer.core.pgn.PgnGame
import net.palaya.chessanalyzer.core.pgn.PgnMove
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ANALYSIS_SPEC.md 5.4 — `foundByPlayer` ("recognised by mover") is gated on the played move
 * being classified BEST/GREAT/BRILLIANT. Detecting a motif on a merely GOOD (or worse) move does
 * not mean the player spotted anything, so it must not land in that bucket.
 */
class GameAnalyzerTacticGateTest {

    // -----------------------------------------------------------------------
    // Fixtures
    // -----------------------------------------------------------------------

    /** A White pawn capture (dxe5) is available and there are plenty of other legal moves. */
    private val fen = "4k3/8/8/4p3/3P4/8/8/4K3 w - - 0 1"

    private class NeutralSee : SeeEvaluator {
        override fun see(position: Position, move: Move): Int = 0
        override fun isHanging(position: Position, square: Square): Boolean = false
    }

    /** Fires on every move and records which positions/moves it was asked about. */
    private class AlwaysDetector : TacticsDetector {
        val calls = ArrayList<String>()
        override fun detect(position: Position, move: Move, pvUci: List<String>): List<TacticInstance> {
            calls += move.toUci()
            return listOf(
                TacticInstance(
                    type = TacticType.HANGING_PIECE,
                    byColor = move.color,
                    moveUci = move.toUci(),
                    targetSquares = listOf(move.to),
                    materialSwing = 100,
                    confidence = 0.9
                )
            )
        }
    }

    private fun singleMoveGame(startFen: String, san: String): PgnGame {
        val before = Position.fromFen(startFen)
        val move = before.parseSan(san)
        val after = before.makeMove(move)
        val pgnMove = PgnMove(
            san = before.moveToSan(move),
            uci = move.toUci(),
            moveNumber = before.fullmoveNumber,
            color = before.sideToMove,
            positionFenBefore = before.toFen(),
            positionFenAfter = after.toFen()
        )
        return PgnGame(tags = emptyMap(), moves = listOf(pgnMove), result = "*", startFen = startFen)
    }

    /**
     * @param bestUci the engine's top move before the played move.
     * @param cpAfterForOpponent eval of the resulting position from the (opponent) side to move's
     *   perspective — positive means the mover has lost ground, which is what drives `loss`.
     */
    private fun evals(game: PgnGame, bestUci: String, cpAfterForOpponent: Int): List<PositionEval> {
        val pgnMove = game.moves.single()
        return listOf(
            PositionEval(
                pgnMove.positionFenBefore,
                listOf(EngineLineInput(1, 0, null, depth = 18, pvUci = listOf(bestUci))),
                depth = 18
            ),
            PositionEval(
                pgnMove.positionFenAfter,
                listOf(EngineLineInput(1, cpAfterForOpponent, null, depth = 18, pvUci = emptyList())),
                depth = 18
            )
        )
    }

    private fun analyzeSingleMove(bestUci: String, cpAfterForOpponent: Int): Pair<MoveAnnotation, AlwaysDetector> {
        val game = singleMoveGame(fen, "dxe5")
        val detector = AlwaysDetector()
        val analyzer = GameAnalyzer(MoveClassifier(NeutralSee()), detector)
        val report = analyzer.analyze(game, evals(game, bestUci, cpAfterForOpponent), Color.WHITE, book = null)
        return report.annotations.single() to detector
    }

    private fun alternativeUci(): String {
        val pos = Position.fromFen(fen)
        val played = pos.parseSan("dxe5").toUci()
        return pos.legalMoves().first { it.toUci() != played && !it.isCapture }.toUci()
    }

    // -----------------------------------------------------------------------
    // Tests
    // -----------------------------------------------------------------------

    @Test
    fun `a BEST move with a detected tactic populates tacticsFound`() {
        val played = Position.fromFen(fen).parseSan("dxe5").toUci()
        val (annotation, _) = analyzeSingleMove(bestUci = played, cpAfterForOpponent = 0)

        assertEquals(MoveClassification.BEST, annotation.classification)
        assertEquals(1, annotation.tacticsFound.size)
        assertEquals(TacticType.HANGING_PIECE, annotation.tacticsFound.single().type)
        assertTrue(annotation.tacticsMissed.isEmpty())
    }

    @Test
    fun `the same tactic on a GOOD move does not populate tacticsFound`() {
        // Engine prefers something else and the played move gives up ~2.8 win% => GOOD.
        val (annotation, detector) = analyzeSingleMove(bestUci = alternativeUci(), cpAfterForOpponent = 30)

        assertEquals(MoveClassification.GOOD, annotation.classification)
        assertTrue(
            "GOOD must not be treated as 'recognised': ${annotation.tacticsFound}",
            annotation.tacticsFound.isEmpty()
        )
        // The detector really did fire on the played move — it is the gate, not the detector,
        // that empties the bucket.
        val playedUci = Position.fromFen(fen).parseSan("dxe5").toUci()
        assertTrue("detector should still have been run on the played move", playedUci in detector.calls)
    }

    @Test
    fun `the same tactic on an INACCURACY does not populate tacticsFound but tacticsMissed is unchanged`() {
        // ~7.3 win% given up => INACCURACY, and loss >= 5.0 so the missed bucket applies.
        val (annotation, _) = analyzeSingleMove(bestUci = alternativeUci(), cpAfterForOpponent = 80)

        assertEquals(MoveClassification.INACCURACY, annotation.classification)
        assertTrue(annotation.tacticsFound.isEmpty())
        assertTrue("tacticsMissed behaviour must be unchanged", annotation.tacticsMissed.isNotEmpty())
        assertTrue(annotation.tacticsMissed.all { it.byColor == Color.WHITE })
    }

    @Test
    fun `the player report only credits gated tactics`() {
        val game = singleMoveGame(fen, "dxe5")
        val analyzer = GameAnalyzer(MoveClassifier(NeutralSee()), AlwaysDetector())
        val report = analyzer.analyze(game, evals(game, alternativeUci(), 30), Color.WHITE, book = null)

        assertTrue(
            "a GOOD move must not add to the player's 'tactics you found' list",
            report.white.tacticsFound.isEmpty()
        )
    }

    @Test
    fun `commentary still describes what a non-recognised move did`() {
        // The raw detection stays available to the commentary templates even though the
        // spec-gated bucket is empty.
        val (annotation, _) = analyzeSingleMove(bestUci = alternativeUci(), cpAfterForOpponent = 30)
        assertTrue(annotation.tacticsFound.isEmpty())
        assertTrue(annotation.text.isNotBlank())
    }
}
