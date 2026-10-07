package net.palaya.chessanalyzer.video

import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.PushbackInputStream
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import net.palaya.chessanalyzer.BuildConfig
import net.palaya.chessanalyzer.data.models.GeneratedModelPins
import net.palaya.chessanalyzer.data.models.ModelCompatibility
import net.palaya.chessanalyzer.ui.model.NeuralVoiceTier
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream

/** The voice archive does not match its expected size or SHA-256, or is missing model files. */
class VoiceArchiveDamagedException(message: String) : IOException(message)

/** Not enough free space under `filesDir` to unpack the voice model. */
class InsufficientVoiceStorageException(val neededBytes: Long, val availableBytes: Long) :
    IOException("Not enough free space for the narration voice: need $neededBytes bytes, have $availableBytes")

/**
 * The file setup downloads for the voice, with its pins (`GeneratedModelPins.VOICE_FILE_NAME`,
 * `VOICE_DOWNLOAD_SIZE_BYTES`, `VOICE_DOWNLOAD_SHA256`). Since D2f it is the Kokoro tar compressed with
 * `gzip -9 -n` (102.5 MB instead of 158.3 MB); `ModelDownloader` verifies these bytes, and the unpack step
 * then checks the tar inside against [VoiceStore.pinnedSha256] / [VoiceStore.pinnedSizeBytes].
 */
data class VoiceDownload(val fileName: String, val sizeBytes: Long, val sha256: String) {
    companion object {
        /** This build's pins. */
        val PINNED = VoiceDownload(
            fileName = GeneratedModelPins.VOICE_FILE_NAME,
            sizeBytes = GeneratedModelPins.VOICE_DOWNLOAD_SIZE_BYTES,
            sha256 = GeneratedModelPins.VOICE_DOWNLOAD_SHA256,
        )
    }
}

/**
 * Where the Kokoro narration voice lives on the phone: `filesDir/tts_models/kokoro/`
 * (docs/MODEL_DOWNLOAD_DESIGN.md §2.1, §4.3). The voice is no longer in the APK (D2a): the app downloads
 * the archive ([download], a `.tar.gz` since D2f) once into [partFile] (`tts_models/kokoro.tar.part`,
 * verified by `ModelDownloader`) and hands it to [installFromTar], which gunzips it as a stream while it
 * unpacks (a gzip archive is recognised by its magic bytes; a plain tar is still accepted, so a test seed,
 * an older part or an update's `.tar` work unchanged). This class does no network I/O.
 *
 * Kept from the bundled-model installer (R4a), unchanged in behaviour:
 *  - the tar is read as a stream through a [DigestInputStream] and its SHA-256 is compared with the
 *    expected one **after the pass and before anything is moved into place** (a second check on top of
 *    the downloader's: the same tail also seeds the test APKs from an asset stream, D2d). For a `.tar.gz`
 *    the digest and the size are those of the decompressed tar, so the pins, the marker and an update
 *    from a bundled build are exactly what they were with the plain tar; a stream that inflates past the
 *    expected size is refused at once (no gzip bomb fills the disk);
 *  - the tar's single top-level directory is stripped, so the model lives at fixed relative paths;
 *  - zip-slip guard: every entry's canonical path must stay inside the scratch directory;
 *  - atomic: files land in `tts_models/kokoro.extracting/`, that directory is renamed to
 *    `tts_models/kokoro/`, and only then is the `.provisioned` marker written, last, holding the tar's
 *    SHA-256. [isInstalled] compares the marker with this build's pin, byte for byte as the bundled
 *    builds did, so an update from a bundled build accepts the voice already on the phone (design §8).
 *
 * Lives under `filesDir`, never `cacheDir`, so the OS cannot evict it; excluded from Auto Backup
 * (`tts_models/` in `backup_rules.xml`, which also covers the part file).
 *
 * **Updates (D2e, design §4.3).** A voice from "Check for updates" is unpacked into the scratch directory
 * ([extractToScratch]), tried there by `ModelActivator`, then swapped in ([swapInScratch]): `kokoro/`
 * becomes `kokoro.previous/` until the activation commits ([deletePrevious]) or rolls back
 * ([restorePrevious]). Its marker holds the update's tar hash and a `.compat` file records the manifest's
 * layout and sherpa-onnx range; [isInstalled] accepts such a voice while this build still matches them.
 */
