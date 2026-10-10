package net.palaya.chessanalyzer.core.text

import net.palaya.chessanalyzer.core.narration.RealGameJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Locale

/**
 * C2 P2a (docs/LLM_REPHRASE_DESIGN.md §5.5): judges the recorded outputs of the candidate wording models with
 * the real [ClaimChecker] and writes the measurement report.
 *
 * The model runs themselves happen outside Gradle (`scripts/rephrase_measure.py`, llama.cpp on the host, the
 * same prompt bytes as the app); their raw outputs are committed under `docs/audit/rephrase/raw_<model>.jsonl`
 * so this test is never vacuous: it fails when that directory holds no run, and every run in it is judged
 * on every build. Output: `core/build/rephrase/measure_<model>.jsonl` (the input of
 * `scripts/audit_commentary.py rephrase`, which must agree) and `core/build/rephrase/report_<model>.md`
 * (copied to `docs/audit/rephrase_<model>_p<prompt>.md`).
 */
class RephraseMeasurementDumpTest {

    private class Row(
        val id: String, val surface: RephraseSurface, val kind: String, val game: String, val side: String?,
        val text: String, val raw: String, val candidate: String, val verdict: ClaimChecker.Verdict,
        val wallMs: Double, val promptN: Int, val promptMs: Double, val predictedN: Int, val predictedMs: Double,
    )

