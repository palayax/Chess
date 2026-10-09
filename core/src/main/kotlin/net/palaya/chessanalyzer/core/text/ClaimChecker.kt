package net.palaya.chessanalyzer.core.text

import java.text.Normalizer

/**
 * The C2 claim checker (docs/LLM_REPHRASE_DESIGN.md §5): a **comparator**, not a verifier.
 *
 * A rephrased text is free prose, so no C1 template can parse it. What it can be held to is closure over
 * the facts of the original, which C1 has already verified: if the candidate carries exactly the
 * original's moves, squares, pieces on squares, sides, numbers, professional terms, evaluation bands,
 * outcome verbs and names, and adds no hedge, praise or claim word the original lacked, it cannot claim
 * more than the original did. Any doubt is a rejection, and a rejection costs nothing but the rewording:
 * the caller shows the original.
 *
 * Pure Kotlin, deterministic, host-tested (`ClaimCheckerTest`). `scripts/audit_commentary.py rephrase`
 * is an independent Python re-implementation of the same rules; the two must agree on every recorded
 * text and every mutation (`mutate-rephrase`).
 */
object ClaimChecker {

    /** Why a candidate was rejected. Logged and counted by reason (design §5.5). */
    enum class Reason {
        EMPTY,
        SHAPE_FORMAT, SHAPE_PREAMBLE, SHAPE_LENGTH, SHAPE_SENTENCES, SHAPE_REPEAT,
        REGISTER_CLASS_NAME, REGISTER_EXCLAMATION, REGISTER_FIRST_PERSON,
        BANNED,
        FACTS_MOVES, FACTS_SQUARES, FACTS_PIECE_SQUARES, FACTS_PIECES, FACTS_PLAYERS, FACTS_NUMBERS,
        FACTS_TERMS, FACTS_BANDS, FACTS_OUTCOMES, FACTS_NAMES, FACTS_NEGATION, FACTS_HYPOTHETICAL, FACTS_ORDER,
        BETTER_WAS, CHARGE,
    }

    sealed interface Verdict {
        /** The candidate says the same facts in other words. */
        data object Accepted : Verdict

        /** The candidate is the original (after normalisation): the model's "cannot keep the facts" answer. */
        data object Unchanged : Verdict

        data class Rejected(val reason: Reason, val detail: String) : Verdict
    }

    /**
     * The fact tokens of one text. Multisets are sorted lists; [players] and [bands] keep their order
     * (a swapped "from X to Y" or a swapped subject is a different claim).
     */
    data class Facts(
        /** SAN moves with a piece letter, a capture, a promotion or castling, with their check marks. */
        val moves: List<String>,
        /** Bare squares, including pawn pushes ("e4" is both), sorted. */
        val squares: List<String>,
        /** "knight@f6" for every "knight on f6". */
        val pieceSquares: Set<String>,
        /** Piece words, singular, sorted. */
        val pieces: List<String>,
        /** WHITE / BLACK / YOU / OPPONENT in order of appearance, consecutive repeats collapsed. */
        val players: List<String>,
        val numbers: List<String>,
        val terms: Map<String, Int>,
        val bands: List<String>,
        val outcomes: Map<String, Int>,
        val names: Set<String>,
        /** The move after "Better was", or null. */
        val betterWas: String?,
        /** "WHO>SAN" for every charge ("Now White can play Nxg7+", "This hands you Qb3"). */
        val charges: List<String>,
        /** How many negations the text has ("not", "no", "nothing", "n't", ...). */
        val negations: Int,
        /**
         * How many markers say a move is the alternative, not what happened ("Instead,", "was the move", "the
         * engine's line", "would", "if"): drop one and "Rook takes the pawn on h seven was the move" becomes "The rook
         * takes the pawn on h seven", a move that was never played (found in the P2a run).
         */
        val hypotheticals: Int = 0,
        /**
         * The order of what happens: every move or square token, outcome-verb class and check/mate word, as they
         * appear. "This hands White d4, which hits the loose bishop on c5" and "This hits the loose bishop on c5,
         * giving White d4" carry the same tokens but not the same claim (who hits the bishop): found in the P2a run.
         */
        val order: List<String> = emptyList(),
    )

