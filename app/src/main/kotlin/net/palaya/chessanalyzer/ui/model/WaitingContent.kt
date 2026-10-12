package net.palaya.chessanalyzer.ui.model

import net.palaya.chessanalyzer.core.chess.Color
import net.palaya.chessanalyzer.core.chess.Position
import net.palaya.chessanalyzer.core.pgn.PgnParser
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/*
 * The content of the "While you wait" panel (docs/WAITING_CONTENT.md): fun facts, player profiles, puzzles and a
 * card per famous game. Pure Kotlin, no Android types: the JSON assets under `assets/waiting/` are read with
 * org.json (as the rest of the app does), and WaitingContentTest parses the real files on the host.
 *
 * Nothing here is downloaded: facts, players and puzzles are shipped in the APK, and the game cards are drawn from
 * the famous-games library the app already has (FamousGames.kt), so no game data is duplicated.
 */

/** Asset names, shared by the loader and the tests. */
object WaitingAssets {
    const val FACTS = "waiting/facts.json"
    const val PLAYERS = "waiting/players.json"
    const val PUZZLES = "waiting/puzzles.json"
}

/** A short, verified fact about chess. The source of each one is listed in docs/WAITING_CONTENT.md, not in the app. */
data class WaitingFact(val id: String, val topic: String, val text: String)

/** A short profile. [died] is null for a living player; [role] is e.g. "World champion 1886-1894" or null. */
data class WaitingPlayer(
    val id: String,
    val name: String,
    val born: Int,
    val died: Int?,
    val country: String,
    val role: String?,
    val text: String,
) {
    /** "1836-1900" or "born 1963" (with the en dash for the range). */
    val years: String get() = if (died != null) "$born–$died" else "b. $born"
}

/** What a puzzle asks for: a forced mate in [moves], or simply the best move. */
sealed interface WaitingPuzzleGoal {
    data class MateIn(val moves: Int) : WaitingPuzzleGoal
    data object BestMove : WaitingPuzzleGoal
}

/**
 * One "find the best move" position taken from a move PLAYED in a famous game ([gameId]).
 *
 * @property fen the position before the move; the side to move is the solver.
 * @property prevUci the opponent's previous move, drawn as the last move (null at the first ply).
 * @property solution UCI moves in order: the solver's move, the opponent's forced or near-forced reply, the
 *   solver's next move and so on. Always odd in length (it ends on a solver move). The first move is the one
 *   played in the game.
 * @property san the same moves in SAN, played from [fen].
 * @property bestCp / [secondCp] the engine's scores for the solver's best move and for the next-best move at
 *   [depth] (centipawns from the solver's point of view); [bestCp] is null for a mate puzzle and [secondCp] is
 *   null when the second line is a mate against the solver or does not exist.
 */
data class WaitingPuzzle(
    val id: String,
    val gameId: String,
    val ply: Int,
    val fen: String,
    val prevUci: String?,
    val goal: WaitingPuzzleGoal,
    val solution: List<String>,
    val san: List<String>,
    val bestCp: Int?,
    val secondCp: Int?,
    val depth: Int,
    /** The famous game's own title (its index row), for "Kasparov vs Topalov, Wijk aan Zee 1999". */
    val gameTitle: String,
    val gameYear: Int,
) {
    val sideToMove: Color get() = if (fen.split(' ').getOrNull(1) == "b") Color.BLACK else Color.WHITE

    /** The full-move number of the first solver move (from the FEN's last field). */
    val moveNumber: Int get() = fen.split(' ').getOrNull(5)?.toIntOrNull() ?: ((ply + 1) / 2)

    /** How many moves the solver has to find. */
    val solverMoves: Int get() = (solution.size + 1) / 2
}

/** One famous game as a card: the index row plus the board it ends on. [event] is the PGN's Event tag. */
data class WaitingGame(
    val game: FamousGame,
    val event: String?,
    val finalFen: String,
    val plyCount: Int,
)

/** Everything the panel can show besides the live insights. */
data class WaitingContent(
    val facts: List<WaitingFact>,
    val players: List<WaitingPlayer>,
    val games: List<WaitingGame>,
    val puzzles: List<WaitingPuzzle>,
) {
    val isEmpty: Boolean get() = facts.isEmpty() && players.isEmpty() && games.isEmpty() && puzzles.isEmpty()

    companion object {
        val EMPTY = WaitingContent(emptyList(), emptyList(), emptyList(), emptyList())
    }
}

/** A malformed asset: the host tests make that a build failure. */
class WaitingContentException(message: String) : Exception(message)

private fun JSONObject.str(key: String, where: String): String =
    optString(key, "").takeIf { it.isNotBlank() } ?: throw WaitingContentException("$where: missing '$key'")

private fun JSONObject.nullableStr(key: String): String? =
    if (isNull(key)) null else optString(key, "").takeIf { it.isNotBlank() }

private fun JSONObject.int(key: String, where: String): Int =
    if (has(key) && !isNull(key)) getInt(key) else throw WaitingContentException("$where: missing '$key'")

