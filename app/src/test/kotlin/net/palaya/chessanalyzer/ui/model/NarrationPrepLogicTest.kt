package net.palaya.chessanalyzer.ui.model

import net.palaya.chessanalyzer.core.narration.VideoPace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** RF: the pure rules of the narration recording (threads, what counts as missing, the ledger, the run request). */
class NarrationPrepLogicTest {

    // ---- Kokoro threads ----

    @Test
    fun aPixel8GetsTheFourFastCoresAtMost() {
        // Tensor G3: 1 x 2.91 GHz, 4 x 2.37 GHz, 3 x 1.70 GHz -> 5 fast cores -> capped at 4.
        val freqs = listOf(1_700_000L, 1_700_000L, 1_700_000L, 2_370_000L, 2_370_000L, 2_370_000L, 2_370_000L, 2_914_000L)
        assertEquals(4, kokoroThreads(freqs))
    }

    @Test
    fun aPhoneWithTwoBigCoresGetsTwo() {
        val freqs = List(6) { 1_800_000L } + List(2) { 2_800_000L }
        assertEquals(2, kokoroThreads(freqs))
    }

    @Test
    fun threeFastCoresGetThree() {
        val freqs = List(5) { 1_800_000L } + List(3) { 2_600_000L }
        assertEquals(3, kokoroThreads(freqs))
    }

    @Test
    fun noFrequencyInformationGetsTwoAndNeverLessThanTwoOrMoreThanFour() {
        assertEquals(2, kokoroThreads(emptyList()))
        assertEquals(2, kokoroThreads(listOf(0L, 0L, 0L, 0L)))
        assertEquals(4, kokoroThreads(List(8) { 2_000_000L })) // one cluster: the core count, capped
        assertEquals(2, kokoroThreads(listOf(2_000_000L, 2_000_000L)))
        for (n in 1..16) {
            val t = kokoroThreads(List(n) { i -> if (i % 2 == 0) 1_000_000L else 2_000_000L })
            assertTrue("threads $t for $n cores", t in 2..4)
        }
    }

    // ---- what counts as missing ----

    @Test
    fun knownFallbacksAreNotMissingButUnrecordedSegmentsAre() {
        val recorded = setOf("a", "b")
        val keys = listOf("a", "b", "c", "d")
        assertEquals(2, missingNarrationCount(keys, emptySet()) { it in recorded })
        assertEquals(1, missingNarrationCount(keys, setOf("c")) { it in recorded })
        assertEquals(0, missingNarrationCount(keys, setOf("c", "d")) { it in recorded })
        // An ignored key that is recorded anyway changes nothing.
        assertEquals(2, missingNarrationCount(keys, setOf("a")) { it in recorded })
    }

    // ---- keys ----

    @Test
    fun theScriptKeyIsStableAndFollowsTheSpokenTextsAndTheGame() {
        val a = narrationScriptKey("g1", listOf("One.", "Two."))
        assertEquals(a, narrationScriptKey("g1", listOf("One.", "Two.")))
        assertEquals(16, a.length)
        assertNotEquals(a, narrationScriptKey("g2", listOf("One.", "Two.")))
        assertNotEquals(a, narrationScriptKey("g1", listOf("One.", "Three.")))
        // "ab" + "c" is not "a" + "bc".
        assertNotEquals(narrationScriptKey("g", listOf("ab", "c")), narrationScriptKey("g", listOf("a", "bc")))
    }

    // ---- the Summary starts the recording ----

    private fun options(prepare: Boolean = true, voice: NarrationProviderChoice = NarrationProviderChoice.NEURAL, side: SideChoice = SideChoice.UNKNOWN, sid: Int = 1) =
        ReviewOptions(AnalysisStrength.STANDARD, side, naturalWording = false, prepareVideo = prepare, voice = voice, speakerId = sid, pace = VideoPace.NORMAL)

    @Test
    fun theRecordingStartsOnlyForAGameThatAsksForTheVideoWithTheInstalledNaturalVoice() {
        assertTrue(backgroundPreparationWanted(true, NarrationProviderChoice.NEURAL, true, 12, alreadyTried = false))
        assertFalse("prepare the video is off", backgroundPreparationWanted(false, NarrationProviderChoice.NEURAL, true, 12, false))
        assertFalse("device voice", backgroundPreparationWanted(true, NarrationProviderChoice.DEVICE, true, 12, false))
        assertFalse("voice not installed", backgroundPreparationWanted(true, NarrationProviderChoice.NEURAL, false, 12, false))
        assertFalse("nothing missing", backgroundPreparationWanted(true, NarrationProviderChoice.NEURAL, true, 0, false))
        assertFalse("tried already (skip / cancel)", backgroundPreparationWanted(true, NarrationProviderChoice.NEURAL, true, 12, alreadyTried = true))
    }

