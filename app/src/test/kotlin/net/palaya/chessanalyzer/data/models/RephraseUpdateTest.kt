package net.palaya.chessanalyzer.data.models

import kotlinx.coroutines.runBlocking
import net.palaya.chessanalyzer.engine.NetStore
import net.palaya.chessanalyzer.rephrase.RephraseModelStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * C2: the wording model as the third update kind (docs/LLM_REPHRASE_DESIGN.md §1.4): the manifest entry, the
 * compatibility rules, and the journaled swap / trial / rollback in [ModelActivator], on a temporary filesDir with
 * stand-in GGUFs. A "crash" is an Error thrown at a step boundary, then a fresh activator recovers.
 */
class RephraseUpdateTest {

    @get:Rule val tmp = TemporaryFolder()

    private class SimulatedDeath(at: String) : Error("process died at $at")

    private val oldGguf = TestGguf.bytes(seed = 1)
    private val newGguf = TestGguf.bytes(seed = 2, dataBytes = 80 * 1024)
    private val pins = RephraseModelStore.Pins("old-model", "old-model.gguf", oldGguf.size.toLong(), TestGguf.sha256(oldGguf), "qwen2", "models-2026.11")
    private lateinit var files: File
    private var trialOk = true
    private val trials = ArrayList<String>()
    private val activated = ArrayList<String>()

    private fun store() = RephraseModelStore(files, pins)

    private fun activator(crashAt: String? = null) = ModelActivator(
        netStore = NetStore(files, TestModelFiles.pinsFor(TestModelFiles.netBytes())),
        voiceStore = TestModelFiles.voiceStoreFor(files, TestModelFiles.voiceTar()),
        journal = ActivationJournal(files),
        netGate = object : NetActivationGate {
            override suspend fun <T> exclusive(block: suspend () -> T): T = block()
            override suspend fun trialLocked(): String = "unused"
        },
        voiceTrial = { VoiceTrialResult(true, "unused") },
        voiceBusy = { false },
        onNetActivated = {},
        onVoiceActivated = {},
        checkpoint = { if (it == crashAt) throw SimulatedDeath(it) },
        rephraseStore = store(),
        rephraseTrial = { f, id -> trials += "${f.name}/$id"; VoiceTrialResult(trialOk, if (trialOk) "accepted" else "rejected (FACTS_PLAYERS)") },
        onRephraseActivated = { activated += it },
    )

    private val entry get() = ManifestEntry(
        ModelKind.REPHRASE, "Wording model", "r2", "new-model.gguf", "https://x/models-2026.12/new-model.gguf",
        newGguf.size.toLong(), TestGguf.sha256(newGguf), 3, null, ModelCompat.Gguf("qwen2"), ModelRuntime("llama.cpp", "b11190", "b11999"),
    )

    private fun part(bytes: ByteArray = newGguf): File = store().updatePartFile("new-model.gguf").apply { parentFile!!.mkdirs(); writeBytes(bytes) }

    @Before
    fun setUp() {
        files = tmp.newFolder("files")
        val s = store()
        s.partFile.apply { parentFile!!.mkdirs(); writeBytes(oldGguf) }
        s.installVerified(s.partFile)
    }

    @Test
    fun aNewModelPassesItsTrialAndReplacesTheOldOne() = runBlocking {
        val outcome = activator().activateRephrase(part(), entry)
        assertTrue("$outcome", outcome is ActivationOutcome.Activated)
        val s = store()
        assertEquals("new-model.gguf", s.activePins().fileName)
        assertEquals("new-model", s.activePins().id)
        assertTrue(File(s.modelsDir, "new-model.gguf").isFile)
        assertFalse("the old file is deleted", File(s.modelsDir, "old-model.gguf").exists())
        assertEquals(listOf("new-model.gguf/new-model"), trials)
        assertEquals("the old model's cache is purged", listOf("new-model"), activated)
        assertEquals(null, ActivationJournal(files).read())
    }

    @Test
    fun aFailedTrialRollsBackAtOnceAndLeavesANotice() = runBlocking {
        trialOk = false
        val outcome = activator().activateRephrase(part(), entry)
        assertTrue("$outcome", outcome is ActivationOutcome.RolledBack)
        val s = store()
        assertEquals(pins, s.activePins())
        assertTrue(File(s.modelsDir, "old-model.gguf").isFile)
        assertFalse(File(s.modelsDir, "new-model.gguf").exists())
        assertTrue(ActivationJournal(files).read() is JournalRecord.RolledBack)
        assertEquals(emptyList<String>(), activated)
    }

    @Test
    fun aDeathDuringTheTrialIsRolledBackAtTheNextStart() = runBlocking {
        try {
            activator(crashAt = "rephrase:trial").activateRephrase(part(), entry)
        } catch (e: SimulatedDeath) {
            // the process is gone
        }
        assertEquals("the new model was active when it died", "new-model.gguf", store().activePins().fileName)
        val report = activator().recoverOnStartup()
        assertEquals(Recovery.ROLL_BACK_REPHRASE, report.recovery)
        assertEquals(pins, store().activePins())
        assertFalse(File(store().modelsDir, "new-model.gguf").exists())
        assertTrue(File(store().modelsDir, "old-model.gguf").isFile)
    }

