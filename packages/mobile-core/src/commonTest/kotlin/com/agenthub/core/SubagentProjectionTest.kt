package com.agenthub.core

import kotlin.test.*
import kotlinx.serialization.json.*

class SubagentProjectionTest {
    private fun text(value: String)=buildJsonObject { put("type","text");put("text",value) }
    private fun child(body: JsonArray, result: Boolean=true, thread: String="thread", root: String="root", revision: Int=1, id: String="child", status: String="running", task: String="task")=buildJsonObject {
        put("id",id);put("kind","child");put("threadId",thread);put("rootRunId",root);put("branchRevision",revision);put("status",status)
        put("input",buildJsonObject { put("title","Research");put("task",task) })
        if(result)put("result",buildJsonObject { put("content",body) })
        else put("checkpoint",buildJsonObject { put("subagentTranscript",buildJsonObject { put("content",body) }) })
    }
    private fun snapshot(vararg runs: JsonObject, thread: String="thread", root: String="root", revision: Int=1)=buildJsonObject {
        put("thread",buildJsonObject { put("id",thread);put("branchRevision",revision) })
        put("activeRun",buildJsonObject { put("runId",root) })
        put("subagentRunTrees",buildJsonArray { add(buildJsonObject {
            put("root",buildJsonObject { put("id",root);put("threadId",thread);put("branchRevision",revision) })
            put("runs",JsonArray(runs.toList()))
        }) })
    }.toString()
    private fun event(phase: String, seq: Int=1, root: String="root", thread: String="thread", revision: Int=1)=buildJsonObject {
        put("threadId",thread);put("threadSeq",seq)
        put("payload",buildJsonObject { put("branchRevision",revision);put("event",buildJsonObject {
            put("type","subagent_run_event");put("event",buildJsonObject { put("childRunId","child");put("rootRunId",root);put("phase",phase) })
        }) })
    }.toString()

    @Test fun resultRetainsTaskAndHeadMiddleTailBeyondBothFormerLimits() {
        val task="TASK_HEAD"+"指令🧭".repeat(18000)+"TASK_END"
        val body="BODY_HEAD"+"正文".repeat(18000)+"BODY_MIDDLE"+"🧭正文".repeat(18000)+"BODY_END"
        val value=SubagentProjection().project(snapshot(child(JsonArray(listOf(text(body))),task=task,status="completed")),"thread").single()
        assertEquals(task+"\n\n"+body,value.detail)
        assertEquals("completed",value.status);assertEquals("root",value.rootRunId)
    }
    @Test fun checkpointAlsoRetainsFullBodyAndResultReplacesItWithoutDuplication() {
        val p=SubagentProjection();val body="HEAD"+"x".repeat(70000)+"TAIL"
        assertEquals("task\n\n$body",p.project(snapshot(child(JsonArray(listOf(text(body))),result=false)),"thread").single().detail)
        val completed=child(JsonArray(listOf(text("FINAL"))),status="completed").toMutableMap()
        completed["checkpoint"]=buildJsonObject { put("subagentTranscript",buildJsonObject { put("content",JsonArray(listOf(text(body)))) }) }
        assertEquals("task\n\nFINAL",p.project(snapshot(JsonObject(completed)),"thread").single().detail)
    }
    @Test fun reasoningToolInputsStructuredResultsAndFuturePartsRemainReadable() {
        val parts=buildJsonArray {
            add(buildJsonObject { put("type","reasoning");put("text","reasoning summary") })
            add(buildJsonObject { put("type","tool-call");put("toolName","read");put("args",buildJsonObject { put("path","original.txt") });put("result",buildJsonObject { put("text","full output");put("exitCode",0) }) })
            add(buildJsonObject { put("type","future-part");put("value","preserved") })
        }
        val detail=SubagentProjection().project(snapshot(child(parts)),"thread").single().detail
        for(value in listOf("reasoning summary","read","original.txt","full output","exitCode","future-part","preserved"))assertContains(detail,value)
    }
    @Test fun authoritativeUpdatesReplaceSameChildRatherThanAppendingOutput() {
        val p=SubagentProjection()
        p.project(snapshot(child(JsonArray(listOf(text("old"))))),"thread")
        val next=p.project(snapshot(child(JsonArray(listOf(text("new"))))),"thread")
        assertEquals(1,next.size);assertEquals("subagent:child",next.single().id);assertEquals("task\n\nnew",next.single().detail)
    }
    @Test fun foreignThreadRootAndBranchCannotEnterTheCurrentTree() {
        val parts=JsonArray(listOf(text("foreign")))
        val value=SubagentProjection().project(snapshot(child(parts,thread="other"),child(parts,root="other"),child(parts,revision=2),child(JsonArray(listOf(text("valid"))))),"thread")
        assertEquals(listOf("task\n\nvalid"),value.map { it.detail })
        assertTrue(SubagentProjection().project(snapshot(child(parts)),"other").isEmpty())
    }
    @Test fun eventReplayDoesNotDuplicateTextOrResurrectTerminalChild() {
        val p=SubagentProjection();p.accept(event("completed",2));p.accept(event("stream",1));p.accept(event("stream",3))
        val empty=snapshot();val value=p.project(empty,"thread").single()
        assertEquals("completed",value.status);assertEquals("",value.detail)
        val body=p.project(snapshot(child(JsonArray(listOf(text("once"))),status="completed")),"thread").single()
        assertEquals("task\n\nonce",body.detail)
    }
    @Test fun eventsAreBoundToObservedRootAndBranchAndClearRetiresThem() {
        val p=SubagentProjection();p.accept(event("started",root="foreign"))
        assertTrue(p.project(snapshot(),"thread").isEmpty())
        p.clear();p.accept(event("started",revision=2));assertTrue(p.project(snapshot(),"thread").isEmpty())
        p.clear();assertTrue(p.project(snapshot(),"thread").isEmpty())
    }
    @Test fun independentTargetSessionsDoNotShareLiveRuns() {
        val first=SubagentProjection();val second=SubagentProjection();first.accept(event("failed"))
        assertEquals("failed",first.project(snapshot(),"thread").single().status)
        assertTrue(second.project(snapshot(),"thread").isEmpty())
    }
    @Test fun sharedTimelineKeepsChildSeparateWithoutChangingTheWireRole() {
        fun message(id: String,kind: String)=TimelineMessage(id,"tool",id,kind,"completed","root",null,null)
        val values=listOf(message("before","commandExecution"),message("child","subAgent"),message("after","commandExecution"))
        val rows=MessageProjection.project(values,100,null,false).rows
        assertEquals(listOf("tools","message","tools"),rows.map { it.kind })
        assertEquals(listOf(1),rows[1].indices);assertEquals("tool",values[1].role)
    }
}
