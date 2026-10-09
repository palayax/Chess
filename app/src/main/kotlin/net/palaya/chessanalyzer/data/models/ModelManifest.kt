package net.palaya.chessanalyzer.data.models

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** The model ids the app knows (docs/MODEL_DOWNLOAD_DESIGN.md §3.2; REPHRASE: C2). Any other id is ignored. */
enum class ModelKind(val id: String) {
    NET("engine-net"),
    VOICE("voice-kokoro-en"),

    /** C2: the optional wording model (docs/LLM_REPHRASE_DESIGN.md §1.4), a GGUF for the llama.cpp in :rephrase. */
    REPHRASE("rephrase-qwen");

    companion object {
        fun fromId(id: String): ModelKind? = entries.firstOrNull { it.id == id }
    }
}

/** `compat` of one entry: what the file must be for this app to be able to use it. */
sealed interface ModelCompat {
    val kind: String

    /** `{"kind":"stockfish-nnue","version":"0x6a448afa","archHash":"a85b2205","engineTag":"sf_19"}`. */
    data class StockfishNnue(val version: Long, val archHash: Long, val engineTag: String?) : ModelCompat {
        override val kind: String get() = KIND

        companion object {
            const val KIND = "stockfish-nnue"
        }
    }

    /** `{"kind":"sherpa-onnx-kokoro","layout":"kokoro-v0_19"}`. */
    data class SherpaKokoro(val layout: String) : ModelCompat {
        override val kind: String get() = KIND

        companion object {
            const val KIND = "sherpa-onnx-kokoro"
        }
    }

    /** C2: `{"kind":"gguf","arch":"qwen2"}`: a GGUF whose general.architecture is [arch]. */
    data class Gguf(val arch: String) : ModelCompat {
        override val kind: String get() = KIND

        companion object {
            const val KIND = "gguf"
        }
    }

    /** A kind this app does not know: the entry is parsed but never compatible. */
    data class Other(override val kind: String) : ModelCompat
}

/** `runtime` of a voice entry: the sherpa-onnx versions (inclusive, dotted numbers) it was built for. */
data class ModelRuntime(val name: String, val min: String, val max: String)

/** One file the manifest offers (one element of `models`). */
data class ManifestEntry(
    val kind: ModelKind,
    val displayName: String,
    val version: String,
    val fileName: String,
    val url: String,
    val sizeBytes: Long,
    val sha256: String,
    val minVersionCode: Int,
    val maxVersionCode: Int?,
    val compat: ModelCompat,
    val runtime: ModelRuntime?,
    /** Voice `.tar.gz` entries (D2f): the SHA-256 of the tar inside (`tarSha256`), what the marker records. */
    val tarSha256: String? = null,
    /** Voice `.tar.gz` entries (D2f): the size of the tar inside (`tarSize`). */
    val tarSizeBytes: Long? = null,
) {
    /** What [ModelDownloader] needs: URL, name, size and the full SHA-256 from the signed manifest. */
    fun toFileSpec(): ModelFileSpec = ModelFileSpec(url = url, fileName = fileName, sizeBytes = sizeBytes, sha256 = sha256)

    /** True for a gzipped voice archive (`*.tar.gz`), which must carry [tarSha256] and [tarSizeBytes]. */
    val isGzip: Boolean get() = fileName.endsWith(".tar.gz")

    /** The tar's SHA-256: [tarSha256] for a `.tar.gz`, the file's own [sha256] for a plain tar. */
    val unpackedSha256: String get() = tarSha256 ?: sha256

    /** The tar's size: [tarSizeBytes] for a `.tar.gz`, the file's own [sizeBytes] for a plain tar. */
    val unpackedSizeBytes: Long get() = tarSizeBytes ?: sizeBytes
}

