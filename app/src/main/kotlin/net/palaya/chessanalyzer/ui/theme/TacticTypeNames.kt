package net.palaya.chessanalyzer.ui.theme

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import net.palaya.chessanalyzer.R
import net.palaya.chessanalyzer.core.analysis.TacticType

/**
 * User-facing names for [TacticType], as string resources so they translate.
 *
 * `TacticType.displayName` in `:core` is English by construction (`:core` cannot see `R`), and it
 * is still what the *narration* speaks through `EnglishNarration`; everything the UI shows goes
 * through here instead. The `when` is exhaustive so a new motif cannot ship without a name.
 */
@StringRes
fun TacticType.displayNameRes(): Int = when (this) {
    TacticType.FORK -> R.string.tactic_fork
    TacticType.PAWN_FORK -> R.string.tactic_pawn_fork
    TacticType.DOUBLE_ATTACK -> R.string.tactic_double_attack
    TacticType.PIN_ABSOLUTE -> R.string.tactic_pin_absolute
    TacticType.PIN_RELATIVE -> R.string.tactic_pin_relative
    TacticType.SKEWER -> R.string.tactic_skewer
    TacticType.DISCOVERED_ATTACK -> R.string.tactic_discovered_attack
    TacticType.DISCOVERED_CHECK -> R.string.tactic_discovered_check
    TacticType.DOUBLE_CHECK -> R.string.tactic_double_check
    TacticType.HANGING_PIECE -> R.string.tactic_hanging_piece
    TacticType.TRAPPED_PIECE -> R.string.tactic_trapped_piece
    TacticType.DEFLECTION -> R.string.tactic_deflection
    TacticType.DECOY -> R.string.tactic_decoy
    TacticType.OVERLOADED_PIECE -> R.string.tactic_overloaded_piece
    TacticType.INTERFERENCE -> R.string.tactic_interference
    TacticType.CLEARANCE -> R.string.tactic_clearance
    TacticType.ZWISCHENZUG -> R.string.tactic_zwischenzug
    TacticType.BACK_RANK_MATE -> R.string.tactic_back_rank_mate
    TacticType.SMOTHERED_MATE -> R.string.tactic_smothered_mate
    TacticType.GREEK_GIFT -> R.string.tactic_greek_gift
    TacticType.WINDMILL -> R.string.tactic_windmill
    TacticType.X_RAY -> R.string.tactic_x_ray
    TacticType.PROMOTION_TACTIC -> R.string.tactic_promotion
    TacticType.UNDERPROMOTION -> R.string.tactic_underpromotion
    TacticType.PASSED_PAWN_BREAKTHROUGH -> R.string.tactic_passed_pawn_breakthrough
    TacticType.REMOVING_THE_DEFENDER -> R.string.tactic_removing_the_defender
    TacticType.MATE_NET -> R.string.tactic_mate_net
    TacticType.PERPETUAL_CHECK -> R.string.tactic_perpetual_check
    TacticType.STALEMATE_TRICK -> R.string.tactic_stalemate_trick
    TacticType.DESPERADO -> R.string.tactic_desperado
    TacticType.BATTERY -> R.string.tactic_battery
    TacticType.FORTRESS -> R.string.tactic_fortress
}

@Composable
fun tacticTypeName(type: TacticType): String = stringResource(type.displayNameRes())

fun Context.tacticTypeName(type: TacticType): String = getString(type.displayNameRes())
