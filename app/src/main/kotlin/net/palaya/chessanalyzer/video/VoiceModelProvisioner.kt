package net.palaya.chessanalyzer.video

import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.palaya.chessanalyzer.ui.model.NeuralVoiceTier
import okhttp3.OkHttpClient
import okhttp3.Request
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream

/** Files a [NeuralVoiceTier]'s model needs, once extracted, to build a working sherpa-onnx config. */
data class ModelSpec(
    val tier: NeuralVoiceTier,
    /** Pinned upstream sherpa-onnx release asset — verified to resolve; see [VoiceModelProvisioner] doc. */
    val url: String,
    /** SHA-256 of the downloaded `.tar.bz2` archive itself, pinned here — never trust the server alone. */
    val sha256: String,
    val downloadSizeBytes: Long,
    /**
     * Bytes the model occupies **once extracted** — what Settings must quote alongside
     * [downloadSizeBytes], because the two differ by a lot (the archives are bzip2'd) and quoting
     * only one of them misleads whichever way you pick. Measured by extracting each pinned
     * archive and summing the result; re-measure if the pinned URL/SHA ever changes.
     */
    val installedSizeBytes: Long,
    /** One-line summary shown in Settings/About — the licence [AboutScreen] must also surface. */
    val licenseSummary: String,
)

sealed interface ProvisioningResult {
    data class Success(val modelDir: File) : ProvisioningResult
    data class Failure(val reason: String) : ProvisioningResult
    data object Cancelled : ProvisioningResult
}

/**
 * Downloads, SHA-256-verifies and extracts the on-device neural voice models
 * ([NeuralVoiceTier.PIPER] / [NeuralVoiceTier.KOKORO]) that [NeuralTtsProvider] runs through
 * sherpa-onnx — mirroring the pattern already proven for the 98 MB Stockfish NNUE net (see
 * [net.palaya.chessanalyzer.engine.NetworkProvider]): download to a temp file under `filesDir`,
 * verify a SHA-256 pinned in code (never the filename, never a checksum fetched from the network),
 * then move into place atomically. Never bundled in the APK — always fetched at runtime, always
 * opt-in.
 *
 * Two differences from [net.palaya.chessanalyzer.engine.NetworkProvider]'s net download, both
 * because a voice model ships as a `.tar.bz2` of several files (the ONNX model, `tokens.txt`, and
 * an `espeak-ng-data/` phonemization directory), not one flat file:
 *  - the SHA-256 covers the downloaded archive, verified before a single byte is extracted;
 *  - "moved into place atomically" means extracting into a scratch directory and renaming that
 *    whole directory into place, so a reader never sees a half-extracted model directory.
 *
 * Lives under `filesDir/tts_models/<tier>/` — never `cacheDir`, for the same reason
 * [NarrationStore] avoids it: a multi-hundred-megabyte, deliberately-downloaded model must not be
 * silently evicted by the OS under storage pressure and re-fetched without the user knowing why
 * their data usage spiked.
 */
