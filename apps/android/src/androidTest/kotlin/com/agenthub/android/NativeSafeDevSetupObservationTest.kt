package com.agenthub.android

import android.content.Context
import android.os.Bundle
import android.os.Looper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Existing-only summaries. No Session, writer, identity loader, Activity or network port.
 * Cipher digests bracket decoding and are checked again before publication. The external
 * parent still owns whole-directory/stat protection; this is not a filesystem transaction.
 */
internal object NativeSafeDevSetupObservation {
    private const val APP = "com.easyai.agenthub.android.acceptance"
    private const val LIMIT = 1_048_576 // ExistingReader limit; larger ledgers are unsupported, never truncated.
    private const val CODEX = "4edc39b469ffdfebdbf64b04e9a672decd1aa5037646336018bfb63581ebbf9b"
    private const val EW = "6ee4a03911278315dcdb5ba58c21817eb321d2ea35175a191b17e957407d0f8d"
    internal class Unavailable : IllegalStateException("SAFE_DEV_OBSERVATION_UNAVAILABLE")
    internal class Ledger(val revision: Long, val scopes: Set<String>, val rows: Map<String, String>)
    internal class Snapshot(val report: JSONObject, val ledger: Ledger?)
    private fun sha(text: String) = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
    private fun canonical(value: Any) = NativeChatRecoveryCanonical.value(value)
    private fun hash(value: Any) = sha(canonical(value))
    private fun bounded(value: JSONObject): JSONObject = value.also {
        require(it.toString().toByteArray(Charsets.UTF_8).size <= 65_536)
    }
    fun unavailable() = JSONObject().put("schema", 1).put("status", "unavailable")
        .put("code", "SAFE_DEV_OBSERVATION_UNAVAILABLE")
    fun encoded(report: JSONObject): String = try { bounded(report).toString() }
        catch (_: Exception) { unavailable().toString() }

