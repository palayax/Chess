package net.palaya.chessanalyzer.video

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * [BundledVoiceInstaller] against the real ~158 MB Kokoro tar bundled in the APK (fresh scratch
 * directories each time), and against small synthetic tars for the paths a real asset cannot
 * reach: a zip-slip entry, a tampered hash, a short archive, a full disk. Nothing skips itself.
 */
@RunWith(AndroidJUnit4::class)
class BundledVoiceInstallerInstrumentedTest {

    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var dir: File

    @Before
    fun setUp() {
        dir = File(context.filesDir, "voice-installer-test-${System.nanoTime()}").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun real() = BundledVoiceInstaller(dir, context.assets)

    private fun root() = File(dir, BundledVoiceInstaller.ROOT_DIR_NAME)

    private fun sha256(bytes: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /** A tar with a top-level directory like the upstream release. `null` data means a directory entry. */
    private fun tar(entries: List<Pair<String, ByteArray?>>): ByteArray {
        val out = ByteArrayOutputStream()
        TarArchiveOutputStream(out).use { t ->
            for ((name, data) in entries) {
                val e = TarArchiveEntry(if (data == null) "$name/" else name)
                if (data != null) e.size = data.size.toLong()
                t.putArchiveEntry(e)
                if (data != null) t.write(data)
                t.closeArchiveEntry()
            }
            t.finish()
        }
        return out.toByteArray()
    }

    private fun validEntries(extra: List<Pair<String, ByteArray?>> = emptyList()): List<Pair<String, ByteArray?>> = listOf(
        "kokoro-fake" to null,
        "kokoro-fake/model.int8.onnx" to ByteArray(20_000) { 1 },
        "kokoro-fake/voices.bin" to ByteArray(5_000) { 2 },
        "kokoro-fake/tokens.txt" to "a 1\nb 2\n".toByteArray(),
        "kokoro-fake/espeak-ng-data" to null,
        "kokoro-fake/espeak-ng-data/en_dict" to ByteArray(3_000) { 3 },
    ) + extra

    private fun synthetic(
        bytes: ByteArray,
        pinnedSha: String = sha256(bytes),
        pinnedSize: Long = bytes.size.toLong(),
        usable: Long = Long.MAX_VALUE,
    ) = BundledVoiceInstaller(
        filesDir = dir,
        openAsset = { ByteArrayInputStream(bytes) },
        usableSpace = { usable },
        pinnedSha256 = pinnedSha,
        pinnedSizeBytes = pinnedSize,
        assetPath = "tts/fake.tar",
    )

    // ---------------------------------------------------------------- the real asset

    @Test
    fun theVoiceAssetIsStoredUncompressedAndHasThePinnedSize() {
        // openFd() throws FileNotFoundException for a deflated asset, so this proves "Stored".
        context.assets.openFd(GeneratedBundledVoiceConstants.ASSET_PATH).use {
            assertEquals(GeneratedBundledVoiceConstants.TAR_SIZE_BYTES, it.length)
        }
    }

    @Test
    fun freshInstallFromTheApkExtractsAVerifiedModelWithMonotonicProgress() = runBlocking {
        val installer = real()
        assertFalse("nothing is installed in a fresh directory", installer.isInstalled())

        val progress = mutableListOf<Float>()
        val modelDir = installer.ensureInstalled { progress += it }

        assertEquals(File(root(), BundledVoiceInstaller.MODEL_DIR_NAME), modelDir)
        assertTrue(installer.isInstalled())
        for (f in BundledVoiceInstaller.REQUIRED_FILES) assertTrue("missing $f", File(modelDir, f).isFile)
        assertTrue(File(modelDir, NeuralTtsProvider.ESPEAK_DATA_DIR).isDirectory)
        assertEquals(
            "the marker must hold this build's pinned tar hash",
            GeneratedBundledVoiceConstants.TAR_SHA256,
            File(modelDir, BundledVoiceInstaller.MARKER_NAME).readText().trim(),
        )
        val bytes = modelDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        assertTrue("an extracted Kokoro model is ~158 MB, was $bytes", bytes > 150_000_000L)
        val model = File(modelDir, BundledVoiceInstaller.KOKORO_MODEL_FILE).length()
        assertEquals("model.int8.onnx is 134,186,977 bytes in the pinned release", 134_186_977L, model)
        assertFalse("the scratch directory must be gone", File(root(), BundledVoiceInstaller.SCRATCH_DIR_NAME).exists())

        assertTrue("progress was never reported", progress.size > 3)
        assertEquals("progress must end at exactly 1", 1f, progress.last(), 0f)
        assertEquals("progress must be monotonic", progress.sorted(), progress)
    }

    @Test
    fun aSecondCallIsANoOp() = runBlocking {
        val installer = real()
        val modelDir = installer.ensureInstalled()
        val marker = File(modelDir, BundledVoiceInstaller.MARKER_NAME)
        val stamp = marker.lastModified()
        val modelStamp = File(modelDir, BundledVoiceInstaller.KOKORO_MODEL_FILE).lastModified()
        Thread.sleep(1100)

        val progress = mutableListOf<Float>()
        installer.ensureInstalled { progress += it }

        assertEquals(listOf(1f), progress)
        assertEquals(stamp, marker.lastModified())
        assertEquals(modelStamp, File(modelDir, BundledVoiceInstaller.KOKORO_MODEL_FILE).lastModified())
        // A cold start (a new installer instance) must also recognise the install without rewriting.
        assertTrue(real().isInstalled())
    }

    @Test
    fun aMarkerFromAnOlderBuildTriggersAReExtraction() = runBlocking {
        val installer = real()
        val modelDir = installer.ensureInstalled()
        val marker = File(modelDir, BundledVoiceInstaller.MARKER_NAME)
        // What the previous (downloading) build wrote: the hash of the .tar.bz2 archive.
        marker.writeText("c9f0dd393615805b0bab050c340834d5e684e732aec91c0e860cd30e982c08bd")
        assertFalse("a stale marker must not count as installed", real().isInstalled())

        val progress = mutableListOf<Float>()
        real().ensureInstalled { progress += it }

        assertTrue("it must have extracted again, not returned at once", progress.size > 3)
        assertEquals(GeneratedBundledVoiceConstants.TAR_SHA256, marker.readText().trim())
        assertTrue(real().isInstalled())
    }

    @Test
    fun aMissingModelFileTriggersARepair() = runBlocking {
        val installer = real()
        val modelDir = installer.ensureInstalled()
        assertTrue(File(modelDir, BundledVoiceInstaller.KOKORO_VOICES_FILE).delete())
        assertFalse(real().isInstalled())

        real().ensureInstalled()
        assertTrue(File(modelDir, BundledVoiceInstaller.KOKORO_VOICES_FILE).isFile)
        assertTrue(real().isInstalled())
    }

    @Test
    fun aScratchDirectoryLeftByAKilledRunIsDiscarded() = runBlocking {
        val scratch = File(root(), BundledVoiceInstaller.SCRATCH_DIR_NAME).apply { mkdirs() }
        File(scratch, "half-written.bin").writeBytes(ByteArray(1_000_000))

        real().ensureInstalled()

        assertFalse("the stale scratch directory must be removed", scratch.exists())
        assertTrue(real().isInstalled())
    }

    @Test
    fun cancellingMidExtractionLeavesNeitherScratchNorModel() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val job = async(Dispatchers.Default) {
            real().ensureInstalled { fraction -> if (fraction > 0.1f) started.complete(Unit) }
        }
        started.await()
        job.cancel()
        try {
            job.await()
            fail("expected cancellation")
        } catch (e: CancellationException) {
            // expected
        }
        assertFalse(File(root(), BundledVoiceInstaller.SCRATCH_DIR_NAME).exists())
        assertFalse("a cancelled install must not look installed", real().isInstalled())
        assertFalse(File(root(), BundledVoiceInstaller.MODEL_DIR_NAME).exists())
    }

    // ---------------------------------------------------------------- synthetic archives

    @Test
    fun anArchiveWithAllRequiredFilesInstallsAndStripsTheTopLevelDirectory() = runBlocking {
        val bytes = tar(validEntries())
        val installer = synthetic(bytes)

        val modelDir = installer.ensureInstalled()

        assertTrue(File(modelDir, "model.int8.onnx").isFile)
        assertTrue(File(modelDir, "espeak-ng-data/en_dict").isFile)
        assertFalse("the top-level directory must be stripped", File(modelDir, "kokoro-fake").exists())
        assertEquals(sha256(bytes), File(modelDir, BundledVoiceInstaller.MARKER_NAME).readText().trim())
        assertTrue(installer.isInstalled())
    }

    @Test
    fun anEntryThatEscapesTheDestinationIsRefusedAndNothingIsWrittenOutside() = runBlocking {
        val bytes = tar(validEntries(listOf("kokoro-fake/../../escaped.txt" to "pwned".toByteArray())))
        val installer = synthetic(bytes)
        try {
            installer.ensureInstalled()
            fail("expected the zip-slip guard to refuse the archive")
        } catch (e: IOException) {
            assertTrue("unexpected message: ${e.message}", e.message.orEmpty().contains("outside destination"))
        }
        // kokoro.extracting/../../escaped.txt would be directly under filesDir's parent of the test dir.
        assertFalse(File(dir, "escaped.txt").exists())
        assertFalse(File(dir.parentFile, "escaped.txt").exists())
        assertFalse(File(root(), "escaped.txt").exists())
        assertFalse("nothing may be installed", installer.isInstalled())
        assertFalse(File(root(), BundledVoiceInstaller.SCRATCH_DIR_NAME).exists())
        assertFalse(File(root(), BundledVoiceInstaller.MODEL_DIR_NAME).exists())
    }

    @Test
    fun aTamperedArchiveFailsVerificationAndNeverBecomesTheInstalledModel() = runBlocking {
        val bytes = tar(validEntries())
        val installer = synthetic(bytes, pinnedSha = sha256(bytes.copyOf().also { it[100] = (it[100] + 1).toByte() }))
        try {
            installer.ensureInstalled()
            fail("expected BundledVoiceDamagedException")
        } catch (e: BundledVoiceDamagedException) {
            assertTrue(e.message.orEmpty().contains("SHA-256"))
        }
        assertFalse(installer.isInstalled())
        assertFalse(File(root(), BundledVoiceInstaller.MODEL_DIR_NAME).exists())
        assertFalse(File(root(), BundledVoiceInstaller.SCRATCH_DIR_NAME).exists())
    }

    @Test
    fun aShortArchiveIsReportedAsDamaged() = runBlocking {
        val bytes = tar(validEntries())
        val installer = synthetic(bytes, pinnedSize = bytes.size + 4096L)
        try {
            installer.ensureInstalled()
            fail("expected BundledVoiceDamagedException")
        } catch (e: BundledVoiceDamagedException) {
            assertTrue(e.message.orEmpty().contains("bytes"))
        }
        assertFalse(installer.isInstalled())
        assertFalse(File(root(), BundledVoiceInstaller.SCRATCH_DIR_NAME).exists())
    }

    @Test
    fun anArchiveMissingARequiredFileIsReportedAsDamaged() = runBlocking {
        val bytes = tar(listOf("kokoro-fake" to null, "kokoro-fake/model.int8.onnx" to ByteArray(100)))
        val installer = synthetic(bytes)
        try {
            installer.ensureInstalled()
            fail("expected BundledVoiceDamagedException")
        } catch (e: BundledVoiceDamagedException) {
            assertTrue(e.message.orEmpty().contains("missing"))
        }
        assertFalse(installer.isInstalled())
    }

    @Test
    fun notEnoughSpaceFailsBeforeAnythingIsExtracted() = runBlocking {
        val bytes = tar(validEntries())
        val installer = synthetic(bytes, usable = bytes.size - 1L)
        try {
            installer.ensureInstalled()
            fail("expected InsufficientVoiceStorageException")
        } catch (e: InsufficientVoiceStorageException) {
            assertEquals(bytes.size.toLong(), e.neededBytes)
        }
        assertFalse(File(root(), BundledVoiceInstaller.SCRATCH_DIR_NAME).exists())
        assertFalse(File(root(), BundledVoiceInstaller.MODEL_DIR_NAME).exists())
    }
}
