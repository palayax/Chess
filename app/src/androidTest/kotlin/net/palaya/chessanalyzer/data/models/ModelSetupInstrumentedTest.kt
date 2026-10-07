package net.palaya.chessanalyzer.data.models

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.util.Collections
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import net.palaya.chessanalyzer.TestApp
import net.palaya.chessanalyzer.data.models.FaultHttpServer.Fault
import net.palaya.chessanalyzer.engine.NetStore
import net.palaya.chessanalyzer.video.VoiceStore
import org.junit.After
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith

/**
 * [ModelSetup] end to end on a device with the two REAL model files and this build's pins (D2d; the
 * successor of the bundled builds' `FirstRunSetupInstrumentedTest`, which copied them out of the APK).
 * They are served from the test APK's seed assets by [FaultHttpServer] in-process on 127.0.0.1, so the
 * whole first-run tail runs for real: HTTP, resume, size and SHA-256 against the pins, the NNUE header
 * check, the atomic move into `nets/`, and the voice unpacked by [VoiceStore.installFromTar].
 *
 * Covers the outcomes the old test covered, in their download form: progress that is monotonic and
 * reaches 1 (byte-weighted over net then voice), a second run that does nothing and asks for nothing,
 * low storage before any request, only the missing file fetched, and a voice that does not match its pin.
 * Scratch directories only: the app's own installed models are never touched.
 */
@RunWith(AndroidJUnit4::class)
class ModelSetupInstrumentedTest {

    companion object {
        private const val TAG = "models-test"
        private lateinit var server: FaultHttpServer
        private lateinit var netSeed: SeedAssetBody
        private lateinit var voiceSeed: SeedAssetBody

        private val netPath get() = "${GeneratedModelPins.RELEASE_TAG}/${NetStore.NET_FILENAME}"
        private val voicePath get() = "${GeneratedModelPins.RELEASE_TAG}/${GeneratedModelPins.VOICE_FILE_NAME}"

        @BeforeClass
        @JvmStatic
        fun startServer() {
            netSeed = SeedAssetBody(TestApp.netSeedPath)
            voiceSeed = SeedAssetBody(TestApp.voiceSeedPath)
            server = FaultHttpServer().serveBody(netPath, netSeed).serveBody(voicePath, voiceSeed)
        }

        @AfterClass
        @JvmStatic
        fun stopServer() {
            server.close()
            netSeed.close()
            voiceSeed.close()
        }
    }

    private lateinit var dir: File
    private val log: MutableList<String> = Collections.synchronizedList(ArrayList())

    private val netStore get() = NetStore(dir)
    private val voiceStore
        get() = VoiceStore(
            filesDir = dir,
            usableSpace = { Long.MAX_VALUE },
            pinnedSha256 = GeneratedModelPins.VOICE_SHA256,
            pinnedSizeBytes = GeneratedModelPins.VOICE_SIZE_BYTES,
        )

    @Before
    fun setUp() {
        dir = File(TestApp.context.filesDir, "model-setup-test-${System.nanoTime()}").apply { mkdirs() }
        server.clearFaults()
        server.requests.clear()
    }

    @After
    fun tearDown() {
        server.clearFaults()
        dir.deleteRecursively()
    }

    private fun setup(free: Long = Long.MAX_VALUE) = ModelSetup(
        netStore = netStore,
        voiceStore = voiceStore,
        downloader = ModelDownloader(
            userAgent = "PalayaChess/test (Android)",
            allowCleartextLoopback = true,
            log = { log += it },
        ),
        baseUrl = server.baseUrl,
        freeBytes = { free },
    )

    private fun run(s: ModelSetup, progress: MutableList<SetupProgress> = ArrayList()): SetupOutcome =
        runBlocking { withTimeout(900_000) { s.run { synchronized(progress) { progress += it } } } }

    private fun partFiles(): List<File> = dir.walkTopDown().filter { it.isFile && it.name.endsWith(".part") }.toList()

