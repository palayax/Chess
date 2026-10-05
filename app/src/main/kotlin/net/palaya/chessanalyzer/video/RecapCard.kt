package net.palaya.chessanalyzer.video

import net.palaya.chessanalyzer.core.analysis.MoveClassification
import net.palaya.chessanalyzer.core.chess.Color as CoreColor
import net.palaya.chessanalyzer.core.narration.VideoRecap

/*
 * The recap end card of an exported video (R6b, ANALYSIS_SPEC 9.7 "Recap card").
 *
 * Everything here is pure: the words, the number format, the text-fit and chip-flow maths and the
 * duration are plain functions with host tests, and `BoardFrameRenderer.renderRecapFrame` only
 * draws what they decide. The facts come from `core.narration.GameRecap`; this file turns them
 * into the strings that go on the card.
 *
 * Pattern reference (owner's rule: chess.com is the reference for decisions not derived from the
 * owner's goals; pattern only, nothing copied): chess.com's Game Review summary shows an accuracy
 * per side, a move-quality count per side and a short verdict. The card takes that hierarchy
 * (accuracy first and large, counts as chips under it, one verdict sentence below), and draws its
 * own layout, colours from the app's own palette and its own words.
 */

/** Which of the Summary screen's three accuracy colours a number gets. */
enum class AccuracyBand { GOOD, MID, LOW }

/**
 * The Summary screen's rule (`GameReportScreen.accuracyColor`): 85 and up is good, 65 and up is
 * middling, anything lower is low. One definition so the video and the screen cannot disagree.
 */
fun accuracyBand(accuracy: Double): AccuracyBand = when {
    accuracy >= 85 -> AccuracyBand.GOOD
    accuracy >= 65 -> AccuracyBand.MID
    else -> AccuracyBand.LOW
}

/** Left-to-right mark: keeps "97%" and "Nf3" in one piece inside a right-to-left paragraph. */
private const val LRM = '‎'

/** Left-to-right isolate and pop: a SAN keeps its own direction inside any surrounding text. */
private const val LRI = '⁦'
private const val PDI = '⁩'

/**
 * The accuracy as the Summary screen writes it: whole percent, LRM first (`"%.0f%%"`), so "97%"
 * never becomes "%97" in a right-to-left paragraph.
 */
fun recapAccuracyText(accuracy: Double): String = "$LRM" + "%.0f%%".format(accuracy)

/** The words the recap card needs from resources, resolved once per export (see `PanelLabels.from`). */
data class RecapLabels(
    /** "Game recap". */
    val heading: String,
    /** "Accuracy" (the Summary screen's word). */
    val accuracy: String,
    /** "(you)". */
    val youMarker: String,
    /** "White" / "Black". */
    val side: (CoreColor) -> String,
    /** The class name without its glyph: "Blunder". */
    val className: (MoveClassification) -> String,
    /** "Biggest moment: move 14, Nf3 (Blunder by Black)", from the move number, the SAN and the "by" phrase. */
    val biggestMoment: (moveNumber: Int, san: String, qualityBy: String) -> String,
    /** "Blunder by Black" from the class name and the side word. */
    val qualityBy: (className: String, side: String) -> String,
    /** The chip text: "Blunder ×2". */
    val countChip: (className: String, count: Int) -> String,
) {
    companion object {
        val ENGLISH = RecapLabels(
            heading = "Game recap",
            accuracy = "Accuracy",
            youMarker = "(you)",
            side = { if (it == CoreColor.WHITE) "White" else "Black" },
            className = { it.displayName },
            biggestMoment = { n, san, by -> "Biggest moment: move $n, $san ($by)" },
            qualityBy = { cls, side -> "$cls by $side" },
            countChip = { cls, n -> "$cls ×$n" },
        )
    }
}

/** One count chip: its text and the class whose colour it wears. */
data class RecapChip(val text: String, val classification: MoveClassification)

/** One side of the card, every string resolved. */
data class RecapSideContent(
    val isWhite: Boolean,
    val name: String,
    /** "(you)" when this is the viewer's side, else null. Kept apart from [name] so the name can shrink alone. */
    val youMarker: String?,
    val accuracy: Double,
    val accuracyText: String,
    val band: AccuracyBand,
    val chips: List<RecapChip>,
)

/** The whole card as strings: what [BoardFrameRenderer.renderRecapFrame] draws. */
data class RecapCardContent(
    val heading: String,
    val accuracyLabel: String,
    val sides: List<RecapSideContent>,
    /** The one-sentence game summary, or null. */
    val summary: String?,
    /** "Biggest moment: ...", or null when no move lost enough to name. */
    val momentLine: String?,
    /** The class of the named moment, for the dot beside its line. */
    val momentClassification: MoveClassification?,
) {
    /** Words the viewer is asked to read, for [recapDurationMs]. */
    val readingWords: Int
        get() = wordCount(summary) + wordCount(momentLine)

    companion object {
        /** The card for [recap]; sides are always White then Black, as on the Summary's table. */
        fun from(recap: VideoRecap, labels: RecapLabels): RecapCardContent {
            val sides = listOf(recap.white, recap.black).map { s ->
                val isWhite = s.color == CoreColor.WHITE
                RecapSideContent(
                    isWhite = isWhite,
                    name = s.name,
                    youMarker = if (s.isUser) labels.youMarker else null,
                    accuracy = s.accuracy,
                    accuracyText = recapAccuracyText(s.accuracy),
                    band = accuracyBand(s.accuracy),
                    chips = s.counts.map {
                        RecapChip(isolateCount(labels.countChip(labels.className(it.classification), it.count)), it.classification)
                    },
                )
            }
            val moment = recap.biggestMoment
            val momentLine = moment?.let {
                val by = labels.qualityBy(labels.className(it.classification), labels.side(it.color))
                labels.biggestMoment(it.moveNumber, "$LRI${it.san}$PDI", by)
            }
            return RecapCardContent(
                heading = labels.heading,
                accuracyLabel = labels.accuracy,
                sides = sides,
                summary = recap.summary?.takeIf { it.isNotBlank() },
                momentLine = momentLine,
                momentClassification = moment?.classification,
            )
        }

        /** A count chip is a name and a number; the LRM keeps "×2" from turning into "2×" in RTL. */
        private fun isolateCount(text: String): String = "$LRM$text"
    }
}

