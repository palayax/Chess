package net.palaya.chessanalyzer.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.School
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import java.util.Locale
import net.palaya.chessanalyzer.R
import net.palaya.chessanalyzer.core.analysis.TacticReferenceLibrary
import net.palaya.chessanalyzer.core.analysis.TacticType
import net.palaya.chessanalyzer.ui.model.MoveRecord
import net.palaya.chessanalyzer.ui.model.MoveSequenceView
import net.palaya.chessanalyzer.ui.model.betterMoveToShow
import net.palaya.chessanalyzer.ui.model.pawnCostHalves
import net.palaya.chessanalyzer.ui.model.showMeIsAboutAMiss
import net.palaya.chessanalyzer.ui.theme.ClassificationBadge
import net.palaya.chessanalyzer.ui.theme.ClassificationLabel
import net.palaya.chessanalyzer.ui.theme.MoveNotationStyle
import net.palaya.chessanalyzer.ui.theme.tacticTypeName

/**
 * The Board screen's per-move annotation card, in plain language (docs/MOBILE_UX_DESIGN.md
 * sections 4 and 6.4): the classification and the move, "Better was Nf6" and "This cost about 2
 * pawns" for the mistake classes only, the engine's explanation, which sequence the move belongs
 * to (the move list's band carries no text), and the actions: "Show me what I missed" when there
 * is a walkthrough, and "See how a skewer works" when the pattern has a textbook example.
 *
 * The card sizes to its content; the screen puts it in the scrolling area under the controls.
 */
@Composable
fun CommentCard(
    move: MoveRecord,
    modifier: Modifier = Modifier,
    onShowMeClick: (() -> Unit)? = null,
    /**
     * Open the textbook example of a pattern this move carries (ANALYSIS_SPEC section 10). Shown as
     * a quiet text button only when the move actually executed or passed up a motif the reference
     * library covers.
     */
    onLearnPattern: ((TacticType) -> Unit)? = null,
    /** The run of plies this move belongs to, if any; named here because the band has no text. */
    sequence: MoveSequenceView? = null,
    /**
     * Jump to the next key moment (B2). Null hides the button: the board was not opened from a key
     * moment, or there is no later one.
     */
    onNextKeyMoment: (() -> Unit)? = null,
) {
    val pattern: TacticType? = remember(move) {
        move.core?.let { a -> (a.tacticsFound + a.tacticsMissed).maxByOrNull { it.confidence }?.type }
            ?.takeIf { TacticReferenceLibrary.hasReference(it) }
    }
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // The explanation as one block, read in one go and spoken again whenever the move changes
            // (the Next and Previous buttons keep TalkBack's focus, so without a live region the new
            // move would be silent). The buttons below stay separate stops.
            Column(
                modifier = Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
            ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                move.classification?.let { classification ->
                    ClassificationBadge(classification = classification, size = 26.dp)
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        ClassificationLabel(classification = classification)
                        Text(
                            text = move.san,
                            style = MoveNotationStyle,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                } ?: Text(text = move.san, style = MoveNotationStyle)
            }

            betterMoveToShow(move)?.let { better ->
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = stringResource(R.string.review_better_was, better),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            if (move.classification?.isMistake == true) {
                pawnCostHalves(move)?.let { halves ->
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = costText(halves),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }

            move.annotation?.let { annotation ->
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = annotation,
                    style = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.Content),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            sequence?.let { run ->
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = sequenceText(run),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            }

            if (onShowMeClick != null) {
                Spacer(modifier = Modifier.height(12.dp))
                Button(
                    onClick = onShowMeClick,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text(
                        text = stringResource(
                            if (showMeIsAboutAMiss(move)) R.string.review_show_me_missed else R.string.review_show_me,
                        ),
                    )
                }
            }

            if (pattern != null && onLearnPattern != null) {
                Spacer(modifier = Modifier.height(4.dp))
                TextButton(
                    onClick = { onLearnPattern(pattern) },
                    modifier = Modifier.heightIn(min = 48.dp),
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
                ) {
                    Icon(Icons.Filled.School, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(stringResource(R.string.review_learn_pattern, tacticTypeName(pattern)))
                }
            }

            if (onNextKeyMoment != null) {
                Spacer(modifier = Modifier.height(4.dp))
                TextButton(
                    onClick = onNextKeyMoment,
                    modifier = Modifier.heightIn(min = 48.dp),
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
                ) {
                    Text(stringResource(R.string.review_next_key_moment))
                }
            }
        }
    }
}

/** "This cost about half a pawn" / "...1.5 pawns" / "...2 pawns", from a count of half pawns. */
@Composable
internal fun costText(halves: Int): String = when {
    halves == 1 -> stringResource(R.string.review_cost_half_pawn)
    halves % 2 == 0 -> pluralStringResource(R.plurals.review_cost_pawns, halves / 2, halves / 2)
    else -> stringResource(R.string.review_cost_pawns_decimal, String.format(Locale.ROOT, "%.1f", halves / 2.0))
}

@Composable
private fun sequenceText(run: MoveSequenceView): String {
    // Move numbers, not plies: "(moves 4-6)" is how a player counts a game.
    val first = (run.startPly + 1) / 2
    val last = (run.endPly + 1) / 2
    return if (first == last) {
        stringResource(R.string.review_sequence_part_of_single, run.label, first)
    } else {
        stringResource(R.string.review_sequence_part_of, run.label, first, last)
    }
}
