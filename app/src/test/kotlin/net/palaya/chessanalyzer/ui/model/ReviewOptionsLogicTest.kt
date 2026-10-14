package net.palaya.chessanalyzer.ui.model

import net.palaya.chessanalyzer.core.narration.VideoPace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** RF: the Review options screen's rules (pre-fill precedence, wording row, entry rule, estimates, voice changes). */
class ReviewOptionsLogicTest {

    private val defaults = ReviewDefaults(
        depth = EngineLevel.L3100.code,
        naturalWording = false,
        prepareVideo = true,
        voice = NarrationVoiceSettings(provider = NarrationProviderChoice.NEURAL, speakerId = 1),
        pace = VideoPace.NORMAL,
    )

    @Test
    fun aGameWithNothingStoredOpensWithTheSettingsDefaults() {
        val o = prefillReviewOptions(stored = null, defaults = defaults)
        assertEquals(EngineLevel.L3100, o.level)
        assertEquals(SideChoice.UNKNOWN, o.side)
        assertFalse(o.naturalWording)
        assertTrue(o.prepareVideo)
        assertEquals(NarrationProviderChoice.NEURAL, o.voice)
        assertEquals(1, o.speakerId)
        assertEquals(VideoPace.NORMAL, o.pace)
    }

    @Test
    fun theGamesOwnStoredOptionsWinOverTheDefaultsOneByOne() {
        val stored = StoredReviewOptions(
            depth = EngineLevel.L2900.code,
            naturalWording = true,
            prepareVideo = false,
            voice = NarrationProviderChoice.DEVICE,
            speakerId = 9,
            pace = VideoPace.RELAXED,
        )
        val o = prefillReviewOptions(stored, defaults)
        assertEquals(EngineLevel.L2900, o.level)
        assertTrue(o.naturalWording)
        assertFalse(o.prepareVideo)
        assertEquals(NarrationProviderChoice.DEVICE, o.voice)
        assertEquals(9, o.speakerId)
        assertEquals(VideoPace.RELAXED, o.pace)
        // A field the game never stored falls back to the default, the others keep the game's value.
        val partial = prefillReviewOptions(StoredReviewOptions(depth = EngineLevel.MAX.code), defaults)
        assertEquals(EngineLevel.MAX, partial.level)
        assertEquals(defaults.pace, partial.pace)
        assertEquals(defaults.voice.speakerId, partial.speakerId)
    }

    @Test
    fun theSideIsTheStoredAnswerThenTheInitialSideThenTheUsernameDetection() {
        assertEquals(SideChoice.WHITE, prefillReviewOptions(null, defaults, detectedSide = PieceColor.WHITE).side)
        // A famous game opens as "Not me" even when a name matches.
        assertEquals(SideChoice.NOT_ME, prefillReviewOptions(null, defaults, detectedSide = PieceColor.WHITE, initialSide = SideChoice.NOT_ME).side)
        // An answer stored with the game wins over both.
        assertEquals(
            SideChoice.BLACK,
            prefillReviewOptions(StoredReviewOptions(side = SideChoice.BLACK), defaults, PieceColor.WHITE, SideChoice.NOT_ME).side,
        )
    }

    @Test
    fun anOldGamesLegacyDepthPreselectsTheNearestLevelWithoutChangingTheStoredGame() {
        fun pre(depth: Int, auto: Boolean = false) = prefillReviewOptions(StoredReviewOptions(depth = depth, autoDepth = auto), defaults).level
        assertEquals(EngineLevel.L2700, pre(12))
        assertEquals(EngineLevel.L2700, pre(8))
        assertEquals(EngineLevel.L2900, pre(13))
        assertEquals(EngineLevel.L2900, pre(14))
        assertEquals(EngineLevel.L3100, pre(15))
        assertEquals(EngineLevel.L3100, pre(18))
        assertEquals(EngineLevel.L3100, pre(30))
        assertEquals(EngineLevel.L3100, pre(14, auto = true))
        // A level code is taken as it is.
        for (l in EngineLevel.entries) assertEquals(l, pre(l.code))
    }

