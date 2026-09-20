package net.palaya.chessanalyzer.video

import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.palaya.chessanalyzer.core.narration.cloud.CloudTtsFailureKind
import net.palaya.chessanalyzer.core.narration.cloud.CloudTtsReply
import net.palaya.chessanalyzer.core.narration.cloud.GoogleCloudTtsProtocol
import net.palaya.chessanalyzer.core.narration.cloud.GoogleCloudVoice
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** One HTTP exchange, reduced to what the protocol layer needs. */
data class CloudTtsHttpReply(val httpStatus: Int, val body: String)

/**
 * The only piece of [GoogleCloudTtsProvider] that touches the network, and therefore the only
 * piece that cannot be verified without a real key. Everything above it \u2014 request building,
 * JSON/base64/RIFF handling, error wording, the session-fatal latch, key validation, the
 * coordinator's per-segment fallback \u2014 runs identically against a fake transport in the
 * instrumented tests.
 */
interface CloudTtsTransport {
    /** Posts [jsonBody] to [url] with the API-key header. Throws [IOException] when no response arrives at all. */
    @Throws(IOException::class)
    fun post(url: String, apiKey: String, jsonBody: String): CloudTtsHttpReply
}

/**
 * Real transport over OkHttp. The key travels in the `X-Goog-Api-Key` header, never in the URL
 * (query strings end up in logs). **Unverified against the live API** as of this writing \u2014 no
 * key was available in the session that wrote it.
 */
class OkHttpCloudTtsTransport(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build(),
) : CloudTtsTransport {
    override fun post(url: String, apiKey: String, jsonBody: String): CloudTtsHttpReply {
        val request = Request.Builder()
            .url(url)
            .header(GoogleCloudTtsProtocol.API_KEY_HEADER, apiKey)
            .header("Content-Type", "application/json; charset=utf-8")
            .post(jsonBody.toRequestBody(JSON))
            .build()
        client.newCall(request).execute().use { response ->
            return CloudTtsHttpReply(response.code, response.body?.string().orEmpty())
        }
    }

    private companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()
    }
}

/** Outcome of the setup wizard's real-request key check. */
sealed interface CloudKeyCheck {
    data class Valid(val durationMs: Long, val sampleRate: Int) : CloudKeyCheck
    data class Invalid(val kind: CloudTtsFailureKind, val message: String) : CloudKeyCheck
}

/**
 * Google Cloud Text-to-Speech as a [NarrationVoiceProvider] \u2014 the opt-in *upgrade* over the
 * on-device neural voice (which stays the default), and the only high-quality Hebrew path (Chirp
 * 3: HD covers `he-IL`; no on-device model does). Never automatic, never a prerequisite.
 *
 * **Bring your own key.** [apiKey] is the user's own, from their own Google Cloud project, read
 * from Keystore-encrypted storage ([net.palaya.chessanalyzer.data.NarrationSettingsRepository]).
 * Nothing here logs it, and the APK carries none.
 *
 * ## Response handling, end to end
 * [CloudTtsTransport.post] -> [GoogleCloudTtsProtocol.interpretResponse] (JSON -> `audioContent`
 * base64 -> RIFF magic check) -> bytes written to [File] -> [WavUtil.readHeader] walks the RIFF
 * chunks and validates `fmt `/`data` exactly as it does for every other provider's output. Google
 * includes the WAV header for `LINEAR16`, so there is no re-wrapping and no raw-PCM guessing.
 *
 * ## Failure policy
 * Every failure is a [SynthesisResult.Failure] (never a throw), so [NarrationCoordinator]'s
 * mandatory per-segment fallback to the device voice applies unchanged. A failure that cannot be
 * cured by retrying in this session ([CloudTtsFailureKind.isFatalForSession]: bad key, API or
 * billing not enabled, quota gone) latches, and every later call fails immediately with the same
 * message instead of paying a network round trip per remaining sentence \u2014 a revoked key costs one
 * request, not sixty, and the export notice says why in plain words.
 */
