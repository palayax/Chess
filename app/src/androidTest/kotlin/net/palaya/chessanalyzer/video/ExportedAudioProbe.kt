package net.palaya.chessanalyzer.video

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * A [NarrationVoiceProvider] that "speaks" every sentence as a pure sine tone. The point is
 * detectability: a 1 kHz tone survives AAC encoding and can be found in an exported MP4's audio
 * track by a Goertzel filter with near-certainty, whereas "some speech-like signal is present"
 * cannot distinguish this provider's output from the device voice's — and that distinction is
 * exactly what the regression test needs (see [VideoExporterInstrumentedTest]).
 *
 * [failFor] makes one sentence fail, so the coordinator's per-segment fallback to the device voice
 * is exercised in the same export rather than only asserted about in isolation.
 */
internal class ToneVoiceProvider(
    val toneHz: Double = TONE_HZ,
    private val failFor: (String) -> Boolean = { false },
    // Unique per instance: VideoExporter narrates through the app's real, persistent
    // NarrationStore, so a stable name would make a second export a pure cache hit and the
    // "provider was called" assertion would fail for the wrong reason.
    override val displayName: String = "Tone voice ${System.nanoTime()}",
) : NarrationVoiceProvider {
    val requested = mutableListOf<String>()
    val releaseCount = AtomicInteger(0)

    override suspend fun prepare(): Boolean = true

    override suspend fun synthesize(text: String, outFile: File): SynthesisResult {
        requested += text
        if (failFor(text)) return SynthesisResult.Failure("deliberate test failure")
        val durationMs = 600L + 25L * text.length
        writeTone(outFile, durationMs)
        return SynthesisResult.Success(outFile, durationMs)
    }

    override fun release() {
        releaseCount.incrementAndGet()
    }

    private fun writeTone(file: File, durationMs: Long) {
        val frames = (durationMs * SAMPLE_RATE / 1000L).toInt()
        val bytes = ByteBuffer.allocate(frames * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until frames) {
            bytes.putShort((AMPLITUDE * sin(2.0 * PI * toneHz * i / SAMPLE_RATE)).toInt().toShort())
        }
        file.outputStream().use { out ->
            out.write(WavUtil.buildWavHeader(frames * 2, SAMPLE_RATE, channels = 1, bitsPerSample = 16))
            out.write(bytes.array())
        }
    }

    companion object {
        const val SAMPLE_RATE = 24_000
        const val TONE_HZ = 1000.0
        const val AMPLITUDE = 12_000.0
    }
}

/** Decodes an MP4's AAC audio track back to PCM and looks for a tone in it. */
internal object ExportedAudioProbe {

    data class Pcm(val mono: ShortArray, val sampleRate: Int)

    /** Fully decodes the first audio track via `MediaCodec`, downmixed to mono 16-bit. */
    fun decodeAudioTrack(file: File): Pcm {
        val extractor = MediaExtractor()
        extractor.setDataSource(file.absolutePath)
        var trackIndex = -1
        var format: MediaFormat? = null
        for (i in 0 until extractor.trackCount) {
            val f = extractor.getTrackFormat(i)
            if ((f.getString(MediaFormat.KEY_MIME) ?: "").startsWith("audio/")) {
                trackIndex = i
                format = f
                break
            }
        }
        require(trackIndex >= 0 && format != null) { "no audio track in $file" }
        extractor.selectTrack(trackIndex)

        val decoder = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
        decoder.configure(format, null, null, 0)
        decoder.start()

        var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        var sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        val out = ArrayList<Short>(sampleRate * 20)
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var outputDone = false
        while (!outputDone) {
            if (!inputDone) {
                val inIndex = decoder.dequeueInputBuffer(10_000)
                if (inIndex >= 0) {
                    val buf = decoder.getInputBuffer(inIndex)!!
                    val size = extractor.readSampleData(buf, 0)
                    if (size < 0) {
                        decoder.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        decoder.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }
            val outIndex = decoder.dequeueOutputBuffer(info, 10_000)
            when {
                outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val f = decoder.outputFormat
                    channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    sampleRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                }
                outIndex >= 0 -> {
                    val buf = decoder.getOutputBuffer(outIndex)!!
                    buf.position(info.offset)
                    buf.limit(info.offset + info.size)
                    val shorts = buf.order(ByteOrder.nativeOrder()).asShortBuffer()
                    val frames = shorts.remaining() / channels
                    for (i in 0 until frames) {
                        var sum = 0
                        for (c in 0 until channels) sum += shorts.get()
                        out.add((sum / channels).toShort())
                    }
                    decoder.releaseOutputBuffer(outIndex, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                }
            }
        }
        decoder.stop()
        decoder.release()
        extractor.release()
        return Pcm(out.toShortArray(), sampleRate)
    }

    /**
     * Fraction of each 100 ms window's energy sitting at [toneHz] (Goertzel), and how many
     * windows are dominated by it. A pure tone scores ~1.0 per window; speech, noise or silence
     * scores near 0 (silent windows are skipped rather than counted either way).
     */
    fun toneWindows(pcm: Pcm, toneHz: Double, dominanceThreshold: Double = 0.5): ToneStats {
        val n = pcm.sampleRate / 10 // 100 ms
        val k = (n * toneHz / pcm.sampleRate)
        val w = 2.0 * PI * k / n
        val coeff = 2.0 * cos(w)
        var analysed = 0
        var dominated = 0
        var best = 0.0
        var start = 0
        while (start + n <= pcm.mono.size) {
            var s1 = 0.0
            var s2 = 0.0
            var energy = 0.0
            for (i in 0 until n) {
                val x = pcm.mono[start + i].toDouble()
                energy += x * x
                val s = x + coeff * s1 - s2
                s2 = s1
                s1 = s
            }
            val meanSquare = energy / n
            if (meanSquare > SILENCE_MEAN_SQUARE) {
                analysed++
                val power = s1 * s1 + s2 * s2 - coeff * s1 * s2
                val ratio = (2.0 * power / (n.toDouble() * n)) / meanSquare
                if (ratio > best) best = ratio
                if (ratio >= dominanceThreshold) dominated++
            }
            start += n
        }
        return ToneStats(analysed, dominated, best)
    }

    data class ToneStats(val nonSilentWindows: Int, val toneDominatedWindows: Int, val bestRatio: Double)

    /** ~-50 dBFS: below this a window is treated as silence (the muxer's own silence is exactly 0). */
    private const val SILENCE_MEAN_SQUARE = 100.0 * 100.0
}
