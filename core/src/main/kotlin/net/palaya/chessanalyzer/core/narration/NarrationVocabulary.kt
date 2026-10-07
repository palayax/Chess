package net.palaya.chessanalyzer.core.narration

import net.palaya.chessanalyzer.core.analysis.ExchangeEvaluator
import net.palaya.chessanalyzer.core.analysis.TacticInstance
import net.palaya.chessanalyzer.core.chess.Color
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

    /**
     * Spoken size of a tactic's advertised centipawn swing. A unit is named only when the swing is
     * within [ExchangeEvaluator.GAIN_TOLERANCE_CP] of it (the rule of ANALYSIS_SPEC 6.1; before C1 "a
     * rook" was said for anything from 500 up, so +800 was "a rook"); a gain of two pawns or more
     * that is no whole unit is "serious material", one pawn or more "material", less "a better position".
     */
    fun materialPayoff(swing: Int): MaterialPayoff = when {
        unit(swing, 900) -> MaterialPayoff.WHOLE_QUEEN
        unit(swing, 500) -> MaterialPayoff.ROOK
        unit(swing, 325) -> MaterialPayoff.PIECE
        unit(swing, 100) -> MaterialPayoff.PAWN
        swing >= 200 -> MaterialPayoff.SERIOUS_MATERIAL
        swing >= 100 -> MaterialPayoff.MATERIAL
        else -> MaterialPayoff.BETTER_POSITION
    }

    /**
     * Material actually netted along a line, by unit, or null below a pawn. The same cut-offs as
     * `ExchangeEvaluator.describeGain` (a unit within 40 cp of its value, else no unit), kept here so
     * the narration never has to parse that function's English. Null for a gain of a pawn or more that
     * is no whole unit: the sentence then says "material".
     */
    fun materialGain(centipawns: Int): MaterialGain? = when {
        unit(centipawns, 900) -> MaterialGain.QUEEN
        unit(centipawns, 500) -> MaterialGain.ROOK
        unit(centipawns, 325) -> MaterialGain.PIECE
        unit(centipawns, 100) -> MaterialGain.PAWN
        else -> null
    }

    /**
     * What [winner] netted between two boards of a line, settled: [MaterialGain.EXCHANGE] when the line
     * gave a minor piece for a rook and nothing else, otherwise [materialGain] of the settled value.
     */
    fun materialGainAlong(from: Position, to: Position, winner: Color, settledCp: Int): MaterialGain? = when {
        settledCp < 100 -> null
        ExchangeEvaluator.winsTheExchange(from, ExchangeEvaluator.settledPosition(to, winner), winner) &&
            settledCp in ExchangeEvaluator.EXCHANGE_MIN_CP..ExchangeEvaluator.EXCHANGE_MAX_CP -> MaterialGain.EXCHANGE
        else -> materialGain(settledCp)
    }

    private fun unit(cp: Int, value: Int): Boolean = kotlin.math.abs(cp - value) <= ExchangeEvaluator.GAIN_TOLERANCE_CP

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
