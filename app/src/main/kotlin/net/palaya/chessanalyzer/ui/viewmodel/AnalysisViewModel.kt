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
import net.palaya.chessanalyzer.data.FirstRunSetup
import net.palaya.chessanalyzer.data.GameRepository
import net.palaya.chessanalyzer.data.mapper.applySideToCommentary
import net.palaya.chessanalyzer.data.mapper.toCoreColor
import net.palaya.chessanalyzer.data.mapper.toHeader
import net.palaya.chessanalyzer.data.mapper.toMoveRecord
import net.palaya.chessanalyzer.data.mapper.toSequenceViews
import net.palaya.chessanalyzer.data.mapper.toUiColor
import net.palaya.chessanalyzer.data.mapper.toUiReport
import net.palaya.chessanalyzer.ui.model.AnalysisPhase
import net.palaya.chessanalyzer.ui.model.AnalysisProgress
import net.palaya.chessanalyzer.ui.model.EngineSettings
import net.palaya.chessanalyzer.ui.model.GameReport
import net.palaya.chessanalyzer.ui.model.ImportedGame
import net.palaya.chessanalyzer.ui.model.NarrationProviderChoice
import net.palaya.chessanalyzer.ui.model.NarrationVoiceSettings
import net.palaya.chessanalyzer.ui.model.NeuralVoiceTier
import net.palaya.chessanalyzer.ui.model.PieceColor
import net.palaya.chessanalyzer.ui.model.RecentGameSummary
import net.palaya.chessanalyzer.ui.model.SideChoice
import net.palaya.chessanalyzer.ui.model.resolveSide
import net.palaya.chessanalyzer.ui.model.usernameToRemember
import net.palaya.chessanalyzer.core.analysis.CommentaryGenerator
import net.palaya.chessanalyzer.core.analysis.GameReport as CoreGameReport
import net.palaya.chessanalyzer.core.analysis.PracticeSelector
import net.palaya.chessanalyzer.core.analysis.PracticeSet
import net.palaya.chessanalyzer.core.analysis.TacticSimulation
import net.palaya.chessanalyzer.core.chess.Color as CoreColor
import net.palaya.chessanalyzer.core.narration.NarrationOptions
import net.palaya.chessanalyzer.core.narration.VideoScript
import net.palaya.chessanalyzer.core.narration.VideoScriptGenerator
import net.palaya.chessanalyzer.ui.model.AppLocales
import net.palaya.chessanalyzer.core.narration.NarrationLocales
import net.palaya.chessanalyzer.core.pgn.PgnGame
import net.palaya.chessanalyzer.video.NarrationProviderSelection
import net.palaya.chessanalyzer.video.NarrationStore
import net.palaya.chessanalyzer.video.NarrationVoiceProvider
import net.palaya.chessanalyzer.video.NeuralTtsProvider
import net.palaya.chessanalyzer.video.selectNarrationProvider
import net.palaya.chessanalyzer.video.BundledVoiceInstaller

/**
 * Owns the real import -> analyze -> review -> report flow, replacing the placeholder
 * [net.palaya.chessanalyzer.ui.navigation.GameStore] + timer-driven progress simulation that
 * previously stood in for it. Held at the nav-host level via `viewModel()` so it (and its
 * `viewModelScope`) survive configuration changes independently of any one screen.
 *
 * The single [net.palaya.chessanalyzer.data.EngineController] and its net gate live
 * one level up on [ChessAnalyzerApplication] (see that class), not here — this class only
 * orchestrates calls into it via [AnalysisService].
 */
class AnalysisViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as ChessAnalyzerApplication
    private val analysisService = AnalysisService(application, app.engineController, app.gameRepository, app.firstRunSetup)

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

    /** The bundled voice, installed once by [FirstRunSetup] before the first analysis. */
    private val voiceInstaller: BundledVoiceInstaller get() = app.voiceInstaller

    private val _progress = MutableStateFlow(AnalysisProgress(phase = AnalysisPhase.PREPARING_ENGINE))
    val progress: StateFlow<AnalysisProgress> = _progress

    /**
     * Why the last analysis failed, or null. A reason rather than a message: the progress screen
     * maps it to a string resource and shows it inline (see `AnalysisProgressScreen`), so no raw
     * exception text ever reaches the user.
     */
    private val _error = MutableStateFlow<AnalysisService.Failure?>(null)
    val error: StateFlow<AnalysisService.Failure?> = _error

    /**
     * Text typed or pasted into Home's paste field. Lives here, not in the screen, so it survives
     * a failed analysis (the user comes back to it and can fix the moves) and is cleared only
     * once an analysis of it has actually succeeded.
     */
    private val _pasteDraft = MutableStateFlow("")
    val pasteDraft: StateFlow<String> = _pasteDraft

    fun setPasteDraft(text: String) {
        _pasteDraft.value = text
    }

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
        /** The report with its commentary written for the current side (see [applySideToCommentary]). */
        val report: CoreGameReport,
        val userColor: CoreColor?,
        val header: net.palaya.chessanalyzer.ui.model.GameHeader,
        /** The user answered "Not me" on the Summary; [userColor] is then null by construction. */
        val sideNotMe: Boolean = false,
    )
    private val coreArtifacts = HashMap<String, CoreArtifacts>()
    private val videoScripts = HashMap<String, VideoScript>()

    /**
     * The narration language for text written into a report (`GameReport.summarySentence`): the
     * same resolution the narrated video uses, so the two never disagree.
     */
    private fun narrationStrings() = NarrationLocales.forTag(AppLocales.narrationTag(app, settings.value.language))

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
        return artifacts.report.toUiReport(artifacts.header, artifacts.userColor?.toUiColor(), tacticThresholdCp, artifacts.sideNotMe, narrationStrings())
            .also { reports[gameId] = it }
    }

    /**
     * The Summary's "Which side were you?" answer for [gameId]: [color] is the side the user
     * played. Re-maps the in-memory `:core` artifacts with that colour (no engine work, so the
     * "you / your opponent" framing and the buckets update at once), remembers the answer in
     * [GameRepository] so reopening the game keeps it, and, when the Settings username is still
     * empty, fills it with that side's PGN name so later games from the same account are
     * recognised without visiting Settings.
     */
    fun setUserColorForGame(gameId: String, color: PieceColor) = setUserSideForGame(gameId, SideChoice.of(color))

    /** "Not me": nobody in this game is the user. Neutral White/Black framing, Practise hidden. */
    fun setNotMeForGame(gameId: String) = setUserSideForGame(gameId, SideChoice.NOT_ME)

    fun setUserSideForGame(gameId: String, choice: SideChoice) {
        val artifacts = coreArtifacts[gameId] ?: return
        val sideColor = choice.color?.toCoreColor()
        // The card texts and the key moments are written for a side ("you" / "your opponent"), so
        // they are written again for this one, from the stored annotations (no engine work), and the
        // move cards are rebuilt from them. "Not me" and no side keep the neutral colour wording.
        val game = games[gameId]
        val (report, rebuilt) = if (game != null) {
            applySideToCommentary(game, artifacts.report, sideColor)
        } else {
            CommentaryGenerator().regenerate(artifacts.report, sideColor) to null
        }
        if (rebuilt != null) games[gameId] = rebuilt
        val updated = artifacts.copy(report = report, userColor = sideColor, sideNotMe = choice == SideChoice.NOT_ME)
        coreArtifacts[gameId] = updated
        // The video script is written "to you" or "to White" depending on the side, and its cache
        // key does not include it, so a cached script for this game would now be stale.
        videoScripts.keys.removeAll { it.startsWith("$gameId:") }
        val threshold = reports[gameId]?.tacticThresholdCp ?: 0
        reports[gameId] = updated.report.toUiReport(updated.header, updated.userColor?.toUiColor(), threshold, updated.sideNotMe, narrationStrings())
        viewModelScope.launch {
            app.gameRepository.load(gameId)?.let { stored ->
                app.gameRepository.save(stored.copy(userColorName = choice.storedName))
            }
            val current = app.settingsRepository.current()
            usernameToRemember(updated.header, choice, current.username)?.let { name ->
                app.settingsRepository.save(current.copy(username = name))
            }
        }
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
        // A new game must not open on the previous game's error state.
        _error.value = null
    }

    /** Re-opens an already-imported game (its engine evals are cached, so this resolves fast). */
    fun registerReopen(gameId: String, onReady: () -> Unit) {
        viewModelScope.launch {
            val stored = app.gameRepository.load(gameId)
            if (stored != null) {
                pendingPgnByGameId[gameId] = stored.pgnText
                _error.value = null
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
        _error.value = null
        val pgnText = pendingPgnByGameId[gameId] ?: run {
            _error.value = AnalysisService.Failure.GAME_TEXT_LOST
            return
        }
        if (games.containsKey(gameId)) {
            onComplete()
            return
        }
        analysisJob?.cancel()
        // A retry starts from the top; without this the screen would flash the previous run's
        // last "Move 12 of 34" until the first new progress update arrives.
        _progress.value = AnalysisProgress(phase = AnalysisPhase.PREPARING_ENGINE)
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
                    // The username auto-detection only covers a user who set a name. An answer to the
                    // Summary's "Which side were you?" from an earlier visit to this game wins over
                    // it (explicit, per game), so reopening a game keeps "you" / "Not me".
                    val side = resolveSide(
                        stored = SideChoice.fromStored(app.gameRepository.load(gameId)?.userColorName),
                        detected = outcome.userColor?.toUiColor(),
                    )
                    val sideColor = side.color?.toCoreColor()
                    // The analysis wrote the card texts for the side it detected; the side that wins
                    // may differ ("Not me", or an earlier answer), so the texts are written for it.
                    val moveRecords = outcome.report.annotations.map { it.toMoveRecord() }
                    val unsided = ImportedGame(
                        id = gameId,
                        header = header,
                        moves = moveRecords,
                        pgnText = pgnText,
                        analysisDepth = currentSettings.depth,
                        multiPv = currentSettings.multiPv,
                        sequences = outcome.report.annotations.toSequenceViews(tacticThresholdCp),
                    )
                    val (sidedReport, importedGame) =
                        if (sideColor == outcome.userColor) outcome.report to unsided
                        else applySideToCommentary(unsided, outcome.report, sideColor)
                    games[gameId] = importedGame
                    // Pass the user colour through so the Game Report can frame the four
                    // tactic buckets as "you"/"your opponent" rather than falling back to
                    // "White"/"Black" — the buckets are the feature the user explicitly asked for.
                    reports[gameId] = sidedReport.toUiReport(header, side.color, tacticThresholdCp, side == SideChoice.NOT_ME, narrationStrings())
                    coreArtifacts[gameId] = CoreArtifacts(outcome.game, sidedReport, sideColor, header, side == SideChoice.NOT_ME)
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
                            userColorName = side.storedName,
                        )
                    )
                    refreshRecentGames()
                    _pasteDraft.value = ""
                    // The analysis loop runs on Dispatchers.Default, but [onComplete] drives
                    // NavController, and NavController touches LifecycleRegistry, which throws
                    // "setCurrentState must be called on the main thread" off the main thread.
                    // Without this hop every *successful* analysis crashed the app at the moment
                    // it finished — the one path a user always takes.
                    withContext(Dispatchers.Main) { onComplete() }
                }
                is AnalysisService.Outcome.ParseError -> {
                    Log.w(TAG, "analysis failed: ${outcome.reason} ${outcome.detail.orEmpty()}")
                    _error.value = outcome.reason
                }
                is AnalysisService.Outcome.EngineError -> {
                    Log.w(TAG, "analysis failed: ${outcome.reason} ${outcome.detail.orEmpty()}")
                    _error.value = outcome.reason
                }
                AnalysisService.Outcome.Cancelled -> Unit
            }
        }
    }

    fun cancelAnalysis() {
        analysisJob?.cancel()
    }

    fun clearError() {
        _error.value = null
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
        when (val selection = selectNarrationProvider(narrationVoiceSettings.value) { voiceInstaller.isInstalled() }) {
            NarrationProviderSelection.Device -> null
            is NarrationProviderSelection.Neural ->
                NeuralTtsProvider(tier = selection.tier, modelDir = voiceInstaller.modelDir)
        }

    // ---- Practise your own mistakes (docs/PRACTICE_DESIGN.md §4, §7.2) ----

    /** The selector's answer per (game, side). Pure `:core`, no engine call, so caching is only about speed. */
    private val practiceSets = HashMap<String, PracticeSet>()

    /**
     * Plies the user solved in Practise, per game. In memory only: persistence is P5, so a restart
     * forgets it. Snapshot state, so the Summary's "N solved" updates when Practise marks a ply.
     */
    val solvedPuzzlePlies = mutableStateMapOf<String, Set<Int>>()

    /**
     * The practice positions for [gameId], or null when the game is not in memory. The side comes
     * from the **UI report** ([reports]), so it follows the Summary's side chooser the moment the
     * user answers it (the answer swaps the stored report), and the result is cached per game and
     * side. "Not me" and "unknown" both have no colour and give [PracticeSet.NoSide]; the Summary
     * tells them apart through `GameReport.sideChoice`.
     */
    fun practiceSetFor(gameId: String): PracticeSet? {
        val artifacts = coreArtifacts[gameId] ?: return null
        val side = reports[gameId]?.userColor?.toCoreColor()
        return practiceSets.getOrPut("$gameId:${side?.name}") { PracticeSelector.select(artifacts.report, side) }
    }

    /** The plies of [gameId] solved so far (empty when none). */
    fun solvedPliesFor(gameId: String): Set<Int> = solvedPuzzlePlies[gameId].orEmpty()

    fun markSolved(gameId: String, ply: Int) {
        val current = solvedPuzzlePlies[gameId].orEmpty()
        if (ply !in current) solvedPuzzlePlies[gameId] = current + ply
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
     * not a wrong decision. Anything that has to be *correct* reads
     * `narrationSettingsRepository.current()` instead.
     */
    suspend fun narrationOptionsForCurrentVoice(): NarrationOptions {
        val s = narrationVoiceSettings.value
        val tier = s.neuralTier.takeIf { s.provider == NarrationProviderChoice.NEURAL && voiceInstaller.isInstalled() }
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
