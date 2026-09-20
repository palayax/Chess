package net.palaya.chessanalyzer.video

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.util.Log
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import net.palaya.chessanalyzer.core.narration.VideoScript

/**
 * Turns narration text into WAVs via the platform `android.speech.tts.TextToSpeech`, so the real
 * spoken duration is known before [BoardFrameRenderer]/[VideoExporter] lay out the timeline (an
 * estimate would drift the board out of sync with the voice over a multi-minute video).
 *
 * TTS is unreliable across devices — no engine installed, an engine installed but its voice data
 * not downloaded, a `synthesizeToFile` call that just never completes. None of those may fail the
 * export: every one of them degrades to [Result.Silent] (a silent WAV of the segment's estimated
 * duration) so the caller always gets a complete, playable timeline and burns captions instead of
 * speech for whatever fell back. Emulator images in particular ship with no TTS voice data at
 * all, so this path is expected to be exercised routinely, not just as an edge case.
 *
 * [prepareEngine]/[synthesizeSegment]/[release] are the decomposed per-segment lifecycle that
 * [DeviceTtsProvider] wraps to expose this as a [NarrationVoiceProvider] — [synthesizeAll] itself
 * is just those three calls in a loop, kept as the original whole-script entry point so existing
 * callers (and the instrumented export test) don't have to change.
 */
class NarrationSynthesizer(context: Context) {

    sealed interface Result {
        val segmentIndex: Int
        val durationMs: Long

        data class Synthesized(
            override val segmentIndex: Int,
            val wavFile: File,
            override val durationMs: Long,
        ) : Result

        data class Silent(
            override val segmentIndex: Int,
            override val durationMs: Long,
            val reason: String,
        ) : Result
    }

    data class Progress(val completed: Int, val total: Int, val currentCaption: String = "")

    /**
     * What [prepareEngine] actually picked, so it is diagnosable (logged, and surfaced to tests
     * and callers) instead of a silent "whatever the default was".
     */
    data class SelectedVoiceInfo(
        val name: String,
        val locale: String,
        val quality: Int,
        val isNetworkConnectionRequired: Boolean,
    )

    private val appContext = context.applicationContext
    private val _progress = MutableStateFlow(Progress(0, 0))
    val progress: StateFlow<Progress> = _progress.asStateFlow()

    private val cancelled = AtomicBoolean(false)

    private var tts: TextToSpeech? = null
    private var engineUsable = false

    /** The voice [prepareEngine] selected, or null if none was usable (falls back to whatever the
     * engine's own default voice is). Populated after a successful [prepareEngine] call. */
    var selectedVoiceInfo: SelectedVoiceInfo? = null
        private set

    fun cancel() {
        cancelled.set(true)
    }

    /** Initializes the TTS engine and checks it actually has a usable voice. Idempotent. */
    suspend fun prepareEngine(): Boolean = withContext(Dispatchers.IO) {
        tts?.let { return@withContext engineUsable }
        try {
            val initResult = CompletableDeferred<Int>()
            val engine = TextToSpeech(appContext) { status -> initResult.complete(status) }
            tts = engine
            val status = withTimeoutOrNull(8_000) { initResult.await() }
            engineUsable = status == TextToSpeech.SUCCESS && isLanguageUsable(engine, Locale.US)
            if (engineUsable) {
                configureVoice(engine)
            }
        } catch (e: Exception) {
            engineUsable = false
        }
        engineUsable
    }

    /** Releases the TTS engine. Safe to call even if [prepareEngine] was never called. */
    fun release() {
        try {
            tts?.stop()
            tts?.shutdown()
        } catch (e: Exception) {
            // Best-effort teardown only.
        } finally {
            tts = null
            engineUsable = false
        }
    }

