package net.palaya.chessanalyzer.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import net.palaya.chessanalyzer.ui.model.NarrationProviderChoice
import net.palaya.chessanalyzer.ui.model.NeuralVoiceTier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Real-DataStore checks for [NarrationSettingsRepository]. Provider-name migration and the legacy
 * key purge are pure and are unit-tested on the host (`ResolvedProviderTest`,
 * `LegacyKeyStoragePurgeTest`); this keeps the checks that need the on-device DataStore.
 */
@RunWith(AndroidJUnit4::class)
class NarrationSettingsRepositoryTest {

    /**
     * The bundled natural voice is the default by construction: with nothing stored, the real
     * DataStore must read back NEURAL with nothing chosen. A deliberate pick of either provider
     * (Settings' "use the phone's built-in voice instead" switch) latches the explicit flag.
     */
    @Test
    fun theDefaultIsTheNaturalVoiceAndAnExplicitPickIsRecorded(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val repo = NarrationSettingsRepository(context)

        try {
            // Settings persist in one DataStore shared across the whole instrumented run, so the
            // pristine state has to be established here, not assumed.
            repo.clearProviderChoiceForTesting()
            val fresh = repo.current()
            assertEquals("a fresh install must default to the natural voice", NarrationProviderChoice.NEURAL, fresh.provider)
            assertEquals(NeuralVoiceTier.KOKORO, fresh.neuralTier)
            assertFalse("nothing has been chosen yet", fresh.providerExplicitlyChosen)

            repo.setProvider(NarrationProviderChoice.DEVICE)
            val afterDevice = repo.current()
            assertEquals(NarrationProviderChoice.DEVICE, afterDevice.provider)
            assertTrue("picking the device voice is a deliberate choice", afterDevice.providerExplicitlyChosen)

            repo.setProvider(NarrationProviderChoice.NEURAL)
            val afterNeural = repo.current()
            assertEquals(NarrationProviderChoice.NEURAL, afterNeural.provider)
            assertTrue(afterNeural.providerExplicitlyChosen)

            // And clearing really does return to the default.
            repo.clearProviderChoiceForTesting()
            assertEquals(NarrationProviderChoice.NEURAL, repo.current().provider)
            assertFalse(repo.current().providerExplicitlyChosen)
        } finally {
            repo.clearProviderChoiceForTesting()
        }
    }

    /**
     * On a real device: legacy key prefs written the way the removed code wrote them are deleted
     * by the startup purge, from the real `shared_prefs` directory.
     */
    @Test
    fun legacyKeyPrefsAreDeletedFromTheRealSharedPrefsDirectory() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        for (name in listOf("narration_key_fallback_unencrypted", "narration_secrets")) {
            val committed = context.getSharedPreferences(name, android.content.Context.MODE_PRIVATE)
                .edit().putString("narration_api_key", "not-a-real-key").commit()
            assertTrue(committed)
        }
        val dir = java.io.File(context.applicationInfo.dataDir, "shared_prefs")
        assertTrue("precondition: plaintext fallback exists", java.io.File(dir, "narration_key_fallback_unencrypted.xml").exists())

        LegacyKeyStoragePurge.run(context)

        assertFalse(java.io.File(dir, "narration_key_fallback_unencrypted.xml").exists())
        assertFalse(java.io.File(dir, "narration_secrets.xml").exists())
    }
}
