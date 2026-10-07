package net.palaya.chessanalyzer.ui.model

/**
 * Which [net.palaya.chessanalyzer.video.NarrationVoiceProvider] narration should use.
 *
 * [NEURAL] (the on-device Kokoro voice, bundled in the APK) is the default; [DEVICE] is the floor
 * every fallback lands on and the user's "use the phone's built-in voice instead" choice. Everything runs locally: there is no cloud option. A Google Cloud TTS
 * provider (`CLOUD`) existed until Round 13 and was removed because it needs a billing account,
 * which breaks the owner's "free, no credit card, local only" rule.
 */
enum class NarrationProviderChoice {
    DEVICE,
    NEURAL;

    companion object {
        /**
         * Reads a persisted provider name, tolerating anything this build no longer knows. The
         * removed `CLOUD` value (written by pre-Round-13 builds that had the Google Cloud voice),
         * a corrupt string or a missing value all come back as null; callers fall back to
         * [NEURAL], the default. Never throws — an old DataStore value must not be able to crash settings.
         */
        fun fromPersistedOrNull(name: String?): NarrationProviderChoice? =
            name?.let { n -> entries.firstOrNull { it.name == n } }
    }
}

/**
 * The on-device neural voice [NarrationProviderChoice.NEURAL] uses. It runs fully offline via
 * sherpa-onnx (Apache 2.0) from a model downloaded once on first run; see
 * [net.palaya.chessanalyzer.video.VoiceStore] for how it reaches app storage and
 * [net.palaya.chessanalyzer.video.NeuralTtsProvider] for how it is turned into audio.
 *
 * Kokoro is the only tier. Piper, the smaller voice that existed for the metered-network fallback,
 * was dropped with the model downloads (Round 13); a persisted `neural_voice_tier = PIPER` from an
 * older build no longer parses and resolves to [KOKORO].
 */
enum class NeuralVoiceTier(
    val label: String,
    val qualityHint: String,
    /**
     * Which of the model's built-in speakers to synthesize with. Kokoro v0.19 ships **11** English speakers (ids 0-10, see
     * [net.palaya.chessanalyzer.video.KokoroVoices]) and defaulting to 0 would be an accident
     * rather than a choice — see [KOKORO_DEFAULT_SPEAKER_ID] for why this one.
     */
    val speakerId: Int,
    /**
     * sherpa-onnx `length_scale`: **higher is slower**. 1.0 is each model's own trained pace.
     *
     * Kokoro's own pace is materially faster than Piper's, which is why this is not 1.0. Measured
     * on the same four-sentence chess paragraph through the real per-sentence pipeline (the full
     * sweep, with audio, is in `docs/voice_samples/`):
     *
     * | `length_scale` | duration | effective wpm |
     * |---|---|---|
     * | 0.90 | 11.22 s | 210 |
     * | 1.00 | 12.01 s | 195 |
     * | 1.10 | 12.59 s | 185 |
     * | **1.20** | **13.74 s** | **169** |
     * | 1.30 | 15.82 s | 145 |
     *
     * 1.20 is picked because it lands the Kokoro default on 169 wpm, within 3% of the **165 wpm
     * the Piper baseline measures at** — a pace already accepted for this narration. 1.10 leaves
     * it at 185 wpm, which is rushed for text dense with coordinates and percentages; 1.30 drops
     * to 145 wpm and drags. The choice is the nearest measured point to a known-good pace, not a
     * preference.
     */
    val lengthScale: Float,
    /**
     * Words per minute this tier actually speaks at **at its configured [lengthScale]**, measured
     * (see `docs/voice_samples/measurements.txt`), for `NarrationOptions.speechWpm`.
     *
     * Not a raw speaking rate: it is inverted from `VideoScriptGenerator.estimateSpeechMs`'s own
     * formula (`words * 60000 / wpm + sentences * 240`) so it can be substituted straight in. The
     * timeline builder estimates segment durations with it before TTS runs, so a wrong value
     * desyncs board animation from speech.
     */
    val measuredWpm: Int,
) {
    /** Kokoro-82M, Apache 2.0 — larger, slower, noticeably more natural. */
    KOKORO(
        "Kokoro",
        "Best quality",
        speakerId = KOKORO_DEFAULT_SPEAKER_ID,
        lengthScale = 1.2f,
        measuredWpm = 169,
    ),
}

/**
 * Kokoro v0.19 speaker `af_bella`. Chosen deliberately, not by defaulting to 0 (which is `af`,
 * an averaged mix): upstream's own published voice table grades `af_bella` **A-**, the highest of
 * the eleven, with every other voice at B- or below and the two American male voices at C+/F+
 * (https://huggingface.co/hexgrad/Kokoro-82M/blob/main/VOICES.md). `docs/voice_samples/` holds a
 * rendered sample of all eleven plus the Piper baseline so the owner can overrule this by ear —
 * changing this constant is the whole change needed.
 */
const val KOKORO_DEFAULT_SPEAKER_ID: Int = 1

/**
 * User-facing narration-voice preferences (Settings screen). Nothing here is sensitive; it all
 * lives in plain DataStore (see [net.palaya.chessanalyzer.data.NarrationSettingsRepository]).
 *
 * Whether the voice is installed is deliberately NOT a field here: it is runtime storage state,
 * re-derived from [net.palaya.chessanalyzer.video.VoiceStore], not a persisted preference.
 */
data class NarrationVoiceSettings(
    val provider: NarrationProviderChoice = NarrationProviderChoice.NEURAL,
    /** Kokoro, the only tier. Kept as a field so a persisted older value still has somewhere to resolve. */
    val neuralTier: NeuralVoiceTier = NeuralVoiceTier.KOKORO,
    /**
     * True once the user has picked a narration provider themselves in Settings, including picking
     * Device (the "use the phone's built-in voice instead" switch). [provider] alone cannot express
     * this when both values are reachable defaults of an older build.
     */
    val providerExplicitlyChosen: Boolean = false,
    /**
     * Which Kokoro speaker narrates (V1, Settings, Video, "Narrator voice"): a sid of
     * [net.palaya.chessanalyzer.video.KokoroVoices.SPEAKERS]. Defaults to [KOKORO_DEFAULT_SPEAKER_ID]; an
     * unknown stored value reads as the default. Used by the in-app player and the MP4 alike, and part of
     * the narration cache key ([net.palaya.chessanalyzer.video.NeuralTtsProvider.cacheFingerprint]), so a
     * switch never plays another speaker's cached audio. The update trial (D2e) keeps using the default.
     */
    val speakerId: Int = KOKORO_DEFAULT_SPEAKER_ID,
)

