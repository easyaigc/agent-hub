package com.agenthub.android

import com.agenthub.core.RuntimeIdentity
import org.json.JSONArray
import org.json.JSONObject

/** Archived history is a closed cache graph, never evidence of a connected owner. */
internal object NativeMigrationHistory {
    fun validate(values: Map<String,Any>,clients: List<MobileClient>): Set<String> {
        val claimed=mutableSetOf<String>()
        for((name,value)in values)if(name.startsWith("history:")&&value is JSONObject) {
            val key=name.removePrefix("history:");require(key.isNotEmpty())
            val version=value.get("version");require(version==1||version==2)
            val required=if(version==1)setOf("version","targetKey","history") else setOf("version","targetKey","hostId","threadId","history","generation","messageCount","parts")
            require(value.keys().asSequence().toSet()==required&&value.get("targetKey")==key&&value.get("history") is JSONObject)
            require(claimed.add(name))
            if(version==2) {
                require(value.get("hostId") is String&&value.get("threadId") is String&&value.get("generation") is String&&value.get("messageCount") is Int&&value.getInt("messageCount")>=0)
                val generation=value.getString("generation");require(java.util.UUID.fromString(generation).toString()==generation)
                val parts=value.get("parts") as JSONArray;require(parts.length()<=1024)
                for(index in 0 until parts.length()) {
                    val part=parts.get(index) as String;require(part=="$name:part:$generation:$index"&&claimed.add(part))
                    val messages=values[part] as JSONArray;require(messages.length() in 1..32)
                }
                require(value.getJSONObject("history").getJSONArray("messages").length()==0)
            }
            val page=NativeHistoryCache.decode(key){record->values[record]?.toString()}?:error("Missing history")
            require(page.messages.map{it.id}.distinct().size==page.messages.size)
            require(clients.any{it.id==page.hostId&&RuntimeIdentity.matches(it.scope,page.scope)&&key in it.operationTargets(page.threadId)})
        }
        require(values.keys.filter{it.startsWith("history:")}.all{it in claimed})
        return claimed
    }
}
