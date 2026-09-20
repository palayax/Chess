package net.palaya.chessanalyzer.core.analysis

import net.palaya.chessanalyzer.core.chess.Color
import net.palaya.chessanalyzer.core.chess.Position
import kotlin.math.abs
import kotlin.math.max

/**
 * The significance gate for detected tactics — ANALYSIS_SPEC §9.6.
 *
 * Detection is deliberately generous (a relative pin on move six is a relative pin), which is
 * right for the commentary and wrong for a report: one real game surfaced Deflection ×5, Relative
 * pin ×3 and Skewer ×3, most of which had no bearing on the result. The owner asked for tactics to
 * be gated by **the same mechanism as moves** — the centipawn swing threshold of §9.2 — and this is
 * that gate. It is a presentation layer: [MoveAnnotation.tacticsFound] / [MoveAnnotation.tacticsMissed]
 * keep the spec §5.4 buckets unchanged, and consumers call [prune] with the user's threshold.
 *
 * **What "swing" means for a tactic.** A *missed* tactic at ply p is judged by the ply's own swing,
 * `|evalAfter − evalBefore|` — the player deviated from the tactic and the swing is what that cost,
 * exactly the number §9.2 uses. A *found* tactic cannot be judged the same way: the played move
 * **is** the engine's best move, so `evalBefore` already assumed it would be played and the swing is
 * ~0 by construction. Its significance is the counterfactual — what *not* finding it would have cost:
 * the margin between the engine's best and second-best line ([MoveAnnotation.evalSecondBestCp]),
 * or, when only one line was analysed, the eval change across the opponent's move that allowed it
 * plus the tactic itself. See [foundSwingCp].
 *
 * **The trap this is built around.** A naive swing gate deletes checkmates: a queen up, the mating
 * move produces almost no swing, so a plain `>= threshold` would discard the single most instructive
 * moment of the game. So a tactic that *is* the point of the game survives any threshold —
 * [isDecisiveFound] / [isDecisiveMissed] list exactly which: the checkmating move, any found tactic
 * played while the mover has a forced mate on the board (a quiet move completing the net included),
 * a BRILLIANT or GREAT move (the classifier already proved those significant — a sound sacrifice, or
 * the only move by a ten-point margin), a missed forced mate, and a MISS. Sequence membership
 * carries over from §9.2 as well, and a threshold of 0 disables the gate entirely.
 *
 * There is no "largest swing" fallback here, unlike §9.2's narration: an empty tactic bucket is an
 * honest answer ("nothing tactically decisive happened"), whereas a review with no body is broken.
 * The UI keeps the pruned-out motifs reachable behind a "minor tactics" disclosure instead.
 */
object TacticSignificance {

    /** `|evalAfter − evalBefore|`, both White-relative, so the number is perspective-free (§9.1). */
    fun swingCp(a: MoveAnnotation): Int = abs(a.evalAfterCp - a.evalBeforeCp)

    /** What missing the tactic cost: the ply's own swing. */
    fun missedSwingCp(a: MoveAnnotation): Int = swingCp(a)

    /**
     * What *not* finding the tactic would have cost: the best-vs-second-best margin when MultiPV
     * supplied one, else 0; and in either case at least the eval change across the two plies
     * (the opponent's move that allowed the tactic, then the tactic), so a MultiPV-1 analysis
     * still credits a fork that punished a blunder.
     */
    fun foundSwingCp(a: MoveAnnotation, previous: MoveAnnotation?): Int {
        val margin = a.evalSecondBestCp?.let { abs(a.evalBeforeCp - it) } ?: 0
        val pairSwing = abs(a.evalAfterCp - (previous?.evalBeforeCp ?: a.evalBeforeCp))
        return max(margin, pairSwing)
    }

    /** True when a found tactic on this ply is the point of the game and must survive any threshold. */
    fun isDecisiveFound(a: MoveAnnotation): Boolean =
        isCheckmatingMove(a) ||
            favoursMover(a.mateInAfter, a.color) ||
            a.classification == MoveClassification.BRILLIANT ||
            a.classification == MoveClassification.GREAT

    /** True when a missed tactic on this ply threw away a forced win and must survive any threshold. */
    fun isDecisiveMissed(a: MoveAnnotation): Boolean =
        favoursMover(a.mateInBefore, a.color) || a.classification == MoveClassification.MISS

    /**
     * Should [tactic], found on [a], be shown at [thresholdCp]? [sequences] are the §9.3 runs of
     * the *unpruned* annotations; a member of a run of this motif whose combined swing clears the
     * bar survives, as a move would.
     */
    fun keepsFound(
        a: MoveAnnotation,
        previous: MoveAnnotation?,
        tactic: TacticInstance,
        thresholdCp: Int,
        sequences: List<MoveSequence>
    ): Boolean {
        if (thresholdCp <= 0) return true
        // The swing (or the decisive rules) prove the MOVE mattered; the engine's confirmation
        // proves the MOTIF is what mattered. A forced recapture has an enormous MultiPV margin,
        // and a relative pin that merely rides on it — the engine never cashes it in — is exactly
        // the noise this gate exists to remove. Seen on the Opera Game: "Relative pin ×2, Skewer
        // ×3", all static pattern hits on forcing moves, before this rule.
        if (tactic.confidence < CONFIRMED_CONFIDENCE) return false
        if (isDecisiveFound(a)) return true
        if (foundSwingCp(a, previous) >= thresholdCp) return true
        return carriedBySequence(a, tactic, thresholdCp, sequences)
    }

