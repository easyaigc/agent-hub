package com.agenthub.android

import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.util.Base64

/** In-memory boundary checks only; no Context, key alias, file, Activity or network is created. */
class ExistingTargetAdmissionPolicyTest {
    private val now = Instant.parse("2026-01-01T00:00:00Z")
    private val pin = "a".repeat(64)
    private val endpoint = "https://local-host.invalid:8443"
    private val secret = "s".repeat(43)
    private fun scope(agent: String = "codex") = JSONObject().put("agentId", agent).put("hostId", "local-host")
        .put("workspaceId", "workspace").put("runtimeId", "desktop-runtime").apply {
            if (agent == "easywork") put("account", account())
        }
    private fun account() = JSONObject().put("authority", "test-authority").put("subject", "test-subject")
    private fun codex(id: String = "client") = JSONObject().put("id", id).put("mode", "desktop-runtime").put("scope", scope())
        .put("codexDirect", JSONObject().put("target", scope()).put("fingerprint", pin)
            .put("routes", JSONArray().put(JSONObject().put("endpoint", endpoint))))
    private fun records(client: JSONObject) = mutableMapOf("metadata" to JSONObject().put("selected", client.getString("id"))
        .put("clients", JSONArray().put(client)).put("drafts", JSONObject().put("draft", "original-draft-must-stay-private")).toString(), "token:client" to secret)
    private fun project(records: Map<String, String>, dpop: Boolean = true) = ExistingTargetAdmission.project({ records[it] }, {
        if (dpop) JSONObject().put("publicKeySha256", pin).put("thumbprint", "public-thumbprint") else null
    }, now)
    private fun row(report: JSONObject, i: Int = 0) = report.getJSONArray("clients").getJSONObject(i)
    private fun denied(block: () -> Unit) { assertNotNull(runCatching(block).exceptionOrNull()) }

    @Test fun completeCodexTargetAndPinAreReadWithoutLeakingOriginalTextOrToken() {
        val report = project(records(codex()))
        val r = row(report)
        ExistingTargetAdmission.requireLocalTarget(r, scope(), endpoint, pin)
        assertEquals("desktop-runtime", r.getJSONObject("target").getString("runtimeId"))
        assertFalse(report.getBoolean("runtimeControllable")); assertFalse(report.getBoolean("readyForBusinessInput"))
        assertFalse(report.toString().contains(secret)); assertFalse(report.toString().contains("original-draft"))
    }

    @Test fun verifiedVaultAddsMissingClientsAndSuppliesTheCurrentRoute() {
        val map = records(codex())
        val newer = codex().apply { getJSONObject("codexDirect").getJSONArray("routes").getJSONObject(0).put("endpoint", "https://new-local.invalid") }
        map["codex-private-connections-v1"] = JSONObject().put("version", 1).put("connections", JSONArray()
            .put(JSONObject().put("client", newer).put("token", secret))
            .put(JSONObject().put("client", codex("vault-only")).put("token", secret))).toString()
        val report = project(map)
        assertEquals(2, report.getJSONArray("clients").length())
        assertEquals("verified-vault", row(report).getString("source"))
        assertEquals("https://new-local.invalid", row(report).getJSONArray("routes").getJSONObject(0).getString("endpoint"))
    }

    @Test fun aChangedCodexAccountDoesNotBlockTheSameMachineButOtherTargetsDo() {
        val r = row(project(records(codex())))
        val changedAccount = scope().put("account", JSONObject().put("authority", "changed").put("subject", "changed"))
        ExistingTargetAdmission.requireLocalTarget(r, changedAccount, endpoint, pin)
        for (key in listOf("agentId", "hostId", "workspaceId", "runtimeId")) {
            denied { ExistingTargetAdmission.requireLocalTarget(r, scope().put(key, "other"), endpoint, pin) }
        }
        denied { ExistingTargetAdmission.requireLocalTarget(r, scope(), endpoint, "b".repeat(64)) }
        denied { ExistingTargetAdmission.requireLocalTarget(r, scope(), "https://another.invalid", pin) }
    }

