package com.agenthub.android

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.IOException
import java.security.SecureRandom
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Runs the current Android production session/crypto on a host JVM with synthetic peers.
 * No Android secure-storage, real TLS, Electron or model proof is inferred. */
class P8EasyWorkBoundaryTest {
    private val vector get()=JSONObject(File(System.getProperty("p8.root"),"apps/android/src/androidTest/assets/easywork-crypto-vector.json").readText())
    private val credential get()=EasyWorkCredential.decode(vector.getJSONObject("credential"))
    private val target get()=credential.binding.target
    private val lan=AgentCandidate("p8-lan","lan-wss","https://127.0.0.1:1")
    private val relay=AgentCandidate("p8-relay","relay-http","http://127.0.0.1:2")
    private fun connection(preference: String="automatic")=AgentConnection(target.scope(),listOf(lan,relay),"synthetic-p8",preference)
    private fun reject(block: ()->Unit) { assertTrue("Must refuse the altered boundary",runCatching(block).isFailure) }

    @Test fun independentNodeFramesAndAuthenticatedHeaders() {
        val v=vector;val c=EasyWorkCrypto(credential,credential.binding)
        assertEquals(v.getString("confirmation"),c.confirmation)
        for(name in listOf("frame","legacy")) {
            val opened=c.open(v.getJSONObject(name).toString().toByteArray())
            assertEquals(42L,opened.sequence);assertEquals(easyCanonical(v.getJSONObject("payload")),easyCanonical(opened.payload))
        }
        for(name in listOf("bindingId","senderDeviceId","recipientDeviceId","senderSessionId","sequence","sentAt","ciphertext","nonce")) {
            val frame=JSONObject(v.getJSONObject("frame").toString())
            frame.put(name,if(name=="sequence")43 else if(name in setOf("ciphertext","nonce"))"AAAA" else "tampered")
            reject { c.open(frame.toString().toByteArray()) }
        }
        reject { c.open(ByteArray(32*1024*1024+1)) }
        for(n in listOf(0L,-1L,9007199254740992L))reject { c.seal(JSONObject(),n) }
    }

    @Test fun independentEwdapJweDeliveryRefusesTamperingAndWrongRecipient() {
        val v=JSONObject(File(System.getProperty("p8.root"),"docs/plans/android-alignment-20261022/parallel/p8-easywork/ewdap-vector.json").readText())
        val i=v.getJSONObject("identity");val identity=EasyWorkIdentity(i.getString("deviceId"),i.getString("privateKey"),i.getString("publicKey"))
        val delivery=v.getString("delivery");val id=v.getString("deliveryId")
        assertEquals(easyCanonical(v.getJSONObject("payload")),easyCanonical(EasyWorkDeviceDelivery.open(identity,delivery,id)))
        reject { EasyWorkDeviceDelivery.open(identity,delivery,"other-delivery") }
        reject { EasyWorkDeviceDelivery.open(identity.copy(privateKey=easyBase64(ByteArray(32) { 7 })),delivery,id) }
        reject { EasyWorkDeviceDelivery.open(identity.copy(publicKey=easyBase64(ByteArray(32) { 9 })),delivery,id) }
        for(index in listOf(0,1,2,3,4)) {
            val parts=delivery.split('.').toMutableList();parts[index]=if(index==1)"unexpected-encrypted-key" else "AAAA"
            reject { EasyWorkDeviceDelivery.open(identity,parts.joinToString("."),id) }
        }
        for(field in listOf("apu","apv","crit","zip")) {
            val parts=delivery.split('.').toMutableList();val header=JSONObject(String(easyBytes(parts[0]))).put(field,"unsupported")
            parts[0]=easyBase64(header.toString().toByteArray())
            reject { EasyWorkDeviceDelivery.open(identity,parts.joinToString("."),id) }
        }
        reject { EasyWorkDeviceDelivery.open(identity,"a".repeat(65537),id) }
    }

    @Test fun bindingRequiresEveryTargetCoordinateAndDeviceKey() {
        val c=credential;val b=c.binding;val identity=EasyWorkIdentity(b.mobileId,c.privateKey,b.mobilePublicKey)
        assertEquals(b,b.verify(identity,target))
        val changes=listOf(target.copy(authority="https://other.example"),target.copy(subject="other"),target.copy(desktopDeviceId="other"),target.copy(workspaceId="other"),target.copy(runtimeId="other"))
        for(changed in changes)reject { b.verify(identity,changed) }
        for(field in listOf("mobileDeviceId","mobilePublicKey","desktopDeviceId","desktopPublicKey","status")) {
            val changed=EasyWorkBinding(b.json.put(field,"different").toString())
            reject { changed.verify(identity,target) }
        }
        reject { b.verify(identity.copy(deviceId="other"),target) }
        reject { b.verify(identity.copy(publicKey="other"),target) }
    }

