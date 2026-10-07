package net.palaya.chessanalyzer.engine

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * [NetStore] on a device against the real 98.5 MB net (D2d; ported from the bundled builds'
 * `BundledNetProviderInstrumentedTest`). The net is no longer in any APK the user installs: this test
 * APK carries a seed copy as a stored asset (`nnue/<name>`), copied here into a `.part` exactly where
 * the download writes it, then handed over with [NetStore.installVerified], the download's own tail.
 *
 * Every test works in a scratch directory, never the filesDir [TestNet] and the engine tests share, and
 * every one fails loudly if the seed is missing, deflated or the wrong size; none of them can skip.
 */
@RunWith(AndroidJUnit4::class)
class NetStoreInstrumentedTest {

    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var dir: File

    private val name = NetStore.NET_FILENAME
    private val seedPath = "nnue/$name"

    @Before
    fun setUp() {
        dir = File(context.filesDir, "net-store-test-${System.nanoTime()}").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun store() = NetStore(dir)

    /**
     * Copies the seed asset to the store's part file, as a finished download leaves it. [flipAt], when
     * given, is an absolute offset whose byte is inverted on the way (a damaged download).
     */
    private fun seedPart(store: NetStore, flipAt: Long? = null): File {
        val part = store.partFileFor()
        part.parentFile!!.mkdirs()
        context.assets.open(seedPath).use { input ->
            part.outputStream().use { out ->
                val buf = ByteArray(1 shl 20)
                var pos = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n == -1) break
                    if (flipAt != null && flipAt >= pos && flipAt < pos + n) {
                        val i = (flipAt - pos).toInt()
                        buf[i] = (buf[i].toInt() xor 0xFF).toByte()
                    }
                    out.write(buf, 0, n)
                    pos += n
                }
            }
        }
        return part
    }

    private fun flipByte(file: File, offset: Long) {
        RandomAccessFile(file, "rw").use { raf ->
            raf.seek(offset)
            val b = raf.read()
            raf.seek(offset)
            raf.write(b xor 0xFF)
        }
    }

    private fun installReal(store: NetStore = store()): File = store.installVerified(seedPart(store))

    @Test
    fun theSeedAssetIsStoredUncompressedWithThePinnedSizeAndHash() {
        // openFd() throws FileNotFoundException for a deflated asset, so this proves "stored".
        context.assets.openFd(seedPath).use { assertEquals(NetStore.NET_SIZE_BYTES, it.length) }
        val part = seedPart(store())
        assertEquals(NetStore.NET_SIZE_BYTES, part.length())
        assertEquals("the seed must be the pinned net", NetStore.NET_SHA256, NetStore.sha256Of(part))
        assertTrue("the pinned hash starts with the 12 hex digits of the net's name", NetStore.NET_SHA256.startsWith(NetStore.prefixOf(name)!!))
    }

    @Test
    fun aFreshDirectoryHasNoNetAndTheGateSaysSo() {
        val s = store()
        assertNull(s.activeNetOrNull())
        assertNull("the engine gate must refuse when nothing is installed", s.verifiedNetOrNull())
        assertEquals(File(File(dir, NetStore.DIR_NAME), name), s.netFile)
        assertEquals(File(File(dir, NetStore.DIR_NAME), name + NetStore.PART_SUFFIX), s.partFileFor())
    }

    @Test
    fun aVerifiedPartIsMovedIntoNetsAndPassesTheEngineGate() {
        val s = store()
        val part = seedPart(s)
        val installed = s.installVerified(part)

        assertEquals(s.netFile, installed)
        assertFalse("the part is moved, not copied", part.exists())
        assertEquals(NetStore.NET_SIZE_BYTES, installed.length())
        assertEquals(installed, s.verifiedNetOrNull())
        // A cold start (a new store over the same directory) hashes it and accepts it too.
        assertEquals(installed, store().verifiedNetOrNull())
        val header = s.readHeader(installed)
        assertNotNull(header)
        assertTrue("the real net's header matches the compiled engine: $header", header!!.matches(NetPins.COMPILED))
        assertTrue(dir.walkTopDown().none { it.name.endsWith(NetStore.PART_SUFFIX) })
    }

    @Test
    fun aTruncatedNetIsNeitherActiveNorVerified() {
        val s = store()
        s.dir.mkdirs()
        s.netFile.writeBytes(ByteArray(4096) { 7 })
        assertNull(s.activeNetOrNull())
        assertNull(s.verifiedNetOrNull())
    }

    @Test
    fun aCorruptNetOfTheRightLengthIsRefusedByTheGateAfterAColdStart() {
        val installed = installReal()
        flipByte(installed, NetStore.NET_SIZE_BYTES / 2)
        assertEquals(NetStore.NET_SIZE_BYTES, installed.length())

        val cold = store()
        assertNotNull("size alone still looks installed (cheap check, Setup vs Home)", cold.activeNetOrNull())
        assertNull("the engine gate hashes the whole file and must refuse it", cold.verifiedNetOrNull())
    }

    @Test
    fun aVerifiedNetIsNotRewrittenAndAChangedOneIsHashedAgain() {
        val s = store()
        val installed = installReal(s)
        val stamp = installed.lastModified()
        Thread.sleep(1100) // file mtimes may have one-second resolution

        assertEquals(installed, s.verifiedNetOrNull())
        assertEquals(installed, store().verifiedNetOrNull())
        assertEquals("verifying must never rewrite the net", stamp, installed.lastModified())

        // Same store, file changed behind its back (new mtime): the cached verdict must not be reused.
        flipByte(installed, 1_000_000)
        assertNull("a net changed after it was verified must be hashed again", s.verifiedNetOrNull())
    }

    @Test
    fun aPartWhoseHeaderTheEngineCannotParseIsRefusedAndNothingIsInstalled() {
        val s = store()
        // Byte 5 is inside the architecture hash (bytes 4..7).
        val part = seedPart(s, flipAt = 5)
        try {
            s.installVerified(part)
            fail("expected the header check to refuse the part")
        } catch (e: IOException) {
            assertTrue("unexpected message: ${e.message}", e.message.orEmpty().contains("header"))
        }
        assertFalse("nothing may reach the engine's path", s.netFile.exists())
        assertNull(s.verifiedNetOrNull())
    }

    @Test
    fun aPartThatSkippedVerificationIsStillRefusedByTheEngineGate() {
        // installVerified trusts its caller for size and hash (ModelDownloader checked them). If a caller
        // ever skipped that, the engine gate must still refuse the file: it hashes independently.
        val s = store()
        val part = seedPart(s, flipAt = 4_000_100)
        val installed = s.installVerified(part)
        assertEquals(NetStore.NET_SIZE_BYTES, installed.length())
        assertNull("a net with the wrong hash must never be handed to Stockfish", store().verifiedNetOrNull())
    }

    @Test
    fun aMissingPartIsAnErrorAndNotANet() {
        val s = store()
        try {
            s.installVerified(s.partFileFor())
            fail("expected an IOException for a missing part")
        } catch (e: IOException) {
            // expected
        }
        assertFalse(s.netFile.exists())
    }

    @Test
    fun cancelDeletesTheLeftoverPartAndKeepsTheInstalledNet() {
        val s = store()
        val installed = installReal(s)
        val stale = s.partFileFor().apply { writeBytes(ByteArray(1_000_000) { 3 }) }
        val other = s.partFileFor("nn-000000000000.nnue").apply { writeBytes(ByteArray(10)) }

        s.deleteParts()

        assertFalse(stale.exists())
        assertFalse(other.exists())
        assertEquals("the installed net must survive a Cancel", installed, store().verifiedNetOrNull())
    }

    @Test
    fun anUpdateFromABundledBuildMovesItsNetIntoNetsAndTheGateAcceptsIt() {
        // What versionCode 1 (R7/D1/F1) left on the phone: the net at filesDir/<name>, plus stale copies.
        val s = store()
        val legacy = File(dir, name)
        seedPart(s).renameTo(legacy)
        File(dir, "nn-ffffffffffff.nnue").writeBytes(ByteArray(100))
        File(dir, "$name.part").writeBytes(ByteArray(100))

        val m = s.migrateLegacy()

        assertEquals(listOf(name), m.moved)
        assertEquals(setOf("nn-ffffffffffff.nnue", "$name.part"), m.deleted.toSet())
        assertFalse(legacy.exists())
        assertEquals(s.netFile, store().verifiedNetOrNull())
        assertFalse("a second start does nothing", store().migrateLegacy().didSomething)
    }
}
