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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import net.palaya.chessanalyzer.R
import net.palaya.chessanalyzer.core.narration.cloud.GoogleCloudVoice
import net.palaya.chessanalyzer.ui.model.EngineSettings
import net.palaya.chessanalyzer.ui.model.AppLocales
import net.palaya.chessanalyzer.ui.model.AppLanguage
import net.palaya.chessanalyzer.ui.model.NarrationProviderChoice
import net.palaya.chessanalyzer.ui.model.NarrationVoiceSettings
import net.palaya.chessanalyzer.ui.model.NeuralModelUiState
import net.palaya.chessanalyzer.ui.model.NeuralVoiceTier
import net.palaya.chessanalyzer.video.VoiceModelProvisioner
import net.palaya.chessanalyzer.ui.theme.ChessAnalyzerTheme

/**
 * Engine configuration + app-level preferences. State is passed in/out via callbacks so a
 * later integration pass can back this with DataStore (the module already depends on
 * `androidx.datastore:datastore-preferences`) without touching the composable's shape.
 */
@Composable
fun SettingsScreen(
    settings: EngineSettings,
    modifier: Modifier = Modifier,
    onSettingsChange: (EngineSettings) -> Unit = {},
    onCheckForUpdates: () -> Unit = {},
    onViewGplNotice: () -> Unit = {},
    onOpenAbout: () -> Unit = {},
    narrationVoiceSettings: NarrationVoiceSettings = NarrationVoiceSettings(),
    onNarrationProviderChange: (NarrationProviderChoice) -> Unit = {},
    /** Total bytes currently held in the persistent narration cache (`filesDir/narration/`). */
    narrationStorageBytes: Long = 0L,
    onClearNarrationStorage: () -> Unit = {},
    neuralModelState: NeuralModelUiState = NeuralModelUiState(),
    onNeuralTierChange: (NeuralVoiceTier) -> Unit = {},
    onDownloadNeuralModel: (NeuralVoiceTier) -> Unit = {},
    onCancelNeuralModelDownload: () -> Unit = {},
    onDeleteNeuralModel: (NeuralVoiceTier) -> Unit = {},
    onCloudVoiceChange: (GoogleCloudVoice) -> Unit = {},
    /** Opens the Google Cloud key wizard ([CloudVoiceSetupScreen]). */
    onOpenCloudSetup: () -> Unit = {},
    onRemoveCloudKey: () -> Unit = {},
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(16.dp),
        ) {
            item {
                SectionHeader(stringResource(R.string.settings_engine_header))
                SettingsCard {
                    LabeledSlider(
                        label = stringResource(R.string.settings_depth),
                        value = settings.depth,
                        valueRange = 6..30,
                        valueLabel = "${settings.depth}",
                        onValueChange = { onSettingsChange(settings.copy(depth = it)) },
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    LabeledSlider(
                        label = stringResource(R.string.settings_time_per_move),
                        value = settings.timePerMoveMs,
                        valueRange = 100..3000,
                        valueLabel = "${settings.timePerMoveMs} ms",
                        step = 100,
                        onValueChange = { onSettingsChange(settings.copy(timePerMoveMs = it)) },
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    LabeledSlider(
                        label = stringResource(R.string.settings_multipv),
                        value = settings.multiPv,
                        valueRange = 1..5,
                        valueLabel = "${settings.multiPv}",
                        onValueChange = { onSettingsChange(settings.copy(multiPv = it)) },
                    )
                }
            }

            item {
                Spacer(modifier = Modifier.height(20.dp))
                SectionHeader(stringResource(R.string.settings_username_header))
                SettingsCard {
                    OutlinedTextField(
                        value = settings.username,
                        onValueChange = { onSettingsChange(settings.copy(username = it)) },
                        label = { Text(stringResource(R.string.settings_username)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = stringResource(R.string.settings_username_help),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            item {
                Spacer(modifier = Modifier.height(20.dp))
                SectionHeader(stringResource(R.string.settings_narration_content_header))
                SettingsCard {
                    NarrationThresholdSlider(
                        thresholdCp = settings.narrationThresholdCp,
                        onThresholdChange = { onSettingsChange(settings.copy(narrationThresholdCp = it)) },
                    )
                }
            }

            item {
                Spacer(modifier = Modifier.height(20.dp))
                SectionHeader(stringResource(R.string.settings_narration_header))
                SettingsCard {
                    NarrationVoiceSection(
                        state = narrationVoiceSettings,
                        onProviderChange = onNarrationProviderChange,
                        neuralModelState = neuralModelState,
                        onNeuralTierChange = onNeuralTierChange,
                        onDownloadNeuralModel = onDownloadNeuralModel,
                        onCancelNeuralModelDownload = onCancelNeuralModelDownload,
                        onDeleteNeuralModel = onDeleteNeuralModel,
                        onCloudVoiceChange = onCloudVoiceChange,
                        onOpenCloudSetup = onOpenCloudSetup,
                        onRemoveCloudKey = onRemoveCloudKey,
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Spacer(modifier = Modifier.height(12.dp))
                    NarrationStorageRow(
                        totalBytes = narrationStorageBytes,
                        onClear = onClearNarrationStorage,
                    )
                }
            }

            item {
                Spacer(modifier = Modifier.height(20.dp))
                SectionHeader(stringResource(R.string.settings_version_header))
                SettingsCard {
                    Text(
                        text = stringResource(R.string.settings_engine_version, settings.engineVersion),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.settings_net_version, settings.netVersion),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    OutlinedButton(onClick = onCheckForUpdates) {
                        Text(stringResource(R.string.settings_check_updates))
                    }
                }
            }

            item {
                Spacer(modifier = Modifier.height(20.dp))
                SectionHeader(stringResource(R.string.settings_language_header))
                SettingsCard {
                    LanguageSection(
                        selected = settings.language,
                        onLanguageChange = { onSettingsChange(settings.copy(language = it)) },
                    )
                }
            }

            item {
                Spacer(modifier = Modifier.height(20.dp))
                SectionHeader(stringResource(R.string.settings_about_header))
                SettingsCard {
                    Text(
                        text = stringResource(R.string.settings_about_body),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    TextButton(onClick = onViewGplNotice) {
                        Text(stringResource(R.string.settings_gpl_notice))
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    OutlinedButton(onClick = onOpenAbout, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.settings_about_open))
                    }
                }
                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }
}

/**
 * The app language. Offers [AppLanguage.entries] — English only today — with the plumbing that a
 * second language plugs into (see [AppLanguage]). Below Android 13 the platform has no per-app
 * language API and this app carries no AppCompat backport, so the choice is shown disabled with
 * the reason rather than silently doing nothing.
 */
@Composable
private fun LanguageSection(selected: AppLanguage, onLanguageChange: (AppLanguage) -> Unit) {
    val supported = AppLocales.isPerAppLanguageSupported
    Column {
        for (language in AppLanguage.entries) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = supported) { onLanguageChange(language) },
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            ) {
                RadioButton(
                    selected = selected == language,
                    onClick = { onLanguageChange(language) },
                    enabled = supported,
                )
                Text(stringResource(language.labelRes), style = MaterialTheme.typography.bodyMedium)
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = stringResource(if (supported) R.string.settings_language_help else R.string.settings_language_requires_android_13),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(bottom = 8.dp),
    )
}

@Composable
private fun SettingsCard(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(modifier = Modifier.padding(16.dp), content = content)
    }
}

@Composable
private fun LabeledSlider(
    label: String,
    value: Int,
    valueRange: IntRange,
    valueLabel: String,
    onValueChange: (Int) -> Unit,
    step: Int = 1,
) {
    Column(modifier = Modifier.padding(vertical = 8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(text = label, style = MaterialTheme.typography.bodyMedium)
            Text(
                text = valueLabel,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Slider(
            value = value.toFloat(),
            onValueChange = { onValueChange((it.toInt() / step) * step) },
            valueRange = valueRange.first.toFloat()..valueRange.last.toFloat(),
        )
    }
}

/**
 * How selective the narrated review is (ANALYSIS_SPEC §9.2).
 *
 * Expressed in **pawns to one decimal**, because that is the unit a chess player thinks in; the
 * stored value is centipawns, so the slider steps in tenths of a pawn and never lands on a value
 * that cannot be displayed exactly. 0.0 is a real, reachable setting and reads "Every move"
 * rather than "±0.0 pawns", since that is what it does.
 */
@Composable
private fun NarrationThresholdSlider(thresholdCp: Int, onThresholdChange: (Int) -> Unit) {
    val valueLabel = if (thresholdCp <= 0) {
        stringResource(R.string.settings_narration_threshold_off)
    } else {
        stringResource(
            R.string.settings_narration_threshold_value,
            String.format(java.util.Locale.ROOT, "%.1f", thresholdCp / 100.0),
        )
    }
    Column {
        LabeledSlider(
            label = stringResource(R.string.settings_narration_threshold),
            value = thresholdCp,
            valueRange = 0..300,
            valueLabel = valueLabel,
            step = 10,
            onValueChange = onThresholdChange,
        )
        Text(
            text = stringResource(R.string.settings_narration_threshold_help),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * "Narration voice" settings: the on-device neural voice (default, free, offline, downloaded once)
 * with Android's built-in device TTS as the always-available floor, and the Google Cloud voice as
 * an opt-in upgrade on the user's own key.
 *
 * The neural option is disabled (with an explanatory line) until its model is actually on disk,
 * and the Cloud option until a key has been saved, so a user can't select a voice that isn't
 * there. [NeuralModelTierPicker] and [CloudVoiceSection] below are where the model download and
 * the key setup live.
 */
@Composable
private fun NarrationVoiceSection(
    state: NarrationVoiceSettings,
    onProviderChange: (NarrationProviderChoice) -> Unit,
    neuralModelState: NeuralModelUiState,
    onNeuralTierChange: (NeuralVoiceTier) -> Unit,
    onDownloadNeuralModel: (NeuralVoiceTier) -> Unit,
    onCancelNeuralModelDownload: () -> Unit,
    onDeleteNeuralModel: (NeuralVoiceTier) -> Unit,
    onCloudVoiceChange: (GoogleCloudVoice) -> Unit,
    onOpenCloudSetup: () -> Unit,
    onRemoveCloudKey: () -> Unit,
) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onProviderChange(NarrationProviderChoice.DEVICE) },
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            RadioButton(selected = state.provider == NarrationProviderChoice.DEVICE, onClick = { onProviderChange(NarrationProviderChoice.DEVICE) })
            Column {
                Text(stringResource(R.string.settings_narration_device), style = MaterialTheme.typography.bodyMedium)
                Text(
                    stringResource(R.string.settings_narration_device_help),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        val neuralUsable = neuralModelState.isInstalled(state.neuralTier)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = neuralUsable) { onProviderChange(NarrationProviderChoice.NEURAL) },
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            RadioButton(
                selected = state.provider == NarrationProviderChoice.NEURAL,
                onClick = { onProviderChange(NarrationProviderChoice.NEURAL) },
                enabled = neuralUsable,
            )
            Column {
                Text(stringResource(R.string.settings_narration_neural), style = MaterialTheme.typography.bodyMedium)
                Text(
                    if (neuralUsable) {
                        stringResource(R.string.settings_narration_neural_help)
                    } else {
                        stringResource(R.string.settings_narration_neural_disabled_help)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        NeuralModelTierPicker(
            state = state,
            neuralModelState = neuralModelState,
            onNeuralTierChange = onNeuralTierChange,
            onDownloadNeuralModel = onDownloadNeuralModel,
            onCancelNeuralModelDownload = onCancelNeuralModelDownload,
            onDeleteNeuralModel = onDeleteNeuralModel,
        )

        Spacer(modifier = Modifier.height(12.dp))

        val cloudUsable = state.hasCloudKey
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = cloudUsable) { onProviderChange(NarrationProviderChoice.CLOUD) },
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            RadioButton(
                selected = state.provider == NarrationProviderChoice.CLOUD,
                onClick = { onProviderChange(NarrationProviderChoice.CLOUD) },
                enabled = cloudUsable,
            )
            Column {
                Text(stringResource(R.string.settings_narration_cloud), style = MaterialTheme.typography.bodyMedium)
                Text(
                    if (cloudUsable) {
                        stringResource(R.string.settings_narration_cloud_help_ready, state.cloudVoice.tier.freeReviewsPerMonth)
                    } else {
                        stringResource(R.string.settings_narration_cloud_help_no_key)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        CloudVoiceSection(
            state = state,
            onCloudVoiceChange = onCloudVoiceChange,
            onOpenCloudSetup = onOpenCloudSetup,
            onRemoveCloudKey = onRemoveCloudKey,
        )
    }
}

/**
 * Google Cloud voice picker + key status. The voice list is the curated [GoogleCloudVoice] set,
 * grouped by language so the Hebrew upgrade path is visible without being confused with the
 * English default. Key handling is deliberately minimal here: status, "Set up"/"Change" (which
 * opens the wizard, the only place a key is entered, because it validates with a real request
 * before saving) and "Remove". The key itself is never displayed.
 */
@Composable
private fun CloudVoiceSection(
    state: NarrationVoiceSettings,
    onCloudVoiceChange: (GoogleCloudVoice) -> Unit,
    onOpenCloudSetup: () -> Unit,
    onRemoveCloudKey: () -> Unit,
) {
    Column(modifier = Modifier.padding(start = 40.dp)) {
        val groups = listOf(
            R.string.settings_narration_cloud_voice_english to GoogleCloudVoice.entries.filter { !it.isHebrew },
            R.string.settings_narration_cloud_voice_hebrew to GoogleCloudVoice.entries.filter { it.isHebrew },
        )
        for ((headerRes, voices) in groups) {
            Text(
                stringResource(headerRes),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, bottom = 2.dp),
            )
            for (voice in voices) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onCloudVoiceChange(voice) },
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                ) {
                    RadioButton(selected = state.cloudVoice == voice, onClick = { onCloudVoiceChange(voice) })
                    Column(modifier = Modifier.weight(1f)) {
                        Text(voice.label, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            stringResource(R.string.settings_narration_cloud_voice_hint, voice.tier.label, voice.tier.freeReviewsPerMonth),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            Text(
                text = when {
                    !state.hasCloudKey -> stringResource(R.string.settings_narration_cloud_key_missing)
                    state.apiKeyIsEncrypted -> stringResource(R.string.settings_narration_cloud_key_saved_encrypted)
                    else -> stringResource(R.string.settings_narration_cloud_key_saved_plain)
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (state.hasCloudKey && !state.apiKeyIsEncrypted) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            if (state.hasCloudKey) {
                TextButton(onClick = onRemoveCloudKey) { Text(stringResource(R.string.settings_narration_cloud_remove)) }
                OutlinedButton(onClick = onOpenCloudSetup) { Text(stringResource(R.string.settings_narration_cloud_change)) }
            } else {
                OutlinedButton(onClick = onOpenCloudSetup) { Text(stringResource(R.string.settings_narration_cloud_set_up)) }
            }
        }
    }
}

/**
 * Per-tier ([NeuralVoiceTier.PIPER] / [NeuralVoiceTier.KOKORO]) model picker + download/delete UI
 * for the on-device neural voice. Each tier shows its size, whether it's downloaded, and either a
 * "Download" button (with live progress once started) or a "Delete" button — the same
 * download-once-keep-forever-under-filesDir shape as [NarrationStorageRow] below, but per model
 * file rather than per cached narration clip. See
 * [net.palaya.chessanalyzer.video.VoiceModelProvisioner] for where these bytes actually live.
 */
@Composable
private fun NeuralModelTierPicker(
    state: NarrationVoiceSettings,
    neuralModelState: NeuralModelUiState,
    onNeuralTierChange: (NeuralVoiceTier) -> Unit,
    onDownloadNeuralModel: (NeuralVoiceTier) -> Unit,
    onCancelNeuralModelDownload: () -> Unit,
    onDeleteNeuralModel: (NeuralVoiceTier) -> Unit,
) {
    // 16dp, not 40dp: the indent has to read as "these belong to Natural voice" while still
    // leaving the label column wide enough that the size line ("98.5 MB download · 150.6 MB on
    // disk") fits without wrapping next to the Download button. Every dp spent here comes
    // straight out of that line, and at 24dp it lost "disk" to a second line.
    Column(modifier = Modifier.padding(start = 16.dp)) {
        for (tier in NeuralVoiceTier.entries) {
            val installed = neuralModelState.isInstalled(tier)
            val downloading = neuralModelState.downloadingTier == tier
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = installed) { onNeuralTierChange(tier) },
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                // weight(1f) so the label column yields space to the action button instead of
                // squeezing it: with a real size on every row ("98.5 MB download · 150.6 MB on
                // disk") the unweighted layout took the button's width and wrapped "Download"
                // one character per line.
                // Top, not CenterVertically: this block is three or four lines tall (title,
                // quality hint, size, and Kokoro's Wi-Fi note), and centring floated the radio
                // down beside the size text instead of beside the title it selects.
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = androidx.compose.ui.Alignment.Top,
                ) {
                    RadioButton(
                        selected = state.neuralTier == tier,
                        onClick = { onNeuralTierChange(tier) },
                        enabled = installed,
                    )
                    // Title and quality hint on separate lines: "Kokoro — Best quality, larger download"
                    // as one bodyMedium string wrapped mid-phrase next to the button, and the size
                    // line then jammed against it. Three short lines read; one long wrapped one didn't.
                    // Nudged down to sit on the title's baseline now the radio is Top-aligned,
                    // and given breathing room so the size line is not jammed against the hint.
                    // The hints stay deliberately terse ("Smaller, faster" / "Best quality"):
                    // the exact figures on the next line already carry the size trade-off, and at
                    // this column width every extra word buys a wrapped line back.
                    Column(
                        modifier = Modifier.weight(1f).padding(top = 12.dp, bottom = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text(tier.label, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            text = tier.qualityHint,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        val spec = VoiceModelProvisioner.specFor(tier)
                        val sizeBytes = neuralModelState.installedSizeBytes[tier] ?: 0L
                        // Real numbers, per tier, in both states: what it costs to fetch and what
                        // it costs to keep. "Not downloaded yet" alone told the user nothing about
                        // whether tapping Download was a 20 MB or a 98 MB decision.
                        Text(
                            text = if (installed) {
                                stringResource(R.string.settings_narration_neural_installed_size, megabytes(sizeBytes))
                            } else {
                                stringResource(
                                    R.string.settings_narration_neural_size,
                                    megabytes(spec.downloadSizeBytes),
                                    megabytes(spec.installedSizeBytes),
                                )
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        // Only Kokoro is withheld on a metered connection (see decideAutoVoice) —
                        // saying so here is the only place the user learns that the automatic
                        // download has a condition attached.
                        if (!installed && tier == NeuralVoiceTier.KOKORO) {
                            Text(
                                text = stringResource(R.string.settings_narration_neural_wifi_only),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                // Tighter than the 24dp default horizontal content padding: the button is the
                // only thing competing with the label column for width, and 12dp keeps
                // "Download" comfortably inside its outline while handing ~24dp back to the
                // size line, which is what stops it wrapping. The 48dp minimum touch height
                // is untouched — this trims padding, not the target.
                val actionPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                when {
                    downloading -> TextButton(
                        onClick = onCancelNeuralModelDownload,
                        contentPadding = actionPadding,
                    ) {
                        Text(stringResource(R.string.settings_narration_neural_cancel))
                    }
                    installed -> TextButton(
                        onClick = { onDeleteNeuralModel(tier) },
                        contentPadding = actionPadding,
                    ) {
                        Text(stringResource(R.string.settings_narration_neural_delete))
                    }
                    else -> OutlinedButton(
                        onClick = { onDownloadNeuralModel(tier) },
                        contentPadding = actionPadding,
                    ) {
                        Text(stringResource(R.string.settings_narration_neural_download))
                    }
                }
            }
            if (downloading) {
                LinearProgressIndicator(
                    progress = { neuralModelState.downloadProgress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 48.dp, end = 8.dp, bottom = 4.dp),
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
        }
        if (neuralModelState.lastError != null) {
            Text(
                text = neuralModelState.lastError,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

/** MB with one decimal, the single formatting used for every model/cache size in Settings. */
private fun megabytes(bytes: Long): String = "%.1f MB".format(bytes / (1024.0 * 1024.0))

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
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        Column {
            val mb = totalBytes / (1024.0 * 1024.0)
            Text(
                text = stringResource(R.string.settings_narration_storage_label, "%.1f MB".format(mb)),
                style = MaterialTheme.typography.bodyMedium,
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
            TextButton(onClick = onClear) {
                Text(stringResource(R.string.settings_narration_storage_clear))
            }
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF302E2B, heightDp = 1200)
@Composable
private fun SettingsScreenPreview() {
    ChessAnalyzerTheme {
        SettingsScreen(settings = EngineSettings())
    }
}
