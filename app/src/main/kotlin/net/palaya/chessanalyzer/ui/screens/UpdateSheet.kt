@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package net.palaya.chessanalyzer.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.NewReleases
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.ui.Alignment
import net.palaya.chessanalyzer.data.models.UpstreamComponent
import net.palaya.chessanalyzer.ui.model.UpstreamLine
import net.palaya.chessanalyzer.ui.model.UpstreamRowView
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import net.palaya.chessanalyzer.R
import net.palaya.chessanalyzer.data.models.ModelKind
import net.palaya.chessanalyzer.data.models.UpdateOffer
import net.palaya.chessanalyzer.ui.a11y.asHeading
import net.palaya.chessanalyzer.ui.model.SetupPrecheck
import net.palaya.chessanalyzer.ui.model.UpdateBlock
import net.palaya.chessanalyzer.ui.model.UpdateLine
import net.palaya.chessanalyzer.ui.model.UpdateOfferRow
import net.palaya.chessanalyzer.ui.model.UpdateSheetView
import net.palaya.chessanalyzer.ui.model.aboutMegabytes
import net.palaya.chessanalyzer.ui.model.megabytesLabel
import net.palaya.chessanalyzer.ui.model.percentOf
import net.palaya.chessanalyzer.ui.model.progressMegabytes

/**
 * The "Check for updates" result sheet (D2e, docs/MODEL_DOWNLOAD_DESIGN.md §1.8): opened by the Settings
 * row, it shows the check's result, the offered files with their sizes and one "Download and install"
 * each, then the install's progress (downloading, checking the file, unpacking, trying the new version)
 * and its end (installed, or rolled back, or why not). Every rule is [UpdateSheetView]'s (host-tested).
 *
 * Accessibility as everywhere else (CLAUDE.md): the title is a heading; the status line is a polite live
 * region (it speaks "Downloading", not every byte count; the bar carries the full line as its state) and
 * an error is an assertive one in the error colour; buttons are Material buttons (48 dp); nothing has
 * `maxLines`; the column scrolls at a large font and in landscape.
 */
@Composable
fun UpdateSheet(
    view: UpdateSheetView,
    upstream: List<UpstreamRowView>,
    block: UpdateBlock?,
    precheckError: SetupPrecheck?,
    meteredOffer: UpdateOffer?,
    onInstall: (UpdateOffer) -> Unit,
    onConfirmMetered: () -> Unit,
    onDismissMetered: () -> Unit,
    onCancel: () -> Unit,
    onCheckAgain: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        UpdateSheetContent(view, upstream, block, precheckError, onInstall, onCancel, onCheckAgain, onDismiss)
    }
    if (meteredOffer != null) {
        AlertDialog(
            onDismissRequest = onDismissMetered,
            title = { Text(stringResource(R.string.setup_metered_title)) },
            text = {
                Text(stringResource(R.string.update_metered_body, megabytesLabel(aboutMegabytes(meteredOffer.entry.sizeBytes))))
            },
            confirmButton = { TextButton(onClick = onConfirmMetered) { Text(stringResource(R.string.setup_metered_confirm)) } },
            dismissButton = { TextButton(onClick = onDismissMetered) { Text(stringResource(R.string.setup_not_now)) } },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun UpdateSheetContent(
    view: UpdateSheetView,
    upstream: List<UpstreamRowView>,
    block: UpdateBlock?,
    precheckError: SetupPrecheck?,
    onInstall: (UpdateOffer) -> Unit,
    onCancel: () -> Unit,
    onCheckAgain: () -> Unit,
    onDismiss: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .navigationBarsPadding()
            .padding(start = 16.dp, end = 16.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = stringResource(R.string.update_sheet_title),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.asHeading(),
        )

        val text = view.line?.let { lineText(view, it) }
        val (shown, spoken, isError) = when (precheckError) {
            SetupPrecheck.NO_NETWORK -> stringResource(R.string.update_no_internet).let { Triple(it, it, true) }
            SetupPrecheck.LOW_STORAGE -> stringResource(R.string.update_low_storage).let { Triple(it, it, true) }
            else -> Triple(
                text,
                if (view.line == UpdateLine.DOWNLOADING) stringResource(R.string.update_downloading_spoken) else text,
                view.line?.isError == true,
            )
        }
        if (shown != null) {
            Text(
                text = shown,
                style = MaterialTheme.typography.bodyLarge.copy(textDirection = TextDirection.Content),
                color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.fillMaxWidth().clearAndSetSemantics {
                    contentDescription = spoken ?: shown
                    liveRegion = if (isError) LiveRegionMode.Assertive else LiveRegionMode.Polite
                },
            )
        }
        if (view.showProgress) {
            val barState = text ?: stringResource(R.string.setup_status_percent, percentOf(view.fraction))
            LinearProgressIndicator(
                progress = { view.fraction },
                modifier = Modifier.fillMaxWidth().height(6.dp).semantics { stateDescription = barState },
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            )
        }

        for (row in view.offers) OfferCard(row, onInstall)

        if (block != null && view.offers.any { !it.installEnabled } && !view.showProgress) {
            Text(
                text = stringResource(blockText(block)),
                style = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.Content),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // A4: what the projects this app is built from have released lately. Information only, and drawn
        // apart from the result above: a problem here never changes what the signed check found.
        if (upstream.isNotEmpty() && !view.showProgress) UpstreamSection(upstream)

        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (view.showCancel) {
                OutlinedButton(onClick = onCancel) { Text(stringResource(R.string.update_cancel)) }
            }
            if (view.showCheckAgain && (block == null || block == UpdateBlock.SETUP)) {
                OutlinedButton(onClick = onCheckAgain) { Text(stringResource(R.string.update_check_again)) }
            }
            if (!view.showProgress) {
                TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.update_close)) }
            }
        }
        Spacer(Modifier.height(4.dp))
    }
}

