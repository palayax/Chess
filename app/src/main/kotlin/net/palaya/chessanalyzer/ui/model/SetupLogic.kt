package net.palaya.chessanalyzer.ui.model

import java.util.Locale
import net.palaya.chessanalyzer.data.models.DownloadState
import net.palaya.chessanalyzer.data.models.DownloadStateMachine
import net.palaya.chessanalyzer.data.models.FailureReason
import net.palaya.chessanalyzer.data.models.ModelFile
import net.palaya.chessanalyzer.data.models.NetworkCost
import net.palaya.chessanalyzer.data.models.PauseReason
import net.palaya.chessanalyzer.data.models.SetupProgress
import net.palaya.chessanalyzer.data.models.SetupState
import net.palaya.chessanalyzer.data.models.SetupStatus

/*
 * The pure logic behind the Setup screen, the Home "Finish setting up" card and the download
 * notification (D2c, docs/MODEL_DOWNLOAD_DESIGN.md §1.2-§1.5). No Android types: every rule (which line,
 * which buttons, the sizes, the pre-checks, the progress throttle) is host-tested in SetupLogicTest.
 */

// ---- Sizes ----

/** One megabyte as the Settings storage row counts it: 1024 * 1024 bytes. */
private const val BYTES_PER_MIB = 1024.0 * 1024.0

/** "12.4 MB", LRM-wrapped, Locale.ROOT (the Settings narration-storage row; moved here from SettingsLogic). */
fun formatStorageMegabytes(totalBytes: Long): String =
    "$LRM" + String.format(Locale.ROOT, "%.1f MB", totalBytes.coerceAtLeast(0L) / BYTES_PER_MIB) + "$LRM"

/** A download size is stated in decimal megabytes, as Google Play and Android's own file-size formatter do. */
const val BYTES_PER_MB: Long = 1_000_000L

private fun ceilDiv(a: Long, b: Long): Long = (a + b - 1) / b

/**
 * The "about" figure for a size the user is told before a download: rounded UP, to a multiple of 10 MB
 * from 10 MB on, to the next whole MB below that, so a download or the space it needs is never
 * understated. 98.5 MB -> 100, 158.3 MB -> 160, 256.8 MB -> 260, 448.6 MB -> 450; 0 for nothing.
 */
fun aboutMegabytes(bytes: Long): Long {
    if (bytes <= 0L) return 0L
    val mb = ceilDiv(bytes, BYTES_PER_MB)
    return if (mb < 10L) mb else ceilDiv(mb, 10L) * 10L
}

/** "260 MB", LRM-wrapped so the number and the unit keep their order inside a right-to-left line. */
fun megabytesLabel(megabytes: Long): String = "$LRM$megabytes MB$LRM"

/**
 * A running count, "120 MB of 257 MB": the total rounded to the nearest MB, the part done rounded
 * DOWN and never above the total, so the line cannot read "257 of 257" before the file is checked.
 */
fun progressMegabytes(doneBytes: Long, totalBytes: Long): Pair<Long, Long> {
    val total = (totalBytes.coerceAtLeast(0L) + BYTES_PER_MB / 2) / BYTES_PER_MB
    // Not done yet never reads "N of N" (201.05 MB rounds to 201, and so does 201.0 MB done).
    val done = if (totalBytes > 0 && doneBytes >= totalBytes) total else (doneBytes.coerceAtLeast(0L) / BYTES_PER_MB).coerceAtMost((total - 1).coerceAtLeast(0L))
    return done to total
}

/** Whole percent, rounded down, 0..100. */
fun percentOf(fraction: Float): Int = (fraction.coerceIn(0f, 1f) * 100f).toInt().coerceIn(0, 100)

/**
 * The pinned download sizes of the two files (`ModelSetup.netSpec` / `voiceSpec`), and the unpacked voice's
 * size on disk ([voiceInstalledBytes]: the tar's, since the download is a `.tar.gz` from D2f on).
 */
data class SetupSizes(val netBytes: Long, val voiceBytes: Long, val voiceInstalledBytes: Long = voiceBytes) {
    fun of(file: ModelFile): Long = if (file == ModelFile.NET) netBytes else voiceBytes
}

/** Bytes the files that are not installed take once they are (the "(260 MB once done)" figure). */
fun installedFootprintBytes(disk: SetupState, sizes: SetupSizes): Long =
    (if (disk.netInstalled) 0L else sizes.netBytes) + (if (disk.voiceInstalled) 0L else sizes.voiceInstalledBytes)

