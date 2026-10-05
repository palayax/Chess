package net.palaya.chessanalyzer.data

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.runBlocking
import net.palaya.chessanalyzer.engine.BundledNetProvider
import net.palaya.chessanalyzer.video.BundledVoiceInstaller
import net.palaya.chessanalyzer.video.GeneratedBundledVoiceConstants
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * [FirstRunSetup] end to end against the real bundled net and voice, in a scratch directory (never
 * the app's own filesDir, so these tests control exactly what is already installed). Covers the
 * four outcomes of the design: progress that is monotonic and reaches 1, a second call that does
 * nothing, [SetupResult.InsufficientStorage] from an injected free-space reading, and
 * [SetupResult.Damaged] from a bundled voice that does not match its pinned hash.
 */
@RunWith(AndroidJUnit4::class)
class FirstRunSetupInstrumentedTest {

    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var dir: File

    private val netFile get() = File(dir, BundledNetProvider.NET_FILENAME)
    private val voiceMarker get() = File(dir, "${BundledVoiceInstaller.ROOT_DIR_NAME}/${BundledVoiceInstaller.MODEL_DIR_NAME}/${BundledVoiceInstaller.MARKER_NAME}")

    @Before
    fun setUp() {
        dir = File(context.filesDir, "first-run-test-${System.nanoTime()}").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun setup(free: Long = Long.MAX_VALUE, voice: BundledVoiceInstaller = BundledVoiceInstaller(dir, context.assets)) =
        FirstRunSetup(EngineController(dir, context.assets), voice) { free }

    @Test
    fun aFreshInstallSetsEverythingUpWithMonotonicProgressEndingAtOne() = runBlocking {
        val s = setup()
        assertTrue("a fresh directory needs setup", s.needsSetup())

        val progress = mutableListOf<Float>()
        val result = s.ensure { progress += it }

        assertEquals(SetupResult.Done, result)
        assertEquals(BundledNetProvider.NET_SIZE_BYTES, netFile.length())
        assertEquals(GeneratedBundledVoiceConstants.TAR_SHA256, voiceMarker.readText().trim())
        assertFalse("nothing is left to set up", s.needsSetup())

        assertTrue("progress was barely reported: ${progress.size} values", progress.size > 10)
        assertEquals("starts at 0", 0f, progress.first(), 0f)
        assertEquals("ends at exactly 1", 1f, progress.last(), 0f)
        assertEquals("monotonic", progress.sorted(), progress)
        // The net is 98.5 of 256.7 MB, so the bar must pass about 38% only once the net is done.
        val netShare = BundledNetProvider.NET_SIZE_BYTES.toFloat() /
            (BundledNetProvider.NET_SIZE_BYTES + GeneratedBundledVoiceConstants.TAR_SIZE_BYTES)
        assertTrue("progress must be byte-weighted across the net then the voice", progress.any { it in (netShare - 0.02f)..(netShare + 0.05f) })
    }

    @Test
    fun aSecondCallIsANoOpThatReportsNothing() = runBlocking {
        val s = setup()
        assertEquals(SetupResult.Done, s.ensure())
        val netStamp = netFile.lastModified()
        val markerStamp = voiceMarker.lastModified()
        Thread.sleep(1100)

        val progress = mutableListOf<Float>()
        assertEquals(SetupResult.Done, s.ensure { progress += it })

        assertTrue("a no-op must not report progress (the screen would flash 'Setting up')", progress.isEmpty())
        assertEquals(netStamp, netFile.lastModified())
        assertEquals(markerStamp, voiceMarker.lastModified())
        // Also true for a brand-new FirstRunSetup over the same directory (a cold start).
        assertFalse(setup().needsSetup())
        val coldProgress = mutableListOf<Float>()
        assertEquals(SetupResult.Done, setup().ensure { coldProgress += it })
        assertTrue(coldProgress.isEmpty())
    }

    @Test
    fun tooLittleFreeSpaceIsInsufficientStorageAndWritesNothing() = runBlocking {
        val needed = BundledNetProvider.NET_SIZE_BYTES + GeneratedBundledVoiceConstants.TAR_SIZE_BYTES +
            FirstRunSetup.SAFETY_MARGIN_BYTES

        val result = setup(free = needed - 1).ensure { error("no progress may be reported when space is short") }

        assertEquals(SetupResult.InsufficientStorage(needed), result)
        assertTrue("nothing may be written when space is short", dir.listFiles().orEmpty().isEmpty())

        // Exactly enough is enough.
        assertEquals(SetupResult.Done, setup(free = needed).ensure())
    }

    @Test
    fun onlyTheMissingPartCountsTowardTheSpaceNeeded() = runBlocking {
        // Install everything, then remove just the voice: only the voice (plus the margin) is needed.
        assertEquals(SetupResult.Done, setup().ensure())
        File(dir, BundledVoiceInstaller.ROOT_DIR_NAME).deleteRecursively()
        val needed = GeneratedBundledVoiceConstants.TAR_SIZE_BYTES + FirstRunSetup.SAFETY_MARGIN_BYTES

        assertEquals(SetupResult.InsufficientStorage(needed), setup(free = needed - 1).ensure())

        val progress = mutableListOf<Float>()
        assertEquals(SetupResult.Done, setup(free = needed).ensure { progress += it })
        assertEquals(1f, progress.last(), 0f)
        assertEquals("monotonic", progress.sorted(), progress)
        assertEquals("starts at 0: the already-installed net is not counted", 0f, progress.first(), 0f)
        assertTrue(voiceMarker.isFile)
    }

    @Test
    fun aBundledVoiceThatDoesNotMatchItsPinnedHashIsDamaged() = runBlocking {
        val wrongPin = BundledVoiceInstaller(
            filesDir = dir,
            openAsset = { path -> context.assets.open(path) },
            usableSpace = { Long.MAX_VALUE },
            pinnedSha256 = "0".repeat(64),
            pinnedSizeBytes = GeneratedBundledVoiceConstants.TAR_SIZE_BYTES,
            assetPath = GeneratedBundledVoiceConstants.ASSET_PATH,
        )

        val result = setup(voice = wrongPin).ensure()

        assertTrue("expected Damaged, got $result", result is SetupResult.Damaged)
        assertTrue((result as SetupResult.Damaged).detail.contains("SHA-256"))
        assertFalse("a damaged voice must never count as installed", wrongPin.isInstalled())
    }

    @Test
    fun outOfSpaceIOExceptionsAreRecognisedAnywhereInTheCauseChain() {
        assertTrue(FirstRunSetup.isOutOfSpace(java.io.IOException("write failed: ENOSPC (No space left on device)")))
        assertTrue(FirstRunSetup.isOutOfSpace(java.io.IOException("outer", java.io.IOException("No space left on device"))))
        assertFalse(FirstRunSetup.isOutOfSpace(java.io.IOException("Connection reset")))
    }
}
