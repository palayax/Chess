@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package net.palaya.chessanalyzer.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import net.palaya.chessanalyzer.R
import net.palaya.chessanalyzer.ui.a11y.AppBarTitle
import net.palaya.chessanalyzer.ui.a11y.asHeading
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import net.palaya.chessanalyzer.ui.model.AdvancedExpander
import net.palaya.chessanalyzer.ui.model.AnalysisStrength
import net.palaya.chessanalyzer.ui.model.AppLanguage
import net.palaya.chessanalyzer.ui.model.AppLocales
import net.palaya.chessanalyzer.ui.model.EngineSettings
import net.palaya.chessanalyzer.core.narration.VideoPace
import net.palaya.chessanalyzer.ui.model.KOKORO_DEFAULT_SPEAKER_ID
import net.palaya.chessanalyzer.ui.model.NarrationProviderChoice
import net.palaya.chessanalyzer.video.KokoroVoices
import net.palaya.chessanalyzer.video.VoiceSamplePlayer
import net.palaya.chessanalyzer.ui.model.NarrationVoiceSettings
import net.palaya.chessanalyzer.ui.model.ReviewDetail
import net.palaya.chessanalyzer.ui.model.UpdateBlock
import net.palaya.chessanalyzer.ui.model.UpdateRowLine
import net.palaya.chessanalyzer.ui.model.customDepthValue
import net.palaya.chessanalyzer.ui.model.customThresholdValue
import net.palaya.chessanalyzer.ui.model.formatStorageMegabytes
import net.palaya.chessanalyzer.ui.model.providerForVoiceSwitch
import net.palaya.chessanalyzer.ui.model.voiceSwitchIsOn
import net.palaya.chessanalyzer.ui.theme.ChessAnalyzerTheme

/**
 * Settings (UX step U9, `docs/MOBILE_UX_DESIGN.md` §3.2 and §6.7): **Your name**, **Language**
 * (opens a list), **Video** (V1 + V3: "Narrator voice" opens the voice picker, "Pace" is Relaxed /
 * Normal / Brisk), **Advanced** (collapsed; expands in place to four controls), **Share diagnostic
 * log** (F1, the owner's request: sends the on-device log through the share sheet), **About
 * Palaya Chess** and, below it, **Check for updates** (D2e, docs/MODEL_DOWNLOAD_DESIGN.md §1.8: checks
 * only when tapped; the result sheet is `UpdateSheet`).
 *
 * State is passed in and out through callbacks; [onSettingsChange] receives the whole
 * [EngineSettings] snapshot, so the two presets are just `copy(depth = ...)` and
 * `copy(narrationThresholdCp = ...)` and the repository's clamps (depth 6..30, threshold 0..300)
 * are unchanged. The search-line count (MultiPV) is not a user decision and stays at its default.
 */