    @Test fun authorizedBindingCannotFallBackToLegacyAccountToken() {
        val c=credential
        reject { EasyWorkRemoteSession(connection(),c.copy(grantRef="private-grant-ref"),{ "legacy-token" }) }
        val b=EasyWorkBinding(c.binding.json.put("deviceAuthorization",JSONObject().put("protocol","EWDAP/1")).toString())
        reject { EasyWorkRemoteSession(connection(),c.copy(binding=b),{ "legacy-token" }) }
        val changed=connection().copy(target=target.copy(subject="other").scope())
        reject { EasyWorkRemoteSession(changed,c,{ "legacy-token" }) }
    }

    private fun registry(c: EasyWorkCredential=credential,make: (AgentCandidate,EasyWorkBinding)->AgentFrameTransport): AgentTransportRegistry = AgentTransportRegistry().apply {
        for(kind in listOf("lan-wss","relay-http"))register(kind) { candidate,binding,_,_->
            assertEquals(c.binding.target,binding.target);make(candidate,binding)
        }
    }
    internal fun receipt(request: JSONObject,mutate: (JSONObject)->Unit={}): JSONObject {
        val params=request.getJSONObject("params")
        return JSONObject().put("state","accepted").put("operationId",params.getString("clientRequestId"))
            .put("method",request.getString("method")).put("target",target.json())
            .put("requestHash",easyHash(easyCanonical(JSONObject().put("target",target.json()).put("method",request.getString("method")).put("params",params)).toByteArray()))
            .put("response",JSONObject().put("ok",true).put("result",JSONObject().put("receipt","original"))).also(mutate)
    }
    internal suspend fun recovery(params: JSONObject=JSONObject().put("threadId","synthetic-thread").put("clientRequestId","stable-operation").put("text","immutable synthetic input"),status: (JSONObject)->JSONObject): Pair<List<JSONObject>,Result<JSONObject>> {
        val requests=CopyOnWriteArrayList<JSONObject>();var original: JSONObject?=null
        val c=credential
        val routes=registry(c) { candidate,binding->Peer(c.copy(binding=binding)) { request->
            requests.add(request)
            if(candidate.id==lan.id) { original=request;null }
            else { assertEquals("command/status",request.getString("method"));assertEquals("stable-operation",request.getJSONObject("params").getString("commandId"));status(original!!) }
        } }
        val session=EasyWorkRemoteSession(connection(),c,{ error("Injected transports must not request an account token") },routes)
        return try {
            val result=runCatching { withTimeout(5000) { session.request("turn/start",params,"stable-request") } }
            requests.toList() to result
        } finally { session.close() }
    }

    @Test fun lostWriteReplyUsesOriginalReceiptWithoutReplayingInput()=runBlocking {
        val (requests,result)=recovery { receipt(it) }
        assertEquals("original",result.getOrThrow().getString("receipt"))
        assertEquals(listOf("turn/start","command/status"),requests.map { it.getString("method") })
        assertEquals("stable-request",requests.first().getString("requestId"))
    }
    @Test fun unknownWriteReceiptRemainsUnknownWithoutReplay()=runBlocking {
        for(state in listOf("submitting","uncertain","missing")) {
            val (requests,result)=recovery { if(state=="missing")JSONObject() else JSONObject().put("state",state) }
            assertTrue(result.exceptionOrNull() is EasyWorkRejection)
            assertEquals(listOf("turn/start","command/status"),requests.map { it.getString("method") })
        }
    }
    @Test fun recoveredReceiptRequiresOriginalTargetMethodIdAndInputHash()=runBlocking {
        for(field in listOf("operationId","method","requestHash","target")) {
            val (requests,result)=recovery { receipt(it) { r->r.put(field,if(field=="target")target.copy(runtimeId="other").json() else "altered") } }
            assertTrue("Reject receipt with changed $field",result.isFailure)
            assertEquals(1,requests.count { it.getString("method")=="turn/start" })
            assertEquals(1,requests.count { it.getString("method")=="command/status" })
        }
    }
    @Test fun readRetryPreservesExactRequestAndUsesSecondChannel()=runBlocking {
        val requests=CopyOnWriteArrayList<JSONObject>();val selected=CopyOnWriteArrayList<String>();val c=credential
        val routes=registry(c) { candidate,binding->selected.add(candidate.id);Peer(c.copy(binding=binding)) { r->requests.add(r);if(candidate.id==lan.id)null else JSONObject().put("read","same") } }
        val session=EasyWorkRemoteSession(connection(),c,{ error("No account token") },routes)
        try {
            assertEquals("same",withTimeout(5000) { session.request("thread/read",JSONObject().put("threadId","read-only"),"stable-read") }.getString("read"))
            assertEquals(listOf(lan.id,relay.id),selected.toList())
            assertEquals(2,requests.size);assertEquals(easyCanonical(requests[0]),easyCanonical(requests[1]))
        } finally { session.close() }
    }
    @Test fun pathPreferencesUseOnlyTheSelectedTransport()=runBlocking {
        for((preference,expected) in listOf("lan-only" to lan.id,"relay-only" to relay.id)) {
            val selected=CopyOnWriteArrayList<String>();val c=credential
            val routes=registry(c) { candidate,binding->selected.add(candidate.id);Peer(c.copy(binding=binding)) { JSONObject() } }
            val session=EasyWorkRemoteSession(connection(preference),c,{ error("No account token") },routes)
            try { withTimeout(5000) { session.request("runtime/context") };assertEquals(listOf(expected),selected.toList()) }
            finally { session.close() }
        }
    }
    @Test fun arbitraryWireMethodIsRejectedBeforeOpeningAnyTransport()=runBlocking {
        var created=0;val c=credential
        val session=EasyWorkRemoteSession(connection(),c,{ error("No account token") },registry(c) { _,_->created++;error("Must not create") })
        try {
            for(method in listOf("shell/execute","electron/ipc","turn/replay","arbitrary")) assertTrue(runCatching { session.request(method) }.isFailure)
            assertEquals(0,created)
        } finally { session.close() }
    }

