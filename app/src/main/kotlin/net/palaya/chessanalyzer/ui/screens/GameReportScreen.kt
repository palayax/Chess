@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package net.palaya.chessanalyzer.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import net.palaya.chessanalyzer.R
import net.palaya.chessanalyzer.ui.theme.tacticTypeName
import net.palaya.chessanalyzer.core.analysis.TacticType
import net.palaya.chessanalyzer.ui.components.EvalGraph
import net.palaya.chessanalyzer.ui.model.ClassificationCount
import net.palaya.chessanalyzer.ui.model.GameReport
import net.palaya.chessanalyzer.ui.model.KeyMoment
import net.palaya.chessanalyzer.ui.model.PieceColor
import net.palaya.chessanalyzer.ui.model.PlaceholderData
import net.palaya.chessanalyzer.ui.model.PlayerReport
import net.palaya.chessanalyzer.ui.model.TacticGroup
import net.palaya.chessanalyzer.ui.model.TacticOccurrence
import net.palaya.chessanalyzer.ui.theme.AccuracyGood
import net.palaya.chessanalyzer.ui.theme.AccuracyLow
import net.palaya.chessanalyzer.ui.theme.AccuracyMid
import net.palaya.chessanalyzer.ui.theme.ChessAnalyzerTheme
import net.palaya.chessanalyzer.ui.theme.ClassificationBadge

/**
 * chess.com-style post-game report: accuracy, estimated performance rating, a
 * classification breakdown table for both sides, an eval graph, and key moments.
 */
