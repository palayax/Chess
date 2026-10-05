package net.palaya.chessanalyzer.video

import android.content.res.AssetManager
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.security.DigestInputStream
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import net.palaya.chessanalyzer.ui.model.NeuralVoiceTier
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream

/** The APK's bundled voice archive does not match its pinned size or SHA-256, or is missing files. */
class BundledVoiceDamagedException(message: String) : IOException(message)

/** Not enough free space under `filesDir` to extract the voice model out of the APK. */
class InsufficientVoiceStorageException(val neededBytes: Long, val availableBytes: Long) :
    IOException("Not enough free space for the narration voice: need $neededBytes bytes, have $availableBytes")

/**
 * Extracts the bundled Kokoro voice model from the APK's assets into `filesDir/tts_models/kokoro/`,
 * once, so [NeuralTtsProvider] (which hands sherpa-onnx real file paths) can load it.
 *
 * The asset is `tts/kokoro-int8-en-v0_19.tar`, a plain (not bzip2) tar of about 158 MB stored
 * uncompressed in the APK (`:app` build file, `noCompress`). It is read as a stream through a
 * [DigestInputStream], extracted into a scratch directory, and its SHA-256 is compared with the one
 * pinned in `vendor/models/MODELS.lock` (surfaced as [GeneratedBundledVoiceConstants]) **after the
 * pass and before anything is moved into place**: a bad asset never becomes the installed model.
 *
 * Replaces the Round 5–12 `VoiceModelProvisioner`, keeping its tested extraction tail:
 *  - the tar's single top-level directory is stripped, so the model lives at fixed relative paths;
 *  - zip-slip guard: every entry's canonical path must stay inside the scratch directory;
 *  - atomic: files land in `tts_models/kokoro.extracting/`, that whole directory is renamed to
 *    `tts_models/kokoro/`, and only then is the `.provisioned` marker written, last;
 *  - the marker holds **this build's pinned tar hash**, so a model bump (or an upgrade from a build
 *    whose marker holds an older value) re-extracts automatically.
 *
 * Lives under `filesDir`, never `cacheDir`, so the OS cannot evict it. It is regenerable from the
 * APK, so it is excluded from Auto Backup (see `backup_rules.xml`).
 *
 * Restart-on-failure: every attempt first deletes any `kokoro.extracting/` left by a killed run;
 * there is no byte-level resume.
 */
