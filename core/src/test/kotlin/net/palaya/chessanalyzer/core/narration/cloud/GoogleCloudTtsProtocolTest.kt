package net.palaya.chessanalyzer.core.narration.cloud

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The whole Google Cloud TTS contract that can be proven without a key: request shape, the
 * JSON -> base64 -> RIFF response path, and the mapping of Google's error vocabulary onto
 * plain-language advice + retry policy. The live network call itself is NOT covered here (or
 * anywhere yet \u2014 no key was available), which is exactly why everything else is.
 */
class GoogleCloudTtsProtocolTest {

    // ---- request ----

    @Test
    fun requestBodyCarriesTextVoiceAndLinear16Config() {
        val body = GoogleCloudTtsProtocol.buildRequestBody("White to play. Can you find it?", GoogleCloudVoice.EN_CHIRP3_HD_FEMALE)
        val json = MiniJson.parse(body) as Map<*, *>
        val input = json["input"] as Map<*, *>
        val voice = json["voice"] as Map<*, *>
        val audio = json["audioConfig"] as Map<*, *>
        assertEquals("White to play. Can you find it?", input["text"])
        assertEquals("en-US", voice["languageCode"])
        assertEquals("en-US-Chirp3-HD-Aoede", voice["name"])
        assertEquals("LINEAR16", audio["audioEncoding"])
        assertEquals(24000.0, audio["sampleRateHertz"])
        assertFalse("SSML must not be used \u2014 pauses are timeline gaps, per sentence", body.contains("ssml"))
    }

    @Test
    fun requestBodyEscapesQuotesNewlinesAndPassesHebrewThrough() {
        val text = "He said \"check\".\nThen: \u05E9\u05D7 \u05DE\u05D8"
        val body = GoogleCloudTtsProtocol.buildRequestBody(text, GoogleCloudVoice.HE_CHIRP3_HD_MALE)
        val json = MiniJson.parse(body) as Map<*, *>
        assertEquals(text, (json["input"] as Map<*, *>)["text"])
        assertEquals("he-IL", (json["voice"] as Map<*, *>)["languageCode"])
    }

    // ---- success path: JSON -> base64 -> RIFF ----

    @Test
    fun successResponseDecodesToTheExactWavBytesGoogleSent() {
        val wav = syntheticWav(sampleRate = 24000, frames = 480)
        val body = """{"audioContent": "${Base64.getEncoder().encodeToString(wav)}"}"""
        val reply = GoogleCloudTtsProtocol.interpretResponse(200, body)
        assertTrue("expected Audio, got $reply", reply is CloudTtsReply.Audio)
        assertArrayEquals(wav, (reply as CloudTtsReply.Audio).wavBytes)
        assertTrue(GoogleCloudTtsProtocol.looksLikeRiffWave(reply.wavBytes))
    }

    @Test
    fun successResponseWithoutAudioContentIsMalformedNotAudio() {
        val reply = GoogleCloudTtsProtocol.interpretResponse(200, """{"somethingElse": 1}""")
        assertEquals(CloudTtsFailureKind.MALFORMED_RESPONSE, (reply as CloudTtsReply.Rejected).kind)
        assertFalse(reply.kind.isFatalForSession)
    }

    @Test
    fun successResponseWithInvalidBase64IsMalformed() {
        val reply = GoogleCloudTtsProtocol.interpretResponse(200, """{"audioContent": "@@not base64@@"}""")
        assertEquals(CloudTtsFailureKind.MALFORMED_RESPONSE, (reply as CloudTtsReply.Rejected).kind)
    }

    @Test
    fun successResponseWhosePayloadIsNotRiffIsRejectedRatherThanWrittenAsAudio() {
        // The exact defect class this project has been burned by: valid-looking bytes that are not
        // PCM WAV getting embedded as if they were, producing noise in the exported video.
        val mp3ish = byteArrayOf(0xFF.toByte(), 0xFB.toByte(), 0x90.toByte(), 0x00) + ByteArray(64)
        val body = """{"audioContent": "${Base64.getEncoder().encodeToString(mp3ish)}"}"""
        val reply = GoogleCloudTtsProtocol.interpretResponse(200, body)
        assertEquals(CloudTtsFailureKind.MALFORMED_RESPONSE, (reply as CloudTtsReply.Rejected).kind)
        assertTrue(reply.message.contains("WAV"))
    }

