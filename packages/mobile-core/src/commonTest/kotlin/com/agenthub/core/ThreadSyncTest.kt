package com.agenthub.core

import kotlin.test.*
import kotlinx.serialization.json.*

class ThreadSyncTest {
    private fun message(id: String="m", text: String="A😀中文e\u0301")=buildJsonObject {
        put("id",id);put("role","assistant");put("status","partial")
        put("content",buildJsonArray { add(buildJsonObject { put("type","text");put("text",text);put("futureField","preserve") }) })
        put("futureMessageField",buildJsonObject { put("version",17) })
    }
    private fun version(message: JsonObject, hash: String="opaque-server-version", text: String?=null)=buildJsonObject {
        put("id",message.getValue("id"));put("hash",hash)
        if(text!=null) { put("textLength",text.length);put("textHash","platform-check");put("shapeHash","opaque-shape") }
    }
    private fun frame(messages: List<JsonObject>, head: List<JsonObject>?=messages.map { version(it) }, reset: Boolean=true, revision: Long=1, patches: List<JsonObject> = emptyList())=buildJsonObject {
        put("snapshot",buildJsonObject { put("thread",buildJsonObject { put("id","thread") });put("futureSnapshot",true) })
        put("messages",JsonArray(messages));put("patches",JsonArray(patches));put("branchRevision",revision);put("reset",reset)
        if(head!=null)put("head",JsonArray(head))
    }
    private fun merge(state: ThreadSyncState, wire: JsonObject)=assertNotNull(ThreadSyncRules.merging(state,wire.toString(),"thread"))
    private fun wire(result: ThreadSyncMerge)=Json.parseToJsonElement(result.wireJson).jsonObject
    private fun known(state: ThreadSyncState)=Json.parseToJsonElement(ThreadSyncRules.parameters(state,"thread")).jsonObject

