package com.agenthub.android

import kotlinx.coroutines.channels.Channel
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

/** Independent encrypted peer for native protocol boundaries; never a model or network Host. */
internal class NativeEasyWorkProtocolPeer(private val credential: EasyWorkCredential,private val confirmation: String,
    private val reply: suspend (JSONObject)->JSONObject): AgentFrameTransport {
    private val incoming=Channel<ByteArray>(Channel.UNLIMITED)
    private val session=UUID.randomUUID().toString();private var sequence=1L
    private val mobileToDesktop: ByteArray;private val desktopToMobile: ByteArray
    init {
        val secret=ByteArray(32)
        X25519PrivateKeyParameters(easyBytes(credential.privateKey),0).generateSecret(X25519PublicKeyParameters(easyBytes(credential.binding.desktopPublicKey),0),secret,0)
        fun key(info: String): ByteArray {
            fun mac(key: ByteArray,data: ByteArray)=Mac.getInstance("HmacSHA256").run { init(SecretKeySpec(key,"HmacSHA256"));doFinal(data) }
            val prk=mac("easyai-remote:${credential.binding.id}".toByteArray(),secret)
            return mac(prk,info.toByteArray()+byteArrayOf(1))
        }
        mobileToDesktop=key("v1:mobile-to-desktop");desktopToMobile=key("v1:desktop-to-mobile");secret.fill(0)
    }
    private fun aad(value: JSONObject)=easyCanonical(JSONArray().put(1).put(value.getString("bindingId")).put(value.getString("senderDeviceId"))
        .put(value.getString("senderSessionId")).put(value.getString("recipientDeviceId")).put(value.getLong("sequence")).put(value.getString("sentAt"))).toByteArray()
    private fun encrypt(payload: JSONObject): ByteArray {
        val binding=credential.binding;val value=JSONObject().put("version",1).put("bindingId",binding.id).put("senderDeviceId",binding.desktopId)
            .put("senderSessionId",session).put("recipientDeviceId",binding.mobileId).put("sequence",sequence++).put("sentAt",Instant.now().toString())
        val nonce=ByteArray(12).also { SecureRandom().nextBytes(it) };val cipher=Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE,SecretKeySpec(desktopToMobile,"AES"),GCMParameterSpec(128,nonce));cipher.updateAAD(aad(value))
        value.put("nonce",easyBase64(nonce)).put("ciphertext",easyBase64(cipher.doFinal(easyCanonical(payload).toByteArray())))
        return value.toString().toByteArray()
    }
    override suspend fun send(bytes: ByteArray) {
        val value=JSONObject(String(bytes));val cipher=Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE,SecretKeySpec(mobileToDesktop,"AES"),GCMParameterSpec(128,easyBytes(value.getString("nonce"))));cipher.updateAAD(aad(value))
        val payload=JSONObject(String(cipher.doFinal(easyBytes(value.getString("ciphertext")))))
        if(payload.getString("kind")=="key-confirmation") {
            require(payload.getString("value")==confirmation)
            incoming.send(encrypt(JSONObject().put("kind","key-confirmation").put("value",confirmation)))
        } else {
            val request=payload.getJSONObject("envelope");require(EasyWorkTarget.decode(request.getJSONObject("target"))==credential.binding.target)
            val response=JSONObject().put("protocolVersion",2).put("kind","response").put("requestId",request.getString("requestId"))
                .put("target",credential.binding.target.json()).put("ok",true).put("result",reply(request))
            incoming.send(encrypt(JSONObject().put("kind","protocol").put("envelope",response)))
        }
    }
    override suspend fun receive()=incoming.receive()
    override fun close() { incoming.close() }
}
