package net.palaya.chessanalyzer.data.models

import java.io.BufferedInputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A tiny HTTP/1.1 GET server on 127.0.0.1 that serves byte arrays and misbehaves on request
 * (docs/MODEL_DOWNLOAD_DESIGN.md §6.1). Plain `java.net.ServerSocket`, no dependencies, so the same
 * class runs in the host tests and, in-process, in the instrumented tests (it lives in `src/sharedTest`).
 *
 * Every response closes its connection. Range requests (`bytes=N-`) are honoured unless a fault says
 * otherwise. Faults are queued per path and consumed one per request ([fault]), or applied to every
 * request ([alwaysFault]); every request is recorded in [requests].
 *
 * A body is a byte array ([serve]) or any [Body] ([serveBody]): the instrumented tests serve the real
 * 98.5 MB net and 158 MB voice straight out of the test APK's stored assets that way (D2d), without
 * copying them to disk or holding them on the heap.
 */
class FaultHttpServer : Closeable {

    /** What to do wrong with one response. Byte counts are of the response BODY unless stated. */
    sealed interface Fault {
        /** Correct headers (full Content-Length), then a clean close after [bytes] body bytes. */
        data class TruncateAfter(val bytes: Long) : Fault

        /** Correct headers, then a connection reset (RST) after [bytes] body bytes. */
        data class DropAfter(val bytes: Long) : Fault

        /** Flip the byte at absolute file offset [offset] (the download then fails its SHA-256). */
        data class CorruptByteAt(val offset: Long) : Fault

        /**
         * Correct headers (full Content-Length), but the bytes at these absolute file offsets are never sent
         * and the connection then closes cleanly: the body ends short by that many bytes, with the bytes
         * after each gap shifted forward. The Android emulator's user-mode network did exactly this to the
         * host test server (R8: single bytes on 1440-byte segment boundaries in the last ~128 KB).
         */
        data class LoseBytesAt(val offsets: Set<Long>) : Fault

        /** Answer [code] with an empty body (and Retry-After when given). */
        data class Status(val code: Int, val retryAfterSeconds: Int? = null) : Fault

        /** Send the body at about [bytesPerSecond]. */
        data class Slow(val bytesPerSecond: Long) : Fault

        /** Send [afterBytes] body bytes, then go silent for [millis] (a read timeout), then close. */
        data class Stall(val afterBytes: Long, val millis: Long) : Fault

        /** Redirect to [location] (an absolute URL, e.g. on a second server) with [code]. */
        data class RedirectTo(val location: String, val code: Int = 302) : Fault

        /** Redirect to a plain-http URL on a non-loopback host (the client must refuse it). */
        data object RedirectToHttp : Fault

        /** Ignore Range: answer 200 with the whole file. */
        data object IgnoreRange : Fault

        /** Claim [claimedTotal] as the size (Content-Length on 200, the Content-Range total on 206). */
        data class WrongTotalSize(val claimedTotal: Long) : Fault

        /** Redirect to the same path, forever (with [alwaysFault]). */
        data object RedirectLoop : Fault
    }

    /** A response body read at absolute offsets; [read] may be called from several connections at once. */
    interface Body {
        val size: Long

        /** Fills `into[0 until length]` with the bytes at [offset]. */
        fun read(offset: Long, into: ByteArray, length: Int)
    }

    private class ArrayBody(private val bytes: ByteArray) : Body {
        override val size: Long get() = bytes.size.toLong()
        override fun read(offset: Long, into: ByteArray, length: Int) {
            System.arraycopy(bytes, offset.toInt(), into, 0, length)
        }
    }

    data class RecordedRequest(val path: String, val headers: Map<String, String>) {
        val range: String? get() = headers["range"]
    }

    private val socket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    private val files = ConcurrentHashMap<String, Body>()
    private val encodings = ConcurrentHashMap<String, String>()
    private val queued = ConcurrentHashMap<String, MutableList<Fault>>()
    private val always = ConcurrentHashMap<String, Fault>()
    private val closed = AtomicBoolean(false)
    private val connections = Collections.synchronizedSet(HashSet<Socket>())

    val requests: MutableList<RecordedRequest> = Collections.synchronizedList(ArrayList())

