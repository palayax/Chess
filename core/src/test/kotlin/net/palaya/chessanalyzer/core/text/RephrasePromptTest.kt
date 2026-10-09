package net.palaya.chessanalyzer.core.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Properties

/** docs/LLM_REPHRASE_DESIGN.md §4, §8.1: the prompt is versioned with the lock and built from the checker. */
class RephrasePromptTest {

    private fun lock(): Properties {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "vendor/models/MODELS.lock").isFile) dir = dir.parentFile
        return Properties().apply { File(dir!!, "vendor/models/MODELS.lock").inputStream().use { load(it) } }
    }

    @Test
    fun `the prompt version is the lock's`() {
        assertEquals(lock().getProperty("rephrase.prompt.version")?.trim(), RephrasePrompt.VERSION.toString())
    }

    @Test
    fun `the fact lines are the checker's extraction`() {
        val text = "Now White can play Nxg7+, which sets a forced mate in 5 in motion. That takes Black from clearly worse to decisively lost. Better was Ba6."
        val lines = RephrasePrompt.factLines(text, RephraseSurface.CARD)
        assertEquals(
            "moves: Ba6, Nxg7+ | squares: none | pieces: none | players: White, Black | numbers: 5\n" +
                "terms: forced mate, mate | bands: clearly worse -> decisively lost | names: none | surface: card",
            lines
        )
        val spoken = RephrasePrompt.factLines("White plays knight to h five. The pawn on f four has nothing defending it.", RephraseSurface.NARRATION)
        assertTrue(spoken, "squares: f four, h five" in spoken && "pawn on f four" in spoken && "knight" in spoken)
        assertFalse(spoken, "h5" in spoken)
    }

    @Test
    fun `the prefix is fixed and the request only adds a suffix`() {
        val a = RephrasePrompt.full("Nf3 is a sound move.", RephraseSurface.CARD)
        val b = RephrasePrompt.full("Bd6 is a serious slip.", RephraseSurface.CARD)
        val prefix = RephrasePrompt.prefix()
        assertTrue(a.startsWith(prefix) && b.startsWith(prefix))
        assertEquals(prefix + RephrasePrompt.suffix("Nf3 is a sound move.", RephraseSurface.CARD), a)
        for (e in RephrasePrompt.EXAMPLES) assertTrue(e.output in prefix)
        assertTrue(prefix.startsWith("<|im_start|>system\n"))
        assertTrue(a.endsWith("<|im_start|>assistant\n"))
        assertTrue(RephrasePrompt.full("x", RephraseSurface.CARD, RephrasePrompt.Family.QWEN3).endsWith("<think>\n\n</think>\n\n"))
    }

    @Test
    fun `the generation cap is twice the original plus 16 tokens`() {
        assertEquals(16, RephrasePrompt.maxTokens(0))
        assertEquals(96, RephrasePrompt.maxTokens(40))
    }
}
