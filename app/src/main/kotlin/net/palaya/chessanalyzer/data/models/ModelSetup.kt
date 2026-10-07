package net.palaya.chessanalyzer.data.models

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import net.palaya.chessanalyzer.diagnostics.DiagnosticLog
import net.palaya.chessanalyzer.engine.NetStore
import net.palaya.chessanalyzer.video.InsufficientVoiceStorageException
import net.palaya.chessanalyzer.video.VoiceArchiveDamagedException
import net.palaya.chessanalyzer.video.VoiceStore

/** The two files setup fetches, in this order. */
enum class ModelFile { NET, VOICE }

/** What the Setup screen, the Home card and the notification say (design §1.2, §1.3). */
enum class SetupStatus { IDLE, CONNECTING, DOWNLOADING, RETRYING, UNPACKING, PAUSED, FAILED, DONE }

/** One file's line in the progress block. */
data class FileProgress(
    val file: ModelFile,
    val bytesDone: Long,
    val bytesTotal: Long,
    val state: DownloadState,
) {
    val done: Boolean get() = state == DownloadState.Installed
}

/**
 * Everything the UI renders while setup runs (design §2.3). [bytesDone]/[bytesTotal] count only the
 * files this run fetches; [overallFraction] weights the voice's unpacking as 10% of its share.
 */
data class SetupProgress(
    val overallFraction: Float,
    val bytesDone: Long,
    val bytesTotal: Long,
    val perFile: List<FileProgress>,
    val status: SetupStatus,
    /** For "Retrying… (2 of 5)": failures so far in a row, 0 when not retrying. */
    val retryAttempt: Int = 0,
    val pauseReason: PauseReason? = null,
    val failure: FailureReason? = null,
)

/** How [ModelSetup.run] ended. A user Pause is not an outcome: it is the job's cancellation. */
sealed interface SetupOutcome {
    data object Complete : SetupOutcome
    data class Paused(val file: ModelFile, val reason: PauseReason) : SetupOutcome
    data class Failed(val file: ModelFile, val reason: FailureReason, val detail: String? = null) : SetupOutcome
}

/** Where setup stands, cheaply (no hashing): what is installed and how much of each part is on disk. */
data class SetupState(
    val netInstalled: Boolean,
    val voiceInstalled: Boolean,
    val netPartBytes: Long,
    val voicePartBytes: Long,
) {
    val complete: Boolean get() = netInstalled && voiceInstalled
}

/**
 * The first-run download of the two model files (docs/MODEL_DOWNLOAD_DESIGN.md §2.1, §2.2): replaces
 * the bundled builds' one-time copy out of the APK. Net first, then voice. **Never starts by itself**: only
 * [run] touches the network, and only the Setup flow (D2c: `ModelDownloadService`, after the user's tap)
 * calls it. Analysis is unblocked as soon as the net is in ([needsNet] false); setup is complete only
 * with both.
 *
 * Pins: the URLs are `baseUrl + releaseTag + "/" + fileName`; size and full SHA-256 come from the
 * build (`GeneratedNetPins` / `GeneratedModelPins`). No remote manifest is consulted on first run.
 *
 * Pause = cancel the coroutine running [run] (parts kept, design §1.1 "Paused at 43%"); Cancel = cancel
 * it, then [discardPartials]. Nothing resumes on its own.
 *
 * Diagnostic log (tag `models`): start, what is needed and the free space, each file's start / 25-50-75%
 * / verified / installed, every retry and its reason, pauses and failures. Hosts only, never a full URL.
 */
