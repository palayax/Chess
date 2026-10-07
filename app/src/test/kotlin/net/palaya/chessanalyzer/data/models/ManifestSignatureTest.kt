package net.palaya.chessanalyzer.data.models

import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The manifest signature (D2e; design §3.4): ECDSA P-256 / SHA256withECDSA over the exact bytes. Keys are
 * TEST keys generated here ([TestManifests]) or a throwaway openssl key whose public half and signature
 * are the fixture in `src/test/resources/manifest/` (its private half was deleted after signing). The
 * maintainers' real private key is never used by a test.
 */
class ManifestSignatureTest {

    private val keys = TestManifests.newKeyPair()
    private val pub = keys.public.encoded
    private val manifest = TestManifests.manifest(
        TestManifests.voiceEntry("https://x/", "t", "v.tar", 30_000_000, "a".repeat(64)),
    )
    private val sig = TestManifests.sign(manifest, keys.private)

    private fun resource(name: String): ByteArray =
        requireNotNull(javaClass.classLoader!!.getResourceAsStream("manifest/$name")) { name }.use { it.readBytes() }

    @Test
    fun aValidSignatureVerifies() {
        assertTrue(ManifestSignature.verify(manifest, sig, pub))
    }

    @Test
    fun anyChangedByteFailsIncludingWhitespace() {
        for (i in listOf(0, manifest.size / 2, manifest.size - 1)) {
            val tampered = manifest.copyOf().also { it[i] = (it[i] + 1).toByte() }
            assertFalse("byte $i", ManifestSignature.verify(tampered, sig, pub))
        }
        assertFalse("an appended newline", ManifestSignature.verify(manifest + '\n'.code.toByte(), sig, pub))
        val flipped = sig.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() }
        assertFalse("a changed signature", ManifestSignature.verify(manifest, flipped, pub))
    }

    @Test
    fun anotherKeysSignatureFails() {
        val other = TestManifests.newKeyPair()
        assertFalse(ManifestSignature.verify(manifest, TestManifests.sign(manifest, other.private), pub))
        assertFalse(ManifestSignature.verify(manifest, sig, other.public.encoded))
    }

    @Test
    fun aMissingEmptyGarbageOrHugeSignatureFailsWithoutThrowing() {
        assertFalse(ManifestSignature.verify(manifest, ByteArray(0), pub))
        assertFalse(ManifestSignature.verify(manifest, "not a signature".toByteArray(), pub))
        assertFalse(ManifestSignature.verify(manifest, ByteArray(70) { 0x30 }, pub))
        assertFalse(ManifestSignature.verify(manifest, ByteArray(ModelManifest.MAX_SIGNATURE_BYTES + 1), pub))
        assertFalse("a broken key", ManifestSignature.verify(manifest, sig, byteArrayOf(1, 2, 3)))
    }

    @Test
    fun aKeyOnAnotherCurveIsRefused() {
        val p384 = TestManifests.newKeyPair("secp384r1")
        val s = TestManifests.sign(manifest, p384.private)
        assertFalse("only P-256 is accepted, even with a matching signature", ManifestSignature.verify(manifest, s, p384.public.encoded))
    }

    @Test
    fun anOpensslSignatureVerifiesInJava() {
        // publish_models.sh signs with `openssl dgst -sha256 -sign`; the app verifies with java.security.
        val m = resource("openssl_signed_models.json")
        val s = resource("openssl_signed_models.json.sig")
        val k = resource("openssl_test_public_key.der")
        assertTrue(ManifestSignature.verify(m, s, k))
        assertFalse(ManifestSignature.verify(m.copyOf().also { it[10] = (it[10] + 1).toByte() }, s, k))
        // And the fixture is a manifest this app reads: unknown fields and ids are ignored.
        val parsed = ModelManifest.parse(m) as ManifestParse.Valid
        assertEquals(listOf("some-future-model"), parsed.manifest.ignoredIds)
        assertEquals("v0_19-r2", parsed.manifest.entries.single().version)
    }

    @Test
    fun theCompiledInKeyIsTheCommittedP256Key() {
        val der = ManifestSignature.appPublicKeyDer
        assertEquals(91, der.size)
        ManifestSignature.publicKeyFrom(der) // throws unless EC P-256
        val sha = MessageDigest.getInstance("SHA-256").digest(der).joinToString("") { "%02x".format(it) }
        assertEquals(GeneratedModelPins.MANIFEST_PUBLIC_KEY_SHA256, sha)
        // The committed file, byte for byte.
        var dir: java.io.File? = java.io.File("").absoluteFile
        while (dir != null && !java.io.File(dir, "settings.gradle.kts").exists()) dir = dir.parentFile
        val committed = java.io.File(dir!!, "vendor/models/manifest_public_key.der").readBytes()
        assertTrue(committed.contentEquals(der))
        // A test key's signature does not verify against it.
        assertFalse(ManifestSignature.verify(manifest, sig))
    }
}
