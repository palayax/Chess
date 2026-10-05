package net.palaya.chessanalyzer.engine

import android.content.res.AssetManager
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.DigestInputStream
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** The APK's bundled copy of the net does not match its own pinned size or hash: a damaged install. */
class BundledNetDamagedException(message: String) : IOException(message)

/** There is not enough free space under `filesDir` to copy the net out of the APK. */
class InsufficientNetStorageException(val neededBytes: Long, val availableBytes: Long) :
    IOException("Not enough free space for the engine net: need $neededBytes bytes, have $availableBytes")

/**
 * Makes the NNUE evaluation net Stockfish needs at runtime (see [StockfishEngine.setEvalFile])
 * available as a real file under `filesDir`, copying it once out of the APK's assets.
 *
 * Why a copy and not a direct read: Stockfish opens the net with `std::ifstream(path)` and calls
 * `exit(EXIT_FAILURE)` on any failure, so it needs an ordinary file path. An asset has none (and a
 * `/proc/self/fd/N` path to the APK would open the whole APK from byte 0, failing the net header
 * check and killing the process). The `setEvalFile()`/`analyze()` guards in [StockfishEngine] are
 * unchanged by bundling and still apply to the file this class returns.
 *
 * The net filename is intentionally not hardcoded here: [NET_FILENAME] comes from
 * [GeneratedNetworkConstants] (derived from `vendor/Stockfish/src/evaluate.h`), and the asset
 * is `nnue/<NET_FILENAME>` in `:engine`'s assets (merged from `vendor/models/engine-assets`, see
 * engine/build.gradle.kts). The expected size comes from [GeneratedBundledNetConstants], written by
 * the `verifyBundledModels` task from `vendor/models/MODELS.lock`.
 *
 * Integrity: official net files are named `nn-<first-12-hex-of-sha256>.nnue`, so the filename is
 * also the checksum. It is verified for the installed file (every cold start of the process; a
 * file that has already been verified in this process and has not changed is not re-hashed) and for
 * the copy before it is renamed into place.
 *
 * The copy is restart-on-failure and atomic: bytes stream into `<name>.part`, are checked for
 * length and hash, and only then renamed over [netFile], so a reader never sees a half-written
 * net and a process killed mid-copy leaves nothing at the final path.
 */
