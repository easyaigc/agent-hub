package com.agenthub.android

import org.json.JSONObject
import org.json.JSONArray
import com.agenthub.core.MessageProjection
import java.security.MessageDigest
import java.util.UUID

/** Optional encrypted local-cache metadata. Never restores runtime/control freshness. */
internal data class NativeHistoryPagingCheckpoint(val nextCursor: String?, val windowed: Boolean, val revision: Long?) {
    fun encode()=JSONObject().put("version",1).put("nextCursor",nextCursor ?: JSONObject.NULL)
        .put("windowed",windowed).put("revision",revision ?: JSONObject.NULL)
    companion object {
        fun decode(value: JSONObject): NativeHistoryPagingCheckpoint? = runCatching {
            require(value.getInt("version")==1)
            NativeHistoryPagingCheckpoint(value.optional("nextCursor"),value.getBoolean("windowed"),
                if(value.isNull("revision"))null else value.getLong("revision"))
        }.getOrNull()
    }
}

internal data class NativeHistoryViewportBookmark(val anchorId: String,val offset: Int,val messageLimit: Int,val followsLatest: Boolean) {
    fun encode()=JSONObject().put("version",1).put("anchorId",anchorId).put("offset",offset)
        .put("messageLimit",messageLimit).put("followsLatest",followsLatest)
    companion object {
        fun decode(value: JSONObject): NativeHistoryViewportBookmark? = runCatching {
            require(value.getInt("version")==1)
            NativeHistoryViewportBookmark(value.getString("anchorId"),value.getInt("offset"),value.getInt("messageLimit"),value.getBoolean("followsLatest"))
                .also { require(it.anchorId.isNotEmpty()&&it.anchorId.length<=1024&&it.offset in -100_000..100_000) }
        }.getOrNull()
    }
}

internal data class NativeReadingRecoverySnapshot(
    val messages: List<MobileMessage>,val paging: NativeHistoryPagingCheckpoint?,val viewport: NativeHistoryViewportBookmark?
)

/** A separate encrypted cache family which old metadata/history writers cannot replace.
 * All methods run on the storage snapshot queue. No runtime, receipt or command is stored.
 */
