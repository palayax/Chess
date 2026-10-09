@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package net.palaya.chessanalyzer.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.RadioButton
import androidx.compose.runtime.remember
import net.palaya.chessanalyzer.ui.components.PlayerMaterialRow
import net.palaya.chessanalyzer.ui.model.AnalysisStrength
import net.palaya.chessanalyzer.ui.model.canReanalyse
import net.palaya.chessanalyzer.ui.model.customDepthValue
import net.palaya.chessanalyzer.ui.model.materialViewOrNull
import net.palaya.chessanalyzer.ui.model.strengthChoices
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.School
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import net.palaya.chessanalyzer.ui.a11y.asHeading
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import net.palaya.chessanalyzer.R
import net.palaya.chessanalyzer.ui.a11y.AppBarTitle
import net.palaya.chessanalyzer.ui.theme.tacticTypeName
import net.palaya.chessanalyzer.core.analysis.TacticType
import net.palaya.chessanalyzer.ui.components.EvalGraph
import net.palaya.chessanalyzer.ui.model.ClassificationRow
import net.palaya.chessanalyzer.ui.model.GameReport
import net.palaya.chessanalyzer.ui.model.KeyMoment
import net.palaya.chessanalyzer.ui.model.PieceColor
import net.palaya.chessanalyzer.ui.model.PracticeEntryState
import net.palaya.chessanalyzer.ui.model.canTryIt
import net.palaya.chessanalyzer.ui.model.PlaceholderData
import net.palaya.chessanalyzer.ui.model.PlayerReport
import net.palaya.chessanalyzer.ui.model.SideChoice
import net.palaya.chessanalyzer.ui.model.SummaryMoments
import net.palaya.chessanalyzer.ui.model.TacticGroup
import net.palaya.chessanalyzer.ui.model.TacticOccurrence
import net.palaya.chessanalyzer.ui.model.classificationRows
import net.palaya.chessanalyzer.ui.model.selectSummaryMoments
import net.palaya.chessanalyzer.ui.model.sideChoice
import net.palaya.chessanalyzer.video.AccuracyBand
import net.palaya.chessanalyzer.video.accuracyBand
import net.palaya.chessanalyzer.video.recapAccuracyText
import net.palaya.chessanalyzer.video.versusLine
import net.palaya.chessanalyzer.ui.theme.AccuracyGood
import net.palaya.chessanalyzer.ui.theme.AccuracyLow
import net.palaya.chessanalyzer.ui.theme.AccuracyMid
import net.palaya.chessanalyzer.ui.theme.ChessAnalyzerTheme
import net.palaya.chessanalyzer.ui.theme.ClassificationBadge
import net.palaya.chessanalyzer.ui.theme.MoveClassification

/**
 * The game summary: the hub of the app (docs/MOBILE_UX_DESIGN.md §6.3). It answers "what went
 * wrong and what do I do about it" in the first screenful:
 *
 *  1. a header (players, opening, result), the accuracy of both sides and a plain-language rating;
 *  2. "Which side were you?", so the rest can say "you";
 *  3. the key moments, each opening the board at that move, with an inline "Show me" where a
 *     walkthrough exists;
 *  4. the video and the board;
 *  5. a collapsed **Details** section with the graph, the move-quality table and the tactic buckets.
 *
 * The order is the design: what to fix first, the instruments (graph, 22 numbers) behind one tap.
 */
