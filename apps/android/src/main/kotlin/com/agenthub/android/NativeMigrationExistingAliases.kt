package com.agenthub.android

import org.json.JSONArray
import org.json.JSONObject

/** One production source for exact legacy root aliases; never guesses hashed filenames. */
internal object NativeMigrationExistingAliases {
    fun root(read: (String)->String?): Set<String> {
        val names=mutableSetOf("metadata","codex-private-connections-v1","ai-sharing-v1","voice-index-v1",NativeMigrationDeviceState.READING,NativeMigrationDeviceState.NOTIFICATIONS,NativeMigrationDeviceState.AUDIT,NativeMigrationEasyWorkBusiness.RECORD,NativeMigrationNotificationTombstones.RECORD,NativeMigrationSounds.RECORD)
        fun add(name: String){require(name.length<=8192&&!name.contains('\u0000'));names.add(name);require(names.size<=1024)}
        fun value(name: String)=read(name)?.let{StrictMigrationJson.parse(it.toByteArray(Charsets.UTF_8))}
        NativeMigrationForeground.ACTIVE_NAMES.forEach(::add)
        add(NativeMigrationNotificationArchives.RECORD)
        read(NativeMigrationNotificationArchives.RECORD)?.let {NativeMigrationNotificationArchives.legacyNames(it).forEach(::add)}
        add(NativeVoicePreferenceRecord.RECORD)
        add(NativeVoiceDiscardCatalog.RECORD)
        add(NativeVoiceLocalResults.RECORD)
        add(NativeMigrationProvisionalVoice.RECORD)
        NativeMigrationSelections.legacyNames(read).forEach(::add)
        NativeMigrationPhotoLocals.legacyNames(read).forEach(::add)
        NativeMigrationPrivateClaims.legacyNames(read).forEach(::add)
        add(NativeSelectionReturnAudit.RECORD)
        add(NativeMigrationInbox.RECORD)
        read(NativeMigrationInbox.RECORD)?.let {NativeMigrationInbox.legacyNames(it).forEach(::add)}
        read(NativeMigrationSounds.RECORD)?.let {raw->NativeChatNotificationSoundLibrary.binaryInventory(raw).keys.forEach(::add)}
        val metadata=value("metadata")?:error("Missing metadata");NativeMigrationMetadata.validate(metadata,allowSourceEasyWork=true)
        val keys=mutableSetOf<String>()
        for(field in listOf("drafts","draftVersions","draftFiles","attachmentImportGenerations","selectedThreads","newProjects","generation","generationPreferences","replyModes","dismissedReceiptFailures","expanded","sidebar","workspaces"))
            metadata.optJSONObject(field)?.keys()?.forEach{keys.add(it)}
        val clients=metadata.getJSONArray("clients").objects().map{decodeClient(it).let{client->client.copy(storageIdentity=client.storageIdentity?.withKeys(keys.toList()))}}
        for(client in clients) {
            keys.addAll(client.operationTargets(null));add("token:${client.id}")
            val threads=metadata.optJSONObject("selectedThreads")?.let{map->map.keys().asSequence().map{map.getString(it)}.filter{it.isNotEmpty()}.toList()}.orEmpty()+
                metadata.getJSONObject("workspaces").let{map->map.keys().asSequence().map{MobileSnapshotCodec.workspace(map.getJSONObject(it).toString())}.filter{it.hostId==client.id}.flatMap{it.threads.asSequence()}.map{it.id}.toList()}
            threads.distinct().forEach{keys.addAll(client.operationTargets(it))}
            client.storageIdentity?.json()?.getJSONArray("keys")?.strings()?.forEach{keys.add(it)}
        }
        val fileIds=mutableSetOf<String>()
        fun files(array: JSONArray){array.objects().forEach{fileIds.add(it.getString("id"))}}
        metadata.optJSONObject("draftFiles")?.let{map->map.keys().forEach{files(map.getJSONArray(it))}}
        for(key in keys) {
            val operations="operations:$key";add(operations)
            value(operations)?.getJSONArray("operations")?.objects()?.forEach{row->
                val id=row.getString("id");val source="submission:$id";add(source)
                val intent=StrictMigrationJson.parse(row.getString("intent").toByteArray())
                if(row.getString("kind")=="reply")intent.getJSONArray("attachmentIds").strings().forEach{fileIds.add(it)}
                if(row.getString("kind")=="creation")files(intent.getJSONArray("attachments"))
                value(source)?.let{files(it.getJSONArray("files"))}
            }
            val history="history:$key";add(history)
            value(history)?.takeIf{it.get("version")==2}?.getJSONArray("parts")?.strings()?.forEach(::add)
        }
        val voiceIds=mutableSetOf<String>()
        read("voice-index-v1")?.let {raw->
            val ids=StrictMigrationJson.value(raw.toByteArray(Charsets.UTF_8)) as JSONArray;require(ids.length()<=32)
            for(n in 0 until ids.length()){val id=ids.get(n) as String;require(java.util.UUID.fromString(id).toString()==id);voiceIds.add(id)}
        }
        read(NativeVoiceDiscardCatalog.RECORD)?.let {raw->NativeVoiceDiscardCatalog.decode(raw).values.forEach {marker->
            voiceIds.add(marker.id);add(marker.auditName);if(marker.evidence.kind=="raw")add(marker.rawName)
        }}
        read(NativeVoiceLocalResults.RECORD)?.let {raw->NativeVoiceLocalResults.decode(raw).forEach {voiceIds.add(it.recordingId)}}
        voiceIds.forEach {id->val name="voice-recording:$id";add(name);value(name)?.let {row->
            if(row.optString("audioStorage")=="binary-chunks-v1")add("voice-audio:$id")
        }}
        fileIds.forEach {id->
            val record="draft-file:$id";add(record);add("draft-thumbnail-v1:$id")
            value(record)?.let {body->NativeDraftFiles.record(body).payload?.let(::add)}
        }
        return names
    }
}
