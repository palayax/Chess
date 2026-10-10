package net.palaya.chessanalyzer.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import net.palaya.chessanalyzer.R
import net.palaya.chessanalyzer.core.chess.PieceType as CorePieceType
import net.palaya.chessanalyzer.ui.board.PieceGeometry
import net.palaya.chessanalyzer.ui.model.PieceColor
import net.palaya.chessanalyzer.ui.model.PieceType
import net.palaya.chessanalyzer.ui.model.SideMaterial
import net.palaya.chessanalyzer.ui.model.groupedTaken

/** Height of the strip's pieces; the Board reserves [MaterialRowHeight] for each strip. */
private val PieceSize = 18.dp
val MaterialRowHeight = 28.dp

/** Same palette as [net.palaya.chessanalyzer.ui.board.ChessBoard]'s pieces, so a captured piece looks like the one on the board. */
private val WhiteFill = Color(0xFFF2F1EC)
private val WhiteStroke = Color(0xFF23221F)
private val BlackFill = Color(0xFF1A1917)
private val BlackStroke = Color(0xFFF2F1EC)

private fun CorePieceType.toUi(): PieceType = when (this) {
    CorePieceType.PAWN -> PieceType.PAWN
    CorePieceType.KNIGHT -> PieceType.KNIGHT
    CorePieceType.BISHOP -> PieceType.BISHOP
    CorePieceType.ROOK -> PieceType.ROOK
    CorePieceType.QUEEN -> PieceType.QUEEN
    CorePieceType.KING -> PieceType.KING
}

/**
 * One player's line on the Board (A4): the name, the pieces this player has captured and, for the side
 * ahead on material, "+N" (chess.com's pattern; our own drawing). The whole line is one TalkBack stop that
 * says the same in words; the pieces and the "+N" are not read on their own.
 */
@Composable
fun PlayerMaterialRow(
    name: String,
    side: SideMaterial,
    modifier: Modifier = Modifier,
) {
    val spoken = spokenMaterial(name, side)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(MaterialRowHeight)
            .clearAndSetSemantics { contentDescription = spoken },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = name,
            style = MaterialTheme.typography.labelLarge.copy(textDirection = TextDirection.Content),
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f, fill = false),
        )
        TakenPieces(side)
        if (side.ahead > 0) AheadLabel(side.ahead)
    }
}

/** "+3": quiet next to the name, and a number, so never a colour alone. */
@Composable
fun AheadLabel(points: Int, modifier: Modifier = Modifier) {
    Text(
        text = "+$points",
        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold, textDirection = TextDirection.Ltr),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}

/** The pieces [side] has captured, drawn small and overlapping within a kind, a gap between kinds. */
@Composable
fun TakenPieces(side: SideMaterial, modifier: Modifier = Modifier, pieceSize: Dp = PieceSize) {
    if (side.taken.isEmpty()) return
    val groups = remember(side.taken) { groupedTaken(side.taken) }
    val density = LocalDensity.current
    val pieceSizePx = with(density) { pieceSize.toPx() }
    val overlapStepPx = pieceSizePx * 0.5f
    val gapPx = pieceSizePx * 0.35f
    val widthPx = groups.sumOf { (_, n) -> (pieceSizePx + overlapStepPx * (n - 1)).toDouble() }.toFloat() +
        gapPx * (groups.size - 1)
    val color = side.takenColor
    Canvas(modifier = modifier.width(with(density) { widthPx.toDp() }).height(pieceSize)) {
        var x = 0f
        for ((type, count) in groups) {
            val path = PieceGeometry.pathFor(type.toUi())
            val bounds = PieceGeometry.boundsFor(type.toUi())
            val scalePiece = minOf(pieceSizePx / bounds.width, pieceSizePx / bounds.height)
            repeat(count) { i ->
                val left = x + overlapStepPx * i
                val ox = left + (pieceSizePx - bounds.width * scalePiece) / 2f - bounds.left * scalePiece
                val oy = (size.height - bounds.height * scalePiece) / 2f - bounds.top * scalePiece
                translate(left = ox, top = oy) {
                    scale(scale = scalePiece, pivot = Offset.Zero) {
                        drawPath(path, if (color == PieceColor.WHITE) WhiteFill else BlackFill)
                        drawPath(
                            path,
                            if (color == PieceColor.WHITE) WhiteStroke else BlackStroke,
                            style = Stroke(width = 2.6f),
                        )
                    }
                }
            }
            x += pieceSizePx + overlapStepPx * (count - 1) + gapPx
        }
    }
}

/** "Anna: captured 2 pawns, 1 knight, ahead by 3" / "Anna: nothing captured yet". */
@Composable
fun spokenMaterial(name: String, side: SideMaterial): String {
    if (side.taken.isEmpty()) {
        val none = stringResource(R.string.cd_material_none, name)
        return if (side.ahead > 0) none + stringResource(R.string.cd_material_ahead, side.ahead) else none
    }
    val parts = groupedTaken(side.taken).map { (type, count) -> pieceCountText(type, count) }
    val base = stringResource(R.string.cd_material_taken, name, parts.joinToString(", "))
    return if (side.ahead > 0) base + stringResource(R.string.cd_material_ahead, side.ahead) else base
}

@Composable
fun pieceCountText(type: CorePieceType, count: Int): String = pluralStringResource(
    when (type) {
        CorePieceType.PAWN -> R.plurals.taken_pawns
        CorePieceType.KNIGHT -> R.plurals.taken_knights
        CorePieceType.BISHOP -> R.plurals.taken_bishops
        CorePieceType.ROOK -> R.plurals.taken_rooks
        CorePieceType.QUEEN -> R.plurals.taken_queens
        // A king is never captured; there is no string for it.
        CorePieceType.KING -> R.plurals.taken_pawns
    },
    count,
    count,
)
