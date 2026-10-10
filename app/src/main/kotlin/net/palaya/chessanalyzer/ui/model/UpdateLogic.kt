package net.palaya.chessanalyzer.ui.model

import net.palaya.chessanalyzer.data.models.ModelKind
import net.palaya.chessanalyzer.data.models.PauseReason
import net.palaya.chessanalyzer.data.models.UpdateCheckResult
import net.palaya.chessanalyzer.data.models.UpdateFailure
import net.palaya.chessanalyzer.data.models.UpdateInstallOutcome
import net.palaya.chessanalyzer.data.models.UpdateOffer
import net.palaya.chessanalyzer.data.models.UpdatePhase
import net.palaya.chessanalyzer.data.models.UpdateUiState
import net.palaya.chessanalyzer.data.models.UpstreamComponent
import net.palaya.chessanalyzer.data.models.UpstreamFailure
import net.palaya.chessanalyzer.data.models.UpstreamRow
import net.palaya.chessanalyzer.data.models.UpstreamStatus

/*
 * The pure logic behind Settings › "Check for updates" and its sheet (D2e, docs/MODEL_DOWNLOAD_DESIGN.md
 * §1.8). No Android types: which line, which buttons and when the row is disabled are host-tested in
 * UpdateLogicTest. References (owner's rule): Android's own Settings › System › System update ("Check for
 * update", a "Last checked" line, the result and one install button on the same screen) and Google Play's
 * "size stated before the tap" pattern; chess.com has no model-update pattern. Our own words throughout.
 */

/**
 * Why the row or an install is disabled. The design's two plus setup: an install needs a finished setup to roll
 * back to; the check itself is blocked only while setup's download runs (the view model asks with
 * `setupComplete = true` for the row).
 */
enum class UpdateBlock { ANALYSIS, EXPORT, SETUP }

/** Analysis first (the net cannot switch under it), then an export (the voice cannot), then setup. */
fun updateBlock(analysisRunning: Boolean, exportRunning: Boolean, setupRunning: Boolean, setupComplete: Boolean): UpdateBlock? = when {
    analysisRunning -> UpdateBlock.ANALYSIS
    exportRunning -> UpdateBlock.EXPORT
    setupRunning || !setupComplete -> UpdateBlock.SETUP
    else -> null
}

/** The row's second line. */
enum class UpdateRowLine { LAST_CHECKED, CHECKING, INSTALLING, BLOCKED }

fun updateRowLine(state: UpdateUiState, block: UpdateBlock?): UpdateRowLine = when {
    state is UpdateUiState.Checking -> UpdateRowLine.CHECKING
    state is UpdateUiState.Installing -> UpdateRowLine.INSTALLING
    block != null -> UpdateRowLine.BLOCKED
    else -> UpdateRowLine.LAST_CHECKED
}

/** Every line the sheet can show (each maps to one string). */
enum class UpdateLine(val isError: Boolean = false) {
    CHECKING,
    UP_TO_DATE,
    AVAILABLE,
    NO_INTERNET(true),
    SERVER_UNAVAILABLE(true),
    NOT_FOUND(true),
    SIGNATURE_INVALID(true),
    MANIFEST_INVALID(true),
    CONNECTING,
    DOWNLOADING,
    RETRYING,
    VERIFYING,
    UNPACKING,
    TRYING,
    INSTALLED,
    INSTALLED_NET,
    ROLLED_BACK(true),
    CONNECTION_DROPPED(true),
    FILE_NOT_FOUND(true),
    DAMAGED(true),
    LOW_STORAGE(true),
    INCOMPATIBLE(true),
    BUSY_ANALYSIS(true),
    BUSY_EXPORT(true),
    FAILED(true),
}

/** One offered file: what it is, its version, its size, and whether its button can be tapped now. */
data class UpdateOfferRow(val offer: UpdateOffer, val kind: ModelKind, val version: String, val sizeBytes: Long, val installEnabled: Boolean)

data class UpdateSheetView(
    val line: UpdateLine?,
    /** A progress block (bar + "X of Y") is shown. */
    val showProgress: Boolean = false,
    val fraction: Float = 0f,
    val bytesDone: Long = 0L,
    val bytesTotal: Long = 0L,
    val retryNumber: Int = 0,
    val retryOf: Int = 0,
    val offers: List<UpdateOfferRow> = emptyList(),
    /** Cancel the download (only while bytes are still coming). */
    val showCancel: Boolean = false,
    /** "Check again" (after a failed check, or to look again). */
    val showCheckAgain: Boolean = false,
)

