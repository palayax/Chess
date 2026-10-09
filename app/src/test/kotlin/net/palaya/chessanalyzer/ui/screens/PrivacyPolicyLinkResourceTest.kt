package net.palaya.chessanalyzer.ui.screens

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The privacy-policy link Google Play requires inside the app (About screen): one fixed URL in
 * strings.xml, not translatable, next to the source link, and the About screen opens it through the
 * same `uriHandler.openUri` path as its other links (so `NetworkCallSitesTest` stays the only
 * network gate). The on-screen behaviour is `AboutPrivacyLinkTest` (instrumented).
 */
class PrivacyPolicyLinkResourceTest {

    private fun repoFile(path: String): File {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "settings.gradle.kts").exists()) dir = dir.parentFile
        return File(requireNotNull(dir) { "repository root not found" }, path)
    }

    private val strings get() = repoFile("app/src/main/res/values/strings.xml").readText()

    @Test
    fun theUrlIsTheHostedPolicyAndNotTranslatable() {
        val m = Regex("""<string name="about_privacy_policy_url"([^>]*)>([^<]*)</string>""").find(strings)
        requireNotNull(m) { "about_privacy_policy_url missing from strings.xml" }
        assertTrue(m.groupValues[1], "translatable=\"false\"" in m.groupValues[1])
        assertEquals("https://palayax.github.io/Chess/privacy/", m.groupValues[2])
    }

    @Test
    fun theLabelIsPresentAndTheAboutScreenOpensTheUrlThroughTheUriHandler() {
        assertTrue(Regex("""<string name="about_privacy_policy_label">Privacy policy</string>""").containsMatchIn(strings))
        val about = repoFile("app/src/main/kotlin/net/palaya/chessanalyzer/ui/screens/AboutScreen.kt").readText()
        assertTrue("privacy url not read from resources", "R.string.about_privacy_policy_url" in about)
        assertTrue("privacy row must use the shared link modifier", "clickableUrl(uriHandler, privacyUrl)" in about)
    }
}
