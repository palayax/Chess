package net.palaya.chessanalyzer.ui.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Per-game analysis strength (A4): which depth a game is analysed at, and what the chooser offers. */
class GameStrengthLogicTest {

    @Test
    fun aNewGameUsesTheSettingsDefault() {
        assertEquals(14, gameAnalysisDepth(explicit = null, stored = null, settingsDefault = 14))
        assertEquals(18, gameAnalysisDepth(explicit = null, stored = null, settingsDefault = 18))
    }

    @Test
    fun aReopenedGameKeepsTheStrengthItWasAnalysedAtWhateverSettingsSaysNow() {
        assertEquals(12, gameAnalysisDepth(explicit = null, stored = 12, settingsDefault = 18))
        assertEquals(18, gameAnalysisDepth(explicit = null, stored = 18, settingsDefault = 12))
    }

    @Test
    fun anExplicitPickBeatsBothTheStoredStrengthAndTheDefault() {
        assertEquals(18, gameAnalysisDepth(explicit = 18, stored = 12, settingsDefault = 14))
    }

    @Test
    fun theChooserOffersQuickStandardDeepInThatOrderAndMarksTheCurrentOne() {
        val choices = strengthChoices(AnalysisStrength.STANDARD.depth)
        assertEquals(listOf(AnalysisStrength.QUICK, AnalysisStrength.STANDARD, AnalysisStrength.DEEP), choices.map { it.strength })
        assertEquals(listOf(false, true, false), choices.map { it.isCurrent })
    }

    @Test
    fun aCustomDepthMarksNoneOfThePresetsAsCurrent() {
        assertTrue(strengthChoices(16).none { it.isCurrent })
        assertNull(AnalysisStrength.fromDepth(16))
    }

    @Test
    fun reanalyseNeedsAPickThatDiffersFromTheCurrentStrength() {
        assertFalse(canReanalyse(currentDepth = 14, picked = null))
        assertFalse(canReanalyse(currentDepth = 14, picked = AnalysisStrength.STANDARD))
        assertTrue(canReanalyse(currentDepth = 14, picked = AnalysisStrength.DEEP))
        assertTrue(canReanalyse(currentDepth = 14, picked = AnalysisStrength.QUICK))
        // A custom depth can be replaced by any preset.
        assertTrue(canReanalyse(currentDepth = 16, picked = AnalysisStrength.STANDARD))
    }

    @Test
    fun theThreeStrengthsHaveDifferentSearchBudgetsSoTheEvalCacheKeepsThemApart() {
        val keys = AnalysisStrength.entries.map { it.budget.cacheKeyPart }
        assertEquals(3, keys.toSet().size)
    }
}
