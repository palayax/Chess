package net.palaya.chessanalyzer.video

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.palaya.chessanalyzer.MainActivity
import net.palaya.chessanalyzer.R
import net.palaya.chessanalyzer.core.narration.VideoScript

/**
 * Runs a [VideoExporter] export as a **started foreground service**, so it survives the user
 * navigating away from the video screen or backgrounding the app.
 *
 * ## Why this exists
 * Export used to run in `VideoScreen`'s `rememberCoroutineScope()`. That scope dies with the
 * composition, so popping the back stack — or anything that recreated the Activity — cancelled a
 * render that takes tens of minutes on a slow device. A started foreground service is the only
 * Android primitive that keeps CPU-bound work alive across that, and it keeps the user informed
 * (and able to cancel) via an ongoing notification.
 *
 * ## Foreground service type
 * `dataSync` (`FOREGROUND_SERVICE_TYPE_DATA_SYNC`). The app's `compileSdk`/`targetSdk` are both
 * **34**, so the typed permission `FOREGROUND_SERVICE_DATA_SYNC` is mandatory — Android 14 throws
 * from `startForeground()` for an untyped/unpermitted FGS. The arguably better-fitting
 * `mediaProcessing` type (and its permission) only exists from **API 35**, so it is not
 * expressible against this compileSdk; revisit when the project moves to 35+.
 *
 * ## State ownership
 * Export state lives in this class's **companion object**, not in the service instance and not in
 * any composable — that is what lets `VideoScreen` be destroyed and recreated mid-export and still
 * show the in-flight progress rather than a fresh idle state. The service instance owns only the
 * coroutine scope doing the work.
 *
 * ## Notifications are optional, deliberately
 * `POST_NOTIFICATIONS` is a runtime permission from API 33. If the user denies it the platform
 * still starts and runs the foreground service; it merely suppresses the notification. Every
 * notification call here is best-effort and failure is swallowed, so **a denied notification
 * permission never fails an export**. The UI never waits on the permission result either.
 */
class VideoExportService : Service() {

    /** One export request. Not an Intent extra: [VideoScript] and the provider aren't parcelable. */
    private data class Request(
        val script: VideoScript,
        val provider: NarrationVoiceProvider?,
        val baseName: String,
    )

    companion object {
        private const val TAG = "VideoExportService"

        const val ACTION_START = "net.palaya.chessanalyzer.action.START_VIDEO_EXPORT"
        const val ACTION_CANCEL = "net.palaya.chessanalyzer.action.CANCEL_VIDEO_EXPORT"

        private const val CHANNEL_PROGRESS = "video_export_progress"
        private const val CHANNEL_DONE = "video_export_done"
        private const val NOTIFICATION_ID_PROGRESS = 4101
        private const val NOTIFICATION_ID_DONE = 4102

        /** Don't hammer NotificationManager from the per-frame render loop. */
        private const val NOTIFICATION_MIN_INTERVAL_MS = 500L

        private val _state = MutableStateFlow<VideoExporter.State>(VideoExporter.State.Idle)

        /**
         * Process-wide export state. Survives every Activity/composition, so re-entering the video
         * screen mid-export shows real progress.
         */
        val state: StateFlow<VideoExporter.State> = _state.asStateFlow()

        private val _exportedUri = MutableStateFlow<Uri?>(null)

        /** MediaStore (or FileProvider fallback) URI of the finished MP4; null until it completes. */
        val exportedUri: StateFlow<Uri?> = _exportedUri.asStateFlow()

        private val _running = MutableStateFlow(false)

        /** True from [start] until the export reaches a terminal state. */
        val running: StateFlow<Boolean> = _running.asStateFlow()

        @Volatile private var pendingRequest: Request? = null
        @Volatile private var activeExporter: VideoExporter? = null
        @Volatile private var cancelPending = false

        /** Default output base name; overridable so a test can find (or prove the absence of) the file. */
        fun defaultBaseName(): String = "chess_review_${System.currentTimeMillis()}"

        /**
         * Starts an export in the foreground service. Returns false if one is already running
         * (a second tap is a no-op) or if the platform refused the foreground start.
         *
         * State is moved out of [VideoExporter.State.Idle] *synchronously* here so the caller's
         * progress dialog can be driven purely off [state] with no gap between the tap and
         * `onStartCommand` actually landing.
         */
        fun start(
            context: Context,
            script: VideoScript,
            provider: NarrationVoiceProvider? = null,
            baseName: String = defaultBaseName(),
        ): Boolean {
            if (_running.value) return false
            _running.value = true
            cancelPending = false
            pendingRequest = Request(script, provider, baseName)
            _exportedUri.value = null
            _state.value = VideoExporter.State.SynthesizingNarration(0, script.segments.size)
            val appContext = context.applicationContext
            val intent = Intent(appContext, VideoExportService::class.java).setAction(ACTION_START)
            return try {
                ContextCompat.startForegroundService(appContext, intent)
                true
            } catch (e: Exception) {
                Log.e(TAG, "startForegroundService refused", e)
                pendingRequest = null
                _running.value = false
                _state.value = VideoExporter.State.Failed(e.message ?: e.javaClass.simpleName)
                false
            }
        }

        /** Cooperatively cancels the running export (from the UI or the notification action). */
        fun requestCancel() {
            // Both, unconditionally, and in this order: a cancel can land in the window between
            // start() and onStartCommand() actually constructing the exporter. Setting the flag
            // first means runExport()'s own `if (cancelPending)` check can never miss it,
            // whichever side of that window the cancel arrives on.
            cancelPending = true
            activeExporter?.cancel()
        }

        /**
         * Clears a terminal state back to [VideoExporter.State.Idle] once the user has dismissed
         * the result dialog. No-op while an export is still running.
         */
        fun acknowledgeTerminalState() {
            if (_running.value) return
            _state.value = VideoExporter.State.Idle
            _exportedUri.value = null
        }

        /** Test-only: drop any terminal state and URI regardless of [running]. */
        fun resetForTesting() {
            _state.value = VideoExporter.State.Idle
            _exportedUri.value = null
        }
    }

