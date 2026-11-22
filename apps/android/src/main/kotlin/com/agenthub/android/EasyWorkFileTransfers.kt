package com.agenthub.android

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardOpenOption.*
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

/** Standalone desktop offer. Never becomes a chat attachment or a model input. */
data class IncomingComputerFile(val hostId:String,val id:String,val resourceId:String,val mobileDeviceId:String,
    val name:String,val mediaType:String,val sizeBytes:Long,val sha256:String,val phase:String,val createdAt:String) {
    fun validate(host:String,device:String) {
        require(hostId==host&&mobileDeviceId==device&&device.isNotBlank())
        for(value in listOf(id,resourceId))require(value.length in 1..256&&value !in setOf(".","..")&&value.none {it<' '||it in "/\\"})
        ComputerFileCodec.validName(name)
        require(ComputerFileCodec.validMediaType(mediaType)&&sizeBytes in 0..ComputerFileCodec.MAX_FILE_BYTES&&sha256.matches(Regex("[a-f0-9]{64}")))
        require(phase in setOf("waiting","transferring","verifying","completed","failed","cancelled"));Instant.parse(createdAt)
    }
    fun sameFile(other:IncomingComputerFile)=copy(phase=other.phase)==other
    val snapshotKey get()=ComputerFileCodec.hash(JSONArray(listOf(hostId,id,resourceId,mobileDeviceId,name,mediaType,sizeBytes,sha256,createdAt)).toString().toByteArray(Charsets.UTF_8))
}

