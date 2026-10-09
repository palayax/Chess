package net.palaya.chessanalyzer.rephrase

import net.palaya.chessanalyzer.data.models.GeneratedModelPins
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.Properties

/** The downloaded wording model failed its checks (hash, size or the GGUF structure). */
class RephraseModelDamagedException(message: String) : IOException(message)

/**
 * The optional wording model on disk (docs/LLM_REPHRASE_DESIGN.md §1.4, §6.4): `filesDir/rephrase/models/<file>.gguf`.
 *
 *  - **Pins**: the compiled ones (`GeneratedModelPins.REPHRASE_*`, from MODELS.lock), or an update's identity in
 *    `models/active.properties` (written only by the activator after a trial).
 *  - **Install** ([installVerified]): the downloader has checked size and SHA-256; the GGUF structure is checked
 *    here in Kotlin ([GgufHeader]) before the file is moved into place, so llama.cpp never parses a file that did
 *    not pass. [verifiedFileOrNull] re-hashes the file once per process before the first load.
 *  - **Wanted** (`rephrase/wanted`): the user asked for the model (the Setup offer, or Settings). Setup downloads
 *    it only while this is set; Cancel and Remove clear it.
 *  - **Load journal** (`rephrase/loading.json`, [LlamaRephraser.LoadJournal]): written before a load, cleared after
 *    the first successful completion. Found at start ([recoverOnStartup]) it means the process died loading or
 *    generating: the file is re-hashed before its next use, and a second such crash in a row turns the feature off
 *    (it could be the low-memory killer rather than a bad file, so the 1.1 GB file is not deleted on one crash).
 */
