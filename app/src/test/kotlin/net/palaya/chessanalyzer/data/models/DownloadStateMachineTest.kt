package net.palaya.chessanalyzer.data.models

import net.palaya.chessanalyzer.data.models.DownloadEvent as E
import net.palaya.chessanalyzer.data.models.DownloadState as S
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Every edge of design §2.3 (docs/MODEL_DOWNLOAD_DESIGN.md), pure. */
class DownloadStateMachineTest {

    private fun machine(vararg events: E): DownloadStateMachine =
        DownloadStateMachine().apply { events.forEach { on(it) } }

    @Test
    fun theHappyPathEndsInstalled() {
        val m = DownloadStateMachine()
        assertEquals(S.Connecting(0, 0), m.on(E.Start).state)
        assertEquals(S.Streaming(0, 0), m.on(E.Connected).state)
        assertEquals(Transition(S.Verified), m.on(E.EndOfFile(hashOk = true)))
        assertEquals(S.Installing, m.on(E.InstallStarted).state)
        val last = m.on(E.InstallSucceeded)
        assertEquals(S.Installed, last.state)
        assertTrue(last.state.isTerminal)
        assertFalse(last.deletePart)
    }

    @Test
    fun transientFailuresBackOffTwoFourEightSixteenSecondsThenTheFifthPauses() {
        val m = machine(E.Start)
        val delays = ArrayList<Long>()
        repeat(4) { i ->
            val t = m.on(E.TransientError())
            val s = t.state as S.Backoff
            assertEquals(i + 1, s.transientFailures)
            assertFalse("a backoff never deletes the part", t.deletePart)
            delays += s.delayMs
            assertEquals(S.Connecting(i + 1, 0), m.on(E.BackoffElapsed).state)
            // Alternate where the failure happens: before and after the first byte.
            if (i % 2 == 0) m.on(E.Connected)
        }
        assertEquals(listOf(2_000L, 4_000L, 8_000L, 16_000L), delays)
        val fifth = m.on(E.TransientError())
        assertEquals(S.Paused(PauseReason.CONNECTION_LOST), fifth.state)
        assertFalse("Paused never deletes the part", fifth.deletePart)
    }

    @Test
    fun theFifthFailureBeingAServerErrorPausesAsServerUnavailable() {
        val m = machine(E.Start)
        repeat(4) { m.on(E.TransientError(serverError = true)); m.on(E.BackoffElapsed) }
        assertEquals(S.Paused(PauseReason.SERVER_UNAVAILABLE), m.on(E.TransientError(serverError = true)).state)
    }

    @Test
    fun aRetryAfterOfAtMostSixtySecondsReplacesTheBackoffDelay() {
        assertEquals(30_000L, (machine(E.Start).on(E.TransientError(true, retryAfterMs = 30_000)).state as S.Backoff).delayMs)
        assertEquals(60_000L, (machine(E.Start).on(E.TransientError(true, retryAfterMs = 60_000)).state as S.Backoff).delayMs)
        assertEquals(2_000L, (machine(E.Start).on(E.TransientError(true, retryAfterMs = 61_000)).state as S.Backoff).delayMs)
    }

    @Test
    fun resumeResetsTheFailureCounter() {
        val m = machine(E.Start)
        repeat(4) { m.on(E.TransientError()); m.on(E.BackoffElapsed) }
        m.on(E.TransientError())
        assertEquals(S.Paused(PauseReason.CONNECTION_LOST), m.state)
        assertEquals(S.Connecting(0, 0), m.on(E.Start).state)
        assertEquals(2_000L, (m.on(E.TransientError()).state as S.Backoff).delayMs)
    }

    @Test
    fun aWrongHashDeletesThePartAndRestartsOnceThenTheSecondIsDamaged() {
        val m = machine(E.Start, E.Connected)
        val first = m.on(E.EndOfFile(hashOk = false))
        assertEquals(S.HashFailed(1), first.state)
        assertTrue(first.deletePart)
        assertEquals(S.Connecting(0, 1), m.on(E.RetryAfterHashFailure).state)
        m.on(E.Connected)
        val second = m.on(E.EndOfFile(hashOk = false))
        assertEquals(S.Failed(FailureReason.DAMAGED), second.state)
        assertTrue(second.deletePart)
        assertTrue(second.state.isTerminal)
    }

