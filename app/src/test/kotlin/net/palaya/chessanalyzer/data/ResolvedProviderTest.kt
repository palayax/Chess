package net.palaya.chessanalyzer.data

import net.palaya.chessanalyzer.ui.model.NarrationProviderChoice
import net.palaya.chessanalyzer.ui.model.NarrationVoiceSettings
import net.palaya.chessanalyzer.ui.model.NeuralVoiceTier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The natural (Kokoro) voice ships inside the app, so it is the default: a fresh install, a
 * missing value and any value this build does not know all resolve to NEURAL, and none of it may
 * throw. Includes the migration of a DataStore written by a pre-Round-13 build (`CLOUD`).
 */
class ResolvedProviderTest {

    @Test
    fun persistedCloudFallsBackToNeuralWithoutThrowing() {
        val r = ResolvedProvider.from(storedProvider = "CLOUD", storedExplicit = true)
        assertEquals(NarrationProviderChoice.NEURAL, r.provider)
    }

    @Test
    fun aMigratedCloudUserIsNotTreatedAsHavingChosenAnything() {
        assertFalse(ResolvedProvider.from("CLOUD", storedExplicit = true).explicitlyChosen)
    }

    @Test
    fun garbageAndEmptyNamesAlsoFallBackToNeural() {
        for (bad in listOf("", "cloud", "Cloud", "GOOGLE", " DEVICE", "NEURAL\n", "null")) {
            assertEquals(bad, NarrationProviderChoice.NEURAL, ResolvedProvider.from(bad, false).provider)
        }
    }

    @Test
    fun knownValuesAreUnchanged() {
        assertEquals(NarrationProviderChoice.NEURAL, ResolvedProvider.from("NEURAL", true).provider)
        assertTrue(ResolvedProvider.from("NEURAL", true).explicitlyChosen)
        assertEquals(NarrationProviderChoice.DEVICE, ResolvedProvider.from("DEVICE", true).provider)
        assertTrue("a deliberate Device choice stays deliberate", ResolvedProvider.from("DEVICE", true).explicitlyChosen)
        assertFalse(ResolvedProvider.from("NEURAL", false).explicitlyChosen)
    }

    @Test
    fun missingValueIsNeuralAndKeepsTheStoredFlag() {
        assertEquals(NarrationProviderChoice.NEURAL, ResolvedProvider.from(null, false).provider)
        assertFalse(ResolvedProvider.from(null, false).explicitlyChosen)
        assertEquals(NarrationProviderChoice.NEURAL, ResolvedProvider.from(null, true).provider)
        assertTrue(ResolvedProvider.from(null, true).explicitlyChosen)
    }

    @Test
    fun theDefaultSettingsAreTheNaturalVoice() {
        assertEquals(NarrationProviderChoice.NEURAL, NarrationVoiceSettings().provider)
        assertEquals(NeuralVoiceTier.KOKORO, NarrationVoiceSettings().neuralTier)
        assertFalse(NarrationVoiceSettings().providerExplicitlyChosen)
    }

    @Test
    fun fromPersistedOrNullNeverThrowsAndHasNoCloud() {
        assertNull(NarrationProviderChoice.fromPersistedOrNull("CLOUD"))
        assertNull(NarrationProviderChoice.fromPersistedOrNull(null))
        assertEquals(NarrationProviderChoice.NEURAL, NarrationProviderChoice.fromPersistedOrNull("NEURAL"))
        assertEquals(listOf("DEVICE", "NEURAL"), NarrationProviderChoice.entries.map { it.name })
    }

    @Test
    fun kokoroIsTheOnlyVoiceTierAndAPersistedPiperNoLongerParses() {
        assertEquals(listOf("KOKORO"), NeuralVoiceTier.entries.map { it.name })
        // NarrationSettingsRepository resolves a stored tier with runCatching { valueOf(name) } and
        // falls back to KOKORO, so a "PIPER" written by an older build lands on Kokoro.
        val resolved = runCatching { NeuralVoiceTier.valueOf("PIPER") }.getOrNull() ?: NeuralVoiceTier.KOKORO
        assertEquals(NeuralVoiceTier.KOKORO, resolved)
    }
}
