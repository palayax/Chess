package net.palaya.chessanalyzer.core.text

import net.palaya.chessanalyzer.core.text.ClaimChecker.Reason
import net.palaya.chessanalyzer.core.text.ClaimChecker.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * docs/LLM_REPHRASE_DESIGN.md §5, §8.1: the comparator on every recorded text, its negative controls, and
 * the rules one by one. The Python twin (`scripts/audit_commentary.py rephrase` / `mutate-rephrase`) reads
 * `build/rephrase/mutations.jsonl`, written here, and must reach the same verdict on every line.
 */
class ClaimCheckerTest {

    private fun check(o: String, c: String, s: RephraseSurface = RephraseSurface.CARD) = ClaimChecker.check(o, c, s)

    private fun assertRejected(v: Verdict, vararg reasons: Reason) {
        assertTrue("expected a rejection ${reasons.toList()}, got $v", v is Verdict.Rejected && v.reason in reasons)
    }

    // -------------------------------------------------------------------------------------------
    // Every recorded text
    // -------------------------------------------------------------------------------------------

    @Test
    fun `every recorded card text and narration beat is unchanged against itself, and its facts are stable`() {
        val items = RephraseCorpus.cards + RephraseCorpus.narration
        assertTrue("${RephraseCorpus.cards.size} cards", RephraseCorpus.cards.size == 3 * (45 + 33 + 66))
        assertTrue("${RephraseCorpus.narration.size} beats", RephraseCorpus.narration.size >= 18)
        for (it in items) {
            assertEquals(it.id, Verdict.Unchanged, ClaimChecker.check(it.text, it.text, it.surface))
            assertEquals(it.id, ClaimChecker.facts(it.text, it.surface), ClaimChecker.facts(" " + it.text.replace(" ", "  ") + " ", it.surface))
        }
    }

    @Test
    fun `the facts of the recorded texts are not empty, the extractor sees the moves, squares and sides`() {
        val cards = RephraseCorpus.cards.map { ClaimChecker.facts(it.text, it.surface) }
        assertTrue(cards.all { it.moves.isNotEmpty() || it.squares.isNotEmpty() })
        assertTrue(cards.count { it.betterWas != null } >= 40)
        assertTrue(cards.count { it.charges.isNotEmpty() } >= 20)
        assertTrue(cards.count { it.bands.size == 2 } >= 20)
        val narr = RephraseCorpus.narration.map { ClaimChecker.facts(it.text, it.surface) }
        assertTrue(narr.count { it.squares.isNotEmpty() } >= 50)
        assertTrue(narr.count { it.names.isNotEmpty() } >= 5)
    }

    @Test
    fun `every mutation of every recorded text is rejected with its reason, and the Python twin gets the same lines`() {
        val out = StringBuilder()
        val applied = HashMap<String, Int>()
        val primary = HashMap<String, Int>()
        val misses = ArrayList<String>()
        for (item in RephraseCorpus.distinct) {
            out.appendLine(jsonLine(item, "identity", item.text, ClaimChecker.check(item.text, item.text, item.surface)))
            for (m in RephraseMutations.ALL) {
                val candidate = m.apply(item.text, item.surface) ?: continue
                if (candidate == item.text) continue
                applied.merge(m.name, 1, Int::plus)
                val v = ClaimChecker.check(item.text, candidate, item.surface)
                out.appendLine(jsonLine(item, m.name, candidate, v))
                if (v !is Verdict.Rejected || v.reason !in m.expected) misses += "${m.name} on ${item.id}: $v\n  [${item.text}]\n  [$candidate]"
                if (v is Verdict.Rejected && v.reason == m.expected.first()) primary.merge(m.name, 1, Int::plus)
            }
        }
        File("build/rephrase/mutations.jsonl").apply { parentFile.mkdirs() }.writeText(out.toString())
        assertTrue("${misses.size} mutations missed:\n" + misses.take(15).joinToString("\n"), misses.isEmpty())
        assertTrue("mutations applied: $applied", applied.size >= 18)
        for (m in RephraseMutations.ALL) assertTrue("${m.name} never applied", (applied[m.name] ?: 0) > 0)
        // Each rule bites on its own somewhere (an addition to a four-word text may trip the length band first).
        for (m in RephraseMutations.ALL) assertTrue("${m.name} never rejected by ${m.expected.first()}: $primary", (primary[m.name] ?: 0) > 0)
    }

