package net.palaya.chessanalyzer.ui.model

import java.text.Normalizer
import java.util.Locale

/*
 * The built-in library of famous games (G1, docs/FAMOUS_GAMES.md). Pure Kotlin, no Android types: reading the
 * index, splitting the PGN asset into games, the search and the grouping by era are host-tested in
 * FamousGamesLogicTest, and FamousGamesAssetTest replays every game of the real assets.
 *
 * Two assets, kept in step by the tests and by scripts/verify_famous_games.py:
 * - `famous_games.pgn`: every game, bare moves plus factual tags (Event, Site, Date, Round, White, Black,
 *   Result, ECO when known), in the same order as the index;
 * - `famous_games_index.tsv`: one row per game, `id, era, title, white, black, year, result, description`.
 * The list is drawn from the index alone, so opening the library never parses a move; only "Review this
 * game" hands the one game's text to the normal analysis flow.
 */

/** The library's groups, in the order the list shows them. [key] is the index's `era` column. */
enum class FamousEra(val key: String) {
    ROMANTIC("romantic"),
    CLASSICAL("classical"),
    INTERWAR("interwar"),
    POSTWAR("postwar"),
    KASPAROV("kasparov"),
    MODERN("modern"),
    COMPUTERS("computers"),
    ;

    companion object {
        fun ofKey(key: String): FamousEra? = entries.firstOrNull { it.key == key.trim() }
    }
}

/** One row of the index. [description] is our own words; the moves stay in the PGN asset. */
data class FamousGame(
    val id: String,
    val era: FamousEra,
    val title: String,
    val white: String,
    val black: String,
    val year: Int,
    val result: String,
    val description: String,
)

/** A malformed index row: the asset is broken (the host tests make that a build failure). */
class FamousIndexException(message: String) : Exception(message)

/** Asset names, shared by the loader and the tests. */
object FamousGameAssets {
    const val PGN = "famous_games.pgn"
    const val INDEX = "famous_games_index.tsv"
}

private const val INDEX_COLUMNS = 8
private val RESULTS = setOf("1-0", "0-1", "1/2-1/2")
private val ID_PATTERN = Regex("[a-z0-9]+(?:-[a-z0-9]+)*")

/**
 * Reads the index. Blank lines and lines starting with `#` are skipped, and so is the header row (`id\t...`).
 * Every other line must have exactly [INDEX_COLUMNS] tab-separated, non-blank fields, a known era, a four-digit
 * year, a decisive or drawn result and a unique id; otherwise this throws.
 */
fun parseFamousIndex(tsv: String): List<FamousGame> {
    val out = ArrayList<FamousGame>()
    val ids = HashSet<String>()
    tsv.removePrefix("\uFEFF").lineSequence().forEachIndexed { i, rawLine ->
        val line = rawLine.trimEnd('\r')
        if (line.isBlank() || line.startsWith("#") || line.startsWith("id\t")) return@forEachIndexed
        val f = line.split('\t')
        val where = "line ${i + 1}"
        if (f.size != INDEX_COLUMNS) throw FamousIndexException("$where: ${f.size} columns, expected $INDEX_COLUMNS")
        if (f.any { it.isBlank() }) throw FamousIndexException("$where: an empty field")
        val id = f[0].trim()
        if (!ID_PATTERN.matches(id)) throw FamousIndexException("$where: bad id '$id'")
        if (!ids.add(id)) throw FamousIndexException("$where: duplicate id '$id'")
        val era = FamousEra.ofKey(f[1]) ?: throw FamousIndexException("$where: unknown era '${f[1]}'")
        val year = f[5].trim().takeIf { it.length == 4 }?.toIntOrNull() ?: throw FamousIndexException("$where: bad year '${f[5]}'")
        val result = f[6].trim().takeIf { it in RESULTS } ?: throw FamousIndexException("$where: bad result '${f[6]}'")
        out += FamousGame(id, era, f[2].trim(), f[3].trim(), f[4].trim(), year, result, f[7].trim())
    }
    return out
}

/**
 * Splits a multi-game PGN text into one text per game, without parsing a move: a game starts at a tag line
 * (`[`) that follows movetext. Leading text before the first tag section and blank games are dropped.
 */
fun splitPgnGames(text: String): List<String> {
    val games = ArrayList<String>()
    val current = StringBuilder()
    var sawMoves = false
    for (rawLine in text.removePrefix("\uFEFF").lineSequence()) {
        val line = rawLine.trimEnd('\r')
        val trimmed = line.trim()
        if (trimmed.startsWith("[") && sawMoves) {
            games += current.toString().trim()
            current.clear()
            sawMoves = false
        }
        if (trimmed.isEmpty() && current.isEmpty()) continue
        if (trimmed.isNotEmpty() && !trimmed.startsWith("[")) sawMoves = true
        current.append(line).append('\n')
    }
    if (current.isNotBlank()) games += current.toString().trim()
    return games.filter { it.isNotBlank() }
}

