package net.palaya.chessanalyzer.ui.model

import net.palaya.chessanalyzer.core.analysis.EvalFormat
import net.palaya.chessanalyzer.core.analysis.MaterialBalance
import net.palaya.chessanalyzer.core.analysis.OpeningBook
import net.palaya.chessanalyzer.core.analysis.PositionEval
import net.palaya.chessanalyzer.core.analysis.WinProbability
import net.palaya.chessanalyzer.core.chess.Color
import net.palaya.chessanalyzer.core.chess.PieceType
import net.palaya.chessanalyzer.core.chess.Position
import net.palaya.chessanalyzer.core.chess.Square
import net.palaya.chessanalyzer.core.chess.parseSan
import net.palaya.chessanalyzer.core.chess.stripSanAnnotations
import kotlin.math.abs

/*
 * Live game insights for the "While you wait" panel (docs/WAITING_CONTENT.md §3).
 *
 * Every insight is an exact derivation from two things the analysis already has: the game's SAN moves and the
 * per-position evaluations known so far. Nothing here is a judgement: no "great", "unlucky" or "dominant".
 * CLAUDE.md, "Generated text is a claim, and is verified": the numbers are computed by `:core` (EvalFormat for the
 * score text, WinProbability for the win-percent scale, MaterialBalance for the material count, the opening book
 * for names) and LiveInsightsTest pins every template with the real strings.xml sentence.
 *
 * Input convention: index 0 is the start position and index i is the position after ply i (ply 1 = White's first
 * move). `evals` is keyed by that index. The evaluations are PositionEval (side-to-move numbers); every score here
 * goes through `WinProbability.cpWhiteRelative` / `mateWhiteRelative` first, so the text is White-relative unless
 * a template says "for Black".
 */

/**
 * The kinds of insight, in the order they are listed (the card shows the first ones; the order is the priority).
 * [resName] is the `strings.xml` entry holding the English template; the arguments of each kind are documented on
 * the entry itself.
 */
enum class InsightKind(val resName: String) {
    OPENING("wait_insight_opening"),
    SWING("wait_insight_swing"),
    SWING_YOURS("wait_insight_swing_yours"),
    SWING_OPPONENT("wait_insight_swing_opponent"),
    MATERIAL_LEVEL("wait_insight_material_level"),
    MATERIAL_AHEAD("wait_insight_material_ahead"),
    RUN("wait_insight_run"),
    RUN_YOURS("wait_insight_run_yours"),
    RUN_OPPONENT("wait_insight_run_opponent"),
    PEAK("wait_insight_peak"),
    LEAD_CHANGES("wait_insight_lead_changes"),
    CHECKS("wait_insight_checks"),
    CAPTURES("wait_insight_captures"),
    LEFT_BOOK("wait_insight_left_book"),
    QUEENS_OFF("wait_insight_queens_off"),
    CASTLED_SHORT("wait_insight_castled_short"),
    CASTLED_LONG("wait_insight_castled_long"),
    NOT_CASTLED("wait_insight_not_castled"),
    PROMOTIONS("wait_insight_promotions"),
    GAME_LENGTH("wait_insight_game_length"),
    PROGRESS("wait_insight_progress"),
}

/**
 * One insight: a [kind] and the values its template needs, in the order of the template's `%1$s`, `%2$s`...
 * An argument is a String, an Int, or a [Color] (the UI writes it as the localized "White"/"Black").
 */
data class LiveInsight(val kind: InsightKind, val args: List<Any> = emptyList())

/** What the win-percent scale calls a precise move: it lost under this many points of win chance (spec §2: the "Excellent" bound). */
const val PRECISE_LOSS_BELOW = 2.0

/** A run has to be at least this long to be reported. */
const val MIN_REPORTED_RUN = 2

/**
 * The text that tells the panel whether anything worth showing again has changed: the insights without the
 * progress counter, which changes every second and would make the insights card win every rotation.
 */
