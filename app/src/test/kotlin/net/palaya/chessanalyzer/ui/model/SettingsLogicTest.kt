package net.palaya.chessanalyzer.ui.model

import net.palaya.chessanalyzer.data.SettingsRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The pure logic behind the rebuilt Settings screen (UX step U9). */
class SettingsLogicTest {

    // ---- Analysis strength: Quick / Standard / Deep = 12 / 14 / 18 ----

    @Test
    fun strengthPresetsMapToTheDesignedDepths() {
        assertEquals(12, AnalysisStrength.QUICK.depth)
        assertEquals(14, AnalysisStrength.STANDARD.depth)
        assertEquals(18, AnalysisStrength.DEEP.depth)
    }

    @Test
    fun standardIsTheStoredDefaultDepth() {
        // Two defaults for one concept drift apart; the preset and the repository default are one number.
        assertEquals(SettingsRepository.DEFAULT_DEPTH, AnalysisStrength.STANDARD.depth)
        assertEquals(AnalysisStrength.STANDARD, AnalysisStrength.fromDepth(EngineSettings().depth))
    }

    @Test
    fun everyPresetDepthReadsBackAsItself() {
        for (preset in AnalysisStrength.entries) {
            assertEquals(preset, AnalysisStrength.fromDepth(preset.depth))
        }
    }

    @Test
    fun depthsBetweenAndAroundThePresetsAreCustom() {
        // Between presets, the old default of earlier builds' sliders, and one off each preset.
        for (depth in listOf(13, 15, 16, 17, 11, 19, 24)) {
            assertNull("depth $depth", AnalysisStrength.fromDepth(depth))
        }
    }

    @Test
    fun depthsAtTheClampsAreCustomAndKeepTheirValue() {
        // The repository clamps to 6..30; both ends are outside the presets and must read "Custom".
        assertNull(AnalysisStrength.fromDepth(6))
        assertNull(AnalysisStrength.fromDepth(30))
        assertEquals("‎6‎", customDepthValue(6))
        assertEquals("‎30‎", customDepthValue(30))
    }

    @Test
    fun customDepthLabelIsLrmWrappedSoRtlCannotReverseIt() {
        val value = customDepthValue(16)
        assertEquals("16", value.filter { it.isDigit() })
        assertTrue(value.startsWith("‎"))
        assertTrue(value.endsWith("‎"))
    }

    @Test
    fun choosingAPresetKeepsEveryOtherSettingAndTheStoredIntIsThePresetDepth() {
        val before = EngineSettings(depth = 16, username = "dor", narrationThresholdCp = 70)
        val after = before.copy(depth = AnalysisStrength.DEEP.depth)
        assertEquals(18, after.depth)
        assertEquals("dor", after.username)
        assertEquals(70, after.narrationThresholdCp)
    }

    // ---- What the review talks about: 100 / 50 / 0 cp ----

    @Test
    fun reviewDetailPresetsMapToTheDesignedThresholds() {
        assertEquals(100, ReviewDetail.ONLY_BIG_MOMENTS.thresholdCp)
        assertEquals(50, ReviewDetail.BALANCED.thresholdCp)
        assertEquals(0, ReviewDetail.EVERY_MOVE.thresholdCp)
    }

    @Test
    fun balancedIsThePinnedDefaultOfFiftyCentipawns() {
        assertEquals(50, SettingsRepository.DEFAULT_NARRATION_THRESHOLD_CP)
        assertEquals(ReviewDetail.BALANCED, ReviewDetail.fromThresholdCp(SettingsRepository.DEFAULT_NARRATION_THRESHOLD_CP))
        assertEquals(ReviewDetail.BALANCED, ReviewDetail.fromThresholdCp(EngineSettings().narrationThresholdCp))
    }

    @Test
    fun everyPresetThresholdReadsBackAsItself() {
        for (preset in ReviewDetail.entries) {
            assertEquals(preset, ReviewDetail.fromThresholdCp(preset.thresholdCp))
        }
    }