@Composable
fun GameReportScreen(
    report: GameReport,
    modifier: Modifier = Modifier,
    /** Open the board at this ply. */
    onKeyMomentClick: ((Int) -> Unit)? = null,
    /** Open the walkthrough of the missed (or found) tactic at this ply. */
    onShowMeClick: ((Int) -> Unit)? = null,
    /** Open the Board at this ply in its best-line mode (V2); offered on a key moment with no walkthrough. */
    onShowBestLine: ((Int) -> Unit)? = null,
    /** Same shape/intent as [onKeyMomentClick]: jump the Review screen to this ply. */
    onTacticClick: ((Int) -> Unit)? = null,
    /** Open the textbook example of a pattern (ANALYSIS_SPEC §10). */
    onLearnPattern: ((TacticType) -> Unit)? = null,
    /** Open the narrated video. */
    onWatchReviewClick: (() -> Unit)? = null,
    /** Open the move-by-move board from the start. */
    onOpenBoardClick: (() -> Unit)? = null,
    /** The user answered "Which side were you?". Null hides the chooser (previews). */
    onSideChosen: ((SideChoice) -> Unit)? = null,
    /** What the "Practise these positions" row says; [PracticeEntryState.Hidden] draws nothing. */
    practiceEntry: PracticeEntryState = PracticeEntryState.Hidden,
    /** Open Practise at the first unsolved position (only called for [PracticeEntryState.Count]). */
    onPracticeClick: (() -> Unit)? = null,
    /** The plies that are practice positions; a key-moment card on one of them offers "Try it". */
    practicePlies: Set<Int> = emptySet(),
    /** Open Practise at this ply. */
    onTryIt: ((Int) -> Unit)? = null,
    /**
     * FEN of the position after the last move (A4): the Summary shows the final material balance from it.
     * Null hides that part (previews, a game with no moves).
     */
    finalFen: String? = null,
    /** The depth this game was analysed at (A4); null while it is not in memory. Shown with "Re-analyse". */
    analysisDepth: Int? = null,
    /** The user picked another strength and confirmed "Re-analyse" (A4). Null hides the card (previews). */
    onReanalyse: ((AnalysisStrength) -> Unit)? = null,
    onBack: (() -> Unit)? = null,
) {
    val moments = selectSummaryMoments(report)
    // A game with nothing to fix has nothing above the fold worth reading, so Details starts open.
    var detailsExpanded by rememberSaveable { mutableStateOf(moments.isEmpty) }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { AppBarTitle(stringResource(R.string.report_title)) },
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
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
            item(key = "header") { HeaderCard(report) }

            if (onSideChosen != null) {
                item(key = "side-chooser") { SideChooserCard(choice = report.sideChoice, onChosen = onSideChosen) }
            }

            // A4: how many moves of each class each side played, and the material at the end: right under
            // "who was who", where chess.com's Game Review puts its table, not behind Details.
            item(key = "move-quality-title") {
                Text(
                    text = stringResource(R.string.summary_move_quality),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.asHeading(),
                )
            }
            item(key = "move-quality") { MoveQualityCard(report = report, finalFen = finalFen) }

            keyMomentsSection(
                report = report,
                moments = moments,
                onMomentClick = onKeyMomentClick,
                onShowMeClick = onShowMeClick,
                onShowBestLine = onShowBestLine,
                practicePlies = practicePlies,
                onTryIt = onTryIt,
            )

            // "Practise these positions" (docs/PRACTICE_DESIGN.md §5): a card row, not a button, after
            // the key moments and before the two buttons, so the Summary keeps one primary action.
            // "Not me" hides the whole section.
            if (practiceEntry != PracticeEntryState.Hidden) {
                item(key = "practice-title") {
                    Text(
                        text = stringResource(R.string.practice_section_title),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.asHeading(),
                    )
                }
                item(key = "practice-entry") {
                    PracticeEntryCard(entry = practiceEntry, onClick = onPracticeClick)
                }
            }

            if (onWatchReviewClick != null || onOpenBoardClick != null) {
                item(key = "buttons") {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (onWatchReviewClick != null) {
                            FilledTonalButton(onClick = onWatchReviewClick, modifier = Modifier.fillMaxWidth()) {
                                Text(stringResource(R.string.summary_watch_video))
                            }
                        }
                        if (onOpenBoardClick != null) {
                            OutlinedButton(onClick = onOpenBoardClick, modifier = Modifier.fillMaxWidth()) {
                                Text(stringResource(R.string.summary_open_board))
                            }
                        }
                    }
                }
            }

            if (onReanalyse != null && analysisDepth != null) {
                item(key = "analysis-strength") { AnalysisStrengthCard(analysisDepth, onReanalyse) }
            }

            item(key = "details-header") {
                DetailsHeader(expanded = detailsExpanded, onToggle = { detailsExpanded = !detailsExpanded })
            }
            if (detailsExpanded) {
                item(key = "graph-title") {
                    Text(
                        text = stringResource(R.string.report_eval_graph),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.asHeading(),
                    )
                }
                item(key = "graph") {
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

                tacticsSections(report = report, onTacticClick = onTacticClick, onLearnPattern = onLearnPattern)

                // F1: the search budget stopped some positions early (ANALYSIS_SPEC §8.1). One quiet
                // line at the end of Details: honest, but not a warning the user has to act on.
                if (report.cappedPositions > 0) {
                    item(key = "capped-positions") {
                        Text(
                            text = pluralStringResource(R.plurals.summary_capped_positions, report.cappedPositions, report.cappedPositions),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

/** First-strong isolate (FSI..PDI): the text keeps its own direction inside any paragraph. */
private val FSI = 0x2068.toChar()
private val PDI = 0x2069.toChar()
/** Left-to-right mark: keeps digits and notation in one piece inside an RTL paragraph. */
private val LRM = 0x200E.toChar()

private fun isolateBidi(text: String): String = "$FSI$text$PDI"

/** "MorphyFan1857 (you) vs DukeAndCount", then "Philidor Defense · 17 moves · 1-0". */
@Composable
private fun HeaderCard(report: GameReport) {
    val userColor = report.userColor
    val vsFormat = stringResource(R.string.game_vs_format)
    val youFormat = stringResource(R.string.summary_name_you)
    // "(you)" goes after the isolated name, outside it (R7: the Hebrew-name header swapped its names).
    fun label(color: PieceColor): (String) -> String =
        if (userColor == color) { isolated -> String.format(youFormat, isolated) } else { isolated -> isolated }
    val moves = (report.plyCount + 1) / 2
    // FSI..PDI isolates "17 moves" so its direction cannot be reshuffled by an RTL paragraph; the
    // LRM keeps "1-0" in one piece (the same two fixes Home needed).
    val parts = listOfNotNull(
        report.openingName?.takeIf { it.isNotBlank() },
        if (moves > 0) isolateBidi(pluralStringResource(R.plurals.recent_moves_count, moves, moves)) else null,
        report.header.result.takeIf { it.isNotBlank() && it != "*" }?.let { "$LRM$it" },
    )
    val subtitle = parts.reduceOrNull { acc, part -> stringResource(R.string.recent_game_subtitle, acc, part) }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = versusLine(
                    vsFormat,
                    report.header.white,
                    report.header.black,
                    decorateWhite = label(PieceColor.WHITE),
                    decorateBlack = label(PieceColor.BLACK),
                ),
                // Ltr, not Content: a title that starts with a Hebrew name must not turn the whole line RTL.
                style = MaterialTheme.typography.titleLarge.copy(textDirection = TextDirection.Ltr),
                fontWeight = FontWeight.Bold,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.Content),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            AccuracyRow(report)
            report.summarySentence?.let { sentence ->
                // One line on how the game unfolded (ANALYSIS_SPEC §12), built in :core from the
                // report alone, so it follows the side chooser like the buckets do.
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = sentence,
                    style = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.Content),
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            RatingLines(report)
        }
    }
}

/** Plain-language rating: one sentence for the user, or one per side when the side is not known. */
@Composable
private fun RatingLines(report: GameReport) {
    val style = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.Content)
    val color = MaterialTheme.colorScheme.onSurface
    @Composable fun sentence(player: PlayerReport): String =
        if (player.lowConfidence) {
            stringResource(R.string.report_rating_low_confidence)
        } else {
            stringResource(R.string.report_est_rating_sentence, player.estimatedRating)
        }
    val user = report.userColor
    when {
        user != null -> Text(
            text = sentence(if (user == PieceColor.WHITE) report.white else report.black),
            style = style,
            color = color,
        )
        report.white.lowConfidence && report.black.lowConfidence -> Text(
            text = stringResource(R.string.report_rating_low_confidence),
            style = style,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        else -> Column {
            listOf(PieceColor.WHITE to report.white, PieceColor.BLACK to report.black).forEach { (c, player) ->
                Text(
                    text = stringResource(
                        R.string.summary_side_rating,
                        stringResource(if (c == PieceColor.WHITE) R.string.side_white else R.string.side_black),
                        sentence(player),
                    ),
                    style = style,
                    color = color,
                )
            }
        }
    }
}

/**
 * "Which side were you?" The answer turns "White found" into "Tactics you found". Always shown, so
 * a wrong answer can be changed; the current answer is the selected segment. [SideChoice.UNKNOWN]
 * selects nothing and adds one line saying what the answer is for.
 */
@Composable
private fun SideChooserCard(choice: SideChoice, onChosen: (SideChoice) -> Unit) {
    val options = listOf(
        SideChoice.WHITE to R.string.side_white,
        SideChoice.BLACK to R.string.side_black,
        SideChoice.NOT_ME to R.string.summary_side_neither,
    )
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = stringResource(R.string.summary_which_side),
                style = MaterialTheme.typography.titleSmall.copy(textDirection = TextDirection.Content),
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.asHeading(),
            )
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                options.forEachIndexed { index, (option, labelRes) ->
                    SegmentedButton(
                        selected = choice == option,
                        onClick = { onChosen(option) },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                    ) {
                        Text(text = stringResource(labelRes), maxLines = 2, textAlign = TextAlign.Center)
                    }
                }
            }
            if (choice == SideChoice.UNKNOWN) {
                Text(
                    text = stringResource(R.string.summary_side_help),
                    style = MaterialTheme.typography.bodySmall.copy(textDirection = TextDirection.Content),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun LazyListScope.keyMomentsSection(
    report: GameReport,
    moments: SummaryMoments,
    onMomentClick: ((Int) -> Unit)?,
    onShowMeClick: ((Int) -> Unit)?,
    practicePlies: Set<Int>,
    onTryIt: ((Int) -> Unit)?,
    onShowBestLine: ((Int) -> Unit)? = null,
) {
    val userKnown = report.userColor != null
    item(key = "moments-title") {
        Text(
            text = stringResource(if (userKnown) R.string.report_key_moments_you else R.string.report_key_moments),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.asHeading(),
        )
    }
    if (moments.isEmpty || (userKnown && moments.primary.isEmpty())) {
        item(key = "moments-none") { TacticsEmptyRow(message = stringResource(R.string.summary_no_key_moments)) }
    }
    items(moments.primary, key = { "moment-${it.ply}" }) { moment ->
        KeyMomentCard(moment, report, onMomentClick, onShowMeClick, practicePlies, onTryIt, onShowBestLine)
    }
    if (moments.opponent.isNotEmpty()) {
        item(key = "moments-opponent-title") {
            Text(
                text = stringResource(R.string.summary_key_moments_opponent),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.asHeading(),
            )
        }
        items(moments.opponent, key = { "moment-${it.ply}" }) { moment ->
            KeyMomentCard(moment, report, onMomentClick, onShowMeClick, practicePlies, onTryIt, onShowBestLine)
        }
    }
}

@Composable
private fun DetailsHeader(expanded: Boolean, onToggle: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        shape = MaterialTheme.shapes.medium,
        onClick = onToggle,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.summary_details),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f).asHeading(),
            )
            Icon(
                imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = stringResource(if (expanded) R.string.summary_details_collapse else R.string.summary_details_expand),
            )
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
    @StringRes val titleRes: Int,
    val groups: List<TacticGroup>,
    val minor: List<TacticGroup>,
    @StringRes val emptyRes: Int,
)

