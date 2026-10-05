package net.palaya.chessanalyzer.core.tactics

import net.palaya.chessanalyzer.core.analysis.TacticInstance
import net.palaya.chessanalyzer.core.analysis.TacticReferenceLibrary
import net.palaya.chessanalyzer.core.analysis.TacticType
import net.palaya.chessanalyzer.core.chess.Position
import net.palaya.chessanalyzer.core.chess.parseSan
import net.palaya.chessanalyzer.core.chess.parseUci
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Round 13, task 65: what the app *reports* for a move (`MotifDetector.detect`) versus what the
 * detectors *recognise* (`detectRaw`) - ANALYSIS_SPEC 5.3 "Reporting".
 *
 * The two false-positive cases are lifted from a real game (`fixtures/chesscom_style_game.pgn`
 * analysed by Stockfish 19 at depth 20, FEN and engine line copied verbatim): 17.Rd8# was tagged
 * Fork, Double attack, Hanging piece and Skewer on top of the mate itself, and 15.Bxd7+ - a
 * capture with check - was tagged Windmill and Trapped piece.
 *
 * Every suppression here is paired with a positive control, because a filter that only ever
 * removes things is indistinguishable from a detector that is broken.
 */
class MotifReportingTest {

    private val detector = MotifDetector(StaticExchangeEvaluator())

    private fun reported(fen: String, uci: String, pv: List<String> = emptyList()): List<TacticInstance> {
        val pos = Position.fromFen(fen)
        return detector.detect(pos, pos.parseUci(uci), pv)
    }

    private fun raw(fen: String, uci: String, pv: List<String> = emptyList()): List<TacticInstance> {
        val pos = Position.fromFen(fen)
        return detector.detectRaw(pos, pos.parseUci(uci), pv)
    }

    private fun types(l: List<TacticInstance>) = l.map { it.type }

    /** The reference line for [type], replayed as the "engine PV" the way the corpus test does. */
    private fun reference(type: TacticType): Triple<Position, String, List<String>> {
        val ref = TacticReferenceLibrary.forType(type)!!
        val pos = Position.fromFen(ref.fen)
        val pv = ArrayList<String>()
        var p = pos
        for (san in ref.solutionSan) {
            val m = p.parseSan(san)
            pv.add(m.toUci())
            p = p.makeMove(m)
        }
        return Triple(pos, pv.first(), pv)
    }

    // The real positions -----------------------------------------------------------------------

    private val matePosition = "1n2kb1r/p4ppp/4q3/4p1B1/4P3/8/PPP2PPP/2KR4 w k - 0 17"
    private val matePv = listOf("d1d8")

    private val bxd7Position = "4kb1r/p2r1ppp/4qn2/1B2p1B1/4P3/1Q6/PPP2PPP/2KR4 w k - 2 15"
    private val bxd7Pv = listOf(
        "b5d7", "e6d7", "b3b8", "e8e7", "b8e5", "e7d8", "g5f6", "g7f6", "e5f6", "d8c7", "d1d7", "c7d7",
        "f6h8", "f8d6", "h8f6", "a7a5", "e4e5", "d6b8", "f6f7", "d7c6", "f7h7"
    )

    // 1. A checkmating move carries its mating pattern and nothing else ---------------------------

    @Test
    fun `a checkmating move is reported as its mating pattern only`() {
        val rawTypes = types(raw(matePosition, "d1d8", matePv))
        assertTrue(
            "the raw detectors really do pile motifs onto the mate: $rawTypes",
            rawTypes.any { it !in MotifDetector.MATING_TYPES }
        )
        assertEquals(listOf(TacticType.MATE_NET), types(reported(matePosition, "d1d8", matePv)))
    }

    @Test
    fun `back-rank and smothered mates keep their specific pattern`() {
        val back = types(reported("6k1/5ppp/8/8/8/7Q/5PPP/6K1 w - - 0 1", "h3c8"))
        assertTrue(TacticType.BACK_RANK_MATE in back)
        assertTrue(back.all { it in MotifDetector.MATING_TYPES })

        val smothered = types(reported("6rk/6pp/8/4N3/8/8/6PP/6K1 w - - 0 1", "e5f7"))
        assertTrue(TacticType.SMOTHERED_MATE in smothered)
        assertTrue(smothered.all { it in MotifDetector.MATING_TYPES })
    }

    @Test
    fun `a non-mating fork is still reported - positive control`() {
        // Ne7+ checks the king and attacks the undefended rook: a real fork, and not mate.
        val shown = types(reported("2r3k1/5ppp/8/3N4/8/8/6PP/6K1 w - - 0 1", "d5e7"))
        assertTrue("fork lost: $shown", TacticType.FORK in shown)
    }

    // 2. 15.Bxd7+ was neither a windmill nor a trapped piece nor a fork ---------------------------

    @Test
    fun `a capture with check is not a windmill, a trapped piece or a fork`() {
        val both = listOf(
            "raw" to raw(bxd7Position, "b5d7", bxd7Pv),
            "reported" to reported(bxd7Position, "b5d7", bxd7Pv)
        )
        for ((label, list) in both) {
            val t = types(list)
            assertFalse("$label: windmill on Bxd7+: $t", TacticType.WINDMILL in t)
            assertFalse("$label: trapped piece on Bxd7+: $t", TacticType.TRAPPED_PIECE in t)
            assertFalse("$label: fork on Bxd7+ (the bishop is simply taken): $t", TacticType.FORK in t)
        }
    }

    @Test
    fun `the clearance that makes Bxd7+ work is still found`() {
        // Bxd7+ vacates b5 so the queen can reach b8 with check next: that is the real point.
        assertTrue(TacticType.CLEARANCE in types(reported(bxd7Position, "b5d7", bxd7Pv)))
    }

