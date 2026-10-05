package net.palaya.chessanalyzer.ui.board

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import net.palaya.chessanalyzer.R
import net.palaya.chessanalyzer.ui.model.PieceType
import net.palaya.chessanalyzer.ui.model.algebraic
import net.palaya.chessanalyzer.ui.model.piecesInSpokenOrder
import net.palaya.chessanalyzer.ui.theme.BoardLabelOnDark
import net.palaya.chessanalyzer.ui.theme.BoardLabelOnLight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import net.palaya.chessanalyzer.ui.model.BoardState
import net.palaya.chessanalyzer.ui.model.Piece
import net.palaya.chessanalyzer.ui.model.PieceColor
import net.palaya.chessanalyzer.ui.model.Square
import net.palaya.chessanalyzer.ui.model.file
import net.palaya.chessanalyzer.ui.model.rankFromTop
import net.palaya.chessanalyzer.ui.model.squareOf
import net.palaya.chessanalyzer.ui.theme.BoardDark
import net.palaya.chessanalyzer.ui.theme.ClassificationBadge
import net.palaya.chessanalyzer.ui.theme.MoveClassification
import net.palaya.chessanalyzer.ui.theme.BoardLight
import net.palaya.chessanalyzer.ui.theme.CheckRed
import net.palaya.chessanalyzer.ui.theme.GreenPrimary
import net.palaya.chessanalyzer.ui.theme.HighlightYellowAlpha
import net.palaya.chessanalyzer.ui.theme.LastMoveAlpha
import net.palaya.chessanalyzer.ui.theme.LegalMoveDot
import net.palaya.chessanalyzer.ui.theme.SelectedSquare
import androidx.compose.ui.unit.min as dpMin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

enum class BoardOrientation { WHITE_DOWN, BLACK_DOWN }

/** An engine-move / missed-tactic arrow overlay drawn on the board. */
data class BoardArrow(
    val from: Square,
    val to: Square,
    val color: Color = GreenPrimary,
)

/**
 * The classification badge of the move on the board (B1), drawn in the top-right corner of its
 * destination [square]. [contentDescription] is what TalkBack says for it ("Blunder on f3"); the
 * board itself has no other per-square semantics.
 */
data class BoardBadge(
    val square: Square,
    val classification: MoveClassification,
    val contentDescription: String,
)

/** The badge's side as a fraction of a square (B1: about 28%). */
const val BOARD_BADGE_FRACTION = 0.28f

/**
 * Renders an 8x8 chess board from a [BoardState] placeholder.
 *
 * Pieces are drawn as original vector silhouettes (see [PieceGeometry]) filled and
 * outlined directly on the `Canvas` — no bitmaps, no Unicode glyphs. Each piece type's
 * [Path][androidx.compose.ui.graphics.Path] is built once and cached, then scaled/
 * translated per square, so they stay crisp at any board size and cost nothing extra
 * to redraw on every move step. Everything else — squares, highlights, legal-move
 * dots, arrows, coordinates — is drawn with Compose `Canvas` paths as before.
 *
 * The board is always square, sized to `min(availableWidth, availableHeight)`, so it
 * behaves in both portrait (full width) and landscape (full height) layouts.
 */
