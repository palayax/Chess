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
import java.io.IOException
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import java.net.URL
import java.util.Collections
import kotlinx.coroutines.runBlocking
import net.palaya.chessanalyzer.data.models.FaultHttpServer
import net.palaya.chessanalyzer.data.models.TestManifests
import net.palaya.chessanalyzer.data.models.UpdateChecker
import net.palaya.chessanalyzer.data.models.UpdateUiState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The other half of "no network after setup" (D2e; design §6.2): **"Check for updates" makes requests
 * only when it is tapped, and exactly the expected ones.** The sibling of [NoNetworkAfterSetupTest], with
 * the same instrument (a recording [ProxySelector], proven live first).
 *
 * The real app is driven through its real UI: Home, the Settings button, the Settings screen (which shows
 * the "Check for updates" row) and a tap on that row. The row's checker is pointed at an in-process
 * [FaultHttpServer] serving a manifest signed with a TEST key (`ChessAnalyzerApplication.updateCheckerForTesting`;
 * the maintainers' private key is never used by a test). Before the tap: zero connections, zero requests.
 * After the tap: exactly `models.json` and `models.json.sig`, two routes through the selector, and the
 * sheet says "Update available". Nothing else follows (no model file is fetched by a check).
 */
@RunWith(AndroidJUnit4::class)
class UpdateCheckNetworkTest {

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
    private var server: FaultHttpServer? = null

    @After
    fun tearDown() {
        ProxySelector.setDefault(original)
        scenario?.close()
        app.updateCheckerForTesting = null
        server?.close()
    }

    private fun waitForText(text: String, timeoutMs: Long = 30_000) {
        compose.waitUntil(timeoutMs) { compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun checkForUpdatesRequestsOnlyWhenTappedAndExactlyTheManifestAndItsSignature() {
        runBlocking { TestApp.ensureSetUp() }
        val keys = TestManifests.newKeyPair()
        val s = FaultHttpServer().also { server = it }
        val tar = "kokoro-int8-en-v0_19-r2.tar"
        val manifest = TestManifests.manifest(
            TestManifests.voiceEntry(s.baseUrl, "models-2026.11", tar, 158_279_680, "a".repeat(64)),
            // A wrong-architecture net: never offered, never requested.
            TestManifests.netEntry(s.baseUrl, "models-2026.11", "nn-abcdef012345.nnue", 98_511_183, "abcdef012345" + "0".repeat(52), archHash = "deadbeef"),
        )
        s.serve("models/models.json", manifest).serve("models/models.json.sig", TestManifests.sign(manifest, keys.private))
        app.updateCheckerForTesting = UpdateChecker(
            downloader = app.modelDownloader,
            manifestUrl = s.url("models/models.json"),
            publicKeyDer = keys.public.encoded,
            networkStatus = app.networkStatus,
            facts = { app.appFacts().copy(baseUrl = s.baseUrl) },
            diagnostics = app.diagnostics.log,
        )

        ProxySelector.setDefault(recorder)
        // The instrument works: a real connection through the platform stack IS recorded.
        FaultHttpServer().serve("probe", ByteArray(100) { 1 }).use { probe ->
            val c = URL(probe.url("probe")).openConnection() as HttpURLConnection
            try {
                assertEquals(100, c.inputStream.use { it.readBytes() }.size)
            } finally {
                c.disconnect()
            }
            assertTrue("the recording ProxySelector must see a connection: ${recorder.selected}", recorder.selected.any { it.contains("127.0.0.1:${probe.port}") })
        }
        recorder.selected.clear()

        // ---- Home, then Settings: the row is on screen, nothing is requested ----
        scenario = ActivityScenario.launch(MainActivity::class.java)
        waitForText(app.getString(R.string.home_title))
        compose.onNodeWithContentDescription(app.getString(R.string.nav_settings)).performClick()
        val rowTitle = app.getString(R.string.update_row_title)
        waitForText(app.getString(R.string.settings_about_open))
        compose.onNode(hasScrollAction()).performScrollToNode(hasText(rowTitle))
        compose.waitUntil(30_000) { compose.onAllNodesWithText(rowTitle).fetchSemanticsNodes().isNotEmpty() }
        Thread.sleep(5_000)
        assertEquals("no connection before the tap: ${recorder.selected}", emptyList<String>(), recorder.selected.toList())
        assertEquals("no request before the tap", emptyList<String>(), s.requests.map { it.path })

        // ---- the tap ----
        compose.onNodeWithText(rowTitle).performClick()
        // The status line is one live-region node whose words are its content description.
        val available = app.getString(R.string.update_available)
        compose.waitUntil(30_000) { compose.onAllNodesWithContentDescription(available).fetchSemanticsNodes().isNotEmpty() }
        assertTrue(app.modelUpdates.state.value is UpdateUiState.Checked)
        // The offered voice is listed with its size; the wrong-architecture net is not.
        assertTrue(compose.onAllNodesWithText(app.getString(R.string.update_kind_voice), substring = true).fetchSemanticsNodes().isNotEmpty())
        assertTrue(compose.onAllNodesWithText(app.getString(R.string.update_kind_net), substring = true).fetchSemanticsNodes().isEmpty())
        Thread.sleep(3_000)

        assertEquals(listOf("/models/models.json", "/models/models.json.sig"), s.requests.map { it.path })
        val routes = recorder.selected.toList()
        assertEquals("exactly two connections, both to the update server: $routes", 2, routes.size)
        assertTrue(routes.toString(), routes.all { it.contains("127.0.0.1:${s.port}") })
        assertTrue("a check downloads no model file", s.requests.none { it.path.endsWith(".tar") || it.path.endsWith(".tar.gz") || it.path.endsWith(".nnue") })
    }
}
