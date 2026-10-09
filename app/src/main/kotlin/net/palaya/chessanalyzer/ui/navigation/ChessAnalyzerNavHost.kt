package net.palaya.chessanalyzer.ui.navigation

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import net.palaya.chessanalyzer.ui.theme.tacticTypeName
import net.palaya.chessanalyzer.R
import androidx.compose.ui.res.stringResource
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import net.palaya.chessanalyzer.core.analysis.PracticeSet
import net.palaya.chessanalyzer.ui.model.canTryIt
import net.palaya.chessanalyzer.ui.model.initialPuzzleIndex
import net.palaya.chessanalyzer.ui.model.keyMomentPlies
import net.palaya.chessanalyzer.ui.model.practiceEntryState
import net.palaya.chessanalyzer.ui.model.practicePlies
import net.palaya.chessanalyzer.ui.model.SideChoice
import net.palaya.chessanalyzer.ui.model.sideChoice
import net.palaya.chessanalyzer.ui.screens.AboutScreen
import net.palaya.chessanalyzer.ui.screens.AnalysisProgressScreen
import net.palaya.chessanalyzer.ui.screens.RephraseSettingsSection
import net.palaya.chessanalyzer.ui.screens.GameReportScreen
import net.palaya.chessanalyzer.ui.screens.ImportScreen
import net.palaya.chessanalyzer.ui.screens.PracticeScreen
import net.palaya.chessanalyzer.ui.screens.ReviewScreen
import net.palaya.chessanalyzer.ui.screens.SettingsScreen
import net.palaya.chessanalyzer.ui.screens.UpdateSheet
import net.palaya.chessanalyzer.ui.model.updateRowLine
import net.palaya.chessanalyzer.ui.model.updateSheetView
import net.palaya.chessanalyzer.ui.viewmodel.UpdatesViewModel
import net.palaya.chessanalyzer.ui.screens.TacticSimulationScreen
import net.palaya.chessanalyzer.ui.screens.VideoScreen
import kotlinx.coroutines.launch
import net.palaya.chessanalyzer.core.narration.VideoScript
import net.palaya.chessanalyzer.ui.viewmodel.AnalysisViewModel
import net.palaya.chessanalyzer.ui.viewmodel.SetupViewModel
import net.palaya.chessanalyzer.data.models.ModelDownloadService
import net.palaya.chessanalyzer.ui.model.NarrationProviderChoice
import net.palaya.chessanalyzer.ui.model.homeSetupCard
import net.palaya.chessanalyzer.ui.screens.SetupScreen
import net.palaya.chessanalyzer.ui.screens.FamousGamesScreen
import net.palaya.chessanalyzer.ui.screens.FamousGamesState
import net.palaya.chessanalyzer.data.FamousGamesStore
import androidx.compose.runtime.produceState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.palaya.chessanalyzer.util.readTextFromUri

/**
 * App-wide nav graph wiring the six screens together against [AnalysisViewModel], which owns
 * the real import -> analyze -> review -> report pipeline (see that class). [pendingImportPgn]
 * carries PGN text extracted from an incoming share/view [android.content.Intent] (see
 * `MainActivity.extractPgnFromIntent`); once consumed it's cleared via
 * [onPendingImportConsumed] so rotation doesn't re-trigger it.
 */
