package net.palaya.chessanalyzer.ui.navigation

import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import net.palaya.chessanalyzer.ui.theme.tacticTypeName
import net.palaya.chessanalyzer.R
import androidx.compose.ui.res.stringResource
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import net.palaya.chessanalyzer.ui.screens.AboutScreen
import net.palaya.chessanalyzer.ui.screens.CloudVoiceSetupScreen
import net.palaya.chessanalyzer.ui.screens.AnalysisProgressScreen
import net.palaya.chessanalyzer.ui.screens.GameReportScreen
import net.palaya.chessanalyzer.ui.screens.ImportScreen
import net.palaya.chessanalyzer.ui.screens.ReviewScreen
import net.palaya.chessanalyzer.ui.screens.SettingsScreen
import net.palaya.chessanalyzer.ui.screens.TacticSimulationScreen
import net.palaya.chessanalyzer.ui.screens.VideoScreen
import net.palaya.chessanalyzer.core.narration.VideoScript
import net.palaya.chessanalyzer.ui.viewmodel.AnalysisViewModel
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
) {
    val context = LocalContext.current
    val viewModel: AnalysisViewModel = viewModel()
    val settings by viewModel.settings.collectAsState()
    val narrationVoiceSettings by viewModel.narrationVoiceSettings.collectAsState()
    val recentGames by viewModel.recentGames.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()

    fun startAnalysis(pgnText: String) {
        val id = viewModel.newGameId()
        viewModel.registerPendingImport(id, pgnText)
        navController.navigate(Destination.AnalysisProgress.createRoute(id))
    }

    LaunchedEffect(pendingImportPgn) {
        val pgn = pendingImportPgn
        if (!pgn.isNullOrBlank()) {
            startAnalysis(pgn)
            onPendingImportConsumed()
        }
    }

    NavHost(
        navController = navController,
        startDestination = Destination.Import.route,
        modifier = modifier,
    ) {
        composable(Destination.Import.route) {
            ImportScreen(
                onFilePicked = { uri: Uri ->
                    val text = readTextFromUri(context.contentResolver, uri)
                    if (text.isNullOrBlank()) {
                        Toast.makeText(context, context.getString(net.palaya.chessanalyzer.R.string.import_invalid_pgn), Toast.LENGTH_LONG).show()
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
            )
        }

        composable(
            route = Destination.AnalysisProgress.route,
            arguments = listOf(navArgument(Destination.ARG_GAME_ID) { type = NavType.StringType }),
        ) { backStackEntry ->
            val gameId = backStackEntry.arguments?.getString(Destination.ARG_GAME_ID).orEmpty()
            val progress by viewModel.progress.collectAsState()

            LaunchedEffect(gameId) {
                viewModel.runAnalysis(gameId) {
                    navController.navigate(Destination.Review.createRoute(gameId)) {
                        popUpTo(Destination.AnalysisProgress.route) { inclusive = true }
                    }
                }
            }

            AnalysisProgressScreen(
                progress = progress,
                onCancel = {
                    viewModel.cancelAnalysis()
                    navController.popBackStack()
                },
            )

            if (errorMessage != null) {
                AlertDialog(
                    onDismissRequest = { viewModel.clearError(); navController.popBackStack() },
                    title = { Text(stringResource(R.string.dialog_analysis_failed_title)) },
                    text = { Text(errorMessage ?: "") },
                    confirmButton = {
                        TextButton(onClick = { viewModel.clearError(); navController.popBackStack() }) {
                            Text(stringResource(R.string.dialog_ok))
                        }
                    },
                )
            }
        }

        composable(
            route = Destination.Review.route,
            arguments = listOf(
                navArgument(Destination.ARG_GAME_ID) { type = NavType.StringType },
                // Optional: -1 means "open at the start", any other value opens at that ply.
                navArgument(Destination.ARG_PLY) { type = NavType.IntType; defaultValue = -1 },
            ),
        ) { backStackEntry ->
            val gameId = backStackEntry.arguments?.getString(Destination.ARG_GAME_ID).orEmpty()
            val requestedPly = backStackEntry.arguments?.getInt(Destination.ARG_PLY) ?: -1
            val game = viewModel.games[gameId]
            if (game != null) {
                ReviewScreen(
                    game = game,
                    initialPly = requestedPly.takeIf { it >= 0 },
                    onShowMeClick = { move ->
                        if (move.core?.simulation != null) {
                            navController.navigate(Destination.Simulation.createRoute(gameId, move.ply))
                        }
                    },
                    onLearnPattern = { type -> navController.navigate(Destination.Reference.createRoute(type)) },
                    onViewReportClick = { navController.navigate(Destination.GameReport.createRoute(gameId)) },
                    onWatchReviewClick = { navController.navigate(Destination.Video.createRoute(gameId)) },
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
            val report = remember(gameId, thresholdCp) { viewModel.uiReportFor(gameId, thresholdCp) }
            if (report != null) {
                GameReportScreen(
                    report = report,
                    onKeyMomentClick = { ply ->
                        navController.navigate(Destination.Review.createRoute(gameId, ply))
                    },
                    onTacticClick = { ply ->
                        navController.navigate(Destination.Review.createRoute(gameId, ply))
                    },
                    onLearnPattern = { type -> navController.navigate(Destination.Reference.createRoute(type)) },
                    onWatchReviewClick = { navController.navigate(Destination.Video.createRoute(gameId)) },
                )
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
                    title = stringResource(R.string.review_learn_pattern, tacticTypeName(simulation.tactic.type)),
                    introText = simulation.tactic.description,
                    doneLabel = stringResource(R.string.common_back),
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
                scriptState = VideoScriptState.Ready(viewModel.videoScriptFor(gameId))
            }
            val script = (scriptState as? VideoScriptState.Ready)?.script
            if (scriptState is VideoScriptState.Loading) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else if (script != null) {
                // First time narration is actually wanted, pull down the default neural voice so
                // the good voice is what users get without hunting through Settings. No-ops if the
                // user already chose a provider or the model is present; narration falls back to
                // the device voice while it is still downloading.
                LaunchedEffect(Unit) { viewModel.ensureDefaultNeuralVoice() }
                val narrationProvider = remember(narrationVoiceSettings) { viewModel.buildNarrationProvider() }
                VideoScreen(
                    script = script,
                    onBack = { navController.popBackStack() },
                    narrationProvider = narrationProvider,
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
                )
            } else {
                LaunchedEffect(Unit) { navController.popBackStack() }
            }
        }

        composable(Destination.Settings.route) {
            var updateMessage by remember { mutableStateOf<String?>(null) }
            val narrationStorageBytes by viewModel.narrationStorageBytes.collectAsState()
            val neuralModelState by viewModel.neuralModelState.collectAsState()
            // Cheap file-size scan; re-run every time this screen is (re)entered so the number
            // shown is never stale after a "Prepare narration"/export elsewhere populated the cache.
            LaunchedEffect(Unit) {
                viewModel.refreshNarrationStorageBytes()
                viewModel.refreshNeuralModelState()
            }
            SettingsScreen(
                settings = settings,
                onSettingsChange = { viewModel.updateSettings(it) },
                onCheckForUpdates = {
                    viewModel.checkForUpdates { info ->
                        updateMessage = if (info != null) {
                            context.getString(R.string.engine_update_available, info.latestVersion)
                        } else {
                            context.getString(R.string.engine_update_latest)
                        }
                    }
                },
                onViewGplNotice = { updateMessage = context.getString(R.string.gpl_notice) },
                onOpenAbout = { navController.navigate(Destination.About.route) },
                narrationVoiceSettings = narrationVoiceSettings,
                onNarrationProviderChange = { viewModel.setNarrationProvider(it) },
                narrationStorageBytes = narrationStorageBytes,
                onClearNarrationStorage = { viewModel.clearNarrationStorage() },
                neuralModelState = neuralModelState,
                onNeuralTierChange = { viewModel.setNeuralTier(it) },
                onDownloadNeuralModel = { viewModel.downloadNeuralModel(it) },
                onCancelNeuralModelDownload = { viewModel.cancelNeuralModelDownload() },
                onDeleteNeuralModel = { viewModel.deleteNeuralModel(it) },
                onCloudVoiceChange = { viewModel.setCloudVoice(it) },
                onOpenCloudSetup = {
                    viewModel.resetCloudKeyCheck()
                    navController.navigate(Destination.CloudVoiceSetup.route)
                },
                onRemoveCloudKey = { viewModel.removeCloudKey() },
            )
            if (updateMessage != null) {
                AlertDialog(
                    onDismissRequest = { updateMessage = null },
                    title = { Text(stringResource(R.string.dialog_engine_title)) },
                    text = { Text(updateMessage ?: "") },
                    confirmButton = { TextButton(onClick = { updateMessage = null }) { Text(stringResource(R.string.dialog_ok)) } },
                )
            }
        }

        composable(Destination.CloudVoiceSetup.route) {
            val checkState by viewModel.cloudKeyCheck.collectAsState()
            CloudVoiceSetupScreen(
                voice = narrationVoiceSettings.cloudVoice,
                checkState = checkState,
                onTestAndSave = { viewModel.testAndSaveCloudKey(it) },
                onBack = { navController.popBackStack() },
            )
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
