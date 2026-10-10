package net.palaya.chessanalyzer

import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.IOException
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import java.util.Collections
import kotlinx.coroutines.runBlocking
import net.palaya.chessanalyzer.data.models.FaultHttpServer
import net.palaya.chessanalyzer.data.models.FaultHttpServer.Fault
import net.palaya.chessanalyzer.data.models.TestManifests
import net.palaya.chessanalyzer.data.models.UpdateChecker
import net.palaya.chessanalyzer.data.models.UpdateUiState
import net.palaya.chessanalyzer.data.models.UpstreamChecker
import net.palaya.chessanalyzer.data.models.UpstreamSources
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * "Check for updates" also asks the original projects (A4), through the real Settings UI and the real
 * Application wiring, with the signed manifest on one in-process [FaultHttpServer] and a stand-in for
 * api.github.com on another (the upstream base URL is injectable: `ChessAnalyzerApplication.upstreamCheckerForTesting`).
 *
 * Pinned on a device: nothing is requested until the tap; the tap makes exactly the two signed requests and the
 * three upstream ones, each to its own server; the sheet shows one row per component with the right words
 * ("Up to date" / "Newer upstream version X available — comes with an app update"); and a rate-limited upstream
 * leaves the signed result ("Update available" and its offer) exactly as it was.
 */
