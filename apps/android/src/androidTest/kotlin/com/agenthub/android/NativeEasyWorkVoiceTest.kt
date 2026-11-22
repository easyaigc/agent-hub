package com.agenthub.android

import androidx.test.platform.app.InstrumentationRegistry
import com.agenthub.core.TargetScope
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

/** Synthetic adapter/shared-flow boundary cases; never calls an ASR provider. */
class NativeEasyWorkVoiceTest {
    private fun fixtureTarget(host:String="host")=TargetScope("easywork","test","test",host,"workspace","runtime")
    private fun fixtureClient(host:String="host")=MobileClient("easywork-"+easyHash(easyCanonical(CodexCommandCodec.target(fixtureTarget(host))).toByteArray()),"Fixture",fixtureTarget(host))
    private fun receipt(m: VoiceManifest,phase: String)=m.json()
        .put("phase",phase).put("text",if(phase=="completed")"synthetic result" else JSONObject.NULL)
        .put("error",JSONObject.NULL).put("expiresAt","2099-01-01T00:00:00.000Z")

    @Test fun readinessProjectsProviderDiagnosticsWithoutWeakeningCommonValidation() {
        val target=fixtureTarget();val client=fixtureClient()
        fun wire()=JSONObject().put("available",true).put("reason",JSONObject.NULL)
            .put("maxBytes",12*1024*1024).put("maxDurationMs",300000)
            .put("diagnostics",JSONObject().put("modelLoaded",true))
        val ready=EasyWorkVoice.readiness(client,target,wire())
        assertEquals(target.hostId,ready.hostId)
        assertTrue(ready.available)
        for(invalid in listOf(
            wire().put("available","true"),wire().apply {remove("available")},
            wire().put("maxBytes",-1),wire().put("maxBytes","12582912"),
            wire().apply {remove("maxDurationMs")},wire().put("reason",JSONObject()),
            wire().put("hostId","other-host")
        ))assertTrue(runCatching {EasyWorkVoice.readiness(client,target,invalid)}.isFailure)
    }

