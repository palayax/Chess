package net.palaya.chessanalyzer.ui.model

import java.util.Locale

/*
 * Pure logic behind the Video screen's player controls (UX step U7, docs/MOBILE_UX_DESIGN.md §6.6):
 * the speed cycle. No Compose and no Android, so it has a host test. Neither the speed nor the
 * mute are persisted: both reset when the screen is left (the owner asked for at most one simple
 * choice, and a player control is more discoverable than a setting).
 */

/** The speeds the one cycle button steps through, in order. */
val PLAYBACK_SPEEDS: List<Float> = listOf(1f, 1.25f, 1.5f)

/** The speed after [current]: 1x, 1.25x, 1.5x, then back to 1x. Anything else restarts at 1x. */
fun nextPlaybackSpeed(current: Float): Float {
    val index = PLAYBACK_SPEEDS.indexOfFirst { it == current }
    return if (index < 0) PLAYBACK_SPEEDS.first() else PLAYBACK_SPEEDS[(index + 1) % PLAYBACK_SPEEDS.size]
}

/**
 * The number in the speed button: "1", "1.25", "1.5" (no trailing zeros, always a dot, whatever the
 * locale). The screen's string adds the multiplication sign and the LRM prefix.
 */
fun playbackSpeedNumber(speed: Float): String {
    val text = String.format(Locale.ROOT, "%.2f", speed)
    return text.trimEnd('0').trimEnd('.')
}