    @Test
    fun successResponseWithNonJsonBodyIsMalformed() {
        val reply = GoogleCloudTtsProtocol.interpretResponse(200, "<html>proxy login</html>")
        assertEquals(CloudTtsFailureKind.MALFORMED_RESPONSE, (reply as CloudTtsReply.Rejected).kind)
    }

    // ---- error mapping ----

    private fun googleError(code: Int, status: String, message: String) =
        """{"error": {"code": $code, "message": ${MiniJson.quote(message)}, "status": "$status"}}"""

    @Test
    fun invalidKeyIs400InvalidArgumentAndIsFatalForTheSession() {
        val reply = GoogleCloudTtsProtocol.interpretResponse(
            400,
            googleError(400, "INVALID_ARGUMENT", "API key not valid. Please pass a valid API key."),
        ) as CloudTtsReply.Rejected
        assertEquals(CloudTtsFailureKind.INVALID_KEY, reply.kind)
        assertTrue(reply.kind.isFatalForSession)
        assertTrue(reply.message, reply.message.startsWith("Google rejected this API key"))
        assertTrue("original detail must not be hidden", reply.message.contains("HTTP 400"))
    }

    @Test
    fun apiNotEnabledIs403AndTheAdvicePointsAtTheEnableStep() {
        val reply = GoogleCloudTtsProtocol.interpretResponse(
            403,
            googleError(403, "PERMISSION_DENIED", "Cloud Text-to-Speech API has not been used in project 123 before or it is disabled. Enable it by visiting ..."),
        ) as CloudTtsReply.Rejected
        assertEquals(CloudTtsFailureKind.PERMISSION_DENIED, reply.kind)
        assertTrue(reply.kind.isFatalForSession)
        assertTrue(reply.message, reply.message.contains("isn't enabled"))
        assertTrue(reply.message, reply.message.contains("step 2"))
    }

    @Test
    fun billingDisabledIs403AndTheAdvicePointsAtTheBillingStep() {
        val reply = GoogleCloudTtsProtocol.interpretResponse(
            403,
            googleError(403, "PERMISSION_DENIED", "This API method requires billing to be enabled. Please enable billing on project #123"),
        ) as CloudTtsReply.Rejected
        assertEquals(CloudTtsFailureKind.PERMISSION_DENIED, reply.kind)
        assertTrue(reply.message, reply.message.contains("billing"))
        assertTrue(reply.message, reply.message.contains("step 3"))
    }

    @Test
    fun keyRestrictionBlockIs403WithRestrictionAdvice() {
        val reply = GoogleCloudTtsProtocol.interpretResponse(
            403,
            googleError(403, "PERMISSION_DENIED", "Requests from referer <empty> are blocked."),
        ) as CloudTtsReply.Rejected
        assertEquals(CloudTtsFailureKind.PERMISSION_DENIED, reply.kind)
        assertTrue(reply.message, reply.message.contains("restrictions"))
    }

    @Test
    fun quotaExhaustedIs429AndFatalForTheSession() {
        val reply = GoogleCloudTtsProtocol.interpretResponse(
            429,
            googleError(429, "RESOURCE_EXHAUSTED", "Quota exceeded for quota metric 'Characters'"),
        ) as CloudTtsReply.Rejected
        assertEquals(CloudTtsFailureKind.QUOTA_EXCEEDED, reply.kind)
        assertTrue(reply.kind.isFatalForSession)
        assertTrue(reply.message, reply.message.contains("quota"))
        assertFalse("must not be raw JSON", reply.message.contains("{"))
    }

