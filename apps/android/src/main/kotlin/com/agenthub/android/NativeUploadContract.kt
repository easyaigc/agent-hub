package com.agenthub.android

import org.json.JSONObject
import java.time.Instant
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** File publication only. A completed upload never submits/resumes a model operation. */
data class NativeUploadManifest(val file: ComputerFileManifest,val purpose: String,
    val threadId: String?=null,val creationId: String?=null,val attachmentId: String?=null) {
    val id get()=file.id
    fun json()=file.json().put("version",1).put("purpose",purpose)
        .put("threadId",threadId?:JSONObject.NULL).put("creationId",creationId?:JSONObject.NULL)
        .put("attachmentId",attachmentId?:JSONObject.NULL)
    fun validate() {
        if(purpose=="file-transfer")ComputerFileCodec.manifest(file.json())
        else {ComputerFileCodec.uuid(file.id);require(file.hostId.isNotBlank())
            CodexCommandCodec.validate(CreationFile(attachmentId?:file.id,file.name,file.mediaType,file.sizeBytes,file.sha256).body(),"creationFile")}
        fun identifier(s:String?)=s!=null&&s.matches(Regex("[A-Za-z0-9_-]{1,200}"))
        require(when(purpose) {
            "attachment"->identifier(threadId)&&creationId==null&&attachmentId==null
            "creation-attachment"->threadId==null&&identifier(creationId)&&identifier(attachmentId)
            "file-transfer"->threadId==null&&creationId==null&&attachmentId==null
            else->false
        })
    }
    companion object {
        fun decode(v:JSONObject):NativeUploadManifest {
            val keys=setOf("hostId","id","name","mediaType","sizeBytes","sha256","version","purpose","threadId","creationId","attachmentId")
            require(v.keys().asSequence().toSet()==keys&&v.get("version")==1)
            for(k in listOf("threadId","creationId","attachmentId"))require(v.get(k)==JSONObject.NULL||v.get(k) is String)
            val f=JSONObject();for(k in setOf("hostId","id","name","mediaType","sizeBytes","sha256"))f.put(k,v.get(k))
            return NativeUploadManifest(ComputerFileManifest(f.getString("hostId"),f.getString("id"),f.getString("name"),f.getString("mediaType"),uploadLong(f.get("sizeBytes")),f.getString("sha256")),v.getString("purpose"),v.optional("threadId"),v.optional("creationId"),v.optional("attachmentId")).also {it.validate()}
        }
        fun attachment(client:MobileClient,parent:String,file:CreationFile,creation:Boolean=false):NativeUploadManifest {
            val purpose=if(creation)"creation-attachment" else "attachment"
            // Preserve EasyWork's existing transfer ID. No identity/ID migration of old attempts.
            val id=if(client.scope?.agentId=="easywork")file.id else {
                val key=NativeUploadTarget.capture(client).key+"\u0000$purpose\u0000$parent\u0000${file.id}"
                val h=ComputerFileCodec.hash(key.toByteArray()).take(32)
                listOf(h.take(8),h.substring(8,12),h.substring(12,16),h.substring(16,20),h.substring(20)).joinToString("-")
            }
            return NativeUploadManifest(ComputerFileManifest(client.id,id,file.name,file.mediaType,file.sizeBytes,file.sha256),purpose,
                if(creation)null else parent,if(creation)parent else null,if(creation)file.id else null).also {it.validate()}
        }
    }
}
data class NativeUploadCapability(val maxChunkBytes:Int,val remoteCancel:Boolean,val protocol:String) {
    init {require(maxChunkBytes in 1..1024*1024&&protocol in setOf("codex-upload-v1","easywork-attachment-v1"))}
}
enum class NativeUploadAction { PREPARE, STATUS, CHUNK, COMPLETE, CANCEL }
data class NativeUploadReceipt(val manifest:NativeUploadManifest,val phase:String,val committedBytes:Long,
    val maxChunkBytes:Int,val wire:String) {
    val result get()=JSONObject(wire).optJSONObject("result")
    companion object {
        fun decode(v:JSONObject,expected:NativeUploadManifest):NativeUploadReceipt {
            val keys=v.keys().asSequence().toSet()
            require(keys.containsAll(setOf("version","manifest","phase","committedBytes","maxChunkBytes","createdAt","updatedAt","result"))&&
                keys.all {it in setOf("version","manifest","phase","committedBytes","maxChunkBytes","createdAt","updatedAt","result","reason","publicationStarted")})
            require(v.get("version")==1&&NativeUploadManifest.decode(v.getJSONObject("manifest"))==expected)
            val count=uploadLong(v.get("committedBytes"));val chunk=uploadLong(v.get("maxChunkBytes"))
            val phase=v.getString("phase");require(count in 0..expected.file.sizeBytes&&chunk in 1..1024*1024)
            require(phase in setOf("uploading","verifying","completed","cancelled"))
            Instant.parse(v.getString("createdAt"));Instant.parse(v.getString("updatedAt"))
            if(v.has("reason"))require(v.getString("reason")=="expired")
            if(v.has("publicationStarted"))require(v.get("publicationStarted") is Boolean)
            require(v.get("result")==JSONObject.NULL||v.get("result") is JSONObject)
            if(phase=="completed") {
                require(count==expected.file.sizeBytes&&v.optJSONObject("result")!=null)
                validateNativeUploadResult(expected,v.getJSONObject("result"))
            }
            return NativeUploadReceipt(expected,phase,count,chunk.toInt(),v.toString())
        }
    }
}
internal fun uploadLong(v:Any):Long {require(v is Int||v is Long);return (v as Number).toLong().also {require(it in 0..9_007_199_254_740_991L)}}
internal fun validateNativeUploadResult(m:NativeUploadManifest,v:JSONObject) {
    when(m.purpose) {
        "attachment"->{CodexCommandCodec.validate(v,"attachment")
            require(v.getString("hostId")==m.file.hostId&&v.getString("threadId")==m.threadId&&v.getString("name")==m.file.name&&
                v.getString("mediaType")==m.file.mediaType&&uploadLong(v.get("sizeBytes"))==m.file.sizeBytes&&v.getString("sha256")==m.file.sha256)}
        "creation-attachment"->{CodexCommandCodec.validate(v,"creationUpload");require(v.getString("hostId")==m.file.hostId&&v.getString("clientOperationId")==m.creationId&&
            v.getString("id")==m.attachmentId&&uploadLong(v.get("sizeBytes"))==m.file.sizeBytes&&v.getString("sha256")==m.file.sha256)}
        "file-transfer"->ComputerFileCodec.receipt(v,m.file.hostId).also {it.validate(m.file);require(it.phase=="completed")}
        else->error("Unknown upload purpose")
    }
}

/** Origin guard remains in force while a paused original send is waiting. Never persist this closure. */
internal class NativeUploadOrigin(val operationId:String,val index:Int,val count:Int,val totalBytes:Long?,val current:()->Boolean):
    AbstractCoroutineContextElement(Key) {companion object Key:CoroutineContext.Key<NativeUploadOrigin>}
