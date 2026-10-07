package net.palaya.chessanalyzer

import android.net.TrafficStats
import android.os.Process
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.Socket
import java.net.SocketAddress
import java.net.URI
import java.net.URL
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import net.palaya.chessanalyzer.core.narration.NarrationOptions
import net.palaya.chessanalyzer.core.narration.VideoScriptGenerator
import net.palaya.chessanalyzer.data.AnalysisService
import net.palaya.chessanalyzer.data.models.FaultHttpServer
import net.palaya.chessanalyzer.ui.model.AnalysisStrength
import net.palaya.chessanalyzer.ui.model.EngineSettings
import net.palaya.chessanalyzer.ui.model.NeuralVoiceTier
import net.palaya.chessanalyzer.video.NeuralTtsProvider
import net.palaya.chessanalyzer.video.VideoExportService
import net.palaya.chessanalyzer.video.VideoExporter
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * **After setup, the app makes no network connection** (docs/MODEL_DOWNLOAD_DESIGN.md §6.2, D2d). Since
 * D2b the app holds INTERNET for the one-time model download; this proves that a whole session after
 * setup (the app opened at Home, a full analysis of a real game at the Standard budget, Kokoro narration
 * synthesis and a narrated MP4 saved through the real export service) opens no connection at all.
 *
 * Two independent instruments, both read in this (the app's) process:
 *  1. **A recording [ProxySelector]** installed as the process default. Android's HttpURLConnection (and
 *     OkHttp) ask it for a route before every connection, so a connection the app opened through them
 *     shows up as a `select()` call. The instrument is proven live first: a request to an in-process
 *     [FaultHttpServer] MUST be recorded, otherwise the zero below would mean nothing.
 *  2. **[TrafficStats]** for the app's uid (every socket the process opens, whatever the API), read
 *     before and after. A device that answers [TrafficStats.UNSUPPORTED] gives "not measured", never a
 *     pass; then the ProxySelector alone decides, and the log says so.
 *
 * The check-for-updates path (design: exactly two `select()` calls, manifest and signature, and only after
 * the tap) is proven by the sibling [UpdateCheckNetworkTest] (D2e) through the real Settings UI.
 */
@RunWith(AndroidJUnit4::class)
class NoNetworkAfterSetupTest {

    private class RecordingProxySelector(private val delegate: ProxySelector?) : ProxySelector() {
        val selected: MutableList<String> = Collections.synchronizedList(ArrayList())

        override fun select(uri: URI): List<Proxy> {
            selected += uri.toString()
            android.util.Log.w(TAG, "ProxySelector.select($uri)", Throwable("connection attempt"))
            return delegate?.select(uri) ?: listOf(Proxy.NO_PROXY)
        }

