package net.palaya.chessanalyzer.desktop.tts

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import net.palaya.chessanalyzer.desktop.script.ScriptFile
import net.palaya.chessanalyzer.desktop.work.Stage
import net.palaya.chessanalyzer.desktop.work.WorkDir
import net.palaya.chessanalyzer.desktop.work.WorkJson
import net.palaya.chessanalyzer.desktop.work.decideStage
import net.palaya.chessanalyzer.desktop.work.sha256Hex
import java.nio.file.Files
import java.time.Instant
import java.util.Locale

// audio/manifest.json ("palaya.audio/1", design §3.4).

@Serializable
data class AudioManifest(
    val schema: String = SCHEMA,
    val backend: ManifestBackend,
    val lines: List<ManifestLine>,
) {
    companion object {
        const val SCHEMA = "palaya.audio/1"
    }
}

@Serializable
data class ManifestBackend(
    val name: String,
    val version: String,
    val device: String,
    val sampleRate: Int,
    val voice: String,
    val fingerprint: String,
    val hello: JsonObject,
)

/**
 * Every number here is measured by the Kotlin reader ([Wav]) from the WAV on disk, never copied
 * from the worker's reply — the worker's own `durationMs` is kept separately as [workerDurationMs]
 * so a disagreement is visible.
 */
@Serializable
data class ManifestLine(
    val id: String,
    val beatId: String,
    val wav: String,
    val durationMs: Int,
    val sampleRate: Int,
    val leadingSilenceMs: Int,
    val trailingSilenceMs: Int,
    val rmsDbfs: Double,
    val peakDbfs: Double,
    val nearSilentRatio: Double,
    val clippedSamples: Int,
    val workerDurationMs: Int?,
    /** Real-time factor of the synthesis that produced this WAV (synth seconds / audio seconds). */
    val rtf: Double?,
    val synthSeconds: Double?,
    val cached: Boolean,
    /** sha256(spoken text + backend fingerprint): the per-line cache key. */
    val textSha256: String,
    val words: Int,
)

@Serializable
data class AudioMetrics(
    val finishedAt: String,
    val backend: String,
    val device: String,
    val lines: Int,
    val synthesized: Int,
    val cached: Int,
    val workerLoadSeconds: Double?,
    val audioSeconds: Double,
    /** Summed per-line synthesis time across all workers (CPU-seconds-ish, not wall). */
    val synthSeconds: Double,
    val workers: Int,
    /** synthSeconds / audioSeconds over the lines synthesized in this run. */
    val rtf: Double?,
    /** Words per minute over the voiced span (leading/trailing silence excluded). */
    val voicedWpm: Double?,
    val wallSeconds: Double,
)

/**
 * Stage AUDIO: one WAV per script line through a [TtsClient], then the manifest. Lines whose
 * spoken text and backend are unchanged are reused (`--from audio` after hand-editing script.json
 * re-synthesizes only the edited lines).
 */
class AudioStage(private val log: (String) -> Unit = ::println) {