private fun buildTacticSections(report: GameReport): List<TacticSection> {
    val userColor = report.userColor
    return if (userColor != null) {
        val you = if (userColor == PieceColor.WHITE) report.white else report.black
        val opponent = if (userColor == PieceColor.WHITE) report.black else report.white
        listOf(
            TacticSection(
                titleRes = R.string.report_tactics_you_found,
                groups = you.tacticsFound,
                minor = you.minorTacticsFound,
                emptyRes = R.string.report_tactics_empty,
            ),
            TacticSection(
                titleRes = R.string.report_tactics_you_missed,
                groups = you.tacticsMissed,
                minor = you.minorTacticsMissed,
                emptyRes = R.string.report_tactics_empty_nice,
            ),
            TacticSection(
                titleRes = R.string.report_tactics_opponent_found,
                groups = opponent.tacticsFound,
                minor = opponent.minorTacticsFound,
                emptyRes = R.string.report_tactics_empty,
            ),
            TacticSection(
                titleRes = R.string.report_tactics_opponent_missed,
                groups = opponent.tacticsMissed,
                minor = opponent.minorTacticsMissed,
                emptyRes = R.string.report_tactics_empty,
            ),
        )
    } else {
        listOf(
            TacticSection(
                titleRes = R.string.report_tactics_white_found,
                groups = report.white.tacticsFound,
                minor = report.white.minorTacticsFound,
                emptyRes = R.string.report_tactics_empty,
            ),
            TacticSection(
                titleRes = R.string.report_tactics_white_missed,
                groups = report.white.tacticsMissed,
                minor = report.white.minorTacticsMissed,
                emptyRes = R.string.report_tactics_empty,
            ),
            TacticSection(
                titleRes = R.string.report_tactics_black_found,
                groups = report.black.tacticsFound,
                minor = report.black.minorTacticsFound,
                emptyRes = R.string.report_tactics_empty,
            ),
            TacticSection(
                titleRes = R.string.report_tactics_black_missed,
                groups = report.black.tacticsMissed,
                minor = report.black.minorTacticsMissed,
                emptyRes = R.string.report_tactics_empty,
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
                text = stringResource(section.titleRes),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.asHeading(),
            )
        }
        if (section.groups.isEmpty()) {
            item { TacticsEmptyRow(message = stringResource(section.emptyRes)) }
        } else {
            items(section.groups) { group ->
                TacticGroupCard(group = group, onOccurrenceClick = onTacticClick, onLearnPattern = onLearnPattern)
            }
        }
        if (section.minor.isNotEmpty()) {
            // One expander per section, keyed on the section so its state survives scrolling.
            item(key = "minor-${section.titleRes}") {
                MinorTacticsDisclosure(
                    minor = section.minor,
                    onOccurrenceClick = onTacticClick,
                    onLearnPattern = onLearnPattern,
                )
            }
        }
    }
}