@Composable
private fun OfferCard(row: UpdateOfferRow, onInstall: (UpdateOffer) -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                text = stringResource(
                    R.string.update_offer,
                    stringResource(
                        when (row.kind) {
                            ModelKind.NET -> R.string.update_kind_net
                            ModelKind.VOICE -> R.string.update_kind_voice
                            ModelKind.REPHRASE -> R.string.update_kind_rephrase
                        },
                    ),
                    row.version,
                    megabytesLabel(aboutMegabytes(row.sizeBytes)),
                ),
                style = MaterialTheme.typography.titleMedium.copy(textDirection = TextDirection.Content),
            )
            Button(
                onClick = { onInstall(row.offer) },
                enabled = row.installEnabled,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.update_download_install))
            }
        }
    }
}

/** "The projects this app is built from": one row per component, an icon and words for each state. */
@Composable
private fun UpstreamSection(rows: List<UpstreamRowView>) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = stringResource(R.string.upstream_title),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.asHeading(),
        )
        Text(
            text = stringResource(R.string.upstream_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        ) {
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                rows.forEachIndexed { i, row ->
                    if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    UpstreamRowItem(row)
                }
            }
        }
    }
}

@Composable
private fun UpstreamRowItem(row: UpstreamRowView) {
    val name = stringResource(upstreamNameRes(row.component))
    val ours = stringResource(R.string.upstream_ours, row.ours)
    val status = upstreamLineText(row)
    // One TalkBack stop per component: "Stockfish. This app has 19. Up to date".
    val spoken = stringResource(R.string.cd_upstream_row, name, ours, status)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .padding(vertical = 8.dp)
            .clearAndSetSemantics { contentDescription = spoken },
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(modifier = Modifier.size(24.dp), contentAlignment = Alignment.Center) {
            when (row.line) {
                UpstreamLine.CHECKING -> CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                UpstreamLine.UP_TO_DATE -> Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                UpstreamLine.NEWER -> Icon(Icons.Filled.NewReleases, contentDescription = null, tint = MaterialTheme.colorScheme.tertiary)
                UpstreamLine.CHANGED -> Icon(Icons.Filled.Info, contentDescription = null, tint = MaterialTheme.colorScheme.tertiary)
                else -> Icon(Icons.Filled.WarningAmber, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(text = name, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = ours,
                style = MaterialTheme.typography.bodySmall.copy(textDirection = TextDirection.Content),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = status,
                style = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.Content),
                color = if (row.line.isProblem) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

private fun upstreamNameRes(component: UpstreamComponent): Int = when (component) {
    UpstreamComponent.STOCKFISH -> R.string.upstream_stockfish
    UpstreamComponent.SHERPA_ONNX -> R.string.upstream_sherpa_onnx
    UpstreamComponent.KOKORO_VOICE -> R.string.upstream_kokoro
}

@Composable
private fun upstreamLineText(row: UpstreamRowView): String = when (row.line) {
    UpstreamLine.CHECKING -> stringResource(R.string.upstream_checking)
    UpstreamLine.UP_TO_DATE -> stringResource(R.string.upstream_up_to_date)
    UpstreamLine.NEWER -> stringResource(R.string.upstream_newer, row.latest.orEmpty())
    UpstreamLine.CHANGED -> stringResource(R.string.upstream_changed)
    UpstreamLine.NO_INTERNET -> stringResource(R.string.upstream_no_internet)
    UpstreamLine.RATE_LIMITED -> stringResource(R.string.upstream_rate_limited)
    UpstreamLine.UNAVAILABLE -> stringResource(R.string.upstream_unavailable)
    UpstreamLine.UNREADABLE -> stringResource(R.string.upstream_unreadable)
}

private fun blockText(block: UpdateBlock): Int = when (block) {
    UpdateBlock.ANALYSIS -> R.string.update_blocked_analysis
    UpdateBlock.EXPORT -> R.string.update_blocked_export
    UpdateBlock.SETUP -> R.string.update_blocked_setup
}

/** The row's disabled reason, for the Settings screen. */
fun updateBlockText(block: UpdateBlock): Int = blockText(block)

@Composable
private fun lineText(view: UpdateSheetView, line: UpdateLine): String = when (line) {
    UpdateLine.CHECKING -> stringResource(R.string.update_checking)
    UpdateLine.UP_TO_DATE -> stringResource(R.string.update_up_to_date)
    UpdateLine.AVAILABLE -> stringResource(R.string.update_available)
    UpdateLine.NO_INTERNET -> stringResource(R.string.update_no_internet)
    UpdateLine.SERVER_UNAVAILABLE -> stringResource(R.string.update_server_unavailable)
    UpdateLine.NOT_FOUND -> stringResource(R.string.update_not_found)
    UpdateLine.SIGNATURE_INVALID -> stringResource(R.string.update_signature_invalid)
    UpdateLine.MANIFEST_INVALID -> stringResource(R.string.update_manifest_invalid)
    UpdateLine.CONNECTING -> stringResource(R.string.update_connecting)
    UpdateLine.DOWNLOADING -> {
        val (done, total) = progressMegabytes(view.bytesDone, view.bytesTotal)
        stringResource(R.string.update_downloading, megabytesLabel(done), megabytesLabel(total))
    }
    UpdateLine.RETRYING -> stringResource(R.string.update_retrying, view.retryNumber, view.retryOf)
    UpdateLine.VERIFYING -> stringResource(R.string.update_verifying)
    UpdateLine.UNPACKING -> stringResource(R.string.update_unpacking)
    UpdateLine.TRYING -> stringResource(R.string.update_trying)
    UpdateLine.INSTALLED -> stringResource(R.string.update_installed)
    UpdateLine.INSTALLED_NET -> stringResource(R.string.update_installed_net)
    UpdateLine.ROLLED_BACK -> stringResource(R.string.update_rolled_back)
    UpdateLine.CONNECTION_DROPPED -> stringResource(R.string.update_connection_dropped)
    UpdateLine.FILE_NOT_FOUND -> stringResource(R.string.update_file_not_found)
    UpdateLine.DAMAGED -> stringResource(R.string.update_damaged)
    UpdateLine.LOW_STORAGE -> stringResource(R.string.update_low_storage)
    UpdateLine.INCOMPATIBLE -> stringResource(R.string.update_incompatible)
    UpdateLine.BUSY_ANALYSIS -> stringResource(R.string.update_blocked_analysis)
    UpdateLine.BUSY_EXPORT -> stringResource(R.string.update_blocked_export)
    UpdateLine.FAILED -> stringResource(R.string.update_failed)
}
