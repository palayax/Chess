package net.palaya.chessanalyzer.core.analysis

import net.palaya.chessanalyzer.core.chess.Color
import kotlin.math.abs

/**
 * Runs of consecutive plies that belong together — a tactic that takes several moves to pay off,
 * or a stretch where one player falls apart. See ANALYSIS_SPEC §9.
 *
 * Why this exists: per-move badges are correct but they atomise the story. A three-move
 * combination shows up as three unrelated green circles, and a collapse shows up as three
 * unrelated red ones, when in both cases the interesting object is the *run*. Grouping is done
 * here, in `:core`, so the move list, the eval graph and the narration all draw the same runs
 * instead of each inventing its own.
 */
enum class MoveSequenceKind {
    /** Several consecutive plies executing (or missing) one and the same motif. */
    TACTIC,

    /** One player making mistakes on consecutive turns of their own. */
    COLLAPSE
}

/**
 * One grouped run, inclusive of both ends.
 *
 * @param startPly first ply of the run (1-based, as [MoveAnnotation.ply]).
 * @param endPly last ply of the run.
 * @param byColor whose run it is — the side executing the tactic, or the side collapsing.
 * @param classification the class the run as a whole should be coloured by (see
 *   [MoveSequenceDetector] for which member of the run supplies it).
 * @param totalSwingCp the absolute White-relative eval change across the whole run, i.e.
 *   `|evalAfter(endPly) - evalBefore(startPly)|`. This is the run's equivalent of a single
 *   move's swing, and is what the narration significance threshold is compared against.
 * @param label a short human label, e.g. "Fork" or "Collapse".
 */
data class MoveSequence(
    val startPly: Int,
    val endPly: Int,
    val byColor: Color,
    val kind: MoveSequenceKind,
    val classification: MoveClassification,
    val totalSwingCp: Int,
    val label: String,
    /** The motif a TACTIC run is about; null for a COLLAPSE. */
    val tacticType: TacticType? = null
) {
    val plies: IntRange get() = startPly..endPly

    operator fun contains(ply: Int): Boolean = ply in startPly..endPly
}

/**
 * Groups a game's annotations into [MoveSequence]s. Deterministic, no thresholds of its own
 * beyond the ones ANALYSIS_SPEC §9 names.
 *
 * Deliberately conservative: a run has to be at least two plies long and has to be *about* one
 * thing, otherwise every game would be one long sequence and the grouping would say nothing.
 * Overlapping candidates are resolved by preferring the longer run, then the earlier one, so a
 * ply belongs to at most one sequence and the UI never has to draw two bands over one move.
 */
object MoveSequenceDetector {

    /** ANALYSIS_SPEC §9: a run must span at least this many plies to be worth grouping. */
    const val MIN_SEQUENCE_PLIES = 2

    fun detect(annotations: List<MoveAnnotation>): List<MoveSequence> {
        if (annotations.size < MIN_SEQUENCE_PLIES) return emptyList()
        val candidates = tacticRuns(annotations) + collapseRuns(annotations)
        return resolveOverlaps(candidates)
    }

    /**
     * Consecutive plies that all carry a motif of the same [TacticType] for the same side, at or
     * above the confidence floor `Contract.kt` sets for a reportable motif (0.6). A motif counts
     * whether it was executed ([MoveAnnotation.tacticsFound]) or passed up
     * ([MoveAnnotation.tacticsMissed]) — a combination the player missed for three moves running
     * is exactly as much one story as one they found.
     */
    private fun tacticRuns(annotations: List<MoveAnnotation>): List<MoveSequence> {
        val runs = ArrayList<MoveSequence>()
        var i = 0
        while (i < annotations.size) {
            val motifs = motifsOf(annotations[i])
            if (motifs.isEmpty()) {
                i++
                continue
            }
            // A run is keyed on one (type, colour) pair; try each the first ply offers and keep
            // the longest, so "fork then fork then pin" groups as the two-ply fork run.
            var best: MoveSequence? = null
            var bestEndIndex = -1
            for (key in motifs) {
                var j = i
                while (j + 1 < annotations.size && key in motifsOf(annotations[j + 1])) j++
                if (j - i + 1 >= MIN_SEQUENCE_PLIES && j > bestEndIndex) {
                    best = buildRun(
                        annotations, i, j, key.second, MoveSequenceKind.TACTIC,
                        label = key.first.displayName, tacticType = key.first
                    )
                    bestEndIndex = j
                }
            }
            if (best != null) {
                runs.add(best)
                i = bestEndIndex + 1
            } else {
                i++
            }
        }
        return runs
    }