class ModelSetup(
    private val netStore: NetStore,
    private val voiceStore: VoiceStore,
    private val downloader: ModelDownloader,
    /** `BuildConfig.MODEL_BASE_URL`, ending in '/'. */
    private val baseUrl: String,
    private val releaseTag: String = GeneratedModelPins.RELEASE_TAG,
    /** Free bytes on the volume that holds `filesDir`; injectable for the low-space tests. */
    private val freeBytes: () -> Long,
    private val diagnostics: DiagnosticLog? = null,
) {
    companion object {
        /** Headroom on top of the files themselves (the app's own databases, caches, an export). */
        const val SAFETY_MARGIN_BYTES: Long = 32L * 1024 * 1024

        const val TAG = "models"

        private val MILESTONES = listOf(25, 50, 75)
    }

    private val runMutex = Mutex()

    /** The net's download: `<base><tag>/<net name>`, the pinned size and SHA-256 (first run never reads a manifest). */
    val netSpec: ModelFileSpec
        get() = ModelFileSpec(
            url = "$baseUrl$releaseTag/${netStore.pins.fileName}",
            fileName = netStore.pins.fileName,
            sizeBytes = netStore.pins.sizeBytes,
            sha256 = netStore.pins.sha256,
        )

    /**
     * The voice's download: `<base><tag>/<archive name>` (the `.tar.gz` since D2f), with the archive's pinned
     * size and SHA-256. The tar inside is checked against the tar pins while it is unpacked.
     */
    val voiceSpec: ModelFileSpec
        get() = ModelFileSpec(
            url = "$baseUrl$releaseTag/${voiceStore.download.fileName}",
            fileName = voiceStore.download.fileName,
            sizeBytes = voiceStore.download.sizeBytes,
            sha256 = voiceStore.download.sha256,
        )

    /** What the unpacked voice takes on disk (the tar's size; the download is the smaller `.tar.gz`). */
    val voiceUnpackedBytes: Long get() = voiceStore.neededBytes

    /** Cheap (no hashing): no net of the pinned size is installed, so the engine cannot run. */
    fun needsNet(): Boolean = netStore.activeNetOrNull() == null

    /** Cheap: the voice is not installed (device TTS narrates until it is). */
    fun needsVoice(): Boolean = !voiceStore.isInstalled()

    fun isComplete(): Boolean = !needsNet() && !needsVoice()

    fun state(): SetupState = SetupState(
        netInstalled = !needsNet(),
        voiceInstalled = !needsVoice(),
        netPartBytes = netStore.partFileFor().lengthOrZero(),
        voicePartBytes = voiceStore.partFile.lengthOrZero(),
    )

    /** Bytes still to download for the missing files ("About 160 MB left"). */
    fun bytesLeft(): Long {
        val s = state()
        val net = if (s.netInstalled) 0L else (netSpec.sizeBytes - s.netPartBytes).coerceAtLeast(0)
        val voice = if (s.voiceInstalled) 0L else (voiceSpec.sizeBytes - s.voicePartBytes).coerceAtLeast(0)
        return net + voice
    }

    /**
     * Free space setup needs at its peak, margin included: the net still to come, the voice archive still
     * to come plus the unpacked voice (both exist until the archive is deleted). About 400 MB on a fresh
     * install since D2f (98.5 + 102.5 + 158.3 MB + the margin; 450 MB with the plain tar, design §1.3).
     */
    fun storageNeeded(): Long {
        val s = state()
        val net = if (s.netInstalled) 0L else (netSpec.sizeBytes - s.netPartBytes).coerceAtLeast(0)
        val voice = if (s.voiceInstalled) 0L else (voiceSpec.sizeBytes - s.voicePartBytes).coerceAtLeast(0) + voiceStore.neededBytes
        return if (net + voice == 0L) 0L else net + voice + SAFETY_MARGIN_BYTES
    }

    /**
     * One-time move of the bundled builds' files (design §8). The net moves into `nets/`; the voice
     * needs nothing (same directory, same marker). Returns the log line, or null when there was nothing.
     */
    fun migrateLegacy(): String? {
        val m = netStore.migrateLegacy()
        if (!m.didSomething) return null
        val line = "migrateLegacy: moved ${m.moved.ifEmpty { listOf("nothing") }.joinToString()} into ${NetStore.DIR_NAME}/" +
            (if (m.deleted.isNotEmpty()) ", deleted ${m.deleted.joinToString()}" else "") +
            "; voice installed: ${voiceStore.isInstalled()}"
        diagnostics?.log(TAG, line)
        return line
    }

    /** Cancel: deletes every part file. Call after the job running [run] was cancelled and joined. */
    fun discardPartials() {
        netStore.deleteParts()
        voiceStore.deletePart()
        diagnostics?.log(TAG, "setup cancelled: part files deleted")
    }

    /**
     * Downloads and installs what is missing, net first. Returns [SetupOutcome.Complete] at once when
     * both are installed. Throws [CancellationException] on Pause/Cancel (parts kept; see the class doc).
     */
    suspend fun run(onProgress: (SetupProgress) -> Unit = {}): SetupOutcome = runMutex.withLock {
        withContext(Dispatchers.IO) {
            val netNeeded = netStore.verifiedNetOrNull() == null
            val voiceNeeded = !voiceStore.isInstalled()
            if (!netNeeded && !voiceNeeded) {
                onProgress(SetupProgress(1f, 0, 0, emptyList(), SetupStatus.DONE))
                return@withContext SetupOutcome.Complete
            }
            diagnostics?.log(
                TAG,
                "setup start: net ${if (netNeeded) "needed" else "installed"}, voice ${if (voiceNeeded) "needed" else "installed"}, " +
                    "free ${freeBytes()} bytes, needs ${storageNeeded()}, from host ${ModelDownloader.hostOf(baseUrl)}, release $releaseTag",
            )
            val tracker = ProgressTracker(netNeeded, voiceNeeded, onProgress)

            if (netNeeded) {
                val r = fetch(ModelFile.NET, netSpec, netStore.partFileFor(), tracker, extraBytesNeeded = 0L)
                if (r != null) return@withContext r
                try {
                    withContext(NonCancellable) {
                        netStore.installVerified(netStore.partFileFor())
                        // Setup's net is the compiled pin. If an update's record named a net that has gone
                        // missing or bad, setup's net replaces it as the active one (D2e).
                        if (netStore.hasUpdateRecord()) {
                            netStore.setActiveIdentity(netStore.compiledIdentity())
                            netStore.otherNets().forEach { it.delete() }
                            diagnostics?.log(TAG, "net: the update record was replaced by this build's net")
                        }
                    }
                } catch (e: IOException) {
                    netStore.partFileFor().delete()
                    diagnostics?.error(TAG, "net: install failed", e)
                    tracker.failed(ModelFile.NET, FailureReason.INSTALL)
                    return@withContext SetupOutcome.Failed(ModelFile.NET, FailureReason.INSTALL, e.message)
                }
                diagnostics?.log(TAG, "net: installed as ${NetStore.DIR_NAME}/${netSpec.fileName}")
                tracker.installed(ModelFile.NET)
            }

            if (voiceNeeded) {
                val part = voiceStore.partFile
                val r = fetch(ModelFile.VOICE, voiceSpec, part, tracker, extraBytesNeeded = voiceStore.neededBytes)
                if (r != null) return@withContext r
                tracker.unpacking()
                val unpackStart = System.nanoTime()
                try {
                    // The tar inside the verified archive is checked against this build's tar pins.
                    voiceStore.installFromTar(part) { f -> tracker.unpackProgress(f) }
                } catch (e: CancellationException) {
                    diagnostics?.log(TAG, "voice: unpacking interrupted; the verified tar is kept")
                    throw e
                } catch (e: IOException) {
                    val reason = when {
                        e is InsufficientVoiceStorageException || ModelDownloader.isOutOfSpace(e) -> FailureReason.INSUFFICIENT_STORAGE
                        e is VoiceArchiveDamagedException -> FailureReason.DAMAGED
                        else -> FailureReason.INSTALL
                    }
                    if (reason == FailureReason.DAMAGED) part.delete()
                    diagnostics?.error(TAG, "voice: install failed ($reason)", e)
                    tracker.failed(ModelFile.VOICE, reason)
                    return@withContext SetupOutcome.Failed(ModelFile.VOICE, reason, e.message)
                }
                diagnostics?.log(
                    TAG,
                    "voice: installed (${voiceStore.installedVersionId()}), unpacked in ${(System.nanoTime() - unpackStart) / 1_000_000} ms",
                )
                tracker.installed(ModelFile.VOICE)
            }
            diagnostics?.log(TAG, "setup complete")
            tracker.done()
            SetupOutcome.Complete
        }
    }

    /** Downloads one file into [part]; null when it is verified, else the outcome to return. */
    private suspend fun fetch(
        file: ModelFile,
        spec: ModelFileSpec,
        part: java.io.File,
        tracker: ProgressTracker,
        extraBytesNeeded: Long,
    ): SetupOutcome? {
        val already = part.lengthOrZero().coerceAtMost(spec.sizeBytes)
        val needed = (spec.sizeBytes - already) + extraBytesNeeded + SAFETY_MARGIN_BYTES
        val free = freeBytes()
        if (free < needed) {
            diagnostics?.error(TAG, "${spec.fileName}: not enough space (needs $needed bytes, has $free)")
            tracker.failed(file, FailureReason.INSUFFICIENT_STORAGE)
            return SetupOutcome.Failed(file, FailureReason.INSUFFICIENT_STORAGE, "needs $needed bytes, has $free")
        }
        diagnostics?.log(
            TAG,
            "${spec.fileName}: download start (${spec.sizeBytes} bytes" +
                (if (already > 0) ", $already already on disk" else "") + ") from host ${ModelDownloader.hostOf(spec.url)}",
        )
        var nextMilestone = 0
        val result = downloader.download(spec, part, object : DownloadListener {
            override fun onState(state: DownloadState) = tracker.state(file, state)
            override fun onBytes(done: Long, total: Long) {
                tracker.bytes(file, done, total)
                val percent = if (total > 0) (done * 100 / total).toInt() else 0
                while (nextMilestone < MILESTONES.size && percent >= MILESTONES[nextMilestone]) {
                    diagnostics?.log(TAG, "${spec.fileName}: ${MILESTONES[nextMilestone]}% ($done of $total bytes)")
                    nextMilestone++
                }
            }
        })
        return when (result) {
            is DownloadResult.Verified -> {
                diagnostics?.log(TAG, "${spec.fileName}: verified")
                null
            }
            is DownloadResult.Paused -> {
                diagnostics?.log(TAG, "${spec.fileName}: paused (${result.reason}) at ${part.lengthOrZero()} of ${spec.sizeBytes} bytes")
                SetupOutcome.Paused(file, result.reason)
            }
            is DownloadResult.Failed -> {
                diagnostics?.error(TAG, "${spec.fileName}: failed (${result.reason}: ${result.detail})")
                SetupOutcome.Failed(file, result.reason, result.detail)
            }
        }
    }

    /** Turns per-file events into [SetupProgress] snapshots. */
    private inner class ProgressTracker(
        private val netNeeded: Boolean,
        private val voiceNeeded: Boolean,
        private val emit: (SetupProgress) -> Unit,
    ) {
        private val files = LinkedHashMap<ModelFile, FileProgress>().apply {
            if (netNeeded) put(ModelFile.NET, FileProgress(ModelFile.NET, 0, netSpec.sizeBytes, DownloadState.Idle))
            if (voiceNeeded) put(ModelFile.VOICE, FileProgress(ModelFile.VOICE, 0, voiceSpec.sizeBytes, DownloadState.Idle))
        }
        private var unpack = 0f
        private var status = SetupStatus.CONNECTING
        private var retry = 0
        private var pause: PauseReason? = null
        private var failure: FailureReason? = null

        fun state(file: ModelFile, s: DownloadState) {
            files[file] = files.getValue(file).copy(state = s)
            when (s) {
                is DownloadState.Connecting -> {
                    status = if (s.transientFailures > 0) SetupStatus.RETRYING else SetupStatus.CONNECTING
                    retry = s.transientFailures
                }
                is DownloadState.Backoff -> { status = SetupStatus.RETRYING; retry = s.transientFailures }
                is DownloadState.Streaming -> status = if (s.transientFailures > 0) SetupStatus.RETRYING else SetupStatus.DOWNLOADING
                is DownloadState.HashFailed -> status = SetupStatus.CONNECTING
                is DownloadState.Paused -> { status = SetupStatus.PAUSED; pause = s.reason }
                is DownloadState.Failed -> { status = SetupStatus.FAILED; failure = s.reason }
                else -> Unit
            }
            publish()
        }

        fun bytes(file: ModelFile, done: Long, total: Long) {
            val f = files.getValue(file)
            files[file] = f.copy(bytesDone = done, bytesTotal = total)
            if (status == SetupStatus.CONNECTING || status == SetupStatus.RETRYING) {
                if (f.state is DownloadState.Streaming) status = SetupStatus.DOWNLOADING
            }
            publish()
        }

        fun unpacking() {
            files[ModelFile.VOICE] = files.getValue(ModelFile.VOICE).copy(state = DownloadState.Installing)
            status = SetupStatus.UNPACKING
            publish()
        }

        fun unpackProgress(f: Float) {
            unpack = f.coerceIn(0f, 1f)
            publish()
        }

        fun installed(file: ModelFile) {
            val f = files.getValue(file)
            files[file] = f.copy(bytesDone = f.bytesTotal, state = DownloadState.Installed)
            if (file == ModelFile.VOICE) unpack = 1f
            publish()
        }

        fun failed(file: ModelFile, reason: FailureReason) {
            files[file] = files.getValue(file).copy(state = DownloadState.Failed(reason))
            status = SetupStatus.FAILED
            failure = reason
            publish()
        }

        fun done() {
            status = SetupStatus.DONE
            publish()
        }

        private fun publish() {
            val net = files[ModelFile.NET]
            val voice = files[ModelFile.VOICE]
            val total = files.values.sumOf { it.bytesTotal }
            val done = files.values.sumOf { it.bytesDone.coerceAtMost(it.bytesTotal) }
            val weighted = (net?.bytesDone?.coerceAtMost(net.bytesTotal) ?: 0L) +
                (voice?.let { it.bytesDone.coerceAtMost(it.bytesTotal) * 0.9 + unpack * 0.1 * it.bytesTotal } ?: 0.0)
            val fraction = if (total > 0) (weighted.toFloat() / total).coerceIn(0f, 1f) else 1f
            emit(
                SetupProgress(
                    overallFraction = if (status == SetupStatus.DONE) 1f else fraction,
                    bytesDone = done,
                    bytesTotal = total,
                    perFile = files.values.toList(),
                    status = status,
                    retryAttempt = if (status == SetupStatus.RETRYING) retry else 0,
                    pauseReason = if (status == SetupStatus.PAUSED) pause else null,
                    failure = if (status == SetupStatus.FAILED) failure else null,
                ),
            )
        }
    }
}

private fun java.io.File.lengthOrZero(): Long = if (isFile) length() else 0L
