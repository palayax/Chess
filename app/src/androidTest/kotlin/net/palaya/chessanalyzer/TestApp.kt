package net.palaya.chessanalyzer

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import net.palaya.chessanalyzer.data.AnalysisService
import net.palaya.chessanalyzer.data.SetupResult

/**
 * Shared access to the app's own process-lifetime services, exactly as the app wires them.
 *
 * The models are bundled in the APK, so there is nothing to push to the device and nothing to
 * skip: a test that needs the net or the voice installs them the way a user's first launch does
 * ([ensureSetUp]), and fails loudly if that cannot work. The first test to call it in a run pays
 * the real ~257 MB copy; every later one finds the files already installed and returns at once.
 */
object TestApp {
    val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext

    val app: ChessAnalyzerApplication get() = context.applicationContext as ChessAnalyzerApplication

    /** An [AnalysisService] wired to the app's single engine, repository and first-run setup. */
    fun analysisService(): AnalysisService =
        AnalysisService(context, app.engineController, app.gameRepository, app.firstRunSetup)

    /** Runs the real one-time setup and fails the test unless it ends [SetupResult.Done]. */
    suspend fun ensureSetUp() {
        val result = app.firstRunSetup.ensure()
        check(result == SetupResult.Done) { "first-run setup did not complete: $result" }
    }

    /** The installed Kokoro model directory, installing it from the APK first if needed. */
    suspend fun installedVoiceDir(): File {
        ensureSetUp()
        check(app.voiceInstaller.isInstalled()) { "the bundled voice is not installed after setup" }
        return app.voiceInstaller.modelDir
    }
}
