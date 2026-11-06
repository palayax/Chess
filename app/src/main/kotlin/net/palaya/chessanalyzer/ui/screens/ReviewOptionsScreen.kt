@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package net.palaya.chessanalyzer.ui.screens

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import net.palaya.chessanalyzer.R
import net.palaya.chessanalyzer.core.narration.VideoPace
import net.palaya.chessanalyzer.ui.a11y.AppBarTitle
import net.palaya.chessanalyzer.ui.a11y.asHeading
import net.palaya.chessanalyzer.ui.model.EngineLevel
import net.palaya.chessanalyzer.ui.model.EngineSettings
import net.palaya.chessanalyzer.ui.model.GameHeader
import net.palaya.chessanalyzer.ui.model.NarrationProviderChoice
import net.palaya.chessanalyzer.ui.model.ReviewOptions
import net.palaya.chessanalyzer.ui.model.ReviewEstimateInputs
import net.palaya.chessanalyzer.ui.model.ReviewOptionsDraft
import net.palaya.chessanalyzer.ui.model.ReviewSpeeds
import net.palaya.chessanalyzer.ui.model.SideChoice
import net.palaya.chessanalyzer.ui.model.TimeEstimate
import net.palaya.chessanalyzer.ui.model.WordingOption
import net.palaya.chessanalyzer.ui.model.levelEstimates
import net.palaya.chessanalyzer.ui.theme.ChessAnalyzerTheme
import net.palaya.chessanalyzer.video.ExportTimeLeft
import net.palaya.chessanalyzer.video.VoiceSamplePlayer
import net.palaya.chessanalyzer.video.timeLeftFor
import net.palaya.chessanalyzer.video.versusLine

/**
 * Review options (RF, owner's phone test 2026-10-11, items 1, 2, 5 and 6): every choice a review needs, on one screen,
 * decided once before any processing; "Start review" then runs everything in one go. Opens after an import (unless
 * Settings says not to), from a library row's "Re-analyse with different settings…" and from the Summary. Pre-filled
 * from the game's own stored options, else the Settings defaults ([net.palaya.chessanalyzer.ui.model.prefillReviewOptions]).
 *
 * Reference (owner's standing rule; RUN_LOG "RF"): chess.com's Game Review setup, pattern only: a game header, a short
 * list of named engine strengths with a one-line description each, "which side were you", and one sticky primary
 * button. Our own words, layout and art.
 *
 * Stateless: [options] is the screen's working copy (the route keeps it), [onOptionsChange] edits it, [onStart] commits.
 */