    @Test
    fun unknownVoiceIs400BadRequestNotFatalAndNamesTheVoice() {
        val reply = GoogleCloudTtsProtocol.interpretResponse(
            400,
            googleError(400, "INVALID_ARGUMENT", "Voice 'en-US-Nope' does not exist. Is it misspelled?"),
        ) as CloudTtsReply.Rejected
        assertEquals(CloudTtsFailureKind.BAD_REQUEST, reply.kind)
        assertFalse(reply.kind.isFatalForSession)
        assertTrue(reply.message, reply.message.contains("voice"))
    }

    @Test
    fun serverErrorsAreTransientAndNotFatal() {
        val reply = GoogleCloudTtsProtocol.interpretResponse(503, googleError(503, "UNAVAILABLE", "The service is currently unavailable.")) as CloudTtsReply.Rejected
        assertEquals(CloudTtsFailureKind.SERVER_ERROR, reply.kind)
        assertFalse(reply.kind.isFatalForSession)
    }

    @Test
    fun errorWithNonJsonBodyIsStillClassifiedByStatusCode() {
        val r403 = GoogleCloudTtsProtocol.interpretResponse(403, "Forbidden") as CloudTtsReply.Rejected
        assertEquals(CloudTtsFailureKind.PERMISSION_DENIED, r403.kind)
        val r429 = GoogleCloudTtsProtocol.interpretResponse(429, "") as CloudTtsReply.Rejected
        assertEquals(CloudTtsFailureKind.QUOTA_EXCEEDED, r429.kind)
        val r401 = GoogleCloudTtsProtocol.interpretResponse(401, "") as CloudTtsReply.Rejected
        assertEquals(CloudTtsFailureKind.INVALID_KEY, r401.kind)
        val r500 = GoogleCloudTtsProtocol.interpretResponse(500, "<html>oops</html>") as CloudTtsReply.Rejected
        assertEquals(CloudTtsFailureKind.SERVER_ERROR, r500.kind)
    }

    @Test
    fun networkFailureIsNotFatalAndReadsAsAdvice() {
        val reply = GoogleCloudTtsProtocol.networkFailure("Unable to resolve host")
        assertEquals(CloudTtsFailureKind.NETWORK, reply.kind)
        assertFalse(reply.kind.isFatalForSession)
        assertTrue(reply.message.contains("Unable to resolve host"))
        assertTrue(GoogleCloudTtsProtocol.networkFailure(null).message.endsWith("try again."))
    }

    // ---- key plausibility ----

    @Test
    fun plausibleKeyCheckRejectsBlankWhitespaceAndUrlsButAcceptsAGoogleShapedKey() {
        assertFalse(GoogleCloudTtsProtocol.isPlausibleApiKey(""))
        assertFalse(GoogleCloudTtsProtocol.isPlausibleApiKey("   "))
        assertFalse(GoogleCloudTtsProtocol.isPlausibleApiKey("AIza short"))
        assertFalse(GoogleCloudTtsProtocol.isPlausibleApiKey("https://console.cloud.google.com/apis/credentials"))
        assertFalse(GoogleCloudTtsProtocol.isPlausibleApiKey("AIzaSyA_thisHas a space_inside_the_key_0123"))
        assertTrue(GoogleCloudTtsProtocol.isPlausibleApiKey("AIzaSyD-not_a_real_key-0123456789abcdefg"))
        assertTrue("surrounding whitespace is a paste artefact, not an error", GoogleCloudTtsProtocol.isPlausibleApiKey("  AIzaSyD-not_a_real_key-0123456789abcdefg\n"))
        assertEquals("AIzaSyD-not_a_real_key-0123456789abcdefg", GoogleCloudTtsProtocol.normalizeApiKey("  AIzaSyD-not_a_real_key-0123456789abcdefg\n"))
    }

    // ---- voice catalogue / free-tier copy ----

    @Test
    fun voiceCatalogueIdsAreUniqueAndRoundTrip() {
        val ids = GoogleCloudVoice.entries.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        for (v in GoogleCloudVoice.entries) assertEquals(v, GoogleCloudVoice.fromId(v.id))
        assertEquals(null, GoogleCloudVoice.fromId("nope"))
        assertEquals(null, GoogleCloudVoice.fromId(null))
    }

