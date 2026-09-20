package net.palaya.chessanalyzer.video

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import net.palaya.chessanalyzer.ui.model.NeuralVoiceTier

/**
 * What the current connection costs the user. Deliberately three-valued: "no network" must not be
 * collapsed into "metered", because the right response differs (wait quietly vs. fetch the small
 * model), and a two-valued boolean would silently pick one of them.
 */
enum class NetworkCost {
    /** Wi-Fi, ethernet, or a mobile plan the system reports as unmetered. Large downloads are fine. */
    UNMETERED,

    /** Cellular, a metered hotspot, or an unknown-but-connected transport. Treat bytes as billable. */
    METERED,

    /** Nothing connected. Nothing can be downloaded at all right now. */
    UNAVAILABLE,
}

/**
 * Injectable reading of [NetworkCost]. An interface purely so the gating decision can be tested
 * on both sides of the metered boundary without an actual cellular connection — an instrumented
 * test cannot make an emulator meter its own network, and a gate that is only ever exercised on
 * the unmetered path is exactly as unverified as the Kokoro tier was.
 */
fun interface NetworkCostProbe {
    fun current(): NetworkCost
}

/**
 * The real probe: [NetworkCapabilities.NET_CAPABILITY_NOT_METERED] on the system's active
 * network.
 *
 * Fails **closed**, to [NetworkCost.METERED], whenever the answer is not a clear "not metered" —
 * no active network capabilities, a SecurityException, a transport the platform will not
 * characterise. Mis-reading metered as unmetered bills the user ~98 MB of cellular data; the
 * opposite mistake costs them a smaller voice model. Only one of those is acceptable to get
 * wrong, so the ambiguous cases all land on the cheap side.
 */
class ConnectivityNetworkCostProbe(context: Context) : NetworkCostProbe {
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

/**
 * What the automatic (never user-initiated) voice provisioning should do right now.
 *
 * [promoteTo] and [download] are independent on purpose: on an unmetered connection with only
 * Piper installed the right answer is *both* — narrate with Piper immediately so the review is
 * not stuck on the robotic device voice, while Kokoro downloads in the background. "Narration
 * must still work while a model is downloading" is what that pairing buys.
 */
data class AutoVoiceDecision(
    /** Tier to switch the narration voice to now. Always one that is already installed, or null. */
    val promoteTo: NeuralVoiceTier?,
    /** Tier to start provisioning in the background, or null to download nothing. */
    val download: NeuralVoiceTier?,
    /** Plain-language justification — logged, and asserted on in tests so the reason is pinned. */
    val reason: String,
)

/**
 * Decides what [net.palaya.chessanalyzer.ui.viewmodel.AnalysisViewModel.ensureDefaultNeuralVoice]
 * may do without asking, given what is on disk and what the connection costs. Pure and
 * Android-free so it is verifiable in isolation; the Android parts ([ConnectivityNetworkCostProbe],
 * DataStore) stay on the outside.
 *
 * The rule that matters: **Kokoro (~98.5 MB) is only ever auto-downloaded on an unmetered
 * connection.** The app already pulls a ~98 MB Stockfish net unprompted, and stacking a second
 * ~98 MB fetch onto a cellular plan is not a cost the user agreed to. On a metered connection the
 * automatic path tops out at Piper's ~20 MB — the same amount Round 5 already shipped as an
 * unconditional auto-download, so this is a tightening, never a new cost — and the device voice
 * remains the floor below that.
 *
 * A user who picks a tier themselves in Settings is not subject to any of this: that is an
 * explicit, on-screen action with the size printed next to it, and
 * `AnalysisViewModel.downloadNeuralModel` runs it regardless of [NetworkCost].
 */
fun decideAutoVoice(cost: NetworkCost, installed: Set<NeuralVoiceTier>): AutoVoiceDecision {
    val kokoro = NeuralVoiceTier.KOKORO in installed
    val piper = NeuralVoiceTier.PIPER in installed
    val bestInstalled = when {
        kokoro -> NeuralVoiceTier.KOKORO
        piper -> NeuralVoiceTier.PIPER
        else -> null
    }

    return when {
        kokoro -> AutoVoiceDecision(
            promoteTo = NeuralVoiceTier.KOKORO,
            download = null,
            reason = "Kokoro already installed — nothing to download",
        )

        cost == NetworkCost.UNMETERED -> AutoVoiceDecision(
            promoteTo = bestInstalled,
            download = NeuralVoiceTier.KOKORO,
            reason = if (bestInstalled == null) {
                "unmetered connection — fetching Kokoro; device voice narrates until it lands"
            } else {
                "unmetered connection — fetching Kokoro; ${bestInstalled.label} narrates until it lands"
            },
        )

        piper -> AutoVoiceDecision(
            promoteTo = NeuralVoiceTier.PIPER,
            download = null,
            reason = "metered or offline — using the already-installed Piper rather than " +
                "spending ~98 MB of billable data on Kokoro",
        )

        cost == NetworkCost.METERED -> AutoVoiceDecision(
            promoteTo = null,
            download = NeuralVoiceTier.PIPER,
            reason = "metered connection — fetching Piper (~20 MB) instead of Kokoro (~98 MB)",
        )

        else -> AutoVoiceDecision(
            promoteTo = null,
            download = null,
            reason = "no usable network — staying on the device voice",
        )
    }
}
