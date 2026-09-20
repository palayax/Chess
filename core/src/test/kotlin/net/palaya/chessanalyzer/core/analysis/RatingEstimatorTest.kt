package net.palaya.chessanalyzer.core.analysis

import org.junit.Assert.assertEquals
import org.junit.Test

class RatingEstimatorTest {

    @Test
    fun `each spec anchor maps to its expected Elo`() {
        assertEquals(800, RatingEstimator.estimate(60.0))
        assertEquals(1100, RatingEstimator.estimate(70.0))
        assertEquals(1300, RatingEstimator.estimate(75.0))
        assertEquals(1500, RatingEstimator.estimate(80.0))
        assertEquals(1800, RatingEstimator.estimate(85.0))
        assertEquals(2100, RatingEstimator.estimate(90.0))
        assertEquals(2500, RatingEstimator.estimate(95.0))
    }

    @Test
    fun `interpolates between anchors`() {
        // Halfway between 60->800 and 70->1100 should be 65 -> 950.
        assertEquals(950, RatingEstimator.estimate(65.0))
    }

    @Test
    fun `extrapolates below the lowest anchor using its slope`() {
        // Slope from 60->800 to 70->1100 is 30 elo per accuracy point; at accuracy 50 that's
        // 800 - 10*30 = 500.
        assertEquals(500, RatingEstimator.estimate(50.0))
    }

    @Test
    fun `extrapolates above the highest anchor using its slope`() {
        // Slope from 90->2100 to 95->2500 is 80 elo per accuracy point; at accuracy 100 that's
        // 2500 + 5*80 = 2900.
        assertEquals(2900, RatingEstimator.estimate(100.0))
    }

    @Test
    fun `clamps to 100 at the low end`() {
        assertEquals(100, RatingEstimator.estimate(0.0))
    }

    @Test
    fun `clamps to 3000 at the high end`() {
        // A pathological, impossible accuracy must not exceed the clamp.
        assertEquals(3000, RatingEstimator.estimate(1000.0))
    }

    @Test
    fun `lowConfidence threshold constant matches the spec's 20-ply cutoff`() {
        assertEquals(20, RatingEstimator.LOW_CONFIDENCE_PLY_THRESHOLD)
    }
}
