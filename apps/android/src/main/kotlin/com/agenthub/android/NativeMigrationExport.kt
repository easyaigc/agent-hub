package com.agenthub.android

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Internal export boundary. Unsupported records reject the whole archive; no best-effort backup. */
internal class NativeMigrationExport(private val context: Context,private val storage: NativeSessionStorage) {
    internal class IncompleteScope(val scopes: Set<String>): IllegalStateException("本机迁移包含尚未支持的资料，原数据未更改。")
    private fun requireSupportedScopes() {
        val scopes=storage.migrationPendingChildScopes().toMutableSet()
        if(VoiceRecordingStore(context,storage).migrationProvisionalIds().isNotEmpty())scopes.add("provisional-voice")
        if(scopes.isNotEmpty())throw IncompleteScope(scopes)
    }
    /** Encrypted file publication only; no plaintext scratch or whole binary/Base64 allocation. */
    fun sealFile(destination: java.io.File, password: String) {
        require(NativeMigrationCrypto.acceptsPassword(password))
        require(!destination.canonicalPath.startsWith(context.noBackupFilesDir.canonicalPath + java.io.File.separator))
        storage.migrationStreamSnapshot(::legacyNames) { records ->
            requireSupportedScopes()
            val portable=records.copy(text=NativeMigrationForeground.projectSource(records.text))
            NativeMigrationCandidateStore(context, storage).validateStreamRecords(portable)
            val atomic = android.util.AtomicFile(destination); val output = atomic.startWrite()
            try {
                NativeMigrationStreamCrypto.write(output, password) { NativeMigrationStreamArchive.write(it, portable, context.packageName) }
                requireSupportedScopes()
                atomic.finishWrite(output)
            } catch (failure: Throwable) { atomic.failWrite(output); throw failure }
        }
    }
    fun seal(password: String): ByteArray {
        require(NativeMigrationCrypto.acceptsPassword(password))
        val clear=capture()
        try{return NativeMigrationCrypto.seal(clear,password)}finally{clear.fill(0)}
    }
    internal fun capture(): ByteArray {
        val original=storage.migrationTypedArchiveSnapshot(::legacyNames)
        val records=original.copy(text=NativeMigrationForeground.projectSource(original.text))
        val clear=records.json(context.packageName).toString().toByteArray(Charsets.UTF_8)
        try {
            require(clear.size<=NativeMigrationCandidateStore.MAX_PAYLOAD_BYTES)
            NativeMigrationCandidateStore(context,storage).validateArchive(clear)
            return clear
        }catch(error: Exception){clear.fill(0);throw error}
    }
    private fun legacyNames(read: (String)->String?): Set<String> {
        val names=mutableSetOf("metadata","codex-private-connections-v1","ai-sharing-v1","voice-index-v1",NativeMigrationDeviceState.READING,NativeMigrationDeviceState.NOTIFICATIONS,NativeMigrationDeviceState.AUDIT,NativeMigrationNotificationTombstones.RECORD,NativeMigrationSounds.RECORD)
        fun add(name: String){require(name.length<=8192&&!name.contains('\u0000'));names.add(name);require(names.size<=1024)}
        fun value(name: String)=read(name)?.let{StrictMigrationJson.parse(it.toByteArray(Charsets.UTF_8))}
        add(NativeMigrationForeground.IDENTITY);add(NativeMigrationForeground.JOURNAL)
        add(NativeVoicePreferenceRecord.RECORD)
        read(NativeMigrationSounds.RECORD)?.let {raw->NativeChatNotificationSoundLibrary.binaryInventory(raw).keys.forEach(::add)}
        val metadata=value("metadata")?:error("Missing metadata");NativeMigrationMetadata.validate(metadata)
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
        read("voice-index-v1")?.let {raw->
            val ids=StrictMigrationJson.value(raw.toByteArray(Charsets.UTF_8)) as JSONArray;require(ids.length()<=32)
            for(n in 0 until ids.length()){val id=ids.get(n) as String;require(java.util.UUID.fromString(id).toString()==id);add("voice-recording:$id");add("voice-audio:$id")}
        }
        fileIds.forEach {id->
            val record="draft-file:$id";add(record);add("draft-thumbnail-v1:$id")
            value(record)?.let {body->NativeDraftFiles.record(body).payload?.let(::add)}
        }
        return names
    }
}
