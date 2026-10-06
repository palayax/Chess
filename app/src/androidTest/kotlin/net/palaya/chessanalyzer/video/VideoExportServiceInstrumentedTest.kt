package net.palaya.chessanalyzer.video

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import net.palaya.chessanalyzer.MainActivity
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
 * Behavioural proof that video export is owned by [VideoExportService] — a started foreground
 * service — and not by whatever composable/Activity kicked it off.
 *
 * The defect this covers: export used to run in `VideoScreen`'s `rememberCoroutineScope()`, so
 * leaving the screen (or the Activity being destroyed) cancelled a render that takes ~23 minutes
 * of wall clock for a real 9-minute review on this emulator. "It compiles" proves nothing about
 * that, so these tests **destroy the Activity mid-export** and then require the export to finish
 * anyway, and require a cancel to stop it promptly and leave no partial MP4 behind.
 *
 * ## Why a truncated script
 * [TestScripts.shortScript] is ~2 s of video, not the ~9 minutes a real review runs. The property
 * under test is coroutine *ownership*, which is independent of script length; encoder fidelity is
 * [VideoExporterInstrumentedTest]'s job. A full-length script here would add ~23 minutes to every
 * suite run for no extra signal.
 *
 * ## No `assumeTrue` anywhere
 * Every precondition these tests need (an idle service, no leftover output file) is *established*
 * in [resetServiceState] rather than assumed — instrumented tests in this project share one
 * process and one real DataStore, so state genuinely leaks between them. A missing precondition
 * must fail loudly, never silently skip: see CLAUDE.md's testing standards.
 */
@RunWith(AndroidJUnit4::class)
class VideoExportServiceInstrumentedTest {

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun outputFileFor(baseName: String) =
        File(File(context.cacheDir, "video_export"), "$baseName.mp4")

    /**
     * Establishes the precondition this suite needs — a service with nothing in flight and no
     * terminal state left over — instead of assuming it. Runs before *and* after each test so a
     * failure here can't poison the next one.
     */
    @Before
    fun establishIdleServiceBefore() = resetServiceState()

    @After
    fun establishIdleServiceAfter() = resetServiceState()

    private fun resetServiceState(): Unit = runBlocking {
        if (VideoExportService.running.value) {
            VideoExportService.requestCancel()
            // Generous: a cancel is checked between pipeline stages, and a stage on a
            // software-rendered emulator can take a few seconds to reach its next check.
            withTimeout(120_000) { VideoExportService.running.first { !it } }
        }
        VideoExportService.resetForTesting()
        assertFalse("service must be idle before this test runs", VideoExportService.running.value)
        assertEquals(
            "state must be Idle before this test runs",
            VideoExporter.State.Idle,
            VideoExportService.state.value,
        )
        assertNull("no stale exported URI", VideoExportService.exportedUri.value)
    }

    /** Waits for the service's process-wide state to satisfy [predicate], failing loudly on timeout. */
    private suspend fun awaitState(
        timeoutMs: Long,
        what: String,
        predicate: (VideoExporter.State) -> Boolean,
    ): VideoExporter.State = try {
        withTimeout(timeoutMs) { VideoExportService.state.first(predicate) }
    } catch (e: Exception) {
        throw AssertionError(
            "timed out after $timeoutMs ms waiting for $what; last state was ${VideoExportService.state.value}",
            e,
        )
    }

    private fun isTerminal(s: VideoExporter.State) =
        s is VideoExporter.State.Completed ||
            s is VideoExporter.State.Failed ||
            s is VideoExporter.State.Cancelled

