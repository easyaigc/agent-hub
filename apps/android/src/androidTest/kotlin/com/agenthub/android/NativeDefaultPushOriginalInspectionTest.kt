package com.agenthub.android

import android.os.Bundle
import android.os.Looper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.agenthub.core.TargetScope
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** One read-only original-profile inventory, never an Activity/Session, binding proof or vendor acceptance.
 * Root owns the external full-directory SHA tables before/after this selector. No evidence file is written here.
 */
@RunWith(AndroidJUnit4::class)
class NativeDefaultPushOriginalInspectionTest {
    companion object {
        private const val SELECTOR = "com.agenthub.android.NativeDefaultPushOriginalInspectionTest#inspectExistingDefaultPushPrerequisitesReadOnly"
        private const val PACKAGE = "com.easyai.agenthub.android.acceptance"
        private const val CODEX_HOST = "13c81542-48e3-4df2-ab46-d45357b631a2"
        private const val EW_HOST = "device-6d96807c-157c-4e78-9bb7-628a59fe08bf"
        private const val EW_WORKSPACE = "workspace-d20f172966704eff4d1261ba"
        private val runs = ConcurrentHashMap.newKeySet<String>()
        private val notices = linkedMapOf(
            "native-chat-notifications-v1" to setOf(1, 2),
            "native-chat-notification-identity-v1" to setOf(1),
            "native-chat-notification-authorizations-v1" to setOf(1),
            "native-chat-notification-foreground-v1" to setOf(1),
            "native-chat-notification-recovery-v2" to setOf(2),
            "native-chat-notification-push-v1" to setOf(1),
            "native-easywork-notifications-v1" to setOf(1))
    }
    private class Unavailable(val code: String) : Exception()
    private fun admit(ok: Boolean, code: String) { if (!ok) throw Unavailable(code) }
    private fun sha(value: String) = NativeChatNotificationWire.hash(value)
    private fun hash(value: String) = value.also { admit(it.matches(Regex("[a-f0-9]{64}")), "invalid-plan") }
    private fun exact(v: JSONObject, names: Set<String>) = admit(v.keys().asSequence().toSet() == names, "unsupported-schema")
    private fun target(client: MobileClient): TargetScope? = client.agentConnection?.target ?: client.migrationEasyWork?.target ?: client.scope ?: client.codexDirect?.target
    private fun digest(target: TargetScope) = NativeChatRecoveryCanonical.hash(CodexCommandCodec.target(target))
    private fun targetSummary(scope: TargetScope) = JSONObject().put("provider", scope.agentId).put("hostId", scope.hostId)
        .put("workspaceId", scope.workspaceId).put("runtimeId", scope.runtimeId).put("fullTargetSha256", digest(scope))
    private fun dev(scope: TargetScope, provider: String) = if (provider == "codex")
        scope.agentId == provider && scope.hostId == CODEX_HOST && scope.workspaceId == CODEX_HOST && scope.runtimeId == "desktop-runtime"
    else scope.agentId == "easywork" && scope.hostId == EW_HOST && scope.workspaceId == EW_WORKSPACE && scope.runtimeId == "local"