    /**
     * Picks the most natural-sounding available voice and applies rate/pitch tuning. This is the
     * single biggest lever on how robotic the device voice sounds — the engine's out-of-the-box
     * default voice/rate is what made narration sound flat and rushed in the first place.
     */
    private fun configureVoice(engine: TextToSpeech) {
        val best = try {
            chooseBestVoice(engine)
        } catch (e: Exception) {
            null
        }
        if (best != null) {
            try {
                engine.setVoice(best)
                selectedVoiceInfo = SelectedVoiceInfo(
                    name = best.name,
                    locale = best.locale.toString(),
                    quality = best.quality,
                    isNetworkConnectionRequired = best.isNetworkConnectionRequired,
                )
                Log.i(
                    TAG,
                    "Selected TTS voice: name=${best.name} locale=${best.locale} " +
                        "quality=${qualityLabel(best.quality)} networkRequired=${best.isNetworkConnectionRequired}",
                )
            } catch (e: Exception) {
                Log.w(TAG, "setVoice() failed for ${best.name}, keeping engine default", e)
                selectedVoiceInfo = null
            }
        } else {
            Log.i(TAG, "No enumerable voice found; keeping engine default voice")
            selectedVoiceInfo = null
        }

        try {
            engine.setSpeechRate(SPEECH_RATE)
            engine.setPitch(SPEECH_PITCH)
        } catch (e: Exception) {
            // Best-effort — an engine that rejects these calls still speaks at its own defaults.
        }
    }

    /**
     * Enumerates [TextToSpeech.getVoices], filters to English (preferring the device locale, then
     * US/UK English, then any English voice), and picks the highest-[Voice.getQuality] one.
     * Among equal-quality candidates an offline voice is preferred, but a network voice is still
     * chosen over a lower-quality offline one — cloud/network TTS voices are usually far more
     * natural, so quality wins over the offline preference rather than the other way around.
     */
    private fun chooseBestVoice(engine: TextToSpeech): Voice? {
        val voices = engine.voices ?: return null
        if (voices.isEmpty()) return null

        // A voice whose data hasn't actually been downloaded will fail to speak with it selected.
        val installed = voices.filterNotNull().filter { v ->
            val notInstalled = v.features?.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) ?: false
            !notInstalled
        }
        val pool = installed.ifEmpty { voices.filterNotNull() }
        if (pool.isEmpty()) return null

        val deviceLocale = Locale.getDefault()
        fun isEnglish(v: Voice) = v.locale.language == Locale.ENGLISH.language
        fun localeRank(v: Voice): Int = when {
            v.locale == deviceLocale -> 0
            v.locale == Locale.US -> 1
            v.locale == Locale.UK -> 2
            isEnglish(v) -> 3
            else -> 4
        }

        val englishCandidates = pool.filter { localeRank(it) < 4 }
        val candidates = englishCandidates.ifEmpty { pool }

