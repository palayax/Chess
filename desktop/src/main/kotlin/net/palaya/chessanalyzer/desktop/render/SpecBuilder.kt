package net.palaya.chessanalyzer.desktop.render

import net.palaya.chessanalyzer.core.analysis.EvalFormat
import net.palaya.chessanalyzer.core.chess.Position
import net.palaya.chessanalyzer.core.chess.Square
import net.palaya.chessanalyzer.core.chess.parseUci
import net.palaya.chessanalyzer.desktop.storyboard.Beat
import net.palaya.chessanalyzer.desktop.storyboard.BeatEval
import net.palaya.chessanalyzer.desktop.storyboard.Storyboard
import net.palaya.chessanalyzer.desktop.timeline.TimedBeat
import net.palaya.chessanalyzer.desktop.timeline.Timeline
import net.palaya.chessanalyzer.desktop.timeline.TimelineBuilder

/**
 * What the frame at time t shows: storyboard cue + timeline position → [FrameSpec]. The
 * animation rules are the app's `SegmentFrameBuilder` (ease-in-out slide, settled position after
 * the slide, excursion tint driven by the beat kind), with the §7 timings (350 ms slide, 800 ms
 * per variation step).
 */
class SpecBuilder(private val storyboard: Storyboard, private val timeline: Timeline) {

    private val whiteAtBottom = storyboard.userColor != "BLACK"
    private val beatsById = storyboard.beats.associateBy { it.id }
    private val topName: String
    private val bottomName: String

    init {
        val h = storyboard.header
        val white = h.white + (h.whiteElo?.let { " ($it)" } ?: "")
        val black = h.black + (h.blackElo?.let { " ($it)" } ?: "")
        topName = if (whiteAtBottom) black else white
        bottomName = if (whiteAtBottom) white else black
    }

    /** One move of a cue, precomputed. */
    private class Step(val before: String, val after: String, val from: Int, val to: Int, val piece: Char?, val checkAfter: Int?)

    private class Visual(val placement: String, val check: Int?, val steps: List<Step>)

    private val visuals: Map<String, Visual> = storyboard.beats.associate { it.id to visual(it) }
    private val chapterAt: List<String>
    private val evalAt: List<BeatEval?>

    init {
        val firstBeatIndex = storyboard.chapters.associate { ch -> storyboard.beats.indexOfFirst { it.id == ch.firstBeat } to ch.title }
        var ch = ""
        chapterAt = storyboard.beats.indices.map { i -> firstBeatIndex[i]?.let { ch = it }; ch }
        var ev: BeatEval? = null
        evalAt = storyboard.beats.map { b -> b.eval?.let { ev = it }; ev }
    }

    private fun visual(b: Beat): Visual {
        val cue = b.board.firstOrNull() ?: return Visual("", null, emptyList())
        val fen = cue.fen ?: return Visual("", null, emptyList())
        val start = try { Position.fromFen(fen) } catch (_: Exception) { return Visual("", null, emptyList()) }
        val steps = ArrayList<Step>()
        if (cue.cue == "play_move" || cue.cue == "play_line") {
            var pos = start
            for (u in cue.uci) {
                val move = try { pos.parseUci(u) } catch (_: Exception) { break }
                val after = pos.makeMove(move)
                steps.add(Step(placement(pos), placement(after), move.from.index, move.to.index, pos.pieceAt(move.from)?.toFenChar(), checkSquare(after)))
                pos = after
            }
        }
        return Visual(placement(start), checkSquare(start), steps)
    }

