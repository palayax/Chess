package net.palaya.chessanalyzer

import android.util.Log
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.runBlocking
import net.palaya.chessanalyzer.core.analysis.MaterialBalance
import net.palaya.chessanalyzer.core.chess.Color as CoreColor
import net.palaya.chessanalyzer.core.pgn.PgnParser
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
 * A4 on a device, in the real app: one short real game (Reti - Tartakower, 1910, 21 plies) is reviewed at Quick
 * (the Settings default), then:
 *
 *  1. **Move quality.** The Summary shows the table without opening anything: every one of the ten classes for
 *     both sides (zeros included), and the rows add up to the plies played (21 = White's + Black's moves).
 *  2. **Material.** The Summary's "Material at the end" says who was ahead and by how much, equal to the sum of
 *     the pieces left on the final board counted square by square.
 *  3. **Per-game strength.** "Analysed at Quick"; Re-analyse at Standard runs the engine again (a progress screen
 *     with the real engine), the Summary then says Standard, the Settings default is still Quick, the game's
 *     stored depth is Standard's; going Home and reopening the game gives the same Summary at Standard at once,
 *     from the eval cache of that strength (no engine run).
 *  4. **The Board** shows both players' names with their captured pieces, and "+N" for the side ahead at the last
 *     move.
 *
 * Pass `-e a4HoldMs 9000` to `am instrument` to pause at each marked stage for a screenshot (logcat tag A4Test).
 */