    @Test
    fun aDeathAfterTheCommitFinishesTheCleanUp() = runBlocking {
        try {
            activator(crashAt = "rephrase:committed").activateRephrase(part(), entry)
        } catch (e: SimulatedDeath) {
            // gone after the trial passed
        }
        assertEquals(Recovery.FINISH_REPHRASE, activator().recoverOnStartup().recovery)
        assertEquals("new-model.gguf", store().activePins().fileName)
        assertFalse(File(store().modelsDir, "old-model.gguf").exists())
        assertEquals(listOf("new-model"), activated)
    }

    @Test
    fun aBrokenFileIsRefusedBeforeLlamaCppEverSeesIt() = runBlocking {
        val outcome = activator().activateRephrase(part(newGguf.copyOf(newGguf.size / 2)), entry)
        assertTrue("$outcome", outcome is ActivationOutcome.Rejected && outcome.reason == RejectReason.INCOMPATIBLE)
        assertEquals(emptyList<String>(), trials)
        assertEquals(pins, store().activePins())
        val other = activator().activateRephrase(part(TestGguf.bytes(arch = "gemma3")), entry)
        assertTrue("$other", other is ActivationOutcome.Rejected)
        assertEquals(emptyList<String>(), trials)
    }

    // ---- compatibility and the manifest ----

    private fun facts(installed: String? = "a".repeat(64), build: Int = 11190) = AppFacts(
        versionCode = 3, netVersion = 1, netArchHash = 1, sherpaOnnxVersion = "1.13.8", voiceLayout = "x",
        baseUrl = "https://x/", allowCleartextLoopback = false, installedNetSha256 = null, installedVoiceSha256 = null,
        limits = SizeLimits.DEFAULT.copy(rephraseMin = 1), rephraseArch = "qwen2", llamaCppBuild = build, installedRephraseSha256 = installed,
    )

    @Test
    fun anUpdateIsOfferedOnlyToAPhoneThatHasTheModelAndRunsTheRightLlamaCpp() {
        assertEquals(CompatVerdict.Offer, ModelCompatibility.evaluate(entry, facts()))
        assertEquals(CompatVerdict.AlreadyInstalled, ModelCompatibility.evaluate(entry, facts(installed = entry.sha256)))
        val notInstalled = ModelCompatibility.evaluate(entry, facts(installed = null))
        assertTrue("$notInstalled", notInstalled is CompatVerdict.Incompatible && notInstalled.reason == Incompatibility.NOT_INSTALLED)
        val oldRuntime = ModelCompatibility.evaluate(entry, facts(build = 11000))
        assertTrue("$oldRuntime", oldRuntime is CompatVerdict.Incompatible && oldRuntime.reason == Incompatibility.REPHRASE_RUNTIME)
    }

    @Test
    fun aGgufOfAnotherArchitectureOrWithABadNameIsNeverOffered() {
        val arch = ModelCompatibility.evaluate(entry.copy(compat = ModelCompat.Gguf("llama")), facts())
        assertTrue("$arch", arch is CompatVerdict.Incompatible && arch.reason == Incompatibility.REPHRASE_ARCH)
        val name = ModelCompatibility.evaluate(entry.copy(fileName = "model.bin", url = "https://x/t/model.bin"), facts())
        assertTrue("$name", name is CompatVerdict.Incompatible && name.reason == Incompatibility.BAD_FILE_NAME)
        val big = ModelCompatibility.evaluate(entry.copy(sizeBytes = 5_000_000_000L), facts())
        assertTrue("$big", big is CompatVerdict.Incompatible && big.reason == Incompatibility.SIZE_OUT_OF_RANGE)
        val compat = ModelCompatibility.evaluate(entry.copy(compat = ModelCompat.SherpaKokoro("x")), facts())
        assertTrue("$compat", compat is CompatVerdict.Incompatible && compat.reason == Incompatibility.WRONG_COMPAT_KIND)
    }

    @Test
    fun theManifestReadsTheWordingModelEntryAndRequiresItsRuntime() {
        val json = """
            {"schemaVersion":1,"models":[{"id":"rephrase-qwen","displayName":"Wording model","version":"r2",
             "fileName":"new-model.gguf","url":"https://x/t/new-model.gguf","size":1117320736,"sha256":"${"b".repeat(64)}",
             "minVersionCode":3,"maxVersionCode":null,"compat":{"kind":"gguf","arch":"qwen2"},
             "runtime":{"name":"llama.cpp","min":"b11190","max":"b11999"}}]}
        """.trimIndent()
        val parsed = ModelManifest.parse(json.toByteArray()) as ManifestParse.Valid
        val e = parsed.manifest.entries.single()
        assertEquals(ModelKind.REPHRASE, e.kind)
        assertEquals(ModelCompat.Gguf("qwen2"), e.compat)
        assertEquals(ModelRuntime("llama.cpp", "b11190", "b11999"), e.runtime)
        val noRuntime = json.replace(Regex(""",\s*"runtime":\{[^}]*\}"""), "")
        assertTrue(noRuntime, "runtime" !in noRuntime)
        assertTrue(ModelManifest.parse(noRuntime.toByteArray()) is ManifestParse.Invalid)
    }
}
