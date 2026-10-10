package net.palaya.chessanalyzer.data.models

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import net.palaya.chessanalyzer.data.models.FaultHttpServer.Fault
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * A4's hard rule: **a failed or slow upstream check never hides, delays or changes our own signed update
 * result.** [ModelUpdates] runs the two halves in separate coroutines with separate state; this drives both
 * against in-process servers (the signed manifest on one, the upstream release lists on another) and breaks
 * the upstream side in every way the fault server can.
 */
class UpdateUpstreamIsolationTest {

    private val keys = TestManifests.newKeyPair()
    private lateinit var models: FaultHttpServer
    private lateinit var github: FaultHttpServer
    private val tag = "models-2026.11"
    private val tarName = "kokoro-int8-en-v0_19-r2.tar"
    private val tarSha = "a".repeat(64)

    private val ours = OurComponents(
        "sf_19", "1.13.8", "kokoro-int8-en-v0_19.tar", "kokoro-int8-en-v0_19.tar.bz2", 103_248_205L,
        "c9f0dd393615805b0bab050c340834d5e684e732aec91c0e860cd30e982c08bd",
    )

    @Before
    fun setUp() {
        models = FaultHttpServer()
        github = FaultHttpServer()
        val manifest = TestManifests.manifest(TestManifests.voiceEntry(models.baseUrl, tag, tarName, 158_279_680, tarSha))
        models.serve("models/models.json", manifest).serve("models/models.json.sig", TestManifests.sign(manifest, keys.private))
        github.serveGzipped("repos/official-stockfish/Stockfish/releases/latest", """{"tag_name":"sf_20"}""".toByteArray())
        github.serveGzipped("repos/k2-fsa/sherpa-onnx/releases/latest", """{"tag_name":"v1.13.8"}""".toByteArray())
        github.serveGzipped(
            "repos/k2-fsa/sherpa-onnx/releases/tags/tts-models",
            """{"assets":[{"name":"kokoro-int8-en-v0_19.tar.bz2","size":103248205}]}""".toByteArray(),
        )
    }

    @After
    fun tearDown() {
        models.close()
        github.close()
    }

    private fun facts() = AppFacts(
        versionCode = 99,
        netVersion = TestModelFiles.NET_VERSION,
        netArchHash = TestModelFiles.NET_ARCH,
        sherpaOnnxVersion = "1.13.8",
        voiceLayout = net.palaya.chessanalyzer.video.VoiceStore.LAYOUT,
        baseUrl = models.baseUrl,
        allowCleartextLoopback = true,
        installedNetSha256 = null,
        installedVoiceSha256 = null,
        limits = SizeLimits(netMin = 1, netMax = 1L shl 30, voiceMin = 1, voiceMax = 1L shl 30),
    )

    private fun downloader(timeoutMs: Int = 1_500) =
        ModelDownloader("test", allowCleartextLoopback = true, connectTimeoutMs = timeoutMs, readTimeoutMs = timeoutMs, sleep = {})

    private fun signedChecker() = UpdateChecker(
        downloader = downloader(),
        manifestUrl = models.url("models/models.json"),
        publicKeyDer = keys.public.encoded,
        networkStatus = { NetworkCost.UNMETERED },
        facts = { facts() },
    )

    private fun updates(upstream: UpstreamChecker?) = ModelUpdates(
        checker = { signedChecker() },
        installer = { error("no install here") },
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
        upstream = upstream?.let { { it } },
    )

    private fun upstreamChecker(timeoutMs: Int = 1_500) =
        UpstreamChecker(downloader(timeoutMs), { NetworkCost.UNMETERED }, UpstreamSources.defaults(ours, github.baseUrl))

    private suspend fun ModelUpdates.awaitChecked(): UpdateUiState.Checked =
        withTimeout(15_000) { state.first { it is UpdateUiState.Checked } as UpdateUiState.Checked }

    private suspend fun ModelUpdates.awaitUpstreamDone(): List<UpstreamRow> =
        withTimeout(15_000) { upstreamRows.first { rows -> rows.isNotEmpty() && rows.none { it.status == UpstreamStatus.Checking } } }

    @Test
    fun bothHalvesAnswerAndEachKeepsToItsOwnState() = runBlocking {
        val u = updates(upstreamChecker())
        assertTrue(u.check())
        val checked = u.awaitChecked()
        assertEquals(listOf(ModelKind.VOICE), checked.offers.map { it.kind })
        val rows = u.awaitUpstreamDone().associate { it.component to it.status }
        assertEquals(UpstreamStatus.Newer("20"), rows[UpstreamComponent.STOCKFISH])
        assertEquals(UpstreamStatus.UpToDate, rows[UpstreamComponent.SHERPA_ONNX])
        assertEquals(UpstreamStatus.UpToDate, rows[UpstreamComponent.KOKORO_VOICE])
    }

    @Test
    fun theRowsAreCheckingTheMomentTheTapLandsAndNoUpstreamRequestStartsWithoutIt() = runBlocking {
        val u = updates(upstreamChecker())
        assertTrue("nothing before the tap", u.upstreamRows.value.isEmpty())
        assertTrue(github.requests.isEmpty())
        u.check()
        // Pending rows are set synchronously by the tap, before any answer.
        assertEquals(3, u.upstreamRows.value.size)
        u.awaitUpstreamDone()
        assertEquals(3, github.requests.size)
    }