/**
 * Bytes the files that are not installed take to DOWNLOAD (the progress total; since D2f the voice's share is
 * its `.tar.gz`, smaller than what it unpacks to, so this is not [installedFootprintBytes]).
 */
fun downloadTotalBytes(disk: SetupState, sizes: SetupSizes): Long =
    (if (disk.netInstalled) 0L else sizes.netBytes) + (if (disk.voiceInstalled) 0L else sizes.voiceBytes)

/** Bytes still to download: the missing files minus what their part files already hold. */
fun bytesLeftToDownload(disk: SetupState, sizes: SetupSizes): Long =
    (if (disk.netInstalled) 0L else (sizes.netBytes - disk.netPartBytes).coerceAtLeast(0L)) +
        (if (disk.voiceInstalled) 0L else (sizes.voiceBytes - disk.voicePartBytes).coerceAtLeast(0L))

// ---- The tap on Download ----

/** What a tap on Download / Resume / Try again leads to (design §1.3). */
enum class SetupPrecheck {
    /** Start the download now (Wi-Fi or another unmetered network, enough space). */
    START,

    /** On mobile data (or a network the phone will not call unmetered): ask first. */
    ASK_METERED,

    /** No connected, validated network: an inline error, nothing is requested. */
    NO_NETWORK,

    /** Not enough free space for setup's peak: an inline error, nothing is requested. */
    LOW_STORAGE,
}

/**
 * Space first (no point asking about mobile data for a download that cannot fit), then no network,
 * then metered. [freeBytes] is what the volume can give the app (`StorageManager.getAllocatableBytes`),
 * [storageNeeded] is `ModelSetup.storageNeeded()` (peak, margin included; 0 when nothing is missing).
 */
fun setupPrecheck(network: NetworkCost, freeBytes: Long, storageNeeded: Long): SetupPrecheck = when {
    freeBytes < storageNeeded -> SetupPrecheck.LOW_STORAGE
    network == NetworkCost.UNAVAILABLE -> SetupPrecheck.NO_NETWORK
    network == NetworkCost.METERED -> SetupPrecheck.ASK_METERED
    else -> SetupPrecheck.START
}

// ---- What the screen shows ----

enum class SetupPhase { INTRO, RUNNING, PAUSED, FAILED, DONE }

/** The status line under the title. Each value is one `setup_status_*` string. */
enum class SetupLine {
    CONNECTING,
    DOWNLOADING,
    RETRYING,
    CHECKING,
    UNPACKING,

    /** Paused by the user in this session. */
    PAUSED,

    /** Found on disk at launch (a kill, or a pause in an earlier session): "Paused at 43%". */
    PAUSED_AT,
    CONNECTION_DROPPED,
    SERVER_UNAVAILABLE,
    NOT_FOUND,
    DAMAGED,
    LOW_STORAGE,

    /** Any other failure (another 4xx, a size mismatch, an insecure redirect, an install error). */
    FAILED,
    DONE,
    ;

    /** Errors are said assertively and drawn in the error colour; statuses are polite. */
    val isError: Boolean
        get() = this in setOf(CONNECTION_DROPPED, SERVER_UNAVAILABLE, NOT_FOUND, DAMAGED, LOW_STORAGE, FAILED)
}

/** The screen's filled button. */
enum class SetupAction { DOWNLOAD, DOWNLOAD_VOICE, RESUME, TRY_AGAIN, CONTINUE }

/** One file's line in the progress block: "Chess engine data ✓", or "21 MB of 158 MB" with a bar. */
data class SetupFileRow(
    val file: ModelFile,
    val installed: Boolean,
    val bytesDone: Long,
    val bytesTotal: Long,
    /** The file being fetched right now: it gets the thin per-file bar. */
    val active: Boolean,
)

/** Everything the Setup screen renders, from the disk state and the service's last progress. */
data class SetupView(
    val phase: SetupPhase,
    val line: SetupLine?,
    val fraction: Float,
    val bytesDone: Long,
    val bytesTotal: Long,
    /** "Retrying… (2 of 5)": the attempt being made now, 2..5. */
    val retryNumber: Int,
    val rows: List<SetupFileRow>,
    val primary: SetupAction?,
    val canPause: Boolean,
    val canCancel: Boolean,
    /** What Download would fetch now (the button's "(about 260 MB)"). */
    val bytesToDownload: Long,
) {
    val retryOf: Int get() = DownloadStateMachine.MAX_TRANSIENT_FAILURES
}

