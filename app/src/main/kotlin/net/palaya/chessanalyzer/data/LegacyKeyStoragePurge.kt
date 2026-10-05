package net.palaya.chessanalyzer.data

import android.content.Context
import java.io.File
import java.security.KeyStore

/**
 * Removes the API-key storage that pre-Round-13 builds created for the (now removed) Google Cloud
 * voice, so a phone that already holds a key does not keep it forever:
 *  - `narration_key_fallback_unencrypted` — the **plaintext** fallback prefs file;
 *  - `narration_secrets` — the EncryptedSharedPreferences file that held the key;
 *  - the AndroidX security-crypto master key that encrypted it, in the Android Keystore.
 *
 * Runs at every process start and is idempotent and cheap (a few `File.delete` calls). The
 * Keystore is only touched when a stale prefs file was actually found, so a clean install never
 * pays for it; running every launch (not once) also covers a stale file restored from an old
 * cloud backup. The file part is pure `java.io`, tested on the host with a temp directory.
 */
object LegacyKeyStoragePurge {

    /** Prefs names (without `.xml`) written by the removed key storage. */
    internal val LEGACY_PREFS_NAMES = listOf("narration_key_fallback_unencrypted", "narration_secrets")

    /** The alias androidx.security.crypto's `MasterKey.DEFAULT_MASTER_KEY_ALIAS` used. */
    internal const val LEGACY_MASTER_KEY_ALIAS = "_androidx_security_master_key_"

    /**
     * Deletes the legacy prefs files (and their `.bak` siblings) from [sharedPrefsDir].
     * @return true if at least one file was present and removed.
     */
    fun deleteLegacyPrefsFiles(sharedPrefsDir: File): Boolean {
        var found = false
        for (name in LEGACY_PREFS_NAMES) {
            for (suffix in listOf(".xml", ".xml.bak")) {
                val f = File(sharedPrefsDir, name + suffix)
                if (f.exists()) {
                    found = true
                    f.delete()
                }
            }
        }
        return found
    }

    /** Process-start hook. Never throws: a failure here must not stop the app launching. */
    fun run(context: Context) {
        try {
            val prefsDir = File(context.applicationInfo.dataDir, "shared_prefs")
            if (deleteLegacyPrefsFiles(prefsDir)) {
                try {
                    KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
                        .deleteEntry(LEGACY_MASTER_KEY_ALIAS)
                } catch (_: Exception) {
                    // Keystore trouble on some OEM builds; the key material it protected is gone.
                }
            }
        } catch (_: Exception) {
        }
    }
}
