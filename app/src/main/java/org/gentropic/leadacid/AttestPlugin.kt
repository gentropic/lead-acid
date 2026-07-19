package org.gentropic.leadacid

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec

/**
 * Hardware-backed signing (SPEC §5.1 — the "starred sleeper"). A key generated
 * in the Android Keystore (StrongBox/TEE where available) signs a capture, so
 * field data carries a signature from the moment it's recorded — provenance by
 * construction. The private key never leaves secure hardware.
 *
 *   GET  /native/attest/keyinfo         — {alg, pub (SPKI base64), security}
 *   POST /native/attest/sign            — bytes (body sidecar) → {sig, pub, hash, alg, security}
 *
 * Signature is raw P-256 (r‖s, 64 bytes) so it verifies with WebCrypto
 * `ECDSA/P-256/SHA-256` directly — no DER handling on the JS side. Context-free
 * (AndroidKeyStore is a static provider); only the StrongBox hint is passed in.
 */
class AttestPlugin(private val strongBoxAvailable: Boolean) : Plugin {
    override val name = "attest"

    private val alias = "leadacid-attest"
    private val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    override fun handle(req: BridgeRequest): BridgeResponse = when (req.subpath) {
        "keyinfo" -> keyinfo()
        "sign" -> sign(req)
        else -> BridgeResponse.error(404, "Not Found", "attest/${req.subpath}")
    }

    private fun entry(): KeyStore.PrivateKeyEntry {
        if (!ks.containsAlias(alias)) generate()
        return ks.getEntry(alias, null) as KeyStore.PrivateKeyEntry
    }

    private fun generate() {
        fun spec(strongBox: Boolean) = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_SHA256)
            .apply { if (strongBox && Build.VERSION.SDK_INT >= 28) setIsStrongBoxBacked(true) }
            .build()
        val gen = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore")
        try {
            gen.initialize(spec(strongBoxAvailable)); gen.generateKeyPair()
        } catch (e: Exception) {
            if (!strongBoxAvailable) throw e
            gen.initialize(spec(false)); gen.generateKeyPair()   // StrongBox refused → TEE
        }
    }

    /** trusted_environment | strongbox | software | unknown — the real security level. */
    private fun securityOf(priv: PrivateKey): String = try {
        val info = KeyFactory.getInstance(priv.algorithm, "AndroidKeyStore")
            .getKeySpec(priv, KeyInfo::class.java)
        if (Build.VERSION.SDK_INT >= 31) when (info.securityLevel) {
            KeyProperties.SECURITY_LEVEL_STRONGBOX -> "strongbox"
            KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT -> "trusted_environment"
            KeyProperties.SECURITY_LEVEL_SOFTWARE -> "software"
            else -> "unknown"
        } else @Suppress("DEPRECATION") if (info.isInsideSecureHardware) "trusted_environment" else "software"
    } catch (e: Exception) { "unknown" }

    private fun b64(b: ByteArray) = Base64.encodeToString(b, Base64.NO_WRAP)

    private fun keyinfo(): BridgeResponse {
        val e = entry()
        val pub = e.certificate.publicKey.encoded          // X.509 SPKI
        return BridgeResponse.json(
            200, "OK",
            """{"alg":"ECDSA-P256-SHA256","pub":${BridgeResponse.jsonStr(b64(pub))},"security":${BridgeResponse.jsonStr(securityOf(e.privateKey))}}""",
        )
    }

    private fun sign(req: BridgeRequest): BridgeResponse {
        if (req.method != "POST") return BridgeResponse.error(405, "Method Not Allowed", req.method)
        val body = req.body ?: return BridgeResponse.error(400, "Bad Request", "no body to sign")
        val e = entry()
        val s = Signature.getInstance("SHA256withECDSA").apply { initSign(e.privateKey); update(body) }
        val raw = derToRawEcdsa(s.sign())                  // → WebCrypto-friendly r‖s
        val hash = MessageDigest.getInstance("SHA-256").digest(body)
        val pub = e.certificate.publicKey.encoded
        return BridgeResponse.json(
            200, "OK",
            """{"alg":"ECDSA-P256-SHA256","sig":${BridgeResponse.jsonStr(b64(raw))},"pub":${BridgeResponse.jsonStr(b64(pub))},"hash":${BridgeResponse.jsonStr(b64(hash))},"security":${BridgeResponse.jsonStr(securityOf(e.privateKey))}}""",
        )
    }

    /** DER ECDSA (SEQUENCE{INTEGER r, INTEGER s}) → raw r‖s, 32 bytes each (P-256). */
    private fun derToRawEcdsa(der: ByteArray): ByteArray {
        var p = 0
        fun len(): Int {
            var l = der[p++].toInt() and 0xFF
            if (l >= 0x80) { var n = l and 0x7f; l = 0; while (n-- > 0) l = (l shl 8) or (der[p++].toInt() and 0xFF) }
            return l
        }
        require(der[p++].toInt() and 0xFF == 0x30); len()
        require(der[p++].toInt() and 0xFF == 0x02); val rl = len(); val r = der.copyOfRange(p, p + rl); p += rl
        require(der[p++].toInt() and 0xFF == 0x02); val sl = len(); val s = der.copyOfRange(p, p + sl); p += sl
        val out = ByteArray(64)
        fun place(v: ByteArray, off: Int) {
            var start = 0
            while (start < v.size - 1 && v[start].toInt() == 0) start++   // strip ASN.1 sign byte
            val n = v.size - start
            System.arraycopy(v, start, out, off + 32 - n, n)              // right-align into 32
        }
        place(r, 0); place(s, 32)
        return out
    }
}
