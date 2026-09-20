package net.palaya.chessanalyzer.core.chess

/**
 * Tracks how many times each position (identified by [Position.zobristKey]) has
 * occurred over the course of a game, for threefold-repetition detection.
 *
 * Kept separate from [Position] itself (rather than embedding a history list in
 * every position) so that [Position.makeMove] and [Position.perft] stay cheap and
 * don't pay for history bookkeeping that only game replay/analysis needs.
 */
class RepetitionTracker {
    private val counts = HashMap<Long, Int>()

    /** Records [position] as having occurred; call once per position as a game is replayed. */
    fun record(position: Position) {
        counts.merge(position.zobristKey, 1, Int::plus)
    }

    fun occurrences(position: Position): Int = counts[position.zobristKey] ?: 0

    fun isThreefoldRepetition(position: Position): Boolean = occurrences(position) >= 3
}