/**
 * "N smaller ones - Show": the tactics the §9.6 gate pruned. Collapsed by default so
 * the report reads as what mattered; one tap shows the rest, drawn with the same cards so nothing
 * about them is second-class except their placement.
 */
@Composable
private fun MinorTacticsDisclosure(
    minor: List<TacticGroup>,
    onOccurrenceClick: ((Int) -> Unit)?,
    onLearnPattern: ((TacticType) -> Unit)?,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val count = minor.sumOf { it.count }
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .clickable(role = Role.Button) { expanded = !expanded }
                .padding(horizontal = 4.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = pluralStringResource(R.plurals.report_minor_tactics_hidden, count, count),
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
            // The pattern is the unit of learning, so the offer sits on the group, not on each
            // occurrence: one quiet text button, no pop quiz. From font scale 1.3 it drops under the
            // name instead of squeezing it ("Hangin / g piece" at 2.0).
            val stackLearn = LocalDensity.current.fontScale >= 1.3f
            val learn: @Composable () -> Unit = {
                if (group.hasReference && onLearnPattern != null) {
                    TextButton(
                        onClick = { onLearnPattern(group.type) },
                        modifier = Modifier.heightIn(min = 48.dp),
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = tacticTypeName(group.type),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                if (group.count > 1) {
                    Text(
                        text = "$LRM×${group.count}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (!stackLearn) learn()
            }
            if (stackLearn) learn()
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
            .heightIn(min = 48.dp)
            .let { base -> if (onClick != null) base.clickable(role = Role.Button, onClick = onClick) else base }
            .padding(vertical = 4.dp),
    ) {
        Text(
            text = "${occurrence.moveNumber}. ${occurrence.san}",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
        )
        Text(
            text = occurrence.description,
            style = MaterialTheme.typography.bodySmall.copy(textDirection = TextDirection.Content),
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
            style = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.Content),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(12.dp),
        )
    }
}

/**
 * The two accuracy numbers, the user's side first. Side by side normally; stacked one per row from
 * font scale 1.3 up, where two columns of big digits plus labels no longer fit a phone.
 */
@Composable
private fun AccuracyRow(report: GameReport) {
    val user = report.userColor
    val order = if (user == PieceColor.BLACK) listOf(PieceColor.BLACK, PieceColor.WHITE) else listOf(PieceColor.WHITE, PieceColor.BLACK)
    val stacked = LocalDensity.current.fontScale >= 1.3f
    @Composable
    fun cell(color: PieceColor, modifier: Modifier) {
        val player = if (color == PieceColor.WHITE) report.white else report.black
        val label = when {
            user == null -> stringResource(if (color == PieceColor.WHITE) R.string.side_white else R.string.side_black)
            user == color -> stringResource(R.string.summary_you)
            else -> stringResource(R.string.summary_opponent)
        }
        AccuracyCell(label = label, accuracyPercent = player.accuracyPercent, stacked = stacked, modifier = modifier)
    }
    if (stacked) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            order.forEach { cell(it, Modifier.fillMaxWidth()) }
        }
    } else {
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            order.forEach { cell(it, Modifier.weight(1f)) }
        }
    }
}

