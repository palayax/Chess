package net.palaya.chessanalyzer.data.models

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.palaya.chessanalyzer.ChessAnalyzerApplication
import net.palaya.chessanalyzer.MainActivity
import net.palaya.chessanalyzer.R
import net.palaya.chessanalyzer.ui.model.ProgressThrottle
import net.palaya.chessanalyzer.ui.model.SetupLine
import net.palaya.chessanalyzer.ui.model.megabytesLabel
import net.palaya.chessanalyzer.ui.model.progressMegabytes

/**
 * Runs the first-run model download ([ModelSetup.run]) as a **started foreground service** of type
 * `dataSync` (docs/MODEL_DOWNLOAD_DESIGN.md §1.5, D2c), so it carries on when the user leaves the Setup
 * screen or the app. The pattern is `VideoExportService`'s, which is proven on-device:
 *
 *  - **State in the companion**: [progress] and [running] are process-wide `StateFlow`s, so the Setup
 *    screen, the Home card and the Video notice can be recreated at any time and still show the run.
 *  - **The platform `Service.startForeground(id, n, type)`**, not `ServiceCompat.startForeground`:
 *    androidx.core 1.13.1 masks the type to the Android 14 set (CLAUDE.md, D1). dataSync is in that set,
 *    but one rule for both services is safer than remembering which type survives the mask.
 *  - **Only a user tap starts it.** [start] is called from the Setup screen's Download / Resume / Try
 *    again button and nowhere else; `onStartCommand` runs a download only when [start] queued one, so a
 *    stray or redelivered intent does nothing, and `START_NOT_STICKY` means a killed process is not
 *    restarted. A part file left by a kill shows as "Paused at N%" with Resume (design §1.1).
 *  - **Pause** cancels the coroutine (the part files stay; `ModelSetup` resumes them with a Range
 *    request); **Cancel** cancels it and then deletes the part files ([ModelSetup.discardPartials]).
 *    Both are also notification actions.
 *  - **Android 15's dataSync limit** (6 hours in 24): [onTimeout] pauses the download and stops at once.
 *  - **Notifications are optional**: POST_NOTIFICATIONS is asked for on the first Download tap, like the
 *    export does, and a denial only hides them (every notification call is best-effort).
 *
 * The progress callback arrives about every 256 KB on an IO thread; [ProgressThrottle] lets through
 * every change of status or file state at once and byte counts at 10 Hz (the UI) / 2 Hz (the
 * notification). Nothing here logs a URL: `ModelSetup` and `ModelDownloader` log hosts only.
 */
class ModelDownloadService : Service() {