fun insightsSignature(insights: List<LiveInsight>): String =
    insights.filter { it.kind != InsightKind.PROGRESS }.joinToString("|") { i -> i.kind.name + ":" + i.args.joinToString(",") }

/** "17.Qxd4" for White's 17th move, "17…Qxd4" for Black's (ply 1 = White's first move). */
fun moveLabel(ply: Int, san: String): String {
    val number = (ply + 1) / 2
    val clean = stripSanAnnotations(san)
    return if (ply % 2 == 1) "$number.$clean" else "$number…$clean"
}

/**
 * The convenience entry point: build the insights for the moves and evaluations known now.
 *
 * Calling this on every new evaluation replays the game each time (microseconds for a game of normal length), but a
 * screen that gets one evaluation per second should keep a [LiveInsightEngine] in a `remember(sans)` and call
 * [LiveInsightEngine.insights], which replays once.
 *
 * @param sans the whole game's moves (the analysis knows them before it starts).
 * @param evals index -> evaluation, whatever has been produced so far.
 * @param userColor the side the user plays, or null when not chosen.
 * @param book the opening book, or null to leave the opening insights out.
 */
fun liveInsights(
    sans: List<String>,
    evals: Map<Int, PositionEval>,
    userColor: Color?,
    book: OpeningBook? = null,
): List<LiveInsight> = LiveInsightEngine(sans, book).insights(evals, userColor)

class LiveInsightEngine(private val sans: List<String>, private val book: OpeningBook? = null) {

    /** positions[i] = the position after ply i; shorter than sans.size + 1 if a move does not parse. */
    private val positions: List<Position> = run {
        val out = ArrayList<Position>(sans.size + 1)
        var pos = Position.startPosition()
        out += pos
        for (san in sans) {
            pos = try {
                pos.makeMove(pos.parseSan(san))
            } catch (e: Exception) {
                break
            }
            out += pos
        }
        out
    }

    /** How many plies replayed cleanly. */
    private val replayed: Int get() = positions.size - 1

    private val colorOfPly: (Int) -> Color = { ply -> if (ply % 2 == 1) Color.WHITE else Color.BLACK }

    private fun label(ply: Int): String = moveLabel(ply, sans[ply - 1])

    // ---- facts of the moves alone, computed once ----

    private val openingEntry by lazy {
        val b = book ?: return@lazy null
        var found: net.palaya.chessanalyzer.core.analysis.OpeningEntry? = null
        for (ply in 1..replayed) b.lookup(positions[ply].toFen())?.let { found = it }
        found
    }

    private val firstOutOfBookPly by lazy {
        val b = book ?: return@lazy null
        (1..replayed).firstOrNull { !b.isBookPosition(positions[it].toFen()) }
    }

    private val queensOffPly by lazy {
        (1..replayed).firstOrNull { ply -> hasNoQueens(positions[ply]) }
    }

    private fun hasNoQueens(p: Position): Boolean =
        (0..63).none { p.pieceAt(Square(it))?.type == PieceType.QUEEN }

