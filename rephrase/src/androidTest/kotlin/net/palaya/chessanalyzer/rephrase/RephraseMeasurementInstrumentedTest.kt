package net.palaya.chessanalyzer.rephrase

import android.content.Context
import android.os.BatteryManager
import android.os.Build
import android.os.Debug
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import net.palaya.chessanalyzer.core.text.RephrasePrompt
import net.palaya.chessanalyzer.core.text.RephraseRequest
import net.palaya.chessanalyzer.core.text.RephraseResult
import net.palaya.chessanalyzer.core.text.RephraseSurface
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The §5.5 measurement on a real phone (the owner's Pixel 8 decides; docs/LLM_REPHRASE_DESIGN.md §5.5, §8.2). A
 * manual evidence tool, excluded from the connected suite: run it with `am instrument` (see RUN_LOG "C2-P2b",
 * "Pixel 8") so Gradle does not uninstall the app and delete the output.
 *
 * It runs the recorded corpus (the `rephrase_corpus.jsonl` asset, the same texts as the host measurement) through
 * the real backend and writes, to the test app's external files dir:
 *  - `raw_<model>_<device>.jsonl`: one line per text in the host runs' schema (id, surface, text, raw output, prompt
 *    and output tokens and times, wall ms), so committing it under docs/audit/rephrase/ makes
 *    `RephraseMeasurementDumpTest` judge it and write the same report as for the host;
 *  - `summary_<model>_<device>.txt`: load time, PSS and native heap before/after load and the peak, the battery
 *    charge counter before/after, the thread count, the CPU ABI.
 *
 * Arguments: `ggufPath` (default /data/local/tmp/rephrase/qwen2.5-1.5b-instruct-q4_k_m.gguf), `ggufArch` (qwen2),
 * `limit` (0 = all 578 texts), `threads` (default: the big and middle cores), `only` (CARD or NARRATION).
 */
@RunWith(AndroidJUnit4::class)
class RephraseMeasurementInstrumentedTest {

    private fun maxFreqs(): List<Long> = (0 until Runtime.getRuntime().availableProcessors()).map { i ->
        runCatching { File("/sys/devices/system/cpu/cpu$i/cpufreq/cpuinfo_max_freq").readText().trim().toLong() }.getOrDefault(0L)
    }

    @Test
    @ManualEvidenceTool
    fun measure() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        val ctx = InstrumentationRegistry.getInstrumentation().context
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val gguf = LlamaRephraserInstrumentedTest.locateGguf(args.getString("ggufPath"))
        if (!gguf.isFile) fail("no GGUF at $gguf")
        val arch = args.getString("ggufArch") ?: "qwen2"
        val limit = args.getString("limit")?.toIntOrNull() ?: 0
        val only = args.getString("only")
        val threads = args.getString("threads")?.toIntOrNull() ?: RephraseSupport.recommendedThreads(maxFreqs())
        val modelId = gguf.nameWithoutExtension.lowercase()
        val device = Build.MODEL.replace(Regex("[^A-Za-z0-9]+"), "-").lowercase()

        var items = ctx.assets.open("rephrase_corpus.jsonl").bufferedReader().readLines().filter { it.isNotBlank() }.map { JSONObject(it) }
        if (only != null) items = items.filter { it.getString("surface") == only }
        if (limit > 0) items = items.take(limit)

        val outDir = target.getExternalFilesDir(null)!!
        val raw = File(outDir, "raw_${modelId}_$device.jsonl")
        val summary = File(outDir, "summary_${modelId}_$device.txt")
        val battery = target.getSystemService(Context.BATTERY_SERVICE) as BatteryManager

        val pssBefore = Debug.getPss()
        val heapBefore = Debug.getNativeHeapAllocatedSize()
        val chargeBefore = battery.getLongProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
        val backend = LlamaRephraser(gguf, modelId, family = if (arch == "qwen3") RephrasePrompt.Family.QWEN3 else RephrasePrompt.Family.QWEN2, threads = threads)
        var pssPeak = pssBefore
        var heapPeak = heapBefore
        var pssAfterLoad = 0L
        val counts = HashMap<String, Int>()
        val t0 = System.nanoTime()
        raw.bufferedWriter().use { w ->
            for ((n, it) in items.withIndex()) {
                val surface = RephraseSurface.valueOf(it.getString("surface"))
                val text = it.getString("text")
                val start = System.nanoTime()
                val r = backend.rephrase(RephraseRequest(surface, text))
                val wall = (System.nanoTime() - start) / 1e6
                val s = backend.lastStats
                counts.merge(r.javaClass.simpleName, 1, Int::plus)
                if (n == 0) pssAfterLoad = Debug.getPss()
                if (n % 10 == 0) {
                    pssPeak = maxOf(pssPeak, Debug.getPss())
                    heapPeak = maxOf(heapPeak, Debug.getNativeHeapAllocatedSize())
                }
                val line = JSONObject()
                    .put("id", it.getString("id")).put("surface", surface.name).put("kind", it.getString("kind"))
                    .put("game", it.getString("game")).put("side", it.opt("side")).put("text", text)
                    .put("raw", if (r is RephraseResult.Unavailable) "" else backend.lastRaw ?: "")
                    .put("prompt_n", s?.promptTokens ?: 0).put("prompt_ms", s?.promptMs ?: 0.0)
                    .put("predicted_n", s?.generatedTokens ?: 0).put("predicted_ms", s?.generatedMs ?: 0.0)
                    .put("wall_ms", wall).put("cache_n", s?.prefixTokens ?: 0).put("verdict", r.javaClass.simpleName)
                w.write(line.toString())
                w.newLine()
                if (n % 25 == 0) Log.i("RephraseMeasure", "$n/${items.size} ${"%.0f".format(wall)} ms $r")
            }
        }
        val totalS = (System.nanoTime() - t0) / 1e9
        val chargeAfter = battery.getLongProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
        summary.writeText(
            """
            |model $modelId ($arch), file ${gguf.length()} bytes, device ${Build.MANUFACTURER} ${Build.MODEL} (${soc()}), abi ${Build.SUPPORTED_ABIS[0]}, sdk ${Build.VERSION.SDK_INT}
            |threads $threads (max freqs ${maxFreqs()}), texts ${items.size}, total ${"%.1f".format(totalS)} s, results $counts
            |load ms ${backend.lastStats?.loadMs}
            |PSS KB before $pssBefore, after the first text $pssAfterLoad, peak $pssPeak
            |native heap bytes before $heapBefore, peak $heapPeak
            |battery charge counter uAh before $chargeBefore, after $chargeAfter (delta ${chargeBefore - chargeAfter})
            |""".trimMargin()
        )
        backend.release()
        Log.i("RephraseMeasure", summary.readText())
        assertTrue(counts.toString(), (counts["Unavailable"] ?: 0) < items.size)
    }

    private fun soc(): String = if (Build.VERSION.SDK_INT >= 31) Build.SOC_MODEL else "?"
}
