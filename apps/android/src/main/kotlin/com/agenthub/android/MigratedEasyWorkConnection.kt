package com.agenthub.android

import com.agenthub.core.TargetScope
import org.json.JSONObject
import java.net.URI

/** Descriptive imported state. It deliberately has no credential reference, pin or device identity. */
class MigratedEasyWorkConnection private constructor(private val encoded: String) {
    private val value get()=JSONObject(encoded)
    val archiveId get()=value.getString("archiveId")
    val target: TargetScope get()=HostReadCodec.target(value.getJSONObject("target"))!!
    val preference get()=value.getString("preference")
    val candidateOrigins get()=value.getJSONArray("candidateOrigins").strings()
    val requiresPairing get()=true
    val credentialState get()="none"
    fun json()=value
    fun withPreference(preference: String)=decode(value.put("preference",preference))
    override fun equals(other: Any?)=other is MigratedEasyWorkConnection&&encoded==other.encoded
    override fun hashCode()=encoded.hashCode()
    override fun toString()="MigratedEasyWorkConnection(requiresPairing=true)"
    companion object {
        fun decode(value: JSONObject): MigratedEasyWorkConnection {
            NativeChatNotificationWire.keys(value,setOf("version","provider","archiveId","target","requiresPairing","credentialState","preference","candidateOrigins"))
            require(value.get("version")==1&&value.get("provider")=="easywork"&&value.get("requiresPairing")==true&&value.get("credentialState")=="none")
            require((value.get("archiveId") as String).matches(Regex("[a-f0-9]{64}")))
            CodexCommandCodec.validate(value.getJSONObject("target"),"target")
            val target=requireNotNull(HostReadCodec.target(value.getJSONObject("target")))
            require(target.agentId=="easywork"&&!target.accountAuthority.isNullOrBlank()&&!target.accountSubject.isNullOrBlank())
            require(value.get("preference") in listOf("automatic","lan-only","relay-only"))
            val origins=value.getJSONArray("candidateOrigins").strings();require(origins.size<=32&&origins.distinct()==origins)
            origins.forEach {require(origin(it)==it)}
            return MigratedEasyWorkConnection(easyCanonical(value))
        }
        /** Parse inert display addresses only. Never uses a network client or imports certificate trust. */
        internal fun origin(endpoint: String): String {
            require(endpoint.length in 1..8192&&!endpoint.any {it.isISOControl()})
            val uri=URI(endpoint)
            require(uri.scheme in setOf("https","http","wss","ws")&&uri.host!=null&&uri.rawUserInfo==null&&uri.rawQuery==null&&uri.rawFragment==null&&uri.port in -1..65535&&uri.port!=0)
            require(uri.rawPath in listOf("","/"))
            return endpoint.trimEnd('/')
        }
    }
}

/** All reads, command-status queries and writes stop before resolving an imported provider. */
internal object NativeMigrationEasyWorkGate {
    const val STATUS="需要重新配对 · 查看缓存"
    const val MESSAGE="迁入的聊天和回执仅供查看。请扫描电脑的新连接二维码，或输入新连接码重新配对。"
    fun previousCredentialRef(client: MobileClient?): String?=client?.takeIf {it.migrationEasyWork==null}?.agentConnection?.credentialRef
    fun requireReady(client: MobileClient) {require(client.migrationEasyWork==null) {MESSAGE}}
    fun validate(client: MobileClient) {
        client.migrationEasyWork?.let {
            require(client.mode=="easywork-runtime"&&sameScope(client.scope,it.target)&&client.agentConnection==null&&client.codexDirect==null&&client.storageIdentity==null&&client.requiresReadTarget&&!client.isDemo)
        }
    }
}
