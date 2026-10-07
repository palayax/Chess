package net.palaya.chessanalyzer.data.models

import java.io.File
import kotlinx.coroutines.CancellationException
import net.palaya.chessanalyzer.diagnostics.DiagnosticLog
import net.palaya.chessanalyzer.engine.NetStore
import net.palaya.chessanalyzer.video.VoiceStore

/** Where an update install stands (the Settings sheet's progress block). */
enum class UpdatePhase { CONNECTING, DOWNLOADING, RETRYING, VERIFYING, UNPACKING, TRYING }

data class UpdateProgress(
    val kind: ModelKind,
    val phase: UpdatePhase,
    val bytesDone: Long,
    val bytesTotal: Long,
    /** Failures in a row while [UpdatePhase.RETRYING] ("Retrying… (2 of 5)"). */
    val retryAttempt: Int = 0,
    /** 0..1 while [UpdatePhase.UNPACKING]. */
    val unpackFraction: Float = 0f,
) {
    val fraction: Float
        get() = when (phase) {
            UpdatePhase.CONNECTING, UpdatePhase.DOWNLOADING, UpdatePhase.RETRYING ->
                if (bytesTotal > 0) (bytesDone.toFloat() / bytesTotal * 0.85f).coerceIn(0f, 0.85f) else 0f
            UpdatePhase.VERIFYING -> 0.87f
            UpdatePhase.UNPACKING -> 0.85f + 0.08f * unpackFraction.coerceIn(0f, 1f)
            UpdatePhase.TRYING -> 0.95f
        }
}

enum class UpdateFailure { INCOMPATIBLE, NOT_FOUND, SERVER, DAMAGED, INSECURE, INSUFFICIENT_STORAGE, BUSY, INSTALL }

/** How [ModelUpdateInstaller.install] ended. */
sealed interface UpdateInstallOutcome {
    val kind: ModelKind

    data class Installed(override val kind: ModelKind) : UpdateInstallOutcome
    data class Failed(override val kind: ModelKind, val reason: UpdateFailure, val detail: String) : UpdateInstallOutcome

    /** The trial failed; the previous version is in use again ("…the previous version was restored."). */
    data class RolledBack(override val kind: ModelKind, val detail: String) : UpdateInstallOutcome

    /** The connection kept failing; the part file is kept and Try again resumes it. */
    data class Paused(override val kind: ModelKind, val reason: PauseReason) : UpdateInstallOutcome
}

/**
 * Downloads an offered file and hands it to [ModelActivator] (D2e). Started only from the Settings sheet's
 * "Download and install" tap. Before ANY request it judges the entry again with [ModelCompatibility]
 * against the facts of this moment, so an incompatible entry (a wrong-architecture net, say) can never be
 * downloaded even if something offered it. The download is [ModelDownloader.download] with the manifest's
 * size and SHA-256 (resumable `.part`, the same fault handling as setup); the activation does the
 * structural checks, the journal, the trial and the rollback.
 *
 * Cancelling the coroutine is Cancel: the part file is deleted ([discardPart]).
 */
