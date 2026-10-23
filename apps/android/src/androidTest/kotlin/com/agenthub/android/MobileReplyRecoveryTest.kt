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
}
