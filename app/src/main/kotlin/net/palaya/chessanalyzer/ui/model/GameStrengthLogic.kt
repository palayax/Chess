package net.palaya.chessanalyzer.ui.model

/*
 * Per-game analysis strength (A4). Settings › Analysis strength is the DEFAULT for a game analysed for the
 * first time; a game keeps the strength it was analysed with (`GameRepository.StoredGame.depth`), and its
 * Summary can re-analyse it with another one. The rules are here, pure, so each has a host test.
 *
 * Reference (owner's rule, recorded in RUN_LOG A4): chess.com's Game Review lets the player choose how deep
 * the engine looks and run the review again from the review itself; the choice belongs to that review, not
 * only to the global settings. Taken as a pattern: a short list of named strengths (not a depth slider),
 * the current one marked, and an explicit "Re-analyse" button. Our own words and layout.
 */

/**
 * The depth a game is analysed at, in order of authority: an explicit choice for this run (a re-analyse),
 * the strength the game was last analysed with (reopening a game from Home), and only then the Settings
 * default (a game analysed for the first time).
 */
fun gameAnalysisDepth(explicit: Int?, stored: Int?, settingsDefault: Int): Int =
    explicit ?: stored ?: settingsDefault

/** One line of the "Re-analyse" chooser. */
data class StrengthChoice(val strength: AnalysisStrength, val isCurrent: Boolean)

/** The three strengths, Quick to Deep; the one [currentDepth] belongs to is marked (none for a custom depth). */
fun strengthChoices(currentDepth: Int): List<StrengthChoice> =
    AnalysisStrength.entries.map { StrengthChoice(it, isCurrent = it.depth == currentDepth) }

/**
 * Whether "Re-analyse" can be pressed: something is picked and it differs from the strength the game
 * already has. (The same strength would hit the eval cache and change nothing.)
 */
fun canReanalyse(currentDepth: Int, picked: AnalysisStrength?): Boolean =
    picked != null && picked.depth != currentDepth
