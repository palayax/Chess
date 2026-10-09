package net.palaya.chessanalyzer.ui.model

import net.palaya.chessanalyzer.data.models.FailureReason
import net.palaya.chessanalyzer.data.models.ModelFile
import net.palaya.chessanalyzer.data.models.SetupProgress
import net.palaya.chessanalyzer.data.models.SetupStatus

/**
 * C2: what the Settings "Commentary" row shows (docs/LLM_REPHRASE_DESIGN.md §7, owner decisions §12). Pure, host-tested
 * in `RephraseSettingsLogicTest`. Default off; the switch appears once the model is installed (and is on after a
 * download, §12.3); the download runs through the setup service (`ModelDownloadService`), so pause, resume, the
 * notification and "no network on its own" are the setup's.
 */
enum class RephraseRow {
    /** 32-bit ABI, an x86_64 CPU without AVX2, or under 4 GB of RAM: "Not available on this phone." */
    UNAVAILABLE,

    /** Not installed, nothing on disk: Download (with the size). */
    NOT_INSTALLED,

    /** The service is fetching it now: progress, Pause, Cancel. */
    DOWNLOADING,

    /** A part on disk and no run: "Paused at N%", Resume, Cancel. */
    PAUSED,

    /** The last run failed on the wording model: the reason, Try again. */
    FAILED,

    /** Installed: the switch, "Remove the model", the cache line. */
    INSTALLED,
}

data class RephraseRowView(
    val row: RephraseRow,
    val enabled: Boolean,
    val bytesDone: Long,
    val bytesTotal: Long,
    val failure: FailureReason? = null,
) {
    val percent: Int get() = if (bytesTotal > 0) ((bytesDone * 100) / bytesTotal).toInt().coerceIn(0, 100) else 0
}

/**
 * @param available the phone can run the model (RephraseSupport); [installed] / [partBytes] / [wanted] the store's
 * cheap facts; [running] / [progress] the setup service's state; [enabled] the setting.
 */
fun rephraseRowView(
    available: Boolean,
    installed: Boolean,
    wanted: Boolean,
    partBytes: Long,
    sizeBytes: Long,
    enabled: Boolean,
    running: Boolean,
    progress: SetupProgress?,
): RephraseRowView {
    if (!available) return RephraseRowView(RephraseRow.UNAVAILABLE, false, 0, sizeBytes)
    if (installed) return RephraseRowView(RephraseRow.INSTALLED, enabled, sizeBytes, sizeBytes)
    val mine = progress?.perFile?.firstOrNull { it.file == ModelFile.REPHRASE }
    if (running && wanted) {
        return RephraseRowView(RephraseRow.DOWNLOADING, false, mine?.bytesDone ?: partBytes, sizeBytes)
    }
    if (!running && wanted && progress?.status == SetupStatus.FAILED && mine != null) {
        return RephraseRowView(RephraseRow.FAILED, false, partBytes, sizeBytes, progress.failure)
    }
    if (wanted && partBytes > 0) return RephraseRowView(RephraseRow.PAUSED, false, partBytes, sizeBytes)
    return RephraseRowView(RephraseRow.NOT_INSTALLED, false, partBytes, sizeBytes)
}
