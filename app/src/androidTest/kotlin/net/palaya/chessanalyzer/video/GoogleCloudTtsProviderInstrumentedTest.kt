package net.palaya.chessanalyzer.video

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import kotlinx.coroutines.runBlocking
import net.palaya.chessanalyzer.core.narration.cloud.CloudTtsFailureKind
import net.palaya.chessanalyzer.core.narration.cloud.GoogleCloudTtsProtocol
import net.palaya.chessanalyzer.core.narration.cloud.GoogleCloudVoice
import net.palaya.chessanalyzer.core.narration.cloud.MiniJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * [GoogleCloudTtsProvider] end to end **except the socket**: a scripted [CloudTtsTransport] stands
 * in for Google, and everything above it — request body, key header, JSON -> base64 -> RIFF ->
 * [WavUtil], the session-fatal latch, key validation, and the coordinator's per-segment fallback
 * — is exercised for real on the device.
 *
 * What this does NOT prove: that Google's live endpoint accepts these requests. No API key was
 * available when this was written, so the live path is unverified (see `OkHttpCloudTtsTransport`).
 * That is why the seam sits exactly at the HTTP exchange and nowhere higher.
 */
@RunWith(AndroidJUnit4::class)
class GoogleCloudTtsProviderInstrumentedTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val key = "AIzaSyD-not_a_real_key-0123456789abcdefg"

    private data class Request(val url: String, val apiKey: String, val body: String)

    /** Replies in order; an [IOException] entry is thrown, anything else is returned. */
    private class FakeTransport(private vararg val replies: Any) : CloudTtsTransport {
        val requests = mutableListOf<Request>()
        override fun post(url: String, apiKey: String, jsonBody: String): CloudTtsHttpReply {
            requests += Request(url, apiKey, jsonBody)
            val r = replies.getOrNull(requests.size - 1) ?: error("no scripted reply for request #${requests.size}")
            if (r is IOException) throw r
            return r as CloudTtsHttpReply
        }
    }

    private fun googleWavJson(sampleRate: Int, frames: Int): CloudTtsHttpReply {
        val data = ByteBuffer.allocate(frames * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until frames) data.putShort(((i % 200) * 50 - 5000).toShort())
        val wav = WavUtil.buildWavHeader(frames * 2, sampleRate, 1, 16) + data.array()
        return CloudTtsHttpReply(200, """{"audioContent":"${Base64.getEncoder().encodeToString(wav)}"}""")
    }

    private fun googleError(code: Int, status: String, message: String) =
        CloudTtsHttpReply(code, """{"error":{"code":$code,"message":${MiniJson.quote(message)},"status":"$status"}}""")

    private fun scratch(name: String) = File(context.cacheDir, "cloud_tts_test/$name").apply { parentFile?.mkdirs(); delete() }

    @Test
    fun successfulReplyBecomesAReadableWavViaWavUtilAndTheRequestCarriesKeyAndText(): Unit = runBlocking {
        val transport = FakeTransport(googleWavJson(GoogleCloudTtsProtocol.SAMPLE_RATE_HZ, 12_000)) // 0.5 s
        val provider = GoogleCloudTtsProvider(" $key ", GoogleCloudVoice.EN_CHIRP3_HD_FEMALE, transport)
        assertTrue(provider.prepare())

        val out = scratch("ok.wav")
        val result = provider.synthesize("White to play. Can you find it?", out)
        assertTrue("expected Success, got $result", result is SynthesisResult.Success)
        result as SynthesisResult.Success
        assertEquals(500L, result.durationMs)

        val info = WavUtil.readHeader(out)
        assertNotNull("WavUtil must parse Google's RIFF container directly", info)
        assertEquals(GoogleCloudTtsProtocol.SAMPLE_RATE_HZ, info!!.sampleRate)
        assertEquals(1, info.channels)
        assertEquals(16, info.bitsPerSample)
        assertEquals(24_000L, info.dataSize)
        assertEquals(12_000, WavUtil.readAsMono16(out, GoogleCloudTtsProtocol.SAMPLE_RATE_HZ).size)
        assertNull(provider.lastFailure)

        val req = transport.requests.single()
        assertEquals(GoogleCloudTtsProtocol.ENDPOINT, req.url)
        assertEquals("key must be trimmed and passed for the header, never in the URL", key, req.apiKey)
        assertFalse(req.url.contains(key))
        val json = MiniJson.parse(req.body) as Map<*, *>
        assertEquals("White to play. Can you find it?", (json["input"] as Map<*, *>)["text"])
        assertEquals("en-US-Chirp3-HD-Aoede", (json["voice"] as Map<*, *>)["name"])
        assertEquals("google/en_chirp3_f/24000", provider.narrationCacheFingerprint())
    }

    @Test
    fun permissionDeniedIsReportedInPlainLanguageAndLatchesSoLaterSentencesSkipTheNetwork(): Unit = runBlocking {
        val transport = FakeTransport(
            googleError(403, "PERMISSION_DENIED", "This API method requires billing to be enabled. Please enable billing on project #42"),
        )
        val provider = GoogleCloudTtsProvider(key, GoogleCloudVoice.DEFAULT, transport)

        val first = provider.synthesize("First sentence.", scratch("f1.wav"))
        assertTrue(first is SynthesisResult.Failure)
        val reason = (first as SynthesisResult.Failure).reason
        assertTrue(reason, reason.contains("billing") && reason.contains("step 3"))
        assertFalse("must not surface raw JSON", reason.contains("{"))
        assertEquals(CloudTtsFailureKind.PERMISSION_DENIED, provider.lastFailure?.kind)

        val second = provider.synthesize("Second sentence.", scratch("f2.wav"))
        assertTrue(second is SynthesisResult.Failure)
        assertEquals("a session-fatal failure must not cost a second round trip", 1, transport.requests.size)
        assertEquals(reason, (second as SynthesisResult.Failure).reason)
    }

    @Test
    fun quotaExhaustedIs429AndLatches(): Unit = runBlocking {
        val transport = FakeTransport(googleError(429, "RESOURCE_EXHAUSTED", "Quota exceeded for quota metric 'Characters'"))
        val provider = GoogleCloudTtsProvider(key, GoogleCloudVoice.DEFAULT, transport)
        val r = provider.synthesize("Hello.", scratch("q.wav")) as SynthesisResult.Failure
        assertTrue(r.reason, r.reason.contains("quota"))
        provider.synthesize("Again.", scratch("q2.wav"))
        assertEquals(1, transport.requests.size)
    }

    @Test
    fun networkFailureIsNotLatchedSoTheNextSentenceIsRetried(): Unit = runBlocking {
        val transport = FakeTransport(
            IOException("Unable to resolve host texttospeech.googleapis.com"),
            googleWavJson(24_000, 2_400),
        )
        val provider = GoogleCloudTtsProvider(key, GoogleCloudVoice.DEFAULT, transport)

        val first = provider.synthesize("One.", scratch("n1.wav")) as SynthesisResult.Failure
        assertTrue(first.reason, first.reason.contains("Unable to resolve host"))
        assertEquals(CloudTtsFailureKind.NETWORK, provider.lastFailure?.kind)

        val second = provider.synthesize("Two.", scratch("n2.wav"))
        assertTrue("transient failure must not block the next sentence: $second", second is SynthesisResult.Success)
        assertEquals(2, transport.requests.size)
        assertNull(provider.lastFailure)
    }

    @Test
    fun nonRiffPayloadOnA200IsRejectedAndNothingIsLeftOnDisk(): Unit = runBlocking {
        val notWav = Base64.getEncoder().encodeToString(ByteArray(300) { 0x11 })
        val transport = FakeTransport(CloudTtsHttpReply(200, """{"audioContent":"$notWav"}"""))
        val provider = GoogleCloudTtsProvider(key, GoogleCloudVoice.DEFAULT, transport)
        val out = scratch("bad.wav")
        val r = provider.synthesize("Hello.", out)
        assertTrue(r is SynthesisResult.Failure)
        assertFalse("a rejected payload must not be left where the exporter could pick it up", out.exists())
        assertEquals(CloudTtsFailureKind.MALFORMED_RESPONSE, provider.lastFailure?.kind)
    }

    @Test
    fun implausibleKeyFailsPrepareWithoutTouchingTheNetwork(): Unit = runBlocking {
        val transport = FakeTransport()
        val provider = GoogleCloudTtsProvider("paste your key here", GoogleCloudVoice.DEFAULT, transport)
        assertFalse(provider.prepare())
        assertEquals(0, transport.requests.size)
    }

    @Test
    fun validateKeyAcceptsAKeyThatProducesAudioAndCleansUpTheProbeFile(): Unit = runBlocking {
        val transport = FakeTransport(googleWavJson(24_000, 9_600)) // 0.4 s
        val dir = File(context.cacheDir, "cloud_key_check_test").apply { deleteRecursively() }
        val result = GoogleCloudTtsProvider.validateKey(key, GoogleCloudVoice.HE_CHIRP3_HD_MALE, transport, dir)
        assertTrue("expected Valid, got $result", result is CloudKeyCheck.Valid)
        assertEquals(400L, (result as CloudKeyCheck.Valid).durationMs)
        assertEquals(24_000, result.sampleRate)
        // The probe used the Hebrew voice's own check text, so a Hebrew project is validated in Hebrew.
        val json = MiniJson.parse(transport.requests.single().body) as Map<*, *>
        assertEquals(GoogleCloudVoice.HE_CHIRP3_HD_MALE.keyCheckText, (json["input"] as Map<*, *>)["text"])
        assertEquals("he-IL", (json["voice"] as Map<*, *>)["languageCode"])
        assertTrue("probe WAV must not linger", dir.listFiles().isNullOrEmpty())
    }

    @Test
    fun validateKeyRejectsABadKeyWithGooglesVerdictAndAnImplausibleOneLocally(): Unit = runBlocking {
        val transport = FakeTransport(googleError(400, "INVALID_ARGUMENT", "API key not valid. Please pass a valid API key."))
        val dir = File(context.cacheDir, "cloud_key_check_test2")
        val rejected = GoogleCloudTtsProvider.validateKey(key, GoogleCloudVoice.DEFAULT, transport, dir)
        assertTrue(rejected is CloudKeyCheck.Invalid)
        rejected as CloudKeyCheck.Invalid
        assertEquals(CloudTtsFailureKind.INVALID_KEY, rejected.kind)
        assertTrue(rejected.message, rejected.message.startsWith("Google rejected this API key"))
        assertEquals(1, transport.requests.size)

        val local = GoogleCloudTtsProvider.validateKey("nope", GoogleCloudVoice.DEFAULT, transport, dir)
        assertTrue(local is CloudKeyCheck.Invalid)
        assertTrue((local as CloudKeyCheck.Invalid).message.contains("AIza"))
        assertEquals("an implausible key must be refused before any network call", 1, transport.requests.size)
    }

    /**
     * The whole-export consequence of a dead key: the coordinator falls back to the device voice
     * for EVERY segment, says so once in plain language, and the key costs exactly one round trip.
     */
    @Test
    fun coordinatorFallsBackToDeviceVoiceForTheWholeScriptAfterOneFatalReply(): Unit = runBlocking {
        val transport = FakeTransport(googleError(403, "PERMISSION_DENIED", "Cloud Text-to-Speech API has not been used in project 7 before or it is disabled."))
        val provider = GoogleCloudTtsProvider(key, GoogleCloudVoice.DEFAULT, transport)
        val script = TestScripts.syntheticScript()
        val store = NarrationStore.forDirectory(File(context.cacheDir, "cloud_fallback_store").apply { deleteRecursively() })
        val outDir = File(context.cacheDir, "cloud_fallback_out").apply { deleteRecursively() }

        val outcome = NarrationCoordinator(provider, DeviceTtsProvider(context), store).synthesizeAll(script, outDir)

        assertEquals(script.segments.size, outcome.results.size)
        assertEquals(0, outcome.primaryUsedCount)
        assertEquals(script.segments.size, outcome.fallbackCount)
        assertEquals("one fatal reply must stop all further network calls", 1, transport.requests.size)
        val notice = outcome.notice
        assertNotNull(notice)
        assertTrue(notice!!, notice.contains("Cloud voice (Google)") && notice.contains("device voice"))
        assertTrue(notice, notice.contains("isn't enabled"))
    }
}
