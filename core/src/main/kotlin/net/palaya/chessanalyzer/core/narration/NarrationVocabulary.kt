package net.palaya.chessanalyzer.core.narration

import net.palaya.chessanalyzer.core.analysis.TacticInstance
import net.palaya.chessanalyzer.core.chess.Position

/**
 * The thresholds that turn numbers into things a sentence can say — and nothing else. Every word
 * lives in a [NarrationStrings] implementation; this object only decides *which* [Standing],
 * [LossSeverity], [MaterialPayoff] or [OpeningFamily] the facts amount to, so the same cut-offs
 * drive every language.
 */
internal object NarrationVocabulary {

    /** Describes a win-percent from its owner's point of view. */
    fun standing(winPercent: Double): Standing = when {
        winPercent >= 95.0 -> Standing.COMPLETELY_WINNING
        winPercent >= 82.0 -> Standing.WINNING
        winPercent >= 68.0 -> Standing.CLEARLY_BETTER
        winPercent >= 57.0 -> Standing.A_LITTLE_BETTER
        winPercent >= 43.0 -> Standing.ABOUT_LEVEL
        winPercent >= 32.0 -> Standing.SLIGHTLY_WORSE
        winPercent >= 18.0 -> Standing.CLEARLY_WORSE
        winPercent >= 5.0 -> Standing.LOSING
        else -> Standing.COMPLETELY_LOST
    }

    /** How big a drop this was. */
    fun lossSeverity(loss: Double): LossSeverity = when {
        loss >= 50.0 -> LossSeverity.THE_WHOLE_GAME
        loss >= 30.0 -> LossSeverity.MOST_OF_THE_ADVANTAGE
        loss >= 18.0 -> LossSeverity.A_BIG_CHUNK
        loss >= 10.0 -> LossSeverity.REAL_GROUND
        else -> LossSeverity.A_LITTLE
    }

    /** Spoken size of a tactic's advertised centipawn swing. */
    fun materialPayoff(swing: Int): MaterialPayoff = when {
        swing >= 900 -> MaterialPayoff.WHOLE_QUEEN
        swing >= 500 -> MaterialPayoff.ROOK
        swing >= 320 -> MaterialPayoff.PIECE
        swing >= 200 -> MaterialPayoff.SERIOUS_MATERIAL
        swing >= 100 -> MaterialPayoff.PAWN
        else -> MaterialPayoff.BETTER_POSITION
    }

    /**
     * Material actually netted along a line, by unit, or null below a pawn. The same cut-offs as
     * `ExchangeEvaluator.describeGain`, kept here so the narration never has to parse that
     * function's English.
     */
    fun materialGain(centipawns: Int): MaterialGain? = when {
        centipawns >= 900 -> MaterialGain.QUEEN
        centipawns >= 500 -> MaterialGain.ROOK
        centipawns >= 300 -> MaterialGain.PIECE
        centipawns >= 100 -> MaterialGain.PAWN
        else -> null
    }

    /** The opening family a book name belongs to, or null when there is no plan blurb for it. */
    fun openingFamily(openingName: String?): OpeningFamily? {
        if (openingName == null) return null
        return OpeningFamily.entries.firstOrNull { family ->
            family.keywords.any { openingName.contains(it, ignoreCase = true) }
        }
    }

    /**
     * The facts of a motif for [Sentence.TacticPoint]. [positionBefore] is the position the motif
     * starts from, so the victim on the target square can be named by piece rather than by letter.
     */
    fun tacticPoint(tactic: TacticInstance, positionBefore: Position?): Sentence.TacticPoint {
        val target = tactic.targetSquares.firstOrNull() ?: tactic.involvedSquares.firstOrNull()
        return Sentence.TacticPoint(
            type = tactic.type,
            target = target,
            victim = target?.let { positionBefore?.pieceAt(it)?.type },
            second = tactic.targetSquares.getOrNull(1)
        )
    }
}

/**
 * Deterministic round-robin over a sentence's variants.
 *
 * Seeded from the report, so the same game always produces the same script, but consecutive uses
 * of the same sentence step forward — which is what keeps thirty segments from all opening with
 * the same two words. Pools are keyed by [Sentence.poolKey], so the rotation adapts to however
 * many variants a locale offers.
 */
internal class PhrasePicker(private val seed: Long) {

    private val cursors = HashMap<String, Int>()

    fun pick(pool: String, options: List<String>): String {
        if (options.isEmpty()) return ""
        // A locale may return a different number of variants for two instances of the same
        // sentence, so a stored cursor is taken modulo the size it is being applied to.
        val start = (cursors[pool] ?: startingCursor(pool, options.size)) % options.size
        cursors[pool] = (start + 1) % options.size
        return options[start]
    }

    private fun startingCursor(pool: String, size: Int): Int {
        val raw = ((seed xor pool.hashCode().toLong()) % size).toInt()
        return if (raw < 0) raw + size else raw
    }
}