    /** Summary only. No epoch, revision, token, key, account subject, name or ref value escapes. */
    private fun epochs(root: JSONObject): JSONObject {
        val values = JSONArray(); var visited = 0
        fun visit(value: Any?, depth: Int) {
            admit(++visited <= 32_768 && depth <= 48, "unsupported-shape")
            when (value) {
                is JSONObject -> for (key in value.keys().asSequence().toList().sorted()) {
                    val child = value.get(key)
                    if (key in setOf("epoch", "credentialEpoch", "generation", "revision") &&
                        (child is String || child is Number)) values.put(JSONObject().put("kind", key).put("value", child))
                    visit(child, depth + 1)
                }
                is JSONArray -> for (index in 0 until value.length()) visit(value.get(index), depth + 1)
            }
        }
        visit(root, 0)
        return JSONObject().put("count", values.length()).put("sha256", NativeChatRecoveryCanonical.hash(values))
    }
    private fun plan(args: Bundle): JSONObject {
        val allowedArgs = setOf("class", "ownerCallbacksRun", "fcmDefaultInspectionPlanBase64", "fcmDefaultInspectionPlanSha256",
            "debug", "log", "timeout_msec", "disableAnalytics")
        admit(args.keySet().all { it in allowedArgs } && args.getString("class") == SELECTOR, "invalid-plan")
        val encoded = args.getString("fcmDefaultInspectionPlanBase64") ?: throw Unavailable("missing-plan")
        admit(encoded.length in 1..8192 && encoded.matches(Regex("[A-Za-z0-9+/]*={0,2}")), "invalid-plan")
        val bytes = Base64.getDecoder().decode(encoded)
        admit(bytes.size <= 4096 && Base64.getEncoder().encodeToString(bytes) == encoded, "invalid-plan")
        val raw = Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            .decode(java.nio.ByteBuffer.wrap(bytes)).toString()
        admit(sha(raw) == hash(args.getString("fcmDefaultInspectionPlanSha256") ?: ""), "invalid-plan")
        val p = JSONObject(raw)
        exact(p, setOf("version", "action", "package", "ownerCallbacksRun", "appSha256", "runnerSha256", "signerSetSha256", "codexTargetSha256", "easyworkTargetSha256"))
        admit(p.get("version") == 1 && p.getString("action") == "inspect-existing-default-push-prerequisites-read-only" && p.getString("package") == PACKAGE, "invalid-plan")
        for (key in listOf("appSha256", "runnerSha256", "signerSetSha256", "codexTargetSha256", "easyworkTargetSha256")) hash(p.getString(key))
        val run = p.getString("ownerCallbacksRun")
        admit(UUID.fromString(run).toString() == run && args.getString("ownerCallbacksRun") == run && runs.add(run), "invalid-or-reused-run")
        return p
    }
    private fun inspect(p: JSONObject): JSONObject {
        check(Looper.myLooper() != Looper.getMainLooper())
        val context = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        admit(context.packageName == PACKAGE, "wrong-package")
        val installed = NativeChatNotificationNaturalStore.installed(context)
        admit(installed.appHash == p.getString("appSha256") && installed.runnerHash == p.getString("runnerSha256") &&
            installed.signerSetHash == p.getString("signerSetSha256"), "wrong-installed-fingerprint")
        val result = JSONObject().put("ownerCallbacksRun", p.getString("ownerCallbacksRun"))
            .put("readOnlyReader", true).put("mutationCounter", "not-exposed")
            .put("zeroWriteAPICallsByReviewedEntry", true).put("completeBindingVerified", "not-checked")
            .put("wholeDirectoryProtection", "Root-external-before-after-full-SHA-tables-required")
        NativeSessionStorage.ExistingReader.open(context, "mobile-v1", "agent-hub-mobile-v1").use { reader ->
            val originals = linkedMapOf<String, String?>()
            fun read(name: String): String? {
                admit(!Thread.currentThread().isInterrupted, "inspection-interrupted")
                val raw = reader.optionalText(name, 1_048_576)
                originals[name] = raw?.let(::sha)
                return raw
            }
            fun record(raw: String?): JSONObject = JSONObject().put("present", raw != null)
                .put("sha256", raw?.let(::sha) ?: JSONObject.NULL)
            val metadataRaw = read("metadata") ?: throw Unavailable("metadata-missing")
            val metadata = JSONObject(metadataRaw)
            admit(metadata.get("version") == 1, "unsupported-metadata-schema")
            val rows = metadata.getJSONArray("clients")
            admit(rows.length() <= 128, "unsupported-client-count")
            val clients = (0 until rows.length()).map { decodeClient(rows.getJSONObject(it)) }.filterNot { it.isDemo }
            val targets = clients.mapNotNull(::target)
            result.put("clientCount", clients.size).put("descriptors", JSONArray(targets.map(::targetSummary)))
            for (provider in listOf("codex", "easywork")) {
                val expected = p.getString(if (provider == "codex") "codexTargetSha256" else "easyworkTargetSha256")
                result.put(provider + "DescriptorPresent", targets.any { dev(it, provider) && digest(it) == expected })
            }
            fun counts(name: String, attachments: Boolean): JSONObject {
                val obj = if (metadata.has(name)) metadata.getJSONObject(name) else JSONObject()
                admit(obj.length() <= 8192, "unsupported-metadata-shape")
                var count = 0
                for (key in obj.keys()) {
                    if (attachments) { val a = obj.getJSONArray(key); admit(a.length() <= 256, "unsupported-metadata-shape"); count += a.length() }
                    else { obj.getString(key); ++count }
                }
                return JSONObject().put("present", metadata.has(name)).put("count", count).put("destinations", obj.length()).put("sha256", NativeChatRecoveryCanonical.hash(obj))
            }
            result.put("drafts", counts("drafts", false)).put("attachments", counts("draftFiles", true))
            val summaries = JSONArray()
            for ((name, versions) in notices) {
                val raw = read(name); val summary = record(raw).put("record", name)
                if (raw != null) {
                    val value = JSONObject(raw); val version = value.get("version")
                    admit(version is Int && version in versions, "unsupported-notification-schema")
                    when (name) {
                        "native-chat-notifications-v1" -> NativeChatNotificationStore.validateEncoded(raw)
                        "native-chat-notification-authorizations-v1" -> NativeChatNotificationAuthorizations.validateEncoded(raw)
                        "native-chat-notification-foreground-v1" -> NativeChatNotificationForegroundStore.validateEncoded(raw)
                        "native-chat-notification-recovery-v2" -> NativeChatNotificationRecoveryStore.validateEncoded(raw)
                    }
                    summary.put("schemaVersion", version).put("epochs", epochs(value))
                        .put("schemaCheck", if (name in setOf("native-chat-notifications-v1", "native-chat-notification-authorizations-v1",
                            "native-chat-notification-foreground-v1", "native-chat-notification-recovery-v2")) "production-validator" else "version-header-only")
                }
                summaries.put(summary)
            }
            result.put("notifications", summaries)
            val codexRaw = read("codex-private-connections-v1")
            var codexVaultDescriptor = false
            if (codexRaw != null) {
                val v = JSONObject(codexRaw); admit(v.get("version") == 1 && v.getJSONArray("connections").length() <= 128, "unsupported-codex-vault-schema")
                val connections = v.getJSONArray("connections")
                codexVaultDescriptor = (0 until connections.length()).any { index ->
                    target(decodeClient(connections.getJSONObject(index).getJSONObject("client")))?.let {
                        dev(it, "codex") && digest(it) == p.getString("codexTargetSha256")
                    } == true
                }
            }
            result.put("codexCredentialRecord", record(codexRaw).put("devDescriptorPresent", codexVaultDescriptor))
            val identityRaw = read("easywork-device-identity")
            if (identityRaw != null) {
                val v = JSONObject(identityRaw); admit(!v.has("version"), "unsupported-easywork-identity-schema")
                for (key in listOf("deviceId", "privateKey", "publicKey")) admit(v.get(key) is String, "unsupported-easywork-identity-schema")
            }
            result.put("easyworkIdentityRecord", record(identityRaw).put("schemaCheck", "required-fields-only"))
            val ew = clients.filter { client -> target(client)?.let { dev(it, "easywork") && digest(it) == p.getString("easyworkTargetSha256") } == true }
            val refs = ew.mapNotNull { it.agentConnection?.credentialRef }.distinct()
            admit(refs.size <= 64 && refs.all { it.matches(Regex("[A-Za-z0-9_-]{1,128}")) }, "unsupported-saved-credential-ref")
            val credentials = JSONArray()
            for (ref in refs) {
                val raw = read("easywork-credential:$ref")
                if (raw != null) {
                    val v = JSONObject(raw); admit(!v.has("version"), "unsupported-easywork-credential-schema")
                    v.getJSONObject("binding"); admit(v.get("privateKey") is String, "unsupported-easywork-credential-schema")
                }
                // Never include ref/client IDs or credential fields, even on errors.
                credentials.put(record(raw).put("schemaCheck", "required-fields-only"))
            }
            result.put("easyworkCredentialRecords", credentials)
            // Per-file read fencing is supplied by ExistingReader; this also detects changes between our reads.
            // It does not replace Root's complete original-directory protection or claim a global atomic snapshot.
            for ((name, before) in originals) {
                admit(!Thread.currentThread().isInterrupted, "inspection-interrupted")
                admit(reader.optionalText(name, 1_048_576)?.let(::sha) == before, "changed-during-inspection")
            }
            result.put("selectedRecordsUnchangedOnReread", true)
            val present = result.getBoolean("codexDescriptorPresent") && result.getBoolean("easyworkDescriptorPresent")
            val credentialsPresent = codexVaultDescriptor && identityRaw != null && refs.isNotEmpty() &&
                (0 until credentials.length()).all { credentials.getJSONObject(it).getBoolean("present") }
            result.put("status", if (present && credentialsPresent) "inspected-not-binding-verified" else "prerequisite-unmet")
                .put("code", if (present && credentialsPresent) "bounded-read-only-inventory-complete" else "original-dev-descriptor-or-credential-missing")
        }
        return result
    }
    @Test fun inspectExistingDefaultPushPrerequisitesReadOnly() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val executor = Executors.newSingleThreadExecutor { task -> Thread(task, "f52-original-read-only-inspection") }
        var result = JSONObject().put("status", "prerequisite-unmet/unsupported").put("completeBindingVerified", "not-checked")
            .put("readOnlyReader", true).put("mutationCounter", "not-exposed").put("zeroWriteAPICallsByReviewedEntry", true)
        try {
            val args = Bundle(InstrumentationRegistry.getArguments())
            val future = executor.submit<JSONObject> {
                var admitted: JSONObject? = null
                try { val p = plan(args); admitted = p; inspect(p) }
                catch (failure: Exception) {
                    JSONObject().put("status", "prerequisite-unmet/unsupported").put("code", (failure as? Unavailable)?.code ?: "existing-original-unavailable-or-unsupported")
                        .put("exceptionType", failure.javaClass.simpleName.take(80)).put("completeBindingVerified", "not-checked")
                        .put("ownerCallbacksRun", admitted?.getString("ownerCallbacksRun") ?: JSONObject.NULL)
                        .put("readOnlyReader", true).put("mutationCounter", "not-exposed").put("zeroWriteAPICallsByReviewedEntry", true)
                }
            }
            try { result = future.get(75, TimeUnit.SECONDS) }
            finally { if (!future.isDone) future.cancel(true) }
        } catch (failure: Exception) {
            result.put("code", "bounded-inspection-unavailable").put("exceptionType", failure.javaClass.simpleName.take(80))
        } finally {
            executor.shutdownNow()
            val stopped = try { executor.awaitTermination(10, TimeUnit.SECONDS) } catch (_: InterruptedException) { Thread.currentThread().interrupt(); false }
            result.put("ownedWorkerTerminated", stopped)
            if (!stopped) result.put("status", "prerequisite-unmet/unsupported").put("code", "worker-release-unconfirmed")
            // Only the fixed report is public. Never throw original decoder/JSON exceptions with sensitive messages.
            var publicReport = result.toString()
            if (publicReport.toByteArray(Charsets.UTF_8).size > 16_384) publicReport = JSONObject()
                .put("status", "prerequisite-unmet/unsupported").put("code", "public-summary-limit")
                .put("completeBindingVerified", "not-checked").put("mutationCounter", "not-exposed")
                .put("readOnlyReader", true).put("zeroWriteAPICallsByReviewedEntry", true)
                .put("ownedWorkerTerminated", stopped).toString()
            instrumentation.sendStatus(2, Bundle().apply { putString("fcmDefaultInspection", publicReport) })
        }
    }
}
