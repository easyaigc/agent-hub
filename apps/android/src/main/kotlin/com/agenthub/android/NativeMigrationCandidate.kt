package com.agenthub.android

import android.content.Context
import android.util.JsonReader
import android.util.JsonToken
import com.agenthub.core.RuntimeIdentity
import com.agenthub.core.ReplyRecoveryRules
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.InputStreamReader
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID

/** An authenticated candidate is never an active installation or permission to repeat a command. */
internal class NativeMigrationCandidateStore(context: Context, parent: NativeSessionStorage) {
    private val applicationId = context.packageName
    private val storage = parent.child(context, "migration-candidates")
    private val journal = NativeOperationJournal(parent)
    internal data class Receipt(val id: String, val sha256: String, val recordCount: Int)
    internal class InvalidCandidate : Exception("Invalid migration candidate; active data was not changed")
    companion object {
        // Initial candidate quota. Large attachment/chunked import is a separate gate.
        const val MAX_PAYLOAD_BYTES = 8 * 1024 * 1024
        const val MAX_ENVELOPE_BYTES = 12 * 1024 * 1024
        private const val SLOT = "candidate-v1"
        private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        private fun exact(value: JSONObject, keys: Set<String>) = require(value.keys().asSequence().toSet() == keys)
        private fun objectValue(raw: String) = StrictMigrationJson.parse(raw.toByteArray(Charsets.UTF_8))
    }

    /** The only publication is one Keystore-encrypted AtomicFile in a separate namespace. */
    fun stage(candidateId: String, envelope: ByteArray, password: String, replacing: Receipt? = null): Receipt = try {
        require(UUID.fromString(candidateId).toString() == candidateId)
        require(envelope.size <= MAX_ENVELOPE_BYTES)
        val clear = NativeMigrationCrypto.open(envelope, password)
        try {
            val records = validate(clear)
            val receipt = Receipt(candidateId, sha(clear), records.size)
            val sealed = JSONObject().put("version", 1).put("id", receipt.id).put("sha256", receipt.sha256)
                .put("payload", Base64.getEncoder().encodeToString(clear))
            storage.operationTransaction {
                val prior = storage.read(SLOT)?.let(::objectValue)
                if (prior?.getString("id") == candidateId) {
                    require(prior.getString("sha256") == receipt.sha256)
                    read(receipt) // Exact original result, without rewriting its ciphertext.
                } else {
                    require(if (prior == null) replacing == null else replacing != null &&
                        prior.getString("id") == replacing.id && prior.getString("sha256") == replacing.sha256)
                    if (replacing != null) read(replacing)
                    storage.write(SLOT, sealed.toString())
                }
            }
            receipt
        } finally { clear.fill(0) }
    } catch (_: Exception) { throw InvalidCandidate() }

    /** A cold reader must name the original candidate and digest; it cannot adopt a replacement. */
    fun read(receipt: Receipt): Map<String, String> {
        require(UUID.fromString(receipt.id).toString() == receipt.id && receipt.sha256.matches(Regex("[a-f0-9]{64}")))
        val saved = objectValue(storage.read(SLOT) ?: error("Migration candidate unavailable"))
        exact(saved, setOf("version", "id", "sha256", "payload"))
        require(saved.get("version") == 1 && saved.getString("id") == receipt.id && saved.getString("sha256") == receipt.sha256)
        val clear = Base64.getDecoder().decode(saved.getString("payload"))
        try { require(sha(clear) == receipt.sha256); return validate(clear).also { require(it.size == receipt.recordCount) } }
        finally { clear.fill(0) }
    }

