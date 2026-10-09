package net.palaya.chessanalyzer.data.models

import java.net.URI

/**
 * What this app is, for [ModelCompatibility] (docs/MODEL_DOWNLOAD_DESIGN.md §3.3). All compiled in
 * except the two "installed" values, read from the stores when the check runs.
 */
data class AppFacts(
    /** `BuildConfig.VERSION_CODE`. */
    val versionCode: Int,
    /** The NNUE header this Stockfish build parses (`GeneratedNetPins`, from MODELS.lock + nnue_common.h). */
    val netVersion: Long,
    val netArchHash: Long,
    /** `BuildConfig.SHERPA_ONNX_VERSION`. */
    val sherpaOnnxVersion: String,
    /** `VoiceStore.LAYOUT`: the files and paths `NeuralTtsProvider` loads. */
    val voiceLayout: String,
    /** `BuildConfig.MODEL_BASE_URL`, ending in '/': every file URL must start with it. */
    val baseUrl: String,
    /** Debug builds only: http to 10.0.2.2 / 127.0.0.1 / localhost is allowed (the test servers). */
    val allowCleartextLoopback: Boolean,
    /** Full SHA-256 of the active net, null when none is installed. */
    val installedNetSha256: String?,
    /** Full SHA-256 of the installed voice tar (its marker), null when none is installed. */
    val installedVoiceSha256: String?,
    val limits: SizeLimits = SizeLimits.DEFAULT,
    /** C2: the GGUF architecture the wording model must have (`GeneratedModelPins.REPHRASE_MODEL_ARCH`). */
    val rephraseArch: String = "",
    /** C2: the llama.cpp build compiled into librephrase.so (`GeneratedRephraseRuntime.LLAMA_CPP_BUILD`); 0 = none. */
    val llamaCppBuild: Int = 0,
    /** C2: SHA-256 of the installed wording model, null when none is (then no update is offered for it). */
    val installedRephraseSha256: String? = null,
)

/** Plausible sizes per kind (design §3.3). Tests with small stand-in files pass their own. */
data class SizeLimits(
    val netMin: Long,
    val netMax: Long,
    val voiceMin: Long,
    val voiceMax: Long,
    val rephraseMin: Long = 200 * MB,
    val rephraseMax: Long = 3_000 * MB,
) {
    companion object {
        private const val MB = 1_000_000L
        val DEFAULT = SizeLimits(netMin = 50 * MB, netMax = 400 * MB, voiceMin = 20 * MB, voiceMax = 600 * MB)
    }
}

/** Why an entry is not offered. The log names it; the user sees only what IS offered. */
enum class Incompatibility {
    APP_TOO_OLD,
    APP_TOO_NEW,
    INSECURE_URL,
    FOREIGN_URL,
    BAD_FILE_NAME,
    SIZE_OUT_OF_RANGE,
    WRONG_COMPAT_KIND,
    NET_VERSION,
    NET_ARCH,
    VOICE_LAYOUT,
    VOICE_RUNTIME,

    /** C2: a GGUF of another architecture, or a llama.cpp range that excludes this build. */
    REPHRASE_ARCH,
    REPHRASE_RUNTIME,

    /** C2: the wording model is optional; an update is offered only to a phone that has it. */
    NOT_INSTALLED,
}

/** The answer for one entry. */
sealed interface CompatVerdict {
    /** Compatible and different from what is installed: offer it. */
    data object Offer : CompatVerdict

    /** Compatible, and exactly the installed file (same SHA-256). */
    data object AlreadyInstalled : CompatVerdict

    data class Incompatible(val reason: Incompatibility, val detail: String) : CompatVerdict
}

/**
 * Decides whether a manifest entry may be offered (docs/MODEL_DOWNLOAD_DESIGN.md §3.3). Pure: no I/O, no
 * Android. Called by `UpdateChecker` before anything is offered and AGAIN by `ModelUpdateInstaller` right
 * before a download, so an entry that is not compatible can never be downloaded, whatever the UI does.
 *
 *  - `minVersionCode <= versionCode <= (maxVersionCode ?: Int.MAX_VALUE)`.
 *  - The URL starts with the app's base URL and is https (debug: or http to a loopback test host), as a
 *    second fence under the signature.
 *  - Net: `compat.kind == "stockfish-nnue"`, the header `version` and `archHash` equal this engine's pins
 *    (a net the compiled Stockfish cannot parse makes it `exit()`, CLAUDE.md gotcha 1), the file name is
 *    `nn-<12 hex>.nnue` and the SHA-256 starts with those 12 hex, the size is within [SizeLimits].
 *  - Voice: `compat.kind == "sherpa-onnx-kokoro"`, `compat.layout == VoiceStore.LAYOUT`, `runtime.name ==
 *    "sherpa-onnx"` and `runtime.min <= sherpaOnnxVersion <= runtime.max` (numeric dotted compare), the file
 *    name a plain `*.tar` or (D2f) a `*.tar.gz` carrying `tarSha256` + `tarSize`, the size within
 *    [SizeLimits] (for a `.tar.gz` both the file and the tar inside). "Already installed" compares the tar's
 *    SHA-256 with the installed voice's marker.
 *  - Offered iff compatible and not already installed. "Newer" is the publisher's call; the app does not
 *    order versions.
 */
