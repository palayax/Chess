package net.palaya.chessanalyzer.data.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** `models.json` as built (D2e; docs/MODEL_DOWNLOAD_DESIGN.md §3.2): parse rules, required fields, unknown ids. */
class ModelManifestTest {

    private val base = "https://github.com/palayax/palaya-chess/releases/download/"
    private val sha = "1a298aa575a0" + "b".repeat(52)
    private val tarSha = "7190c4801645bf31d10996477a04082019d9cf492ad3d5aeef7b1f7cf10a5dea"

    private fun valid(bytes: ByteArray): ModelManifest {
        val p = ModelManifest.parse(bytes)
        assertTrue("expected a valid manifest, got $p", p is ManifestParse.Valid)
        return (p as ManifestParse.Valid).manifest
    }

    private fun invalid(bytes: ByteArray): String {
        val p = ModelManifest.parse(bytes)
        assertTrue("expected an invalid manifest, got $p", p is ManifestParse.Invalid)
        return (p as ManifestParse.Invalid).reason
    }

    private fun both() = TestManifests.manifest(
        TestManifests.netEntry(base, "models-2026.10", "nn-1a298aa575a0.nnue", 98_511_183, sha),
        TestManifests.voiceEntry(base, "models-2026.10", "kokoro-int8-en-v0_19.tar", 158_269_440, tarSha, version = "v0_19"),
    )

    @Test
    fun theDesignsTwoEntriesParseToEveryField() {
        val m = valid(both())
        assertEquals(1, m.schemaVersion)
        assertEquals("2026-10-07T12:00:00Z", m.generatedAt)
        assertEquals(2, m.entries.size)
        val net = m.entries[0]
        assertEquals(ModelKind.NET, net.kind)
        assertEquals("Chess engine data", net.displayName)
        assertEquals("nn-1a298aa575a0", net.version)
        assertEquals("nn-1a298aa575a0.nnue", net.fileName)
        assertEquals("${base}models-2026.10/nn-1a298aa575a0.nnue", net.url)
        assertEquals(98_511_183L, net.sizeBytes)
        assertEquals(sha, net.sha256)
        assertEquals(1, net.minVersionCode)
        assertNull(net.maxVersionCode)
        assertEquals(ModelCompat.StockfishNnue(0x6a448afaL, 0xa85b2205L, "sf_19"), net.compat)
        assertNull(net.runtime)
        val voice = m.entries[1]
        assertEquals(ModelKind.VOICE, voice.kind)
        assertEquals(ModelCompat.SherpaKokoro("kokoro-v0_19"), voice.compat)
        assertEquals(ModelRuntime("sherpa-onnx", "1.13.8", "1.13.8"), voice.runtime)
        assertEquals(ModelFileSpec(voice.url, voice.fileName, voice.sizeBytes, voice.sha256), voice.toFileSpec())
    }

    @Test
    fun theManifestPublishModelsShWritesParses() {
        // The exact text scripts/publish_models.sh generates (copied from its here-doc, values filled in).
        val text = """
            {
              "schemaVersion": 1,
              "generatedAt": "2026-10-07T07:40:00Z",
              "models": [
                {
                  "id": "engine-net",
                  "displayName": "Chess engine data",
                  "version": "nn-1a298aa575a0",
                  "fileName": "nn-1a298aa575a0.nnue",
                  "url": "${base}models-2026.10/nn-1a298aa575a0.nnue",
                  "size": 98511183,
                  "sha256": "$sha",
                  "minVersionCode": 1,
                  "maxVersionCode": null,
                  "compat": { "kind": "stockfish-nnue", "version": "0x6a448afa", "archHash": "a85b2205", "engineTag": "sf_19" }
                }
              ]
            }
        """.trimIndent()
        assertEquals(1, valid(text.toByteArray()).entries.size)
    }

    @Test
    fun theVoiceEntryPublishModelsShWritesSinceD2fCarriesTheGzAndItsTar() {
        // Copied from dist/models/models.json as `publish_models.sh models-2026.10 --min-version-code 2` wrote it (D2f).
        val text = """
            {
              "schemaVersion": 1,
              "generatedAt": "2026-10-07T10:32:07Z",
              "models": [
                {
                  "id": "voice-kokoro-en",
                  "displayName": "Narration voice",
                  "version": "v0_19",
                  "fileName": "kokoro-int8-en-v0_19.tar.gz",
                  "url": "${base}models-2026.10/kokoro-int8-en-v0_19.tar.gz",
                  "size": 102543452,
                  "sha256": "936044f1f7e3e9822e35ac3212ed555a958983b4c255b9aac7c3b9c9069619c6",
                  "tarSize": 158269440,
                  "tarSha256": "$tarSha",
                  "minVersionCode": 2,
                  "maxVersionCode": null,
                  "compat": { "kind": "sherpa-onnx-kokoro", "layout": "kokoro-v0_19" },
                  "runtime": { "name": "sherpa-onnx", "min": "1.13.8", "max": "1.13.8" }
                }
              ]
            }
        """.trimIndent()
        val voice = valid(text.toByteArray()).entries.single()
        assertTrue(voice.isGzip)
        assertEquals(102_543_452L, voice.sizeBytes)
        assertEquals("936044f1f7e3e9822e35ac3212ed555a958983b4c255b9aac7c3b9c9069619c6", voice.sha256)
        assertEquals(158_269_440L, voice.unpackedSizeBytes)
        assertEquals(tarSha, voice.unpackedSha256)
        assertEquals(2, voice.minVersionCode)
        // The download is the .tar.gz itself.
        assertEquals(ModelFileSpec(voice.url, voice.fileName, 102_543_452L, voice.sha256), voice.toFileSpec())

        // Present but malformed tar fields reject the whole manifest; absent ones are fine (a plain tar).
        assertTrue(invalid(text.replace("\"tarSha256\": \"$tarSha\"", "\"tarSha256\": \"xyz\"").toByteArray()).contains("tarSha256"))
        assertTrue(invalid(text.replace("\"tarSize\": 158269440", "\"tarSize\": -1").toByteArray()).contains("tarSize"))
        assertTrue(invalid(text.replace("\"tarSize\": 158269440", "\"tarSize\": 1.5").toByteArray()).contains("tarSize"))
        val plain = valid(TestManifests.manifest(TestManifests.voiceEntry(base, "t", "v.tar", 30_000_000, tarSha))).entries.single()
        assertNull(plain.tarSha256)
        assertEquals(tarSha, plain.unpackedSha256)
        assertEquals(30_000_000L, plain.unpackedSizeBytes)
    }