    private fun repoRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "vendor/models/MODELS.lock").isFile) dir = dir.parentFile
        return dir!!
    }

    private fun q(s: String?): String =
        if (s == null) "null" else "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t") + "\""

    private fun num(v: Any?): Double = (v as? Number)?.toDouble() ?: (v as? String)?.toDoubleOrNull() ?: 0.0

    @Suppress("UNCHECKED_CAST")
    private fun load(file: File): List<Row> = file.readLines(Charsets.UTF_8).filter { it.isNotBlank() }.map { line ->
        val m = RealGameJson(line).value() as Map<String, Any?>
        val surface = RephraseSurface.valueOf(m["surface"] as String)
        val text = m["text"] as String
        val raw = m["raw"] as String
        val candidate = RephrasePrompt.cleanOutput(raw, surface)
        Row(
            m["id"] as String, surface, m["kind"] as String, m["game"] as String, m["side"] as String?, text, raw, candidate,
            ClaimChecker.check(text, candidate, surface),
            num(m["wall_ms"]), num(m["prompt_n"]).toInt(), num(m["prompt_ms"]), num(m["predicted_n"]).toInt(), num(m["predicted_ms"]),
        )
    }

    private fun pct(a: Int, b: Int) = if (b == 0) "-" else String.format(Locale.ROOT, "%.1f %%", 100.0 * a / b)
    private fun quantile(xs: List<Double>, q: Double): Double {
        if (xs.isEmpty()) return 0.0
        val s = xs.sorted()
        return s[((s.size - 1) * q).toInt().coerceIn(0, s.size - 1)]
    }
    private fun f0(x: Double) = String.format(Locale.ROOT, "%.0f", x)
    private fun f1(x: Double) = String.format(Locale.ROOT, "%.1f", x)

    private val keyClasses = listOf("BLUNDER", "MISTAKE", "MISS", "BRILLIANT", "GREAT", "TURNING_POINT", "KEY_MOMENT", "MISSED_TACTIC", "FOUND_TACTIC", "INACCURACY")

    private fun report(label: String, rows: List<Row>): String = buildString {
        appendLine("# Rephrase measurement: $label, prompt v${RephrasePrompt.VERSION}")
        appendLine()
        appendLine("Written by `RephraseMeasurementDumpTest` from `docs/audit/rephrase/raw_$label.jsonl` (host run of")
        appendLine("`scripts/rephrase_measure.py`, llama.cpp b11190 CPU, greedy, n_ctx 2048, prefix in the KV cache).")
        appendLine("Verdicts by the Kotlin `ClaimChecker`; `scripts/audit_commentary.py rephrase` re-checks them independently.")
        appendLine("Corpus: every distinct card text of the three audited games (no side, White, Black) and every eligible")
        appendLine("narration beat of the five pacing games at the Normal pace (three sides).")
        appendLine("Texts of this run that today's generator no longer writes (V4 changed three connectives after the run): ${staleCounts[label] ?: 0}.")
        appendLine()
        appendLine("## Verdicts (design §5.5: rejection = REJECT / all; bar <= 25 % cards, <= 35 % narration)")
        appendLine()
        appendLine("| Surface | Texts | Accepted | Unchanged | Rejected | Rejection rate | Unchanged rate |")
        appendLine("|---|---|---|---|---|---|---|")
        for (s in RephraseSurface.values()) {
            val r = rows.filter { it.surface == s }
            val acc = r.count { it.verdict == ClaimChecker.Verdict.Accepted }
            val unc = r.count { it.verdict == ClaimChecker.Verdict.Unchanged }
            val rej = r.count { it.verdict is ClaimChecker.Verdict.Rejected }
            appendLine("| ${s.name.lowercase()} | ${r.size} | $acc | $unc | $rej | ${pct(rej, r.size)} | ${pct(unc, r.size)} |")
        }
        appendLine()
        appendLine("### Rejections by reason")
        appendLine()
        appendLine("| Reason | Cards | Narration |")
        appendLine("|---|---|---|")
        val reasons = rows.mapNotNull { (it.verdict as? ClaimChecker.Verdict.Rejected)?.reason }.distinct().sortedBy { it.name }
        for (reason in reasons) {
            val c = rows.count { it.surface == RephraseSurface.CARD && (it.verdict as? ClaimChecker.Verdict.Rejected)?.reason == reason }
            val n = rows.count { it.surface == RephraseSurface.NARRATION && (it.verdict as? ClaimChecker.Verdict.Rejected)?.reason == reason }
            appendLine("| ${reason.name} | $c | $n |")
        }
        appendLine()
        appendLine("## Latency on the host (reference only; the Pixel 8 decides)")
        appendLine()
        appendLine("| Surface | Median ms | p90 ms | Median prompt tokens (after the cached prefix) | Median output tokens | Prefill tok/s | Decode tok/s |")
        appendLine("|---|---|---|---|---|---|---|")
        for (s in RephraseSurface.values()) {
            val r = rows.filter { it.surface == s }
            val prefill = r.filter { it.promptMs > 0 }.map { it.promptN * 1000.0 / it.promptMs }
            val decode = r.filter { it.predictedMs > 0 }.map { it.predictedN * 1000.0 / it.predictedMs }
            appendLine(
                "| ${s.name.lowercase()} | ${f0(quantile(r.map { it.wallMs }, 0.5))} | ${f0(quantile(r.map { it.wallMs }, 0.9))} | " +
                    "${quantile(r.map { it.promptN.toDouble() }, 0.5).toInt()} | ${quantile(r.map { it.predictedN.toDouble() }, 0.5).toInt()} | " +
                    "${f1(quantile(prefill, 0.5))} | ${f1(quantile(decode, 0.5))} |"
            )
        }
        appendLine()
        appendLine("Key moments per game (the cards of class BLUNDER/MISTAKE/MISS/BRILLIANT/GREAT, no side, at most 5): total time")
        appendLine()
        for (g in rows.map { it.game }.distinct().sorted()) {
            val k = rows.filter { it.game == g && it.surface == RephraseSurface.CARD && it.side == null && it.kind in keyClasses.take(5) }.take(5)
            if (k.isNotEmpty()) appendLine("- $g: ${k.size} cards, ${f1(k.sumOf { it.wallMs } / 1000)} s")
        }
        appendLine()
        val narrAccepted = rows.filter { it.surface == RephraseSurface.NARRATION && it.verdict == ClaimChecker.Verdict.Accepted }
            .associate { it.text to ClaimChecker.normalize(it.candidate) }
        if (rows.count { it.surface == RephraseSurface.NARRATION } >= 300) {
            appendLine("## The narrated story after the post-pass (Normal pace, 169 wpm, no side; design §6.2's cap: <= 1.10 x and the §9.7 budget)")
            appendLine()
            appendLine("| Game | Beats reworded | Story before | Story after | Change | Budget |")
            appendLine("|---|---|---|---|---|---|")
            val games = listOf(
                "scholars" to net.palaya.chessanalyzer.core.narration.RealGameFixture.scholars,
                "chesscom" to net.palaya.chessanalyzer.core.narration.RealGameFixture.chesscom,
                "immortal" to net.palaya.chessanalyzer.core.narration.RealGameFixture.immortal,
                "game01" to net.palaya.chessanalyzer.core.narration.RealGameFixture.game01,
                "byrne_fischer" to net.palaya.chessanalyzer.core.narration.RealGameFixture.byrneFischer,
            )
            for ((name, g) in games) {
                val script = net.palaya.chessanalyzer.core.narration.VideoScriptGenerator(null)
                    .generate(g.report(null), g.pgn, net.palaya.chessanalyzer.core.narration.NarrationOptions(speechWpm = 169, pace = net.palaya.chessanalyzer.core.narration.VideoPace.NORMAL))
                val budget = net.palaya.chessanalyzer.core.narration.VideoScriptGenerator.budgetMs((g.report(null).annotations.size + 1) / 2)
                val after = RephrasedScript.apply(script, narrAccepted, 169, budget)
                val changed = script.segments.zip(after.segments).count { (a, b) -> a.narration != b.narration }
                appendLine(
                    "| $name | $changed of ${RephrasedScript.beats(script).size} | ${f1(script.storyMs / 1000.0)} s | ${f1(after.storyMs / 1000.0)} s | " +
                        "${f1((after.storyMs - script.storyMs) / 1000.0)} s | ${f1(budget / 1000.0)} s |"
                )
            }
            appendLine()
        }
        appendLine("## Quality sample: original vs accepted rewrite (owner: fill the A/B column, prefer = R or O)")
        appendLine()
        appendLine("| # | Id | Original | Rewrite | Prefer |")
        appendLine("|---|---|---|---|---|")
        var n = 0
        for (g in listOf("immortal", "chesscom", "game01")) {
            val accepted = rows.filter { it.game == g && it.verdict == ClaimChecker.Verdict.Accepted }
                .sortedWith(compareBy<Row> { keyClasses.indexOf(it.kind).let { i -> if (i < 0) 99 else i } }.thenBy { it.id })
                .take(20)
            for (r in accepted) {
                n++
                appendLine("| $n | ${r.id} | ${r.text.replace("|", "/")} | ${r.candidate.replace("|", "/")} |  |")
            }
        }
        appendLine()
        appendLine("## Every rejection (candidate and reason)")
        appendLine()
        for (r in rows.filter { it.verdict is ClaimChecker.Verdict.Rejected }) {
            val v = r.verdict as ClaimChecker.Verdict.Rejected
            appendLine("- `${r.id}` **${v.reason}** (${v.detail.replace("\n", " ").take(160)})  ")
            appendLine("  O: ${r.text}  ")
            appendLine("  R: ${r.candidate.replace("\n", " / ")}")
        }
    }

    @Test
    fun `judge every recorded model run and write the report`() {
        val dir = File(repoRoot(), "docs/audit/rephrase")
        val runs = dir.listFiles { f -> f.name.startsWith("raw_") && f.name.endsWith(".jsonl") }.orEmpty().sortedBy { it.name }
        assertTrue("no recorded model run in $dir (scripts/rephrase_measure.py writes them)", runs.isNotEmpty())
        val out = File("build/rephrase").apply { mkdirs() }
        val corpus = RephraseCorpus.distinct.map { it.surface to it.text }.toSet()
        for (run in runs) {
            val label = run.name.removePrefix("raw_").removeSuffix(".jsonl")
            val rows = load(run)
            assertTrue("$label: empty", rows.isNotEmpty())
            // A run is of the current corpus: (nearly) every text it judged is a recorded text of today's generator. V4
            // replaced three narration connectives after the runs ("Back to the game now."): up to 2 % may be stale, and
            // the report says how many; more means the generator moved and the runs must be redone.
            val stale = rows.filter { (it.surface to it.text) !in corpus }
            assertTrue("$label: ${stale.size} texts not in today's corpus: ${stale.take(3).map { it.id }}", stale.size <= rows.size / 50)
            staleCounts[label] = stale.size
            File(out, "measure_$label.jsonl").writeText(rows.joinToString("") { r ->
                val v = r.verdict
                val verdict = when (v) {
                    ClaimChecker.Verdict.Accepted -> "ACCEPT"
                    ClaimChecker.Verdict.Unchanged -> "UNCHANGED"
                    is ClaimChecker.Verdict.Rejected -> "REJECT"
                }
                "{\"id\":${q(r.id)},\"surface\":${q(r.surface.name)},\"original\":${q(r.text)},\"candidate\":${q(r.candidate)}," +
                    "\"kotlin\":${q(verdict)},\"kotlin_reason\":${q((v as? ClaimChecker.Verdict.Rejected)?.reason?.name)}}\n"
            })
            File(out, "report_$label.md").writeText(report(label, rows))
            judged[label] = rows
        }
        File(out, "report_comparison.md").writeText(comparison(judged))
    }

    private val judged = LinkedHashMap<String, List<Row>>()
    private val staleCounts = HashMap<String, Int>()

    /** The runs side by side on the texts every run judged (the comparison runs keep all cards and every 4th beat). */
    private fun comparison(runs: Map<String, List<Row>>): String = buildString {
        val common = runs.values.map { r -> r.map { it.id }.toSet() }.reduce { a, b -> a intersect b }
        appendLine("# Rephrase measurement: the candidates side by side, prompt v${RephrasePrompt.VERSION}")
        appendLine()
        appendLine("On the ${common.size} texts every run judged (${common.count { it.startsWith("card/") }} card texts, ${common.count { it.startsWith("narr/") }} narration beats).")
        appendLine("Host CPU (llama.cpp b11190); latency is a reference only.")
        appendLine()
        appendLine("| Model | Card rejection | Card unchanged | Card accepted | Narration rejection | Narration unchanged | Narration accepted | Median ms card | Median ms narration | Decode tok/s |")
        appendLine("|---|---|---|---|---|---|---|---|---|---|")
        for ((label, all) in runs) {
            val rows = all.filter { it.id in common }
            fun part(s: RephraseSurface) = rows.filter { it.surface == s }
            fun rate(s: RephraseSurface, f: (Row) -> Boolean) = pct(part(s).count(f), part(s).size)
            val decode = rows.filter { it.predictedMs > 0 }.map { it.predictedN * 1000.0 / it.predictedMs }
            appendLine(
                "| $label | ${rate(RephraseSurface.CARD) { it.verdict is ClaimChecker.Verdict.Rejected }} | " +
                    "${rate(RephraseSurface.CARD) { it.verdict == ClaimChecker.Verdict.Unchanged }} | " +
                    "${rate(RephraseSurface.CARD) { it.verdict == ClaimChecker.Verdict.Accepted }} | " +
                    "${rate(RephraseSurface.NARRATION) { it.verdict is ClaimChecker.Verdict.Rejected }} | " +
                    "${rate(RephraseSurface.NARRATION) { it.verdict == ClaimChecker.Verdict.Unchanged }} | " +
                    "${rate(RephraseSurface.NARRATION) { it.verdict == ClaimChecker.Verdict.Accepted }} | " +
                    "${f0(quantile(part(RephraseSurface.CARD).map { it.wallMs }, 0.5))} | ${f0(quantile(part(RephraseSurface.NARRATION).map { it.wallMs }, 0.5))} | " +
                    "${f1(quantile(decode, 0.5))} |"
            )
        }
        appendLine()
        appendLine("## The same texts, accepted by every model (first 12): how each one words it")
        appendLine()
        val labels = runs.keys.toList()
        val byId = runs.mapValues { (_, r) -> r.associateBy { it.id } }
        val both = common.filter { id -> labels.all { byId.getValue(it).getValue(id).verdict == ClaimChecker.Verdict.Accepted } }.sorted().take(12)
        for (id in both) {
            appendLine("- `$id`  ")
            appendLine("  original: ${byId.getValue(labels.first()).getValue(id).text}  ")
            for (l in labels) appendLine("  $l: ${byId.getValue(l).getValue(id).candidate}  ")
        }
    }
}