class GoogleCloudTtsProvider(
    private val apiKey: String,
    val voice: GoogleCloudVoice,
    private val transport: CloudTtsTransport = OkHttpCloudTtsTransport(),
) : NarrationVoiceProvider {

    override val displayName: String = "Cloud voice (Google)"

    /** Cache-key axis: the same text through a different voice is different audio. */
    val cacheFingerprint: String get() = "google/${voice.id}/${GoogleCloudTtsProtocol.SAMPLE_RATE_HZ}"

    @Volatile private var fatal: CloudTtsReply.Rejected? = null

    /** The most recent failure, for diagnostics \u2014 null once a call has succeeded since. */
    @Volatile var lastFailure: CloudTtsReply.Rejected? = null
        private set

    /** No network here: a wrong key is reported per sentence (and latched), so the export still starts promptly. */
    override suspend fun prepare(): Boolean = GoogleCloudTtsProtocol.isPlausibleApiKey(apiKey)

    override suspend fun synthesize(text: String, outFile: File): SynthesisResult = withContext(Dispatchers.IO) {
        if (text.isBlank()) return@withContext SynthesisResult.Failure("empty narration")
        fatal?.let { return@withContext SynthesisResult.Failure(it.message) }

        when (val reply = exchange(text)) {
            is CloudTtsReply.Rejected -> {
                lastFailure = reply
                if (reply.kind.isFatalForSession) fatal = reply
                SynthesisResult.Failure(reply.message)
            }
            is CloudTtsReply.Audio -> {
                try {
                    outFile.parentFile?.mkdirs()
                    outFile.writeBytes(reply.wavBytes)
                } catch (e: IOException) {
                    return@withContext SynthesisResult.Failure("couldn't write narration audio: ${e.message}")
                }
                val info = WavUtil.readHeader(outFile)
                if (info == null || info.durationMs <= 0L) {
                    outFile.delete()
                    lastFailure = CloudTtsReply.Rejected(CloudTtsFailureKind.MALFORMED_RESPONSE, "Google's audio was not a readable PCM WAV.")
                    SynthesisResult.Failure(lastFailure!!.message)
                } else {
                    lastFailure = null
                    SynthesisResult.Success(outFile, info.durationMs)
                }
            }
        }
    }

    override fun release() = Unit

    private fun exchange(text: String): CloudTtsReply {
        val body = GoogleCloudTtsProtocol.buildRequestBody(text, voice)
        val http = try {
            transport.post(GoogleCloudTtsProtocol.ENDPOINT, GoogleCloudTtsProtocol.normalizeApiKey(apiKey), body)
        } catch (e: IOException) {
            return GoogleCloudTtsProtocol.networkFailure(e.message)
        }
        return GoogleCloudTtsProtocol.interpretResponse(http.httpStatus, http.body)
    }

    companion object {
        /**
         * The setup wizard's "Test and save": synthesizes [GoogleCloudVoice.keyCheckText] \u2014 a couple
         * of words, a few dozen characters against a million-character monthly allowance \u2014 through
         * the exact same path an export would use, so a bad key, a project without billing, or a
         * mistyped voice fails *here*, with an actionable message, instead of silently mid-export.
         */
        suspend fun validateKey(
            apiKey: String,
            voice: GoogleCloudVoice,
            transport: CloudTtsTransport,
            scratchDir: File,
        ): CloudKeyCheck = withContext(Dispatchers.IO) {
            if (!GoogleCloudTtsProtocol.isPlausibleApiKey(apiKey)) {
                return@withContext CloudKeyCheck.Invalid(
                    CloudTtsFailureKind.INVALID_KEY,
                    "That doesn't look like a Google API key. Paste the whole key from Google Cloud Console (it usually starts with \"AIza\").",
                )
            }
            val provider = GoogleCloudTtsProvider(apiKey, voice, transport)
            scratchDir.mkdirs()
            val probe = File(scratchDir, "cloud_key_check_${System.nanoTime()}.wav")
            try {
                when (val result = provider.synthesize(voice.keyCheckText, probe)) {
                    is SynthesisResult.Success -> {
                        val info = WavUtil.readHeader(probe)
                        CloudKeyCheck.Valid(result.durationMs, info?.sampleRate ?: 0)
                    }
                    is SynthesisResult.Failure -> {
                        val failure = provider.lastFailure
                        CloudKeyCheck.Invalid(failure?.kind ?: CloudTtsFailureKind.MALFORMED_RESPONSE, failure?.message ?: result.reason)
                    }
                }
            } finally {
                probe.delete()
            }
        }
    }
}
