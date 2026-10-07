package net.palaya.chessanalyzer.diagnostics

import java.io.File
import java.io.FileOutputStream
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * The on-device diagnostic log (F1): a rolling plain-text file in app-private storage
 * (`filesDir/logs/`, excluded from backup), so that when something goes wrong on a phone the owner
 * can send us what happened instead of a one-line description.
 *
 * - **Never leaves the phone by itself.** The app has no network permission; the only way out is the
 *   user's own Share (Settings, or the analysis error screen), which sends [snapshot].
 * - **Bounded.** Two files of at most [maxFileBytes] each ([CURRENT_NAME] and [PREVIOUS_NAME]): when
 *   the current one would pass the limit it becomes the previous one (the older previous is deleted)
 *   and a new current one starts. With the default 512 KiB that is about 1 MiB in total.
 * - **Plain Kotlin and java.io**, no Android, so formatting and rotation have host tests. The
 *   Android facts (device, exit reasons, lifecycle) are gathered by [AppDiagnostics].
 * - **Thread-safe and synchronous.** Every entry is appended and closed before [log] returns, so the
 *   uncaught-exception handler can write the crash before the process dies.
 *
 * Content is written as given: there is nothing to redact (no account, no key, no location, no
 * identifiers; the game is the user's own and is only sent when the user shares the log).
 */
class DiagnosticLog(
    val dir: File,
    private val maxFileBytes: Long = DEFAULT_MAX_FILE_BYTES,
    private val clock: () -> Long = System::currentTimeMillis,
    private val zone: TimeZone = TimeZone.getDefault(),
) {
    val currentFile: File get() = File(dir, CURRENT_NAME)
    val previousFile: File get() = File(dir, PREVIOUS_NAME)

    /** The most recent [error] message, for the one-line summary that goes with a shared log. */
    @Volatile
    var lastError: String? = null
        private set

    /** Appends one entry. Never throws: a log that cannot be written must not break the app. */
    @Synchronized
    fun log(tag: String, message: String) {
        try {
            dir.mkdirs()
            val entry = formatEntry(clock(), zone, tag, message.take(MAX_ENTRY_CHARS)).toByteArray(Charsets.UTF_8)
            val current = currentFile
            if (current.exists() && current.length() + entry.size > maxFileBytes) rotate()
            FileOutputStream(current, true).use { it.write(entry) }
        } catch (_: Exception) {
            // Out of space or a read-only filesystem: nothing useful to do, and nowhere to say it.
        }
    }

    /** Logs [message] with [error]'s full stack trace and remembers it as the last error. */
    fun error(tag: String, message: String, error: Throwable? = null) {
        lastError = if (error != null) "$message: ${error.javaClass.simpleName}: ${error.message}" else message
        log(tag, if (error != null) "$message\n${stackTraceOf(error)}" else message)
    }

    /**
     * Writes the whole log (previous file, then current) to [target] for sharing and returns it.
     * The live files are not handed out, so a share in progress never races a rotation.
     */
    @Synchronized
    fun snapshot(target: File): File {
        target.parentFile?.mkdirs()
        FileOutputStream(target, false).use { out ->
            for (f in listOf(previousFile, currentFile)) if (f.isFile) f.inputStream().use { it.copyTo(out) }
        }
        return target
    }

    /** Total bytes on disk (both files). */
    fun sizeBytes(): Long = listOf(previousFile, currentFile).sumOf { if (it.isFile) it.length() else 0L }

    private fun rotate() {
        val previous = previousFile
        previous.delete()
        if (!currentFile.renameTo(previous)) currentFile.delete()
    }

    companion object {
        const val DIR_NAME = "logs"
        const val CURRENT_NAME = "diagnostic.log"
        const val PREVIOUS_NAME = "diagnostic.1.log"

        /** Two files of this size: about 1 MiB in total. */
        const val DEFAULT_MAX_FILE_BYTES = 512L * 1024

        /** One entry is cut here, so a runaway message cannot rotate the whole history away. */
        const val MAX_ENTRY_CHARS = 32 * 1024

        /**
         * One entry: `2026-10-06 14:03:22.123 +0300 [tag] message`. Continuation lines of a
         * multi-line message (a stack trace, a PGN) are indented by four spaces so every entry
         * starts at column 0 with a timestamp.
         */
        fun formatEntry(timeMs: Long, zone: TimeZone, tag: String, message: String): String {
            val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS Z", Locale.US).apply { timeZone = zone }.format(Date(timeMs))
            val lines = message.replace("\r\n", "\n").trimEnd('\n').split('\n')
            return buildString {
                append(stamp).append(" [").append(tag).append("] ").append(lines.first()).append('\n')
                for (line in lines.drop(1)) append("    ").append(line).append('\n')
            }
        }

        fun stackTraceOf(error: Throwable): String {
            val writer = StringWriter()
            PrintWriter(writer).use { error.printStackTrace(it) }
            return writer.toString().trimEnd()
        }
    }
}