    @Test
    fun hebrewVoicesExistUpToChirp3HdAndTheDefaultIsEnglishTopTier() {
        val hebrew = GoogleCloudVoice.entries.filter { it.isHebrew }
        assertTrue(hebrew.isNotEmpty())
        assertTrue(hebrew.all { it.languageCode == "he-IL" && it.voiceName.startsWith("he-IL-") })
        assertTrue("Chirp 3: HD must be reachable for Hebrew \u2014 it is the whole point of the Cloud upgrade there",
            hebrew.any { it.tier == GoogleVoiceTier.CHIRP3_HD })
        assertTrue(hebrew.all { it.keyCheckText.any { c -> c in '\u05D0'..'\u05EA' } })
        assertEquals(GoogleVoiceTier.CHIRP3_HD, GoogleCloudVoice.DEFAULT.tier)
        assertEquals("en-US", GoogleCloudVoice.DEFAULT.languageCode)
        assertNotNull(GoogleCloudVoice.entries.firstOrNull { !it.isHebrew && it.tier == GoogleVoiceTier.STANDARD })
    }

    @Test
    fun freeTierCopyIsRoughly140ReviewsAtTopTierAndFourTimesThatOnStandard() {
        assertEquals(142, GoogleVoiceTier.CHIRP3_HD.freeReviewsPerMonth)
        assertEquals(142, GoogleVoiceTier.NEURAL2.freeReviewsPerMonth)
        assertEquals(571, GoogleVoiceTier.STANDARD.freeReviewsPerMonth)
        assertEquals(30, GoogleVoiceTier.CHIRP3_HD.usdPerMillionChars)
        assertEquals(4, GoogleVoiceTier.STANDARD.usdPerMillionChars)
    }

    // ---- MiniJson itself ----

    @Test
    fun miniJsonParsesNestedStructuresEscapesAndUnicode() {
        val parsed = MiniJson.parse("""{"a": [1, 2.5, -3e2, true, false, null], "b": {"c": "x\"y\\z\n\u05E9"}, "d": ""}""") as Map<*, *>
        assertEquals(listOf(1.0, 2.5, -300.0, true, false, null), parsed["a"])
        assertEquals("x\"y\\z\n\u05E9", (parsed["b"] as Map<*, *>)["c"])
        assertEquals("", parsed["d"])
    }

    @Test
    fun miniJsonQuoteRoundTripsThroughParse() {
        val original = "tab\there \"quoted\" back\\slash  ctrl \u05E9\u05DC\u05D5\u05DD"
        assertEquals(original, MiniJson.parse(MiniJson.quote(original)))
    }

    @Test(expected = MiniJson.JsonException::class)
    fun miniJsonRejectsTrailingGarbage() {
        MiniJson.parse("""{"a": 1} extra""")
    }

    @Test(expected = MiniJson.JsonException::class)
    fun miniJsonRejectsUnterminatedObject() {
        MiniJson.parse("""{"a": 1""")
    }

    // ---- helpers ----

    /** A minimal RIFF/WAVE container: 44-byte header + [frames] 16-bit mono samples of a ramp. */
    private fun syntheticWav(sampleRate: Int, frames: Int): ByteArray {
        val dataSize = frames * 2
        val buf = ByteBuffer.allocate(44 + dataSize).order(ByteOrder.LITTLE_ENDIAN)
        buf.put("RIFF".toByteArray(Charsets.US_ASCII)).putInt(36 + dataSize).put("WAVE".toByteArray(Charsets.US_ASCII))
        buf.put("fmt ".toByteArray(Charsets.US_ASCII)).putInt(16).putShort(1).putShort(1).putInt(sampleRate).putInt(sampleRate * 2).putShort(2).putShort(16)
        buf.put("data".toByteArray(Charsets.US_ASCII)).putInt(dataSize)
        for (i in 0 until frames) buf.putShort((i * 37 % 20000 - 10000).toShort())
        return buf.array()
    }
}