class BundledVoiceInstaller internal constructor(
    filesDir: File,
    private val openAsset: (String) -> InputStream,
    private val usableSpace: (File) -> Long,
    private val pinnedSha256: String,
    private val pinnedSizeBytes: Long,
    private val assetPath: String,
) {
    constructor(filesDir: File, assets: AssetManager) : this(
        filesDir = filesDir,
        openAsset = { path -> assets.open(path, AssetManager.ACCESS_STREAMING) },
        usableSpace = { it.usableSpace },
        pinnedSha256 = GeneratedBundledVoiceConstants.TAR_SHA256,
        pinnedSizeBytes = GeneratedBundledVoiceConstants.TAR_SIZE_BYTES,
        assetPath = GeneratedBundledVoiceConstants.ASSET_PATH,
    )

    private val rootDir = File(filesDir, ROOT_DIR_NAME)
    private val installMutex = Mutex()

    /** Where the model files live once installed: the directory [NeuralTtsProvider] reads from. */
    val modelDir: File get() = File(rootDir, MODEL_DIR_NAME)

    private val markerFile: File get() = File(modelDir, MARKER_NAME)

    /** [tier] is accepted for callers that still pass one; Kokoro is the only tier. */
    @Suppress("UNUSED_PARAMETER")
    fun modelDir(tier: NeuralVoiceTier): File = modelDir

    /**
     * True when this build's voice is fully installed: the marker holds exactly this build's pinned
     * tar hash and every required file is present. Cheap (no hashing, no walking).
     */
    fun isInstalled(): Boolean =
        markerFile.isFile &&
            runCatching { markerFile.readText().trim() }.getOrNull() == pinnedSha256 &&
            hasRequiredFiles(modelDir)

    /** Bytes the extracted model needs on disk (a little under the tar's own size). */
    val neededBytes: Long get() = pinnedSizeBytes

    /**
     * Ensures the voice is installed, extracting it from the APK if missing, stale or incomplete.
     * Safe to call repeatedly and concurrently; an installed voice returns at once. [onProgress]
     * reports 0f..1f by bytes of the tar consumed and ends at exactly 1f.
     *
     * @throws InsufficientVoiceStorageException when the volume cannot hold the extracted model
     * @throws BundledVoiceDamagedException when the bundled archive fails verification
     * @throws IOException on any other I/O failure (the next call starts over)
     */
    suspend fun ensureInstalled(onProgress: (Float) -> Unit = {}): File = withContext(Dispatchers.IO) {
        installMutex.withLock {
            if (isInstalled()) {
                onProgress(1f)
                return@withLock modelDir
            }

            rootDir.mkdirs()
            val extractingDir = File(rootDir, SCRATCH_DIR_NAME)
            extractingDir.deleteRecursively()

            val available = usableSpace(rootDir)
            if (available < pinnedSizeBytes) throw InsufficientVoiceStorageException(pinnedSizeBytes, available)

            try {
                extractingDir.mkdirs()
                val digest = MessageDigest.getInstance("SHA-256")
                val counter = CountingInputStream(DigestInputStream(openAsset(assetPath), digest))
                counter.use { source ->
                    extractTar(source, extractingDir) { consumed ->
                        onProgress((consumed.toFloat() / pinnedSizeBytes).coerceIn(0f, 0.999f))
                    }
                    // TarArchiveInputStream stops at the end-of-archive marker, but a tar is padded
                    // to a whole record, so the hash covers the file only once the rest is read.
                    val rest = ByteArray(64 * 1024)
                    while (true) {
                        coroutineContext.ensureActive()
                        if (source.read(rest) == -1) break
                    }
                }
                if (counter.count != pinnedSizeBytes) {
                    throw BundledVoiceDamagedException(
                        "Bundled voice archive is ${counter.count} bytes, expected $pinnedSizeBytes"
                    )
                }
                val actual = digest.digest().joinToString("") { "%02x".format(it) }
                if (!actual.equals(pinnedSha256, ignoreCase = true)) {
                    throw BundledVoiceDamagedException(
                        "Bundled voice archive failed SHA-256 verification (expected $pinnedSha256, got $actual)"
                    )
                }
                if (!hasRequiredFiles(extractingDir)) {
                    throw BundledVoiceDamagedException("Bundled voice archive is missing expected model files")
                }
                // Verified. The swap and the marker must not be interrupted halfway by a cancel.
                withContext(NonCancellable) {
                    val finalDir = modelDir
                    finalDir.deleteRecursively()
                    if (!extractingDir.renameTo(finalDir)) {
                        throw IOException("Failed to move the extracted voice model into place at ${finalDir.absolutePath}")
                    }
                    // Written only once the directory is in its final place: its presence, holding
                    // this build's hash, IS the "fully installed" signal [isInstalled] relies on.
                    markerFile.writeText(pinnedSha256)
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
     * Extracts a tar read from [source] into [destDir], stripping the single top-level directory
     * the upstream release archives its files under (`kokoro-int8-en-v0_19/model.int8.onnx` ->
     * `model.int8.onnx`), so [NeuralTtsProvider] can address files by fixed relative paths.
     *
     * Guards against zip-slip (a `../`-crafted entry name escaping [destDir]) by resolving every
     * entry's canonical path and refusing to write outside [destDir]. The archive is hash-verified
     * after the pass, but there is no reason to write entries blindly in the meantime.
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

    /** Counts the bytes that pass through, so progress and the pinned size check see the whole asset. */
    private class CountingInputStream(input: InputStream) : FilterInputStream(input) {
        var count = 0L
            private set

        override fun read(): Int = super.read().also { if (it != -1) count++ }

        override fun read(b: ByteArray, off: Int, len: Int): Int =
            super.read(b, off, len).also { if (it > 0) count += it }

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
        /** Directory under `filesDir` holding the model and its scratch directory. Excluded from backup. */
        const val ROOT_DIR_NAME = "tts_models"

        /** The installed model's directory name under [ROOT_DIR_NAME] (the tier's lower-case name). */
        const val MODEL_DIR_NAME = "kokoro"

        /** Where an extraction is staged before the atomic rename. A killed run leaves it behind. */
        const val SCRATCH_DIR_NAME = "kokoro.extracting"

        /** Written last, inside [MODEL_DIR_NAME]; holds this build's pinned tar SHA-256. */
        const val MARKER_NAME = ".provisioned"

        const val KOKORO_MODEL_FILE = "model.int8.onnx"
        const val KOKORO_VOICES_FILE = "voices.bin"
        const val KOKORO_TOKENS_FILE = "tokens.txt"

        /** Relative-to-model-dir paths [NeuralTtsProvider] loads. */
        val REQUIRED_FILES: List<String> = listOf(KOKORO_MODEL_FILE, KOKORO_VOICES_FILE, KOKORO_TOKENS_FILE)

        fun requiredFilesFor(@Suppress("UNUSED_PARAMETER") tier: NeuralVoiceTier): List<String> = REQUIRED_FILES
    }
}
