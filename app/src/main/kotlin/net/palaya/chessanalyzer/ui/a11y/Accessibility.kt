package net.palaya.chessanalyzer.ui.a11y

import android.view.View
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import android.content.res.Configuration

/*
 * Small accessibility helpers shared by every screen (UX step U10, docs/MOBILE_UX_DESIGN.md §7).
 */

/** Marks a title as a heading, so TalkBack's "next heading" navigation lands on it. */
fun Modifier.asHeading(): Modifier = semantics { heading() }

/**
 * True when the window is wider than it is tall. The activity handles `orientation|screenSize`
 * itself (it is never recreated), so [LocalConfiguration] changes on rotation and every state that
 * lives in the composition survives it.
 */
@Composable
fun isLandscape(): Boolean {
    val configuration = LocalConfiguration.current
    return configuration.orientation == Configuration.ORIENTATION_LANDSCAPE ||
        configuration.screenWidthDp > configuration.screenHeightDp
}

/** Speaks [message] through the accessibility service (a no-op when none is running). */
fun View.announce(message: String) {
    announceForAccessibility(message)
}

/** The view to announce on; read once per composition. */
@Composable
fun rememberAnnouncer(): (String) -> Unit {
    val view = LocalView.current
    return { message -> view.announce(message) }
}

/** The most an app-bar title grows with the system font: the bar is a fixed 64 dp tall. */
const val APP_BAR_TITLE_MAX_FONT_SCALE = 1.3f

/**
 * The text of a top app bar: a heading for TalkBack, one line, and its size capped at
 * [APP_BAR_TITLE_MAX_FONT_SCALE] times the base size. The bar cannot grow with a 2.0 system font
 * (a two-line 44 sp title clipped inside 64 dp), and every screen repeats its subject in the body,
 * so the title stays readable at 1.3x and is never cut mid-glyph.
 */
@Composable
fun AppBarTitle(text: String, modifier: Modifier = Modifier) {
    val density = LocalDensity.current
    CompositionLocalProvider(
        LocalDensity provides Density(density.density, fontScale = minOf(density.fontScale, APP_BAR_TITLE_MAX_FONT_SCALE)),
    ) {
        Text(
            text = text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = modifier.asHeading(),
        )
    }
}