    @Test fun aVaultTargetConflictIsReportedAndCannotAuthorizeARead() {
        val map = records(codex())
        val changed = codex().apply { getJSONObject("scope").put("hostId", "other"); getJSONObject("codexDirect").getJSONObject("target").put("hostId", "other") }
        map["codex-private-connections-v1"] = JSONObject().put("version", 1).put("connections", JSONArray().put(JSONObject().put("client", changed).put("token", secret))).toString()
        val r = row(project(map))
        assertFalse(r.getBoolean("metadataTargetMatches"))
        denied { ExistingTargetAdmission.requireLocalTarget(r, scope().put("hostId", "other"), endpoint, pin) }
    }

    @Test fun credentialBearingOrMalformedRoutesFailWithoutEchoingTheirValues() {
        for (bad in listOf("https://user:password@local.invalid", "https://local.invalid?token=$secret", "https://local.invalid/#$secret", "https://local.invalid/$secret")) {
            val client = codex().apply { getJSONObject("codexDirect").getJSONArray("routes").getJSONObject(0).put("endpoint", bad) }
            val report = project(records(client))
            assertEquals("invalid-existing-record", row(report).getString("status"))
            assertFalse(report.toString().contains(secret)); assertFalse(report.toString().contains("password"))
        }
    }

    private fun easywork(): MutableMap<String, String> {
        val key = X25519PrivateKeyParameters(ByteArray(32) { (it + 1).toByte() }, 0)
        fun b64(bytes: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        val identity = JSONObject().put("deviceId", "test-mobile").put("privateKey", b64(key.encoded)).put("publicKey", b64(key.generatePublicKey().encoded))
        val target = JSONObject().put("account", account()).put("desktopDeviceId", "local-host").put("workspaceId", "workspace").put("runtimeId", "desktop-runtime")
        val binding = JSONObject().put("bindingId", "test-binding").put("status", "active").put("target", target).put("account", account())
            .put("desktopDeviceId", "local-host").put("mobileDeviceId", "test-mobile").put("mobilePublicKey", identity.getString("publicKey"))
            .put("desktopPublicKey", b64(key.generatePublicKey().encoded)).put("deviceAuthorization", JSONObject().put("protocol", "EWDAP/1")
                .put("grantId", "test-grant").put("authKeyThumbprint", "public-thumbprint").put("keyVersion", 1))
        val credential = JSONObject().put("privateKey", identity.getString("privateKey")).put("binding", binding).put("grantRef", "test-grant-ref")
        val client = JSONObject().put("id", "client").put("mode", "easywork-runtime").put("scope", scope("easywork"))
            .put("agentConnection", JSONObject().put("target", scope("easywork")).put("credentialRef", "test-credential-ref")
                .put("candidates", JSONArray().put(JSONObject().put("endpoint", endpoint).put("transportId", "lan-wss").put("peerFingerprint", pin))))
        val grant = JSONObject().put("protocol", "EWDAP/1").put("deviceId", "test-mobile").put("grantId", "test-grant").put("binding", binding).put("target", target)
            .put("keyVersion", 1).put("accessToken", secret).put("refreshToken", "r".repeat(43))
            .put("accessExpiresAt", now.plusSeconds(3600).toString()).put("refreshExpiresAt", now.plusSeconds(7200).toString())
            .put("scopes", JSONArray(listOf("relay:read", "relay:write", "runtime:read", "runtime:control")))
        return records(client).apply {
            put("easywork-device-identity", identity.toString()); put("easywork-credential:test-credential-ref", credential.toString())
            put("test-grant-ref", JSONObject().put("origin", endpoint).put("grant", grant).toString())
        }
    }
    private fun changeGrant(map: MutableMap<String, String>, edit: (JSONObject) -> Unit) {
        val saved = JSONObject(map.getValue("test-grant-ref")); edit(saved); map["test-grant-ref"] = saved.toString()
    }

    @Test fun currentEasyworkGrantOnlyAdmitsTheExactMachineAccountRouteAndPin() {
        val map = easywork(); val r = row(project(map))
        ExistingTargetAdmission.requireLocalTarget(r, scope("easywork"), endpoint, pin)
        assertEquals("access-current", r.getJSONObject("grant").getString("state"))
        denied { ExistingTargetAdmission.requireLocalTarget(r, scope("easywork").put("account", account().put("subject", "other")), endpoint, pin) }
        val output = project(map).toString()
        for (hidden in listOf(secret, "r".repeat(43), "privateKey", "original-draft")) assertFalse(output.contains(hidden))
    }

    @Test fun pendingRefreshKeepsItsOriginalOperationAndNeverAuthorizesOrReplays() {
        val map = easywork(); val operation = "11111111-1111-4111-8111-111111111111"
        changeGrant(map) { it.put("refreshOperation", JSONObject().put("operationId", operation).put("token", secret)) }
        val before = map.toMap(); val r = row(project(map))
        assertEquals("refresh-pending-no-replay", r.getJSONObject("grant").getString("state"))
        assertEquals(operation, r.getJSONObject("grant").getString("pendingRefreshOperationId"))
        assertFalse(r.getJSONObject("grant").getBoolean("refreshAttempted")); assertEquals(before, map)
        denied { ExistingTargetAdmission.requireLocalTarget(r, scope("easywork"), endpoint, pin) }
    }

    @Test fun expiryBoundariesAreObservedWithoutRefreshingTheOriginalGrant() {
        for ((seconds, state) in listOf(-1L to "access-expired-no-refresh", 0L to "access-expired-no-refresh", 60L to "access-near-expiry-no-refresh", 61L to "access-current")) {
            val map = easywork(); changeGrant(map) { it.getJSONObject("grant").put("accessExpiresAt", now.plusSeconds(seconds).toString()) }
            assertEquals(state, row(project(map)).getJSONObject("grant").getString("state"))
        }
        val map = easywork(); changeGrant(map) { it.getJSONObject("grant").put("refreshExpiresAt", now.toString()) }
        assertEquals("refresh-expired-no-refresh", row(project(map)).getJSONObject("grant").getString("state"))
    }

    @Test fun durableRevokeFenceWinsWithoutTheGrantSideMarker() {
        val map = easywork()
        val hash = MessageDigestHolder.sha("test-grant-ref")
        map["easywork-authorization-fence:$hash"] = JSONObject().put("version", 1).put("ref", "test-grant-ref")
            .put("epoch", "11111111-1111-4111-8111-111111111111").put("revoke", JSONObject().put("state", "confirmed")).toString()
        val r = row(project(map)); assertEquals("revoke-confirmed", r.getJSONObject("grant").getString("state"))
        denied { ExistingTargetAdmission.requireLocalTarget(r, scope("easywork"), endpoint, pin) }
    }

    @Test fun malformedExistingFenceCannotBeSilentlyRepairedOrAuthorizeARead() {
        val map = easywork()
        map["easywork-authorization-fence:${MessageDigestHolder.sha("test-grant-ref")}"] = JSONObject()
            .put("version", 1.5).put("ref", "test-grant-ref").put("epoch", "1-1-1-1-1").toString()
        val before = map.toMap(); val report = project(map)
        assertEquals("invalid-existing-record", row(report).getString("status")); assertEquals(before, map)
    }

    @Test fun aMissingDpopKeyStillReportsExpiryButCannotAuthorizeOrCreateAnything() {
        val map = easywork(); val before = map.toMap(); val r = row(project(map, false))
        assertFalse(r.getBoolean("existingDpopValidated"))
        assertEquals("access-current", r.getJSONObject("grant").getString("state"))
        denied { ExistingTargetAdmission.requireLocalTarget(r, scope("easywork"), endpoint, pin) }
        assertEquals(before, map)
    }

    @Test fun aDifferentGrantProofKeyIsRejectedWithoutExportingTheGrant() {
        val map = easywork()
        changeGrant(map) { it.getJSONObject("grant").getJSONObject("binding").getJSONObject("deviceAuthorization").put("authKeyThumbprint", "other-proof-key") }
        val report = project(map)
        assertEquals("invalid-existing-record", row(report).getString("status"))
        assertFalse(report.toString().contains(secret)); assertFalse(report.toString().contains("other-proof-key"))
    }

    @Test fun mismatchedOriginalIdentityKeysFailWithoutFallingBackToANewIdentity() {
        val map = easywork(); val identity = JSONObject(map.getValue("easywork-device-identity"))
        identity.put("publicKey", Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32)))
        map["easywork-device-identity"] = identity.toString()
        val before = map.toMap(); val report = project(map)
        assertEquals("invalid-existing-record", row(report).getString("status")); assertEquals(before, map)
        assertFalse(report.toString().contains("privateKey"))
    }

    private object MessageDigestHolder {
        fun sha(value: String) = java.security.MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