@Composable
fun GameReportScreen(
    report: GameReport,
    modifier: Modifier = Modifier,
    onShareClick: (() -> Unit)? = null,
    onKeyMomentClick: ((Int) -> Unit)? = null,
    /** Same shape/intent as [onKeyMomentClick]: jump the Review screen to this ply. */
    onTacticClick: ((Int) -> Unit)? = null,
    /** Open the textbook example of a pattern (ANALYSIS_SPEC §10). */
    onLearnPattern: ((TacticType) -> Unit)? = null,
    onWatchReviewClick: (() -> Unit)? = null,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.report_title)) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                actions = {
                    if (onWatchReviewClick != null) {
                        androidx.compose.material3.TextButton(onClick = onWatchReviewClick) {
                            Text(stringResource(R.string.watch_game_review))
                        }
                    }
                    if (onShareClick != null) {
                        IconButton(onClick = onShareClick) {
                            Icon(Icons.Filled.Share, contentDescription = stringResource(R.string.report_share))
                        }
                    }
                },
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                Text(
                    text = "${report.header.white} vs ${report.header.black}",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
            }

            item { AccuracyRow(white = report.white, black = report.black) }

            item {
                Text(
                    text = stringResource(R.string.report_eval_graph),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                    shape = MaterialTheme.shapes.medium,
                ) {
                    EvalGraph(
                        evalHistory = report.evalHistory,
                        modifier = Modifier.padding(12.dp),
                        plyClassifications = report.plyClassifications,
                        sequences = report.sequences,
                    )
                }
            }

            item {
                Text(
                    text = stringResource(R.string.report_classification_breakdown),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            item { ClassificationTable(white = report.white, black = report.black) }

            item {
                Text(
                    text = stringResource(R.string.report_key_moments),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            items(report.keyMoments) { moment ->
                KeyMomentRow(moment = moment, onClick = { onKeyMomentClick?.invoke(moment.ply) })
            }

            tacticsSections(report = report, onTacticClick = onTacticClick, onLearnPattern = onLearnPattern)
        }
    }
}

/**
 * The four tactics buckets the user explicitly asked for: recognized/missed, for the user and
 * for the opponent. Framed as "you"/"your opponent" when [GameReport.userColor] is known,
 * falling back to plain White/Black labelling otherwise.
 *
 * [minor] holds what the significance gate (ANALYSIS_SPEC §9.6) pruned from [groups]: shown
 * behind a disclosure row so the default view is the tactics that mattered, and nothing detected
 * is unreachable.
 */
private data class TacticSection(
    val title: String,
    val groups: List<TacticGroup>,
    val minor: List<TacticGroup>,
    val emptyMessage: String,
)

private fun buildTacticSections(report: GameReport): List<TacticSection> {
    val userColor = report.userColor
    return if (userColor != null) {
        val you = if (userColor == PieceColor.WHITE) report.white else report.black
        val opponent = if (userColor == PieceColor.WHITE) report.black else report.white
        listOf(
            TacticSection(
                title = "Tactics you found",
                groups = you.tacticsFound,
                minor = you.minorTacticsFound,
                emptyMessage = "No tactics found yet.",
            ),
            TacticSection(
                title = "Tactics you missed",
                groups = you.tacticsMissed,
                minor = you.minorTacticsMissed,
                emptyMessage = "No missed tactics — nice game.",
            ),
            TacticSection(
                title = "Tactics your opponent found",
                groups = opponent.tacticsFound,
                minor = opponent.minorTacticsFound,
                emptyMessage = "Your opponent didn't land any tactics.",
            ),
            TacticSection(
                title = "Tactics your opponent missed",
                groups = opponent.tacticsMissed,
                minor = opponent.minorTacticsMissed,
                emptyMessage = "Your opponent didn't miss any tactics.",
            ),
        )
    } else {
        listOf(
            TacticSection(
                title = "White found",
                groups = report.white.tacticsFound,
                minor = report.white.minorTacticsFound,
                emptyMessage = "White didn't land any tactics.",
            ),
            TacticSection(
                title = "White missed",
                groups = report.white.tacticsMissed,
                minor = report.white.minorTacticsMissed,
                emptyMessage = "No missed tactics for White — nice game.",
            ),
            TacticSection(
                title = "Black found",
                groups = report.black.tacticsFound,
                minor = report.black.minorTacticsFound,
                emptyMessage = "Black didn't land any tactics.",
            ),
            TacticSection(
                title = "Black missed",
                groups = report.black.tacticsMissed,
                minor = report.black.minorTacticsMissed,
                emptyMessage = "No missed tactics for Black — nice game.",
            ),
        )
    }
}

private fun LazyListScope.tacticsSections(
    report: GameReport,
    onTacticClick: ((Int) -> Unit)?,
    onLearnPattern: ((TacticType) -> Unit)?,
) {
    buildTacticSections(report).forEach { section ->
        item {
            Text(
                text = section.title,
                style = MaterialTheme.typography.titleMedium,
            )
        }
        if (section.groups.isEmpty()) {
            item { TacticsEmptyRow(message = section.emptyMessage) }
        } else {
            items(section.groups) { group ->
                TacticGroupCard(group = group, onOccurrenceClick = onTacticClick, onLearnPattern = onLearnPattern)
            }
        }
        if (section.minor.isNotEmpty()) {
            // One expander per section, keyed on the section so its state survives scrolling.
            item(key = "minor-${section.title}") {
                MinorTacticsDisclosure(
                    minor = section.minor,
                    thresholdCp = report.tacticThresholdCp,
                    onOccurrenceClick = onTacticClick,
                    onLearnPattern = onLearnPattern,
                )
            }
        }
    }
}

/**
 * "N minor (below ±0.5 pawns) — Show": the tactics the §9.6 gate pruned. Collapsed by default so
 * the report reads as what mattered; one tap shows the rest, drawn with the same cards so nothing
 * about them is second-class except their placement.
 */
@Composable
private fun MinorTacticsDisclosure(
    minor: List<TacticGroup>,
    thresholdCp: Int,
    onOccurrenceClick: ((Int) -> Unit)?,
    onLearnPattern: ((TacticType) -> Unit)?,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val count = minor.sumOf { it.count }
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(horizontal = 4.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.report_minor_tactics_hidden, count, "%.1f".format(thresholdCp / 100.0)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = stringResource(if (expanded) R.string.report_minor_tactics_hide else R.string.report_minor_tactics_show),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        if (expanded) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                minor.forEach { group ->
                    TacticGroupCard(group = group, onOccurrenceClick = onOccurrenceClick, onLearnPattern = onLearnPattern)
                }
            }
        }
    }
}

