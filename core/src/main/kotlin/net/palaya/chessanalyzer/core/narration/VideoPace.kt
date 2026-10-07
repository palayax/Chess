package net.palaya.chessanalyzer.core.narration

/**
 * How fast the board moves through the key moments of the narrated review (ANALYSIS_SPEC 9.8, V3).
 * The owner's words: the analysis was "a bit too fast for key moves/sequences".
 *
 * The pace never touches the narration audio: the same sentences are spoken by the same voice at the
 * same rate (a narration clip cached at one pace is the clip played at every pace). It changes only
 * the silent board time around the speech:
 *
 *  - [keyLeadInMs]: before a key move is played, the position is held still, with the moving piece's
 *    square and its destination lit, for this long and in silence. Then the piece moves and the
 *    narration starts. A key move is a game move the review dwells on: a brilliant or great move, a
 *    move told at DWELL or FULL length (the error played after its detour), a found tactic with its
 *    own beat, and the mate. When the beat before is already a still picture of that position (the
 *    "back to the game" beat after a detour, a puzzle), that picture is the pause.
 *  - [keyHoldAfterMs]: after the key move's narration, the position it produced stays on screen for
 *    this long before the next beat.
 *  - [lineMoveMinMs]: every move of a played-out sequence is on screen for at least this long. That
 *    is the rate of the one or two game moves played silently before a key move (the replies the
 *    story skipped, which used to be jumped over), and the floor for a detour's narrated line moves.
 *  - [lineFinalHoldMs]: the line's final position (its payoff) is held this much longer.
 *
 * The length budget of ANALYSIS_SPEC 9.7 is NOT scaled by the pace: it holds the story (the speech
 * and the puzzle pauses), which is therefore the same at every pace, and the pace time comes on top,
 * capped at 15 percent of the budget (spec 9.8).
 *
 * [NORMAL] is the reference: about 1.2 s before a key move and about 1.5 s per line move, the rates
 * the owner asked for. [RELAXED] is the app's default, because the owner's complaint was that the
 * review was too fast at exactly these moments; [BRISK] is the old feel, for someone who has seen
 * enough reviews to want the quick version (still never below the readable floors measured in V3).
 */
enum class VideoPace(
    val keyLeadInMs: Long,
    val keyHoldAfterMs: Long,
    val lineMoveMinMs: Long,
    val lineFinalHoldMs: Long,
) {
    RELAXED(keyLeadInMs = 1_500L, keyHoldAfterMs = 1_500L, lineMoveMinMs = 2_000L, lineFinalHoldMs = 1_500L),
    NORMAL(keyLeadInMs = 1_200L, keyHoldAfterMs = 1_000L, lineMoveMinMs = 1_500L, lineFinalHoldMs = 1_000L),
    BRISK(keyLeadInMs = 700L, keyHoldAfterMs = 500L, lineMoveMinMs = 1_100L, lineFinalHoldMs = 500L);

    companion object {
        /** What the app starts on (V3): the owner asked for slower key moments. */
        val DEFAULT: VideoPace = RELAXED

        /** A persisted name, tolerating anything this build does not know (null for absent or unknown). */
        fun fromPersistedOrNull(name: String?): VideoPace? = name?.let { n -> entries.firstOrNull { it.name == n } }
    }
}

/**
 * The four silent times of a [VideoPace], as the generator applies them. Normally exactly the pace's
 * own values; [scaled] down only when a game's pace time would pass its cap (ANALYSIS_SPEC 9.8,
 * `VideoScriptGenerator.pacingCapMs`). A line move never drops below [MIN_LINE_STEP_MS], the slide
 * plus a moment to see where the piece landed.
 */
data class PaceTimes(
    val keyLeadInMs: Long,
    val keyHoldAfterMs: Long,
    val lineMoveMinMs: Long,
    val lineFinalHoldMs: Long,
) {
    fun scaled(factor: Double): PaceTimes {
        val f = factor.coerceIn(0.0, 1.0)
        return PaceTimes(
            keyLeadInMs = (keyLeadInMs * f).toLong(),
            keyHoldAfterMs = (keyHoldAfterMs * f).toLong(),
            lineMoveMinMs = maxOf((lineMoveMinMs * f).toLong(), MIN_LINE_STEP_MS),
            lineFinalHoldMs = (lineFinalHoldMs * f).toLong(),
        )
    }

    companion object {
        const val MIN_LINE_STEP_MS = ScriptTiming.MOVE_ANIMATION_MS + 300L

        fun of(pace: VideoPace) = PaceTimes(pace.keyLeadInMs, pace.keyHoldAfterMs, pace.lineMoveMinMs, pace.lineFinalHoldMs)
    }
}

/**
 * The timing constants every consumer of a [VideoScript] lays segments out with: the app's timeline
 * (`TimelineBuilder`, shared by the in-app player and the MP4 exporter) and the pacing measurements.
 * One place, so the measured numbers are the played ones.
 */
object ScriptTiming {
    /** A floor so a mis-estimated or near-empty segment still gets visible board time. */
    const val MIN_SEGMENT_MS = 900L

    /** Silence between segments; also what keeps a slightly early end of speech from clipping it. */
    const val INTER_SEGMENT_GAP_MS = 250L

    /** How long one move's slide takes on the board (the renderer's "about 400 ms, not a snap"). */
    const val MOVE_ANIMATION_MS = 400L
}
