package net.palaya.chessanalyzer.video

import net.palaya.chessanalyzer.core.narration.ScriptSegment
import net.palaya.chessanalyzer.core.narration.VideoScript

/**
 * A [VideoScript] laid out on a real time axis once TTS durations are known (or defaulted to
 * [ScriptSegment.estimatedSpeechMs] for silent segments). Shared by [VideoExporter] (drives frame
 * timing) and in-app playback (drives when the board advances and captions change) so both
 * consumers of one script always agree on timing.
 */
data class TimedSegment(
    val segment: ScriptSegment,
    val startMs: Long,
    /** How long the voice (or its silent stand-in) actually takes. */
    val speechDurationMs: Long,
    /** [speechDurationMs] + [ScriptSegment.holdAfterMs] + the inter-segment pacing gap. */
    val totalDurationMs: Long,
) {
    val endMs: Long get() = startMs + totalDurationMs
}

data class ScriptTimeline(val segments: List<TimedSegment>, val totalDurationMs: Long) {
    /** The segment active at [timeMs], or the last segment if past the end. */
    fun segmentAt(timeMs: Long): TimedSegment? {
        if (segments.isEmpty()) return null
        val hit = segments.firstOrNull { timeMs >= it.startMs && timeMs < it.endMs }
        return hit ?: if (timeMs >= totalDurationMs) segments.last() else segments.first()
    }
}

object TimelineBuilder {
    /** A floor so a mis-estimated/near-empty segment still gets visible board time. */
    const val MIN_SEGMENT_MS = 900L

    /** Silence between segments — also what keeps a slightly-early `onDone` from clipping speech. */
    const val INTER_SEGMENT_GAP_MS = 250L

    fun build(script: VideoScript, synthResults: List<NarrationSynthesizer.Result>): ScriptTimeline {
        val byIndex = synthResults.associateBy { it.segmentIndex }
        var cursor = 0L
        val timed = ArrayList<TimedSegment>(script.segments.size)
        for (seg in script.segments) {
            val speechMs = (byIndex[seg.index]?.durationMs ?: seg.estimatedSpeechMs)
                .coerceAtLeast(MIN_SEGMENT_MS)
            val total = speechMs + seg.holdAfterMs + INTER_SEGMENT_GAP_MS
            timed.add(TimedSegment(seg, cursor, speechMs, total))
            cursor += total
        }
        return ScriptTimeline(timed, cursor)
    }
}
