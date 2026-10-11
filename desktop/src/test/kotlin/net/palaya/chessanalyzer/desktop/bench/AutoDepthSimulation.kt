package net.palaya.chessanalyzer.desktop.bench

import net.palaya.chessanalyzer.core.analysis.AdaptiveDepth
import net.palaya.chessanalyzer.core.analysis.AdaptiveDepth.Reason as R
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
 * Replays the Auto-depth policy on the recorded tiers, without the engine: pass 1 = the Quick recording, a
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
            val configs = if ("sweep3" in args) sweep3() else if ("sweep2" in args) sweep2() else if (sweep) sweepConfigs() else listOf(Triple("default", "quick", AdaptiveDepth.Config()))
            for ((name, baseTier, cfg) in configs) {
                val s = sum(benches.map { b -> score(b, simulate(b, baseTier, "deep", cfg), b.tiers.getValue(reference).evals.map { it.toEval() }) })
                println(row("auto $name", s, deepNodes))
            }
            if ("reasons" in args) reasons(benches, "standard", reference)
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
                    println(row("${b.id} auto", score(b, simulate(b, "quick", "deep", AdaptiveDepth.Config()), ref), dn))
                }
            }
        }
    }

    /**
     * Which rule finds the moves whose label changes with depth: per rule, the moves it flags on the base tier, how
     * many of them the reference labels differently (precision) and what share of all such moves it covers (recall);
     * the label pairs that change; and the changed moves no rule flags.
     */
    fun reasons(benches: List<Bench>, base: String, reference: String) {
        data class Row(var flagged: Int = 0, var changed: Int = 0, var deepNodes: Long = 0)
        val rows = LinkedHashMap<String, Row>()
        val pairs = HashMap<String, Int>()
        var changedTotal = 0
        var judged = 0
        val missed = ArrayList<String>()
        for (b in benches) {
            val baseEvals = b.tiers.getValue(base).evals.map { it.toEval() }
            val refEvals = b.tiers.getValue(reference).evals.map { it.toEval() }
            val deepRec = b.tiers.getValue("deep").evals
            val got = labels(b.game, baseEvals)
            val ref = labels(b.game, refEvals)
            val crit = AdaptiveDepth.criticalMoves(b.game, baseEvals, book, classifier, AdaptiveDepth.Config(closeGapWin = 2.0))
            for (m in 1..b.game.moves.size) {
                val r = ref[m - 1]; val g = got[m - 1]
                if (r == MoveClassification.BOOK || r == MoveClassification.FORCED) continue
                judged++
                val changed = r != g
                if (changed) { changedTotal++; pairs.merge("${g.name}->${r.name}", 1, Int::plus) }
                val reasons = crit[m].orEmpty()
                for (reason in reasons.map { it.name } + if (reasons.isNotEmpty()) listOf("ANY") else emptyList()) {
                    val row = rows.getOrPut(reason) { Row() }
                    row.flagged++; if (changed) row.changed++
                    row.deepNodes += deepRec[m - 1].nodes + deepRec[m].nodes
                }
                if (changed && reasons.isEmpty()) missed += "${b.id} ply $m ${b.game.moves[m - 1].san}: ${g.name} -> ${r.name}"
            }
        }
        println("\njudged moves $judged, label changed $changedTotal\n")
        println("| Rule | Moves flagged | Of them changed (precision) | Share of all changes (recall) | Deep nodes of both sides |\n|---|---|---|---|---|")
        for ((k, v) in rows) println(String.format(Locale.ROOT, "| %s | %d | %d (%.0f%%) | %.0f%% | %.0f M |", k, v.flagged, v.changed, 100.0 * v.changed / v.flagged, 100.0 * v.changed / changedTotal, v.deepNodes / 1e6))
        println("\nchanges: " + pairs.entries.sortedByDescending { it.value }.joinToString { "${it.key} ${it.value}" })
        println("not flagged:\n  " + missed.joinToString("\n  "))
    }

    /** The cap and the ranking under it, Quick base, default rules. */
    private fun sweep3(): List<Triple<String, String, AdaptiveDepth.Config>> {
        val orders = mapOf(
            "A" to listOf(R.MISTAKE, R.MATE, R.SWING, R.UNSTABLE, R.BOUNDARY),
            "B" to listOf(R.MATE, R.BOUNDARY, R.UNSTABLE, R.MISTAKE, R.SWING),
            "C" to listOf(R.BOUNDARY, R.MATE, R.MISTAKE, R.SWING, R.UNSTABLE),
            "D" to listOf(R.MATE, R.UNSTABLE, R.BOUNDARY, R.MISTAKE, R.SWING),
        )
        val out = ArrayList<Triple<String, String, AdaptiveDepth.Config>>()
        for ((o, order) in orders) for (cap in listOf(0.4, 0.5, 0.6, 0.7)) {
            out += Triple("order $o cap=$cap", "quick", AdaptiveDepth.Config(maxFraction = cap, priority = order))
        }
        for (cap in listOf(0.4, 0.5, 0.6)) out += Triple("standard base, order B cap=$cap", "standard", AdaptiveDepth.Config(maxFraction = cap, priority = orders.getValue("B")))
        return out
    }

    /** Quick as the base tier, around the rules without GREAT. */
    private fun sweep2(): List<Triple<String, String, AdaptiveDepth.Config>> {
        val noGreat = AdaptiveDepth.Reason.entries.toSet() - AdaptiveDepth.Reason.GREAT
        val d = AdaptiveDepth.Config(maxFraction = 1.0, rules = noGreat)
        val out = ArrayList<Triple<String, String, AdaptiveDepth.Config>>()
        out += Triple("q noGreat", "quick", d)
        out += Triple("q all", "quick", d.copy(rules = AdaptiveDepth.Reason.entries.toSet()))
        for (v in listOf(0.1, 0.15, 0.2, 0.35, 0.5)) out += Triple("q noGreat boundary=$v", "quick", d.copy(boundaryMargin = v))
        for (v in listOf(5.0, 12.0, 20.0)) out += Triple("q noGreat swing=$v", "quick", d.copy(swingWin = v))
        for (v in listOf(0.0, 1.5, 5.0)) out += Triple("q noGreat greatGap=$v", "quick", d.copy(greatGapMarginWin = v))
        for (v in listOf(3.0, 8.0, 101.0)) out += Triple("q noGreat unstable=$v", "quick", d.copy(unstableWin = v))
        for (v in listOf(1, 2)) out += Triple("q noGreat rounds=$v", "quick", d.copy(maxRounds = v))
        for (r in listOf(AdaptiveDepth.Reason.MISTAKE, AdaptiveDepth.Reason.MATE, AdaptiveDepth.Reason.SWING, AdaptiveDepth.Reason.BOUNDARY)) {
            out += Triple("q noGreat -$r", "quick", d.copy(rules = noGreat - r))
        }
        for (f in listOf(0.5, 0.6, 0.7)) out += Triple("q noGreat cap=$f", "quick", d.copy(maxFraction = f))
        out += Triple("s noGreat", "standard", d)
        for (v in listOf(0.1, 0.15)) out += Triple("s noGreat boundary=$v", "standard", d.copy(boundaryMargin = v))
        return out
    }

    private fun sweepConfigs(): List<Triple<String, String, AdaptiveDepth.Config>> {
        val d = AdaptiveDepth.Config()
        val R = AdaptiveDepth.Reason.entries.toSet()
        val noGreat = R - AdaptiveDepth.Reason.GREAT
        val core = setOf(AdaptiveDepth.Reason.MISTAKE, AdaptiveDepth.Reason.MATE, AdaptiveDepth.Reason.SWING, AdaptiveDepth.Reason.UNSTABLE)
        val out = ArrayList<Triple<String, String, AdaptiveDepth.Config>>()
        for (base in listOf("standard", "quick")) {
            for (f in listOf(0.2, 0.3, 0.4, 0.5, 0.6, 1.0)) out += Triple("$base cap=$f", base, d.copy(maxFraction = f))
            for (f in listOf(0.3, 0.4, 1.0)) out += Triple("$base noGreat cap=$f", base, d.copy(maxFraction = f, rules = noGreat))
            for (f in listOf(0.3, 1.0)) out += Triple("$base core cap=$f", base, d.copy(maxFraction = f, rules = core))
            for (f in listOf(0.3, 1.0)) out += Triple("$base core+boundary cap=$f", base, d.copy(maxFraction = f, rules = core + AdaptiveDepth.Reason.BOUNDARY))
            for (f in listOf(0.3, 1.0)) out += Triple("$base core+great cap=$f", base, d.copy(maxFraction = f, rules = core + AdaptiveDepth.Reason.GREAT))
            for (v in listOf(0.0, 0.1, 0.4)) out += Triple("$base boundary=$v cap=0.4", base, d.copy(maxFraction = 0.4, boundaryMargin = v))
            out += Triple("$base close=2 cap=1", base, d.copy(maxFraction = 1.0, closeGapWin = 2.0))
            out += Triple("$base rounds=1 cap=0.4", base, d.copy(maxFraction = 0.4, maxRounds = 1))
            out += Triple("$base unstable=3 cap=0.4", base, d.copy(maxFraction = 0.4, unstableWin = 3.0))
        }
        return out
    }
}
