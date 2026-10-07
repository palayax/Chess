package net.palaya.chessanalyzer.video

import androidx.test.ext.junit.runners.AndroidJUnit4
import net.palaya.chessanalyzer.ui.model.NarrationProviderChoice
import net.palaya.chessanalyzer.ui.model.NarrationVoiceSettings
import net.palaya.chessanalyzer.ui.model.NeuralVoiceTier
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The provider-selection rule behind `AnalysisViewModel.buildNarrationProvider()`, pinned down
 * case by case: NEURAL means the bundled Kokoro voice whenever it is installed, and DEVICE is the
 * floor when it is not (an interrupted install must not leave narration silent). There is no cloud
 * provider and no second voice tier any more.
 */
@RunWith(AndroidJUnit4::class)
class NarrationProviderSelectionTest {

    private val none: (NeuralVoiceTier) -> Boolean = { false }
    private val all: (NeuralVoiceTier) -> Boolean = { true }

    @Test
    fun deviceIsDeviceRegardlessOfWhatIsInstalled() {
        val s = NarrationVoiceSettings(provider = NarrationProviderChoice.DEVICE)
        assertEquals(NarrationProviderSelection.Device, selectNarrationProvider(s, all))
        assertEquals(NarrationProviderSelection.Device, selectNarrationProvider(s, none))
    }

    @Test
    fun neuralIsKokoroWhenInstalledAndDeviceOtherwise() {
        val kokoro = NarrationVoiceSettings(provider = NarrationProviderChoice.NEURAL, neuralTier = NeuralVoiceTier.KOKORO)
        assertEquals(NarrationProviderSelection.Neural(NeuralVoiceTier.KOKORO), selectNarrationProvider(kokoro, all))
        assertEquals(NarrationProviderSelection.Device, selectNarrationProvider(kokoro, none))
    }

    @Test
    fun theDefaultSettingsSelectTheKokoroVoice() {
        assertEquals(
            NarrationProviderSelection.Neural(NeuralVoiceTier.KOKORO),
            selectNarrationProvider(NarrationVoiceSettings(), all),
        )
    }

    @Test
    fun kokoroIsTheOnlyTier() {
        assertEquals(listOf("KOKORO"), NeuralVoiceTier.entries.map { it.name })
    }

    @Test
    fun providerChoiceEnumHasNoCloudValue() {
        assertEquals(
            listOf("DEVICE", "NEURAL"),
            NarrationProviderChoice.entries.map { it.name },
        )
    }
}
