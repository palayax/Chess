package net.palaya.chessanalyzer.core.analysis

import net.palaya.chessanalyzer.core.chess.Color
import net.palaya.chessanalyzer.core.chess.Position
import net.palaya.chessanalyzer.core.chess.moveToSan
import net.palaya.chessanalyzer.core.chess.parseUci
import kotlin.math.max
import kotlin.math.min

/** One move of a displayed engine line (ANALYSIS_SPEC §6.2). */
data class BestLineStep(
    val uci: String,
    val san: String,
    /** The full-move number as a player counts it: 18 for both "18. Nf5" and "18... Qd8". */
    val moveNumber: Int,
    /** Who plays this move. */
    val color: Color,
    val fenBefore: String,
    val fenAfter: String,
)

/**
 * An engine line, from the position BEFORE a game move, as the Board's line mode and the video play it
 * (ANALYSIS_SPEC §6.2, V2). Built only by [BestLines.build], so every step is legal (checked with the
 * core move generator, step by step) and the line is never longer than the depth supports.
 *
 * [scoreCp] / [mateIn] are the engine's numbers for the line, mover-relative exactly like
 * [CandidateLine]; [endsInCheckmate] and [settledGainCp] are what the board proves about the shown
 * plies ([ExchangeEvaluator.settledGain] for [mover], so a line that stops right after a capture is
 * not credited with a piece that is taken straight back).
 */
data class BestLine(
    val startFen: String,
    val multiPv: Int,
    val steps: List<BestLineStep>,
    val scoreCp: Int?,
    val mateIn: Int?,
    val depth: Int,
    /** How many plies the engine's PV had; [steps] is at most [BestLines.maxPliesFor] of them. */
    val pvLength: Int,
    /** The side to move at [startFen]: whose line it is. */
    val mover: Color,
    val endsInCheckmate: Boolean,
    val settledGainCp: Int,
) {
    val ucis: List<String> get() = steps.map { it.uci }
    val sans: List<String> get() = steps.map { it.san }
    val endFen: String get() = steps.lastOrNull()?.fenAfter ?: startFen

    fun startPosition(): Position = Position.fromFen(startFen)
    fun endPosition(): Position = Position.fromFen(endFen)

    /** The line's score from White's side (the eval bar and every printed score are White-relative, §9.4). */
    val whiteCp: Int? get() = scoreCp?.let { if (mover == Color.WHITE) it else -it }
    val whiteMateIn: Int? get() = mateIn?.let { if (mover == Color.WHITE) it else -it }

    /** The engine's own win percent for the mover (§1.1: a mate is 100 / 0). */
    val moverWinPercent: Double
        get() = WinProbability.winPercentOfLine(EngineLineInput(multiPv, scoreCp, mateIn, depth, emptyList()))

    /** "+2.3", "M3", "-M2": the line's score as every score in the app is printed (§9.4). */
    val scoreText: String get() = EvalFormat.score(whiteCp, whiteMateIn)
}

/**
 * The rules for which engine lines are shown and how much of each (ANALYSIS_SPEC §6.2).
 *
 * **How many plies: `min(PV length, depth / 2, 8)`, and never past a checkmate.** The PV Stockfish
 * prints is longer than the depth it searched (on the five recorded games the best line before a move
 * that lost at least 5 win-percent was 8 to 29 plies at depth 12 to 20: longer than the depth in 38 of
 * 41 cases): its tail comes from extensions and the quiescence search, not from moves the search chose
 * with depth to spare. A move at ply k of the PV was chosen with about `depth - k` plies of search
 * under it, so stopping at `depth / 2` keeps every shown move backed by at least half the search: 6
 * plies at Quick (12), 7 at Standard (14), 8 at Deep (18, the cap). The cap of 8 is the walkthrough's
 * (§6), so the missed-tactic walkthrough and a best line agree on how far a line goes. "Up to the
 * point the tactic resolves" was measured and rejected as the rule: the material in those PVs kept
 * changing until ply 12 in the median case (last capture at plies 0 to 25), so "resolved" is not a
 * stable point inside the part of the line the depth supports.
 *
 * **Which lines.** The best line always (MultiPV 1). Lines 2 and 3 only when the engine rates them
 * within [ALTERNATIVE_MARGIN] win-percent of the best (the best-or-near-best bound of §2 and §11) and
 * they do not start with the move actually played (that is the game, not an alternative). An
 * annotation built without MultiPV data offers no line.
 */
object BestLines {
    /** The walkthrough's cap (§6): no line runs past 8 plies. */
    const val MAX_PLIES = 8

    /** Lines 2 and 3 are offered when within this many win-percent of line 1 (= [PracticeSelector.ACCEPT_LOSS]). */
    const val ALTERNATIVE_MARGIN = PracticeSelector.ACCEPT_LOSS

    /** The best line and at most two alternatives (MultiPV 3, §8). */
    const val MAX_LINES = 3

    /** At most this many plies of a line are played in the narrated video (the DWELL variation length of §9.7). */
    const val VIDEO_MAX_PLIES = 4

    /** `min(depth / 2, 8)`, at least 1; an unknown depth (0) shows the first move only. */
    fun maxPliesFor(depth: Int): Int = if (depth <= 0) 1 else min(MAX_PLIES, max(1, depth / 2))

