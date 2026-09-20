package net.palaya.chessanalyzer.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import net.palaya.chessanalyzer.ui.model.MoveSequenceView
import net.palaya.chessanalyzer.ui.theme.EvalWhiteFill
import net.palaya.chessanalyzer.ui.theme.MoveClassification

/**
 * Canvas line chart of evaluation (centipawns, White's perspective) across plies, mirroring
 * chess.com's game-report eval graph. Clamped to +/-6 pawns for legibility; mate scores are
 * pre-clamped by the caller.
 *
 * Beyond the line itself it carries the classification colouring (ANALYSIS_SPEC §9.5):
 *  - each **segment** of the line is drawn in the colour of the move that produced it, so a
 *    blunder is a red edge and a brilliancy a teal one;
 *  - the mistakes (INACCURACY → BLUNDER) additionally get a **marked dot**, because those are
 *    the ones a reader is scanning for and a 3px colour change alone is easy to miss;
 *  - [sequences] shade the vertical band behind a multi-move run so a collapse or a combination
 *    reads as one region rather than as adjacent dots.
 *
 * **Accessibility.** Colour is not the only signal here either: the mistake dots are *shape*
 * (a filled marker where quiet moves have none), the line's vertical position already encodes
 * the evaluation, and the same information is available as text in the move list. A red/green
 * deficiency loses the hue distinction between BEST and BLUNDER but keeps the marker and the
 * shape of the curve.
 *
 * @param plyClassifications index `i` = ply `i + 1`, matching `GameReport.plyClassifications`.
 *   Empty falls back to a single-colour line, exactly as before.
 */
@Composable
fun EvalGraph(
    evalHistory: List<Int>,
    modifier: Modifier = Modifier,
    highlightPly: Int? = null,
    plyClassifications: List<MoveClassification> = emptyList(),
    sequences: List<MoveSequenceView> = emptyList(),
) {
    val lineColor = MaterialTheme.colorScheme.primary
    val gridColor = MaterialTheme.colorScheme.outlineVariant

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(120.dp),
    ) {
        if (evalHistory.isEmpty()) return@Canvas
        val maxAbs = 600f
        val stepX = size.width / (evalHistory.size - 1).coerceAtLeast(1)

        fun yFor(cp: Int): Float {
            val clamped = cp.coerceIn(-maxAbs.toInt(), maxAbs.toInt())
            val fraction = clamped / maxAbs // -1..1, positive = white better
            return size.height / 2f - fraction * (size.height / 2f)
        }

        // ---- Sequence bands, behind everything: index i of evalHistory is the position AFTER
        // ply i, so a run over plies a..b spans x from (a-1) to b.
        for (sequence in sequences) {
            val left = ((sequence.startPly - 1).coerceAtLeast(0)) * stepX
            val right = (sequence.endPly.coerceAtMost(evalHistory.size - 1)) * stepX
            if (right <= left) continue
            drawRect(
                color = sequence.classification.color.copy(alpha = 0.18f),
                topLeft = Offset(left, 0f),
                size = Size(right - left, size.height),
            )
        }

        // Midline (eval = 0)
        drawLine(
            color = gridColor,
            start = Offset(0f, size.height / 2f),
            end = Offset(size.width, size.height / 2f),
            strokeWidth = 1.5f,
        )

        // Filled area: white above midline, black below — echoes the eval bar's palette.
        val whiteArea = Path().apply {
            moveTo(0f, size.height / 2f)
            evalHistory.forEachIndexed { i, cp -> lineTo(i * stepX, yFor(cp)) }
            lineTo(size.width, size.height / 2f)
            close()
        }
        drawPath(whiteArea, color = EvalWhiteFill.copy(alpha = 0.18f), style = Fill)

        // ---- The line, one coloured segment per move.
        for (i in 0 until evalHistory.size - 1) {
            val ply = i + 1
            val classification = plyClassifications.getOrNull(ply - 1)
            drawLine(
                color = classification?.color ?: lineColor,
                start = Offset(i * stepX, yFor(evalHistory[i])),
                end = Offset((i + 1) * stepX, yFor(evalHistory[i + 1])),
                strokeWidth = 3f,
            )
        }

        // ---- Mistake markers: a shape, not just a hue, so the worst moves survive both a small
        // screen and a colour-vision deficiency.
        plyClassifications.forEachIndexed { index, classification ->
            if (!classification.isMistakeClass()) return@forEachIndexed
            val i = index + 1
            if (i !in evalHistory.indices) return@forEachIndexed
            val point = Offset(i * stepX, yFor(evalHistory[i]))
            drawCircle(color = classification.color, radius = 4.5f, center = point)
            drawCircle(color = Color.Black.copy(alpha = 0.55f), radius = 4.5f, center = point, style = Stroke(width = 1.5f))
        }

        highlightPly?.let { ply ->
            if (ply in evalHistory.indices) {
                val point = Offset(ply * stepX, yFor(evalHistory[ply]))
                drawCircle(color = Color.White, radius = 5f, center = point)
                drawCircle(color = lineColor, radius = 5f, center = point, style = Stroke(width = 2f))
            }
        }
    }
}

/**
 * Mirrors `core.analysis.MoveClassification.isMistake`. Duplicated rather than imported because
 * this is the *ui.theme* enum, which is a presentation type by design (see `DomainMapper`); the
 * two enums have identical entry names by construction, so the sets cannot drift silently.
 */
private fun MoveClassification.isMistakeClass(): Boolean = this in setOf(
    MoveClassification.INACCURACY,
    MoveClassification.MISTAKE,
    MoveClassification.MISS,
    MoveClassification.BLUNDER,
)
