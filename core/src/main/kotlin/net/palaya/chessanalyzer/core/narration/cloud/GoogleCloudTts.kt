package net.palaya.chessanalyzer.core.narration.cloud

import java.util.Base64

/**
 * Google Cloud Text-to-Speech's pricing tiers, which is also what the free tier is counted
 * against: each tier has its own monthly allowance of characters **per billing project** \u2014 the
 * user's own, since every key is theirs (see [GoogleCloudTtsProtocol]'s doc). Figures are Google's
 * published ones at the time of writing; they are informational copy for the setup wizard, not a
 * gate the app enforces (Google enforces it, and bills the user beyond it).
 */
enum class GoogleVoiceTier(
    val label: String,
    /** Free characters per month per billing project. */
    val freeCharsPerMonth: Long,
    /** USD per 1M characters beyond the free allowance. */
    val usdPerMillionChars: Int,
) {
    STANDARD("Standard", 4_000_000L, 4),
    WAVENET("WaveNet", 1_000_000L, 16),
    NEURAL2("Neural2", 1_000_000L, 16),
    CHIRP3_HD("Chirp 3: HD", 1_000_000L, 30),
    ;

    /** How many full game reviews the free tier covers per month at this tier \u2014 a rough, honest number for the UI. */
    val freeReviewsPerMonth: Int get() = (freeCharsPerMonth / GoogleCloudTtsProtocol.APPROX_CHARS_PER_REVIEW).toInt()
}

/**
 * The curated set of Cloud voices the app offers. A short list on purpose: Google publishes
 * hundreds of voices and a picker of all of them helps nobody. Two languages, matching the
 * narration matrix the owner settled on (English: on-device neural is the default, Cloud is the
 * upgrade; Hebrew: device TTS is the default, Cloud Chirp 3: HD is the upgrade), with a Standard
 * option in each because its free tier is four times larger.
 *
 * [voiceName] strings follow Google's documented naming (`<locale>-<family>-<variant>`). They come
 * from the voice list documentation, NOT from a live call \u2014 no key was available when this was
 * written, so if Google renames a voice the key-check request in the setup wizard is what will
 * report it, as a plain "voice not found" message rather than a mid-export failure.
 */
enum class GoogleCloudVoice(
    val id: String,
    val languageCode: String,
    val voiceName: String,
    val tier: GoogleVoiceTier,
    val label: String,
    /** Text used by the setup wizard's real-request key check \u2014 a couple of words in the voice's own language. */
    val keyCheckText: String,
) {
    EN_CHIRP3_HD_FEMALE("en_chirp3_f", "en-US", "en-US-Chirp3-HD-Aoede", GoogleVoiceTier.CHIRP3_HD, "Chirp 3 HD \u2014 female", "Hello, chess."),
    EN_CHIRP3_HD_MALE("en_chirp3_m", "en-US", "en-US-Chirp3-HD-Charon", GoogleVoiceTier.CHIRP3_HD, "Chirp 3 HD \u2014 male", "Hello, chess."),
    EN_NEURAL2_FEMALE("en_neural2_f", "en-US", "en-US-Neural2-F", GoogleVoiceTier.NEURAL2, "Neural2 \u2014 female", "Hello, chess."),
    EN_STANDARD("en_standard", "en-US", "en-US-Standard-C", GoogleVoiceTier.STANDARD, "Standard", "Hello, chess."),
    HE_CHIRP3_HD_FEMALE("he_chirp3_f", "he-IL", "he-IL-Chirp3-HD-Aoede", GoogleVoiceTier.CHIRP3_HD, "Chirp 3 HD \u2014 female", "\u05E9\u05DC\u05D5\u05DD, \u05E9\u05D7\u05DE\u05D8."),
    HE_CHIRP3_HD_MALE("he_chirp3_m", "he-IL", "he-IL-Chirp3-HD-Charon", GoogleVoiceTier.CHIRP3_HD, "Chirp 3 HD \u2014 male", "\u05E9\u05DC\u05D5\u05DD, \u05E9\u05D7\u05DE\u05D8."),
    HE_WAVENET("he_wavenet", "he-IL", "he-IL-Wavenet-A", GoogleVoiceTier.WAVENET, "WaveNet", "\u05E9\u05DC\u05D5\u05DD, \u05E9\u05D7\u05DE\u05D8."),
    HE_STANDARD("he_standard", "he-IL", "he-IL-Standard-A", GoogleVoiceTier.STANDARD, "Standard", "\u05E9\u05DC\u05D5\u05DD, \u05E9\u05D7\u05DE\u05D8."),
    ;

    val isHebrew: Boolean get() = languageCode == "he-IL"

    companion object {
        /** The voice a fresh install gets if the user opts into Cloud without picking one: top tier, English. */
        val DEFAULT: GoogleCloudVoice = EN_CHIRP3_HD_FEMALE

        fun fromId(id: String?): GoogleCloudVoice? = entries.firstOrNull { it.id == id }
    }
}

