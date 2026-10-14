package net.palaya.chessanalyzer.data

import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.palaya.chessanalyzer.core.analysis.EngineLineInput
import net.palaya.chessanalyzer.core.analysis.PositionEval
import net.palaya.chessanalyzer.engine.NetStore
import net.palaya.chessanalyzer.core.narration.VideoPace
import net.palaya.chessanalyzer.ui.model.AnalysisStrength
import net.palaya.chessanalyzer.ui.model.NarrationProviderChoice
import net.palaya.chessanalyzer.ui.model.RecentGameSummary
import net.palaya.chessanalyzer.ui.model.ReviewOptions
import net.palaya.chessanalyzer.ui.model.SideChoice
import net.palaya.chessanalyzer.ui.model.StoredReviewOptions
import net.palaya.chessanalyzer.video.KokoroVoices
import org.json.JSONArray
import org.json.JSONObject

/**
 * File-backed persistence for imported games and their finished analysis — deliberately not
 * Room (per the integration brief): one small JSON document per game under `filesDir/games/`,
 * plus one JSON document per *engine evaluation* under `filesDir/eval_cache/<net 12-hex>/` keyed by a
 * hash of (PGN text, depth, MultiPV, search budget). Since D2e each net has its own folder
 * ([EvalCacheLayout]): [netPrefix] names the active net's, and a net update purges the others.
 *
 * The evaluation cache — not a cache of the derived [net.palaya.chessanalyzer.core.analysis.GameReport]
 * — is what actually makes re-opening a game instant: [PositionEval] is expensive to produce
 * (dozens of Stockfish searches) but cheap to serialize, while [net.palaya.chessanalyzer.core.analysis.GameReport]
 * is cheap to re-derive from cached evals via [net.palaya.chessanalyzer.core.analysis.GameAnalyzer]
 * (pure, synchronous, no I/O) but has a much larger/more nested shape to hand-serialize. Caching
 * the cheaper-to-serialize, more-expensive-to-recompute artifact is the better trade.
 */
