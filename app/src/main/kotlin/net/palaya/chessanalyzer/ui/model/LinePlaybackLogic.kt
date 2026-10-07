package net.palaya.chessanalyzer.ui.model

import net.palaya.chessanalyzer.core.analysis.BestLine
import net.palaya.chessanalyzer.core.analysis.BestLines
import net.palaya.chessanalyzer.core.chess.Color
import net.palaya.chessanalyzer.core.chess.Position
import net.palaya.chessanalyzer.core.chess.parseUci

/*
 * Pure logic behind the line player (V2, ANALYSIS_SPEC §6.2): the one component that steps a line of
 * moves on a board, shared by the Walkthrough (a missed tactic, §6) and the Board's "Show the best line"
 * mode. No Compose and no Android, so it has a host test.
 */

/**
 * The positions of a line: index 0 is [startFen], index k the position after the k-th move. Replayed with
 * the core move generator, so it stops at the first move that is not legal (a stale line degrades to its
 * legal part instead of throwing). A malformed FEN is the start of a game.
 */
fun linePositions(startFen: String, uci: List<String>): List<Position> {
    val out = ArrayList<Position>(uci.size + 1)
    var pos = try {
        Position.fromFen(startFen)
    } catch (e: Exception) {
        Position.startPosition()
    }
    out.add(pos)
    for (u in uci) {
        val move = try {
            pos.parseUci(u)
        } catch (e: Exception) {
            break
        }
        pos = pos.makeMove(move)
        out.add(pos)
    }
    return out
}

/** One press of "previous": never before the start. */
fun linePreviousStep(step: Int): Int = (step - 1).coerceAtLeast(0)

/** One press of "next" (or one tick of Play): never past the last move. */
fun lineNextStep(step: Int, totalPlies: Int): Int = (step + 1).coerceAtMost(totalPlies.coerceAtLeast(0))

/**
 * What Play does from [step]: the next step, or null when the line is over (Play stops there). Pressing
 * Play on the last step starts again from the beginning, as a video player does.
 */
fun linePlayTick(step: Int, totalPlies: Int): Int? = if (step >= totalPlies) null else step + 1

/** Where Play starts: from the beginning when the line has already been played to its end. */
fun linePlayStart(step: Int, totalPlies: Int): Int = if (step >= totalPlies) 0 else step

/**
 * The move a line step has just played, for the caption ("18... Nf5"), or null at the start. The
 * Walkthrough's own numbering ([walkthroughMove]) so both screens number a line the same way.
 */
fun lineStepMove(startFen: String, step: Int, sans: List<String>): WalkthroughMove? =
    if (step <= 0) null else walkthroughMove(startFen, step, sans.getOrNull(step - 1).orEmpty())

/** Whose move it is in the position a step shows. */
fun lineSideToMove(positions: List<Position>, step: Int): Color? = positions.getOrNull(step)?.sideToMove

/**
 * Whether the Board offers "Show the best line" for [move] (V2): every MISTAKE, MISS, BLUNDER and
 * INACCURACY, and any key moment the Summary listed, as long as the engine left a line for it.
 */
fun bestLineOffered(move: MoveRecord, keyMomentPlies: Collection<Int>, lines: List<BestLine>): Boolean {
    if (lines.isEmpty()) return false
    return move.classification?.isMistake == true || move.ply in keyMomentPlies
}

/** The lines to offer for [move]: the best and its near-equal alternatives (§6.2), or none. */
fun bestLinesFor(move: MoveRecord?): List<BestLine> = move?.core?.let { BestLines.linesFor(it) }.orEmpty()

/**
 * "18… Nf5 19. Qd2 Nd4": a line in move-number notation, the number before every White move and before
 * a first move by Black, as a scoresheet writes it.
 */
fun numberedLine(line: BestLine): String {
    val parts = ArrayList<String>()
    for ((i, step) in line.steps.withIndex()) {
        parts += when {
            step.color == Color.WHITE -> "${step.moveNumber}. ${step.san}"
            i == 0 -> "${step.moveNumber}… ${step.san}"
            else -> step.san
        }
    }
    return parts.joinToString(" ")
}