class RephraseModelStore(
    filesDir: File,
    val compiled: Pins = Pins.compiled(),
) : net.palaya.chessanalyzer.rephrase.LlamaRephraser.LoadJournal {

    data class Pins(
        val id: String,
        val fileName: String,
        val sizeBytes: Long,
        val sha256: String,
        val arch: String,
        val releaseTag: String,
    ) {
        companion object {
            fun compiled() = Pins(
                id = GeneratedModelPins.REPHRASE_MODEL_ID,
                fileName = GeneratedModelPins.REPHRASE_MODEL_FILE,
                sizeBytes = GeneratedModelPins.REPHRASE_MODEL_SIZE_BYTES,
                sha256 = GeneratedModelPins.REPHRASE_MODEL_SHA256,
                arch = GeneratedModelPins.REPHRASE_MODEL_ARCH,
                releaseTag = GeneratedModelPins.REPHRASE_RELEASE_TAG,
            )
        }
    }

    val root = File(filesDir, ROOT_NAME)
    val modelsDir = File(root, "models")
    private val activeFile = File(modelsDir, "active.properties")
    private val wantedMarker = File(root, "wanted")
    private val journalFile = File(root, "loading.json")
    private val crashFile = File(root, "crashes")

    @Volatile private var verifiedOnce: String? = null

    /** The model in use: an update's record when its file is there, else the compiled pins. */
    fun activePins(): Pins {
        if (!activeFile.isFile) return compiled
        return try {
            val p = Properties().apply { activeFile.inputStream().use { load(it) } }
            val pins = Pins(
                id = p.getProperty("id"), fileName = p.getProperty("file"), sizeBytes = p.getProperty("size").toLong(),
                sha256 = p.getProperty("sha256"), arch = p.getProperty("arch"), releaseTag = p.getProperty("tag", compiled.releaseTag),
            )
            if (File(modelsDir, pins.fileName).isFile) pins else compiled
        } catch (e: Exception) {
            compiled
        }
    }

    /** Only the activator calls this, after a trial passed (or to roll back: null = the compiled pins). */
    fun setActivePins(pins: Pins?) {
        if (pins == null || pins == compiled) {
            activeFile.delete()
            return
        }
        modelsDir.mkdirs()
        val p = Properties().apply {
            setProperty("id", pins.id); setProperty("file", pins.fileName); setProperty("size", pins.sizeBytes.toString())
            setProperty("sha256", pins.sha256); setProperty("arch", pins.arch); setProperty("tag", pins.releaseTag)
        }
        val tmp = File(modelsDir, "active.properties.tmp")
        FileOutputStream(tmp).use { out -> p.store(out, "C2 wording model in use"); out.fd.sync() }
        if (!tmp.renameTo(activeFile)) {
            activeFile.delete()
            if (!tmp.renameTo(activeFile)) throw IOException("could not write ${activeFile.path}")
        }
        verifiedOnce = null
    }

    fun fileFor(name: String): File = File(modelsDir, name)

    /** The first-run download's part file (setup's resumable `.part`). */
    val partFile: File get() = File(modelsDir, compiled.fileName + ".part")

    /** An update's part file, by its own name. */
    fun updatePartFile(fileName: String): File = File(modelsDir, "$fileName.part")

    /** Cheap: a file of the active pins' size is in place (no hashing). */
    fun installedFileOrNull(): File? {
        val pins = activePins()
        val f = File(modelsDir, pins.fileName)
        return if (f.isFile && f.length() == pins.sizeBytes) f else null
    }

    fun isInstalled(): Boolean = installedFileOrNull() != null

    /** The installed model's SHA-256 as pinned (for "already installed"), or null. */
    fun installedSha256(): String? = installedFileOrNull()?.let { activePins().sha256 }

    /**
     * The installed file, verified by size and full SHA-256 once per process (about 5-10 s on a phone for 1.1 GB;
     * never on the main thread) and by its GGUF structure. A file that fails is deleted: "Download again".
     */
    fun verifiedFileOrNull(): File? {
        val pins = activePins()
        val f = installedFileOrNull() ?: return null
        if (verifiedOnce == pins.sha256) return f
        val sha = sha256(f)
        val structural = runCatching { GgufHeader.check(f, pins.arch) }
        if (sha != pins.sha256 || structural.isFailure) {
            f.delete()
            if (pins != compiled) setActivePins(null)
            return null
        }
        verifiedOnce = pins.sha256
        return f
    }

    /**
     * Moves the downloaded [part] (size and SHA-256 already verified against the pins by the downloader) into place
     * after the structural check. A part that fails is deleted and [RephraseModelDamagedException] thrown.
     */
    fun installVerified(part: File, pins: Pins = compiled): File {
        try {
            GgufHeader.check(part, pins.arch)
        } catch (e: IOException) {
            part.delete()
            throw RephraseModelDamagedException("the wording model is not a usable GGUF: ${e.message}")
        }
        modelsDir.mkdirs()
        val dest = File(modelsDir, pins.fileName)
        if (dest.exists() && !dest.delete()) throw IOException("could not replace ${dest.path}")
        if (!part.renameTo(dest)) throw IOException("could not move the wording model into place")
        verifiedOnce = pins.sha256 // the downloader hashed exactly these bytes
        return dest
    }

    fun deleteParts() {
        modelsDir.listFiles { f -> f.name.endsWith(".part") }?.forEach { it.delete() }
    }

    /** Settings "Remove the model": the file, its parts, the update record, the journal. The cache is separate. */
    fun remove() {
        modelsDir.listFiles()?.forEach { it.delete() }
        journalFile.delete()
        crashFile.delete()
        setWanted(false)
        verifiedOnce = null
    }

    fun totalModelBytes(): Long = modelsDir.listFiles()?.sumOf { it.length() } ?: 0L

    // ---- the user's request ----

    fun isWanted(): Boolean = wantedMarker.isFile

    fun setWanted(wanted: Boolean) {
        if (wanted) {
            root.mkdirs()
            if (!wantedMarker.isFile) wantedMarker.writeText("1")
        } else {
            wantedMarker.delete()
        }
    }

    // ---- the load journal (LlamaRephraser.LoadJournal) ----

    override fun begin() {
        root.mkdirs()
        journalFile.writeText("{\"model\":\"${activePins().id}\",\"at\":${System.currentTimeMillis()}}")
    }

    override fun clear() {
        journalFile.delete()
        crashFile.delete()
    }

    /** What [recoverOnStartup] found. */
    enum class Recovery { NOTHING, RECHECK, TURN_OFF }

    /**
     * Runs once at start, before anything can load the model. A journal left behind means the process died in a
     * load or a generation: re-hash the file before its next use, and after two such deaths in a row tell the
     * caller to turn the feature off.
     */
    fun recoverOnStartup(): Recovery {
        if (!journalFile.isFile) return Recovery.NOTHING
        journalFile.delete()
        verifiedOnce = null
        val crashes = (runCatching { crashFile.readText().trim().toInt() }.getOrDefault(0)) + 1
        root.mkdirs()
        crashFile.writeText(crashes.toString())
        return if (crashes >= 2) Recovery.TURN_OFF else Recovery.RECHECK
    }

    companion object {
        const val ROOT_NAME = "rephrase"

        fun sha256(f: File): String {
            val d = MessageDigest.getInstance("SHA-256")
            f.inputStream().use { input ->
                val buf = ByteArray(1 shl 20)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    d.update(buf, 0, n)
                }
            }
            return d.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
