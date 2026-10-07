package net.palaya.chessanalyzer.video

import net.palaya.chessanalyzer.core.narration.NarrationOptions
import net.palaya.chessanalyzer.ui.model.EngineSettings
import net.palaya.chessanalyzer.ui.model.NarrationProviderChoice
import net.palaya.chessanalyzer.ui.model.NarrationVoiceSettings
import net.palaya.chessanalyzer.ui.model.NeuralVoiceTier

/** What `AnalysisViewModel.buildNarrationProvider()` should construct, before any Android object is touched. */
sealed interface NarrationProviderSelection {
    /** The device voice, i.e. a null provider: VideoExporter/VideoScreen take their built-in device-TTS path. */
    data object Device : NarrationProviderSelection

    /** The neural voice, spoken by [speakerId] (V1; the settings' speaker, default [NeuralVoiceTier.speakerId]). */
    data class Neural(val tier: NeuralVoiceTier, val speakerId: Int = tier.speakerId) : NarrationProviderSelection
}

/**
 * The one readable rule for which narration provider the current settings select — pure, so it
 * is testable without a ViewModel, a DataStore or a model on disk:
 *
 *  - **NEURAL**: the voice if it is installed, else Device (an interrupted install must not leave
 *    narration silent).
 *  - **DEVICE**: Device.
 */
fun selectNarrationProvider(
    settings: NarrationVoiceSettings,
    isInstalled: (NeuralVoiceTier) -> Boolean,
): NarrationProviderSelection {
    return when (settings.provider) {
        NarrationProviderChoice.DEVICE -> NarrationProviderSelection.Device
        NarrationProviderChoice.NEURAL ->
            settings.neuralTier.takeIf(isInstalled)?.let { NarrationProviderSelection.Neural(it, settings.speakerId) }
                ?: NarrationProviderSelection.Device
    }
}

/**
 * The [NarrationOptions] a script is built with, from the persisted settings (pure, host-tested):
 *
 *  - `speechWpm`: the measured rate of the voice that will speak. The chosen Kokoro speaker's
 *    ([KokoroVoices.Speaker.measuredWpm], V1) when the natural voice is chosen and installed, otherwise the
 *    device voice's default. The timeline and the length budget are estimated with it before any audio exists.
 *  - `significanceThresholdCp`: "What the review talks about".
 *  - `pace`: Settings, Video, Pace (V3, ANALYSIS_SPEC 9.8). It is baked into the script, so the in-app
 *    player and the MP4 exporter, which both play the script, get the same pace.
 */
fun narrationOptionsFor(
    voice: NarrationVoiceSettings,
    voiceInstalled: Boolean,
    settings: EngineSettings,
): NarrationOptions {
    val neural = voice.provider == NarrationProviderChoice.NEURAL && voiceInstalled
    val wpm = if (neural) {
        KokoroVoices.speaker(voice.speakerId)?.measuredWpm ?: voice.neuralTier.measuredWpm
    } else {
        NarrationOptions().speechWpm
    }
    return NarrationOptions(
        speechWpm = wpm,
        significanceThresholdCp = settings.narrationThresholdCp,
        pace = settings.videoPace,
    )
}
