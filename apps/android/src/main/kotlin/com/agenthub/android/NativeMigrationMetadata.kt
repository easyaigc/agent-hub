package com.agenthub.android

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Untrusted archive metadata is closed and typed before it can become an import candidate. */
internal object NativeMigrationMetadata {
    private val required=setOf("version","appearance","demo","selected","clients","drafts","selectedThreads","expanded","workspaces")
    private val optional=setOf("language","draftFiles","draftVersions","attachmentImportGenerations","newProjects","generation","generationPreferences","replyModes","dismissedReceiptFailures","sidebar","photoSources")
    private fun text(value: Any,maximum: Int,nonempty: Boolean=false): String=(value as String).also{require(it.length<=maximum&&(!nonempty||it.isNotEmpty()))}
    private fun map(value: Any): JSONObject=(value as JSONObject).also{require(it.length()<=10000);it.keys().forEach{key->text(key,16384,true)}}
    private fun strings(value: Any): List<String> {
        val array=value as JSONArray;require(array.length()<=10000)
        return (0 until array.length()).map{text(array.get(it),16384,true)}.also{require(it.distinct()==it)}
    }
    private fun fields(root: JSONObject,name: String,check: (Any)->Unit){if(root.has(name))map(root.get(name)).let{rows->rows.keys().forEach{check(rows.get(it))}}}
    fun validate(root: JSONObject) {
        val keys=root.keys().asSequence().toSet();require(keys.containsAll(required)&&keys.all{it in required||it in optional})
        require(root.get("version")==1&&root.get("appearance") in listOf("system","light","dark")&&root.get("demo") is Boolean)
        text(root.get("selected"),16384,true)
        if(root.has("language"))require(root.get("language") in listOf("system","zh-Hans","en"))
        val clients=root.get("clients") as JSONArray;require(clients.length()<=128)
        for(index in 0 until clients.length()) {
            val row=clients.get(index) as JSONObject
            val mandatory=setOf("id","name","endpoint","mode","scope")
            val allowed=mandatory+setOf("readerId","requiresReadTarget","codexDirect","agentConnection","storageIdentity")
            require(row.keys().asSequence().toSet().containsAll(mandatory)&&row.keys().asSequence().all{it in allowed})
            text(row.get("id"),16384,true);text(row.get("name"),4096);val endpoint=text(row.get("endpoint"),8192,true);NativeHostReads.baseAddress(endpoint)
            require(row.get("mode") in listOf("desktop-runtime","managed-runtime","shared-runtime","history"))
            if(row.has("requiresReadTarget"))require(row.get("requiresReadTarget") is Boolean)
            if(row.has("readerId")&&row.get("readerId")!=JSONObject.NULL)text(row.get("readerId"),1024,true)
            for(name in listOf("scope","codexDirect","agentConnection","storageIdentity"))if(row.has(name))require(row.get(name)==JSONObject.NULL||row.get(name)is JSONObject)
            CodexCommandCodec.validate(row.getJSONObject("scope"),"target")
            row.optJSONObject("codexDirect")?.let {direct->
                val mandatoryDirect=setOf("target","fingerprint","routes");val allowedDirect=mandatoryDirect+"systemEndpoints"
                require(direct.keys().asSequence().toSet().containsAll(mandatoryDirect)&&direct.keys().asSequence().all{it in allowedDirect})
                CodexCommandCodec.validate(direct.getJSONObject("target"),"target");text(direct.get("fingerprint"),64,true)
                val routes=direct.get("routes") as JSONArray;require(routes.length() in 1..32)
                for(index in 0 until routes.length()) {val route=routes.get(index) as JSONObject;require(route.keys().asSequence().toSet()==setOf("endpoint","networkKind"));text(route.get("endpoint"),8192,true);text(route.get("networkKind"),64,true)}
                if(direct.has("systemEndpoints"))strings(direct.get("systemEndpoints")).also{require(it.size<=4)}
            }
            row.optJSONObject("storageIdentity")?.let {identity->
                require(identity.keys().asSequence().toSet()==setOf("version","legacyTarget","keys","selections")&&identity.get("version")==1)
                if(identity.get("legacyTarget")!=JSONObject.NULL)CodexCommandCodec.validate(identity.getJSONObject("legacyTarget"),"target")
                strings(identity.get("keys"));val selections=map(identity.get("selections"));selections.keys().forEach{key->text(selections.get(key),16384,true)}
            }
            val client=decodeClient(row);require(client.scope?.agentId=="codex"&&client.scope.hostId==client.id)
        }
        require((0 until clients.length()).map{clients.getJSONObject(it).getString("id")}.distinct().size==clients.length())
        fields(root,"drafts"){text(it,100000)};fields(root,"selectedThreads"){text(it,16384)}
        fields(root,"draftVersions"){text(it,1024,true)}
        fields(root,"attachmentImportGenerations"){val generation=text(it,36,true);require(UUID.fromString(generation).toString()==generation)}
        fields(root,"newProjects"){if(it!=JSONObject.NULL)text(it,16384,true)}
        fields(root,"expanded"){strings(it)};fields(root,"dismissedReceiptFailures"){strings(it)}
        fields(root,"replyModes"){require(it in listOf("queue","steer","next-turn"))}
        for(name in listOf("generation","generationPreferences"))fields(root,name){
            val settings=it as JSONObject
            require(settings.keys().asSequence().all{it in setOf("model","effort","permissionMode","speed","collaborationMode")})
            settings.keys().forEach{field->val value=settings.get(field);if(value!=JSONObject.NULL)text(value,1024)}
        }
        fields(root,"sidebar"){
            val state=it as JSONObject;val mandatory=setOf("recent","projects","lists");val allowed=mandatory+setOf("pinnedSection","recentSection")
            require(state.keys().asSequence().toSet().containsAll(mandatory)&&state.keys().asSequence().all{it in allowed})
            for(name in listOf("recent","projects","pinnedSection","recentSection"))if(state.has(name))require(state.get(name)is Boolean)
            strings(state.get("lists"))
        }
        fields(root,"workspaces"){MobileSnapshotCodec.workspace((it as JSONObject).toString())}
        fields(root,"draftFiles"){
            val files=it as JSONArray;require(files.length()<=10)
            val ids=(0 until files.length()).map{index->val file=CodexCommandCodec.validate(files.get(index) as JSONObject,"creationFile");file.getString("id")};require(ids.distinct()==ids)
        }
        if(root.has("photoSources"))map(root.get("photoSources")) // Reference/media/source validation follows at the manifest boundary.
    }
}
