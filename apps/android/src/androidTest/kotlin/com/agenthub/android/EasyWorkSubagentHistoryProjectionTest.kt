package com.agenthub.android

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class EasyWorkSubagentHistoryProjectionTest {
    private fun page(): JSONObject {
        val parts=JSONArray(listOf("text","reasoning","tool-call","plan-artifact","attachment","future-extension").map { JSONObject().put("type",it).put("text","Original $it") })
        val child=JSONObject().put("id","child").put("kind","child").put("threadId","parent").put("rootRunId","root").put("branchRevision",3)
            .put("status","completed").put("input",JSONObject().put("task","  task\n"))
            .put("checkpoint",JSONObject().put("subagentTranscript",JSONObject().put("messages",JSONArray().put(JSONObject().put("id","original").put("role","assistant").put("content",parts)))))
        return JSONObject().put("branchRevision",3).put("snapshot",JSONObject().put("thread",JSONObject().put("id","parent").put("branchRevision",3))
            .put("subagentRunTrees",JSONArray().put(JSONObject().put("root",JSONObject().put("id","root").put("threadId","parent").put("branchRevision",3)).put("runs",JSONArray().put(child)))))
    }
    @Test fun typedLineagePreservesAllTranscriptPartsAndInput() {
        val selected=EasyWorkSubagentHistory.select(page(),MobileSubagentHistoryRequest("parent","subagent:child"))
        val originals=EasyWorkSubagentHistory.originals(selected)
        assertEquals("  task\n",originals.first().getJSONArray("content").getJSONObject(0).getString("text"))
        assertEquals(6,originals.last().getJSONArray("content").length());assertEquals("original",originals.last().getString("id"))
        assertEquals("child",originals.last().getString("sourceRunId"))
        for(request in listOf(MobileSubagentHistoryRequest("other","subagent:child"),MobileSubagentHistoryRequest("parent","ordinary-message"),MobileSubagentHistoryRequest("parent","subagent:child","other")))
            assertTrue(runCatching { EasyWorkSubagentHistory.select(page(),request) }.isFailure)
        val foreign=page();foreign.getJSONObject("snapshot").getJSONArray("subagentRunTrees").getJSONObject(0).getJSONArray("runs").getJSONObject(0).put("branchRevision",4)
        assertTrue(runCatching { EasyWorkSubagentHistory.select(foreign,MobileSubagentHistoryRequest("parent","subagent:child")) }.isFailure)
    }
    @Test fun realDurableCheckpointContentSurvivesAnEmptyTerminalResult() {
        val page=page()
        val run=page.getJSONObject("snapshot").getJSONArray("subagentRunTrees").getJSONObject(0).getJSONArray("runs").getJSONObject(0)
        val originalParts=run.getJSONObject("checkpoint").getJSONObject("subagentTranscript").getJSONArray("messages").getJSONObject(0).getJSONArray("content")
        // W5 createSubagentPersistedCheckpoint stores {version:1,content}, not messages.
        run.put("checkpoint",JSONObject().put("subagentTranscript",JSONObject().put("version",1).put("content",originalParts)))
            .put("result",JSONObject().put("content",JSONArray()).put("truncated",false))
        val selected=EasyWorkSubagentHistory.select(page,MobileSubagentHistoryRequest("parent","subagent:child"))
        val transcript=EasyWorkSubagentHistory.originals(selected).last()
        assertEquals(6,transcript.getJSONArray("content").length())
        assertEquals("child:transcript",transcript.getString("id"))
        assertEquals("completed",transcript.getString("status"))
        // A real final result with content remains authoritative over an older checkpoint.
        run.put("result",JSONObject().put("content",JSONArray().put(JSONObject().put("type","text").put("text","Final result"))))
        assertEquals("Final result",EasyWorkSubagentHistory.originals(selected).last().getJSONArray("content").getJSONObject(0).getString("text"))
    }
    @Test fun scopedPagesPreserveLongBodiesAndRejectCrossTargetOrChildCursors() {
        val body="HEAD"+"Original🧭\n".repeat(14000)+"TAIL"
        val messages=(0..100).map { MobileMessage("m$it","assistant",if(it==90)body else "Original $it") }
        val recent=EasyWorkSubagentHistory.page(messages,"host/runtime/branch/child",null)
        assertEquals(body,recent.messages.single { it.id=="m90" }.text)
        val earlier=EasyWorkSubagentHistory.page(messages,"host/runtime/branch/child",recent.next)
        assertEquals(messages,earlier.messages+recent.messages);assertNull(earlier.next)
        assertTrue(runCatching { EasyWorkSubagentHistory.page(messages,"other-runtime-or-child",recent.next) }.isFailure)
        assertTrue(runCatching { EasyWorkSubagentHistory.page(messages+messages.last(),"host/runtime/branch/child",null) }.isFailure)
    }
}