    fun capture(context: Context, run: String, selector: String): Snapshot = try {
        check(Looper.myLooper() != Looper.getMainLooper())
        require(UUID.fromString(run).toString() == run)
        require(selector in setOf(
            "com.agenthub.android.NativeDefaultTwoDevNormalPairingTest#appendTwoFixedDevConnectionsWithoutInput",
            NativeSafeDevSetupObservationTest.SELECTOR))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        require(BuildConfig.DEBUG && context.packageName == APP &&
            instrumentation.targetContext.packageName == APP &&
            instrumentation.targetContext.applicationInfo.uid == android.os.Process.myUid() &&
            context.applicationInfo.uid == android.os.Process.myUid() &&
            instrumentation.context.packageName == "$APP.test")
        val installed = NativeChatNotificationNaturalStore.installed(context)
        require(installed.signerSetHash == "4f719bb23e1366a472b42507d73147e69b005aaf84f8d12ae020208dc1618965")
        val records = JSONArray()
        val bindings = JSONArray()
        val digests = linkedMapOf<String, String?>()
        val report = JSONObject().put("schema", 1).put("status", "observed")
            .put("ownerCallbacksRun", run).put("selector", selector).put("package", APP)
            .put("uid", android.os.Process.myUid()).put("sdk", android.os.Build.VERSION.SDK_INT)
            .put("appSHA256", installed.appHash).put("runnerSHA256", installed.runnerHash)
            .put("signerSetSHA256", installed.signerSetHash)
            .put("namespace", "mobile-v1").put("keyAlias", "agent-hub-mobile-v1")
            .put("readOnlyReader", true).put("mutationCounter", "not-exposed")
            .put("zeroWriteAPICallsByReviewedEntry", true).put("completeBindingVerified", "not-checked")
            .put("records", records).put("bindings", bindings)
        var ledger: Ledger? = null
        NativeSessionStorage.ExistingReader.open(context, "mobile-v1", "agent-hub-mobile-v1").use { reader ->
            fun read(name: String, provenance: String): String? {
                check(!Thread.currentThread().isInterrupted && name !in digests && digests.size < 6)
                val before = reader.opaqueTextDigest(name, LIMIT)
                val text = reader.optionalText(name, LIMIT)
                require((before == null) == (text == null) && reader.opaqueTextDigest(name, LIMIT) == before)
                digests[name] = before
                // NativeSessionStorage.atomic/digest: SHA256(UTF8(logical name)) + .bin.
                records.put(JSONObject().put("logicalName", name).put("filename", sha(name) + ".bin")
                    .put("cipherSHA256", before ?: JSONObject.NULL).put("present", text != null)
                    .put("provenance", provenance))
                return text
            }
            val metadata = JSONObject(read("metadata", "MobileSession metadata / production client codec")
                ?: throw Unavailable())
            // The same closed metadata/client validator used by production migration admission.
            NativeMigrationMetadata.validate(metadata, allowSourceEasyWork = true)
            val clients = metadata.getJSONArray("clients").objects().map(::decodeClient)
            report.put("clientCount", clients.size)
            val vault = read("codex-private-connections-v1", "CodexConnectionVault.key/validateRows")
                ?.let { CodexConnectionVault.validateRows(JSONObject(it)) }
            // Explicitly existing-only equivalent of EasyWorkIdentity.load's decode branch;
            // never call load(), whose absent branch generates and writes a new identity.
            val identityRaw = read("easywork-device-identity", "EasyWorkIdentity.load existing-record branch")
            val identity = identityRaw?.let { raw ->
                val v = JSONObject(raw)
                require(v.keys().asSequence().toSet() == setOf("deviceId", "privateKey", "publicKey"))
                val id = v.getString("deviceId")
                require(id.length in 1..512 && id.none(Char::isWhitespace))
                val publicKey = v.getString("publicKey")
                val privateKey = v.getString("privateKey")
                require(publicKey.length == 43 && privateKey.length == 43)
                val secret = easyBytes(privateKey)
                val publicBytes = easyBytes(publicKey)
                try {
                    require(secret.size == 32 && publicBytes.size == 32 &&
                        org.bouncycastle.crypto.params.X25519PrivateKeyParameters(secret, 0)
                            .generatePublicKey().encoded.contentEquals(publicBytes))
                } finally { secret.fill(0); publicBytes.fill(0) }
                // Only public data is retained beyond the production consistency check.
                id to publicKey
            }
            report.put("existingIdentityAbsent", identity == null)
                .put("ownPublicKeySHA256", identity?.second?.let(::sha) ?: JSONObject.NULL)
                .put("ownPublicKeyHashEncoding", "SHA256(UTF8(publicKey-base64url))")
            val rawLedger = read(NativeReadingLedger.STORAGE_NAME, "NativeReadingLedger.STORAGE_NAME/canonicalEncodedForInspection")
            val ledgerReport = JSONObject().put("present", rawLedger != null)
            if (rawLedger != null) {
                val value = JSONObject(NativeReadingLedger.canonicalEncodedForInspection(rawLedger))
                val rows = value.getJSONArray("rows").objects().associate { row ->
                    val target = checkNotNull(HostReadCodec.target(row.getJSONObject("target")))
                    ThreadReadingIdentity.key(target, row.getString("threadId")) to canonical(row)
                }
                val scopes = value.getJSONArray("scopes").strings().toSet()
                ledger = Ledger(value.getLong("revision"), scopes, rows)
                ledgerReport.put("revision", value.getLong("revision")).put("rowCount", rows.size)
                    .put("scopeCount", scopes.size)
                    .put("canonicalSHA256", hash(value))
                    .put("rowProjectionSHA256", hash(JSONObject(rows)))
                    .put("scopeProjectionSHA256", hash(JSONArray(scopes.sorted())))
            } else ledgerReport.put("status", "missing-not-assumed-empty")
            report.put("readingLedger", ledgerReport)
            for ((provider, expected) in listOf("codex" to CODEX, "easywork" to EW)) {
                val matching = clients.filter { client -> !client.isDemo &&
                    (client.agentConnection?.target ?: client.scope)?.let { hash(CodexCommandCodec.target(it)) } == expected }
                require(matching.size <= 1)
                val item = JSONObject().put("provider", provider).put("targetSHA256", expected)
                    .put("descriptorPresent", matching.size == 1)
                bindings.put(item)
                val client = matching.singleOrNull() ?: continue
                require(client.migrationEasyWork == null && client.storageIdentity == null)
                // Only these two source-derived local IDs may escape; names/accounts never do.
                require(client.id == if (provider == "codex") "13c81542-48e3-4df2-ab46-d45357b631a2" else "easywork-$EW")
                item.put("localClientID", client.id)
                if (provider == "codex") {
                    val row = vault?.singleOrNull { it.getJSONObject("client").getString("id") == client.id }
                    item.put("vaultRowPresent", row != null)
                    if (row != null) {
                        require(hash(encodeClient(decodeClient(row.getJSONObject("client")))) == hash(encodeClient(client)))
                        item.put("tokenSHA256", sha(row.getString("token")))
                    }
                    if (identity == null) item.put("claimMappingStatus", "unavailable-existing-identity-absent")
                    else {
                        val scope = checkNotNull(client.scope)
                        val direct = checkNotNull(client.codexDirect)
                        val endpoint = CodexEndpoints.local(checkNotNull(client.endpoint))
                        // Exact production insertion order + JSONObject.toString(), NOT a
                        // canonical serializer: CodexDiscoveryAPI.authorize hashes these bytes.
                        val claim = JSONObject().put("version", 1).put("target", encodeTarget(scope))
                            .put("publicKey", identity.second)
                        require(com.agenthub.core.CodexDiscoveryContract.validateNamed(claim.toString(), "privateClaim") == null)
                        val intent = JSONObject().put("claim", claim).put("endpoint", endpoint)
                            .put("fingerprint", direct.fingerprint).toString()
                        val logical = "codex-private-claim:${sha(intent)}"
                        val saved = read(logical, "CodexDiscoveryAPI.authorize claim/endpoint/fingerprint exact UTF8 bytes + own existing identity")
                        if (saved != null) require(saved == intent)
                        // If the route has changed since pairing, absence is NOT permission
                        // to scan/guess another historical endpoint or invent a claim mapping.
                        item.put("claimMappingStatus", if (saved == null) "unavailable-current-endpoint-claim-absent" else "exact-existing-intent-matched")
                            .put("claimLogicalName", logical)
                    }
                } else {
                    val connection = checkNotNull(client.agentConnection)
                    val prefix = "agent-${client.id}-"
                    require(connection.credentialRef.startsWith(prefix))
                    val lease = connection.credentialRef.removePrefix(prefix)
                    require(UUID.fromString(lease).toString() == lease)
                    val logical = "easywork-credential:${connection.credentialRef}"
                    item.put("credentialLogicalName", logical)
                    val raw = read(logical, "MobileSession.saveEasyWorkLeased metadata.agentConnection.credentialRef / lease.id")
                    item.put("credentialPresent", raw != null)
                    if (raw != null) {
                        val credential = EasyWorkCredential.decode(JSONObject(raw))
                        require(hash(CodexCommandCodec.target(credential.binding.target.scope())) == expected)
                        require(identity != null && credential.binding.mobileId == identity.first &&
                            credential.binding.mobilePublicKey == identity.second)
                        item.put("ownPublicKeyMatchesBinding", true)
                    }
                }
            }
            val complete = identity != null && ledger != null && bindings.objects().all { item ->
                item.getBoolean("descriptorPresent") && if (item.getString("provider") == "codex")
                    item.optBoolean("vaultRowPresent") && item.optString("claimMappingStatus") == "exact-existing-intent-matched"
                else item.optBoolean("credentialPresent") && item.optBoolean("ownPublicKeyMatchesBinding")
            }
            report.put("requiredSavedRecordsPresent", complete)
                .put("savedDevStatus", if (complete) "records-observed-authority-not-checked" else "prerequisite-unmet")
            digests.forEach { (name, value) -> require(reader.opaqueTextDigest(name, LIMIT) == value) }
        }
        Snapshot(bounded(report), ledger)
    } catch (_: Exception) { throw Unavailable() } // Never propagate parser/path/credential exception messages.

