package com.agenthub.android

import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

internal data class NativeDocumentRecord(val id: String,val destination: AttachmentImportDestination,val errorGeneration: Long,
    val phase: String,val sources: List<String> = emptyList(),val releaseReadGrants: List<String> = emptyList()) {
    val awaitingResult get()=phase in listOf("permission","prepared","launched")
    val terminal get()=phase in listOf("consumed","retired")
}

/** Encrypted local selection intent only. A restored request is never launched again. */
internal class NativeDocumentJournal(private val storage: NativeSessionStorage) {
    private val name="document-requests-v1"
    private data class Snapshot(val revision: Long,val records: List<NativeDocumentRecord>)
    private fun uuid(value: String)=value.also { require(UUID.fromString(it).toString()==it) }
    private fun strings(rows: JSONArray)= (0 until rows.length()).map { rows.getString(it) }
    private fun decode(): Snapshot {
        val raw=storage.read(name) ?: return Snapshot(0,emptyList())
        val root=JSONObject(raw);require(root.keys().asSequence().toSet()==setOf("version","revision","records")&&root.get("version") in listOf(1,2))
        val revision=root.getLong("revision");require(revision>=0)
        val rows=root.getJSONArray("records");require(rows.length()<=32)
        val records=(0 until rows.length()).map { index ->
            val value=rows.getJSONObject(index)
            val version=root.get("version") as Int
            val expected=setOf("id","key","generation","selection","requestId","errorGeneration","phase","sources","releaseReadGrants")+if(version==2)setOf("maximum")else emptySet()
            require(value.keys().asSequence().toSet()==expected)
            val maximum=if(version==1)10 else (value.get("maximum") as Int).also {require(it==5||it==10)}
            val id=uuid(value.getString("id"));val key=value.getString("key");require(key.isNotBlank()&&key.length<=16384)
            val generation=uuid(value.getString("generation"));val request=uuid(value.getString("requestId"))
            val selection=value.getLong("selection");require(selection>=0)
            val phase=value.getString("phase");require(phase in PHASES)
            val sources=strings(value.getJSONArray("sources"));require(sources.size<=maximum&&sources.distinct()==sources)
            sources.forEach { source -> val uri=android.net.Uri.parse(source);require(source.length<=4096&&uri.scheme=="content"&&!uri.authority.isNullOrBlank()&&uri.fragment==null) }
            val grants=strings(value.getJSONArray("releaseReadGrants"));require(grants.distinct()==grants&&grants.all { it in sources })
            require(phase !in listOf("selected","ready","hold-selected","held")||sources.isNotEmpty())
            require(phase !in listOf("permission","prepared","launched","detached")||sources.isEmpty())
            NativeDocumentRecord(id,AttachmentImportDestination(key,generation,DraftDestination(key,selection),request,maximum),value.getLong("errorGeneration"),phase,sources,grants)
        }
        require(records.map { it.id }.distinct().size==records.size)
        require(records.count { it.awaitingResult }<=1)
        return Snapshot(revision,records)
    }
    private fun write(before: Snapshot,records: List<NativeDocumentRecord>) {
        require(before.revision<Long.MAX_VALUE&&records.size<=32)
        val rows=records.map { record -> JSONObject().put("id",record.id).put("key",record.destination.key).put("generation",record.destination.generation)
            .put("maximum",record.destination.maximum).put("selection",record.destination.presentation.selection).put("requestId",record.destination.requestId).put("errorGeneration",record.errorGeneration)
            .put("phase",record.phase).put("sources",JSONArray(record.sources)).put("releaseReadGrants",JSONArray(record.releaseReadGrants)) }
        storage.write(name,JSONObject().put("version",2).put("revision",before.revision+1).put("records",JSONArray(rows)).toString())
    }
    suspend fun restore(): List<NativeDocumentRecord> {
        val generation=storage.beginRestore()
        return withContext(Dispatchers.IO) {
            val value=storage.operationTransaction { decode() }
            storage.finishRestore(generation) { };value.records
        }
    }
    suspend fun records(): List<NativeDocumentRecord> = withContext(Dispatchers.IO) { storage.operationTransaction { decode().records } }
    suspend fun prepare(original: NativePhotoRequest,phase: String="prepared"): NativeDocumentRecord = withContext(Dispatchers.IO) {
        storage.operationTransaction {
            require(phase in listOf("permission","prepared"))
            val before=decode();require(before.records.none { it.awaitingResult })
            require(before.records.none { it.id==original.id })
            val retained=before.records.toMutableList()
            // Only closed local receipts expire. Unresolved intents and selected bytes are retained.
            while(retained.size>=32) {
                val index=retained.indexOfFirst { it.terminal };require(index>=0) { "本地文件选择记录已满，原记录已保留" };retained.removeAt(index)
            }
            NativeDocumentRecord(original.id,original.destination,original.errorGeneration,phase).also { write(before,retained+it) }
        }
    }
    suspend fun update(id: String,change: (NativeDocumentRecord)->NativeDocumentRecord): NativeDocumentRecord = withContext(Dispatchers.IO) {
        storage.operationTransaction {
            val before=decode();val old=before.records.single { it.id==id };val next=change(old)
            require(next.id==old.id&&next.destination==old.destination&&next.errorGeneration==old.errorGeneration&&next.phase in PHASES)
            require(old.sources.isEmpty()||next.sources==old.sources)
            require(next.sources.distinct()==next.sources&&next.sources.size<=next.destination.maximum&&next.releaseReadGrants.all { it in next.sources })
            require(next.phase !in listOf("permission","prepared","launched","detached")||next.sources.isEmpty())
            require(next.phase !in listOf("selected","ready","hold-selected","held")||next.sources.isNotEmpty())
            if(next!=old)write(before,before.records.map { if(it.id==id)next else it });next
        }
    }
    companion object { private val PHASES=setOf("permission","prepared","launched","selected","ready","detached","hold-selected","held","consumed","retired","failed") }
}
