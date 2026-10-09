package net.palaya.chessanalyzer.ui.viewmodel

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.palaya.chessanalyzer.ChessAnalyzerApplication
import net.palaya.chessanalyzer.data.models.ModelDownloadService
import net.palaya.chessanalyzer.data.models.ModelSetup
import net.palaya.chessanalyzer.rephrase.RephraseSupport
import net.palaya.chessanalyzer.data.models.SetupState
import net.palaya.chessanalyzer.ui.model.SetupPrecheck
import net.palaya.chessanalyzer.ui.model.SetupSizes
import net.palaya.chessanalyzer.ui.model.setupPrecheck

/**
 * The Setup screen's link to the download (D2c). Holds the cheap disk facts ([disk]), refreshed whenever
 * the service's run changes shape (a file installed, a pause, the end), and turns the user's taps into
 * the pre-check (space, network) and the service calls. Activity-scoped, like [AnalysisViewModel], so
 * Home's card and the Video notice read the same state.
 *
 * Nothing here touches the network: [precheck] only asks the platform whether a network is connected
 * and metered; the download itself is [ModelDownloadService], started only by [start] from a tap.
 */
class SetupViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as ChessAnalyzerApplication
    private val setup: ModelSetup get() = app.modelSetup

    /**
     * C2 (owner decision §12.3): the Setup screen offers the wording model as an optional third download, on a phone
     * that can run it. Decided once per screen visit (cheap: ABI, RAM, CPU flags).
     */
    val rephraseOffered: Boolean =
        setup.rephraseSpec != null && app.rephraseAvailability() == RephraseSupport.Availability.AVAILABLE

    val sizes = SetupSizes(
        netBytes = setup.netSpec.sizeBytes,
        voiceBytes = setup.voiceSpec.sizeBytes,
        voiceInstalledBytes = setup.voiceUnpackedBytes,
        rephraseBytes = if (rephraseOffered) setup.rephraseSpec?.sizeBytes ?: 0L else 0L,
    )

    /** The optional download's check box: the request lives on disk (`rephrase/wanted`), so a resume keeps it. */
    fun setRephraseWanted(wanted: Boolean) {
        if (!rephraseOffered) return
        app.rephraseModelStore.setWanted(wanted)
        app.diagnostics.log.log(ModelSetup.TAG, "wording model ${if (wanted) "added to" else "removed from"} the setup download")
        refresh()
    }

    private val _disk = MutableStateFlow(setup.state())

    /** Installed files and part-file sizes; no hashing (see [refresh] for the one exception). */
    val disk: StateFlow<SetupState> = _disk

    /** The last inline pre-check error (no network, low storage), cleared by the next tap. */
    private val _precheckError = MutableStateFlow<SetupPrecheck?>(null)
    val precheckError: StateFlow<SetupPrecheck?> = _precheckError

    init {
        // Re-read the disk whenever a run changes shape: a file installed, paused, failed, done, cancelled.
        viewModelScope.launch {
            ModelDownloadService.progress
                .map { p -> p?.let { it.status to it.perFile.map { f -> f.done } } }
                .distinctUntilChanged()
                .collect { refresh() }
        }
    }

    /**
     * Re-reads the disk state. With [verifyNet], an installed net is also hashed against its pin (once
     * per process, cached by `NetStore`): a damaged net of the right size then counts as missing, so the
     * Setup screen offers Download instead of "All set" (the engine gate would refuse it anyway).
     */
    fun refresh(verifyNet: Boolean = false) {
        viewModelScope.launch(Dispatchers.IO) {
            var s = setup.state()
            if (verifyNet && s.netInstalled && app.netStore.verifiedNetOrNull() == null) {
                app.diagnostics.log.log(ModelSetup.TAG, "the installed net does not match its pin; setup will fetch it again")
                s = s.copy(netInstalled = false)
            }
            _disk.value = s
        }
    }

    /** Space needed at setup's peak and the space there is, for the low-storage line. */
    fun storageNeededBytes(): Long = setup.storageNeeded()

    /** The tap: decides between start, the metered question, and the two inline errors (design §1.3). */
    suspend fun precheck(): SetupPrecheck = withContext(Dispatchers.IO) {
        val needed = setup.storageNeeded()
        val free = app.modelStorageFreeBytes()
        val network = app.networkStatus.current()
        val result = setupPrecheck(network, free, needed)
        app.diagnostics.log.log(ModelSetup.TAG, "download tapped: network $network, free $free bytes, needs $needed -> $result")
        _precheckError.value = result.takeIf { it == SetupPrecheck.NO_NETWORK || it == SetupPrecheck.LOW_STORAGE }
        result
    }

    /** Starts (or resumes) the download service. Only ever called from the user's tap. */
    fun start(context: Context) {
        _precheckError.value = null
        ModelDownloadService.start(context)
    }

    fun pause() {
        app.diagnostics.log.log(ModelSetup.TAG, "pause tapped on the Setup screen")
        ModelDownloadService.pause()
    }

    fun cancel(context: Context) {
        app.diagnostics.log.log(ModelSetup.TAG, "cancel tapped on the Setup screen")
        _precheckError.value = null
        ModelDownloadService.cancel(context) { refresh() }
    }

    /** Cheap: the net is in, so analysis works (the voice may still be missing). */
    fun netReady(): Boolean = !setup.needsNet()

    /** Cheap: the neural voice is installed. */
    fun voiceInstalled(): Boolean = !setup.needsVoice()
}
