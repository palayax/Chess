package net.palaya.chessanalyzer.core.text

/**
 * The closed vocabulary of the card text (docs/COMMENTARY_STYLE.md §1), as the regular expressions C1's
 * `CommentaryClaimsTest` proves every sentence against. Moved here from that test (C2,
 * docs/LLM_REPHRASE_DESIGN.md §5.1) so the rephrase [ClaimChecker] reads the same words; the test imports
 * them unchanged, so its template catalogue and this object cannot drift apart.
 *
 * These are patterns, not claims: a sentence that matches a template is still verified against the board
 * by `CommentaryClaimsTest` and `scripts/audit_commentary.py`.
 */
object CommentaryVocabulary {

    /** Who a card is about: the colour with no side chosen, "you" / "your opponent" with one. */
    const val WHO = "(?:you|your opponent|White|Black)"

    const val PIECE = "(?:pawn|knight|bishop|rook|queen|king)"

    const val ON = "the $PIECE on [a-h][1-8]"

    const val MATERIAL = "(?:a queen|a rook|a piece|a pawn|the exchange)"

    /** The nine evaluation bands of ANALYSIS_SPEC §9, as `CommentaryGenerator.standingWords` says them. */
    const val BANDS = "(?:decisively winning|winning|clearly better|slightly better|about level|slightly worse|clearly worse|losing|decisively lost)"

    /** The nine band words, best first. */
    val BAND_WORDS: List<String> = listOf(
        "decisively winning", "winning", "clearly better", "slightly better", "about level",
        "slightly worse", "clearly worse", "losing", "decisively lost"
    )

    /** The verb phrases a found / allowed / better sentence may carry (the vocabulary table). */
    val PHRASES: String = listOf(
        "is checkmate", "is mate", "delivers checkmate",
        "(?:starts|begins) a forced mate(?: in \\d+)?", "sets a forced mate in \\d+ in motion",
        "is a back-rank mate", "is mate on the back rank",
        "is a smothered mate(?:: the king is boxed in by its own pieces)?",
        "forks $ON(?:, $ON)* and $ON(?: with a pawn)?", "is a (?:pawn )?fork, hitting $ON(?:, $ON)* and $ON(?: at once)?", "lands a fork on $ON(?:, $ON)* and $ON",
        "attacks $ON(?:, $ON)* and $ON at once", "creates a double attack on $ON(?:, $ON)* and $ON",
        "pins $ON to $ON", "puts $ON in an absolute pin against the king on [a-h][1-8]", "ties $ON to $ON with a relative pin",
        "skewers $ON, with $ON behind it", "is a skewer: it attacks $ON, and $ON stands behind it on the same line",
        "uncovers $ON, which now attacks $ON", "is a discovered attack: $ON is unmasked against $ON",
        "gives check by uncovering $ON", "is a discovered check from $ON",
        "gives double check", "is a double check: only a king move can answer it",
        "attacks the undefended $PIECE on [a-h][1-8]", "hits the loose $PIECE on [a-h][1-8]", "attacks $ON, which is en prise",
        "leaves $ON undefended, with $ON attacking it", "leaves $ON en prise to $ON",
        "(?:attacks|hits) $ON with an? $PIECE", "leaves $ON attacked by an? $PIECE",
        "attacks $ON more often than it is defended", "piles up on $ON: more attackers than defenders", "leaves $ON attacked more often than it is defended",
        "leaves $ON with no safe square", "traps $ON: every square it can reach loses material",
        "(?:wins|picks up) $MATERIAL",
        "promotes (?:the pawn )?to an? $PIECE", "underpromotes to an? $PIECE", "is an underpromotion, to an? $PIECE",
        "is a desperado: the $PIECE was lost anyway, so it takes $ON on the way out",
        "threatens \\S+, mate on the back rank", "sets up a back-rank mate: \\S+ is the threat",
        // the engine's line (the detector's own descriptions, opened with the move)
        "deflects $ON away from guarding [a-h][1-8]", "drags $ON off [^,]+, and \\S+ follows", "clears [a-h][1-8] so that \\S+ can come through",
        "takes away $ON, which was what held [a-h][1-8]; \\S+ follows", "lures the $PIECE to [a-h][1-8], and \\S+ mates", "drags the $PIECE to [a-h][1-8], where \\S+ forks it",
        "cuts $ON off from [a-h][1-8], and \\S+ follows", "is the Greek gift: the knight comes to [a-h][1-8] and the queen to [a-h][1-8] behind it",
        "sets up a windmill: the rook keeps coming back to [a-h][1-8] with check, taking material each time round",
        "is a zwischenzug: it comes first, and \\S+ follows", "exploits the overloaded $PIECE on [a-h][1-8], which cannot guard [a-h][1-8] and [a-h][1-8] at once"
    ).joinToString("|") { "(?:$it)" }

