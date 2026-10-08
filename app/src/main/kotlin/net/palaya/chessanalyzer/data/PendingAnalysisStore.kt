package net.palaya.chessanalyzer.data

import java.io.File
import org.json.JSONObject

/**
 * The analysis request in flight, on disk (F1).
 *
 * Without it a Deep analysis that Android killed in the background came back as "The game text was
 * lost": the PGN lived only in the ViewModel's memory, and the restored Analysing screen had nothing
 * to run. Now the request (game text, the settings it started with, and the name used to find the
 * user's side) is written when the analysis starts and deleted on success or on the user's own
 * Cancel/Back; a new process finds it and resumes from the partial eval cache.
 *
 * The same class, with [WAITING_FOR_SETUP_FILE_NAME], keeps a game that arrived before setup (D2c).
 *
 * One request at a time (there is one engine). App-private (`filesDir`), excluded from backup: a
 * restored request would start an analysis on another phone.
 */
class PendingAnalysisStore(filesDir: File, fileName: String = FILE_NAME) {

    data class Request(
        val gameId: String,
        val pgnText: String,
        /** The settings the analysis started with: a resume must use the same ones (same cache key). */
        val depth: Int,
        val multiPv: Int,
        /** The name used to detect the user's side ("Your name" in Settings when it started). */
        val username: String,
        val startedAtMs: Long,
        /**
         * The side the game opens with when the user has not answered "Which side were you?" yet, in the
         * form `GameRepository.StoredGame.userColorName` holds (`SideChoice.storedName`): `NOT_ME` for a
         * game from the Famous games library (G1-device). Null (a share, a paste, a file): the username
         * detection decides, as before.
         */
        val initialSide: String? = null,
    )

    private val file = File(filesDir, fileName)
    private val tmp = File(filesDir, "$fileName.tmp")

    @Synchronized
    fun save(request: Request) {
        val json = JSONObject().apply {
            put("gameId", request.gameId)
            put("pgnText", request.pgnText)
            put("depth", request.depth)
            put("multiPv", request.multiPv)
            put("username", request.username)
            put("startedAtMs", request.startedAtMs)
            request.initialSide?.let { put("initialSide", it) }
        }
        // Temp file then rename: a kill mid-write must not leave a half-written request.
        tmp.writeText(json.toString())
        file.delete()
        tmp.renameTo(file)
    }

    /** The stored request, or null when there is none or it cannot be read (then it is deleted). */
    @Synchronized
    fun load(): Request? {
        if (!file.isFile) return null
        return try {
            val json = JSONObject(file.readText())
            Request(
                gameId = json.getString("gameId"),
                pgnText = json.getString("pgnText"),
                depth = json.getInt("depth"),
                multiPv = json.getInt("multiPv"),
                username = json.optString("username", ""),
                startedAtMs = json.optLong("startedAtMs", 0L),
                // Absent in files written before G1-device: no initial side, as then.
                initialSide = if (json.isNull("initialSide")) null else json.getString("initialSide"),
            )
        } catch (e: Exception) {
            file.delete()
            null
        }
    }

    /** Deletes the stored request if it is for [gameId] (or any request when [gameId] is null). */
    @Synchronized
    fun clear(gameId: String? = null) {
        if (gameId != null && load()?.gameId != gameId) return
        file.delete()
        tmp.delete()
    }

    companion object {
        const val FILE_NAME = "pending_analysis.json"

        /**
         * D2c: a game shared (or reopened) before the engine net was installed. Same format as the
         * in-flight request, a second file: it is analysed once the net is in (from the Setup screen, from
         * Home, or at the next launch), not lost when the user taps "Not now". Excluded from backup too.
         */
        const val WAITING_FOR_SETUP_FILE_NAME = "setup_waiting_game.json"

        /**
         * A request older than this is dropped instead of resumed when the app starts at Home: the
         * user has long moved on, and an analysis starting by itself days later would be a surprise.
         * A restored Analysing screen resumes regardless of age (the user is looking at it).
         */
        const val AUTO_RESUME_MAX_AGE_MS = 24L * 60 * 60 * 1000
    }
}
