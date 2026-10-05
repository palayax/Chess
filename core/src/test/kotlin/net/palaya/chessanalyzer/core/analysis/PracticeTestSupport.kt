package net.palaya.chessanalyzer.core.analysis

import net.palaya.chessanalyzer.core.chess.Color
import net.palaya.chessanalyzer.core.chess.Position

/**
 * Hand-built reports for [PracticeSelectorTest] / [PracticeJudgeTest]. The fixtures are synthetic on
 * purpose (every number is pinned to a boundary); the real-data tests live in
 * [PracticeRealGameTest].
 */
internal object PracticeFixtures {
    const val START = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"
    const val AFTER_E4 = "rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq e3 0 1"

    /** White king a1 vs black king h8: exactly three legal moves (a2, b1, b2). */
    const val THREE_MOVES = "7k/8/8/8/8/8/8/K7 w - - 0 1"

    /** White pawn a7 about to promote; seven legal moves (3 king moves + 4 promotions). */
    const val PROMOTION = "8/P6k/8/8/8/8/8/K7 w - - 0 1"

    /** Back-rank mate in one: Ra8#. */
    const val MATE_IN_ONE = "6k1/5ppp/8/8/8/8/8/R3K3 w - - 0 1"

    /** Two mates in one: Ra8# and Rb8#. */
    const val TWO_MATES = "6k1/5ppp/8/8/8/8/1R6/R3K3 w - - 0 1"

    fun line(multiPv: Int, uci: String, cp: Int? = null, mate: Int? = null) =
        CandidateLine(multiPv, uci, san = null, scoreCp = cp, mateIn = mate)

    fun fenFor(color: Color) = if (color == Color.WHITE) START else AFTER_E4

    /** A first legal move that is neither [best] nor [played], so a second line can never collide. */
    fun otherMove(fen: String, best: String, played: String): String =
        Position.fromFen(fen).legalMoves().map { it.toUci() }.first { it != best && it != played }

    fun annotation(
        ply: Int,
        color: Color = Color.WHITE,
        classification: MoveClassification = MoveClassification.MISTAKE,
        loss: Double = 15.0,
        winBefore: Double = 60.0,
        fen: String = fenFor(color),
        played: String = if (color == Color.WHITE) "a2a3" else "a7a6",
        best: String? = if (color == Color.WHITE) "e2e4" else "e7e5",
        lines: List<CandidateLine>? = null,
        mateInBefore: Int? = null,
        mateInAfter: Int? = null,
        evalBeforeCp: Int = 100,
        evalAfterCp: Int = 100,
        missed: List<TacticInstance> = emptyList(),
        simulation: TacticSimulation? = null
    ): MoveAnnotation {
        // Default cache: the best move at +100 and one clearly worse move, so the position is
        // judgeable (k = 2 and the last line is far below the 2.0 band).
        val cached = lines ?: if (best != null) {
            listOf(line(1, best, cp = 100), line(2, otherMove(fen, best, played), cp = -300))
        } else emptyList()
        return MoveAnnotation(
            ply = ply,
            moveNumber = (ply + 1) / 2,
            color = color,
            san = "played",
            uci = played,
            fenBefore = fen,
            fenAfter = fen,
            classification = classification,
            loss = loss,
            winPercentBefore = winBefore,
            winPercentAfter = (winBefore - loss).coerceAtLeast(0.0),
            evalBeforeCp = evalBeforeCp,
            evalAfterCp = evalAfterCp,
            mateInBefore = mateInBefore,
            mateInAfter = mateInAfter,
            bestMoveUci = best,
            bestMoveSan = best?.let { "best-$it" },
            bestLineSan = listOf("a", "b"),
            tacticsMissed = missed,
            simulation = simulation,
            candidateLines = cached
        )
    }

    fun tactic(swing: Int, color: Color = Color.WHITE, type: TacticType = TacticType.FORK, confidence: Double = 0.9) =
        TacticInstance(type, color, "e2e4", materialSwing = swing, confidence = confidence)

    private fun player(color: Color) =
        PlayerReport(color, null, 80.0, 1500, false, emptyMap(), emptyList(), emptyList())

    fun report(vararg annotations: MoveAnnotation) = GameReport(
        white = player(Color.WHITE),
        black = player(Color.BLACK),
        annotations = annotations.toList(),
        openingName = null,
        openingEco = null,
        result = "*",
        evalGraph = emptyList(),
        keyMoments = emptyList(),
        analysisDepth = 14
    )

    /** The plies of a [PracticeSet.Puzzles], or an empty list for any other set. */
    fun plies(set: PracticeSet): List<Int> = (set as? PracticeSet.Puzzles)?.puzzles?.map { it.ply } ?: emptyList()

    /** The single puzzle of a one-annotation report, or null when the selector dropped it. */
    fun single(a: MoveAnnotation, userColor: Color = a.color): PracticePuzzle? =
        (PracticeSelector.select(report(a), userColor) as? PracticeSet.Puzzles)?.puzzles?.singleOrNull()
}
