package com.agenthub.android

import com.agenthub.core.RuntimeIdentity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import kotlin.coroutines.coroutineContext

/** Codex wire claims and platform file I/O; target identity and contract schema remain shared. */
internal object CodexResources {
    const val maximumBytes=20L*1024*1024
    private fun invalid(): Nothing=throw CodexCommandError("文件不属于当前电脑和对话",appOwnedCopy=true)
    fun validate(client: MobileClient,file: MobileFile): MobileFile {
        fun basename(value: String)=value.isNotEmpty()&&value !in listOf(".","..")&&value.none { it=='/'||it=='\\'||it=='\u0000' }
        if(client.scope?.agentId!="codex"||file.hostId!=client.id||file.threadId.isEmpty()||!basename(file.id)||!basename(file.name)||
            file.mediaType.isEmpty()||file.sizeBytes !in 0..maximumBytes||!file.sha256.matches(Regex("[a-f0-9]{64}")))invalid()
        return file
    }
    private fun file(client: MobileClient,thread: String,value: JSONObject): MobileFile {
        try {
            val v=CodexCommandCodec.validate(value,"attachment")
            return validate(client,MobileFile(v.getString("id"),v.getString("name"),v.getString("mediaType"),v.getLong("sizeBytes"),
                v.getString("hostId"),v.getString("threadId"),v.getString("sha256"),
                v.resourceIdentityOrNull())).also { if(it.threadId!=thread)invalid() }
        } catch(error: CodexCommandError) { throw error }
          catch(_: Exception) { invalid() }
    }
    private fun claims(client: MobileClient,thread: String,value: JSONObject) {
        val target=CodexCommandCodec.validate(value.getJSONObject("readTarget"),"target")
        if(client.scope?.agentId!="codex"||value.opt("hostId")!=client.id||value.opt("threadId")!=thread||
            !sameScope(HostReadCodec.target(target),client.scope))invalid()
    }
    fun fromMessage(client: MobileClient,thread: String,message: String,reference: String,value: JSONObject): MobileFile {
        try {
            claims(client,thread,value)
            if(value.opt("messageId")!=message||value.opt("reference")!=reference)invalid()
            return file(client,thread,value.getJSONObject("attachment"))
        } catch(error: CodexCommandError) { throw error }
          catch(_: Exception) { invalid() }
    }
    fun current(client: MobileClient,original: MobileFile,value: JSONObject): MobileFile {
        try {
            claims(client,original.threadId,value)
            if(value.opt("sourceAttachmentId")!=original.id)invalid()
            return file(client,original.threadId,value.getJSONObject("attachment")).also {
                if(it.name!=original.name||it.mediaType!=original.mediaType)invalid()
            }
        } catch(error: CodexCommandError) { throw error }
          catch(_: Exception) { invalid() }
    }
    private fun hash(value: ByteArray)=MessageDigest.getInstance("SHA-256").digest(value).joinToString("") { "%02x".format(it) }
    private suspend fun cached(file: File,expected: MobileFile): Boolean=withContext(Dispatchers.IO) {
        if(!file.isFile||file.length()!=expected.sizeBytes)return@withContext false
        val digest=MessageDigest.getInstance("SHA-256");val buffer=ByteArray(16_384);var count=0L
        file.inputStream().use { input->while(true) {
            coroutineContext.ensureActive()
            val n=input.read(buffer);if(n<0)break
            count+=n;if(count>expected.sizeBytes)return@withContext false
            digest.update(buffer,0,n)
        } }
        count==expected.sizeBytes&&digest.digest().joinToString("") { "%02x".format(it) }==expected.sha256
    }
    suspend fun download(client: MobileClient,original: MobileFile,cache: File,
        resolve: suspend ()->MobileFile?,fetch: suspend (MobileFile,File)->File): File {
        validate(client,original)
        for(attempt in 0..1) {
            coroutineContext.ensureActive()
            // A stale cached copy cannot hide a changed/removed current source.
            val current=resolve() ?: original
            validate(client,current)
            val scope=hash(RuntimeIdentity.readingKey(client.scope!!,current.threadId).toByteArray(Charsets.UTF_8))
            val directory=File(cache,"codex-"+scope+"/"+hash(current.id.toByteArray(Charsets.UTF_8))+"/"+current.sha256)
            // Display names can exceed a filesystem component's byte limit.
            // Identity/version stay in the directory; the Provider carries the original display name.
            val filename=if(current.name.toByteArray(Charsets.UTF_8).size<=200)current.name else
                "resource-"+hash(current.name.toByteArray(Charsets.UTF_8))+current.name.substringAfterLast('.',"").takeIf { it.matches(Regex("[A-Za-z0-9]{1,12}")) }?.let { ".$it" }.orEmpty()
            val destination=File(directory,filename)
            if(cached(destination,current))return destination
            try { return fetch(current,destination) }
            catch(error: CodexCommandError) { if(error.status!=409||attempt!=0)throw error }
        }
        throw CodexCommandError("下载文件完整性校验失败",appOwnedCopy=true)
    }
    /** Stream to an unpublished sibling, verify bytes, then publish atomically. */
    fun write(input: InputStream,active: ()->Boolean,file: MobileFile,destination: File): File {
        val directory=destination.parentFile!!
        check(directory.mkdirs()||directory.isDirectory)
        val temporary=File(directory,".resource-"+UUID.randomUUID()+".tmp")
        val digest=MessageDigest.getInstance("SHA-256")
        fun checkActive() { if(!active())throw kotlinx.coroutines.CancellationException("Resource read cancelled") }
        try {
            var count=0L;val buffer=ByteArray(16_384)
            temporary.outputStream().use { output->
                while(true) {
                    checkActive();val n=input.read(buffer);if(n<0)break
                    count+=n
                    if(count>file.sizeBytes||count>maximumBytes)throw CodexCommandError("下载文件完整性校验失败",appOwnedCopy=true)
                    output.write(buffer,0,n);digest.update(buffer,0,n)
                }
                output.fd.sync()
            }
            checkActive()
            if(count!=file.sizeBytes||digest.digest().joinToString("") { "%02x".format(it) }!=file.sha256)
                throw CodexCommandError("下载文件完整性校验失败",appOwnedCopy=true)
            Files.move(temporary.toPath(),destination.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING)
            return destination
        } finally { temporary.delete() }
    }
}