    val port: Int get() = socket.localPort

    /** `http://127.0.0.1:<port>/`. */
    val baseUrl: String get() = "http://127.0.0.1:$port/"

    fun url(path: String): String = baseUrl + path.trimStart('/')

    private val acceptor = Thread({ acceptLoop() }, "FaultHttpServer-$port").apply {
        isDaemon = true
        start()
    }

    fun serve(path: String, body: ByteArray): FaultHttpServer = serveBody(path, ArrayBody(body))

    fun serveBody(path: String, body: Body): FaultHttpServer = apply {
        files["/" + path.trimStart('/')] = body
        encodings.remove("/" + path.trimStart('/'))
    }

    /**
     * Serves [plain] gzip-compressed with `Content-Encoding: gzip` (A4: GitHub's release lists come that way).
     * The Content-Length is the compressed size, as a real server's is.
     */
    fun serveGzipped(path: String, plain: ByteArray): FaultHttpServer {
        val bytes = java.io.ByteArrayOutputStream().also { out ->
            java.util.zip.GZIPOutputStream(out).use { it.write(plain) }
        }.toByteArray()
        serveBody(path, ArrayBody(bytes))
        encodings["/" + path.trimStart('/')] = "gzip"
        return this
    }

    /** Queues [fault] for the next request to [path] (each queued fault is used once, in order). */
    fun fault(path: String, fault: Fault): FaultHttpServer = apply {
        queued.getOrPut("/" + path.trimStart('/')) { Collections.synchronizedList(ArrayList()) }.add(fault)
    }

    /** Applies [fault] to every request to [path] until [clearFaults]. */
    fun alwaysFault(path: String, fault: Fault): FaultHttpServer = apply { always["/" + path.trimStart('/')] = fault }

    fun clearFaults() {
        queued.clear()
        always.clear()
    }

