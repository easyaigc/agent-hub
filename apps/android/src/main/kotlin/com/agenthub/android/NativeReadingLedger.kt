package com.agenthub.android

import com.agenthub.core.ReadingPolicy
import com.agenthub.core.RuntimeIdentity
import com.agenthub.core.TargetScope
import org.json.JSONArray
import org.json.JSONObject

/** Narrow P9 input. Validate transport/authentication before constructing; this port never executes a command. */
data class NativeReadingEvent(val id: String, val target: TargetScope, val threadId: String,
    val kind: String, val position: ThreadReadPosition? = null,
    val runId: String? = null, val requestId: String? = null, val operationId: String? = null) {
    init {
        require(id.isNotEmpty() && id.length <= 512)
        require(kind in setOf("completed", "approval", "question", "plan", "paused", "interrupted", "failed", "uncertain", "stopped"))
        require(position == null || kind == "completed")
        require(listOfNotNull(runId, requestId, operationId).all { it.isNotEmpty() && it.length <= 512 })
        ThreadReadingIdentity.key(target, threadId)
    }
}
data class NativeReadingNotice(val id: String, val kind: String, val runId: String? = null,
    val requestId: String? = null, val operationId: String? = null, val seen: Boolean = false) {
    internal fun json() = JSONObject().put("id",id).put("kind",kind).put("seen",seen).also {
        runId?.let { v -> it.put("runId",v) }; requestId?.let { v -> it.put("requestId",v) }; operationId?.let { v -> it.put("operationId",v) }
    }
}
/** The ledger's event-level projection. Chat unread alone never authorizes an older reminder. */
internal object NativeReadingPresentationPolicy {
    fun mayPresent(event: NativeReadingEvent, latest: ThreadReadPosition?, read: ThreadReadPosition?,
        replyUnread: Boolean, notice: NativeReadingNotice?): Boolean {
        val position = event.position
        return if (position != null) replyUnread && latest == position &&
            ReadingPolicy.newPushUnread(read == position, read?.revision ?: 0, position.revision)
        else notice != null && !notice.seen && notice == NativeReadingNotice(event.id,event.kind,event.runId,event.requestId,event.operationId)
    }
}
data class NativeReadingSnapshot(val target: TargetScope, val threadId: String, val latest: ThreadReadPosition?,
    val sourceUnread: Boolean, val localReaderId: String? = null, val state: ThreadReadState? = null)
data class NativePendingRead(val target: TargetScope, val threadId: String, val readerId: String, val position: ThreadReadPosition)
data class NativeUnreadSnapshot(val revision: Long, val unreadKeys: Set<String>, val countsByTarget: Map<String, Int>) {
    val count get() = unreadKeys.size
    fun count(target: TargetScope) = countsByTarget[ThreadReadingIdentity.scopeKey(target)] ?: 0
    fun isUnread(target: TargetScope, threadId: String) = ThreadReadingIdentity.key(target, threadId) in unreadKeys
    fun badge(enabled: Boolean) = if (enabled) count else 0
}

/** One encrypted, device-owned ledger used by history, visible reading and P9. No message bodies or credentials.
 * Read/merge/write uses the existing storage transaction lock across ledger instances. A failed write publishes nothing.
 * Bounds fail closed, preserving tombstones; dropping a seen ID would allow a delayed delivery to revive it.
 */