@Composable
private fun AccuracyCell(label: String, accuracyPercent: Double, stacked: Boolean, modifier: Modifier = Modifier) {
    // LRM: "97%" must not become "%97" inside an RTL paragraph.
    val percent = recapAccuracyText(accuracyPercent)
    val color = accuracyColor(accuracyPercent)
    val accuracyWord = stringResource(R.string.report_accuracy)
    // One TalkBack stop ("You, Accuracy, 97%"); the bar underneath only repeats the number.
    Column(modifier = modifier.semantics(mergeDescendants = true) {}) {
        if (stacked) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "$label · $accuracyWord",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Text(text = percent, style = MaterialTheme.typography.headlineSmall, color = color, fontWeight = FontWeight.Bold)
            }
        } else {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(text = percent, style = MaterialTheme.typography.headlineMedium, color = color, fontWeight = FontWeight.Bold)
        }
        Spacer(modifier = Modifier.height(4.dp))
        LinearProgressIndicator(
            progress = { (accuracyPercent / 100.0).toFloat().coerceIn(0f, 1f) },
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp)
                .clearAndSetSemantics { },
            color = color,
            trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        )
        if (!stacked) {
            Text(
                text = accuracyWord,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun accuracyColor(accuracy: Double) = when (accuracyBand(accuracy)) {
    AccuracyBand.GOOD -> AccuracyGood
    AccuracyBand.MID -> AccuracyMid
    AccuracyBand.LOW -> AccuracyLow
}

/**
 * The move-quality table (A4), the chess.com Game Review pattern: both players' counts of each class either
 * side of the class badge, every class for both sides in the app's own order, zeros included (see
 * [classificationRows]). Below it, the material at the end of the game.
 */
@Composable
private fun MoveQualityCard(report: GameReport, finalFen: String?) {
    val rows = classificationRows(report.white.counts, report.black.counts)
    val user = report.userColor
    @Composable
    fun header(player: PlayerReport, color: PieceColor) =
        if (user == color) stringResource(R.string.summary_name_you, player.name) else player.name
    val whiteName = header(report.white, PieceColor.WHITE)
    val blackName = header(report.black, PieceColor.BLACK)
    val material = remember(finalFen) { materialViewOrNull(finalFen) }
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = whiteName,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = blackName,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.End,
                    modifier = Modifier.weight(1f),
                )
            }
            rows.forEach { ClassificationTableRow(it) }
            Spacer(modifier = Modifier.height(8.dp))
            // The classes whose name alone does not say what they are.
            val legends = buildList {
                add(R.string.classification_book_help)
                if (rows.any { it.badge == MoveClassification.FORCED }) add(R.string.classification_forced_help)
                add(R.string.classification_miss_help)
            }
            legends.forEach { helpRes ->
                Text(
                    text = stringResource(helpRes),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (material != null) {
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = stringResource(R.string.summary_final_material),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.asHeading(),
                )
                PlayerMaterialRow(name = whiteName, side = material.white)
                PlayerMaterialRow(name = blackName, side = material.black)
                Text(
                    text = when {
                        material.isLevel -> stringResource(R.string.summary_material_level)
                        material.whitePoints > material.blackPoints ->
                            stringResource(R.string.summary_material_ahead, whiteName, material.whitePoints - material.blackPoints)
                        else ->
                            stringResource(R.string.summary_material_ahead, blackName, material.blackPoints - material.whitePoints)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ClassificationTableRow(row: ClassificationRow) {
    val name = stringResource(row.badge.displayNameRes)
    val spoken = stringResource(R.string.cd_class_row, name, row.white, row.black)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            // One sentence per row ("Mistake, White 2, Black 1"): read cell by cell it was "2, 1".
            .clearAndSetSemantics { contentDescription = spoken },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "${row.white}",
            modifier = Modifier.widthIn(min = 28.dp),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(modifier = Modifier.width(4.dp))
        ClassificationBadge(classification = row.badge, size = 18.dp)
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            text = name,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        ClassificationBadge(classification = row.badge, size = 18.dp)
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = "${row.black}",
            modifier = Modifier.widthIn(min = 28.dp),
            style = MaterialTheme.typography.labelLarge,
            textAlign = TextAlign.End,
        )
    }
}

/**
 * "Analysed at Standard" and a "Re-analyse" button (A4): the strength belongs to the game. The button opens a
 * short chooser (Quick / Standard / Deep, the current one marked); confirming analyses the game again at that
 * strength. The Settings value stays the default for games analysed for the first time.
 */
@Composable
private fun AnalysisStrengthCard(depth: Int, onReanalyse: (AnalysisStrength) -> Unit) {
    var choosing by rememberSaveable { mutableStateOf(false) }
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = stringResource(R.string.settings_depth),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.asHeading(),
            )
            Text(
                text = stringResource(R.string.reanalyse_analysed_at, strengthLabel(depth)),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(onClick = { choosing = true }, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.reanalyse_button))
            }
        }
    }
    if (choosing) {
        ReanalyseDialog(
            currentDepth = depth,
            onConfirm = { strength ->
                choosing = false
                onReanalyse(strength)
            },
            onDismiss = { choosing = false },
        )
    }
}

