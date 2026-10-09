package net.palaya.chessanalyzer.core.text

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Writes the C2 corpus (docs/LLM_REPHRASE_DESIGN.md §5.5) to `core/build/rephrase/`:
 *  - `corpus.jsonl`: every distinct recorded card text and narration beat, its surface and its prompt suffix;
 *  - `prefix_qwen2.txt` / `prefix_qwen3.txt`: the fixed prompt prefix, byte for byte what a backend sends.
 * The input of `scripts/rephrase_measure.py` (the host model runs) and of `scripts/audit_commentary.py rephrase`.
 * Like the other dumps it writes into the build directory only.
 */
class RephraseCorpusDumpTest {

    private fun q(s: String?): String =
        if (s == null) "null" else "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""

    @Test
    fun dump() {
        val dir = File("build/rephrase").apply { mkdirs() }
        val out = StringBuilder()
        for (item in RephraseCorpus.distinct) {
            out.appendLine(
                "{\"id\":${q(item.id)},\"game\":${q(item.game)},\"side\":${q(item.side?.name)},\"surface\":${q(item.surface.name)}," +
                    "\"ply\":${item.ply},\"kind\":${q(item.kind)},\"text\":${q(item.text)}," +
                    "\"suffix_qwen2\":${q(RephrasePrompt.suffix(item.text, item.surface, RephrasePrompt.Family.QWEN2))}," +
                    "\"suffix_qwen3\":${q(RephrasePrompt.suffix(item.text, item.surface, RephrasePrompt.Family.QWEN3))}}"
            )
        }
        File(dir, "corpus.jsonl").writeText(out.toString())
        File(dir, "prefix_qwen2.txt").writeText(RephrasePrompt.prefix(RephrasePrompt.Family.QWEN2))
        File(dir, "prefix_qwen3.txt").writeText(RephrasePrompt.prefix(RephrasePrompt.Family.QWEN3))
        File(dir, "prompt_version.txt").writeText(RephrasePrompt.VERSION.toString())
        assertTrue(RephraseCorpus.distinct.count { it.surface == RephraseSurface.CARD } > 150)
        assertTrue(RephraseCorpus.distinct.count { it.surface == RephraseSurface.NARRATION } > 100)
    }
}