    /**
     * The export's scope. Tied to the *service*, which is exactly the point — nothing here is
     * reachable from a composition or an Activity, so neither can cancel it.
     */
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var exportJob: Job? = null
    private var notificationJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannels(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            requestCancel()
            // A cancel for an export that already finished (stale notification action): there is
            // nothing to keep the service alive for.
            if (exportJob?.isActive != true) stopForegroundAndSelf()
            return START_NOT_STICKY
        }

        // startForegroundService() gives us ~5 seconds to call startForeground(), whatever else
        // happens below — do it first.
        startForegroundSafely(buildProgressNotification(_state.value))

        val request = pendingRequest
        if (request == null || exportJob?.isActive == true) {
            // Nothing queued (e.g. a redelivered intent), or an export is already in flight.
            if (exportJob?.isActive != true) stopForegroundAndSelf()
            return START_NOT_STICKY
        }
        pendingRequest = null
        runExport(request)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun runExport(request: Request) {
        val exporter = VideoExporter(applicationContext)
        activeExporter = exporter

        // Mirror non-terminal progress into the companion StateFlow and the notification. Terminal
        // states are published by the export job itself so this collector can never race ahead of
        // (or overwrite) the final result.
        notificationJob = serviceScope.launch {
            var lastNotifiedAt = 0L
            exporter.state.collect { s ->
                // Re-assert a cancel that arrived before export() actually began. This cannot be
                // done once up front: VideoExporter.export() clears its own `cancelRequested`
                // flag as its very first statement, so an exporter.cancel() issued before that
                // is silently wiped. Re-checking on every state emission is what makes a cancel
                // that races the service's own startup deterministic instead of a coin flip —
                // the first emission the exporter makes is already past that reset.
                if (cancelPending) exporter.cancel()
                when (s) {
                    is VideoExporter.State.Completed,
                    is VideoExporter.State.Failed,
                    VideoExporter.State.Cancelled,
                    VideoExporter.State.Idle,
                    -> Unit
                    else -> {
                        _state.value = s
                        val now = SystemClock.elapsedRealtime()
                        if (now - lastNotifiedAt >= NOTIFICATION_MIN_INTERVAL_MS) {
                            lastNotifiedAt = now
                            notifySafely(NOTIFICATION_ID_PROGRESS, buildProgressNotification(s))
                        }
                    }
                }
            }
        }

        exportJob = serviceScope.launch {
            val terminal: VideoExporter.State = try {
                val file = exporter.export(request.script, request.baseName, request.provider)
                val published = MediaStorePublisher.publishToMovies(
                    applicationContext, file, "${request.baseName}.mp4",
                )
                val uri = published ?: MediaStorePublisher.shareUriFor(applicationContext, file)
                _exportedUri.value = uri
                (exporter.state.value as? VideoExporter.State.Completed)
                    ?.copy(mediaStoreUri = published)
                    ?: VideoExporter.State.Completed(
                        file = file,
                        mediaStoreUri = published,
                        durationMs = 0L,
                        fileSizeBytes = file.length(),
                        narrationWasSpoken = false,
                    )
            } catch (e: VideoExportCancelledException) {
                VideoExporter.State.Cancelled
            } catch (e: Exception) {
                Log.e(TAG, "export failed", e)
                VideoExporter.State.Failed(e.message ?: e.javaClass.simpleName)
            }

            withContext(NonCancellable) {
                notificationJob?.cancelAndJoin()
                notificationJob = null
                activeExporter = null
                cancelPending = false
                // Order matters: clear `running` BEFORE publishing the terminal state. Observers
                // wait on `state` and then read `running` (the dialog does exactly this to decide
                // whether Cancel or Close is the right button), so publishing the terminal state
                // first leaves a window where an observer sees "Completed" and "still running".
                _running.value = false
                _state.value = terminal
                postTerminalNotification(terminal, _exportedUri.value)
                stopForegroundAndSelf()
            }
        }
    }

