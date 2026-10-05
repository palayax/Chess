package net.palaya.chessanalyzer.core.text

/**
 * Small English grammar helpers for text that is built from *generated* names (a motif's display
 * name, a piece, a classification) rather than typed by hand.
 *
 * The defect these exist for: `"a " + name` is only right when the name happens to start with a
 * consonant sound. Generated names do not respect that: "in-between move", "absolute pin",
 * "underpromotion", "interference", "x-ray". The rule is therefore one function that every
 * sentence builder goes through, not a patch for the one word that was noticed.
 */
object EnglishGrammar {

    /** "a" or "an" for [phrase], decided on the first *sound* of its first word. */
    fun article(phrase: String): String = if (startsWithVowelSound(phrase)) "an" else "a"

    /** [phrase] with its indefinite article: "an in-between move", "a fork", "an x-ray". */
    fun withArticle(phrase: String): String = "${article(phrase)} $phrase"

    /**
     * True when the first word of [phrase] begins with a vowel *sound*. Spelling is a close but not
     * perfect guide, so three corrections are layered on the vowel-letter rule:
     *  - a word that opens with a single letter and a hyphen is read as that letter's name
     *    ("x-ray" is "ex-ray", "L-shaped" is "el"), and an all-capitals word likewise ("SEE");
     *  - vowel-letter words that open with a consonant sound ("unique", "useful", "one", "euro");
     *  - consonant-letter words with a silent h ("hour", "honest", "heir").
     */
    fun startsWithVowelSound(phrase: String): Boolean {
        val word = phrase.trim().dropWhile { !it.isLetterOrDigit() }.takeWhile { !it.isWhitespace() }
        if (word.isEmpty()) return false
        val first = word.first()

        if (first.isDigit()) {
            val number = word.takeWhile { it.isDigit() }
            // eight, eighty, eleven, eighteen, and their large forms ("8000", "11", "18").
            return number.startsWith("8") || number == "11" || number == "18"
        }

        val letters = word.takeWhile { it.isLetter() }
        if (letters.length == 1) return first.lowercaseChar() in LETTERS_NAMED_WITH_A_VOWEL
        if (letters.length >= 2 && letters.all { it.isUpperCase() }) return first.lowercaseChar() in LETTERS_NAMED_WITH_A_VOWEL

        val lower = letters.lowercase()
        if (SILENT_H.any { lower.startsWith(it) }) return true
        if (first.lowercaseChar() !in "aeiou") return false
        if (CONSONANT_SOUND_EXACT.contains(lower)) return false
        if (CONSONANT_SOUND_PREFIX.any { lower.startsWith(it) } && NOT_THOSE.none { lower.startsWith(it) }) return false
        return true
    }

    /** Letters whose spoken name starts with a vowel sound: A, E, F (eff), H (aitch), I, L (el), M (em), N (en), O, R (ar), S (ess), X (ex). */
    private const val LETTERS_NAMED_WITH_A_VOWEL = "aefhilmnorsx"

    private val SILENT_H = listOf("hour", "honest", "honor", "honour", "heir")

    /** Words that open with a vowel letter but a consonant "y"/"w" sound. */
    private val CONSONANT_SOUND_EXACT = setOf("one", "once", "ewe")

    private val CONSONANT_SOUND_PREFIX = listOf("uni", "use", "usa", "usu", "uti", "uten", "ura", "uri", "eu", "ubiq")

    /** "uni" prefixes that are really "un" + a vowel word: "an unimportant", "an uninvited". */
    private val NOT_THOSE = listOf("unim", "unin", "unid")
}
