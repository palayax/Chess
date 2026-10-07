package net.palaya.chessanalyzer.video

import android.content.Intent
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import net.palaya.chessanalyzer.ui.screens.buildVideoShareIntent
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * "Share" on the export-complete dialog (R6a): an `ACTION_SEND` of the MP4 through the app's own
 * FileProvider. Proves, on a device, that the provider's `<paths>` really cover the exporter's output
 * directory (`cacheDir/video_export/`), that the URI is readable through the content resolver with
 * the file's exact bytes, and that building the intent needs no permission.
 */
@RunWith(AndroidJUnit4::class)
class VideoShareInstrumentedTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var file: File

    @Before
    fun writeAFakeExport() {
        val dir = File(context.cacheDir, "video_export").apply { mkdirs() }
        file = File(dir, "share_test_${System.nanoTime()}.mp4")
        file.writeBytes(ByteArray(4096) { (it % 251).toByte() })
    }

    @After
    fun cleanUp() {
        file.delete()
    }

    @Test
    fun theExportDirectoryIsCoveredByTheFileProvider() {
        val uri = MediaStorePublisher.shareUriFor(context, file)
        assertEquals("content", uri.scheme)
        assertEquals("${context.packageName}.fileprovider", uri.authority)
    }

    @Test
    fun theShareIntentSendsTheMp4AsAGrantedContentStream() {
        val intent = buildVideoShareIntent(context, file, fallbackUri = null)
        assertNotNull(intent)
        intent!!
        assertEquals(Intent.ACTION_SEND, intent.action)
        assertEquals("video/mp4", intent.type)
        val stream = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
        assertNotNull("the video travels as EXTRA_STREAM", stream)
        assertEquals("${context.packageName}.fileprovider", stream!!.authority)
        assertTrue(
            "read access is granted for that URI only",
            intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0,
        )
        assertEquals(stream, intent.clipData?.getItemAt(0)?.uri)
        // The stream is the file itself: same bytes, read through the content resolver as another app would.
        val read = context.contentResolver.openInputStream(stream)!!.use { it.readBytes() }
        assertTrue("the shared stream is the exported file", read.contentEquals(file.readBytes()))
    }

    @Test
    fun theShareIntentNeedsNoPermissionOfItsOwn() {
        val requested = context.packageManager
            .getPackageInfo(context.packageName, android.content.pm.PackageManager.GET_PERMISSIONS)
            .requestedPermissions.orEmpty().toList()
        buildVideoShareIntent(context, file, fallbackUri = null)!!
        // Same list as before: building and starting the intent adds nothing to ask for. No storage
        // permission: the file goes out through our FileProvider. (INTERNET is held since D2b for the
        // one-time model download, not for sharing; NetworkPermissionTest pins the exact set and
        // NoNetworkAfterSetupTest proves nothing uses it after setup.)
        assertTrue("no storage permission is requested: $requested", requested.none {
            it.endsWith("READ_EXTERNAL_STORAGE") || it.endsWith("WRITE_EXTERNAL_STORAGE") || it.endsWith("MANAGE_EXTERNAL_STORAGE")
        })
    }

    @Test
    fun aFileThatIsGoneFallsBackToTheSavedCopyAndWithNeitherThereIsNoIntent() {
        val gone = File(file.parentFile, "gone.mp4")
        val saved = Uri.parse("content://media/external/video/media/1")
        val fallback = buildVideoShareIntent(context, gone, saved)
        assertEquals(saved, fallback!!.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
        assertNull(buildVideoShareIntent(context, gone, null))
        assertNull(buildVideoShareIntent(context, null, null))
    }
}
