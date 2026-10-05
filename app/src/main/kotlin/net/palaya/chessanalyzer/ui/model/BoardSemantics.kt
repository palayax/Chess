package net.palaya.chessanalyzer.ui.model

/*
 * Pure ordering behind the board's TalkBack description (U10): which pieces are listed, and in what
 * order. The words come from string resources (`cd_*`, `piece_*`, `side_*`); only the order lives
 * here so it has a host test.
 */

/** How pieces are listed aloud: king first, pawns last (the order a player would read a position). */
private val SPOKEN_ORDER = listOf(
    PieceType.KING, PieceType.QUEEN, PieceType.ROOK, PieceType.BISHOP, PieceType.KNIGHT, PieceType.PAWN,
)

/**
 * The pieces of [color] in reading order: by piece type (king, queen, rook, bishop, knight, pawn),
 * then by file (a to h), then by rank (1 to 8). Stable and independent of the board's orientation,
 * so a flipped board is described exactly like an unflipped one.
 */
fun piecesInSpokenOrder(board: BoardState, color: PieceColor): List<Pair<Square, Piece>> =
    board.pieces.entries
        .filter { it.value.color == color }
        .map { it.key to it.value }
        .sortedWith(
            compareBy<Pair<Square, Piece>>(
                { SPOKEN_ORDER.indexOf(it.second.type) },
                { it.first.file() },
                { -it.first.rankFromTop() },
            ),
        )
