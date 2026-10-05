package net.palaya.chessanalyzer.desktop.analysis

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import net.palaya.chessanalyzer.core.analysis.EngineLineInput
import net.palaya.chessanalyzer.core.analysis.GameReport
import net.palaya.chessanalyzer.core.analysis.PositionEval
import net.palaya.chessanalyzer.core.chess.Color
import net.palaya.chessanalyzer.core.pgn.PgnGame
import net.palaya.chessanalyzer.desktop.engine.EngineAnalyzer
import net.palaya.chessanalyzer.desktop.engine.EngineSettings
import net.palaya.chessanalyzer.desktop.engine.PlyTiming
import net.palaya.chessanalyzer.desktop.engine.UciClient
import net.palaya.chessanalyzer.desktop.work.Stage
import net.palaya.chessanalyzer.desktop.work.WorkDir
import net.palaya.chessanalyzer.desktop.work.WorkJson
import net.palaya.chessanalyzer.desktop.work.decideStage
import net.palaya.chessanalyzer.desktop.work.sha256Hex
import java.io.BufferedWriter
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

// --- analysis.json schema ("palaya.analysis/1", design §3.1) --------------------------------

@Serializable
data class AnalysisFile(
    val schema: String = SCHEMA,
    val gameId: String,
    val pgnSha256: String,
    /** 1-based index of the game within the source PGN. */
    val gameIndex: Int,
    val tags: Map<String, String>,
    val startFen: String?,
    val engine: EngineInfoDto,
    /** The ANALYZE input fingerprint these evals were computed under; resume requires a match. */
    val fingerprint: String,
    val evals: List<EvalEntry>,
    val checkpoint: Checkpoint,
) {
    companion object {
        const val SCHEMA = "palaya.analysis/1"
    }
}

/** One `PositionEval`, 1:1 (`Contract.kt:37-44`); scores side-to-move relative, as UCI reports them. */
@Serializable
data class EvalEntry(
    val ply: Int,
    val fen: String,
    val depth: Int,
    /**
     * True when the search stopped below the requested depth, i.e. the movetime cap ended it. A
     * capped eval is shallower than the rest of the game and can flip a classification (P0:
     * 15...Nxd7 read BEST at depth 13-15 and GOOD at depth 18), so it is never silent.
     */
    val capped: Boolean = false,
    val lines: List<LineEntry>,
) {
    fun toPositionEval() = PositionEval(fen, lines.map { it.toInput() }, depth)

    companion object {
        /** Terminal positions carry the requested depth, so they can never read as capped. */
        fun of(ply: Int, e: PositionEval, requestedDepth: Int) =
            EvalEntry(ply, e.fen, e.depth, isCapped(e, requestedDepth), e.lines.map(LineEntry::of))

        fun isCapped(e: PositionEval, requestedDepth: Int): Boolean = e.depth < requestedDepth
    }
}

/** One `EngineLineInput`, 1:1 (`Contract.kt:26-34`). */
@Serializable
data class LineEntry(val multiPv: Int, val scoreCp: Int?, val mateIn: Int?, val depth: Int, val pvUci: List<String>) {
    fun toInput() = EngineLineInput(multiPv, scoreCp, mateIn, depth, pvUci)

    companion object {
        fun of(l: EngineLineInput) = LineEntry(l.multiPv, l.scoreCp, l.mateIn, l.depth, l.pvUci)
    }
}

/** @param completedPlies how many positions (evals) are stored; [complete] once all `moves + 1` are. */
@Serializable
data class Checkpoint(val completedPlies: Int, val complete: Boolean)

// --- metrics.json "analyze" section -----------------------------------------------------------

@Serializable
data class AnalyzeMetrics(
    val finishedAt: String,
    val engine: String,
    val threads: Int,
    val hashMb: Int,
    val depth: Int,
    val multiPv: Int,
    val movetimeCapMs: Long?,
    val positions: Int,
    val searched: Int,
    val terminalShortCircuits: Int,
    val resumed: Int,
    /** Searched positions whose reached depth was below the requested one (movetime cap hit). */
    val cappedByMovetime: Int,
    val engineSeconds: Double,
    val meanSecondsPerPly: Double,
    val medianSecondsPerPly: Double,
    val maxSecondsPerPly: Double,
    val meanNps: Long?,
    /** Index-aligned with evals (0 = start position); null for a resumed position. */
    val perPositionSeconds: List<Double?>,
    val perPositionDepth: List<Int>,
    val stageSeconds: Map<String, Double>,
)

