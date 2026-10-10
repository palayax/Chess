package net.palaya.chessanalyzer.ui.viewmodel

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.mutableStateMapOf
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
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
import net.palaya.chessanalyzer.data.PendingAnalysisStore
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
import net.palaya.chessanalyzer.ui.model.gameAnalysisDepth
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
import net.palaya.chessanalyzer.video.narrationOptionsFor
import net.palaya.chessanalyzer.video.VoiceSamplePlayer
import net.palaya.chessanalyzer.core.narration.VideoPace
import net.palaya.chessanalyzer.video.VoiceStore

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

    /** The narration voice, downloaded once by the Setup flow (`ModelSetup`); device TTS narrates until then. */
    private val voiceStore: VoiceStore get() = app.voiceStore

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

    @Volatile private var polishJob: Job? = null
    @Volatile private var backgroundWording: Job? = null

    /** The Analysing screen's Skip during "Polishing the commentary": the originals stay, the rest goes on lazily. */
    fun skipPolishing() {
        polishJob?.cancel()
    }

    /**
     * Swaps every cached rewording of [gameId]'s card texts into the UI copies (file reads only, no model). A card
     * already on screen keeps its words until it is composed again (design §6.2).
     */
    private suspend fun applyCachedWording(gameId: String) {
        val artifacts = coreArtifacts[gameId] ?: return
        val worded = withContext(Dispatchers.IO) { app.rephraseService.applyCached(artifacts.report) }
        if (worded === artifacts.report) {
            wordedReports.remove(gameId)
            return
        }
        wordedReports[gameId] = worded
        games[gameId]?.let { g -> games[gameId] = g.copy(moves = worded.annotations.map { it.toMoveRecord() }) }
        val threshold = reports[gameId]?.tacticThresholdCp ?: 0
        reports[gameId] = worded.toUiReport(artifacts.header, artifacts.userColor?.toUiColor(), threshold, artifacts.sideNotMe, narrationStrings())
            .copy(cappedPositions = artifacts.cappedPositions)
    }

    /**
     * The blocking phase at the end of an analysis: the key moments of the current side, with progress and Skip.
     * Nothing happens when the feature is off, no model is installed, or every text is already cached.
     */
    private suspend fun polishKeyMoments(report: CoreGameReport) {
        val items = RephraseService.keyMomentTexts(report)
        val pending = app.rephraseService.pending(items)
        if (pending == 0) return
        _progress.value = AnalysisProgress(phase = AnalysisPhase.POLISHING_COMMENTARY, totalMoves = items.size, fractionComplete = 0f)
        val job = viewModelScope.launch(Dispatchers.Default) {
            app.rephraseService.polish(items) { done, total ->
                _progress.value = AnalysisProgress(
                    phase = AnalysisPhase.POLISHING_COMMENTARY, currentMoveIndex = done, totalMoves = total,
                    fractionComplete = if (total > 0) done.toFloat() / total else 1f,
                )
            }
        }
        polishJob = job
        job.join()
        polishJob = null
        if (job.isCancelled) diagnostics.log(RephraseService.TAG, "polishing skipped by the user")
    }

    /**
     * The lazy part (§6.2): every other card text of [gameId], key moments first then by distance from [aroundPly],
     * one worker, only while a screen of ours is in the foreground; the cards pick the rewordings up when next drawn.
     */
    fun startBackgroundWording(gameId: String, aroundPly: Int = 0) {
        val artifacts = coreArtifacts[gameId] ?: return
        backgroundWording?.cancel()
        backgroundWording = viewModelScope.launch(Dispatchers.Default) {
            val items = RephraseService.cardOrder(artifacts.report, aroundPly)
            if (app.rephraseService.pending(items) == 0) return@launch
            app.rephraseService.polish(items)
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
        // C2: the texts for the new side are different strings, so new cache keys: the originals show now, the
        // cached rewordings of this side (if any) are swapped in, and the background job refills the rest.
        wordedReports.remove(gameId)
        // The video script is written "to you" or "to White" depending on the side, and its cache
        // key does not include it, so a cached script for this game would now be stale.
        videoScripts.keys.removeAll { it.startsWith("$gameId:") }
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
     * The depth a registered game is analysed at when it is not the Settings default (A4): the strength a game
     * was analysed with when it is reopened, or the one picked for a re-analyse. Absent = the Settings default.
     */
    private val pendingDepthByGameId = mutableMapOf<String, Int>()

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
                ),
            )
            diagnostics.log(AppDiagnostics.TAG_ANALYSIS, "game $gameId kept until setup has installed the engine data")
        }
    }

    /**
     * The game waiting for setup, registered for analysis and removed from the waiting file; null when
     * none waits. Call only when the net is installed. The analysis then uses the settings in force now.
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
     * Registers PGN text to be analyzed once the caller navigates to the AnalysisProgress route.
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
            return PendingAnalysisStore.Request(
                gameId = gameId,
                pgnText = text,
                // A4: a re-analyse's pick, else the strength a reopened game was analysed with, else the Settings default.
                depth = gameAnalysisDepth(
                    explicit = pendingDepthByGameId[gameId],
                    stored = app.gameRepository.load(gameId)?.depth,
                    settingsDefault = current.depth,
                ),
                multiPv = current.multiPv,
                username = current.username,
                startedAtMs = System.currentTimeMillis(),
                initialSide = pendingSideByGameId[gameId]?.storedName,
            )
        }
        return withContext(Dispatchers.IO) { pendingStore.load() }
            ?.takeIf { it.gameId == gameId }
            ?.also {
                pendingPgnByGameId[gameId] = it.pgnText
                diagnostics.log(AppDiagnostics.TAG_ANALYSIS, "resuming $gameId from the saved request (process was restarted)")
            }
    }

    /**
     * Runs (or re-runs) analysis for [gameId], whose PGN text must already be registered via
     * [registerPendingImport]/[registerReopen] — or, after the process was killed, be on disk in
     * [PendingAnalysisStore]. Safe to call more than once for the same id (e.g. on recomposition)
     * — a prior in-flight job for a different id is cancelled first, since only one
     * [net.palaya.chessanalyzer.engine.StockfishEngine] exists for the app.
     *
     * The request is written to disk as the run starts and deleted when it succeeds or the user
     * cancels ([cancelAnalysis]), so a kill in between resumes from the per-ply checkpoint.
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
            )
            // The tactic significance gate (ANALYSIS_SPEC §9.6) has to be *correct*, so read the
            // persisted threshold rather than the eagerly-seeded StateFlow (see CLAUDE.md).
            val tacticThresholdCp = currentSettings.narrationThresholdCp
            val runStartedAt = SystemClock.elapsedRealtime()
            val timeLeft = AnalysisTimeLeftTracker(currentSettings.searchBudget.typicalNodes)
            val outcome = analysisService.analyze(
                pgnText = pgnText,
                username = currentSettings.username,
                settings = currentSettings,
                onProgress = { p ->
                    val shown = timeLeft.onProgress(p)
                    _progress.value = p.copy(runStartedAtMs = runStartedAt, timeLeft = shown)
                },
            )
            when (outcome) {
                is AnalysisService.Outcome.Success -> {
                    val header = outcome.game.toHeader()
                    // The username auto-detection only covers a user who set a name. An answer to the
                    // Summary's "Which side were you?" from an earlier visit to this game wins over
                    // it (explicit, per game), so reopening a game keeps "you" / "Not me". A famous
                    // game opens as "Not me" (the request's initial side) unless answered otherwise.
                    val side = resolveSide(
                        stored = SideChoice.fromStored(app.gameRepository.load(gameId)?.userColorName),
                        detected = outcome.userColor?.toUiColor(),
                        initial = SideChoice.fromStored(request.initialSide),
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
                        .copy(cappedPositions = outcome.cappedPositions)
                    coreArtifacts[gameId] = CoreArtifacts(outcome.game, sidedReport, sideColor, header, side == SideChoice.NOT_ME, outcome.cappedPositions)
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
                    // Done: nothing left to resume.
                    withContext(Dispatchers.IO) { pendingStore.clear(gameId) }
                    activeRequest = null
                    refreshRecentGames()
                    _pasteDraft.value = ""
                    // C2: the key moments are reworded now (skippable), the rest lazily in the background.
                    try {
                        polishKeyMoments(sidedReport)
                        applyCachedWording(gameId)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        diagnostics.error(RephraseService.TAG, "polishing failed; the original texts stay", e)
                    }
                    startBackgroundWording(gameId)
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

    /**
     * "Re-analyse" on the Summary (A4): analyse [gameId] again with [strength] instead of the one it has. The
     * game's text and its answer to "Which side were you?" are kept; its in-memory result is dropped so the
     * next [runAnalysis] runs (or reads the eval cache of that strength: the budget is in the key, so an
     * earlier run at the same strength is instant). The choice is stored with the game once the run finishes,
     * so reopening the game keeps it; a cancelled run leaves the old result and the old strength untouched.
     *
     * The Summary showing now keeps its report until the new one replaces it (the screen pops itself when a
     * report disappears). Returns false when the game is not in memory.
     */
    fun reanalyseGame(gameId: String, strength: AnalysisStrength): Boolean {
        val pgnText = games[gameId]?.pgnText?.takeIf { it.isNotBlank() } ?: return false
        pendingPgnByGameId[gameId] = pgnText
        pendingDepthByGameId[gameId] = strength.depth
        // The side the game opened with must not come back (a famous game's "Not me" is an answer the user
        // may have changed); the stored answer is what runAnalysis reads.
        pendingSideByGameId.remove(gameId)
        activeRequest = null
        games.remove(gameId)
        videoScripts.keys.removeAll { it.startsWith("$gameId:") }
        practiceSets.keys.removeAll { it.startsWith("$gameId:") }
        solvedPuzzlePlies.remove(gameId)
        _error.value = null
        diagnostics.log(AppDiagnostics.TAG_ANALYSIS, "re-analysing $gameId at ${strength.name.lowercase()} (depth ${strength.depth})")
        return true
    }

    /**
     * The user's own Cancel (or Back) on the Analysing screen: stops the run and forgets the saved
     * request, so it is not resumed on the next launch. The partial eval cache stays, so analysing
     * the same game again later still starts from where this run stopped.
     */
    fun cancelAnalysis() {
        analysisJob?.cancel()
        val request = activeRequest
        activeRequest = null
        diagnostics.log(AppDiagnostics.TAG_ANALYSIS, "analysis cancelled by the user")
        viewModelScope.launch(Dispatchers.IO) { pendingStore.clear(request?.gameId) }
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

    /** V1: the narrator voice picked in Settings (a Kokoro sid). */
    fun setNarratorSpeaker(sid: Int) {
        viewModelScope.launch { app.narrationSettingsRepository.setSpeakerId(sid) }
    }

    /** V3: Settings, Video, Pace. */
    fun setVideoPace(pace: VideoPace) {
        viewModelScope.launch { app.settingsRepository.setVideoPace(pace) }
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
        releaseVoiceSamples()
        super.onCleared()
    }

    /**
     * Builds the [NarrationVoiceProvider] the current settings select, or null for the device
     * default. The rule itself is [selectNarrationProvider] (pure, tested on its own); this only
     * turns the selection into a live object.
     */
    fun buildNarrationProvider(): NarrationVoiceProvider? =
        when (val selection = selectNarrationProvider(narrationVoiceSettings.value) { voiceStore.isInstalled() }) {
            NarrationProviderSelection.Device -> null
            is NarrationProviderSelection.Neural ->
                NeuralTtsProvider(
                    tier = selection.tier,
                    modelDir = voiceStore.modelDir,
                    speakerId = selection.speakerId,
                    voiceVersionId = voiceStore.installedVersionId(),
                )
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
     * [NarrationOptions] matching the voice that will actually speak this script, the review detail and
     * the pace (the rule is [narrationOptionsFor], pure and host-tested).
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

    // ---- C2: the narration post-pass (docs/LLM_REPHRASE_DESIGN.md §6.2) ----
    //
    // Wired into the Video route (ChessAnalyzerNavHost): `narrationPolishPending` decides whether the "Polishing the
    // narration" pre-step shows, `polishNarration` runs it (Skip = cancel), and `rephrasedVideoScriptFor` is the script the
    // VideoScreen, the player, NarrationCoordinator and the exporter get. The narration WAV cache is keyed by sentence text,
    // so the originals' WAVs stay valid for the setting-off case; the export never calls the model.

    /**
     * The finished, paced script of [gameId] with every cached narration rewording applied by the pure
     * [net.palaya.chessanalyzer.core.text.RephrasedScript] post-pass (allowlisted prose beats only, speech re-estimated,
     * lead-ins/holds/boards untouched, the story capped at 1.10x and the §9.7 budget). Reads files only. Cached per
     * rephraser id, so turning the feature off or a model update shows the right words at once.
     */
    suspend fun rephrasedVideoScriptFor(gameId: String): VideoScript? {
        val base = videoScriptFor(gameId) ?: return null
        val id = app.rephraseService.activeId() ?: return base
        val options = narrationOptionsForCurrentVoice()
        val key = "rephrased:$gameId:${options}:$id:${base.hashCode()}"
        videoScripts[key]?.let { return it }
        val fullMoves = ((coreArtifacts[gameId]?.report?.annotations?.size ?: 0) + 1) / 2 // ScriptBuilder.budgetMs
        val worded = withContext(Dispatchers.IO) {
            app.rephraseService.applyCachedScript(base, options.speechWpm, VideoScriptGenerator.budgetMs(fullMoves))
        }
        videoScripts[key] = worded
        return worded
    }

    /** How many narration beats of [gameId] still need the model: 0 = no "Polishing the narration" step to show. */
    suspend fun narrationPolishPending(gameId: String): Int {
        val base = videoScriptFor(gameId) ?: return 0
        return app.rephraseService.pending(RephraseService.narrationOrder(base))
    }

    /**
     * The Video screen's "Polishing the narration… N of M" pass: rewords every eligible beat that is not cached yet,
     * one request per beat (the sentences of a beat refer to each other). Cancel the calling coroutine to Skip.
     */
    suspend fun polishNarration(gameId: String, onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }): RephraseService.Stats? {
        val base = videoScriptFor(gameId) ?: return null
        val stats = app.rephraseService.polish(RephraseService.narrationOrder(base), onProgress)
        videoScripts.keys.removeAll { it.startsWith("rephrased:$gameId:") }
        return stats
    }

    private fun refreshRecentGames() {
        viewModelScope.launch { _recentGames.value = app.gameRepository.listRecent() }
    }

    private companion object {
        const val TAG = "AnalysisViewModel"
    }
}
