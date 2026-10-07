package net.palaya.chessanalyzer.data.models

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import net.palaya.chessanalyzer.TestApp
import net.palaya.chessanalyzer.data.AnalysisService
import net.palaya.chessanalyzer.engine.ActiveNet
import net.palaya.chessanalyzer.engine.NetStore
import net.palaya.chessanalyzer.ui.model.AnalysisStrength
import net.palaya.chessanalyzer.ui.model.EngineSettings
import net.palaya.chessanalyzer.video.NeuralVoiceTrial
import net.palaya.chessanalyzer.video.VoiceStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Activation, trial and rollback on a device (D2e; docs/MODEL_DOWNLOAD_DESIGN.md §4, §6.2), with the app's
 * ONE engine (`app.engineController`, CLAUDE.md gotcha 2) and its real stores.
 *
 * The "update" net is the real 98.5 MB net with one byte of its description string changed: the same
 * architecture and weights (so Stockfish loads it), a different SHA-256 and therefore a different name,
 * exactly what a same-architecture net from the manifest would be. A "crash" is a [SimulatedDeath] (an
 * Error nothing catches) thrown where the process would die; the next "start" is a fresh [ModelActivator]
 * on the same files running [ModelActivator.recoverOnStartup], before anything touches the engine.
 *
 * Every test puts the compiled net back as the active one and re-loads it into the engine ([restore]).
 */
@RunWith(AndroidJUnit4::class)
class ActivationRollbackInstrumentedTest {

    private class SimulatedDeath(message: String) : Error(message)

    private val app get() = TestApp.app
    private val netStore get() = app.netStore
    private val journal get() = ActivationJournal(app.filesDir)
    private val scratchDirs = ArrayList<File>()

    @Before
    fun setUp() = runBlocking {
        TestApp.ensureSetUp()
        journal.clear()
        Unit
    }

    @After
    fun restore() = runBlocking {
        journal.clear()
        val s = netStore
        if (s.activeIdentity() != s.compiledIdentity()) s.setActiveIdentity(s.compiledIdentity())
        s.otherNets().forEach { it.delete() }
        s.deleteParts()
        TestApp.ensureSetUp()
        app.engineController.switchNet()
        assertEquals(NetStore.NET_FILENAME, app.engineController.loadedNetName)
        scratchDirs.forEach { it.deleteRecursively() }
    }

    /** Writes the seed net with byte [flipAt] changed into `nets/<its name>.part`; returns the manifest entry. */
    private fun variantNet(flipAt: Long = 20): Pair<File, ManifestEntry> {
        val tmp = File(netStore.dir, "variant.tmp")
        val digest = MessageDigest.getInstance("SHA-256")
        var size = 0L
        TestApp.openSeed(TestApp.netSeedPath).use { input ->
            tmp.outputStream().use { out ->
                val buf = ByteArray(1 shl 20)
                while (true) {
                    val n = input.read(buf)
                    if (n == -1) break
                    if (flipAt >= size && flipAt < size + n) {
                        val i = (flipAt - size).toInt()
                        buf[i] = (buf[i].toInt() xor 0x01).toByte()
                    }
                    out.write(buf, 0, n)
                    digest.update(buf, 0, n)
                    size += n
                }
            }
        }
        val sha = digest.digest().joinToString("") { "%02x".format(it) }
        val name = "nn-${sha.take(12)}.nnue"
        val part = netStore.partFileFor(name)
        check(tmp.renameTo(part))
        return part to entryFor(name, size, sha)
    }

    private fun entryFor(name: String, size: Long, sha: String) = ManifestEntry(
        ModelKind.NET, "Chess engine data", name.removeSuffix(".nnue"), name, "https://example.invalid/t/$name", size, sha, 1, null,
        ModelCompat.StockfishNnue(NetStore.NET_VERSION, NetStore.NET_ARCH_HASH, "sf_19"), null,
    )

    private fun activator(gate: NetActivationGate = app.engineController, checkpoint: (String) -> Unit = {}) = ModelActivator(
        netStore = netStore,
        voiceStore = app.voiceStore,
        journal = journal,
        netGate = gate,
        voiceTrial = { VoiceTrialResult(true, "not used") },
        voiceBusy = { false },
        onNetActivated = { net -> net.prefix?.let { app.gameRepository.purgeEvalCachesExcept(it) } },
        onVoiceActivated = {},
        checkpoint = checkpoint,
    )

    /** A real search on whatever net the engine has loaded now. */
    private suspend fun searchWorks() {
        val engine = app.engineController.ensureReady()
        engine.newGame()
        engine.setPosition(fen = "r1bqkbnr/pppp1ppp/2n5/4p3/2B1P3/5Q2/PPPP1PPP/RNB1K1NR w KQkq - 2 3")
        val r = engine.analyze(multiPv = 1, depth = 6)
        assertEquals("the engine must find the mate in one", "f3f7", r.bestMoveUci)
    }

