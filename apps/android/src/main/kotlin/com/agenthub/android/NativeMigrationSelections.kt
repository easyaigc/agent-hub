package com.agenthub.android

import org.json.JSONObject

/** Exact source topology and bytes are inert originals; no request, URI or output is recreated. */
internal object NativeMigrationSelections {
    private const val TOPOLOGY="selection-original-v1"
    private val kinds=NativeMigrationSelectionJournal.Kind.values().toList()
    fun record(kind: NativeMigrationSelectionJournal.Kind)="migration-${kind.namespace}-v1"
    fun textPrefix(kind: NativeMigrationSelectionJournal.Kind)="migration-${kind.namespace}-text-v1:"
    fun binaryPrefix(kind: NativeMigrationSelectionJournal.Kind)="migration-${kind.namespace}-binary-v1:"
    private val archives=kinds.associateWith {kind->NativeMigrationInertArchive(record(kind),textPrefix(kind),binaryPrefix(kind),
        "agent-hub/inert-${kind.namespace}/v1",{text,binary->validateOriginal(kind,text,binary)})}
    private fun exact(value: JSONObject,names: Set<String>)=require(value.keys().asSequence().toSet()==names)
    private fun validateOriginal(kind: NativeMigrationSelectionJournal.Kind,text: Map<String,String>,binary: Map<String,NativeMigrationBlob>) {
        val root=StrictMigrationJson.parse(text.getValue(TOPOLOGY).toByteArray(Charsets.UTF_8))
        exact(root,setOf("version","kind","nodes","raw"));require(root.get("version")==1&&root.get("kind")==kind.namespace)
        val nodes=root.getJSONObject("nodes");val camera=kind==NativeMigrationSelectionJournal.Kind.CAMERA
        exact(nodes,setOf("journal","payloads")+if(camera)setOf("outputs")else emptySet())
        require(text.keys.all {it==TOPOLOGY||it.startsWith("journal:")||it.startsWith("payloads:")||camera&&it.startsWith("outputs:")})
        require(binary.keys.all {it.startsWith("payloads:")})
        fun node(name: String): NativeMigrationStreamRecords? {
            val state=nodes.get(name) as String;require(state in setOf("absent","empty","records"))
            val prefix="$name:";val values=NativeMigrationTextMap.copy(emptyMap())
            for(key in text.keys.filter {it.startsWith(prefix)})values.alias(text,key,key.removePrefix(prefix))
            val bytes=binary.filterKeys {it.startsWith(prefix)}.mapKeys {it.key.removePrefix(prefix)}
            if(state!="records"){require(values.isEmpty()&&bytes.isEmpty());return null}
            require(values.isNotEmpty()||bytes.isNotEmpty());return NativeMigrationStreamRecords(values,bytes,4)
        }
        val journal=node("journal");val payloads=node("payloads")
        if(nodes.getString("journal")=="absent")require(nodes.getString("payloads")=="absent"&&(!camera||nodes.getString("outputs")=="absent"))
        if(camera) {
            val raw=root.getJSONObject("raw");exact(raw,setOf("scope","present"))
            require((raw.get("scope") as String).matches(Regex("[a-f0-9]{32}"))&&raw.get("present") is Boolean)
            // Authenticated claims alone do not prove an external camera writer has stopped.
            require(node("outputs")==null) {"Nonempty camera claims require a proved external writer boundary"}
        }else require(root.isNull("raw"))
        NativeMigrationSelectionGraph.validate(kind,journal,payloads)
    }
    fun append(source: NativeMigrationStreamRecords,selection: NativeMigrationSelectionSource): NativeMigrationStreamRecords {
        val text=NativeMigrationTextMap.copy(emptyMap());val binary=linkedMapOf<String,NativeMigrationBlob>();val nodes=JSONObject()
        fun capture(name: String,node: NativeMigrationSelectionSourceNode) {
            nodes.put(name,if(!node.present)"absent"else if(node.records==null)"empty"else"records")
            node.records?.let {records->
                for(key in records.text.keys)text.alias(records.text,key,"$name:$key")
                for((key,value)in records.binary)require(binary.put("$name:$key",value)==null)
            }
        }
        capture("journal",selection.journal);capture("payloads",selection.payloads)
        selection.outputs?.let {capture("outputs",it)}
        val raw=selection.raw?.let {JSONObject().put("scope",it.targetScope).put("present",it.present)}?:JSONObject.NULL
        text[TOPOLOGY]=JSONObject().put("version",1).put("kind",selection.kind.namespace).put("nodes",nodes).put("raw",raw).toString()
        return archives.getValue(selection.kind).append(source,NativeMigrationStreamRecords(text,binary,4))
    }
    fun legacyNames(read: (String)->String?): Set<String> = kinds.flatMap {kind->
        listOf(record(kind))+read(record(kind))?.let {archives.getValue(kind).legacyNames(it)}.orEmpty()
    }.toSet()
    fun validate(text: Map<String,String>,binary: Map<String,NativeMigrationBlob>): Pair<Set<String>,Set<String>> {
        val graphs=kinds.map {kind->archives.getValue(kind).validate(text,binary.filterKeys {it.startsWith(binaryPrefix(kind))})}
        return graphs.flatMap {it.textNames}.toSet() to graphs.flatMap {it.binaryNames}.toSet()
    }
    fun merge(source: NativeMigrationStreamRecords,destination: NativeMigrationStreamRecords): NativeMigrationStreamRecords {
        var result=source
        for(kind in kinds)result=archives.getValue(kind).merge(result,destination)
        return result
    }
    fun read(storage: NativeSessionStorage,kind: NativeMigrationSelectionJournal.Kind)=archives.getValue(kind).read(storage)
    fun text(storage: NativeSessionStorage,kind: NativeMigrationSelectionJournal.Kind,original: NativeMigrationInertArchive.Original,index: Int)=archives.getValue(kind).text(storage,original,index)
    fun copy(storage: NativeSessionStorage,kind: NativeMigrationSelectionJournal.Kind,original: NativeMigrationInertArchive.Original,index: Int,output: java.io.OutputStream)=archives.getValue(kind).copy(storage,original,index,output)
}
