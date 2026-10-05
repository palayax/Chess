package net.palaya.chessanalyzer.core.analysis

import net.palaya.chessanalyzer.core.chess.Color
import net.palaya.chessanalyzer.core.chess.Move
import net.palaya.chessanalyzer.core.chess.Position
import net.palaya.chessanalyzer.core.chess.Square

/**
 * SHARED CONTRACT — owned by the orchestrator, treated as read-only by implementing agents.
 *
 * This file exists so the tactics module and the analysis/classification module can be built
 * independently and still agree on the same vocabulary. Implementations live elsewhere:
 *  - `core.tactics`  implements [SeeEvaluator] and [TacticsDetector]
 *  - `core.analysis` implements the classifier, accuracy/rating maths and commentary
 *
 * The authoritative behavioural spec is docs/ANALYSIS_SPEC.md. Where this file and the spec
 * disagree, the spec wins and this file should be corrected.
 */

// ---------------------------------------------------------------------------
// Engine input (core is a pure-JVM module and must NOT depend on :engine, so the
// app adapts the engine's own result types into these before calling core.)
// ---------------------------------------------------------------------------

/** One engine PV line for a position, normalised for core's use. */
data class EngineLineInput(
    val multiPv: Int,
    /** Centipawns from the perspective of the side to move, null when [mateIn] is set. */
    val scoreCp: Int?,
    /** Mate distance in moves from the side to move's perspective (positive = we mate). */
    val mateIn: Int?,
    val depth: Int,
    val pvUci: List<String>
)

/** Everything the engine had to say about one position. */
data class PositionEval(
    val fen: String,
    val lines: List<EngineLineInput>,
    val depth: Int
) {
    val best: EngineLineInput? get() = lines.minByOrNull { it.multiPv }
    val secondBest: EngineLineInput? get() = lines.filter { it.multiPv == 2 }.minByOrNull { it.multiPv }
}

// ---------------------------------------------------------------------------
// Classification
// ---------------------------------------------------------------------------

/** chess.com-style move quality labels. Order is the display/severity order. */
enum class MoveClassification(val displayName: String, val glyph: String) {
    BRILLIANT("Brilliant", "!!"),
    GREAT("Great", "!"),
    BEST("Best", "★"),
    EXCELLENT("Excellent", "✓"),
    GOOD("Good", "✓"),
    BOOK("Book", "♞"),
    INACCURACY("Inaccuracy", "?!"),
    MISTAKE("Mistake", "?"),
    MISS("Miss", "×"),
    BLUNDER("Blunder", "??"),
    FORCED("Forced", "⇒");

    val isGood: Boolean get() = this in setOf(BRILLIANT, GREAT, BEST, EXCELLENT, GOOD, BOOK)
    val isMistake: Boolean get() = this in setOf(INACCURACY, MISTAKE, MISS, BLUNDER)
}

// ---------------------------------------------------------------------------
// Tactics
// ---------------------------------------------------------------------------

enum class TacticType(val displayName: String) {
    FORK("Fork"),
    PAWN_FORK("Pawn fork"),
    DOUBLE_ATTACK("Double attack"),
    PIN_ABSOLUTE("Absolute pin"),
    PIN_RELATIVE("Relative pin"),
    SKEWER("Skewer"),
    DISCOVERED_ATTACK("Discovered attack"),
    DISCOVERED_CHECK("Discovered check"),
    DOUBLE_CHECK("Double check"),
    HANGING_PIECE("Hanging piece"),
    TRAPPED_PIECE("Trapped piece"),
    DEFLECTION("Deflection"),
    DECOY("Decoy"),
    OVERLOADED_PIECE("Overloaded piece"),
    INTERFERENCE("Interference"),
    CLEARANCE("Clearance"),
    ZWISCHENZUG("In-between move"),
    BACK_RANK_MATE("Back-rank mate"),
    SMOTHERED_MATE("Smothered mate"),
    GREEK_GIFT("Greek gift sacrifice"),
    WINDMILL("Windmill"),
    X_RAY("X-ray"),
    PROMOTION_TACTIC("Promotion"),
    UNDERPROMOTION("Underpromotion"),
    PASSED_PAWN_BREAKTHROUGH("Passed pawn breakthrough"),
    REMOVING_THE_DEFENDER("Removing the defender"),
    MATE_NET("Mating net"),
    PERPETUAL_CHECK("Perpetual check"),
    STALEMATE_TRICK("Stalemate trick"),
    DESPERADO("Desperado"),
    BATTERY("Battery"),
    FORTRESS("Fortress")
}

/**
 * One detected tactical motif.
 *
 * @param materialSwing centipawns the motif wins (positive) for [byColor].
 * @param confidence 0.6 for a static pattern match, 0.95 when the engine PV confirms the
 *   follow-up within 4 plies. Anything below 0.6 must not be reported.
 */
data class TacticInstance(
    val type: TacticType,
    val byColor: Color,
    val moveUci: String,
    val targetSquares: List<Square> = emptyList(),
    val involvedSquares: List<Square> = emptyList(),
    val materialSwing: Int = 0,
    val description: String = "",
    val confidence: Double = 0.6
)

/** Static Exchange Evaluation. Piece values: P=100, N=320, B=330, R=500, Q=900, K=20000. */
interface SeeEvaluator {
    /** Centipawns won (positive) or lost (negative) by [move], from the mover's perspective. */
    fun see(position: Position, move: Move): Int