class GameRepository(
    filesDir: File,
    /**
     * The 12 hex digits of the active net's name (`NetStore.activeIdentity()`), read at every cache call.
     * The default is the compiled net's, for tests that make a repository on their own.
     */
    private val netPrefix: () -> String = { NetStore.prefixOf(NetStore.NET_FILENAME)!! },
) {

    private val gamesDir = File(filesDir, "games").apply { mkdirs() }

    /** `filesDir/eval_cache/` with one folder per net. */
    val evalCache = EvalCacheLayout(File(filesDir, EvalCacheLayout.DIR_NAME).apply { mkdirs() })

    /** The active net's prefix now: the eval-cache folder an analysis started now reads and writes. */
    fun currentNetPrefix(): String = netPrefix()

    private fun evalCacheDir(prefix: String): File = evalCache.dirFor(prefix).apply { mkdirs() }

    /** First launch of a D2e build: the flat cache moves into the active net's folder. */
    fun migrateFlatEvalCache(): EvalCacheLayout.Result = evalCache.migrateFlat(netPrefix())

    /** After a net update commits: other nets' folders go (design §4.2). */
    fun purgeEvalCachesExcept(keepNetPrefix: String): EvalCacheLayout.Result = evalCache.purgeExcept(keepNetPrefix)

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
        // ---- The game's review options (Review options screen, RF). Null = never chosen for this game (a game saved
        // before RF): every reader then falls back to the Settings default. Absent keys read as null. ----
        /** `NarrationProviderChoice` name. */
        val voiceProvider: String? = null,
        /** Kokoro speaker sid. */
        val speakerId: Int? = null,
        /** `VideoPace` name. */
        val pace: String? = null,
        val naturalWording: Boolean? = null,
        val prepareVideo: Boolean? = null,
        /**
         * Analysed with Auto-depth (ANALYSIS_SPEC §8.5; [depth] is then its first pass's depth), so reopening the
         * game keeps Auto. Absent in games saved before Auto: false.
         */
        val autoDepth: Boolean = false,
    ) {
        /** The options stored with this game, for [net.palaya.chessanalyzer.ui.model.prefillReviewOptions]. */
        fun reviewOptions(): StoredReviewOptions = StoredReviewOptions(
            depth = depth,
            autoDepth = autoDepth,
            side = SideChoice.fromStored(userColorName),
            naturalWording = naturalWording,
            prepareVideo = prepareVideo,
            voice = NarrationProviderChoice.fromPersistedOrNull(voiceProvider),
            speakerId = speakerId?.takeIf { KokoroVoices.isKnown(it) },
            pace = VideoPace.fromPersistedOrNull(pace),
        )

        /** This game with [options] stored (the strength goes to [depth], the side to [userColorName]). */
        fun withReviewOptions(options: ReviewOptions): StoredGame = copy(
            depth = options.level.code,
            autoDepth = false,
            userColorName = options.side.storedName ?: userColorName,
            voiceProvider = options.voice.name,
            speakerId = options.speakerId,
            pace = options.pace.name,
            naturalWording = options.naturalWording,
            prepareVideo = options.prepareVideo,
        )
    }

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
            game.voiceProvider?.let { put("voiceProvider", it) }
            game.speakerId?.let { put("speakerId", it) }
            game.pace?.let { put("pace", it) }
            game.naturalWording?.let { put("naturalWording", it) }
            game.prepareVideo?.let { put("prepareVideo", it) }
            put("autoDepth", game.autoDepth)
        }
        File(gamesDir, "${game.id}.json").writeText(json.toString())
    }

    /**
     * Deletes the game [id] (Home's library menu, RF): its JSON and its pending review options. Returns false when it
     * was not there. The caller decides about the eval cache ([deleteEvalCacheFor], `deletePlan`) and never deletes the
     * narration audio (shared across games, keyed by sentence; Settings clears it).
     */
    suspend fun delete(id: String): Boolean = withContext(Dispatchers.IO) {
        clearPendingOptionsBlocking(id)
        val file = File(gamesDir, "$id.json")
        file.exists() && file.delete()
    }

    /** The texts of every stored game except [exceptId] (two imports of the same game share their eval cache). */
    suspend fun storedPgnTexts(exceptId: String? = null): List<String> = withContext(Dispatchers.IO) {
        gamesDir.listFiles { f -> f.extension == "json" }.orEmpty()
            .mapNotNull { parseStoredGame(it) }
            .filter { it.id != exceptId }
            .map { it.pgnText }
    }

    /**
     * Deletes the eval-cache files (complete, partial and its temp) under [keys] in every net's folder. Returns how
     * many files went.
     */
    suspend fun deleteEvalCacheFor(keys: Collection<String>): Int = withContext(Dispatchers.IO) {
        var deleted = 0
        for (folder in evalCache.root.listFiles { f -> f.isDirectory }.orEmpty()) {
            for (key in keys) {
                for (name in listOf("$key.json", "$key.partial.json", "$key.partial.tmp")) {
                    if (File(folder, name).delete()) deleted++
                }
            }
        }
        deleted
    }

    /** True when the complete evaluations under [key] are in the active net's folder (a run at that strength is instant). */
    fun hasEvalCache(key: String): Boolean = File(evalCacheDir(netPrefix()), "$key.json").isFile

    // ---- Review options of a game not analysed yet (RF) --------------------------------------------------------------
    // The options screen runs before the engine, and a game is stored only once its analysis succeeds. Until then its
    // options are kept here, so a process killed during the run (F1's resume) still runs the stages the user chose.
    // `filesDir/review/pending/<id>.json`, device-specific like the pending request (excluded from backup).

    private val pendingOptionsDir = File(filesDir, "$REVIEW_DIR_NAME/pending")

    suspend fun savePendingOptions(id: String, options: ReviewOptions) = withContext(Dispatchers.IO) {
        pendingOptionsDir.mkdirs()
        val json = JSONObject().apply {
            put("depth", options.level.code)
            options.side.storedName?.let { put("side", it) }
            put("naturalWording", options.naturalWording)
            put("prepareVideo", options.prepareVideo)
            put("voiceProvider", options.voice.name)
            put("speakerId", options.speakerId)
            put("pace", options.pace.name)
        }
        val tmp = File(pendingOptionsDir, "$id.json.tmp")
        tmp.writeText(json.toString())
        val target = File(pendingOptionsDir, "$id.json")
        target.delete()
        tmp.renameTo(target)
        Unit
    }

    /** The options saved by [savePendingOptions] for [id], or null (none, or unreadable: then it is deleted). */
    suspend fun loadPendingOptions(id: String): StoredReviewOptions? = withContext(Dispatchers.IO) {
        val file = File(pendingOptionsDir, "$id.json")
        if (!file.isFile) return@withContext null
        try {
            val json = JSONObject(file.readText())
            val strengthDepth = AnalysisStrength.entries.firstOrNull { it.name == json.optString("strength", "") }?.depth
            StoredReviewOptions(
                depth = strengthDepth ?: if (json.has("depth")) json.getInt("depth") else null,
                side = SideChoice.fromStored(json.optString("side", "")),
                naturalWording = if (json.has("naturalWording")) json.getBoolean("naturalWording") else null,
                prepareVideo = if (json.has("prepareVideo")) json.getBoolean("prepareVideo") else null,
                voice = NarrationProviderChoice.fromPersistedOrNull(json.optString("voiceProvider", "")),
                speakerId = if (json.has("speakerId")) json.getInt("speakerId").takeIf { KokoroVoices.isKnown(it) } else null,
                pace = VideoPace.fromPersistedOrNull(json.optString("pace", "")),
            )
        } catch (e: Exception) {
            file.delete()
            null
        }
    }

    suspend fun clearPendingOptions(id: String) = withContext(Dispatchers.IO) { clearPendingOptionsBlocking(id) }

    private fun clearPendingOptionsBlocking(id: String) {
        File(pendingOptionsDir, "$id.json").delete()
        File(pendingOptionsDir, "$id.json.tmp").delete()
    }

    suspend fun load(id: String): StoredGame? = withContext(Dispatchers.IO) {
        val file = File(gamesDir, "$id.json")
        if (!file.exists()) return@withContext null
        parseStoredGame(file)
    }

    /** The library, newest first; every game by default (RF: no longer only the last 20). */
    suspend fun listRecent(limit: Int = Int.MAX_VALUE): List<RecentGameSummary> = withContext(Dispatchers.IO) {
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
            depth = json.optInt("depth", SettingsRepository.LEGACY_DEFAULT_DEPTH),
            multiPv = json.optInt("multiPv", SettingsRepository.DEFAULT_MULTI_PV),
            userColorName = json.optString("userColorName", null).takeUnless { it.isNullOrEmpty() },
            savedAtMs = json.optLong("savedAtMs", file.lastModified()),
            voiceProvider = json.optString("voiceProvider", "").takeUnless { it.isEmpty() },
            speakerId = if (json.has("speakerId")) json.getInt("speakerId") else null,
            pace = json.optString("pace", "").takeUnless { it.isEmpty() },
            naturalWording = if (json.has("naturalWording")) json.getBoolean("naturalWording") else null,
            prepareVideo = if (json.has("prepareVideo")) json.getBoolean("prepareVideo") else null,
            autoDepth = json.optBoolean("autoDepth", false),
        )
    } catch (e: Exception) {
        null
    }

    // -----------------------------------------------------------------
    // Engine-evaluation cache
    // -----------------------------------------------------------------

    /**
     * Cache key over the inputs that actually change the analysis: game text + depth + MultiPV +
     * the per-position search budget ([budget], e.g. `SearchBudget.cacheKeyPart`; F1). With the
     * budget in the key a result is reused only under identical limits, so results from before F1
     * (no budget) are not reused, and a changed budget re-analyses rather than mixing limits.
     * [budget] null gives the pre-F1 key.
     */
    fun cacheKey(pgnText: String, depth: Int, multiPv: Int, budget: String? = null): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(pgnText.toByteArray(Charsets.UTF_8))
        digest.update(":$depth:$multiPv".toByteArray(Charsets.UTF_8))
        if (budget != null) digest.update(":$budget".toByteArray(Charsets.UTF_8))
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    suspend fun loadEvalCache(key: String, prefix: String = netPrefix()): List<PositionEval>? = withContext(Dispatchers.IO) {
        val file = File(evalCacheDir(prefix), "$key.json")
        if (!file.exists()) return@withContext null
        try {
            val array = JSONArray(file.readText())
            (0 until array.length()).map { i -> parsePositionEval(array.getJSONObject(i)) }
        } catch (e: Exception) {
            null
        }
    }

    suspend fun saveEvalCache(key: String, evals: List<PositionEval>, prefix: String = netPrefix()) = withContext(Dispatchers.IO) {
        val array = JSONArray()
        evals.forEach { array.put(positionEvalToJson(it)) }
        File(evalCacheDir(prefix), "$key.json").writeText(array.toString())
    }

    // ---- Partial (resumable) eval cache -------------------------------------------------------
    // A full-game analysis is minutes of engine work. Without a partial cache, cancelling — or
    // backgrounding the app hard enough to be killed — threw away every ply already computed and
    // the user started from zero. These store a *prefix* of the eval list so a later run can pick
    // up where it stopped. The key already encodes the PGN, depth and MultiPV, so a prefix under a
    // matching key was computed under identical settings; the caller still re-checks FEN alignment.

    private fun partialFile(key: String, prefix: String) = File(evalCacheDir(prefix), "$key.partial.json")

    suspend fun loadPartialEvalCache(key: String, prefix: String = netPrefix()): List<PositionEval>? = withContext(Dispatchers.IO) {
        val file = partialFile(key, prefix)
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

    suspend fun savePartialEvalCache(key: String, evals: List<PositionEval>, prefix: String = netPrefix()) = withContext(Dispatchers.IO) {
        val array = JSONArray()
        evals.forEach { array.put(positionEvalToJson(it)) }
        // Write to a temp file then rename, so a kill mid-write cannot leave a half-written file
        // that looks complete.
        val tmp = File(evalCacheDir(prefix), "$key.partial.tmp")
        tmp.writeText(array.toString())
        val target = partialFile(key, prefix)
        target.delete()
        tmp.renameTo(target)
        Unit
    }

    suspend fun clearPartialEvalCache(key: String, prefix: String = netPrefix()) = withContext(Dispatchers.IO) {
        partialFile(key, prefix).delete()
        File(evalCacheDir(prefix), "$key.partial.tmp").delete()
        Unit
    }

    private fun positionEvalToJson(eval: PositionEval): JSONObject = JSONObject().apply {
        put("fen", eval.fen)
        put("depth", eval.depth)
        eval.requestedDepth?.let { put("requestedDepth", it) }
        if (eval.timeCapped) put("timeCapped", true)
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
        return PositionEval(
            fen = json.getString("fen"),
            lines = lines,
            depth = json.getInt("depth"),
            requestedDepth = if (json.has("requestedDepth")) json.getInt("requestedDepth") else null,
            timeCapped = json.optBoolean("timeCapped", false),
        )
    }

    companion object {
        /** `filesDir/review/`: the review run's device-specific files (pending options, measured speeds). Not backed up. */
        const val REVIEW_DIR_NAME = "review"
    }
}
