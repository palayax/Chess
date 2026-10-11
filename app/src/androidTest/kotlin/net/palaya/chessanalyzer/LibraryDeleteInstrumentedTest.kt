package net.palaya.chessanalyzer

import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import kotlinx.coroutines.runBlocking
import net.palaya.chessanalyzer.data.GameRepository
import net.palaya.chessanalyzer.ui.model.AnalysisStrength
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * RF, the owner's item 1 ("Can't delete existing games"): a library row's menu has Delete; the confirm dialog names the
 * game; Cancel keeps it; Delete removes its stored JSON, its eval cache (no other game has its text) and the row, and
 * leaves the other games alone.
 */
@RunWith(AndroidJUnit4::class)
class LibraryDeleteInstrumentedTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    private val app get() = TestApp.app
    private var scenario: ActivityScenario<MainActivity>? = null
    private val tag = System.nanoTime()
    private val keep = "rf-keep-$tag"
    private val gone = "rf-gone-$tag"

    @After
    fun tearDown() {
        scenario?.close()
        listOf(keep, gone).forEach { File(app.filesDir, "games/$it.json").delete() }
    }

    private fun waitForText(text: String, timeoutMs: Long = 30_000) {
        compose.waitUntil(timeoutMs) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun pgn(white: String, black: String, moves: String) = "[White \"$white\"]\n[Black \"$black\"]\n[Result \"*\"]\n\n$moves *"

    private fun stored(id: String, white: String, black: String, moves: String) = GameRepository.StoredGame(
        id = id, pgnText = pgn(white, black, moves), white = white, black = black, result = "*", date = "2026.10.11",
        plyCount = 4, depth = AnalysisStrength.QUICK.depth, multiPv = 3, userColorName = null,
    )

    @Test
    fun deleteAsksNamingTheGameThenRemovesItsFilesAndRowOnly() {
        runBlocking { TestApp.ensureSetUp() }
        val repo = app.gameRepository
        val goneGame = stored(gone, "Deletea$tag", "Deleteb", "1. e4 e5 2. Nf3 Nc6")
        val keepGame = stored(keep, "Keepa$tag", "Keepb", "1. d4 d5 2. c4 e6")
        val goneKey = repo.cacheKey(goneGame.pgnText, AnalysisStrength.QUICK.depth, 3, AnalysisStrength.QUICK.budget.cacheKeyPart)
        val keepKey = repo.cacheKey(keepGame.pgnText, AnalysisStrength.QUICK.depth, 3, AnalysisStrength.QUICK.budget.cacheKeyPart)
        runBlocking {
            repo.save(goneGame)
            repo.save(keepGame)
            repo.saveEvalCache(goneKey, emptyList())
            repo.saveEvalCache(keepKey, emptyList())
        }
        assertTrue(repo.hasEvalCache(goneKey))

        scenario = ActivityScenario.launch(MainActivity::class.java)
        waitForText(app.getString(R.string.home_title))
        val goneName = net.palaya.chessanalyzer.video.versusLine(app.getString(R.string.game_vs_format), goneGame.white, goneGame.black)
        val menu = hasContentDescription(app.getString(R.string.cd_library_menu, goneName))
        waitForText(goneName)

        // Cancel keeps the game.
        compose.onNode(menu).performScrollTo().performClick()
        compose.onNodeWithText(app.getString(R.string.library_delete)).performClick()
        waitForText(app.getString(R.string.library_delete_title))
        compose.onNode(hasText(app.getString(R.string.library_delete_body, goneName))).assertExists()
        compose.onNodeWithText(app.getString(R.string.library_delete_cancel)).performClick()
        assertNotNull(runBlocking { repo.load(gone) })

        // Delete removes it.
        compose.onNode(menu).performScrollTo().performClick()
        compose.onNodeWithText(app.getString(R.string.library_delete)).performClick()
        waitForText(app.getString(R.string.library_delete_title))
        compose.onNodeWithText(app.getString(R.string.library_delete_confirm)).performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText(goneName).fetchSemanticsNodes().isEmpty() }
        compose.waitUntil(10_000) { !File(app.filesDir, "games/$gone.json").exists() }
        compose.waitUntil(10_000) { !repo.hasEvalCache(goneKey) }
        assertNull(runBlocking { repo.load(gone) })
        assertFalse(runBlocking { repo.listRecent() }.any { it.id == gone })

        // The other game is untouched, row and cache.
        assertNotNull(runBlocking { repo.load(keep) })
        assertTrue(repo.hasEvalCache(keepKey))
        waitForText(net.palaya.chessanalyzer.video.versusLine(app.getString(R.string.game_vs_format), keepGame.white, keepGame.black))
        runBlocking { repo.deleteEvalCacheFor(listOf(keepKey)) }
    }
}