    @Test
    fun aRateLimitedUpstreamLeavesTheSignedResultUntouched() = runBlocking {
        github.alwaysFault("repos/official-stockfish/Stockfish/releases/latest", Fault.Status(403))
        github.alwaysFault("repos/k2-fsa/sherpa-onnx/releases/latest", Fault.Status(403))
        github.alwaysFault("repos/k2-fsa/sherpa-onnx/releases/tags/tts-models", Fault.Status(403))
        val u = updates(upstreamChecker())
        u.check()
        val checked = u.awaitChecked()
        assertTrue("the signed result is the update offer", checked.result is UpdateCheckResult.Available)
        assertEquals(listOf(ModelKind.VOICE), checked.offers.map { it.kind })
        val rows = u.awaitUpstreamDone()
        assertTrue(rows.all { it.status == UpstreamStatus.Failed(UpstreamFailure.RATE_LIMITED) })
        // And the signed state is still the same value afterwards.
        assertEquals(checked, u.state.value)
    }

    @Test
    fun anUpstreamThatNeverAnswersDoesNotDelayTheSignedResult() = runBlocking {
        for (path in listOf(
            "repos/official-stockfish/Stockfish/releases/latest",
            "repos/k2-fsa/sherpa-onnx/releases/latest",
            "repos/k2-fsa/sherpa-onnx/releases/tags/tts-models",
        )) github.alwaysFault(path, Fault.Stall(afterBytes = 0, millis = 8_000))
        val u = updates(upstreamChecker(timeoutMs = 3_000))
        u.check()
        val started = System.nanoTime()
        val checked = u.awaitChecked()
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        assertTrue("the signed result arrived in $elapsedMs ms, not after the 3 s upstream timeout", elapsedMs < 2_500)
        assertTrue(checked.result is UpdateCheckResult.Available)
        // The upstream rows are still on their way at that moment, then each ends in its own failure.
        assertTrue(u.upstreamRows.value.any { it.status == UpstreamStatus.Checking })
        val rows = u.awaitUpstreamDone()
        assertTrue(rows.all { it.status == UpstreamStatus.Failed(UpstreamFailure.UNAVAILABLE) })
    }

    @Test
    fun aGarbledUpstreamAnswerLeavesTheSignedResultUntouched() = runBlocking {
        github.serve("repos/official-stockfish/Stockfish/releases/latest", "}{ not json".toByteArray())
        val u = updates(upstreamChecker())
        u.check()
        assertTrue(u.awaitChecked().result is UpdateCheckResult.Available)
        val rows = u.awaitUpstreamDone().associate { it.component to it.status }
        assertEquals(UpstreamStatus.Failed(UpstreamFailure.UNREADABLE), rows[UpstreamComponent.STOCKFISH])
        assertEquals(UpstreamStatus.UpToDate, rows[UpstreamComponent.SHERPA_ONNX])
    }

    @Test
    fun aBrokenSignedCheckStillShowsTheUpstreamRows() = runBlocking {
        // The reverse direction: our manifest cannot be verified, upstream is fine; both are reported as they are.
        models.serve("models/models.json.sig", ByteArray(8))
        val u = updates(upstreamChecker())
        u.check()
        assertTrue(u.awaitChecked().result is UpdateCheckResult.SignatureInvalid)
        assertEquals(UpstreamStatus.Newer("20"), u.awaitUpstreamDone().first { it.component == UpstreamComponent.STOCKFISH }.status)
    }

    @Test
    fun checkingAgainStartsOverWithFreshRowsAndNeverMixesTwoRuns() = runBlocking {
        val u = updates(upstreamChecker())
        u.check()
        u.awaitChecked()
        u.awaitUpstreamDone()
        github.serveGzipped("repos/official-stockfish/Stockfish/releases/latest", """{"tag_name":"sf_21"}""".toByteArray())
        assertTrue(u.check())
        assertTrue(u.upstreamRows.value.all { it.status == UpstreamStatus.Checking })
        val rows = u.awaitUpstreamDone()
        assertEquals(UpstreamStatus.Newer("21"), rows.first { it.component == UpstreamComponent.STOCKFISH }.status)
    }

    @Test
    fun withoutAnUpstreamCheckThereAreNoRowsAndNoUpstreamRequest() = runBlocking {
        val u = updates(null)
        u.check()
        u.awaitChecked()
        assertTrue(u.upstreamRows.value.isEmpty())
        assertFalse(github.requests.isNotEmpty())
    }

    @Test
    fun theLastCheckedTimeStillMeansTheSignedServerWasAsked() = runBlocking {
        val stamps = ArrayList<Long>()
        val u = ModelUpdates(
            checker = { signedChecker() },
            installer = { error("no install here") },
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
            onChecked = { stamps += it },
            clock = { 7L },
            upstream = { upstreamChecker() },
        )
        github.alwaysFault("repos/official-stockfish/Stockfish/releases/latest", Fault.Status(500))
        u.check()
        u.awaitChecked()
        u.awaitUpstreamDone()
        assertEquals(listOf(7L), stamps)
    }
}
