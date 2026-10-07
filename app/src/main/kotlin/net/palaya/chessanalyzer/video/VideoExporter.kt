package net.palaya.chessanalyzer.video

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import java.io.File
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.min
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.palaya.chessanalyzer.core.narration.VideoScript
import net.palaya.chessanalyzer.ui.board.BoardOrientation

/** Export was cancelled cooperatively via [VideoExporter.cancel]. Not a [CancellationException] subtype. */
class VideoExportCancelledException : Exception("Video export cancelled")

/**
 * Renders a [VideoScript] to an MP4 — H.264 video via `MediaCodec` **buffer-mode** input (each
 * frame drawn to an off-screen `Bitmap`/`Canvas` via [BoardFrameRenderer], converted to YUV420
 * and queued with an explicit presentation timestamp), AAC audio muxed as a second track,
 * [android.media.MediaMuxer] tying them together.
 *
 * ## Why buffer mode, not an input `Surface`
 * An earlier version of this class drew frames to `MediaCodec.createInputSurface()` via
 * `Surface.lockCanvas()`/`unlockCanvasAndPost()`. That path has **no public API to set an
 * explicit presentation timestamp** — each posted buffer is stamped with the real wall-clock time
 * it was posted, so the render loop had to sleep between frames to pace them to real time, or the
 * video's own duration would stretch to match however long rendering actually took. That was fine
 * as long as every frame rendered in a fixed, fast time — but once the side-panel/eval-bar
 * drawing made frames more expensive, rendering could no longer keep up with the 30fps real-time
 * pace, frames landed further apart in wall-clock time than intended, and the exported video's
 * duration inflated (measured: 2.3x too long) — **and worse, would keep getting worse under load
 * or on slower hardware, silently desyncing the (correctly-timed) audio track from the board.**
 * That's a correctness bug, not a performance quirk, so it's fixed at the root: every frame now
 * gets an **explicit** `presentationTimeUs = frameIndex * 1_000_000L / FPS`, computed purely from
 * the timeline, queued via `queueInputBuffer`. The render loop no longer sleeps at all — it runs
 * as fast as it can, and the exported duration is deterministic regardless of device speed.
 *
 * The trade is real: buffer mode means converting each ARGB `Bitmap` frame to YUV420 by hand
 * ([fillYuv420Image]) instead of letting the codec's internal `Surface`/GLES pipeline do it, which
 * is more CPU work per frame than the GPU-backed surface path would be. That's the right trade
 * here — correctness (a video whose duration is *always* right) over raw encode speed.
 *
 * ## Track ordering gotcha
 * `MediaMuxer.addTrack` must be called for *every* track before the first `MediaMuxer.start()`,
 * and no `writeSampleData` may happen before `start()`. The audio track's format is cheap to get
 * up front (audio is encoded to completion first, in memory, before the muxer exists at all); the
 * video track's format is only known once the H.264 encoder reports `INFO_OUTPUT_FORMAT_CHANGED`,
 * which requires at least one input buffer already queued. So: queue frame 0 -> add the video
 * track once we see the format change -> muxer.start() -> flush the pre-encoded audio samples ->
 * continue the frame loop for real. End of stream is signalled the buffer-mode way too — an empty
 * `queueInputBuffer` call carrying `BUFFER_FLAG_END_OF_STREAM` — since `signalEndOfInputStream()`
 * is a surface-only API and throws in buffer mode.
 */
class VideoExporter(private val context: Context) {

    sealed interface State {
        data object Idle : State
        data class SynthesizingNarration(val completed: Int, val total: Int) : State
        data class Rendering(val elapsedMs: Long, val totalMs: Long) : State
        data object Finalizing : State
        data class Completed(
            val file: File,
            val mediaStoreUri: Uri?,
            val durationMs: Long,
            val fileSizeBytes: Long,
            val narrationWasSpoken: Boolean,
            /** Non-blocking summary if a paid narration provider fell back to the device voice. */
            val narrationNotice: String? = null,
        ) : State
        data class Failed(val message: String) : State
        data object Cancelled : State
    }