    fun requestsFor(path: String): List<RecordedRequest> = synchronized(requests) {
        requests.filter { it.path == "/" + path.trimStart('/') }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            runCatching { socket.close() }
            synchronized(connections) { connections.forEach { runCatching { it.close() } } }
        }
    }

    private fun acceptLoop() {
        while (!closed.get()) {
            val client = try {
                socket.accept()
            } catch (e: IOException) {
                break
            }
            connections += client
            Thread({ handle(client) }, "FaultHttpServer-conn").apply { isDaemon = true; start() }
        }
    }

    private fun handle(client: Socket) {
        try {
            client.use { s ->
                val input = BufferedInputStream(s.getInputStream())
                val requestLine = readLine(input) ?: return
                val headers = LinkedHashMap<String, String>()
                while (true) {
                    val line = readLine(input) ?: break
                    if (line.isEmpty()) break
                    val i = line.indexOf(':')
                    if (i > 0) headers[line.substring(0, i).trim().lowercase()] = line.substring(i + 1).trim()
                }
                val parts = requestLine.split(" ")
                val path = parts.getOrNull(1)?.substringBefore('?') ?: "/"
                requests += RecordedRequest(path, headers)
                respond(s, s.getOutputStream(), path, headers)
            }
        } catch (e: IOException) {
            // The client went away, or a fault closed the socket: nothing to do.
        } finally {
            connections -= client
        }
    }

    private fun nextFault(path: String): Fault? {
        always[path]?.let { return it }
        val list = queued[path] ?: return null
        synchronized(list) { return if (list.isEmpty()) null else list.removeAt(0) }
    }

    private fun respond(socket: Socket, out: OutputStream, path: String, headers: Map<String, String>) {
        val body = files[path]
        if (body == null) {
            writeHead(out, 404, "Not Found", mapOf("Content-Length" to "0"))
            return
        }
        when (val fault = nextFault(path)) {
            is Fault.Status -> {
                val extra = LinkedHashMap<String, String>()
                fault.retryAfterSeconds?.let { extra["Retry-After"] = it.toString() }
                extra["Content-Length"] = "0"
                writeHead(out, fault.code, "Fault", extra)
                return
            }
            is Fault.RedirectTo -> {
                writeHead(out, fault.code, "Redirect", mapOf("Location" to fault.location, "Content-Length" to "0"))
                return
            }
            Fault.RedirectToHttp -> {
                writeHead(out, 302, "Found", mapOf("Location" to "http://example.invalid$path", "Content-Length" to "0"))
                return
            }
            Fault.RedirectLoop -> {
                writeHead(out, 302, "Found", mapOf("Location" to url(path), "Content-Length" to "0"))
                return
            }
            else -> sendBody(socket, out, body, headers["range"], fault, encodings[path])
        }
    }

    private fun sendBody(socket: Socket, out: OutputStream, body: Body, range: String?, fault: Fault?, encoding: String? = null) {
        val size = body.size
        var start = 0L
        var partial = false
        if (range != null && fault != Fault.IgnoreRange) {
            val m = Regex("""bytes=(\d+)-""").matchEntire(range.trim())
            if (m != null) {
                start = m.groupValues[1].toLong()
                if (start >= size) {
                    writeHead(out, 416, "Range Not Satisfiable", mapOf("Content-Range" to "bytes */$size", "Content-Length" to "0"))
                    return
                }
                partial = true
            }
        }
        val length = size - start
        val claimed = (fault as? Fault.WrongTotalSize)?.claimedTotal
        val head = LinkedHashMap<String, String>()
        head["Content-Type"] = "application/octet-stream"
        if (encoding != null) head["Content-Encoding"] = encoding
        if (partial) {
            head["Content-Length"] = length.toString()
            head["Content-Range"] = "bytes $start-${size - 1}/${claimed ?: size}"
        } else {
            head["Content-Length"] = (claimed ?: length).toString()
        }
        head["Accept-Ranges"] = "bytes"
        writeHead(out, if (partial) 206 else 200, if (partial) "Partial Content" else "OK", head)

        val stopAfter = when (fault) {
            is Fault.TruncateAfter -> fault.bytes
            is Fault.DropAfter -> fault.bytes
            is Fault.Stall -> fault.afterBytes
            else -> null
        }
        val rate = (fault as? Fault.Slow)?.bytesPerSecond
        val corruptAt = (fault as? Fault.CorruptByteAt)?.offset
        val lose = (fault as? Fault.LoseBytesAt)?.offsets.orEmpty()
        val began = System.nanoTime()
        var sent = 0L
        val chunk = 16 * 1024
        while (sent < length) {
            var n = minOf(chunk.toLong(), length - sent)
            if (stopAfter != null) n = minOf(n, stopAfter - sent)
            if (n <= 0) break
            val bytes = ByteArray(n.toInt())
            body.read(start + sent, bytes, n.toInt())
            if (corruptAt != null && corruptAt in (start + sent) until (start + sent + n)) {
                val i = (corruptAt - start - sent).toInt()
                bytes[i] = (bytes[i].toInt() xor 0xFF).toByte()
            }
            val chunkStart = start + sent
            val toSend = if (lose.none { it in chunkStart until chunkStart + n }) bytes
            else bytes.filterIndexed { i, _ -> (chunkStart + i) !in lose }.toByteArray()
            try {
                out.write(toSend)
                out.flush()
            } catch (e: SocketException) {
                return
            }
            sent += n
            if (rate != null) {
                val due = sent * 1_000_000_000L / rate
                val ahead = (due - (System.nanoTime() - began)) / 1_000_000L
                if (ahead > 0) Thread.sleep(ahead)
            }
        }
        when (fault) {
            is Fault.DropAfter -> {
                socket.setSoLinger(true, 0) // RST instead of FIN
                socket.close()
            }
            is Fault.Stall -> Thread.sleep(fault.millis)
            else -> Unit
        }
    }

    private fun writeHead(out: OutputStream, code: Int, reason: String, headers: Map<String, String>) {
        val sb = StringBuilder("HTTP/1.1 $code $reason\r\n")
        headers.forEach { (k, v) -> sb.append(k).append(": ").append(v).append("\r\n") }
        sb.append("Connection: close\r\n\r\n")
        out.write(sb.toString().toByteArray(Charsets.ISO_8859_1))
        out.flush()
    }

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val c = input.read()
            if (c == -1) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(c.toChar())
        }
    }
}