internal object EasyWorkFileTransfers {
    const val CHUNK=192*1024
    suspend fun cancel(m:NativeUploadManifest,device:String,capabilities:Set<String>,
        request:suspend (String,JSONObject,String)->JSONObject):JSONObject {
        check("file-transfers" in capabilities) {"手机发送已停止；当前电脑不支持同步取消，请在电脑端处理暂存。"}
        val reply=request("resource/transfer-cancel",JSONObject().put("transferId",m.id),"cancel-${m.id}")
        require(uploadTransfer(reply,m,device).getString("status") in setOf("completed","cancelled"))
        return reply
    }
    fun uploadTransfer(value:JSONObject,m:NativeUploadManifest,device:String,completed:Boolean=false):JSONObject {
        val t=value.getJSONObject("transfer");val independent=m.purpose=="file-transfer"
        require(m.purpose in setOf("attachment","file-transfer"))
        require(t.getString("transferId")==m.id&&(if(independent)t.isNull("threadId") else t.getString("threadId")==m.threadId)&&t.getString("mobileDeviceId")==device&&
            t.getString("direction")=="mobile-to-desktop"&&t.getString("purpose")==(if(independent)"transfer" else "attachment")&&t.getString("name")==m.file.name&&
            t.getString("contentType")==m.file.mediaType&&uploadLong(t.get("size"))==m.file.sizeBytes)
        // Legacy attachment receipts may omit this optional echo. Never accept a conflicting echo;
        // final publication still requires the complete file digest and exact original identity.
        if(independent||t.has("expectedSha256"))require(t.get("expectedSha256")==m.file.sha256)
        val n=uploadLong(t.get("transferredBytes"));require(n in 0..m.file.sizeBytes)
        require(t.getString("status") in setOf("waiting","transferring","verifying","completed","cancelled"))
        if(completed||t.getString("status")=="completed")require(t.getString("status")=="completed"&&n==m.file.sizeBytes&&t.getString("sha256")==m.file.sha256)
        return t
    }
    fun uploadReceipt(value:JSONObject,m:NativeUploadManifest,device:String,chunk:Int=CHUNK):NativeUploadReceipt {
        val t=uploadTransfer(value,m,device);val completed=t.getString("status")=="completed"
        val result=if(completed&&m.purpose=="file-transfer") {
            EasyWorkIndependentUploads.receipt(t,m.file.hostId,device,"").json()
        } else if(completed) {
            val id=t.getString("resourceId");require(id.isNotBlank())
            value.optJSONObject("attachment")?.let {a->
                require(a.getString("id")==m.id&&a.getString("resourceId")==id&&a.getString("name")==m.file.name&&a.getString("contentType")==m.file.mediaType&&
                    uploadLong(a.get("size"))==m.file.sizeBytes&&a.getString("sha256")==m.file.sha256)
            }
            JSONObject().put("hostId",m.file.hostId).put("threadId",m.threadId).put("id",id).put("name",m.file.name).put("mediaType",m.file.mediaType)
                .put("sizeBytes",m.file.sizeBytes).put("sha256",m.file.sha256)
        }else null
        val now=Instant.now().toString()
        return NativeUploadReceipt.decode(JSONObject().put("version",1).put("manifest",m.json())
            .put("phase",when(t.getString("status")){"completed"->"completed";"cancelled"->"cancelled";"verifying"->"verifying";else->"uploading"})
            .put("committedBytes",uploadLong(t.get("transferredBytes"))).put("maxChunkBytes",chunk)
            .put("createdAt",now).put("updatedAt",now).put("result",result?:JSONObject.NULL),m)
    }
    fun record(v:JSONObject,host:String,device:String):IncomingComputerFile {
        require(v.getString("purpose")=="transfer"&&v.getString("direction")=="desktop-to-mobile"&&v.isNull("threadId"))
        val f=IncomingComputerFile(host,v.getString("transferId"),v.getString("resourceId"),v.getString("mobileDeviceId"),
            v.getString("name"),v.getString("contentType"),uploadLong(v.get("size")),v.getString("sha256"),v.getString("status"),v.getString("createdAt"))
        f.validate(host,device)
        val n=uploadLong(v.get("transferredBytes"));require(n<=f.sizeBytes&&(f.phase!="completed"||n==f.sizeBytes))
        return f
    }
    fun catalog(v:JSONObject,host:String,device:String,limits:EasyWorkFileLimits=EasyWorkFileLimits(EasyWorkFileLimits.LEGACY,CHUNK,false)):ComputerFileCatalog {
        val directory=ComputerFileCodec.text(v.get("destinationDirectory"),16384)
        val maximum=limits.catalogMaximum(v)
        val records=v.getJSONArray("transfers");require(records.length()<=ComputerFileCodec.MAX_CATALOG)
        val incoming=records.objects().filter {it.optString("purpose")=="transfer"&&it.optString("direction")=="desktop-to-mobile"&&it.isNull("threadId")}
            .map {record(it,host,device)}
        require(incoming.map {it.id}.distinct().size==incoming.size)
        val outgoing=records.objects().filter {it.optString("purpose")=="transfer"&&it.optString("direction")=="mobile-to-desktop"&&
            it.isNull("threadId")&&it.optString("status") in setOf("waiting","transferring","verifying","completed")}
            .map {EasyWorkIndependentUploads.receipt(it,host,device,directory)}
        require(outgoing.map {it.file.id}.distinct().size==outgoing.size)
        return ComputerFileCatalog(host,directory,maximum,outgoing,incoming,uploadAvailable=true)
    }
    fun chunk(v:JSONObject,f:IncomingComputerFile,offset:Long,length:Int):ByteArray {
        require(length in 1..CHUNK&&offset>=0&&offset<f.sizeBytes&&length<=f.sizeBytes-offset)
        require(v.getString("resourceId")==f.resourceId&&v.getString("transferId")==f.id&&uploadLong(v.get("offset"))==offset)
        val encoded=v.getString("dataBase64");require(encoded.length<=4*((length+2)/3))
        val bytes=easyBytes(encoded)
        require(bytes.isNotEmpty()&&bytes.size<=length&&uploadLong(v.get("nextOffset"))==offset+bytes.size)
        require(v.get("complete") is Boolean&&v.getBoolean("complete")==(offset+bytes.size==f.sizeBytes))
        return bytes
    }
    private fun ownedDirectory(directory:File) {
        // App-private root is supplied by the model, never by the remote catalog.
        val path=directory.toPath()
        if(Files.exists(path,NOFOLLOW_LINKS))require(Files.isDirectory(path,NOFOLLOW_LINKS)&&!Files.isSymbolicLink(path))
        else Files.createDirectory(path)
    }
    private suspend fun verified(path:File,f:IncomingComputerFile,current:()->Boolean):Boolean {
        if(!Files.exists(path.toPath(),NOFOLLOW_LINKS))return false
        require(Files.isRegularFile(path.toPath(),NOFOLLOW_LINKS)&&!Files.isSymbolicLink(path.toPath()))
        val digest=MessageDigest.getInstance("SHA-256");var count=0L
        FileChannel.open(path.toPath(),READ,NOFOLLOW_LINKS).use {input->
            require(input.size()==f.sizeBytes)
            val buffer=ByteBuffer.allocate(CHUNK)
            while(true) {
                currentCoroutineContext().ensureActive();check(current())
                buffer.clear();val n=input.read(buffer);if(n<0)break
                count+=n;require(count<=f.sizeBytes);digest.update(buffer.array(),0,n)
            }
        }
        return count==f.sizeBytes&&digest.digest().joinToString("") {"%02x".format(it)}==f.sha256
    }
    suspend fun receive(f:IncomingComputerFile,directory:File,current:()->Boolean,progress:(Long)->Unit,
        request:suspend (String,JSONObject,String?)->JSONObject):File=withContext(Dispatchers.IO) {
        f.validate(f.hostId,f.mobileDeviceId);check(current());require(f.phase !in setOf("failed","cancelled"))
        // No remote names become directory components. Check every app-owned parent.
        ownedDirectory(directory)
        val folder=File(directory,f.snapshotKey);ownedDirectory(folder)
        val destination=File(folder,f.name)
        val cached=verified(destination,f,current)
        if(!cached) {
            require(!Files.exists(destination.toPath(),NOFOLLOW_LINKS)) {"手机收件副本校验失败，请保留原文件。"}
            val temporary=File(folder,"${UUID.randomUUID()}.partial")
            try {
                FileChannel.open(temporary.toPath(),WRITE,CREATE_NEW,NOFOLLOW_LINKS).use {output->
                    var offset=0L;val digest=MessageDigest.getInstance("SHA-256")
                    while(offset<f.sizeBytes) {
                        currentCoroutineContext().ensureActive();check(current())
                        val length=minOf(CHUNK.toLong(),f.sizeBytes-offset).toInt()
                        val value=request("resource/download",JSONObject().put("resourceId",f.resourceId).put("transferId",f.id).put("offset",offset).put("length",length),null)
                        check(current());val bytes=chunk(value,f,offset,length)
                        val buffer=ByteBuffer.wrap(bytes);while(buffer.hasRemaining())output.write(buffer)
                        digest.update(bytes);offset+=bytes.size;progress(offset)
                    }
                    require(digest.digest().joinToString("") {"%02x".format(it)}==f.sha256) {"文件完整性校验失败"}
                    currentCoroutineContext().ensureActive();check(current());output.force(true)
                }
                Files.move(temporary.toPath(),destination.toPath(),ATOMIC_MOVE)
                FileChannel.open(folder.toPath(),READ).use {it.force(true)}
            } finally {Files.deleteIfExists(temporary.toPath())}
        }
        currentCoroutineContext().ensureActive();check(current())
        val ack="file-receive-complete-${f.snapshotKey}"
        if(cached&&f.phase!="completed") {
            // A durable file may outlive a lost ACK. Only inspect the original operation;
            // do not replay download-complete (or download bytes) after restart/timeout.
            val status=request("command/status",JSONObject().put("commandId",ack),null)
            val response=status.optJSONObject("response")
            val raw=response?.optJSONObject("result")?.optJSONObject("transfer")
            check(status.optString("operationId")==ack&&status.optString("method")=="resource/download-complete"&&
                status.optString("state")=="completed"&&response!=null&&response.opt("protocolVersion")==2&&
                response.optString("kind")=="response"&&response.optString("requestId")==ack&&
                response.opt("ok")==true&&raw!=null) {"文件已保存；电脑接收确认待查询，请刷新原传输。"}
            val confirmed=record(checkNotNull(raw),f.hostId,f.mobileDeviceId)
            require(confirmed.sameFile(f)&&confirmed.phase=="completed")
            check(current());progress(f.sizeBytes);return@withContext destination
        }
        if(f.phase!="completed") {
            val reply=request("resource/download-complete",JSONObject().put("resourceId",f.resourceId).put("transferId",f.id),ack)
            val confirmed=record(reply.getJSONObject("transfer"),f.hostId,f.mobileDeviceId)
            require(confirmed.sameFile(f)&&confirmed.phase=="completed")
        }
        check(current());progress(f.sizeBytes);destination
    }
}
