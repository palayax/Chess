package net.palaya.chessanalyzer.ui.model

/*
 * Per-game analysis strength (A4). Settings › Analysis strength is the DEFAULT for a game analysed for the
 * first time; a game keeps the strength it was analysed with (`GameRepository.StoredGame.depth` + `.autoDepth`), and its
 * Summary can re-analyse it with another one. The rules are here, pure, so each has a host test.
 *
 * Reference (owner's rule, recorded in RUN_LOG A4): chess.com's Game Review lets the player choose how deep
 * the engine looks and run the review again from the review itself; the choice belongs to that review, not
 * only to the global settings. Taken as a pattern: a short list of named strengths (not a depth slider),
 * the current one marked, and an explicit "Re-analyse" button. Our own words and layout.
 */

/**
 * What a game is analysed with: a search [depth] (a preset's, or an old custom one) and whether Auto-depth deepens
 * its critical moves afterwards (ANALYSIS_SPEC §8.5; [depth] is then Auto's first pass). A depth alone cannot say
 * Auto, so this pair is what the request, the stored game and the chooser carry.
 */
data class AnalysisLevel(val depth: Int, val autoDepth: Boolean = false) {
    /** The named strength, or null for an old custom depth. */
    val strength: AnalysisStrength? get() = AnalysisStrength.of(depth, autoDepth)

    companion object {
        fun of(strength: AnalysisStrength): AnalysisLevel = AnalysisLevel(strength.depth, strength.autoDepth)
    }
}

/**
 * The level a game is analysed at, in order of authority: an explicit choice for this run (a re-analyse), the level
 * the game was last analysed with (reopening a game from Home: an Auto game stays Auto), and only then the Settings
 * default (a game analysed for the first time).
 */
fun gameAnalysisLevel(explicit: AnalysisLevel?, stored: AnalysisLevel?, settingsDefault: AnalysisLevel): AnalysisLevel =
    explicit ?: stored ?: settingsDefault

/** One line of the "Re-analyse" chooser. */
data class StrengthChoice(val strength: AnalysisStrength, val isCurrent: Boolean)

/** The four strengths, Quick, Standard, Deep, then Auto; the one [current] is marked (none for a custom depth). */
fun strengthChoices(current: AnalysisLevel): List<StrengthChoice> =
    AnalysisStrength.entries.map { StrengthChoice(it, isCurrent = it == current.strength) }

/**
 * Whether "Re-analyse" can be pressed: something is picked and it differs from the strength the game
 * already has. (The same strength would hit the eval cache and change nothing.)
 */
fun canReanalyse(current: AnalysisLevel, picked: AnalysisStrength?): Boolean =
    picked != null && picked != current.strength
