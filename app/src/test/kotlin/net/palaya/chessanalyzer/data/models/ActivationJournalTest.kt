package net.palaya.chessanalyzer.data.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** The activation journal's state machine and its file (D2e; design §4.1). */
class ActivationJournalTest {

    @get:Rule val tmp = TemporaryFolder()

    private val new = ModelIdentity("nn-abcdef012345.nnue", 98_511_183, "abcdef012345" + "1".repeat(52))
    private val old = ModelIdentity("nn-1a298aa575a0.nnue", 98_511_183, "1a298aa575a0" + "2".repeat(52))

    private fun rec(model: ModelKind, phase: JournalPhase) = JournalRecord.InFlight(model, phase, new, old, 1_000L)

    @Test
    fun theNetGoesSwappedTrialCommittedAndNothingElse() {
        val m = ModelKind.NET
        assertTrue(ActivationMachine.canAdvance(m, null, JournalPhase.SWAPPED))
        assertTrue(ActivationMachine.canAdvance(m, JournalPhase.SWAPPED, JournalPhase.TRIAL))
        assertTrue(ActivationMachine.canAdvance(m, JournalPhase.TRIAL, JournalPhase.COMMITTED))
        assertFalse(ActivationMachine.canAdvance(m, null, JournalPhase.TRIAL))
        assertFalse(ActivationMachine.canAdvance(m, null, JournalPhase.COMMITTED))
        assertFalse("no skipping the trial", ActivationMachine.canAdvance(m, JournalPhase.SWAPPED, JournalPhase.COMMITTED))
        assertFalse(ActivationMachine.canAdvance(m, JournalPhase.TRIAL, JournalPhase.SWAPPED))
        assertFalse(ActivationMachine.canAdvance(m, JournalPhase.COMMITTED, JournalPhase.COMMITTED))
    }

    @Test
    fun theVoiceIsTriedBeforeItIsSwapped() {
        val m = ModelKind.VOICE
        assertTrue(ActivationMachine.canAdvance(m, null, JournalPhase.TRIAL))
        assertTrue(ActivationMachine.canAdvance(m, JournalPhase.TRIAL, JournalPhase.SWAPPED))
        assertTrue(ActivationMachine.canAdvance(m, JournalPhase.SWAPPED, JournalPhase.COMMITTED))
        assertFalse(ActivationMachine.canAdvance(m, null, JournalPhase.SWAPPED))
        assertFalse(ActivationMachine.canAdvance(m, JournalPhase.TRIAL, JournalPhase.COMMITTED))
    }

    @Test
    fun aRecordFoundAtStartMeansRollBackUnlessTheTrialHadPassed() {
        assertEquals(Recovery.NOTHING, ActivationMachine.recoveryFor(null))
        assertEquals(Recovery.ROLL_BACK_NET, ActivationMachine.recoveryFor(rec(ModelKind.NET, JournalPhase.SWAPPED)))
        assertEquals(Recovery.ROLL_BACK_NET, ActivationMachine.recoveryFor(rec(ModelKind.NET, JournalPhase.TRIAL)))
        assertEquals(Recovery.FINISH_NET, ActivationMachine.recoveryFor(rec(ModelKind.NET, JournalPhase.COMMITTED)))
        assertEquals(Recovery.ROLL_BACK_VOICE, ActivationMachine.recoveryFor(rec(ModelKind.VOICE, JournalPhase.TRIAL)))
        assertEquals(Recovery.ROLL_BACK_VOICE, ActivationMachine.recoveryFor(rec(ModelKind.VOICE, JournalPhase.SWAPPED)))
        assertEquals(Recovery.FINISH_VOICE, ActivationMachine.recoveryFor(rec(ModelKind.VOICE, JournalPhase.COMMITTED)))
        assertEquals(Recovery.KEEP_NOTICE, ActivationMachine.recoveryFor(JournalRecord.RolledBack(ModelKind.NET, 1, "x")))
        assertEquals(Recovery.UNREADABLE, ActivationMachine.recoveryFor(JournalRecord.Unreadable))
    }

    @Test
    fun theFileRoundTripsAndEnforcesTheOrder() {
        val j = ActivationJournal(tmp.newFolder())
        assertNull(j.read())
        j.advance(rec(ModelKind.NET, JournalPhase.SWAPPED))
        assertEquals(rec(ModelKind.NET, JournalPhase.SWAPPED), j.read())
        j.advance(rec(ModelKind.NET, JournalPhase.TRIAL))
        assertEquals(JournalPhase.TRIAL, (j.read() as JournalRecord.InFlight).phase)
        try {
            j.advance(rec(ModelKind.NET, JournalPhase.SWAPPED))
            fail("going back must be refused")
        } catch (e: IllegalStateException) {
            // expected
        }
        j.advance(rec(ModelKind.NET, JournalPhase.COMMITTED))
        j.clear()
        assertNull(j.read())
        assertFalse("no temp file is left", java.io.File(j.file.parentFile, "${ActivationJournal.FILE_NAME}.tmp").exists())
    }

    @Test
    fun anOldVoiceMayBeAbsentAndTheNoticeRoundTrips() {
        val j = ActivationJournal(tmp.newFolder())
        val r = JournalRecord.InFlight(ModelKind.VOICE, JournalPhase.TRIAL, new, null, 5L)
        j.advance(r)
        assertEquals(r, j.read())
        j.writeRolledBack(ModelKind.VOICE, "trial failed: RMS 3", 9L)
        assertEquals(JournalRecord.RolledBack(ModelKind.VOICE, 9L, "trial failed: RMS 3"), j.read())
        j.writeRolledBack(null, "unreadable", 10L)
        assertEquals(JournalRecord.RolledBack(null, 10L, "unreadable"), j.read())
    }

    @Test
    fun aDamagedFileReadsAsUnreadable() {
        val j = ActivationJournal(tmp.newFolder())
        j.file.parentFile!!.mkdirs()
        j.file.writeText("{\"model\":\"engine-net\",\"phase\":")
        assertEquals(JournalRecord.Unreadable, j.read())
        j.file.writeText("{\"model\":\"engine-net\",\"phase\":\"halfway\",\"new\":{},\"at\":1}")
        assertEquals(JournalRecord.Unreadable, j.read())
        assertEquals("the journal lives under the backup-excluded models/ directory", "models", j.file.parentFile!!.name)
    }
}
