package com.agenthub.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.agenthub.core.PrivateConnectionRules
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/** Actual TLS/Host issuer/Android crypto and encrypted atomic state; no real model proof is claimed. */
@RunWith(AndroidJUnit4::class)
class MobilePrivateConnectionTest {
    private val instrument get()=InstrumentationRegistry.getInstrumentation()
    private val args get()=InstrumentationRegistry.getArguments()
    private val endpoint get()=args.getString("privateEndpoint")!!
    private val control get()=args.getString("privateControl")!!
    private val namespace="um04-boundary-"+UUID.randomUUID()
    private val store by lazy { NativeSessionStorage(instrument.targetContext,namespace) }
    private fun encryptedRecords()=java.io.File(instrument.targetContext.noBackupFilesDir,namespace).listFiles()!!.filter { it.extension=="bin" }.associate { it.name to java.security.MessageDigest.getInstance("SHA-256").digest(it.readBytes()).toList() }
    private val host get()=DiscoveredCodexHost("um04-private-fixture","UM04 isolated computer",endpoint,false,true)
    private fun mode(value: String) {
        val c=URL(control+"/mode").openConnection() as HttpURLConnection
        try { c.requestMethod="POST";c.doOutput=true;c.setRequestProperty("Content-Type","application/json");c.outputStream.use { it.write(JSONObject().put("mode",value).toString().toByteArray()) };assertEquals(200,c.responseCode);c.inputStream.close() } finally { c.disconnect() }
    }
    private fun stats(): JSONObject = (URL(control+"/stats").openConnection() as HttpURLConnection).let { c -> try { JSONObject(c.inputStream.bufferedReader().use { it.readText() }) } finally { c.disconnect() } }
    private fun posts(s: JSONObject=stats())=s.getJSONArray("requests").objects().filter { it.getString("method")=="POST" }
    private fun client(auth: CodexPrivateAuthorization)=MobileClient(host.id,host.name,auth.descriptor.target,endpoint,true,"desktop-runtime",false,"reader",true,
        PairedCodexConnection(auth.descriptor.target,auth.descriptor.fingerprint,(listOf(CodexRoute(endpoint))+auth.descriptor.routes).distinctBy { it.endpoint }))
    @Before fun reset()=mode("normal")

