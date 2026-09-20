package net.palaya.chessanalyzer.core.chess

/**
 * SAN (Standard Algebraic Notation) and UCI long-algebraic parsing/serialization.
 *
 * Parsing always resolves against a [Position]: the resulting [Move] is guaranteed
 * to be one of that position's [Position.legalMoves] (never a "trust the string"
 * pseudo-move), so downstream code (e.g. the PGN parser) can rely on parsed moves
 * being legal.
 */

private val ANNOTATION_SUFFIX = Regex("[!?]+$")

private val SAN_REGEX = Regex(
    "^([NBRQK])?([a-h])?([1-8])?(x)?([a-h][1-8])(=([NBRQ]))?[+#]?$"
)

/** Strips trailing NAG-style annotation glyphs (!, ?, !!, ??, !?, ?!) from a SAN token. */
fun stripSanAnnotations(san: String): String = san.trim().replace(ANNOTATION_SUFFIX, "")

private fun pieceTypeFromLetter(letter: Char): PieceType = when (letter) {
    'N' -> PieceType.KNIGHT
    'B' -> PieceType.BISHOP
    'R' -> PieceType.ROOK
    'Q' -> PieceType.QUEEN
    'K' -> PieceType.KING
    else -> throw IllegalArgumentException("Unknown piece letter: $letter")
}

/**
 * Parses [sanInput] (optionally with trailing annotation glyphs and/or a +/# suffix)
 * into the unique matching legal [Move] in this position.
 *
 * @throws SanParseException if the text isn't valid SAN, or doesn't match exactly
 *   one legal move in this position.
 */
fun Position.parseSan(sanInput: String): Move {
    val san = stripSanAnnotations(sanInput)

    if (san == "O-O" || san == "0-0") {
        return legalMoves().firstOrNull { it.isCastleKingside }
            ?: throw SanParseException("Illegal move (no legal kingside castle): $sanInput")
    }
    if (san == "O-O-O" || san == "0-0-0") {
        return legalMoves().firstOrNull { it.isCastleQueenside }
            ?: throw SanParseException("Illegal move (no legal queenside castle): $sanInput")
    }

    val match = SAN_REGEX.matchEntire(san)
        ?: throw SanParseException("Cannot parse SAN move: $sanInput")
    val (pieceLetter, disambFile, disambRank, captureFlag, destStr, _, promoLetter) = match.destructured

    val pieceType = if (pieceLetter.isEmpty()) PieceType.PAWN else pieceTypeFromLetter(pieceLetter[0])
    val dest = Square.fromAlgebraic(destStr)
    val promotion = if (promoLetter.isEmpty()) null else pieceTypeFromLetter(promoLetter[0])

    val candidates = legalMoves().filter { move ->
        move.piece == pieceType &&
            move.to == dest &&
            move.promotion == promotion &&
            (disambFile.isEmpty() || move.from.file == disambFile[0] - 'a') &&
            (disambRank.isEmpty() || move.from.rank == disambRank[0] - '1') &&
            (captureFlag.isEmpty() || move.isCapture)
    }

    return when {
        candidates.size == 1 -> candidates[0]
        candidates.isEmpty() -> throw SanParseException("Illegal or unrecognized move: $sanInput")
        else -> throw SanParseException("Ambiguous SAN move (matches ${candidates.size} legal moves): $sanInput")
    }
}

/** Serializes [move] (assumed legal in this position) to SAN, including +/# suffix. */
fun Position.moveToSan(move: Move): String {
    val base = when {
        move.isCastleKingside -> "O-O"
        move.isCastleQueenside -> "O-O-O"
        move.piece == PieceType.PAWN -> buildPawnSan(move)
        else -> buildPieceSan(move)
    }
    val next = makeMove(move)
    return when {
        next.isCheckmate() -> "$base#"
        next.isInCheck() -> "$base+"
        else -> base
    }
}

private fun Position.buildPawnSan(move: Move): String {
    val sb = StringBuilder()
    if (move.isCapture) {
        sb.append('a' + move.from.file)
        sb.append('x')
    }
    sb.append(move.to.toString())
    if (move.promotion != null) sb.append('=').append(move.promotion.sanLetter)
    return sb.toString()
}

private fun Position.buildPieceSan(move: Move): String {
    val sb = StringBuilder()
    sb.append(move.piece.sanLetter)

    val others = legalMoves().filter {
        it.piece == move.piece && it.to == move.to && it.from != move.from
    }
    if (others.isNotEmpty()) {
        val sameFile = others.any { it.from.file == move.from.file }
        val sameRank = others.any { it.from.rank == move.from.rank }
        when {
            !sameFile -> sb.append('a' + move.from.file)
            !sameRank -> sb.append(move.from.rank + 1)
            else -> {
                sb.append('a' + move.from.file)
                sb.append(move.from.rank + 1)
            }
        }
    }
    if (move.isCapture) sb.append('x')
    sb.append(move.to.toString())
    return sb.toString()
}

/**
 * Parses a UCI long-algebraic move (e.g. "e2e4", "e7e8q") into the matching legal
 * [Move] in this position.
 */
fun Position.parseUci(uci: String): Move {
    val trimmed = uci.trim()
    require(trimmed.length == 4 || trimmed.length == 5) { "Invalid UCI move: $uci" }
    val from = Square.fromAlgebraic(trimmed.substring(0, 2))
    val to = Square.fromAlgebraic(trimmed.substring(2, 4))
    val promotion = if (trimmed.length == 5) pieceTypeFromLetter(trimmed[4].uppercaseChar()) else null

    return legalMoves().firstOrNull { it.from == from && it.to == to && it.promotion == promotion }
        ?: throw SanParseException("Illegal or unrecognized UCI move: $uci")
}

class SanParseException(message: String) : Exception(message)
