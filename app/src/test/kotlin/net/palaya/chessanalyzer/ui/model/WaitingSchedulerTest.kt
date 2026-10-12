package net.palaya.chessanalyzer.ui.model

import net.palaya.chessanalyzer.ui.components.WaitingPreviewContent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The rotation rules of the "While you wait" cards (WaitingScheduler.kt), with an explicit clock. */
class WaitingSchedulerTest {

    private fun fact(i: Int) = WaitingFact("f$i", "rules", "Fact $i")
    private fun player(i: Int) = WaitingPlayer("p$i", "Player $i", 1900, null, "Nowhere", null, "Text $i")
    private val gameRow = WaitingPreviewContent.content.games.first()
    private val puzzleRow = WaitingPreviewContent.content.puzzles.first()
    private fun game(i: Int) = gameRow.copy(game = gameRow.game.copy(id = "g$i"))
    private fun puzzle(i: Int) = puzzleRow.copy(id = "z$i")

    private val content = WaitingContent(
        facts = (1..10).map { fact(it) },
        players = (1..4).map { player(it) },
        games = (1..5).map { game(it) },
        puzzles = (1..6).map { puzzle(it) },
    )

    private fun drawAll(seed: Long, n: Int): List<String> {
        val deck = WaitingDeck(content, seed)
        return List(n) { deck.draw()!!.key }
    }

    @Test
    fun aRoundShowsEveryCardExactlyOnce() {
        val total = 10 + 4 + 5 + 6
        val round = drawAll(7, total)
        assertEquals(total, round.toSet().size)
    }

    @Test
    fun theOrderIsDeterministicPerSeedAndDiffersBetweenSeeds() {
        assertEquals(drawAll(1, 25), drawAll(1, 25))
        assertNotEquals(drawAll(1, 25), drawAll(2, 25))
    }

    @Test
    fun theKindsAreMixedFromTheStart() {
        val first = drawAll(3, 6)
        fun kinds(prefix: String) = first.count { it.startsWith(prefix) }
        assertEquals(2, kinds("fact-"))
        assertEquals(2, kinds("puzzle-"))
        assertEquals(1, kinds("game-"))
        assertEquals(1, kinds("player-"))
    }

    @Test
    fun aNewRoundStartsOnlyAfterEveryCardAndNeverRepeatsTheLastOne() {
        val total = 25
        val cards = drawAll(5, total * 2)
        // every card of the second round appears exactly once too
        assertEquals(total, cards.drop(total).toSet().size)
        assertNotEquals(cards[total - 1], cards[total])
    }

    @Test
    fun anEmptyDeckDrawsNothing() {
        assertNull(WaitingDeck(WaitingContent.EMPTY, 1).draw())
    }

    // ---- the panel controller ----

    private val sig = "a"

    private fun controller() = WaitingPanelController(content, seed = 11)

    @Test
    fun theInsightsComeFirst() {
        val c = controller()
        val s = c.start(nowMs = 0, signature = sig)
        assertEquals(WaitingCard.Insights(0), s.current)
    }

    @Test
    fun nothingRotatesBeforeTheDwellTimeAndSomethingDoesAfter() {
        val c = controller()
        var s = c.start(0, sig)
        s = c.tick(s, WaitingTiming.DWELL_TEXT_MS - 1, sig)
        assertEquals(1, s.history.size)
        s = c.tick(s, WaitingTiming.DWELL_TEXT_MS, sig)
        assertEquals(2, s.history.size)
        assertFalse(s.current is WaitingCard.Insights)
    }

    @Test
    fun insightsReturnOnlyWhenTheyChangedAndNeverTwiceInARow() {
        val c = controller()
        var s = c.start(0, "a")
        var now = 0L
        fun rotate(signature: String): WaitingCard {
            now += 60_000
            s = c.tick(s, now, signature)
            return s.current
        }
        // unchanged signature: only deck cards
        repeat(4) { assertFalse(rotate("a") is WaitingCard.Insights) }
        // changed: the insights card comes back (page 1) ...
        val back = rotate("b")
        assertEquals(WaitingCard.Insights(1), back)
        // ... and even if they change again right away, the next card is from the deck
        assertFalse(rotate("c") is WaitingCard.Insights)
        // and the change that was pending then brings them back again
        assertEquals(WaitingCard.Insights(2), rotate("d"))
    }