    internal fun validate(clear: ByteArray): Map<String, String> {
        require(clear.size <= MAX_PAYLOAD_BYTES)
        val root = StrictMigrationJson.parse(clear)
        exact(root, setOf("format", "version", "applicationId", "records"))
        require(root.getString("format") == "agent-hub/android-migration" && root.get("version") == 1)
        require(root.getString("applicationId") == applicationId)
        val values = root.getJSONObject("records")
        val names = values.keys().asSequence().toList(); require(names.size in 1..1024 && "metadata" in names)
        val records = names.associateWith { name -> require(name.length <= 8192 && !name.contains('\u0000')); values.get(name) as String }
        // Parse every record with duplicate/depth/UTF-8 checks, preserving its exact original text for later activation.
        val allValues = records.mapValues { StrictMigrationJson.value(it.value.toByteArray(Charsets.UTF_8)) }
        val parsed = allValues.filterValues { it is JSONObject }.mapValues { it.value as JSONObject }
        val metadata = parsed.getValue("metadata")
        NativeMigrationMetadata.validate(metadata)
        require(metadata.get("version") == 1)
        require(metadata.get("appearance") in listOf("system", "light", "dark"))
        require(metadata.get("demo") is Boolean)
        metadata.getString("selected"); metadata.getJSONObject("drafts"); metadata.getJSONObject("selectedThreads")
        metadata.getJSONObject("expanded"); metadata.getJSONObject("workspaces")
        val clients = metadata.getJSONArray("clients").objects().map(::decodeClient)
        require(clients.size <= 128 && clients.map { it.id }.distinct().size == clients.size)
        require(clients.all { it.scope?.agentId == "codex" }) // EasyWork device re-pairing is not an identity copy.
        val historyNames = NativeMigrationHistory.validate(allValues,clients)
        val ancillaryNames = NativeMigrationAncillary.validate(parsed)
        require(allValues.keys.all { it in parsed || it in historyNames })
        val operations = mutableListOf<SavedOperation>()
        val manifests = mutableMapOf<String, JSONObject>()
        fun manifest(value: JSONObject) {
            CodexCommandCodec.validate(value, "creationFile")
            val id = value.getString("id"); require(UUID.fromString(id).toString() == id)
            manifests[id]?.let { prior -> require(prior.toString() == value.toString()) }
            manifests[id] = value
        }
        metadata.optJSONObject("draftFiles")?.let { drafts -> drafts.keys().forEach { key -> drafts.getJSONArray(key).objects().forEach(::manifest) } }
        NativePhotoOrigins.validate(metadata.optJSONObject("photoSources"),manifests.values.map {LocalDraftFile(CreationFile(it.getString("id"),it.getString("name"),it.getString("mediaType"),it.getLong("sizeBytes"),it.getString("sha256")))})
        for ((name, body) in parsed) when {
            name == "metadata" -> Unit
            name in historyNames -> Unit
            name in ancillaryNames -> Unit
            name == "codex-private-connections-v1" -> CodexConnectionVault.validateRows(body)
            name.startsWith("draft-file:") -> {
                exact(body, setOf("file", "bytes")); val file = body.getJSONObject("file"); manifest(file)
                require(name == "draft-file:" + file.getString("id"))
                val encoded = body.getString("bytes"); val bytes = Base64.getDecoder().decode(encoded)
                try {
                    require(Base64.getEncoder().encodeToString(bytes) == encoded && bytes.size <= 20 * 1024 * 1024)
                    require(file.getLong("sizeBytes") == bytes.size.toLong() && file.getString("sha256") == sha(bytes))
                } finally { bytes.fill(0) }
            }
            name.startsWith("operations:") -> {
                val target = name.removePrefix("operations:")
                body.getJSONArray("operations").objects().forEach { row ->
                    objectValue(row.getString("intent"))
                    row.optional("result")?.let(::objectValue)
                }
                val rows = journal.validateMigrationRecords(target, body)
                rows.forEach { row ->
                    val intent = objectValue(row.intent)
                    val client = clients.single { it.id == intent.getString("hostId") }
                    require(target in client.operationTargets(intent.optional("threadId")))
                    row.replyRecovery?.let { require(it.matchesObservation(client, row)) }
                }
                operations.addAll(rows)
            }
            name.startsWith("submission:") -> {
                body.getJSONArray("files").objects().forEach(::manifest)
                NativePhotoOrigins.validate(body.optJSONObject("photoSources"),body.getJSONArray("files").objects().map {LocalDraftFile(CreationFile(it.getString("id"),it.getString("name"),it.getString("mediaType"),it.getLong("sizeBytes"),it.getString("sha256")))})
                require(clients.any { it.id == body.getString("hostId") && RuntimeIdentity.matches(it.scope, HostReadCodec.target(body.getJSONObject("target"))) })
            }
            else -> error("Unsupported migration record")
        }
        NativeMigrationReferences.validate(parsed,clients,operations)
        // Only verified Codex credentials are supported here. Device keys, grants, push bindings
        // and other provider re-pairing remain explicit boundaries, never silently discarded.
        require(operations.map { it.id }.distinct().size == operations.size)
        for ((id, _) in manifests) require("draft-file:$id" in records)
        for ((name, body) in parsed) if (name.startsWith("submission:")) {
            val row = operations.single { it.id == name.removePrefix("submission:") }
            require(row.sourceKey == body.getString("sourceKey") && row.sourceVersion == body.getString("sourceVersion"))
            val intent = objectValue(row.intent)
            require(intent.getString("hostId") == body.getString("hostId"))
            val target = CodexCommandCodec.target(HostReadCodec.target(body.getJSONObject("target"))!!).toString()
            if (row.kind == "reply") {
                val settings = GenerationSettings(body.optional("model"), body.optional("effort"), body.optional("permissionMode"), body.optional("speed"), body.optional("collaborationMode"))
                val captured = ReplyIntent(body.getString("hostId"), intent.getString("threadId"), row.id, body.getString("text"),
                    intent.getJSONArray("attachmentIds").strings(), intent.getString("mode"), intent.optional("expectedTurnId"), intent.optional("expectedPlanId"), settings)
                require(body.getJSONArray("files").length() == intent.getJSONArray("attachmentIds").length())
                require(ReplyRecoveryRules.canonicalPayload(target, row.intent) == ReplyRecoveryRules.canonicalPayload(target, captured.body().toString()))
            } else if (row.kind == "creation") {
                require(intent.getString("text") == body.getString("text"))
                require(intent.getJSONArray("attachments").toString() == body.getJSONArray("files").toString())
                for (field in listOf("model", "effort", "permissionMode", "speed", "collaborationMode")) require(intent.optional(field) == body.optional(field))
            } else error("Unexpected submission snapshot")
        }
        return records
    }
}

