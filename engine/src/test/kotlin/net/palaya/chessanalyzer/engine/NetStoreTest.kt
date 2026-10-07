package net.palaya.chessanalyzer.engine

import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlin.random.Random
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** [NetStore] with a small synthetic net and pins made for it (the real one is 98.5 MB). */
class NetStoreTest {

    @get:Rule val tmp = TemporaryFolder()

    private val version = 0x6a448afaL
    private val arch = 0xa85b2205L

    private fun netBytes(seed: Int = 3, v: Long = version, a: Long = arch): ByteArray {
        val b = Random(seed).nextBytes(64_000)
        fun put(off: Int, x: Long) { for (i in 0..3) b[off + i] = ((x ushr (8 * i)) and 0xFF).toByte() }
        put(0, v); put(4, a); put(8, 10)
        return b
    }

    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    private val bytes = netBytes()
    private val pins = sha(bytes).let { NetPins("nn-${it.take(12)}.nnue", bytes.size.toLong(), it, arch, version) }

    private fun store(files: File) = NetStore(files, pins)

    @Test
    fun nothingInstalledMeansNoNet() {
        val s = store(tmp.newFolder())
        assertNull(s.activeNetOrNull())
        assertNull(s.verifiedNetOrNull())
    }

    @Test
    fun aVerifiedPartIsInstalledAtomicallyUnderNets() {
        val files = tmp.newFolder()
        val s = store(files)
        val part = s.partFileFor().apply { parentFile!!.mkdirs(); writeBytes(bytes) }
        assertEquals(File(files, "nets/${pins.fileName}.part"), part)

        val installed = s.installVerified(part)

        assertEquals(File(files, "nets/${pins.fileName}"), installed)
        assertFalse(part.exists())
        assertArrayEquals(bytes, installed.readBytes())
        assertEquals(installed, s.activeNetOrNull())
        assertEquals(installed, s.verifiedNetOrNull())
        assertEquals(installed, store(files).verifiedNetOrNull())
    }

    @Test
    fun aNetOfTheRightSizeButTheWrongHashIsNotVerified() {
        val files = tmp.newFolder()
        File(files, "nets").mkdirs()
        File(files, "nets/${pins.fileName}").writeBytes(netBytes(seed = 99))
        val s = store(files)
        assertTrue("the cheap check only looks at the size", s.activeNetOrNull() != null)
        assertNull("the engine gate hashes it", s.verifiedNetOrNull())
    }

    @Test
    fun aNetOfTheWrongSizeIsNotActive() {
        val files = tmp.newFolder()
        File(files, "nets").mkdirs()
        File(files, "nets/${pins.fileName}").writeBytes(bytes.copyOf(bytes.size - 1))
        assertNull(store(files).activeNetOrNull())
    }