    // -------------------------------------------------------------------------------------------
    // Normalisation
    // -------------------------------------------------------------------------------------------

    private val WHITESPACE = Regex("\\s+")
    private val SPOKEN_DIGITS = mapOf(
        "one" to "1", "two" to "2", "three" to "3", "four" to "4",
        "five" to "5", "six" to "6", "seven" to "7", "eight" to "8"
    )
    private val SPOKEN_SQUARE = Regex("\\b([a-h]) (one|two|three|four|five|six|seven|eight)\\b")

    /** NFC, straight quotes, one space, trimmed, a final full stop if the text has no end mark. */
    fun normalize(text: String): String {
        var t = Normalizer.normalize(text, Normalizer.Form.NFC)
            .replace('‘', '\'').replace('’', '\'')
            .replace('“', '"').replace('”', '"')
        t = WHITESPACE.replace(t, " ").trim()
        if (t.isNotEmpty() && t.last() !in ".!?") t = "$t."
        return t
    }

    /** Narration says squares ("h five"); fold them to "h5" so both surfaces compare alike (§5.2). */
    fun foldSpokenSquares(text: String): String =
        SPOKEN_SQUARE.replace(text) { m -> m.groupValues[1] + SPOKEN_DIGITS.getValue(m.groupValues[2]) }

    // -------------------------------------------------------------------------------------------
    // Fact extraction
    // -------------------------------------------------------------------------------------------

    private const val NB = "(?<![A-Za-z0-9])"
    private const val NA = "(?![A-Za-z0-9])"

    /** SAN with a piece letter, a pawn capture, a promotion or castling; check marks kept. */
    private val MOVE = Regex("$NB(O-O-O|O-O|[KQRBN][a-h]?[1-8]?x?[a-h][1-8]|[a-h]x[a-h][1-8](?:=[QRBN])?|[a-h][1-8]=[QRBN])([+#]?)(?![A-Za-z0-9=])")
    private val SQUARE = Regex("$NB([a-h][1-8])([+#]?)$NA")
    private val TOKEN = Regex(MOVE.pattern + "|" + SQUARE.pattern)
    private val PIECE_ON_SQUARE = Regex("\\b(pawn|knight|bishop|rook|queen|king)s? on ([a-h][1-8])\\b", RegexOption.IGNORE_CASE)
    private val PIECE_WORD = Regex("\\b(pawn|knight|bishop|rook|queen|king)s?\\b", RegexOption.IGNORE_CASE)
    private val PLAYER = Regex("\\b(your opponent's|your opponent|White's|Black's|White|Black|yours|your|you)\\b|\\b(Your opponent's|Your opponent|Yours|Your|You)\\b")
    private val DIGITS = Regex("$NB(\\d+(?:\\.\\d+)?)$NA")
    private val SPELLED = mapOf(
        "two" to "2", "three" to "3", "four" to "4", "five" to "5", "six" to "6", "seven" to "7", "eight" to "8",
        "nine" to "9", "ten" to "10", "eleven" to "11", "twelve" to "12", "thirteen" to "13", "fourteen" to "14",
        "fifteen" to "15", "sixteen" to "16", "seventeen" to "17", "eighteen" to "18", "nineteen" to "19",
        "twenty" to "20", "half" to "0.5", "twice" to "2x", "double" to "2x"
    )
    private val SPELLED_RE = Regex("\\b(" + SPELLED.keys.joinToString("|") + ")\\b(?! (?:attack|check))", RegexOption.IGNORE_CASE)