    private const val SAN = "\\S+"

    /** Every sentence a card may say, each anchored to one whole sentence (C1's template catalogue). */
    val TEMPLATES: List<Regex> = listOf(
        "$SAN (?:follows known opening theory|is still opening theory|stays in book)",
        "(?:$SAN was the only legal move|$SAN was forced: the only legal move|No choice here: $SAN was the only legal move)",
        "$SAN (?:matches the engine's top choice|is the engine's first choice|is the top engine move here)",
        "$SAN (?:is very close to the best move|is nearly the engine's top choice|comes within a whisker of the best move)",
        "$SAN (?:is a sound move|is a reasonable move|is a solid choice)",
        "$SAN (?:was the only move that kept things on track|is the only move here: the next-best option gives up real ground|is an only move, and nothing else keeps the position on track)",
        "$SAN (?:is a sacrifice: it offers|sacrifices) $ON", "$SAN offers $ON: a sacrifice the engine rates among the best moves here",
        "$SAN leaves $ON open to capture, and the engine still rates it among the best moves", "$SAN is among the engine's best moves here",
        "$SAN (?:gives back ground|is not the most precise|concedes a little ground)",
        "$SAN (?:gives up real ground|goes wrong|lets the position slip)",
        "$SAN (?:gives up a big chunk of the position|is a serious slip|throws a big chunk of the position away)",
        "(?:This|It) (?:$PHRASES)", "(?:In the engine's line, |The engine's line shows it: )$SAN (?:$PHRASES)",
        "(?:This lets $WHO play $SAN|Now $WHO can play $SAN|This hands $WHO $SAN)(?:, which|; in the engine's line it) (?:$PHRASES)",
        "This allows a forced mate(?: in \\d+)?", "This walks into a forced mate in \\d+", "After this, $WHO has a forced mate in \\d+",
        "That takes $WHO from $BANDS to $BANDS", "The position swings from $BANDS to $BANDS for $WHO", "From $BANDS to $BANDS in one move: that is what this cost $WHO",
        "Better was $SAN", "Better was $SAN(?:, which|: it|; in the engine's line it|: in the engine's line it) (?:$PHRASES)",
        "Better was $SAN(?:, forcing mate in \\d+|, with a forced mate in \\d+|: mate in \\d+ was on the board)",
        "Better was $SAN(?:, keeping|, which holds on to|: it keeps) (?:a decisive advantage|a winning position)",
        "A forced mate in \\d+ was on the board", "A decisive advantage was on the board"
    ).map { Regex("^(?:$it)\\.$") }

    /**
     * The wordings C1 found unprovable and removed for good (`CommentaryClaimsTest`, "none of the unprovable
     * wordings survives anywhere").
     */
    val C1_BANNED: List<String> = listOf(
        "forces mate", "allowed", "drops the", "sets up a deflection", "sets up an", "sets up a clearance",
        "keeping material level", "stunning", "opens a discovered attack", "The point becomes clear", "probably", "might",
        "plan", "idea", "intends", "strategic"
    )

    /** "wins the knight on f6": a card never says a piece on a square is won (C1). */
    val WINS_A_PIECE_ON_A_SQUARE = Regex("wins the (pawn|knight|bishop|rook|queen|king) on")

    /** The move-quality names; a card never opens with one (the badge shows it). */
    val CLASSIFICATION_NAMES: List<String> = listOf(
        "Blunder", "Mistake", "Inaccuracy", "Brilliant", "Great", "Best", "Excellent", "Good", "Book", "Forced", "Miss"
    )
}
