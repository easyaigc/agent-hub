package com.agenthub.android

import android.content.Context
import android.util.Base64
import com.agenthub.core.VoiceTranscriptionRules
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.util.UUID

data class PendingVoiceRecording(val id: String,val scope: AISharingScope,val certificate: String?,val draftKey: String,
    val draftId: String,val threadId: String?,val originalText: String,val createdAt: Long,val manifest: VoiceManifest? = null,
    val prepareAttempted: Boolean = false,val audioAttempted: Boolean = false,val migrationReadOnly: Boolean = false) {
    fun json()=JSONObject().put("version",1).put("id",id).put("scope",scope.json()).put("certificate",certificate ?: JSONObject.NULL)
        .put("draftKey",draftKey).put("draftId",draftId).put("threadId",threadId ?: JSONObject.NULL).put("originalText",originalText)
        .put("createdAt",createdAt).put("manifest",manifest?.json() ?: JSONObject.NULL).put("prepareAttempted",prepareAttempted).put("audioAttempted",audioAttempted).also {if(migrationReadOnly)it.put("migrationReadOnly",true)}
}

/** Closed local/migration reader; no permissive coercion or unknown terminal fields in original rows. */
internal object NativeVoiceRecordingRecord {
    fun decode(v: JSONObject): PendingVoiceRecording {
        val required=setOf("version","id","scope","certificate","draftKey","draftId","threadId","originalText","createdAt","manifest","prepareAttempted","audioAttempted")
        val optional=setOf("audioStorage","audio","phase","expiresAt","migrationReadOnly")
        val keys=v.keys().asSequence().toSet();require(keys.containsAll(required)&&keys.all {it in required||it in optional}&&v.get("version")==1)
        fun text(key: String,max: Int,nullable: Boolean=false): String? {
            val value=v.get(key);if(nullable&&value==JSONObject.NULL)return null
            return (value as String).also {require(it.length<=max)}
        }
        val id=v.get("id") as String;require(UUID.fromString(id).toString()==id)
        val created=v.get("createdAt");require(created is Int||created is Long);require((created as Number).toLong() in 0..9_007_199_254_740_991L)
        val scopeValue=v.getJSONObject("scope");require(scopeValue.get("version")==1);val scope=AISharingScope.decode(scopeValue)
        val manifest=if(v.get("manifest")==JSONObject.NULL)null else VoiceCodec.manifest(v.getJSONObject("manifest")).also {require(VoiceTranscriptionRules.validateManifest(it.json().toString()))}
        val value=PendingVoiceRecording(id,scope,text("certificate",8192,true),text("draftKey",8192)!!,text("draftId",2048)!!,
            text("threadId",256,true),text("originalText",100_000)!!,(created as Number).toLong(),manifest,v.get("prepareAttempted") as Boolean,
            v.get("audioAttempted") as Boolean,if(v.has("migrationReadOnly"))v.get("migrationReadOnly") as Boolean else false)
        require(manifest==null||manifest.operationId==id&&manifest.hostId==scope.target.hostId&&manifest.draftId==value.draftId&&manifest.threadId==value.threadId)
        require(v.has("phase")==v.has("expiresAt"))
        if(v.has("phase")){require(v.get("phase") in setOf("awaiting-audio","transcribing","completed","failed","outcome-unknown"));java.time.Instant.parse(text("expiresAt",256)!!)}
        if(v.has("audioStorage"))require(v.get("audioStorage")=="binary-chunks-v1"&&!v.has("audio"))
        if(v.has("audio"))require(v.get("audio") is String&&!v.has("audioStorage"))
        if(manifest==null)require(!v.has("audioStorage")&&!v.has("audio"))
        return value
    }
}

