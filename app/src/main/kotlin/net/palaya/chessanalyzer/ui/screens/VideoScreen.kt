@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package net.palaya.chessanalyzer.ui.screens

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.view.ViewGroup
import android.content.pm.ActivityInfo
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.palaya.chessanalyzer.R
import net.palaya.chessanalyzer.core.narration.VideoScript
import net.palaya.chessanalyzer.ui.video.BoardSurfaceView
import net.palaya.chessanalyzer.ui.video.PlayerUiState
import net.palaya.chessanalyzer.ui.video.VideoPlayerController
import net.palaya.chessanalyzer.video.DeviceTtsProvider
import net.palaya.chessanalyzer.video.NarrationCoordinator
import net.palaya.chessanalyzer.video.NarrationStore
import net.palaya.chessanalyzer.video.NarrationVoiceProvider
import net.palaya.chessanalyzer.video.VideoExportService
import net.palaya.chessanalyzer.video.VideoExporter

/** Local UI state for the "Prepare narration" action — mirrors [VideoExporter.State]'s shape
 * closely enough to reuse the same progress-dialog pattern, without depending on export's type. */
private sealed interface PrepareNarrationState {
    data object Idle : PrepareNarrationState
    data class Running(val completed: Int, val total: Int) : PrepareNarrationState
    data class Done(val notice: String?) : PrepareNarrationState
    data class Failed(val message: String) : PrepareNarrationState
}

/** Walks the [Context] wrapper chain to find the hosting [Activity] — needed to reach its
 * [android.view.Window] for immersive system bars and keep-screen-on, neither of which Compose
 * exposes directly. Returns null only for a context that truly isn't activity-backed (e.g. some
 * test harnesses), in which case the caller just skips the window-level effect. */
private fun Context.findActivity(): Activity? {
    var ctx = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}

/** How long the full-screen control overlay stays up after appearing before auto-hiding again. */
private const val FULLSCREEN_CONTROLS_AUTO_HIDE_MS = 3_500L

/**
 * Plays a [VideoScript] live in-app (board + TTS + captions, chaptered) and offers exporting the
 * same script to a shareable MP4. The board is drawn by [BoardSurfaceView] — a thin
 * [android.view.View] wrapper around [net.palaya.chessanalyzer.video.BoardFrameRenderer] — so
 * live playback and the exported file are visually identical instead of two implementations.
 */
