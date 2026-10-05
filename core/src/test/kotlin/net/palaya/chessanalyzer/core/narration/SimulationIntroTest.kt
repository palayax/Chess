package net.palaya.chessanalyzer.core.narration

import net.palaya.chessanalyzer.core.analysis.SimulationBuilder
import net.palaya.chessanalyzer.core.analysis.TacticInstance
import net.palaya.chessanalyzer.core.analysis.TacticReferenceLibrary
import net.palaya.chessanalyzer.core.analysis.TacticSimulation
import net.palaya.chessanalyzer.core.analysis.TacticType
import net.palaya.chessanalyzer.core.chess.Color
import net.palaya.chessanalyzer.core.chess.Position
import net.palaya.chessanalyzer.core.chess.Square
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The walkthrough intro, which used to read "h5 starts a line that The pawn on g4 cannot be held -
 * taking it wins material.." (a capital letter mid-sentence, two full stops). It is assembled in
 * `:core` now, so it is checked here for every tactic type, for awkward descriptions, for every
 * textbook reference and for every walkthrough the two recorded real games produce.
 */
class SimulationIntroTest {

    private val builder = SimulationBuilder()

    /** A move or a square: the one kind of capital (or lowercase) token that may follow "a"/"the"/"that". */
    private val notation = Regex(
        "^(O-O(-O)?|[KQRBN][a-h]?[1-8]?x?[a-h][1-8](=[QRBN])?[+#]?|[a-h]x?[a-h]?[1-8](=[QRBN])?[+#]?)$"
    )

    /** A capitalised word that may legitimately follow an article: a proper adjective. */
    private val properWords = setOf("Greek")

    /** An ALL-CAPS word is deliberate emphasis in the textbook teaching points ("a check that MUST be captured"). */
    private fun isEmphasis(word: String): Boolean {
        val letters = word.filter { it.isLetter() }
        return letters.length >= 2 && letters.all { it.isUpperCase() }
    }

    private fun assertClean(label: String, text: String) {
        assertTrue("$label: blank", text.isNotBlank())
        assertEquals("$label: stray outer whitespace", text.trim(), text)
        assertTrue("$label: must end in exactly one full stop: [$text]", text.endsWith(".") && !text.endsWith(".."))
        assertTrue("$label: double punctuation in [$text]", !Regex("[.,;:!?]{2,}").containsMatchIn(text))
        assertTrue("$label: punctuation, space, punctuation in [$text]", !Regex("[.,;:!?]\\s+[.,;:!?]").containsMatchIn(text))
        assertTrue("$label: double space in [$text]", !text.contains("  "))
        // A capital letter in the middle of a sentence, straight after "that"/"a"/"an"/"the".
        for (m in Regex("\\b(that|a|an|the)\\s+([A-Z][A-Za-z0-9+#=-]*)").findAll(text)) {
            val word = m.groupValues[2]
            assertTrue(
                "$label: capital '$word' after '${m.groupValues[1]}' mid-sentence in [$text]",
                notation.matches(word.trimEnd('.', ',', ';', ':')) || word in properWords || isEmphasis(word)
            )
        }
        // A new sentence starts with a capital, a move or a square, never a stray lowercase word.
        for (m in Regex("[.!?] ([a-z][A-Za-z0-9+#=-]*)").findAll(text)) {
            assertTrue(
                "$label: sentence starts with lowercase '${m.groupValues[1]}' in [$text]",
                notation.matches(m.groupValues[1].trimEnd('.', ',', ';', ':'))
            )
        }
    }

    /**
     * @param payoffRequired a textbook reference always says what its line is for; a walkthrough of a
     *   game line says something only when the board proves it (mate, or material netted), and is
     *   blank otherwise - never a vague claim ("has invested material in the attack").
     */
    private fun assertSimulationClean(label: String, sim: TacticSimulation, payoffRequired: Boolean = true) {
        assertClean("$label intro", SimulationIntro.text(sim))
        sim.perPlyExplanation.forEachIndexed { i, text -> assertClean("$label step ${i + 1}", text) }
        assertTrue("$label payoff has trailing punctuation: [${sim.payoffDescription}]", !sim.payoffDescription.endsWith("."))
        if (payoffRequired) assertTrue("$label payoff blank", sim.payoffDescription.isNotBlank())
        for (vague in listOf("invested material", "decisive advantage")) {
            assertTrue("$label: unprovable payoff [$vague] in ${sim.perPlyExplanation.last()}", vague !in sim.perPlyExplanation.last())
            assertTrue("$label: unprovable payoff [$vague]", vague !in sim.payoffDescription)
        }
    }

