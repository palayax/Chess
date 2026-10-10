package net.palaya.chessanalyzer.core.narration

import net.palaya.chessanalyzer.core.analysis.BestLineCaption
import net.palaya.chessanalyzer.core.analysis.BestLines
import net.palaya.chessanalyzer.core.analysis.MoveAnnotation
import net.palaya.chessanalyzer.core.analysis.TacticInstance
import net.palaya.chessanalyzer.core.chess.Color
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Writes every annotation text and walkthrough of the two recorded games, for no side, White and Black,
 * as JSON lines to `core/build/commentary_audit/after.jsonl` - the input of `scripts/audit_commentary.py`
 * (docs/COMMENTARY_AUDIT.md). Like the narration catalogue, the file lands in the build directory: a
 * test that wrote into docs/ would be a side effect.
 */
class CommentaryAuditDumpTest {
    private fun q(s: String?): String =
        if (s == null) "null" else "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""

    private fun tactic(t: TacticInstance): String =
        "{\"type\":${q(t.type.name)},\"by\":${q(t.byColor.name)},\"uci\":${q(t.moveUci)}," +
            "\"targets\":[${t.targetSquares.joinToString(",") { q(it.toString()) }}]," +
            "\"involved\":[${t.involvedSquares.joinToString(",") { q(it.toString()) }}]," +
            "\"swing\":${t.materialSwing},\"conf\":${t.confidence},\"description\":${q(t.description)}}"

    private fun line(name: String, side: Color?, a: MoveAnnotation, text: String): String {
        val sim = a.simulation
        val simJson = if (sim == null) "null" else
            "{\"type\":${q(sim.tactic.type.name)},\"startFen\":${q(sim.startFen)}," +
                "\"pvUci\":[${sim.pvUci.joinToString(",") { q(it) }}],\"pvSan\":[${sim.pvSan.joinToString(",") { q(it) }}]," +
                "\"intro\":${q(SimulationIntro.text(sim))},\"steps\":[${sim.perPlyExplanation.joinToString(",") { q(it) }}]," +
                "\"payoff\":${q(sim.payoffDescription)},\"tactic\":${tactic(sim.tactic)}}"
        return "{\"game\":${q(name)},\"side\":${q(side?.name)},\"ply\":${a.ply},\"moveNumber\":${a.moveNumber}," +
            "\"color\":${q(a.color.name)},\"san\":${q(a.san)},\"uci\":${q(a.uci)},\"cls\":${q(a.classification.name)}," +
            "\"loss\":${a.loss},\"wpBefore\":${a.winPercentBefore},\"wpAfter\":${a.winPercentAfter}," +
            "\"mateB\":${a.mateInBefore},\"mateA\":${a.mateInAfter},\"best\":${q(a.bestMoveSan)},\"bestUci\":${q(a.bestMoveUci)}," +
            "\"fenBefore\":${q(a.fenBefore)},\"text\":${q(text)}," +
            "\"played\":[${a.tacticsPlayed.joinToString(",") { tactic(it) }}]," +
            "\"missed\":[${a.tacticsMissed.joinToString(",") { tactic(it) }}]," +
            "\"threats\":[${a.threatsAllowed.joinToString(",") { tactic(it) }}],\"sim\":$simJson}"
    }

    @Test
    fun dump() {
        val out = StringBuilder()
        val games = listOf("immortal" to RealGameFixture.immortal, "chesscom" to RealGameFixture.chesscom, "game01" to RealGameFixture.game01)
        for ((name, game) in games) {
            for (side in listOf<Color?>(null, Color.WHITE, Color.BLACK)) {
                for (a in game.report(side).annotations) {
                    out.appendLine(line(name, side, a, a.text))
                }
            }
        }
        File("build/commentary_audit/after.jsonl").apply { parentFile.mkdirs() }.writeText(out.toString())
        assertTrue("three games, three sides each", out.lines().count { it.isNotBlank() } == 3 * (45 + 33 + RealGameFixture.game01.pgn.moves.size))
    }

