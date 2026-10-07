package net.palaya.chessanalyzer

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import net.palaya.chessanalyzer.ui.navigation.ChessAnalyzerNavHost
import net.palaya.chessanalyzer.ui.theme.ChessAnalyzerTheme
import net.palaya.chessanalyzer.util.extractPgnFromIntent

/**
 * App entry point and the target of every share/view intent filter declared in
 * AndroidManifest.xml (see the manifest's `ACTION_SEND` / `ACTION_SEND_MULTIPLE` /
 * `ACTION_VIEW` filters). PGN extraction from the intent itself lives in the standalone,
 * unit-testable [extractPgnFromIntent] function rather than inline here.
 */
class MainActivity : ComponentActivity() {

    /** Backs the pending-import text shown to Compose; mutated directly from [onNewIntent]. */
    private val pendingImportPgnState = mutableStateOf<String?>(null)

    /** Set by a tap on the setup download's notification (D2c): the nav host opens the Setup screen. */
    private val openSetupState = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The app is always dark (ChessAnalyzerTheme(darkTheme = true)), so the system bars are told
        // so: light icons on a transparent status bar, and the dark scrim behind 3-button navigation.
        // The default (SystemBarStyle.auto) follows the SYSTEM theme and drew dark status icons and a
        // light navigation-bar scrim over the dark UI whenever the phone was in light mode (seen on
        // API 36, D1).
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(NAV_BAR_SCRIM),
        )
        // Only on a genuine launch. A recreation (rotation, font scale, language) re-delivers the same
        // launch intent, and re-reading it would import the shared game a second time and push a
        // fresh analysis over whatever screen the user was on.
        if (savedInstanceState == null) {
            pendingImportPgnState.value = extractPgnFromIntent(intent, contentResolver)
            openSetupState.value = intent.getBooleanExtra(EXTRA_OPEN_SETUP, false)
        }

        setContent {
            var pendingImportPgn by pendingImportPgnState
            var openSetup by openSetupState

            ChessAnalyzerTheme(darkTheme = true) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    // Edge to edge is enforced from targetSdk 35, and the window then also extends into
                    // the display cutout. Every screen's Scaffold/TopAppBar handles the system bars,
                    // but not a cutout on the SIDE (a phone in landscape), so the whole UI is kept out
                    // of it here, once. windowInsetsPadding consumes what it pads, so a navigation bar
                    // on the same side is not counted twice by the Scaffolds below. The Surface still
                    // paints the background into the cutout area.
                    ChessAnalyzerNavHost(
                        pendingImportPgn = pendingImportPgn,
                        onPendingImportConsumed = { pendingImportPgn = null },
                        openSetupRequested = openSetup,
                        onOpenSetupConsumed = { openSetup = false },
                        modifier = Modifier.windowInsetsPadding(
                            WindowInsets.displayCutout.only(WindowInsetsSides.Horizontal),
                        ),
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // singleTask launch mode routes re-shares/re-opens here instead of a new instance.
        setIntent(intent)
        if (intent.getBooleanExtra(EXTRA_OPEN_SETUP, false)) {
            openSetupState.value = true
        } else {
            pendingImportPgnState.value = extractPgnFromIntent(intent, contentResolver)
        }
    }

    companion object {
        /** Extra on the setup notification's tap intent: open the Setup screen (design §1.5). */
        const val EXTRA_OPEN_SETUP = "open_setup"

        /** Same translucent dark scrim androidx.activity uses by default for a dark navigation bar. */
        private val NAV_BAR_SCRIM = Color.argb(0x80, 0x1b, 0x1b, 0x1b)
    }
}
