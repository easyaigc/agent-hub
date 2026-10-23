package com.agenthub.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.agenthub.core.ReplyRecoveryRules
import com.agenthub.core.TargetScope
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

/** Actual Android crypto/journal/HTTP, with synthetic authority; no desktop or model. */
@RunWith(AndroidJUnit4::class)
class MobileReplyRecoveryTest {
    private val context=InstrumentationRegistry.getInstrumentation().targetContext
    private val scope=TargetScope("codex",null,null,"host","workspace","runtime")
    private fun client()=MobileClient("host","fixture",scope,"https://127.0.0.1:1",true,"desktop-runtime",false,
        codexDirect=PairedCodexConnection(scope,"a".repeat(64),listOf(CodexRoute("https://127.0.0.1:1","lan"))))
    private fun reply(id: String="operation")=ReplyIntent("host","thread",id,"中文 😀",emptyList(),"next-turn")
    private fun storage(namespace: String)=NativeSessionStorage(context,namespace)
    private fun freshNamespace()="reply-recovery-"+UUID.randomUUID()
    private fun proof(record: SavedOperation)=JSONObject().put("version",2).put("hostId","host").put("threadId","thread")
        .put("clientMessageId",record.id).put("target",JSONObject(record.replyRecovery!!.capturedTarget))
        .put("capturedTarget",JSONObject(record.replyRecovery.capturedTarget)).put("status","not-accepted")
        .put("observedAt",Instant.now().toString()).put("fingerprintVersion",2).put("fingerprint",record.replyRecovery.fingerprint)
        .put("receipt",JSONObject.NULL).put("evidence",JSONObject().put("kind","operator-fenced-checkpoint-v2")
            .put("fingerprint",record.replyRecovery.fingerprint).put("oldPid",100).put("newPid",200)
            .put("ledgerSha256","b".repeat(64)).put("fenceSha256","c".repeat(64)).put("ledgerId",UUID.randomUUID().toString()))
    private suspend fun uncertain(journal: NativeOperationJournal): SavedOperation {
        val record=journal.prepare(client(),"thread","reply",reply().body())
        journal.markSubmitting(record.target,record.id)
        return journal.uncertain(record.target,record.id,"synthetic lost response")
    }
    private suspend fun fails(action: suspend ()->Unit) { assertTrue(runCatching { action() }.isFailure) }

