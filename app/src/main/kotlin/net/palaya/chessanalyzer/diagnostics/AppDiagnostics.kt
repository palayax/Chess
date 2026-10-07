package net.palaya.chessanalyzer.diagnostics

import android.app.Activity
import android.app.ActivityManager
import android.app.Application
import android.content.ClipData
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.core.content.FileProvider
import java.io.File
import net.palaya.chessanalyzer.BuildConfig

/**
 * The Android half of the diagnostic log (F1): gathers what [DiagnosticLog] writes, at the moments
 * that explain a failure on a real phone.
 *
 * - At every process start ([onProcessStart]): the app and device facts, and the **previous
 *   process's exit reasons** (`ActivityManager.getHistoricalProcessExitReasons`, API 30+; skipped
 *   below). Those records are what tell us whether Android killed the app in the background
 *   (LOW_MEMORY, EXCESSIVE_RESOURCE_USAGE, FREEZER, ...) or it crashed.
 * - An uncaught-exception handler that writes the crash, then chains to the previous handler (so
 *   the platform still shows its dialog and records the crash).
 * - Activity start/stop/recreation and memory-trim callbacks.
 *
 * Nothing here opens a socket (only `data/models/ModelDownloader.kt` does, and only after a tap on
 * Download or Check for updates). The log leaves the phone only through [shareIntent], which the user starts.
 */
class AppDiagnostics(private val app: Application, val log: DiagnosticLog) {

    fun onProcessStart() {
        installCrashHandler()
        log.log(TAG_LIFECYCLE, "process start (pid ${android.os.Process.myPid()})")
        log.log(TAG_DEVICE, formatDeviceInfo(deviceInfo()))
        app.registerActivityLifecycleCallbacks(lifecycleLogger)
        // A binder call; off the main thread so a slow system service cannot delay the first frame.
        Thread({ logExitReasons() }, "diagnostics-exit-reasons").start()
    }

    fun onTrimMemory(level: Int) = log.log(TAG_LIFECYCLE, "onTrimMemory ${trimLevelName(level)}")

    fun onLowMemory() = log.log(TAG_LIFECYCLE, "onLowMemory")

    fun deviceInfo(): DeviceInfo {
        val am = app.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mem = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        return DeviceInfo(
            appVersion = BuildConfig.VERSION_NAME,
            versionCode = BuildConfig.VERSION_CODE.toLong(),
            buildType = BuildConfig.BUILD_TYPE,
            manufacturer = Build.MANUFACTURER,
            model = Build.MODEL,
            androidRelease = Build.VERSION.RELEASE,
            sdkInt = Build.VERSION.SDK_INT,
            abis = Build.SUPPORTED_ABIS.toList(),
            cpuCores = Runtime.getRuntime().availableProcessors(),
            totalRamBytes = mem.totalMem,
            availRamBytes = mem.availMem,
            lowRamDevice = am.isLowRamDevice,
        )
    }

    /**
     * The share intent for the whole log: a snapshot in `cacheDir/diagnostics/` (covered by the
     * FileProvider's `cache-path`) as a text/plain `EXTRA_STREAM`, plus a short `EXTRA_TEXT`
     * summary for targets that read only text. Null when the snapshot cannot be written.
     */
    fun shareIntent(context: Context): Intent? = try {
        log.log(TAG_LIFECYCLE, "diagnostic log shared (${log.sizeBytes()} bytes)")
        val file = log.snapshot(File(File(context.cacheDir, SHARE_DIR), SHARE_FILE_NAME))
        val info = deviceInfo()
        val summary = shareSummary(
            appVersion = info.appVersion,
            buildType = info.buildType,
            device = "${info.manufacturer} ${info.model}",
            androidRelease = info.androidRelease,
            sdkInt = info.sdkInt,
            lastError = log.lastError,
        )
        buildDiagnosticShareIntent(context, file, summary)
    } catch (e: Exception) {
        log.error(TAG_LIFECYCLE, "could not prepare the diagnostic log for sharing", e)
        null
    }

