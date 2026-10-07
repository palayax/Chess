package net.palaya.chessanalyzer.core.narration

import net.palaya.chessanalyzer.core.chess.Position
import net.palaya.chessanalyzer.core.chess.parseUci
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V3 measurement: lays the scripts of the Immortal Game and games/game01.txt out on a time axis (the
 * estimated speech at the Kokoro default's 169 wpm, the app's 900 ms floor and 250 ms gap) and writes,
 * per key moment, how long the position before the move is on screen, how long the result is held, the
 * time per move of every played-out line, and whether the board jumps. Output:
 * `core/build/pace/<label>.txt`. A measurement, not an assertion: [PaceTimingTest] holds the contract.
 */
class PaceMeasurementDumpTest {

    private fun boardKey(fen: String): String = fen.split(' ').take(2).joinToString(" ")

    private fun after(fen: String, uci: String): String = try {
        val p = Position.fromFen(fen)
        p.makeMove(p.parseUci(uci)).toFen()
    } catch (e: Exception) {
        fen
    }

    /** The board a segment starts on and ends on. Cards have none. */
    private fun startFen(d: BoardDirective): String? = when (d) {
        is BoardDirective.Hold -> d.fen
        is BoardDirective.PlayMove -> d.fen
        is BoardDirective.Annotate -> d.fen
        is BoardDirective.PlayLine -> d.fen
        is BoardDirective.Card -> null
    }

    private fun endFen(d: BoardDirective): String? = when (d) {
        is BoardDirective.PlayMove -> after(d.fen, d.uci)
        is BoardDirective.PlayLine -> d.uciMoves.fold(d.fen) { f, u -> after(f, u) }
        else -> startFen(d)
    }

    private class Timed(val seg: ScriptSegment, val start: Long, val leadIn: Long, val speech: Long, val total: Long)

    private fun layout(s: VideoScript): List<Timed> {
        var t = 0L
        return s.segments.map { seg ->
            val speech = seg.estimatedSpeechMs.coerceAtLeast(ScriptTiming.MIN_SEGMENT_MS)
            val total = seg.leadInMs + speech + seg.holdAfterMs + ScriptTiming.INTER_SEGMENT_GAP_MS
            Timed(seg, t, seg.leadInMs, speech, total).also { t += total }
        }
    }