    fun insights(evals: Map<Int, PositionEval>, userColor: Color?): List<LiveInsight> {
        val out = ArrayList<LiveInsight>()
        val known = evals.keys.filter { it in 0..sans.size }.sorted()
        val latest = known.lastOrNull()

        // Opening (from the moves alone)
        openingEntry?.let { out += LiveInsight(InsightKind.OPENING, listOf(it.name, it.eco)) }

        // Biggest swing so far
        biggestSwing(evals)?.let { (ply, before, after) ->
            val kind = when (userColor) {
                null -> InsightKind.SWING
                colorOfPly(ply) -> InsightKind.SWING_YOURS
                else -> InsightKind.SWING_OPPONENT
            }
            out += LiveInsight(kind, listOf(label(ply), before, after))
        }

        // Material at the latest analysed position
        if (latest != null && latest in 1..replayed) {
            val balance = MaterialBalance.of(positions[latest])
            val ahead = balance.ahead
            out += if (ahead == null) {
                LiveInsight(InsightKind.MATERIAL_LEVEL, listOf(label(latest)))
            } else {
                LiveInsight(InsightKind.MATERIAL_AHEAD, listOf(label(latest), ahead, balance.lead(ahead)))
            }
        }

        // Longest run of precise moves
        runInsights(evals, userColor, out)

        // Best evaluation reached by each side
        peakInsights(evals, userColor, out)

        // Changes of side
        leadChanges(evals)?.let { if (it >= 1) out += LiveInsight(InsightKind.LEAD_CHANGES, listOf(it)) }

        // Checks and captures in the moves analysed so far
        if (latest != null && latest >= 1) {
            val upTo = minOf(latest, replayed)
            val checks = intArrayOf(0, 0)
            val captures = intArrayOf(0, 0)
            for (ply in 1..upTo) {
                val san = sans[ply - 1]
                val side = if (ply % 2 == 1) 0 else 1
                if (san.endsWith("+") || san.endsWith("#")) checks[side]++
                if (san.contains('x')) captures[side]++
            }
            out += LiveInsight(InsightKind.CHECKS, listOf(checks[0], checks[1]))
            out += LiveInsight(InsightKind.CAPTURES, listOf(captures[0], captures[1]))
        }

        // Facts of the whole game
        firstOutOfBookPly?.let { out += LiveInsight(InsightKind.LEFT_BOOK, listOf(label(it))) }
        queensOffPly?.let { out += LiveInsight(InsightKind.QUEENS_OFF, listOf(label(it))) }
        if (replayed > 0) {
            for (color in listOf(Color.WHITE, Color.BLACK)) {
                val plies = (1..replayed).filter { colorOfPly(it) == color }
                val castle = plies.firstOrNull { stripSanAnnotations(sans[it - 1]).startsWith("O-O") }
                out += when {
                    castle == null -> LiveInsight(InsightKind.NOT_CASTLED, listOf(color))
                    stripSanAnnotations(sans[castle - 1]).startsWith("O-O-O") ->
                        LiveInsight(InsightKind.CASTLED_LONG, listOf(color, (castle + 1) / 2))
                    else -> LiveInsight(InsightKind.CASTLED_SHORT, listOf(color, (castle + 1) / 2))
                }
            }
            val promotions = intArrayOf(0, 0)
            for (ply in 1..replayed) if (sans[ply - 1].contains('=')) promotions[if (ply % 2 == 1) 0 else 1]++
            if (promotions[0] + promotions[1] > 0) out += LiveInsight(InsightKind.PROMOTIONS, listOf(promotions[0], promotions[1]))
            out += LiveInsight(InsightKind.GAME_LENGTH, listOf((sans.size + 1) / 2, sans.size / 2))
        }

        // The count of positions analysed
        out += LiveInsight(InsightKind.PROGRESS, listOf(known.size, sans.size + 1))

        // Stable: within a kind the insertion order stays (White before Black); the three castling kinds share one slot.
        return out.sortedBy { sortSlot(it.kind) }
    }

    private fun sortSlot(kind: InsightKind): Int = when (kind) {
        InsightKind.CASTLED_SHORT, InsightKind.CASTLED_LONG, InsightKind.NOT_CASTLED -> InsightKind.CASTLED_SHORT.ordinal
        else -> kind.ordinal
    }

    // ---- evaluation helpers ----

    private fun cpWhite(e: PositionEval): Int = WinProbability.cpWhiteRelative(e)

    private fun scoreText(e: PositionEval): String = EvalFormat.score(cpWhite(e), WinProbability.mateWhiteRelative(e))

    /** -1 / 0 / +1: whom the displayed score favours (a score that reads "0.0" favours nobody). */
    private fun displayedSide(e: PositionEval): Int {
        val text = scoreText(e)
        return when {
            text == "0.0" -> 0
            text.startsWith("-") -> -1
            text == "#" -> if (cpWhite(e) >= 0) 1 else -1
            else -> 1
        }
    }

