package net.palaya.chessanalyzer.core.analysis

import net.palaya.chessanalyzer.core.chess.Color
import net.palaya.chessanalyzer.core.narration.RealGameFixture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** ANALYSIS_SPEC §9.9: the phases of a game, from the White win-percent of every position. */
class GamePhasesTest {

    private fun phases(vararg wp: Double) = GamePhases.detect(wp.toList())

    private fun repeat(value: Double, times: Int) = List(times) { value }

    @Test
    fun `the bands are the spoken evaluation bands, mirrored for Black`() {
        val expected = listOf(
            // Black's cut-offs are the mirror of White's: Black needs 82 / 68 / 57 of its own win-percent, so White's
            // 18 / 32 / 43 still belong to Black's band (a side's cut-off is inclusive for that side).
            0.0 to PhaseStanding.BLACK_DECISIVE, 18.0 to PhaseStanding.BLACK_DECISIVE, 18.1 to PhaseStanding.BLACK_CLEAR,
            32.0 to PhaseStanding.BLACK_CLEAR, 32.1 to PhaseStanding.BLACK_EDGE, 43.0 to PhaseStanding.BLACK_EDGE,
            43.1 to PhaseStanding.BALANCED, 50.0 to PhaseStanding.BALANCED, 56.9 to PhaseStanding.BALANCED,
            57.0 to PhaseStanding.WHITE_EDGE, 67.9 to PhaseStanding.WHITE_EDGE, 68.0 to PhaseStanding.WHITE_CLEAR,
            81.9 to PhaseStanding.WHITE_CLEAR, 82.0 to PhaseStanding.WHITE_DECISIVE, 100.0 to PhaseStanding.WHITE_DECISIVE
        )
        for ((wp, band) in expected) assertEquals("$wp", band, PhaseStanding.of(wp))
        // Mirror symmetry: the same advantage for the other side lands in the mirrored band.
        for (wp in listOf(3.0, 20.5, 35.0, 49.0, 55.0, 60.0, 70.0, 90.0)) {
            assertEquals("$wp", PhaseStanding.of(wp).mirrored(), PhaseStanding.of(100.0 - wp))
        }
        assertEquals(Color.WHITE, PhaseStanding.WHITE_EDGE.leader)
        assertEquals(Color.BLACK, PhaseStanding.BLACK_CLEAR.leader)
        assertNull(PhaseStanding.BALANCED.leader)
        // Every band's interval is where of() puts its values.
        for (band in PhaseStanding.entries) {
            assertEquals(band, PhaseStanding.of((band.low + minOf(band.high, 100.0)) / 2))
        }
    }

    @Test
    fun `nothing, one position and a steady game`() {
        assertTrue(GamePhases.detect(emptyList()).isEmpty())
        assertEquals(listOf(Phase(PhaseStanding.BALANCED, 0, 0)), phases(50.0))
        assertEquals(listOf(Phase(PhaseStanding.BALANCED, 0, 9)), GamePhases.detect(repeat(51.0, 10)))
        assertTrue(GamePhases.shifts(GamePhases.detect(repeat(51.0, 10))).isEmpty())
    }

    @Test
    fun `one noisy ply does not split a phase`() {
        val series = repeat(50.0, 6) + listOf(72.0) + repeat(50.0, 6)
        assertEquals(listOf(Phase(PhaseStanding.BALANCED, 0, 12)), GamePhases.detect(series))
    }

    @Test
    fun `a position that sits on a cut-off does not flicker between two bands`() {
        // 56 / 58 / 56 / 58 ... straddles the 57 line by one point either way: inside the 3 point margin.
        val series = repeat(50.0, 4) + List(12) { if (it % 2 == 0) 58.0 else 56.0 }
        assertEquals(listOf(Phase(PhaseStanding.BALANCED, 0, 15)), GamePhases.detect(series))
        // Past the margin it does change.
        val past = repeat(50.0, 4) + repeat(61.0, 8)
        assertEquals(
            listOf(Phase(PhaseStanding.BALANCED, 0, 3), Phase(PhaseStanding.WHITE_EDGE, 4, 11)),
            GamePhases.detect(past)
        )
    }

    @Test
    fun `a shift is on the move that caused it, and says who gained`() {
        val series = repeat(50.0, 6) + repeat(75.0, 8)
        val ph = GamePhases.detect(series)
        assertEquals(listOf(Phase(PhaseStanding.BALANCED, 0, 5), Phase(PhaseStanding.WHITE_CLEAR, 6, 13)), ph)
        val shift = GamePhases.shifts(ph).single()
        assertEquals(PhaseShift(6, PhaseStanding.BALANCED, PhaseStanding.WHITE_CLEAR), shift)
        assertEquals(Color.WHITE, shift.benefiting)
        val back = GamePhases.shifts(GamePhases.detect(repeat(75.0, 6) + repeat(50.0, 6))).single()
        assertEquals(Color.BLACK, back.benefiting)
        assertEquals(6, back.ply)
    }

