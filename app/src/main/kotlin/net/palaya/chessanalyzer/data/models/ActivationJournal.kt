package net.palaya.chessanalyzer.data.models

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import org.json.JSONException
import org.json.JSONObject

/** A model file as the journal names it: file name, size and full SHA-256 (a voice's "old" may have size 0). */
data class ModelIdentity(val name: String, val sizeBytes: Long, val sha256: String)

/**
 * Where an activation stands (docs/MODEL_DOWNLOAD_DESIGN.md §4.1). The order differs per model:
 *  - **net**: [SWAPPED] (the new file is in `nets/` and is the active net) -> [TRIAL] (the engine is
 *    loading it and searching depth 1) -> [COMMITTED] (the trial passed; the old file and the other nets'
 *    eval caches are being deleted).
 *  - **voice**: [TRIAL] (unpacked in the scratch directory, a throwaway synthesis is running) -> [SWAPPED]
 *    (the directories are being renamed) -> [COMMITTED] (the previous voice and the narration cache are
 *    being deleted).
 */
enum class JournalPhase(val wire: String) {
    SWAPPED("swapped"),
    TRIAL("trial"),
    COMMITTED("committed");

    companion object {
        fun fromWire(s: String): JournalPhase? = entries.firstOrNull { it.wire == s }
    }
}

/** What `filesDir/models/activation.json` holds. */
sealed interface JournalRecord {
    /** An activation in progress: found at start, it means the process died in the middle of it. */
    data class InFlight(
        val model: ModelKind,
        val phase: JournalPhase,
        val new: ModelIdentity,
        val old: ModelIdentity?,
        val atMs: Long,
    ) : JournalRecord

    /** An update was rolled back; Settings says so once ("…the previous version was restored."). */
    data class RolledBack(val model: ModelKind?, val atMs: Long, val reason: String) : JournalRecord

    /** The file exists but cannot be read (it is written atomically, so this should never happen). */
    data object Unreadable : JournalRecord
}

/** What [ModelActivator.recoverOnStartup] must do for the record it found. */
enum class Recovery { NOTHING, ROLL_BACK_NET, FINISH_NET, ROLL_BACK_VOICE, FINISH_VOICE, KEEP_NOTICE, UNREADABLE }

/**
 * The journal's rules, pure (host-tested in `ActivationJournalTest`): which phase may follow which, and
 * what a record found at start means. The **only** way out of an in-flight record other than reaching
 * [JournalPhase.COMMITTED] is a rollback.
 */
object ActivationMachine {

    /** The phases of [model] in order. */
    fun phases(model: ModelKind): List<JournalPhase> = when (model) {
        ModelKind.NET -> listOf(JournalPhase.SWAPPED, JournalPhase.TRIAL, JournalPhase.COMMITTED)
        ModelKind.VOICE -> listOf(JournalPhase.TRIAL, JournalPhase.SWAPPED, JournalPhase.COMMITTED)
    }

    /** True when [to] may be written over [from] (null = no journal: only the first phase may start). */
    fun canAdvance(model: ModelKind, from: JournalPhase?, to: JournalPhase): Boolean {
        val order = phases(model)
        return if (from == null) to == order.first() else order.indexOf(to) == order.indexOf(from) + 1
    }

    /**
     * A record found when the process starts. An in-flight record before [JournalPhase.COMMITTED] means the
     * process died between the swap and a successful trial (most likely Stockfish's `exit()` on a net it
     * could not load, CLAUDE.md gotcha 1): roll back. At [JournalPhase.COMMITTED] the new file had passed
     * its trial: finish the clean-up, no rollback.
     */
    fun recoveryFor(record: JournalRecord?): Recovery = when (record) {
        null -> Recovery.NOTHING
        is JournalRecord.RolledBack -> Recovery.KEEP_NOTICE
        JournalRecord.Unreadable -> Recovery.UNREADABLE
        is JournalRecord.InFlight -> when (record.model) {
            ModelKind.NET -> if (record.phase == JournalPhase.COMMITTED) Recovery.FINISH_NET else Recovery.ROLL_BACK_NET
            ModelKind.VOICE -> if (record.phase == JournalPhase.COMMITTED) Recovery.FINISH_VOICE else Recovery.ROLL_BACK_VOICE
        }
    }
}

