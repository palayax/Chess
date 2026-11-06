package net.palaya.chessanalyzer.data

import java.io.File
import kotlinx.coroutines.runBlocking
import net.palaya.chessanalyzer.core.narration.VideoPace
import net.palaya.chessanalyzer.ui.model.AnalysisStrength
import net.palaya.chessanalyzer.ui.model.EngineLevel
import net.palaya.chessanalyzer.ui.model.NarrationProviderChoice
import net.palaya.chessanalyzer.ui.model.ReviewOptions
import net.palaya.chessanalyzer.ui.model.ReviewSpeeds
import net.palaya.chessanalyzer.ui.model.ReviewStage
import net.palaya.chessanalyzer.ui.model.SideChoice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** RF: a game's review options are stored with it (backward compatible), pending options survive a restart, and delete. */
class GameRepositoryReviewOptionsTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val prefix = "0123456789ab"
    private fun repo() = GameRepository(folder.root) { prefix }

    private fun stored(id: String, pgn: String = "1. e4 e5 *") = GameRepository.StoredGame(
        id = id, pgnText = pgn, white = "A", black = "B", result = "*", date = "2026.10.11",
        plyCount = 2, depth = AnalysisStrength.STANDARD.depth, multiPv = 3, userColorName = null,
    )

    private val options = ReviewOptions(
        level = EngineLevel.L3100,
        side = SideChoice.BLACK,
        naturalWording = true,
        prepareVideo = false,
        voice = NarrationProviderChoice.NEURAL,
        speakerId = 9,
        pace = VideoPace.RELAXED,
    )

    @Test
    fun aGameSavedBeforeRfReadsBackWithNoOptions() = runBlocking {
        // The JSON an older build wrote: none of the new keys.
        File(folder.root, "games").mkdirs()
        File(folder.root, "games/old.json").writeText(
            """{"id":"old","pgnText":"1. e4 *","white":"A","black":"B","result":"*","date":"","plyCount":1,"depth":14,"multiPv":3,"userColorName":"WHITE","savedAtMs":1}""",
        )
        val g = repo().load("old")!!
        assertNull(g.voiceProvider)
        assertNull(g.speakerId)
        assertNull(g.pace)
        assertNull(g.naturalWording)
        assertNull(g.prepareVideo)
        val o = g.reviewOptions()
        assertEquals(14, o.depth)
        assertEquals(SideChoice.WHITE, o.side)
        assertNull(o.voice)
        assertNull(o.pace)
    }

    @Test
    fun theOptionsStoredWithAGameComeBackInANewProcess() = runBlocking {
        repo().save(stored("g1").withReviewOptions(options))
        val o = repo().load("g1")!!.reviewOptions()
        assertEquals(EngineLevel.L3100.code, o.depth)
        assertEquals(SideChoice.BLACK, o.side)
        assertEquals(true, o.naturalWording)
        assertEquals(false, o.prepareVideo)
        assertEquals(NarrationProviderChoice.NEURAL, o.voice)
        assertEquals(9, o.speakerId)
        assertEquals(VideoPace.RELAXED, o.pace)
    }

    @Test
    fun aTimeCappedEvalSurvivesTheCacheAndAnOldCacheReadsAsNotCapped() = runBlocking {
        val r = repo()
        val capped = net.palaya.chessanalyzer.core.analysis.PositionEval(fen = "f", lines = emptyList(), depth = 17, timeCapped = true)
        val plain = net.palaya.chessanalyzer.core.analysis.PositionEval(fen = "g", lines = emptyList(), depth = 17)
        r.saveEvalCache("k", listOf(capped, plain), prefix)
        val back = r.loadEvalCache("k", prefix)!!
        assertEquals(true, back[0].timeCapped)
        assertEquals(true, back[0].isCapped)
        assertEquals(false, back[1].timeCapped)
        assertEquals(false, back[1].isCapped)
    }

    @Test
    fun anUnknownStoredVoiceOrPaceReadsAsNotStored() = runBlocking {
        repo().save(stored("g1").copy(voiceProvider = "CLOUD", pace = "WARP", speakerId = 999))
        val o = repo().load("g1")!!.reviewOptions()
        assertNull(o.voice)
        assertNull(o.pace)
        assertNull(o.speakerId)
    }

    @Test
    fun pendingOptionsSurviveARestartAndAreClearedOnDemand() = runBlocking {
        repo().savePendingOptions("new", options)
        val o = repo().loadPendingOptions("new")!!
        assertEquals(EngineLevel.L3100.code, o.depth)
        assertEquals(SideChoice.BLACK, o.side)
        assertEquals(9, o.speakerId)
        assertEquals(VideoPace.RELAXED, o.pace)
        repo().clearPendingOptions("new")
        assertNull(repo().loadPendingOptions("new"))
        // They live in filesDir/review/, which is excluded from backup.
        repo().savePendingOptions("new", options)
        assertTrue(File(folder.root, "${GameRepository.REVIEW_DIR_NAME}/pending/new.json").isFile)
    }

    @Test
    fun aDeleteRemovesTheGameAndItsPendingOptionsAndTheLibraryRow() = runBlocking {
        val r = repo()
        r.save(stored("a"))
        r.save(stored("b", pgn = "1. d4 *"))
        r.savePendingOptions("a", options)
        assertTrue(r.delete("a"))
        assertNull(r.load("a"))
        assertNull(r.loadPendingOptions("a"))
        assertEquals(listOf("b"), r.listRecent().map { it.id })
        assertFalse(r.delete("a"))
        assertEquals(emptyList<String>(), r.storedPgnTexts(exceptId = "b"))
        assertEquals(listOf("1. d4 *"), r.storedPgnTexts())
    }

    @Test
    fun theEvalCacheOfAGameIsDeletedInEveryNetFolder() = runBlocking {
        val r = repo()
        val key = r.cacheKey("1. e4 e5 *", 14, 3, "n1:t1")
        r.saveEvalCache(key, emptyList(), prefix)
        r.savePartialEvalCache(key, emptyList(), "ba9876543210")
        val other = r.cacheKey("1. d4 *", 14, 3, "n1:t1")
        r.saveEvalCache(other, emptyList(), prefix)
        assertTrue(r.hasEvalCache(key))
        assertEquals(2, r.deleteEvalCacheFor(listOf(key)))
        assertFalse(r.hasEvalCache(key))
        assertTrue(r.hasEvalCache(other))
    }

    @Test
    fun theLibraryListsEveryGameNotOnlyTwenty() = runBlocking {
        val r = repo()
        repeat(25) { r.save(stored("g$it")) }
        assertEquals(25, r.listRecent().size)
    }

    @Test
    fun measuredSpeedsSurviveARestart() {
        val store = ReviewSpeedStore(folder.root)
        assertNull(store.load().engineMsPerNode)
        store.save(ReviewSpeeds(engineMsPerNode = 0.002, msPerPly = mapOf(ReviewStage.RECORD_VOICE to 1500.0)))
        val again = ReviewSpeedStore(folder.root).load()
        assertEquals(0.002, again.engineMsPerNode!!, 1e-12)
        assertEquals(1500.0, again.msPerPly.getValue(ReviewStage.RECORD_VOICE), 1e-9)
        // A damaged file is dropped, not a crash.
        File(folder.root, "${GameRepository.REVIEW_DIR_NAME}/${ReviewSpeedStore.FILE_NAME}").writeText("{not json")
        assertNull(ReviewSpeedStore(folder.root).load().engineMsPerNode)
    }

    @Test
    fun aLegacyPendingAutoStrengthMeansTheTopLevelNotDepth14() = runBlocking {
        val dir = File(folder.root, "${GameRepository.REVIEW_DIR_NAME}/pending").apply { mkdirs() }
        File(dir, "auto.json").writeText("""{"strength":"AUTO","side":"WHITE"}""")
        File(dir, "std.json").writeText("""{"strength":"STANDARD"}""")
        File(dir, "deep.json").writeText("""{"strength":"DEEP"}""")
        val r = repo()
        assertEquals(EngineLevel.L3100.code, r.loadPendingOptions("auto")!!.depth)
        // The other legacy strengths keep their depth (the options screen pre-selects the nearest level from it).
        assertEquals(14, r.loadPendingOptions("std")!!.depth)
        assertEquals(18, r.loadPendingOptions("deep")!!.depth)
    }
}
