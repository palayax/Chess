package net.palaya.chessanalyzer.core.narration

import net.palaya.chessanalyzer.core.analysis.GameReport
import net.palaya.chessanalyzer.core.analysis.MoveAnnotation
import net.palaya.chessanalyzer.core.analysis.MoveClassification
import net.palaya.chessanalyzer.core.analysis.TacticInstance
import net.palaya.chessanalyzer.core.analysis.TacticType
import net.palaya.chessanalyzer.core.chess.Color
import net.palaya.chessanalyzer.core.pgn.PgnGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ANALYSIS_SPEC §9.2 — the centipawn significance threshold on [NarrationOptions].
 *
 * The boundary is tested at 49 / 50 / 51 centipawns because "half a pawn" is the number the
 * owner will type into Settings, and an off-by-one in a `>` vs `>=` is invisible in a video and
 * would only ever be noticed as "it skipped the move I wanted".
 *
 * Every report here is synthesised on top of [NarrationFixture]'s real game (the Opera Game, 33
 * plies, ending `Rd8#`) so the FENs, SANs and UCIs are genuine and the generator runs its real
 * code path — only the evaluations, classifications and tactics are replaced, which is what
 * makes an exact-centipawn assertion possible at all.
 */
class NarrationSignificanceTest {

    private val game: PgnGame = NarrationFixture.game()
    private val baseline: GameReport = NarrationFixture.report(Color.WHITE)

    /** Plies chosen to sit well apart, away from both ends of the game. */
    private val targetPly = 12
    private val carrierPly = 26

    // -----------------------------------------------------------------------
    // Synthetic report construction
    // -----------------------------------------------------------------------

