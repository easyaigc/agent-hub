package com.agenthub.android

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI

/** Closed descriptive business projection. Original authority remains in its installation archive. */
internal object NativeMigrationEasyWorkBusiness {
    const val RECORD="migration-easywork-business-v1"
    private const val MAX_ENTRIES=256
    private const val MAX_CATALOG_BYTES=2*1024*1024
    private fun hash(raw: String)=NativeChatNotificationWire.hash(raw)
    private fun json(raw: String)=StrictMigrationJson.parse(NativeMigrationTextFrames.bytes(raw))
    private fun original(entry: JSONObject): JSONObject {
        val raw=entry.get("originalClient") as String
        require(NativeMigrationTextFrames.bytes(raw).size<=256*1024&&hash(raw)==entry.get("originalClientSha256"))
        return json(raw).also {row->
            NativeMigrationMetadata.validateClient(row,allowSourceEasyWork=true)
            require(row.optJSONObject("migrationEasyWork")==null&&row.getJSONObject("scope").get("agentId")=="easywork"&&row.optJSONObject("agentConnection")!=null)
        }
    }
    private fun entries(records: Map<String,String>): Map<String,JSONObject> {
        val raw=records[RECORD]?:return emptyMap();require(NativeMigrationTextFrames.bytes(raw).size<=MAX_CATALOG_BYTES)
        val root=json(raw);NativeChatNotificationWire.keys(root,setOf("version","clients"));require(root.get("version")==1)
        val rows=root.getJSONArray("clients").objects();require(rows.size in 1..MAX_ENTRIES)
        return rows.associateBy {entry->
            NativeChatNotificationWire.keys(entry,setOf("archiveId","originalClient","originalClientSha256","installationArchiveId","credentialName","credentialSha256"))
            for(key in listOf("archiveId","originalClientSha256","installationArchiveId","credentialSha256"))require((entry.get(key) as String).matches(Regex("[a-f0-9]{64}")))
            val body=JSONObject(entry.toString()).apply {remove("archiveId")}
            val id=entry.getString("archiveId");require(hash(easyCanonical(body))==id)
            val row=original(entry);val connection=AgentConnection.decode(row.getJSONObject("agentConnection"))
            require(entry.get("credentialName")==NativeMigrationEasyWorkOriginals.CREDENTIAL+connection.credentialRef)
            id
        }.also {require(it.size==rows.size)}
    }
    private fun requireBudget(rows: Collection<JSONObject>) {
        var bytes=64L
        for(row in rows){bytes+=NativeMigrationTextFrames.bytes(easyCanonical(row)).size+1L;require(bytes<=MAX_CATALOG_BYTES)}
    }
    private fun encode(rows: Collection<JSONObject>): String {
        requireBudget(rows)
        return easyCanonical(JSONObject().put("version",1).put("clients",JSONArray(rows.sortedBy {it.getString("archiveId")}))).also {require(NativeMigrationTextFrames.bytes(it).size<=MAX_CATALOG_BYTES)}
    }
    private fun candidateOrigin(endpoint: String): String {
        val uri=URI(endpoint);require(uri.rawUserInfo==null&&uri.rawQuery==null&&uri.rawFragment==null)
        return MigratedEasyWorkConnection.origin(URI(uri.scheme,null,uri.host,uri.port,null,null,null).toASCIIString())
    }
    private fun marker(entry: JSONObject): MigratedEasyWorkConnection {
        val row=original(entry);val connection=AgentConnection.decode(row.getJSONObject("agentConnection"))
        val origins=connection.candidates.map {candidateOrigin(it.endpoint)}.distinct()
        return MigratedEasyWorkConnection.decode(JSONObject().put("version",1).put("provider","easywork").put("archiveId",entry.getString("archiveId"))
            .put("target",CodexCommandCodec.target(connection.target)).put("requiresPairing",true).put("credentialState","none")
            .put("preference",connection.preference).put("candidateOrigins",JSONArray(origins)))
    }
    private fun validateCatalog(records: Map<String,String>): Map<String,JSONObject> = entries(records).also {rows->
        for(entry in rows.values) {
            val raw=NativeMigrationEasyWorkArchives.readOnlyOriginal(records,entry.getString("installationArchiveId"),entry.getString("credentialName"),entry.getString("credentialSha256"))
            val credential=EasyWorkCredential.decode(json(raw));val client=decodeClient(original(entry))
            require(sameScope(credential.binding.target.scope(),client.scope))
            credential.relayBinding?.let {require(sameScope(it.target.scope(),client.scope))}
        }
    }
    fun validatePortable(records: Map<String,String>): Set<String> {
        val rows=validateCatalog(records)
        records["metadata"]?.let {raw->
            val metadata=json(raw);NativeMigrationMetadata.validate(metadata)
            metadata.getJSONArray("clients").objects().filter {it.getJSONObject("scope").get("agentId")=="easywork"}.forEach {row->
                val state=MigratedEasyWorkConnection.decode(row.getJSONObject("migrationEasyWork"));val entry=rows.getValue(state.archiveId)
                val original=original(entry);val expected=marker(entry)
                // The user's current path preference/name are editable; original descriptor remains exact.
                require(easyCanonical(state.json().put("preference",expected.preference))==easyCanonical(expected.json()))
                require(row.get("id")==original.get("id")&&row.get("endpoint")==original.get("endpoint")&&sameScope(decodeClient(row).scope,expected.target))
            }
        }
        return if(RECORD in records)setOf(RECORD)else emptySet()
    }
    /** Called after private originals have been archived, with their unmodified source inventory. */
    fun projectSource(portable: Map<String,String>,sourceOriginals: Map<String,String>): Map<String,String> = prepareSource(portable,sourceOriginals).also {validatePortable(it)}
    // Leave the mutable metadata parse behind before validating the resulting portable graph.
    private fun prepareSource(portable: Map<String,String>,sourceOriginals: Map<String,String>): Map<String,String> {
        val result=NativeMigrationTextMap.copy(portable);val rows=validateCatalog(portable).toMutableMap()
        val metadata=json(portable.getValue("metadata"));NativeMigrationMetadata.validate(metadata,allowSourceEasyWork=true)
        val installation by lazy {requireNotNull(NativeMigrationEasyWorkArchives.sourceArchiveId(sourceOriginals)) {"EasyWork source installation missing"}}
        for(row in metadata.getJSONArray("clients").objects()) {
            if(row.getJSONObject("scope").get("agentId")!="easywork"||row.optJSONObject("migrationEasyWork")!=null)continue
            val client=decodeClient(row);val connection=requireNotNull(client.agentConnection)
            val name=NativeMigrationEasyWorkOriginals.CREDENTIAL+connection.credentialRef
            val raw=sourceOriginals.getValue(name);require(sameScope(EasyWorkCredential.decode(json(raw)).binding.target.scope(),client.scope))
            val rawClient=row.toString();require(NativeMigrationTextFrames.bytes(rawClient).size<=256*1024)
            val body=JSONObject().put("originalClient",rawClient).put("originalClientSha256",hash(rawClient))
                .put("installationArchiveId",installation).put("credentialName",name).put("credentialSha256",hash(raw))
            val id=hash(easyCanonical(body));body.put("archiveId",id);rows[id]?.let {require(easyCanonical(it)==easyCanonical(body))};rows[id]=body;require(rows.size<=MAX_ENTRIES);requireBudget(rows.values)
            row.put("agentConnection",JSONObject.NULL).put("codexDirect",JSONObject.NULL).put("storageIdentity",JSONObject.NULL)
                .put("requiresReadTarget",true).put("migrationEasyWork",marker(body).json())
        }
        require(rows.size<=MAX_ENTRIES);if(rows.isNotEmpty())result[RECORD]=encode(rows.values)
        result["metadata"]=metadata.toString();return result
    }
    /** Previous local audit rows are preserved even if their old client was explicitly removed. */
    fun mergeDestination(source: Map<String,String>,destination: Map<String,String>): Map<String,String> {
        val rows=entries(source).toMutableMap();for((id,row)in entries(destination)){rows[id]?.let {require(easyCanonical(it)==easyCanonical(row))};rows[id]=row}
        require(rows.size<=MAX_ENTRIES);val result=NativeMigrationTextMap.copy(source);if(rows.isNotEmpty())result[RECORD]=encode(rows.values)
        validatePortable(result);return result
    }
    fun captureDestination(storage: NativeSessionStorage)=storage.migrationSelectedTextFrames({it==RECORD},{_,_->setOf(RECORD)})