        return candidates
            .sortedWith(
                compareByDescending<Voice> { it.quality }
                    // false (offline) sorts before true (network) on a quality tie.
                    .thenBy { it.isNetworkConnectionRequired }
                    .thenBy { localeRank(it) },
            )
            .firstOrNull()
    }

    private fun qualityLabel(quality: Int): String = when (quality) {
        Voice.QUALITY_VERY_HIGH -> "VERY_HIGH"
        Voice.QUALITY_HIGH -> "HIGH"
        Voice.QUALITY_NORMAL -> "NORMAL"
        Voice.QUALITY_LOW -> "LOW"
        Voice.QUALITY_VERY_LOW -> "VERY_LOW"
        else -> "UNKNOWN($quality)"
    }

    /**
     * Synthesizes one piece of text to [outFile]. [prepareEngine] must be called first (and have
     * returned `true`) for this to do anything but immediately fall back to [Result.Silent].
     * [segmentIndex] is only carried through to the returned [Result] for the caller's bookkeeping
     * — pass `-1` for an ad-hoc call not tied to a script segment.
     */
    suspend fun synthesizeSegment(text: String, outFile: File, estimatedMs: Long, segmentIndex: Int = -1): Result {
        val engine = tts
        if (!engineUsable || engine == null) {
            return Result.Silent(segmentIndex, estimatedMs.coerceAtLeast(400L), "TTS engine/voice unavailable")
        }
        if (text.isBlank()) {
            return Result.Silent(segmentIndex, estimatedMs.coerceAtLeast(400L), "empty narration")
        }
        return synthesizeOne(engine, segmentIndex, text, estimatedMs, outFile)
    }

    /**
     * Synthesizes every segment's narration into [outputDir], one WAV per segment named by
     * segment index. Always returns a result per segment (never throws) — see class doc for the
     * fallback rules. Owns the full engine lifecycle (prepares it, releases it when done).
     */
    suspend fun synthesizeAll(script: VideoScript, outputDir: File): List<Result> = withContext(Dispatchers.IO) {
        outputDir.mkdirs()
        _progress.value = Progress(0, script.segments.size)
        prepareEngine()

        val results = ArrayList<Result>(script.segments.size)
        for (segment in script.segments) {
            if (cancelled.get()) break
            val outFile = File(outputDir, "segment_%03d.wav".format(segment.index))
            val result = synthesizeSegment(segment.narration, outFile, segment.estimatedSpeechMs, segment.index)
            results.add(result)
            _progress.value = Progress(results.size, script.segments.size, segment.caption)
        }

        release()

        // If we bailed early on cancellation, pad the rest with silence so callers still get a
        // result per segment rather than having to special-case a short list.
        if (results.size < script.segments.size) {
            for (i in results.size until script.segments.size) {
                val seg = script.segments[i]
                results.add(Result.Silent(seg.index, seg.estimatedSpeechMs.coerceAtLeast(400L), "cancelled"))
            }
        }
        results
    }

    private fun isLanguageUsable(tts: TextToSpeech, locale: Locale): Boolean = try {
        val availability = tts.setLanguage(locale)
        availability != TextToSpeech.LANG_MISSING_DATA && availability != TextToSpeech.LANG_NOT_SUPPORTED
    } catch (e: Exception) {
        false
    }

    /**
     * Entry point for one segment's narration: splits it into sentences and, when there is more
     * than one, paces them as separate utterances stitched together with short silences (see
     * [synthesizePaced]) instead of speaking the whole paragraph as one flat run-on utterance —
     * the single biggest lever on naturalness after voice selection. A single-sentence segment
     * (the common case for short beats) skips straight to [synthesizeSingleUtterance].
     */
    private suspend fun synthesizeOne(
        tts: TextToSpeech,
        segmentIndex: Int,
        text: String,
        estimatedMs: Long,
        outFile: File,
    ): Result {
        val sentences = splitIntoSentences(text)
        return if (sentences.size <= 1) {
            synthesizeSingleUtterance(tts, segmentIndex, text, estimatedMs, outFile)
        } else {
            synthesizePaced(tts, segmentIndex, sentences, estimatedMs, outFile)
        }
    }

    /**
     * Splits narration into sentences on `.`/`!`/`?` boundaries, keeping the terminal punctuation
     * (it decides the inter-sentence gap length below) and any trailing quote/paren. Falls back to
     * treating the whole text as one "sentence" if the text has no terminal punctuation at all —
     * this must never drop text, only choose how to chunk it.
     */
    internal fun splitIntoSentences(text: String): List<String> = Companion.splitIntoSentences(text)

    /**
     * Synthesizes each sentence as its own utterance (so the engine's natural end-of-sentence
     * intonation actually lands) and concatenates the resulting PCM into one WAV at [outFile],
     * with a short silence between sentences — a longer one after a question — instead of the
     * engine running every sentence together into one flat monotone. A sentence that fails to
     * synthesize is replaced with silence of its proportional share of [estimatedMs] rather than
     * losing the segment; only if every sentence fails does the whole segment fall back to
     * [Result.Silent], per the class-level fallback contract.
     */
    private suspend fun synthesizePaced(
        tts: TextToSpeech,
        segmentIndex: Int,
        sentences: List<String>,
        estimatedMs: Long,
        outFile: File,
    ): Result {
        val workDir = outFile.parentFile ?: appContext.cacheDir
        val perSentenceEstimate = (estimatedMs / sentences.size.coerceAtLeast(1)).coerceAtLeast(300L)

        data class SentenceAudio(val file: File?, val text: String)

        val rendered = ArrayList<SentenceAudio>(sentences.size)
        for ((i, sentence) in sentences.withIndex()) {
            if (cancelled.get()) {
                rendered.add(SentenceAudio(null, sentence))
                continue
            }
            val tmpFile = File(workDir, "${outFile.nameWithoutExtension}_s$i.wav")
            val result = synthesizeSingleUtterance(tts, segmentIndex, sentence, perSentenceEstimate, tmpFile)
            rendered.add(SentenceAudio((result as? Result.Synthesized)?.wavFile, sentence))
        }

        val targetSampleRate = rendered.firstNotNullOfOrNull { it.file?.let(WavUtil::readHeader)?.sampleRate }
        if (targetSampleRate == null || targetSampleRate <= 0) {
            rendered.forEach { it.file?.delete() }
            return Result.Silent(segmentIndex, estimatedMs.coerceAtLeast(400L), "all sentences failed to synthesize")
        }

        val pcmParts = ArrayList<ShortArray>(rendered.size * 2)
        for ((i, entry) in rendered.withIndex()) {
            val samples = if (entry.file != null) {
                WavUtil.readAsMono16(entry.file, targetSampleRate)
            } else {
                ShortArray(silenceSampleCount(perSentenceEstimate, targetSampleRate))
            }
            pcmParts.add(samples)
            entry.file?.delete()
            if (i < rendered.size - 1) {
                val gapMs = if (entry.text.trimEnd().endsWith("?")) QUESTION_GAP_MS else SENTENCE_GAP_MS
                pcmParts.add(ShortArray(silenceSampleCount(gapMs, targetSampleRate)))
            }
        }

        val totalSamples = pcmParts.sumOf { it.size }
        if (totalSamples <= 0) {
            return Result.Silent(segmentIndex, estimatedMs.coerceAtLeast(400L), "paced synthesis produced no audio")
        }

        return try {
            val dataSize = totalSamples * 2
            val pcmBuffer = ByteBuffer.allocate(dataSize).order(ByteOrder.LITTLE_ENDIAN)
            pcmParts.forEach { part -> part.forEach { pcmBuffer.putShort(it) } }
            outFile.outputStream().use { out ->
                out.write(WavUtil.buildWavHeader(dataSize, targetSampleRate, channels = 1, bitsPerSample = 16))
                out.write(pcmBuffer.array())
            }
            val durationMs = (totalSamples.toLong() * 1000L) / targetSampleRate
            Result.Synthesized(segmentIndex, outFile, durationMs)
        } catch (e: Exception) {
            outFile.delete()
            Result.Silent(segmentIndex, estimatedMs.coerceAtLeast(400L), "failed writing paced WAV: ${e.message}")
        }
    }

    private fun silenceSampleCount(durationMs: Long, sampleRate: Int): Int =
        ((durationMs * sampleRate) / 1000L).toInt().coerceAtLeast(0)

    private suspend fun synthesizeSingleUtterance(
        tts: TextToSpeech,
        segmentIndex: Int,
        text: String,
        estimatedMs: Long,
        outFile: File,
    ): Result {
        val utteranceId = "seg-$segmentIndex-${outFile.name}"
        val done = CompletableDeferred<Boolean>()

        val listener = object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit
            override fun onDone(utteranceId: String?) {
                if (!done.isCompleted) done.complete(true)
            }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                if (!done.isCompleted) done.complete(false)
            }
            override fun onError(utteranceId: String?, errorCode: Int) {
                if (!done.isCompleted) done.complete(false)
            }
        }

        return try {
            tts.setOnUtteranceProgressListener(listener)
            val queued = tts.synthesizeToFile(text, Bundle(), outFile, utteranceId)
            if (queued != TextToSpeech.SUCCESS) {
                return Result.Silent(segmentIndex, estimatedMs.coerceAtLeast(400L), "synthesizeToFile rejected the request")
            }
            // Real narration segments are short (a sentence or two); 20s is generous headroom.
            val succeeded = withTimeoutOrNull(20_000) { done.await() } ?: false
            if (!succeeded || !outFile.isFile || outFile.length() < 44) {
                outFile.delete()
                return Result.Silent(segmentIndex, estimatedMs.coerceAtLeast(400L), "synthesis failed or timed out")
            }
            val info = WavUtil.readHeader(outFile)
            if (info == null || info.durationMs <= 0L) {
                outFile.delete()
                Result.Silent(segmentIndex, estimatedMs.coerceAtLeast(400L), "produced file was not a readable WAV")
            } else {
                Result.Synthesized(segmentIndex, outFile, info.durationMs)
            }
        } catch (e: Exception) {
            outFile.delete()
            Result.Silent(segmentIndex, estimatedMs.coerceAtLeast(400L), "exception: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "NarrationSynthesizer"

        /**
         * Engine default speech rate reads slightly rushed for spoken explanation; a small
         * slow-down reads as more deliberate/natural without dragging. 1.0f is the engine's
         * normal rate — this is intentionally close to it, not a dramatic change.
         */
        private const val SPEECH_RATE = 0.95f

        /**
         * Left at the engine's neutral pitch. Pitch-shifting a synthetic voice does not make it
         * sound more human — it just makes an already-artificial voice sound more artificial — so
         * this is a constant purely to document that it was a deliberate choice, not an oversight.
         */
        private const val SPEECH_PITCH = 1.0f

        /**
         * Beat between sentences — long enough to read as a breath, not a stutter or a stall.
         * `internal` (not `private`) so [NarrationCoordinator] can reproduce the exact same pacing
         * when it stitches cached PAID-provider sentence clips back together — the fallback device
         * voice keeps using this via [synthesizePaced] above, unchanged.
         */
        internal const val SENTENCE_GAP_MS = 220L

        /** A question mark earns a slightly longer beat so it has room to land before continuing. */
        internal const val QUESTION_GAP_MS = 320L

        /**
         * Splits on a run of non-terminal characters followed by one or more `.`/`!`/`?` (handles
         * "...", "?!", etc.) and an optional trailing closing quote/paren/bracket, or — for text
         * with no terminal punctuation at all — the remaining fragment to end of string.
         */
        private val SENTENCE_SPLIT_REGEX = Regex("[^.!?]+[.!?]+[\"')\\]]*|[^.!?]+$")

        /**
         * Static so [NarrationCoordinator] can split a segment's narration into cache units without
         * instantiating a whole `TextToSpeech` engine just to reach a pure text-splitting function.
         */
        /**
         * A '.' sitting between two digits is a decimal point, not a sentence terminator.
         *
         * Accuracy is spoken to one decimal place ("59.9 percent"), so without this the splitter
         * cut mid-number and the voice said "You finished on fifty-nine." … "nine percent accuracy."
         * — two utterances with a pause through the middle of a single figure. Protecting the
         * decimal point before splitting, then restoring it, is the smallest fix that cannot
         * disturb ordinary sentence ends.
         */
        private val DECIMAL_POINT = Regex("(?<=\\d)\\.(?=\\d)")

        /** Placeholder for a decimal point while splitting. Never appears in narration text. */
        private const val DECIMAL_SENTINEL = ''

        internal fun splitIntoSentences(text: String): List<String> {
            val trimmed = text.trim()
            if (trimmed.isEmpty()) return emptyList()
            val protectedText = DECIMAL_POINT.replace(trimmed, DECIMAL_SENTINEL.toString())
            val matches = SENTENCE_SPLIT_REGEX.findAll(protectedText)
                .map { it.value.trim().replace(DECIMAL_SENTINEL, '.') }
                .filter { it.isNotBlank() }
                .toList()
            return matches.ifEmpty { listOf(trimmed) }
        }
    }
}
