package net.palaya.chessanalyzer.core.pgn

import net.palaya.chessanalyzer.core.chess.Color
import net.palaya.chessanalyzer.core.chess.Position
import net.palaya.chessanalyzer.core.chess.parseSan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PgnParserTest {

    /**
     * Replays every SAN move of [game] (main line only) from scratch, verifying that
     * each move is legal in sequence and that the recorded before/after FENs in the
     * PgnMove records match what independent replay produces.
     */
    private fun replayAndVerify(game: PgnGame): Position {
        val startFen = game.startFen
        var pos = if (startFen != null) Position.fromFen(startFen) else Position.startPosition()
        for (pgnMove in game.moves) {
            assertEquals(pgnMove.positionFenBefore, pos.toFen())
            val move = pos.parseSan(pgnMove.san)
            pos = pos.makeMove(move)
            assertEquals(pgnMove.positionFenAfter, pos.toFen())
        }
        return pos
    }

    @Test
    fun parsesChessComStylePgnWithClocksAndFullTagSet() {
        val pgn = """
            [Event "Live Chess"]
            [Site "Chess.com"]
            [Date "2024.03.15"]
            [Round "-"]
            [White "Hikaru"]
            [Black "MagnusCarlsen"]
            [Result "1-0"]
            [WhiteElo "3200"]
            [BlackElo "2850"]
            [TimeControl "180+2"]
            [Termination "Hikaru won by checkmate"]
            [ECO "C50"]
            [UTCDate "2024.03.15"]
            [UTCTime "12:00:00"]
            [Link "https://www.chess.com/game/live/123456789"]

            1. e4 {[%clk 0:03:00]} 1... e5 {[%clk 0:02:59.1]} 2. Bc4 {[%clk 0:02:58]} 2... Bc5 {[%clk 0:02:57.4]}
            3. Qh5 {[%clk 0:02:56]} 3... Nf6?? {[%clk 0:02:55]} 4. Qxf7# {[%clk 0:02:50]} 1-0
        """.trimIndent()

        val games = PgnParser.parse(pgn)
        assertEquals(1, games.size)
        val game = games[0]

        assertEquals("Hikaru", game.tags["White"])
        assertEquals("MagnusCarlsen", game.tags["Black"])
        assertEquals("180+2", game.tags["TimeControl"])
        assertEquals("C50", game.tags["ECO"])
        assertEquals("https://www.chess.com/game/live/123456789", game.tags["Link"])
        assertEquals("1-0", game.result)
        assertNull(game.startFen)

        assertEquals(7, game.moves.size)
        assertEquals("e4", game.moves[0].san)
        assertEquals(Color.WHITE, game.moves[0].color)
        assertEquals(1, game.moves[0].moveNumber)
        assertEquals("0:03:00", game.moves[0].clock)
        assertEquals("e5", game.moves[1].san)
        assertEquals(Color.BLACK, game.moves[1].color)
        assertEquals("0:02:59.1", game.moves[1].clock)

        val last = game.moves.last()
        assertEquals("Qxf7#", last.san)
        assertEquals("0:02:50", last.clock)

        val finalPos = replayAndVerify(game)
        assertTrue(finalPos.isCheckmate())
    }

    @Test
    fun parsesMultipleGamesInOneFile() {
        val pgn = """
            [Event "Game One"]
            [Site "?"]
            [Date "????.??.??"]
            [Round "1"]
            [White "A"]
            [Black "B"]
            [Result "1-0"]

            1. e4 e5 2. Nf3 Nc6 1-0

            [Event "Game Two"]
            [Site "?"]
            [Date "????.??.??"]
            [Round "2"]
            [White "C"]
            [Black "D"]
            [Result "0-1"]

            1. d4 d5 2. c4 e6 0-1
        """.trimIndent()

        val games = PgnParser.parse(pgn)
        assertEquals(2, games.size)
        assertEquals("A", games[0].tags["White"])
        assertEquals("1-0", games[0].result)
        assertEquals(4, games[0].moves.size)
        assertEquals("C", games[1].tags["White"])
        assertEquals("0-1", games[1].result)
        assertEquals(4, games[1].moves.size)

        replayAndVerify(games[0])
        replayAndVerify(games[1])
    }

    @Test
    fun parsesRavsAndNags() {
        val pgn = """
            [Event "RAV test"]
            [Site "?"]
            [Date "????.??.??"]
            [Round "1"]
            [White "A"]
            [Black "B"]
            [Result "*"]

            1. e4 $1 e5 (1... c5 2. Nf3 (2. Nc3 Nc6) 2... d6) 2. Nf3 Nc6 *
        """.trimIndent()

        val games = PgnParser.parse(pgn)
        assertEquals(1, games.size)
        val game = games[0]
        assertEquals("*", game.result)

        // Main line: e4 e5 Nf3 Nc6
        assertEquals(4, game.moves.size)
        assertEquals("e4", game.moves[0].san)
        assertEquals(listOf(1), game.moves[0].nags)

        // The variation is attached to the move it replaces: Black's e5.
        val e5Move = game.moves[1]
        assertEquals(1, e5Move.variations.size)
        val variation = e5Move.variations[0]
        assertEquals("c5", variation[0].san)
        assertEquals("d6", variation.last().san)

        // Nested variation inside the variation, attached to its 2.Nf3.
        val nfMoveInVariation = variation[1]
        assertEquals("Nf3", nfMoveInVariation.san)
        assertEquals(1, nfMoveInVariation.variations.size)
        assertEquals("Nc3", nfMoveInVariation.variations[0][0].san)

        replayAndVerify(game)

        // Sanity-check that the variation itself replays as legal chess from the branch point.
        var varPos = Position.startPosition().let { it.makeMove(it.parseSan("e4")) }
        for (m in variation) {
            varPos = varPos.makeMove(varPos.parseSan(m.san))
        }
    }

    @Test
    fun parsesGameFromFenSetupPosition() {
        val fen = "r3k2r/p1ppqpb1/bn2pnp1/3PN3/1p2P3/2N2Q1p/PPPBBPPP/R3K2R w KQkq - 0 1"
        val pgn = """
            [Event "From FEN"]
            [Site "?"]
            [Date "????.??.??"]
            [Round "1"]
            [White "A"]
            [Black "B"]
            [Result "*"]
            [SetUp "1"]
            [FEN "$fen"]

            1. O-O O-O-O *
        """.trimIndent()

        val games = PgnParser.parse(pgn)
        val game = games[0]
        assertEquals(fen, game.startFen)
        assertEquals(2, game.moves.size)
        assertEquals(fen, game.moves[0].positionFenBefore)

        val finalPos = replayAndVerify(game)
        assertNotNull(finalPos)
    }

    @Test
    fun malformedPgnThrowsPgnParseException() {
        val pgn = """
            [Event "Bad move"]
            [Site "?"]
            [Date "????.??.??"]
            [Round "1"]
            [White "A"]
            [Black "B"]
            [Result "*"]

            1. e4 e5 2. Nf9 Nc6 *
        """.trimIndent()

        val ex = assertThrows(PgnParseException::class.java) { PgnParser.parse(pgn) }
        assertTrue(ex.message!!.contains("Nf9"))
        assertEquals("Nf9", ex.moveText)
    }

    @Test
    fun malformedTagSectionThrows() {
        val pgn = "[Event \"Missing closing quote]\n\n1. e4 *"
        assertThrows(PgnParseException::class.java) { PgnParser.parse(pgn) }
    }

    @Test
    fun tolerantOfBomAndCrlfAndBlankLines() {
        val pgn = "﻿[Event \"X\"]\r\n[Site \"?\"]\r\n[Date \"????.??.??\"]\r\n" +
            "[Round \"1\"]\r\n[White \"A\"]\r\n[Black \"B\"]\r\n[Result \"*\"]\r\n\r\n\r\n" +
            "1. e4 e5\r\n2. Nf3 Nc6 *\r\n"
        val games = PgnParser.parse(pgn)
        assertEquals(1, games.size)
        assertEquals(4, games[0].moves.size)
        replayAndVerify(games[0])
    }
}
