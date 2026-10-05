package net.palaya.chessanalyzer.desktop.analysis

import kotlinx.serialization.Serializable
import net.palaya.chessanalyzer.core.analysis.EvalFormat
import net.palaya.chessanalyzer.core.analysis.GameAnalyzer
import net.palaya.chessanalyzer.core.analysis.GameReport
import net.palaya.chessanalyzer.core.analysis.MoveAnnotation
import net.palaya.chessanalyzer.core.analysis.MoveClassifier
import net.palaya.chessanalyzer.core.analysis.MoveSequenceDetector
import net.palaya.chessanalyzer.core.analysis.OpeningBook
import net.palaya.chessanalyzer.core.analysis.PlayerReport
import net.palaya.chessanalyzer.core.analysis.PositionEval
import net.palaya.chessanalyzer.core.analysis.TacticInstance
import net.palaya.chessanalyzer.core.analysis.TacticSignificance
import net.palaya.chessanalyzer.core.analysis.TacticSimulation
import net.palaya.chessanalyzer.core.analysis.WinProbability
import net.palaya.chessanalyzer.core.chess.Color
import net.palaya.chessanalyzer.core.chess.Position
import net.palaya.chessanalyzer.core.chess.moveToSan
import net.palaya.chessanalyzer.core.chess.parseUci
import net.palaya.chessanalyzer.core.pgn.PgnGame
import net.palaya.chessanalyzer.core.tactics.MotifDetector
import net.palaya.chessanalyzer.core.tactics.StaticExchangeEvaluator
import java.io.InputStreamReader

/**
 * evals + game → [GameReport], exactly as the app builds it (`AnalysisService.kt:194-199`), plus
 * the human-readable `report.json` dump (docs/PC_PRODUCER_DESIGN.md §3, §4).
 *
 * The report is never cached: [GameAnalyzer.analyze] is pure and takes milliseconds, so it is
 * recomputed from `analysis.json` on every load, and a `:core` classification fix retroactively
 * improves cached games.
 */
object ReportFactory {

    private const val BOOK_RESOURCE = "/openings.tsv"

    /** How many PV plies of each engine line `report.json` spells out in SAN. */
    private const val PV_SAN_PLIES = 8

    @Volatile private var cachedBook: OpeningBook? = null

    /** The app's `openings.tsv`, copied into this module's resources by `processResources`. */
    fun openingBook(): OpeningBook = cachedBook ?: synchronized(this) {
        cachedBook ?: run {
            val stream = ReportFactory::class.java.getResourceAsStream(BOOK_RESOURCE)
                ?: error("openings.tsv missing from the classpath (desktop processResources copies it from app/src/main/assets)")
            InputStreamReader(stream, Charsets.UTF_8).use { OpeningBook.load(it) }.also { cachedBook = it }
        }
    }

    /** `AnalysisService.kt:196-198`. */
    fun build(game: PgnGame, evals: List<PositionEval>, userColor: Color?, book: OpeningBook? = openingBook()): GameReport {
        val see = StaticExchangeEvaluator()
        val analyzer = GameAnalyzer(MoveClassifier(see), MotifDetector(see))
        return analyzer.analyze(game, evals, userColor, book)
    }

    /** `AnalysisService.detectUserColor`. */
    fun detectUserColor(game: PgnGame, username: String?): Color? {
        if (username.isNullOrBlank()) return null
        val white = game.tags["White"].orEmpty()
        val black = game.tags["Black"].orEmpty()
        return when {
            white.equals(username, ignoreCase = true) -> Color.WHITE
            black.equals(username, ignoreCase = true) -> Color.BLACK
            else -> null
        }
    }