    @Test
    fun aLegacySettingsDefaultIsMigratedTheSameWayAndAutoIsNeverOffered() {
        val legacy = ReviewDefaults(
            depth = 14, autoDepth = true, naturalWording = false, prepareVideo = false,
            voice = NarrationVoiceSettings(), pace = VideoPace.DEFAULT,
        )
        assertEquals(EngineLevel.L3100, prefillReviewOptions(null, legacy).level)
        assertEquals(EngineLevel.L2900, prefillReviewOptions(null, legacy.copy(autoDepth = false)).level)
        assertEquals(EngineLevel.L2700, prefillReviewOptions(null, legacy.copy(depth = 12, autoDepth = false)).level)
        assertEquals(5, EngineLevel.entries.size)
    }

    @Test
    fun theWordingRowIsHiddenOfferedOrASwitch() {
        assertEquals(WordingOption.HIDDEN, wordingOption(supported = false, installed = false))
        assertEquals(WordingOption.HIDDEN, wordingOption(supported = false, installed = true))
        assertEquals(WordingOption.DOWNLOAD_IN_SETTINGS, wordingOption(supported = true, installed = false))
        assertEquals(WordingOption.SWITCH, wordingOption(supported = true, installed = true))
    }

    @Test
    fun wordingRunsOnlyWhenAskedForAndTheModelCanRun() {
        val on = prefillReviewOptions(StoredReviewOptions(naturalWording = true), defaults)
        assertTrue(wordingInEffect(on, modelUsable = true))
        assertFalse(wordingInEffect(on, modelUsable = false))
        assertFalse(wordingInEffect(on.copy(naturalWording = false), modelUsable = true))
    }

    @Test
    fun theScreenAlwaysOpensForAReanalyseAndForAnImportOnlyWhileTheSwitchIsOn() {
        assertTrue(showReviewOptions(ReviewEntry.IMPORT, showBeforeEachReview = true))
        assertFalse(showReviewOptions(ReviewEntry.IMPORT, showBeforeEachReview = false))
        for (entry in listOf(ReviewEntry.LIBRARY, ReviewEntry.SUMMARY)) {
            assertTrue(showReviewOptions(entry, showBeforeEachReview = true))
            assertTrue(showReviewOptions(entry, showBeforeEachReview = false))
        }
    }

    @Test
    fun aStrengthEstimateIsShownOnlyFromAMeasuredSpeed() {
        // Not measured on this phone yet: nothing (no invented number).
        assertNull(strengthEstimateMs(positions = 41, typicalNodes = 3_450_000L, msPerNode = null))
        // 41 positions x 3.45 M nodes x 0.001 ms per node.
        assertEquals(141_450L, strengthEstimateMs(positions = 41, typicalNodes = 3_450_000L, msPerNode = 0.001))
        // Already analysed at that strength: ready at once, whatever the speed.
        assertEquals(0L, strengthEstimateMs(positions = 41, typicalNodes = 3_450_000L, msPerNode = null, cached = true))
        assertNull(strengthEstimateMs(positions = 0, typicalNodes = 3_450_000L, msPerNode = 0.001))
        assertNull(strengthEstimateMs(positions = 41, typicalNodes = null, msPerNode = 0.001))
    }

    @Test
    fun aLevelEstimateIsTheSmallerOfNodesTimesSpeedAndTheGameBudget() {
        // 81 positions x 180,000 nodes x 0.002 ms = 29,160 ms: under the budget, so the nodes decide.
        assertEquals(29_160L, levelEstimateMs(EngineLevel.L2900, 81, 0.002))
        // 81 x 600,000 x 0.002 = 97,200 ms; on a slow phone (0.01 ms per node) 486,000 ms is cut to the budget.
        assertEquals(97_200L, levelEstimateMs(EngineLevel.L3100, 81, 0.002))
        assertEquals(GAME_ENGINE_BUDGET_MS, levelEstimateMs(EngineLevel.L3100, 81, 0.01))
        // Not measured: nothing, except Max whose time is the budget itself.
        assertNull(levelEstimateMs(EngineLevel.L2500, 81, null))
        assertEquals(GAME_ENGINE_BUDGET_MS, levelEstimateMs(EngineLevel.MAX, 81, null))
        assertEquals(GAME_ENGINE_BUDGET_MS, levelEstimateMs(EngineLevel.MAX, 81, 0.001))
        // Already analysed at that level: ready at once.
        assertEquals(0L, levelEstimateMs(EngineLevel.MAX, 81, null, cached = true))
    }

