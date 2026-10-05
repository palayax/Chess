package net.palaya.chessanalyzer.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WCAG 2.x AA for the dark theme's semantic roles (R6a): 4.5:1 for text, 3:1 for the borders and
 * glyphs that identify a control. Computed from the real values in Color.kt, so a palette edit that
 * breaks legibility fails here. The move-quality palette itself (Class*) is deliberately NOT
 * asserted against anything: it is owner-approved and unchanged; only what is drawn on it or in its
 * name is checked.
 */
class ThemeContrastTest {

    private val backgrounds = mapOf(
        "background" to ChromeDark,
        "surface" to SurfaceDark,
        "card" to ElevatedDark,
        "card highest" to ElevatedDarkHigh,
    )

    private fun assertAtLeast(minimum: Double, foreground: Color, background: Color, what: String) {
        val ratio = contrastRatio(foreground, background)
        assertTrue("$what is %.2f:1, needs $minimum:1".format(ratio), ratio >= minimum)
    }

    @Test
    fun primaryTextRolesReachAaOnEverySurface() {
        for ((name, bg) in backgrounds) {
            assertAtLeast(4.5, OnDarkPrimary, bg, "onSurface on $name")
            assertAtLeast(4.5, OnDarkSecondary, bg, "onSurfaceVariant on $name")
            assertAtLeast(4.5, ErrorText, bg, "error text on $name")
        }
    }

    @Test
    fun greenTextAndButtonsReachAa() {
        assertAtLeast(4.5, GreenPrimary, ChromeDark, "primary on background")
        assertAtLeast(4.5, GreenPrimary, ElevatedDark, "primary on card")
        assertAtLeast(4.5, Color(0xFF0B1A02), GreenPrimary, "label on a filled primary button")
        assertAtLeast(4.5, OnDarkPrimary, GreenContainer, "text on a selected chip or segment")
        assertAtLeast(4.5, OnDarkSecondary, GreenContainer.let { it }, "secondary text on a selected chip")
    }

    @Test
    fun controlBordersReachThreeToOne() {
        assertAtLeast(3.0, OutlineStrong, ChromeDark, "outline on background")
        assertAtLeast(3.0, OutlineStrong, ElevatedDark, "outline on card")
    }

    @Test
    fun secondaryTextStaysReadableOnTintedMoveChips() {
        // A highlight-tier chip is the class colour at 14% over a card.
        for (cls in MoveClassification.entries.filter { it.isHighlight }) {
            val chip = over(cls.color, ElevatedDark, 0.14f)
            assertAtLeast(4.5, OnDarkSecondary, chip, "score text on a ${cls.name} chip")
            assertAtLeast(4.5, OnDarkPrimary, chip, "move text on a ${cls.name} chip")
        }
    }

    @Test
    fun theBadgeGlyphReadsOnEveryClassColour() {
        val ink = Color(0xFF1A1917)
        for (cls in MoveClassification.entries) {
            assertAtLeast(4.5, ink, cls.color, "glyph ink on the ${cls.name} badge")
        }
    }

    @Test
    fun classNameTextIsLightenedOnlyEnoughToReachAa() {
        for (cls in MoveClassification.entries) {
            val text = legibleTextColor(cls.color, ElevatedDark)
            assertAtLeast(4.5, text, ElevatedDark, "${cls.name} label on a card")
        }
        // A colour that already passes is returned as it is (the palette is not touched needlessly).
        assertEquals(ClassBrilliant, legibleTextColor(ClassBrilliant, ElevatedDark))
        // BLUNDER red (3.2:1 on a card) is the one that has to move.
        assertTrue(legibleTextColor(ClassBlunder, ElevatedDark) != ClassBlunder)
    }

    @Test
    fun boardCoordinateLettersReadOnBothSquareColours() {
        assertAtLeast(4.5, BoardLabelOnLight, BoardLight, "coordinate on a light square")
        assertAtLeast(4.5, BoardLabelOnDark, BoardDark, "coordinate on a dark square")
    }

    @Test
    fun theContrastFunctionMatchesKnownValues() {
        assertEquals(21.0, contrastRatio(Color.Black, Color.White), 0.01)
        assertEquals(1.0, contrastRatio(Color.White, Color.White), 0.0001)
    }

    private fun over(fg: Color, bg: Color, alpha: Float) = Color(
        red = fg.red * alpha + bg.red * (1 - alpha),
        green = fg.green * alpha + bg.green * (1 - alpha),
        blue = fg.blue * alpha + bg.blue * (1 - alpha),
        alpha = 1f,
    )
}