    /**
     * The `report.json` view: the whole [GameReport] with every eval White-relative and every move
     * also in SAN, plus the engine's top lines per position, so a reader (or the task-55 LLM
     * spike) can check a commentary claim against it without re-running anything.
     *
     * Tactics are listed unpruned; each carries `survivesPrune`, whether it passes
     * [TacticSignificance.prune] at [thresholdCp] — the view the video narration uses.
     */
    fun toDto(
        gameId: String,
        game: PgnGame,
        evals: List<PositionEval>,
        report: GameReport,
        engine: EngineInfoDto,
        thresholdCp: Int,
    ): ReportDto {
        val pruned = TacticSignificance.prune(report.annotations, thresholdCp).associateBy { it.ply }
        val sequences = MoveSequenceDetector.detect(report.annotations)
        val finalFen = evals.last().fen
        val finalPos = Position.fromFen(finalFen)
        val plies = report.annotations.map { a ->
            val kept = pruned[a.ply]
            val before = Position.fromFen(a.fenBefore)
            val after = Position.fromFen(a.fenAfter)
            PlyDto(
                ply = a.ply,
                moveNumber = a.moveNumber,
                color = a.color.name,
                label = moveLabel(a),
                san = a.san,
                uci = a.uci,
                fenBefore = a.fenBefore,
                fenAfter = a.fenAfter,
                classification = a.classification.name,
                evalBefore = EvalDto(a.evalBeforeCp, a.mateInBefore, EvalFormat.score(a.evalBeforeCp, a.mateInBefore)),
                evalAfter = EvalDto(a.evalAfterCp, a.mateInAfter, EvalFormat.score(a.evalAfterCp, a.mateInAfter)),
                winPercentWhiteBefore = round2(report.evalGraph[a.ply - 1]),
                winPercentWhiteAfter = round2(report.evalGraph[a.ply]),
                loss = round2(a.loss),
                moveAccuracy = round2(a.moveAccuracy),
                secondBestCp = a.evalSecondBestCp,
                bestMoveSan = a.bestMoveSan,
                bestMoveUci = a.bestMoveUci,
                playedBest = a.bestMoveUci == a.uci,
                bestLineSan = a.bestLineSan,
                engineLinesBefore = engineLines(evals[a.ply - 1], before),
                depthBefore = evals[a.ply - 1].depth,
                depthAfter = evals[a.ply].depth,
                capped = EvalEntry.isCapped(evals[a.ply - 1], engine.depth) || EvalEntry.isCapped(evals[a.ply], engine.depth),
                givesCheck = after.isInCheck(),
                checkmate = after.isCheckmate(),
                stalemate = after.isStalemate(),
                openingName = a.openingName,
                tacticsFound = a.tacticsFound.map { tacticDto(it, before, kept?.tacticsFound) },
                tacticsMissed = a.tacticsMissed.map { tacticDto(it, before, kept?.tacticsMissed) },
                threatsAllowed = a.threatsAllowed.map { tacticDto(it, after, null) },
                simulation = a.simulation?.let { simulationDto(it, before) },
                commentary = a.text,
            )
        }
        return ReportDto(
            gameId = gameId,
            tags = game.tags,
            result = report.result,
            termination = when {
                finalPos.isCheckmate() -> "checkmate"
                finalPos.isStalemate() -> "stalemate"
                else -> null
            },
            finalFen = finalFen,
            opening = OpeningDto(report.openingName, report.openingEco, game.tags["ECO"]),
            engine = engine,
            analysisDepth = report.analysisDepth,
            significanceThresholdCp = thresholdCp,
            cappedPositions = evals.indices.filter { EvalEntry.isCapped(evals[it], engine.depth) },
            white = playerDto(report.white),
            black = playerDto(report.black),
            keyMoments = report.keyMoments.map {
                KeyMomentDto(it.ply, moveLabel(report.annotations[it.ply - 1]), it.san, it.classification.name, round2(it.swing), it.summary)
            },
            sequences = sequences.map {
                SequenceDto(it.startPly, it.endPly, it.byColor.name, it.kind.name, it.classification.name, it.totalSwingCp, it.label, it.tacticType?.name)
            },
            evalGraphWhite = report.evalGraph.map(::round2),
            plies = plies,
        )
    }

    /** "16. Qb8+" / "16... Nxb8". */
    fun moveLabel(a: MoveAnnotation): String =
        if (a.color == Color.WHITE) "${a.moveNumber}. ${a.san}" else "${a.moveNumber}... ${a.san}"

    private fun engineLines(eval: PositionEval, pos: Position): List<EngineLineDto> {
        val whiteToMove = WinProbability.sideToMoveOf(eval.fen) == Color.WHITE
        return eval.lines.sortedBy { it.multiPv }.map { line ->
            val cpWhite = line.scoreCp?.let { if (whiteToMove) it else -it }
            val mateWhite = line.mateIn?.let { if (whiteToMove) it else -it }
            EngineLineDto(
                multiPv = line.multiPv,
                cpWhite = cpWhite,
                mateWhite = mateWhite,
                text = EvalFormat.score(cpWhite, mateWhite),
                depth = line.depth,
                pvSan = sanLine(pos, line.pvUci.take(PV_SAN_PLIES)),
            )
        }
    }

    private fun tacticDto(t: TacticInstance, pos: Position, survivors: List<TacticInstance>?): TacticDto = TacticDto(
        type = t.type.name,
        name = t.type.displayName,
        byColor = t.byColor.name,
        moveUci = t.moveUci,
        moveSan = sanLine(pos, listOf(t.moveUci)).firstOrNull(),
        targetSquares = t.targetSquares.map { it.toString() },
        involvedSquares = t.involvedSquares.map { it.toString() },
        materialSwing = t.materialSwing,
        confidence = t.confidence,
        description = t.description,
        survivesPrune = survivors?.contains(t),
    )

    private fun simulationDto(s: TacticSimulation, pos: Position) = SimulationDto(
        startFen = s.startFen,
        pvSan = s.pvSan,
        pvUci = s.pvUci,
        perPlyExplanation = s.perPlyExplanation,
        tactic = tacticDto(s.tactic, pos, null),
        payoff = s.payoffDescription,
    )

    private fun playerDto(p: PlayerReport) = PlayerDto(
        color = p.color.name,
        name = p.name,
        accuracy = p.accuracy,
        estimatedRating = p.estimatedRating,
        lowConfidence = p.lowConfidence,
        classificationCounts = p.classificationCounts.entries.sortedBy { it.key.ordinal }.associate { it.key.name to it.value },
        tacticsFound = p.tacticsFound.size,
        tacticsMissed = p.tacticsMissed.size,
    )

