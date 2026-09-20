package net.palaya.chessanalyzer.core.analysis

import net.palaya.chessanalyzer.core.chess.Position
import net.palaya.chessanalyzer.core.chess.parseSan

/**
 * One canonical teaching position for a [TacticType]: a legal FEN, the solution line in SAN, and
 * the one sentence a learner should take away.
 *
 * Every entry is machine-verified before it ships — see `scripts/verify_tactic_references.py`,
 * which checks legality, the pattern's own structural claim, and (for combinations) soundness by
 * search, and only then regenerates [TacticReferenceCorpus] and `fixtures/tactic_references.json`.
 * `TacticReferenceCorpusTest` replays the same lines through `:core`'s own move generator so two
 * independent engines have to agree before a position reaches a user.
 */
data class TacticReference(
    val type: TacticType,
    val fen: String,
    val solutionSan: List<String>,
    val teachingPoint: String
)

/**
 * The reference library behind "see this pattern done cleanly" — ANALYSIS_SPEC §10.
 *
 * A player's own missed fork is a fork buried in a messy position; the reference is the same idea
 * with nothing else on the board. It is surfaced *from* a real occurrence (a tactic in the report, a
 * missed-tactic walkthrough that just finished) rather than as a lesson of its own, and it is played
 * through the exact same [TacticSimulation] machinery as the missed-tactic "Show me" flow — one
 * board, one stepper, one explanation card — so learning a pattern and reviewing a miss feel like
 * the same thing.
 */
object TacticReferenceLibrary {

    val all: List<TacticReference> get() = TacticReferenceCorpus.entries

    fun forType(type: TacticType): TacticReference? = all.firstOrNull { it.type == type }

    fun hasReference(type: TacticType): Boolean = forType(type) != null

    /**
     * The reference for [type] as a [TacticSimulation], or null when the library has no entry for
     * it. The line is played in full — a reference is never truncated at "payoff realised" the way a
     * game excursion is, because the whole line *is* the lesson (a windmill is nine plies long on
     * purpose). Explanations and the payoff come from [SimulationBuilder], read off the board, so
     * they cannot disagree with what the learner is watching.
     */
    fun simulation(type: TacticType, builder: SimulationBuilder = SimulationBuilder()): TacticSimulation? {
        val reference = forType(type) ?: return null
        val start = Position.fromFen(reference.fen)
        val uci = ArrayList<String>(reference.solutionSan.size)
        var pos = start
        for (san in reference.solutionSan) {
            val move = pos.parseSan(san)
            uci.add(move.toUci())
            pos = pos.makeMove(move)
        }
        val instance = TacticInstance(
            type = type,
            byColor = start.sideToMove,
            moveUci = uci.first(),
            description = reference.teachingPoint,
            confidence = 1.0
        )
        return builder.build(
            startPosition = start,
            pvUci = uci,
            tactic = instance,
            maxPlies = uci.size,
            truncateAtPayoff = false,
            payoffOverride = payoffOf(type)
        )
    }

    /**
     * What a one-move reference collects *after* its line ends — the verifier's structural check
     * for the pattern is exactly the claim made here (a skewer has a valuable piece behind the
     * king; a trapped piece has no safe square; a fork hits two valuable pieces). Used only when
     * the line itself neither mates nor nets material, so a reference is never described with
     * the game excursion's non-committal "keeps the king under fire".
     */
    private fun payoffOf(type: TacticType): String = when (type) {
        TacticType.FORK, TacticType.PAWN_FORK, TacticType.DOUBLE_ATTACK -> "wins material next move"
        TacticType.PIN_ABSOLUTE, TacticType.PIN_RELATIVE -> "leaves the pinned piece frozen"
        TacticType.SKEWER -> "wins the piece behind the king"
        TacticType.DISCOVERED_ATTACK, TacticType.DISCOVERED_CHECK, TacticType.DOUBLE_CHECK -> "wins the exposed piece"
        TacticType.TRAPPED_PIECE -> "wins the trapped piece"
        TacticType.HANGING_PIECE -> "wins the loose piece"
        TacticType.PROMOTION_TACTIC, TacticType.UNDERPROMOTION -> "gets a new piece on the board"
        TacticType.GREEK_GIFT -> "has the king out in the open with mate threatened"
        TacticType.PERPETUAL_CHECK -> "forces a draw by repetition"
        TacticType.STALEMATE_TRICK -> "sets up a stalemate the opponent cannot avoid"
        TacticType.DESPERADO -> "gets the most out of a piece that was lost anyway"
        TacticType.PASSED_PAWN_BREAKTHROUGH -> "creates a passed pawn nobody can catch"
        else -> "gains a decisive advantage"
    }
}
