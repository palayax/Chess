package net.palaya.chessanalyzer.core.chess

import org.junit.Assert.assertEquals
import org.junit.Test

class PerftTest {

    @Test
    fun startPosition() {
        val pos = Position.startPosition()
        assertEquals(20L, pos.perft(1))
        assertEquals(400L, pos.perft(2))
        assertEquals(8902L, pos.perft(3))
        assertEquals(197281L, pos.perft(4))
        assertEquals(4865609L, pos.perft(5))
    }

    @Test
    fun kiwipete() {
        val pos = Position.fromFen("r3k2r/p1ppqpb1/bn2pnp1/3PN3/1p2P3/2N2Q1p/PPPBBPPP/R3K2R w KQkq - 0 1")
        assertEquals(48L, pos.perft(1))
        assertEquals(2039L, pos.perft(2))
        assertEquals(97862L, pos.perft(3))
        assertEquals(4085603L, pos.perft(4))
    }

    @Test
    fun position3() {
        val pos = Position.fromFen("8/2p5/3p4/KP5r/1R3p1k/8/4P1P1/8 w - - 0 1")
        assertEquals(14L, pos.perft(1))
        assertEquals(191L, pos.perft(2))
        assertEquals(2812L, pos.perft(3))
        assertEquals(43238L, pos.perft(4))
        assertEquals(674624L, pos.perft(5))
    }

    @Test
    fun position4() {
        val pos = Position.fromFen("r3k2r/Pppp1ppp/1b3nbN/nP6/BBP1P3/q4N2/Pp1P2PP/R2Q1RK1 w kq - 0 1")
        assertEquals(6L, pos.perft(1))
        assertEquals(264L, pos.perft(2))
        assertEquals(9467L, pos.perft(3))
        assertEquals(422333L, pos.perft(4))
    }

    @Test
    fun position5() {
        val pos = Position.fromFen("rnbq1k1r/pp1Pbppp/2p5/8/2B5/8/PPP1NnPP/RNBQK2R w KQ - 1 8")
        assertEquals(44L, pos.perft(1))
        assertEquals(1486L, pos.perft(2))
        assertEquals(62379L, pos.perft(3))
        assertEquals(2103487L, pos.perft(4))
    }
}
