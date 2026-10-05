package net.palaya.chessanalyzer.core.tactics

import net.palaya.chessanalyzer.core.analysis.TacticType
import net.palaya.chessanalyzer.core.chess.Color
import net.palaya.chessanalyzer.core.narration.RealGameFixture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R1b (ANALYSIS_SPEC §5.3): descriptions that asserted more than the detector checked, and two PV
 * motifs that were not what they said. Each is pinned on the real recorded games, where it was seen.
 */
class MotifClaimsTest {

    private val immortal = RealGameFixture.immortal.report(null).annotations
    private val chesscom = RealGameFixture.chesscom.report(null).annotations

    private val allTactics = (immortal + chesscom).flatMap { it.tacticsPlayed + it.tacticsMissed + it.threatsAllowed }

    @Test
    fun `a clearance gets another piece through - the moved piece coming back clears nothing`() {
        // 5.Bxb5 ... Be2: the bishop returns along the line it left. 9.Nf5 ... h4: a pawn steps onto the vacated square.
        for (ply in listOf(9, 17, 20)) {
            val a = immortal.first { it.ply == ply }
            assertTrue("immortal ply $ply (${a.san}) must not report a clearance", a.tacticsPlayed.none { it.type == TacticType.CLEARANCE })
        }
        // 15.Bxd7+ clears b5 for the queen: a genuine one, kept.
        val real = chesscom.first { it.ply == 29 }
        val clearance = real.tacticsPlayed.single { it.type == TacticType.CLEARANCE }
        assertEquals("Bxd7+ clears b5 so that Qb8+ can come through.", clearance.description)
    }

    @Test
    fun `a defender that captures on the square it guarded was exchanged there, not deflected from it`() {
        // 4...Nc6: exd6 Qxd6. The pawn takes on d6 - the square it was "dragged away from guarding".
        val inaccuracy = chesscom.first { it.ply == 8 }
        assertTrue(
            "no deflection of the e5 pawn from d6: ${inaccuracy.tacticsMissed.map { it.description }}",
            inaccuracy.tacticsMissed.none { it.type == TacticType.DEFLECTION }
        )
        // 14.Rd1's own deflection of the queen (it leaves e7 for good) is kept.
        assertTrue(chesscom.first { it.ply == 27 }.tacticsPlayed.any { it.type == TacticType.DEFLECTION })
    }

    @Test
    fun `no description claims a piece can be taken for nothing or cannot be held`() {
        assertTrue(allTactics.isNotEmpty())
        for (t in allTactics) {
            assertFalse(t.description, "taken for nothing" in t.description)
            assertFalse(t.description, "cannot be held" in t.description)
            assertFalse(t.description, "left hanging" in t.description)
        }
        val hanging = allTactics.filter { it.type == TacticType.HANGING_PIECE }
        assertTrue(hanging.isNotEmpty())
        assertTrue(hanging.all { "is attacked" in it.description })
    }

    @Test
    fun `a skewer's rear piece is attacked, not already lost`() {
        val skewers = allTactics.filter { it.type == TacticType.SKEWER }
        assertTrue(skewers.isNotEmpty())
        for (t in skewers) {
            assertFalse(t.description, "falls" in t.description)
            assertTrue(t.description, t.description.endsWith("behind it is attacked."))
        }
    }

    @Test
    fun `the engine-line motifs of a mated side are the mating side's, never the mated side's`() {
        // 15...Nxd7?? is mated after 16.Qb8+: the deflection in that line is White's.
        val nxd7 = chesscom.first { it.ply == 30 }
        assertEquals(Color.BLACK, nxd7.color)
        // Whatever the detector read out of it, the card must not credit Black with it (CommentaryGenerator).
        assertTrue(nxd7.text, "deflect" !in nxd7.text)
        assertEquals("Nxd7 is a sound move.", nxd7.text)
    }
}