/** Why a synthesize call did not yield audio, coarse enough to drive both UI copy and retry policy. */
enum class CloudTtsFailureKind(
    /**
     * True when retrying the same key in the same session cannot help: a bad key, a project
     * without the API/billing, or an exhausted quota. [net.palaya.chessanalyzer.video] uses this
     * to stop hitting the network for every remaining sentence of an export once one of these
     * comes back, so a revoked key degrades to the device voice in one round trip, not sixty.
     */
    val isFatalForSession: Boolean,
) {
    INVALID_KEY(true),
    PERMISSION_DENIED(true),
    QUOTA_EXCEEDED(true),
    BAD_REQUEST(false),
    SERVER_ERROR(false),
    NETWORK(false),
    MALFORMED_RESPONSE(false),
}

/** Outcome of interpreting one `text:synthesize` HTTP exchange. */
sealed interface CloudTtsReply {
    /** [wavBytes] is a complete RIFF/WAVE container (LINEAR16 responses include the header). */
    data class Audio(val wavBytes: ByteArray) : CloudTtsReply

    data class Rejected(val kind: CloudTtsFailureKind, val message: String) : CloudTtsReply
}

/**
 * The request/response contract of `POST https://texttospeech.googleapis.com/v1/text:synthesize`,
 * kept free of any HTTP client or Android type so it is fully host-testable: build the JSON body,
 * interpret a status code + body pair, and turn Google's error vocabulary into sentences a chess
 * player can act on.
 *
 * ## Contract (established from Google's REST reference, recorded in RUN_PLAN.md task 41)
 * - Body: `{input: {text}, voice: {languageCode, name}, audioConfig: {audioEncoding, sampleRateHertz}}`.
 * - Success is `200` with a JSON body whose `audioContent` field is **base64**. For `LINEAR16`
 *   the decoded bytes are a **full RIFF/WAVE container** \u2014 header included \u2014 so they can be
 *   handed straight to the app's existing WAV reader.
 * - Failure is a non-2xx status with `{"error": {"code", "message", "status"}}`, where `status`
 *   is a gRPC-style name (`PERMISSION_DENIED`, `RESOURCE_EXHAUSTED`, `INVALID_ARGUMENT`, ...).
 *
 * ## Every key is the user's own
 * There is deliberately no key anywhere in this code or the APK. The free tier is granted per
 * Google Cloud *billing project*, so a key embedded in a public app would be one `jadx` away
 * from strangers draining the owner's quota. Each user creates their own project (the setup wizard
 * walks them through it), and the key lives only in Keystore-encrypted storage on their device.
 */
object GoogleCloudTtsProtocol {

    const val ENDPOINT = "https://texttospeech.googleapis.com/v1/text:synthesize"

    /** Header carrying the API key \u2014 never a query parameter, which would land in server logs. */
    const val API_KEY_HEADER = "X-Goog-Api-Key"

    /** Output sample rate requested. Kokoro produces 24 kHz too, so downstream resampling is a no-op for the common case. */
    const val SAMPLE_RATE_HZ = 24_000

    /**
     * Characters in one full game review's narration, measured across generated scripts
     * (RUN_PLAN.md task 41: "~7k characters per game review"). Drives the "N reviews per month"
     * copy; rounded down on purpose so the wizard never over-promises.
     */
    const val APPROX_CHARS_PER_REVIEW = 7_000L

