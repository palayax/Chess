package net.palaya.chessanalyzer

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import kotlinx.coroutines.runBlocking
import net.palaya.chessanalyzer.data.FamousGamesStore
import net.palaya.chessanalyzer.data.PendingAnalysisStore
import net.palaya.chessanalyzer.data.models.GeneratedModelPins
import net.palaya.chessanalyzer.data.models.ModelDownloader
import net.palaya.chessanalyzer.data.models.ModelSetup
import net.palaya.chessanalyzer.engine.NetStore
import net.palaya.chessanalyzer.ui.model.AnalysisStrength
import net.palaya.chessanalyzer.ui.model.EngineSettings
import net.palaya.chessanalyzer.ui.model.SideChoice
import net.palaya.chessanalyzer.ui.screens.FAMOUS_SEARCH_TAG
import net.palaya.chessanalyzer.video.VoiceStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * G1 on a device: Home's "Famous games" opens the library, the search narrows it, a game's sheet offers
 * "Review this game", and that runs the same flow as a shared game: Analysing when the engine net is in,
 * Setup (with the game kept in `setup_waiting_game.json`) when it is not. The library is read from the APK's
 * own assets; nothing touches the network.
 *
 * G1-device: a famous game opens as "Not me" (nobody in it is the user): the request carries that initial side
 * (in `pending_analysis.json`, and in `setup_waiting_game.json` when it waits for Setup), and the finished review's
 * Summary has "Not me" selected, the real names and no "you" wording.
 */
