package net.palaya.chessanalyzer

import android.app.Application
import net.palaya.chessanalyzer.data.EngineController
import net.palaya.chessanalyzer.data.EngineInfo
import net.palaya.chessanalyzer.data.GameRepository
import net.palaya.chessanalyzer.data.GeneratedEngineVersion
import net.palaya.chessanalyzer.data.NarrationSettingsRepository
import net.palaya.chessanalyzer.data.SettingsRepository
import net.palaya.chessanalyzer.video.CloudTtsTransport
import net.palaya.chessanalyzer.video.OkHttpCloudTtsTransport
import net.palaya.chessanalyzer.video.VoiceModelProvisioner

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

    val engineController: EngineController by lazy { EngineController(filesDir) }
    val gameRepository: GameRepository by lazy { GameRepository(filesDir) }
    val settingsRepository: SettingsRepository by lazy { SettingsRepository(this) }
    val narrationSettingsRepository: NarrationSettingsRepository by lazy { NarrationSettingsRepository(this) }

    /** Downloads/verifies the on-device neural voice models — see [VoiceModelProvisioner]'s doc. */
    val voiceModelProvisioner: VoiceModelProvisioner by lazy { VoiceModelProvisioner(filesDir) }

    /** The one HTTP seam of the Cloud voice — see [net.palaya.chessanalyzer.video.GoogleCloudTtsProvider]. */
    val cloudTtsTransport: CloudTtsTransport by lazy { OkHttpCloudTtsTransport() }

    override fun onCreate() {
        super.onCreate()
        EngineInfo.VERSION_LABEL = GeneratedEngineVersion.LABEL
    }

    override fun onTerminate() {
        // Best-effort only — onTerminate() is not guaranteed to run on a real device, but the
        // engine's native process is torn down with the app process regardless. This just gives
        // the emulator/test-harness case a clean shutdown path.
        engineController.shutdown()
        super.onTerminate()
    }
}
