package com.agenthub.android

import android.content.Context
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.security.KeyStore
import java.security.MessageDigest
import java.security.interfaces.ECPublicKey
import java.time.Instant
import java.util.Base64

/** No Activity, session restore, AtomicFile recovery, key creation, credential export or network.
 * The output describes saved identity, never runtime connectivity or permission to send a command.
 * Keep the read callback private to this helper/test seam: it must not escape into a UI session.
 */
internal object ExistingTargetAdmission {
    private const val LIMIT = 1_048_576
    private val tokenPattern = Regex("[A-Za-z0-9_-]{43}")
    private val pinPattern = Regex("[a-fA-F0-9]{64}")

    fun inspect(context: Context, namespace: String = "mobile-v1", keyAlias: String = "agent-hub-mobile-v1"): JSONObject {
        require(context.packageName == "com.easyai.agenthub.android.acceptance")
        require(namespace == "mobile-v1" && keyAlias == "agent-hub-mobile-v1")
        return try {
            NativeSessionStorage.ExistingReader.open(context, namespace, keyAlias).use { reader ->
                project({ name -> reader.optionalText(name, LIMIT) }, ::existingDpopPublic)
            }
        } catch (_: Exception) {
            envelope().put("status", "existing-storage-unavailable-or-unsettled")
        }
    }

    private fun envelope() = JSONObject().put("schemaVersion", 1).put("kind", "existing-target-metadata-only")
        .put("networkRequests", 0).put("credentialsExported", false).put("runtimeControllable", false)
        .put("readyForBusinessInput", false)

    /** In-memory seam for boundary checks. No callback can write or create a missing record. */
    internal fun project(read: (String) -> String?, dpopPublic: (JSONObject) -> JSONObject?, now: Instant = Instant.now()): JSONObject {
        val metadata = JSONObject(requireNotNull(read("metadata")))
        val saved = metadata.getJSONArray("clients")
        require(saved.length() <= 128)
        val clients = linkedMapOf<String, JSONObject>()
        for (i in 0 until saved.length()) {
            val client = saved.getJSONObject(i)
            require(clients.put(text(client, "id"), client) == null)
        }
        // Ordinary restore also consults this atomic vault. Reading metadata alone can report an
        // obsolete route or entirely omit a newer verified pairing. Never instantiate the vault.
        val vaulted = linkedMapOf<String, JSONObject>()
        read("codex-private-connections-v1")?.let { raw ->
            val vault = JSONObject(raw)
            require(vault.get("version") == 1)
            val rows = vault.getJSONArray("connections")
            require(rows.length() <= 128)
            for (i in 0 until rows.length()) {
                val row = rows.getJSONObject(i)
                val client = row.getJSONObject("client")
                require(row.getString("token").matches(tokenPattern))
                require(target(client.getJSONObject("scope")).getString("agentId") == "codex")
                require(client.optJSONObject("codexDirect") != null)
                require(vaulted.put(text(client, "id"), row) == null)
            }
        }
        val output = JSONArray()
        for (id in (clients.keys + vaulted.keys).distinct()) {
            try {
                val vaultRow = vaulted[id]
                val client = vaultRow?.getJSONObject("client") ?: requireNotNull(clients[id])
                val scope = target(client.getJSONObject("scope"))
                val out = JSONObject().put("clientId", id).put("target", scope)
                    .put("source", if (vaultRow == null) "metadata" else "verified-vault")
                    .put("mode", text(client, "mode")).put("runtimeControllable", false)
                out.put("metadataTargetMatches", clients[id]?.let { prior ->
                    sameTarget(scope, target(prior.getJSONObject("scope"))) &&
                        (vaultRow == null || prior.optJSONObject("codexDirect")?.optString("fingerprint") == client.getJSONObject("codexDirect").getString("fingerprint"))
                } ?: true)
                if (client.optJSONObject("migrationEasyWork") != null) {
                    out.put("status", "migration-requires-explicit-pairing-not-permitted").put("routes", JSONArray())
                } else when (scope.getString("agentId")) {
                    "codex" -> codex(out, client, vaultRow, read)
                    "easywork" -> easywork(out, client, read, dpopPublic, now)
                    else -> out.put("status", "unsupported-provider")
                }
                output.put(out)
            } catch (_: Exception) {
                // Never include exception messages: JSON parsers can quote a credential value.
                output.put(JSONObject().put("clientIdSha256", sha(id.toByteArray())).put("status", "invalid-existing-record"))
            }
        }
        return envelope().put("status", "saved-metadata-read").put("clients", output)
            .put("selectedClientId", text(metadata, "selected"))
    }

