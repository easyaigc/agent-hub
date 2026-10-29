package com.agenthub.android

import android.Manifest
import android.content.Context
import android.os.Process
import androidx.test.platform.app.InstrumentationRegistry
import com.agenthub.core.TargetScope
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Actual Session/storage/owner; only recognizer and Host transport are synthetic. */
internal class NativeSavedVoiceSessionFixture(val context: Context,val storage: NativeSessionStorage) {
    companion object {
        const val ORIGINAL="original saved voice draft"
        const val TRANSCRIPT="synthetic saved file transcript"
        const val ACCEPTED=ORIGINAL+"\n"+TRANSCRIPT
        suspend fun until(label: String,predicate: ()->Boolean) {
            withTimeout(15_000) {while(!predicate())delay(5)}
        }
        fun fresh(context: Context)=NativeSavedVoiceSessionFixture(context,NativeSessionStorage(context,"P7-saved-session-"+UUID.randomUUID()))
    }
    val voiceHostCalls=AtomicInteger()
    val microphoneCalls=AtomicInteger()
    val answers=AtomicInteger()
    val probes=AtomicInteger()
    val decodes=AtomicInteger()
    val engineEntered=CompletableDeferred<Unit>()
    var engineRelease: CompletableDeferred<Unit>?=null
    var engineExited=false
    private val target=TargetScope("codex",null,null,"saved-session-host","workspace","runtime")
    private fun approvalWire()=JSONObject().put("id","approval").put("threadId","thread").put("turnId","turn").put("kind","question")
        .put("title","Question").put("detail","").put("decisions",JSONArray()).put("questions",JSONArray().put(JSONObject()
            .put("id","q").put("question","Question").put("custom",true).put("isSecret",false).put("options",JSONArray())))
    private val reads=object: HostReads {
        override suspend fun workspace(client: MobileClient,projectCursor: String?,threadCursor: String?)=HostReadCodec.workspace(JSONObject()
            .put("hostId",target.hostId).put("name","Fixture").put("connectionMode","desktop-runtime").put("readTarget",CodexCommandCodec.target(target))
            .put("projects",JSONArray()).put("threads",JSONArray(listOf("thread","other").map {id->JSONObject().put("hostId",target.hostId).put("id",id).put("title",id).put("state","idle")}))
            .put("agentConnectionState",JSONObject().put("runtimeConnected",true)).put("capabilities",JSONArray(listOf("reply","approvals","commands:target-bound:v1"))).toString())
        override suspend fun history(client: MobileClient,threadId: String,cursor: String?)=HostReadCodec.history(JSONObject()
            .put("hostId",target.hostId).put("threadId",threadId).put("readTarget",CodexCommandCodec.target(target)).put("messages",JSONArray())
            .put("runtime",JSONObject().put("state","idle").put("turnId",JSONObject.NULL).put("plan",JSONArray()).put("error",JSONObject.NULL)
                .put("approvals",JSONArray().apply {if(threadId=="thread")put(approvalWire())}).put("receipts",JSONArray()).put("canControl",true)).toString())
    }
    private val commands=object: CodexCommands by NativeCodexCommands({error("No unrelated command")}) {
        override suspend fun models(client: MobileClient)=emptyList<CodexModel>()
        override suspend fun voiceReadiness(client: MobileClient): VoiceReadiness {voiceHostCalls.incrementAndGet();error("No Host voice request")}
        override suspend fun answer(client: MobileClient,threadId: String,turnId: String,approvalId: String,decision: String,answers: Map<String,List<String>>) {this@NativeSavedVoiceSessionFixture.answers.incrementAndGet();error("No implicit answer")}
    }
    val session=MobileSession(storage,reads,commands)
    init {require(context.packageName=="com.easyai.agenthub.android.acceptance")}
    private fun configure() {
        session.configureVoice(context)
        for(controller in listOfNotNull(session.voice,session.questionVoice)) {
            controller.systemFactory={microphoneCalls.incrementAndGet();error("No microphone")}
            controller.savedFileFactory={object: NativeSavedVoiceSpeech {
                override suspend fun probe(locale: String): SavedVoiceProbe {probes.incrementAndGet();return SavedVoiceProbe(SavedVoiceAvailability.INSTALLED)}
                override suspend fun install(locale: String,checkpoint: suspend ()->Unit) {error("No model installation")}
                override suspend fun transcribe(locale: String,wav: ByteArray,checkpoint: suspend ()->Unit): SavedVoiceText {
                    decodes.incrementAndGet();SavedVoicePcm.validate(wav);engineEntered.complete(Unit)
                    try {engineRelease?.let {withContext(NonCancellable){it.await()}};checkpoint();return SavedVoiceText(TRANSCRIPT,"vosk-model-small-en-us-0.15","30f26242c4eb449f948e42cb302dd7a686cb29a3423a8367f99ff41780942498")}
                    finally {engineExited=true}
                }
            }}
        }
    }
    suspend fun connect() {
        session.resetAcceptance(reads,commands);configure();session.connect("Fixture","https://fixture.invalid","")
        session.selectThread(session.threads.first {it.id=="thread"});until("history") {!session.historyLoading&&session.runtime?.approvals?.isNotEmpty()==true}
        session.text="independent composer draft";storage.awaitQueuedWrites()
    }
    suspend fun restore() {
        configure();session.restore();until("restored actual Session") {!session.restoring&&!session.localRecoveryRequired&&session.selectedThread=="thread"&&session.runtime?.approvals?.isNotEmpty()==true}
        storage.awaitQueuedWrites()
    }
    fun approval()=session.runtime!!.approvals.single()
    fun question()=approval().questions.single()
    fun key(question: Boolean)=if(question)session.questionVoiceIdentity(approval(),question(),session.approvalActionDestination())else session.targetKey
    fun ownerKey(question: Boolean)=NativeVoiceDestinationKeys.owner(key(question),session.selectedClient.operationTargets("thread").toSet())
    fun value(question: Boolean)=if(question)session.questionVoiceDraft(approval(),question(),session.approvalActionDestination()).orEmpty()else session.text
    fun edit(question: Boolean,value: String) {
        if(question)session.setQuestionVoiceDraft(approval(),question(),session.approvalActionDestination(),value)else session.text=value
    }
    val store get()=VoiceRecordingStore(context,storage)
    suspend fun seed(question: Boolean): PendingVoiceRecording {
        edit(question,ORIGINAL);storage.awaitQueuedWrites()
        val scope=AISharingScope(session.selectedClient.scope!!,"codex-dictation");val key=key(question)
        val draftId="voice-v1:"+voiceSHA((scope.key+"\u0000"+key).toByteArray(Charsets.UTF_8))
        val row=store.begin(scope,session.selectedClient.codexDirect?.fingerprint,key,draftId,"thread",ORIGINAL)
        store.file(row.id).writeBytes(WavePCM.header(3200)+ByteArray(3200));val sealed=store.seal(row)
        store.mark(sealed,true,true);return store.current(sealed)
    }
    fun result(original: PendingVoiceRecording,locale: String="en-US")=NativeVoiceLocalResult(UUID.randomUUID().toString(),original.id,NativeVoiceDiscardRequest(original).fingerprint,original.manifest!!.sha256,locale,
        if(locale=="en-US")"vosk-model-small-en-us-0.15"else"vosk-model-small-cn-0.22",
        if(locale=="en-US")"30f26242c4eb449f948e42cb302dd7a686cb29a3423a8367f99ff41780942498"else"3af8b0e7e0f835ae9d414ce5df580237a3cfb08d586c9fbbb0f7ff29ad5b14ba",TRANSCRIPT,1)
    suspend fun begin(question: Boolean): VoiceInputController {
        val controller=if(question)session.questionVoice!!else session.voice!!
        until("actual Session destination admission") {
            !session.loading&&!session.historyLoading&&session.canUseVoice&&
                (!question||session.canBeginQuestionVoice(approval(),question(),session.approvalActionDestination()))
        }
        storage.awaitQueuedWrites()
        if(question)session.beginQuestionVoice(approval(),question(),session.approvalActionDestination(),{value(true)},{edit(true,it)},recoverSavedFile=true)else session.beginVoice(recoverSavedFile=true)
        until("explicit original restore") {controller.phase==VoiceInputController.Phase.FAILED}
        assertTrue(controller.canInspectSavedFile);assertEquals(0,microphoneCalls.get());assertEquals(0,voiceHostCalls.get());return controller
    }
    /** Reuse the real Session owner object, not a synthetic publish Boolean or a fixture metadata map.
     * Only the existing port's executor checkpoint is changed. The frozen source fingerprint gates this reflection.
     */
    fun checkpoint(controller: VoiceInputController,block: (Int)->Unit) {
        val destination=VoiceInputController::class.java.getDeclaredField("localDestination").apply {isAccessible=true}
        val actual=destination.get(controller) as NativeEncryptedVoiceDraftDestinationPort
        val field=NativeEncryptedVoiceDraftDestinationPort::class.java.getDeclaredField("owner").apply {isAccessible=true}
        val owner=field.get(actual) as NativeVoiceDraftOwner
        val controlled=NativeEncryptedVoiceDraftDestinationPort(storage,owner,block)
        assertSame(owner,field.get(controlled));destination.set(controller,controlled)
    }
    suspend fun transcribe(controller: VoiceInputController) {
        controller.chooseSavedFileLocale("en-US");controller.inspectSavedFile();until("explicit probe") {!controller.savedFileBusy}
        assertTrue(controller.canTranscribeSavedFile);controller.transcribeSavedFile()
    }
    suspend fun preserved(original: PendingVoiceRecording,raw: String,audio: ByteArray,originalJson: String) {
        assertEquals(raw,storage.read("voice-recording:"+original.id))
        assertEquals(originalJson,easyCanonical(store.current(original).json()))
        assertArrayEquals(audio,store.bytes(original))
        assertEquals(0,answers.get());assertEquals(0,voiceHostCalls.get());assertEquals(0,microphoneCalls.get())
    }
    suspend fun close() {
        engineRelease?.complete(Unit)
        session.leaveForeground()
        for(controller in listOfNotNull(session.voice,session.questionVoice)) {controller.cancel();controller.quiesceForMigration();controller.retireForMigration()}
        session.abandonMigrationRestore();runCatching {storage.awaitQueuedWrites()};storage.retireWriter()
    }
    class WriteGate(private val storage: NativeSessionStorage) {
        val entered=CountDownLatch(1);val release=CountDownLatch(1)
        val staged=AtomicReference<List<NativeVoiceLocalResult>>()
        fun checkpoint(phase: Int) {if(phase==0){staged.set(NativeVoiceLocalResults.read(storage));entered.countDown();check(release.await(15,TimeUnit.SECONDS)) {"Destination write gate expired"}}}
        suspend fun await()=withContext(Dispatchers.IO) {check(entered.await(5,TimeUnit.SECONDS)) {"Actual destination writer did not enter"}}
    }
}

