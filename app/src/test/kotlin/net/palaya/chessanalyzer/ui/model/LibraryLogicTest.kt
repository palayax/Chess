package net.palaya.chessanalyzer.ui.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** RF: the bookkeeping of deleting a game from the library. */
class LibraryLogicTest {

    @Test
    fun aDeleteClearsThePendingAndWaitingRequestsOnlyWhenTheyAreThisGames() {
        val mine = deletePlan("g1", pendingRequestGameId = "g1", waitingGameId = "g1", pgnText = "1. e4 *", otherGamesPgnTexts = emptyList())
        assertTrue(mine.clearPendingRequest)
        assertTrue(mine.clearWaitingGame)
        val other = deletePlan("g1", pendingRequestGameId = "g2", waitingGameId = null, pgnText = "1. e4 *", otherGamesPgnTexts = emptyList())
        assertFalse(other.clearPendingRequest)
        assertFalse(other.clearWaitingGame)
    }

    @Test
    fun theEvalCacheGoesUnlessAnotherStoredGameHasTheSameText() {
        assertTrue(deletePlan("g1", null, null, "1. e4 *", listOf("1. d4 *")).deleteEvalCache)
        // Imported twice: both share the cache key, so the other one keeps its instant reopen.
        assertFalse(deletePlan("g1", null, null, "1. e4 *", listOf("1. e4 *")).deleteEvalCache)
        // Nothing stored under the id: nothing to delete.
        assertFalse(deletePlan("g1", null, null, null, emptyList()).deleteEvalCache)
    }

    @Test
    fun aGamesCacheKeysAreFoundWithoutMatchingALongerId() {
        assertTrue(isKeyOfGame("imported-1-5:en:opts", "imported-1-5"))
        assertTrue(isKeyOfGame("rephrased:imported-1-5:opts:id:1", "imported-1-5"))
        assertTrue(isKeyOfGame("imported-1-5:WHITE", "imported-1-5"))
        assertFalse(isKeyOfGame("imported-1-50:en:opts", "imported-1-5"))
        assertFalse(isKeyOfGame("rephrased:imported-1-50:opts", "imported-1-5"))
    }

    @Test
    fun theLibraryLosesOnlyTheDeletedRowAndKeepsItsOrder() {
        val rows = listOf("c", "b", "a").map { RecentGameSummary(it, "W", "B", "*", "", 10) }
        assertEquals(listOf("c", "a"), libraryAfterDelete(rows, "b").map { it.id })
        assertEquals(rows, libraryAfterDelete(rows, "missing"))
    }
}
