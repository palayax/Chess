package net.palaya.chessanalyzer.engine

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/** No verified NNUE net is installed: the first-run download (Setup) has not finished. */
class NetNotInstalledException :
    IOException("The engine's net is not installed yet: finish setting up (download) first")

/**
 * The first 12 bytes of an NNUE file: the file [version], Stockfish's architecture hash ([archHash])
 * and the length of the description string that follows ([descriptionLength]). All little-endian
 * uint32, read as non-negative longs. Layout from `nnue/network.cpp` `read_header`.
 */
data class NetHeader(val version: Long, val archHash: Long, val descriptionLength: Long) {
    /** True when this header is one the engine pinned in [pins] can parse (version + architecture). */
    fun matches(pins: NetPins): Boolean =
        version == pins.version && archHash == pins.archHash && descriptionLength < MAX_DESCRIPTION_BYTES

    companion object {
        /** A real net's description is ~100 bytes; anything past this is not a net (design §4.2). */
        const val MAX_DESCRIPTION_BYTES = 1024L
        const val SIZE_BYTES = 12
    }
}

/**
 * What a verified net must be. [COMPILED] is this build's (from `vendor/models/MODELS.lock` and
 * evaluate.h); tests pass their own so they never need the real 98.5 MB file.
 */
data class NetPins(
    val fileName: String,
    val sizeBytes: Long,
    val sha256: String,
    val archHash: Long,
    val version: Long,
) {
    companion object {
        val COMPILED = NetPins(
            fileName = GeneratedNetworkConstants.EVAL_FILE_DEFAULT_NAME,
            sizeBytes = GeneratedNetPins.NET_SIZE_BYTES,
            sha256 = GeneratedNetPins.NET_SHA256,
            archHash = GeneratedNetPins.NET_ARCH_HASH,
            version = GeneratedNetPins.NET_VERSION,
        )
    }
}

/**
 * Which net the engine uses: its file name under `nets/`, its size and its full SHA-256. Without an
 * update this is the compiled pin ([NetStore.compiledIdentity]); a net installed by "Check for updates"
 * (D2e, docs/MODEL_DOWNLOAD_DESIGN.md §4.2) is recorded in `nets/active.properties` with the values from
 * the signed manifest. Either way [NetStore.verifiedNetOrNull] checks the file against exactly these.
 */
data class ActiveNet(val fileName: String, val sizeBytes: Long, val sha256: String) {
    /** The 12 hex digits the name encodes (the eval-cache folder), or null for a malformed name. */
    val prefix: String? get() = NetStore.prefixOf(fileName)
}

/**
 * Where the Stockfish NNUE net lives on the phone: `filesDir/nets/<name>` (docs/MODEL_DOWNLOAD_DESIGN.md
 * §2.1). The net is no longer in the APK (D2a): it is downloaded once by the app's `ModelSetup` into
 * [partFileFor], verified there (size + full SHA-256 against the build-time pins in [GeneratedNetPins]),
 * and handed over with [installVerified]. This class does no network I/O.
 *
 * Why a real file: Stockfish opens the net with `std::ifstream(path)` and calls `exit(EXIT_FAILURE)` on
 * any failure (CLAUDE.md engine gotcha 1). Every path this class hands out has been verified, and the
 * `setEvalFile()`/`analyze()` guards in [StockfishEngine] still apply on top.
 *
 * The net name is not hardcoded: [NET_FILENAME] comes from `vendor/Stockfish/src/evaluate.h`
 * (CLAUDE.md engine gotcha 5); its 12 hex digits are a prefix of the pinned [NET_SHA256].
 *
 * Pure `java.io`/`java.nio`: no Android imports, so it is host-tested (`NetStoreTest`, `NetHeaderTest`).
 */
class NetStore(private val filesDir: File, val pins: NetPins = NetPins.COMPILED) {