    @Test
    fun aGoodHashAfterOneBadOneVerifies() {
        val m = machine(E.Start, E.Connected, E.EndOfFile(false), E.RetryAfterHashFailure, E.Connected)
        assertEquals(S.Verified, m.on(E.EndOfFile(true)).state)
    }

    @Test
    fun theHashCountSurvivesTransientFailuresBetweenTheTwoAttempts() {
        val m = machine(E.Start, E.Connected, E.EndOfFile(false), E.RetryAfterHashFailure)
        m.on(E.TransientError())
        m.on(E.BackoffElapsed)
        m.on(E.Connected)
        assertEquals(S.Failed(FailureReason.DAMAGED), m.on(E.EndOfFile(false)).state)
    }

    @Test
    fun fatalAnswersFailAndOnlyASizeMismatchDeletesThePart() {
        for (reason in FailureReason.entries) {
            val fromConnecting = machine(E.Start).on(E.Fatal(reason))
            val fromStreaming = machine(E.Start, E.Connected).on(E.Fatal(reason))
            for (t in listOf(fromConnecting, fromStreaming)) {
                assertEquals(S.Failed(reason), t.state)
                assertEquals(reason.name, reason == FailureReason.SIZE_MISMATCH || reason == FailureReason.DAMAGED, t.deletePart)
            }
        }
    }

    @Test
    fun userPauseFromEveryActiveStateKeepsThePart() {
        val actives = listOf(
            machine(E.Start),
            machine(E.Start, E.Connected),
            machine(E.Start, E.TransientError()),
            machine(E.Start, E.Connected, E.EndOfFile(false)),
            machine(E.Start, E.Connected, E.EndOfFile(true)),
            machine(E.Start, E.Connected, E.EndOfFile(true), E.InstallStarted),
        )
        for (m in actives) {
            val before = m.state
            val t = m.on(E.UserPause)
            assertEquals("pause from $before", S.Paused(PauseReason.USER), t.state)
            assertFalse("pause from $before must keep the part", t.deletePart)
            assertEquals("resume from a pause", S.Connecting(0, 0), m.on(E.Start).state)
        }
    }

    @Test
    fun cancelFromEveryNonTerminalStateDeletesThePartAndReturnsToIdle() {
        val states = listOf(
            machine(),
            machine(E.Start),
            machine(E.Start, E.Connected),
            machine(E.Start, E.TransientError()),
            machine(E.Start, E.Connected, E.EndOfFile(false)),
            machine(E.Start, E.Connected, E.EndOfFile(true)),
            machine(E.Start, E.Connected, E.EndOfFile(true), E.InstallStarted),
            machine(E.Start, E.UserPause),
        )
        for (m in states) {
            val before = m.state
            val t = m.on(E.Cancel)
            assertEquals("cancel from $before", S.Idle, t.state)
            assertTrue("cancel from $before must delete the part", t.deletePart)
        }
    }

    @Test
    fun terminalStatesRefuseCancelButFailedAcceptsTryAgain() {
        val installed = machine(E.Start, E.Connected, E.EndOfFile(true), E.InstallStarted, E.InstallSucceeded)
        expectInvalid { installed.on(E.Cancel) }
        val failed = machine(E.Start, E.Fatal(FailureReason.NOT_FOUND))
        expectInvalid { failed.on(E.Cancel) }
        assertEquals(S.Connecting(0, 0), failed.on(E.Start).state)
    }

    @Test
    fun aFailedInstallDeletesThePart() {
        val t = machine(E.Start, E.Connected, E.EndOfFile(true), E.InstallStarted).on(E.InstallFailed(FailureReason.DAMAGED))
        assertEquals(S.Failed(FailureReason.DAMAGED), t.state)
        assertTrue(t.deletePart)
    }

    @Test
    fun eventsThatMakeNoSenseInAStateAreRejected() {
        expectInvalid { machine().on(E.Connected) }
        expectInvalid { machine().on(E.EndOfFile(true)) }
        expectInvalid { machine(E.Start).on(E.EndOfFile(true)) } // no bytes before Connected
        expectInvalid { machine(E.Start).on(E.Start) }
        expectInvalid { machine(E.Start).on(E.BackoffElapsed) }
        expectInvalid { machine(E.Start, E.Connected).on(E.InstallStarted) }
        expectInvalid { machine().on(E.UserPause) }
    }

    private fun expectInvalid(block: () -> Unit) {
        try {
            block()
            fail("expected IllegalStateException")
        } catch (e: IllegalStateException) {
            // expected
        }
    }
}
