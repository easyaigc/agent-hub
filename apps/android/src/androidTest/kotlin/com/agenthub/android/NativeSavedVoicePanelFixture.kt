package com.agenthub.android

import android.content.Context
import android.content.ContextWrapper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import com.agenthub.core.TargetScope
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/** Fixed test input, independent of controller state. Live Host/microphone rerecord is not authorized. */
internal object NativeSavedVoicePanelInputs {
    const val originalHostRerecordAuthorized=false
    const val localSavedFileRecognitionAuthorized=true
    const val microphoneInvocationsAuthorized=false
    const val rerecordReason="This recovery fixture has no original sharing/Host rerecord authority; live rerecord acceptance is separate."
    val expectedRerecordEnabled get()=originalHostRerecordAuthorized
    fun json()=JSONObject().put("originalHostRerecordAuthorized",originalHostRerecordAuthorized)
        .put("localSavedFileRecognitionAuthorized",localSavedFileRecognitionAuthorized)
        .put("microphoneInvocationsAuthorized",microphoneInvocationsAuthorized)
        .put("expectedRerecordEnabled",expectedRerecordEnabled).put("reason",rerecordReason)
}

/** Real encrypted original/destination owners; an explicitly synthetic resource/worker gate only. */
internal class NativeSavedVoicePanelFixture private constructor(val context: Context,val root: File) {
    private val local=object: ContextWrapper(context) {
        override fun getApplicationContext(): Context=this
        override fun getNoBackupFilesDir()=File(root,"no-backup").apply {check(mkdirs()||isDirectory);android.system.Os.chmod(path,0x1c0)}
    }
    val inputs=NativeSavedVoicePanelInputs
    val alias="agent-hub-panel-"+root.name
    val storage=NativeSessionStorage(local,"original",alias)
    val store=VoiceRecordingStore(local,storage)
    val hostCalls=AtomicInteger();val micCalls=AtomicInteger();var discardCalls=0
    val controller=VoiceInputController(local,store){hostCalls.incrementAndGet();error("Panel fixture cannot create Host commands")}
    class Engine: NativeSavedVoiceSpeech {
        companion object {const val LATE_TEXT="synthetic late success / 合成迟到成功"}
        var availability=SavedVoiceAvailability.MISSING
        var returnSuccess=false;var successfulReturns=0;var forwardedSuccessfulReturns=0
        var returnedText: SavedVoiceText?=null
        var probes=0;var installs=0;@Volatile var decodes=0;@Volatile var actualExit=false
        var gate: CompletableDeferred<Unit>?=null
        override suspend fun probe(locale: String): SavedVoiceProbe {require(locale in setOf("zh-CN","en-US"));probes++;return SavedVoiceProbe(availability)}
        override suspend fun install(locale: String,checkpoint: suspend ()->Unit) {
            installs++;actualExit=false
            try {withContext(NonCancellable){checkNotNull(gate).await()};checkpoint();availability=SavedVoiceAvailability.INSTALLED}
            finally {actualExit=true}
        }
        override suspend fun transcribe(locale: String,wav: ByteArray,checkpoint: suspend ()->Unit): SavedVoiceText {
            decodes++;actualExit=false;SavedVoicePcm.validate(wav)
            try {
                if(returnSuccess) {
                    val value=withContext(NonCancellable) {
                        checkNotNull(gate).await()
                        when(locale) {
                            "en-US"->SavedVoiceText(LATE_TEXT,"vosk-model-small-en-us-0.15","30f26242c4eb449f948e42cb302dd7a686cb29a3423a8367f99ff41780942498")
                            "zh-CN"->SavedVoiceText(LATE_TEXT,"vosk-model-small-cn-0.22","3af8b0e7e0f835ae9d414ce5df580237a3cfb08d586c9fbbb0f7ff29ad5b14ba")
                            else->error("Unsupported synthetic locale")
                        }
                    }
                    returnedText=value;successfulReturns++;return value
                }
                withContext(NonCancellable){checkNotNull(gate).await()};checkpoint();error("Synthetic worker must be cancelled before any result")
            } finally {actualExit=true}
        }
    }
    val engine=Engine()
    var ownerCurrent by mutableStateOf(true)
    private val observedEngine=object: NativeSavedVoiceSpeech by engine {
        override suspend fun transcribe(locale: String,wav: ByteArray,checkpoint: suspend ()->Unit): SavedVoiceText {
            val value=engine.transcribe(locale,wav,checkpoint)
            engine.forwardedSuccessfulReturns++
            return value
        }
    }
    lateinit var draft: VoiceDraft;private set
    lateinit var original: PendingVoiceRecording;private set
    private lateinit var originalJson: String
    private lateinit var originalRow: String
    private lateinit var metadata: String
    private lateinit var audio: ByteArray
    private fun canonical(value: Any?): String=when(value) {
        null,JSONObject.NULL->"null"
        is JSONObject->value.keys().asSequence().toList().sorted().joinToString(",","{","}"){JSONObject.quote(it)+":"+canonical(value.get(it))}
        is JSONArray->(0 until value.length()).joinToString(",","[","]"){canonical(value.get(it))}
        is String->JSONObject.quote(value)
        is Boolean,is Int,is Long->value.toString()
        else->error("Unexpected original JSON value")
    }
    private suspend fun prepare() {
        val target=TargetScope("codex","historical-account","historical-subject","panel-host","panel-workspace","panel-runtime")
        val scope=AISharingScope(target,"codex-dictation")
        val client=MobileClient(target.hostId,"Synthetic panel",target,"https://panel.invalid")
        val text=(1..12).joinToString("\n"){"Original draft $it / 原草稿第${it}行：保留全部文字。"}
        draft=VoiceDraft(client,scope,client.cacheKey("panel-thread"),"original-version","panel-draft","panel-thread",text)
        val pending=store.begin(scope,draft.certificate,draft.key,draft.draftId,draft.threadId,text)
        store.file(pending.id).writeBytes(WavePCM.header(3200)+ByteArray(3200){(it%127).toByte()})
        val sealed=store.seal(pending)
        store.mark(sealed,true,true,VoiceReceipt(sealed.manifest!!,"outcome-unknown",null,null,"2030-01-01T00:00:00Z"))
        original=store.current(sealed);originalJson=canonical(original.json());originalRow=storage.read("voice-recording:${original.id}")!!;audio=store.bytes(original)
        val destination=NativeSavedVoiceDestinationFixture(storage,draft){error("Cancellation/component checks cannot publish a draft")}
        metadata=storage.read("metadata")!!
        controller.savedFileFactory={observedEngine};controller.systemFactory={micCalls.incrementAndGet();error("Panel fixture cannot start a microphone")}
        controller.localResultPort=NativeEncryptedVoiceLocalResultPort(storage)
        controller.discardPort=NativeVoiceDiscardPort {request->discardCalls++;store.discardLocal(request){}}
        controller.start(draft,{inputs.originalHostRerecordAuthorized},{_,_->error("No desktop draft callback")},restoreOnly=true,localAllowed={inputs.localSavedFileRecognitionAuthorized&&ownerCurrent},localDestination=destination.port)
        until {controller.phase==VoiceInputController.Phase.FAILED}
        check(store.restore(draft.scope,draft.certificate,draft.key,draft.text)?.id==original.id) {"The exact original must restore through the actual draft certificate"}
        check(controller.canInspectSavedFile) {"A generic FAILED phase is not proof of saved-original recovery"}
        controller.chooseSavedFileLocale("zh-CN")
        NativeRealFileAsrContract.immutable(File(root,"original.json"),JSONObject().put("canonicalOriginal",originalJson).put("persistedRow",originalRow).put("metadata",metadata).put("fixtureInputs",inputs.json()))
    }
    suspend fun preserved(): JSONObject {
        val current=store.current(original);val bytes=store.bytes(current)
        try {
            assertEquals("Whole canonical DTO, including complete target/account and attempt fields",originalJson,canonical(current.json()))
            assertEquals("Exact persisted original row including its unknown receipt",originalRow,storage.read("voice-recording:${original.id}"))
            assertArrayEquals("Every encrypted-original WAV byte",audio,bytes)
            assertEquals("Original draft/version metadata",metadata,storage.read("metadata"))
            assertEquals(0,hostCalls.get());assertEquals(0,micCalls.get());assertNull(controller.permissionRequest)
            return JSONObject().put("canonicalOriginalSha256",voiceSHA(originalJson.toByteArray())).put("rowSha256",voiceSHA(originalRow.toByteArray()))
                .put("audioSha256",voiceSHA(bytes)).put("metadataSha256",voiceSHA(metadata.toByteArray())).put("exactStringsAndFullWavCompared",true)
                .put("fixtureInputs",inputs.json()).put("fixtureDirectory",root.name).put("keystoreAlias",alias).put("originalId",original.id)
                .put("hostFactoryCalls",hostCalls.get()).put("microphoneFactoryCalls",micCalls.get()).put("syntheticEngine",true)
        }finally{bytes.fill(0)}
    }
    suspend fun cleanup(): JSONObject {
        try {
            engine.gate?.complete(Unit);controller.cancel();controller.quiesceForMigration();controller.retireForMigration()
            return preserved() // Last comparison uses the action-before baseline, never a fresh read as expected data.
        }finally{audio.fill(0)}
    }
    companion object {
        suspend fun until(value: ()->Boolean)=withTimeout(10_000){while(!value())delay(5)}
        suspend fun create(context: Context,parent: File): NativeSavedVoicePanelFixture {
            require(context.packageName=="com.easyai.agenthub.android.acceptance")
            val root=File(parent,UUID.randomUUID().toString());check(root.mkdir());android.system.Os.chmod(root.path,0x1c0)
            return NativeSavedVoicePanelFixture(context,root).also {it.prepare()}
        }
    }
}
