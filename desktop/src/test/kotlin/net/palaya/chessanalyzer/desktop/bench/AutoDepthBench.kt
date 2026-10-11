package net.palaya.chessanalyzer.desktop.bench

import java.nio.file.Path
import java.nio.file.Paths

/**
 * Auto-depth host measurement (docs/ANALYSIS_SPEC.md §8.5, RUN_LOG "Auto-depth"). Run outside Gradle:
 *
 *     gradlew :desktop:testClasses :desktop:installDist
 *     java -cp "desktop/build/classes/kotlin/test;desktop/build/install/palaya-review/lib/(star)" \
 *         net.palaya.chessanalyzer.desktop.bench.AutoDepthBench record <label> <outDir>
 *
 * Modes:
 *  - `record <quick|standard|deep|deep2...> <outDir>`: every bench game at that tier, the app's search
 *    exactly (BenchEngine); one JSON per game, a finished game is not searched again.
 *  - `simulate <outDir>`: the Auto-depth policy replayed on the recorded tiers (no engine), plus the
 *    agreement table.
 *  - `auto <outDir>`: a real two-pass Auto run (pass 1 at -Dbench.autoBase, default Standard, then the policy, then the deeper searches).
 */
object AutoDepthBench {
    @JvmStatic
    fun main(args: Array<String>) {
        val root = Paths.get(System.getProperty("palaya.repoRoot") ?: ".").toAbsolutePath().normalize()
        when (args.getOrNull(0)) {
            "record" -> record(root, args[1], Paths.get(args[2]), args.drop(3).toSet())
            "simulate" -> AutoDepthSimulation.run(root, Paths.get(args[1]), args.drop(2))
            "auto" -> AutoDepthRealRun.run(root, Paths.get(args[1]), args.drop(2).toSet())
            else -> error("usage: record <tier> <outDir> | simulate <outDir> | auto <outDir>")
        }
    }

    val stockfishRelative = "pc/bin/stockfish/stockfish-windows-x86-64-universal.exe"

    fun stockfish(root: Path): Path {
        // -Dbench.stockfish: a renamed copy of the binary (another job on the host killed Stockfish by its name).
        System.getProperty("bench.stockfish")?.let { return Paths.get(it) }
        val local = root.resolve(stockfishRelative)
        if (local.toFile().isFile) return local
        // A worktree has no pc/bin (gitignored): the main checkout's copy.
        return Paths.get("C:/Claude/ChessAnalyzer").resolve(stockfishRelative)
    }

    private fun record(root: Path, label: String, out: Path, only: Set<String>) {
        val tier = BenchTier.of(label).withTimeCapFactor()
        ResilientEngine(stockfish(root)).use { engine ->
            for (source in BenchGames.CALIBRATION + BenchGames.HELD_OUT) {
                val id = BenchGames.idOf(source)
                if (only.isNotEmpty() && id !in only) continue
                if (BenchGames.read(out, label, id) != null) continue
                val game = BenchGames.load(root, source)
                engine.newGame()
                val fens = BenchGames.fens(game)
                // A per-position checkpoint, so a dead run continues where it stopped.
                val partialLabel = "$label.partial"
                val evals = BenchGames.read(out, partialLabel, id)?.evals
                    ?.withIndex()?.takeWhile { (i, e) -> i < fens.size && e.fen == fens[i] }?.map { it.value }
                    ?.toMutableList() ?: mutableListOf()
                if (evals.isNotEmpty()) println("$label $id resuming at ${evals.size}")
                for (i in evals.size until fens.size) {
                    evals += engine.evaluate(i, fens[i], tier).also {
                        println("$label $id $i/${fens.size - 1} d${it.depth}/${it.requestedDepth} ${it.nodes} nodes ${it.timeMs} ms")
                    }
                    BenchGames.write(out, partialLabel, GameRecord(id, label, engine.name, evals))
                }
                BenchGames.write(out, label, GameRecord(id, label, engine.name, evals))
            }
        }
    }
}