    private fun assertBothInstalled() {
        assertNotNull("the engine gate must accept the downloaded net", netStore.verifiedNetOrNull())
        assertTrue("the voice must be installed", voiceStore.isInstalled())
        assertTrue("no part file may remain: ${partFiles()}", partFiles().isEmpty())
    }

    @Test
    fun aFreshDirectoryDownloadsBothFilesWithMonotonicByteWeightedProgress() {
        val s = setup()
        assertTrue(s.needsNet())
        assertTrue(s.needsVoice())
        assertEquals(NetStore.NET_SIZE_BYTES + GeneratedModelPins.VOICE_DOWNLOAD_SIZE_BYTES, s.bytesLeft())

        val progress = ArrayList<SetupProgress>()
        assertEquals(SetupOutcome.Complete, run(s, progress))

        assertBothInstalled()
        assertTrue(s.isComplete())
        assertEquals(0L, s.bytesLeft())
        assertEquals("one request per file, no Range", listOf(null), server.requestsFor(netPath).map { it.range })
        assertEquals(listOf(null), server.requestsFor(voicePath).map { it.range })

        val fractions = progress.map { it.overallFraction }
        assertTrue("progress was barely reported: ${fractions.size} values", fractions.size > 20)
        assertEquals("monotonic", fractions.sorted(), fractions)
        assertEquals("ends at exactly 1", 1f, fractions.last(), 0f)
        assertEquals(SetupStatus.DONE, progress.last().status)
        assertEquals(NetStore.NET_SIZE_BYTES + GeneratedModelPins.VOICE_DOWNLOAD_SIZE_BYTES, progress.last().bytesTotal)
        assertTrue("the unpacking phase was shown", progress.any { it.status == SetupStatus.UNPACKING })
        // The net is 98.5 of 201.1 MB (the voice downloads as a 102.5 MB .tar.gz since D2f): the bar passes
        // about 49% only once the net is done.
        val netShare = NetStore.NET_SIZE_BYTES.toFloat() / (NetStore.NET_SIZE_BYTES + GeneratedModelPins.VOICE_DOWNLOAD_SIZE_BYTES)
        val atNetInstalled = progress.first { p -> p.perFile.any { it.file == ModelFile.NET && it.done } }
        assertEquals("byte-weighted across the net then the voice", netShare, atNetInstalled.overallFraction, 0.01f)
        android.util.Log.i(TAG, "fresh setup: ${progress.size} snapshots, log: ${log.size} lines")
    }

    @Test
    fun aSecondRunIsANoOpThatAsksTheServerForNothing() {
        assertEquals(SetupOutcome.Complete, run(setup()))
        val netStamp = netStore.netFile.lastModified()
        val markerStamp = File(voiceStore.modelDir, VoiceStore.MARKER_NAME).lastModified()
        server.requests.clear()
        Thread.sleep(1100)

        val progress = ArrayList<SetupProgress>()
        // A brand-new ModelSetup over the same directory: a cold start.
        assertEquals(SetupOutcome.Complete, run(setup(), progress))

        assertTrue("nothing may be requested once both files are in: ${server.requests}", server.requests.isEmpty())
        assertEquals("a no-op reports DONE once and nothing else", listOf(SetupStatus.DONE), progress.map { it.status })
        assertEquals(netStamp, netStore.netFile.lastModified())
        assertEquals(markerStamp, File(voiceStore.modelDir, VoiceStore.MARKER_NAME).lastModified())
        assertFalse(setup().needsNet())
        assertFalse(setup().needsVoice())
    }

