package net.palaya.chessanalyzer.data.models

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import net.palaya.chessanalyzer.diagnostics.DiagnosticLog
import org.json.JSONObject

/**
 * The third-party projects this app is built from, whose own latest releases "Check for updates" reports on
 * (A4). Information only: nothing from these projects is ever downloaded by a check, and engine code can never
 * be downloaded at all (Play policy). To add a component (an LLM, one day) add an entry here, a source in
 * [UpstreamSources.defaults] and a name string; the sheet draws whatever rows it is given.
 */
enum class UpstreamComponent(val id: String) {
    STOCKFISH("stockfish"),
    SHERPA_ONNX("sherpa-onnx"),
    KOKORO_VOICE("kokoro-voice"),
}

/** Why one upstream row has no answer. Never a message: the sheet maps each to a string. */
enum class UpstreamFailure {
    /** No connected network: nothing was requested. */
    NO_INTERNET,

    /** GitHub answered 403 / 429: the unauthenticated limit (60 requests an hour per address) is used up. */
    RATE_LIMITED,

    /** No answer, a timeout, a 404 or a 5xx. */
    UNAVAILABLE,

    /** An answer that is too large, not https, or not what was expected. */
    UNREADABLE,
}

/** What one component's check found. */
sealed interface UpstreamStatus {
    /** The request is on its way. */
    data object Checking : UpstreamStatus

    /** The latest upstream release is not newer than what this app has. */
    data object UpToDate : UpstreamStatus

    /** A newer upstream release exists; [latest] is how to show it ("20", "1.14.0", "v1.1"). It reaches users with an app update. */
    data class Newer(val latest: String) : UpstreamStatus

    /** Same version, but upstream's file is not the one this app pinned (re-published). The app keeps its own copy. */
    data object Changed : UpstreamStatus

    data class Failed(val reason: UpstreamFailure) : UpstreamStatus
}

/** One row of the upstream list: [ours] is the version this build has, as shown ("19", "1.13.8", "v0.19"). */
data class UpstreamRow(val component: UpstreamComponent, val ours: String, val status: UpstreamStatus)

/**
 * One thing to ask upstream: the request ([url], at most [maxBytes] once inflated) and how to read the answer.
 * [evaluate] is pure; it throws (anything) when the answer is not what was expected, which becomes
 * [UpstreamFailure.UNREADABLE].
 */
class UpstreamSource(
    val component: UpstreamComponent,
    val ours: String,
    val url: String,
    val maxBytes: Int,
    val evaluate: (ByteArray) -> UpstreamStatus,
)

/** What this build contains, as the upstream projects name it. */
data class OurComponents(
    /** `sf_19`. */
    val stockfishTag: String,
    /** `1.13.8`. */
    val sherpaOnnxVersion: String,
    /** `kokoro-int8-en-v0_19.tar`: the voice's tar, whose name carries the Kokoro version. */
    val voiceTarName: String,
    /** The upstream archive the tar was made from, with the size and SHA-256 this build pinned. */
    val voiceArchiveName: String,
    val voiceArchiveSizeBytes: Long,
    val voiceArchiveSha256: String,
)

/** Version strings and how they order. Pure; host-tested in `UpstreamVersionsTest`. */
object UpstreamVersions {
    /** `sf_19` -> [19]; `sf_17.1` -> [17, 1]; anything else (a dev build, a date tag) -> null. */
    fun stockfish(tag: String): List<Int>? =
        Regex("""^sf_(\d+(?:\.\d+)*)$""").matchEntire(tag.trim())?.groupValues?.get(1)?.split('.')?.map { it.toInt() }

    /** `v1.13.8` or `1.13.8` -> [1, 13, 8]; a pre-release suffix (`-rc1`) is not a release and gives null. */
    fun semver(tag: String): List<Int>? =
        Regex("""^v?(\d+(?:\.\d+){1,3})$""").matchEntire(tag.trim())?.groupValues?.get(1)?.split('.')?.map { it.toInt() }

    /**
     * The Kokoro version in a file name: `kokoro-int8-en-v0_19.tar(.bz2)` -> [0, 19], `kokoro-int8-multi-lang-v1_1.tar.bz2`
     * -> [1, 1]. Only the int8 builds (the kind this app uses); the "_" is Kokoro's own separator, v0.19 is a
     * different, older release than v1.0, so the two numbers are compared as numbers.
     */
    fun kokoroInt8(fileName: String): List<Int>? =
        Regex("""^kokoro-int8-.*v(\d+)_(\d+)\.tar(?:\.bz2|\.gz)?$""").matchEntire(fileName.trim())
            ?.let { listOf(it.groupValues[1].toInt(), it.groupValues[2].toInt()) }