@Composable
fun ChessBoard(
    board: BoardState,
    modifier: Modifier = Modifier,
    orientation: BoardOrientation = BoardOrientation.WHITE_DOWN,
    lastMove: Pair<Square, Square>? = null,
    checkedKingSquare: Square? = null,
    selectedSquare: Square? = null,
    legalMoveTargets: Set<Square> = emptySet(),
    arrows: List<BoardArrow> = emptyList(),
    showCoordinates: Boolean = true,
    badge: BoardBadge? = null,
    onSquareClick: ((Square) -> Unit)? = null,
) {
    // A chessboard is not a text layout: a1 stays bottom-left for a Hebrew reader exactly as for
    // an English one (a mirrored board is a *different position*), so the board and everything
    // drawn on it — coordinates, arrows, tap mapping — are pinned to LTR regardless of the app's
    // layout direction. The Canvas below draws in absolute coordinates and would not mirror by
    // itself; pinning it makes the intent explicit and protects any Row/Column added here later.
    // The tap handler below is installed once per (orientation, size) key, so it must read the
    // *current* callback through this state, not the one captured when it was installed. Without it a
    // caller whose lambda closes over per-puzzle values (the Practise screen) kept calling the first
    // lambda forever, which showed up the first time a screen used the tap path (R3).
    val currentOnSquareClick by rememberUpdatedState(onSquareClick)
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
    BoxWithConstraints(modifier = modifier) {
        val sizeDp = dpMin(maxWidth, maxHeight)
        val context = LocalContext.current
        val configuration = LocalConfiguration.current
        // Read once per position, not per frame: the summary names all (up to 32) pieces.
        val summary = remember(board, configuration) { boardSummary(context, board) }
        val interactive = onSquareClick != null
        Box(
            modifier = Modifier
                .size(sizeDp)
                // Read-only board: one TalkBack stop that lists the position. Interactive board
                // (Practise): the squares below are the stops, so this node only names the board.
                .semantics {
                    contentDescription = if (interactive) context.getString(R.string.cd_board_name) else summary
                }
                .then(
                    if (onSquareClick != null) {
                        Modifier.pointerInput(orientation, sizeDp) {
                            detectTapGestures { offset ->
                                val square = squareAtOffset(offset.x, offset.y, size.width.toFloat(), orientation)
                                currentOnSquareClick?.invoke(square)
                            }
                        }
                    } else Modifier,
                ),
        ) {
            BoardCanvas(
                board = board,
                orientation = orientation,
                lastMove = lastMove,
                checkedKingSquare = checkedKingSquare,
                selectedSquare = selectedSquare,
                legalMoveTargets = legalMoveTargets,
                arrows = arrows,
                showCoordinates = showCoordinates,
            )
            if (interactive) {
                BoardSquareNodes(
                    board = board,
                    orientation = orientation,
                    selectedSquare = selectedSquare,
                    legalMoveTargets = legalMoveTargets,
                    onSquareClick = { square -> currentOnSquareClick?.invoke(square) },
                )
            }
            if (badge != null) {
                BoardBadgeOverlay(badge = badge, orientation = orientation, squareSize = sizeDp / 8)
            }
        }
    }
    }
}

/**
 * "Chess board. White: king e1, queen d1, ... Black: king e8, ..." for TalkBack. The board is a
 * Canvas, so without this it is an unlabelled blank to a screen reader.
 */
private fun boardSummary(context: android.content.Context, board: BoardState): String {
    fun listFor(color: PieceColor): String = piecesInSpokenOrder(board, color).joinToString(", ") { (square, piece) ->
        context.getString(R.string.cd_piece_short, context.getString(pieceNameRes(piece.type)), square.algebraic())
    }.ifEmpty { context.getString(R.string.cd_board_none) }
    return context.getString(R.string.cd_board_summary, listFor(PieceColor.WHITE), listFor(PieceColor.BLACK))
}

private fun pieceNameRes(type: PieceType): Int = when (type) {
    PieceType.PAWN -> R.string.piece_pawn
    PieceType.KNIGHT -> R.string.piece_knight
    PieceType.BISHOP -> R.string.piece_bishop
    PieceType.ROOK -> R.string.piece_rook
    PieceType.QUEEN -> R.string.piece_queen
    PieceType.KING -> R.string.piece_king
}

/**
 * The 64 squares of an interactive board as transparent, individually focusable nodes ("White knight
 * on f3", "Square e4", "selected", "possible move"), laid out in display order and activated by
 * TalkBack's double tap. They draw nothing and take no pointer input, so touch play is unchanged.
 */
@Composable
private fun BoardSquareNodes(
    board: BoardState,
    orientation: BoardOrientation,
    selectedSquare: Square?,
    legalMoveTargets: Set<Square>,
    onSquareClick: (Square) -> Unit,
) {
    val context = LocalContext.current
    val selectedText = context.getString(R.string.cd_square_selected)
    val targetText = context.getString(R.string.cd_square_target)
    Column(modifier = Modifier.fillMaxSize()) {
        for (row in 0..7) {
            Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                for (col in 0..7) {
                    val square = displayCellToSquare(col, row, orientation)
                    val piece = board.pieces[square]
                    val label = if (piece == null) {
                        context.getString(R.string.cd_board_square, square.algebraic())
                    } else {
                        context.getString(
                            R.string.cd_piece,
                            context.getString(if (piece.color == PieceColor.WHITE) R.string.side_white else R.string.side_black),
                            context.getString(pieceNameRes(piece.type)),
                            square.algebraic(),
                        )
                    }
                    val state = when {
                        square == selectedSquare -> selectedText
                        square in legalMoveTargets -> targetText
                        else -> null
                    }
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .semantics {
                                contentDescription = label
                                role = Role.Button
                                if (state != null) stateDescription = state
                                onClick { onSquareClick(square); true }
                            },
                    )
                }
            }
        }
    }
}

