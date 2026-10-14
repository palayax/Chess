package net.palaya.chessanalyzer.ui.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Elo engine levels and the game time budget (docs/ANALYSIS_SPEC.md §8.6). */
class EngineLevelTest {

    @Test
    fun theLevelsHaveTheirCodesAndNodeBudgets() {
        assertEquals(listOf(2500, 2700, 2900, 3100, 4000), EngineLevel.entries.map { it.code })
        assertEquals(listOf<Long?>(20_000L, 60_000L, 180_000L, 600_000L, null), EngineLevel.entries.map { it.nodes })
        assertEquals(EngineLevel.L3100, EngineLevel.DEFAULT)
        assertNull(EngineLevel.MAX.elo)
        assertEquals(2900, EngineLevel.L2900.elo)
        // More strength is never fewer nodes.
        val nodes = EngineLevel.entries.mapNotNull { it.nodes }
        assertEquals(nodes.sorted(), nodes)
    }

    @Test
    fun codesAreDistinguishedFromLegacyDepths() {
        for (l in EngineLevel.entries) {
            assertTrue(EngineLevel.isLevelCode(l.code))
            assertEquals(l, EngineLevel.fromCode(l.code))
            assertTrue(l.code >= EngineLevel.CODE_THRESHOLD)
        }
        for (depth in 6..30) {
            assertFalse(EngineLevel.isLevelCode(depth))
            assertNull(EngineLevel.fromCode(depth))
        }
        assertFalse(EngineLevel.isLevelCode(3000))
    }

    @Test
    fun aLegacyDepthPreselectsTheNearestLevel() {
        assertEquals(EngineLevel.L2700, EngineLevel.forLegacyDepth(12, false))
        assertEquals(EngineLevel.L2700, EngineLevel.forLegacyDepth(6, false))
        assertEquals(EngineLevel.L2900, EngineLevel.forLegacyDepth(13, false))
        assertEquals(EngineLevel.L2900, EngineLevel.forLegacyDepth(14, false))
        assertEquals(EngineLevel.L3100, EngineLevel.forLegacyDepth(18, false))
        assertEquals(EngineLevel.L3100, EngineLevel.forLegacyDepth(26, false))
        assertEquals(EngineLevel.L3100, EngineLevel.forLegacyDepth(14, true))
        assertEquals(EngineLevel.L2500, EngineLevel.fromStored(2500))
        assertEquals(EngineLevel.L2900, EngineLevel.fromStored(14))
    }

    // ---- GameTimeBudget ----

    /** Runs a whole game where every non-terminal search uses its full movetime; returns the total time spent. */
    private fun spend(budget: GameTimeBudget, positions: Int, terminalEvery: Int = 0): Long {
        var spent = 0L
        for (i in 0 until positions) {
            if (terminalEvery > 0 && i % terminalEvery == terminalEvery - 1) continue // terminal: costs nothing
            spent += budget.movetimeFor(positions - i, spent)
        }
        return spent
    }

    @Test
    fun aGameWhoseSearchesUseTheirWholeMovetimeStaysWithinTheBudget() {
        val total = 300_000L
        for (n in listOf(1, 2, 3, 10, 41, 81, 150, 300, 600, 1500)) {
            val b = GameTimeBudget(total)
            val spent = spend(b, n)
            assertTrue("n=$n spent=$spent", spent <= total * 11 / 10 + n * b.minPerPositionMs)
            // And it really uses the budget (the search is not starved) unless the minimum per position dominates.
            if (n * b.minPerPositionMs < total) assertTrue("n=$n spent=$spent", spent >= total * 9 / 10)
        }
    }

    @Test
    fun terminalPositionsCostNothingAndLeaveTimeToTheOthers() {
        val b = GameTimeBudget(300_000L)
        val spent = spend(b, 80, terminalEvery = 40)
        assertTrue(spent <= 330_000L + 80 * 250)
    }

    @Test
    fun theFirstSearchGetsAFairShareTimes1_5AndNeverLessThanTheMinimum() {
        val b = GameTimeBudget(300_000L)
        assertEquals(5_625L, b.movetimeFor(positionsLeft = 80, spentMs = 0L)) // 300000 / 80 x 1.5
        // A huge game: the minimum per position holds.
        assertEquals(250L, b.movetimeFor(positionsLeft = 5_000, spentMs = 0L))
        // Budget used up: still the minimum, never zero or negative.
        assertEquals(250L, b.movetimeFor(positionsLeft = 10, spentMs = 400_000L))
    }

    @Test
    fun theLastPositionNeverGetsMoreThanTheTimeLeft() {
        val b = GameTimeBudget(300_000L)
        assertEquals(100_000L, b.movetimeFor(positionsLeft = 1, spentMs = 200_000L))
        assertEquals(300_000L, b.movetimeFor(positionsLeft = 1, spentMs = 0L))
        assertEquals(100_000L, b.leftMs(200_000L))
        assertEquals(0L, b.leftMs(999_999L))
    }
}
