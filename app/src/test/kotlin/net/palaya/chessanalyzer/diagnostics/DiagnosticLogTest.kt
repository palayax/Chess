package net.palaya.chessanalyzer.diagnostics

import java.io.File
import java.nio.file.Files
import java.util.TimeZone
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The on-device diagnostic log (F1): one timestamped entry per call, multi-line messages indented,
 * two files that rotate at the size limit (about 1 MiB in total by default), a snapshot for sharing
 * that holds both in order, and content written exactly as given.
 */
class DiagnosticLogTest {

    private val dir: File = Files.createTempDirectory("diaglog").toFile()
    private val utc = TimeZone.getTimeZone("UTC")
    private var now = 1_791_288_000_000L // 2026-10-06 12:00:00 UTC

    private fun log(maxFileBytes: Long = DiagnosticLog.DEFAULT_MAX_FILE_BYTES) =
        DiagnosticLog(dir, maxFileBytes, clock = { now }, zone = utc)

    @After
    fun cleanUp() {
        dir.deleteRecursively()
    }

    // ---- formatting ----

    @Test
    fun anEntryIsOneTimestampedLineWithItsTag() {
        assertEquals(
            "2026-10-06 12:00:00.000 +0000 [analysis] analysis start: depth 18\n",
            DiagnosticLog.formatEntry(now, utc, "analysis", "analysis start: depth 18"),
        )
    }

    @Test
    fun continuationLinesAreIndentedSoEveryEntryStartsWithItsTimestamp() {
        val text = DiagnosticLog.formatEntry(now + 1_234, utc, "crash", "boom\r\nat a.b(C.kt:1)\nat d.e(F.kt:2)\n")
        assertEquals(
            "2026-10-06 12:00:01.234 +0000 [crash] boom\n    at a.b(C.kt:1)\n    at d.e(F.kt:2)\n",
            text,
        )
    }

    @Test
    fun theTimestampCarriesTheLocalOffset() {
        val jerusalem = TimeZone.getTimeZone("GMT+03:00")
        assertTrue(DiagnosticLog.formatEntry(now, jerusalem, "t", "m").startsWith("2026-10-06 15:00:00.000 +0300 [t] m"))
    }

    @Test
    fun anErrorIsLoggedWithItsFullStackTraceAndRememberedForTheShareSummary() {
        val l = log()
        val error = IllegalStateException("engine pipe closed", RuntimeException("root cause"))
        l.error("analysis", "engine failed at position 45 of 67", error)
        val text = l.currentFile.readText()
        assertTrue(text.contains("[analysis] engine failed at position 45 of 67\n"))
        assertTrue(text.contains("    java.lang.IllegalStateException: engine pipe closed"))
        assertTrue("the cause is in the trace", text.contains("Caused by: java.lang.RuntimeException: root cause"))
        assertTrue(text.contains("    \tat net.palaya.chessanalyzer.diagnostics.DiagnosticLogTest"))
        assertEquals("engine failed at position 45 of 67: IllegalStateException: engine pipe closed", l.lastError)
    }

    // ---- content is written as given (nothing is redacted, and nothing needs to be) ----

    @Test
    fun theGameAndThePositionLinesArriveVerbatim() {
        val l = log()
        val game = "tags: [White \"Fouchon\"] [Black \"Sonarmind\"]\nmoves: 1. e4 d5 2. exd5 Nf6 0-1"
        l.log("analysis", "analysis start: depth 18\n$game")
        l.log("analysis", "pos 45 Rdg1 depth 16/18 nodes 30123456 ms 41234 capped yes score cp -598")
        val text = l.currentFile.readText()
        assertTrue(text.contains("    tags: [White \"Fouchon\"] [Black \"Sonarmind\"]\n"))
        assertTrue(text.contains("    moves: 1. e4 d5 2. exd5 Nf6 0-1\n"))
        assertTrue(text.contains("[analysis] pos 45 Rdg1 depth 16/18 nodes 30123456 ms 41234 capped yes score cp -598\n"))
        assertFalse("no masking of any kind", text.contains("***") || text.contains("REDACTED"))
    }

    @Test
    fun aRunawayEntryIsCutSoItCannotRotateTheWholeHistoryAway() {
        val l = log()
        l.log("t", "x".repeat(DiagnosticLog.MAX_ENTRY_CHARS * 3))
        assertTrue(l.currentFile.length() < DiagnosticLog.MAX_ENTRY_CHARS + 100)
    }

    // ---- rotation ----

    @Test
    fun theCurrentFileBecomesThePreviousOneAtTheLimitAndTheTotalStaysBounded() {
        val limit = 4_096L
        val l = log(maxFileBytes = limit)
        repeat(400) { i -> now += 1_000; l.log("analysis", "entry $i " + "y".repeat(60)) }
        assertTrue(l.currentFile.isFile)
        assertTrue(l.previousFile.isFile)
        assertTrue("current within its limit", l.currentFile.length() <= limit)
        assertTrue("previous within its limit", l.previousFile.length() <= limit)
        assertTrue("two files at most", l.sizeBytes() <= 2 * limit)
        assertEquals(setOf(DiagnosticLog.CURRENT_NAME, DiagnosticLog.PREVIOUS_NAME), dir.list()!!.toSet())
        // The newest entry is in the current file, the oldest kept ones in the previous file.
        assertTrue(l.currentFile.readText().contains("entry 399 "))
        assertFalse("the oldest entries were rotated out", (l.previousFile.readText() + l.currentFile.readText()).contains("entry 0 "))
    }

    @Test
    fun theDefaultLimitIsAboutOneMebibyteAcrossTwoFiles() {
        assertEquals(1024L * 1024, 2 * DiagnosticLog.DEFAULT_MAX_FILE_BYTES)
    }

    @Test
    fun theSnapshotHoldsThePreviousFileThenTheCurrentOneInOrder() {
        val l = log(maxFileBytes = 1_024L)
        repeat(60) { i -> now += 1_000; l.log("t", "line $i " + "z".repeat(40)) }
        val snap = l.snapshot(File(dir, "share/log.txt"))
        val text = snap.readText()
        assertEquals(l.previousFile.readText() + l.currentFile.readText(), text)
        val numbers = Regex("line (\\d+) ").findAll(text).map { it.groupValues[1].toInt() }.toList()
        assertEquals("oldest first, no gaps", numbers, numbers.sorted())
        assertEquals(59, numbers.last())
    }

    @Test
    fun aLogThatCannotBeWrittenNeverThrows() {
        val blocked = File(dir, "not-a-dir").apply { writeText("x") }
        val l = DiagnosticLog(blocked, clock = { now }, zone = utc)
        l.log("t", "nothing happens")
        l.error("t", "still nothing", RuntimeException())
        assertEquals(0L, l.sizeBytes())
    }
}
