package net.palaya.chessanalyzer.desktop.timeline

import kotlinx.serialization.Serializable
import net.palaya.chessanalyzer.desktop.script.ScriptFile
import net.palaya.chessanalyzer.desktop.storyboard.Beat
import net.palaya.chessanalyzer.desktop.storyboard.Storyboard
import net.palaya.chessanalyzer.desktop.tts.AudioManifest
import kotlin.math.max

// timeline.json (design §3.5): beats and lines on one ms axis, read by both the renderer and the
// mixer so picture and sound cannot disagree.

@Serializable
data class Timeline(
    val schema: String = "palaya.timeline/1",
    val fps: Int,
    val width: Int,
    val height: Int,
    val totalMs: Long,
    val beats: List<TimedBeat>,
)

@Serializable
data class TimedBeat(
    val id: String,
    val index: Int,
    val startMs: Long,
    val endMs: Long,
    /** When the board animation of the beat's cue ends (start + animation); == startMs if static. */
    val animEndMs: Long,
    val speechEndMs: Long,
    val holdAfterMs: Long,
    val lines: List<TimedLine>,
)

@Serializable
data class TimedLine(
    val id: String,
    /** Where the WAV's first sample lands. */
    val audioStartMs: Long,
    /** The WAV's full measured duration; never truncated. */
    val durationMs: Long,
    /** The voiced span (leading/trailing silence removed), for caption timing. */
    val voicedStartMs: Long,
    val voicedEndMs: Long,
    val wav: String,
    val caption: String,
)

/**
 * §7, audio drives the picture. Per beat:
 *
 * ```
 * lineStart(0) = beatStart
 * lineStart(n) = lineStart(n-1) + audio(n-1) + gap(n-1)       gap 220 ms, 320 after "?"
 * speechEnd    = lineStart(last) + audio(last)
 * contentEnd   = max(speechEnd, beatStart + visualMs)          visualMs = cue animation + settle
 * beatEnd      = max(contentEnd + holdAfter + 250, beatStart + 900)
 * ```
 * The measured WAV duration is the speech duration; nothing is ever truncated.
 */
object TimelineBuilder {
    const val GAP_MS = 220L
    const val GAP_AFTER_QUESTION_MS = 320L
    const val TAIL_MS = 250L
    const val MIN_BEAT_MS = 900L
    /** One move's slide (VIDEO_FORMAT §3; the app uses 400). */
    const val MOVE_ANIM_MS = 350L
    /** A variation step: slide + time to read the position. */
    const val LINE_STEP_MS = 800L
    /** Time a settled move stays visible before a silent beat may end. */
    const val SETTLE_MS = 300L
    /** Opening/closing silence around the whole video. */
    const val LEAD_IN_MS = 300L
    const val TAIL_OUT_MS = 1500L

    fun animationMs(beat: Beat): Long {
        val cue = beat.board.firstOrNull() ?: return 0L
        return when (cue.cue) {
            "play_move" -> MOVE_ANIM_MS
            "play_line" -> cue.uci.size * LINE_STEP_MS
            else -> 0L
        }
    }

    fun build(storyboard: Storyboard, script: ScriptFile, manifest: AudioManifest, fps: Int, width: Int, height: Int): Timeline {
        val audio = manifest.lines.associateBy { it.id }
        val scriptBeats = script.beats.associateBy { it.id }
        var cursor = LEAD_IN_MS
        val out = ArrayList<TimedBeat>(storyboard.beats.size)
        for ((index, beat) in storyboard.beats.withIndex()) {
            val start = cursor
            val lines = scriptBeats[beat.id]?.lines.orEmpty()
            var t = start
            val timed = ArrayList<TimedLine>(lines.size)
            for ((i, line) in lines.withIndex()) {
                val a = audio[line.id] ?: error("audio manifest has no line ${line.id}; re-run --from audio")
                val dur = a.durationMs.toLong()
                timed.add(
                    TimedLine(
                        id = line.id,
                        audioStartMs = t,
                        durationMs = dur,
                        voicedStartMs = t + a.leadingSilenceMs,
                        voicedEndMs = t + (dur - a.trailingSilenceMs).coerceAtLeast(a.leadingSilenceMs.toLong()),
                        wav = a.wav,
                        caption = line.caption,
                    )
                )
                t += dur
                if (i < lines.size - 1) t += if (line.text.trimEnd().endsWith("?")) GAP_AFTER_QUESTION_MS else GAP_MS
            }
            val speechEnd = t
            val anim = animationMs(beat)
            val visual = if (anim > 0) anim + SETTLE_MS else 0L
            val contentEnd = max(speechEnd, start + visual)
            val end = max(contentEnd + beat.holdAfterMs + TAIL_MS, start + MIN_BEAT_MS)
            out.add(TimedBeat(beat.id, index, start, end, start + anim, speechEnd, beat.holdAfterMs, timed))
            cursor = end
        }
        return Timeline(fps = fps, width = width, height = height, totalMs = cursor + TAIL_OUT_MS, beats = out)
    }

    /** Frames in the video: the audio is exactly [Timeline.totalMs] long, the video within half a frame. */
    fun frameCount(t: Timeline): Int = Math.round(t.totalMs * t.fps / 1000.0).toInt()
}