    /** (ply, "+0.4", "-2.8") of the move that changed the White-relative score the most, ties to the earlier move. */
    private fun biggestSwing(evals: Map<Int, PositionEval>): Triple<Int, String, String>? {
        var best: Triple<Int, String, String>? = null
        var bestDelta = -1
        for (ply in 1..minOf(sans.size, replayed)) {
            val before = evals[ply - 1] ?: continue
            val after = evals[ply] ?: continue
            val delta = abs(cpWhite(after) - cpWhite(before))
            val b = scoreText(before)
            val a = scoreText(after)
            if (a == b) continue
            if (delta > bestDelta) {
                bestDelta = delta
                best = Triple(ply, b, a)
            }
        }
        return best
    }

    /** One side's longest run of consecutive own moves that each lost under [PRECISE_LOSS_BELOW] win-percent points. */
    private fun longestRun(evals: Map<Int, PositionEval>, color: Color): Triple<Int, Int, Int>? {
        var best: Triple<Int, Int, Int>? = null
        var length = 0
        var startMove = 0
        var lastMove = 0
        for (ply in 1..minOf(sans.size, replayed)) {
            if (colorOfPly(ply) != color) continue
            val before = evals[ply - 1]
            val after = evals[ply]
            val precise = before != null && after != null &&
                WinProbability.loss(before, after, color) < PRECISE_LOSS_BELOW
            val moveNo = (ply + 1) / 2
            if (precise) {
                if (length == 0) startMove = moveNo
                length++
                lastMove = moveNo
                if (best == null || length > best.first) best = Triple(length, startMove, lastMove)
            } else {
                length = 0
            }
        }
        return best?.takeIf { it.first >= MIN_REPORTED_RUN }
    }

    private fun runInsights(evals: Map<Int, PositionEval>, userColor: Color?, out: MutableList<LiveInsight>) {
        if (userColor == null) {
            for (color in listOf(Color.WHITE, Color.BLACK)) {
                longestRun(evals, color)?.let { (n, from, to) -> out += LiveInsight(InsightKind.RUN, listOf(color, n, from, to)) }
            }
        } else {
            longestRun(evals, userColor)?.let { (n, from, to) -> out += LiveInsight(InsightKind.RUN_YOURS, listOf(n, from, to)) }
            longestRun(evals, userColor.opposite())?.let { (n, from, to) -> out += LiveInsight(InsightKind.RUN_OPPONENT, listOf(n, from, to)) }
        }
    }

    private fun peakInsights(evals: Map<Int, PositionEval>, userColor: Color?, out: MutableList<LiveInsight>) {
        val sides = if (userColor == Color.BLACK) listOf(Color.BLACK, Color.WHITE) else listOf(Color.WHITE, Color.BLACK)
        for (color in sides) {
            var bestPly = -1
            var bestCp = 0
            for (ply in 1..minOf(sans.size, replayed)) {
                val e = evals[ply] ?: continue
                val side = displayedSide(e)
                if (side == 0 || (side > 0) != (color == Color.WHITE)) continue
                val cp = if (color == Color.WHITE) cpWhite(e) else -cpWhite(e)
                if (bestPly == -1 || cp > bestCp) {
                    bestPly = ply
                    bestCp = cp
                }
            }
            if (bestPly == -1) continue
            val e = evals[bestPly]!!
            val mate = WinProbability.mateWhiteRelative(e)
            val text = if (color == Color.WHITE) {
                EvalFormat.score(cpWhite(e), mate)
            } else {
                EvalFormat.score(-cpWhite(e), mate?.let { -it })
            }
            out += LiveInsight(InsightKind.PEAK, listOf(color, text, label(bestPly)))
        }
    }

    /** How many times the displayed score switched from favouring one side to favouring the other (zero readings skipped). */
    private fun leadChanges(evals: Map<Int, PositionEval>): Int? {
        val sequence = evals.keys.filter { it in 0..sans.size }.sorted().map { displayedSide(evals.getValue(it)) }.filter { it != 0 }
        if (sequence.isEmpty()) return null
        return sequence.zipWithNext().count { (a, b) -> a != b }
    }
}