/** Observes the protected default metadata and permission; never restores or resets them. */
internal class NativeSavedVoiceSessionEvidence(private val context: Context) {
    private val defaults=NativeSessionStorage(context)
    private val fields=listOf("drafts","draftFiles","draftVersions","attachmentImportGenerations","photoSources")
    private val before=defaults.metadata().let {m->fields.associateWith {m?.opt(it)?.toString()}}
    private val permission=context.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
    val folder: File
    init {
        require(context.packageName=="com.easyai.agenthub.android.acceptance")
        val run=InstrumentationRegistry.getArguments().getString("ownerCallbacksRun") ?: "owned-model"
        require(run.matches(Regex("[a-zA-Z0-9_-]{1,80}")))
        folder=File(context.filesDir,"acceptance/P4-owner-callbacks/"+run+"/saved-voice-session-"+UUID.randomUUID()).apply {check(mkdirs())}
    }
    fun write(name: String,body: JSONObject) {require(name.matches(Regex("[a-z0-9-]+")));File(folder,name+".json").writeText(body.put("pid",Process.myPid()).put("realSession",true).put("realEncryptedWriter",true).put("realAcousticEngine",false).put("modelTasks",0).toString())}
    fun verify() {
        val current=defaults.metadata();fields.forEach {assertEquals("Default "+it,before[it],current?.opt(it)?.toString())}
        assertEquals(permission,context.checkSelfPermission(Manifest.permission.RECORD_AUDIO))
        write("default-storage-proof",JSONObject().put("defaultDraftsAndAttachmentsPreserved",true).put("microphonePermissionPreserved",true))
    }
}
