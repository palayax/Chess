package net.palaya.chessanalyzer

import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import kotlinx.coroutines.runBlocking
import net.palaya.chessanalyzer.core.text.FakeRephraser
import net.palaya.chessanalyzer.core.text.RephraseSurface
import net.palaya.chessanalyzer.data.FamousGamesStore
import net.palaya.chessanalyzer.ui.model.AnalysisStrength
import net.palaya.chessanalyzer.ui.model.EngineSettings
import net.palaya.chessanalyzer.ui.screens.FAMOUS_SEARCH_TAG
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * C2 on a device (docs/LLM_REPHRASE_DESIGN.md §6.2, §8.2): with Natural wording on and a model behind the interface
 * (a [FakeRephraser] through `ChessAnalyzerApplication.rephraserForTesting`, so no 1.1 GB file is needed here), a
 * reviewed game's key-moment cards show the reworded text that the real ClaimChecker accepted, the verdicts are
 * cached under `filesDir/rephrase/cache/<model>/`, and with the setting off the same report shows the originals.
 * The real llama.cpp backend is `:rephrase`'s LlamaRephraserInstrumentedTest.
 */
@RunWith(AndroidJUnit4::class)
class RephraseFlowInstrumentedTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    private val app get() = TestApp.app
    private var scenario: ActivityScenario<MainActivity>? = null
    private var settingsBefore: EngineSettings? = null
    private val fakeId = "fake-flow@p1"
    private val cacheDir get() = File(app.filesDir, "rephrase/cache/fake-flow")

    /** A "model" that rewords the charge sentences of error cards, the way the checker accepts. */
    private val fake = FakeRephraser(fakeId) { r ->
        if (r.surface != RephraseSurface.CARD) r.text
        else r.text
            .replace(Regex("^Now (White|Black|you|your opponent) can play "), "$1 can now play ")
            .replace(Regex("^This hands (White|Black|you|your opponent) "), "This gives $1 ")
            .replace(Regex("^This lets (White|Black|you|your opponent) play "), "Now $1 gets to play ")
    }

    @After
    fun tearDown() {
        scenario?.close()
        app.rephraserForTesting = null
        runBlocking {
            app.settingsRepository.setRephraseEnabled(false)
            settingsBefore?.let { app.settingsRepository.save(it) }
        }
        cacheDir.deleteRecursively()
        // the reviewed game must not stay in Home's recent games for later tests
        runBlocking { app.gameRepository.listRecent() }.filter { it.black.contains("Tartakower") }
            .forEach { File(app.filesDir, "games/${it.id}.json").delete() }
    }

    private fun waitForText(text: String, timeoutMs: Long = 30_000) {
        compose.waitUntil(timeoutMs) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun shownSubstring(text: String): Boolean = compose.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun aReviewedGamesKeyMomentsShowTheAcceptedRewordingAndTheSettingOffShowsTheOriginals() {
        runBlocking {
            TestApp.ensureSetUp()
            val before = app.settingsRepository.current()
            settingsBefore = before
            app.settingsRepository.save(before.copy(depth = AnalysisStrength.QUICK.depth))
            app.settingsRepository.setRephraseEnabled(true)
        }
        cacheDir.deleteRecursively()
        app.rephraserForTesting = fake
        val game = FamousGamesStore.load(app).games.first { it.black.contains("Tartakower") }

        scenario = ActivityScenario.launch(MainActivity::class.java)
        waitForText(app.getString(R.string.home_title))
        val entry = app.getString(R.string.famous_home_entry_title)
        waitForText(entry)
        compose.onNodeWithText(entry).performScrollTo().performClick()
        waitForText(app.getString(R.string.famous_search_label))
        compose.onNode(hasTestTag(FAMOUS_SEARCH_TAG) and hasSetTextAction()).performTextInput("Tartakower")
        waitForText(game.title)
        compose.onNode(hasText(game.title)).performClick()
        val review = app.getString(R.string.famous_review_action)
        waitForText(review)
        compose.onNodeWithText(review).performScrollTo().performClick()

        waitForText(app.getString(R.string.summary_which_side), timeoutMs = 300_000)
        // The polishing phase ran over the key moments: their verdicts are cached under the fake's folder.
        compose.waitUntil(30_000) { cacheDir.listFiles()?.isNotEmpty() == true }
        assertTrue("the fake was asked", fake.calls > 0)
        val reworded = listOf("can now play", "This gives ", "gets to play")
        compose.waitUntil(30_000) { reworded.any { shownSubstring(it) } }

        // Every key-moment text the Summary shows is either an original or an accepted rewording of one.
        val stored = runBlocking { app.gameRepository.listRecent() }.first { it.black.contains("Tartakower") }
        assertEquals(game.white, stored.white)
        val accepted = cacheDir.listFiles()!!.map { it.readText() }.count { it.startsWith("A\n") }
        assertTrue("$accepted accepted", accepted > 0)

        // Setting off: the service hands back the originals at once (the cache is only read when it is on).
        runBlocking { app.settingsRepository.setRephraseEnabled(false) }
        assertEquals(null, runBlocking { app.rephraseService.activeId() })
    }
}
