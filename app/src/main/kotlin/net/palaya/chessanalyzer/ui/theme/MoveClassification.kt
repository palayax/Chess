package net.palaya.chessanalyzer.ui.theme

import androidx.annotation.StringRes
import androidx.compose.ui.graphics.Color
import net.palaya.chessanalyzer.R

/**
 * chess.com-style per-move annotation classes.
 *
 * This is a UI-layer concept (display name / color / glyph) — the later integration
 * pass should map `core`'s real classification result type onto this enum (or replace
 * this enum outright if `core` defines an equivalent one), keeping the ordinal order
 * best-to-worst intact since [MoveClassification.entries] ordering drives the report
 * table row order.
 */
enum class MoveClassification(
    val color: Color,
    val glyph: String,
    @StringRes val displayNameRes: Int,
) {
    BRILLIANT(ClassBrilliant, "!!", R.string.classification_brilliant),
    GREAT(ClassGreat, "!", R.string.classification_great),
    BEST(ClassBest, "★", R.string.classification_best),
    EXCELLENT(ClassExcellent, "✓", R.string.classification_excellent),
    GOOD(ClassGood, "✓", R.string.classification_good),
    BOOK(ClassBook, "B", R.string.classification_book),
    INACCURACY(ClassInaccuracy, "?!", R.string.classification_inaccuracy),
    MISTAKE(ClassMistake, "?", R.string.classification_mistake),
    MISS(ClassMiss, "✗", R.string.classification_miss),
    BLUNDER(ClassBlunder, "??", R.string.classification_blunder),
    FORCED(ClassForced, "□", R.string.classification_forced),
}
