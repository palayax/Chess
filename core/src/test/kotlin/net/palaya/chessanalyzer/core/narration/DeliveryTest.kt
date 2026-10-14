package net.palaya.chessanalyzer.core.narration

import net.palaya.chessanalyzer.core.analysis.MoveClassification
import net.palaya.chessanalyzer.core.chess.Position
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** ANALYSIS_SPEC 9.10 (NC): the per-beat delivery is a pure function of the story and scales the speech estimate. */
class DeliveryTest {

    private val fen = Position.STANDARD_START_FEN

    private fun seg(
        kind: SegmentKind,
        text: String = "One. Two. Three.",
        board: BoardDirective = BoardDirective.Hold(fen),
        classification: MoveClassification? = null,
        swing: Int? = null,
    ) = ScriptSegment(
        index = 0, kind = kind, ply = null, narration = text, caption = "", board = board,
        estimatedSpeechMs = VideoScriptGenerator.estimateSpeechMs(text, 169),
        evalSwingCp = swing, classification = classification,
    )

    private fun play(san: String) = BoardDirective.PlayMove(fen, "e2e4", san)

    @Test
    fun `the neutral delivery is today's behaviour`() {
        assertTrue(Delivery().isNeutral)
        assertEquals(Delivery.NEUTRAL, ScriptSegment(0, SegmentKind.INTRO, null, "x", "", BoardDirective.Hold(fen), 1).delivery)
        assertEquals(220L, Delivery.NEUTRAL.gapMs(220L))
        assertEquals(
            VideoScriptGenerator.estimateSpeechMs("One. Two. Three.", 169),
            VideoScriptGenerator.estimateSpeechMs("One. Two. Three.", 169, Delivery.NEUTRAL),
        )
    }

