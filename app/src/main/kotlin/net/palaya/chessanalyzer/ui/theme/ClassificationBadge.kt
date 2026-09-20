package net.palaya.chessanalyzer.ui.theme

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Draws a chess.com-style move-classification badge as a solid Compose-rendered
 * vector/Canvas circle + glyph — no raster/bitmap assets are shipped, so there is
 * nothing here that could be mistaken for a copied chess.com badge image.
 */
@Composable
fun ClassificationBadge(
    classification: MoveClassification,
    modifier: Modifier = Modifier,
    size: Dp = 22.dp,
    ringOnly: Boolean = false,
) {
    Box(
        modifier = modifier.size(size),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.size(size)) {
            val radius = this.size.minDimension / 2f
            if (ringOnly) {
                drawCircle(
                    color = classification.color,
                    radius = radius - 1.5f,
                    style = Stroke(width = 2.5f),
                )
            } else {
                drawCircle(color = classification.color, radius = radius)
                // subtle inner shade for a flat-but-dimensional look
                drawCircle(
                    color = Color.Black.copy(alpha = 0.12f),
                    radius = radius,
                    center = Offset(this.size.width / 2f, this.size.height / 2f + radius * 0.15f),
                )
            }
        }
        Text(
            text = classification.glyph,
            style = TextStyle(
                color = if (ringOnly) classification.color else glyphContentColor(classification),
                fontSize = (size.value * 0.52f).sp,
                fontWeight = FontWeight.Black,
            ),
        )
    }
}

/** Light glyphs read better on the darker classification colors, dark glyphs on the bright ones. */
private fun glyphContentColor(classification: MoveClassification): Color = when (classification) {
    MoveClassification.INACCURACY -> Color(0xFF3B2E00)
    else -> Color.White
}

/** Small inline label variant used in dense contexts like the move list. */
@Composable
fun ClassificationLabel(classification: MoveClassification) {
    Text(
        text = stringResource(classification.displayNameRes),
        color = classification.color,
        style = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 13.sp),
    )
}
