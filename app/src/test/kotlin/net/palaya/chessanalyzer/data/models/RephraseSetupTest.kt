package net.palaya.chessanalyzer.data.models

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import net.palaya.chessanalyzer.diagnostics.DiagnosticLog
import net.palaya.chessanalyzer.engine.NetStore
import net.palaya.chessanalyzer.rephrase.RephraseModelStore
import net.palaya.chessanalyzer.ui.model.SetupSizes
import net.palaya.chessanalyzer.ui.model.bytesLeftToDownload
import net.palaya.chessanalyzer.ui.model.setupFiles
import net.palaya.chessanalyzer.ui.model.setupView
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * C2 owner decision §12.3: the wording model as setup's optional third download. Real [ModelSetup] and
 * [ModelDownloader] against [FaultHttpServer], a stand-in GGUF. It is fetched only when the user asked for it, it
 * never makes setup incomplete, it lives on its own release tag, and Cancel withdraws the request.
 */
class RephraseSetupTest {

    @get:Rule val tmp = TemporaryFolder()

    private val tag = "models-2026.10"
    private val netBytes = TestModelFiles.netBytes()
    private val pins = TestModelFiles.pinsFor(netBytes)
    private val tarBytes = TestModelFiles.voiceTar()
    private val gguf = TestGguf.bytes(dataBytes = 300 * 1024)
    private val rPins = RephraseModelStore.Pins("test-model", "test-model.gguf", gguf.size.toLong(), TestGguf.sha256(gguf), "qwen2", "models-2026.11")
    private lateinit var server: FaultHttpServer
    private lateinit var filesDir: File
    private var installedCallbacks = 0

    @Before
    fun setUp() {
        filesDir = tmp.newFolder("files")
        server = FaultHttpServer()
            .serve("$tag/${pins.fileName}", netBytes)
            .serve("$tag/${GeneratedModelPins.VOICE_FILE_NAME}", tarBytes)
            .serve("models-2026.11/test-model.gguf", gguf)
    }

    @After
    fun tearDown() = server.close()

    private fun store() = RephraseModelStore(filesDir, rPins)

    private fun setup() = ModelSetup(
        netStore = NetStore(filesDir, pins),
        voiceStore = TestModelFiles.voiceStoreFor(filesDir, tarBytes),
        downloader = ModelDownloader(userAgent = "test", allowCleartextLoopback = true, connectTimeoutMs = 2_000, readTimeoutMs = 2_000, sleep = {}),
        baseUrl = server.baseUrl,
        releaseTag = tag,
        freeBytes = { Long.MAX_VALUE },
        diagnostics = DiagnosticLog(File(tmp.root, "logs")),
        rephraseStore = store(),
        onRephraseInstalled = { installedCallbacks++ },
    )

    private fun run(s: ModelSetup, progress: MutableList<SetupProgress> = ArrayList()) =
        runBlocking { withTimeout(60_000) { s.run { progress += it } } }

    @Test
    fun notAskedForMeansNeverRequested() {
        val s = setup()
        assertFalse(s.needsRephrase())
        assertEquals(netBytes.size.toLong() + tarBytes.size, s.bytesLeft())
        assertEquals(SetupOutcome.Complete, run(s))
        assertEquals(0, server.requestsFor("models-2026.11/test-model.gguf").size)
        assertFalse(store().isInstalled())
        assertEquals(0, installedCallbacks)
    }

    @Test
    fun askedForAtSetupItIsTheThirdFileFromItsOwnTagAndSwitchesTheFeatureOn() {
        store().setWanted(true)
        val s = setup()
        assertTrue(s.needsRephrase())
        assertEquals(netBytes.size.toLong() + tarBytes.size + gguf.size, s.bytesLeft())
        val progress = ArrayList<SetupProgress>()
        assertEquals(SetupOutcome.Complete, run(s, progress))
        assertArrayEquals(gguf, File(filesDir, "rephrase/models/test-model.gguf").readBytes())
        assertEquals(1, installedCallbacks)
        assertEquals(listOf(ModelFile.NET, ModelFile.VOICE, ModelFile.REPHRASE), progress.last().perFile.map { it.file })
        assertTrue(progress.last().perFile.all { it.done })
        assertEquals(0L, s.bytesLeft())
    }

    @Test
    fun withTheBasicsInstalledARunFetchesOnlyTheWordingModel() {
        assertEquals(SetupOutcome.Complete, run(setup()))
        assertTrue(setup().isComplete())
        store().setWanted(true) // the Settings row's Download
        val progress = ArrayList<SetupProgress>()
        assertEquals(SetupOutcome.Complete, run(setup(), progress))
        assertEquals(listOf(ModelFile.REPHRASE), progress.last().perFile.map { it.file })
        assertEquals(1, server.requestsFor("$tag/${pins.fileName}").size)
        assertTrue(store().isInstalled())
    }

    @Test
    fun aDamagedDownloadFailsOnItsOwnAndCancelWithdrawsTheRequest() {
        server.close()
        server = FaultHttpServer()
            .serve("$tag/${pins.fileName}", netBytes)
            .serve("$tag/${GeneratedModelPins.VOICE_FILE_NAME}", tarBytes)
            .serve("models-2026.11/test-model.gguf", gguf.copyOf().also { it[it.size - 5] = 1 })
        store().setWanted(true)
        val s = setup()
        val outcome = run(s)
        assertTrue("$outcome", outcome is SetupOutcome.Failed && outcome.file == ModelFile.REPHRASE)
        assertTrue("net and voice are in: the app works without the model", s.isComplete())
        s.discardPartials()
        assertFalse(store().isWanted())
        assertFalse(s.needsRephrase())
    }

    @Test
    fun theSetupScreenListsTheModelOnlyWhenAskedFor() {
        val sizes = SetupSizes(netBytes.size.toLong(), tarBytes.size.toLong(), rephraseBytes = gguf.size.toLong())
        val plain = setup().state()
        assertEquals(listOf(ModelFile.NET, ModelFile.VOICE), setupFiles(plain))
        assertEquals(netBytes.size.toLong() + tarBytes.size, bytesLeftToDownload(plain, sizes))
        store().setWanted(true)
        val wanted = setup().state()
        assertEquals(listOf(ModelFile.NET, ModelFile.VOICE, ModelFile.REPHRASE), setupFiles(wanted))
        assertEquals(netBytes.size.toLong() + tarBytes.size + gguf.size, bytesLeftToDownload(wanted, sizes))
        assertEquals(3, setupView(wanted, sizes, running = false, progress = null).rows.size)
    }
}
