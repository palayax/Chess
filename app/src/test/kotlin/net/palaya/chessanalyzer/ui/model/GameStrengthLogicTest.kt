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
    private val auto = AnalysisLevel(14, autoDepth = true)

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
    fun theChooserOffersTheFiveLevelsInOrderAndMarksTheCurrentOne() {
        val choices = strengthChoices(AnalysisLevel.of(EngineLevel.L2900))
        assertEquals(
            listOf(EngineLevel.L2500, EngineLevel.L2700, EngineLevel.L2900, EngineLevel.L3100, EngineLevel.MAX),
            choices.map { it.level },
        )
        assertEquals(listOf(false, false, true, false, false), choices.map { it.isCurrent })
        assertEquals(listOf(false, false, false, false, true), strengthChoices(AnalysisLevel.of(EngineLevel.MAX)).map { it.isCurrent })
    }

    @Test
    fun aGameFromBeforeTheLevelsMarksNoneAsCurrentAndKeepsItsLegacyStrength() {
        for (old in listOf(standard, quick, deep, auto, AnalysisLevel(16))) {
            assertTrue(strengthChoices(old).none { it.isCurrent })
            assertNull(old.engineLevel)
        }
        assertEquals(AnalysisStrength.AUTO, auto.strength)
        assertNull(AnalysisStrength.fromDepth(16))
        // A level has no legacy strength.
        assertNull(AnalysisLevel.of(EngineLevel.L3100).strength)
    }

    @Test
    fun reanalyseNeedsAPickThatDiffersFromTheCurrentLevel() {
        val current = AnalysisLevel.of(EngineLevel.L2900)
        assertFalse(canReanalyse(current, picked = null))
        assertFalse(canReanalyse(current, picked = EngineLevel.L2900))
        assertTrue(canReanalyse(current, picked = EngineLevel.L3100))
        assertTrue(canReanalyse(current, picked = EngineLevel.MAX))
        // An old game can be re-analysed at any level, including the one pre-selected for it.
        assertTrue(canReanalyse(standard, picked = EngineLevel.L2900))
        assertTrue(canReanalyse(auto, picked = EngineLevel.L3100))
    }

    @Test
    fun aLevelGameIsReopenedAtItsOwnLevelWhateverSettingsSaysNow() {
        val l = AnalysisLevel.of(EngineLevel.L2500)
        assertEquals(l, gameAnalysisLevel(explicit = null, stored = l, settingsDefault = AnalysisLevel.of(EngineLevel.MAX)))
    }

    @Test
    fun theLegacyStrengthsStillHaveDifferentEvalCacheKeysAndTheLevelsDifferFromThem() {
        val legacy = AnalysisStrength.entries.map { if (it.autoDepth) AutoDepthBudget.cacheKeyPart else it.budget.cacheKeyPart }
        assertEquals(4, legacy.toSet().size)
        val levels = EngineLevel.entries.map { SearchBudget.forLevel(it).cacheKeyPart }
        assertEquals(5, levels.toSet().size)
        assertTrue((legacy + levels).toSet().size == 9)
    }
}
