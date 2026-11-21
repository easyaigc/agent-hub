package com.agenthub.android

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.util.UUID

internal data class NativeDesktopOpenResult(val record: SavedOperation,
    val workspace: MobileWorkspace? = null, val history: MobileHistory? = null) {
    val ownerConfirmed get() = record.phase == "opened" && history?.runtime?.canControl == true &&
        (workspace?.runtimeConnected == true || workspace?.runtimeConnected == null && workspace?.scope?.agentId == "codex")
}

/** A named desktop navigation only. No input, attachment, draft or model operation is available here. */
internal class NativeDesktopOpenFlow(private val journal: NativeOperationJournal,
    private val reads: HostReads, private val commands: AgentCommands,
    private val timeoutMillis: Long = 35_000,
    private val queryOriginal: suspend (MobileClient, SavedOperation) -> SavedOperation) {
    init { require(timeoutMillis in 1..35_000) }
    private val active = mutableSetOf<String>()
    private suspend fun pending(client: MobileClient, thread: String): List<SavedOperation> =
        client.operationTargets(thread).flatMap { journal.records(it) }.distinctBy { it.target to it.id }
            .filter { it.kind == "open" && it.phase !in setOf("opened", "rejected", "failed") }
    suspend fun hasPending(client: MobileClient, thread: String) = pending(client, thread).isNotEmpty()
    private fun current(guard: () -> Boolean) {
        if (!guard()) throw CancellationException("打开目标已改变，原操作保留")
    }
    private fun validateWorkspace(client: MobileClient,value: MobileWorkspace) {
        val connected=value.runtimeConnected==true || client.scope?.agentId=="codex" && value.runtimeConnected!=false
        require(value.hostId == client.id && sameScope(value.scope, client.scope) && connected &&
            "commands:target-bound:v1" in value.capabilities && "desktop:open" in value.capabilities) { "此电脑尚未提供已连接的主动打开能力" }
    }
    private suspend fun workspace(client: MobileClient, guard: () -> Boolean): MobileWorkspace {
        current(guard)
        val value = reads.workspace(client)
        validateWorkspace(client,value)
        current(guard)
        return value
    }
    suspend fun open(client: MobileClient, thread: String, guard: () -> Boolean,
        progress: (String) -> Unit = {}, sourceKey: String? = null, sourceVersion: String? = null,
        parentOperationId: String? = null, preflight: MobileWorkspace? = null): NativeDesktopOpenResult {
        require(!client.isDemo && client.scope != null && thread.isNotEmpty())
        val key = com.agenthub.core.RuntimeIdentity.cacheKey(client.id, client.scope, thread, null)
        check(synchronized(active) { active.add(key) }) { "正在打开此对话，请勿重复提交" }
        var original: SavedOperation? = null
        var pendingChecked=false
        var priorRecordsExist=false
        try {
            // One total budget covers preflight, the original operation, receipt queries and
            // fresh owner checks. Each transport read must not receive another35seconds.
            return withTimeout(timeoutMillis) {
                current(guard)
                val pending = pending(client, thread)
                pendingChecked=true;priorRecordsExist=pending.isNotEmpty()
                require(pending.size <= 1) { "存在多个原打开记录，请分别查询；不会提交新操作" }
                val existing = pending.singleOrNull()
                original=existing // An old UNKNOWN remains the original even when network preflight fails.
                current(guard)
                if(preflight==null)workspace(client, guard) else {validateWorkspace(client,preflight);current(guard)}
                val id = existing?.id ?: UUID.randomUUID().toString()
                var record = existing ?: journal.prepare(client, thread, "open",
                    JSONObject().put("hostId", client.id).put("threadId", thread).put("clientOperationId", id),
                    sourceKey, sourceVersion, parentOperationId)
                original = record
                require(record.target in client.operationTargets(thread) && record.kind == "open" &&
                    JSONObject(record.intent).getString("hostId") == client.id && JSONObject(record.intent).getString("threadId") == thread) { "原打开记录不属于此目标" }
                progress(if (existing == null) "正在原桌面打开…" else "查询原打开记录…")
                if (existing == null) {
                    current(guard)
                    record = journal.markSubmitting(record.target, id)
                    original = record
                    current(guard)
                    val result = commands.open(client, thread, id)
                    require(result.hostId == client.id && result.threadId == thread && result.operationId == id)
                    record = journal.observed(record.target, id, result.phase, encodeOpen(result), result.error)
                } else {
                    // Includes unrecorded/404/UNKNOWN: a previously persisted ID is never posted here.
                    record = queryOriginal(client, record)
                }
                original = record
                current(guard)
                while (record.phase in setOf("opening", "outcome-unknown") && record.error.isNullOrEmpty()) {
                    delay(350)
                    current(guard)
                    record = queryOriginal(client, record)
                    original = record
                    current(guard)
                }
                if (record.phase != "opened") return@withTimeout NativeDesktopOpenResult(record)
                // EasyWork's history projection alone is not an owner receipt. The exact original
                // opened receipt is mandatory, then independently read this live target and thread.
                val freshWorkspace = workspace(client, guard)
                val history = reads.history(client, thread)
                require(history.hostId == client.id && history.threadId == thread && sameScope(history.scope, client.scope)) { "打开后的运行时目标不匹配" }
                current(guard)
                NativeDesktopOpenResult(record, freshWorkspace, history)
            }
        } catch (failure: Exception) {
            val record = original ?: run {
                if(failure is CancellationException && failure !is TimeoutCancellationException)throw failure
                if(!pendingChecked||priorRecordsExist)throw failure
                throw AgentOperationNotSubmitted("打开前检查未通过，草稿和附件已保留",failure)
            }
            val uncertain = withContext(NonCancellable) { journal.uncertain(record.target, record.id, "打开结果仍待确认，请查询原记录；草稿和附件已保留") }
            if (failure is CancellationException && failure !is TimeoutCancellationException) throw failure
            return NativeDesktopOpenResult(uncertain)
        } finally {
            synchronized(active) { active.remove(key) }
        }
    }
}