    /** True when the piece on [square] can be profitably captured by the side to move. */
    fun isHanging(position: Position, square: Square): Boolean
}

/**
 * Detects tactical motifs created by a candidate move.
 *
 * @param position the position BEFORE [move] is played.
 * @param pvUci the engine's principal variation starting with [move], used to raise
 *   confidence and to confirm follow-up motifs (deflection, decoy, overload...).
 */
interface TacticsDetector {
    fun detect(position: Position, move: Move, pvUci: List<String> = emptyList()): List<TacticInstance>
}

// ---------------------------------------------------------------------------
// Per-move output
// ---------------------------------------------------------------------------

/** A guided walkthrough of a tactic the player missed. Never mutates the main game line. */
data class TacticSimulation(
    val startFen: String,
    val pvUci: List<String>,
    val pvSan: List<String>,
    val perPlyExplanation: List<String>,
    val tactic: TacticInstance,
    val payoffDescription: String
)

/** Everything the UI needs to annotate one ply. */
data class MoveAnnotation(
    val ply: Int,
    val moveNumber: Int,
    val color: Color,
    val san: String,
    val uci: String,
    val fenBefore: String,
    val fenAfter: String,
    val classification: MoveClassification,
    /** Win-percent lost by this move, 0..100. */
    val loss: Double,
    val winPercentBefore: Double,
    val winPercentAfter: Double,
    val evalBeforeCp: Int,
    val evalAfterCp: Int,
    val mateInBefore: Int? = null,
    val mateInAfter: Int? = null,
    /**
     * White-relative centipawns of the engine's **second** MultiPV line before this move, or
     * null when only one line was analysed. `evalBeforeCp` is line 1, so the difference between
     * the two is what the best move was worth over the next-best alternative — the counterfactual
     * cost of *not* finding it. That is the number a *found* tactic is judged by
     * (ANALYSIS_SPEC §9.6): a best move's own swing is ~0 by construction, because `evalBeforeCp`
     * already assumed it would be played.
     */
    val evalSecondBestCp: Int? = null,
    val bestMoveSan: String? = null,
    val bestMoveUci: String? = null,
    val bestLineSan: List<String> = emptyList(),
    val moveAccuracy: Double = 0.0,
    val openingName: String? = null,
    /** Motifs the played move actually executed. */
    val tacticsFound: List<TacticInstance> = emptyList(),
    /** Motifs available via the engine's best move that this move passed up. */
    val tacticsMissed: List<TacticInstance> = emptyList(),
    /** Motifs this move hands to the opponent on the next ply. */
    val threatsAllowed: List<TacticInstance> = emptyList(),
    val text: String = "",
    val simulation: TacticSimulation? = null,
    /**
     * The engine's MultiPV lines for the position BEFORE this move, best first (ascending
     * `multiPv`), one entry per line that carried a first move. Mover-relative, exactly like
     * [EngineLineInput], and **not** flipped to White's perspective like [evalBeforeCp] /
     * [evalSecondBestCp]. Empty for annotations built without MultiPV data. This is what
     * `PracticeJudge` decides "equally good" from (ANALYSIS_SPEC §11); it is a defaulted trailing
     * field so every existing constructor call keeps compiling.
     */
    val candidateLines: List<CandidateLine> = emptyList(),
    /**
     * Every motif the detector reported for the move **actually played**, whatever its
     * classification. [tacticsFound] is this list filtered to BEST / GREAT / BRILLIANT moves (the
     * spec §5.4 "recognised by mover" bucket), so a GOOD or EXCELLENT move that happens to trip a
     * detector has an empty [tacticsFound] but still did what the detector saw. The commentary
     * describes what a move did, so it is written from this list; keeping it here is what lets the
     * text be written again when the user later says which side they were (ANALYSIS_SPEC §7.1)
     * without an engine or a detector. A defaulted trailing field so every constructor call keeps
     * compiling; empty on annotations built without it, in which case the commentary falls back to
     * [tacticsFound].
     */
    val tacticsPlayed: List<TacticInstance> = emptyList()
)

/**
 * One cached MultiPV line of the position before a move, reduced to its first move.
 * [scoreCp] / [mateIn] are from the perspective of the side to move in that position (the
 * mover), exactly as in [EngineLineInput]; [mateIn] is set instead of [scoreCp] for a mate.
 * [san] is null only when [uci] is not legal in the position.
 */
data class CandidateLine(
    val multiPv: Int,
    val uci: String,
    val san: String?,
    val scoreCp: Int?,
    val mateIn: Int?
)

// ---------------------------------------------------------------------------
// Game-level output
// ---------------------------------------------------------------------------

data class PlayerReport(
    val color: Color,
    val name: String?,
    val accuracy: Double,
    val estimatedRating: Int,
    val lowConfidence: Boolean,
    val classificationCounts: Map<MoveClassification, Int>,
    val tacticsFound: List<TacticInstance>,
    val tacticsMissed: List<TacticInstance>
)

data class KeyMoment(
    val ply: Int,
    val san: String,
    val classification: MoveClassification,
    val swing: Double,
    val summary: String
)

data class GameReport(
    val white: PlayerReport,
    val black: PlayerReport,
    val annotations: List<MoveAnnotation>,
    val openingName: String?,
    val openingEco: String?,
    val result: String,
    /** Win-percent for White at each ply, for the evaluation graph. */
    val evalGraph: List<Double>,
    val keyMoments: List<KeyMoment>,
    val analysisDepth: Int
)