    private fun codex(out: JSONObject, client: JSONObject, vault: JSONObject?, read: (String) -> String?) {
        val direct = client.optJSONObject("codexDirect")
        if (direct == null) { out.put("status", "missing-verified-pairing"); return }
        require(sameTarget(out.getJSONObject("target"), target(direct.getJSONObject("target"))))
        val pin = pin(direct.getString("fingerprint"))
        val routes = JSONArray()
        val raw = direct.getJSONArray("routes")
        require(raw.length() in 1..32)
        for (i in 0 until raw.length()) {
            val r = raw.getJSONObject(i)
            routes.put(route(r.getString("endpoint")).put("trust", "paired-leaf-sha256").put("pinSha256", pin))
        }
        direct.optJSONArray("systemEndpoints")?.let { systems ->
            require(systems.length() <= 4)
            for (i in 0 until systems.length()) routes.put(route(systems.getString(i)).put("trust", "system-ca-and-hostname"))
        }
        val token = vault?.getString("token") ?: read("token:${client.getString("id")}")
        out.put("routes", routes).put("pinSha256", pin)
            .put("credentialState", if (token?.matches(tokenPattern) == true) "existing" else "missing-or-invalid")
            .put("status", "saved-target-requires-local-allowlist-and-live-verification")
    }

    private fun easywork(out: JSONObject, client: JSONObject, read: (String) -> String?, dpopPublic: (JSONObject) -> JSONObject?, now: Instant) {
        val connection = client.getJSONObject("agentConnection")
        require(sameTarget(out.getJSONObject("target"), target(connection.getJSONObject("target"))))
        val candidates = connection.getJSONArray("candidates")
        require(candidates.length() in 1..32)
        val routes = JSONArray()
        for (i in 0 until candidates.length()) {
            val c = candidates.getJSONObject(i)
            val r = route(c.getString("endpoint")).put("transportId", text(c, "transportId"))
            if (!c.isNull("peerFingerprint")) r.put("pinSha256", pin(c.getString("peerFingerprint")))
            routes.put(r)
        }
        out.put("routes", routes)
        val credentialRaw = read("easywork-credential:${text(connection, "credentialRef")}")
        if (credentialRaw == null) { out.put("status", "missing-existing-credential"); return }
        val credential = JSONObject(credentialRaw)
        val identityRaw = read("easywork-device-identity")
        if (identityRaw == null) { out.put("status", "missing-existing-identity"); return }
        val identity = JSONObject(identityRaw)
        val mobileKey = bytes32(identity.getString("publicKey"))
        val privateKey = bytes32(identity.getString("privateKey"))
        try { require(X25519PrivateKeyParameters(privateKey, 0).generatePublicKey().encoded.contentEquals(mobileKey)) }
        finally { privateKey.fill(0) }
        require(credential.getString("privateKey") == identity.getString("privateKey"))
        val binding = credential.getJSONObject("binding")
        verifyBinding(binding, out.getJSONObject("target"), identity)
        out.put("bindingId", text(binding, "bindingId")).put("mobileDeviceId", text(identity, "deviceId"))
            .put("mobilePublicKeySha256", sha(mobileKey))
            .put("desktopPublicKeySha256", sha(bytes32(binding.getString("desktopPublicKey"))))
        val dpop = dpopPublic(identity)
        out.put("existingDpopValidated", dpop != null)
        if (dpop != null) out.put("dpopPublicKeySha256", pin(dpop.getString("publicKeySha256")))
            .put("dpopThumbprint", text(dpop, "thumbprint"))
        if (credential.isNull("grantRef")) { out.put("status", "legacy-credential-no-ewdap-grant"); return }
        val ref = text(credential, "grantRef")
        val savedRaw = read(ref)
        if (savedRaw == null) { out.put("status", "missing-existing-grant"); return }
        val saved = JSONObject(savedRaw)
        val fence = read("easywork-authorization-fence:" + sha(ref.toByteArray()))?.let(::JSONObject)
        out.put("grant", grantMetadata(saved, fence, ref, binding, identity, dpop?.getString("thumbprint"), now))
            .put("status", "saved-target-requires-local-allowlist-and-live-verification")
    }

