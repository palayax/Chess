package net.palaya.chessanalyzer.data.models

import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.PublicKey
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.X509EncodedKeySpec

/**
 * The signature on `models.json` (docs/MODEL_DOWNLOAD_DESIGN.md §3.4): `models.json.sig` is a DER ECDSA
 * signature over the exact bytes of `models.json`, made with the maintainers' P-256 key
 * (`keystore/models-signing.pem`, never in the repo) by `scripts/publish_models.sh` (`openssl dgst
 * -sha256 -sign`). The app verifies it with the public half compiled in
 * (`GeneratedModelPins.MANIFEST_PUBLIC_KEY_DER_BASE64`, from `vendor/models/manifest_public_key.der`).
 *
 * `SHA256withECDSA` and `KeyFactory("EC")` exist on API 26; no BouncyCastle, no Ed25519. Only a P-256
 * key is accepted (a weaker curve compiled in by mistake fails closed). [verify] never throws: anything
 * that is not a valid signature by that key is false, and then nothing in the manifest is used.
 */
object ManifestSignature {

    const val ALGORITHM = "SHA256withECDSA"

    /** The compiled-in public key ([GeneratedModelPins]), decoded once. */
    val appPublicKeyDer: ByteArray by lazy {
        java.util.Base64.getDecoder().decode(GeneratedModelPins.MANIFEST_PUBLIC_KEY_DER_BASE64)
    }

    /** Decodes an X.509 SubjectPublicKeyInfo (DER) and insists on an EC key over a 256-bit curve. */
    fun publicKeyFrom(der: ByteArray): PublicKey {
        val key = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(der))
        val ec = key as? ECPublicKey ?: throw GeneralSecurityException("not an EC key")
        if (ec.params.curve.field.fieldSize != 256) throw GeneralSecurityException("not a P-256 key")
        return key
    }

    /** True only when [signature] is a valid [ALGORITHM] signature over exactly [data] by [publicKeyDer]. */
    fun verify(data: ByteArray, signature: ByteArray, publicKeyDer: ByteArray = appPublicKeyDer): Boolean {
        if (signature.isEmpty() || signature.size > ModelManifest.MAX_SIGNATURE_BYTES) return false
        return try {
            val s = Signature.getInstance(ALGORITHM)
            s.initVerify(publicKeyFrom(publicKeyDer))
            s.update(data)
            s.verify(signature)
        } catch (e: GeneralSecurityException) {
            false
        } catch (e: IllegalArgumentException) {
            false
        } catch (e: RuntimeException) {
            // A malformed DER signature can surface as an unchecked exception on some providers.
            false
        }
    }
}