private fun wordCount(text: String?): Int = text?.split(Regex("\\s+"))?.count { it.isNotBlank() } ?: 0

// ---------------------------------------------------------------------------
// Duration
// ---------------------------------------------------------------------------

/** The recap is on screen for 4 to 6 seconds (R6b). */
const val RECAP_MIN_MS = 4_000L
const val RECAP_MAX_MS = 6_000L

private const val RECAP_BASE_MS = 3_000L
private const val RECAP_PER_WORD_MS = 100L

/**
 * How long the silent card stays up: a 3 s base for the numbers plus 100 ms per word of the two
 * sentences to read, kept within [RECAP_MIN_MS]..[RECAP_MAX_MS]. About 25 words (a typical summary
 * and moment line) is 5.5 s; a card with only the numbers is 4 s; it never runs past 6 s.
 */
fun recapDurationMs(readingWords: Int): Long =
    (RECAP_BASE_MS + RECAP_PER_WORD_MS * readingWords.coerceAtLeast(0)).coerceIn(RECAP_MIN_MS, RECAP_MAX_MS)

// ---------------------------------------------------------------------------
// Text fit
// ---------------------------------------------------------------------------

/** A single line of text as it will be drawn: [text] (maybe shortened) at [size] px. */
data class FittedLine(val text: String, val size: Float, val ellipsized: Boolean)

private const val ELLIPSIS = "…"

/**
 * The largest size in [minSize]..[maxSize] (whole-pixel steps) at which [text] fits [maxWidth], so a
 * short name is drawn big and a long one smaller. When even [minSize] is too wide the text is
 * shortened with an ellipsis at that size, never clipped mid-letter and never wider than [maxWidth]
 * (the longest prefix that fits, found by binary search; an empty box still returns the ellipsis).
 *
 * @param measure the width of a string at a size, in px (a `Paint.measureText` on the device, a
 *   fake in the tests).
 */
fun fitLine(
    text: String,
    maxWidth: Float,
    maxSize: Float,
    minSize: Float,
    measure: (text: String, size: Float) -> Float,
): FittedLine {
    require(minSize in 1f..maxSize) { "need 1 <= minSize <= maxSize" }
    var size = maxSize
    while (true) {
        val s = size.coerceAtLeast(minSize)
        if (measure(text, s) <= maxWidth) return FittedLine(text, s, false)
        if (s <= minSize) break
        size -= 1f
    }
    // Even the smallest size is too wide: keep the longest prefix that fits with the ellipsis.
    var lo = 0
    var hi = text.length
    while (lo < hi) {
        val mid = (lo + hi + 1) / 2
        if (measure(text.substring(0, mid).trimEnd() + ELLIPSIS, minSize) <= maxWidth) lo = mid else hi = mid - 1
    }
    return FittedLine(text.substring(0, lo).trimEnd() + ELLIPSIS, minSize, true)
}

/** A paragraph as it will be drawn: the size, and whether it had to be cut to [maxLines]. */
data class FittedParagraph(val size: Float, val lineCount: Int, val truncated: Boolean)

/**
 * The largest size in [minSize]..[maxSize] at which [text] wraps to at most [maxLines] lines in
 * [maxWidth]; at [minSize] with more lines than allowed it is cut to [maxLines] with an ellipsis
 * (the caller sets `maxLines` and `TruncateAt.END`). [lineCount] is the number of lines the text
 * wraps to at a size (a `StaticLayout` on the device, a fake in the tests).
 */
fun fitParagraph(
    maxSize: Float,
    minSize: Float,
    maxLines: Int,
    lineCount: (size: Float) -> Int,
): FittedParagraph {
    require(minSize in 1f..maxSize && maxLines >= 1)
    var size = maxSize
    while (true) {
        val s = size.coerceAtLeast(minSize)
        val lines = lineCount(s)
        if (lines <= maxLines) return FittedParagraph(s, lines, false)
        if (s <= minSize) break
        size -= 1f
    }
    return FittedParagraph(minSize, maxLines, true)
}

// ---------------------------------------------------------------------------
// Chip flow
// ---------------------------------------------------------------------------

/**
 * Lays chips of the given [widths] out in rows no wider than [maxWidth], left to right, wrapping
 * to a new row when the next chip would not fit. Returns the chip indexes of each row. A chip
 * wider than [maxWidth] gets a row to itself (the caller shrinks its text); nothing is dropped.
 */
fun flowChips(widths: List<Float>, maxWidth: Float, gap: Float): List<List<Int>> {
    val rows = ArrayList<MutableList<Int>>()
    var rowWidth = 0f
    for ((i, w) in widths.withIndex()) {
        val needed = if (rows.isEmpty()) w else rowWidth + gap + w
        if (rows.isEmpty() || needed > maxWidth) {
            rows.add(mutableListOf(i))
            rowWidth = w
        } else {
            rows.last().add(i)
            rowWidth = needed
        }
    }
    return rows
}
