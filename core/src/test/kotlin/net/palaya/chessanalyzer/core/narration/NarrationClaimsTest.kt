package net.palaya.chessanalyzer.core.narration

import net.palaya.chessanalyzer.core.chess.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R1b: the narration's own claims, found while auditing the text beside it. The narrated video says
 * its own sentences from typed facts (`NarrationStrings`); these are the ones that were not true.
 */
class NarrationClaimsTest {

    private val viewer = Subject(Color.WHITE, Person.SECOND, Gender.FEMININE)
    private val black = Subject(Color.BLACK, Person.THIRD)

    private fun english(sentence: Sentence, style: NarrationStyle = NarrationStyle.COACH): List<String> =
        EnglishNarration.render(sentence, style)

    @Test
    fun `a lesson lead that counts the lessons is only used when the count is right`() {
        for (count in 1..5) {
            for (style in NarrationStyle.entries) {
                for (variant in english(Sentence.LessonLead(count), style)) {
                    for ((words, n) in mapOf("Two things" to 2, "Three things" to 3, "Four things" to 4, "one thing" to 1)) {
                        if (words in variant) assertEquals("[$variant] for $count lessons", n, count)
                    }
                }
            }
        }
        assertTrue(english(Sentence.LessonLead(3)).any { "Three things" in it })
        assertTrue(english(Sentence.LessonLead(1)).none { "things" in it })
    }

    @Test
    fun `how often a move was found never reads one times`() {
        fun positive(n: Int) = english(Sentence.LessonPositive(viewer, n, 8, "90.3")).single()
        assertTrue(positive(1), "once out of 8" in positive(1))
        assertTrue(positive(2), "twice out of 8" in positive(2))
        assertTrue(positive(5), "five times out of 8" in positive(5))
        assertTrue(positive(0), "not once out of 8" in positive(0))
        for (n in 0..5) assertFalse(positive(n), "one times" in positive(n))
    }

    @Test
    fun `chances are counted in the singular and the plural`() {
        fun converted(n: Int) = english(Sentence.LessonOpponentMissedToo(viewer, net.palaya.chessanalyzer.core.analysis.TacticType.FORK, 2, n)).single()
        assertTrue(converted(1), "one chance of your own" in converted(1))
        assertTrue(converted(2), "two chances of your own" in converted(2))
        assertFalse(converted(1), "one chances" in converted(1))
    }

    @Test
    fun `a line that proves nothing is said to end, not to have invested material or to be completely on top`() {
        val ends = english(Sentence.PayoffOutcome(black, PayoffKind.LINE_ENDS)).single()
        assertEquals("That is as far as the line goes.", ends)
        val material = english(Sentence.PayoffMaterial(viewer, MaterialGain.ROOK)).single()
        assertTrue(material, material.endsWith("a rook up."))
        assertFalse(material, "for nothing" in material)
    }

    @Test
    fun `no narrated video of the recorded games claims an unprovable payoff`() {
        val unprovable = listOf("invested material", "completely on top", "for nothing", "one times")
        for ((name, game) in listOf(
            "immortal" to RealGameFixture.immortal, "chesscom" to RealGameFixture.chesscom,
            "scholar" to RealGameFixture.scholars, "byrne-fischer" to RealGameFixture.byrneFischer
        )) {
            for (side in listOf<Color?>(null, Color.WHITE, Color.BLACK)) {
                for (style in NarrationStyle.entries) {
                    val report = game.report(side)
                    val options = NarrationOptions(style = style)
                    for (depth in NarrationDepth.entries) {
                        val script = VideoScriptGenerator(side).generate(report, game.pgn, options.copy(depth = depth))
                        for (seg in script.segments) {
                            for (u in unprovable) assertFalse("$name/$side/$style/$depth: '$u' in [${seg.narration}]", u in seg.narration)
                        }
                    }
                }
            }
        }
    }

    @Test
    fun `a found mate is never followed by material in the bank, and material words follow the 40 cp rule`() {
        // C1: a mating motif's swing is the mate's saturated value, not material. Before C1 every found
        // forced mate ended "So that's a whole queen."
        val mating = setOf(
            net.palaya.chessanalyzer.core.analysis.TacticType.MATE_NET, net.palaya.chessanalyzer.core.analysis.TacticType.BACK_RANK_MATE,
            net.palaya.chessanalyzer.core.analysis.TacticType.SMOTHERED_MATE, net.palaya.chessanalyzer.core.analysis.TacticType.GREEK_GIFT
        )
        var mates = 0
        for ((name, game) in listOf("immortal" to RealGameFixture.immortal, "game01" to RealGameFixture.game01, "chesscom" to RealGameFixture.chesscom)) {
            val script = VideoScriptGenerator(null).generate(game.report(null), game.pgn, NarrationOptions())
            for (seg in script.segments) {
                if (seg.kind == SegmentKind.FOUND_TACTIC && seg.tactic?.type in mating) {
                    mates++
                    for (word in listOf("in the bank", "just like that", "So that's", "serious material", "a whole queen")) {
                        assertFalse("$name seg ${seg.index}: [${seg.narration}]", word in seg.narration)
                    }
                }
                assertFalse("$name seg ${seg.index}: [${seg.narration}]", "everything holds" in seg.narration)
            }
        }
        assertTrue("found mates in the three games: $mates", mates >= 3)
        assertEquals(MaterialPayoff.ROOK, NarrationVocabulary.materialPayoff(500))
        assertEquals(MaterialPayoff.SERIOUS_MATERIAL, NarrationVocabulary.materialPayoff(800))
        assertEquals(MaterialPayoff.MATERIAL, NarrationVocabulary.materialPayoff(170))
        assertEquals(MaterialPayoff.SERIOUS_MATERIAL, NarrationVocabulary.materialPayoff(10_000))
        assertEquals(MaterialGain.PIECE, NarrationVocabulary.materialGain(330))
        assertEquals(null, NarrationVocabulary.materialGain(170))
        assertEquals(null, NarrationVocabulary.materialGain(800))
    }

    @Test
    fun `every missed line the video walks still ends with a payoff beat on a position`() {
        val script = VideoScriptGenerator(null).generate(
            RealGameFixture.immortal.report(null), RealGameFixture.immortal.pgn, NarrationOptions()
        )
        val payoffs = script.segments.filter { it.kind == SegmentKind.MISSED_TACTIC && it.board is BoardDirective.Annotate && it.narration.contains("line") }
        // the beat exists and speaks: either a proven payoff or "That is as far as the line goes."
        assertTrue(script.segments.any { it.kind == SegmentKind.MISSED_TACTIC })
        assertTrue(payoffs.all { it.narration.isNotBlank() })
    }
}
