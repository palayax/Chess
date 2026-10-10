package net.palaya.chessanalyzer.data.models

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What the "Check for updates" sheet shows (one process-wide state, like the setup service's). */
sealed interface UpdateUiState {
    /** Nothing checked in this process yet. */
    data object Idle : UpdateUiState

    data object Checking : UpdateUiState

    /** A check ended with [result]; [offers] is what can still be installed (shrinks as offers are installed). */
    data class Checked(val result: UpdateCheckResult, val offers: List<UpdateOffer>) : UpdateUiState

    data class Installing(val offer: UpdateOffer, val progress: UpdateProgress, val others: List<UpdateOffer>) : UpdateUiState

    /** An install ended; [others] are the offers still available from the same check. */
    data class Finished(val offer: UpdateOffer, val outcome: UpdateInstallOutcome, val others: List<UpdateOffer>) : UpdateUiState
}

/**
 * The app-wide holder of "Check for updates" (D2e): the sheet starts a check or an install here and
 * renders [state]; the work runs in the application's scope, so a rotation or leaving Settings does not
 * cancel it. One thing at a time: a tap while a check or an install runs is ignored.
 *
 * Network: [check] and [install] are the only entry points, and only the Settings sheet's buttons call
 * them (`NoNetworkAfterSetupTest`, `UpdateCheckNetworkTest`).
 */
class ModelUpdates(
    private val checker: () -> UpdateChecker,
    private val installer: () -> ModelUpdateInstaller,
    private val scope: CoroutineScope,
    /** Called with the time of every finished check ("Last checked: …"). */
    private val onChecked: suspend (Long) -> Unit = {},
    /**
     * The upstream half of a check (A4): null when there is none. Asked in its own coroutine at every [check],
     * after nothing of the signed check's, so it can neither delay nor change [state].
     */
    private val upstream: (() -> UpstreamChecker)? = null,
    private val clock: () -> Long = System::currentTimeMillis,
    private val elapsed: () -> Long = System::nanoTime,
) {
    private val _state = MutableStateFlow<UpdateUiState>(UpdateUiState.Idle)
    val state: StateFlow<UpdateUiState> = _state.asStateFlow()

    @Volatile private var job: Job? = null

    /** The upstream rows (A4): empty until a check runs (or when there is no upstream check); never part of [state]. */
    private val _upstream = MutableStateFlow<List<UpstreamRow>>(emptyList())
    val upstreamRows: StateFlow<List<UpstreamRow>> = _upstream.asStateFlow()

    @Volatile private var upstreamJob: Job? = null

    val busy: Boolean get() = job?.isActive == true

    /** The tap on the Settings row (or Try again). False when a check or an install already runs. */
    fun check(): Boolean {
        if (busy) return false
        _state.value = UpdateUiState.Checking
        startUpstreamCheck()
        job = scope.launch {
            val result = try {
                checker().check()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                UpdateCheckResult.ServerUnavailable("${e.javaClass.simpleName}: ${e.message}")
            }
            // "Last checked" means the server was asked: not when there was no network to ask with.
            if (result != UpdateCheckResult.NoInternet) runCatching { onChecked(clock()) }
            _state.value = UpdateUiState.Checked(result, (result as? UpdateCheckResult.Available)?.offers.orEmpty())
        }
        return true
    }

    /** Starts (or restarts) the upstream half beside the signed check. A failure there stays in its own rows. */
    private fun startUpstreamCheck() {
        upstreamJob?.cancel()
        val provider = upstream ?: return
        val checker = try {
            provider()
        } catch (e: Exception) {
            _upstream.value = emptyList()
            return
        }
        _upstream.value = checker.pendingRows()
        upstreamJob = scope.launch {
            try {
                checker.check { row -> _upstream.update { rows -> rows.map { if (it.component == row.component) row else it } } }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // check() reports failures as rows; this is only a bug guard. The rows still say "checking" otherwise.
                _upstream.update { rows -> rows.map { if (it.status == UpstreamStatus.Checking) it.copy(status = UpstreamStatus.Failed(UpstreamFailure.UNREADABLE)) else it } }
            }
        }
    }

    /** "Download and install" for [offer]. False when something already runs. */
    fun install(offer: UpdateOffer): Boolean {
        if (busy) return false
        val others = currentOffers().filter { it != offer }
        _state.value = UpdateUiState.Installing(offer, UpdateProgress(offer.kind, UpdatePhase.CONNECTING, 0, offer.entry.sizeBytes), others)
        job = scope.launch {
            var lastPhase: UpdatePhase? = null
            var lastAt = 0L
            val outcome = try {
                installer().install(offer) { p ->
                    val now = elapsed()
                    // Phase changes at once, byte counts at 10 Hz (the setup screen's rate).
                    if (p.phase != lastPhase || now - lastAt >= 100_000_000L) {
                        lastPhase = p.phase
                        lastAt = now
                        _state.value = UpdateUiState.Installing(offer, p, others)
                    }
                }
            } catch (e: CancellationException) {
                _state.value = UpdateUiState.Checked(UpdateCheckResult.Available(listOf(offer) + others, emptyList()), listOf(offer) + others)
                throw e
            } catch (e: Exception) {
                UpdateInstallOutcome.Failed(offer.kind, UpdateFailure.INSTALL, "${e.javaClass.simpleName}: ${e.message}")
            }
            // Installed, rolled back or not compatible: not offered again in this sheet. Otherwise Try again.
            val retryable = outcome is UpdateInstallOutcome.Paused ||
                (outcome is UpdateInstallOutcome.Failed && outcome.reason != UpdateFailure.INCOMPATIBLE)
            val remaining = if (retryable) listOf(offer) + others else others
            _state.value = UpdateUiState.Finished(offer, outcome, remaining)
        }
        return true
    }

    /** Cancel in the sheet while downloading: the part file is deleted by the installer. */
    fun cancelInstall() {
        val s = _state.value
        if (s is UpdateUiState.Installing && s.progress.phase in setOf(UpdatePhase.CONNECTING, UpdatePhase.DOWNLOADING, UpdatePhase.RETRYING)) {
            job?.cancel(CancellationException("cancelled by the user"))
        }
    }

    private fun currentOffers(): List<UpdateOffer> = when (val s = _state.value) {
        is UpdateUiState.Checked -> s.offers
        is UpdateUiState.Finished -> s.others
        is UpdateUiState.Installing -> listOf(s.offer) + s.others
        else -> emptyList()
    }
}
