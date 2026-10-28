package com.agenthub.android

import org.json.JSONObject

/** Captured saved connection. Epoch survives process death; account/path/name/online changes are not authority. */
internal class NativeChatNotificationSavedConnection private constructor(val client: MobileClient, private val credential: String,
    val snapshot: NativeChatNotificationSnapshot, val workerKey: String) {
    val target get() = snapshot.target
    fun matches(client: MobileClient) = !client.isDemo && client.scope?.agentId == "codex" && client.id == target.hostId &&
        NativeChatNoticeTarget.from(requireNotNull(client.scope)) == target && client.endpoint == this.client.endpoint &&
        client.codexDirect?.fingerprint?.lowercase() == this.client.codexDirect?.fingerprint?.lowercase()
    fun reads(): HostReads = NativeHostReads { id -> require(id == target.hostId); credential }
    internal fun json() = JSONObject().put("client", encodeClient(client)).put("credential", credential)
        .put("epoch", snapshot.credentialEpoch).put("authorization", snapshot.authorizationFingerprint)
    override fun toString() = "NativeChatNotificationSavedConnection(redacted)"
    companion object {
        /** Call on IO with a client and token captured from the same saved Session/storage generation. */
        fun capture(client: MobileClient, credential: String): NativeChatNotificationSavedConnection {
            require(!client.isDemo && client.scope?.agentId == "codex" && client.agentConnection == null)
            val frozen = decodeClient(encodeClient(client))
            val target = NativeChatNoticeTarget.from(requireNotNull(frozen.scope)); require(frozen.id == target.hostId)
            val epoch = NativeChatRecoveryCanonical.hash(JSONObject().put("domain", "agent-hub.chat-notice-authority.v1")
                .put("target", NativeChatNotificationCodec.target(target)).put("certificate", frozen.codexDirect?.fingerprint?.lowercase().orEmpty())
                .put("credential", NativeChatNotificationWire.hash(credential)))
            val snapshot = NativeChatNotificationSnapshot(frozen, credential, epoch)
            val worker = NativeChatNotificationWire.hash(snapshot.authorizationFingerprint + "\u0000" + NativeHostReads.baseAddress(requireNotNull(frozen.endpoint)))
            return NativeChatNotificationSavedConnection(frozen, credential, snapshot, worker)
        }
        internal fun decode(raw: JSONObject): NativeChatNotificationSavedConnection {
            NativeChatNotificationWire.keys(raw, setOf("client", "credential", "epoch", "authorization"))
            val value = capture(decodeClient(raw.getJSONObject("client")), NativeChatNotificationWire.text(raw, "credential", 512))
            require(value.snapshot.credentialEpoch == NativeChatNotificationWire.text(raw, "epoch") &&
                value.snapshot.authorizationFingerprint == NativeChatNotificationWire.text(raw, "authorization"))
            return value
        }
    }
}

internal data class NativeChatNotificationSessionState(val ready: Boolean, val foreground: Boolean,
    val navigation: String, val readingSurface: Boolean, val readingReady: Boolean = true)

/** Session-owned Main boundary. Production must use the same storage and phoneReading.ledger, never a second ledger. */
internal interface NativeChatNotificationSessionPort {
    val storage: NativeSessionStorage
    val reading: NativeChatNotificationReading
    fun state(): NativeChatNotificationSessionState
    /** Capture realClients on Main, read their tokens on IO, and recheck metadata + Session generation on Main. */
    suspend fun savedConnections(): List<NativeChatNotificationSavedConnection>
    /** Exact active set from the Host, applied to this Session's existing P7 ledger on IO, published on Main. */
    suspend fun reconcileActive(target: NativeChatNoticeTarget, ids: Set<String>, current: () -> Boolean)
    /** Main-only, no suspension: validate the saved connection and adopt the actual read result, preserving all draft maps. */
    fun openExistingThread(location: NativeChatNotificationLocation, current: () -> Boolean): Boolean
}

/** Only constructed from an actual complete-target workspace response containing the original thread. */
internal class NativeChatNotificationLocation private constructor(val connection: NativeChatNotificationSavedConnection,
    val workspace: MobileWorkspace, val thread: MobileThread) {
    companion object {
        fun from(connection: NativeChatNotificationSavedConnection, workspace: MobileWorkspace, threadId: String): NativeChatNotificationLocation? {
            if (workspace.hostId != connection.target.hostId || workspace.scope?.agentId != "codex" ||
                NativeChatNoticeTarget.from(requireNotNull(workspace.scope)) != connection.target) return null
            val thread = workspace.threads.singleOrNull { it.id == threadId && it.hostId == connection.target.hostId } ?: return null
            return NativeChatNotificationLocation(connection, workspace, thread)
        }
    }
}

/** Named existing HostReads only. No synthetic thread, owner choice, execution call or arbitrary REST forwarding. */
internal class NativeChatNotificationThreadLocator(private val reads: (NativeChatNotificationSavedConnection) -> HostReads = { it.reads() }) {
    suspend fun locate(connection: NativeChatNotificationSavedConnection, event: NativeChatNotificationEvent, current: () -> Boolean): NativeChatNotificationLocation? {
        val thread = event.threadId ?: return null
        if (event.target != connection.target || !current()) return null
        val reader = reads(connection); val seen = mutableSetOf<String>(); var cursor: String? = null
        repeat(8) {
            if (!current()) return null
            val page = reader.workspace(connection.client, threadCursor = cursor)
            if (!current() || page.hostId != connection.target.hostId || page.scope?.agentId != "codex" ||
                NativeChatNoticeTarget.from(requireNotNull(page.scope)) != connection.target) return null
            require(page.threads.size <= 2048)
            NativeChatNotificationLocation.from(connection, page, thread)?.let { return it }
            val next = page.nextThreadCursor ?: return null
            if (next.length !in 1..512 || !seen.add(next)) return null
            cursor = next
        }
        return null
    }
}
