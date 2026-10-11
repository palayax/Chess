package net.palaya.chessanalyzer.data

import kotlinx.coroutines.runBlocking
import net.palaya.chessanalyzer.ui.model.AnalysisStrength
import net.palaya.chessanalyzer.ui.model.AutoDepthBudget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * A4: the analysis strength is stored with the game, so reopening it keeps it, and the eval cache keeps
 * one result per strength (the budget is in the key), so a re-analyse never overwrites the other result.
 */
class GameRepositoryStrengthTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun repo() = GameRepository(folder.root) { "0123456789ab" }

    private fun stored(id: String, depth: Int) = GameRepository.StoredGame(
        id = id, pgnText = "1. e4 e5 *", white = "A", black = "B", result = "*", date = "2026.10.09",
        plyCount = 2, depth = depth, multiPv = 3, userColorName = "WHITE",
    )

    @Test
    fun theStrengthAGameWasAnalysedAtComesBackWhenItIsLoaded() = runBlocking {
        val r = repo()
        r.save(stored("g1", AnalysisStrength.DEEP.depth))
        assertEquals(AnalysisStrength.DEEP.depth, r.load("g1")?.depth)
        // A fresh repository over the same folder (a new process) reads the same value.
        assertEquals(AnalysisStrength.DEEP.depth, repo().load("g1")?.depth)
    }

    @Test
    fun aReanalyseReplacesTheStoredStrengthAndKeepsTheRestOfTheGame() = runBlocking {
        val r = repo()
        r.save(stored("g1", AnalysisStrength.QUICK.depth))
        val before = r.load("g1")!!
        r.save(before.copy(depth = AnalysisStrength.STANDARD.depth))
        val after = r.load("g1")!!
        assertEquals(AnalysisStrength.STANDARD.depth, after.depth)
        assertEquals(before.pgnText, after.pgnText)
        assertEquals(before.userColorName, after.userColorName)
        assertEquals(before.white, after.white)
    }

    @Test
    fun twoGamesKeepTheirOwnStrength() = runBlocking {
        val r = repo()
        r.save(stored("a", AnalysisStrength.QUICK.depth))
        r.save(stored("b", AnalysisStrength.DEEP.depth))
        assertEquals(AnalysisStrength.QUICK.depth, r.load("a")?.depth)
        assertEquals(AnalysisStrength.DEEP.depth, r.load("b")?.depth)
        assertNull(r.load("missing"))
    }

    @Test
    fun theEvalCacheKeyDiffersPerStrengthSoEachResultIsKept() {
        val r = repo()
        // AnalysisService's keys: Auto's assembled result has its own key part (spec §8.5).
        val keys = AnalysisStrength.entries.map {
            r.cacheKey("1. e4 e5 *", it.depth, 3, if (it.autoDepth) AutoDepthBudget.cacheKeyPart else it.budget.cacheKeyPart)
        }
        assertEquals(4, keys.toSet().size)
        assertNotEquals(keys[0], keys[1])
    }

    @Test
    fun anAutoGameComesBackAsAutoAndAGameSavedBeforeAutoReadsAsItsDepthAlone() = runBlocking {
        val r = repo()
        r.save(stored("auto", AnalysisStrength.AUTO.depth).copy(autoDepth = true))
        val loaded = repo().load("auto")!!
        assertEquals(AnalysisStrength.AUTO, AnalysisStrength.of(loaded.depth, loaded.autoDepth))
        // A game file written before Auto has no "autoDepth": it reads as before.
        java.io.File(folder.root, "games/old.json").writeText(
            """{"id":"old","pgnText":"1. e4 e5 *","white":"A","black":"B","result":"*","date":"","plyCount":2,"depth":14,"multiPv":3,"userColorName":null,"savedAtMs":1}""",
        )
        val old = repo().load("old")!!
        assertEquals(false, old.autoDepth)
        assertEquals(AnalysisStrength.STANDARD, AnalysisStrength.of(old.depth, old.autoDepth))
    }
}
