package net.palaya.chessanalyzer.ui.viewmodel

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.mutableStateMapOf
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.palaya.chessanalyzer.ChessAnalyzerApplication
import net.palaya.chessanalyzer.data.AnalysisService
import net.palaya.chessanalyzer.data.GameRepository
import net.palaya.chessanalyzer.data.PendingAnalysisStore
import net.palaya.chessanalyzer.data.ReviewPreferencesRepository
import net.palaya.chessanalyzer.diagnostics.AppDiagnostics
import net.palaya.chessanalyzer.ui.model.AnalysisTimeLeftTracker
import net.palaya.chessanalyzer.data.mapper.applySideToCommentary
import net.palaya.chessanalyzer.data.mapper.toCoreColor
import net.palaya.chessanalyzer.data.mapper.toHeader
import net.palaya.chessanalyzer.data.mapper.toMoveRecord
import net.palaya.chessanalyzer.data.mapper.toSequenceViews
import net.palaya.chessanalyzer.data.mapper.toUiColor
import net.palaya.chessanalyzer.data.mapper.toUiReport
import net.palaya.chessanalyzer.rephrase.RephraseService
import net.palaya.chessanalyzer.ui.model.AnalysisPhase
import net.palaya.chessanalyzer.ui.model.AnalysisStrength
import net.palaya.chessanalyzer.ui.model.AnalysisLevel
import net.palaya.chessanalyzer.ui.model.AutoDepthBudget
import net.palaya.chessanalyzer.ui.model.gameAnalysisLevel
import net.palaya.chessanalyzer.ui.model.AnalysisProgress
import net.palaya.chessanalyzer.ui.model.EngineSettings
import net.palaya.chessanalyzer.ui.model.GameReport
import net.palaya.chessanalyzer.ui.model.ImportedGame
import net.palaya.chessanalyzer.ui.model.NarrationPrepState
import net.palaya.chessanalyzer.ui.model.NarrationProviderChoice
import net.palaya.chessanalyzer.ui.model.NarrationVoiceSettings
import net.palaya.chessanalyzer.ui.model.PieceColor
import net.palaya.chessanalyzer.ui.model.RecentGameSummary
import net.palaya.chessanalyzer.ui.model.ReviewDefaults
import net.palaya.chessanalyzer.ui.model.ReviewEntry
import net.palaya.chessanalyzer.ui.model.ReviewOptions
import net.palaya.chessanalyzer.ui.model.ReviewOptionsDraft
import net.palaya.chessanalyzer.ui.model.ReviewOptionsLoad
import net.palaya.chessanalyzer.ui.model.ReviewRunProgress
import net.palaya.chessanalyzer.ui.model.ReviewStage
import net.palaya.chessanalyzer.ui.model.ReviewTimeLeftTracker
import net.palaya.chessanalyzer.ui.model.SideChoice
import net.palaya.chessanalyzer.ui.model.StageState
import net.palaya.chessanalyzer.ui.model.StageStatus
import net.palaya.chessanalyzer.ui.model.deletePlan
import net.palaya.chessanalyzer.ui.model.detectSide
import net.palaya.chessanalyzer.ui.model.estimateAnalysisRemainingMs
import net.palaya.chessanalyzer.ui.model.finished
import net.palaya.chessanalyzer.ui.model.initialStageStates
import net.palaya.chessanalyzer.ui.model.isKeyOfGame
import net.palaya.chessanalyzer.ui.model.libraryAfterDelete
import net.palaya.chessanalyzer.ui.model.narrationNeedsPreparation
import net.palaya.chessanalyzer.ui.model.overallFraction
import net.palaya.chessanalyzer.ui.model.prefillReviewOptions
import net.palaya.chessanalyzer.ui.model.resolveSide
import net.palaya.chessanalyzer.ui.model.reviewStagePlan
import net.palaya.chessanalyzer.ui.model.showReviewOptions
import net.palaya.chessanalyzer.ui.model.stageWeights
import net.palaya.chessanalyzer.ui.model.strengthEstimateMs
import net.palaya.chessanalyzer.ui.model.updated
import net.palaya.chessanalyzer.ui.model.usernameToRemember
import net.palaya.chessanalyzer.ui.model.wordingInEffect
import net.palaya.chessanalyzer.ui.model.wordingOption
import net.palaya.chessanalyzer.core.analysis.CommentaryGenerator
import net.palaya.chessanalyzer.core.analysis.GameReport as CoreGameReport
import net.palaya.chessanalyzer.core.analysis.PositionEval
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
import net.palaya.chessanalyzer.core.pgn.PgnParser
import net.palaya.chessanalyzer.ui.model.SearchBudget
import net.palaya.chessanalyzer.video.DeviceTtsProvider
import net.palaya.chessanalyzer.video.ExportTimeLeftTracker
import net.palaya.chessanalyzer.video.NarrationPreparer
import net.palaya.chessanalyzer.video.NarrationProviderSelection
import net.palaya.chessanalyzer.video.NarrationStore
import net.palaya.chessanalyzer.video.NarrationVoiceProvider
import net.palaya.chessanalyzer.video.NeuralTtsProvider
import net.palaya.chessanalyzer.video.narrationCacheFingerprint
import net.palaya.chessanalyzer.video.selectNarrationProvider
import net.palaya.chessanalyzer.video.narrationOptionsFor
import net.palaya.chessanalyzer.video.VoiceSamplePlayer
import net.palaya.chessanalyzer.core.narration.VideoPace
import net.palaya.chessanalyzer.video.VoiceStore

/**
 * Owns the real import -> review options -> processing -> review -> report flow. Held at the nav-host level via
 * `viewModel()` so it (and its `viewModelScope`) survive configuration changes independently of any one screen.
 *
 * RF (owner's phone test, 2026-10-11): a review's choices are made once, on the Review options screen, and stored with
 * the game ([gameOptions], `GameRepository.StoredGame`); one processing run ([runAnalysis]) then does every stage they
 * ask for (engine, commentary wording, narration script and its wording, the narration recording) with one progress
 * model ([run]); the Video screen only plays, and records missing narration inline ([ensureNarrationPrepared]).
 * Every consumer reads the GAME's options, not the global settings (which are only the defaults for new games).
 *
 * The single [net.palaya.chessanalyzer.data.EngineController] and its net gate live one level up on
 * [ChessAnalyzerApplication] (see that class), not here — this class only orchestrates calls into it via
 * [AnalysisService].
 */
class AnalysisViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as ChessAnalyzerApplication
    private val diagnostics = app.diagnostics.log
    private val analysisService =
        AnalysisService(application, app.engineController, app.gameRepository, diagnostics)
    private val pendingStore: PendingAnalysisStore get() = app.pendingAnalysisStore

    /** A game that arrived before the engine net was installed (D2c): analysed once the net is in. */
    private val waitingStore: PendingAnalysisStore get() = app.setupWaitingGameStore

    private val _gameWaitingForSetup = MutableStateFlow(false)

    /** True while a shared game waits for setup (Setup and the Home card say so). */
    val gameWaitingForSetup: StateFlow<Boolean> = _gameWaitingForSetup

    val settings: StateFlow<EngineSettings> = app.settingsRepository.settingsFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, EngineSettings())

    val narrationVoiceSettings: StateFlow<NarrationVoiceSettings> =
        app.narrationSettingsRepository.settingsFlow
            .stateIn(viewModelScope, SharingStarted.Eagerly, NarrationVoiceSettings())

    /** RF: "Show the review options before each review" and "Prepare the video with each review" (Settings). */
    val reviewPreferences: StateFlow<ReviewPreferencesRepository.Prefs> = app.reviewPreferences.prefsFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, ReviewPreferencesRepository.Prefs())

    /** The persistent (`filesDir`-backed) narration cache — see [NarrationStore]'s class doc. */
    private val narrationStore: NarrationStore by lazy { NarrationStore.forApp(application) }

    /** RF: records narration into [narrationStore] (the processing run's last stage and the Video screen). */
    private val narrationPreparer: NarrationPreparer by lazy {
        NarrationPreparer(narrationStore, { DeviceTtsProvider(app) }, File(app.cacheDir, "narration_prep"))
    }

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

    /** The narration voice, downloaded once by the Setup flow (`ModelSetup`); device TTS narrates until then. */
    private val voiceStore: VoiceStore get() = app.voiceStore

    private val _progress = MutableStateFlow(AnalysisProgress(phase = AnalysisPhase.PREPARING_ENGINE))

    /** The engine stage's own progress (kept for callers that only need the engine; the screen reads [run]). */
    val progress: StateFlow<AnalysisProgress> = _progress

    private val _run = MutableStateFlow(ReviewRunProgress())

    /** RF: the whole processing run (every stage, the overall bar, the measured time left), drawn by the processing screen. */
    val run: StateFlow<ReviewRunProgress> = _run

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
     * RF: the review options of every game this process has run (Compose-observable: the Video route rebuilds its
     * script and its voice when the game's voice or pace changes in its sheet).
     */
    val gameOptions = mutableStateMapOf<String, ReviewOptions>()

    /** RF: options chosen for the next run of a game (the Review options screen's Start, or the defaults with it off). */
    private val pendingOptionsByGameId = mutableMapOf<String, ReviewOptions>()

    // ---- RF: what the "while you wait" panel reads (built by another agent; this class only provides the data) ----

    /** The game being processed now: its id, its moves in SAN and the user's side (UNKNOWN when not known). */
    data class ProcessingGame(val gameId: String, val sanMoves: List<String>, val userSide: SideChoice)

    private val _processingGame = MutableStateFlow<ProcessingGame?>(null)
    val processingGame: StateFlow<ProcessingGame?> = _processingGame

    private val _evalsSoFar = MutableStateFlow<List<PositionEval?>>(emptyList())

    /**
     * The engine's evaluation of each position of the game being processed, so far (index 0 = the start position, i =
     * after ply i; null = not evaluated yet). Filled by [recordPositionEval].
     */
    val evalsSoFar: StateFlow<List<PositionEval?>> = _evalsSoFar

    /**
     * One position's final evaluation from the engine stage (`AnalysisService.analyze`'s `onPositionEvaluated`: also for
     * cache and checkpoint hits, and again when Auto-depth's deeper result replaces the first one).
     */
    private fun recordPositionEval(index: Int, eval: PositionEval) {
        val current = _evalsSoFar.value
        val size = maxOf(current.size, index + 1)
        _evalsSoFar.value = List(size) { i -> if (i == index) eval else current.getOrNull(i) }
    }

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
        /** Positions the search budget stopped early (ANALYSIS_SPEC §8.1), for the Summary's Details line. */
        val cappedPositions: Int = 0,
    )
    private val coreArtifacts = HashMap<String, CoreArtifacts>()
    private val videoScripts = HashMap<String, VideoScript>()

    // ---- C2: the on-device wording model on the cards (docs/LLM_REPHRASE_DESIGN.md §6.2) ----

    /**
     * The core report of [gameId] with the cached rewordings swapped in. [coreArtifacts] keeps the verified
     * originals (side changes and the narrated video are written from them); only the UI copies show this.
     */
    private val wordedReports = HashMap<String, CoreGameReport>()

    @Volatile private var backgroundWording: Job? = null

    /** RF: whether [gameId]'s own Natural wording option is on and the model can run (never the global switch). */
    private fun wordingFor(gameId: String): Boolean {
        val options = gameOptions[gameId] ?: return false
        return wordingInEffect(options, app.wordingModelUsable())
    }

    /**
     * Swaps every cached rewording of [gameId]'s card texts into the UI copies (file reads only, no model). A card
     * already on screen keeps its words until it is composed again (design §6.2). With the game's wording off, the
     * originals.
     */
    private suspend fun applyCachedWording(gameId: String) {
        val artifacts = coreArtifacts[gameId] ?: return
        val worded = withContext(Dispatchers.IO) {
            if (wordingFor(gameId)) app.gameRephraseService.applyCached(artifacts.report) else artifacts.report
        }
        if (worded === artifacts.report) {
            if (wordedReports.remove(gameId) == null) return
        } else {
            wordedReports[gameId] = worded
        }
        val shown = wordedReports[gameId] ?: artifacts.report
        games[gameId]?.let { g -> games[gameId] = g.copy(moves = shown.annotations.map { it.toMoveRecord() }) }
        val threshold = reports[gameId]?.tacticThresholdCp ?: 0
        reports[gameId] = shown.toUiReport(artifacts.header, artifacts.userColor?.toUiColor(), threshold, artifacts.sideNotMe, narrationStrings())
            .copy(cappedPositions = artifacts.cappedPositions)
    }

    /**
     * The lazy part (§6.2): every other card text of [gameId], key moments first then by distance from [aroundPly],
     * one worker, only while a screen of ours is in the foreground; the cards pick the rewordings up when next drawn.
     * Only for a game whose own wording option is on.
     */
    fun startBackgroundWording(gameId: String, aroundPly: Int = 0) {
        val artifacts = coreArtifacts[gameId] ?: return
        backgroundWording?.cancel()
        backgroundWording = viewModelScope.launch(Dispatchers.Default) {
            if (!wordingFor(gameId)) return@launch
            val items = RephraseService.cardOrder(artifacts.report, aroundPly)
            if (app.gameRephraseService.pending(items) == 0) return@launch
            app.gameRephraseService.polish(items)
            applyCachedWording(gameId)
        }
    }

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
        return (wordedReports[gameId] ?: artifacts.report).toUiReport(artifacts.header, artifacts.userColor?.toUiColor(), tacticThresholdCp, artifacts.sideNotMe, narrationStrings())
            .copy(cappedPositions = artifacts.cappedPositions)
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
        gameOptions[gameId]?.let { gameOptions[gameId] = it.copy(side = choice) }
        // C2: the texts for the new side are different strings, so new cache keys: the originals show now, the
        // cached rewordings of this side (if any) are swapped in, and the background job refills the rest.
        wordedReports.remove(gameId)
        // The video script is written "to you" or "to White" depending on the side, and its cache
        // key does not include it, so a cached script for this game would now be stale.
        videoScripts.keys.removeAll { isKeyOfGame(it, gameId) }
        val threshold = reports[gameId]?.tacticThresholdCp ?: 0
        reports[gameId] = updated.report.toUiReport(updated.header, updated.userColor?.toUiColor(), threshold, updated.sideNotMe, narrationStrings())
            .copy(cappedPositions = updated.cappedPositions)
        viewModelScope.launch {
            applyCachedWording(gameId)
            startBackgroundWording(gameId)
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

    /** The side a registered game opens with, when it is not [SideChoice.UNKNOWN] (a famous game: "Not me"). */
    private val pendingSideByGameId = mutableMapOf<String, SideChoice>()

    /**
     * The level (depth, Auto or not) a registered game is analysed at when it is not the Settings default (A4): the
     * strength picked on the Review options screen. Absent = the stored game's level, else the Settings default.
     */
    private val pendingLevelByGameId = mutableMapOf<String, AnalysisLevel>()

    private var analysisJob: Job? = null
    private val idCounter = AtomicInteger(1)

    init {
        refreshRecentGames()
        viewModelScope.launch(Dispatchers.IO) { _gameWaitingForSetup.value = waitingStore.load() != null }
    }

    /**
     * A game shared (or opened) while the engine net is missing (D2c): kept on disk instead of being
     * analysed now, so neither "Not now" nor a killed process loses it. One game waits at a time; a later
     * share replaces it. [takeGameWaitingForSetup] starts it once the net is in. [initialSide] is kept
     * with it (a famous game opens as "Not me", see [registerPendingImport]).
     */
    fun holdGameForSetup(gameId: String, pgnText: String, initialSide: SideChoice = SideChoice.UNKNOWN) {
        _gameWaitingForSetup.value = true
        _error.value = null
        viewModelScope.launch(Dispatchers.IO) {
            val current = app.settingsRepository.current()
            waitingStore.save(
                PendingAnalysisStore.Request(
                    gameId = gameId,
                    pgnText = pgnText,
                    depth = current.depth,
                    multiPv = current.multiPv,
                    username = current.username,
                    startedAtMs = System.currentTimeMillis(),
                    initialSide = initialSide.storedName,
                    autoDepth = current.autoDepth,
                ),
            )
            diagnostics.log(AppDiagnostics.TAG_ANALYSIS, "game $gameId kept until setup has installed the engine data")
        }
    }

    /**
     * The game waiting for setup, registered for analysis and removed from the waiting file; null when
     * none waits. Call only when the net is installed. The caller then opens its Review options (or starts it with
     * the defaults when that screen is switched off), exactly like a fresh import.
     */
    suspend fun takeGameWaitingForSetup(): String? {
        val request = withContext(Dispatchers.IO) { waitingStore.load() } ?: return null
        withContext(Dispatchers.IO) { waitingStore.clear() }
        _gameWaitingForSetup.value = false
        registerPendingImport(request.gameId, request.pgnText, SideChoice.fromStored(request.initialSide))
        diagnostics.log(AppDiagnostics.TAG_ANALYSIS, "setup installed the engine data: analysing the waiting game ${request.gameId}")
        return request.gameId
    }

    fun newGameId(): String = "imported-${idCounter.getAndIncrement()}-${System.currentTimeMillis()}"

    /**
     * Registers PGN text to be reviewed: the Review options screen reads it, and the processing run analyses it.
     * [initialSide] is the side the game opens with until the user answers "Which side were you?":
     * [SideChoice.NOT_ME] for a game from the Famous games library (G1-device: nobody in it is the user,
     * so no "you" wording and the real names); [SideChoice.UNKNOWN] (a share, a paste, a file) leaves it
     * to the username detection, as before. It travels in the request on disk, so a resume keeps it.
     */
    fun registerPendingImport(gameId: String, pgnText: String, initialSide: SideChoice = SideChoice.UNKNOWN) {
        pendingPgnByGameId[gameId] = pgnText
        if (initialSide == SideChoice.UNKNOWN) pendingSideByGameId.remove(gameId) else pendingSideByGameId[gameId] = initialSide
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

    // ---- RF: the Review options screen ----

    /** The Settings values, which are the defaults of a game with nothing stored (read from the repositories). */
    suspend fun reviewDefaults(): ReviewDefaults {
        val s = app.settingsRepository.current()
        return ReviewDefaults(
            depth = s.depth,
            autoDepth = s.autoDepth,
            naturalWording = app.settingsRepository.rephraseEnabled.first(),
            prepareVideo = app.reviewPreferences.current().prepareVideoByDefault,
            voice = app.narrationSettingsRepository.current(),
            pace = s.videoPace,
        )
    }

    /** Whether a review started from [entry] opens the Review options screen first (Settings switch for imports). */
    suspend fun shouldShowReviewOptions(entry: ReviewEntry): Boolean =
        showReviewOptions(entry, app.reviewPreferences.current().showBeforeEachReview)

    /**
     * The game text registered under [gameId]: an import, a reopened or re-analysed game, a game in memory, or a stored
     * one. Null when nothing is known under that id.
     */
    private suspend fun pgnTextFor(gameId: String): String? =
        pendingPgnByGameId[gameId]
            ?: games[gameId]?.pgnText?.takeIf { it.isNotBlank() }
            ?: app.gameRepository.load(gameId)?.pgnText?.also { pendingPgnByGameId[gameId] = it }

    /**
     * What the Review options screen shows for [gameId]: the game's header, its options pre-filled (this game's stored
     * options, else the Settings defaults), what Natural wording can offer here, and a rough time per strength measured
     * on this phone.
     */
    suspend fun reviewOptionsLoad(gameId: String): ReviewOptionsLoad = withContext(Dispatchers.Default) {
        val text = pgnTextFor(gameId) ?: return@withContext ReviewOptionsLoad.Missing
        val game = try {
            PgnParser.parse(text).firstOrNull()
        } catch (e: Exception) {
            null
        } ?: return@withContext ReviewOptionsLoad.Unreadable
        val header = game.toHeader()
        val stored = app.gameRepository.load(gameId)
        val storedOptions = app.gameRepository.loadPendingOptions(gameId) ?: stored?.reviewOptions()
        val settingsNow = app.settingsRepository.current()
        val prefilled = pendingOptionsByGameId[gameId] ?: prefillReviewOptions(
            stored = storedOptions,
            defaults = reviewDefaults(),
            detectedSide = detectSide(header.white, header.black, settingsNow.username),
            initialSide = pendingSideByGameId[gameId] ?: SideChoice.UNKNOWN,
        )
        val positions = game.moves.size + 1
        val speeds = withContext(Dispatchers.IO) { app.reviewSpeedStore.load() }
        val estimates = AnalysisStrength.entries.associateWith { strength ->
            val budget = runCatching { strength.budget }.getOrNull()
            val cached = budget != null && strength.depth > 0 &&
                app.gameRepository.hasEvalCache(app.gameRepository.cacheKey(text, strength.depth, settingsNow.multiPv, budget.cacheKeyPart))
            strengthEstimateMs(positions, budget?.typicalNodes, speeds.engineMsPerNode, cached)
        }
        val supported = app.wordingModelSupported()
        ReviewOptionsLoad.Ready(
            ReviewOptionsDraft(
                gameId = gameId,
                header = header,
                plies = game.moves.size,
                options = prefilled,
                wording = wordingOption(supported, app.wordingModelUsable()),
                voiceInstalled = voiceStore.isInstalled(),
                strengthEstimatesMs = estimates,
            ),
        )
    }

    /**
     * "Start review": [options] are this game's from now on. Kept in memory and on disk (a process killed during the run
     * resumes with them), the strength goes into the depth path (A4), and a game already reviewed is dropped from memory
     * so the run does every stage again (the engine stage reads the eval cache of that strength when it exists).
     */
    fun startReview(gameId: String, options: ReviewOptions) {
        pendingOptionsByGameId[gameId] = options
        pendingLevelByGameId[gameId] = AnalysisLevel.of(options.strength)
        // The options' side is the user's answer; the side the game opened with must not come back.
        if (options.side != SideChoice.UNKNOWN) pendingSideByGameId.remove(gameId)
        games[gameId]?.pgnText?.takeIf { it.isNotBlank() }?.let { pendingPgnByGameId[gameId] = it }
        activeRequest = null
        games.remove(gameId)
        videoScripts.keys.removeAll { isKeyOfGame(it, gameId) }
        practiceSets.keys.removeAll { isKeyOfGame(it, gameId) }
        solvedPuzzlePlies.remove(gameId)
        _error.value = null
        viewModelScope.launch { app.gameRepository.savePendingOptions(gameId, options) }
        diagnostics.log(
            AppDiagnostics.TAG_ANALYSIS,
            "review options for $gameId: ${options.strength.name.lowercase()}, side ${options.side}, wording ${options.naturalWording}, " +
                "video ${options.prepareVideo}, voice ${options.voice}/${options.speakerId}, pace ${options.pace}",
        )
    }

    /** An import with the Review options screen switched off: the defaults (and the detected side) are its options. */
    suspend fun startReviewWithDefaults(gameId: String) {
        val load = reviewOptionsLoad(gameId)
        val options = (load as? ReviewOptionsLoad.Ready)?.draft?.options ?: prefillReviewOptions(
            stored = null,
            defaults = reviewDefaults(),
            initialSide = pendingSideByGameId[gameId] ?: SideChoice.UNKNOWN,
        )
        startReview(gameId, options)
    }

    /** Back from the Review options screen of a new import: the game is forgotten (nothing was stored). */
    fun forgetPendingImport(gameId: String) {
        if (games.containsKey(gameId)) return
        pendingPgnByGameId.remove(gameId)
        pendingSideByGameId.remove(gameId)
        pendingOptionsByGameId.remove(gameId)
        pendingLevelByGameId.remove(gameId)
    }

    fun setShowReviewOptionsBeforeEachReview(show: Boolean) {
        viewModelScope.launch { app.reviewPreferences.setShowBeforeEachReview(show) }
    }

    fun setPrepareVideoByDefault(prepare: Boolean) {
        viewModelScope.launch { app.reviewPreferences.setPrepareVideoByDefault(prepare) }
    }

    /**
     * The request the current or last run used, kept so a Retry after a failure resumes under the
     * same settings (same eval-cache key) instead of whatever Settings says now.
     */
    @Volatile private var activeRequest: PendingAnalysisStore.Request? = null

    /** Set once this ViewModel has run or offered an analysis, so a resume is offered at most once. */
    @Volatile private var resumeChecked = false

    /**
     * The analysis request for [gameId]: the one already running or failed (same settings), a fresh
     * one for game text registered in this process, or the one a previous process left on disk
     * ([PendingAnalysisStore], F1). Null only when none of those exists: then the text really is lost.
     */
    private suspend fun requestFor(gameId: String): PendingAnalysisStore.Request? {
        activeRequest?.takeIf { it.gameId == gameId && pendingPgnByGameId[gameId] in listOf(null, it.pgnText) }?.let { return it }
        pendingPgnByGameId[gameId]?.let { text ->
            // The persisted settings, not the eagerly-seeded StateFlow: a fresh process serves the
            // defaults until DataStore's first emission, and the depth decides the whole analysis.
            val current = app.settingsRepository.current()
            // A4: a re-analyse's pick, else the strength a reopened game was analysed with (an Auto game stays
            // Auto), else the Settings default.
            val level = gameAnalysisLevel(
                explicit = pendingLevelByGameId[gameId],
                stored = app.gameRepository.load(gameId)?.let { AnalysisLevel(it.depth, it.autoDepth) },
                settingsDefault = AnalysisLevel(current.depth, current.autoDepth),
            )
            return PendingAnalysisStore.Request(
                gameId = gameId,
                pgnText = text,
                depth = level.depth,
                autoDepth = level.autoDepth,
                multiPv = current.multiPv,
                username = current.username,
                startedAtMs = System.currentTimeMillis(),
                initialSide = pendingSideByGameId[gameId]?.storedName,
            )
        }
        withContext(Dispatchers.IO) { pendingStore.load() }
            ?.takeIf { it.gameId == gameId }
            ?.let {
                pendingPgnByGameId[gameId] = it.pgnText
                diagnostics.log(AppDiagnostics.TAG_ANALYSIS, "resuming $gameId from the saved request (process was restarted)")
                return it
            }
        // RF: the process was killed after the engine stage (during a wording or recording stage): the game is stored,
        // so the restored screen reopens it (the engine stage reads the eval cache; missing narration is recorded by the
        // Video screen when it is needed).
        return app.gameRepository.load(gameId)?.let { stored ->
            pendingPgnByGameId[gameId] = stored.pgnText
            diagnostics.log(AppDiagnostics.TAG_ANALYSIS, "reopening the stored $gameId (process was restarted after its engine stage)")
            requestFor(gameId)
        }
    }

    /**
     * The options a run of [gameId] uses and whether it is a full review: the Review options' pick (in memory, or on
     * disk after a restart: F1), else the game's stored options or the defaults for a plain reopen (a library row), which
     * runs only the engine stage (see [reviewStagePlan]).
     */
    private suspend fun optionsForRun(gameId: String, request: PendingAnalysisStore.Request): Pair<ReviewOptions, Boolean> {
        pendingOptionsByGameId[gameId]?.let { return it to true }
        val defaults = reviewDefaults()
        app.gameRepository.loadPendingOptions(gameId)?.let { saved ->
            return prefillReviewOptions(saved, defaults, initialSide = SideChoice.fromStored(request.initialSide)) to true
        }
        val stored = app.gameRepository.load(gameId)
        return prefillReviewOptions(stored?.reviewOptions(), defaults, initialSide = SideChoice.fromStored(request.initialSide)) to false
    }

    // ---- RF: the processing run's stages ----

    /** The stage coroutine running now, cancelled by "Skip" ([skipStage]). */
    @Volatile private var stageJob: Job? = null
    @Volatile private var stageJobStage: ReviewStage? = null

    /** "Skip" on an optional stage of the processing screen: what is done stays, the rest of that stage is not done. */
    fun skipStage(stage: ReviewStage) {
        if (!stage.skippable || stageJobStage != stage) return
        diagnostics.log(AppDiagnostics.TAG_ANALYSIS, "stage ${stage.name.lowercase()} skipped by the user")
        stageJob?.cancel()
    }

    /** The current run's bookkeeping: the plan, the stage weights, the time-left tracker. */
    private inner class RunState(
        plan: List<ReviewStage>,
        val weights: Map<ReviewStage, Double>,
        val tracker: ReviewTimeLeftTracker,
        val startedAtMs: Long,
    ) {
        @Volatile var stages: List<StageState> = initialStageStates(plan)
        @Volatile var engine: AnalysisProgress = AnalysisProgress(phase = AnalysisPhase.PREPARING_ENGINE)
        @Volatile var engineRemainingMs: Long? = null
        @Volatile var stageStartedAtMs: Long = startedAtMs

        @Synchronized
        fun emit() {
            val (overall, step) = tracker.update(stages, engineRemainingMs)
            _run.value = ReviewRunProgress(
                stages = stages,
                overallFraction = overallFraction(stages, weights),
                overallTimeLeft = overall,
                stageTimeLeft = step,
                engine = engine,
                runStartedAtMs = startedAtMs,
            )
        }

        @Synchronized
        fun stageProgress(stage: ReviewStage, done: Int, total: Int) {
            stages = stages.updated(stage) { it.copy(done = done, total = total) }
            if (stage != ReviewStage.ENGINE) tracker.onStageProgress(stage, done, SystemClock.elapsedRealtime())
            emit()
        }

        @Synchronized
        fun finish(stage: ReviewStage, skipped: Boolean) {
            stages = stages.finished(stage, skipped)
            stageStartedAtMs = SystemClock.elapsedRealtime()
            emit()
        }
    }

    /**
     * Runs one optional stage as a child coroutine, so "Skip" ([skipStage]) cancels only it and a Cancel of the whole
     * run cancels it too. A stage that fails is logged and treated as skipped (the originals stay). Records the stage's
     * measured time for later estimates when it ran to the end.
     */
    private suspend fun runStage(state: RunState, stage: ReviewStage, plies: Int, block: suspend (report: (Int, Int) -> Unit) -> Unit) {
        val startedAt = SystemClock.elapsedRealtime()
        var failed = false
        val skipped = coroutineScope {
            val job = launch {
                try {
                    block { done, total -> state.stageProgress(stage, done, total) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    failed = true
                    diagnostics.error(AppDiagnostics.TAG_ANALYSIS, "stage ${stage.name.lowercase()} failed; the review goes on without it", e)
                }
            }
            stageJob = job
            stageJobStage = stage
            job.join()
            stageJob = null
            stageJobStage = null
            job.isCancelled
        }
        if (!skipped && !failed) {
            val took = SystemClock.elapsedRealtime() - startedAt
            withContext(Dispatchers.IO) { app.reviewSpeedStore.update { it.withStage(stage, took, plies) } }
        }
        state.finish(stage, skipped || failed)
    }

    /**
     * Runs (or re-runs) the review of [gameId], whose PGN text must already be registered via
     * [registerPendingImport]/[registerReopen] — or, after the process was killed, be on disk in
     * [PendingAnalysisStore]. Safe to call more than once for the same id (e.g. on recomposition)
     * — a prior in-flight job for a different id is cancelled first, since only one
     * [net.palaya.chessanalyzer.engine.StockfishEngine] exists for the app.
     *
     * RF: one run does every stage the game's options ask for ([reviewStagePlan]): the engine, then the key-moment
     * wording, the narration script and its wording, and the narration recording, each with its own count and the
     * overall bar and measured time left in [run]. The request is written to disk as the run starts and deleted when
     * the engine stage succeeds or the user cancels ([cancelAnalysis]), so a kill in between resumes from the per-ply
     * checkpoint (F1).
     */
    fun runAnalysis(gameId: String, onComplete: () -> Unit) {
        _error.value = null
        resumeChecked = true
        if (games.containsKey(gameId)) {
            onComplete()
            return
        }
        analysisJob?.cancel()
        // A retry starts from the top; without this the screen would flash the previous run's
        // last "Move 12 of 34" until the first new progress update arrives.
        _progress.value = AnalysisProgress(phase = AnalysisPhase.PREPARING_ENGINE)
        _run.value = ReviewRunProgress()
        analysisJob = viewModelScope.launch(Dispatchers.Default) {
            val request = requestFor(gameId) ?: run {
                diagnostics.error(AppDiagnostics.TAG_ANALYSIS, "game text lost for $gameId: nothing in memory or on disk")
                _error.value = AnalysisService.Failure.GAME_TEXT_LOST
                return@launch
            }
            activeRequest = request
            withContext(Dispatchers.IO) { pendingStore.save(request) }
            val pgnText = request.pgnText
            val currentSettings = app.settingsRepository.current().copy(
                depth = request.depth,
                multiPv = request.multiPv,
                username = request.username,
                autoDepth = request.autoDepth,
            )
            val (options, fullReview) = optionsForRun(gameId, request)
            gameOptions[gameId] = options
            val parsed = runCatching { PgnParser.parse(pgnText).firstOrNull() }.getOrNull()
            val plies = parsed?.moves?.size ?: 0
            _processingGame.value = ProcessingGame(
                gameId = gameId,
                sanMoves = parsed?.moves?.map { it.san }.orEmpty(),
                userSide = options.side.takeIf { it != SideChoice.UNKNOWN }
                    ?: SideChoice.of(parsed?.let { detectSide(it.tags["White"].orEmpty(), it.tags["Black"].orEmpty(), request.username) }),
            )
            _evalsSoFar.value = List(plies + 1) { null }
            // The plan, and the weights of its stages from what this phone measured before (priors when it has not).
            val modelUsable = app.wordingModelUsable()
            val kokoro = withContext(Dispatchers.IO) { voiceStore.isInstalled() }
            val plan = reviewStagePlan(options, modelUsable, kokoro, reopen = !fullReview)
            val speeds = withContext(Dispatchers.IO) { app.reviewSpeedStore.load() }
            val budget = currentSettings.searchBudget
            val engineCached = app.gameRepository.hasEvalCache(
                app.gameRepository.cacheKey(pgnText, currentSettings.depth, currentSettings.multiPv, budget.cacheKeyPart),
            )
            val estimates = plan.associateWith { stage ->
                if (stage == ReviewStage.ENGINE) strengthEstimateMs(plies + 1, budget.typicalNodes, speeds.engineMsPerNode, engineCached)
                else speeds.estimateMs(stage, plies)
            }
            val runStartedAt = SystemClock.elapsedRealtime()
            val state = RunState(plan, stageWeights(plan, estimates), ReviewTimeLeftTracker(speeds, plies), runStartedAt)
            state.emit()
            diagnostics.log(AppDiagnostics.TAG_ANALYSIS, "review plan for $gameId: ${plan.joinToString { it.name.lowercase() }}")

            // The tactic significance gate (ANALYSIS_SPEC §9.6) has to be *correct*, so read the
            // persisted threshold rather than the eagerly-seeded StateFlow (see CLAUDE.md).
            val tacticThresholdCp = currentSettings.narrationThresholdCp
            val timeLeft = AnalysisTimeLeftTracker(
                typicalNodes = currentSettings.searchBudget.typicalNodes,
                // Auto-depth's second pass (ANALYSIS_SPEC §8.5) is counted in its own, dearer positions.
                deepTypicalNodes = if (currentSettings.autoDepth) AutoDepthBudget.TYPICAL_DEEPENED_NODES else 0L,
            )
            var lastEngine = AnalysisProgress(phase = AnalysisPhase.PREPARING_ENGINE)
            val outcome = analysisService.analyze(
                pgnText = pgnText,
                username = currentSettings.username,
                settings = currentSettings,
                onPositionEvaluated = ::recordPositionEval,
                onProgress = { p ->
                    val shown = timeLeft.onProgress(p)
                    val withTime = p.copy(runStartedAtMs = runStartedAt, timeLeft = shown)
                    _progress.value = withTime
                    if (p.runPositionsSearched > 0) lastEngine = p
                    state.engine = withTime
                    if (p.phase == AnalysisPhase.ANALYZING_MOVES && p.totalMoves > 0 && p.currentMoveIndex < p.totalMoves) {
                        state.engineRemainingMs = estimateAnalysisRemainingMs(
                            done = p.currentMoveIndex,
                            total = p.totalMoves,
                            runNodes = p.runNodes,
                            runMs = p.runSearchMs,
                            runPositions = p.runPositionsSearched,
                            typicalNodes = currentSettings.searchBudget.typicalNodes,
                        ) ?: state.engineRemainingMs
                    }
                    // "Starting…" and the final DONE update carry no counts: they must not reset the stage's own.
                    if (p.totalMoves > 0) state.stageProgress(ReviewStage.ENGINE, p.currentMoveIndex, p.totalMoves) else state.emit()
                },
            )
            when (outcome) {
                is AnalysisService.Outcome.Success -> {
                    withContext(Dispatchers.IO) {
                        app.reviewSpeedStore.update { it.withEngine(lastEngine.runSearchMs, lastEngine.runNodes, lastEngine.runPositionsSearched) }
                    }
                    val header = outcome.game.toHeader()
                    val previous = app.gameRepository.load(gameId)
                    // RF: the side picked on the Review options screen is the user's answer. A plain reopen keeps the
                    // old rule: an answer from an earlier visit, then the side the game opened with (a famous game:
                    // "Not me"), then the username detection.
                    val resolved = resolveSide(
                        stored = SideChoice.fromStored(previous?.userColorName),
                        detected = outcome.userColor?.toUiColor(),
                        initial = SideChoice.fromStored(request.initialSide),
                    )
                    val side = if (fullReview && options.side != SideChoice.UNKNOWN) options.side else resolved
                    if (options.side != side) gameOptions[gameId] = options.copy(side = side)
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
                        autoDepth = currentSettings.autoDepth,
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
                        .copy(cappedPositions = outcome.cappedPositions)
                    coreArtifacts[gameId] = CoreArtifacts(outcome.game, sidedReport, sideColor, header, side == SideChoice.NOT_ME, outcome.cappedPositions)
                    val fresh = GameRepository.StoredGame(
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
                        autoDepth = currentSettings.autoDepth,
                    )
                    // A full review stores its options with the game; a plain reopen keeps what the game had.
                    val toStore = if (fullReview) {
                        fresh.withReviewOptions(options.copy(side = side)).copy(depth = currentSettings.depth, autoDepth = currentSettings.autoDepth)
                    } else {
                        fresh.copy(
                            voiceProvider = previous?.voiceProvider,
                            speakerId = previous?.speakerId,
                            pace = previous?.pace,
                            naturalWording = previous?.naturalWording,
                            prepareVideo = previous?.prepareVideo,
                        )
                    }
                    app.gameRepository.save(toStore)
                    // Done with the engine: nothing left to resume.
                    withContext(Dispatchers.IO) { pendingStore.clear(gameId) }
                    app.gameRepository.clearPendingOptions(gameId)
                    pendingOptionsByGameId.remove(gameId)
                    pendingLevelByGameId.remove(gameId)
                    activeRequest = null
                    refreshRecentGames()
                    _pasteDraft.value = ""
                    if (fullReview && side.color != null) {
                        val current = app.settingsRepository.current()
                        usernameToRemember(header, side, current.username)?.let { name ->
                            app.settingsRepository.save(current.copy(username = name))
                        }
                    }
                    state.finish(ReviewStage.ENGINE, skipped = false)

                    runRemainingStages(state, gameId, plan, sidedReport, plies)
                    applyCachedWording(gameId)
                    startBackgroundWording(gameId)
                    _processingGame.value = null
                    // The analysis loop runs on Dispatchers.Default, but [onComplete] drives
                    // NavController, and NavController touches LifecycleRegistry, which throws
                    // "setCurrentState must be called on the main thread" off the main thread.
                    // Without this hop every *successful* analysis crashed the app at the moment
                    // it finished — the one path a user always takes.
                    withContext(Dispatchers.Main) { onComplete() }
                }
                is AnalysisService.Outcome.ParseError -> {
                    Log.w(TAG, "analysis failed: ${outcome.reason} ${outcome.detail.orEmpty()}")
                    diagnostics.error(AppDiagnostics.TAG_ANALYSIS, "analysis failed: ${outcome.reason} ${outcome.detail.orEmpty()}")
                    // A text that cannot be parsed will not parse after a restart either.
                    withContext(Dispatchers.IO) { pendingStore.clear(gameId) }
                    _error.value = outcome.reason
                }
                is AnalysisService.Outcome.EngineError -> {
                    Log.w(TAG, "analysis failed: ${outcome.reason} ${outcome.detail.orEmpty()}")
                    diagnostics.error(AppDiagnostics.TAG_ANALYSIS, "analysis failed: ${outcome.reason} ${outcome.detail.orEmpty()}")
                    if (outcome.reason == AnalysisService.Failure.SETUP_REQUIRED) {
                        // D2c: no engine data. The game is not lost: it moves from the in-flight request to the
                        // waiting file and is analysed once setup has installed the net ("Set up" on this screen).
                        withContext(Dispatchers.IO) {
                            waitingStore.save(request)
                            pendingStore.clear(gameId)
                        }
                        _gameWaitingForSetup.value = true
                        activeRequest = null
                    }
                    _error.value = outcome.reason
                }
                AnalysisService.Outcome.Cancelled -> Unit
            }
        }
    }

    /** Stages 2 to 4 of [plan], in order, each skippable; the engine stage is done when this runs. */
    private suspend fun runRemainingStages(state: RunState, gameId: String, plan: List<ReviewStage>, report: CoreGameReport, plies: Int) {
        if (ReviewStage.POLISH_COMMENTARY in plan) {
            // C2: the key moments of the current side, now; the rest lazily in the background afterwards.
            runStage(state, ReviewStage.POLISH_COMMENTARY, plies) { progress ->
                val items = RephraseService.keyMomentTexts(report)
                progress(0, items.size)
                if (app.gameRephraseService.pending(items) > 0) app.gameRephraseService.polish(items, progress)
                progress(items.size, items.size)
            }
            applyCachedWording(gameId)
        }
        if (ReviewStage.WRITE_NARRATION in plan) {
            // The paced script for this game's voice and pace, its beats reworded when the game's wording is on.
            runStage(state, ReviewStage.WRITE_NARRATION, plies) { progress ->
                val base = videoScriptFor(gameId) ?: return@runStage
                if (wordingFor(gameId)) {
                    val items = RephraseService.narrationOrder(base)
                    progress(0, items.size)
                    if (app.gameRephraseService.pending(items) > 0) polishNarration(gameId, progress)
                    progress(items.size, items.size)
                } else {
                    progress(1, 1)
                }
            }
        }
        if (ReviewStage.RECORD_VOICE in plan) {
            runStage(state, ReviewStage.RECORD_VOICE, plies) { progress ->
                val script = finishedVideoScriptFor(gameId) ?: return@runStage
                val provider = buildNarrationProvider(gameId) ?: return@runStage
                // The wording model holds ~1.2 GB of native memory; the voice model needs its own. Free it first.
                releaseWordingModel()
                narrationPreparer.prepare(script, provider, progress)
                markNarrationPrepared(gameId, script, provider)
            }
        }
    }

    /** Frees the wording model before the voice is loaded (design §2.2: one big model at a time). */
    private suspend fun releaseWordingModel() {
        app.releaseWordingModel()
    }

    /**
     * The user's own Cancel (or Back) on the processing screen: stops the run and forgets the saved
     * request, so it is not resumed on the next launch. The partial eval cache stays, so analysing
     * the same game again later still starts from where this run stopped. A game the run had not stored yet is
     * forgotten with its pending options; a stored game keeps its old result and options.
     */
    fun cancelAnalysis() {
        analysisJob?.cancel()
        stageJob?.cancel()
        val request = activeRequest
        activeRequest = null
        _processingGame.value = null
        diagnostics.log(AppDiagnostics.TAG_ANALYSIS, "analysis cancelled by the user")
        val gameId = request?.gameId
        if (gameId != null) {
            pendingOptionsByGameId.remove(gameId)
            pendingLevelByGameId.remove(gameId)
        }
        viewModelScope.launch(Dispatchers.IO) {
            pendingStore.clear(gameId)
            if (gameId != null) app.gameRepository.clearPendingOptions(gameId)
        }
    }

    /**
     * At most once per ViewModel, at app start: the id of an analysis a killed process left
     * unfinished, to be resumed by opening its Analysing screen. Null when there is none, when the
     * screen is already showing one, or when the request is older than
     * [PendingAnalysisStore.AUTO_RESUME_MAX_AGE_MS] (it is then dropped).
     */
    suspend fun resumableAnalysisOnLaunch(): String? {
        if (resumeChecked) return null
        resumeChecked = true
        val request = withContext(Dispatchers.IO) { pendingStore.load() } ?: return null
        val age = System.currentTimeMillis() - request.startedAtMs
        if (age !in 0..PendingAnalysisStore.AUTO_RESUME_MAX_AGE_MS) {
            diagnostics.log(AppDiagnostics.TAG_ANALYSIS, "dropping a saved analysis request ${age / 60_000} min old")
            withContext(Dispatchers.IO) { pendingStore.clear() }
            return null
        }
        diagnostics.log(AppDiagnostics.TAG_ANALYSIS, "an unfinished analysis is on disk at launch: ${request.gameId}")
        return request.gameId
    }

    /** The share intent for the diagnostic log (Settings, the analysis error screen); null if it cannot be prepared. */
    fun diagnosticShareIntent(context: Context): Intent? = app.diagnostics.shareIntent(context)

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

    /** V1: the narrator voice picked in Settings (a Kokoro sid). The default for new games since RF. */
    fun setNarratorSpeaker(sid: Int) {
        viewModelScope.launch { app.narrationSettingsRepository.setSpeakerId(sid) }
    }

    /** V3: Settings, Video, Pace (the default for new games since RF). */
    fun setVideoPace(pace: VideoPace) {
        viewModelScope.launch { app.settingsRepository.setVideoPace(pace) }
    }

    // ---- RF: one game's voice and pace (the Video screen's "Voice and pace" sheet) ----

    /**
     * Changes [gameId]'s narration voice (the Video screen's sheet): stored with the game, and the Video route then
     * rebuilds the script and the provider and records the narration in the new voice inline (item 3 of the owner's
     * phone test). [provider] DEVICE is a valid explicit choice and needs no recording.
     */
    fun setGameVoice(gameId: String, provider: NarrationProviderChoice, sid: Int) {
        val current = gameOptions[gameId] ?: return
        updateGameOptions(gameId, current.copy(voice = provider, speakerId = sid))
    }

    /** Changes [gameId]'s video pace (the Video screen's sheet). The recordings stay valid (same sentences). */
    fun setGamePace(gameId: String, pace: VideoPace) {
        val current = gameOptions[gameId] ?: return
        updateGameOptions(gameId, current.copy(pace = pace))
    }

    private fun updateGameOptions(gameId: String, options: ReviewOptions) {
        if (gameOptions[gameId] == options) return
        gameOptions[gameId] = options
        videoScripts.keys.removeAll { isKeyOfGame(it, gameId) }
        viewModelScope.launch {
            app.gameRepository.load(gameId)?.let { stored -> app.gameRepository.save(stored.withReviewOptions(options).copy(depth = stored.depth)) }
        }
    }

    /**
     * V1: "Play sample" in the narrator voice picker. Created on first use; [releaseVoiceSamples] (the
     * picker closing) stops the sample and frees the Kokoro engine it loaded.
     */
    val voiceSamples: VoiceSamplePlayer by lazy {
        VoiceSamplePlayer(
            cacheDir = app.cacheDir,
            modelDir = { voiceStore.modelDir },
            voiceVersionId = { if (voiceStore.isInstalled()) voiceStore.installedVersionId() else null },
            scope = viewModelScope,
        )
    }
    private var voiceSamplesUsed = false

    fun playVoiceSample(sid: Int) {
        voiceSamplesUsed = true
        voiceSamples.play(sid)
    }

    fun stopVoiceSample() {
        if (voiceSamplesUsed) voiceSamples.stop()
    }

    fun releaseVoiceSamples() {
        if (voiceSamplesUsed) voiceSamples.release()
    }

    override fun onCleared() {
        backgroundWording?.cancel()
        prepJob?.cancel()
        releaseVoiceSamples()
        super.onCleared()
    }

    /**
     * Builds the [NarrationVoiceProvider] the global settings select, or null for the device default (Settings' own
     * uses). The rule itself is [selectNarrationProvider] (pure, tested on its own).
     */
    fun buildNarrationProvider(): NarrationVoiceProvider? = providerFor(narrationVoiceSettings.value)

    /** RF: the provider [gameId]'s own voice selects (the player, the recording and the export of that game). */
    fun buildNarrationProvider(gameId: String): NarrationVoiceProvider? =
        providerFor(gameOptions[gameId]?.voiceSettings ?: narrationVoiceSettings.value)

    private fun providerFor(voice: NarrationVoiceSettings): NarrationVoiceProvider? =
        when (val selection = selectNarrationProvider(voice) { voiceStore.isInstalled() }) {
            NarrationProviderSelection.Device -> null
            is NarrationProviderSelection.Neural ->
                NeuralTtsProvider(
                    tier = selection.tier,
                    modelDir = voiceStore.modelDir,
                    speakerId = selection.speakerId,
                    voiceVersionId = voiceStore.installedVersionId(),
                )
        }

    // ---- RF: one game's delete (Home's library menu) ----

    /**
     * Deletes [gameId]: its stored JSON, its pending options, its in-memory state, the pending or waiting request when
     * it is this game's, and its eval cache when no other stored game has the same text ([deletePlan]). The narration
     * audio stays (shared, keyed by sentence; Settings clears it).
     */
    fun deleteGame(gameId: String) {
        _recentGames.value = libraryAfterDelete(_recentGames.value, gameId)
        viewModelScope.launch {
            val repo = app.gameRepository
            val stored = repo.load(gameId)
            val plan = deletePlan(
                gameId = gameId,
                pendingRequestGameId = withContext(Dispatchers.IO) { pendingStore.load()?.gameId },
                waitingGameId = withContext(Dispatchers.IO) { waitingStore.load()?.gameId },
                pgnText = stored?.pgnText,
                otherGamesPgnTexts = repo.storedPgnTexts(exceptId = gameId),
            )
            if (activeRequest?.gameId == gameId) {
                analysisJob?.cancel()
                activeRequest = null
            }
            if (plan.clearPendingRequest) withContext(Dispatchers.IO) { pendingStore.clear(gameId) }
            if (plan.clearWaitingGame) {
                withContext(Dispatchers.IO) { waitingStore.clear(gameId) }
                _gameWaitingForSetup.value = false
            }
            if (plan.deleteEvalCache && stored != null) {
                val keys = AnalysisStrength.entries.mapNotNull { s ->
                    runCatching { repo.cacheKey(stored.pgnText, s.depth, stored.multiPv, s.budget.cacheKeyPart) }.getOrNull()
                } + repo.cacheKey(stored.pgnText, stored.depth, stored.multiPv, SearchBudget.forDepth(stored.depth).cacheKeyPart)
                val deleted = repo.deleteEvalCacheFor(keys.distinct())
                diagnostics.log(AppDiagnostics.TAG_ANALYSIS, "deleted $deleted eval-cache file(s) of $gameId")
            }
            repo.delete(gameId)
            games.remove(gameId)
            reports.remove(gameId)
            coreArtifacts.remove(gameId)
            wordedReports.remove(gameId)
            gameOptions.remove(gameId)
            videoScripts.keys.removeAll { isKeyOfGame(it, gameId) }
            practiceSets.keys.removeAll { isKeyOfGame(it, gameId) }
            solvedPuzzlePlies.remove(gameId)
            pendingPgnByGameId.remove(gameId)
            pendingSideByGameId.remove(gameId)
            pendingLevelByGameId.remove(gameId)
            pendingOptionsByGameId.remove(gameId)
            preparedNarration.removeAll { it.startsWith("$gameId|") }
            diagnostics.log(AppDiagnostics.TAG_ANALYSIS, "game $gameId deleted by the user")
            refreshRecentGames()
        }
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
     * [NarrationOptions] matching the voice that will actually speak this script, the review detail and
     * the pace (the rule is [narrationOptionsFor], pure and host-tested), from the GLOBAL settings (Settings' own uses).
     * [NarrationOptions.speechWpm] is what `VideoScriptGenerator.estimateSpeechMs` uses to lay the
     * timeline out *before* TTS runs, so it has to describe the real voice: each Kokoro speaker was
     * measured at its own rate (V1: 127 to 177 wpm at `length_scale` 1.20), and estimating the wrong
     * rate both drifts the board away from the narration and misjudges the length budget.
     */
    suspend fun narrationOptionsForCurrentVoice(): NarrationOptions {
        // Everything here is read from the repositories, not from the eagerly-seeded StateFlows, which
        // serve defaults until DataStore's first emission lands (see the class docs on
        // NarrationSettingsRepository): the threshold decides which moves are narrated, the speaker's
        // measured rate feeds the length budget (V1), and the pace (V3) is what the user asked for.
        val voice = app.narrationSettingsRepository.current()
        val installed = withContext(Dispatchers.IO) { voiceStore.isInstalled() }
        return narrationOptionsFor(voice, installed, app.settingsRepository.current())
    }

    /**
     * RF: the [NarrationOptions] of [gameId]: its own voice (the speaker's measured rate) and pace; the review detail
     * (threshold) stays the Settings value, as for the Summary. Falls back to the global settings for a game with no
     * options in memory.
     */
    suspend fun narrationOptionsForGame(gameId: String): NarrationOptions {
        val options = gameOptions[gameId] ?: return narrationOptionsForCurrentVoice()
        val installed = withContext(Dispatchers.IO) { voiceStore.isInstalled() }
        return narrationOptionsFor(options.voiceSettings, installed, app.settingsRepository.current().copy(videoPace = options.pace))
    }

    /**
     * Builds (and caches) the narrated-video script of [gameId] with ITS options (voice rate, pace). Suspends because
     * the significance threshold is read from persisted settings; the caller shows a spinner for the one frame that takes.
     */
    suspend fun videoScriptFor(gameId: String): VideoScript? {
        // The language is read from the repository, not the eagerly-seeded StateFlow, for the
        // same reason as the threshold: it decides what the script *says*, so it has to be right.
        val language = app.settingsRepository.current().language
        return videoScriptFor(gameId, narrationOptionsForGame(gameId), AppLocales.narrationTag(app, language))
    }

    /**
     * Deterministic and pure per [VideoScriptGenerator]'s contract, so the cache never goes stale for a given
     * [gameId]/[options] pair.
     *
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

    // ---- C2: the narration post-pass (docs/LLM_REPHRASE_DESIGN.md §6.2), RF: inside the processing run ----
    //
    // The processing run's "Writing the video narration" stage rewords the beats (`polishNarration`); the Video route
    // only reads the result (`finishedVideoScriptFor`) and never calls the model. The narration WAV cache is keyed by
    // sentence text, so the originals' WAVs stay valid for the wording-off case; the export never calls the model.

    /**
     * The finished, paced script of [gameId]: with the game's wording on, every cached narration rewording applied by
     * the pure [net.palaya.chessanalyzer.core.text.RephrasedScript] post-pass (allowlisted prose beats only, speech
     * re-estimated, lead-ins/holds/boards untouched, the story capped at 1.10x and the §9.7 budget); else the original.
     * Reads files only, never the model. Cached per rephraser id.
     */
    suspend fun finishedVideoScriptFor(gameId: String): VideoScript? {
        val base = videoScriptFor(gameId) ?: return null
        if (!withContext(Dispatchers.IO) { wordingFor(gameId) }) return base
        val service = app.gameRephraseService
        val id = service.activeId() ?: return base
        val options = narrationOptionsForGame(gameId)
        val key = "rephrased:$gameId:${options}:$id:${base.hashCode()}"
        videoScripts[key]?.let { return it }
        val fullMoves = ((coreArtifacts[gameId]?.report?.annotations?.size ?: 0) + 1) / 2 // ScriptBuilder.budgetMs
        val worded = withContext(Dispatchers.IO) {
            service.applyCachedScript(base, options.speechWpm, VideoScriptGenerator.budgetMs(fullMoves))
        }
        videoScripts[key] = worded
        return worded
    }

    /** The name the C2 tests know: the same as [finishedVideoScriptFor]. */
    suspend fun rephrasedVideoScriptFor(gameId: String): VideoScript? = finishedVideoScriptFor(gameId)

    /** How many narration beats of [gameId] still need the model (0 with the game's wording off). */
    suspend fun narrationPolishPending(gameId: String): Int {
        if (!wordingFor(gameId)) return 0
        val base = videoScriptFor(gameId) ?: return 0
        return app.gameRephraseService.pending(RephraseService.narrationOrder(base))
    }

    /**
     * Rewords every eligible beat of [gameId]'s narration that is not cached yet, one request per beat (the sentences
     * of a beat refer to each other). Cancel the calling coroutine to Skip.
     */
    suspend fun polishNarration(gameId: String, onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }): RephraseService.Stats? {
        val base = videoScriptFor(gameId) ?: return null
        val stats = app.gameRephraseService.polish(RephraseService.narrationOrder(base), onProgress)
        videoScripts.keys.removeAll { it.startsWith("rephrased:$gameId:") }
        return stats
    }

    // ---- RF: the Video screen's inline narration recording ----

    private val _narrationPrep = MutableStateFlow<NarrationPrepState>(NarrationPrepState.Idle)

    /** What the Video screen's player area shows about the narration recording of the game on screen. */
    val narrationPrep: StateFlow<NarrationPrepState> = _narrationPrep

    @Volatile private var prepJob: Job? = null
    private val prepVersion = AtomicInteger(0)

    /**
     * (game, voice fingerprint, script) triples recorded in this process, so an attempt that left segments unrecorded
     * (the device voice reads them) is not repeated every time the screen opens; "Prepare the narration" forces it.
     */
    private val preparedNarration: MutableSet<String> = java.util.Collections.synchronizedSet(HashSet())

    private fun preparationKey(gameId: String, script: VideoScript, provider: NarrationVoiceProvider): String =
        "$gameId|${provider.displayName}|${provider.narrationCacheFingerprint()}|${script.hashCode()}"

    private fun markNarrationPrepared(gameId: String, script: VideoScript, provider: NarrationVoiceProvider) {
        preparedNarration += preparationKey(gameId, script, provider)
    }

    /**
     * Makes sure the narration of [gameId]'s finished script is recorded in the game's voice before it plays: when it
     * is ([narrationNeedsPreparation] says no) the state is Ready at once; otherwise the missing segments are recorded
     * here, with progress and a measured time left, then Ready. The Video route calls it when it opens and whenever the
     * game's voice or pace changes; [force] = the user's "Prepare the narration" after a Cancel.
     */
    fun ensureNarrationPrepared(gameId: String, force: Boolean = false) {
        prepJob?.cancel()
        prepJob = viewModelScope.launch(Dispatchers.Default) {
            val script = finishedVideoScriptFor(gameId) ?: return@launch
            val voice = gameOptions[gameId]?.voice ?: narrationVoiceSettings.value.provider
            val provider = buildNarrationProvider(gameId)
            val installed = withContext(Dispatchers.IO) { voiceStore.isInstalled() }
            val missing = if (provider == null) 0 else withContext(Dispatchers.IO) { narrationPreparer.missingSegments(script, provider) }
            if (provider == null || !narrationNeedsPreparation(voice, installed, missing)) {
                _narrationPrep.value = NarrationPrepState.Ready(gameId, prepVersion.get())
                return@launch
            }
            val key = preparationKey(gameId, script, provider)
            if (!force && key in preparedNarration) {
                _narrationPrep.value = NarrationPrepState.Incomplete(gameId, missing)
                return@launch
            }
            releaseWordingModel()
            val timeLeft = ExportTimeLeftTracker()
            _narrationPrep.value = NarrationPrepState.Preparing(gameId, 0, script.segments.size, net.palaya.chessanalyzer.video.ExportTimeLeft.Hidden)
            try {
                narrationPreparer.prepare(script, provider) { done, total ->
                    val left = timeLeft.onProgress(done, total, SystemClock.elapsedRealtime())
                    _narrationPrep.value = NarrationPrepState.Preparing(gameId, done, total, left)
                }
                preparedNarration += key
                val after = withContext(Dispatchers.IO) { narrationPreparer.missingSegments(script, provider) }
                _narrationPrep.value = if (after == 0) {
                    NarrationPrepState.Ready(gameId, prepVersion.incrementAndGet())
                } else {
                    NarrationPrepState.Incomplete(gameId, after)
                }
            } catch (e: CancellationException) {
                val after = withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) { narrationPreparer.missingSegments(script, provider) }
                _narrationPrep.value = NarrationPrepState.Incomplete(gameId, after)
                prepVersion.incrementAndGet()
                throw e
            }
        }
    }

    /** Cancel in the player area, or the Video screen closing: stops the recording (what is done stays). */
    fun cancelNarrationPreparation() {
        prepJob?.cancel()
        prepJob = null
    }

    private fun refreshRecentGames() {
        viewModelScope.launch { _recentGames.value = app.gameRepository.listRecent() }
    }

    private companion object {
        const val TAG = "AnalysisViewModel"
    }
}
