package net.palaya.chessanalyzer.data.models

import kotlinx.coroutines.runBlocking
import net.palaya.chessanalyzer.data.models.FaultHttpServer.Fault
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The upstream half of "Check for updates" (A4) against [FaultHttpServer] standing in for api.github.com: the
 * base URL of [UpstreamSources.defaults] is injectable, so nothing here leaves the machine. What is pinned:
 * exactly the three requests, what each answer is read as, that every failure stays inside its own row, and
 * the request itself (GET, the app's User-Agent, no credentials, size caps).
 */
class UpstreamCheckerTest {

    private lateinit var server: FaultHttpServer
    private var network = NetworkCost.UNMETERED

    private val ours = OurComponents(
        stockfishTag = "sf_19",
        sherpaOnnxVersion = "1.13.8",
        voiceTarName = "kokoro-int8-en-v0_19.tar",
        voiceArchiveName = "kokoro-int8-en-v0_19.tar.bz2",
        voiceArchiveSizeBytes = 103_248_205L,
        voiceArchiveSha256 = "c9f0dd393615805b0bab050c340834d5e684e732aec91c0e860cd30e982c08bd",
    )

    private val stockfishPath = "repos/official-stockfish/Stockfish/releases/latest"
    private val sherpaPath = "repos/k2-fsa/sherpa-onnx/releases/latest"
    private val ttsPath = "repos/k2-fsa/sherpa-onnx/releases/tags/tts-models"

    @Before
    fun setUp() {
        server = FaultHttpServer()
    }

    @After
    fun tearDown() = server.close()

    private fun release(tag: String) = """{"url":"x","tag_name":"$tag","name":"$tag","assets":[]}""".toByteArray()

    private fun asset(name: String, size: Long, sha: String? = null) =
        """{"name":"$name","size":$size${if (sha != null) ",\"digest\":\"sha256:$sha\"" else ""}}"""

    private fun tts(vararg assets: String) = """{"tag_name":"tts-models","assets":[${assets.joinToString(",")}]}""".toByteArray()

    private val ourAsset get() = asset(ours.voiceArchiveName, ours.voiceArchiveSizeBytes, ours.voiceArchiveSha256)

    private fun publishAllCurrent() {
        server.serveGzipped(stockfishPath, release("sf_19"))
        server.serveGzipped(sherpaPath, release("v1.13.8"))
        server.serveGzipped(ttsPath, tts(ourAsset, asset("kokoro-en-v0_19.tar.bz2", 1), asset("matcha-icefall-en_US-ljspeech.tar.bz2", 2)))
    }

    private fun checker(
        downloader: ModelDownloader = ModelDownloader("PalayaChess/test (Android 36)", allowCleartextLoopback = true, connectTimeoutMs = 2_000, readTimeoutMs = 2_000, sleep = {}),
    ) = UpstreamChecker(downloader, { network }, UpstreamSources.defaults(ours, server.baseUrl))

    private fun rowsOf(c: UpstreamChecker = checker()): Map<UpstreamComponent, UpstreamStatus> =
        runBlocking { c.check() }.associate { it.component to it.status }

    private fun paths() = server.requests.map { it.path }.sorted()

    @Test
    fun whenEverythingIsCurrentEveryRowIsUpToDate() {
        publishAllCurrent()
        val rows = rowsOf()
        assertEquals(UpstreamStatus.UpToDate, rows[UpstreamComponent.STOCKFISH])
        assertEquals(UpstreamStatus.UpToDate, rows[UpstreamComponent.SHERPA_ONNX])
        assertEquals(UpstreamStatus.UpToDate, rows[UpstreamComponent.KOKORO_VOICE])
    }

    @Test
    fun aCheckMakesExactlyThreeGetRequestsAndNothingElse() {
        publishAllCurrent()
        rowsOf()
        assertEquals(
            listOf("/$sherpaPath", "/$stockfishPath", "/$ttsPath").sorted(),
            paths(),
        )
    }

    @Test
    fun theRowsKeepTheSourceOrderAndCarryTheVersionsThisAppHas() {
        publishAllCurrent()
        val rows = runBlocking { checker().check() }
        assertEquals(listOf(UpstreamComponent.STOCKFISH, UpstreamComponent.SHERPA_ONNX, UpstreamComponent.KOKORO_VOICE), rows.map { it.component })
        assertEquals(listOf("19", "1.13.8", "v0.19"), rows.map { it.ours })
    }

    @Test
    fun aNewerStockfishIsReportedWithItsVersion() {
        publishAllCurrent()
        server.serveGzipped(stockfishPath, release("sf_20"))
        assertEquals(UpstreamStatus.Newer("20"), rowsOf()[UpstreamComponent.STOCKFISH])
        // A point release counts too.
        server.serveGzipped(stockfishPath, release("sf_19.1"))
        assertEquals(UpstreamStatus.Newer("19.1"), rowsOf()[UpstreamComponent.STOCKFISH])
    }

    @Test
    fun aStockfishOlderOrEqualToOursIsUpToDate() {
        publishAllCurrent()
        server.serveGzipped(stockfishPath, release("sf_18"))
        assertEquals(UpstreamStatus.UpToDate, rowsOf()[UpstreamComponent.STOCKFISH])
    }

    @Test
    fun aNewerSherpaOnnxIsReportedWithoutTheV() {
        publishAllCurrent()
        server.serveGzipped(sherpaPath, release("v1.14.0"))
        assertEquals(UpstreamStatus.Newer("1.14.0"), rowsOf()[UpstreamComponent.SHERPA_ONNX])
        // 1.13.10 is newer than 1.13.8 (numbers, not text).
        server.serveGzipped(sherpaPath, release("v1.13.10"))
        assertEquals(UpstreamStatus.Newer("1.13.10"), rowsOf()[UpstreamComponent.SHERPA_ONNX])
    }

    @Test
    fun aNewerKokoroGenerationInTheTtsModelsReleaseIsReported() {
        publishAllCurrent()
        server.serveGzipped(
            ttsPath,
            tts(ourAsset, asset("kokoro-int8-multi-lang-v1_0.tar.bz2", 5), asset("kokoro-int8-multi-lang-v1_1.tar.bz2", 6)),
        )
        assertEquals(UpstreamStatus.Newer("v1.1"), rowsOf()[UpstreamComponent.KOKORO_VOICE])
    }

    @Test
    fun theSameKokoroVersionRepublishedUnderTheSameNameIsReportedAsChanged() {
        publishAllCurrent()
        // Another SHA-256.
        server.serveGzipped(ttsPath, tts(asset(ours.voiceArchiveName, ours.voiceArchiveSizeBytes, "0".repeat(64))))
        assertEquals(UpstreamStatus.Changed, rowsOf()[UpstreamComponent.KOKORO_VOICE])
        // Another size.
        server.serveGzipped(ttsPath, tts(asset(ours.voiceArchiveName, ours.voiceArchiveSizeBytes + 1, ours.voiceArchiveSha256)))
        assertEquals(UpstreamStatus.Changed, rowsOf()[UpstreamComponent.KOKORO_VOICE])
        // No longer listed at all.
        server.serveGzipped(ttsPath, tts(asset("kokoro-int8-en-v0_18.tar.bz2", 7)))
        assertEquals(UpstreamStatus.Changed, rowsOf()[UpstreamComponent.KOKORO_VOICE])
    }

    @Test
    fun anAssetWithoutADigestIsJudgedBySizeAlone() {
        publishAllCurrent()
        server.serveGzipped(ttsPath, tts(asset(ours.voiceArchiveName, ours.voiceArchiveSizeBytes)))
        assertEquals(UpstreamStatus.UpToDate, rowsOf()[UpstreamComponent.KOKORO_VOICE])
    }

    @Test
    fun aPlainUncompressedAnswerIsReadToo() {
        server.serve(stockfishPath, release("sf_20"))
        server.serve(sherpaPath, release("v1.13.8"))
        server.serve(ttsPath, tts(ourAsset))
        val rows = rowsOf()
        assertEquals(UpstreamStatus.Newer("20"), rows[UpstreamComponent.STOCKFISH])
        assertEquals(UpstreamStatus.UpToDate, rows[UpstreamComponent.SHERPA_ONNX])
    }

    // ---- The request itself ----

    @Test
    fun theRequestsAreGetsWithTheAppsUserAgentAndNoCredentialsOrIdentifiers() {
        publishAllCurrent()
        rowsOf()
        assertEquals(3, server.requests.size)
        for (r in server.requests) {
            assertEquals("PalayaChess/test (Android 36)", r.headers["user-agent"])
            assertEquals("gzip", r.headers["accept-encoding"])
            assertEquals(null, r.headers["authorization"])
            assertEquals(null, r.headers["cookie"])
            assertEquals(null, r.headers["referer"])
            assertEquals(null, r.headers["range"])
            // Only these: no device, account or install identifier of any kind.
            val unexpected = r.headers.keys - setOf("user-agent", "accept-encoding", "host", "connection", "accept", "cache-control", "pragma")
            assertTrue("unexpected request headers: $unexpected", unexpected.isEmpty())
        }
    }

    // ---- Failures stay inside their own row ----

    @Test
    fun aRateLimitedAnswerIsThatRowsFailureAndTheOthersStillAnswer() {
        publishAllCurrent()
        server.serveGzipped(stockfishPath, release("sf_20"))
        server.alwaysFault(sherpaPath, Fault.Status(403))
        val rows = rowsOf()
        assertEquals(UpstreamStatus.Failed(UpstreamFailure.RATE_LIMITED), rows[UpstreamComponent.SHERPA_ONNX])
        assertEquals(UpstreamStatus.Newer("20"), rows[UpstreamComponent.STOCKFISH])
        assertEquals(UpstreamStatus.UpToDate, rows[UpstreamComponent.KOKORO_VOICE])
    }

    @Test
    fun http429IsRateLimitedToo() {
        publishAllCurrent()
        server.alwaysFault(stockfishPath, Fault.Status(429, retryAfterSeconds = 3600))
        assertEquals(UpstreamStatus.Failed(UpstreamFailure.RATE_LIMITED), rowsOf()[UpstreamComponent.STOCKFISH])
    }

    @Test
    fun aServerErrorOrAMissingPageIsUnavailable() {
        publishAllCurrent()
        server.alwaysFault(stockfishPath, Fault.Status(503))
        assertEquals(UpstreamStatus.Failed(UpstreamFailure.UNAVAILABLE), rowsOf()[UpstreamComponent.STOCKFISH])
        server.clearFaults()
        val noPages = FaultHttpServer().use { empty ->
            runBlocking { UpstreamChecker(ModelDownloader("t", true, 2_000, 2_000), { network }, UpstreamSources.defaults(ours, empty.baseUrl)).check() }
        }
        assertTrue(noPages.all { it.status == UpstreamStatus.Failed(UpstreamFailure.UNAVAILABLE) })
    }

    @Test
    fun aDroppedConnectionAndATimeoutAreUnavailable() {
        publishAllCurrent()
        server.alwaysFault(stockfishPath, Fault.DropAfter(10))
        server.alwaysFault(sherpaPath, Fault.Stall(afterBytes = 5, millis = 5_000))
        val rows = rowsOf()
        assertEquals(UpstreamStatus.Failed(UpstreamFailure.UNAVAILABLE), rows[UpstreamComponent.STOCKFISH])
        assertEquals(UpstreamStatus.Failed(UpstreamFailure.UNAVAILABLE), rows[UpstreamComponent.SHERPA_ONNX])
        assertEquals(UpstreamStatus.UpToDate, rows[UpstreamComponent.KOKORO_VOICE])
    }

    @Test
    fun answersThatAreNotWhatWasExpectedAreUnreadable() {
        publishAllCurrent()
        server.serve(stockfishPath, "<html>nope</html>".toByteArray())
        server.serveGzipped(sherpaPath, """{"tag_name":"nightly"}""".toByteArray())
        server.serveGzipped(ttsPath, tts(asset("matcha-icefall-en_US-ljspeech.tar.bz2", 2)))
        val rows = rowsOf()
        assertEquals(UpstreamStatus.Failed(UpstreamFailure.UNREADABLE), rows[UpstreamComponent.STOCKFISH])
        assertEquals(UpstreamStatus.Failed(UpstreamFailure.UNREADABLE), rows[UpstreamComponent.SHERPA_ONNX])
        assertEquals(UpstreamStatus.Failed(UpstreamFailure.UNREADABLE), rows[UpstreamComponent.KOKORO_VOICE])
    }

    @Test
    fun anAnswerOverTheSizeCapIsRefusedNotTruncated() {
        publishAllCurrent()
        // 300 KB of JSON for Stockfish, whose cap is 256 KB (its real answer is about 20 KB).
        val padding = "a".repeat(300 * 1024)
        server.serve(stockfishPath, """{"tag_name":"sf_20","pad":"$padding"}""".toByteArray())
        assertEquals(UpstreamStatus.Failed(UpstreamFailure.UNREADABLE), rowsOf()[UpstreamComponent.STOCKFISH])
    }

    @Test
    fun aGzipBombIsRefusedByItsInflatedSizeWhileSmallOnTheWire() {
        publishAllCurrent()
        // 40 MB of spaces compresses to about 40 KB: small on the wire, far over the 256 KB cap once inflated.
        val bomb = ByteArray(40 * 1024 * 1024) { ' '.code.toByte() }
        server.serveGzipped(stockfishPath, bomb)
        assertEquals(UpstreamStatus.Failed(UpstreamFailure.UNREADABLE), rowsOf()[UpstreamComponent.STOCKFISH])
    }

    @Test
    fun noNetworkMeansNoRequestAndEveryRowSaysSo() {
        publishAllCurrent()
        network = NetworkCost.UNAVAILABLE
        val rows = runBlocking { checker().check() }
        assertEquals(3, rows.size)
        assertTrue(rows.all { it.status == UpstreamStatus.Failed(UpstreamFailure.NO_INTERNET) })
        assertTrue("nothing was requested", server.requests.isEmpty())
    }

    @Test
    fun anHttpUrlOutsideTheLoopbackExceptionIsRefusedWithoutARequest() {
        // The release downloader has no cleartext exception: an http base URL is refused before any connection.
        val strict = ModelDownloader("t", allowCleartextLoopback = false)
        val rows = runBlocking { UpstreamChecker(strict, { network }, UpstreamSources.defaults(ours, server.baseUrl)).check() }
        assertTrue(rows.all { it.status == UpstreamStatus.Failed(UpstreamFailure.UNREADABLE) })
        assertTrue(server.requests.isEmpty())
    }

    @Test
    fun theDefaultSourcesAskGitHubsApiOverHttpsAndNothingElse() {
        val urls = UpstreamSources.defaults(ours).map { it.url }
        assertEquals(
            listOf(
                "https://api.github.com/repos/official-stockfish/Stockfish/releases/latest",
                "https://api.github.com/repos/k2-fsa/sherpa-onnx/releases/latest",
                "https://api.github.com/repos/k2-fsa/sherpa-onnx/releases/tags/tts-models",
            ),
            urls,
        )
        assertFalse(urls.any { it.contains("huggingface") })
    }

    @Test
    fun theRowListIsDrivenBySourcesSoAnotherComponentIsOneMoreSource() {
        publishAllCurrent()
        val extra = UpstreamSource(UpstreamComponent.STOCKFISH, "x", server.url(stockfishPath), 1024) { UpstreamStatus.UpToDate }
        val c = UpstreamChecker(ModelDownloader("t", true, 2_000, 2_000), { network }, UpstreamSources.defaults(ours, server.baseUrl) + extra)
        assertEquals(4, runBlocking { c.check() }.size)
        assertEquals(4, c.pendingRows().size)
        assertTrue(c.pendingRows().all { it.status == UpstreamStatus.Checking })
    }
}
