package net.palaya.chessanalyzer.desktop.bench

import net.palaya.chessanalyzer.core.analysis.AdaptiveDepth
import net.palaya.chessanalyzer.core.analysis.MoveClassification
import net.palaya.chessanalyzer.core.analysis.MoveClassifier
import net.palaya.chessanalyzer.core.analysis.OpeningBook
import net.palaya.chessanalyzer.core.analysis.PositionEval
import net.palaya.chessanalyzer.core.chess.Position
import net.palaya.chessanalyzer.core.chess.parseUci
import net.palaya.chessanalyzer.core.pgn.PgnGame
import net.palaya.chessanalyzer.core.tactics.StaticExchangeEvaluator
import net.palaya.chessanalyzer.desktop.analysis.ReportFactory
import java.nio.file.Path
import java.util.Locale

/**
 * Replays the Auto-depth policy on the recorded tiers, without the engine: pass 1 = the Standard recording, a
 * deepened position takes the Deep recording's evaluation and node count. Scored against a SECOND, independent
 * Deep run (`deep2`): a deepened move compared with the very run it was copied from would agree by construction.
 */
object AutoDepthSimulation {

    class Bench(val id: String, val game: PgnGame, val tiers: Map<String, GameRecord>)

    private val book: OpeningBook by lazy { ReportFactory.openingBook() }
    private val classifier = MoveClassifier(StaticExchangeEvaluator())

    fun labels(game: PgnGame, evals: List<PositionEval>): List<MoveClassification> {
        var pos = game.startFen?.let { Position.fromFen(it) } ?: Position.startPosition()
        return game.moves.mapIndexed { i, m ->
            val move = pos.parseUci(m.uci)
            val after = pos.makeMove(move)
            val label = classifier.classify(
                pos, move, evals[i], evals[i + 1],
                book.isBookPosition(pos.toFen()), book.isBookPosition(after.toFen()), ply = i + 1,
            )
            pos = after
            label
        }
    }

    /** Coarse grade: the labels a reader acts on (the mistakes one by one, Great/Brilliant, everything fine). */
    fun grade(c: MoveClassification): String = when (c) {
        MoveClassification.BRILLIANT, MoveClassification.GREAT -> "great"
        MoveClassification.BEST, MoveClassification.EXCELLENT, MoveClassification.GOOD -> "ok"
        else -> c.name
    }

    data class Score(
        val moves: Int,
        val judged: Int,
        val exact: Int,
        val exactJudged: Int,
        val graded: Int,
        val mistakesRef: Int,
        val mistakesHit: Int,
        val nodes: Long,
        val deepened: Int,
        val positions: Int,
    )

    data class Run(val evals: List<PositionEval>, val nodes: Long, val deepened: Int)

    fun simulate(b: Bench, baseTier: String, deepTier: String, config: AdaptiveDepth.Config): Run {
        val base = b.tiers.getValue(baseTier).evals
        val deep = b.tiers.getValue(deepTier).evals
        val baseEvals = base.map { it.toEval() }
        val current = baseEvals.toMutableList()
        val deepened = LinkedHashSet<Int>()
        var nodes = base.sumOf { it.nodes }
        var round = 1
        while (true) {
            val picks = AdaptiveDepth.plan(b.game, baseEvals, current, deepened, round, book, classifier, config)
            if (picks.isEmpty()) break
            for (p in picks) {
                current[p.index] = deep[p.index].toEval()
                nodes += deep[p.index].nodes
                deepened += p.index
            }
            round++
        }
        return Run(current, nodes, deepened.size)
    }

    fun score(b: Bench, run: Run, reference: List<PositionEval>): Score {
        val ref = labels(b.game, reference)
        val got = labels(b.game, run.evals)
        val judgedIdx = ref.indices.filter { ref[it] != MoveClassification.BOOK && ref[it] != MoveClassification.FORCED }
        return Score(
            moves = ref.size,
            judged = judgedIdx.size,
            exact = ref.indices.count { ref[it] == got[it] },
            exactJudged = judgedIdx.count { ref[it] == got[it] },
            graded = ref.indices.count { grade(ref[it]) == grade(got[it]) },
            mistakesRef = ref.count { it.isMistake },
            mistakesHit = ref.indices.count { ref[it].isMistake && ref[it] == got[it] },
            nodes = run.nodes,
            deepened = run.deepened,
            positions = run.evals.size,
        )
    }

    fun sum(scores: List<Score>) = Score(
        scores.sumOf { it.moves }, scores.sumOf { it.judged }, scores.sumOf { it.exact }, scores.sumOf { it.exactJudged },
        scores.sumOf { it.graded }, scores.sumOf { it.mistakesRef }, scores.sumOf { it.mistakesHit },
        scores.sumOf { it.nodes }, scores.sumOf { it.deepened }, scores.sumOf { it.positions },
    )