    /** SAN for a UCI line from [start]; stops at the first move that does not parse. */
    fun sanLine(start: Position, uci: List<String>): List<String> {
        val out = ArrayList<String>(uci.size)
        var pos = start
        for (u in uci) {
            val move = try { pos.parseUci(u) } catch (_: Exception) { break }
            out.add(pos.moveToSan(move))
            pos = pos.makeMove(move)
        }
        return out
    }

    private fun round2(x: Double) = Math.round(x * 100.0) / 100.0
}

// --- report.json schema ("palaya.report/1") -------------------------------------------------

@Serializable
data class EngineInfoDto(
    val name: String,
    val binarySha256: String,
    val threads: Int,
    val hashMb: Int,
    val multiPv: Int,
    val depth: Int,
    val movetimeCapMs: Long?,
)

@Serializable
data class ReportDto(
    val schema: String = "palaya.report/1",
    val gameId: String,
    val tags: Map<String, String>,
    val result: String,
    /** "checkmate" / "stalemate" when the final position is one, else absent. */
    val termination: String?,
    val finalFen: String,
    val opening: OpeningDto,
    val engine: EngineInfoDto,
    val analysisDepth: Int,
    val significanceThresholdCp: Int,
    /** Eval indices (0 = start position) whose search the movetime cap stopped below [EngineInfoDto.depth]. */
    val cappedPositions: List<Int> = emptyList(),
    val white: PlayerDto,
    val black: PlayerDto,
    val keyMoments: List<KeyMomentDto>,
    val sequences: List<SequenceDto>,
    /** White's win percent at each position, index 0 = start. */
    val evalGraphWhite: List<Double>,
    val plies: List<PlyDto>,
)

@Serializable
data class OpeningDto(val name: String?, val eco: String?, val pgnEcoTag: String?)

@Serializable
data class PlayerDto(
    val color: String,
    val name: String?,
    val accuracy: Double,
    val estimatedRating: Int,
    val lowConfidence: Boolean,
    val classificationCounts: Map<String, Int>,
    val tacticsFound: Int,
    val tacticsMissed: Int,
)

@Serializable
data class KeyMomentDto(val ply: Int, val label: String, val san: String, val classification: String, val swing: Double, val summary: String)

@Serializable
data class SequenceDto(
    val startPly: Int,
    val endPly: Int,
    val byColor: String,
    val kind: String,
    val classification: String,
    val totalSwingCp: Int,
    val label: String,
    val tacticType: String?,
)

/** An evaluation, White-relative: `cp` in centipawns (mates saturated), `mate` in moves, `text` as the UI shows it. */
@Serializable
data class EvalDto(val cp: Int, val mate: Int?, val text: String)

@Serializable
data class EngineLineDto(val multiPv: Int, val cpWhite: Int?, val mateWhite: Int?, val text: String, val depth: Int, val pvSan: List<String>)

@Serializable
data class TacticDto(
    val type: String,
    val name: String,
    val byColor: String,
    val moveUci: String,
    val moveSan: String?,
    val targetSquares: List<String>,
    val involvedSquares: List<String>,
    val materialSwing: Int,
    val confidence: Double,
    val description: String,
    /** Whether the motif passes TacticSignificance.prune at the report's threshold; absent where not applicable. */
    val survivesPrune: Boolean?,
)

@Serializable
data class SimulationDto(
    val startFen: String,
    val pvSan: List<String>,
    val pvUci: List<String>,
    val perPlyExplanation: List<String>,
    val tactic: TacticDto,
    val payoff: String,
)

@Serializable
data class PlyDto(
    val ply: Int,
    val moveNumber: Int,
    val color: String,
    val label: String,
    val san: String,
    val uci: String,
    val fenBefore: String,
    val fenAfter: String,
    val classification: String,
    val evalBefore: EvalDto,
    val evalAfter: EvalDto,
    val winPercentWhiteBefore: Double,
    val winPercentWhiteAfter: Double,
    /** Win percent the mover lost, 0..100. */
    val loss: Double,
    val moveAccuracy: Double,
    /** White-relative cp of the engine's second line before the move. */
    val secondBestCp: Int?,
    val bestMoveSan: String?,
    val bestMoveUci: String?,
    val playedBest: Boolean,
    val bestLineSan: List<String>,
    val engineLinesBefore: List<EngineLineDto>,
    /** Depth reached on the position before / after the move. */
    val depthBefore: Int = 0,
    val depthAfter: Int = 0,
    /**
     * True when either eval this move's classification is computed from was cut short by the
     * movetime cap (depth below the requested one): treat the classification with suspicion.
     */
    val capped: Boolean = false,
    val givesCheck: Boolean,
    val checkmate: Boolean,
    val stalemate: Boolean,
    val openingName: String?,
    val tacticsFound: List<TacticDto>,
    val tacticsMissed: List<TacticDto>,
    val threatsAllowed: List<TacticDto>,
    val simulation: SimulationDto?,
    val commentary: String,
)
