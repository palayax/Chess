package net.palaya.chessanalyzer.rephrase

import net.palaya.chessanalyzer.core.text.RephraseSurface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** docs/LLM_REPHRASE_DESIGN.md §6.3: keys, the three entry kinds, atomic files, purges, and the re-check. */
class RephraseCacheTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val id = "qwen2.5-1.5b-instruct-q4_k_m@p1"
    private val text = "Nf3 matches the engine's top choice. It hits the loose queen on h4."

    @Test
    fun theKeyIsTwentyFourHexAndMovesWithModelPromptSurfaceAndText() {
        val c = RephraseCache(tmp.root)
        val k = c.keyFor(id, RephraseSurface.CARD, text)
        assertTrue(k, Regex("[0-9a-f]{24}").matches(k))
        assertNotEquals(k, c.keyFor("qwen2.5-1.5b-instruct-q4_k_m@p2", RephraseSurface.CARD, text))
        assertNotEquals(k, c.keyFor("other@p1", RephraseSurface.CARD, text))
        assertNotEquals(k, c.keyFor(id, RephraseSurface.NARRATION, text))
        assertNotEquals(k, c.keyFor(id, RephraseSurface.CARD, "$text "))
        assertEquals(k, RephraseCache(File(tmp.root, "x")).keyFor(id, RephraseSurface.CARD, text))
    }

    @Test
    fun theThreeEntriesRoundTripUnderTheModelsFolder() {
        val c = RephraseCache(tmp.root)
        c.put(id, RephraseSurface.CARD, text, RephraseCache.Entry.Accepted("Nf3 is the engine's top choice, and it hits the loose queen on h4."))
        c.put(id, RephraseSurface.CARD, "a", RephraseCache.Entry.Unchanged)
        c.put(id, RephraseSurface.CARD, "b", RephraseCache.Entry.Rejected("FACTS_PLAYERS"))
        assertEquals(RephraseCache.Entry.Accepted("Nf3 is the engine's top choice, and it hits the loose queen on h4."), c.get(id, RephraseSurface.CARD, text))
        assertEquals(RephraseCache.Entry.Unchanged, c.get(id, RephraseSurface.CARD, "a"))
        assertEquals(RephraseCache.Entry.Rejected("FACTS_PLAYERS"), c.get(id, RephraseSurface.CARD, "b"))
        assertEquals(null, c.get(id, RephraseSurface.CARD, "c"))
        val dir = File(tmp.root, "qwen2.5-1.5b-instruct-q4_k_m")
        assertEquals(3, dir.listFiles()!!.count { it.name.endsWith(".txt") })
        assertFalse(dir.listFiles()!!.any { it.name.endsWith(".tmp") })
        assertTrue(c.totalSizeBytes() > 0)
    }

    @Test
    fun acceptedTextsAreReCheckedSoATamperedFileIsIgnored() {
        val c = RephraseCache(tmp.root)
        c.put(id, RephraseSurface.CARD, text, RephraseCache.Entry.Accepted("Nf3 matches the engine's top choice. It hits the loose queen on h5."))
        assertEquals(emptyMap<String, String>(), c.accepted(id, RephraseSurface.CARD, listOf(text)))
        c.put(id, RephraseSurface.CARD, text, RephraseCache.Entry.Accepted("Nf3 is the engine's top choice, and it hits the loose queen on h4."))
        assertEquals(mapOf(text to "Nf3 is the engine's top choice, and it hits the loose queen on h4."), c.accepted(id, RephraseSurface.CARD, listOf(text)))
    }

    @Test
    fun aCorruptFileReadsAsAbsent() {
        val c = RephraseCache(tmp.root)
        c.put(id, RephraseSurface.CARD, text, RephraseCache.Entry.Unchanged)
        File(File(tmp.root, "qwen2.5-1.5b-instruct-q4_k_m"), c.keyFor(id, RephraseSurface.CARD, text) + ".txt").writeText("garbage")
        assertEquals(null, c.get(id, RephraseSurface.CARD, text))
    }

    @Test
    fun clearExceptKeepsOnlyTheActiveModelAndClearEmptiesAll() {
        val c = RephraseCache(tmp.root)
        c.put(id, RephraseSurface.CARD, "a", RephraseCache.Entry.Unchanged)
        c.put("old-model@p1", RephraseSurface.CARD, "a", RephraseCache.Entry.Unchanged)
        c.clearExcept(id)
        assertEquals(listOf("qwen2.5-1.5b-instruct-q4_k_m"), tmp.root.listFiles()!!.map { it.name })
        assertTrue(c.clear())
        assertEquals(0L, c.totalSizeBytes())
    }
}