    /** Professional terms (COMMENTARY_STYLE §1) and the words that carry the same claim; counted (a term said twice is two claims). */
    private val TERMS: List<Pair<String, Regex>> = listOf(
        "fork" to "\\bfork(?:s|ed|ing)?\\b",
        "pawn fork" to "\\bpawn fork(?:e?s)?\\b",
        "double attack" to "\\bdouble attack(?:e?s)?\\b",
        "pin" to "\\bpin(?:s|ned|ning)?\\b",
        "absolute pin" to "\\babsolute pin(?:e?s)?\\b",
        "relative pin" to "\\brelative pin(?:e?s)?\\b",
        "skewer" to "\\bskewer(?:s|ed|ing)?\\b",
        "discovered" to "\\b(?:discovered|uncover(?:s|ed|ing)?|unmasked)\\b",
        "discovered check" to "\\bdiscovered check(?:e?s)?\\b",
        "double check" to "\\bdouble check(?:e?s)?\\b",
        "check" to "\\bcheck(?:s)?\\b",
        "mate" to "\\b(?:check)?mate(?:s|d)?\\b|\\bmating\\b",
        "checkmate" to "\\bcheckmate(?:e?s)?\\b",
        "forced mate" to "\\bforced mates?\\b|\\bmate in\\b",
        "en prise" to "\\ben prise\\b",
        "undefended" to "\\b(?:undefended|unprotected|loose|hanging|no defender|nothing defending|nothing guarding)\\b",
        "trapped" to "\\b(?:trap(?:s|ped)?|no safe square)\\b",
        "the exchange" to "\\bthe exchange\\b",
        "zwischenzug" to "\\b(?:zwischenzug(?:s)?|in-between move)\\b",
        "overloaded" to "\\boverload(?:ed|s)?\\b",
        "desperado" to "\\bdesperado(?:e?s)?\\b",
        "back rank" to "\\bback[- ]rank\\b",
        "smothered" to "\\bsmothered\\b",
        "only move" to "\\bonly (?:legal )?move\\b|\\ban only move\\b",
        "sacrifice" to "\\b(?:sacrific(?:e|es|ed|ing)|offered|offers? (?:the|a|an|its))\\b",
        "deflection" to "\\bdeflect(?:s|ed|ion|ing)?\\b",
        "decoy" to "\\b(?:decoy(?:s)?|lure(?:s|d)?)\\b",
        "clearance" to "\\b(?:clearance|clears|cleared)\\b",
        "removing the defender" to "\\b(?:removing the defender|takes away)\\b",
        "interference" to "\\binterference\\b|\\bcuts?\\b.{0,40}?\\boff\\b",
        "greek gift" to "\\bgreek gift(?:e?s)?\\b",
        "windmill" to "\\bwindmill(?:e?s)?\\b",
        "promotion" to "\\b(?:promot(?:e|es|ed|ing|ion)|underpromot(?:e|es|ed|ion))\\b",
        "underpromotion" to "\\bunderpromot(?:e|es|ed|ion)\\b",
        "winning position" to "\\bwinning position(?:e?s)?\\b",
        "decisive advantage" to "\\bdecisive advantage(?:e?s)?\\b",
        "more attackers" to "\\b(?:more attackers than defenders|more often than it is defended)\\b",
        "turning point" to "\\bturning point(?:e?s)?\\b",
        "theory" to "\\b(?:theory|book)\\b",
        "castles" to "\\bcastl(?:e|es|ed|ing)\\b",
        "kingside" to "\\bkingside\\b",
        "queenside" to "\\bqueenside\\b",
        "engine" to "\\bengine(?:'s|s)?\\b",
        "material" to "\\bmaterial\\b",
        "accuracy" to "\\b(?:accuracy|percent)\\b",
    ).map { (k, v) -> k to Regex(v, RegexOption.IGNORE_CASE) }

    /** The evaluation bands in words, longest first; the ordered list must be equal. */
    private val BAND = Regex(
        "\\b(decisively winning|decisively lost|completely winning|completely lost|completely won|clearly better|" +
            "slightly better|clearly worse|slightly worse|about level|the evaluation moves|winning|losing|equal)\\b",
        RegexOption.IGNORE_CASE
    )

