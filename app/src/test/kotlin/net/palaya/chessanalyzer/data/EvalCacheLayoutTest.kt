package net.palaya.chessanalyzer.data

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Eval caches per net, `eval_cache/<net 12-hex>/` (D2e; design §4.2): the flat-cache migration and the purge. */
class EvalCacheLayoutTest {

    @get:Rule val tmp = TemporaryFolder()

    private val current = "1a298aa575a0"
    private val other = "abcdef012345"

    private fun layout(): EvalCacheLayout = EvalCacheLayout(tmp.newFolder("eval_cache"))

    private fun File.put(name: String, text: String = "[]") = File(this, name).apply { parentFile!!.mkdirs(); writeText(text) }

    @Test
    fun theFlatCacheMovesIntoTheActiveNetsFolderWithItsCheckpointFiles() {
        val l = layout()
        val key = "a".repeat(64)
        l.root.put("$key.json", "[1]")
        l.root.put("$key.partial.json", "[2]")
        l.root.put("$key.partial.tmp", "[3]")
        l.root.put("${"b".repeat(64)}.json", "[4]")

        val r = l.migrateFlat(current)

        assertEquals(4, r.moved)
        val dir = File(l.root, current)
        assertEquals("[1]", File(dir, "$key.json").readText())
        assertEquals("[2]", File(dir, "$key.partial.json").readText())
        assertEquals("[3]", File(dir, "$key.partial.tmp").readText())
        assertTrue(File(dir, "${"b".repeat(64)}.json").isFile)
        assertTrue("nothing is left at the root", l.root.listFiles()!!.all { it.isDirectory })
    }

    @Test
    fun theMigrationIsIdempotentAndKeepsTheFoldersNewerCopy() {
        val l = layout()
        l.root.put("k.json", "[old]")
        File(l.root, current).put("k.json", "[new]")
        val r = l.migrateFlat(current)
        assertEquals(0, r.moved)
        assertEquals(1, r.deletedFiles)
        assertEquals("[new]", File(l.root, "$current/k.json").readText())
        assertEquals(EvalCacheLayout.Result(0, 0, emptyList()), l.migrateFlat(current))
    }

    @Test
    fun aNetUpdatePurgesEveryOtherNetsFolder() {
        val l = layout()
        File(l.root, current).put("k.json")
        File(l.root, other).put("k.json")
        File(l.root, other).put("j.partial.json")
        l.root.put("stray.json")
        val r = l.purgeExcept(other)
        assertEquals(listOf(current), r.deletedFolders)
        assertEquals(1, r.deletedFiles)
        assertTrue(File(l.root, "$other/k.json").isFile)
        assertTrue(File(l.root, "$other/j.partial.json").isFile)
        assertFalse(File(l.root, current).exists())
    }

    @Test
    fun onlyNetPrefixesAreFolders() {
        val l = layout()
        assertEquals(File(l.root, current), l.dirFor(current))
        for (bad in listOf("", "..", "1A298AA575A0", "1a298aa575a", "1a298aa575a0/..", "nn-1a298aa575a0")) {
            try {
                l.dirFor(bad)
                fail("'$bad' must be refused")
            } catch (e: IllegalArgumentException) {
                // expected
            }
        }
    }

    @Test
    fun theRepositoryReadsAndWritesTheActiveNetsFolder() = kotlinx.coroutines.runBlocking {
        val files = tmp.newFolder("files")
        var prefix = current
        val repo = GameRepository(files) { prefix }
        val key = repo.cacheKey("1. e4 e5", 14, 3, "n25000000t150000")
        repo.saveEvalCache(key, emptyList())
        assertTrue(File(files, "eval_cache/$current/$key.json").isFile)
        assertTrue(repo.loadEvalCache(key) != null)
        prefix = other
        assertEquals("another net's folder has nothing for this key", null, repo.loadEvalCache(key))
        assertTrue(repo.loadEvalCache(key, current) != null)
        // F1's budget is still part of the key.
        assertFalse(key == repo.cacheKey("1. e4 e5", 14, 3, "n45000000t270000"))
    }
}