    @Test
    fun aTouchHoldsTheRotationForTwentyFiveSeconds() {
        val c = controller()
        var s = c.start(0, sig)
        s = c.touch(s, 10_000)
        assertFalse(c.mayRotate(s, 10_000 + WaitingTiming.TOUCH_HOLD_MS - 1, autoRotate = true))
        // dwell is long over, touch hold is the reason
        assertTrue(c.mayRotate(s, 10_000 + WaitingTiming.TOUCH_HOLD_MS, autoRotate = true))
    }

    @Test
    fun anEngagedPuzzleIsNeverRotatedAwayNoMatterHowLong() {
        val c = controller()
        var s = c.start(0, sig)
        // go to a puzzle card
        var now = 0L
        while (s.current !is WaitingCard.Puzzle) {
            now += 60_000
            s = c.next(s, now, sig)
        }
        s = c.setEngaged(s, true, now)
        assertFalse(c.mayRotate(s, now + 10 * 60_000, autoRotate = true))
        assertEquals(s.current, c.tick(s, now + 10 * 60_000, "other").current)
        // once finished, it may rotate after the touch hold and its dwell
        s = c.setEngaged(s, false, now + 1_000)
        assertFalse(c.mayRotate(s, now + 1_000 + WaitingTiming.TOUCH_HOLD_MS - 1, autoRotate = true))
        assertTrue(c.mayRotate(s, now + 2 * 60_000, autoRotate = true))
    }

    @Test
    fun anUntouchedPuzzleKeepsItsLongerDwell() {
        val c = controller()
        var s = c.start(0, sig)
        var now = 0L
        while (s.current !is WaitingCard.Puzzle) {
            now += 60_000
            s = c.next(s, now, sig)
        }
        assertFalse(c.mayRotate(s, now + WaitingTiming.DWELL_PUZZLE_MS - 1, autoRotate = true))
        assertTrue(c.mayRotate(s, now + WaitingTiming.DWELL_PUZZLE_MS, autoRotate = true))
    }

    @Test
    fun pauseAndAutoRotateOffStopTheRotation() {
        val c = controller()
        var s = c.start(0, sig)
        assertFalse(c.mayRotate(s, 10 * 60_000, autoRotate = false))
        s = c.setPaused(s, true, 0)
        assertFalse(c.mayRotate(s, 10 * 60_000, autoRotate = true))
        // resuming starts the card's dwell afresh
        s = c.setPaused(s, false, 100_000)
        assertFalse(c.mayRotate(s, 100_000 + WaitingTiming.DWELL_TEXT_MS - 1, autoRotate = true))
        assertTrue(c.mayRotate(s, 100_000 + WaitingTiming.DWELL_TEXT_MS, autoRotate = true))
    }

    @Test
    fun backAndForwardWalkTheHistoryAndAutoRotationWaitsAtTheEnd() {
        val c = controller()
        var s = c.start(0, sig)
        s = c.next(s, 1_000, sig)
        s = c.next(s, 2_000, sig)
        val third = s.current
        assertEquals(3, s.history.size)
        s = c.previous(s, 3_000)
        s = c.previous(s, 3_000)
        assertEquals(WaitingCard.Insights(0), s.current)
        assertFalse(s.canGoBack)
        assertEquals(s, c.previous(s, 4_000))
        // browsing back: no rotation, however long
        assertFalse(c.mayRotate(s, 99 * 60_000, autoRotate = true))
        s = c.next(s, 5_000, sig)
        s = c.next(s, 5_000, sig)
        assertEquals(third, s.current)
        assertEquals(3, s.history.size)
    }

    @Test
    fun withNoDeckOnlyInsightsAreShownAndNothingCrashes() {
        val c = WaitingPanelController(WaitingContent.EMPTY, 1)
        var s = c.start(0, sig)
        s = c.next(s, 1_000, sig)
        assertEquals(WaitingCard.Insights(1), s.current)
        assertFalse(c.hasDeck)
    }

    @Test
    fun aSessionNeverShowsACardTwiceBeforeAllHaveBeenShown() {
        val c = controller()
        var s = c.start(0, sig)
        var now = 0L
        repeat(25) {
            now += 60_000
            s = c.next(s, now, sig)
        }
        val deckKeys = s.history.filter { it !is WaitingCard.Insights }.map { it.key }
        assertEquals(deckKeys.size, deckKeys.toSet().size)
    }
}