/**
 * `filesDir/models/activation.json` (excluded from backup with `models/`). Every write is atomic: a
 * temp file, `fsync`, then a rename over the journal, so a kill leaves the old record or the new one,
 * never half of one. JSON via org.json:
 *
 * `{"model":"engine-net","phase":"trial","new":{"name":"nn-….nnue","size":…,"sha256":"…"},"old":{…}|null,"at":<ms>}`
 * or, after a rollback, `{"rolledBack":"engine-net","reason":"…","at":<ms>}`.
 */
class ActivationJournal(filesDir: File) {

    companion object {
        const val DIR_NAME = "models"
        const val FILE_NAME = "activation.json"
    }

    val file: File = File(File(filesDir, DIR_NAME), FILE_NAME)

    fun read(): JournalRecord? {
        if (!file.isFile) return null
        return try {
            parse(JSONObject(file.readText()))
        } catch (e: IOException) {
            JournalRecord.Unreadable
        } catch (e: JSONException) {
            JournalRecord.Unreadable
        }
    }

    /** Writes [record]; refuses a phase that does not follow the one on disk ([ActivationMachine.canAdvance]). */
    fun advance(record: JournalRecord.InFlight) {
        val current = read() as? JournalRecord.InFlight
        val from = current?.takeIf { it.model == record.model && it.new == record.new }?.phase
        check(ActivationMachine.canAdvance(record.model, from, record.phase)) {
            "journal: ${record.model} cannot go from $from to ${record.phase}"
        }
        write(toJson(record))
    }

    fun writeRolledBack(model: ModelKind?, reason: String, atMs: Long) {
        write(
            JSONObject().apply {
                put("rolledBack", model?.id ?: "unknown")
                put("reason", reason.take(200))
                put("at", atMs)
            },
        )
    }

    fun clear() {
        file.delete()
    }

    private fun write(json: JSONObject) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, "$FILE_NAME.tmp")
        FileOutputStream(tmp).use { out ->
            out.write(json.toString().toByteArray(Charsets.UTF_8))
            out.flush()
            out.fd.sync()
        }
        if (!tmp.renameTo(file)) {
            file.delete()
            if (!tmp.renameTo(file)) throw IOException("could not write ${file.path}")
        }
    }

    private fun parse(o: JSONObject): JournalRecord {
        if (o.has("rolledBack")) {
            return JournalRecord.RolledBack(
                model = ModelKind.fromId(o.optString("rolledBack")),
                atMs = o.optLong("at", 0L),
                reason = if (o.has("reason") && !o.isNull("reason")) o.getString("reason") else "",
            )
        }
        val model = ModelKind.fromId(o.getString("model")) ?: return JournalRecord.Unreadable
        val phase = JournalPhase.fromWire(o.getString("phase")) ?: return JournalRecord.Unreadable
        val new = identity(o.getJSONObject("new"))
        val old = if (!o.has("old") || o.isNull("old")) null else identity(o.getJSONObject("old"))
        return JournalRecord.InFlight(model, phase, new, old, o.optLong("at", 0L))
    }

    private fun identity(o: JSONObject) = ModelIdentity(o.getString("name"), o.getLong("size"), o.getString("sha256"))

    private fun toJson(r: JournalRecord.InFlight) = JSONObject().apply {
        put("model", r.model.id)
        put("phase", r.phase.wire)
        put("new", identityJson(r.new))
        put("old", r.old?.let { identityJson(it) } ?: JSONObject.NULL)
        put("at", r.atMs)
    }

    private fun identityJson(i: ModelIdentity) = JSONObject().apply {
        put("name", i.name)
        put("size", i.sizeBytes)
        put("sha256", i.sha256)
    }
}