    private fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                log.error(TAG_CRASH, "uncaught exception on thread \"${thread.name}\"", error)
            } catch (_: Throwable) {
                // Never let logging stand between the crash and the platform's own handler.
            }
            previous?.uncaughtException(thread, error)
        }
    }

    private fun logExitReasons() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            log.log(TAG_LIFECYCLE, "previous process exit reasons: not available below Android 11 (API 30)")
            return
        }
        try {
            val am = app.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val records = am.getHistoricalProcessExitReasons(app.packageName, 0, 5).map {
                ExitRecord(
                    reason = it.reason,
                    subReason = null,
                    description = it.description,
                    importance = it.importance,
                    timestampMs = it.timestamp,
                    status = it.status,
                    pssKb = it.pss,
                    rssKb = it.rss,
                )
            }
            val seenFile = File(log.dir, EXIT_SEEN_NAME)
            val lastLogged = seenFile.takeIf { it.isFile }?.readText()?.trim()?.toLongOrNull() ?: 0L
            val fresh = newExitRecords(records, lastLogged)
            if (fresh.isEmpty()) log.log(TAG_LIFECYCLE, "previous process exit: no new record")
            fresh.forEach { log.log(TAG_LIFECYCLE, formatExitRecord(it)) }
            records.maxOfOrNull { it.timestampMs }?.let { newest ->
                if (newest > lastLogged) { log.dir.mkdirs(); seenFile.writeText(newest.toString()) }
            }
        } catch (e: Exception) {
            log.error(TAG_LIFECYCLE, "could not read previous process exit reasons", e)
        }
    }

    private val lifecycleLogger = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
            val how = if (savedInstanceState == null) "fresh" else "recreated from saved state"
            log.log(TAG_LIFECYCLE, "${activity.javaClass.simpleName} onCreate ($how)")
        }
        override fun onActivityStarted(activity: Activity) = log.log(TAG_LIFECYCLE, "${activity.javaClass.simpleName} onStart")
        override fun onActivityStopped(activity: Activity) = log.log(TAG_LIFECYCLE, "${activity.javaClass.simpleName} onStop")
        override fun onActivityResumed(activity: Activity) = Unit
        override fun onActivityPaused(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
        override fun onActivityDestroyed(activity: Activity) {
            val why = if (activity.isChangingConfigurations) "configuration change" else if (activity.isFinishing) "finishing" else "by the system"
            log.log(TAG_LIFECYCLE, "${activity.javaClass.simpleName} onDestroy ($why)")
        }
    }

    companion object {
        const val TAG_LIFECYCLE = "lifecycle"
        const val TAG_DEVICE = "device"
        const val TAG_CRASH = "crash"
        const val TAG_ANALYSIS = "analysis"
        const val TAG_ENGINE = "engine"
        const val TAG_VIDEO = "video"

        const val SHARE_DIR = "diagnostics"
        const val SHARE_FILE_NAME = "palaya-chess-diagnostic-log.txt"
        const val EXIT_SEEN_NAME = "exit_reasons_seen"

        @Suppress("DEPRECATION")
        fun trimLevelName(level: Int): String = when (level) {
            ComponentCallbacks2.TRIM_MEMORY_COMPLETE -> "COMPLETE"
            ComponentCallbacks2.TRIM_MEMORY_MODERATE -> "MODERATE"
            ComponentCallbacks2.TRIM_MEMORY_BACKGROUND -> "BACKGROUND"
            ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN -> "UI_HIDDEN"
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL -> "RUNNING_CRITICAL"
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW -> "RUNNING_LOW"
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE -> "RUNNING_MODERATE"
            else -> "level $level"
        }
    }
}

/**
 * `ACTION_SEND` of [file] as text/plain through the app's FileProvider, with [summary] as
 * `EXTRA_TEXT`. Works with any target: an app that takes attachments gets the file (read access
 * granted for that one URI), one that takes only text gets the summary.
 */
internal fun buildDiagnosticShareIntent(context: Context, file: File, summary: String): Intent {
    val uri: Uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    return Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_TEXT, summary)
        putExtra(Intent.EXTRA_SUBJECT, "Palaya Chess diagnostic log")
        clipData = ClipData.newRawUri(null, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}