    @Test fun completeLegacyResponseNeverAcquiresDeltaVersions() {
        val original=frame(listOf(message()),head=null)
        val result=merge(ThreadSyncRules.empty(),original)
        assertEquals(original,wire(result));assertTrue(known(result.next).getValue("known").jsonArray.isEmpty())
        assertFalse(known(result.next).containsKey("branchRevision"));assertTrue(result.textChecks.isEmpty())
        assertNull(ThreadSyncRules.merging(result.next,JsonObject(original+("patches" to JsonArray(listOf(buildJsonObject { put("id","m") })))).toString(),"thread"))
    }
    @Test fun appendUsesOpaqueVersionAndUtf16LengthWithoutLosingUnknownFields() {
        val text="A😀中文e\u0301";assertEquals(7,text.length)
        val first=message(text=text);val initial=merge(ThreadSyncRules.empty(),frame(listOf(first),head=listOf(version(first,text=text))))
        val appended=text+"🧭尾";val final=message(text=appended)
        val patch=buildJsonObject { put("id","m");put("baseHash","opaque-server-version");put("append","🧭尾");put("status","complete") }
        val delta=frame(emptyList(),head=listOf(version(final,"new-opaque-version",appended)),reset=false,patches=listOf(patch))
        val result=merge(initial.next,delta);val actual=wire(result).getValue("messages").jsonArray.single().jsonObject
        assertEquals(JsonObject(final+("status" to JsonPrimitive("complete"))),actual)
        assertEquals(appended,result.textChecks.single().text);assertEquals("platform-check",result.textChecks.single().sha256)
        assertEquals("new-opaque-version",known(result.next).getValue("known").jsonArray[0].jsonObject.getValue("hash").jsonPrimitive.content)
        assertEquals("opaque-server-version",known(initial.next).getValue("known").jsonArray[0].jsonObject.getValue("hash").jsonPrimitive.content)
    }
    @Test fun authoritativeHeadReordersRemovesAndPreservesRichContent() {
        val a=message("a");val b=message("b");val initial=merge(ThreadSyncRules.empty(),frame(listOf(a,b)))
        val rich=buildJsonObject { put("id","rich");put("content",buildJsonArray { add(buildJsonObject { put("type","future-resource");put("payload",buildJsonArray { add("文档");add(42) }) }) }) }
        val result=merge(initial.next,frame(listOf(rich),head=listOf(version(rich,"rich-version"),version(b)),reset=false))
        assertEquals(listOf(rich,b),wire(result).getValue("messages").jsonArray.toList())
        assertEquals(2,known(result.next).getValue("known").jsonArray.size)
    }
    @Test fun invalidBasesAndDuplicateIdsLeavePreviousStateUntouched() {
        val a=message();val state=merge(ThreadSyncRules.empty(),frame(listOf(a))).next;val before=known(state)
        val patch=buildJsonObject { put("id","m");put("baseHash","wrong-base");put("append","x") }
        val bad=listOf(
            frame(emptyList(),head=listOf(version(a,"unexplained-change")),reset=false),
            frame(emptyList(),head=listOf(version(a),version(a)),reset=false),
            frame(emptyList(),head=listOf(version(a)),reset=false,patches=listOf(patch)),
            frame(listOf(a,a)),
            frame(listOf(message("foreign")),head=listOf(version(a))),
            frame(emptyList(),head=listOf(version(a)),reset=false,patches=listOf(JsonObject(patch+("baseHash" to JsonPrimitive("opaque-server-version"))+ ("status" to JsonPrimitive("unsupported"))))),
        )
        for(value in bad) { assertNull(ThreadSyncRules.merging(state,value.toString(),"thread"));assertEquals(before,known(state)) }
        assertNull(ThreadSyncRules.merging(state,frame(listOf(a)).toString(),"other-thread"))
    }
    @Test fun branchRevisionRequiresExplicitResetAndResetCannotAppend() {
        val a=message();val state=merge(ThreadSyncRules.empty(),frame(listOf(a))).next
        assertNull(ThreadSyncRules.merging(state,frame(emptyList(),head=listOf(version(a)),reset=false,revision=2).toString(),"thread"))
        val reset=merge(state,frame(emptyList(),revision=2));assertTrue(wire(reset).getValue("messages").jsonArray.isEmpty())
        assertEquals(2,known(reset.next).getValue("branchRevision").jsonPrimitive.int)
        val patch=buildJsonObject { put("id","m");put("baseHash","opaque-server-version");put("append","x") }
        assertNull(ThreadSyncRules.merging(state,frame(listOf(a),patches=listOf(patch)).toString(),"thread"))
    }
    @Test fun limitsTypesAndTextShapeAreCheckedBeforeCandidatePublication() {
        val a=message();val empty=ThreadSyncRules.empty()
        val tooMany=(0..500).map { version(message("m$it")) }
        val badLength=JsonObject(version(a,text="A😀中文e\u0301")+("textLength" to JsonPrimitive(6)))
        val nonText=JsonObject(a+("content" to buildJsonArray { add(buildJsonObject { put("type","tool");put("text","A😀中文e\u0301") }) }))
        val bad=listOf(frame(emptyList(),head=tooMany),frame(listOf(a),head=listOf(badLength)),frame(listOf(nonText),head=listOf(version(a,text="A😀中文e\u0301"))),JsonObject(frame(listOf(a))+("reset" to JsonPrimitive("true"))),JsonObject(frame(listOf(a))+("branchRevision" to JsonPrimitive(-1))))
        for(value in bad)assertNull(ThreadSyncRules.merging(empty,value.toString(),"thread"))
    }
    @Test fun unchangedVersionsReuseCompleteMessagesAndStillRequirePlatformDigestCheck() {
        val a=message();val head=version(a,text="A😀中文e\u0301")
        val initial=merge(ThreadSyncRules.empty(),frame(listOf(a),head=listOf(head)))
        val result=merge(initial.next,frame(emptyList(),head=listOf(head),reset=false))
        assertEquals(listOf(a),wire(result).getValue("messages").jsonArray.toList())
        assertEquals("A😀中文e\u0301",result.textChecks.single().text)
        assertEquals("platform-check",result.textChecks.single().sha256)
    }
}
