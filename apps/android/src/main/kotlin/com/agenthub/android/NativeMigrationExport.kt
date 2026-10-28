package com.agenthub.android

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Internal export boundary. Unsupported records reject the whole archive; no best-effort backup. */
internal class NativeMigrationExport(private val context: Context,private val storage: NativeSessionStorage) {
    fun seal(password: String): ByteArray {
        require(NativeMigrationCrypto.acceptsPassword(password))
        val clear=capture()
        try{return NativeMigrationCrypto.seal(clear,password)}finally{clear.fill(0)}
    }
    internal fun capture(): ByteArray {
        val records=storage.migrationArchiveSnapshot(::legacyNames)
        val clear=JSONObject().put("format","agent-hub/android-migration").put("version",1).put("applicationId",context.packageName)
            .put("records",JSONObject(records)).toString().toByteArray(Charsets.UTF_8)
        try {
            require(clear.size<=NativeMigrationCandidateStore.MAX_PAYLOAD_BYTES)
            NativeMigrationCandidateStore(context,storage).validate(clear)
            return clear
        }catch(error: Exception){clear.fill(0);throw error}
    }
    private fun legacyNames(read: (String)->String?): Set<String> {
        val names=mutableSetOf("metadata","codex-private-connections-v1","ai-sharing-v1")
        fun add(name: String){require(name.length<=8192&&!name.contains('\u0000'));names.add(name);require(names.size<=1024)}
        fun value(name: String)=read(name)?.let{StrictMigrationJson.parse(it.toByteArray(Charsets.UTF_8))}
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
        fileIds.forEach{add("draft-file:$it");add("draft-thumbnail-v1:$it")}
        return names
    }
}
