package com.agenthub.android

import com.agenthub.core.MobileRules
import com.agenthub.core.RuntimeIdentity
import com.agenthub.core.TargetScope
import org.json.JSONObject
import java.security.MessageDigest

/** Same UTF-8 framing and position schema as iOS ReadPosition and protocol/read-state. */
data class ThreadReadPosition(val revision: Long, val messageId: String, val contentVersion: String) {
    init {
        require(revision in 1..9_007_199_254_740_991L)
        require(messageId.isNotEmpty() && messageId.length <= 512)
        require(contentVersion.matches(Regex("[a-f0-9]{64}")))
    }
    fun matches(messageId: String, text: String, status: String?, projection: ReadContentProjection? = null): Boolean {
        if (this.messageId != messageId) return false
        val display = contentVersion(messageId, text, status ?: "completed")
        return contentVersion == if (projection?.displayVersion == display) projection.sourceVersion else display
    }
    internal fun json() = JSONObject().put("revision", revision).put("messageId", messageId).put("contentVersion", contentVersion)
    companion object {
        fun contentVersion(messageId: String, text: String, status: String) = readingSHA(
            MobileRules.contentVersionPreimage(messageId, text, status))
        fun decode(value: JSONObject): ThreadReadPosition {
            require(value.keys().asSequence().toSet() == setOf("revision", "messageId", "contentVersion"))
            return ThreadReadPosition(readingInteger(value, "revision"), readingString(value, "messageId"), readingString(value, "contentVersion"))
        }
    }
}

data class ReadContentProjection(val sourceVersion: String, val displayVersion: String)
data class ThreadReadState(val latest: ThreadReadPosition, val readerId: String? = null, val read: ThreadReadPosition? = null) {
    companion object {
        fun decode(value: JSONObject): ThreadReadState {
            require(value.keys().asSequence().all { it in setOf("version", "latest", "readerId", "read") })
            require(readingInteger(value, "version") == 1L)
            val reader = if (value.has("readerId")) readingString(value, "readerId") else null
            require(reader == null || reader.matches(Regex("[a-f0-9]{64}")))
            return ThreadReadState(ThreadReadPosition.decode(value.getJSONObject("latest")), reader,
                if (value.has("read")) ThreadReadPosition.decode(value.getJSONObject("read")) else null)
        }
    }
}

internal fun readingSHA(text: String): String = MessageDigest.getInstance("SHA-256")
    .digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
internal fun readingInteger(value: JSONObject, field: String): Long {
    val number = value.get(field)
    require(number is Number && number.toDouble().isFinite() && number.toDouble() == number.toLong().toDouble() && number.toDouble() in 0.0..9_007_199_254_740_991.0) { "Invalid reading integer: $field" }
    return (number as Number).toLong()
}
internal fun readingString(value: JSONObject, field: String): String = (value.get(field) as? String)
    ?: error("Invalid reading string: $field")

/** No connection address, project grouping or Codex login account participates in this identity. */
object ThreadReadingIdentity {
    fun key(target: TargetScope, threadId: String): String {
        require(listOf(target.agentId, target.hostId, target.workspaceId, target.runtimeId, threadId).all { it.isNotEmpty() && it.length <= 4096 })
        return readingSHA(RuntimeIdentity.readingKey(target, threadId))
    }
    fun scopeKey(target: TargetScope) = readingSHA(RuntimeIdentity.readingKey(target, null))
    fun readerId(deviceId: String, target: TargetScope, threadId: String) = readingSHA(deviceId + ":" + RuntimeIdentity.readingKey(target, threadId))
}

/** View-owned lease: changing target, covering content, backgrounding or leaving invalidates old callbacks. */
class NativeReadingLifecycle {
    data class Lease internal constructor(internal val owner: Any, internal val generation: Long,
        val targetKey: String, val position: ThreadReadPosition?, val noticeIds: Set<String>)
    private val owner = Any()
    private var generation = 0L
    private var foreground = false
    private var uncovered = false
    private var selected: String? = null
    private var displayed: ThreadReadPosition? = null
    private var notices = emptySet<String>()
    @Synchronized fun present(target: TargetScope, threadId: String, position: ThreadReadPosition?, noticeIds: Set<String> = emptySet()) {
        generation++; selected = ThreadReadingIdentity.key(target, threadId); displayed = position; notices = noticeIds.toSet()
    }
    @Synchronized fun foreground(value: Boolean) { if (foreground != value) { generation++; foreground = value } }
    @Synchronized fun uncovered(value: Boolean) { if (uncovered != value) { generation++; uncovered = value } }
    @Synchronized fun leave() { generation++; selected = null; displayed = null; notices = emptySet() }
    @Synchronized fun visible(target: TargetScope, threadId: String, messageId: String, text: String, status: String?,
        latestContentVisible: Boolean, projection: ReadContentProjection? = null): Lease? {
        val key = ThreadReadingIdentity.key(target, threadId)
        if (!foreground || !uncovered || !latestContentVisible || selected != key ||
            displayed?.matches(messageId, text, status, projection) != true) return null
        return Lease(owner, generation, key, displayed, notices)
    }
    /** Notice visibility is explicit; rendering an approval is never an approval decision. */
    @Synchronized fun visibleNotices(target: TargetScope, threadId: String, visibleIds: Set<String>): Lease? {
        val key = ThreadReadingIdentity.key(target, threadId)
        if (!foreground || !uncovered || selected != key || !notices.containsAll(visibleIds) || visibleIds.isEmpty()) return null
        return Lease(owner, generation, key, null, visibleIds.toSet())
    }
    @Synchronized internal fun <T> ifCurrent(lease: Lease, block: () -> T): T? =
        if (lease.owner === owner && lease.generation == generation && foreground && uncovered && selected == lease.targetKey) block() else null
}
