package com.agenthub.android

import android.content.Context
import android.system.Os
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

/** androidTest only: source-derived names, existing key/read-only namespace, ciphertext streams. */
internal data class F31UserDataSnapshotV2(
    val protectedMetadata: Map<String, Any?>,
    val cipher: Map<String, Cipher>,
    val localReferences: Int,
    val remoteReferences: Int,
    val derived: Derived
) {
    internal data class Cipher(val sha256: String,val bytes: Long,val stat: List<Long>)
    fun same(other: F31UserDataSnapshotV2)=protectedMetadata==other.protectedMetadata && cipher==other.cipher &&
        localReferences==other.localReferences && remoteReferences==other.remoteReferences
    fun evidence()=JSONObject().put("contract", "f31-user-data-v2-parent-required")
        .put("localReferences",localReferences).put("remoteReferences",remoteReferences)
        .put("cipherBindings",JSONArray(cipher.toSortedMap().map { (name,value) ->
            JSONObject().put("relativePath","no_backup/mobile-v1/$name.bin")
                .put("sha256",value.sha256).put("bytes",value.bytes).put("stat",JSONArray(value.stat))
        })).put("externalFullProtectionRequired",true)
        .put("derivedRecordDescriptorsV1",derived.evidence())
    // androidTest evidence only. Never a new authorization or a replacement for same().
    internal data class DerivedRow(val protectedHash: String,val headHash: String?,val headRevision: Long?)
    internal data class Derived(val safe: JSONObject,val scopes: Set<String>?,val rows: Map<String,DerivedRow>?,val qaRow: String,
        val historyV2: F31DerivedProofV2.History? = null) {
        fun evidence()=JSONObject(safe.toString())
        fun proofV2(): JSONObject = F31DerivedProofV2.proof(safe,scopes,
            rows?.map { (key,row) -> F31DerivedProofV2.Row(digest(key),row.protectedHash,row.headRevision,row.headHash) },
            digest(qaRow),historyV2)
        fun preservationV2(next: Derived): JSONObject = F31DerivedProofV2.compare(proofV2(),next.proofV2())
        fun preservation(next: Derived): JSONObject {
            val a=rows;val b=next.rows
            val observed=a!=null && b!=null && scopes!=null && next.scopes!=null && qaRow==next.qaRow
            val keys=observed && a!!.keys==b!!.keys
            val nonSelected=keys && a!!.filterKeys {it!=qaRow}.mapValues {it.value.protectedHash}==
                b!!.filterKeys {it!=qaRow}.mapValues {it.value.protectedHash}
            val qa=keys && a!![qaRow]?.protectedHash==b!![qaRow]?.protectedHash
            val heads=keys && a!!.all { (key,old) ->
                val new=b!!.getValue(key)
                old.headRevision==null || (new.headRevision!=null &&
                    (new.headRevision>old.headRevision || new.headRevision==old.headRevision && new.headHash==old.headHash))
            }
            return JSONObject().put("version",1).put("status",if(observed)"OBSERVED" else "UNAVAILABLE")
                .put("scopeSetPreserved",observed && scopes==next.scopes).put("rowKeysPreserved",keys)
                .put("nonSelectedReadPendingAndNoticesPreserved",nonSelected)
                .put("qaReadPendingAndNoticesUnchanged",qa).put("derivedHeadsNoRegression",heads)
                // No production API exports the actual visible lease/ack binding here.
                .put("qaReadingProof",if(qa)"UNCHANGED" else "UNKNOWN_REQUIRES_PRODUCT_READING_PROOF")
                .put("completeUserDataProtectionPassed",JSONObject.NULL)
        }
    }
    companion object {
        private fun digest(text: String)=MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString(""){"%02x".format(it)}
        private fun value(v: Any?): Any? = when(v) {
            null,JSONObject.NULL -> null
            is JSONObject -> v.keys().asSequence().associateWith { value(v.get(it)) }
            is JSONArray -> (0 until v.length()).map { value(v.get(it)) }
            is String,is Boolean -> v
            is Int,is Long -> (v as Number).toLong()
            else -> error("F31_USER_DATA_VALUE")
        }
        private fun fields(v: JSONObject, allowed: Set<String>) {require(v.keys().asSequence().all {it in allowed})}
        /** Production message encoding, then canonical SHA only. No message values leave this process. */
        internal fun historyProofV2(page: MobileHistory?): F31DerivedProofV2.History? = try {
            page?.let { value ->
                require(value.messages.size<=F31DerivedProofV2.MAX_MESSAGES)
                require(value.messages.all { it.id.isNotEmpty() } && value.messages.map { it.id }.distinct().size==value.messages.size)
                var bytes=0L
                val messages=value.messages.map { message ->
                    val encoded=easyCanonical(encodeMessage(message)).toByteArray(Charsets.UTF_8)
                    bytes+=encoded.size
                    require(encoded.size<=1_048_576 && bytes<=16L*1024*1024)
                    F31DerivedProofV2.Message(digest(message.id),MessageDigest.getInstance("SHA-256")
                        .digest(encoded).joinToString(""){"%02x".format(it)})
                }
                F31DerivedProofV2.History(messages,value.windowed,value.historyWindowKind,value.nextCursor!=null)
            }
        } catch (_: Exception) { null } // Unknown is never an observed empty history.
        private fun derived(reader: NativeSessionStorage.ExistingReader,clients: List<MobileClient>,
            stamp: (String)->Cipher): Derived {
            val host="13c81542-48e3-4df2-ab46-d45357b631a2"
            val thread="01a1d0bb-e7b3-7c51-92d9-98ab96329fb7"
            val client=clients.single {it.id==host};val target=requireNotNull(client.scope)
            require(target.agentId=="codex" && target.hostId==host && target.workspaceId==host && target.runtimeId=="desktop-runtime")
            val machine=JSONObject().put("agentId",target.agentId).put("hostId",target.hostId)
                .put("workspaceId",target.workspaceId).put("runtimeId",target.runtimeId)
            val key=com.agenthub.core.RuntimeIdentity.cacheKey(host,target,thread,null)
            val index="history:$key";val ledger=NativeReadingLedger.STORAGE_NAME
            val descriptors=linkedMapOf<String,JSONObject>();var budget=0L
            fun text(name: String): String? {
                // Only a fixed ledger/index or a decoder-requested exact QA generation part.
                require(name==ledger || name==index || name.startsWith("$index:part:"))
                val raw=reader.optionalText(name,1_048_576) ?: return null
                budget+=raw.toByteArray(Charsets.UTF_8).size;require(budget<=16L*1024*1024 && descriptors.size<12)
                val c=stamp(name)
                // Bind the decoded text to the same settled original ciphertext; no AtomicFile recovery.
                require(reader.opaqueTextDigest(name,1_048_576)==c.sha256 && reader.text(name,1_048_576)==raw)
                val d=JSONObject().put("logicalNameHash",digest(name)).put("relativePath","no_backup/mobile-v1/${digest(name)}.bin")
                    .put("sha256",c.sha256).put("bytes",c.bytes).put("stat",JSONArray(c.stat))
                if(name==ledger)d.put("kind","global-reading-ledger")
                else if(name==index)d.put("kind","qa-history-index")
                else {
                    val suffix=name.removePrefix("$index:part:");val generation=suffix.substringBefore(':')
                    require(java.util.UUID.fromString(generation).toString()==generation)
                    val ordinal=suffix.substringAfter(':').toInt();require(ordinal in 0..9 && suffix=="$generation:$ordinal")
                    d.put("kind","qa-history-part").put("ordinal",ordinal).put("generationHash",digest(generation))
                }
                require(descriptors.put(name,d)==null);return raw
            }
            // Actual production decoder supplies and validates each referenced part logical name.
            val page=NativeHistoryCache.decode(key,::text)
            if(page!=null)require(page.hostId==host && page.threadId==thread && sameScope(page.scope,target))
            val canonical=text(ledger)?.let {NativeReadingLedger.canonicalEncodedForInspection(it)}?.let(::JSONObject)
            val rows=canonical?.getJSONArray("rows")?.objects()?.associate { row ->
                val scope=requireNotNull(HostReadCodec.target(row.getJSONObject("target")))
                val rowKey=ThreadReadingIdentity.key(scope,readingString(row,"threadId"))
                val protected=JSONObject(row.toString()).also {it.remove("latest");it.remove("replyUnread")}
                val head=row.optJSONObject("latest")
                rowKey to DerivedRow(digest(easyCanonical(protected)),head?.let {digest(easyCanonical(it))},head?.let {readingInteger(it,"revision")})
            }
            val scopes=canonical?.getJSONArray("scopes")?.strings()?.toSet()
            val qaRow=ThreadReadingIdentity.key(target,thread)
            val safe=JSONObject().put("version",1).put("targetHash",digest(easyCanonical(machine)))
                .put("threadHash",digest(thread)).put("cacheKeyHash",digest(key))
                .put("historyPresent",page!=null).put("historyProductionDecoded",page!=null)
                .put("historyMessageCount",page?.messages?.size ?: 0).put("ledgerPresent",canonical!=null)
                .put("ledgerProductionDecoded",canonical!=null).put("records",JSONArray(descriptors.values.toList()))
                .put("ledgerRevision",canonical?.getLong("revision") ?: JSONObject.NULL)
                .put("scopeSetHash",scopes?.sorted()?.let {digest(JSONArray(it).toString())} ?: JSONObject.NULL)
                .put("rowKeysHash",rows?.keys?.sorted()?.let {digest(JSONArray(it).toString())} ?: JSONObject.NULL)
            require(safe.toString().toByteArray(Charsets.UTF_8).size<=8192)
            return Derived(safe,scopes,rows,qaRow,historyProofV2(page))
        }
        fun capture(context: Context, metadata: JSONObject, operations: List<SavedOperation>): F31UserDataSnapshotV2 {
            NativeMigrationMetadata.validate(metadata,allowSourceEasyWork=true)
            val clients=metadata.getJSONArray("clients").objects().map(::decodeClient)
            val owners=linkedMapOf<String,F31UserDataRulesV2.Owner>()
            clients.forEach { client ->
                val target=F31UserDataRulesV2.obj(value(CodexCommandCodec.target(requireNotNull(client.scope))))
                for(key in client.operationTargets(null)) {
                    val owner=F31UserDataRulesV2.Owner(client.id,target,client.requiresReadTarget)
                    require(owners[key]==null || owners[key]==owner);owners[key]=owner
                }
                client.agentConnection?.let { c ->
                    // id is used for retry-exclusion, so only the production-derived direct ID is exempt.
                    c.candidates.filter {it.transportId=="lan-wss"}.forEach { candidate ->
                        val pin=requireNotNull(candidate.peerFingerprint)
                        val hints=candidate.networkKind?.let {listOf(JSONObject().put("endpoint",candidate.endpoint).put("networkKind",it))}.orEmpty()
                        val expected=EasyWorkPairing.directCandidates(listOf(candidate.endpoint),hints,pin).single()
                        require(candidate==expected)
                    }
                }
            }
            metadata.getJSONObject("workspaces").let { spaces -> spaces.keys().forEach { key ->
                val wire=spaces.getJSONObject(key);val owner=requireNotNull(owners[key])
                val decoded=MobileSnapshotCodec.workspace(wire.toString())
                require(decoded.hostId==owner.host)
                if(decoded.scope==null) require(!owner.requiresReadTarget)
                else require(F31UserDataRulesV2.obj(value(CodexCommandCodec.target(requireNotNull(decoded.scope))))==owner.target)
                wire.optJSONObject("agentConnectionState")?.let { state ->
                    fields(state,setOf("target","phase","transportId","processHealthy","runtimeConnected","historyReadable"))
                    state.optJSONObject("target")?.let { require(sameScope(HostReadCodec.target(CodexCommandCodec.validate(it,"target")),decoded.scope)) }
                }
                wire.optJSONArray("threads")?.objects()?.forEach { row ->
                    row.optJSONObject("readState")?.let { NativeThreadReadingWire.state(row) }
                }
                wire.optJSONObject("sidebar")?.let { sidebar ->
                    fields(sidebar,setOf("version","pinned","recentThreadIds","projectIds","projectThreadIds"))
                    require(sidebar.get("version")==1)
                    sidebar.getJSONArray("pinned").objects().forEach { fields(it,setOf("kind","id"));require(it.get("kind") is String && it.get("id") is String) }
                    for(field in listOf("recentThreadIds","projectIds")) require(sidebar.getJSONArray(field).let { a -> (0 until a.length()).all {a.get(it) is String} })
                    sidebar.getJSONObject("projectThreadIds").let { map -> map.keys().forEach { key -> require(map.getJSONArray(key).let { a -> (0 until a.length()).all {a.get(it) is String} }) } }
                    MobileWorkspaceMetadata.sidebar(wire)
                }
            } }
            val protectedMetadata=F31UserDataRulesV2.project(F31UserDataRulesV2.obj(value(metadata)),owners)
            val folder=File(context.noBackupFilesDir,"mobile-v1")
            val files=linkedMapOf<String,Cipher>();var total=0L
            val originalPorts=mutableListOf<NativeMigrationReadOnlyFile>()
            fun stamp(name: String,destination: MutableMap<String,Cipher> = files): Cipher {
                val nameHash=digest(name);destination[nameHash]?.let {return it}
                require(destination.size<(if(destination===files)64 else 12))
                val file=File(folder,"$nameHash.bin")
                fun settled(){for(s in listOf(".bak",".new"))require(Files.notExists(File(file.path+s).toPath(),NOFOLLOW_LINKS))}
                settled();val initial=Os.lstat(file.path)
                val port=NativeMigrationReadOnlyFile(folder,file,23*1024*1024)
                originalPorts.add(port)
                val read=port.read { input ->
                    val buffer=ByteArray(65536)
                    try {while(true) {val count=input.read(buffer);if(count<0)break;total+=count;require(total<=96L*1024*1024)}} finally {buffer.fill(0)}
                }
                settled();val last=Os.lstat(file.path)
                fun stat(s: android.system.StructStat)=listOf(s.st_dev,s.st_ino,s.st_uid.toLong(),s.st_gid.toLong(),s.st_mode.toLong(),s.st_nlink,s.st_size,s.st_mtime,s.st_ctime)
                require(stat(initial)==stat(last));return Cipher(read.ciphertextSha256,last.st_size,stat(last)).also {destination[nameHash]=it}
            }
            val manifests=linkedMapOf<String,CreationFile>();var remoteReferences=0
            fun add(body: JSONObject) {
                CodexCommandCodec.validate(body,"creationFile")
                val f=CreationFile(body.getString("id"),body.getString("name"),body.getString("mediaType"),body.getLong("sizeBytes"),body.getString("sha256"))
                require(java.util.UUID.fromString(f.id).toString()==f.id)
                require(manifests[f.id]==null || manifests[f.id]==f);manifests[f.id]=f
                require(manifests.size<=32)
            }
            metadata.optJSONObject("draftFiles")?.let { map -> map.keys().forEach { key -> map.getJSONArray(key).objects().forEach(::add) } }
            lateinit var derivedEvidence: Derived
            val derivedFiles=linkedMapOf<String,Cipher>()
            NativeSessionStorage.ExistingReader.open(context,"mobile-v1","agent-hub-mobile-v1").use { reader ->
                for(op in operations) {
                    val intent=JSONObject(op.intent)
                    if(op.kind=="creation")intent.getJSONArray("attachments").objects().forEach(::add)
                    // NativeCodexFlow.source stores local manifests in submission:<root operation id>.
                    if(op.kind in setOf("reply","creation","thread-create")) {
                        val id=op.parentOperationId ?: op.id
                        require(java.util.UUID.fromString(id).toString()==id)
                        val name="submission:$id";val source=reader.optionalText(name,150000)
                        if(source!=null) {
                            val v=JSONObject(source)
                            fields(v,setOf("hostId","target","sourceKey","sourceVersion","text","files","photoSources","model","effort","permissionMode","speed","collaborationMode"))
                            require(v.getString("sourceKey")==op.sourceKey && v.getString("sourceVersion")==op.sourceVersion)
                            val scope=HostReadCodec.target(CodexCommandCodec.validate(v.getJSONObject("target"),"target"))
                            require(v.getString("hostId")==intent.getString("hostId") && clients.any {it.id==v.getString("hostId")&&sameScope(it.scope,scope)})
                            if(op.kind=="reply")require(v.getJSONArray("files").length()==intent.getJSONArray("attachmentIds").length())
                            v.getJSONArray("files").objects().forEach(::add);stamp(name)
                        } else if(op.kind=="reply" && intent.getJSONArray("attachmentIds").length()>0) {
                            // These are remote upload IDs, NEVER local draft IDs. Cannot guess a linkage.
                            error("F31_ORIGINAL_REPLY_SOURCE_UNAVAILABLE")
                        }
                        if(op.kind=="reply")remoteReferences+=intent.getJSONArray("attachmentIds").length()
                    }
                }
                for(file in manifests.values) {
                    val name="draft-file:${file.id}";stamp(name)
                    val original=NativeDraftFiles.record(JSONObject(reader.text(name,1048576)))
                    require(original.file==file)
                    original.payload?.let {stamp(it)}
                }
                derivedEvidence=derived(reader,clients) {name -> stamp(name,derivedFiles)}
            }
            originalPorts.forEach { it.unchanged() }
            return F31UserDataSnapshotV2(protectedMetadata,files.toMap(),manifests.size,remoteReferences,derivedEvidence)
        }
    }
}

