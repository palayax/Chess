package net.palaya.chessanalyzer.video

import net.palaya.chessanalyzer.core.narration.cloud.GoogleCloudVoice
import net.palaya.chessanalyzer.ui.model.NarrationProviderChoice
import net.palaya.chessanalyzer.ui.model.NarrationVoiceSettings
import net.palaya.chessanalyzer.ui.model.NeuralVoiceTier

/** What `AnalysisViewModel.buildNarrationProvider()` should construct, before any Android object is touched. */
sealed interface NarrationProviderSelection {
    /** The device voice, i.e. a null provider: VideoExporter/VideoScreen take their built-in device-TTS path. */
    data object Device : NarrationProviderSelection

    data class Neural(val tier: NeuralVoiceTier) : NarrationProviderSelection

    data class Cloud(val apiKey: String, val voice: GoogleCloudVoice) : NarrationProviderSelection
}

/**
 * The one readable rule for which narration provider the current settings select — pure, so it
 * is testable without a ViewModel, a DataStore or a model on disk:
 *
 *  - **NEURAL**: the selected tier if installed, else *any* installed tier (a device that only has
 *    Piper while Kokoro is still downloading must not drop to the robotic voice), else Device.
 *  - **CLOUD**: only with a saved key. A user who removed their key but left Cloud selected gets
 *    the same treatment as NEURAL-without-a-model — the on-device fallback in this exact order,
 *    neural if anything is installed, device otherwise — rather than a provider that would fail
 *    every sentence. Cloud is never a prerequisite.
 *  - **DEVICE**: Device.
 */
fun selectNarrationProvider(
    settings: NarrationVoiceSettings,
    isInstalled: (NeuralVoiceTier) -> Boolean,
): NarrationProviderSelection {
    fun installedTierPreferring(preferred: NeuralVoiceTier): NeuralVoiceTier? =
        preferred.takeIf(isInstalled) ?: NeuralVoiceTier.entries.firstOrNull(isInstalled)

    return when (settings.provider) {
        NarrationProviderChoice.DEVICE -> NarrationProviderSelection.Device
        NarrationProviderChoice.NEURAL ->
            installedTierPreferring(settings.neuralTier)?.let { NarrationProviderSelection.Neural(it) }
                ?: NarrationProviderSelection.Device
        NarrationProviderChoice.CLOUD ->
            if (settings.hasCloudKey) {
                NarrationProviderSelection.Cloud(settings.apiKey, settings.cloudVoice)
            } else {
                installedTierPreferring(settings.neuralTier)?.let { NarrationProviderSelection.Neural(it) }
                    ?: NarrationProviderSelection.Device
            }
    }
}
