package net.palaya.chessanalyzer.ui.model

import net.palaya.chessanalyzer.core.analysis.MoveAnnotation
import net.palaya.chessanalyzer.core.analysis.TacticType
import net.palaya.chessanalyzer.core.narration.NarrationOptions
import net.palaya.chessanalyzer.ui.theme.MoveClassification

/**
 * UI-facing domain models consumed by the screens/components in `ui.screens`/`ui.components`.
 *
 * These are thin, screen-shaped views over the real `:core` analysis result types
 * (`core.analysis.GameReport`, `MoveAnnotation`, ...) — see
 * `net.palaya.chessanalyzer.data.mapper.DomainMapper` for the conversion. [BoardState] is the
 * one exception: it stays a standalone placeholder-shaped type because
 * [net.palaya.chessanalyzer.ui.board.ChessBoard] (owned by another agent) is pinned to its
 * current signature; [MoveRecord.boardAfter] is pre-rendered from the real position via
 * `net.palaya.chessanalyzer.data.mapper.fenToBoardState` rather than `ChessBoard` reading
 * `:core` itself.
 *
 * [PlaceholderData] below is kept only for `@Preview` composables — the real import/analysis
 * flow never touches it.
 */

data class GameHeader(
    val white: String,
    val black: String,
    val whiteElo: Int? = null,
    val blackElo: Int? = null,
    val event: String? = null,
    val date: String? = null,
    /** "1-0", "0-1", "1/2-1/2", "*" */
    val result: String = "*",
)

data class MoveRecord(
    val ply: Int,
    val san: String,
    val classification: MoveClassification? = null,
    /**
     * White-relative centipawn evaluation **after** this move (ANALYSIS_SPEC §1). Positive =
     * White is better, whoever just moved.
     */
    val evalCp: Int? = null,
    val isMateScore: Boolean = false,
    val mateInMoves: Int? = null,
    /** White-relative centipawn evaluation **before** this move; the other half of the swing. */
    val evalBeforeCp: Int? = null,
    /** Signed, White-relative mate distance before this move, when the position was already mating. */
    val mateInBefore: Int? = null,
    val bestMoveSan: String? = null,
    val annotation: String? = null,
    val boardAfter: BoardState? = null,
    val moverColor: PieceColor = if (ply % 2 == 1) PieceColor.WHITE else PieceColor.BLACK,
    /** UCI of the move actually played, e.g. "e2e4" — used to draw the last-move highlight. */
    val uci: String? = null,
    /** UCI of the engine's best move, used to draw the best-move suggestion arrow. */
    val bestMoveUci: String? = null,
    val fenBefore: String? = null,
    val fenAfter: String? = null,
    /**
     * The real `:core` annotation this record was built from (null for placeholder/preview
     * data). Carried through rather than re-derived so the "Show me" missed-tactic simulation
     * and any other deep-dive UI can use [MoveAnnotation.simulation] /
     * `tacticsFound`/`tacticsMissed` directly instead of re-computing them.
     */
    val core: MoveAnnotation? = null,
) {
    /** Move number as shown in a move list, e.g. ply 1 & 2 are both move 1. */
    val moveNumber: Int get() = (ply + 1) / 2

    /**
     * How much this move moved the evaluation, signed and White-relative
     * (`evalAfter - evalBefore`), or null when either end is unknown (placeholder/preview data).
     *
     * This is the quantity the narration significance threshold acts on
     * (`NarrationOptions.significanceThresholdCp`, ANALYSIS_SPEC §9), which is why the review
     * screen shows it next to the score: the score says where the game stands, the swing says
     * what this move did to it.
     */
    val evalSwingCp: Int?
        get() {
            // Never across a mate boundary: mate scores are saturated to ~10000cp, so the
            // subtraction would render as "+99.0 pawns" — arithmetic, not information. The chip's
            // score already reads `M2` or `#`. (ANALYSIS_SPEC §9.1.)
            if (isMateScore || mateInBefore != null) return null
            val after = evalCp ?: return null
            val before = evalBeforeCp ?: return null
            return after - before
        }
}