class BundledNetProvider internal constructor(
    private val filesDir: File,
    private val openAsset: (String) -> InputStream,
    private val usableSpace: (File) -> Long,
) {
    constructor(filesDir: File, assets: AssetManager) : this(
        filesDir = filesDir,
        openAsset = { path -> assets.open(path, AssetManager.ACCESS_STREAMING) },
        usableSpace = { it.usableSpace },
    )

    companion object {
        /** The NNUE net filename this Stockfish build expects (see class doc). */
        const val NET_FILENAME: String = GeneratedNetworkConstants.EVAL_FILE_DEFAULT_NAME

        /** Exact size in bytes of the bundled net, pinned in `vendor/models/MODELS.lock`. */
        const val NET_SIZE_BYTES: Long = GeneratedBundledNetConstants.NET_SIZE_BYTES

        /** Path of the net inside the APK's assets. */
        const val ASSET_PATH: String = "nnue/$NET_FILENAME"

        /** Suffix of the scratch file the copy streams into. Excluded from backup like the net. */
        const val PART_SUFFIX = ".part"

        private const val BUFFER_BYTES = 256 * 1024
    }

    private val netFile: File
        get() = File(filesDir, NET_FILENAME)

    private val copyMutex = Mutex()

    /** `length` and `lastModified` of the file as last verified in this process. */
    @Volatile private var verifiedStamp: Pair<Long, Long>? = null

    /** Cheap check, with no hashing: a file of exactly the right size is at the final path. */
    fun isNetPresent(): Boolean = netFile.isFile && netFile.length() == NET_SIZE_BYTES

    /**
     * Ensures the verified net is under [filesDir] and returns it, copying it out of the APK if it
     * is missing, truncated or corrupt. Safe to call repeatedly and concurrently.
     *
     * [onProgress] reports 0f..1f by bytes copied, ending at exactly 1f; an already-valid install
     * reports only 1f.
     *
     * @throws InsufficientNetStorageException when the volume cannot hold the copy
     * @throws BundledNetDamagedException when the bundled asset itself fails verification
     * @throws IOException on any other I/O failure; the next call starts over from byte 0
     */
    suspend fun ensureNet(onProgress: (Float) -> Unit = {}): File = withContext(Dispatchers.IO) {
        copyMutex.withLock {
            if (isInstalledAndVerified()) {
                onProgress(1f)
                return@withLock netFile
            }

            filesDir.mkdirs()
            val partFile = File(filesDir, "$NET_FILENAME$PART_SUFFIX")
            deleteStaleParts(partFile)

            val available = usableSpace(filesDir)
            if (available < NET_SIZE_BYTES) throw InsufficientNetStorageException(NET_SIZE_BYTES, available)

            try {
                val digest = MessageDigest.getInstance("SHA-256")
                var copied = 0L
                DigestInputStream(openAsset(ASSET_PATH), digest).use { input ->
                    partFile.outputStream().use { output ->
                        val buffer = ByteArray(BUFFER_BYTES)
                        while (true) {
                            coroutineContext.ensureActive()
                            val n = input.read(buffer)
                            if (n == -1) break
                            output.write(buffer, 0, n)
                            copied += n
                            onProgress((copied.toFloat() / NET_SIZE_BYTES).coerceIn(0f, 0.999f))
                        }
                        output.fd.sync()
                    }
                }
                if (copied != NET_SIZE_BYTES) {
                    throw BundledNetDamagedException(
                        "Bundled $NET_FILENAME is $copied bytes, expected $NET_SIZE_BYTES"
                    )
                }
                if (!hexOf(digest.digest()).startsWith(expectedPrefix())) {
                    throw BundledNetDamagedException("Bundled $NET_FILENAME failed SHA-256 verification against its own name")
                }
                // Verified. Moving it into place must not be interrupted halfway by a cancel.
                withContext(NonCancellable) { moveIntoPlace(partFile) }
            } catch (t: Throwable) {
                partFile.delete()
                throw t
            }
            verifiedStamp = stampOf(netFile)
            onProgress(1f)
            netFile
        }
    }

    private fun isInstalledAndVerified(): Boolean {
        val f = netFile
        if (!f.isFile || f.length() != NET_SIZE_BYTES) return false
        if (verifiedStamp == stampOf(f)) return true
        if (!hashOfFile(f).startsWith(expectedPrefix())) return false
        verifiedStamp = stampOf(f)
        return true
    }

    private fun stampOf(f: File): Pair<Long, Long> = f.length() to f.lastModified()

    private fun deleteStaleParts(partFile: File) {
        partFile.delete()
        filesDir.listFiles { _, name -> name.startsWith("nn-") && name.endsWith(PART_SUFFIX) }
            ?.forEach { it.delete() }
    }

    private fun moveIntoPlace(partFile: File) {
        try {
            Files.move(partFile.toPath(), netFile.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } catch (e: IOException) {
            netFile.delete()
            if (!partFile.renameTo(netFile)) {
                throw IOException("Failed to move the engine net into place at ${netFile.absolutePath}", e)
            }
        }
    }

    private fun expectedPrefix(): String =
        Regex("""nn-([0-9a-f]{12})\.nnue""").find(NET_FILENAME)?.groupValues?.get(1)
            ?: throw IllegalStateException("Net name $NET_FILENAME does not encode a 12-hex SHA-256 prefix")

    private fun hashOfFile(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(BUFFER_BYTES)
            while (true) {
                val n = input.read(buffer)
                if (n == -1) break
                digest.update(buffer, 0, n)
            }
        }
        return hexOf(digest.digest())
    }

    private fun hexOf(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }
}
