package net.palaya.chessanalyzer.data.models

import java.io.File
import kotlinx.coroutines.runBlocking
import net.palaya.chessanalyzer.engine.ActiveNet
import net.palaya.chessanalyzer.engine.NetNotInstalledException
import net.palaya.chessanalyzer.engine.NetStore
import net.palaya.chessanalyzer.video.VoiceStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * [ModelActivator] on the host: real [NetStore], [VoiceStore] and [ActivationJournal] on a temporary
 * `filesDir`, small stand-in files, a fake engine that loads through `verifiedNetOrNull()` exactly as
 * `EngineController.trialLocked()` does, and a fake voice trial. A "crash" is a [SimulatedDeath] thrown at
 * a step boundary ([ModelActivator]'s test checkpoint): nothing after it runs, as when the process dies;
 * then a FRESH activator on the same directory runs [ModelActivator.recoverOnStartup].
 */
class ModelActivatorTest {

    @get:Rule val tmp = TemporaryFolder()

    private class SimulatedDeath(at: String) : Error("process died at $at")

    private class FakeEngine(private val store: () -> NetStore) : NetActivationGate {
        var analysisRunning = false
        var failFor: String? = null
        val loads = ArrayList<String>()
        override suspend fun <T> exclusive(block: suspend () -> T): T {
            if (analysisRunning) throw EngineBusyException()
            return block()
        }
        override suspend fun trialLocked(): String {
            val f = store().verifiedNetOrNull() ?: throw NetNotInstalledException()
            loads += f.name
            if (f.name == failFor) error("the depth-1 trial search gave no move")
            return "${f.name}: bestmove e2e4"
        }
    }

    private val oldNet = TestModelFiles.netBytes(size = 1_100_000, seed = 7)
    private val newNet = TestModelFiles.netBytes(size = 1_100_000, seed = 8)
    private val pins = TestModelFiles.pinsFor(oldNet)
    private val newSha = TestModelFiles.sha256(newNet)
    private val newName = "nn-${newSha.take(12)}.nnue"
    private val oldTar = TestModelFiles.voiceTar()
    private val newTar = TestModelFiles.voiceTar(payload = 210_000)
    private val newTarSha = TestModelFiles.sha256(newTar)

    private lateinit var files: File
    private lateinit var engine: FakeEngine
    private val purged = ArrayList<String>()
    private var narrationCleared = 0
    private var voiceTrialOk = true
    private var exporting = false
    private val voiceTrialDirs = ArrayList<String>()

    private fun netStore() = NetStore(files, pins)
    private fun voiceStore() = TestModelFiles.voiceStoreFor(files, oldTar)
    private fun journal() = ActivationJournal(files)

    private fun activator(crashAt: String? = null) = ModelActivator(
        netStore = netStore(),
        voiceStore = voiceStore(),
        journal = journal(),
        netGate = engine,
        voiceTrial = { dir ->
            voiceTrialDirs += dir.name
            VoiceTrialResult(voiceTrialOk, if (voiceTrialOk) "1800 ms, RMS 2400" else "RMS 3 (silence)")
        },
        voiceBusy = { exporting },
        onNetActivated = { purged += it.fileName },
        onVoiceActivated = { narrationCleared++ },
        checkpoint = { if (it == crashAt) throw SimulatedDeath(it) },
    )

    private val netEntry get() = ManifestEntry(
        ModelKind.NET, "Chess engine data", newName.removeSuffix(".nnue"), newName, "https://x/t/$newName",
        newNet.size.toLong(), newSha, 1, null, ModelCompat.StockfishNnue(TestModelFiles.NET_VERSION, TestModelFiles.NET_ARCH, "sf_19"), null,
    )

    private val voiceEntry get() = ManifestEntry(
        ModelKind.VOICE, "Narration voice", "v0_19-r2", "kokoro-r2.tar", "https://x/t/kokoro-r2.tar",
        newTar.size.toLong(), newTarSha, 1, null, ModelCompat.SherpaKokoro(VoiceStore.LAYOUT), ModelRuntime("sherpa-onnx", "1.13.8", "1.13.8"),
    )

    @Before
    fun setUp() = runBlocking {
        files = tmp.newFolder("files")
        engine = FakeEngine { netStore() }
        // Setup's result: the compiled net and the pinned voice are installed.
        val s = netStore()
        s.partFileFor().apply { parentFile!!.mkdirs(); writeBytes(oldNet) }
        s.installVerified(s.partFileFor())
        voiceStore().installFromStream({ oldTar.inputStream() }, TestModelFiles.sha256(oldTar), oldTar.size.toLong())
        Unit
    }

    private fun netPart(bytes: ByteArray = newNet): File = netStore().partFileFor(newName).apply { writeBytes(bytes) }
    private fun voicePart(): File = voiceStore().updatePartFile(newTarSha).apply { parentFile!!.mkdirs(); writeBytes(newTar) }

    // ---- net ----

    @Test
    fun aNetPassesItsTrialAndIsActivated() = runBlocking {
        val outcome = activator().activateNet(netPart(), netEntry)
        assertTrue("$outcome", outcome is ActivationOutcome.Activated)
        val s = netStore()
        assertEquals(ActiveNet(newName, newNet.size.toLong(), newSha), s.activeIdentity())
        assertEquals(newName, s.verifiedNetOrNull()?.name)
        assertFalse("the old net is deleted", File(s.dir, pins.fileName).exists())
        assertFalse(s.partFileFor(newName).exists())
        assertEquals(listOf(newName), engine.loads)
        assertEquals(listOf(newName), purged)
        assertNull("the journal is cleared", journal().read())
    }

    @Test
    fun aNetWhoseTrialFailsIsRolledBackAtOnce() = runBlocking {
        engine.failFor = newName
        val outcome = activator().activateNet(netPart(), netEntry)
        assertTrue("$outcome", outcome is ActivationOutcome.RolledBack)
        val s = netStore()
        assertEquals(s.compiledIdentity(), s.activeIdentity())
        assertFalse("the new net is deleted", File(s.dir, newName).exists())
        assertEquals("the old net is re-loaded after the failed trial", listOf(newName, pins.fileName), engine.loads)
        assertTrue(purged.isEmpty())
        assertEquals(ModelKind.NET, (journal().read() as JournalRecord.RolledBack).model)
    }

    @Test
    fun aWrongArchitectureNetNeverReachesTheEngine() = runBlocking {
        val foreign = TestModelFiles.netBytes(size = 1_100_000, seed = 9, arch = 0xdeadbeefL)
        val sha = TestModelFiles.sha256(foreign)
        val name = "nn-${sha.take(12)}.nnue"
        val part = netStore().partFileFor(name).apply { writeBytes(foreign) }
        val entry = netEntry.copy(fileName = name, sha256 = sha, sizeBytes = foreign.size.toLong())
        val outcome = activator().activateNet(part, entry)
        assertEquals(RejectReason.INCOMPATIBLE, (outcome as ActivationOutcome.Rejected).reason)
        assertTrue("the engine was never called", engine.loads.isEmpty())
        assertFalse("the part is deleted", part.exists())
        assertEquals(netStore().compiledIdentity(), netStore().activeIdentity())
        assertNull(journal().read())
    }

    @Test
    fun aNetIsNotSwitchedUnderARunningAnalysis() = runBlocking {
        engine.analysisRunning = true
        val part = netPart()
        val outcome = activator().activateNet(part, netEntry)
        assertEquals(RejectReason.BUSY, (outcome as ActivationOutcome.Rejected).reason)
        assertTrue("the verified download is kept for later", part.exists())
        assertEquals(netStore().compiledIdentity(), netStore().activeIdentity())
        assertNull(journal().read())
    }

    @Test
    fun aCrashDuringTheNetTrialIsRolledBackOnTheNextStart() = runBlocking {
        try {
            activator(crashAt = "net:trial").activateNet(netPart(), netEntry)
            fail("the process should have died")
        } catch (e: SimulatedDeath) {
            // the process is gone
        }
        // What the dead process left: the journal in phase trial and the new net active.
        assertEquals(JournalPhase.TRIAL, (journal().read() as JournalRecord.InFlight).phase)
        assertEquals(newName, netStore().activeIdentity().fileName)

        engine.loads.clear()
        val report = activator().recoverOnStartup()
        assertEquals(Recovery.ROLL_BACK_NET, report.recovery)
        assertTrue("recovery never touches the engine", engine.loads.isEmpty())
        val s = netStore()
        assertEquals(s.compiledIdentity(), s.activeIdentity())
        assertEquals(pins.fileName, s.verifiedNetOrNull()?.name)
        assertFalse(File(s.dir, newName).exists())
        assertEquals(ModelKind.NET, activator().pendingNotice()?.model)
        activator().acknowledgeNotice()
        assertNull(journal().read())
        assertEquals(Recovery.NOTHING, activator().recoverOnStartup().recovery)
    }

    @Test
    fun aCrashRightAfterTheJournalIsWrittenIsRolledBackToo() = runBlocking {
        val part = netPart()
        try {
            activator(crashAt = "net:journal-swapped").activateNet(part, netEntry)
            fail()
        } catch (e: SimulatedDeath) {
        }
        assertEquals(Recovery.ROLL_BACK_NET, activator().recoverOnStartup().recovery)
        assertEquals(netStore().compiledIdentity(), netStore().activeIdentity())
        assertFalse("the part is dropped (the user downloads again)", part.exists())
    }

    @Test
    fun aCrashAfterThePassedTrialFinishesTheCommit() = runBlocking {
        try {
            activator(crashAt = "net:committed").activateNet(netPart(), netEntry)
            fail()
        } catch (e: SimulatedDeath) {
        }
        val report = activator().recoverOnStartup()
        assertEquals(Recovery.FINISH_NET, report.recovery)
        assertEquals(newName, netStore().activeIdentity().fileName)
        assertFalse(File(netStore().dir, pins.fileName).exists())
        assertEquals(listOf(newName), purged)
        assertNull("no rollback notice: the update is in", journal().read())
    }

    // ---- voice ----

    @Test
    fun aVoicePassesItsTrialAndIsSwappedIn() = runBlocking {
        val outcome = activator().activateVoice(voicePart(), voiceEntry)
        assertTrue("$outcome", outcome is ActivationOutcome.Activated)
        val v = voiceStore()
        assertEquals(newTarSha, v.installedSha256())
        assertTrue("an update's voice counts as installed (.compat)", v.isInstalled())
        assertEquals(listOf(VoiceStore.SCRATCH_DIR_NAME), voiceTrialDirs)
        assertFalse(v.previousDir.exists())
        assertFalse(v.scratchDir.exists())
        assertEquals(1, narrationCleared)
        assertNull(journal().read())
    }

    @Test
    fun aSilentVoiceIsRefusedAndTheInstalledOneIsUntouched() = runBlocking {
        voiceTrialOk = false
        val outcome = activator().activateVoice(voicePart(), voiceEntry)
        assertTrue("$outcome", outcome is ActivationOutcome.RolledBack)
        val v = voiceStore()
        assertEquals(TestModelFiles.sha256(oldTar), v.installedSha256())
        assertFalse(v.scratchDir.exists())
        assertEquals(0, narrationCleared)
        assertEquals(ModelKind.VOICE, (journal().read() as JournalRecord.RolledBack).model)
    }

    @Test
    fun aVoiceIsNotSwappedDuringAnExport() = runBlocking {
        exporting = true
        val outcome = activator().activateVoice(voicePart(), voiceEntry)
        assertEquals(RejectReason.BUSY, (outcome as ActivationOutcome.Rejected).reason)
        assertTrue(voiceTrialDirs.isEmpty())
    }

    @Test
    fun aCrashDuringTheVoiceTrialLeavesTheInstalledVoice() = runBlocking {
        try {
            activator(crashAt = "voice:trial").activateVoice(voicePart(), voiceEntry)
            fail()
        } catch (e: SimulatedDeath) {
        }
        assertTrue(voiceStore().scratchDir.exists())
        assertEquals(Recovery.ROLL_BACK_VOICE, activator().recoverOnStartup().recovery)
        val v = voiceStore()
        assertEquals(TestModelFiles.sha256(oldTar), v.installedSha256())
        assertFalse(v.scratchDir.exists())
        assertEquals(ModelKind.VOICE, activator().pendingNotice()?.model)
    }

    @Test
    fun aCrashMidSwapPutsThePreviousVoiceBack() = runBlocking {
        for (at in listOf("voice:journal-swapped", "voice:swapped")) {
            setUpVoiceAgain()
            try {
                activator(crashAt = at).activateVoice(voicePart(), voiceEntry)
                fail()
            } catch (e: SimulatedDeath) {
            }
            assertEquals(at, Recovery.ROLL_BACK_VOICE, activator().recoverOnStartup().recovery)
            val v = voiceStore()
            assertEquals(at, TestModelFiles.sha256(oldTar), v.installedSha256())
            assertTrue(at, v.isInstalled())
            assertFalse(at, v.previousDir.exists())
            assertEquals(at, 0, narrationCleared)
        }
    }

    @Test
    fun aCrashAfterTheVoiceSwapCommitsOnTheNextStart() = runBlocking {
        try {
            activator(crashAt = "voice:committed").activateVoice(voicePart(), voiceEntry)
            fail()
        } catch (e: SimulatedDeath) {
        }
        assertEquals(Recovery.FINISH_VOICE, activator().recoverOnStartup().recovery)
        assertEquals(newTarSha, voiceStore().installedSha256())
        assertFalse(voiceStore().previousDir.exists())
        assertEquals(1, narrationCleared)
    }

    @Test
    fun anUnreadableJournalFallsBackToTheCompiledNet() = runBlocking {
        activator().activateNet(netPart(), netEntry)
        // Put the old net back so the compiled one is on disk, then damage the journal.
        netStore().partFileFor().writeBytes(oldNet)
        netStore().installVerified(netStore().partFileFor())
        journal().file.apply { parentFile!!.mkdirs(); writeText("{broken") }
        assertEquals(Recovery.UNREADABLE, activator().recoverOnStartup().recovery)
        assertEquals(netStore().compiledIdentity(), netStore().activeIdentity())
    }

    private fun setUpVoiceAgain() = runBlocking {
        journal().clear()
        val v = voiceStore()
        v.previousDir.deleteRecursively()
        v.scratchDir.deleteRecursively()
        v.installFromStream({ oldTar.inputStream() }, TestModelFiles.sha256(oldTar), oldTar.size.toLong())
        narrationCleared = 0
    }
}
