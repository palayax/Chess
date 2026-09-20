package net.palaya.chessanalyzer.core.narration

import net.palaya.chessanalyzer.core.analysis.GameReport
import net.palaya.chessanalyzer.core.analysis.MoveClassification
import net.palaya.chessanalyzer.core.chess.Color
import net.palaya.chessanalyzer.core.pgn.PgnGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `ScriptSegment.classification` — the spec's verdict on a segment's move (ANALYSIS_SPEC §4),
 * published so the video's side panel can label a ply with the same word the review move list
 * uses for it.
 *
 * Why this exists: an error beat is emitted as [SegmentKind.BLUNDER] for MISTAKE, MISS and
 * BLUNDER alike — the kind says *why the beat exists*, not what the move was worth — so a panel
 * that labelled the move by its kind called a MISTAKE a "Blunder" while the move list called the
 * same ply "Mistake". The kind is not wrong; it was answering a different question.
 *
 * Reports are synthesised on top of [NarrationFixture]'s real game exactly as
 * [NarrationSignificanceTest] does, so the FENs, SANs and UCIs are genuine and the generator runs
 * its real path.
 */
class SegmentClassificationTest {

    private val game: PgnGame = NarrationFixture.game()
    private val baseline: GameReport = NarrationFixture.report(Color.WHITE)

    /** Well away from both ends of the game, and from the fixture's own overridden plies. */
    private val targetPly = 12

    private fun script(report: GameReport, thresholdCp: Int = 50): VideoScript =
        VideoScriptGenerator(Color.WHITE).generate(
            report, game, NarrationOptions(significanceThresholdCp = thresholdCp)
        )

    /**
     * Every ply quiet and GOOD except [targetPly], which gets [classification] and a swing big
     * enough to earn a beat. Flattening the rest means nothing else can put [targetPly] into the
     * script, and no sequence or key moment can change which beat it gets.
     */
    private fun reportWith(classification: MoveClassification, swingCp: Int = -400): GameReport {
        val annotations = baseline.annotations.map { a ->
            val target = a.ply == targetPly
            a.copy(
                classification = if (target) classification else MoveClassification.GOOD,
                loss = 0.0,
                winPercentBefore = 50.0,
                winPercentAfter = 50.0,
                evalBeforeCp = 0,
                evalAfterCp = if (target) swingCp else 0,
                mateInBefore = null,
                mateInAfter = null,
                tacticsFound = emptyList(),
                tacticsMissed = emptyList(),
                threatsAllowed = emptyList(),
                simulation = null
            )
        }
        return baseline.copy(
            annotations = annotations,
            evalGraph = List(annotations.size + 1) { 50.0 },
            keyMoments = emptyList(),
            white = baseline.white.copy(
                classificationCounts = emptyMap(), tacticsFound = emptyList(), tacticsMissed = emptyList()
            ),
            black = baseline.black.copy(
                classificationCounts = emptyMap(), tacticsFound = emptyList(), tacticsMissed = emptyList()
            )
        )
    }

    // -----------------------------------------------------------------------
    // The mismatch this field exists to remove
    // -----------------------------------------------------------------------

    @Test
    fun `a MISTAKE keeps its own verdict on a beat whose kind is BLUNDER`() {
        val segments = script(reportWith(MoveClassification.MISTAKE)).segments.filter { it.ply == targetPly }
        assertTrue("the mistake must be narrated at all", segments.isNotEmpty())
        // The kind is the bucket the beat was emitted from; the classification is the verdict.
        // Both are published, and they are allowed to differ — that is the whole point.
        assertTrue(
            "an error beat is emitted as SegmentKind.BLUNDER; if that changes, this test is stale",
            segments.any { it.kind == SegmentKind.BLUNDER }
        )
        for (seg in segments) {
            assertEquals(
                "segment ${seg.index} (${seg.kind}) must carry the spec's verdict",
                MoveClassification.MISTAKE,
                seg.classification
            )
        }
    }

    @Test
    fun `MISS, MISTAKE and BLUNDER are each published as themselves`() {
        for (cls in listOf(MoveClassification.MISTAKE, MoveClassification.MISS, MoveClassification.BLUNDER)) {
            val segments = script(reportWith(cls)).segments.filter { it.ply == targetPly }
            assertTrue("$cls must be narrated", segments.isNotEmpty())
            for (seg in segments) assertEquals("$cls at segment ${seg.index}", cls, seg.classification)
        }
    }

    @Test
    fun `the verdict reaches a beat that annotates the position instead of replaying the move`() {
        // errorBeat draws arrows over the position; it emits no PlayMove, so before this field
        // existed that frame had nothing but its SegmentKind to label the move by.
        val annotated = script(reportWith(MoveClassification.MISTAKE)).segments
            .filter { it.ply == targetPly && it.board is BoardDirective.Annotate }
        assertTrue("the error beat annotates the position", annotated.isNotEmpty())
        for (seg in annotated) assertEquals(MoveClassification.MISTAKE, seg.classification)
    }

    // -----------------------------------------------------------------------
    // It is never borrowed from another move
    // -----------------------------------------------------------------------

    @Test
    fun `a published verdict always belongs to that segment's own ply`() {
        val report = reportWith(MoveClassification.BLUNDER)
        for (seg in script(report, thresholdCp = 0).segments) {
            val cls = seg.classification ?: continue
            val ply = seg.ply
            assertNotNull("segment ${seg.index} published a verdict with no ply", ply)
            assertEquals(
                "segment ${seg.index} (${seg.kind}) published another ply's verdict",
                report.annotations[ply!! - 1].classification,
                cls
            )
        }
    }

    @Test
    fun `card segments carry no verdict, since they are about no move`() {
        for (seg in script(reportWith(MoveClassification.BLUNDER), thresholdCp = 0).segments) {
            if (seg.board is BoardDirective.Card) {
                assertNull("segment ${seg.index} (${seg.kind}) invented a verdict", seg.classification)
            }
        }
    }

    @Test
    fun `the plies of a hypothetical line carry no verdict, since they were never played`() {
        // The fixture's overridden plies produce real missed tactics, and the excursion replays a
        // line that never occurred. Those PlayMove beats must not borrow the real move's verdict.
        val s = script(baseline, thresholdCp = 0)
        val played = baseline.annotations.map { it.uci to it.fenBefore }.toSet()
        val hypothetical = s.segments.filter { seg ->
            val board = seg.board
            board is BoardDirective.PlayMove && (board.uci to board.fen) !in played
        }
        assertTrue("the fixture must produce at least one hypothetical-line beat", hypothetical.isNotEmpty())
        for (seg in hypothetical) {
            assertNull(
                "segment ${seg.index} (${seg.kind}, ${(seg.board as BoardDirective.PlayMove).san}) " +
                    "classified a move that was never played",
                seg.classification
            )
        }
    }
}
