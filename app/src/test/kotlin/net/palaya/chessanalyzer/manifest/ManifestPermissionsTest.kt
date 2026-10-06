package net.palaya.chessanalyzer.manifest

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/**
 * Host-side guard for "the app is fully offline": the source manifest declares no network
 * permission and does not opt in to cleartext traffic. It parses the real XML, so comments that
 * merely mention a permission do not count, and it does not need a device.
 *
 * Its on-device counterpart is the instrumented `NoNetworkPermissionTest`, which checks the
 * installed package and tries to open a socket.
 */
class ManifestPermissionsTest {

    private val manifest: File
        get() {
            // Gradle runs unit tests with the module directory as the working directory.
            val f = File("src/main/AndroidManifest.xml")
            return if (f.exists()) f else File("app/src/main/AndroidManifest.xml")
        }

    private val root: Element
        get() = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(manifest).documentElement

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
    fun noInternetPermission() {
        assertFalse(requestedPermissions().any { it.endsWith(".INTERNET") })
    }

    @Test
    fun noNetworkStatePermission() {
        assertFalse(requestedPermissions().any { it.endsWith(".ACCESS_NETWORK_STATE") })
    }

    @Test
    fun noOtherNetworkishPermission() {
        val bad = requestedPermissions().filter {
            listOf("INTERNET", "NETWORK", "WIFI", "BLUETOOTH", "NEARBY", "CHANGE_NETWORK").any { word -> it.contains(word) }
        }
        assertTrue("unexpected network permissions: $bad", bad.isEmpty())
    }

    @Test
    fun onlyTheExportServiceAndNotificationPermissionsRemain() {
        assertEquals(
            setOf(
                "android.permission.FOREGROUND_SERVICE",
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
            "usesCleartextTraffic is meaningless without a network permission and must be gone",
            app.hasAttribute("android:usesCleartextTraffic"),
        )
    }
}