    @Test
    fun theWholeReviewAddsTheMeasuredOtherStagesOfThePlanAndInventsNone() {
        val speeds = ReviewSpeeds(msPerPly = mapOf(ReviewStage.WRITE_NARRATION to 100.0, ReviewStage.RECORD_VOICE to 1_000.0))
        val plan = listOf(ReviewStage.ENGINE, ReviewStage.POLISH_COMMENTARY, ReviewStage.WRITE_NARRATION, ReviewStage.RECORD_VOICE)
        // POLISH_COMMENTARY was never measured on this phone: it adds nothing.
        assertEquals(60_000L + 40 * 100L + 40 * 1_000L, wholeReviewEstimateMs(60_000L, plan, speeds, plies = 40))
        assertEquals(60_000L, wholeReviewEstimateMs(60_000L, listOf(ReviewStage.ENGINE), speeds, plies = 40))
        assertNull(wholeReviewEstimateMs(null, plan, speeds, plies = 40))
    }

    @Test
    fun theNaturalVoiceNeedsARecordingWhenSegmentsAreMissingTheDeviceVoiceNever() {
        assertTrue(narrationNeedsPreparation(NarrationProviderChoice.NEURAL, voiceInstalled = true, missingSegments = 3))
        assertFalse(narrationNeedsPreparation(NarrationProviderChoice.NEURAL, voiceInstalled = true, missingSegments = 0))
        // Not installed yet: the device voice reads it, with the "Finish setup" notice instead.
        assertFalse(narrationNeedsPreparation(NarrationProviderChoice.NEURAL, voiceInstalled = false, missingSegments = 3))
        // The device voice speaks live: never a recording.
        assertFalse(narrationNeedsPreparation(NarrationProviderChoice.DEVICE, voiceInstalled = true, missingSegments = 40))
    }

    @Test
    fun aVoiceChangeIsANewRecordingIdentityAPaceChangeIsNot() {
        val base = prefillReviewOptions(null, defaults)
        assertTrue(voiceIdentityChanged(base, base.copy(speakerId = 9)))
        assertTrue(voiceIdentityChanged(base, base.copy(voice = NarrationProviderChoice.DEVICE)))
        assertTrue(voiceIdentityChanged(base.copy(voice = NarrationProviderChoice.DEVICE), base))
        assertFalse(voiceIdentityChanged(base, base.copy(pace = VideoPace.BRISK)))
        // The device voice has no speaker: a speaker change under it changes nothing that is heard.
        val device = base.copy(voice = NarrationProviderChoice.DEVICE)
        assertFalse(voiceIdentityChanged(device, device.copy(speakerId = 9)))
    }

    @Test
    fun theUsernameNamesASideOnlyOnAnExactCaseInsensitiveMatch() {
        assertEquals(PieceColor.WHITE, detectSide("Magnus", "Hikaru", "magnus"))
        assertEquals(PieceColor.BLACK, detectSide("Magnus", "Hikaru", "HIKARU"))
        assertNull(detectSide("Magnus", "Hikaru", ""))
        assertNull(detectSide("Magnus", "Hikaru", "Mag"))
    }

    @Test
    fun theOptionsVoiceIsAnExplicitChoiceInTheNarrationShape() {
        val o = prefillReviewOptions(StoredReviewOptions(voice = NarrationProviderChoice.DEVICE, speakerId = 4), defaults)
        val v = o.voiceSettings
        assertEquals(NarrationProviderChoice.DEVICE, v.provider)
        assertEquals(4, v.speakerId)
        assertTrue(v.providerExplicitlyChosen)
    }
}
