package net.palaya.chessanalyzer.data.models

import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import net.palaya.chessanalyzer.diagnostics.DiagnosticLog
import net.palaya.chessanalyzer.engine.ActiveNet
import net.palaya.chessanalyzer.engine.NetStore
import net.palaya.chessanalyzer.engine.StockfishEngine
import net.palaya.chessanalyzer.rephrase.GgufHeader
import net.palaya.chessanalyzer.rephrase.RephraseModelStore
import net.palaya.chessanalyzer.video.InsufficientVoiceStorageException
import net.palaya.chessanalyzer.video.VoiceArchiveDamagedException
import net.palaya.chessanalyzer.video.VoiceStore

/** An analysis is running: the net cannot be switched now (the update keeps its downloaded file). */
class EngineBusyException : IllegalStateException("an analysis is running")

/**
 * The engine side of a net activation (docs/MODEL_DOWNLOAD_DESIGN.md §4.2). `EngineController` in the app:
 * one engine per process (CLAUDE.md gotcha 2), so the trial runs on THE engine, never on a second one.
 */
interface NetActivationGate {
    /** Runs [block] while no analysis can start; throws [EngineBusyException] when one is running. */
    suspend fun <T> exclusive(block: suspend () -> T): T

    /**
     * Only inside [exclusive]: hands the engine `NetStore.verifiedNetOrNull()` (full SHA-256 against the
     * active identity, the `setEvalFile` guards on top) and runs a depth-1 search from the start position.
     * Returns a line for the log. Throws when the net is not verified or the search gives no move. A net
     * Stockfish cannot load makes it `exit()` here: that is what the journal is for.
     */
    suspend fun trialLocked(): String
}

/** A throwaway synthesis on a voice directory (design §4.3): `NeuralVoiceTrial` in the app. */
fun interface VoiceTrial {
    suspend fun run(modelDir: File): VoiceTrialResult
}

data class VoiceTrialResult(val ok: Boolean, val detail: String)

/**
 * C2: the trial of a new wording model (docs/LLM_REPHRASE_DESIGN.md §1.4): load it and rephrase one fixed text; the
 * result must pass the claim checker (accepted or unchanged). The app's implementation releases the backend in use
 * first (one model per process).
 */
fun interface RephraseTrial {
    suspend fun run(model: File, modelId: String): VoiceTrialResult
}

/** How an activation ended. Only [Activated] changed anything for good. */
sealed interface ActivationOutcome {
    data class Activated(val kind: ModelKind, val detail: String) : ActivationOutcome

    /** Refused before anything was swapped; the installed model is untouched. [keepPart]: resumable. */
    data class Rejected(val kind: ModelKind, val reason: RejectReason, val detail: String) : ActivationOutcome

    /** The trial failed and the previous version was restored at once (the same message as after a crash). */
    data class RolledBack(val kind: ModelKind, val detail: String) : ActivationOutcome
}

enum class RejectReason {
    /** The file's header / layout is not one this app can use (never handed to native code). */
    INCOMPATIBLE,

    /** The archive failed its checks (hash, size, missing files). */
    DAMAGED,

    /** An analysis or an export is running. */
    BUSY,

    /** Not enough space to unpack. */
    INSUFFICIENT_STORAGE,

    /** The file could not be moved into place. */
    INSTALL,
}

/** What [ModelActivator.recoverOnStartup] did, for the log and the tests. */
data class RecoveryReport(val recovery: Recovery, val model: ModelKind?, val detail: String)

/**
 * Activates a downloaded, verified model file with a journal, a trial and a rollback
 * (docs/MODEL_DOWNLOAD_DESIGN.md §4). Host-tested with fakes for the engine and the voice trial.
 *
 * **Net** ([activateNet]): the header is checked in Kotlin first (version and architecture equal this
 * engine's pins, a sane description length, a plausible size), so a net Stockfish cannot parse never
 * reaches it. Then, while no analysis can start ([NetActivationGate.exclusive]): journal `swapped`, move
 * the part into `nets/`, make it the active identity; journal `trial`; the trial loads it through
 * `verifiedNetOrNull()` (full SHA-256 against the signed manifest's value) and searches depth 1; journal
 * `committed`; the old file and every other net's eval cache are deleted; the journal is cleared. A trial
 * that throws is rolled back at once (the old identity is restored and re-loaded). A trial that kills the
 * process (Stockfish's `exit()`) is rolled back by [recoverOnStartup] on the next launch, before anything
 * touches the engine.
 *
 * **Voice** ([activateVoice]): unpacked and verified into the scratch directory; journal `trial`; a
 * throwaway synthesis must give non-silent speech ([VoiceTrial]); journal `swapped`; `kokoro/` ->
 * `kokoro.previous/`, scratch -> `kokoro/`, marker and `.compat`; journal `committed`; the previous voice
 * and the narration cache are deleted (old WAVs can never be hit again: the cache key also carries the
 * voice id). A failed trial leaves the installed voice untouched.
 *
 * [checkpoint] exists for tests only: it is called at each step boundary, so a test can stop the activation
 * there exactly as a killed process would and then run [recoverOnStartup] on a fresh activator.
 */
