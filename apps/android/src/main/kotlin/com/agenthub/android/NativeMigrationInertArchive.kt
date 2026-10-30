package com.agenthub.android

import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStream
import java.security.MessageDigest

/** One closed, content-addressed archive engine. No domain objects or execution are restored. */
internal class NativeMigrationInertArchive(val record: String,val textPrefix: String,val binaryPrefix: String,
    private val domain: String,private val validateOriginal: (Map<String,String>,Map<String,NativeMigrationBlob>)->Unit) {
    private val MAX_ARCHIVES=128
    private val MAX_ENTRIES=512
    private val MAX_CATALOG_BYTES=1024*1024
    data class Entry(val name: String,val kind: String,val size: Int,val sha256: String) {
        fun json()=JSONObject().put("name",name).put("kind",kind).put("size",size).put("sha256",sha256)
    }
    data class Original(val id: String,val entries: List<Entry>,private val textPrefix: String,private val binaryPrefix: String) {
        fun json()=JSONObject().put("id",id).put("entries",JSONArray(entries.map {it.json()}))
        fun alias(index: Int)=(if(entries[index].kind=="text")textPrefix else binaryPrefix)+id+":"+index
    }
    data class Graph(val originals: List<Original>,val textNames: Set<String>,val binaryNames: Set<String>)
    private fun exact(value: JSONObject,names: Set<String>)=require(value.keys().asSequence().toSet()==names)
    private fun digest(value: String)=require(value.matches(Regex("[a-f0-9]{64}")))
    companion object { fun sha(bytes: ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)} }
    /** Domain + length-prefixed names, kind, exact size and complete content digest; independent of JSON spelling. */
    internal fun identity(entries: List<Entry>): String {
        val hash=MessageDigest.getInstance("SHA-256");hash.update((domain+"\u0000").toByteArray(Charsets.UTF_8))
        fun bytes(value: ByteArray){hash.update(java.nio.ByteBuffer.allocate(4).putInt(value.size).array());hash.update(value)}
        for(entry in entries){bytes(entry.name.toByteArray(Charsets.UTF_8));bytes(entry.kind.toByteArray(Charsets.UTF_8));hash.update(java.nio.ByteBuffer.allocate(4).putInt(entry.size).array());bytes(entry.sha256.toByteArray(Charsets.US_ASCII))}
        return hash.digest().joinToString(""){"%02x".format(it)}
    }
    private fun decode(raw: String?): List<Original> {
        if(raw==null)return emptyList()
        require(raw.toByteArray(Charsets.UTF_8).size<=MAX_CATALOG_BYTES)
        val root=StrictMigrationJson.parse(raw.toByteArray(Charsets.UTF_8));exact(root,setOf("version","archives"));require(root.get("version")==1)
        val archives=root.getJSONArray("archives");require(archives.length() in 1..MAX_ARCHIVES)
        return (0 until archives.length()).map {n->
            val body=archives.getJSONObject(n);exact(body,setOf("id","entries"));val id=body.get("id") as String;digest(id)
            val rows=body.getJSONArray("entries");require(rows.length()<=MAX_ENTRIES)
            val entries=(0 until rows.length()).map {i->
                val row=rows.getJSONObject(i);exact(row,setOf("name","kind","size","sha256"))
                val name=row.get("name") as String;require(name.length in 1..8192&&!name.contains('\u0000'))
                val kind=row.get("kind") as String;val size=row.get("size") as Int;val sha=row.get("sha256") as String;digest(sha)
                require(if(kind=="text")size in 1..NativeMigrationTextFrames.MAX_RECORD_BYTES else kind=="binary"&&size in 1..NativeDraftFiles.MAXIMUM_BYTES)
                Entry(name,kind,size,sha)
            }
            require(entries.map {it.name}==entries.map {it.name}.distinct().sorted()&&identity(entries)==id)
            Original(id,entries,textPrefix,binaryPrefix)
        }.also {rows->require(rows.map {it.id}==rows.map {it.id}.distinct().sorted()&&rows.sumOf {it.entries.size}<=1022)}
    }
    fun legacyNames(raw: String): Set<String> = decode(raw).flatMap {original->original.entries.indices.map(original::alias)}.toSet()
    private fun frames(records: Map<String,String>)=if(records is NativeMigrationTextMap)records.frames()else records.mapValues {NativeMigrationTextFrames.retained(it.value)}
    fun validate(records: Map<String,String>,binary: Map<String,NativeMigrationBlob>): Graph {
        if(records is NativeMigrationTextMap&&record in records)require(records.frame(record).size<=MAX_CATALOG_BYTES)
        val originals=decode(records[record]);val expectedText=mutableSetOf<String>();val expectedBinary=mutableSetOf<String>();val sourceFrames=frames(records)
        for(original in originals) {
            val text=linkedMapOf<String,NativeMigrationBlob>();val payloads=linkedMapOf<String,NativeMigrationBlob>()
            original.entries.forEachIndexed {index,entry->
                val alias=original.alias(index)
                val blob=if(entry.kind=="text")sourceFrames.getValue(alias).also {require(expectedText.add(alias))}else binary.getValue(alias).also {require(expectedBinary.add(alias))}
                require(blob.size==entry.size&&blob.sha256==entry.sha256)
                if(entry.kind=="text")text[entry.name]=blob else payloads[entry.name]=blob
            }
            validateOriginal(NativeMigrationTextMap(text),payloads)
        }
        require(records.keys.filter {it.startsWith(textPrefix)||it.startsWith(binaryPrefix)}.toSet()==expectedText&&binary.keys==expectedBinary)
        return Graph(originals,expectedText+if(originals.isEmpty())emptySet()else setOf(record),expectedBinary)
    }
    fun append(source: NativeMigrationStreamRecords,child: NativeMigrationStreamRecords?): NativeMigrationStreamRecords {
        val old=validate(source.text,source.binary.filterKeys {it.startsWith(binaryPrefix)})
        val entries=if(child==null)emptyList()else {
            validateOriginal(child.text,child.binary)
            (child.textFrames().map {Entry(it.key,"text",it.value.size,it.value.sha256)}+child.binary.map {Entry(it.key,"binary",it.value.size,it.value.sha256)}).sortedBy {it.name}
        }
        require(entries.size<=MAX_ENTRIES);val original=Original(identity(entries),entries,textPrefix,binaryPrefix)
        val previous=old.originals.firstOrNull {it.id==original.id}
        if(previous!=null){require(previous==original);return source}
        require(old.originals.size<MAX_ARCHIVES)
        val text=NativeMigrationTextMap.copy(source.text);val binary=LinkedHashMap(source.binary)
        if(child!=null)entries.forEachIndexed {index,entry->
            if(entry.kind=="text")text.alias(child.text,entry.name,original.alias(index))
            else require(binary.put(original.alias(index),child.binary.getValue(entry.name))==null)
        }
        text[record]=JSONObject().put("version",1).put("archives",JSONArray((old.originals+original).sortedBy {it.id}.map {it.json()})).toString()
        return NativeMigrationStreamRecords(text,binary,source.version).also {validate(it.text,it.binary.filterKeys {name->name.startsWith(binaryPrefix)})}
    }
    /** Union exact originals; content IDs deduplicate only identical descriptors and bytes. */
    fun merge(source: NativeMigrationStreamRecords,destination: NativeMigrationStreamRecords): NativeMigrationStreamRecords {
        val originals=validate(destination.text,destination.binary.filterKeys {it.startsWith(binaryPrefix)}).originals
        var result=source
        for(original in originals) {
            if(original.entries.isEmpty()){result=append(result,null);continue}
            val text=NativeMigrationTextMap.copy(emptyMap());val binary=linkedMapOf<String,NativeMigrationBlob>()
            original.entries.forEachIndexed {index,entry->
                if(entry.kind=="text")text.alias(destination.text,original.alias(index),entry.name)
                else binary[entry.name]=destination.binary.getValue(original.alias(index))
            }
            result=append(result,NativeMigrationStreamRecords(text,binary,4))
        }
        return result
    }
    fun descriptors(binary: Map<String,String>)=binary.mapValues {(_,encoded)->
        val bytes=NativeMigrationRecords.bytes(encoded)
        try {val probe=NativeMigrationProbe();probe.write(bytes);NativeMigrationBlob(bytes.size,probe.sha256(),probe.prefix()){error("Descriptor only")}}
        finally {bytes.fill(0)}
    }
    fun read(storage: NativeSessionStorage): List<Original> = storage.operationTransaction {decode(storage.migrationReadTextRecords(setOf(record))[record])}
    private fun current(storage: NativeSessionStorage,original: Original,index: Int): Entry {
        require(original in read(storage)&&index in original.entries.indices);return original.entries[index]
    }
    fun text(storage: NativeSessionStorage,original: Original,index: Int): String = storage.operationTransaction {
        val entry=current(storage,original,index);require(entry.kind=="text")
        storage.migrationReadTextRecords(setOf(original.alias(index))).getValue(original.alias(index)).also {require(it.toByteArray(Charsets.UTF_8).let {bytes->bytes.size==entry.size&&sha(bytes)==entry.sha256})}
    }
    /** Publishes only after complete frame authentication and this original descriptor match. */
    fun copy(storage: NativeSessionStorage,original: Original,index: Int,output: OutputStream)=storage.operationTransaction {
        val entry=current(storage,original,index);require(entry.kind=="binary")
        val probe=NativeMigrationProbe(output);storage.migrationCopyBinaryTo(original.alias(index),entry.size,probe)
        require(probe.size==entry.size.toLong()&&probe.sha256()==entry.sha256)
    }
}