    @Test fun crossPlatformVectorAuthenticatesExactCapturedMetadataAndRejectsTampering() {
        val vector=JSONObject(instrument.context.assets.open("private-connection-v1.json").bufferedReader().use { it.readText() })
        val identity=vector.getJSONObject("identity").let { EasyWorkIdentity(it.getString("deviceId"),it.getString("privateKey"),it.getString("publicKey")) }
        val target=HostReadCodec.target(vector.getJSONObject("target"))!!
        val reply=vector.getJSONObject("reply")
        assertEquals(vector.getString("expectedToken"),CodexPrivateCrypto.open(reply.toString(),target,identity))
        assertEquals(vector.getString("contextUTF8Hex"),PrivateConnectionRules.context(target,identity.publicKey).toByteArray().joinToString("") { "%02x".format(it.toInt() and 255) })
        for(change in listOf<(JSONObject)->Unit>(
            { it.getJSONObject("target").getJSONObject("account").put("subject","different") },
            { it.put("publicKey","K".repeat(43)) },{ it.put("ephemeralPublicKey","A".repeat(43)) },
            { val bytes=easyBytes(it.getString("ciphertext"));bytes[0]=(bytes[0].toInt() xor 1).toByte();it.put("ciphertext",easyBase64(bytes)) },
            { it.put("nonce",it.getString("nonce")+"=") },{ it.put("unexpected",true) })) {
            val bad=JSONObject(reply.toString());change(bad)
            try { CodexPrivateCrypto.open(bad.toString(),target,identity);fail("Tampered private credential accepted") } catch(_: Exception) {}
        }
    }
    @Test fun publicDiscoveryHasNoCredentialAndNeverCreatesClientAuthorization()=runBlocking {
        val hints=listOf(host)
        val result=CodexPublicDiscovery(store).scan(hints,emptyList(),{ error("Public probing cannot read a credential") },{true})
        assertEquals(host.id,result.codex.single().id);assertTrue(CodexConnectionVault(store).clients().isEmpty())
        assertTrue(posts().isEmpty());assertTrue(stats().getJSONArray("requests").objects().all { !it.getBoolean("authorizationPresent") })
    }
    @Test fun verifiedHintRefreshKeepsReplyButTransportChangeStopsBeforeSend()=runBlocking {
        val auth=CodexDiscoveryAPI.authorize(host,EasyWorkIdentity.load(store),store)
        for(changeTransport in listOf(false,true)) {
            val original=client(auth);CodexConnectionVault(store).save(original,auth.token)
            val gate=CompletableDeferred<Unit>();val entered=CompletableDeferred<Unit>()
            var block=false;var sends=0
            val native=NativeHostReads(store::token)
            val reader=object: HostReads by native {
                override suspend fun workspace(client: MobileClient,projectCursor: String?,threadCursor: String?): MobileWorkspace {
                    if(block) { entered.complete(Unit);gate.await() }
                    return auth.workspace.copy(threads=listOf(MobileThread("hint-thread",original.id,"hint test","idle")),
                        // Exercise the fresh-authority fallback, not a later background directory GET.
                        // A fully confirmed runtime snapshot legitimately skips that GET.
                        capabilities=listOf("reply","commands:target-bound:v1"),runtimeConnected=null)
                }
                override suspend fun history(client: MobileClient,threadId: String,cursor: String?)=MobileHistory(original.id,threadId,emptyList(),null,original.scope,"idle",null,"{}",true,
                    MobileRuntime("idle",null,emptyList(),null,emptyList(),emptyList(),true,null,null,GenerationSettings()))
            }
            val commands=object: CodexCommands by NativeCodexCommands(store::token) {
                override suspend fun send(client: MobileClient,intent: ReplyIntent): DeliveryReceipt {
                    sends++;return DeliveryReceipt(client.id,intent.threadId,intent.operationId,"submitted","aux-turn",null,null,intent.text,emptyList())
                }
            }
            val session=withContext(Dispatchers.Main) { MobileSession(store,reader,commands).also { it.restore() } }
            try {
                withTimeout(10_000) { while(withContext(Dispatchers.Main) { session.restoring || session.loading || session.threads.isEmpty() })delay(20) }
                withContext(Dispatchers.Main) { session.selectThread(session.threads.first()) }
                withTimeout(10_000) { while(withContext(Dispatchers.Main) { session.historyLoading || !session.canCompose })delay(20) }
                withContext(Dispatchers.Main) { allowSyntheticRegressionSharing(session) }
                withContext(Dispatchers.Main) { session.text="one guarded auxiliary reply";block=true;session.performAction() }
                withTimeout(10_000) { entered.await() }
                withContext(Dispatchers.Main) {
                    val current=session.selectedClient
                    val alternate=endpoint.replace("127.0.0.1","localhost")
                    val descriptor=auth.descriptor.copy(routes=auth.descriptor.routes+CodexRoute(alternate,"lan"))
                    assertTrue(session.updateCodexRoute(current,if(changeTransport) alternate else endpoint,descriptor))
                    block=false;gate.complete(Unit)
                }
                withTimeout(10_000) { while(withContext(Dispatchers.Main) { session.transferStatus!=null })delay(20) }
                assertEquals(if(changeTransport) 0 else 1,sends)
                if(changeTransport) assertEquals("one guarded auxiliary reply",withContext(Dispatchers.Main) { session.text })
            } finally { gate.complete(Unit);withContext(Dispatchers.Main) { session.leaveForeground() } }
        }
    }
    @Test fun explicitClaimUsesOnlyThreeFieldsAndAuthenticatesWorkspace()=runBlocking {
        val auth=CodexDiscoveryAPI.authorize(host,EasyWorkIdentity.load(store),store)
        val post=posts().single();assertEquals(setOf("publicKey","target","version"),post.getJSONArray("fields").strings().toSet());assertFalse(post.getBoolean("authorizationPresent"))
        assertTrue(stats().getJSONArray("requests").objects().filter { it.getString("path")=="/host" }.all { !it.getBoolean("authorizationPresent") })
        assertTrue(stats().getJSONArray("requests").objects().single { it.getString("path")=="/v1/workspace" }.getBoolean("authorizationPresent"))
        assertEquals(host.id,auth.workspace.hostId);assertTrue(store.token(host.id).isEmpty())
        CodexConnectionVault(store).save(client(auth),auth.token);assertEquals(auth.token,store.token(host.id))
    }
    @Test fun wrongCertificateHostOrLegacyCapabilityIsRejectedBeforeAnyClaim()=runBlocking {
        store.setToken(host.id,"fixture-original-token-".repeat(2))
        for(value in listOf("bad-fingerprint","wrong-host","legacy-host","redirect-host")) {
            mode(value)
            try { CodexDiscoveryAPI.authorize(host,EasyWorkIdentity.load(store),store);fail(value) } catch(_: Exception) {}
            assertTrue(posts().isEmpty());assertEquals("fixture-original-token-".repeat(2),store.token(host.id))
        }
    }
    @Test fun rejectedCiphertextReplyTargetOrWorkspacePreservesOriginalCredential()=runBlocking {
        val original="fixture-original-token-".repeat(2);store.setToken(host.id,original)
        for(value in listOf("bad-tag","wrong-reply-target","wrong-workspace","denied-claim")) {
            mode(value)
            try { CodexDiscoveryAPI.authorize(host,EasyWorkIdentity.load(store),store);fail(value) } catch(_: Exception) {}
            assertEquals(1,posts().size);assertEquals(original,store.token(host.id));assertTrue(CodexConnectionVault(store).clients().isEmpty())
        }
    }
    @Test fun unknownClaimDoesNotReplayAndExplicitSameKeyRetryUsesExistingBinding()=runBlocking {
        val identity=EasyWorkIdentity.load(store);val before=stats().getInt("bindingCount")
        mode("drop-claim-response")
        try { CodexDiscoveryAPI.authorize(host,identity,store);fail("Lost response accepted") } catch(_: Exception) {}
        delay(500);assertEquals(1,posts().size);assertEquals(before+1,stats().getInt("bindingCount"));assertTrue(store.token(host.id).isEmpty())
        val firstKey=posts().single().getString("publicKey");val originalRecords=encryptedRecords()
        mode("normal");val auth=CodexDiscoveryAPI.authorize(host,identity,store)
        assertEquals(originalRecords,encryptedRecords());assertEquals(firstKey,posts().single().getString("publicKey"));assertEquals(before+1,stats().getInt("bindingCount"))
        assertTrue(auth.token.matches(Regex("[A-Za-z0-9_-]{43}")))
    }
    @Test fun atomicCredentialAndClientSurviveMissingOrdinaryMetadataWithoutNewClaim()=runBlocking {
        val original="fixture-original-token-".repeat(2);store.setToken(host.id,original)
        val auth=CodexDiscoveryAPI.authorize(host,EasyWorkIdentity.load(store),store)
        val vault=CodexConnectionVault(store);vault.save(client(auth),auth.token)
        assertNull(store.metadata());assertEquals(auth.token,store.token(host.id))
        val restored=withContext(Dispatchers.Main) { MobileSession(store).also { it.restore() } }
        try {
            withTimeout(15_000) { while(withContext(Dispatchers.Main) { restored.restoring || !restored.hasClient })delay(50) }
            assertEquals(host.id,withContext(Dispatchers.Main) { restored.selectedClient.id });assertEquals(1,posts().size)
        } finally { withContext(Dispatchers.Main) { restored.resetAcceptance() } }
        vault.remove(host.id);assertEquals(original,store.token(host.id))
    }
    @Test fun explicitLegacyReplacementAndRemovalSupersedeOnlyThisHost()=runBlocking {
        val auth=CodexDiscoveryAPI.authorize(host,EasyWorkIdentity.load(store),store)
        val vault=CodexConnectionVault(store);vault.save(client(auth),auth.token)
        store.setToken("other-host","other legacy key")
        store.setToken(host.id,"replacement manual or QR key")
        assertEquals("replacement manual or QR key",store.token(host.id));assertTrue(vault.clients().isEmpty())
        vault.save(client(auth),auth.token);store.setToken(host.id,"")
        assertTrue(store.token(host.id).isEmpty());assertTrue(vault.clients().isEmpty());assertEquals("other legacy key",store.token("other-host"))
    }
    @Test fun removedPrivateConnectionCannotRecoverFromVaultAfterOrdinaryMetadataLoss()=runBlocking {
        val auth=CodexDiscoveryAPI.authorize(host,EasyWorkIdentity.load(store),store)
        CodexConnectionVault(store).save(client(auth),auth.token)
        val session=withContext(Dispatchers.Main) { MobileSession(store).also { it.restore() } }
        withTimeout(10_000) { while(withContext(Dispatchers.Main) { session.restoring || !session.hasClient }) delay(30) }
        withContext(Dispatchers.Main) { session.removeClient(host.id) }
        assertTrue(CodexConnectionVault(store).clients().isEmpty());assertTrue(store.token(host.id).isEmpty());assertEquals(1,posts().size)
        withContext(Dispatchers.Main) { session.resetAcceptance() }
    }
    @Test fun atomicConnectionOverridesStaleMetadataAndRetainsLaterVerifiedRoutesAndUIState()=runBlocking {
        val auth=CodexDiscoveryAPI.authorize(host,EasyWorkIdentity.load(store),store);val verified=client(auth)
        val stale=verified.copy(name="原连接名称",readerId="original-reader",endpoint="https://127.0.0.1:1",codexDirect=verified.codexDirect!!.copy(routes=listOf(CodexRoute("https://127.0.0.1:1"))))
        val key=stale.cacheKey(null)
        val metadata=JSONObject().put("version",1).put("clients",org.json.JSONArray().put(encodeClient(stale))).put("selected",stale.id).put("appearance","light").put("demo",false)
            .put("drafts",JSONObject().put(key,"原加密草稿")).put("selectedThreads",JSONObject()).put("expanded",JSONObject()).put("workspaces",JSONObject())
        store.write("metadata",metadata.toString());CodexConnectionVault(store).save(verified,auth.token)
        val session=withContext(Dispatchers.Main) { MobileSession(store).also { it.restore() } }
        withTimeout(10_000) { while(withContext(Dispatchers.Main) { session.restoring || !session.hasClient })delay(30) }
        assertEquals(endpoint,withContext(Dispatchers.Main) { session.selectedClient.endpoint });assertEquals("原加密草稿",withContext(Dispatchers.Main) { session.text })
        assertEquals("原连接名称",withContext(Dispatchers.Main) { session.selectedClient.name });assertEquals("original-reader",withContext(Dispatchers.Main) { session.selectedClient.readerId })
        val alias=endpoint.replace("127.0.0.1","localhost");val descriptor=CodexDiscoveryAPI.discover(alias)
        withContext(Dispatchers.Main) { assertTrue(session.updateCodexRoute(session.selectedClient,alias,descriptor)) }
        assertEquals(alias,CodexConnectionVault(store).clients().single().endpoint);assertEquals(auth.token,store.token(host.id));assertEquals(1,posts().size)
        withContext(Dispatchers.Main) { session.resetAcceptance() }
    }
    @Test fun changedLocalTargetGuardStopsBeforeClaim()=runBlocking {
        try { CodexDiscoveryAPI.authorize(host,EasyWorkIdentity.load(store),store) { error("Target changed") };fail() } catch(_: Exception) {}
        assertTrue(posts().isEmpty());assertTrue(CodexConnectionVault(store).clients().isEmpty())
    }
    @Test fun lateIssuedCredentialCannotReplaceNewSelectionOrDraft()=runBlocking {
        mode("hold-claim")
        val session=withContext(Dispatchers.Main) { MobileSession(store).also { it.resetAcceptance();it.text="original draft" } }
        val result=async(Dispatchers.Main) { runCatching { session.connectCodexDiscovered(host) } }
        withTimeout(10_000) { while(posts().isEmpty())delay(30) }
        withContext(Dispatchers.Main) { session.newChat();session.text="newer draft" }
        assertTrue(result.await().isFailure);assertEquals(1,posts().size)
        assertTrue(CodexConnectionVault(store).clients().isEmpty());assertEquals("newer draft",withContext(Dispatchers.Main) { session.text })
        withContext(Dispatchers.Main) { session.resetAcceptance() }
    }
    @Test fun cancelledClaimNeverPublishesOrSchedulesAnotherPost()=runBlocking {
        mode("hold-claim")
        val result=launch { CodexDiscoveryAPI.authorize(host,EasyWorkIdentity.load(store),store) }
        withTimeout(10_000) { while(posts().isEmpty())delay(30) }
        result.cancelAndJoin();delay(500)
        assertEquals(1,posts().size);assertTrue(CodexConnectionVault(store).clients().isEmpty());assertTrue(store.token(host.id).isEmpty())
    }
    @Test fun easyWorkAutomaticClickUsesExistingBindingProtocolWithoutQRorAccountLogin()=runBlocking {
        mode("easywork-automatic");val identity=EasyWorkIdentity.load(store)
        val (binding,candidates)=EasyWorkPairing.discovered(endpoint,"UM04 phone",identity,"um04-easywork-desktop")
        binding.verify(identity);assertEquals("easy-runtime",binding.target.runtimeId);assertEquals(endpoint,candidates.first().endpoint)
        val post=posts().single();assertEquals("/connections",post.getString("path"));assertFalse(post.getBoolean("authorizationPresent"));assertEquals(setOf("mobileDeviceId","publicKey","name"),post.getJSONArray("fields").strings().toSet())
    }
    @Test fun easyWorkWrongDeviceCertificateOrLegacyHostIsRejectedBeforeBinding()=runBlocking {
        val identity=EasyWorkIdentity.load(store)
        for(value in listOf("easywork-automatic","easywork-bad-pin","easywork-legacy")) {
            mode(value);assertTrue(runCatching { EasyWorkPairing.discovered(endpoint,"UM04 phone",identity,if(value=="easywork-automatic")"wrong-device" else "um04-easywork-desktop") }.isFailure);assertTrue(posts().isEmpty())
        }
    }
    @Test fun easyWorkChangedTargetGuardAndInvalidReturnedBindingKeepOriginalState()=runBlocking {
        val identity=EasyWorkIdentity.load(store);store.setToken(host.id,"original credential");mode("easywork-automatic")
        assertTrue(runCatching { EasyWorkPairing.discovered(endpoint,"UM04 phone",identity,"um04-easywork-desktop") { error("selection changed") } }.isFailure);assertTrue(posts().isEmpty())
        mode("easywork-wrong-binding-target");assertTrue(runCatching { EasyWorkPairing.discovered(endpoint,"UM04 phone",identity,"um04-easywork-desktop") }.isFailure);assertEquals(1,posts().size);assertEquals("original credential",store.token(host.id));assertTrue(CodexConnectionVault(store).clients().isEmpty())
    }
    @Test fun easyWorkLostAutomaticBindingResponseNeverRetriesOtherRoutes()=runBlocking {
        mode("easywork-drop-claim-response");val identity=EasyWorkIdentity.load(store)
        assertTrue(runCatching { EasyWorkPairing.discovered(endpoint,"UM04 phone",identity,"um04-easywork-desktop") }.isFailure);delay(500);assertEquals(1,posts().size);assertEquals("/connections",posts().single().getString("path"))
    }

}
