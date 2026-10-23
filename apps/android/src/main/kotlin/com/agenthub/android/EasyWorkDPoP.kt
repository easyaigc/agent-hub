package com.agenthub.android

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.bouncycastle.asn1.ASN1Integer
import org.bouncycastle.asn1.ASN1Sequence
import org.json.JSONObject
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.time.Instant
import java.util.UUID

/** RFC 9449 proof key. This key is independent of the X25519 content encryption key. */
class EasyWorkDPoP private constructor(private val key: PrivateKey, private val publicKey: ECPublicKey) {
    val jwk: JSONObject get() = JSONObject().put("kty", "EC").put("crv", "P-256")
        .put("x", easyBase64(unsigned32(publicKey.w.affineX)))
        .put("y", easyBase64(unsigned32(publicKey.w.affineY)))
    val thumbprint: String get() = easyBase64(MessageDigest.getInstance("SHA-256").digest(easyCanonical(jwk).toByteArray(Charsets.UTF_8)))

    /** Generate a fresh proof for each request, including retries of one immutable operation. */
    fun proof(method: String, url: String, accessToken: String? = null, nonce: String? = null, body: ByteArray? = null): String {
        require(method in setOf("GET", "POST", "DELETE"))
        val target = url.toHttpUrl()
        require(target.username.isEmpty() && target.password.isEmpty())
        require(target.isHttps || target.host in setOf("127.0.0.1", "localhost", "::1")) { "设备授权需要 HTTPS" }
        val htu = target.newBuilder().query(null).fragment(null).build().toString()
        val header = JSONObject().put("typ", "dpop+jwt").put("alg", "ES256").put("jwk", jwk)
        val payload = JSONObject().put("jti", UUID.randomUUID().toString()).put("htm", method)
            .put("htu", htu).put("iat", Instant.now().epochSecond)
        accessToken?.let {
            require(it.isNotEmpty() && it.all { c -> c.code in 33..126 })
            payload.put("ath", easyBase64(MessageDigest.getInstance("SHA-256").digest(it.toByteArray(Charsets.US_ASCII))))
        }
        nonce?.let { require(it.length in 1..512 && it.all { c -> c.code in 33..126 }); payload.put("nonce", it) }
        body?.takeIf { it.isNotEmpty() }?.let { payload.put("bht", easyBase64(MessageDigest.getInstance("SHA-256").digest(it))) }
        val signingInput = easyBase64(easyCanonical(header).toByteArray(Charsets.UTF_8)) + "." + easyBase64(easyCanonical(payload).toByteArray(Charsets.UTF_8))
        val der = Signature.getInstance("SHA256withECDSA").run { initSign(key); update(signingInput.toByteArray(Charsets.US_ASCII)); sign() }
        val values = ASN1Sequence.getInstance(der)
        require(values.size() == 2)
        val raw = unsigned32(ASN1Integer.getInstance(values.getObjectAt(0)).value) + unsigned32(ASN1Integer.getInstance(values.getObjectAt(1)).value)
        return signingInput + "." + easyBase64(raw)
    }

    companion object {
        private val keyCreation = Any()
        private fun unsigned32(value: BigInteger): ByteArray {
            require(value.signum() >= 0 && value.bitLength() <= 256)
            val bytes = value.toByteArray().let { if(it.size == 33 && it[0].toInt() == 0) it.copyOfRange(1, 33) else it }
            return ByteArray(32 - bytes.size) + bytes
        }
        fun load(identity: EasyWorkIdentity): EasyWorkDPoP = synchronized(keyCreation) {
            val alias = "agent-hub-easywork-dpop-" + easyHash(identity.deviceId.toByteArray(Charsets.UTF_8))
            val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            if(!store.containsAlias(alias)) {
                KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore").apply {
                    initialize(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                        .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                        .setDigests(KeyProperties.DIGEST_SHA256).build())
                }.generateKeyPair()
            }
            val entry = store.getEntry(alias, null) as KeyStore.PrivateKeyEntry
            require(entry.privateKey.encoded == null) { "设备签名密钥必须由系统安全存储保管" }
            val publicKey = entry.certificate.publicKey as ECPublicKey
            require(publicKey.params.curve.field.fieldSize == 256)
            EasyWorkDPoP(entry.privateKey, publicKey)
        }
    }
}
