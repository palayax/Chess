@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package net.palaya.chessanalyzer.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.palaya.chessanalyzer.ChessAnalyzerApplication
import net.palaya.chessanalyzer.R
import net.palaya.chessanalyzer.data.models.FailureReason
import net.palaya.chessanalyzer.data.models.ModelDownloadService
import net.palaya.chessanalyzer.data.models.ModelSetup
import net.palaya.chessanalyzer.rephrase.RephraseSupport
import net.palaya.chessanalyzer.ui.a11y.asHeading
import net.palaya.chessanalyzer.ui.model.RephraseRow
import net.palaya.chessanalyzer.ui.model.RephraseRowView
import net.palaya.chessanalyzer.ui.model.SetupPrecheck
import net.palaya.chessanalyzer.ui.model.formatStorageMegabytes
import net.palaya.chessanalyzer.ui.model.gigabytesLabel
import net.palaya.chessanalyzer.ui.model.megabytesLabel
import net.palaya.chessanalyzer.ui.model.progressMegabytes
import net.palaya.chessanalyzer.ui.model.rephraseRowView
import net.palaya.chessanalyzer.ui.model.setupPrecheck

/**
 * C2: Settings > Commentary > "Natural wording (on-device AI)" (docs/LLM_REPHRASE_DESIGN.md §7, owner decisions §12).
 * Self-contained (reads the application's stores and the setup service) so SettingsScreen only places it.
 *
 * Reference (owner's rule, recorded in RUN_LOG C2-P4): chess.com's Settings group rows under a feature header with a
 * switch and a one-line explanation; Android's own on-device AI feature rows (a switch, an "on this device" note, the
 * download size before the tap). Every download goes through the setup service and starts only from this row's tap.
 */
@Composable
fun RephraseSettingsSection() {
    val context = LocalContext.current
    val app = context.applicationContext as ChessAnalyzerApplication
    val scope = rememberCoroutineScope()
    val store = app.rephraseModelStore
    val running by ModelDownloadService.running.collectAsState()
    val progress by ModelDownloadService.progress.collectAsState()
    val enabled by app.settingsRepository.rephraseEnabled.collectAsState(initial = false)
    val available = remember { app.rephraseAvailability() == RephraseSupport.Availability.AVAILABLE }
    var tick by remember { mutableLongStateOf(0L) }
    var cacheBytes by remember { mutableLongStateOf(0L) }
    var askMetered by remember { mutableStateOf(false) }
    var askRemove by remember { mutableStateOf(false) }
    var precheckError by remember { mutableStateOf<SetupPrecheck?>(null) }
    // Disk facts are re-read whenever the run changes shape (a file in, a pause, the end) and after an action.
    LaunchedEffect(progress?.status, progress?.perFile?.map { it.done }, running, tick) {
        cacheBytes = withContext(Dispatchers.IO) { app.rephraseCache.totalSizeBytes() }
    }
    val sizeBytes = store.compiled.sizeBytes
    val view = rephraseRowView(
        available = available,
        installed = remember(progress?.status, running, tick) { store.isInstalled() },
        wanted = remember(progress?.status, running, tick) { store.isWanted() },
        partBytes = remember(progress?.status, running, tick) { store.partFile.let { if (it.isFile) it.length() else 0L } },
        sizeBytes = sizeBytes,
        enabled = enabled,
        running = running,
        progress = progress,
    )

    fun startDownload() {
        precheckError = null
        store.setWanted(true)
        tick++
        app.diagnostics.log.log(ModelSetup.TAG, "wording model download tapped in Settings")
        ModelDownloadService.start(context)
    }

    fun precheckAndStart() {
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                val needed = (sizeBytes - (store.partFile.takeIf { it.isFile }?.length() ?: 0L)) + ModelSetup.SAFETY_MARGIN_BYTES
                setupPrecheck(app.networkStatus.current(), app.modelStorageFreeBytes(), needed)
            }
            when (result) {
                SetupPrecheck.START -> startDownload()
                SetupPrecheck.ASK_METERED -> askMetered = true
                else -> precheckError = result
            }
        }
    }

    RephraseSettingsContent(
        view = view,
        sizeBytes = sizeBytes,
        cacheBytes = cacheBytes,
        precheckError = precheckError,
        onToggle = { on -> scope.launch { app.settingsRepository.setRephraseEnabled(on) } },
        onDownload = { precheckAndStart() },
        onPause = { ModelDownloadService.pause() },
        onCancel = { ModelDownloadService.cancel(context) { tick++ } },
        onClearCache = {
            scope.launch {
                withContext(Dispatchers.IO) { app.rephraseCache.clear() }
                tick++
            }
        },
        onRemove = { askRemove = true },
    )

    if (askMetered) {
        AlertDialog(
            onDismissRequest = { askMetered = false },
            title = { Text(stringResource(R.string.setup_metered_title)) },
            text = { Text(stringResource(R.string.settings_rephrase_metered_body, gigabytesLabel(sizeBytes))) },
            confirmButton = {
                TextButton(onClick = { askMetered = false; startDownload() }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.setup_metered_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { askMetered = false }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.setup_not_now))
                }
            },
        )
    }
    if (askRemove) {
        AlertDialog(
            onDismissRequest = { askRemove = false },
            title = { Text(stringResource(R.string.settings_rephrase_remove_title)) },
            text = { Text(stringResource(R.string.settings_rephrase_remove_body, gigabytesLabel(sizeBytes))) },
            confirmButton = {
                TextButton(
                    onClick = {
                        askRemove = false
                        scope.launch {
                            app.settingsRepository.setRephraseEnabled(false)
                            app.rephraseBackend.release()
                            withContext(Dispatchers.IO) {
                                store.remove()
                                app.rephraseCache.clear()
                            }
                            app.diagnostics.log.log(ModelSetup.TAG, "wording model removed in Settings")
                            tick++
                        }
                    },
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text(stringResource(R.string.settings_rephrase_remove_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { askRemove = false }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.setup_cancel))
                }
            },
        )
    }
}

