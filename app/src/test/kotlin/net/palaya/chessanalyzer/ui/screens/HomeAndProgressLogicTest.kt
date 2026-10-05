package net.palaya.chessanalyzer.ui.screens

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure logic behind Home (U3) and the progress screen (U4). */
class HomeAndProgressLogicTest {

    // ---- Recent-game date ----

    @Test
    fun pgnDateBecomesALocalizedMediumDate() {
        assertEquals("Mar 14, 2026", formatRecentGameDate("2026.03.14", Locale.US))
        assertEquals("14 Mar 2026", formatRecentGameDate("2026.03.14", Locale.UK))
    }

    @Test
    fun unknownPgnDatesAreDroppedNotShownRaw() {
        assertNull(formatRecentGameDate("????.??.??", Locale.US))
        assertNull(formatRecentGameDate("2026.??.??", Locale.US))
        assertNull(formatRecentGameDate("   ", Locale.US))
        assertNull(formatRecentGameDate("", Locale.US))
    }

    @Test
    fun impossibleDatesFallBackToTheirOwnText() {
        assertEquals("2026.13.45", formatRecentGameDate("2026.13.45", Locale.US))
    }

    // ---- Paste sheet ----

    @Test
    fun analyzeNeedsSomethingBesidesWhitespace() {
        assertFalse(isPasteSubmittable(""))
        assertFalse(isPasteSubmittable("  \n\t "))
        assertTrue(isPasteSubmittable("1. e4"))
        assertTrue(isPasteSubmittable("  [Event \"x\"]  "))
    }

    @Test
    fun clipboardButtonIgnoresAnEmptyOrBlankClipboard() {
        assertNull(clipboardPasteText(null))
        assertNull(clipboardPasteText(""))
        assertNull(clipboardPasteText("   \n"))
        assertEquals("1. e4 e5", clipboardPasteText("1. e4 e5"))
    }

    // ---- "Move N of M" ----

    @Test
    fun progressCountsWholeMovesToMatchHomesMoveCount() {
        // 33 plies = 34 positions; Home says "17 moves" for it, so progress must end on 17 of 17.
        assertEquals(1 to 17, wholeMoveCounter(1, 34))
        assertEquals(6 to 17, wholeMoveCounter(11, 34))
        assertEquals(17 to 17, wholeMoveCounter(34, 34))
    }

    @Test
    fun oddPlyCountNeverOvershoots() {
        // 34 plies = 35 positions: Home says (34+1)/2 = 17 moves; the last position must not read 18.
        assertEquals(17 to 17, wholeMoveCounter(35, 35))
        assertEquals(17 to 17, wholeMoveCounter(34, 35))
    }

    @Test
    fun degenerateCountsStaySane() {
        assertEquals(0 to 1, wholeMoveCounter(0, 0))
        assertEquals(1 to 1, wholeMoveCounter(5, 2))
        assertEquals(0 to 1, wholeMoveCounter(-3, 2))
    }
}
