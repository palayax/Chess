package net.palaya.chessanalyzer.core.narration

import net.palaya.chessanalyzer.core.analysis.BestLineCaption
import net.palaya.chessanalyzer.core.analysis.BestLines
import net.palaya.chessanalyzer.core.analysis.MoveAnnotation
import net.palaya.chessanalyzer.core.analysis.TacticInstance
import net.palaya.chessanalyzer.core.analysis.TacticSignificance
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

    /**
     * NS (ANALYSIS_SPEC 9.9): every sentence of the narrated story that makes a claim of its own, for the four recorded
     * games (the three audited ones and Byrne-Fischer) and each side, as JSON lines to
     * `core/build/commentary_audit/story.jsonl` - the input of `scripts/audit_commentary.py story`. A `game` record per
     * game and side carries what the generator took from the annotations and the audit may not re-derive (each ply's
     * move, class and missed motif); a `part` record per sentence carries the words, the sentence kind and the plies it is
     * about. Every claim in the sentence is re-derived from the recorded engine numbers and python-chess.
     */
    @Test
    fun dumpStory() {
        val out = StringBuilder()
        // The four recorded games, and variants of them that bring the other story sentences out: a game cut short (no
        // mate, an error with no missed motif), a clean one, and the same games with a drawn result.
        fun withResult(g: RealGameFixture.Game, result: String) =
            RealGameFixture.Game(g.pgn.copy(result = result, tags = g.pgn.tags + ("Result" to result)), g.evals)
        val games = listOf(
            Triple("immortal", "immortal", RealGameFixture.immortal), Triple("chesscom", "chesscom", RealGameFixture.chesscom),
            Triple("game01", "game01", RealGameFixture.game01), Triple("byrne_fischer", "byrne_fischer", RealGameFixture.byrneFischer),
            Triple("immortal first 36 plies, drawn", "immortal", withResult(RealGameFixture.immortal.firstPlies(36), "1/2-1/2")),
            Triple("byrne_fischer first 20 plies, drawn", "byrne_fischer", withResult(RealGameFixture.byrneFischer.firstPlies(20), "1/2-1/2")),
            Triple("game01 first 36 plies, drawn", "game01", withResult(RealGameFixture.game01.firstPlies(36), "1/2-1/2")),
            Triple("immortal first 30 plies, White won", "immortal", withResult(RealGameFixture.immortal.firstPlies(30), "1-0")),
            Triple("chesscom first 3 plies", "chesscom", RealGameFixture.chesscom.firstPlies(3)),
            Triple("chesscom first 14 plies", "chesscom", RealGameFixture.chesscom.firstPlies(14)),
            Triple("chesscom first 24 plies", "chesscom", RealGameFixture.chesscom.firstPlies(24)),
            Triple("immortal first 24 plies", "immortal", RealGameFixture.immortal.firstPlies(24)),
            Triple("game01 first 20 plies", "game01", RealGameFixture.game01.firstPlies(20)),
            Triple("game01 first 28 plies", "game01", RealGameFixture.game01.firstPlies(28)),
            Triple("game01 first 34 plies", "game01", RealGameFixture.game01.firstPlies(34)),
            Triple("game01 first 40 plies", "game01", RealGameFixture.game01.firstPlies(40)),
            Triple("game01 first 50 plies", "game01", RealGameFixture.game01.firstPlies(50)),
            Triple("byrne_fischer first 40 plies", "byrne_fischer", RealGameFixture.byrneFischer.firstPlies(40)),
            Triple("byrne_fischer first 60 plies", "byrne_fischer", RealGameFixture.byrneFischer.firstPlies(60))
        )
        fun norm(t: String) = t.trim().replace(Regex("\\s+"), " ").replace(" .", ".").lowercase()
        var parts = 0
        for ((label, base, game) in games) {
            for (side in listOf<Color?>(null, Color.WHITE, Color.BLACK)) {
                val report = game.report(side)
                val options = NarrationOptions(speechWpm = 169, pace = VideoPace.NORMAL)
                val (script, story) = VideoScriptGenerator(side).generateWithStory(report, game.pgn, options)
                val pruned = TacticSignificance.prune(report, options.significanceThresholdCp)
                val plies = pruned.annotations.joinToString(",") { a ->
                    val best = a.bestMoveUci
                    val missed = if (best != null && best != a.uci) {
                        a.tacticsMissed.filter { it.confidence >= 0.6 }.maxWithOrNull(compareBy({ it.confidence }, { it.materialSwing }))
                    } else null
                    "{\"ply\":${a.ply},\"moveNumber\":${a.moveNumber},\"color\":${q(a.color.name)},\"san\":${q(a.san)},\"uci\":${q(a.uci)}," +
                        "\"cls\":${q(a.classification.name)},\"best\":${q(a.bestMoveUci)},\"missed\":${q(missed?.type?.name)}}"
                }
                out.appendLine(
                    "{\"rec\":\"game\",\"game\":${q(label)},\"base\":${q(base)},\"side\":${q(side?.name)},\"result\":${q(report.result)}," +
                        "\"plies\":[$plies]}"
                )
                var cursor = 0
                for (p in story) {
                    val needle = norm(p.text)
                    val idx = (cursor until script.segments.size).firstOrNull { needle in norm(script.segments[it].narration) }
                        ?: script.segments.indices.firstOrNull { needle in norm(script.segments[it].narration) }
                        ?: -1
                    if (idx >= 0) cursor = idx
                    val seg = script.segments.getOrNull(idx)
                    out.appendLine(
                        "{\"rec\":\"part\",\"game\":${q(label)},\"side\":${q(side?.name)},\"id\":${q(p.id)},\"text\":${q(p.text.trim().replace(Regex("\\s+"), " "))}," +
                            "\"plies\":[${p.plies.joinToString(",")}],\"segment\":$idx,\"kind\":${q(seg?.kind?.name)},\"segPly\":${seg?.ply}}"
                    )
                    parts++
                }
            }
        }
        File("build/commentary_audit/story.jsonl").apply { parentFile.mkdirs() }.writeText(out.toString())
        assertTrue("a story to audit: $parts parts", parts > 100)
    }
}
