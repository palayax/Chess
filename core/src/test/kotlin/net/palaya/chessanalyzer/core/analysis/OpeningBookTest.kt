package net.palaya.chessanalyzer.core.analysis

import net.palaya.chessanalyzer.core.chess.Position
import net.palaya.chessanalyzer.core.chess.parseSan
import net.palaya.chessanalyzer.core.pgn.PgnParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.FileReader

/**
 * Loads the REAL `app/src/main/assets/openings.tsv` (3810 data lines), resolved relative to the
 * project root the way the task requires.
 */
class OpeningBookTest {

    private fun findOpeningsFile(): File {
        // Walk upward from the working directory until we find the asset, so this test doesn't
        // depend on whether Gradle's working directory is the project root or the :core module.
        var dir = File(".").absoluteFile
        repeat(6) {
            val candidate = File(dir, "app/src/main/assets/openings.tsv")
            if (candidate.exists()) return candidate
            dir = dir.parentFile ?: return@repeat
        }
        throw IllegalStateException("Could not locate app/src/main/assets/openings.tsv from ${File(".").absolutePath}")
    }

    @Test
    fun `loads all 3810 openings quickly with no unexpected failures`() {
        val file = findOpeningsFile()
        val startNanos = System.nanoTime()
        val book = FileReader(file).use { OpeningBook.load(it) }
        val elapsedMs = (System.nanoTime() - startNanos) / 1_000_000.0

        println("OpeningBook load: ${book.linesLoaded} loaded, ${book.linesSkipped} skipped, ${elapsedMs}ms")

        assertEquals(3810, book.linesLoaded)
        assertEquals(0, book.linesSkipped)
        // A wall-clock bound only guards against a catastrophic regression (e.g. an accidental O(n^2)).
        // It must not be tight: it measured 0.9 s alone but 2.8-8.7 s while an emulator suite and a
        // Gradle build shared the host, which made a 2 s limit fail for the wrong reason.
        assertTrue("Loading took ${elapsedMs}ms, expected well under 15000ms", elapsedMs < 15_000)
    }

    @Test
    fun `longest match after 1 e4 e5 2 Nf3 d6 is the Philidor Defense`() {
        val book = FileReader(findOpeningsFile()).use { OpeningBook.load(it) }
        val pgn = "[Event \"Test\"]\n\n1. e4 e5 2. Nf3 d6 *"
        val game = PgnParser.parse(pgn).single()

        val entry = book.longestMatchingOpening(game)
        assertNotNull(entry)
        assertEquals("Philidor Defense", entry!!.name)
        assertEquals("C41", entry.eco)
    }

    @Test
    fun `a random middlegame FEN is not a book position`() {
        val book = FileReader(findOpeningsFile()).use { OpeningBook.load(it) }
        // An arbitrary, deeply irregular middlegame position that cannot appear in any short
        // named opening line.
        val middlegameFen = "2r2rk1/pb1nqp1p/1p1ppbp1/8/2PNP3/1PN1BP2/P2Q2PP/2R2RK1 w - - 4 18"
        assertFalse(book.isBookPosition(middlegameFen))
        assertEquals(null, book.lookup(middlegameFen))
    }

    @Test
    fun `malformed lines are skipped and counted, never thrown`() {
        val tsv = buildString {
            appendLine("eco\tname\tpgn")
            appendLine("A00\tValid Opening\t1. Nh3")
            appendLine("A01\tMissing pgn column") // only 2 fields
            appendLine("A02\tIllegal move opening\t1. e4 e4") // e4 twice is illegal
            appendLine("") // blank line
            appendLine("A03\tAnother valid\t1. d4 d5")
        }
        val book = OpeningBook.load(tsv.reader())
        assertEquals(2, book.linesLoaded)
        assertEquals(2, book.linesSkipped)
    }

    @Test
    fun `isBookPosition recognises an unnamed prefix of a named line`() {
        val book = FileReader(findOpeningsFile()).use { OpeningBook.load(it) }
        // The starting position itself is trivially "in book".
        assertTrue(book.isBookPosition(Position.startPosition().toFen()))
    }

    @Test
    fun `fenKey ignores halfmove and fullmove counters`() {
        val book = OpeningBook.load("eco\tname\tpgn\nA00\tTest\t1. e4\n".reader())
        val pos = Position.startPosition()
        val afterE4 = pos.makeMove(pos.parseSan("e4"))
        val fenWithCounters = afterE4.toFen()
        val fenWithDifferentCounters = fenWithCounters.split(" ").toMutableList()
            .also { it[4] = "7"; it[5] = "42" }
            .joinToString(" ")
        assertEquals(book.fenKey(fenWithCounters), book.fenKey(fenWithDifferentCounters))
        assertTrue(book.isBookPosition(fenWithDifferentCounters))
    }
}
