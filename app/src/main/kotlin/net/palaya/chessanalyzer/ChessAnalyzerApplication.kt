package net.palaya.chessanalyzer

import android.annotation.SuppressLint
import android.app.Activity
import android.app.ActivityManager
import android.app.Application
import android.content.ComponentCallbacks2
import android.os.Bundle
import android.os.Build
import android.os.storage.StorageManager
import android.util.Log
import androidx.annotation.VisibleForTesting
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import net.palaya.chessanalyzer.core.text.RephrasePrompt
import net.palaya.chessanalyzer.core.text.Rephraser
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
import net.palaya.chessanalyzer.data.models.OurComponents
import net.palaya.chessanalyzer.data.models.UpdateChecker
import net.palaya.chessanalyzer.data.models.UpstreamChecker
import net.palaya.chessanalyzer.data.models.UpstreamSources
import net.palaya.chessanalyzer.diagnostics.AppDiagnostics
import net.palaya.chessanalyzer.diagnostics.DiagnosticLog
import net.palaya.chessanalyzer.engine.NetStore
import net.palaya.chessanalyzer.rephrase.GeneratedRephraseRuntime
import net.palaya.chessanalyzer.rephrase.RephraseBackend
import net.palaya.chessanalyzer.rephrase.RephraseCache
import net.palaya.chessanalyzer.rephrase.RephraseModelStore
import net.palaya.chessanalyzer.rephrase.RephraseService
import net.palaya.chessanalyzer.rephrase.RephraseSupport
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

    /** The one User-Agent every request carries: the app and its version, the Android API level, nothing about the user. */
    private val userAgent: String get() = "PalayaChess/${BuildConfig.VERSION_NAME} (Android ${Build.VERSION.SDK_INT})"

    /** The one class that opens network connections, used only by [modelSetup] and "Check for updates". */
    val modelDownloader: ModelDownloader by lazy {
        ModelDownloader(
            userAgent = userAgent,
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
            rephraseStore = rephraseModelStore,
            // Owner decision (C2 §12.3): a wording model downloaded at setup switches the feature on.
            onRephraseInstalled = { appScope.launch { settingsRepository.setRephraseEnabled(true) } },
        )
    }

    // ---- C2: the optional on-device wording model (docs/LLM_REPHRASE_DESIGN.md) ----

    /** `filesDir/rephrase/models/`: the GGUF, its part, the wanted marker and the load journal. */
    val rephraseModelStore: RephraseModelStore by lazy { RephraseModelStore(filesDir) }

    /** `filesDir/rephrase/cache/<model>/`: accepted / unchanged / rejected verdicts per text. */
    val rephraseCache: RephraseCache by lazy { RephraseCache(File(filesDir, RephraseCache.DIR_NAME)) }

    /** True while an Activity of ours is started: the rephrase jobs run only then (design §6.2). */
    val appForeground = MutableStateFlow(false)
    private var startedActivities = 0

    /** Whether this phone can run the model (ABI, AVX2 on x86_64, RAM). Cheap; never asked at launch. */
    fun rephraseAvailability(): RephraseSupport.Availability {
        val memory = ActivityManager.MemoryInfo().also { (getSystemService(ActivityManager::class.java))?.getMemoryInfo(it) }
        return RephraseSupport.availability(Build.SUPPORTED_ABIS.firstOrNull().orEmpty(), memory.totalMem) {
            runCatching { File("/proc/cpuinfo").readText() }.getOrDefault("")
        }
    }

    private fun rephraseThreads(): Int = RephraseSupport.recommendedThreads(
        (0 until Runtime.getRuntime().availableProcessors()).map { i ->
            runCatching { File("/sys/devices/system/cpu/cpu$i/cpufreq/cpuinfo_max_freq").readText().trim().toLong() }.getOrDefault(0L)
        },
    )

    /** The one llama.cpp model of the process. */
    val rephraseBackend: RephraseBackend by lazy {
        RephraseBackend(
            store = rephraseModelStore,
            availability = { rephraseAvailability() },
            threads = { rephraseThreads() },
            scope = appScope,
            log = { line -> diagnostics.log.log(RephraseService.TAG, line) },
        )
    }

    /**
     * Test seam only (C2, like [updateCheckerForTesting]): the instrumented UI tests put a `FakeRephraser` behind
     * the service. Null in the app; nothing in main code sets it.
     */
    @VisibleForTesting
    @Volatile
    var rephraserForTesting: Rephraser? = null

    private suspend fun rephraseEnabled(): Boolean = settingsRepository.rephraseEnabled.first()

    /** Cards and narration go through this (design §6): cache, checker, jobs. */
    val rephraseService: RephraseService by lazy {
        RephraseService(
            cache = rephraseCache,
            rephraser = { rephraserForTesting?.takeIf { rephraseEnabled() } ?: if (rephraseEnabled()) rephraseBackend.get() else null },
            foreground = appForeground,
            log = { line -> diagnostics.log.log(RephraseService.TAG, line) },
            activeIdOf = { rephraserForTesting?.takeIf { rephraseEnabled() }?.id ?: if (rephraseEnabled()) rephraseBackend.activeId() else null },
            onJobDone = { if (rephraserForTesting == null) rephraseBackend.scheduleIdleRelease() },
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
            rephraseStore = rephraseModelStore,
            rephraseTrial = { model, id -> rephraseBackend.trial(model, id) },
            onRephraseActivated = { id -> rephraseCache.clearExcept("$id@p${RephrasePrompt.VERSION}") },
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
        rephraseArch = net.palaya.chessanalyzer.data.models.GeneratedModelPins.REPHRASE_MODEL_ARCH,
        llamaCppBuild = GeneratedRephraseRuntime.LLAMA_CPP_BUILD,
        installedRephraseSha256 = rephraseModelStore.installedSha256(),
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

    // ---- "Check for updates", the upstream half (A4): the latest releases of Stockfish, sherpa-onnx and Kokoro ----

    /**
     * A second [ModelDownloader] for the upstream list: the same class, rules and User-Agent, with shorter
     * timeouts, because these answers are information and a slow GitHub must not keep the sheet spinning.
     */
    val upstreamDownloader: ModelDownloader by lazy {
        ModelDownloader(
            userAgent = userAgent,
            allowCleartextLoopback = BuildConfig.DEBUG,
            connectTimeoutMs = 8_000,
            readTimeoutMs = 12_000,
            log = { line -> diagnostics.log.log(ModelSetup.TAG, line) },
        )
    }

    /** What this build contains of each upstream component, as those projects name it. */
    fun ourComponents(): OurComponents = OurComponents(
        stockfishTag = BuildConfig.STOCKFISH_TAG,
        sherpaOnnxVersion = BuildConfig.SHERPA_ONNX_VERSION,
        voiceTarName = net.palaya.chessanalyzer.data.models.GeneratedModelPins.VOICE_TAR_NAME,
        voiceArchiveName = net.palaya.chessanalyzer.data.models.GeneratedModelPins.VOICE_UPSTREAM_ARCHIVE_NAME,
        voiceArchiveSizeBytes = net.palaya.chessanalyzer.data.models.GeneratedModelPins.VOICE_UPSTREAM_ARCHIVE_SIZE_BYTES,
        voiceArchiveSha256 = net.palaya.chessanalyzer.data.models.GeneratedModelPins.VOICE_UPSTREAM_ARCHIVE_SHA256,
    )

    private val defaultUpstreamChecker: UpstreamChecker by lazy {
        UpstreamChecker(
            downloader = upstreamDownloader,
            networkStatus = networkStatus,
            sources = UpstreamSources.defaults(ourComponents()),
            diagnostics = diagnostics.log,
        )
    }

    /**
     * Test seam only (A4): `UpdateCheckUpstreamTest` points the upstream rows at an in-process `FaultHttpServer`
     * (the base URL of [UpstreamSources.defaults] is injectable); `UpdateCheckNetworkTest` sets a checker with no
     * sources, so its "exactly two requests" stays about the signed check. Null in the app.
     */
    @VisibleForTesting
    @Volatile
    var upstreamCheckerForTesting: UpstreamChecker? = null

    val modelUpdateInstaller: ModelUpdateInstaller by lazy {
        ModelUpdateInstaller(
            downloader = modelDownloader,
            activator = modelActivator,
            netStore = netStore,
            voiceStore = voiceStore,
            facts = { appFacts() },
            rephraseStore = rephraseModelStore,
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
            upstream = { upstreamCheckerForTesting ?: defaultUpstreamChecker },
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
        // C2: a wording-model load or generation the previous process did not finish (a native abort, or the
        // low-memory killer). The file is re-hashed before its next use; a second death in a row turns it off.
        try {
            when (rephraseModelStore.recoverOnStartup()) {
                RephraseModelStore.Recovery.NOTHING -> Unit
                RephraseModelStore.Recovery.RECHECK ->
                    diagnostics.log.log(RephraseService.TAG, "the last process died loading or running the wording model; it is re-checked before its next use")
                RephraseModelStore.Recovery.TURN_OFF -> {
                    diagnostics.log.log(RephraseService.TAG, "the wording model ended the process twice in a row; Natural wording is turned off")
                    appScope.launch { settingsRepository.setRephraseEnabled(false) }
                }
            }
        } catch (e: Exception) {
            diagnostics.log.error(RephraseService.TAG, "rephrase recovery failed", e)
        }
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                startedActivities++
                appForeground.value = true
            }

            override fun onActivityStopped(activity: Activity) {
                startedActivities = (startedActivities - 1).coerceAtLeast(0)
                appForeground.value = startedActivities > 0
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
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
        // C2: the 1.1 GB model goes first when the system is short of memory, or when the app leaves the screen.
        @Suppress("DEPRECATION")
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) appScope.launch { rephraseBackend.release() }
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
