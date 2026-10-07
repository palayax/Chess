package net.palaya.chessanalyzer.data.models

import java.io.File
import java.security.MessageDigest
import java.util.Collections
import kotlin.random.Random
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import net.palaya.chessanalyzer.data.models.FaultHttpServer.Fault
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * [ModelDownloader] against [FaultHttpServer] on 127.0.0.1, the fault matrix of design §6.1: every case
 * is a real HTTP exchange through `HttpURLConnection`. Backoff waits are recorded, not slept.
 *
 * Shared (D2d): the host runs it as `ModelDownloaderTest` (the JVM's HttpURLConnection) and the device
 * runs the very same cases as `ModelDownloaderInstrumentedTest` (Android's HttpURLConnection, the debug
 * network security config, the device's loopback), as design §6.2 asks. Plain JUnit 4 only: no host- or
 * device-specific API (`TemporaryFolder` uses `java.io.tmpdir`, the app's cache dir on Android).
 */
abstract class ModelDownloaderFaultMatrix {

    @get:Rule val tmp = TemporaryFolder()

    private val path = "models-2026.10/nn-0123456789ab.nnue"
    private val body: ByteArray = Random(42).nextBytes(700_000)
    private val sha: String = sha256(body)
    private lateinit var server: FaultHttpServer
    private lateinit var part: File
    private val sleeps: MutableList<Long> = Collections.synchronizedList(ArrayList())
    private val states: MutableList<DownloadState> = Collections.synchronizedList(ArrayList())
    private val logLines: MutableList<String> = Collections.synchronizedList(ArrayList())

    private val listener = object : DownloadListener {
        override fun onState(state: DownloadState) { states += state }
    }

    @Before
    fun setUp() {
        server = FaultHttpServer().serve(path, body)
        part = File(tmp.root, "nets/nn-0123456789ab.nnue.part")
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun downloader(cleartext: Boolean = true, readTimeoutMs: Int = 1_000) = ModelDownloader(
        userAgent = USER_AGENT,
        allowCleartextLoopback = cleartext,
        connectTimeoutMs = 2_000,
        readTimeoutMs = readTimeoutMs,
        sleep = { sleeps += it },
        log = { logLines += it },
    )

    private fun spec(url: String = server.url(path), size: Long = body.size.toLong(), hash: String = sha) =
        ModelFileSpec(url = url, fileName = "nn-0123456789ab.nnue", sizeBytes = size, sha256 = hash)

    private fun download(d: ModelDownloader = downloader(), s: ModelFileSpec = spec()): DownloadResult =
        runBlocking { withTimeout(60_000) { d.download(s, part, listener) } }

    private fun writePrefix(n: Int) {
        part.parentFile!!.mkdirs()
        part.writeBytes(body.copyOf(n))
    }

    private fun assertComplete(result: DownloadResult) {
        assertEquals(DownloadResult.Verified(part), result)
        assertArrayEquals("the part must hold exactly the file", body, part.readBytes())
    }

    // ---- clean and resumed downloads ----

    @Test
    fun cleanDownloadIsVerifiedWithOneRequestAndTheRightHeaders() {
        assertComplete(download())
        val requests = server.requestsFor(path)
        assertEquals(1, requests.size)
        assertNull("a fresh download sends no Range", requests[0].range)
        assertEquals(USER_AGENT, requests[0].headers["user-agent"])
        assertEquals("identity", requests[0].headers["accept-encoding"])
        assertTrue(sleeps.isEmpty())
        assertEquals(DownloadState.Verified, states.last())
        assertTrue(states.contains(DownloadState.Streaming(0, 0)))
    }

    @Test
    fun aTruncatedBodyResumesWithARangeAndTheRehashedPrefix() {
        server.fault(path, Fault.TruncateAfter(300_000))
        assertComplete(download())
        val requests = server.requestsFor(path)
        assertEquals(2, requests.size)
        assertEquals("bytes=300000-", requests[1].range)
        assertEquals(listOf(2_000L), sleeps)
        assertTrue(states.contains(DownloadState.Backoff(1, 0, 2_000)))
    }

    @Test
    fun aDroppedConnectionMidFileResumesAndCompletes() {
        server.fault(path, Fault.DropAfter(250_000))
        assertComplete(download())
        val requests = server.requestsFor(path)
        assertEquals(2, requests.size)
        // The RST can discard bytes still in flight, so the exact offset is the client's to know: it
        // must be what was on disk, never past the drop.
        val range = requests[1].range
        if (range != null) {
            val offset = Regex("""bytes=(\d+)-""").matchEntire(range)!!.groupValues[1].toLong()
            assertTrue("resumed at $offset", offset in 1..250_000)
        }
        assertEquals(listOf(2_000L), sleeps)
    }

    @Test
    fun anExistingPartIsResumedNotRestarted() {
        writePrefix(123_456)
        assertComplete(download())
        assertEquals("bytes=123456-", server.requestsFor(path).single().range)
    }

    @Test
    fun aCompleteVerifiedPartIsReusedWithoutAnyRequest() {
        writePrefix(body.size)
        assertComplete(download())
        assertTrue(server.requests.isEmpty())
    }

    @Test
    fun aCompleteButWrongPartIsDeletedAndFetchedAgain() {
        part.parentFile!!.mkdirs()
        part.writeBytes(ByteArray(body.size))
        assertComplete(download())
        assertNull(server.requestsFor(path).single().range)
    }

    @Test
    fun aSlowServerStillCompletesWithManyProgressUpdates() {
        server.fault(path, Fault.Slow(bytesPerSecond = 600_000))
        val progress = Collections.synchronizedList(ArrayList<Long>())
        val result = runBlocking {
            downloader().download(spec(), part, object : DownloadListener {
                override fun onBytes(done: Long, total: Long) { progress += done }
            })
        }
        assertComplete(result)
        assertEquals(1, server.requestsFor(path).size)
        assertTrue("progress was reported ${progress.size} times", progress.size >= 3)
        assertEquals(progress.sorted(), progress)
        assertEquals(body.size.toLong(), progress.last())
    }

    @Test
    fun aStallLongerThanTheReadTimeoutIsTransientAndResumes() {
        server.fault(path, Fault.Stall(afterBytes = 200_000, millis = 3_000))
        assertComplete(download(downloader(readTimeoutMs = 500)))
        val requests = server.requestsFor(path)
        assertEquals(2, requests.size)
        assertEquals("bytes=200000-", requests[1].range)
        assertEquals(listOf(2_000L), sleeps)
    }

    // ---- hash and size ----

    @Test
    fun aWrongHashDeletesThePartAndRestartsOnceFromZero() {
        server.fault(path, Fault.CorruptByteAt(350_000))
        assertComplete(download())
        val requests = server.requestsFor(path)
        assertEquals(2, requests.size)
        assertNull("the bad part was deleted, so the restart sends no Range", requests[1].range)
        assertTrue(states.contains(DownloadState.HashFailed(1)))
    }

    @Test
    fun aWrongHashTwiceFailsAsDamagedAndLeavesNoPart() {
        server.alwaysFault(path, Fault.CorruptByteAt(10))
        val result = download()
        assertEquals(FailureReason.DAMAGED, (result as DownloadResult.Failed).reason)
        assertFalse("the part must be deleted", part.exists())
        assertEquals(2, server.requestsFor(path).size)
    }

    /**
     * R8: the emulator's user-mode network (10.0.2.2) dropped single bytes in the last ~128 KB of a long
     * response. The body then ends N bytes short; the resume asks for exactly the bytes still missing by
     * count, gets the file's real last N bytes, and the whole no longer hashes. That file must never be
     * accepted: one automatic restart, then DAMAGED, and no part left behind.
     */
    @Test
    fun bytesLostInTransitAreNeverAcceptedAndTwiceInARowAreDamaged() {
        val size = body.size.toLong()
        val lost = (1..9).map { size - 100 - it * 8_640L }.toSet()
        server.alwaysFault(path, Fault.LoseBytesAt(lost))
        val result = download()
        assertEquals(FailureReason.DAMAGED, (result as DownloadResult.Failed).reason)
        assertFalse("the part must be deleted", part.exists())
        assertFalse(states.contains(DownloadState.Verified))
        assertEquals(
            "short body -> resume at exactly the bytes on disk -> bad hash -> restart from 0 -> the same again",
            listOf(null, "bytes=${size - 9}-", null, "bytes=${size - 9}-"),
            server.requestsFor(path).map { it.range },
        )
        assertTrue(states.contains(DownloadState.HashFailed(1)))
    }

    @Test
    fun bytesLostInTransitOnceAreRecoveredByTheAutomaticRestart() {
        val size = body.size.toLong()
        server.fault(path, Fault.LoseBytesAt(setOf(size - 30_000, size - 21_360, size - 12_720)))
        assertComplete(download())
        assertEquals(listOf(null, "bytes=${size - 3}-", null), server.requestsFor(path).map { it.range })
        assertTrue(states.contains(DownloadState.HashFailed(1)))
    }

    @Test
    fun aContentLengthThatDiffersFromThePinIsFatalAndDeletesThePart() {
        server.fault(path, Fault.WrongTotalSize(claimedTotal = body.size + 1L))
        val result = download()
        assertEquals(FailureReason.SIZE_MISMATCH, (result as DownloadResult.Failed).reason)
        assertFalse(part.exists())
        assertEquals(1, server.requestsFor(path).size)
    }

    @Test
    fun aContentRangeTotalThatDiffersFromThePinIsFatal() {
        writePrefix(1_000)
        server.fault(path, Fault.WrongTotalSize(claimedTotal = 5L))
        val result = download()
        assertEquals(FailureReason.SIZE_MISMATCH, (result as DownloadResult.Failed).reason)
        assertFalse(part.exists())
    }

    @Test
    fun aPinnedSizeOverOneGigabyteIsRefusedBeforeAnyRequest() {
        val result = download(s = spec(size = (1L shl 30) + 1))
        assertEquals(FailureReason.SIZE_MISMATCH, (result as DownloadResult.Failed).reason)
        assertTrue(server.requests.isEmpty())
    }

    // ---- ranges ----

    @Test
    fun aServerThatIgnoresTheRangeRestartsTheFileFromZero() {
        writePrefix(400_000)
        server.fault(path, Fault.IgnoreRange)
        assertComplete(download())
        val requests = server.requestsFor(path)
        assertEquals(1, requests.size)
        assertEquals("the Range was sent, the server ignored it", "bytes=400000-", requests[0].range)
    }

    @Test
    fun a416DeletesThePartAndRestartsFromZero() {
        writePrefix(50_000)
        server.fault(path, Fault.Status(416))
        assertComplete(download())
        val requests = server.requestsFor(path)
        assertEquals(2, requests.size)
        assertEquals("bytes=50000-", requests[0].range)
        assertNull(requests[1].range)
        assertEquals(listOf(2_000L), sleeps)
    }

    // ---- status codes ----

    @Test
    fun notFoundIsFatalAtOnceAndKeepsAnExistingPart() {
        writePrefix(10_000)
        for (code in listOf(404, 410)) {
            server.fault(path, Fault.Status(code))
            val result = download()
            assertEquals(FailureReason.NOT_FOUND, (result as DownloadResult.Failed).reason)
            assertEquals("the part survives a 404 for a later try", 10_000L, part.length())
        }
        assertEquals(2, server.requestsFor(path).size)
        assertTrue(sleeps.isEmpty())
    }

    @Test
    fun anotherClientErrorIsFatal() {
        server.fault(path, Fault.Status(403))
        assertEquals(FailureReason.SERVER, (download() as DownloadResult.Failed).reason)
    }

    @Test
    fun aServerErrorIsRetriedAfterABackoff() {
        server.fault(path, Fault.Status(500))
        assertComplete(download())
        assertEquals(listOf(2_000L), sleeps)
        assertEquals(2, server.requestsFor(path).size)
    }

    @Test
    fun fiveServerErrorsInARowPauseAsServerUnavailable() {
        server.alwaysFault(path, Fault.Status(500))
        assertEquals(DownloadResult.Paused(PauseReason.SERVER_UNAVAILABLE), download())
        assertEquals(5, server.requestsFor(path).size)
        assertEquals(listOf(2_000L, 4_000L, 8_000L, 16_000L), sleeps)
    }

    @Test
    fun aShortRetryAfterIsHonouredAndALongOneIsNot() {
        server.fault(path, Fault.Status(503, retryAfterSeconds = 7))
        server.fault(path, Fault.Status(429, retryAfterSeconds = 120))
        assertComplete(download())
        assertEquals(listOf(7_000L, 4_000L), sleeps)
    }

    @Test
    fun fiveDroppedConnectionsPauseAsConnectionLostAndKeepThePart() {
        server.alwaysFault(path, Fault.TruncateAfter(1_000))
        val result = download()
        assertEquals(DownloadResult.Paused(PauseReason.CONNECTION_LOST), result)
        assertEquals(5, server.requestsFor(path).size)
        assertTrue("Paused keeps what arrived", part.length() > 0)
        assertEquals(DownloadState.Paused(PauseReason.CONNECTION_LOST), states.last())
    }

    // ---- redirects and the https rule ----

    @Test
    fun aRedirectToAnotherHostIsFollowedAndTheRangeIsSentAgain() {
        FaultHttpServer().serve(path, body).use { cdn ->
            for (code in listOf(302, 307)) {
                part.delete()
                writePrefix(100_000)
                server.fault(path, Fault.RedirectTo(cdn.url(path) + "?sig=secret-token", code))
                assertComplete(download())
            }
            val first = server.requestsFor(path)
            val second = cdn.requestsFor(path)
            assertEquals(2, first.size)
            assertEquals(2, second.size)
            assertTrue((first + second).all { it.range == "bytes=100000-" })
        }
        assertFalse("the signed redirect URL must never be logged", logLines.any { it.contains("secret-token") })
        assertTrue(logLines.any { it.contains("redirect 302 to host 127.0.0.1") })
    }

    @Test
    fun aRedirectToPlainHttpOnAnotherHostIsRefused() {
        server.fault(path, Fault.RedirectToHttp)
        assertEquals(FailureReason.INSECURE, (download() as DownloadResult.Failed).reason)
        assertEquals(1, server.requests.size)
    }

    @Test
    fun aRedirectLoopFailsAfterFiveHops() {
        server.alwaysFault(path, Fault.RedirectLoop)
        assertEquals(FailureReason.SERVER, (download() as DownloadResult.Failed).reason)
        assertEquals(ModelDownloader.MAX_REDIRECTS + 1, server.requestsFor(path).size)
    }

    @Test
    fun plainHttpIsRefusedOutsideTheDebugLoopbackException() {
        val result = download(downloader(cleartext = false))
        assertEquals(FailureReason.INSECURE, (result as DownloadResult.Failed).reason)
        assertTrue("nothing may be requested", server.requests.isEmpty())
    }

    @Test
    fun theHttpsRule() {
        val debug = downloader(cleartext = true)
        val release = downloader(cleartext = false)
        for (d in listOf(debug, release)) {
            assertTrue(d.isAllowed("https://github.com/palayax/palaya-chess/releases/download/models-2026.10/x.nnue"))
            assertTrue(d.isAllowed("https://objects.githubusercontent.com/abc?x=1"))
            assertFalse(d.isAllowed("http://github.com/x"))
            assertFalse(d.isAllowed("http://example.invalid/x"))
            assertFalse(d.isAllowed("ftp://127.0.0.1/x"))
            assertFalse(d.isAllowed("not a url"))
        }
        for (host in listOf("10.0.2.2:8787", "127.0.0.1", "localhost:1")) {
            assertTrue(debug.isAllowed("http://$host/x"))
            assertFalse(release.isAllowed("http://$host/x"))
        }
        assertFalse(debug.isAllowed("http://10.0.2.3/x"))
    }

    // ---- pause ----

    @Test
    fun pausingMidStreamKeepsThePartAndResumingContinuesFromIt() = runBlocking {
        server.fault(path, Fault.Slow(bytesPerSecond = 200_000))
        val reached = CompletableDeferred<Unit>()
        val job = launch(Dispatchers.Default) {
            downloader().download(spec(), part, object : DownloadListener {
                override fun onState(state: DownloadState) { states += state }
                override fun onBytes(done: Long, total: Long) { if (done >= 100_000) reached.complete(Unit) }
            })
        }
        withTimeout(30_000) { reached.await() }
        job.cancelAndJoin()
        val kept = part.length()
        assertTrue("the part is kept on pause ($kept bytes)", kept in 1 until body.size)
        assertEquals(DownloadState.Paused(PauseReason.USER), states.last())

        val result = downloader().download(spec(), part, listener)
        assertComplete(result)
        assertEquals("bytes=$kept-", server.requestsFor(path).last().range)
    }

    @Test
    fun outOfSpaceIsRecognisedAnywhereInTheCauseChain() {
        assertTrue(ModelDownloader.isOutOfSpace(java.io.IOException("write failed: ENOSPC (No space left on device)")))
        assertTrue(ModelDownloader.isOutOfSpace(java.io.IOException("outer", java.io.IOException("No space left on device"))))
        assertFalse(ModelDownloader.isOutOfSpace(java.io.IOException("Connection reset")))
    }

    @Test
    fun onlyTheHostOfAUrlIsEverLogged() {
        assertEquals("objects.githubusercontent.com", ModelDownloader.hostOf("https://objects.githubusercontent.com/a/b?sig=xyz"))
        assertEquals("?", ModelDownloader.hostOf("nonsense"))
    }

    private companion object {
        const val USER_AGENT = "PalayaChess/test"
    }
}
