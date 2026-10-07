package net.palaya.chessanalyzer.video

import android.media.MediaPlayer
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.palaya.chessanalyzer.ui.model.NeuralVoiceTier

/**
 * "Play sample" in the narrator voice picker (V1): synthesizes [SAMPLE_TEXT] on the device with the
 * chosen Kokoro speaker and plays it. One sample sounds at a time: starting another stops the one
 * playing (and abandons one still being prepared). Samples are cached in [cacheDir] under a name made
 * of the voice files' id, the speaker, the `length_scale` and the text, so a second tap plays at once
 * and a voice update can never play the old voice's sample.
 *
 * Kokoro is loaded once, lazily, on the first tap (a few seconds), with the default speaker; every
 * sample goes through [NeuralTtsProvider.synthesizeAs] on that one engine. [release] frees it.
 */
class VoiceSamplePlayer(
    private val cacheDir: File,
    private val modelDir: () -> File,
    /** The installed voice files' id (`VoiceStore.installedVersionId()`); null while nothing is installed. */
    private val voiceVersionId: () -> String?,
    private val scope: CoroutineScope,
) {
    sealed interface State {
        data object Idle : State
        data class Preparing(val sid: Int) : State
        data class Playing(val sid: Int) : State
        data class Failed(val sid: Int) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private var provider: NeuralTtsProvider? = null
    private var job: Job? = null
    private var player: MediaPlayer? = null

    /** Plays [sid]'s sample, stopping whatever sample is playing or being prepared. */
    fun play(sid: Int) {
        stop()
        val version = voiceVersionId()
        if (version == null || !KokoroVoices.isKnown(sid)) {
            _state.value = State.Failed(sid)
            return
        }
        _state.value = State.Preparing(sid)
        job = scope.launch {
            val file = withContext(Dispatchers.IO) { sampleFile(version, sid).takeIf { it.isFile && it.length() > 44 } }
                ?: synthesize(version, sid)
            if (file == null) {
                _state.value = State.Failed(sid)
                return@launch
            }
            startPlayback(sid, file)
        }
    }

    /** Stops the sample playing (or being prepared). Safe to call at any time. */
    fun stop() {
        job?.cancel()
        job = null
        player?.let { p ->
            runCatching { if (p.isPlaying) p.stop() }
            runCatching { p.release() }
        }
        player = null
        _state.value = State.Idle
    }

    /** Stops and frees the Kokoro engine (the picker closed). */
    fun release() {
        stop()
        provider?.release()
        provider = null
    }

    /** The cached sample for [sid] of the voice [version]; the name changes with anything that changes the audio. */
    fun sampleFile(version: String, sid: Int): File {
        val tier = NeuralVoiceTier.KOKORO
        val key = "${tier.name}@$version/sid$sid/ls${"%.2f".format(java.util.Locale.ROOT, tier.lengthScale)}/$SAMPLE_TEXT"
        val digest = MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }.take(24)
        return File(File(cacheDir, DIR), "sample_${sid}_$digest.wav")
    }

    private suspend fun synthesize(version: String, sid: Int): File? {
        val engine = provider ?: NeuralTtsProvider(NeuralVoiceTier.KOKORO, modelDir(), voiceVersionId = version).also { provider = it }
        if (!engine.prepare()) return null
        val out = sampleFile(version, sid)
        withContext(Dispatchers.IO) { out.parentFile?.mkdirs() }
        val tmp = File(out.parentFile, out.name + ".part")
        return when (val r = engine.synthesizeAs(sid, SAMPLE_TEXT, tmp)) {
            is SynthesisResult.Success -> withContext(Dispatchers.IO) { if (tmp.renameTo(out)) out else null }
            is SynthesisResult.Failure -> {
                withContext(Dispatchers.IO) { tmp.delete() }
                null
            }
        }
    }

    private fun startPlayback(sid: Int, file: File) {
        try {
            val p = MediaPlayer()
            p.setDataSource(file.absolutePath)
            p.setOnCompletionListener {
                if (player === it) {
                    runCatching { it.release() }
                    player = null
                    _state.value = State.Idle
                }
            }
            p.setOnErrorListener { _, _, _ ->
                _state.value = State.Failed(sid)
                true
            }
            p.prepare()
            p.start()
            player = p
            _state.value = State.Playing(sid)
        } catch (e: Exception) {
            _state.value = State.Failed(sid)
        }
    }

    companion object {
        private const val DIR = "voice_samples"

        /**
         * The one sentence every voice says (V1): squares read aloud, a check and an exclamation, the
         * things a voice has to get right in this app. Our own words.
         */
        const val SAMPLE_TEXT = "Knight to f seven, check! The king must move, and the queen on d eight is lost."
    }
}
