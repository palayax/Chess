package net.palaya.chessanalyzer.data.models

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "After setup the app makes no network call on its own" starts with "only one class can make one"
 * (docs/MODEL_DOWNLOAD_DESIGN.md §6.1). Scans the production sources of `:app`, `:engine` and `:rephrase` (C2) for every
 * way Kotlin/Java code on Android opens a connection and asserts the only hits are in
 * `data/models/ModelDownloader.kt`. A new call site anywhere else fails here and has to be argued for.
 */
class NetworkCallSitesTest {

    private fun repoDir(): File {
        // Gradle runs unit tests with the module directory (app/) as the working directory.
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "settings.gradle.kts").exists()) dir = dir.parentFile
        return requireNotNull(dir) { "cannot find the repository root from ${File("").absolutePath}" }
    }

    private val patterns = listOf(
        Regex("""\bopenConnection\s*\("""),
        Regex("""\bopenStream\s*\("""),
        Regex("""\bHttps?URLConnection\b"""),
        Regex("""\bURLConnection\b"""),
        Regex("""\bjava\.net\.(Socket|ServerSocket|DatagramSocket|URL|HttpURLConnection)\b"""),
        Regex("""\b(Socket|ServerSocket|DatagramSocket|SSLSocket)\s*\("""),
        Regex("""\bSocketChannel\b"""),
        Regex("""\bokhttp3?\b""", RegexOption.IGNORE_CASE),
        Regex("""\bretrofit2?\b""", RegexOption.IGNORE_CASE),
        Regex("""\bio\.ktor\b"""),
        Regex("""\bDownloadManager\b"""),
        Regex("""\bWebView\b"""),
        Regex("""\bVolley\b"""),
        Regex("""\bHttpClient\b"""),
    )

    private data class Hit(val file: String, val line: Int, val text: String)

    private fun scan(): Pair<Int, List<Hit>> {
        val root = repoDir()
        val dirs = listOf("app/src/main/kotlin", "app/src/main/java", "engine/src/main/kotlin", "engine/src/main/java", "rephrase/src/main/kotlin", "rephrase/src/main/java")
            .map { File(root, it) }.filter { it.isDirectory }
        var files = 0
        val hits = ArrayList<Hit>()
        for (dir in dirs) {
            dir.walkTopDown().filter { it.isFile && (it.extension == "kt" || it.extension == "java") }.forEach { f ->
                files++
                val rel = f.relativeTo(root).invariantSeparatorsPath
                f.readLines().forEachIndexed { i, line ->
                    // Drop a trailing line comment, but not the "//" inside "https://".
                    val code = line.replace(Regex("""(^|\s)//.*$"""), "").trim()
                    // Doc comments may name the classes; only code counts.
                    if (code.startsWith("*") || code.startsWith("/*")) return@forEachIndexed
                    if (patterns.any { it.containsMatchIn(code) }) hits += Hit(rel, i + 1, code)
                }
            }
        }
        return files to hits
    }

    @Test
    fun onlyTheModelDownloaderOpensNetworkConnections() {
        val (files, hits) = scan()
        assertTrue("vacuity guard: scanned only $files source files", files > 50)
        val elsewhere = hits.filterNot { it.file.endsWith("app/src/main/kotlin/net/palaya/chessanalyzer/data/models/ModelDownloader.kt") }
        assertEquals("network call sites outside ModelDownloader.kt:\n" + elsewhere.joinToString("\n"), emptyList<Hit>(), elsewhere)
    }

    @Test
    fun theScanFindsTheDownloadersOwnConnection() {
        // If the patterns stopped matching anything, the test above would pass vacuously.
        val (_, hits) = scan()
        assertTrue(
            "the scan must see ModelDownloader's openConnection / HttpURLConnection: $hits",
            hits.any { it.file.endsWith("data/models/ModelDownloader.kt") && it.text.contains("openConnection") },
        )
    }
}
