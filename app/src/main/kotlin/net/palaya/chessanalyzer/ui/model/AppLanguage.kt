package net.palaya.chessanalyzer.ui.model

import android.app.LocaleManager
import android.content.Context
import android.os.Build
import android.os.LocaleList
import androidx.annotation.StringRes
import net.palaya.chessanalyzer.R

/**
 * The languages the app offers in Settings. **English only for now** — Hebrew is groundwork, not
 * content, until `core.narration` has a Hebrew `NarrationStrings` and `res/values-iw/` exists.
 *
 * Adding a language is: add an entry here with its BCP-47 [tag] and label, add `values-<tag>/`
 * resources, add the entry to `res/xml/locales_config.xml`, and register the narration
 * implementation in `NarrationLocales.all`. Nothing else in the app knows the list.
 *
 * **Hebrew trap, recorded so it is not rediscovered:** Android resolves Hebrew to the legacy ISO
 * code `iw`, so resources must live in `values-iw` (not `values-he`) or they are silently ignored
 * on many devices and the app just shows English. `LocaleList.forLanguageTags("he")` is fine —
 * the platform maps the tag — it is only the *resource directory* that has to use `iw`.
 */
enum class AppLanguage(val tag: String, @StringRes val labelRes: Int) {
    /** Follow the device language. */
    SYSTEM("", R.string.settings_language_system),
    ENGLISH("en", R.string.settings_language_english);

    companion object {
        /** The stored tag back to an entry; unknown or empty tags mean [SYSTEM]. */
        fun fromTag(tag: String?): AppLanguage = entries.firstOrNull { it.tag == tag && it.tag.isNotEmpty() } ?: SYSTEM
    }
}

/**
 * Per-app language plumbing on top of the platform's own support, so the choice made in Settings
 * reaches both the resources (`R.string`) and the narration (`NarrationLocales`).
 *
 * **Why this is API 33+ only.** [LocaleManager] — the platform's per-app language API — arrived in
 * Android 13. Below that the usual backport is `AppCompatDelegate.setApplicationLocales()`, which
 * needs the AppCompat dependency this pure-Compose app does not have (see `docs/` and the Round
 * 10 notes for the trade-off). Until that decision is taken, [isPerAppLanguageSupported] is false
 * on API 26–32 and the app follows the system language there; Settings says so.
 */
object AppLocales {

    val isPerAppLanguageSupported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    /**
     * Applies [language] as the app's locale. Returns true if the platform accepted it (the
     * activity is recreated by the system and resources re-resolve), false below API 33 where
     * nothing changes.
     */
    fun apply(context: Context, language: AppLanguage): Boolean {
        if (!isPerAppLanguageSupported) return false
        val manager = context.getSystemService(LocaleManager::class.java) ?: return false
        manager.applicationLocales =
            if (language == AppLanguage.SYSTEM) LocaleList.getEmptyLocaleList() else LocaleList.forLanguageTags(language.tag)
        return true
    }

    /**
     * The BCP-47 tag the narration should be generated in: the explicit choice when there is one,
     * otherwise whatever language the app's resources actually resolved to — so narration and UI
     * can never disagree.
     */
    fun narrationTag(context: Context, language: AppLanguage): String =
        if (language != AppLanguage.SYSTEM) language.tag
        else context.resources.configuration.locales[0]?.toLanguageTag() ?: "en"
}
