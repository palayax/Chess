package net.palaya.chessanalyzer.data

import java.io.IOException
import net.palaya.chessanalyzer.engine.BundledNetDamagedException
import net.palaya.chessanalyzer.engine.BundledNetProvider
import net.palaya.chessanalyzer.engine.InsufficientNetStorageException
import net.palaya.chessanalyzer.video.BundledVoiceDamagedException
import net.palaya.chessanalyzer.video.BundledVoiceInstaller
import net.palaya.chessanalyzer.video.InsufficientVoiceStorageException

/** How [FirstRunSetup.ensure] ended. Deliberately not a message: the UI maps each to a string resource. */
sealed interface SetupResult {
    /** Everything the app ships with is installed and verified (possibly because it already was). */
    data object Done : SetupResult

    /** Not enough free space; [neededBytes] is what setup wanted, including a safety margin. */
    data class InsufficientStorage(val neededBytes: Long) : SetupResult

    /** The files bundled in the APK failed verification. Reinstalling the app is the only fix. */
    data class Damaged(val detail: String) : SetupResult
}

/**
 * The one-time setup that makes a fresh install usable offline: copies the Stockfish net and
 * extracts the Kokoro voice out of the APK into `filesDir` (see [BundledNetProvider] and
 * [BundledVoiceInstaller] for the how, and `docs/BUNDLED_MODELS_DESIGN.md` for the why).
 *
 * [AnalysisService] runs it after the PGN parse and before the eval-cache lookup, so a game
 * reopened from cache after an upgrade still gets the voice installed. When everything is already
 * installed [ensure] returns [SetupResult.Done] at once and reports **no progress**, so the
 * Analysing screen never flashes a setup phase on a second analysis.
 *
 * Progress is weighted by the bytes that actually have to be copied (net 98.5 MB : voice 158.2 MB
 * on a fresh install, only the missing part otherwise), is monotonic, and ends at exactly 1f.
 *
 * [freeBytes] is injectable so the low-disk path is testable without filling a real disk.
 */
class FirstRunSetup(
    private val engineController: EngineController,
    private val voiceInstaller: BundledVoiceInstaller,
    private val freeBytes: () -> Long,
) {

    /** Cheap (no hashing): whether [ensure] has anything to copy. */
    fun needsSetup(): Boolean = !engineController.isNetPresent() || !voiceInstaller.isInstalled()

    suspend fun ensure(onProgress: (Float) -> Unit = {}): SetupResult {
        val netMissing = !engineController.isNetPresent()
        val voiceMissing = !voiceInstaller.isInstalled()
        if (!netMissing && !voiceMissing) return SetupResult.Done

        val netBytes = if (netMissing) BundledNetProvider.NET_SIZE_BYTES else 0L
        val voiceBytes = if (voiceMissing) voiceInstaller.neededBytes else 0L
        val needed = netBytes + voiceBytes + SAFETY_MARGIN_BYTES
        if (freeBytes() < needed) return SetupResult.InsufficientStorage(needed)

        val total = (netBytes + voiceBytes).toFloat()
        var reported = 0f
        fun report(value: Float) {
            // Monotonic by construction, whatever order the two steps report in.
            val v = value.coerceIn(reported, 1f)
            if (v > reported || reported == 0f) {
                reported = v
                onProgress(v)
            }
        }
        report(0f)
        try {
            if (netMissing) engineController.ensureNet { f -> report(f * netBytes / total) }
            if (voiceMissing) voiceInstaller.ensureInstalled { f -> report((netBytes + f * voiceBytes) / total) }
        } catch (e: BundledNetDamagedException) {
            return SetupResult.Damaged(e.message.orEmpty())
        } catch (e: BundledVoiceDamagedException) {
            return SetupResult.Damaged(e.message.orEmpty())
        } catch (e: InsufficientNetStorageException) {
            return SetupResult.InsufficientStorage(needed)
        } catch (e: InsufficientVoiceStorageException) {
            return SetupResult.InsufficientStorage(needed)
        } catch (e: IOException) {
            if (isOutOfSpace(e)) return SetupResult.InsufficientStorage(needed)
            throw e
        }
        report(1f)
        return SetupResult.Done
    }

    companion object {
        /** Headroom on top of the files themselves: the app's own databases, caches and the export. */
        const val SAFETY_MARGIN_BYTES: Long = 32L * 1024 * 1024

        /** An `ENOSPC` anywhere in the cause chain: the disk filled up mid-copy. */
        internal fun isOutOfSpace(e: Throwable): Boolean =
            generateSequence(e) { it.cause }.any {
                val m = it.message.orEmpty()
                m.contains("ENOSPC") || m.contains("No space left on device", ignoreCase = true)
            }
    }
}