object ModelCompatibility {

    private val NET_NAME = Regex("""nn-([0-9a-f]{12})\.nnue""")
    private val TAR_NAME = Regex("""[A-Za-z0-9._-]{1,120}\.tar(\.gz)?""")
    private val LOOPBACK = setOf("10.0.2.2", "127.0.0.1", "localhost")

    fun evaluate(entry: ManifestEntry, facts: AppFacts): CompatVerdict {
        if (entry.minVersionCode > facts.versionCode) {
            return CompatVerdict.Incompatible(Incompatibility.APP_TOO_OLD, "needs versionCode >= ${entry.minVersionCode}, app is ${facts.versionCode}")
        }
        val max = entry.maxVersionCode ?: Int.MAX_VALUE
        if (facts.versionCode > max) {
            return CompatVerdict.Incompatible(Incompatibility.APP_TOO_NEW, "for versionCode <= $max, app is ${facts.versionCode}")
        }
        urlProblem(entry.url, facts)?.let { return it }

        return when (entry.kind) {
            ModelKind.NET -> evaluateNet(entry, facts)
            ModelKind.VOICE -> evaluateVoice(entry, facts)
            ModelKind.REPHRASE -> evaluateRephrase(entry, facts)
        }
    }

    private val GGUF_NAME = Regex("""[A-Za-z0-9._-]{1,120}\.gguf""")
    private val LLAMA_TAG = Regex("""b(\d{1,7})""")

    /**
     * C2 (docs/LLM_REPHRASE_DESIGN.md §1.4): `compat.kind == "gguf"` with this build's architecture, a `llama.cpp`
     * runtime range (`bNNNN` tags, inclusive) holding the compiled build, a `*.gguf` name, a size in range; and only
     * for a phone that has the wording model installed (an optional 1.1 GB file is never pushed by an update).
     */
    private fun evaluateRephrase(entry: ManifestEntry, facts: AppFacts): CompatVerdict {
        val compat = entry.compat as? ModelCompat.Gguf
            ?: return CompatVerdict.Incompatible(Incompatibility.WRONG_COMPAT_KIND, "compat.kind ${entry.compat.kind}")
        if (compat.arch != facts.rephraseArch) {
            return CompatVerdict.Incompatible(Incompatibility.REPHRASE_ARCH, "GGUF architecture ${compat.arch}, app ${facts.rephraseArch}")
        }
        val rt = entry.runtime
        val lo = rt?.min?.let { LLAMA_TAG.matchEntire(it)?.groupValues?.get(1)?.toInt() }
        val hi = rt?.max?.let { LLAMA_TAG.matchEntire(it)?.groupValues?.get(1)?.toInt() }
        if (rt == null || rt.name != "llama.cpp" || lo == null || hi == null || facts.llamaCppBuild !in lo..hi) {
            return CompatVerdict.Incompatible(
                Incompatibility.REPHRASE_RUNTIME,
                "runtime ${rt?.name} ${rt?.min}..${rt?.max}, app has llama.cpp b${facts.llamaCppBuild}",
            )
        }
        if (!GGUF_NAME.matches(entry.fileName) || !entry.url.endsWith("/" + entry.fileName)) {
            return CompatVerdict.Incompatible(Incompatibility.BAD_FILE_NAME, "wording model file ${entry.fileName}")
        }
        if (entry.sizeBytes !in facts.limits.rephraseMin..facts.limits.rephraseMax) {
            return CompatVerdict.Incompatible(Incompatibility.SIZE_OUT_OF_RANGE, "wording model size ${entry.sizeBytes}")
        }
        val installed = facts.installedRephraseSha256
            ?: return CompatVerdict.Incompatible(Incompatibility.NOT_INSTALLED, "the wording model is not installed")
        return if (installed == entry.sha256) CompatVerdict.AlreadyInstalled else CompatVerdict.Offer
    }

    private fun urlProblem(url: String, facts: AppFacts): CompatVerdict.Incompatible? {
        val uri = runCatching { URI(url) }.getOrNull()
        val scheme = uri?.scheme?.lowercase()
        val host = uri?.host?.lowercase()
        val secure = when {
            uri == null || host.isNullOrEmpty() -> false
            scheme == "https" -> true
            scheme == "http" -> facts.allowCleartextLoopback && host in LOOPBACK
            else -> false
        }
        if (!secure) return CompatVerdict.Incompatible(Incompatibility.INSECURE_URL, "not an https URL")
        if (!url.startsWith(facts.baseUrl) || url.contains("/../") || url.contains("?") || url.contains("#")) {
            return CompatVerdict.Incompatible(Incompatibility.FOREIGN_URL, "not under the app's base URL")
        }
        return null
    }

