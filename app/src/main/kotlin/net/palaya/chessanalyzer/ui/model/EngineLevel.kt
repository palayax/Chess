package net.palaya.chessanalyzer.ui.model

/** Declared outside the enum: an entry cannot read its own companion while the enum is being built. */
private const val MAX_LEVEL_CODE = 4000

/**
 * The engine level of an analysis (docs/ANALYSIS_SPEC.md §8.6): roughly how strong a player the analysis is.
 * Replaces Quick / Standard / Deep / Auto for every NEW analysis (owner 2026-10-14: Auto took half an hour on a
 * Pixel 8; a review must finish in about ten minutes).
 *
 * The level is a per-position NODE budget; a game-wide time budget ([GameTimeBudget], spec §8.6) makes the engine
 * stage finish within [GAME_ENGINE_BUDGET_MS] on any phone. The Elo figures are an approximation, not a measurement.
 *
 * Stored as an Int [code] in the same place a legacy depth was stored (`StoredGame.depth`, the pending request,
 * `EngineSettings.depth`): 6..30 is a legacy fixed depth, [CODE_THRESHOLD] and above is a level code.
 */
enum class EngineLevel(val code: Int, val nodes: Long?) {
    L2500(2500, 20_000L),
    L2700(2700, 60_000L),
    L2900(2900, 180_000L),
    L3100(3100, 600_000L),

    /** No node budget: the game time budget alone decides how long each position gets. */
    MAX(MAX_LEVEL_CODE, null);

    /** The "about N" Elo for the label; null for [MAX]. */
    val elo: Int? get() = if (this == MAX) null else code

    val isMax: Boolean get() = this == MAX

    companion object {
        const val CODE_THRESHOLD = 1000
        const val MAX_CODE = MAX_LEVEL_CODE

        val DEFAULT = L3100

        /** Depth limit sent with every level search: effectively none (spec §8.6). */
        const val SEARCH_DEPTH = 60

        /** True for a stored value that is a level code, false for a legacy fixed depth (6..30). */
        fun isLevelCode(value: Int): Boolean = fromCode(value) != null

        fun fromCode(code: Int): EngineLevel? = entries.firstOrNull { it.code == code }

        /**
         * The level to PRE-SELECT for an old game (a re-analysis) or to migrate the Settings default from a legacy
         * depth: Quick (<= 12) -> 2700, Standard (13..14) -> 2900, Deep, Auto and deeper -> 3100.
         */
        fun forLegacyDepth(depth: Int, autoDepth: Boolean): EngineLevel = when {
            autoDepth -> L3100
            depth <= 12 -> L2700
            depth <= 14 -> L2900
            else -> L3100
        }

        /** The level a stored value means: its code, or the pre-selection for a legacy depth. */
        fun fromStored(depth: Int, autoDepth: Boolean = false): EngineLevel =
            fromCode(depth) ?: forLegacyDepth(depth, autoDepth)
    }
}

/**
 * The whole first pass (every position of the game, all levels) gets this much engine time at most
 * (spec §8.6). Together with the wording, narration and voice stages it keeps a review near ten minutes on a phone
 * like a Pixel 8.
 */
const val GAME_ENGINE_BUDGET_MS: Long = 300_000L

/**
 * The most one position can get at level Max (spec §8.6): Max has no node budget, so without a ceiling it always spent the
 * whole [GAME_ENGINE_BUDGET_MS], however short the game. With 8 s per position a 20-move game (41 positions) finishes in
 * about 5.5 minutes at the very most, and a short one early.
 */
const val MAX_LEVEL_MOVETIME_CEILING_MS: Long = 8_000L

/**
 * How the game's engine time is shared between its positions (spec §8.6), pure and host-tested.
 *
 * Each search gets the time left divided by the positions left, times [SLACK] (a position that stops early on its
 * node budget leaves time to the later ones, so the average stays within the total), never less than [minPerPositionMs]
 * and never more than the whole time left (when that is above the minimum). [ceilingMs] (level Max only,
 * [MAX_LEVEL_MOVETIME_CEILING_MS]) caps one search whatever its share is, so a short game finishes early.
 */
class GameTimeBudget(val totalMs: Long, val minPerPositionMs: Long = 250L, val ceilingMs: Long? = null) {

    fun movetimeFor(positionsLeft: Int, spentMs: Long): Long {
        val left = (totalMs - spentMs).coerceAtLeast(0L)
        val n = positionsLeft.coerceAtLeast(1)
        val share = (left.toDouble() / n * SLACK).toLong()
        val capped = if (n == 1) minOf(share, left) else share
        val wanted = maxOf(minPerPositionMs, capped)
        return if (ceilingMs == null) wanted else minOf(wanted, maxOf(ceilingMs, minPerPositionMs))
    }

    /** Time still available for the engine stage. */
    fun leftMs(spentMs: Long): Long = (totalMs - spentMs).coerceAtLeast(0L)

    companion object {
        const val SLACK = 1.5
    }
}