@RunWith(AndroidJUnit4::class)
class UpdateCheckUpstreamTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    private class RecordingProxySelector(private val delegate: ProxySelector?) : ProxySelector() {
        val selected: MutableList<String> = Collections.synchronizedList(ArrayList())
        override fun select(uri: URI): List<Proxy> {
            selected += uri.toString()
            return delegate?.select(uri) ?: listOf(Proxy.NO_PROXY)
        }
        override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) {
            delegate?.connectFailed(uri, sa, ioe)
        }
    }

    private val app get() = TestApp.app
    private val original: ProxySelector? = ProxySelector.getDefault()
    private val recorder = RecordingProxySelector(original)
    private var scenario: ActivityScenario<MainActivity>? = null
    private var models: FaultHttpServer? = null
    private var github: FaultHttpServer? = null

    private val stockfishPath = "repos/official-stockfish/Stockfish/releases/latest"
    private val sherpaPath = "repos/k2-fsa/sherpa-onnx/releases/latest"
    private val ttsPath = "repos/k2-fsa/sherpa-onnx/releases/tags/tts-models"

    @After
    fun tearDown() {
        ProxySelector.setDefault(original)
        scenario?.close()
        app.updateCheckerForTesting = null
        app.upstreamCheckerForTesting = null
        models?.close()
        github?.close()
    }

    private fun waitForText(text: String, timeoutMs: Long = 30_000) {
        compose.waitUntil(timeoutMs) { compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty() }
    }

    /** Status lines and upstream rows are one node each whose words are the content description. */
    private fun waitForDescription(text: String, timeoutMs: Long = 30_000) {
        compose.waitUntil(timeoutMs) {
            compose.onAllNodesWithContentDescription(text, substring = true).fetchSemanticsNodes().isNotEmpty()
        }
    }

    /** The signed manifest on [models] (a voice offer) and the app's own versions as the "ours" side. */
    private fun wire(configureGithub: FaultHttpServer.() -> Unit) {
        val keys = TestManifests.newKeyPair()
        val m = FaultHttpServer().also { models = it }
        val g = FaultHttpServer().also { github = it }
        val manifest = TestManifests.manifest(
            TestManifests.voiceEntry(m.baseUrl, "models-2026.11", "kokoro-int8-en-v0_19-r2.tar", 158_279_680, "a".repeat(64)),
        )
        m.serve("models/models.json", manifest).serve("models/models.json.sig", TestManifests.sign(manifest, keys.private))
        g.configureGithub()
        app.updateCheckerForTesting = UpdateChecker(
            downloader = app.modelDownloader,
            manifestUrl = m.url("models/models.json"),
            publicKeyDer = keys.public.encoded,
            networkStatus = app.networkStatus,
            facts = { app.appFacts().copy(baseUrl = m.baseUrl) },
            diagnostics = app.diagnostics.log,
        )
        app.upstreamCheckerForTesting = UpstreamChecker(
            downloader = app.upstreamDownloader,
            networkStatus = app.networkStatus,
            sources = UpstreamSources.defaults(app.ourComponents(), g.baseUrl),
            diagnostics = app.diagnostics.log,
        )
    }

    private fun releaseJson(tag: String) = """{"tag_name":"$tag","assets":[]}""".toByteArray()

    private fun ttsJson(latestKokoro: String): ByteArray {
        val ours = app.ourComponents()
        return """{"tag_name":"tts-models","assets":[
            {"name":"${ours.voiceArchiveName}","size":${ours.voiceArchiveSizeBytes},"digest":"sha256:${ours.voiceArchiveSha256}"},
            {"name":"$latestKokoro","size":99}]}""".toByteArray()
    }

    private fun openUpdateSheet() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        waitForText(app.getString(R.string.home_title))
        compose.onNodeWithContentDescription(app.getString(R.string.nav_settings)).performClick()
        val rowTitle = app.getString(R.string.update_row_title)
        waitForText(app.getString(R.string.settings_about_open))
        compose.onNode(hasScrollAction()).performScrollToNode(hasText(rowTitle))
        compose.waitUntil(30_000) { compose.onAllNodesWithText(rowTitle).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(rowTitle).performClick()
    }

    /** Optional pause so a person (or `adb emu screenrecord screenshot`) can capture the sheet. */
    private fun holdForScreenshot() {
        val ms = InstrumentationRegistry.getArguments().getString("a4HoldMs")?.toLongOrNull() ?: return
        Thread.sleep(ms)
    }

    @Test
    fun theTapAsksTheSignedServerAndThenGithubAndTheSheetShowsOneRowPerComponent() {
        runBlocking { TestApp.ensureSetUp() }
        val ours = app.ourComponents()
        wire {
            // Stockfish has a newer release, sherpa-onnx is the same, Kokoro has a newer generation.
            serveGzipped(stockfishPath, releaseJson("sf_99"))
            serveGzipped(sherpaPath, releaseJson("v${ours.sherpaOnnxVersion}"))
            serveGzipped(ttsPath, ttsJson("kokoro-int8-multi-lang-v9_9.tar.bz2"))
        }
        ProxySelector.setDefault(recorder)
        recorder.selected.clear()

        scenario = ActivityScenario.launch(MainActivity::class.java)
        waitForText(app.getString(R.string.home_title))
        compose.onNodeWithContentDescription(app.getString(R.string.nav_settings)).performClick()
        val rowTitle = app.getString(R.string.update_row_title)
        waitForText(app.getString(R.string.settings_about_open))
        compose.onNode(hasScrollAction()).performScrollToNode(hasText(rowTitle))
        compose.waitUntil(30_000) { compose.onAllNodesWithText(rowTitle).fetchSemanticsNodes().isNotEmpty() }
        Thread.sleep(3_000)
        assertEquals("no connection before the tap: ${recorder.selected}", emptyList<String>(), recorder.selected.toList())
        assertTrue("no signed request before the tap", models!!.requests.isEmpty())
        assertTrue("no upstream request before the tap", github!!.requests.isEmpty())

        compose.onNodeWithText(rowTitle).performClick()

        waitForDescription(app.getString(R.string.update_available))
        // Our signed result is the offer, whatever upstream says.
        assertTrue(app.modelUpdates.state.value is UpdateUiState.Checked)
        assertTrue(compose.onAllNodesWithText(app.getString(R.string.update_kind_voice), substring = true).fetchSemanticsNodes().isNotEmpty())

        // One row per component, each with its own words.
        waitForDescription(app.getString(R.string.upstream_newer, "99"))
        waitForDescription(app.getString(R.string.upstream_up_to_date))
        waitForDescription(app.getString(R.string.upstream_newer, "v9.9"))
        waitForDescription(app.getString(R.string.upstream_stockfish))
        waitForDescription(app.getString(R.string.upstream_sherpa_onnx))
        waitForDescription(app.getString(R.string.upstream_kokoro))
        waitForDescription(app.getString(R.string.upstream_ours, "19"))
        // The note says nothing is downloaded from these projects.
        waitForText(app.getString(R.string.upstream_note))

        // Requests: exactly the two signed ones to the models server, exactly the three upstream ones to the other.
        assertEquals(listOf("/models/models.json", "/models/models.json.sig"), models!!.requests.map { it.path })
        assertEquals(setOf("/$stockfishPath", "/$sherpaPath", "/$ttsPath"), github!!.requests.map { it.path }.toSet())
        assertEquals(3, github!!.requests.size)
        val routes = recorder.selected.toList()
        assertEquals("five connections in all: $routes", 5, routes.size)
        assertEquals(2, routes.count { it.contains("127.0.0.1:${models!!.port}") })
        assertEquals(3, routes.count { it.contains("127.0.0.1:${github!!.port}") })
        assertTrue("a check downloads nothing from upstream", github!!.requests.none { it.path.endsWith(".tar.bz2") || it.path.endsWith(".nnue") })
        for (r in github!!.requests) {
            assertEquals("PalayaChess/${BuildConfig.VERSION_NAME} (Android ${android.os.Build.VERSION.SDK_INT})", r.headers["user-agent"])
            assertEquals(null, r.headers["authorization"])
            assertEquals(null, r.headers["cookie"])
        }
        holdForScreenshot()
    }

    @Test
    fun aRateLimitedUpstreamLeavesTheSignedResultAndItsOfferAsTheyWere() {
        runBlocking { TestApp.ensureSetUp() }
        wire {
            alwaysFault(stockfishPath, Fault.Status(403))
            alwaysFault(sherpaPath, Fault.Status(403))
            alwaysFault(ttsPath, Fault.Status(429, retryAfterSeconds = 3600))
            serveGzipped(stockfishPath, releaseJson("sf_99"))
            serveGzipped(sherpaPath, releaseJson("v9.9.9"))
            serveGzipped(ttsPath, ttsJson("kokoro-int8-multi-lang-v9_9.tar.bz2"))
        }
        openUpdateSheet()

        waitForDescription(app.getString(R.string.update_available))
        assertTrue(compose.onAllNodesWithText(app.getString(R.string.update_kind_voice), substring = true).fetchSemanticsNodes().isNotEmpty())
        val limited = app.getString(R.string.upstream_rate_limited)
        compose.waitUntil(30_000) {
            compose.onAllNodesWithContentDescription(limited, substring = true).fetchSemanticsNodes().size == 3
        }
        // The signed state is still the offer, and the Download button is still there.
        assertTrue(app.modelUpdates.state.value.let { it is UpdateUiState.Checked && it.offers.size == 1 })
        assertTrue(compose.onAllNodesWithText(app.getString(R.string.update_download_install)).fetchSemanticsNodes().isNotEmpty())
        holdForScreenshot()
    }

    @Test
    fun aGithubThatNeverAnswersDoesNotKeepTheSignedResultWaiting() {
        runBlocking { TestApp.ensureSetUp() }
        wire {
            for (path in listOf(stockfishPath, sherpaPath, ttsPath)) {
                serveGzipped(path, releaseJson("v1.0.0"))
                alwaysFault(path, Fault.Stall(afterBytes = 0, millis = 30_000))
            }
        }
        openUpdateSheet()
        // The signed answer appears at once; the upstream rows are still "Checking…" at that moment.
        waitForDescription(app.getString(R.string.update_available), timeoutMs = 10_000)
        waitForDescription(app.getString(R.string.upstream_checking), timeoutMs = 10_000)
        // Then each row ends in its own "couldn't check" (the upstream downloader's read timeout is 12 s).
        waitForDescription(app.getString(R.string.upstream_unavailable), timeoutMs = 60_000)
        assertTrue(compose.onAllNodesWithText(app.getString(R.string.update_download_install)).fetchSemanticsNodes().isNotEmpty())
    }
}