/**
 * The signed upgrade manifest, `models.json` (docs/MODEL_DOWNLOAD_DESIGN.md §3.2), as built (D2e):
 *
 * ```
 * { "schemaVersion": 1, "generatedAt": "2026-10-07T12:00:00Z",
 *   "models": [ { "id": "engine-net" | "voice-kokoro-en", "displayName": "...", "version": "...",
 *                 "fileName": "...", "url": "https://...", "size": 98511183, "sha256": "<64 hex>",
 *                 "minVersionCode": 1, "maxVersionCode": null,
 *                 "compat": { "kind": "stockfish-nnue", "version": "0x6a448afa", "archHash": "a85b2205", "engineTag": "sf_19" }
 *                         | { "kind": "sherpa-onnx-kokoro", "layout": "kokoro-v0_19" },
 *                 "runtime": { "name": "sherpa-onnx", "min": "1.13.8", "max": "1.13.8" }   (voice only),
 *                 "tarSha256": "<64 hex>", "tarSize": 158269440   (voice .tar.gz only, D2f) } ] }
 * ```
 *
 * Since D2f the voice is published as `kokoro-int8-en-v0_19.tar.gz`: `size`/`sha256` are the compressed
 * file's (what is downloaded and verified), `tarSize`/`tarSha256` the tar's (what the unpacked stream is
 * checked against and what the installed voice's marker records, so "already installed" compares tars).
 *
 * It is parsed ONLY after its signature has been verified over the exact bytes ([ManifestSignature]).
 * Rules: `schemaVersion` must be [SCHEMA_VERSION]; an entry with an unknown `id` is skipped without
 * looking at its other fields; unknown fields are ignored; a known entry with a missing or malformed
 * required field rejects the whole manifest (a publisher's mistake must not half-apply). Hex values accept
 * an optional `0x`. `sha256` is lower-cased. Nothing here decides compatibility: that is
 * [ModelCompatibility], which also re-checks the URL, sizes and names.
 */
