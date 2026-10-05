package net.palaya.chessanalyzer.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale
import net.palaya.chessanalyzer.R
import net.palaya.chessanalyzer.ui.model.MoveRecord
import net.palaya.chessanalyzer.ui.model.MoveSequenceView
import net.palaya.chessanalyzer.ui.model.chipLabel
import net.palaya.chessanalyzer.ui.theme.ClassificationBadge
import net.palaya.chessanalyzer.ui.theme.MoveNotationStyle

/** Height of the sequence band strip above every chip; reserved even when no run is present. */
private val BAND_HEIGHT = 4.dp

/** Touch target floor (docs/MOBILE_UX_DESIGN.md section 5.5). */
private val CHIP_MIN_SIZE = 48.dp

/** Badge size in the list (section 5.4: 18 dp minimum; the comment card draws it at 26 dp). */
private val CHIP_BADGE_SIZE = 18.dp

/**
 * Horizontally scrollable move list with per-move classification badges, in the style of
 * chess.com's move panel condensed into one line. `selectedPly` drives auto-scroll and
 * highlight; tapping a move calls [onMoveSelected] with its ply.
 *
 * Each chip is deliberately small: the move (with its number on White's moves), the classification
 * badge, and the **engine score after the move** (White-relative, signed: `+0.9`, `-1.4`, `M3`,
 * `#`), formatted by `:core`'s single `EvalFormat` so it cannot drift from the eval bar. The swing
 * the move caused is not on the chip any more; the comment card says it in words for mistakes.
 *
 * **Two tiers.** Only the highlight-tier classes (`MoveClassification.isHighlight`: brilliancies
 * and the four mistake classes) tint the chip and draw a coloured border. The quiet
 * tier keeps a neutral chip and just its badge, so the mistakes stand out.
 *
 * [sequences] draws runs of plies that belong together (a multi-move tactic, a collapse) as one
 * continuous 4 dp coloured band above the chips. The band has no text; the comment card names the
 * sequence when one of its plies is selected. That is why the chips sit flush against each other
 * with internal padding instead of being spaced apart: a gap would break the band.
 *
 * **Accessibility.** Colour is never the only signal: every chip keeps its glyph (`!!`, `?`,
 * `??`, `★`, ...) and the score as text, and the spoken description names the classification. The
 * INACCURACY to BLUNDER ramp is yellow, orange, red, i.e. exactly the axis a red/green deficiency
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
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            items(moves, key = { it.ply }) { move ->
                val sequence = sequences.firstOrNull { move.ply in it }
                MoveColumn(
                    move = move,
                    sequence = sequence,
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
    selected: Boolean,
    onClick: () -> Unit,
) {
    // IntrinsicSize.Max, not fillMaxWidth: a LazyRow measures its items with an **unbounded**
    // main axis, so `fillMaxWidth()` inside one is a no-op and the band would collapse to nothing.
    // Fixing the column's width to the chip's gives the band something real to fill. (Found by
    // looking at a screenshot, not by a test.)
    Column(
        modifier = Modifier.width(IntrinsicSize.Max),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        SequenceBand(sequence = sequence)
        Box(modifier = Modifier.padding(horizontal = 2.dp, vertical = 2.dp)) {
            MoveChip(move = move, selected = selected, onClick = onClick)
        }
    }
}

/**
 * A fixed-height strip above every chip. Filled with the run's classification colour when the
 * ply belongs to one, transparent otherwise: adjacent items therefore paint a single unbroken
 * bar across the whole run. No text (a 9 sp label was unreadable and broke at large font scale);
 * the comment card carries the name.
 */
@Composable
private fun SequenceBand(sequence: MoveSequenceView?) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(BAND_HEIGHT)
            .background(sequence?.classification?.color?.copy(alpha = 0.9f) ?: Color.Transparent),
    )
}

@Composable
private fun MoveChip(move: MoveRecord, selected: Boolean, onClick: () -> Unit) {
    val isSelected = selected
    val classification = move.classification
    // Quiet-tier classes get no tint and no coloured border: only the small badge says what they are.
    val accent = classification?.takeIf { it.isHighlight }?.color
    val background = when {
        selected -> MaterialTheme.colorScheme.primaryContainer
        // A 14% wash of the classification colour over the dark surface: enough to read as
        // "this move was a blunder" at a glance, not enough to fight the text on top of it.
        accent != null -> accent.copy(alpha = 0.14f).compositeOverSurface()
        else -> MaterialTheme.colorScheme.surfaceContainerHigh
    }
    val label = chipLabel(move)
    // Spoken description for TalkBack: two complete templates rather than glued fragments, so a
    // translation can order the words naturally.
    val classificationName = classification?.let {
        stringResource(it.displayNameRes).lowercase(Locale.getDefault())
    }
    val description = if (classificationName != null) {
        stringResource(R.string.cd_move_chip_classified, move.moveNumber, move.san, classificationName, label.score)
    } else {
        stringResource(R.string.cd_move_chip, move.moveNumber, move.san, label.score)
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
            .clickable(role = Role.Button, onClick = onClick)
            // One TalkBack stop per chip: the sentence below replaces the SAN, badge glyph and score
            // texts (read separately they were "4. Nf3, plus 0.3, 4. Nf3 ..."), and says if it is selected.
            .clearAndSetSemantics {
                contentDescription = description
                this.selected = isSelected
            }
            .heightIn(min = CHIP_MIN_SIZE)
            .widthIn(min = CHIP_MIN_SIZE)
            .padding(horizontal = 7.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            label.number?.let { number ->
                Text(
                    text = number,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = label.san,
                style = MoveNotationStyle,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold,
                color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
            )
            classification?.let { ClassificationBadge(classification = it, size = CHIP_BADGE_SIZE) }
        }
        Text(
            text = label.score,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

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
