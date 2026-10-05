package net.palaya.chessanalyzer.core.narration

import net.palaya.chessanalyzer.core.analysis.TacticSimulation

/**
 * The lead-in line of a missed-tactic walkthrough ("Show me"), assembled in `:core` so that it is
 * a clean sentence whatever the tactic type and whatever its description says.
 *
 * It used to be an Android string template, `"... %1$s starts a line that %2$s."`, with the
 * detector's description dropped into the second slot. That description is a **finished
 * sentence** ("The pawn on g4 cannot be held - taking it wins material."), so the result had a
 * capital letter in the middle of a sentence and two full stops at the end. Grafting one sentence
 * into another cannot be fixed by lowercasing the first letter either: a description may open with
 * a move ("Qxa1+ clears b2...") or a square ("h5 forks..."), where the capital is not optional.
 *
 * So the intro is two sentences, and the description is normalised ([normalisePoint]) into one
 * well-formed sentence of its own before it is handed to the locale as a typed
 * [Sentence.WalkthroughIntro].
 */
object SimulationIntro {

    /** The typed intro for [simulation]; render it with a [NarrationStrings]. */
    fun build(simulation: TacticSimulation): Sentence.WalkthroughIntro {
        val first = simulation.pvSan.firstOrNull()
        val point = normalisePoint(simulation.tactic.description)
        // "h5 starts the line. h5 forks..." says the first move twice. A description that already
        // names the move (the engine-line motifs all open with it) carries the whole lead-in, so the
        // "starts the line" half is dropped rather than repeated.
        val repeated = first != null && point != null && names(point, first)
        return Sentence.WalkthroughIntro(
            firstSan = if (repeated) null else first,
            tactic = simulation.tactic.type,
            point = point
        )
    }

    /**
     * True when [text] opens with the move [san] as a whole token (check and mate marks are not
     * needed to match). Only the opening counts: a pawn move is spelled like a square, and "the pawn
     * on e4" in the middle of a description is not the move e4.
     */
    internal fun names(text: String, san: String): Boolean {
        val bare = san.trimEnd('+', '#')
        if (bare.isEmpty()) return false
        return Regex("^${Regex.escape(bare)}(?![A-Za-z0-9])").containsMatchIn(text)
    }

    /** The intro for [simulation] as text in [strings]' language. */
    fun text(
        simulation: TacticSimulation,
        strings: NarrationStrings = NarrationLocales.default,
        style: NarrationStyle = NarrationStyle.COACH
    ): String = strings.render(build(simulation), style).first()

    /**
     * One sentence: whitespace collapsed, trailing punctuation replaced by exactly one full stop,
     * the first letter capitalised unless the sentence opens with a move or a square (where a
     * lowercase letter is the notation, not a typo). Null when there is nothing to say.
     */
    fun normalisePoint(raw: String?): String? {
        val collapsed = raw?.replace(Regex("\\s+"), " ")?.trim() ?: return null
        val body = collapsed.trimEnd { it in TRAILING }.replace(Regex("\\.{2,}"), ".")
        if (body.isEmpty()) return null
        val opened = if (body.first().isLowerCase() && !NOTATION_START.containsMatchIn(body)) {
            body.first().uppercaseChar() + body.substring(1)
        } else body
        return "$opened."
    }

    private const val TRAILING = ".!?;:, "

    /** "h5 forks...", "exd5 wins...", "O-O tucks...": a lowercase first token that is notation. */
    private val NOTATION_START = Regex("^([a-h][1-8x]|O-O)")
}
