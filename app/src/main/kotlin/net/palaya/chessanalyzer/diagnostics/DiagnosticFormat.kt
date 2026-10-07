package net.palaya.chessanalyzer.diagnostics

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/*
 * What the diagnostic log says, as pure functions over plain values (host-tested in
 * DiagnosticFormatTest). The Android side only gathers the values (AppDiagnostics).
 */

/** The facts about the app and the phone logged at every process start. No identifiers: no serial, no Android ID. */
data class DeviceInfo(
    val appVersion: String,
    val versionCode: Long,
    val buildType: String,
    val manufacturer: String,
    val model: String,
    val androidRelease: String,
    val sdkInt: Int,
    val abis: List<String>,
    val cpuCores: Int,
    val totalRamBytes: Long,
    val availRamBytes: Long,
    val lowRamDevice: Boolean,
)

fun formatDeviceInfo(info: DeviceInfo): String = buildString {
    append("app ").append(info.appVersion).append(" (").append(info.versionCode).append(", ").append(info.buildType).append(")\n")
    append("device ").append(info.manufacturer).append(' ').append(info.model).append('\n')
    append("android ").append(info.androidRelease).append(" (API ").append(info.sdkInt).append(")\n")
    append("abi ").append(info.abis.joinToString(",")).append('\n')
    append("cpu cores ").append(info.cpuCores).append('\n')
    append("ram total ").append(mib(info.totalRamBytes)).append(" MiB, available ").append(mib(info.availRamBytes))
        .append(" MiB, low-ram device ").append(if (info.lowRamDevice) "yes" else "no")
}

private fun mib(bytes: Long): Long = bytes / (1024 * 1024)

/** One record of `ActivityManager.getHistoricalProcessExitReasons` (API 30+), as plain values. */
data class ExitRecord(
    val reason: Int,
    val subReason: String?,
    val description: String?,
    val importance: Int,
    val timestampMs: Long,
    val status: Int,
    val pssKb: Long,
    val rssKb: Long,
)

/** `ApplicationExitInfo.REASON_*` by value (literals, so the host test needs no Android). */
fun exitReasonName(reason: Int): String = when (reason) {
    0 -> "UNKNOWN"
    1 -> "EXIT_SELF"
    2 -> "SIGNALED"
    3 -> "LOW_MEMORY"
    4 -> "CRASH"
    5 -> "CRASH_NATIVE"
    6 -> "ANR"
    7 -> "INITIALIZATION_FAILURE"
    8 -> "PERMISSION_CHANGE"
    9 -> "EXCESSIVE_RESOURCE_USAGE"
    10 -> "USER_REQUESTED"
    11 -> "USER_STOPPED"
    12 -> "DEPENDENCY_DIED"
    13 -> "OTHER"
    14 -> "FREEZER"
    15 -> "PACKAGE_STATE_CHANGE"
    16 -> "PACKAGE_UPDATED"
    else -> "REASON_$reason"
}

/** `RunningAppProcessInfo.IMPORTANCE_*` by value: what the app was doing when it died. */
fun importanceName(importance: Int): String = when (importance) {
    100 -> "FOREGROUND"
    125 -> "FOREGROUND_SERVICE"
    150 -> "TOP_SLEEPING_PRE_28"
    200 -> "VISIBLE"
    230 -> "PERCEPTIBLE"
    300 -> "SERVICE"
    325 -> "TOP_SLEEPING"
    350 -> "CANT_SAVE_STATE"
    400 -> "CACHED"
    1000 -> "GONE"
    else -> "IMPORTANCE_$importance"
}

fun formatExitRecord(record: ExitRecord, zone: TimeZone = TimeZone.getDefault()): String {
    val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).apply { timeZone = zone }.format(Date(record.timestampMs))
    return buildString {
        append("previous process exit: ").append(exitReasonName(record.reason))
        record.subReason?.let { append(" / ").append(it) }
        append(" at ").append(stamp)
        append(", importance ").append(importanceName(record.importance))
        append(", status ").append(record.status)
        append(", pss ").append(record.pssKb).append(" KB, rss ").append(record.rssKb).append(" KB")
        if (!record.description.isNullOrBlank()) append(", description \"").append(record.description).append('"')
    }
}

/**
 * The exit records not logged yet: newer than [lastLoggedMs], oldest first, at most [limit] (the
 * platform keeps a few per app; the newest is the one that matters).
 */
fun newExitRecords(records: List<ExitRecord>, lastLoggedMs: Long, limit: Int = 5): List<ExitRecord> =
    records.filter { it.timestampMs > lastLoggedMs }.sortedBy { it.timestampMs }.takeLast(limit)

/** One analysed position, one line: what a slow or failing analysis needs to be understood. */
data class PositionLogLine(
    val index: Int,
    /** The move that led to this position, null for the start position. */
    val san: String?,
    val requestedDepth: Int,
    val reachedDepth: Int,
    val nodes: Long,
    val timeMs: Long,
    val capped: Boolean,
    /** "cp -35" / "mate 3" from the side to move, or "none" (terminal position). */
    val score: String,
)

fun formatPositionLine(p: PositionLogLine): String =
    "pos ${p.index} ${p.san ?: "start"} depth ${p.reachedDepth}/${p.requestedDepth} nodes ${p.nodes} " +
        "ms ${p.timeMs} capped ${if (p.capped) "yes" else "no"} score ${p.score}"

fun scoreText(scoreCp: Int?, mateIn: Int?): String = when {
    mateIn != null -> "mate $mateIn"
    scoreCp != null -> "cp $scoreCp"
    else -> "none"
}

/** The short text that goes with a shared log (`EXTRA_TEXT`): enough to triage without opening the file. */
fun shareSummary(appVersion: String, buildType: String, device: String, androidRelease: String, sdkInt: Int, lastError: String?): String =
    buildString {
        append("Palaya Chess ").append(appVersion).append(" (").append(buildType).append(")\n")
        append(device).append(", Android ").append(androidRelease).append(" (API ").append(sdkInt).append(")\n")
        append("Last error: ").append(lastError ?: "none")
    }

/**
 * The game being analysed, for the log: its tags, then the moves in SAN with move numbers (no
 * comments or clocks). It is the user's own game and the log only leaves the phone when they share it.
 */
fun formatGameForLog(game: net.palaya.chessanalyzer.core.pgn.PgnGame): String = buildString {
    append("tags:")
    if (game.tags.isEmpty()) append(" none")
    for ((k, v) in game.tags) append(" [").append(k).append(" \"").append(v).append("\"]")
    append("\nmoves:")
    for (m in game.moves) {
        if (m.color == net.palaya.chessanalyzer.core.chess.Color.WHITE) append(' ').append(m.moveNumber).append('.')
        else if (m === game.moves.first()) append(' ').append(m.moveNumber).append("...")
        append(' ').append(m.san)
    }
    append(' ').append(game.result)
}
