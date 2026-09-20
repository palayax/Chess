package net.palaya.chessanalyzer.core.analysis

import net.palaya.chessanalyzer.core.chess.Color
import net.palaya.chessanalyzer.core.chess.Move
import net.palaya.chessanalyzer.core.chess.PieceType
import net.palaya.chessanalyzer.core.chess.Position
import net.palaya.chessanalyzer.core.chess.Square
import net.palaya.chessanalyzer.core.pgn.PgnParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.FileReader

/**
 * End-to-end test of [GameAnalyzer] against the real `fixtures/chesscom_style_game.pgn`
 * (a genuine chess.com-exported Philidor Defense miniature ending in mate), parsed with the
 * existing (perft-verified) [PgnParser] and fed entirely synthetic [PositionEval]s — no engine
 * involved, per the task. [SeeEvaluator] and [TacticsDetector] are hand-rolled fakes, since the
 * real `core.tactics` implementations are out of this module's scope.
 */
class GameAnalyzerTest {

    private fun findOpeningsFile(): File {
        var dir = File(".").absoluteFile
        repeat(6) {
            val candidate = File(dir, "app/src/main/assets/openings.tsv")
            if (candidate.exists()) return candidate
            dir = dir.parentFile ?: return@repeat
        }
        throw IllegalStateException("Could not locate app/src/main/assets/openings.tsv")
    }

    private fun fixturePgnText(): String {
        var dir = File(".").absoluteFile
        repeat(6) {
            val candidate = File(dir, "fixtures/chesscom_style_game.pgn")
            if (candidate.exists()) return candidate.readText()
            dir = dir.parentFile ?: return@repeat
        }
        throw IllegalStateException("Could not locate fixtures/chesscom_style_game.pgn")
    }

    /** No-op SEE fake: never reports a sacrifice, so BRILLIANT never spuriously fires here. */
    private class NeutralSee : SeeEvaluator {
        override fun see(position: Position, move: Move): Int = 0
        override fun isHanging(position: Position, square: Square): Boolean = false
    }

    /**
     * Reports a tactic for any capturing move (attributed to the mover, i.e. the capturing
     * side — this is what exercises the found-by-mover bucket on both colors, since this game
     * has captures by both White and Black), plus for any move explicitly listed in
     * [alwaysTrigger] (used to force the missed-by-mover bucket for a specific, deliberately
     * "better" alternative move at a couple of plies).
     */
    private class CaptureTacticsDetector(private val alwaysTrigger: Set<String> = emptySet()) : TacticsDetector {
        override fun detect(position: Position, move: Move, pvUci: List<String>): List<TacticInstance> {
            if (!move.isCapture && move.toUci() !in alwaysTrigger) return emptyList()
            return listOf(
                TacticInstance(
                    type = TacticType.HANGING_PIECE,
                    byColor = move.color,
                    moveUci = move.toUci(),
                    targetSquares = listOf(move.to),
                    materialSwing = move.capturedPiece?.let { PieceValues.of(it) } ?: 100,
                    confidence = 0.9
                )
            )
        }
    }

    private fun materialCpForSideToMove(pos: Position): Int {
        var diff = 0
        for (sq in 0..63) {
            val piece = pos.pieceAt(Square(sq)) ?: continue
            if (piece.type == PieceType.KING) continue
            diff += if (piece.color == Color.WHITE) PieceValues.of(piece.type) else -PieceValues.of(piece.type)
        }
        return if (pos.sideToMove == Color.WHITE) diff else -diff
    }

    @Test
    fun `full game report has the right shape, opening, accuracy range and tactic buckets`() {
        val pgnText = fixturePgnText()
        val game = PgnParser.parse(pgnText).single()
        assertEquals(33, game.moves.size) // sanity check on the fixture itself

        // Deliberately give the engine a different (but legal) "best" move at one White ply and
        // one Black ply, well ahead of what was actually played, so both the missed-by-white and
        // missed-by-black buckets get exercised end-to-end.
        val whiteOverrideIndex = 14 // White's 8th move, Nc3
        val blackOverrideIndex = 17 // Black's 9th move, b5
        val alternates = HashSet<String>()

        val evals = ArrayList<PositionEval>()
        for (i in game.moves.indices) {
            val pgnMove = game.moves[i]
            val posBefore = Position.fromFen(pgnMove.positionFenBefore)
            val baseCp = materialCpForSideToMove(posBefore)

            val line = if (i == whiteOverrideIndex || i == blackOverrideIndex) {
                val altMove = posBefore.legalMoves().first { it.toUci() != pgnMove.uci }
                alternates += altMove.toUci()
                EngineLineInput(1, baseCp + 600, null, depth = 18, pvUci = listOf(altMove.toUci()))
            } else {
                EngineLineInput(1, baseCp, null, depth = 18, pvUci = listOf(pgnMove.uci))
            }
            evals.add(PositionEval(pgnMove.positionFenBefore, listOf(line), depth = 18))
        }
        val finalFen = game.moves.last().positionFenAfter
        val finalPos = Position.fromFen(finalFen)
        evals.add(
            PositionEval(
                finalFen,
                listOf(EngineLineInput(1, materialCpForSideToMove(finalPos), null, depth = 18, pvUci = emptyList())),
                depth = 18
            )
        )

        val book = FileReader(findOpeningsFile()).use { OpeningBook.load(it) }
        val classifier = MoveClassifier(NeutralSee())
        val detector = CaptureTacticsDetector(alwaysTrigger = alternates)
        val analyzer = GameAnalyzer(classifier, detector)

        val report = analyzer.analyze(game, evals, userColor = Color.WHITE, book = book)

        // Shape
        assertEquals(33, report.annotations.size)
        report.annotations.forEachIndexed { idx, ann ->
            assertEquals(idx + 1, ann.ply)
            assertEquals(if (idx % 2 == 0) Color.WHITE else Color.BLACK, ann.color)
        }

        // Opening
        assertEquals("Philidor Defense", report.openingName)
        assertEquals("C41", report.openingEco)

        // Accuracy in range for both players
        assertTrue(report.white.accuracy in 0.0..100.0)
        assertTrue(report.black.accuracy in 0.0..100.0)
        assertTrue(report.white.estimatedRating in 100..3000)
        assertTrue(report.black.estimatedRating in 100..3000)

        // Tactic buckets attributed to the correct colours (this game has captures by both
        // sides, e.g. 4. dxe5 for White and 4... Bxf3 for Black).
        assertTrue("white foundByPlayer should be non-empty", report.white.tacticsFound.isNotEmpty())
        assertTrue("black foundByPlayer should be non-empty", report.black.tacticsFound.isNotEmpty())
        assertTrue(report.white.tacticsFound.all { it.byColor == Color.WHITE })
        assertTrue(report.black.tacticsFound.all { it.byColor == Color.BLACK })

        assertTrue("white missedByPlayer should be non-empty", report.white.tacticsMissed.isNotEmpty())
        assertTrue("black missedByPlayer should be non-empty", report.black.tacticsMissed.isNotEmpty())
        assertTrue(report.white.tacticsMissed.all { it.byColor == Color.WHITE })
        assertTrue(report.black.tacticsMissed.all { it.byColor == Color.BLACK })

        // Per-ply annotations must carry non-blank commentary text.
        assertTrue(report.annotations.all { it.text.isNotBlank() })

        // Eval graph has one entry per position (plies + 1).
        assertEquals(34, report.evalGraph.size)

        // The game actually ends in checkmate (Rd8#).
        assertEquals("1-0", report.result)
    }
}
