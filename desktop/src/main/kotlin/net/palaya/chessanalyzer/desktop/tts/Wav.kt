package net.palaya.chessanalyzer.desktop.tts

import java.io.ByteArrayOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

/**
 * PCM WAV reading, writing and measurement: a port of `app/.../video/WavUtil.kt` (already pure
 * JVM) plus the measurements the audio manifest records (design §3.4, §10).
 */
object Wav {

    data class Info(val sampleRate: Int, val channels: Int, val bitsPerSample: Int, val dataOffset: Long, val dataSize: Long) {
        val frames: Long get() = if (channels * (bitsPerSample / 8) == 0) 0 else dataSize / (channels * (bitsPerSample / 8))
        /** Exact, from the header: frames / rate. */
        val durationMs: Double get() = if (sampleRate == 0) 0.0 else frames * 1000.0 / sampleRate
    }

    /** Walks RIFF chunks to `fmt ` and `data`; null if the file is not 16-bit PCM WAV. */
    fun readHeader(file: Path): Info? {
        if (!Files.isRegularFile(file) || Files.size(file) < 44) return null
        RandomAccessFile(file.toFile(), "r").use { raf ->
            val riff = ByteArray(12)
            raf.readFully(riff)
            if (String(riff, 0, 4, Charsets.US_ASCII) != "RIFF" || String(riff, 8, 4, Charsets.US_ASCII) != "WAVE") return null
            var format = 0
            var sampleRate = 0
            var channels = 0
            var bits = 0
            var dataOffset = -1L
            var dataSize = -1L
            while (raf.filePointer + 8 <= raf.length()) {
                val h = ByteArray(8)
                raf.readFully(h)
                val id = String(h, 0, 4, Charsets.US_ASCII)
                val size = ByteBuffer.wrap(h, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL
                when (id) {
                    "fmt " -> {
                        val fmt = ByteArray(size.toInt().coerceAtMost(16))
                        raf.readFully(fmt)
                        val bb = ByteBuffer.wrap(fmt).order(ByteOrder.LITTLE_ENDIAN)
                        format = bb.short.toInt()
                        channels = bb.short.toInt()
                        sampleRate = bb.int
                        bb.int
                        bb.short
                        bits = bb.short.toInt()
                        if (size > 16) raf.seek(raf.filePointer + (size - 16))
                    }
                    "data" -> {
                        dataOffset = raf.filePointer
                        dataSize = minOf(size, raf.length() - dataOffset)
                        raf.seek(dataOffset + dataSize)
                    }
                    else -> raf.seek(raf.filePointer + size)
                }
                if (size % 2L == 1L && raf.filePointer < raf.length()) raf.seek(raf.filePointer + 1)
            }
            if (format != 1 || bits != 16 || sampleRate == 0 || dataOffset < 0) return null
            return Info(sampleRate, max(1, channels), bits, dataOffset, dataSize)
        }
    }

    /** 16-bit samples, downmixed to mono, at the file's own rate. */
    fun readMono16(file: Path): Pair<ShortArray, Int> {
        val info = readHeader(file) ?: throw IllegalArgumentException("not a 16-bit PCM WAV: $file")
        RandomAccessFile(file.toFile(), "r").use { raf ->
            raf.seek(info.dataOffset)
            val bytes = ByteArray(info.dataSize.toInt())
            raf.readFully(bytes)
            val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val n = bytes.size / (2 * info.channels)
            val mono = ShortArray(n)
            for (i in 0 until n) {
                var sum = 0
                repeat(info.channels) { sum += bb.short }
                mono[i] = (sum / info.channels).toShort()
            }
            return mono to info.sampleRate
        }
    }

    fun readMono16(file: Path, targetRate: Int): ShortArray {
        val (mono, rate) = readMono16(file)
        return resample(mono, rate, targetRate)
    }

    /** Linear interpolation; adequate for speech into a 48 kHz mix. */
    fun resample(input: ShortArray, fromRate: Int, toRate: Int): ShortArray {
        if (input.isEmpty() || fromRate == toRate) return input
        val outLength = ((input.size.toLong() * toRate) / fromRate).toInt().coerceAtLeast(1)
        val out = ShortArray(outLength)
        val ratio = fromRate.toDouble() / toRate
        for (i in 0 until outLength) {
            val src = i * ratio
            val i0 = src.toInt().coerceIn(0, input.size - 1)
            val i1 = (i0 + 1).coerceAtMost(input.size - 1)
            val f = src - i0
            out[i] = (input[i0] * (1 - f) + input[i1] * f).toInt().toShort()
        }
        return out
    }

    fun write(file: Path, samples: ShortArray, sampleRate: Int) {
        val data = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        samples.forEach { data.putShort(it) }
        val out = ByteArrayOutputStream(44 + samples.size * 2)
        out.write(header(samples.size * 2, sampleRate, 1, 16))
        out.write(data.array())
        Files.createDirectories(file.toAbsolutePath().parent)
        val tmp = file.resolveSibling(file.fileName.toString() + ".tmp")
        Files.write(tmp, out.toByteArray())
        Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
    }

    fun header(dataSize: Int, sampleRate: Int, channels: Int, bits: Int): ByteArray {
        val b = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray(Charsets.US_ASCII)); b.putInt(36 + dataSize)
        b.put("WAVE".toByteArray(Charsets.US_ASCII))
        b.put("fmt ".toByteArray(Charsets.US_ASCII)); b.putInt(16)
        b.putShort(1); b.putShort(channels.toShort()); b.putInt(sampleRate)
        b.putInt(sampleRate * channels * bits / 8); b.putShort((channels * bits / 8).toShort()); b.putShort(bits.toShort())
        b.put("data".toByteArray(Charsets.US_ASCII)); b.putInt(dataSize)
        return b.array()
    }

