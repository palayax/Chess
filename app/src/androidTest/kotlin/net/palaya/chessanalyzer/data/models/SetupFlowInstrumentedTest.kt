package net.palaya.chessanalyzer.data.models

import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import net.palaya.chessanalyzer.MainActivity
import net.palaya.chessanalyzer.TestApp
import net.palaya.chessanalyzer.data.models.FaultHttpServer.Fault
import net.palaya.chessanalyzer.engine.NetStore
import net.palaya.chessanalyzer.video.VoiceStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * [ModelDownloadService], the real foreground service the Setup screen starts (D2c), on a device
 * (docs/MODEL_DOWNLOAD_DESIGN.md §6.2, D2d). The service is driven exactly as the app drives it
 * (`ModelDownloadService.start` from a foreground Activity, `pause()`, the notification's Cancel intent),
 * against [FaultHttpServer] **in-process on 127.0.0.1** (not the emulator's NAT to the host, which loses
 * bytes on fast transfers: RUN_LOG D2c) and a fresh scratch directory: the app's [ModelSetup] is pointed
 * at it through `ChessAnalyzerApplication.modelSetupForTesting`, so the app's own installed models are
 * never touched.
 *
 * The two files are small stand-ins ([TestModelFiles]: a net whose NNUE header matches its own pins, a
 * Kokoro-shaped tar) served at [SLOW_BYTES_PER_SECOND], so a run lasts about ten seconds and every state
 * can be caught on the way: the real 201 MB of files go through the same code in
 * [ModelSetupInstrumentedTest]. Covered here: the foreground-service type as the PLATFORM reports it
 * (dataSync), pause and resume (a Range from the kept part), Cancel from the notification (parts deleted,
 * an installed net kept), Cancel of a paused setup, the Activity destroyed mid-download, and a start intent
 * that no tap queued doing nothing (no request: the app never downloads on its own).
 */
@RunWith(AndroidJUnit4::class)
class SetupFlowInstrumentedTest {

    private companion object {
        const val TAG = "SetupFlowTest"
        const val SLOW_BYTES_PER_SECOND = 500_000L
        const val TIMEOUT_MS = 120_000L
    }

    private val context get() = TestApp.context
    private val app get() = TestApp.app

    private val netBytes = TestModelFiles.netBytes(size = 3_000_000, seed = 11)
    private val netPins = TestModelFiles.pinsFor(netBytes)
    private val voiceTar = TestModelFiles.voiceTar(payload = 2_000_000)
    // What setup really downloads since D2f: the tar gzipped (random payload, so about the same size).
    private val voiceGz = TestModelFiles.gzip(voiceTar)

    private val netPath = "${GeneratedModelPins.RELEASE_TAG}/${netPins.fileName}"
    private val voicePath = "${GeneratedModelPins.RELEASE_TAG}/${GeneratedModelPins.VOICE_FILE_NAME}"

    private lateinit var dir: File
    private lateinit var server: FaultHttpServer
    private lateinit var netStore: NetStore
    private lateinit var voiceStore: VoiceStore
    private var scenario: ActivityScenario<MainActivity>? = null

    @Before
    fun setUp(): Unit = runBlocking {
        dir = File(context.filesDir, "setup-flow-test-${System.nanoTime()}").apply { mkdirs() }
        server = FaultHttpServer().serve(netPath, netBytes).serve(voicePath, voiceGz)
        server.alwaysFault(netPath, Fault.Slow(SLOW_BYTES_PER_SECOND))
        server.alwaysFault(voicePath, Fault.Slow(SLOW_BYTES_PER_SECOND))
        netStore = NetStore(dir, netPins)
        voiceStore = TestModelFiles.voiceStoreFor(dir, voiceTar, download = voiceGz)
        app.modelSetupForTesting = ModelSetup(
            netStore = netStore,
            voiceStore = voiceStore,
            downloader = ModelDownloader(userAgent = "PalayaChess/test (Android)", allowCleartextLoopback = true),
            baseUrl = server.baseUrl,
            freeBytes = { Long.MAX_VALUE },
        )
        establishIdleService()
    }

    @After
    fun tearDown(): Unit = runBlocking {
        try {
            establishIdleService()
        } finally {
            scenario?.close()
            app.modelSetupForTesting = null
            server.close()
            dir.deleteRecursively()
        }
    }

    /**
     * Nothing in flight and no snapshot left over, ESTABLISHED (instrumented tests share one process and
     * the service keeps its state in its companion). Cancel with nothing running deletes the scratch
     * parts and clears the snapshot.
     */
    private suspend fun establishIdleService() {
        if (ModelDownloadService.running.value) {
            ModelDownloadService.pause()
            withTimeout(TIMEOUT_MS) { ModelDownloadService.running.first { !it } }
        }
        val cleared = AtomicBoolean(false)
        ModelDownloadService.cancel(context) { cleared.set(true) }
        withTimeout(TIMEOUT_MS) { while (!cleared.get()) delay(50) }
        assertFalse(ModelDownloadService.running.value)
        assertNull("no snapshot may be left over", ModelDownloadService.progress.value)
    }

    private fun launchActivity(): ActivityScenario<MainActivity> =
        ActivityScenario.launch(MainActivity::class.java).also { scenario = it }

    /** The Setup screen's tap: the service may only be started from the foreground. */
    private fun tapDownload(s: ActivityScenario<MainActivity>) {
        val started = AtomicBoolean(false)
        s.onActivity { activity -> started.set(ModelDownloadService.start(activity)) }
        assertTrue("ModelDownloadService.start() must accept the run", started.get())
    }

    private suspend fun awaitProgress(what: String, predicate: (SetupProgress) -> Boolean): SetupProgress = try {
        withTimeout(TIMEOUT_MS) { ModelDownloadService.progress.first { it != null && predicate(it) }!! }
    } catch (e: Exception) {
        throw AssertionError("timed out waiting for $what; last snapshot ${ModelDownloadService.progress.value}", e)
    }

    private suspend fun awaitStopped(): SetupProgress? {
        withTimeout(TIMEOUT_MS) { ModelDownloadService.running.first { !it } }
        return ModelDownloadService.progress.value
    }

    private fun fileProgress(p: SetupProgress, file: ModelFile) = p.perFile.firstOrNull { it.file == file }

    private fun assertBothInstalled() {
        assertNotNull("the net passed the engine gate", netStore.verifiedNetOrNull())
        assertTrue("the voice is installed", voiceStore.isInstalled())
        assertTrue("no part file is left", dir.walkTopDown().none { it.isFile && it.name.endsWith(".part") })
    }

    /** `dumpsys activity services` for our package, as the platform sees the running service. */
    private fun dumpsysServices(): String {
        val pfd = InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand("dumpsys activity services ${context.packageName}")
        return ParcelFileDescriptor.AutoCloseInputStream(pfd).use { it.readBytes().toString(Charsets.UTF_8) }
    }

    private val serviceRecord = Regex("""ServiceRecord\{[^}]*ModelDownloadService""")

    /** Waits until ActivityManager no longer lists the service (it stops itself right after the run ends). */
    private suspend fun assertServiceGone() {
        val deadline = System.currentTimeMillis() + 15_000
        var dump = dumpsysServices()
        while (serviceRecord.containsMatchIn(dump) && System.currentTimeMillis() < deadline) {
            delay(250)
            dump = dumpsysServices()
        }
        assertFalse("the service must have stopped itself:\n${dump.take(1500)}", serviceRecord.containsMatchIn(dump))
    }

    @Test
    fun theDownloadRunsAsADataSyncForegroundServiceAndInstallsBothFiles(): Unit = runBlocking {
        val s = launchActivity()
        tapDownload(s)
        awaitProgress("the net to be downloading") { (fileProgress(it, ModelFile.NET)?.bytesDone ?: 0) > 0 }

        // The platform's own answer (getForegroundServiceType(), recorded right after startForeground):
        // a refused or masked type would not stop the download, so only this shows it.
        assertEquals(
            "the platform's foreground-service type for the running download",
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            ModelDownloadService.lastForegroundServiceType,
        )
        // And ActivityManager's record of the service (D2c read the same lines by hand).
        val dump = dumpsysServices()
        val start = serviceRecord.find(dump)?.range?.first
        val record = if (start == null) "" else dump.substring(start).let { rest ->
            // Up to the next service record, so another service's lines cannot answer for this one.
            val next = Regex("""\n\s*\* ServiceRecord""").find(rest, 1)?.range?.first ?: rest.length
            rest.substring(0, next)
        }
        android.util.Log.i(TAG, "dumpsys while downloading: " + record.lines().filter { "isForeground" in it || "types=" in it }.joinToString(" | ") { it.trim() })
        assertTrue("dumpsys must list the running ModelDownloadService:\n${dump.take(2000)}", record.isNotEmpty())
        assertTrue("dumpsys must show it in the foreground:\n${record.take(1500)}", record.contains("isForeground=true"))
        assertTrue(
            "dumpsys must show type dataSync (0x00000001):\n${record.take(1500)}",
            Regex("""types=(0x)?0*1(?![0-9a-fA-F])""").containsMatchIn(record),
        )

        val terminal = awaitStopped()
        assertEquals(SetupStatus.DONE, terminal?.status)
        assertEquals(1f, terminal!!.overallFraction, 0f)
        assertBothInstalled()
        assertEquals("one request per file", 1, server.requestsFor(netPath).size)
        assertEquals(1, server.requestsFor(voicePath).size)
        assertServiceGone()
    }

    @Test
    fun pauseKeepsThePartAndResumeContinuesFromItWithARange(): Unit = runBlocking {
        val s = launchActivity()
        tapDownload(s)
        awaitProgress("1 MB of the net") { (fileProgress(it, ModelFile.NET)?.bytesDone ?: 0) >= 1_000_000 }

        ModelDownloadService.pause() // the Setup screen's Pause
        val paused = awaitStopped()
        assertEquals(SetupStatus.PAUSED, paused?.status)
        assertEquals(PauseReason.USER, paused?.pauseReason)
        val kept = netStore.partFileFor().length()
        assertTrue("Pause keeps the part ($kept bytes)", kept in 1 until netBytes.size)
        assertNull("nothing is installed yet", netStore.activeNetOrNull())
        assertEquals("the app's view of the disk shows the part", kept, app.modelSetup.state().netPartBytes)

        tapDownload(s) // Resume
        val done = awaitStopped()
        assertEquals(SetupStatus.DONE, done?.status)
        val netRequests = server.requestsFor(netPath)
        assertEquals("one request, then one resume: ${netRequests.map { it.range }}", 2, netRequests.size)
        assertEquals("the resume asks for exactly what is missing", "bytes=$kept-", netRequests[1].range)
        assertBothInstalled()
    }

    @Test
    fun theDownloadSurvivesItsActivityBeingDestroyed(): Unit = runBlocking {
        val s = launchActivity()
        tapDownload(s)
        val inFlight = awaitProgress("the download to be under way") { (fileProgress(it, ModelFile.NET)?.bytesDone ?: 0) > 0 }
        assertTrue(ModelDownloadService.running.value)

        // Pull the rug out: the Activity that started it is destroyed mid-download.
        s.close()
        scenario = null
        android.util.Log.i(TAG, "activity destroyed at ${inFlight.bytesDone} of ${inFlight.bytesTotal} bytes")

        val terminal = awaitStopped()
        assertEquals("the run must finish without its Activity", SetupStatus.DONE, terminal?.status)
        assertBothInstalled()
    }

    @Test
    fun cancelFromTheNotificationStopsTheRunDeletesThePartAndKeepsTheInstalledNet(): Unit = runBlocking {
        val s = launchActivity()
        tapDownload(s)
        awaitProgress("the voice to be downloading") { p ->
            fileProgress(p, ModelFile.NET)?.done == true && (fileProgress(p, ModelFile.VOICE)?.bytesDone ?: 0) > 0
        }
        assertTrue("a voice part exists before the cancel", voiceStore.partFile.length() > 0)

        // The very Intent the ongoing notification's Cancel action carries.
        s.onActivity { activity ->
            activity.startService(Intent(activity, ModelDownloadService::class.java).setAction(ModelDownloadService.ACTION_CANCEL))
        }
        awaitStopped()
        // The run's tail clears `running` BEFORE it publishes the terminal snapshot (null for a cancel), so the
        // last progress snapshot (the voice "Paused") can still be read for an instant after `running` turns
        // false (G1-device: 2 of 3 runs on chess34 read it). Wait for the terminal value; a cancel that ended as
        // a pause would keep its PAUSED snapshot and still fail here.
        val terminal = runCatching { withTimeout(5_000) { ModelDownloadService.progress.first { it == null } } }
            .getOrElse { ModelDownloadService.progress.value }
        assertNull("a cancelled setup leaves no snapshot", terminal)
        assertFalse("Cancel deletes the voice part", voiceStore.partFile.exists())
        assertFalse(voiceStore.isInstalled())
        assertNotNull("Cancel keeps the installed net", netStore.verifiedNetOrNull())
        val voiceRequests = server.requestsFor(voicePath).size
        delay(1_500)
        assertEquals("nothing is fetched after a cancel", voiceRequests, server.requestsFor(voicePath).size)
    }

    @Test
    fun cancellingAPausedSetupDeletesItsParts(): Unit = runBlocking {
        val s = launchActivity()
        tapDownload(s)
        awaitProgress("part of the net") { (fileProgress(it, ModelFile.NET)?.bytesDone ?: 0) >= 500_000 }
        ModelDownloadService.pause()
        assertEquals(SetupStatus.PAUSED, awaitStopped()?.status)
        assertTrue(netStore.partFileFor().exists())

        val cleared = AtomicBoolean(false)
        ModelDownloadService.cancel(context) { cleared.set(true) } // the Setup screen's Cancel, no run in flight
        withTimeout(TIMEOUT_MS) { while (!cleared.get()) delay(50) }
        assertFalse("the paused part is deleted", netStore.partFileFor().exists())
        assertNull(ModelDownloadService.progress.value)
        assertFalse(ModelDownloadService.running.value)
    }

    @Test
    fun aStartIntentThatNoTapQueuedDownloadsNothing(): Unit = runBlocking {
        val s = launchActivity()
        // What a redelivered or stray intent looks like: ACTION_START with no ModelDownloadService.start().
        s.onActivity { activity ->
            activity.startService(Intent(activity, ModelDownloadService::class.java).setAction(ModelDownloadService.ACTION_START))
        }
        delay(3_000)
        assertTrue("the app must not download on its own: ${server.requests}", server.requests.isEmpty())
        assertFalse(ModelDownloadService.running.value)
        assertNull(ModelDownloadService.progress.value)
        assertTrue("nothing is written", dir.walkTopDown().none { it.isFile })
        assertServiceGone()
    }
}
