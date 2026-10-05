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
 * Proof, on a real device, that the app cannot use the network (the app is fully offline: both
 * models ship inside the APK, and the manifest declares no network permission).
 *
 *  1. Declared: the **installed** package requests neither `INTERNET` nor `ACCESS_NETWORK_STATE`.
 *     The host `ManifestPermissionsTest` reads the source manifest; this reads what the platform
 *     actually installed, after manifest merging with every library's manifest.
 *  2. Behavioural: without `INTERNET`, the platform denies even a loopback socket. A
 *     declared-permissions check alone could be fooled by a library adding the permission some
 *     other way, a refused socket cannot.
 *
 * What the denial looks like was **measured on the API 34 emulator**, not assumed: the design
 * expected `EACCES`, and the platform actually fails the `socket()` call itself with
 * `socket failed: EPERM (Operation not permitted)`. Both are "permission denied", and both come
 * from the kernel's network-permission check before any connection is attempted, whereas a
 * genuinely permitted loopback connect to a closed port reports `ECONNREFUSED`. The test accepts
 * the two permission errnos and nothing else.
 */
@RunWith(AndroidJUnit4::class)
class NoNetworkPermissionTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun requested(): List<String> =
        context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
            .requestedPermissions.orEmpty().toList()

    @Test
    fun theInstalledPackageRequestsNeitherNetworkPermission() {
        val permissions = requested()
        assertTrue("vacuity guard: the app does request some permissions, got $permissions", permissions.isNotEmpty())
        assertFalse("INTERNET must not be requested: $permissions", "android.permission.INTERNET" in permissions)
        assertFalse(
            "ACCESS_NETWORK_STATE must not be requested: $permissions",
            "android.permission.ACCESS_NETWORK_STATE" in permissions,
        )
    }

    @Test
    fun theInstalledPackageRequestsOnlyTheExportServiceAndNotificationPlatformPermissions() {
        // AndroidX adds its own signature permission (DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION, in
        // the app's own namespace); only platform permissions matter for what the app may do.
        assertEquals(
            setOf(
                "android.permission.FOREGROUND_SERVICE",
                "android.permission.FOREGROUND_SERVICE_DATA_SYNC",
                "android.permission.POST_NOTIFICATIONS",
            ),
            requested().filter { it.startsWith("android.permission.") }.toSet(),
        )
    }

    @Test
    fun theInstalledPackageHasNotBeenGrantedInternet() {
        val result = context.packageManager.checkPermission("android.permission.INTERNET", context.packageName)
        assertEquals(PackageManager.PERMISSION_DENIED, result)
    }

    @Test
    fun aSocketToLoopbackIsDeniedByThePlatformPermissionCheck() {
        var message: String? = null
        try {
            Socket().use { it.connect(InetSocketAddress("127.0.0.1", 9), 2_000) }
            fail("a socket connect must not succeed without the INTERNET permission")
        } catch (e: SocketException) {
            message = e.message
        }
        android.util.Log.i("NoNetworkPermissionTest", "socket to 127.0.0.1:9 without INTERNET failed with: $message")
        // A refused or timed-out connect (ECONNREFUSED) would mean the permission check did NOT
        // stop the socket; only the permission errnos prove it did.
        val m = message.orEmpty()
        assertTrue(
            "expected a permission denial (EPERM or EACCES), got: $m",
            m.contains("EPERM") || m.contains("EACCES") || m.contains("Operation not permitted") || m.contains("Permission denied"),
        )
        assertFalse("ECONNREFUSED means the socket was allowed: $m", m.contains("ECONNREFUSED"))
    }
}
