package net.palaya.chessanalyzer.ui.video

import android.content.Context
import android.media.MediaPlayer
import android.speech.tts.TextToSpeech
import java.io.File
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import net.palaya.chessanalyzer.core.narration.ScriptSegment
import net.palaya.chessanalyzer.core.narration.VideoScript
import net.palaya.chessanalyzer.ui.board.BoardOrientation
import net.palaya.chessanalyzer.video.DeviceTtsProvider
import net.palaya.chessanalyzer.video.NarrationStore
import net.palaya.chessanalyzer.video.NarrationSynthesizer
import net.palaya.chessanalyzer.video.NarrationVoiceProvider
import net.palaya.chessanalyzer.video.RenderInstruction
import net.palaya.chessanalyzer.video.ScriptTimeline
import net.palaya.chessanalyzer.video.SegmentFrameBuilder
import net.palaya.chessanalyzer.video.BoardFrameRenderer
import net.palaya.chessanalyzer.video.TimelineBuilder
import net.palaya.chessanalyzer.video.WavUtil
import net.palaya.chessanalyzer.video.narrationCacheFingerprint

/** Which narration source actually spoke the current segment — surfaced mainly for tests/diagnosis. */
enum class NarrationSource { NONE, FILE, TTS }

data class PlayerUiState(
    val segmentIndex: Int = 0,
    val positionMs: Long = 0L,
    val totalDurationMs: Long = 0L,
    val isPlaying: Boolean = false,
    val speed: Float = 1f,
    /** The narration is muted (playback and the board carry on). Not persisted; see [VideoPlayerController.setMuted]. */
    val muted: Boolean = false,
    val caption: String = "",
    val chapterLabel: String? = null,
    val instruction: RenderInstruction? = null,
    val narrationAvailable: Boolean = true,
    val finished: Boolean = false,
    /** Where the CURRENT segment's narration came from — [NarrationSource.NONE] until decided. */
    val narrationSource: NarrationSource = NarrationSource.NONE,
)

/**
 * Drives **live in-app playback** of a [VideoScript]: a virtual clock advances the board/caption
 * (via [SegmentFrameBuilder] — the same timing/animation logic [net.palaya.chessanalyzer.video.VideoExporter]
 * uses) while narration plays in parallel.
 *
 * Narration prefers **pre-generated audio** over the live device voice: for every segment this
 * checks [NarrationStore] (the same persistent, `filesDir`-backed cache
 * [net.palaya.chessanalyzer.video.NarrationCoordinator] populates on export or via "Prepare
 * narration") for a ready WAV keyed by (segment text, [narrationProvider]'s identity). When one
 * exists it is played with [MediaPlayer] — so the in-app preview, the app's main feature, actually
 * uses the narration voice the user configured, not just the exported MP4. Only when no file
 * is available for a segment does this fall back to live [TextToSpeech.speak] — never the other way
 * around, and never a silent gap either way.
 *
 * Because segment timing ([timeline]) is built from the SAME cached files' real measured
 * durations (falling back to [ScriptSegment.estimatedSpeechMs] only where nothing is cached yet),
 * a fully-prepared review's board/voice sync is exact, not estimated.
 */
