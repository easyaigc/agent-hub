package com.agenthub.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.agenthub.core.*
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class MobileMachineIdentityTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private fun scope(account: String?="old", agent: String="codex", workspace: String="fixture-workspace") =
        TargetScope(agent,account?.let { "https://accounts.invalid" },account,"fixture-host",workspace,"fixture-runtime")
    private fun client(target: TargetScope=scope())=MobileClient("fixture-host","fixture",target,"http://127.0.0.1:1",true,"desktop-runtime",false,readerId="preserved-reader",requiresReadTarget=true)
    private fun storage()=NativeSessionStorage(context,"machine-identity-"+UUID.randomUUID())

    @Test fun dualRoutesPreserveCredentialDraftAndUnknownOperationWithSeparateTlsTrust() = runBlocking {
        val store=storage();val serve="https://office.tail.ts.net:8443";val lan="https://192.168.1.20:8796"
        val direct=PairedCodexConnection(scope(),"a".repeat(64),listOf(CodexRoute(lan,"lan")),listOf(serve))
        val original=client().copy(endpoint=serve,codexDirect=direct)
        val old=encodeClient(original).apply { getJSONObject("codexDirect").remove("systemEndpoints") }
        assertTrue(decodeClient(old).codexDirect!!.systemEndpoints.isEmpty())
        val token="K".repeat(43);val vault=CodexConnectionVault(store);vault.save(original,token)
        store.write("dual-draft-sentinel","未发送的原草稿 😀")
        val journal=NativeOperationJournal(store)
        val intent=ReplyIntent(original.id,"fixture-thread",UUID.randomUUID().toString(),"immutable input",emptyList(),"next-turn")
        val record=journal.prepare(original,intent.threadId,"reply",intent.body(),original.cacheKey(intent.threadId),"original-draft-version")
        journal.markSubmitting(record.target,record.id);journal.uncertain(record.target,record.id,"unknown")
        val before=store.read("operations:"+record.target)
        for(endpoint in listOf(lan,serve)) {
            val switched=original.copy(endpoint=endpoint);vault.updateClient(switched)
            val restored=vault.clients().single()
            assertEquals(endpoint,restored.endpoint);assertEquals(listOf(serve),restored.codexDirect!!.systemEndpoints)
            assertEquals(original.cacheKey(intent.threadId),restored.cacheKey(intent.threadId));assertEquals(token,vault.token(restored.id))
            assertEquals("未发送的原草稿 😀",store.read("dual-draft-sentinel"));assertEquals(before,store.read("operations:"+record.target))
            assertTrue(runCatching { journal.markSubmitting(record.target,record.id) }.isFailure)
        }
        val connection=java.net.URI(serve).toURL().openConnection() as javax.net.ssl.HttpsURLConnection
        val factory=connection.sslSocketFactory;val verifier=connection.hostnameVerifier
        PairedCodexTLS.configure(connection,serve,direct)
        assertSame(factory,connection.sslSocketFactory);assertSame(verifier,connection.hostnameVerifier)
        assertTrue(runCatching { PairedCodexTLS.configure(connection,"https://other.tail.ts.net",direct) }.isFailure)
        val pinned=java.net.URI(lan).toURL().openConnection() as javax.net.ssl.HttpsURLConnection
        val systemFactory=pinned.sslSocketFactory;PairedCodexTLS.configure(pinned,lan,direct)
        assertNotSame(systemFactory,pinned.sslSocketFactory)
    }

    @Test fun preUpgradeClientKeepsOldNamespaceAcrossCodexAccountChange() {
        val original=client();val wire=encodeClient(original).apply { remove("storageIdentity") }
        val upgraded=decodeClient(wire)
        val old=MobileRules.cacheKey(original.id,original.scope,"fixture-thread",null)
        assertEquals(old,upgraded.cacheKey("fixture-thread"))
        val changed=upgraded.copy(scope=scope(null))
        assertTrue(sameScope(changed.scope,original.scope))
        assertEquals(old,changed.cacheKey("fixture-thread"))
        val restored=decodeClient(encodeClient(changed))
        assertEquals(old,restored.cacheKey("fixture-thread"))
        assertEquals(original.readerId,restored.readerId)
        assertFalse(sameScope(original.scope,scope(workspace="different")))
    }

    @Test fun unknownJournalKeepsCapturedBytesAndNeverPermitsAnotherSubmission() = runBlocking {
        val storage=storage();val raw=client()
        val legacy=decodeClient(encodeClient(raw).apply { remove("storageIdentity") })
        val journal=NativeOperationJournal(storage)
        val intent=ReplyIntent(raw.id,"fixture-thread",UUID.randomUUID().toString(),"原始正文 😀",emptyList(),"next-turn")
        val record=journal.prepare(legacy,intent.threadId,"reply",intent.body(),legacy.cacheKey(intent.threadId),"original-draft-version")
        journal.markSubmitting(record.target,record.id);journal.uncertain(record.target,record.id,"unknown")
        val bytes=storage.read("operations:"+record.target)
        val changed=legacy.copy(scope=scope(null))
        assertEquals(record.target,NativeOperationJournal.target(changed,intent.threadId))
        val restored=NativeOperationJournal(storage).records(record.target).single()
        assertEquals(record.intent,restored.intent);assertEquals(record.intentSha256,restored.intentSha256)
        assertEquals(record.sourceKey,restored.sourceKey);assertTrue(restored.submissionAttempted)
        assertTrue(runCatching { journal.markSubmitting(record.target,record.id) }.isFailure)
        assertTrue(runCatching { journal.prepare(changed,intent.threadId,"reply",intent.copy(text="changed").body()) }.isFailure)
        assertEquals(bytes,storage.read("operations:"+record.target))
    }

    @Test fun ambiguousLegacyDraftsNeedSelectionAndNeverOverwriteEachOther() {
        val a=MobileRules.cacheKey("fixture-host",scope(),"thread",null)
        val b=MobileRules.cacheKey("fixture-host",scope("new"),"thread",null)
        val foreign=MobileRules.cacheKey("fixture-host",scope(workspace="foreign"),"thread",null)
        val index=MobileStorageIdentity(null,listOf(a,b,foreign))
        val c=client(scope(null)).copy(storageIdentity=index)
        assertTrue(index.ambiguous(c,"thread",null))
        assertEquals(setOf(a,b),index.choices(c,"thread",null).toSet())
        val chosen=c.copy(storageIdentity=index.selecting(c,"thread",null,a))
        assertEquals(a,chosen.cacheKey("thread"))
        assertFalse(chosen.storageIdentity!!.ambiguous(chosen,"thread",null))
        val restored=decodeClient(encodeClient(chosen));assertEquals(a,restored.cacheKey("thread"))
        assertEquals(setOf(a,b),restored.storageIdentity!!.choices(restored,"thread",null).toSet())
        assertTrue(runCatching { index.selecting(c,"thread",null,foreign) }.isFailure)
    }

    @Test fun encryptedHistoryReusesOriginalAuthenticatedFragmentsAfterAccountChange() {
        val old=client();val current=decodeClient(encodeClient(old).apply { remove("storageIdentity") }).copy(scope=scope(null))
        val key=MobileRules.cacheKey(old.id,old.scope,"fixture-thread",null)
        val wire=JSONObject().put("hostId",old.id).put("threadId","fixture-thread").put("readTarget",encodeTarget(old.scope!!))
            .put("messages",JSONArray().put(JSONObject().put("id","original-answer").put("role","assistant").put("text","原历史未改写")))
        val history=HostReadCodec.history(wire.toString());val store=storage();val cache=NativeHistoryCache(store)
        cache.save(key,old,"fixture-thread",history,history.messages)
        val header=store.read("history:"+key)
        assertEquals(key,current.cacheKey("fixture-thread"))
        val restored=cache.read(current.cacheKey("fixture-thread"))!!
        assertEquals(history.messages,restored.messages);assertTrue(sameScope(restored.scope,current.scope))
        assertEquals(header,store.read("history:"+key))
    }

    @Test fun easyWorkAccountAndRuntimeIsolationRemainStrict() {
        val a=client(scope(agent="easywork"));val b=client(scope("other",agent="easywork"))
        assertFalse(sameScope(a.scope,b.scope));assertNotEquals(a.cacheKey("thread"),b.cacheKey("thread"))
        val machine=client(scope(null));assertFalse(sameScope(machine.scope,a.scope))
        assertNotEquals(machine.cacheKey("thread"),client(scope(null,workspace="other")).cacheKey("thread"))
        assertNotEquals(machine.cacheKey("thread"),machine.cacheKey(null,"thread"))
    }
}
