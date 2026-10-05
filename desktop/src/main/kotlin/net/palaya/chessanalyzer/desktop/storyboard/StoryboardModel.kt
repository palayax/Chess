package net.palaya.chessanalyzer.desktop.storyboard

import kotlinx.serialization.Serializable

// storyboard.json ("palaya.storyboard/1", docs/PC_PRODUCER_DESIGN.md §3.2), Director v0 subset.
//
// v0 is a thin adapter over core's VideoScriptGenerator: one beat per ScriptSegment, one board cue
// per beat (the segment's BoardDirective). The richer P2 fields (tiers, facts, placeholders, word
// budgets, sfx, music) are absent until the Director v1 produces them.

@Serializable
data class Storyboard(
    val schema: String = SCHEMA,
    val gameId: String,
    val lang: String,
    val director: String,
    val header: StoryHeader,
    /** "WHITE"/"BLACK" when --user matched a player (board orientation, second person), else null. */
    val userColor: String?,
    val chapters: List<StoryChapter>,
    val beats: List<Beat>,
    /** Sum of the generator's per-segment estimates plus holds; the real length is known after AUDIO. */
    val estimatedSeconds: Double,
    val summary: StorySummary,
) {
    companion object {
        const val SCHEMA = "palaya.storyboard/1"
    }
}

@Serializable
data class StoryHeader(
    val white: String,
    val black: String,
    val whiteElo: Int?,
    val blackElo: Int?,
    val result: String,
    val opening: String?,
    val eco: String?,
    val date: String?,
)

@Serializable
data class StorySummary(
    val whiteAccuracy: Double?,
    val blackAccuracy: Double?,
    val whiteEstimatedRating: Int?,
    val blackEstimatedRating: Int?,
)

@Serializable
data class StoryChapter(val title: String, val firstBeat: String)

@Serializable
data class Beat(
    val id: String,
    /** core SegmentKind name (INTRO, BLUNDER, MISSED_TACTIC, PUZZLE_PROMPT, ...). */
    val kind: String,
    val ply: Int?,
    val moveNumber: Int?,
    /** Whose move / whose perspective the beat is about. */
    val color: String?,
    /** The spec's verdict on the played move, when the beat is about one. */
    val classification: String?,
    /** On-screen text from the generator (may contain SAN; never spoken). */
    val caption: String,
    /** The templated narration (§5.6): what `--no-llm` speaks. Notation-free. */
    val fallbackText: String,
    val estimatedMs: Long,
    /** Silent hold after the speech (find-the-move pauses). */
    val holdAfterMs: Long,
    val eval: BeatEval?,
    val evalSwingCp: Int?,
    val tactic: String?,
    /** True inside a missed-tactic detour: the renderer tints the board. */
    val excursion: Boolean,
    val board: List<BoardCue>,
)

@Serializable
data class BeatEval(val winPercentWhite: Double, val cp: Int?, val mateIn: Int?)

/**
 * One board instruction. [cue] is one of `hold`, `annotate`, `play_move`, `play_line`, `card`.
 *
 * @param fen the position the cue starts from.
 * @param uci/san the move(s) to animate (`play_move`: one; `play_line`: several).
 * @param lastMove UCI of the game move that led to [fen], for the last-move highlight on static
 *   cues; null when [fen] is not a game position (the end of a variation).
 */
@Serializable
data class BoardCue(
    val cue: String,
    val at: String = "start",
    val fen: String? = null,
    val uci: List<String> = emptyList(),
    val san: List<String> = emptyList(),
    val label: String? = null,
    val arrows: List<ArrowCue> = emptyList(),
    val highlights: List<String> = emptyList(),
    val lastMove: String? = null,
    val heading: String? = null,
    val lines: List<String> = emptyList(),
)

/** role: PLAYED, BEST, THREAT, SUPPORT (core ArrowRole). */
@Serializable
data class ArrowCue(val from: String, val to: String, val role: String)
