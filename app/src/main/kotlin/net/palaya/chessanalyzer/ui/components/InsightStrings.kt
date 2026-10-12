package net.palaya.chessanalyzer.ui.components

import androidx.annotation.StringRes
import net.palaya.chessanalyzer.R
import net.palaya.chessanalyzer.ui.model.InsightKind

/**
 * The `strings.xml` template of each live insight. A plain `when` (no Android calls), so a host test can compare it
 * with the entry named by [InsightKind.resName] (LiveInsightsTest) and a missing branch is a compile error.
 */
@StringRes
fun insightStringRes(kind: InsightKind): Int = when (kind) {
    InsightKind.OPENING -> R.string.wait_insight_opening
    InsightKind.SWING -> R.string.wait_insight_swing
    InsightKind.SWING_YOURS -> R.string.wait_insight_swing_yours
    InsightKind.SWING_OPPONENT -> R.string.wait_insight_swing_opponent
    InsightKind.MATERIAL_LEVEL -> R.string.wait_insight_material_level
    InsightKind.MATERIAL_AHEAD -> R.string.wait_insight_material_ahead
    InsightKind.RUN -> R.string.wait_insight_run
    InsightKind.RUN_YOURS -> R.string.wait_insight_run_yours
    InsightKind.RUN_OPPONENT -> R.string.wait_insight_run_opponent
    InsightKind.PEAK -> R.string.wait_insight_peak
    InsightKind.LEAD_CHANGES -> R.string.wait_insight_lead_changes
    InsightKind.CHECKS -> R.string.wait_insight_checks
    InsightKind.CAPTURES -> R.string.wait_insight_captures
    InsightKind.LEFT_BOOK -> R.string.wait_insight_left_book
    InsightKind.QUEENS_OFF -> R.string.wait_insight_queens_off
    InsightKind.CASTLED_SHORT -> R.string.wait_insight_castled_short
    InsightKind.CASTLED_LONG -> R.string.wait_insight_castled_long
    InsightKind.NOT_CASTLED -> R.string.wait_insight_not_castled
    InsightKind.PROMOTIONS -> R.string.wait_insight_promotions
    InsightKind.GAME_LENGTH -> R.string.wait_insight_game_length
    InsightKind.PROGRESS -> R.string.wait_insight_progress
}
