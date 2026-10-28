package com.agenthub.android

import android.content.Context
import org.json.JSONObject
import java.util.UUID

/** Internal activation boundary. UI must retire the old session and restore a newly opened handle. */
internal class NativeMigrationActivation(private val context: Context,private val storage: NativeSessionStorage) {
    internal class ExistingInstallation: Exception("请在尚未添加真实电脑的新安装中导入迁移文件，现有连接未更改。")
    fun activate(receipt: NativeMigrationCandidateStore.Receipt,checkpoint: (Int)->Unit={}): String=storage.operationTransaction {
        storage.publishedMigration(receipt.id,receipt.sha256,receipt.recordCount)?.let{return@operationTransaction it}
        // The authoritative Vault also covers pairing committed before an ordinary metadata snapshot.
        val metadata=storage.metadata()
        if(metadata?.getJSONArray("clients")?.length()?.let{it>0}==true||CodexConnectionVault(storage).clients().isNotEmpty())throw ExistingInstallation()
        val stream=NativeMigrationStreamCandidate(context,storage)
        if(stream.isStream(receipt))return@operationTransaction stream.read(receipt) { records ->
            storage.publishPreparedStreamMigration(receipt.id,receipt.sha256,NativeMigrationStreamRecords(prepare(receipt,records.text),records.binary),receipt.recordCount,checkpoint)
        }
        val records=NativeMigrationCandidateStore(context,storage).readArchive(receipt)
        storage.publishPreparedMigrationRecords(receipt.id,receipt.sha256,NativeMigrationRecords(prepare(receipt,records.text),records.binary),receipt.recordCount,checkpoint)
    }
    private fun prepare(receipt: NativeMigrationCandidateStore.Receipt,original: Map<String,String>): Map<String,String> {
        val records=original.toMutableMap();val metadata=JSONObject(records.getValue("metadata"))
        val clients=metadata.getJSONArray("clients").objects()
        val readers=clients.associate {row->val id=row.getString("id");id to UUID.nameUUIDFromBytes(("android-migration-reader-v1\u0000"+receipt.id+"\u0000"+id).toByteArray(Charsets.UTF_8)).toString()}
        clients.forEach {it.put("readerId",readers.getValue(it.getString("id")))}
        metadata.remove("photoSources");metadata.remove("attachmentImportGenerations")
        records["metadata"]=metadata.toString()
        if("ai-sharing-v1" in records)records["ai-sharing-v1"]=JSONObject().put("version",1).put("grants",org.json.JSONArray()).toString()
        records["codex-private-connections-v1"]?.let {raw->
            val vault=JSONObject(raw);vault.getJSONArray("connections").objects().forEach {row->val client=row.getJSONObject("client");client.put("readerId",readers.getValue(client.getString("id")))}
            records["codex-private-connections-v1"]=vault.toString()
        }
        for((name,raw)in original)when {
            name.startsWith("voice-recording:") -> records[name]=JSONObject(raw).put("migrationReadOnly",true).toString()
            name.startsWith("submission:") -> records[name]=JSONObject(raw).apply{remove("photoSources")}.toString()
            name.startsWith("operations:") -> {
                val body=JSONObject(raw)
                body.getJSONArray("operations").objects().forEach {row->
                    row.put("migrationReadOnly",true)
                    row.optJSONObject("replyRecovery")?.put("phase","uncertain")?.put("inspection",JSONObject.NULL)
                }
                records[name]=body.toString()
            }
        }
        // Archived AI grants are audit material, never authority on the new installation.
        // OS/device callbacks are not accepted; generation-scoped children start empty.
        // Immutable intent/result strings stay exact.
        NativeMigrationDeviceState.prepare(receipt,original,records)
        return records
    }
}