    @Test
    fun `a short stretch on the way from one standing to another joins the one it leads to`() {
        // White clearly better, two positions of balance, then Black clearly better: the middle two are a transit.
        val series = repeat(75.0, 8) + repeat(50.0, 2) + repeat(25.0, 8)
        val ph = GamePhases.detect(series)
        assertEquals(
            listOf(Phase(PhaseStanding.WHITE_CLEAR, 0, 7), Phase(PhaseStanding.BLACK_CLEAR, 8, 17)),
            ph
        )
        assertEquals(8, GamePhases.shifts(ph).single().ply)
    }

    @Test
    fun `a short dip between two equal standings joins both`() {
        val series = repeat(75.0, 8) + repeat(50.0, 2) + repeat(75.0, 8)
        assertEquals(listOf(Phase(PhaseStanding.WHITE_CLEAR, 0, 17)), GamePhases.detect(series))
    }

    @Test
    fun `the first phase, if short, joins the one after it`() {
        val series = repeat(50.0, 2) + repeat(75.0, 8)
        assertEquals(listOf(Phase(PhaseStanding.WHITE_CLEAR, 0, 9)), GamePhases.detect(series))
    }

    @Test
    fun `a game that ends in a swing keeps its last two positions as a phase`() {
        val series = repeat(75.0, 8) + repeat(100.0, 2)
        assertEquals(
            listOf(Phase(PhaseStanding.WHITE_CLEAR, 0, 7), Phase(PhaseStanding.WHITE_DECISIVE, 8, 9)),
            GamePhases.detect(series)
        )
        // ...but not a single last position.
        assertEquals(listOf(Phase(PhaseStanding.WHITE_CLEAR, 0, 8)), GamePhases.detect(repeat(75.0, 8) + listOf(100.0)))
    }

    @Test
    fun `phases tile the game without gaps and are long enough`() {
        val series = listOf(50.0, 52.0, 49.0, 66.0, 70.0, 74.0, 90.0, 91.0, 40.0, 30.0, 20.0, 15.0, 12.0, 8.0, 5.0, 100.0)
        val ph = GamePhases.detect(series)
        assertEquals(0, ph.first().startPly)
        assertEquals(series.lastIndex, ph.last().endPly)
        for ((a, b) in ph.zipWithNext()) {
            assertEquals(a.endPly + 1, b.startPly)
            assertTrue(a.standing != b.standing)
        }
        for ((i, p) in ph.withIndex()) {
            val min = if (i == ph.lastIndex) GamePhases.MIN_LAST_LENGTH else GamePhases.MIN_LENGTH
            assertTrue("$p", p.length >= min || ph.size == 1)
        }
        assertEquals(ph[1], GamePhases.phaseAt(ph, ph[1].startPly))
    }

    @Test
    fun `the same series always gives the same phases`() {
        val series = (0..60).map { 50.0 + 45.0 * kotlin.math.sin(it / 5.0) }
        assertEquals(GamePhases.detect(series), GamePhases.detect(series.toList()))
    }

    @Test
    fun `the recorded games`() {
        fun shifts(game: RealGameFixture.Game) = GamePhases.shifts(GamePhases.detect(game.report(null).evalGraph))
        // The Immortal Game: level for eleven moves, then White takes over, on Black's 11...Bxb5 (ply 22).
        assertEquals(
            listOf(
                PhaseShift(22, PhaseStanding.BALANCED, PhaseStanding.WHITE_EDGE),
                PhaseShift(26, PhaseStanding.WHITE_EDGE, PhaseStanding.WHITE_CLEAR),
                PhaseShift(40, PhaseStanding.WHITE_CLEAR, PhaseStanding.WHITE_DECISIVE)
            ),
            shifts(RealGameFixture.immortal)
        )
        // Byrne-Fischer: level until White's 11.Bg5 (ply 21), Black clearly better, then winning after ply 35.
        assertEquals(
            listOf(
                PhaseShift(21, PhaseStanding.BALANCED, PhaseStanding.BLACK_CLEAR),
                PhaseShift(35, PhaseStanding.BLACK_CLEAR, PhaseStanding.BLACK_DECISIVE)
            ),
            shifts(RealGameFixture.byrneFischer)
        )
        // game01: White built a lead, Black clawed back to level, then won.
        val g = shifts(RealGameFixture.game01)
        assertEquals(PhaseStanding.WHITE_CLEAR, g.first().to)
        assertEquals(PhaseShift(47, PhaseStanding.WHITE_DECISIVE, PhaseStanding.BALANCED), g[g.size - 2])
        assertEquals(PhaseShift(55, PhaseStanding.BALANCED, PhaseStanding.BLACK_DECISIVE), g.last())
    }
}
