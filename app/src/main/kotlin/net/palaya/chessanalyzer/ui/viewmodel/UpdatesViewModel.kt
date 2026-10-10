package net.palaya.chessanalyzer.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.palaya.chessanalyzer.ChessAnalyzerApplication
import net.palaya.chessanalyzer.data.models.JournalRecord
import net.palaya.chessanalyzer.data.models.ModelDownloadService
import net.palaya.chessanalyzer.data.models.UpdateOffer
import net.palaya.chessanalyzer.data.models.UpdateUiState
import net.palaya.chessanalyzer.data.models.UpstreamRow
import net.palaya.chessanalyzer.ui.model.SetupPrecheck
import net.palaya.chessanalyzer.ui.model.UpdateBlock
import net.palaya.chessanalyzer.ui.model.setupPrecheck
import net.palaya.chessanalyzer.ui.model.updateBlock
import net.palaya.chessanalyzer.video.VideoExportService

/**
 * Settings › "Check for updates" (D2e). The work itself is [net.palaya.chessanalyzer.data.models.ModelUpdates]
 * in the application (it survives leaving Settings); this adds what only the screen needs: the row's
 * "Last checked", the reason the row is disabled, the rollback notice from the last start, and the
 * pre-check before an install (space, no network, the metered question: the Setup screen's rules).
 *
 * Nothing here touches the network on its own: [onRowTapped] and [requestInstall] are the user's taps.
 */
class UpdatesViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as ChessAnalyzerApplication
    private val updates get() = app.modelUpdates

    val state: StateFlow<UpdateUiState> = updates.state

    /** The upstream rows (A4), beside [state] and independent of it. */
    val upstream: StateFlow<List<UpstreamRow>> = updates.upstreamRows

    val lastCheckedMs: StateFlow<Long?> = app.settingsRepository.lastUpdateCheckMs
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** Why "Download and install" is disabled (the sheet). Installing needs a finished setup to roll back to. */
    val block: StateFlow<UpdateBlock?> = combine(
        app.engineController.analysisInFlight,
        VideoExportService.running,
        ModelDownloadService.running,
    ) { analysis, export, setupRunning ->
        updateBlock(analysis, export, setupRunning, setupComplete = app.modelSetup.isComplete())
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /**
     * Why the Settings row (the check itself) is disabled: an analysis, an export or a running setup download.
     * An unfinished setup does not block a check (it only reads the manifest).
     */
    val checkBlock: StateFlow<UpdateBlock?> = combine(
        app.engineController.analysisInFlight,
        VideoExportService.running,
        ModelDownloadService.running,
    ) { analysis, export, setupRunning ->
        updateBlock(analysis, export, setupRunning, setupComplete = true)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** The pre-check's inline error for an install tap (no network, low storage), cleared by the next tap. */
    private val _precheckError = MutableStateFlow<SetupPrecheck?>(null)
    val precheckError: StateFlow<SetupPrecheck?> = _precheckError

    /** An install waiting for the answer to "You're on mobile data". */
    private val _meteredOffer = MutableStateFlow<UpdateOffer?>(null)
    val meteredOffer: StateFlow<UpdateOffer?> = _meteredOffer

    /** A rollback the last start performed (shown once, then acknowledged). */
    private val _rollbackNotice = MutableStateFlow(false)
    val rollbackNotice: StateFlow<Boolean> = _rollbackNotice

    init {
        viewModelScope.launch(Dispatchers.IO) {
            val notice: JournalRecord.RolledBack? = app.modelActivator.pendingNotice()
            if (notice != null) {
                _rollbackNotice.value = true
                // Shown once: from now on only this screen visit carries it.
                app.modelActivator.acknowledgeNotice()
            }
        }
    }

    /** The row tap: starts a check unless one (or an install) already runs. The sheet opens either way. */
    fun onRowTapped() {
        _precheckError.value = null
        if (!updates.busy) updates.check()
    }

    fun checkAgain() {
        _precheckError.value = null
        updates.check()
    }

    /** "Download and install": space, then network, then metered (the Setup screen's order). */
    fun requestInstall(offer: UpdateOffer) {
        _precheckError.value = null
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                setupPrecheck(
                    network = app.networkStatus.current(),
                    freeBytes = app.modelStorageFreeBytes(),
                    storageNeeded = app.modelUpdateInstaller.storageNeeded(offer.entry),
                )
            }
            when (result) {
                SetupPrecheck.START -> updates.install(offer)
                SetupPrecheck.ASK_METERED -> _meteredOffer.value = offer
                SetupPrecheck.NO_NETWORK, SetupPrecheck.LOW_STORAGE -> _precheckError.value = result
            }
        }
    }

    fun confirmMetered() {
        val offer = _meteredOffer.value ?: return
        _meteredOffer.value = null
        updates.install(offer)
    }

    fun dismissMetered() {
        _meteredOffer.value = null
    }

    fun cancelInstall() = updates.cancelInstall()

    fun dismissRollbackNotice() {
        _rollbackNotice.value = false
    }
}
