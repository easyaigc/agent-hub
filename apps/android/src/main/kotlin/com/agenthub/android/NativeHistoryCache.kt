package com.agenthub.android

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** A small atomic index publishes complete encrypted fragments; readers never mix snapshot generations. */
internal class NativeHistoryCache(private val storage: NativeSessionStorage) {
    fun save(key: String,client: MobileClient,thread: String,page: MobileHistory,messages: List<MobileMessage>,reuseUnchangedFragments: Boolean=false) = storage.snapshotTransaction {
        val name="history:$key"
        val previous=if(storage.storedBytes(name)<=1024*1024) storage.read(name)?.let(::JSONObject) else null
        val header=JSONObject(page.wire).put("messages",JSONArray()).put("nextCursor",page.nextCursor ?: JSONObject.NULL)
        // The in-memory hint avoids an extra decrypt/compare on changing streams.
        // Reuse still requires a complete authenticated read of the actual files;
        // a missing, changed or corrupt fragment must be repaired from the new page.
        if(reuseUnchangedFragments && previous?.optInt("version")==2 &&
            previous.optString("targetKey")==key && previous.optString("hostId")==client.id && previous.optString("threadId")==thread) {
            val cached=try { read(key) } catch(_: Exception) { null }
            if(cached!=null && cached.hostId==client.id && cached.threadId==thread && sameScope(cached.scope,page.scope) && cached.messages==messages) {
                storage.write(name,JSONObject(previous.toString()).put("history",header).toString())
                return@snapshotTransaction
            }
        }
        val generation=UUID.randomUUID().toString();val parts=mutableListOf<String>()
        var published=false
        try {
        messages.chunked(32).forEachIndexed { index,chunk ->
            val part="$name:part:$generation:$index";parts.add(part)
            storage.write(part,JSONArray(chunk.map(::encodeMessage)).toString())
        }
        val envelope=JSONObject().put("version",2).put("targetKey",key).put("hostId",client.id).put("threadId",thread)
            .put("history",header).put("generation",generation).put("messageCount",messages.size).put("parts",JSONArray(parts))
        storage.write(name,envelope.toString())
        published=true
        // Delete only the obsolete generation after the complete new index is durable.
        if(previous?.optInt("version")==2) previous.getJSONArray("parts").strings().forEach { part ->
            if(part.startsWith("$name:part:${previous.getString("generation")}:")) storage.remove(part)
        }
        } finally {
            // A failed generation never replaces or removes the old published index.
            if(!published) parts.forEach { runCatching { storage.remove(it) } }
        }
    }
    fun read(key: String): MobileHistory? = storage.snapshotTransaction {
        decode(key,storage::read)
    }
    companion object {
    /** Shared by the native cache reader and authenticated migration validation. */
    internal fun decode(key: String,read: (String)->String?): MobileHistory? {
        val name="history:$key";val envelope=read(name)?.let(::JSONObject) ?: return null
        require(envelope.getString("targetKey")==key)
        val header=envelope.getJSONObject("history")
        if(envelope.getInt("version")==1) return MobileSnapshotCodec.history(header.toString())
        require(envelope.getInt("version")==2)
        val page=MobileSnapshotCodec.history(header.toString());require(page.hostId==envelope.getString("hostId")&&page.threadId==envelope.getString("threadId"))
        val generation=envelope.getString("generation");require(runCatching { UUID.fromString(generation) }.isSuccess)
        val parts=envelope.getJSONArray("parts").strings();require(parts.size<=10_000&&parts.distinct().size==parts.size)
        val messages=parts.flatMapIndexed { index,part ->
            require(part=="$name:part:$generation:$index") { "缓存分片目标不匹配" }
            val content=try { read(part) } catch(failure: Exception) { throw IllegalStateException("缓存分片 $index 读取失败",failure) }
            val array=JSONArray(content ?: error("缓存分片缺失，原文件已保留"));require(array.length() in 1..32)
            MobileSnapshotCodec.history(JSONObject(header.toString()).put("messages",array).toString()).messages
        }
        require(messages.size==envelope.getInt("messageCount")&&messages.map { it.id }.distinct().size==messages.size)
        return page.copy(messages=messages)
    }
    }
}