/** Only this installation can decrypt sealed audio. A provisional WAV can be recovered after process death before any upload. */
class VoiceRecordingStore(context: Context,private val storage: NativeSessionStorage) {
    private val directory=File(context.noBackupFilesDir,"voice-recordings-v1").apply { check(mkdirs() || isDirectory);android.system.Os.chmod(path,0x1c0) }
    private fun key(id: String)="voice-recording:$id"
    private fun index()=storage.migrationReadTextRecords(setOf("voice-index-v1"))["voice-index-v1"]?.let { raw->
        (StrictMigrationJson.value(raw.toByteArray(Charsets.UTF_8)) as JSONArray).let {a->(0 until a.length()).map {a.get(it) as String}}
            .also {ids->require(ids.size<=32&&ids.distinct().size==ids.size);ids.forEach(::validId)}
    }.orEmpty()
    private fun validId(id: String) { require(UUID.fromString(id).toString()==id) }
    fun file(id: String): File { validId(id);return File(directory,"$id.wav") }
    private fun decode(v: JSONObject)=NativeVoiceRecordingRecord.decode(v)
    private fun raw(id: String)=storage.migrationReadTextRecords(setOf(key(id)))[key(id)]?:error("原录音记录不存在")
    private fun read(id: String)=StrictMigrationJson.parse(raw(id).toByteArray(Charsets.UTF_8))
    private fun terminal(current: PendingVoiceRecording,catalog: Map<String,NativeVoiceDiscardMarker>): Boolean {
        val marker=catalog[current.id]?:return false;NativeVoiceDiscardCatalog.validate(storage,marker,current);return true
    }
    /** Inventory only: a raw WAV is not a sealed manifest and is never implicitly repaired for export. */
    internal fun migrationProvisionalIds(): List<String> = storage.operationTransaction {
        val catalog=NativeVoiceDiscardCatalog.read(storage)
        (index()+catalog.keys).distinct().filter { id->val current=decode(read(id));current.manifest==null&&!terminal(current,catalog) }
    }
    suspend fun begin(scope: AISharingScope,certificate: String?,draftKey: String,draftId: String,threadId: String?,text: String): PendingVoiceRecording = withContext(Dispatchers.IO) {
        storage.operationTransaction {
            val catalog=NativeVoiceDiscardCatalog.read(storage)
            val ids=index().filterNot {terminal(decode(read(it)),catalog)}
            check(ids.size<32) { "待处理录音过多，请先处理原录音。" }
            val pending=PendingVoiceRecording(UUID.randomUUID().toString(),scope,certificate,draftKey,draftId,threadId,text,System.currentTimeMillis())
            // Register first: an interrupted save cannot create an unindexed audio file.
            storage.write("voice-index-v1",JSONArray(ids+pending.id).toString())
            storage.write(key(pending.id),pending.json().toString());pending
        }
    }
    suspend fun seal(pending: PendingVoiceRecording): PendingVoiceRecording = withContext(Dispatchers.IO) {
        storage.operationTransaction {
            val body=read(pending.id);val current=decode(body)
            check(!terminal(current,NativeVoiceDiscardCatalog.read(storage))) { "录音已明确丢弃，原证据已保留。" }
            check(!current.migrationReadOnly) { "迁入的原录音仅核对原回执，音频已保留。" }
            if(current.manifest!=null) return@operationTransaction current
            val audio=file(current.id);require(audio.isFile && audio.length() in 46..VoiceTranscriptionRules.maxBytes.toLong()) { "尚无可恢复的完整录音，原记录已保留。" }
            val dataBytes=((audio.length()-44)/2*2).toInt()
            require(dataBytes in 2..9_600_000) { "录音超出五分钟限制" }
            RandomAccessFile(audio,"rw").use { out->out.setLength(44L+dataBytes);out.seek(0);out.write(WavePCM.header(dataBytes));out.fd.sync() }
            val digest=java.security.MessageDigest.getInstance("SHA-256")
            audio.inputStream().use { input->val chunk=ByteArray(16384);while(true) { val count=input.read(chunk);if(count<0) break;digest.update(chunk,0,count) } }
            val manifest=VoiceManifest(current.scope.target.hostId,current.threadId,current.draftId,current.id,audio.length().toInt(),digest.digest().joinToString("") { "%02x".format(it) },(dataBytes*1000L/32000).toInt().coerceAtLeast(1))
            require(VoiceTranscriptionRules.validateManifest(manifest.json().toString()))
            val sealed=current.copy(manifest=manifest)
            // Commit encrypted audio before its metadata. A failed/interrupted seal retains the provisional original.
            storage.writeBinary("voice-audio:${current.id}",audio)
            val next=sealed.json().put("audioStorage","binary-chunks-v1")
            storage.write(key(current.id),next.toString())
            check(audio.delete() || !audio.exists()) { "录音已加密保存，临时文件尚未清理" };sealed
        }
    }
    suspend fun restore(scope: AISharingScope,certificate: String?,draftKey: String,text: String): PendingVoiceRecording? = withContext(Dispatchers.IO) {
        storage.operationTransaction {
            val catalog=NativeVoiceDiscardCatalog.read(storage)
            index().map {id->decode(read(id))}.filterNot {terminal(it,catalog)}
                .filter { it.scope.matches(scope) && it.certificate==certificate && it.draftKey==draftKey && it.originalText==text }
                .maxByOrNull { it.createdAt }
        }
    }
    suspend fun bytes(pending: PendingVoiceRecording): ByteArray = withContext(Dispatchers.IO) {
        storage.operationTransaction {
            val body=read(pending.id);val current=decode(body)
            require(current.manifest==pending.manifest && current.scope.matches(pending.scope) && current.draftKey==pending.draftKey)
            val bytes=if(body.optString("audioStorage")=="binary-chunks-v1") storage.readBinary("voice-audio:${pending.id}",current.manifest!!.sizeBytes)
                else Base64.decode(body.getString("audio"),Base64.NO_WRAP) // Existing development recordings retain their exact original.
            current.manifest!!.verify(bytes);bytes
        }
    }
    suspend fun current(pending: PendingVoiceRecording): PendingVoiceRecording = withContext(Dispatchers.IO) {
        storage.operationTransaction { decode(read(pending.id)).also { require(it.manifest==pending.manifest && it.scope.matches(pending.scope) && it.certificate==pending.certificate && it.draftKey==pending.draftKey && it.originalText==pending.originalText) } }
    }
    suspend fun mark(pending: PendingVoiceRecording,prepareAttempted: Boolean=false,audioAttempted: Boolean=false,receipt: VoiceReceipt?=null) = withContext(Dispatchers.IO) {
        storage.operationTransaction {
            val body=read(pending.id);val current=decode(body);require(current.manifest==pending.manifest)
            // A late original receipt may update current status, never the independent terminal/audit.
            terminal(current,NativeVoiceDiscardCatalog.read(storage))
            body.put("prepareAttempted",current.prepareAttempted || prepareAttempted).put("audioAttempted",current.audioAttempted || audioAttempted)
            if(receipt!=null) { require(receipt.manifest==current.manifest);body.put("phase",receipt.phase).put("expiresAt",receipt.expiresAt) }
            storage.write(key(pending.id),body.toString())
        }
    }
    suspend fun remove(pending: PendingVoiceRecording,unsealedOnly: Boolean=false)=withContext(Dispatchers.IO) {
        storage.operationTransaction {
            val body=storage.migrationReadTextRecords(setOf(key(pending.id)))[key(pending.id)]?.let {StrictMigrationJson.parse(it.toByteArray(Charsets.UTF_8))} ?: return@operationTransaction
            val current=decode(body)
            if(terminal(current,NativeVoiceDiscardCatalog.read(storage)))return@operationTransaction
            // Cancel may race the durable seal. End waiting while retaining an already sealed/attempted original.
            if(unsealedOnly && (current.manifest!=null || current.prepareAttempted || current.audioAttempted)) return@operationTransaction
            storage.write("voice-index-v1",JSONArray(index().filterNot { it==pending.id }).toString())
            storage.remove(key(pending.id));storage.remove("voice-audio:${pending.id}")
            // Imported IDs do not grant ownership of this installation's global raw directory.
            // The source installation/namespace may still own an interrupted provisional WAV.
            if(!current.migrationReadOnly) {val audio=file(pending.id);check(audio.delete() || !audio.exists())}
        }
    }
    /** The catalog is the only commit. Staged original/raw evidence is immutable per attempt. */
    internal suspend fun discardLocal(request: NativeVoiceDiscardRequest,checkpoint: (Int)->Unit): NativeVoiceDiscardReceipt=withContext(Dispatchers.IO) {
        val caller=currentCoroutineContext()
        storage.operationTransaction {
            caller.ensureActive();validId(request.recordingId)
            val original=raw(request.recordingId);val current=decode(StrictMigrationJson.parse(original.toByteArray(Charsets.UTF_8)))
            require(request.matches(current)) { "Discard target or original recording changed" }
            val catalog=NativeVoiceDiscardCatalog.read(storage)
            catalog[current.id]?.let {marker->NativeVoiceDiscardCatalog.validate(storage,marker,current)
                return@operationTransaction NativeVoiceDiscardReceipt(current.id,request.fingerprint,true)}
            require(current.id in index());check(catalog.size<NativeVoiceDiscardCatalog.MAX_ENTRIES) { "本地丢弃审计容量已满，原录音已保留。" }
            val auditId=UUID.randomUUID().toString();val auditName="${NativeVoiceDiscardCatalog.AUDIT_PREFIX}${current.id}:$auditId"
            val created=mutableListOf<String>();var committed=false
            try {
                created.add(auditName);storage.write(auditName,original)
                require(storage.migrationReadTextRecords(setOf(auditName))[auditName]==original);checkpoint(1)
                val evidence=if(current.manifest!=null) {
                    val probe=NativeMigrationProbe()
                    val row=StrictMigrationJson.parse(original.toByteArray(Charsets.UTF_8))
                    if(row.optString("audioStorage")=="binary-chunks-v1")storage.copyBinaryTo("voice-audio:${current.id}",current.manifest.sizeBytes,probe)
                    else {val bytes=NativeMigrationRecords.bytes(row.getString("audio"));try{probe.write(bytes)}finally{bytes.fill(0)}}
                    require(probe.size==current.manifest.sizeBytes.toLong()&&probe.sha256()==current.manifest.sha256)
                    NativeVoiceDiscardEvidence("sealed")
                }else {
                    require(!current.migrationReadOnly&&!current.prepareAttempted&&!current.audioAttempted)
                    val source=file(current.id);val exists=java.nio.file.Files.exists(source.toPath(),java.nio.file.LinkOption.NOFOLLOW_LINKS)
                    if(!exists)NativeVoiceDiscardEvidence("absent")else {
                        require(source.isFile&&source.canonicalFile==File(directory.canonicalFile,source.name))
                        if(source.length()==0L)NativeVoiceDiscardEvidence("empty")else {
                            fun probe(): Pair<Int,String> {
                                require(source.length() in 1..VoiceTranscriptionRules.maxBytes.toLong());val probe=NativeMigrationProbe()
                                source.inputStream().use {input->val bytes=ByteArray(65536);while(true){val n=input.read(bytes);if(n<0)break;require(probe.size+n<=VoiceTranscriptionRules.maxBytes);probe.write(bytes,0,n)}}
                                return probe.size.toInt() to probe.sha256()
                            }
                            val before=probe();val payload=UUID.randomUUID().toString();val name="${NativeVoiceDiscardCatalog.RAW_PREFIX}${current.id}:$payload"
                            created.add(name);storage.writeBinary(name,source)
                            val observed=NativeMigrationProbe();storage.copyBinaryTo(name,before.first,observed)
                            require(observed.size==before.first.toLong()&&observed.sha256()==before.second&&probe()==before)
                            NativeVoiceDiscardEvidence("raw",payload,before.first,before.second)
                        }
                    }
                }
                checkpoint(2);val marker=NativeVoiceDiscardMarker(current.id,request.fingerprint,request.original.manifest!=null,auditId,
                    voiceSHA(original.toByteArray(Charsets.UTF_8)),System.currentTimeMillis(),evidence)
                NativeVoiceDiscardCatalog.original(marker,original,current);caller.ensureActive();checkpoint(0)
                storage.write(NativeVoiceDiscardCatalog.RECORD,NativeVoiceDiscardCatalog.encode(catalog.values+marker));checkpoint(-1)
                val saved=NativeVoiceDiscardCatalog.read(storage).getValue(current.id);require(saved==marker)
                NativeVoiceDiscardCatalog.validate(storage,saved,current);committed=true
                NativeVoiceDiscardReceipt(current.id,request.fingerprint,true)
            } finally {
                if(!committed) {
                    val observed=runCatching {NativeVoiceDiscardCatalog.read(storage)[current.id]}
                    // An ambiguous/post-commit acknowledgement retains possibly committed evidence.
                    if(observed.isSuccess&&observed.getOrNull()?.auditId!=auditId)created.forEach {storage.remove(it)}
                }
            }
        }
    }
}

internal object WavePCM {
    fun header(size: Int)=java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN).apply {
        put("RIFF".toByteArray(Charsets.US_ASCII));putInt(size+36);put("WAVEfmt ".toByteArray(Charsets.US_ASCII));putInt(16)
        putShort(1);putShort(1);putInt(16000);putInt(32000);putShort(2);putShort(16);put("data".toByteArray(Charsets.US_ASCII));putInt(size)
    }.array()
}