/** What the ANALYZE stage hands the next stage (and `--dry-run`). */
class AnalysisOutcome(
    val workDir: WorkDir,
    val game: PgnGame,
    val evals: List<PositionEval>,
    val report: GameReport,
    val reportDto: ReportDto,
    val ranEngine: Boolean,
    val decisionReason: String,
    /** Wall-clock seconds per sub-step of this run, in order. */
    val stageSeconds: Map<String, Double>,
    /** Per-position seconds (null = resumed or unknown), from this run or the cached metrics. */
    val perPositionSeconds: List<Double?>,
)

/**
 * PGN → evals → `analysis.json` (+ `report.json`) — stage ANALYZE (docs/PC_PRODUCER_DESIGN.md §3, §4).
 */
class AnalysisStage(
    private val stockfish: Path,
    private val settings: EngineSettings,
    private val log: (String) -> Unit = ::println,
) {

    fun run(
        workDir: WorkDir,
        game: PgnGame,
        gameIndex: Int,
        pgnText: String,
        userName: String?,
        thresholdCp: Int,
        force: Boolean = false,
        from: Stage? = null,
    ): AnalysisOutcome {
        val steps = LinkedHashMap<String, Double>()
        fun <T> timed(name: String, block: () -> T): T {
            val t0 = System.nanoTime()
            return block().also { steps[name] = (System.nanoTime() - t0) / 1e9 }
        }
        workDir.ensureExists()
        workDir.writeAtomic(workDir.gamePgn, pgnText)

        val binarySha = timed("binarySha256") { WorkDir.sha256OfFile(stockfish) }
        val fingerprint = fingerprint(game, settings, binarySha)
        val fens = EngineAnalyzer.plyFens(game)
        val existing = readAnalysis(workDir)
        val decision = decideStage(
            Stage.ANALYZE,
            outputExists = existing?.checkpoint?.complete == true && existing.evals.size == fens.size,
            recorded = workDir.stageRecord(Stage.ANALYZE),
            currentFingerprint = fingerprint,
            force = force,
            from = from,
        )

        val evals: List<PositionEval>
        val perPosition: List<Double?>
        if (!decision.run) {
            log("[analyze] ${decision.reason}; ${fens.size} positions from ${workDir.analysisJson}")
            evals = existing!!.evals.map { it.toPositionEval() }
            perPosition = cachedPerPositionSeconds(workDir, fens.size)
        } else {
            log("[analyze] running: ${decision.reason}")
            // Resume only a partial run computed under the very same fingerprint, and never on
            // --force/--from: those ask for a recompute.
            val resumable = if (!force && from == null && existing != null && existing.fingerprint == fingerprint) {
                existing.evals.map { it.toPositionEval() }
            } else emptyList()
            val result = runEngine(workDir, game, gameIndex, pgnText, fens, resumable, fingerprint, binarySha, steps)
            evals = result.first
            perPosition = result.second
            workDir.recordStage(Stage.ANALYZE, fingerprint, mapOf("stockfish" to engineName, "stockfishSha256" to binarySha))
        }

        val userColor: Color? = ReportFactory.detectUserColor(game, userName)
        val book = timed("openingBook") { ReportFactory.openingBook() }
        val report = timed("gameAnalyzer") { ReportFactory.build(game, evals, userColor, book) }
        val engineInfo = existing?.engine?.takeIf { !decision.run } ?: engineInfo(binarySha)
        val dto = ReportFactory.toDto(workDir.gameId, game, evals, report, engineInfo, thresholdCp)
        timed("writeReport") { workDir.writeAtomic(workDir.reportJson, WorkJson.encodeToString(dto)) }

        reportCapped(evals)

        if (decision.run) {
            val m = WorkJson.decodeFromJsonElement(AnalyzeMetrics.serializer(), workDir.readMetrics()["analyze"]!!)
            workDir.writeMetricsSection("analyze", WorkJson.encodeToJsonElement(AnalyzeMetrics.serializer(), m.copy(stageSeconds = steps.mapValues { round3(it.value) })))
        }
        return AnalysisOutcome(workDir, game, evals, report, dto, decision.run, decision.reason, steps, perPosition)
    }

    /**
     * The end-of-stage movetime-cap summary. Printed for cached analyses too: a capped eval stays
     * capped until the settings change and the stage re-runs.
     */
    private fun reportCapped(evals: List<PositionEval>) {
        val capped = evals.withIndex().filter { (_, e) -> EvalEntry.isCapped(e, settings.depth) }
        if (capped.isEmpty()) {
            log("[analyze] 0 of ${evals.size} positions hit the movetime cap (all reached depth ${settings.depth})")
            return
        }
        log("[analyze] WARNING: ${capped.size} of ${evals.size} positions hit the ${settings.movetimeCapMs} ms movetime cap " +
            "before depth ${settings.depth}: " + capped.joinToString(", ") { (i, e) -> "pos $i d${e.depth}" })
        log("[analyze] WARNING: moves judged from those positions may be misclassified; raise --movetime-cap or free the CPU")
    }

    private var engineName = "unknown"

    private fun engineInfo(binarySha: String) = EngineInfoDto(
        name = engineName,
        binarySha256 = binarySha,
        threads = settings.threads,
        hashMb = settings.hashMb,
        multiPv = settings.multiPv,
        depth = settings.depth,
        movetimeCapMs = settings.movetimeCapMs,
    )

    private fun runEngine(
        workDir: WorkDir,
        game: PgnGame,
        gameIndex: Int,
        pgnText: String,
        fens: List<String>,
        resumable: List<PositionEval>,
        fingerprint: String,
        binarySha: String,
        steps: MutableMap<String, Double>,
    ): Pair<List<PositionEval>, List<Double?>> {
        val logDir = workDir.dir.resolve("logs")
        Files.createDirectories(logDir)
        val uciLog: BufferedWriter = Files.newBufferedWriter(logDir.resolve("analyze.uci.log"))
        val timings = arrayOfNulls<PlyTiming>(fens.size)
        val pgnSha = sha256Hex(pgnText)

        fun snapshot(evals: List<PositionEval>, complete: Boolean) = AnalysisFile(
            gameId = workDir.gameId,
            pgnSha256 = pgnSha,
            gameIndex = gameIndex,
            tags = game.tags,
            startFen = game.startFen,
            engine = engineInfo(binarySha),
            fingerprint = fingerprint,
            evals = evals.mapIndexed { i, e -> EvalEntry.of(i, e, settings.depth) },
            checkpoint = Checkpoint(evals.size, complete),
        )

        val t0 = System.nanoTime()
        val evals = try {
            UciClient(stockfish, transcript = { line -> uciLog.write(line); uciLog.newLine() }).use { client ->
                val tStart = System.nanoTime()
                client.start(settings.threads, settings.hashMb, settings.multiPv)
                engineName = client.engineName
                steps["engineStart"] = (System.nanoTime() - tStart) / 1e9
                log("[analyze] $engineName, threads ${settings.threads}, hash ${settings.hashMb} MB, MultiPV ${settings.multiPv}, " +
                    "depth ${settings.depth}, movetime cap ${settings.movetimeCapMs ?: "none"} ms, ${fens.size} positions" +
                    (if (resumable.isNotEmpty()) ", resuming from a checkpoint" else ""))
                EngineAnalyzer(client, settings).analyze(
                    fens = fens,
                    resumable = resumable,
                    onPosition = { eval, t ->
                        timings[t.index] = t
                        if (t.source != PlyTiming.Source.RESUMED) {
                            val best = eval.best
                            val score = when {
                                best?.mateIn != null -> "mate ${best.mateIn}"
                                best?.scoreCp != null -> "cp ${best.scoreCp}"
                                else -> "-"
                            }
                            log(String.format(java.util.Locale.ROOT, "[analyze] %3d/%d  %-8s d%-2d  %6.2fs  %s",
                                t.index, fens.size - 1, t.source.name.lowercase(), t.depthReached, t.seconds, score))
                        }
                    },
                    checkpoint = { partial ->
                        workDir.writeAtomic(workDir.analysisJson, WorkJson.encodeToString(snapshot(partial, complete = false)))
                    },
                )
            }
        } finally {
            uciLog.close()
        }
        steps["engine"] = (System.nanoTime() - t0) / 1e9
        val tw = System.nanoTime()
        workDir.writeAtomic(workDir.analysisJson, WorkJson.encodeToString(snapshot(evals, complete = true)))
        steps["writeAnalysis"] = (System.nanoTime() - tw) / 1e9

        val searched = timings.filterNotNull().filter { it.source == PlyTiming.Source.SEARCHED }
        val secs = searched.map { it.seconds }.sorted()
        val metrics = AnalyzeMetrics(
            finishedAt = Instant.now().toString(),
            engine = engineName,
            threads = settings.threads,
            hashMb = settings.hashMb,
            depth = settings.depth,
            multiPv = settings.multiPv,
            movetimeCapMs = settings.movetimeCapMs,
            positions = fens.size,
            searched = searched.size,
            terminalShortCircuits = timings.count { it?.source == PlyTiming.Source.TERMINAL },
            resumed = timings.count { it?.source == PlyTiming.Source.RESUMED },
            cappedByMovetime = searched.count { it.depthReached < settings.depth },
            engineSeconds = round3(secs.sum()),
            meanSecondsPerPly = round3(if (secs.isEmpty()) 0.0 else secs.average()),
            medianSecondsPerPly = round3(if (secs.isEmpty()) 0.0 else secs[secs.size / 2]),
            maxSecondsPerPly = round3(secs.maxOrNull() ?: 0.0),
            meanNps = searched.mapNotNull { it.nps }.takeIf { it.isNotEmpty() }?.average()?.toLong(),
            perPositionSeconds = timings.map { t -> t?.takeIf { it.source != PlyTiming.Source.RESUMED }?.let { round3(it.seconds) } },
            perPositionDepth = evals.map { it.depth },
            stageSeconds = emptyMap(),
        )
        workDir.writeMetricsSection("analyze", WorkJson.encodeToJsonElement(AnalyzeMetrics.serializer(), metrics))
        return evals to metrics.perPositionSeconds
    }

    private fun cachedPerPositionSeconds(workDir: WorkDir, n: Int): List<Double?> = try {
        workDir.readMetrics()["analyze"]
            ?.let { WorkJson.decodeFromJsonElement(AnalyzeMetrics.serializer(), it).perPositionSeconds }
            ?.takeIf { it.size == n }
            ?: List(n) { null }
    } catch (_: Exception) {
        List(n) { null }
    }

    companion object {
        /**
         * Every analysis setting that can change an eval is in the fingerprint, so changing any
         * of them invalidates a cached `analysis.json` (and a partial one cannot be resumed under
         * different settings). Threads is included because multi-threaded search is not
         * reproducible (P0 parity finding); Hash because it changes what the search reaches.
         */
        fun fingerprint(game: PgnGame, settings: EngineSettings, binarySha: String): String = Stage.fingerprint(
            Stage.ANALYZE,
            WorkDir.normalizedPgn(game),
            "depth=${settings.depth}",
            "multipv=${settings.multiPv}",
            "movetimeCap=${settings.movetimeCapMs}",
            "threads=${settings.threads}",
            "hash=${settings.hashMb}",
            "stockfish=$binarySha",
        )

        fun readAnalysis(workDir: WorkDir): AnalysisFile? {
            if (!Files.isRegularFile(workDir.analysisJson)) return null
            return try {
                WorkJson.decodeFromString(AnalysisFile.serializer(), Files.readString(workDir.analysisJson))
                    .takeIf { it.schema == AnalysisFile.SCHEMA }
            } catch (_: Exception) {
                null // Unreadable → recompute rather than trust it.
            }
        }

        private fun round3(x: Double) = Math.round(x * 1000.0) / 1000.0
    }
}
