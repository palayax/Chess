package net.palaya.chessanalyzer.video

import java.io.File

/**
 * A pluggable source of narration audio. [DeviceTtsProvider] (Android's built-in TTS — free,
 * offline, the floor) and [NeuralTtsProvider] (sherpa-onnx, on-device, the default) are the two
 * implementations today; see [NarrationCoordinator] for how a script is driven through one with a
 * mandatory fallback to [DeviceTtsProvider] on any failure.
 *
 * Deliberately provider-agnostic and free of any caching concerns of its own — those live one
 * layer up, in [NarrationCoordinator], so a provider only has to answer "can you talk right now"
 * and "synthesize this text".
 */
interface NarrationVoiceProvider {
    /** Shown in UI/notices, e.g. "Device voice", "Natural voice (Kokoro)". */
    val displayName: String

    /** Checks the provider is actually usable right now (engine/voice present, API key set, ...). */
    suspend fun prepare(): Boolean

    /** Synthesizes [text] into [outFile]. Never throws — failures come back as [SynthesisResult.Failure]. */
    suspend fun synthesize(text: String, outFile: File): SynthesisResult

    /** Releases any held resources (engine session, HTTP connections). Safe to call unconditionally. */
    fun release()
}

sealed interface SynthesisResult {
    data class Success(val file: File, val durationMs: Long) : SynthesisResult
    data class Failure(val reason: String) : SynthesisResult
}
