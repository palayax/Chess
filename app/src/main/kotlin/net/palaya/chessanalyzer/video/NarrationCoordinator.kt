package net.palaya.chessanalyzer.video

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import net.palaya.chessanalyzer.core.narration.ScriptSegment
import net.palaya.chessanalyzer.core.narration.VideoScript

/**
 * Drives narration synthesis for a whole [VideoScript] through a [primary] provider, with a
 * mandatory per-segment fallback to [fallback] (always [DeviceTtsProvider]) on any failure —
 * "any [primary] failure must fall back to device TTS, never fail the export, never leave a
 * silent gap" is enforced here, in one place, rather than trusted to every caller. That contract
 * is what makes the neural voice safe to default to: a missing model, a mid-export crash or a
 * single unpronounceable sentence degrades to the robotic voice instead of to silence.
 *
 * Also owns the work-avoidance the pipeline needs, persisted in [store]
 * (`filesDir/narration/`, never the evictable `cacheDir` — see [NarrationStore]'s doc):
 *
 *  - **Segment-level caching** (the original behaviour): re-running this for the same segment
 *    text/provider/voice never re-synthesizes audio it already produced.
 *  - **Sentence-level caching**: [primary] narration is synthesized and cached one sentence
 *    at a time, not per whole segment. The script is deterministic template text, so a lot of it —
 *    "White to play. Pause the video. Can you find it?", per-ply reason clauses, chapter
 *    scaffolding — recurs **verbatim across different games**. Keying by sentence means those
 *    recur as cache hits on the *second and third* game a user reviews, not just on a re-export of
 *    the same one. The synthesized sentences are stitched back into one per-segment WAV with the
 *    same inter-sentence silence [NarrationSynthesizer] already uses for the device voice, then
 *    that whole-segment result is *also* cached under the segment-level key so playback/export
 *    never need to re-derive it from pieces.
 *
 * The mandatory [fallback] is not run through sentence-level caching — it is the cheap path being
 * fallen back TO, and it already does its own sentence pacing internally
 * ([NarrationSynthesizer.synthesizeOne]); duplicating that here would be pure risk for no benefit.
 *
 * Produces the same [NarrationSynthesizer.Result] list [ScriptTimeline]/[VideoExporter]'s PCM
 * builder already consume, so neither of those had to change to support pluggable providers.
 */
