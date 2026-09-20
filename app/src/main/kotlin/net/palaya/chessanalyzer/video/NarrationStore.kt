package net.palaya.chessanalyzer.video

import android.content.Context
import java.io.File
import java.security.MessageDigest

/**
 * Persistent, pruneable store for narration audio that has already been synthesized.
 *
 * Lives under [Context.filesDir] (`filesDir/narration/` via [forApp]) — **never** [Context.cacheDir].
 * `cacheDir` is fair game for Android to evict under storage pressure with no user-visible warning,
 * which would silently throw away audio that cost the user minutes of on-device synthesis to
 * produce. `filesDir` is never auto-cleared by the OS; it only goes away if the user explicitly
 * clears app storage/data (a deliberate, informed action) or calls [clear] here from Settings.
 *
 * Content-addressed by [keyFor] — a hash of (text, provider display name, provider fingerprint) —
 * so the same entry point serves two granularities the caller chooses by what it hashes:
 *  - **per-sentence** keys (one WAV per sentence) let [NarrationCoordinator] reuse boilerplate
 *    narration text ACROSS different games/scripts, since a lot of the script is deterministic
 *    template text ("White to play. Pause the video. Can you find it?", chapter scaffolding, ...).
 *  - **per-segment** keys (the segment's whole narration text) let playback
 *    ([net.palaya.chessanalyzer.ui.video.VideoPlayerController]) and export find one ready-to-play
 *    file per segment without re-deriving it from sentence pieces every time.
 *
 * Both live side by side in the same flat directory — they're just different hash inputs over the
 * same content-addressed store, so a segment made of a single sentence naturally dedups to one
 * file instead of two.
 */
class NarrationStore private constructor(val dir: File) {

    init {
        dir.mkdirs()
    }

    /** Hash key for a piece of narration text under a given provider identity. */
    fun keyFor(text: String, providerDisplayName: String, providerFingerprint: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(providerDisplayName.toByteArray(Charsets.UTF_8))
        digest.update(0.toByte())
        digest.update(providerFingerprint.toByteArray(Charsets.UTF_8))
        digest.update(0.toByte())
        digest.update(text.toByteArray(Charsets.UTF_8))
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun fileFor(key: String): File = File(dir, "$key.wav")

    /** The cached file for [key], or null if absent or not a readable, non-empty WAV. */
    fun get(key: String): File? {
        val file = fileFor(key)
        if (!file.isFile) return null
        val info = WavUtil.readHeader(file)
        return if (info != null && info.durationMs > 0) file else null
    }

    /** Adopts [sourceFile]'s bytes into the store under [key], returning the now-persistent file. */
    fun put(key: String, sourceFile: File): File {
        val dest = fileFor(key)
        if (dest.canonicalPath != sourceFile.canonicalPath) {
            sourceFile.copyTo(dest, overwrite = true)
        }
        return dest
    }

    /** Total bytes currently held — what Settings shows as "Narration audio: N MB". */
    fun totalSizeBytes(): Long = dir.listFiles()?.sumOf { it.length() } ?: 0L

    /** Deletes every cached entry. The user's own explicit "Clear" action — never called silently. */
    fun clear(): Boolean {
        val files = dir.listFiles() ?: return true
        var allDeleted = true
        for (file in files) {
            if (!file.delete()) allDeleted = false
        }
        return allDeleted
    }

    companion object {
        private const val DIR_NAME = "narration"

        /** The one store the whole app should share — always `filesDir/narration`. */
        fun forApp(context: Context): NarrationStore =
            NarrationStore(File(context.applicationContext.filesDir, DIR_NAME))

        /** For tests that want an isolated store while still proving it lives under `filesDir`. */
        fun forDirectory(dir: File): NarrationStore = NarrationStore(dir)
    }
}

/**
 * The cache-fingerprint to combine with [NarrationVoiceProvider.displayName] when hashing a
 * [NarrationStore] key — the extra axis a provider may have along which identical text sounds
 * different, which a provider with no such axis (device TTS) doesn't need. [NeuralTtsProvider] has
 * one: [NeuralTtsProvider.displayName] already differs per tier (see its doc), but hashing the
 * tier and speaker explicitly too keeps this future-proof against that display string ever
 * changing without the cache key silently colliding across tiers.
 */
fun NarrationVoiceProvider.narrationCacheFingerprint(): String = when (this) {
    is NeuralTtsProvider -> cacheFingerprint
    is GoogleCloudTtsProvider -> cacheFingerprint
    else -> "default"
}
