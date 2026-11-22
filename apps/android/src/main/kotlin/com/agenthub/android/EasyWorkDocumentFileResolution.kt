package com.agenthub.android

import org.json.JSONObject
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Adapter metadata only; never grants local file or renderer authority. */
data class DocumentFileSource(val grantId: String,val version: String,val messageId: String)
data class DocumentMessageSource(val messageId: String,val reference: String)

internal object EasyWorkDocumentFileResolution {
    const val capability="attachments:resolve-document-link:v1"
    fun identifier(value: String)=value.toByteArray().size in 1..1024 && value.none { it.isISOControl() }
    fun originalMessageId(value: String): String {
        val parts=value.split(':')
        return if(parts.size==2 && parts[0].matches(Regex("[a-fA-F0-9]{8}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{12}")) &&
            parts[1].isNotEmpty() && parts[1].all { it in '0'..'9' } && parts[1].toULongOrNull()!=null) parts[0] else value
    }
    private fun leaf(value: String)=identifier(value)&&value !in listOf(".","..")&&value.none { it=='/'||it=='\\' }
    private fun exact(a: JSONObject,b: JSONObject)=easyCanonical(a)==easyCanonical(b)
    fun decode(file: JSONObject,client: MobileClient,thread: String): MobileFile {
        val id=readingString(file,"resourceId");val name=readingString(file,"name");val media=readingString(file,"contentType")
        val hash=readingString(file,"sha256");val size=readingInteger(file,"size")
        require(leaf(id)&&leaf(name)&&media.toByteArray().size in 1..255&&media.none { it.isISOControl() }&&
            hash.matches(Regex("[a-f0-9]{64}"))&&size<=20L*1024*1024)
        val source=if(file.has("documentSource"))file.getJSONObject("documentSource").let {
            require(it.keys().asSequence().toSet()==setOf("grantId","version","messageId"))
            val grant=readingString(it,"grantId");val version=readingString(it,"version");val message=readingString(it,"messageId")
            require(listOf(grant,version,message).all(::identifier));DocumentFileSource(grant,version,message)
        } else null
        return MobileFile(id,name,media,size,client.id,thread,hash,documentSource=source)
    }
    suspend fun message(client: MobileClient,thread: String,message: String,reference: String,
        request: suspend (String,JSONObject)->JSONObject): MobileFile {
        val original=originalMessageId(message)
        require(identifier(thread)&&identifier(original)&&reference.toByteArray().size in 1..8192)
        currentCoroutineContext().ensureActive()
        val value=request("resource/resolve",JSONObject().put("threadId",thread).put("messageId",original).put("reference",reference))
        currentCoroutineContext().ensureActive()
        require(readingInteger(value,"version")==1L&&readingString(value,"threadId")==thread&&
            readingString(value,"messageId")==original&&readingString(value,"reference")==reference)
        val file=decode(value.getJSONObject("attachment"),client,thread)
        require(file.documentSource==null||file.documentSource.messageId==original)
        return file.copy(documentMessageSource=DocumentMessageSource(original,reference))
    }
    suspend fun document(client: MobileClient,target: JSONObject,file: MobileFile,reference: String,digest: String,
        request: suspend (String,JSONObject)->JSONObject): MobileFile {
        val grant=requireNotNull(file.documentSource)
        require(file.hostId==client.id&&file.sha256==digest&&digest.matches(Regex("[a-f0-9]{64}"))&&
            identifier(file.threadId)&&listOf(file.id,grant.grantId,grant.version,grant.messageId).all(::identifier)&&
            MessageLinkDestination.documentReference(reference))
        val source=JSONObject().put("resourceId",file.id).put("grantId",grant.grantId).put("version",grant.version)
            .put("messageId",grant.messageId).put("sha256",digest)
        currentCoroutineContext().ensureActive()
        val value=request("resource/resolve-document-link",JSONObject().put("version",1).put("target",target)
            .put("threadId",file.threadId).put("source",source).put("reference",reference))
        currentCoroutineContext().ensureActive()
        require(readingInteger(value,"version")==1L&&exact(value.getJSONObject("target"),target)&&
            readingString(value,"threadId")==file.threadId&&readingString(value,"reference")==reference&&exact(value.getJSONObject("source"),source))
        return decode(value.getJSONObject("attachment"),client,file.threadId).also {
            require(it.documentSource?.messageId==grant.messageId)
        }
    }
}
