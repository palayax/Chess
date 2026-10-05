package net.palaya.chessanalyzer.desktop.tts

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import net.palaya.chessanalyzer.desktop.work.sha256Hex
import java.io.BufferedWriter
import java.io.OutputStreamWriter
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class TtsException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/**
 * How to start one worker: which interpreter (so each backend can live in its own venv — the
 * Kokoro baseline in `pc/tts/.venv-kokoro`, Chatterbox later in `pc/tts/.venv`), which script,
 * which backend, and its backend-specific flags.
 */
data class TtsBackendSpec(
    val backend: String,
    val python: Path,
    val worker: Path,
    val extraArgs: List<String> = emptyList(),
) {
    fun command(): List<String> = listOf(python.toString(), "-u", worker.toString(), "--backend", backend) + extraArgs
}

/** The worker's `hello` reply. [fingerprint] keys the per-line cache (§3.4). */
data class BackendInfo(val name: String, val version: String, val device: String, val sampleRate: Int, val voice: String, val raw: JsonObject, val loadSeconds: Double?) {
    val fingerprint: String by lazy {
        val stable = JsonObject(raw.filterKeys { it != "ok" && it != "loadSeconds" && it != "device" })
        sha256Hex(stable.toString())
    }
}

data class SynthReply(val id: String, val durationMs: Int, val sampleRate: Int, val rtf: Double?, val synthSeconds: Double?)

/** Speech synthesis, one line at a time. */
interface TtsClient : AutoCloseable {
    val info: BackendInfo
    fun synth(id: String, text: String, lang: String, emotion: String, out: Path): SynthReply
}

/**
 * The Python sidecar `pc/tts/tts_worker.py` over its JSON-lines protocol (design §11): one JSON
 * object per line each way, stdout carries nothing else, stderr goes to [logFile]. stdin stays
 * open for the life of the worker (the same lesson as the Stockfish pipe).
 */
class TtsWorkerProcess(private val spec: TtsBackendSpec, private val logFile: Path) : TtsClient {

    private val process: Process
    private val writer: BufferedWriter
    private val lines = LinkedBlockingQueue<String>()
    @Volatile private var eof = false
    override val info: BackendInfo

    init {
        if (!Files.isRegularFile(spec.python)) {
            throw TtsException("TTS Python not found: ${spec.python}. Create it with: uv venv -p 3.13 pc/tts/.venv-kokoro && " +
                "uv pip install --python pc/tts/.venv-kokoro/Scripts/python.exe sherpa-onnx numpy (or pass --tts-python)")
        }
        if (!Files.isRegularFile(spec.worker)) throw TtsException("TTS worker script not found: ${spec.worker}")
        Files.createDirectories(logFile.toAbsolutePath().parent)
        val pb = ProcessBuilder(spec.command())
            .redirectError(ProcessBuilder.Redirect.appendTo(logFile.toFile()))
        pb.environment()["PYTHONIOENCODING"] = "utf-8"
        pb.environment()["PYTHONUTF8"] = "1"
        process = pb.start()
        writer = BufferedWriter(OutputStreamWriter(process.outputStream, Charsets.UTF_8))
        Thread({
            try {
                process.inputStream.bufferedReader(Charsets.UTF_8).useLines { seq -> seq.forEach { lines.put(it) } }
            } catch (_: Exception) {
            } finally {
                eof = true
            }
        }, "tts-worker-stdout").apply { isDaemon = true; start() }

        val hello = request(buildJsonObject { put("cmd", "hello") }, HELLO_TIMEOUT_MS)
        if (hello["ok"]?.jsonPrimitive?.booleanOrNull != true) {
            close()
            throw TtsException("TTS worker (${spec.backend}) failed to start: ${hello["error"]?.jsonPrimitive?.content}; see $logFile")
        }
        info = BackendInfo(
            name = hello.str("backend") ?: spec.backend,
            version = hello.str("version") ?: "?",
            device = hello.str("device") ?: "?",
            sampleRate = hello["sampleRate"]?.jsonPrimitive?.intOrNull ?: 0,
            voice = hello.str("voice") ?: "default",
            raw = hello,
            loadSeconds = hello["loadSeconds"]?.jsonPrimitive?.doubleOrNull,
        )
    }

    override fun synth(id: String, text: String, lang: String, emotion: String, out: Path): SynthReply {
        val reply = request(buildJsonObject {
            put("cmd", "synth"); put("id", id); put("text", text); put("lang", lang); put("emotion", emotion)
            put("out", out.toAbsolutePath().toString().replace('\\', '/'))
        }, SYNTH_TIMEOUT_MS)
        if (reply["ok"]?.jsonPrimitive?.booleanOrNull != true) {
            throw TtsException("synth $id failed: ${reply["error"]?.jsonPrimitive?.content}")
        }
        val gotId = reply.str("id")
        if (gotId != id) throw TtsException("protocol desync: asked for $id, got a reply for $gotId")
        return SynthReply(
            id = id,
            durationMs = reply["durationMs"]?.jsonPrimitive?.intOrNull ?: -1,
            sampleRate = reply["sampleRate"]?.jsonPrimitive?.intOrNull ?: 0,
            rtf = reply["rtf"]?.jsonPrimitive?.doubleOrNull,
            synthSeconds = reply["synthSeconds"]?.jsonPrimitive?.doubleOrNull,
        )
    }

    private fun request(msg: JsonObject, timeoutMs: Long): JsonObject {
        try {
            writer.write(msg.toString())
            writer.newLine()
            writer.flush()
        } catch (e: Exception) {
            throw TtsException("TTS worker stdin closed (exit ${exitCodeOrNull()}); see $logFile", e)
        }
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            val remaining = deadline - System.currentTimeMillis()
            if (remaining <= 0) throw TtsException("TTS worker timed out after $timeoutMs ms on ${msg["cmd"]}; see $logFile")
            val line = lines.poll(minOf(remaining, 500L), TimeUnit.MILLISECONDS)
            if (line == null) {
                if (eof && lines.isEmpty()) throw TtsException("TTS worker exited (code ${exitCodeOrNull()}); see $logFile")
                continue
            }
            if (line.isBlank()) continue
            return try {
                kotlinx.serialization.json.Json.parseToJsonElement(line).jsonObject
            } catch (e: Exception) {
                throw TtsException("TTS worker wrote a non-JSON line on stdout: ${line.take(200)}", e)
            }
        }
    }

    private fun exitCodeOrNull(): Int? = if (process.isAlive) null else process.exitValue()

    override fun close() {
        try {
            if (process.isAlive) {
                writer.write(buildJsonObject { put("cmd", "quit") }.toString()); writer.newLine(); writer.flush()
            }
        } catch (_: Exception) {
        }
        try { writer.close() } catch (_: Exception) {}
        if (!process.waitFor(10, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            process.waitFor(10, TimeUnit.SECONDS)
        }
    }

    companion object {
        /** Model load (Chatterbox from disk can take a minute). */
        const val HELLO_TIMEOUT_MS = 300_000L
        /** One ≤ 320-char line; Kokoro int8 measured RTF ~4 on this CPU under load. */
        const val SYNTH_TIMEOUT_MS = 600_000L

        private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

        @Suppress("unused")
        private fun JsonElement.asObj() = jsonObject
    }
}