/** Strict bounded local archive parser; strings that hold original records are checked again at their own boundary. */
internal object StrictMigrationJson {
    fun parse(bytes: ByteArray): JSONObject = value(bytes) as JSONObject
    fun value(bytes: ByteArray): Any {
        require(bytes.size <= NativeMigrationCandidateStore.MAX_PAYLOAD_BYTES * 2)
        var nodes = 0
        JsonReader(InputStreamReader(ByteArrayInputStream(bytes), Charsets.UTF_8.newDecoder())).use { reader ->
            reader.isLenient = false
            fun value(depth: Int): Any {
                require(depth <= 32 && ++nodes <= 200_000)
                return when (reader.peek()) {
                    JsonToken.BEGIN_OBJECT -> {
                        reader.beginObject(); val result = JSONObject(); val names = mutableSetOf<String>()
                        while (reader.hasNext()) {
                            val name = reader.nextName(); require(names.add(name))
                            Charsets.UTF_8.newEncoder().encode(java.nio.CharBuffer.wrap(name))
                            result.put(name, value(depth + 1))
                        }
                        reader.endObject(); result
                    }
                    JsonToken.BEGIN_ARRAY -> { reader.beginArray(); val result = JSONArray(); while (reader.hasNext()) result.put(value(depth + 1)); reader.endArray(); result }
                    JsonToken.STRING -> reader.nextString().also { Charsets.UTF_8.newEncoder().encode(java.nio.CharBuffer.wrap(it)) }
                    JsonToken.NUMBER -> {
                        val text = reader.nextString(); val integer = text.toLongOrNull()
                        if (integer != null) { if (integer in Int.MIN_VALUE..Int.MAX_VALUE) integer.toInt() else integer }
                        else text.toDouble().also { require(it.isFinite()) }
                    }
                    JsonToken.BOOLEAN -> reader.nextBoolean()
                    JsonToken.NULL -> { reader.nextNull(); JSONObject.NULL }
                    else -> error("Invalid migration JSON")
                }
            }
            val result = value(0)
            require(reader.peek() == JsonToken.END_DOCUMENT); return result
        }
    }
}
