package net.palaya.chessanalyzer.core.narration

import net.palaya.chessanalyzer.core.analysis.GameReport
import net.palaya.chessanalyzer.core.analysis.PlayerReport
import net.palaya.chessanalyzer.core.chess.Color

/**
 * The facts for the recap end card of an exported video (ANALYSIS_SPEC 9.7, "Recap card").
 *
 * It says nothing the report does not already hold, and it reuses the two things the Summary
 * screen already says so the card can never disagree with it:
 *  - the sentence is [GameSummarySentence], rendered for the same viewer;
 *  - the "biggest moment" is [GameSummarySentence.turningPoint], the very move that sentence
 *    names, and only when it lost at least [GameSummarySentence.BIG_SWING] win-percent, which is
 *    the line below which the sentence does not name a move either.
 *
 * The counts are read from [PlayerReport.classificationCounts], which is what the Summary's move
 * table shows.
 */
object GameRecap {

    /**
     * @param whiteName / [blackName] as the title card shows them.
     * @param userColor the viewer's side, or null when unknown or "Not me".
     */
    fun build(
        report: GameReport,
        whiteName: String,
        blackName: String,
        userColor: Color?,
        strings: NarrationStrings = NarrationLocales.default,
        viewerGender: Gender = Gender.UNSPECIFIED
    ): VideoRecap? {
        if (report.annotations.isEmpty()) return null
        val turning = GameSummarySentence.turningPoint(report.annotations)
            ?.takeIf { it.loss >= GameSummarySentence.BIG_SWING }
        return VideoRecap(
            white = side(report.white, whiteName, userColor),
            black = side(report.black, blackName, userColor),
            summary = GameSummarySentence.text(report, userColor, false, strings, viewerGender),
            biggestMoment = turning?.let { RecapMoment(it.moveNumber, it.color, it.san, it.classification) }
        )
    }

    private fun side(player: PlayerReport, name: String, userColor: Color?) = RecapSide(
        color = player.color,
        name = name,
        isUser = userColor == player.color,
        accuracy = player.accuracy,
        counts = RECAP_COUNT_CLASSES.mapNotNull { cls ->
            player.classificationCounts[cls]?.takeIf { it > 0 }?.let { RecapCount(cls, it) }
        }
    )
}
