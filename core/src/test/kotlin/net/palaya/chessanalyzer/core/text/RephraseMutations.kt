package net.palaya.chessanalyzer.core.text

import net.palaya.chessanalyzer.core.text.ClaimChecker.Reason

/**
 * The negative controls of docs/LLM_REPHRASE_DESIGN.md §5.4: one deliberate breakage of a fact or of the
 * register each, applied to a verified text as if a model had returned it. [apply] returns null when the
 * text has nothing for the mutation to break. `scripts/audit_commentary.py mutate-rephrase` has its own
 * Python implementation of the same table.
 */
object RephraseMutations {

    class Mutation(val name: String, val expected: Set<Reason>, val apply: (String, RephraseSurface) -> String?)

    private val SQUARE_TOKEN = Regex("(?<![A-Za-z0-9])([a-h])([1-8])(?![A-Za-z0-9])")
    private val SPOKEN_SQUARE = Regex("\\b([a-h]) (one|two|three|four|five|six|seven|eight)\\b")
    private val PIECE = Regex("\\b(pawn|knight|bishop|rook|queen|king)\\b")
    private val BETTER = Regex("\\s*Better was [^.]*\\.$")
    private val NUMBER = Regex("(?<![A-Za-z0-9.])(\\d+)(?![A-Za-z0-9])")
    private val FROM_TO = Regex("from (decisively winning|winning|clearly better|slightly better|about level|slightly worse|clearly worse|losing|decisively lost) to (decisively winning|winning|clearly better|slightly better|about level|slightly worse|clearly worse|losing|decisively lost)")
    private val NOT = Regex("\\b(not|no|nothing) ")

    private fun mirror(file: Char): Char = ('a' + ('h' - file))

    private fun swapSides(t: String): String? {
        val pairs = listOf("White" to "Black", "your opponent" to "you")
        var out = t
        var changed = false
        for ((a, b) in pairs) {
            if (a in out || Regex("\\b$b\\b").containsMatchIn(out)) {
                out = out.replace(a, "\u0001").replace(Regex("\\b$b\\b"), a).replace("\u0001", b)
                changed = true
            }
        }
        return if (changed && out != t) out else null
    }

    private fun firstSentenceEnd(t: String): Int = t.indexOf(". ").let { if (it < 0) t.length - 1 else it }

