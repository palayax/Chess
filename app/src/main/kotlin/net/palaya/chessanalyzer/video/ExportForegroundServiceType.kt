package net.palaya.chessanalyzer.video

import android.annotation.SuppressLint
import android.content.pm.ServiceInfo
import android.os.Build

/**
 * Which foreground-service type [VideoExportService] passes to `startForeground()`.
 *
 * The manifest declares `dataSync|mediaProcessing` and holds both typed permissions; the type used
 * must be one of the declared ones and must exist on the running platform:
 * - **API 35+ (Android 15+): `mediaProcessing`.** Added in Android 15 for media transcoding and
 *   encoding, which is exactly what the export is (it renders frames and encodes an MP4). It is the
 *   type to declare to Play for this service.
 * - **API 29-34: `dataSync`.** `mediaProcessing` does not exist there. Types are only enforced from
 *   Android 14 (API 34); on 29-33 the value is informational.
 * - **Below API 29:** typed foreground services do not exist; 0 means "no type".
 *
 * Both types share Android 15's 6-hour-per-24-hours limit, handled in `VideoExportService.onTimeout`.
 * Pure (the constants are compile-time ints), so it is host-tested by `ExportForegroundServiceTypeTest`.
 */
object ExportForegroundServiceType {
    // InlinedApi: the constants are compile-time ints copied into this class, and each is returned
    // only for an sdkInt at or above the level that defines it. Lint cannot see that through the
    // parameter (it only recognises Build.VERSION.SDK_INT checks).
    @SuppressLint("InlinedApi")
    fun forSdk(sdkInt: Int): Int = when {
        sdkInt >= Build.VERSION_CODES.VANILLA_ICE_CREAM -> ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
        sdkInt >= Build.VERSION_CODES.Q -> ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        else -> 0
    }

    /** The type for the device the app is running on. */
    fun current(): Int = forSdk(Build.VERSION.SDK_INT)
}