    /** Business keys retain every EasyWork account/runtime component; no Codex alias policy applies. */
    fun validateMetadataGraph(metadata: JSONObject,clients: List<MobileClient>) {
        val easy=clients.filter {it.scope?.agentId=="easywork"};if(easy.isEmpty())return
        val fields=listOf("drafts","draftVersions","draftFiles","attachmentImportGenerations","selectedThreads","newProjects","generation","generationPreferences","replyModes","dismissedReceiptFailures","expanded","sidebar","workspaces")
        for(field in fields)metadata.optJSONObject(field)?.let {map->map.keys().forEach {key->
            if(key.startsWith("agent-v1:")) {
                val bytes=java.util.Base64.getDecoder().decode(key.removePrefix("agent-v1:"));require(bytes.size<=8192)
                val tuple=StrictMigrationJson.value(bytes) as JSONArray
                if(tuple.opt(0)=="easywork") {
                    require(tuple.length()==8&&tuple.get(6) in setOf("draft","thread"))
                    val thread=if(tuple.get(6)=="thread")tuple.get(7) as String else null
                    val project=if(tuple.get(6)=="draft"&&tuple.get(7)!=JSONObject.NULL)tuple.get(7) as String else null
                    require(easy.count {it.cacheKey(thread,project)==key}==1) {"EasyWork business cache target is missing or ambiguous"}
                }
            }
            if(field=="workspaces") {
                val workspace=MobileSnapshotCodec.workspace(map.getJSONObject(key).toString())
                if(workspace.scope?.agentId=="easywork")require(easy.any {it.id==workspace.hostId&&sameScope(it.scope,workspace.scope)&&it.cacheKey(null)==key})
            }
        }}
    }
}
