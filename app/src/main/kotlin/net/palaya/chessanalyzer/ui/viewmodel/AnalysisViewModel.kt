package net.palaya.chessanalyzer.ui.viewmodel

import android.app.Application
import android.util.Log
import androidx.compose.runtime.mutableStateMapOf
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.palaya.chessanalyzer.ChessAnalyzerApplication
import net.palaya.chessanalyzer.data.AnalysisService
import net.palaya.chessanalyzer.data.GameRepository
import net.palaya.chessanalyzer.data.mapper.toHeader
import net.palaya.chessanalyzer.data.mapper.toMoveRecord
import net.palaya.chessanalyzer.data.mapper.toSequenceViews
import net.palaya.chessanalyzer.data.mapper.toUiColor
import net.palaya.chessanalyzer.data.mapper.toUiReport
import net.palaya.chessanalyzer.engine.EngineUpdateInfo
import net.palaya.chessanalyzer.ui.model.AnalysisPhase
import net.palaya.chessanalyzer.ui.model.AnalysisProgress
import net.palaya.chessanalyzer.ui.model.EngineSettings
import net.palaya.chessanalyzer.ui.model.GameReport
import net.palaya.chessanalyzer.ui.model.ImportedGame
import net.palaya.chessanalyzer.ui.model.NarrationProviderChoice
import net.palaya.chessanalyzer.ui.model.NarrationVoiceSettings
import net.palaya.chessanalyzer.ui.model.NeuralModelUiState
import net.palaya.chessanalyzer.ui.model.NeuralVoiceTier
import net.palaya.chessanalyzer.ui.model.RecentGameSummary
import net.palaya.chessanalyzer.core.analysis.GameReport as CoreGameReport
import net.palaya.chessanalyzer.core.analysis.TacticSimulation
import net.palaya.chessanalyzer.core.chess.Color as CoreColor
import net.palaya.chessanalyzer.core.narration.NarrationOptions
import net.palaya.chessanalyzer.core.narration.cloud.GoogleCloudTtsProtocol
import net.palaya.chessanalyzer.core.narration.cloud.GoogleCloudVoice
import net.palaya.chessanalyzer.core.narration.VideoScript
import net.palaya.chessanalyzer.core.narration.VideoScriptGenerator
import net.palaya.chessanalyzer.ui.model.AppLocales
import net.palaya.chessanalyzer.core.narration.NarrationLocales
import net.palaya.chessanalyzer.core.pgn.PgnGame
import net.palaya.chessanalyzer.video.ConnectivityNetworkCostProbe
import net.palaya.chessanalyzer.video.NetworkCostProbe
import net.palaya.chessanalyzer.video.decideAutoVoice
import net.palaya.chessanalyzer.video.CloudKeyCheck
import net.palaya.chessanalyzer.video.GoogleCloudTtsProvider
import net.palaya.chessanalyzer.video.NarrationProviderSelection
import net.palaya.chessanalyzer.video.NarrationStore
import net.palaya.chessanalyzer.video.NarrationVoiceProvider
import net.palaya.chessanalyzer.video.NeuralTtsProvider
import net.palaya.chessanalyzer.video.selectNarrationProvider
import net.palaya.chessanalyzer.video.ProvisioningResult
import net.palaya.chessanalyzer.video.VoiceModelProvisioner

/**
 * Owns the real import -> analyze -> review -> report flow, replacing the placeholder
 * [net.palaya.chessanalyzer.ui.navigation.GameStore] + timer-driven progress simulation that
 * previously stood in for it. Held at the nav-host level via `viewModel()` so it (and its
 * `viewModelScope`) survive configuration changes independently of any one screen.
 *
 * The single [net.palaya.chessanalyzer.data.EngineController] and its net-download gate live
 * one level up on [ChessAnalyzerApplication] (see that class), not here — this class only
 * orchestrates calls into it via [AnalysisService].
 */
class AnalysisViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as ChessAnalyzerApplication
    private val analysisService = AnalysisService(application, app.engineController, app.gameRepository)

    val settings: StateFlow<EngineSettings> = app.settingsRepository.settingsFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, EngineSettings())

    val narrationVoiceSettings: StateFlow<NarrationVoiceSettings> =
        app.narrationSettingsRepository.settingsFlow
            .stateIn(viewModelScope, SharingStarted.Eagerly, NarrationVoiceSettings())

    /** The persistent (`filesDir`-backed) narration cache — see [NarrationStore]'s class doc. */
    private val narrationStore: NarrationStore by lazy { NarrationStore.forApp(application) }

    private val _narrationStorageBytes = MutableStateFlow(0L)
    val narrationStorageBytes: StateFlow<Long> = _narrationStorageBytes

    /** Re-reads how much pre-generated narration audio is on disk — cheap, call whenever Settings opens. */
    fun refreshNarrationStorageBytes() {
        viewModelScope.launch(Dispatchers.IO) { _narrationStorageBytes.value = narrationStore.totalSizeBytes() }
    }

    /** The user's own explicit "Clear" action from Settings — never called automatically. */
    fun clearNarrationStorage() {
        viewModelScope.launch(Dispatchers.IO) {
            narrationStore.clear()
            _narrationStorageBytes.value = narrationStore.totalSizeBytes()
        }
    }

    private val voiceModelProvisioner: VoiceModelProvisioner get() = app.voiceModelProvisioner

    private val _neuralModelState = MutableStateFlow(NeuralModelUiState())
    val neuralModelState: StateFlow<NeuralModelUiState> = _neuralModelState

    /** Cheap disk check — call whenever Settings opens, same pattern as [refreshNarrationStorageBytes]. */
    fun refreshNeuralModelState() {
        viewModelScope.launch(Dispatchers.IO) {
            val installed = NeuralVoiceTier.entries.filter { voiceModelProvisioner.isInstalled(it) }.toSet()
            val sizes = NeuralVoiceTier.entries.associateWith { voiceModelProvisioner.installedSizeBytes(it) }
            _neuralModelState.value = _neuralModelState.value.copy(installedTiers = installed, installedSizeBytes = sizes)
        }
    }

    /**
     * Downloads+verifies+extracts [tier]'s model (see [VoiceModelProvisioner.ensureModel]). On
     * success, if the user has never picked a provider themselves, this promotes them to the
     * neural voice automatically — it is strictly better than device TTS at no cost, which is the
     * whole point of shipping it. A user who has ever explicitly picked a provider (including
     * explicitly picking Device again) is never overridden: that is what
     * [net.palaya.chessanalyzer.ui.model.NarrationVoiceSettings.providerExplicitlyChosen] records.
     * Checking `provider == DEVICE` instead would be wrong — DEVICE is also the un-chosen default,
     * so it cannot tell a deliberate choice apart from no choice at all.
     */
    fun downloadNeuralModel(tier: NeuralVoiceTier) {
        if (_neuralModelState.value.downloadingTier != null) return
        _neuralModelState.value = _neuralModelState.value.copy(downloadingTier = tier, downloadProgress = 0f, lastError = null)
        viewModelScope.launch(Dispatchers.IO) {
            val result = voiceModelProvisioner.ensureModel(tier) { fraction ->
                _neuralModelState.value = _neuralModelState.value.copy(downloadProgress = fraction)
            }
            when (result) {
                is ProvisioningResult.Success -> {
                    // Read persisted settings, not the StateFlow: it is seeded with a default
                    // NarrationVoiceSettings() until DataStore's first emission arrives, and that
                    // seed says providerExplicitlyChosen=false — which would read as "no choice"
                    // and override a user who had in fact chosen one.
                    if (!app.narrationSettingsRepository.current().providerExplicitlyChosen) {
                        app.narrationSettingsRepository.setNeuralTier(tier)
                        app.narrationSettingsRepository.setProviderAutomatically(NarrationProviderChoice.NEURAL)
                    }
                }
                is ProvisioningResult.Failure ->
                    _neuralModelState.value = _neuralModelState.value.copy(lastError = result.reason)
                ProvisioningResult.Cancelled -> Unit
            }
            _neuralModelState.value = _neuralModelState.value.copy(downloadingTier = null, downloadProgress = 0f)
            refreshNeuralModelState()
        }
    }

    /**
     * Reads what the current connection costs. Swappable so the metered gate in
     * [ensureDefaultNeuralVoice] can be exercised on both sides of the boundary by an instrumented
     * test — an emulator cannot be made to report a metered network, and a gate that has only
     * ever run on the unmetered path is not a verified gate.
     */
    @Volatile
    var networkCostProbe: NetworkCostProbe = ConnectivityNetworkCostProbe(app)

    /**
     * Makes the neural voice the *effective* default: the first time narration is actually needed,
     * provision a model in the background and let [downloadNeuralModel]'s promotion select it.
     * Without this the neural voice is only ever reachable by a user who goes looking for it in
     * Settings, so the shipped default would in practice remain the robotic device voice.
     *
     * **Which model depends on what the connection costs** — see [decideAutoVoice]. Kokoro is the
     * intended default but it is ~98.5 MB, on top of the ~98 MB Stockfish net the app already
     * fetches unprompted; auto-pulling that over cellular is not a bill the user agreed to, so the
     * automatic path only reaches for it on an unmetered network and tops out at Piper's ~20 MB
     * otherwise. Narration keeps working throughout: whatever tier is already installed is
     * promoted immediately, and the device voice covers the case where none is.
     *
     * Does nothing when the user has already chosen a provider themselves, or when a download is
     * already running — so it is safe to call on every entry to the video screen.
     */
    fun ensureDefaultNeuralVoice() {
        if (_neuralModelState.value.downloadingTier != null) return
        viewModelScope.launch(Dispatchers.IO) {
            // Deliberately reads the persisted value rather than narrationVoiceSettings.value:
            // that StateFlow is seeded with a default NarrationVoiceSettings() (which reports
            // providerExplicitlyChosen=false) until DataStore's first emission arrives. This runs
            // from a LaunchedEffect on entering the video screen, which on a cold start can easily
            // win that race — and acting on the seed would download a model and switch the voice
            // for a user who had explicitly chosen the device voice. current() suspends until the
            // real stored value is available.
            val stored = app.narrationSettingsRepository.current()
            if (stored.providerExplicitlyChosen) return@launch

            val installed = NeuralVoiceTier.entries.filter { voiceModelProvisioner.isInstalled(it) }.toSet()
            val decision = decideAutoVoice(networkCostProbe.current(), installed)
            Log.i(TAG, "ensureDefaultNeuralVoice: ${decision.reason} (installed=$installed)")

            decision.promoteTo?.let { tier ->
                // setProviderAutomatically, never setProvider: an automatic switch must not latch
                // providerExplicitlyChosen, or the app would record its own decision as the user's.
                if (stored.provider != NarrationProviderChoice.NEURAL || stored.neuralTier != tier) {
                    app.narrationSettingsRepository.setNeuralTier(tier)
                    app.narrationSettingsRepository.setProviderAutomatically(NarrationProviderChoice.NEURAL)
                }
            }
            decision.download?.let { tier -> downloadNeuralModel(tier) }
        }
    }

    fun cancelNeuralModelDownload() {
        voiceModelProvisioner.cancel()
    }

    /** The user's own explicit "delete" action from Settings — reclaims disk space on purpose. */
    fun deleteNeuralModel(tier: NeuralVoiceTier) {
        viewModelScope.launch(Dispatchers.IO) {
            voiceModelProvisioner.delete(tier)
            refreshNeuralModelState()
        }
    }

    fun setNeuralTier(tier: NeuralVoiceTier) {
        viewModelScope.launch { app.narrationSettingsRepository.setNeuralTier(tier) }
    }

    private val _progress = MutableStateFlow(AnalysisProgress(phase = AnalysisPhase.PREPARING_ENGINE))
    val progress: StateFlow<AnalysisProgress> = _progress

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage

    private val _recentGames = MutableStateFlow<List<RecentGameSummary>>(emptyList())
    val recentGames: StateFlow<List<RecentGameSummary>> = _recentGames

    /** In-memory results for games analyzed this session (Compose-observable, like the old GameStore). */
    val games = mutableStateMapOf<String, ImportedGame>()
    val reports = mutableStateMapOf<String, GameReport>()

    /**
     * The raw `:core` artifacts behind each [reports] entry, kept only so the narrated-video
     * feature can call [net.palaya.chessanalyzer.core.narration.VideoScriptGenerator] — it needs
     * the real [CoreGameReport]/[PgnGame], not the UI-shaped [GameReport]/[ImportedGame] the rest
     * of the app renders. [videoScripts] caches the (deterministic, pure) generated script per
     * game so re-opening [net.palaya.chessanalyzer.ui.screens.VideoScreen] doesn't regenerate it.
     */
    private data class CoreArtifacts(
        val game: PgnGame,
        val report: CoreGameReport,
        val userColor: CoreColor?,
        val header: net.palaya.chessanalyzer.ui.model.GameHeader,
    )
    private val coreArtifacts = HashMap<String, CoreArtifacts>()
    private val videoScripts = HashMap<String, VideoScript>()

    /**
     * The UI report for [gameId] gated at [tacticThresholdCp] (ANALYSIS_SPEC §9.6). [reports]
     * holds the version built at analysis time; this re-gates the same `:core` report when the
     * user has since moved the threshold in Settings, so the report screen and the narration never
     * disagree about which tactics mattered. Cheap (no engine work) and cached per threshold.
     */
    fun uiReportFor(gameId: String, tacticThresholdCp: Int): GameReport? {
        val artifacts = coreArtifacts[gameId] ?: return reports[gameId]
        val cached = reports[gameId]
        if (cached != null && cached.tacticThresholdCp == tacticThresholdCp) return cached
        return artifacts.report.toUiReport(artifacts.header, artifacts.userColor?.toUiColor(), tacticThresholdCp)
            .also { reports[gameId] = it }
    }

    /**
     * The textbook example for [type] (ANALYSIS_SPEC §10) as a [TacticSimulation], or null when
     * the library holds none. Built in `:core` from the verified corpus; cached because it is
     * deterministic and the same for every game.
     */
    fun referenceSimulationFor(type: net.palaya.chessanalyzer.core.analysis.TacticType): TacticSimulation? =
        referenceSimulations.getOrPut(type) {
            net.palaya.chessanalyzer.core.analysis.TacticReferenceLibrary.simulation(type)
        }

    private val referenceSimulations = HashMap<net.palaya.chessanalyzer.core.analysis.TacticType, TacticSimulation?>()

    /** Pending PGN text keyed by the gameId already navigated to, consumed by the progress screen. */
    private val pendingPgnByGameId = mutableMapOf<String, String>()

    private var analysisJob: Job? = null
    private val idCounter = AtomicInteger(1)

    init {
        refreshRecentGames()
    }

    fun newGameId(): String = "imported-${idCounter.getAndIncrement()}-${System.currentTimeMillis()}"

    /** Registers PGN text to be analyzed once the caller navigates to the AnalysisProgress route. */
    fun registerPendingImport(gameId: String, pgnText: String) {
        pendingPgnByGameId[gameId] = pgnText
    }

    /** Re-opens an already-imported game (its engine evals are cached, so this resolves fast). */
    fun registerReopen(gameId: String, onReady: () -> Unit) {
        viewModelScope.launch {
            val stored = app.gameRepository.load(gameId)
            if (stored != null) {
                pendingPgnByGameId[gameId] = stored.pgnText
                onReady()
            }
        }
    }

    /**
     * Runs (or re-runs) analysis for [gameId], whose PGN text must already be registered via
     * [registerPendingImport]/[registerReopen]. Safe to call more than once for the same id
     * (e.g. on recomposition) — a prior in-flight job for a different id is cancelled first,
     * since only one [net.palaya.chessanalyzer.engine.StockfishEngine] exists for the app.
     */
    fun runAnalysis(gameId: String, onComplete: () -> Unit) {
        val pgnText = pendingPgnByGameId[gameId] ?: run {
            _errorMessage.value = "Nothing to analyze — the game text was lost. Please import it again."
            return
        }
        if (games.containsKey(gameId)) {
            onComplete()
            return
        }
        analysisJob?.cancel()
        _errorMessage.value = null
        analysisJob = viewModelScope.launch(Dispatchers.Default) {
            val currentSettings = settings.value
            // The tactic significance gate (ANALYSIS_SPEC §9.6) has to be *correct*, so read the
            // persisted threshold rather than the eagerly-seeded StateFlow (see CLAUDE.md).
            val tacticThresholdCp = app.settingsRepository.current().narrationThresholdCp
            val outcome = analysisService.analyze(
                pgnText = pgnText,
                username = currentSettings.username,
                settings = currentSettings,
                onProgress = { _progress.value = it },
            )
            when (outcome) {
                is AnalysisService.Outcome.Success -> {
                    val header = outcome.game.toHeader()
                    val moveRecords = outcome.report.annotations.map { it.toMoveRecord() }
                    val importedGame = ImportedGame(
                        id = gameId,
                        header = header,
                        moves = moveRecords,
                        pgnText = pgnText,
                        analysisDepth = currentSettings.depth,
                        multiPv = currentSettings.multiPv,
                        sequences = outcome.report.annotations.toSequenceViews(tacticThresholdCp),
                    )
                    games[gameId] = importedGame
                    // Pass the detected user colour through so the Game Report can frame the four
                    // tactic buckets as "you"/"your opponent" rather than falling back to
                    // "White"/"Black" — the buckets are the feature the user explicitly asked for.
                    reports[gameId] = outcome.report.toUiReport(header, outcome.userColor?.toUiColor(), tacticThresholdCp)
                    coreArtifacts[gameId] = CoreArtifacts(outcome.game, outcome.report, outcome.userColor, header)
                    app.gameRepository.save(
                        GameRepository.StoredGame(
                            id = gameId,
                            pgnText = pgnText,
                            white = header.white,
                            black = header.black,
                            result = header.result,
                            date = header.date ?: SimpleDateFormat("yyyy.MM.dd", Locale.US).format(Date()),
                            plyCount = moveRecords.size,
                            depth = currentSettings.depth,
                            multiPv = currentSettings.multiPv,
                            userColorName = outcome.userColor?.name,
                        )
                    )
                    refreshRecentGames()
                    // The analysis loop runs on Dispatchers.Default, but [onComplete] drives
                    // NavController, and NavController touches LifecycleRegistry, which throws
                    // "setCurrentState must be called on the main thread" off the main thread.
                    // Without this hop every *successful* analysis crashed the app at the moment
                    // it finished — the one path a user always takes.
                    withContext(Dispatchers.Main) { onComplete() }
                }
                is AnalysisService.Outcome.ParseError -> _errorMessage.value = outcome.message
                is AnalysisService.Outcome.EngineError -> _errorMessage.value = outcome.message
                AnalysisService.Outcome.Cancelled -> Unit
            }
        }
    }

    fun cancelAnalysis() {
        analysisJob?.cancel()
    }

    fun clearError() {
        _errorMessage.value = null
    }

    fun updateSettings(newSettings: EngineSettings) {
        val previousLanguage = settings.value.language
        viewModelScope.launch {
            app.settingsRepository.save(newSettings)
            // Hand the choice to the platform (API 33+): it recreates the Activity and resources
            // re-resolve. Below API 33 this is a documented no-op — see AppLocales.
            if (newSettings.language != previousLanguage) AppLocales.apply(app, newSettings.language)
        }
    }

    fun setNarrationProvider(provider: NarrationProviderChoice) {
        viewModelScope.launch { app.narrationSettingsRepository.setProvider(provider) }
    }

    /**
     * Builds the [NarrationVoiceProvider] the current settings select, or null for the device
     * default. The rule itself is [selectNarrationProvider] (pure, tested on its own); this only
     * turns the selection into a live object.
     */
    fun buildNarrationProvider(): NarrationVoiceProvider? =
        when (val selection = selectNarrationProvider(narrationVoiceSettings.value, voiceModelProvisioner::isInstalled)) {
            NarrationProviderSelection.Device -> null
            is NarrationProviderSelection.Neural ->
                NeuralTtsProvider(tier = selection.tier, modelDir = voiceModelProvisioner.modelDir(selection.tier))
            is NarrationProviderSelection.Cloud ->
                GoogleCloudTtsProvider(apiKey = selection.apiKey, voice = selection.voice, transport = app.cloudTtsTransport)
        }

    // ---- Google Cloud voice: the user's own key, validated with a real request before it is kept ----

    /** State of the setup wizard's "Test and save" — see [testAndSaveCloudKey]. */
    sealed interface CloudKeyCheckState {
        data object Idle : CloudKeyCheckState
        data object Checking : CloudKeyCheckState
        /** The key synthesized real audio; it has been saved and the Cloud voice selected. */
        data class Saved(val voice: GoogleCloudVoice, val durationMs: Long) : CloudKeyCheckState
        data class Rejected(val message: String) : CloudKeyCheckState
    }

    private val _cloudKeyCheck = MutableStateFlow<CloudKeyCheckState>(CloudKeyCheckState.Idle)
    val cloudKeyCheck: StateFlow<CloudKeyCheckState> = _cloudKeyCheck

    fun resetCloudKeyCheck() {
        _cloudKeyCheck.value = CloudKeyCheckState.Idle
    }

    fun setCloudVoice(voice: GoogleCloudVoice) {
        viewModelScope.launch { app.narrationSettingsRepository.setCloudVoice(voice) }
    }

    /**
     * Validates [rawKey] by synthesizing a couple of words through the real path (see
     * [GoogleCloudTtsProvider.validateKey]); only a key that produced audio is saved, and saving
     * it also selects the Cloud voice — the user just walked through a six-step wizard to get
     * here, which is as explicit a choice as Settings' radio button. A rejected key is never
     * stored, so a typo can't leave the app configured for a voice that fails every sentence.
     */
    fun testAndSaveCloudKey(rawKey: String) {
        if (_cloudKeyCheck.value is CloudKeyCheckState.Checking) return
        val key = GoogleCloudTtsProtocol.normalizeApiKey(rawKey)
        _cloudKeyCheck.value = CloudKeyCheckState.Checking
        viewModelScope.launch(Dispatchers.IO) {
            val voice = app.narrationSettingsRepository.current().cloudVoice
            val scratch = java.io.File(getApplication<Application>().cacheDir, "cloud_key_check")
            val result = GoogleCloudTtsProvider.validateKey(key, voice, app.cloudTtsTransport, scratch)
            _cloudKeyCheck.value = when (result) {
                is CloudKeyCheck.Valid -> {
                    app.narrationSettingsRepository.setApiKey(key)
                    app.narrationSettingsRepository.setProvider(NarrationProviderChoice.CLOUD)
                    CloudKeyCheckState.Saved(voice, result.durationMs)
                }
                is CloudKeyCheck.Invalid -> CloudKeyCheckState.Rejected(result.message)
            }
        }
    }

    /**
     * Forgets the key. If the Cloud voice was selected, the choice falls back to the on-device
     * neural voice (or Device if no model is installed) so Settings never shows a selected option
     * that cannot work.
     */
    fun removeCloudKey() {
        viewModelScope.launch(Dispatchers.IO) {
            app.narrationSettingsRepository.clearApiKey()
            if (app.narrationSettingsRepository.current().provider == NarrationProviderChoice.CLOUD) {
                val anyNeural = NeuralVoiceTier.entries.any { voiceModelProvisioner.isInstalled(it) }
                app.narrationSettingsRepository.setProvider(if (anyNeural) NarrationProviderChoice.NEURAL else NarrationProviderChoice.DEVICE)
            }
            _cloudKeyCheck.value = CloudKeyCheckState.Idle
        }
    }

    /**
     * [preferred] if its model is on disk, otherwise any other installed tier, otherwise null.
     * Kept separate from [buildNarrationProvider] so the preference order is one readable rule
     * rather than a nested expression inside a `when`.
     */
    private fun installedTierPreferring(preferred: NeuralVoiceTier): NeuralVoiceTier? =
        preferred.takeIf { voiceModelProvisioner.isInstalled(it) }
            ?: NeuralVoiceTier.entries.firstOrNull { voiceModelProvisioner.isInstalled(it) }

    fun checkForUpdates(onResult: (EngineUpdateInfo?) -> Unit) {
        viewModelScope.launch {
            val result = try {
                app.engineController.checkForEngineUpdate()
            } catch (e: Exception) {
                null
            }
            onResult(result)
        }
    }

    /** Finds the [TacticSimulation] attached to a given ply of an already-analyzed game, if any. */
    fun simulationFor(gameId: String, ply: Int): TacticSimulation? =
        games[gameId]?.moves?.firstOrNull { it.ply == ply }?.core?.simulation

    /**
     * Builds (and caches) the narrated-video [VideoScript] for an already-analyzed game.
     * Deterministic and pure per [VideoScriptGenerator]'s contract, so the cache never goes
     * stale for a given [gameId]/[options] pair — only the options a user might change (depth,
     * style) would need a fresh build, which is why the cache key includes them.
     */
    /**
     * [NarrationOptions] matching the voice that will actually speak this script.
     * [NarrationOptions.speechWpm] is what `VideoScriptGenerator.estimateSpeechMs` uses to lay the
     * timeline out *before* TTS runs, so it has to describe the real voice: Kokoro at
     * `length_scale` 1.1 is measurably slower than the device voice the 165 default was tuned for,
     * and estimating the wrong pace drifts the board animation away from the narration over a
     * multi-minute review.
     *
     * Reads [narrationVoiceSettings]`.value` rather than suspending on the repository, which is
     * safe *here specifically*: this only scales a duration estimate that the real synthesized
     * durations later replace, so losing the StateFlow seed race costs a slightly-off estimate,
     * not a wrong decision. Anything that has to be *correct* (the automatic-promotion gate) reads
     * `narrationSettingsRepository.current()` instead — see [ensureDefaultNeuralVoice].
     */
    suspend fun narrationOptionsForCurrentVoice(): NarrationOptions {
        val s = narrationVoiceSettings.value
        val tier = if (s.provider == NarrationProviderChoice.NEURAL) installedTierPreferring(s.neuralTier) else null
        // The significance threshold decides which moves get narrated at all, so unlike the wpm
        // estimate it has to be *correct* — read it from the repository rather than from the
        // eagerly-seeded `settings` StateFlow, which serves EngineSettings() until DataStore's
        // first emission lands (see the class docs on NarrationSettingsRepository for the same
        // trap on the provider choice).
        val thresholdCp = app.settingsRepository.current().narrationThresholdCp
        return NarrationOptions(
            speechWpm = tier?.measuredWpm ?: NarrationOptions().speechWpm,
            significanceThresholdCp = thresholdCp,
        )
    }

    /**
     * Builds (and caches) the narrated-video script. Suspends because the significance threshold
     * is read from persisted settings; the caller shows a spinner for the one frame that takes.
     */
    suspend fun videoScriptFor(gameId: String): VideoScript? {
        // The language is read from the repository, not the eagerly-seeded StateFlow, for the
        // same reason as the threshold: it decides what the script *says*, so it has to be right.
        val language = app.settingsRepository.current().language
        return videoScriptFor(gameId, narrationOptionsForCurrentVoice(), AppLocales.narrationTag(app, language))
    }

    /**
     * @param languageTag BCP-47 tag the narration is generated in; resolved through
     *   [NarrationLocales.forTag], which falls back to English for anything unsupported. Part of
     *   the cache key so a language change never serves a script in the old language.
     */
    fun videoScriptFor(
        gameId: String,
        options: NarrationOptions,
        languageTag: String = AppLocales.narrationTag(app, settings.value.language),
    ): VideoScript? {
        val strings = NarrationLocales.forTag(languageTag)
        val cacheKey = "$gameId:${strings.languageTag}:$options"
        videoScripts[cacheKey]?.let { return it }
        val artifacts = coreArtifacts[gameId] ?: return null
        val script = VideoScriptGenerator(artifacts.userColor, strings).generate(artifacts.report, artifacts.game, options)
        videoScripts[cacheKey] = script
        return script
    }

    private fun refreshRecentGames() {
        viewModelScope.launch { _recentGames.value = app.gameRepository.listRecent() }
    }

    private companion object {
        const val TAG = "AnalysisViewModel"
    }
}