    fun specAt(tMs: Long, tb: TimedBeat): FrameSpec {
        val beat = beatsById.getValue(tb.id)
        val e = tMs - tb.startMs
        val subtitle = subtitleAt(tMs, tb)
        val cue = beat.board.firstOrNull()
        if (cue?.cue == "card") {
            val subLines = when (beat.kind) {
                "INTRO" -> introLines()
                "OUTRO_SUMMARY", "OUTRO_LESSONS" -> outroLines()
                else -> emptyList()
            }
            return FrameSpec(card = CardSpec(cue.heading ?: "", cue.lines, subLines, subtitle))
        }
        val ev = evalAt[tb.index]
        var spec = FrameSpec(
            whiteAtBottom = whiteAtBottom,
            excursion = beat.excursion,
            excursionLabel = if (beat.excursion) (cue?.label ?: "WHAT COULD HAVE HAPPENED") else null,
            evalWinWhite = ev?.winPercentWhite ?: 50.0,
            evalText = ev?.let { EvalFormat.score(it.cp, it.mateIn) } ?: "",
            topName = topName,
            bottomName = bottomName,
            chapter = chapterAt[tb.index],
            moveLabel = moveLabel(beat),
            classification = beat.classification,
            beatCaption = beat.caption,
            subtitle = subtitle,
        )
        val v = visuals.getValue(beat.id)
        when (cue?.cue) {
            "hold", "annotate" -> {
                val last = cue.lastMove?.takeIf { it.length >= 4 }
                spec = spec.copy(
                    placement = v.placement,
                    checkSquare = v.check,
                    lastMoveFrom = last?.let { sq(it.substring(0, 2)) },
                    lastMoveTo = last?.let { sq(it.substring(2, 4)) },
                    highlights = cue.highlights.mapNotNull { sq(it) },
                    arrows = cue.arrows.mapNotNull { a -> sq(a.from)?.let { f -> sq(a.to)?.let { t -> ArrowSpec(f, t, a.role) } } },
                )
            }
            "play_move", "play_line" -> {
                if (v.steps.isEmpty()) return spec.copy(placement = v.placement, checkSquare = v.check)
                val stepMs = if (cue.cue == "play_move") TimelineBuilder.MOVE_ANIM_MS else TimelineBuilder.LINE_STEP_MS
                val k = (e / stepMs).toInt().coerceIn(0, v.steps.size - 1)
                val within = e - k * stepMs
                val s = v.steps[k]
                val lastStep = k == v.steps.size - 1
                spec = if (within < TimelineBuilder.MOVE_ANIM_MS && !(lastStep && e >= v.steps.size * stepMs)) {
                    val p = easeInOut(within.toDouble() / TimelineBuilder.MOVE_ANIM_MS)
                    spec.copy(
                        placement = s.before, lastMoveFrom = s.from, lastMoveTo = s.to,
                        animPiece = s.piece, animFrom = s.from, animTo = s.to, animProgressMilli = Math.round(p * 1000).toInt(),
                    )
                } else {
                    val settledFor = within - TimelineBuilder.MOVE_ANIM_MS
                    val badge = cue.cue == "play_move" && beat.classification != null && settledFor >= BADGE_DELAY_MS
                    spec.copy(
                        placement = s.after, lastMoveFrom = s.from, lastMoveTo = s.to, checkSquare = s.checkAfter,
                        badgeSquare = if (badge) s.to else null, badgeClassification = if (badge) beat.classification else null,
                    )
                }
            }
            else -> spec = spec.copy(placement = v.placement)
        }
        return spec
    }

    /** The line being heard, shown from its first sample until its end plus a short linger. */
    private fun subtitleAt(tMs: Long, tb: TimedBeat): String {
        val line = tb.lines.lastOrNull { it.audioStartMs <= tMs } ?: return ""
        return if (tMs < line.audioStartMs + line.durationMs + SUBTITLE_LINGER_MS) line.caption else ""
    }

    private fun moveLabel(b: Beat): String {
        val cue = b.board.firstOrNull()
        val n = b.moveNumber ?: return ""
        if (cue?.cue == "play_move" && cue.san.isNotEmpty() && !b.excursion) {
            return if (b.color == "BLACK") "$n... ${cue.san[0]}" else "$n. ${cue.san[0]}"
        }
        return "Move $n"
    }

    private fun introLines(): List<String> {
        val h = storyboard.header
        val out = ArrayList<String>()
        out.add("White: ${h.white}${h.whiteElo?.let { " ($it)" } ?: ""}   ·   Black: ${h.black}${h.blackElo?.let { " ($it)" } ?: ""}")
        out.add("Result: ${h.result}")
        h.opening?.let { out.add(if (h.eco != null) "$it (${h.eco})" else it) }
        return out
    }

    private fun outroLines(): List<String> {
        val s = storyboard.summary
        val h = storyboard.header
        return listOfNotNull(
            s.whiteAccuracy?.let { String.format(java.util.Locale.ROOT, "%s: %.1f%% accuracy%s", h.white, it, s.whiteEstimatedRating?.let { r -> " (est. $r)" } ?: "") },
            s.blackAccuracy?.let { String.format(java.util.Locale.ROOT, "%s: %.1f%% accuracy%s", h.black, it, s.blackEstimatedRating?.let { r -> " (est. $r)" } ?: "") },
        )
    }

    companion object {
        const val BADGE_DELAY_MS = 150L
        const val SUBTITLE_LINGER_MS = 400L

        fun placement(p: Position): String = p.toFen().substringBefore(' ')

        fun checkSquare(p: Position): Int? = if (p.isInCheck()) p.kingSquare(p.sideToMove).index else null

        fun sq(alg: String): Int? = try { Square.fromAlgebraic(alg).index } catch (_: Exception) { null }

        fun easeInOut(t: Double): Double {
            val c = t.coerceIn(0.0, 1.0)
            return if (c < 0.5) 2 * c * c else 1 - (-2 * c + 2) * (-2 * c + 2) / 2
        }
    }
}
