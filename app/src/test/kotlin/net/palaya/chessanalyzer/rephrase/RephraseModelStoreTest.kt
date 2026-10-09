package net.palaya.chessanalyzer.rephrase

import net.palaya.chessanalyzer.data.models.TestGguf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** docs/LLM_REPHRASE_DESIGN.md §1.4, §6.4: the wording model's store on a temporary filesDir with a stand-in GGUF. */
class RephraseModelStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val gguf = TestGguf.bytes()
    private val pins = RephraseModelStore.Pins("test-model", "test-model.gguf", gguf.size.toLong(), TestGguf.sha256(gguf), "qwen2", "models-2026.11")

    private fun store(files: File = tmp.root) = RephraseModelStore(files, pins)

    @Test
    fun aVerifiedPartIsMovedIntoPlaceAndHashedOnce() {
        val s = store()
        s.partFile.apply { parentFile!!.mkdirs(); writeBytes(gguf) }
        val f = s.installVerified(s.partFile)
        assertEquals(File(tmp.root, "rephrase/models/test-model.gguf"), f)
        assertFalse(s.partFile.exists())
        assertTrue(s.isInstalled())
        assertEquals(pins.sha256, s.installedSha256())
        assertEquals(f, store().verifiedFileOrNull())
    }

    @Test
    fun aPartThatIsNotAGgufOrIsTruncatedIsRefusedAndDeleted() {
        val s = store()
        s.partFile.apply { parentFile!!.mkdirs(); writeBytes(gguf.copyOf(gguf.size - 1000)) }
        try {
            s.installVerified(s.partFile)
            fail("accepted a truncated GGUF")
        } catch (e: RephraseModelDamagedException) {
            assertTrue(e.message, e.message!!.contains("tensors need"))
        }
        assertFalse(s.partFile.exists())
        assertFalse(s.isInstalled())
        s.partFile.writeBytes("<html>not found</html>".toByteArray())
        try {
            s.installVerified(s.partFile)
            fail("accepted HTML")
        } catch (e: RephraseModelDamagedException) {
            assertTrue(e.message!!.contains("magic") || e.message!!.contains("truncated"))
        }
    }

    @Test
    fun aWrongArchitectureIsRefused() {
        val s = store()
        s.partFile.apply { parentFile!!.mkdirs(); writeBytes(TestGguf.bytes(arch = "llama")) }
        try {
            s.installVerified(s.partFile)
            fail("accepted another architecture")
        } catch (e: RephraseModelDamagedException) {
            assertTrue(e.message, e.message!!.contains("architecture"))
        }
    }

    @Test
    fun aFileThatNoLongerHashesRightIsDeletedBeforeItsFirstUse() {
        val s = store()
        File(s.modelsDir.apply { mkdirs() }, pins.fileName).writeBytes(gguf.copyOf().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() })
        assertTrue("the size matches, so it looks installed", s.isInstalled())
        assertNull(s.verifiedFileOrNull())
        assertFalse(s.isInstalled())
    }

    @Test
    fun wantedRemoveAndParts() {
        val s = store()
        assertFalse(s.isWanted())
        s.setWanted(true)
        assertTrue(store().isWanted())
        s.partFile.apply { parentFile!!.mkdirs(); writeBytes(gguf.copyOf(100)) }
        s.deleteParts()
        assertFalse(s.partFile.exists())
        File(s.modelsDir, pins.fileName).writeBytes(gguf)
        s.remove()
        assertFalse(s.isInstalled())
        assertFalse(s.isWanted())
    }

    @Test
    fun theLoadJournalTurnsTheFeatureOffAfterTwoDeathsInARowAndAFirstSuccessResetsIt() {
        val s = store()
        assertEquals(RephraseModelStore.Recovery.NOTHING, s.recoverOnStartup())
        s.begin() // the process dies loading
        assertEquals(RephraseModelStore.Recovery.RECHECK, store().recoverOnStartup())
        store().begin() // and again
        assertEquals(RephraseModelStore.Recovery.TURN_OFF, store().recoverOnStartup())
        val t = store()
        t.begin()
        t.clear() // a first completion
        assertEquals(RephraseModelStore.Recovery.NOTHING, store().recoverOnStartup())
        store().begin()
        assertEquals("the count started again", RephraseModelStore.Recovery.RECHECK, store().recoverOnStartup())
    }

    @Test
    fun anUpdateRecordIsUsedOnlyWhileItsFileIsThere() {
        val s = store()
        val other = pins.copy(id = "other", fileName = "other.gguf", sha256 = "ab".repeat(32))
        s.setActivePins(other)
        assertEquals("no file yet: the compiled pins", pins, s.activePins())
        File(s.modelsDir, "other.gguf").writeBytes(gguf)
        assertEquals(other, store().activePins())
        s.setActivePins(null)
        assertEquals(pins, store().activePins())
    }
}