@Composable
fun ReviewOptionsScreen(
    draft: ReviewOptionsDraft,
    options: ReviewOptions,
    onOptionsChange: (ReviewOptions) -> Unit,
    onStart: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    /** Opens Settings (where the wording model is downloaded). */
    onOpenSettings: () -> Unit = {},
    voiceSampleState: VoiceSamplePlayer.State = VoiceSamplePlayer.State.Idle,
    onPlayVoiceSample: (Int) -> Unit = {},
    onStopVoiceSample: () -> Unit = {},
    onVoicePickerClosed: () -> Unit = {},
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { AppBarTitle(stringResource(R.string.review_options_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        bottomBar = {
            // The one primary action, always reachable (sticky); edge to edge: the bottom bar owns its insets.
            Surface(color = MaterialTheme.colorScheme.background) {
                Button(
                    onClick = onStart,
                    modifier = Modifier
                        .navigationBarsPadding()
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                        .heightIn(min = 52.dp),
                ) {
                    Text(stringResource(R.string.review_options_start))
                }
            }
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            GameHeaderLine(draft.header, draft.plies)
            OptionsCard {
                StrengthChooser(
                    selected = options.level,
                    // Recomputed from the options as edited: the plan (video, wording, voice) changes the stages added.
                    estimates = remember(draft.estimateInputs, options.prepareVideo, options.naturalWording, options.voice) {
                        levelEstimates(draft.estimateInputs, options)
                    },
                    onSelect = { onOptionsChange(options.copy(level = it)) },
                )
            }
            OptionsCard {
                SideChooser(
                    selected = options.side,
                    onSelect = { onOptionsChange(options.copy(side = it)) },
                )
            }
            when (draft.wording) {
                WordingOption.HIDDEN -> Unit
                WordingOption.SWITCH -> OptionsCard {
                    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                        SwitchRow(
                            title = stringResource(R.string.settings_rephrase_title),
                            help = stringResource(R.string.review_options_wording_help),
                            checked = options.naturalWording,
                            onCheckedChange = { onOptionsChange(options.copy(naturalWording = it)) },
                        )
                    }
                }
                WordingOption.DOWNLOAD_IN_SETTINGS -> OptionsCard {
                    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                        Text(
                            text = stringResource(R.string.review_options_wording_download),
                            style = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.Content),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        TextButton(onClick = onOpenSettings, modifier = Modifier.heightIn(min = 48.dp)) {
                            Text(stringResource(R.string.review_options_open_settings))
                        }
                    }
                }
            }
            OptionsCard {
                VideoSection(
                    settings = EngineSettings(videoPace = options.pace),
                    onSettingsChange = { onOptionsChange(options.copy(pace = it.videoPace)) },
                    narrationVoiceSettings = options.voiceSettings,
                    voiceInstalled = draft.voiceInstalled,
                    onNarratorSpeakerChange = { sid -> onOptionsChange(options.copy(voice = NarrationProviderChoice.NEURAL, speakerId = sid)) },
                    voiceSampleState = voiceSampleState,
                    onPlayVoiceSample = onPlayVoiceSample,
                    onStopVoiceSample = onStopVoiceSample,
                    onVoicePickerClosed = onVoicePickerClosed,
                    heading = stringResource(R.string.review_options_video),
                    topContent = {
                        Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                            SwitchRow(
                                title = stringResource(R.string.review_options_prepare_video),
                                help = stringResource(R.string.review_options_prepare_video_help),
                                checked = options.prepareVideo,
                                onCheckedChange = { onOptionsChange(options.copy(prepareVideo = it)) },
                            )
                        }
                    },
                    onDeviceVoiceChosen = { onOptionsChange(options.copy(voice = NarrationProviderChoice.DEVICE)) },
                )
            }
            Spacer(modifier = Modifier.heightIn(min = 8.dp))
        }
    }
}

