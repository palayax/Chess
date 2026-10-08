package net.palaya.chessanalyzer.ui.model

import net.palaya.chessanalyzer.ui.theme.MoveClassification

/*
 * Pure logic behind the Summary hub (UX step U5, docs/MOBILE_UX_DESIGN.md §6.3): which side the
 * user played, which moments are worth the first screenful, and how the move-quality table is
 * grouped. No Compose and no Android here, so every rule has a host test.
 */

/**
 * The answer to "Which side were you?" for one game.
 *
 * [UNKNOWN] and [NOT_ME] must stay distinct: both have no [color], but "unknown" means the user has
 * not been asked (Practise says "choose a side"), while "not me" is an answer (Practise hides).
 */
enum class SideChoice {
    UNKNOWN,
    WHITE,
    BLACK,
    NOT_ME;

    /** The colour the user played, or null for [UNKNOWN] and [NOT_ME]. */
    val color: PieceColor?
        get() = when (this) {
            WHITE -> PieceColor.WHITE
            BLACK -> PieceColor.BLACK
            UNKNOWN, NOT_ME -> null
        }

    /**
     * The form stored in `GameRepository.StoredGame.userColorName`. `WHITE`/`BLACK` are what that
     * field has always held, so games saved before this step still read back correctly.
     */
    val storedName: String? get() = if (this == UNKNOWN) null else name

    companion object {
        fun of(color: PieceColor?): SideChoice = when (color) {
            PieceColor.WHITE -> WHITE
            PieceColor.BLACK -> BLACK
            null -> UNKNOWN
        }

        /** Unknown strings (and null) read as [UNKNOWN] rather than failing. */
        fun fromStored(name: String?): SideChoice =
            entries.firstOrNull { it != UNKNOWN && it.name == name } ?: UNKNOWN
    }
}

/** What the report currently says about the user's side. */
val GameReport.sideChoice: SideChoice
    get() = if (notMe) SideChoice.NOT_ME else SideChoice.of(userColor)

/**
 * The player name to remember as the Settings username after the user picks a side on the
 * Summary, or null when nothing should be written.
 *
 * Only ever fills an **empty** username: a user who already set one (for another account) must not
 * have it silently replaced because they played a game under a different name. A missing PGN name
 * (the header falls back to a bare "White"/"Black") or "?" is not a name worth remembering.
 */
fun usernameToRemember(header: GameHeader, choice: SideChoice, currentUsername: String): String? {
    if (currentUsername.isNotBlank()) return null
    val name = when (choice) {
        SideChoice.WHITE -> header.white
        SideChoice.BLACK -> header.black
        SideChoice.UNKNOWN, SideChoice.NOT_ME -> return null
    }.trim()
    val generic = if (choice == SideChoice.WHITE) "White" else "Black"
    return name.takeUnless { it.isEmpty() || it == "?" || it == generic }
}

/** Classes that earn a place among the Summary's key moments. Inaccuracies are not "key". */
private val SUMMARY_MOMENT_CLASSES = setOf(
    MoveClassification.BRILLIANT,
    MoveClassification.MISTAKE,
    MoveClassification.MISS,
    MoveClassification.BLUNDER,
)

/**
 * The moments the Summary shows, split by whose they are.
 *
 * With the side known, [primary] is the user's own moments and [opponent] the other side's (shown
 * under "Your opponent's"). With the side unknown or "not me" there is no "yours", so everything is
 * in [primary] and [opponent] is empty.
 */
data class SummaryMoments(val primary: List<KeyMoment>, val opponent: List<KeyMoment>) {
    val isEmpty: Boolean get() = primary.isEmpty() && opponent.isEmpty()
}

fun selectSummaryMoments(report: GameReport): SummaryMoments {
    val candidates = report.keyMoments
        .filter { it.classification in SUMMARY_MOMENT_CLASSES }
        .distinctBy { it.ply }
        .sortedBy { it.ply }
    val user = report.userColor ?: return SummaryMoments(primary = candidates, opponent = emptyList())
    val (own, other) = candidates.partition { it.moverColor == user }
    return SummaryMoments(primary = own, opponent = other)
}

/**
 * The plies of every key moment the Summary lists (yours and your opponent's), in game order. The
 * Board's "Next key moment" walks this list, so it visits exactly the moments the user saw.
 */
val GameReport.keyMomentPlies: List<Int>
    get() {
        val moments = selectSummaryMoments(this)
        return (moments.primary + moments.opponent).map { it.ply }.distinct().sorted()
    }

/** The collapsed rows that fold several quiet classes into one line (design §5, rule 4). */
enum class ClassificationGroup(val members: List<MoveClassification>) {
    /** Best, Excellent and Good: all "fine", so one row. */
    GOOD_MOVES(listOf(MoveClassification.BEST, MoveClassification.EXCELLENT, MoveClassification.GOOD)),

    /** Book and Forced: moves the player did not really choose. */
    BOOK_FORCED(listOf(MoveClassification.BOOK, MoveClassification.FORCED)),
}

/**
 * One row of the "All moves" table. A row is either one class ([group] null, [badge] is that class)
 * or a [group] of classes whose counts are summed.
 */
data class ClassificationRow(
    val badge: MoveClassification,
    val group: ClassificationGroup?,
    val white: Int,
    val black: Int,
)

/**
 * The move-quality table, grouped.
 *
 * Collapsed (`showAll = false`): Brilliant, Great, Good moves, the four mistake classes, then
 * Book / Forced, with rows where both sides have 0 left out. `showAll = true` is the old flat
 * table, one row per class and zeros included, in [MoveClassification] order. Nothing is lost
 * either way, and the totals of both views are equal.
 */
fun groupClassificationRows(
    white: List<ClassificationCount>,
    black: List<ClassificationCount>,
    showAll: Boolean,
): List<ClassificationRow> {
    val w = white.associate { it.classification to it.count }
    val b = black.associate { it.classification to it.count }
    fun row(c: MoveClassification) = ClassificationRow(c, null, w[c] ?: 0, b[c] ?: 0)
    if (showAll) return MoveClassification.entries.map(::row)

    fun group(g: ClassificationGroup) = ClassificationRow(
        badge = g.members.first(),
        group = g,
        white = g.members.sumOf { w[it] ?: 0 },
        black = g.members.sumOf { b[it] ?: 0 },
    )
    return listOf(
        row(MoveClassification.BRILLIANT),
        row(MoveClassification.GREAT),
        group(ClassificationGroup.GOOD_MOVES),
        row(MoveClassification.INACCURACY),
        row(MoveClassification.MISTAKE),
        row(MoveClassification.MISS),
        row(MoveClassification.BLUNDER),
        group(ClassificationGroup.BOOK_FORCED),
    ).filter { it.white > 0 || it.black > 0 }
}

/**
 * The side to use when a game is (re)opened: an answer the user gave on the Summary earlier wins,
 * because it was explicit and per game; then the side the game was opened with ([initial]: "Not me"
 * for a game from the Famous games library, G1-device, since nobody in it is the user); otherwise
 * whatever the username auto-detection found. The user can still change it on the Summary.
 */
fun resolveSide(stored: SideChoice, detected: PieceColor?, initial: SideChoice = SideChoice.UNKNOWN): SideChoice = when {
    stored != SideChoice.UNKNOWN -> stored
    initial != SideChoice.UNKNOWN -> initial
    else -> SideChoice.of(detected)
}
