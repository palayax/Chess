package net.palaya.chessanalyzer.data.models

import net.palaya.chessanalyzer.diagnostics.DiagnosticLog

/** One entry the user may install, with why it is offered (always [CompatVerdict.Offer]). */
data class UpdateOffer(val entry: ManifestEntry) {
    val kind: ModelKind get() = entry.kind
}

/** An entry that is not offered, and why (for the log and the tests; the user never sees these). */
data class NotOffered(val id: String, val fileName: String, val verdict: CompatVerdict)

/** What one tap on "Check for updates" found. */
sealed interface UpdateCheckResult {
    data class UpToDate(val notOffered: List<NotOffered>) : UpdateCheckResult
    data class Available(val offers: List<UpdateOffer>, val notOffered: List<NotOffered>) : UpdateCheckResult

    /** No connected network: nothing was requested. */
    data object NoInternet : UpdateCheckResult

    /** No answer, a timeout, or a 5xx / unexpected status. */
    data class ServerUnavailable(val detail: String) : UpdateCheckResult

    /** 404 / 410: the manifest (or its signature) is not published (e.g. the provisional URL). */
    data class NotFound(val detail: String) : UpdateCheckResult

    /** The signature is missing, malformed or not by the compiled-in key: nothing in the manifest is used. */
    data class SignatureInvalid(val detail: String) : UpdateCheckResult

    /** Signed, but not a manifest this app can read (schema, a missing field). Nothing is used. */
    data class ManifestInvalid(val detail: String) : UpdateCheckResult
}

/**
 * "Check for updates" (docs/MODEL_DOWNLOAD_DESIGN.md §1.8, §3.2-§3.4, D2e). Runs **only** when the user
 * taps the Settings row; nothing else in the app calls [check]. One check = exactly two requests, both
 * through [ModelDownloader.fetchSmall] (the app's only network class): `models.json` (at most 64 KB) and
 * `models.json.sig`. Then, in this order and with nothing skipped:
 *  1. the signature is verified over the exact bytes received ([ManifestSignature]) — a bad or missing
 *     signature ends the check: nothing in the manifest is even parsed;
 *  2. the manifest is parsed ([ModelManifest]);
 *  3. every known entry is judged by [ModelCompatibility] against [facts]. Only [CompatVerdict.Offer]
 *     entries are offered; a wrong-architecture net, a voice for another runtime and the like are logged
 *     and dropped here, so they are never shown and never downloaded.
 *
 * [manifestUrl] is `BuildConfig.MODEL_MANIFEST_URL` (`<base>models/models.json`); the signature is that URL
 * plus `.sig`. [publicKeyDer] is the compiled-in key; tests pass a test key. No file is downloaded here.
 */
class UpdateChecker(
    private val downloader: ModelDownloader,
    private val manifestUrl: String,
    private val publicKeyDer: ByteArray,
    private val networkStatus: NetworkStatus,
    private val facts: () -> AppFacts,
    private val diagnostics: DiagnosticLog? = null,
) {
    private fun log(line: String) {
        diagnostics?.log(ModelSetup.TAG, line)
    }

    suspend fun check(): UpdateCheckResult {
        if (networkStatus.current() == NetworkCost.UNAVAILABLE) {
            log("update check: no network, nothing requested")
            return UpdateCheckResult.NoInternet
        }
        log("update check: asking host ${ModelDownloader.hostOf(manifestUrl)} for the manifest and its signature")
        val manifest = when (val r = downloader.fetchSmall(manifestUrl, ModelManifest.MAX_BYTES)) {
            is SmallFetch.Ok -> r.bytes
            is SmallFetch.Failed -> return failed("manifest", r)
        }
        val signature = when (val r = downloader.fetchSmall("$manifestUrl.sig", ModelManifest.MAX_SIGNATURE_BYTES)) {
            is SmallFetch.Ok -> r.bytes
            is SmallFetch.Failed -> return if (r.reason == SmallFetchFailure.NOT_FOUND || r.reason == SmallFetchFailure.TOO_LARGE) {
                // A manifest without a usable signature is an unsigned manifest: refused, never parsed.
                log("update check: ManifestRejected(signature): ${r.reason} ${r.detail}")
                UpdateCheckResult.SignatureInvalid("signature ${r.reason}")
            } else {
                failed("signature", r)
            }
        }
        if (!ManifestSignature.verify(manifest, signature, publicKeyDer)) {
            log("update check: ManifestRejected(signature): the signature does not verify (${manifest.size} B manifest, ${signature.size} B signature); nothing used")
            return UpdateCheckResult.SignatureInvalid("does not verify")
        }
        val parsed = when (val p = ModelManifest.parse(manifest)) {
            is ManifestParse.Valid -> p.manifest
            is ManifestParse.Invalid -> {
                log("update check: ManifestRejected(format): ${p.reason}")
                return UpdateCheckResult.ManifestInvalid(p.reason)
            }
        }
        val f = facts()
        val offers = ArrayList<UpdateOffer>()
        val notOffered = ArrayList<NotOffered>()
        for (e in parsed.entries) {
            when (val v = ModelCompatibility.evaluate(e, f)) {
                CompatVerdict.Offer -> offers += UpdateOffer(e)
                else -> notOffered += NotOffered(e.kind.id, e.fileName, v)
            }
        }
        log(
            "update check: signature OK, ${parsed.entries.size} known entr${if (parsed.entries.size == 1) "y" else "ies"}" +
                (if (parsed.ignoredIds.isNotEmpty()) " (ignored ids: ${parsed.ignoredIds.joinToString()})" else "") +
                "; offered: ${offers.joinToString { "${it.entry.kind.id} ${it.entry.version} (${it.entry.sizeBytes} B)" }.ifEmpty { "nothing" }}" +
                notOffered.joinToString("") { "; not offered: ${it.id} ${it.fileName} ${describe(it.verdict)}" },
        )
        // At most one offer per kind: the first in the manifest's order wins (the publisher's call).
        val one = offers.distinctBy { it.kind }
        return if (one.isEmpty()) UpdateCheckResult.UpToDate(notOffered) else UpdateCheckResult.Available(one, notOffered)
    }

    private fun failed(what: String, r: SmallFetch.Failed): UpdateCheckResult {
        log("update check: the $what request failed (${r.reason}: ${r.detail})")
        return when (r.reason) {
            SmallFetchFailure.NOT_FOUND -> UpdateCheckResult.NotFound("$what ${r.detail}")
            SmallFetchFailure.TOO_LARGE, SmallFetchFailure.INSECURE -> UpdateCheckResult.ManifestInvalid("$what ${r.reason}")
            SmallFetchFailure.NETWORK, SmallFetchFailure.SERVER -> UpdateCheckResult.ServerUnavailable("$what ${r.detail}")
        }
    }

    private fun describe(v: CompatVerdict): String = when (v) {
        CompatVerdict.Offer -> "offer"
        CompatVerdict.AlreadyInstalled -> "(already installed)"
        is CompatVerdict.Incompatible -> "(${v.reason}: ${v.detail})"
    }
}
