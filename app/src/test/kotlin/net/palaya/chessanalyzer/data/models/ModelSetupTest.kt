package net.palaya.chessanalyzer.data.models

import java.io.File
import java.util.Collections
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import net.palaya.chessanalyzer.data.models.FaultHttpServer.Fault
import net.palaya.chessanalyzer.diagnostics.DiagnosticLog
import net.palaya.chessanalyzer.engine.NetPins
import net.palaya.chessanalyzer.engine.NetStore
import net.palaya.chessanalyzer.video.VoiceDownload
import net.palaya.chessanalyzer.video.VoiceStore
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
 * [ModelSetup] end to end on the host: real [ModelDownloader] against [FaultHttpServer], real
 * [NetStore] and [VoiceStore] on a temporary `filesDir`, small stand-in model files
 * ([TestModelFiles]). Covers order (net first), what "needs" means, the storage arithmetic of design
 * §1.3, pause vs cancel, the migration from a bundled build (§8: nothing downloaded) and the log.
 */
class ModelSetupTest {

    @get:Rule val tmp = TemporaryFolder()

    private val tag = "models-2026.10"
    private val netBytes = TestModelFiles.netBytes()
    private val pins: NetPins = TestModelFiles.pinsFor(netBytes)
    private val tarBytes = TestModelFiles.voiceTar()
    private val voiceName = GeneratedModelPins.VOICE_FILE_NAME
    private lateinit var server: FaultHttpServer
    private lateinit var filesDir: File
    private lateinit var log: DiagnosticLog
    private val sleeps: MutableList<Long> = Collections.synchronizedList(ArrayList())

    private val netPath get() = "$tag/${pins.fileName}"
    private val voicePath get() = "$tag/$voiceName"