    @Test
    fun aPartWhoseHeaderDoesNotMatchTheEngineIsRefused() {
        val files = tmp.newFolder()
        val s = store(files)
        val wrongArch = netBytes(a = arch + 1)
        val part = s.partFileFor().apply { parentFile!!.mkdirs(); writeBytes(wrongArch) }
        try {
            s.installVerified(part)
            fail("expected IOException")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("header"))
        }
        assertNull(s.activeNetOrNull())
    }

    @Test(expected = IllegalArgumentException::class)
    fun onlyNetNamesCanBeInstalled() {
        val files = tmp.newFolder()
        val s = store(files)
        val part = File(files, "x.part").apply { writeBytes(bytes) }
        s.installVerified(part, "../evil.nnue")
    }

    @Test
    fun deletePartsRemovesOnlyPartFiles() {
        val files = tmp.newFolder()
        val s = store(files)
        s.partFileFor().apply { parentFile!!.mkdirs(); writeBytes(ByteArray(5)) }
        s.installVerified(s.partFileFor().apply { writeBytes(bytes) })
        s.partFileFor("nn-000000000000.nnue").writeBytes(ByteArray(5))
        s.deleteParts()
        assertEquals(listOf(pins.fileName), File(files, "nets").list()!!.toList())
    }

    @Test
    fun theReadHeaderOfAFile() {
        val f = tmp.newFile().apply { writeBytes(bytes) }
        val h = store(tmp.root).readHeader(f)!!
        assertEquals(version, h.version)
        assertEquals(arch, h.archHash)
        assertEquals(10L, h.descriptionLength)
        assertNull(store(tmp.root).readHeader(tmp.newFile().apply { writeBytes(ByteArray(5)) }))
    }

    @Test
    fun migrateLegacyMovesTheBundledBuildsNetAndDropsTheRest() {
        val files = tmp.newFolder()
        File(files, pins.fileName).writeBytes(bytes)                         // the bundled build's net
        File(files, "${pins.fileName}.part").writeBytes(ByteArray(10))       // its interrupted copy
        File(files, "nn-aaaaaaaaaaaa.nnue").writeBytes(ByteArray(10))         // a net of another engine
        File(files, "games").mkdirs()
        File(files, "settings.txt").writeText("keep me")

        val s = store(files)
        val m = s.migrateLegacy()

        assertEquals(listOf(pins.fileName), m.moved)
        assertEquals(setOf("${pins.fileName}.part", "nn-aaaaaaaaaaaa.nnue"), m.deleted.toSet())
        assertFalse(File(files, pins.fileName).exists())
        assertEquals(File(files, "nets/${pins.fileName}"), s.verifiedNetOrNull())
        assertEquals("keep me", File(files, "settings.txt").readText())
        assertTrue(File(files, "games").isDirectory)

        val again = s.migrateLegacy()
        assertFalse("idempotent", again.didSomething)
    }

    @Test
    fun migrateLegacyDoesNotOverwriteANetAlreadyInNets() {
        val files = tmp.newFolder()
        val s = store(files)
        s.installVerified(s.partFileFor().apply { parentFile!!.mkdirs(); writeBytes(bytes) })
        File(files, pins.fileName).writeBytes(ByteArray(bytes.size))
        val m = s.migrateLegacy()
        assertTrue(m.moved.isEmpty())
        assertEquals(listOf(pins.fileName), m.deleted)
        assertArrayEquals(bytes, File(files, "nets/${pins.fileName}").readBytes())
    }

    @Test
    fun theDefaultStoreUsesTheCompiledPins() {
        val s = NetStore(tmp.newFolder())
        assertEquals(NetPins.COMPILED, s.pins)
        assertEquals(NetStore.NET_FILENAME, s.netFile.name)
        assertEquals(NetStore.DIR_NAME, s.netFile.parentFile!!.name)
    }

    // ---- D2e: the active identity (an update's net) ----

    private val newBytes = netBytes(seed = 11)
    private val newSha = sha(newBytes)
    private val newNet = ActiveNet("nn-${newSha.take(12)}.nnue", newBytes.size.toLong(), newSha)

    private fun installed(files: File): NetStore = store(files).also {
        it.installVerified(it.partFileFor().apply { parentFile!!.mkdirs(); writeBytes(bytes) })
    }

    @Test
    fun withoutARecordTheCompiledPinIsActive() {
        val s = installed(tmp.newFolder())
        assertEquals(ActiveNet(pins.fileName, pins.sizeBytes, pins.sha256), s.activeIdentity())
        assertEquals(s.compiledIdentity(), s.activeIdentity())
        assertFalse(s.hasUpdateRecord())
    }

    @Test
    fun anUpdateRecordMakesItsNetTheVerifiedOne() {
        val files = tmp.newFolder()
        val s = installed(files)
        s.installVerified(s.partFileFor(newNet.fileName).apply { writeBytes(newBytes) }, newNet.fileName)
        assertEquals("installing does not switch", pins.fileName, s.verifiedNetOrNull()?.name)
        s.setActiveIdentity(newNet)
        assertTrue(s.hasUpdateRecord())
        assertEquals(newNet, store(files).activeIdentity())
        assertEquals(newNet.fileName, s.verifiedNetOrNull()?.name)
        assertEquals("a cold store reads the record", newNet.fileName, store(files).verifiedNetOrNull()?.name)
        assertEquals(listOf(pins.fileName), s.otherNets().map { it.name })
        assertFalse("the active net cannot be deleted", s.deleteNet(newNet.fileName))
        assertTrue(s.deleteNet(pins.fileName))
        // Back to the compiled pin: the record is removed.
        s.setActiveIdentity(s.compiledIdentity())
        assertFalse(File(files, "nets/${NetStore.ACTIVE_RECORD_NAME}").exists())
    }

    @Test
    fun aRecordedNetWithTheWrongBytesIsNotVerified() {
        val files = tmp.newFolder()
        val s = installed(files)
        File(s.dir, newNet.fileName).writeBytes(newBytes.copyOf().also { it[5000] = (it[5000] + 1).toByte() })
        s.setActiveIdentity(newNet)
        assertEquals(newNet.fileName, s.activeNetOrNull()?.name)
        assertNull(s.verifiedNetOrNull())
    }

    @Test
    fun aRecordWhoseNetHasAForeignHeaderIsStale() {
        // An app update changed the engine's architecture: the older update's net cannot be loaded.
        val files = tmp.newFolder()
        val s = installed(files)
        val foreign = netBytes(seed = 12, a = 0x1234L)
        val fSha = sha(foreign)
        val f = ActiveNet("nn-${fSha.take(12)}.nnue", foreign.size.toLong(), fSha)
        File(s.dir, f.fileName).writeBytes(foreign)
        s.setActiveIdentity(f)
        assertEquals(s.compiledIdentity(), s.activeIdentity())
        assertEquals(pins.fileName, s.verifiedNetOrNull()?.name)
    }

    @Test
    fun aMalformedRecordIsIgnoredAndABadIdentityIsRefused() {
        val files = tmp.newFolder()
        val s = installed(files)
        File(s.dir, NetStore.ACTIVE_RECORD_NAME).writeText("fileName=nn-zzz.nnue\nsizeBytes=-1\nsha256=x\n")
        assertEquals(s.compiledIdentity(), s.activeIdentity())
        for (bad in listOf(newNet.copy(sha256 = "0".repeat(64)), newNet.copy(fileName = "net.nnue"), newNet.copy(sizeBytes = 0))) {
            try {
                s.setActiveIdentity(bad)
                fail("$bad must be refused")
            } catch (e: IllegalArgumentException) {
                // expected
            }
        }
    }
}