    private fun evaluateNet(entry: ManifestEntry, facts: AppFacts): CompatVerdict {
        val compat = entry.compat as? ModelCompat.StockfishNnue
            ?: return CompatVerdict.Incompatible(Incompatibility.WRONG_COMPAT_KIND, "compat.kind ${entry.compat.kind}")
        if (compat.version != facts.netVersion) {
            return CompatVerdict.Incompatible(Incompatibility.NET_VERSION, "NNUE version ${hex(compat.version)}, engine ${hex(facts.netVersion)}")
        }
        if (compat.archHash != facts.netArchHash) {
            return CompatVerdict.Incompatible(Incompatibility.NET_ARCH, "architecture ${hex(compat.archHash)}, engine ${hex(facts.netArchHash)}")
        }
        val m = NET_NAME.matchEntire(entry.fileName)
        if (m == null || !entry.sha256.startsWith(m.groupValues[1]) || !entry.url.endsWith("/" + entry.fileName)) {
            return CompatVerdict.Incompatible(Incompatibility.BAD_FILE_NAME, "net name ${entry.fileName} does not match its SHA-256 or URL")
        }
        if (entry.sizeBytes !in facts.limits.netMin..facts.limits.netMax) {
            return CompatVerdict.Incompatible(Incompatibility.SIZE_OUT_OF_RANGE, "net size ${entry.sizeBytes}")
        }
        return if (facts.installedNetSha256 == entry.sha256) CompatVerdict.AlreadyInstalled else CompatVerdict.Offer
    }

    private fun evaluateVoice(entry: ManifestEntry, facts: AppFacts): CompatVerdict {
        val compat = entry.compat as? ModelCompat.SherpaKokoro
            ?: return CompatVerdict.Incompatible(Incompatibility.WRONG_COMPAT_KIND, "compat.kind ${entry.compat.kind}")
        if (compat.layout != facts.voiceLayout) {
            return CompatVerdict.Incompatible(Incompatibility.VOICE_LAYOUT, "layout ${compat.layout}, app ${facts.voiceLayout}")
        }
        val rt = entry.runtime
        if (rt == null || rt.name != "sherpa-onnx" || !versionInRange(facts.sherpaOnnxVersion, rt.min, rt.max)) {
            return CompatVerdict.Incompatible(
                Incompatibility.VOICE_RUNTIME,
                "runtime ${rt?.name} ${rt?.min}..${rt?.max}, app has sherpa-onnx ${facts.sherpaOnnxVersion}",
            )
        }
        if (!TAR_NAME.matches(entry.fileName) || !entry.url.endsWith("/" + entry.fileName)) {
            return CompatVerdict.Incompatible(Incompatibility.BAD_FILE_NAME, "voice file ${entry.fileName}")
        }
        // A .tar.gz must say what its tar is (the unpack check and the marker); a plain tar is its own tar.
        val tarPinsOk = if (entry.isGzip) {
            entry.tarSha256 != null && entry.tarSizeBytes != null
        } else {
            (entry.tarSha256 == null || entry.tarSha256 == entry.sha256) && (entry.tarSizeBytes == null || entry.tarSizeBytes == entry.sizeBytes)
        }
        if (!tarPinsOk) {
            return CompatVerdict.Incompatible(Incompatibility.BAD_FILE_NAME, "voice file ${entry.fileName}: tarSha256/tarSize missing or inconsistent")
        }
        val range = facts.limits.voiceMin..facts.limits.voiceMax
        if (entry.sizeBytes !in range || entry.unpackedSizeBytes !in range) {
            return CompatVerdict.Incompatible(Incompatibility.SIZE_OUT_OF_RANGE, "voice size ${entry.sizeBytes} (tar ${entry.unpackedSizeBytes})")
        }
        return if (facts.installedVoiceSha256 == entry.unpackedSha256) CompatVerdict.AlreadyInstalled else CompatVerdict.Offer
    }

    /** Numeric dotted compare, inclusive: "1.13.8" in "1.13.8".."1.13.10" is true; any non-number part is false. */
    fun versionInRange(version: String, min: String, max: String): Boolean {
        val v = parseVersion(version) ?: return false
        val lo = parseVersion(min) ?: return false
        val hi = parseVersion(max) ?: return false
        return compareVersions(lo, v) <= 0 && compareVersions(v, hi) <= 0
    }

    fun compareVersions(a: String, b: String): Int? {
        val x = parseVersion(a) ?: return null
        val y = parseVersion(b) ?: return null
        return compareVersions(x, y)
    }

    private fun parseVersion(s: String): List<Long>? {
        val parts = s.trim().split('.')
        if (parts.isEmpty() || parts.size > 6) return null
        return parts.map { p -> if (Regex("""\d{1,9}""").matches(p)) p.toLong() else return null }
    }

    private fun compareVersions(a: List<Long>, b: List<Long>): Int {
        for (i in 0 until maxOf(a.size, b.size)) {
            val c = (a.getOrElse(i) { 0L }).compareTo(b.getOrElse(i) { 0L })
            if (c != 0) return c
        }
        return 0
    }

    private fun hex(v: Long): String = "0x" + v.toString(16).padStart(8, '0')
}