    val ALL: List<Mutation> = listOf(
        Mutation("square changed (file mirrored)", setOf(Reason.FACTS_SQUARES, Reason.FACTS_MOVES, Reason.FACTS_PIECE_SQUARES, Reason.BETTER_WAS, Reason.CHARGE)) { t, s ->
            if (s == RephraseSurface.NARRATION) {
                SPOKEN_SQUARE.find(t)?.let { m -> t.replaceRange(m.range, "${mirror(m.groupValues[1][0])} ${m.groupValues[2]}") }
            } else {
                SQUARE_TOKEN.find(t)?.let { m -> t.replaceRange(m.groups[1]!!.range, mirror(m.groupValues[1][0]).toString()) }
            }
        },
        Mutation("piece renamed", setOf(Reason.FACTS_PIECES, Reason.FACTS_PIECE_SQUARES)) { t, _ ->
            PIECE.find(t)?.let { m -> t.replaceRange(m.range, if (m.value == "bishop") "knight" else "bishop") }
        },
        Mutation("side flipped", setOf(Reason.FACTS_PLAYERS, Reason.CHARGE)) { t, _ -> swapSides(t) },
        Mutation("Better was dropped", setOf(Reason.BETTER_WAS, Reason.SHAPE_LENGTH)) { t, _ ->
            if (BETTER.containsMatchIn(t)) BETTER.replace(t, "") else null
        },
        Mutation("Better was moved first", setOf(Reason.BETTER_WAS)) { t, _ ->
            BETTER.find(t)?.let { m -> m.value.trim() + " " + t.substring(0, m.range.first).trim() }
        },
        Mutation("Better was names another move", setOf(Reason.BETTER_WAS)) { t, _ ->
            Regex("Better was (\\S+?)([.,:;])").find(t)?.let { m ->
                val other = if (m.groupValues[1] == "Qd1") "Qd2" else "Qd1"
                t.replaceRange(m.groups[1]!!.range, other)
            }
        },
        Mutation("number changed", setOf(Reason.FACTS_NUMBERS)) { t, _ ->
            NUMBER.find(t)?.let { m -> t.replaceRange(m.range, (m.value.toInt() + 1).toString()) }
        },
        Mutation("bands swapped", setOf(Reason.FACTS_BANDS)) { t, _ ->
            FROM_TO.find(t)?.takeIf { it.groupValues[1] != it.groupValues[2] }?.let { m ->
                t.replaceRange(m.range, "from ${m.groupValues[2]} to ${m.groupValues[1]}")
            }
        },
        Mutation("term added (a fork)", setOf(Reason.FACTS_TERMS, Reason.SHAPE_LENGTH)) { t, _ ->
            if (Regex("\\bfork").containsMatchIn(t)) null else firstSentenceEnd(t).let { i -> t.substring(0, i) + ", a fork" + t.substring(i) }
        },
        Mutation("term dropped (relative/absolute pin -> pin, en prise -> attacked, zwischenzug -> move)", setOf(Reason.FACTS_TERMS)) { t, _ ->
            when {
                "relative pin" in t -> t.replaceFirst("relative pin", "pin")
                "absolute pin" in t -> t.replaceFirst("absolute pin", "pin")
                "en prise" in t -> t.replaceFirst("en prise", "attacked")
                "zwischenzug" in t -> t.replaceFirst("zwischenzug", "move")
                "forced mate" in t -> t.replaceFirst("forced mate", "attack")
                else -> null
            }
        },
        Mutation("outcome verb added (and wins it)", setOf(Reason.FACTS_OUTCOMES, Reason.FACTS_PIECES, Reason.SHAPE_LENGTH)) { t, _ ->
            firstSentenceEnd(t).let { i -> t.substring(0, i) + " and wins it" + t.substring(i) }
        },
        Mutation("hedge added (probably)", setOf(Reason.BANNED, Reason.SHAPE_LENGTH)) { t, _ ->
            val i = t.indexOf(' ')
            if (i < 0) null else t.substring(0, i) + " probably" + t.substring(i)
        },
        Mutation("praise added (a brilliant idea)", setOf(Reason.BANNED, Reason.SHAPE_LENGTH)) { t, _ -> "$t It is a brilliant idea." },
        Mutation("text doubled", setOf(Reason.SHAPE_LENGTH, Reason.SHAPE_SENTENCES, Reason.SHAPE_REPEAT)) { t, _ -> "$t $t" },
        Mutation("list format", setOf(Reason.SHAPE_FORMAT)) { t, _ -> "- " + t.replace(". ", ".\n- ") },
        Mutation("preamble", setOf(Reason.SHAPE_PREAMBLE)) { t, _ -> "Here is the rewritten text: $t" },
        Mutation("twice as long", setOf(Reason.SHAPE_LENGTH)) { t, _ ->
            val n = t.split(' ').size
            t.dropLast(1) + ", " + List(n) { "and so on" }.joinToString(" ") + "."
        },
        Mutation("classification name prepended", setOf(Reason.REGISTER_CLASS_NAME, Reason.SHAPE_LENGTH)) { t, _ -> "Blunder. $t" },
        Mutation("you -> White", setOf(Reason.FACTS_PLAYERS, Reason.CHARGE)) { t, _ ->
            if (Regex("\\byou\\b").containsMatchIn(t)) t.replace(Regex("\\byou\\b"), "White") else null
        },
        Mutation("negation dropped", setOf(Reason.FACTS_NEGATION, Reason.BANNED, Reason.FACTS_TERMS, Reason.FACTS_OUTCOMES)) { t, _ ->
            NOT.find(t)?.let { m -> t.removeRange(m.range) }
        },
        Mutation("first person added", setOf(Reason.REGISTER_FIRST_PERSON, Reason.SHAPE_LENGTH)) { t, _ ->
            val i = firstSentenceEnd(t)
            t.substring(0, i) + ", as we see" + t.substring(i)
        },
        Mutation("notation in narration (h five -> h5)", setOf(Reason.SHAPE_FORMAT)) { t, s ->
            if (s != RephraseSurface.NARRATION) null
            else SPOKEN_SQUARE.find(t)?.let { m ->
                t.replaceRange(m.range, m.groupValues[1] + (listOf("one", "two", "three", "four", "five", "six", "seven", "eight").indexOf(m.groupValues[2]) + 1))
            }
        },
    )
}