@RunWith(AndroidJUnit4::class)
class A4AnalyticsInstrumentedTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    private val app get() = TestApp.app
    private var scenario: ActivityScenario<MainActivity>? = null
    private var settingsBefore: EngineSettings? = null
    private var storedGameFile: File? = null

    @After
    fun tearDown() {
        scenario?.close()
        settingsBefore?.let { runBlocking { app.settingsRepository.save(it) } }
        storedGameFile?.delete()
    }

    private fun waitForText(text: String, timeoutMs: Long = 30_000) {
        compose.waitUntil(timeoutMs) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun shown(text: String): Boolean = compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()

    /** Marks a stage for the screenshot loop on the host and, when asked, holds the screen. */
    private fun stage(name: String) {
        val hold = InstrumentationRegistry.getArguments().getString("a4HoldMs")?.toLongOrNull() ?: return
        Log.i("A4Test", "A4_SHOT $name")
        Thread.sleep(hold)
    }

    private val heading = androidx.compose.ui.test.SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)

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

    /** "Mistake, White 2, Black 1" -> the two numbers, for the row of [className]; null if the row is not drawn. */
    private fun rowCounts(className: String): Pair<Int, Int>? {
        val template = app.getString(R.string.cd_class_row, className, 111111, 222222)
        val re = Regex("^" + Regex.escape(template).replace("111111", "\\E(\\d+)\\Q").replace("222222", "\\E(\\d+)\\Q") + "$")
        for (node in compose.onAllNodesWithContentDescription(className, substring = true).fetchSemanticsNodes()) {
            val d = node.config.getOrNull(SemanticsProperties.ContentDescription)?.firstOrNull() ?: continue
            val m = re.find(d) ?: continue
            return m.groupValues[1].toInt() to m.groupValues[2].toInt()
        }
        return null
    }

    private val classNames
        get() = listOf(
            R.string.classification_brilliant, R.string.classification_great, R.string.classification_best,
            R.string.classification_excellent, R.string.classification_good, R.string.classification_book,
            R.string.classification_inaccuracy, R.string.classification_mistake, R.string.classification_miss,
            R.string.classification_blunder,
        ).map { app.getString(it) }

    /** Points on a board counted square by square with the rules engine, independent of FEN parsing. */
    private fun pointsOf(fen: String, color: CoreColor): Int {
        val pos = net.palaya.chessanalyzer.core.chess.Position.fromFen(fen)
        var total = 0
        for (i in 0 until 64) {
            val piece = pos.pieceAt(net.palaya.chessanalyzer.core.chess.Square(i)) ?: continue
            if (piece.color == color) total += MaterialBalance.valueOf(piece.type)
        }
        return total
    }

    @Test
    fun summaryTableMaterialPerGameStrengthAndBoardStrips() {
        runBlocking { TestApp.ensureSetUp() }
        val famous = FamousGamesStore.load(app).games.first { it.black.contains("Tartakower") }
        val pgn = FamousGamesStore.load(app).let { it.pgnFor(famous.id)!! }
        val parsed = PgnParser.parse(pgn).first()
        // The names the app shows are the PGN's own tags.
        val whiteName = parsed.tags["White"].orEmpty()
        val blackName = parsed.tags["Black"].orEmpty()
        val plies = parsed.moves.size
        val finalFen = parsed.moves.last().positionFenAfter
        runBlocking {
            val before = app.settingsRepository.current()
            settingsBefore = before
            app.settingsRepository.save(before.copy(depth = AnalysisStrength.QUICK.depth, username = ""))
        }
        scenario = ActivityScenario.launch(MainActivity::class.java)
        waitForText(app.getString(R.string.home_title))

        // ---- Review at Quick, the Settings default ----
        reviewFamousGame("Tartakower")
        waitForText(app.getString(R.string.summary_which_side), timeoutMs = 300_000)
        val stored = runBlocking { app.gameRepository.listRecent() }.first { it.white == whiteName && it.black == blackName }
        storedGameFile = File(app.filesDir, "games/${stored.id}.json")
        val gameId = stored.id
        assertEquals("analysed at the Settings default", AnalysisStrength.QUICK.depth, runBlocking { app.gameRepository.load(gameId) }?.depth)

        // ---- 1. Move quality: visible without opening anything, every class, both sides ----
        waitForText(app.getString(R.string.summary_move_quality))
        compose.onNode(hasText(app.getString(R.string.summary_move_quality)) and heading).assertExists()
        var whiteTotal = 0
        var blackTotal = 0
        for (name in classNames) {
            val counts = rowCounts(name)
            assertTrue("a row for $name with both sides' counts", counts != null)
            whiteTotal += counts!!.first
            blackTotal += counts.second
        }
        rowCounts(app.getString(R.string.classification_forced))?.let { whiteTotal += it.first; blackTotal += it.second }
        assertEquals("White's rows add up to White's moves", (plies + 1) / 2, whiteTotal)
        assertEquals("Black's rows add up to Black's moves", plies / 2, blackTotal)

        // ---- 2. Material at the end ----
        val balance = MaterialBalance.fromFen(finalFen)
        val whitePoints = pointsOf(finalFen, CoreColor.WHITE)
        val blackPoints = pointsOf(finalFen, CoreColor.BLACK)
        assertEquals(whitePoints, balance.white)
        assertEquals(blackPoints, balance.black)
        val expectedLine = when {
            whitePoints == blackPoints -> app.getString(R.string.summary_material_level)
            whitePoints > blackPoints -> app.getString(R.string.summary_material_ahead, whiteName, whitePoints - blackPoints)
            else -> app.getString(R.string.summary_material_ahead, blackName, blackPoints - whitePoints)
        }
        compose.onNodeWithText(app.getString(R.string.summary_final_material)).performScrollTo()
        compose.onNodeWithText(expectedLine).performScrollTo().assertExists()
        stage("summary_stats_material")

        // ---- 3. Per-game strength ----
        compose.onNodeWithText(app.getString(R.string.reanalyse_analysed_at, app.getString(R.string.settings_depth_quick))).performScrollTo().assertExists()
        compose.onNodeWithText(app.getString(R.string.reanalyse_button)).performScrollTo().performClick()
        waitForText(app.getString(R.string.reanalyse_title))
        stage("reanalyse_dialog")
        // Nothing is picked: Re-analyse is disabled; the current strength is marked.
        compose.onNodeWithText(app.getString(R.string.reanalyse_confirm)).assertIsNotEnabled()
        assertTrue(shown("${app.getString(R.string.settings_depth_quick)} (${app.getString(R.string.reanalyse_current)})"))
        compose.onNodeWithText(app.getString(R.string.settings_depth_standard)).performClick()
        compose.onNodeWithText(app.getString(R.string.reanalyse_confirm)).performClick()

        // The Analysing screen with the real engine, then a new Summary.
        waitForText(app.getString(R.string.progress_title))
        stage("reanalyse_progress")
        waitForText(app.getString(R.string.summary_which_side), timeoutMs = 600_000)
        compose.onNodeWithText(app.getString(R.string.reanalyse_analysed_at, app.getString(R.string.settings_depth_standard))).performScrollTo().assertExists()
        stage("summary_after_reanalyse")
        assertEquals("the game's own strength is stored", AnalysisStrength.STANDARD.depth, runBlocking { app.gameRepository.load(gameId) }?.depth)
        assertEquals("the Settings default is untouched", AnalysisStrength.QUICK.depth, runBlocking { app.settingsRepository.current() }.depth)
        // The table is still complete after the second analysis.
        for (name in classNames) assertTrue("a row for $name", rowCounts(name) != null)

        // ---- Reopen in a NEW process-like state: the game keeps its strength, answered from that strength's cache ----
        // Closing the scenario destroys the Activity and its ViewModel (all results in memory), so the reopened
        // game is read from disk: its stored depth (Standard) decides, not the Settings default (Quick).
        scenario?.close()
        scenario = ActivityScenario.launch(MainActivity::class.java)
        waitForText(app.getString(R.string.home_title))
        val startedReopen = System.nanoTime()
        compose.onNode(hasClickAction() and hasText(blackName, substring = true)).performScrollTo().performClick()
        waitForText(app.getString(R.string.summary_which_side), timeoutMs = 120_000)
        val reopenMs = (System.nanoTime() - startedReopen) / 1_000_000
        compose.onNodeWithText(app.getString(R.string.reanalyse_analysed_at, app.getString(R.string.settings_depth_standard))).performScrollTo().assertExists()
        assertTrue("reopening is answered from the cache of the game's own strength, not by the engine ($reopenMs ms)", reopenMs < 40_000)
        assertEquals("reopening does not change the game's strength", AnalysisStrength.STANDARD.depth, runBlocking { app.gameRepository.load(gameId) }?.depth)
        assertEquals("the Settings default is still Quick", AnalysisStrength.QUICK.depth, runBlocking { app.settingsRepository.current() }.depth)

        // ---- 4. The Board: names, captured pieces, "+N" ----
        compose.onNodeWithText(app.getString(R.string.summary_open_board)).performScrollTo().performClick()
        waitForText(app.getString(R.string.review_start_position_hint))
        // At the start both lines say nothing is captured.
        assertTrue(compose.onAllNodesWithContentDescription(app.getString(R.string.cd_material_none, whiteName)).fetchSemanticsNodes().isNotEmpty())
        assertTrue(compose.onAllNodesWithContentDescription(app.getString(R.string.cd_material_none, blackName)).fetchSemanticsNodes().isNotEmpty())
        compose.onNodeWithContentDescription(app.getString(R.string.review_last_move)).performClick()
        // At the last move the captures are listed for the side that made them ("<name>: captured ...").
        val takenPrefix = { name: String -> app.getString(R.string.cd_material_taken, name, "") }
        compose.waitUntil(15_000) {
            compose.onAllNodesWithContentDescription(takenPrefix(whiteName), substring = true).fetchSemanticsNodes().isNotEmpty() ||
                compose.onAllNodesWithContentDescription(takenPrefix(blackName), substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        if (balance.difference != 0) {
            // "+N" is spoken as ", ahead by N" on exactly one of the two lines (the side that is ahead).
            val ahead = kotlin.math.abs(balance.difference)
            val spokenAhead = app.getString(R.string.cd_material_ahead, ahead)
            assertEquals(1, compose.onAllNodesWithContentDescription(spokenAhead, substring = true).fetchSemanticsNodes().size)
            val aheadName = if (balance.difference > 0) whiteName else blackName
            val line = compose.onAllNodesWithContentDescription(spokenAhead, substring = true).fetchSemanticsNodes().single()
                .config.getOrNull(SemanticsProperties.ContentDescription)?.firstOrNull().orEmpty()
            assertTrue("the plus belongs to $aheadName: $line", line.startsWith(aheadName))
        }
        stage("board_material")
    }
}
