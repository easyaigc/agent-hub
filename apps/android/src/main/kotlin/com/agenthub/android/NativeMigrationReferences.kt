package com.agenthub.android

import org.json.JSONArray
import org.json.JSONObject

/** A closed archive graph: every payload belongs to an exact draft or immutable operation. */
internal object NativeMigrationReferences {
    private fun keys(value: JSONObject)=value.keys().asSequence().toSet()
    private fun fields(value: JSONObject,required: Set<String>,optional: Set<String> = emptySet()) {
        val names=keys(value);require(names.containsAll(required)&&names.all {it in required||it in optional})
    }
    private fun equal(a: Any,b: Any): Boolean=when {
        a is JSONObject&&b is JSONObject -> keys(a)==keys(b)&&keys(a).all{equal(a.get(it),b.get(it))}
        a is JSONArray&&b is JSONArray -> a.length()==b.length()&&(0 until a.length()).all{equal(a.get(it),b.get(it))}
        else -> a==b
    }
    fun validate(records: Map<String,JSONObject>,clients: List<MobileClient>,operations: Sequence<SavedOperation>) {
        val metadata=records.getValue("metadata")
        val references=mutableMapOf<String,JSONObject>()
        fun reference(file: JSONObject) {
            CodexCommandCodec.validate(file,"creationFile")
            val id=file.getString("id")
            references[id]?.let {require(equal(it,file))}
            references[id]=file
        }
        val draftFiles=metadata.optJSONObject("draftFiles")
        draftFiles?.keys()?.forEach {key->draftFiles.getJSONArray(key).objects().forEach(::reference)}
        val draftIds=references.keys.toSet()
        metadata.optJSONObject("photoSources")?.keys()?.forEach {require(it in draftIds)}
        val operationRequired=setOf("id","kind","target","intent","intentSha256","phase","result","error","detached")
        val operationOptional=setOf("submissionAttempted","sourceKey","sourceVersion","parentOperationId","replyRecovery","forkContext","sharingScope","migrationReadOnly")
        for((name,body)in records)if(name.startsWith("operations:")) {
            fields(body,setOf("version","target","operations"))
            require(body.get("version")==1&&body.get("target") is String&&body.get("operations") is JSONArray)
            val rows=body.getJSONArray("operations");require(rows.length()<=10_000)
            rows.objects().forEach {row->
                fields(row,operationRequired,operationOptional)
                for(field in listOf("id","kind","target","intent","intentSha256","phase"))require(row.get(field)is String)
                for(field in listOf("result","error","sourceKey","sourceVersion","parentOperationId"))if(row.has(field))require(row.get(field)==JSONObject.NULL||row.get(field)is String)
                for(field in listOf("detached","submissionAttempted","migrationReadOnly"))if(row.has(field))require(row.get(field)is Boolean)
                for(field in listOf("replyRecovery","forkContext","sharingScope"))if(row.has(field))require(row.get(field)==JSONObject.NULL||row.get(field)is JSONObject)
                require(row.getString("phase") in setOf("ready","preparing","prepared","submitting","received","queued","submitted","completed","cancelled","interrupted","failed","uncertain","confirmed","outcome-unknown","rejected","opening","opened"))
            }
        }
        for(row in operations) {
            val intent=JSONObject(row.intent)
            if(row.kind=="creation")intent.getJSONArray("attachments").objects().forEach(::reference)
            require((row.sourceKey==null)==(row.sourceVersion==null))
            if(row.kind in listOf("reply","creation")&&row.sourceKey!=null)require("submission:${row.id}" in records)
            val source=records["submission:${row.id}"]
            if(source!=null) {
                fields(source,setOf("hostId","target","sourceKey","sourceVersion","text","files"),setOf("model","effort","permissionMode","speed","collaborationMode","photoSources"))
                for(field in listOf("hostId","sourceKey","sourceVersion","text"))require(source.get(field)is String)
                require(source.getString("text").length<=100_000&&source.get("target")is JSONObject&&source.get("files")is JSONArray)
                for(field in listOf("model","effort","permissionMode","speed","collaborationMode"))if(source.has(field))require(source.get(field)==JSONObject.NULL||source.get(field)is String)
                if(source.has("photoSources"))require(source.get("photoSources")is JSONObject)
                val files=source.getJSONArray("files").objects();require(files.size<=10&&files.map{it.getString("id")}.distinct().size==files.size)
                files.forEach(::reference)
                if(row.kind=="reply")require(files.map{it.getString("id")}==intent.getJSONArray("attachmentIds").strings())
            }
            if(row.kind=="reply")intent.getJSONArray("attachmentIds").strings().forEach {require("draft-file:$it" in records)}
        }
        val payloads=records.keys.filter {it.startsWith("draft-file:")}.map {it.removePrefix("draft-file:")}.toSet()
        require(payloads==references.keys)
        for((id,file)in references)require(equal(file,records.getValue("draft-file:$id").getJSONObject("file")))
        records["codex-private-connections-v1"]?.let {vault->
            require(vault.toString().length<=1_048_576)
            for(row in CodexConnectionVault.validateRows(vault)) {
                // Pairing, routes, storage identity and runtime must match the archived client exactly.
                val saved=decodeClient(row.getJSONObject("client"))
                val client=clients.single {it.id==saved.id}
                val original=metadata.getJSONArray("clients").objects().single {it.getString("id")==client.id}
                require(equal(row.getJSONObject("client"),original))
            }
        }
    }
}
