package net.palaya.chessanalyzer.ui.model

/*
 * Pure logic behind the Walkthrough's step caption (UX step U8, docs/MOBILE_UX_DESIGN.md §6.5): which
 * move number a step plays and whether the main button reads "Next" or "Done". No Compose and no
 * Android, so it has a host test.
 */

/** The move a walkthrough step plays: its number as a player counts it, whose it is, and its SAN. */
data class WalkthroughMove(val number: Int, val isWhite: Boolean, val san: String)

/**
 * The move played by [step] (1-based: step 1 plays the first move of the line), numbered from the
 * walkthrough's start position, so a line that starts with Black to move on move 8 reads
 * "8... Bxa6" and then "9. Nf3", not "1. Bxa6". Reads the side to move and the full-move counter
 * from [startFen]; a malformed FEN is treated as the start of a game.
 */
fun walkthroughMove(startFen: String, step: Int, san: String): WalkthroughMove {
    val fields = startFen.trim().split(' ')
    val blackStarts = fields.getOrNull(1) == "b"
    val fullMove = fields.getOrNull(5)?.toIntOrNull()?.coerceAtLeast(1) ?: 1
    val offset = (step - 1).coerceAtLeast(0) + if (blackStarts) 1 else 0
    return WalkthroughMove(number = fullMove + offset / 2, isWhite = offset % 2 == 0, san = san)
}

/** The main button advances until the last step, where it reads "Done" and leaves. */
fun walkthroughButtonIsDone(step: Int, totalPlies: Int): Boolean = step >= totalPlies

/** One press of "Next": the following step, never past the last one. */
fun walkthroughNextStep(step: Int, totalPlies: Int): Int = (step + 1).coerceAtMost(totalPlies)