    /** Outcome verbs by class (§5.2), counted: a second "wins" is a second claim. */
    private val OUTCOMES: List<Pair<String, Regex>> = listOf(
        "WIN" to "\\b(?:wins?|won(?!'t)|picks? up|picked up|picking up|winning (?:a|an|the)|gains?|gained|gaining|collects?|collected|collecting|nets?|netted|netting)\\b",
        "LOSE" to "\\b(?:loses?|lost|losing (?:a|an|the)|drops?|dropped|dropping|hangs?|hung|gives? away|giving away|gave away|throws?(?: [a-z]+){0,6} away|throwing(?: [a-z]+){0,6} away|costs?|costing)\\b",
        "THREAT" to "\\bthreat(?:s|en|ens|ened|ening)?\\b",
        "ATTACK" to "\\b(?:attack(?:s|ed|ing|er|ers)?|hits?|hitting|piles? up|piled up|piling up)\\b",
        "DEFEND" to "\\b(?:saves?|saved|saving|holds?|held|holding|defend(?:s|ed|ing|er|ers)?|defen[cs]e|guard(?:s|ed|ing)?|protect(?:s|ed|ing)?)\\b",
        "CAPTURE" to "\\b(?:takes?|took|taking|captur(?:e|es|ed|ing)|grabs?|grabbed|grabbing)\\b(?!\\s+(?:White|Black|you|your)\\b)",
        "FORCE" to "\\bforc(?:e|es|ed|ing)\\b",
    ).map { (k, v) -> k to Regex(v, RegexOption.IGNORE_CASE) }

    private val SENTENCE_SPLIT = Regex("(?<=[.!?])\\s+")
    private val WORD = Regex("[A-Za-z][A-Za-z'-]*")
    private val CHARGE_VERB = Regex("\\b(?:lets|let|hands|can play|allows)\\b")
    private val BETTER_WAS = Regex("\\bBetter was ([^\\s,.:;]+?)(?=[,.:;]?(?:\\s|$))")

    /**
     * Words a sentence may open with although they are not names. A capitalised word that is neither one of
     * these nor present in the original (any case) is a name the model added.
     */
    private val STARTERS = setOf(
        "a", "an", "the", "this", "that", "these", "those", "it", "its", "and", "but", "so", "now", "then", "here",
        "there", "after", "before", "with", "without", "in", "on", "at", "by", "for", "from", "to", "of", "as", "if",
        "when", "while", "instead", "still", "both", "each", "every", "one", "two", "three", "four", "five", "six",
        "seven", "eight", "nine", "ten", "only", "no", "not", "nothing", "all", "any", "what", "which", "where",
        "who", "how", "yet", "also", "even", "just", "once", "next", "first", "last", "back", "over", "out", "up",
        "down", "into", "through", "since", "because", "though", "although", "until", "between", "against",
        "is", "was", "are", "were", "be", "has", "had", "have", "can", "will", "would", "does", "did", "do",
        "here's", "that's", "it's", "there's", "let", "let's", "from", "move", "moves", "playing", "plays", "having",
        "again", "later", "earlier", "soon", "shortly", "together", "suddenly", "otherwise", "rather", "thus",
        "therefore", "hence", "indeed", "already", "far", "much", "more", "less", "most", "least", "some", "such",
    )

    private val NOT_NAMES = setOf("White", "Black", "I", "You", "Your", "Yours")