    @Test
    fun `a check never makes the opponent's other pieces trapped`() {
        // Same position, no PV needed: only a king move or a capture of the checker is legal, so
        // every other black piece "has no legal move", which says nothing about being trapped.
        assertFalse(TacticType.TRAPPED_PIECE in types(raw(bxd7Position, "b5d7")))
    }

    @Test
    fun `a genuinely trapped piece is still reported - positive control`() {
        // Not a check: b3 takes away the bishop's last retreat, and every square it can reach loses it.
        val shown = types(reported("6k1/6pp/8/8/8/8/bPP3PP/2R3K1 w - - 0 1", "b2b3"))
        assertTrue("trapped piece lost: $shown", TacticType.TRAPPED_PIECE in shown)
        val (pos, first, _) = reference(TacticType.TRAPPED_PIECE)
        assertTrue(TacticType.TRAPPED_PIECE in types(detector.detect(pos, pos.parseUci(first))))
    }

    @Test
    fun `a genuine windmill is still reported - positive control`() {
        val (pos, first, pv) = reference(TacticType.WINDMILL)
        val shown = types(detector.detect(pos, pos.parseUci(first), pv))
        assertTrue("corpus windmill lost: $shown", TacticType.WINDMILL in shown)
    }

    @Test
    fun `the Torre-Lasker windmill is reported on its opening move`() {
        val shown = types(
            reported(
                "r3rnk1/pb3pp1/3pp2p/1q4BQ/1P1P4/4N1R1/P4PPP/4R1K1 w - - 0 1", "g5f6",
                listOf("g5f6", "b5h5", "g3g7", "g8h8", "g7f7", "h8g8", "f7g7", "g8h8", "g7b7", "h8g8", "b7g7", "g8h8", "g7g5")
            )
        )
        assertTrue(shown.toString(), TacticType.WINDMILL in shown)
    }

    @Test
    fun `a windmill needs a discovered check, so a plain run of checks is not one`() {
        // The real Bxd7+ line has a bishop and, later, a rook landing on d7 with checks and captures in
        // between: the shape the first windmill rule accepted. It must stay rejected on its own merits,
        // so judge the line itself rather than only the first move.
        val shown = types(raw(bxd7Position, "b5d7", bxd7Pv))
        assertFalse(shown.toString(), TacticType.WINDMILL in shown)
    }

    // 3. At most two survive, mating first, then confidence and swing -------------------------------

    @Test
    fun `no move is ever reported with more than two tactics`() {
        assertEquals(2, MotifDetector.MAX_TACTICS_PER_MOVE)
        for (type in TacticType.entries) {
            if (TacticReferenceLibrary.forType(type) == null) continue
            val (pos, first, pv) = reference(type)
            val shown = detector.detect(pos, pos.parseUci(first), pv)
            assertTrue("$type: ${types(shown)}", shown.size <= MotifDetector.MAX_TACTICS_PER_MOVE)
        }
    }

    @Test
    fun `a promotion with check that forks keeps the fork and the underpromotion`() {
        val fen = "8/pp1k1P1q/8/8/8/8/8/6K1 w - - 0 1"
        val rawTypes = types(raw(fen, "f7f8n"))
        assertTrue(
            rawTypes.toString(),
            rawTypes.containsAll(listOf(TacticType.FORK, TacticType.UNDERPROMOTION, TacticType.PROMOTION_TACTIC))
        )
        val shown = types(reported(fen, "f7f8n"))
        assertEquals(shown.toString(), 2, shown.size)
        assertTrue(shown.toString(), TacticType.FORK in shown && TacticType.UNDERPROMOTION in shown)
    }

    @Test
    fun `a mating motif ranks first`() {
        // Qxc7 in the deflection reference mates in two: the mating motifs outrank whatever else the
        // line produces.
        val (pos, first, pv) = reference(TacticType.DEFLECTION)
        val shown = detector.detect(pos, pos.parseUci(first), pv)
        assertTrue(shown.isNotEmpty())
        assertTrue("first is not a mating motif: ${types(shown)}", shown.first().type in MotifDetector.MATING_TYPES)
    }

    @Test
    fun `a hanging piece that a fork already names is not listed again`() {
        val (pos, first, _) = reference(TacticType.PAWN_FORK)
        val move = pos.parseUci(first)
        assertTrue(types(detector.detectRaw(pos, move, emptyList())).count { it == TacticType.HANGING_PIECE } >= 2)
        assertEquals(listOf(TacticType.PAWN_FORK), types(detector.detect(pos, move)))
    }

    @Test
    fun `a fork whose forker is simply taken is not a fork`() {
        // Qxd8+ Rxd8 is a queen trade: the queen attacks king and rook, then is taken.
        val fen = "r2q3k/6pp/8/6N1/8/8/6PP/3Q2K1 w - - 0 1"
        assertFalse(TacticType.FORK in types(raw(fen, "d1d8")))
        assertFalse(TacticType.DOUBLE_ATTACK in types(raw(fen, "d1d8")))
    }

    @Test
    fun `a pawn fork the forked piece can answer by taking the pawn is not a fork`() {
        // The old corpus entry: 1.e4 against a knight on d5 and a bishop on f5, where Bxe4 simply wins
        // the pawn. Replaced in the corpus by a sound position (two knights, neither can take it).
        val fen = "4k3/8/8/3n1b2/8/4P3/8/4K3 w - - 0 1"
        val shown = types(raw(fen, "e3e4"))
        assertFalse(shown.toString(), TacticType.PAWN_FORK in shown)
    }
}
