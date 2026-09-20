package net.palaya.chessanalyzer.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import net.palaya.chessanalyzer.core.narration.cloud.GoogleCloudVoice
import net.palaya.chessanalyzer.ui.model.NarrationProviderChoice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Proves a narration API key round-trips through real on-device storage and — the point of the
 * whole design — is never handled as plain text when [EncryptedSharedPreferences] is available.
 * This is the one place a Keystore problem on some OEM build would actually surface, so it's
 * worth a real on-device check rather than trusting the code to compile.
 *
 * The key is the user's own Google Cloud key (see [NarrationSettingsRepository]'s class doc);
 * the Cloud voice choice persists alongside it in plain DataStore.
 */
@RunWith(AndroidJUnit4::class)
class NarrationSettingsRepositoryTest {

    @Test
    fun apiKeyRoundTripsAndReportsEncryptionStatusHonestly(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val repo = NarrationSettingsRepository(context)

        try {
            repo.setApiKey("test_not_a_real_key_12345")
            val afterSave = repo.current()
            assertEquals("test_not_a_real_key_12345", afterSave.apiKey)

            // Whichever storage mode is active, the repository must say so accurately — the
            // Settings UI shows a different message depending on this.
            assertEquals(repo.isKeyStorageEncrypted, afterSave.apiKeyIsEncrypted)

            repo.clearApiKey()
            assertTrue("key should be gone after clear", repo.current().apiKey.isBlank())
        } finally {
            repo.clearApiKey()
            repo.setProvider(NarrationProviderChoice.DEVICE)
        }
    }

    /**
     * The automatic promotion to the neural voice must be able to tell "never chose a provider"
     * apart from "deliberately chose Device". Both leave `provider == DEVICE`, so the distinction
     * lives in [net.palaya.chessanalyzer.ui.model.NarrationVoiceSettings.providerExplicitlyChosen]
     * — and getting it wrong means overriding a user's deliberate choice of the device voice.
     */
    @Test
    fun explicitProviderChoiceIsDistinguishedFromTheUnchosenDefault(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val repo = NarrationSettingsRepository(context)

        try {
            // Settings persist in one DataStore shared across the whole instrumented run, so the
            // un-chosen state has to be established here, not assumed — another test's cleanup
            // calls setProvider() and would otherwise leave the flag latched.
            repo.clearProviderChoiceForTesting()
            assertFalse(
                "precondition: the flag should start clear",
                repo.current().providerExplicitlyChosen,
            )

            // An automatic switch must never look like consent, even though it changes provider.
            repo.setProviderAutomatically(NarrationProviderChoice.NEURAL)
            val afterAuto = repo.current()
            assertEquals(NarrationProviderChoice.NEURAL, afterAuto.provider)
            assertFalse(
                "an automatic promotion must NOT latch providerExplicitlyChosen — otherwise the " +
                    "app would treat its own choice as the user's",
                afterAuto.providerExplicitlyChosen,
            )

            // A deliberate pick of Device latches the flag, even though DEVICE is also the default.
            repo.setProvider(NarrationProviderChoice.DEVICE)
            val afterExplicitDevice = repo.current()
            assertEquals(NarrationProviderChoice.DEVICE, afterExplicitDevice.provider)
            assertTrue(
                "explicitly picking Device must latch providerExplicitlyChosen, so the neural " +
                    "promotion can never silently override it",
                afterExplicitDevice.providerExplicitlyChosen,
            )

            // ...and it stays latched across a later automatic switch attempt.
            repo.setProviderAutomatically(NarrationProviderChoice.NEURAL)
            assertTrue(
                "an automatic switch must not clear a previously-recorded explicit choice",
                repo.current().providerExplicitlyChosen,
            )
        } finally {
            repo.setProviderAutomatically(NarrationProviderChoice.DEVICE)
            repo.clearProviderChoiceForTesting()
        }
    }

    /** The Cloud voice and the CLOUD provider choice persist and read back; a fresh store serves the documented default. */
    @Test
    fun cloudVoiceAndCloudProviderChoiceRoundTrip(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val repo = NarrationSettingsRepository(context)

        try {
            repo.setCloudVoice(GoogleCloudVoice.HE_CHIRP3_HD_MALE)
            repo.setApiKey("AIzaSyD-not_a_real_key-0123456789abcdefg")
            repo.setProvider(NarrationProviderChoice.CLOUD)
            val s = repo.current()
            assertEquals(GoogleCloudVoice.HE_CHIRP3_HD_MALE, s.cloudVoice)
            assertEquals(NarrationProviderChoice.CLOUD, s.provider)
            assertTrue(s.hasCloudKey)
            assertTrue(s.providerExplicitlyChosen)

            repo.setCloudVoice(GoogleCloudVoice.DEFAULT)
            assertEquals(GoogleCloudVoice.DEFAULT, repo.current().cloudVoice)

            repo.clearApiKey()
            assertFalse("no key means no Cloud", repo.current().hasCloudKey)
        } finally {
            repo.clearApiKey()
            repo.setCloudVoice(GoogleCloudVoice.DEFAULT)
            repo.setProvider(NarrationProviderChoice.DEVICE)
        }
    }
}
