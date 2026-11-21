package com.agenthub.android

import android.content.Context
import com.agenthub.core.TargetScope
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.UUID

/** Read-only cache projection: no authority, lease creation, Activity, Session or writer.
 * Invoked only by the separately admitted current-cache-graph read-only test.
 * Both callbacks are mandatory: the existing elapsed lease and exact CURRENT opaque
 * FD/stat/hash inventory fence. Caller retains the original empty writer.lock owner.
 */
internal object Q04CurrentOwnedCacheGraph {
    data class CipherEntry(val bytes: Long, val sha256: String)
    private const val MAX_TEXT = 1_048_576
    private fun digest(value: String) = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    private fun filename(name: String) = digest(name) + ".bin"
    private fun relative(name: String) = "no-backup/q02-session/" + filename(name)
    private fun machine(target: TargetScope) = JSONObject().put("agentId", target.agentId)
        .put("hostId", target.hostId).put("workspaceId", target.workspaceId).put("runtimeId", target.runtimeId)

    /** Inventory values come from the new current read-only admission document, never old B.
     * No unknown filename is offered as a guessed logical name to a decoder.
     * Strings returned by ExistingReader and production decode remain local, never serialized.
     */
    fun inspect(
        context: Context,
        owned: Q04ExistingOwnedProfile,
        expectedTarget: TargetScope,
        expectedThreadId: String,
        currentInventory: Map<String, CipherEntry>,
        addedCandidateFilename: String,
        removedHistoricalFilename: String,
        checkLease: () -> Unit,
        assertCurrentUnchanged: () -> Unit
    ): JSONObject {
        require(currentInventory.size == 11)
        require(currentInventory.keys.all { it.matches(Regex("no-backup/q02-session/[a-f0-9]{64}\\.bin")) })
        require(currentInventory.values.all { it.bytes in 1..MAX_TEXT.toLong() && it.sha256.matches(Regex("[a-f0-9]{64}")) })
        require(addedCandidateFilename.matches(Regex("[a-f0-9]{64}\\.bin")))
        require(removedHistoricalFilename.matches(Regex("[a-f0-9]{64}\\.bin")))
        require(sameScope(owned.client.scope, expectedTarget) && expectedTarget.agentId == "codex")
        require(UUID.fromString(owned.original.id).toString() == owned.original.id)
        require(expectedThreadId.isNotEmpty())
        checkLease(); assertCurrentUnchanged()
        val cacheKey = owned.client.cacheKey(expectedThreadId)
        val historyName = "history:$cacheKey"
        val authenticatedNames = linkedSetOf<String>()
        val indexedPartNames = linkedSetOf<String>()
        val knownNames = linkedSetOf("metadata", "operations:$cacheKey", historyName,
            "mobile-reading-ledger-v1", "ai-sharing-v1", "submission:${owned.original.id}")
        knownNames.addAll(Q04ExistingOwnedProfile.protectedNames)
        var cacheVersion = 0
        var generation: String? = null
        var declaredMessages = -1
        var decodedMessages = -1
        var productionDecodePassed = false
        var primary: Throwable? = null
        try {
            val fixedContext = Q02RealReadInstallation.FilesContext(context, owned.root)
            NativeSessionStorage.ExistingReader.open(fixedContext, "q02-session", owned.alias).use { reader ->
                fun before(name: String) {
                    checkLease(); assertCurrentUnchanged()
                    require(relative(name) in currentInventory)
                }
                before("metadata")
                val metadata = JSONObject(reader.text("metadata", MAX_TEXT))
                assertCurrentUnchanged(); checkLease()
                require(metadata.get("version") is Int && metadata.getInt("version") == 1)
                val metadataClient = metadata.getJSONArray("clients").objects().map(::decodeClient).single()
                require(!metadataClient.isDemo && metadataClient.id == owned.client.id &&
                    sameScope(metadataClient.scope, expectedTarget) && metadataClient.cacheKey(expectedThreadId) == cacheKey)
                authenticatedNames.add("metadata")

                before(historyName)
                val rawIndex = requireNotNull(reader.optionalText(historyName, MAX_TEXT))
                assertCurrentUnchanged(); checkLease()
                val index = JSONObject(rawIndex)
                require(index.get("version") is Int)
                cacheVersion = index.getInt("version"); require(cacheVersion in 1..2)
                require(index.getString("targetKey") == cacheKey)
                // Header decoding uses the production codec; no wire/header JSON is emitted.
                val header = MobileSnapshotCodec.history(index.getJSONObject("history").toString())
                require(header.hostId == owned.client.id && header.threadId == expectedThreadId &&
                    sameScope(header.scope, expectedTarget))
                authenticatedNames.add(historyName)
                if (cacheVersion == 2) {
                    generation = index.getString("generation")
                    require(UUID.fromString(generation).toString() == generation)
                    val names = index.getJSONArray("parts").strings()
                    require(names.size <= currentInventory.size && names.distinct().size == names.size)
                    names.forEachIndexed { position, name ->
                        require(name == "$historyName:part:$generation:$position")
                        require(relative(name) in currentInventory)
                        indexedPartNames.add(name)
                    }
                    require(index.get("messageCount") is Int)
                    declaredMessages = index.getInt("messageCount")
                    require(declaredMessages in 0..(32 * currentInventory.size))
                }
                // This is the actual production cache decoder. The finite callback can read
                // only the preauthenticated index or names obtained from that index.
                val decoded = requireNotNull(NativeHistoryCache.decode(cacheKey) { name ->
                    require(name == historyName || name in indexedPartNames)
                    if (name == historyName) rawIndex else {
                        before(name)
                        val value = reader.optionalText(name, MAX_TEXT)
                        assertCurrentUnchanged(); checkLease()
                        if (value != null) authenticatedNames.add(name)
                        value
                    }
                })
                require(decoded.hostId == owned.client.id && decoded.threadId == expectedThreadId &&
                    sameScope(decoded.scope, expectedTarget))
                decodedMessages = decoded.messages.size
                if (cacheVersion == 2) require(decodedMessages == declaredMessages)
                productionDecodePassed = true
            }
            knownNames.addAll(indexedPartNames)
            val mapped = JSONArray()
            val mappedPaths = linkedSetOf<String>()
            knownNames.forEach { name ->
                val path = relative(name)
                currentInventory[path]?.let { cipher ->
                    require(mappedPaths.add(path))
                    mapped.put(JSONObject().put("logicalKey", name).put("cipherFilename", filename(name))
                        .put("cipherBytes", cipher.bytes).put("cipherSha256", cipher.sha256)
                        .put("logicalNameAuthenticatedByExistingReader", name in authenticatedNames)
                        .put("mappingBasis", if (name in authenticatedNames) "existing-v3-record-authentication" else "known-name-sha256-only-not-decrypted"))
                }
            }
            val unknown = JSONArray()
            (currentInventory.keys - mappedPaths).sorted().forEach { path ->
                val cipher = currentInventory.getValue(path)
                unknown.put(JSONObject().put("cipherFilename", path.substringAfterLast('/'))
                    .put("cipherBytes", cipher.bytes).put("cipherSha256", cipher.sha256)
                    .put("logicalKey", JSONObject.NULL).put("classification", "UNKNOWN-preserved-not-decrypted"))
            }
            val indexedFiles = indexedPartNames.map(::filename).toSet()
            return JSONObject().put("kind", "q04-current-cache-graph-readonly-projection-v1")
                .put("target", machine(expectedTarget)).put("threadId", expectedThreadId)
                .put("inventoryFiles", currentInventory.size).put("inventoryBytes", currentInventory.values.sumOf { it.bytes })
                .put("mappedNameCount", mappedPaths.size).put("authenticatedReadCount", authenticatedNames.size)
                .put("unknownFileCount", unknown.length()).put("records", mapped).put("unknown", unknown)
                .put("metadataTargetProjectionValid", true).put("cacheVersion", cacheVersion)
                .put("generation", generation ?: JSONObject.NULL).put("indexedParts", indexedPartNames.size)
                .put("declaredMessageCount", if (cacheVersion == 2) declaredMessages else JSONObject.NULL)
                .put("productionDecodedMessageCount", decodedMessages).put("productionCacheDecodePassed", productionDecodePassed)
                .put("historyTargetAndThreadMatched", true).put("allIndexedPartsAuthenticated", indexedPartNames.all { it in authenticatedNames })
                .put("addedCandidateFilename", addedCandidateFilename).put("addedCandidateIsIndexedAuthenticatedPart",
                    addedCandidateFilename in indexedFiles && indexedPartNames.filter { filename(it) == addedCandidateFilename }.all { it in authenticatedNames })
                .put("removedHistoricalFilename", removedHistoricalFilename)
                .put("removedHistoricalFileAbsentNow", currentInventory.keys.none { it.substringAfterLast('/') == removedHistoricalFilename })
                .put("removedHistoricalLogicalName", JSONObject.NULL).put("historicalDeletedNameProvenanceClosed", false)
                .put("profileWrites", 0).put("networkRequests", 0).put("modelInputs", 0).put("credentialsExported", false)
                .put("payloadsExported", false).put("readOnlyProjectionIsNotExecutionAuthority", true)
        } catch (failure: Throwable) {
            primary = failure
            throw failure
        } finally {
            // Caller must ALSO perform these in its own finally after release/re-admission.
            try { assertCurrentUnchanged(); checkLease() } catch (fenceFailure: Throwable) {
                val original = primary
                if (original == null) throw fenceFailure else original.addSuppressed(fenceFailure)
            }
        }
    }
}
