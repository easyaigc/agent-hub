package com.agenthub.android

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import org.json.JSONArray
import org.json.JSONObject
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.math.BigInteger
import java.security.KeyStore
import java.security.MessageDigest
import java.security.Signature
import org.bouncycastle.asn1.ASN1Integer
import org.bouncycastle.asn1.DERSequence
import org.junit.Test
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Uses Android JSONObject/Base64 and production crypto/session with in-memory transports.
 * Does not open a socket, launch an Activity, contact Desktop or submit a model task.
 * Every method has a 12-second JUnit bound; the coordinator also caps instrumentation at 240s.
 */
@RunWith(AndroidJUnit4::class)
class NativeEasyWorkProtocolBoundaryTest {
    private val vector get()=InstrumentationRegistry.getInstrumentation().context.assets.open("easywork-crypto-vector.json").use { JSONObject(String(it.readBytes(),Charsets.UTF_8)) }
    private val credential get()=EasyWorkCredential.decode(vector.getJSONObject("credential"))
    private val target get()=credential.binding.target
    private val lan=AgentCandidate("p8-lan","lan-wss","https://127.0.0.1:1")
    private val relay=AgentCandidate("p8-relay","relay-http","http://127.0.0.1:2")
    private fun connection(preference: String="automatic")=AgentConnection(target.scope(),listOf(lan,relay),"synthetic-p8",preference)
    private fun reject(block: ()->Unit) { assertTrue("Must refuse the altered boundary",runCatching(block).isFailure) }

    @Test(timeout=12_000) fun independentNodeFramesAndAuthenticatedHeaders() {
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
        // Keep valid lengths so these cases reach authentication rather than format rejection.
        for(field in listOf("nonce","ciphertext")) {
            val frame=JSONObject(v.getJSONObject("frame").toString())
            val bytes=easyBytes(frame.getString(field));bytes[0]=(bytes[0].toInt() xor 1).toByte()
            frame.put(field,easyBase64(bytes))
            val failure=runCatching { c.open(frame.toString().toByteArray(Charsets.UTF_8)) }.exceptionOrNull()
            assertTrue("Same-length $field mutation must fail cryptographic authentication",failure is GeneralSecurityException)
        }
        for(n in listOf(0L,-1L,9007199254740992L))reject { c.seal(JSONObject(),n) }
    }

    @Test(timeout=12_000) fun independentEwdapJweDeliveryRefusesTamperingAndWrongRecipient() {
        val v=JSONObject(EWDAP_VECTOR)
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
        for(index in listOf(2,3,4)) {
            val parts=delivery.split('.').toMutableList();val bytes=easyBytes(parts[index])
            bytes[0]=(bytes[0].toInt() xor 1).toByte();parts[index]=easyBase64(bytes)
            val failure=runCatching { EasyWorkDeviceDelivery.open(identity,parts.joinToString("."),id) }.exceptionOrNull()
            assertTrue("Same-length JWE part $index mutation must fail authentication",failure is GeneralSecurityException)
        }
        for(field in listOf("apu","apv","crit","zip")) {
            val parts=delivery.split('.').toMutableList();val header=JSONObject(String(easyBytes(parts[0]))).put(field,"unsupported")
            parts[0]=easyBase64(header.toString().toByteArray())
            reject { EasyWorkDeviceDelivery.open(identity,parts.joinToString("."),id) }
        }
        reject { EasyWorkDeviceDelivery.open(identity,"a".repeat(65537),id) }
    }

