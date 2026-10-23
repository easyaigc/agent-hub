package com.agenthub.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class MobileEasyWorkBoundaryTest {
    @Test fun developmentIdentityCannotMasqueradeAsAnAccountJWT()=runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val storage=NativeSessionStorage(context,"easywork-login-examiner")
        val login=EasyWorkLogin(storage);val target=EasyWorkTarget("https://issuer.example","user","desktop","workspace","runtime")
        val development="easyai-ios-simulator-v1."+easyBase64(JSONObject().put("deviceId","synthetic").put("publicKey","synthetic").toString().toByteArray())
        assertEquals(development,login.validToken(development,target))
        val wrongAccount="header."+easyBase64(JSONObject().put("iss",target.authority).put("sub","another-user").toString().toByteArray())+".signature"
        assertTrue(runCatching { login.validToken(wrongAccount,target) }.isFailure)
        storage.write("easywork-oidc-pending",JSONObject().put("state","expected").put("expiresAt",System.currentTimeMillis()+60000).toString())
        for(uri in listOf("com.easyai.remote://oauth/callback?state=wrong&code=synthetic","com.easyai.remote://other/callback?state=expected&code=synthetic","com.easyai.remote://oauth/callback?state=expected&state=wrong&code=synthetic","com.easyai.remote://oauth/callback?state=expected&code=synthetic#ignored")) assertTrue(runCatching { login.callback(android.net.Uri.parse(uri)) }.isFailure)
        assertNotNull(storage.read("easywork-oidc-pending"));storage.remove("easywork-oidc-pending")
    }
    @Test fun independentNodeCryptoAndDeviceIdentity()=runBlocking {
        val i=InstrumentationRegistry.getInstrumentation();val v=JSONObject(i.context.assets.open("easywork-crypto-vector.json").bufferedReader().use { it.readText() })
        val credential=EasyWorkCredential.decode(v.getJSONObject("credential"));val crypto=EasyWorkCrypto(credential,credential.binding)
        assertEquals(v.getString("confirmation"),crypto.confirmation)
        for(key in listOf("frame","legacy")) { val opened=crypto.open(v.getJSONObject(key).toString().toByteArray());assertEquals(42L,opened.sequence);assertEquals(easyCanonical(v.getJSONObject("payload")),easyCanonical(opened.payload)) }
        for(key in listOf("bindingId","senderDeviceId","recipientDeviceId","senderSessionId","sequence","sentAt","ciphertext","nonce")) {
            val modified=JSONObject(v.getJSONObject("frame").toString());if(key=="sequence")modified.put(key,43) else modified.put(key,if(key in listOf("ciphertext","nonce"))"AAAA" else "tampered")
            assertTrue("Authenticated $key must reject tampering",runCatching { crypto.open(modified.toString().toByteArray()) }.isFailure)
        }
        val output=File(i.targetContext.getExternalFilesDir(null),"acceptance/A5-boundary").apply { mkdirs() }
        File(output,"node-interoperability.json").writeBytes(crypto.seal(v.getJSONObject("payload"),77))
        val storage=NativeSessionStorage(i.targetContext,"easywork-identity-examiner")
        val identities=coroutineScope { (0 until 8).map { async(Dispatchers.IO) { EasyWorkIdentity.load(storage) } }.awaitAll() }
        assertEquals(1,identities.distinct().size);assertEquals(identities.first(),EasyWorkIdentity.load(storage))
        File(output,"native-boundary.json").writeText(JSONObject().put("nodeInbound",2).put("tamperRejected",8).put("identityConcurrency",8).put("api",android.os.Build.VERSION.SDK_INT).put("nativeX25519",true).toString())
    }
    @Test fun pairingTrustAndFullIdentityCache()=runBlocking {
        val i=InstrumentationRegistry.getInstrumentation();val v=JSONObject(i.context.assets.open("easywork-crypto-vector.json").bufferedReader().use { it.readText() });val credential=EasyWorkCredential.decode(v.getJSONObject("credential"));val target=credential.binding.target.scope()
        val connection=AgentConnection(target,listOf(AgentCandidate("pinned","lan-wss","https://127.0.0.1:9999","a".repeat(64))),"synthetic-ref")
        assertEquals(connection,AgentConnection.decode(connection.json()))
        assertTrue(runCatching { AgentConnection.decode(connection.json().put("preference","guess")) }.isFailure)
        val offer=JSONObject().put("endpoints",org.json.JSONArray(listOf("https://127.0.0.1:9999"))).put("fingerprint","a".repeat(64)).put("publicKey",credential.binding.desktopPublicKey).put("target",credential.binding.target.json()).put("offerId","synthetic").put("pairingCode","123456").put("expiresAt","2000-01-01T00:00:00.000Z")
        fun uri()="easywork-host://pair?descriptor="+easyBase64(offer.toString().toByteArray())
        assertTrue(runCatching { EasyWorkLANOffer.parse(uri()) }.isFailure)
        offer.put("connectionMode","automatic");assertEquals(credential.binding.target,EasyWorkLANOffer.parse(uri()).host.target)
        offer.put("endpoints",org.json.JSONArray(listOf("https://public.example")));assertTrue(runCatching { EasyWorkLANOffer.parse(uri()) }.isFailure)
        val client=MobileClient("display-instance","same name",target,connection.candidates.first().endpoint,mode="easywork-runtime",isDemo=false,agentConnection=connection)
        val journal=NativeOperationJournal(NativeSessionStorage(i.targetContext,"easywork-journal-examiner"))
        val operation="boundary-"+java.util.UUID.randomUUID()
        val intent=ReplyIntent(client.id,"thread",operation,"immutable",emptyList(),"next-turn")
        val record=journal.prepare(client,"thread","reply",intent.body())
        assertEquals(operation,record.id);assertEquals(NativeOperationJournal.target(client,"thread"),record.target)
        assertTrue(runCatching { journal.prepare(client,"thread","reply",intent.copy(text="changed").body()) }.isFailure)
        val roundtrip=decodeClient(encodeClient(client));assertTrue(sameScope(client.scope,roundtrip.scope));assertEquals(connection,roundtrip.agentConnection)
        val changed=client.copy(scope=credential.binding.target.copy(runtimeId="different").scope(),agentConnection=connection.copy(target=credential.binding.target.copy(runtimeId="different").scope()))
        assertNotEquals(NativeOperationJournal.target(client,"thread"),NativeOperationJournal.target(changed,"thread"))
        assertEquals(NativeOperationJournal.target(client,"thread"),NativeOperationJournal.target(client.copy(endpoint="https://127.0.0.1:10000",agentConnection=connection.copy(candidates=listOf(connection.candidates.first().copy(endpoint="https://127.0.0.1:10000")))) ,"thread"))
    }
}
