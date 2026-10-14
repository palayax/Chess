package net.palaya.chessanalyzer.core.narration

import net.palaya.chessanalyzer.core.analysis.MoveClassification
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * How one beat is SPOKEN (ANALYSIS_SPEC 9.10, NC): its speed and the breath between its sentences. The words
 * never change with it. The default is today's delivery (speed 1, gaps as they were), so a segment that does not
 * set it, and a consumer that ignores it, behave as before.
 *
 * @param speed the voice's speed relative to its normal one (1 = normal, below 1 slower). Kept within
 *   [MIN_SPEED]..[MAX_SPEED]: Kokoro produces artefacts outside it.
 * @param gapScale multiplies the silence between the beat's sentences (the base gap is 220 ms, 320 ms after a
 *   question), the result clamped to [MIN_GAP_MS]..[MAX_GAP_MS] by [gapMs].
 */
data class Delivery(val speed: Float = 1f, val gapScale: Float = 1f) {

    init {
        require(speed in MIN_SPEED..MAX_SPEED) { "delivery speed $speed outside $MIN_SPEED..$MAX_SPEED" }
        require(gapScale > 0f && gapScale <= 3f) { "delivery gapScale $gapScale outside (0, 3]" }
    }

    /** True for today's delivery: nothing is scaled, nothing changes in any cache key. */
    val isNeutral: Boolean get() = isOne(speed) && isOne(gapScale)

    /** The silence between two sentences, given the [baseMs] the voice uses (220, or 320 after a question). */
    fun gapMs(baseMs: Long): Long =
        if (isOne(gapScale)) baseMs else (baseMs * gapScale).roundToLong().coerceIn(MIN_GAP_MS, MAX_GAP_MS)

    companion object {
        const val MIN_SPEED = 0.9f
        const val MAX_SPEED = 1.1f
        const val MIN_GAP_MS = 120L
        const val MAX_GAP_MS = 600L

        val NEUTRAL = Delivery()

        /** Intro, summaries, lessons and quiet moves. */
        val CALM = Delivery(speed = 0.94f, gapScale = 1.25f)

        /** The sentence(s) before a key move or a missed-tactic prompt. */
        val SETUP = Delivery(speed = 0.90f, gapScale = 1.4f)

        /** Forced sequences, a played-out line, the engine's line after a key moment. */
        val BRISK = Delivery(speed = 1.08f, gapScale = 0.8f)

        /** The final mate and Brilliant moves. */
        val WEIGHTY = Delivery(speed = 0.95f, gapScale = 1.5f)

        private fun isOne(v: Float): Boolean = abs(v - 1f) < 1e-4f
    }
}

/**
 * Which [Delivery] each beat gets (ANALYSIS_SPEC 9.10): a pure, deterministic function of the finished story's
 * segments (kind, classification, board, narration, and the next beat), with no clock and no randomness, so the
 * delivery is the same on every run and the narration cache key stays reproducible. First rule that matches wins:
 *
 *  1. blank narration: [Delivery.NEUTRAL] (nothing is spoken);
 *  2. [Delivery.WEIGHTY]: a played move that gives checkmate, or a Brilliant move (not a move of a line);
 *  3. [Delivery.BRISK]: a [SegmentKind.BEST_LINE] move, a move of a played-out missed tactic (a
 *     [SegmentKind.MISSED_TACTIC] `PlayMove`), a FORCED move;
 *  4. [Delivery.CALM]: the cards of the story (intro, opening summary, outro summary, lessons);
 *  5. [Delivery.SETUP]: a [SegmentKind.PUZZLE_PROMPT], and the beat right before a key move ([isKeyMove]) when it
 *     is not a key move itself (the moves of a sequence are told at the normal pace);
 *  6. [Delivery.CALM]: a quiet move (a NORMAL_MOVE with a verdict whose swing is at most [QUIET_SWING_CP]);
 *  7. otherwise [Delivery.NEUTRAL] (normal moves, phase shifts, chapter hops, errors, tactics).
 */
object DeliveryPlan {

    /** A move that moved the evaluation by no more than this (centipawns, either way) is a quiet move. */
    const val QUIET_SWING_CP = 25

    fun deliveryFor(segment: ScriptSegment, next: ScriptSegment?): Delivery {
        if (segment.narration.isBlank()) return Delivery.NEUTRAL
        val move = segment.board as? BoardDirective.PlayMove
        val isLineMove = segment.kind == SegmentKind.BEST_LINE || segment.kind == SegmentKind.MISSED_TACTIC
        if (!isLineMove && (move?.san?.endsWith("#") == true || segment.classification == MoveClassification.BRILLIANT)) {
            return Delivery.WEIGHTY
        }
        if (segment.kind == SegmentKind.BEST_LINE ||
            (segment.kind == SegmentKind.MISSED_TACTIC && move != null) ||
            segment.classification == MoveClassification.FORCED
        ) {
            return Delivery.BRISK
        }
        // The cards of the story (intro, summaries, lessons) are calm even right before a key move.
        when (segment.kind) {
            SegmentKind.INTRO, SegmentKind.OPENING_SUMMARY, SegmentKind.OUTRO_SUMMARY, SegmentKind.OUTRO_LESSONS ->
                return Delivery.CALM
            else -> Unit
        }
        if (segment.kind == SegmentKind.PUZZLE_PROMPT || (next != null && isKeyMove(next) && !isKeyMove(segment))) return Delivery.SETUP
        if (segment.kind == SegmentKind.NORMAL_MOVE) {
            val swing = segment.evalSwingCp
            if (segment.classification != null && swing != null && abs(swing) <= QUIET_SWING_CP) return Delivery.CALM
        }
        return Delivery.NEUTRAL
    }

    /** A beat that is a key move: an error, a found tactic, a mate or a Brilliant/Great move, told in its own beat. */
    fun isKeyMove(segment: ScriptSegment): Boolean {
        if (segment.kind == SegmentKind.BEST_LINE || segment.narration.isBlank()) return false
        val c = segment.classification
        val mate = (segment.board as? BoardDirective.PlayMove)?.san?.endsWith("#") == true
        return mate || segment.kind == SegmentKind.FOUND_TACTIC || segment.kind == SegmentKind.BLUNDER ||
            c == MoveClassification.BLUNDER || c == MoveClassification.MISTAKE || c == MoveClassification.MISS ||
            c == MoveClassification.BRILLIANT || c == MoveClassification.GREAT
    }

    /**
     * [segments] with every beat's [ScriptSegment.delivery] set and its speech estimate re-measured for it
     * ([VideoScriptGenerator.estimateSpeechMs] at [wpm], scaled by 1/speed, the sentence pauses by the gap scale),
     * so the 9.7 budget measures the story as it will be spoken.
     */
    fun apply(segments: List<ScriptSegment>, wpm: Int): List<ScriptSegment> =
        segments.mapIndexed { i, s ->
            val d = deliveryFor(s, segments.getOrNull(i + 1))
            if (d.isNeutral && s.delivery.isNeutral) s
            else s.copy(delivery = d, estimatedSpeechMs = VideoScriptGenerator.estimateSpeechMs(s.narration, wpm, d))
        }
}
