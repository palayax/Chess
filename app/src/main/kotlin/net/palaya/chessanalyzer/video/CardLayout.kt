package net.palaya.chessanalyzer.video

import net.palaya.chessanalyzer.core.narration.SegmentKind
import net.palaya.chessanalyzer.core.narration.VideoGameHeader
import net.palaya.chessanalyzer.core.narration.VideoScript

/*
 * The title cards of a video (intro, final numbers, "what to work on"): what they print and where it
 * may go (R6c, ANALYSIS_SPEC 9.7 "Title cards").
 *
 * Pure, like `RecapCard.kt`, and it reuses that file's `fitLine`, `fitParagraph` and `FittedLine`:
 * the words and the geometry are plain functions with host tests, and
 * `BoardFrameRenderer.renderCardFrame` only draws what they decide.
 *
 * Pattern reference (owner's rule: chess.com is the reference for decisions not derived from the
 * owner's goals; pattern only, nothing copied): chess.com's Game Review header names the two
 * players first and large, each with its rating beside it, and puts the facts about the game
 * (result, opening) under them on one quiet line, with the accuracies as their own row. The intro
 * card takes that hierarchy (title = the players, one subtitle line, one accuracy line, nothing said
 * twice) and draws it in the video's own palette and words.
 */

/** The caption bar's share of the frame height, at the bottom of every frame. */
const val CAPTION_BAR_FRACTION = 0.095f

/** Where a card's text may go. Everything is a fraction of the frame, so 720 p and any other size agree. */
object CardGeometry {
    /** Left edge of the text column, past the green accent bar. */
    const val TEXT_LEFT = 0.10f

    /** Width of the text column: the right margin is the same 10 percent as the left. */
    const val TEXT_WIDTH = 0.80f

    /** The chapter bar across the top of a card is 7 percent high. */
    const val CHAPTER_BAR = 0.07f

    fun textLeft(w: Float): Float = w * TEXT_LEFT

    fun textWidth(w: Float): Float = w * TEXT_WIDTH

    /** The first pixel row text may use: below the chapter bar, with a little air. */
    fun contentTop(h: Float): Float = h * 0.09f

    /** The last pixel row text may use: above the caption bar, with a little air, caption or not. */
    fun contentBottom(h: Float): Float = h - h * CAPTION_BAR_FRACTION - h * 0.03f
}

/** U+2068 FIRST STRONG ISOLATE and U+2069 POP DIRECTIONAL ISOLATE: a name keeps its own direction. */
private val FSI = 0x2068.toChar()
private val PDI = 0x2069.toChar()

/** [text] with its own direction inside whatever line it is printed in (the helper the Summary uses). */
fun isolateBidi(text: String): String = "$FSI$text$PDI"

/**
 * "White vs Black" for a screen title (Summary header, Home recent-game row): each name is isolated, so
 * a Hebrew name cannot pull its neighbour across it, and [decorateWhite] / [decorateBlack] add what
 * belongs after a name ("(you)") *outside* the isolate, like the rating on a card. The caller draws the
 * line with a forced left-to-right paragraph direction, so White's name is always first and on the left.
 * [versusFormat] is the `game_vs_format` string ("%1$s vs %2$s").
 */
fun versusLine(
    versusFormat: String,
    white: String,
    black: String,
    decorateWhite: (String) -> String = { it },
    decorateBlack: (String) -> String = { it },
): String = String.format(versusFormat, decorateWhite(isolateBidi(white)), decorateBlack(isolateBidi(black)))

/** The heading of a card. */
sealed interface CardTitle {
    /** Every string it prints, for the "each fact once" check. */
    val text: String

    data class Plain(override val text: String) : CardTitle

    /**
     * "White vs Black": each side already carries its rating and its bidi isolates, and [versus] is the
     * word between them with its spaces (" vs "). On one line when it fits, else the names stack.
     */
    data class Versus(val white: String, val versus: String, val black: String) : CardTitle {
        override val text: String get() = white + versus + black
    }
}

/** A body paragraph and the most lines it may take ([FILL] = as many as the card has room for). */
data class CardParagraph(val text: String, val maxLines: Int = FILL) {
    companion object {
        const val FILL = Int.MAX_VALUE
    }
}

/**
 * A card as strings: the title, grey body paragraphs, and bold green fact lines under a rule. The
 * renderer fits each part; nothing here knows a pixel.
 */
data class CardContent(
    val title: CardTitle,
    val body: List<CardParagraph> = emptyList(),
    val facts: List<String> = emptyList(),
) {
    /** Everything the card prints, in reading order. */
    val allText: List<String> get() = listOf(title.text) + body.map { it.text } + facts
}

// ---------------------------------------------------------------------------
// The words
// ---------------------------------------------------------------------------

object CardContents {

    /**
     * The card for one script segment. [heading] and [lines] are the generator's `BoardDirective.Card`.
     *
     *  - INTRO: the title is the two players (from the header, so a Hebrew name is isolated and the
     *    separator word is the script's), the lines are `[subtitle, accuracy]` (ANALYSIS_SPEC 9.7), so
     *    the subtitle is one grey line and the accuracy is the green line. Nothing the title or those two
     *    lines say is printed a second time.
     *  - OUTRO_SUMMARY: the heading and its lines, each at most two lines long. The accuracy and the
     *    names are already in the lines, so the structured sub-lines the card used to add are gone.
     *  - anything else (the lesson cards): one paragraph that takes the room it needs.
     */
    fun forSegment(script: VideoScript, kind: SegmentKind, heading: String, lines: List<String>): CardContent = when (kind) {
        SegmentKind.INTRO -> intro(script, heading, lines)
        SegmentKind.OUTRO_SUMMARY -> CardContent(CardTitle.Plain(heading), lines.map { CardParagraph(it, maxLines = 2) })
        else -> CardContent(CardTitle.Plain(heading), lines.map { CardParagraph(it) })
    }

