package com.agenthub.android

import com.agenthub.core.TargetScope
import kotlinx.coroutines.*
import org.json.JSONObject

/** Source-level boundary fixtures, not Android Session/Keystore or live Host acceptance. */
suspend fun coordinatorVectors(): Int {
    var passed=0
    suspend fun test(name:String,action:suspend()->Unit){action();passed++;println("PASS $name")}
    val target=TargetScope("codex",null,null,"fixture-host","fixture-workspace","desktop-runtime")
    val client=MobileClient(target.hostId,"Fixture",target,"https://fixture-host.local",mode="desktop-runtime",requiresReadTarget=true,
        codexDirect=PairedCodexConnection(target,"a".repeat(64),listOf(CodexRoute("https://fixture-host.local"))))
    val credential="t".repeat(43)
    val source=NativeChatNotificationSavedConnection.capture(client,credential)
    val event=NativeChatNotificationEvent("b".repeat(64),source.target,NativeChatNoticeKind.APPROVAL,"2026-10-28T12:00:00Z","original-thread")
    fun workspace(threads:List<MobileThread>,scope:TargetScope=target,cursor:String?=null)=MobileWorkspace(target.hostId,"Fixture","desktop-runtime",emptyList(),threads,null,cursor,scope,listOf("history"),"synthetic",true)
    fun thread(id:String="original-thread")=MobileThread(id,target.hostId,"Actual fixture row","idle")
    fun reader(read:suspend(String?)->MobileWorkspace)=object:HostReads{
        override suspend fun workspace(client:MobileClient,projectCursor:String?,threadCursor:String?)=read(threadCursor)
        override suspend fun history(client:MobileClient,threadId:String,cursor:String?):MobileHistory=error("No history/owner fallback allowed")
    }
    test("credential epoch survives serialization/process capture"){
        val again=NativeChatNotificationSavedConnection.decode(source.json());check(again.snapshot.credentialEpoch==source.snapshot.credentialEpoch&&again.snapshot.authorizationFingerprint==source.snapshot.authorizationFingerprint)
        check(!source.toString().contains(credential))
    }
    test("account metadata name online and read revision do not rotate authority"){
        val changed=client.copy(name="Renamed",online=true,readerId="new-reader",scope=TargetScope("codex","history","other",target.hostId,target.workspaceId,target.runtimeId))
        check(NativeChatNotificationSavedConnection.capture(changed,credential).snapshot.credentialEpoch==source.snapshot.credentialEpoch)
    }
    test("route change preserves authority but changes worker transport key"){
        val changed=NativeChatNotificationSavedConnection.capture(client.copy(endpoint="https://second-host.local"),credential)
        check(changed.snapshot.credentialEpoch==source.snapshot.credentialEpoch&&changed.workerKey!=source.workerKey)
    }
    test("credential certificate and full runtime changes each isolate authority"){
        val token=NativeChatNotificationSavedConnection.capture(client,"u".repeat(43))
        val certificate=NativeChatNotificationSavedConnection.capture(client.copy(codexDirect=client.codexDirect!!.copy(fingerprint="b".repeat(64))),credential)
        val other=TargetScope("codex",null,null,target.hostId,target.workspaceId,"other-runtime")
        val runtime=NativeChatNotificationSavedConnection.capture(client.copy(scope=other,codexDirect=client.codexDirect!!.copy(target=other)),credential)
        check(setOf(source.snapshot.credentialEpoch,token.snapshot.credentialEpoch,certificate.snapshot.credentialEpoch,runtime.snapshot.credentialEpoch).size==4)
    }
    test("retained old authorization is immutable after credential replacement"){
        val storage=NativeSessionStorage();val store=NativeChatNotificationAuthorizations(storage);store.retain(source)
        val first=store.all().single().json().toString();store.retain(NativeChatNotificationSavedConnection.capture(client,"u".repeat(43)))
        check(store.all().size==2&&store.all().first().json().toString()==first)
    }
    test("retained unknown schema blocks overwrite with exact original bytes"){
        val storage=NativeSessionStorage();val raw="{\"version\":99,\"authorizations\":[]}";storage.write(NativeChatNotificationAuthorizations.RECORD,raw)
        check(runCatching{NativeChatNotificationAuthorizations(storage).retain(source)}.isFailure)
        check(storage.read(NativeChatNotificationAuthorizations.RECORD)==raw)
    }
    test("retained archive validator does not activate a connection"){
        val storage=NativeSessionStorage();val store=NativeChatNotificationAuthorizations(storage);store.retain(source)
        NativeChatNotificationAuthorizations.validateEncoded(requireNotNull(storage.read(NativeChatNotificationAuthorizations.RECORD)))
        check(NativeSessionStorage().read(NativeChatNotificationAuthorizations.RECORD)==null)
    }
    test("retained source capacity fails without dropping old authority"){
        val storage=NativeSessionStorage();val store=NativeChatNotificationAuthorizations(storage)
        repeat(128){index->store.retain(NativeChatNotificationSavedConnection.capture(client,index.toString().padStart(43,'x')))}
        val raw=storage.read(NativeChatNotificationAuthorizations.RECORD)
        check(runCatching{store.retain(source)}.isFailure);check(storage.read(NativeChatNotificationAuthorizations.RECORD)==raw)
    }
    test("locator uses actual original thread from a later named workspace page"){
        val calls=mutableListOf<String?>();val locator=NativeChatNotificationThreadLocator{reader{cursor->calls.add(cursor);if(cursor==null)workspace(listOf(thread("other")),cursor="next")else workspace(listOf(thread()))}}
        val value=locator.locate(source,event){true};check(value?.thread?.id==event.threadId&&calls==listOf(null,"next"))
    }
    test("locator rejects foreign runtime without trying a different owner"){
        var count=0;val locator=NativeChatNotificationThreadLocator{reader{count++;workspace(listOf(thread()),TargetScope("codex",null,null,target.hostId,target.workspaceId,"foreign"))}}
        val failure=runCatching{locator.locate(source,event){true}}.exceptionOrNull();check(failure is NativeWorkspaceThreadDirectory.Incomplete&&failure.reason=="foreign-target"&&count==1)
    }
    test("page safety limit is incomplete without requesting a 1025th page or synthesizing a thread"){
        var count=0;val locator=NativeChatNotificationThreadLocator{reader{count++;workspace(listOf(thread("other")),cursor="page-$count")}}
        val failure=runCatching{locator.locate(source,event){true}}.exceptionOrNull()
        check(failure is NativeWorkspaceThreadDirectory.Incomplete&&failure.reason=="page-limit"&&count==1024)
    }
    test("original beyond eight pages uses the actual returned row"){
        var count=0;val actual=thread();val locator=NativeChatNotificationThreadLocator{reader{count++;workspace(if(count==12)listOf(actual)else listOf(thread("other-$count")),cursor="page-$count")}}
        check(locator.locate(source,event){true}?.thread===actual&&count==12)
    }
    test("original on final allowed page remains reachable"){
        var count=0;val actual=thread();val locator=NativeChatNotificationThreadLocator{reader{count++;workspace(if(count==1024)listOf(actual)else emptyList(),cursor="page-$count")}}
        check(locator.locate(source,event){true}?.thread===actual&&count==1024)
    }
    test("complete directory absence has no route or alternate owner lookup"){
        var count=0;val locator=NativeChatNotificationThreadLocator{reader{count++;workspace(listOf(thread("other")))}}
        check(locator.locate(source,event){true}==null&&count==1)
    }
    test("long bounded directory cursor is forwarded unchanged"){
        val cursor="p".repeat(16_384);val calls=mutableListOf<String?>();val locator=NativeChatNotificationThreadLocator{reader{next->calls.add(next);workspace(if(next==null)emptyList()else listOf(thread()),cursor=cursor)}}
        check(locator.locate(source,event){true}?.thread?.id==event.threadId&&calls==listOf(null,cursor))
    }
    test("repeated or oversize cursors fail incomplete without another read"){
        for(cursor in listOf("repeat","x".repeat(16_385))){
            var count=0;val locator=NativeChatNotificationThreadLocator{reader{count++;workspace(emptyList(),cursor=cursor)}}
            val failure=runCatching{locator.locate(source,event){true}}.exceptionOrNull()
            check(failure is NativeWorkspaceThreadDirectory.Incomplete&&failure.reason=="invalid-cursor"&&count==if(cursor=="repeat")2 else 1)
        }
    }
    test("full ledger capacity permits the original real row"){
        val actual=thread();val rows=(1 until 16_384).map{thread("other-$it")}+actual
        val locator=NativeChatNotificationThreadLocator{reader{workspace(rows)}}
        check(locator.locate(source,event){true}?.thread===actual)
    }
    test("thread budget overflow is incomplete even if the requested row is in the page"){
        val rows=(1..16_384).map{thread("other-$it")}+thread();var count=0
        val locator=NativeChatNotificationThreadLocator{reader{count++;workspace(rows)}}
        val failure=runCatching{locator.locate(source,event){true}}.exceptionOrNull()
        check(failure is NativeWorkspaceThreadDirectory.Incomplete&&failure.reason=="thread-limit"&&count==1)
    }
    test("foreign row or project cannot cross the target through a valid envelope"){
        for(page in listOf(workspace(listOf(thread().copy(hostId="foreign"))),workspace(listOf(thread())).copy(projects=listOf(MobileProject("foreign","foreign","Foreign",emptyList()))))){
            val locator=NativeChatNotificationThreadLocator{reader{page}}
            val failure=runCatching{locator.locate(source,event){true}}.exceptionOrNull()
            check(failure is NativeWorkspaceThreadDirectory.Incomplete&&failure.reason=="foreign-target")
        }
    }
    test("cancelled lookup propagates cancellation instead of claiming original absence"){
        val entered=CompletableDeferred<Unit>();val gate=CompletableDeferred<Unit>();var count=0
        val locator=NativeChatNotificationThreadLocator{reader{count++;entered.complete(Unit);gate.await();workspace(listOf(thread()))}}
        coroutineScope{
            val job=async{locator.locate(source,event){true}};entered.await();job.cancel();gate.complete(Unit)
            check(runCatching{job.await()}.exceptionOrNull() is CancellationException&&count==1)
        }
    }
    test("shared directory validates a cached first page before a P7-style visitor"){
        var reads=0;var visits=0;val actual=thread()
        val found=NativeWorkspaceThreadDirectory.scan(client,workspace(listOf(actual)),{true},{reads++;error("Cached complete first page needs no fetch")}){page,number->visits++;check(number==1);page.threads.single()}
        check(found===actual&&reads==0&&visits==1)
    }
    test("shared directory rejects mismatched saved target before issuing any request"){
        var reads=0;val wrong=client.copy(id="foreign")
        val failure=runCatching{NativeWorkspaceThreadDirectory.scan<Unit>(wrong,current={true},fetch={reads++;workspace(emptyList())}){_,_->null}}.exceptionOrNull()
        check(failure is NativeWorkspaceThreadDirectory.Incomplete&&failure.reason=="foreign-target"&&reads==0)
    }
    test("locator rechecks lease after suspended read before returning location"){
        var valid=true;val locator=NativeChatNotificationThreadLocator{reader{valid=false;workspace(listOf(thread()))}}
        check(locator.locate(source,event){valid}==null)
    }
    test("notice without original thread has no lookup or navigation fallback"){
        var count=0;val locator=NativeChatNotificationThreadLocator{reader{count++;workspace(listOf(thread()))}}
        check(locator.locate(source,event.copy(threadId=null,kind=NativeChatNoticeKind.CONNECTION)){true}==null&&count==0)
    }
    return passed
}