    /**
     * A report whose every ply is quiet (no swing, no tactic, GOOD, no loss) except the plies
     * named in [swings], which get `evalBeforeCp = 0` and `evalAfterCp = <swing>`.
     *
     * Flattening everything else is the point: with no tactics and no mistakes there are no
     * [net.palaya.chessanalyzer.core.analysis.MoveSequence]s and no key moments, so the only
     * thing that can put a ply into the script is the threshold under test.
     */
    private fun quietReport(
        swings: Map<Int, Int> = emptyMap(),
        tacticsAt: Map<Int, TacticInstance> = emptyMap(),
        evals: Map<Int, Pair<Int, Int>> = emptyMap(),
        classifications: Map<Int, MoveClassification> = emptyMap(),
        stripFinalMate: Boolean = false
    ): GameReport {
        val annotations = baseline.annotations.map { a ->
            val (before, after) = evals[a.ply] ?: (0 to (swings[a.ply] ?: 0))
            a.copy(
                classification = classifications[a.ply] ?: MoveClassification.GOOD,
                loss = 0.0,
                winPercentBefore = 50.0,
                winPercentAfter = 50.0,
                evalBeforeCp = before,
                evalAfterCp = after,
                mateInBefore = null,
                mateInAfter = null,
                tacticsFound = listOfNotNull(tacticsAt[a.ply]),
                tacticsMissed = emptyList(),
                threatsAllowed = emptyList(),
                simulation = null,
                san = if (stripFinalMate) a.san.removeSuffix("#") else a.san
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

    private fun narratedPlies(
        report: GameReport,
        thresholdCp: Int = NarrationOptions.DEFAULT_SIGNIFICANCE_THRESHOLD_CP,
        depth: NarrationDepth = NarrationDepth.HIGHLIGHTS
    ): Set<Int> = script(report, thresholdCp, depth).segments.mapNotNull { it.ply }.toSet()

    private fun script(
        report: GameReport,
        thresholdCp: Int = NarrationOptions.DEFAULT_SIGNIFICANCE_THRESHOLD_CP,
        depth: NarrationDepth = NarrationDepth.HIGHLIGHTS
    ): VideoScript = VideoScriptGenerator(Color.WHITE).generate(
        report, game, NarrationOptions(depth = depth, significanceThresholdCp = thresholdCp)
    )

    // -----------------------------------------------------------------------
    // The default
    // -----------------------------------------------------------------------

    @Test
    fun `the default threshold is fifty centipawns`() {
        assertEquals(50, NarrationOptions.DEFAULT_SIGNIFICANCE_THRESHOLD_CP)
        assertEquals(50, NarrationOptions().significanceThresholdCp)
    }

    // -----------------------------------------------------------------------
    // The boundary: 49 / 50 / 51
    // -----------------------------------------------------------------------

    /** A second, clearly-significant ply so the empty-result fallback can never mask the result. */
    private fun boundaryReport(swingCp: Int): GameReport =
        quietReport(
            swings = mapOf(targetPly to swingCp, carrierPly to 400),
            classifications = mapOf(carrierPly to MoveClassification.BLUNDER)
        )

    @Test
    fun `a forty-nine centipawn swing is below the default threshold`() {
        val plies = narratedPlies(boundaryReport(49))
        assertFalse("49cp must not clear a 50cp bar; narrated=$plies", targetPly in plies)
        assertTrue("the 400cp carrier must still be narrated", carrierPly in plies)
    }

    @Test
    fun `a fifty centipawn swing clears the default threshold exactly`() {
        val plies = narratedPlies(boundaryReport(50))
        assertTrue("50cp is >= 50cp and must be narrated; narrated=$plies", targetPly in plies)
    }

    @Test
    fun `a fifty-one centipawn swing clears the default threshold`() {
        assertTrue(targetPly in narratedPlies(boundaryReport(51)))
    }

    @Test
    fun `the boundary is on the absolute swing, so a negative swing behaves identically`() {
        assertFalse(targetPly in narratedPlies(boundaryReport(-49)))
        assertTrue(targetPly in narratedPlies(boundaryReport(-50)))
    }

    @Test
    fun `the threshold filters on the swing, not on the position's own evaluation`() {
        // A completely won position that does not change: absolute eval is far beyond half a
        // pawn, but nothing happened, so there is nothing to say. This is the whole reason
        // §9.2 reads the request as a swing filter.
        val report = quietReport(
            evals = mapOf(targetPly to (900 to 900)),
            swings = mapOf(carrierPly to 400),
            classifications = mapOf(carrierPly to MoveClassification.BLUNDER)
        )
        assertFalse(targetPly in narratedPlies(report))
    }

    @Test
    fun `a threshold of zero disables pruning entirely`() {
        val report = quietReport(stripFinalMate = true)
        val unpruned = narratedPlies(report, thresholdCp = 0)
        val pruned = narratedPlies(report, thresholdCp = 50)
        assertTrue("threshold 0 must keep every candidate", unpruned.size > 1)
        assertEquals("nothing in a flat game clears 50cp", 1, pruned.size)
    }

    @Test
    fun `EVERY_MOVE is an explicit escape hatch and ignores the threshold`() {
        val plies = narratedPlies(quietReport(), thresholdCp = 1_000_000, depth = NarrationDepth.EVERY_MOVE)
        assertEquals(baseline.annotations.size, plies.size)
    }

    // -----------------------------------------------------------------------
    // Sequences
    // -----------------------------------------------------------------------

    @Test
    fun `a sequence carries members whose own swing is below the bar`() {
        // Three consecutive plies, each worth 20cp on its own (below 50), but 60cp across the
        // run — and tied together as one fork for White, so they form a TACTIC sequence.
        val fork = { TacticInstance(TacticType.FORK, Color.WHITE, "a1a2", confidence = 0.95) }
        val report = quietReport(
            evals = mapOf(15 to (0 to 20), 16 to (20 to 40), 17 to (40 to 60)),
            tacticsAt = mapOf(15 to fork(), 16 to fork(), 17 to fork()),
            swings = mapOf(carrierPly to 400),
            classifications = mapOf(carrierPly to MoveClassification.BLUNDER)
        )
        val plies = narratedPlies(report)
        assertTrue("the middle of a 60cp run must survive a 50cp bar; narrated=$plies", 16 in plies)

        // The control: identical evaluations, no motif tying them together, so no sequence and
        // each 20cp move is judged alone and pruned.
        val unlinked = quietReport(
            evals = mapOf(15 to (0 to 20), 16 to (20 to 40), 17 to (40 to 60)),
            swings = mapOf(carrierPly to 400),
            classifications = mapOf(carrierPly to MoveClassification.BLUNDER)
        )
        assertFalse("without a sequence a 20cp move must be pruned", 16 in narratedPlies(unlinked))
    }

    // -----------------------------------------------------------------------
    // Never broken, never empty
    // -----------------------------------------------------------------------

    @Test
    fun `when nothing clears the bar the review falls back to the largest swing`() {
        // Final move's "#" stripped so the checkmate exemption cannot supply the body instead.
        val report = quietReport(swings = mapOf(targetPly to 10, 5 to 4), stripFinalMate = true)
        val plies = narratedPlies(report)
        assertEquals("exactly the single largest-swing ply should be narrated", setOf(targetPly), plies)
    }

    @Test
    fun `the fallback never yields an empty script`() {
        val report = quietReport(stripFinalMate = true)
        val s = script(report, thresholdCp = 1_000_000)
        assertTrue(s.segments.isNotEmpty())
        assertTrue("a body beat must survive", s.segments.any { it.ply != null })
    }

    @Test
    fun `structural segments survive an absurdly high threshold`() {
        val s = script(quietReport(), thresholdCp = 1_000_000)
        val kinds = s.segments.map { it.kind }.toSet()
        assertTrue("intro missing", SegmentKind.INTRO in kinds)
        assertTrue("opening summary missing", SegmentKind.OPENING_SUMMARY in kinds)
        assertTrue("outro summary missing", SegmentKind.OUTRO_SUMMARY in kinds)
        assertTrue("outro lessons missing", SegmentKind.OUTRO_LESSONS in kinds)
        assertTrue("chapters missing", s.chapters.isNotEmpty())
        // Chapters must never point past the end of the script.
        assertTrue(s.chapters.all { it.startSegmentIndex in s.segments.indices })
        // The final result has to be stated somewhere the viewer sees it.
        val outro = s.segments.first { it.kind == SegmentKind.OUTRO_SUMMARY }
        val card = outro.board as BoardDirective.Card
        assertTrue("the result must survive", card.lines.any { it.contains(baseline.result) })
    }

    @Test
    fun `the checkmating final move survives any threshold`() {
        val s = script(quietReport(), thresholdCp = 1_000_000)
        val matePly = baseline.annotations.last().ply
        assertTrue(
            "the game's own ending must be narrated",
            s.segments.any { it.ply == matePly }
        )
    }

    @Test
    fun `the threshold composes with NarrationDepth rather than replacing it`() {
        // MISTAKES_ONLY over a game with no mistakes proposes almost nothing; the threshold must
        // not turn that into a broken script, and must not resurrect moves depth excluded.
        val report = quietReport(
            swings = mapOf(targetPly to 400, carrierPly to 400),
            classifications = mapOf(
                targetPly to MoveClassification.BLUNDER,
                carrierPly to MoveClassification.BLUNDER
            )
        )
        val highlights = narratedPlies(report, depth = NarrationDepth.HIGHLIGHTS)
        val mistakesOnly = narratedPlies(report, depth = NarrationDepth.MISTAKES_ONLY)
        assertTrue(mistakesOnly.size <= highlights.size)
        assertTrue("MISTAKES_ONLY must still produce a body", mistakesOnly.isNotEmpty())
    }

    // -----------------------------------------------------------------------
    // The swing that the UI reads back
    // -----------------------------------------------------------------------

    @Test
    fun `a played-move segment carries the signed swing it was judged on`() {
        val report = quietReport(swings = mapOf(targetPly to -400))
        val segment = script(report, thresholdCp = 50).segments.first { it.ply == targetPly }
        assertEquals(-400, segment.evalSwingCp)
    }

    @Test
    fun `no numeric swing is published across a mate boundary`() {
        // cpFromMate saturates to ~10000cp, so the raw difference would display as "+99 pawns".
        // The threshold still sees the raw swing — allowing mate is maximally significant — but
        // there is no honest number to show, so none is published.
        val base = quietReport(classifications = mapOf(targetPly to MoveClassification.BLUNDER))
        val report = base.copy(
            annotations = base.annotations.map { a ->
                if (a.ply == targetPly) a.copy(evalBeforeCp = 0, evalAfterCp = -9950, mateInAfter = -1) else a
            }
        )
        val segment = script(report, thresholdCp = 50).segments.firstOrNull { it.ply == targetPly }
        assertTrue("the mate-allowing move must still be narrated", segment != null)
        assertEquals(null, segment!!.evalSwingCp)
    }

    @Test
    fun `card segments carry no swing, since they are about no move`() {
        val s = script(quietReport(), thresholdCp = 0)
        for (seg in s.segments.filter { it.board is BoardDirective.Card }) {
            assertEquals("segment ${seg.index} (${seg.kind}) invented a swing", null, seg.evalSwingCp)
        }
    }
}