/**
 * A run of plies the UI should draw as one coloured unit — a multi-move tactic, or a stretch
 * where one player fell apart. The presentation-side view of
 * [net.palaya.chessanalyzer.core.analysis.MoveSequence] (ANALYSIS_SPEC §9.3), carrying the
 * **ui.theme** [MoveClassification] so the move list and the eval graph colour a run exactly the
 * way they colour its individual moves — one palette, no second one.
 */
data class MoveSequenceView(
    val startPly: Int,
    val endPly: Int,
    val classification: MoveClassification,
    /** Short text label, e.g. "Fork" or "Collapse". Carries the meaning when colour cannot. */
    val label: String,
    /** Absolute White-relative centipawn change across the whole run. */
    val totalSwingCp: Int,
) {
    operator fun contains(ply: Int): Boolean = ply in startPly..endPly
}

data class ImportedGame(
    val id: String,
    val header: GameHeader,
    val moves: List<MoveRecord>,
    val pgnText: String = "",
    val analysisDepth: Int = 0,
    val multiPv: Int = 0,
    /** Multi-move runs to draw as single coloured units in the move list (ANALYSIS_SPEC §9.3). */
    val sequences: List<MoveSequenceView> = emptyList(),
)

data class RecentGameSummary(
    val id: String,
    val white: String,
    val black: String,
    val result: String,
    val date: String,
    val plyCount: Int,
)

enum class AnalysisPhase { PREPARING_ENGINE, DOWNLOADING_NET, ANALYZING_MOVES, DONE }

data class AnalysisProgress(
    val phase: AnalysisPhase,
    val currentMoveIndex: Int = 0,
    val totalMoves: Int = 0,
    val bytesDownloaded: Long = 0L,
    val totalBytes: Long = 0L,
    val fractionComplete: Float = 0f,
)

data class ClassificationCount(val classification: MoveClassification, val count: Int)

/** One occurrence of a tactical motif at a specific ply, reachable by tapping it. */
data class TacticOccurrence(
    val ply: Int,
    val moveNumber: Int,
    val san: String,
    val description: String,
)

/**
 * All occurrences of one tactical motif ([type]), collapsed into a single group so the same
 * pattern recurring many times in a game doesn't produce a wall of identical rows. Individual
 * plies stay reachable via [occurrences].
 */
data class TacticGroup(
    val type: TacticType,
    val occurrences: List<TacticOccurrence>,
    /**
     * True when `core.analysis.TacticReferenceLibrary` holds a verified textbook example of this
     * motif, so the group can offer "see this pattern done cleanly" (ANALYSIS_SPEC §10).
     */
    val hasReference: Boolean = false,
) {
    val count: Int get() = occurrences.size
}

data class PlayerReport(
    val name: String,
    val accuracyPercent: Double,
    val estimatedRating: Int,
    val counts: List<ClassificationCount>,
    /**
     * True when the game had too few plies for [estimatedRating] to mean anything
     * (ANALYSIS_SPEC.md §4). The UI must show "not enough moves" instead of the number in
     * that case rather than presenting a meaningless estimate as fact.
     */
    val lowConfidence: Boolean = false,
    /** Tactical motifs this player executed on their own moves, grouped by motif type. */
    val tacticsFound: List<TacticGroup> = emptyList(),
    /** Tactical motifs available to this player that they passed up, grouped by motif type. */
    val tacticsMissed: List<TacticGroup> = emptyList(),
    /**
     * Motifs the significance gate (ANALYSIS_SPEC §9.6) pruned from [tacticsFound] /
     * [tacticsMissed] — detected, but with no bearing on the result at the current threshold.
     * Kept so the report can disclose them behind a "minor tactics" toggle rather than deleting
     * detections the user might still want to look at.
     */
    val minorTacticsFound: List<TacticGroup> = emptyList(),
    val minorTacticsMissed: List<TacticGroup> = emptyList(),
)

data class KeyMoment(
    val ply: Int,
    val moveNumber: Int,
    val san: String,
    val classification: MoveClassification,
    val description: String,
)