    @Test
    fun `every named delivery stays within the speed range Kokoro tolerates`() {
        for (d in listOf(Delivery.CALM, Delivery.SETUP, Delivery.BRISK, Delivery.WEIGHTY)) {
            assertTrue("$d", d.speed in 0.9f..1.1f)
            assertFalse("$d", d.isNeutral)
        }
        assertEquals(0.94f, Delivery.CALM.speed, 0f)
        assertEquals(1.25f, Delivery.CALM.gapScale, 0f)
        assertEquals(0.9f, Delivery.SETUP.speed, 0f)
        assertEquals(1.4f, Delivery.SETUP.gapScale, 0f)
        assertEquals(1.08f, Delivery.BRISK.speed, 0f)
        assertEquals(0.8f, Delivery.BRISK.gapScale, 0f)
        assertEquals(0.95f, Delivery.WEIGHTY.speed, 0f)
        assertEquals(1.5f, Delivery.WEIGHTY.gapScale, 0f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a speed outside the range is refused`() {
        Delivery(speed = 1.2f)
    }

    @Test
    fun `the gap is scaled and clamped to 120 to 600 ms`() {
        assertEquals(275L, Delivery.CALM.gapMs(220L))
        assertEquals(448L, Delivery.SETUP.gapMs(320L))
        assertEquals(176L, Delivery.BRISK.gapMs(220L))
        assertEquals(120L, Delivery(gapScale = 0.2f).gapMs(220L))
        assertEquals(600L, Delivery(gapScale = 3f).gapMs(320L))
    }

    @Test
    fun `the speech estimate is scaled by one over the speed and the pauses by the gap scale`() {
        val text = "One two three four five six. Seven eight nine ten."
        val n = VideoScriptGenerator.estimateSpeechMs(text, 120)
        val words = 10
        val speaking = words * 60_000L / 120
        assertEquals(speaking + 2 * 240L, n)
        val slow = VideoScriptGenerator.estimateSpeechMs(text, 120, Delivery.SETUP)
        assertEquals(Math.round(speaking / 0.9f.toDouble() + 2 * 240 * 1.4f.toDouble()), slow)
        assertTrue(slow > n)
        val fast = VideoScriptGenerator.estimateSpeechMs(text, 120, Delivery.BRISK)
        assertTrue(fast < n)
        assertEquals(0L, VideoScriptGenerator.estimateSpeechMs("", 120, Delivery.SETUP))
    }

    @Test
    fun `each kind of beat gets its delivery`() {
        assertEquals(Delivery.CALM, DeliveryPlan.deliveryFor(seg(SegmentKind.INTRO), null))
        assertEquals(Delivery.CALM, DeliveryPlan.deliveryFor(seg(SegmentKind.OPENING_SUMMARY), null))
        assertEquals(Delivery.CALM, DeliveryPlan.deliveryFor(seg(SegmentKind.OUTRO_SUMMARY), null))
        assertEquals(Delivery.CALM, DeliveryPlan.deliveryFor(seg(SegmentKind.OUTRO_LESSONS), null))
        // quiet move: a verdict and a small swing
        assertEquals(Delivery.CALM, DeliveryPlan.deliveryFor(seg(SegmentKind.NORMAL_MOVE, classification = MoveClassification.GOOD, swing = 10), null))
        // a normal move (bigger swing), a skip-ahead hop (no verdict), a mate-less error: unchanged
        assertEquals(Delivery.NEUTRAL, DeliveryPlan.deliveryFor(seg(SegmentKind.NORMAL_MOVE, classification = MoveClassification.GOOD, swing = 60), null))
        assertEquals(Delivery.NEUTRAL, DeliveryPlan.deliveryFor(seg(SegmentKind.NORMAL_MOVE), null))
        assertEquals(Delivery.NEUTRAL, DeliveryPlan.deliveryFor(seg(SegmentKind.BLUNDER, classification = MoveClassification.BLUNDER), null))
        // the final mate and a Brilliant move: weighty
        assertEquals(Delivery.WEIGHTY, DeliveryPlan.deliveryFor(seg(SegmentKind.KEY_MOMENT, board = play("Qxf7#")), null))
        assertEquals(Delivery.WEIGHTY, DeliveryPlan.deliveryFor(seg(SegmentKind.FOUND_TACTIC, classification = MoveClassification.BRILLIANT), null))
        // line moves are brisk, even a mating one
        assertEquals(Delivery.BRISK, DeliveryPlan.deliveryFor(seg(SegmentKind.BEST_LINE, board = play("Qxf7#")), null))
        assertEquals(Delivery.BRISK, DeliveryPlan.deliveryFor(seg(SegmentKind.MISSED_TACTIC, board = play("Nxe5")), null))
        assertEquals(Delivery.BRISK, DeliveryPlan.deliveryFor(seg(SegmentKind.NORMAL_MOVE, classification = MoveClassification.FORCED), null))
        // the missed-tactic detour's explaining beat (an Annotate, not a line move) is not brisk
        assertEquals(Delivery.NEUTRAL, DeliveryPlan.deliveryFor(seg(SegmentKind.MISSED_TACTIC), null))
        // set-up: the puzzle prompt, and whatever comes right before a key move
        assertEquals(Delivery.SETUP, DeliveryPlan.deliveryFor(seg(SegmentKind.PUZZLE_PROMPT), null))
        val keyMove = seg(SegmentKind.BLUNDER, classification = MoveClassification.BLUNDER)
        assertEquals(Delivery.SETUP, DeliveryPlan.deliveryFor(seg(SegmentKind.NORMAL_MOVE), keyMove))
        assertEquals(Delivery.NEUTRAL, DeliveryPlan.deliveryFor(seg(SegmentKind.NORMAL_MOVE), seg(SegmentKind.NORMAL_MOVE)))
        // nothing spoken, nothing delivered
        assertEquals(Delivery.NEUTRAL, DeliveryPlan.deliveryFor(seg(SegmentKind.PUZZLE_PROMPT, text = " "), null))
    }

    @Test
    fun `apply sets the delivery and re-measures the estimate, and is deterministic`() {
        val segs = listOf(seg(SegmentKind.INTRO), seg(SegmentKind.NORMAL_MOVE, classification = MoveClassification.GOOD, swing = 400), seg(SegmentKind.BLUNDER, classification = MoveClassification.BLUNDER))
        val a = DeliveryPlan.apply(segs, 169)
        assertEquals(a, DeliveryPlan.apply(segs, 169))
        assertEquals(Delivery.CALM, a[0].delivery)
        assertEquals(Delivery.SETUP, a[1].delivery)
        assertEquals(Delivery.NEUTRAL, a[2].delivery)
        assertEquals(VideoScriptGenerator.estimateSpeechMs(segs[0].narration, 169, Delivery.CALM), a[0].estimatedSpeechMs)
        assertEquals(segs[2].estimatedSpeechMs, a[2].estimatedSpeechMs)
        assertEquals(segs[2], a[2])
    }

    @Test
    fun `real games get a varied, in-range delivery on every segment, with estimates that match it`() {
        val games = listOf(
            RealGameFixture.scholars, RealGameFixture.chesscom, RealGameFixture.immortal,
            RealGameFixture.game01, RealGameFixture.byrneFischer,
        )
        val kinds = HashSet<Delivery>()
        for (g in games) {
            val report = g.report(null)
            val options = NarrationOptions(speechWpm = 169)
            val script = VideoScriptGenerator(null).generate(report, g.pgn, options)
            for (s in script.segments) {
                assertTrue(s.delivery.speed in 0.9f..1.1f)
                assertEquals(
                    "estimate of '${s.narration}'",
                    VideoScriptGenerator.estimateSpeechMs(s.narration, 169, s.delivery),
                    s.estimatedSpeechMs,
                )
                kinds.add(s.delivery)
            }
            // the intro and the outro summary are calm, whatever the game
            assertEquals(Delivery.CALM, script.segments.first().delivery)
            // the same game twice gives the same deliveries
            val again = VideoScriptGenerator(null).generate(report, g.pgn, options)
            assertEquals(script.segments.map { it.delivery }, again.segments.map { it.delivery })
        }
        assertTrue("the games use several deliveries: $kinds", kinds.size >= 3)
    }

    @Test
    fun `a game ending in mate says the mating move weightily and its best lines briskly`() {
        val g = RealGameFixture.scholars
        val script = VideoScriptGenerator(null).generate(g.report(null), g.pgn, NarrationOptions(speechWpm = 169))
        val mate = script.segments.last {
            it.kind != SegmentKind.BEST_LINE && it.kind != SegmentKind.MISSED_TACTIC &&
                (it.board as? BoardDirective.PlayMove)?.san?.endsWith("#") == true
        }
        assertEquals(Delivery.WEIGHTY, mate.delivery)
        for (s in script.segments.filter { it.kind == SegmentKind.BEST_LINE }) assertEquals(Delivery.BRISK, s.delivery)
    }
}
