package com.agenthub.android

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Synthetic schema boundaries, not evidence of a real desktop/native workflow. */
class EasyWorkQuestionProjectionTest {
    private fun request(call: String,async: Boolean=true)=JSONObject().put("threadId","parent").put("runId","run")
        .put("sessionId","session").put("toolCallId",call).put("async",async).put("status","pending")
        .put("questions",JSONArray().put(JSONObject().put("header","q").put("question","Original prompt")
            .put("isSecret",true).put("multiple",true).put("custom",true).put("options",JSONArray()
                .put(JSONObject().put("label","A").put("description","First"))
                .put(JSONObject().put("label","B").put("description","Second")))))
    @Test fun multiplePendingRequestsKeepIdentityWithoutBlocking() {
        val first=request("one");val second=request("two")
        val snapshot=JSONObject().put("pendingQuestion",first).put("questionStates",JSONArray().put(first).put(second).put(request("done").put("status","answered")))
        val rows=EasyWorkQuestionProjection.approvals(snapshot,"parent")
        assertEquals(2,rows.size);assertTrue(rows.all { it.getString("interactionMode")=="async" && it.getJSONArray("decisions").length()==0 })
        first.put("answeredAt",999)
        assertEquals(rows[0].getString("id"),EasyWorkQuestionProjection.approvals(snapshot,"parent")[0].getString("id"))
        assertTrue(rows[0].getJSONArray("questions").getJSONObject(0).getBoolean("isSecret"))
    }
    @Test fun legacyBlockingAndAsyncFallbackRemainAvailable() {
        val blocking=EasyWorkQuestionProjection.approvals(JSONObject().put("pendingQuestion",request("blocking",false)),"parent").single()
        assertFalse(blocking.has("interactionMode"));assertEquals(2,blocking.getJSONArray("decisions").length())
        assertEquals("async",EasyWorkQuestionProjection.approvals(JSONObject().put("pendingQuestion",request("legacy")),"parent").single().getString("interactionMode"))
    }
    @Test fun answerPreservesWhitespaceAndSelectedCombinationAndSkipUsesOriginalRequest() {
        val approval=EasyWorkQuestionProjection.approvals(JSONObject().put("pendingQuestion",request("original")),"parent").single()
        val id=approval.getString("id");val original="  first\nsecond  \n"
        val params=EasyWorkQuestionProjection.resolution("parent","run",id,"answer",mapOf("q" to listOf("A","B",original)))
        val answer=params.getJSONObject("answer").getJSONArray("questions").getJSONObject(0)
        assertEquals(original,answer.getString("customAnswer"));assertEquals(2,answer.getJSONArray("selectedOptions").length())
        assertEquals("original",params.getString("serverRequestId"));assertEquals("session",params.getString("sessionId"))
        val skip=EasyWorkQuestionProjection.resolution("parent","run",id,"skip",emptyMap())
        assertEquals("abandon",skip.getString("action"));assertFalse(skip.has("answer"))
        assertTrue(runCatching { EasyWorkQuestionProjection.resolution("other","run",id,"answer",mapOf("q" to listOf(original))) }.isFailure)
        assertTrue(runCatching { EasyWorkQuestionProjection.resolution("parent","other",id,"skip",emptyMap()) }.isFailure)
        assertTrue(runCatching { EasyWorkQuestionProjection.resolution("parent","run",id,"answer",mapOf("other" to listOf(original))) }.isFailure)
    }
    @Test fun foreignAndDuplicateRequestsAreRejected() {
        assertTrue(runCatching { EasyWorkQuestionProjection.approvals(JSONObject().put("pendingQuestion",request("one").put("threadId","foreign")),"parent") }.isFailure)
        val request=request("one")
        assertTrue(runCatching { EasyWorkQuestionProjection.approvals(JSONObject().put("questionStates",JSONArray().put(request).put(request)),"parent") }.isFailure)
    }
}