/**
 * The badge in the top-right corner of its square. It is placed by the same display-cell mapping the
 * pieces use, so flipping the board moves it with the square, and it lives inside the board's
 * LTR-pinned subtree, so RTL never moves it. Its glyph is sized in sp, so a large system font would
 * overflow an 11 dp circle: the overlay pins the font scale to 1 (the board is not text, and the
 * colour plus the content description carry the meaning).
 */
@Composable
private fun BoardBadgeOverlay(badge: BoardBadge, orientation: BoardOrientation, squareSize: Dp) {
    val (col, row) = squareToDisplayCell(badge.square, orientation)
    val size = squareSize * BOARD_BADGE_FRACTION
    val inset = squareSize * 0.03f
    val density = LocalDensity.current
    CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 1f)) {
        Box(
            modifier = Modifier
                .offset(x = squareSize * (col + 1) - size - inset, y = squareSize * row + inset)
                .clearAndSetSemantics { contentDescription = badge.contentDescription },
        ) {
            ClassificationBadge(classification = badge.classification, size = size)
        }
    }
}

/**
 * The UI square under a tap at ([x], [y]) pixels on a board [boardSizePx] wide, for [orientation].
 * Pure, so the flipped-board mapping has a host test. A tap on the edge is clamped onto the board.
 */
internal fun squareAtOffset(x: Float, y: Float, boardSizePx: Float, orientation: BoardOrientation): Square {
    val squareSizePx = boardSizePx / 8f
    val col = (x / squareSizePx).toInt().coerceIn(0, 7)
    val row = (y / squareSizePx).toInt().coerceIn(0, 7)
    return displayCellToSquare(col, row, orientation)
}

private fun displayCellToSquare(col: Int, row: Int, orientation: BoardOrientation): Square =
    if (orientation == BoardOrientation.WHITE_DOWN) squareOf(col, row)
    else squareOf(7 - col, 7 - row)

internal fun squareToDisplayCell(square: Square, orientation: BoardOrientation): Pair<Int, Int> {
    val file = square.file()
    val rankFromTop = square.rankFromTop()
    return if (orientation == BoardOrientation.WHITE_DOWN) file to rankFromTop
    else (7 - file) to (7 - rankFromTop)
}

