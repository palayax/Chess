package net.palaya.chessanalyzer.data

import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.palaya.chessanalyzer.core.analysis.EngineLineInput
import net.palaya.chessanalyzer.core.analysis.PositionEval
import net.palaya.chessanalyzer.ui.model.RecentGameSummary
import org.json.JSONArray
import org.json.JSONObject

/**
 * File-backed persistence for imported games and their finished analysis — deliberately not
 * Room (per the integration brief): one small JSON document per game under `filesDir/games/`,
 * plus one JSON document per *engine evaluation* under `filesDir/eval_cache/` keyed by a hash
 * of (PGN text, depth, MultiPV).
 *
 * The evaluation cache — not a cache of the derived [net.palaya.chessanalyzer.core.analysis.GameReport]
 * — is what actually makes re-opening a game instant: [PositionEval] is expensive to produce
 * (dozens of Stockfish searches) but cheap to serialize, while [net.palaya.chessanalyzer.core.analysis.GameReport]
 * is cheap to re-derive from cached evals via [net.palaya.chessanalyzer.core.analysis.GameAnalyzer]
 * (pure, synchronous, no I/O) but has a much larger/more nested shape to hand-serialize. Caching
 * the cheaper-to-serialize, more-expensive-to-recompute artifact is the better trade.
 */
class GameRepository(filesDir: File) {

    private val gamesDir = File(filesDir, "games").apply { mkdirs() }
    private val evalCacheDir = File(filesDir, "eval_cache").apply { mkdirs() }

    data class StoredGame(
        val id: String,
        val pgnText: String,
        val white: String,
        val black: String,
        val result: String,
        val date: String,
        val plyCount: Int,
        val depth: Int,
        val multiPv: Int,
        val userColorName: String?,
        val savedAtMs: Long = System.currentTimeMillis(),
    )

    // -----------------------------------------------------------------
    // Imported-game index (drives the Import screen's "recent games")
    // -----------------------------------------------------------------

    suspend fun save(game: StoredGame) = withContext(Dispatchers.IO) {
        val json = JSONObject().apply {
            put("id", game.id)
            put("pgnText", game.pgnText)
            put("white", game.white)
            put("black", game.black)
            put("result", game.result)
            put("date", game.date)
            put("plyCount", game.plyCount)
            put("depth", game.depth)
            put("multiPv", game.multiPv)
            put("userColorName", game.userColorName ?: JSONObject.NULL)
            put("savedAtMs", game.savedAtMs)
        }
        File(gamesDir, "${game.id}.json").writeText(json.toString())
    }

    suspend fun load(id: String): StoredGame? = withContext(Dispatchers.IO) {
        val file = File(gamesDir, "$id.json")
        if (!file.exists()) return@withContext null
        parseStoredGame(file)
    }

    suspend fun listRecent(limit: Int = 20): List<RecentGameSummary> = withContext(Dispatchers.IO) {
        gamesDir.listFiles { f -> f.extension == "json" }
            ?.sortedByDescending { it.lastModified() }
            ?.take(limit)
            ?.mapNotNull { file ->
                parseStoredGame(file)?.let { g ->
                    RecentGameSummary(
                        id = g.id,
                        white = g.white,
                        black = g.black,
                        result = g.result,
                        date = g.date,
                        plyCount = g.plyCount,
                    )
                }
            }
            ?: emptyList()
    }

    private fun parseStoredGame(file: File): StoredGame? = try {
        val json = JSONObject(file.readText())
        StoredGame(
            id = json.getString("id"),
            pgnText = json.getString("pgnText"),
            white = json.optString("white", "White"),
            black = json.optString("black", "Black"),
            result = json.optString("result", "*"),
            date = json.optString("date", ""),
            plyCount = json.optInt("plyCount", 0),
            depth = json.optInt("depth", SettingsRepository.DEFAULT_DEPTH),
            multiPv = json.optInt("multiPv", SettingsRepository.DEFAULT_MULTI_PV),
            userColorName = json.optString("userColorName", null).takeUnless { it.isNullOrEmpty() },
            savedAtMs = json.optLong("savedAtMs", file.lastModified()),
        )
    } catch (e: Exception) {
        null
    }

    // -----------------------------------------------------------------
    // Engine-evaluation cache
    // -----------------------------------------------------------------

