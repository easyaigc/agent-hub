package com.agenthub.android

import com.agenthub.core.TargetScope
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/** Fixed named, in-process metadata reads. No transport instance or generic method dispatcher exists. */
internal class NativeChatNotificationColdPorts(val plan: NativeChatNotificationColdPlan, private val writer: Boolean) {
    val forbiddenCommands = AtomicInteger()
    val notificationCommands = AtomicInteger()
    val locatorCursors = CopyOnWriteArrayList<String>()
    @Volatile var lastLocatorPage: MobileWorkspace? = null
    val scope get() = TargetScope("codex", null, null, plan.target.hostId, plan.target.workspaceId, plan.target.runtimeId)
    private fun checkClient(client: MobileClient) {
        if(writer && client.id.isEmpty() && client.scope == null)return
        require(client.id == plan.target.hostId && sameScope(client.scope, scope) && client.endpoint == plan.endpoint)
    }
    private fun row(id: String) = JSONObject().put("id", id).put("hostId", plan.target.hostId)
        .put("title", if(id == plan.originalThread) "Owned cold original" else "Owned cold other").put("state", "idle")
    private fun page(ids: List<String>, next: String? = null) = HostReadCodec.workspace(JSONObject()
        .put("hostId", plan.target.hostId).put("name", "Owned cold fixture").put("connectionMode", "desktop-runtime")
        .put("readTarget", CodexCommandCodec.target(scope)).put("agentConnectionState", JSONObject().put("runtimeConnected", true))
        .put("capabilities", JSONArray(listOf("history"))).put("projects", JSONArray()).put("threads", JSONArray(ids.map(::row)))
        .put("nextThreadCursor", next).toString())
    private fun reads(locator: Boolean) = object : HostReads {
        override suspend fun workspace(client: MobileClient, projectCursor: String?, threadCursor: String?): MobileWorkspace {
            checkClient(client); require(projectCursor == null)
            if(!locator) { require(threadCursor == null); return page(listOf(plan.otherThread, plan.originalThread)) }
            val index = if(threadCursor == null) 1 else threadCursor.removePrefix("cold-page-").toInt().also { require(threadCursor == "cold-page-$it" && it in 2..12) }
            locatorCursors.add(threadCursor ?: "first")
            return page(if(index == 12 && plan.case != NativeChatNotificationColdCase.MISSING_THREAD) listOf(plan.originalThread) else listOf(plan.otherThread),
                if(index < 12) "cold-page-${index + 1}" else null).also { lastLocatorPage = it }
        }
        override suspend fun history(client: MobileClient, threadId: String, cursor: String?): MobileHistory {
            checkClient(client); require(threadId in setOf(plan.originalThread, plan.otherThread) && cursor == null)
            return HostReadCodec.history(JSONObject().put("hostId", plan.target.hostId).put("threadId", threadId)
                .put("readTarget", CodexCommandCodec.target(scope)).put("messages", JSONArray()).toString())
        }
    }
    val sessionReads: HostReads = reads(false)
    fun locatorReads(source: NativeChatNotificationSavedConnection): HostReads {
        require(source.target == plan.target && source.snapshot.credentialEpoch == plan.authority)
        checkClient(source.client); return reads(true)
    }
    private fun forbidden(): Nothing { forbiddenCommands.incrementAndGet(); error("Cold route acceptance never executes a conversation command") }
    val commands = object : CodexCommands {
        override suspend fun models(client: MobileClient): List<CodexModel> { checkClient(client); return emptyList() }
        override suspend fun events(client: MobileClient, threadId: String, epoch: String, cursor: Long): EventBatch {
            checkClient(client); return EventBatch(client.id, plan.lease, cursor, false, false, emptyList(), emptyList())
        }
        override suspend fun send(client: MobileClient, intent: ReplyIntent): DeliveryReceipt = forbidden()
        override suspend fun receipt(client: MobileClient, threadId: String, operationId: String): DeliveryReceipt = forbidden()
        override suspend fun createThread(client: MobileClient, projectId: String?, operationId: String): MobileThread = forbidden()
        override suspend fun prepareCreation(client: MobileClient, intent: CreationIntent): DesktopCreation = forbidden()
        override suspend fun creation(client: MobileClient, operationId: String, inspectOnly: Boolean): DesktopCreation = forbidden()
        override suspend fun submitCreation(client: MobileClient, operationId: String): DesktopCreation = forbidden()
        override suspend fun detachCreation(client: MobileClient, operationId: String): DesktopCreation = forbidden()
        override suspend fun open(client: MobileClient, threadId: String, operationId: String, queryOnly: Boolean): DesktopOpen = forbidden()
        override suspend fun stop(client: MobileClient, threadId: String, turnId: String): Unit = forbidden()
        override suspend fun cancelQueue(client: MobileClient, threadId: String, operationId: String): Unit = forbidden()
        override suspend fun answer(client: MobileClient, threadId: String, turnId: String, approvalId: String, decision: String, answers: Map<String,List<String>>): Unit = forbidden()
        override suspend fun upload(client: MobileClient, threadId: String, file: CreationFile, bytes: ByteArray): MobileFile = forbidden()
        override suspend fun uploadCreation(client: MobileClient, operationId: String, file: CreationFile, bytes: ByteArray): Unit = forbidden()
    }
    private fun noPolling(): Nothing { notificationCommands.incrementAndGet(); error("Cold route acceptance does not poll or register notifications") }
    val notices = object : NativeChatNotificationCommands {
        override suspend fun register(snapshot: NativeChatNotificationSnapshot, identity: NativeChatNotificationIdentity, preferences: NativeChatNotificationPreferences): NativeChatNotificationStatus = noPolling()
        override suspend fun preferences(snapshot: NativeChatNotificationSnapshot, deviceId: String, preferences: NativeChatNotificationPreferences): NativeChatNotificationStatus = noPolling()
        override suspend fun unregister(snapshot: NativeChatNotificationSnapshot, deviceId: String): NativeChatNotificationStatus = noPolling()
        override suspend fun status(snapshot: NativeChatNotificationSnapshot, deviceId: String): NativeChatNotificationStatus = noPolling()
        override suspend fun events(snapshot: NativeChatNotificationSnapshot, deviceId: String, after: String): NativeChatNotificationFeed = noPolling()
    }
}
