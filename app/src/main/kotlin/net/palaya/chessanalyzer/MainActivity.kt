package net.palaya.chessanalyzer

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Only on a genuine launch. A recreation (rotation, font scale, language) re-delivers the same
        // launch intent, and re-reading it would import the shared game a second time and push a
        // fresh analysis over whatever screen the user was on.
        if (savedInstanceState == null) {
            pendingImportPgnState.value = extractPgnFromIntent(intent, contentResolver)
        }

        setContent {
            var pendingImportPgn by pendingImportPgnState

            ChessAnalyzerTheme(darkTheme = true) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    ChessAnalyzerNavHost(
                        pendingImportPgn = pendingImportPgn,
                        onPendingImportConsumed = { pendingImportPgn = null },
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // singleTask launch mode routes re-shares/re-opens here instead of a new instance.
        setIntent(intent)
        pendingImportPgnState.value = extractPgnFromIntent(intent, contentResolver)
    }
}
