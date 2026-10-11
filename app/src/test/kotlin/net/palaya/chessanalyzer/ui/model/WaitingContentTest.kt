package net.palaya.chessanalyzer.ui.model

import net.palaya.chessanalyzer.core.chess.Color
import net.palaya.chessanalyzer.core.chess.Move
import net.palaya.chessanalyzer.core.chess.PieceType
import net.palaya.chessanalyzer.core.chess.Position
import net.palaya.chessanalyzer.core.chess.moveToSan
import net.palaya.chessanalyzer.core.chess.parseUci
import net.palaya.chessanalyzer.core.pgn.PgnParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import net.palaya.chessanalyzer.ui.model.WaitingTestSupport as T

/**
 * The real "While you wait" assets (the JSON files under assets/waiting), parsed and checked on the host. The puzzle checks use
 * `:core`'s own move generator and the famous games' PGN, independently of the Python/Stockfish miner that produced
 * the file (scripts/waiting_puzzles.py). docs/WAITING_CONTENT.md lists the source of every fact and player.
 */
class WaitingContentTest {

    private val library by lazy {
        FamousGamesLibrary.from(T.assetText(FamousGameAssets.INDEX), T.assetText(FamousGameAssets.PGN))
    }
    private val content by lazy {
        parseWaitingContent(T.assetText(WaitingAssets.FACTS), T.assetText(WaitingAssets.PLAYERS), T.assetText(WaitingAssets.PUZZLES), library)
    }
    private val doc by lazy { T.repoFile("docs/WAITING_CONTENT.md").readText(Charsets.UTF_8) }

    private fun sentences(text: String) = text.split(Regex("(?<=[.!?])\\s+(?=[A-Z0-9])")).size

    @Test
    fun theContentHasTheAgreedSizes() {
        assertTrue("facts: ${content.facts.size}", content.facts.size in 55..80)
        assertTrue("players: ${content.players.size}", content.players.size in 24..30)
        assertTrue("puzzles: ${content.puzzles.size}", content.puzzles.size in 30..80)
        assertEquals(library.games.size, content.games.size)
    }

    @Test
    fun factsAndPlayersAreShortAndEachHasASourceInTheDoc() {
        for (f in content.facts) {
            assertTrue("${f.id}: ${f.text.length} chars", f.text.length in 20..330)
            assertTrue("${f.id}: ${sentences(f.text)} sentences", sentences(f.text) <= 2)
            assertTrue("${f.id}: topic ${f.topic}", f.topic in setOf("maths", "rules", "history", "records", "computers"))
            assertTrue("${f.id} has no source row in docs/WAITING_CONTENT.md", doc.contains("| ${f.id} |"))
        }
        for (p in content.players) {
            assertTrue("${p.id}: text", p.text.length in 20..330 && sentences(p.text) <= 2)
            assertTrue("${p.id}: years", p.died?.let { it >= p.born } ?: true)
            assertTrue("${p.id} has no source row in docs/WAITING_CONTENT.md", doc.contains("| ${p.id} |"))
        }
        assertEquals(content.facts.size, content.facts.map { it.id }.toSet().size)
    }

    @Test
    fun noTextIsClaimedInDubiousTermsOrCopiedMarkup() {
        val all = content.facts.map { it.text } + content.players.map { it.text } + content.players.mapNotNull { it.role }
        for (t in all) {
            assertFalse("html/markdown in '$t'", t.contains('<') || t.contains("**") || t.contains("]("))
            assertFalse("a hedge that is not a fact: '$t'", Regex("(?i)\\b(allegedly|reportedly|legend has it|some say|rumou?r)\\b").containsMatchIn(t))
        }
    }

    @Test
    fun everyGameCardEndsOnALegalPositionOfItsOwnGame() {
        for (g in content.games) {
            assertTrue(g.game.id, isUsableFen(g.finalFen))
            assertTrue(g.game.id, g.plyCount in 10..400)
        }
    }

