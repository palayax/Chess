package net.palaya.chessanalyzer.video

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import kotlinx.coroutines.runBlocking
import net.palaya.chessanalyzer.data.models.TestModelFiles
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** [VoiceStore] on the host with a small Kokoro-shaped tar ([TestModelFiles.voiceTar]). */
class VoiceStoreTest {

    @get:Rule val tmp = TemporaryFolder()

    private val tar = TestModelFiles.voiceTar()

    private fun store(files: File, archive: ByteArray = tar, free: Long = Long.MAX_VALUE) =
        TestModelFiles.voiceStoreFor(files, archive, free)

    private fun tarFile(dir: File, bytes: ByteArray = tar): File =
        File(dir, "tts_models/${VoiceStore.PART_FILE_NAME}").apply { parentFile!!.mkdirs(); writeBytes(bytes) }

    @Test
    fun installsFromTheVerifiedPartStrippingTheTopDirectoryAndDeletesThePart() = runBlocking {
        val files = tmp.newFolder()
        val s = store(files)
        assertFalse(s.isInstalled())
        assertNull(s.installedVersionId())
        val part = tarFile(files)
        assertEquals(part, s.partFile)

        val progress = ArrayList<Float>()
        val dir = s.installFromTar(part) { progress += it }

        assertEquals(File(files, "tts_models/kokoro"), dir)
        for (name in VoiceStore.REQUIRED_FILES) assertTrue(name, File(dir, name).isFile)
        assertTrue(File(dir, "espeak-ng-data/phontab").isFile)
        assertTrue(s.isInstalled())
        assertEquals(TestModelFiles.sha256(tar).take(12), s.installedVersionId())
        assertEquals(TestModelFiles.sha256(tar), File(dir, VoiceStore.MARKER_NAME).readText())
        assertFalse("the part is deleted once unpacked", part.exists())
        assertFalse(File(files, "tts_models/${VoiceStore.SCRATCH_DIR_NAME}").exists())
        assertEquals(1f, progress.last(), 0f)
        assertEquals(progress.sorted(), progress)
    }

    @Test
    fun aWrongHashInstallsNothing() = runBlocking {
        val files = tmp.newFolder()
        val s = store(files)
        try {
            s.installFromStream({ tar.inputStream() }, "0".repeat(64), tar.size.toLong())
            fail("expected VoiceArchiveDamagedException")
        } catch (e: VoiceArchiveDamagedException) {
            assertTrue(e.message!!.contains("SHA-256"))
        }
        assertFalse(s.isInstalled())
        assertFalse(File(files, "tts_models/kokoro").exists())
        assertFalse(File(files, "tts_models/${VoiceStore.SCRATCH_DIR_NAME}").exists())
    }

    @Test
    fun aWrongSizeInstallsNothing() = runBlocking {
        val files = tmp.newFolder()
        val s = store(files)
        try {
            s.installFromStream({ tar.inputStream() }, s.pinnedSha256, tar.size + 1L)
            fail("expected VoiceArchiveDamagedException")
        } catch (e: VoiceArchiveDamagedException) {
            assertTrue(e.message!!.contains("bytes"))
        }
        assertFalse(s.isInstalled())
    }

    @Test
    fun anArchiveWithoutTheModelFilesIsDamaged() = runBlocking {
        val broken = TestModelFiles.voiceTar(complete = false)
        val files = tmp.newFolder()
        val s = store(files, broken)
        try {
            s.installFromTar(tarFile(files, broken))
            fail("expected VoiceArchiveDamagedException")
        } catch (e: VoiceArchiveDamagedException) {
            assertTrue(e.message!!.contains("missing"))
        }
        assertFalse(s.isInstalled())
    }

