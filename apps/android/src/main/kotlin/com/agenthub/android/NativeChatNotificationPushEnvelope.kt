package com.agenthub.android

import java.util.Base64
import com.agenthub.core.TaskNotificationRules
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.params.HKDFParameters
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import org.json.JSONObject
import java.time.Instant
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

internal data class NativeChatPushEnvelope(val bindingId:String,val eventId:String,val nonce:ByteArray,
    val ciphertext:ByteArray,val tag:ByteArray,val expiresAt:Instant,val badgeOnly:Boolean)

/** Provider-independent decoding of the existing Host's v1 encrypted task notice.
 * This layer cannot create an identity, perform network reads, post a notice or acknowledge a chat.
 */
internal object NativeChatPushEnvelopeCodec {
    private fun bytes(value:String,maximum:Int):ByteArray {
        require(value.length<=maximum&&value.matches(Regex("[A-Za-z0-9+/]+={0,2}")))
        return Base64.getDecoder().decode(value).also { require(Base64.getEncoder().encodeToString(it)==value) }
    }
    fun decode(value:JSONObject,now:Instant=Instant.now()):NativeChatPushEnvelope {
        val required=setOf("version","bindingId","eventId","nonce","ciphertext","tag","expiresAt","sound","timeSensitive")
        val fields=value.keys().asSequence().toSet();require(fields.containsAll(required)&&fields.all { it in required+setOf("badge","badgeOnly") })
        require(value.opt("version")==1)
        fun text(name:String)= (value.opt(name) as? String)?:error("推送资料字段无效")
        val binding=text("bindingId");val id=text("eventId")
        require(TaskNotificationRules.identifier(binding)&&TaskNotificationRules.eventId(id))
        val expires=Instant.parse(text("expiresAt"));require(expires>now&&expires<=now.plusSeconds(660))
        val nonce=bytes(text("nonce"),16);val ciphertext=bytes(text("ciphertext"),2800);val tag=bytes(text("tag"),24)
        require(nonce.size==12&&tag.size==16&&ciphertext.isNotEmpty())
        require(TaskNotificationRules.sound(text("sound"))&&value.opt("timeSensitive") is Boolean)
        if(value.has("badge")) {
            val badge=value.opt("badge");require(badge is Number)
            require(badge.toString().toLongOrNull()?.let { it in 0..9_007_199_254_740_991L }==true)
        }
        require(!value.has("badgeOnly")||value.opt("badgeOnly") is Boolean)
        val silent=value.optBoolean("badgeOnly")
        if(silent)require(!value.has("badge")&&text("sound")=="none"&&value.getBoolean("timeSensitive")==false)
        return NativeChatPushEnvelope(binding,id,nonce,ciphertext,tag,expires,silent)
    }
    fun open(envelope:NativeChatPushEnvelope,privateKey:ByteArray,peerPublicKey:String,expected:NativeChatNoticeTarget):NativeChatNotificationEvent {
        return NativeChatNotificationCodec.event(openPayload(envelope, privateKey, peerPublicKey)).also {
            require(it.id == envelope.eventId && it.target == expected)
        }
    }
    fun openPayload(envelope: NativeChatPushEnvelope, privateKey: ByteArray, peerPublicKey: String): JSONObject {
        require(privateKey.size==32)
        val peer=bytes(peerPublicKey,44);require(peer.size==32)
        val shared=ByteArray(32)
        X25519PrivateKeyParameters(privateKey,0).generateSecret(X25519PublicKeyParameters(peer,0),shared,0)
        require(shared.any { it!=0.toByte() })
        val key=ByteArray(32)
        try {
            HKDFBytesGenerator(SHA256Digest()).apply {
                init(HKDFParameters(shared,envelope.bindingId.toByteArray(Charsets.UTF_8),"codex-manage/notifications/v1".toByteArray(Charsets.UTF_8)))
                generateBytes(key,0,key.size)
            }
            val cipher=Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE,SecretKeySpec(key,"AES"),GCMParameterSpec(128,envelope.nonce))
            cipher.updateAAD(envelope.eventId.toByteArray(Charsets.UTF_8))
            val plaintext=cipher.doFinal(envelope.ciphertext+envelope.tag)
            try {
                val text=Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(plaintext)).toString()
                return JSONObject(text)
            } finally { plaintext.fill(0) }
        } finally { key.fill(0);shared.fill(0) }
    }
}