@Composable
private fun BoardCanvas(
    board: BoardState,
    orientation: BoardOrientation,
    lastMove: Pair<Square, Square>?,
    checkedKingSquare: Square?,
    selectedSquare: Square?,
    legalMoveTargets: Set<Square>,
    arrows: List<BoardArrow>,
    showCoordinates: Boolean,
) {
    // Ink on each square colour (AA: 6.4:1 on light squares, 5.1:1 on dark; the old green-on-cream
    // and cream-on-green pair gave 2.8:1).
    val coordPaintLight = remember { labelPaint(BoardLabelOnLight) }
    val coordPaintDark = remember { labelPaint(BoardLabelOnDark) }

    Canvas(modifier = Modifier.fillMaxSize()) {
        val squareSize = size.width / 8f

        // 1. Base squares
        for (row in 0..7) {
            for (col in 0..7) {
                val isLight = (row + col) % 2 == 0
                drawRect(
                    color = if (isLight) BoardLight else BoardDark,
                    topLeft = Offset(col * squareSize, row * squareSize),
                    size = androidx.compose.ui.geometry.Size(squareSize, squareSize),
                )
            }
        }

        // 2. Last move highlight
        lastMove?.let { (from, to) ->
            listOf(from, to).forEach { sq ->
                val (c, r) = squareToDisplayCell(sq, orientation)
                drawRect(
                    color = LastMoveAlpha,
                    topLeft = Offset(c * squareSize, r * squareSize),
                    size = androidx.compose.ui.geometry.Size(squareSize, squareSize),
                )
            }
        }

        // 3. Selected square highlight
        selectedSquare?.let { sq ->
            val (c, r) = squareToDisplayCell(sq, orientation)
            drawRect(
                color = SelectedSquare,
                topLeft = Offset(c * squareSize, r * squareSize),
                size = androidx.compose.ui.geometry.Size(squareSize, squareSize),
            )
        }

        // 4. Check highlight (radial-ish glow approximated with a soft rect + ring)
        checkedKingSquare?.let { sq ->
            val (c, r) = squareToDisplayCell(sq, orientation)
            drawRect(
                color = CheckRed.copy(alpha = 0.55f),
                topLeft = Offset(c * squareSize, r * squareSize),
                size = androidx.compose.ui.geometry.Size(squareSize, squareSize),
            )
            drawCircle(
                color = CheckRed,
                radius = squareSize * 0.46f,
                center = Offset(c * squareSize + squareSize / 2f, r * squareSize + squareSize / 2f),
                style = Stroke(width = squareSize * 0.05f),
            )
        }

        // 5. Coordinate labels (file letters along bottom row, rank numbers along left column)
        if (showCoordinates) {
            // Scale the label to the square rather than using a fixed pixel size — a hardcoded
            // size renders huge on a small board and tiny on a tablet, and it is what made the
            // file letters collide with the rank-1 piece bases.
            val labelSize = squareSize * 0.17f
            coordPaintLight.textSize = labelSize
            coordPaintDark.textSize = labelSize
            for (i in 0..7) {
                val file = if (orientation == BoardOrientation.WHITE_DOWN) i else 7 - i
                val fileChar = ('a' + file).toString()
                val isLightCorner = (7 + i) % 2 == 0
                drawContext.canvas.nativeCanvas.drawText(
                    fileChar,
                    i * squareSize + squareSize * 0.09f,
                    8 * squareSize - squareSize * 0.08f,
                    if (isLightCorner) coordPaintLight else coordPaintDark,
                )
                val rankFromTop = if (orientation == BoardOrientation.WHITE_DOWN) i else 7 - i
                val rankNum = (8 - rankFromTop).toString()
                val isLightRow = (i) % 2 == 0
                drawContext.canvas.nativeCanvas.drawText(
                    rankNum,
                    squareSize * 8 - squareSize * 0.22f,
                    i * squareSize + squareSize * 0.24f,
                    if (isLightRow) coordPaintLight else coordPaintDark,
                )
            }
        }

        // 6. Legal move dots
        legalMoveTargets.forEach { sq ->
            val (c, r) = squareToDisplayCell(sq, orientation)
            val occupied = board.pieces.containsKey(sq)
            val center = Offset(c * squareSize + squareSize / 2f, r * squareSize + squareSize / 2f)
            if (occupied) {
                drawCircle(
                    color = LegalMoveDot,
                    radius = squareSize * 0.46f,
                    center = center,
                    style = Stroke(width = squareSize * 0.08f),
                )
            } else {
                drawCircle(color = LegalMoveDot, radius = squareSize * 0.16f, center = center)
            }
        }

        // 7. Pieces — original vector silhouettes (PieceGeometry), scaled/translated per
        // square. Each piece type's Path is built once and cached (see PieceGeometry),
        // so this loop only does cheap transforms + fills, never path construction.
        board.pieces.forEach { (sq, piece) ->
            val (c, r) = squareToDisplayCell(sq, orientation)
            drawPiece(piece, c, r, squareSize)
        }

        // 8. Arrows (engine best move / missed tactics)
        arrows.forEach { arrow ->
            drawBoardArrow(arrow, orientation, squareSize)
        }
    }
}

// Piece palette: light pieces get a warm off-white fill with a dark outline, dark pieces
// get a near-black fill with a light outline, so both read clearly on either square color.
private val WhitePieceFill = Color(0xFFF7F5EF)
private val WhitePieceStroke = Color(0xFF23221F)
private val BlackPieceFill = Color(0xFF1A1917)
private val BlackPieceStroke = Color(0xFFF2F1EC)

