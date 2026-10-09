package net.palaya.chessanalyzer.ui.model

import net.palaya.chessanalyzer.data.models.UpstreamComponent
import net.palaya.chessanalyzer.data.models.UpstreamFailure
import net.palaya.chessanalyzer.data.models.UpstreamRow
import net.palaya.chessanalyzer.data.models.UpstreamStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What each upstream row of the update sheet says, and in which order (A4). */
class UpstreamRowViewsTest {

    private fun row(c: UpstreamComponent, s: UpstreamStatus, ours: String = "1") = UpstreamRow(c, ours, s)

    @Test
    fun everyStatusHasItsOwnLine() {
        val views = upstreamRowViews(
            listOf(
                row(UpstreamComponent.STOCKFISH, UpstreamStatus.Newer("20"), "19"),
                row(UpstreamComponent.SHERPA_ONNX, UpstreamStatus.UpToDate, "1.13.8"),
                row(UpstreamComponent.KOKORO_VOICE, UpstreamStatus.Changed, "v0.19"),
            ),
        )
        assertEquals(listOf(UpstreamLine.NEWER, UpstreamLine.UP_TO_DATE, UpstreamLine.CHANGED), views.map { it.line })
        assertEquals("20", views[0].latest)
        assertEquals("19", views[0].ours)
        assertNull(views[1].latest)
    }

    @Test
    fun aRowStillBeingCheckedIsCheckingAndNotAProblem() {
        val v = upstreamRowViews(listOf(row(UpstreamComponent.STOCKFISH, UpstreamStatus.Checking))).single()
        assertEquals(UpstreamLine.CHECKING, v.line)
        assertFalse(v.line.isProblem)
    }

    @Test
    fun everyFailureReasonMapsToItsOwnProblemLine() {
        val map = mapOf(
            UpstreamFailure.NO_INTERNET to UpstreamLine.NO_INTERNET,
            UpstreamFailure.RATE_LIMITED to UpstreamLine.RATE_LIMITED,
            UpstreamFailure.UNAVAILABLE to UpstreamLine.UNAVAILABLE,
            UpstreamFailure.UNREADABLE to UpstreamLine.UNREADABLE,
        )
        assertEquals(UpstreamFailure.entries.toSet(), map.keys)
        for ((reason, line) in map) {
            val v = upstreamRowViews(listOf(row(UpstreamComponent.SHERPA_ONNX, UpstreamStatus.Failed(reason)))).single()
            assertEquals(line, v.line)
            assertTrue(v.line.isProblem)
        }
    }

    @Test
    fun theListKeepsTheComponentOrderWhateverOrderTheAnswersCameIn() {
        val views = upstreamRowViews(
            listOf(
                row(UpstreamComponent.KOKORO_VOICE, UpstreamStatus.UpToDate),
                row(UpstreamComponent.STOCKFISH, UpstreamStatus.UpToDate),
                row(UpstreamComponent.SHERPA_ONNX, UpstreamStatus.Checking),
            ),
        )
        assertEquals(
            listOf(UpstreamComponent.STOCKFISH, UpstreamComponent.SHERPA_ONNX, UpstreamComponent.KOKORO_VOICE),
            views.map { it.component },
        )
    }

    @Test
    fun onlyUpToDateIsGoodAndNothingUpstreamIsAnAppError() {
        assertEquals(setOf(UpstreamLine.UP_TO_DATE), UpstreamLine.entries.filter { it.isGood }.toSet())
        // A newer upstream version is information, not a problem with the app or with the check.
        assertFalse(UpstreamLine.NEWER.isProblem)
        assertFalse(UpstreamLine.CHANGED.isProblem)
    }

    @Test
    fun noRowsMeansNothingToDraw() {
        assertTrue(upstreamRowViews(emptyList()).isEmpty())
    }
}