    // A knight fork line from a real position: Nf6+ forks king and queen.
    private val forkStart = Position.fromFen("6k1/8/8/3q4/4N3/8/8/4K3 w - - 0 1")
    private val forkPv = listOf("e4f6", "g8g7", "f6d5", "g7g6")

    private fun simulationFor(type: TacticType, description: String?): TacticSimulation {
        val tactic = TacticInstance(
            type = type, byColor = Color.WHITE, moveUci = "e4f6",
            targetSquares = listOf(Square.fromAlgebraic("g8"), Square.fromAlgebraic("d5")),
            materialSwing = 900, description = description ?: "", confidence = 0.95
        )
        return builder.build(forkStart, forkPv, tactic)
    }

    private val awkwardDescriptions = listOf(
        null,
        "",
        "   ",
        "The pawn on g4 cannot be held - taking it wins material.",
        "The pawn on g4 cannot be held - taking it wins material..",
        "The pawn on g4 cannot be held - taking it wins material",
        "Qxa1+ clears b2 so that Qb2 can come through.",
        "h5 forks the king and the rook",
        "exd5 wins a pawn.",
        "lowercase opening with no stop",
        "Ends with an ellipsis...",
        "Asks a question?",
        "Shouts!",
        "  Padded   with   spaces .  ",
        "Two sentences. The second also ends cleanly."
    )

    // -----------------------------------------------------------------------
    // The defect itself
    // -----------------------------------------------------------------------

    @Test
    fun `the reported defect reads as a clean pair of sentences`() {
        val sim = simulationFor(TacticType.HANGING_PIECE, "The pawn on g4 cannot be held - taking it wins material.")
        val first = sim.pvSan.first()
        assertEquals(
            "Watch what happens: $first starts the line. The pawn on g4 cannot be held - taking it wins material.",
            SimulationIntro.text(sim)
        )
    }

    @Test
    fun `a description that opens with a move keeps its capital and is not grafted into a clause`() {
        val text = SimulationIntro.text(simulationFor(TacticType.CLEARANCE, "Qxa1+ clears b2 so that Qb2 can come through."))
        assertTrue(text, text.endsWith("Qxa1+ clears b2 so that Qb2 can come through."))
        assertTrue(text, !text.contains("line that"))
    }

    @Test
    fun `a description that opens with a square keeps its lowercase letter`() {
        val text = SimulationIntro.text(simulationFor(TacticType.FORK, "h5 forks the king and the rook"))
        assertTrue(text, text.endsWith(" h5 forks the king and the rook."))
    }

    @Test
    fun `normalisePoint gives one capitalised sentence with one full stop`() {
        assertNull(SimulationIntro.normalisePoint(null))
        assertNull(SimulationIntro.normalisePoint(""))
        assertNull(SimulationIntro.normalisePoint("  ...  "))
        assertEquals("Takes it.", SimulationIntro.normalisePoint("takes it"))
        assertEquals("Takes it.", SimulationIntro.normalisePoint("Takes it..."))
        assertEquals("Takes it.", SimulationIntro.normalisePoint("  Takes   it !  "))
        assertEquals("O-O tucks the king away.", SimulationIntro.normalisePoint("O-O tucks the king away"))
        assertEquals("a6 attacks the bishop.", SimulationIntro.normalisePoint("a6 attacks the bishop."))
        assertEquals("Two. Sentences.", SimulationIntro.normalisePoint("Two. Sentences."))
    }

    @Test
    fun `the intro carries typed facts so a locale can rebuild it`() {
        val sim = simulationFor(TacticType.FORK, "It forks the king and the queen.")
        val facts = SimulationIntro.build(sim)
        assertEquals(sim.pvSan.first(), facts.firstSan)
        assertEquals(TacticType.FORK, facts.tactic)
        assertEquals("It forks the king and the queen.", facts.point)
        assertNull(SimulationIntro.build(simulationFor(TacticType.FORK, null)).point)
    }

    @Test
    fun `with no description the intro names the tactic instead`() {
        val text = SimulationIntro.text(simulationFor(TacticType.ZWISCHENZUG, null))
        assertTrue(text, text.endsWith("The tactic: in-between move."))
        assertClean("fallback", text)
    }

    // -----------------------------------------------------------------------
    // Sweeps
    // -----------------------------------------------------------------------

