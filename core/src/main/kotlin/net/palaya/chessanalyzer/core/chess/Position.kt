package net.palaya.chessanalyzer.core.chess

/**
 * An immutable chess position: piece placement, side to move, castling rights,
 * en-passant target square, halfmove clock and fullmove number - i.e. everything
 * a FEN string encodes.
 *
 * The board is stored as a flat `IntArray(64)` of piece codes (see the codec
 * constants below) rather than `Array<Piece?>`, to avoid boxing during move
 * generation and perft, which is the hot path of this class.
 *
 * [makeMove] returns a *new* Position rather than mutating in place; copying a
 * 64-element IntArray is cheap (a single `arraycopy`), so this keeps the API
 * simple and safe without meaningfully hurting perft performance.
 *
 * Game-level concerns that span multiple positions - such as threefold
 * repetition - are deliberately kept out of this class; see [RepetitionTracker].
 */
class Position private constructor(
    internal val board: IntArray,
    val sideToMove: Color,
    internal val castlingRights: Int,
    val enPassantSquare: Square?,
    val halfmoveClock: Int,
    val fullmoveNumber: Int,
    val zobristKey: Long
) {
    companion object {
        // Piece codes stored in `board`. 0 = empty.
        internal const val WP = 1
        internal const val WN = 2
        internal const val WB = 3
        internal const val WR = 4
        internal const val WQ = 5
        internal const val WK = 6
        internal const val BP = 7
        internal const val BN = 8
        internal const val BB = 9
        internal const val BR = 10
        internal const val BQ = 11
        internal const val BK = 12

        internal const val CASTLE_WK = 1
        internal const val CASTLE_WQ = 2
        internal const val CASTLE_BK = 4
        internal const val CASTLE_BQ = 8

        const val STANDARD_START_FEN =
            "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"

        fun startPosition(): Position = fromFen(STANDARD_START_FEN)

        internal fun codeOf(piece: Piece): Int = when (piece.color) {
            Color.WHITE -> when (piece.type) {
                PieceType.PAWN -> WP; PieceType.KNIGHT -> WN; PieceType.BISHOP -> WB
                PieceType.ROOK -> WR; PieceType.QUEEN -> WQ; PieceType.KING -> WK
            }
            Color.BLACK -> when (piece.type) {
                PieceType.PAWN -> BP; PieceType.KNIGHT -> BN; PieceType.BISHOP -> BB
                PieceType.ROOK -> BR; PieceType.QUEEN -> BQ; PieceType.KING -> BK
            }
        }

        internal fun typeOfCode(code: Int): PieceType = when (code) {
            WP, BP -> PieceType.PAWN
            WN, BN -> PieceType.KNIGHT
            WB, BB -> PieceType.BISHOP
            WR, BR -> PieceType.ROOK
            WQ, BQ -> PieceType.QUEEN
            WK, BK -> PieceType.KING
            else -> throw IllegalStateException("Empty square has no piece type")
        }

        internal fun colorOfCode(code: Int): Color =
            if (code in WP..WK) Color.WHITE else Color.BLACK

        internal fun pieceOfCode(code: Int): Piece = Piece(typeOfCode(code), colorOfCode(code))

        /** Parses a full FEN string (all six fields) into a Position. */
        fun fromFen(fen: String): Position {
            val fields = fen.trim().split(Regex("\\s+"))
            require(fields.size >= 4) { "Invalid FEN (need at least 4 fields): $fen" }

            val board = IntArray(64)
            val ranks = fields[0].split("/")
            require(ranks.size == 8) { "Invalid FEN piece placement: ${fields[0]}" }
            for (rankIdx in 0..7) {
                // FEN ranks are listed 8 (top) down to 1 (bottom); board rank 0 = rank "1".
                val rank = 7 - rankIdx
                var file = 0
                for (c in ranks[rankIdx]) {
                    if (c.isDigit()) {
                        file += c.digitToInt()
                    } else {
                        require(file in 0..7) { "Invalid FEN rank overflow: ${ranks[rankIdx]}" }
                        board[Square.of(file, rank).index] = codeOf(Piece.fromFenChar(c))
                        file++
                    }
                }
                require(file == 8) { "Invalid FEN rank length: ${ranks[rankIdx]}" }
            }

            val sideToMove = when (fields[1]) {
                "w" -> Color.WHITE
                "b" -> Color.BLACK
                else -> throw IllegalArgumentException("Invalid FEN side to move: ${fields[1]}")
            }

            var castling = 0
            if (fields[2] != "-") {
                for (c in fields[2]) {
                    castling = castling or when (c) {
                        'K' -> CASTLE_WK
                        'Q' -> CASTLE_WQ
                        'k' -> CASTLE_BK
                        'q' -> CASTLE_BQ
                        else -> throw IllegalArgumentException("Invalid FEN castling field: ${fields[2]}")
                    }
                }
            }

            val ep = Square.fromAlgebraicOrNull(fields[3])

            val halfmove = if (fields.size > 4) fields[4].toIntOrNull() ?: 0 else 0
            val fullmove = if (fields.size > 5) fields[5].toIntOrNull() ?: 1 else 1

            val zobrist = computeZobristFromScratch(board, sideToMove, castling, ep)
            return Position(board, sideToMove, castling, ep, halfmove, fullmove, zobrist)
        }

        private fun computeZobristFromScratch(
            board: IntArray, sideToMove: Color, castling: Int, ep: Square?
        ): Long {
            var key = 0L
            for (sq in 0..63) {
                val code = board[sq]
                if (code != 0) key = key xor Zobrist.pieceKeys[code][sq]
            }
            if (sideToMove == Color.BLACK) key = key xor Zobrist.sideToMoveKey
            key = key xor Zobrist.castlingKey(castling)
            if (ep != null) key = key xor Zobrist.enPassantFileKeys[ep.file]
            return key
        }

        // --- Move-generation direction tables ---
        private val KNIGHT_DELTAS = arrayOf(1 to 2, 2 to 1, 2 to -1, 1 to -2, -1 to -2, -2 to -1, -2 to 1, -1 to 2)
        private val KING_DELTAS = arrayOf(1 to 0, 1 to 1, 0 to 1, -1 to 1, -1 to 0, -1 to -1, 0 to -1, 1 to -1)
        private val BISHOP_DIRS = arrayOf(1 to 1, 1 to -1, -1 to 1, -1 to -1)
        private val ROOK_DIRS = arrayOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)
        private val QUEEN_DIRS = BISHOP_DIRS + ROOK_DIRS
    }

    /** Piece at [square], or null if empty. */
    fun pieceAt(square: Square): Piece? {
        val code = board[square.index]
        return if (code == 0) null else pieceOfCode(code)
    }

    fun hasCastlingRight(color: Color, kingside: Boolean): Boolean {
        val bit = when {
            color == Color.WHITE && kingside -> CASTLE_WK
            color == Color.WHITE && !kingside -> CASTLE_WQ
            color == Color.BLACK && kingside -> CASTLE_BK
            else -> CASTLE_BQ
        }
        return (castlingRights and bit) != 0
    }

    fun kingSquare(color: Color): Square {
        val kingCode = if (color == Color.WHITE) WK else BK
        for (sq in 0..63) if (board[sq] == kingCode) return Square(sq)
        throw IllegalStateException("Position has no $color king")
    }

    fun isInCheck(color: Color = sideToMove): Boolean =
        isSquareAttacked(kingSquare(color).index, color.opposite())

    // ---------------------------------------------------------------------
    // Move generation
    // ---------------------------------------------------------------------

    /** All strictly legal moves for [sideToMove] (pseudo-legal moves filtered by king safety). */
    fun legalMoves(): List<Move> {
        val pseudo = pseudoLegalMoves()
        val legal = ArrayList<Move>(pseudo.size)
        val mover = sideToMove
        for (m in pseudo) {
            val next = makeMove(m)
            if (!next.isSquareAttacked(next.kingSquare(mover).index, mover.opposite())) {
                legal.add(m)
            }
        }
        return legal
    }

    private fun pseudoLegalMoves(): List<Move> {
        val moves = ArrayList<Move>(48)
        val color = sideToMove
        for (sq in 0..63) {
            val code = board[sq]
            if (code == 0 || colorOfCode(code) != color) continue
            when (typeOfCode(code)) {
                PieceType.PAWN -> generatePawnMoves(sq, color, moves)
                PieceType.KNIGHT -> generateLeaperMoves(sq, color, PieceType.KNIGHT, KNIGHT_DELTAS, moves)
                PieceType.BISHOP -> generateSliderMoves(sq, color, PieceType.BISHOP, BISHOP_DIRS, moves)
                PieceType.ROOK -> generateSliderMoves(sq, color, PieceType.ROOK, ROOK_DIRS, moves)
                PieceType.QUEEN -> generateSliderMoves(sq, color, PieceType.QUEEN, QUEEN_DIRS, moves)
                PieceType.KING -> generateLeaperMoves(sq, color, PieceType.KING, KING_DELTAS, moves)
            }
        }
        generateCastlingMoves(color, moves)
        return moves
    }

    private fun generateLeaperMoves(
        from: Int, color: Color, type: PieceType, deltas: Array<Pair<Int, Int>>, out: MutableList<Move>
    ) {
        val file = from and 7
        val rank = from shr 3
        for ((df, dr) in deltas) {
            val f = file + df
            val r = rank + dr
            if (f !in 0..7 || r !in 0..7) continue
            val to = r * 8 + f
            val target = board[to]
            if (target == 0) {
                out.add(Move(Square(from), Square(to), type, color))
            } else if (colorOfCode(target) != color) {
                out.add(Move(Square(from), Square(to), type, color, isCapture = true, capturedPiece = typeOfCode(target)))
            }
        }
    }

    private fun generateSliderMoves(
        from: Int, color: Color, type: PieceType, dirs: Array<Pair<Int, Int>>, out: MutableList<Move>
    ) {
        val file0 = from and 7
        val rank0 = from shr 3
        for ((df, dr) in dirs) {
            var f = file0 + df
            var r = rank0 + dr
            while (f in 0..7 && r in 0..7) {
                val to = r * 8 + f
                val target = board[to]
                if (target == 0) {
                    out.add(Move(Square(from), Square(to), type, color))
                } else {
                    if (colorOfCode(target) != color) {
                        out.add(Move(Square(from), Square(to), type, color, isCapture = true, capturedPiece = typeOfCode(target)))
                    }
                    break
                }
                f += df
                r += dr
            }
        }
    }

    private fun generatePawnMoves(from: Int, color: Color, out: MutableList<Move>) {
        val file = from and 7
        val rank = from shr 3
        val forward = if (color == Color.WHITE) 1 else -1
        val startRank = if (color == Color.WHITE) 1 else 6
        val promoRank = if (color == Color.WHITE) 7 else 0

        fun addPawnMove(to: Int, isCapture: Boolean, captured: PieceType?, isEp: Boolean) {
            val toRank = to shr 3
            if (toRank == promoRank) {
                for (promo in arrayOf(PieceType.QUEEN, PieceType.ROOK, PieceType.BISHOP, PieceType.KNIGHT)) {
                    out.add(
                        Move(
                            Square(from), Square(to), PieceType.PAWN, color,
                            promotion = promo, isCapture = isCapture, capturedPiece = captured, isEnPassant = isEp
                        )
                    )
                }
            } else {
                out.add(
                    Move(
                        Square(from), Square(to), PieceType.PAWN, color,
                        isCapture = isCapture, capturedPiece = captured, isEnPassant = isEp
                    )
                )
            }
        }

        // Single push
        val oneRank = rank + forward
        if (oneRank in 0..7) {
            val one = oneRank * 8 + file
            if (board[one] == 0) {
                addPawnMove(one, isCapture = false, captured = null, isEp = false)
                // Double push
                if (rank == startRank) {
                    val twoRank = rank + 2 * forward
                    val two = twoRank * 8 + file
                    if (board[two] == 0) {
                        out.add(
                            Move(
                                Square(from), Square(two), PieceType.PAWN, color,
                                isDoublePawnPush = true
                            )
                        )
                    }
                }
            }
        }

        // Captures (including en passant)
        for (df in intArrayOf(-1, 1)) {
            val f = file + df
            if (f !in 0..7 || oneRank !in 0..7) continue
            val to = oneRank * 8 + f
            val target = board[to]
            if (target != 0 && colorOfCode(target) != color) {
                addPawnMove(to, isCapture = true, captured = typeOfCode(target), isEp = false)
            } else if (target == 0 && enPassantSquare != null && enPassantSquare.index == to) {
                addPawnMove(to, isCapture = true, captured = PieceType.PAWN, isEp = true)
            }
        }
    }

    private fun generateCastlingMoves(color: Color, out: MutableList<Move>) {
        val rank = if (color == Color.WHITE) 0 else 7
        val kingFrom = Square.of(4, rank).index
        if (board[kingFrom] != (if (color == Color.WHITE) WK else BK)) return
        val opp = color.opposite()

        // Kingside
        if (hasCastlingRight(color, kingside = true)) {
            val f = Square.of(5, rank).index
            val g = Square.of(6, rank).index
            val h = Square.of(7, rank).index
            if (board[f] == 0 && board[g] == 0 && board[h] == (if (color == Color.WHITE) WR else BR)) {
                if (!isSquareAttacked(kingFrom, opp) && !isSquareAttacked(f, opp) && !isSquareAttacked(g, opp)) {
                    out.add(Move(Square(kingFrom), Square(g), PieceType.KING, color, isCastleKingside = true))
                }
            }
        }
        // Queenside
        if (hasCastlingRight(color, kingside = false)) {
            val d = Square.of(3, rank).index
            val c = Square.of(2, rank).index
            val b = Square.of(1, rank).index
            val a = Square.of(0, rank).index
            if (board[d] == 0 && board[c] == 0 && board[b] == 0 && board[a] == (if (color == Color.WHITE) WR else BR)) {
                if (!isSquareAttacked(kingFrom, opp) && !isSquareAttacked(d, opp) && !isSquareAttacked(c, opp)) {
                    out.add(Move(Square(kingFrom), Square(c), PieceType.KING, color, isCastleQueenside = true))
                }
            }
        }
    }

    /** Whether [square] is attacked by any piece of [byColor], in the current board. */
    internal fun isSquareAttacked(square: Int, byColor: Color): Boolean {
        val file = square and 7
        val rank = square shr 3

        // Pawn attacks: a byColor pawn attacks `square` if it sits one rank behind
        // (from that pawn's perspective) and one file to either side.
        val pawnRank = rank - (if (byColor == Color.WHITE) 1 else -1)
        if (pawnRank in 0..7) {
            val pawnCode = if (byColor == Color.WHITE) WP else BP
            for (df in intArrayOf(-1, 1)) {
                val f = file + df
                if (f in 0..7 && board[pawnRank * 8 + f] == pawnCode) return true
            }
        }

        // Knight attacks
        val knightCode = if (byColor == Color.WHITE) WN else BN
        for ((df, dr) in KNIGHT_DELTAS) {
            val f = file + df
            val r = rank + dr
            if (f in 0..7 && r in 0..7 && board[r * 8 + f] == knightCode) return true
        }

        // King attacks (adjacency)
        val kingCode = if (byColor == Color.WHITE) WK else BK
        for ((df, dr) in KING_DELTAS) {
            val f = file + df
            val r = rank + dr
            if (f in 0..7 && r in 0..7 && board[r * 8 + f] == kingCode) return true
        }

        // Sliding attacks: bishops/queens on diagonals, rooks/queens on files/ranks
        val bishopLike = if (byColor == Color.WHITE) intArrayOf(WB, WQ) else intArrayOf(BB, BQ)
        for ((df, dr) in BISHOP_DIRS) {
            var f = file + df
            var r = rank + dr
            while (f in 0..7 && r in 0..7) {
                val code = board[r * 8 + f]
                if (code != 0) {
                    if (code == bishopLike[0] || code == bishopLike[1]) return true
                    break
                }
                f += df
                r += dr
            }
        }
        val rookLike = if (byColor == Color.WHITE) intArrayOf(WR, WQ) else intArrayOf(BR, BQ)
        for ((df, dr) in ROOK_DIRS) {
            var f = file + df
            var r = rank + dr
            while (f in 0..7 && r in 0..7) {
                val code = board[r * 8 + f]
                if (code != 0) {
                    if (code == rookLike[0] || code == rookLike[1]) return true
                    break
                }
                f += df
                r += dr
            }
        }

        return false
    }

    // ---------------------------------------------------------------------
    // Making moves
    // ---------------------------------------------------------------------

    /**
     * Applies [move] and returns the resulting position. Does not itself verify
     * legality (that's [legalMoves]'s job); it trusts from/to/promotion/flags and
     * derives capture/rook-move bookkeeping from the actual board contents, so it
     * is safe to call with any [Move] whose from/to/flags are internally consistent.
     */
    fun makeMove(move: Move): Position {
        val newBoard = board.copyOf()
        var key = zobristKey

        val fromIdx = move.from.index
        val toIdx = move.to.index
        val movingCode = board[fromIdx]
        val color = colorOfCode(movingCode)

        // Remove moving piece from source.
        newBoard[fromIdx] = 0
        key = key xor Zobrist.pieceKeys[movingCode][fromIdx]

        // Handle capture (en passant captures a pawn NOT on the destination square).
        if (move.isEnPassant) {
            val capturedSq = Square.of(move.to.file, move.from.rank).index
            val capturedCode = newBoard[capturedSq]
            if (capturedCode != 0) {
                newBoard[capturedSq] = 0
                key = key xor Zobrist.pieceKeys[capturedCode][capturedSq]
            }
        } else {
            val capturedCode = newBoard[toIdx]
            if (capturedCode != 0) {
                key = key xor Zobrist.pieceKeys[capturedCode][toIdx]
            }
        }

        // Place moving piece (promoted, if applicable) on destination.
        val placedCode = if (move.promotion != null) codeOf(Piece(move.promotion, color)) else movingCode
        newBoard[toIdx] = placedCode
        key = key xor Zobrist.pieceKeys[placedCode][toIdx]

        // Move the rook on castling.
        if (move.isCastleKingside || move.isCastleQueenside) {
            val rank = move.from.rank
            val rookFromFile = if (move.isCastleKingside) 7 else 0
            val rookToFile = if (move.isCastleKingside) 5 else 3
            val rookFrom = Square.of(rookFromFile, rank).index
            val rookTo = Square.of(rookToFile, rank).index
            val rookCode = newBoard[rookFrom]
            newBoard[rookFrom] = 0
            newBoard[rookTo] = rookCode
            key = key xor Zobrist.pieceKeys[rookCode][rookFrom] xor Zobrist.pieceKeys[rookCode][rookTo]
        }

        // Update castling rights: king moves clear both; rook moves/captures on the
        // original rook squares clear that specific right.
        var newCastling = castlingRights
        when (movingCode) {
            WK -> newCastling = newCastling and (CASTLE_WK or CASTLE_WQ).inv()
            BK -> newCastling = newCastling and (CASTLE_BK or CASTLE_BQ).inv()
        }
        if (fromIdx == 0 || toIdx == 0) newCastling = newCastling and CASTLE_WQ.inv() // a1
        if (fromIdx == 7 || toIdx == 7) newCastling = newCastling and CASTLE_WK.inv() // h1
        if (fromIdx == 56 || toIdx == 56) newCastling = newCastling and CASTLE_BQ.inv() // a8
        if (fromIdx == 63 || toIdx == 63) newCastling = newCastling and CASTLE_BK.inv() // h8
        key = key xor Zobrist.castlingKey(castlingRights) xor Zobrist.castlingKey(newCastling)

        // Update en passant target.
        val newEp = if (move.isDoublePawnPush) {
            Square.of(move.from.file, (move.from.rank + move.to.rank) / 2)
        } else null
        if (enPassantSquare != null) key = key xor Zobrist.enPassantFileKeys[enPassantSquare.file]
        if (newEp != null) key = key xor Zobrist.enPassantFileKeys[newEp.file]

        // Halfmove clock: reset on pawn move or capture.
        val isCapture = move.isCapture || move.isEnPassant || board[toIdx] != 0
        val newHalfmove = if (movingCode == WP || movingCode == BP || isCapture) 0 else halfmoveClock + 1

        val newFullmove = if (color == Color.BLACK) fullmoveNumber + 1 else fullmoveNumber

        key = key xor Zobrist.sideToMoveKey

        return Position(newBoard, color.opposite(), newCastling, newEp, newHalfmove, newFullmove, key)
    }

    // ---------------------------------------------------------------------
    // Game-end / draw conditions
    // ---------------------------------------------------------------------

    fun isCheckmate(): Boolean = isInCheck() && legalMoves().isEmpty()

    fun isStalemate(): Boolean = !isInCheck() && legalMoves().isEmpty()

    fun isFiftyMoveRule(): Boolean = halfmoveClock >= 100

    /** Standard "dead position" approximation: KvK, KvK+minor, or KB vs KB same-color bishops. */
    fun isInsufficientMaterial(): Boolean {
        val whitePieces = ArrayList<Int>()
        val blackPieces = ArrayList<Int>()
        for (code in board) {
            if (code == 0) continue
            when (code) {
                WK, BK -> {}
                WP, BP, WR, BR, WQ, BQ -> return false
                else -> if (colorOfCode(code) == Color.WHITE) whitePieces.add(code) else blackPieces.add(code)
            }
        }
        if (whitePieces.isEmpty() && blackPieces.isEmpty()) return true
        if (whitePieces.size == 1 && blackPieces.isEmpty()) return true
        if (blackPieces.size == 1 && whitePieces.isEmpty()) return true
        if (whitePieces.size == 1 && blackPieces.size == 1 &&
            whitePieces[0] == WB && blackPieces[0] == BB
        ) {
            val whiteBishopSq = (0..63).first { board[it] == WB }
            val blackBishopSq = (0..63).first { board[it] == BB }
            val whiteSqColor = (whiteBishopSq.let { (it and 7) + (it shr 3) }) % 2
            val blackSqColor = (blackBishopSq.let { (it and 7) + (it shr 3) }) % 2
            return whiteSqColor == blackSqColor
        }
        return false
    }

    /** True if the game is over for any FIDE-style reason detectable from this position alone. */
    fun isGameOver(): Boolean = isCheckmate() || isStalemate() || isFiftyMoveRule() || isInsufficientMaterial()

    // ---------------------------------------------------------------------
    // FEN serialization
    // ---------------------------------------------------------------------

    fun toFen(): String {
        val sb = StringBuilder()
        for (rankIdx in 0..7) {
            val rank = 7 - rankIdx
            var empty = 0
            for (file in 0..7) {
                val code = board[Square.of(file, rank).index]
                if (code == 0) {
                    empty++
                } else {
                    if (empty > 0) {
                        sb.append(empty)
                        empty = 0
                    }
                    sb.append(pieceOfCode(code).toFenChar())
                }
            }
            if (empty > 0) sb.append(empty)
            if (rankIdx != 7) sb.append('/')
        }
        sb.append(' ').append(if (sideToMove == Color.WHITE) 'w' else 'b')
        sb.append(' ')
        if (castlingRights == 0) {
            sb.append('-')
        } else {
            if (castlingRights and CASTLE_WK != 0) sb.append('K')
            if (castlingRights and CASTLE_WQ != 0) sb.append('Q')
            if (castlingRights and CASTLE_BK != 0) sb.append('k')
            if (castlingRights and CASTLE_BQ != 0) sb.append('q')
        }
        sb.append(' ').append(enPassantSquare?.toString() ?: "-")
        sb.append(' ').append(halfmoveClock)
        sb.append(' ').append(fullmoveNumber)
        return sb.toString()
    }

    override fun toString(): String = toFen()

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Position) return false
        return sideToMove == other.sideToMove &&
            castlingRights == other.castlingRights &&
            enPassantSquare == other.enPassantSquare &&
            halfmoveClock == other.halfmoveClock &&
            fullmoveNumber == other.fullmoveNumber &&
            board.contentEquals(other.board)
    }

    override fun hashCode(): Int = zobristKey.hashCode()
}