    /**
     * V2 (ANALYSIS_SPEC 6.2): every line the Board can display for every move of the two recorded games
     * plus game01, for each side, with its caption, and the line the video plays for the same move (Normal
     * pace), as JSON lines to `core/build/commentary_audit/best_lines.jsonl` - the input of
     * `scripts/audit_commentary.py lines`, which replays each with python-chess.
     */
    @Test
    fun dumpBestLines() {
        val out = StringBuilder()
        val returns = StringBuilder()
        val games = listOf("immortal" to RealGameFixture.immortal, "chesscom" to RealGameFixture.chesscom, "game01" to RealGameFixture.game01)
        for ((name, game) in games) {
            for (side in listOf<Color?>(null, Color.WHITE, Color.BLACK)) {
                val report = game.report(side)
                val script = VideoScriptGenerator(side).generate(report, game.pgn, NarrationOptions(speechWpm = 169, pace = VideoPace.NORMAL))
                val video = script.segments.mapNotNull { s -> s.bestLine?.let { s.ply!! to it } }.toMap()
                // V4: what the segments after each key moment actually say and show (its spoken moves, then the return).
                val after = script.segments.filter { it.bestLine != null }.associate { k ->
                    k.ply!! to script.segments.subList(k.index + 1, k.index + 2 + k.bestLine!!.uci.size)
                }
                for (a in report.annotations) {
                    val lines = BestLines.linesFor(a).joinToString(",") { l ->
                        "{\"multiPv\":${l.multiPv},\"depth\":${l.depth},\"startFen\":${q(l.startFen)}," +
                            "\"uci\":[${l.ucis.joinToString(",") { q(it) }}],\"san\":[${l.sans.joinToString(",") { q(it) }}]," +
                            "\"scoreCp\":${l.scoreCp},\"mateIn\":${l.mateIn},\"caption\":${q(BestLineCaption.text(l, side))}}"
                    }
                    val v = video[a.ply]
                    val videoJson = if (v == null) "null" else
                        "{\"fen\":${q(v.fen)},\"uci\":[${v.uci.joinToString(",") { q(it) }}],\"san\":[${v.san.joinToString(",") { q(it) }}]," +
                            "\"captions\":[${v.captions.joinToString(",") { q(it) }}]," +
                            "\"says\":[${after.getValue(a.ply).dropLast(1).joinToString(",") { q(it.narration) }}]," +
                            "\"sayFens\":[${after.getValue(a.ply).dropLast(1).joinToString(",") { q((it.board as BoardDirective.PlayMove).fen) }}]," +
                            "\"sayUci\":[${after.getValue(a.ply).dropLast(1).joinToString(",") { q((it.board as BoardDirective.PlayMove).uci) }}]," +
                            "\"sayKinds\":[${after.getValue(a.ply).joinToString(",") { q(it.kind.name) }}]," +
                            "\"back\":${q(after.getValue(a.ply).last().narration)}," +
                            "\"backFen\":${q((after.getValue(a.ply).last().board as BoardDirective.Annotate).fen)}}"
                    out.appendLine(
                        "{\"game\":${q(name)},\"side\":${q(side?.name)},\"ply\":${a.ply},\"san\":${q(a.san)},\"uci\":${q(a.uci)}," +
                            "\"cls\":${q(a.classification.name)},\"fenBefore\":${q(a.fenBefore)},\"lines\":[$lines],\"video\":$videoJson}"
                    )
                }
                // V4: every place the narration says the board is back in the game, with what the board shows
                // and what came just before it (a best line's last move, or a detour's payoff).
                for ((i, seg) in script.segments.withIndex()) {
                    if (!seg.narration.contains("Back to the game")) continue
                    val a = report.annotations[seg.ply!! - 1]
                    val fen = (seg.board as? BoardDirective.Annotate)?.fen
                    returns.appendLine(
                        "{\"game\":${q(name)},\"side\":${q(side?.name)},\"ply\":${a.ply},\"fenBefore\":${q(a.fenBefore)}," +
                            "\"boardFen\":${q(fen)},\"prevKind\":${q(script.segments[i - 1].kind.name)},\"narration\":${q(seg.narration)}}"
                    )
                }
            }
        }
        File("build/commentary_audit/best_lines.jsonl").apply { parentFile.mkdirs() }.writeText(out.toString())
        File("build/commentary_audit/video_returns.jsonl").writeText(returns.toString())
        assertTrue("some returns to the game", returns.isNotEmpty())
        assertTrue("three games, three sides each", out.lines().count { it.isNotBlank() } == 3 * (45 + 33 + RealGameFixture.game01.pgn.moves.size))
    }
}
