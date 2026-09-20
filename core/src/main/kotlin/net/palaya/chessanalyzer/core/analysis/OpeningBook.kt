package net.palaya.chessanalyzer.core.analysis

import net.palaya.chessanalyzer.core.chess.Position
import net.palaya.chessanalyzer.core.chess.parseSan
import net.palaya.chessanalyzer.core.pgn.PgnGame
import java.io.Reader

/** One catalogued opening line's identity. */
data class OpeningEntry(val eco: String, val name: String)

/**
 * The bundled opening book (`app/src/main/assets/openings.tsv`), replayed through the real
 * chess core so lookups are by *position*, not by move text (this correctly handles
 * transpositions).
 *
 * `core` is a pure-JVM module and cannot read Android assets directly, hence [load] takes a
 * [Reader] — the app layer is responsible for opening the asset and handing this a reader over
 * it.
 *
 * Two indexes are built from the TSV:
 *  - [namedIndex] maps the *exact terminal position* of each catalogued row to its
 *    (eco, name) — this is what makes `1. e4 e5 2. Nf3 d6` resolve to exactly "Philidor
 *    Defense" and nothing vaguer.
 *  - [bookPositions] additionally contains every *prefix* position of every catalogued row
 *    (including the ones with no row of their own, e.g. after 1.e4 alone when only "1. e4 e5"
 *    has a named row) — this is what backs [isBookPosition], since a position can obviously
 *    still be "in book" without itself having its own display name.
 */
class OpeningBook private constructor(
    private val namedIndex: Map<String, OpeningEntry>,
    private val bookPositions: Set<String>,
    val linesLoaded: Int,
    val linesSkipped: Int
) {
    /** Keys on the FEN's first four fields (board, side, castling, en passant). */
    fun fenKey(fen: String): String = fen.trim().split(Regex("\\s+")).take(4).joinToString(" ")

    /** The catalogued opening whose terminal position is exactly [fen], if any. */
    fun lookup(fen: String): OpeningEntry? = namedIndex[fenKey(fen)]

    /** True if [fen] is anywhere within a catalogued opening line (prefix or terminal). */
    fun isBookPosition(fen: String): Boolean = fenKey(fen) in bookPositions

    /**
     * Walks [game]'s positions in order and returns the deepest (longest) catalogued opening
     * reached — this is what the report displays as the game's opening.
     */
    fun longestMatchingOpening(game: PgnGame): OpeningEntry? {
        var found: OpeningEntry? = null
        for (move in game.moves) {
            lookup(move.positionFenAfter)?.let { found = it }
        }
        return found
    }

    companion object {
        private val MOVE_NUMBER_TOKEN = Regex("^\\d+\\.+$")

        fun load(reader: Reader): OpeningBook {
            val named = HashMap<String, OpeningEntry>()
            val prefixes = HashSet<String>()
            var loaded = 0
            var skipped = 0

            reader.useLines { lines ->
                for ((index, rawLine) in lines.withIndex()) {
                    val line = rawLine.trim()
                    if (line.isEmpty()) continue
                    if (index == 0 && line.startsWith("eco", ignoreCase = true)) continue // header

                    val parts = line.split("\t")
                    if (parts.size < 3) {
                        skipped++
                        continue
                    }
                    val eco = parts[0].trim()
                    val name = parts[1].trim()
                    val pgnText = parts[2].trim()
                    if (eco.isEmpty() || name.isEmpty()) {
                        skipped++
                        continue
                    }

                    try {
                        var pos = Position.startPosition()
                        val tokens = pgnText.split(Regex("\\s+")).filter { it.isNotBlank() && !MOVE_NUMBER_TOKEN.matches(it) }
                        // Start position itself counts as "in book" trivially.
                        prefixes.add(keyOf(pos.toFen()))
                        for (san in tokens) {
                            val move = pos.parseSan(san)
                            pos = pos.makeMove(move)
                            prefixes.add(keyOf(pos.toFen()))
                        }
                        named[keyOf(pos.toFen())] = OpeningEntry(eco, name)
                        loaded++
                    } catch (e: Exception) {
                        skipped++
                    }
                }
            }

            return OpeningBook(named, prefixes, loaded, skipped)
        }

        private fun keyOf(fen: String): String = fen.trim().split(Regex("\\s+")).take(4).joinToString(" ")
    }
}