    @Test(timeout=12_000) fun bindingRequiresEveryTargetCoordinateAndDeviceKey() {
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

    @Test(timeout=12_000) fun authorizedBindingCannotFallBackToLegacyAccountToken() {
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
    private fun receipt(request: JSONObject,mutate: (JSONObject)->Unit={}): JSONObject {
        val params=request.getJSONObject("params")
        return JSONObject().put("state","accepted").put("operationId",params.getString("clientRequestId"))
            .put("method",request.getString("method")).put("target",target.json())
            .put("requestHash",easyHash(easyCanonical(JSONObject().put("target",target.json()).put("method",request.getString("method")).put("params",params)).toByteArray()))
            .put("response",JSONObject().put("ok",true).put("result",JSONObject().put("receipt","original"))).also(mutate)
    }
    private suspend fun recovery(status: (JSONObject)->JSONObject): Pair<List<JSONObject>,Result<JSONObject>> {
        val requests=CopyOnWriteArrayList<JSONObject>();var original: JSONObject?=null
        val c=credential
        val routes=registry(c) { candidate,binding->Peer(c.copy(binding=binding)) { request->
            requests.add(request)
            if(candidate.id==lan.id) { original=request;null }
            else { assertEquals("command/status",request.getString("method"));assertEquals("stable-operation",request.getJSONObject("params").getString("commandId"));status(original!!) }
        } }
        val session=EasyWorkRemoteSession(connection(),c,{ error("Injected transports must not request an account token") },routes)
        return try {
            val result=runCatching { withTimeout(4000) { session.request("turn/start",JSONObject().put("threadId","synthetic-thread").put("clientRequestId","stable-operation").put("text","immutable synthetic input"),"stable-request") } }
            requests.toList() to result
        } finally { session.close() }
    }

    @Test(timeout=12_000) fun lostWriteReplyUsesOriginalReceiptWithoutReplayingInput()=runBlocking {
        val (requests,result)=recovery { receipt(it) }
        assertEquals("original",result.getOrThrow().getString("receipt"))
        assertEquals(listOf("turn/start","command/status"),requests.map { it.getString("method") })
        assertEquals("stable-request",requests.first().getString("requestId"))
    }
    @Test(timeout=12_000) fun unknownWriteReceiptRemainsUnknownWithoutReplay()=runBlocking {
        for(state in listOf("submitting","uncertain","missing")) {
            val (requests,result)=recovery { if(state=="missing")JSONObject() else JSONObject().put("state",state) }
            assertTrue(result.exceptionOrNull() is EasyWorkRejection)
            assertEquals(listOf("turn/start","command/status"),requests.map { it.getString("method") })
        }
    }
    private suspend fun rejectsChangedReceiptField(field: String) {
        val (requests,result)=recovery { receipt(it) { r->r.put(field,if(field=="target")target.copy(runtimeId="other").json() else "altered") } }
        // A timeout or an injected-peer error must never satisfy a negative receipt assertion.
        val failure=result.exceptionOrNull()
        assertTrue("Exact receipt validation must refuse changed $field; got ${failure?.javaClass?.name}",failure is IllegalArgumentException)
        assertEquals("原操作回执身份或内容不匹配",failure?.message)
        assertEquals(listOf("turn/start","command/status"),requests.map { it.getString("method") })
    }
    @Test(timeout=12_000) fun recoveredReceiptRejectsChangedOperationId()=runBlocking { rejectsChangedReceiptField("operationId") }
    @Test(timeout=12_000) fun recoveredReceiptRejectsChangedMethod()=runBlocking { rejectsChangedReceiptField("method") }
    @Test(timeout=12_000) fun recoveredReceiptRejectsChangedTarget()=runBlocking { rejectsChangedReceiptField("target") }
    @Test(timeout=12_000) fun recoveredReceiptRejectsChangedInputHash()=runBlocking { rejectsChangedReceiptField("requestHash") }
    @Test(timeout=12_000) fun readRetryPreservesExactRequestAndUsesSecondChannel()=runBlocking {
        val requests=CopyOnWriteArrayList<JSONObject>();val selected=CopyOnWriteArrayList<String>();val c=credential
        val routes=registry(c) { candidate,binding->selected.add(candidate.id);Peer(c.copy(binding=binding)) { r->requests.add(r);if(candidate.id==lan.id)null else JSONObject().put("read","same") } }
        val session=EasyWorkRemoteSession(connection(),c,{ error("No account token") },routes)
        try {
            assertEquals("same",withTimeout(4000) { session.request("thread/read",JSONObject().put("threadId","read-only"),"stable-read") }.getString("read"))
            assertEquals(listOf(lan.id,relay.id),selected.toList())
            assertEquals(2,requests.size);assertEquals(easyCanonical(requests[0]),easyCanonical(requests[1]))
        } finally { session.close() }
    }
    @Test(timeout=12_000) fun pathPreferencesUseOnlyTheSelectedTransport()=runBlocking {
        for((preference,expected) in listOf("lan-only" to lan.id,"relay-only" to relay.id)) {
            val selected=CopyOnWriteArrayList<String>();val c=credential
            val routes=registry(c) { candidate,binding->selected.add(candidate.id);Peer(c.copy(binding=binding)) { JSONObject() } }
            val session=EasyWorkRemoteSession(connection(preference),c,{ error("No account token") },routes)
            try { withTimeout(4000) { session.request("runtime/context") };assertEquals(listOf(expected),selected.toList()) }
            finally { session.close() }
        }
    }
    @Test(timeout=12_000) fun arbitraryWireMethodIsRejectedBeforeOpeningAnyTransport()=runBlocking {
        var created=0;val c=credential
        val session=EasyWorkRemoteSession(connection(),c,{ error("No account token") },registry(c) { _,_->created++;error("Must not create") })
        try {
            for(method in listOf("shell/execute","electron/ipc","turn/replay","arbitrary")) assertTrue(runCatching { session.request(method) }.isFailure)
            assertEquals(0,created)
        } finally { session.close() }
    }

    @Test(timeout=12_000) fun nonIntegralSequenceCannotReuseAuthenticatedIntegerHeader() {
        val frame=JSONObject(vector.getJSONObject("frame").toString()).put("sequence",42.5)
        // Regression intent: authenticated integer 42 must not authenticate JSON 42.5.
        // Baseline source uses JSONObject.getLong, which may coerce this to 42.
        // It is deliberately not ignored; native result must be retained even if it fails.
        reject { EasyWorkCrypto(credential,credential.binding).open(frame.toString().toByteArray(Charsets.UTF_8)) }
    }

    @Test(timeout=12_000) fun dedicatedSystemDpopKeyPersistsAndBindsMethodUrlTokenAndBody() {
        val c=credential
        // Never load the user's default identity or enumerate/delete any original key.
        val identity=EasyWorkIdentity(DPOP_DEVICE_ID,c.privateKey,c.binding.mobilePublicKey)
        val first=EasyWorkDPoP.load(identity);val again=EasyWorkDPoP.load(identity)
        assertEquals(first.thumbprint,again.thumbprint)
        val alias="agent-hub-easywork-dpop-"+easyHash(DPOP_DEVICE_ID.toByteArray(Charsets.UTF_8))
        val store=KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val entry=store.getEntry(alias,null) as KeyStore.PrivateKeyEntry
        assertNull("The dedicated signing key remains non-exportable",entry.privateKey.encoded)
        assertFalse(first.jwk.has("d"))
        val body="{\"operationId\":\"synthetic-p8-only\"}".toByteArray(Charsets.UTF_8)
        val url="https://relay.example.test/v2/remote-access/relay/envelopes?ignored=value#ignored"
        val token="public-p8-synthetic-access-token"
        val proof=first.proof("POST",url,token,body=body)
        val parts=proof.split('.');assertEquals(3,parts.size)
        val header=JSONObject(String(easyBytes(parts[0]),Charsets.UTF_8))
        val claims=JSONObject(String(easyBytes(parts[1]),Charsets.UTF_8))
        assertEquals("dpop+jwt",header.getString("typ"));assertEquals("ES256",header.getString("alg"))
        assertEquals(easyCanonical(first.jwk),easyCanonical(header.getJSONObject("jwk")))
        assertEquals("POST",claims.getString("htm"))
        assertEquals("https://relay.example.test/v2/remote-access/relay/envelopes",claims.getString("htu"))
        fun digest(bytes: ByteArray)=easyBase64(MessageDigest.getInstance("SHA-256").digest(bytes))
        assertEquals(digest(token.toByteArray(Charsets.US_ASCII)),claims.getString("ath"));assertEquals(digest(body),claims.getString("bht"))
        val raw=easyBytes(parts[2]);assertEquals(64,raw.size)
        val der=DERSequence(arrayOf(ASN1Integer(BigInteger(1,raw.copyOfRange(0,32))),ASN1Integer(BigInteger(1,raw.copyOfRange(32,64))))).encoded
        val input=parts.take(2).joinToString(".").toByteArray(Charsets.US_ASCII)
        fun verified(bytes: ByteArray)=Signature.getInstance("SHA256withECDSA").run { initVerify(entry.certificate.publicKey);update(bytes);verify(der) }
        assertTrue(verified(input));assertFalse(verified(input+byteArrayOf(33)))
        val next=again.proof("POST",url,token,body=body).split('.')
        assertNotEquals(claims.getString("jti"),JSONObject(String(easyBytes(next[1]),Charsets.UTF_8)).getString("jti"))
        for(invalid in listOf("http://relay.example.test/path","https://name:password@relay.example.test/path"))reject { first.proof("POST",invalid,token,body=body) }
        reject { first.proof("POST",url,"invalid\r\nheader",body=body) }
    }

    private companion object {
        const val DPOP_DEVICE_ID="p8-native-protocol-boundary-v1-synthetic-only"
        // Public deterministic Node ECDH-ES/A256GCM fixture; never a real device grant.
        const val EWDAP_VECTOR="""{"version":1,"identity":{"deviceId":"mobile-vector","privateKey":"dwdtCnMYpX08FsFyUbJmRd9ML4frwJkqsXf7pR25LCo","publicKey":"hSDwCYkwp1R0i33ctD73Wg2_Og0mOBr066SpjqqbTmo"},"deliveryId":"3d1d45bb-8db4-43bd-80d5-27c7fae639d3","payload":{"protocol":"EWDAP/1","deliveryId":"3d1d45bb-8db4-43bd-80d5-27c7fae639d3","synthetic":"independent Node delivery; no real credentials"},"delivery":"eyJhbGciOiJFQ0RILUVTIiwiZW5jIjoiQTI1NkdDTSIsInR5cCI6ImV3ZGFwK2p3ZSIsInZlcnNpb24iOjEsImRlbGl2ZXJ5SWQiOiIzZDFkNDViYi04ZGI0LTQzYmQtODBkNS0yN2M3ZmFlNjM5ZDMiLCJlcGsiOnsiY3J2IjoiWDI1NTE5IiwieCI6IlpyMURVVGhvV1Fsd0FxaHlKSU5mTHBsdkRvRVRjMUxFLVhuS0x6d1ZiMXMiLCJrdHkiOiJPS1AifX0..qavxXK1P0rwgasnZ.yP4TRxsBfPNonDcSEXFGPWWF22Vj_JgDtYpAYES7KtObeAWho_h32mMftyQXCe_MJp_DLsy0clfymnXegtr1e5AnMsFxnk_lRW1Ggh_tahBF1GuelUug6-0wtC2i73yIHz82bfHs7jV2H2S28TSTdR70gltojqLdrYFEv_rva9-4gENWRhDO.2Ty3m290vdpeolhHPc3sww"}"""
    }

    @Test(timeout=12_000) fun strictInboundNumbersRejectWrongTypesBoundsAndVersion() {
        // Independent Node frames and numeric tokens; no host JSON or Base64 shim.
        val cases=JSONObject("""{"version":1,"sourceBase":"bf5d1a7aeaaea4623078973f8e58ca70e288e7ab","sourceFixtureSha256":"b511e868a6c893552ba9bc554f1ff57dfb052524420d181dcbc6454dff288104","frames":{"minimum":{"version":1,"bindingId":"binding-vector","senderDeviceId":"desktop-vector","senderSessionId":"p8-numeric-node-vector","recipientDeviceId":"mobile-vector","sequence":1,"sentAt":"2026-10-28T00:00:00.000Z","nonce":"s-P8xBpdaBk86JJr","ciphertext":"fCFPLttTLeNTun_2qCB-cm_YxbBUKjOMbz3z116oLWsuKEj7s70K11yRQKbiPz-d9A6WeNYpK3doSaqEt6iu8Q7RWrvwdcVRd7G7t-QuRZUlPR8jQuuegml_VZ91zkXmYM3z2F9s-23hSVpNbnTc85QzkhYshpMq0D-ns_mwnFVkKiyVJzkG6z2V0Su7Ku2hbZLKDf-w5EAD5YGR1fsEQ64Wo-KvBpVEQUJKZu0-bGqDg55h9FrmOQGsDwxuXQ"},"maximum":{"version":1,"bindingId":"binding-vector","senderDeviceId":"desktop-vector","senderSessionId":"p8-numeric-node-vector","recipientDeviceId":"mobile-vector","sequence":9007199254740991,"sentAt":"2026-10-28T00:00:00.000Z","nonce":"x7pMhrv0MoM0p8qc","ciphertext":"0-C33ERC0vriVRXZx5GB3lHrDGU-4bnpavi4NE3OFOfBXBcQNbnzaGyUh0JGpS_hPlRi-3kKBxB9eZra0lJLZ9oZzYORnW5Hb67doEfIMUw9iVN-N_u5V0DvxyrVdpVVpi99D5LwR5HRZk4FKtoY2kY5szuNlnB499bdiepIPk395qjMlB__xquXwIElC8-to13DIM_DfiGn4H9uTkNQnLC3ompoELG2pTRzginZwz6U3gKqANW5HpafeCml6Q"}},"cases":[{"id":"sequence-integer","field":"sequence","token":"42","accepted":true},{"id":"sequence-integral-decimal","field":"sequence","token":"42.0","accepted":true},{"id":"sequence-integral-exponent","field":"sequence","token":"4.2e1","accepted":true},{"id":"version-integer","field":"version","token":"1","accepted":true},{"id":"version-integral-decimal","field":"version","token":"1.0","accepted":true},{"id":"version-integral-exponent","field":"version","token":"1e0","accepted":true},{"id":"sequence-string","field":"sequence","token":"\"42\"","accepted":false},{"id":"sequence-true","field":"sequence","token":"true","accepted":false},{"id":"sequence-false","field":"sequence","token":"false","accepted":false},{"id":"sequence-null","field":"sequence","token":"null","accepted":false},{"id":"sequence-object","field":"sequence","token":"{}","accepted":false},{"id":"sequence-array","field":"sequence","token":"[]","accepted":false},{"id":"sequence-fraction","field":"sequence","token":"42.5","accepted":false},{"id":"sequence-zero","field":"sequence","token":"0","accepted":false},{"id":"sequence-negative","field":"sequence","token":"-1","accepted":false},{"id":"sequence-above-safe","field":"sequence","token":"9007199254740992","accepted":false},{"id":"sequence-long-max","field":"sequence","token":"9223372036854775807","accepted":false},{"id":"sequence-overflow","field":"sequence","token":"1e999","accepted":false},{"id":"sequence-underflow","field":"sequence","token":"1e-999","accepted":false},{"id":"sequence-nan-token","field":"sequence","token":"NaN","accepted":false},{"id":"sequence-infinity-token","field":"sequence","token":"Infinity","accepted":false},{"id":"version-string","field":"version","token":"\"1\"","accepted":false},{"id":"version-true","field":"version","token":"true","accepted":false},{"id":"version-false","field":"version","token":"false","accepted":false},{"id":"version-null","field":"version","token":"null","accepted":false},{"id":"version-object","field":"version","token":"{}","accepted":false},{"id":"version-array","field":"version","token":"[]","accepted":false},{"id":"version-fraction","field":"version","token":"1.5","accepted":false},{"id":"version-zero","field":"version","token":"0","accepted":false},{"id":"version-negative","field":"version","token":"-1","accepted":false},{"id":"version-unsupported","field":"version","token":"2","accepted":false},{"id":"version-above-safe","field":"version","token":"9007199254740992","accepted":false},{"id":"version-overflow","field":"version","token":"1e999","accepted":false}]}""")
        val v=vector;val c=credential;val crypto=EasyWorkCrypto(c,c.binding)
        for((name,sequence) in listOf("minimum" to 1L,"maximum" to 9007199254740991L)) {
            val opened=crypto.open(cases.getJSONObject("frames").getJSONObject(name).toString().toByteArray(Charsets.UTF_8))
            assertEquals(sequence,opened.sequence);assertEquals(easyCanonical(v.getJSONObject("payload")),easyCanonical(opened.payload))
        }
        val base=v.getJSONObject("frame").toString();val values=cases.getJSONArray("cases")
        for(index in 0 until values.length()) {
            val item=values.getJSONObject(index);val field=item.getString("field")
            val match=Regex("\""+field+"\"\\s*:\\s*(?:42|1)(?=[,}])").find(base)?:error("Missing fixture number")
            val raw=base.replaceRange(match.range,match.value.substringBefore(':')+":"+item.getString("token"))
            val result=runCatching { crypto.open(raw.toByteArray(Charsets.UTF_8)) }
            if(item.getBoolean("accepted"))assertEquals(item.getString("id"),42L,result.getOrThrow().sequence)
            else {
                val failure=result.exceptionOrNull()
                assertTrue("${item.getString("id")} must reject before GCM, got ${failure?.javaClass?.name}",failure is IllegalArgumentException||failure is org.json.JSONException)
            }
        }
        for(field in listOf("sequence","version")) {
            val frame=JSONObject(base).apply { remove(field) }
            assertTrue(runCatching { crypto.open(frame.toString().toByteArray()) }.exceptionOrNull() is IllegalArgumentException)
        }
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
