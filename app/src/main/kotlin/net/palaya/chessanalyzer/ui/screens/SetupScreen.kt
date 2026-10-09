@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package net.palaya.chessanalyzer.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.launch
import net.palaya.chessanalyzer.R
import net.palaya.chessanalyzer.data.models.ModelDownloadService
import net.palaya.chessanalyzer.data.models.ModelFile
import net.palaya.chessanalyzer.data.models.SetupState
import net.palaya.chessanalyzer.ui.a11y.AppBarTitle
import net.palaya.chessanalyzer.ui.a11y.asHeading
import net.palaya.chessanalyzer.ui.model.SetupAction
import net.palaya.chessanalyzer.ui.model.SetupFileRow
import net.palaya.chessanalyzer.ui.model.SetupLine
import net.palaya.chessanalyzer.ui.model.SetupPhase
import net.palaya.chessanalyzer.ui.model.SetupPrecheck
import net.palaya.chessanalyzer.ui.model.SetupSizes
import net.palaya.chessanalyzer.ui.model.SetupView
import net.palaya.chessanalyzer.ui.model.aboutMegabytes
import net.palaya.chessanalyzer.ui.model.downloadTotalBytes
import net.palaya.chessanalyzer.ui.model.installedFootprintBytes
import net.palaya.chessanalyzer.ui.model.megabytesLabel
import net.palaya.chessanalyzer.ui.model.gigabytesLabel
import net.palaya.chessanalyzer.ui.model.percentOf
import net.palaya.chessanalyzer.ui.model.progressMegabytes
import net.palaya.chessanalyzer.ui.model.setupView
import net.palaya.chessanalyzer.ui.theme.ChessAnalyzerTheme
import net.palaya.chessanalyzer.ui.viewmodel.SetupViewModel

/**
 * First-run setup (D2c, docs/MODEL_DOWNLOAD_DESIGN.md §1.2, §1.3): one screen, one filled button,
 * a text link to leave. It states both sizes before anything is fetched, asks before using mobile data,
 * and shows the download's progress, pauses and failures in our own words. The download itself is
 * [ModelDownloadService]: it keeps going when this screen or the app is left.
 *
 * References (owner's rule, CLAUDE.md; recorded in RUN_LOG D2c): chess.com's onboarding (one primary
 * action per screen with a secondary text link) and its gated-feature card that says what you get
 * with one button; Google Play's download pattern (size stated before the tap, a Wi-Fi preference,
 * pause and cancel in the notification); Material 3's determinate linear progress with a text label.
 */
@Composable
fun SetupScreen(
    viewModel: SetupViewModel,
    gameWaiting: Boolean,
    /** "Not now" / "Continue in the background": to Home (or back where Setup was opened from). */
    onLeave: () -> Unit,
    /** "Continue" once everything is installed. */
    onContinue: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val disk by viewModel.disk.collectAsState()
    val running by ModelDownloadService.running.collectAsState()
    val progress by ModelDownloadService.progress.collectAsState()
    val precheckError by viewModel.precheckError.collectAsState()
    var askMetered by rememberSaveable { mutableStateOf(false) }

    // Once per visit: an installed net is checked against its pin, so a damaged one is offered again.
    LaunchedEffect(Unit) { viewModel.refresh(verifyNet = true) }

    // POST_NOTIFICATIONS (API 33+) is asked for on the tap, like the export does, and never gates the
    // download: the callback fires on grant, on denial, and at once when the system shows no dialog.
    var startAfterPermission by remember { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { _ ->
        startAfterPermission = true
    }
    LaunchedEffect(startAfterPermission) {
        if (startAfterPermission) {
            startAfterPermission = false
            viewModel.start(context)
        }
    }
    fun startNow() {
        val ask = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        if (ask) permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) else viewModel.start(context)
    }

    val view = setupView(disk, viewModel.sizes, running, progress)
    SetupScreenContent(
        view = view,
        sizes = viewModel.sizes,
        disk = disk,
        precheckError = precheckError.takeIf { !running },
        storageNeededBytes = viewModel.storageNeededBytes(),
        gameWaiting = gameWaiting,
        rephraseOffered = viewModel.rephraseOffered,
        onRephraseWanted = { viewModel.setRephraseWanted(it) },
        onPrimary = { action ->
            if (action == SetupAction.CONTINUE) {
                onContinue()
            } else {
                scope.launch {
                    when (viewModel.precheck()) {
                        SetupPrecheck.START -> startNow()
                        SetupPrecheck.ASK_METERED -> askMetered = true
                        SetupPrecheck.NO_NETWORK, SetupPrecheck.LOW_STORAGE -> Unit
                    }
                }
            }
        },
        onPause = { viewModel.pause() },
        onCancel = { viewModel.cancel(context) },
        onLeave = onLeave,
        modifier = modifier,
    )

    if (askMetered) {
        AlertDialog(
            onDismissRequest = { askMetered = false },
            title = { Text(stringResource(R.string.setup_metered_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.setup_metered_body,
                        megabytesLabel(aboutMegabytes(view.bytesToDownload.takeIf { it > 0 } ?: downloadTotalBytes(disk, viewModel.sizes))),
                    ),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        askMetered = false
                        startNow()
                    },
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text(stringResource(R.string.setup_metered_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { askMetered = false }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.setup_not_now))
                }
            },
        )
    }
}