@Composable
private fun strengthLabel(depth: Int): String = when (AnalysisStrength.fromDepth(depth)) {
    AnalysisStrength.QUICK -> stringResource(R.string.settings_depth_quick)
    AnalysisStrength.STANDARD -> stringResource(R.string.settings_depth_standard)
    AnalysisStrength.DEEP -> stringResource(R.string.settings_depth_deep)
    null -> stringResource(R.string.settings_custom_value, customDepthValue(depth))
}

@Composable
private fun ReanalyseDialog(currentDepth: Int, onConfirm: (AnalysisStrength) -> Unit, onDismiss: () -> Unit) {
    var picked by rememberSaveable { mutableStateOf<AnalysisStrength?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.reanalyse_title)) },
        text = {
            Column {
                Text(
                    text = stringResource(R.string.reanalyse_body, strengthLabel(currentDepth)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(8.dp))
                strengthChoices(currentDepth).forEach { choice ->
                    val label = strengthLabel(choice.strength.depth)
                    val hint = stringResource(
                        when (choice.strength) {
                            AnalysisStrength.QUICK -> R.string.reanalyse_hint_quick
                            AnalysisStrength.STANDARD -> R.string.reanalyse_hint_standard
                            AnalysisStrength.DEEP -> R.string.reanalyse_hint_deep
                        },
                    )
                    val current = stringResource(R.string.reanalyse_current)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 56.dp)
                            .selectable(
                                selected = picked == choice.strength,
                                role = Role.RadioButton,
                                onClick = { picked = choice.strength },
                            )
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = picked == choice.strength, onClick = null)
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = if (choice.isCurrent) "$label ($current)" else label,
                                style = MaterialTheme.typography.bodyLarge,
                            )
                            Text(
                                text = hint,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { picked?.let(onConfirm) },
                enabled = canReanalyse(currentDepth, picked),
                modifier = Modifier.heightIn(min = 48.dp),
            ) { Text(stringResource(R.string.reanalyse_confirm)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.reanalyse_cancel)) }
        },
    )
}