    private suspend fun scenario(agent: String,phase: String?,queryOnly: Boolean,attempted: Boolean=false): List<String> {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val store=VoiceRecordingStore(context,NativeSessionStorage(context,"voice-wire-"+UUID.randomUUID()))
        val scope=TargetScope(agent,if(agent=="easywork")"test" else null,if(agent=="easywork")"test" else null,"host","workspace","runtime")
        val client=MobileClient(if(agent=="easywork")"easywork-"+easyHash(easyCanonical(CodexCommandCodec.target(scope)).toByteArray()) else "host","Fixture",scope)
        var pending=store.begin(AISharingScope(scope,"codex-dictation"),null,"key","draft","thread","original draft")
        store.file(pending.id).writeBytes(WavePCM.header(3200)+ByteArray(3200))
        pending=store.seal(pending)
        if(attempted)store.mark(pending,prepareAttempted=true,audioAttempted=true)
        val manifest=pending.manifest!!;val original=store.bytes(pending);val calls=mutableListOf<String>()
        val wire: suspend (String,JSONObject,String)->JSONObject = { method,params,id ->
            calls.add(method)
            when(method) {
                "voice/status"->{assertEquals(manifest.operationId,params.getString("operationId"));JSONObject().put("receipt",phase?.let {receipt(manifest,if(it=="transcribing"&&calls.count {v->v=="voice/status"}>1)"completed" else it)} ?: JSONObject.NULL)}
                "voice/prepare"->{assertEquals(manifest.operationId+":prepare",id);assertEquals(manifest,VoiceCodec.manifest(params.getJSONObject("recording")));receipt(manifest,"awaiting-audio")}
                "voice/upload"->{assertEquals(manifest.operationId,id);assertArrayEquals(original,easyBytes(params.getString("audioBase64")));receipt(manifest,"completed")}
                else->error("Unexpected method $method")
            }
        }
        val commands=object: AgentCommands by NativeCodexCommands({error("No real connection")}) {
            override suspend fun voiceStatus(client: MobileClient,request: VoiceManifest)=EasyWorkVoice.status(if(scope.agentId=="easywork")client else fixtureClient(),if(scope.agentId=="easywork")scope else fixtureTarget(),request,wire)
            override suspend fun prepareVoice(client: MobileClient,request: VoiceManifest)=EasyWorkVoice.prepare(if(scope.agentId=="easywork")client else fixtureClient(),if(scope.agentId=="easywork")scope else fixtureTarget(),request,wire)
            override suspend fun uploadVoice(client: MobileClient,request: VoiceManifest,bytes: ByteArray)=EasyWorkVoice.upload(if(scope.agentId=="easywork")client else fixtureClient(),if(scope.agentId=="easywork")scope else fixtureTarget(),request,bytes,wire)
        }
        try {
            val result=runCatching { VoiceTranscriptionFlow(commands,store).transcribe(client,pending,queryOnly) {true} }
            if(phase in listOf("completed","transcribing") || !queryOnly&&!attempted&&phase==null)assertEquals("synthetic result",result.getOrThrow())
            else assertTrue(result.isFailure)
            assertArrayEquals(original,store.bytes(pending));assertEquals("original draft",store.current(pending).originalText)
            return calls
        } finally { store.remove(pending) }
    }
    @Test fun freshFinishPreparesAndUploadsExactlyOnce()=runBlocking {
        assertEquals(listOf("voice/status","voice/prepare","voice/upload"),scenario("easywork",null,false))
    }
    @Test fun recoveryIsReadOnlyForBothProvidersIncludingAwaitingAudioMissingFailureAndCompletion()=runBlocking {
        for(agent in listOf("codex","easywork"))for(phase in listOf(null,"awaiting-audio","failed","outcome-unknown","completed")) {
            assertEquals(listOf("voice/status"),scenario(agent,phase,true))
            assertEquals(listOf("voice/status"),scenario(agent,phase,false,attempted=true))
        }
    }
    @Test fun interruptedTranscriptionPollsTheSameReceiptUntilCompleteWithoutWrites()=runBlocking {
        assertEquals(listOf("voice/status","voice/status"),scenario("easywork","transcribing",true,attempted=true))
    }
    @Test fun malformedOrWrongManifestIsNeverTreatedAsMissing()=runBlocking {
        val m=VoiceManifest("host","thread","draft",UUID.randomUUID().toString(),3244,"a".repeat(64),100)
        for(value in listOf(JSONObject(),JSONObject().put("receipt","bad"),JSONObject().put("receipt",receipt(m,"completed").put("draftId","different")))) {
            var calls=0
            val result=runCatching { EasyWorkVoice.status(fixtureClient(),fixtureTarget(),m) {_,_,_->calls++;value} }
            assertTrue(result.isFailure);assertEquals(1,calls)
            assertFalse((result.exceptionOrNull() as? CodexCommandError)?.status==404)
        }
    }
    @Test fun transportErrorsAndCompletedWithoutTextCannotAuthorizeSubmissionOrFill()=runBlocking {
        val m=VoiceManifest("host","thread","draft",UUID.randomUUID().toString(),3244,"a".repeat(64),100)
        val failure=EasyWorkRejection("read failed","unavailable")
        assertSame(failure,runCatching {EasyWorkVoice.status(fixtureClient(),fixtureTarget(),m) {_,_,_->throw failure}}.exceptionOrNull())
        assertSame(failure,runCatching {EasyWorkVoice.prepare(fixtureClient(),fixtureTarget(),m) {_,_,_->throw failure}}.exceptionOrNull())
        for(value in listOf(receipt(m,"completed").put("text",JSONObject.NULL),receipt(m,"completed").apply {remove("text")})) {
            assertTrue(runCatching {EasyWorkVoice.status(fixtureClient(),fixtureTarget(),m) {_,_,_->JSONObject().put("receipt",value)}}.isFailure)
        }
    }
    @Test fun restoredControllerFinishCannotPrepareOrUploadEvenWhenNoAttemptWasSaved()=runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val store=VoiceRecordingStore(context,NativeSessionStorage(context,"voice-controller-query-"+UUID.randomUUID()))
        val target=TargetScope("easywork","test","test","host","workspace","runtime")
        val draft=VoiceDraft(MobileClient("host","Fixture",target),AISharingScope(target,"codex-dictation"),"key","version","draft","thread","original")
        var pending=store.begin(draft.scope,null,draft.key,draft.draftId,draft.threadId,draft.text)
        store.file(pending.id).writeBytes(WavePCM.header(3200)+ByteArray(3200));pending=store.seal(pending)
        var queries=0;var writes=0;var fills=0
        val commands=object: AgentCommands by NativeCodexCommands({error("No real connection")}) {
            override suspend fun voiceStatus(client: MobileClient,request: VoiceManifest): VoiceReceipt { queries++;return VoiceCodec.receipt(receipt(request,"awaiting-audio"),request) }
            override suspend fun prepareVoice(client: MobileClient,request: VoiceManifest): VoiceReceipt {writes++;error("Must not prepare")}
            override suspend fun uploadVoice(client: MobileClient,request: VoiceManifest,bytes: ByteArray): VoiceReceipt {writes++;error("Must not upload")}
        }
        val controller=VoiceInputController(context,store) {commands}
        try {
            withContext(Dispatchers.Main) {
                controller.start(draft,{true},{_,_->fills++;true})
                withTimeout(10_000) {while(controller.phase!=VoiceInputController.Phase.FAILED)delay(10)}
                controller.finish()
                withTimeout(10_000) {while(controller.phase!=VoiceInputController.Phase.FAILED)delay(10)}
                assertEquals(1,queries);assertEquals(0,writes);assertEquals(0,fills)
            }
            assertFalse(store.current(pending).audioAttempted)
        } finally { withContext(Dispatchers.Main) {controller.endWaiting()};store.remove(pending) }
    }

    @Test fun realStoreControllerAndAdapterKeepPhysicalHostWhenLocalClientIdDiffers()=runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val store=VoiceRecordingStore(context,NativeSessionStorage(context,"voice-physical-host-"+UUID.randomUUID()))
        val target=TargetScope("easywork","authority","subject","physical-host","workspace","runtime")
        val client=MobileClient("easywork-"+easyHash(easyCanonical(CodexCommandCodec.target(target)).toByteArray()),"Fixture",target,agentConnection=AgentConnection(target,emptyList(),"fixture-credential-not-used"))
        val draft=VoiceDraft(client,AISharingScope(client.scope!!,"codex-dictation"),"key","version","draft","thread","original")
        var pending=store.begin(draft.scope,null,draft.key,draft.draftId,draft.threadId,draft.text)
        store.file(pending.id).writeBytes(WavePCM.header(3200)+ByteArray(3200));pending=store.seal(pending)
        val sealed=pending.manifest!!
        assertEquals(target.hostId,sealed.hostId);assertNotEquals(client.id,sealed.hostId)
        val expectedId=pending.id;val calls=mutableListOf<String>();var filled:String?=null
        assertEquals(target.hostId,EasyWorkVoice.readiness(client,target,JSONObject().put("hostId",target.hostId)
            .put("available",true).put("reason",JSONObject.NULL).put("maxBytes",12*1024*1024).put("maxDurationMs",300000)).hostId)
        val commands=object: AgentCommands by NativeCodexCommands({error("No real connection")}) {
            override suspend fun voiceStatus(client:MobileClient,request:VoiceManifest):VoiceReceipt {
                assertEquals(sealed,request);assertEquals(expectedId,request.operationId)
                return EasyWorkVoice.status(client,target,request) {method,params,_->
                    calls.add(method);assertEquals(expectedId,params.getString("operationId"))
                    JSONObject().put("receipt",receipt(sealed,"completed"))
                }
            }
            override suspend fun prepareVoice(client:MobileClient,request:VoiceManifest):VoiceReceipt=error("Recovery must not prepare")
            override suspend fun uploadVoice(client:MobileClient,request:VoiceManifest,bytes:ByteArray):VoiceReceipt=error("Recovery must not upload")
        }
        val controller=VoiceInputController(context,store) {commands}
        try {
            withContext(Dispatchers.Main) {
                controller.start(draft,{true},{original,text->assertEquals(draft,original);filled=original.text+"\n"+text;true})
                withTimeout(10_000) {while(controller.phase!=VoiceInputController.Phase.FAILED)delay(10)}
                controller.finish()
                withTimeout(10_000) {while(controller.phase==VoiceInputController.Phase.FINISHING)delay(10)}
                assertEquals(controller.error,VoiceInputController.Phase.IDLE,controller.phase)
            }
            assertEquals(listOf("voice/status"),calls);assertEquals("original\nsynthetic result",filled)
            assertEquals(target.hostId,sealed.hostId);assertEquals(expectedId,sealed.operationId)
        } finally {withContext(Dispatchers.Main) {controller.endWaiting()};store.remove(pending)}
    }
    @Test fun fullTargetAndPhysicalHostMismatchesStillRejectBeforeWire()=runBlocking {
        val target=TargetScope("easywork","authority","subject","physical-host","workspace","runtime")
        val client=MobileClient("easywork-"+easyHash(easyCanonical(CodexCommandCodec.target(target)).toByteArray()),"Fixture",target)
        for(changed in listOf(TargetScope("easywork","authority","subject","other","workspace","runtime"),
            TargetScope("easywork","authority","subject","physical-host","other","runtime"),
            TargetScope("easywork","authority","subject","physical-host","workspace","other"),
            TargetScope("easywork","authority","other","physical-host","workspace","runtime"),
            TargetScope("easywork","other","subject","physical-host","workspace","runtime"),
            TargetScope("codex","authority","subject","physical-host","workspace","runtime")))
            assertTrue(runCatching {EasyWorkVoice.host(client,changed)}.isFailure)
        val wrongConnection=client.copy(agentConnection=AgentConnection(TargetScope("easywork","authority","subject","physical-host","workspace","wrong-runtime"),emptyList(),"fixture-not-used"))
        assertTrue(runCatching {EasyWorkVoice.host(wrongConnection,target)}.isFailure)
        assertTrue(runCatching {EasyWorkVoice.readiness(client,target,JSONObject().put("hostId",client.id))}.isFailure)
        var calls=0
        val wrong=VoiceManifest(client.id,"thread","draft",UUID.randomUUID().toString(),3244,"a".repeat(64),100)
        assertTrue(runCatching {EasyWorkVoice.status(client,target,wrong) {_,_,_->calls++;error("Must not reach wire")}}.isFailure)
        assertEquals(0,calls)
    }

}
