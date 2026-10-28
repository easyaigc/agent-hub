package com.agenthub.android

import org.json.JSONArray
import org.json.JSONObject

/** Frozen cc0b6ac8 saved-authority schema. Produces fingerprints/targets only, never a command credential object. */
internal object NativeMigrationNotificationAuthorizations {
    fun validate(raw: String): Map<String,NativeChatNoticeTarget> {
        val bytes=raw.toByteArray(Charsets.UTF_8);require(bytes.size<=1024*1024)
        val value=StrictMigrationJson.parse(bytes);NativeChatNotificationWire.keys(value,setOf("version","authorizations"))
        require(value.get("version")==1);val rows=value.getJSONArray("authorizations");require(rows.length()<=128)
        val result=mutableMapOf<String,NativeChatNoticeTarget>()
        for(row in rows.objects()) {
            NativeChatNotificationWire.keys(row,setOf("client","credential","epoch","authorization"))
            val clientRow=row.getJSONObject("client")
            // Reuse the closed shared migration client schema, without activating/copying that client.
            val metadata=JSONObject().put("version",1).put("appearance","system").put("demo",false).put("selected",clientRow.get("id") as String)
                .put("clients",JSONArray().put(clientRow)).put("drafts",JSONObject()).put("selectedThreads",JSONObject()).put("expanded",JSONObject()).put("workspaces",JSONObject())
            NativeMigrationMetadata.validate(metadata);val client=decodeClient(clientRow)
            require(!client.isDemo&&client.scope?.agentId=="codex"&&client.agentConnection==null&&client.endpoint!=null)
            NativeHostReads.baseAddress(requireNotNull(client.endpoint))
            val target=NativeChatNoticeTarget.from(requireNotNull(client.scope));require(client.id==target.hostId)
            val credential=NativeChatNotificationWire.text(row,"credential",512);require(credential.length>=32&&credential.none(Char::isWhitespace))
            val certificate=client.codexDirect?.fingerprint?.lowercase().orEmpty()
            val epoch=NativeMigrationRecoveryCanonical.hash(JSONObject().put("domain","agent-hub.chat-notice-authority.v1")
                .put("target",NativeChatNotificationCodec.target(target)).put("certificate",certificate).put("credential",NativeChatNotificationWire.hash(credential)))
            val authorization=NativeChatNotificationWire.hash(listOf("codex",target.hostId,target.workspaceId,target.runtimeId,epoch,certificate,
                NativeChatNotificationWire.hash(credential)).joinToString("\u0000"))
            require(row.get("epoch")==epoch&&row.get("authorization")==authorization&&result.put(authorization,target)==null)
        }
        return result
    }
}
