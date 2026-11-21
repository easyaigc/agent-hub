package com.agenthub.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Local encrypted-state tests only. Does not initialize Firebase, schedule a job or contact a Host. */
@RunWith(AndroidJUnit4::class)
class NativeChatNotificationPushLifecycleTest {
    @Test fun pushFailureDiagnosticsExposeOnlyFixedMetadata() {
        val secret = "PRIVATE_TOKEN_URI_AND_MESSAGE_MUST_NOT_APPEAR"
        val failure = IllegalArgumentException(secret, com.google.firebase.installations.FirebaseInstallationsException(
            secret, com.google.firebase.installations.FirebaseInstallationsException.Status.BAD_CONFIG))
        val value = JSONObject(NativeChatNotificationPushDiagnostic.failure(failure))
        assertFalse(value.toString().contains(secret))
        assertEquals(setOf("stage", "causes"), value.keys().asSequence().toSet())
        assertEquals("token-task", value.getString("stage"))
        val causes = value.getJSONArray("causes")
        assertEquals(2, causes.length()); assertEquals("OTHER", causes.getJSONObject(0).getString("class"))
        assertEquals("BAD_CONFIG", causes.getJSONObject(1).getString("status"))
        val wrapped = JSONObject(NativeChatNotificationPushDiagnostic.failure(java.io.IOException(secret,
            java.util.concurrent.ExecutionException(java.io.IOException("SERVICE_NOT_AVAILABLE")))))
        assertFalse(wrapped.toString().contains(secret))
        assertFalse(wrapped.getJSONArray("causes").getJSONObject(0).has("errorCode"))
        assertEquals("SERVICE_NOT_AVAILABLE", wrapped.getJSONArray("causes").getJSONObject(2).getString("errorCode"))
        for (message in listOf("SERVICE_NOT_AVAILABLE:$secret", "$secret SERVICE_NOT_AVAILABLE", "AUTHENTICATION_FAILED")) {
            val unknown = JSONObject(NativeChatNotificationPushDiagnostic.failure(java.io.IOException(message)))
            assertFalse(unknown.getJSONArray("causes").getJSONObject(0).has("errorCode"))
            assertFalse(unknown.toString().contains(secret))
        }
        for ((message, code) in mapOf("INTERNAL_SERVER_ERROR" to "INTERNAL_SERVER_ERROR",
            "InternalServerError" to "INTERNAL_SERVER_ERROR", "INSTANCE_ID_RESET" to "INSTANCE_ID_RESET",
            "MISSING_INSTANCEID_SERVICE" to "MISSING_INSTANCEID_SERVICE", "TIMEOUT" to "TIMEOUT")) {
            val known = JSONObject(NativeChatNotificationPushDiagnostic.failure(java.io.IOException(message)))
            assertEquals(code, known.getJSONArray("causes").getJSONObject(0).getString("errorCode"))
        }
        val foreign = JSONObject(NativeChatNotificationPushDiagnostic.failure(IllegalArgumentException("SERVICE_NOT_AVAILABLE")))
        assertFalse(foreign.getJSONArray("causes").getJSONObject(0).has("errorCode"))
        val missing = JSONObject(NativeChatNotificationPushDiagnostic.failure(null))
        assertEquals(0, missing.getJSONArray("causes").length())
        val availability = JSONObject(NativeChatNotificationPushDiagnostic.availability(3))
        assertEquals(setOf("stage", "code"), availability.keys().asSequence().toSet())
        assertEquals("google-services", availability.getString("stage")); assertEquals(3, availability.getInt("code"))
        for (successful in listOf(false, true)) for (active in listOf(false, true)) {
            val event = JSONObject(NativeChatNotificationPushDiagnostic.tokenTaskEvent(7, successful, active, failure))
            assertFalse(event.toString().contains(secret))
            assertEquals(7, event.getInt("attempt"))
            assertEquals(successful, event.getBoolean("successful")); assertEquals(active, event.getBoolean("callerActive"))
            if (successful) assertFalse(event.has("causes"))
        }
        val terminal = JSONObject(NativeChatNotificationPushDiagnostic.tokenResultEvent(7, "exception", failure))
        assertFalse(terminal.toString().contains(secret)); assertEquals("exception", terminal.getString("outcome"))
        for (outcome in listOf("success", "unavailable", "timeout", "cancelled", "not-configured", "google-services-unavailable")) {
            val event = JSONObject(NativeChatNotificationPushDiagnostic.tokenResultEvent(7, outcome))
            assertEquals(setOf("stage", "attempt", "outcome"), event.keys().asSequence().toSet())
            assertEquals(outcome, event.getString("outcome"))
        }
        for (message in listOf("Unable to bind to service", "Timed out while binding", "Service disconnected", "Timed out waiting for response")) {
            val foreignIpc = JSONObject(NativeChatNotificationPushDiagnostic.failure(java.io.IOException(message)))
            assertFalse(foreignIpc.getJSONArray("causes").getJSONObject(0).has("ipcErrorCode"))
        }
        assertThrows(IllegalArgumentException::class.java) { NativeChatNotificationPushDiagnostic.tokenResultEvent(7, secret) }
        assertThrows(IllegalArgumentException::class.java) { NativeChatNotificationPushDiagnostic.tokenTaskEvent(0, false, false, failure) }
    }
    @Test fun rotationAndLateJobCompletionPreserveNewToken() = runBlocking { withContext(Dispatchers.IO) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val storage = NativeSessionStorage(context, "p4-push-lifecycle-" + UUID.randomUUID())
        val state = NativeChatNotificationPushState(storage)
        val config = "a".repeat(64)
        val target = NativeChatNoticeTarget("fixture-host", "fixture-workspace", "fixture-runtime")
        try {
            assertFalse(state.enqueueToken("unrequested-token", config))
            state.token(NativeChatNotificationWire.hash("first-token"), config)
            state.save(target, JSONObject().put("target", NativeChatNotificationCodec.target(target)).put("stage", "ready").put("operationId", UUID.randomUUID().toString()))
            assertFalse(state.enqueueToken("wrong-project-token", "b".repeat(64)))
            assertTrue(state.enqueueToken("second-token", config))
            val original = requireNotNull(state.pendingSync())
            assertEquals("second-token", original.token)
            assertTrue(state.enqueueToken("second-token", config))
            assertEquals(original.id, requireNotNull(state.pendingSync()).id)
            assertFalse(state.matches(NativeChatNotificationWire.hash("first-token"), config))
            assertTrue(state.enqueueToken("third-token", config))
            val newer = requireNotNull(state.pendingSync())
            assertNotEquals(original.id, newer.id)
            state.finishSync(original.id)
            assertEquals(newer.id, requireNotNull(state.pendingSync()).id)
            assertEquals("third-token", requireNotNull(state.pendingSync()).token)
            state.finishSync(newer.id)
            assertNull(state.pendingSync())
        } finally { storage.remove(NativeChatNotificationPushState.RECORD); storage.remove(NativeChatNotificationStore.RECORD) }
    } }
    @Test fun endingWaitDoesNotDeleteOrRewriteOriginalAndMigrationKeepsCheckpoint() = runBlocking { withContext(Dispatchers.IO) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val storage = NativeSessionStorage(context, "p4-push-original-" + UUID.randomUUID())
        val state = NativeChatNotificationPushState(storage)
        val target = NativeChatNoticeTarget("fixture-host", "fixture-workspace", "fixture-runtime")
        val id = UUID.randomUUID().toString()
        val original = JSONObject().put("operationId", id).put("stage", "unknown").put("immutable", "fixture-intent")
        try {
            state.save(target, original)
            state.endWaiting(target, id)
            assertEquals("fixture-intent", requireNotNull(state.row(target)).getString("immutable"))
            assertTrue(requireNotNull(state.row(target)).getBoolean("waitingEnded"))
            state.resumeWaiting(target, UUID.randomUUID().toString())
            assertTrue(requireNotNull(state.row(target)).getBoolean("waitingEnded"))
            val replacement = UUID.randomUUID().toString()
            state.save(target, JSONObject().put("protocol", 2).put("operationId", replacement).put("stage", "revoked"))
            state.resumeWaiting(target, id) // A late original response cannot resume a different operation.
            val row = requireNotNull(state.row(target))
            assertEquals(replacement, row.getString("operationId"))
            assertEquals(id, row.getJSONObject("legacyOriginal").getString("operationId"))
            assertEquals("fixture-intent", row.getJSONObject("legacyOriginal").getString("immutable"))
        } finally { storage.remove(NativeChatNotificationPushState.RECORD) }
    } }
    @Test fun unknownOriginalIsQueryableAndLateReceiptCannotReplaceNewerRevoke() = runBlocking { withContext(Dispatchers.IO) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val storage = NativeSessionStorage(context, "p4-push-journal-" + UUID.randomUUID())
        val target = NativeChatNoticeTarget("fixture-host", "fixture-workspace", "fixture-runtime")
        val client = MobileClient(target.hostId, "Fixture", com.agenthub.core.TargetScope("codex", null, null,
            target.hostId, target.workspaceId, target.runtimeId), "https://fixture.example.invalid")
        val snapshot = NativeChatNotificationSnapshot(client, "fixture-credential-" + "x".repeat(32), "fixture-epoch")
        try {
            val identity = NativeChatNotificationIdentity.load(storage)
            val notices = NativeChatNotificationStore(storage)
            val prefs = notices.update { it.copy(enabled = true) }
            val journal = NativeChatNotificationRecoveryStore(storage)
            val epoch = UUID.randomUUID().toString()
            val unknown = journal.reserve(identity, snapshot, epoch, prefs, "register", { true }, deviceToken = "fixture-token")
            val reloaded = NativeChatNotificationRecoveryStore(storage).view(identity, target)
            assertEquals("unknown", reloaded.operations.single().state)
            assertEquals(unknown.encoded(), reloaded.operations.single().original.encoded())
            assertTrue(runCatching { journal.reserve(identity, snapshot, epoch, prefs, "register", { true }, deviceToken = "fixture-token") }.isFailure)
            val off = notices.update { it.copy(enabled = false) }
            val revoke = journal.reserve(identity, snapshot, epoch, off, "revoke", { true }, explicit = true)
            fun receipt(original: NativeChatRecoveryIntent, status: JSONObject) = NativeChatRecoveryWire.receipt(JSONObject()
                .put("version", 2).put("epoch", epoch).put("intent", original.json()).put("stage", "applied").put("status", status), original)
            fun status(revision: String) = JSONObject().put("version", 1).put("target", NativeChatNotificationCodec.target(target)).put("revision", revision)
            journal.settle(identity, target, revoke, receipt(revoke, status(off.revision).put("delivery", "foreground-only").put("state", "disabled")))
            val late = receipt(unknown, status(prefs.revision).put("delivery", "foreground-only").put("state", "pending")
                .put("pushProvider", "fcm").put("deviceTokenSha256", NativeChatNotificationWire.hash("fixture-token")))
            journal.settle(identity, target, unknown, late)
            assertEquals(revoke.operationId, requireNotNull(journal.view(identity, target).applied).original.operationId)
            assertEquals("revoke", requireNotNull(journal.view(identity, target).applied).original.kind)
            assertFalse(notices.preferences().enabled)
        } finally {
            listOf(NativeChatNotificationRecoveryStore.RECORD, NativeChatNotificationStore.RECORD,
                NativeChatNotificationIdentity.RECORD).forEach(storage::remove)
        }
    } }

}
