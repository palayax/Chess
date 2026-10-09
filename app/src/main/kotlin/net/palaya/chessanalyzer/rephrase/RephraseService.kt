package net.palaya.chessanalyzer.rephrase

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import net.palaya.chessanalyzer.core.analysis.GameReport
import net.palaya.chessanalyzer.core.narration.VideoScript
import net.palaya.chessanalyzer.core.text.RephraseRequest
import net.palaya.chessanalyzer.core.text.RephraseResult
import net.palaya.chessanalyzer.core.text.RephraseSurface
import net.palaya.chessanalyzer.core.text.RephrasedReport
import net.palaya.chessanalyzer.core.text.RephrasedScript
import net.palaya.chessanalyzer.core.text.Rephraser
import java.security.MessageDigest

/**
 * The app side of C2 (docs/LLM_REPHRASE_DESIGN.md §6): the cache, the checker (inside every [Rephraser]
 * result) and the job runner, behind one object. It never blocks a screen on its own: [applyCached] and
 * [applyCachedScript] only read the cache, and [polish] is the job a "Polishing..." phase or the background
 * worker runs (skippable: cancel the coroutine).
 *
 * [rephraser] returns the backend to use now, or null when the feature is off or no model is installed;
 * [foreground] gates every request (the job only runs while a screen of ours is resumed, §6.2). Failures
 * (an exception, a timeout, an unavailable backend) are never cached and never surface: the original stays.
 * The log gets text hashes and reason codes only.
 */