    companion object {
        private const val TAG = "ModelDownloadService"

        const val ACTION_START = "net.palaya.chessanalyzer.action.START_MODEL_DOWNLOAD"
        const val ACTION_PAUSE = "net.palaya.chessanalyzer.action.PAUSE_MODEL_DOWNLOAD"
        const val ACTION_CANCEL = "net.palaya.chessanalyzer.action.CANCEL_MODEL_DOWNLOAD"

        const val CHANNEL_PROGRESS = "model_download_progress"
        const val CHANNEL_DONE = "model_download_done"
        const val NOTIFICATION_ID_PROGRESS = 4201
        const val NOTIFICATION_ID_DONE = 4202

        /** Byte-only updates: the screen at 10 Hz, the notification at 2 Hz (design §2.2 step 5). */
        const val UI_MIN_INTERVAL_MS = 100L
        const val NOTIFICATION_MIN_INTERVAL_MS = 500L

        private val _progress = MutableStateFlow<SetupProgress?>(null)

        /**
         * The last snapshot of the run in this process, or null (nothing ran, or the user cancelled).
         * A terminal snapshot (DONE / PAUSED / FAILED) stays until the next run, so the Setup screen can
         * say why it stopped.
         */
        val progress: StateFlow<SetupProgress?> = _progress.asStateFlow()

        private val _running = MutableStateFlow(false)

        /** True from [start] until the run has ended and its terminal snapshot is published. */
        val running: StateFlow<Boolean> = _running.asStateFlow()

        @Volatile private var startQueued = false
        @Volatile private var pauseRequested = false
        @Volatile private var cancelRequested = false
        @Volatile private var cancelFromNotification = false
        @Volatile private var pauseFromNotification = false
        @Volatile private var timedOut = false
        @Volatile private var activeJob: Job? = null

        /** The type the platform reports right after `startForeground()`, -1 before / when refused (read by tests). */
        @Volatile var lastForegroundServiceType: Int = -1
            private set

        /**
         * Starts (or resumes) the download. Call ONLY from the user's tap. Returns false when a run is
         * already in flight (a second tap is a no-op) or the platform refused the start.
         */
        fun start(context: Context): Boolean {
            if (_running.value) return false
            _running.value = true
            startQueued = true
            pauseRequested = false
            cancelRequested = false
            cancelFromNotification = false
            pauseFromNotification = false
            timedOut = false
            // Out of Idle at once, so the screen switches to the progress block with no gap.
            _progress.value = SetupProgress(
                overallFraction = _progress.value?.overallFraction ?: 0f,
                bytesDone = _progress.value?.bytesDone ?: 0L,
                bytesTotal = _progress.value?.bytesTotal ?: 0L,
                perFile = _progress.value?.perFile.orEmpty(),
                status = SetupStatus.CONNECTING,
            )
            val appContext = context.applicationContext
            return try {
                ContextCompat.startForegroundService(appContext, Intent(appContext, ModelDownloadService::class.java).setAction(ACTION_START))
                true
            } catch (e: Exception) {
                Log.e(TAG, "startForegroundService refused", e)
                startQueued = false
                _running.value = false
                _progress.value = null
                diagnostic(appContext, "setup could not start the download service: ${e.javaClass.simpleName}")
                false
            }
        }

        /** The Pause button (screen or notification): the part files stay. */
        fun pause() {
            pauseRequested = true
            activeJob?.cancel(CancellationException("paused by the user"))
        }

        /**
         * The Cancel button: stops a run and deletes every part file. With no run in flight (a paused or
         * failed setup, or one found on disk at launch) the parts are deleted directly.
         */
        fun cancel(context: Context, onDone: () -> Unit = {}) {
            cancelRequested = true
            val job = activeJob
            if (_running.value) {
                // The run's own NonCancellable tail deletes the parts and publishes null.
                job?.cancel(CancellationException("cancelled by the user"))
                onDone()
            } else {
                val app = context.applicationContext as? ChessAnalyzerApplication ?: return
                CoroutineScope(Dispatchers.IO).launch {
                    app.modelSetup.discardPartials()
                    _progress.value = null
                    cancelRequested = false
                    onDone()
                }
            }
        }

        private fun diagnostic(context: Context, message: String) {
            val app = context.applicationContext as? ChessAnalyzerApplication ?: return
            app.diagnostics.log.log(ModelSetup.TAG, message)
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val uiThrottle = ProgressThrottle(UI_MIN_INTERVAL_MS)
    private val notificationThrottle = ProgressThrottle(NOTIFICATION_MIN_INTERVAL_MS)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannels(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PAUSE -> {
                diagnostic(this, "pause tapped in the notification")
                pauseFromNotification = true
                pause()
                if (activeJob?.isActive != true) stopForegroundAndSelf()
                return START_NOT_STICKY
            }
            ACTION_CANCEL -> {
                diagnostic(this, "cancel tapped in the notification")
                cancelFromNotification = true
                cancel(this)
                if (activeJob?.isActive != true) stopForegroundAndSelf()
                return START_NOT_STICKY
            }
        }
        // startForegroundService() gives ~5 s to call startForeground(): first, whatever happens below.
        startForegroundSafely(buildProgressNotification(_progress.value))
        if (!startQueued || activeJob?.isActive == true) {
            // Nothing queued by a tap (a redelivered or stray intent), or a run already in flight.
            if (activeJob?.isActive != true) stopForegroundAndSelf()
            return START_NOT_STICKY
        }
        startQueued = false
        runSetup()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    /**
     * Android 15+: dataSync reached its 6-hours-in-24 limit. The system wants `stopSelf()` within
     * seconds. Pause (parts kept, the user can resume later) and stop now; 260 MB never gets near it.
     */
    override fun onTimeout(startId: Int, fgsType: Int) {
        Log.w(TAG, "foreground service time limit reached (type $fgsType); pausing the download")
        timedOut = true
        pause()
        stopForegroundAndSelf()
    }

    private fun runSetup() {
        val app = application as ChessAnalyzerApplication
        val setup = app.modelSetup
        uiThrottle.reset()
        notificationThrottle.reset()
        // A new run makes an earlier "Setup paused" / "Setup stopped" notification stale (seen on chess36).
        runCatching { NotificationManagerCompat.from(this).cancel(NOTIFICATION_ID_DONE) }
        val disk = setup.state()
        val resuming = disk.netPartBytes > 0 || disk.voicePartBytes > 0
        diagnostic(
            this,
            (if (resuming) "setup resumed by the user (part files on disk: net ${disk.netPartBytes}, voice ${disk.voicePartBytes} bytes)"
            else "setup started by the user") +
                ", notifications ${if (notificationsAllowed()) "allowed" else "not allowed"}, fgs type ${lastForegroundServiceType}",
        )
        // Ask the system to clear other apps' cache for setup's peak first (the space check counted it as
        // free: StorageManager.getAllocatableBytes). Best effort: a refusal surfaces as low storage below.
        app.reserveModelStorage(setup.storageNeeded())

        val job = serviceScope.launch {
            var last: SetupProgress? = null
            val outcome: SetupOutcome? = try {
                setup.run { p ->
                    last = p
                    val now = SystemClock.elapsedRealtime()
                    if (uiThrottle.shouldEmit(p, now)) _progress.value = p
                    // Only a running state goes into the ongoing notification, and none once Pause or Cancel
                    // was asked for: a late post could outlive stopForeground() (seen on chess36: the ongoing
                    // notification stayed after a Pause from the notification).
                    val live = p.status != SetupStatus.PAUSED && p.status != SetupStatus.FAILED && p.status != SetupStatus.DONE
                    if (live && !pauseRequested && !cancelRequested && notificationThrottle.shouldEmit(p, now)) {
                        notifySafely(NOTIFICATION_ID_PROGRESS, buildProgressNotification(p))
                    }
                }
            } catch (e: CancellationException) {
                null
            } catch (e: Exception) {
                Log.e(TAG, "setup failed", e)
                app.diagnostics.log.error(ModelSetup.TAG, "setup failed with an unexpected error", e)
                SetupOutcome.Failed(ModelFile.NET, FailureReason.INSTALL, e.message)
            }
            withContext(NonCancellable) {
                val terminal: SetupProgress? = when {
                    cancelRequested -> {
                        setup.discardPartials()
                        diagnostic(this@ModelDownloadService, "setup cancelled by the user; part files deleted")
                        null
                    }
                    outcome == null -> {
                        diagnostic(
                            this@ModelDownloadService,
                            if (timedOut) "setup paused: the dataSync foreground-service time limit was reached"
                            else "setup paused by the user",
                        )
                        (last ?: _progress.value)?.copy(status = SetupStatus.PAUSED, pauseReason = PauseReason.USER, failure = null, retryAttempt = 0)
                            ?: SetupProgress(0f, 0, 0, emptyList(), SetupStatus.PAUSED, pauseReason = PauseReason.USER)
                    }
                    outcome is SetupOutcome.Complete -> {
                        diagnostic(this@ModelDownloadService, "setup done: both files installed")
                        (last ?: SetupProgress(1f, 0, 0, emptyList(), SetupStatus.DONE)).copy(status = SetupStatus.DONE, overallFraction = 1f)
                    }
                    outcome is SetupOutcome.Paused -> {
                        diagnostic(this@ModelDownloadService, "setup paused: ${outcome.file} ${outcome.reason}")
                        (last ?: SetupProgress(0f, 0, 0, emptyList(), SetupStatus.PAUSED)).copy(status = SetupStatus.PAUSED, pauseReason = outcome.reason, failure = null)
                    }
                    else -> {
                        val f = outcome as SetupOutcome.Failed
                        diagnostic(this@ModelDownloadService, "setup failed: ${f.file} ${f.reason}")
                        (last ?: SetupProgress(0f, 0, 0, emptyList(), SetupStatus.FAILED)).copy(status = SetupStatus.FAILED, failure = f.reason, pauseReason = null)
                    }
                }
                activeJob = null
                postTerminalNotification(terminal)
                // The flags are reset BEFORE running goes false: from that moment a Resume tap may start
                // the next run (start() sets its own flags), and a late reset here would wipe them.
                cancelRequested = false
                cancelFromNotification = false
                pauseFromNotification = false
                pauseRequested = false
                timedOut = false
                // Running cleared BEFORE the terminal snapshot: an observer that sees the snapshot must
                // not also see "still running" (the export's ordering, for the same reason).
                _running.value = false
                _progress.value = terminal
                // Stop on the main thread, where start() and onStartCommand() run, and only if no new run
                // was queued meanwhile. A Resume tapped right after a Pause (the screen shows "Paused" as
                // soon as the snapshot above lands) has already called startForegroundService(); an
                // unconditional stopSelf() here destroyed the service before its startForeground(), and
                // the platform then kills the app with ForegroundServiceDidNotStartInTimeException
                // (found by SetupFlowInstrumentedTest on chess36, D2d).
                withContext(Dispatchers.Main) {
                    if (activeJob == null && !startQueued) stopForegroundAndSelf()
                }
            }
        }
        activeJob = job
        // A Pause or Cancel that landed between start() and here.
        if (pauseRequested || cancelRequested) job.cancel(CancellationException("stopped before it began"))
    }

    // ---- Notifications: best-effort, a denied POST_NOTIFICATIONS never affects the download ----

    private fun notificationsAllowed(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun startForegroundSafely(notification: Notification) {
        try {
            lastForegroundServiceType = -1
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // The platform call (see the class doc): dataSync on every API level this app runs on.
                startForeground(NOTIFICATION_ID_PROGRESS, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
                lastForegroundServiceType = foregroundServiceType
            } else {
                startForeground(NOTIFICATION_ID_PROGRESS, notification)
                lastForegroundServiceType = 0
            }
        } catch (e: Exception) {
            // The download still runs in serviceScope; only the foreground status is missing.
            Log.w(TAG, "startForeground refused; the download continues without a foreground notification", e)
            diagnostic(this, "startForeground refused: ${e.javaClass.simpleName}")
        }
    }

    private fun notifySafely(id: Int, notification: Notification) {
        if (!notificationsAllowed()) return
        try {
            NotificationManagerCompat.from(this).notify(id, notification)
        } catch (e: Exception) {
            Log.w(TAG, "notify($id) suppressed", e)
        }
    }

    private fun stopForegroundAndSelf() {
        runCatching { ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE) }
        // Also cancel it by id: the progress notification is re-posted with notify() as it moves, and
        // stopForeground alone left a copy behind once (chess36).
        runCatching { NotificationManagerCompat.from(this).cancel(NOTIFICATION_ID_PROGRESS) }
        stopSelf()
    }

    private fun openSetupIntent(requestCode: Int): PendingIntent = PendingIntent.getActivity(
        this,
        requestCode,
        Intent(this, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_OPEN_SETUP, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun serviceAction(action: String, requestCode: Int): PendingIntent = PendingIntent.getService(
        this,
        requestCode,
        Intent(this, ModelDownloadService::class.java).setAction(action),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** "Setting up Palaya Chess" / "120 MB of 257 MB" (or the status), determinate bar, Pause and Cancel. */
    private fun buildProgressNotification(p: SetupProgress?): Notification {
        val text = when (p?.status) {
            SetupStatus.DOWNLOADING -> {
                val (done, total) = progressMegabytes(p.bytesDone, p.bytesTotal)
                getString(R.string.setup_notification_progress, megabytesLabel(done), megabytesLabel(total))
            }
            SetupStatus.RETRYING -> getString(R.string.setup_status_retrying_short)
            SetupStatus.UNPACKING -> getString(R.string.setup_status_unpacking)
            else -> getString(R.string.setup_status_connecting)
        }
        val permille = ((p?.overallFraction ?: 0f).coerceIn(0f, 1f) * 1000).toInt()
        return NotificationCompat.Builder(this, CHANNEL_PROGRESS)
            .setSmallIcon(R.drawable.ic_notification_model_download)
            .setContentTitle(getString(R.string.setup_notification_title))
            .setContentText(text)
            .setProgress(1000, permille, p == null || p.status == SetupStatus.CONNECTING && permille == 0)
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(openSetupIntent(11))
            .addAction(0, getString(R.string.setup_pause), serviceAction(ACTION_PAUSE, 12))
            .addAction(0, getString(R.string.setup_cancel), serviceAction(ACTION_CANCEL, 13))
            .build()
    }

    /** "Palaya Chess is ready" / "Setup paused" / "Setup stopped" / (from the notification) "Setup cancelled". */
    private fun postTerminalNotification(p: SetupProgress?) {
        val builder = NotificationCompat.Builder(this, CHANNEL_DONE)
            .setSmallIcon(R.drawable.ic_notification_model_download)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(openSetupIntent(14))
        when {
            p == null -> {
                // A cancel from the screen needs no notification; one from the notification gets a receipt.
                if (!cancelFromNotification) return
                builder.setContentTitle(getString(R.string.setup_notification_cancelled))
            }
            p.status == SetupStatus.DONE -> builder.setContentTitle(getString(R.string.setup_notification_ready))
                .setContentText(getString(R.string.setup_notification_ready_text))
            // A Pause tapped on the Setup screen needs no notification: the user is looking at the result.
            p.status == SetupStatus.PAUSED && p.pauseReason == PauseReason.USER && !pauseFromNotification && !timedOut -> return
            p.status == SetupStatus.PAUSED -> builder.setContentTitle(getString(R.string.setup_notification_paused))
                .setContentText(getString(lineText(p)))
            else -> builder.setContentTitle(getString(R.string.setup_notification_stopped))
                .setContentText(getString(lineText(p)))
        }
        notifySafely(NOTIFICATION_ID_DONE, builder.build())
    }

    private fun lineText(p: SetupProgress): Int {
        val line = when {
            p.status == SetupStatus.PAUSED && p.pauseReason == PauseReason.CONNECTION_LOST -> SetupLine.CONNECTION_DROPPED
            p.status == SetupStatus.PAUSED && p.pauseReason == PauseReason.SERVER_UNAVAILABLE -> SetupLine.SERVER_UNAVAILABLE
            p.status == SetupStatus.PAUSED -> SetupLine.PAUSED
            p.failure == FailureReason.NOT_FOUND -> SetupLine.NOT_FOUND
            p.failure == FailureReason.DAMAGED -> SetupLine.DAMAGED
            p.failure == FailureReason.INSUFFICIENT_STORAGE -> SetupLine.LOW_STORAGE
            else -> SetupLine.FAILED
        }
        return when (line) {
            SetupLine.CONNECTION_DROPPED -> R.string.setup_status_connection_dropped
            SetupLine.SERVER_UNAVAILABLE -> R.string.setup_status_server_unavailable
            SetupLine.NOT_FOUND -> R.string.setup_status_not_found
            SetupLine.DAMAGED -> R.string.setup_status_damaged
            SetupLine.LOW_STORAGE -> R.string.setup_notification_low_storage
            SetupLine.PAUSED -> R.string.setup_status_paused
            else -> R.string.setup_status_failed
        }
    }

    private fun createChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_PROGRESS, context.getString(R.string.setup_channel_progress), NotificationManager.IMPORTANCE_LOW).apply {
                description = context.getString(R.string.setup_channel_progress_desc)
                setShowBadge(false)
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_DONE, context.getString(R.string.setup_channel_done), NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = context.getString(R.string.setup_channel_done_desc)
            },
        )
    }
}
