package com.agenthub.android

import com.agenthub.core.RuntimeIdentity
import com.agenthub.core.TargetScope
import org.json.JSONObject

/** Client-local presentation identity is distinct from the authenticated computer target.
 * ComputerFileTarget preserves the existing scope and Codex journal keys. No credentials are stored here. */
internal data class NativeUploadTarget(val clientId:String,val scope:TargetScope,val certificate:String?) {
    init {
        require(clientId.isNotBlank()&&clientId.length<=512)
        CodexCommandCodec.validate(CodexCommandCodec.target(scope),"target")
        if(scope.agentId=="codex")require(clientId==scope.hostId)
        require(certificate==null||certificate.isNotEmpty()&&certificate.length<=8192)
    }
    val key=ComputerFileCodec.hash(("native-upload-target-v1\u0000"+clientId+"\u0000"+RuntimeIdentity.readingKey(scope,null)).toByteArray(Charsets.UTF_8))
    fun matches(client:MobileClient)=!client.isDemo&&client.migrationEasyWork==null&&client.id==clientId&&
        RuntimeIdentity.matches(scope,client.scope)&&client.codexDirect?.fingerprint==certificate&&
        (scope.agentId!="easywork"||client.agentConnection!=null)&&
        (client.agentConnection==null||RuntimeIdentity.matches(scope,client.agentConnection.target))
    fun json()=JSONObject().put("clientId",clientId).put("scope",CodexCommandCodec.target(scope))
        .put("certificate",certificate?:JSONObject.NULL)
    fun computerFileTarget():ComputerFileTarget {
        require(scope.agentId in setOf("codex","easywork"))
        return ComputerFileTarget(scope,certificate).also {require(it.clientId==clientId)}
    }
    companion object {
        fun capture(client:MobileClient):NativeUploadTarget {
            require(!client.isDemo&&client.migrationEasyWork==null)
            val scope=checkNotNull(client.scope)
            if(scope.agentId=="easywork")require(client.agentConnection!=null&&RuntimeIdentity.matches(scope,client.agentConnection.target))
            return NativeUploadTarget(client.id,scope,client.codexDirect?.fingerprint).also {require(it.matches(client))}
        }
        fun decode(value:JSONObject):NativeUploadTarget {
            require(value.keys().asSequence().toSet()==setOf("clientId","scope","certificate"))
            require(value.get("clientId") is String)
            CodexCommandCodec.validate(value.getJSONObject("scope"),"target")
            require(value.get("certificate")==JSONObject.NULL||value.get("certificate") is String)
            return NativeUploadTarget(value.getString("clientId"),checkNotNull(HostReadCodec.target(value.getJSONObject("scope"))),value.optional("certificate"))
        }
    }
}
