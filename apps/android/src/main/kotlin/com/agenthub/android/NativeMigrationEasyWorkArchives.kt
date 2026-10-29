package com.agenthub.android

import org.json.JSONArray
import org.json.JSONObject

/** Inert original authorization evidence. Never pass these aliases to an EasyWork loader. */
internal object NativeMigrationEasyWorkArchives {
    const val RECORD="migration-easywork-archives-v1"
    const val PREFIX="migration-easywork-original-v1:"
    private const val MAX_INSTALLATIONS=16
    private const val MAX_CATALOG_BYTES=512*1024
    private fun hash(raw: String)=NativeChatNotificationWire.hash(raw)
    private fun canonical(value: Any)=easyCanonical(value)
    private fun alias(id: String,name: String)=PREFIX+id+":"+hash(name)
    private fun role(name: String)=when {
        name==NativeMigrationEasyWorkOriginals.IDENTITY->"original-device-identity"
        name.startsWith(NativeMigrationEasyWorkOriginals.CREDENTIAL)->"unreferenced-credential-candidate"
        name.startsWith(NativeMigrationEasyWorkOriginals.GRANT)->"original-device-grant"
        name.startsWith(NativeMigrationEasyWorkOriginals.FENCE)->"original-authorization-fence"
        name.startsWith(NativeMigrationEasyWorkOriginals.CLAIM)->"original-pairing-claim"
        else->"original-oidc-session"
    }
    private fun entries(records: Map<String,String>): Map<String,JSONObject> {
        val raw=records[RECORD]?:return emptyMap();val bytes=raw.toByteArray(Charsets.UTF_8);require(bytes.size<=MAX_CATALOG_BYTES)
        val value=StrictMigrationJson.parse(bytes);NativeChatNotificationWire.keys(value,setOf("version","installations"));require(value.get("version")==1)
        val rows=value.getJSONArray("installations");require(rows.length() in 1..MAX_INSTALLATIONS)
        return rows.objects().associateBy {row->
            NativeChatNotificationWire.keys(row,setOf("archiveId","context","requiresOriginalInstallation","records"));require(row.get("requiresOriginalInstallation")==true)
            val id=row.get("archiveId") as String;require(id.matches(Regex("[a-f0-9]{64}")))
            val body=JSONObject(row.toString()).apply {remove("archiveId")};require(hash(canonical(body))==id);id
        }.also {require(it.size==rows.length())}
    }
    private fun originals(entry: JSONObject): Map<String,JSONObject> {
        val rows=entry.getJSONArray("records");require(rows.length() in 1..1024)
        return rows.objects().associateBy {row->
            NativeChatNotificationWire.keys(row,setOf("name","size","sha256","role"))
            val name=row.get("name") as String;require(name.length in 1..8192&&!name.contains('\u0000')&&NativeMigrationEasyWorkOriginals.owns(name))
            require(row.get("size") is Int&&row.getInt("size") in 1..NativeMigrationTextFrames.MAX_RECORD_BYTES)
            require((row.get("sha256") as String).matches(Regex("[a-f0-9]{64}"))&&row.get("role")==role(name));name
        }.also {require(it.size==rows.length())}
    }
    private fun catalog(rows: Collection<JSONObject>)=canonical(JSONObject().put("version",1).put("installations",JSONArray(rows.sortedBy {it.getString("archiveId")}))).also {require(it.toByteArray(Charsets.UTF_8).size<=MAX_CATALOG_BYTES)}
    private fun frame(records: Map<String,String>,name: String)=if(records is NativeMigrationTextMap)records.frame(name)else NativeMigrationTextFrames.retained(records.getValue(name))
    fun validatePortable(records: Map<String,String>): Set<String> {
        require(records.keys.none(NativeMigrationEasyWorkOriginals::owns)) {"EasyWork originals cannot become current authority"}
        val claimed=mutableSetOf<String>();val rows=entries(records);if(rows.isNotEmpty())claimed.add(RECORD)
        for((id,entry)in rows) {
            val descriptors=originals(entry)
            val aliases=descriptors.keys.associateWith {name->alias(id,name).also {require(claimed.add(it))}}
            descriptors.forEach {(name,row)->val blob=frame(records,aliases.getValue(name));require(blob.size==row.getInt("size")&&blob.sha256==row.getString("sha256"))}
            val originals=NativeMigrationValueMap(descriptors.keys){name->records.getValue(aliases.getValue(name))}
            val context=requireNotNull(NativeMigrationEasyWorkOriginals.validate(originals));require(canonical(context)==canonical(entry.getJSONObject("context")))
        }
        require(records.keys.filter {it==RECORD||it.startsWith(PREFIX)}.all {it in claimed}) {"Orphan EasyWork archive original"}
        return claimed
    }
    fun projectSource(records: Map<String,String>): Map<String,String> {
        val names=records.keys.filter(NativeMigrationEasyWorkOriginals::owns).toSet()
        val result=NativeMigrationTextMap.copy(records).apply {forget(names)};validatePortable(result)
        val context=NativeMigrationEasyWorkOriginals.validate(records)?:return result
        // Raw source business descriptors are validated here; their explicit read-only projection
        // follows at the export boundary before any candidate is accepted.
        records["metadata"]?.let {NativeMigrationMetadata.validate(StrictMigrationJson.parse(it.toByteArray(Charsets.UTF_8)),allowSourceEasyWork=true)}
        val descriptors=JSONArray(names.sorted().map {name->val blob=frame(records,name)
            JSONObject().put("name",name).put("size",blob.size).put("sha256",blob.sha256).put("role",role(name))})
        val body=JSONObject().put("context",context).put("requiresOriginalInstallation",true).put("records",descriptors)
        val id=hash(canonical(body));body.put("archiveId",id)
        val rows=entries(result).toMutableMap();rows[id]?.let {require(canonical(it)==canonical(body))};rows[id]=body;require(rows.size<=MAX_INSTALLATIONS)
        names.forEach {result.alias(records,it,alias(id,it))};result[RECORD]=catalog(rows.values);validatePortable(result);return result
    }
    internal fun sourceArchiveId(records: Map<String,String>): String? {
        val context=NativeMigrationEasyWorkOriginals.validate(records)?:return null
        val descriptors=JSONArray(records.keys.filter(NativeMigrationEasyWorkOriginals::owns).sorted().map {name->val blob=frame(records,name)
            JSONObject().put("name",name).put("size",blob.size).put("sha256",blob.sha256).put("role",role(name))})
        return hash(canonical(JSONObject().put("context",context).put("requiresOriginalInstallation",true).put("records",descriptors)))
    }
    internal fun readOnlyOriginal(records: Map<String,String>,archiveId: String,name: String,digest: String): String {
        val entry=entries(records).getValue(archiveId);val descriptor=originals(entry).getValue(name)
        require(descriptor.get("sha256")==digest)
        val value=frame(records,alias(archiveId,name));require(value.sha256==digest&&value.size==descriptor.getInt("size"))
        return records.getValue(alias(archiveId,name))
    }
    fun mergeDestination(source: Map<String,String>,destination: Map<String,String>): Map<String,String> {
        validatePortable(source)
        val localNames=destination.keys.filter(NativeMigrationEasyWorkOriginals::owns).toSet()
        val localArchives=NativeMigrationTextMap.copy(destination).apply {forget(localNames)};validatePortable(localArchives)
        NativeMigrationEasyWorkOriginals.validate(destination)
        val result=NativeMigrationTextMap.copy(source);val rows=entries(source).toMutableMap()
        for((id,row)in entries(localArchives)) {
            rows[id]?.let {require(canonical(it)==canonical(row))};rows[id]=row
            originals(row).keys.forEach {name->val key=alias(id,name);result.alias(localArchives,key,key)}
        }
        require(rows.size<=MAX_INSTALLATIONS);if(rows.isNotEmpty())result[RECORD]=catalog(rows.values);validatePortable(result)
        localNames.forEach {result.alias(destination,it,it)};return result
    }
    fun legacyNames(discovered: Set<String>,read: (String)->String?): Set<String> {
        val result=NativeMigrationEasyWorkOriginals.legacyNames(discovered,read).toMutableSet();result.add(RECORD)
        read(RECORD)?.let {raw->entries(mapOf(RECORD to raw)).forEach {(id,row)->originals(row).keys.forEach {result.add(alias(id,it))}}}
        return result
    }
    fun captureDestination(storage: NativeSessionStorage): Map<String,String> = storage.migrationSelectedTextFrames(
        {name->NativeMigrationEasyWorkOriginals.owns(name)||name==RECORD||name.startsWith(PREFIX)},::legacyNames)
    class ArchivedInstallation internal constructor(val archiveId: String,val deviceId: String,val publicKey: String,private val records: Map<String,String>) {
        val requiresOriginalInstallation get()=true
        fun names()=records.keys.toSet()
        fun readOnly(name: String)=records[name]
        override fun toString()="ArchivedEasyWorkOriginals(redacted)"
    }
    fun readOnlyArchives(storage: NativeSessionStorage): List<ArchivedInstallation> {
        val all=captureDestination(storage);val portable=NativeMigrationTextMap.copy(all).apply {forget(all.keys.filter(NativeMigrationEasyWorkOriginals::owns).toSet())};validatePortable(portable)
        return entries(portable).map {(id,row)->val context=row.getJSONObject("context");val names=originals(row).keys
            ArchivedInstallation(id,context.getString("deviceId"),context.getString("publicKey"),NativeMigrationValueMap(names){name->portable.getValue(alias(id,name))})}
    }
}
