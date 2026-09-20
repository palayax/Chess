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
     */
    data object Review : Destination("review/{gameId}?ply={ply}") {
        fun createRoute(gameId: String, ply: Int? = null) =
            if (ply != null) "review/$gameId?ply=$ply" else "review/$gameId"
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
    data object Settings : Destination("settings")
    /** The Google Cloud key setup wizard, reached only from Settings' Cloud voice section. */
    data object CloudVoiceSetup : Destination("cloud_voice_setup")
    data object About : Destination("about")

    companion object {
        const val ARG_GAME_ID = "gameId"
        const val ARG_PLY = "ply"
        const val ARG_TACTIC_TYPE = "tacticType"
    }
}
