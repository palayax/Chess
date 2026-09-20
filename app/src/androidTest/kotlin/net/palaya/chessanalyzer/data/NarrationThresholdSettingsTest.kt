package net.palaya.chessanalyzer.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import net.palaya.chessanalyzer.core.narration.NarrationOptions
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The narration significance threshold (ANALYSIS_SPEC §9.2) as the user actually experiences it:
 * a value that survives being written to real on-device DataStore and read back.
 *
 * Read back via [SettingsRepository.current], not via the eagerly-seeded StateFlow on
 * `AnalysisViewModel`, for the reason recorded in CLAUDE.md — that flow serves a default until
 * DataStore's first emission lands, so asserting against it would pass even if nothing were
 * persisted at all.
 *
 * Instrumented tests share one DataStore across the whole run, so this restores the default
 * afterwards rather than assuming it.
 */
@RunWith(AndroidJUnit4::class)
class NarrationThresholdSettingsTest {

    private val repo = SettingsRepository(InstrumentationRegistry.getInstrumentation().targetContext)

    @Test
    fun thresholdRoundTripsThroughRealStorage(): Unit = runBlocking {
        try {
            repo.setNarrationThresholdCp(120)
            assertEquals(120, repo.current().narrationThresholdCp)

            // Zero is a real setting — "narrate every candidate move" — not a missing value.
            repo.setNarrationThresholdCp(0)
            assertEquals(0, repo.current().narrationThresholdCp)
        } finally {
            repo.setNarrationThresholdCp(SettingsRepository.DEFAULT_NARRATION_THRESHOLD_CP)
        }
    }

    @Test
    fun thresholdIsClampedToTheDocumentedRange(): Unit = runBlocking {
        try {
            repo.setNarrationThresholdCp(-500)
            assertEquals(0, repo.current().narrationThresholdCp)

            repo.setNarrationThresholdCp(99_999)
            assertEquals(300, repo.current().narrationThresholdCp)
        } finally {
            repo.setNarrationThresholdCp(SettingsRepository.DEFAULT_NARRATION_THRESHOLD_CP)
        }
    }

    @Test
    fun theAppDefaultIsTheSameHalfPawnCoreDefaultsTo() {
        // Two defaults for one concept is how they drift apart; pin them together.
        assertEquals(
            NarrationOptions.DEFAULT_SIGNIFICANCE_THRESHOLD_CP,
            SettingsRepository.DEFAULT_NARRATION_THRESHOLD_CP,
        )
        assertEquals(50, SettingsRepository.DEFAULT_NARRATION_THRESHOLD_CP)
    }

    @Test
    fun savingAWholeSettingsSnapshotCarriesTheThreshold(): Unit = runBlocking {
        try {
            val current = repo.current()
            repo.save(current.copy(narrationThresholdCp = 200))
            assertEquals(200, repo.current().narrationThresholdCp)
        } finally {
            repo.setNarrationThresholdCp(SettingsRepository.DEFAULT_NARRATION_THRESHOLD_CP)
        }
    }
}
