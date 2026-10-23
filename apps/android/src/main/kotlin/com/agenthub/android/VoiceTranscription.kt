package com.agenthub.android

import com.agenthub.core.VoiceTranscriptionRules
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.security.MessageDigest

data class VoiceReadiness(val hostId: String,val available: Boolean,val reason: String?,val maxBytes: Int,val maxDurationMs: Int)
data class VoiceManifest(val hostId: String,val threadId: String?,val draftId: String,val operationId: String,val sizeBytes: Int,val sha256: String,val durationMs: Int) {
    fun json()=JSONObject().put("hostId",hostId).put("threadId",threadId ?: JSONObject.NULL).put("draftId",draftId).put("operationId",operationId)
        .put("mediaType","audio/wav").put("sizeBytes",sizeBytes).put("sha256",sha256).put("durationMs",durationMs)
    fun verify(bytes: ByteArray) { require(VoiceTranscriptionRules.validateManifest(json().toString()) && bytes.size==sizeBytes && voiceSHA(bytes)==sha256) { "原录音清单或内容已变化" } }
}
data class VoiceReceipt(val manifest: VoiceManifest,val phase: String,val text: String?,val error: String?,val expiresAt: String)
object VoiceCodec {
    fun readiness(v: JSONObject)=CodexCommandCodec.validate(v,"voiceReadiness").let { VoiceReadiness(it.getString("hostId"),it.getBoolean("available"),it.optional("reason"),it.getInt("maxBytes"),it.getInt("maxDurationMs")) }
    fun manifest(v: JSONObject)=CodexCommandCodec.validate(v,"voiceManifest").let { VoiceManifest(it.getString("hostId"),it.optional("threadId"),it.getString("draftId"),it.getString("operationId"),it.getInt("sizeBytes"),it.getString("sha256"),it.getInt("durationMs")) }
    fun receipt(v: JSONObject,expected: VoiceManifest): VoiceReceipt {
        require(VoiceTranscriptionRules.receiptMatches(expected.json().toString(),v.toString())) { "转写回执与原录音或目标不一致" }
        return VoiceReceipt(expected,v.getString("phase"),v.optional("text"),v.optional("error"),v.getString("expiresAt"))
    }
}
internal fun voiceSHA(bytes: ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

/** Only an explicit finish/retry calls this flow. Unknown uploads are queried, never replayed. */
class VoiceTranscriptionFlow(private val commands: AgentCommands,private val store: VoiceRecordingStore) {
    suspend fun transcribe(client: MobileClient,pending: PendingVoiceRecording,allowed: ()->Boolean): String {
        val current=store.current(pending)
        val request=current.manifest ?: error("原录音尚未完成")
        val bytes=store.bytes(current);request.verify(bytes)
        fun guard() { check(allowed()) { "共享许可、录音目标或草稿已变化，原录音已保留" } }
        guard()
        var receipt=try { commands.voiceStatus(client,request) }
        catch(e: CodexCommandError) {
            if(e.status!=404 || current.audioAttempted) throw e
            guard();store.mark(pending,prepareAttempted=true);guard()
            commands.prepareVoice(client,request)
        }
        guard()
        if(receipt.phase=="awaiting-audio") {
            // The original Host authoritatively still awaits these exact bytes.
            // It commits the transcribing receipt before upstream I/O.
            store.mark(pending,prepareAttempted=true,audioAttempted=true);guard()
            receipt=commands.uploadVoice(client,request,bytes)
        }
        val deadline=System.nanoTime()+100_000_000_000L
        while(receipt.phase=="transcribing" && System.nanoTime()<deadline) { delay(700);guard();receipt=commands.voiceStatus(client,request) }
        store.mark(pending,prepareAttempted=true,audioAttempted=current.audioAttempted || receipt.phase!="awaiting-audio",receipt=receipt)
        guard()
        if(receipt.phase=="completed" && receipt.text!=null) return receipt.text
        error(receipt.error ?: "转写仍在处理中，录音已保留；点击重试查询原结果。")
    }
}