    // ------------------------------------------------------------------------------------------------
    // Puzzles: re-verified with :core, independently of the Python miner
    // ------------------------------------------------------------------------------------------------

    private fun key3(fen: String) = fen.split(' ').take(3).joinToString(" ")

    @Test
    fun everyPuzzleIsAPositionOfItsGameAndItsFirstMoveIsTheMovePlayed() {
        for (p in content.puzzles) {
            val game = PgnParser.parse(library.pgnFor(p.gameId)!!).single()
            val move = game.moves[p.ply - 1]
            assertEquals("${p.id}: position", key3(move.positionFenBefore), key3(p.fen))
            assertEquals("${p.id}: first solution move is the move played", move.uci, p.solution[0])
            assertEquals("${p.id}: previous move", if (p.ply >= 2) game.moves[p.ply - 2].uci else null, p.prevUci)
            assertEquals("${p.id}: side to move", move.color, p.sideToMove)
        }
    }

    @Test
    fun everySolutionIsLegalAndItsSanIsTheCoreOnes() {
        for (p in content.puzzles) {
            var pos = Position.fromFen(p.fen)
            p.solution.forEachIndexed { i, uci ->
                assertEquals("${p.id}: only queen promotions (the tap path auto-queens)", true, uci.length == 4 || uci[4] == 'q')
                val move = pos.parseUci(uci)
                assertTrue("${p.id}: $uci is legal", move in pos.legalMoves())
                assertEquals("${p.id}: SAN of move ${i + 1}", p.san[i], pos.moveToSan(move))
                pos = pos.makeMove(move)
            }
            assertEquals("${p.id}: ends on the solver's move", 1, p.solution.size % 2)
        }
    }

    @Test
    fun matePuzzlesEndInCheckmateInExactlyTheClaimedMoves() {
        var mates = 0
        for (p in content.puzzles) {
            val goal = p.goal as? WaitingPuzzleGoal.MateIn ?: continue
            mates++
            var pos = Position.fromFen(p.fen)
            for (uci in p.solution) pos = pos.makeMove(pos.parseUci(uci))
            assertTrue("${p.id}: ends in checkmate", pos.isCheckmate())
            assertEquals("${p.id}: mate in", goal.moves, p.solverMoves)
            assertTrue("${p.id}: mate in ${goal.moves}", goal.moves in 2..4)
        }
        assertTrue("some mate puzzles", mates >= 5)
    }

    /** True when the side to move can force checkmate within [n] of its own moves. */
    private fun mateWithin(pos: Position, n: Int): Boolean = pos.legalMoves().any { forcesMate(pos.makeMove(it), n) }

    /** After the attacker's move: checkmate now, or every reply allows mate within n-1 more attacker moves. */
    private fun forcesMate(after: Position, n: Int): Boolean {
        if (after.isCheckmate()) return true
        if (n <= 1) return false
        val replies = after.legalMoves()
        if (replies.isEmpty()) return false
        return replies.all { mateWithin(after.makeMove(it), n - 1) }
    }

    @Test
    fun mateInTwoPuzzlesAreProvedWithoutAnEngine() {
        // Exhaustive: the solution's first move forces mate within 2, no shorter mate exists, and no other first move forces mate within 2.
        for (p in content.puzzles) {
            val goal = p.goal as? WaitingPuzzleGoal.MateIn ?: continue
            if (goal.moves > 2) continue
            val pos = Position.fromFen(p.fen)
            assertFalse("${p.id}: a mate in 1 exists", pos.legalMoves().any { pos.makeMove(it).isCheckmate() })
            val mating = pos.legalMoves().filter { forcesMate(pos.makeMove(it), 2) }
            assertEquals("${p.id}: the first moves that force mate in 2", listOf(p.solution[0]), mating.map(Move::toUci))
        }
    }