/** Stateless content, previewable. [precheckError] replaces the status line until the next tap. */
@Composable
fun SetupScreenContent(
    view: SetupView,
    sizes: SetupSizes,
    disk: SetupState,
    precheckError: SetupPrecheck?,
    storageNeededBytes: Long,
    gameWaiting: Boolean,
    onPrimary: (SetupAction) -> Unit,
    onPause: () -> Unit,
    onCancel: () -> Unit,
    onLeave: () -> Unit,
    modifier: Modifier = Modifier,
    /** C2: the optional wording model is offered on this phone (the check box below the files). */
    rephraseOffered: Boolean = false,
    onRephraseWanted: (Boolean) -> Unit = {},
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { AppBarTitle(stringResource(R.string.setup_title)) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { innerPadding ->
        // One scrolling column in every orientation (there is no picture to put beside the controls),
        // capped in width so a landscape phone or a tablet does not stretch the buttons edge to edge.
        Box(
            modifier = Modifier.fillMaxSize().padding(innerPadding),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 560.dp)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                KnightIcon(modifier = Modifier.size(56.dp))
                Spacer(Modifier.height(12.dp))
                Text(
                    text = stringResource(R.string.setup_headline),
                    style = MaterialTheme.typography.titleLarge.copy(textDirection = TextDirection.Content),
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.asHeading(),
                )
                if (view.phase == SetupPhase.INTRO) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = if (disk.netInstalled) {
                            stringResource(R.string.setup_voice_only_body, megabytesLabel(aboutMegabytes(sizes.voiceBytes)))
                        } else if (disk.voiceInstalled) {
                            // Only the net is missing (it was removed or damaged after setup): do not ask for two files.
                            stringResource(R.string.setup_net_only_body, megabytesLabel(aboutMegabytes(sizes.netBytes)))
                        } else {
                            stringResource(
                                R.string.setup_intro_body,
                                megabytesLabel(aboutMegabytes(sizes.netBytes)),
                                megabytesLabel(aboutMegabytes(sizes.voiceBytes)),
                                megabytesLabel(aboutMegabytes(sizes.netBytes + sizes.voiceBytes)),
                            )
                        },
                        style = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.Content),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
                if (gameWaiting && !disk.netInstalled) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.setup_game_waiting),
                        style = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.Content),
                        color = MaterialTheme.colorScheme.primary,
                        textAlign = TextAlign.Center,
                    )
                }
                Spacer(Modifier.height(20.dp))

                FileRowsCard(view.rows)
                Spacer(Modifier.height(16.dp))
                if (rephraseOffered && view.phase == SetupPhase.INTRO && !disk.rephraseInstalled) {
                    RephraseOfferRow(
                        checked = disk.rephraseWanted,
                        sizeBytes = sizes.rephraseBytes,
                        onCheckedChange = onRephraseWanted,
                    )
                    Spacer(Modifier.height(16.dp))
                }

                StatusBlock(view = view, sizes = sizes, disk = disk, precheckError = precheckError, storageNeededBytes = storageNeededBytes)

                Spacer(Modifier.height(20.dp))
                val primary = if (precheckError != null) SetupAction.TRY_AGAIN else view.primary
                if (primary != null) {
                    Button(
                        onClick = { onPrimary(if (precheckError != null) view.primary ?: SetupAction.DOWNLOAD else primary) },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    ) {
                        Text(
                            text = when (primary) {
                                SetupAction.DOWNLOAD -> stringResource(R.string.setup_download, megabytesLabel(aboutMegabytes(view.bytesToDownload)))
                                SetupAction.DOWNLOAD_VOICE -> stringResource(R.string.setup_download_voice, megabytesLabel(aboutMegabytes(view.bytesToDownload)))
                                SetupAction.RESUME -> stringResource(R.string.setup_resume)
                                SetupAction.TRY_AGAIN -> stringResource(R.string.setup_try_again)
                                SetupAction.CONTINUE -> stringResource(R.string.setup_continue)
                            },
                            textAlign = TextAlign.Center,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                }
                if (view.canPause || view.canCancel) {
                    // Wraps onto two lines at a large font instead of clipping a label.
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (view.canPause) {
                            OutlinedButton(onClick = onPause, modifier = Modifier.heightIn(min = 48.dp)) {
                                Text(stringResource(R.string.setup_pause))
                            }
                        }
                        if (view.canCancel) {
                            OutlinedButton(onClick = onCancel, modifier = Modifier.heightIn(min = 48.dp)) {
                                Text(stringResource(R.string.setup_cancel))
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }
                if (view.phase != SetupPhase.DONE) {
                    TextButton(onClick = onLeave, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(
                            stringResource(if (view.phase == SetupPhase.RUNNING) R.string.setup_background else R.string.setup_not_now),
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
        }
    }
}

/**
 * C2 (owner decision §12.3): the optional third download, off unless ticked. Reference (owner's rule): Google Play's
 * optional add-on pattern and chess.com's gated-feature card: what you get, its size, and that it is optional, in one
 * row; the check box and its words are one 48 dp target and one TalkBack stop.
 */
@Composable
private fun RephraseOfferRow(checked: Boolean, sizeBytes: Long, onCheckedChange: (Boolean) -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        shape = MaterialTheme.shapes.large,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .toggleable(value = checked, role = Role.Checkbox, onValueChange = onCheckedChange)
                .heightIn(min = 48.dp)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked = checked, onCheckedChange = null)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.setup_rephrase_offer, gigabytesLabel(sizeBytes)),
                    style = MaterialTheme.typography.titleSmall.copy(textDirection = TextDirection.Content),
                )
                Text(
                    text = stringResource(R.string.setup_rephrase_offer_help),
                    style = MaterialTheme.typography.bodySmall.copy(textDirection = TextDirection.Content),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** The two files, each with its size (before) or its progress (during) or a check (installed). */
@Composable
private fun FileRowsCard(rows: List<SetupFileRow>) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        shape = MaterialTheme.shapes.large,
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            rows.forEach { row -> FileRowItem(row) }
        }
    }
}

