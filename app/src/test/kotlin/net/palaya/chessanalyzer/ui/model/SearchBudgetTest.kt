package net.palaya.chessanalyzer.ui.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The per-position search budget per strength (F1, docs/ANALYSIS_SPEC.md §8.1). The numbers are the
 * calibrated ones from the spec's table; changing one without re-measuring breaks this on purpose.
 */
class SearchBudgetTest {

    @Test
    fun eachStrengthHasItsCalibratedBudget() {
        assertEquals(SearchBudget(nodes = 4_000_000L, movetimeMs = 30_000L, typicalNodes = 500_000L), AnalysisStrength.QUICK.budget)
        assertEquals(SearchBudget(nodes = 25_000_000L, movetimeMs = 150_000L, typicalNodes = 3_450_000L), AnalysisStrength.STANDARD.budget)
        assertEquals(SearchBudget(nodes = 45_000_000L, movetimeMs = 270_000L, typicalNodes = 10_300_000L), AnalysisStrength.DEEP.budget)
    }

    @Test
    fun theTypicalNodeCountIsBelowTheBudgetAndNotPartOfTheCacheKey() {
        for (s in AnalysisStrength.entries) assertTrue(s.name, s.budget.typicalNodes in 1 until s.budget.nodes)
        assertEquals(AnalysisStrength.DEEP.budget.cacheKeyPart, AnalysisStrength.DEEP.budget.copy(typicalNodes = 1L).cacheKeyPart)
    }

    @Test
    fun aStrongerStrengthNeverGetsLessRoom() {
        val budgets = AnalysisStrength.entries.sortedBy { it.depth }.map { it.budget }
        for ((weaker, stronger) in budgets.zipWithNext()) {
            assertTrue("$weaker vs $stronger", stronger.nodes > weaker.nodes)
            assertTrue("$weaker vs $stronger", stronger.movetimeMs >= weaker.movetimeMs)
        }
    }

    /**
     * The time cap is a safety net, never the normal limit: at the slowest engine speed measured on
     * the emulator (spec §8.1) the node budget is spent well before the clock runs out, so a phone
     * that is a few times slower still gets its full node budget.
     */
    @Test
    fun theTimeCapLeavesRoomForAPhoneSlowerThanTheEmulator() {
        for (s in AnalysisStrength.entries) {
            val msAtEmulatorSpeed = s.budget.nodes * 1000 / SLOWEST_EMULATOR_NPS
            assertTrue(
                "${s.name}: ${s.budget.movetimeMs} ms vs $msAtEmulatorSpeed ms to spend the budget on the emulator",
                s.budget.movetimeMs >= SLOWER_PHONE_FACTOR * msAtEmulatorSpeed,
            )
        }
    }

    @Test
    fun theSettingsDepthMapsToItsStrengthsBudget() {
        assertEquals(AnalysisStrength.QUICK.budget, EngineSettings(depth = 12).searchBudget)
        assertEquals(AnalysisStrength.STANDARD.budget, EngineSettings().searchBudget)
        assertEquals(AnalysisStrength.DEEP.budget, EngineSettings(depth = 18).searchBudget)
    }

    @Test
    fun anOldCustomDepthGetsTheBudgetOfTheNextPresetUp() {
        assertEquals(AnalysisStrength.QUICK.budget, SearchBudget.forDepth(6))
        assertEquals(AnalysisStrength.STANDARD.budget, SearchBudget.forDepth(13))
        assertEquals(AnalysisStrength.DEEP.budget, SearchBudget.forDepth(16))
        assertEquals(AnalysisStrength.DEEP.budget, SearchBudget.forDepth(24))
    }

    @Test
    fun theBudgetIsPartOfTheCacheKeyText() {
        assertEquals("n${AnalysisStrength.DEEP.budget.nodes}:t${AnalysisStrength.DEEP.budget.movetimeMs}", AnalysisStrength.DEEP.budget.cacheKeyPart)
        assertNotEquals(AnalysisStrength.QUICK.budget.cacheKeyPart, AnalysisStrength.DEEP.budget.cacheKeyPart)
    }

    private companion object {
        /**
         * Engine speed on chess34 (4 threads) in F1: the slowest search of more than 10 M nodes in
         * two full game01 Deep runs was 510 k nodes/s (median 570 k), rounded down (spec §8.1).
         */
        const val SLOWEST_EMULATOR_NPS = 500_000L
        /** How much slower than the emulator a phone may be and still spend its node budget. */
        const val SLOWER_PHONE_FACTOR = 3
    }
}