    @Test
    fun theScoresBehindABestMovePuzzleHaveTheDocumentedMargin() {
        for (p in content.puzzles) {
            if (p.goal != WaitingPuzzleGoal.BestMove) {
                assertEquals("${p.id}: a mate puzzle has no best score", null, p.bestCp)
                continue
            }
            val best = p.bestCp!!
            assertTrue("${p.id}: best $best", best >= 150)
            p.secondCp?.let { assertTrue("${p.id}: margin ${best - it}", best - it >= 150) }
            assertTrue("${p.id}: depth ${p.depth}", p.depth >= 18)
        }
    }

    @Test
    fun everyPuzzleCanBeSolvedByTappingOnTheBoard() {
        for (p in content.puzzles) {
            var s = newWaitingPuzzleSession(p)
            var i = 0
            while (i < p.solution.size) {
                val (from, to) = uciToUiSquares(p.solution[i])!!
                s = waitingPuzzleTap(waitingPuzzleTap(s, from), to)
                if (s.status == WaitingPuzzleStatus.REPLYING) s = waitingPuzzleReply(s)
                i += 2
            }
            assertEquals("${p.id}: solved by taps", WaitingPuzzleStatus.SOLVED, s.status)
            // and "Show solution" reaches the very same final position
            val shown = waitingPuzzleReveal(newWaitingPuzzleSession(p))
            assertEquals("${p.id}: reveal", s.position.toFen(), shown.position.toFen())
            assertEquals("${p.id}: line text", p.solution.size, solutionLineText(p).split(' ').size)
        }
    }

    @Test
    fun puzzlesAreSpreadOverManyGamesAndBothSides() {
        assertTrue("games with a puzzle: ${content.puzzles.map { it.gameId }.toSet().size}", content.puzzles.map { it.gameId }.toSet().size >= 20)
        assertTrue(content.puzzles.any { it.sideToMove == Color.WHITE })
        assertTrue(content.puzzles.any { it.sideToMove == Color.BLACK })
        assertEquals(content.puzzles.size, content.puzzles.map { it.id }.toSet().size)
    }

    @Test
    fun theLoaderRejectsBrokenAssets() {
        val ok = T.assetText(WaitingAssets.FACTS)
        assertNotNull(parseWaitingFacts(ok))
        for (bad in listOf("{}", """{"facts":[{"id":"x"}]}""", """{"facts":[{"id":"a","topic":"t","text":"x"},{"id":"a","topic":"t","text":"y"}]}""")) {
            try {
                parseWaitingFacts(bad)
                throw AssertionError("accepted: $bad")
            } catch (e: WaitingContentException) {
                // expected
            }
        }
        try {
            parseWaitingPuzzles("""{"puzzles":[{"id":"p","game":"nope","ply":1,"fen":"x","goal":{"type":"best"},"solution":["e2e4"],"san":["e4"],"depth":22}]}""", library.games)
            throw AssertionError("an unknown game was accepted")
        } catch (e: WaitingContentException) {
            // expected
        }
    }

    @Test
    fun theOpeningBookTheFactSpeaksOfHasThousandsOfNames() {
        val book = net.palaya.chessanalyzer.core.analysis.OpeningBook.load(T.assetText("openings.tsv").reader())
        assertTrue("lines loaded: ${book.linesLoaded}", book.linesLoaded > 3000)
        assertTrue(content.facts.any { it.id == "opening-book" && it.text.contains("more than 3,000") })
    }

    @Test
    fun theKindOfPieceInAPromotionPositionMatchesTheFactAboutPromotion() {
        val pos = Position.fromFen("8/P7/8/8/8/8/8/k6K w - - 0 1")
        val promotions = pos.legalMoves().filter { it.from.toString() == "a7" }.mapNotNull { it.promotion }.toSet()
        assertEquals(setOf(PieceType.QUEEN, PieceType.ROOK, PieceType.BISHOP, PieceType.KNIGHT), promotions)
    }
}
