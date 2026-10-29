package com.agenthub.android

import android.content.Context
import android.os.Process
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import android.util.AtomicFile
import android.util.Base64
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Only fresh synthetic namespaces. Proofs are immutable except one bounded heartbeat file. */
internal class NativeSavedVoicePostWriteProtocol(val context: Context) {
    init {require(context.packageName=="com.easyai.agenthub.android.acceptance")}
    val run=requireNotNull(InstrumentationRegistry.getArguments().getString("savedVoicePostWriteRun")) {"Fresh parent-owned UUID required"}
        .also {require(UUID.fromString(it).toString()==it)}
    val boundary=(InstrumentationRegistry.getArguments().getString("savedVoicePostWriteBoundary") ?: "1").toInt().also {require(it in 1..3)}
    val folder=File(context.filesDir,"acceptance/saved-voice-postwrite-"+run)
    private val defaultMetadata=NativeSessionStorage(context,existingKeyOnly=true).read("metadata")
    val defaultMetadataHash: Any get()=defaultMetadata?.let {voiceSHA(it.toByteArray(Charsets.UTF_8))} ?: JSONObject.NULL
    fun namespace(question: Boolean)="P7-saved-postwrite-"+(if(question)"q-"else"c-")+run
    fun storage(question: Boolean,readOnlyKey: Boolean=false): NativeSessionStorage {
        val name=namespace(question);require(File(context.noBackupFilesDir,name).isDirectory)
        return NativeSessionStorage(context,name,name,existingKeyOnly=readOnlyKey)
    }
    fun fresh() {
        require(!folder.exists()) {"Prior writer intent exists; reconcile it instead of replaying"}
        for(question in listOf(false,true))require(!File(context.noBackupFilesDir,namespace(question)).exists())
        check(folder.mkdirs());Os.chmod(folder.path,0x1c0)
    }
    fun file(name: String): File {
        require(name.matches(Regex("[a-z0-9-]+")));val f=File(folder,name+".json")
        require(requireNotNull(f.parentFile).canonicalFile==folder.canonicalFile);return f
    }
    fun publish(name: String,value: JSONObject,heartbeat: Boolean=false) {
        require(folder.isDirectory)
        val f=file(name);if(!heartbeat)require(!f.exists()&&!File(f.path+".bak").exists()) {"Immutable post-write proof exists"}
        val bytes=value.toString().toByteArray(Charsets.UTF_8);require(bytes.size<=256*1024)
        val atomic=AtomicFile(f);val out=atomic.startWrite()
        try {out.write(bytes);out.fd.sync();atomic.finishWrite(out);Os.chmod(f.path,0x180);syncDirectory(folder)}catch(t: Throwable){atomic.failWrite(out);throw t}
    }
    fun read(name: String): JSONObject {
        val f=file(name)
        if(name=="heartbeat") {
            // API26 AtomicFile may leave a partial base plus a complete backup when killed mid-heartbeat.
            // Select the last complete observation without repairing or rewriting either file.
            for(candidate in listOf(File(f.path+".bak"),f)) {
                val value=runCatching {require(candidate.isFile&&candidate.length() in 1..256*1024);StrictMigrationJson.parse(candidate.readBytes())}.getOrNull()
                if(value!=null)return value
            }
            error("No complete post-write heartbeat")
        }
        require(f.isFile&&f.length() in 1..256*1024)
        return StrictMigrationJson.parse(f.readBytes())
    }
    fun hash(name: String)=voiceSHA(file(name).readBytes())
    fun header(kind: String)=JSONObject().put("version",1).put("kind",kind).put("run",run).put("boundary",boundary)
        .put("pid",Process.myPid()).put("package",context.packageName).put("modelTasks",0).put("microphone",0).put("HostOperations",0)
    fun verifyHeader(v: JSONObject,kind: String) {require(v.get("version")==1&&v.get("kind")==kind&&v.get("run")==run&&v.get("boundary")==boundary&&v.get("package")==context.packageName)}
    fun claim(name: String,readySha256: String) = publish(name,header(name).put("readySha256",readySha256))
    fun requireKilledWriter(): JSONObject {
        require(!file("writer-ended").exists()) {"Writer exited its gate; this is not the requested process-termination window"}
        val ready=read("ready");verifyHeader(ready,"ready")
        assertNotEquals(ready.getInt("pid"),Process.myPid())
        val intent=read("intent");verifyHeader(intent,"intent");require(intent.getInt("pid")==ready.getInt("pid"))
        require(ready.getString("intentSha256")==hash("intent")&&ready.getString("inputsSha256")==hash("inputs"))
        require(ready.getString("composerObservationSha256")==hash("composer-observation")&&ready.getString("questionObservationSha256")==hash("question-observation"))
        val heartbeat=read("heartbeat");verifyHeader(heartbeat,"heartbeat")
        require(heartbeat.getInt("pid")==ready.getInt("pid")&&heartbeat.getBoolean("bothGatesHeld")&&heartbeat.getString("readySha256")==hash("ready"))
        require(heartbeat.getLong("elapsed")<ready.getLong("expiresElapsed"))
        assertEquals(ready.get("defaultMetadataSha256"),defaultMetadataHash)
        return ready
    }
    fun baselines(): List<NativeSavedVoicePostWriteBaseline> {
        val root=read("inputs");verifyHeader(root,"inputs");val rows=root.getJSONArray("cases");require(rows.length()==2)
        val result=(0 until rows.length()).map {NativeSavedVoicePostWriteBaseline.decode(rows.getJSONObject(it),this)}
        require(result.map {it.question}.toSet()==setOf(false,true));return result
    }
    fun protectDefault(evidence: NativeSavedVoiceSessionEvidence) {
        assertEquals(defaultMetadata,NativeSessionStorage(context,existingKeyOnly=true).read("metadata"))
        evidence.verify();val f=File(evidence.folder,"default-storage-proof.json")
        evidence.write("postwrite-full-default-metadata",JSONObject().put("completeDefaultMetadataUnchanged",true).put("metadataSha256",defaultMetadataHash))
        for(proof in listOf(f,File(evidence.folder,"postwrite-full-default-metadata.json")))FileOutputStream(proof,true).use {it.fd.sync()}
        syncDirectory(evidence.folder)
    }
    private fun syncDirectory(dir: File) {
        require(dir.isDirectory)
        val descriptor=Os.open(dir.path,OsConstants.O_RDONLY,0)
        try {Os.fsync(descriptor)}finally{Os.close(descriptor)}
    }
}