class NativeReadingLedger(private val storage: NativeSessionStorage) {
    private data class Row(val target: TargetScope, val threadId: String, var latest: ThreadReadPosition? = null,
        var read: ThreadReadPosition? = null, var pendingReader: String? = null, var replyUnread: Boolean = false,
        val notices: MutableMap<String, NativeReadingNotice> = linkedMapOf()) {
        val unread get() = replyUnread || notices.values.any { !it.seen }
    }
    private data class Ledger(var revision: Long = 0, var scopes: Set<String> = emptySet(), val rows: MutableMap<String, Row> = linkedMapOf())
    companion object {
        const val STORAGE_NAME = "mobile-reading-ledger-v1"
        private const val MAX_ROWS = 16_384
        private const val MAX_NOTICES = 256
        /** Migration validation uses the production parser without opening or mutating a store. */
        fun validateEncoded(text: String) { decode(text) }
        private fun bool(value: JSONObject, key: String) = (value.get(key) as? Boolean) ?: error("Invalid reading boolean: $key")
        private fun decode(saved: String): Ledger {
            require(saved.length <= 8 * 1024 * 1024)
            val root = JSONObject(saved)
            require(root.keys().asSequence().toSet() == setOf("version", "revision", "scopes", "rows") && readingInteger(root, "version") == 1L)
            val revision = readingInteger(root, "revision"); require(revision >= 0)
            val scopes = root.getJSONArray("scopes").strings().toSet()
            require(scopes.size <= MAX_ROWS && scopes.all { it.matches(Regex("[a-f0-9]{64}")) })
            val entries = root.getJSONArray("rows"); require(entries.length() <= MAX_ROWS)
            val ledger = Ledger(revision, scopes)
            entries.objects().forEach { value ->
                require(value.keys().asSequence().all { it in setOf("target", "threadId", "latest", "read", "pendingReader", "replyUnread", "notices") })
                val targetValue = value.getJSONObject("target")
                require(targetValue.keys().asSequence().all { it in setOf("agentId", "hostId", "workspaceId", "runtimeId", "account") })
                val target = HostReadCodec.target(CodexCommandCodec.validate(targetValue,"target"))!!
                val thread = readingString(value, "threadId"); val key = ThreadReadingIdentity.key(target, thread)
                val pending = if (value.has("pendingReader")) readingString(value, "pendingReader") else null
                require(pending == null || pending.matches(Regex("[a-f0-9]{64}")))
                val row = Row(target, thread, if (value.has("latest")) ThreadReadPosition.decode(value.getJSONObject("latest")) else null,
                    if (value.has("read")) ThreadReadPosition.decode(value.getJSONObject("read")) else null, pending, bool(value, "replyUnread"))
                require(pending == null || row.read != null)
                val notices = value.getJSONObject("notices"); require(notices.length() <= MAX_NOTICES)
                notices.keys().forEach { id ->
                    val n = notices.getJSONObject(id)
                    require(n.keys().asSequence().all { it in setOf("id", "kind", "runId", "requestId", "operationId", "seen") })
                    val notice = NativeReadingNotice(readingString(n,"id"),readingString(n,"kind"),
                        if(n.has("runId"))readingString(n,"runId")else null,if(n.has("requestId"))readingString(n,"requestId")else null,
                        if(n.has("operationId"))readingString(n,"operationId")else null,bool(n,"seen"))
                    require(id == notice.id)
                    NativeReadingEvent(id,target,thread,notice.kind,runId=notice.runId,requestId=notice.requestId,operationId=notice.operationId)
                    row.notices[id] = notice
                }
                require(ledger.rows.put(key, row) == null) { "Duplicate reading target" }
            }
            return ledger
        }
    }
    private fun load(): Ledger = storage.read(STORAGE_NAME)?.let { decode(it) } ?: Ledger()
    private fun save(value: Ledger) {
        require(value.rows.size <= MAX_ROWS && value.scopes.size <= MAX_ROWS)
        val rows = JSONArray(value.rows.values.map { row ->
            JSONObject().put("target", CodexCommandCodec.target(row.target)).put("threadId", row.threadId)
                .put("replyUnread", row.replyUnread).put("notices", JSONObject(row.notices.mapValues { it.value.json() })).also {
                    row.latest?.let { p -> it.put("latest", p.json()) }; row.read?.let { p -> it.put("read", p.json()) }
                    row.pendingReader?.let { reader -> it.put("pendingReader", reader) }
                }
        })
        val text = JSONObject().put("version", 1).put("revision", value.revision).put("scopes", JSONArray(value.scopes.toList())).put("rows", rows).toString()
        require(text.toByteArray(Charsets.UTF_8).size <= 8 * 1024 * 1024)
        storage.write(STORAGE_NAME, text)
    }
    private fun projection(value: Ledger): NativeUnreadSnapshot {
        val rows = value.rows.filterValues { ThreadReadingIdentity.scopeKey(it.target) in value.scopes && it.unread }
        return NativeUnreadSnapshot(value.revision, rows.keys.toSet(), rows.values.groupingBy { ThreadReadingIdentity.scopeKey(it.target) }.eachCount())
    }
    private fun transaction(action: (Ledger) -> Unit): NativeUnreadSnapshot = storage.operationTransaction {
        val value = load(); val before = encodeComparison(value); action(value)
        if (encodeComparison(value) != before) { value.revision = Math.addExact(value.revision, 1); save(value) }
        projection(value)
    }
    // Comparison values stay local and are never logged or sent to a service.
    private fun encodeComparison(value: Ledger) = value.scopes.toSet() to value.rows.mapValues { (_, row) ->
        listOf(row.latest, row.read, row.pendingReader, row.replyUnread, row.notices.toMap())
    }
    fun snapshot(): NativeUnreadSnapshot = storage.operationTransaction { projection(load()) }
    /** Read-only transaction after receive(), rechecked by P9 before presentation claim/dispatch.
     * This does not receive the event, mark it visible, approve it or change a delivery receipt.
     */
    fun mayPresent(event: NativeReadingEvent): Boolean = storage.operationTransaction {
        val ledger = load()
        if(ThreadReadingIdentity.scopeKey(event.target) !in ledger.scopes)return@operationTransaction false
        val row = ledger.rows[ThreadReadingIdentity.key(event.target,event.threadId)] ?: return@operationTransaction false
        NativeReadingPresentationPolicy.mayPresent(event,row.latest,row.read,row.replyUnread,row.notices[event.id])
    }
    /** Caller supplies all saved real targets, including offline computers. Removing a connection only changes counting. */
    fun reconcile(scopes: List<TargetScope>, values: List<NativeReadingSnapshot>): NativeUnreadSnapshot = transaction { ledger ->
        ledger.scopes = scopes.map(ThreadReadingIdentity::scopeKey).toSet()
        values.forEach { value ->
            if (ThreadReadingIdentity.scopeKey(value.target) !in ledger.scopes) return@forEach
            val key = ThreadReadingIdentity.key(value.target, value.threadId)
            val row = ledger.rows.getOrPut(key) { Row(value.target, value.threadId, replyUnread = value.sourceUnread) }
            val latest = value.latest ?: value.state?.latest
            require(value.latest == null || value.state == null || value.latest == value.state.latest)
            // Equal revision with different bytes is a conflict, never an authoritative replacement.
            val accepted = latest != null && (row.latest == null || latest == row.latest || latest.revision > row.latest!!.revision)
            val hadLatest = row.latest != null; val changed = latest != row.latest
            if (accepted) row.latest = latest
            // Foreign/desktop reader metadata cannot clear this phone's local record.
            val ack = value.state?.read
            if (row.pendingReader != null && row.pendingReader == value.localReaderId && value.state?.readerId == row.pendingReader && ack == row.read) row.pendingReader = null
            val settled = row.read?.let { read -> row.latest?.let { read == it || read.revision > it.revision } } == true
            row.replyUnread = ReadingPolicy.reconciledUnread(row.replyUnread, hadLatest, accepted, changed, value.sourceUnread, settled)
        }
    }
    /** Old-host fallback: caller must pass the actual latest reply, not an earlier paged prefix. */
    fun localPosition(target: TargetScope, threadId: String, messageId: String, text: String, status: String): ThreadReadPosition = storage.operationTransaction {
        val latest = load().rows[ThreadReadingIdentity.key(target, threadId)]?.latest
        val version = ThreadReadPosition.contentVersion(messageId, text, status)
        ThreadReadPosition(ReadingPolicy.nextRevision(latest?.revision ?: 0, latest?.contentVersion == version), messageId, version)
    }
    fun receive(event: NativeReadingEvent): NativeUnreadSnapshot = transaction { ledger ->
        if (ThreadReadingIdentity.scopeKey(event.target) !in ledger.scopes) return@transaction
        val row = ledger.rows.getOrPut(ThreadReadingIdentity.key(event.target, event.threadId)) { Row(event.target, event.threadId) }
        val position = event.position
        if (position != null) {
            if (position.revision > (row.latest?.revision ?: 0)) {
                row.latest = position
                row.replyUnread = ReadingPolicy.newPushUnread(row.read == position, row.read?.revision ?: 0, position.revision)
            }
        } else {
            val incoming = NativeReadingNotice(event.id,event.kind,event.runId,event.requestId,event.operationId)
            val existing = row.notices[event.id]
            if (existing == null) {
                require(row.notices.size < MAX_NOTICES) { "Reading notice capacity reached" }; row.notices[event.id] = incoming
            } else require(existing.copy(seen=false) == incoming) { "Reading notice ID reused with different intent" }
        }
    }
    /** Check the lease after loading under the storage lock. Only in-memory mutation holds the view lock;
     * encryption/disk writes never hold it, so a UI background transition does not wait for storage I/O.
     */
    fun readVisible(target: TargetScope, threadId: String, readerId: String, lifecycle: NativeReadingLifecycle,
        lease: NativeReadingLifecycle.Lease): NativeUnreadSnapshot? {
        require(readerId.matches(Regex("[a-f0-9]{64}")))
        require(lease.targetKey == ThreadReadingIdentity.key(target, threadId))
        var authorized = false
        val result = transaction { ledger ->
            lifecycle.ifCurrent(lease) {
                authorized = true
                if (ThreadReadingIdentity.scopeKey(target) in ledger.scopes) {
                    val row = ledger.rows[lease.targetKey]
                    if (row != null) {
                        val position = lease.position
                        // The visible version must still be latest at transaction time.
                        if (position != null && row.latest == position && (row.read == null || position.revision >= row.read!!.revision)) {
                            if (row.read != position) { row.read = position; row.pendingReader = readerId }
                            row.replyUnread = false
                        }
                        lease.noticeIds.forEach { id -> row.notices[id]?.let { row.notices[id] = it.copy(seen=true) } }
                    }
                }
            }
        }
        return if (authorized) result else null
    }
    fun notices(target: TargetScope,threadId: String): List<NativeReadingNotice> = storage.operationTransaction {
        load().rows[ThreadReadingIdentity.key(target,threadId)]?.notices?.values?.filter { !it.seen }.orEmpty()
    }
    /** The named Host read response acknowledges only the captured position; it never clears a newer reply. */
    fun acknowledge(original: NativePendingRead, response: JSONObject): NativeUnreadSnapshot {
        require(response.keys().asSequence().toSet() == setOf("hostId","threadId","target","readerId","read"))
        val target = HostReadCodec.target(CodexCommandCodec.validate(response.getJSONObject("target"),"target"))
        require(RuntimeIdentity.matches(target,original.target) && readingString(response,"hostId")==original.target.hostId &&
            readingString(response,"threadId")==original.threadId && readingString(response,"readerId")==original.readerId &&
            ThreadReadPosition.decode(response.getJSONObject("read"))==original.position)
        return transaction { ledger ->
            ledger.rows[ThreadReadingIdentity.key(original.target,original.threadId)]?.let { row ->
                if(row.read==original.position && row.pendingReader==original.readerId)row.pendingReader=null
            }
        }
    }
    fun pendingReads(): List<NativePendingRead> = storage.operationTransaction {
        load().let { ledger -> ledger.rows.values.mapNotNull { row -> row.pendingReader?.takeIf {
            ThreadReadingIdentity.scopeKey(row.target) in ledger.scopes
        }?.let { NativePendingRead(row.target, row.threadId, it, requireNotNull(row.read)) } } }
    }
}
