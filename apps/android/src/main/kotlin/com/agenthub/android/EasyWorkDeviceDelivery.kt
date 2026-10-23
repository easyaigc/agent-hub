package com.agenthub.android

import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import org.json.JSONObject
import java.nio.ByteBuffer
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** EWDAP/1 sensitive delivery: RFC 7518 ECDH-ES/A256GCM compact JWE. */
internal object EasyWorkDeviceDelivery {
    fun open(identity: EasyWorkIdentity, delivery: String, expectedDeliveryId: String): JSONObject {
        require(delivery.length in 1..65_536)
        val parts=delivery.split('.')
        require(parts.size==5&&parts[1].isEmpty()) { "设备授权密文格式无效" }
        val header=JSONObject(String(easyBytes(parts[0]),Charsets.UTF_8))
        require(header.getString("alg")=="ECDH-ES"&&header.getString("enc")=="A256GCM"&&header.getString("typ")=="ewdap+jwe"&&header.getInt("version")==1&&header.getString("deliveryId")==expectedDeliveryId)
        require(listOf("apu","apv","crit","zip").none(header::has))
        val ephemeral=header.getJSONObject("epk")
        require(ephemeral.getString("kty")=="OKP"&&ephemeral.getString("crv")=="X25519"&&!ephemeral.has("d"))
        val privateBytes=easyBytes(identity.privateKey);val publicBytes=easyBytes(ephemeral.getString("x"))
        require(privateBytes.size==32&&publicBytes.size==32)
        val nonce=easyBytes(parts[2]);val ciphertext=easyBytes(parts[3]);val tag=easyBytes(parts[4])
        require(nonce.size==12&&tag.size==16&&ciphertext.size<=32_768)
        val secret=ByteArray(32)
        var key: ByteArray?=null
        var clear: ByteArray?=null
        try {
            val privateKey=X25519PrivateKeyParameters(privateBytes,0)
            require(privateKey.generatePublicKey().encoded.contentEquals(easyBytes(identity.publicKey)))
            privateKey.generateSecret(X25519PublicKeyParameters(publicBytes,0),secret,0)
            require(secret.any { it.toInt()!=0 })
            fun uint32(value: Int)=ByteBuffer.allocate(4).putInt(value).array()
            val algorithm="A256GCM".toByteArray(Charsets.US_ASCII)
            key=MessageDigest.getInstance("SHA-256").digest(uint32(1)+secret+uint32(algorithm.size)+algorithm+uint32(0)+uint32(0)+uint32(256))
            val cipher=Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE,SecretKeySpec(key,"AES"),GCMParameterSpec(128,nonce))
            cipher.updateAAD(parts[0].toByteArray(Charsets.US_ASCII))
            clear=cipher.doFinal(ciphertext+tag)
            val value=JSONObject(String(clear,Charsets.UTF_8))
            require(value.getString("protocol")=="EWDAP/1"&&value.getString("deliveryId")==expectedDeliveryId)
            return value
        } finally { privateBytes.fill(0);secret.fill(0);key?.fill(0);clear?.fill(0) }
    }
}
