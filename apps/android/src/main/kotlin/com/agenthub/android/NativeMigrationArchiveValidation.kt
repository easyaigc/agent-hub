package com.agenthub.android

import com.agenthub.core.RuntimeIdentity
import com.agenthub.core.ReplyRecoveryRules
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Same closed graph validator for mutable staging and read-only existing candidates. No storage. */
internal object NativeMigrationArchiveValidation {
    private fun exact(value: JSONObject,keys: Set<String>)=require(value.keys().asSequence().toSet()==keys)
    private fun objectValue(raw: String)=StrictMigrationJson.parse(raw.toByteArray(Charsets.UTF_8))
    fun archive(clear: ByteArray,applicationId: String): NativeMigrationRecords {
        require(clear.size <= NativeMigrationCandidateStore.MAX_PAYLOAD_BYTES)
        val root = StrictMigrationJson.parse(clear)
        val version=root.get("version");require(version==1||version==2)
        exact(root,setOf("format","version","applicationId","records")+if(version==2)setOf("binaries")else emptySet())
        require(root.getString("format")=="agent-hub/android-migration")
        require(root.getString("applicationId") == applicationId)
        val values = root.getJSONObject("records")
        val names = values.keys().asSequence().toList(); require(names.size in 1..1024 && "metadata" in names)
        val records = names.associateWith { name -> require(name.isNotEmpty() && name.length <= 8192 && !name.contains('\u0000')); values.get(name) as String }
        val binaries=if(version==2)root.getJSONObject("binaries").let {body->body.keys().asSequence().associateWith {key->require(key.isNotEmpty()&&key.length<=8192&&!key.contains('\u0000'));body.get(key) as String}.also {require(it.isNotEmpty())}}else emptyMap()
        val archive=NativeMigrationRecords(records,binaries);require(archive.size<=1024)
        validateRecordGraph(records, binaries, null)
        return archive
    }

    fun stream(archive: NativeMigrationStreamRecords, verifySoundBytes: Boolean = true) {
        require(archive.size<=1024)
        validateRecordGraph(archive.text, emptyMap(), archive.binary, verifySoundBytes)
    }

