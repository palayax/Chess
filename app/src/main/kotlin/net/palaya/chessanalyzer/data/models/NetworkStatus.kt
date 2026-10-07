package net.palaya.chessanalyzer.data.models

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/**
 * What the current connection costs the user (design §1.3: the metered dialog and the "No internet
 * connection" error before a download starts). Deliberately three-valued: "no network" must not be
 * collapsed into "metered", because the right response differs (an error vs. a confirmation dialog).
 */
enum class NetworkCost {
    /** Wi-Fi, ethernet, or a mobile plan the system reports as unmetered. */
    UNMETERED,

    /** Cellular, a metered hotspot, or an unknown-but-connected transport. Treat bytes as billable. */
    METERED,

    /** Nothing connected (or not validated). Nothing can be downloaded right now. */
    UNAVAILABLE,
}

/**
 * Injectable reading of [NetworkCost], so the Setup logic is testable on both sides of the metered
 * boundary without a cellular connection. Only ever read when the user taps Download; reading it does
 * not use the network.
 */
fun interface NetworkStatus {
    fun current(): NetworkCost
}

/**
 * The real reading: [NetworkCapabilities.NET_CAPABILITY_NOT_METERED] on the active network (needs
 * ACCESS_NETWORK_STATE). The Round 11 `ConnectivityNetworkCostProbe` logic (git bbeb931,
 * `video/AutoVoicePolicy.kt`), brought back verbatim.
 *
 * Fails **closed**, to [NetworkCost.METERED], whenever the answer is not a clear "not metered": no
 * connectivity service, a transport the platform will not characterise. Mis-reading metered as
 * unmetered bills the user ~260 MB of cellular data without asking; the opposite mistake costs one
 * extra confirmation tap.
 */
class ConnectivityNetworkStatus(context: Context) : NetworkStatus {
    private val appContext = context.applicationContext

    override fun current(): NetworkCost {
        val cm = appContext.getSystemService(ConnectivityManager::class.java) ?: return NetworkCost.METERED
        val caps = try {
            cm.getNetworkCapabilities(cm.activeNetwork)
        } catch (e: SecurityException) {
            null
        } ?: return NetworkCost.UNAVAILABLE
        val connected = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        if (!connected) return NetworkCost.UNAVAILABLE
        return if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)) {
            NetworkCost.UNMETERED
        } else {
            NetworkCost.METERED
        }
    }
}