    /**
     * The headline test. Starts an export from a real Activity (so the foreground-service start is
     * allowed, exactly as it is for a user tapping "Export video"), then **destroys that Activity
     * and cancels the caller's CoroutineScope while the export is still in flight**, and requires
     * the export to run to completion regardless — file on disk, published URI, `Completed` state.
     *
     * Under the old `scope.launch { exporter.export(...) }` design this could not pass: the job
     * died with the composition.
     */
    @Test
    fun exportSurvivesActivityDestructionAndCompletesUnderTheServiceOwnedScope(): Unit = runBlocking {
        val script = TestScripts.shortScript()
        val baseName = "instrumented_service_export_${System.currentTimeMillis()}"
        val outFile = outputFileFor(baseName)
        outFile.delete()
        assertFalse("output must not exist before the export", outFile.exists())

        // Stand-in for VideoScreen's rememberCoroutineScope(): the scope that "owns" the UI.
        val callerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        val scenario = ActivityScenario.launch(MainActivity::class.java)
        val started = AtomicBoolean(false)
        scenario.onActivity { activity ->
            started.set(VideoExportService.start(activity, script, null, baseName))
        }
        assertTrue("VideoExportService.start() should have accepted the export", started.get())

        // Make sure the export is genuinely in flight before we pull the rug out.
        val inFlight = awaitState(120_000, "the export to get past the initial state") {
            it != VideoExporter.State.Idle && it != VideoExporter.State.SynthesizingNarration(0, script.segments.size)
        }
        assertFalse("export should not have finished before we destroyed the Activity", isTerminal(inFlight))
        android.util.Log.i("VideoExportServiceTest", "in-flight state before teardown: $inFlight")

        // A *fresh* observer (i.e. what a re-entered VideoScreen composition sees) must be handed
        // the in-flight state immediately, not a fresh Idle — requirement 4 of this feature.
        val seenByNewObserver = withTimeout(5_000) { VideoExportService.state.first() }
        assertFalse(
            "a newly-attached observer must see in-flight progress, not Idle",
            seenByNewObserver == VideoExporter.State.Idle,
        )
        assertTrue("service should report itself running", VideoExportService.running.value)

        // Pull the rug out: destroy the Activity and cancel the caller's scope, mid-export.
        scenario.close()
        callerScope.cancel()

        val terminal = awaitState(600_000, "the export to reach a terminal state") { isTerminal(it) }
        assertTrue(
            "export must complete despite the Activity being destroyed; got $terminal",
            terminal is VideoExporter.State.Completed,
        )
        val completed = terminal as VideoExporter.State.Completed

        assertTrue("exported file should exist at $outFile", outFile.isFile)
        assertTrue("exported file should be non-trivial, was ${outFile.length()} bytes", outFile.length() > 10_000)
        assertEquals("Completed.file should be the file we asked for", outFile.absolutePath, completed.file.absolutePath)
        assertTrue("Completed should report a real duration", completed.durationMs > 0)

        // The service — not the composable — is what publishes to MediaStore now.
        val published = VideoExportService.exportedUri.value
        assertNotNull("service should have published the video and exposed a URI", published)
        android.util.Log.i(
            "VideoExportServiceTest",
            "completed durationMs=${completed.durationMs} bytes=${outFile.length()} uri=$published",
        )

        assertFalse("running must be false once terminal", VideoExportService.running.value)

        // The UI acknowledging the dialog is what clears the terminal state.
        VideoExportService.acknowledgeTerminalState()
        assertEquals(VideoExporter.State.Idle, VideoExportService.state.value)
        assertNull(VideoExportService.exportedUri.value)

        // Housekeeping: don't leave a test video in the user's gallery collection on every run.
        if (completed.mediaStoreUri != null) {
            runCatching { context.contentResolver.delete(completed.mediaStoreUri!!, null, null) }
        }
        outFile.delete()
    }

    /**
     * D1: the export really runs as a foreground service of the type chosen for this SDK level
     * (`mediaProcessing` on API 35+, `dataSync` on 29-34). A refused `startForeground()` does not
     * fail the export, so only the platform's own answer (`getForegroundServiceType()`, recorded by
     * the service) shows it. On API 36 androidx's `ServiceCompat` masked `mediaProcessing` to 0 and
     * the platform refused the start; this would have failed then.
     */
    @Test
    fun theExportRunsAsAForegroundServiceOfTheTypeChosenForThisSdk(): Unit = runBlocking {
        val script = TestScripts.shortScript()
        val baseName = "instrumented_service_fgs_type_${System.currentTimeMillis()}"
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        val started = AtomicBoolean(false)
        scenario.onActivity { activity ->
            started.set(VideoExportService.start(activity, script, null, baseName))
        }
        assertTrue("VideoExportService.start() should have accepted the export", started.get())
        awaitState(120_000, "the export to get past the initial state") {
            it != VideoExporter.State.Idle && it != VideoExporter.State.SynthesizingNarration(0, script.segments.size)
        }
        val expected = ExportForegroundServiceType.forSdk(android.os.Build.VERSION.SDK_INT)
        assertTrue("this device has typed foreground services (API 29+)", expected != 0)
        assertEquals(
            "the platform's foreground-service type for the running export",
            expected,
            VideoExportService.lastForegroundServiceType,
        )
        VideoExportService.requestCancel()
        awaitState(120_000, "the export to stop after cancel") { isTerminal(it) }
        scenario.close()
        outputFileFor(baseName).delete()
    }