    /** Compares two versions, the shorter padded with zeros: negative if [a] is older than [b]. */
    fun compare(a: List<Int>, b: List<Int>): Int {
        for (i in 0 until maxOf(a.size, b.size)) {
            val d = a.getOrElse(i) { 0 }.compareTo(b.getOrElse(i) { 0 })
            if (d != 0) return d
        }
        return 0
    }

    /** `[20]` -> "20", `[1, 14, 0]` -> "1.14.0". */
    fun dotted(v: List<Int>): String = v.joinToString(".")

    /** `[1, 1]` -> "v1.1" (Kokoro's own way to write it). */
    fun kokoroLabel(v: List<Int>): String = "v" + v.joinToString(".")
}

/**
 * The upstream requests, with the base URL injectable (tests point it at an in-process server). Only GitHub's
 * unauthenticated REST API is used: the latest release of Stockfish and of sherpa-onnx (`releases/latest`
 * leaves out pre-releases and drafts) and the asset list of sherpa-onnx's rolling `tts-models` release, which
 * is where `scripts/fetch_models.sh` gets the Kokoro voice from. Hugging Face is not asked.
 */
object UpstreamSources {
    const val GITHUB_API = "https://api.github.com/"

    /** A release list is JSON of 20 KB (Stockfish) to 1 MB (sherpa-onnx, hundreds of assets); caps are generous and apply inflated. */
    private const val SINGLE_RELEASE_MAX_BYTES = 256 * 1024
    private const val ASSET_LIST_MAX_BYTES = 6 * 1024 * 1024

    val displayOrder: List<UpstreamComponent> = UpstreamComponent.entries

    fun defaults(ours: OurComponents, apiBase: String = GITHUB_API): List<UpstreamSource> {
        val base = if (apiBase.endsWith("/")) apiBase else "$apiBase/"
        return listOf(
            stockfish(ours, "${base}repos/official-stockfish/Stockfish/releases/latest"),
            sherpaOnnx(ours, "${base}repos/k2-fsa/sherpa-onnx/releases/latest"),
            kokoro(ours, "${base}repos/k2-fsa/sherpa-onnx/releases/tags/tts-models"),
        )
    }

    private fun text(bytes: ByteArray) = String(bytes, Charsets.UTF_8)

    fun stockfish(ours: OurComponents, url: String): UpstreamSource {
        val mine = requireNotNull(UpstreamVersions.stockfish(ours.stockfishTag)) { "unreadable own Stockfish tag ${ours.stockfishTag}" }
        return UpstreamSource(UpstreamComponent.STOCKFISH, UpstreamVersions.dotted(mine), url, SINGLE_RELEASE_MAX_BYTES) { body ->
            val latest = requireNotNull(UpstreamVersions.stockfish(JSONObject(text(body)).getString("tag_name"))) { "tag" }
            if (UpstreamVersions.compare(latest, mine) > 0) UpstreamStatus.Newer(UpstreamVersions.dotted(latest)) else UpstreamStatus.UpToDate
        }
    }

    fun sherpaOnnx(ours: OurComponents, url: String): UpstreamSource {
        val mine = requireNotNull(UpstreamVersions.semver(ours.sherpaOnnxVersion)) { "unreadable own sherpa-onnx version" }
        return UpstreamSource(UpstreamComponent.SHERPA_ONNX, UpstreamVersions.dotted(mine), url, ASSET_LIST_MAX_BYTES) { body ->
            val latest = requireNotNull(UpstreamVersions.semver(JSONObject(text(body)).getString("tag_name"))) { "tag" }
            if (UpstreamVersions.compare(latest, mine) > 0) UpstreamStatus.Newer(UpstreamVersions.dotted(latest)) else UpstreamStatus.UpToDate
        }
    }