    @Test
    fun unknownIdsAndUnknownFieldsAreIgnored() {
        val m = valid(
            TestManifests.manifest(
                """{ "id": "some-future-model", "anything": [1, 2, 3] }""",
                TestManifests.voiceEntry(base, "t", "v.tar", 30_000_000, tarSha).replace("\"version\":", "\"newField\": {\"x\": 1}, \"version\":"),
            ),
        )
        assertEquals(listOf("some-future-model"), m.ignoredIds)
        assertEquals(listOf(ModelKind.VOICE), m.entries.map { it.kind })
    }

    @Test
    fun aMissingRequiredFieldRejectsTheWholeManifest() {
        for (field in listOf("displayName", "version", "fileName", "url", "size", "sha256", "minVersionCode", "compat")) {
            val entry = TestManifests.netEntry(base, "t", "nn-1a298aa575a0.nnue", 98_511_183, sha)
                .lines().filterNot { it.trim().startsWith("\"$field\"") }.joinToString("\n")
                .replace(",\n}", "\n}")
            val bytes = TestManifests.manifest(
                TestManifests.voiceEntry(base, "t", "v.tar", 30_000_000, tarSha),
                entry,
            )
            assertTrue("$field: ${invalid(bytes)}", invalid(bytes).isNotEmpty())
        }
    }

    @Test
    fun malformedValuesRejectTheManifest() {
        val net = TestManifests.netEntry(base, "t", "nn-1a298aa575a0.nnue", 98_511_183, sha)
        val cases = mapOf(
            "sha256 not hex" to net.replace(sha, "z".repeat(64)),
            "sha256 short" to net.replace(sha, sha.take(40)),
            "size fractional" to net.replace("98511183", "98511183.5"),
            "size negative" to net.replace("98511183", "-1"),
            "size a string" to net.replace("98511183", "\"98511183\""),
            "archHash not hex" to net.replace("a85b2205", "xyz"),
            "version too long" to net.replace("0x6a448afa", "0x6a448afa00"),
            "compat without kind" to net.replace("\"kind\": \"stockfish-nnue\", ", ""),
        )
        for ((what, entry) in cases) assertTrue(what, invalid(TestManifests.manifest(entry)).isNotEmpty())
        val voiceNoRuntime = TestManifests.voiceEntry(base, "t", "v.tar", 30_000_000, tarSha)
            .lines().filterNot { it.contains("\"runtime\"") }.joinToString("\n").replace("},\n}", "}\n}")
        assertTrue(invalid(TestManifests.manifest(voiceNoRuntime)).contains("runtime"))
    }

    @Test
    fun anotherSchemaVersionOrNotJsonIsRejected() {
        assertTrue(invalid(both().toString(Charsets.UTF_8).replace("\"schemaVersion\": 1", "\"schemaVersion\": 2").toByteArray()).contains("schemaVersion"))
        assertTrue(invalid("not json".toByteArray()).isNotEmpty())
        assertTrue(invalid("{}".toByteArray()).isNotEmpty())
        assertTrue(invalid("""{"schemaVersion": 1, "models": {}}""".toByteArray()).contains("models"))
    }

    @Test
    fun hexAcceptsAnOptionalPrefixAndShaIsLowerCased() {
        assertEquals(0x6a448afaL, ModelManifest.parseHex32("0x6a448afa"))
        assertEquals(0xa85b2205L, ModelManifest.parseHex32("A85B2205"))
        val m = valid(TestManifests.manifest(TestManifests.netEntry(base, "t", "nn-1a298aa575a0.nnue", 98_511_183, sha.uppercase().let { "1a298aa575a0" + it.drop(12) })))
        assertEquals(sha, m.entries.single().sha256)
    }

    @Test
    fun anUnknownCompatKindParsesAsOther() {
        val m = valid(TestManifests.manifest(TestManifests.netEntry(base, "t", "nn-1a298aa575a0.nnue", 98_511_183, sha).replace("\"stockfish-nnue\", \"version\": \"0x6a448afa\", \"archHash\": \"a85b2205\", \"engineTag\": \"sf_19\"", "\"lc0\"")))
        assertEquals(ModelCompat.Other("lc0"), m.entries.single().compat)
    }
}
