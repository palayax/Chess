package net.palaya.chessanalyzer.core.analysis

import net.palaya.chessanalyzer.core.chess.Color
import net.palaya.chessanalyzer.core.chess.Piece
import net.palaya.chessanalyzer.core.chess.PieceType
import net.palaya.chessanalyzer.core.chess.Position
import net.palaya.chessanalyzer.core.chess.Square

/**
 * The material on the board, as a chess.com-style score line shows it (V4): the pieces each side has
 * captured and "+N" for the side that is ahead. A pure function of one position's pieces, so every
 * frame of the video (and any other screen) can compute it from the position it shows.
 *
 * **Points** are the usual teaching values, pawn 1, knight 3, bishop 3, rook 5, queen 9 (the king
 * counts nothing). They are deliberately not [PieceValues]' centipawns: "+2" is what a player reads
 * next to a name, not "+190".
 *
 * **Captured** pieces are the opponent's pieces missing from the standard starting set (8 pawns, 2
 * knights, 2 bishops, 2 rooks, 1 queen). A piece above its starting count can only have come from a
 * promotion, so each extra piece is one pawn that was promoted rather than captured: a side with 7
 * pawns and 2 queens has lost no pawn to capture. (A position that did not come from the standard
 * start, such as a composed study, is read the same way; nothing here can go negative.)
 */
data class MaterialBalance(
    /** How many of each type White has on the board (every type present, kings included). */
    val white: Map<PieceType, Int>,
    val black: Map<PieceType, Int>,
) {
    /** The sum of [POINTS] over a side's pieces. */
    fun points(color: Color): Int = countsOf(color).entries.sumOf { (type, n) -> POINTS.getValue(type) * n }

    /** White's points minus Black's: positive when White is ahead. */
    val advantage: Int get() = points(Color.WHITE) - points(Color.BLACK)

    /** How far [color] is ahead in points, or 0 when it is level or behind (the "+N" next to that side). */
    fun lead(color: Color): Int = (if (color == Color.WHITE) advantage else -advantage).coerceAtLeast(0)

    /**
     * The pieces [by] has captured: the opponent's pieces missing from the starting set, promotions taken
     * into account (see the class doc), weakest first (pawns, knights, bishops, rooks, queen), one entry per
     * piece.
     */
    fun captured(by: Color): List<PieceType> {
        val victim = countsOf(by.opposite())
        val promoted = CAPTURABLE.filter { it != PieceType.PAWN }
            .sumOf { type -> ((victim[type] ?: 0) - START.getValue(type)).coerceAtLeast(0) }
        val out = ArrayList<PieceType>()
        for (type in CAPTURABLE) {
            val start = START.getValue(type)
            val missing = if (type == PieceType.PAWN) {
                start - (victim[type] ?: 0) - promoted
            } else {
                start - (victim[type] ?: 0)
            }
            repeat(missing.coerceAtLeast(0)) { out.add(type) }
        }
        return out
    }

    // The Summary/Board view of the same numbers (A4, merged with V4's class): the difference, the side ahead
    // and the captures most valuable first, as the screens list them.

    /** White's points minus Black's (the same number as [advantage]). */
    val difference: Int get() = advantage

    /** The side ahead on material, or null when it is level. */
    val ahead: Color?
        get() = when {
            advantage > 0 -> Color.WHITE
            advantage < 0 -> Color.BLACK
            else -> null
        }

    /** How many points [color] is ahead by, or 0 when it is level or behind (the same as [lead]). */
    fun advantage(color: Color): Int = lead(color)

    /** Black's pieces that White has taken, most valuable first (queen, rook, bishop, knight, pawn). */
    val capturedByWhite: List<PieceType> get() = captured(Color.WHITE).reversed()

    /** White's pieces that Black has taken, most valuable first. */
    val capturedByBlack: List<PieceType> get() = captured(Color.BLACK).reversed()

    /** The pieces [color] has taken, most valuable first. */
    fun capturedBy(color: Color): List<PieceType> = if (color == Color.WHITE) capturedByWhite else capturedByBlack

    private fun countsOf(color: Color): Map<PieceType, Int> = if (color == Color.WHITE) white else black

    companion object {
        /** The standard value of one piece of [type] (the king counts nothing). */
        fun valueOf(type: PieceType): Int = POINTS.getValue(type)

        /** The teaching values a score line uses (the king counts nothing). */
        val POINTS: Map<PieceType, Int> = mapOf(
            PieceType.PAWN to 1,
            PieceType.KNIGHT to 3,
            PieceType.BISHOP to 3,
            PieceType.ROOK to 5,
            PieceType.QUEEN to 9,
            PieceType.KING to 0,
        )

        /** The standard starting set of one side, without the king. */
        val START: Map<PieceType, Int> = mapOf(
            PieceType.PAWN to 8,
            PieceType.KNIGHT to 2,
            PieceType.BISHOP to 2,
            PieceType.ROOK to 2,
            PieceType.QUEEN to 1,
        )

        /** The types that can be captured, in the order a score line lists them (weakest first). */
        val CAPTURABLE: List<PieceType> = listOf(PieceType.PAWN, PieceType.KNIGHT, PieceType.BISHOP, PieceType.ROOK, PieceType.QUEEN)

        /** From any collection of pieces (a board's contents, in any order). */
        fun of(pieces: Iterable<Piece>): MaterialBalance {
            val white = PieceType.entries.associateWith { 0 }.toMutableMap()
            val black = PieceType.entries.associateWith { 0 }.toMutableMap()
            for (p in pieces) {
                val m = if (p.color == Color.WHITE) white else black
                m[p.type] = m.getValue(p.type) + 1
            }
            return MaterialBalance(white, black)
        }

        fun of(position: Position): MaterialBalance =
            of((0..63).mapNotNull { position.pieceAt(Square(it)) })

        /**
         * From a FEN; only its piece-placement field is read, so a placement alone ("8/8/...") works too.
         * Throws [IllegalArgumentException] for a placement that is not eight ranks of eight squares.
         */
        fun fromFen(fen: String): MaterialBalance {
            val placement = fen.trim().substringBefore(' ')
            val ranks = placement.split('/')
            require(ranks.size == 8) { "Not a FEN placement: $placement" }
            val pieces = ArrayList<Piece>()
            for (rank in ranks) {
                var files = 0
                for (c in rank) {
                    if (c.isDigit()) {
                        files += c - '0'
                    } else {
                        pieces.add(Piece.fromFenChar(c))
                        files++
                    }
                }
                require(files == 8) { "Not a FEN placement: $placement" }
            }
            return of(pieces)
        }
    }
}
