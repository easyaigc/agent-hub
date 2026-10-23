package com.agenthub.android

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom
import java.time.Instant
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

data class EasyWorkIdentity(val deviceId: String,val privateKey: String,val publicKey: String) {
    fun json()=JSONObject().put("deviceId",deviceId).put("privateKey",privateKey).put("publicKey",publicKey)
    companion object {
        suspend fun load(storage: NativeSessionStorage): EasyWorkIdentity = withContext(Dispatchers.IO) {
            storage.snapshotTransaction {
            storage.read("easywork-device-identity")?.let { raw ->
                val v=JSONObject(raw)
                return@snapshotTransaction EasyWorkIdentity(v.getString("deviceId"),v.getString("privateKey"),v.getString("publicKey")).also {
                    require(X25519PrivateKeyParameters(easyBytes(it.privateKey),0).generatePublicKey().encoded.contentEquals(easyBytes(it.publicKey))) { "设备身份密钥不匹配" }
                }
            }
            val key=X25519PrivateKeyParameters(SecureRandom())
            val identity=EasyWorkIdentity("mobile-"+UUID.randomUUID(),easyBase64(key.encoded),easyBase64(key.generatePublicKey().encoded))
            storage.write("easywork-device-identity",identity.json().toString())
            identity
            }
        }
    }
}

/** Byte-compatible with iOS and the existing Host: X25519 / HKDF-SHA256 / AES-GCM. */
class EasyWorkCrypto(credential: EasyWorkCredential,val binding: EasyWorkBinding) {
    val sessionId=UUID.randomUUID().toString()
    private val outbound: ByteArray
    private val inbound: ByteArray
    val confirmation: String
    init {
        require(binding.json.getString("status")=="active") { "设备配对已失效" }
        val privateBytes=easyBytes(credential.privateKey)
        require(privateBytes.size==32) { "设备身份格式无效" }
        val privateKey=X25519PrivateKeyParameters(privateBytes,0)
        require(privateKey.generatePublicKey().encoded.contentEquals(easyBytes(binding.mobilePublicKey))) { "配对公钥与本机身份不匹配" }
        val desktopBytes=easyBytes(binding.desktopPublicKey)
        require(desktopBytes.size==32) { "电脑公钥无效" }
        val secret=ByteArray(32)
        privateKey.generateSecret(X25519PublicKeyParameters(desktopBytes,0),secret,0)
        require(secret.any { it.toInt()!=0 }) { "电脑密钥交换无效" }
        val salt="easyai-remote:${binding.id}".toByteArray()
        outbound=hkdf(secret,salt,"v1:mobile-to-desktop",32)
        inbound=hkdf(secret,salt,"v1:desktop-to-mobile",32)
        confirmation=easyBase64(hkdf(secret,binding.id.toByteArray(),"easyai-remote:key-confirmation:v1",16))
        secret.fill(0)
    }
    private fun header(v: JSONObject): ByteArray {
        val a=JSONArray().put(1).put(v.getString("bindingId")).put(v.getString("senderDeviceId"))
        v.optional("senderSessionId")?.let { a.put(it) }
        a.put(v.getString("recipientDeviceId")).put(v.getLong("sequence")).put(v.getString("sentAt"))
        return easyCanonical(a).toByteArray(Charsets.UTF_8)
    }
    fun seal(payload: JSONObject,sequence: Long): ByteArray {
        require(sequence in 1..9007199254740991L)
        val v=JSONObject().put("version",1).put("bindingId",binding.id).put("senderDeviceId",binding.mobileId)
            .put("senderSessionId",sessionId).put("recipientDeviceId",binding.desktopId).put("sequence",sequence).put("sentAt",Instant.now().toString())
        val nonce=ByteArray(12).also { SecureRandom().nextBytes(it) }
        val cipher=Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE,SecretKeySpec(outbound,"AES"),GCMParameterSpec(128,nonce))
        cipher.updateAAD(header(v))
        val encrypted=cipher.doFinal(easyCanonical(payload).toByteArray(Charsets.UTF_8))
        v.put("nonce",easyBase64(nonce)).put("ciphertext",easyBase64(encrypted))
        return easyCanonical(v).toByteArray(Charsets.UTF_8)
    }
    data class Opened(val payload: JSONObject,val peerSession: String,val sequence: Long)
    fun open(bytes: ByteArray): Opened {
        require(bytes.size<=32*1024*1024) { "电脑消息超过接收上限" }
        val v=JSONObject(String(bytes,Charsets.UTF_8))
        val sequence=v.getLong("sequence")
        require(v.getInt("version")==1&&v.getString("bindingId")==binding.id&&v.getString("senderDeviceId")==binding.desktopId&&v.getString("recipientDeviceId")==binding.mobileId&&sequence in 1..9007199254740991L&&v.getString("sentAt").isNotBlank()) { "加密消息设备或序号不匹配" }
        val nonce=easyBytes(v.getString("nonce"));val ciphertext=easyBytes(v.getString("ciphertext"))
        require(nonce.size==12&&ciphertext.size>=16) { "加密消息格式无效" }
        val cipher=Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE,SecretKeySpec(inbound,"AES"),GCMParameterSpec(128,nonce));cipher.updateAAD(header(v))
        val payload=JSONObject(String(cipher.doFinal(ciphertext),Charsets.UTF_8))
        return Opened(payload,binding.id+":"+(v.optional("senderSessionId")?:"legacy"),sequence)
    }
    companion object {
        private fun hkdf(secret: ByteArray,salt: ByteArray,info: String,size: Int): ByteArray {
            fun hmac(key: ByteArray,bytes: ByteArray)=Mac.getInstance("HmacSHA256").run { init(SecretKeySpec(key,"HmacSHA256"));doFinal(bytes) }
            val prk=hmac(salt,secret);val result=hmac(prk,info.toByteArray()+byteArrayOf(1)).copyOf(size);prk.fill(0);return result
        }
    }
}
