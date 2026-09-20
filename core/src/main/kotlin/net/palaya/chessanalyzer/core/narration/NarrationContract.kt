package net.palaya.chessanalyzer.core.narration

import net.palaya.chessanalyzer.core.analysis.MoveClassification
import net.palaya.chessanalyzer.core.analysis.TacticInstance
import net.palaya.chessanalyzer.core.chess.Color

/**
 * SHARED CONTRACT for the narrated game-review video. Owned by the orchestrator, read-only to
 * implementing agents.
 *
 * The product goal: a single, continuous, narrated walkthrough of the game in the style of a chess
 * YouTube explainer — it talks the viewer through what happened, why a move was bad, what was
 * missed, and what to learn — rendered over an animated board and exportable as an MP4.
 *
 * Two consumers, one script:
 *  - in-app playback (TTS speaks it live while the board animates)
 *  - video export (the same script drives frame rendering + an audio track)
 *
 * The script is produced **offline and deterministically** from a `GameReport`. No LLM at runtime.
 */

/** What the board should be doing while a segment is spoken. */
sealed interface BoardDirective {

    /** Hold the given position still. */
    data class Hold(val fen: String) : BoardDirective

    /** Play a single move from [fen], animating [uci]. */
    data class PlayMove(
        val fen: String,
        val uci: String,
        val san: String,
        val classification: MoveClassification? = null
    ) : BoardDirective

    /**
     * Play out a line from [fen] — used for "here is what you should have played".
     * The board must return to the main line afterwards; the renderer is responsible for
     * making the excursion visually distinct (e.g. a tinted border).
     */
    data class PlayLine(
        val fen: String,
        val uciMoves: List<String>,
        val sanMoves: List<String>,
        val label: String
    ) : BoardDirective

    /** Draw arrows/highlights on a static position to point at a motif. */
    data class Annotate(
        val fen: String,
        val arrows: List<ArrowSpec> = emptyList(),
        val highlightSquares: List<String> = emptyList()
    ) : BoardDirective

    /** A title/summary card with no board focus (intro, outro, chapter break). */
    data class Card(val heading: String, val lines: List<String>) : BoardDirective
}

/** An arrow to draw, in algebraic squares, with a semantic role that decides its colour. */
data class ArrowSpec(
    val fromSquare: String,
    val toSquare: String,
    val role: ArrowRole
)

enum class ArrowRole {
    /** The move actually played. */
    PLAYED,
    /** The engine's recommendation / what should have been played. */
    BEST,
    /** A threat or tactic aimed at the player. */
    THREAT,
    /** Supporting detail — a defender, a line, a square being covered. */
    SUPPORT
}

/** Why this segment exists — lets the UI chapter the video and the renderer pick a visual style. */
enum class SegmentKind {
    INTRO,
    OPENING_SUMMARY,
    NORMAL_MOVE,
    KEY_MOMENT,
    BLUNDER,
    MISSED_TACTIC,
    FOUND_TACTIC,
    THREAT_ALLOWED,
    /** "Pause here — can you see it?" — a deliberate beat before revealing a tactic. */
    PUZZLE_PROMPT,
    TURNING_POINT,
    OUTRO_SUMMARY,
    OUTRO_LESSONS
}

/**
 * One spoken beat of the video.
 *
 * @param narration what is spoken. Written to be *heard*, not read: short sentences, plain words,
 *   no notation the ear cannot parse. Say "knight takes e5", never "Nxe5". Say "white's queen",
 *   not "Qd1". This is the single most important quality constraint in the whole feature.
 * @param caption a short on-screen line (may use real notation, since it is read not heard).
 * @param board what the board does during this segment.
 * @param estimatedSpeechMs a duration estimate used for layout before TTS runs; the real duration
 *   replaces it once the speech is synthesised.
 * @param holdAfterMs extra silent beat after speaking — used for PUZZLE_PROMPT pauses.
 */