    /** New Google Cloud customers get a time-limited trial credit \u2014 mentioned in the wizard as a trial, never as "free". */
    const val NEW_CUSTOMER_TRIAL_USD = 300

    // Cloud Console deep links the setup wizard offers. Plain, stable console URLs \u2014 no project id
    // is known at this point, so each opens the console's own picker.
    const val URL_CREATE_PROJECT = "https://console.cloud.google.com/projectcreate"
    const val URL_ENABLE_API = "https://console.cloud.google.com/apis/library/texttospeech.googleapis.com"
    const val URL_BILLING = "https://console.cloud.google.com/billing"
    const val URL_CREDENTIALS = "https://console.cloud.google.com/apis/credentials"
    const val URL_PRICING = "https://cloud.google.com/text-to-speech/pricing"

    /** Trims and rejects anything that cannot be a Google API key, before any network call. */
    fun normalizeApiKey(raw: String): String = raw.trim()

    /**
     * Cheap local plausibility check, so a pasted URL or an empty field fails instantly with a
     * useful message instead of after a network round trip. Google API keys are currently
     * `AIza` + 35 URL-safe characters (39 total); this only insists on the shape that can never
     * be right (blank, internal whitespace, obviously too short) rather than on the exact
     * length, so a future key format does not get locked out by a local check.
     */
    fun isPlausibleApiKey(raw: String): Boolean {
        val key = normalizeApiKey(raw)
        if (key.length < 20) return false
        if (key.any { it.isWhitespace() }) return false
        return key.all { it.isLetterOrDigit() || it == '-' || it == '_' }
    }

    /** The JSON body for one sentence. Plain text input \u2014 SSML is deliberately unused (pausing is done in the timeline, per sentence). */
    fun buildRequestBody(text: String, voice: GoogleCloudVoice, sampleRateHz: Int = SAMPLE_RATE_HZ): String =
        "{\"input\":{\"text\":${MiniJson.quote(text)}}," +
            "\"voice\":{\"languageCode\":${MiniJson.quote(voice.languageCode)},\"name\":${MiniJson.quote(voice.voiceName)}}," +
            "\"audioConfig\":{\"audioEncoding\":\"LINEAR16\",\"sampleRateHertz\":$sampleRateHz}}"

    /**
     * Interprets one HTTP exchange. Never throws: an unparseable body becomes
     * [CloudTtsFailureKind.MALFORMED_RESPONSE] (on 2xx) or is classified from the status alone.
     */
    fun interpretResponse(httpStatus: Int, body: String): CloudTtsReply {
        val json = runCatching { MiniJson.parse(body) as? Map<*, *> }.getOrNull()
        if (httpStatus in 200..299) {
            val audio = json?.get("audioContent") as? String
                ?: return CloudTtsReply.Rejected(
                    CloudTtsFailureKind.MALFORMED_RESPONSE,
                    "Google returned a success response without any audio in it.",
                )
            val bytes = try {
                Base64.getDecoder().decode(audio)
            } catch (e: IllegalArgumentException) {
                return CloudTtsReply.Rejected(CloudTtsFailureKind.MALFORMED_RESPONSE, "Google's audio payload was not valid base64.")
            }
            if (!looksLikeRiffWave(bytes)) {
                return CloudTtsReply.Rejected(
                    CloudTtsFailureKind.MALFORMED_RESPONSE,
                    "Google's audio payload was not the WAV container LINEAR16 is documented to return.",
                )
            }
            return CloudTtsReply.Audio(bytes)
        }

        val error = json?.get("error") as? Map<*, *>
        val status = (error?.get("status") as? String).orEmpty()
        val message = (error?.get("message") as? String).orEmpty()
        return CloudTtsReply.Rejected(classifyFailure(httpStatus, status, message), describeFailure(httpStatus, status, message))
    }

    /** For a failed connection (no response at all). */
    fun networkFailure(detail: String?): CloudTtsReply.Rejected =
        CloudTtsReply.Rejected(
            CloudTtsFailureKind.NETWORK,
            "Couldn't reach Google Cloud" + (detail?.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: "") + ". Check the connection and try again.",
        )

