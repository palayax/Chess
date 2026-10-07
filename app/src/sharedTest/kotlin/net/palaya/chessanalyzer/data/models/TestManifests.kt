package net.palaya.chessanalyzer.data.models

import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec

/**
 * Signed test manifests for the update tests (D2e), host and device alike. The keys are TEST keys made
 * here at run time (`KeyPairGenerator("EC")` on secp256r1, i.e. P-256): the maintainers' real private key
 * (`keystore/models-signing.pem`) is never used by a test, and no private key is committed anywhere.
 */
object TestManifests {

    fun newKeyPair(curve: String = "secp256r1"): KeyPair =
        KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec(curve)) }.generateKeyPair()

    /** A DER `SHA256withECDSA` signature, exactly what `openssl dgst -sha256 -sign` writes. */
    fun sign(data: ByteArray, key: PrivateKey): ByteArray =
        Signature.getInstance(ManifestSignature.ALGORITHM).run {
            initSign(key)
            update(data)
            sign()
        }

    /** One `models` element in the as-built schema (see [ModelManifest]). */
    fun netEntry(
        baseUrl: String,
        tag: String,
        fileName: String,
        size: Long,
        sha256: String,
        version: String = "0x6a448afa",
        archHash: String = "a85b2205",
        minVersionCode: Int = 1,
        maxVersionCode: Int? = null,
    ): String = """
        {
          "id": "engine-net",
          "displayName": "Chess engine data",
          "version": "${fileName.removeSuffix(".nnue")}",
          "fileName": "$fileName",
          "url": "$baseUrl$tag/$fileName",
          "size": $size,
          "sha256": "$sha256",
          "minVersionCode": $minVersionCode,
          "maxVersionCode": ${maxVersionCode ?: "null"},
          "compat": { "kind": "stockfish-nnue", "version": "$version", "archHash": "$archHash", "engineTag": "sf_19" }
        }
    """.trimIndent()

    fun voiceEntry(
        baseUrl: String,
        tag: String,
        fileName: String,
        size: Long,
        sha256: String,
        version: String = "v0_19-r2",
        layout: String = "kokoro-v0_19",
        runtimeMin: String = "1.13.8",
        runtimeMax: String = "1.13.8",
        minVersionCode: Int = 1,
        /** For a `.tar.gz` entry (D2f): the tar inside, as `publish_models.sh` writes it. */
        tarSha256: String? = null,
        tarSize: Long? = null,
    ): String = """
        {
          "id": "voice-kokoro-en",${tarFields(tarSha256, tarSize)}
          "displayName": "Narration voice",
          "version": "$version",
          "fileName": "$fileName",
          "url": "$baseUrl$tag/$fileName",
          "size": $size,
          "sha256": "$sha256",
          "minVersionCode": $minVersionCode,
          "maxVersionCode": null,
          "compat": { "kind": "sherpa-onnx-kokoro", "layout": "$layout" },
          "runtime": { "name": "sherpa-onnx", "min": "$runtimeMin", "max": "$runtimeMax" }
        }
    """.trimIndent()

    /** The optional `tarSha256` / `tarSize` lines of a voice entry, indented like the template's. */
    private fun tarFields(tarSha256: String?, tarSize: Long?): String = buildString {
        if (tarSha256 != null) append("\n          \"tarSha256\": \"").append(tarSha256).append("\",")
        if (tarSize != null) append("\n          \"tarSize\": ").append(tarSize).append(',')
    }

    fun manifest(vararg entries: String, schemaVersion: Int = 1): ByteArray = """
        {
          "schemaVersion": $schemaVersion,
          "generatedAt": "2026-10-07T12:00:00Z",
          "models": [
        ${entries.joinToString(",\n")}
          ]
        }
    """.trimIndent().toByteArray(Charsets.UTF_8)
}