/**
 * Fraction of the square a piece's *tight ink bounds* (not the nominal 100x100
 * viewport) occupies. Centering by actual bounds — rather than the nominal box, which
 * different pieces fill by very different amounts vertically (a pawn's head tops out
 * around y=31, a king's cross reaches y=2) — is what guarantees a consistent margin on
 * all four sides for every piece, so nothing ever crowds the square edge or the
 * coordinate labels in the corner squares.
 */
private const val PiecePadding = 0.85f
private const val PieceStrokeWidth = 2.6f

private fun DrawScope.drawPiece(piece: Piece, col: Int, row: Int, squareSize: Float) {
    val path = PieceGeometry.pathFor(piece.type)
    val bounds = PieceGeometry.boundsFor(piece.type)
    val (fill, stroke) = if (piece.color == PieceColor.WHITE) {
        WhitePieceFill to WhitePieceStroke
    } else {
        BlackPieceFill to BlackPieceStroke
    }
    val targetSize = squareSize * PiecePadding
    val pieceScale = minOf(targetSize / bounds.width, targetSize / bounds.height)
    val originX = col * squareSize + (squareSize - bounds.width * pieceScale) / 2f - bounds.left * pieceScale
    val originY = row * squareSize + (squareSize - bounds.height * pieceScale) / 2f - bounds.top * pieceScale
    translate(left = originX, top = originY) {
        scale(scale = pieceScale, pivot = Offset.Zero) {
            drawPath(path = path, color = fill)
            drawPath(path = path, color = stroke, style = Stroke(width = PieceStrokeWidth))
        }
    }
}

private fun DrawScope.drawBoardArrow(arrow: BoardArrow, orientation: BoardOrientation, squareSize: Float) {
    val (fc, fr) = squareToDisplayCell(arrow.from, orientation)
    val (tc, tr) = squareToDisplayCell(arrow.to, orientation)
    val start = Offset(fc * squareSize + squareSize / 2f, fr * squareSize + squareSize / 2f)
    val end = Offset(tc * squareSize + squareSize / 2f, tr * squareSize + squareSize / 2f)
    val strokeWidth = squareSize * 0.16f
    val angle = atan2((end.y - start.y).toDouble(), (end.x - start.x).toDouble())
    val shortenBy = squareSize * 0.32f
    val trimmedEnd = Offset(
        (end.x - cos(angle) * shortenBy).toFloat(),
        (end.y - sin(angle) * shortenBy).toFloat(),
    )
    val arrowColor = arrow.color.copy(alpha = 0.9f)

    drawLine(
        color = arrowColor,
        start = start,
        end = trimmedEnd,
        strokeWidth = strokeWidth,
        cap = androidx.compose.ui.graphics.StrokeCap.Round,
    )
    val headLength = squareSize * 0.34f
    val headAngle = Math.toRadians(28.0)
    val p1 = Offset(
        (end.x - cos(angle - headAngle) * headLength).toFloat(),
        (end.y - sin(angle - headAngle) * headLength).toFloat(),
    )
    val p2 = Offset(
        (end.x - cos(angle + headAngle) * headLength).toFloat(),
        (end.y - sin(angle + headAngle) * headLength).toFloat(),
    )
    val path = androidx.compose.ui.graphics.Path().apply {
        moveTo(end.x, end.y)
        lineTo(p1.x, p1.y)
        lineTo(p2.x, p2.y)
        close()
    }
    drawPath(path, color = arrowColor)
}

private fun labelPaint(color: Color): Paint = Paint().apply {
    isAntiAlias = true
    this.color = color.toArgb()
    textAlign = Paint.Align.LEFT
    textSize = 16f // replaced per-draw with a square-relative size; see drawBoard
    isFakeBoldText = true
}

private fun Color.toArgb(): Int {
    val a = (alpha * 255f).toInt().coerceIn(0, 255)
    val r = (red * 255f).toInt().coerceIn(0, 255)
    val g = (green * 255f).toInt().coerceIn(0, 255)
    val b = (blue * 255f).toInt().coerceIn(0, 255)
    return (a shl 24) or (r shl 16) or (g shl 8) or b
}

/** Kept for callers that want a fixed square-size board without letting it fill its parent. */
val ChessBoardDefaultSize: Dp = 320.dp
