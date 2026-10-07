package net.palaya.chessanalyzer.data.models

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.util.Collections
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import net.palaya.chessanalyzer.TestApp
import net.palaya.chessanalyzer.data.models.FaultHttpServer.Fault
import net.palaya.chessanalyzer.engine.NetStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The downloader's whole fault matrix ([ModelDownloaderFaultMatrix], the same cases the host runs as
 * `ModelDownloaderTest`) on the device, against [FaultHttpServer] **in-process on 127.0.0.1**
 * (docs/MODEL_DOWNLOAD_DESIGN.md §6.2): Android's own HttpURLConnection, the debug network security
 * config (cleartext to 127.0.0.1 only), the device's loopback. Not the emulator's NAT to the host, which
 * loses bytes on fast transfers (RUN_LOG D2c).
 *
 * Plus the real thing end to end: the pinned 98.5 MB net, served from the test APK's seed asset, dropped
 * mid-file, resumed with a Range, verified against the build's pins and installed by [NetStore].
 */
@RunWith(AndroidJUnit4::class)
class ModelDownloaderInstrumentedTest : ModelDownloaderFaultMatrix() {

    @Test
    fun theRealNetIsDownloadedResumedAfterADropVerifiedAndInstalled(): Unit = runBlocking {
        val dir = File(TestApp.context.filesDir, "downloader-real-net-${System.nanoTime()}").apply { mkdirs() }
        val store = NetStore(dir)
        val path = "models-test/${NetStore.NET_FILENAME}"
        SeedAssetBody(TestApp.netSeedPath).use { seed ->
            FaultHttpServer().use { server ->
                server.serveBody(path, seed)
                server.fault(path, Fault.DropAfter(40_000_000))
                val progress = Collections.synchronizedList(ArrayList<Long>())
                val downloader = ModelDownloader(
                    userAgent = "PalayaChess/test (Android)",
                    allowCleartextLoopback = true,
                    sleep = {}, // the 2 s backoff is the state machine's; not worth waiting for here
                )
                try {
                    val spec = ModelFileSpec(
                        url = server.url(path),
                        fileName = NetStore.NET_FILENAME,
                        sizeBytes = NetStore.NET_SIZE_BYTES,
                        sha256 = NetStore.NET_SHA256,
                    )
                    val result = withTimeout(600_000) {
                        downloader.download(spec, store.partFileFor(), object : DownloadListener {
                            override fun onBytes(done: Long, total: Long) { progress += done }
                        })
                    }
                    assertEquals(DownloadResult.Verified(store.partFileFor()), result)

                    val requests = server.requestsFor(path)
                    assertEquals("one drop, one resume: ${requests.map { it.range }}", 2, requests.size)
                    assertNull(requests[0].range)
                    val resumedAt = Regex("""bytes=(\d+)-""").matchEntire(requests[1].range.orEmpty())?.groupValues?.get(1)?.toLong()
                    assertTrue("resumed from what was on disk, never past the drop: ${requests[1].range}", resumedAt != null && resumedAt in 1..40_000_000L)
                    assertEquals("progress ends at the pinned size", NetStore.NET_SIZE_BYTES, progress.last())

                    val installed = store.installVerified(store.partFileFor())
                    assertEquals(installed, NetStore(dir).verifiedNetOrNull())
                    assertFalse(store.partFileFor().exists())
                } finally {
                    dir.deleteRecursively()
                }
            }
        }
    }
}
