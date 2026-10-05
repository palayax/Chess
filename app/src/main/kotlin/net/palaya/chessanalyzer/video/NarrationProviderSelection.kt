package net.palaya.chessanalyzer.video

import net.palaya.chessanalyzer.ui.model.NarrationProviderChoice
import net.palaya.chessanalyzer.ui.model.NarrationVoiceSettings
import net.palaya.chessanalyzer.ui.model.NeuralVoiceTier

/** What `AnalysisViewModel.buildNarrationProvider()` should construct, before any Android object is touched. */
sealed interface NarrationProviderSelection {
    /** The device voice, i.e. a null provider: VideoExporter/VideoScreen take their built-in device-TTS path. */
    data object Device : NarrationProviderSelection

    data class Neural(val tier: NeuralVoiceTier) : NarrationProviderSelection
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
            settings.neuralTier.takeIf(isInstalled)?.let { NarrationProviderSelection.Neural(it) }
                ?: NarrationProviderSelection.Device
    }
}
