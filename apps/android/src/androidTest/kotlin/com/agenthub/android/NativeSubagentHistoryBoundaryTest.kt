package com.agenthub.android

import com.agenthub.core.TargetScope
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Synthetic protocol pages and deterministic deferred reads. No mic, socket, Host or model. */
class NativeSubagentHistoryBoundaryTest {
    private val target=TargetScope("codex",null,null,"host","workspace","desktop-runtime")
    private val client=MobileClient("host","Synthetic Codex",target,"https://example.invalid",true)
    private fun wire(child: String="a",source: String="saved-history",next: String?=null,id: String=child+"-latest"): JSONObject =
        JSONObject().put("version",1).put("hostId","host").put("readTarget",CodexCommandCodec.target(target))
            .put("parentThreadId","parent").put("messageId","activity").put("threadId",child).put("childId",child)
            .put("source",source).put("nextCursor",next?:JSONObject.NULL)
            .put("children",JSONArray(listOf("a","b").map {JSONObject().put("id",it).put("title",it).put("status","completed")}))
            .put("messages",JSONArray().put(JSONObject().put("id",id).put("role","assistant").put("text",id)))
    private fun decode(value: JSONObject,child: String?=null,cursor: String?=null)=
        NativeSubagentHistoryWire.decode(value.toString(),client,MobileSubagentHistoryRequest("parent","activity",child,cursor))
    private fun rejected(block: ()->Unit) {
        var failed=false;try {block()}catch(_: Exception) {failed=true};assertTrue("Invalid page/request must be rejected",failed)
    }
    @Test fun onlyTheNamedHistoryRouteIsAdmitted() {
        val url=NativeHostReads.url("https://example.invalid","v1/subagents/history",
            mapOf("hostId" to "host","threadId" to "parent","messageId" to "activity","childId" to "a","cursor" to "opaque +&"))
        assertEquals("/v1/subagents/history",url.path)
        assertTrue(url.rawQuery.contains("cursor=opaque+%2B%26"))
        rejected {NativeHostReads.url("https://example.invalid","v1/arbitrary",emptyMap())}
    }
    @Test fun exactParentTargetChildAndOpaqueCursorAreRequired() {
        assertEquals("saved-history",decode(wire()).source)
        rejected {decode(wire().put("hostId","foreign"))}
        rejected {decode(wire().put("parentThreadId","foreign"))}
        rejected {decode(wire().put("messageId","foreign"))}
        val foreign=wire();foreign.getJSONObject("readTarget").put("workspaceId","foreign")
        rejected {decode(foreign)}
        rejected {decode(wire("a"),"b")}
        rejected {decode(wire().put("threadId","b"))}
        rejected {decode(wire().put("childId","not-listed"))}
        rejected {MobileSubagentHistoryRequest("parent","activity",null,"opaque")}
        rejected {decode(wire(next="opaque"),"a","opaque")}
    }
    @Test fun malformedVersionSourceDuplicateChildAndMessagesAreRejected() {
        rejected {decode(wire().put("version","1"))}
        rejected {decode(wire().put("source","owner-connected"))}
        val duplicate=wire();duplicate.getJSONArray("children").put(duplicate.getJSONArray("children").getJSONObject(0))
        rejected {decode(duplicate)}
        val rows=wire();rows.getJSONArray("messages").put(rows.getJSONArray("messages").getJSONObject(0))
        rejected {decode(rows)}
    }
    @Test fun codexAccountMetadataDoesNotAddAnIdentityGate() {
        val changed=wire();changed.getJSONObject("readTarget").put("account",JSONObject().put("authority","old").put("subject","metadata"))
        assertEquals("a",decode(changed).childId)
    }
    @Test fun earlierPagePinsChildDeduplicatesAndKeepsSavedSource() = runBlocking {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
        val calls=mutableListOf<Pair<String?,String?>>()
        val initial=decode(wire(next="earlier"))
        val old=decode(wire(next=null,id="a-older"),"a","earlier").let {it.copy(messages=it.messages+initial.messages)}
        val model=NativeSubagentHistoryModel(scope,{true}) {child,cursor->calls+=child to cursor;if(cursor==null)initial else old}
        try {
            model.refresh();model.earlier()
            assertEquals(listOf(null to null,"a" to "earlier"),calls)
            assertEquals(listOf("a-older","a-latest"),model.messages.map {it.id})
            assertEquals("saved-history",model.page?.source);assertNull(model.nextCursor)
        } finally {model.close();scope.cancel()}
    }
    @Test fun sourceChangeDuringPagingKeepsExistingPageAndRequiresRefresh() = runBlocking {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
        val model=NativeSubagentHistoryModel(scope,{true}) {_,cursor ->
            if(cursor==null)decode(wire(next="earlier")) else decode(wire(source="desktop-runtime",id="foreign-epoch"),"a",cursor)
        }
        try {
            model.refresh();model.earlier();assertNotNull(model.error)
            assertEquals(listOf("a-latest"),model.messages.map {it.id});assertEquals("saved-history",model.page?.source)
        } finally {model.close();scope.cancel()}
    }
    @Test fun childChangeDropsEvenAnUncancellableLateEarlierPage() = runBlocking {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
        val late=CompletableDeferred<MobileSubagentHistory>()
        val calls=mutableListOf<Pair<String?,String?>>()
        val model=NativeSubagentHistoryModel(scope,{true}) {child,cursor ->
            calls+=child to cursor
            if(cursor!=null)withContext(NonCancellable) {late.await()}
            else decode(wire(child=child?:"a",next=if(child==null)"earlier" else null),child)
        }
        try {
            model.refresh();model.earlier();model.choose("b")
            late.complete(decode(wire(id="a-late"),"a","earlier"))
            assertEquals(listOf(null to null,"a" to "earlier","b" to null),calls)
            assertEquals("b",model.page?.childId);assertEquals(listOf("b-latest"),model.messages.map {it.id})
        } finally {model.close();scope.cancel()}
    }
    @Test fun retiredGenerationAndLostParentCannotPublishOrRestartClosedModel() = runBlocking {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
        val first=CompletableDeferred<MobileSubagentHistory>();val second=CompletableDeferred<MobileSubagentHistory>()
        var calls=0;var current=true
        val model=NativeSubagentHistoryModel(scope,{current}) {_,_->
            val request=++calls;withContext(NonCancellable) {if(request==1)first.await()else second.await()}
        }
        try {
            model.refresh();model.refresh();second.complete(decode(wire(id="new")));first.complete(decode(wire(id="old")))
            assertEquals(listOf("new"),model.messages.map {it.id})
            current=false;model.close();model.refresh();assertEquals(2,calls)
        } finally {model.close();scope.cancel()}
    }
    @Test fun lossOfParentWhileReadIsInFlightDropsThePage() = runBlocking {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
        val late=CompletableDeferred<MobileSubagentHistory>();var current=true
        val model=NativeSubagentHistoryModel(scope,{current}) {_,_->withContext(NonCancellable) {late.await()}}
        try {
            model.refresh();current=false;late.complete(decode(wire()))
            assertNull(model.page);assertTrue(model.messages.isEmpty())
        } finally {model.close();scope.cancel()}
    }
    @Test fun aFailedChildReadKeepsTheAuthenticatedPickerAndCanReturnToAnotherChild() = runBlocking {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
        val model=NativeSubagentHistoryModel(scope,{true}) {child,_ ->
            if(child=="b")throw HostReadError("synthetic read unavailable")
            decode(wire(child=child?:"a"),child)
        }
        try {
            model.refresh();model.choose("b")
            assertNotNull(model.error);assertNull(model.page);assertTrue(model.messages.isEmpty())
            assertEquals(listOf("a","b"),model.children.map {it.id});assertEquals("b",model.selectedChild)
            model.choose("a")
            assertNull(model.error);assertEquals("a",model.page?.childId)
            assertEquals(listOf("a-latest"),model.messages.map {it.id})
        } finally {model.close();scope.cancel()}
    }

