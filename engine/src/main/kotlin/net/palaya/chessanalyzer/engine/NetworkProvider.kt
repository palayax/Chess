package net.palaya.chessanalyzer.engine

import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/** A newer Stockfish release than the one vendored in this build (see [NetworkProvider]). */
data class EngineUpdateInfo(
    val latestVersion: String,
    val releaseUrl: String,
)

/**
 * Downloads, verifies and caches the NNUE evaluation net Stockfish needs at runtime (see
 * [StockfishEngine.setEvalFile]), and checks whether a newer Stockfish release exists upstream.
 *
 * The net filename is intentionally not hardcoded here: [NET_FILENAME] comes from
 * [GeneratedNetworkConstants], which the `:engine` module's `generateNetworkConstants` Gradle
 * task (engine/build.gradle.kts) generates at build time by reading
 * `vendor/Stockfish/src/evaluate.h`'s `#define EvalFileDefaultName "..."` — the one place
 * Stockfish itself defines that name. That keeps this file and the vendored C++ source from
 * ever silently disagreeing about which net is "the" default net.
 */
class NetworkProvider(
    private val filesDir: File,
    private val okHttpClient: OkHttpClient = OkHttpClient(),
    private val vendoredStockfishTag: String = VENDORED_STOCKFISH_TAG,
) {

    companion object {
        /** The NNUE net filename this Stockfish build expects (see class doc). */
        const val NET_FILENAME: String = GeneratedNetworkConstants.EVAL_FILE_DEFAULT_NAME

        /** Official endpoint Stockfish's own tooling uses to distribute nets by filename. */
        private const val NET_BASE_URL = "https://tests.stockfishchess.org/api/nn/"

        private const val LATEST_RELEASE_API =
            "https://api.github.com/repos/official-stockfish/Stockfish/releases/latest"

        /** Tag vendored under vendor/Stockfish — see vendor/STOCKFISH_VERSION.txt. */
        const val VENDORED_STOCKFISH_TAG: String = "sf_19"

        private const val DOWNLOAD_SUFFIX = ".download"
    }

    private val netFile: File
        get() = File(filesDir, NET_FILENAME)

    /**
     * Ensures the NNUE net is present and SHA-256-verified under [filesDir], downloading it from
     * the official endpoint if missing or corrupt. Safe to call repeatedly: an already-valid
     * cached file is reused without re-downloading.
     *
     * The download is restart-on-failure (any error, including a checksum mismatch, discards
     * the partial/corrupt file and the next call starts over from byte 0 — there is no partial
     * file left where [netFile] would be) and atomic (written to a temp file, verified, then
     * renamed into place — a concurrent reader never sees a half-written net).
     */
    suspend fun ensureNet(onProgress: (Float) -> Unit = {}): File = withContext(Dispatchers.IO) {
        if (netFile.exists() && verifySha256Prefix(netFile)) {
            onProgress(1f)
            return@withContext netFile
        }

        val tmpFile = File(filesDir, "$NET_FILENAME$DOWNLOAD_SUFFIX")
        tmpFile.delete()

        val request = Request.Builder().url(NET_BASE_URL + NET_FILENAME).build()
        okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Failed to download $NET_FILENAME: HTTP ${response.code}")
            }
            val body = response.body ?: throw IOException("Empty response body for $NET_FILENAME")
            val total = body.contentLength()
            var readSoFar = 0L

            body.byteStream().use { input ->
                tmpFile.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buffer)
                        if (n == -1) break
                        output.write(buffer, 0, n)
                        readSoFar += n
                        if (total > 0) onProgress(readSoFar.toFloat() / total.toFloat())
                    }
                }
            }
        }

        if (!verifySha256Prefix(tmpFile)) {
            tmpFile.delete()
            throw IOException(
                "Downloaded $NET_FILENAME failed SHA-256 verification against its own " +
                    "filename; deleted the corrupt download. Call ensureNet() again to retry."
            )
        }

        if (!tmpFile.renameTo(netFile)) {
            throw IOException("Failed to move downloaded net into place at ${netFile.absolutePath}")
        }
        onProgress(1f)
        netFile
    }

    /**
     * Checks GitHub's "latest release" API for the official Stockfish repo and compares its tag
     * to the version vendored in this build ([vendoredStockfishTag]). Returns null both when
     * already up to date and when the check itself fails (offline, rate-limited, etc.) — callers
     * should treat null as "nothing to report", not as an error.
     */
    suspend fun checkForEngineUpdate(): EngineUpdateInfo? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url(LATEST_RELEASE_API).build()
            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val bodyString = response.body?.string() ?: return@withContext null
                val json = JSONObject(bodyString)
                val tagName = json.optString("tag_name", "")
                val releaseUrl = json.optString("html_url", "")
                if (tagName.isEmpty() || tagName == vendoredStockfishTag) {
                    null
                } else {
                    EngineUpdateInfo(latestVersion = tagName, releaseUrl = releaseUrl)
                }
            }
        } catch (e: IOException) {
            null
        }
    }

    /**
     * Verifies [file]'s SHA-256 against the hash encoded in its own expected filename: official
     * Stockfish net files are named `nn-<first-12-hex-of-sha256>.nnue`, so the filename doubles
     * as the checksum (this is the same scheme Stockfish's own UCI code uses to validate nets).
     */
    private fun verifySha256Prefix(file: File): Boolean {
        val expectedPrefix = Regex("""nn-([0-9a-f]{12})\.nnue""").find(NET_FILENAME)
            ?.groupValues?.get(1) ?: return false

        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n == -1) break
                digest.update(buffer, 0, n)
            }
        }
        val actualHex = digest.digest().joinToString("") { "%02x".format(it) }
        return actualHex.startsWith(expectedPrefix)
    }
}