/** What the sheet shows for [state]; [block] disables the install buttons with the row's reason. */
fun updateSheetView(state: UpdateUiState, block: UpdateBlock?, maxRetries: Int = 5): UpdateSheetView = when (state) {
    UpdateUiState.Idle -> UpdateSheetView(line = null, showCheckAgain = true)
    UpdateUiState.Checking -> UpdateSheetView(line = UpdateLine.CHECKING)
    is UpdateUiState.Checked -> when (val r = state.result) {
        is UpdateCheckResult.Available ->
            if (state.offers.isEmpty()) UpdateSheetView(UpdateLine.UP_TO_DATE, showCheckAgain = true)
            else UpdateSheetView(UpdateLine.AVAILABLE, offers = rows(state.offers, block == null))
        is UpdateCheckResult.UpToDate -> UpdateSheetView(UpdateLine.UP_TO_DATE, showCheckAgain = true)
        UpdateCheckResult.NoInternet -> UpdateSheetView(UpdateLine.NO_INTERNET, showCheckAgain = true)
        is UpdateCheckResult.ServerUnavailable -> UpdateSheetView(UpdateLine.SERVER_UNAVAILABLE, showCheckAgain = true)
        is UpdateCheckResult.NotFound -> UpdateSheetView(UpdateLine.NOT_FOUND, showCheckAgain = true)
        is UpdateCheckResult.SignatureInvalid -> UpdateSheetView(UpdateLine.SIGNATURE_INVALID, showCheckAgain = true)
        is UpdateCheckResult.ManifestInvalid -> UpdateSheetView(UpdateLine.MANIFEST_INVALID, showCheckAgain = true)
    }
    is UpdateUiState.Installing -> {
        val p = state.progress
        val line = when (p.phase) {
            UpdatePhase.CONNECTING -> UpdateLine.CONNECTING
            UpdatePhase.DOWNLOADING -> UpdateLine.DOWNLOADING
            UpdatePhase.RETRYING -> UpdateLine.RETRYING
            UpdatePhase.VERIFYING -> UpdateLine.VERIFYING
            UpdatePhase.UNPACKING -> UpdateLine.UNPACKING
            UpdatePhase.TRYING -> UpdateLine.TRYING
        }
        UpdateSheetView(
            line = line,
            showProgress = true,
            fraction = p.fraction,
            bytesDone = p.bytesDone,
            bytesTotal = p.bytesTotal,
            retryNumber = (p.retryAttempt + 1).coerceAtMost(maxRetries),
            retryOf = maxRetries,
            offers = rows(listOf(state.offer), enabled = false),
            showCancel = p.phase == UpdatePhase.CONNECTING || p.phase == UpdatePhase.DOWNLOADING || p.phase == UpdatePhase.RETRYING,
        )
    }
    is UpdateUiState.Finished -> {
        val o = state.outcome
        val line = when (o) {
            is UpdateInstallOutcome.Installed -> if (o.kind == ModelKind.NET) UpdateLine.INSTALLED_NET else UpdateLine.INSTALLED
            is UpdateInstallOutcome.RolledBack -> UpdateLine.ROLLED_BACK
            is UpdateInstallOutcome.Paused -> if (o.reason == PauseReason.SERVER_UNAVAILABLE) UpdateLine.SERVER_UNAVAILABLE else UpdateLine.CONNECTION_DROPPED
            is UpdateInstallOutcome.Failed -> when (o.reason) {
                UpdateFailure.NOT_FOUND -> UpdateLine.FILE_NOT_FOUND
                UpdateFailure.SERVER -> UpdateLine.SERVER_UNAVAILABLE
                UpdateFailure.DAMAGED -> UpdateLine.DAMAGED
                UpdateFailure.INSUFFICIENT_STORAGE -> UpdateLine.LOW_STORAGE
                UpdateFailure.INCOMPATIBLE -> UpdateLine.INCOMPATIBLE
                UpdateFailure.BUSY -> if (o.kind == ModelKind.VOICE) UpdateLine.BUSY_EXPORT else UpdateLine.BUSY_ANALYSIS
                UpdateFailure.INSECURE, UpdateFailure.INSTALL -> UpdateLine.FAILED
            }
        }
        // [UpdateUiState.Finished.others] already leaves out an installed, rolled-back or incompatible file.
        UpdateSheetView(line = line, offers = rows(state.others, block == null), showCheckAgain = state.others.isEmpty())
    }
}

private fun rows(offers: List<UpdateOffer>, enabled: Boolean): List<UpdateOfferRow> =
    offers.map { UpdateOfferRow(it, it.kind, it.entry.version, it.entry.sizeBytes, enabled) }

// ---- Upstream versions (A4) ----

/**
 * What one upstream row says. Information only: nothing upstream is downloaded, and a newer version "comes with
 * an app update" because engine code cannot be downloaded (Play policy) and the runtime and voice are built into
 * the release. A problem never reads as an error in the app: it is about the check.
 */
enum class UpstreamLine(val isProblem: Boolean, val isGood: Boolean = false) {
    CHECKING(false),
    UP_TO_DATE(false, isGood = true),
    NEWER(false),
    CHANGED(false),
    NO_INTERNET(true),
    RATE_LIMITED(true),
    UNAVAILABLE(true),
    UNREADABLE(true),
}

/** One drawn row: [latest] is set only for [UpstreamLine.NEWER]. */
data class UpstreamRowView(
    val component: UpstreamComponent,
    val ours: String,
    val line: UpstreamLine,
    val latest: String? = null,
)

/** The rows in the list's fixed order (the component order), so a late answer never reshuffles the list. */
fun upstreamRowViews(rows: List<UpstreamRow>): List<UpstreamRowView> =
    rows.sortedBy { it.component.ordinal }.map { row ->
        when (val s = row.status) {
            UpstreamStatus.Checking -> UpstreamRowView(row.component, row.ours, UpstreamLine.CHECKING)
            UpstreamStatus.UpToDate -> UpstreamRowView(row.component, row.ours, UpstreamLine.UP_TO_DATE)
            is UpstreamStatus.Newer -> UpstreamRowView(row.component, row.ours, UpstreamLine.NEWER, s.latest)
            UpstreamStatus.Changed -> UpstreamRowView(row.component, row.ours, UpstreamLine.CHANGED)
            is UpstreamStatus.Failed -> UpstreamRowView(
                row.component,
                row.ours,
                when (s.reason) {
                    UpstreamFailure.NO_INTERNET -> UpstreamLine.NO_INTERNET
                    UpstreamFailure.RATE_LIMITED -> UpstreamLine.RATE_LIMITED
                    UpstreamFailure.UNAVAILABLE -> UpstreamLine.UNAVAILABLE
                    UpstreamFailure.UNREADABLE -> UpstreamLine.UNREADABLE
                },
            )
        }
    }