private val RUNNING_STATUSES = setOf(SetupStatus.CONNECTING, SetupStatus.DOWNLOADING, SetupStatus.RETRYING, SetupStatus.UNPACKING)

/**
 * The view for [disk] (cheap file facts) and the download service's state: [running] (a run is in
 * flight in this process) and [progress] (the last snapshot it published, null when nothing ran in this
 * process or the user cancelled).
 *
 *  - A run in flight is shown from its progress (connecting, downloading, retrying, checking, unpacking).
 *  - A run that ended in this process is shown from its last snapshot (paused with its reason, failed
 *    with its reason, done).
 *  - Otherwise the disk decides: both installed = done; a part file of a missing model = "Paused at N%"
 *    with Resume (a kill or a pause in an earlier session; setup never resumes by itself); else the intro.
 */
fun setupView(disk: SetupState, sizes: SetupSizes, running: Boolean, progress: SetupProgress?): SetupView {
    val left = bytesLeftToDownload(disk, sizes)
    val hasParts = (!disk.netInstalled && disk.netPartBytes > 0) || (!disk.voiceInstalled && disk.voicePartBytes > 0)
    val live = progress?.takeIf { running || it.status !in RUNNING_STATUSES }
    val rows = ModelFile.entries.map { f ->
        val installed = if (f == ModelFile.NET) disk.netInstalled else disk.voiceInstalled
        val part = if (f == ModelFile.NET) disk.netPartBytes else disk.voicePartBytes
        SetupFileRow(f, installed, if (installed) sizes.of(f) else part.coerceIn(0L, sizes.of(f)), sizes.of(f), active = false)
    }
    val footprint = downloadTotalBytes(disk, sizes)
    val done = footprint - left
    val fraction = if (footprint > 0) (done.toFloat() / footprint).coerceIn(0f, 1f) else 1f

    if (live != null && !(live.status == SetupStatus.DONE && !disk.complete && !running)) {
        // A run that ended (paused, failed) is drawn from the disk: a failure may have deleted the part
        // (a wrong hash), and the bars must not keep showing what is no longer there (seen on chess34).
        val ended = !running && live.status != SetupStatus.DONE
        val base = SetupView(
            phase = SetupPhase.RUNNING,
            line = null,
            fraction = if (ended) fraction else live.overallFraction.coerceIn(0f, 1f),
            bytesDone = if (ended) done else live.bytesDone,
            bytesTotal = if (ended) footprint else live.bytesTotal,
            retryNumber = (live.retryAttempt + 1).coerceIn(2, DownloadStateMachine.MAX_TRANSIENT_FAILURES),
            rows = if (ended) rows else rowsFrom(disk, sizes, live, running),
            primary = null,
            canPause = false,
            canCancel = false,
            bytesToDownload = left,
        )
        return when (live.status) {
            SetupStatus.DONE -> base.copy(phase = SetupPhase.DONE, line = SetupLine.DONE, fraction = 1f, primary = SetupAction.CONTINUE)
            SetupStatus.PAUSED -> base.copy(
                phase = SetupPhase.PAUSED,
                line = when (live.pauseReason) {
                    PauseReason.CONNECTION_LOST -> SetupLine.CONNECTION_DROPPED
                    PauseReason.SERVER_UNAVAILABLE -> SetupLine.SERVER_UNAVAILABLE
                    PauseReason.USER, null -> SetupLine.PAUSED
                },
                primary = if (live.pauseReason == PauseReason.SERVER_UNAVAILABLE) SetupAction.TRY_AGAIN else SetupAction.RESUME,
                canCancel = true,
            )
            SetupStatus.FAILED -> base.copy(
                phase = SetupPhase.FAILED,
                line = when (live.failure) {
                    FailureReason.NOT_FOUND -> SetupLine.NOT_FOUND
                    FailureReason.DAMAGED -> SetupLine.DAMAGED
                    FailureReason.INSUFFICIENT_STORAGE -> SetupLine.LOW_STORAGE
                    else -> SetupLine.FAILED
                },
                primary = SetupAction.TRY_AGAIN,
                canCancel = hasParts,
            )
            SetupStatus.IDLE, SetupStatus.CONNECTING -> base.copy(line = SetupLine.CONNECTING, canPause = true, canCancel = true)
            SetupStatus.RETRYING -> base.copy(line = SetupLine.RETRYING, canPause = true, canCancel = true)
            SetupStatus.UNPACKING -> base.copy(line = SetupLine.UNPACKING, canPause = true, canCancel = true)
            SetupStatus.DOWNLOADING -> {
                val activeState = live.perFile.firstOrNull { !it.done }
                val checking = activeState != null && (
                    activeState.state == DownloadState.Verified ||
                        (activeState.file == ModelFile.NET && activeState.state == DownloadState.Installing)
                    )
                base.copy(line = if (checking) SetupLine.CHECKING else SetupLine.DOWNLOADING, canPause = true, canCancel = true)
            }
        }
    }

    return when {
        disk.complete -> SetupView(SetupPhase.DONE, SetupLine.DONE, 1f, 0, 0, 2, rows, SetupAction.CONTINUE, false, false, 0)
        hasParts -> SetupView(SetupPhase.PAUSED, SetupLine.PAUSED_AT, fraction, done, footprint, 2, rows, SetupAction.RESUME, false, true, left)
        else -> SetupView(
            SetupPhase.INTRO, null, fraction, done, footprint, 2, rows,
            if (disk.netInstalled) SetupAction.DOWNLOAD_VOICE else SetupAction.DOWNLOAD,
            false, false, left,
        )
    }
}