        override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) {
            delegate?.connectFailed(uri, sa, ioe)
        }
    }

    private companion object {
        const val TAG = "NoNetworkAfterSetup"
    }

    private val original: ProxySelector? = ProxySelector.getDefault()
    private val recorder = RecordingProxySelector(original)
    private var scenario: ActivityScenario<MainActivity>? = null

    /** The Opera Game, a real game that ends in mate; a unique tag so no eval cache can answer for the engine. */
    private val pgn = """
        [Event "No network after setup ${System.nanoTime()}"]
        [White "MorphyFan1857"]
        [Black "DukeAndCount"]
        [Result "1-0"]

        1. e4 e5 2. Nf3 d6 3. d4 Bg4 4. dxe5 Bxf3 5. Qxf3 dxe5 6. Bc4 Nf6 7. Qb3 Qe7 8. Nc3 c6 9. Bg5 b5
        10. Nxb5 cxb5 11. Bxb5+ Nbd7 12. O-O-O Rd8 13. Rxd7 Rxd7 14. Rd1 Qe6 15. Bxd7+ Nxd7
        16. Qb8+ Nxb8 17. Rd8# 1-0
    """.trimIndent()

    @Before
    fun establishIdleExport(): Unit = runBlocking {
        if (VideoExportService.running.value) {
            VideoExportService.requestCancel()
            withTimeout(120_000) { VideoExportService.running.first { !it } }
        }
        VideoExportService.resetForTesting()
    }

    @After
    fun restore() {
        ProxySelector.setDefault(original)
        scenario?.close()
        VideoExportService.resetForTesting()
    }

    private fun uidTraffic(): Pair<Long, Long> {
        val uid = Process.myUid()
        return TrafficStats.getUidTxBytes(uid) to TrafficStats.getUidRxBytes(uid)
    }

    @Test
    fun afterSetupAFullAnalysisAndANarratedExportOpenNoConnection(): Unit = runBlocking {
        TestApp.ensureSetUp()
        ProxySelector.setDefault(recorder)

        // ---- the instrument works: a real connection through the platform stack IS recorded ----
        FaultHttpServer().serve("probe", ByteArray(1_000) { 1 }).use { probe ->
            val c = URL(probe.url("probe")).openConnection() as HttpURLConnection
            try {
                assertEquals(1_000, c.inputStream.use { it.readBytes() }.size)
            } finally {
                c.disconnect()
            }
            assertTrue(
                "the recording ProxySelector must see an HttpURLConnection, or a zero proves nothing: ${recorder.selected}",
                // Android's OkHttp asks per route, so the URI it passes has no path: http://127.0.0.1:<port>/
                recorder.selected.any { it.contains("127.0.0.1:${probe.port}") },
            )
            // A raw java.net.Socket, for the record: whether this libcore routes it through the selector.
            val before = recorder.selected.size
            Socket().use { it.connect(InetSocketAddress("127.0.0.1", probe.port), 2_000) }
            android.util.Log.i(TAG, "raw Socket recorded by the ProxySelector: ${recorder.selected.size > before} (${recorder.selected.drop(before)})")
        }
        recorder.selected.clear()
        // Let the probe's sockets close before the baseline is read.
        delay(1_000)
        val (tx0, rx0) = uidTraffic()
        val started = System.currentTimeMillis()

        // ---- 1. the app opens at Home (the net is in) and sits there ----
        val s = ActivityScenario.launch(MainActivity::class.java).also { scenario = it }
        delay(5_000)

        // ---- 2. a full analysis of a real game at the Standard budget ----
        var sawEngineWork = false
        val outcome = TestApp.analysisService().analyze(
            pgnText = pgn,
            username = "MorphyFan1857",
            settings = EngineSettings(depth = AnalysisStrength.STANDARD.depth, multiPv = 3, username = "MorphyFan1857"),
            onProgress = { p -> if (p.currentMoveIndex > 0) sawEngineWork = true },
        )
        assertTrue("analysis did not succeed: $outcome", outcome is AnalysisService.Outcome.Success)
        assertTrue("the engine must have run (no cache may answer)", sawEngineWork)
        val success = outcome as AnalysisService.Outcome.Success
        assertEquals(33, success.report.annotations.size)

        // ---- 3. a narrated export of that game's own script, Kokoro voice, through the real service ----
        val full = VideoScriptGenerator(success.userColor)
            .generate(success.report, success.game, NarrationOptions(speechWpm = NeuralVoiceTier.KOKORO.measuredWpm))
        // The first segments only: the pipeline (synthesis, render, mux, MediaStore) is the same for a
        // whole review, which takes ~4x real time on an emulator. The recap card stays.
        val keep = 4
        val segments = full.segments.take(keep)
        val script = full.copy(
            segments = segments,
            chapters = full.chapters.filter { it.startSegmentIndex < keep },
            totalEstimatedMs = segments.sumOf { it.estimatedSpeechMs + it.leadInMs + it.holdAfterMs },
        )
        val provider = NeuralTtsProvider(NeuralVoiceTier.KOKORO, TestApp.installedVoiceDir())
        val baseName = "no_network_after_setup_${System.currentTimeMillis()}"
        val accepted = AtomicBoolean(false)
        s.onActivity { activity -> accepted.set(VideoExportService.start(activity, script, provider, baseName)) }
        assertTrue("VideoExportService.start() must accept the export", accepted.get())
        val terminal = withTimeout(1_200_000) {
            VideoExportService.state.first {
                it is VideoExporter.State.Completed || it is VideoExporter.State.Failed || it is VideoExporter.State.Cancelled
            }
        }
        assertTrue("the narrated export must complete: $terminal", terminal is VideoExporter.State.Completed)
        val completed = terminal as VideoExporter.State.Completed
        assertTrue("the export must be narrated by the Kokoro voice", completed.narrationWasSpoken)
        s.close()
        scenario = null
        delay(2_000)

        // ---- the verdict ----
        val (tx1, rx1) = uidTraffic()
        val elapsedS = (System.currentTimeMillis() - started) / 1000
        val measured = tx0 != TrafficStats.UNSUPPORTED.toLong() && tx1 != TrafficStats.UNSUPPORTED.toLong()
        android.util.Log.i(
            TAG,
            "window ${elapsedS}s: ProxySelector.select() calls ${recorder.selected.size}; " +
                (if (measured) "TrafficStats uid tx ${tx1 - tx0} B, rx ${rx1 - rx0} B" else "TrafficStats UNSUPPORTED (not measured)") +
                "; export ${completed.fileSizeBytes} B, ${completed.durationMs} ms",
        )
        assertEquals("no connection may be opened after setup: ${recorder.selected}", emptyList<String>(), recorder.selected.toList())
        if (measured) {
            assertEquals("the app's uid sent bytes after setup", 0L, tx1 - tx0)
            assertEquals("the app's uid received bytes after setup", 0L, rx1 - rx0)
        }

        // Housekeeping: no test video left in the gallery.
        runCatching { VideoExportService.exportedUri.value?.let { TestApp.context.contentResolver.delete(it, null, null) } }
        completed.file.delete()
        VideoExportService.acknowledgeTerminalState()
    }
}
