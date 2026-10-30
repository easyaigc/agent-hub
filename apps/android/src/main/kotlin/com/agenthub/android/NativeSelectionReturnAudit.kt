package com.agenthub.android

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Immutable native-return receipts. Reading or importing this audit never dispatches a callback. */
internal object NativeSelectionReturnAudit {
    const val RECORD="migration-selection-return-audit-v1"
    private const val MAX_EVENTS=128
    class CaptureLimit(val boundary: String): IllegalArgumentException("Original selection return exceeds $boundary audit boundary")
    data class Event(val raw: String,val eventId: String,val origin: String,val kind: NativeMigrationSelectionJournal.Kind,
        val requestId: String,val sources: List<String>?,val permission: Boolean?)
    data class Admission(val raw: String,val acceptsOriginalReturn: Boolean)
    private fun exact(value: JSONObject,names: Set<String>)=require(value.keys().asSequence().toSet()==names)
    private fun identity(origin: String,kind: NativeMigrationSelectionJournal.Kind,id: String,permission: Boolean)=
        NativeMigrationInertArchive.sha(("agent-hub/native-selection-return/v1\u0000$origin\u0000${kind.namespace}\u0000$id\u0000$permission").toByteArray(Charsets.UTF_8))
    fun capture(ownerKey: String,kind: NativeMigrationSelectionJournal.Kind,id: String,request: NativeDocumentRecord?,sources: List<String>?=null,permission: Boolean?=null): Event {
        require((sources==null)!=(permission==null));require(UUID.fromString(id).toString()==id)
        if(sources!=null&&sources.size>8192)throw CaptureLimit("source-count")
        var bytes=0L
        for(source in sources.orEmpty()) {
            var index=0
            while(index<source.length) {
                val c=source[index++]
                bytes+=when {
                    c.code<0x80->1
                    c.code<0x800->2
                    Character.isHighSurrogate(c)->{require(index<source.length&&Character.isLowSurrogate(source[index++]));4}
                    Character.isLowSurrogate(c)->error("Unrepresentable original return string")
                    else->3
                }
                if(bytes>NativeMigrationTextFrames.MAX_RECORD_BYTES)throw CaptureLimit("source-bytes")
            }
        }
        val origin=NativeMigrationInertArchive.sha(ownerKey.toByteArray(Charsets.UTF_8))
        val target=request?.let {row->
            require(row.id==id)
            JSONObject().put("id",row.id).put("key",row.destination.key).put("generation",row.destination.generation)
                .put("selection",row.destination.presentation.selection).put("requestId",row.destination.requestId).put("maximum",row.destination.maximum)
                .put("errorGeneration",row.errorGeneration).put("phase",row.phase).put("sources",JSONArray(row.sources)).put("releaseReadGrants",JSONArray(row.releaseReadGrants))
        }?:JSONObject.NULL
        return decode(JSONObject().put("version",1).put("eventId",identity(origin,kind,id,permission!=null)).put("origin",origin).put("kind",kind.namespace)
            .put("requestId",id).put("request",target).put("sources",sources?.let(::JSONArray)?:JSONObject.NULL).put("permission",permission?:JSONObject.NULL).toString())
    }
    fun decode(raw: String): Event {
        require(raw.toByteArray(Charsets.UTF_8).size<=NativeMigrationTextFrames.MAX_RECORD_BYTES)
        val value=StrictMigrationJson.parse(raw.toByteArray(Charsets.UTF_8));exact(value,setOf("version","eventId","origin","kind","requestId","request","sources","permission"))
        require(value.get("version")==1)
        val kind=NativeMigrationSelectionJournal.Kind.values().single {it.namespace==value.get("kind")}
        val origin=value.get("origin") as String;require(origin.matches(Regex("[a-f0-9]{64}")))
        val id=value.get("requestId") as String;require(UUID.fromString(id).toString()==id)
        val sources=if(value.isNull("sources"))null else value.getJSONArray("sources").let {rows->
            require(rows.length()<=8192);List(rows.length()){rows.get(it) as String}
        }
        val permission=if(value.isNull("permission"))null else value.get("permission") as Boolean
        require((sources==null)!=(permission==null)&& (permission==null||kind==NativeMigrationSelectionJournal.Kind.CAMERA))
        // Return arguments are opaque, including malformed/over-limit picker input. No provider is opened.
        if(!value.isNull("request")) {
            val journal=JSONObject().put("version",2).put("revision",0).put("records",JSONArray().put(value.getJSONObject("request"))).toString()
            require(NativeMigrationSelectionJournal.decode(journal,kind).requests.single().id==id)
        }
        val eventId=value.get("eventId") as String;require(eventId==identity(origin,kind,id,permission!=null))
        return Event(raw,eventId,origin,kind,id,sources,permission)
    }
    fun read(raw: String?): List<Event> {
        if(raw==null)return emptyList()
        require(raw.toByteArray(Charsets.UTF_8).size<=NativeMigrationTextFrames.MAX_RECORD_BYTES)
        val root=StrictMigrationJson.parse(raw.toByteArray(Charsets.UTF_8));exact(root,setOf("version","events"));require(root.get("version")==1)
        val rows=root.getJSONArray("events");require(rows.length() in 1..MAX_EVENTS)
        return List(rows.length()){decode(rows.get(it) as String)}.also {events->require(events.map {it.eventId}==events.map {it.eventId}.distinct().sorted())}
    }
    private fun encode(events: List<Event>): String = JSONObject().put("version",1).put("events",JSONArray(events.sortedBy {it.eventId}.map {it.raw})).toString().also {
        require(it.toByteArray(Charsets.UTF_8).size<=NativeMigrationTextFrames.MAX_RECORD_BYTES)
    }
    fun admit(raw: String?,event: Event): Admission {
        require(decode(event.raw)==event)
        val old=read(raw);val previous=old.firstOrNull {it.eventId==event.eventId}
        if(previous!=null)return Admission(requireNotNull(raw),previous.sources==event.sources&&previous.permission==event.permission)
        require(old.size<MAX_EVENTS) {"Original selection return audit is full; return remains pending"}
        return Admission(encode(old+event),true)
    }
    fun validate(records: Map<String,String>): Set<String> = if(RECORD in records){read(records.getValue(RECORD));setOf(RECORD)}else emptySet()
    fun merge(source: Map<String,String>,destination: Map<String,String>): Map<String,String> {
        val old=read(destination[RECORD]);var result=source[RECORD];read(result)
        for(event in old) {
            read(result).firstOrNull {it.eventId==event.eventId}?.let {require(it.raw==event.raw) {"Conflicting immutable selection return receipts retained"}}
            val admission=admit(result,event);require(admission.acceptsOriginalReturn);result=admission.raw
        }
        return if(result==null)source else NativeMigrationTextMap.copy(source).also {it[RECORD]=result!!}
    }
}
