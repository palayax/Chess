package net.palaya.chessanalyzer.data.models

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The app-wide update state holder (D2e): one thing at a time, and "Last checked" only when the server was asked. */
class ModelUpdatesTest {

    private fun checker(network: NetworkCost, url: String = "http://127.0.0.1:9/models/models.json") = UpdateChecker(
        downloader = ModelDownloader("test", allowCleartextLoopback = true, connectTimeoutMs = 500, readTimeoutMs = 500, sleep = {}),
        manifestUrl = url,
        publicKeyDer = TestManifests.newKeyPair().public.encoded,
        networkStatus = { network },
        facts = { error("not reached") },
    )

    private fun updates(c: UpdateChecker, stamps: MutableList<Long>) = ModelUpdates(
        checker = { c },
        installer = { error("no install here") },
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
        onChecked = { stamps += it },
        clock = { 42L },
    )

    private suspend fun ModelUpdates.awaitChecked(): UpdateUiState.Checked =
        withTimeout(10_000) { state.first { it is UpdateUiState.Checked } as UpdateUiState.Checked }

    @Test
    fun noNetworkIsNotALastCheck() = runBlocking {
        val stamps = ArrayList<Long>()
        val u = updates(checker(NetworkCost.UNAVAILABLE), stamps)
        assertTrue(u.check())
        assertEquals(UpdateCheckResult.NoInternet, u.awaitChecked().result)
        assertTrue(stamps.isEmpty())
    }

    @Test
    fun anAskedServerIsALastCheckEvenWhenItFails() = runBlocking {
        val stamps = ArrayList<Long>()
        FaultHttpServer().use { s ->
            val u = updates(checker(NetworkCost.UNMETERED, s.url("models/models.json")), stamps)
            assertTrue(u.check())
            assertTrue(u.awaitChecked().result is UpdateCheckResult.NotFound)
        }
        assertEquals(listOf(42L), stamps)
    }

    @Test
    fun aSecondTapWhileCheckingIsIgnored() = runBlocking {
        FaultHttpServer().use { s ->
            s.serve("models/models.json", ByteArray(10)).alwaysFault("models/models.json", FaultHttpServer.Fault.Slow(2))
            val u = updates(checker(NetworkCost.UNMETERED, s.url("models/models.json")), ArrayList())
            assertTrue(u.check())
            assertFalse("busy", u.check())
            u.awaitChecked()
        }
        Unit
    }
}