    private fun projection(before: Snapshot, current: Snapshot): JSONObject {
        val old = before.ledger
        val next = current.ledger
        val delta = JSONObject().put("beforePresent", old != null).put("afterPresent", next != null)
        if (old != null) {
            val removed = old.rows.keys.count { next?.rows?.containsKey(it) != true }
            val changed = old.rows.count { (key, value) -> next != null && next.rows.containsKey(key) && next.rows[key] != value }
            val removedScopes = old.scopes.count { next?.scopes?.contains(it) != true }
            delta.put("oldRowCount", old.rows.size).put("changedOldRows", changed).put("removedOldRows", removed)
                .put("removedOldScopes", removedScopes).put("oldRowsPreserved", removed == 0 && changed == 0 && next != null)
                .put("oldScopesPreserved", removedScopes == 0 && next != null)
                .put("revisionNondecreasing", next != null && next.revision >= old.revision)
        } else delta.put("oldRowsPreserved", JSONObject.NULL).put("status", "before-missing-not-assumed-empty")
        return delta
    }
    fun after(context: Context, run: String, selector: String, preStartup: Snapshot, beforeRole: Snapshot): JSONObject = try {
        val current = capture(context, run, selector)
        bounded(current.report.put("originalReadingProjection", projection(preStartup, current))
            .put("beforeRoleReadingProjection", projection(beforeRole, current)))
    } catch (_: Exception) { unavailable() } // API completion is independent of observation availability.
}