    @Test
    fun anEntryThatEscapesTheDirectoryIsRefused() = runBlocking {
        val out = ByteArrayOutputStream()
        TarArchiveOutputStream(out).use { t ->
            val data = "x".toByteArray()
            val e = TarArchiveEntry("top/../../evil.txt", true)
            e.size = data.size.toLong()
            t.putArchiveEntry(e)
            t.write(data)
            t.closeArchiveEntry()
        }
        val evil = out.toByteArray()
        val files = tmp.newFolder()
        val s = store(files, evil)
        try {
            s.installFromStream({ evil.inputStream() }, s.pinnedSha256, evil.size.toLong())
            fail("expected IOException")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("outside"))
        }
        assertFalse(File(files.parentFile, "evil.txt").exists())
        assertFalse(File(files, "evil.txt").exists())
    }

    @Test
    fun tooLittleSpaceIsRefusedBeforeWriting() = runBlocking {
        val files = tmp.newFolder()
        val s = store(files, free = tar.size - 1L)
        try {
            s.installFromStream({ tar.inputStream() }, s.pinnedSha256, tar.size.toLong())
            fail("expected InsufficientVoiceStorageException")
        } catch (e: InsufficientVoiceStorageException) {
            assertEquals(tar.size.toLong(), e.neededBytes)
        }
        assertFalse(File(files, "tts_models/kokoro").exists())
    }

    /**
     * Design §8: a bundled build (versionCode 1) left `tts_models/kokoro/` with a `.provisioned` marker
     * holding the tar's SHA-256. The download build must accept it as installed, byte for byte the same
     * check, and must not accept a marker for another archive.
     */
    @Test
    fun aVoiceInstalledByABundledBuildIsAcceptedAndAStaleOneIsNot() {
        val files = tmp.newFolder()
        val dir = File(files, "tts_models/kokoro").apply { mkdirs() }
        for (name in VoiceStore.REQUIRED_FILES) File(dir, name).writeText("x")
        File(dir, "espeak-ng-data").mkdirs()
        val s = store(files)
        File(dir, VoiceStore.MARKER_NAME).writeText(s.pinnedSha256)
        assertTrue(s.isInstalled())
        File(dir, VoiceStore.MARKER_NAME).writeText("1".repeat(64))
        assertFalse(s.isInstalled())
        assertEquals("1".repeat(12), s.installedVersionId())
    }

    @Test
    fun deletePartRemovesTheDownloadInProgress() {
        val files = tmp.newFolder()
        val s = store(files)
        val part = tarFile(files)
        s.deletePart()
        assertFalse(part.exists())
    }

    // ---- D2e: an update's voice ----

    private val newTar = TestModelFiles.voiceTar(payload = 230_000)
    private val newSha = TestModelFiles.sha256(newTar)

    private fun installedStore(files: File): VoiceStore = store(files).also {
        runBlocking { it.installFromStream({ tar.inputStream() }, it.pinnedSha256, tar.size.toLong()) }
    }

    @Test
    fun anUpdateIsUnpackedBesideTheInstalledVoiceThenSwappedIn() = runBlocking {
        val files = tmp.newFolder()
        val s = installedStore(files)
        val part = s.updatePartFile(newSha).apply { writeBytes(newTar) }
        val scratch = s.extractToScratch(part, newSha)
        assertEquals(s.scratchDir, scratch)
        assertTrue(scratch.resolve(VoiceStore.KOKORO_MODEL_FILE).isFile)
        assertEquals("the installed voice is untouched", s.pinnedSha256, s.installedSha256())

        s.swapInScratch(newSha, VoiceStore.LAYOUT, "1.13.8", "1.13.8")
        assertEquals(newSha, s.installedSha256())
        assertTrue(s.isInstalled())
        assertEquals(newSha.take(12), s.installedVersionId())
        assertTrue(File(s.previousDir, VoiceStore.MARKER_NAME).readText() == s.pinnedSha256)
        s.deletePrevious()
        assertFalse(s.previousDir.exists())
        s.deleteUpdateParts()
        assertFalse(part.exists())
    }

    @Test
    fun aSwapCanBeUndone() = runBlocking {
        val files = tmp.newFolder()
        val s = installedStore(files)
        s.extractToScratch(s.updatePartFile(newSha).apply { writeBytes(newTar) }, newSha)
        s.swapInScratch(newSha, VoiceStore.LAYOUT, "1.13.8", "1.13.8")
        assertTrue(s.restorePrevious())
        assertEquals(s.pinnedSha256, s.installedSha256())
        assertFalse(File(s.modelDir, VoiceStore.COMPAT_NAME).exists())
        assertFalse("nothing to restore twice", s.restorePrevious())
    }

    @Test
    fun anUpdatesVoiceStopsCountingWhenTheRuntimeOrLayoutNoLongerMatch() {
        val files = tmp.newFolder()
        val dir = File(files, "tts_models/kokoro").apply { mkdirs() }
        for (name in VoiceStore.REQUIRED_FILES) File(dir, name).writeText("x")
        File(dir, "espeak-ng-data").mkdirs()
        File(dir, VoiceStore.MARKER_NAME).writeText(newSha)
        fun compat(layout: String, min: String, max: String) =
            File(dir, VoiceStore.COMPAT_NAME).writeText("layout=$layout\nruntimeMin=$min\nruntimeMax=$max\n")
        val s = store(files)
        assertFalse("no compat record: only the pinned hash counts", s.isInstalled())
        compat(VoiceStore.LAYOUT, "1.13.0", "1.13.10")
        assertTrue(s.isInstalled())
        compat(VoiceStore.LAYOUT, "1.14.0", "1.15.0")
        assertFalse("another sherpa-onnx", s.isInstalled())
        compat("kokoro-multi-lang-v1_0", "1.13.8", "1.13.8")
        assertFalse("another layout", s.isInstalled())
    }

    @Test
    fun anArchiveCannotBringItsOwnMarkerOrCompatRecord() = runBlocking {
        val out = ByteArrayOutputStream()
        TarArchiveOutputStream(out).use { t ->
            fun file(name: String, data: ByteArray) {
                val e = TarArchiveEntry(name)
                e.size = data.size.toLong()
                t.putArchiveEntry(e)
                t.write(data)
                t.closeArchiveEntry()
            }
            file("k/${VoiceStore.KOKORO_MODEL_FILE}", ByteArray(10))
            file("k/${VoiceStore.KOKORO_VOICES_FILE}", ByteArray(10))
            file("k/${VoiceStore.KOKORO_TOKENS_FILE}", ByteArray(10))
            file("k/espeak-ng-data/x", ByteArray(10))
            file("k/${VoiceStore.MARKER_NAME}", "f".repeat(64).toByteArray())
            file("k/${VoiceStore.COMPAT_NAME}", "layout=${VoiceStore.LAYOUT}\nruntimeMin=0\nruntimeMax=99\n".toByteArray())
        }
        val evil = out.toByteArray()
        val files = tmp.newFolder()
        val s = store(files)
        val part = File(files, "x.tar").apply { writeBytes(evil) }
        val scratch = s.extractToScratch(part, TestModelFiles.sha256(evil))
        assertFalse(File(scratch, VoiceStore.MARKER_NAME).exists())
        assertFalse(File(scratch, VoiceStore.COMPAT_NAME).exists())
    }

    // ---- D2f: the voice is downloaded as a .tar.gz ----

    @Test
    fun aGzippedPartIsInflatedWhileUnpackingAndCheckedAgainstTheTarPins() = runBlocking {
        val files = tmp.newFolder()
        val gz = TestModelFiles.gzip(tar)
        val s = TestModelFiles.voiceStoreFor(files, tar, download = gz)
        val part = tarFile(files, gz)
        val progress = ArrayList<Float>()
        val dir = s.installFromTar(part) { progress += it }

        for (name in VoiceStore.REQUIRED_FILES) assertTrue(name, File(dir, name).isFile)
        assertTrue(s.isInstalled())
        // The marker is the TAR's hash, as the bundled builds and the plain-tar download wrote it.
        assertEquals(TestModelFiles.sha256(tar), File(dir, VoiceStore.MARKER_NAME).readText())
        assertFalse(part.exists())
        assertEquals(1f, progress.last(), 0f)
        assertEquals(progress.sorted(), progress)
    }

    @Test
    fun aGzipThatInflatesToAnotherTarInstallsNothing() = runBlocking {
        val files = tmp.newFolder()
        val s = store(files)
        val other = TestModelFiles.voiceTar(payload = 150_000)
        try {
            s.installFromTar(tarFile(files, TestModelFiles.gzip(other)), s.pinnedSha256, s.pinnedSizeBytes)
            fail("expected VoiceArchiveDamagedException")
        } catch (e: VoiceArchiveDamagedException) {
            assertTrue(e.message!!, e.message!!.contains("bytes") || e.message!!.contains("SHA-256"))
        }
        assertFalse(s.isInstalled())
        assertFalse(File(files, "tts_models/${VoiceStore.SCRATCH_DIR_NAME}").exists())
    }

    @Test
    fun aGzipThatInflatesPastThePinnedSizeIsStoppedThere() = runBlocking {
        // A small .tar.gz that inflates to far more than the pin (a "gzip bomb": a tiny tar followed by
        // 300 MB of zeros): the unpack stops as soon as the pinned size is passed, long before the end.
        val files = tmp.newFolder()
        val small = TestModelFiles.voiceTar(payload = 1_000)
        val s = store(files, small)
        val out = ByteArrayOutputStream()
        java.util.zip.GZIPOutputStream(out).use { z ->
            z.write(small)
            val zeros = ByteArray(1 shl 20)
            repeat(300) { z.write(zeros) }
        }
        val bomb = out.toByteArray()
        var read = 0L
        try {
            s.installFromStream({ object : java.io.FilterInputStream(bomb.inputStream()) {
                override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { if (it > 0) read += it }
            } }, s.pinnedSha256, s.pinnedSizeBytes)
            fail("expected VoiceArchiveDamagedException")
        } catch (e: VoiceArchiveDamagedException) {
            assertTrue(e.message!!, e.message!!.contains("longer than"))
        }
        assertTrue("stopped early: $read of ${bomb.size} compressed bytes read", read < bomb.size / 2)
        assertFalse(s.isInstalled())
        assertFalse(File(files, "tts_models/${VoiceStore.SCRATCH_DIR_NAME}").exists())
    }

    @Test
    fun aTruncatedGzipIsAnErrorAndInstallsNothing() = runBlocking {
        val files = tmp.newFolder()
        val s = store(files)
        val gz = TestModelFiles.gzip(tar)
        try {
            s.installFromTar(tarFile(files, gz.copyOf(gz.size / 2)))
            fail("expected an IOException")
        } catch (e: IOException) {
            // EOFException from the inflater, or the size check: either way nothing is installed.
        }
        assertFalse(s.isInstalled())
        assertFalse(File(files, "tts_models/${VoiceStore.SCRATCH_DIR_NAME}").exists())
    }

    @Test
    fun anUpdateShippedAsTarGzIsExtractedWithTheTarPinsItCarries() = runBlocking {
        val files = tmp.newFolder()
        val s = store(files)
        val newTar = TestModelFiles.voiceTar(payload = 120_000)
        val gz = TestModelFiles.gzip(newTar)
        val part = s.updatePartFile(TestModelFiles.sha256(gz)).apply { parentFile!!.mkdirs(); writeBytes(gz) }
        val scratch = s.extractToScratch(part, TestModelFiles.sha256(newTar), newTar.size.toLong())
        for (name in VoiceStore.REQUIRED_FILES) assertTrue(name, File(scratch, name).isFile)
        // The file's own length is NOT the tar's size: without the tar size the check refuses it.
        try {
            s.extractToScratch(part, TestModelFiles.sha256(newTar))
            fail("expected VoiceArchiveDamagedException")
        } catch (e: VoiceArchiveDamagedException) {
            assertTrue(e.message!!, e.message!!.contains("bytes"))
        }
    }

    @Test
    fun onlyTheGzipMagicSwitchesOnTheInflater() {
        fun through(bytes: ByteArray) = VoiceStore.gunzipIfCompressed(bytes.inputStream()).readBytes()
        assertTrue(through(TestModelFiles.gzip(tar)).contentEquals(tar))
        assertTrue("a plain tar passes unchanged", through(tar).contentEquals(tar))
        assertTrue(through(ByteArray(0)).isEmpty())
        assertTrue(through(byteArrayOf(0x1f)).contentEquals(byteArrayOf(0x1f)))
        assertTrue(through(byteArrayOf(0x1f, 0x00, 0x05)).contentEquals(byteArrayOf(0x1f, 0x00, 0x05)))
    }
}
