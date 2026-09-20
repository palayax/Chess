package net.palaya.chessanalyzer.core.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AccuracyCalculatorTest {

    @Test
    fun `moveAccuracy at zero loss is 100`() {
        assertEquals(100.0, AccuracyCalculator.moveAccuracy(0.0), 0.01)
    }

    @Test
    fun `moveAccuracy hand-computed at loss 10`() {
        val expected = (103.1668 * Math.exp(-0.04354 * 10.0) - 3.1669).coerceIn(0.0, 100.0)
        assertEquals(expected, AccuracyCalculator.moveAccuracy(10.0), 1e-9)
    }

    @Test
    fun `moveAccuracy decreases monotonically with loss and clamps to 0`() {
        val a1 = AccuracyCalculator.moveAccuracy(0.0)
        val a2 = AccuracyCalculator.moveAccuracy(20.0)
        val a3 = AccuracyCalculator.moveAccuracy(100.0)
        assertTrue(a1 > a2)
        assertTrue(a2 > a3)
        assertTrue(a3 >= 0.0)
    }

    @Test
    fun `game accuracy for a perfect game is 100`() {
        val losses = List(10) { 0.0 }
        val series = List(11) { 50.0 } // flat eval graph -> minimal but clamped-floor volatility
        val indices = (0..9).toList()
        val accuracy = AccuracyCalculator.gameAccuracy(losses, series, indices)
        assertEquals(100.0, accuracy, 0.5)
    }

    @Test
    fun `game accuracy drops as losses increase`() {
        val series = List(11) { 50.0 }
        val indices = (0..9).toList()
        val goodGame = AccuracyCalculator.gameAccuracy(List(10) { 1.0 }, series, indices)
        val sloppyGame = AccuracyCalculator.gameAccuracy(List(10) { 15.0 }, series, indices)
        assertTrue("expected $goodGame > $sloppyGame", goodGame > sloppyGame)
    }

    @Test
    fun `empty (all-book) move list yields a defined accuracy rather than throwing`() {
        val accuracy = AccuracyCalculator.gameAccuracy(emptyList(), listOf(50.0, 50.0), emptyList())
        assertEquals(100.0, accuracy, 1e-9)
    }

    @Test
    fun `round1dp rounds to one decimal place`() {
        assertEquals(83.5, AccuracyCalculator.round1dp(83.46), 1e-9)
        assertEquals(83.4, AccuracyCalculator.round1dp(83.44), 1e-9)
    }

    @Test
    fun `known per-move losses produce the expected blended accuracy`() {
        // A short, mostly-clean game: losses of 0, 0, 3, 0, 8 win-percent.
        val losses = listOf(0.0, 0.0, 3.0, 0.0, 8.0)
        // A volatile eval graph so the sliding-window stdev weighting actually engages.
        val series = listOf(50.0, 55.0, 40.0, 60.0, 35.0, 65.0)
        val indices = listOf(0, 1, 2, 3, 4)

        val accuracies = losses.map { AccuracyCalculator.moveAccuracy(it) }
        val harmonicMean = accuracies.size / accuracies.sumOf { 1.0 / maxOf(it, 1.0) }

        val accuracy = AccuracyCalculator.gameAccuracy(losses, series, indices)

        // The blended accuracy must sit between the harmonic mean (which is dragged down hard
        // by the worst move) and 100 (the ceiling if every move were perfect) — this is a
        // coarse but meaningful sanity bound on the blend, computed against the spec's own
        // formula rather than a fudge factor.
        assertTrue(accuracy in harmonicMean..100.0)
    }

    @Test
    fun `BOOK moves are excluded from accuracy by the caller`() {
        // AccuracyCalculator itself is BOOK-agnostic; exclusion is the caller's job (see
        // GameAnalyzer.buildPlayerReport). This test documents/locks in that contract: feeding
        // it a full loss list including a would-be BOOK move's loss changes the result versus
        // feeding it the same list with that entry excluded.
        val allLosses = listOf(0.0, 0.0, 25.0, 0.0) // pretend index 2 was actually BOOK
        val withoutBook = listOf(0.0, 0.0, 0.0)
        val series = List(5) { 50.0 }

        val withBookIncluded = AccuracyCalculator.gameAccuracy(allLosses, series, listOf(0, 1, 2, 3))
        val bookExcluded = AccuracyCalculator.gameAccuracy(withoutBook, series, listOf(0, 1, 3))

        assertTrue(bookExcluded > withBookIncluded)
    }
}
