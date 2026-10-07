package net.palaya.chessanalyzer.ui.model

import java.io.File
import net.palaya.chessanalyzer.core.chess.Color
import net.palaya.chessanalyzer.core.chess.Position
import net.palaya.chessanalyzer.core.pgn.PgnParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The real famous-games assets (G1), replayed on the host with our own PGN parser and move generator, so a bad
 * game breaks the build (docs/FAMOUS_GAMES.md §3; scripts/verify_famous_games.py checks the same facts with
 * python-chess, independently of our code). Every game must:
 * - parse as exactly one game from the standard start, every move legal, with no comment, NAG or variation;
 * - carry only factual tags (the Seven Tag Roster plus ECO), and a Result equal to the movetext's own;
 * - end consistently with its result: a checkmate is won by the side that gave it, a stalemate is a draw;
 * - match its index row, and be unique (no two games with the same moves, ids or titles).
 */
class FamousGamesAssetTest {

    private fun asset(name: String): File =
        listOf(File("src/main/assets/$name"), File("app/src/main/assets/$name")).firstOrNull { it.exists() }
            ?: throw AssertionError("asset $name not found")

    private val indexText by lazy { asset(FamousGameAssets.INDEX).readText(Charsets.UTF_8) }
    private val pgnText by lazy { asset(FamousGameAssets.PGN).readText(Charsets.UTF_8) }
    private val library by lazy { FamousGamesLibrary.from(indexText, pgnText) }

    private val allowedTags = setOf("Event", "Site", "Date", "Round", "White", "Black", "Result", "ECO")
    private val rosterTags = listOf("Event", "Site", "Date", "Round", "White", "Black", "Result")

    @Test
    fun theIndexAndThePgnHoldTheSameGamesInTheSameOrder() {
        val games = parseFamousIndex(indexText)
        val texts = splitPgnGames(pgnText)
        assertEquals("index rows vs PGN games", games.size, texts.size)
        assertTrue("the library should hold 80-120 games, has ${games.size}", games.size in 80..120)
        // FamousGamesLibrary.from checks White, Black, Result and the Date's year of every pair.
        assertEquals(games.map { it.id }, library.games.map { it.id })
        // The whole PGN, parsed at once by our parser, has as many games as the index.
        assertEquals(games.size, PgnParser.parse(pgnText).size)
    }

    @Test
    fun everyEraIsUsedAndEveryRowIsComplete() {
        assertEquals(FamousEra.entries.toSet(), library.games.map { it.era }.toSet())
        for (g in library.games) {
            assertTrue("${g.id}: description too long for one line", g.description.length <= 240)
            assertTrue("${g.id}: description must be a sentence", g.description.endsWith("."))
            assertTrue("${g.id}: title", g.title.isNotBlank() && g.title.length <= 80)
        }
    }

    @Test
    fun everyGameReplaysLegallyWithBareMovesAndFactualTags() {
        for (g in library.games) {
            val text = library.pgnFor(g.id)!!
            val parsed = PgnParser.parse(text)
            assertEquals("${g.id}: one game per entry", 1, parsed.size)
            val game = parsed.single()
            assertNull("${g.id}: must start from the standard position", game.startFen)
            assertTrue("${g.id}: no moves", game.moves.isNotEmpty())
            assertEquals("${g.id}: the cheap ply count must agree with the parser", game.moves.size, pgnPlyCount(text))
            for (m in game.moves) {
                assertNull("${g.id}: comment at ${m.san}", m.comment)
                assertTrue("${g.id}: NAG at ${m.san}", m.nags.isEmpty())
                assertTrue("${g.id}: variation at ${m.san}", m.variations.isEmpty())
                assertTrue("${g.id}: annotation glyph in '${m.san}'", m.san.none { it == '!' || it == '?' })
            }
            val unknown = game.tags.keys - allowedTags
            assertTrue("${g.id}: tags beyond the factual set: $unknown", unknown.isEmpty())
            for (t in rosterTags) assertTrue("${g.id}: missing tag $t", !game.tags[t].isNullOrBlank())
            assertEquals("${g.id}: Result tag vs movetext", game.tags["Result"], game.result)
            assertTrue("${g.id}: Date ${game.tags["Date"]}", Regex("""\d{4}\.(\d{2}|\?\?)\.(\d{2}|\?\?)""").matches(game.tags["Date"]!!))
            game.tags["ECO"]?.let { assertTrue("${g.id}: ECO $it", Regex("[A-E]\\d{2}").matches(it)) }
        }
    }

    @Test
    fun everyResultIsConsistentWithTheFinalPosition() {
        var mates = 0
        for (g in library.games) {
            val game = PgnParser.parse(library.pgnFor(g.id)!!).single()
            val last = game.moves.last()
            val end = Position.fromFen(last.positionFenAfter)
            when {
                end.isCheckmate() -> {
                    mates++
                    val winner = if (end.sideToMove == Color.WHITE) "0-1" else "1-0"
                    assertEquals("${g.id}: the side that mated must win", winner, game.result)
                    assertTrue("${g.id}: a mating move is written with #", last.san.endsWith("#"))
                }
                end.isStalemate() -> assertEquals("${g.id}: stalemate is a draw", "1/2-1/2", game.result)
                else -> assertTrue("${g.id}: a game over the board ended without mate needs a decisive or drawn result", game.result in setOf("1-0", "0-1", "1/2-1/2"))
            }
            // A check is written with + and only then (our SAN writer and the asset agree on every move).
            for (m in game.moves) {
                val after = Position.fromFen(m.positionFenAfter)
                val checks = after.isInCheck()
                val marked = m.san.endsWith("+") || m.san.endsWith("#")
                assertEquals("${g.id}: check mark on ${m.moveNumber}. ${m.san}", checks, marked)
            }
        }
        assertTrue("several famous games end in mate", mates >= 10)
    }

    @Test
    fun noGameIsInTheLibraryTwice() {
        val sequences = library.games.associate { g -> g.id to PgnParser.parse(library.pgnFor(g.id)!!).single().moves.joinToString(" ") { it.uci } }
        val dupes = sequences.entries.groupBy { it.value }.values.filter { it.size > 1 }.map { group -> group.map { it.key } }
        assertTrue("same moves twice: $dupes", dupes.isEmpty())
        val titles = library.games.groupBy { it.title.lowercase() }.filter { it.value.size > 1 }.keys
        assertTrue("same title twice: $titles", titles.isEmpty())
    }
}