/** Stateless content of [RephraseSettingsSection], for the UI tests (every row state) and previews. */
@Composable
fun RephraseSettingsContent(
    view: RephraseRowView,
    sizeBytes: Long,
    cacheBytes: Long,
    precheckError: SetupPrecheck?,
    onToggle: (Boolean) -> Unit,
    onDownload: () -> Unit,
    onPause: () -> Unit,
    onCancel: () -> Unit,
    onClearCache: () -> Unit,
    onRemove: () -> Unit,
) {
    Column(modifier = Modifier.padding(bottom = 8.dp)) {
        Text(
            text = stringResource(R.string.settings_commentary_header),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp).asHeading(),
        )
        val title = stringResource(R.string.settings_rephrase_title)
        when (view.row) {
            RephraseRow.INSTALLED -> {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .toggleable(
                            value = view.enabled,
                            role = Role.Switch,
                            onValueChange = onToggle,
                        )
                        .heightIn(min = 56.dp)
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = title, style = MaterialTheme.typography.titleMedium)
                        Text(
                            text = stringResource(R.string.settings_rephrase_installed, gigabytesLabel(sizeBytes)),
                            style = MaterialTheme.typography.bodySmall.copy(textDirection = TextDirection.Content),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Switch(checked = view.enabled, onCheckedChange = null)
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.settings_rephrase_cache, formatStorageMegabytes(cacheBytes)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        onClick = onClearCache,
                        enabled = cacheBytes > 0,
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) { Text(stringResource(R.string.settings_rephrase_cache_clear)) }
                }
                TextButton(onClick = onRemove, modifier = Modifier.padding(horizontal = 4.dp).heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.settings_rephrase_remove))
                }
            }
            else -> {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .alpha(if (view.row == RephraseRow.UNAVAILABLE) 0.6f else 1f)
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(text = title, style = MaterialTheme.typography.titleMedium)
                    val line = when (view.row) {
                        RephraseRow.UNAVAILABLE -> stringResource(R.string.settings_rephrase_unavailable)
                        RephraseRow.NOT_INSTALLED -> stringResource(R.string.settings_rephrase_not_installed)
                        RephraseRow.DOWNLOADING -> {
                            val (done, total) = progressMegabytes(view.bytesDone, view.bytesTotal)
                            stringResource(R.string.settings_rephrase_downloading, megabytesLabel(done), megabytesLabel(total))
                        }
                        RephraseRow.PAUSED -> stringResource(R.string.settings_rephrase_paused, view.percent)
                        RephraseRow.FAILED -> stringResource(
                            when (view.failure) {
                                FailureReason.NOT_FOUND -> R.string.setup_status_not_found
                                FailureReason.DAMAGED -> R.string.setup_status_damaged
                                FailureReason.INSUFFICIENT_STORAGE -> R.string.setup_notification_low_storage
                                else -> R.string.setup_status_failed
                            },
                        )
                        RephraseRow.INSTALLED -> ""
                    }
                    val error = when (precheckError) {
                        SetupPrecheck.NO_NETWORK -> stringResource(R.string.settings_rephrase_no_network)
                        SetupPrecheck.LOW_STORAGE -> stringResource(R.string.settings_rephrase_low_storage, gigabytesLabel(sizeBytes))
                        else -> null
                    }
                    Text(
                        text = error ?: line,
                        style = MaterialTheme.typography.bodySmall.copy(textDirection = TextDirection.Content),
                        color = if (error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    )
                    if (view.row == RephraseRow.DOWNLOADING || view.row == RephraseRow.PAUSED) {
                        LinearProgressIndicator(
                            progress = { if (view.bytesTotal > 0) view.bytesDone.toFloat() / view.bytesTotal else 0f },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        when (view.row) {
                            RephraseRow.NOT_INSTALLED -> OutlinedButton(onClick = onDownload, modifier = Modifier.heightIn(min = 48.dp)) {
                                Text(stringResource(R.string.settings_rephrase_download, gigabytesLabel(sizeBytes)))
                            }
                            RephraseRow.FAILED -> OutlinedButton(onClick = onDownload, modifier = Modifier.heightIn(min = 48.dp)) {
                                Text(stringResource(R.string.setup_try_again))
                            }
                            RephraseRow.PAUSED -> {
                                OutlinedButton(onClick = onDownload, modifier = Modifier.heightIn(min = 48.dp)) {
                                    Text(stringResource(R.string.setup_resume))
                                }
                                OutlinedButton(onClick = onCancel, modifier = Modifier.heightIn(min = 48.dp)) {
                                    Text(stringResource(R.string.setup_cancel))
                                }
                            }
                            RephraseRow.DOWNLOADING -> {
                                OutlinedButton(onClick = onPause, modifier = Modifier.heightIn(min = 48.dp)) {
                                    Text(stringResource(R.string.setup_pause))
                                }
                                OutlinedButton(onClick = onCancel, modifier = Modifier.heightIn(min = 48.dp)) {
                                    Text(stringResource(R.string.setup_cancel))
                                }
                            }
                            else -> Unit
                        }
                    }
                }
            }
        }
    }

}