internal data class NativeSavedVoicePostWriteBaseline(val question: Boolean,val namespace: String,val original: PendingVoiceRecording,
    val raw: String,val canonical: String,val audio: ByteArray,val catalog: String,val ownerKey: String,val sourceVersion: String) {
    fun json()=JSONObject().put("question",question).put("namespace",namespace).put("originalRow",raw).put("canonicalOriginal",canonical)
        .put("audio",Base64.encodeToString(audio,Base64.NO_WRAP)).put("catalog",catalog).put("ownerKey",ownerKey).put("sourceVersion",sourceVersion)
    val staged get()=NativeVoiceLocalResults.decode(catalog).single()
    /** Direct synchronous reads are reentrant at the actual executor checkpoint's ioLock. */
    fun preserved(storage: NativeSessionStorage) {
        val row=storage.read("voice-recording:"+original.id)!!;assertEquals(raw,row)
        val current=NativeVoiceRecordingRecord.decode(StrictMigrationJson.parse(row.toByteArray(Charsets.UTF_8)))
        assertEquals(canonical,easyCanonical(current.json()))
        val bytes=storage.readBinary("voice-audio:"+original.id,original.manifest!!.sizeBytes)
        try {assertArrayEquals(audio,bytes)}finally{bytes.fill(0)}
        assertEquals(catalog,storage.read(NativeVoiceLocalResults.RECORD))
        assertFalse(NativeVoiceLocalResults.read(storage).single().applied)
    }
    companion object {
        fun decode(v: JSONObject,p: NativeSavedVoicePostWriteProtocol): NativeSavedVoicePostWriteBaseline {
            require(v.keys().asSequence().toSet()==setOf("question","namespace","originalRow","canonicalOriginal","audio","catalog","ownerKey","sourceVersion"))
            val question=v.get("question") as Boolean;require(v.getString("namespace")==p.namespace(question))
            val raw=v.getString("originalRow");val original=NativeVoiceRecordingRecord.decode(StrictMigrationJson.parse(raw.toByteArray(Charsets.UTF_8)))
            require(original.originalText==NativeSavedVoiceSessionFixture.ORIGINAL&&original.threadId=="thread"&&original.scope.target.hostId=="saved-session-host")
            val canonical=v.getString("canonicalOriginal");require(canonical==easyCanonical(original.json()))
            val encoded=v.getString("audio");val audio=Base64.decode(encoded,Base64.NO_WRAP)
            require(Base64.encodeToString(audio,Base64.NO_WRAP)==encoded);original.manifest!!.verify(audio)
            val catalog=v.getString("catalog");val result=NativeVoiceLocalResults.decode(catalog).single();result.validate(original)
            require(!result.applied&&result.destination==null&&result.text==NativeSavedVoiceSessionFixture.TRANSCRIPT&&result.locale=="en-US")
            val owner=v.getString("ownerKey");require(owner==NativeVoiceDestinationKeys.owner(original.draftKey))
            require(NativeVoiceDestinationKeys.isQuestion(original.draftKey)==question)
            val version=v.getString("sourceVersion");require(UUID.fromString(version).toString()==version)
            return NativeSavedVoicePostWriteBaseline(question,p.namespace(question),original,raw,canonical,audio,catalog,owner,version)
        }
    }
}

