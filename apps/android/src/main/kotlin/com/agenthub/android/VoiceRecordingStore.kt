package com.agenthub.android

import android.content.Context
import android.util.Base64
import com.agenthub.core.VoiceTranscriptionRules
import kotlinx.coroutines.Dispatchers
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

/** Only this installation can decrypt sealed audio. A provisional WAV can be recovered after process death before any upload. */
class VoiceRecordingStore(context: Context,private val storage: NativeSessionStorage) {
    private val directory=File(context.noBackupFilesDir,"voice-recordings-v1").apply { check(mkdirs() || isDirectory);android.system.Os.chmod(path,0x1c0) }
    private fun key(id: String)="voice-recording:$id"
    private fun index()=storage.read("voice-index-v1")?.let { JSONArray(it).strings().also { ids-> require(ids.size<=32 && ids.distinct().size==ids.size);ids.forEach(::validId) } }.orEmpty()
    private fun validId(id: String) { require(UUID.fromString(id).toString()==id) }
    fun file(id: String): File { validId(id);return File(directory,"$id.wav") }
    private fun decode(v: JSONObject): PendingVoiceRecording {
        require(v.getInt("version")==1);val id=v.getString("id");validId(id)
        return PendingVoiceRecording(id,AISharingScope.decode(v.getJSONObject("scope")),v.optional("certificate"),v.getString("draftKey"),
            v.getString("draftId"),v.optional("threadId"),v.getString("originalText"),v.getLong("createdAt"),
            v.optJSONObject("manifest")?.let(VoiceCodec::manifest),v.getBoolean("prepareAttempted"),v.getBoolean("audioAttempted"),v.optBoolean("migrationReadOnly",false)).also {
            require(it.manifest==null || it.manifest.operationId==id && it.manifest.hostId==it.scope.target.hostId && it.manifest.draftId==it.draftId && it.manifest.threadId==it.threadId)
        }
    }
    private fun read(id: String)=storage.read(key(id))?.let(::JSONObject) ?: error("原录音记录不存在")
    /** Inventory only: a raw WAV is not a sealed manifest and is never implicitly repaired for export. */
    internal fun migrationProvisionalIds(): List<String> = storage.operationTransaction {
        index().filter { id -> decode(read(id)).manifest == null }
    }
    suspend fun begin(scope: AISharingScope,certificate: String?,draftKey: String,draftId: String,threadId: String?,text: String): PendingVoiceRecording = withContext(Dispatchers.IO) {
        storage.operationTransaction {
            val ids=index();check(ids.size<32) { "待处理录音过多，请先处理原录音。" }
            val pending=PendingVoiceRecording(UUID.randomUUID().toString(),scope,certificate,draftKey,draftId,threadId,text,System.currentTimeMillis())
            // Register first: an interrupted save cannot create an unindexed audio file.
            storage.write("voice-index-v1",JSONArray(ids+pending.id).toString())
            storage.write(key(pending.id),pending.json().toString());pending
        }
    }
    suspend fun seal(pending: PendingVoiceRecording): PendingVoiceRecording = withContext(Dispatchers.IO) {
        storage.operationTransaction {
            val body=read(pending.id);val current=decode(body)
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
            index().mapNotNull { id->storage.read(key(id))?.let { decode(JSONObject(it)) } }
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
            body.put("prepareAttempted",current.prepareAttempted || prepareAttempted).put("audioAttempted",current.audioAttempted || audioAttempted)
            if(receipt!=null) { require(receipt.manifest==current.manifest);body.put("phase",receipt.phase).put("expiresAt",receipt.expiresAt) }
            storage.write(key(pending.id),body.toString())
        }
    }
    suspend fun remove(pending: PendingVoiceRecording,unsealedOnly: Boolean=false)=withContext(Dispatchers.IO) {
        storage.operationTransaction {
            val body=storage.read(key(pending.id))?.let(::JSONObject) ?: return@operationTransaction
            val current=decode(body)
            // Cancel may race the durable seal. End waiting while retaining an already sealed/attempted original.
            if(unsealedOnly && (current.manifest!=null || current.prepareAttempted || current.audioAttempted)) return@operationTransaction
            storage.write("voice-index-v1",JSONArray(index().filterNot { it==pending.id }).toString())
            storage.remove(key(pending.id));storage.remove("voice-audio:${pending.id}")
            // Imported IDs do not grant ownership of this installation's global raw directory.
            // The source installation/namespace may still own an interrupted provisional WAV.
            if(!current.migrationReadOnly) {val audio=file(pending.id);check(audio.delete() || !audio.exists())}
        }
    }
}

internal object WavePCM {
    fun header(size: Int)=java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN).apply {
        put("RIFF".toByteArray(Charsets.US_ASCII));putInt(size+36);put("WAVEfmt ".toByteArray(Charsets.US_ASCII));putInt(16)
        putShort(1);putShort(1);putInt(16000);putInt(32000);putShort(2);putShort(16);put("data".toByteArray(Charsets.US_ASCII));putInt(size)
    }.array()
}