    /** Cache key over the inputs that actually change the analysis: game text + depth + MultiPV. */
    fun cacheKey(pgnText: String, depth: Int, multiPv: Int): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(pgnText.toByteArray(Charsets.UTF_8))
        digest.update(":$depth:$multiPv".toByteArray(Charsets.UTF_8))
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    suspend fun loadEvalCache(key: String): List<PositionEval>? = withContext(Dispatchers.IO) {
        val file = File(evalCacheDir, "$key.json")
        if (!file.exists()) return@withContext null
        try {
            val array = JSONArray(file.readText())
            (0 until array.length()).map { i -> parsePositionEval(array.getJSONObject(i)) }
        } catch (e: Exception) {
            null
        }
    }

    suspend fun saveEvalCache(key: String, evals: List<PositionEval>) = withContext(Dispatchers.IO) {
        val array = JSONArray()
        evals.forEach { array.put(positionEvalToJson(it)) }
        File(evalCacheDir, "$key.json").writeText(array.toString())
    }

    // ---- Partial (resumable) eval cache -------------------------------------------------------
    // A full-game analysis is minutes of engine work. Without a partial cache, cancelling — or
    // backgrounding the app hard enough to be killed — threw away every ply already computed and
    // the user started from zero. These store a *prefix* of the eval list so a later run can pick
    // up where it stopped. The key already encodes the PGN, depth and MultiPV, so a prefix under a
    // matching key was computed under identical settings; the caller still re-checks FEN alignment.

    private fun partialFile(key: String) = File(evalCacheDir, "$key.partial.json")

    suspend fun loadPartialEvalCache(key: String): List<PositionEval>? = withContext(Dispatchers.IO) {
        val file = partialFile(key)
        if (!file.exists()) return@withContext null
        try {
            val array = JSONArray(file.readText())
            (0 until array.length()).map { i -> parsePositionEval(array.getJSONObject(i)) }
        } catch (e: Exception) {
            // A partial file truncated by a process kill mid-write is expected, not exceptional —
            // drop it and start over rather than failing the analysis.
            file.delete()
            null
        }
    }

    suspend fun savePartialEvalCache(key: String, evals: List<PositionEval>) = withContext(Dispatchers.IO) {
        val array = JSONArray()
        evals.forEach { array.put(positionEvalToJson(it)) }
        // Write to a temp file then rename, so a kill mid-write cannot leave a half-written file
        // that looks complete.
        val tmp = File(evalCacheDir, "$key.partial.tmp")
        tmp.writeText(array.toString())
        val target = partialFile(key)
        target.delete()
        tmp.renameTo(target)
        Unit
    }

    suspend fun clearPartialEvalCache(key: String) = withContext(Dispatchers.IO) {
        partialFile(key).delete()
        File(evalCacheDir, "$key.partial.tmp").delete()
        Unit
    }

    private fun positionEvalToJson(eval: PositionEval): JSONObject = JSONObject().apply {
        put("fen", eval.fen)
        put("depth", eval.depth)
        val lines = JSONArray()
        eval.lines.forEach { line ->
            lines.put(
                JSONObject().apply {
                    put("multiPv", line.multiPv)
                    put("scoreCp", line.scoreCp?.let { it as Any } ?: JSONObject.NULL)
                    put("mateIn", line.mateIn?.let { it as Any } ?: JSONObject.NULL)
                    put("depth", line.depth)
                    put("pvUci", JSONArray(line.pvUci))
                }
            )
        }
        put("lines", lines)
    }

    private fun parsePositionEval(json: JSONObject): PositionEval {
        val linesJson = json.getJSONArray("lines")
        val lines = (0 until linesJson.length()).map { i ->
            val l = linesJson.getJSONObject(i)
            EngineLineInput(
                multiPv = l.getInt("multiPv"),
                scoreCp = if (l.isNull("scoreCp")) null else l.getInt("scoreCp"),
                mateIn = if (l.isNull("mateIn")) null else l.getInt("mateIn"),
                depth = l.getInt("depth"),
                pvUci = (0 until l.getJSONArray("pvUci").length()).map { j -> l.getJSONArray("pvUci").getString(j) },
            )
        }
        return PositionEval(fen = json.getString("fen"), lines = lines, depth = json.getInt("depth"))
    }
}
