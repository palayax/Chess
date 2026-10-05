package net.palaya.chessanalyzer.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import net.palaya.chessanalyzer.R
import net.palaya.chessanalyzer.core.analysis.EvalFormat
import net.palaya.chessanalyzer.ui.theme.EvalBlackFill
import net.palaya.chessanalyzer.ui.theme.EvalWhiteFill

/**
 * Vertical animated evaluation bar. `evalCentipawns` is from White's perspective
 * (positive = White better). `mateIn` overrides the numeric read-out with "M#" when set.
 *
 * A later integration pass wires `evalCentipawns`/`mateIn` to the engine's live/analyzed
 * score for the currently-displayed ply instead of static preview values.
 */
@Composable
fun EvalBar(
    evalCentipawns: Int,
    modifier: Modifier = Modifier,
    mateIn: Int? = null,
    width: androidx.compose.ui.unit.Dp = 28.dp,
    orientationFlipped: Boolean = false,
) {
    // Map centipawns to a 0..1 fraction of the bar that is "white" via a compressing
    // curve so huge advantages don't instantly peg the bar.
    val clamped = evalCentipawns.coerceIn(-1000, 1000)
    val rawFraction = 0.5f + (clamped / 1000f) * 0.5f
    val whiteFraction = if (mateIn != null) (if (mateIn > 0) 0.97f else 0.03f) else rawFraction
    val animatedFraction by animateFloatAsState(
        targetValue = whiteFraction.coerceIn(0.03f, 0.97f),
        animationSpec = tween(durationMillis = 350),
        label = "evalBarFraction",
    )

    // One formatter for the bar, the move list and the video panel (ANALYSIS_SPEC §9.4),
    // so the same position cannot read "+0.9" here and "0.9" there. Mate renders as mate.
    val label = EvalFormat.score(evalCentipawns, mateIn)
    val spoken = stringResource(R.string.cd_eval_bar, label)
    Box(
        modifier = modifier
            .width(width)
            .fillMaxHeight()
            .background(EvalBlackFill)
            // TalkBack: "Evaluation +0.9" instead of a bare, unexplained number.
            .clearAndSetSemantics { contentDescription = spoken },
        contentAlignment = Alignment.BottomCenter,
    ) {
        Canvas(modifier = Modifier.fillMaxHeight().width(width)) {
            val displayFraction = if (orientationFlipped) 1f - animatedFraction else animatedFraction
            val whiteHeight = size.height * displayFraction
            drawRect(
                color = EvalWhiteFill,
                topLeft = Offset(0f, size.height - whiteHeight),
                size = Size(size.width, whiteHeight),
            )
        }
        val labelIsOnWhiteSide = (mateIn?.let { it > 0 } ?: (evalCentipawns >= 0)) != orientationFlipped
        // The bar is a fixed-width graphic, so its read-out must not grow with the system font
        // scale: at 1.5x "+0.2" wrapped to two lines and clipped. The sp size is pinned by
        // resolving it against a density whose fontScale is 1.
        val density = LocalDensity.current
        // Also pinned LTR: "+2.0" has no strong-direction character, so under an RTL locale bidi
        // resolution rendered it "2.0+" (seen on-device, docs/screenshots/r13_u6_14_360_rtl_font10.png).
        CompositionLocalProvider(
            LocalDensity provides Density(density.density, fontScale = 1f),
            LocalLayoutDirection provides LayoutDirection.Ltr,
        ) {
            Text(
                text = label,
                modifier = Modifier
                    .align(if (labelIsOnWhiteSide) Alignment.BottomCenter else Alignment.TopCenter)
                    .background(if (labelIsOnWhiteSide) EvalWhiteFill else EvalBlackFill)
                    .width(width),
                textAlign = TextAlign.Center,
                maxLines = 1,
                softWrap = false,
                color = if (labelIsOnWhiteSide) EvalBlackFill else EvalWhiteFill,
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}
