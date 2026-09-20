package net.palaya.chessanalyzer.video

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Minimal PCM-WAV reader/writer. `TextToSpeech.synthesizeToFile` writes a plain RIFF/WAVE
 * container (mono/stereo 16-bit PCM on every device this has been checked against), and this is
 * the only WAV shape [VideoExporter] ever has to deal with — reading and re-encoding through
 * `MediaExtractor`/`MediaCodec` for a handful of short files would be a lot of ceremony for no
 * benefit, so a hand-rolled RIFF walk is enough.
 */
object WavUtil {

    data class WavInfo(
        val sampleRate: Int,
        val channels: Int,
        val bitsPerSample: Int,
        val dataOffset: Long,
        val dataSize: Long,
    ) {
        val durationMs: Long
            get() {
                val bytesPerSecond = sampleRate.toLong() * channels * (bitsPerSample / 8)
                return if (bytesPerSecond == 0L) 0L else (dataSize * 1000L) / bytesPerSecond
            }
    }

    /** Walks RIFF chunks to find `fmt ` and `data`; returns null if the file isn't a PCM WAV. */
    fun readHeader(file: File): WavInfo? {
        if (!file.isFile || file.length() < 44) return null
        RandomAccessFile(file, "r").use { raf ->
            val riff = ByteArray(12)
            raf.readFully(riff)
            val riffTag = String(riff, 0, 4, Charsets.US_ASCII)
            val waveTag = String(riff, 8, 4, Charsets.US_ASCII)
            if (riffTag != "RIFF" || waveTag != "WAVE") return null

            var sampleRate = 0
            var channels = 0
            var bitsPerSample = 0
            var dataOffset = -1L
            var dataSize = -1L

            while (raf.filePointer + 8 <= raf.length()) {
                val chunkHeader = ByteArray(8)
                raf.readFully(chunkHeader)
                val id = String(chunkHeader, 0, 4, Charsets.US_ASCII)
                val size = ByteBuffer.wrap(chunkHeader, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL
                when (id) {
                    "fmt " -> {
                        val fmt = ByteArray(size.toInt().coerceAtMost(16))
                        raf.readFully(fmt)
                        val bb = ByteBuffer.wrap(fmt).order(ByteOrder.LITTLE_ENDIAN)
                        bb.getShort() // audio format
                        channels = bb.getShort().toInt()
                        sampleRate = bb.getInt()
                        bb.getInt() // byte rate
                        bb.getShort() // block align
                        bitsPerSample = bb.getShort().toInt()
                        if (size > 16) raf.seek(raf.filePointer + (size - 16))
                    }
                    "data" -> {
                        dataOffset = raf.filePointer
                        dataSize = size
                        raf.seek(raf.filePointer + size)
                    }
                    else -> raf.seek(raf.filePointer + size)
                }
                // Chunks are word-aligned.
                if (size % 2L == 1L && raf.filePointer < raf.length()) raf.seek(raf.filePointer + 1)
            }
            if (sampleRate == 0 || dataOffset < 0) return null
            return WavInfo(sampleRate, channels.coerceAtLeast(1), bitsPerSample.coerceAtLeast(16), dataOffset, dataSize.coerceAtLeast(0))
        }
    }

    /** Reads the PCM payload as 16-bit mono samples, downmixing stereo and resampling if needed. */
    fun readAsMono16(file: File, targetSampleRate: Int): ShortArray {
        val info = readHeader(file) ?: return ShortArray(0)
        RandomAccessFile(file, "r").use { raf ->
            raf.seek(info.dataOffset)
            val bytes = ByteArray(info.dataSize.toInt())
            raf.readFully(bytes)
            val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

            val frameCount = if (info.channels > 0) bytes.size / (2 * info.channels) else 0
            val mono = ShortArray(frameCount)
            for (i in 0 until frameCount) {
                var sum = 0
                for (c in 0 until info.channels) sum += bb.short
                mono[i] = (sum / info.channels).toShort()
            }
            return if (info.sampleRate == targetSampleRate) mono else resample(mono, info.sampleRate, targetSampleRate)
        }
    }

    /** Linear-interpolation resample — adequate for speech, and this app links no DSP library. */
    private fun resample(input: ShortArray, fromRate: Int, toRate: Int): ShortArray {
        if (input.isEmpty() || fromRate == toRate) return input
        val outLength = ((input.size.toLong() * toRate) / fromRate).toInt().coerceAtLeast(1)
        val out = ShortArray(outLength)
        val ratio = fromRate.toDouble() / toRate.toDouble()
        for (i in 0 until outLength) {
            val srcPos = i * ratio
            val idx0 = srcPos.toInt().coerceIn(0, input.size - 1)
            val idx1 = (idx0 + 1).coerceAtMost(input.size - 1)
            val frac = srcPos - idx0
            out[i] = (input[idx0] * (1 - frac) + input[idx1] * frac).toInt().toShort()
        }
        return out
    }

    /** Writes a 16-bit mono silent WAV of [durationMs] at [sampleRate] — the documented TTS fallback. */
    fun writeSilentWav(file: File, durationMs: Long, sampleRate: Int = 24000) {
        val frameCount = ((durationMs * sampleRate) / 1000L).toInt().coerceAtLeast(1)
        val dataSize = frameCount * 2
        file.outputStream().use { out ->
            out.write(buildWavHeader(dataSize, sampleRate, channels = 1, bitsPerSample = 16))
            out.write(ByteArray(dataSize))
        }
    }

    fun buildWavHeader(dataSize: Int, sampleRate: Int, channels: Int, bitsPerSample: Int): ByteArray {
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8
        val buffer = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put("RIFF".toByteArray(Charsets.US_ASCII))
        buffer.putInt(36 + dataSize)
        buffer.put("WAVE".toByteArray(Charsets.US_ASCII))
        buffer.put("fmt ".toByteArray(Charsets.US_ASCII))
        buffer.putInt(16)
        buffer.putShort(1) // PCM
        buffer.putShort(channels.toShort())
        buffer.putInt(sampleRate)
        buffer.putInt(byteRate)
        buffer.putShort(blockAlign.toShort())
        buffer.putShort(bitsPerSample.toShort())
        buffer.put("data".toByteArray(Charsets.US_ASCII))
        buffer.putInt(dataSize)
        return buffer.array()
    }
}