class RephraseService(
    private val cache: RephraseCache,
    private val rephraser: suspend () -> Rephraser?,
    private val foreground: StateFlow<Boolean>? = null,
    private val requestTimeoutMs: Long = DEFAULT_TIMEOUT_MS,
    private val log: (String) -> Unit = {},
    /**
     * The id the cache is read under, cheaply (no load, no hashing): what [applyCached] and [applyCachedScript] use
     * on the UI path. Null = the feature is off or there is no model: the originals.
     */
    private val activeIdOf: suspend () -> String? = { rephraser()?.id },
    /** After a job: the app frees the model a minute later ([RephraseBackend.scheduleIdleRelease]). */
    private val onJobDone: () -> Unit = {},
) {

    data class Stats(
        val accepted: Int = 0,
        val unchanged: Int = 0,
        val rejected: Int = 0,
        val unavailable: Int = 0,
        val cached: Int = 0,
        val rejectedByReason: Map<String, Int> = emptyMap(),
    )

    /** [report] with every annotation text whose rewording is already cached swapped in. Reads files only. */
    suspend fun applyCached(report: GameReport): GameReport {
        val id = activeIdOf() ?: return report
        val texts = report.annotations.map { it.text } + report.keyMoments.map { it.summary }
        return RephrasedReport.apply(report, cache.accepted(id, RephraseSurface.CARD, texts.distinct()))
    }

    /** [script] with every cached narration rewording applied, under the script-level cap. Reads files only. */
    suspend fun applyCachedScript(script: VideoScript, wpm: Int, budgetMs: Long): VideoScript {
        val id = activeIdOf() ?: return script
        return RephrasedScript.apply(script, cache.accepted(id, RephraseSurface.NARRATION, RephrasedScript.beats(script)), wpm, budgetMs)
    }

    /** The id the narration cache entry of a rephrased script must carry, or null with the feature off. */
    suspend fun activeId(): String? = activeIdOf()

    /** How many of [items] still need the model (not cached under the active id): 0 = nothing to polish. */
    suspend fun pending(items: List<Pair<RephraseSurface, String>>): Int {
        val id = activeIdOf() ?: return 0
        return items.distinct().count { (s, t) -> cache.get(id, s, t) == null }
    }

    /**
     * Rewords every text of [items] that is not cached yet, in order, one at a time. [onProgress] gets
     * (done, total) after each item (cached ones count as done at once). Cancelling the calling coroutine
     * is Skip: what is done stays cached, the rest stays original.
     */
    suspend fun polish(
        items: List<Pair<RephraseSurface, String>>,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): Stats {
        val r = rephraser() ?: return Stats(unavailable = items.size)
        try {
            return polishWith(r, items, onProgress)
        } finally {
            onJobDone()
        }
    }

    private suspend fun polishWith(
        r: Rephraser,
        items: List<Pair<RephraseSurface, String>>,
        onProgress: (done: Int, total: Int) -> Unit,
    ): Stats {
        val unique = items.distinct()
        var s = Stats()
        val byReason = HashMap<String, Int>()
        onProgress(0, unique.size)
        for ((i, item) in unique.withIndex()) {
            val (surface, text) = item
            if (cache.get(r.id, surface, text) != null) {
                s = s.copy(cached = s.cached + 1)
                onProgress(i + 1, unique.size)
                continue
            }
            foreground?.first { it }
            val result = try {
                withTimeoutOrNull(requestTimeoutMs) { r.rephrase(RephraseRequest(surface, text)) }
                    ?: RephraseResult.Unavailable("timeout after $requestTimeoutMs ms")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                RephraseResult.Unavailable("${e.javaClass.simpleName}: ${e.message}")
            }
            when (result) {
                is RephraseResult.Accepted -> {
                    cache.put(r.id, surface, text, RephraseCache.Entry.Accepted(result.text)); s = s.copy(accepted = s.accepted + 1)
                }
                RephraseResult.Unchanged -> {
                    cache.put(r.id, surface, text, RephraseCache.Entry.Unchanged); s = s.copy(unchanged = s.unchanged + 1)
                }
                is RephraseResult.Rejected -> {
                    cache.put(r.id, surface, text, RephraseCache.Entry.Rejected(result.reason.name))
                    byReason.merge(result.reason.name, 1, Int::plus)
                    s = s.copy(rejected = s.rejected + 1)
                }
                is RephraseResult.Unavailable -> s = s.copy(unavailable = s.unavailable + 1)
            }
            log("rephrase ${surface.name.lowercase()} ${hash(text)}: ${describe(result)}")
            onProgress(i + 1, unique.size)
        }
        return s.copy(rejectedByReason = byReason)
    }

    private fun describe(r: RephraseResult): String = when (r) {
        is RephraseResult.Accepted -> "accepted"
        RephraseResult.Unchanged -> "unchanged"
        is RephraseResult.Rejected -> "rejected ${r.reason}"
        is RephraseResult.Unavailable -> "unavailable (${r.cause.take(80)})"
    }

    companion object {
        /** The diagnostic log tag. */
        const val TAG = "rephrase"

        /** Per request (design §6.4: 30 s on llama.cpp). */
        const val DEFAULT_TIMEOUT_MS = 30_000L

        fun hash(text: String): String =
            MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).take(6).joinToString("") { "%02x".format(it) }

        /**
         * The card texts of [report] in the order a job should take them: the key moments first (the
         * "Polishing the commentary" phase, §6.2), then every other card by its distance from [aroundPly].
         */
        fun cardOrder(report: GameReport, aroundPly: Int = 0): List<Pair<RephraseSurface, String>> {
            val key = report.keyMoments.map { it.summary }
            val rest = report.annotations.sortedWith(compareBy({ kotlin.math.abs(it.ply - aroundPly) }, { it.ply })).map { it.text }
            return (key + rest).distinct().map { RephraseSurface.CARD to it }
        }

        /** Only the key-moment texts (≤ 5 per side): the blocking phase's work. */
        fun keyMomentTexts(report: GameReport): List<Pair<RephraseSurface, String>> =
            report.keyMoments.map { RephraseSurface.CARD to it.summary }.distinct()

        /** The narration beats of [script] (the Video screen's "Polishing the narration" pass). */
        fun narrationOrder(script: VideoScript): List<Pair<RephraseSurface, String>> =
            RephrasedScript.beats(script).map { RephraseSurface.NARRATION to it }
    }
}
