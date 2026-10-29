package com.agenthub.android

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Closed question suffix, and a versioned metadata owner key distinct from transport/cache keys. */
internal object NativeVoiceDestinationKeys {
    fun question(base: String,thread: String,turn: String?,approval: String,question: String)=
        base+":question:"+voiceSHA(JSONArray(listOf(thread,turn,approval,question)).toString().toByteArray(Charsets.UTF_8))
    fun matches(bases: Set<String>,key: String)=bases.any {base->key==base || key.matches(Regex(Regex.escape(base)+":question:[a-f0-9]{64}"))}
    fun isQuestion(key: String)=key.matches(Regex("(?s).+:question:[a-f0-9]{64}"))
    fun owner(key: String,bases: Set<String> = emptySet())=if(key !in bases && isQuestion(key))"voice-question-draft-v1:"+java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(key.toByteArray(Charsets.UTF_8))else key
}

internal data class NativeVoiceDraftRequest(val draft: VoiceDraft,val original: PendingVoiceRecording,val result: NativeVoiceLocalResult) {
    val ownerKey=NativeVoiceDestinationKeys.owner(draft.key,draft.client.operationTargets(draft.threadId).toSet())
    val text=draft.text+(if(draft.text.isEmpty()||draft.text.last().isWhitespace())""else "\n")+result.text
    init {
        result.validate(original)
        require(draft.key==original.draftKey&&draft.draftId==original.draftId&&draft.threadId==original.threadId&&draft.text==original.originalText)
        require(draft.scope.matches(original.scope)&&draft.certificate==original.certificate&&sameScope(draft.client.scope,original.scope.target))
        require(NativeVoiceDestinationKeys.matches(draft.client.operationTargets(draft.threadId).toSet(),draft.key))
        require(draft.version.isNotEmpty()&&draft.version.length<=1024&&text.length<=100_000)
    }
}

internal data class NativeVoiceDraftReceipt(val actionId: String,val originalFingerprint: String,val destinationKey: String,val ownerKey: String,
    val sourceVersion: String,val acceptedVersion: String,val textSha256: String,val metadataSha256: String) {
    fun json()=JSONObject().put("version",1).put("actionId",actionId).put("originalFingerprint",originalFingerprint).put("destinationKey",destinationKey)
        .put("ownerKey",ownerKey).put("sourceVersion",sourceVersion).put("acceptedVersion",acceptedVersion).put("textSha256",textSha256).put("metadataSha256",metadataSha256)
    fun validate(original: PendingVoiceRecording,result: NativeVoiceLocalResult) {
        require(actionId==result.actionId&&originalFingerprint==result.fingerprint&&destinationKey==original.draftKey&&(ownerKey==destinationKey || ownerKey==NativeVoiceDestinationKeys.owner(destinationKey)))
        val text=original.originalText+(if(original.originalText.isEmpty()||original.originalText.last().isWhitespace())""else "\n")+result.text
        require(textSha256==voiceSHA(text.toByteArray(Charsets.UTF_8)))
    }
    fun validate(request: NativeVoiceDraftRequest) {validate(request.original,request.result);require(sourceVersion==request.draft.version&&ownerKey==request.ownerKey)}
    companion object {
        fun decode(value: JSONObject): NativeVoiceDraftReceipt {
            require(value.keys().asSequence().toSet()==setOf("version","actionId","originalFingerprint","destinationKey","ownerKey","sourceVersion","acceptedVersion","textSha256","metadataSha256")&&value.get("version")==1)
            fun text(key: String,max: Int)= (value.get(key) as String).also {require(it.isNotEmpty()&&it.length<=max)}
            for(key in listOf("actionId","acceptedVersion"))require(UUID.fromString(text(key,36)).toString()==text(key,36))
            for(key in listOf("originalFingerprint","textSha256","metadataSha256"))require(text(key,64).matches(Regex("[a-f0-9]{64}")))
            return NativeVoiceDraftReceipt(text("actionId",36),text("originalFingerprint",64),text("destinationKey",8192),text("ownerKey",16384),text("sourceVersion",1024),text("acceptedVersion",36),text("textSha256",64),text("metadataSha256",64)).also {require(it.ownerKey==it.destinationKey||it.ownerKey==NativeVoiceDestinationKeys.owner(it.destinationKey))}
        }
    }
}

/** The immutable write changes only this exact draft and its revision. It is the actual metadata owner. */
internal class NativeVoiceDraftWrite private constructor(val request: NativeVoiceDraftRequest,val before: String,val after: String,val receipt: NativeVoiceDraftReceipt) {
    companion object {
        fun prepare(request: NativeVoiceDraftRequest,before: String): NativeVoiceDraftWrite {
            val root=StrictMigrationJson.parse(before.toByteArray(Charsets.UTF_8))
            val client=root.getJSONArray("clients").objects().map(::decodeClient).single {it.id==request.draft.client.id}
            require(sameScope(client.scope,request.draft.client.scope)&&client.codexDirect?.fingerprint==request.draft.certificate&&client.endpoint==request.draft.client.endpoint&&client.readerId==request.draft.client.readerId&&client.agentConnection==request.draft.client.agentConnection)
            require(root.getJSONObject("drafts").optString(request.ownerKey,"")==request.draft.text&&root.getJSONObject("draftVersions").getString(request.ownerKey)==request.draft.version)
            val version=UUID.randomUUID().toString();root.getJSONObject("drafts").put(request.ownerKey,request.text);root.getJSONObject("draftVersions").put(request.ownerKey,version)
            val after=root.toString();val receipt=NativeVoiceDraftReceipt(request.result.actionId,request.result.fingerprint,request.draft.key,request.ownerKey,request.draft.version,version,voiceSHA(request.text.toByteArray(Charsets.UTF_8)),voiceSHA(after.toByteArray(Charsets.UTF_8)))
            receipt.validate(request);return NativeVoiceDraftWrite(request,before,after,receipt)
        }
    }
}
internal interface NativeVoiceDraftOwner {
    fun prepare(request: NativeVoiceDraftRequest): NativeVoiceDraftWrite
    /** Main-thread publication after exact durable readback; reject any intervening owner change. */
    fun publish(write: NativeVoiceDraftWrite): Boolean
}
internal fun interface NativeVoiceDraftDestinationPort {
    suspend fun accept(request: NativeVoiceDraftRequest,checkpoint: suspend ()->Unit): NativeVoiceDraftReceipt
}
internal class NativeEncryptedVoiceDraftDestinationPort(private val storage: NativeSessionStorage,private val owner: NativeVoiceDraftOwner,
    private val writeCheckpoint: (Int)->Unit={}): NativeVoiceDraftDestinationPort {
    override suspend fun accept(request: NativeVoiceDraftRequest,checkpoint: suspend ()->Unit): NativeVoiceDraftReceipt {
        checkpoint();val plan=owner.prepare(request)
        val digest=storage.writeVoiceDestinationMetadata(plan.after,writeCheckpoint)
        checkpoint();require(digest==plan.receipt.metadataSha256)
        require(owner.publish(plan)) {"Voice destination changed before durable publication"}
        return plan.receipt.also {it.validate(request)}
    }
}