    @Test
    fun aSameArchitectureNetIsTriedOnTheAppsEngineAndBecomesTheOneAnalysesUse() = runBlocking {
        val (part, entry) = variantNet()
        val outcome = activator().activateNet(part, entry)
        assertTrue("$outcome", outcome is ActivationOutcome.Activated)
        assertTrue("the trial searched depth 1: $outcome", (outcome as ActivationOutcome.Activated).detail.contains("bestmove"))
        assertEquals(entry.fileName, app.engineController.loadedNetName)
        assertEquals(ActiveNet(entry.fileName, entry.sizeBytes, entry.sha256), netStore.activeIdentity())
        assertEquals(entry.fileName, netStore.verifiedNetOrNull()?.name)
        assertFalse("the old net is deleted", File(netStore.dir, NetStore.NET_FILENAME).exists())
        assertNull(journal.read())
        searchWorks()

        // A real analysis now runs on the new net and caches under its own folder.
        val outcomeA = TestApp.analysisService().analyze(
            pgnText = "[Event \"D2e net switch ${System.nanoTime()}\"]\n\n1. e4 e5 2. Bc4 Nc6 3. Qh5 Nf6 4. Qxf7# 1-0",
            username = "",
            settings = EngineSettings(depth = AnalysisStrength.QUICK.depth, multiPv = 3),
        )
        assertTrue("$outcomeA", outcomeA is AnalysisService.Outcome.Success)
        val prefix = entry.sha256.take(12)
        assertTrue("the result is cached in eval_cache/$prefix/", File(app.filesDir, "eval_cache/$prefix").listFiles().orEmpty().isNotEmpty())

        // And back: the compiled net as an "update" (the same path a later manifest would take).
        val compiledPart = netStore.partFileFor().also { f -> TestApp.openSeed(TestApp.netSeedPath).use { i -> f.outputStream().use { i.copyTo(it) } } }
        val back = activator().activateNet(compiledPart, entryFor(NetStore.NET_FILENAME, NetStore.NET_SIZE_BYTES, NetStore.NET_SHA256))
        assertTrue("$back", back is ActivationOutcome.Activated)
        assertEquals(netStore.compiledIdentity(), netStore.activeIdentity())
        assertFalse(File(netStore.dir, entry.fileName).exists())
        assertFalse("the other net's eval cache is purged", File(app.filesDir, "eval_cache/$prefix").exists())
        assertEquals(NetStore.NET_FILENAME, app.engineController.loadedNetName)
    }

    @Test
    fun aCrashInTheMiddleOfTheTrialIsRolledBackOnTheNextStart() = runBlocking {
        val (part, entry) = variantNet()
        // The process "dies" after the engine has loaded the new net and searched, before the commit:
        // what Stockfish's exit() during the load would leave behind, minus the dead process.
        val dying = object : NetActivationGate {
            override suspend fun <T> exclusive(block: suspend () -> T): T = app.engineController.exclusive(block)
            override suspend fun trialLocked(): String {
                val r = app.engineController.trialLocked()
                throw SimulatedDeath("the process died mid-trial ($r)")
            }
        }
        try {
            activator(dying).activateNet(part, entry)
            fail("the process should have died")
        } catch (e: SimulatedDeath) {
            // gone
        }
        assertEquals(JournalPhase.TRIAL, (journal.read() as JournalRecord.InFlight).phase)
        assertEquals(entry.fileName, netStore.activeIdentity().fileName)
        assertEquals(entry.fileName, app.engineController.loadedNetName)

        // ---- the next start ----
        val report = activator().recoverOnStartup()
        assertEquals(Recovery.ROLL_BACK_NET, report.recovery)
        assertEquals(netStore.compiledIdentity(), netStore.activeIdentity())
        assertFalse("the new net is deleted", File(netStore.dir, entry.fileName).exists())
        assertEquals(NetStore.NET_FILENAME, netStore.verifiedNetOrNull()?.name)
        val notice = activator().pendingNotice()
        assertEquals(ModelKind.NET, notice?.model)
        activator().acknowledgeNotice()
        assertNull(journal.read())
        // The engine loads the restored net (a fresh process would do this through ensureReady()).
        app.engineController.switchNet()
        assertEquals(NetStore.NET_FILENAME, app.engineController.loadedNetName)
        searchWorks()
    }

