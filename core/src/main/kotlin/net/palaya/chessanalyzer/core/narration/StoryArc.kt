package net.palaya.chessanalyzer.core.narration

import net.palaya.chessanalyzer.core.analysis.Phase
import net.palaya.chessanalyzer.core.analysis.PhaseShift
import net.palaya.chessanalyzer.core.analysis.PhaseStanding
import net.palaya.chessanalyzer.core.chess.Color

/**
 * The shape of a whole game, read off its [Phase]s and its result (ANALYSIS_SPEC §9.9): the one sentence the
 * cold open can say about the stakes, and only when the evaluation graph proves it. Pure; the conditions are
 * stated here once and `scripts/audit_commentary.py story` re-derives each from the recorded engine data.
 *
 * Positions are counted in plies, so "N moves" is the number of positions in the stretch divided by two,
 * rounded down.
 */
internal sealed interface StoryArc {

    /** [winner] won, but the other side was clearly better (level 2 or more) for [moves] moves in a row. */
    data class Comeback(val winner: Color, val moves: Int) : StoryArc

    /**
     * [winner] won and the other side never led: from move [moveNumber] on [winner] was ahead in every phase
     * ([fromTheStart]: that began within the first [EARLY_MOVES] moves).
     */
    data class TookOver(val winner: Color, val moveNumber: Int, val fromTheStart: Boolean) : StoryArc

    /** A draw in which neither side was ever more than slightly better. */
    data object TightDraw : StoryArc

    /** A draw in which [leader] was clearly better (level 2 or more) for [moves] moves in a row. */
    data class WastedLead(val leader: Color, val moves: Int) : StoryArc

    companion object {

        /** A clear lead has to last this many moves to be called "for N moves". */
        const val MIN_CLEAR_MOVES = 6

        /** A lead that began within this many moves is "from the opening". */
        const val EARLY_MOVES = 6

        /** The shortest game (in plies) that is given a story of this kind at all. */
        const val MIN_PLIES = 16

        /** The longest stretch, in positions, in which [side] is clearly better (level 2 or more) in consecutive phases. */
        fun longestClearRun(phases: List<Phase>, side: Color): Int {
            var best = 0
            var run = 0
            for (p in phases) {
                if (p.standing.leader == side && p.standing.level >= 2) {
                    run += p.length
                    if (run > best) best = run
                } else {
                    run = 0
                }
            }
            return best
        }

        /** The arc of a game of [plies] plies with [phases] and [result] ("1-0", "0-1", "1/2-1/2"), or null for none. */
        fun of(phases: List<Phase>, result: String, plies: Int): StoryArc? {
            if (plies < MIN_PLIES || phases.isEmpty()) return null
            return when (result) {
                "1-0" -> decisive(phases, Color.WHITE)
                "0-1" -> decisive(phases, Color.BLACK)
                "1/2-1/2" -> drawn(phases)
                else -> null
            }
        }

        private fun decisive(phases: List<Phase>, winner: Color): StoryArc? {
            val loser = winner.opposite()
            val run = longestClearRun(phases, loser)
            if (run / 2 >= MIN_CLEAR_MOVES) return Comeback(winner, run / 2)
            // The loser never led at all, and the winner was ahead from some phase to the end.
            if (phases.any { it.standing.leader == loser }) return null
            val firstLead = phases.indexOfFirst { it.standing.leader == winner }
            if (firstLead < 0) return null
            if (phases.drop(firstLead).any { it.standing.leader != winner }) return null
            val span = phases.drop(firstLead).sumOf { it.length }
            if (span < 8) return null
            val startPly = phases[firstLead].startPly
            val moveNumber = (startPly + 1) / 2
            return TookOver(winner, moveNumber.coerceAtLeast(1), fromTheStart = moveNumber <= EARLY_MOVES)
        }

        private fun drawn(phases: List<Phase>): StoryArc? {
            for (side in Color.entries) {
                val run = longestClearRun(phases, side)
                if (run / 2 >= MIN_CLEAR_MOVES) return WastedLead(side, run / 2)
            }
            return if (phases.all { it.standing.level <= 1 }) TightDraw else null
        }

        /**
         * What followed the turning move at [ply] (played by [mover]): [Aftermath.NeverBack] when every phase from
         * the one it began is led by the other side, [Aftermath.FoughtBack] with the move number at which the
         * mover's side first got the standing back to level or better, otherwise null.
         */
        fun aftermath(phases: List<Phase>, ply: Int, mover: Color): Aftermath? {
            val opponent = mover.opposite()
            val from = phases.indexOfFirst { ply in it.startPly..it.endPly }
            if (from < 0) return null
            val after = phases.drop(from)
            val first = after.first().standing
            if (first.leader != opponent || first.level < 1) return null
            if (after.all { it.standing.leader == opponent }) return Aftermath.NeverBack(opponent)
            val back = after.firstOrNull { it.standing.leader != opponent } ?: return null
            return Aftermath.FoughtBack(mover, (back.startPly + 1) / 2, back.standing)
        }
    }

    /** What happened after the turning move, when the phases prove it. */
    sealed interface Aftermath {
        data class NeverBack(val leader: Color) : Aftermath
        data class FoughtBack(val side: Color, val moveNumber: Int, val standing: PhaseStanding) : Aftermath
    }
}

/** The phase boundaries of a game, indexed by the ply that caused them. */
internal fun List<PhaseShift>.byPly(): Map<Int, PhaseShift> = associateBy { it.ply }