    // ---------------------------------------------------------------------------------------
    // Notifications — every one of these is best-effort. See the class doc: a denied
    // POST_NOTIFICATIONS must degrade the UX, never the export.
    // ---------------------------------------------------------------------------------------

    private fun startForegroundSafely(notification: Notification) {
        try {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID_PROGRESS,
                notification,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                } else {
                    0
                },
            )
        } catch (e: Exception) {
            // Denied POST_NOTIFICATIONS does NOT land here (the platform runs the FGS and just
            // hides the notification). A restrictive background-start policy can. Either way the
            // export continues in serviceScope rather than being aborted.
            Log.w(TAG, "startForeground refused; export continues without a foreground notification", e)
        }
    }

    private fun notifySafely(id: Int, notification: Notification) {
        try {
            NotificationManagerCompat.from(this).notify(id, notification)
        } catch (e: Exception) {
            Log.w(TAG, "notify($id) suppressed", e)
        }
    }

    private fun stopForegroundAndSelf() {
        runCatching { ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE) }
        stopSelf()
    }

    private fun buildProgressNotification(state: VideoExporter.State): Notification {
        var text = getString(R.string.video_export_notification_title)
        var progress = 0
        var indeterminate = true
        when (state) {
            is VideoExporter.State.SynthesizingNarration -> {
                text = getString(R.string.video_export_synthesizing, state.completed, state.total)
                if (state.total > 0) {
                    progress = (state.completed * 100) / state.total
                    indeterminate = false
                }
            }
            is VideoExporter.State.Rendering -> {
                text = getString(R.string.video_export_rendering)
                if (state.totalMs > 0) {
                    progress = ((state.elapsedMs * 100L) / state.totalMs).toInt().coerceIn(0, 100)
                    indeterminate = false
                }
            }
            VideoExporter.State.Finalizing -> text = getString(R.string.video_export_finalizing)
            else -> Unit
        }

        val cancelIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, VideoExportService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val openAppIntent = PendingIntent.getActivity(
            this,
            2,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        return NotificationCompat.Builder(this, CHANNEL_PROGRESS)
            .setSmallIcon(R.drawable.ic_notification_video_export)
            .setContentTitle(getString(R.string.video_export_notification_title))
            .setContentText(text)
            .setProgress(100, progress, indeterminate)
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(openAppIntent)
            .addAction(0, getString(R.string.video_export_cancel), cancelIntent)
            .build()
    }

    private fun postTerminalNotification(state: VideoExporter.State, uri: Uri?) {
        val builder = NotificationCompat.Builder(this, CHANNEL_DONE)
            .setSmallIcon(R.drawable.ic_notification_video_export)
            .setAutoCancel(true)
            .setOngoing(false)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
        when (state) {
            is VideoExporter.State.Completed -> {
                builder.setContentTitle(getString(R.string.video_export_completed_title))
                    .setContentText(getString(R.string.video_export_notification_done_text))
                if (uri != null) {
                    val view = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(uri, "video/mp4")
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    builder.setContentIntent(
                        PendingIntent.getActivity(
                            this,
                            3,
                            view,
                            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                        ),
                    )
                }
            }
            is VideoExporter.State.Failed -> {
                builder.setContentTitle(getString(R.string.video_export_failed_title))
                    .setContentText(state.message)
            }
            VideoExporter.State.Cancelled -> {
                builder.setContentTitle(getString(R.string.video_export_cancelled_title))
                    .setContentText(getString(R.string.video_export_notification_cancelled_text))
            }
            else -> return
        }
        notifySafely(NOTIFICATION_ID_DONE, builder.build())
    }

    private fun createChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_PROGRESS,
                context.getString(R.string.video_export_channel_progress),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = context.getString(R.string.video_export_channel_progress_desc)
                setShowBadge(false)
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_DONE,
                context.getString(R.string.video_export_channel_done),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = context.getString(R.string.video_export_channel_done_desc)
            },
        )
    }
}