    private fun verifyBinding(binding: JSONObject, expected: JSONObject, identity: JSONObject) {
        require(binding.getString("status") == "active")
        require(binding.getString("mobileDeviceId") == identity.getString("deviceId"))
        require(binding.getString("mobilePublicKey") == identity.getString("publicKey"))
        val t = binding.getJSONObject("target")
        val account = t.getJSONObject("account")
        require(binding.getString("desktopDeviceId") == t.getString("desktopDeviceId"))
        require(sameTarget(expected, JSONObject().put("agentId", "easywork").put("hostId", t.getString("desktopDeviceId"))
            .put("workspaceId", t.getString("workspaceId")).put("runtimeId", t.getString("runtimeId")).put("account", account)))
        require(canonical(binding.getJSONObject("account")) == canonical(account))
        bytes32(binding.getString("desktopPublicKey"))
    }

    private fun grantMetadata(saved: JSONObject, fence: JSONObject?, ref: String, binding: JSONObject, identity: JSONObject, thumbprint: String?, now: Instant): JSONObject {
        val g = saved.getJSONObject("grant")
        require(g.getString("protocol") == "EWDAP/1" && g.getString("deviceId") == identity.getString("deviceId"))
        require(canonical(g.getJSONObject("binding")) == canonical(binding))
        require(canonical(g.getJSONObject("target")) == canonical(binding.getJSONObject("target")))
        val auth = binding.getJSONObject("deviceAuthorization")
        require(auth.getString("protocol") == "EWDAP/1" && auth.getString("grantId") == g.getString("grantId"))
        require((thumbprint == null || auth.getString("authKeyThumbprint") == thumbprint) && auth.getInt("keyVersion") == g.getInt("keyVersion") && g.getInt("keyVersion") > 0)
        require(g.getString("accessToken").matches(tokenPattern) && g.getString("refreshToken").matches(tokenPattern))
        val access = Instant.parse(g.getString("accessExpiresAt")); val refresh = Instant.parse(g.getString("refreshExpiresAt"))
        require(access > Instant.EPOCH && refresh > Instant.EPOCH)
        val scopes = g.getJSONArray("scopes")
        val values = (0 until scopes.length()).map { scopes.getString(it) }
        require(values.distinct() == values && values.containsAll(listOf("relay:read", "relay:write", "runtime:read", "runtime:control")))
        require(values.all { it in setOf("relay:read", "relay:write", "runtime:read", "runtime:control", "files:read", "files:write") })
        fence?.let {
            require(it.get("version") == 1 && it.getString("ref") == ref)
            require(java.util.UUID.fromString(it.getString("epoch")).toString() == it.getString("epoch"))
        }
        val revoke = fence?.optJSONObject("revoke")
        revoke?.let { require(it.getString("state") in listOf("pending", "confirmed")) }
        val state = when {
            revoke != null -> "revoke-${revoke.getString("state")}" // Durable fence wins even without the grant-side marker.
            saved.has("revokeOperationId") -> "revoke-pending"
            saved.has("refreshOperation") -> "refresh-pending-no-replay"
            refresh <= now -> "refresh-expired-no-refresh"
            access <= now -> "access-expired-no-refresh"
            access <= now.plusSeconds(60) -> "access-near-expiry-no-refresh"
            else -> "access-current"
        }
        return JSONObject().put("grantId", text(g, "grantId")).put("state", state).put("accessExpiresAt", access.toString())
            .put("refreshExpiresAt", refresh.toString()).put("origin", route(saved.getString("origin")))
            .put("scopes", JSONArray(values)).put("refreshAttempted", false).put("revokeAttempted", false)
            .apply {
                saved.optJSONObject("refreshOperation")?.let { pending ->
                    put("pendingRefreshOperationId", java.util.UUID.fromString(pending.getString("operationId")).toString())
                }
                if (!saved.isNull("revokeOperationId")) put("pendingRevokeOperationId", java.util.UUID.fromString(saved.getString("revokeOperationId")).toString())
            }
    }

