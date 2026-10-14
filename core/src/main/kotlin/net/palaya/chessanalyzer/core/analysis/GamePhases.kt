package net.palaya.chessanalyzer.core.analysis

import net.palaya.chessanalyzer.core.chess.Color
import kotlin.math.abs
import kotlin.math.sign

/**
 * Who stands how well in a stretch of a game, as seven bands from White's point of view (ANALYSIS_SPEC §9.9).
 *
 * The cut-offs are the ones of the nine spoken evaluation bands (`NarrationVocabulary.standing`: 57 / 68 / 82
 * win-percent for the side that is ahead, mirrored for the other), with "decisively winning" folded into "winning"
 * because a stretch of a game is not told in nine shades: [EDGE] "slightly better", [CLEAR] "clearly better",
 * [DECISIVE] "winning".
 */
enum class PhaseStanding(val signed: Int) {
    BLACK_DECISIVE(-3), BLACK_CLEAR(-2), BLACK_EDGE(-1), BALANCED(0), WHITE_EDGE(1), WHITE_CLEAR(2), WHITE_DECISIVE(3);

    /** The side that is ahead, or null when it is balanced. */
    val leader: Color? get() = when {
        signed > 0 -> Color.WHITE
        signed < 0 -> Color.BLACK
        else -> null
    }

    /** 0 balanced, 1 edge, 2 clear, 3 decisive. */
    val level: Int get() = abs(signed)

    /** The same band seen from the other side of the board. */
    fun mirrored(): PhaseStanding = ofSigned(-signed)

    /** The White win-percent interval `[low, high)` this band covers (the last one is closed at 100). */
    val low: Double get() = when (this) {
        BLACK_DECISIVE -> 0.0
        BLACK_CLEAR -> 18.0
        BLACK_EDGE -> 32.0
        BALANCED -> 43.0
        WHITE_EDGE -> 57.0
        WHITE_CLEAR -> 68.0
        WHITE_DECISIVE -> 82.0
    }

    val high: Double get() = when (this) {
        BLACK_DECISIVE -> 18.0
        BLACK_CLEAR -> 32.0
        BLACK_EDGE -> 43.0
        BALANCED -> 57.0
        WHITE_EDGE -> 68.0
        WHITE_CLEAR -> 82.0
        WHITE_DECISIVE -> 100.0 + 1e-9
    }

    companion object {
        /** The band of a White win-percent (0..100), with no hysteresis. */
        fun of(whiteWinPercent: Double): PhaseStanding {
            val ahead = if (whiteWinPercent >= 50.0) whiteWinPercent else 100.0 - whiteWinPercent
            val level = when {
                ahead >= 82.0 -> 3
                ahead >= 68.0 -> 2
                ahead >= 57.0 -> 1
                else -> 0
            }
            return ofSigned(if (whiteWinPercent >= 50.0) level else -level)
        }

        fun ofSigned(signed: Int): PhaseStanding = entries.first { it.signed == signed.coerceIn(-3, 3) }
    }
}

/**
 * A stretch of a game in which the same side holds the same standing. [startPly] / [endPly] are position
 * indices: position `k` is the board after ply `k` (position 0 is the start), so a phase that starts at position
 * `p > 0` began with ply `p`, the move the story tells it at.
 */
data class Phase(val standing: PhaseStanding, val startPly: Int, val endPly: Int) {
    /** How many positions the phase covers. */
    val length: Int get() = endPly - startPly + 1
}

/** The boundary between two phases: [ply] is the move after which the new standing began. */
data class PhaseShift(val ply: Int, val from: PhaseStanding, val to: PhaseStanding) {
    /** The side whose win-percent went up across the boundary, or null when the leader is the same side and nothing moved. */
    val benefiting: Color?
        get() = when {
            to.signed > from.signed -> Color.WHITE
            to.signed < from.signed -> Color.BLACK
            else -> null
        }
}

