package com.agenthub.android

import org.json.JSONObject
import com.agenthub.core.TargetScope
import java.util.UUID

/** Wire projection only. The shared voice flow owns submission and query-only recovery. */
internal object EasyWorkVoice {
    /** Local client IDs key App state; manifests use the original physical Host in the full scope. */
    fun host(client: MobileClient,authenticated: TargetScope): String {
        require(authenticated.agentId=="easywork" && sameScope(authenticated,client.scope) &&
            client.agentConnection?.let {sameScope(it.target,authenticated)}!=false) { "录音完整目标不匹配" }
        return authenticated.hostId
    }
    fun readiness(client: MobileClient,authenticated: TargetScope,value: JSONObject): VoiceReadiness {
        val hostId=host(client,authenticated)
        require(!value.has("hostId") || value.get("hostId")==hostId) { "录音就绪目标不匹配" }
        // Upstream diagnostics are provider metadata, not fields in the shared domain DTO.
        // Preserve the raw types and required fields so the common validator stays strict.
        val domain=JSONObject().put("hostId",hostId)
        for(key in listOf("available","reason","maxBytes","maxDurationMs"))
            if(value.has(key))domain.put(key,value.get(key))
        return VoiceCodec.readiness(domain)
    }
    private fun validate(hostId: String,manifest: VoiceManifest) {
        require(manifest.hostId==hostId) { "录音目标不匹配" }
        VoiceCodec.manifest(manifest.json())
    }
    suspend fun status(client: MobileClient,authenticated: TargetScope,manifest: VoiceManifest,
        request: suspend (String,JSONObject,String)->JSONObject): VoiceReceipt {
        val hostId=host(client,authenticated);validate(hostId,manifest)
        val value=request("voice/status",JSONObject().put("operationId",manifest.operationId),UUID.randomUUID().toString())
        require(value.has("receipt")) { "电脑未提供原转写回执" }
        if(value.isNull("receipt"))throw CodexCommandError("原转写回执尚不存在，录音已保留。",status=404)
        return VoiceCodec.receipt(value.getJSONObject("receipt"),manifest)
    }
    suspend fun prepare(client: MobileClient,authenticated: TargetScope,manifest: VoiceManifest,
        request: suspend (String,JSONObject,String)->JSONObject): VoiceReceipt {
        val hostId=host(client,authenticated);validate(hostId,manifest)
        return VoiceCodec.receipt(request("voice/prepare",JSONObject().put("recording",manifest.json()),manifest.operationId+":prepare"),manifest)
    }
    suspend fun upload(client: MobileClient,authenticated: TargetScope,manifest: VoiceManifest,bytes: ByteArray,
        request: suspend (String,JSONObject,String)->JSONObject): VoiceReceipt {
        val hostId=host(client,authenticated);validate(hostId,manifest);manifest.verify(bytes)
        return VoiceCodec.receipt(request("voice/upload",JSONObject().put("operationId",manifest.operationId)
            .put("audioBase64",easyBase64(bytes)),manifest.operationId),manifest)
    }
}