    fun run(
        workDir: WorkDir,
        script: ScriptFile,
        spec: TtsBackendSpec,
        force: Boolean,
        from: Stage?,
        workers: Int = 1,
        clientFactory: (TtsBackendSpec) -> TtsClient = { TtsWorkerProcess(it, workDir.logsDir.resolve("tts_worker.${it.backend}.log")) },
    ): AudioManifest {
        val scriptSha = sha256Hex(Files.readAllBytes(workDir.scriptJson))
        // The backend's own fingerprint is only known once it says hello; the stage fingerprint
        // uses the spec (backend, interpreter, flags), which determines it.
        val fp = Stage.fingerprint(Stage.AUDIO, "script=$scriptSha", "backend=${spec.backend}", "args=${spec.extraArgs.joinToString(" ")}")
        val existing = read(workDir)
        val allWavsThere = existing?.lines?.all { Files.isRegularFile(workDir.dir.resolve(it.wav)) } == true
        val decision = decideStage(Stage.AUDIO, existing != null && allWavsThere, workDir.stageRecord(Stage.AUDIO), fp, force, from)
        if (!decision.run) {
            log("[audio] ${decision.reason}; ${existing!!.lines.size} lines, ${fmt(existing.lines.sumOf { it.durationMs } / 1000.0)} s")
            return existing
        }
        log("[audio] running: ${decision.reason}; backend ${spec.backend} via ${spec.python}")
        val t0 = System.nanoTime()
        Files.createDirectories(workDir.audioDir)
        val previous = existing?.lines?.associateBy { it.id } ?: emptyMap()
        val allLines = script.beats.flatMap { b -> b.lines.map { b.id to it } }

        val out = ArrayList<ManifestLine>(allLines.size)
        var synthSeconds = 0.0
        var synthAudioSeconds = 0.0
        val first = clientFactory(spec)
        val info = first.info
        log("[audio] ${info.name} ${info.version} on ${info.device}, ${info.sampleRate} Hz, voice ${info.voice}" +
            (info.loadSeconds?.let { ", loaded in ${fmt(it)} s" } ?: ""))

        // Which lines need synthesis: the cache key is the spoken text + the backend fingerprint.
        val keys = allLines.associate { (_, line) -> line.id to sha256Hex(line.spoken + "\n" + info.fingerprint) }
        val todo = allLines.filter { (_, line) ->
            val prev = previous[line.id]
            val wav = workDir.dir.resolve("audio/${line.id}.wav")
            force || prev == null || prev.textSha256 != keys[line.id] || !Files.isRegularFile(wav) || Wav.readHeader(wav) == null
        }.map { it.second }

        // Several CPU workers in parallel: Kokoro int8 measured RTF ~3.6 per process with 1-8
        // threads alike (the per-call work does not scale with threads), so processes are the lever.
        val replies = java.util.concurrent.ConcurrentHashMap<String, SynthReply>()
        val nWorkers = workers.coerceIn(1, maxOf(1, todo.size))
        val clients = ArrayList<TtsClient>()
        clients.add(first)
        try {
            if (todo.isNotEmpty()) {
                repeat(nWorkers - 1) { clients.add(clientFactory(spec)) }
                if (clients.size > 1) log("[audio] ${clients.size} workers for ${todo.size} lines")
                val queue = java.util.concurrent.ConcurrentLinkedQueue(todo)
                val done = java.util.concurrent.atomic.AtomicInteger()
                val failure = java.util.concurrent.atomic.AtomicReference<Throwable?>(null)
                val threads = clients.mapIndexed { wi, client ->
                    Thread({
                        while (failure.get() == null) {
                            val line = queue.poll() ?: break
                            val wav = workDir.dir.resolve("audio/${line.id}.wav")
                            try {
                                val reply = try {
                                    client.synth(line.id, line.spoken, script.lang, line.emotion, wav)
                                } catch (e: TtsException) {
                                    log("[audio] ${line.id}: ${e.message}; retrying once")
                                    client.synth(line.id, line.spoken, script.lang, line.emotion, wav)
                                }
                                replies[line.id] = reply
                                val n = done.incrementAndGet()
                                log(String.format(Locale.ROOT, "[audio] %3d/%d w%d %-10s %6d ms  rtf %5.2f  \"%s\"",
                                    n, todo.size, wi, line.id, reply.durationMs, reply.rtf ?: -1.0, line.spoken.take(60)))
                            } catch (e: Throwable) {
                                failure.compareAndSet(null, e)
                            }
                        }
                    }, "tts-$wi").apply { start() }
                }
                threads.forEach { it.join() }
                failure.get()?.let { throw if (it is TtsException) it else TtsException("synthesis failed: ${it.message}", it) }
            }
        } finally {
            clients.forEach { runCatching { it.close() } }
        }

        for ((beatId, line) in allLines) {
            val key = keys.getValue(line.id)
            val rel = "audio/${line.id}.wav"
            val wav = workDir.dir.resolve(rel)
            val prev = previous[line.id]
            val reply = replies[line.id]
            val (samples, rate) = Wav.readMono16(wav)
            val st = Wav.measure(samples, rate)
            val durationMs = Math.round(st.durationMs).toInt()
            if (reply != null) {
                synthSeconds += reply.synthSeconds ?: 0.0
                synthAudioSeconds += st.durationMs / 1000.0
                if (kotlin.math.abs(reply.durationMs - durationMs) > 5) {
                    log("[audio] WARNING ${line.id}: worker said ${reply.durationMs} ms, the WAV header says $durationMs ms")
                }
            }
            val words = line.spoken.split(Regex("\\s+")).count { it.isNotBlank() }
            out.add(
                ManifestLine(
                    id = line.id, beatId = beatId, wav = rel,
                    durationMs = durationMs, sampleRate = rate,
                    leadingSilenceMs = st.leadingSilenceMs, trailingSilenceMs = st.trailingSilenceMs,
                    rmsDbfs = st.rmsDbfs, peakDbfs = st.peakDbfs, nearSilentRatio = st.nearSilentRatio,
                    clippedSamples = st.clippedSamples,
                    workerDurationMs = reply?.durationMs ?: prev?.workerDurationMs,
                    rtf = reply?.rtf ?: prev?.rtf, synthSeconds = reply?.synthSeconds ?: prev?.synthSeconds,
                    cached = reply == null, textSha256 = key, words = words,
                )
            )
        }
        val synthesized = replies.size
        val manifest = AudioManifest(
            backend = ManifestBackend(info.name, info.version, info.device, info.sampleRate, info.voice, info.fingerprint, info.raw),
            lines = out,
        )
        workDir.writeAtomic(workDir.audioManifestJson, WorkJson.encodeToString(manifest))
        workDir.recordStage(Stage.AUDIO, fp, mapOf("backend" to info.name, "version" to info.version, "backendFingerprint" to info.fingerprint))

        val voicedMs = out.sumOf { (it.durationMs - it.leadingSilenceMs - it.trailingSilenceMs).coerceAtLeast(0) }
        val metrics = AudioMetrics(
            finishedAt = Instant.now().toString(),
            backend = info.name,
            device = info.device,
            lines = out.size,
            synthesized = synthesized,
            cached = out.size - synthesized,
            workerLoadSeconds = info.loadSeconds,
            audioSeconds = round3(out.sumOf { it.durationMs } / 1000.0),
            synthSeconds = round3(synthSeconds),
            workers = workers,
            rtf = if (synthAudioSeconds > 0) round3(synthSeconds / synthAudioSeconds) else null,
            voicedWpm = if (voicedMs > 0) round3(out.sumOf { it.words } * 60_000.0 / voicedMs) else null,
            wallSeconds = round3((System.nanoTime() - t0) / 1e9),
        )
        workDir.writeMetricsSection("audio", WorkJson.encodeToJsonElement(AudioMetrics.serializer(), metrics))
        log("[audio] ${out.size} lines (${synthesized} synthesized, ${out.size - synthesized} cached), ${fmt(metrics.audioSeconds)} s of audio, " +
            "RTF ${metrics.rtf?.let(::fmt) ?: "-"}, ${metrics.voicedWpm?.let { fmt(it) } ?: "-"} wpm voiced, wall ${fmt(metrics.wallSeconds)} s")
        return manifest
    }

    companion object {
        fun read(workDir: WorkDir): AudioManifest? {
            if (!Files.isRegularFile(workDir.audioManifestJson)) return null
            return try {
                WorkJson.decodeFromString(AudioManifest.serializer(), Files.readString(workDir.audioManifestJson))
                    .takeIf { it.schema == AudioManifest.SCHEMA }
            } catch (_: Exception) {
                null
            }
        }

        private fun round3(x: Double) = Math.round(x * 1000.0) / 1000.0
        private fun fmt(x: Double) = String.format(Locale.ROOT, "%.2f", x)
    }
}
