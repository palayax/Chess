package net.palaya.chessanalyzer.ui.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** The pure logic of the famous-games library (G1): index rows, PGN splitting, tags, search and grouping. */
class FamousGamesLogicTest {

    private fun game(id: String, era: FamousEra, title: String, white: String, black: String, year: Int, result: String = "1-0") =
        FamousGame(id, era, title, white, black, year, result, "Played in $year.")

    private val immortal = game("immortal", FamousEra.ROMANTIC, "The Immortal Game", "Adolf Anderssen", "Lionel Kieseritzky", 1851)
    private val evergreen = game("evergreen", FamousEra.ROMANTIC, "The Evergreen Game", "Adolf Anderssen", "Jean Dufresne", 1852)
    private val opera = game("opera", FamousEra.ROMANTIC, "The Opera Game", "Paul Morphy", "Duke Karl of Brunswick and Count Isouard", 1858)
    private val zugzwang = game("zugzwang", FamousEra.INTERWAR, "The Immortal Zugzwang Game", "Friedrich Sämisch", "Aron Nimzowitsch", 1923, "0-1")
    private val reti = game("reti-tartakower", FamousEra.CLASSICAL, "Réti's miniature", "Richard Réti", "Savielly Tartakower", 1910)
    private val deepBlue = game("deep-blue-1997", FamousEra.COMPUTERS, "Deep Blue's match win", "Deep Blue", "Garry Kasparov", 1997)
    private val all = listOf(opera, immortal, zugzwang, evergreen, reti, deepBlue)

    // ---- Index ----

    private val header = "id\tera\ttitle\twhite\tblack\tyear\tresult\tdescription"

    @Test
    fun indexRowsAreRead() {
        val tsv = "# comment\n$header\n" +
            "immortal\tromantic\tThe Immortal Game\tAdolf Anderssen\tLionel Kieseritzky\t1851\t1-0\tPlayed in London in 1851.\r\n" +
            "\n" +
            "draw\tromantic\tA draw\tA\tB\t1872\t1/2-1/2\tA draw.\n"
        val games = parseFamousIndex(tsv)
        assertEquals(2, games.size)
        assertEquals(immortal.copy(description = "Played in London in 1851."), games[0])
        assertEquals("1/2-1/2", games[1].result)
    }

    private fun assertRejected(row: String) {
        try {
            parseFamousIndex("$header\n$row\n")
            fail("accepted: $row")
        } catch (e: FamousIndexException) {
            // expected
        }
    }

    @Test
    fun malformedIndexRowsAreRejected() {
        assertRejected("x\tromantic\tT\tW\tB\t1851\t1-0") // 7 columns
        assertRejected("x\tbaroque\tT\tW\tB\t1851\t1-0\tD") // unknown era
        assertRejected("x\tromantic\tT\tW\tB\t51\t1-0\tD") // year
        assertRejected("x\tromantic\tT\tW\tB\t1851\t*\tD") // unfinished result
        assertRejected("x\tromantic\tT\t \tB\t1851\t1-0\tD") // blank field
        assertRejected("X Y\tromantic\tT\tW\tB\t1851\t1-0\tD") // id
        try {
            parseFamousIndex("$header\nx\tromantic\tT\tW\tB\t1851\t1-0\tD\nx\tromantic\tT\tW\tB\t1852\t1-0\tD\n")
            fail("a duplicate id was accepted")
        } catch (e: FamousIndexException) {
            // expected
        }
    }

    // ---- PGN splitting and tags ----

    private val twoGames = "\uFEFF[Event \"London\"]\r\n[White \"A\"]\r\n[Black \"B\"]\r\n[Result \"1-0\"]\r\n\r\n1. e4 e5 2. Qh5 Nc6\r\n3. Bc4 Nf6 4. Qxf7# 1-0\r\n\r\n" +
        "[Event \"Paris \\\"Opera\\\"\"]\n[White \"C\"]\n[Black \"D\"]\n[Result \"0-1\"]\n\n1. f3 e5 2. g4 Qh4# 0-1\n"

    @Test
    fun aMultiGameTextSplitsAtTheNextTagSection() {
        val games = splitPgnGames(twoGames)
        assertEquals(2, games.size)
        assertTrue(games[0].startsWith("[Event \"London\"]"))
        assertTrue(games[0].endsWith("4. Qxf7# 1-0"))
        assertTrue(games[1].startsWith("[Event \"Paris"))
        assertTrue(splitPgnGames("  \n\n").isEmpty())
    }

