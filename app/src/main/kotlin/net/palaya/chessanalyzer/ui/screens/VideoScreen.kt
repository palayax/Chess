@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package net.palaya.chessanalyzer.ui.screens

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.text.format.Formatter
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Surface
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
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlinx.coroutines.delay
import net.palaya.chessanalyzer.R
import net.palaya.chessanalyzer.ui.a11y.AppBarTitle
import net.palaya.chessanalyzer.ui.a11y.rememberAnnouncer
import net.palaya.chessanalyzer.video.ExportTimeLeft
import net.palaya.chessanalyzer.video.MediaStorePublisher
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import net.palaya.chessanalyzer.ui.a11y.isLandscape
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.text.style.TextDirection
import net.palaya.chessanalyzer.core.narration.VideoScript
import net.palaya.chessanalyzer.ui.model.nextPlaybackSpeed
import net.palaya.chessanalyzer.ui.model.playbackSpeedNumber
import net.palaya.chessanalyzer.ui.video.BoardSurfaceView
import net.palaya.chessanalyzer.ui.video.PlayerUiState
import net.palaya.chessanalyzer.ui.video.VideoPlayerController
import net.palaya.chessanalyzer.video.NarrationVoiceProvider
import net.palaya.chessanalyzer.video.VideoExportService
import net.palaya.chessanalyzer.video.VideoExporter

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
    /**
     * D2c: non-null while the neural voice is chosen but not installed yet (setup unfinished). The phone's
     * voice narrates meanwhile (the existing fallback); a one-line notice says so, with "Finish setup".
     */
    onFinishSetup: (() -> Unit)? = null,
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
    val timeLeft by VideoExportService.timeLeft.collectAsState()

    // POST_NOTIFICATIONS (API 33+) is requested opportunistically and the result is deliberately
    // ignored: the export starts either way. The service runs as a foreground service regardless;
    // a denial only hides its progress notification. Never gate the feature on it.
    var exportRequestedAfterPermission by remember { mutableStateOf(false) }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { _ -> exportRequestedAfterPermission = true }

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
        // instead, so the export reuses any audio the player already cached and keeps the mandatory
        // per-segment fallback to the device voice. "Save video" is the only place narration is
        // prepared on purpose; its progress ("Preparing narration... (n/N)") is the export dialog's.
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
                // Back arrow, the title, and the fullscreen icon: nothing else lives in the bar. The one
                // primary action ("Save video") is the bottom button.
                TopAppBar(
                    title = { AppBarTitle(stringResource(R.string.video_title)) },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                    actions = {
                        IconButton(onClick = { setFullScreen(true) }) {
                            Icon(Icons.Filled.Fullscreen, contentDescription = stringResource(R.string.video_enter_fullscreen))
                        }
                    },
                )
            }
        },
        bottomBar = {
            // One full-width primary button, pinned under the player. Hidden in full screen, like the bar.
            if (!isFullScreen) {
                // Lifted above the navigation bar (edge to edge: the bottom bar owns its insets).
                Surface(color = MaterialTheme.colorScheme.background) {
                    Button(
                        onClick = { exportTapped() },
                        modifier = Modifier
                            .navigationBarsPadding()
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp)
                            .heightIn(min = 52.dp),
                    ) {
                        Text(stringResource(R.string.video_export_action))
                    }
                }
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
            val surface: @Composable () -> Unit = {
                VideoBoardSurface(
                    script = script,
                    playerState = playerState,
                    fillAvailableSpace = false,
                    onDoubleTap = { setFullScreen(true) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            // Everything under (or, in landscape, beside) the picture.
            val controls: @Composable () -> Unit = {
                if (onFinishSetup != null) {
                    VoiceNotInstalledNotice(onFinishSetup)
                }
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

                // Chapters: tap to jump. Chips alone, no label.
                if (script.chapters.isNotEmpty()) {
                    LazyRow(
                        modifier = Modifier.padding(vertical = 4.dp),
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
            if (isLandscape()) {
                // Picture on the left, as large as the height allows (16:9), controls on the right.
                // Before, the picture took the whole width and pushed every control off the screen.
                BoxWithConstraints(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
                    val pictureWidth = minOf(maxWidth * 0.58f, maxHeight * (16f / 9f))
                    Row(modifier = Modifier.fillMaxSize()) {
                        Box(modifier = Modifier.width(pictureWidth).align(Alignment.CenterVertically)) { surface() }
                        Column(
                            modifier = Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.Center,
                        ) { controls() }
                    }
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                        // Scrolls rather than clips at a large font scale or on a short window.
                        .verticalScroll(rememberScrollState()),
                ) {
                    surface()
                    controls()
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
            // Returns false when no share sheet could be opened, so the dialog can say so in words.
            onShare = { file, uri -> shareExportedVideo(context, file, uri) },
            onOpen = { uri -> openVideo(context, uri) },
            exportedUri = exportedUri,
            timeLeft = timeLeft,
        )
    }

}

/** "Narrated with the phone's voice until setup is finished." + Finish setup; wraps at a large font. */
@Composable
private fun VoiceNotInstalledNotice(onFinishSetup: () -> Unit) {
    FlowRow(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = stringResource(R.string.video_voice_not_installed),
            style = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.Content),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(end = 8.dp).align(Alignment.CenterVertically),
        )
        TextButton(onClick = onFinishSetup, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(stringResource(R.string.video_finish_setup))
        }
    }
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

    // The frame is a drawing (board, side panel, burned-in caption); the narration is spoken audio, so
    // TalkBack gets what the picture is, not the caption a second time.
    val frameDescription = stringResource(R.string.cd_video_frame, script.title)
    AndroidView(
        modifier = modifier
            .then(if (fillAvailableSpace) Modifier else Modifier.fillMaxWidth())
            .aspectRatio(VideoExporter.VIDEO_WIDTH.toFloat() / VideoExporter.VIDEO_HEIGHT.toFloat())
            .semantics { contentDescription = frameDescription }
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
    // Time runs left to right in every language, like the transport row under it: pinned LTR so the
    // thumb starts at the left in RTL too.
    val label = stringResource(R.string.video_scrub)
    val position = stringResource(
        R.string.video_scrub_state,
        android.text.format.DateUtils.formatElapsedTime(playerState.positionMs / 1000),
        android.text.format.DateUtils.formatElapsedTime(playerState.totalDurationMs / 1000),
    )
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Slider(
            value = playerState.positionMs.toFloat(),
            valueRange = 0f..playerState.totalDurationMs.toFloat().coerceAtLeast(1f),
            onValueChange = { onSeek(it.toLong()) },
            // "Video position, 0:12 of 6:20": the bare slider said only a percentage.
            modifier = modifier.semantics {
                contentDescription = label
                stateDescription = position
            },
        )
    }
}

/**
 * Previous, play/pause, next, with the two small player controls on either side: **one** speaker
 * icon that mutes the narration while playback carries on, and **one** speed button that cycles
 * 1x, 1.25x, 1.5x. Neither is remembered. The row is pinned LTR like the Board's transport: media
 * controls keep their order and their arrows in every language, and the speed label is
 * LRM-prefixed besides.
 */
@Composable
private fun TransportRow(playerState: PlayerUiState, controller: VideoPlayerController, modifier: Modifier = Modifier) {
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Row(
            modifier = modifier,
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val announce = rememberAnnouncer()
            val mutedSpoken = stringResource(R.string.video_muted_announce)
            val unmutedSpoken = stringResource(R.string.video_unmuted_announce)
            Box(modifier = Modifier.widthIn(min = 72.dp), contentAlignment = Alignment.Center) {
                IconButton(onClick = {
                    // Said aloud as well as shown: the icon swaps, which a screen-reader user cannot see.
                    announce(if (playerState.muted) unmutedSpoken else mutedSpoken)
                    controller.toggleMuted()
                }) {
                    Icon(
                        if (playerState.muted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
                        contentDescription = stringResource(if (playerState.muted) R.string.video_unmute else R.string.video_mute),
                    )
                }
            }
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
            Box(modifier = Modifier.widthIn(min = 72.dp), contentAlignment = Alignment.Center) {
                val speedLabel = stringResource(R.string.video_speed_value, playbackSpeedNumber(playerState.speed))
                val speedSpoken = stringResource(R.string.video_speed, speedLabel)
                TextButton(
                    onClick = { controller.setSpeed(nextPlaybackSpeed(playerState.speed)) },
                    modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = speedSpoken },
                    contentPadding = PaddingValues(horizontal = 8.dp),
                ) {
                    Text(speedLabel, maxLines = 1, fontWeight = FontWeight.Bold)
                }
            }
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
    onShare: (java.io.File?, Uri?) -> Boolean,
    onOpen: (Uri) -> Unit,
    timeLeft: ExportTimeLeft,
) {
    var shareFailed by remember { mutableStateOf(false) }
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
            // Scrolls: at a 2.0 font the completed dialog is taller than a landscape window.
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                when (state) {
                    is VideoExporter.State.SynthesizingNarration -> {
                        Text(stringResource(R.string.video_export_synthesizing, state.completed, state.total.coerceAtLeast(1)))
                        // Measured, not guessed: from the time the finished segments took (ExportTimeLeft).
                        when (val left = timeLeft) {
                            ExportTimeLeft.Hidden -> Unit
                            ExportTimeLeft.LessThanMinute -> Text(
                                stringResource(R.string.video_export_less_than_minute),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            is ExportTimeLeft.Minutes -> Text(
                                pluralStringResource(R.plurals.video_export_minutes_left, left.minutes, left.minutes),
                                style = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.Content),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
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
                        val dialogContext = LocalContext.current
                        Text(
                            stringResource(
                                R.string.video_export_completed_body,
                                formatVideoDuration(dialogContext, state.durationMs),
                                Formatter.formatShortFileSize(dialogContext, state.fileSizeBytes),
                            )
                        )
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
                        // Sharing could not be started (no app can take a video, or the file is gone):
                        // a plain sentence, spoken when it appears, never a crash.
                        if (shareFailed) {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                stringResource(R.string.video_share_failed),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
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
            if (state is VideoExporter.State.Completed) {
                // Close, Open and Share (Share last: it is the next step after saving). A flow row, so at a
                // large font the three wrap onto two lines instead of clipping.
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End),
                ) {
                    TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.video_export_close))
                    }
                    if (exportedUri != null) {
                        TextButton(onClick = { onOpen(exportedUri) }, modifier = Modifier.heightIn(min = 48.dp)) {
                            Text(stringResource(R.string.video_export_open))
                        }
                    }
                    TextButton(
                        onClick = { shareFailed = !onShare(state.file, exportedUri) },
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) { Text(stringResource(R.string.video_export_share)) }
                }
            } else if (isTerminal) {
                TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.video_export_close)) }
            } else {
                TextButton(onClick = onCancel, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.video_export_cancel)) }
            }
        },
    )
}

