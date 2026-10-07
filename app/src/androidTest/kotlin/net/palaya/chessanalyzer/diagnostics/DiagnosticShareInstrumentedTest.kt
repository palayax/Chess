package net.palaya.chessanalyzer.diagnostics

import android.content.Intent
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import net.palaya.chessanalyzer.ChessAnalyzerApplication
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * "Share diagnostic log" (F1, Settings and the analysis error screen): an `ACTION_SEND` of the log
 * as a text/plain attachment through the app's own FileProvider, with a short summary as
 * `EXTRA_TEXT`. Proves on a device that the snapshot directory is covered by the provider, that a
 * receiving app reads exactly the log's bytes through the content resolver, and that the summary
 * names the app version and the device.
 */
@RunWith(AndroidJUnit4::class)
class DiagnosticShareInstrumentedTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val diagnostics get() = (context.applicationContext as ChessAnalyzerApplication).diagnostics

    @Test
    fun theShareIntentSendsTheWholeLogAsAGrantedTextAttachmentWithASummary() {
        val marker = "share-test-marker-${System.nanoTime()}"
        diagnostics.log.log("test", marker)

        val intent = diagnostics.shareIntent(context)
        assertNotNull("the share intent is built", intent)
        intent!!
        assertEquals(Intent.ACTION_SEND, intent.action)
        assertEquals("text/plain", intent.type)

        val stream = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
        assertNotNull("the log travels as EXTRA_STREAM", stream)
        assertEquals("content", stream!!.scheme)
        assertEquals("${context.packageName}.fileprovider", stream.authority)
        assertTrue("read access is granted", intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertEquals(stream, intent.clipData?.getItemAt(0)?.uri)

        // Read as the receiving app would: the whole log, previous file then current, ending with
        // the entry the share itself wrote.
        val shared = context.contentResolver.openInputStream(stream)!!.use { it.readBytes().toString(Charsets.UTF_8) }
        val onDisk = listOf(diagnostics.log.previousFile, diagnostics.log.currentFile)
            .filter { it.isFile }.joinToString("") { it.readText() }
        // A prefix, not equality: a background writer (the engine, the exit-reason thread) may append
        // after the snapshot was taken.
        assertTrue("the attachment is the log", onDisk.startsWith(shared))
        assertTrue("it holds what was just logged", shared.contains(marker))
        assertTrue("the process start was logged", shared.contains("[lifecycle] process start"))
        assertTrue("the device block was logged", shared.contains("[device] app "))

        val summary = intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
        assertTrue(summary, summary.startsWith("Palaya Chess "))
        assertTrue(summary, summary.contains("(API ${android.os.Build.VERSION.SDK_INT})"))
        assertTrue(summary, summary.contains("Last error: "))
    }
}