private fun rowsFrom(disk: SetupState, sizes: SetupSizes, p: SetupProgress, running: Boolean): List<SetupFileRow> {
    val activeFile = if (running) p.perFile.firstOrNull { !it.done }?.file else null
    return ModelFile.entries.map { f ->
        val fp = p.perFile.firstOrNull { it.file == f }
        val installedOnDisk = if (f == ModelFile.NET) disk.netInstalled else disk.voiceInstalled
        val installed = fp?.done ?: installedOnDisk
        val total = fp?.bytesTotal?.takeIf { it > 0 } ?: sizes.of(f)
        val done = when {
            installed -> total
            fp != null -> fp.bytesDone.coerceIn(0L, total)
            else -> (if (f == ModelFile.NET) disk.netPartBytes else disk.voicePartBytes).coerceIn(0L, total)
        }
        SetupFileRow(f, installed, done, total, active = f == activeFile)
    }
}

// ---- Home ----

/** The Home card shown above the start card until setup is complete (design §1.4). */
data class HomeSetupCard(
    val bytesLeft: Long,
    val running: Boolean,
    val bytesDone: Long,
    val bytesTotal: Long,
    val gameWaiting: Boolean,
)

/** Null once both files are installed and nothing is running. */
fun homeSetupCard(disk: SetupState, sizes: SetupSizes, running: Boolean, progress: SetupProgress?, gameWaiting: Boolean): HomeSetupCard? {
    if (disk.complete && !running) return null
    val live = progress?.takeIf { running }
    return HomeSetupCard(
        bytesLeft = bytesLeftToDownload(disk, sizes),
        running = live != null,
        bytesDone = live?.bytesDone ?: 0L,
        bytesTotal = live?.bytesTotal ?: 0L,
        gameWaiting = gameWaiting,
    )
}

// ---- Throttle ----

/**
 * True when [b] differs from [a] in anything but byte counts: the status, the retry attempt, a pause or
 * failure reason, or a file's state. Such a snapshot is always published at once; byte-only updates
 * are throttled.
 */
fun isStructuralChange(a: SetupProgress, b: SetupProgress): Boolean =
    a.status != b.status || a.retryAttempt != b.retryAttempt || a.pauseReason != b.pauseReason ||
        a.failure != b.failure || a.perFile.map { it.file to it.state } != b.perFile.map { it.file to it.state }

/**
 * `ModelSetup` reports progress about every 256 KB on an IO thread (a few hundred times a second on a
 * fast connection). The UI takes at most one byte-only update per [minIntervalMs] (10 Hz), the
 * notification one per 500 ms (as the export does); structural changes always pass.
 */
class ProgressThrottle(private val minIntervalMs: Long) {
    private var last: SetupProgress? = null
    private var lastAtMs = 0L

    @Synchronized
    fun shouldEmit(p: SetupProgress, nowMs: Long): Boolean {
        val prev = last
        if (prev == null || isStructuralChange(prev, p) || nowMs - lastAtMs >= minIntervalMs) {
            last = p
            lastAtMs = nowMs
            return true
        }
        return false
    }

    @Synchronized
    fun reset() {
        last = null
        lastAtMs = 0L
    }
}
