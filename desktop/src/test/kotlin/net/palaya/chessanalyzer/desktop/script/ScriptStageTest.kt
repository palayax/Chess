package net.palaya.chessanalyzer.desktop.script

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScriptStageTest {

    @Test
    fun splitsOnSentencesButNotDecimalsOrInitials() {
        val lines = ScriptStage.splitLines(
            "White finished on 91.4 percent accuracy, which is excellent for a club game. " +
                "Black never recovered from move twelve. Why? Because the knight was gone. " +
                "A. Anderssen played it anyway."
        )
        assertTrue(lines.any { it.contains("91.4 percent") })
        assertTrue("short 'Why?' merged with a neighbour", lines.none { it == "Why?" })
        assertTrue("an initial does not end a sentence", lines.any { it.contains("A. Anderssen") })
        assertEquals(lines.joinToString(" "), lines.joinToString(" ").replace(Regex("\\s+"), " "))
    }

    @Test
    fun hardWrapsOverlongSentences() {
        val long = (1..80).joinToString(", ") { "word$it" } + "."
        val lines = ScriptStage.splitLines(long)
        assertTrue(lines.size > 1)
        assertTrue(lines.all { it.length <= ScriptStage.MAX_LINE_CHARS })
        assertEquals(long.replace(" ", ""), lines.joinToString("").replace(" ", ""))
    }
}