    fun facts(text: String, surface: RephraseSurface): Facts {
        var t = normalize(text)
        if (surface == RephraseSurface.NARRATION) t = foldSpokenSquares(t)

        val moves = MOVE.findAll(t).map { it.groupValues[1] + it.groupValues[2] }.sorted().toList()
        val noMoves = MOVE.replace(t, " ")
        val squares = SQUARE.findAll(noMoves).map { it.groupValues[1] + it.groupValues[2] }.sorted().toList()
        val noTokens = SQUARE.replace(noMoves, " ")

        val pieceSquares = PIECE_ON_SQUARE.findAll(t).map { it.groupValues[1].lowercase() + "@" + it.groupValues[2] }.toSet()
        val pieces = PIECE_WORD.findAll(t).map { it.groupValues[1].lowercase() }.sorted().toList()

        val players = ArrayList<String>()
        for (m in PLAYER.findAll(t)) {
            val w = m.value.lowercase()
            val p = when {
                w.startsWith("your opponent") -> "OPPONENT"
                w.startsWith("you") -> "YOU"
                w.startsWith("white") -> "WHITE"
                else -> "BLACK"
            }
            if (players.lastOrNull() != p) players += p
        }

        val numbers = (DIGITS.findAll(noTokens).map { it.groupValues[1] } +
            SPELLED_RE.findAll(noTokens).map { SPELLED.getValue(it.groupValues[1].lowercase()) })
            .sorted().toList()

        val terms = TERMS.associate { (k, r) -> k to r.findAll(t).count() }.filterValues { it > 0 }.toSortedMap()
        val bands = BAND.findAll(t).map { it.groupValues[1].lowercase() }.toList()
        val outcomes = OUTCOMES.associate { (k, r) -> k to r.findAll(t).count() }.filterValues { it > 0 }.toSortedMap()
        val names = names(noMoves)
        val better = BETTER_WAS.find(t)?.groupValues?.get(1)

        // A charge: the first move the sentence names, and the side named last before it.
        val charges = sentences(t).filter { CHARGE_VERB.containsMatchIn(it) }.mapNotNull { s ->
            val token = TOKEN.find(s) ?: return@mapNotNull null
            val who = PLAYER.findAll(s.substring(0, token.range.first)).lastOrNull()?.value ?: return@mapNotNull null
            playerKey(who) + ">" + token.value
        }
        val negations = NEGATION.findAll(t).count()
        val hypotheticals = HYPOTHETICAL.findAll(t).count()
        val events = ArrayList<Pair<Int, String>>()
        TOKEN.findAll(t).forEach { events += it.range.first to it.value }
        for ((k, r) in OUTCOMES) r.findAll(t).forEach { events += it.range.first to k }
        CHECK_OR_MATE.findAll(t).forEach { events += it.range.first to it.value.lowercase() }
        val order = events.sortedBy { it.first }.map { it.second }

        return Facts(moves, squares, pieceSquares, pieces, players, numbers, terms, bands, outcomes, names, better, charges, negations, hypotheticals, order)
    }

    private fun playerKey(word: String): String {
        val w = word.lowercase()
        return when {
            w.startsWith("your opponent") -> "OPPONENT"
            w.startsWith("you") -> "YOU"
            w.startsWith("white") -> "WHITE"
            else -> "BLACK"
        }
    }

    /**
     * Proper names: a capitalised word that is not the first word of its sentence, or that is followed by
     * another capitalised word ("Adolf Anderssen", "King's Gambit Accepted"). Moves are removed first.
     */
    private fun names(text: String): Set<String> {
        val out = HashSet<String>()
        for (s in sentences(text)) {
            val words = WORD.findAll(s).map { it.value.trimEnd('\'', '-').removeSuffix("'s") }.filter { it.isNotEmpty() }.toList()
            for ((i, w) in words.withIndex()) {
                if (!w[0].isUpperCase() || w in NOT_NAMES || w.length < 2) continue
                val nextCap = words.getOrNull(i + 1)?.let { it[0].isUpperCase() && it !in NOT_NAMES } == true
                if (i > 0 || nextCap) out += w
            }
        }
        return out
    }

    fun sentences(text: String): List<String> =
        SENTENCE_SPLIT.split(text.trim()).map { it.trim() }.filter { it.isNotEmpty() }

