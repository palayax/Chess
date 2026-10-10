package net.palaya.chessanalyzer.data.models

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** One file to fetch: where from, and what it must be (size + full SHA-256, from the build-time pins). */
data class ModelFileSpec(
    val url: String,
    val fileName: String,
    val sizeBytes: Long,
    val sha256: String,
)

/** Callbacks from a running [ModelDownloader.download]; called on the download's thread, must not block. */
interface DownloadListener {
    fun onState(state: DownloadState) {}

    /** Bytes of the file now in the part file (including a resumed prefix), out of [total]. */
    fun onBytes(done: Long, total: Long) {}

    companion object {
        val NONE = object : DownloadListener {}
    }
}

/** How [ModelDownloader.download] ended. A user pause is not a result: it is a [CancellationException]. */
sealed interface DownloadResult {
    /** The part file holds exactly the pinned bytes. */
    data class Verified(val file: File) : DownloadResult

    data class Paused(val reason: PauseReason) : DownloadResult

    data class Failed(val reason: FailureReason, val detail: String? = null) : DownloadResult
}

/** Why [ModelDownloader.fetchSmall] got nothing usable. */
enum class SmallFetchFailure {
    /** No connection, a timeout, or the answer broke off. */
    NETWORK,

    /** 404 / 410. */
    NOT_FOUND,

    /** Any other status, or a redirect the client cannot follow. */
    SERVER,

    /** Not https (and not the debug-only loopback exception). */
    INSECURE,

    /** Longer than the caller's cap. */
    TOO_LARGE,

    /**
     * 403 / 429: a host that rate-limits (GitHub's unauthenticated API allows 60 requests an hour per address)
     * is asking us to wait. Not an error in the app and not worth a retry loop; the caller says so.
     */
    RATE_LIMITED,
}

/** The result of [ModelDownloader.fetchSmall]. */
sealed interface SmallFetch {
    class Ok(val bytes: ByteArray) : SmallFetch
    data class Failed(val reason: SmallFetchFailure, val detail: String) : SmallFetch
}

/**
 * Downloads one model file into a `.part` file, resumably, and verifies it (docs/MODEL_DOWNLOAD_DESIGN.md
 * §2.2). **The only class in the app that opens a network connection** (`NetworkCallSitesTest`).
 *
 * - `HttpURLConnection`, GET only, https only. The one exception, [allowCleartextLoopback] (true only in
 *   a debug build), lets http reach 10.0.2.2 / 127.0.0.1 / localhost: the host-side test server and the
 *   in-process [FaultHttpServer]. The debug network security config allows the same three hosts.
 * - Redirects are followed by hand (at most [MAX_REDIRECTS]); every hop must pass the same https rule;
 *   the redirect is resolved afresh on every attempt (GitHub's CDN URLs are short-lived and signed), and
 *   only its host is ever logged.
 * - Resume: an existing part is re-hashed into the digest and continued with `Range: bytes=<n>-`. A server
 *   that ignores the range (200) restarts the file from 0; a 416 deletes the part and restarts.
 * - Every answer is checked against the pin: Content-Length / Content-Range total must equal the size,
 *   and at the end size + SHA-256 must match ([DownloadStateMachine] decides what a mismatch means).
 * - Cancellation is checked per 256 KB chunk. Cancelling the coroutine is "Pause": the part stays and a
 *   [CancellationException] propagates. Deleting the part on Cancel is the caller's job (`ModelSetup`).
 *
 * The transitions (backoff, retry limits, the one automatic restart after a bad hash) are
 * [DownloadStateMachine]'s; this class only performs them. [sleep], the timeouts and [openConnection]
 * are injectable so the host tests run the whole fault matrix in seconds.
 */