class VoiceStore internal constructor(
    filesDir: File,
    private val usableSpace: (File) -> Long,
    /** This build's pinned tar SHA-256 (`GeneratedModelPins.VOICE_SHA256`). */
    val pinnedSha256: String,
    /** This build's pinned tar size. */
    val pinnedSizeBytes: Long,
    /** This build's sherpa-onnx version (`BuildConfig.SHERPA_ONNX_VERSION`), for an update's `.compat`. */
    private val runtimeVersion: String = BuildConfig.SHERPA_ONNX_VERSION,
    /** What setup downloads (D2f: the gzipped tar) and its pins. */
    val download: VoiceDownload = VoiceDownload.PINNED,
) {
    constructor(filesDir: File) : this(
        filesDir = filesDir,
        usableSpace = { it.usableSpace },
        pinnedSha256 = GeneratedModelPins.VOICE_SHA256,
        pinnedSizeBytes = GeneratedModelPins.VOICE_SIZE_BYTES,
    )

    private val rootDir = File(filesDir, ROOT_DIR_NAME)
    private val installMutex = Mutex()

    /** Where the model files live once installed: the directory [NeuralTtsProvider] reads from. */
    val modelDir: File get() = File(rootDir, MODEL_DIR_NAME)

    /** The download target: `tts_models/kokoro.tar.part` (it holds [download], the `.tar.gz`). Kept across a pause or a kill. */
    val partFile: File get() = File(rootDir, PART_FILE_NAME)

    private val markerFile: File get() = File(modelDir, MARKER_NAME)

    /** Where an extraction is staged ([extractToScratch]); an update's trial runs on it. */
    val scratchDir: File get() = File(rootDir, SCRATCH_DIR_NAME)

    /** The voice an update replaced, kept until its activation commits or rolls back. */
    val previousDir: File get() = File(rootDir, PREVIOUS_DIR_NAME)

    /** [tier] is accepted for callers that still pass one; Kokoro is the only tier. */
    @Suppress("UNUSED_PARAMETER")
    fun modelDir(tier: NeuralVoiceTier): File = modelDir

    /**
     * True when this build's voice is fully installed: the marker holds exactly this build's pinned
     * tar hash and every required file is present. Cheap (no hashing, no walking).
     */
    fun isInstalled(): Boolean {
        if (!markerFile.isFile) return false
        val marker = runCatching { markerFile.readText().trim() }.getOrNull() ?: return false
        val accepted = marker == pinnedSha256 || (SHA256.matches(marker) && updateCompatHolds(modelDir))
        return accepted && hasRequiredFiles(modelDir)
    }

    /** The installed voice tar's full SHA-256 (its marker), or null when [isInstalled] is false. */
    fun installedSha256(): String? =
        if (isInstalled()) runCatching { markerFile.readText().trim() }.getOrNull() else null

    /**
     * True when [dir] holds a `.compat` record (written by [swapInScratch] for an update) whose layout is
     * this app's [LAYOUT] and whose sherpa-onnx range contains this build's version. An app update that
     * changes either makes the voice "not installed", and Setup fetches this build's pinned voice.
     */
    private fun updateCompatHolds(dir: File): Boolean {
        val f = File(dir, COMPAT_NAME)
        if (!f.isFile) return false
        return runCatching {
            val p = java.util.Properties().apply { f.inputStream().use { load(it) } }
            p.getProperty("layout") == LAYOUT &&
                ModelCompatibility.versionInRange(runtimeVersion, p.getProperty("runtimeMin").orEmpty(), p.getProperty("runtimeMax").orEmpty())
        }.getOrDefault(false)
    }

    /** The installed voice's id: the first 12 hex digits of its tar's SHA-256, or null when none. */
    fun installedVersionId(): String? =
        runCatching { markerFile.readText().trim() }.getOrNull()
            ?.takeIf { Regex("[0-9a-f]{64}").matches(it) && hasRequiredFiles(modelDir) }
            ?.take(12)

    /** Bytes the unpacked model needs on disk (a little under the tar's own size). */
    val neededBytes: Long get() = pinnedSizeBytes

    /** Deletes the download in progress (Cancel). */
    fun deletePart() {
        partFile.delete()
    }

    /** An update's download target (D2e): `tts_models/update-<12 hex>.tar.part`, apart from setup's part. */
    fun updatePartFile(sha256: String): File = File(rootDir, "$UPDATE_PART_PREFIX${sha256.take(12)}.tar.part")

    /** Deletes every update download (a rollback, or Cancel in the update sheet). */
    fun deleteUpdateParts() {
        rootDir.listFiles { f -> f.isFile && f.name.startsWith(UPDATE_PART_PREFIX) }?.forEach { it.delete() }
    }

    /**
     * Unpacks a verified archive [tar] (normally [partFile]: the `.tar.gz` setup downloaded, or a plain tar)
     * into place and deletes it afterwards. [expectedSha256] and [expectedSizeBytes] are the TAR's (after
     * gunzip), by default this build's pins. See [installFromStream] for the checks. [onProgress] reports
     * 0f..1f by bytes of the tar consumed.
     */
    suspend fun installFromTar(
        tar: File,
        expectedSha256: String = pinnedSha256,
        expectedSizeBytes: Long = pinnedSizeBytes,
        onProgress: (Float) -> Unit = {},
    ): File {
        val dir = installFromStream({ tar.inputStream() }, expectedSha256, expectedSizeBytes, onProgress)
        tar.delete()
        return dir
    }

    /**
     * Unpacks the tar (or gzipped tar) read from [open] into `tts_models/kokoro/`, verifying
     * [expectedSizeBytes] and [expectedSha256] over the whole (decompressed) tar stream before the swap. Safe to call repeatedly and concurrently;
     * restart-on-failure (a killed run leaves only `kokoro.extracting/`, which the next call deletes).
     *
     * @throws InsufficientVoiceStorageException when the volume cannot hold the unpacked model
     * @throws VoiceArchiveDamagedException when the archive fails verification or lacks model files
     * @throws IOException on any other I/O failure
     */
    suspend fun installFromStream(
        open: () -> InputStream,
        expectedSha256: String,
        expectedSizeBytes: Long,
        onProgress: (Float) -> Unit = {},
    ): File = withContext(Dispatchers.IO) {
        installMutex.withLock {
            val extractingDir = extractVerifiedLocked(open, expectedSha256, expectedSizeBytes, onProgress)
            try {
                // Verified. The swap and the marker must not be interrupted halfway by a cancel.
                withContext(NonCancellable) {
                    val finalDir = modelDir
                    finalDir.deleteRecursively()
                    if (!extractingDir.renameTo(finalDir)) {
                        throw IOException("Failed to move the unpacked voice model into place at ${finalDir.absolutePath}")
                    }
                    // Written only once the directory is in its final place: its presence, holding
                    // the tar's hash, IS the "fully installed" signal [isInstalled] relies on.
                    markerFile.writeText(expectedSha256.lowercase())
                }
            } catch (t: Throwable) {
                extractingDir.deleteRecursively()
                throw t
            }
            onProgress(1f)
            modelDir
        }
    }

    /**
     * An update's first step (D2e): unpacks [tar] into [scratchDir] with every check [installFromStream]
     * makes (size, SHA-256 over the whole tar stream, top-level strip, zip-slip guard, required files), and
     * stops there: the installed voice is not touched. Returns [scratchDir]. [expectedSha256] and
     * [expectedSizeBytes] are the tar's; the default size is the file's own length, right only for a plain
     * tar (a `.tar.gz` entry carries its tar's size in the manifest, D2f).
     */
    suspend fun extractToScratch(
        tar: File,
        expectedSha256: String,
        expectedSizeBytes: Long = tar.length(),
        onProgress: (Float) -> Unit = {},
    ): File = withContext(Dispatchers.IO) {
        installMutex.withLock {
            extractVerifiedLocked({ tar.inputStream() }, expectedSha256, expectedSizeBytes, onProgress)
        }
    }

    /** Deletes the scratch directory (an update's trial failed, or a killed run left it). */
    fun discardScratch() {
        scratchDir.deleteRecursively()
    }

    /**
     * Swaps a tried [scratchDir] in (D2e): `kokoro/` -> `kokoro.previous/`, `kokoro.extracting/` ->
     * `kokoro/`, then the `.compat` record and, last, the marker with [sha256]. The caller journals it first.
     */
    fun swapInScratch(sha256: String, layout: String, runtimeMin: String, runtimeMax: String) {
        check(SHA256.matches(sha256)) { "not a SHA-256: $sha256" }
        check(hasRequiredFiles(scratchDir)) { "the scratch voice is incomplete" }
        previousDir.deleteRecursively()
        if (modelDir.exists() && !modelDir.renameTo(previousDir)) throw IOException("could not move the current voice aside")
        if (!scratchDir.renameTo(modelDir)) {
            // Put the old one back before reporting the failure.
            if (previousDir.exists()) previousDir.renameTo(modelDir)
            throw IOException("could not move the new voice into place")
        }
        File(modelDir, COMPAT_NAME).writeText("layout=$layout\nruntimeMin=$runtimeMin\nruntimeMax=$runtimeMax\n")
        markerFile.writeText(sha256.lowercase())
    }

    /** Rollback: the voice from before [swapInScratch] goes back into place. False when there is none. */
    fun restorePrevious(): Boolean {
        if (!previousDir.isDirectory) return false
        modelDir.deleteRecursively()
        return previousDir.renameTo(modelDir)
    }

    /** Commit: the replaced voice is no longer needed. */
    fun deletePrevious() {
        previousDir.deleteRecursively()
    }

    /** Unpacks and verifies into [scratchDir]; the caller holds [installMutex]. Returns [scratchDir]. */
    private suspend fun extractVerifiedLocked(
        open: () -> InputStream,
        expectedSha256: String,
        expectedSizeBytes: Long,
        onProgress: (Float) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        run {
            rootDir.mkdirs()
            val extractingDir = scratchDir
            extractingDir.deleteRecursively()

            val available = usableSpace(rootDir)
            if (available < expectedSizeBytes) throw InsufficientVoiceStorageException(expectedSizeBytes, available)

            try {
                extractingDir.mkdirs()
                val digest = MessageDigest.getInstance("SHA-256")
                val counter = CountingInputStream(DigestInputStream(gunzipIfCompressed(open()), digest), limit = expectedSizeBytes)
                counter.use { source ->
                    extractTar(source, extractingDir) { consumed ->
                        onProgress((consumed.toFloat() / expectedSizeBytes).coerceIn(0f, 0.999f))
                    }
                    // TarArchiveInputStream stops at the end-of-archive marker, but a tar is padded
                    // to a whole record, so the hash covers the file only once the rest is read.
                    val rest = ByteArray(64 * 1024)
                    while (true) {
                        coroutineContext.ensureActive()
                        if (source.read(rest) == -1) break
                    }
                }
                if (counter.count != expectedSizeBytes) {
                    throw VoiceArchiveDamagedException("Voice archive is ${counter.count} bytes, expected $expectedSizeBytes")
                }
                val actual = digest.digest().joinToString("") { "%02x".format(it) }
                if (!actual.equals(expectedSha256, ignoreCase = true)) {
                    throw VoiceArchiveDamagedException("Voice archive failed SHA-256 verification (expected $expectedSha256, got $actual)")
                }
                if (!hasRequiredFiles(extractingDir)) {
                    throw VoiceArchiveDamagedException("Voice archive is missing expected model files")
                }
                // The marker and the compat record are ours to write, never the archive's.
                File(extractingDir, MARKER_NAME).delete()
                File(extractingDir, COMPAT_NAME).delete()
            } catch (t: Throwable) {
                extractingDir.deleteRecursively()
                throw t
            }
            extractingDir
        }
    }

    /**
     * Extracts a tar read from [source] into [destDir], stripping the single top-level directory
     * the upstream release archives its files under (`kokoro-int8-en-v0_19/model.int8.onnx` ->
     * `model.int8.onnx`), so [NeuralTtsProvider] can address files by fixed relative paths.
     *
     * Guards against zip-slip (a `../`-crafted entry name escaping [destDir]) by resolving every
     * entry's canonical path and refusing to write outside [destDir].
     */
    private suspend fun extractTar(source: CountingInputStream, destDir: File, onBytes: (Long) -> Unit) {
        val destCanonical = destDir.canonicalFile
        val tar = TarArchiveInputStream(source)
        var entry: TarArchiveEntry? = tar.nextEntry
        while (entry != null) {
            coroutineContext.ensureActive()
            val relative = stripTopLevelDir(entry.name)
            if (relative.isNotEmpty()) {
                val outFile = File(destDir, relative)
                val outCanonical = outFile.canonicalFile
                if (outCanonical != destCanonical &&
                    !outCanonical.path.startsWith(destCanonical.path + File.separator)
                ) {
                    throw IOException("Refusing to extract entry outside destination: ${entry.name}")
                }
                if (entry.isDirectory) {
                    outFile.mkdirs()
                } else {
                    outFile.parentFile?.mkdirs()
                    outFile.outputStream().use { out ->
                        val buffer = ByteArray(256 * 1024)
                        while (true) {
                            coroutineContext.ensureActive()
                            val n = tar.read(buffer)
                            if (n == -1) break
                            out.write(buffer, 0, n)
                            onBytes(source.count)
                        }
                    }
                }
            }
            onBytes(source.count)
            entry = tar.nextEntry
        }
    }

    private fun stripTopLevelDir(entryName: String): String {
        val normalized = entryName.replace('\\', '/').trimStart('/')
        val slashIndex = normalized.indexOf('/')
        return if (slashIndex < 0) "" else normalized.substring(slashIndex + 1)
    }

    private fun hasRequiredFiles(dir: File): Boolean =
        REQUIRED_FILES.all { File(dir, it).isFile } && File(dir, NeuralTtsProvider.ESPEAK_DATA_DIR).isDirectory

    /**
     * Counts the bytes that pass through, so progress and the size check see the whole archive. Throws
     * [VoiceArchiveDamagedException] as soon as more than [limit] bytes came through: a tar (or what a
     * `.tar.gz` inflates to) longer than its pin is wrong, and is not written out to the end first.
     */
    private class CountingInputStream(input: InputStream, private val limit: Long) : FilterInputStream(input) {
        var count = 0L
            private set

        override fun read(): Int = super.read().also { if (it != -1) add(1) }

        override fun read(b: ByteArray, off: Int, len: Int): Int =
            super.read(b, off, len).also { if (it > 0) add(it.toLong()) }

        private fun add(n: Long) {
            count += n
            if (count > limit) throw VoiceArchiveDamagedException("Voice archive is longer than the expected $limit bytes")
        }

        override fun skip(n: Long): Long {
            // Tar skips unread entry bodies; they still pass through the digest, so read them.
            var left = n
            val sink = ByteArray(8 * 1024)
            while (left > 0) {
                val r = read(sink, 0, minOf(sink.size.toLong(), left).toInt())
                if (r == -1) break
                left -= r
            }
            return n - left
        }
    }

    companion object {
        /**
         * [input] as a tar stream: wrapped in a streaming [GZIPInputStream] when it starts with the gzip magic
         * bytes 1f 8b (the `.tar.gz` setup downloads since D2f), else returned as is (a plain tar, whose
         * first bytes are an entry name, never 1f 8b). Decompression costs about 1 s for the voice on the
         * chess36 emulator (D2f measurement), and nothing is written but the unpacked files.
         */
        fun gunzipIfCompressed(input: InputStream): InputStream {
            val pushback = PushbackInputStream(input, 2)
            val head = ByteArray(2)
            var n = 0
            while (n < 2) {
                val r = pushback.read(head, n, 2 - n)
                if (r == -1) break
                n += r
            }
            if (n > 0) pushback.unread(head, 0, n)
            val gzip = n == 2 && head[0] == 0x1f.toByte() && head[1] == 0x8b.toByte()
            return if (gzip) GZIPInputStream(pushback, 64 * 1024) else pushback
        }

        /** Directory under `filesDir` holding the model, its scratch directory and the part file. Excluded from backup. */
        const val ROOT_DIR_NAME = "tts_models"

        /** The installed model's directory name under [ROOT_DIR_NAME]. */
        const val MODEL_DIR_NAME = "kokoro"

        /** Where an extraction is staged before the atomic rename. A killed run leaves it behind. */
        const val SCRATCH_DIR_NAME = "kokoro.extracting"

        /** The download in progress, under [ROOT_DIR_NAME] (it holds the `.tar.gz` since D2f; the name is kept). */
        const val PART_FILE_NAME = "kokoro.tar.part"

        /** Written last, inside [MODEL_DIR_NAME]; holds the installed tar's SHA-256. */
        const val MARKER_NAME = ".provisioned"

        /** File-name prefix of an update's download under [ROOT_DIR_NAME] (D2e). */
        const val UPDATE_PART_PREFIX = "update-"

        /** An update's compatibility record, inside [MODEL_DIR_NAME] (D2e). */
        const val COMPAT_NAME = ".compat"

        /** The voice an update replaced, until the activation commits or rolls back (D2e). */
        const val PREVIOUS_DIR_NAME = "kokoro.previous"

        private val SHA256 = Regex("[0-9a-f]{64}")

        /** The model layout this app's [NeuralTtsProvider] loads (D2e's manifest `compat.layout`). */
        const val LAYOUT = "kokoro-v0_19"

        const val KOKORO_MODEL_FILE = "model.int8.onnx"
        const val KOKORO_VOICES_FILE = "voices.bin"
        const val KOKORO_TOKENS_FILE = "tokens.txt"

        /** Relative-to-model-dir paths [NeuralTtsProvider] loads. */
        val REQUIRED_FILES: List<String> = listOf(KOKORO_MODEL_FILE, KOKORO_VOICES_FILE, KOKORO_TOKENS_FILE)

        fun requiredFilesFor(@Suppress("UNUSED_PARAMETER") tier: NeuralVoiceTier): List<String> = REQUIRED_FILES
    }
}
