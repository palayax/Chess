package net.palaya.chessanalyzer.core.analysis

import kotlin.math.abs

/**
 * The one place that turns an evaluation into text.
 *
 * Before this existed the same four lines were copy-pasted into `ui/components/EvalBar.kt` and
 * `video/BoardFrameRenderer.kt`, which is exactly how a UI ends up showing `+0.9` in one place
 * and `0.9` in another for the same position. Both now call in here, and so do the per-move
 * readouts added for the move list and the video side panel.
 *
 * **Perspective.** Every centipawn value passed in is **White-relative** (positive = White is
 * better), per ANALYSIS_SPEC §1 — the same convention [MoveAnnotation.evalBeforeCp] /
 * [MoveAnnotation.evalAfterCp] and [GameReport.evalGraph] already store. The sign is always
 * printed for a non-negative score so a reader never has to guess whether an unsigned number
 * means "White by 0.9" or "0.9 for whoever just moved".
 *
 * Lives in `:core` rather than `:app` so it is host-testable and so the Android renderer (which
 * must not depend on Compose) and the Compose UI cannot drift apart.
 */
object EvalFormat {

    /**
     * A position's score, e.g. `+0.9`, `-1.4`, `M3`, `-M2`.
     *
     * [mateIn] wins when set: a mate must never be rendered through the centipawn path, because
     * [WinProbability.cpFromMate] saturates it to ~10000cp and the reader would see `+99.5`
     * instead of `M3`. The mate string carries an explicit `-` when it is Black who mates, for
     * the same reason the centipawn string carries an explicit `+`. `mate 0` — the position is
     * already checkmate — renders as `#`.
     */
    fun score(cp: Int?, mateIn: Int? = null): String = when {
        mateIn != null -> mateText(mateIn)
        cp != null -> pawns(cp)
        else -> "—"
    }

    /**
     * How much a move moved the evaluation, signed from White's point of view:
     * `+1.2` means the move gained White 1.2 pawns, `-3.0` means it cost White 3 pawns.
     *
     * This is the quantity [net.palaya.chessanalyzer.core.narration.NarrationOptions
     * .significanceThresholdCp] is compared against (as an absolute value), so it is deliberately
     * rendered in the same units the setting is expressed in.
     */
    fun swing(deltaCp: Int): String = pawns(deltaCp)

    /** `+0.9` / `-1.4` / `0.0`. One decimal, sign always shown for values >= 0. */
    private fun pawns(cp: Int): String {
        val value = cp / 100.0
        // -0.04 would otherwise print as "-0.0"; snap anything that rounds to zero to "0.0".
        val rounded = Math.round(value * 10.0) / 10.0
        if (rounded == 0.0) return "0.0"
        val sign = if (rounded > 0) "+" else ""
        // Locale.ROOT, not the platform default: a comma-decimal locale would otherwise render
        // "+0,9" in a UI whose every other number uses a point.
        return sign + String.format(java.util.Locale.ROOT, "%.1f", rounded)
    }

    /**
     * `M3` when White mates, `-M2` when Black does, `#` when the mate is already on the board.
     *
     * Zero is not a distance and has no side: the engine reports `mate 0` for a position that
     * *is* checkmate, and both "M0" (which the first version of this printed, and which reads as
     * a mate in no moves) and a signed "-M0" are nonsense to a reader. The final position of a
     * mated game is the most-looked-at frame of any review, so it gets the notation a chess
     * player already knows.
     */
    private fun mateText(mateIn: Int): String = when {
        mateIn == 0 -> "#"
        mateIn < 0 -> "-M${abs(mateIn)}"
        else -> "M${abs(mateIn)}"
    }
}