/** Never releases on a successful path. Parent must terminate the exact held writer process. */
internal class NativeSavedVoicePostWriteGate(private val protocol: NativeSavedVoicePostWriteProtocol,val baseline: NativeSavedVoicePostWriteBaseline,
    private val storage: NativeSessionStorage) {
    val release=CountDownLatch(1)
    val held=AtomicBoolean(false)
    val observation=AtomicReference<String>()
    @Volatile var expiresElapsed=0L
    fun checkpoint(phase: Int) {if(protocol.boundary in 1..2&&phase==protocol.boundary)captureAndHold(null)}
    fun captureAndHold(receipt: NativeVoiceDraftReceipt?): Nothing=storage.operationTransaction {
        check(observation.get()==null);baseline.preserved(storage)
        val metadata=storage.read("metadata")!!;val root=StrictMigrationJson.parse(metadata.toByteArray(Charsets.UTF_8))
        assertEquals(NativeSavedVoiceSessionFixture.ACCEPTED,root.getJSONObject("drafts").getString(baseline.ownerKey))
        val version=root.getJSONObject("draftVersions").getString(baseline.ownerKey);require(UUID.fromString(version).toString()==version)
        assertNotEquals(baseline.sourceVersion,version)
        if(protocol.boundary==3) {
            checkNotNull(receipt).validate(baseline.original,baseline.staged)
            assertEquals(baseline.sourceVersion,receipt.sourceVersion);assertEquals(version,receipt.acceptedVersion)
            assertEquals(baseline.ownerKey,receipt.ownerKey);assertEquals(voiceSHA(metadata.toByteArray(Charsets.UTF_8)),receipt.metadataSha256)
        }else assertNull(receipt)
        expiresElapsed=SystemClock.elapsedRealtime()+120_000
        val proof=protocol.header("observation").put("question",baseline.question).put("namespace",baseline.namespace)
            .put("metadata",metadata).put("metadataSha256",voiceSHA(metadata.toByteArray(Charsets.UTF_8))).put("acceptedVersion",version)
            .put("actionId",baseline.staged.actionId).put("recordingId",baseline.original.id).put("destinationReceipt",receipt?.json() ?: JSONObject.NULL)
            .put("rootCiphertext",JSONObject(MigrationActivationFixtures.tree(storage))).put("expiresElapsed",expiresElapsed)
        protocol.publish(if(baseline.question)"question-observation"else"composer-observation",proof)
        observation.set(proof.toString());held.set(true)
        try {
            check(!release.await(120,TimeUnit.SECONDS)) {"Writer gate released without process termination"}
            error("Parent did not terminate this writer within its bounded gate")
        }finally{held.set(false)}
    }
}
