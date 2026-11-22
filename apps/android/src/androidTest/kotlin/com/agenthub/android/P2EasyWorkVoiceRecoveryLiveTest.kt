package com.agenthub.android

import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import java.io.File
import java.io.IOException

/** Task-owned real DEV ONLY. No provider/Registry/session substitute and no automatic phase replay.
 * Root must bind/review/compile first, end this package's process between the two selected tests,
 * and independently freeze desktop receipt + upload/ASR/write counters around cold recovery.
 */
class P2EasyWorkVoiceRecoveryLiveTest {
    @get:Rule val ui=createEmptyComposeRule()
    @get:Rule val limit=Timeout.seconds(300)
    private val i=InstrumentationRegistry.getInstrumentation()
    private lateinit var config:JSONObject
    private lateinit var folder:File
    private lateinit var session:MobileSession
    private val operation="379c4c28-9f26-4531-b6a5-7bfbbb101f0c"
    private val audioSHA="aa5f689f9740f219bc7452cf73852065c208f1950d14c3f89cf16c19e9576851"
    private val dropMessage="P2 verified completion delivery suppressed once"
    private fun read(name:String)=JSONObject(File(folder,name).readText())
    private fun save(name:String,value:JSONObject) {
        val file=File(folder,name)
        require(file.createNewFile()) {"Phase already claimed; reconcile original operation, never replay"}
        file.setReadable(false,false);file.setWritable(false,false);file.setReadable(true,true);file.setWritable(true,true)
        file.writeText(value.toString(2))
    }
    private fun sameTarget()=easyCanonical(CodexCommandCodec.target(checkNotNull(session.selectedClient.scope)))==easyCanonical(config.getJSONObject("target")) &&
        session.selectedClient.agentConnection?.target?.let {sameScope(it,session.selectedClient.scope)}==true
    private fun launch(phase:String,recordIntent:Boolean=true) {
        config=JSONObject(File(InstrumentationRegistry.getArguments().getString("p2VoiceConfig") ?: error("Root-bound task config required")).readText())
        require(config.getBoolean("enabled") && config.getString("taskId")=="mobile-increment-20261122-01a1cd5e" && config.getString("phase")==phase)
        requireNativeVoiceFileAcceptance(i.targetContext)
        require(i.targetContext.packageName==config.getString("expectedPackage"))
        require(config.getString("operationId")==operation && config.getString("sha256")==audioSHA)
        require(config.getBoolean("taskProfileFresh") && config.getBoolean("exclusiveTaskDev") && config.getBoolean("exclusiveTaskEmulator") && config.getBoolean("ownedTaskThread"))
        require(config.getJSONObject("target").getString("agentId")=="easywork")
        require(config.getString("namespace")=="mobile-v1" && config.getString("keyAlias")=="agent-hub-mobile-v1")
        folder=File(i.targetContext.getExternalFilesDir(null),"acceptance/p2-voice-$operation").apply {mkdirs()}
        if(recordIntent)save("$phase-intent.json",JSONObject().put("operationId",operation).put("target",config.getJSONObject("target"))
            .put("pid",android.os.Process.myPid()).put("phase",phase).put("threadId",config.getString("threadId")))
        // Target package is fixed by its own Context; config cannot supply an arbitrary shell target.
        ParcelFileDescriptor.AutoCloseInputStream(i.uiAutomation.executeShellCommand("am start -W -n ${i.targetContext.packageName}/com.agenthub.android.MainActivity")).use {it.readBytes()}
        var activity:MainActivity?=null
        ui.waitUntil(20_000) {i.runOnMainSync {activity=ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).filterIsInstance<MainActivity>().singleOrNull()};activity!=null}
        session=activity!!.session // actual MainActivity production MobileSession → AgentRegistry.
        ui.waitUntil(60_000) {session.selectedClient.scope?.agentId=="easywork" && session.selectedClient.online && !session.loading && !session.historyLoading && session.canCompose}
        require(sameTarget() && session.selectedThread==config.getString("threadId"))
        if(phase=="initial") {
            require(session.text.isEmpty()) {"Fresh task draft required; existing draft is protected"}
            require(config.getString("originalDraft").startsWith("P2_SYNTHETIC_"))
            ui.onNodeWithTag("message-input").performTextReplacement(config.getString("originalDraft"))
        }
        assertEquals(config.getString("originalDraft"),session.text)
        if(ui.onAllNodesWithTag("composer-dismiss-keyboard").fetchSemanticsNodes().size==1)
            ui.onNodeWithTag("composer-dismiss-keyboard").performClick()
        ui.runOnIdle {session.configureVoice(i.targetContext)}
        assertNotNull(session.voice)
        assertEquals(VoiceInputController.Provider.DESKTOP,session.voiceSettings.defaultProvider)
    }
    private fun shot(name:String) {
        val image=i.uiAutomation.takeScreenshot()
        try {File(folder,"$name.png").outputStream().use {image.compress(Bitmap.CompressFormat.PNG,100,it)}} finally {image.recycle()}
    }
    @Test fun firstFinishOnceAndDropVerifiedCompletionLocally() {
        launch("initial")
        firstFinishUsingCurrentOriginalDraft()
    }
    /** One guarded continuation of the SAME original, only after independently confirmed pre-capture failure. */
    @Test fun continueSameOriginalAfterVerifiedPreCaptureFailure() {
        launch("continue-pre-capture",recordIntent=false)
        val originalFence=File(folder,"initial-intent.json")
        require(originalFence.isFile && !java.nio.file.Files.isSymbolicLink(originalFence.toPath()))
        val original=read("initial-intent.json")
        require(original.getString("operationId")==operation && original.getString("phase")=="initial" &&
            original.getString("threadId")==config.getString("threadId") &&
            easyCanonical(original.getJSONObject("target"))==easyCanonical(config.getJSONObject("target")))
        for(name in listOf("fresh-manifest.json","completion-dropped.json","initial-native-proof.json","recover-intent.json","recovery-native-proof.json"))
            require(!File(folder,name).exists()) { "Original progressed beyond pre-capture; only reconcile/query, never submit" }
        require(session.voice!!.phase==VoiceInputController.Phase.IDLE && session.voice!!.fileAcceptance==null)
        val initialConfigFile=File(config.getString("originalInitialConfigPath"))
        val initialConfig=JSONObject(initialConfigFile.readText())
        require(initialConfig.getString("phase")=="initial" && initialConfig.getString("operationId")==operation &&
            initialConfig.getString("sha256")==audioSHA && initialConfig.getString("originalDraft")==config.getString("originalDraft") &&
            initialConfig.getString("threadId")==config.getString("threadId") &&
            easyCanonical(initialConfig.getJSONObject("target"))==easyCanonical(config.getJSONObject("target")))
        require(session.text==initialConfig.getString("originalDraft") && session.text.startsWith("P2_SYNTHETIC_"))
        val failedLog=File(config.getString("originalFailureLogPath"))
        val failure=failedLog.readText()
        require(failure.contains("firstFinishOnceAndDropVerifiedCompletionLocally") && failure.contains("FAILURES!!!") &&
            failure.contains("电脑请求或返回格式无效（voiceReadiness）") && failure.contains("expected:<LISTENING> but was:<FAILED>"))
        val storage=NativeSessionStorage(i.targetContext,existingKeyOnly=true,existingNamespaceOnly=true)
        require(storage.read("voice-recording:$operation")==null && storage.read("voice-audio:$operation")==null)
        require(storage.read("voice-index-v1")?.let {org.json.JSONArray(it).length()==0}!=false)
        val store=VoiceRecordingStore(i.targetContext,storage)
        require(!store.file(operation).exists())
        require(runBlocking {store.restore(AISharingScope(session.selectedClient.scope!!,"codex-dictation"),null,session.targetKey,session.text)}==null)
        val proofFile=File(config.getString("preCaptureReconciliationPath"))
        val proof=JSONObject(proofFile.readText())
        require(proof.getString("kind")=="original-voice-pre-capture-reconciliation" && proof.getString("operationId")==operation)
        require(proof.getString("originalReceiptState")=="missing" && proof.get("observerArmed")==true && proof.get("observerUnsafe")==false)
        fun fresh() { require(System.currentTimeMillis()-proof.getLong("observedAtUnixMs") in -5000L..120000L) {"Fresh independent desktop reconciliation required"} }
        fresh()
        for(counter in listOf("prepareCount","uploadCount","asrCount","writeCount")) {
            val value=proof.get(counter);require(value==0 || value==0L) { "Original write/ASR counters must be exact integer zero" }
        }
        require(proof.getString("threadId")==config.getString("threadId") &&
            easyCanonical(proof.getJSONObject("target"))==easyCanonical(config.getJSONObject("target")))
        require(proof.getString("audioSHA256")==audioSHA && proof.getLong("sizeBytes")==281604L && proof.getLong("durationMs")==8798L)
        require(proof.getString("originalDraftSHA256")==voiceSHA(session.text.toByteArray(Charsets.UTF_8)))
        require(proof.getString("initialIntentSHA256")==fileSHA(originalFence) &&
            proof.getString("originalInitialConfigSHA256")==fileSHA(initialConfigFile) && proof.getString("originalFailureLogSHA256")==fileSHA(failedLog))
        require(proof.getString("failedAppAPK_SHA256")=="020a2ae1296064a479ebe2fe5a4a7a5ede68d1e43d74ffb74c4e04ab8eaf3b96" &&
            proof.getString("failedTestAPK_SHA256")=="5a95c862d5b35798b9b2cd6d90f4306b38994ec3df5e304858c786b1f6c80ada")
        require(proof.getString("candidateAppSHA256")==fileSHA(File(i.targetContext.applicationInfo.sourceDir)) &&
            proof.getString("candidateTestSHA256")==fileSHA(File(i.context.applicationInfo.sourceDir)))
        require(proof.getString("candidateSourceCommit").matches(Regex("[a-f0-9]{40}")) &&
            proof.getString("candidateSourceCommit")==config.getString("expectedCandidateSourceCommit"))
        require(proof.getString("desktopSourceHead")==config.getString("expectedDesktopSourceHead") &&
            proof.getString("desktopSourceOutFingerprint")==config.getString("expectedDesktopSourceOutFingerprint") &&
            proof.getInt("desktopPid")==config.getInt("expectedDesktopPid") &&
            proof.getString("desktopStartIdentity")==config.getString("expectedDesktopStartIdentity"))
        val connection=checkNotNull(session.selectedClient.agentConnection)
        require(connection.candidates.any {it.peerFingerprint==proof.getString("certificateSHA256")} &&
            proof.getString("certificateSHA256")==config.getString("expectedCertificateSHA256"))
        // Read only the already paired public binding's device ID. Never export or install a credential.
        val credential=JSONObject(checkNotNull(storage.read("easywork-credential:${connection.credentialRef}")))
        val paired=EasyWorkBinding(credential.getJSONObject("binding").toString())
        require(sameScope(paired.target.scope(),connection.target))
        require(proof.getString("mobileDeviceId")==paired.mobileId && proof.getString("mobileDeviceId")==config.getString("expectedMobileDeviceId"))
        require(proof.getString("serial")=="emulator-5566" && proof.getInt("appUid")==android.os.Process.myUid() &&
            proof.getString("bootId")==File("/proc/sys/kernel/random/boot_id").readText().trim())
        val wave=File(config.getString("wavePath"));require(wave.length()==281604L && fileSHA(wave)==audioSHA)
        require(sameTarget() && session.text==initialConfig.getString("originalDraft"));fresh()
        save("continue-pre-capture-intent.json",JSONObject().put("operationId",operation).put("target",config.getJSONObject("target"))
            .put("threadId",config.getString("threadId")).put("pid",android.os.Process.myPid()).put("phase","continue-pre-capture")
            .put("initialIntentSHA256",fileSHA(originalFence)).put("failureLogSHA256",fileSHA(failedLog))
            .put("independentReconciliationSHA256",fileSHA(proofFile)).put("candidateAppSHA256",proof.getString("candidateAppSHA256"))
            .put("candidateTestSHA256",proof.getString("candidateTestSHA256")).put("originalDraftSHA256",proof.getString("originalDraftSHA256")))
        // No FAILED finish/retry path is used: one fresh file intake + first finish, after zero-write reconciliation.
        firstFinishUsingCurrentOriginalDraft()
    }
    private fun fileSHA(file:File):String {
        val digest=java.security.MessageDigest.getInstance("SHA-256")
        file.inputStream().use {input->val bytes=ByteArray(192*1024);while(true){val n=input.read(bytes);if(n<0)break;digest.update(bytes,0,n)}}
        return digest.digest().joinToString("") {"%02x".format(it)}
    }
    private fun firstFinishUsingCurrentOriginalDraft() {
        val storage=NativeSessionStorage(i.targetContext)
        val originals=VoiceRecordingStore(i.targetContext,storage)
        val wave=File(config.getString("wavePath")).readBytes()
        require(wave.size==281604 && voiceSHA(wave)==audioSHA)
        var dropped=false
        val boundary=object:NativeVoiceFileAcceptance {
            override suspend fun capture(original:VoiceDraft,store:VoiceRecordingStore):PendingVoiceRecording {
                require(original.client.id==session.selectedClient.id && sameTarget() && original.text==config.getString("originalDraft"))
                require(original.scope.model=="codex-dictation" && session.aiSharing.permits(original.scope))
                // Fixed fresh ID must not replace any existing recording; normal register + seal encryption.
                val pending=store.beginFileAcceptance(original,operation)
                withContext(Dispatchers.IO) {
                    val raw=store.file(operation)
                    NativeVoiceRawOwnership.writer(raw).use {
                        java.nio.file.Files.write(raw.toPath(),wave,java.nio.file.StandardOpenOption.CREATE_NEW,java.nio.file.StandardOpenOption.WRITE)
                    }
                }
                val sealed=store.seal(pending)
                require(sealed.manifest!!.sha256==audioSHA && sealed.manifest.sizeBytes==281604 && sealed.manifest.durationMs==8798)
                save("fresh-manifest.json",sealed.manifest.json())
                return sealed
            }
            override fun completed(recording:PendingVoiceRecording,queryOnly:Boolean) {
                require(!queryOnly && !dropped && recording.id==operation && recording.manifest!!.sha256==audioSHA)
                dropped=true
                // Flow already verified the authoritative result. Do not save/replay its text.
                save("completion-dropped.json",JSONObject().put("manifest",recording.manifest.json()).put("pid",android.os.Process.myPid())
                    .put("deliverySuppressedAfterVerifiedProductionFlow",true).put("cachedTranscript",false))
                throw IOException(dropMessage)
            }
        }
        ui.runOnIdle {session.voice!!.fileAcceptance=boundary;session.beginVoice()}
        ui.waitUntil(10_000) {session.aiSharingRequest!=null || session.voice!!.active}
        if(session.aiSharingRequest!=null) {
            ui.onNodeWithTag("ai-sharing-allow").performScrollTo().performClick()
            ui.waitUntil(10_000) {session.aiSharingRequest==null && !session.aiSharing.busy}
            ui.runOnIdle {session.beginVoice()}
        }
        ui.waitUntil(20_000) {session.voice!!.phase in listOf(VoiceInputController.Phase.LISTENING,VoiceInputController.Phase.FAILED)}
        assertEquals(session.voice!!.error,VoiceInputController.Phase.LISTENING,session.voice!!.phase)
        shot("fresh-file-ready-no-microphone")
        ui.onNodeWithTag("stop-dictation").performClick() // exactly ONE first production finish.
        ui.waitUntil(120_000) {session.voice!!.phase in listOf(VoiceInputController.Phase.IDLE,VoiceInputController.Phase.FAILED)}
        assertTrue(dropped);assertEquals(VoiceInputController.Phase.FAILED,session.voice!!.phase);assertEquals(dropMessage,session.voice!!.error)
        assertEquals(config.getString("originalDraft"),session.text)
        val original=runBlocking {originals.restore(AISharingScope(session.selectedClient.scope!!,"codex-dictation"),null,session.targetKey,session.text)}!!
        assertEquals(operation,original.id);assertTrue(original.prepareAttempted);assertTrue(original.audioAttempted)
        assertEquals(audioSHA,voiceSHA(runBlocking {originals.bytes(original)}))
        shot("verified-completion-delivery-interrupted")
        save("initial-native-proof.json",JSONObject().put("operationId",operation).put("manifest",original.manifest!!.json()).put("originalDraftPreserved",true)
            .put("audioPreserved",true).put("pid",android.os.Process.myPid()).put("status","awaiting-independent-desktop-and-cold-recovery"))
        // Do NOT retry here, remove the recording, replay captured text, or create another operation.
    }
    @Test fun coldReopenAndManualOriginalReceiptOnly() {
        launch("recover")
        require(config.getBoolean("previousProcessExitVerified"))
        val first=read("completion-dropped.json")
        require(first.getInt("pid")!=android.os.Process.myPid()) {"Need a genuinely different task App process"}
        val witness=JSONObject(File(config.getString("desktopBeforeRecoveryProofPath")).readText())
        require(witness.getString("operationId")==operation && witness.getString("phase")=="completed" &&
            witness.getInt("uploadCount")==1 && witness.getInt("asrCount")==1 &&
            easyCanonical(witness.getJSONObject("manifest"))==easyCanonical(first.getJSONObject("manifest")) &&
            easyCanonical(witness.getJSONObject("target"))==easyCanonical(config.getJSONObject("target")))
        require(witness.getString("textSha256").matches(Regex("[a-f0-9]{64}")))
        assertNull(session.voice!!.fileAcceptance) // cold process has no injection or cached return value.
        ui.runOnIdle {session.beginVoice()}
        ui.waitUntil(15_000) {session.voice!!.phase==VoiceInputController.Phase.FAILED}
        assertTrue(session.voice!!.canRetry);assertEquals(config.getString("originalDraft"),session.text)
        shot("cold-original-recording-restored")
        ui.onNodeWithTag("stop-dictation").performClick() // explicit user-facing original-result recovery.
        ui.waitUntil(120_000) {session.voice!!.phase in listOf(VoiceInputController.Phase.IDLE,VoiceInputController.Phase.FAILED)}
        assertEquals(session.voice!!.error,VoiceInputController.Phase.IDLE,session.voice!!.phase)
        val prefix=config.getString("originalDraft").let {it+if(it.isEmpty()||it.last().isWhitespace())"" else "\n"}
        assertTrue(session.text.startsWith(prefix))
        val transcript=session.text.removePrefix(prefix)
        require(transcript.isNotBlank());assertEquals(witness.getString("textSha256"),voiceSHA(transcript.toByteArray(Charsets.UTF_8)))
        ui.onNodeWithTag("message-input").assertTextEquals(session.text)
        shot("original-result-in-original-draft")
        save("recovery-native-proof.json",JSONObject().put("operationId",operation).put("target",config.getJSONObject("target"))
            .put("textSha256",voiceSHA(transcript.toByteArray(Charsets.UTF_8))).put("pid",android.os.Process.myPid())
            .put("originalDraftFilled",true).put("manualRetryClicked",true).put("cachedTranscriptUsed",false)
            .put("status","awaiting-independent-desktop-zero-recovery-writes-check"))
    }
}