@Composable
private fun TacticGroupCard(
    group: TacticGroup,
    onOccurrenceClick: ((Int) -> Unit)?,
    onLearnPattern: ((TacticType) -> Unit)? = null,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = tacticTypeName(group.type),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                if (group.count > 1) {
                    Text(
                        text = "×${group.count}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (group.hasReference && onLearnPattern != null) {
                    // The pattern is the unit of learning, so the offer sits on the group, not on
                    // each occurrence — one quiet text button, no pop quiz.
                    TextButton(
                        onClick = { onLearnPattern(group.type) },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                    ) {
                        Icon(
                            Icons.Filled.School,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(stringResource(R.string.report_learn_pattern), style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
            group.occurrences.forEachIndexed { index, occurrence ->
                if (index > 0) {
                    Spacer(modifier = Modifier.height(6.dp))
                }
                TacticOccurrenceRow(
                    occurrence = occurrence,
                    onClick = onOccurrenceClick?.let { callback -> { callback(occurrence.ply) } },
                )
            }
        }
    }
}

@Composable
private fun TacticOccurrenceRow(occurrence: TacticOccurrence, onClick: (() -> Unit)?) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .let { base -> if (onClick != null) base.clickable(onClick = onClick) else base }
            .padding(vertical = 4.dp),
    ) {
        Text(
            text = "${occurrence.moveNumber}. ${occurrence.san}",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
        )
        Text(
            text = occurrence.description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun TacticsEmptyRow(message: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        shape = MaterialTheme.shapes.medium,
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(12.dp),
        )
    }
}

@Composable
private fun AccuracyRow(white: PlayerReport, black: PlayerReport) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AccuracyCard(player = white, modifier = Modifier.weight(1f))
        AccuracyCard(player = black, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun AccuracyCard(player: PlayerReport, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                text = player.name,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "%.1f%%".format(player.accuracyPercent),
                style = MaterialTheme.typography.displaySmall,
                color = accuracyColor(player.accuracyPercent),
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = stringResource(R.string.report_accuracy),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(10.dp))
            LinearProgressIndicator(
                progress = { (player.accuracyPercent / 100.0).toFloat() },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp),
                color = accuracyColor(player.accuracyPercent),
                trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            )
            Spacer(modifier = Modifier.height(10.dp))
            if (player.lowConfidence) {
                // ANALYSIS_SPEC.md §4: games under 20 plies have too little signal for the
                // rating estimate to mean anything — showing the number as fact would be
                // actively misleading (e.g. a near-empty game can "estimate" to ~2900).
                Text(
                    text = stringResource(R.string.report_rating_low_confidence),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(
                    text = "${player.estimatedRating}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Text(
                text = stringResource(R.string.report_est_rating),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun accuracyColor(accuracy: Double) = when {
    accuracy >= 85 -> AccuracyGood
    accuracy >= 65 -> AccuracyMid
    else -> AccuracyLow
}

@Composable
private fun ClassificationTable(white: PlayerReport, black: PlayerReport) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            white.counts.forEachIndexed { index, whiteCount ->
                val blackCount = black.counts.getOrNull(index) ?: ClassificationCount(whiteCount.classification, 0)
                ClassificationTableRow(whiteCount = whiteCount, blackCount = blackCount)
            }
        }
    }
}

@Composable
private fun ClassificationTableRow(whiteCount: ClassificationCount, blackCount: ClassificationCount) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "${whiteCount.count}",
            modifier = Modifier.width(28.dp),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(modifier = Modifier.width(4.dp))
        ClassificationBadge(classification = whiteCount.classification, size = 18.dp)
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            text = stringResource(whiteCount.classification.displayNameRes),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        ClassificationBadge(classification = blackCount.classification, size = 18.dp)
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = "${blackCount.count}",
            modifier = Modifier.width(28.dp),
            style = MaterialTheme.typography.labelLarge,
            textAlign = androidx.compose.ui.text.style.TextAlign.End,
        )
    }
}

@Composable
private fun KeyMomentRow(moment: KeyMoment, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        shape = MaterialTheme.shapes.medium,
        onClick = onClick,
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ClassificationBadge(classification = moment.classification, size = 22.dp)
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "${moment.moveNumber}. ${moment.san}",
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    text = moment.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF302E2B, heightDp = 1400)
@Composable
private fun GameReportScreenPreview() {
    ChessAnalyzerTheme {
        GameReportScreen(report = PlaceholderData.sampleReport)
    }
}
