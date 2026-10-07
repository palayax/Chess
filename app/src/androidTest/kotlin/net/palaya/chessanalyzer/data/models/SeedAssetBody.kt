package net.palaya.chessanalyzer.data.models

import java.io.Closeable
import java.io.EOFException
import java.io.FileInputStream
import java.nio.ByteBuffer
import net.palaya.chessanalyzer.TestApp

/**
 * A [FaultHttpServer] body read straight out of one of the test APK's stored seed assets (D2d): the real
 * 98.5 MB net or 158 MB voice tar, served over the in-process server without copying it to disk or
 * holding it on the heap. Positional reads on the APK's file descriptor, so several connections may read
 * at once. `openFd` only works because the seed is stored uncompressed (`noCompress`), which is also
 * what the seed tests assert.
 */
class SeedAssetBody(path: String) : FaultHttpServer.Body, Closeable {
    private val afd = TestApp.testContext.assets.openFd(path)
    private val stream = FileInputStream(afd.fileDescriptor)
    private val channel = stream.channel

    override val size: Long = afd.length

    override fun read(offset: Long, into: ByteArray, length: Int) {
        require(offset >= 0 && offset + length <= size) { "read past the asset: $offset + $length > $size" }
        val buffer = ByteBuffer.wrap(into, 0, length)
        var position = afd.startOffset + offset
        while (buffer.hasRemaining()) {
            val n = channel.read(buffer, position)
            if (n < 0) throw EOFException("asset ended at ${position - afd.startOffset}")
            position += n
        }
    }

    override fun close() {
        runCatching { channel.close() }
        runCatching { afd.close() }
    }
}