/** Shown while the screen reads the game (one parse, a few file checks). */
@Composable
fun ReviewOptionsLoading(onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { AppBarTitle(stringResource(R.string.review_options_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize().padding(innerPadding), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
    }
}

@Composable
private fun OptionsCard(content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        shape = MaterialTheme.shapes.medium,
    ) { content() }
}

/** "Magnus vs Hikaru · 1-0 · 17 moves" (wraps at a large font; names pinned left to right). */
@Composable
private fun GameHeaderLine(header: GameHeader, plies: Int) {
    val moves = (plies + 1) / 2
    Text(
        text = stringResource(
            R.string.review_options_header,
            versusLine(stringResource(R.string.game_vs_format), header.white, header.black),
            "‎" + header.result,
            pluralStringResource(R.plurals.recent_moves_count, moves, moves),
        ),
        style = MaterialTheme.typography.titleMedium.copy(textDirection = TextDirection.Ltr),
        modifier = Modifier.padding(vertical = 4.dp),
    )
}

@StringRes
private fun levelLabelRes(level: EngineLevel): Int = when (level) {
    EngineLevel.L2500 -> R.string.level_label_2500
    EngineLevel.L2700 -> R.string.level_label_2700
    EngineLevel.L2900 -> R.string.level_label_2900
    EngineLevel.L3100 -> R.string.level_label_3100
    EngineLevel.MAX -> R.string.level_label_max
}

@StringRes
private fun levelHintRes(level: EngineLevel): Int = when (level) {
    EngineLevel.L2500 -> R.string.level_hint_2500
    EngineLevel.L2700 -> R.string.level_hint_2700
    EngineLevel.L2900 -> R.string.level_hint_2900
    EngineLevel.L3100 -> R.string.level_hint_3100
    EngineLevel.MAX -> R.string.level_hint_max
}

/**
 * Every [EngineLevel] as a radio row: its name, a one-line description, and the rough time of the whole review on this
 * phone when one was measured (or "ready at once" when this game's evaluations at that level are already on the phone).
 */
@Composable
private fun StrengthChooser(selected: EngineLevel, estimates: Map<EngineLevel, TimeEstimate>, onSelect: (EngineLevel) -> Unit) {
    Column(modifier = Modifier.padding(vertical = 8.dp)) {
        SectionHeading(stringResource(R.string.review_options_strength))
        Column(modifier = Modifier.selectableGroup()) {
            for (level in EngineLevel.entries) {
                val estimate = estimates[level]
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 56.dp)
                        .selectable(selected = level == selected, role = Role.RadioButton) { onSelect(level) }
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = level == selected, onClick = null)
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(text = stringResource(levelLabelRes(level)), style = MaterialTheme.typography.bodyLarge)
                        Text(
                            text = stringResource(levelHintRes(level)),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        estimateText(estimate)?.let {
                            Text(text = it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
        }
    }
}

/**
 * The time line under a strength: "About N min" when this phone measured every part, "Up to about N min" when part of it
 * is only an upper bound (nothing measured yet, or Max), "ready at once" when cached.
 */
@Composable
private fun estimateText(estimate: TimeEstimate?): String? = when {
    estimate == null -> null
    estimate.ms == 0L -> stringResource(R.string.review_options_strength_ready)
    else -> when (val t = timeLeftFor(estimate.ms)) {
        ExportTimeLeft.Hidden, ExportTimeLeft.LessThanMinute ->
            stringResource(if (estimate.upperBound) R.string.review_options_strength_under_minute_upto else R.string.review_options_strength_under_minute)
        is ExportTimeLeft.Minutes -> pluralStringResource(
            if (estimate.upperBound) R.plurals.review_options_strength_minutes_upto else R.plurals.review_options_strength_minutes,
            t.minutes,
            t.minutes,
        )
    }
}

/** "Which side were you?": White / Black / Not me, as on the Summary. Nothing selected = not known yet. */
@Composable
private fun SideChooser(selected: SideChoice, onSelect: (SideChoice) -> Unit) {
    val choices = listOf(
        SideChoice.WHITE to R.string.side_white,
        SideChoice.BLACK to R.string.side_black,
        SideChoice.NOT_ME to R.string.summary_side_neither,
    )
    Column(modifier = Modifier.padding(bottom = 12.dp)) {
        SectionHeading(stringResource(R.string.summary_which_side), stringResource(R.string.review_options_side_help))
        val largeText = LocalDensity.current.fontScale >= LARGE_FONT_SCALE
        if (largeText) {
            Column(modifier = Modifier.selectableGroup().padding(horizontal = 16.dp)) {
                choices.forEach { (choice, label) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .selectable(selected = selected == choice, role = Role.RadioButton) { onSelect(choice) },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = selected == choice, onClick = null)
                        Text(text = stringResource(label), modifier = Modifier.padding(start = 12.dp))
                    }
                }
            }
        } else {
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                choices.forEachIndexed { index, (choice, label) ->
                    SegmentedButton(
                        selected = selected == choice,
                        onClick = { onSelect(choice) },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = choices.size),
                    ) {
                        Text(text = stringResource(label), maxLines = 2, textAlign = TextAlign.Center)
                    }
                }
            }
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF302E2B, heightDp = 1100)
@Composable
private fun ReviewOptionsScreenPreview() {
    val options = ReviewOptions(
        level = EngineLevel.L2900,
        side = SideChoice.WHITE,
        naturalWording = true,
        prepareVideo = true,
        voice = NarrationProviderChoice.NEURAL,
        speakerId = 1,
        pace = VideoPace.NORMAL,
    )
    ChessAnalyzerTheme {
        ReviewOptionsScreen(
            draft = ReviewOptionsDraft(
                gameId = "g",
                header = GameHeader(white = "Morphy", black = "Duke Karl / Count Isouard", result = "1-0"),
                plies = 33,
                options = options,
                wording = WordingOption.SWITCH,
                voiceInstalled = true,
                estimateInputs = ReviewEstimateInputs(
                    positions = 34,
                    plies = 33,
                    speeds = ReviewSpeeds(),
                    cachedLevels = setOf(EngineLevel.L2900),
                    wordingUsable = true,
                    kokoroInstalled = true,
                ),
            ),
            options = options,
            onOptionsChange = {},
            onStart = {},
            onBack = {},
        )
    }
}
