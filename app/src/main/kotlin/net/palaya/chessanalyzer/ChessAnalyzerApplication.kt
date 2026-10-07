package net.palaya.chessanalyzer

import android.annotation.SuppressLint
import android.app.Application
import android.os.Build
import android.os.storage.StorageManager
import android.util.Log
import androidx.annotation.VisibleForTesting
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import net.palaya.chessanalyzer.data.EngineController
import net.palaya.chessanalyzer.data.GameRepository
import net.palaya.chessanalyzer.data.LegacyKeyStoragePurge
import net.palaya.chessanalyzer.data.NarrationSettingsRepository
import net.palaya.chessanalyzer.data.PendingAnalysisStore
import net.palaya.chessanalyzer.data.SettingsRepository
import net.palaya.chessanalyzer.data.models.ActivationJournal
import net.palaya.chessanalyzer.data.models.AppFacts
import net.palaya.chessanalyzer.data.models.ConnectivityNetworkStatus
import net.palaya.chessanalyzer.data.models.ManifestSignature
import net.palaya.chessanalyzer.data.models.ModelActivator
import net.palaya.chessanalyzer.data.models.ModelDownloader
import net.palaya.chessanalyzer.data.models.ModelSetup
import net.palaya.chessanalyzer.data.models.ModelUpdateInstaller
import net.palaya.chessanalyzer.data.models.ModelUpdates
import net.palaya.chessanalyzer.data.models.NetworkStatus
import net.palaya.chessanalyzer.data.models.UpdateChecker
import net.palaya.chessanalyzer.diagnostics.AppDiagnostics
import net.palaya.chessanalyzer.diagnostics.DiagnosticLog
import net.palaya.chessanalyzer.engine.NetStore
import net.palaya.chessanalyzer.video.NarrationStore
import net.palaya.chessanalyzer.video.NeuralVoiceTrial
import net.palaya.chessanalyzer.video.VideoExportService
import net.palaya.chessanalyzer.video.VoiceStore

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

    /** The on-device diagnostic log (F1), `filesDir/logs/`; see [DiagnosticLog]. */
    val diagnostics: AppDiagnostics by lazy {
        AppDiagnostics(this, DiagnosticLog(File(filesDir, DiagnosticLog.DIR_NAME)))
    }

    /** The downloaded engine net, `filesDir/nets/` (docs/MODEL_DOWNLOAD_DESIGN.md §2.1). */
    val netStore: NetStore by lazy { NetStore(filesDir) }

    /** The downloaded narration voice, `filesDir/tts_models/kokoro/`. */
    val voiceStore: VoiceStore by lazy {
        VoiceStore(
            filesDir = filesDir,
            // The same reading as setup's up-front check (D2c), not File.usableSpace.
            usableSpace = { modelStorageFreeBytes() },
            pinnedSha256 = net.palaya.chessanalyzer.data.models.GeneratedModelPins.VOICE_SHA256,
            pinnedSizeBytes = net.palaya.chessanalyzer.data.models.GeneratedModelPins.VOICE_SIZE_BYTES,
        )
    }

    /** Whether the phone is on Wi-Fi, mobile data or nothing; read only when the user taps Download. */
    val networkStatus: NetworkStatus by lazy { ConnectivityNetworkStatus(this) }

    /** The one class that opens network connections, used only by [modelSetup] and "Check for updates". */
    val modelDownloader: ModelDownloader by lazy {
        ModelDownloader(
            userAgent = "PalayaChess/${BuildConfig.VERSION_NAME} (Android ${Build.VERSION.SDK_INT})",
            // http to 10.0.2.2 / 127.0.0.1 / localhost (scripts/model_test_server.py, FaultHttpServer)
            // only in a debug build; release is https only.
            allowCleartextLoopback = BuildConfig.DEBUG,
            log = { line -> diagnostics.log.log(ModelSetup.TAG, line) },
        )
    }

    /**
     * The first-run download of the net and the voice; started only from the Setup flow (D2c).
     * [modelSetupForTesting], when set, replaces it everywhere (the service, Setup, the nav gate).
     */
    val modelSetup: ModelSetup get() = modelSetupForTesting ?: defaultModelSetup

    /**
     * Test seam only (D2d): `SetupFlowInstrumentedTest` and `SetupGateInstrumentedTest` point the real
     * `ModelDownloadService` and the nav gate at a scratch directory and the in-process `FaultHttpServer`,
     * so the app's own installed models are never touched. Null in the app; nothing in main code sets it.
     */
    @VisibleForTesting
    @Volatile
    var modelSetupForTesting: ModelSetup? = null

    private val defaultModelSetup: ModelSetup by lazy {
        ModelSetup(
            netStore = netStore,
            voiceStore = voiceStore,
            downloader = modelDownloader,
            baseUrl = BuildConfig.MODEL_BASE_URL,
            freeBytes = { modelStorageFreeBytes() },
            diagnostics = diagnostics.log,
        )
    }

    val engineController: EngineController by lazy {
        EngineController(netStore) { line -> diagnostics.log.log(AppDiagnostics.TAG_ENGINE, line) }
    }

    /** The analysis request in flight, kept on disk so a killed process can resume it (F1). */
    val pendingAnalysisStore: PendingAnalysisStore by lazy { PendingAnalysisStore(filesDir) }

    /** A game shared before the engine net was installed, analysed once it is (D2c). */
    val setupWaitingGameStore: PendingAnalysisStore by lazy {
        PendingAnalysisStore(filesDir, PendingAnalysisStore.WAITING_FOR_SETUP_FILE_NAME)
    }

    /**
     * Bytes the app can still write on the volume that holds `filesDir`, for setup's space checks
     * (design §1.3, D2c). `StorageManager.getAllocatableBytes` (API 26, so always here) also counts
     * other apps' cache that the system will clear on request, which `File.usableSpace` misses (lint
     * UsableSpace); [reserveModelStorage] makes that request before a download. Falls back to
     * `usableSpace` only if the storage service cannot answer for this path.
     */
    @SuppressLint("UsableSpace")
    fun modelStorageFreeBytes(): Long = try {
        val storage = getSystemService(StorageManager::class.java)
        storage.getAllocatableBytes(storage.getUuidForPath(filesDir))
    } catch (e: Exception) {
        filesDir.usableSpace
    }

    /**
     * Asks the system to free other apps' cache until [bytes] can be written (`StorageManager.allocateBytes`),
     * right before the download starts. Best effort: a refusal is logged and setup's own per-file check
     * then reports low storage.
     */
    fun reserveModelStorage(bytes: Long) {
        if (bytes <= 0L) return
        try {
            val storage = getSystemService(StorageManager::class.java)
            storage.allocateBytes(storage.getUuidForPath(filesDir), bytes)
        } catch (e: IOException) {
            diagnostics.log.log(ModelSetup.TAG, "allocateBytes($bytes) refused: ${e.message}")
        } catch (e: Exception) {
            diagnostics.log.log(ModelSetup.TAG, "allocateBytes($bytes) unavailable: ${e.javaClass.simpleName}")
        }
    }
    /** Eval caches live per net (D2e): the active net's 12-hex prefix names the folder. */
    val gameRepository: GameRepository by lazy {
        GameRepository(filesDir) { netStore.activeIdentity().prefix ?: NetStore.prefixOf(NetStore.NET_FILENAME)!! }
    }

    // ---- "Check for updates" (D2e, docs/MODEL_DOWNLOAD_DESIGN.md §1.8, §3, §4) ----

    /** Long-lived work the user started (an update check or install) that must survive leaving Settings. */
    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** `filesDir/models/activation.json`. */
    val activationJournal: ActivationJournal by lazy { ActivationJournal(filesDir) }

    /** Journaled swap, trial and rollback of an updated net or voice; [ModelActivator.recoverOnStartup] in [onCreate]. */
    val modelActivator: ModelActivator by lazy {
        ModelActivator(
            netStore = netStore,
            voiceStore = voiceStore,
            journal = activationJournal,
            netGate = engineController,
            voiceTrial = NeuralVoiceTrial(cacheDir) { line -> diagnostics.log.log(ModelSetup.TAG, line) },
            voiceBusy = { VideoExportService.running.value },
            onNetActivated = { net -> net.prefix?.let { gameRepository.purgeEvalCachesExcept(it) } },
            onVoiceActivated = { NarrationStore.forApp(this).clear() },
            diagnostics = diagnostics.log,
        )
    }

    /** What this build is, for the compatibility rules ([net.palaya.chessanalyzer.data.models.ModelCompatibility]). */
    fun appFacts(): AppFacts = AppFacts(
        versionCode = BuildConfig.VERSION_CODE,
        netVersion = NetStore.NET_VERSION,
        netArchHash = NetStore.NET_ARCH_HASH,
        sherpaOnnxVersion = BuildConfig.SHERPA_ONNX_VERSION,
        voiceLayout = VoiceStore.LAYOUT,
        baseUrl = BuildConfig.MODEL_BASE_URL,
        allowCleartextLoopback = BuildConfig.DEBUG,
        installedNetSha256 = netStore.activeNetOrNull()?.let { netStore.activeIdentity().sha256 },
        installedVoiceSha256 = voiceStore.installedSha256(),
    )

    private val defaultUpdateChecker: UpdateChecker by lazy {
        UpdateChecker(
            downloader = modelDownloader,
            manifestUrl = BuildConfig.MODEL_MANIFEST_URL,
            publicKeyDer = ManifestSignature.appPublicKeyDer,
            networkStatus = networkStatus,
            facts = { appFacts() },
            diagnostics = diagnostics.log,
        )
    }

    /**
     * Test seam only (D2e): `UpdateCheckNetworkTest` points the Settings row's check at an in-process
     * `FaultHttpServer` and a TEST key. Null in the app; nothing in main code sets it.
     */
    @VisibleForTesting
    @Volatile
    var updateCheckerForTesting: UpdateChecker? = null

    val modelUpdateInstaller: ModelUpdateInstaller by lazy {
        ModelUpdateInstaller(
            downloader = modelDownloader,
            activator = modelActivator,
            netStore = netStore,
            voiceStore = voiceStore,
            facts = { appFacts() },
            freeBytes = { modelStorageFreeBytes() },
            diagnostics = diagnostics.log,
        )
    }

    /** The Settings sheet's state and its two taps (check, install). */
    val modelUpdates: ModelUpdates by lazy {
        ModelUpdates(
            checker = { updateCheckerForTesting ?: defaultUpdateChecker },
            installer = { modelUpdateInstaller },
            scope = appScope,
            onChecked = { at -> settingsRepository.setLastUpdateCheckMs(at) },
        )
    }
    val settingsRepository: SettingsRepository by lazy { SettingsRepository(this) }
    val narrationSettingsRepository: NarrationSettingsRepository by lazy { NarrationSettingsRepository(this) }

    override fun onCreate() {
        super.onCreate()
        // First, so a crash anywhere after this point is in the log, along with why the previous
        // process ended (the question a "stuck, then it failed" report always raises).
        diagnostics.onProcessStart()
        // D2e: an update activation the previous process did not finish (most likely Stockfish exit()ing on
        // a new net during its trial) is rolled back here, synchronously (renames only), BEFORE anything
        // can touch the engine or the voice (docs/MODEL_DOWNLOAD_DESIGN.md §4.1).
        try {
            modelActivator.recoverOnStartup().let { r ->
                if (r.recovery != net.palaya.chessanalyzer.data.models.Recovery.NOTHING) Log.i("ModelActivator", r.detail)
            }
        } catch (e: Exception) {
            diagnostics.log.error(ModelSetup.TAG, "recoverOnStartup failed", e)
        }
        // An update from a bundled build (versionCode 1) moves its net into nets/ here, before anything
        // can read it: renames only, no hashing, nothing downloaded (docs/MODEL_DOWNLOAD_DESIGN.md §8).
        try {
            modelSetup.migrateLegacy()?.let { Log.i("ModelSetup", it) }
        } catch (e: Exception) {
            diagnostics.log.error(ModelSetup.TAG, "migrateLegacy failed", e)
        }
        // D2e: the flat eval cache of earlier builds moves into the active net's folder (once; cheap after).
        try {
            val m = gameRepository.migrateFlatEvalCache()
            if (m.moved > 0 || m.deletedFiles > 0) {
                diagnostics.log.log(ModelSetup.TAG, "eval cache: moved ${m.moved} file(s) into eval_cache/${gameRepository.currentNetPrefix()}/" +
                    (if (m.deletedFiles > 0) ", dropped ${m.deletedFiles} duplicate(s)" else ""))
            }
        } catch (e: Exception) {
            diagnostics.log.error(ModelSetup.TAG, "eval cache migration failed", e)
        }
        // Delete any API key a pre-Round-13 build stored for the removed Google Cloud voice.
        Thread { LegacyKeyStoragePurge.run(this) }.start()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        diagnostics.onTrimMemory(level)
    }

    override fun onLowMemory() {
        super.onLowMemory()
        diagnostics.onLowMemory()
    }

    override fun onTerminate() {
        // Best-effort only — onTerminate() is not guaranteed to run on a real device, but the
        // engine's native process is torn down with the app process regardless. This just gives
        // the emulator/test-harness case a clean shutdown path.
        engineController.shutdown()
        super.onTerminate()
    }
}
