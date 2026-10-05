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
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Density
import androidx.compose.material3.MaterialTheme
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
 *
 * Accessibility (R6a): the badge is a picture, so by default it is hidden from TalkBack (its glyph
 * would be read as "exclamation mark exclamation mark"; every place that draws it also writes the
 * class name in words). Pass [contentDescription] where the badge stands alone. The glyph's size
 * is pinned to font scale 1 so a 2.0 system font cannot overflow the circle, and its ink is the
 * darker of white and near-black against the fill, which keeps it at 4.9:1 or more (white gave
 * 2.2:1 to 3.6:1 on the palette).
 */
@Composable
fun ClassificationBadge(
    classification: MoveClassification,
    modifier: Modifier = Modifier,
    size: Dp = 22.dp,
    ringOnly: Boolean = false,
    contentDescription: String? = null,
) {
    Box(
        modifier = modifier.size(size).clearAndSetSemantics {
            if (contentDescription != null) this.contentDescription = contentDescription
        },
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
        val density = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 1f)) {
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
}

/** Light glyphs read better on the darker classification colors, dark glyphs on the bright ones. */
private fun glyphContentColor(classification: MoveClassification): Color = BadgeGlyphInk

/** Near-black ink of the badge glyph: 4.9:1 (blunder) to 7.8:1 (brilliant) on the palette's fills. */
private val BadgeGlyphInk = Color(0xFF1A1917)

/** Small inline label variant used in dense contexts like the move list. */
@Composable
fun ClassificationLabel(
    classification: MoveClassification,
    background: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
) {
    Text(
        text = stringResource(classification.displayNameRes),
        // The class colour, lightened only as far as needed to reach 4.5:1 on [background]: BLUNDER red
        // is 3.2:1 on a card as it is. The palette itself is unchanged; the badge beside the label
        // keeps the exact colour.
        color = legibleTextColor(classification.color, background),
        style = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 13.sp),
    )
}

/**
 * The Practise card's result badge: a green check for a solved puzzle, a red cross for a wrong
 * attempt, so colour is not the only signal. It reuses [ClassificationBadge]'s own shape (a solid
 * circle with the same inner shade) and the palette's best / blunder colours; the check and cross are
 * drawn as strokes so they never depend on a font. [contentDescription] is what TalkBack says.
 */
@Composable
fun PracticeResultBadge(
    correct: Boolean,
    contentDescription: String,
    modifier: Modifier = Modifier,
    size: Dp = 24.dp,
) {
    val fill = if (correct) ClassBest else ClassBlunder
    Canvas(
        modifier = modifier
            .size(size)
            .semantics { this.contentDescription = contentDescription },
    ) {
        val w = this.size.width
        val h = this.size.height
        val radius = this.size.minDimension / 2f
        drawCircle(color = fill, radius = radius)
        drawCircle(
            color = Color.Black.copy(alpha = 0.12f),
            radius = radius,
            center = Offset(w / 2f, h / 2f + radius * 0.15f),
        )
        val stroke = this.size.minDimension * 0.12f
        val glyph = BadgeGlyphInk
        if (correct) {
            drawLine(glyph, Offset(w * 0.28f, h * 0.52f), Offset(w * 0.44f, h * 0.68f), stroke, StrokeCap.Round)
            drawLine(glyph, Offset(w * 0.44f, h * 0.68f), Offset(w * 0.73f, h * 0.34f), stroke, StrokeCap.Round)
        } else {
            drawLine(glyph, Offset(w * 0.32f, h * 0.32f), Offset(w * 0.68f, h * 0.68f), stroke, StrokeCap.Round)
            drawLine(glyph, Offset(w * 0.68f, h * 0.32f), Offset(w * 0.32f, h * 0.68f), stroke, StrokeCap.Round)
        }
    }
}


/**
 * [color] itself when it already reaches [minRatio] contrast against [background], otherwise the
 * smallest blend towards white that does. Used for text drawn in a move-quality colour.
 */
fun legibleTextColor(color: Color, background: Color, minRatio: Double = 4.5): Color {
    var step = 0
    while (step <= 20) {
        val candidate = lerp(color, Color.White, step / 20f)
        if (contrastRatio(candidate, background) >= minRatio) return candidate
        step++
    }
    return Color.White
}

/** WCAG relative-luminance contrast ratio of two opaque colours (1.0 to 21.0). */
fun contrastRatio(a: Color, b: Color): Double {
    val la = relativeLuminance(a)
    val lb = relativeLuminance(b)
    val hi = maxOf(la, lb)
    val lo = minOf(la, lb)
    return (hi + 0.05) / (lo + 0.05)
}

private fun relativeLuminance(c: Color): Double {
    fun channel(v: Float): Double {
        val x = v.toDouble()
        return if (x <= 0.03928) x / 12.92 else Math.pow((x + 0.055) / 1.055, 2.4)
    }
    return 0.2126 * channel(c.red) + 0.7152 * channel(c.green) + 0.0722 * channel(c.blue)
}

private fun lerp(from: Color, to: Color, t: Float): Color = Color(
    red = from.red + (to.red - from.red) * t,
    green = from.green + (to.green - from.green) * t,
    blue = from.blue + (to.blue - from.blue) * t,
    alpha = 1f,
)