data class GameReport(
    val header: GameHeader,
    val white: PlayerReport,
    val black: PlayerReport,
    /** Centipawn eval (white's perspective) per ply, for the report's line chart. */
    val evalHistory: List<Int>,
    /**
     * Classification per ply, index `i` = ply `i + 1`, so the eval graph can colour each point
     * the same way the move list colours the chip. Empty for placeholder data.
     */
    val plyClassifications: List<MoveClassification> = emptyList(),
    /** Multi-move runs to shade on the eval graph (ANALYSIS_SPEC §9.3). */
    val sequences: List<MoveSequenceView> = emptyList(),
    val keyMoments: List<KeyMoment>,
    /**
     * Which side the app user played, when known (from username auto-detection in
     * `AnalysisService`). Null when it couldn't be determined, in which case tactics sections
     * fall back to plain White/Black labelling instead of "you"/"your opponent".
     */
    val userColor: PieceColor? = null,
    /**
     * The centipawn threshold the tactic buckets were gated at (ANALYSIS_SPEC §9.6), so the
     * "minor tactics" disclosure can say what "minor" means. 0 = ungated.
     */
    val tacticThresholdCp: Int = 0,
)

data class EngineSettings(
    val depth: Int = 14,
    val timePerMoveMs: Int = 500,
    val multiPv: Int = 3,
    val username: String = "",
    val engineVersion: String = "Stockfish 19 (bundled)",
    val netVersion: String = "not downloaded",
    /**
     * How much a move must change the evaluation before the narrated review talks about it, in
     * centipawns. Default 50 (= ±0.5 pawns), the value ANALYSIS_SPEC §9.2 fixes; the Settings
     * slider shows it in pawns to one decimal. 0 narrates every candidate move.
     *
     * Lives on this general settings bag (which already carries non-engine preferences such as
     * [username]) rather than on `NarrationVoiceSettings`, which is specifically about *voice*.
     */
    val narrationThresholdCp: Int = NarrationOptions.DEFAULT_SIGNIFICANCE_THRESHOLD_CP,
    /**
     * The language the app and the narrated review use. [AppLanguage.SYSTEM] follows the device;
     * only English is offered today — see [AppLanguage] for what adding one involves.
     */
    val language: AppLanguage = AppLanguage.SYSTEM,
)

/** Placeholder sample data so every screen has something realistic to render in @Preview and initial state. */
object PlaceholderData {
    val sampleHeader = GameHeader(
        white = "dor_palaya",
        black = "MagnusFan42",
        whiteElo = 1742,
        blackElo = 1698,
        event = "Live Chess",
        date = "2026.09.16",
        result = "1-0",
    )

    val sampleMoves: List<MoveRecord> = listOf(
        MoveRecord(1, "e4", MoveClassification.BOOK, evalCp = 20),
        MoveRecord(2, "e5", MoveClassification.BOOK, evalCp = 15),
        MoveRecord(3, "Nf3", MoveClassification.BOOK, evalCp = 25),
        MoveRecord(4, "Nc6", MoveClassification.BOOK, evalCp = 20),
        MoveRecord(5, "Bb5", MoveClassification.BEST, evalCp = 35),
        MoveRecord(6, "a6", MoveClassification.EXCELLENT, evalCp = 30),
        MoveRecord(7, "Ba4", MoveClassification.GOOD, evalCp = 32),
        MoveRecord(8, "Nf6", MoveClassification.BEST, evalCp = 28),
        MoveRecord(9, "O-O", MoveClassification.BOOK, evalCp = 30),
        MoveRecord(10, "Be7", MoveClassification.BOOK, evalCp = 27),
        MoveRecord(
            11, "d4", MoveClassification.GREAT, evalCp = 55,
            bestMoveSan = "d4",
            annotation = "Opening the center at the right moment puts immediate pressure on e5.",
        ),
        MoveRecord(12, "b5", MoveClassification.INACCURACY, evalCp = 90, bestMoveSan = "exd4"),
        MoveRecord(
            13, "Bb3", MoveClassification.MISTAKE, evalCp = 15, bestMoveSan = "dxe5",
            annotation = "Missed the chance to win a pawn outright with dxe5.",
        ),
        MoveRecord(14, "d6", MoveClassification.BEST, evalCp = 20),
        MoveRecord(
            15, "c3", MoveClassification.BLUNDER, evalCp = -180, bestMoveSan = "Nc3",
            annotation = "This drops material — Nxe5 is now crushing for Black.",
        ),
        MoveRecord(16, "Nxe5", MoveClassification.BRILLIANT, evalCp = -420, bestMoveSan = "Nxe5"),
    )