    private fun validateRecordGraph(records: Map<String,String>, binaries: Map<String,String>, streams: Map<String,NativeMigrationBlob>?, verifySoundBytes: Boolean = true) {
        val inbox=NativeMigrationInbox.validate(records,streams?.filterKeys {it.startsWith(NativeMigrationInbox.BINARY)}?:NativeMigrationInbox.descriptors(binaries.filterKeys {it.startsWith(NativeMigrationInbox.BINARY)}))
        val selectionBinaries=streams?:NativeMigrationInbox.descriptors(binaries.filterKeys {name->NativeMigrationSelectionJournal.Kind.values().any {name.startsWith(NativeMigrationSelections.binaryPrefix(it))}})
        val selections=NativeMigrationSelections.validate(records,selectionBinaries)
        val foregroundNames=NativeMigrationNotificationArchives.validatePortable(records)+NativeMigrationEasyWorkArchives.validatePortable(records)+NativeMigrationEasyWorkBusiness.validatePortable(records)+inbox.textNames+selections.first+NativeMigrationPhotoLocals.validate(records,selectionBinaries)+NativeSelectionReturnAudit.validate(records)+NativeMigrationPrivateClaims.validatePortable(records,streams?:NativeMigrationInbox.descriptors(binaries.filterKeys {it.startsWith(NativeMigrationPrivateClaims.BINARY)||it.startsWith(NativeMigrationPrivateClaims.ACTIVE_PREFIX)}))
        require("metadata" in records)
        // Frozen archive readers already checked their claimed records. Parse each other value once
        // for shape, then expose lazy views instead of retaining all JSON/raw text concurrently.
        val names=records.keys-foregroundNames
        fun value(name: String)=StrictMigrationJson.value(records.getValue(name).toByteArray(Charsets.UTF_8))
        val objects=names.filter {value(it) is JSONObject}.toSet()
        val allValues=NativeMigrationValueMap(names,::value)
        val parsed=NativeMigrationValueMap(objects){name->value(name) as JSONObject}
        val metadata = parsed.getValue("metadata")
        NativeMigrationMetadata.validate(metadata)
        require(metadata.get("version") == 1)
        require(metadata.get("appearance") in listOf("system", "light", "dark"))
        require(metadata.get("demo") is Boolean)
        metadata.getString("selected"); metadata.getJSONObject("drafts"); metadata.getJSONObject("selectedThreads")
        metadata.getJSONObject("expanded"); metadata.getJSONObject("workspaces")
        val clients = metadata.getJSONArray("clients").objects().map(::decodeClient)
        require(clients.size <= 128 && clients.map { it.id }.distinct().size == clients.size)
        NativeMigrationEasyWorkBusiness.validateMetadataGraph(metadata,clients)
        val historyNames = NativeMigrationHistory.validate(allValues,clients)
        val ancillaryNames = NativeMigrationAncillary.validate(parsed)
        val deviceNames = NativeMigrationDeviceState.validate(records,clients)
        val voicePreferenceNames = NativeVoicePreferenceRecord.validate(records)
        val provisionalBinary=streams?.filterKeys {it.startsWith(NativeMigrationProvisionalVoice.PREFIX)}?:NativeMigrationProvisionalVoice.descriptors(binaries.filterKeys {it.startsWith(NativeMigrationProvisionalVoice.PREFIX)})
        val provisionalNames=NativeMigrationProvisionalVoice.validate(records,provisionalBinary,clients)
        val soundBinaries = NativeMigrationSounds.validate(records,binaries,streams,verifySoundBytes)
        val voiceBinaries=binaries.filterKeys {it.startsWith("voice-audio:")||it.startsWith(NativeVoiceDiscardCatalog.RAW_PREFIX)}
        val voiceStreams=streams?.filterKeys {it.startsWith("voice-audio:")||it.startsWith(NativeVoiceDiscardCatalog.RAW_PREFIX)}
        val voiceNames=if(streams==null)NativeMigrationVoice.validate(allValues,voiceBinaries,clients,records)else NativeMigrationVoice.validateStreams(allValues,voiceStreams!!,clients,records)
        val attachmentBinaries=mutableSetOf<String>()
        require(allValues.keys.all { it in parsed || it in historyNames || it in voiceNames })
        val operationIds=mutableSetOf<String>()
        fun operations()=sequence {
            for(name in objects.filter {it.startsWith("operations:")})yieldAll(NativeOperationJournal.validateExistingMigrationRecords(name.removePrefix("operations:"),parsed.getValue(name)))
        }
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
            name in deviceNames -> Unit
            name in voicePreferenceNames -> Unit
            name == NativeMigrationSounds.RECORD -> Unit
            name == NativeMigrationProvisionalVoice.RECORD -> Unit
            name in voiceNames -> Unit
            name in foregroundNames -> Unit
            name == "codex-private-connections-v1" -> CodexConnectionVault.validateRows(body)
            name.startsWith("draft-file:") -> {
                manifest(body.getJSONObject("file"))
                NativeMigrationAttachments.validate(name,body,binaries,streams)?.let {require(attachmentBinaries.add(it))}
            }
            name.startsWith("operations:") -> {
                val target = name.removePrefix("operations:")
                body.getJSONArray("operations").objects().forEach { row ->
                    objectValue(row.getString("intent"))
                    row.optional("result")?.let(::objectValue)
                }
                val rows = NativeOperationJournal.validateExistingMigrationRecords(target, body)
                rows.forEach { row ->
                    val intent = objectValue(row.intent)
                    val client = clients.single { it.id == intent.getString("hostId") }
                    require(target in client.operationTargets(intent.optional("threadId")))
                    if(client.scope?.agentId=="easywork")require(row.kind in setOf("reply","creation","open","thread-create","chat-action")) {"EasyWork task/project management migration is not enabled"}
                    row.replyRecovery?.let { require(it.matchesObservation(client, row)) }
                }
                rows.forEach {require(operationIds.add(it.id))}
            }
            name.startsWith("submission:") -> {
                body.getJSONArray("files").objects().forEach(::manifest)
                NativePhotoOrigins.validate(body.optJSONObject("photoSources"),body.getJSONArray("files").objects().map {LocalDraftFile(CreationFile(it.getString("id"),it.getString("name"),it.getString("mediaType"),it.getLong("sizeBytes"),it.getString("sha256")))})
                require(clients.any { it.id == body.getString("hostId") && RuntimeIdentity.matches(it.scope, HostReadCodec.target(body.getJSONObject("target"))) })
            }
            else -> error("Unsupported migration record")
        }
        require((streams?.keys?:binaries.keys)==(voiceStreams?.keys?:voiceBinaries.keys)+attachmentBinaries+soundBinaries+provisionalNames+inbox.binaryNames+selections.second)
        NativeMigrationReferences.validate(parsed,clients,operations())
        // EasyWork credentials stay only in exact original-installation archives; projected
        // clients cannot use a credential reference or inherit execution authority.
        require(parsed.keys.filter {it.startsWith("submission:")}.all {it.removePrefix("submission:") in operationIds})
        for ((id, _) in manifests) require("draft-file:$id" in records)
        for (row in operations()) {
            val body=parsed["submission:${row.id}"]?:continue
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
    }
}
