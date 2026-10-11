package net.palaya.chessanalyzer.desktop.bench

import net.palaya.chessanalyzer.core.analysis.AdaptiveDepth
import net.palaya.chessanalyzer.core.analysis.MoveClassifier
import net.palaya.chessanalyzer.core.tactics.StaticExchangeEvaluator
import net.palaya.chessanalyzer.desktop.analysis.ReportFactory
import java.nio.file.Path

/**
 * A real Auto run per game, the way `AnalysisService` does it: `ucinewgame`, pass 1 (Standard, or -Dbench.autoBase) over every
 * position, then the policy's rounds, each picked position searched again at Deep on the same engine (its hash
 * as pass 1 left it). The assembled evaluations go to `<out>/auto/<game>.json`; a deepened position's node count
 * there is its pass-1 nodes plus its pass-2 nodes.
 */
object AutoDepthRealRun {
    /**
     * `-Dbench.autoBase=quick|standard` (default standard): the first pass's tier. `-Dbench.reusePass1=true`: take the
     * first pass from that tier's recording and run only the second pass, on a fresh engine after `ucinewgame` (what the
     * app does when the first pass comes from the eval cache). The result goes to `auto_<base>`.
     */
    fun run(root: Path, out: Path, only: Set<String>) {
        val baseTier = BenchTier.of(System.getProperty("bench.autoBase") ?: "standard")
        val reuse = System.getProperty("bench.reusePass1") == "true"
        val label = "auto_${baseTier.name}"
        val book = ReportFactory.openingBook()
        val classifier = MoveClassifier(StaticExchangeEvaluator())
        ResilientEngine(AutoDepthBench.stockfish(root)).use { engine ->
            for (source in BenchGames.CALIBRATION + BenchGames.HELD_OUT) {
                val id = BenchGames.idOf(source)
                if (only.isNotEmpty() && id !in only) continue
                if (BenchGames.read(out, label, id) != null) continue
                val game = BenchGames.load(root, source)
                engine.newGame()
                val fens = BenchGames.fens(game)
                val pass1 = if (reuse) BenchGames.read(out, baseTier.name, id)!!.evals else fens.mapIndexed { i, fen ->
                    engine.evaluate(i, fen, baseTier.withTimeCapFactor()).also {
                        println("auto $id pass1 $i/${fens.size - 1} d${it.depth}/${it.requestedDepth} ${it.nodes} nodes")
                    }
                }
                val base = pass1.map { it.toEval() }
                val records = pass1.toMutableList()
                val current = base.toMutableList()
                val deepened = LinkedHashSet<Int>()
                var round = 1
                while (true) {
                    val picks = AdaptiveDepth.plan(game, base, current, deepened, round, book, classifier)
                    if (picks.isEmpty()) break
                    for (p in picks) {
                        val deep = engine.evaluate(p.index, fens[p.index], BenchTier.DEEP.withTimeCapFactor())
                        println("auto $id round $round deepen ${p.index} (${p.reasons.joinToString(",") { it.logName }}) d${deep.depth}/18 ${deep.nodes} nodes")
                        records[p.index] = deep.copy(nodes = pass1[p.index].nodes + deep.nodes, timeMs = pass1[p.index].timeMs + deep.timeMs)
                        current[p.index] = deep.toEval()
                        deepened += p.index
                    }
                    round++
                }
                println("auto $id done: ${deepened.size} of ${fens.size} deepened in ${round - 1} rounds")
                BenchGames.write(out, label, GameRecord(id, label, engine.name, records))
            }
        }
    }
}