    val sampleGame = ImportedGame(id = "sample-1", header = sampleHeader, moves = sampleMoves)

    val sampleRecent = listOf(
        RecentGameSummary("g1", "dor_palaya", "MagnusFan42", "1-0", "Sep 16", 32),
        RecentGameSummary("g2", "queenside_castle", "dor_palaya", "0-1", "Sep 14", 47),
        RecentGameSummary("g3", "dor_palaya", "rook_lift_pro", "1/2-1/2", "Sep 11", 68),
    )

    val sampleReport = GameReport(
        header = sampleHeader,
        white = PlayerReport(
            name = sampleHeader.white,
            accuracyPercent = 91.4,
            estimatedRating = 1810,
            counts = listOf(
                ClassificationCount(MoveClassification.BRILLIANT, 1),
                ClassificationCount(MoveClassification.GREAT, 1),
                ClassificationCount(MoveClassification.BEST, 9),
                ClassificationCount(MoveClassification.EXCELLENT, 3),
                ClassificationCount(MoveClassification.GOOD, 2),
                ClassificationCount(MoveClassification.BOOK, 6),
                ClassificationCount(MoveClassification.INACCURACY, 2),
                ClassificationCount(MoveClassification.MISTAKE, 1),
                ClassificationCount(MoveClassification.MISS, 0),
                ClassificationCount(MoveClassification.BLUNDER, 1),
                ClassificationCount(MoveClassification.FORCED, 0),
            ),
            tacticsFound = listOf(
                TacticGroup(
                    type = TacticType.DISCOVERED_CHECK,
                    occurrences = listOf(
                        TacticOccurrence(11, 6, "d4", "Opening the center wins a tempo with a discovered attack on e5."),
                    ),
                ),
            ),
            tacticsMissed = listOf(
                TacticGroup(
                    type = TacticType.HANGING_PIECE,
                    occurrences = listOf(
                        TacticOccurrence(13, 7, "Bb3", "dxe5 wins a clean pawn — the e5 pawn was undefended."),
                    ),
                ),
            ),
        ),
        black = PlayerReport(
            name = sampleHeader.black,
            accuracyPercent = 84.2,
            estimatedRating = 1655,
            counts = listOf(
                ClassificationCount(MoveClassification.BRILLIANT, 0),
                ClassificationCount(MoveClassification.GREAT, 0),
                ClassificationCount(MoveClassification.BEST, 6),
                ClassificationCount(MoveClassification.EXCELLENT, 4),
                ClassificationCount(MoveClassification.GOOD, 5),
                ClassificationCount(MoveClassification.BOOK, 5),
                ClassificationCount(MoveClassification.INACCURACY, 3),
                ClassificationCount(MoveClassification.MISTAKE, 2),
                ClassificationCount(MoveClassification.MISS, 1),
                ClassificationCount(MoveClassification.BLUNDER, 1),
                ClassificationCount(MoveClassification.FORCED, 2),
            ),
            tacticsFound = listOf(
                TacticGroup(
                    type = TacticType.FORK,
                    occurrences = listOf(
                        TacticOccurrence(16, 8, "Nxe5", "The knight forks the queen and the undefended c4 bishop."),
                    ),
                ),
            ),
        ),
        evalHistory = listOf(0, 20, 15, 25, 20, 35, 30, 32, 28, 30, 27, 55, 90, 15, 20, -180, -420),
        keyMoments = listOf(
            KeyMoment(15, 8, "c3", MoveClassification.BLUNDER, "White drops a piece to Nxe5."),
            KeyMoment(16, 8, "Nxe5", MoveClassification.BRILLIANT, "Black finds the only move that wins material."),
            KeyMoment(13, 7, "Bb3", MoveClassification.MISTAKE, "White misses dxe5 winning a clean pawn."),
        ),
        userColor = PieceColor.WHITE,
    )
}
