package com.agenthub.android

import android.graphics.Bitmap
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.net.URL
import java.security.MessageDigest

/** Opt-in acoustic capture through the original encrypted phone binding. No claim, chat input, fixture audio or transcript. */
@RunWith(AndroidJUnit4::class)
class MobileVoiceLiveTest {
    @get:Rule(order=0) val foreground=object: org.junit.rules.ExternalResource() {
        override fun before() {
            val d=InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("am start -W -n com.easyai.agenthub.android.debug/androidx.activity.ComponentActivity")
            val out=android.os.ParcelFileDescriptor.AutoCloseInputStream(d).bufferedReader().use { it.readText() };check(out.contains("Status: ok")) { out }
        }
    }
    @get:Rule(order=1) val ui=createComposeRule()
    @get:Rule(order=2) val limit=org.junit.rules.Timeout.seconds(300)
    private val args get()=InstrumentationRegistry.getArguments()
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private val holder=mutableStateOf<MobileSession?>(null)
    private val session get()=holder.value!!
    private fun control(path: String): JSONObject {
        val url=URL(args.getString("voiceLiveControl")+"/um05/"+path)
        return url.openConnection().apply { connectTimeout=5000;readTimeout=45000;setRequestProperty("X-Voice-Case",args.getString("voiceLiveCase")) }.getInputStream().bufferedReader().use { JSONObject(it.readText()) }
    }
    private fun main(block: suspend ()->Unit)=runBlocking { withContext(Dispatchers.Main) { block() } }
    private fun sha(bytes: ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun folder(): File {
        val id=args.getString("voiceLiveCase")!!;require(id.matches(Regex("[a-f0-9-]+")))
        return File(context.getExternalFilesDir(null),"acceptance/UM05-live/$id").apply { mkdirs() }
    }
    private fun shot(name: String) {
        ui.waitForIdle();val i=InstrumentationRegistry.getInstrumentation();i.uiAutomation.waitForIdle(200,5000)
        val image=i.uiAutomation.takeScreenshot();try { File(folder(),"$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG,100,it) } } finally { image.recycle() }
    }
    @After fun stop() { holder.value?.let { main { it.voice?.cancel();it.discovery?.stop();it.leaveForeground() } } }
    @Test fun recordAcousticOriginalBeforeProcessRestart()=exercise("capture")
    @Test fun transcribeOriginalAfterProcessRestart()=exercise("resume")
    private fun exercise(phase: String) {
        val f=control("fixture");assertEquals(phase,f.getString("phase"));val hostId=f.getString("hostId");val namespace=f.getString("namespace")
        require(namespace.matches(Regex("private-live-[a-f0-9-]+")))
        val store=NativeSessionStorage(context,namespace)
        val original=CodexConnectionVault(store).clients().single { it.id==hostId }
        assertEquals(f.getString("expectedTokenSha256"),sha(store.token(hostId).toByteArray()))
        val descriptor=runBlocking { CodexDiscoveryAPI.discover(f.getString("endpoint")) }
        assertTrue(sameScope(descriptor.target,original.scope));assertEquals(original.codexDirect!!.fingerprint,descriptor.fingerprint)
        val verified=original.copy(endpoint=f.getString("endpoint"),codexDirect=original.codexDirect.copy(routes=listOf(CodexRoute(f.getString("endpoint")))+original.codexDirect.routes))
        // Reuse the original verified credential; this changes only its verified transport address.
        CodexConnectionVault(store).updateClient(verified)
        holder.value=MobileSession(store).also { it.configureVoice(context) }
        ui.setContent { AgentHubApp(session) }
        main { session.restore();session.enterForeground() }
        ui.waitUntil(90_000) { !session.restoring && session.hasClient && !session.loading && session.threads.any { it.id==f.getString("threadId") } }
        ui.runOnIdle { session.selectThread(session.threads.first { it.id==f.getString("threadId") }) }
        ui.waitUntil(90_000) { !session.historyLoading && session.canCompose && session.runtime?.canControl==true }
        val captureFile=File(folder(),"capture-proof.json")
        val captureProof=if(phase=="resume") JSONObject(captureFile.readText()) else null
        val priorText=captureProof?.getString("priorDraft") ?: session.text
        var success=false
        val prefix="VOICE_ORIGINAL_DRAFT_"+args.getString("voiceLiveCase")
        try {
            ui.runOnIdle { if(phase=="capture") session.text=prefix else assertEquals(prefix,session.text);session.beginVoice() }
            ui.waitUntil(15_000) { session.aiSharingRequest!=null || session.voice!!.active }
            if(session.aiSharingRequest!=null) {
                assertEquals("codex-dictation",session.aiSharingRequest!!.scope.model)
                ui.onNodeWithTag("ai-sharing-allow").performScrollTo().performClick();ui.waitUntil(15_000) { session.aiSharingRequest==null && !session.aiSharing.busy }
                assertFalse(session.voice!!.active);assertEquals(prefix,session.text)
                ui.runOnIdle { session.beginVoice() }
            }
            if(phase=="capture") {
                ui.waitUntil(30_000) { session.voice!!.phase in listOf(VoiceInputController.Phase.LISTENING,VoiceInputController.Phase.FAILED) }
                assertEquals(session.voice!!.error,VoiceInputController.Phase.LISTENING,session.voice!!.phase)
                shot("actual-acoustic-listening");control("play");android.os.SystemClock.sleep(350)
                ui.runOnIdle { session.leaveForeground() };ui.waitUntil(20_000) { session.voice!!.phase==VoiceInputController.Phase.FAILED }
                val recordingStore=VoiceRecordingStore(context,store)
                val recorded=runBlocking { recordingStore.restore(AISharingScope(session.selectedClient.scope!!,"codex-dictation"),session.selectedClient.codexDirect!!.fingerprint,session.targetKey,prefix) }!!
                val manifest=recorded.manifest!!;val bytes=runBlocking { recordingStore.bytes(recorded) };manifest.verify(bytes)
                File(folder(),"actual-original.wav").writeBytes(bytes) // Private opt-in calibration evidence; never packaged with source.
                val peak=(44 until bytes.size-1 step 2).maxOf { offset->kotlin.math.abs(((bytes[offset].toInt() and 255) or (bytes[offset+1].toInt() shl 8)).toShort().toInt()) }
                assertTrue("The acoustic capture must contain actual non-silent PCM",peak>100)
                val stats=control("stats");assertEquals(0,stats.getInt("claimPosts"));assertEquals(0,stats.getInt("chatPosts"));assertEquals(0,stats.getInt("audioPosts"))
                captureFile.writeText(JSONObject().put("priorDraft",priorText).put("originalText",prefix).put("manifest",manifest.json()).put("namespace",namespace).put("pid",android.os.Process.myPid()).put("pcmPeak",peak).put("microphone",true).put("fixtureAudio",false).toString(2))
                shot("actual-recording-persisted-before-process-exit")
                ui.onNodeWithTag("cancel-dictation").performClick();ui.waitUntil(15_000) { !session.voice!!.active }
                ui.waitUntil(15_000) { store.metadata()?.optJSONObject("drafts")?.optString(session.targetKey)==prefix }
                success=true;return
            }
            ui.waitUntil(20_000) { session.voice!!.phase==VoiceInputController.Phase.FAILED }
            assertTrue(session.voice!!.error.orEmpty().contains("已恢复"));assertNotEquals(captureProof!!.getInt("pid"),android.os.Process.myPid())
            val recovered=runBlocking { VoiceRecordingStore(context,store).restore(AISharingScope(session.selectedClient.scope!!,"codex-dictation"),session.selectedClient.codexDirect!!.fingerprint,session.targetKey,prefix) }!!
            assertEquals(captureProof.getJSONObject("manifest").toString(),recovered.manifest!!.json().toString())
            val before=control("stats");assertEquals(0,before.getInt("audioPosts"));assertEquals(0,before.getInt("claimPosts"));assertEquals(0,before.getInt("chatPosts"))
            shot("actual-original-recording-restored-without-upload")
            ui.onNodeWithTag("stop-dictation").performClick()
            ui.waitUntil(120_000) { session.voice!!.phase in listOf(VoiceInputController.Phase.IDLE,VoiceInputController.Phase.FAILED) }
            if(session.voice!!.phase==VoiceInputController.Phase.FAILED) {
                File(folder(),"first-transcription-failure.json").writeText(JSONObject().put("error",session.voice!!.error).put("draft",session.text).toString(2));shot("actual-first-transcription-failure")
                // One explicit retry uses the original operation; production queries before any upload.
                ui.onNodeWithTag("stop-dictation").performClick();ui.waitUntil(120_000) { session.voice!!.phase in listOf(VoiceInputController.Phase.IDLE,VoiceInputController.Phase.FAILED) }
            }
            assertEquals(session.voice!!.error,VoiceInputController.Phase.IDLE,session.voice!!.phase)
            val result=session.text;assertTrue(result.startsWith(prefix+"\n"))
            File(folder(),"transcription-observation.json").writeText(JSONObject().put("originalManifest",captureProof.getJSONObject("manifest"))
                .put("originalText",prefix).put("transcribedDraft",result).put("pid",android.os.Process.myPid()).put("phase",session.voice!!.phase.toString()).toString(2))
            fun normalize(text: String)=text.lowercase().filter { it.isLetterOrDigit() }
            assertTrue("The actual audio must contain the calibration phrase",normalize(result.removePrefix(prefix)).contains(normalize(f.getString("expectedPhrase"))))
            val stats=control("stats");assertEquals(0,stats.getInt("claimPosts"));assertEquals(0,stats.getInt("chatPosts"));assertEquals(1,stats.getInt("audioPosts"))
            assertEquals(f.getString("expectedTokenSha256"),sha(store.token(hostId).toByteArray()))
            shot("actual-transcript-original-draft")
            File(folder(),"actual-proof.json").writeText(JSONObject().put("microphone",true).put("fixtureAudio",false).put("fixtureTranscript",false)
                .put("hostId",hostId).put("threadId",session.selectedThread).put("namespace",namespace).put("pid",android.os.Process.myPid())
                .put("originalManifest",captureProof.getJSONObject("manifest")).put("capturePid",captureProof.getInt("pid")).put("originalText",prefix).put("transcribedDraft",result).put("calibrationMatched",true).put("tokenSha256",sha(store.token(hostId).toByteArray())).put("http",stats).toString(2))
            success=true
        } finally { if(phase=="resume" && success) main { session.text=priorText } }
    }
}
