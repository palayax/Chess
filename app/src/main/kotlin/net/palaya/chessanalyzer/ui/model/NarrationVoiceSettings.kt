package net.palaya.chessanalyzer.ui.model

import net.palaya.chessanalyzer.core.narration.cloud.GoogleCloudVoice

/**
 * Which [net.palaya.chessanalyzer.video.NarrationVoiceProvider] narration should use.
 *
 * [NEURAL] (on-device Kokoro/Piper) is the intended default; [DEVICE] is the floor every
 * fallback lands on; [CLOUD] is Google Cloud Text-to-Speech with the user's own API key, an opt-in
 * upgrade that is never selected automatically and never a prerequisite for anything.
 */
enum class NarrationProviderChoice { DEVICE, NEURAL, CLOUD }

/**
 * Which on-device neural voice model tier [NarrationProviderChoice.NEURAL] uses. Both run fully
 * offline via sherpa-onnx (Apache 2.0) once downloaded — see
 * [net.palaya.chessanalyzer.video.VoiceModelProvisioner] for the pinned URL/SHA-256/size of each
 * and [net.palaya.chessanalyzer.video.NeuralTtsProvider] for how a tier is turned into audio.
 */
enum class NeuralVoiceTier(
    val label: String,
    val qualityHint: String,
    /**
     * Which of the model's built-in speakers to synthesize with. Piper's
     * `en_US-ljspeech-medium` is single-speaker, so 0 is the only valid value there. Kokoro
     * v0.19 ships **11** English speakers (ids 0-10, see
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
    /** Piper (VITS) trained on the public-domain LJ Speech dataset — smaller, faster. */
    PIPER("Piper", "Smaller, faster", speakerId = 0, lengthScale = 1.0f, measuredWpm = 165),

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
 * User-facing narration-voice preferences (Settings screen). The provider/tier choice is not
 * sensitive and lives in plain DataStore; [apiKey] is the one field that matters for security —
 * see [net.palaya.chessanalyzer.data.NarrationSettingsRepository] for how it's actually stored
 * (Keystore-encrypted, never logged, never compiled into the app).
 *
 * Whether a [neuralTier]'s model is actually present on disk is deliberately NOT a field here —
 * that is runtime storage state, re-derived cheaply from [net.palaya.chessanalyzer.video.VoiceModelProvisioner]
 * whenever Settings opens, the same way [net.palaya.chessanalyzer.video.NarrationStore]'s size is
 * (see `AnalysisViewModel.narrationStorageBytes`) — not a persisted preference.
 */
data class NarrationVoiceSettings(
    val provider: NarrationProviderChoice = NarrationProviderChoice.DEVICE,
    /**
     * Kokoro is the intended default voice — it is the whole reason the neural tier exists. A
     * device that only has Piper installed (metered connection, or a user who downloaded Piper
     * by hand) still narrates with Piper: `AnalysisViewModel.buildNarrationProvider()` falls back
     * to whichever tier is actually on disk rather than dropping to the robotic device voice.
     */
    val neuralTier: NeuralVoiceTier = NeuralVoiceTier.KOKORO,
    /**
     * The user's own Google Cloud API key for [NarrationProviderChoice.CLOUD], held in
     * Keystore-backed encrypted storage. Empty until the setup wizard has validated and saved one.
     * The security design (user-supplied, never embedded in the APK, never logged, encrypted at
     * rest) is the whole reason a cloud voice can be offered at all.
     */
    val apiKey: String = "",
    /** Which Google Cloud voice [NarrationProviderChoice.CLOUD] speaks with. */
    val cloudVoice: GoogleCloudVoice = GoogleCloudVoice.DEFAULT,
    /** Whether the API key is sitting in Keystore-backed encrypted storage right now. */
    val apiKeyIsEncrypted: Boolean = true,
    /**
     * True once the user has picked a narration provider themselves in Settings — including
     * picking Device again. [provider] alone cannot express this: its default IS [DEVICE], so
     * "still on the default" and "deliberately chose Device" are indistinguishable without this
     * flag, and the automatic promotion to the neural voice would happily override a deliberate
     * choice. Persisted, so the promotion can only ever happen to a user who never expressed one.
     */
    val providerExplicitlyChosen: Boolean = false,
) {
    /** True once a Cloud key has been saved — the gate on selecting [NarrationProviderChoice.CLOUD] at all. */
    val hasCloudKey: Boolean get() = apiKey.isNotBlank()
}

/**
 * Runtime (not persisted) state of the on-device neural voice models — which tiers are actually
 * downloaded on THIS device right now, how much disk each uses, and any in-flight download. Kept
 * separate from [NarrationVoiceSettings] the same way `AnalysisViewModel.narrationStorageBytes`
 * is kept separate from it: this is storage state re-derived from
 * [net.palaya.chessanalyzer.video.VoiceModelProvisioner], not a user preference.
 */
data class NeuralModelUiState(
    val installedTiers: Set<NeuralVoiceTier> = emptySet(),
    val installedSizeBytes: Map<NeuralVoiceTier, Long> = emptyMap(),
    val downloadingTier: NeuralVoiceTier? = null,
    val downloadProgress: Float = 0f,
    val lastError: String? = null,
) {
    fun isInstalled(tier: NeuralVoiceTier): Boolean = tier in installedTiers
}
