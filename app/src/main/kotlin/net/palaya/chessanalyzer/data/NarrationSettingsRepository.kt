package net.palaya.chessanalyzer.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import net.palaya.chessanalyzer.ui.model.NarrationProviderChoice
import net.palaya.chessanalyzer.ui.model.NarrationVoiceSettings
import net.palaya.chessanalyzer.ui.model.NeuralVoiceTier

private val Context.narrationDataStore by preferencesDataStore(
    name = "chess_analyzer_narration_settings",
    corruptionHandler = resetOnCorruption("chess_analyzer_narration_settings"),
)

/**
 * Persists narration-voice preferences — provider and neural tier. None of it is sensitive, so it
 * lives in plain DataStore (same pattern as [SettingsRepository]).
 *
 * History: until Round 13 this class also held a Google Cloud API key (encrypted, with a
 * plaintext fallback file). The Cloud voice was removed — it needs billing, against the owner's
 * "free, local only" rule — and so was all key storage. A phone that ran an older build may still
 * have the key on disk; [LegacyKeyStoragePurge] deletes it at startup.
 */
class NarrationSettingsRepository(private val context: Context) {

    private object Keys {
        val PROVIDER = stringPreferencesKey("narration_provider")
        val PROVIDER_EXPLICIT = booleanPreferencesKey("narration_provider_explicit")
        val NEURAL_TIER = stringPreferencesKey("neural_voice_tier")
    }

    val settingsFlow: Flow<NarrationVoiceSettings> = context.narrationDataStore.data.map { prefs ->
        val resolved = ResolvedProvider.from(prefs[Keys.PROVIDER], prefs[Keys.PROVIDER_EXPLICIT] ?: false)
        NarrationVoiceSettings(
            provider = resolved.provider,
            neuralTier = prefs[Keys.NEURAL_TIER]
                ?.let { name -> runCatching { NeuralVoiceTier.valueOf(name) }.getOrNull() }
                ?: NeuralVoiceTier.KOKORO,
            providerExplicitlyChosen = resolved.explicitlyChosen,
        )
    }

    suspend fun current(): NarrationVoiceSettings = settingsFlow.first()

    /**
     * Records the user's own choice from Settings (the "use the phone's built-in voice instead"
     * switch, or choosing the natural voice again). This also latches
     * [NarrationVoiceSettings.providerExplicitlyChosen].
     */
    suspend fun setProvider(provider: NarrationProviderChoice) {
        context.narrationDataStore.edit {
            it[Keys.PROVIDER] = provider.name
            it[Keys.PROVIDER_EXPLICIT] = true
        }
    }

    /**
     * Forgets any stored provider choice, returning to the fresh-install state (the default,
     * NEURAL, with nothing chosen). Exists for instrumented tests: settings live in one real
     * on-device DataStore shared by every test in the run, so a test that needs the pristine state
     * has to establish it rather than assume it.
     */
    suspend fun clearProviderChoiceForTesting() {
        context.narrationDataStore.edit {
            it.remove(Keys.PROVIDER_EXPLICIT)
            it.remove(Keys.PROVIDER)
        }
    }

    suspend fun setNeuralTier(tier: NeuralVoiceTier) {
        context.narrationDataStore.edit { it[Keys.NEURAL_TIER] = tier.name }
    }
}
