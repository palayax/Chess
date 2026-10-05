package net.palaya.chessanalyzer

import android.app.Application
import net.palaya.chessanalyzer.data.EngineController
import net.palaya.chessanalyzer.data.GameRepository
import net.palaya.chessanalyzer.data.LegacyKeyStoragePurge
import net.palaya.chessanalyzer.data.NarrationSettingsRepository
import net.palaya.chessanalyzer.data.SettingsRepository
import net.palaya.chessanalyzer.data.FirstRunSetup
import net.palaya.chessanalyzer.video.BundledVoiceInstaller

/**
 * App-wide `Application`. Owns the process-lifetime singletons that must not be duplicated per
 * Activity/ViewModel:
 *  - [engineController] — exactly one [net.palaya.chessanalyzer.engine.StockfishEngine] for the
 *    whole app (see [EngineController]'s doc for why two would corrupt each other).
 *  - [gameRepository] / [settingsRepository] — file/DataStore-backed persistence, cheap to hold
 *    once and share.
 *
 * All three are plain lazily-constructed properties rather than a DI framework — the module
 * graph here is small enough that Hilt/Koin would be pure ceremony.
 */
class ChessAnalyzerApplication : Application() {

    val engineController: EngineController by lazy { EngineController(filesDir, assets) }
    val gameRepository: GameRepository by lazy { GameRepository(filesDir) }
    val settingsRepository: SettingsRepository by lazy { SettingsRepository(this) }
    val narrationSettingsRepository: NarrationSettingsRepository by lazy { NarrationSettingsRepository(this) }

    /** Installs the bundled Kokoro voice out of the APK — see [BundledVoiceInstaller]'s doc. */
    val voiceInstaller: BundledVoiceInstaller by lazy { BundledVoiceInstaller(filesDir, assets) }

    /** The one-time setup run before the first analysis — see [FirstRunSetup]'s doc. */
    val firstRunSetup: FirstRunSetup by lazy { FirstRunSetup(engineController, voiceInstaller) { filesDir.usableSpace } }

    override fun onCreate() {
        super.onCreate()
        // Delete any API key a pre-Round-13 build stored for the removed Google Cloud voice.
        Thread { LegacyKeyStoragePurge.run(this) }.start()
    }

    override fun onTerminate() {
        // Best-effort only — onTerminate() is not guaranteed to run on a real device, but the
        // engine's native process is torn down with the app process regardless. This just gives
        // the emulator/test-harness case a clean shutdown path.
        engineController.shutdown()
        super.onTerminate()
    }
}