    /**
     * The Kokoro voice. Newer = a Kokoro int8 build with a higher version than ours is in k2-fsa's `tts-models`
     * release (v1.0, v1.1 for the multi-language ones; ours is the English v0.19). Changed = the version is the
     * same but that release no longer lists our pinned archive, or lists it with another size or SHA-256.
     */
    fun kokoro(ours: OurComponents, url: String): UpstreamSource {
        val mine = requireNotNull(UpstreamVersions.kokoroInt8(ours.voiceTarName)) { "unreadable own voice name ${ours.voiceTarName}" }
        return UpstreamSource(UpstreamComponent.KOKORO_VOICE, UpstreamVersions.kokoroLabel(mine), url, ASSET_LIST_MAX_BYTES) { body ->
            val assets = JSONObject(text(body)).getJSONArray("assets")
            var newest: List<Int>? = null
            var pinnedFound = false
            var pinnedSame = false
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                val name = a.getString("name")
                UpstreamVersions.kokoroInt8(name)?.let { v ->
                    if (newest == null || UpstreamVersions.compare(v, newest!!) > 0) newest = v
                }
                if (name == ours.voiceArchiveName) {
                    pinnedFound = true
                    val digest = a.optString("digest", "").removePrefix("sha256:").lowercase()
                    pinnedSame = a.optLong("size", -1L) == ours.voiceArchiveSizeBytes &&
                        (digest.isEmpty() || digest == ours.voiceArchiveSha256.lowercase())
                }
            }
            val latest = requireNotNull(newest) { "no Kokoro int8 asset listed" }
            when {
                UpstreamVersions.compare(latest, mine) > 0 -> UpstreamStatus.Newer(UpstreamVersions.kokoroLabel(latest))
                !pinnedFound || !pinnedSame -> UpstreamStatus.Changed
                else -> UpstreamStatus.UpToDate
            }
        }
    }
}

/**
 * "Check for updates", the upstream half (A4). Runs only with the user's tap, next to the signed model check
 * ([UpdateChecker]) and never in its way: it has its own coroutine and its own state, so a slow, rate-limited
 * or broken GitHub cannot change, delay or hide the signed result.
 *
 * Every request goes through [ModelDownloader.fetchSmall] (https only, size-capped, redirects checked, the
 * app's User-Agent and nothing else identifying: GitHub sees the IP address, as any website does). The
 * requests are unauthenticated and run in parallel, one per source; each source succeeds or fails alone, and a
 * failure is a row saying so, not an exception.
 */
class UpstreamChecker(
    private val downloader: ModelDownloader,
    private val networkStatus: NetworkStatus,
    val sources: List<UpstreamSource>,
    private val diagnostics: DiagnosticLog? = null,
) {
    private fun log(line: String) {
        diagnostics?.log(ModelSetup.TAG, line)
    }

    /** One row per source, each [UpstreamStatus.Checking]: what the sheet shows the moment the tap lands. */
    fun pendingRows(): List<UpstreamRow> = sources.map { UpstreamRow(it.component, it.ours, UpstreamStatus.Checking) }

    /**
     * Asks every source and returns the finished rows in source order. [onRow] is called (possibly from
     * several threads) as each row finishes, so the sheet can fill in as answers arrive.
     */
    suspend fun check(onRow: (UpstreamRow) -> Unit = {}): List<UpstreamRow> = coroutineScope {
        if (sources.isEmpty()) return@coroutineScope emptyList()
        if (networkStatus.current() == NetworkCost.UNAVAILABLE) {
            log("upstream check: no network, nothing requested")
            return@coroutineScope sources.map { src ->
                UpstreamRow(src.component, src.ours, UpstreamStatus.Failed(UpstreamFailure.NO_INTERNET)).also(onRow)
            }
        }
        log("upstream check: asking host ${ModelDownloader.hostOf(sources.first().url)} for ${sources.size} release lists")
        sources.map { src -> async { checkOne(src).also(onRow) } }.awaitAll()
    }

    private suspend fun checkOne(src: UpstreamSource): UpstreamRow {
        fun row(status: UpstreamStatus) = UpstreamRow(src.component, src.ours, status)
        val status: UpstreamStatus = when (val r = downloader.fetchSmall(src.url, src.maxBytes, acceptGzip = true)) {
            is SmallFetch.Ok -> try {
                src.evaluate(r.bytes)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                log("upstream check: ${src.component.id}: the answer could not be read (${e.javaClass.simpleName})")
                UpstreamStatus.Failed(UpstreamFailure.UNREADABLE)
            }
            is SmallFetch.Failed -> UpstreamStatus.Failed(
                when (r.reason) {
                    SmallFetchFailure.RATE_LIMITED -> UpstreamFailure.RATE_LIMITED
                    SmallFetchFailure.NETWORK, SmallFetchFailure.SERVER, SmallFetchFailure.NOT_FOUND -> UpstreamFailure.UNAVAILABLE
                    SmallFetchFailure.TOO_LARGE, SmallFetchFailure.INSECURE -> UpstreamFailure.UNREADABLE
                },
            )
        }
        log("upstream check: ${src.component.id}: ours ${src.ours}, $status")
        return row(status)
    }
}
