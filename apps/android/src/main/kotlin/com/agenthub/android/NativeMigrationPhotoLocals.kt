package com.agenthub.android

import org.json.JSONObject

internal data class NativeMigrationPhotoLocalSource(val kind: NativeMigrationPhotoLocalKind,val key: String,val present: Boolean,
    val records: NativeMigrationStreamRecords?,val unchanged: ()->Unit)

/** Exact device-local bookkeeping as inert audit; never applied to a new library or permission actor. */
internal object NativeMigrationPhotoLocals {
    private const val RECENTS="preferences"
    private const val EXTERNAL="android.permission.READ_EXTERNAL_STORAGE"
    private const val IMAGES="android.permission.READ_MEDIA_IMAGES"
    private const val SELECTED="android.permission.READ_MEDIA_VISUAL_USER_SELECTED"
    private val permissions=mapOf("attempt-external" to listOf(EXTERNAL),"attempt-images" to listOf(IMAGES),"attempt-selected" to listOf(IMAGES,SELECTED).sorted())
    private val kinds=NativeMigrationPhotoLocalKind.values().toList()
    fun names(kind: NativeMigrationPhotoLocalKind)=if(kind==NativeMigrationPhotoLocalKind.RECENTS)setOf(RECENTS)else permissions.keys
    fun record(kind: NativeMigrationPhotoLocalKind)="migration-${kind.namespace}-v1"
    fun textPrefix(kind: NativeMigrationPhotoLocalKind)="migration-${kind.namespace}-text-v1:"
    fun binaryPrefix(kind: NativeMigrationPhotoLocalKind)="migration-${kind.namespace}-binary-v1:"
    private val archives=kinds.associateWith {kind->NativeMigrationInertArchive(record(kind),textPrefix(kind),binaryPrefix(kind),
        "agent-hub/inert-${kind.namespace}/v1",{text,binary->validateOriginal(kind,text,binary)})}
    fun validateOriginal(kind: NativeMigrationPhotoLocalKind,text: Map<String,String>,binary: Map<String,NativeMigrationBlob>) {
        require(binary.isEmpty()&&text.keys.all {it in names(kind)})
        for((name,raw)in text) {
            val root=StrictMigrationJson.parse(raw.toByteArray(Charsets.UTF_8));require(root.get("version")==1)
            if(kind==NativeMigrationPhotoLocalKind.RECENTS) {
                require(root.keys().asSequence().toSet()==setOf("version","ignored"))
                val rows=root.getJSONArray("ignored");require(rows.length()<=256)
                val ignored=List(rows.length()){rows.get(it) as String}
                require(ignored.distinct()==ignored&&ignored.all {it.matches(Regex("content://media/external/images/media/[0-9]{1,20}"))})
            }else {
                require(root.keys().asSequence().toSet()==setOf("version","permissions","attempted")&&root.get("attempted")==true)
                val rows=root.getJSONArray("permissions");require(rows.length()<=2)
                require(List(rows.length()){rows.get(it) as String}==permissions.getValue(name))
            }
        }
    }
    fun append(source: NativeMigrationStreamRecords,child: NativeMigrationPhotoLocalSource): NativeMigrationStreamRecords {require(child.present);return archives.getValue(child.kind).append(source,child.records)}
    fun legacyNames(read: (String)->String?): Set<String> = kinds.flatMap {kind->listOf(record(kind))+read(record(kind))?.let {archives.getValue(kind).legacyNames(it)}.orEmpty()}.toSet()
    fun validate(text: Map<String,String>,binary: Map<String,NativeMigrationBlob>): Set<String> = kinds.flatMap {kind->
        val graph=archives.getValue(kind).validate(text,binary.filterKeys {it.startsWith(binaryPrefix(kind))});require(graph.binaryNames.isEmpty());graph.textNames
    }.toSet()
    fun merge(source: NativeMigrationStreamRecords,destination: NativeMigrationStreamRecords): NativeMigrationStreamRecords {
        var result=source;for(kind in kinds)result=archives.getValue(kind).merge(result,destination);return result
    }
    fun read(storage: NativeSessionStorage,kind: NativeMigrationPhotoLocalKind)=archives.getValue(kind).read(storage)
    fun text(storage: NativeSessionStorage,kind: NativeMigrationPhotoLocalKind,original: NativeMigrationInertArchive.Original,index: Int)=archives.getValue(kind).text(storage,original,index)
}
