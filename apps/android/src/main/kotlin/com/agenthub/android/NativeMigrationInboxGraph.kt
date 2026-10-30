package com.agenthub.android

import com.agenthub.core.AttachmentPreparationRules
import org.json.JSONObject
import java.util.UUID

/** Exact original child graph, including known interrupted prepare/consumed-cleanup residue. Never mutates. */
internal object NativeMigrationInboxGraph {
    const val INDEX="external-inbox-index-v1"
    private fun exact(value: JSONObject,names: Set<String>)=require(value.keys().asSequence().toSet()==names)
    private fun id(value: String){require(UUID.fromString(value).toString()==value)}
    private fun file(value: JSONObject): CreationFile {
        CodexCommandCodec.validate(value,"creationFile")
        return CreationFile(value.getString("id"),value.getString("name"),value.getString("mediaType"),value.getLong("sizeBytes"),value.getString("sha256")).also {id(it.id);require(it.sizeBytes in 1..NativeDraftFiles.MAXIMUM_BYTES.toLong())}
    }
    /** Only authenticated indexed aliases can identify legacy ciphertext; modern discovery remains exhaustive. */
    fun legacyNames(read: (String)->String?): Set<String> {
        val names=mutableSetOf(INDEX)
        read(INDEX)?.let {raw->
            val root=StrictMigrationJson.parse(raw.toByteArray(Charsets.UTF_8));val rows=root.getJSONArray("records");require(rows.length()<=128)
            for(batch in rows.objects())for(slot in batch.getJSONArray("slots").objects()) {
                val key=slot.get("id") as String;id(key);names.add("draft-file:$key");names.add("draft-thumbnail-v1:$key")
                read("draft-file:$key")?.let {NativeDraftFiles.record(StrictMigrationJson.parse(it.toByteArray(Charsets.UTF_8))).payload?.let(names::add)}
            }
        }
        return names
    }
    fun validate(text: Map<String,String>,binary: Map<String,NativeMigrationBlob>) {
        if(text.isEmpty()&&binary.isEmpty())return
        val root=StrictMigrationJson.parse(text.getValue(INDEX).toByteArray(Charsets.UTF_8));exact(root,setOf("version","revision","records"))
        val version=root.get("version") as Int;require(version in setOf(1,2))
        val revision=root.get("revision");require((revision is Int||revision is Long)&&(revision as Number).toLong()>=0)
        val batches=root.getJSONArray("records");require(batches.length()<=128)
        val batchIds=mutableSetOf<String>();val slots=linkedMapOf<String,Pair<String,CreationFile?>>()
        for(row in batches.objects()) {
            exact(row,setOf("id","phase","sourceHash","slots","binding")+if(version==2)setOf("maximum")else emptySet())
            val batch=row.get("id") as String;id(batch);require(batchIds.add(batch))
            val phase=row.get("phase") as String;require(phase in setOf("receiving","failed","pending","consumed"))
            require((row.get("sourceHash") as String).matches(Regex("[a-f0-9]{64}")))
            val maximum=if(version==1)10 else row.get("maximum") as Int;require(maximum==5||maximum==10)
            if(!row.isNull("binding")) {
                val binding=row.getJSONObject("binding");exact(binding,setOf("key","generation"))
                for(name in listOf("key","generation"))require((binding.get(name) as String).let {it.length in 1..8192&&!it.contains('\u0000')})
            }
            val values=row.getJSONArray("slots")
            require(phase=="consumed"&&values.length()==0||AttachmentPreparationRules.canReserveWithinLimit(0,0,values.length(),maximum))
            for(slot in values.objects()) {
                exact(slot,setOf("id","file"));val key=slot.get("id") as String;id(key)
                val manifest=if(slot.isNull("file"))null else file(slot.getJSONObject("file"));require(manifest==null||manifest.id==key)
                require(phase!="pending"||manifest!=null);require(slots.put(key,phase to manifest)==null)
            }
        }
        val payloads=mutableMapOf<String,Pair<String,NativeMigrationBlob>>()
        for((name,blob)in binary) {
            val pieces=name.split(':');require(pieces.size==3&&pieces[0]=="draft-payload-v2");id(pieces[1]);id(pieces[2]);require(name==NativeDraftFiles.payloadName(pieces[1],pieces[2]))
            require(pieces[1] in slots&&payloads.put(pieces[1],name to blob)==null)
        }
        val expected=mutableSetOf(INDEX)
        for((key,slot)in slots) {
            val (phase,manifest)=slot;val name="draft-file:$key";val thumb="draft-thumbnail-v1:$key";val raw=text[name];val thumbnail=text[thumb];val payload=payloads[key]
            if(manifest==null){require(raw==null&&thumbnail==null&&payload==null);continue}
            payload?.second?.let {require(it.size.toLong()==manifest.sizeBytes&&it.sha256==manifest.sha256)}
            if(raw!=null) {
                expected.add(name);val body=StrictMigrationJson.parse(raw.toByteArray(Charsets.UTF_8));val record=NativeDraftFiles.record(body);require(record.file==manifest)
                val owned=NativeMigrationAttachments.validate(name,body,emptyMap(),binary)
                require(if(owned==null)payload==null else payload?.first==owned)
            }else require(phase!="pending") // A partial original is evidence, never a reconstructed ready file.
            if(thumbnail!=null) {
                expected.add(thumb);require(manifest.mediaType.startsWith("image/"));DraftAttachmentThumbnails.validate(StrictMigrationJson.parse(thumbnail.toByteArray(Charsets.UTF_8)),manifest)
            }
        }
        require(text.keys==expected)
    }
}