private val TAG_LINE = Regex("""^\[(\w+)\s+"((?:[^"\\]|\\.)*)"\]\s*$""")

/** The tag pairs of one game's text, in order (a cheap read for the detail sheet; the moves are not parsed). */
fun pgnTags(gameText: String): Map<String, String> {
    val tags = LinkedHashMap<String, String>()
    for (line in gameText.lineSequence()) {
        val m = TAG_LINE.matchEntire(line.trim()) ?: continue
        tags[m.groupValues[1]] = m.groupValues[2].replace("\\\"", "\"").replace("\\\\", "\\")
    }
    return tags
}

private val MOVE_NUMBER = Regex("""\d+\.+""")

/**
 * The number of half-moves in one game's bare movetext (no comments or variations, as in the asset): every
 * token that is not a tag line, a move number or the result. Shown as whole moves on the detail sheet.
 */
fun pgnPlyCount(gameText: String): Int =
    gameText.lineSequence()
        .filterNot { it.trim().startsWith("[") }
        .flatMap { it.trim().split(Regex("\\s+")).asSequence() }
        .map { it.replace(MOVE_NUMBER, "") }
        .count { it.isNotEmpty() && it !in RESULTS && it != "*" }

/** Lower case, accents and a few special letters folded, so "reti" finds "Réti" and "samisch" finds "Sämisch". */
fun foldForSearch(text: String): String {
    val decomposed = Normalizer.normalize(text.lowercase(Locale.ROOT), Normalizer.Form.NFD)
    val sb = StringBuilder(decomposed.length)
    for (c in decomposed) {
        when {
            Character.getType(c) == Character.NON_SPACING_MARK.toInt() -> Unit
            c == 'ø' -> sb.append('o')
            c == 'ł' -> sb.append('l')
            c == 'ß' -> sb.append("ss")
            c == 'æ' -> sb.append("ae")
            c == 'ə' -> sb.append('e')
            c == '–' || c == '—' -> sb.append('-')
            else -> sb.append(c)
        }
    }
    return sb.toString()
}

/**
 * The games matching [query]: every word of the query must appear in the game's title, a player's name or
 * its year (case and accents ignored). A blank query matches everything. Order is kept.
 */
fun searchFamousGames(games: List<FamousGame>, query: String): List<FamousGame> {
    val words = foldForSearch(query).split(Regex("\\s+")).filter { it.isNotEmpty() }
    if (words.isEmpty()) return games
    return games.filter { g ->
        val haystack = foldForSearch("${g.title} ${g.white} ${g.black} ${g.year}")
        words.all { haystack.contains(it) }
    }
}

/** The games by era, in [FamousEra] order; inside an era by year, then as listed in the index. Empty eras are left out. */
fun groupFamousGames(games: List<FamousGame>): List<Pair<FamousEra, List<FamousGame>>> =
    FamousEra.entries.mapNotNull { era ->
        val inEra = games.withIndex().filter { it.value.era == era }.sortedWith(compareBy({ it.value.year }, { it.index })).map { it.value }
        if (inEra.isEmpty()) null else era to inEra
    }

/** The loaded library: the index rows and each game's PGN text, matched by position. */
class FamousGamesLibrary(val games: List<FamousGame>, private val pgnById: Map<String, String>) {
    /** The game's PGN text, exactly as in the asset (factual tags and moves only). */
    fun pgnFor(id: String): String? = pgnById[id]

    companion object {
        /**
         * Pairs the index with the PGN asset. They must have the same number of games, and each PGN game's
         * White, Black and Result tags and the year of its Date tag must match its index row; otherwise this
         * throws (FamousGamesAssetTest makes a mismatch a build failure, so the app never shows the wrong moves).
         */
        fun from(indexTsv: String, pgnText: String): FamousGamesLibrary {
            val games = parseFamousIndex(indexTsv)
            val texts = splitPgnGames(pgnText)
            if (games.size != texts.size) throw FamousIndexException("index has ${games.size} games, PGN has ${texts.size}")
            val byId = LinkedHashMap<String, String>()
            games.zip(texts).forEach { (g, text) ->
                val tags = pgnTags(text)
                val year = tags["Date"]?.take(4)?.toIntOrNull()
                if (tags["White"] != g.white || tags["Black"] != g.black || tags["Result"] != g.result || year != g.year) {
                    throw FamousIndexException(
                        "${g.id}: index says ${g.white} - ${g.black} ${g.result} ${g.year}, PGN says " +
                            "${tags["White"]} - ${tags["Black"]} ${tags["Result"]} ${tags["Date"]}",
                    )
                }
                byId[g.id] = text
            }
            return FamousGamesLibrary(games, byId)
        }
    }
}