    /** Independent peer framing. Only network I/O is substituted; session and crypto stay production. */
    private class Peer(private val credential: EasyWorkCredential,private val reply: suspend (JSONObject)->JSONObject?): AgentFrameTransport {
        private val input=Channel<ByteArray>(Channel.UNLIMITED)
        private val session=UUID.randomUUID().toString();private var sequence=1L
        private val mobileToDesktop: ByteArray;private val desktopToMobile: ByteArray;private val confirmation: String
        init {
            val binding=credential.binding;val secret=ByteArray(32)
            X25519PrivateKeyParameters(easyBytes(credential.privateKey),0).generateSecret(X25519PublicKeyParameters(easyBytes(binding.desktopPublicKey),0),secret,0)
            fun key(salt: String,info: String): ByteArray {
                fun hmac(k: ByteArray,b: ByteArray)=Mac.getInstance("HmacSHA256").run { init(SecretKeySpec(k,"HmacSHA256"));doFinal(b) }
                return hmac(hmac(salt.toByteArray(),secret),info.toByteArray()+byteArrayOf(1))
            }
            mobileToDesktop=key("easyai-remote:${binding.id}","v1:mobile-to-desktop")
            desktopToMobile=key("easyai-remote:${binding.id}","v1:desktop-to-mobile")
            confirmation=easyBase64(key(binding.id,"easyai-remote:key-confirmation:v1").copyOf(16));secret.fill(0)
        }
        private fun aad(value: JSONObject)=easyCanonical(JSONArray().put(1).put(value.getString("bindingId")).put(value.getString("senderDeviceId")).put(value.getString("senderSessionId")).put(value.getString("recipientDeviceId")).put(value.getLong("sequence")).put(value.getString("sentAt"))).toByteArray()
        private fun encrypted(payload: JSONObject): ByteArray {
            val binding=credential.binding
            val value=JSONObject().put("version",1).put("bindingId",binding.id).put("senderDeviceId",binding.desktopId).put("senderSessionId",session).put("recipientDeviceId",binding.mobileId).put("sequence",sequence++).put("sentAt",Instant.now().toString())
            val nonce=ByteArray(12).also { SecureRandom().nextBytes(it) };val cipher=Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE,SecretKeySpec(desktopToMobile,"AES"),GCMParameterSpec(128,nonce));cipher.updateAAD(aad(value))
            return value.put("nonce",easyBase64(nonce)).put("ciphertext",easyBase64(cipher.doFinal(easyCanonical(payload).toByteArray()))).toString().toByteArray()
        }
        override suspend fun send(bytes: ByteArray) {
            val value=JSONObject(String(bytes));val cipher=Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE,SecretKeySpec(mobileToDesktop,"AES"),GCMParameterSpec(128,easyBytes(value.getString("nonce"))));cipher.updateAAD(aad(value))
            val payload=JSONObject(String(cipher.doFinal(easyBytes(value.getString("ciphertext")))))
            if(payload.getString("kind")=="key-confirmation") {
                require(payload.getString("value")==confirmation)
                input.send(encrypted(JSONObject().put("kind","key-confirmation").put("value",confirmation)))
            } else {
                val request=payload.getJSONObject("envelope")
                require(EasyWorkTarget.decode(request.getJSONObject("target"))==credential.binding.target)
                val result=reply(request)
                if(result==null) { input.close(IOException("synthetic lost response"));return }
                val response=JSONObject().put("protocolVersion",2).put("kind","response").put("requestId",request.getString("requestId")).put("target",credential.binding.target.json()).put("ok",true).put("result",result)
                input.send(encrypted(JSONObject().put("kind","protocol").put("envelope",response)))
            }
        }
        override suspend fun receive()=input.receive()
        override fun close() { input.close() }
    }
}
