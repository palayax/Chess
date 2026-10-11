package net.palaya.chessanalyzer.ui.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Per-game analysis strength (A4, Auto since §8.5): which level a game is analysed at, and what the chooser offers. */
class GameStrengthLogicTest {

    private val standard = AnalysisLevel(14)
    private val quick = AnalysisLevel(12)
    private val deep = AnalysisLevel(18)
    private val auto = AnalysisLevel.of(AnalysisStrength.AUTO)

    @Test
    fun aNewGameUsesTheSettingsDefault() {
        assertEquals(standard, gameAnalysisLevel(explicit = null, stored = null, settingsDefault = standard))
        assertEquals(deep, gameAnalysisLevel(explicit = null, stored = null, settingsDefault = deep))
        assertEquals(auto, gameAnalysisLevel(explicit = null, stored = null, settingsDefault = auto))
    }

    @Test
    fun aReopenedGameKeepsTheStrengthItWasAnalysedAtWhateverSettingsSaysNow() {
        assertEquals(quick, gameAnalysisLevel(explicit = null, stored = quick, settingsDefault = deep))
        assertEquals(deep, gameAnalysisLevel(explicit = null, stored = deep, settingsDefault = quick))
    }

    @Test
    fun aReopenedAutoGameStaysAutoEvenWhenSettingsSaysStandardWhichHasTheSameDepth() {
        val level = gameAnalysisLevel(explicit = null, stored = AnalysisLevel(14, autoDepth = true), settingsDefault = standard)
        assertEquals(AnalysisStrength.AUTO, level.strength)
        // And a Standard game stays Standard under an Auto default.
        assertEquals(AnalysisStrength.STANDARD, gameAnalysisLevel(null, standard, auto).strength)
    }

    @Test
    fun anExplicitPickBeatsBothTheStoredStrengthAndTheDefault() {
        assertEquals(deep, gameAnalysisLevel(explicit = deep, stored = quick, settingsDefault = standard))
        assertEquals(auto, gameAnalysisLevel(explicit = auto, stored = standard, settingsDefault = standard))
    }

    @Test
    fun theChooserOffersQuickStandardDeepAutoInThatOrderAndMarksTheCurrentOne() {
        val choices = strengthChoices(standard)
        assertEquals(
            listOf(AnalysisStrength.QUICK, AnalysisStrength.STANDARD, AnalysisStrength.DEEP, AnalysisStrength.AUTO),
            choices.map { it.strength },
        )
        assertEquals(listOf(false, true, false, false), choices.map { it.isCurrent })
        assertEquals(listOf(false, false, false, true), strengthChoices(auto).map { it.isCurrent })
    }

    @Test
    fun aCustomDepthMarksNoneOfThePresetsAsCurrent() {
        assertTrue(strengthChoices(AnalysisLevel(16)).none { it.isCurrent })
        assertNull(AnalysisStrength.fromDepth(16))
    }

    @Test
    fun reanalyseNeedsAPickThatDiffersFromTheCurrentStrength() {
        assertFalse(canReanalyse(standard, picked = null))
        assertFalse(canReanalyse(standard, picked = AnalysisStrength.STANDARD))
        assertTrue(canReanalyse(standard, picked = AnalysisStrength.DEEP))
        assertTrue(canReanalyse(standard, picked = AnalysisStrength.QUICK))
        // Auto has Standard's depth, but it is another strength.
        assertTrue(canReanalyse(standard, picked = AnalysisStrength.AUTO))
        assertTrue(canReanalyse(auto, picked = AnalysisStrength.STANDARD))
        assertFalse(canReanalyse(auto, picked = AnalysisStrength.AUTO))
        // A custom depth can be replaced by any preset.
        assertTrue(canReanalyse(AnalysisLevel(16), picked = AnalysisStrength.STANDARD))
    }

    @Test
    fun theLevelOfAStrengthReadsBackAsThatStrength() {
        for (s in AnalysisStrength.entries) assertEquals(s, AnalysisLevel.of(s).strength)
    }

    @Test
    fun theFourStrengthsHaveDifferentEvalCacheKeysSoTheirResultsStayApart() {
        val keys = AnalysisStrength.entries.map { if (it.autoDepth) AutoDepthBudget.cacheKeyPart else it.budget.cacheKeyPart }
        assertEquals(4, keys.toSet().size)
    }
}