    @Test
    fun `the intro is clean for every tactic type and every awkward description`() {
        for (type in TacticType.entries) {
            for (description in awkwardDescriptions) {
                val sim = simulationFor(type, description)
                assertClean("$type / ${description?.let { "[$it]" }}", SimulationIntro.text(sim))
            }
        }
    }

    @Test
    fun `the intro and every step are clean for every textbook reference`() {
        val references = TacticReferenceLibrary.all
        assertTrue("the corpus must not be empty", references.isNotEmpty())
        for (reference in references) {
            val sim = TacticReferenceLibrary.simulation(reference.type)
            assertNotNull("no simulation for ${reference.type}", sim)
            assertSimulationClean("reference ${reference.type}", sim!!)
        }
    }

    @Test
    fun `every walkthrough of the recorded real games reads cleanly`() {
        var seen = 0
        for ((name, game) in listOf("immortal" to RealGameFixture.immortal, "chesscom" to RealGameFixture.chesscom)) {
            for (side in listOf<Color?>(null, Color.WHITE, Color.BLACK)) {
                for (a in game.report(side).annotations) {
                    val sim = a.simulation ?: continue
                    assertSimulationClean("$name ply ${a.ply}", sim, payoffRequired = false)
                    seen++
                }
            }
        }
        assertTrue("the recorded games should yield walkthroughs (saw $seen)", seen >= 10)
    }

    @Test
    fun `all of the above hold in every registered language too`() {
        val sim = simulationFor(TacticType.HANGING_PIECE, "The pawn on g4 cannot be held - taking it wins material.")
        for (strings in NarrationLocales.all) {
            for (style in NarrationStyle.entries) {
                assertClean("${strings.languageTag}/$style", SimulationIntro.text(sim, strings, style))
            }
        }
    }

    // -----------------------------------------------------------------------
    // R1b defect 5: the second sentence must not say the first move again
    // -----------------------------------------------------------------------

    @Test
    fun `a description that opens with the first move does not follow a starts-the-line sentence`() {
        val sim = simulationFor(TacticType.DEFLECTION, "Qe4 deflects the queen on g5 away from guarding f4.")
        val first = sim.pvSan.first()
        // The fixture's first move is Nf6+; make the description open with exactly that.
        val described = simulationFor(TacticType.DEFLECTION, "$first deflects the queen on d5 away from guarding f4.")
        val text = SimulationIntro.text(described)
        assertEquals("Watch what happens. $first deflects the queen on d5 away from guarding f4.", text)
        assertEquals(1, Regex(Regex.escape(first.trimEnd('+', '#'))).findAll(text).count())
        // A description that does not name the move keeps the lead-in.
        assertTrue(SimulationIntro.text(sim).startsWith("Watch what happens: $first starts the line. "))
    }

    @Test
    fun `a pawn move spelled like a square is only treated as repeated when the description opens with it`() {
        val sim = simulationFor(TacticType.HANGING_PIECE, "The pawn on e4 is attacked and nothing defends it.")
        assertTrue(SimulationIntro.text(sim).contains("starts the line"))
        assertTrue(SimulationIntro.names("h5 forks the king.", "h5"))
        assertTrue(SimulationIntro.names("Qxa1+ clears b2.", "Qxa1+"))
        assertTrue(SimulationIntro.names("Qxa1 clears b2.", "Qxa1+"))
        assertFalse(SimulationIntro.names("The pawn on h5 is attacked.", "h5"))
        assertFalse(SimulationIntro.names("h50 is not a move.", "h5"))
    }

    @Test
    fun `no walkthrough of the recorded games repeats its first move`() {
        var seen = 0
        for ((name, game) in listOf("immortal" to RealGameFixture.immortal, "chesscom" to RealGameFixture.chesscom)) {
            for (side in listOf<Color?>(null, Color.WHITE, Color.BLACK)) {
                for (a in game.report(side).annotations) {
                    val sim = a.simulation ?: continue
                    val intro = SimulationIntro.text(sim)
                    val san = sim.pvSan.first().trimEnd('+', '#')
                    val lead = "starts the line."
                    if (lead in intro) {
                        val second = intro.substringAfter(lead).trim()
                        assertFalse("$name ply ${a.ply}: [$intro]", second.startsWith("$san "))
                    }
                    val mentions = Regex("(?<![A-Za-z0-9])${Regex.escape(san)}(?![A-Za-z0-9])").findAll(intro).count()
                    assertTrue("$name ply ${a.ply} says $san $mentions times: [$intro]", mentions <= 1 || intro.startsWith("Watch what happens: $san starts"))
                    seen++
                }
            }
        }
        assertTrue(seen >= 10)
    }
}