    /**
     * The two layers *above* [VideoExporter] in the "every exported video used the device voice"
     * defect. `VideoScreen.startExport()` passes the selected provider to [VideoExportService.start],
     * which parks it in `pendingRequest` and must hand it back to `VideoExporter.export()` when
     * `onStartCommand` finally lands — a handoff that deliberately does **not** go through the
     * Intent (a provider is not parcelable) and so is easy to drop silently.
     * [VideoExporterProviderRegressionTest] covers the exporter itself; nothing covered this
     * forwarding, which is the half a null argument in `VideoScreen` actually broke.
     *
     * Same standard of proof as the exporter's regression test: the assertion is on the *audio in
     * the file the service produced*, via [ToneVoiceProvider]'s 1 kHz tone, not on a flag.
     */
    @Test
    fun theServiceForwardsTheSuppliedProviderAllTheWayIntoTheExportedAudio(): Unit = runBlocking {
        val script = TestScripts.shortScript()
        val provider = ToneVoiceProvider()
        val baseName = "instrumented_service_provider_${System.currentTimeMillis()}"
        val outFile = outputFileFor(baseName)
        outFile.delete()

        val scenario = ActivityScenario.launch(MainActivity::class.java)
        val started = AtomicBoolean(false)
        scenario.onActivity { activity ->
            started.set(VideoExportService.start(activity, script, provider, baseName))
        }
        assertTrue("VideoExportService.start() should have accepted the export", started.get())

        val terminal = awaitState(600_000, "the provider-backed export to reach a terminal state") { isTerminal(it) }
        scenario.close()
        assertTrue("provider-backed export must complete; got $terminal", terminal is VideoExporter.State.Completed)
        val completed = terminal as VideoExporter.State.Completed

        // The service reached the provider at all...
        assertEquals(
            "the service must drive the supplied provider for every spoken sentence",
            script.segments.map { it.narration }.filter { it.isNotBlank() }.sorted(),
            provider.requested.sorted(),
        )
        // ...and what the provider produced is what got muxed.
        val stats = ExportedAudioProbe.toneWindows(ExportedAudioProbe.decodeAudioTrack(outFile), provider.toneHz)
        android.util.Log.i("VideoExportServiceTest", "service provider export: $stats")
        assertTrue(
            "expected the provider's ${provider.toneHz} Hz tone in the audio the SERVICE exported, got $stats",
            stats.toneDominatedWindows >= 5,
        )
        assertTrue("the tone should be essentially pure in its best window: $stats", stats.bestRatio > 0.8)

        VideoExportService.acknowledgeTerminalState()
        if (completed.mediaStoreUri != null) {
            runCatching { context.contentResolver.delete(completed.mediaStoreUri!!, null, null) }
        }
        outFile.delete()
    }