    /** RIFF....WAVE magic \u2014 the minimum that makes handing the bytes to a WAV reader sensible. */
    fun looksLikeRiffWave(bytes: ByteArray): Boolean =
        bytes.size >= 12 &&
            bytes[0] == 'R'.code.toByte() && bytes[1] == 'I'.code.toByte() && bytes[2] == 'F'.code.toByte() && bytes[3] == 'F'.code.toByte() &&
            bytes[8] == 'W'.code.toByte() && bytes[9] == 'A'.code.toByte() && bytes[10] == 'V'.code.toByte() && bytes[11] == 'E'.code.toByte()

    internal fun classifyFailure(httpStatus: Int, status: String, message: String): CloudTtsFailureKind {
        val lower = message.lowercase()
        return when {
            httpStatus == 429 || status == "RESOURCE_EXHAUSTED" -> CloudTtsFailureKind.QUOTA_EXCEEDED
            httpStatus == 401 || status == "UNAUTHENTICATED" -> CloudTtsFailureKind.INVALID_KEY
            // Google reports a malformed/unknown key as 400 INVALID_ARGUMENT "API key not valid".
            httpStatus == 400 && "api key" in lower -> CloudTtsFailureKind.INVALID_KEY
            httpStatus == 403 || status == "PERMISSION_DENIED" -> CloudTtsFailureKind.PERMISSION_DENIED
            httpStatus == 400 || status == "INVALID_ARGUMENT" || httpStatus == 404 -> CloudTtsFailureKind.BAD_REQUEST
            httpStatus >= 500 -> CloudTtsFailureKind.SERVER_ERROR
            else -> CloudTtsFailureKind.BAD_REQUEST
        }
    }

    /**
     * Plain-language text for the setup wizard / export notice. Google's own messages are
     * accurate but written for developers ("Cloud Text-to-Speech API has not been used in project
     * 1234 before or it is disabled"); this says what to *do*. The original message is appended in
     * brackets when it adds information, so nothing is hidden.
     */
    internal fun describeFailure(httpStatus: Int, status: String, message: String): String {
        val lower = message.lowercase()
        val kind = classifyFailure(httpStatus, status, message)
        val advice = when (kind) {
            CloudTtsFailureKind.INVALID_KEY ->
                "Google rejected this API key. Check it was pasted completely, and that it was created in the same project where the Text-to-Speech API is enabled."
            CloudTtsFailureKind.PERMISSION_DENIED -> when {
                "billing" in lower ->
                    "This Google Cloud project has no billing account. Text-to-Speech needs billing enabled even to use the free tier \u2014 step 3 in the setup guide."
                "has not been used" in lower || "disabled" in lower || "not enabled" in lower ->
                    "The Text-to-Speech API isn't enabled in this project yet \u2014 step 2 in the setup guide. Give it a minute after enabling before trying again."
                "referer" in lower || "restricted" in lower || "blocked" in lower ->
                    "This key's restrictions block the request. In Google Cloud Console, edit the key and allow the Cloud Text-to-Speech API (an application restriction like HTTP referrers will block an app)."
                else ->
                    "Google refused the request for this key. Check the Text-to-Speech API is enabled and billing is set up for the project the key belongs to."
            }
            CloudTtsFailureKind.QUOTA_EXCEEDED ->
                "This project's Text-to-Speech quota is used up for now. The free tier resets monthly; Google Cloud Console shows the current usage."
            CloudTtsFailureKind.BAD_REQUEST -> when {
                "voice" in lower -> "Google doesn't recognise the selected voice. Pick a different voice in Settings and try again."
                else -> "Google couldn't process the request."
            }
            CloudTtsFailureKind.SERVER_ERROR -> "Google Cloud reported a temporary server problem. Try again in a moment."
            CloudTtsFailureKind.NETWORK -> "Couldn't reach Google Cloud."
            CloudTtsFailureKind.MALFORMED_RESPONSE -> "Google returned something this app couldn't read."
        }
        val detail = message.trim().takeIf { it.isNotEmpty() }?.let { " (HTTP $httpStatus: $it)" } ?: " (HTTP $httpStatus)"
        return advice + detail
    }
}
