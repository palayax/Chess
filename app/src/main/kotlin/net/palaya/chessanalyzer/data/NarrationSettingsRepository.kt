package net.palaya.chessanalyzer.data

import android.content.Context
import android.content.SharedPreferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import net.palaya.chessanalyzer.core.narration.cloud.GoogleCloudVoice
import net.palaya.chessanalyzer.ui.model.NarrationProviderChoice
import net.palaya.chessanalyzer.ui.model.NarrationVoiceSettings
import net.palaya.chessanalyzer.ui.model.NeuralVoiceTier

private val Context.narrationDataStore by preferencesDataStore(name = "chess_analyzer_narration_settings")

/**
 * Persists narration-voice preferences. The provider/tier choice is not sensitive and lives in
 * plain DataStore (same pattern as [SettingsRepository]); a cloud narration provider's **API key**
 * is stored separately, via [EncryptedSharedPreferences] — a Keystore-backed AES-256-GCM
 * encrypted file, never plaintext.
 *
 * Why this matters: this app is meant to ship on a public marketplace, so anyone can pull the
 * APK apart with `apktool`/`jadx` in minutes. A key baked into a resource or a `BuildConfig`
 * field is trivially recoverable and would let a stranger burn the account owner's paid quota.
 * The key here is supplied by the *user* at runtime, is never logged (search this file — there is
 * no `Log.*` call anywhere near it), and only ever touches [EncryptedSharedPreferences].
 *
 * The key is the user's **own Google Cloud** key for [NarrationProviderChoice.CLOUD] (see
 * [net.palaya.chessanalyzer.video.GoogleCloudTtsProvider]) — every user brings their own
 * project, so there is no shared quota to drain and nothing worth extracting from the APK.
 *
 * If building the encrypted store fails for any reason (a Keystore issue on some OEM build is
 * the realistic case), this falls back to a plain [SharedPreferences] file rather than crashing
 * Settings — [NarrationVoiceSettings.apiKeyIsEncrypted] reports which mode is active so the UI
 * can say so honestly instead of silently downgrading the guarantee.
 */
class NarrationSettingsRepository(private val context: Context) {

    private object Keys {
        val PROVIDER = stringPreferencesKey("narration_provider")
        val PROVIDER_EXPLICIT = booleanPreferencesKey("narration_provider_explicit")
        val NEURAL_TIER = stringPreferencesKey("neural_voice_tier")
        val CLOUD_VOICE = stringPreferencesKey("cloud_voice_id")
    }

    private val encryptedPrefs: SharedPreferences? by lazy { buildEncryptedPrefs() }
    private val fallbackPrefs: SharedPreferences by lazy {
        context.getSharedPreferences("narration_key_fallback_unencrypted", Context.MODE_PRIVATE)
    }

    /** True while the API key is actually sitting in Keystore-backed encrypted storage. */
    val isKeyStorageEncrypted: Boolean get() = encryptedPrefs != null

    private fun buildEncryptedPrefs(): SharedPreferences? = try {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "narration_secrets",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    } catch (e: Exception) {
        null
    }

    val settingsFlow: Flow<NarrationVoiceSettings> = context.narrationDataStore.data.map { prefs ->
        NarrationVoiceSettings(
            provider = prefs[Keys.PROVIDER]
                ?.let { name -> runCatching { NarrationProviderChoice.valueOf(name) }.getOrNull() }
                ?: NarrationProviderChoice.DEVICE,
            neuralTier = prefs[Keys.NEURAL_TIER]
                ?.let { name -> runCatching { NeuralVoiceTier.valueOf(name) }.getOrNull() }
                ?: NeuralVoiceTier.KOKORO,
            cloudVoice = GoogleCloudVoice.fromId(prefs[Keys.CLOUD_VOICE]) ?: GoogleCloudVoice.DEFAULT,
            apiKey = readApiKey(),
            apiKeyIsEncrypted = isKeyStorageEncrypted,
            providerExplicitlyChosen = prefs[Keys.PROVIDER_EXPLICIT] ?: false,
        )
    }

    suspend fun current(): NarrationVoiceSettings = settingsFlow.first()

    /**
     * Records the user's own choice from Settings. This also latches
     * [NarrationVoiceSettings.providerExplicitlyChosen], which permanently opts them out of the
     * automatic promotion to the neural voice — see [setProviderAutomatically] for the other path.
     */
    suspend fun setProvider(provider: NarrationProviderChoice) {
        context.narrationDataStore.edit {
            it[Keys.PROVIDER] = provider.name
            it[Keys.PROVIDER_EXPLICIT] = true
        }
    }

    /**
     * Switches the provider WITHOUT marking it as the user's own choice — used only by the
     * automatic promotion to the neural voice once its model is installed. Deliberately does not
     * touch [Keys.PROVIDER_EXPLICIT]: an automatic switch must never be mistaken for consent, so a
     * user who later picks a provider by hand still wins.
     */
    suspend fun setProviderAutomatically(provider: NarrationProviderChoice) {
        context.narrationDataStore.edit { it[Keys.PROVIDER] = provider.name }
    }

    /**
     * Clears the record of the user having picked a provider, returning them to the un-chosen
     * state the automatic neural promotion acts on. Exists for instrumented tests: settings live
     * in one real on-device DataStore shared by every test in the run, so a test that needs the
     * un-chosen state has to establish it rather than assume it (mirrors
     * [net.palaya.chessanalyzer.video.VoiceModelProvisioner.provisionFromLocalArchiveForTesting]).
     */
    suspend fun clearProviderChoiceForTesting() {
        context.narrationDataStore.edit { it.remove(Keys.PROVIDER_EXPLICIT) }
    }

    suspend fun setNeuralTier(tier: NeuralVoiceTier) {
        context.narrationDataStore.edit { it[Keys.NEURAL_TIER] = tier.name }
    }

    suspend fun setCloudVoice(voice: GoogleCloudVoice) {
        context.narrationDataStore.edit { it[Keys.CLOUD_VOICE] = voice.id }
    }

    /** Persists the cloud narration provider's API key. Never logged — see class doc. */
    suspend fun setApiKey(apiKey: String) = withContext(Dispatchers.IO) {
        val target = encryptedPrefs
        if (target != null) {
            target.edit().putString(KEY_API_KEY, apiKey).apply()
            // Clear any pre-existing plaintext fallback copy so a prior Keystore failure never
            // leaves a stale unencrypted key lying around once encryption becomes available.
            fallbackPrefs.edit().remove(KEY_API_KEY).apply()
        } else {
            fallbackPrefs.edit().putString(KEY_API_KEY, apiKey).apply()
        }
    }

    suspend fun clearApiKey() = withContext(Dispatchers.IO) {
        encryptedPrefs?.edit()?.remove(KEY_API_KEY)?.apply()
        fallbackPrefs.edit().remove(KEY_API_KEY).apply()
    }

    private fun readApiKey(): String =
        encryptedPrefs?.getString(KEY_API_KEY, null) ?: fallbackPrefs.getString(KEY_API_KEY, null) ?: ""

    companion object {
        /**
         * Provider-agnostic on purpose. It was previously named after the one cloud provider that
         * used it; renaming it means a key a user saved for that (now removed) provider is never
         * read back into a different provider's request — the old entry is simply abandoned
         * inside the encrypted file rather than silently reused.
         */
        private const val KEY_API_KEY = "narration_api_key"
    }
}