/** One read-only selector for already-saved results. No foreground rule or Activity. */
@RunWith(AndroidJUnit4::class)
class NativeSafeDevSetupObservationTest {
    companion object {
        internal const val SELECTOR = "com.agenthub.android.NativeSafeDevSetupObservationTest#inspectCurrentSavedTwoDevSetupReadOnly"
        private val runs = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    }
    @Test fun inspectCurrentSavedTwoDevSetupReadOnly() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        var report = NativeSafeDevSetupObservation.unavailable()
        var admittedRun: String? = null
        val worker = Executors.newSingleThreadExecutor()
        try {
            val args = InstrumentationRegistry.getArguments()
            require(args.keySet().all { it in setOf("class", "ownerCallbacksRun", "debug", "log", "timeout_msec", "disableAnalytics") })
            require(args.getString("class") == SELECTOR)
            val run = checkNotNull(args.getString("ownerCallbacksRun"))
            require(UUID.fromString(run).toString() == run && runs.add(run))
            admittedRun = run
            report = worker.submit<JSONObject> {
                NativeSafeDevSetupObservation.capture(instrumentation.targetContext.applicationContext, run, SELECTOR).report
            }.get(15, TimeUnit.SECONDS)
        } catch (_: Exception) { report = NativeSafeDevSetupObservation.unavailable() }
        finally {
            worker.shutdownNow()
            val closed = try { worker.awaitTermination(2, TimeUnit.SECONDS) }
                catch (_: InterruptedException) { Thread.currentThread().interrupt(); false }
            report.put("workerTerminated", closed)
                .put("selector", SELECTOR).put("ownerCallbacksRun", admittedRun ?: JSONObject.NULL)
            instrumentation.sendStatus(2, Bundle().apply {
                putString("safeDevSetupObservationV1", NativeSafeDevSetupObservation.encoded(report))
            })
        }
        check(report.optString("status") == "observed" && report.optBoolean("workerTerminated") &&
            report.optBoolean("requiredSavedRecordsPresent")) { "SAFE_DEV_OBSERVATION_PREREQUISITE_UNMET_OR_UNAVAILABLE" }
    }
}