class VideoPlayerController(
    context: Context,
    val script: VideoScript,
    private val scope: CoroutineScope,
    /** Non-null only when the user has a non-device provider (the neural voice) configured. */
    narrationProvider: NarrationVoiceProvider? = null,
) {
    private val appContext = context.applicationContext

    /** Side-panel wording in the app's current language, resolved once per controller. */
    private val panelLabels = BoardFrameRenderer.PanelLabels.from(appContext)
    private val store = NarrationStore.forApp(appContext)

    /** Only used for its cache identity (displayName + fingerprint) — never prepared/synthesized. */
    private val identityProvider: NarrationVoiceProvider = narrationProvider ?: DeviceTtsProvider(appContext)

    private val prebuiltFromCache: List<NarrationSynthesizer.Result> = buildList {
        for (segment in script.segments) {
            if (segment.narration.isBlank()) continue
            val file = cachedFileFor(segment) ?: continue
            val info = WavUtil.readHeader(file) ?: continue
            add(NarrationSynthesizer.Result.Synthesized(segment.index, file, info.durationMs))
        }
    }

    /** True if at least one segment already has pre-generated audio ready to play. */
    private val anyPreparedAudio = prebuiltFromCache.isNotEmpty()

    private val timeline: ScriptTimeline = TimelineBuilder.build(script, prebuiltFromCache)

    private val _uiState = MutableStateFlow(
        PlayerUiState(totalDurationMs = timeline.totalDurationMs, narrationAvailable = anyPreparedAudio),
    )
    val uiState: StateFlow<PlayerUiState> = _uiState.asStateFlow()

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var activeMediaPlayer: MediaPlayer? = null
    private var tickJob: Job? = null
    private var lastNarratedIndex = -1

    private var positionMs = 0L
    private var playing = false
    private var speed = 1f
    private var muted = false

    init {
        tts = TextToSpeech(appContext) { status ->
            ttsReady = if (status == TextToSpeech.SUCCESS) {
                val avail = tts?.setLanguage(Locale.getDefault())
                avail != TextToSpeech.LANG_MISSING_DATA && avail != TextToSpeech.LANG_NOT_SUPPORTED
            } else {
                false
            }
            _uiState.update { it.copy(narrationAvailable = ttsReady || anyPreparedAudio) }
        }
        emitFrame()
    }

    fun play() {
        if (playing) return
        playing = true
        val resumable = activeMediaPlayer
        if (resumable != null && lastNarratedIndex == currentSegmentIndex()) {
            // Was merely paused mid-segment, not stopped — resume rather than restart from 0,
            // exactly like a video player would.
            runCatching { if (!resumable.isPlaying) resumable.start() }
        } else {
            playOrSpeakCurrentIfNeeded()
        }
        tickJob = scope.launch {
            var lastNanos = System.nanoTime()
            while (isActive && playing) {
                delay(33)
                val now = System.nanoTime()
                val deltaMs = ((now - lastNanos) / 1_000_000L * speed).toLong()
                lastNanos = now
                advance(deltaMs)
            }
        }
        _uiState.update { it.copy(isPlaying = true, finished = false) }
    }

    fun pause() {
        playing = false
        tickJob?.cancel()
        tts?.stop()
        activeMediaPlayer?.let { runCatching { if (it.isPlaying) it.pause() } }
        _uiState.update { it.copy(isPlaying = false) }
    }

    fun togglePlayPause() {
        if (playing) pause() else play()
    }

    /** Absolute-position seek, e.g. from a scrub [androidx.compose.material3.Slider]. */
    fun seekToMs(ms: Long) {
        if (timeline.segments.isEmpty()) return
        positionMs = ms.coerceIn(0, timeline.totalDurationMs)
        resetNarrationState()
        emitFrame()
        if (playing) playOrSpeakCurrentIfNeeded()
    }

    fun skipNext() = seekToSegment(currentSegmentIndex() + 1)
    fun skipPrevious() = seekToSegment(currentSegmentIndex() - 1)

    fun seekToChapterIndex(chapterIndex: Int) {
        val chapter = script.chapters.getOrNull(chapterIndex) ?: return
        seekToSegment(chapter.startSegmentIndex)
    }

    /**
     * Changes playback speed, for the board, and for whichever narration source is currently
     * sounding.
     *
     * **File playback** ([MediaPlayer.setPlaybackParams]) supports changing speed *mid-utterance*
     * — unlike `TextToSpeech`, no re-issue is needed there; the same [android.media.PlaybackParams]
     * call that sets it takes effect immediately on the buffer already playing.
     *
     * **`TextToSpeech.setSpeechRate()` only takes effect on the NEXT `speak()` call** — it does
     * nothing to an utterance already in flight. Setting it alone made the speed control look
     * broken: the board immediately ran faster (the tick loop scales by [speed]) while the voice
     * carried on at the old rate until the next segment, so picture and narration desynced and the
     * chosen speed appeared to do nothing. So for the TTS path only, after setting the rate we
     * re-issue the current segment's utterance, which is the only way to make the new rate audible
     * now. `lastNarratedIndex` is reset so [playOrSpeakCurrentIfNeeded] does not treat the segment
     * as already spoken.
     */
    fun setSpeed(newSpeed: Float) {
        if (newSpeed <= 0f || newSpeed == speed) return
        speed = newSpeed
        tts?.setSpeechRate(newSpeed)
        _uiState.update { it.copy(speed = newSpeed) }
        val player = activeMediaPlayer
        if (player != null) {
            try {
                val wasPlaying = player.isPlaying
                player.playbackParams = player.playbackParams.setSpeed(newSpeed)
                // Some OEM decoders silently pause on a playbackParams change; make sure we're
                // still actually playing afterwards if we were before.
                if (wasPlaying && !player.isPlaying) player.start()
            } catch (e: Exception) {
                // A handful of devices/decoders reject an arbitrary speed change mid-playback.
                // Leave the audio at its previous rate rather than crash — the board (which always
                // honors `speed`) will simply run a little ahead of the voice for the rest of this
                // one segment; the next segment picks up the new speed from scratch via playFromFile.
            }
        } else if (playing) {
            tts?.stop()
            lastNarratedIndex = -1
            playOrSpeakCurrentIfNeeded()
        }
    }

    /**
     * Mutes or unmutes the narration while playback **continues**: the board and captions keep their
     * clock, so unmuting rejoins the voice where the video is, never mid-way through a stale
     * utterance. File narration (the neural voice) keeps playing at volume 0, so unmuting inside a
     * segment resumes it exactly in sync. The live device voice cannot change volume mid-utterance,
     * so muting stops it and it stays silent until the next segment. Not persisted: a new screen
     * starts unmuted.
     */
    fun setMuted(value: Boolean) {
        if (value == muted) return
        muted = value
        activeMediaPlayer?.let { player ->
            val volume = if (value) 0f else 1f
            runCatching { player.setVolume(volume, volume) }
        }
        if (value) tts?.stop()
        _uiState.update { it.copy(muted = value) }
    }

    fun toggleMuted() = setMuted(!muted)

    fun release() {
        playing = false
        tickJob?.cancel()
        tts?.stop()
        tts?.shutdown()
        releaseMediaPlayer()
    }

    private fun currentSegmentIndex(): Int = timeline.segmentAt(positionMs)?.segment?.index ?: 0

    private fun seekToSegment(index: Int) {
        val clamped = index.coerceIn(0, timeline.segments.lastIndex.coerceAtLeast(0))
        if (timeline.segments.isEmpty()) return
        positionMs = timeline.segments[clamped].startMs
        resetNarrationState()
        emitFrame()
        if (playing) playOrSpeakCurrentIfNeeded()
    }

    private fun resetNarrationState() {
        lastNarratedIndex = -1
        tts?.stop()
        releaseMediaPlayer()
    }

    private fun advance(deltaMs: Long) {
        if (timeline.segments.isEmpty()) return
        positionMs = (positionMs + deltaMs).coerceAtMost(timeline.totalDurationMs)
        emitFrame()
        playOrSpeakCurrentIfNeeded()
        if (positionMs >= timeline.totalDurationMs) {
            playing = false
            tickJob?.cancel()
            _uiState.update { it.copy(isPlaying = false, finished = true) }
        }
    }

    /**
     * Narrates the segment currently under the playhead, preferring a pre-generated file (see
     * class doc) and falling back to live TTS only when no file is cached for this segment.
     */
    private fun playOrSpeakCurrentIfNeeded() {
        val timed = timeline.segmentAt(positionMs) ?: return
        if (timed.segment.index == lastNarratedIndex) return
        // ANALYSIS_SPEC 9.8: a key move's lead-in is silent (the board plays the skipped moves and pauses
        // on the position); the voice starts with the move, at the same instant as in the exported MP4.
        if (positionMs < timed.speechStartMs) return
        lastNarratedIndex = timed.segment.index
        releaseMediaPlayer()
        tts?.stop()

        if (timed.segment.narration.isBlank()) {
            _uiState.update { it.copy(narrationSource = NarrationSource.NONE) }
            return
        }

        val file = cachedFileFor(timed.segment)
        if (file != null && playFromFile(file)) {
            _uiState.update { it.copy(narrationSource = NarrationSource.FILE) }
            return
        }

        val engine = tts
        if (muted) {
            // The segment counts as narrated (lastNarratedIndex is set above), so unmuting mid-segment
            // stays silent until the next one rather than restarting a half-gone sentence.
            _uiState.update { it.copy(narrationSource = NarrationSource.NONE) }
        } else if (engine != null && ttsReady) {
            engine.speak(timed.segment.narration, TextToSpeech.QUEUE_FLUSH, null, "live-seg-${timed.segment.index}")
            _uiState.update { it.copy(narrationSource = NarrationSource.TTS) }
        } else {
            _uiState.update { it.copy(narrationSource = NarrationSource.NONE) }
        }
    }

    /** @return true if playback actually started. */
    private fun playFromFile(file: File): Boolean = try {
        val player = MediaPlayer()
        player.setDataSource(file.absolutePath)
        player.setOnErrorListener { _, _, _ -> true } // swallow — board keeps going on caption alone
        player.prepare() // local file, a few seconds at most — synchronous prepare is cheap here
        if (muted) player.setVolume(0f, 0f)
        if (speed != 1f) {
            runCatching { player.playbackParams = player.playbackParams.setSpeed(speed) }
        }
        player.start()
        activeMediaPlayer = player
        true
    } catch (e: Exception) {
        activeMediaPlayer = null
        false
    }

    private fun releaseMediaPlayer() {
        activeMediaPlayer?.let { player ->
            runCatching { if (player.isPlaying) player.stop() }
            runCatching { player.release() }
        }
        activeMediaPlayer = null
    }

    private fun cachedFileFor(segment: ScriptSegment): File? {
        if (segment.narration.isBlank()) return null
        val key = store.keyFor(segment.narration, identityProvider.displayName, identityProvider.narrationCacheFingerprint())
        return store.get(key)
    }

    private fun emitFrame() {
        val timed = timeline.segmentAt(positionMs)
        if (timed == null) {
            _uiState.update { it.copy(positionMs = positionMs) }
            return
        }
        val elapsed = positionMs - timed.startMs
        val chapterLabel = SegmentFrameBuilder.chapterLabelFor(script, timed.segment.index)
        // The same builder and the same timeline as the exporter, so the player and the MP4 draw the same frame.
        val instruction = SegmentFrameBuilder.build(script, timed.segment, elapsed, BoardOrientation.WHITE_DOWN, panelLabels)
        _uiState.update {
            it.copy(
                segmentIndex = timed.segment.index,
                positionMs = positionMs,
                caption = timed.segment.caption,
                chapterLabel = chapterLabel,
                instruction = instruction,
            )
        }
    }
}
