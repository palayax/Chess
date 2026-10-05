package net.palaya.chessanalyzer.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R7: the "A vs B" title of the Summary header and the Home recent-game row. White's name is isolated
 * from Black's, so a Hebrew name cannot reorder its neighbour; "(you)" sits outside the isolate.
 */
class VersusLineTest {
    private val fsi = 0x2068.toChar()
    private val pdi = 0x2069.toChar()
    private val format = "%1\$s vs %2\$s"
    private val moshe = "משה דיין"
    private val david = "דוד בן־גוריון"

    @Test
    fun eachNameIsIsolatedAndWhiteComesFirst() {
        val line = versusLine(format, moshe, david)
        assertEquals("$fsi$moshe$pdi vs $fsi$david$pdi", line)
        assertTrue(line.indexOf(moshe) < line.indexOf(" vs ") && line.indexOf(" vs ") < line.indexOf(david))
    }

    @Test
    fun latinNamesAreOnlyWrappedInInvisibleIsolates() {
        assertEquals("Ann vs Bob", versusLine(format, "Ann", "Bob").replace(fsi.toString(), "").replace(pdi.toString(), ""))
    }

    @Test
    fun youSuffixIsOutsideTheIsolate() {
        val line = versusLine(format, moshe, "Bob", decorateWhite = { "$it (you)" })
        assertEquals("$fsi$moshe$pdi (you) vs ${fsi}Bob$pdi", line)
        assertTrue(line.indexOf(pdi) < line.indexOf("(you)"))
    }

    @Test
    fun mixedScriptsKeepEachOwnIsolate() {
        val line = versusLine(format, "Ann", david)
        assertEquals("${fsi}Ann$pdi vs $fsi$david$pdi", line)
    }
}