class VoiceModelProvisioner(
    filesDir: File,
    private val okHttpClient: OkHttpClient = defaultClient,
) {
    private val rootDir = File(filesDir, "tts_models")

    /** Where [tier]'s model files live once installed — the directory [NeuralTtsProvider] reads from. */
    fun modelDir(tier: NeuralVoiceTier): File = File(rootDir, tier.name.lowercase())

    /** A completion marker written only after extraction finishes — see [ensureModel]. */
    private fun markerFile(tier: NeuralVoiceTier): File = File(modelDir(tier), ".provisioned")

    fun isInstalled(tier: NeuralVoiceTier): Boolean = markerFile(tier).isFile

    /** Total bytes on disk for [tier] — 0 if not installed. What Settings shows per tier. */
    fun installedSizeBytes(tier: NeuralVoiceTier): Long {
        val dir = modelDir(tier)
        if (!dir.isDirectory) return 0L
        return dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }

    private val cancelled = AtomicBoolean(false)

    /** Cooperative cancellation for an in-flight [ensureModel] — checked between chunks/entries. */
    fun cancel() {
        cancelled.set(true)
    }

    /**
     * Ensures [tier]'s model is present and verified under [modelDir], downloading+extracting it
     * if missing. Safe to call repeatedly: an already-provisioned model (marker file present) is
     * reused without re-downloading. [onProgress] reports 0f..1f across the whole operation
     * (download is the overwhelming majority of the time; extraction reports the last 5%).
     */
    suspend fun ensureModel(tier: NeuralVoiceTier, onProgress: (Float) -> Unit = {}): ProvisioningResult =
        withContext(Dispatchers.IO) {
            cancelled.set(false)
            if (isInstalled(tier)) {
                onProgress(1f)
                return@withContext ProvisioningResult.Success(modelDir(tier))
            }

            val spec = specFor(tier)
            rootDir.mkdirs()
            val tmpArchive = File(rootDir, "${tier.name.lowercase()}.tar.bz2.download")
            tmpArchive.delete()

            try {
                downloadTo(spec, tmpArchive) { downloadFraction -> onProgress(downloadFraction * 0.9f) }
                if (cancelled.get()) return@withContext ProvisioningResult.Cancelled
                verifyAndInstall(tier, tmpArchive) { installFraction -> onProgress(0.9f + installFraction * 0.1f) }
            } catch (e: IOException) {
                tmpArchive.delete()
                ProvisioningResult.Failure("Network error downloading ${tier.name} model: ${e.message ?: e.javaClass.simpleName}")
            } catch (e: Exception) {
                tmpArchive.delete()
                ProvisioningResult.Failure("Failed to provision ${tier.name} model: ${e.message ?: e.javaClass.simpleName}")
            }
        }

    /**
     * SHA-256-verifies [archive] against [tier]'s pinned hash, extracts it, and atomically moves
     * the result into [modelDir] — the shared tail end of [ensureModel] once a `.tar.bz2` is on
     * disk, regardless of how it got there. Split out so instrumented tests can exercise this
     * exact verify/extract/install logic against a model archive pushed straight to the device
     * (`adb push ... /data/local/tmp/`, see the class doc) instead of re-downloading 20-100 MB
     * over the network on every test run — see [provisionFromLocalArchiveForTesting].
     */
    private fun verifyAndInstall(tier: NeuralVoiceTier, archive: File, onProgress: (Float) -> Unit): ProvisioningResult {
        val spec = specFor(tier)
        val actualSha256 = sha256Of(archive)
        if (!actualSha256.equals(spec.sha256, ignoreCase = true)) {
            archive.delete()
            return ProvisioningResult.Failure(
                "Downloaded ${tier.name} model failed SHA-256 verification " +
                    "(expected ${spec.sha256}, got $actualSha256); deleted the corrupt download."
            )
        }
        onProgress(0.2f)

        val extractingDir = File(rootDir, "${tier.name.lowercase()}.extracting")
        extractingDir.deleteRecursively()
        extractingDir.mkdirs()
        extractTarBz2(archive, extractingDir)
        if (cancelled.get()) {
            extractingDir.deleteRecursively()
            return ProvisioningResult.Cancelled
        }
        onProgress(0.8f)

        if (!hasRequiredFiles(tier, extractingDir)) {
            extractingDir.deleteRecursively()
            return ProvisioningResult.Failure("Extracted ${tier.name} archive is missing expected model files.")
        }

        val finalDir = modelDir(tier)
        finalDir.deleteRecursively()
        if (!extractingDir.renameTo(finalDir)) {
            extractingDir.deleteRecursively()
            return ProvisioningResult.Failure("Failed to move extracted ${tier.name} model into place.")
        }
        // Written only once the directory is already in its final place — its presence IS the
        // atomic "fully provisioned" signal isInstalled()/modelDir() rely on.
        markerFile(tier).writeText(spec.sha256)
        archive.delete()
        onProgress(1f)
        return ProvisioningResult.Success(finalDir)
    }

    /**
     * Test-only entry point: installs [tier] from an already-downloaded archive (e.g. one
     * `adb push`ed to `/data/local/tmp/` ahead of a `connectedDebugAndroidTest` run) instead of
     * fetching it over the network — same SHA-256 verification and extraction as the real
     * [ensureModel] path, just skipping the HTTP GET. Production code never calls this.
     */
    suspend fun provisionFromLocalArchiveForTesting(tier: NeuralVoiceTier, archive: File): ProvisioningResult =
        withContext(Dispatchers.IO) {
            cancelled.set(false)
            rootDir.mkdirs()
            verifyAndInstall(tier, archive) {}
        }

    /** The user's own explicit "delete" action from Settings — reclaims disk space on purpose. */
    fun delete(tier: NeuralVoiceTier): Boolean = modelDir(tier).deleteRecursively()

    private fun downloadTo(spec: ModelSpec, dest: File, onProgress: (Float) -> Unit) {
        val request = Request.Builder().url(spec.url).build()
        okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Failed to download ${spec.tier.name} model: HTTP ${response.code}")
            }
            val body = response.body ?: throw IOException("Empty response body for ${spec.tier.name} model")
            val total = body.contentLength().takeIf { it > 0 } ?: spec.downloadSizeBytes
            var readSoFar = 0L
            body.byteStream().use { input ->
                dest.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        if (cancelled.get()) return
                        val n = input.read(buffer)
                        if (n == -1) break
                        output.write(buffer, 0, n)
                        readSoFar += n
                        if (total > 0) onProgress((readSoFar.toFloat() / total.toFloat()).coerceIn(0f, 1f))
                    }
                }
            }
        }
    }

    private fun sha256Of(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n == -1) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * Extracts [archive] (a `.tar.bz2`) into [destDir], stripping the single top-level directory
     * every sherpa-onnx model release archives their files under (e.g.
     * `vits-piper-en_US-ljspeech-medium-int8/model.onnx` -> `model.onnx`) so [NeuralTtsProvider]
     * can address files by a fixed, tier-specific relative path regardless of the upstream
     * archive's own folder name.
     *
     * Guards against zip-slip (a `../`-crafted entry name escaping [destDir]) by resolving every
     * entry's canonical path and refusing to write outside [destDir] — this archive comes from a
     * pinned, SHA-256-verified URL, but there is no reason to trust archive entry names blindly.
     */
    private fun extractTarBz2(archive: File, destDir: File) {
        val destCanonical = destDir.canonicalFile
        BZip2CompressorInputStream(archive.inputStream().buffered()).use { bzip2 ->
            TarArchiveInputStream(bzip2).use { tar ->
                var entry: TarArchiveEntry? = tar.nextTarEntry
                while (entry != null) {
                    if (cancelled.get()) return
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
                            outFile.outputStream().use { out -> tar.copyTo(out) }
                        }
                    }
                    entry = tar.nextTarEntry
                }
            }
        }
    }

    private fun stripTopLevelDir(entryName: String): String {
        val normalized = entryName.replace('\\', '/').trimStart('/')
        val slashIndex = normalized.indexOf('/')
        return if (slashIndex < 0) "" else normalized.substring(slashIndex + 1)
    }

    private fun hasRequiredFiles(tier: NeuralVoiceTier, dir: File): Boolean =
        requiredFilesFor(tier).all { File(dir, it).isFile } && File(dir, "espeak-ng-data").isDirectory

    companion object {
        /**
         * Piper (VITS), trained on the public-domain LJ Speech dataset — verified via the
         * archive's own `MODEL_CARD` ("License: public domain",
         * https://keithito.com/LJ-Speech-Dataset/) and independently confirmed: LJ Speech's texts
         * (public-domain works, 1884-1964) and its LibriVox audio are both public domain. Piper
         * itself (the training code/architecture) is MIT. This voice was chosen specifically
         * INSTEAD OF the more obvious "amy"/"lessac" Piper voices, whose underlying dataset (the
         * 2013 Blizzard Challenge "lessac_blizzard2013" corpus) is licensed for non-commercial
         * research only and forbids redistributing derived models — unshippable here despite
         * HuggingFace's misleading top-level "License: mit" tag on that repo (which covers the
         * exported file format, not the training data's own terms).
         */
        val PIPER_SPEC = ModelSpec(
            tier = NeuralVoiceTier.PIPER,
            url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-en_US-ljspeech-medium-int8.tar.bz2",
            sha256 = "24dc3bd77dd48c291e52c297878d3437c9492f245d823d7f6a06c4bbb67f4b6b",
            downloadSizeBytes = 21_090_429L,
            installedSizeBytes = 37_347_875L,
            licenseSummary = "Piper (MIT) voice trained on the public-domain LJ Speech dataset.",
        )

        /**
         * Kokoro-82M, int8-quantized for size — Apache 2.0. Confirmed via the full Apache-2.0
         * `LICENSE` file bundled inside this exact archive (`kokoro-int8-en-v0_19.tar.bz2`),
         * matching https://huggingface.co/hexgrad/Kokoro-82M's stated licence.
         */
        val KOKORO_SPEC = ModelSpec(
            tier = NeuralVoiceTier.KOKORO,
            url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/kokoro-int8-en-v0_19.tar.bz2",
            sha256 = "c9f0dd393615805b0bab050c340834d5e684e732aec91c0e860cd30e982c08bd",
            downloadSizeBytes = 103_248_205L,
            installedSizeBytes = 157_947_103L,
            licenseSummary = "Kokoro-82M (Apache License 2.0).",
        )

        fun specFor(tier: NeuralVoiceTier): ModelSpec = when (tier) {
            NeuralVoiceTier.PIPER -> PIPER_SPEC
            NeuralVoiceTier.KOKORO -> KOKORO_SPEC
        }

        /** Relative-to-model-dir paths [NeuralTtsProvider] loads for each tier — see [hasRequiredFiles]. */
        fun requiredFilesFor(tier: NeuralVoiceTier): List<String> = when (tier) {
            NeuralVoiceTier.PIPER -> listOf(PIPER_MODEL_FILE, PIPER_TOKENS_FILE)
            NeuralVoiceTier.KOKORO -> listOf(KOKORO_MODEL_FILE, KOKORO_VOICES_FILE, KOKORO_TOKENS_FILE)
        }

        const val PIPER_MODEL_FILE = "en_US-ljspeech-medium.onnx"
        const val PIPER_TOKENS_FILE = "tokens.txt"
        const val KOKORO_MODEL_FILE = "model.int8.onnx"
        const val KOKORO_VOICES_FILE = "voices.bin"
        const val KOKORO_TOKENS_FILE = "tokens.txt"

        private val defaultClient: OkHttpClient by lazy {
            OkHttpClient.Builder().build()
        }
    }
}