    fun load(root: Path, out: Path, sources: List<String>, tiers: List<String>): List<Bench> = sources.mapNotNull { s ->
        val id = BenchGames.idOf(s)
        val recs = tiers.associateWith { BenchGames.read(out, it, id) }
        if (recs.values.any { it == null }) null else Bench(id, BenchGames.load(root, s), recs.mapValues { it.value!! })
    }

    fun row(name: String, s: Score, deepNodes: Long): String = String.format(
        Locale.ROOT, "| %s | %.1f%% (%d/%d) | %.1f%% | %.1f%% | %d/%d | %.0f M | %.0f%% | %d/%d (%.0f%%) |",
        name, 100.0 * s.exactJudged / s.judged, s.exactJudged, s.judged, 100.0 * s.exact / s.moves, 100.0 * s.graded / s.moves,
        s.mistakesHit, s.mistakesRef, s.nodes / 1e6, 100.0 * s.nodes / deepNodes, s.deepened, s.positions,
        100.0 * s.deepened / s.positions,
    )

    const val HEADER = "| Run | Exact, engine-judged moves | Exact, all moves | Graded | Mistakes kept | Nodes | vs Deep | Deepened |\n|---|---|---|---|---|---|---|---|"

    fun run(root: Path, out: Path, args: List<String>) {
        val sets = mapOf("calibration" to BenchGames.CALIBRATION, "held-out" to BenchGames.HELD_OUT)
        val reference = args.firstOrNull { it.startsWith("ref=") }?.removePrefix("ref=") ?: "deep2"
        val sweep = "sweep" in args
        for ((setName, sources) in sets) {
            val benches = load(root, out, sources, listOf("quick", "standard", "deep", reference))
            if (benches.isEmpty()) { println("$setName: not recorded yet"); continue }
            println("\n### $setName (${benches.joinToString { it.id }}), reference = $reference\n")
            val deepNodes = benches.sumOf { b -> b.tiers.getValue("deep").evals.sumOf { it.nodes } }
            fun plain(tier: String) = sum(benches.map { b ->
                val r = b.tiers.getValue(tier).evals
                score(b, Run(r.map { it.toEval() }, r.sumOf { it.nodes }, if (tier.startsWith("deep")) r.size else 0), b.tiers.getValue(reference).evals.map { it.toEval() })
            })
            println(HEADER)
            for (t in listOf("quick", "standard", "deep")) println(row(t, plain(t), deepNodes))
            val configs = if (sweep) sweepConfigs() else listOf("default" to AdaptiveDepth.Config())
            for ((name, cfg) in configs) {
                val s = sum(benches.map { b -> score(b, simulate(b, "standard", "deep", cfg), b.tiers.getValue(reference).evals.map { it.toEval() }) })
                println(row("auto $name", s, deepNodes))
            }
            val real = load(root, out, sources, listOf("auto", reference))
            if (real.size == benches.size) {
                val s = sum(real.map { b ->
                    val r = b.tiers.getValue("auto").evals
                    score(b, Run(r.map { it.toEval() }, r.sumOf { it.nodes }, r.count { it.requestedDepth == BenchTier.DEEP.depth }), b.tiers.getValue(reference).evals.map { it.toEval() })
                })
                println(row("auto (real run)", s, deepNodes))
            }
            if ("perGame" in args) {
                for (b in benches) {
                    val ref = b.tiers.getValue(reference).evals.map { it.toEval() }
                    val dn = b.tiers.getValue("deep").evals.sumOf { it.nodes }
                    println(row("${b.id} standard", score(b, Run(b.tiers.getValue("standard").evals.map { it.toEval() }, b.tiers.getValue("standard").evals.sumOf { it.nodes }, 0), ref), dn))
                    println(row("${b.id} auto", score(b, simulate(b, "standard", "deep", AdaptiveDepth.Config()), ref), dn))
                }
            }
        }
    }

    private fun sweepConfigs(): List<Pair<String, AdaptiveDepth.Config>> {
        val d = AdaptiveDepth.Config()
        val out = ArrayList<Pair<String, AdaptiveDepth.Config>>()
        out += "default" to d
        for (v in listOf(4.0, 6.0, 10.0, 15.0, 101.0)) out += "swing=$v" to d.copy(swingWin = v)
        for (v in listOf(0.0, 0.1, 0.4, 0.6)) out += "boundary=$v" to d.copy(boundaryMargin = v)
        for (v in listOf(0.0, 1.5, 5.0)) out += "greatGap=$v" to d.copy(greatGapMarginWin = v)
        for (v in listOf(1.0, 2.0, 4.0)) out += "close=$v" to d.copy(closeGapWin = v)
        for (v in listOf(3.0, 8.0, 101.0)) out += "unstable=$v" to d.copy(unstableWin = v)
        for (v in listOf(0.3, 0.4, 0.6, 1.0)) out += "maxFraction=$v" to d.copy(maxFraction = v)
        for (v in listOf(1, 2, 5)) out += "rounds=$v" to d.copy(maxRounds = v)
        return out
    }
}
