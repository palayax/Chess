package net.palaya.chessanalyzer.core.narration

import net.palaya.chessanalyzer.core.analysis.GameReport
import net.palaya.chessanalyzer.core.analysis.MoveClassification
import net.palaya.chessanalyzer.core.analysis.TacticInstance
import net.palaya.chessanalyzer.core.analysis.TacticReferenceLibrary
import net.palaya.chessanalyzer.core.analysis.TacticType
import net.palaya.chessanalyzer.core.chess.Color
import net.palaya.chessanalyzer.core.pgn.PgnGame
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ANALYSIS_SPEC §9.6 / §10 as the narration experiences them: minor tactics are never named, a
 * significant found tactic earns its ply a beat even though a best move has no swing of its own,
 * and the lessons offer the reference example by name — without ever playing it inside the video.
 *
 * Built on [NarrationFixture]'s real game, with evaluations and tactics replaced, exactly as
 * [NarrationSignificanceTest] does.
 */
class NarrationTacticGateTest {

    private val game: PgnGame = NarrationFixture.game()
    private val baseline: GameReport = NarrationFixture.report(Color.WHITE)

    private val targetPly = 12
    private val carrierPly = 26

    private fun quiet(
        evals: Map<Int, Pair<Int, Int>> = emptyMap(),
        found: Map<Int, TacticInstance> = emptyMap(),
        missed: Map<Int, TacticInstance> = emptyMap(),
        secondBest: Map<Int, Int> = emptyMap(),
        classifications: Map<Int, MoveClassification> = emptyMap()
    ): GameReport {
        val annotations = baseline.annotations.map { a ->
            val (before, after) = evals[a.ply] ?: (0 to 0)
            a.copy(
                classification = classifications[a.ply] ?: MoveClassification.GOOD,
                loss = 0.0, winPercentBefore = 50.0, winPercentAfter = 50.0,
                evalBeforeCp = before, evalAfterCp = after,
                evalSecondBestCp = secondBest[a.ply],
                mateInBefore = null, mateInAfter = null,
                tacticsFound = listOfNotNull(found[a.ply]),
                tacticsMissed = listOfNotNull(missed[a.ply]),
                threatsAllowed = emptyList(), simulation = null
            )
        }
        val white = annotations.filter { it.color == Color.WHITE }
        val black = annotations.filter { it.color == Color.BLACK }
        return baseline.copy(
            annotations = annotations,
            evalGraph = List(annotations.size + 1) { 50.0 },
            keyMoments = emptyList(),
            white = baseline.white.copy(
                classificationCounts = emptyMap(),
                tacticsFound = white.flatMap { it.tacticsFound },
                tacticsMissed = white.flatMap { it.tacticsMissed }
            ),
            black = baseline.black.copy(
                classificationCounts = emptyMap(),
                tacticsFound = black.flatMap { it.tacticsFound },
                tacticsMissed = black.flatMap { it.tacticsMissed }
            )
        )
    }

    private fun script(report: GameReport, thresholdCp: Int = 50, depth: NarrationDepth = NarrationDepth.HIGHLIGHTS) =
        VideoScriptGenerator(Color.WHITE).generate(report, game, NarrationOptions(depth = depth, significanceThresholdCp = thresholdCp))

    private fun pinFor(ply: Int) = TacticInstance(
        TacticType.PIN_RELATIVE, if (ply % 2 == 1) Color.WHITE else Color.BLACK, "a1a2", confidence = 0.6
    )

    private fun forkFor(ply: Int) = TacticInstance(
        TacticType.FORK, if (ply % 2 == 1) Color.WHITE else Color.BLACK, "a1a2", materialSwing = 500, confidence = 0.95
    )

    @Test
    fun `a minor found tactic is never named in the narration`() {
        // Ply 12 executes a relative pin on a best move that changed nothing (second line equal).
        val report = quiet(
            evals = mapOf(targetPly to (30 to 30), carrierPly to (0 to 400)),
            secondBest = mapOf(targetPly to 28),
            found = mapOf(targetPly to pinFor(targetPly)),
            classifications = mapOf(targetPly to MoveClassification.BEST, carrierPly to MoveClassification.BLUNDER)
        )
        val s = script(report)
        val spoken = s.segments.joinToString(" ") { it.narration }.lowercase()
        assertFalse("minor pin must not be narrated: $spoken", "pin" in spoken)
        assertTrue("no FOUND_TACTIC beat for a minor motif", s.segments.none { it.kind == SegmentKind.FOUND_TACTIC && it.ply == targetPly })
    }