data class ScriptSegment(
    val index: Int,
    val kind: SegmentKind,
    val ply: Int?,
    val narration: String,
    val caption: String,
    val board: BoardDirective,
    val estimatedSpeechMs: Long,
    val holdAfterMs: Long = 0L,
    val tactic: TacticInstance? = null,
    val speakerColor: Color? = null,
    /** Evaluation to show on the eval bar while this segment plays; null for cards/intros. */
    val eval: SegmentEval? = null,
    /** Full-move number this segment refers to, for the side panel. */
    val moveNumber: Int? = null,
    /**
     * Signed, White-relative centipawn change the segment's move caused
     * (`evalAfterCp - evalBeforeCp`), for the side panel's "swing" readout. Null on cards, on
     * hypothetical-line beats (none of those positions occurred in the game, so no swing was
     * measured) and on any segment that is not about one specific played move.
     *
     * This is the quantity [NarrationOptions.significanceThresholdCp] filters on, which is the
     * reason it is shown: the viewer can see why a move earned its beat.
     */
    val evalSwingCp: Int? = null,
    /**
     * The spec's verdict on the one real move this segment is about (ANALYSIS_SPEC §4), or null
     * when the segment is about no single played move — cards, and the plies of a hypothetical
     * line, which were never played and so were never classified.
     *
     * [SegmentKind] answers a different question: *why this beat exists*. The two used to be
     * conflated on screen, because an error beat is emitted as [SegmentKind.BLUNDER] whether the
     * move was a MISTAKE, a MISS or a BLUNDER — so the video panel labelled a MISTAKE "Blunder"
     * while the review move list called the same ply "Mistake". Carrying the classification here
     * lets every consumer show the spec's verdict and keep the kind for what it actually means.
     */
    val classification: MoveClassification? = null
)

/** A chapter marker, for scrubbing in playback and for chapter markers in the exported file. */
data class ScriptChapter(val title: String, val startSegmentIndex: Int)

/**
 * Who played the game, for the video's side panel and title card.
 *
 * Added after the first video frames showed an under-used side panel: the renderer had nothing
 * real to display, and correctly refused to invent placeholder names rather than show fiction.
 */
data class VideoGameHeader(
    val whiteName: String,
    val blackName: String,
    val whiteRating: Int? = null,
    val blackRating: Int? = null,
    val result: String,
    val openingName: String? = null,
    val openingEco: String? = null,
    val dateText: String? = null
)

/**
 * The evaluation at a segment, for a continuously-visible eval bar.
 *
 * This is the single most valuable thing to show alongside the board in a review video: it lets a
 * viewer see the game swing without having to parse the position.
 *
 * @param winPercentWhite 0..100 from White's perspective, per ANALYSIS_SPEC §1.1.
 * @param evalCp centipawns, **White-relative** (display convention — see ANALYSIS_SPEC §1).
 * @param mateIn signed mate distance, White-relative, or null when the score is centipawns.
 */
data class SegmentEval(
    val winPercentWhite: Double,
    val evalCp: Int?,
    val mateIn: Int? = null
)

/**
 * A complete narrated review, ready to be played or rendered.
 *
 * @param totalEstimatedMs sum of segment durations plus holds — the projected video length.
 */
data class VideoScript(
    val title: String,
    val subtitle: String,
    val segments: List<ScriptSegment>,
    val chapters: List<ScriptChapter>,
    val totalEstimatedMs: Long,
    val userColor: Color?,
    /** Players, result and opening, for the title card and the side panel. */
    val header: VideoGameHeader? = null,
    /** Accuracy and estimated rating, for the outro card. */
    val whiteAccuracy: Double? = null,
    val blackAccuracy: Double? = null,
    val whiteEstimatedRating: Int? = null,
    val blackEstimatedRating: Int? = null
)

/**
 * How much of the game to narrate. Full games get long, so the default is highlights.
 *
 * This picks the *candidate* moves; [NarrationOptions.significanceThresholdCp] then prunes them.
 * The two compose rather than overriding each other — except for [EVERY_MOVE], which is the
 * explicit escape hatch (see its own doc).
 */
enum class NarrationDepth {
    /** Intro, every key moment and tactic, outro. Typically 2-5 minutes. */
    HIGHLIGHTS,

    /**
     * Every move gets at least a line. Long, but complete.
     *
     * **Not subject to [NarrationOptions.significanceThresholdCp].** Choosing this depth is an
     * explicit request for the complete walkthrough, and a significance filter quietly deleting
     * moves out of "every move" would make the option a lie. This is the way to switch the
     * threshold off for the whole review; setting the threshold to 0 does the same at any depth.
     */
    EVERY_MOVE,

    /** Only blunders, missed tactics and the turning point. Typically under 2 minutes. */
    MISTAKES_ONLY
}

/** Tone of the narration. */
enum class NarrationStyle {
    /** Energetic explainer, in the style of a chess YouTube channel. Default. */
    COACH,

