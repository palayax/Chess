package net.palaya.chessanalyzer.core.narration

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
        for ((name, game) in listOf("immortal" to RealGameFixture.immortal, "chesscom" to RealGameFixture.chesscom)) {
            for (side in listOf<Color?>(null, Color.WHITE, Color.BLACK)) {
                for (a in game.report(side).annotations) {
                    out.appendLine(line(name, side, a, a.text))
                }
            }
        }
        File("build/commentary_audit/after.jsonl").apply { parentFile.mkdirs() }.writeText(out.toString())
        assertTrue("both games, three sides each", out.lines().count { it.isNotBlank() } == 3 * (45 + 33))
    }
}