    @Test fun androidCryptoMatchesActualHostOrderedPayloadVectors() {
        val vectors=JSONObject(InstrumentationRegistry.getInstrumentation().context.assets.open("reply-recovery-v2.json").bufferedReader().use { it.readText() })
        vectors.getJSONArray("fingerprints").objects().forEach { test ->
            val payload=ReplyRecoveryRules.canonicalPayload(test.getJSONObject("target").toString(),test.getJSONObject("reply").toString())!!
            assertEquals(test.getString("payload"),payload)
            assertEquals(test.getString("fingerprint"),MessageDigest.getInstance("SHA-256").digest(payload.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) })
        }
    }
    @Test fun unknownCannotBecomeRetryableFromReceiptAbsence()=runBlocking {
        val journal=NativeOperationJournal(storage(freshNamespace()));val record=uncertain(journal)
        assertFalse(record.replyRecovery!!.mayRetry(client(),record))
        fails { journal.consumeReplyRetry(client(),record.target,record.id) }
        val bad=proof(record).put("evidence",JSONObject.NULL)
        fails { journal.observeReplyInspection(client(),record.target,record.id,bad) }
        assertTrue(journal.records(record.target).single().submissionAttempted)
    }
    @Test fun oneRetryIsDurablyConsumedAcrossJournalInstances()=runBlocking {
        val namespace=freshNamespace();val first=NativeOperationJournal(storage(namespace));val second=NativeOperationJournal(storage(namespace))
        val original=uncertain(first);val authorized=first.observeReplyInspection(client(),original.target,original.id,proof(original))
        assertTrue(authorized.replyRecovery!!.mayRetry(client(),authorized))
        val start=CompletableDeferred<Unit>()
        val attempts=(0..<16).map { index -> async(Dispatchers.IO) { start.await();runCatching { (if(index%2==0)first else second).consumeReplyRetry(client(),original.target,original.id) }.isSuccess } }
        start.complete(Unit)
        assertEquals(1,attempts.awaitAll().count { it })
        val restored=NativeOperationJournal(storage(namespace)).records(original.target).single()
        assertEquals(original.intent,restored.intent);assertEquals(original.intentSha256,restored.intentSha256)
        assertTrue(restored.submissionAttempted);assertTrue(restored.replyRecovery!!.consumed)
        assertFalse(restored.replyRecovery.mayRetry(client(),restored))
    }
    @Test fun staleProofAndChangedTrustAreNeverRetryAuthority()=runBlocking {
        val journal=NativeOperationJournal(storage(freshNamespace()));val original=uncertain(journal)
        val bad=proof(original).put("observedAt",Instant.now().minusSeconds(61).toString())
        fails { journal.observeReplyInspection(client(),original.target,original.id,bad) }
        val accepted=journal.observeReplyInspection(client(),original.target,original.id,proof(original))
        val recovery=accepted.replyRecovery!!
        assertFalse(recovery.mayRetry(client().copy(codexDirect=client().codexDirect!!.copy(fingerprint="d".repeat(64))),accepted))
        assertFalse(recovery.mayRetry(client().copy(scope=TargetScope("codex",null,null,"host","workspace","other")),accepted))
        assertFalse(recovery.matches(client(),accepted.copy(intent=reply().copy(text="changed").body().toString())))
        assertFalse(recovery.mayRetry(client(),accepted,System.currentTimeMillis()+61_000))
    }
    @Test fun oldRowsNeverAcquireCapturedContextFromCurrentPairing()=runBlocking {
        val journal=NativeOperationJournal(storage(freshNamespace()));val old=journal.prepare(client().copy(codexDirect=null),"thread","reply",reply().body())
        assertNull(old.replyRecovery)
        val repeated=journal.prepare(client(),"thread","reply",reply().body())
        assertNull(repeated.replyRecovery)
        fails { journal.consumeReplyRetry(client(),old.target,old.id) }
    }
    @Test fun positiveReceiptCannotBeDowngradedByNegativeInspection()=runBlocking {
        val journal=NativeOperationJournal(storage(freshNamespace()));val original=uncertain(journal)
        val receipt=JSONObject().put("hostId","host").put("threadId","thread").put("clientMessageId",original.id)
            .put("state","completed").put("turnId","turn").put("error",JSONObject.NULL)
        val completed=journal.observed(original.target,original.id,"completed",receipt)
        fails { journal.observeReplyInspection(client(),original.target,original.id,proof(original)) }
        assertFalse(completed.replyRecovery!!.mayRetry(client(),completed))
        assertEquals("completed",journal.records(original.target).single().phase)
    }
    @Test fun namedInspectionIsGetAndExactTargetBound()=runBlocking {
        val journal=NativeOperationJournal(storage(freshNamespace()));val original=uncertain(journal)
        CommandFixture { WireResponse(body=proof(original).toString()) }.use { server ->
            val transportClient=client().copy(codexDirect=null,endpoint=server.endpoint)
            val native=NativeCodexCommands { "synthetic-only" }
            val result=AgentRegistry(NativeHostReads { "synthetic-only" },native).replyDeliveryStatus(transportClient,"thread",original.id)
            assertEquals("not-accepted",result.getString("status"))
            assertEquals(1,server.requests.size);assertEquals("GET",server.requests.single().method)
            assertEquals("/v1/replies/status",server.requests.single().path)
        }
    }
    @Test fun missingReceiptFieldIsRejectedByNativeHttpCodec()=runBlocking {
        val journal=NativeOperationJournal(storage(freshNamespace()));val original=uncertain(journal)
        CommandFixture { WireResponse(body=proof(original).apply { remove("receipt") }.toString()) }.use { server ->
            fails { NativeCodexCommands { "synthetic-only" }.replyDeliveryStatus(client().copy(codexDirect=null,endpoint=server.endpoint),"thread",original.id) }
            assertEquals(1,server.requests.size)
            assertFalse(journal.records(original.target).single().replyRecovery!!.mayRetry(client(),original))
        }
    }
    @Test fun firstInputRetryRequiresAuthoritativePreparedDiagnostic()=runBlocking {
        val namespace=freshNamespace();val journal=NativeOperationJournal(storage(namespace))
        val intent=CreationIntent("host","creation",null,"first",emptyList())
        val original=journal.prepare(client(),null,"creation",intent.body());journal.markPreparing(original.target,original.id)
        val initial=DesktopCreation("host","creation",null,"prepared",null,null,emptyList(),null,false)
        journal.observed(original.target,original.id,"prepared",encodeCreation(initial));journal.markSubmitting(original.target,original.id)
        val ambiguous=journal.observed(original.target,original.id,"prepared",encodeCreation(initial))
        fails { journal.markCreationRetrySubmitting(ambiguous.target,ambiguous.id) }
        val safe=initial.copy(phase="outcome-unknown",preparation=CreationPreparation("prepared-thread","input-retryable",CreationDiagnostic("generation","generation-preflight-failed","retry-original-preflight")))
        journal.observed(original.target,original.id,"outcome-unknown",encodeCreation(safe))
        val restored=NativeOperationJournal(storage(namespace))
        assertEquals("retry-original-preflight",CodexCommandCodec.creation(JSONObject(restored.records(original.target).single().result!!)).diagnostic!!.disposition)
        val submitted=restored.markCreationRetrySubmitting(original.target,original.id)
        assertTrue(submitted.submissionAttempted);assertEquals(original.intent,submitted.intent)
        fails { journal.markCreationRetrySubmitting(original.target,original.id) }
    }
    @Test fun invalidatedAndDetachedAuthoritiesCannotSubmit()=runBlocking {
        val journal=NativeOperationJournal(storage(freshNamespace()));val original=uncertain(journal)
        journal.observeReplyInspection(client(),original.target,original.id,proof(original))
        val invalidated=journal.invalidateReplyInspection(original.target,original.id)
        assertNull(invalidated.replyRecovery!!.inspection);assertFalse(invalidated.replyRecovery.mayRetry(client(),invalidated))
        journal.observeReplyInspection(client(),original.target,original.id,proof(original))
        val detached=journal.detach(original.target,original.id)
        assertFalse(detached.replyRecovery!!.mayRetry(client(),detached))
        fails { journal.consumeReplyRetry(client(),original.target,original.id) }
    }
    private suspend fun priorUnknownStatusFixture(accepted: Boolean,consumed: Boolean=false,detached: Boolean=false,block: suspend (NativeCodexFlow,SavedOperation,CommandFixture)->Unit) {
        val store=storage(freshNamespace());val journal=NativeOperationJournal(store);var initial=uncertain(journal)
        if(consumed) { journal.observeReplyInspection(client(),initial.target,initial.id,proof(initial));initial=journal.consumeReplyRetry(client(),initial.target,initial.id) }
        val raw=JSONObject().put("hostId","host").put("threadId","thread").put("clientMessageId",initial.id).put("state","uncertain").put("turnId",JSONObject.NULL).put("error",JSONObject.NULL)
        val observed=journal.observed(initial.target,initial.id,"uncertain",raw)
        val original=if(detached)journal.detach(observed.target,observed.id) else observed
        val value=if(!accepted)proof(original) else JSONObject().put("version",2).put("hostId","host").put("threadId","thread").put("clientMessageId",original.id)
            .put("target",JSONObject(original.replyRecovery!!.capturedTarget)).put("capturedTarget",JSONObject(original.replyRecovery.capturedTarget))
            .put("status","accepted").put("observedAt",Instant.now().toString()).put("evidence",JSONObject.NULL).put("fingerprintVersion",JSONObject.NULL).put("fingerprint",JSONObject.NULL)
            .put("receipt",JSONObject(raw.toString()).put("state","submitted").put("turnId","original-accepted-run"))
        CommandFixture { WireResponse(body=value.toString()) }.use { server ->
            val http=NativeCodexCommands { "synthetic-only" }
            val commands=object: CodexCommands by http {
                override suspend fun receipt(client: MobileClient,threadId: String,operationId: String): DeliveryReceipt { assertEquals(original.id,operationId);throw CodexCommandError("Original primary receipt absent",404) }
                override suspend fun replyDeliveryStatus(client: MobileClient,threadId: String,operationId: String): JSONObject {
                    assertEquals("codex:"+client.codexDirect!!.fingerprint.lowercase(),original.replyRecovery!!.trust)
                    // Only the examiner transport is loopback HTTP; captured domain authority remains original. No TLS/desktop proof.
                    return http.replyDeliveryStatus(client.copy(codexDirect=null,endpoint=server.endpoint),threadId,operationId)
                }
            }
            block(NativeCodexFlow(store,NativeHostReads { "" },commands),original,server)
        }
    }
    @Test fun exactAcceptedCanonicalFallbackRecoversAfterAnEarlierUncertainReceipt()=runBlocking {
        priorUnknownStatusFixture(true) { flow,original,server ->
            val observed=flow.query(client(),original).record
            assertEquals("submitted",observed.phase);assertEquals("original-accepted-run",JSONObject(observed.result!!).getString("turnId"))
            assertEquals(original.id,observed.id);assertEquals(original.intentSha256,observed.intentSha256)
            assertFalse(observed.replyRecovery!!.mayRetry(client(),observed))
            assertEquals(1,server.requests.size);assertEquals("GET",server.requests.single().method);assertEquals("/v1/replies/status",server.requests.single().path)
        }
    }
    @Test fun priorUncertainReceiptDoesNotGainNegativeRetryAuthorityFromStatusInspection()=runBlocking {
        priorUnknownStatusFixture(false) { flow,original,server ->
            fails { flow.query(client(),original) }
            val current=flow.journal.records(original.target).single()
            assertEquals("uncertain",current.phase);assertFalse(current.replyRecovery!!.mayRetry(client(),current))
            assertEquals(original.intentSha256,current.intentSha256);assertTrue(server.requests.all { it.method=="GET" })
        }
    }

    @Test fun consumedOriginalRetryStillReadsAcceptedEvidenceWithoutAnotherWrite()=runBlocking {
        priorUnknownStatusFixture(true,consumed=true) { flow,original,server ->
            val observed=flow.query(client(),original).record
            assertEquals("submitted",observed.phase);assertTrue(observed.replyRecovery!!.consumed)
            assertFalse(observed.replyRecovery.mayRetry(client(),observed));assertEquals(original.intentSha256,observed.intentSha256)
            assertEquals(1,server.requests.size);assertTrue(server.requests.all { it.method=="GET" })
        }
    }

    @Test fun detachedOriginalReadsCanonicalAcceptedEvidenceWithoutRestoringWriteAuthority()=runBlocking {
        priorUnknownStatusFixture(true,detached=true) { flow,original,server ->
            val observed=flow.query(client(),original).record
            assertEquals("submitted",observed.phase);assertTrue(observed.detached)
            assertFalse(observed.replyRecovery!!.mayRetry(client(),observed));assertEquals(original.intentSha256,observed.intentSha256)
            assertEquals(1,server.requests.size);assertTrue(server.requests.all { it.method=="GET" })
        }
    }
    @Test fun detachedOriginalQueriesCanonicalStateWithoutAcquiringNegativeRetryAuthority()=runBlocking {
        priorUnknownStatusFixture(false,detached=true) { flow,original,server ->
            fails { flow.query(client(),original) }
            val observed=flow.journal.records(original.target).single()
            assertTrue(observed.detached);assertFalse(observed.replyRecovery!!.mayRetry(client(),observed))
            assertEquals(original.intentSha256,observed.intentSha256)
            assertEquals(1,server.requests.size);assertTrue(server.requests.all { it.method=="GET" })
        }
    }

    @Test fun detachedReadContextStillRejectsChangedTrustTargetAndIntent()=runBlocking {
        val journal=NativeOperationJournal(storage(freshNamespace()));val original=uncertain(journal)
        val detached=journal.detach(original.target,original.id);val recovery=detached.replyRecovery!!
        assertTrue(recovery.matchesObservation(client(),detached));assertFalse(recovery.matches(client(),detached))
        assertFalse(recovery.matchesObservation(client().copy(codexDirect=client().codexDirect!!.copy(fingerprint="b".repeat(64))),detached))
        assertFalse(recovery.matchesObservation(client().copy(scope=TargetScope("codex",null,null,"host","workspace","other-runtime")),detached))
        val changed=JSONObject(detached.intent).put("text","another original").toString()
        assertFalse(recovery.matchesObservation(client(),detached.copy(intent=changed)))
        assertFalse(recovery.matchesObservation(client(),detached.copy(target="another-target")))
        fails { recovery.capturedClient(client(),detached) }
        assertEquals(detached.replyRecovery!!.capturedTarget,CodexCommandCodec.target(recovery.capturedObservationClient(client(),detached).scope!!).toString())
    }


    private fun unavailable(record: SavedOperation)=JSONObject().put("version",2).put("hostId","host").put("threadId","thread")
        .put("clientMessageId",record.id).put("target",JSONObject(record.replyRecovery!!.capturedTarget))
        .put("status","unavailable").put("observedAt",Instant.now().toString())
        .put("evidence",JSONObject.NULL).put("capturedTarget",JSONObject.NULL)
        .put("fingerprint",JSONObject.NULL).put("fingerprintVersion",JSONObject.NULL).put("receipt",JSONObject.NULL)

    @Test fun coldUnavailableReadKeepsOriginalAndRevokesOldAbsenceProofWithoutWriting()=runBlocking {
        val namespace=freshNamespace();val store=storage(namespace);val journal=NativeOperationJournal(store)
        val original=uncertain(journal)
        val prior=journal.observeReplyInspection(client(),original.target,original.id,proof(original))
        assertTrue(prior.replyRecovery!!.mayRetry(client(),prior))
        val restored=NativeOperationJournal(storage(namespace)).records(original.target).single()
        assertEquals(prior.intentSha256,restored.intentSha256)
        val inspection=unavailable(restored)
        CommandFixture { WireResponse(body=inspection.toString()) }.use { server ->
            val native=NativeCodexCommands { "synthetic-only" }
            val commands=object: CodexCommands by native {
                override suspend fun receipt(client: MobileClient,threadId: String,operationId: String): DeliveryReceipt { assertEquals(restored.id,operationId);throw CodexCommandError("Exact original receipt unavailable",404) }
                override suspend fun replyDeliveryStatus(client: MobileClient,threadId: String,operationId: String)=native.replyDeliveryStatus(client.copy(codexDirect=null,endpoint=server.endpoint),threadId,operationId)
            }
            val flow=NativeCodexFlow(storage(namespace),NativeHostReads { "" },commands)
            fails { flow.query(client(),restored) }
            val now=NativeOperationJournal(storage(namespace)).records(original.target).single()
            assertEquals(restored.id,now.id);assertEquals(restored.target,now.target);assertEquals(restored.intent,now.intent);assertEquals(restored.intentSha256,now.intentSha256)
            assertEquals(restored.replyRecovery!!.fingerprint,now.replyRecovery!!.fingerprint)
            assertTrue(now.submissionAttempted);assertNull(now.result);assertNull(now.replyRecovery.inspection)
            assertEquals("uncertain",now.replyRecovery.phase);assertFalse(now.replyRecovery.mayRetry(client(),now))
            fails { flow.retryReply(client(),now,{ true },{}) }
            for(key in listOf("evidence","capturedTarget","fingerprint","fingerprintVersion","receipt"))assertTrue(inspection.isNull(key))
            assertEquals(1,server.requests.size);assertEquals("GET",server.requests.single().method);assertEquals("/v1/replies/status",server.requests.single().path)
        }
    }

    @Test fun unavailableWithAReceiptCannotManufactureAcceptanceOrRetry()=runBlocking {
        val store=storage(freshNamespace());val journal=NativeOperationJournal(store);val original=uncertain(journal)
        val bad=unavailable(original).put("receipt",JSONObject().put("hostId","host").put("threadId","thread").put("clientMessageId",original.id)
            .put("state","completed").put("turnId","invented-turn").put("error",JSONObject.NULL))
        val http=NativeCodexCommands { "synthetic-only" }
        CommandFixture { WireResponse(body=bad.toString()) }.use { server ->
            val commands=object: CodexCommands by http {
                override suspend fun receipt(client: MobileClient,threadId: String,operationId: String): DeliveryReceipt { throw CodexCommandError("Exact original receipt unavailable",404) }
                override suspend fun replyDeliveryStatus(client: MobileClient,threadId: String,operationId: String)=http.replyDeliveryStatus(client.copy(codexDirect=null,endpoint=server.endpoint),threadId,operationId)
            }
            val flow=NativeCodexFlow(store,NativeHostReads { "" },commands);fails { flow.query(client(),original) }
            val current=journal.records(original.target).single()
            assertNull(current.result);assertNull(current.replyRecovery!!.inspection);assertFalse(current.replyRecovery.mayRetry(client(),current))
            assertEquals(original.intentSha256,current.intentSha256);assertEquals(original.id,current.id)
            assertEquals(1,server.requests.size);assertTrue(server.requests.all { it.method=="GET" })
        }
    }

    @Test fun coldAcceptedCanonicalReceiptRemainsReadableWithoutRuntimeOrHistoryAndNeverReplays()=runBlocking {
        val namespace=freshNamespace();val journal=NativeOperationJournal(storage(namespace));val original=uncertain(journal)
        val receipt=JSONObject().put("hostId","host").put("threadId","thread").put("clientMessageId",original.id)
            .put("state","completed").put("turnId","accepted-before-cold").put("error",JSONObject.NULL)
        val positive=unavailable(original).put("status","accepted").put("receipt",receipt)
        val restored=NativeOperationJournal(storage(namespace)).records(original.target).single()
        CommandFixture { WireResponse(body=positive.toString()) }.use { server ->
            val http=NativeCodexCommands { "synthetic-only" }
            val commands=object: CodexCommands by http {
                override suspend fun receipt(client: MobileClient,threadId: String,operationId: String): DeliveryReceipt { assertEquals(original.id,operationId);throw CodexCommandError("Primary receipt absent",404) }
                override suspend fun replyDeliveryStatus(client: MobileClient,threadId: String,operationId: String)=http.replyDeliveryStatus(client.copy(codexDirect=null,endpoint=server.endpoint),threadId,operationId)
            }
            // Only the synthetic read transport is available; no workspace/history request can succeed.
            val offline=client().copy(online=false);val flow=NativeCodexFlow(storage(namespace),NativeHostReads { "" },commands)
            val observed=flow.query(offline,restored).record
            assertEquals("completed",observed.phase);assertEquals("accepted-before-cold",JSONObject(observed.result!!).getString("turnId"))
            assertEquals(restored.id,observed.id);assertEquals(restored.intentSha256,observed.intentSha256)
            assertFalse(observed.replyRecovery!!.mayRetry(offline,observed));fails { flow.retryReply(offline,observed,{ true },{}) }
            val coldAgain=NativeOperationJournal(storage(namespace)).records(original.target).single();assertEquals(observed.result,coldAgain.result)
            assertEquals(1,server.requests.size);assertTrue(server.requests.all { it.method=="GET"&&it.path=="/v1/replies/status" })
        }
    }

    @Test fun readOnlyReplySnapshotRejectsChangedBodyAndTargetWithoutGrantingRetry()=runBlocking {
        val store=storage(freshNamespace());val flow=NativeCodexFlow(store,NativeHostReads { "" },NativeCodexCommands { "" })
        val bytes="original retained bytes".toByteArray();val file=NativeDraftFiles(store).import("original.txt","text/plain",bytes)
        val input=ReplyIntent("host","thread","operation",reply().text,listOf("uploaded-original-id"),"next-turn")
        val prepared=flow.journal.prepare(client(),"thread","reply",input.body(),"source-key","source-version")
        flow.journal.markSubmitting(prepared.target,prepared.id);val pending=flow.journal.uncertain(prepared.target,prepared.id,"Unknown original")
        val source=JSONObject().put("hostId","host").put("target",CodexCommandCodec.target(scope)).put("sourceKey","source-key").put("sourceVersion","source-version")
            .put("text",reply().text).put("files",JSONArray().put(file.manifest.body()))
        val saved=source.toString();store.write("submission:${pending.id}",saved)
        val allowed=flow.journal.observeReplyInspection(client(),pending.target,pending.id,proof(pending))
        assertEquals(file.manifest.id,flow.originalReplyContent(client(),allowed).files.single().manifest.id)
        // A display may retain names when bytes are unavailable; editable restoration still checks exact original bytes.
        store.write("draft-file:${file.manifest.id}",JSONObject().put("file",file.manifest.body()).put("bytes",android.util.Base64.encodeToString("changed".toByteArray(),android.util.Base64.NO_WRAP)).toString())
        fails { flow.originalReplyContent(client(),allowed) }
        val original=flow.journal.detach(prepared.target,prepared.id)
        val display=flow.replyRecordContent(client(),original);assertEquals(reply().text,display.text);assertEquals(file.manifest.name,display.files.single().manifest.name)
        assertFalse(original.replyRecovery!!.mayRetry(client(),original));fails { flow.originalReplyContent(client(),original) }
        store.write("submission:${original.id}",JSONObject(saved).put("text","different original body").toString())
        fails { flow.replyRecordContent(client(),original) }
        store.write("submission:${original.id}",JSONObject(saved).put("target",CodexCommandCodec.target(TargetScope("codex",null,null,"host","workspace","other-runtime"))).toString())
        fails { flow.replyRecordContent(client(),original) }
        assertEquals(original.intentSha256,flow.journal.records(original.target).single().intentSha256)
    }


}
