package net.palaya.chessanalyzer.video

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.File

/**
 * Publishes an exported MP4 (already sitting in the app's cache dir) so the user can actually
 * find it: into the shared `Movies/ChessAnalyzer` collection via `MediaStore` (API 29+ scoped
 * storage needs no permission for an app inserting its own media — deliberately not requesting
 * `WRITE_EXTERNAL_STORAGE`), plus a `content://` URI through the app's existing `FileProvider`
 * for direct sharing without waiting on the MediaStore copy.
 */
object MediaStorePublisher {

    /**
     * Copies [sourceFile] into `Movies/ChessAnalyzer` so it shows up in the gallery/Files app.
     * Returns null (rather than throwing) below API 29, where scoped-storage MediaStore inserts
     * aren't available and this app does not request the legacy write permission — the file
     * still exists in the app's own cache and remains shareable via [shareUriFor].
     */
    fun publishToMovies(context: Context, sourceFile: File, displayName: String): Uri? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/ChessAnalyzer")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val itemUri = resolver.insert(collection, values) ?: return null
        return try {
            resolver.openOutputStream(itemUri)?.use { out ->
                sourceFile.inputStream().use { input -> input.copyTo(out) }
            } ?: run { resolver.delete(itemUri, null, null); return null }
            values.clear()
            values.put(MediaStore.Video.Media.IS_PENDING, 0)
            resolver.update(itemUri, values, null, null)
            itemUri
        } catch (e: Exception) {
            resolver.delete(itemUri, null, null)
            null
        }
    }

    /** A `content://` URI for [file] (which must live under the app's cache dir) for `ACTION_SEND`. */
    fun shareUriFor(context: Context, file: File): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
}