@Composable
private fun FileRowItem(row: SetupFileRow) {
    val label = stringResource(
        when (row.file) {
            ModelFile.NET -> R.string.setup_file_net
            ModelFile.VOICE -> R.string.setup_file_voice
            ModelFile.REPHRASE -> R.string.setup_file_rephrase
        },
    )
    val value = when {
        row.installed -> stringResource(R.string.setup_file_done)
        row.active || row.bytesDone > 0 -> {
            val (done, total) = progressMegabytes(row.bytesDone, row.bytesTotal)
            stringResource(R.string.setup_file_progress, megabytesLabel(done), megabytesLabel(total))
        }
        else -> stringResource(R.string.setup_file_about, megabytesLabel(aboutMegabytes(row.bytesTotal)))
    }
    // One stop for TalkBack: "Chess engine data, Done" / "Narration voice, 21 MB of 158 MB".
    val rowModifier = Modifier
        .fillMaxWidth()
        .heightIn(min = 48.dp)
        .clearAndSetSemantics { contentDescription = "$label, $value" }
    val valueContent: @Composable () -> Unit = {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (row.installed) {
                Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(6.dp))
            }
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                color = if (row.installed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    // From a 1.3 font the label and the value stack (CLAUDE.md: a name beside a value never squeezes).
    if (LocalDensity.current.fontScale >= 1.3f) {
        Column(modifier = rowModifier.padding(vertical = 6.dp), verticalArrangement = Arrangement.Center) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            valueContent()
        }
    } else {
        Row(modifier = rowModifier, verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f).padding(end = 12.dp))
            valueContent()
        }
    }
    if (row.active && !row.installed) {
        LinearProgressIndicator(
            progress = { if (row.bytesTotal > 0) (row.bytesDone.toFloat() / row.bytesTotal).coerceIn(0f, 1f) else 0f },
            modifier = Modifier.fillMaxWidth().height(4.dp).padding(bottom = 0.dp).clearAndSetSemantics { },
            color = MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        )
        Spacer(Modifier.height(8.dp))
    }
}