class ModelActivator(
    private val netStore: NetStore,
    private val voiceStore: VoiceStore,
    private val journal: ActivationJournal,
    private val netGate: NetActivationGate,
    private val voiceTrial: VoiceTrial,
    /** True while a video export runs (`VideoExportService.running`): the voice must not move under it. */
    private val voiceBusy: () -> Boolean,
    /** After a net commits: purge the other nets' eval caches (`GameRepository.purgeEvalCachesExcept`). */
    private val onNetActivated: (ActiveNet) -> Unit,
    /** After a voice commits: `NarrationStore.clear()`. */
    private val onVoiceActivated: () -> Unit,
    private val diagnostics: DiagnosticLog? = null,
    private val clock: () -> Long = System::currentTimeMillis,
    private val checkpoint: (String) -> Unit = {},
    /** C2: the wording model's store, its trial, and what to do after a switch (purge the old model's cache). */
    private val rephraseStore: RephraseModelStore? = null,
    private val rephraseTrial: RephraseTrial? = null,
    private val onRephraseActivated: (newModelId: String) -> Unit = {},
) {
    companion object {
        const val TAG = "models"
    }

    private fun log(line: String) {
        diagnostics?.log(TAG, line)
    }

    // ---------------------------------------------------------------------------------------------
    // Startup recovery
    // ---------------------------------------------------------------------------------------------

    /**
     * Runs first thing in `Application.onCreate`, synchronously (file renames only, nothing is hashed or
     * loaded), and before anything can touch the engine or the voice.
     */
    fun recoverOnStartup(): RecoveryReport {
        val record = journal.read()
        val recovery = ActivationMachine.recoveryFor(record)
        val report = when (recovery) {
            Recovery.NOTHING -> RecoveryReport(recovery, null, "no journal")
            Recovery.KEEP_NOTICE -> RecoveryReport(recovery, (record as JournalRecord.RolledBack).model, "a rollback notice is waiting for Settings")
            Recovery.ROLL_BACK_NET -> {
                val r = record as JournalRecord.InFlight
                rollBackNetFiles(r.new, r.old)
                journal.writeRolledBack(ModelKind.NET, "the process ended during the ${r.phase.wire} phase", clock())
                RecoveryReport(recovery, ModelKind.NET, "net ${r.new.name} rolled back to ${r.old?.name ?: "the compiled net"} (died in ${r.phase.wire})")
            }
            Recovery.FINISH_NET -> {
                val r = record as JournalRecord.InFlight
                finishNetCommit(r.new, r.old)
                RecoveryReport(recovery, ModelKind.NET, "net ${r.new.name} had passed its trial; clean-up finished")
            }
            Recovery.ROLL_BACK_VOICE -> {
                val r = record as JournalRecord.InFlight
                val restored = rollBackVoiceFiles()
                journal.writeRolledBack(ModelKind.VOICE, "the process ended during the ${r.phase.wire} phase", clock())
                RecoveryReport(recovery, ModelKind.VOICE, "voice ${r.new.sha256.take(12)} rolled back (died in ${r.phase.wire}; previous restored: $restored)")
            }
            Recovery.FINISH_VOICE -> {
                val r = record as JournalRecord.InFlight
                finishVoiceCommit()
                RecoveryReport(recovery, ModelKind.VOICE, "voice ${r.new.sha256.take(12)} had passed its trial; clean-up finished")
            }
            Recovery.ROLL_BACK_REPHRASE -> {
                val r = record as JournalRecord.InFlight
                rollBackRephraseFiles(r.new, r.old)
                journal.writeRolledBack(ModelKind.REPHRASE, "the process ended during the ${r.phase.wire} phase", clock())
                RecoveryReport(recovery, ModelKind.REPHRASE, "wording model ${r.new.name} rolled back to ${r.old?.name ?: "the compiled model"} (died in ${r.phase.wire})")
            }
            Recovery.FINISH_REPHRASE -> {
                val r = record as JournalRecord.InFlight
                finishRephraseCommit(r.new, r.old)
                RecoveryReport(recovery, ModelKind.REPHRASE, "wording model ${r.new.name} had passed its trial; clean-up finished")
            }
            Recovery.UNREADABLE -> {
                // Never expected (writes are atomic). Fail safe: back to the compiled net (the engine then
                // loads only the pinned file), and the voice from before an interrupted swap, if any.
                if (netStore.hasUpdateRecord()) netStore.setActiveIdentity(netStore.compiledIdentity())
                rollBackVoiceFiles()
                journal.writeRolledBack(null, "the activation journal could not be read", clock())
                RecoveryReport(recovery, null, "unreadable journal: back to the compiled net")
            }
        }
        if (recovery != Recovery.NOTHING) log("recoverOnStartup: ${report.detail}")
        return report
    }

    /** The rollback notice for Settings ("…the previous version was restored."), or null. */
    fun pendingNotice(): JournalRecord.RolledBack? = journal.read() as? JournalRecord.RolledBack

    /** Settings showed the notice: it is not shown again. */
    fun acknowledgeNotice() {
        if (journal.read() is JournalRecord.RolledBack) journal.clear()
    }

    // ---------------------------------------------------------------------------------------------
    // Net
    // ---------------------------------------------------------------------------------------------

    /**
     * Activates the verified net [part] (size and SHA-256 already checked against [entry] by the
     * downloader). [entry] must have passed [ModelCompatibility] (the installer checks it again before the
     * download).
     */
    suspend fun activateNet(part: File, entry: ManifestEntry): ActivationOutcome = withContext(Dispatchers.IO) {
        require(entry.kind == ModelKind.NET)
        // 1. Structural validation in Kotlin. The engine is never used to "test-load" a net it may not parse.
        val header = runCatching { netStore.readHeader(part) }.getOrNull()
        val length = part.length()
        val problem = when {
            !part.isFile -> "the downloaded file is missing"
            header == null -> "no NNUE header"
            !header.matches(netStore.pins) -> "header version 0x${header.version.toString(16)} / architecture 0x${header.archHash.toString(16)} " +
                "(description ${header.descriptionLength} B) is not this engine's 0x${netStore.pins.version.toString(16)} / 0x${netStore.pins.archHash.toString(16)}"
            length != entry.sizeBytes -> "size $length, the manifest says ${entry.sizeBytes}"
            length < StockfishEngine.MIN_PLAUSIBLE_NET_BYTES -> "only $length bytes"
            NetStore.prefixOf(entry.fileName) == null -> "not a net name: ${entry.fileName}"
            else -> null
        }
        if (problem != null) {
            part.delete()
            log("net update ${entry.fileName} refused before the engine saw it: $problem")
            return@withContext ActivationOutcome.Rejected(ModelKind.NET, RejectReason.INCOMPATIBLE, problem)
        }

        val new = ActiveNet(entry.fileName, entry.sizeBytes, entry.sha256)
        try {
            netGate.exclusive {
                withContext(NonCancellable) { swapTrialCommitNet(part, new) }
            }
        } catch (e: EngineBusyException) {
            log("net update ${entry.fileName} waits: an analysis is running")
            ActivationOutcome.Rejected(ModelKind.NET, RejectReason.BUSY, "an analysis is running")
        }
    }

    private suspend fun swapTrialCommitNet(part: File, new: ActiveNet): ActivationOutcome {
        val old = netStore.activeIdentity()
        if (old.fileName == new.fileName) {
            part.delete()
            return ActivationOutcome.Rejected(ModelKind.NET, RejectReason.INCOMPATIBLE, "the same net name is already active")
        }
        val oldId = ModelIdentity(old.fileName, old.sizeBytes, old.sha256)
        val newId = ModelIdentity(new.fileName, new.sizeBytes, new.sha256)

        // 2. Journal, then swap: the part becomes nets/<new> and the active identity.
        journal.advance(JournalRecord.InFlight(ModelKind.NET, JournalPhase.SWAPPED, newId, oldId, clock()))
        checkpoint("net:journal-swapped")
        try {
            netStore.installVerified(part, new.fileName)
            netStore.setActiveIdentity(new)
        } catch (e: IOException) {
            rollBackNetFiles(newId, oldId)
            journal.clear()
            log("net update ${new.fileName}: could not be moved into place (${e.message}); nothing changed")
            return ActivationOutcome.Rejected(ModelKind.NET, RejectReason.INSTALL, e.message ?: "install failed")
        }
        checkpoint("net:swapped")

        // 3. Trial on the one engine.
        journal.advance(JournalRecord.InFlight(ModelKind.NET, JournalPhase.TRIAL, newId, oldId, clock()))
        checkpoint("net:trial")
        log("net update ${new.fileName}: trying it (depth-1 search on the app's engine)")
        val trial = try {
            Result.success(netGate.trialLocked())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
        if (trial.isFailure) {
            val why = trial.exceptionOrNull()?.let { "${it.javaClass.simpleName}: ${it.message}" } ?: "unknown"
            rollBackNetFiles(newId, oldId)
            val reload = runCatching { netGate.trialLocked() }
            journal.writeRolledBack(ModelKind.NET, "trial failed: $why", clock())
            log("net update ${new.fileName}: the trial failed ($why); rolled back to ${old.fileName}" +
                (reload.exceptionOrNull()?.let { ", and reloading it failed too (${it.javaClass.simpleName})" } ?: ", reloaded"))
            return ActivationOutcome.RolledBack(ModelKind.NET, why)
        }
        log("net update ${new.fileName}: trial passed (${trial.getOrNull()})")

        // 4. Commit.
        journal.advance(JournalRecord.InFlight(ModelKind.NET, JournalPhase.COMMITTED, newId, oldId, clock()))
        checkpoint("net:committed")
        finishNetCommit(newId, oldId)
        log("net update ${new.fileName}: installed; ${old.fileName} deleted, other nets' eval caches purged")
        return ActivationOutcome.Activated(ModelKind.NET, trial.getOrNull().orEmpty())
    }

    private fun rollBackNetFiles(new: ModelIdentity, old: ModelIdentity?) {
        val restore = old?.let { ActiveNet(it.name, it.sizeBytes, it.sha256) } ?: netStore.compiledIdentity()
        if (netStore.activeIdentity() != restore) netStore.setActiveIdentity(restore)
        if (new.name != restore.fileName) {
            netStore.deleteNet(new.name)
            netStore.partFileFor(new.name).delete()
        }
    }

    private fun finishNetCommit(new: ModelIdentity, old: ModelIdentity?) {
        val active = ActiveNet(new.name, new.sizeBytes, new.sha256)
        if (netStore.activeIdentity() != active) netStore.setActiveIdentity(active)
        old?.let { if (it.name != new.name) netStore.deleteNet(it.name) }
        runCatching { onNetActivated(active) }.onFailure { log("eval-cache purge failed: ${it.javaClass.simpleName}") }
        journal.clear()
    }

    // ---------------------------------------------------------------------------------------------
    // Voice
    // ---------------------------------------------------------------------------------------------

    /**
     * Activates the verified voice tar [part]. [onUnpack] reports 0..1 while it is unpacked into the
     * scratch directory; [onTrial] is called when the trial synthesis starts.
     */
    suspend fun activateVoice(
        part: File,
        entry: ManifestEntry,
        onUnpack: (Float) -> Unit = {},
        onTrial: () -> Unit = {},
    ): ActivationOutcome = withContext(Dispatchers.IO) {
        require(entry.kind == ModelKind.VOICE)
        val compat = entry.compat as? ModelCompat.SherpaKokoro
        val runtime = entry.runtime
        if (compat == null || runtime == null) {
            part.delete()
            return@withContext ActivationOutcome.Rejected(ModelKind.VOICE, RejectReason.INCOMPATIBLE, "no voice compat")
        }
        if (voiceBusy()) return@withContext ActivationOutcome.Rejected(ModelKind.VOICE, RejectReason.BUSY, "a video export is running")

        // 1. Unpack and verify into the scratch directory. The installed voice is not touched.
        val scratch = try {
            // Checked against the TAR's pins (a .tar.gz entry carries them, D2f; a plain tar is its own).
            voiceStore.extractToScratch(part, entry.unpackedSha256, entry.unpackedSizeBytes, onUnpack)
        } catch (e: InsufficientVoiceStorageException) {
            return@withContext ActivationOutcome.Rejected(ModelKind.VOICE, RejectReason.INSUFFICIENT_STORAGE, e.message ?: "low storage")
        } catch (e: VoiceArchiveDamagedException) {
            part.delete()
            log("voice update ${entry.version}: the archive failed its checks (${e.message})")
            return@withContext ActivationOutcome.Rejected(ModelKind.VOICE, RejectReason.DAMAGED, e.message ?: "damaged")
        } catch (e: IOException) {
            part.delete()
            log("voice update ${entry.version}: could not be unpacked (${e.javaClass.simpleName}: ${e.message})")
            return@withContext ActivationOutcome.Rejected(ModelKind.VOICE, RejectReason.DAMAGED, e.message ?: "unpack failed")
        }

        withContext(NonCancellable) {
            val newId = ModelIdentity(entry.fileName, entry.unpackedSizeBytes, entry.unpackedSha256)
            val oldId = voiceStore.installedSha256()?.let { ModelIdentity("installed", 0L, it) }

            // 2. Trial in the scratch directory.
            journal.advance(JournalRecord.InFlight(ModelKind.VOICE, JournalPhase.TRIAL, newId, oldId, clock()))
            checkpoint("voice:trial")
            onTrial()
            log("voice update ${entry.version}: trying it (a throwaway synthesis)")
            val result = try {
                voiceTrial.run(scratch)
            } catch (e: Exception) {
                VoiceTrialResult(false, "${e.javaClass.simpleName}: ${e.message}")
            }
            if (!result.ok) {
                voiceStore.discardScratch()
                part.delete()
                journal.writeRolledBack(ModelKind.VOICE, "trial failed: ${result.detail}", clock())
                log("voice update ${entry.version}: the trial failed (${result.detail}); the installed voice was kept")
                return@withContext ActivationOutcome.RolledBack(ModelKind.VOICE, result.detail)
            }
            log("voice update ${entry.version}: trial passed (${result.detail})")
            if (voiceBusy()) {
                voiceStore.discardScratch()
                journal.clear()
                return@withContext ActivationOutcome.Rejected(ModelKind.VOICE, RejectReason.BUSY, "a video export started")
            }

            // 3. Swap.
            journal.advance(JournalRecord.InFlight(ModelKind.VOICE, JournalPhase.SWAPPED, newId, oldId, clock()))
            checkpoint("voice:journal-swapped")
            try {
                voiceStore.swapInScratch(entry.unpackedSha256, compat.layout, runtime.min, runtime.max)
            } catch (e: Exception) {
                val restored = rollBackVoiceFiles()
                journal.clear()
                log("voice update ${entry.version}: the swap failed (${e.message}); previous restored: $restored")
                return@withContext ActivationOutcome.Rejected(ModelKind.VOICE, RejectReason.INSTALL, e.message ?: "swap failed")
            }
            checkpoint("voice:swapped")

            // 4. Commit.
            journal.advance(JournalRecord.InFlight(ModelKind.VOICE, JournalPhase.COMMITTED, newId, oldId, clock()))
            checkpoint("voice:committed")
            part.delete()
            finishVoiceCommit()
            log("voice update ${entry.version}: installed (${entry.unpackedSha256.take(12)}); the narration cache was cleared")
            ActivationOutcome.Activated(ModelKind.VOICE, result.detail)
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Wording model (C2)
    // ---------------------------------------------------------------------------------------------

    private fun pinsFor(id: ModelIdentity, store: RephraseModelStore): RephraseModelStore.Pins =
        if (id.name == store.compiled.fileName) store.compiled
        else RephraseModelStore.Pins(
            id = id.name.removeSuffix(".gguf").lowercase(), fileName = id.name, sizeBytes = id.sizeBytes, sha256 = id.sha256,
            arch = store.compiled.arch, releaseTag = store.compiled.releaseTag,
        )

    /**
     * Activates a verified wording-model [part] (C2, docs/LLM_REPHRASE_DESIGN.md §1.4): the GGUF structure is checked
     * in Kotlin first (llama.cpp never parses a file that failed); then journal `swapped` (moved into place and made
     * active), `trial` (load + one checked rephrase, [RephraseTrial]), `committed` (the old file and the old model's
     * cache folder go). A trial that fails is rolled back at once; one that kills the process (a native abort) is
     * rolled back by [recoverOnStartup].
     */
    suspend fun activateRephrase(part: File, entry: ManifestEntry): ActivationOutcome = withContext(Dispatchers.IO) {
        require(entry.kind == ModelKind.REPHRASE)
        val store = rephraseStore
        val trialRunner = rephraseTrial
        val compat = entry.compat as? ModelCompat.Gguf
        if (store == null || trialRunner == null || compat == null) {
            part.delete()
            return@withContext ActivationOutcome.Rejected(ModelKind.REPHRASE, RejectReason.INCOMPATIBLE, "no wording-model support")
        }
        val problem = try {
            GgufHeader.check(part, compat.arch)
            if (part.length() != entry.sizeBytes) "size ${part.length()}, the manifest says ${entry.sizeBytes}" else null
        } catch (e: IOException) {
            e.message ?: "not a GGUF"
        }
        val old = store.activePins()
        val oldInstalled = store.installedFileOrNull() != null
        if (problem == null && entry.fileName == old.fileName) {
            part.delete()
            return@withContext ActivationOutcome.Rejected(ModelKind.REPHRASE, RejectReason.INCOMPATIBLE, "the same file name is already in use")
        }
        if (problem != null) {
            part.delete()
            log("wording model update ${entry.fileName} refused before llama.cpp saw it: $problem")
            return@withContext ActivationOutcome.Rejected(ModelKind.REPHRASE, RejectReason.INCOMPATIBLE, problem)
        }
        withContext(NonCancellable) {
            val newId = ModelIdentity(entry.fileName, entry.sizeBytes, entry.sha256)
            val oldId = if (oldInstalled) ModelIdentity(old.fileName, old.sizeBytes, old.sha256) else null
            val newPins = pinsFor(newId, store)

            journal.advance(JournalRecord.InFlight(ModelKind.REPHRASE, JournalPhase.SWAPPED, newId, oldId, clock()))
            checkpoint("rephrase:journal-swapped")
            try {
                store.installVerified(part, newPins)
                store.setActivePins(newPins)
            } catch (e: IOException) {
                rollBackRephraseFiles(newId, oldId)
                journal.clear()
                return@withContext ActivationOutcome.Rejected(ModelKind.REPHRASE, RejectReason.INSTALL, e.message ?: "install failed")
            }
            checkpoint("rephrase:swapped")

            journal.advance(JournalRecord.InFlight(ModelKind.REPHRASE, JournalPhase.TRIAL, newId, oldId, clock()))
            checkpoint("rephrase:trial")
            log("wording model update ${entry.fileName}: trying it (load + one checked rephrase)")
            val result = try {
                trialRunner.run(store.fileFor(entry.fileName), newPins.id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                VoiceTrialResult(false, "${e.javaClass.simpleName}: ${e.message}")
            }
            if (!result.ok) {
                rollBackRephraseFiles(newId, oldId)
                journal.writeRolledBack(ModelKind.REPHRASE, "trial failed: ${result.detail}", clock())
                log("wording model update ${entry.fileName}: the trial failed (${result.detail}); rolled back")
                return@withContext ActivationOutcome.RolledBack(ModelKind.REPHRASE, result.detail)
            }

            journal.advance(JournalRecord.InFlight(ModelKind.REPHRASE, JournalPhase.COMMITTED, newId, oldId, clock()))
            checkpoint("rephrase:committed")
            finishRephraseCommit(newId, oldId)
            log("wording model update ${entry.fileName}: installed (${result.detail})")
            ActivationOutcome.Activated(ModelKind.REPHRASE, result.detail)
        }
    }

    private fun rollBackRephraseFiles(new: ModelIdentity, old: ModelIdentity?) {
        val store = rephraseStore ?: return
        val restore = old?.let { pinsFor(it, store) }
        store.setActivePins(restore)
        if (new.name != old?.name && new.name != store.compiled.fileName) store.fileFor(new.name).delete()
        store.updatePartFile(new.name).delete()
    }

    private fun finishRephraseCommit(new: ModelIdentity, old: ModelIdentity?) {
        val store = rephraseStore
        if (store != null) {
            val pins = pinsFor(new, store)
            if (store.activePins() != pins) store.setActivePins(pins)
            old?.let { if (it.name != new.name) store.fileFor(it.name).delete() }
            runCatching { onRephraseActivated(pins.id) }.onFailure { log("rephrase cache purge failed: ${it.javaClass.simpleName}") }
        }
        journal.clear()
    }

    /** Puts the previous voice back if a swap was under way and drops any scratch. True if one was restored. */
    private fun rollBackVoiceFiles(): Boolean {
        val restored = voiceStore.restorePrevious()
        voiceStore.discardScratch()
        voiceStore.deleteUpdateParts()
        return restored
    }

    private fun finishVoiceCommit() {
        voiceStore.deletePrevious()
        voiceStore.discardScratch()
        runCatching { onVoiceActivated() }.onFailure { log("narration cache clear failed: ${it.javaClass.simpleName}") }
        journal.clear()
    }
}