    @Test
    fun tooLittleFreeSpaceStopsBeforeTheRequestThatWouldNotFit() {
        val margin = ModelSetup.SAFETY_MARGIN_BYTES
        // Not even the net fits: nothing is requested and nothing is written.
        val netNeeded = NetStore.NET_SIZE_BYTES + margin
        val outcome = run(setup(free = netNeeded - 1))
        assertEquals(ModelFile.NET, (outcome as SetupOutcome.Failed).file)
        assertEquals(FailureReason.INSUFFICIENT_STORAGE, outcome.reason)
        assertTrue("nothing may be requested when space is short", server.requests.isEmpty())
        assertTrue("nothing may be written when space is short", partFiles().isEmpty() && !netStore.netFile.exists())

        // Exactly enough for the net, not for the voice (tar plus unpacked model): the net is installed
        // (analysis works), the voice stops before its request.
        val second = run(setup(free = netNeeded))
        assertEquals(ModelFile.VOICE, (second as SetupOutcome.Failed).file)
        assertEquals(FailureReason.INSUFFICIENT_STORAGE, second.reason)
        assertNotNull(netStore.verifiedNetOrNull())
        assertEquals(1, server.requestsFor(netPath).size)
        assertTrue("the voice was never requested", server.requestsFor(voicePath).isEmpty())
        assertFalse(voiceStore.isInstalled())
    }

    @Test
    fun onlyTheMissingFileIsFetchedAndCounted() {
        assertEquals(SetupOutcome.Complete, run(setup()))
        File(dir, VoiceStore.ROOT_DIR_NAME).deleteRecursively()
        server.requests.clear()
        val s = setup()
        assertFalse(s.needsNet())
        assertTrue(s.needsVoice())
        assertEquals(GeneratedModelPins.VOICE_DOWNLOAD_SIZE_BYTES, s.bytesLeft())
        // The .tar.gz and the unpacked voice exist together at the peak, plus the margin.
        assertEquals(
            GeneratedModelPins.VOICE_DOWNLOAD_SIZE_BYTES + GeneratedModelPins.VOICE_SIZE_BYTES + ModelSetup.SAFETY_MARGIN_BYTES,
            s.storageNeeded(),
        )

        val progress = ArrayList<SetupProgress>()
        assertEquals(SetupOutcome.Complete, run(s, progress))

        assertTrue("the installed net is not fetched again", server.requestsFor(netPath).isEmpty())
        assertEquals(1, server.requestsFor(voicePath).size)
        assertEquals("only the voice counts", GeneratedModelPins.VOICE_DOWNLOAD_SIZE_BYTES, progress.last().bytesTotal)
        val fractions = progress.map { it.overallFraction }
        assertEquals("starts at 0: the installed net is not counted", 0f, fractions.first(), 0f)
        assertEquals("monotonic", fractions.sorted(), fractions)
        assertBothInstalled()
    }

    @Test
    fun aDroppedConnectionResumesWithARangeAndStillVerifies() {
        server.fault(voicePath, Fault.DropAfter(60_000_000))
        assertEquals(SetupOutcome.Complete, run(setup()))
        val voiceRequests = server.requestsFor(voicePath)
        assertEquals(2, voiceRequests.size)
        assertTrue("the second request resumes: ${voiceRequests[1].range}", voiceRequests[1].range?.startsWith("bytes=") == true)
        assertBothInstalled()
    }

    @Test
    fun aVoiceThatDoesNotMatchItsPinIsDamagedAndNeverInstalledButTheNetIs() {
        // Every voice response has one flipped byte: the first mismatch restarts once, the second fails.
        server.alwaysFault(voicePath, Fault.CorruptByteAt(GeneratedModelPins.VOICE_DOWNLOAD_SIZE_BYTES / 2))
        val outcome = run(setup())

        assertEquals(ModelFile.VOICE, (outcome as SetupOutcome.Failed).file)
        assertEquals(FailureReason.DAMAGED, outcome.reason)
        assertEquals("one automatic restart after a bad hash, then stop", 2, server.requestsFor(voicePath).size)
        assertFalse("a damaged voice must never count as installed", voiceStore.isInstalled())
        assertFalse("the damaged part is deleted", voiceStore.partFile.exists())
        assertNotNull("the net still went in: analysis is unblocked", netStore.verifiedNetOrNull())
        assertFalse(setup().needsNet())
    }
}