/** The status line and the overall bar. Statuses are a polite live region, errors an assertive one. */
@Composable
private fun StatusBlock(view: SetupView, sizes: SetupSizes, disk: SetupState, precheckError: SetupPrecheck?, storageNeededBytes: Long) {
    val lowStorageText = stringResource(
        R.string.setup_status_low_storage,
        megabytesLabel(aboutMegabytes(storageNeededBytes)),
        megabytesLabel(aboutMegabytes(installedFootprintBytes(disk, sizes))),
    )
    val (text, spoken, isError) = when {
        precheckError == SetupPrecheck.NO_NETWORK -> stringResource(R.string.setup_status_no_internet).let { Triple(it, it, true) }
        precheckError == SetupPrecheck.LOW_STORAGE -> Triple(lowStorageText, lowStorageText, true)
        view.line == null -> Triple(null, null, false)
        else -> {
            val full = lineText(view, view.line, lowStorageText)
            // The live region speaks the phase, not every byte count (it changes ten times a second);
            // the bar below carries the full line for a TalkBack user who focuses it.
            val coarse = if (view.line == SetupLine.DOWNLOADING) stringResource(R.string.setup_status_downloading_spoken) else full
            Triple(full, coarse, view.line.isError)
        }
    }
    if (text != null) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.Content),
            color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().clearAndSetSemantics {
                contentDescription = spoken ?: text
                liveRegion = if (isError) LiveRegionMode.Assertive else LiveRegionMode.Polite
            },
        )
    }
    if (view.phase != SetupPhase.INTRO && precheckError == null) {
        Spacer(Modifier.height(12.dp))
        val barState = text ?: stringResource(R.string.setup_status_percent, percentOf(view.fraction))
        LinearProgressIndicator(
            progress = { view.fraction },
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .semantics { stateDescription = barState },
            color = MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        )
    }
}

@Composable
private fun lineText(view: SetupView, line: SetupLine, lowStorageText: String): String = when (line) {
    SetupLine.CONNECTING -> stringResource(R.string.setup_status_connecting)
    SetupLine.DOWNLOADING -> {
        val (done, total) = progressMegabytes(view.bytesDone, view.bytesTotal)
        stringResource(R.string.setup_status_downloading, megabytesLabel(done), megabytesLabel(total))
    }
    SetupLine.RETRYING -> stringResource(R.string.setup_status_retrying, view.retryNumber, view.retryOf)
    SetupLine.CHECKING -> stringResource(R.string.setup_status_checking)
    SetupLine.UNPACKING -> stringResource(R.string.setup_status_unpacking)
    SetupLine.PAUSED -> stringResource(R.string.setup_status_paused)
    SetupLine.PAUSED_AT -> stringResource(R.string.setup_status_paused_at, percentOf(view.fraction))
    SetupLine.CONNECTION_DROPPED -> stringResource(R.string.setup_status_connection_dropped)
    SetupLine.SERVER_UNAVAILABLE -> stringResource(R.string.setup_status_server_unavailable)
    SetupLine.NOT_FOUND -> stringResource(R.string.setup_status_not_found)
    SetupLine.DAMAGED -> stringResource(R.string.setup_status_damaged)
    SetupLine.LOW_STORAGE -> lowStorageText
    SetupLine.FAILED -> stringResource(R.string.setup_status_failed)
    SetupLine.DONE -> stringResource(R.string.setup_status_done)
}

private val PREVIEW_SIZES = SetupSizes(98_511_183L, 102_543_452L, 158_269_440L)

@Preview(showBackground = true, backgroundColor = 0xFF302E2B)
@Composable
private fun SetupScreenIntroPreview() {
    val disk = SetupState(netInstalled = false, voiceInstalled = false, netPartBytes = 0, voicePartBytes = 0)
    ChessAnalyzerTheme {
        SetupScreenContent(
            view = setupView(disk, PREVIEW_SIZES, running = false, progress = null),
            sizes = PREVIEW_SIZES,
            disk = disk,
            precheckError = null,
            storageNeededBytes = 448_600_000L,
            gameWaiting = false,
            onPrimary = {},
            onPause = {},
            onCancel = {},
            onLeave = {},
        )
    }
}
