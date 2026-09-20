package net.palaya.chessanalyzer.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.palaya.chessanalyzer.core.analysis.EvalFormat
import net.palaya.chessanalyzer.ui.model.MoveRecord
import net.palaya.chessanalyzer.ui.model.MoveSequenceView
import net.palaya.chessanalyzer.ui.theme.ClassificationBadge
import net.palaya.chessanalyzer.ui.theme.MoveNotationStyle

/** Height of the sequence band strip above every chip; reserved even when no run is present. */
private val BAND_HEIGHT = 16.dp

/**
 * Horizontally scrollable move list with per-move classification badges, in the style of
 * chess.com's move panel condensed into one line. `selectedPly` drives auto-scroll and
 * highlight; tapping a move calls [onMoveSelected] with its ply.
 *
 * Each chip carries three things beyond the move itself:
 *  - the **engine score after the move**, White-relative and always signed (`+0.9`, `-1.4`,
 *    `M3`), formatted by `:core`'s single [EvalFormat] so it cannot drift from the eval bar;
 *  - the **swing** the move caused, which is the quantity the narration significance threshold
 *    acts on (ANALYSIS_SPEC §9);
 *  - the move's **classification colour and glyph**.
 *
 * [sequences] draws runs of plies that belong together (a multi-move tactic, a collapse) as one
 * continuous coloured band above the chips, so the run reads as a unit rather than as a row of
 * unrelated badges. That is why the chips sit flush against each other with internal padding
 * instead of being spaced apart — a gap would break the band.
 *
 * **Accessibility.** Colour is never the only signal: every chip keeps its glyph (`!!`, `?`,
 * `??`, `★`, ...), the score and swing are text, and each band carries a text label. The
 * INACCURACY→BLUNDER ramp is yellow→orange→red, i.e. exactly the axis a red/green deficiency
 * collapses, so a colour-only encoding would be unreadable for the most common CVD type.
 */
@Composable
fun MoveList(
    moves: List<MoveRecord>,
    selectedPly: Int?,
    onMoveSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
    sequences: List<MoveSequenceView> = emptyList(),
) {
    val listState = rememberLazyListState()
    LaunchedEffect(selectedPly) {
        val index = moves.indexOfFirst { it.ply == selectedPly }
        if (index >= 0) listState.animateScrollToItem(maxOf(0, index - 1))
    }

    // Pinned to LTR whatever the app's layout direction: algebraic notation is a left-to-right
    // script and a game reads 1. e4 e5 2. Nf3 in that order in every language. Without this an
    // RTL locale reversed the row AND, because "3." and "+0.4" contain no strong-direction
    // character, bidi resolution inherited the RTL paragraph direction and rendered them as
    // ".3" and "0.4+" (seen on-device, docs/screenshots/r10_rtl_review_before.png).
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        LazyRow(
            state = listState,
            modifier = modifier,
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            items(moves, key = { it.ply }) { move ->
                val sequence = sequences.firstOrNull { move.ply in it }
                MoveColumn(
                    move = move,
                    sequence = sequence,
                    isSequenceStart = sequence != null && sequence.startPly == move.ply,
                    selected = move.ply == selectedPly,
                    onClick = { onMoveSelected(move.ply) },
                )
            }
        }
    }
}

/** The sequence band plus the move chip. Kept in one item so the band stays continuous. */
@Composable
private fun MoveColumn(
    move: MoveRecord,
    sequence: MoveSequenceView?,
    isSequenceStart: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
) {
    // IntrinsicSize.Max, not fillMaxWidth: a LazyRow measures its items with an **unbounded**
    // main axis, so `fillMaxWidth()` inside one is a no-op and the band collapsed to the width of
    // its own text — which made it invisible on every ply except the one carrying the label, and
    // the run stopped reading as a run at all. Fixing the column's width first gives the band
    // something real to fill. (Found by looking at a screenshot, not by a test.)
    Column(
        modifier = Modifier.width(IntrinsicSize.Max),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        SequenceBand(sequence = sequence, showLabel = isSequenceStart)
        Box(modifier = Modifier.padding(horizontal = 2.dp)) {
            MoveChip(move = move, selected = selected, onClick = onClick)
        }
    }
}

/**
 * A fixed-height strip above every chip. Filled with the run's classification colour when the
 * ply belongs to one, transparent otherwise — adjacent items therefore paint a single unbroken
 * bar across the whole run. The label is drawn only on the first ply so the run reads as one
 * named thing, and it may widen that one chip a little — the run's first move is the one worth
 * giving room to.
 */
@Composable
private fun SequenceBand(sequence: MoveSequenceView?, showLabel: Boolean) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(BAND_HEIGHT)
            .background(sequence?.classification?.color?.copy(alpha = 0.9f) ?: Color.Transparent),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (sequence != null && showLabel) {
            Text(
                text = "${sequence.classification.glyph} ${sequence.label}",
                color = Color.Black,
                // lineHeight pinned under the band height, or the descenders of "Collapse" are
                // sliced off — which is what the first build did.
                style = TextStyle(fontSize = 9.sp, lineHeight = 11.sp, fontWeight = FontWeight.Black),
                maxLines = 1,
                overflow = TextOverflow.Clip,
                modifier = Modifier.padding(horizontal = 3.dp),
            )
        }
    }
}

@Composable
private fun MoveChip(move: MoveRecord, selected: Boolean, onClick: () -> Unit) {
    val accent = move.classification?.color
    val background = when {
        selected -> MaterialTheme.colorScheme.primaryContainer
        // A 14% wash of the classification colour over the dark surface: enough to read as
        // "this move was a blunder" at a glance, not enough to fight the text on top of it.
        accent != null -> accent.copy(alpha = 0.14f).compositeOverSurface()
        else -> MaterialTheme.colorScheme.surfaceContainerHigh
    }
    val description = buildString {
        append("Move ${move.moveNumber}, ${move.san}")
        move.classification?.let { append(", ${it.name.lowercase()}") }
        append(", evaluation ${scoreText(move)}")
        move.evalSwingCp?.let { append(", swing ${EvalFormat.swing(it)}") }
    }

    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(background)
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = if (selected) MaterialTheme.colorScheme.primary else (accent ?: Color.Transparent),
                shape = RoundedCornerShape(6.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 7.dp, vertical = 5.dp)
            .semantics { contentDescription = description },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            if (move.ply % 2 == 1) {
                Text(
                    text = "${move.moveNumber}.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = move.san,
                style = MoveNotationStyle,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold,
                color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
            )
            move.classification?.let { ClassificationBadge(classification = it, size = 14.dp) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = scoreText(move),
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurface,
            )
            move.evalSwingCp?.let { swing ->
                Text(
                    text = EvalFormat.swing(swing),
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** White-relative, mate-aware, shared with the eval bar and the video panel. */
private fun scoreText(move: MoveRecord): String = EvalFormat.score(move.evalCp, move.mateInMoves)

/**
 * Compose's [Color.copy] alpha is only honoured against whatever is painted underneath, and a
 * `LazyRow` item has no guaranteed backdrop. Flattening the wash against the theme surface up
 * front keeps every chip the same colour regardless of what happens to be behind it.
 */
@Composable
private fun Color.compositeOverSurface(): Color {
    val surface = MaterialTheme.colorScheme.surfaceContainerHigh
    return Color(
        red = red * alpha + surface.red * (1f - alpha),
        green = green * alpha + surface.green * (1f - alpha),
        blue = blue * alpha + surface.blue * (1f - alpha),
        alpha = 1f,
    )
}