    @Test
    fun aNetThatFailsVerificationAfterTheSwapNeverReachesTheEngineAndIsRolledBack() = runBlocking {
        val (part, entry) = variantNet()
        val before = app.engineController.switchNet().let { app.engineController.loadedNetName }
        // Between the swap and the trial the file changes on disk (a bad sector, a bug): the trial's
        // verifiedNetOrNull() must refuse it, so the engine is never handed an unverified file.
        val outcome = activator(checkpoint = { at ->
            if (at == "net:swapped") {
                java.io.RandomAccessFile(File(netStore.dir, entry.fileName), "rw").use { f ->
                    f.seek(50_000_000)
                    val b = f.read()
                    f.seek(50_000_000)
                    f.write(b xor 0xFF)
                }
            }
        }).activateNet(part, entry)
        assertTrue("$outcome", outcome is ActivationOutcome.RolledBack)
        assertEquals("the engine never loaded the damaged file", before, app.engineController.loadedNetName)
        assertEquals(netStore.compiledIdentity(), netStore.activeIdentity())
        assertFalse(File(netStore.dir, entry.fileName).exists())
        assertEquals(ModelKind.NET, (journal.read() as JournalRecord.RolledBack).model)
        searchWorks()
    }

    @Test
    fun aWrongArchitectureNetIsRefusedBeforeTheEngine() = runBlocking {
        val (part, entry) = variantNet(flipAt = 5) // byte 5 is inside the architecture hash
        val before = app.engineController.loadedNetName
        val outcome = activator().activateNet(part, entry)
        assertEquals(RejectReason.INCOMPATIBLE, (outcome as ActivationOutcome.Rejected).reason)
        assertFalse(part.exists())
        assertEquals(before, app.engineController.loadedNetName)
        assertEquals(netStore.compiledIdentity(), netStore.activeIdentity())
        assertNull(journal.read())
    }

    // ---- voice ----

    private fun scratchVoice(): Triple<VoiceStore, ActivationJournal, File> {
        val dir = File(app.filesDir, "activation-test-${System.nanoTime()}").apply { mkdirs() }
        scratchDirs += dir
        val old = TestModelFiles.voiceTar()
        val store = TestModelFiles.voiceStoreFor(dir, old)
        runBlocking { store.installFromStream({ old.inputStream() }, store.pinnedSha256, old.size.toLong()) }
        return Triple(store, ActivationJournal(dir), dir)
    }

    @Test
    fun aCrashDuringTheVoiceTrialOrSwapIsRolledBackOnTheNextStart() = runBlocking {
        val newTar = TestModelFiles.voiceTar(payload = 230_000)
        val newSha = TestModelFiles.sha256(newTar)
        val entry = ManifestEntry(
            ModelKind.VOICE, "Narration voice", "v-test", "k.tar", "https://example.invalid/t/k.tar", newTar.size.toLong(), newSha, 1, null,
            ModelCompat.SherpaKokoro(VoiceStore.LAYOUT), ModelRuntime("sherpa-onnx", net.palaya.chessanalyzer.BuildConfig.SHERPA_ONNX_VERSION, net.palaya.chessanalyzer.BuildConfig.SHERPA_ONNX_VERSION),
        )
        for (at in listOf("voice:trial", "voice:journal-swapped", "voice:swapped")) {
            val (store, j, dir) = scratchVoice()
            val oldSha = store.installedSha256()
            fun activator(crash: String?) = ModelActivator(
                NetStore(dir), store, j, app.engineController, { VoiceTrialResult(true, "fake") }, { false }, {}, {},
                checkpoint = { if (it == crash) throw SimulatedDeath(it) },
            )
            val part = store.updatePartFile(newSha).apply { writeBytes(newTar) }
            try {
                activator(at).activateVoice(part, entry)
                fail(at)
            } catch (e: SimulatedDeath) {
            }
            assertTrue(at, j.read() is JournalRecord.InFlight)
            assertEquals(at, Recovery.ROLL_BACK_VOICE, activator(null).recoverOnStartup().recovery)
            assertEquals(at, oldSha, store.installedSha256())
            assertTrue(at, store.isInstalled())
            assertFalse(at, store.previousDir.exists())
            assertFalse(at, store.scratchDir.exists())
            assertEquals(at, ModelKind.VOICE, (j.read() as JournalRecord.RolledBack).model)
        }
    }

    @Test
    fun theRealVoiceTrialHearsKokoroAndRefusesABrokenModel() = runBlocking {
        val lines = ArrayList<String>()
        val trial = NeuralVoiceTrial(app.cacheDir) { lines += it }
        val ok = trial.run(TestApp.installedVoiceDir())
        assertTrue("the installed Kokoro voice must pass: $ok", ok.ok)
        android.util.Log.i("ActivationRollbackTest", "real voice trial: ${ok.detail}")
        val rms = Regex("RMS (\\d+)").find(ok.detail)!!.groupValues[1].toDouble()
        assertTrue("RMS $rms", rms > NeuralVoiceTrial.MIN_RMS)

        val broken = File(app.cacheDir, "broken-voice-${System.nanoTime()}").apply { mkdirs() }
        scratchDirs += broken
        File(broken, VoiceStore.KOKORO_TOKENS_FILE).writeText("a 1\n")
        assertFalse(trial.run(broken).ok)
    }
}