@RunWith(AndroidJUnit4::class)
class FamousGamesInstrumentedTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    private val app get() = TestApp.app
    private var scenario: ActivityScenario<MainActivity>? = null
    private var dir: File? = null
    private var settingsBefore: EngineSettings? = null
    private var storedGameFile: File? = null

    /** The Immortal Game: in every build of the library (FamousGamesAssetTest pins the assets). */
    private val searchFor = "Kieseritzky"

    @After
    fun tearDown() {
        scenario?.close()
        settingsBefore?.let { runBlocking { app.settingsRepository.save(it) } }
        // The reviewed game must not stay in Home's recent games for later tests.
        storedGameFile?.delete()
        app.modelSetupForTesting = null
        dir?.deleteRecursively()
        // The no-net case leaves the game waiting for setup; later tests must not inherit it.
        File(app.filesDir, PendingAnalysisStore.WAITING_FOR_SETUP_FILE_NAME).delete()
    }

    private fun waitForText(text: String, timeoutMs: Long = 30_000) {
        compose.waitUntil(timeoutMs) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    }

    private val heading = SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)

    private fun shown(text: String): Boolean = compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()

    /** Home -> Famous games -> search -> the game's sheet -> Review this game. */
    private fun reviewTheImmortalGameFromHome() = reviewFromHome(searchFor)

    private fun reviewFromHome(searchFor: String) {
        val entry = app.getString(R.string.famous_home_entry_title)
        waitForText(entry)
        compose.onNodeWithText(entry).performScrollTo().performClick()

        waitForText(app.getString(R.string.famous_search_label))
        // The library opens grouped by era, the first group a heading.
        waitForText(app.getString(R.string.famous_era_romantic))
        // TalkBack (G1-device): the screen title and the era titles are headings.
        compose.onNode(hasText(app.getString(R.string.famous_title)) and heading).assertExists()
        compose.onNode(hasText(app.getString(R.string.famous_era_romantic)) and heading).assertExists()

        compose.onNode(hasTestTag(FAMOUS_SEARCH_TAG) and hasSetTextAction()).performTextInput(searchFor)
        val title = FamousGamesStore.load(app).games.first { it.black.contains(searchFor) }.title
        waitForText(title)
        // The search narrowed the list: a game without that name is gone.
        val other = FamousGamesStore.load(app).games.first { !it.white.contains(searchFor) && !it.black.contains(searchFor) }.title
        compose.waitUntil(10_000) { !shown(other) }
        // TalkBack: a row is one stop that reads the title, the players and "year · result" together.
        val game = FamousGamesStore.load(app).games.first { it.black.contains(searchFor) }
        compose.onNode(
            hasClickAction() and hasText(title) and hasText(game.white, substring = true) and
                hasText(game.black, substring = true) and hasText("${game.year} · ${game.result}", substring = true),
        ).assertExists()

        compose.onNode(hasText(title)).performClick()
        val review = app.getString(R.string.famous_review_action)
        waitForText(review)
        // The sheet's title is a heading.
        compose.onNode(hasText(title) and heading).assertExists()
        compose.onNodeWithText(review).performScrollTo().performClick()
    }

    @Test
    fun withTheNetInstalledReviewThisGameStartsTheAnalysis() {
        runBlocking { TestApp.ensureSetUp() }
        scenario = ActivityScenario.launch(MainActivity::class.java)
        waitForText(app.getString(R.string.home_title))

        reviewTheImmortalGameFromHome()

        waitForText(app.getString(R.string.progress_title))
        assertTrue("Setup must not be shown once the net is in", !shown(app.getString(R.string.setup_title)))
        // G1-device: the request in flight carries the famous game's initial side, "Not me".
        val pending = PendingAnalysisStore(app.filesDir)
        compose.waitUntil(10_000) { pending.load() != null }
        assertEquals("a famous game opens as Not me", SideChoice.NOT_ME.storedName, pending.load()?.initialSide)
        // Leave the analysis: Analysing's Back cancels it and returns to the library.
        compose.onNodeWithText(app.getString(R.string.progress_cancel)).performClick()
        waitForText(app.getString(R.string.famous_search_label))
    }

    @Test
    fun withNoNetReviewThisGameKeepsTheGameForSetup() {
        val scratch = File(app.filesDir, "famous-games-test-${System.nanoTime()}").apply { mkdirs() }
        dir = scratch
        File(app.filesDir, PendingAnalysisStore.WAITING_FOR_SETUP_FILE_NAME).delete()
        app.modelSetupForTesting = ModelSetup(
            netStore = NetStore(scratch),
            voiceStore = VoiceStore(scratch, { Long.MAX_VALUE }, GeneratedModelPins.VOICE_SHA256, GeneratedModelPins.VOICE_SIZE_BYTES),
            // Never called: nothing is downloaded without a tap on Download. An unreachable URL proves it.
            downloader = ModelDownloader(userAgent = "PalayaChess/test (Android)", allowCleartextLoopback = false),
            baseUrl = "https://example.invalid/",
            freeBytes = { Long.MAX_VALUE },
        )
        scenario = ActivityScenario.launch(MainActivity::class.java)

        // A fresh process opens on Setup while the net is missing; "Not now" leads to Home.
        waitForText(app.getString(R.string.setup_title))
        compose.onNodeWithText(app.getString(R.string.setup_not_now)).performScrollTo().performClick()
        waitForText(app.getString(R.string.home_title))

        reviewTheImmortalGameFromHome()

        // Same as a shared game: Setup, saying the game is kept, and the game's text on disk.
        waitForText(app.getString(R.string.setup_title))
        waitForText(app.getString(R.string.setup_game_waiting))
        val waiting = File(app.filesDir, PendingAnalysisStore.WAITING_FOR_SETUP_FILE_NAME)
        compose.waitUntil(10_000) { waiting.exists() }
        val immortal = FamousGamesStore.load(app).let { lib -> lib.pgnFor(lib.games.first { it.black.contains(searchFor) }.id)!! }
        val saved = PendingAnalysisStore(app.filesDir, PendingAnalysisStore.WAITING_FOR_SETUP_FILE_NAME).load()
        assertEquals("the kept game is the famous game's text", immortal, saved?.pgnText)
        assertEquals("the kept game still opens as Not me", SideChoice.NOT_ME.storedName, saved?.initialSide)
    }

    /**
     * A full review of a short famous game (Réti - Tartakower, 1910: 21 plies) at Quick, through to the Summary.
     * The Settings name is set to White's name on purpose: a shared game would be detected as "you played White";
     * a famous game still opens as "Not me".
     */
    @Test
    fun aReviewedFamousGameOpensAsNotMeWithTheRealNames() {
        runBlocking { TestApp.ensureSetUp() }
        val game = FamousGamesStore.load(app).games.first { it.black.contains("Tartakower") }
        runBlocking {
            val before = app.settingsRepository.current()
            settingsBefore = before
            app.settingsRepository.save(before.copy(depth = AnalysisStrength.QUICK.depth, username = game.white))
        }
        scenario = ActivityScenario.launch(MainActivity::class.java)
        waitForText(app.getString(R.string.home_title))

        reviewFromHome("Tartakower")

        waitForText(app.getString(R.string.summary_which_side), timeoutMs = 300_000)
        compose.onNodeWithText(app.getString(R.string.summary_side_neither)).assertIsSelected()
        assertFalse("no 'what is this for' line once a side is chosen", shown(app.getString(R.string.summary_side_help)))
        // The real names, and no "you" anywhere.
        assertTrue(compose.onAllNodes(hasText(game.white, substring = true)).fetchSemanticsNodes().isNotEmpty())
        assertTrue(compose.onAllNodes(hasText(game.black, substring = true)).fetchSemanticsNodes().isNotEmpty())
        // "%1$s (you)" with the name left out: the "(you)" a chosen side adds after a name.
        val you = app.getString(R.string.summary_name_you, "").trim()
        assertFalse(compose.onAllNodes(hasText(you, substring = true)).fetchSemanticsNodes().isNotEmpty())
        assertFalse(shown(app.getString(R.string.report_key_moments_you)))

        // Stored per game, like an answer on the Summary: reopening keeps it.
        val stored = runBlocking { app.gameRepository.listRecent() }.first { it.white == game.white && it.black == game.black }
        storedGameFile = File(app.filesDir, "games/${stored.id}.json")
        assertEquals(SideChoice.NOT_ME.storedName, runBlocking { app.gameRepository.load(stored.id) }?.userColorName)
    }
}