    /** The caption bar under a card: the intro and the final numbers already say it all in the card. */
    fun captionFor(kind: SegmentKind, caption: String): String = when (kind) {
        SegmentKind.INTRO, SegmentKind.OUTRO_SUMMARY -> ""
        else -> caption
    }

    private fun intro(script: VideoScript, heading: String, lines: List<String>): CardContent {
        val title = script.header?.let { versusTitle(script.title.ifBlank { heading }, it) } ?: CardTitle.Plain(heading)
        // [subtitle, accuracy]: the last line is the accuracy line, any before it are the subtitle's one line.
        val facts = if (lines.size >= 2) lines.takeLast(1) else emptyList()
        val body = lines.dropLast(facts.size).map { CardParagraph(it, maxLines = 1) }
        return CardContent(title, body, facts)
    }

    /**
     * "Name (1500)": the name isolated so it keeps its own direction, the rating outside the isolate so it
     * stays a plain left-to-right "(1500)" after the name (inside a Hebrew name's isolate its brackets and
     * digits were reordered). A rating is only shown when the PGN has one.
     */
    fun playerText(name: String, rating: Int?): String = isolateBidi(name) + (rating?.let { " ($it)" } ?: "")

    fun versusTitle(scriptTitle: String, header: VideoGameHeader): CardTitle.Versus {
        val w = header.whiteName
        val b = header.blackName
        // The word between the names is the script's own ("vs"); recover it from the title, else " vs ".
        val between = if (scriptTitle.length > w.length + b.length && scriptTitle.startsWith(w) && scriptTitle.endsWith(b)) {
            scriptTitle.substring(w.length, scriptTitle.length - b.length)
        } else {
            ""
        }
        return CardTitle.Versus(
            white = playerText(w, header.whiteRating),
            versus = between.ifBlank { " vs " },
            black = playerText(b, header.blackRating),
        )
    }
}

// ---------------------------------------------------------------------------
// Fit
// ---------------------------------------------------------------------------

/** The title as it will be drawn: one line, or (when the names are too long for one) two stacked. */
data class TitleLayout(val lines: List<FittedLine>, val stacked: Boolean)

/**
 * The title's lines. A plain title is one line from [maxSize] down to [minSize], then an ellipsis. A
 * "A vs B" title is one line while it fits at [minSingleSize] or more; when it does not, the two names
 * stack ("A" over "vs B") at one common size from [stackedMaxSize] down to [minSize], so a long pair of
 * names keeps both names visible instead of losing the second to an ellipsis. Never wider than
 * [maxWidth] (see [fitLine]).
 */
fun layoutTitle(
    title: CardTitle,
    maxWidth: Float,
    maxSize: Float,
    minSingleSize: Float,
    stackedMaxSize: Float,
    minSize: Float,
    measure: (text: String, size: Float) -> Float,
): TitleLayout {
    val min = minOf(minSize, maxSize)
    return when (title) {
        is CardTitle.Plain -> TitleLayout(listOf(fitLine(title.text, maxWidth, maxSize, min, measure)), false)
        is CardTitle.Versus -> {
            val single = fitLine(title.text, maxWidth, maxSize, minOf(minSingleSize, maxSize), measure)
            if (!single.ellipsized) return TitleLayout(listOf(single), false)
            val top = title.white
            val bottom = title.versus.trimStart() + title.black
            val stackedMax = minOf(stackedMaxSize, maxSize)
            val first = fitLine(top, maxWidth, stackedMax, minOf(min, stackedMax), measure)
            val second = fitLine(bottom, maxWidth, stackedMax, minOf(min, stackedMax), measure)
            val common = minOf(first.size, second.size)
            TitleLayout(
                listOf(
                    fitLine(top, maxWidth, common, common, measure),
                    fitLine(bottom, maxWidth, common, common, measure),
                ),
                stacked = true,
            )
        }
    }
}

/**
 * The largest size in [minSize]..[maxSize] at which a paragraph is at most [maxHeight] tall; at
 * [minSize] and still too tall it is cut to the lines that fit with an ellipsis ([FittedParagraph.truncated]).
 * [heightAt] is the whole paragraph's height at a size, [lineHeightAt] one line's, [lineCountAt] its
 * wrapped line count. Built on [fitParagraph]: "fits" is the one-line budget of a paragraph whose
 * height is compared with the room it has.
 */
fun fitParagraphToHeight(
    maxSize: Float,
    minSize: Float,
    maxHeight: Float,
    heightAt: (size: Float) -> Float,
    lineHeightAt: (size: Float) -> Float,
    lineCountAt: (size: Float) -> Int,
): FittedParagraph {
    val fit = fitParagraph(maxSize, minSize, 1) { s -> if (heightAt(s) <= maxHeight) 1 else 2 }
    if (!fit.truncated) return FittedParagraph(fit.size, lineCountAt(fit.size), false)
    val lines = (maxHeight / lineHeightAt(minSize)).toInt().coerceAtLeast(1)
    return FittedParagraph(minSize, minOf(lines, lineCountAt(minSize)), true)
}