/**
 * Opens the system share sheet for the exported MP4. A local intent: `ACTION_SEND` with the video
 * as a `content://` stream from the app's own FileProvider (`res/xml/file_paths.xml`, whose
 * `cache-path` covers `cacheDir/video_export/`, where the exporter writes), read access granted for
 * that one URI. No permission is needed and nothing leaves the device until the person picks an
 * app. Returns false, instead of throwing, when the share sheet cannot be started.
 */
internal fun shareExportedVideo(context: Context, file: java.io.File?, fallbackUri: Uri?): Boolean {
    val intent = buildVideoShareIntent(context, file, fallbackUri) ?: return false
    return try {
        context.startActivity(Intent.createChooser(intent, context.getString(R.string.video_export_share)))
        true
    } catch (e: Exception) {
        false
    }
}

/** The `ACTION_SEND` intent for [file] (via the FileProvider), or for [fallbackUri] when the file is gone; null when neither exists. */
internal fun buildVideoShareIntent(context: Context, file: java.io.File?, fallbackUri: Uri?): Intent? {
    val uri: Uri = try {
        if (file != null && file.isFile) MediaStorePublisher.shareUriFor(context, file) else fallbackUri
    } catch (e: IllegalArgumentException) {
        // A file outside every <paths> root: fall back to the MediaStore copy.
        fallbackUri
    } ?: return null
    return Intent(Intent.ACTION_SEND).apply {
        type = "video/mp4"
        putExtra(Intent.EXTRA_STREAM, uri)
        // The grant goes with the clip data (what the chooser and the target read), plus the flag.
        clipData = android.content.ClipData.newRawUri(null, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}

private fun openVideo(context: android.content.Context, uri: Uri) {
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, "video/mp4")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    try {
        context.startActivity(intent)
    } catch (e: Exception) {
        // No video player installed: offer the share sheet instead (and ignore it if that fails too).
        shareExportedVideo(context, null, uri)
    }
}

/** "9 min 33 s" / "45 s": whole seconds, words from resources so they follow the app language. */
internal fun formatVideoDuration(context: Context, durationMs: Long): String {
    val totalSeconds = ((durationMs + 500) / 1000).coerceAtLeast(0)
    val minutes = (totalSeconds / 60).toInt()
    val seconds = (totalSeconds % 60).toInt()
    return if (minutes > 0) {
        context.getString(R.string.video_duration_min_sec, minutes, seconds)
    } else {
        context.getString(R.string.video_duration_sec, seconds)
    }
}
