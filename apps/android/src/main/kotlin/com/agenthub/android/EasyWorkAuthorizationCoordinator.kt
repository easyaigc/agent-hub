package com.agenthub.android

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

internal class EasyWorkAuthorizationBlocked(val confirmed: Boolean): IllegalStateException(
    if(confirmed) "设备授权已移除，请重新配对" else "设备授权撤销结果待确认，请核对电脑状态")

/** All adapters must serialize a short read/modify/write for the exact storage root.
 * Network and Keystore work stay outside this transaction. Native storage already supplies it.
 */
internal interface EasyWorkAuthorizationRecords {
    val root: String
    fun <T> transaction(block: ()->T): T
    fun read(key: String): String?
    fun write(key: String,value: String)
    fun remove(key: String)
}

/** Per-root/ref network coordination plus a durable revoke fence, independent of client lifetime.
 * The fence is written before DELETE, retained after confirmation, and never authorizes a grant.
 */
internal class EasyWorkAuthorizationCoordinator(private val records: EasyWorkAuthorizationRecords) {
    constructor(storage: NativeSessionStorage): this(object: EasyWorkAuthorizationRecords {
        override val root get()=storage.coordinationKey
        override fun <T> transaction(block: ()->T)=storage.operationTransaction(block)
        override fun read(key: String)=storage.read(key)
        override fun write(key: String,value: String) { storage.write(key,value) }
        override fun remove(key: String) { storage.remove(key) }
    })
    private class Locks { val refresh=Mutex();val revoke=Mutex() }
    companion object {
        private val locks=ConcurrentHashMap<Pair<String,String>,Locks>()
        internal fun fenceKey(ref: String)="easywork-authorization-fence:"+easyHash(ref.toByteArray(Charsets.UTF_8))
        private val grantFields=listOf("protocol","grantId","deviceId","binding","target","keyVersion","scopes","accessToken","refreshToken","accessExpiresAt","refreshExpiresAt")
    }
    private fun locks(ref: String)=locks.computeIfAbsent(records.root to ref) { Locks() }
    suspend fun <T> withRefresh(ref: String,block: suspend ()->T): T=locks(ref).refresh.withLock { block() }
    suspend fun <T> withRevoke(ref: String,block: suspend ()->T): T=locks(ref).revoke.withLock { block() }
    private fun read(key: String)=records.read(key)?.let(::JSONObject)
    private fun write(key: String,value: JSONObject)=records.write(key,value.toString())
    private fun guard(ref: String): JSONObject?=read(fenceKey(ref))?.also {
        require(it.get("version")==1&&it.getString("ref")==ref&&UUID.fromString(it.getString("epoch")).toString()==it.getString("epoch")) { "设备授权保存记录无效，原记录已保留" }
        if(it.has("revoke"))require(it.getJSONObject("revoke").getString("state") in listOf("pending","confirmed")) { "设备授权保存记录无效，原记录已保留" }
    }
    private fun ensureGuard(ref: String)=guard(ref)?:JSONObject().put("version",1).put("ref",ref).put("epoch",UUID.randomUUID().toString()).also {write(fenceKey(ref),it)}
    private fun live(ref: String): JSONObject {
        val fence=guard(ref)
        fence?.optJSONObject("revoke")?.let { throw EasyWorkAuthorizationBlocked(it.getString("state")=="confirmed") }
        val saved=read(ref)?:error("设备授权已移除，请重新配对")
        if(saved.has("revokeOperationId"))throw EasyWorkAuthorizationBlocked(false)
        return saved
    }
    fun assertAuthorized(ref: String,binding: JSONObject,token: String?=null)=records.transaction {
        val saved=live(ref);val grant=saved.getJSONObject("grant")
        require(easyCanonical(grant.getJSONObject("binding"))==easyCanonical(binding)) { "设备授权目标或密钥已改变，请重新配对" }
        require(token==null||grant.getString("accessToken")==token) { "设备授权已更新，请重新核对原操作" }
    }
    /** A late pairing response can neither resurrect a revoked ref nor overwrite a rotated grant. */
    fun install(ref: String,origin: String,grant: JSONObject)=records.transaction {
        guard(ref)?.optJSONObject("revoke")?.let {throw EasyWorkAuthorizationBlocked(it.getString("state")=="confirmed")}
        val saved=read(ref)
        if(saved!=null) {
            if(saved.has("revokeOperationId"))throw EasyWorkAuthorizationBlocked(false)
            require(saved.getString("origin")==origin&&saved.getJSONObject("grant").getString("grantId")==grant.getString("grantId")&&easyCanonical(saved.getJSONObject("grant").getJSONObject("binding"))==easyCanonical(grant.getJSONObject("binding")))
        } else {ensureGuard(ref);write(ref,JSONObject().put("origin",origin).put("grant",grant))}
    }
    data class Refresh(val root: String,val ref: String,val saved: String,val epoch: String,val operationId: String,val token: String,val body: String) {
        val json get()=JSONObject(saved)
    }
    data class Access(val token: String?=null,val refresh: Refresh?=null)
    /** Validation runs against the latest snapshot while the root transaction is held. */
    fun access(ref: String,usableToken: (JSONObject)->String?): Access=records.transaction {
        val saved=live(ref);val token=usableToken(saved)
        if(token!=null)return@transaction Access(token=token)
        val fence=ensureGuard(ref)
        val pending=saved.optJSONObject("refreshOperation")?:JSONObject().put("operationId",UUID.randomUUID().toString()).put("token",saved.getJSONObject("grant").getString("refreshToken")).also {saved.put("refreshOperation",it);write(ref,saved)}
        val id=pending.getString("operationId");UUID.fromString(id)
        val originalToken=pending.getString("token");require(originalToken.matches(Regex("[A-Za-z0-9_-]{43}")))
        Access(refresh=Refresh(records.root,ref,saved.toString(),fence.getString("epoch"),id,originalToken,easyCanonical(JSONObject().put("operationId",id))))
    }
    private fun grantState(grant: JSONObject)=easyCanonical(JSONObject().apply {grantFields.forEach {put(it,grant.get(it))}})
    private fun merge(old: JSONObject,next: JSONObject): JSONObject=JSONObject(old.toString()).also {result->
        next.keys().forEach {key->val value=next.get(key);val previous=result.opt(key);result.put(key,if(previous is JSONObject&&value is JSONObject)merge(previous,value)else value)}
    }
    /** A verified response may update only the still-live original refresh, using the latest JSON. */
    fun refreshed(ref: String,attempt: Refresh,verifiedGrant: JSONObject): String=records.transaction {
        require(attempt.root==records.root&&attempt.ref==ref)
        val saved=live(ref);val fence=ensureGuard(ref);val pending=saved.getJSONObject("refreshOperation")
        check(fence.getString("epoch")==attempt.epoch&&pending.getString("operationId")==attempt.operationId&&pending.getString("token")==attempt.token&&grantState(saved.getJSONObject("grant"))==grantState(attempt.json.getJSONObject("grant"))) { "原设备授权刷新已被新的状态替代，原记录已保留" }
        // Preserve unknown grant fields after the caller validates the complete incoming grant.
        val merged=merge(saved.getJSONObject("grant"),verifiedGrant)
        val receipts=fence.optJSONObject("refreshResults")?:JSONObject().also {fence.put("refreshResults",it)}
        val receipt=JSONObject(pending.toString()).apply {remove("token");put("originalPending",JSONObject(pending.toString()).apply {remove("token")});put("tokenHash",easyHash(attempt.token.toByteArray()));put("body",attempt.body);put("grantHash",easyHash(easyCanonical(verifiedGrant).toByteArray()))}
        val previous=receipts.optJSONObject(attempt.operationId)
        if(previous==null)receipts.put(attempt.operationId,receipt) else require(previous.getString("body")==attempt.body&&previous.getString("tokenHash")==receipt.getString("tokenHash"))
        // Durable audit precedes removal of the pending intent; an interrupted ref save resumes it.
        write(fenceKey(ref),fence)
        saved.put("grant",merged).remove("refreshOperation");write(ref,saved)
        merged.getString("accessToken")
    }
    data class Revoke(val root: String,val ref: String,val saved: String,val operationId: String,val body: String,val intentHash: String) {
        val json get()=JSONObject(saved)
    }
    private fun revokeIntent(ref: String,saved: JSONObject,id: String): Revoke {
        val body=easyCanonical(JSONObject().put("operationId",id));val grant=saved.getJSONObject("grant")
        val fingerprint=easyHash(easyCanonical(JSONObject().put("ref",ref).put("origin",saved.getString("origin")).put("binding",grant.getJSONObject("binding")).put("grantId",grant.getString("grantId")).put("body",body).put("tokenHash",easyHash(grant.getString("accessToken").toByteArray()))).toByteArray())
        return Revoke(records.root,ref,saved.toString(),id,body,fingerprint)
    }
    private fun sameIdentity(current: JSONObject,original: JSONObject) {
        require(current.getString("origin")==original.getString("origin")&&current.getJSONObject("grant").getString("grantId")==original.getJSONObject("grant").getString("grantId")&&easyCanonical(current.getJSONObject("grant").getJSONObject("binding"))==easyCanonical(original.getJSONObject("grant").getJSONObject("binding"))) { "设备授权记录目标已变化，原记录已保留" }
    }
    fun hasRevokeWork(ref: String): Boolean=records.transaction {
        val revoke=guard(ref)?.optJSONObject("revoke")
        records.read(ref)!=null||revoke?.getString("state")=="pending"
    }
    /** Fence first. Legacy unknown DELETE IDs and all extra saved fields remain intact. */
    fun revocation(ref: String,verify: (JSONObject)->Unit): Revoke?=records.transaction {
        val observed=guard(ref);val existing=observed?.optJSONObject("revoke")
        if(existing!=null) {
            val original=JSONObject(existing.getString("saved"));verify(original)
            val attempt=revokeIntent(ref,original,existing.getString("operationId"));require(attempt.intentHash==existing.getString("intentHash")&&attempt.body==existing.getString("body"))
            if(existing.getString("state")=="confirmed") {
                read(ref)?.let {sameIdentity(it,original);existing.put("localReceipt",it);write(fenceKey(ref),requireNotNull(observed))}
                records.remove(ref);return@transaction null
            }
            require(existing.getString("state")=="pending");return@transaction attempt
        }
        val saved=read(ref)?:return@transaction null
        verify(saved)
        val fence=observed?:ensureGuard(ref)
        val id=saved.optional("revokeOperationId")?:UUID.randomUUID().toString();UUID.fromString(id)
        saved.put("revokeOperationId",id)
        val attempt=revokeIntent(ref,saved,id)
        fence.put("epoch",UUID.randomUUID().toString()).put("revoke",JSONObject().put("state","pending").put("operationId",id).put("body",attempt.body).put("intentHash",attempt.intentHash).put("saved",attempt.saved))
        write(fenceKey(ref),fence) // Survives a crash or a failed following grant-record write.
        write(ref,saved)
        attempt
    }
    fun revoked(ref: String,attempt: Revoke)=records.transaction {
        require(attempt.root==records.root&&attempt.ref==ref)
        val fence=requireNotNull(guard(ref));val revoke=fence.getJSONObject("revoke")
        require(revoke.getString("operationId")==attempt.operationId&&revoke.getString("intentHash")==attempt.intentHash)
        read(ref)?.let {sameIdentity(it,attempt.json);revoke.put("localReceipt",it)}
        revoke.put("state","confirmed");write(fenceKey(ref),fence)
        records.remove(ref) // A failed delete remains fenced; resume finishes only local cleanup.
    }
}
