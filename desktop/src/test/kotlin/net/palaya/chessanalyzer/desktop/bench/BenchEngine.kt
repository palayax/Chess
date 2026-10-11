package net.palaya.chessanalyzer.desktop.bench

import kotlinx.serialization.Serializable
import net.palaya.chessanalyzer.core.analysis.EngineLineInput
import net.palaya.chessanalyzer.core.analysis.PositionEval
import net.palaya.chessanalyzer.core.chess.Position
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.nio.file.Path
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/*
 * Host measurement harness for Auto-depth (docs/ANALYSIS_SPEC.md §8.5). Not a test and not part of the
 * PC producer: it is run by hand with `java` outside Gradle (a run takes an hour), see AutoDepthBench.
 *
 * The search is the app's (`StockfishEngine.analyze` + `AnalysisService`): `go depth D nodes N movetime T`,
 * MultiPV 3, Threads 4, Hash 96, `ucinewgame` once per game and the hash kept from position to position,
 * a checkmate/stalemate never searched, and the lines chosen by the ConsistentLines rule (spec §8.2,
 * restated below because `:engine` is an Android library the host cannot load).
 */

/** One search tier: what `SearchBudget` + `AnalysisStrength` give the app (spec §8.1). */
data class BenchTier(val name: String, val depth: Int, val nodes: Long, val movetimeMs: Long) {
    /**
     * `-Dbench.timeCapFactor=F` multiplies the time cap. The host was shared with another job while these runs
     * were made (about 0.25 M nodes/s per engine instead of 1.5 M); the node budget is the real limit (spec §8.1)
     * and the time cap a phone's safety net, so it is widened rather than let the host's load decide a search.
     */
    fun withTimeCapFactor(): BenchTier = copy(movetimeMs = movetimeMs * (System.getProperty("bench.timeCapFactor")?.toLong() ?: 1L))

    companion object {
        val QUICK = BenchTier("quick", 12, 4_000_000L, 30_000L)
        val STANDARD = BenchTier("standard", 14, 25_000_000L, 150_000L)
        val DEEP = BenchTier("deep", 18, 45_000_000L, 270_000L)
        fun of(name: String): BenchTier = listOf(QUICK, STANDARD, DEEP).first { name.startsWith(it.name) }
    }
}

@Serializable
data class LineDto(val multiPv: Int, val scoreCp: Int? = null, val mateIn: Int? = null, val depth: Int, val pvUci: List<String>)

@Serializable
data class EvalRecord(
    val index: Int,
    val fen: String,
    val depth: Int,
    val requestedDepth: Int,
    val nodes: Long,
    val timeMs: Long,
    val lines: List<LineDto>,
) {
    fun toEval(): PositionEval = PositionEval(
        fen = fen,
        lines = lines.map { EngineLineInput(it.multiPv, it.scoreCp, it.mateIn, it.depth, it.pvUci) },
        depth = depth,
        requestedDepth = requestedDepth,
    )
}

@Serializable
data class GameRecord(val gameId: String, val tier: String, val engine: String, val evals: List<EvalRecord>)

/** The parts of one `info` line the selection needs. */
data class InfoLine(
    val multiPv: Int,
    val depth: Int,
    val scoreCp: Int?,
    val mateIn: Int?,
    val bound: Boolean,
    val nodes: Long?,
    val timeMs: Long?,
    val pv: List<String>,
)

class BenchEngine(binary: Path, threads: Int = 4, hashMb: Int = 96, private val multiPv: Int = 3) : AutoCloseable {
    private val process = ProcessBuilder(binary.toString()).redirectErrorStream(true).start()
    private val writer = BufferedWriter(OutputStreamWriter(process.outputStream, Charsets.US_ASCII))
    private val output = LinkedBlockingQueue<String>()
    var name = "unknown"
        private set

    init {
        Thread({
            BufferedReader(InputStreamReader(process.inputStream, Charsets.US_ASCII)).use { r ->
                while (true) output.put(r.readLine() ?: break)
            }
            output.put(EOF)
        }, "bench-stockfish").apply { isDaemon = true; start() }
        send("uci")
        readUntil { if (it.startsWith("id name ")) name = it.removePrefix("id name "); it == "uciok" }
        send("setoption name Threads value $threads")
        send("setoption name Hash value $hashMb")
        send("setoption name MultiPV value $multiPv")
        newGame()
    }

    fun newGame() {
        send("ucinewgame")
        send("isready")
        readUntil { it == "readyok" }
    }

    /** One position, exactly as the app searches it; terminal positions are never searched. */
    fun evaluate(index: Int, fen: String, tier: BenchTier): EvalRecord {
        val position = Position.fromFen(fen)
        if (position.legalMoves().isEmpty()) {
            val line = if (position.isInCheck()) LineDto(1, null, 0, tier.depth, emptyList())
            else LineDto(1, 0, null, tier.depth, emptyList())
            return EvalRecord(index, fen, tier.depth, tier.depth, 0, 0, listOf(line))
        }
        send("position fen $fen")
        send("go depth ${tier.depth} nodes ${tier.nodes} movetime ${tier.movetimeMs}")
        val infos = ArrayList<InfoLine>()
        var maxNodes = 0L
        var maxTime = 0L
        val started = System.nanoTime()
        while (true) {
            val line = output.poll(tier.movetimeMs + 120_000, TimeUnit.MILLISECONDS) ?: error("no bestmove for $fen")
            if (line === EOF) error("stockfish died on $fen")
            if (line.startsWith("bestmove")) {
                val best = line.split(" ")[1]
                val limitHit = maxNodes >= tier.nodes || maxTime >= tier.movetimeMs
                val (chosen, depth) = select(infos, limitHit)
                val lines = if (chosen.isEmpty()) listOf(LineDto(1, null, null, 0, listOf(best)))
                else chosen.map { LineDto(it.multiPv, it.scoreCp, it.mateIn, it.depth, it.pv) }
                val wall = (System.nanoTime() - started) / 1_000_000
                return EvalRecord(index, fen, depth, tier.depth, maxNodes, wall, lines)
            }
            parseInfo(line)?.let { info ->
                infos += info
                info.nodes?.let { if (it > maxNodes) maxNodes = it }
                info.timeMs?.let { if (it > maxTime) maxTime = it }
            }
        }
    }

