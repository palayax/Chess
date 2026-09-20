package net.palaya.chessanalyzer.video

import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import net.palaya.chessanalyzer.ManualEvidenceTool
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Evidence tool for the owner's decision that **Android device TTS is the Hebrew narration
 * default**: it asks the installed engine for Hebrew and, if the engine says yes, synthesizes
 * one Hebrew sentence to a WAV in the app's external files dir for the host to measure.
 *
 * Deliberately NOT an assertion. `isLanguageAvailable()` returning `LANG_AVAILABLE` is not
 * evidence of audio — voice data can be absent, the engine can accept the request and produce
 * silence — so the judgement is made on the host, on the pulled WAV's duration and RMS, the same
 * bar Piper and Kokoro had to clear. Everything this tool learns is written to
 * `hebrew_tts_probe.txt` next to the WAV so the outcome is recorded either way.
 *
 * Run via `am instrument` (see [ManualEvidenceTool]); Gradle would uninstall the app and delete
 * the evidence before it could be pulled.
 */
@RunWith(AndroidJUnit4::class)
class HebrewDeviceTtsProbe {

    @Test
    @ManualEvidenceTool
    fun probeHebrew() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val outDir = context.getExternalFilesDir(null) ?: context.filesDir
        outDir.mkdirs()
        val report = StringBuilder()
        fun log(line: String) {
            report.appendLine(line)
            Log.i(TAG, line)
        }

        val initLatch = CountDownLatch(1)
        var initStatus = Int.MIN_VALUE
        val tts = TextToSpeech(context) { status ->
            initStatus = status
            initLatch.countDown()
        }
        initLatch.await(15, TimeUnit.SECONDS)
        log("engine=${tts.defaultEngine} initStatus=$initStatus (SUCCESS=${TextToSpeech.SUCCESS})")
        try {
            val engines = tts.engines.joinToString { "${it.name}(${it.label})" }
            log("installed engines: $engines")
        } catch (e: Exception) {
            log("engines: ${e.message}")
        }

        val hebrew = Locale("he", "IL")
        val legacy = Locale("iw", "IL")
        for (locale in listOf(hebrew, legacy)) {
            val availability = try { tts.isLanguageAvailable(locale) } catch (e: Exception) { Int.MIN_VALUE }
            log("isLanguageAvailable($locale) = ${availabilityName(availability)}")
        }
        val voices = try { tts.voices?.filter { it.locale.language in setOf("he", "iw") } ?: emptyList() } catch (e: Exception) { emptyList() }
        log("hebrew voices enumerated: ${voices.size}")
        for (v in voices) {
            log("  voice ${v.name} locale=${v.locale} quality=${v.quality} network=${v.isNetworkConnectionRequired} features=${v.features}")
        }
        val allLocales = try { tts.availableLanguages?.map { it.toLanguageTag() }?.sorted() ?: emptyList() } catch (e: Exception) { emptyList() }
        log("availableLanguages (${allLocales.size}): $allLocales")

        val setResult = try { tts.setLanguage(hebrew) } catch (e: Exception) { Int.MIN_VALUE }
        log("setLanguage(he-IL) = ${availabilityName(setResult)}")

        val wav = File(outDir, "hebrew_tts_probe.wav")
        wav.delete()
        if (setResult == TextToSpeech.LANG_MISSING_DATA || setResult == TextToSpeech.LANG_NOT_SUPPORTED || setResult < 0) {
            log("RESULT: engine reports no usable Hebrew voice; no synthesis attempted")
        } else {
            val done = CountDownLatch(1)
            var outcome = "no callback"
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit
                override fun onDone(utteranceId: String?) { outcome = "done"; done.countDown() }
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) { outcome = "error"; done.countDown() }
                override fun onError(utteranceId: String?, errorCode: Int) { outcome = "error($errorCode)"; done.countDown() }
            })
            val queued = tts.synthesizeToFile(HEBREW_SENTENCE, Bundle(), wav, "hebrew-probe")
            log("synthesizeToFile queued=$queued (SUCCESS=${TextToSpeech.SUCCESS})")
            if (queued == TextToSpeech.SUCCESS) {
                done.await(30, TimeUnit.SECONDS)
                log("synthesis outcome=$outcome file=${wav.exists()} bytes=${wav.length()}")
                val header = if (wav.exists()) WavUtil.readHeader(wav) else null
                log("wav header: ${header?.let { "sampleRate=${it.sampleRate} durationMs=${it.durationMs}" } ?: "unreadable"}")
            }
            log("RESULT: see host-side measurement of ${wav.name}")
        }
        tts.shutdown()
        File(outDir, "hebrew_tts_probe.txt").writeText(report.toString())
        Log.i(TAG, "report written to ${outDir.absolutePath}")
    }

    private fun availabilityName(code: Int): String = when (code) {
        TextToSpeech.LANG_COUNTRY_VAR_AVAILABLE -> "LANG_COUNTRY_VAR_AVAILABLE(2)"
        TextToSpeech.LANG_COUNTRY_AVAILABLE -> "LANG_COUNTRY_AVAILABLE(1)"
        TextToSpeech.LANG_AVAILABLE -> "LANG_AVAILABLE(0)"
        TextToSpeech.LANG_MISSING_DATA -> "LANG_MISSING_DATA(-1)"
        TextToSpeech.LANG_NOT_SUPPORTED -> "LANG_NOT_SUPPORTED(-2)"
        else -> "UNKNOWN($code)"
    }

    private companion object {
        const val TAG = "HebrewDeviceTtsProbe"

        /** "White plays knight to f three, and that is a strong move." */
        const val HEBREW_SENTENCE = "הלבן משחק פרש לאף שלוש, וזה מהלך חזק."
    }
}