    private fun words(text: String): List<String> = text.split(WHITESPACE).filter { it.isNotBlank() }

    // -------------------------------------------------------------------------------------------
    // The rules
    // -------------------------------------------------------------------------------------------

    /**
     * Hedges, praise, judgement and the C1 banned wordings: a candidate may use one only as often as the
     * original does (the original is verified, so its own words are proven; any extra one is a new claim).
     */
    private val BANNED: List<Pair<String, Regex>> = (
        CommentaryVocabulary.C1_BANNED.map { it to Regex("\\b" + Regex.escape(it) + "\\b", RegexOption.IGNORE_CASE) } +
            listOf(
                "likely", "perhaps", "maybe", "possibly", "could", "should", "must", "always", "never", "obviously",
                "brilliant", "beautiful", "crushing", "devastating", "dominant", "initiative", "tempo", "development",
                "control", "pressure", "positional", "strong", "stronger", "strongest", "weak", "weaker", "weakest",
                "powerful", "excellent", "great", "good", "bad", "terrible", "awful", "mistake", "mistakes", "blunder",
                "blunders", "error", "errors", "inaccuracy", "inaccuracies", "accurate", "inaccurate", "precise",
                "imprecise", "careless", "clever", "nice", "fantastic", "superb", "impressive", "dubious", "risky",
                "dangerous", "aggressive", "passive", "crucial", "critical", "decisive", "decisively", "serious",
                "huge", "massive", "best", "worst", "better", "worse", "fine", "solid", "sound", "reasonable", "slip",
                "wrong", "correct", "right", "winner", "loser", "advantage", "edge", "lead", "ahead", "behind",
                "seems", "appears", "probably", "certainly", "surely", "definitely", "really", "very",
                "simply", "easily", "completely", "totally", "entirely", "fully", "finally", "unfortunately", "luckily",
                "sadly", "happily", "amazing", "incredible", "remarkable", "elegant", "subtle", "quiet",
                "only", "just", "all", "every", "meanwhile", "however",
            ).map { it to Regex("\\b$it\\b", RegexOption.IGNORE_CASE) } +
            listOf(
                "clearly (adverb)" to Regex("\\bclearly\\b(?! (?:better|worse))", RegexOption.IGNORE_CASE),
                // C1 removed "allowed"; any form of "allow" is a charge the original did not make.
                "allow*" to Regex("\\ballow(?:s|ed|ing)?\\b", RegexOption.IGNORE_CASE),
                // "sets up a check" is a threat the original did not claim ("with check" is a fact, "sets up" a plan).
                "set up" to Regex("\\bset(?:s|ting)? up\\b", RegexOption.IGNORE_CASE),
                "wins the <piece> on" to CommentaryVocabulary.WINS_A_PIECE_ON_A_SQUARE,
                "material up/down" to Regex(
                    "\\b(?:pawn|knight|bishop|rook|queen|piece|exchange|material)s? (?:up|down|ahead|behind)\\b",
                    RegexOption.IGNORE_CASE
                ),
            )
        )

    private val FIRST_PERSON = Regex("\\b(?:[Ww]e|[Uu]s|[Oo]urs?|[Ll]et's|[Ww]e're|[Ww]e've|[Ww]e'll|I|I'm|I'd|[Mm]e|[Mm]y)\\b")

    private val CHECK_OR_MATE = Regex("\\b(?:checkmate|check|mate)\\b", RegexOption.IGNORE_CASE)

    /** Alternative-move markers (see [Facts.hypotheticals]); the count must be equal. */
    private val HYPOTHETICAL = Regex(
        "\\b(?:instead|was the move|the move (?:was|is)|better was|line|would|if|otherwise|rather than)\\b",
        RegexOption.IGNORE_CASE
    )

