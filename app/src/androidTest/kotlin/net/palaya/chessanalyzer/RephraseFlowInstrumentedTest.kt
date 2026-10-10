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
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.performScrollToNode
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

    /**
     * A "model" that makes small rewordings the checker accepts (facts and their order unchanged): it joins a card's
     * first two sentences ("Bd2 is the top engine move here, and it attacks…") and turns "that is what this cost
     * Black" into "that is the cost for Black". The key moments of the Opera Game are the consulting noblemen's
     * errors, whose cards carry that last phrase.
     */
    private val asked = java.util.Collections.synchronizedList(ArrayList<String>())
    private val fake = FakeRephraser(fakeId) { r ->
        asked.add(r.surface.name + ": " + r.text)
        if (r.surface == RephraseSurface.NARRATION) {
            narrationCalls++
            // merge the move and its evaluation into one sentence, as the real model does
            r.text.replaceFirst(". The evaluation moves", ", and the evaluation moves")
        } else r.text
            .replaceFirst(". It ", ", and it ")
            .replaceFirst(". That takes ", ", and that takes ")
            .replaceFirst(". This ", ", and this ")
            .replaceFirst(": that is what this cost ", ": that is the cost for ")
    }

    private var narrationCalls = 0

    private companion object {
        /**
         * The Opera Game (Morphy, 1858): the losing side makes several clear errors even at Quick, so the Summary
         * always has key moments to show. (Réti vs Tartakower, used before, can come out of a Quick run with none.)
         */
        const val LOSER = "Brunswick"
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
        runBlocking { app.gameRepository.listRecent() }.filter { it.black.contains(LOSER) }
            .forEach { File(app.filesDir, "games/${it.id}.json").delete() }
    }

    private fun waitForText(text: String, timeoutMs: Long = 30_000) {
        compose.waitUntil(timeoutMs) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    }

    /**
     * The Summary is a LazyColumn: an item below the composed window is not in the semantics tree (A4's "Move quality"
     * card pushed the key moments and the buttons down), so scroll the list to the node before looking it up.
     */
    private fun scrolledTo(matcher: androidx.compose.ui.test.SemanticsMatcher): Boolean =
        runCatching { compose.onNode(hasScrollAction()).performScrollToNode(matcher) }.isSuccess

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
        val game = FamousGamesStore.load(app).games.first { it.black.contains(LOSER) }

        scenario = ActivityScenario.launch(MainActivity::class.java)
        waitForText(app.getString(R.string.home_title))
        val entry = app.getString(R.string.famous_home_entry_title)
        waitForText(entry)
        compose.onNodeWithText(entry).performScrollTo().performClick()
        waitForText(app.getString(R.string.famous_search_label))
        compose.onNode(hasTestTag(FAMOUS_SEARCH_TAG) and hasSetTextAction()).performTextInput("Opera")
        waitForText(game.title)
        compose.onNode(hasText(game.title)).performClick()
        val review = app.getString(R.string.famous_review_action)
        waitForText(review)
        compose.onNodeWithText(review).performScrollTo().performClick()

        waitForText(app.getString(R.string.summary_which_side), timeoutMs = 300_000)
        // The polishing phase ran over the key moments: their verdicts are cached under the fake's folder.
        compose.waitUntil(30_000) { cacheDir.listFiles()?.isNotEmpty() == true }
        assertTrue("the fake was asked", fake.calls > 0)
        val reworded = hasText(", and it ", substring = true) or hasText(", and that takes ", substring = true) or
            hasText(", and this ", substring = true) or hasText(": that is the cost for ", substring = true)
        try {
            compose.waitUntil(30_000) { scrolledTo(reworded) }
        } catch (e: Throwable) {
            // say what the fake was given and what was cached, so a change in the texts is visible at once
            val files = cacheDir.listFiles().orEmpty().joinToString(" ## ") { it.readText().take(200).replace('\n', '|') }
            throw AssertionError("asked: ${asked.take(30)}; cached: $files", e)
        }

        // Every key-moment text the Summary shows is either an original or an accepted rewording of one.
        val stored = runBlocking { app.gameRepository.listRecent() }.first { it.black.contains(LOSER) }
        assertEquals(game.white, stored.white)
        val accepted = cacheDir.listFiles()!!.map { it.readText() }.count { it.startsWith("A\n") }
        assertTrue("$accepted accepted", accepted > 0)

        // The Video route: the narration pre-step rewords the beats (verdicts cached under the fake's folder,
        // NARRATION entries among them), and the script the screen gets carries the accepted wording.
        val narrationCallsBefore = narrationCalls
        assertTrue(scrolledTo(hasText(app.getString(R.string.summary_watch_video))))
        compose.onNodeWithText(app.getString(R.string.summary_watch_video)).performClick()
        compose.waitUntil(120_000) { compose.onAllNodesWithText(app.getString(R.string.video_title)).fetchSemanticsNodes().isNotEmpty() }
        assertTrue("the narration beats were sent to the model", narrationCalls > narrationCallsBefore)
        val activity = run {
            var a: MainActivity? = null
            scenario!!.onActivity { a = it }
            a!!
        }
        val vm = androidx.lifecycle.ViewModelProvider(activity)[net.palaya.chessanalyzer.ui.viewmodel.AnalysisViewModel::class.java]
        val base = runBlocking { vm.videoScriptFor(stored.id) }!!
        val worded = runBlocking { vm.rephrasedVideoScriptFor(stored.id) }!!
        assertEquals(base.segments.size, worded.segments.size)
        assertTrue(
            "some beat carries the accepted rewording",
            worded.segments.any { it.narration.contains(", and the evaluation moves") } &&
                base.segments.none { it.narration.contains(", and the evaluation moves") },
        )
        assertEquals(0, runBlocking { vm.narrationPolishPending(stored.id) })

        // Setting off: the service hands back the originals at once (the cache is only read when it is on).
        runBlocking { app.settingsRepository.setRephraseEnabled(false) }
        assertEquals(null, runBlocking { app.rephraseService.activeId() })
        assertEquals(base, runBlocking { vm.rephrasedVideoScriptFor(stored.id) })
    }
}
