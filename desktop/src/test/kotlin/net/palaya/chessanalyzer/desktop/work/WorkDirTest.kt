package net.palaya.chessanalyzer.desktop.work

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import net.palaya.chessanalyzer.core.pgn.PgnParser
import net.palaya.chessanalyzer.desktop.TestEnv
import net.palaya.chessanalyzer.desktop.cli.ProducerOptions
import net.palaya.chessanalyzer.desktop.cli.UsageException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.nio.file.Files

class WorkDirTest {

    @Test
    fun gameIdIgnoresTagsCommentsClocksAndLayout() {
        val chesscom = Files.readString(TestEnv.fixture("chesscom_style_game.pgn"))
        // Different tags, no clocks or comments, different line breaks, an annotation glyph.
        // (Move numbers keep a space after the dot: core's PgnParser rejects "1.e4" today.)
        val handTyped = """
            [White "someone"]
            1. e4 e5 2. Nf3 d6 3. d4 Bg4 4. dxe5 Bxf3 5. Qxf3 dxe5 6. Bc4 Nf6 7. Qb3 Qe7
            8. Nc3 c6 9. Bg5 b5 10. Nxb5 cxb5 11. Bxb5+ Nbd7 12. O-O-O Rd8 13. Rxd7 Rxd7 14. Rd1 Qe6
            15. Bxd7+ Nxd7 16. Qb8+! Nxb8 17. Rd8# 1-0
        """.trimIndent()
        val a = PgnParser.parse(chesscom).first()
        val b = PgnParser.parse(handTyped).first()
        assertEquals(WorkDir.gameId(a), WorkDir.gameId(b))
        assertEquals(12, WorkDir.gameId(a).length)
        val other = PgnParser.parse("[Event \"x\"]\n\n1. e4 e5 2. Nf3 d6 *").first()
        assertNotEquals(WorkDir.gameId(a), WorkDir.gameId(other))
    }

    @Test
    fun atomicWritesStagesAndMetrics() {
        val wd = WorkDir(Files.createTempDirectory("palaya-wd"), "abc123def456").ensureExists()
        wd.writeAtomic(wd.analysisJson, "{\"x\":1}")
        wd.writeAtomic(wd.analysisJson, "{\"x\":2}")
        assertEquals("{\"x\":2}", Files.readString(wd.analysisJson))
        assertFalse("no .tmp left behind", Files.exists(wd.dir.resolve("analysis.json.tmp")))

        val fp = Stage.fingerprint(Stage.ANALYZE, "moves", "depth=22")
        assertNotEquals(fp, Stage.fingerprint(Stage.ANALYZE, "moves", "depth=18"))
        assertNotEquals("length-prefixed parts", Stage.fingerprint(Stage.ANALYZE, "ab", "c"), Stage.fingerprint(Stage.ANALYZE, "a", "bc"))
        wd.recordStage(Stage.ANALYZE, fp, mapOf("stockfish" to "Stockfish 19"))
        assertEquals(fp, wd.stageRecord(Stage.ANALYZE)!!.inputFingerprint)

        assertFalse(decideStage(Stage.ANALYZE, true, wd.stageRecord(Stage.ANALYZE), fp, force = false, from = null).run)
        assertTrue(decideStage(Stage.ANALYZE, true, wd.stageRecord(Stage.ANALYZE), "other", force = false, from = null).run)
        assertTrue(decideStage(Stage.ANALYZE, false, wd.stageRecord(Stage.ANALYZE), fp, force = false, from = null).run)
        assertTrue(decideStage(Stage.ANALYZE, true, wd.stageRecord(Stage.ANALYZE), fp, force = true, from = null).run)
        assertTrue(decideStage(Stage.ANALYZE, true, wd.stageRecord(Stage.ANALYZE), fp, force = false, from = Stage.ANALYZE).run)
        assertFalse(decideStage(Stage.ANALYZE, true, wd.stageRecord(Stage.ANALYZE), fp, force = false, from = Stage.AUDIO).run)

        wd.writeMetricsSection("analyze", JsonPrimitive(1))
        wd.writeMetricsSection("audio", JsonPrimitive(2))
        wd.writeMetricsSection("analyze", JsonPrimitive(3))
        val m = wd.readMetrics()
        assertEquals("3", m["analyze"]!!.jsonPrimitive.content)
        assertEquals("2", m["audio"]!!.jsonPrimitive.content)
    }

    @Test
    fun optionsFollowTheCliSpec() {
        val o = ProducerOptions.parse(arrayOf("g.pgn", "--dry-run", "--depth", "18", "--threads", "4", "--target", "8:00-14:00", "--only", "analyze"))
        assertEquals(18, o.depth)
        assertEquals(4, o.threads)
        assertEquals(480..840, o.targetSeconds)
        assertEquals(Stage.ANALYZE, o.only)
        // P1 defaults (RUN_LOG P0 finding): depth 20, 30 s movetime safety cap.
        val defaults = ProducerOptions.parse(arrayOf("g.pgn", "--dry-run"))
        assertEquals(20, defaults.depth)
        assertEquals(30_000L, defaults.movetimeCapMs)
        assertEquals(18, ProducerOptions.parse(arrayOf("g.pgn", "--dry-run", "--quality", "fast")).depth)
        assertEquals(26, ProducerOptions.parse(arrayOf("g.pgn", "--dry-run", "--quality", "best")).depth)
        for (bad in listOf(arrayOf("g.pgn"), arrayOf("g.pgn", "--dry-run", "--bogus"), arrayOf("--dry-run"), arrayOf("g.pgn", "-o", "x.mp4", "--only", "nope"))) {
            try {
                ProducerOptions.parse(bad)
                fail("expected a usage error for ${bad.toList()}")
            } catch (_: UsageException) {
            }
        }
    }
}