    override fun close() {
        runCatching { send("quit"); writer.close() }
        if (!process.waitFor(5, TimeUnit.SECONDS)) process.destroyForcibly()
    }

    private fun send(cmd: String) {
        writer.write(cmd); writer.newLine(); writer.flush()
    }

    private fun readUntil(pred: (String) -> Boolean) {
        while (true) {
            val line = output.poll(30, TimeUnit.SECONDS) ?: error("stockfish did not answer")
            if (line === EOF) error("stockfish died")
            if (pred(line)) return
        }
    }

    companion object {
        private val EOF = String(charArrayOf('\u0000'))

        /** `ConsistentLines.select` (engine/AnalysisModels.kt, spec §8.2), restated for the host. */
        fun select(infos: List<InfoLine>, limitHit: Boolean): Pair<List<InfoLine>, Int> {
            if (infos.isEmpty()) return emptyList<InfoLine>() to 0
            val batches = ArrayList<MutableList<InfoLine>>()
            for (line in infos) {
                val current = batches.lastOrNull()
                if (current == null || line.multiPv == 1 || line.multiPv <= current.last().multiPv) batches += mutableListOf(line)
                else current += line
            }
            val expected = batches.maxOf { it.size }
            fun usable(b: List<InfoLine>): Boolean {
                if (b.size != expected) return false
                val d = b[0].depth
                return b.withIndex().all { (i, l) -> l.multiPv == i + 1 && l.depth == d && !l.bound }
            }
            val candidates = if (limitHit && batches.size > 1) batches.dropLast(1) else batches
            val best = candidates.withIndex().filter { usable(it.value) }
                .maxWithOrNull(compareBy<IndexedValue<MutableList<InfoLine>>> { it.value[0].depth }.thenBy { it.index })?.value
            if (best != null) return best.toList() to best[0].depth
            val latest = LinkedHashMap<Int, InfoLine>()
            for (l in infos) if (!l.bound || l.multiPv !in latest) latest[l.multiPv] = l
            val lines = latest.values.sortedBy { it.multiPv }
            return lines to lines.minOf { it.depth }
        }

        fun parseInfo(line: String): InfoLine? {
            val t = line.trim().split(Regex("\\s+"))
            if (t.isEmpty() || t[0] != "info") return null
            var multiPv = 1
            var depth: Int? = null
            var cp: Int? = null
            var mate: Int? = null
            var bound = false
            var nodes: Long? = null
            var time: Long? = null
            var pv: List<String> = emptyList()
            var i = 1
            while (i < t.size) {
                when (t[i]) {
                    "depth" -> { depth = t.getOrNull(i + 1)?.toIntOrNull(); i += 2 }
                    "multipv" -> { multiPv = t.getOrNull(i + 1)?.toIntOrNull() ?: 1; i += 2 }
                    "nodes" -> { nodes = t.getOrNull(i + 1)?.toLongOrNull(); i += 2 }
                    "time" -> { time = t.getOrNull(i + 1)?.toLongOrNull(); i += 2 }
                    "score" -> {
                        when (t.getOrNull(i + 1)) {
                            "cp" -> { cp = t.getOrNull(i + 2)?.toIntOrNull(); i += 3 }
                            "mate" -> { mate = t.getOrNull(i + 2)?.toIntOrNull(); i += 3 }
                            else -> i += 2
                        }
                        if (t.getOrNull(i) == "lowerbound" || t.getOrNull(i) == "upperbound") { bound = true; i += 1 }
                    }
                    "pv" -> { pv = t.subList(i + 1, t.size); i = t.size }
                    else -> i += 1
                }
            }
            val d = depth ?: return null
            if (cp == null && mate == null && pv.isEmpty()) return null
            return InfoLine(multiPv, d, cp, mate, bound, nodes, time, pv)
        }
    }
}

/**
 * A [BenchEngine] that survives the engine process dying under it (the shared host had another job that killed
 * every Stockfish process by name): the position is searched again on a fresh engine after `ucinewgame`. The hash
 * is then cold for that one search, which can change its node count a little; the search itself is the same.
 */
class ResilientEngine(private val binary: Path) : AutoCloseable {
    private var engine = BenchEngine(binary)
    val name: String get() = engine.name
    var restarts = 0
        private set

    fun newGame() = retry { engine.newGame() }

    fun evaluate(index: Int, fen: String, tier: BenchTier): EvalRecord = retry { engine.evaluate(index, fen, tier) }

    private fun <T> retry(block: () -> T): T {
        var attempt = 0
        while (true) {
            try {
                return block()
            } catch (e: Exception) {
                if (++attempt > 5) throw e
                System.err.println("engine failed (${e.message}); restarting, attempt $attempt")
                runCatching { engine.close() }
                Thread.sleep(2_000)
                engine = BenchEngine(binary)
                restarts++
            }
        }
    }

    override fun close() = engine.close()
}
