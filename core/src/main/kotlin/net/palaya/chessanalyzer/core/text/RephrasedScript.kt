package net.palaya.chessanalyzer.core.text

import net.palaya.chessanalyzer.core.analysis.GameReport
import net.palaya.chessanalyzer.core.narration.ScriptSegment
import net.palaya.chessanalyzer.core.narration.SegmentKind
import net.palaya.chessanalyzer.core.narration.VideoScript
import net.palaya.chessanalyzer.core.narration.VideoScriptGenerator

/**
 * The narration post-pass (docs/LLM_REPHRASE_DESIGN.md §6.2), a pure function over a finished, paced
 * [VideoScript]: it swaps in accepted rewordings and nothing else. Segment count, kinds, boards, lead-ins,
 * holds, best lines, evals and captions are untouched; only `narration` and its `estimatedSpeechMs` change,
 * so the V3 pace and the §9.7 budget logic are not re-run.
 *
 * Which beats may be reworded is an **allowlist** of the prose kinds; a kind not on it (a V4 addition, say)
 * keeps its words. A beat whose narration equals its caption, or that is blank, is never touched.
 */
object RephrasedScript {

    /** The prose beats. Anything else (including kinds added later) is denied by default. */
    val ALLOWED_KINDS: Set<SegmentKind> = setOf(
        SegmentKind.INTRO, SegmentKind.OPENING_SUMMARY, SegmentKind.NORMAL_MOVE, SegmentKind.KEY_MOMENT,
        SegmentKind.BLUNDER, SegmentKind.MISSED_TACTIC, SegmentKind.FOUND_TACTIC, SegmentKind.THREAT_ALLOWED,
        SegmentKind.PUZZLE_PROMPT, SegmentKind.TURNING_POINT, SegmentKind.OUTRO_SUMMARY, SegmentKind.OUTRO_LESSONS,
    )

    /** The story may grow by at most this factor over the original's speech (§6.2, the script-level cap). */
    const val MAX_GROWTH = 1.10

    fun eligible(segment: ScriptSegment): Boolean =
        segment.kind in ALLOWED_KINDS && segment.narration.isNotBlank() && segment.narration != segment.caption

    /** The beat texts a rephrase job should send, in script order, each once. */
    fun beats(script: VideoScript): List<String> =
        script.segments.filter(::eligible).map { it.narration }.distinct()

    /**
     * [script] with every eligible beat whose narration is a key of [accepted] replaced by its value and
     * its speech re-estimated at [wpm]. Then the cap: the summed speech may grow by at most [MAX_GROWTH], and
     * the story may not pass [budgetMs] (or the original story, if that was already over the budget, as
     * EVERY_MOVE may be); while either fails, the beat with the largest growth goes back to its original
     * words (ties: the earlier beat), deterministically.
     */
    fun apply(script: VideoScript, accepted: Map<String, String>, wpm: Int, budgetMs: Long): VideoScript {
        if (accepted.isEmpty()) return script
        val newEstimate = HashMap<Int, Long>()
        val newText = HashMap<Int, String>()
        for ((i, s) in script.segments.withIndex()) {
            if (!eligible(s)) continue
            val text = accepted[s.narration] ?: continue
            if (text.isBlank() || text == s.narration) continue
            newText[i] = text
            newEstimate[i] = VideoScriptGenerator.estimateSpeechMs(text, wpm)
        }
        if (newText.isEmpty()) return script

        val originalSpeech = script.segments.sumOf { it.estimatedSpeechMs }
        val speechCap = (originalSpeech * MAX_GROWTH).toLong()
        val storyCap = maxOf(budgetMs, script.storyMs)
        fun delta(i: Int) = newEstimate.getValue(i) - script.segments[i].estimatedSpeechMs
        fun total() = newEstimate.keys.sumOf { delta(it) }
        while (newText.isNotEmpty() && (originalSpeech + total() > speechCap || script.storyMs + total() > storyCap)) {
            val worst = newText.keys.sortedWith(compareByDescending<Int> { delta(it) }.thenBy { it }).first()
            newText.remove(worst)
            newEstimate.remove(worst)
        }
        if (newText.isEmpty()) return script

        val growth = total()
        val segments = script.segments.mapIndexed { i, s ->
            val text = newText[i] ?: return@mapIndexed s
            s.copy(narration = text, estimatedSpeechMs = newEstimate.getValue(i))
        }
        return script.copy(segments = segments, totalEstimatedMs = script.totalEstimatedMs + growth)
    }
}

/**
 * The card-text counterpart (§6.2): a [GameReport] with every annotation text (and the key moment summary
 * that repeats it) that is a key of [accepted] replaced. Nothing else changes; applying an empty map is the
 * identity, so "setting off" shows the originals at once.
 */
object RephrasedReport {
    fun apply(report: GameReport, accepted: Map<String, String>): GameReport {
        if (accepted.isEmpty()) return report
        val annotations = report.annotations.map { a -> accepted[a.text]?.let { a.copy(text = it) } ?: a }
        val keyMoments = report.keyMoments.map { k -> accepted[k.summary]?.let { k.copy(summary = it) } ?: k }
        return report.copy(annotations = annotations, keyMoments = keyMoments)
    }
}
