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
import net.palaya.chessanalyzer.TestApp
import net.palaya.chessanalyzer.data.models.GeneratedModelPins
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
 * [VoiceStore] on a device (D2d; ported from the bundled builds' `BundledVoiceInstallerInstrumentedTest`).
 * The real ~158 MB Kokoro tar comes from the test APK's seed asset, never the app APK; it is installed
 * through [VoiceStore.installFromTar] (the download's tail: a verified `.part` unpacked, then deleted)
 * and [VoiceStore.installFromStream] (the seeding tail). Small synthetic tars cover what the real file
 * cannot reach: a zip-slip entry, a tampered hash, a short archive, missing files, a full disk.
 *
 * Fresh scratch directories every time, never the app's own filesDir (the other tests' installed voice
 * is untouched). Nothing skips itself.
 */
@RunWith(AndroidJUnit4::class)
class VoiceStoreInstrumentedTest {

    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var dir: File

    @Before
    fun setUp() {
        dir = File(context.filesDir, "voice-store-test-${System.nanoTime()}").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    /** A store with this build's pins over the scratch directory, as the app builds it. */
    private fun real(free: Long = Long.MAX_VALUE) = VoiceStore(
        filesDir = dir,
        usableSpace = { free },
        pinnedSha256 = GeneratedModelPins.VOICE_SHA256,
        pinnedSizeBytes = GeneratedModelPins.VOICE_SIZE_BYTES,
    )

    private fun root() = File(dir, VoiceStore.ROOT_DIR_NAME)

    private suspend fun installRealFromSeed(store: VoiceStore = real(), onProgress: (Float) -> Unit = {}): File =
        store.installFromStream(
            open = { TestApp.openSeed(TestApp.voiceSeedPath) },
            expectedSha256 = GeneratedModelPins.VOICE_SHA256,
            expectedSizeBytes = GeneratedModelPins.VOICE_SIZE_BYTES,
            onProgress = onProgress,
        )

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

    /** A store pinned to a synthetic [bytes] archive. */
    private fun synthetic(bytes: ByteArray, usable: Long = Long.MAX_VALUE) =
        VoiceStore(dir, usableSpace = { usable }, pinnedSha256 = sha256(bytes), pinnedSizeBytes = bytes.size.toLong())

    private suspend fun VoiceStore.installBytes(
        bytes: ByteArray,
        sha: String = sha256(bytes),
        size: Long = bytes.size.toLong(),
    ): File = installFromStream({ ByteArrayInputStream(bytes) }, sha, size)

    // ---------------------------------------------------------------- the real seed

    @Test
    fun theVoiceSeedIsStoredUncompressedAndIsTheDownloadsPinnedTarGz() {
        // openFd() throws FileNotFoundException for a deflated asset, so this proves "stored". Since D2f the
        // seed is the file setup downloads: the .tar.gz, with its own pins.
        assertTrue(TestApp.voiceSeedPath.endsWith(".tar.gz.seed"))
        TestApp.testContext.assets.openFd(TestApp.voiceSeedPath).use {
            assertEquals(GeneratedModelPins.VOICE_DOWNLOAD_SIZE_BYTES, it.length)
        }
        val digest = MessageDigest.getInstance("SHA-256")
        TestApp.openSeed(TestApp.voiceSeedPath).use { input ->
            val buf = ByteArray(1 shl 20)
            while (true) {
                val n = input.read(buf)
                if (n == -1) break
                digest.update(buf, 0, n)
            }
        }
        assertEquals(GeneratedModelPins.VOICE_DOWNLOAD_SHA256, digest.digest().joinToString("") { "%02x".format(it) })
    }

    @Test
    fun aVerifiedTarPartIsUnpackedIntoAWorkingModelWithMonotonicProgressAndDeleted() = runBlocking {
        val store = real()
        assertFalse("nothing is installed in a fresh directory", store.isInstalled())
        // Where the download leaves the verified voice.
        val part = store.partFile
        part.parentFile!!.mkdirs()
        TestApp.openSeed(TestApp.voiceSeedPath).use { input -> part.outputStream().use { input.copyTo(it, 1 shl 20) } }
        assertEquals("the part is the downloaded .tar.gz", GeneratedModelPins.VOICE_DOWNLOAD_SIZE_BYTES, part.length())

        val progress = mutableListOf<Float>()
        val started = System.nanoTime()
        // Inflated as a stream while it is unpacked, and checked against the TAR's pins (D2f).
        val modelDir = store.installFromTar(part) { progress += it }
        android.util.Log.i("VoiceStoreTest", "D2f: the .tar.gz part was inflated and unpacked in ${(System.nanoTime() - started) / 1_000_000} ms")

        assertEquals(File(root(), VoiceStore.MODEL_DIR_NAME), modelDir)
        assertTrue(store.isInstalled())
        assertFalse("the tar part is deleted once unpacked", part.exists())
        for (f in VoiceStore.REQUIRED_FILES) assertTrue("missing $f", File(modelDir, f).isFile)
        assertTrue(File(modelDir, NeuralTtsProvider.ESPEAK_DATA_DIR).isDirectory)
        assertEquals(
            "the marker must hold this build's pinned tar hash",
            GeneratedModelPins.VOICE_SHA256,
            File(modelDir, VoiceStore.MARKER_NAME).readText().trim(),
        )
        assertEquals(GeneratedModelPins.VOICE_SHA256.take(12), store.installedVersionId())
        val bytes = modelDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        assertTrue("an unpacked Kokoro model is ~158 MB, was $bytes", bytes > 150_000_000L)
        assertEquals("model.int8.onnx is 134,186,977 bytes in the pinned release", 134_186_977L, File(modelDir, VoiceStore.KOKORO_MODEL_FILE).length())
        assertFalse("the scratch directory must be gone", File(root(), VoiceStore.SCRATCH_DIR_NAME).exists())

        assertTrue("progress was never reported", progress.size > 3)
        assertEquals("progress must end at exactly 1", 1f, progress.last(), 0f)
        assertEquals("progress must be monotonic", progress.sorted(), progress)
    }

    @Test
    fun anInstalledVoiceIsRecognisedOnAColdStartWithoutRewriting() = runBlocking {
        val modelDir = installRealFromSeed()
        val marker = File(modelDir, VoiceStore.MARKER_NAME)
        val stamp = marker.lastModified()
        val modelStamp = File(modelDir, VoiceStore.KOKORO_MODEL_FILE).lastModified()
        Thread.sleep(1100)

        assertTrue("a new store over the same directory (a cold start) sees the voice", real().isInstalled())
        assertEquals(stamp, marker.lastModified())
        assertEquals(modelStamp, File(modelDir, VoiceStore.KOKORO_MODEL_FILE).lastModified())
    }

    @Test
    fun aMarkerFromAnotherBuildIsNotInstalledAndAReinstallFixesIt() = runBlocking {
        val modelDir = installRealFromSeed()
        val marker = File(modelDir, VoiceStore.MARKER_NAME)
        // What an older downloading build wrote: the hash of the .tar.bz2 archive.
        marker.writeText("c9f0dd393615805b0bab050c340834d5e684e732aec91c0e860cd30e982c08bd")
        assertFalse("a stale marker must not count as installed", real().isInstalled())

        val progress = mutableListOf<Float>()
        installRealFromSeed { progress += it }

        assertTrue("it must have unpacked again, not returned at once", progress.size > 3)
        assertEquals(GeneratedModelPins.VOICE_SHA256, marker.readText().trim())
        assertTrue(real().isInstalled())
    }

    @Test
    fun aMissingModelFileIsNotInstalledAndAReinstallRepairsIt() = runBlocking {
        val modelDir = installRealFromSeed()
        assertTrue(File(modelDir, VoiceStore.KOKORO_VOICES_FILE).delete())
        assertFalse(real().isInstalled())
        assertEquals("a broken install has no version", null, real().installedVersionId())

        installRealFromSeed()
        assertTrue(File(modelDir, VoiceStore.KOKORO_VOICES_FILE).isFile)
        assertTrue(real().isInstalled())
    }

    @Test
    fun aScratchDirectoryLeftByAKilledRunIsDiscarded() = runBlocking {
        val scratch = File(root(), VoiceStore.SCRATCH_DIR_NAME).apply { mkdirs() }
        File(scratch, "half-written.bin").writeBytes(ByteArray(1_000_000))

        installRealFromSeed()

        assertFalse("the stale scratch directory must be removed", scratch.exists())
        assertTrue(real().isInstalled())
    }

    @Test
    fun cancellingMidUnpackLeavesNeitherScratchNorModel() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val job = async(Dispatchers.Default) {
            installRealFromSeed { fraction -> if (fraction > 0.1f) started.complete(Unit) }
        }
        started.await()
        job.cancel()
        try {
            job.await()
            fail("expected cancellation")
        } catch (e: CancellationException) {
            // expected
        }
        assertFalse(File(root(), VoiceStore.SCRATCH_DIR_NAME).exists())
        assertFalse("a cancelled install must not look installed", real().isInstalled())
        assertFalse(File(root(), VoiceStore.MODEL_DIR_NAME).exists())
    }

    @Test
    fun cancelDeletesThePartAndNothingElse() = runBlocking {
        val store = real()
        installRealFromSeed(store)
        store.partFile.writeBytes(ByteArray(500_000))
        store.deletePart()
        assertFalse(store.partFile.exists())
        assertTrue("Cancel must not remove an installed voice", real().isInstalled())
    }

    // ---------------------------------------------------------------- synthetic archives

    @Test
    fun anArchiveWithAllRequiredFilesInstallsAndStripsTheTopLevelDirectory() = runBlocking {
        val bytes = tar(validEntries())
        val store = synthetic(bytes)

        val modelDir = store.installBytes(bytes)

        assertTrue(File(modelDir, "model.int8.onnx").isFile)
        assertTrue(File(modelDir, "espeak-ng-data/en_dict").isFile)
        assertFalse("the top-level directory must be stripped", File(modelDir, "kokoro-fake").exists())
        assertEquals(sha256(bytes), File(modelDir, VoiceStore.MARKER_NAME).readText().trim())
        assertTrue(store.isInstalled())
    }

    @Test
    fun anEntryThatEscapesTheDestinationIsRefusedAndNothingIsWrittenOutside() = runBlocking {
        val bytes = tar(validEntries(listOf("kokoro-fake/../../escaped.txt" to "pwned".toByteArray())))
        val store = synthetic(bytes)
        try {
            store.installBytes(bytes)
            fail("expected the zip-slip guard to refuse the archive")
        } catch (e: IOException) {
            assertTrue("unexpected message: ${e.message}", e.message.orEmpty().contains("outside destination"))
        }
        assertFalse(File(dir, "escaped.txt").exists())
        assertFalse(File(dir.parentFile, "escaped.txt").exists())
        assertFalse(File(root(), "escaped.txt").exists())
        assertFalse("nothing may be installed", store.isInstalled())
        assertFalse(File(root(), VoiceStore.SCRATCH_DIR_NAME).exists())
        assertFalse(File(root(), VoiceStore.MODEL_DIR_NAME).exists())
    }

    @Test
    fun aTamperedArchiveFailsVerificationAndNeverBecomesTheInstalledModel() = runBlocking {
        val bytes = tar(validEntries())
        val store = synthetic(bytes)
        val wrongSha = sha256(bytes.copyOf().also { it[100] = (it[100] + 1).toByte() })
        try {
            store.installBytes(bytes, sha = wrongSha)
            fail("expected VoiceArchiveDamagedException")
        } catch (e: VoiceArchiveDamagedException) {
            assertTrue(e.message.orEmpty().contains("SHA-256"))
        }
        assertFalse(store.isInstalled())
        assertFalse(File(root(), VoiceStore.MODEL_DIR_NAME).exists())
        assertFalse(File(root(), VoiceStore.SCRATCH_DIR_NAME).exists())
    }

    @Test
    fun aDamagedTarPartIsKeptForTheCallerToDecide() = runBlocking {
        // installFromTar deletes the part only after a successful install; ModelSetup deletes a DAMAGED
        // one itself (and keeps a part whose unpack was merely interrupted, so it is not downloaded again).
        val bytes = tar(validEntries())
        val store = synthetic(bytes)
        val part = store.partFile.apply { parentFile!!.mkdirs(); writeBytes(bytes.copyOf().also { it[600] = (it[600] + 1).toByte() }) }
        try {
            store.installFromTar(part)
            fail("expected VoiceArchiveDamagedException")
        } catch (e: VoiceArchiveDamagedException) {
            // expected
        }
        assertTrue(part.exists())
        assertFalse(store.isInstalled())
    }

    @Test
    fun aShortArchiveIsReportedAsDamaged() = runBlocking {
        val bytes = tar(validEntries())
        val store = synthetic(bytes)
        try {
            store.installBytes(bytes, size = bytes.size + 4096L)
            fail("expected VoiceArchiveDamagedException")
        } catch (e: VoiceArchiveDamagedException) {
            assertTrue(e.message.orEmpty().contains("bytes"))
        }
        assertFalse(store.isInstalled())
        assertFalse(File(root(), VoiceStore.SCRATCH_DIR_NAME).exists())
    }

    @Test
    fun anArchiveMissingARequiredFileIsReportedAsDamaged() = runBlocking {
        val bytes = tar(listOf("kokoro-fake" to null, "kokoro-fake/model.int8.onnx" to ByteArray(100)))
        val store = synthetic(bytes)
        try {
            store.installBytes(bytes)
            fail("expected VoiceArchiveDamagedException")
        } catch (e: VoiceArchiveDamagedException) {
            assertTrue(e.message.orEmpty().contains("missing"))
        }
        assertFalse(store.isInstalled())
    }

    @Test
    fun notEnoughSpaceFailsBeforeAnythingIsUnpacked() = runBlocking {
        val bytes = tar(validEntries())
        val store = synthetic(bytes, usable = bytes.size - 1L)
        try {
            store.installBytes(bytes)
            fail("expected InsufficientVoiceStorageException")
        } catch (e: InsufficientVoiceStorageException) {
            assertEquals(bytes.size.toLong(), e.neededBytes)
        }
        assertFalse(File(root(), VoiceStore.SCRATCH_DIR_NAME).exists())
        assertFalse(File(root(), VoiceStore.MODEL_DIR_NAME).exists())
    }
}