class NarrationCoordinator(
    private val primary: NarrationVoiceProvider,
    private val fallback: NarrationVoiceProvider,
    private val store: NarrationStore,
) {
    data class Outcome(
        val results: List<NarrationSynthesizer.Result>,
        val fallbackCount: Int,
        val primaryUsedCount: Int,
        /** Segments served entirely from the segment-level cache — zero provider calls at all. */
        val cacheHits: Int,
        /** Individual sentence-level cache hits across every segment attempted via [primary]. */
        val sentenceCacheHits: Int,
        /** Individual sentence-level provider calls (cache misses) across every segment. */
        val sentenceSynthesisCalls: Int,
        /** A single non-blocking, user-facing summary of what happened — null if nothing to report. */
        val notice: String?,
    ) {
        /** 0..100, or null if no sentence was ever attempted through [primary] at all. */
        val sentenceCacheHitRatePercent: Double?
            get() {
                val total = sentenceCacheHits + sentenceSynthesisCalls
                return if (total == 0) null else (sentenceCacheHits * 100.0) / total
            }
    }

    private val _progress = MutableStateFlow(NarrationSynthesizer.Progress(0, 0))
    val progress: StateFlow<NarrationSynthesizer.Progress> = _progress.asStateFlow()

    private val cancelled = AtomicBoolean(false)
    fun cancel() {
        cancelled.set(true)
    }

    suspend fun synthesizeAll(script: VideoScript, outputDir: File): Outcome = withContext(Dispatchers.IO) {
        outputDir.mkdirs()
        _progress.value = NarrationSynthesizer.Progress(0, script.segments.size)

        val primaryIsDevice = primary === fallback || primary.displayName == fallback.displayName
        val primaryReady = primary.prepare()
        // Only prepare the fallback engine if we might actually need it — avoids paying device
        // TTS's ~8s init cost on every export when the primary provider is healthy throughout.
        var fallbackReady: Boolean? = null

        val results = ArrayList<NarrationSynthesizer.Result>(script.segments.size)
        var fallbackCount = 0
        var primaryUsedCount = 0
        var segmentCacheHits = 0
        var sentenceCacheHits = 0
        var sentenceMisses = 0
        val failureReasons = LinkedHashSet<String>()

        for (segment in script.segments) {
            if (cancelled.get()) break

            if (segment.narration.isBlank()) {
                results.add(NarrationSynthesizer.Result.Silent(segment.index, segment.estimatedSpeechMs.coerceAtLeast(400L), "empty narration"))
                _progress.value = NarrationSynthesizer.Progress(results.size, script.segments.size, segment.caption)
                continue
            }

            var success: SynthesisResult.Success? = null
            var primaryFailure: String? = null

            if (primaryReady) {
                val segmentKey = store.keyFor(segment.narration, primary.displayName, primary.narrationCacheFingerprint())
                val wholeSegmentCached = store.get(segmentKey)
                val wholeSegmentInfo = wholeSegmentCached?.let(WavUtil::readHeader)
                if (wholeSegmentCached != null && wholeSegmentInfo != null) {
                    segmentCacheHits++
                    success = SynthesisResult.Success(wholeSegmentCached, wholeSegmentInfo.durationMs)
                } else {
                    val sentenceOutcome = synthesizeSegmentBySentence(segment, outputDir)
                    sentenceCacheHits += sentenceOutcome.cacheHits
                    sentenceMisses += sentenceOutcome.misses
                    if (sentenceOutcome.file != null) {
                        // Persist the assembled whole-segment result too, so a repeat of this exact
                        // segment (same text) is a single lookup rather than N sentence lookups.
                        val stored = store.put(segmentKey, sentenceOutcome.file)
                        if (sentenceOutcome.misses == 0) segmentCacheHits++
                        success = SynthesisResult.Success(stored, sentenceOutcome.durationMs)
                    } else {
                        primaryFailure = sentenceOutcome.failureReason
                    }
                }
            }

            if (success != null) {
                primaryUsedCount++
                results.add(NarrationSynthesizer.Result.Synthesized(segment.index, success.file, success.durationMs))
            } else {
                // Mandatory fallback — never leave this segment silent-by-accident.
                if (primaryFailure != null) failureReasons.add(primaryFailure)
                if (fallbackReady == null) fallbackReady = fallback.prepare()
                val fallbackFile = File(outputDir, "fallback_%03d.wav".format(segment.index))
                val fallbackResult = if (fallbackReady == true) {
                    fallback.synthesize(segment.narration, fallbackFile)
                } else {
                    SynthesisResult.Failure("device TTS unavailable")
                }
                fallbackCount++
                when (fallbackResult) {
                    is SynthesisResult.Success ->
                        results.add(NarrationSynthesizer.Result.Synthesized(segment.index, fallbackResult.file, fallbackResult.durationMs))
                    is SynthesisResult.Failure ->
                        results.add(NarrationSynthesizer.Result.Silent(segment.index, segment.estimatedSpeechMs.coerceAtLeast(400L), fallbackResult.reason))
                }
            }
            _progress.value = NarrationSynthesizer.Progress(results.size, script.segments.size, segment.caption)
        }

        primary.release()
        fallback.release()

        if (results.size < script.segments.size) {
            for (i in results.size until script.segments.size) {
                val seg = script.segments[i]
                results.add(NarrationSynthesizer.Result.Silent(seg.index, seg.estimatedSpeechMs.coerceAtLeast(400L), "cancelled"))
            }
        }

        val notice = when {
            primaryIsDevice || fallbackCount == 0 -> null
            primaryUsedCount == 0 ->
                "${primary.displayName} narration wasn't available — used the device voice for the whole review" +
                    (failureReasons.firstOrNull()?.let { " ($it)" } ?: "")
            else ->
                "${primary.displayName} narration wasn't used for $fallbackCount of ${script.segments.size} segment(s) — used the device voice instead" +
                    (failureReasons.firstOrNull()?.let { " ($it)" } ?: "")
        }

        Outcome(results, fallbackCount, primaryUsedCount, segmentCacheHits, sentenceCacheHits, sentenceMisses, notice)
    }

    private data class SentenceAssembly(
        val file: File?,
        val durationMs: Long,
        val cacheHits: Int,
        val misses: Int,
        val failureReason: String?,
    )

    /**
     * Synthesizes [segment]'s narration through [primary] one SENTENCE at a time, checking
     * [store] before every provider call and caching every new result under its own sentence-level
     * key, then stitches the (cached-or-fresh) sentence clips into one WAV with the same
     * inter-sentence gap timing [NarrationSynthesizer.synthesizePaced] uses for the device voice —
     * this is what makes cross-game boilerplate reuse (and the synthesis time it saves) real
     * rather than incidental.
     *
     * Fails the whole segment (falling through to the mandatory device-voice fallback) if any
     * sentence fails — sentences already synthesized and cached before the failure are not lost,
     * they simply sit in [store] ready for next time.
     */
    private suspend fun synthesizeSegmentBySentence(segment: ScriptSegment, outputDir: File): SentenceAssembly {
        val sentences = NarrationSynthesizer.splitIntoSentences(segment.narration)
        if (sentences.isEmpty()) return SentenceAssembly(null, 0, 0, 0, "empty narration")

        val fingerprint = primary.narrationCacheFingerprint()
        data class SentenceAudio(val file: File, val text: String)
        val rendered = ArrayList<SentenceAudio>(sentences.size)
        var hits = 0
        var misses = 0

        for ((i, sentence) in sentences.withIndex()) {
            val key = store.keyFor(sentence, primary.displayName, fingerprint)
            val cached = store.get(key)
            if (cached != null) {
                hits++
                rendered.add(SentenceAudio(cached, sentence))
                continue
            }
            val tmpFile = File(outputDir, "primary_seg%03d_s%02d.wav".format(segment.index, i))
            when (val attempt = primary.synthesize(sentence, tmpFile)) {
                is SynthesisResult.Success -> {
                    misses++
                    rendered.add(SentenceAudio(store.put(key, attempt.file), sentence))
                }
                is SynthesisResult.Failure -> {
                    misses++
                    return SentenceAssembly(null, 0, hits, misses, attempt.reason)
                }
            }
        }

        if (rendered.size == 1) {
            val info = WavUtil.readHeader(rendered[0].file)
                ?: return SentenceAssembly(null, 0, hits, misses, "sentence audio was not a readable WAV")
            return SentenceAssembly(rendered[0].file, info.durationMs, hits, misses, null)
        }

        val targetSampleRate = rendered.firstNotNullOfOrNull { WavUtil.readHeader(it.file)?.sampleRate }
            ?: return SentenceAssembly(null, 0, hits, misses, "no sentence audio was readable")

        val pcmParts = ArrayList<ShortArray>(rendered.size * 2)
        for ((i, entry) in rendered.withIndex()) {
            pcmParts.add(WavUtil.readAsMono16(entry.file, targetSampleRate))
            if (i < rendered.size - 1) {
                val gapMs = if (entry.text.trimEnd().endsWith("?")) {
                    NarrationSynthesizer.QUESTION_GAP_MS
                } else {
                    NarrationSynthesizer.SENTENCE_GAP_MS
                }
                pcmParts.add(ShortArray(((gapMs * targetSampleRate) / 1000L).toInt().coerceAtLeast(0)))
            }
        }

        val totalSamples = pcmParts.sumOf { it.size }
        if (totalSamples <= 0) return SentenceAssembly(null, 0, hits, misses, "assembled sentence audio was empty")

        return try {
            val outFile = File(outputDir, "primary_%03d.wav".format(segment.index))
            val dataSize = totalSamples * 2
            val pcmBuffer = ByteBuffer.allocate(dataSize).order(ByteOrder.LITTLE_ENDIAN)
            pcmParts.forEach { part -> part.forEach { pcmBuffer.putShort(it) } }
            outFile.outputStream().use { out ->
                out.write(WavUtil.buildWavHeader(dataSize, targetSampleRate, channels = 1, bitsPerSample = 16))
                out.write(pcmBuffer.array())
            }
            val durationMs = (totalSamples.toLong() * 1000L) / targetSampleRate
            SentenceAssembly(outFile, durationMs, hits, misses, null)
        } catch (e: Exception) {
            SentenceAssembly(null, 0, hits, misses, "failed assembling sentence audio: ${e.message}")
        }
    }

    companion object {
        /**
         * True when every non-blank segment of [script] already has a segment-level cache hit in
         * [store] for [provider] — the "prepare narration" button's "already done, do nothing" case.
         * Cheap: pure file-existence + header reads, no synthesis, no network.
         */
        fun isFullyPrepared(script: VideoScript, provider: NarrationVoiceProvider, store: NarrationStore): Boolean {
            val fingerprint = provider.narrationCacheFingerprint()
            return script.segments.all { segment ->
                segment.narration.isBlank() ||
                    store.get(store.keyFor(segment.narration, provider.displayName, fingerprint)) != null
            }
        }
    }
}