    companion object {
        /** The NNUE net filename this Stockfish build expects (from evaluate.h). */
        const val NET_FILENAME: String = GeneratedNetworkConstants.EVAL_FILE_DEFAULT_NAME

        /** Pinned in `vendor/models/MODELS.lock` (`generateModelPins`). */
        const val NET_SIZE_BYTES: Long = GeneratedNetPins.NET_SIZE_BYTES
        const val NET_SHA256: String = GeneratedNetPins.NET_SHA256
        const val NET_ARCH_HASH: Long = GeneratedNetPins.NET_ARCH_HASH
        const val NET_VERSION: Long = GeneratedNetPins.NET_VERSION

        /** Directory under `filesDir`. Excluded from backup (`backup_rules.xml`). */
        const val DIR_NAME = "nets"

        /** Suffix of a download in progress (`nets/<name>.part`). It survives a pause or a kill. */
        const val PART_SUFFIX = ".part"

        /**
         * The record of a net installed by an update (D2e): `nets/active.properties`, written atomically.
         * Absent = the compiled pin. Only `ModelActivator` and setup write it.
         */
        const val ACTIVE_RECORD_NAME = "active.properties"

        private val SHA256_PATTERN = Regex("[0-9a-f]{64}")

        private const val BUFFER_BYTES = 256 * 1024

        private val NET_NAME_PATTERN = Regex("""nn-[0-9a-f]{12}\.nnue""")

        /** The 12-hex SHA-256 prefix a net's name encodes, or null for a name that is not a net. */
        fun prefixOf(name: String): String? =
            if (NET_NAME_PATTERN.matches(name)) name.substring(3, 15) else null

        /** Reads the 12-byte header of [bytes]; null when there are fewer than 12. */
        fun parseHeader(bytes: ByteArray): NetHeader? {
            if (bytes.size < NetHeader.SIZE_BYTES) return null
            fun le32(off: Int): Long =
                (bytes[off].toLong() and 0xFF) or
                    ((bytes[off + 1].toLong() and 0xFF) shl 8) or
                    ((bytes[off + 2].toLong() and 0xFF) shl 16) or
                    ((bytes[off + 3].toLong() and 0xFF) shl 24)
            return NetHeader(version = le32(0), archHash = le32(4), descriptionLength = le32(8))
        }

        fun sha256Of(file: File, digest: MessageDigest = MessageDigest.getInstance("SHA-256")): String {
            file.inputStream().use { input ->
                val buffer = ByteArray(BUFFER_BYTES)
                while (true) {
                    val n = input.read(buffer)
                    if (n == -1) break
                    digest.update(buffer, 0, n)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }

    /** `filesDir/nets/`. */
    val dir: File get() = File(filesDir, DIR_NAME)

    /** Where the active net ([activeIdentity]) lives once installed. */
    val netFile: File get() = File(dir, activeIdentity().fileName)

    /** Path, `length`, `lastModified` and expected SHA-256 of the file last verified in this process. */
    @Volatile private var verifiedStamp: List<Any>? = null
    private val lock = Any()

    private val recordFile: File get() = File(dir, ACTIVE_RECORD_NAME)

    /** The compiled pin as an [ActiveNet]: what a fresh install downloads (first run never reads a manifest). */
    fun compiledIdentity(): ActiveNet = ActiveNet(pins.fileName, pins.sizeBytes, pins.sha256)

    /**
     * The net the engine should use: the update record when there is one that is well formed and whose
     * installed file's header this engine can parse (an app update that changed the engine's architecture
     * makes an older update's record stale), else the compiled pin. Cheap: one small file and, with a
     * record, the 12-byte header of the net.
     */
    fun activeIdentity(): ActiveNet = synchronized(lock) {
        val record = readRecord() ?: return compiledIdentity()
        if (record == compiledIdentity()) return record
        val f = File(dir, record.fileName)
        val header = if (f.isFile) runCatching { readHeader(f) }.getOrNull() else null
        // A recorded file that is missing is still "the active net" (Setup then offers the compiled one,
        // and installing that resets the record); a file with a foreign header makes the record stale.
        if (header != null && !header.matches(pins)) compiledIdentity() else record
    }

    /** True when an update record (not the compiled pin) names the active net. */
    fun hasUpdateRecord(): Boolean = readRecord()?.let { it != compiledIdentity() } == true

    /**
     * Makes [net] the active net, atomically (temp file + rename); the compiled pin deletes the record.
     * The caller has verified the file, or is restoring the previous identity during a rollback.
     */
    fun setActiveIdentity(net: ActiveNet): Unit = synchronized(lock) {
        val prefix = requireNotNull(prefixOf(net.fileName)) { "not a net name: ${net.fileName}" }
        require(SHA256_PATTERN.matches(net.sha256) && net.sha256.startsWith(prefix)) {
            "the SHA-256 does not match the net name ${net.fileName}"
        }
        require(net.sizeBytes > 0) { "a net has a size" }
        if (net == compiledIdentity()) {
            recordFile.delete()
        } else {
            dir.mkdirs()
            val tmp = File(dir, "$ACTIVE_RECORD_NAME.tmp")
            tmp.writeText("fileName=${net.fileName}\nsizeBytes=${net.sizeBytes}\nsha256=${net.sha256}\n")
            try {
                Files.move(tmp.toPath(), recordFile.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (e: IOException) {
                recordFile.delete()
                if (!tmp.renameTo(recordFile)) throw IOException("could not write ${recordFile.name}", e)
            }
        }
        verifiedStamp = null
    }

    private fun readRecord(): ActiveNet? {
        val f = recordFile
        if (!f.isFile) return null
        return try {
            val p = java.util.Properties().apply { f.inputStream().use { load(it) } }
            val name = p.getProperty("fileName")?.trim().orEmpty()
            val size = p.getProperty("sizeBytes")?.trim()?.toLongOrNull()
            val sha = p.getProperty("sha256")?.trim()?.lowercase().orEmpty()
            val prefix = prefixOf(name)
            if (prefix == null || size == null || size <= 0 || !SHA256_PATTERN.matches(sha) || !sha.startsWith(prefix)) null
            else ActiveNet(name, size, sha)
        } catch (e: IOException) {
            null
        }
    }

    /** Deletes `nets/<name>` (an old net after an update, a new one on rollback); never the active one. */
    fun deleteNet(name: String): Boolean = synchronized(lock) {
        if (prefixOf(name) == null || name == activeIdentity().fileName) return false
        File(dir, name).delete()
    }

    /** Every installed net file other than the active one (left by an interrupted update or an app update). */
    fun otherNets(): List<File> {
        val active = activeIdentity().fileName
        return dir.listFiles { f -> f.isFile && prefixOf(f.name) != null && f.name != active }.orEmpty().toList()
    }

    /** The download target for the net called [name]: `nets/<name>.part`. */
    fun partFileFor(name: String = pins.fileName): File = File(dir, name + PART_SUFFIX)

    /**
     * Cheap, no hashing: the net is at its final path with exactly the pinned size. Enough for "show
     * Setup or Home"; [verifiedNetOrNull] is the gate in front of the engine.
     */
    fun activeNetOrNull(): File? {
        val id = activeIdentity()
        return File(dir, id.fileName).takeIf { it.isFile && it.length() == id.sizeBytes }
    }

    /**
     * The installed net, only if it is verified: exact pinned size and full SHA-256. Hashes ~98 MB
     * once per process (about half a second on a phone); a file already verified in this process and
     * unchanged since is not re-hashed. A net that fails is NOT deleted here (the Setup flow replaces
     * it); null tells the caller to send the user to Setup.
     */
    fun verifiedNetOrNull(): File? = synchronized(lock) {
        val id = activeIdentity()
        val f = File(dir, id.fileName).takeIf { it.isFile && it.length() == id.sizeBytes } ?: return null
        if (verifiedStamp == stampOf(f, id)) return f
        if (sha256Of(f) != id.sha256) return null
        // An update's net must also carry a header this engine parses (the compiled one was checked when
        // it was installed). activeIdentity() already ignores a record whose file has a foreign header.
        if (id != compiledIdentity() && readHeader(f)?.matches(pins) != true) return null
        verifiedStamp = stampOf(f, id)
        f
    }

    /** The header of [file]'s first 12 bytes, or null when it is shorter. */
    fun readHeader(file: File): NetHeader? = file.inputStream().use { input ->
        val bytes = ByteArray(NetHeader.SIZE_BYTES)
        var read = 0
        while (read < bytes.size) {
            val n = input.read(bytes, read, bytes.size - read)
            if (n == -1) break
            read += n
        }
        if (read < bytes.size) null else parseHeader(bytes)
    }

    /**
     * Moves a part file the caller has ALREADY verified (size and SHA-256, see `ModelDownloader`)
     * to `nets/<name>`, atomically, and returns the installed file. Also refuses a part whose NNUE
     * header does not match the compiled engine (a net the engine cannot parse would `exit()`).
     * It does not change which net is active: setup's net IS the compiled pin, and an update's net becomes
     * active only through [setActiveIdentity] (journaled by `ModelActivator`).
     */
    fun installVerified(part: File, name: String = pins.fileName): File = synchronized(lock) {
        require(prefixOf(name) != null) { "not a net name: $name" }
        if (!part.isFile) throw IOException("verified net part is missing: ${part.name}")
        val header = readHeader(part)
        if (header == null || !header.matches(pins)) {
            throw IOException("the downloaded net's header does not match this engine: $header")
        }
        dir.mkdirs()
        val target = File(dir, name)
        try {
            Files.move(part.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (e: IOException) {
            target.delete()
            if (!part.renameTo(target)) throw IOException("could not move the net into place at ${target.absolutePath}", e)
        }
        if (name == pins.fileName && activeIdentity() == compiledIdentity()) verifiedStamp = stampOf(target, compiledIdentity())
        target
    }

    /** Deletes the download in progress (Cancel) and any stray parts. */
    fun deleteParts() {
        dir.listFiles { _, n -> n.endsWith(PART_SUFFIX) }?.forEach { it.delete() }
    }

    /** What [migrateLegacy] did, for the diagnostic log. */
    data class Migration(val moved: List<String>, val deleted: List<String>) {
        val didSomething: Boolean get() = moved.isNotEmpty() || deleted.isNotEmpty()
    }

    /**
     * One-time move from the bundled builds (R7/D1/F1, versionCode 1), which kept the net at
     * `filesDir/<name>` (design §8). The net this engine expects is renamed into `nets/` (same
     * filesystem, atomic, nothing downloaded); its hash is checked later by [verifiedNetOrNull] as
     * before. Legacy `.part` copies and nets of another name (unusable by this engine) are deleted.
     * Idempotent and cheap (one directory listing); safe to call on every start.
     */
    fun migrateLegacy(): Migration = synchronized(lock) {
        val moved = ArrayList<String>()
        val deleted = ArrayList<String>()
        val legacy = filesDir.listFiles { f -> f.isFile && f.name.startsWith("nn-") }.orEmpty()
        for (f in legacy) {
            val name = f.name
            when {
                name == pins.fileName && !File(dir, pins.fileName).exists() -> {
                    val target = File(dir, pins.fileName)
                    dir.mkdirs()
                    try {
                        Files.move(f.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
                        moved += name
                    } catch (e: IOException) {
                        if (f.renameTo(target)) moved += name
                    }
                }
                name.endsWith(".nnue") || name.endsWith(PART_SUFFIX) -> if (f.delete()) deleted += name
            }
        }
        Migration(moved, deleted)
    }

    private fun stampOf(f: File, id: ActiveNet): List<Any> = listOf(f.absolutePath, f.length(), f.lastModified(), id.sha256)
}
