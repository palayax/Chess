package net.palaya.chessanalyzer.rephrase

import net.palaya.chessanalyzer.core.text.ClaimChecker
import net.palaya.chessanalyzer.core.text.RephraseSurface
import java.io.File
import java.security.MessageDigest

/**
 * The rephrase cache (docs/LLM_REPHRASE_DESIGN.md §6.3): `filesDir/rephrase/<modelId>/<key>.txt`, one file per
 * (rephraser, prompt version, surface, original text). The file holds `A\n<text>` (accepted), `U` (the model
 * kept the original) or `R <reason>` (rejected: kept so a rejected text is not retried on every visit; a
 * prompt-version or model change moves every key, so everything is retried then).
 *
 * Writes are atomic (temp file + rename). One directory per model id, so a model update purges the old
 * model's entries with one `deleteRecursively` ([clearExcept]). The cache is read only when the setting is
 * on; turning it off shows the originals at once.
 */
class RephraseCache(private val root: File) {

    sealed interface Entry {
        data class Accepted(val text: String) : Entry
        data object Unchanged : Entry
        data class Rejected(val reason: String) : Entry
    }

    /** `qwen2.5-1.5b-instruct-q4_k_m@p1` -> `qwen2.5-1.5b-instruct-q4_k_m`. */
    private fun modelDir(rephraserId: String): File =
        File(root, rephraserId.substringBefore('@').replace(Regex("[^A-Za-z0-9._-]"), "_"))

    fun keyFor(rephraserId: String, surface: RephraseSurface, text: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$SCHEMA|$rephraserId|$surface|$text".toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }.take(24)
    }

    private fun fileFor(rephraserId: String, surface: RephraseSurface, text: String): File =
        File(modelDir(rephraserId), keyFor(rephraserId, surface, text) + ".txt")

    fun get(rephraserId: String, surface: RephraseSurface, text: String): Entry? {
        val f = fileFor(rephraserId, surface, text)
        val body = try {
            if (!f.isFile) return null
            f.readText(Charsets.UTF_8)
        } catch (e: Exception) {
            return null
        }
        return when {
            body.startsWith("A\n") -> body.substring(2).takeIf { it.isNotBlank() }?.let { Entry.Accepted(it) }
            body == "U" -> Entry.Unchanged
            body.startsWith("R ") -> Entry.Rejected(body.substring(2))
            else -> null
        }
    }

    fun put(rephraserId: String, surface: RephraseSurface, text: String, entry: Entry) {
        val f = fileFor(rephraserId, surface, text)
        f.parentFile?.mkdirs()
        val body = when (entry) {
            is Entry.Accepted -> "A\n" + entry.text
            Entry.Unchanged -> "U"
            is Entry.Rejected -> "R " + entry.reason
        }
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(body, Charsets.UTF_8)
        if (!tmp.renameTo(f)) {
            f.delete()
            if (!tmp.renameTo(f)) tmp.delete()
        }
    }

    /**
     * The accepted rewordings among [texts], original -> reworded. An accepted entry is re-checked against
     * its original before use (a cache file cannot smuggle in a text the checker never saw).
     */
    fun accepted(rephraserId: String, surface: RephraseSurface, texts: Collection<String>): Map<String, String> {
        val out = HashMap<String, String>()
        for (t in texts) {
            val e = get(rephraserId, surface, t) as? Entry.Accepted ?: continue
            if (ClaimChecker.check(t, e.text, surface) == ClaimChecker.Verdict.Accepted) out[t] = e.text
        }
        return out
    }

    fun totalSizeBytes(): Long = root.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    /** The user's "Clear" (Settings). */
    fun clear(): Boolean = !root.exists() || root.deleteRecursively()

    /** After a model change: every model's folder but [rephraserId]'s goes. */
    fun clearExcept(rephraserId: String) {
        val keep = modelDir(rephraserId).name
        root.listFiles()?.filter { it.isDirectory && it.name != keep }?.forEach { it.deleteRecursively() }
    }

    companion object {
        const val DIR_NAME = "rephrase/cache"

        /** Bumped only if the file format changes. */
        const val SCHEMA = 1
    }
}
