package net.palaya.chessanalyzer.core.narration

import net.palaya.chessanalyzer.core.chess.Position
import net.palaya.chessanalyzer.core.chess.Square
import net.palaya.chessanalyzer.core.chess.parseSan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The speech-form helper is the thing standing between a SAN string and a text-to-speech engine,
 * so it gets its own tests: every phrase it produces has to be pronounceable, and none of it may
 * contain notation. [SpokenChess] supplies the facts, [EnglishNarration.Vocabulary] the words.
 */
class SpokenChessTest {

    private val english = EnglishNarration.Vocabulary

    private fun phraseOf(fen: String, san: String): String {
        val pos = Position.fromFen(fen)
        return english.movePhrase(SpokenChess.describe(pos, pos.parseSan(san)))
    }

    @Test
    fun `squares are spelled letter then word`() {
        assertEquals("e four", english.square(Square.fromAlgebraic("e4")))
        assertEquals("a one", english.square(Square.fromAlgebraic("a1")))
        assertEquals("h eight", english.square(Square.fromAlgebraic("h8")))
    }

    @Test
    fun `quiet moves name the piece and the destination`() {
        assertEquals("pawn to e four", phraseOf(Position.STANDARD_START_FEN, "e4"))
        assertEquals("knight to f three", phraseOf(Position.STANDARD_START_FEN, "Nf3"))
    }

    @Test
    fun `captures name the victim`() {
        // Philidor after 3... Bg4: 4. dxe5 takes a pawn.
        val fen = "rn1qkbnr/ppp2ppp/3p4/4p3/3PP1b1/5N2/PPP2PPP/RNBQKB1R w KQkq - 1 4"
        assertEquals("pawn takes the pawn on e five", phraseOf(fen, "dxe5"))
    }

    @Test
    fun `castling is spoken, never spelled`() {
        val fen = "r1bqk2r/pppp1ppp/2n2n2/2b1p3/2B1P3/2N2N2/PPPP1PPP/R1BQK2R w KQkq - 6 5"
        assertEquals("castles kingside", phraseOf(fen, "O-O"))
        val queenside = "r3kbnr/pppqpppp/2npb3/8/3PP3/2N1B3/PPPQ1PPP/R3KBNR w KQkq - 6 6"
        assertEquals("castles queenside", phraseOf(queenside, "O-O-O"))
    }

    @Test
    fun `check and mate are spoken as words`() {
        // Scholar's mate position: Qxf7 is mate.
        val fen = "r1bqkbnr/pppp1ppp/2n5/4p3/2B1P3/5Q2/PPPP1PPP/RNB1K1NR w KQkq - 4 4"
        assertTrue(phraseOf(fen, "Qxf7#").endsWith("and that is checkmate"))
    }

    @Test
    fun `ambiguous piece moves speak the origin square`() {
        // Both rooks can reach d1.
        val fen = "4k3/8/8/8/8/8/4K3/R6R w - - 0 1"
        val pos = Position.fromFen(fen)
        val facts = SpokenChess.describe(pos, pos.parseSan("Rad1"))
        assertTrue(facts.ambiguous)
        val phrase = english.movePhrase(facts)
        assertTrue(phrase, phrase.contains("from a one"))
        assertTrue(phrase, phrase.contains("d one"))
    }

    @Test
    fun `a whole line is spoken as one sentence`() {
        val fen = "rn1qkbnr/ppp2ppp/3p4/4p3/3PP1b1/5N2/PPP2PPP/RNBQKB1R w KQkq - 1 4"
        val pos = Position.fromFen(fen)
        val line = english.linePhrase(SpokenChess.describeLine(pos, listOf("d4e5", "g4f3", "d1f3"), 3))
        assertTrue(line, line.contains(", then "))
        assertFalse(NotationGuard.containsNotation(line))
    }

    @Test
    fun `notation guard rewrites anything that slips through`() {
        assertEquals("knight takes on f three", NotationGuard.scrub("Nxf3"))
        assertEquals("queen to h five, with check", NotationGuard.scrub("Qh5+"))
        assertEquals("castles queenside", NotationGuard.scrub("O-O-O"))
        assertEquals("castles kingside", NotationGuard.scrub("O-O"))
        assertEquals("the d pawn takes on e five", NotationGuard.scrub("dxe5"))
        assertEquals("the e four square", NotationGuard.scrub("the e4 square"))
        assertEquals("the a file rook to d one", NotationGuard.scrub("Rad1"))
    }

    @Test
    fun `notation guard leaves ordinary prose alone`() {
        val prose = "You walked past three forks in this game, and the knight on f three was hanging."
        assertEquals(prose, NotationGuard.scrub(prose))
        assertFalse(NotationGuard.containsNotation(prose))
    }
}
