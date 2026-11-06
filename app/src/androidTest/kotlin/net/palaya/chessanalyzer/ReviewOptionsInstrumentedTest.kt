package net.palaya.chessanalyzer

import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import kotlinx.coroutines.runBlocking
import net.palaya.chessanalyzer.core.narration.VideoPace
import net.palaya.chessanalyzer.data.FamousGamesStore
import net.palaya.chessanalyzer.data.GameRepository
import net.palaya.chessanalyzer.ui.model.AnalysisStrength
import net.palaya.chessanalyzer.ui.model.EngineLevel
import net.palaya.chessanalyzer.ui.model.EngineSettings
import net.palaya.chessanalyzer.ui.model.NarrationProviderChoice
import net.palaya.chessanalyzer.ui.screens.FAMOUS_SEARCH_TAG
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * RF on a device: the Review options screen decides a review once, before any processing.
 *  - after an import it opens pre-filled with the Settings defaults; the choices made on it are the ones the run uses
 *    (the stages on the processing screen) and are stored with the game;
 *  - with "Show the review options before each review" off, an import goes straight to processing;
 *  - a library row's "Re-analyse with different settings…" opens it pre-filled with THAT game's stored options.
 */
@RunWith(AndroidJUnit4::class)
class ReviewOptionsInstrumentedTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    private val app get() = TestApp.app
    private var scenario: ActivityScenario<MainActivity>? = null
    private var settingsBefore: EngineSettings? = null
    private val createdGames = ArrayList<String>()

    @After
    fun tearDown() {
        scenario?.close()
        runBlocking {
            settingsBefore?.let { app.settingsRepository.save(it) }
            TestApp.restoreReviewDefaults()
            app.narrationSettingsRepository.clearProviderChoiceForTesting()
        }
        createdGames.forEach { File(app.filesDir, "games/$it.json").delete() }
        runBlocking { app.gameRepository.listRecent() }.filter { it.black.contains("Tartakower") }
            .forEach { File(app.filesDir, "games/${it.id}.json").delete() }
    }

    private fun waitForText(text: String, timeoutMs: Long = 30_000) {
        compose.waitUntil(timeoutMs) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun shown(text: String): Boolean = compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()

    private fun reviewFamousGame(searchFor: String) {
        val entry = app.getString(R.string.famous_home_entry_title)
        waitForText(entry)
        compose.onNodeWithText(entry).performScrollTo().performClick()
        waitForText(app.getString(R.string.famous_search_label))
        compose.onNode(hasTestTag(FAMOUS_SEARCH_TAG) and hasSetTextAction()).performTextInput(searchFor)
        val title = FamousGamesStore.load(app).games.first { it.black.contains(searchFor) }.title
        waitForText(title)
        compose.onNode(hasText(title)).performClick()
        val review = app.getString(R.string.famous_review_action)
        waitForText(review)
        compose.onNodeWithText(review).performScrollTo().performClick()
    }

    @Test
    fun afterAnImportTheScreenOpensWithTheDefaultsAndItsChoicesAreTheRunsAndTheGames() {
        runBlocking {
            TestApp.ensureSetUp()
            val before = app.settingsRepository.current()
            settingsBefore = before
            app.settingsRepository.save(before.copy(depth = EngineLevel.L2900.code, videoPace = VideoPace.NORMAL))
            app.settingsRepository.setRephraseEnabled(false)
            TestApp.restoreReviewDefaults()
        }
        scenario = ActivityScenario.launch(MainActivity::class.java)
        waitForText(app.getString(R.string.home_title))
        reviewFamousGame("Tartakower")

        // Pre-filled from Settings: about 2900, "Prepare the video now" on, the famous game's "Not me".
        waitForText(app.getString(R.string.review_options_title))
        waitForText(app.getString(R.string.review_options_start)) // the title also shows while the options load
        compose.onNode(hasText(app.getString(R.string.level_label_2900)) and isSelected()).assertExists()
        compose.onNode(hasText(app.getString(R.string.review_options_prepare_video)) and isToggleable()).assertIsOn()
        compose.onNode(hasText(app.getString(R.string.summary_side_neither)) and isSelected()).assertExists()

        // This game: about 2500, no video now, Relaxed.
        compose.onNode(hasText(app.getString(R.string.level_label_2500)) and hasClickAction()).performClick()
        compose.onNode(hasText(app.getString(R.string.review_options_prepare_video)) and isToggleable()).performClick()
        compose.onNode(hasText(app.getString(R.string.review_options_prepare_video)) and isToggleable()).assertIsOff()
        compose.onNode(hasText(app.getString(R.string.settings_pace_relaxed)) and hasClickAction()).performScrollTo().performClick()
        compose.onNodeWithText(app.getString(R.string.review_options_start)).performClick()

        // One processing screen with only the stages these choices ask for: the engine (no wording, no video).
        waitForText(app.getString(R.string.progress_title))
        assertTrue(compose.onAllNodesWithContentDescription(app.getString(R.string.stage_engine), substring = true).fetchSemanticsNodes().isNotEmpty())
        assertTrue(compose.onAllNodesWithContentDescription(app.getString(R.string.stage_write_narration), substring = true).fetchSemanticsNodes().isEmpty())
        assertTrue(compose.onAllNodesWithContentDescription(app.getString(R.string.stage_record_voice), substring = true).fetchSemanticsNodes().isEmpty())

        waitForText(app.getString(R.string.summary_which_side), timeoutMs = 300_000)
        val stored = runBlocking { app.gameRepository.listRecent() }.first { it.black.contains("Tartakower") }
        val game = runBlocking { app.gameRepository.load(stored.id) }!!
        assertEquals("the game's strength is the one picked", EngineLevel.L2500.code, game.depth)
        assertEquals(false, game.prepareVideo)
        assertEquals(VideoPace.RELAXED.name, game.pace)
        assertEquals(false, game.naturalWording)
        // Settings are only the defaults: untouched by the game's choices.
        assertEquals(EngineLevel.L2900.code, runBlocking { app.settingsRepository.current() }.depth)
        assertEquals(VideoPace.NORMAL, runBlocking { app.settingsRepository.current() }.videoPace)
    }

    @Test
    fun withTheScreenSwitchedOffAnImportGoesStraightToProcessing() {
        runBlocking {
            TestApp.ensureSetUp()
            TestApp.reviewImportsWithDefaults()
        }
        scenario = ActivityScenario.launch(MainActivity::class.java)
        waitForText(app.getString(R.string.home_title))
        reviewFamousGame("Tartakower")
        waitForText(app.getString(R.string.progress_title))
        assertFalse("no Review options screen", shown(app.getString(R.string.review_options_title)))
        compose.onNodeWithText(app.getString(R.string.progress_cancel)).performClick()
        waitForText(app.getString(R.string.famous_search_label))
    }

    @Test
    fun aLibraryRowsReanalyseOpensTheScreenWithThatGamesOwnOptions() {
        runBlocking { TestApp.ensureSetUp() }
        val id = "rf-options-${System.nanoTime()}"
        createdGames += id
        runBlocking {
            app.gameRepository.save(
                GameRepository.StoredGame(
                    id = id,
                    pgnText = "[White \"Optiona\"]\n[Black \"Optionb\"]\n[Result \"*\"]\n\n1. e4 e5 2. Nf3 Nc6 *",
                    white = "Optiona", black = "Optionb", result = "*", date = "2026.10.11", plyCount = 4,
                    depth = AnalysisStrength.DEEP.depth, multiPv = 3, userColorName = "BLACK",
                    voiceProvider = NarrationProviderChoice.DEVICE.name, speakerId = 1, pace = VideoPace.RELAXED.name,
                    naturalWording = false, prepareVideo = false,
                ),
            )
        }
        scenario = ActivityScenario.launch(MainActivity::class.java)
        waitForText(app.getString(R.string.home_title))
        val name = net.palaya.chessanalyzer.video.versusLine(app.getString(R.string.game_vs_format), "Optiona", "Optionb")
        compose.onNode(hasContentDescription(app.getString(R.string.cd_library_menu, name))).performScrollTo().performClick()
        compose.onNodeWithText(app.getString(R.string.library_reanalyse)).performClick()

        waitForText(app.getString(R.string.review_options_title))
        waitForText(app.getString(R.string.review_options_start)) // the title also shows while the options load
        compose.onNode(hasText(app.getString(R.string.level_label_3100)) and isSelected()).assertExists()
        compose.onNode(hasText(app.getString(R.string.side_black)) and isSelected()).assertExists()
        compose.onNode(hasText(app.getString(R.string.review_options_prepare_video)) and isToggleable()).assertIsOff()
        compose.onNode(hasText(app.getString(R.string.settings_pace_relaxed)) and isSelected()).performScrollTo().assertExists()
        assertTrue(shown(app.getString(R.string.settings_voice_row_device)))

        // Back keeps the game as it was.
        compose.onNode(hasContentDescription(app.getString(R.string.common_back))).performClick()
        waitForText(app.getString(R.string.home_title))
        assertEquals(AnalysisStrength.DEEP.depth, runBlocking { app.gameRepository.load(id) }?.depth)
    }
}
