package net.palaya.chessanalyzer.core.chess

/** Side to move / piece color. */
enum class Color {
    WHITE, BLACK;

    fun opposite(): Color = if (this == WHITE) BLACK else WHITE
}

/** The six chess piece types, independent of color. */
enum class PieceType(val sanLetter: Char) {
    PAWN('P'), KNIGHT('N'), BISHOP('B'), ROOK('R'), QUEEN('Q'), KING('K')
}

/** A piece of a given type and color. */
data class Piece(val type: PieceType, val color: Color) {
    /** FEN-style character: uppercase for white, lowercase for black. */
    fun toFenChar(): Char =
        if (color == Color.WHITE) type.sanLetter else type.sanLetter.lowercaseChar()

    companion object {
        fun fromFenChar(c: Char): Piece {
            val color = if (c.isUpperCase()) Color.WHITE else Color.BLACK
            val type = when (c.uppercaseChar()) {
                'P' -> PieceType.PAWN
                'N' -> PieceType.KNIGHT
                'B' -> PieceType.BISHOP
                'R' -> PieceType.ROOK
                'Q' -> PieceType.QUEEN
                'K' -> PieceType.KING
                else -> throw IllegalArgumentException("Not a valid piece char: $c")
            }
            return Piece(type, color)
        }
    }
}

/**
 * A board square, stored as a 0..63 index where index = rank * 8 + file,
 * file 0..7 = a..h, rank 0..7 = rank 1..rank 8. So a1 = 0, h1 = 7, a8 = 56, h8 = 63.
 */
@JvmInline
value class Square(val index: Int) {

    val file: Int get() = index and 7
    val rank: Int get() = index shr 3

    val isValid: Boolean get() = index in 0..63

    /** Algebraic notation, e.g. "e4". */
    override fun toString(): String = "${('a' + file)}${rank + 1}"

    companion object {
        fun of(file: Int, rank: Int): Square = Square(rank * 8 + file)

        fun fromAlgebraic(s: String): Square {
            require(s.length == 2) { "Invalid square: $s" }
            val file = s[0].lowercaseChar() - 'a'
            val rank = s[1] - '1'
            require(file in 0..7 && rank in 0..7) { "Invalid square: $s" }
            return of(file, rank)
        }

        /** Returns null (rather than throwing) for a "-" FEN placeholder. */
        fun fromAlgebraicOrNull(s: String): Square? =
            if (s == "-") null else fromAlgebraic(s)
    }
}
