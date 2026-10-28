package com.agenthub.android

import org.json.JSONObject
import java.util.UUID

/** Preview caches and old consent can be archived; neither grants execution authority. */
internal object NativeMigrationAncillary {
    fun validate(records: Map<String,JSONObject>): Set<String> {
        val names=mutableSetOf<String>()
        records["ai-sharing-v1"]?.let {root->
            require(root.keys().asSequence().toSet()==setOf("version","grants")&&root.get("version")==1)
            val grants=root.getJSONArray("grants").objects();require(grants.size<=1000)
            val ids=grants.map{grant->
                require(grant.keys().asSequence().toSet()==setOf("id","scope","computerName","agentName","modelName","grantedAt"))
                val id=grant.get("id") as String;require(UUID.fromString(id).toString()==id)
                val scope=grant.getJSONObject("scope");require(scope.get("version")==1);AISharingScope.decode(scope)
                for(name in listOf("computerName","agentName","modelName"))require((grant.get(name) as String).length<=100_000)
                val time=grant.get("grantedAt");require((time is Long||time is Int)&&(time as Number).toLong()>=0)
                id
            }
            require(ids.distinct().size==ids.size);names.add("ai-sharing-v1")
        }
        for((name,value)in records)if(name.startsWith("draft-thumbnail-v1:")) {
            val id=name.removePrefix("draft-thumbnail-v1:");require(UUID.fromString(id).toString()==id)
            val manifest=records.getValue("draft-file:$id").getJSONObject("file");CodexCommandCodec.validate(manifest,"creationFile")
            val file=CreationFile(manifest.getString("id"),manifest.getString("name"),manifest.getString("mediaType"),manifest.getLong("sizeBytes"),manifest.getString("sha256"))
            require(file.id==id&&file.mediaType.startsWith("image/"))
            DraftAttachmentThumbnails.validate(value,file);names.add(name)
        }
        return names
    }
}
