package net.palaya.chessanalyzer.video

import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import net.palaya.chessanalyzer.ui.model.NeuralVoiceTier

/**
 * On-device neural narration via sherpa-onnx (Apache 2.0, k2-fsa) — the strictly-better-than-
 * [DeviceTtsProvider] default, with its model downloaded once on first run and unpacked (see
 * [VoiceStore]); afterwards free, fully offline, no per-user API key, no redistribution licensing
 * question. Slots into
 * [NarrationCoordinator] behind the same [NarrationVoiceProvider] interface as
 * [DeviceTtsProvider].
 *
 * The narration pipeline this feeds ([NarrationCoordinator]/[NarrationStore]) always
 * pre-synthesizes a whole script ahead of playback/export rather than speaking in real time, so
 * this deliberately does not try to be the fastest option: Kokoro's slower, more natural model is
 * exactly as usable here as a faster one would be.
 *
 * [modelDir] must already be a verified, fully-extracted model directory (see
 * [VoiceStore.installFromTar]/[VoiceStore.isInstalled]) — this class only
 * loads and runs it, it never installs anything. [prepare] returns false (never throws) when the
 * directory or its expected files are missing, which sends [NarrationCoordinator] straight to its
 * mandatory device-voice fallback with no silent gap.
 */
class NeuralTtsProvider(
    private val tier: NeuralVoiceTier,
    private val modelDir: File,
    /**
     * Which of the model's speakers to use — defaults to the tier's deliberate choice (see
     * [NeuralVoiceTier.speakerId]). The app passes the user's "Narrator voice" (V1,
     * `NarrationVoiceSettings.speakerId`); the update trial (D2e, [NeuralVoiceTrial]) keeps the default.
     */
    private val speakerId: Int = tier.speakerId,
    /** sherpa-onnx `length_scale` — higher is slower. See [NeuralVoiceTier.lengthScale]. */
    private val lengthScale: Float = tier.lengthScale,
    /**
     * Which voice files these are: `VoiceStore.installedVersionId()` (the first 12 hex of the installed
     * tar's SHA-256), part of [cacheFingerprint] since D2e so audio from one voice can never be served for
     * another after an update. Null (tests, a directory that is not the installed voice) reads "unknown".
     */
    private val voiceVersionId: String? = null,
) : NarrationVoiceProvider {

    override val displayName: String = "Natural voice (${tier.label})"

    /**
     * Cache-key fingerprint. The tier alone is not enough: the same text through the same tier at
     * a different speaker id or `length_scale` is *different audio*, so leaving those out would
     * serve stale clips from [NarrationStore] after the default voice or pacing is retuned. The voice
     * files' id ([voiceVersionId]) is in it too (D2e): an updated voice never reuses the old voice's WAVs.
     * So is the id of the spoken respelling table ([SpokenRespelling.tableId], C1-device): the cache key stays
     * the real sentence, and an edit to the table moves it, so audio made from an old spelling is never reused.
     */
    val cacheFingerprint: String get() =
        "${tier.name}@${voiceVersionId ?: "unknown"}/sid$speakerId/ls${"%.2f".format(java.util.Locale.ROOT, lengthScale)}/pr${SpokenRespelling.tableId}"

    /** Speakers the loaded model reports — 11 for Kokoro v0.19. -1 until [prepare]. */
    val speakerCount: Int get() = tts?.numSpeakers() ?: -1

    /** Native output rate of the loaded model — 24000 for Kokoro. -1 until [prepare]. */
    val modelSampleRate: Int get() = tts?.sampleRate() ?: -1

    private val lock = Mutex()
    @Volatile private var tts: OfflineTts? = null
    @Volatile private var prepareFailed = false

    override suspend fun prepare(): Boolean = withContext(Dispatchers.IO) {
        lock.withLock {
            val existing = tts
            if (existing != null) return@withLock true
            if (prepareFailed) return@withLock false
            if (!VoiceStore.requiredFilesFor(tier).all { File(modelDir, it).isFile }) {
                prepareFailed = true
                return@withLock false
            }
            // espeak-ng-data is what phonemizes the text; a model directory without it loads and
            // then produces nothing. Checked here, not just in the provisioner, because prepare()
            // is also reached with a directory this process did not install.
            if (!File(modelDir, ESPEAK_DATA_DIR).isDirectory) {
                prepareFailed = true
                return@withLock false
            }
            try {
                val engine = OfflineTts(assetManager = null, config = buildConfig(tier, modelDir, lengthScale))
                if (speakerId !in 0 until engine.numSpeakers().coerceAtLeast(1)) {
                    // A speaker id the model does not have is a configuration bug, and sherpa-onnx
                    // answers it with silence rather than an error — exactly the valid-but-silent
                    // WAV this project has been burned by. Refuse to load instead.
                    android.util.Log.e(
                        TAG,
                        "$tier model reports ${engine.numSpeakers()} speaker(s); requested sid=$speakerId is out of range",
                    )
                    engine.release()
                    prepareFailed = true
                    return@withLock false
                }
                android.util.Log.i(
                    TAG,
                    "loaded $tier: speakers=${engine.numSpeakers()} sampleRate=${engine.sampleRate()} " +
                        "sid=$speakerId lengthScale=$lengthScale dir=${modelDir.absolutePath}",
                )
                tts = engine
                true
            } catch (e: Exception) {
                android.util.Log.e(TAG, "failed to load $tier model from ${modelDir.absolutePath}", e)
                prepareFailed = true
                false
            }
        }
    }

    /** The text last handed to the engine (after any respelling); tests only, to prove what the voice was given. */
    @Volatile internal var lastEngineText: String? = null
        private set

    /**
     * The narration path: [text] is the real sentence (the one on screen and in the cache key); the engine is
     * given [SpokenRespelling.apply] of it, so a term espeak-ng reads wrongly is said right ("zwischenzug",
     * "en prise"). [synthesizeAs] takes its text as given.
     */
    override suspend fun synthesize(text: String, outFile: File): SynthesisResult =
        synthesizeAs(speakerId, SpokenRespelling.apply(text), outFile)

    /**
     * [synthesize] with another of the loaded model's speakers. The model holds all eleven, so the voice
     * picker's "Play sample" (V1, [VoiceSamplePlayer]) loads Kokoro once and renders any speaker with it.
     * A sid outside the loaded model fails instead of returning sherpa-onnx's silence.
     */
    suspend fun synthesizeAs(sid: Int, text: String, outFile: File): SynthesisResult = withContext(Dispatchers.IO) {
        lastEngineText = text
        if (text.isBlank()) return@withContext SynthesisResult.Failure("empty narration")
        val engine = tts ?: return@withContext SynthesisResult.Failure("neural voice model not loaded")
        if (sid !in 0 until engine.numSpeakers().coerceAtLeast(1)) {
            return@withContext SynthesisResult.Failure("speaker $sid is not in the loaded model")
        }
        try {
            // speed is left at 1.0: pacing is controlled by the model config's length_scale (see
            // [NeuralVoiceTier.lengthScale]) so it is part of the loaded config — and therefore
            // part of [cacheFingerprint] — rather than a per-call argument the cache cannot see.
            val audio = lock.withLock { engine.generate(text = text, sid = sid, speed = 1.0f) }
            if (audio.samples.isEmpty() || audio.sampleRate <= 0) {
                return@withContext SynthesisResult.Failure("sherpa-onnx returned no audio")
            }
            writeWav(audio.samples, audio.sampleRate, outFile)
            val info = WavUtil.readHeader(outFile)
            when {
                info == null || info.durationMs <= 0L -> {
                    outFile.delete()
                    SynthesisResult.Failure("sherpa-onnx output was not a readable WAV")
                }
                else -> SynthesisResult.Success(outFile, info.durationMs)
            }
        } catch (e: Exception) {
            SynthesisResult.Failure("sherpa-onnx: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    /** Releases the native engine. Safe to call unconditionally, including when never prepared. */
    override fun release() {
        tts?.release()
        tts = null
    }

    /**
     * [com.k2fsa.sherpa.onnx.GeneratedAudio.samples] are floats normalized to [-1, 1] at whatever
     * rate the model emits (24000 Hz for Kokoro) — converted to 16-bit
     * PCM and wrapped via [WavUtil.buildWavHeader] so downstream (sentence-stitching, AAC export,
     * MediaPlayer playback) sees the exact same WAV shape every other provider produces.
     * [WavUtil.readAsMono16] already resamples to whatever rate the export track needs, so no
     * resampling happens here.
     */
    private fun writeWav(samples: FloatArray, sampleRate: Int, outFile: File) {
        val dataSize = samples.size * 2
        val pcmBuffer = ByteBuffer.allocate(dataSize).order(ByteOrder.LITTLE_ENDIAN)
        for (sample in samples) {
            val clamped = sample.coerceIn(-1f, 1f)
            val intSample = (clamped * Short.MAX_VALUE).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            pcmBuffer.putShort(intSample.toShort())
        }
        outFile.outputStream().use { out ->
            out.write(WavUtil.buildWavHeader(dataSize, sampleRate, channels = 1, bitsPerSample = 16))
            out.write(pcmBuffer.array())
        }
    }

    companion object {
        private const val TAG = "NeuralTtsProvider"

        /** Phonemizer data directory, present in the upstream archive under this name. */
        const val ESPEAK_DATA_DIR = "espeak-ng-data"

        /**
         * Kokoro gets no `lexicon`/`dictDir`/`lang`: `kokoro-int8-en-v0_19` ships no lexicon
         * file at all (verified by listing the archive: `model.int8.onnx`, `voices.bin`,
         * `tokens.txt`, `espeak-ng-data/`, `LICENSE`, `README.md`), so English is phonemized
         * entirely from `espeak-ng-data`. This matches upstream's own documented invocation for
         * this exact model, which passes only `--kokoro-model/-voices/-tokens/-data-dir`. The
         * `lexicon`/`lang` fields exist for the multi-lingual `kokoro-multi-lang-v1_*` models.
         */
        private fun buildConfig(tier: NeuralVoiceTier, modelDir: File, lengthScale: Float): OfflineTtsConfig {
            val dir = modelDir.absolutePath
            val dataDir = "$dir/$ESPEAK_DATA_DIR"
            return when (tier) {
                NeuralVoiceTier.KOKORO -> OfflineTtsConfig(
                    model = OfflineTtsModelConfig(
                        kokoro = OfflineTtsKokoroModelConfig(
                            model = "$dir/${VoiceStore.KOKORO_MODEL_FILE}",
                            voices = "$dir/${VoiceStore.KOKORO_VOICES_FILE}",
                            tokens = "$dir/${VoiceStore.KOKORO_TOKENS_FILE}",
                            dataDir = dataDir,
                            lengthScale = lengthScale,
                        ),
                        numThreads = 4,
                        debug = false,
                        provider = "cpu",
                    ),
                )
            }
        }
    }
}