    private fun report(label: String, s: VideoScript): String {
        val tl = layout(s)
        val out = StringBuilder()
        val total = tl.sumOf { it.total }
        out.appendLine("== $label: ${s.segments.size} segments, estimated ${total / 1000.0} s (totalEstimatedMs ${s.totalEstimatedMs / 1000.0} s)")
        out.appendLine("-- every segment: idx kind ply board leadIn speech hold total  | narration")
        for (t in tl) {
            val d = t.seg.board
            val b = when (d) {
                is BoardDirective.PlayMove -> "PlayMove ${d.san}"
                is BoardDirective.Annotate -> "Annotate"
                is BoardDirective.Hold -> "Hold"
                is BoardDirective.Card -> "Card"
                is BoardDirective.PlayLine -> "PlayLine"
            }
            out.appendLine(
                "%3d %-14s %4s %-18s %5d %6d %5d %6d | %s".format(
                    t.seg.index, t.seg.kind, t.seg.ply ?: "-", b, t.leadIn, t.speech, t.seg.holdAfterMs, t.total,
                    t.seg.narration.take(90)
                )
            )
        }

        out.appendLine("-- key moments (a real move with a verdict, or a mate): pre = the position before the move on screen before it moves, post = the result held after the slide")
        val anim = 400L
        var keyCount = 0
        var noPause = 0
        for ((i, t) in tl.withIndex()) {
            val d = t.seg.board as? BoardDirective.PlayMove ?: continue
            val cls = t.seg.classification ?: continue
            val key = cls.name in setOf("BRILLIANT", "GREAT", "BLUNDER", "MISTAKE", "MISS") || d.san.endsWith("#") ||
                t.seg.kind == SegmentKind.FOUND_TACTIC
            if (!key) continue
            keyCount++
            val before = boardKey(d.fen)
            val li = t.seg.leadIn
            var pre = li?.pauseMs ?: 0L
            var j = if (li != null && li.approachUci.isNotEmpty()) -1 else i - 1
            while (j >= 0) {
                val p = tl[j]
                val end = endFen(p.seg.board) ?: break
                if (boardKey(end) != before) break
                pre += if (p.seg.board is BoardDirective.PlayMove) p.total - p.leadIn - anim else p.total
                if (p.seg.board is BoardDirective.PlayMove) break
                j--
            }
            val afterKey = boardKey(after(d.fen, d.uci))
            var post = t.total - t.leadIn - anim
            var k = i + 1
            while (k < tl.size) {
                val n = tl[k]
                val st = startFen(n.seg.board) ?: break
                if (boardKey(st) != afterKey || n.seg.board is BoardDirective.PlayMove) break
                post += n.total
                k++
            }
            if (pre < 1000) noPause++
            out.appendLine(
                "ply %3d %-8s %-10s %-13s pre %5d ms  post %5d ms  speech %5d ms  speechStartsWithMove=%s%s".format(
                    t.seg.ply, d.san, cls, t.seg.kind, pre, post, t.speech, t.leadIn == 0L,
                    if (li != null && li.approachUci.isNotEmpty()) "  approach ${li.approachSan.joinToString(" ")} at ${li.stepMs} ms/move" else ""
                )
            )
        }
        out.appendLine("key moments: $keyCount, with less than 1.0 s on the position before the move: $noPause")

        out.appendLine("-- played-out lines (consecutive MISSED_TACTIC PlayMove beats): ms per move, then the final position")
        var i = 0
        while (i < tl.size) {
            if (tl[i].seg.kind == SegmentKind.MISSED_TACTIC && tl[i].seg.board is BoardDirective.PlayMove) {
                val run = ArrayList<Timed>()
                while (i < tl.size && tl[i].seg.kind == SegmentKind.MISSED_TACTIC && tl[i].seg.board is BoardDirective.PlayMove) {
                    run.add(tl[i]); i++
                }
                val finalHold = tl.getOrNull(i)?.takeIf { it.seg.kind == SegmentKind.MISSED_TACTIC }?.total ?: 0L
                val sans = run.map { (it.seg.board as BoardDirective.PlayMove).san }
                val per = run.map { it.total }
                val lastSettled = run.last().total - run.last().leadIn - anim
                out.appendLine(
                    "line at ply ${run.first().seg.ply} (${sans.joinToString(" ")}): per move ${per.joinToString(", ")} ms " +
                        "(min ${per.min()}, mean ${per.average().toLong()}); last position ${lastSettled + finalHold} ms"
                )
            } else {
                i++
            }
        }

        out.appendLine("-- board jumps: a segment that starts on a position the previous one did not end on (skipped moves are not shown)")
        var jumps = 0
        for (n in 1 until tl.size) {
            val prevEnd = endFen(tl[n - 1].seg.board) ?: continue
            val st = tl[n].seg.leadIn?.fen ?: startFen(tl[n].seg.board) ?: continue
            if (boardKey(prevEnd) != boardKey(st)) {
                jumps++
                out.appendLine("seg ${tl[n].seg.index} (ply ${tl[n].seg.ply}, ${tl[n].seg.kind}) jumps")
            }
        }
        out.appendLine("jumps: $jumps")
        return out.toString()
    }

    @Test
    fun dump() {
        val label = System.getProperty("pace.label") ?: "current"
        val dir = File("build/pace").apply { mkdirs() }
        for ((name, game) in listOf("immortal" to RealGameFixture.immortal, "game01" to RealGameFixture.game01)) {
            for (pace in VideoPace.entries) {
                val r = game.report(null)
                val s = VideoScriptGenerator(null).generate(r, game.pgn, NarrationOptions(speechWpm = 169, pace = pace))
                File(dir, "${label}_${name}_${pace.name.lowercase()}.txt").writeText(report("$name @ $pace", s))
            }
        }
        // The overhead of each pace on every recorded game, against the story and the §9.7 budget.
        val summary = StringBuilder("game plies budget_s story_s pace pacing_s total_s overhead_pct\n")
        val recorded = listOf(
            "scholars" to RealGameFixture.scholars, "chesscom" to RealGameFixture.chesscom,
            "immortal" to RealGameFixture.immortal, "game01" to RealGameFixture.game01,
            "byrne_fischer" to RealGameFixture.byrneFischer
        )
        for ((name, game) in recorded) {
            val r = game.report(null)
            for (pace in VideoPace.entries) {
                val s = VideoScriptGenerator(null).generate(r, game.pgn, NarrationOptions(speechWpm = 169, pace = pace))
                val budget = VideoScriptGenerator.budgetMs((r.annotations.size + 1) / 2)
                summary.append(
                    "%s %d %.1f %.1f %s %.1f %.1f %.1f\n".format(
                        name, r.annotations.size, budget / 1000.0, s.storyMs / 1000.0, pace, s.pacingMs / 1000.0,
                        s.totalEstimatedMs / 1000.0, 100.0 * s.pacingMs / s.storyMs
                    )
                )
            }
        }
        File(dir, "${label}_summary.txt").writeText(summary.toString())
        assertTrue(File(dir, "${label}_immortal_normal.txt").length() > 0)
    }
}