    @Test
    fun thresholdsBetweenThePresetsAreCustom() {
        for (cp in listOf(1, 10, 30, 49, 51, 70, 99, 101, 150)) {
            assertNull("cp $cp", ReviewDetail.fromThresholdCp(cp))
        }
    }

    @Test
    fun thresholdsAtTheClampsBehaveAsDocumented() {
        // 0 is the lower clamp AND the "Every move" preset: a real setting, not "Custom".
        assertEquals(ReviewDetail.EVERY_MOVE, ReviewDetail.fromThresholdCp(0))
        // 300 is the upper clamp, above every preset: Custom, shown as +/-3.0.
        assertNull(ReviewDetail.fromThresholdCp(300))
        assertEquals("‎±3.0‎", customThresholdValue(300))
    }

    @Test
    fun customThresholdLabelIsInPawnsWithOneDecimalAndLrmWrapped() {
        assertEquals("‎±0.7‎", customThresholdValue(70))
        assertEquals("‎±0.3‎", customThresholdValue(30))
        assertEquals("‎±1.5‎", customThresholdValue(150))
    }

    @Test
    fun customThresholdUsesADotWhateverTheDeviceLocale() {
        val saved = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale("he", "IL"))
            assertEquals("‎±0.7‎", customThresholdValue(70))
            java.util.Locale.setDefault(java.util.Locale.GERMANY)
            assertEquals("‎±0.7‎", customThresholdValue(70))
        } finally {
            java.util.Locale.setDefault(saved)
        }
    }

    @Test
    fun choosingAThresholdPresetStaysInsideTheRepositoryClamp() {
        for (preset in ReviewDetail.entries) {
            assertTrue(preset.thresholdCp in 0..300)
        }
        for (preset in AnalysisStrength.entries) {
            assertTrue(preset.depth in 6..30)
        }
    }

    // ---- The voice switch ----

    @Test
    fun switchOnWritesTheDeviceVoiceAndOffWritesTheKokoroOne() {
        assertEquals(NarrationProviderChoice.DEVICE, providerForVoiceSwitch(true))
        assertEquals(NarrationProviderChoice.NEURAL, providerForVoiceSwitch(false))
    }

    @Test
    fun switchPositionFollowsTheStoredProvider() {
        assertTrue(voiceSwitchIsOn(NarrationProviderChoice.DEVICE))
        assertFalse(voiceSwitchIsOn(NarrationProviderChoice.NEURAL))
    }

    @Test
    fun switchAndProviderRoundTripForEveryChoice() {
        for (choice in NarrationProviderChoice.entries) {
            assertEquals(choice, providerForVoiceSwitch(voiceSwitchIsOn(choice)))
        }
        for (on in listOf(true, false)) {
            assertEquals(on, voiceSwitchIsOn(providerForVoiceSwitch(on)))
        }
    }

    @Test
    fun aFreshInstallShowsTheSwitchOff() {
        assertFalse(voiceSwitchIsOn(NarrationVoiceSettings().provider))
    }

    // ---- Saved narration audio ----

    @Test
    fun storageIsShownInMegabytesToOneDecimalWithLrm() {
        assertEquals("‎0.0 MB‎", formatStorageMegabytes(0L))
        assertEquals("‎1.0 MB‎", formatStorageMegabytes(1024L * 1024L))
        assertEquals("‎12.4 MB‎", formatStorageMegabytes((12.4 * 1024 * 1024).toLong()))
        assertEquals("‎0.0 MB‎", formatStorageMegabytes(-5L))
    }

    // ---- The Advanced expander ----

    @Test
    fun advancedIsCollapsedByDefault() {
        assertFalse(AdvancedExpander().expanded)
    }

    @Test
    fun oneTapExpandsAndTheNextCollapses() {
        val opened = AdvancedExpander().toggled()
        assertTrue(opened.expanded)
        assertFalse(opened.toggled().expanded)
    }
}
