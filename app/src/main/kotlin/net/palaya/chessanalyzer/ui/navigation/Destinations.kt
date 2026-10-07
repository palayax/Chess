package net.palaya.chessanalyzer.ui.navigation

/**
 * Top-level navigation routes. `gameId` is threaded through as a nav arg so screens look
 * games/reports up from [net.palaya.chessanalyzer.ui.viewmodel.AnalysisViewModel] rather than
 * receiving them as constructor arguments.
 */
sealed class Destination(val route: String) {
    data object Import : Destination("import")
    data object AnalysisProgress : Destination("analysis_progress/{gameId}") {
        fun createRoute(gameId: String) = "analysis_progress/$gameId"
    }
    /**
     * The review screen, optionally opened at a specific ply.
     *
     * The ply argument exists because the Game Report's key moments and tactic entries are only
     * useful if tapping one takes you to the position it is talking about. Without it they all
     * opened the review at the start of the game, which looked like a working link but was not.
     * `line=true` opens it in that ply's best-line mode (V2: the Summary's "Show the best line").
     */
    data object Review : Destination("review/{gameId}?ply={ply}&line={line}") {
        fun createRoute(gameId: String, ply: Int? = null, bestLine: Boolean = false) = when {
            ply != null && bestLine -> "review/$gameId?ply=$ply&line=true"
            ply != null -> "review/$gameId?ply=$ply"
            else -> "review/$gameId"
        }
    }
    data object GameReport : Destination("game_report/{gameId}") {
        fun createRoute(gameId: String) = "game_report/$gameId"
    }
    data object Simulation : Destination("simulation/{gameId}/{ply}") {
        fun createRoute(gameId: String, ply: Int) = "simulation/$gameId/$ply"
    }
    /**
     * A textbook example of one tactic pattern (ANALYSIS_SPEC §10), played through the same
     * simulation screen as a missed tactic. Keyed on the `TacticType` name, not on a game: the
     * example is the same whichever game it was reached from.
     */
    data object Reference : Destination("reference/{tacticType}") {
        fun createRoute(type: net.palaya.chessanalyzer.core.analysis.TacticType) = "reference/${type.name}"
    }
    data object Video : Destination("video/{gameId}") {
        fun createRoute(gameId: String) = "video/$gameId"
    }
    /**
     * Practise your own mistakes (docs/PRACTICE_DESIGN.md §5), a leaf of the Summary. The optional
     * ply (-1 = none) opens it at that position: a Summary key-moment card's "Try it" and the
     * Walkthrough's "Try it yourself" pass it, and the Summary's own row does not, so it opens at
     * the first unsolved puzzle.
     */
    data object Practice : Destination("practice/{gameId}?ply={ply}") {
        fun createRoute(gameId: String, ply: Int? = null) =
            if (ply != null) "practice/$gameId?ply=$ply" else "practice/$gameId"
    }
    /**
     * First-run setup: the one-time download of the engine data and the voice (D2c,
     * docs/MODEL_DOWNLOAD_DESIGN.md §1). The start destination while the net is missing. A game shared
     * before setup waits on disk (`PendingAnalysisStore.WAITING_FOR_SETUP_FILE_NAME`), not in the route,
     * so it survives "Not now" and a killed process.
     */
    data object Setup : Destination("setup")
    /** The built-in famous-games library (G1, docs/FAMOUS_GAMES.md), opened from Home. */
    data object FamousGames : Destination("famous_games")
    data object Settings : Destination("settings")
    data object About : Destination("about")

    companion object {
        const val ARG_GAME_ID = "gameId"
        const val ARG_PLY = "ply"
        const val ARG_LINE = "line"
        const val ARG_TACTIC_TYPE = "tacticType"
    }
}