internal class NativeReadingRecovery(private val storage: NativeSessionStorage,private val publicationProbe: (String)->Unit={}) {
    companion object {
        const val INDEX="reading-recovery-v1"
        const val BODY_LIMIT=32*1024*1024
        const val INDEX_LIMIT=256*1024
        const val MESSAGE_LIMIT=50_000
        const val ENTRY_LIMIT=128
        const val TOTAL_LIMIT=128L*1024*1024
        private fun digest(value: ByteArray)=MessageDigest.getInstance("SHA-256").digest(value).joinToString(""){"%02x".format(it)}
        private fun bodyName(id: String): String {
            require(UUID.fromString(id).toString()==id)
            return "$INDEX:body:$id"
        }
        private fun anchors(messages: List<MobileMessage>): Set<String> {
            val rows=MessageProjection.project(messages.map {it.timelineMessage()},messages.size,null,false).rows
            return buildSet {
                for(row in rows) {
                    add(row.kind+":"+row.id)
                    for(index in row.indices) {
                        if(row.kind in setOf("tools","errors"))add(row.kind+":"+messages[index].id)
                    }
                }
            }
        }
    }
    private var blocked=false
    private val anchorCache=linkedMapOf<String,Set<String>>()
    var bodyWrites=0;private set
    var bodyBytes=0L;private set
    var indexWrites=0;private set
    var indexBytes=0L;private set
    val enabled get()=storage.canUseReadingRecovery&&!blocked
    fun disable(){blocked=true}
    private fun index(): JSONObject {
        val raw=storage.readReadingRecovery(INDEX) ?: run {
            // The earlier root-family prototype was confined to isolated task
            // profiles. Preserve it rather than silently replacing or deleting it.
            check(storage.read(INDEX)==null) { "Unreleased root reading cache retained" }
            return JSONObject().put("version",1).put("namespace",storage.readingRecoveryNamespace).put("entries",JSONObject())
        }
        require(raw.toByteArray().size<=INDEX_LIMIT)
        val value=JSONObject(raw)
        require(value.getInt("version")==1&&value.getString("namespace")==storage.readingRecoveryNamespace)
        val entries=value.getJSONObject("entries");require(entries.length()<=ENTRY_LIMIT)
        for(key in entries.keys()) {
            require(key.length in 1..4096)
            val entry=entries.getJSONObject(key)
            bodyName(entry.getString("body"))
            require(entry.getString("reader").length in 1..512&&entry.getString("host").length in 1..512&&
                entry.getString("thread").length in 1..1024&&entry.getString("sha256").matches(Regex("[a-f0-9]{64}")))
            require(entry.getLong("bytes") in 1..BODY_LIMIT.toLong()&&entry.getInt("count") in 1..MESSAGE_LIMIT)
        }
        require(entries.keys().asSequence().sumOf {entries.getJSONObject(it).getLong("bytes")}<=TOTAL_LIMIT)
        for(name in listOf("staging","garbage"))if(!value.isNull(name)&&value.has(name))bodyName(value.getString(name))
        return value
    }
    private fun publish(value: JSONObject) {
        val text=value.toString();require(text.toByteArray().size<=INDEX_LIMIT)
        storage.writeReadingRecovery(INDEX,text)
        check(storage.readReadingRecovery(INDEX)==text) {"Reading recovery index was not published"}
        indexWrites++;indexBytes+=text.toByteArray().size
    }
    private fun collect(value: JSONObject) {
        val referenced=value.getJSONObject("entries").let { entries -> entries.keys().asSequence().map { entries.getJSONObject(it).getString("body") }.toSet() }
        for(name in listOf("staging","garbage"))if(value.has(name)&&!value.isNull(name)) {
            val id=value.getString(name)
            if(id !in referenced) {
                storage.removeReadingRecovery(bodyName(id))
                check(storage.readReadingRecovery(bodyName(id))==null) {"Reading recovery obsolete body was not removed"}
            }
            value.remove(name);publish(value)
            anchorCache.remove(id)
        }
    }
    private inline fun <T> checked(action: ()->T): T {
        try {return action()}catch(failure: Exception) {blocked=true;throw failure}
    }
    /** Must run against the loaded carrier, before a nil reader is initialized. */
    fun reconcile(clients: List<MobileClient>) {
        if(!enabled)return
        checked {storage.snapshotTransaction {
            val value=index();collect(value)
            val entries=value.getJSONObject("entries")
            for(key in entries.keys().asSequence().toList()) {
                val entry=entries.getJSONObject(key)
                val client=clients.firstOrNull {it.id==entry.getString("host")&&!it.isDemo&&it.migrationEasyWork==null}
                if(client==null||client.readerId.isNullOrEmpty()||client.readerId!=entry.getString("reader")||
                    client.scope==null||client.cacheKey(entry.getString("thread"))!=key) {
                    value.put("garbage",entry.getString("body"));entries.remove(key);publish(value);collect(value)
                }
            }
        }}
    }
    private fun loadBody(key: String,entry: JSONObject,client: MobileClient): NativeReadingRecoverySnapshot {
        val raw=storage.readReadingRecovery(bodyName(entry.getString("body"))) ?: error("Reading recovery body missing")
        val bytes=raw.toByteArray();require(bytes.size==entry.getInt("bytes")&&bytes.size<=BODY_LIMIT&&digest(bytes)==entry.getString("sha256"))
        val body=JSONObject(raw)
        require(body.getInt("version")==1&&body.getString("namespace")==storage.readingRecoveryNamespace&&
            body.getString("key")==key&&body.getString("reader")==client.readerId&&
            body.getString("hostId")==client.id&&body.getString("threadId")==entry.getString("thread"))
        val target=body.getJSONObject("readTarget")
        CodexCommandCodec.validate(target,"target")
        require(sameScope(HostReadCodec.target(target),client.scope))
        val page=MobileSnapshotCodec.history(MobileSnapshotCodec.historyCacheHeader(client)
            .put("threadId",body.getString("threadId")).put("messages",body.getJSONArray("messages")).toString())
        require(page.messages.size==entry.getInt("count")&&page.messages.map{it.id}.distinct().size==page.messages.size&&sameScope(page.scope,client.scope))
        val allowed=anchors(page.messages);anchorCache[entry.getString("body")]=allowed
        while(anchorCache.size>2)anchorCache.remove(anchorCache.keys.first())
        val paging=entry.optJSONObject("paging")?.let {NativeHistoryPagingCheckpoint.decode(it) ?: error("Reading recovery paging invalid")}
        val viewport=entry.optJSONObject("viewport")?.let {NativeHistoryViewportBookmark.decode(it) ?: error("Reading recovery viewport invalid")}
        require(viewport==null||viewport.anchorId in allowed)
        return NativeReadingRecoverySnapshot(page.messages,paging,viewport)
    }
    fun read(client: MobileClient,thread: String): NativeReadingRecoverySnapshot? {
        if(!enabled||client.readerId.isNullOrEmpty()||client.scope==null)return null
        return checked {storage.snapshotTransaction {
            val key=client.cacheKey(thread);val value=index();collect(value)
            val entry=value.getJSONObject("entries").optJSONObject(key) ?: return@snapshotTransaction null
            if(entry.getString("reader")!=client.readerId||entry.getString("host")!=client.id||entry.getString("thread")!=thread)return@snapshotTransaction null
            loadBody(key,entry,client)
        }}
    }
    fun checkpoint(client: MobileClient,thread: String,messages: List<MobileMessage>,paging: NativeHistoryPagingCheckpoint?,
        viewport: NativeHistoryViewportBookmark?,forceBody: Boolean,current: ()->Boolean): Boolean {
        if(!enabled||client.readerId.isNullOrEmpty()||client.scope==null||messages.isEmpty()||!current())return false
        return checked {storage.snapshotTransaction {
            if(!current())return@snapshotTransaction false
            val key=client.cacheKey(thread);val value=index();collect(value);val entries=value.getJSONObject("entries")
            val previous=entries.optJSONObject(key)
            val same=previous?.getString("reader")==client.readerId
            val allowed=previous?.getString("body")?.let {anchorCache[it]}
            // A scroll update never decrypts or reconstructs a body. An evicted
            // anchor memo waits for the next explicit stable-body checkpoint.
            if(!forceBody&&same&&allowed==null)return@snapshotTransaction false
            if(!forceBody&&same&&(viewport==null||viewport.anchorId in allowed.orEmpty())) {
                val next=JSONObject(previous!!.toString()).put("viewport",viewport?.encode() ?: JSONObject.NULL)
                if(next.toString()!=previous.toString()&&current()) {entries.put(key,next);publish(value)}
                return@snapshotTransaction current()
            }
            require(messages.size<=MESSAGE_LIMIT&&messages.map{it.id}.distinct().size==messages.size)
            val validAnchors=anchors(messages);require(viewport==null||viewport.anchorId in validAnchors)
            val body=JSONObject().put("version",1).put("namespace",storage.readingRecoveryNamespace).put("key",key)
                .put("reader",client.readerId).put("hostId",client.id).put("threadId",thread)
                .put("readTarget",CodexCommandCodec.target(client.scope)).put("messages",JSONArray(messages.map(::encodeMessage)))
            val raw=body.toString();val bytes=raw.toByteArray();require(bytes.size<=BODY_LIMIT)
            require(previous!=null||entries.length()<ENTRY_LIMIT)
            val total=entries.keys().asSequence().filter {it!=key}.sumOf {entries.getJSONObject(it).getLong("bytes")}
            require(total+bytes.size<=TOTAL_LIMIT)
            if(!current())return@snapshotTransaction false
            val id=UUID.randomUUID().toString()
            value.put("staging",id);publish(value)
            publicationProbe("before-body")
            storage.writeReadingRecovery(bodyName(id),raw)
            check(storage.readReadingRecovery(bodyName(id))==raw) {"Reading recovery body was not persisted"}
            bodyWrites++;bodyBytes+=bytes.size
            publicationProbe("after-body")
            if(!current()) {collect(value);return@snapshotTransaction false}
            val entry=JSONObject().put("body",id).put("reader",client.readerId).put("host",client.id).put("thread",thread)
                .put("sha256",digest(bytes)).put("bytes",bytes.size).put("count",messages.size)
                .put("paging",paging?.encode() ?: JSONObject.NULL).put("viewport",viewport?.encode() ?: JSONObject.NULL)
            entries.put(key,entry);value.remove("staging")
            previous?.getString("body")?.let {value.put("garbage",it)}
            publicationProbe("before-index")
            publish(value);publicationProbe("after-index");anchorCache[id]=validAnchors;collect(value)
            while(anchorCache.size>2)anchorCache.remove(anchorCache.keys.first())
            true
        }}
    }
    fun discard(key: String) {
        if(!enabled)return
        checked {storage.snapshotTransaction {
            val value=index();collect(value);val entry=value.getJSONObject("entries").optJSONObject(key) ?: return@snapshotTransaction
            value.getJSONObject("entries").remove(key);value.put("garbage",entry.getString("body"));publish(value);collect(value)
        }}
    }
    /** Pure old-format export projection. Capture under the same source lock as
     * ordinary records; never write/GC here or migrate cached authority. */
    fun projectExport(records: Map<String,String>): Map<String,String> = storage.snapshotTransaction {
        val metadata=JSONObject(records.getValue("metadata"))
        for(field in listOf("historyPaging","historyViewports"))if(metadata.has(field)) {
            val values=metadata.getJSONObject(field);require(values.length()<=10_000)
            for(key in values.keys()) {
                require(key.length in 1..16_384)
                if(field=="historyPaging")require(NativeHistoryPagingCheckpoint.decode(values.getJSONObject(key))!=null)
                else require(NativeHistoryViewportBookmark.decode(values.getJSONObject(key))!=null)
            }
            metadata.remove(field)
        }
        // The portable metadata stays compatible with the old closed reader.
        NativeMigrationMetadata.validate(metadata,allowSourceEasyWork=true)
        val result=NativeMigrationTextMap.copy(records)
        result["metadata"]=metadata.toString()
        if(!enabled)return@snapshotTransaction result
        val value=index() // Does not collect staging/garbage during export.
        val entries=value.getJSONObject("entries")
        val clients=metadata.getJSONArray("clients").objects().map(::decodeClient)
        for(key in entries.keys()) {
            val entry=entries.getJSONObject(key)
            val client=clients.singleOrNull {it.id==entry.getString("host")&&!it.isDemo&&it.migrationEasyWork==null}
                ?: error("Reading export target is no longer present")
            require(!client.readerId.isNullOrEmpty()&&client.readerId==entry.getString("reader")&&client.scope!=null&&
                client.cacheKey(entry.getString("thread"))==key)
            // Keep the established single-frame limit. A larger reading cache
            // does not silently raise the portable archive's memory/budget limit.
            require(entry.getLong("bytes")<=NativeMigrationTextFrames.MAX_RECORD_BYTES)
            val snapshot=loadBody(key,entry,client)
            val paging=requireNotNull(snapshot.paging) {"Reading export has no coherent paging mode"}
            val ordinary=NativeHistoryCache.decode(key,records::get)
            if(ordinary!=null) {
                require(ordinary.hostId==client.id&&ordinary.threadId==entry.getString("thread")&&sameScope(ordinary.scope,client.scope))
                val positions=snapshot.messages.mapIndexed {index,message->message.id to index}.toMap()
                var previous=-1
                for(message in ordinary.messages) {
                    val position=requireNotNull(positions[message.id]) {"Newer ordinary messages must not be discarded"}
                    require(position>previous&&snapshot.messages[position]==message) {"Conflicting reading snapshots retained"}
                    previous=position
                }
                require(com.agenthub.core.HistoryCacheRecovery.canKeepPrefix(paging.windowed,ordinary.windowed,
                    paging.revision ?: -1L,ordinary.historyRevision ?: -1L,ordinary.messages.isNotEmpty()))
            }
            val name="history:$key"
            records[name]?.let {raw ->
                val envelope=JSONObject(raw)
                if(envelope.getInt("version")==2)result.forget(envelope.getJSONArray("parts").strings().toSet())
            }
            val page=MobileSnapshotCodec.historyCacheHeader(client).put("threadId",entry.getString("thread"))
                .put("messages",JSONArray(snapshot.messages.map(::encodeMessage)))
                .put("nextCursor",paging.nextCursor ?: JSONObject.NULL).put("historyWindow",paging.windowed)
                .put("historyRevision",paging.revision ?: JSONObject.NULL)
            val encoded=JSONObject().put("version",1).put("targetKey",key).put("history",page).toString()
            require(encoded.toByteArray().size<=NativeMigrationTextFrames.MAX_RECORD_BYTES)
            result[name]=encoded
        }
        result
    }
}
