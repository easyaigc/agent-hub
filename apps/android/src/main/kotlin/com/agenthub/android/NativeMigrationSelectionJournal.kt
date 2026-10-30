package com.agenthub.android

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Audit-only source contract. Never returns NativeDocumentRecord, Uri or a callback authority. */
internal object NativeMigrationSelectionJournal {
    const val RECORD="document-requests-v1"
    enum class Kind(val namespace: String,val sourceLimit: Int) { DOCUMENT("document-results",10),PHOTO("photo-results",5),CAMERA("camera-results",1) }
    data class Request(val id: String,val key: String,val generation: String,val selection: Long,val requestId: String,
        val errorGeneration: Long,val phase: String,val sources: List<String>,val releaseReadGrants: List<String>,val maximum: Int)
    data class Original(val raw: String,val version: Int,val revision: Long,val requests: List<Request>)
    private val phases=setOf("permission","prepared","launched","selected","ready","detached","hold-selected","held","consumed","retired","failed")
    private val awaiting=setOf("permission","prepared","launched")
    private val cameraOnly=setOf("permission","detached","hold-selected","held")
    private fun exact(value: JSONObject,keys: Set<String>)=require(value.keys().asSequence().toSet()==keys)
    private fun id(value: String)=value.also {require(UUID.fromString(it).toString()==it)}
    private fun number(value: Any): Long {require(value is Int||value is Long);return (value as Number).toLong()}
    private fun strings(value: JSONArray)=List(value.length()){value.get(it) as String}
    private fun source(value: String) {
        require(value.length<=4096&&value.startsWith("content://")&&'#' !in value)
        // Retain the opaque original string. This shape check never parses/opens a provider.
        require(value.removePrefix("content://").substringBefore('/').substringBefore('?').isNotBlank())
    }
    fun decode(raw: String,kind: Kind): Original {
        val bytes=raw.toByteArray(Charsets.UTF_8);require(bytes.size<=NativeMigrationTextFrames.MAX_RECORD_BYTES)
        val root=StrictMigrationJson.parse(bytes);exact(root,setOf("version","revision","records"))
        val version=root.get("version") as Int;require(version==1||version==2)
        val revision=number(root.get("revision"));require(revision>=0)
        val rows=root.getJSONArray("records");require(rows.length()<=32)
        val requests=List(rows.length()){n->
            val row=rows.getJSONObject(n)
            exact(row,setOf("id","key","generation","selection","requestId","errorGeneration","phase","sources","releaseReadGrants")+if(version==2)setOf("maximum")else emptySet())
            val maximum=if(version==1)10 else row.get("maximum") as Int;require(maximum==5||maximum==10)
            val key=row.get("key") as String;require(key.isNotBlank()&&key.length<=16384)
            val selection=number(row.get("selection"));require(selection>=0)
            val phase=row.get("phase") as String;require(phase in phases&&(kind==Kind.CAMERA||phase !in cameraOnly))
            val sources=strings(row.getJSONArray("sources"));require(sources.size<=minOf(maximum,kind.sourceLimit)&&sources.distinct()==sources);sources.forEach(::source)
            val grants=strings(row.getJSONArray("releaseReadGrants"));require(grants.distinct()==grants&&grants.all {it in sources})
            require(phase !in setOf("selected","ready","hold-selected","held")||sources.isNotEmpty())
            require(phase !in awaiting+"detached"||sources.isEmpty())
            Request(id(row.get("id") as String),key,id(row.get("generation") as String),selection,id(row.get("requestId") as String),
                number(row.get("errorGeneration")),phase,sources,grants,maximum)
        }
        require(requests.map {it.id}.distinct().size==requests.size&&requests.count {it.phase in awaiting}<=1)
        return Original(raw,version,revision,requests)
    }
}
