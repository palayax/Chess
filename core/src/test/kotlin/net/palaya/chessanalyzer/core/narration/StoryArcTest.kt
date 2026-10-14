package net.palaya.chessanalyzer.core.narration

import net.palaya.chessanalyzer.core.analysis.GamePhases
import net.palaya.chessanalyzer.core.analysis.Phase
import net.palaya.chessanalyzer.core.analysis.PhaseStanding
import net.palaya.chessanalyzer.core.chess.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** ANALYSIS_SPEC §9.9: the stakes of a game, read off its phases and its result. */
class StoryArcTest {

    private fun phases(vararg runs: Pair<PhaseStanding, Int>): List<Phase> {
        var start = 0
        return runs.map { (standing, length) -> Phase(standing, start, start + length - 1).also { start += length } }
    }

    private fun arc(result: String, vararg runs: Pair<PhaseStanding, Int>): StoryArc? {
        val ph = phases(*runs)
        return StoryArc.of(ph, result, ph.last().endPly)
    }

    @Test
    fun `the other side was clearly better for N moves in a row and lost - a comeback`() {
        // White clearly better for 14 + 8 positions in consecutive phases = 11 moves, then Black wins.
        val a = arc(
            "0-1",
            PhaseStanding.BALANCED to 6, PhaseStanding.WHITE_CLEAR to 14, PhaseStanding.WHITE_DECISIVE to 8,
            PhaseStanding.BALANCED to 6, PhaseStanding.BLACK_DECISIVE to 10
        )
        assertEquals(StoryArc.Comeback(Color.BLACK, 11), a)
        // A balanced stretch in between breaks the run: the longer one counts.
        val b = arc(
            "0-1",
            PhaseStanding.WHITE_CLEAR to 14, PhaseStanding.BALANCED to 6, PhaseStanding.WHITE_CLEAR to 8, PhaseStanding.BLACK_CLEAR to 10
        )
        assertEquals(StoryArc.Comeback(Color.BLACK, 7), b)
        // Five moves is not a story.
        assertNull(arc("0-1", PhaseStanding.WHITE_CLEAR to 10, PhaseStanding.BLACK_CLEAR to 10, PhaseStanding.BLACK_DECISIVE to 8))
    }

    @Test
    fun `the loser never led and the winner led to the end - took over`() {
        assertEquals(
            StoryArc.TookOver(Color.WHITE, 11, fromTheStart = false),
            arc("1-0", PhaseStanding.BALANCED to 22, PhaseStanding.WHITE_EDGE to 4, PhaseStanding.WHITE_CLEAR to 14, PhaseStanding.WHITE_DECISIVE to 6)
        )
        // Ahead from the first moves: "from the opening".
        assertEquals(
            StoryArc.TookOver(Color.BLACK, 3, fromTheStart = true),
            arc("0-1", PhaseStanding.BALANCED to 6, PhaseStanding.BLACK_EDGE to 8, PhaseStanding.BLACK_DECISIVE to 12)
        )
        // A phase of the loser's, however small, is no longer "never let go".
        assertNull(
            arc("1-0", PhaseStanding.BALANCED to 8, PhaseStanding.BLACK_EDGE to 4, PhaseStanding.WHITE_CLEAR to 8, PhaseStanding.WHITE_DECISIVE to 8)
        )
        // The winner slipping back to level at the end is not "for good".
        assertNull(arc("1-0", PhaseStanding.BALANCED to 8, PhaseStanding.WHITE_CLEAR to 10, PhaseStanding.BALANCED to 8))
    }

    @Test
    fun `draws - a tight one and one with a wasted lead`() {
        assertEquals(StoryArc.TightDraw, arc("1/2-1/2", PhaseStanding.BALANCED to 12, PhaseStanding.WHITE_EDGE to 8, PhaseStanding.BALANCED to 12))
        assertEquals(
            StoryArc.WastedLead(Color.WHITE, 9),
            arc("1/2-1/2", PhaseStanding.BALANCED to 6, PhaseStanding.WHITE_CLEAR to 18, PhaseStanding.BALANCED to 10)
        )
        // A clear lead of a few moves is neither: no story.
        assertNull(arc("1/2-1/2", PhaseStanding.BALANCED to 12, PhaseStanding.BLACK_CLEAR to 6, PhaseStanding.BALANCED to 12))
    }

    @Test
    fun `short games, unfinished games and empty graphs have no stakes`() {
        assertNull(StoryArc.of(phases(PhaseStanding.WHITE_DECISIVE to 14), "1-0", 13))
        assertNull(arc("*", PhaseStanding.BALANCED to 10, PhaseStanding.WHITE_CLEAR to 20))
        assertNull(StoryArc.of(emptyList(), "1-0", 40))
    }

    @Test
    fun `what followed the turning move`() {
        val ph = phases(PhaseStanding.BALANCED to 20, PhaseStanding.WHITE_CLEAR to 10, PhaseStanding.BALANCED to 8, PhaseStanding.BLACK_CLEAR to 12)
        // Black's move at ply 20 let White ahead; White later lost it: Black got back to level at ply 30 -> move 15.
        assertEquals(StoryArc.Aftermath.FoughtBack(Color.BLACK, 15, PhaseStanding.BALANCED), StoryArc.aftermath(ph, 20, Color.BLACK))
        // From a move after which the other side stays ahead to the end.
        val ph2 = phases(PhaseStanding.BALANCED to 20, PhaseStanding.WHITE_CLEAR to 10, PhaseStanding.WHITE_DECISIVE to 10)
        assertEquals(StoryArc.Aftermath.NeverBack(Color.WHITE), StoryArc.aftermath(ph2, 21, Color.BLACK))
        // The move did not hand the other side the lead: nothing is claimed.
        assertNull(StoryArc.aftermath(ph2, 5, Color.BLACK))
        assertNull(StoryArc.aftermath(ph2, 25, Color.WHITE))
    }

    @Test
    fun `the recorded games`() {
        fun arcOf(game: RealGameFixture.Game): StoryArc? {
            val report = game.report(null)
            return StoryArc.of(GamePhases.detect(report.evalGraph), report.result, report.annotations.size)
        }
        assertEquals(StoryArc.TookOver(Color.WHITE, 11, fromTheStart = false), arcOf(RealGameFixture.immortal))
        assertEquals(StoryArc.TookOver(Color.BLACK, 11, fromTheStart = false), arcOf(RealGameFixture.byrneFischer))
        assertEquals(StoryArc.Comeback(Color.BLACK, 16), arcOf(RealGameFixture.game01))
        assertEquals(StoryArc.TookOver(Color.WHITE, 3, fromTheStart = true), arcOf(RealGameFixture.chesscom))
        assertNull("the scholar's mate is too short for a story", arcOf(RealGameFixture.scholars))
    }
}
