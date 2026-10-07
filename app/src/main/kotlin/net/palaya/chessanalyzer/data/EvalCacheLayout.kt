package net.palaya.chessanalyzer.data

import java.io.File

/**
 * Where engine evaluations are cached: one folder per net, `filesDir/eval_cache/<net 12-hex>/`
 * (docs/MODEL_DOWNLOAD_DESIGN.md §4.2, D2e), so a report always comes from one net. Pure `java.io`
 * (host-tested in `EvalCacheLayoutTest`). The file names inside are unchanged: `<key>.json`,
 * `<key>.partial.json` and `<key>.partial.tmp`, with the key from `GameRepository.cacheKey` (PGN, depth,
 * MultiPV and, since F1, the search budget).
 */
class EvalCacheLayout(val root: File) {

    companion object {
        const val DIR_NAME = "eval_cache"
        private val PREFIX = Regex("[0-9a-f]{12}")

        /** True for a folder name that is a net prefix. */
        fun isNetFolder(name: String): Boolean = PREFIX.matches(name)
    }

    /** The folder for the net whose name encodes [netPrefix]. */
    fun dirFor(netPrefix: String): File {
        require(isNetFolder(netPrefix)) { "not a net prefix: $netPrefix" }
        return File(root, netPrefix)
    }

    /** What [migrateFlat] and [purgeExcept] did, for the log. */
    data class Result(val moved: Int, val deletedFiles: Int, val deletedFolders: List<String>)

    /**
     * One-time move of the flat layout (every build before D2e kept `eval_cache/<key>.json` at the root)
     * into the folder of [currentNetPrefix]: before D2e the only net there ever was is the compiled one,
     * which is the active net on the first launch of this build. A file whose name already exists in the
     * folder is dropped (the folder's copy is newer). Idempotent; cheap when there is nothing to move.
     */
    fun migrateFlat(currentNetPrefix: String): Result {
        val files = root.listFiles { f -> f.isFile }.orEmpty()
        if (files.isEmpty()) return Result(0, 0, emptyList())
        val dir = dirFor(currentNetPrefix).apply { mkdirs() }
        var moved = 0
        var deleted = 0
        for (f in files) {
            val target = File(dir, f.name)
            if (!target.exists() && f.renameTo(target)) moved++ else if (f.delete()) deleted++
        }
        return Result(moved, deleted, emptyList())
    }

    /**
     * After a net update commits: every other net's folder (and any stray flat file) is deleted, so a
     * report can never mix nets and the old results do not take space for nothing. Games reopened later are
     * analysed again with the new net.
     */
    fun purgeExcept(keepNetPrefix: String): Result {
        var deletedFiles = 0
        val folders = ArrayList<String>()
        for (f in root.listFiles().orEmpty()) {
            when {
                f.isDirectory && f.name == keepNetPrefix -> Unit
                f.isDirectory -> if (f.deleteRecursively()) folders += f.name
                else -> if (f.delete()) deletedFiles++
            }
        }
        return Result(0, deletedFiles, folders)
    }
}