data class ModelManifest(
    val schemaVersion: Int,
    val generatedAt: String?,
    val entries: List<ManifestEntry>,
    /** Ids that were present but unknown to this app (for the log). */
    val ignoredIds: List<String>,
) {
    companion object {
        const val SCHEMA_VERSION = 1

        /** The manifest is small; anything bigger is refused before it is read (the downloader's cap). */
        const val MAX_BYTES = 64 * 1024

        /** A DER ECDSA P-256 signature is at most 72 bytes; a little room for odd encoders. */
        const val MAX_SIGNATURE_BYTES = 512

        /** Parses verified manifest bytes. Never throws. */
        fun parse(bytes: ByteArray): ManifestParse = try {
            parseOrThrow(String(bytes, Charsets.UTF_8))
        } catch (e: ManifestFormatException) {
            ManifestParse.Invalid(e.message ?: "malformed")
        } catch (e: JSONException) {
            ManifestParse.Invalid("not JSON: ${e.message}")
        } catch (e: RuntimeException) {
            ManifestParse.Invalid("unreadable: ${e.javaClass.simpleName}")
        }

        private fun parseOrThrow(text: String): ManifestParse {
            val root = JSONObject(text)
            val schema = root.requireInt("schemaVersion")
            if (schema != SCHEMA_VERSION) return ManifestParse.Invalid("unsupported schemaVersion $schema")
            val generatedAt = root.optStringOrNull("generatedAt")
            val models = root.opt("models") as? JSONArray ?: fail("models is missing or not an array")
            val entries = ArrayList<ManifestEntry>()
            val ignored = ArrayList<String>()
            for (i in 0 until models.length()) {
                val o = models.opt(i) as? JSONObject ?: fail("models[$i] is not an object")
                val id = o.requireString("id")
                val kind = ModelKind.fromId(id)
                if (kind == null) {
                    ignored += id
                    continue
                }
                entries += parseEntry(kind, o, "models[$i]")
            }
            return ManifestParse.Valid(ModelManifest(schema, generatedAt, entries, ignored))
        }

        private fun parseEntry(kind: ModelKind, o: JSONObject, where: String): ManifestEntry {
            val compatObj = o.opt("compat") as? JSONObject ?: fail("$where.compat is missing")
            val compat = when (val k = compatObj.requireString("kind")) {
                ModelCompat.StockfishNnue.KIND -> ModelCompat.StockfishNnue(
                    version = parseHex32(compatObj.requireString("version"), "$where.compat.version"),
                    archHash = parseHex32(compatObj.requireString("archHash"), "$where.compat.archHash"),
                    engineTag = compatObj.optStringOrNull("engineTag"),
                )
                ModelCompat.SherpaKokoro.KIND -> ModelCompat.SherpaKokoro(layout = compatObj.requireString("layout"))
                ModelCompat.Gguf.KIND -> ModelCompat.Gguf(arch = compatObj.requireString("arch"))
                else -> ModelCompat.Other(k)
            }
            val runtime = (o.opt("runtime") as? JSONObject)?.let {
                ModelRuntime(name = it.requireString("name"), min = it.requireString("min"), max = it.requireString("max"))
            }
            if (kind == ModelKind.VOICE && runtime == null) fail("$where.runtime is required for a voice")
            if (kind == ModelKind.REPHRASE && runtime == null) fail("$where.runtime is required for the wording model")
            val sha = o.requireString("sha256").lowercase()
            if (!Regex("[0-9a-f]{64}").matches(sha)) fail("$where.sha256 is not 64 hex digits")
            val size = o.requireLong("size")
            if (size <= 0) fail("$where.size must be positive")
            val maxVc = if (!o.has("maxVersionCode") || o.isNull("maxVersionCode")) null else o.requireInt("maxVersionCode")
            // Optional; when present they must be well formed (ModelCompatibility decides whether they are required).
            val tarSha = o.optStringOrNull("tarSha256")?.lowercase()?.also {
                if (!Regex("[0-9a-f]{64}").matches(it)) fail("$where.tarSha256 is not 64 hex digits")
            }
            val tarSize = if (!o.has("tarSize") || o.isNull("tarSize")) null else o.requireLong("tarSize").also {
                if (it <= 0) fail("$where.tarSize must be positive")
            }
            return ManifestEntry(
                kind = kind,
                displayName = o.requireString("displayName"),
                version = o.requireString("version"),
                fileName = o.requireString("fileName"),
                url = o.requireString("url"),
                sizeBytes = size,
                sha256 = sha,
                minVersionCode = o.requireInt("minVersionCode"),
                maxVersionCode = maxVc,
                compat = compat,
                runtime = runtime,
                tarSha256 = tarSha,
                tarSizeBytes = tarSize,
            )
        }

        /** "0x6a448afa" or "a85b2205": eight hex digits, as an unsigned 32-bit value. */
        fun parseHex32(text: String, what: String = "value"): Long {
            val hex = text.trim().removePrefix("0x").removePrefix("0X")
            if (!Regex("[0-9a-fA-F]{1,8}").matches(hex)) fail("$what is not a 32-bit hex number: $text")
            return hex.toLong(16)
        }

        private fun fail(message: String): Nothing = throw ManifestFormatException(message)

        private fun JSONObject.requireString(key: String): String {
            if (!has(key) || isNull(key)) fail("$key is missing")
            val v = get(key) as? String ?: fail("$key is not a string")
            if (v.isBlank()) fail("$key is empty")
            return v
        }

        private fun JSONObject.optStringOrNull(key: String): String? =
            if (!has(key) || isNull(key)) null else get(key) as? String

        private fun JSONObject.requireLong(key: String): Long {
            if (!has(key) || isNull(key)) fail("$key is missing")
            val v = get(key)
            // Int or Long only: org.json gives a Double (Android) or a BigDecimal (the JVM build) for 1.5.
            if (v !is Int && v !is Long) fail("$key is not a whole number")
            return (v as Number).toLong()
        }

        private fun JSONObject.requireInt(key: String): Int {
            val v = requireLong(key)
            if (v < Int.MIN_VALUE || v > Int.MAX_VALUE) fail("$key is out of range")
            return v.toInt()
        }
    }
}

/** The result of [ModelManifest.parse]. */
sealed interface ManifestParse {
    data class Valid(val manifest: ModelManifest) : ManifestParse
    data class Invalid(val reason: String) : ManifestParse
}

private class ManifestFormatException(message: String) : RuntimeException(message)