    private fun motifsOf(a: MoveAnnotation): Set<Pair<TacticType, Color>> =
        (a.tacticsFound + a.tacticsMissed)
            .filter { it.confidence >= REPORTABLE_CONFIDENCE }
            .map { it.type to it.byColor }
            .toSet()

    /**
     * Consecutive *turns of the same player* that are all mistakes. Plies alternate, so a
     * collapse at plies 11, 13, 15 is a run over plies 11..15 even though 12 and 14 (the
     * opponent's replies) sit inside it — the span is what the eye reads as "this is where it
     * fell apart", and clipping it to only the guilty plies would draw three disconnected marks
     * again, which is the thing this is here to fix.
     */
    private fun collapseRuns(annotations: List<MoveAnnotation>): List<MoveSequence> {
        val runs = ArrayList<MoveSequence>()
        for (color in listOf(Color.WHITE, Color.BLACK)) {
            val own = annotations.filter { it.color == color }
            var i = 0
            while (i < own.size) {
                if (!own[i].classification.isMistake) {
                    i++
                    continue
                }
                var j = i
                while (j + 1 < own.size &&
                    own[j + 1].classification.isMistake &&
                    own[j + 1].ply == own[j].ply + 2
                ) j++
                if (j - i + 1 >= MIN_SEQUENCE_PLIES) {
                    runs.add(
                        buildRun(
                            annotations, indexOfPly(annotations, own[i].ply), indexOfPly(annotations, own[j].ply),
                            color, MoveSequenceKind.COLLAPSE, label = "Collapse"
                        )
                    )
                }
                i = j + 1
            }
        }
        return runs
    }

    private fun indexOfPly(annotations: List<MoveAnnotation>, ply: Int): Int =
        annotations.indexOfFirst { it.ply == ply }

    private fun buildRun(
        annotations: List<MoveAnnotation>,
        fromIndex: Int,
        toIndex: Int,
        byColor: Color,
        kind: MoveSequenceKind,
        label: String,
        tacticType: TacticType? = null
    ): MoveSequence {
        val first = annotations[fromIndex]
        val last = annotations[toIndex]
        val members = annotations.subList(fromIndex, toIndex + 1).filter { it.color == byColor }
            .ifEmpty { annotations.subList(fromIndex, toIndex + 1) }
        // Colour the run by its most extreme member: the worst move of a collapse, the best move
        // of a combination. `MoveClassification`'s declaration order is severity order, so
        // max/min of the ordinal is exactly that.
        val classification = when (kind) {
            MoveSequenceKind.COLLAPSE -> members.maxByOrNull { it.classification.ordinal }!!.classification
            MoveSequenceKind.TACTIC -> members.minByOrNull { it.classification.ordinal }!!.classification
        }
        return MoveSequence(
            startPly = first.ply,
            endPly = last.ply,
            byColor = byColor,
            kind = kind,
            classification = classification,
            totalSwingCp = abs(last.evalAfterCp - first.evalBeforeCp),
            label = label,
            tacticType = tacticType
        )
    }

    /** Longest wins, then earliest; a ply belongs to at most one run. */
    private fun resolveOverlaps(candidates: List<MoveSequence>): List<MoveSequence> {
        val ordered = candidates.sortedWith(
            compareByDescending<MoveSequence> { it.endPly - it.startPly }.thenBy { it.startPly }
        )
        val taken = HashSet<Int>()
        val kept = ArrayList<MoveSequence>()
        for (c in ordered) {
            if (c.plies.any { it in taken }) continue
            kept.add(c)
            taken.addAll(c.plies)
        }
        return kept.sortedBy { it.startPly }
    }

    /** `Contract.kt`'s floor for a motif that may be reported at all. */
    private const val REPORTABLE_CONFIDENCE = 0.6
}