class ModelDownloader(
    private val userAgent: String,
    private val allowCleartextLoopback: Boolean,
    private val connectTimeoutMs: Int = 15_000,
    private val readTimeoutMs: Int = 30_000,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
    /** One line per attempt, redirect host, failure and result. Never a full URL. */
    private val log: (String) -> Unit = {},
    private val openConnection: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection },
) {

    companion object {
        const val MAX_REDIRECTS = 5
        const val MAX_FILE_BYTES = 1L shl 30
        const val CHUNK_BYTES = 256 * 1024
        val LOOPBACK_HOSTS = setOf("10.0.2.2", "127.0.0.1", "localhost")

        /** An `ENOSPC` anywhere in the cause chain: the disk filled up mid-write. */
        fun isOutOfSpace(e: Throwable): Boolean =
            generateSequence(e) { it.cause }.any {
                val m = it.message.orEmpty()
                m.contains("ENOSPC") || m.contains("No space left on device", ignoreCase = true)
            }

        /** The host of [url] for the log, or "?" — never the path or query of a signed URL. */
        fun hostOf(url: String): String = runCatching { URL(url).host }.getOrNull()?.ifEmpty { null } ?: "?"
    }

    /** True when [url] may be fetched: https, or http to a loopback test host in a debug build. */
    fun isAllowed(url: String): Boolean {
        val u = runCatching { URL(url) }.getOrNull() ?: return false
        return when (u.protocol.lowercase()) {
            "https" -> u.host.isNotEmpty()
            "http" -> allowCleartextLoopback && u.host.lowercase() in LOOPBACK_HOSTS
            else -> false
        }
    }

    /**
     * Fetches [spec] into [partFile] until it is verified, paused by repeated failures, or failed.
     * Throws [CancellationException] when the calling coroutine is cancelled (the part is kept).
     */
    suspend fun download(
        spec: ModelFileSpec,
        partFile: File,
        listener: DownloadListener = DownloadListener.NONE,
    ): DownloadResult = withContext(Dispatchers.IO) {
        val machine = DownloadStateMachine()
        fun go(event: DownloadEvent): DownloadState {
            val t = machine.on(event)
            if (t.deletePart) partFile.delete()
            listener.onState(t.state)
            return t.state
        }

        if (spec.sizeBytes <= 0 || spec.sizeBytes > MAX_FILE_BYTES) {
            go(DownloadEvent.Fatal(FailureReason.SIZE_MISMATCH))
            return@withContext DownloadResult.Failed(FailureReason.SIZE_MISMATCH, "refused: ${spec.sizeBytes} bytes")
        }
        if (!isAllowed(spec.url)) {
            go(DownloadEvent.Fatal(FailureReason.INSECURE))
            log("${spec.fileName}: refused, not an https URL (host ${hostOf(spec.url)})")
            return@withContext DownloadResult.Failed(FailureReason.INSECURE, "not https")
        }
        partFile.parentFile?.mkdirs()

        // A part that is already complete (the process died while the store was installing it) is
        // reused when its hash matches, instead of being fetched again.
        if (partFile.isFile && partFile.length() == spec.sizeBytes) {
            if (sha256Of(partFile) == spec.sha256) {
                log("${spec.fileName}: the part file is already complete and verified")
                listener.onBytes(spec.sizeBytes, spec.sizeBytes)
                go(DownloadEvent.Start)
                go(DownloadEvent.Connected)
                go(DownloadEvent.EndOfFile(hashOk = true))
                return@withContext DownloadResult.Verified(partFile)
            }
            partFile.delete()
        }

        var lastDetail: String? = null
        try {
            go(DownloadEvent.Start)
            while (true) {
                when (val s = machine.state) {
                    is DownloadState.Connecting -> when (val r = attempt(spec, partFile, listener) { go(DownloadEvent.Connected) }) {
                        is Attempt.Complete -> {
                            if (!r.hashOk) log("${spec.fileName}: SHA-256 mismatch after ${spec.sizeBytes} bytes")
                            go(DownloadEvent.EndOfFile(r.hashOk))
                        }
                        is Attempt.Transient -> {
                            lastDetail = r.detail
                            val next = go(DownloadEvent.TransientError(r.serverError, r.retryAfterMs))
                            log("${spec.fileName}: transient failure (${r.detail}) -> $next")
                        }
                        is Attempt.Fatal -> {
                            log("${spec.fileName}: failed, ${r.reason} (${r.detail})")
                            go(DownloadEvent.Fatal(r.reason))
                            return@withContext DownloadResult.Failed(r.reason, r.detail)
                        }
                    }
                    is DownloadState.Backoff -> {
                        sleep(s.delayMs)
                        go(DownloadEvent.BackoffElapsed)
                    }
                    is DownloadState.HashFailed -> {
                        log("${spec.fileName}: wrong hash ${s.hashFailures} time(s), starting the file again")
                        go(DownloadEvent.RetryAfterHashFailure)
                    }
                    DownloadState.Verified -> {
                        log("${spec.fileName}: verified (${spec.sizeBytes} bytes, SHA-256 matches the pin)")
                        return@withContext DownloadResult.Verified(partFile)
                    }
                    is DownloadState.Paused -> {
                        log("${spec.fileName}: paused, ${s.reason} after ${DownloadStateMachine.MAX_TRANSIENT_FAILURES} failures (last: $lastDetail)")
                        return@withContext DownloadResult.Paused(s.reason)
                    }
                    is DownloadState.Failed -> {
                        log("${spec.fileName}: failed, ${s.reason}")
                        return@withContext DownloadResult.Failed(s.reason, lastDetail)
                    }
                    else -> error("unexpected download state $s")
                }
            }
            @Suppress("UNREACHABLE_CODE")
            error("unreachable")
        } catch (e: CancellationException) {
            if (!machine.state.isTerminal && machine.state !is DownloadState.Paused) go(DownloadEvent.UserPause)
            log("${spec.fileName}: paused by the user at ${partFile.length()} of ${spec.sizeBytes} bytes")
            throw e
        }
    }

    /**
     * One small GET into memory (the update manifest and its signature, D2e): a single attempt (the user's
     * tap is the retry), the same https / redirect / timeout / User-Agent rules as [download], no Range,
     * and at most [maxBytes] (a longer body is refused, not truncated). Never logs more than the host.
     *
     * [acceptGzip] (A4, the upstream version check): asks for a gzip body and inflates it here; [maxBytes] then
     * caps the INFLATED size (a gzip bomb is refused as TOO_LARGE, not unpacked), and a declared Content-Length is
     * checked against the bytes that actually came over the wire. GitHub's release lists are 0.5 to 1 MB of JSON and
     * about a tenth of that compressed. Off by default: the manifest and its signature ask for identity.
     */
    suspend fun fetchSmall(url: String, maxBytes: Int, acceptGzip: Boolean = false): SmallFetch = withContext(Dispatchers.IO) {
        val name = url.substringAfterLast('/')
        if (!isAllowed(url)) {
            log("$name: refused, not an https URL (host ${hostOf(url)})")
            return@withContext SmallFetch.Failed(SmallFetchFailure.INSECURE, "not https")
        }
        var current = url
        var hops = 0
        while (true) {
            coroutineContext.ensureActive()
            if (!isAllowed(current)) return@withContext SmallFetch.Failed(SmallFetchFailure.INSECURE, "redirect to a non-https URL (host ${hostOf(current)})")
            val conn = openConnection(URL(current))
            try {
                conn.instanceFollowRedirects = false
                conn.connectTimeout = connectTimeoutMs
                conn.readTimeout = readTimeoutMs
                conn.useCaches = false
                conn.setRequestProperty("User-Agent", userAgent)
                conn.setRequestProperty("Accept-Encoding", if (acceptGzip) "gzip" else "identity")
                val code = try {
                    conn.responseCode
                } catch (e: IOException) {
                    log("$name: no answer from host ${hostOf(current)} (${e.javaClass.simpleName})")
                    return@withContext SmallFetch.Failed(SmallFetchFailure.NETWORK, "connect to ${hostOf(current)}: ${e.javaClass.simpleName}")
                }
                when (code) {
                    301, 302, 303, 307, 308 -> {
                        val location = conn.getHeaderField("Location")
                        hops++
                        if (location.isNullOrBlank() || hops > MAX_REDIRECTS) {
                            return@withContext SmallFetch.Failed(SmallFetchFailure.SERVER, "bad redirect $code")
                        }
                        val next = runCatching { URL(URL(current), location).toString() }.getOrNull()
                            ?: return@withContext SmallFetch.Failed(SmallFetchFailure.SERVER, "unreadable redirect")
                        log("$name: redirect $code to host ${hostOf(next)}")
                        current = next
                        continue
                    }
                    200 -> Unit
                    404, 410 -> {
                        log("$name: HTTP $code from host ${hostOf(current)}")
                        return@withContext SmallFetch.Failed(SmallFetchFailure.NOT_FOUND, "HTTP $code")
                    }
                    403, 429 -> {
                        log("$name: HTTP $code from host ${hostOf(current)} (rate limited?)")
                        return@withContext SmallFetch.Failed(SmallFetchFailure.RATE_LIMITED, "HTTP $code")
                    }
                    else -> {
                        log("$name: HTTP $code from host ${hostOf(current)}")
                        return@withContext SmallFetch.Failed(SmallFetchFailure.SERVER, "HTTP $code")
                    }
                }
                val declared = conn.getHeaderField("Content-Length")?.toLongOrNull()
                if (declared != null && declared > maxBytes) {
                    return@withContext SmallFetch.Failed(SmallFetchFailure.TOO_LARGE, "Content-Length $declared > $maxBytes")
                }
                val out = java.io.ByteArrayOutputStream()
                var raw: CountingInputStream? = null
                try {
                    raw = CountingInputStream(conn.inputStream)
                    val body: java.io.InputStream =
                        if (acceptGzip && conn.contentEncoding.equals("gzip", ignoreCase = true)) java.util.zip.GZIPInputStream(raw) else raw
                    body.use { input ->
                        val buffer = ByteArray(8 * 1024)
                        while (true) {
                            coroutineContext.ensureActive()
                            val n = input.read(buffer)
                            if (n == -1) break
                            out.write(buffer, 0, n)
                            if (out.size() > maxBytes) {
                                return@withContext SmallFetch.Failed(SmallFetchFailure.TOO_LARGE, "more than $maxBytes bytes")
                            }
                        }
                    }
                } catch (e: IOException) {
                    log("$name: the answer from host ${hostOf(current)} broke off (${e.javaClass.simpleName})")
                    return@withContext SmallFetch.Failed(SmallFetchFailure.NETWORK, "${e.javaClass.simpleName} while reading")
                }
                // Compared with what came over the wire (the compressed size when gzip was inflated).
                val received = raw?.count ?: out.size().toLong()
                if (declared != null && declared != received) {
                    return@withContext SmallFetch.Failed(SmallFetchFailure.NETWORK, "body ended at $received of $declared bytes")
                }
                log("$name: ${out.size()} bytes from host ${hostOf(current)}")
                return@withContext SmallFetch.Ok(out.toByteArray())
            } finally {
                conn.disconnect()
            }
        }
        @Suppress("UNREACHABLE_CODE")
        error("unreachable")
    }

    private sealed interface Attempt {
        data class Complete(val hashOk: Boolean) : Attempt
        data class Transient(val detail: String, val serverError: Boolean = false, val retryAfterMs: Long? = null) : Attempt
        data class Fatal(val reason: FailureReason, val detail: String) : Attempt
    }

    /** One connection: open (following redirects), check the answer, stream to the end or to an error. */
    private suspend fun attempt(
        spec: ModelFileSpec,
        partFile: File,
        listener: DownloadListener,
        onConnected: () -> Unit,
    ): Attempt {
        val digest = MessageDigest.getInstance("SHA-256")
        var offset = 0L
        if (partFile.isFile) {
            val length = partFile.length()
            if (length in 1 until spec.sizeBytes) {
                hashPrefix(partFile, length, digest)
                offset = length
            } else {
                partFile.delete()
            }
        }

        var url = spec.url
        var hops = 0
        var conn: HttpURLConnection
        var code: Int
        while (true) {
            if (!isAllowed(url)) return Attempt.Fatal(FailureReason.INSECURE, "redirect to a non-https URL (host ${hostOf(url)})")
            conn = openConnection(URL(url))
            conn.instanceFollowRedirects = false
            conn.connectTimeout = connectTimeoutMs
            conn.readTimeout = readTimeoutMs
            conn.useCaches = false
            conn.setRequestProperty("User-Agent", userAgent)
            conn.setRequestProperty("Accept-Encoding", "identity")
            if (offset > 0) conn.setRequestProperty("Range", "bytes=$offset-")
            code = try {
                conn.responseCode
            } catch (e: IOException) {
                conn.disconnect()
                return if (isOutOfSpace(e)) Attempt.Fatal(FailureReason.INSUFFICIENT_STORAGE, "ENOSPC")
                else Attempt.Transient("connect to ${hostOf(url)}: ${e.javaClass.simpleName}")
            }
            if (code in setOf(301, 302, 303, 307, 308)) {
                val location = conn.getHeaderField("Location")
                conn.disconnect()
                if (location.isNullOrBlank()) return Attempt.Fatal(FailureReason.SERVER, "redirect $code without a Location")
                hops++
                if (hops > MAX_REDIRECTS) return Attempt.Fatal(FailureReason.SERVER, "more than $MAX_REDIRECTS redirects")
                val next = runCatching { URL(URL(url), location).toString() }.getOrNull()
                    ?: return Attempt.Fatal(FailureReason.SERVER, "unreadable redirect")
                log("${spec.fileName}: redirect $code to host ${hostOf(next)}")
                url = next
                continue
            }
            break
        }

        try {
            when (code) {
                206 -> {
                    val range = conn.getHeaderField("Content-Range")
                    val m = range?.let { Regex("""bytes\s+(\d+)-(\d+)/(\d+)""").find(it) }
                        ?: return Attempt.Fatal(FailureReason.SERVER, "206 without a usable Content-Range")
                    val start = m.groupValues[1].toLong()
                    val total = m.groupValues[3].toLong()
                    if (total != spec.sizeBytes) return Attempt.Fatal(FailureReason.SIZE_MISMATCH, "Content-Range total $total, pinned ${spec.sizeBytes}")
                    if (start != offset) {
                        partFile.delete()
                        return Attempt.Transient("206 from byte $start, asked for $offset")
                    }
                }
                200 -> {
                    if (offset > 0) {
                        log("${spec.fileName}: the server ignored the range, starting again from 0")
                        offset = 0
                        digest.reset()
                    }
                    val length = conn.getHeaderField("Content-Length")?.toLongOrNull()
                    if (length != null && length != spec.sizeBytes) {
                        return Attempt.Fatal(FailureReason.SIZE_MISMATCH, "Content-Length $length, pinned ${spec.sizeBytes}")
                    }
                }
                416 -> {
                    partFile.delete()
                    return Attempt.Transient("416, part deleted")
                }
                404, 410 -> return Attempt.Fatal(FailureReason.NOT_FOUND, "HTTP $code from ${hostOf(url)}")
                429, 503 -> {
                    val retryAfter = conn.getHeaderField("Retry-After")?.trim()?.toLongOrNull()?.times(1000)
                        ?.takeIf { it in 0..DownloadStateMachine.MAX_RETRY_AFTER_MS }
                    return Attempt.Transient("HTTP $code from ${hostOf(url)}", serverError = true, retryAfterMs = retryAfter)
                }
                in 500..599 -> return Attempt.Transient("HTTP $code from ${hostOf(url)}", serverError = true)
                else -> return Attempt.Fatal(FailureReason.SERVER, "HTTP $code from ${hostOf(url)}")
            }

            onConnected()
            log("${spec.fileName}: ${if (offset > 0) "resuming at $offset" else "downloading"} from host ${hostOf(url)} (HTTP $code)")
            var done = offset
            listener.onBytes(done, spec.sizeBytes)
            try {
                FileOutputStream(partFile, offset > 0).use { out ->
                    conn.inputStream.use { input ->
                        val buffer = ByteArray(CHUNK_BYTES)
                        while (true) {
                            coroutineContext.ensureActive()
                            val n = input.read(buffer)
                            if (n == -1) break
                            if (done + n > spec.sizeBytes) {
                                partFile.delete()
                                return Attempt.Fatal(FailureReason.SIZE_MISMATCH, "more than ${spec.sizeBytes} bytes sent")
                            }
                            out.write(buffer, 0, n)
                            digest.update(buffer, 0, n)
                            done += n
                            listener.onBytes(done, spec.sizeBytes)
                        }
                        out.flush()
                        out.fd.sync()
                    }
                }
            } catch (e: IOException) {
                if (isOutOfSpace(e)) return Attempt.Fatal(FailureReason.INSUFFICIENT_STORAGE, "ENOSPC at byte $done")
                return Attempt.Transient("${e.javaClass.simpleName} at byte $done of ${spec.sizeBytes}")
            }
            if (done < spec.sizeBytes) return Attempt.Transient("the body ended at byte $done of ${spec.sizeBytes}")
            val hex = digest.digest().joinToString("") { "%02x".format(it) }
            return Attempt.Complete(hashOk = hex.equals(spec.sha256, ignoreCase = true))
        } finally {
            conn.disconnect()
        }
    }

    private suspend fun hashPrefix(file: File, length: Long, digest: MessageDigest) {
        file.inputStream().use { input ->
            val buffer = ByteArray(CHUNK_BYTES)
            var left = length
            while (left > 0) {
                coroutineContext.ensureActive()
                val n = input.read(buffer, 0, minOf(buffer.size.toLong(), left).toInt())
                if (n == -1) break
                digest.update(buffer, 0, n)
                left -= n
            }
        }
    }

    private fun sha256Of(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(CHUNK_BYTES)
            while (true) {
                val n = input.read(buffer)
                if (n == -1) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}

/** Counts the bytes read through it: what actually came over the wire, before any inflating. */
private class CountingInputStream(source: java.io.InputStream?) : java.io.FilterInputStream(source) {
    var count = 0L
        private set

    override fun read(): Int = super.read().also { if (it >= 0) count++ }

    override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { if (it > 0) count += it }
}