    @Test
    fun aDifferentSideVoiceSpeakerOrGameIsANewQuestion() {
        val base = backgroundPreparationKey("g1", options())
        assertEquals(base, backgroundPreparationKey("g1", options()))
        assertNotEquals(base, backgroundPreparationKey("g2", options()))
        assertNotEquals(base, backgroundPreparationKey("g1", options(side = SideChoice.NOT_ME)))
        assertNotEquals(base, backgroundPreparationKey("g1", options(sid = 2)))
        assertNotEquals(base, backgroundPreparationKey("g1", options(voice = NarrationProviderChoice.DEVICE)))
        // The pace changes no sentence: the same question.
        assertEquals(base, backgroundPreparationKey("g1", options().copy(pace = VideoPace.RELAXED)))
        assertTrue(base.startsWith("g1|"))
    }

    // ---- the processing screen's second call ----

    @Test
    fun aSecondCallWhileTheRunIsStillInItsStagesDoesNotCompleteEarly() {
        assertEquals(RunRequestAction.START, runRequestAction(gameInMemory = false, runActiveForGame = false))
        assertEquals(RunRequestAction.START, runRequestAction(gameInMemory = false, runActiveForGame = true))
        assertEquals(RunRequestAction.KEEP_WAITING, runRequestAction(gameInMemory = true, runActiveForGame = true))
        assertEquals(RunRequestAction.COMPLETE_NOW, runRequestAction(gameInMemory = true, runActiveForGame = false))
    }

    // ---- the ledger ----

    @Test
    fun aRecordedScriptIsPreparedOnlyForItsVoiceIdentity() {
        val voiceA = narrationIdentity("Natural voice (Kokoro)", "KOKORO@v1/sid1")
        val voiceB = narrationIdentity("Natural voice (Kokoro)", "KOKORO@v1/sid2")
        val ledger = PreparedNarrationLedger().record(voiceA, "s1", setOf("k1", "k2", "k3"), setOf("k3"))
        assertTrue(ledger.isPrepared(voiceA, "s1"))
        assertFalse(ledger.isPrepared(voiceA, "s2"))
        assertFalse("another speaker is another identity", ledger.isPrepared(voiceB, "s1"))
        assertEquals(setOf("k3"), ledger.fallbackKeys(voiceA))
        assertEquals("a voice update or another speaker retries the fallbacks", emptySet<String>(), ledger.fallbackKeys(voiceB))
    }

    @Test
    fun aRetryReplacesTheFallbackMarksOfItsOwnScriptOnly() {
        val id = "voice"
        val first = PreparedNarrationLedger()
            .record(id, "s1", setOf("a", "b", "c"), setOf("b", "c"))
            .record(id, "s2", setOf("x", "y"), setOf("y"))
        assertEquals(setOf("b", "c", "y"), first.fallbackKeys(id))
        // s1 again (forced): only "c" falls back this time; "b" worked, "y" belongs to s2 and stays.
        val retry = first.record(id, "s1", setOf("a", "b", "c"), setOf("c"))
        assertEquals(setOf("c", "y"), retry.fallbackKeys(id))
        assertTrue(retry.isPrepared(id, "s1"))
        assertTrue(retry.isPrepared(id, "s2"))
        assertEquals("a script is listed once", 1, retry.identities.single().scripts.count { it == "s1" })
    }

    @Test
    fun theLedgerStaysSmall() {
        var ledger = PreparedNarrationLedger()
        for (i in 1..(PreparedNarrationLedger.MAX_IDENTITIES + 4)) ledger = ledger.record("id$i", "s", setOf("k"), setOf("k"))
        assertEquals(PreparedNarrationLedger.MAX_IDENTITIES, ledger.identities.size)
        assertFalse("the oldest identity went first", ledger.isPrepared("id1", "s"))
        assertTrue(ledger.isPrepared("id${PreparedNarrationLedger.MAX_IDENTITIES + 4}", "s"))

        var scripts = PreparedNarrationLedger()
        for (i in 1..(PreparedNarrationLedger.MAX_SCRIPTS + 10)) scripts = scripts.record("id", "s$i", setOf("k$i"), setOf("k$i"))
        val only = scripts.identities.single()
        assertEquals(PreparedNarrationLedger.MAX_SCRIPTS, only.scripts.size)
        assertEquals("s${PreparedNarrationLedger.MAX_SCRIPTS + 10}", only.scripts.last())
        assertTrue(only.fallback.size <= PreparedNarrationLedger.MAX_FALLBACK)
    }

    @Test
    fun anIdentityTouchedAgainIsTheNewestAndIsKept() {
        var ledger = PreparedNarrationLedger()
        for (i in 1..PreparedNarrationLedger.MAX_IDENTITIES) ledger = ledger.record("id$i", "s", emptySet(), emptySet())
        ledger = ledger.record("id1", "s2", emptySet(), emptySet()) // id1 becomes the newest
        ledger = ledger.record("new", "s", emptySet(), emptySet()) // pushes out the oldest, which is now id2
        assertTrue(ledger.isPrepared("id1", "s2"))
        assertTrue(ledger.isPrepared("id1", "s"))
        assertFalse(ledger.isPrepared("id2", "s"))
    }
}
