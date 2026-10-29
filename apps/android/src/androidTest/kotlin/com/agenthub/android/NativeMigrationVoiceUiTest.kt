package com.agenthub.android

import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.util.UUID

/** Actual imported session, production AgentHubApp/voice controls and scoped synthetic receipt callbacks. */
class NativeMigrationVoiceUiTest {
    @get:Rule(order=0)val foreground: org.junit.rules.TestRule=NativeTestForeground()
    @get:Rule(order=1)val ui=createAndroidComposeRule<MainActivity>()
    private lateinit var owned: OwnedControlAcceptance
    private lateinit var session: MobileSession
    private val args get()=InstrumentationRegistry.getArguments()
    private var actualDark: Boolean?=null
    @Before fun setup(){ui.waitUntil(30_000){!ui.activity.session.restoring};ui.runOnIdle{owned=OwnedControlAcceptance(ui.activity,"migration-voice-ui")}}
    @After fun finish(){if(::session.isInitialized){ui.runOnIdle{session.voice?.cancel()};ui.waitUntil(15_000){session.voice?.active!=true};ui.runOnIdle{session.leaveForeground()}};if(::owned.isInitialized)ui.runOnIdle{owned.session.leaveForeground();owned.verifyDefault()}}
    private fun shot(name: String) {
        val i=InstrumentationRegistry.getInstrumentation();ui.waitForIdle();val frames=java.util.concurrent.CountDownLatch(1)
        i.runOnMainSync{ui.activity.window.decorView.postOnAnimation{ui.activity.window.decorView.postOnAnimation{frames.countDown()}}};assertTrue(frames.await(5,java.util.concurrent.TimeUnit.SECONDS))
        var bitmap: Bitmap?=null;i.runOnMainSync{assertTrue(ui.activity.window.decorView.hasWindowFocus());bitmap=Bitmap.createBitmap(ui.activity.window.decorView.width,ui.activity.window.decorView.height,Bitmap.Config.ARGB_8888)}
        val image=checkNotNull(bitmap);val done=java.util.concurrent.CountDownLatch(1);var result=-1
        try{i.runOnMainSync{android.view.PixelCopy.request(ui.activity.window,image,{result=it;done.countDown()},android.os.Handler(android.os.Looper.getMainLooper()))};assertTrue(done.await(10,java.util.concurrent.TimeUnit.SECONDS));assertEquals(android.view.PixelCopy.SUCCESS,result)
            File(owned.folder,"$name.png").outputStream().use{image.compress(Bitmap.CompressFormat.PNG,100,it)}
            File(owned.folder,"$name-window.json").writeText(JSONObject().put("method","PixelCopy.owned-activity-window").put("package",i.targetContext.packageName).put("width",image.width).put("height",image.height)
                .put("language",session.language).put("appearance",session.appearance).put("actualDark",actualDark).put("fontScale",ui.density.fontScale).toString())
        }finally{image.recycle()}
    }
    @Test fun importedRetryQueriesOriginalAndLocalizesOwnedStatusWhileKeepingExternalErrorLiteral()=runBlocking{
        val source=owned.storage.child(ui.activity,"voice-ui-source");val payload=MigrationActivationFixtures.payload();val client=MigrationActivationFixtures.client(payload);val rows=payload.getJSONObject("records");val metadata=JSONObject(rows.getString("metadata"));val key=client.cacheKey(null);val originalText="Original voice draft 中文 😀 100% %@"
        metadata.getJSONObject("drafts").put(key,originalText);rows.put("metadata",metadata.toString());rows.keys().forEach{source.write(it,rows.getString(it))}
        val sourceStore=VoiceRecordingStore(ui.activity,source);val pending=sourceStore.begin(AISharingScope(client.scope!!,"codex-dictation"),client.codexDirect!!.fingerprint,key,"voice-v1:ui-draft",null,originalText)
        val audio=WavePCM.header(32000)+ByteArray(32000){(it%251).toByte()};sourceStore.file(pending.id).writeBytes(audio);val sealed=sourceStore.seal(pending)
        val password="owned voice interface migration";val receipt=NativeMigrationCandidateStore(ui.activity,owned.storage).stage(UUID.randomUUID().toString(),NativeMigrationExport(ui.activity,source).seal(password),password);NativeMigrationActivation(ui.activity,owned.storage).activate(receipt)
        val current=owned.storage.reopen(ui.activity);val voiceStore=VoiceRecordingStore(ui.activity,current);var reads=0;var writes=0;var terminals=0;var applied=0;var externalFailure=false;val entered=CompletableDeferred<Unit>();val returned=CompletableDeferred<Unit>()
        val commands=object: CodexCommands by NativeCodexCommands({""}){
            override suspend fun voiceStatus(client: MobileClient,request: VoiceManifest): VoiceReceipt{reads++;assertEquals(sealed.manifest,request);if(externalFailure)throw CodexCommandError("复制",503);entered.complete(Unit);returned.await();return VoiceReceipt(request,"awaiting-audio",null,null,"2030-01-01T00:00:00Z")}
            override suspend fun prepareVoice(client: MobileClient,request: VoiceManifest): VoiceReceipt{writes++;error("Imported audio must not prepare")}
            override suspend fun uploadVoice(client: MobileClient,request: VoiceManifest,bytes: ByteArray): VoiceReceipt{writes++;error("Imported audio must not upload")}
        }
        val readBoundary=object: HostReads by NativeHostReads({""}){
            override suspend fun workspace(client: MobileClient,projectCursor: String?,threadCursor: String?): MobileWorkspace=awaitCancellation()
            override suspend fun history(client: MobileClient,threadId: String,cursor: String?): MobileHistory=awaitCancellation()
        }
        ui.runOnIdle{session=MobileSession(current,readBoundary,commands);session.restore()};ui.waitUntil(30_000){!session.restoring};assertFalse(session.localRecoveryRequired)
        val language=args.getString("readingLanguage")?:"en";val appearance=args.getString("viewportTheme")?:"light"
        ui.runOnIdle{session.leaveForeground();session.error=null;session.changeLanguage(language);session.changeAppearance(appearance);session.configureVoice(ui.activity)
            session.voice!!.discardPort=NativeVoiceDiscardPort{terminals++;error("Ending waiting must not submit a discard terminal")}
            ui.activity.setContent{AgentHubApp(session){actualDark=it}}}
        assertEquals(originalText,session.text);val originalVoice=current.read("voice-recording:${pending.id}")!!
        val originalFingerprint=NativeVoiceDiscardRequest(checkNotNull(voiceStore.restore(pending.scope,pending.certificate,key,originalText))).fingerprint
        ui.runOnIdle{session.voice!!.start(VoiceDraft(session.selectedClient,pending.scope,key,"owned-version",pending.draftId,null,originalText),{true},{_,_->applied++;false})}
        ui.waitUntil(15_000){session.voice!!.phase==VoiceInputController.Phase.FAILED};assertNull(session.voice!!.permissionRequest);assertEquals(0,reads);assertEquals(0,writes)
        val text=NativeL10n(ui.activity,language);ui.onNodeWithTag("voice-status").assertTextEquals(text.tr("已恢复此草稿的原录音。点击重试查询同一转写；不会自动重新提交。"))
        ui.onNodeWithTag("stop-dictation").performClick();ui.waitUntil(15_000){entered.isCompleted&&session.voice!!.phase==VoiceInputController.Phase.FINISHING};ui.onNodeWithTag("stop-dictation").assertIsNotEnabled();ui.onNodeWithTag("dictation-waveform").assertContentDescriptionEquals(text.tr("电脑正在转写…"));ui.runOnIdle{session.voice!!.finish()};assertEquals(1,reads)
        shot("imported-voice-query-busy");returned.complete(Unit);ui.waitUntil(15_000){session.voice!!.phase==VoiceInputController.Phase.FAILED}
        ui.onNodeWithTag("voice-status").assertTextEquals(text.tr(ImportedVoiceReceiptOnly.MESSAGE));ui.onNodeWithTag("stop-dictation").assertIsEnabled();assertEquals(1,reads);assertEquals(0,writes);assertEquals(0,applied)
        val panel=ui.onNodeWithTag("voice-panel").fetchSemanticsNode().boundsInRoot;val status=ui.onNodeWithTag("voice-status").fetchSemanticsNode().boundsInRoot;assertTrue(status.width>0&&status.height>0&&status.left>=panel.left&&status.right<=panel.right&&status.top>=panel.top&&status.bottom<=panel.bottom)
        assertEquals(appearance=="dark",actualDark);shot("imported-voice-receipt-only")
        externalFailure=true;ui.onNodeWithTag("stop-dictation").performClick();ui.waitUntil(15_000){session.voice!!.phase==VoiceInputController.Phase.FAILED&&reads==2};ui.onNodeWithTag("voice-status").assertTextEquals("复制");shot("original-external-error-literal")
        ui.onNodeWithTag("cancel-dictation").assertContentDescriptionEquals(text.tr("丢弃录音"))
        ui.onNodeWithTag("voice-end-waiting").performScrollTo().assertIsDisplayed().assertIsEnabled().assertTextEquals(text.tr("结束等待")).performClick()
        ui.waitUntil(15_000){session.voice!!.active==false}
        val preserved=checkNotNull(voiceStore.restore(pending.scope,pending.certificate,key,originalText))
        assertEquals(pending.id,preserved.id);assertEquals(sealed.manifest,preserved.manifest);assertEquals(originalFingerprint,NativeVoiceDiscardRequest(preserved).fingerprint)
        assertEquals(originalText,session.text);assertEquals(originalVoice,current.read("voice-recording:${pending.id}"));assertArrayEquals(audio,voiceStore.bytes(preserved));assertEquals(2,reads);assertEquals(0,writes);assertEquals(0,terminals);assertEquals(0,applied)
        File(owned.folder,"voice-ui-proof.json").writeText(JSONObject().put("language",language).put("appearance",appearance).put("actualDark",actualDark).put("statusWithinPanel",true).put("microphoneNotRequested",true)
            .put("originalVoiceQueries",reads).put("voiceWriteCalls",writes).put("voiceTerminalCalls",terminals).put("exitAction","voice-end-waiting").put("exitActionLabel",text.tr("结束等待"))
            .put("originalFingerprint",originalFingerprint).put("originalFingerprintUnchanged",true).put("appliedTranscripts",applied).put("originalDraftAndAudioPreserved",true).put("originalMetadataUnchanged",true).put("externalErrorLiteral",true).put("busyActionDisabled",true).toString())
    }
}