    /** Neutral and factual. */
    ANALYST
}

data class NarrationOptions(
    val depth: NarrationDepth = NarrationDepth.HIGHLIGHTS,
    val style: NarrationStyle = NarrationStyle.COACH,
    /**
     * Words per minute assumed when estimating speech duration before TTS runs.
     *
     * The default describes the **device TTS voice** this estimator was originally calibrated
     * against; it is not a universal constant. Different voices speak at materially different
     * rates, and the estimate lays out the board animation before any audio exists, so a value
     * that does not describe the voice that will actually speak drifts picture away from sound
     * over a multi-minute review. The app passes the measured rate of the selected voice instead
     * — see `AnalysisViewModel.narrationOptionsForCurrentVoice()` and
     * `NeuralVoiceTier.measuredWpm`. `:core` has no way to know which voice that is, which is why
     * this stays a parameter rather than becoming a lookup.
     */
    val speechWpm: Int = 165,
    /** Include "can you spot it?" pauses before revealing missed tactics. */
    val includePuzzlePrompts: Boolean = true,
    /** What to call the user in narration ("you"), or null to use colour names throughout. */
    val addressUserAsYou: Boolean = true,
    /**
     * Grammatical gender of the viewer, for languages whose second-person verbs inflect for it
     * (Hebrew: "you played" differs for a man and a woman). English ignores it. Carried on the
     * [Subject] of every sentence about the viewer so a locale never has to guess; see
     * [NarrationStrings] for the contract.
     */
    val viewerGender: Gender = Gender.UNSPECIFIED,
    /**
     * How much a move (or a [net.palaya.chessanalyzer.core.analysis.MoveSequence]) has to change
     * the evaluation before it is worth narrating, in **centipawns**. Default 50 = half a pawn.
     *
     * **Semantics: the absolute eval swing the move caused.** A ply earns a spoken beat only when
     * `|evalAfterCp - evalBeforeCp| >= significanceThresholdCp`, where both values are the
     * White-relative centipawn scores ANALYSIS_SPEC §1 defines (so the subtraction is
     * perspective-free and the absolute value is the same number for either player). A ply also
     * survives when it belongs to a `MoveSequence` whose `totalSwingCp` clears the same bar, so a
     * combination that only pays off on its third move is not cut off at the knees.
     *
     * **Why the swing and not the position's own evaluation.** The request this implements was
     * "narrate moves scoring higher/lower than X, default ±0.5", which reads two ways. Filtering
     * on the *absolute evaluation of the position* would fire on essentially every move of any
     * decisive game — once somebody is a pawn up, every subsequent move is "above ±0.5" and the
     * filter selects the whole game. That is the exact opposite of the stated purpose, which was
     * to "focus on the important and relevant insights". Filtering on the *change* the move
     * caused selects the moments where something actually happened, which is what a review is
     * made of, and it degrades gracefully: a quiet game narrates its few real moments, a wild one
     * narrates more. Documented here rather than only in a commit message so the interpretation
     * is reviewable and can be reversed deliberately if the owner meant the other one.
     *
     * **Composition with [NarrationDepth].** Depth picks the candidate moves, this prunes them;
     * neither replaces the other. [NarrationDepth.EVERY_MOVE] is exempt, because that depth is an
     * explicit request for the complete walkthrough. Setting this to 0 disables pruning at any
     * depth.
     *
     * **Never produces an empty or broken review.** Structural beats (intro, opening summary,
     * chapter transitions, the outro summary with the final result, the lessons) are emitted
     * outside the per-move selection and are not filtered at all. If the threshold rejects every
     * single ply, the generator falls back to narrating the one ply with the largest swing, so
     * the review always has a body. Both behaviours are pinned by tests in
     * `NarrationSignificanceTest`.
     *
     * ANALYSIS_SPEC §9 is the authoritative statement of this rule.
     */
    val significanceThresholdCp: Int = DEFAULT_SIGNIFICANCE_THRESHOLD_CP
) {
    companion object {
        /**
         * Half a pawn. ANALYSIS_SPEC §9; also the default the Settings slider starts on.
         * Expressed in centipawns because that is the unit the analysis stores; the UI converts
         * to pawns with one decimal for display.
         */
        const val DEFAULT_SIGNIFICANCE_THRESHOLD_CP: Int = 50
    }
}