    @Test fun parentLineageMustBeExplicitVersionedAndSavedAndTargetBound() {
        val request=MobileSubagentHistoryRequest("parent","activity",association="parent-thread-lineage")
        val response=wire().put("version",2).put("association","parent-thread-lineage")
        val result=NativeSubagentHistoryWire.decode(response.toString(),client,request)
        assertEquals("parent-thread-lineage",result.association)
        rejected {decode(response)} // No opt-in: old typed path rejects new semantics.
        rejected {NativeSubagentHistoryWire.decode(wire().toString(),client,request)}
        rejected {NativeSubagentHistoryWire.decode(response.put("source","desktop-runtime").toString(),client,request)}
        response.put("source","saved-history").put("parentThreadId","foreign")
        rejected {NativeSubagentHistoryWire.decode(response.toString(),client,request)}
        response.put("parentThreadId","parent").getJSONObject("readTarget").put("runtimeId","foreign")
        rejected {NativeSubagentHistoryWire.decode(response.toString(),client,request)}
        rejected {MobileSubagentHistoryRequest("parent","activity",association="guess-from-text")}
    }
    @Test fun onlyUnavailableTypedReadExposesOptionalFallback() { runBlocking {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
        try {
            for(status in listOf(401,403,404,409,503)) {
                val model=NativeSubagentHistoryModel(scope,{true}) {_,_->throw HostReadError("bounded",status=status)}
                try {model.refresh();yield();assertEquals(status==404,model.unavailable)} finally {model.close()}
            }
        } finally {scope.cancel();scope.coroutineContext[Job]?.join()}
    } }

    @Test fun lineageResponseCannotBorrowTypedVersionAssociationOrChild() {
        val request=MobileSubagentHistoryRequest("parent","activity","a",association="parent-thread-lineage")
        val response=wire().put("version",2).put("association","parent-thread-lineage")
        rejected {NativeSubagentHistoryWire.decode(response.put("version",1).toString(),client,request)}
        response.put("version",2).remove("association")
        rejected {NativeSubagentHistoryWire.decode(response.toString(),client,request)}
        response.put("association","parent-thread-lineage").put("messageId","other-anchor")
        rejected {NativeSubagentHistoryWire.decode(response.toString(),client,request)}
        response.put("messageId","activity").put("childId","b").put("threadId","b")
        rejected {NativeSubagentHistoryWire.decode(response.toString(),client,request)}
    }
}