    @Before
    fun setUp() {
        filesDir = tmp.newFolder("files")
        log = DiagnosticLog(File(tmp.root, "logs"))
        server = FaultHttpServer().serve(netPath, netBytes).serve(voicePath, tarBytes)
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun netStore() = NetStore(filesDir, pins)
    private fun voiceStore(tar: ByteArray = tarBytes) = TestModelFiles.voiceStoreFor(filesDir, tar)

    private fun setup(
        free: Long = Long.MAX_VALUE,
        voice: VoiceStore = voiceStore(),
        net: NetStore = netStore(),
    ) = ModelSetup(
        netStore = net,
        voiceStore = voice,
        downloader = ModelDownloader(
            userAgent = "test",
            allowCleartextLoopback = true,
            connectTimeoutMs = 2_000,
            readTimeoutMs = 2_000,
            sleep = { sleeps += it },
            log = { log.log(ModelSetup.TAG, it) },
        ),
        baseUrl = server.baseUrl,
        releaseTag = tag,
        freeBytes = { free },
        diagnostics = log,
    )

    private fun run(s: ModelSetup, progress: MutableList<SetupProgress> = ArrayList()): SetupOutcome =
        runBlocking { withTimeout(60_000) { s.run { progress += it } } }

    private fun logText(): String = log.currentFile.readText()

    @Test
    fun aFreshInstallNeedsBothAndDownloadsTheNetThenTheVoice() {
        val s = setup()
        assertTrue(s.needsNet())
        assertTrue(s.needsVoice())
        assertFalse(s.isComplete())
        assertEquals(netBytes.size.toLong() + tarBytes.size, s.bytesLeft())

        val progress = ArrayList<SetupProgress>()
        assertEquals(SetupOutcome.Complete, run(s, progress))

        assertArrayEquals(netBytes, File(filesDir, "nets/${pins.fileName}").readBytes())
        assertTrue(voiceStore().isInstalled())
        assertFalse(s.needsNet())
        assertFalse(s.needsVoice())
        assertTrue(s.isComplete())
        assertEquals(0L, s.bytesLeft())
        assertEquals(0L, s.storageNeeded())
        assertFalse("no part file is left", File(filesDir, "nets/${pins.fileName}.part").exists())
        assertFalse(File(filesDir, "tts_models/kokoro.tar.part").exists())

        assertEquals("net first, then voice", listOf("/$netPath", "/$voicePath"), server.requests.map { it.path })
        val last = progress.last()
        assertEquals(SetupStatus.DONE, last.status)
        assertEquals(1f, last.overallFraction, 0f)
        assertEquals(netBytes.size.toLong() + tarBytes.size, last.bytesTotal)
        assertEquals(last.bytesTotal, last.bytesDone)
        assertTrue(last.perFile.all { it.done })
        val statuses = progress.map { it.status }.toSet()
        assertTrue("$statuses", statuses.containsAll(listOf(SetupStatus.CONNECTING, SetupStatus.DOWNLOADING, SetupStatus.UNPACKING)))
        val fractions = progress.map { it.overallFraction }
        assertEquals("a clean run's bar only moves forward", fractions.sorted(), fractions)
    }

    @Test
    fun aSecondRunDoesNothing() {
        assertEquals(SetupOutcome.Complete, run(setup()))
        server.requests.clear()
        val progress = ArrayList<SetupProgress>()
        assertEquals(SetupOutcome.Complete, run(setup(), progress))
        assertTrue(server.requests.isEmpty())
        assertEquals(SetupStatus.DONE, progress.single().status)
    }

    @Test
    fun withTheNetInstalledOnlyTheVoiceIsFetched() {
        File(filesDir, "nets").mkdirs()
        File(filesDir, "nets/${pins.fileName}").writeBytes(netBytes)
        val s = setup()
        assertFalse(s.needsNet())
        assertTrue(s.needsVoice())
        assertEquals(tarBytes.size.toLong(), s.bytesLeft())
        assertEquals(SetupOutcome.Complete, run(s))
        assertEquals(listOf("/$voicePath"), server.requests.map { it.path })
    }

    @Test
    fun aDamagedInstalledNetIsDownloadedAgain() {
        File(filesDir, "nets").mkdirs()
        File(filesDir, "nets/${pins.fileName}").writeBytes(ByteArray(netBytes.size))
        val s = setup()
        assertFalse("cheap check: right size", s.needsNet())
        assertEquals(SetupOutcome.Complete, run(s))
        assertArrayEquals(netBytes, File(filesDir, "nets/${pins.fileName}").readBytes())
        assertEquals("/$netPath", server.requests.first().path)
    }

    @Test
    fun tooLittleSpaceFailsBeforeAnyRequestAndWritesNothing() {
        val s = setup(free = 1_000)
        val outcome = run(s)
        assertEquals(ModelFile.NET, (outcome as SetupOutcome.Failed).file)
        assertEquals(FailureReason.INSUFFICIENT_STORAGE, outcome.reason)
        assertTrue(server.requests.isEmpty())
        assertFalse(File(filesDir, "nets").exists())
    }

    @Test
    fun theVoiceNeedsRoomForTheTarAndTheUnpackedModelAtOnce() {
        // Enough for the net, one byte short of the voice's peak (tar + unpacked copy + margin).
        val free = 2L * tarBytes.size + ModelSetup.SAFETY_MARGIN_BYTES - 1
        assertTrue(free >= netBytes.size + ModelSetup.SAFETY_MARGIN_BYTES)
        val outcome = run(setup(free = free))
        assertEquals(ModelFile.VOICE, (outcome as SetupOutcome.Failed).file)
        assertEquals(FailureReason.INSUFFICIENT_STORAGE, outcome.reason)
        assertEquals("the voice was never requested", listOf("/$netPath"), server.requests.map { it.path })
        assertFalse("the net is in: analysis is unblocked", setup().needsNet())
    }

    @Test
    fun theStorageArithmeticWithTheRealSizes() {
        // D2f: the voice downloads as a .tar.gz (102.5 MB) and unpacks to its 158.3 MB tar. About 400 MB free
        // needed on a fresh install (450 with the plain tar, design §1.3), about 201 MB to download, and
        // about 103 MB left once the net is in.
        val realNet = NetPins("nn-1a298aa575a0.nnue", 98_511_183L, "1a298aa575a0" + "0".repeat(52), 0, 0)
        val gz = 102_543_452L
        val tar = 158_269_440L
        val realVoice = VoiceStore(
            filesDir, { Long.MAX_VALUE }, "7".repeat(64), tar,
            download = VoiceDownload(GeneratedModelPins.VOICE_FILE_NAME, gz, "9".repeat(64)),
        )
        val s = setup(net = NetStore(filesDir, realNet), voice = realVoice)
        val margin = ModelSetup.SAFETY_MARGIN_BYTES
        assertEquals(98_511_183L + gz, s.bytesLeft())
        assertEquals(98_511_183L + gz + tar + margin, s.storageNeeded())
        assertTrue("about 400 MB: ${s.storageNeeded()}", s.storageNeeded() in 390_000_000L..400_000_000L)
        assertTrue("about 201 MB: ${s.bytesLeft()}", s.bytesLeft() in 200_000_000L..202_000_000L)
        assertEquals(tar, s.voiceUnpackedBytes)

        File(filesDir, "nets").mkdirs()
        java.io.RandomAccessFile(File(filesDir, "nets/nn-1a298aa575a0.nnue"), "rw").use { it.setLength(98_511_183L) }
        assertEquals(gz, s.bytesLeft())
        assertEquals(gz + tar + margin, s.storageNeeded())
    }

    @Test
    fun aGzippedVoiceIsDownloadedVerifiedAndUnpackedAgainstTheTarPins() {
        // What the app really downloads since D2f: the tar gzipped. The downloader verifies the .tar.gz's
        // own pins; the unpack step checks the tar inside against the tar pins and writes the TAR's hash as
        // the marker (the same marker a bundled build wrote, so nothing changes for an update from one).
        val gz = TestModelFiles.gzip(tarBytes)
        assertTrue("gzip makes the stand-in smaller (${gz.size} < ${tarBytes.size})", gz.size < tarBytes.size)
        server.serve(voicePath, gz)
        val voice = TestModelFiles.voiceStoreFor(filesDir, tarBytes, download = gz)
        val s = setup(voice = voice)
        assertEquals(netBytes.size.toLong() + gz.size, s.bytesLeft())
        assertEquals(netBytes.size.toLong() + gz.size + tarBytes.size + ModelSetup.SAFETY_MARGIN_BYTES, s.storageNeeded())

        val progress = ArrayList<SetupProgress>()
        assertEquals(SetupOutcome.Complete, run(s, progress))
        assertTrue(voice.isInstalled())
        assertEquals(TestModelFiles.sha256(tarBytes), File(filesDir, "tts_models/kokoro/.provisioned").readText())
        assertEquals(TestModelFiles.sha256(tarBytes).take(12), voice.installedVersionId())
        assertFalse("the archive is deleted after unpacking", File(filesDir, "tts_models/kokoro.tar.part").exists())
        assertEquals(1, server.requestsFor(voicePath).size)
        assertEquals(netBytes.size.toLong() + gz.size, progress.last().bytesTotal)
        assertTrue(logText().contains(Regex("""voice: installed \([0-9a-f]{12}\), unpacked in \d+ ms""")))
    }

    @Test
    fun aGzipWhoseTarIsNotThePinnedOneIsDamagedAndDiscarded() {
        // The .tar.gz matches its own (download) pins, but inflates to a different tar: refused at unpack.
        val otherTar = TestModelFiles.voiceTar(payload = 150_000)
        val gz = TestModelFiles.gzip(otherTar)
        server.serve(voicePath, gz)
        val voice = VoiceStore(
            filesDir, { Long.MAX_VALUE }, TestModelFiles.sha256(tarBytes), tarBytes.size.toLong(),
            download = VoiceDownload(GeneratedModelPins.VOICE_FILE_NAME, gz.size.toLong(), TestModelFiles.sha256(gz)),
        )
        val outcome = run(setup(voice = voice))
        assertEquals(ModelFile.VOICE, (outcome as SetupOutcome.Failed).file)
        assertEquals(FailureReason.DAMAGED, outcome.reason)
        assertFalse(File(filesDir, "tts_models/kokoro.tar.part").exists())
        assertFalse(voice.isInstalled())
        assertFalse("nothing half-unpacked is left", File(filesDir, "tts_models/kokoro.extracting").exists())
    }

    @Test
    fun aMissingReleaseFileFailsAndTheVoiceIsNotAttempted() {
        server.fault(netPath, Fault.Status(404))
        val outcome = run(setup())
        assertEquals(ModelFile.NET, (outcome as SetupOutcome.Failed).file)
        assertEquals(FailureReason.NOT_FOUND, outcome.reason)
        assertEquals(listOf("/$netPath"), server.requests.map { it.path })
    }

    @Test
    fun repeatedConnectionLossPausesAndKeepsTheBytesForResume() {
        server.alwaysFault(voicePath, Fault.TruncateAfter(20_000))
        val s = setup()
        val outcome = run(s)
        assertEquals(SetupOutcome.Paused(ModelFile.VOICE, PauseReason.CONNECTION_LOST), outcome)
        val state = s.state()
        assertTrue(state.netInstalled)
        assertFalse(state.voiceInstalled)
        assertTrue("bytes kept: ${state.voicePartBytes}", state.voicePartBytes >= 20_000)
        assertEquals(tarBytes.size - state.voicePartBytes, s.bytesLeft())

        server.clearFaults()
        assertEquals(SetupOutcome.Complete, run(s))
        assertEquals("bytes=${state.voicePartBytes}-", server.requestsFor(voicePath).last().range)
    }

    @Test
    fun pauseKeepsThePartAndCancelDeletesIt() = runBlocking {
        server.fault(voicePath, Fault.Slow(bytesPerSecond = 100_000))
        val s = setup()
        val reached = CompletableDeferred<Unit>()
        val job = launch(Dispatchers.Default) {
            s.run { p -> if (p.perFile.any { it.file == ModelFile.VOICE && it.bytesDone >= 30_000 }) reached.complete(Unit) }
        }
        withTimeout(30_000) { reached.await() }
        job.cancelAndJoin() // Pause
        val kept = s.state().voicePartBytes
        assertTrue("Pause keeps the part ($kept bytes)", kept > 0)
        assertFalse(s.needsNet())

        s.discardPartials() // Cancel
        assertEquals(0L, s.state().voicePartBytes)
        assertFalse(File(filesDir, "tts_models/kokoro.tar.part").exists())
        assertFalse("Cancel keeps an installed net", s.needsNet())
        assertTrue(logText().contains("setup cancelled"))
    }

    @Test
    fun aVoiceArchiveWithoutItsModelFilesIsDamagedAndDiscarded() {
        val broken = TestModelFiles.voiceTar(complete = false)
        server.serve(voicePath, broken)
        val outcome = run(setup(voice = voiceStore(broken)))
        assertEquals(ModelFile.VOICE, (outcome as SetupOutcome.Failed).file)
        assertEquals(FailureReason.DAMAGED, outcome.reason)
        assertFalse(File(filesDir, "tts_models/kokoro.tar.part").exists())
        assertFalse(voiceStore(broken).isInstalled())
    }

    @Test
    fun anUpdateFromABundledBuildReusesItsFilesAndDownloadsNothing() = runBlocking {
        // What a versionCode-1 (bundled) install left in filesDir: the net in the root, and the voice
        // unpacked with its marker. The voice is installed through the same tail the bundled installer had.
        File(filesDir, pins.fileName).writeBytes(netBytes)
        File(filesDir, "nn-${"f".repeat(12)}.nnue.part").writeBytes(ByteArray(10))
        val voice = voiceStore()
        voice.installFromStream({ tarBytes.inputStream() }, voice.pinnedSha256, tarBytes.size.toLong())
        assertTrue(voice.isInstalled())

        val s = setup()
        val line = s.migrateLegacy()
        assertTrue("$line", line!!.contains("moved ${pins.fileName}"))
        assertFalse(File(filesDir, pins.fileName).exists())
        assertFalse(s.needsNet())
        assertFalse(s.needsVoice())
        assertNull("a second start has nothing to migrate", s.migrateLegacy())

        assertEquals(SetupOutcome.Complete, s.run())
        assertTrue("nothing on the wire", server.requests.isEmpty())
        assertTrue(logText().contains("migrateLegacy"))
    }

    @Test
    fun theLogRecordsTheDownloadWithoutFullUrls() {
        server.fault(voicePath, Fault.TruncateAfter(50_000))
        assertEquals(SetupOutcome.Complete, run(setup()))
        val text = logText()
        for (expected in listOf("setup start", "download start", "50%", "verified", "transient failure", "installed", "setup complete")) {
            assertTrue("log must contain '$expected':\n$text", text.contains(expected))
        }
        assertFalse("no URL path in the log", text.contains("$tag/"))
        assertFalse(text.contains("http://"))
    }
}