/** Values emitted by the real metadata projection, including its unordered trust-policy set.
 * Set typing and sorted canonical members preserve policy equality without treating it as a list.
 * Raw values stay in test-process memory; only the caller's HMAC is written to evidence.
 */
internal object F31MetadataWitnessValues {
    fun value(input: Any?): Any = when (input) {
        null, JSONObject.NULL -> JSONObject.NULL
        is JSONObject, is JSONArray, is String, is Boolean, is Int, is Long -> checkNotNull(input)
        is Map<*, *> -> JSONObject().also { json ->
            require(input.keys.all { it is String })
            for ((key, item) in input) json.put(key as String, value(item))
        }
        is List<*> -> JSONArray(input.map { value(it) })
        is Set<*> -> {
            require(input.size <= 32)
            val members = input.map { item ->
                val policy = F31UserDataRulesV2.obj(item)
                require(policy.keys.all { it in setOf("transportId", "peerFingerprint", "networkKind") })
                require(policy["transportId"] is String && (policy["transportId"] as String).isNotEmpty())
                for (field in listOf("peerFingerprint", "networkKind"))
                    require(policy[field] == null || policy[field] is String)
                value(policy)
            }.sortedBy { NativeChatRecoveryCanonical.value(it) }
            JSONObject().put("__f31Type", "unordered-trust-policy-set-v1").put("items", JSONArray(members))
        }
        else -> error("F31_METADATA_WITNESS_TYPE")
    }
}
