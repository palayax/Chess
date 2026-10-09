package net.palaya.chessanalyzer.core.analysis

import net.palaya.chessanalyzer.core.chess.Color
import net.palaya.chessanalyzer.core.chess.PieceType
import net.palaya.chessanalyzer.core.chess.Position

/**
 * Material on the board and the pieces taken off it, worked out from one position alone (A4).
 *
 * Values are the usual 1 / 3 / 3 / 5 / 9 (the king has none). They are deliberately not
 * [PieceValues], which are centipawns for the classifier: this is the number a player reads on the
 * board ("+3"), and it must stay that way whatever the classifier's table says.
 *
 * Captured pieces are inferred, not replayed: a side's missing pieces are the starting set (8 pawns,
 * 2 knights, 2 bishops, 2 rooks, 1 queen) minus what is on the board. A promoted piece is on the board
 * beyond the starting count, so each extra knight/bishop/rook/queen is taken to be one pawn that is not
 * missing but promoted. The one case this cannot see is a promotion that replaces a captured piece of
 * the same kind (the board then looks untouched); the material total is right regardless.
 *
 * Pure `:core`: no clock, no engine, no I/O.
 */
data class MaterialBalance(
    /** Points White has on the board (pawn 1, knight 3, bishop 3, rook 5, queen 9). */
    val white: Int,
    /** Points Black has on the board. */
    val black: Int,
    /** Black's pieces that White has taken, most valuable first (queen, rook, bishop, knight, pawn). */
    val capturedByWhite: List<PieceType>,
    /** White's pieces that Black has taken, most valuable first. */
    val capturedByBlack: List<PieceType>,
) {
    /** White's points minus Black's: positive when White is ahead. */
    val difference: Int get() = white - black

    /** The side ahead on material, or null when it is level. */
    val ahead: Color?
        get() = when {
            difference > 0 -> Color.WHITE
            difference < 0 -> Color.BLACK
            else -> null
        }

    /** How many points [color] is ahead by, or 0 when it is level or behind (what "+N" shows). */
    fun advantage(color: Color): Int = when (color) {
        Color.WHITE -> difference.coerceAtLeast(0)
        Color.BLACK -> (-difference).coerceAtLeast(0)
    }

    /** The pieces [color] has taken from the other side. */
    fun capturedBy(color: Color): List<PieceType> = if (color == Color.WHITE) capturedByWhite else capturedByBlack

    companion object {
        /** The standard values; the king is not counted. */
        fun valueOf(type: PieceType): Int = when (type) {
            PieceType.PAWN -> 1
            PieceType.KNIGHT, PieceType.BISHOP -> 3
            PieceType.ROOK -> 5
            PieceType.QUEEN -> 9
            PieceType.KING -> 0
        }

        private val DISPLAY_ORDER =
            listOf(PieceType.QUEEN, PieceType.ROOK, PieceType.BISHOP, PieceType.KNIGHT, PieceType.PAWN)

        private val STARTING_COUNT = mapOf(
            PieceType.QUEEN to 1,
            PieceType.ROOK to 2,
            PieceType.BISHOP to 2,
            PieceType.KNIGHT to 2,
            PieceType.PAWN to 8,
        )

        /** The balance of [position]. */
        fun of(position: Position): MaterialBalance = fromFen(position.toFen())

        /**
         * The balance of a FEN (only its piece-placement field is read).
         * @throws IllegalArgumentException if the placement field is not a board of 8 ranks.
         */
        fun fromFen(fen: String): MaterialBalance {
            val placement = fen.trim().substringBefore(' ')
            val ranks = placement.split('/')
            require(ranks.size == 8) { "Not a FEN placement of 8 ranks: $fen" }
            val white = HashMap<PieceType, Int>()
            val black = HashMap<PieceType, Int>()
            for (rank in ranks) {
                for (c in rank) {
                    if (c.isDigit()) continue
                    val type = when (c.uppercaseChar()) {
                        'P' -> PieceType.PAWN
                        'N' -> PieceType.KNIGHT
                        'B' -> PieceType.BISHOP
                        'R' -> PieceType.ROOK
                        'Q' -> PieceType.QUEEN
                        'K' -> PieceType.KING
                        else -> throw IllegalArgumentException("Not a piece in FEN: '$c' in $fen")
                    }
                    val side = if (c.isUpperCase()) white else black
                    side[type] = (side[type] ?: 0) + 1
                }
            }
            return MaterialBalance(
                white = points(white),
                black = points(black),
                capturedByWhite = missing(black),
                capturedByBlack = missing(white),
            )
        }

        private fun points(onBoard: Map<PieceType, Int>): Int =
            onBoard.entries.sumOf { (type, count) -> valueOf(type) * count }

        /** What a side lost: the starting set minus the board, with promotions not counted as lost pawns. */
        private fun missing(onBoard: Map<PieceType, Int>): List<PieceType> {
            val promoted = DISPLAY_ORDER.filter { it != PieceType.PAWN }
                .sumOf { ((onBoard[it] ?: 0) - STARTING_COUNT.getValue(it)).coerceAtLeast(0) }
            val result = ArrayList<PieceType>()
            for (type in DISPLAY_ORDER) {
                var lost = (STARTING_COUNT.getValue(type) - (onBoard[type] ?: 0)).coerceAtLeast(0)
                if (type == PieceType.PAWN) lost = (lost - promoted).coerceAtLeast(0)
                repeat(lost) { result += type }
            }
            return result
        }
    }
}