/**
 * One key moment: badge, move and class, the one-line explanation, and (only where a walkthrough
 * exists) "Show me". Tapping the card opens the board at that move.
 */
@Composable
private fun KeyMomentCard(
    moment: KeyMoment,
    report: GameReport,
    onClick: ((Int) -> Unit)?,
    onShowMe: ((Int) -> Unit)?,
    practicePlies: Set<Int>,
    onTryIt: ((Int) -> Unit)?,
    onShowBestLine: ((Int) -> Unit)? = null,
) {
    // "what I missed" is only true of the user's own mistakes; a found tactic or the opponent's
    // moment is a plain "Show me".
    val ownMistake = report.userColor == moment.moverColor && moment.classification != MoveClassification.BRILLIANT
    val canShowMe = onShowMe != null && moment.ply in report.plysWithSimulation
    // V2: a moment with no walkthrough plays the engine's line on the Board instead of leaving it unseen.
    val canShowLine = !canShowMe && onShowBestLine != null && moment.ply in report.plysWithBestLine
    // "Try it" opens Practise at this move, only when the move is a practice position.
    val tryIt = onTryIt != null && canTryIt(moment.ply, practicePlies)
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        shape = MaterialTheme.shapes.medium,
        onClick = { onClick?.invoke(moment.ply) },
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ClassificationBadge(classification = moment.classification, size = 26.dp)
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        // LRM: a move like "15… Nf6" is notation, never mirrored (Round 10).
                        text = LRM + stringResource(
                            if (moment.moverColor == PieceColor.WHITE) R.string.summary_moment_white else R.string.summary_moment_black,
                            moment.moveNumber,
                            moment.san,
                        ),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        text = stringResource(moment.classification.displayNameRes),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (moment.description.isNotBlank()) {
                Text(
                    text = moment.description,
                    style = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.Content),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (canShowMe || canShowLine || tryIt) {
                // A flow row, so at a large font the two buttons wrap instead of clipping.
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (canShowMe) {
                        FilledTonalButton(
                            onClick = { onShowMe?.invoke(moment.ply) },
                            modifier = Modifier.heightIn(min = 48.dp),
                        ) {
                            Text(stringResource(if (ownMistake) R.string.review_show_me_missed else R.string.review_show_me))
                        }
                    }
                    if (canShowLine) {
                        FilledTonalButton(
                            onClick = { onShowBestLine?.invoke(moment.ply) },
                            modifier = Modifier.heightIn(min = 48.dp),
                        ) {
                            Text(stringResource(R.string.review_show_best_line))
                        }
                    }
                    if (tryIt) {
                        OutlinedButton(
                            onClick = { onTryIt?.invoke(moment.ply) },
                            modifier = Modifier.heightIn(min = 48.dp),
                        ) {
                            Text(stringResource(R.string.practice_try_it))
                        }
                    }
                }
            }
        }
    }
}

