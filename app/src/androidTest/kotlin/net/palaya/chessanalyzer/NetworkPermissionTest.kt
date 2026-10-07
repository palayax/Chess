package net.palaya.chessanalyzer

import android.content.pm.PackageManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The app's network permissions as INSTALLED on a real device, after manifest merging with every
 * library (replaces the bundled builds' `NoNetworkPermissionTest`; docs/MODEL_DOWNLOAD_DESIGN.md §6.2).
 *
 * Since D2b the app downloads its two model files once, on the user's tap, so it holds INTERNET and
 * ACCESS_NETWORK_STATE and nothing else network-related. The host `ManifestPermissionsTest` reads the
 * source manifest; this reads what the platform installed. That the app does not USE the network after
 * setup is `NoNetworkAfterSetupTest`'s job (D2d), and that only `ModelDownloader` can open a connection
 * is the host `NetworkCallSitesTest`'s.
 *
 * Behavioural check: with INTERNET held, a loopback connect to a closed port is no longer refused by the
 * permission check (EPERM/EACCES, measured on API 34 before D2b) but by the closed port: ECONNREFUSED.
 */
@RunWith(AndroidJUnit4::class)
class NetworkPermissionTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun requested(): List<String> =
        context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
            .requestedPermissions.orEmpty().toList()

    @Test
    fun theInstalledPackageRequestsExactlyTheDownloadExportAndNotificationPlatformPermissions() {
        // AndroidX adds its own signature permission (DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION, in
        // the app's own namespace); only platform permissions matter for what the app may do.
        assertEquals(
            setOf(
                "android.permission.INTERNET",
                "android.permission.ACCESS_NETWORK_STATE",
                "android.permission.FOREGROUND_SERVICE",
                "android.permission.FOREGROUND_SERVICE_DATA_SYNC",
                "android.permission.FOREGROUND_SERVICE_MEDIA_PROCESSING",
                "android.permission.POST_NOTIFICATIONS",
            ),
            requested().filter { it.startsWith("android.permission.") }.toSet(),
        )
    }

    @Test
    fun internetAndNetworkStateAreGrantedAtInstall() {
        // Both are normal permissions: granted at install, never asked for.
        for (p in listOf("android.permission.INTERNET", "android.permission.ACCESS_NETWORK_STATE")) {
            assertEquals(p, PackageManager.PERMISSION_GRANTED, context.packageManager.checkPermission(p, context.packageName))
        }
    }

    @Test
    fun aSocketToAClosedLoopbackPortIsRefusedByThePortNotByThePermissionCheck() {
        var message: String? = null
        try {
            Socket().use { it.connect(InetSocketAddress("127.0.0.1", 9), 2_000) }
            fail("nothing listens on 127.0.0.1:9, the connect must fail")
        } catch (e: SocketException) {
            message = e.message
        }
        android.util.Log.i("NetworkPermissionTest", "socket to 127.0.0.1:9 failed with: $message")
        val m = message.orEmpty()
        assertTrue("expected ECONNREFUSED (the permission is held), got: $m", m.contains("ECONNREFUSED") || m.contains("Connection refused"))
        assertFalse("EPERM/EACCES would mean INTERNET is missing: $m", m.contains("EPERM") || m.contains("EACCES"))
    }

    @Test
    fun noEmojiFontIsFetchedFromPlayServicesOnTheAppsBehalf() {
        // D2f: androidx.emoji2's startup initializer asked Play services for "Noto Color Emoji Compat" on the
        // first Activity, and GMS downloaded ~3 MB charged to this uid (NoNetworkAfterSetupTest caught it when
        // the font was not cached). The installed (merged) startup provider must not carry that initializer.
        val info = context.packageManager.getProviderInfo(
            android.content.ComponentName(context.packageName, "androidx.startup.InitializationProvider"),
            PackageManager.GET_META_DATA,
        )
        val keys = info.metaData?.keySet().orEmpty()
        assertFalse("EmojiCompatInitializer is still registered: $keys", keys.contains("androidx.emoji2.text.EmojiCompatInitializer"))
        assertTrue("the other startup initializers stay: $keys", keys.contains("androidx.lifecycle.ProcessLifecycleInitializer"))
    }
}
