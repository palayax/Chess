package net.palaya.chessanalyzer.core.text

import net.palaya.chessanalyzer.core.analysis.TacticType
import org.junit.Assert.assertEquals
import org.junit.Test

/** "a"/"an" agreement for *generated* names: the rule, not a patch for one word. */
class EnglishGrammarTest {

    private fun check(expected: String, phrase: String) =
        assertEquals("article for '$phrase'", expected, EnglishGrammar.article(phrase))

    @Test
    fun `vowel-sound names take an`() {
        listOf(
            "absolute pin", "in-between move", "underpromotion", "x-ray", "interference", "overloaded piece",
            "elegant fork", "umbrella", "unimportant move", "uninvited guest", "hour", "honest mistake", "heir",
            "SEE", "FBI", "8-piece ending", "11th-hour save", "L-shaped route", "X"
        ).forEach { check("an", it) }
    }

    @Test
    fun `consonant-sound names take a`() {
        listOf(
            "fork", "pawn fork", "hanging piece", "skewer", "decoy", "knight", "unique move", "useful trick",
            "one-move trick", "european tour", "uniform", "unicorn", "union", "usable idea", "U-turn", "UFO",
            "history lesson", "hotel", "batter", "9-piece ending"
        ).forEach { check("a", it) }
    }

    @Test
    fun `withArticle puts the article in front and leaves the phrase alone`() {
        assertEquals("an in-between move", EnglishGrammar.withArticle("in-between move"))
        assertEquals("a fork", EnglishGrammar.withArticle("fork"))
        assertEquals("an x-ray", EnglishGrammar.withArticle("x-ray"))
    }

    @Test
    fun `leading punctuation and capitals do not confuse it`() {
        check("an", "  Absolute pin")
        check("an", "\"in-between\" move")
        check("a", "(Fork)")
        check("a", "")
    }

    @Test
    fun `every tactic name in the taxonomy gets the right article`() {
        // Hand-listed, not computed, so a change to the rule cannot quietly bless itself.
        val an = setOf("absolute pin", "interference", "in-between move", "x-ray", "underpromotion", "overloaded piece")
        for (type in TacticType.entries) {
            val name = type.displayName.lowercase()
            val expected = if (name in an) "an" else "a"
            assertEquals("article for $type ('$name')", expected, EnglishGrammar.article(name))
        }
        // The list above must itself describe real taxonomy entries.
        val names = TacticType.entries.map { it.displayName.lowercase() }.toSet()
        assertEquals(emptySet<String>(), an - names)
    }
}