private fun JSONObject.nullableInt(key: String): Int? = if (!has(key) || isNull(key)) null else getInt(key)

private fun JSONArray.strings(where: String): List<String> =
    (0 until length()).map { i -> getString(i).also { if (it.isBlank()) throw WaitingContentException("$where: empty entry") } }

private inline fun <T> guard(what: String, block: () -> T): T = try {
    block()
} catch (e: JSONException) {
    throw WaitingContentException("$what: ${e.message}")
}

fun parseWaitingFacts(json: String): List<WaitingFact> = guard("facts") {
    val arr = JSONObject(json).getJSONArray("facts")
    val ids = HashSet<String>()
    (0 until arr.length()).map { i ->
        val o = arr.getJSONObject(i)
        val where = "facts[$i]"
        val id = o.str("id", where)
        if (!ids.add(id)) throw WaitingContentException("$where: duplicate id '$id'")
        WaitingFact(id, o.str("topic", where), o.str("text", where))
    }
}

fun parseWaitingPlayers(json: String): List<WaitingPlayer> = guard("players") {
    val arr = JSONObject(json).getJSONArray("players")
    val ids = HashSet<String>()
    (0 until arr.length()).map { i ->
        val o = arr.getJSONObject(i)
        val where = "players[$i]"
        val id = o.str("id", where)
        if (!ids.add(id)) throw WaitingContentException("$where: duplicate id '$id'")
        WaitingPlayer(
            id = id,
            name = o.str("name", where),
            born = o.int("born", where),
            died = o.nullableInt("died"),
            country = o.str("country", where),
            role = o.nullableStr("role"),
            text = o.str("text", where),
        )
    }
}

/** Puzzles, each joined with its famous game (title, year) from [games]. An unknown game id is an error. */
fun parseWaitingPuzzles(json: String, games: List<FamousGame>): List<WaitingPuzzle> = guard("puzzles") {
    val byId = games.associateBy { it.id }
    val arr = JSONObject(json).getJSONArray("puzzles")
    val ids = HashSet<String>()
    (0 until arr.length()).map { i ->
        val o = arr.getJSONObject(i)
        val where = "puzzles[$i]"
        val id = o.str("id", where)
        if (!ids.add(id)) throw WaitingContentException("$where: duplicate id '$id'")
        val gameId = o.str("game", where)
        val game = byId[gameId] ?: throw WaitingContentException("$where: unknown game '$gameId'")
        val goalObj = o.getJSONObject("goal")
        val goal = when (goalObj.str("type", "$where.goal")) {
            "mate" -> WaitingPuzzleGoal.MateIn(goalObj.int("moves", "$where.goal"))
            "best" -> WaitingPuzzleGoal.BestMove
            else -> throw WaitingContentException("$where: unknown goal type")
        }
        val solution = o.getJSONArray("solution").strings("$where.solution")
        val san = o.getJSONArray("san").strings("$where.san")
        if (solution.size % 2 == 0 || solution.size != san.size) {
            throw WaitingContentException("$where: a solution has an odd number of moves, and as many SAN moves")
        }
        WaitingPuzzle(
            id = id,
            gameId = gameId,
            ply = o.int("ply", where),
            fen = o.str("fen", where),
            prevUci = o.nullableStr("prev"),
            goal = goal,
            solution = solution,
            san = san,
            bestCp = o.nullableInt("bestCp"),
            secondCp = o.nullableInt("secondCp"),
            depth = o.int("depth", where),
            gameTitle = game.title,
            gameYear = game.year,
        )
    }
}

/**
 * The game cards from the famous-games library. The final position comes from replaying the game's own PGN with
 * `:core`; a game whose text does not parse is left out rather than failing the whole panel.
 */
fun waitingGamesFrom(library: FamousGamesLibrary): List<WaitingGame> = library.games.mapNotNull { g ->
    val text = library.pgnFor(g.id) ?: return@mapNotNull null
    try {
        val parsed = PgnParser.parse(text).firstOrNull() ?: return@mapNotNull null
        val last = parsed.moves.lastOrNull() ?: return@mapNotNull null
        WaitingGame(g, parsed.tags["Event"]?.takeIf { it.isNotBlank() }, last.positionFenAfter, parsed.moves.size)
    } catch (e: Exception) {
        null
    }
}

/** Builds the whole content from the four assets' texts. */
fun parseWaitingContent(factsJson: String, playersJson: String, puzzlesJson: String, library: FamousGamesLibrary): WaitingContent =
    WaitingContent(
        facts = parseWaitingFacts(factsJson),
        players = parseWaitingPlayers(playersJson),
        games = waitingGamesFrom(library),
        puzzles = parseWaitingPuzzles(puzzlesJson, library.games),
    )

/** True when [fen] describes a legal setup the board can show; used by the tests and to skip a broken puzzle. */
fun isUsableFen(fen: String): Boolean = try {
    Position.fromFen(fen)
    true
} catch (e: Exception) {
    false
}
