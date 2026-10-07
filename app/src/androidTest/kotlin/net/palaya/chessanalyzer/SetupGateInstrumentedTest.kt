package net.palaya.chessanalyzer

import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import kotlinx.coroutines.runBlocking
import net.palaya.chessanalyzer.data.models.GeneratedModelPins
import net.palaya.chessanalyzer.data.models.ModelDownloader
import net.palaya.chessanalyzer.data.models.ModelSetup
import net.palaya.chessanalyzer.engine.NetStore
import net.palaya.chessanalyzer.video.VoiceStore
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The Setup gate at the nav host, on a device (D2c's rule, tested in D2d): a fresh process opens on Setup
 * while the engine net is missing, and on Home once it is in. Nothing else in the suite opens the nav host
 * expecting Home without seeding first (`VideoExportServiceInstrumentedTest` only needs a foreground
 * Activity), so this is where the gate itself is pinned. Neither case touches the network.
 *
 * "Missing" is a scratch directory with nothing installed, given to the app through
 * `ChessAnalyzerApplication.modelSetupForTesting` (the app's own installed models are never removed);
 * "in" is the real store, seeded by [TestApp.ensureSetUp].
 */
@RunWith(AndroidJUnit4::class)
class SetupGateInstrumentedTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    private val app get() = TestApp.app
    private var scenario: ActivityScenario<MainActivity>? = null
    private var dir: File? = null

    @After
    fun tearDown() {
        scenario?.close()
        app.modelSetupForTesting = null
        dir?.deleteRecursively()
    }

    private fun waitForText(text: String) {
        compose.waitUntil(30_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun shown(text: String): Boolean = compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun withNoNetTheAppOpensOnSetup() {
        val scratch = File(app.filesDir, "setup-gate-test-${System.nanoTime()}").apply { mkdirs() }
        dir = scratch
        app.modelSetupForTesting = ModelSetup(
            netStore = NetStore(scratch),
            voiceStore = VoiceStore(scratch, { Long.MAX_VALUE }, GeneratedModelPins.VOICE_SHA256, GeneratedModelPins.VOICE_SIZE_BYTES),
            // Never called: the gate only reads the disk. An unreachable https URL proves nothing is fetched.
            downloader = ModelDownloader(userAgent = "PalayaChess/test (Android)", allowCleartextLoopback = false),
            baseUrl = "https://example.invalid/",
            freeBytes = { Long.MAX_VALUE },
        )
        scenario = ActivityScenario.launch(MainActivity::class.java)

        val setupTitle = app.getString(R.string.setup_title)
        waitForText(setupTitle)
        assertTrue("the Setup screen's Not now must be offered", shown(app.getString(R.string.setup_not_now)))
        assertTrue("Home must not be shown before the net is in", !shown(app.getString(R.string.home_title)))
    }

    @Test
    fun withTheNetInstalledTheAppOpensOnHome() {
        runBlocking { TestApp.ensureSetUp() }
        scenario = ActivityScenario.launch(MainActivity::class.java)

        waitForText(app.getString(R.string.home_title))
        assertTrue("Setup must not be shown once the net is in", !shown(app.getString(R.string.setup_title)))
    }
}