    private fun q(s: String?): String =
        if (s == null) "null" else "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\u0001", "") + "\""

    private fun jsonLine(item: RephraseCorpus.Item, mutation: String, candidate: String, v: Verdict): String {
        val verdict = when (v) {
            Verdict.Accepted -> "ACCEPT"
            Verdict.Unchanged -> "UNCHANGED"
            is Verdict.Rejected -> "REJECT"
        }
        val reason = (v as? Verdict.Rejected)?.reason?.name
        return "{\"id\":${q(item.id)},\"surface\":${q(item.surface.name)},\"mutation\":${q(mutation)},\"original\":${q(item.text)}," +
            "\"candidate\":${q(candidate)},\"kotlin\":${q(verdict)},\"kotlin_reason\":${q(reason)}}"
    }

    // -------------------------------------------------------------------------------------------
    // The prompt's own examples
    // -------------------------------------------------------------------------------------------

    @Test
    fun `the few-shot outputs pass the checker by construction`() {
        for (e in RephrasePrompt.EXAMPLES) {
            val v = ClaimChecker.check(e.text, e.output, e.surface)
            if (e.text == e.output) assertEquals(e.text, Verdict.Unchanged, v) else assertEquals(e.text, Verdict.Accepted, v)
        }
    }

    // -------------------------------------------------------------------------------------------
    // The rules, one by one
    // -------------------------------------------------------------------------------------------

    private val charge = "Now White can play Nxg7+, which sets a forced mate in 5 in motion. That takes Black from clearly worse to decisively lost. Better was Ba6."

    @Test
    fun `a faithful rewording is accepted`() {
        assertEquals(Verdict.Accepted, check(charge, "White can now play Nxg7+, which sets a forced mate in 5 in motion, and that takes Black from clearly worse to decisively lost. Better was Ba6."))
        assertEquals(
            Verdict.Accepted,
            check("Nf3 matches the engine's top choice. It hits the loose queen on h4.", "Nf3 is the engine's top choice, and it hits the loose queen on h4.")
        )
    }

    @Test
    fun `curly quotes, spacing and a missing full stop are normalised, not rejected`() {
        assertEquals(Verdict.Unchanged, check("Qf3 is the engine's first choice.", "  Qf3 is the engine\u2019s first   choice "))
    }

    @Test
    fun `spoken squares fold to the same fact, but notation in narration is refused`() {
        val o = "White plays knight to h five. The pawn on f four has nothing defending it."
        assertEquals(listOf("f4", "h5"), ClaimChecker.facts(o, RephraseSurface.NARRATION).squares)
        assertEquals(Verdict.Accepted, check(o, "White's knight goes to h five, and the pawn on f four has nothing defending it.", RephraseSurface.NARRATION))
        assertRejected(check(o, "White's knight goes to h5, and the pawn on f four has nothing defending it.", RephraseSurface.NARRATION), Reason.SHAPE_FORMAT)
    }

    @Test
    fun `a side can never flip, not even inside one sentence`() {
        val o = "That takes White from winning to about level."
        assertRejected(check(o, "That takes Black from winning to about level."), Reason.FACTS_PLAYERS)
        val two = "White takes the rook on a1 with the queen. Black recaptures."
        assertRejected(check(two, "Black takes the rook on a1 with the queen. White recaptures."), Reason.FACTS_PLAYERS)
    }

    @Test
    fun `the charge keeps its beneficiary right before its move`() {
        assertRejected(
            check(charge, "Black's position allows White can play... no: Black can play Nxg7+ for a forced mate in 5, and White goes from clearly worse to decisively lost. Better was Ba6."),
            Reason.CHARGE, Reason.FACTS_PLAYERS, Reason.BANNED, Reason.SHAPE_LENGTH
        )
        assertRejected(check(charge, "Black goes from clearly worse to decisively lost: White can play Nxg7+, which sets a forced mate in 5 in motion. Better was Ba6."), Reason.FACTS_PLAYERS)
    }

    @Test
    fun `Better was stays last, once, with the same move`() {
        assertRejected(check(charge, "Better was Ba6. Now White can play Nxg7+, which sets a forced mate in 5 in motion. That takes Black from clearly worse to decisively lost."), Reason.BETTER_WAS)
        assertRejected(check(charge, "Now White can play Nxg7+, which sets a forced mate in 5 in motion. That takes Black from clearly worse to decisively lost. Better was Bb5."), Reason.BETTER_WAS)
        // said twice: "better" is counted (BANNED) before the Better-was rule looks
        assertRejected(check(charge, "Now White can play Nxg7+, which sets a forced mate in 5 in motion; Better was Ba6, so that takes Black from clearly worse to decisively lost. Better was Ba6."), Reason.BETTER_WAS, Reason.BANNED)
    }

    @Test
    fun `a check mark is part of the move`() {
        assertRejected(check(charge, charge.replace("Nxg7+", "Nxg7")), Reason.CHARGE, Reason.FACTS_MOVES)
    }

    @Test
    fun `hedges, praise and judgement words the original lacks are banned, its own are allowed`() {
        val o = "Bd6 is a serious slip."
        assertRejected(check(o, "Bd6 is probably a serious slip."), Reason.BANNED)
        assertRejected(check(o, "Bd6 is a terrible, serious slip."), Reason.BANNED)
        assertEquals(Verdict.Accepted, check(o, "Bd6 is a serious slip here."))
    }

    @Test
    fun `an outcome said with another verb form is the same outcome, a second one is a new claim`() {
        val o = "Nf3 matches the engine's top choice. It hits the loose queen on h4."
        assertEquals(Verdict.Accepted, check(o, "Nf3 matches the engine's top choice, hitting the loose queen on h4."))
        val p = "exf3 was the only move that kept things on track. This picks up a piece."
        assertEquals(Verdict.Accepted, check(p, "exf3 was the only move that kept things on track, picking up a piece."))
        assertRejected(check(p, "exf3 was the only move that kept things on track and wins a tempo, picking up a piece."), Reason.BANNED, Reason.FACTS_OUTCOMES)
        assertRejected(check(p, "exf3 was the only move that kept things on track, it wins, picking up a piece."), Reason.FACTS_OUTCOMES)
    }

    @Test
    fun `negations are counted`() {
        val o = "It does not come back."
        assertRejected(check(o, "It does come back."), Reason.FACTS_NEGATION)
        assertEquals(Verdict.Accepted, check(o, "It doesn't come back."))
    }

    @Test
    fun `a name the original does not have is refused, the ones it has must stay`() {
        val o = "Adolf Anderssen has the white pieces. Lionel Kieseritzky is on the other side."
        assertRejected(check(o, "Adolf Anderssen has the white pieces. Kieseritzky is on the other side.", RephraseSurface.NARRATION), Reason.FACTS_NAMES)
        assertRejected(check(o, "Adolf Anderssen has the white pieces, like Morphy. Lionel Kieseritzky is on the other side.", RephraseSurface.NARRATION), Reason.FACTS_NAMES)
        assertRejected(check("Qf3 is the engine's first choice.", "Kasparov would play Qf3, the engine's first choice."), Reason.FACTS_NAMES, Reason.SHAPE_LENGTH)
    }

    @Test
    fun `shape rules - length band, sentence count, format characters, preamble, card exclamation`() {
        val o = "Nf3 matches the engine's top choice. It hits the loose queen on h4."
        assertRejected(check(o, "Nf3 hits the loose queen on h4."), Reason.SHAPE_LENGTH)
        assertRejected(check(o, "Nf3 matches the engine's top choice. It hits the loose queen on h4. Nice. Yes. Yes."), Reason.SHAPE_SENTENCES, Reason.BANNED)
        assertRejected(check(o, "**Nf3** matches the engine's top choice. It hits the loose queen on h4."), Reason.SHAPE_FORMAT)
        assertRejected(check(o, "Sure: Nf3 matches the engine's top choice and hits the loose queen on h4."), Reason.SHAPE_PREAMBLE)
        assertRejected(check(o, "Nf3 matches the engine's top choice! It hits the loose queen on h4."), Reason.REGISTER_EXCLAMATION)
        assertRejected(check(o, "Nf3 matches the engine's top choice.\nIt hits the loose queen on h4."), Reason.SHAPE_FORMAT)
        assertRejected(check(o, ""), Reason.EMPTY)
    }

    @Test
    fun `cleanOutput strips stop markers, an empty think block, wrapping quotes and a second paragraph`() {
        assertEquals("Nf3 is fine.", RephrasePrompt.cleanOutput("<think>\n\n</think>\n\n\"Nf3 is fine.\"<|im_end|>"))
        assertEquals("Nf3 is fine.", RephrasePrompt.cleanOutput("Nf3 is fine.\n\nNote: I kept every fact."))
        assertEquals("It's the engine's move.", RephrasePrompt.cleanOutput("It's the engine's move."))
        // narration: a bare square written as notation goes back to its spoken form; a SAN move is left alone
        assertEquals("Black plays queen to f six, then Nf3.", RephrasePrompt.cleanOutput("Black plays queen to f6, then Nf3.", RephraseSurface.NARRATION))
        assertEquals("Black plays queen to f6.", RephrasePrompt.cleanOutput("Black plays queen to f6.", RephraseSurface.CARD))
    }
}