    /**
     * [line] played from [fenBefore], cut to [maxPlies] (default [maxPliesFor] its depth) and to the
     * part of the PV that is legal, step by step. Null when not even the first move is legal. A line
     * with no PV (built without one) is its first move alone.
     */
    fun build(fenBefore: String, line: CandidateLine, maxPlies: Int = maxPliesFor(line.depth)): BestLine? {
        val start = try {
            Position.fromFen(fenBefore)
        } catch (e: Exception) {
            return null
        }
        val pv = line.pvUci.ifEmpty { listOf(line.uci) }
        val cap = min(maxPlies, maxPliesFor(line.depth))
        val steps = ArrayList<BestLineStep>()
        var pos = start
        for (uci in pv) {
            if (steps.size >= cap) break
            val move = try {
                pos.parseUci(uci)
            } catch (e: Exception) {
                break
            }
            val after = pos.makeMove(move)
            steps.add(
                BestLineStep(
                    uci = uci,
                    san = pos.moveToSan(move),
                    moveNumber = pos.fullmoveNumber,
                    color = pos.sideToMove,
                    fenBefore = pos.toFen(),
                    fenAfter = after.toFen(),
                )
            )
            pos = after
            if (after.isCheckmate()) break
        }
        if (steps.isEmpty()) return null
        return BestLine(
            startFen = start.toFen(),
            multiPv = line.multiPv,
            steps = steps,
            scoreCp = line.scoreCp,
            mateIn = line.mateIn,
            depth = line.depth,
            pvLength = pv.size,
            mover = start.sideToMove,
            endsInCheckmate = pos.isCheckmate(),
            settledGainCp = ExchangeEvaluator.settledGain(start, pos, start.sideToMove),
        )
    }

    /**
     * The lines to offer for [annotation]'s position before the move: the best, then lines 2 and 3 when
     * they are within [ALTERNATIVE_MARGIN] of it and are not the move that was played. Empty when the
     * annotation carries no engine line.
     */
    fun linesFor(annotation: MoveAnnotation): List<BestLine> {
        val candidates = annotation.candidateLines.sortedBy { it.multiPv }
        if (candidates.isEmpty()) return emptyList()
        val best = build(annotation.fenBefore, candidates.first()) ?: return emptyList()
        val out = arrayListOf(best)
        for (c in candidates.drop(1)) {
            if (out.size >= MAX_LINES) break
            if (c.uci == annotation.uci) continue
            val line = build(annotation.fenBefore, c) ?: continue
            if (best.moverWinPercent - line.moverWinPercent <= ALTERNATIVE_MARGIN) out.add(line)
        }
        return out
    }

    /** The best line only. */
    fun bestFor(annotation: MoveAnnotation): BestLine? = linesFor(annotation).firstOrNull()

}

/**
 * The short caption under a displayed line (ANALYSIS_SPEC §6.2, the rules of §6.1 and §7.2). Every
 * sentence is a claim the data proves, and one that cannot be proved is left out, not hedged:
 *
 *  1. **The engine's verdict, as the engine's.** "The engine rates this line +2.3." (White-relative,
 *     §9.4), or, when the engine reported a mate, "The engine sees a forced mate in 3 for White." Never
 *     "wins": a score is not a result.
 *  2. **What the shown moves prove on the board.** "The line ends in checkmate." when its last move
 *     mates; otherwise, when [BestLine.settledGainCp] is at least 100 cp (the opponent's best
 *     take-back already charged), "In this line White wins a piece." named by the 40 cp rule of §6.1,
 *     or "...wins material" between two piece values. Nothing for a line that nets nothing or gives
 *     material up.
 *
 * Who it is about follows §7 rule 4: "you" / "your opponent" for a chosen side, colours otherwise.
 */
object BestLineCaption {

    fun text(line: BestLine, viewer: Color?): String = listOfNotNull(engineSentence(line, viewer), boardSentence(line, viewer))
        .joinToString(" ")

    /** The engine's number for the line, said as the engine's. */
    fun engineSentence(line: BestLine, viewer: Color?): String {
        val mate = line.mateIn
        if (mate != null && mate != 0) {
            val winner = if (mate > 0) line.mover else line.mover.opposite()
            return "The engine sees a forced mate in ${kotlin.math.abs(mate)} for ${who(winner, viewer)}."
        }
        return "The engine rates this line ${line.scoreText}."
    }

    /** The board's proof about the shown plies, or null when it proves nothing worth saying. */
    fun boardSentence(line: BestLine, viewer: Color?): String? {
        if (line.endsInCheckmate) return "The line ends in checkmate."
        val gain = line.settledGainCp
        if (gain < MIN_GAIN_CP) return null
        val subject = who(line.mover, viewer)
        val verb = if (subject == "you") "win" else "wins"
        // "the exchange" when the shown plies give a minor piece for a rook and nothing else (C1).
        val what = ExchangeEvaluator.describeSettled(line.startPosition(), line.endPosition(), line.mover, MIN_GAIN_CP) ?: "material"
        return "In this line $subject $verb $what."
    }

    private fun who(side: Color, viewer: Color?): String = when {
        viewer == null -> if (side == Color.WHITE) "White" else "Black"
        side == viewer -> "you"
        else -> "your opponent"
    }

    /** A gain is named only from a pawn up (§6.1 `minimumPayoffCp`). */
    const val MIN_GAIN_CP = 100
}