@Composable
fun SettingsScreen(
    settings: EngineSettings,
    modifier: Modifier = Modifier,
    onSettingsChange: (EngineSettings) -> Unit = {},
    onOpenAbout: () -> Unit = {},
    onBack: (() -> Unit)? = null,
    narrationVoiceSettings: NarrationVoiceSettings = NarrationVoiceSettings(),
    onNarrationProviderChange: (NarrationProviderChoice) -> Unit = {},
    /** Total bytes currently held in the persistent narration cache (`filesDir/narration/`). */
    narrationStorageBytes: Long = 0L,
    onClearNarrationStorage: () -> Unit = {},
    /** F1: shares the on-device diagnostic log; null hides the row (previews). */
    onShareDiagnosticLog: (() -> Unit)? = null,
    /** D2e: what the "Check for updates" row's second line says; null hides the row (previews). */
    updateRowLine: UpdateRowLine? = null,
    /** When the last check finished, null for never. */
    lastUpdateCheckMs: Long? = null,
    /** Why the row is disabled (an analysis, an export, setup), or null. */
    updateBlock: UpdateBlock? = null,
    /** The last start rolled back an update that could not be used (shown once). */
    updateRolledBackNotice: Boolean = false,
    onCheckForUpdates: () -> Unit = {},
    /** V1: whether the natural voice is installed (the picker explains it comes with setup otherwise). */
    voiceInstalled: Boolean = true,
    onNarratorSpeakerChange: (Int) -> Unit = {},
    /** V1: the sample player's state, for the picker's Play / Stop / Preparing buttons. */
    voiceSampleState: VoiceSamplePlayer.State = VoiceSamplePlayer.State.Idle,
    onPlayVoiceSample: (Int) -> Unit = {},
    onStopVoiceSample: () -> Unit = {},
    /** The picker closed: stop the sample and free the voice engine. */
    onVoicePickerClosed: () -> Unit = {},
    /** C2: the "Commentary" section (Natural wording), placed between the name and Video; null hides it. */
    commentarySection: (@Composable () -> Unit)? = null,
) {
    var advanced by rememberSaveable(stateSaver = AdvancedExpanderSaver) { mutableStateOf(AdvancedExpander()) }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { AppBarTitle(stringResource(R.string.settings_title)) },
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
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "name") {
                SettingsCard {
                    Column(modifier = Modifier.padding(16.dp)) {
                        val usernameLabel = stringResource(R.string.settings_username)
                        // A floating label is one line tall; at a large font it wraps and pokes out of the
                        // card. From scale 1.3 the label becomes a plain line above the field (the field
                        // keeps it as its TalkBack name).
                        val labelAbove = LocalDensity.current.fontScale >= LARGE_FONT_SCALE
                        if (labelAbove) {
                            Text(
                                text = usernameLabel,
                                style = MaterialTheme.typography.titleSmall.copy(textDirection = TextDirection.Content),
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(bottom = 8.dp),
                            )
                        }
                        OutlinedTextField(
                            value = settings.username,
                            onValueChange = { onSettingsChange(settings.copy(username = it)) },
                            label = if (labelAbove) null else ({ Text(usernameLabel) }),
                            modifier = Modifier
                                .fillMaxWidth()
                                .semantics { if (labelAbove) contentDescription = usernameLabel },
                            singleLine = true,
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = stringResource(R.string.settings_username_help),
                            style = MaterialTheme.typography.bodySmall.copy(textDirection = TextDirection.Content),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            if (commentarySection != null) item(key = "commentary") {
                SettingsCard { commentarySection() }
            }

            // Hidden while English is the only language (owner, 2026-10-08: Hebrew and RTL wait for a later
            // version); it comes back by itself once AppLanguage has a second language.
            if (AppLanguage.entries.size > 2) item(key = "language") {
                SettingsCard {
                    LanguageRow(
                        selected = settings.language,
                        onLanguageChange = { onSettingsChange(settings.copy(language = it)) },
                    )
                }
            }

            item(key = "video") {
                SettingsCard {
                    VideoSection(
                        settings = settings,
                        onSettingsChange = onSettingsChange,
                        narrationVoiceSettings = narrationVoiceSettings,
                        voiceInstalled = voiceInstalled,
                        onNarratorSpeakerChange = onNarratorSpeakerChange,
                        voiceSampleState = voiceSampleState,
                        onPlayVoiceSample = onPlayVoiceSample,
                        onStopVoiceSample = onStopVoiceSample,
                        onVoicePickerClosed = onVoicePickerClosed,
                    )
                }
            }

            item(key = "advanced") {
                SettingsCard {
                    Column {
                        AdvancedHeaderRow(expanded = advanced.expanded, onToggle = { advanced = advanced.toggled() })
                        if (advanced.expanded) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            Column(
                                modifier = Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(20.dp),
                            ) {
                                AnalysisStrengthControl(
                                    depth = settings.depth,
                                    onChange = { onSettingsChange(settings.copy(depth = it.depth)) },
                                )
                                ReviewDetailControl(
                                    thresholdCp = settings.narrationThresholdCp,
                                    onChange = { onSettingsChange(settings.copy(narrationThresholdCp = it.thresholdCp)) },
                                )
                                DeviceVoiceSwitchRow(
                                    provider = narrationVoiceSettings.provider,
                                    onProviderChange = onNarrationProviderChange,
                                )
                                NarrationStorageRow(
                                    totalBytes = narrationStorageBytes,
                                    onClear = onClearNarrationStorage,
                                )
                            }
                        }
                    }
                }
            }

            if (onShareDiagnosticLog != null) {
                item(key = "diagnostic-log") {
                    SettingsCard {
                        SettingsRow(
                            title = stringResource(R.string.settings_share_log),
                            // Says where the log is before anyone taps: on this phone, until shared.
                            supporting = stringResource(R.string.settings_share_log_help),
                            onClick = onShareDiagnosticLog,
                        )
                    }
                }
            }

            item(key = "about") {
                SettingsCard {
                    SettingsRow(
                        title = stringResource(R.string.settings_about_open),
                        supporting = null,
                        onClick = onOpenAbout,
                    )
                }
            }

            if (updateRowLine != null) {
                item(key = "updates") {
                    SettingsCard {
                        Column {
                            val supporting = when (updateRowLine) {
                                UpdateRowLine.CHECKING -> stringResource(R.string.update_row_checking)
                                UpdateRowLine.INSTALLING -> stringResource(R.string.update_row_installing)
                                UpdateRowLine.BLOCKED -> stringResource(updateBlockText(updateBlock ?: UpdateBlock.SETUP))
                                UpdateRowLine.LAST_CHECKED -> stringResource(
                                    R.string.update_row_supporting,
                                    lastUpdateCheckMs?.let { formatLastChecked(it) } ?: stringResource(R.string.update_last_checked_never),
                                )
                            }
                            SettingsRow(
                                title = stringResource(R.string.update_row_title),
                                supporting = supporting,
                                // A running check or install can always be looked at; a new one waits for the block.
                                enabled = updateBlock == null || updateRowLine == UpdateRowLine.CHECKING || updateRowLine == UpdateRowLine.INSTALLING,
                                onClick = onCheckForUpdates,
                            )
                            if (updateRolledBackNotice) {
                                Text(
                                    text = stringResource(R.string.update_rolled_back),
                                    style = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.Content),
                                    color = MaterialTheme.colorScheme.error,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(start = 16.dp, end = 16.dp, bottom = 12.dp)
                                        .semantics { liveRegion = LiveRegionMode.Polite },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** "7 Oct 2026, 14:05" in the user's locale. */
private fun formatLastChecked(ms: Long): String =
    java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT).format(java.util.Date(ms))

/** Survives rotation and process death: collapsed by default, remembered as one boolean. */
private val AdvancedExpanderSaver = Saver<AdvancedExpander, Boolean>(
    save = { it.expanded },
    restore = { AdvancedExpander(it) },
)

@Composable
private fun SettingsCard(content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        shape = MaterialTheme.shapes.medium,
    ) {
        content()
    }
}

/** A tappable row with a title, an optional second line and a chevron that mirrors in RTL. */
@Composable
private fun SettingsRow(
    title: String,
    supporting: String?,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else 0.6f)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .heightIn(min = 56.dp)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.titleMedium)
            if (supporting != null) {
                Text(
                    text = supporting,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun AdvancedHeaderRow(expanded: Boolean, onToggle: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onToggle)
            .heightIn(min = 56.dp)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.settings_advanced),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(1f).asHeading(),
        )
        Icon(
            imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
            contentDescription = stringResource(
                if (expanded) R.string.settings_advanced_collapse else R.string.settings_advanced_expand,
            ),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * The app language as one row that opens a list. Offers [AppLanguage.entries] (English only
 * today). Below Android 13 the platform has no per-app language API and this app carries no
 * AppCompat backport, so the row is shown disabled with the reason rather than silently doing
 * nothing.
 */
@Composable
private fun LanguageRow(selected: AppLanguage, onLanguageChange: (AppLanguage) -> Unit) {
    val supported = AppLocales.isPerAppLanguageSupported
    var dialogOpen by rememberSaveable { mutableStateOf(false) }

    SettingsRow(
        title = stringResource(R.string.settings_language_header),
        supporting = if (supported) {
            stringResource(selected.labelRes)
        } else {
            stringResource(R.string.settings_language_requires_android_13)
        },
        onClick = { dialogOpen = true },
        enabled = supported,
    )

    if (dialogOpen && supported) {
        AlertDialog(
            onDismissRequest = { dialogOpen = false },
            title = { Text(stringResource(R.string.settings_language_header)) },
            text = {
                Column {
                    for (language in AppLanguage.entries) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .selectable(selected = selected == language, role = Role.RadioButton) {
                                    dialogOpen = false
                                    if (language != selected) onLanguageChange(language)
                                },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = selected == language, onClick = null)
                            Text(
                                text = stringResource(language.labelRes),
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.padding(start = 12.dp, top = 12.dp, bottom = 12.dp),
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.settings_language_help),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { dialogOpen = false }) {
                    Text(stringResource(R.string.settings_language_close))
                }
            },
        )
    }
}

/**
 * V1 + V3: the Video section. "Narrator voice" opens the picker; "Pace" is Relaxed / Normal / Brisk
 * (ANALYSIS_SPEC 9.8), and goes through [onSettingsChange] like the other presets. V4: also the content of
 * the Video screen's "Voice and pace" sheet (the same component, under that [heading]).
 */
@Composable
internal fun VideoSection(
    settings: EngineSettings,
    onSettingsChange: (EngineSettings) -> Unit,
    narrationVoiceSettings: NarrationVoiceSettings,
    voiceInstalled: Boolean,
    onNarratorSpeakerChange: (Int) -> Unit,
    voiceSampleState: VoiceSamplePlayer.State,
    onPlayVoiceSample: (Int) -> Unit,
    onStopVoiceSample: () -> Unit,
    onVoicePickerClosed: () -> Unit,
    heading: String = stringResource(R.string.settings_video_header),
) {
    var pickerOpen by rememberSaveable { mutableStateOf(false) }
    Column {
        Text(
            text = heading,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier
                .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp)
                .asHeading(),
        )
        val chosen = voiceLabel(narrationVoiceSettings.speakerId)
        SettingsRow(
            title = stringResource(R.string.settings_voice_row),
            supporting = when {
                narrationVoiceSettings.provider == NarrationProviderChoice.DEVICE -> stringResource(R.string.settings_voice_row_device)
                !voiceInstalled -> stringResource(R.string.settings_voice_row_not_installed, chosen)
                else -> chosen
            },
            onClick = { pickerOpen = true },
        )
        Column(modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 16.dp)) {
            PresetControl(
                title = stringResource(R.string.settings_pace),
                help = stringResource(R.string.settings_pace_help),
                options = listOf(
                    VideoPace.RELAXED to stringResource(R.string.settings_pace_relaxed),
                    VideoPace.NORMAL to stringResource(R.string.settings_pace_normal),
                    VideoPace.BRISK to stringResource(R.string.settings_pace_brisk),
                ),
                selected = settings.videoPace,
                customLabel = null,
                onSelect = { onSettingsChange(settings.copy(videoPace = it)) },
            )
        }
    }
    if (pickerOpen) {
        val close = {
            pickerOpen = false
            onVoicePickerClosed()
        }
        VoicePickerDialog(
            selected = narrationVoiceSettings.speakerId,
            voiceInstalled = voiceInstalled,
            deviceVoiceOn = narrationVoiceSettings.provider == NarrationProviderChoice.DEVICE,
            sampleState = voiceSampleState,
            onSelect = onNarratorSpeakerChange,
            onPlay = onPlayVoiceSample,
            onStop = onStopVoiceSample,
            onDismiss = close,
        )
    }
}

/** "Bella · American, female": the name (the blend has none of its own), the accent and the voice. */
@Composable
private fun voiceLabel(sid: Int): String {
    val speaker = KokoroVoices.speaker(sid) ?: KokoroVoices.SPEAKERS[KOKORO_DEFAULT_SPEAKER_ID]
    return stringResource(
        R.string.voice_label,
        speaker.displayName ?: stringResource(R.string.voice_name_blend),
        stringResource(
            when (speaker.accent) {
                KokoroVoices.Accent.AMERICAN -> R.string.voice_accent_american
                KokoroVoices.Accent.BRITISH -> R.string.voice_accent_british
            },
        ),
        stringResource(
            when (speaker.gender) {
                KokoroVoices.Gender.FEMALE -> R.string.voice_gender_female
                KokoroVoices.Gender.MALE -> R.string.voice_gender_male
            },
        ),
    )
}

/**
 * The narrator voice picker (V1). Follows the pattern of chess.com's coach picker (reference only):
 * one list, each entry its name and a short descriptor, a way to hear it before choosing, the current
 * one marked; tapping an entry chooses it at once and the list stays open, so several can be compared.
 * Our own words and no art. One sample plays at a time (the player stops the previous one).
 */
@Composable
private fun VoicePickerDialog(
    selected: Int,
    voiceInstalled: Boolean,
    deviceVoiceOn: Boolean,
    sampleState: VoiceSamplePlayer.State,
    onSelect: (Int) -> Unit,
    onPlay: (Int) -> Unit,
    onStop: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.voice_picker_title)) },
        text = {
            LazyColumn {
                item(key = "help") {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(bottom = 8.dp)) {
                        Text(
                            text = stringResource(R.string.voice_picker_help),
                            style = MaterialTheme.typography.bodySmall.copy(textDirection = TextDirection.Content),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (!voiceInstalled) {
                            Text(
                                text = stringResource(R.string.voice_picker_not_installed),
                                style = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.Content),
                                color = MaterialTheme.colorScheme.primary,
                            )
                        } else if (deviceVoiceOn) {
                            Text(
                                text = stringResource(R.string.voice_picker_device_on),
                                style = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.Content),
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
                for (sid in KokoroVoices.PICKER_ORDER) {
                    item(key = "voice-$sid") {
                        VoicePickerRow(
                            sid = sid,
                            selected = sid == selected,
                            canPlay = voiceInstalled,
                            sampleState = sampleState,
                            onSelect = { onSelect(sid) },
                            onPlay = { onPlay(sid) },
                            onStop = onStop,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.voice_picker_done)) }
        },
    )
}

@Composable
private fun VoicePickerRow(
    sid: Int,
    selected: Boolean,
    canPlay: Boolean,
    sampleState: VoiceSamplePlayer.State,
    onSelect: () -> Unit,
    onPlay: () -> Unit,
    onStop: () -> Unit,
) {
    val label = voiceLabel(sid)
    val isDefault = sid == KOKORO_DEFAULT_SPEAKER_ID
    val preparing = sampleState is VoiceSamplePlayer.State.Preparing && sampleState.sid == sid
    val playing = sampleState is VoiceSamplePlayer.State.Playing && sampleState.sid == sid
    val failed = sampleState is VoiceSamplePlayer.State.Failed && sampleState.sid == sid
    // At a large font the name and the button do not fit side by side: the button goes under the name.
    val stacked = LocalDensity.current.fontScale >= LARGE_FONT_SCALE
    val selectRow: @Composable (Modifier) -> Unit = { mod ->
        Row(
            modifier = mod
                .heightIn(min = 48.dp)
                .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = selected, onClick = null)
            Column(modifier = Modifier.padding(start = 12.dp, top = 6.dp, bottom = 6.dp)) {
                Text(text = label, style = MaterialTheme.typography.bodyLarge.copy(textDirection = TextDirection.Content))
                if (isDefault) {
                    Text(
                        text = stringResource(R.string.voice_default_tag),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                if (failed) {
                    Text(
                        text = stringResource(R.string.voice_sample_failed),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }
            }
        }
    }
    val button: @Composable () -> Unit = {
        val cd = stringResource(if (playing) R.string.cd_voice_sample_stop else R.string.cd_voice_sample_play, label)
        TextButton(
            onClick = { if (playing || preparing) onStop() else onPlay() },
            enabled = canPlay,
            modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = cd },
        ) {
            Text(
                stringResource(
                    when {
                        preparing -> R.string.voice_sample_preparing
                        playing -> R.string.voice_sample_stop
                        else -> R.string.voice_sample_play
                    },
                ),
            )
        }
    }
    if (stacked) {
        Column(modifier = Modifier.fillMaxWidth()) {
            selectRow(Modifier.fillMaxWidth())
            Row(modifier = Modifier.padding(start = 36.dp)) { button() }
        }
    } else {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            selectRow(Modifier.weight(1f))
            button()
        }
    }
}

/** "Analysis strength": Quick / Standard / Deep. A stored depth outside those shows "Custom (N)". */
@Composable
private fun AnalysisStrengthControl(depth: Int, onChange: (AnalysisStrength) -> Unit) {
    val preset = AnalysisStrength.fromDepth(depth)
    PresetControl(
        title = stringResource(R.string.settings_depth),
        help = stringResource(R.string.settings_depth_help),
        options = listOf(
            AnalysisStrength.QUICK to stringResource(R.string.settings_depth_quick),
            AnalysisStrength.STANDARD to stringResource(R.string.settings_depth_standard),
            AnalysisStrength.DEEP to stringResource(R.string.settings_depth_deep),
        ),
        selected = preset,
        customLabel = if (preset == null) stringResource(R.string.settings_custom_value, customDepthValue(depth)) else null,
        onSelect = onChange,
    )
}

/**
 * "What the review talks about": Only big moments / Balanced / Every move. The help says in plain
 * words that the same setting also decides which tactics the summary lists (the significance gate
 * drives both).
 */
@Composable
private fun ReviewDetailControl(thresholdCp: Int, onChange: (ReviewDetail) -> Unit) {
    val preset = ReviewDetail.fromThresholdCp(thresholdCp)
    PresetControl(
        title = stringResource(R.string.settings_narration_threshold),
        help = stringResource(R.string.settings_narration_threshold_help),
        options = listOf(
            ReviewDetail.ONLY_BIG_MOMENTS to stringResource(R.string.settings_threshold_big),
            ReviewDetail.BALANCED to stringResource(R.string.settings_threshold_balanced),
            ReviewDetail.EVERY_MOVE to stringResource(R.string.settings_threshold_all),
        ),
        selected = preset,
        customLabel = if (preset == null) {
            stringResource(R.string.settings_custom_value, customThresholdValue(thresholdCp))
        } else null,
        onSelect = onChange,
    )
}

/**
 * A title, a single-choice control and a help line. At normal text size the control is a segmented
 * row; at large font scales three labels no longer fit side by side (a word would break in the
 * middle), so it becomes a vertical list of radio rows instead (the design's "large font" rule).
 *
 * When [customLabel] is non-null the stored value is not one of [options]: no preset is selected
 * and a "Custom (N)" line states what is stored, so the screen never claims a preset that is not
 * what is stored (and never rewrites it unless the user picks one).
 */
@Composable
private fun <T> PresetControl(
    title: String,
    help: String,
    options: List<Pair<T, String>>,
    selected: T?,
    customLabel: String?,
    onSelect: (T) -> Unit,
) {
    val largeText = LocalDensity.current.fontScale >= LARGE_FONT_SCALE
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall.copy(textDirection = TextDirection.Content),
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.asHeading(),
        )
        if (largeText) {
            Column {
                options.forEach { (option, label) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .selectable(selected = selected == option, role = Role.RadioButton) { onSelect(option) },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = selected == option, onClick = null)
                        Text(
                            text = label,
                            style = MaterialTheme.typography.bodyLarge.copy(textDirection = TextDirection.Content),
                            modifier = Modifier.padding(start = 12.dp),
                        )
                    }
                }
            }
        } else {
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                options.forEachIndexed { index, (option, label) ->
                    SegmentedButton(
                        selected = selected == option,
                        onClick = { onSelect(option) },
                        modifier = Modifier.fillMaxHeight(),
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                    ) {
                        Text(text = label, maxLines = 2, textAlign = TextAlign.Center)
                    }
                }
            }
        }
        if (customLabel != null) {
            Text(
                text = customLabel,
                style = MaterialTheme.typography.labelLarge.copy(textDirection = TextDirection.Content),
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Text(
            text = help,
            style = MaterialTheme.typography.bodySmall.copy(textDirection = TextDirection.Content),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** From this font scale up, three preset labels do not fit side by side on a phone. */
private const val LARGE_FONT_SCALE = 1.3f

/**
 * "Use the phone's built-in voice instead". Off means the bundled natural voice (NEURAL); on
 * writes DEVICE. Either way it goes through `setProvider`, so `providerExplicitlyChosen` latches.
 */
@Composable
private fun DeviceVoiceSwitchRow(
    provider: NarrationProviderChoice,
    onProviderChange: (NarrationProviderChoice) -> Unit,
) {
    val checked = voiceSwitchIsOn(provider)
    // The whole row is the switch (label, state and a 48 dp+ target in one TalkBack stop). Before, the
    // Switch stood alone with no label, so TalkBack read "switch, off" with no idea what it switched.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .toggleable(
                value = checked,
                role = Role.Switch,
                onValueChange = { onProviderChange(providerForVoiceSwitch(it)) },
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.settings_voice_use_device),
            style = MaterialTheme.typography.bodyLarge.copy(textDirection = TextDirection.Content),
            modifier = Modifier.weight(1f),
        )
        Switch(
            checked = checked,
            onCheckedChange = null,
            modifier = Modifier.padding(start = 12.dp),
        )
    }
}

/**
 * Shows how much pre-generated narration audio is sitting in the persistent, `filesDir`-backed
 * store ([net.palaya.chessanalyzer.video.NarrationStore]) — audio the user already spent
 * minutes of synthesis on — and lets them reclaim that space on purpose. The store is never cleared
 * automatically (that's the whole point of keeping it out of `cacheDir`), so this is the one place
 * a user can give it back deliberately instead of losing it silently to OS storage pressure.
 */
@Composable
private fun NarrationStorageRow(totalBytes: Long, onClear: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.settings_narration_storage_label, formatStorageMegabytes(totalBytes)),
                style = MaterialTheme.typography.bodyLarge.copy(textDirection = TextDirection.Content),
            )
            if (totalBytes <= 0L) {
                Text(
                    text = stringResource(R.string.settings_narration_storage_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (totalBytes > 0L) {
            val clearLabel = stringResource(R.string.cd_settings_storage_clear)
            TextButton(
                onClick = onClear,
                modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = clearLabel },
            ) {
                Text(stringResource(R.string.settings_narration_storage_clear))
            }
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF302E2B, heightDp = 900)
@Composable
private fun SettingsScreenPreview() {
    ChessAnalyzerTheme {
        SettingsScreen(settings = EngineSettings())
    }
}
