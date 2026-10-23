package com.agenthub.android

import androidx.test.platform.app.InstrumentationRegistry
import com.agenthub.core.TargetScope
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.UUID

class MobileQueueMetadataTest {
    @get:Rule val foreground: org.junit.rules.TestRule=NativeTestForeground()
    private val context=InstrumentationRegistry.getInstrumentation().targetContext
    private val target=TargetScope("codex",null,null,"queue-metadata-host","queue-workspace","queue-runtime")
    private fun receipt()=JSONObject().put("hostId",target.hostId).put("threadId","queue-thread")
        .put("clientMessageId","original-queued-message").put("state","queued").put("turnId",JSONObject.NULL).put("error",JSONObject.NULL)
        .put("text","original queued body").put("attachmentIds",JSONArray(listOf("original-file")))
        .put("queueSource","host").put("queuePresent",false).put("canExecuteNow",false)
        .put("queueDisposition","legacy-review").put("queueSyncError","original client queue sync error")
    @Test fun fiveOriginalQueueFieldsSurviveNativeHttpCodecAndReopenedEncryptedHistory()=runBlocking {
        val original=receipt()
        val body=JSONObject().put("hostId",target.hostId).put("threadId","queue-thread").put("readTarget",CodexCommandCodec.target(target))
            .put("messages",JSONArray()).put("runtime",JSONObject().put("state","idle").put("turnId",JSONObject.NULL)
                .put("plan",JSONArray()).put("error",JSONObject.NULL).put("approvals",JSONArray()).put("receipts",JSONArray().put(original)).put("canControl",true))
        CommandFixture { WireResponse(body=body.toString()) }.use { server ->
            val client=MobileClient(target.hostId,"queue metadata fixture",target,server.endpoint,requiresReadTarget=true)
            val page=NativeHostReads { "" }.history(client,"queue-thread")
            val namespace="P4-queue-metadata-"+UUID.randomUUID()
            val storage=NativeSessionStorage(context,namespace)
            val key=client.cacheKey("queue-thread")
            NativeHistoryCache(storage).save(key,client,"queue-thread",page,page.messages)
            val restored=checkNotNull(NativeHistoryCache(NativeSessionStorage(context,namespace)).read(key))
            val roundTrip=encodeReceipt(restored.runtime!!.receipts.single())
            for(name in listOf("queueSource","queuePresent","canExecuteNow","queueDisposition","queueSyncError")) {
                assertTrue("Preserve original queue metadata field $name across native HTTP/cache/codec",roundTrip.has(name))
                assertEquals(original.get(name),roundTrip.get(name))
            }
            assertEquals("original-queued-message",roundTrip.getString("clientMessageId"))
            assertEquals("original queued body",roundTrip.getString("text"));assertEquals("original-file",roundTrip.getJSONArray("attachmentIds").getString(0))
            assertEquals(1,server.requests.size);assertTrue(server.requests.all { it.method=="GET" })
        }
    }
    @Test fun absentNullAndFutureQueueMetadataStayCompatibleWhileWrongKnownTypesReject() {
        val original=receipt().put("noticeContext","historical").put("futureQueueEvidence",JSONObject().put("version",2))
        val fields=listOf("queueSource","queuePresent","canExecuteNow","queueDisposition","queueSyncError")
        fields.forEach { original.remove(it) }
        val legacy=CodexCommandCodec.receipt(original)
        assertNull(legacy.queueSource);assertNull(legacy.queuePresent);assertNull(legacy.canExecuteNow)
        assertEquals("historical",encodeReceipt(legacy).getString("noticeContext"))
        fields.forEach { original.put(it,JSONObject.NULL) }
        val nulls=encodeReceipt(CodexCommandCodec.receipt(original))
        fields.forEach { assertTrue(nulls.isNull(it)) }
        for((field,bad) in listOf("queueSource" to 7,"queueDisposition" to true,"queueSyncError" to JSONArray(),"queuePresent" to "false","canExecuteNow" to 0)) {
            assertTrue("Reject wrong type before it can grant queue controls: $field",runCatching { CodexCommandCodec.receipt(receipt().put(field,bad)) }.isFailure)
        }
    }
    @Test fun easyWorkProviderNormalizationKeepsOriginalQueueEvidenceAndRejectsForeignThread() {
        val original=receipt().put("hostId","upstream-local-host-name").put("noticeContext","historical")
        val normalized=encodeReceipt(easyWorkQueueReceipt(target.hostId,"queue-thread",original))
        assertEquals(target.hostId,normalized.getString("hostId"));assertEquals("queue-thread",normalized.getString("threadId"))
        for(field in listOf("clientMessageId","text","queueSource","queuePresent","canExecuteNow","queueDisposition","queueSyncError","noticeContext"))assertEquals(original.get(field),normalized.get(field))
        assertEquals(original.getJSONArray("attachmentIds").toString(),normalized.getJSONArray("attachmentIds").toString())
        assertTrue(runCatching { easyWorkQueueReceipt(target.hostId,"other-thread",original) }.isFailure)
        assertTrue(runCatching { easyWorkQueueReceipt(target.hostId,"queue-thread",original.put("queuePresent","false")) }.isFailure)
    }

}
