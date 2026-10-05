package net.palaya.chessanalyzer.engine

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * [BundledNetProvider] against the real 98.5 MB net in the test APK's assets, in scratch
 * directories (never the shared filesDir the engine tests use). Every test would fail loudly if the
 * asset were missing, compressed or the wrong size; none of them can skip.
 */
@RunWith(AndroidJUnit4::class)
class BundledNetProviderInstrumentedTest {

    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var dir: File

    private val name = BundledNetProvider.NET_FILENAME
    private val prefix = Regex("""nn-([0-9a-f]{12})\.nnue""").find(name)!!.groupValues[1]

    @Before
    fun setUp() {
        dir = File(context.filesDir, "bundled-net-test-${System.nanoTime()}").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun provider() = BundledNetProvider(dir, context.assets)

    private fun sha256Hex(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(1 shl 20)
            while (true) {
                val n = input.read(buf)
                if (n == -1) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun assertVerifiedNet(file: File) {
        assertEquals(BundledNetProvider.NET_SIZE_BYTES, file.length())
        assertTrue("net hash must start with the prefix in its own name", sha256Hex(file).startsWith(prefix))
    }

    @Test
    fun theNetAssetIsStoredUncompressedAndHasThePinnedSize() {
        // openFd() throws FileNotFoundException for a deflated asset, so this proves "Stored".
        context.assets.openFd(BundledNetProvider.ASSET_PATH).use {
            assertEquals(BundledNetProvider.NET_SIZE_BYTES, it.length)
        }
    }

    @Test
    fun freshDirectoryGetsTheVerifiedNetWithMonotonicProgress() = runBlocking {
        val progress = mutableListOf<Float>()
        val file = provider().ensureNet { progress += it }

        assertEquals(File(dir, name), file)
        assertVerifiedNet(file)
        assertTrue("progress was never reported", progress.size > 2)
        assertEquals("progress must end at exactly 1", 1f, progress.last(), 0f)
        assertEquals("progress must be monotonic", progress.sorted(), progress)
        assertTrue("no .part may remain", dir.listFiles().orEmpty().none { it.name.endsWith(".part") })
    }

    @Test
    fun aTruncatedNetIsReplaced() = runBlocking {
        File(dir, name).writeBytes(ByteArray(4096) { 7 })
        val file = provider().ensureNet()
        assertVerifiedNet(file)
    }

    @Test
    fun aCorruptNetOfTheRightLengthIsReplaced() = runBlocking {
        val first = provider().ensureNet()
        // Flip one byte in the middle: same length, wrong hash. A new provider (a cold start) must notice.
        java.io.RandomAccessFile(first, "rw").use { raf ->
            raf.seek(BundledNetProvider.NET_SIZE_BYTES / 2)
            val b = raf.read()
            raf.seek(BundledNetProvider.NET_SIZE_BYTES / 2)
            raf.write(b xor 0xFF)
        }
        assertEquals(BundledNetProvider.NET_SIZE_BYTES, first.length())
        assertFalse("the corruption must be real", sha256Hex(first).startsWith(prefix))

        val repaired = provider().ensureNet()
        assertVerifiedNet(repaired)
    }

    @Test
    fun aLeftoverPartFileIsDiscarded() = runBlocking {
        val part = File(dir, "$name${BundledNetProvider.PART_SUFFIX}")
        part.writeBytes(ByteArray(1_000_000) { 3 })
        val file = provider().ensureNet()
        assertVerifiedNet(file)
        assertFalse("the stale .part must be gone", part.exists())
    }

    @Test
    fun aSecondCallDoesNotRewriteTheFile() = runBlocking {
        val p = provider()
        val first = p.ensureNet()
        val stamp = first.lastModified()
        Thread.sleep(1100) // file mtimes may have one-second resolution

        val progress = mutableListOf<Float>()
        val again = p.ensureNet { progress += it }
        assertEquals(stamp, again.lastModified())
        assertEquals("an installed net reports exactly one progress value, 1", listOf(1f), progress)

        // And a brand-new provider (a cold start) verifies it by hash but still must not rewrite it.
        val cold = provider().ensureNet()
        assertEquals(stamp, cold.lastModified())
    }

    @Test
    fun notEnoughSpaceFailsBeforeAnythingIsWritten() = runBlocking {
        val tight = BundledNetProvider(
            filesDir = dir,
            openAsset = { context.assets.open(BundledNetProvider.ASSET_PATH) },
            usableSpace = { BundledNetProvider.NET_SIZE_BYTES - 1 },
        )
        try {
            tight.ensureNet()
            fail("expected InsufficientNetStorageException")
        } catch (e: InsufficientNetStorageException) {
            assertEquals(BundledNetProvider.NET_SIZE_BYTES, e.neededBytes)
        }
        assertTrue("nothing may be written when space is short", dir.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun aDamagedBundledAssetIsReportedAndLeavesNothingBehind() = runBlocking {
        // Same length as the real net, one byte different.
        val flipping = BundledNetProvider(
            filesDir = dir,
            openAsset = { path ->
                object : FilterInputStream(context.assets.open(path)) {
                    private var pos = 0L
                    override fun read(b: ByteArray, off: Int, len: Int): Int {
                        val n = super.read(b, off, len)
                        if (n > 0 && pos <= 1000 && 1000 < pos + n) { val i = off + (1000 - pos).toInt(); b[i] = (b[i].toInt() xor 0x55).toByte() }
                        if (n > 0) pos += n
                        return n
                    }
                    override fun read(): Int = throw UnsupportedOperationException()
                }
            },
            usableSpace = { Long.MAX_VALUE },
        )
        try {
            flipping.ensureNet()
            fail("expected BundledNetDamagedException")
        } catch (e: BundledNetDamagedException) {
            // expected
        }
        assertTrue("a damaged copy must leave no net and no .part", dir.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun aShortBundledAssetIsReportedAsDamaged() = runBlocking {
        val short = BundledNetProvider(
            filesDir = dir,
            openAsset = { path ->
                object : FilterInputStream(context.assets.open(path)) {
                    private var left = 5_000_000L
                    override fun read(b: ByteArray, off: Int, len: Int): Int {
                        if (left <= 0) return -1
                        val n = super.read(b, off, minOf(len.toLong(), left).toInt())
                        if (n > 0) left -= n
                        return n
                    }
                    override fun read(): Int = throw UnsupportedOperationException()
                }
            },
            usableSpace = { Long.MAX_VALUE },
        )
        try {
            short.ensureNet()
            fail("expected BundledNetDamagedException")
        } catch (e: BundledNetDamagedException) {
            // expected
        }
        assertTrue(dir.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun aFailureWhileCopyingLeavesNoPartialNet() = runBlocking {
        val failing = BundledNetProvider(
            filesDir = dir,
            openAsset = { path ->
                object : FilterInputStream(context.assets.open(path)) {
                    private var total = 0L
                    override fun read(b: ByteArray, off: Int, len: Int): Int {
                        if (total > 10_000_000) throw IOException("simulated read failure")
                        val n = super.read(b, off, len)
                        if (n > 0) total += n
                        return n
                    }
                    override fun read(): Int = throw UnsupportedOperationException()
                }
            },
            usableSpace = { Long.MAX_VALUE },
        )
        try {
            failing.ensureNet()
            fail("expected IOException")
        } catch (e: IOException) {
            // expected
        }
        assertTrue("an interrupted copy must leave nothing at the final path", dir.listFiles().orEmpty().isEmpty())
        // And the next call simply starts over and succeeds.
        assertVerifiedNet(provider().ensureNet())
    }

    @Test
    fun cancellingMidCopyLeavesNoPartialNet() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val job = async(Dispatchers.Default) {
            provider().ensureNet { fraction -> if (fraction > 0.05f) started.complete(Unit) }
        }
        started.await()
        job.cancel()
        try {
            job.await()
            fail("expected cancellation")
        } catch (e: CancellationException) {
            // expected
        }
        assertTrue("a cancelled copy must leave nothing behind", dir.listFiles().orEmpty().isEmpty())
    }
}