    /**
     * Cancel delivered as the notification's Cancel action does it — an `ACTION_CANCEL` Intent to
     * the service — must stop the export promptly and leave **no partial MP4** behind. Mirrors [VideoExporterInstrumentedTest]'s
     * `cancelStopsExportPromptlyWithoutLeavingAPartialOutputFile`, but through the service-owned
     * scope and additionally asserting the on-disk outcome and the service shutting itself down.
     */
    @Test
    fun cancelThroughTheServiceStopsExportPromptlyWithoutLeavingAPartialOutputFile(): Unit = runBlocking {
        val script = TestScripts.shortScript()
        val baseName = "instrumented_service_cancel_${System.currentTimeMillis()}"
        val outFile = outputFileFor(baseName)
        outFile.delete()

        val scenario = ActivityScenario.launch(MainActivity::class.java)
        val started = AtomicBoolean(false)
        scenario.onActivity { activity ->
            started.set(VideoExportService.start(activity, script, null, baseName))
        }
        assertTrue("VideoExportService.start() should have accepted the export", started.get())

        // Let it get properly into the narration/render pipeline first, so this is a cancel of
        // real work rather than a race against startup.
        awaitState(120_000, "the export to get past the initial state") {
            it != VideoExporter.State.Idle && it != VideoExporter.State.SynthesizingNarration(0, script.segments.size)
        }
        delay(300)

        // Deliberately NOT VideoExportService.requestCancel(): this fires the very Intent the
        // ongoing notification's Cancel action carries, so the ACTION_CANCEL branch of
        // onStartCommand is covered too rather than only the in-process shortcut the dialog uses.
        val cancelledAt = System.currentTimeMillis()
        scenario.onActivity { activity ->
            activity.startService(
                Intent(activity, VideoExportService::class.java)
                    .setAction(VideoExportService.ACTION_CANCEL),
            )
        }
        val terminal = awaitState(120_000, "the export to reach a terminal state after cancel") { isTerminal(it) }
        val stopMs = System.currentTimeMillis() - cancelledAt
        android.util.Log.i("VideoExportServiceTest", "cancel -> terminal in $stopMs ms, terminal=$terminal")

        assertEquals("cancel must yield State.Cancelled", VideoExporter.State.Cancelled, terminal)
        assertTrue(
            "cancel took $stopMs ms to take effect — a cooperative cancel is checked between " +
                "pipeline stages and must not take this long",
            stopMs < 60_000,
        )
        assertFalse(
            "a cancelled export must not leave a partial MP4 behind: $outFile (${outFile.length()} bytes)",
            outFile.exists(),
        )
        assertNull("a cancelled export must not publish a URI", VideoExportService.exportedUri.value)
        assertFalse("running must be false after a cancel", VideoExportService.running.value)

        scenario.close()
    }

    /**
     * A second `start()` while one export is in flight must be refused rather than trampling the
     * first — the service is a single-export owner, and two concurrent `MediaMuxer`/`MediaCodec`
     * pipelines on an emulator is exactly how you get a flaky, unreproducible failure.
     *
     * This also pins down a cancel racing the service's own startup. [VideoExporter.export] clears
     * its `cancelRequested` flag as its first statement, so a `cancel()` issued before the export
     * coroutine actually starts used to be wiped — the export then ran to completion and published
     * a video the caller had already cancelled. That is why the assertions below require
     * `State.Cancelled` and **no published file**, not merely "some terminal state".
     */
    @Test
    fun aSecondStartIsRefusedWhileAnExportIsAlreadyRunning(): Unit = runBlocking {
        val script = TestScripts.shortScript()
        val baseName = "instrumented_service_double_${System.currentTimeMillis()}"
        outputFileFor(baseName).delete()

        val scenario = ActivityScenario.launch(MainActivity::class.java)
        val first = AtomicBoolean(false)
        val second = AtomicBoolean(true)
        scenario.onActivity { activity ->
            first.set(VideoExportService.start(activity, script, null, baseName))
            second.set(VideoExportService.start(activity, script, null, "${baseName}_second"))
        }
        assertTrue("first start should be accepted", first.get())
        assertFalse("second start should be refused while one is running", second.get())

        // Cancel immediately — i.e. racing the service's own startup, before the export coroutine
        // has necessarily begun.
        VideoExportService.requestCancel()
        val terminal = awaitState(120_000, "the export to reach a terminal state after cancel") { isTerminal(it) }
        assertEquals(
            "a cancel issued before the export coroutine starts must still take effect",
            VideoExporter.State.Cancelled,
            terminal,
        )
        assertFalse(
            "the cancelled first export must not have produced a file",
            outputFileFor(baseName).exists(),
        )
        assertFalse(
            "the refused second export must never have produced a file",
            outputFileFor("${baseName}_second").exists(),
        )
        assertNull("a cancelled export must not publish a URI", VideoExportService.exportedUri.value)
        scenario.close()
    }
}
