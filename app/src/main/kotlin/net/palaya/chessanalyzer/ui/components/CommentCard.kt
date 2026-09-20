package net.palaya.chessanalyzer.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import net.palaya.chessanalyzer.R
import net.palaya.chessanalyzer.ui.theme.tacticTypeName
import net.palaya.chessanalyzer.core.analysis.TacticReferenceLibrary
import net.palaya.chessanalyzer.core.analysis.TacticType
import net.palaya.chessanalyzer.ui.model.MoveRecord
import net.palaya.chessanalyzer.ui.theme.ClassificationBadge
import net.palaya.chessanalyzer.ui.theme.ClassificationLabel
import net.palaya.chessanalyzer.ui.theme.MoveNotationStyle

/**
 * The Review screen's per-move annotation card: classification, engine best line, and
 * free-text commentary, plus a "Show me" affordance that will later launch the
 * missed-tactic replay simulation for this move.
 */
@Composable
fun CommentCard(
    move: MoveRecord,
    modifier: Modifier = Modifier,
    onShowMeClick: (() -> Unit)? = null,
    /**
     * Open the textbook example of a pattern this move carries (ANALYSIS_SPEC §10). Shown as a
     * quiet text button only when the move actually executed or passed up a motif the reference
     * library covers — the review card is the natural place to ask "what is a skewer, exactly?".
     */
    onLearnPattern: ((TacticType) -> Unit)? = null,
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                move.classification?.let { classification ->
                    ClassificationBadge(classification = classification, size = 26.dp)
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        ClassificationLabel(classification = classification)
                        Text(
                            text = move.san,
                            style = MoveNotationStyle,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                } ?: Text(text = move.san, style = MoveNotationStyle)

                Spacer(modifier = Modifier.weight(1f))

                if (onShowMeClick != null) {
                    Button(onClick = onShowMeClick) {
                        Text(text = stringResource(R.string.review_show_me))
                    }
                }
            }

            move.bestMoveSan?.let { bestMove ->
                Spacer(modifier = Modifier.height(10.dp))
                Row {
                    Text(
                        text = stringResource(R.string.review_best_line) + ": ",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = bestMove,
                        style = MoveNotationStyle,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            move.annotation?.let { annotation ->
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = annotation,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (pattern != null && onLearnPattern != null) {
                Spacer(modifier = Modifier.height(4.dp))
                TextButton(
                    onClick = { onLearnPattern(pattern) },
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
                ) {
                    Icon(Icons.Filled.School, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(stringResource(R.string.review_learn_pattern, tacticTypeName(pattern)))
                }
            }
        }
    }
}