/**
 * The "Practise these positions" row (docs/PRACTICE_DESIGN.md §5): a card, not a button. Only the
 * [PracticeEntryState.Count] state is tappable and carries a chevron; "choose a side" and "nothing to
 * fix" are plain text. [PracticeEntryState.Hidden] is never passed here (the section is not drawn).
 */
@Composable
private fun PracticeEntryCard(entry: PracticeEntryState, onClick: (() -> Unit)?) {
    val text = when (entry) {
        PracticeEntryState.NoSide -> stringResource(R.string.practice_entry_no_side)
        PracticeEntryState.Empty -> stringResource(R.string.practice_entry_empty)
        is PracticeEntryState.Count -> stringResource(
            R.string.practice_entry_solved,
            pluralStringResource(R.plurals.practice_entry_count, entry.total, entry.total),
            entry.solved,
        )
        PracticeEntryState.Hidden -> ""
    }
    val content: @Composable () -> Unit = {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodyLarge.copy(textDirection = TextDirection.Content),
                color = if (entry is PracticeEntryState.Count) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            if (entry is PracticeEntryState.Count) {
                // Auto-mirrored, so it points the reading direction in RTL.
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
    if (entry is PracticeEntryState.Count && onClick != null) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            shape = MaterialTheme.shapes.medium,
            onClick = onClick,
        ) { content() }
    } else {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            shape = MaterialTheme.shapes.medium,
        ) { content() }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF302E2B, heightDp = 1400)
@Composable
private fun GameReportScreenPreview() {
    ChessAnalyzerTheme {
        GameReportScreen(report = PlaceholderData.sampleReport, onSideChosen = {})
    }
}