@Composable
fun ChessAnalyzerNavHost(
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController(),
    pendingImportPgn: String? = null,
    onPendingImportConsumed: () -> Unit = {},
    /** D2c: a tap on the setup download's notification asks for the Setup screen. */
    openSetupRequested: Boolean = false,
    onOpenSetupConsumed: () -> Unit = {},
) {
    val context = LocalContext.current
    val viewModel: AnalysisViewModel = viewModel()
    val setupViewModel: SetupViewModel = viewModel()
    val setupDisk by setupViewModel.disk.collectAsState()
    val setupRunning by ModelDownloadService.running.collectAsState()
    val setupProgress by ModelDownloadService.progress.collectAsState()
    val gameWaitingForSetup by viewModel.gameWaitingForSetup.collectAsState()
    // D2c: while the engine net is missing, Setup is the first screen (design §1.1). Decided once per
    // Activity: the NavHost's start destination must not change under it.
    val startRoute = remember { if (setupViewModel.netReady()) Destination.Import.route else Destination.Setup.route }
    val settings by viewModel.settings.collectAsState()
    val narrationVoiceSettings by viewModel.narrationVoiceSettings.collectAsState()
    val recentGames by viewModel.recentGames.collectAsState()
    val pasteDraft by viewModel.pasteDraft.collectAsState()
    // Home's transient messages (an unreadable file) are a Snackbar, not a Toast: it belongs to the
    // screen, is themed, and does not outlive navigation.
    val homeSnackbarHost = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    fun currentRoute(): String? = navController.currentBackStackEntry?.destination?.route

    /** Home as the only screen on the back stack (Setup may be the graph's start destination). */
    fun goHome() {
        navController.navigate(Destination.Import.route) {
            popUpTo(navController.graph.id) { inclusive = true }
            launchSingleTop = true
        }
    }

    fun goToSetup() {
        if (currentRoute() != Destination.Setup.route) {
            navController.navigate(Destination.Setup.route) { launchSingleTop = true }
        }
    }

    /** "Not now", "Continue in the background", "Continue": back where Setup was opened from, else Home. */
    fun leaveSetup() {
        if (navController.previousBackStackEntry != null) navController.popBackStack() else goHome()
    }

    /** [initialSide]: the side the game opens with (a famous game: "Not me"); UNKNOWN lets the username decide. */
    fun startAnalysis(pgnText: String, initialSide: SideChoice = SideChoice.UNKNOWN) {
        val id = viewModel.newGameId()
        if (!setupViewModel.netReady()) {
            // D2c: no engine data yet. The game waits on disk and is analysed once setup has the net in.
            viewModel.holdGameForSetup(id, pgnText, initialSide)
            goToSetup()
            return
        }
        viewModel.registerPendingImport(id, pgnText, initialSide)
        navController.navigate(Destination.AnalysisProgress.createRoute(id))
    }

    LaunchedEffect(pendingImportPgn) {
        val pgn = pendingImportPgn
        if (!pgn.isNullOrBlank()) {
            startAnalysis(pgn)
            onPendingImportConsumed()
        }
    }

    // F1: an analysis the system killed while the app was in the background resumes by itself.
    // When Android restored the Analysing screen, that screen resumes it; when the app starts fresh
    // at Home instead (task removed, or the state was not kept), it is reopened here, once.
    // D2c: then, whenever the engine net is in (at launch, or the moment setup installs it), a game that
    // was shared before setup is analysed, if the user is on Setup or Home (never over another screen).
    val netReady = setupDisk.netInstalled
    LaunchedEffect(netReady) {
        if (pendingImportPgn.isNullOrBlank()) {
            val gameId = viewModel.resumableAnalysisOnLaunch()
            if (gameId != null) {
                if (currentRoute() == Destination.Import.route) {
                    navController.navigate(Destination.AnalysisProgress.createRoute(gameId))
                }
                return@LaunchedEffect
            }
        }
        if (!netReady || !setupViewModel.netReady()) return@LaunchedEffect
        val route = currentRoute()
        if (route != Destination.Setup.route && route != Destination.Import.route) return@LaunchedEffect
        val waitingId = viewModel.takeGameWaitingForSetup() ?: return@LaunchedEffect
        if (route == Destination.Setup.route) goHome()
        navController.navigate(Destination.AnalysisProgress.createRoute(waitingId))
    }

    LaunchedEffect(openSetupRequested) {
        if (openSetupRequested) {
            goToSetup()
            onOpenSetupConsumed()
        }
    }

    // F1: the diagnostic log, shared from Settings and from the analysis error screen.
    fun shareDiagnosticLog() {
        val intent = viewModel.diagnosticShareIntent(context)
        val started = intent != null && try {
            val chooser = Intent.createChooser(intent, context.getString(R.string.diagnostic_share_chooser)).apply {
                // The app takes text shares itself (a PGN); the log must not be offered back to it.
                putExtra(
                    Intent.EXTRA_EXCLUDE_COMPONENTS,
                    arrayOf(android.content.ComponentName(context, net.palaya.chessanalyzer.MainActivity::class.java)),
                )
            }
            context.startActivity(chooser)
            true
        } catch (e: Exception) {
            false
        }
        if (!started) Toast.makeText(context, R.string.diagnostic_share_failed, Toast.LENGTH_LONG).show()
    }

    NavHost(
        navController = navController,
        startDestination = startRoute,
        modifier = modifier,
    ) {
        composable(Destination.Import.route) {
            ImportScreen(
                onFilePicked = { uri: Uri ->
                    val text = readTextFromUri(context.contentResolver, uri)
                    if (text.isNullOrBlank()) {
                        val message = context.getString(R.string.import_invalid_pgn)
                        scope.launch { homeSnackbarHost.showSnackbar(message) }
                    } else {
                        startAnalysis(text)
                    }
                },
                onPastePgnSubmit = { pastedText -> startAnalysis(pastedText) },
                onRecentGameSelected = { gameId ->
                    viewModel.registerReopen(gameId) {
                        navController.navigate(Destination.AnalysisProgress.createRoute(gameId))
                    }
                },
                recentGames = recentGames,
                onSettingsClick = { navController.navigate(Destination.Settings.route) },
                pastedPgn = pasteDraft,
                onPastedPgnChange = { viewModel.setPasteDraft(it) },
                snackbarHostState = homeSnackbarHost,
                setupCard = homeSetupCard(setupDisk, setupViewModel.sizes, setupRunning, setupProgress, gameWaitingForSetup),
                onSetupCardClick = { goToSetup() },
                onFamousGamesClick = { navController.navigate(Destination.FamousGames.route) { launchSingleTop = true } },
            )
        }

        composable(Destination.FamousGames.route) {
            // G1: read from the APK's assets once per process, off the main thread.
            val state by produceState<FamousGamesState>(FamousGamesState.Loading) {
                value = withContext(Dispatchers.IO) {
                    runCatching { FamousGamesStore.load(context) }
                        .fold({ FamousGamesState.Ready(it) }, { FamousGamesState.Failed })
                }
            }
            FamousGamesScreen(
                state = state,
                onBack = { navController.popBackStack() },
                // Exactly as if the game had been shared: analysed now, or kept for Setup while the net is missing.
                // One difference (G1-device): nobody in a famous game is the user, so it opens as "Not me"
                // (real names, no "you" wording); the Summary's chooser can still change it.
                onReview = { pgnText -> startAnalysis(pgnText, SideChoice.NOT_ME) },
            )
        }

        composable(Destination.Setup.route) {
            SetupScreen(
                viewModel = setupViewModel,
                gameWaiting = gameWaitingForSetup,
                onLeave = { leaveSetup() },
                onContinue = { leaveSetup() },
            )
        }

        composable(
            route = Destination.AnalysisProgress.route,
            arguments = listOf(navArgument(Destination.ARG_GAME_ID) { type = NavType.StringType }),
        ) { backStackEntry ->
            val gameId = backStackEntry.arguments?.getString(Destination.ARG_GAME_ID).orEmpty()
            val progress by viewModel.progress.collectAsState()
            val error by viewModel.error.collectAsState()

            // The analysis ends on the summary (the answer to "what went wrong"), not on the board.
            // popUpTo removes the progress screen, so system back from the summary lands on Home
            // instead of replaying an analysis that has already finished.
            val openSummary: () -> Unit = {
                navController.navigate(Destination.GameReport.createRoute(gameId)) {
                    popUpTo(Destination.AnalysisProgress.route) { inclusive = true }
                }
            }
            // Leaving this screen by any route must stop the analysis too, or it would finish in
            // the background and yank the user onto a summary they walked away from.
            val leave: () -> Unit = {
                viewModel.cancelAnalysis()
                viewModel.clearError()
                navController.popBackStack()
            }

            LaunchedEffect(gameId) { viewModel.runAnalysis(gameId, openSummary) }
            BackHandler(onBack = leave)

            AnalysisProgressScreen(
                progress = progress,
                onCancel = leave,
                error = error,
                onRetry = { viewModel.runAnalysis(gameId, openSummary) },
                onBack = leave,
                onShareDetails = { shareDiagnosticLog() },
                onSkipPolishing = { viewModel.skipPolishing() }, // C2
                // D2c: the game already waits on disk (AnalysisViewModel moved it there); Setup replaces this screen.
                onSetUp = {
                    viewModel.clearError()
                    navController.navigate(Destination.Setup.route) {
                        popUpTo(Destination.AnalysisProgress.route) { inclusive = true }
                        launchSingleTop = true
                    }
                },
            )
        }

        composable(
            route = Destination.Review.route,
            arguments = listOf(
                navArgument(Destination.ARG_GAME_ID) { type = NavType.StringType },
                // Optional: -1 means "open at the start", any other value opens at that ply.
                navArgument(Destination.ARG_PLY) { type = NavType.IntType; defaultValue = -1 },
                // Optional: true opens the ply's best-line mode (V2).
                navArgument(Destination.ARG_LINE) { type = NavType.BoolType; defaultValue = false },
            ),
        ) { backStackEntry ->
            val gameId = backStackEntry.arguments?.getString(Destination.ARG_GAME_ID).orEmpty()
            val requestedPly = backStackEntry.arguments?.getInt(Destination.ARG_PLY) ?: -1
            val openBestLine = backStackEntry.arguments?.getBoolean(Destination.ARG_LINE) ?: false
            val game = viewModel.games[gameId]
            if (game != null) {
                ReviewScreen(
                    game = game,
                    initialPly = requestedPly.takeIf { it >= 0 },
                    // The board opens from the user's side once the Summary has learned it.
                    userColor = viewModel.reports[gameId]?.userColor,
                    // The moments the Summary listed, so "Next key moment" visits exactly those.
                    keyMomentPlies = viewModel.reports[gameId]?.keyMomentPlies.orEmpty(),
                    openBestLine = openBestLine,
                    // A line plays at the video's line rate (Settings, Video, Pace).
                    playStepMs = settings.videoPace.lineMoveMinMs,
                    onShowMeClick = { move ->
                        if (move.core?.simulation != null) {
                            navController.navigate(Destination.Simulation.createRoute(gameId, move.ply))
                        }
                    },
                    onLearnPattern = { type -> navController.navigate(Destination.Reference.createRoute(type)) },
                    onBack = { navController.popBackStack() },
                )
            } else {
                // Game text was registered but never analyzed in this process (e.g. deep link
                // after process death) — send the user back to re-run analysis.
                LaunchedEffect(gameId) {
                    navController.navigate(Destination.AnalysisProgress.createRoute(gameId)) {
                        popUpTo(Destination.Review.route) { inclusive = true }
                    }
                }
            }
        }

        composable(
            route = Destination.GameReport.route,
            arguments = listOf(navArgument(Destination.ARG_GAME_ID) { type = NavType.StringType }),
        ) { backStackEntry ->
            val gameId = backStackEntry.arguments?.getString(Destination.ARG_GAME_ID).orEmpty()
            // Re-gated whenever the significance threshold changes (the settings flow serves the
            // seeded default until DataStore emits; keying on the value re-runs this when it lands).
            val thresholdCp = settings.narrationThresholdCp
            // `reports` is snapshot state, so reading it here re-runs the lookup when the user answers
            // "Which side were you?" (the answer swaps the stored report for a re-mapped one).
            val sideKey = viewModel.reports[gameId]?.let { it.userColor to it.notMe }
            val report = remember(gameId, thresholdCp, sideKey) { viewModel.uiReportFor(gameId, thresholdCp) }
            if (report != null) {
                // The practice row and the "Try it" buttons. Both read snapshot state (the report's
                // side, the solved map), so they follow the side chooser and a finished Practise.
                val practiceSet = viewModel.practiceSetFor(gameId)
                val solvedPlies = viewModel.solvedPliesFor(gameId)
                GameReportScreen(
                    report = report,
                    practiceEntry = practiceEntryState(report.sideChoice, practiceSet, solvedPlies),
                    practicePlies = practicePlies(practiceSet),
                    onPracticeClick = { navController.navigate(Destination.Practice.createRoute(gameId)) },
                    onTryIt = { ply -> navController.navigate(Destination.Practice.createRoute(gameId, ply)) },
                    onKeyMomentClick = { ply ->
                        navController.navigate(Destination.Review.createRoute(gameId, ply))
                    },
                    onShowMeClick = { ply ->
                        navController.navigate(Destination.Simulation.createRoute(gameId, ply))
                    },
                    onShowBestLine = { ply ->
                        navController.navigate(Destination.Review.createRoute(gameId, ply, bestLine = true))
                    },
                    onSideChosen = { viewModel.setUserSideForGame(gameId, it) },
                    onTacticClick = { ply ->
                        navController.navigate(Destination.Review.createRoute(gameId, ply))
                    },
                    onLearnPattern = { type -> navController.navigate(Destination.Reference.createRoute(type)) },
                    onWatchReviewClick = { navController.navigate(Destination.Video.createRoute(gameId)) },
                    onOpenBoardClick = { navController.navigate(Destination.Review.createRoute(gameId)) },
                    onBack = { navController.popBackStack() },
                )
            } else {
                // Nothing in memory for this game (process death restored the route but not the
                // analysis): a blank screen is a dead end, so go back to Home.
                LaunchedEffect(gameId) { navController.popBackStack() }
            }
        }

        composable(
            route = Destination.Practice.route,
            arguments = listOf(
                navArgument(Destination.ARG_GAME_ID) { type = NavType.StringType },
                // Optional: -1 means "the first unsolved position", any other value opens at that ply.
                navArgument(Destination.ARG_PLY) { type = NavType.IntType; defaultValue = -1 },
            ),
        ) { backStackEntry ->
            val gameId = backStackEntry.arguments?.getString(Destination.ARG_GAME_ID).orEmpty()
            val requestedPly = backStackEntry.arguments?.getInt(Destination.ARG_PLY)?.takeIf { it >= 0 }
            val puzzles = (viewModel.practiceSetFor(gameId) as? PracticeSet.Puzzles)?.puzzles
            if (puzzles != null) {
                val solvedPlies = viewModel.solvedPliesFor(gameId)
                // Read once, when the screen is first composed: re-entry resumes at the first unsolved.
                val initialIndex = remember(gameId, requestedPly) {
                    initialPuzzleIndex(puzzles, viewModel.solvedPliesFor(gameId), requestedPly)
                }
                PracticeScreen(
                    puzzles = puzzles,
                    initialIndex = initialIndex,
                    solvedPlies = solvedPlies,
                    // The first sentence of the walkthrough says why the best move works.
                    explanationFor = { ply -> viewModel.simulationFor(gameId, ply)?.perPlyExplanation?.firstOrNull() },
                    onSolved = { ply -> viewModel.markSolved(gameId, ply) },
                    onShowMissed = { ply -> navController.navigate(Destination.Simulation.createRoute(gameId, ply)) },
                    onDone = { navController.popBackStack() },
                )
            } else {
                // No puzzles (side changed to "Not me", or the game is not in memory): back out.
                LaunchedEffect(gameId) { navController.popBackStack() }
            }
        }

        composable(
            route = Destination.Reference.route,
            arguments = listOf(navArgument(Destination.ARG_TACTIC_TYPE) { type = NavType.StringType }),
        ) { backStackEntry ->
            val typeName = backStackEntry.arguments?.getString(Destination.ARG_TACTIC_TYPE).orEmpty()
            val type = runCatching { net.palaya.chessanalyzer.core.analysis.TacticType.valueOf(typeName) }.getOrNull()
            val simulation = type?.let { viewModel.referenceSimulationFor(it) }
            if (simulation != null) {
                TacticSimulationScreen(
                    simulation = simulation,
                    title = stringResource(R.string.reference_title, tacticTypeName(simulation.tactic.type)),
                    introText = simulation.tactic.description,
                    // No mistake in a textbook example, so no "Before the mistake".
                    startCaption = stringResource(R.string.simulation_starting_position),
                    onDone = { navController.popBackStack() },
                )
            } else {
                LaunchedEffect(Unit) { navController.popBackStack() }
            }
        }

        composable(
            route = Destination.Video.route,
            arguments = listOf(navArgument(Destination.ARG_GAME_ID) { type = NavType.StringType }),
        ) { backStackEntry ->
            val gameId = backStackEntry.arguments?.getString(Destination.ARG_GAME_ID).orEmpty()
            // Building the script reads the persisted narration significance threshold, so it
            // suspends. Three states, not two: "still loading" must not be mistaken for "no
            // analysis in memory", which pops the back stack.
            var scriptState by remember(gameId) { mutableStateOf<VideoScriptState>(VideoScriptState.Loading) }
            LaunchedEffect(gameId) {
                // TODO(C2 after V4): rephrasedVideoScriptFor(gameId) here, see the hook in AnalysisViewModel.
                scriptState = VideoScriptState.Ready(viewModel.videoScriptFor(gameId))
            }
            val script = (scriptState as? VideoScriptState.Ready)?.script
            if (scriptState is VideoScriptState.Loading) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else if (script != null) {
                // Rebuilt when the voice arrives (D2c: setup may finish while this screen is open).
                val voiceInstalled = setupDisk.voiceInstalled
                val narrationProvider = remember(narrationVoiceSettings, voiceInstalled) { viewModel.buildNarrationProvider() }
                VideoScreen(
                    script = script,
                    onBack = { navController.popBackStack() },
                    narrationProvider = narrationProvider,
                    onFinishSetup = if (narrationVoiceSettings.provider == NarrationProviderChoice.NEURAL && !voiceInstalled) {
                        { goToSetup() }
                    } else {
                        null
                    },
                )
            } else {
                // Analysis for this game isn't in memory (e.g. after process death) — nothing to
                // narrate without re-running analysis, so just back out.
                LaunchedEffect(gameId) { navController.popBackStack() }
            }
        }

        composable(
            route = Destination.Simulation.route,
            arguments = listOf(
                navArgument(Destination.ARG_GAME_ID) { type = NavType.StringType },
                navArgument(Destination.ARG_PLY) { type = NavType.IntType },
            ),
        ) { backStackEntry ->
            val gameId = backStackEntry.arguments?.getString(Destination.ARG_GAME_ID).orEmpty()
            val ply = backStackEntry.arguments?.getInt(Destination.ARG_PLY) ?: 0
            val simulation = viewModel.simulationFor(gameId, ply)
            if (simulation != null) {
                val type = simulation.tactic.type
                val hasReference = net.palaya.chessanalyzer.core.analysis.TacticReferenceLibrary.hasReference(type)
                TacticSimulationScreen(
                    simulation = simulation,
                    onDone = { navController.popBackStack() },
                    // Offered at the end of the walkthrough, not as a detour from it: first the
                    // user's own miss, then "want to see this pattern done cleanly?".
                    onSeeReference = if (hasReference) {
                        { navController.navigate(Destination.Reference.createRoute(type)) }
                    } else null,
                    // "Try it yourself" on the last step, only when this move is a practice position.
                    // Reached from Practise itself, it goes back to that puzzle (same state) instead of
                    // stacking a second Practise on top.
                    onTryIt = if (canTryIt(ply, practicePlies(viewModel.practiceSetFor(gameId)))) {
                        {
                            val cameFromPractice =
                                navController.previousBackStackEntry?.destination?.route == Destination.Practice.route
                            if (cameFromPractice) {
                                navController.popBackStack()
                            } else {
                                navController.navigate(Destination.Practice.createRoute(gameId, ply))
                            }
                        }
                    } else null,
                )
            } else {
                LaunchedEffect(Unit) { navController.popBackStack() }
            }
        }

        composable(Destination.Settings.route) {
            // D2e: "Check for updates". The view model is this screen's; the work is the application's.
            val updatesViewModel: UpdatesViewModel = viewModel()
            val updateState by updatesViewModel.state.collectAsState()
            val lastUpdateCheckMs by updatesViewModel.lastCheckedMs.collectAsState()
            val updateBlock by updatesViewModel.block.collectAsState()
            val checkBlock by updatesViewModel.checkBlock.collectAsState()
            val updatePrecheck by updatesViewModel.precheckError.collectAsState()
            val meteredOffer by updatesViewModel.meteredOffer.collectAsState()
            val rolledBackNotice by updatesViewModel.rollbackNotice.collectAsState()
            var updateSheetOpen by rememberSaveable { mutableStateOf(false) }
            val narrationStorageBytes by viewModel.narrationStorageBytes.collectAsState()
            val voiceSampleState by viewModel.voiceSamples.state.collectAsState()
            // Leaving Settings stops a sample still playing and frees the voice engine it loaded.
            DisposableEffect(Unit) { onDispose { viewModel.releaseVoiceSamples() } }
            // Cheap file-size scan; re-run every time this screen is (re)entered so the number
            // shown is never stale after a "Prepare narration"/export elsewhere populated the cache.
            LaunchedEffect(Unit) {
                viewModel.refreshNarrationStorageBytes()
            }
            SettingsScreen(
                settings = settings,
                onBack = { navController.popBackStack() },
                onSettingsChange = { viewModel.updateSettings(it) },
                onOpenAbout = { navController.navigate(Destination.About.route) },
                narrationVoiceSettings = narrationVoiceSettings,
                onNarrationProviderChange = { viewModel.setNarrationProvider(it) },
                narrationStorageBytes = narrationStorageBytes,
                onClearNarrationStorage = { viewModel.clearNarrationStorage() },
                onShareDiagnosticLog = { shareDiagnosticLog() },
                updateRowLine = updateRowLine(updateState, checkBlock),
                lastUpdateCheckMs = lastUpdateCheckMs,
                updateBlock = checkBlock,
                updateRolledBackNotice = rolledBackNotice,
                onCheckForUpdates = {
                    updatesViewModel.dismissRollbackNotice()
                    updatesViewModel.onRowTapped()
                    updateSheetOpen = true
                },
                // V1: the narrator voice picker and its samples.
                voiceInstalled = setupDisk.voiceInstalled,
                onNarratorSpeakerChange = { viewModel.setNarratorSpeaker(it) },
                voiceSampleState = voiceSampleState,
                onPlayVoiceSample = { viewModel.playVoiceSample(it) },
                onStopVoiceSample = { viewModel.stopVoiceSample() },
                onVoicePickerClosed = { viewModel.releaseVoiceSamples() },
                // C2: Natural wording (on-device AI).
                commentarySection = { RephraseSettingsSection() },
            )
            if (updateSheetOpen) {
                UpdateSheet(
                    view = updateSheetView(updateState, updateBlock),
                    block = updateBlock,
                    precheckError = updatePrecheck,
                    meteredOffer = meteredOffer,
                    onInstall = { updatesViewModel.requestInstall(it) },
                    onConfirmMetered = { updatesViewModel.confirmMetered() },
                    onDismissMetered = { updatesViewModel.dismissMetered() },
                    onCancel = { updatesViewModel.cancelInstall() },
                    onCheckAgain = { updatesViewModel.checkAgain() },
                    onDismiss = { updateSheetOpen = false },
                )
            }
        }

        composable(Destination.About.route) {
            AboutScreen(onBack = { navController.popBackStack() })
        }
    }
}

/**
 * Three-state result of building the narrated script: "not built yet" has to be distinguishable
 * from "there is nothing to narrate", because the latter pops the back stack and doing that while
 * the script is still being built would make the Watch Review button look broken.
 */
private sealed interface VideoScriptState {
    data object Loading : VideoScriptState
    data class Ready(val script: VideoScript?) : VideoScriptState
}
