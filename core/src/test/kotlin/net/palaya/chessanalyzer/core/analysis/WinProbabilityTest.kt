package net.palaya.chessanalyzer.core.analysis

import net.palaya.chessanalyzer.core.chess.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class WinProbabilityTest {

    private fun eval(fen: String, cp: Int? = null, mateIn: Int? = null, multiPv: Int = 1): PositionEval =
        PositionEval(
            fen = fen,
            lines = listOf(EngineLineInput(multiPv = multiPv, scoreCp = cp, mateIn = mateIn, depth = 18, pvUci = emptyList())),
            depth = 18
        )

    @Test
    fun `cpFromMate saturates and preserves sign`() {
        assertEquals(10000 - 50, WinProbability.cpFromMate(1))
        assertEquals(-(10000 - 50), WinProbability.cpFromMate(-1))
        // clamps distance at 40
        assertEquals(10000 - 40 * 50, WinProbability.cpFromMate(40))
        assertEquals(10000 - 40 * 50, WinProbability.cpFromMate(100))
    }

    @Test
    fun `winPercent of 0 cp is exactly 50`() {
        assertEquals(50.0, WinProbability.winPercent(0), 1e-9)
    }

    @Test
    fun `winPercent is monotonic and symmetric around 0`() {
        val low = WinProbability.winPercent(-300)
        val mid = WinProbability.winPercent(0)
        val high = WinProbability.winPercent(300)
        assertTrue(low < mid)
        assertTrue(mid < high)
        assertEquals(100.0, low + high, 1e-9)
    }

    @Test
    fun `winPercent clamps beyond 1000cp`() {
        assertEquals(WinProbability.winPercent(1000), WinProbability.winPercent(5000), 1e-9)
        assertEquals(WinProbability.winPercent(-1000), WinProbability.winPercent(-5000), 1e-9)
    }

    @Test
    fun `winPercent hand-computed value at 100cp`() {
        // 50 + 50 * tanh(0.00368208 * 100) ~= 50 + 50*tanh(0.368208)
        val expected = 50.0 + 50.0 * (2.0 / (1.0 + Math.exp(-0.00368208 * 100)) - 1.0)
        assertEquals(expected, WinProbability.winPercent(100), 1e-9)
        assertTrue(abs(WinProbability.winPercent(100) - 59.1) < 0.5)
    }

    @Test
    fun `mate score gives 100 for mating side and 0 for mated side`() {
        val matingLine = EngineLineInput(1, null, mateIn = 3, depth = 18, pvUci = emptyList())
        val matedLine = EngineLineInput(1, null, mateIn = -3, depth = 18, pvUci = emptyList())
        assertEquals(100.0, WinProbability.winPercentOfLine(matingLine), 1e-9)
        assertEquals(0.0, WinProbability.winPercentOfLine(matedLine), 1e-9)
    }

    // --- Perspective / sign-flip tests -------------------------------------------------

    @Test
    fun `winPercentForColor reads perspective from the FEN side to move, not an assumption`() {
        // Black to move, scoreCp is +200 FOR BLACK (per EngineLineInput contract: side-to-move
        // relative). A White/Black flip bug would report White as favored here.
        val blackToMoveFen = "8/8/8/8/8/8/8/4k2K b - - 0 1"
        val e = eval(blackToMoveFen, cp = 200)

        val blackWin = WinProbability.winPercentForColor(e, Color.BLACK)
        val whiteWin = WinProbability.winPercentForColor(e, Color.WHITE)

        assertTrue("Black should be favored (was $blackWin)", blackWin > 55.0)
        assertTrue("White should be disfavored (was $whiteWin)", whiteWin < 45.0)
        assertEquals(100.0, blackWin + whiteWin, 1e-9)
    }

    @Test
    fun `winPercentForColor agrees with white-relative reading when white to move`() {
        val whiteToMoveFen = "8/8/8/8/8/8/8/4k2K w - - 0 1"
        val e = eval(whiteToMoveFen, cp = 200)
        val whiteWin = WinProbability.winPercentForColor(e, Color.WHITE)
        assertTrue(whiteWin > 55.0)
        assertEquals(WinProbability.winPercent(200), whiteWin, 1e-9)
    }

    @Test
    fun `cpWhiteRelative flips sign only when black is to move`() {
        val whiteToMove = eval("8/8/8/8/8/8/8/4k2K w - - 0 1", cp = 150)
        val blackToMove = eval("8/8/8/8/8/8/8/4k2K b - - 0 1", cp = 150)
        assertEquals(150, WinProbability.cpWhiteRelative(whiteToMove))
        assertEquals(-150, WinProbability.cpWhiteRelative(blackToMove))
    }

    @Test
    fun `loss is zero when the mover keeps the same win percent`() {
        val before = eval("8/8/8/8/8/8/8/4k2K w - - 0 1", cp = 100)
        // After White plays, Black to move; from White's perspective the eval should mirror.
        val after = eval("8/8/8/8/8/8/8/4k2K b - - 0 1", cp = -100)
        assertEquals(0.0, WinProbability.loss(before, after, Color.WHITE), 1e-6)
    }

    @Test
    fun `loss is positive when the mover's win percent drops`() {
        // White to move, winning by 500cp before...
        val before = eval("8/8/8/8/8/8/8/4k2K w - - 0 1", cp = 500)
        // ...but after White's move (Black to move), Black is now also winning by 500 for
        // itself, i.e. White's position collapsed.
        val after = eval("8/8/8/8/8/8/8/4k2K b - - 0 1", cp = 500)
        val loss = WinProbability.loss(before, after, Color.WHITE)
        assertTrue("Expected a large loss for White, got $loss", loss > 50.0)
    }

    @Test
    fun `loss for black mover uses black's own perspective, not white's`() {
        // Black to move, Black winning by 500 (good for black) before.
        val before = eval("8/8/8/8/8/8/8/4k2K b - - 0 1", cp = 500)
        // After Black's move, White to move, White eval is +500 for White = bad for Black.
        val after = eval("8/8/8/8/8/8/8/4k2K w - - 0 1", cp = 500)
        val loss = WinProbability.loss(before, after, Color.BLACK)
        assertTrue("Expected a large loss for Black, got $loss", loss > 50.0)

        // A buggy implementation that always computed loss from White's perspective would
        // instead report ~0 loss here (White's own winPercent barely moved sign-wise for
        // itself), so this assertion specifically catches that class of bug.
        val wronglyComputed = WinProbability.loss(before, after, Color.WHITE)
        assertTrue(loss > wronglyComputed)
    }

    @Test
    fun `mate in zero means the side to move is mated, so it saturates negative`() {
        // AnalysisService records a checkmated position as mateIn = 0. It used to come back as
        // +10000, which gave every mating move a wrong-sign centipawn value.
        assertTrue(WinProbability.cpFromMate(0) < 0)
        assertEquals(-10000, WinProbability.cpFromMate(0))
        assertEquals(0.0, WinProbability.winPercentOfLine(
            EngineLineInput(multiPv = 1, scoreCp = null, mateIn = 0, depth = 0, pvUci = emptyList())), 0.0)
    }
}
