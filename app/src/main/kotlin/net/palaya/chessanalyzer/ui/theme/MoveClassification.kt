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
    /**
     * Highlight tier (docs/MOBILE_UX_DESIGN.md §5.4, narrowed by the owner brief for U6): the classes
     * worth tinting a move chip, i.e. the brilliancies and the four mistake classes, so those stand
     * out. GREAT joined them in the design but is quiet here: the engine hands out "!" in runs
     * (three in a row after the blunder in the Immortal Game) and tinting them drowned the mistakes.
     * The quiet tier (GREAT, BEST, EXCELLENT, GOOD, BOOK, FORCED) is a neutral chip with only its
     * small badge. This is a tinting rule only: the palette and the glyphs are unchanged, and the
     * glyph still carries the meaning wherever the colour is not drawn.
     */
    val isHighlight: Boolean,
) {
    BRILLIANT(ClassBrilliant, "!!", R.string.classification_brilliant, isHighlight = true),
    GREAT(ClassGreat, "!", R.string.classification_great, isHighlight = false),
    BEST(ClassBest, "★", R.string.classification_best, isHighlight = false),
    EXCELLENT(ClassExcellent, "✓", R.string.classification_excellent, isHighlight = false),
    GOOD(ClassGood, "✓", R.string.classification_good, isHighlight = false),
    BOOK(ClassBook, "B", R.string.classification_book, isHighlight = false),
    INACCURACY(ClassInaccuracy, "?!", R.string.classification_inaccuracy, isHighlight = true),
    MISTAKE(ClassMistake, "?", R.string.classification_mistake, isHighlight = true),
    MISS(ClassMiss, "✗", R.string.classification_miss, isHighlight = true),
    BLUNDER(ClassBlunder, "??", R.string.classification_blunder, isHighlight = true),
    FORCED(ClassForced, "□", R.string.classification_forced, isHighlight = false);

    /**
     * The classes that lose something (INACCURACY, MISTAKE, MISS, BLUNDER). Only these get the
     * "Better was ..." and "This cost about ..." lines on the comment card.
     */
    val isMistake: Boolean
        get() = when (this) {
            INACCURACY, MISTAKE, MISS, BLUNDER -> true
            else -> false
        }
}
