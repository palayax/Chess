package net.palaya.chessanalyzer.manifest

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/**
 * Host-side guard for the app's network surface (D2b, docs/MODEL_DOWNLOAD_DESIGN.md §6.1). Until D2b
 * the app had no network permission at all; it now downloads its two model files once, on the user's
 * tap, so it requests exactly INTERNET and ACCESS_NETWORK_STATE on top of the export service's and the
 * notification permissions, and nothing else network-ish. Cleartext stays off in the main manifest: only
 * the debug build's network security config allows http, and only to the local test hosts.
 *
 * Parses the real XML, so comments that merely mention a permission do not count; no device needed.
 * The on-device counterpart is the instrumented `NetworkPermissionTest` (the installed, merged package).
 */
class ManifestPermissionsTest {

    private fun moduleFile(path: String): File {
        // Gradle runs unit tests with the module directory as the working directory.
        val f = File(path)
        return if (f.exists()) f else File("app/$path")
    }

    private val manifest: File get() = moduleFile("src/main/AndroidManifest.xml")

    private fun parse(file: File): Element =
        DocumentBuilderFactory.newInstance().apply { isNamespaceAware = false }.newDocumentBuilder().parse(file).documentElement

    private val root: Element get() = parse(manifest)

    private fun androidName(e: Element): String = e.getAttributeNS("http://schemas.android.com/apk/res/android", "name")
        .ifEmpty { e.getAttribute("android:name") }

    private fun requestedPermissions(): List<String> {
        val nodes = root.getElementsByTagName("uses-permission")
        return (0 until nodes.length).map { androidName(nodes.item(it) as Element) }
    }

    @Test
    fun manifestParsesAndDeclaresPermissions() {
        assertTrue("the test would be vacuous if it read no permissions", requestedPermissions().isNotEmpty())
    }

    @Test
    fun internetIsRequestedForTheOneTimeModelDownload() {
        assertTrue(requestedPermissions().contains("android.permission.INTERNET"))
    }

    @Test
    fun networkStateIsRequestedForTheMeteredCheckBeforeADownload() {
        assertTrue(requestedPermissions().contains("android.permission.ACCESS_NETWORK_STATE"))
    }

    @Test
    fun noOtherNetworkishPermission() {
        val allowed = setOf("android.permission.INTERNET", "android.permission.ACCESS_NETWORK_STATE")
        val bad = requestedPermissions().filter { it !in allowed }.filter {
            listOf("INTERNET", "NETWORK", "WIFI", "BLUETOOTH", "NEARBY", "CHANGE_NETWORK").any { word -> it.contains(word) }
        }
        assertTrue("unexpected network permissions: $bad", bad.isEmpty())
    }

    @Test
    fun exactlyTheDownloadExportServiceAndNotificationPermissions() {
        assertEquals(
            setOf(
                // D2b: the one-time model download (and the manual update check, D2e).
                "android.permission.INTERNET",
                "android.permission.ACCESS_NETWORK_STATE",
                "android.permission.FOREGROUND_SERVICE",
                // The export on API 29-34, and D2c's model download service (dataSync on every API).
                "android.permission.FOREGROUND_SERVICE_DATA_SYNC",
                // D1: the export uses the mediaProcessing type on Android 15+ (ExportForegroundServiceType).
                "android.permission.FOREGROUND_SERVICE_MEDIA_PROCESSING",
                "android.permission.POST_NOTIFICATIONS",
            ),
            requestedPermissions().toSet(),
        )
    }

    @Test
    fun noCleartextTrafficAttribute() {
        val app = root.getElementsByTagName("application").item(0) as Element
        assertFalse(
            "usesCleartextTraffic must not be set: the release build allows https only",
            app.hasAttribute("android:usesCleartextTraffic"),
        )
    }

    @Test
    fun noNetworkSecurityConfigInTheMainManifest() {
        val app = root.getElementsByTagName("application").item(0) as Element
        assertFalse(
            "the cleartext exception is for debug builds only (src/debug), never in src/main",
            app.hasAttribute("android:networkSecurityConfig"),
        )
    }

    @Test
    fun theDebugOnlyConfigAllowsCleartextToTheLocalTestHostsAndNothingElse() {
        val debugManifest = parse(moduleFile("src/debug/AndroidManifest.xml"))
        val app = debugManifest.getElementsByTagName("application").item(0) as Element
        assertEquals("@xml/network_security_config", app.getAttribute("android:networkSecurityConfig"))
        assertFalse(moduleFile("src/main/res/xml/network_security_config.xml").exists())

        val config = parse(moduleFile("src/debug/res/xml/network_security_config.xml"))
        val base = config.getElementsByTagName("base-config").item(0) as Element
        assertEquals("false", base.getAttribute("cleartextTrafficPermitted"))
        val domainConfigs = config.getElementsByTagName("domain-config")
        assertEquals(1, domainConfigs.length)
        val dc = domainConfigs.item(0) as Element
        assertEquals("true", dc.getAttribute("cleartextTrafficPermitted"))
        val domains = dc.getElementsByTagName("domain")
        val names = (0 until domains.length).map { (domains.item(it) as Element).textContent.trim() }.toSet()
        assertEquals(setOf("10.0.2.2", "127.0.0.1", "localhost"), names)
        for (i in 0 until domains.length) {
            assertFalse((domains.item(i) as Element).getAttribute("includeSubdomains") == "true")
        }
    }

    @Test
    fun theEmojiCompatInitializerIsRemovedSoNoFontIsFetchedOnTheAppsBehalf() {
        // D2f: androidx.emoji2's startup initializer made Play services download "Noto Color Emoji Compat"
        // (~3 MB, charged to the app's uid) on the first Activity, with no tap. The main manifest removes it.
        val providers = root.getElementsByTagName("provider")
        val startup = (0 until providers.length).map { providers.item(it) as Element }
            .single { androidName(it) == "androidx.startup.InitializationProvider" }
        assertEquals("merge", startup.getAttribute("tools:node"))
        val metas = startup.getElementsByTagName("meta-data")
        val emoji = (0 until metas.length).map { metas.item(it) as Element }
            .single { androidName(it) == "androidx.emoji2.text.EmojiCompatInitializer" }
        assertEquals("remove", emoji.getAttribute("tools:node"))
    }
}