    companion object {
        const val VIDEO_WIDTH = 1280
        const val VIDEO_HEIGHT = 720
        const val FPS = 30
        const val VIDEO_BITRATE = 4_000_000
        const val AUDIO_SAMPLE_RATE = 44_100
        const val AUDIO_BITRATE = 128_000
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    @Volatile private var cancelRequested = false
    fun cancel() {
        cancelRequested = true
    }

    private fun checkCancelled() {
        if (cancelRequested) throw VideoExportCancelledException()
    }

    /**
     * Renders [script] end to end and returns the produced MP4 in the app's cache dir (the
     * caller — [VideoScreen]/the instrumented test — decides whether/when to publish it to
     * MediaStore via [MediaStorePublisher]). Never blocks the calling thread's dispatcher: runs
     * on [Dispatchers.Default].
     *
     * @param narrationProvider when null (the default), narration uses the original
     *   [NarrationSynthesizer] device-TTS path directly, unchanged. When set (e.g. a
     *   [NeuralTtsProvider] built from the user's Settings), narration is driven through
     *   [NarrationCoordinator] instead, which enforces the mandatory per-segment fallback to
     *   [DeviceTtsProvider] and surfaces a single notice via [State.Completed.narrationNotice]
     *   if any segment had to fall back.
     */
    suspend fun export(
        script: VideoScript,
        baseName: String,
        narrationProvider: NarrationVoiceProvider? = null,
    ): File = withContext(Dispatchers.Default) {
        cancelRequested = false
        val workDir = File(context.cacheDir, "video_export").apply { mkdirs() }
        val panelLabels = BoardFrameRenderer.PanelLabels.from(context)
        val outFile = File(workDir, "$baseName.mp4")
        var narrationWasSpoken = false
        var narrationNotice: String? = null

        var muxer: MediaMuxer? = null
        var videoCodec: MediaCodec? = null

        try {
            // ---- 1. Narration -> one WAV per segment. Never throws. ----
            _state.value = State.SynthesizingNarration(0, script.segments.size)
            val ttsDir = File(workDir, "tts_$baseName")
            val synthResults: List<NarrationSynthesizer.Result>
            if (narrationProvider == null) {
                val synthesizer = NarrationSynthesizer(context)
                val progressJob = launch {
                    synthesizer.progress.collect { p -> _state.value = State.SynthesizingNarration(p.completed, p.total) }
                }
                synthResults = synthesizer.synthesizeAll(script, ttsDir)
                progressJob.cancel()
            } else {
                // Persistent (filesDir), not cacheDir — see NarrationStore's doc: this is paid
                // audio the OS must not silently evict under storage pressure.
                val store = NarrationStore.forApp(context)
                val coordinator = NarrationCoordinator(narrationProvider, DeviceTtsProvider(context), store)
                val progressJob = launch {
                    coordinator.progress.collect { p -> _state.value = State.SynthesizingNarration(p.completed, p.total) }
                }
                val outcome = coordinator.synthesizeAll(script, ttsDir)
                progressJob.cancel()
                synthResults = outcome.results
                narrationNotice = outcome.notice
            }
            narrationWasSpoken = synthResults.any { it is NarrationSynthesizer.Result.Synthesized }
            checkCancelled()

            // ---- 2. Real timeline, now that speech durations are known. ----
            // R6b: the silent recap end card follows the last segment. It is not a segment (the
            // "N of M" and time-left figures above are unchanged) and it is outside the pacing
            // budget (ANALYSIS_SPEC 9.7): the narrated length is whatever the budget allowed, and
            // the card adds its own 4-6 s on top.
            val recapCard = script.recap?.let { RecapCardContent.from(it, panelLabels.recap) }
            val narratedTimeline = TimelineBuilder.build(script, synthResults)
            val timeline = if (recapCard != null) {
                narratedTimeline.withRecap(recapDurationMs(recapCard.readingWords))
            } else {
                narratedTimeline
            }

            // ---- 3. PCM timeline -> AAC, fully encoded up front so its MediaFormat (and csd-0)
            // is ready before the muxer needs it. A multi-minute review's AAC track is only a
            // couple of MB, so buffering the encoded samples in memory is fine. ----
            val pcmFile = File(workDir, "audio_$baseName.pcm")
            buildPcmTrack(script, synthResults, timeline, pcmFile, AUDIO_SAMPLE_RATE)
            checkCancelled()
            val audioTrack = encodeAudioTrack(pcmFile, AUDIO_SAMPLE_RATE)
            pcmFile.delete()
            ttsDir.deleteRecursively()
            checkCancelled()

            // ---- 4. Video encode + mux. ----
            outFile.delete()
            val realMuxer = MediaMuxer(outFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer = realMuxer
            val audioTrackIndex = realMuxer.addTrack(audioTrack.format)

            // COLOR_FormatYUV420Flexible works with any YUV-capable encoder (software or
            // hardware) — the framework maps it to whatever concrete planar/semi-planar layout
            // the codec actually uses, and `getInputImage()` exposes that layout's real
            // per-plane row/pixel strides so we never have to guess or hardcode one.
            val videoFormat = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, VIDEO_WIDTH, VIDEO_HEIGHT).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
                setInteger(MediaFormat.KEY_BIT_RATE, VIDEO_BITRATE)
                setInteger(MediaFormat.KEY_FRAME_RATE, FPS)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
            }
            val realVideoCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            videoCodec = realVideoCodec
            realVideoCodec.configure(videoFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            realVideoCodec.start()

            var videoTrackIndex = -1
            var muxerStarted = false
            val bufferInfo = MediaCodec.BufferInfo()

            // No PTS rebasing needed here (unlike the old surface-based version): every frame's
            // timestamp is already computed from the timeline and starts at 0, exactly like the
            // audio track, so video and audio are aligned by construction rather than by patching
            // up wall-clock-derived numbers after the fact.
            fun drainVideo(blockForFormat: Boolean) {
                while (true) {
                    val timeoutUs = if (blockForFormat && videoTrackIndex < 0) 2_000_000L else 0L
                    val index = realVideoCodec.dequeueOutputBuffer(bufferInfo, timeoutUs)
                    when {
                        index == MediaCodec.INFO_TRY_AGAIN_LATER -> return
                        index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            if (videoTrackIndex < 0) videoTrackIndex = realMuxer.addTrack(realVideoCodec.outputFormat)
                            if (!muxerStarted) {
                                realMuxer.start()
                                muxerStarted = true
                                val info = MediaCodec.BufferInfo()
                                for (sample in audioTrack.samples) {
                                    info.set(0, sample.data.size, sample.presentationTimeUs, sample.flags)
                                    realMuxer.writeSampleData(audioTrackIndex, ByteBuffer.wrap(sample.data), info)
                                }
                            }
                        }
                        index >= 0 -> {
                            val isConfig = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                            val buf = realVideoCodec.getOutputBuffer(index)
                            if (buf != null && bufferInfo.size > 0 && !isConfig && muxerStarted && videoTrackIndex >= 0) {
                                realMuxer.writeSampleData(videoTrackIndex, buf, bufferInfo)
                            }
                            val eos = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                            realVideoCodec.releaseOutputBuffer(index, false)
                            if (eos) return
                        }
                    }
                }
            }

            /** Blocks (draining output to avoid deadlocking the codec's bounded input queue) until an input buffer is free. */
            fun awaitInputBuffer(): Int {
                while (true) {
                    checkCancelled()
                    val index = realVideoCodec.dequeueInputBuffer(10_000)
                    if (index >= 0) return index
                    drainVideo(blockForFormat = false)
                }
            }

            val totalFrames = ((timeline.totalDurationMs * FPS) / 1000L).coerceAtLeast(1L)
            val frameBitmap = Bitmap.createBitmap(VIDEO_WIDTH, VIDEO_HEIGHT, Bitmap.Config.ARGB_8888)
            val frameCanvas = Canvas(frameBitmap)
            val frameByteCount = VIDEO_WIDTH * VIDEO_HEIGHT * 3 / 2 // 4:2:0
            // Reused every frame — allocating a fresh IntArray/ByteArray per frame (500+ frames
            // for even a short review) was the other half of the original YUV conversion being
            // too slow: per-frame allocation of a ~3.6MB IntArray plus GC churn on top of the
            // (also since-fixed) per-pixel JNI put calls.
            val yuvScratch = YuvScratchBuffers(VIDEO_WIDTH, VIDEO_HEIGHT)
            var frameIndex = 0L
            // Every recap frame is the same picture: draw it once and let the codec re-encode the bitmap.
            var recapDrawn = false

            while (frameIndex < totalFrames) {
                checkCancelled()
                val targetMs = (frameIndex * 1000L) / FPS
                val timed = timeline.segmentAt(targetMs)
                if (recapCard != null && timeline.inRecap(targetMs)) {
                    if (!recapDrawn) {
                        BoardFrameRenderer.renderRecapFrame(frameCanvas, VIDEO_WIDTH, VIDEO_HEIGHT, recapCard)
                        recapDrawn = true
                    }
                } else if (timed == null) {
                    BoardFrameRenderer.renderCardFrame(frameCanvas, VIDEO_WIDTH, VIDEO_HEIGHT, script.title, listOf(script.subtitle))
                } else {
                    val elapsedInSegment = targetMs - timed.startMs
                    val chapterLabel = SegmentFrameBuilder.chapterLabelFor(script, timed.segment.index)
                    when (
                        val instruction = SegmentFrameBuilder.build(
                            script, timed.segment, elapsedInSegment, BoardOrientation.WHITE_DOWN, panelLabels,
                            speechMs = timed.speechDurationMs,
                        )
                    ) {
                        is RenderInstruction.Board ->
                            BoardFrameRenderer.renderBoardFrame(frameCanvas, VIDEO_WIDTH, VIDEO_HEIGHT, instruction.spec)
                        is RenderInstruction.Card ->
                            BoardFrameRenderer.renderCardFrame(
                                frameCanvas, VIDEO_WIDTH, VIDEO_HEIGHT, instruction.content, instruction.caption, chapterLabel,
                            )
                    }
                }

                val ptsUs = (frameIndex * 1_000_000L) / FPS
                val inputIndex = awaitInputBuffer()
                val image = realVideoCodec.getInputImage(inputIndex)
                    ?: error("MediaCodec.getInputImage returned null — codec not configured for buffer-mode YUV input")
                fillYuv420Image(image, frameBitmap, VIDEO_WIDTH, VIDEO_HEIGHT, yuvScratch)
                realVideoCodec.queueInputBuffer(inputIndex, 0, frameByteCount, ptsUs, 0)

                // Frame 0 must block until the encoder hands us its format so the muxer can start
                // and the buffered audio can be flushed; every later frame only drains whatever is
                // already sitting in the encoder's output queue.
                drainVideo(blockForFormat = frameIndex == 0L)

                frameIndex++
                // Deliberately no sleep/delay here — see the class doc. Rendering runs as fast as
                // the device can go; the video's duration comes entirely from `ptsUs` above.
                _state.value = State.Rendering(targetMs, timeline.totalDurationMs)
            }

            checkCancelled()
            _state.value = State.Finalizing
            // Buffer-mode end-of-stream: an empty input buffer carrying BUFFER_FLAG_END_OF_STREAM.
            // (`signalEndOfInputStream()` is surface-mode only and throws IllegalStateException
            // here — this bit us during the surface-to-buffer-mode migration.)
            val eosPtsUs = (totalFrames * 1_000_000L) / FPS
            val eosIndex = awaitInputBuffer()
            realVideoCodec.queueInputBuffer(eosIndex, 0, 0, eosPtsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)

            // Drain to end-of-stream, blocking a little longer per call since the encoder may
            // still be flushing its internal pipeline (B-frame reordering, etc.) with no more
            // input coming.
            var eosSeen = false
            while (!eosSeen) {
                val index = realVideoCodec.dequeueOutputBuffer(bufferInfo, 2_000_000L)
                when {
                    index == MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        if (videoTrackIndex < 0) {
                            videoTrackIndex = realMuxer.addTrack(realVideoCodec.outputFormat)
                            if (!muxerStarted) { realMuxer.start(); muxerStarted = true }
                        }
                    }
                    index >= 0 -> {
                        val isConfig = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                        val buf = realVideoCodec.getOutputBuffer(index)
                        if (buf != null && bufferInfo.size > 0 && !isConfig && muxerStarted && videoTrackIndex >= 0) {
                            realMuxer.writeSampleData(videoTrackIndex, buf, bufferInfo)
                        }
                        eosSeen = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                        realVideoCodec.releaseOutputBuffer(index, false)
                    }
                }
            }

            realVideoCodec.stop()
            realVideoCodec.release()
            videoCodec = null
            frameBitmap.recycle()
            if (muxerStarted) realMuxer.stop()
            realMuxer.release()
            muxer = null

            val durationMs = timeline.totalDurationMs
            _state.value = State.Completed(outFile, null, durationMs, outFile.length(), narrationWasSpoken, narrationNotice)
            outFile
        } catch (e: VideoExportCancelledException) {
            _state.value = State.Cancelled
            safeRelease(muxer, videoCodec)
            outFile.delete()
            throw e
        } catch (e: Exception) {
            _state.value = State.Failed(e.message ?: e.javaClass.simpleName)
            safeRelease(muxer, videoCodec)
            outFile.delete()
            throw e
        }
    }

    private fun safeRelease(muxer: MediaMuxer?, codec: MediaCodec?) {
        runCatching { codec?.stop() }
        runCatching { codec?.release() }
        runCatching { muxer?.stop() }
        runCatching { muxer?.release() }
    }

    /** Reused scratch space for [fillYuv420Image] — see that function's doc for why this matters. */
    private class YuvScratchBuffers(width: Int, height: Int) {
        val pixels = IntArray(width * height)
        val lumaRow = ByteArray(width)
        val chromaRow = ByteArray(width / 2)
    }

    /**
     * Copies [bitmap]'s ARGB pixels into [image]'s Y/U/V planes (4:2:0 subsampled), respecting
     * each plane's actual `rowStride`/`pixelStride` — which is *why* this goes through the
     * `Image` API instead of hand-packing a tightly-packed I420 buffer: the concrete layout
     * behind `COLOR_FormatYUV420Flexible` is codec/device-specific (planar vs. semi-planar,
     * padded rows or not), and `Image.planes` is the one API that tells you the truth about it
     * instead of assuming.
     *
     * **Performance note, because this bit us once already:** the first version of this function
     * wrote one byte at a time via `ByteBuffer.put(index, byte)` directly against the plane's
     * buffer — which, for the direct/native-backed buffers `Image` hands out, is a JNI transition
     * *per pixel* (literally millions per frame at 1280x720). That made a ~17s test export take
     * 78s wall-clock, failing this class's own "export isn't paced to real time" guarantee for a
     * completely different reason than the original wall-clock-PTS bug — CPU cost, not a design
     * flaw, but a real one. Fixed by building each row into a plain JVM `ByteArray`
     * ([YuvScratchBuffers], reused across frames to also avoid per-frame allocation churn) and
     * doing exactly one bulk `ByteBuffer.put(byte[], off, len)` per row when the plane is truly
     * packed (`pixelStride == 1`, the common case for the luma plane and for planar chroma);
     * only semi-planar (interleaved, `pixelStride == 2`) chroma still needs per-sample writes,
     * and there are 4x fewer of those than luma samples.
     *
     * Standard BT.601 integer approximation (the same coefficients used in Android's own
     * CTS `EncodeDecodeTest`) — this is a flat-shaded UI render, not photographic content, so
     * broadcast-grade colour accuracy isn't the bar; correctness of the *timing*, which this
     * whole rewrite is about, doesn't depend on it.
     */
    private fun fillYuv420Image(image: Image, bitmap: Bitmap, width: Int, height: Int, scratch: YuvScratchBuffers) {
        val pixels = scratch.pixels
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        // Luma: 1:1 with the source pixel grid.
        writePlane(image.planes[0], width, height, scratch.lumaRow, pixels, pixelRowStride = width, pixelColStride = 1, convert = ::lumaByte)

        // Chroma: one sample per 2x2 block — every other row, every other column of `pixels`.
        val chromaWidth = width / 2
        val chromaHeight = height / 2
        writePlane(image.planes[1], chromaWidth, chromaHeight, scratch.chromaRow, pixels, pixelRowStride = width * 2, pixelColStride = 2, convert = ::chromaByteU)
        writePlane(image.planes[2], chromaWidth, chromaHeight, scratch.chromaRow, pixels, pixelRowStride = width * 2, pixelColStride = 2, convert = ::chromaByteV)
    }

    /**
     * Writes one [planeWidth]x[planeHeight] plane from [pixels], stepping [pixelRowStride]/
     * [pixelColStride] through the *source* pixel array per output row/column (1/1 for luma;
     * 2*width/2 for chroma, since each chroma sample covers a 2x2 source block). Bulk-copies a
     * whole row at once via [rowScratch] when the plane is truly packed (`pixelStride == 1`);
     * falls back to per-sample writes only for interleaved (semi-planar) chroma.
     */
    private inline fun writePlane(
        plane: Image.Plane,
        planeWidth: Int,
        planeHeight: Int,
        rowScratch: ByteArray,
        pixels: IntArray,
        pixelRowStride: Int,
        pixelColStride: Int,
        convert: (Int) -> Byte,
    ) {
        val buf = plane.buffer
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        for (row in 0 until planeHeight) {
            val sourceRowBase = row * pixelRowStride
            if (pixelStride == 1) {
                var srcIdx = sourceRowBase
                for (col in 0 until planeWidth) {
                    rowScratch[col] = convert(pixels[srcIdx])
                    srcIdx += pixelColStride
                }
                buf.position(row * rowStride)
                buf.put(rowScratch, 0, planeWidth)
            } else {
                var pos = row * rowStride
                var srcIdx = sourceRowBase
                for (col in 0 until planeWidth) {
                    buf.put(pos, convert(pixels[srcIdx]))
                    pos += pixelStride
                    srcIdx += pixelColStride
                }
            }
        }
    }

    private fun lumaByte(argb: Int): Byte {
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        return (((66 * r + 129 * g + 25 * b + 128) shr 8) + 16).coerceIn(0, 255).toByte()
    }

    private fun chromaByteU(argb: Int): Byte {
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        return (((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128).coerceIn(0, 255).toByte()
    }

    private fun chromaByteV(argb: Int): Byte {
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        return (((112 * r - 94 * g - 18 * b + 128) shr 8) + 128).coerceIn(0, 255).toByte()
    }

    // ---------------------------------------------------------------------
    // Audio: WAV segments + silence padding -> raw PCM -> AAC
    // ---------------------------------------------------------------------

    /**
     * Writes the whole spoken track as raw 16-bit mono PCM at [sampleRate]: each segment's silent
     * lead-in ([TimedSegment.leadInMs], ANALYSIS_SPEC 9.8), then its speech samples (real TTS audio,
     * or silence when that segment fell back), padded out to exactly [TimedSegment.speechDurationMs],
     * then the rest of [TimedSegment.totalDurationMs] as trailing silence (the `holdAfterMs` beat plus
     * the inter-segment gap) — so every segment's audio chunk is exactly as long as the board holds
     * that segment on screen, keeping speech and picture in lockstep however TTS durations landed.
     */
    private fun buildPcmTrack(
        script: VideoScript,
        synthResults: List<NarrationSynthesizer.Result>,
        timeline: ScriptTimeline,
        outFile: File,
        sampleRate: Int,
    ) {
        val byIndex = synthResults.associateBy { it.segmentIndex }
        outFile.outputStream().buffered().use { out ->
            for (timed in timeline.segments) {
                // ANALYSIS_SPEC 9.8: the lead-in before a key move is silent; the speech starts after it,
                // exactly when the board starts the move (SegmentFrameBuilder shifts the board the same way).
                val leadInSamples = ((timed.leadInMs * sampleRate) / 1000L).toInt().coerceAtLeast(0)
                writeSilence(out, leadInSamples)
                val speechTargetSamples = ((timed.speechDurationMs * sampleRate) / 1000L).toInt().coerceAtLeast(0)
                val rawSpeech = when (val result = byIndex[timed.segment.index]) {
                    is NarrationSynthesizer.Result.Synthesized -> WavUtil.readAsMono16(result.wavFile, sampleRate)
                    else -> ShortArray(0)
                }
                val fitted = ShortArray(speechTargetSamples)
                System.arraycopy(rawSpeech, 0, fitted, 0, min(rawSpeech.size, speechTargetSamples))
                writeShorts(out, fitted)

                val totalSamples = ((timed.totalDurationMs * sampleRate) / 1000L).toInt().coerceAtLeast(0)
                writeSilence(out, (totalSamples - leadInSamples - speechTargetSamples).coerceAtLeast(0))
            }
            // The recap card is silent: the audio track stays as long as the picture.
            writeSilence(out, ((timeline.recapDurationMs * sampleRate) / 1000L).toInt())
        }
    }

    private fun writeShorts(out: OutputStream, shorts: ShortArray) {
        if (shorts.isEmpty()) return
        val bytes = ByteArray(shorts.size * 2)
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        for (s in shorts) bb.putShort(s)
        out.write(bytes)
    }

    private fun writeSilence(out: OutputStream, sampleCount: Int) {
        if (sampleCount <= 0) return
        out.write(ByteArray(sampleCount * 2))
    }

    private data class EncodedSample(val data: ByteArray, val flags: Int, val presentationTimeUs: Long)
    private data class EncodedAudioTrack(val format: MediaFormat, val samples: List<EncodedSample>)

    /** Encodes a raw 16-bit mono PCM file to AAC-LC, fully, synchronously, before any muxer exists. */
    private fun encodeAudioTrack(pcmFile: File, sampleRate: Int): EncodedAudioTrack {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, 1).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, AUDIO_BITRATE)
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()

        val samples = ArrayList<EncodedSample>()
        var outputFormat: MediaFormat? = null
        val bufferInfo = MediaCodec.BufferInfo()
        val chunk = ByteArray(4096)
        var samplesFed = 0L
        var eosQueued = false
        var moreInput = pcmFile.length() > 0

        try {
            pcmFile.inputStream().buffered().use { input ->
                while (true) {
                    if (!eosQueued) {
                        val inIndex = codec.dequeueInputBuffer(10_000)
                        if (inIndex >= 0) {
                            val buf = codec.getInputBuffer(inIndex)!!
                            buf.clear()
                            val read: Int = if (moreInput) input.read(chunk) else -1
                            val ptsUs = (samplesFed * 1_000_000L) / sampleRate
                            if (read <= 0) {
                                moreInput = false
                                codec.queueInputBuffer(inIndex, 0, 0, ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                eosQueued = true
                            } else {
                                buf.put(chunk, 0, read)
                                codec.queueInputBuffer(inIndex, 0, read, ptsUs, 0)
                                samplesFed += read / 2
                            }
                        }
                    }
                    val outIndex = codec.dequeueOutputBuffer(bufferInfo, 10_000)
                    when {
                        outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> outputFormat = codec.outputFormat
                        outIndex >= 0 -> {
                            val isConfig = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                            if (bufferInfo.size > 0 && !isConfig) {
                                val outBuf = codec.getOutputBuffer(outIndex)!!
                                outBuf.position(bufferInfo.offset)
                                outBuf.limit(bufferInfo.offset + bufferInfo.size)
                                val data = ByteArray(bufferInfo.size)
                                outBuf.get(data)
                                samples.add(EncodedSample(data, bufferInfo.flags, bufferInfo.presentationTimeUs))
                            }
                            val isEos = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                            codec.releaseOutputBuffer(outIndex, false)
                            if (isEos) return@use
                        }
                    }
                }
            }
        } finally {
            codec.stop()
            codec.release()
        }

        return EncodedAudioTrack(outputFormat ?: format, samples)
    }
}
