package net.palaya.chessanalyzer.core.narration

import net.palaya.chessanalyzer.core.analysis.EngineLineInput
import net.palaya.chessanalyzer.core.analysis.GameAnalyzer
import net.palaya.chessanalyzer.core.analysis.GameReport
import net.palaya.chessanalyzer.core.analysis.MoveClassifier
import net.palaya.chessanalyzer.core.analysis.OpeningBook
import net.palaya.chessanalyzer.core.analysis.PieceValues
import net.palaya.chessanalyzer.core.analysis.PositionEval
import net.palaya.chessanalyzer.core.analysis.SeeEvaluator
import net.palaya.chessanalyzer.core.analysis.TacticInstance
import net.palaya.chessanalyzer.core.analysis.TacticType
import net.palaya.chessanalyzer.core.analysis.TacticsDetector
import net.palaya.chessanalyzer.core.chess.Color
import net.palaya.chessanalyzer.core.chess.Move
import net.palaya.chessanalyzer.core.chess.PieceType
import net.palaya.chessanalyzer.core.chess.Position
import net.palaya.chessanalyzer.core.chess.Square
import net.palaya.chessanalyzer.core.pgn.PgnGame
import net.palaya.chessanalyzer.core.pgn.PgnParser
import java.io.File
import java.io.FileReader

/**
 * Builds a real [GameReport] over `fixtures/chesscom_style_game.pgn` with synthetic
 * [PositionEval]s, exactly as `analysis/GameAnalyzerTest` does — same PGN, same "no engine, fake
 * evals" approach — so the narration tests run against the genuine pipeline output rather than a
 * hand-written report.
 *
 * The one addition: at a handful of plies the "engine" prefers a different legal move and the
 * fake detector reports a juicy [TacticType.FORK] for it, which is what populates the
 * missed-tactic buckets and gives the script something to build a puzzle prompt around.
 */
object NarrationFixture {

    /** Plies (0-based move indices) where the fake engine prefers something else. */
    private val WHITE_OVERRIDES = listOf(6, 14, 22)
    private val BLACK_OVERRIDES = listOf(17)

    fun game(): PgnGame = PgnParser.parse(fixturePgnText()).single()

    fun report(userColor: Color? = Color.WHITE): GameReport {
        val game = game()
        val alternates = HashSet<String>()
        val evals = ArrayList<PositionEval>()

        for (i in game.moves.indices) {
            val pgnMove = game.moves[i]
            val posBefore = Position.fromFen(pgnMove.positionFenBefore)
            val baseCp = materialCpForSideToMove(posBefore)

            val line = if (i in WHITE_OVERRIDES || i in BLACK_OVERRIDES) {
                val alt = posBefore.legalMoves().first { it.toUci() != pgnMove.uci }
                alternates += alt.toUci()
                EngineLineInput(1, baseCp + 600, null, depth = 18, pvUci = continuation(posBefore, alt, 3))
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
        val analyzer = GameAnalyzer(MoveClassifier(NeutralSee()), FixtureTacticsDetector(alternates))
        return analyzer.analyze(game, evals, userColor = userColor, book = book)
    }

    /** A short legal continuation so the missed-tactic PlayLine has a real line to play out. */
    private fun continuation(start: Position, first: Move, plies: Int): List<String> {
        val uci = ArrayList<String>()
        var pos = start
        var move: Move? = first
        while (move != null && uci.size < plies) {
            uci.add(move.toUci())
            pos = pos.makeMove(move)
            move = pos.legalMoves().firstOrNull()
        }
        return uci
    }

    /** No-op SEE, matching GameAnalyzerTest, so BRILLIANT never spuriously fires. */
    private class NeutralSee : SeeEvaluator {
        override fun see(position: Position, move: Move): Int = 0
        override fun isHanging(position: Position, square: Square): Boolean = false
    }

    /**
     * Captures report a hanging piece; the deliberately-preferred alternates report a fork worth
     * a rook, which is what makes them significant enough to earn a puzzle prompt.
     */
    private class FixtureTacticsDetector(private val alwaysTrigger: Set<String>) : TacticsDetector {
        override fun detect(position: Position, move: Move, pvUci: List<String>): List<TacticInstance> {
            if (move.toUci() in alwaysTrigger) {
                return listOf(
                    TacticInstance(
                        type = TacticType.FORK,
                        byColor = move.color,
                        moveUci = move.toUci(),
                        targetSquares = listOf(move.to, position.kingSquare(move.color.opposite())),
                        materialSwing = 500,
                        confidence = 0.95
                    )
                )
            }
            if (!move.isCapture) return emptyList()
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

    private fun findOpeningsFile(): File = locate("app/src/main/assets/openings.tsv")

    private fun fixturePgnText(): String = locate("fixtures/chesscom_style_game.pgn").readText()

    private fun locate(relative: String): File {
        var dir = File(".").absoluteFile
        repeat(6) {
            val candidate = File(dir, relative)
            if (candidate.exists()) return candidate
            dir = dir.parentFile ?: return@repeat
        }
        throw IllegalStateException("Could not locate $relative")
    }
}
