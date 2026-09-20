package net.palaya.chessanalyzer.core.chess

/**
 * Counts the number of leaf nodes reachable in exactly [depth] plies of fully
 * legal moves from this position. Standard move-generator correctness test.
 */
fun Position.perft(depth: Int): Long {
    if (depth == 0) return 1L
    var nodes = 0L
    for (move in legalMoves()) {
        nodes += makeMove(move).perft(depth - 1)
    }
    return nodes
}

/**
 * Per-root-move perft breakdown (move UCI -> leaf count at [depth] - 1 below it),
 * useful for diffing against a reference engine when a perft total is wrong.
 */
fun Position.perftDivide(depth: Int): Map<String, Long> {
    val result = LinkedHashMap<String, Long>()
    for (move in legalMoves()) {
        result[move.toUci()] = makeMove(move).perft(depth - 1)
    }
    return result
}
