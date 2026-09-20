package net.palaya.chessanalyzer.util

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Parcelable
import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader

private const val TAG = "PgnIntentUtils"

/** MIME types we recognize as "this is probably PGN" when deciding whether to read a stream. */
private val PGN_LIKE_MIME_TYPES = setOf(
    "application/x-chess-pgn",
    "application/vnd.chess-pgn",
    "text/x-chess-pgn",
    "text/plain",
    "application/octet-stream",
)

/**
 * Pulls PGN text out of an incoming [Intent] — the share sheet (`ACTION_SEND` /
 * `ACTION_SEND_MULTIPLE`, e.g. chess.com/Lichess sharing a game as plain text or as a
 * `.pgn` file attachment) or a direct file open (`ACTION_VIEW` on a `file://`/`content://`
 * URI, e.g. from a file manager). Returns `null` if the intent doesn't carry anything we
 * can interpret as PGN.
 *
 * Kept as a standalone, dependency-injected function (no Activity/Context coupling beyond
 * the resolver) specifically so it can be unit tested with a fake [ContentResolver] and a
 * plain [Intent] fixture.
 */
fun extractPgnFromIntent(intent: Intent, contentResolver: ContentResolver): String? {
    return when (intent.action) {
        Intent.ACTION_SEND -> extractFromSend(intent, contentResolver)
        Intent.ACTION_SEND_MULTIPLE -> extractFromSendMultiple(intent, contentResolver)
        Intent.ACTION_VIEW -> intent.data?.let { readTextFromUri(contentResolver, it) }
        else -> null
    }
}

private fun extractFromSend(intent: Intent, contentResolver: ContentResolver): String? {
    // chess.com / Lichess "share game" typically sends EXTRA_TEXT with the PGN inline.
    intent.getStringExtra(Intent.EXTRA_TEXT)?.let { text ->
        if (text.isNotBlank()) return text
    }
    // Some apps instead attach a .pgn file as EXTRA_STREAM.
    getParcelableExtraCompat(intent, Intent.EXTRA_STREAM, Uri::class.java)?.let { uri ->
        return readTextFromUri(contentResolver, uri)
    }
    return null
}

private fun extractFromSendMultiple(intent: Intent, contentResolver: ContentResolver): String? {
    intent.getStringExtra(Intent.EXTRA_TEXT)?.let { text ->
        if (text.isNotBlank()) return text
    }
    val uris = getParcelableArrayListExtraCompat(intent, Intent.EXTRA_STREAM, Uri::class.java)
    val firstUri = uris?.firstOrNull() ?: return null
    return readTextFromUri(contentResolver, firstUri)
}

/** Reads the full text contents of a `content://` or `file://` URI as UTF-8. */
fun readTextFromUri(contentResolver: ContentResolver, uri: Uri): String? = try {
    contentResolver.openInputStream(uri)?.use { input ->
        BufferedReader(InputStreamReader(input, Charsets.UTF_8)).readText()
    }
} catch (e: Exception) {
    Log.w(TAG, "Failed to read PGN text from $uri", e)
    null
}

fun isPgnLikeMimeType(mimeType: String?): Boolean = mimeType == null || mimeType in PGN_LIKE_MIME_TYPES

@Suppress("DEPRECATION")
private fun <T : Parcelable> getParcelableExtraCompat(intent: Intent, name: String, clazz: Class<T>): T? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        intent.getParcelableExtra(name, clazz)
    } else {
        intent.getParcelableExtra(name)
    }

@Suppress("DEPRECATION")
private fun <T : Parcelable> getParcelableArrayListExtraCompat(intent: Intent, name: String, clazz: Class<T>): ArrayList<T>? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        intent.getParcelableArrayListExtra(name, clazz)
    } else {
        intent.getParcelableArrayListExtra(name)
    }
