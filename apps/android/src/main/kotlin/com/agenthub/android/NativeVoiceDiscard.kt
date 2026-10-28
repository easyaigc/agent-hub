package com.agenthub.android

import org.json.JSONObject

/** Exact local intent. Acknowledging it never cancels or replays an original remote operation. */
internal class NativeVoiceDiscardRequest(val original: PendingVoiceRecording) {
    val recordingId get()=original.id
    val fingerprint: String=voiceSHA(JSONObject().put("version",1).put("recordingId",original.id)
        .put("target",ThreadReadingIdentity.scopeKey(original.scope.target)).put("model",original.scope.model)
        .put("certificate",original.certificate ?: JSONObject.NULL).put("draftKey",original.draftKey).put("draftId",original.draftId)
        .put("threadId",original.threadId ?: JSONObject.NULL).put("createdAt",original.createdAt)
        .put("originalTextSha256",voiceSHA(original.originalText.toByteArray(Charsets.UTF_8)))
        .put("manifest",original.manifest?.json() ?: JSONObject.NULL).toString().toByteArray(Charsets.UTF_8))
    fun matches(value: PendingVoiceRecording)=value.id==original.id && value.scope.matches(original.scope) &&
        value.certificate==original.certificate && value.draftKey==original.draftKey && value.draftId==original.draftId &&
        value.threadId==original.threadId && value.createdAt==original.createdAt && value.originalText==original.originalText &&
        (original.manifest==null || value.manifest==original.manifest)
}
internal data class NativeVoiceDiscardReceipt(val recordingId: String,val fingerprint: String,val discarded: Boolean) {
    fun acknowledges(request: NativeVoiceDiscardRequest)=discarded && recordingId==request.recordingId && fingerprint==request.fingerprint
}
/** Storage owner must atomically verify the original and persist a no-longer-offerable terminal before returning.
 * Keep immutable audio/attachment/receipt/operation evidence; schema and migration stay behind this port.
 */
internal fun interface NativeVoiceDiscardPort {
    suspend fun discard(request: NativeVoiceDiscardRequest): NativeVoiceDiscardReceipt
}
