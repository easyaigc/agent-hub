package com.agenthub.android

import androidx.test.platform.app.InstrumentationRegistry
import com.agenthub.core.TargetScope
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Original write disposition survives a late preparation receipt; only synthetic native storage/HTTP. */
class MobileCreationReceiptOrderTest {
    private val context=InstrumentationRegistry.getInstrumentation().targetContext
    private fun storage(namespace: String)=NativeSessionStorage(context,namespace)
    private fun client(endpoint: String="http://127.0.0.1:1")=MobileClient("fixture-host","Creation receipt order",
        TargetScope("codex",null,null,"fixture-host","fixture-workspace","fixture-runtime"),endpoint,true,"desktop-runtime",false,requiresReadTarget=true)
    private fun receipt(record: SavedOperation,phase: String)=JSONObject().put("hostId","fixture-host").put("clientOperationId",record.id)
        .put("projectId",JSONObject.NULL).put("phase",phase).put("threadId",if(phase=="confirmed")"created-thread" else JSONObject.NULL)
        .put("error",if(phase=="outcome-unknown")"Original outcome unknown" else JSONObject.NULL).put("uploaded",JSONArray())
    private suspend fun prepared(journal: NativeOperationJournal,client: MobileClient=client()): SavedOperation {
        val intent=CreationIntent(client.id,UUID.randomUUID().toString(),null,"Synthetic original first input",emptyList())
        val r=journal.prepare(client,null,"creation",intent.body(),"synthetic-source","synthetic-version")
        journal.markPreparing(r.target,r.id);return journal.observed(r.target,r.id,"prepared",receipt(r,"prepared"))
    }
    @Test fun latePreparedPreservesEveryAttemptedNonterminalStateAcrossEncryptedReopen()=runBlocking {
        for(phase in listOf("submitting","uncertain","outcome-unknown")) {
            val namespace="creation-order-"+UUID.randomUUID();val j=NativeOperationJournal(storage(namespace));val p=prepared(j)
            val submitted=j.markSubmitting(p.target,p.id)
            val original=when(phase){"uncertain"->j.uncertain(p.target,p.id,"Original transport unknown");"outcome-unknown"->j.observed(p.target,p.id,phase,receipt(p,phase));else->submitted}
            val late=j.observed(p.target,p.id,"prepared",receipt(p,"prepared"))
            assertEquals("Late prepared must not retire the original observer",original,late)
            assertEquals(original,NativeOperationJournal(storage(namespace)).records(p.target).single());assertTrue(late.submissionAttempted)
            assertTrue(runCatching { j.markSubmitting(p.target,p.id) }.isFailure)
        }
    }
    @Test fun preparationBeforeAnyAttemptStillAllowsExactlyOneFirstSubmission()=runBlocking {
        val j=NativeOperationJournal(storage("creation-unattempted-"+UUID.randomUUID()));val p=prepared(j)
        val observed=j.observed(p.target,p.id,"prepared",receipt(p,"prepared"));assertEquals("prepared",observed.phase);assertFalse(observed.submissionAttempted)
        assertTrue(j.markSubmitting(p.target,p.id).submissionAttempted);assertTrue(runCatching { j.markSubmitting(p.target,p.id) }.isFailure)
    }
    @Test fun latePreparedCannotEraseDetachedOriginalUnknownOrConfirmedResult()=runBlocking {
        for(phase in listOf("outcome-unknown","confirmed")) {
            val j=NativeOperationJournal(storage("creation-detached-order-"+UUID.randomUUID()));val p=prepared(j);j.markSubmitting(p.target,p.id)
            j.observed(p.target,p.id,phase,receipt(p,phase));val original=j.detach(p.target,p.id)
            assertEquals(original,j.observed(p.target,p.id,"prepared",receipt(p,"prepared")))
        }
    }
    @Test fun stalePreparationStillValidatesOriginalHostOperationAndProject()=runBlocking {
        val j=NativeOperationJournal(storage("creation-invalid-order-"+UUID.randomUUID()));val p=prepared(j);val original=j.markSubmitting(p.target,p.id)
        for((key,value)in listOf("hostId" to "wrong-host","clientOperationId" to "wrong-operation","projectId" to "wrong-project"))
            assertTrue(runCatching { j.observed(p.target,p.id,"prepared",receipt(p,"prepared").put(key,value)) }.isFailure)
        assertEquals(original,j.records(p.target).single())
    }
    @Test fun laterAuthoritativeUnknownAndConfirmedOutcomesRemainObservable()=runBlocking {
        val j=NativeOperationJournal(storage("creation-forward-order-"+UUID.randomUUID()));val p=prepared(j);j.markSubmitting(p.target,p.id)
        val unknown=j.uncertain(p.target,p.id,"Original uncertain");assertEquals(unknown,j.observed(p.target,p.id,"prepared",receipt(p,"prepared")))
        val remote=j.observed(p.target,p.id,"outcome-unknown",receipt(p,"outcome-unknown"));assertEquals("outcome-unknown",remote.phase)
        val confirmed=j.observed(p.target,p.id,"confirmed",receipt(p,"confirmed"));assertEquals("created-thread",JSONObject(confirmed.result!!).getString("threadId"));assertTrue(confirmed.submissionAttempted)
        assertEquals(p.intent,confirmed.intent);assertEquals(p.intentSha256,confirmed.intentSha256)
    }
    @Test fun actualHeldReadCannotReplaceANewerOriginalSubmissionReceipt()=runBlocking {
        val started=CountDownLatch(1);val release=CountDownLatch(1)
        lateinit var p: SavedOperation
        CommandFixture { request ->
            require(request.method=="GET"&&request.path=="/v1/desktop/creation-receipts")
            started.countDown();check(release.await(10,TimeUnit.SECONDS));WireResponse(body=receipt(p,"prepared").toString())
        }.use { server ->
            val store=storage("creation-http-order-"+UUID.randomUUID());val flow=NativeCodexFlow(store,NativeHostReads { "" },NativeCodexCommands { "synthetic-token" });val c=client(server.endpoint)
            p=prepared(flow.journal,c);val submitted=flow.journal.markSubmitting(p.target,p.id)
            val read=async(Dispatchers.IO) { flow.query(c,submitted) }
            try {
                assertTrue(started.await(5,TimeUnit.SECONDS));val newer=flow.journal.observed(p.target,p.id,"outcome-unknown",receipt(p,"outcome-unknown"))
                release.countDown();assertEquals(newer,read.await().record);assertEquals(newer,NativeOperationJournal(store).records(p.target).single())
                assertEquals(1,server.requests.size);assertTrue(server.requests.all { it.method=="GET" })
            }finally{release.countDown()}
        }
    }
}
