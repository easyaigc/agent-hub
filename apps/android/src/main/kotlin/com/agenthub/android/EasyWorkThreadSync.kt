package com.agenthub.android

import com.agenthub.core.ThreadSyncRules
import com.agenthub.core.ThreadSyncState
import org.json.JSONObject
import java.security.MessageDigest

internal class InvalidThreadDelta : Exception("电脑增量历史无效，需要重新读取完整历史")

/** Candidate state stays private until platform SHA-256 verification succeeds. */
internal class EasyWorkThreadSync(private val state: ThreadSyncState=ThreadSyncRules.empty(),val cacheWeight: Long=0) {
    fun parameters(threadId: String)=JSONObject(ThreadSyncRules.parameters(state,threadId))
    fun merging(value: JSONObject,threadId: String): Pair<EasyWorkThreadSync,JSONObject> {
        if(value.optJSONObject("snapshot")?.optJSONObject("thread")?.optString("id")!=threadId)
            throw HostReadError("电脑返回的聊天身份不匹配")
        val candidate=ThreadSyncRules.merging(state,value.toString(),threadId)?:throw InvalidThreadDelta()
        for(check in candidate.textChecks) {
            val hash=MessageDigest.getInstance("SHA-256").digest(check.text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
            if(hash!=check.sha256)throw InvalidThreadDelta()
        }
        // Account for both parsed wire representations conservatively. The
        // session can return large complete reads without retaining this cache.
        return EasyWorkThreadSync(candidate.next,candidate.wireJson.length.toLong()*4) to JSONObject(candidate.wireJson)
    }
}