/**
 * The phases of a game, from the White win-percent of every position (ANALYSIS_SPEC §9.9). Pure and
 * deterministic: the same series gives the same phases, with no clock, no randomness and no hash order.
 *
 *  1. **Hysteresis.** The standing changes only when the win-percent has gone [MARGIN] points beyond the edge of
 *     the band the game is in, so a position sitting on a cut-off does not flicker between two bands.
 *  2. **Runs.** A run of positions in one standing is a phase.
 *  3. **Merging.** A phase shorter than [MIN_LENGTH] positions is not a phase: one noisy ply (or a short dip and
 *     return) does not split a stretch. A short phase between two different standings is a transit and is joined
 *     to the standing it leads to, so the boundary stays on the move that caused it; a dip between two equal
 *     standings joins both. The last phase is kept down to two positions, because a game that ends in a swing
 *     (a blunder, then mate) must be able to say so. A phase is merged one at a time, shortest first, the earlier
 *     first on a tie, until none is left.
 */
object GamePhases {

    /** Win-percent points past a band's edge before the standing changes. */
    const val MARGIN = 3.0

    /** The fewest positions a phase may have (the last phase: [MIN_LAST_LENGTH]). */
    const val MIN_LENGTH = 4
    const val MIN_LAST_LENGTH = 2

    fun detect(whiteWinPercent: List<Double>): List<Phase> {
        if (whiteWinPercent.isEmpty()) return emptyList()
        val states = hysteresis(whiteWinPercent)
        var phases = runs(states)
        while (true) {
            val i = shortest(phases) ?: break
            phases = absorb(phases, i)
        }
        return phases
    }

    /** The boundaries between consecutive phases, in order. */
    fun shifts(phases: List<Phase>): List<PhaseShift> =
        phases.zipWithNext { a, b -> PhaseShift(b.startPly, a.standing, b.standing) }

    /** The phase that covers position [index]. */
    fun phaseAt(phases: List<Phase>, index: Int): Phase? = phases.firstOrNull { index in it.startPly..it.endPly }

    private fun hysteresis(series: List<Double>): List<PhaseStanding> {
        val out = ArrayList<PhaseStanding>(series.size)
        var current = PhaseStanding.of(series[0])
        out.add(current)
        for (i in 1 until series.size) {
            val x = series[i]
            val fresh = PhaseStanding.of(x)
            if (fresh != current && (x < current.low - MARGIN || x >= current.high + MARGIN)) current = fresh
            out.add(current)
        }
        return out
    }

    private fun runs(states: List<PhaseStanding>): List<Phase> {
        val out = ArrayList<Phase>()
        var start = 0
        for (i in 1..states.size) {
            if (i == states.size || states[i] != states[start]) {
                out.add(Phase(states[start], start, i - 1))
                start = i
            }
        }
        return out
    }

    /** The index of the phase to merge next, or null when every phase is long enough. */
    private fun shortest(phases: List<Phase>): Int? {
        if (phases.size < 2) return null
        var best: Int? = null
        for ((i, p) in phases.withIndex()) {
            val min = if (i == phases.lastIndex) MIN_LAST_LENGTH else MIN_LENGTH
            if (p.length >= min) continue
            if (best == null || p.length < phases[best].length) best = i
        }
        return best
    }

    private fun absorb(phases: List<Phase>, i: Int): List<Phase> {
        val p = phases[i]
        val previous = phases.getOrNull(i - 1)
        val next = phases.getOrNull(i + 1)
        val list = phases.toMutableList()
        when {
            // The last phase, or a dip between two equal standings: back into the one before.
            next == null && previous != null -> list[i - 1] = previous.copy(endPly = p.endPly)
            previous == null && next != null -> list[i + 1] = next.copy(startPly = p.startPly)
            previous != null && next != null && previous.standing == next.standing ->
                list[i - 1] = previous.copy(endPly = next.endPly).also { list.removeAt(i + 1) }
            // A transit between two standings: joined to where it leads, so the boundary is on its first move.
            next != null -> list[i + 1] = next.copy(startPly = p.startPly)
        }
        list.removeAt(i)
        // Equal standings that now touch are one phase.
        val merged = ArrayList<Phase>()
        for (q in list) {
            val last = merged.lastOrNull()
            if (last != null && last.standing == q.standing) merged[merged.lastIndex] = last.copy(endPly = q.endPly) else merged.add(q)
        }
        return merged
    }

    /** The sign of a standing change across a shift: +1 White gained, -1 Black gained, 0 none. */
    internal fun direction(shift: PhaseShift): Int = (shift.to.signed - shift.from.signed).sign
}