class ModelUpdateInstaller(
    private val downloader: ModelDownloader,
    private val activator: ModelActivator,
    private val netStore: NetStore,
    private val voiceStore: VoiceStore,
    private val facts: () -> AppFacts,
    private val freeBytes: () -> Long,
    private val diagnostics: DiagnosticLog? = null,
) {
    private fun log(line: String) {
        diagnostics?.log(ModelSetup.TAG, line)
    }

    /** The `.part` an update of [entry] downloads into (net: `nets/<name>.part`; voice: its own name). */
    fun partFileFor(entry: ManifestEntry): File = when (entry.kind) {
        ModelKind.NET -> netStore.partFileFor(entry.fileName)
        ModelKind.VOICE -> voiceStore.updatePartFile(entry.sha256)
    }

    /** Free space the update needs: the rest of the download, plus the unpacked voice, plus the margin. */
    fun storageNeeded(entry: ManifestEntry): Long {
        val part = partFileFor(entry)
        val rest = (entry.sizeBytes - (if (part.isFile) part.length() else 0L)).coerceAtLeast(0L)
        val unpack = if (entry.kind == ModelKind.VOICE) entry.unpackedSizeBytes else 0L
        return rest + unpack + ModelSetup.SAFETY_MARGIN_BYTES
    }

    fun discardPart(entry: ManifestEntry) {
        partFileFor(entry).delete()
    }

    suspend fun install(offer: UpdateOffer, onProgress: (UpdateProgress) -> Unit = {}): UpdateInstallOutcome {
        val entry = offer.entry
        val kind = entry.kind
        val verdict = ModelCompatibility.evaluate(entry, facts())
        if (verdict != CompatVerdict.Offer) {
            log("update ${kind.id} ${entry.fileName}: not installed, it is not compatible now ($verdict); nothing requested")
            return UpdateInstallOutcome.Failed(kind, UpdateFailure.INCOMPATIBLE, verdict.toString())
        }
        val needed = storageNeeded(entry)
        val free = freeBytes()
        if (free < needed) {
            log("update ${kind.id}: not enough space (needs $needed bytes, has $free)")
            return UpdateInstallOutcome.Failed(kind, UpdateFailure.INSUFFICIENT_STORAGE, "needs $needed bytes, has $free")
        }
        val part = partFileFor(entry)
        log("update ${kind.id} ${entry.version}: download start (${entry.sizeBytes} bytes from host ${ModelDownloader.hostOf(entry.url)})")
        var phase = UpdatePhase.CONNECTING
        var retry = 0
        var bytes = 0L
        fun emit() = onProgress(UpdateProgress(kind, phase, bytes, entry.sizeBytes, retry))
        emit()
        val result = try {
            downloader.download(entry.toFileSpec(), part, object : DownloadListener {
                override fun onState(state: DownloadState) {
                    when (state) {
                        is DownloadState.Connecting -> { retry = state.transientFailures; phase = if (retry > 0) UpdatePhase.RETRYING else UpdatePhase.CONNECTING }
                        is DownloadState.Backoff -> { retry = state.transientFailures; phase = UpdatePhase.RETRYING }
                        is DownloadState.Streaming -> phase = if (state.transientFailures > 0) UpdatePhase.RETRYING else UpdatePhase.DOWNLOADING
                        else -> return
                    }
                    emit()
                }

                override fun onBytes(done: Long, total: Long) {
                    bytes = done
                    if (phase == UpdatePhase.CONNECTING) phase = UpdatePhase.DOWNLOADING
                    emit()
                }
            })
        } catch (e: CancellationException) {
            part.delete()
            log("update ${kind.id}: cancelled by the user; part file deleted")
            throw e
        }
        when (result) {
            is DownloadResult.Paused -> {
                log("update ${kind.id}: paused (${result.reason}) at ${part.length()} of ${entry.sizeBytes} bytes")
                return UpdateInstallOutcome.Paused(kind, result.reason)
            }
            is DownloadResult.Failed -> {
                log("update ${kind.id}: download failed (${result.reason}: ${result.detail})")
                return UpdateInstallOutcome.Failed(kind, result.reason.toUpdateFailure(), result.detail ?: result.reason.name)
            }
            is DownloadResult.Verified -> log("update ${kind.id}: downloaded and verified (SHA-256 matches the signed manifest)")
        }

        bytes = entry.sizeBytes
        val done = bytes
        val outcome = when (kind) {
            ModelKind.NET -> {
                phase = UpdatePhase.VERIFYING
                emit()
                // The activator checks the header first; the trial phase is shown while it runs.
                phase = UpdatePhase.TRYING
                emit()
                activator.activateNet(part, entry)
            }
            ModelKind.VOICE -> {
                phase = UpdatePhase.UNPACKING
                emit()
                activator.activateVoice(
                    part,
                    entry,
                    onUnpack = { f -> onProgress(UpdateProgress(kind, UpdatePhase.UNPACKING, done, entry.sizeBytes, unpackFraction = f)) },
                    onTrial = { onProgress(UpdateProgress(kind, UpdatePhase.TRYING, done, entry.sizeBytes)) },
                )
            }
        }
        return when (outcome) {
            is ActivationOutcome.Activated -> UpdateInstallOutcome.Installed(kind)
            is ActivationOutcome.RolledBack -> UpdateInstallOutcome.RolledBack(kind, outcome.detail)
            is ActivationOutcome.Rejected -> UpdateInstallOutcome.Failed(
                kind,
                when (outcome.reason) {
                    RejectReason.INCOMPATIBLE -> UpdateFailure.INCOMPATIBLE
                    RejectReason.DAMAGED -> UpdateFailure.DAMAGED
                    RejectReason.BUSY -> UpdateFailure.BUSY
                    RejectReason.INSUFFICIENT_STORAGE -> UpdateFailure.INSUFFICIENT_STORAGE
                    RejectReason.INSTALL -> UpdateFailure.INSTALL
                },
                outcome.detail,
            )
        }
    }

    private fun FailureReason.toUpdateFailure(): UpdateFailure = when (this) {
        FailureReason.NOT_FOUND -> UpdateFailure.NOT_FOUND
        FailureReason.SERVER -> UpdateFailure.SERVER
        FailureReason.SIZE_MISMATCH, FailureReason.DAMAGED -> UpdateFailure.DAMAGED
        FailureReason.INSECURE -> UpdateFailure.INSECURE
        FailureReason.INSUFFICIENT_STORAGE -> UpdateFailure.INSUFFICIENT_STORAGE
        FailureReason.INSTALL -> UpdateFailure.INSTALL
    }
}
