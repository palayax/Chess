package net.palaya.chessanalyzer.data

import java.io.File
import java.nio.file.Files
import net.palaya.chessanalyzer.ui.model.PreparedNarrationLedger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** RF: what was recorded survives a restart (`filesDir/review/prepared_narration.json`). */
class PreparedNarrationStoreTest {

    private val dir: File = Files.createTempDirectory("prepared-narration-test").toFile()

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    @Test
    fun noFileIsAnEmptyLedger() {
        val ledger = PreparedNarrationStore(dir).load()
        assertTrue(ledger.identities.isEmpty())
    }

    @Test
    fun theLedgerSurvivesAnotherInstance() {
        PreparedNarrationStore(dir).update { it.record("voice|fp", "script1", setOf("a", "b"), setOf("b")) }
        val reloaded = PreparedNarrationStore(dir).load()
        assertTrue(reloaded.isPrepared("voice|fp", "script1"))
        assertEquals(setOf("b"), reloaded.fallbackKeys("voice|fp"))
        assertFalse(reloaded.isPrepared("other|fp", "script1"))
    }

    @Test
    fun itLivesInTheReviewFolderWithoutALeftoverTempFile() {
        PreparedNarrationStore(dir).update { it.record("v", "s", setOf("k"), emptySet()) }
        val file = File(dir, "${GameRepository.REVIEW_DIR_NAME}/${PreparedNarrationStore.FILE_NAME}")
        assertTrue(file.isFile)
        assertFalse(File(file.path + ".tmp").exists())
    }

    @Test
    fun anUnreadableFileIsAnEmptyLedgerAndIsRemoved() {
        val file = File(dir, "${GameRepository.REVIEW_DIR_NAME}/${PreparedNarrationStore.FILE_NAME}")
        file.parentFile!!.mkdirs()
        file.writeText("{not json")
        assertTrue(PreparedNarrationStore(dir).load().identities.isEmpty())
        assertFalse(file.exists())
    }

    @Test
    fun clearingTheNarrationAudioForgetsEverything() {
        val store = PreparedNarrationStore(dir)
        store.update { it.record("v", "s", setOf("k"), setOf("k")) }
        store.clear()
        assertEquals(PreparedNarrationLedger(), store.load())
    }
}
