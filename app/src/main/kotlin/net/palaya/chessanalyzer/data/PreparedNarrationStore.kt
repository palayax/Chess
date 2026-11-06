package net.palaya.chessanalyzer.data

import java.io.File
import net.palaya.chessanalyzer.ui.model.PreparedNarrationLedger
import org.json.JSONArray
import org.json.JSONObject

/**
 * [PreparedNarrationLedger] on disk: which narration recordings were run for which voice identity, and which segments
 * fell back to the device voice. `filesDir/review/prepared_narration.json` (excluded from backup with `review/`: the
 * recordings it describes are not backed up either). One small JSON file, temp file then rename; an unreadable file is
 * an empty ledger (the worst case is one more recording attempt).
 */
class PreparedNarrationStore(filesDir: File) {

    private val file = File(filesDir, "${GameRepository.REVIEW_DIR_NAME}/$FILE_NAME")
    private val tmp = File(filesDir, "${GameRepository.REVIEW_DIR_NAME}/$FILE_NAME.tmp")

    @Synchronized
    fun load(): PreparedNarrationLedger {
        if (!file.isFile) return PreparedNarrationLedger()
        return try {
            val array = JSONObject(file.readText()).getJSONArray("identities")
            PreparedNarrationLedger(
                (0 until array.length()).map { i ->
                    val o = array.getJSONObject(i)
                    PreparedNarrationLedger.Identity(o.getString("id"), o.getJSONArray("scripts").strings(), o.getJSONArray("fallback").strings())
                },
            )
        } catch (e: Exception) {
            file.delete()
            PreparedNarrationLedger()
        }
    }

    @Synchronized
    fun save(ledger: PreparedNarrationLedger) {
        file.parentFile?.mkdirs()
        val identities = JSONArray()
        ledger.identities.forEach { identity ->
            identities.put(
                JSONObject()
                    .put("id", identity.id)
                    .put("scripts", JSONArray(identity.scripts))
                    .put("fallback", JSONArray(identity.fallback)),
            )
        }
        tmp.writeText(JSONObject().put("identities", identities).toString())
        file.delete()
        tmp.renameTo(file)
    }

    /** Applies [change] to the stored ledger and saves the result. */
    @Synchronized
    fun update(change: (PreparedNarrationLedger) -> PreparedNarrationLedger) = save(change(load()))

    /** The user cleared the narration audio (Settings): nothing is recorded any more, so nothing is "prepared". */
    @Synchronized
    fun clear() {
        file.delete()
        tmp.delete()
    }

    private fun JSONArray.strings(): List<String> = (0 until length()).map { getString(it) }

    companion object {
        const val FILE_NAME = "prepared_narration.json"
    }
}