    /**
     * `Contract.kt` / ANALYSIS_SPEC §5.3: the confidence a detector assigns when the engine PV
     * plays the exploiting move within four plies. Below it, a found motif is a static pattern
     * the engine did not use.
     */
    const val CONFIRMED_CONFIDENCE = 0.95

    fun keepsMissed(
        a: MoveAnnotation,
        tactic: TacticInstance,
        thresholdCp: Int,
        sequences: List<MoveSequence>
    ): Boolean {
        if (thresholdCp <= 0) return true
        if (isDecisiveMissed(a)) return true
        if (missedSwingCp(a) >= thresholdCp) return true
        return carriedBySequence(a, tactic, thresholdCp, sequences)
    }

    /**
     * The annotations with every tactic that fails the gate removed. The commentary [MoveAnnotation.text]
     * is untouched — it describes what the move did, which stays true — and a ply's [MoveAnnotation.simulation]
     * is kept only while at least one missed motif on that ply survives, so "Show me" never offers a
     * walkthrough of a tactic the report does not list.
     */
    fun prune(annotations: List<MoveAnnotation>, thresholdCp: Int): List<MoveAnnotation> {
        if (thresholdCp <= 0) return annotations
        val sequences = MoveSequenceDetector.detect(annotations)
        return annotations.mapIndexed { index, a ->
            val previous = annotations.getOrNull(index - 1)
            val found = a.tacticsFound.filter { keepsFound(a, previous, it, thresholdCp, sequences) }
            val missed = a.tacticsMissed.filter { keepsMissed(a, it, thresholdCp, sequences) }
            if (found.size == a.tacticsFound.size && missed.size == a.tacticsMissed.size) a
            else a.copy(
                tacticsFound = found,
                tacticsMissed = missed,
                simulation = a.simulation?.takeIf { missed.isNotEmpty() }
            )
        }
    }

    /**
     * The whole report through the gate: pruned annotations, and each [PlayerReport]'s tactic lists
     * rebuilt from them the way [GameAnalyzer] built the originals. Accuracy, ratings, counts and key
     * moments are not tactics and are not touched.
     */
    fun prune(report: GameReport, thresholdCp: Int): GameReport {
        if (thresholdCp <= 0) return report
        val annotations = prune(report.annotations, thresholdCp)
        fun rebuild(p: PlayerReport): PlayerReport {
            val own = annotations.filter { it.color == p.color }
            return p.copy(
                tacticsFound = own.flatMap { it.tacticsFound }.filter { it.byColor == p.color },
                tacticsMissed = own.flatMap { it.tacticsMissed }.filter { it.byColor == p.color }
            )
        }
        return report.copy(
            annotations = annotations,
            white = rebuild(report.white),
            black = rebuild(report.black)
        )
    }

    /** The motifs the gate removed, per ply — what a UI shows behind "minor tactics". */
    fun minor(annotations: List<MoveAnnotation>, thresholdCp: Int): List<MoveAnnotation> {
        val kept = prune(annotations, thresholdCp)
        return annotations.zip(kept).map { (raw, k) ->
            raw.copy(
                tacticsFound = raw.tacticsFound.filter { it !in k.tacticsFound },
                tacticsMissed = raw.tacticsMissed.filter { it !in k.tacticsMissed }
            )
        }
    }

    // -----------------------------------------------------------------------

    private fun carriedBySequence(
        a: MoveAnnotation,
        tactic: TacticInstance,
        thresholdCp: Int,
        sequences: List<MoveSequence>
    ): Boolean = sequences.any {
        a.ply in it && it.kind == MoveSequenceKind.TACTIC &&
            it.tacticType == tactic.type && it.byColor == tactic.byColor &&
            it.totalSwingCp >= thresholdCp
    }

    /** A White-relative mate distance that is a mate *for* [mover]. */
    private fun favoursMover(mateIn: Int?, mover: Color): Boolean =
        mateIn != null && mateIn != 0 && ((mover == Color.WHITE) == (mateIn > 0))

    /**
     * The move delivered mate. The SAN suffix is checked first because it is free; the position is
     * consulted as well because a PGN is allowed to omit the `#`.
     */
    private fun isCheckmatingMove(a: MoveAnnotation): Boolean {
        if (a.san.endsWith("#")) return true
        return try {
            Position.fromFen(a.fenAfter).isCheckmate()
        } catch (e: Exception) {
            false
        }
    }
}
