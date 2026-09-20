package net.palaya.chessanalyzer.video

import androidx.test.ext.junit.runners.AndroidJUnit4
import net.palaya.chessanalyzer.core.narration.cloud.GoogleCloudVoice
import net.palaya.chessanalyzer.ui.model.NarrationProviderChoice
import net.palaya.chessanalyzer.ui.model.NarrationVoiceSettings
import net.palaya.chessanalyzer.ui.model.NeuralVoiceTier
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The provider-selection rule behind `AnalysisViewModel.buildNarrationProvider()`, pinned down
 * case by case. The two properties the owner fixed are asserted explicitly: the on-device neural
 * voice is what NEURAL means whenever anything is installed, and Cloud is never reached without a
 * key — a leftover CLOUD choice with no key degrades to on-device, never to a failing provider.
 */
@RunWith(AndroidJUnit4::class)
class NarrationProviderSelectionTest {

    private val none: (NeuralVoiceTier) -> Boolean = { false }
    private val all: (NeuralVoiceTier) -> Boolean = { true }
    private val piperOnly: (NeuralVoiceTier) -> Boolean = { it == NeuralVoiceTier.PIPER }
    private val key = "AIzaSyD-not_a_real_key-0123456789abcdefg"

    @Test
    fun deviceIsDeviceRegardlessOfWhatIsInstalled() {
        val s = NarrationVoiceSettings(provider = NarrationProviderChoice.DEVICE, apiKey = key)
        assertEquals(NarrationProviderSelection.Device, selectNarrationProvider(s, all))
        assertEquals(NarrationProviderSelection.Device, selectNarrationProvider(s, none))
    }

    @Test
    fun neuralPrefersTheChosenTierThenAnyInstalledTierThenDevice() {
        val kokoro = NarrationVoiceSettings(provider = NarrationProviderChoice.NEURAL, neuralTier = NeuralVoiceTier.KOKORO)
        assertEquals(NarrationProviderSelection.Neural(NeuralVoiceTier.KOKORO), selectNarrationProvider(kokoro, all))
        assertEquals(
            "Kokoro still downloading must not drop to the robotic voice while Piper is on disk",
            NarrationProviderSelection.Neural(NeuralVoiceTier.PIPER),
            selectNarrationProvider(kokoro, piperOnly),
        )
        assertEquals(NarrationProviderSelection.Device, selectNarrationProvider(kokoro, none))
    }

    @Test
    fun cloudWithAKeyIsCloudWithTheChosenVoice() {
        val s = NarrationVoiceSettings(provider = NarrationProviderChoice.CLOUD, apiKey = key, cloudVoice = GoogleCloudVoice.HE_CHIRP3_HD_FEMALE)
        assertEquals(NarrationProviderSelection.Cloud(key, GoogleCloudVoice.HE_CHIRP3_HD_FEMALE), selectNarrationProvider(s, all))
        assertEquals("Cloud does not depend on any model being installed", NarrationProviderSelection.Cloud(key, GoogleCloudVoice.HE_CHIRP3_HD_FEMALE), selectNarrationProvider(s, none))
    }

    @Test
    fun cloudWithoutAKeyDegradesToOnDeviceNeuralThenDeviceNeverToAFailingCloudProvider() {
        val s = NarrationVoiceSettings(provider = NarrationProviderChoice.CLOUD, apiKey = "", neuralTier = NeuralVoiceTier.KOKORO)
        assertEquals(NarrationProviderSelection.Neural(NeuralVoiceTier.KOKORO), selectNarrationProvider(s, all))
        assertEquals(NarrationProviderSelection.Neural(NeuralVoiceTier.PIPER), selectNarrationProvider(s, piperOnly))
        assertEquals(NarrationProviderSelection.Device, selectNarrationProvider(s, none))
        val blank = s.copy(apiKey = "   ")
        assertEquals(NarrationProviderSelection.Device, selectNarrationProvider(blank, none))
    }
}
