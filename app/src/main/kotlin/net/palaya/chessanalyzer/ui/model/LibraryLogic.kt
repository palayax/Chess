package net.palaya.chessanalyzer.ui.model

/*
 * The library on Home (owner's phone test, 2026-10-11, item 1: "Can't delete existing games or re-analyze with
 * different settings"). Each row has a menu: "Re-analyse with different settings…" and "Delete". The bookkeeping of a
 * delete is here, pure, host-tested (LibraryLogicTest); the ViewModel applies it.
 *
 * Reference (owner's standing rule, recorded in RUN_LOG "RF"): chess.com's game archive keeps a per-game overflow
 * menu with the game's actions (analyse again, delete) and asks before deleting, naming the game. Taken as a pattern.
 */

/** What deleting one game must also clear. */
data class DeletePlan(
    /** The analysis request on disk (F1) belongs to this game: it must not resume a deleted game. */
    val clearPendingRequest: Boolean,
    /** The game waiting for setup (D2c) is this one: it must not be analysed once the net is in. */
    val clearWaitingGame: Boolean,
    /**
     * The game's engine evaluations can go: no other stored game has the same text (they share the cache key, so the
     * other game would lose its instant reopen). The narration audio is never deleted here: it is keyed by sentence and
     * shared across games, and Settings clears it.
     */
    val deleteEvalCache: Boolean,
)

fun deletePlan(
    gameId: String,
    pendingRequestGameId: String?,
    waitingGameId: String?,
    pgnText: String?,
    otherGamesPgnTexts: Collection<String>,
): DeletePlan = DeletePlan(
    clearPendingRequest = pendingRequestGameId == gameId,
    clearWaitingGame = waitingGameId == gameId,
    deleteEvalCache = !pgnText.isNullOrBlank() && pgnText !in otherGamesPgnTexts,
)

/**
 * True for a per-game cache key of [gameId] in the ViewModel (scripts `"$gameId:..."`, reworded scripts
 * `"rephrased:$gameId:..."`, practice sets `"$gameId:<side>"`). The colon after the id keeps "imported-1-5" from
 * matching "imported-1-50".
 */
fun isKeyOfGame(key: String, gameId: String): Boolean =
    key.startsWith("$gameId:") || key.startsWith("rephrased:$gameId:")

/** The library after [gameId] is deleted (newest first, as it was). */
fun libraryAfterDelete(games: List<RecentGameSummary>, gameId: String): List<RecentGameSummary> = games.filterNot { it.id == gameId }