    /** Negations: a dropped or an added "not" flips a claim, so the count must be equal. */
    private val NEGATION = Regex(
        "\\b(?:not|no|nothing|none|never|without|nobody|neither|nor|cannot|nowhere)\\b|n't\\b",
        RegexOption.IGNORE_CASE
    )
    private val PREAMBLE = Regex(
        "^(?:here is|here's|sure|certainly|of course|okay|ok|rewritten|rewrite|polished|output|text|answer|result)\\b",
        RegexOption.IGNORE_CASE
    )
    private val LIST_MARKER = Regex("^(?:[-•]|\\d+[.)])\\s")
    private const val FORBIDDEN_CHARS ="#*\"`_[]{}<>|\\~^=@$%&"

    /**
     * Checks [candidate] against [original] (the verified text) on [surface]. [candidate] is the model's
     * output after [RephrasePrompt.cleanOutput]; a newline left in it is a format failure.
     */
    fun check(original: String, candidate: String, surface: RephraseSurface): Verdict {
        if (candidate.isBlank()) return Verdict.Rejected(Reason.EMPTY, "empty")
        if ('\n' in candidate.trim() || '\r' in candidate.trim()) return Verdict.Rejected(Reason.SHAPE_FORMAT, "newline")
        val o = normalize(original)
        val c = normalize(candidate)
        if (o == c) return Verdict.Unchanged

        // SHAPE
        for (ch in c) {
            val allowed = (ch in ' '..'~' && ch !in FORBIDDEN_CHARS) || ch == '–' || ch in o
            if (!allowed) return Verdict.Rejected(Reason.SHAPE_FORMAT, "character U+%04X".format(ch.code))
        }
        if (LIST_MARKER.containsMatchIn(c)) return Verdict.Rejected(Reason.SHAPE_FORMAT, "list marker")
        val preamble = PREAMBLE.find(c)?.value?.lowercase()
        if (preamble != null && preamble != PREAMBLE.find(o)?.value?.lowercase()) return Verdict.Rejected(Reason.SHAPE_PREAMBLE, c.take(20))
        if (Regex("\\brewr[io]t").containsMatchIn(c.lowercase()) && !Regex("\\brewr[io]t").containsMatchIn(o.lowercase())) {
            return Verdict.Rejected(Reason.SHAPE_PREAMBLE, "mentions the rewrite")
        }
        // Narration is heard: a square or a move written as notation ("h5", "Nf3") would be read out as
        // letters by the voice (NotationGuard), even though it folds to the same fact.
        if (surface == RephraseSurface.NARRATION && TOKEN.findAll(c).count() > TOKEN.findAll(o).count()) {
            return Verdict.Rejected(Reason.SHAPE_FORMAT, "notation in narration: ${TOKEN.find(c)?.value}")
        }
        val ow = words(o).size
        val cw = words(c).size
        if (cw < 0.7 * ow || cw > 1.3 * ow || cw > ow + 12) return Verdict.Rejected(Reason.SHAPE_LENGTH, "$cw words for $ow")
        val os = sentences(o)
        val cs = sentences(c)
        if (cs.isEmpty() || cs.size > os.size + 1) return Verdict.Rejected(Reason.SHAPE_SENTENCES, "${cs.size} sentences for ${os.size}")
        if (cs.map { it.lowercase() }.toSet().size < cs.size && os.map { it.lowercase() }.toSet().size == os.size) {
            return Verdict.Rejected(Reason.SHAPE_REPEAT, "a sentence repeats")
        }

        // REGISTER
        val first = WORD.find(c)?.value
        if (first != null && first in CommentaryVocabulary.CLASSIFICATION_NAMES && WORD.find(o)?.value != first) {
            return Verdict.Rejected(Reason.REGISTER_CLASS_NAME, first)
        }
        if (surface == RephraseSurface.CARD && '!' in c) return Verdict.Rejected(Reason.REGISTER_EXCLAMATION, "!")
        if (c.count { it == '!' } > o.count { it == '!' }) return Verdict.Rejected(Reason.REGISTER_EXCLAMATION, "!")
        if (FIRST_PERSON.findAll(c).count() > FIRST_PERSON.findAll(o).count()) {
            return Verdict.Rejected(Reason.REGISTER_FIRST_PERSON, FIRST_PERSON.find(c)!!.value)
        }

        // BANNED (count rule)
        val oFolded = if (surface == RephraseSurface.NARRATION) foldSpokenSquares(o) else o
        val cFolded = if (surface == RephraseSurface.NARRATION) foldSpokenSquares(c) else c
        for ((word, r) in BANNED) {
            if (r.findAll(cFolded).count() > r.findAll(oFolded).count()) return Verdict.Rejected(Reason.BANNED, word)
        }

        val fo = facts(o, surface)
        val fc = facts(c, surface)

        // "Better was X": present iff in the original, same move, once, and the last sentence.
        if (fo.betterWas != null || fc.betterWas != null) {
            val count = Regex("\\bBetter was\\b").findAll(c).count()
            if (fo.betterWas != fc.betterWas || count != 1 || !cs.last().startsWith("Better was ${fo.betterWas}")) {
                return Verdict.Rejected(Reason.BETTER_WAS, "${fo.betterWas} -> ${fc.betterWas} ($count, last: ${cs.last()})")
            }
        }

        // Charges: the beneficiary of each charge is still the side named right before its move.
        for (charge in fo.charges) {
            val who = charge.substringBefore('>')
            val san = charge.substringAfter('>')
            val ok = cs.any { s ->
                val at = Regex("$NB${Regex.escape(san)}(?![A-Za-z0-9+#])").find(s)?.range?.first ?: return@any false
                val before = PLAYER.findAll(s.substring(0, at)).lastOrNull()?.value ?: return@any false
                playerKey(before) == who
            }
            if (!ok) return Verdict.Rejected(Reason.CHARGE, charge)
        }

        // FACTS
        fun differ(reason: Reason, a: Any?, b: Any?): Verdict.Rejected? = if (a != b) Verdict.Rejected(reason, "$a -> $b") else null
        differ(Reason.FACTS_MOVES, fo.moves, fc.moves)?.let { return it }
        differ(Reason.FACTS_SQUARES, fo.squares, fc.squares)?.let { return it }
        differ(Reason.FACTS_PIECE_SQUARES, fo.pieceSquares, fc.pieceSquares)?.let { return it }
        differ(Reason.FACTS_PIECES, fo.pieces, fc.pieces)?.let { return it }
        differ(Reason.FACTS_PLAYERS, fo.players, fc.players)?.let { return it }
        differ(Reason.FACTS_NUMBERS, fo.numbers, fc.numbers)?.let { return it }
        differ(Reason.FACTS_TERMS, fo.terms, fc.terms)?.let { return it }
        differ(Reason.FACTS_BANDS, fo.bands, fc.bands)?.let { return it }
        differ(Reason.FACTS_OUTCOMES, fo.outcomes, fc.outcomes)?.let { return it }
        differ(Reason.FACTS_NAMES, fo.names, fc.names)?.let { return it }
        differ(Reason.FACTS_NEGATION, fo.negations, fc.negations)?.let { return it }
        differ(Reason.FACTS_HYPOTHETICAL, fo.hypotheticals, fc.hypotheticals)?.let { return it }
        differ(Reason.FACTS_ORDER, fo.order, fc.order)?.let { return it }
        // A capitalised word the original never uses (in any case) is a name the model added.
        val oLower = WORD.findAll(oFolded).map { it.value.lowercase().removeSuffix("'s") }.toSet()
        for (s in sentences(MOVE.replace(cFolded, " "))) {
            for (w in WORD.findAll(s).map { it.value.removeSuffix("'s") }) {
                if (w[0].isUpperCase() && w !in NOT_NAMES && w.lowercase() !in oLower && w.lowercase() !in STARTERS) {
                    return Verdict.Rejected(Reason.FACTS_NAMES, w)
                }
            }
        }
        return Verdict.Accepted
    }
}