@Composable
fun VideoScreen(
    script: VideoScript,
    modifier: Modifier = Modifier,
    onBack: () -> Unit = {},
    /** Non-null only when the user has a non-device narration voice selected and installed. */
    narrationProvider: NarrationVoiceProvider? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // Re-created whenever the configured narration provider changes (e.g. the user just switched
    // voice tier in Settings) so live playback picks up pre-generated audio under the new identity
    // rather than staying pinned to whatever was active when this screen first opened.
    val controller = remember(script, narrationProvider) { VideoPlayerController(context, script, scope, narrationProvider) }
    DisposableEffect(controller) { onDispose { controller.release() } }
    val playerState by controller.uiState.collectAsState()

    // Export state is owned by VideoExportService's companion — process-scoped, NOT by this
    // composition. That is what makes an export survive leaving this screen, and what makes
    // re-entering mid-export show the real in-flight progress instead of a fresh idle state.
    val exportState by VideoExportService.state.collectAsState()
    val exportedUri by VideoExportService.exportedUri.collectAsState()

    // POST_NOTIFICATIONS (API 33+) is requested opportunistically and the result is deliberately
    // ignored: the export starts either way. The service runs as a foreground service regardless;
    // a denial only hides its progress notification. Never gate the feature on it.
    var exportRequestedAfterPermission by remember { mutableStateOf(false) }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { _ -> exportRequestedAfterPermission = true }

    val narrationStore = remember { NarrationStore.forApp(context) }
    var prepareState by remember { mutableStateOf<PrepareNarrationState>(PrepareNarrationState.Idle) }
    var prepareJob by remember { mutableStateOf<Job?>(null) }
    // Re-checked whenever the script or provider changes, and again once a prepare pass finishes —
    // this is what lets the button honestly say "already done" instead of re-running for free.
    var alreadyPrepared by remember(script, narrationProvider) { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(script, narrationProvider, prepareState) {
        if (prepareState is PrepareNarrationState.Running) return@LaunchedEffect
        val provider = narrationProvider ?: DeviceTtsProvider(context)
        alreadyPrepared = withContext(Dispatchers.IO) {
            NarrationCoordinator.isFullyPrepared(script, provider, narrationStore)
        }
    }

    fun runPrepareNarration() {
        if (prepareState is PrepareNarrationState.Running) return
        val provider = narrationProvider ?: DeviceTtsProvider(context)
        val fallback = DeviceTtsProvider(context)
        val coordinator = NarrationCoordinator(provider, fallback, narrationStore)
        prepareState = PrepareNarrationState.Running(0, script.segments.size)
        prepareJob = scope.launch {
            val progressJob = launch {
                coordinator.progress.collect { p ->
                    prepareState = PrepareNarrationState.Running(p.completed, p.total)
                }
            }
            try {
                val outcome = coordinator.synthesizeAll(script, File(context.cacheDir, "narration_prepare"))
                prepareState = PrepareNarrationState.Done(outcome.notice)
            } catch (e: Exception) {
                prepareState = PrepareNarrationState.Failed(e.message ?: e.javaClass.simpleName)
            } finally {
                progressJob.cancel()
            }
        }
    }

    var isFullScreen by remember { mutableStateOf(false) }
    var controlsVisible by remember { mutableStateOf(true) }

    fun setFullScreen(value: Boolean) {
        isFullScreen = value
        controlsVisible = true
    }

    // Auto-hide the full-screen control overlay a few seconds after it's shown; re-armed every
    // time it's shown again (a tap), so it always counts down from the moment it last appeared.
    LaunchedEffect(isFullScreen, controlsVisible) {
        if (isFullScreen && controlsVisible) {
            delay(FULLSCREEN_CONTROLS_AUTO_HIDE_MS)
            controlsVisible = false
        }
    }

    // Back press exits full screen first; a second press (full screen already off) falls through
    // to this screen's normal back navigation.
    BackHandler(enabled = isFullScreen) { setFullScreen(false) }

    // Immersive system bars while full screen. The `onDispose` unconditionally re-shows them —
    // on exiting full screen AND on leaving this screen entirely — so the immersive state can
    // never leak onto whatever screen comes after this one.
    val view = LocalView.current
    DisposableEffect(isFullScreen) {
        val window = context.findActivity()?.window
        if (window != null) {
            val insetsController = WindowCompat.getInsetsController(window, view)
            if (isFullScreen) {
                insetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                insetsController.hide(WindowInsetsCompat.Type.systemBars())
            } else {
                insetsController.show(WindowInsetsCompat.Type.systemBars())
            }
        }
        onDispose {
            val disposedWindow = context.findActivity()?.window
            if (disposedWindow != null) {
                WindowCompat.getInsetsController(disposedWindow, view).show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }

    // Full screen must also go LANDSCAPE, which is the whole point of the mode.
    //
    // The review frame is 16:9. Hiding the system bars on a portrait phone therefore just
    // letterboxes that 16:9 band across the screen's width and leaves roughly three quarters of
    // the display black — the board ends up no bigger than it was in the normal layout, which is
    // the opposite of what "full screen" is for. Rotating to landscape lets the 16:9 frame fill
    // the display, so the board actually gets large. This is what video players do.
    //
    // `onDispose` restores the user's own orientation preference, both on exiting full screen and
    // on leaving the screen, so this never leaks.
    DisposableEffect(isFullScreen) {
        val activity = context.findActivity()
        val previousOrientation = activity?.requestedOrientation
        if (activity != null) {
            activity.requestedOrientation = if (isFullScreen) {
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            } else {
                ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }
        }
        onDispose {
            context.findActivity()?.requestedOrientation =
                previousOrientation ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    // A narrated review runs minutes long — the display must not sleep mid-playback.
    DisposableEffect(Unit) {
        val window = context.findActivity()?.window
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }

    fun startExport() {
        controller.pause()
        // Hands the whole job — render, MediaStore publish, progress/terminal notifications — to
        // the foreground service. Nothing about it is tied to this composable's lifetime any more.
        //
        // Pass the SELECTED provider. This used to be hardcoded null, which meant VideoExporter
        // took NarrationSynthesizer's device-TTS path and every exported video was narrated by the
        // robotic device voice — even with the neural voice chosen, downloaded and already
        // pre-generated. The bug hid because the *in-app* player narrates through a different path
        // (NarrationCoordinator + NarrationStore) and did use the neural voice, so playback sounded
        // right and only the exported MP4 was wrong. It also looked like cache reuse: export
        // "re-synthesized" 32 segments in seconds, which is device TTS being fast, not the store
        // being hit.
        //
        // With a non-null provider VideoExporter goes through NarrationCoordinator + NarrationStore
        // instead, so the export reuses the audio "Prepare narration" already produced and keeps the
        // mandatory per-segment fallback to the device voice.
        VideoExportService.start(context, script, narrationProvider)
    }

    // Ask for notifications once, then export whatever the answer is (the callback fires on both
    // grant and denial, and immediately if the system decides not to show a dialog at all).
    LaunchedEffect(exportRequestedAfterPermission) {
        if (exportRequestedAfterPermission) {
            exportRequestedAfterPermission = false
            startExport()
        }
    }

    fun exportTapped() {
        val needsNotificationPermission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        if (needsNotificationPermission) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            startExport()
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            // No app bar at all in full screen — it exists to hide, not to shrink to a sliver.
            if (!isFullScreen) {
                TopAppBar(
                    title = { Text(script.title, maxLines = 1) },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                    actions = {
                        IconButton(onClick = { setFullScreen(true) }) {
                            Icon(Icons.Filled.Fullscreen, contentDescription = stringResource(R.string.video_enter_fullscreen))
                        }
                        // Synthesize the whole review's narration once, visibly, rather than
                        // letting it trickle out during playback/export. A no-op once cached.
                        TextButton(
                            onClick = { runPrepareNarration() },
                            enabled = prepareState !is PrepareNarrationState.Running && alreadyPrepared != true,
                        ) {
                            Text(
                                if (alreadyPrepared == true) {
                                    stringResource(R.string.video_prepare_narration_ready)
                                } else {
                                    stringResource(R.string.video_prepare_narration_action)
                                },
                            )
                        }
                        TextButton(onClick = { exportTapped() }) {
                            Text(stringResource(R.string.video_export_action))
                        }
                    },
                )
            }
        },
    ) { innerPadding ->
        if (isFullScreen) {
            FullScreenVideoContent(
                script = script,
                playerState = playerState,
                controller = controller,
                controlsVisible = controlsVisible,
                onSingleTap = { controlsVisible = !controlsVisible },
                onExitFullScreen = { setFullScreen(false) },
            )
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            ) {
                VideoBoardSurface(
                    script = script,
                    playerState = playerState,
                    fillAvailableSpace = false,
                    onDoubleTap = { setFullScreen(true) },
                    modifier = Modifier.fillMaxWidth(),
                )

                if (!playerState.narrationAvailable) {
                    Text(
                        text = stringResource(R.string.video_narration_unavailable),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }

                // Scrub bar.
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                ) {
                    ScrubBar(
                        playerState = playerState,
                        onSeek = { controller.seekToMs(it) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                TransportRow(
                    playerState = playerState,
                    controller = controller,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                )

                // Speed control.
                //
                // A plain Row could not fit the label plus five chips on a phone: the last one
                // ("2.0x") had no horizontal room left and wrapped its text one character per line,
                // rendering as a tall "2 . 0 x" column. A LazyRow scrolls instead of wrapping, and
                // `maxLines = 1` on the label makes a squeeze impossible rather than ugly.
                Text(
                    stringResource(R.string.video_speed),
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(start = 16.dp, top = 4.dp),
                )
                LazyRow(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(listOf(0.75f, 1f, 1.25f, 1.5f, 2f)) { speed ->
                        FilterChip(
                            selected = playerState.speed == speed,
                            onClick = { controller.setSpeed(speed) },
                            label = { Text("${speed}x", maxLines = 1) },
                        )
                    }
                }

                // Chapters — tap to jump.
                if (script.chapters.isNotEmpty()) {
                    Text(
                        stringResource(R.string.video_chapters),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(script.chapters) { chapter ->
                            val isCurrent = script.chapters.indexOf(chapter).let { idx ->
                                val nextStart = script.chapters.getOrNull(idx + 1)?.startSegmentIndex ?: Int.MAX_VALUE
                                playerState.segmentIndex in chapter.startSegmentIndex until nextStart
                            }
                            FilterChip(
                                selected = isCurrent,
                                onClick = { controller.seekToChapterIndex(script.chapters.indexOf(chapter)) },
                                label = { Text(chapter.title) },
                            )
                        }
                    }
                }
            }
        }
    }

    // Driven purely off the service's state: Idle means "no export to report", anything else
    // means there is one — including an export started before this composition existed.
    if (exportState !is VideoExporter.State.Idle) {
        ExportProgressDialog(
            state = exportState,
            onDismiss = { VideoExportService.acknowledgeTerminalState() },
            onCancel = { VideoExportService.requestCancel() },
            onShare = { uri -> shareVideo(context, uri) },
            onOpen = { uri -> openVideo(context, uri) },
            exportedUri = exportedUri,
        )
    }

    val currentPrepareState = prepareState
    if (currentPrepareState !is PrepareNarrationState.Idle) {
        PrepareNarrationDialog(
            state = currentPrepareState,
            onDismiss = { prepareState = PrepareNarrationState.Idle },
        )
    }
}

/**
 * Progress for the explicit "prepare narration" action — the same shape of dialog as
 * [ExportProgressDialog]'s [net.palaya.chessanalyzer.video.VideoExporter.State.SynthesizingNarration]
 * case, kept as its own small composable because prepare has no rendering/finalizing phase of its
 * own: it is done the moment every segment's audio is cached.
 */
@Composable
private fun PrepareNarrationDialog(state: PrepareNarrationState, onDismiss: () -> Unit) {
    val isTerminal = state is PrepareNarrationState.Done || state is PrepareNarrationState.Failed
    AlertDialog(
        onDismissRequest = { if (isTerminal) onDismiss() },
        title = {
            Text(
                when (state) {
                    is PrepareNarrationState.Done -> stringResource(R.string.video_prepare_narration_done_title)
                    is PrepareNarrationState.Failed -> stringResource(R.string.video_export_failed_title)
                    else -> stringResource(R.string.video_prepare_narration_action)
                },
            )
        },
        text = {
            Column {
                when (state) {
                    is PrepareNarrationState.Running -> {
                        Text(stringResource(R.string.video_export_synthesizing, state.completed, state.total.coerceAtLeast(1)))
                        Spacer(Modifier.height(8.dp))
                        LinearProgressIndicator(
                            progress = { if (state.total > 0) state.completed.toFloat() / state.total else 0f },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    is PrepareNarrationState.Done -> {
                        Text(stringResource(R.string.video_prepare_narration_done_body))
                        state.notice?.let { notice ->
                            Spacer(Modifier.height(4.dp))
                            Text(notice, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                        }
                    }
                    is PrepareNarrationState.Failed -> Text(state.message)
                    is PrepareNarrationState.Idle -> Unit
                }
            }
        },
        confirmButton = {
            if (isTerminal) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.video_export_close)) }
            }
        },
    )
}

/**
 * The board surface shared by normal and full-screen playback. In normal mode it's pinned to
 * [fillMaxWidth] at the top of the screen, same as before. In full screen it is given no size
 * modifier of its own beyond [aspectRatio] — the surrounding [Box] (fillMaxSize + centered) then
 * fits it to whichever dimension is limiting, which is exactly "landscape gives it the height,
 * portrait gives it the width" without any orientation-specific logic here.
 */
@Composable
private fun VideoBoardSurface(
    script: VideoScript,
    playerState: PlayerUiState,
    fillAvailableSpace: Boolean,
    modifier: Modifier = Modifier,
    onSingleTap: () -> Unit = {},
    onDoubleTap: () -> Unit = {},
) {
    // pointerInput(Unit) never restarts its gesture-detection coroutine across recompositions, so
    // it would otherwise close over the *first* composition's lambdas — rememberUpdatedState keeps
    // it reading the latest ones without needing to restart (and re-arm) tap detection.
    val currentOnSingleTap by rememberUpdatedState(onSingleTap)
    val currentOnDoubleTap by rememberUpdatedState(onDoubleTap)

    AndroidView(
        modifier = modifier
            .then(if (fillAvailableSpace) Modifier else Modifier.fillMaxWidth())
            .aspectRatio(VideoExporter.VIDEO_WIDTH.toFloat() / VideoExporter.VIDEO_HEIGHT.toFloat())
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { currentOnSingleTap() },
                    onDoubleTap = { currentOnDoubleTap() },
                )
            },
        factory = { ctx ->
            BoardSurfaceView(ctx).apply {
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                fallbackTitle = script.title
                fallbackSubtitle = script.subtitle
            }
        },
        update = { view -> view.instruction = playerState.instruction },
    )
}

@Composable
private fun ScrubBar(playerState: PlayerUiState, onSeek: (Long) -> Unit, modifier: Modifier = Modifier) {
    Slider(
        value = playerState.positionMs.toFloat(),
        valueRange = 0f..playerState.totalDurationMs.toFloat().coerceAtLeast(1f),
        onValueChange = { onSeek(it.toLong()) },
        modifier = modifier,
    )
}

@Composable
private fun TransportRow(playerState: PlayerUiState, controller: VideoPlayerController, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.Center,
    ) {
        IconButton(onClick = { controller.skipPrevious() }) {
            Icon(Icons.Filled.SkipPrevious, contentDescription = stringResource(R.string.video_previous_segment))
        }
        IconButton(onClick = { controller.togglePlayPause() }) {
            Icon(
                if (playerState.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                contentDescription = stringResource(if (playerState.isPlaying) R.string.video_pause else R.string.video_play),
            )
        }
        IconButton(onClick = { controller.skipNext() }) {
            Icon(Icons.Filled.SkipNext, contentDescription = stringResource(R.string.video_next_segment))
        }
    }
}

/**
 * Full-screen playback: the board fills whichever dimension is limiting, the top app bar and side
 * chrome are gone entirely (not just shrunk), and the transport controls become a bottom overlay
 * that auto-hides (see the `LaunchedEffect` in [VideoScreen]) and reappears on a single tap. The
 * caption itself needs no separate handling here — [net.palaya.chessanalyzer.video.BoardFrameRenderer]
 * burns it into the board frame itself, so it's already visible for as long as the board is.
 */
@Composable
private fun FullScreenVideoContent(
    script: VideoScript,
    playerState: PlayerUiState,
    controller: VideoPlayerController,
    controlsVisible: Boolean,
    onSingleTap: () -> Unit,
    onExitFullScreen: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        VideoBoardSurface(
            script = script,
            playerState = playerState,
            fillAvailableSpace = true,
            onSingleTap = onSingleTap,
            onDoubleTap = onExitFullScreen,
        )

        AnimatedVisibility(
            visible = controlsVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopEnd),
        ) {
            IconButton(onClick = onExitFullScreen, modifier = Modifier.padding(8.dp)) {
                Icon(
                    Icons.Filled.FullscreenExit,
                    contentDescription = stringResource(R.string.video_exit_fullscreen),
                    tint = Color.White,
                )
            }
        }

        AnimatedVisibility(
            visible = controlsVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xCC000000))
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                if (!playerState.narrationAvailable) {
                    Text(
                        text = stringResource(R.string.video_narration_unavailable),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                ScrubBar(
                    playerState = playerState,
                    onSeek = { controller.seekToMs(it) },
                    modifier = Modifier.fillMaxWidth(),
                )
                TransportRow(
                    playerState = playerState,
                    controller = controller,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun ExportProgressDialog(
    state: VideoExporter.State,
    exportedUri: Uri?,
    onDismiss: () -> Unit,
    onCancel: () -> Unit,
    onShare: (Uri) -> Unit,
    onOpen: (Uri) -> Unit,
) {
    val isTerminal = state is VideoExporter.State.Completed ||
        state is VideoExporter.State.Failed ||
        state is VideoExporter.State.Cancelled

    AlertDialog(
        onDismissRequest = { if (isTerminal) onDismiss() },
        title = {
            Text(
                when (state) {
                    is VideoExporter.State.Completed -> stringResource(R.string.video_export_completed_title)
                    is VideoExporter.State.Failed -> stringResource(R.string.video_export_failed_title)
                    is VideoExporter.State.Cancelled -> stringResource(R.string.video_export_cancelled_title)
                    else -> stringResource(R.string.video_export_action)
                }
            )
        },
        text = {
            Column {
                when (state) {
                    is VideoExporter.State.SynthesizingNarration -> {
                        Text(stringResource(R.string.video_export_synthesizing, state.completed, state.total.coerceAtLeast(1)))
                        Spacer(Modifier.height(8.dp))
                        LinearProgressIndicator(
                            progress = { if (state.total > 0) state.completed.toFloat() / state.total else 0f },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    is VideoExporter.State.Rendering -> {
                        Text(stringResource(R.string.video_export_rendering))
                        Spacer(Modifier.height(8.dp))
                        LinearProgressIndicator(
                            progress = { if (state.totalMs > 0) (state.elapsedMs.toFloat() / state.totalMs).coerceIn(0f, 1f) else 0f },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    is VideoExporter.State.Finalizing -> {
                        Text(stringResource(R.string.video_export_finalizing))
                        Spacer(Modifier.height(8.dp))
                        CircularProgressIndicator()
                    }
                    is VideoExporter.State.Completed -> {
                        val seconds = state.durationMs / 1000.0
                        val mb = state.fileSizeBytes / (1024.0 * 1024.0)
                        Text("%.1fs · %.1f MB".format(seconds, mb))
                        if (!state.narrationWasSpoken) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                stringResource(R.string.video_narration_unavailable),
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                        // Single non-blocking notice when a paid provider fell back to the
                        // device voice for some or all segments — never a silent surprise.
                        state.narrationNotice?.let { notice ->
                            Spacer(Modifier.height(4.dp))
                            Text(
                                notice,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                    is VideoExporter.State.Failed -> Text(state.message)
                    is VideoExporter.State.Cancelled -> Text(stringResource(R.string.video_export_cancelled_title))
                    else -> Text(stringResource(R.string.video_export_action))
                }
            }
        },
        confirmButton = {
            if (state is VideoExporter.State.Completed && exportedUri != null) {
                TextButton(onClick = { onOpen(exportedUri) }) { Text(stringResource(R.string.video_export_open)) }
            } else if (isTerminal) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.video_export_close)) }
            } else {
                TextButton(onClick = onCancel) { Text(stringResource(R.string.video_export_cancel)) }
            }
        },
        dismissButton = {
            if (state is VideoExporter.State.Completed && exportedUri != null) {
                TextButton(onClick = { onShare(exportedUri) }) { Text(stringResource(R.string.video_export_share)) }
            }
        },
    )
}

private fun shareVideo(context: android.content.Context, uri: Uri) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "video/mp4"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, context.getString(R.string.video_export_share)))
}

private fun openVideo(context: android.content.Context, uri: Uri) {
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, "video/mp4")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    try {
        context.startActivity(intent)
    } catch (e: Exception) {
        shareVideo(context, uri)
    }
}