    /** Existing-only private-key validation, public metadata output; never signs or provisions. */
    private fun existingDpopPublic(identity: JSONObject): JSONObject? {
        val signer = try {
            EasyWorkDPoP.loadExisting(EasyWorkIdentity(identity.getString("deviceId"), identity.getString("privateKey"), identity.getString("publicKey")))
        } catch (_: Exception) { return null }
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val alias = "agent-hub-easywork-dpop-" + sha(identity.getString("deviceId").toByteArray(Charsets.UTF_8))
        if (!store.containsAlias(alias) || !store.isKeyEntry(alias)) return null
        val key = store.getCertificate(alias)?.publicKey as? ECPublicKey ?: return null
        require(key.params.curve.field.fieldSize == 256)
        fun coordinate(n: java.math.BigInteger): String {
            require(n.signum() >= 0 && n.bitLength() <= 256)
            val b = n.toByteArray().let { if (it.size == 33 && it[0] == 0.toByte()) it.copyOfRange(1, 33) else it }
            return Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32 - b.size) + b)
        }
        val jwk = JSONObject().put("kty", "EC").put("crv", "P-256").put("x", coordinate(key.w.affineX)).put("y", coordinate(key.w.affineY))
        require(canonical(jwk) == canonical(signer.jwk))
        return JSONObject().put("publicKeySha256", sha(key.encoded)).put("thumbprint", signer.thumbprint)
    }

    /** Strict exact-target gate. An endpoint being private, or a 200 response, is not identity proof. */
    internal fun requireLocalTarget(saved: JSONObject, expected: JSONObject, endpoint: String, expectedPin: String) {
        require(sameTarget(target(saved.getJSONObject("target")), target(expected)))
        require(saved.getBoolean("metadataTargetMatches"))
        val normalized = route(endpoint).getString("endpoint")
        val routes = saved.getJSONArray("routes")
        require((0 until routes.length()).any { i ->
            val r = routes.getJSONObject(i)
            r.getString("endpoint") == normalized && r.optString("pinSha256").equals(pin(expectedPin), true)
        })
        require(saved.getString("status") == "saved-target-requires-local-allowlist-and-live-verification")
        if (expected.getString("agentId") == "easywork") require(saved.getBoolean("existingDpopValidated") && saved.getJSONObject("grant").getString("state") == "access-current")
        else require(saved.getString("credentialState") == "existing")
    }

    private fun target(raw: JSONObject): JSONObject = JSONObject().apply {
        for (k in listOf("agentId", "hostId", "workspaceId", "runtimeId")) put(k, text(raw, k))
        if (raw.optJSONObject("account") != null) put("account", JSONObject().put("authority", text(raw.getJSONObject("account"), "authority")).put("subject", text(raw.getJSONObject("account"), "subject")))
        if (getString("agentId") == "easywork") require(has("account"))
    }
    private fun sameTarget(a: JSONObject, b: JSONObject): Boolean =
        listOf("agentId", "hostId", "workspaceId", "runtimeId").all { a.getString(it) == b.getString(it) } &&
            (a.getString("agentId") == "codex" || canonical(a.getJSONObject("account")) == canonical(b.getJSONObject("account")))
    private fun route(value: String): JSONObject {
        require(value.length in 1..512 && value.none { it.isISOControl() })
        val uri = URI(value)
        require(uri.scheme in setOf("https", "wss", "http", "ws") && uri.host != null && uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null)
        require(uri.rawPath in listOf("", "/") && uri.port in -1..65535 && uri.port != 0)
        return JSONObject().put("endpoint", value.trimEnd('/'))
    }
    private fun pin(value: String): String { require(value.matches(pinPattern)); return value.lowercase() }
    private fun text(value: JSONObject, key: String): String = value.getString(key).also { require(it.isNotBlank() && it.length <= 512 && it.none(Char::isISOControl)) }
    private fun bytes32(value: String): ByteArray {
        require(value.matches(Regex("[A-Za-z0-9_-]{43}")))
        return Base64.getUrlDecoder().decode(value).also { require(it.size == 32) }
    }
    private fun sha(value: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(value).joinToString("") { "%02x".format(it) }
    private fun canonical(value: Any?): String = when (value) {
        null, JSONObject.NULL -> "null"
        is JSONObject -> value.keys().asSequence().toList().sorted().joinToString(",", "{", "}") { canonical(it) + ":" + canonical(value.get(it)) }
        is JSONArray -> (0 until value.length()).joinToString(",", "[", "]") { canonical(value.get(it)) }
        is String -> JSONObject.quote(value).replace("\\/", "/")
        is Boolean, is Number -> value.toString()
        else -> error("Unsupported metadata")
    }
}
