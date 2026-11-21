package com.agenthub.android

import androidx.test.platform.app.InstrumentationRegistry
import com.agenthub.core.TargetScope
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

/** Current MobileClient migration marker; no original credential, identity, provider or OS notice is needed. */
class NativeEasyWorkNotificationMigrationAdmissionTest {
    @Test fun importedConnectionsStayOutOfSavedAuthorityCaptureAndCoordinator() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val storage = NativeSessionStorage(context, "p4-migration-admission-" + UUID.randomUUID())
        val target = TargetScope("easywork", "https://fixture.invalid", "fixture-subject", "fixture-host", "fixture-workspace", "fixture-runtime")
        val migrated = MigratedEasyWorkConnection.decode(JSONObject().put("version", 1).put("provider", "easywork")
            .put("archiveId", "a".repeat(64)).put("target", CodexCommandCodec.target(target)).put("requiresPairing", true)
            .put("credentialState", "none").put("preference", "automatic").put("candidateOrigins", JSONArray(listOf("https://fixture.invalid"))))
        val client = MobileClient(target.hostId, "Imported fixture", target, "https://fixture.invalid", mode = "easywork-runtime",
            requiresReadTarget = true, migrationEasyWork = migrated)
        val parent = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        var coordinator: NativeEasyWorkNotificationCoordinator? = null
        try {
            withContext(Dispatchers.IO) {
                storage.write("metadata", JSONObject().put("version", 1).put("clients", JSONArray(listOf(encodeClient(client)))).toString())
                assertTrue(NativeEasyWorkNoticeAuthority.saved(storage).isEmpty())
                assertTrue(runCatching { NativeEasyWorkNoticeAuthority.capture(storage, client) }.isFailure)
                assertNull(storage.read("easywork-device-identity"))
                assertNull(storage.read(NativeChatNotificationIdentity.RECORD))
            }
            withContext(Dispatchers.Main.immediate) {
                val adapter = EasyWorkAgentClient(EasyWorkChannels { error("Imported fixture must never resolve a provider") })
                val port = object : NativeEasyWorkNotificationSessionPort {
                    override val notifications: AgentTaskNotifications = adapter
                    override val reads: AgentReads = adapter
                    override fun ready() = true
                    override fun clients() = listOf(client)
                    override fun open(client: MobileClient, workspace: MobileWorkspace, thread: MobileThread, current: () -> Boolean): Boolean = error("Imported fixture must not navigate")
                }
                coordinator = NativeEasyWorkNotificationCoordinator(context, parent, storage, port)
                coordinator!!.reconcile(true)
                val admitted = withTimeoutOrNull(1_000) {
                    while (coordinator!!.reports.isEmpty()) delay(10)
                    true
                }
                assertNull(admitted)
                assertTrue(coordinator!!.reports.isEmpty())
            }
        } finally {
            withContext(Dispatchers.Main.immediate) { coordinator?.dispose() }
            parent.coroutineContext[Job]!!.cancelAndJoin()
            withContext(Dispatchers.IO) { storage.remove("metadata") }
        }
    }
}