    // ---- measurement ---------------------------------------------------------------------

    /** Silence threshold for leading/trailing trim and the near-silent ratio (§10). */
    const val SILENCE_DBFS = -40.0
    const val WINDOW_MS = 20

    data class Stats(
        val durationMs: Double,
        val rmsDbfs: Double,
        val peakDbfs: Double,
        val leadingSilenceMs: Int,
        val trailingSilenceMs: Int,
        /** Fraction of 20 ms windows whose RMS is below [SILENCE_DBFS]. */
        val nearSilentRatio: Double,
        val clippedSamples: Int,
    )

    fun dbfs(linear: Double): Double = if (linear <= 0.0) -120.0 else 20.0 * log10(linear)

    fun measure(samples: ShortArray, sampleRate: Int): Stats {
        val n = samples.size
        if (n == 0) return Stats(0.0, -120.0, -120.0, 0, 0, 1.0, 0)
        var sumSq = 0.0
        var peak = 0
        var clipped = 0
        for (s in samples) {
            val v = s.toInt()
            sumSq += v.toDouble() * v
            val a = if (v < 0) -v else v
            if (a > peak) peak = a
            if (a >= 32767) clipped++
        }
        val win = max(1, sampleRate * WINDOW_MS / 1000)
        val windows = (n + win - 1) / win
        val loud = BooleanArray(windows)
        for (w in 0 until windows) {
            val from = w * win
            val to = minOf(n, from + win)
            var acc = 0.0
            for (i in from until to) acc += samples[i].toDouble() * samples[i]
            val rms = sqrt(acc / (to - from)) / 32768.0
            loud[w] = dbfs(rms) > SILENCE_DBFS
        }
        val first = loud.indexOfFirst { it }
        val last = loud.indexOfLast { it }
        val totalMs = n * 1000.0 / sampleRate
        val leading = if (first < 0) totalMs.toInt() else first * WINDOW_MS
        val trailing = if (last < 0) totalMs.toInt() else ((windows - 1 - last) * WINDOW_MS).coerceAtLeast(0)
        return Stats(
            durationMs = totalMs,
            rmsDbfs = round1(dbfs(sqrt(sumSq / n) / 32768.0)),
            peakDbfs = round1(dbfs(peak / 32768.0)),
            leadingSilenceMs = leading,
            trailingSilenceMs = trailing,
            nearSilentRatio = Math.round(loud.count { !it }.toDouble() / windows * 1000.0) / 1000.0,
            clippedSamples = clipped,
        )
    }

    private fun round1(x: Double) = Math.round(x * 10.0) / 10.0
}
