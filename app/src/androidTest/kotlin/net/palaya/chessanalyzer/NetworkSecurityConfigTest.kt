package net.palaya.chessanalyzer

import android.security.NetworkSecurityPolicy
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The debug build's network security config as the PLATFORM applies it (docs/MODEL_DOWNLOAD_DESIGN.md
 * §6.2, §6.3; D2d). `app/src/debug/res/xml/network_security_config.xml` allows cleartext only to the three
 * local test hosts (the emulator's host alias for `scripts/model_test_server.py`, and loopback for the
 * in-process `FaultHttpServer`), without subdomains, and nothing else. The release build has no such
 * config at all (`ManifestPermissionsTest` on the host; `apkanalyzer` on the release APK).
 */
@RunWith(AndroidJUnit4::class)
class NetworkSecurityConfigTest {

    private val policy get() = NetworkSecurityPolicy.getInstance()

    @Test
    fun thisSuiteRunsOnTheDebugBuild() {
        assertTrue("the instrumented suites run against the debug build", BuildConfig.DEBUG)
    }

    @Test
    fun cleartextIsAllowedToTheThreeLocalTestHosts() {
        for (host in listOf("10.0.2.2", "127.0.0.1", "localhost")) {
            assertTrue("cleartext to $host must be allowed in a debug build", policy.isCleartextTrafficPermitted(host))
        }
    }

    @Test
    fun cleartextIsRefusedEverywhereElse() {
        assertFalse("the base config refuses cleartext", policy.isCleartextTrafficPermitted)
        for (host in listOf("github.com", "objects.githubusercontent.com", "example.com", "10.0.2.3", "192.168.1.1", "sub.localhost")) {
            assertFalse("cleartext to $host must be refused", policy.isCleartextTrafficPermitted(host))
        }
    }
}
