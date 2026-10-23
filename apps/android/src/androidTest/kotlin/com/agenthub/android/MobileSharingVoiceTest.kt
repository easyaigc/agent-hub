package com.agenthub.android

import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.agenthub.core.TargetScope
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Real native UI/Keystore/HTTP, synthetic transcripts only. Actual microphone and Host transcription are separate gates. */
@RunWith(AndroidJUnit4::class)
class MobileSharingVoiceTest {
    @get:Rule(order=0) val limit=org.junit.rules.Timeout.seconds(120)
    // A foreground test Activity and private namespace keep synthetic data out of the ordinary App repository.
    @get:Rule(order=1) val foreground=sharingVoiceForeground()
    @get:Rule(order=2) val ui=createComposeRule()
    private lateinit var session: MobileSession
    private lateinit var sessionStorage: NativeSessionStorage
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private val scope=TargetScope("codex",null,null,"fixture-host","fixture-workspace","fixture-runtime")
    private fun main(block: suspend ()->Unit)=runBlocking { withContext(Dispatchers.Main) { block() } }
    @Before fun reset() {
        sessionStorage=NativeSessionStorage(context,"sharing-fixture-"+UUID.randomUUID())
        session=MobileSession(sessionStorage)
        main { session.resetAcceptance();session.configureVoice(context) }
        ui.setContent { AgentHubApp(session) }
    }
    @After fun leave() { ui.runOnIdle { session.voice?.cancel();session.leaveForeground();session.resetAcceptance() } }
    private fun shot(name: String) {
        ui.waitForIdle();val i=InstrumentationRegistry.getInstrumentation();i.waitForIdleSync()
        val folder=File(context.getExternalFilesDir(null),"acceptance/UM05").apply { mkdirs() };val image=i.uiAutomation.takeScreenshot()
        try { File(folder,"$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG,100,it) } } finally { image.recycle() }
    }
    private fun connect(f: SharingVoiceFixture) {
        main { session.connect("共享验收电脑",f.server.endpoint,"") }
        ui.waitUntil(15_000) { session.hasClient && !session.loading }
        ui.runOnIdle { session.selectThread(session.threads.first()) }
        ui.waitUntil(15_000) { !session.historyLoading && session.runtime!=null }
    }
    private fun grant() {
        ui.waitUntil(5000) { session.aiSharingRequest!=null }
        ui.onNodeWithTag("ai-sharing-allow").performScrollTo().performClick()
        ui.waitUntil(10_000) { session.aiSharingRequest==null && !session.aiSharing.busy }
    }
    @Test fun allowReturnsWithoutSendingAndOriginalClickSendsExactlyOnce() {
        SharingVoiceFixture().use { f->connect(f);ui.runOnIdle { session.text="原草稿 中文 😀" }
            ui.onNodeWithTag("send-message").performClick();ui.waitUntil { session.aiSharingRequest!=null }
            assertTrue(f.posts.isEmpty());assertEquals("原草稿 中文 😀",session.text);shot("consent-before-allow")
            grant();assertTrue(f.posts.isEmpty());assertEquals("原草稿 中文 😀",session.text)
            ui.onNodeWithTag("send-message").performClick();ui.waitUntil(15_000) { f.posts.size==1 && session.transferStatus==null }
            assertEquals("原草稿 中文 😀",JSONObject(String(f.posts.single().body)).getString("text"));assertNull(session.aiSharingRequest)
            shot("explicit-send-after-consent")
        }
    }
    @Test fun decliningPreservesDraftAndRevokeRequiresExplicitNewPermission() {
        SharingVoiceFixture().use { f->connect(f);ui.runOnIdle { session.text="保留草稿";session.performAction() }
            ui.waitUntil { session.aiSharingRequest!=null };ui.onNodeWithTag("ai-sharing-decline").performScrollTo().performClick()
            assertTrue(f.posts.isEmpty());assertEquals("保留草稿",session.text)
            ui.runOnIdle { session.performAction() };grant()
            val id=session.aiSharing.grants.single().id
            ui.onNodeWithTag("chat-menu").performClick();ui.onNodeWithTag("open-settings").performClick();ui.onNodeWithTag("settings-ai-sharing").performClick()
            ui.onNodeWithTag("ai-sharing-revoke-$id").performScrollTo().performClick();ui.waitUntil { session.aiSharing.grants.isEmpty() && !session.aiSharing.busy };shot("consent-revoked")
            ui.onNodeWithTag("sheet-done").performClick();ui.runOnIdle { session.performAction() }
            ui.waitUntil { session.aiSharingRequest!=null };assertTrue(f.posts.isEmpty());assertEquals("保留草稿",session.text)
        }
    }
    @Test fun voicePermissionIsSeparateAndDoesNotStartRecordingAfterAllow() {
        SharingVoiceFixture().use { f->connect(f);ui.runOnIdle { session.text="语音草稿";session.beginVoice() }
            ui.waitUntil { session.aiSharingRequest!=null };assertEquals("codex-dictation",session.aiSharingRequest!!.scope.model)
            grant();assertFalse(session.voice!!.active);assertTrue(f.posts.isEmpty());assertEquals(0,f.server.requests.count { it.path.startsWith("/v1/voice/") })
            shot("voice-consent-no-replay")
        }
    }
    @Test fun approvingRequiresPermissionAndAllowDoesNotReplayApproval() {
        SharingVoiceFixture().use { f->f.approvals=JSONArray().put(f.approval());connect(f)
            ui.onNodeWithTag("approval-accept").performScrollTo().performClick();ui.waitUntil { session.aiSharingRequest!=null };assertTrue(f.posts.isEmpty())
            grant();assertTrue(f.posts.isEmpty());ui.onNodeWithTag("approval-accept").performScrollTo().performClick()
            ui.waitUntil(10_000) { f.posts.size==1 };assertEquals("accept",JSONObject(String(f.posts.single().body)).getString("decision"))
        }
    }
    @Test fun rejectingAndStoppingStayAvailableWithoutSharingPermission() {
        SharingVoiceFixture().use { f->f.approvals=JSONArray().put(f.approval());connect(f)
            ui.onNodeWithTag("approval-decline").performScrollTo().performClick()
            try { ui.waitUntil(10_000) { f.posts.size==1 && session.runtime!!.approvals.isEmpty() } }
            catch(failure: Throwable) { throw AssertionError("Synthetic reject: error=${session.error}, consent=${session.aiSharingRequest!=null}, posts=${f.posts.map { it.path+":"+String(it.body) }}, approvals=${session.runtime?.approvals?.size}",failure) }
            assertNull(session.aiSharingRequest);assertEquals("decline",JSONObject(String(f.posts.single().body)).getString("decision"))
            f.state="running";f.turn="fixture-turn";ui.runOnIdle { session.loadHistory() };ui.waitUntil(10_000) { !session.historyLoading && session.running }
            ui.runOnIdle { session.performAction() };ui.waitUntil(10_000) { f.posts.any { it.path=="/v1/stop" } }
            assertNull(session.aiSharingRequest);assertTrue(session.aiSharing.grants.isEmpty())
        }
    }
    @Test fun asyncAnswerKeepsItsTextBehindConsentAndSubmitsOnlyOnSecondClick() {
        SharingVoiceFixture().use { f->f.approvals=JSONArray().put(f.approval(question=true));connect(f)
            ui.onNodeWithTag("question-custom-q").performScrollTo().performTextInput("完整保留的回答 😀")
            ui.onNodeWithTag("answer-question").performScrollTo().performClick();ui.waitUntil { session.aiSharingRequest!=null };assertTrue(f.posts.isEmpty())
            grant();assertTrue(f.posts.isEmpty());ui.onNodeWithTag("question-custom-q").assertTextContains("完整保留的回答 😀")
            ui.onNodeWithTag("answer-question").performScrollTo().performClick();ui.waitUntil(10_000) { f.posts.size==1 }
            val request=JSONObject(String(f.posts.single().body));assertEquals(listOf("完整保留的回答 😀"),request.getJSONObject("answers").getJSONArray("q").strings());shot("question-after-explicit-consent")
        }
    }
    @Test fun runtimeModelChangeRequiresANewPermissionWithoutSending() {
        SharingVoiceFixture().use { f->connect(f);ui.runOnIdle { session.text="模型范围草稿";session.performAction() };grant();assertTrue(f.posts.isEmpty())
            f.model="different-model";ui.runOnIdle { session.loadHistory() };ui.waitUntil(10_000) { !session.historyLoading && session.runtime?.settings?.model=="different-model" }
            ui.runOnIdle { session.performAction() };ui.waitUntil { session.aiSharingRequest!=null }
            assertEquals("different-model",session.aiSharingRequest!!.scope.model);assertTrue(f.posts.isEmpty());assertEquals("模型范围草稿",session.text)
        }
    }
    @Test fun permissionSurvivesStoreRestorationButChangedModelDoesNotMatch()=runBlocking {
        val storage=NativeSessionStorage(context,"sharing-test-"+UUID.randomUUID());val consent=AISharingConsent(storage)
        val grant=AISharingRequest(AISharingScope(scope,"model-a"),"draft","fixture","Codex","A")
        withContext(Dispatchers.Main) { consent.allow(grant) }
        val restored=AISharingConsent(storage);withContext(Dispatchers.Main) { restored.restore() }
        assertTrue(restored.permits(grant.scope));assertFalse(restored.permits(AISharingScope(scope,"model-b")))
        assertFalse(restored.permits(AISharingScope(TargetScope("codex",null,null,"fixture-host","fixture-workspace","different"),"model-a")))
        withContext(Dispatchers.Main) { restored.revoke(restored.grants.single().id) }
        val again=AISharingConsent(storage);withContext(Dispatchers.Main) { again.restore() };assertFalse(again.permits(grant.scope))
    }
    private suspend fun recording(store: VoiceRecordingStore,text: String="原草稿"): PendingVoiceRecording {
        val record=store.begin(AISharingScope(scope,"codex-dictation"),"fixture-certificate","fixture-draft","voice-fixture",null,text)
        withContext(Dispatchers.IO) { store.file(record.id).writeBytes(WavePCM.header(32000)+ByteArray(32000) { (it%100).toByte() }) }
        return store.seal(record)
    }
    private fun client(f: CommandFixture)=MobileClient(scope.hostId,"fixture",scope,f.endpoint,true,"desktop-runtime",false)
    private suspend fun fails(block: suspend ()->Unit) { var failed=false;try { block() } catch(_: Exception) { failed=true };assertTrue("Expected a bounded rejection",failed) }
    @Test fun originalAudioAndManifestSurviveFreshStoreWithoutNetworkReplay()=runBlocking {
        val storage=NativeSessionStorage(context,"voice-store-"+UUID.randomUUID());val first=VoiceRecordingStore(context,storage);val saved=recording(first)
        val bytes=first.bytes(saved);assertFalse(first.file(saved.id).exists())
        val second=VoiceRecordingStore(context,storage);val restored=second.restore(saved.scope,saved.certificate,saved.draftKey,saved.originalText)!!
        assertEquals(saved.json().toString(),restored.json().toString());assertTrue(saved.scope.matches(restored.scope));assertArrayEquals(bytes,second.bytes(restored));assertEquals(saved.id,saved.manifest!!.operationId)
        assertNull(second.restore(saved.scope,"changed-certificate",saved.draftKey,saved.originalText));assertNull(second.restore(saved.scope,saved.certificate,saved.draftKey,"新草稿"))
        assertNull(second.restore(saved.scope.copy(target=TargetScope("codex",null,null,"fixture-host","fixture-workspace","different")),saved.certificate,saved.draftKey,saved.originalText))
        second.remove(restored)
    }
    @Test fun processDeathProvisionalPCMRecoversSameOperationBeforeAnyUpload()=runBlocking {
        val storage=NativeSessionStorage(context,"voice-provisional-"+UUID.randomUUID());val first=VoiceRecordingStore(context,storage)
        val saved=first.begin(AISharingScope(scope,"codex-dictation"),null,"draft","voice-draft",null,"")
        withContext(Dispatchers.IO) { first.file(saved.id).writeBytes(WavePCM.header(0)+ByteArray(32000)) }
        val second=VoiceRecordingStore(context,storage);val restored=second.restore(saved.scope,null,"draft","")!!
        val sealed=second.seal(restored);assertEquals(saved.id,sealed.id);assertEquals(1000,sealed.manifest!!.durationMs);sealed.manifest.verify(second.bytes(sealed));second.remove(sealed)
    }
    @Test fun explicitTranscriptionFillsOnlyReturnedTextUsingExactManifestAndNoChatPost()=runBlocking {
        val store=VoiceRecordingStore(context,NativeSessionStorage(context,"voice-flow-"+UUID.randomUUID()));val saved=recording(store)
        var manifest: JSONObject?=null
        CommandFixture { r-> when {
            r.method=="GET" && manifest==null->WireResponse(404)
            r.path=="/v1/voice/transcriptions" && r.method=="POST"->{ manifest=JSONObject(String(r.body));WireResponse(body=SharingVoiceFixture.receipt(manifest!!,"awaiting-audio",null).toString()) }
            r.path=="/v1/voice/transcriptions/audio"->{ assertArrayEquals(storeBytes(saved,store),r.body);WireResponse(body=SharingVoiceFixture.receipt(manifest!!,"completed","合成转写结果").toString()) }
            else->WireResponse(500)
        } }.use { f->
            val result=VoiceTranscriptionFlow(NativeCodexCommands { "fixture-private" },store).transcribe(client(f),saved) { true }
            assertEquals("合成转写结果",result);assertEquals(saved.manifest!!.json().toString(),manifest!!.toString())
            assertEquals(listOf("GET","POST","POST"),f.requests.map { it.method });assertTrue(f.requests.all { "target=" !in it.pathWithQuery && it.path.startsWith("/v1/voice/") });assertEquals("audio/wav",f.requests.last().headers["content-type"])
        };store.remove(saved)
    }
    private fun storeBytes(saved: PendingVoiceRecording,store: VoiceRecordingStore)=runBlocking { store.bytes(saved) }
    @Test fun lostUploadAndRestartNeverCreateAgainOn404EvenWithStaleInMemoryFlags()=runBlocking {
        val store=VoiceRecordingStore(context,NativeSessionStorage(context,"voice-unknown-"+UUID.randomUUID()));val saved=recording(store)
        store.mark(saved,true,true) // Original process persisted this fence before a lost upload response.
        CommandFixture { WireResponse(404) }.use { f->
            fails { VoiceTranscriptionFlow(NativeCodexCommands { "" },store).transcribe(client(f),saved) { true } }
            assertEquals(1,f.requests.size);assertEquals("GET",f.requests.single().method)
        };store.remove(saved)
    }
    @Test fun changedReceiptAndRevokedPermissionRejectBeforeAudioUpload()=runBlocking {
        val store=VoiceRecordingStore(context,NativeSessionStorage(context,"voice-boundary-"+UUID.randomUUID()));val saved=recording(store)
        CommandFixture { WireResponse(body=SharingVoiceFixture.receipt(saved.manifest!!.json(),"awaiting-audio",null).put("draftId","changed").toString()) }.use { f->
            fails { VoiceTranscriptionFlow(NativeCodexCommands { "" },store).transcribe(client(f),saved) { true } };assertTrue(f.requests.all { it.method=="GET" })
        }
        var permitted=true
        CommandFixture { permitted=false;WireResponse(body=SharingVoiceFixture.receipt(saved.manifest!!.json(),"awaiting-audio",null).toString()) }.use { f->
            fails { VoiceTranscriptionFlow(NativeCodexCommands { "" },store).transcribe(client(f),saved) { permitted } };assertEquals(1,f.requests.size)
        };store.remove(saved)
    }
    @Test fun corruptedSealedAudioFailsBeforeAnyHostRequest()=runBlocking {
        val storage=NativeSessionStorage(context,"voice-corruption-"+UUID.randomUUID());val store=VoiceRecordingStore(context,storage);val saved=recording(store)
        val changed=File(context.cacheDir,"changed-${saved.id}.wav");changed.writeBytes(ByteArray(32044))
        try { storage.writeBinary("voice-audio:${saved.id}",changed) } finally { changed.delete() }
        CommandFixture { WireResponse() }.use { f->fails { VoiceTranscriptionFlow(NativeCodexCommands { "" },store).transcribe(client(f),saved) { true } };assertTrue(f.requests.isEmpty()) }
        store.remove(saved)
    }
    @Test fun cancelledWaitCannotApplyLateTextOrReplayOriginalAudio()=runBlocking {
        val storage=NativeSessionStorage(context,"voice-cancel-"+UUID.randomUUID());val store=VoiceRecordingStore(context,storage);val saved=recording(store)
        val started=CompletableDeferred<Unit>();val returned=CompletableDeferred<Unit>();var applied=false;var calls=0
        val commands=object: CodexCommands by NativeCodexCommands({ "" }) {
            override suspend fun voiceStatus(client: MobileClient,request: VoiceManifest): VoiceReceipt {
                calls++;started.complete(Unit)
                withContext(NonCancellable) { returned.await() }
                return VoiceReceipt(request,"completed","late synthetic transcript",null,"2026-10-18T00:00:00Z")
            }
        }
        val controller=withContext(Dispatchers.Main) { VoiceInputController(context,store) { commands } }
        val original=VoiceDraft(MobileClient(scope.hostId,"fixture",scope,"http://127.0.0.1:1",true,"desktop-runtime",false,codexDirect=PairedCodexConnection(scope,"fixture-certificate",emptyList())),saved.scope,saved.draftKey,"version",saved.draftId,null,saved.originalText)
        withContext(Dispatchers.Main) { controller.start(original,{true},{_,_->applied=true;true}) }
        withTimeout(10_000) { while(controller.phase!=VoiceInputController.Phase.FAILED) delay(20) }
        withContext(Dispatchers.Main) { controller.finish() };withTimeout(10_000) { started.await() }
        withContext(Dispatchers.Main) { controller.cancel() }
        withTimeout(10_000) { while(controller.active) delay(20) };returned.complete(Unit);delay(100)
        assertFalse(applied);assertEquals(1,calls);assertNotNull(store.restore(saved.scope,saved.certificate,saved.draftKey,saved.originalText));store.remove(saved)
    }
    @Test fun cancelRacingDurableSealClosesWaitingAndRetainsOriginal()=runBlocking {
        val store=VoiceRecordingStore(context,NativeSessionStorage(context,"voice-seal-race-"+UUID.randomUUID()))
        val record=store.begin(AISharingScope(scope,"codex-dictation"),null,"draft","voice-draft",null,"original")
        withContext(Dispatchers.IO) { store.file(record.id).writeBytes(WavePCM.header(32000)+ByteArray(32000)) }
        val controller=withContext(Dispatchers.Main) { VoiceInputController(context,store) { NativeCodexCommands { "" } } }
        val original=VoiceDraft(MobileClient(scope.hostId,"fixture",scope,"http://127.0.0.1:1",true,"desktop-runtime",false),record.scope,"draft","version","voice-draft",null,"original")
        withContext(Dispatchers.Main) { controller.start(original,{true},{_,_->fail("No transcript may be applied");false}) }
        withTimeout(10_000) { while(controller.phase!=VoiceInputController.Phase.FAILED) delay(20) }
        val sealed=store.seal(record) // Disk commits after the controller captured the old provisional metadata.
        withContext(Dispatchers.Main) { controller.cancel();controller.cancel() }
        withTimeout(10_000) { while(controller.active) delay(20) }
        val retained=store.restore(record.scope,null,"draft","original")!!;assertEquals(sealed.manifest,retained.manifest);sealed.manifest!!.verify(store.bytes(retained));store.remove(retained)
    }
    @Test fun fiveMinutePCMSealsAndRestoresThroughKeystoreAtTheDocumentedLimit()=runBlocking {
        val storage=NativeSessionStorage(context,"voice-size-boundary-"+UUID.randomUUID());val store=VoiceRecordingStore(context,storage)
        val record=store.begin(AISharingScope(scope,"codex-dictation"),null,"draft","voice-draft",null,"five-minute synthetic boundary, not microphone evidence")
        withContext(Dispatchers.IO) { store.file(record.id).outputStream().use { out->out.write(WavePCM.header(9_600_000));val chunk=ByteArray(32000);repeat(300) { out.write(chunk) } } }
        val started=System.nanoTime();val sealed=store.seal(record);assertEquals(300000,sealed.manifest!!.durationMs);assertEquals(9_600_044,sealed.manifest.sizeBytes)
        val restored=store.restore(record.scope,null,"draft",record.originalText)!!;restored.manifest!!.verify(store.bytes(restored))
        val elapsed=(System.nanoTime()-started)/1_000_000
        File(context.getExternalFilesDir(null),"acceptance/UM05").apply { mkdirs() }.resolve("maximum-keystore-boundary.json").writeText(JSONObject().put("sizeBytes",sealed.manifest.sizeBytes).put("durationMs",sealed.manifest.durationMs).put("elapsedMs",elapsed).put("synthetic",true).toString(2))
        store.remove(restored)
    }
    @Test fun encryptedBinaryRejectsTruncationReorderingAndAuthenticationChanges()=runBlocking {
        val namespace="voice-chunk-integrity-"+UUID.randomUUID();val storage=NativeSessionStorage(context,namespace)
        val source=File(context.cacheDir,"chunk-integrity-${UUID.randomUUID()}");val name="original-audio"
        val bytes=ByteArray(131072) { (it%251).toByte() };source.writeBytes(bytes)
        val filename=java.security.MessageDigest.getInstance("SHA-256").digest(name.toByteArray()).joinToString("") { "%02x".format(it) }+".bin"
        val encrypted=File(File(context.noBackupFilesDir,namespace),filename)
        try {
            storage.writeBinary(name,source);val original=encrypted.readBytes();assertArrayEquals(bytes,storage.readBinary(name,bytes.size))
            val tampered=original.copyOf();tampered[tampered.lastIndex]=(tampered.last().toInt() xor 1).toByte();encrypted.writeBytes(tampered);fails { storage.readBinary(name,bytes.size) }
            encrypted.writeBytes(original.copyOf(original.size-1));fails { storage.readBinary(name,bytes.size) }
            val chunk=65536+12+16
            encrypted.writeBytes(original.copyOfRange(0,8)+original.copyOfRange(8+chunk,original.size)+original.copyOfRange(8,8+chunk));fails { storage.readBinary(name,bytes.size) }
            encrypted.writeBytes(original);assertArrayEquals(bytes,storage.readBinary(name,bytes.size))
        } finally { source.delete();storage.remove(name) }
    }
    @Test fun nativeMicrophoneCaptureCancelAndBackgroundPreserveOriginalWithoutUpload() {
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("pm grant ${context.packageName} android.permission.RECORD_AUDIO").use { descriptor->java.io.FileInputStream(descriptor.fileDescriptor).use { it.readBytes() } }
        assertEquals(android.content.pm.PackageManager.PERMISSION_GRANTED,context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO))
        val originalText="原生录音草稿 "+UUID.randomUUID().toString()
        SharingVoiceFixture().use { f->connect(f);ui.runOnIdle { session.text=originalText;session.beginVoice() };grant()
            ui.runOnIdle { session.beginVoice() };ui.waitUntil(15_000) { session.voice!!.phase in listOf(VoiceInputController.Phase.LISTENING,VoiceInputController.Phase.FAILED) }
            if(session.voice!!.phase!=VoiceInputController.Phase.LISTENING) shot("native-microphone-start-failed")
            assertEquals(session.voice!!.error,VoiceInputController.Phase.LISTENING,session.voice!!.phase)
            android.os.SystemClock.sleep(1100);shot("native-microphone-listening")
            ui.onNodeWithTag("cancel-dictation").performClick();ui.waitUntil(10_000) { !session.voice!!.active };assertEquals(originalText,session.text)
            ui.runOnIdle { session.beginVoice() };ui.waitUntil(15_000) { session.voice!!.phase in listOf(VoiceInputController.Phase.LISTENING,VoiceInputController.Phase.FAILED) }
            if(session.voice!!.phase!=VoiceInputController.Phase.LISTENING) shot("native-microphone-start-failed")
            assertEquals(session.voice!!.error,VoiceInputController.Phase.LISTENING,session.voice!!.phase);android.os.SystemClock.sleep(1100)
            val key=session.targetKey;val consent=AISharingScope(session.selectedClient.scope!!,"codex-dictation")
            ui.runOnIdle { session.leaveForeground() };ui.waitUntil(15_000) { session.voice!!.phase==VoiceInputController.Phase.FAILED };shot("native-background-audio-preserved")
            val store=VoiceRecordingStore(context,sessionStorage);val record=runBlocking { store.restore(consent,null,key,originalText) }!!
            assertNotNull(record.manifest);assertTrue(record.manifest!!.durationMs>=500);runBlocking { record.manifest.verify(store.bytes(record)) }
            assertTrue(f.posts.isEmpty());assertEquals(originalText,session.text)
            ui.onNodeWithTag("cancel-dictation").performClick();ui.waitUntil(10_000) { !session.voice!!.active };runBlocking { store.remove(record) }
        }
    }
}

private class SharingVoiceFixture: java.io.Closeable {
    val posts get()=server.requests.filter { it.method=="POST" }
    @Volatile var state="idle"
    @Volatile var turn: String?=null
    @Volatile var model: String?=null
    @Volatile var approvals=JSONArray()
    fun approval(question: Boolean=false)=JSONObject().put("id","fixture-approval").put("threadId","fixture-thread").put("turnId","approval-turn")
        .put("kind",if(question) "question" else "command").put("title","共享许可审批").put("detail","")
        .put("decisions",JSONArray(if(question) emptyList() else listOf("accept","decline"))).put("interactionMode",if(question) "async" else JSONObject.NULL)
        .put("questions",JSONArray().apply { if(question) put(JSONObject().put("id","q").put("question","请输入回复").put("custom",true).put("isSecret",false).put("options",JSONArray())) })
    private val target=JSONObject().put("agentId","codex").put("hostId","fixture-host").put("workspaceId","fixture-workspace").put("runtimeId","fixture-runtime")
    private val thread=JSONObject().put("id","fixture-thread").put("hostId","fixture-host").put("title","共享测试").put("state","idle").put("projectId",JSONObject.NULL)
    val server=CommandFixture { r->WireResponse(body=when(r.path) {
        "/v1/workspace"->JSONObject().put("hostId","fixture-host").put("name","共享验收电脑").put("connectionMode","desktop-runtime").put("readTarget",target).put("projects",JSONArray()).put("threads",JSONArray().put(thread))
            .put("agentConnectionState",JSONObject().put("runtimeConnected",true)).put("capabilities",JSONArray(listOf("reply","stop","approvals","commands:target-bound:v1","models:read","generation-settings"))).toString()
        "/v1/models"->JSONObject().put("hostId","fixture-host").put("models",JSONArray()).toString()
        "/v1/voice/readiness"->JSONObject().put("hostId","fixture-host").put("available",true).put("reason",JSONObject.NULL).put("maxBytes",12582912).put("maxDurationMs",300000).toString()
        "/v1/history"->JSONObject().put("hostId","fixture-host").put("threadId","fixture-thread").put("readTarget",target).put("messages",JSONArray()).put("runtime",JSONObject().put("state",state).put("turnId",turn ?: JSONObject.NULL).put("plan",JSONArray()).put("error",JSONObject.NULL).put("approvals",approvals).put("receipts",JSONArray()).put("canControl",true).apply { model?.let { put("generationSettings",JSONObject().put("model",it)) } }).toString()
        "/v1/approvals"->{ approvals=JSONArray();"{\"ok\":true}" }
        "/v1/stop"->{ state="interrupted";turn=null;"{\"ok\":true}" }
        "/v1/replies"->JSONObject(String(r.body)).let { JSONObject().put("hostId","fixture-host").put("threadId",it.getString("threadId")).put("clientMessageId",it.getString("clientMessageId")).put("state","submitted").put("turnId","fixture-turn").put("error",JSONObject.NULL).put("text",it.getString("text")).put("attachmentIds",JSONArray()).toString() }
        else->"{}"
    }) }
    companion object { fun receipt(manifest: JSONObject,phase: String,text: String?)=JSONObject(manifest.toString()).put("phase",phase).put("text",text ?: JSONObject.NULL).put("error",JSONObject.NULL).put("expiresAt","2026-10-18T00:00:00Z") }
    override fun close()=server.close()
}

private fun sharingVoiceForeground()=object: org.junit.rules.ExternalResource() {
    override fun before() {
        val i=InstrumentationRegistry.getInstrumentation()
        val d=i.uiAutomation.executeShellCommand("am start -W -n ${i.targetContext.packageName}/androidx.activity.ComponentActivity")
        val response=android.os.ParcelFileDescriptor.AutoCloseInputStream(d).bufferedReader().use { it.readText() }
        check(response.contains("Status: ok")) { response }
    }
}

/** An explicit environment gate. A missing legacy emulator audio HAL is never counted as successful microphone capture. */
@RunWith(AndroidJUnit4::class)
class MobileVoiceUnavailableTest {
    @get:Rule(order=0) val limit=org.junit.rules.Timeout.seconds(120)
    @get:Rule(order=1) val foreground=sharingVoiceForeground()
    @get:Rule(order=2) val ui=createComposeRule()
    @Test fun unavailableMicrophoneKeepsDraftAndNeverUploads() {
        require(InstrumentationRegistry.getArguments().getString("voiceUnavailable")=="true")
        val i=InstrumentationRegistry.getInstrumentation();val context=i.targetContext
        val d=i.uiAutomation.executeShellCommand("pm grant ${context.packageName} android.permission.RECORD_AUDIO")
        android.os.ParcelFileDescriptor.AutoCloseInputStream(d).use { it.readBytes() }
        val session=MobileSession(NativeSessionStorage(context,"voice-unavailable-fixture-"+UUID.randomUUID()))
        i.runOnMainSync { session.resetAcceptance();session.configureVoice(context) }
        ui.setContent { AgentHubApp(session) }
        try { SharingVoiceFixture().use { f->
            runBlocking { withContext(Dispatchers.Main) { session.connect("录音不可用环境",f.server.endpoint,"") } }
            ui.waitUntil(15_000) { !session.loading && session.hasClient }
            i.runOnMainSync { session.selectThread(session.threads.first()) }
            ui.waitUntil(15_000) { !session.historyLoading && session.runtime!=null }
            val text="音频HAL不可用时保留的草稿 "+UUID.randomUUID()
            i.runOnMainSync { session.text=text;session.beginVoice() };ui.waitUntil { session.aiSharingRequest!=null }
            ui.onNodeWithTag("ai-sharing-allow").performScrollTo().performClick();ui.waitUntil(10_000) { session.aiSharingRequest==null && !session.aiSharing.busy }
            i.runOnMainSync { session.beginVoice() };ui.waitUntil(15_000) { session.voice!!.phase==VoiceInputController.Phase.FAILED }
            assertTrue(session.voice!!.error.orEmpty().contains("无法初始化麦克风"));assertEquals(text,session.text);assertTrue(f.posts.isEmpty())
            ui.onNodeWithTag("voice-record-again").assertIsEnabled();ui.onNodeWithTag("stop-dictation").assertIsEnabled()
            val folder=File(context.getExternalFilesDir(null),"acceptance/UM05").apply { mkdirs() };val image=i.uiAutomation.takeScreenshot()
            try { File(folder,"actual-unavailable-microphone-keeps-draft.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG,100,it) } } finally { image.recycle() }
            ui.onNodeWithTag("cancel-dictation").performClick();ui.waitUntil(10_000) { !session.voice!!.active };assertEquals(text,session.text)
        } } finally { i.runOnMainSync { session.voice?.cancel();session.leaveForeground() } }
    }
}