    @Test
    fun tagsAreReadWithEscapes() {
        val second = splitPgnGames(twoGames)[1]
        val tags = pgnTags(second)
        assertEquals("Paris \"Opera\"", tags["Event"])
        assertEquals("C", tags["White"])
        assertEquals("0-1", tags["Result"])
        assertNull(tags["Date"])
    }

    @Test
    fun plyCountSkipsNumbersAndTheResult() {
        val (first, second) = splitPgnGames(twoGames)
        assertEquals(7, pgnPlyCount(first))
        assertEquals(4, pgnPlyCount(second))
        assertEquals(3, pgnPlyCount("1.e4 e5 2.Nf3 *"))
    }

    // ---- Search ----

    @Test
    fun aBlankQueryKeepsEverythingInOrder() {
        assertEquals(all, searchFamousGames(all, ""))
        assertEquals(all, searchFamousGames(all, "   "))
    }

    @Test
    fun searchFindsPlayersYearsAndTitles() {
        assertEquals(listOf(immortal, evergreen), searchFamousGames(all, "anderssen"))
        assertEquals(listOf(immortal), searchFamousGames(all, "1851"))
        assertEquals(listOf(opera), searchFamousGames(all, "OPERA"))
        assertEquals(listOf(deepBlue), searchFamousGames(all, "Kasparov 1997"))
        // Every word must match.
        assertEquals(listOf(evergreen), searchFamousGames(all, "anderssen dufresne"))
        assertTrue(searchFamousGames(all, "anderssen 1858").isEmpty())
    }

    @Test
    fun searchIgnoresAccents() {
        assertEquals(listOf(zugzwang), searchFamousGames(all, "samisch"))
        assertEquals(listOf(reti), searchFamousGames(all, "reti"))
        assertEquals(listOf(reti), searchFamousGames(all, "Réti"))
        assertEquals("oe-l-ss-ae", foldForSearch("Øe–Ł-ß-Æ"))
    }

    // ---- Grouping ----

    @Test
    fun gamesGroupByEraInEraOrderAndByYearInside() {
        val groups = groupFamousGames(all)
        assertEquals(listOf(FamousEra.ROMANTIC, FamousEra.CLASSICAL, FamousEra.INTERWAR, FamousEra.COMPUTERS), groups.map { it.first })
        assertEquals(listOf(immortal, evergreen, opera), groups[0].second)
    }

    @Test
    fun aSearchLeavesOutEmptyEras() {
        val groups = groupFamousGames(searchFamousGames(all, "1923"))
        assertEquals(listOf(FamousEra.INTERWAR to listOf(zugzwang)), groups)
    }

    @Test
    fun sameYearKeepsIndexOrder() {
        val a = game("a", FamousEra.MODERN, "A", "W", "B", 2021)
        val b = game("b", FamousEra.MODERN, "B", "W", "B", 2021)
        assertEquals(listOf(b, a), groupFamousGames(listOf(b, a)).single().second)
    }

    // ---- Library ----

    @Test
    fun theLibraryPairsTheIndexWithThePgnAndChecksThem() {
        val tsv = "$header\nm1\tromantic\tScholar\tA\tB\t1900\t1-0\tD\nm2\tromantic\tFool\tC\tD\t1901\t0-1\tD\n"
        val pgn = twoGames.replace("[Event \"London\"]", "[Event \"London\"]\n[Date \"1900.??.??\"]")
            .replace("[Event \"Paris", "[Date \"1901.01.02\"]\n[Event \"Paris")
        val library = FamousGamesLibrary.from(tsv, pgn)
        assertEquals(listOf("m1", "m2"), library.games.map { it.id })
        assertTrue(library.pgnFor("m2")!!.contains("Qh4#"))
        assertNull(library.pgnFor("nope"))

        // A wrong year, a swapped name or a missing game is refused.
        for (bad in listOf(tsv.replace("1901", "1902"), tsv.replace("\tC\t", "\tX\t"), tsv.lines().take(2).joinToString("\n"))) {
            try {
                FamousGamesLibrary.from(bad, pgn)
                fail("accepted a mismatched index")
            } catch (e: FamousIndexException) {
                // expected
            }
        }
    }

    @Test
    fun yearAndResultStayLeftToRight() {
        assertEquals("\u200E1851 · 1-0\u200E", net.palaya.chessanalyzer.ui.screens.famousYearAndResult(1851, "1-0"))
    }
}