    @Test
    fun `a found tactic with a real MultiPV margin earns its ply a beat despite zero swing`() {
        val report = quiet(
            evals = mapOf(targetPly to (500 to 500), carrierPly to (0 to 400)),
            secondBest = mapOf(targetPly to 0),
            found = mapOf(targetPly to forkFor(targetPly)),
            classifications = mapOf(targetPly to MoveClassification.BEST, carrierPly to MoveClassification.BLUNDER)
        )
        val s = script(report)
        val beat = s.segments.firstOrNull { it.ply == targetPly }
        assertTrue("the fork must be narrated; plies=${s.segments.mapNotNull { it.ply }.toSet()}", beat != null)
        assertTrue(s.segments.any { it.kind == SegmentKind.FOUND_TACTIC && it.ply == targetPly })
    }

    @Test
    fun `the same fork with no margin is pruned as a move and as a tactic`() {
        // The ply before is flat at +500 too, so the two-ply swing cannot credit it either.
        val report = quiet(
            evals = mapOf((targetPly - 1) to (500 to 500), targetPly to (500 to 500), carrierPly to (0 to 400)),
            secondBest = mapOf(targetPly to 490),
            found = mapOf(targetPly to forkFor(targetPly)),
            classifications = mapOf(targetPly to MoveClassification.BEST, carrierPly to MoveClassification.BLUNDER)
        )
        val s = script(report)
        assertTrue(s.segments.none { it.ply == targetPly })
    }

    @Test
    fun `the tactic gate applies at EVERY_MOVE depth too, and a threshold of zero switches it off`() {
        val report = quiet(
            evals = mapOf(targetPly to (30 to 30)),
            secondBest = mapOf(targetPly to 28),
            found = mapOf(targetPly to pinFor(targetPly)),
            classifications = mapOf(targetPly to MoveClassification.BEST)
        )
        val every = script(report, depth = NarrationDepth.EVERY_MOVE)
        assertTrue("EVERY_MOVE still visits the ply", every.segments.any { it.ply == targetPly })
        assertTrue("but as a plain move, not a tactic", every.segments.none { it.kind == SegmentKind.FOUND_TACTIC && it.ply == targetPly })

        val off = script(report, thresholdCp = 0, depth = NarrationDepth.EVERY_MOVE)
        assertTrue(off.segments.any { it.kind == SegmentKind.FOUND_TACTIC && it.ply == targetPly })
    }

    @Test
    fun `the lessons offer the textbook example by name and the video never plays it`() {
        // White misses a fork at ply 11 (loss of 300cp) — the lesson names the fork.
        val missedPly = 11
        val report = quiet(
            evals = mapOf(missedPly to (0 to -300)),
            missed = mapOf(missedPly to forkFor(missedPly)),
            classifications = mapOf(missedPly to MoveClassification.MISTAKE)
        )
        val s = script(report)
        val lessons = s.segments.filter { it.kind == SegmentKind.OUTRO_LESSONS }.joinToString(" ") { it.narration }
        assertTrue("lesson should offer the reference: $lessons", "textbook fork" in lessons.lowercase())
        assertTrue(TacticReferenceLibrary.hasReference(TacticType.FORK))

        // The reference position itself never appears as a board directive.
        val referenceFen = TacticReferenceLibrary.forType(TacticType.FORK)!!.fen
        for (seg in s.segments) {
            val fen = when (val b = seg.board) {
                is BoardDirective.Hold -> b.fen
                is BoardDirective.PlayMove -> b.fen
                is BoardDirective.PlayLine -> b.fen
                is BoardDirective.Annotate -> b.fen
                is BoardDirective.Card -> null
            }
            assertFalse("the video must stay in the game", fen == referenceFen)
        }
    }
}
