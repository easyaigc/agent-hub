package com.agenthub.android

import android.content.Context
import org.json.JSONObject
import java.util.UUID

/** Internal activation boundary. UI must retire the old session and restore a newly opened handle. */
internal class NativeMigrationActivation(private val context: Context,private val storage: NativeSessionStorage,
    private val notificationLease: NativeMigrationNotificationActivationLease?=null) {
    internal class ExistingInstallation: Exception("请在尚未添加真实电脑的新安装中导入迁移文件，现有连接未更改。")
    fun activate(receipt: NativeMigrationCandidateStore.Receipt,checkpoint: (Int)->Unit={}): String=storage.operationTransaction {
        storage.publishedMigration(receipt.id,receipt.sha256,receipt.recordCount)?.let{return@operationTransaction it}
        // The authoritative Vault also covers pairing committed before an ordinary metadata snapshot.
        val metadata=storage.metadata()
        if(metadata?.getJSONArray("clients")?.length()?.let{it>0}==true||CodexConnectionVault(storage).clients().isNotEmpty())throw ExistingInstallation()
        val destination=NativeMigrationForeground.preserveDestination(storage)
        val localRecords=NativeVoicePreferenceRecord.preserveDestination(storage)
        val easyWorkDestination=NativeMigrationEasyWorkArchives.captureDestination(storage)
        val easyWorkBusinessDestination=NativeMigrationEasyWorkBusiness.captureDestination(storage)
        fun guarded(original: Map<String,String>): (Int)->Unit {
            val required=NativeMigrationNotificationArchives.requiresBoundary(original,destination)||NativeMigrationEasyWorkArchives.RECORD in original||easyWorkDestination.isNotEmpty()||NativeMigrationEasyWorkBusiness.RECORD in original||easyWorkBusinessDestination.isNotEmpty()
            fun checkBoundary(){if(required)requireNotNull(notificationLease){"Notification owner activation lease required"}.requireCurrent(storage,receipt)}
            checkBoundary()
            return {point->if(point==0)checkBoundary();checkpoint(point);if(point==0)checkBoundary()}
        }
        val stream=NativeMigrationStreamCandidate(context,storage)
        if(stream.isStream(receipt))return@operationTransaction stream.read(receipt) { records ->
            withPrepared(receipt,records.text,localRecords,destination,easyWorkDestination,easyWorkBusinessDestination) {prepared->
                storage.publishPreparedStreamMigration(receipt.id,receipt.sha256,NativeMigrationStreamRecords(prepared,records.binary,4),receipt.recordCount,guarded(records.text))
            }
        }
        val records=NativeMigrationCandidateStore(context,storage).readArchive(receipt)
        storage.publishPreparedMigrationRecords(receipt.id,receipt.sha256,NativeMigrationRecords(prepare(receipt,records.text,localRecords,destination,easyWorkDestination,easyWorkBusinessDestination),records.binary),receipt.recordCount,guarded(records.text))
    }
    /** Only transformed values use encrypted scratch cells; root generations receive independent text. */
    private fun <T> withPrepared(receipt: NativeMigrationCandidateStore.Receipt,original: Map<String,String>,localRecords: Map<String,String>,destination: Map<String,String>,easyWorkDestination: Map<String,String>,easyWorkBusinessDestination: Map<String,String>,consume: (Map<String,String>)->T): T {
        val scratch=storage.child(context,"migration-candidates");val prefix="migration-text-transform:"+UUID.randomUUID();val created=mutableListOf<String>()
        try {
            val prepared=prepare(receipt,original,localRecords,destination,easyWorkDestination,easyWorkBusinessDestination) {_,raw->
                val bytes=NativeMigrationTextFrames.bytes(raw);val size=bytes.size;val name="$prefix:${created.size}";created.add(name)
                try {
                    val probe=NativeMigrationProbe();probe.write(bytes)
                    scratch.writeBinaryStream(name,bytes.size){it.write(bytes)}
                    NativeMigrationBlob(size,probe.sha256(),probe.prefix()) {output->scratch.migrationCopyBinaryTo(name,size,output)}
                }finally {bytes.fill(0)}
            }
            return consume(prepared)
        }finally {created.forEach(scratch::remove)}
    }
    private fun prepare(receipt: NativeMigrationCandidateStore.Receipt,original: Map<String,String>,localRecords: Map<String,String>,destination: Map<String,String>,easyWorkDestination: Map<String,String>,easyWorkBusinessDestination: Map<String,String>,persist: ((String,String)->NativeMigrationBlob)?=null): Map<String,String> {
        val records=NativeMigrationTextMap.copy(NativeMigrationEasyWorkBusiness.mergeDestination(NativeMigrationEasyWorkArchives.mergeDestination(NativeMigrationNotificationArchives.mergeDestination(original,destination),easyWorkDestination),easyWorkBusinessDestination),persist);val metadata=JSONObject(records.getValue("metadata"))
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
        for(name in original.keys)when {
            name.startsWith("voice-recording:") -> records[name]=JSONObject(original.getValue(name)).put("migrationReadOnly",true).toString()
            name.startsWith("submission:") -> records[name]=JSONObject(original.getValue(name)).apply{remove("photoSources")}.toString()
            name.startsWith("operations:") -> {
                val body=JSONObject(original.getValue(name))
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
        records.putAll(localRecords)
        return records
    }
}
