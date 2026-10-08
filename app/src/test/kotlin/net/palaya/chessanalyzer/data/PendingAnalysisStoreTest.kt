package net.palaya.chessanalyzer.data

import java.io.File
import java.nio.file.Files
import net.palaya.chessanalyzer.ui.model.SideChoice
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The request on disk keeps the side a game opens with (G1-device): a famous game kept for Setup, or
 * resumed after a process kill, still opens as "Not me"; a shared game, and a file written by an older
 * build, has none.
 */
class PendingAnalysisStoreTest {

    private val dir: File = Files.createTempDirectory("pending-analysis-test").toFile()

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun request(initialSide: String?) = PendingAnalysisStore.Request(
        gameId = "imported-1-1",
        pgnText = "1. e4 e5 *",
        depth = 14,
        multiPv = 3,
        username = "",
        startedAtMs = 42L,
        initialSide = initialSide,
    )

    @Test
    fun aFamousGamesNotMeSurvivesTheRoundTrip() {
        val store = PendingAnalysisStore(dir, PendingAnalysisStore.WAITING_FOR_SETUP_FILE_NAME)
        store.save(request(SideChoice.NOT_ME.storedName))
        val loaded = store.load()
        assertEquals(request(SideChoice.NOT_ME.storedName), loaded)
        assertEquals(SideChoice.NOT_ME, SideChoice.fromStored(loaded?.initialSide))
    }

    @Test
    fun aSharedGameHasNoInitialSide() {
        val store = PendingAnalysisStore(dir)
        store.save(request(SideChoice.UNKNOWN.storedName))
        val loaded = store.load()
        assertNull(loaded?.initialSide)
        assertEquals(SideChoice.UNKNOWN, SideChoice.fromStored(loaded?.initialSide))
    }

    @Test
    fun aFileFromAnOlderBuildReadsWithNoInitialSide() {
        File(dir, PendingAnalysisStore.FILE_NAME).writeText(
            """{"gameId":"imported-1-1","pgnText":"1. e4 e5 *","depth":14,"multiPv":3,"username":"","startedAtMs":42}""",
        )
        assertEquals(request(null), PendingAnalysisStore(dir).load())
    }
}
