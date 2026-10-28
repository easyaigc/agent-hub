package com.agenthub.android

import com.agenthub.core.RuntimeIdentity
import com.agenthub.core.MobileRules
import com.agenthub.core.SessionAccess
import com.agenthub.core.SessionPolicies
import com.agenthub.core.ReplyRecoveryRules
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.UUID

data class LocalDraftFile(val manifest: CreationFile,val photoSourceId: String?=null)
internal class LocalDraftSourceMissing: IllegalStateException("原附件不存在，未提交")
internal class PreparedLocalDraftFile(val file: LocalDraftFile,val disposable: Boolean,private val release: ()->Unit={}) {
    private val closed=java.util.concurrent.atomic.AtomicBoolean(false)
    fun close() { if(closed.compareAndSet(false,true))release() }
}
/** A fixed source cannot be reused until its prior prepare/commit/cleanup has finished. */
internal object FixedFilePreparationLeases {
    private class Entry(val mutex: Mutex=Mutex(),var users: Int=0)
    private val entries=mutableMapOf<Pair<String,String>,Entry>()
    suspend fun acquire(scope: String,id: String): ()->Unit {
        val key=scope to id
        val entry=synchronized(entries) { entries.getOrPut(key) { Entry() }.also { it.users++ } }
        fun releaseReference() { synchronized(entries) { if(--entry.users==0)check(entries.remove(key)===entry) } }
        try { entry.mutex.lock() }catch(failure: Throwable) { releaseReference();throw failure }
        return { entry.mutex.unlock();releaseReference() }
    }
}
data class SubmissionOrigin(val key: String,val version: String)
data class CodexContent(val text: String,val files: List<LocalDraftFile>,val settings: GenerationSettings,val sharingScope: String? = null)
data class CodexFlowResult(val record: SavedOperation,val thread: MobileThread? = null,val project: MobileProject?=null)

/** Platform storage holds source bytes; submitted snapshots never follow later draft edits. */
class NativeDraftFiles(private val storage: NativeSessionStorage) {
    suspend fun import(name: String,mediaType: String,bytes: ByteArray): LocalDraftFile {
        val result=prepare(name,mediaType,bytes)
        return try { result.file }finally { result.close() }
    }
    /** New file entry is bounded end-to-end; it does not read a complete source or make Base64. */
    suspend fun importFile(name: String,mediaType: String,source: java.io.File): LocalDraftFile {
        val result=prepareFile(name,mediaType,source)
        return try {result.file}finally {result.close()}
    }
    internal suspend fun prepareFile(name: String,mediaType: String,source: java.io.File,id: String?=null,checkpoint: (Int)->Unit={}): PreparedLocalDraftFile {
        return prepareSource(name,mediaType,{
            require(source.isFile&&source.length() in 0..MAXIMUM_BYTES.toLong()) { "单个附件不能超过 20 MiB" }
            val probe=NativeMigrationProbe()
            source.inputStream().use {input->transfer(input,probe)}
            NativeMigrationBlob(probe.size.toInt(),probe.sha256(),probe.prefix()) {out->source.inputStream().use {transfer(it,out)}}
        },id,checkpoint)
    }
    internal suspend fun prepare(name: String,mediaType: String,bytes: ByteArray,id: String?=null): PreparedLocalDraftFile {
        require(bytes.size<=MAXIMUM_BYTES) { "单个附件不能超过 20 MiB" }
        return prepareSource(name,mediaType,{
            val probe=NativeMigrationProbe();probe.write(bytes)
            NativeMigrationBlob(bytes.size,probe.sha256(),probe.prefix()) {out->out.write(bytes)}
        },id,{}) {file->
            try {DraftAttachmentThumbnails.storeIfMissing(storage,file,bytes)}catch(_: OutOfMemoryError) {/* Optional preview cannot discard a committed original. */}
        }
    }
    private suspend fun prepareSource(name: String,mediaType: String,supply: ()->NativeMigrationBlob,id: String?,checkpoint: (Int)->Unit,onNew: (CreationFile)->Unit={}): PreparedLocalDraftFile {
        if(id!=null)validId(id)
        val release=if(id==null) ({})else FixedFilePreparationLeases.acquire(storage.coordinationKey,id)
        var handedOff=false
        val committed=java.util.concurrent.atomic.AtomicReference<Pair<LocalDraftFile,String>?>()
        try {
            val result=withContext(Dispatchers.IO) {
                require(name.isNotBlank()&&name.length<=512&&mediaType.isNotBlank()) { "附件名称或类型无效" }
                // One IO handoff covers scan + encrypted commit. Caller-context cancellation owns
                // the successful receipt until PreparedLocalDraftFile is actually handed back.
                val blob=supply()
                require(blob.size in 0..MAXIMUM_BYTES)
                val manifest=CreationFile(id?:UUID.randomUUID().toString(),name,mediaType,blob.size.toLong(),blob.sha256)
                CodexCommandCodec.validate(manifest.body(),"creationFile")
                storage.operationTransaction {
                    val prior=storage.read("draft-file:${manifest.id}")
                    if(prior!=null) {
                        val previous=JSONObject(prior)
                        require(record(previous).file.body().toString()==manifest.body().toString()) { "原附件清单已改变" }
                        copyVerified(manifest,nullOutputStreamCompat(),previous)
                        PreparedLocalDraftFile(LocalDraftFile(manifest),false,release)
                    }else {
                        val payloadId=UUID.randomUUID().toString()
                        val payload=if(blob.size==0)null else payloadName(manifest.id,payloadId)
                        val metadata=JSONObject().put("version",2).put("file",manifest.body())
                            .put("storage",if(payload==null)"empty-v1" else "binary-chunks-v1")
                        if(payload!=null)metadata.put("payloadId",payloadId)
                        var published=false
                        try {
                            if(payload!=null) {
                                storage.writeBinaryStream(payload,blob.size,blob::verifiedCopy);checkpoint(1)
                                val observed=NativeMigrationProbe();storage.copyBinaryTo(payload,blob.size,observed)
                                require(observed.size==blob.size.toLong()&&observed.sha256()==blob.sha256&&observed.prefix().contentEquals(blob.prefix));checkpoint(2)
                            }
                            checkpoint(0);storage.write("draft-file:${manifest.id}",metadata.toString());published=true
                            committed.set(LocalDraftFile(manifest) to metadata.toString());onNew(manifest)
                            PreparedLocalDraftFile(LocalDraftFile(manifest),true,release)
                        }finally {
                            if(!published&&payload!=null) {
                                // Keep a possibly published original after an ambiguous metadata acknowledgement.
                                val observed=runCatching {storage.read("draft-file:${manifest.id}")}
                                if(observed.isSuccess&&observed.getOrNull()==null)storage.remove(payload)
                            }
                        }
                    }
                }
            }
            handedOff=true;return result
        }catch(_: OutOfMemoryError) {throw IllegalStateException("手机内存不足，未能准备附件；原草稿已保留")}
        finally {
            if(!handedOff) {
                // A known successful write can be retired when cancellation prevents handoff.
                // An ambiguous acknowledgement never enters this receipt and keeps its originals.
                try {withContext(NonCancellable+Dispatchers.IO) {
                    committed.get()?.let {(file,metadata)->runCatching {storage.operationTransaction {
                        if(storage.read("draft-file:${file.manifest.id}")==metadata)discardPrepared(file)
                    }}}
                }}finally {release()}
            }
        }
    }
    internal suspend fun inboxOwnedFile(id: String): LocalDraftFile? = withContext(Dispatchers.IO) {
        validId(id);val raw=storage.read("draft-file:$id")?:return@withContext null
        val saved=record(JSONObject(raw));require(saved.file.id==id) { "原附件清单已改变" };LocalDraftFile(saved.file)
    }
    internal suspend fun discardInboxOwned(file: LocalDraftFile) {
        val release=FixedFilePreparationLeases.acquire(storage.coordinationKey,file.manifest.id)
        try {withContext(Dispatchers.IO) {discardPrepared(file);check(storage.read("draft-file:${file.manifest.id}")==null) { "本地文件清理未完成" }}}
        finally {release()}
    }
    /** Called only for a freshly prepared file that has never entered a draft or operation. */
    internal fun discardPrepared(file: LocalDraftFile)=storage.operationTransaction {
        val raw=storage.read("draft-file:${file.manifest.id}")
        val saved=raw?.let {record(JSONObject(it))}
        if(saved!=null)require(saved.file.body().toString()==file.manifest.body().toString()) { "准备中的附件清单已变化" }
        DraftAttachmentThumbnails.discardPrepared(storage,file.manifest)
        storage.remove("draft-file:${file.manifest.id}")
        saved?.payload?.let(storage::remove)
    }
    suspend fun thumbnail(file: CreationFile): ByteArray? = withContext(Dispatchers.IO) {
        if(!file.mediaType.startsWith("image/"))return@withContext null
        val cached=DraftAttachmentThumbnails.read(storage,file)
        if(cached.found)return@withContext cached.png
        if(file.sizeBytes>DraftAttachmentThumbnails.MAX_LEGACY_SOURCE)return@withContext null
        bytes(file);DraftAttachmentThumbnails.read(storage,file).png
    }
    /** The consumer must publish only after all authentication and digest checks return successfully. */
    internal suspend fun copyTo(file: CreationFile,output: java.io.OutputStream)=withContext(Dispatchers.IO) {copyVerified(file,output)}
    private fun copyVerified(file: CreationFile,output: java.io.OutputStream,original: JSONObject?=null)=storage.operationTransaction {
        val value=original?:JSONObject(storage.read("draft-file:${file.id}")?:throw LocalDraftSourceMissing())
        val saved=record(value)
        require(saved.file.body().toString()==file.body().toString()) { "原附件清单已改变" }
        val probe=NativeMigrationProbe(output)
        if(saved.legacy) {
            val bytes=android.util.Base64.decode(value.getString("bytes"),android.util.Base64.NO_WRAP)
            try {probe.write(bytes)}finally {bytes.fill(0)}
        }else saved.payload?.let {storage.copyBinaryTo(it,file.sizeBytes.toInt(),probe)}
        require(probe.size==file.sizeBytes&&probe.sha256()==file.sha256) { "原附件内容已改变，未提交" }
    }
    /** Existing upload callers still explicitly request a complete ByteArray; migration never calls this. */
    suspend fun bytes(file: CreationFile): ByteArray = withContext(Dispatchers.IO) {
        storage.operationTransaction {
            val value=JSONObject(storage.read("draft-file:${file.id}")?:throw LocalDraftSourceMissing());val saved=record(value)
            require(saved.file.body().toString()==file.body().toString()) { "原附件清单已改变" }
            val bytes=if(saved.legacy)android.util.Base64.decode(value.getString("bytes"),android.util.Base64.NO_WRAP)
                else saved.payload?.let {storage.readBinary(it,file.sizeBytes.toInt(),MAXIMUM_BYTES)}?:ByteArray(0)
            try {
                require(bytes.size.toLong()==file.sizeBytes&&sha(bytes)==file.sha256) { "原附件内容已改变，未提交" }
                DraftAttachmentThumbnails.storeIfMissing(storage,file,bytes);bytes
            }catch(error: Throwable) {bytes.fill(0);throw error}
        }
    }
    companion object {
        internal const val MAXIMUM_BYTES=20*1024*1024
        internal data class StoredRecord(val file: CreationFile,val payload: String?,val legacy: Boolean)
        private fun validId(id: String) {require(UUID.fromString(id).toString()==id) { "附件标识无效" }}
        internal fun payloadName(id: String,generation: String): String {validId(id);validId(generation);return "draft-payload-v2:$id:$generation"}
        internal fun record(value: JSONObject): StoredRecord {
            val body=value.getJSONObject("file");CodexCommandCodec.validate(body,"creationFile")
            val file=CreationFile(body.getString("id"),body.getString("name"),body.getString("mediaType"),body.getLong("sizeBytes"),body.getString("sha256"));validId(file.id)
            require(file.sizeBytes in 0..MAXIMUM_BYTES.toLong())
            if(!value.has("version")) {
                require(value.keys().asSequence().toSet()==setOf("file","bytes")&&value.get("bytes") is String)
                require(value.getString("bytes").length<=((MAXIMUM_BYTES+2)/3)*4)
                return StoredRecord(file,null,true)
            }
            require(value.get("version")==2)
            if(value.get("storage")=="empty-v1") {
                require(value.keys().asSequence().toSet()==setOf("version","file","storage")&&file.sizeBytes==0L&&file.sha256==sha(ByteArray(0)))
                return StoredRecord(file,null,false)
            }
            require(value.keys().asSequence().toSet()==setOf("version","file","storage","payloadId")&&value.get("storage")=="binary-chunks-v1"&&file.sizeBytes>0)
            return StoredRecord(file,payloadName(file.id,value.getString("payloadId")),false)
        }
        private fun transfer(input: java.io.InputStream,output: java.io.OutputStream) {
            val buffer=ByteArray(65536);var size=0
            try {while(true) {val count=input.read(buffer);if(count<0)break;require(count<=MAXIMUM_BYTES-size) { "单个附件不能超过 20 MiB" };output.write(buffer,0,count);size+=count}}
            finally {buffer.fill(0)}
        }
        private fun sha(bytes: ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}

/** Executes one explicitly requested operation. Queries never replay a model input. */
class NativeCodexFlow(private val storage: NativeSessionStorage,private val reads: HostReads,
    private val commands: CodexCommands) {
    val journal=NativeOperationJournal(storage)
    val files=NativeDraftFiles(storage)
    // Only the foreground session can supply a current, generation-bound owner
    // observation. Persisted history and standalone flow callers cannot do so.
    internal var confirmedReplyReads: ((MobileClient,String)->Pair<MobileWorkspace,MobileHistory>?)?=null
    private class OpenObservation { val mutex=Mutex();var readers=0 }
    private val openObservations=mutableMapOf<Pair<String,String>,OpenObservation>()
    private fun matches(client: MobileClient,workspace: MobileWorkspace) = workspace.hostId==client.id && sameScope(workspace.scope,client.scope)
    private suspend fun authority(client: MobileClient,thread: String?,projectId: String?,guard: ()->Boolean,reuseConfirmed: Boolean=false): Pair<MobileWorkspace,MobileHistory?> {
        check(guard()) { "目标或草稿已改变，未提交" }
        if(reuseConfirmed&&thread!=null)confirmedReplyReads?.invoke(client,thread)?.let { (workspace,history) ->
            require(matches(client,workspace)&&workspace.runtimeConnected==true&&"commands:target-bound:v1" in workspace.capabilities&&
                history.hostId==client.id&&history.threadId==thread&&sameScope(history.scope,client.scope)&&history.runtime?.canControl==true) { "当前聊天运行时证明已变化，未提交" }
            require(projectId==null||workspace.projects.any { it.id==projectId }) { "项目已不存在" }
            check(guard()) { "目标或草稿已改变，未提交" }
            return workspace to history
        }
        val workspace=reads.workspace(client)
        require(matches(client,workspace) && workspace.runtimeConnected!=false && "commands:target-bound:v1" in workspace.capabilities) { "电脑尚未提供可核对目标的执行连接" }
        require(projectId==null || workspace.projects.any { it.id==projectId }) { "项目已不存在" }
        val history=thread?.let { reads.history(client,it) }
        require(history==null || history.hostId==client.id && history.threadId==thread && sameScope(history.scope,client.scope)) { "聊天运行时身份不匹配" }
        check(guard()) { "目标或草稿已改变，未提交" }
        return workspace to history
    }
    private fun access(client: MobileClient,w: MobileWorkspace,h: MobileHistory?,project: String?) = SessionAccess(false,client.online,w.runtimeConnected,w.capabilities,h!=null,project!=null,project==null,h?.runtime?.canControl==true,false)
    private suspend fun source(client: MobileClient,id: String,origin: SubmissionOrigin,content: CodexContent) {
        require(MobileRules.hasContent(content.text,content.files.size) && content.text.length<=100_000 && content.files.size<=10) { "消息为空或超出文字／附件限制" }
        val body=content.settings.append(JSONObject().put("hostId",client.id).put("target",CodexCommandCodec.target(client.scope!!))
            .put("sourceKey",origin.key).put("sourceVersion",origin.version).put("text",content.text).put("files",JSONArray(content.files.map { it.manifest.body() })).put("photoSources",NativePhotoOrigins.encode(content.files)))
        require(body.toString().toByteArray(Charsets.UTF_8).size<=150_000) { "消息超出电脑请求大小限制" }
        withContext(Dispatchers.IO) { storage.write("submission:$id",body.toString()) }
    }
    suspend fun submit(client: MobileClient,threadId: String?,projectId: String?,origin: SubmissionOrigin,
        content: CodexContent,mode: String,expectedTurnId: String?,expectedPlanId: String? = null,
        guard: ()->Boolean,progress: (String)->Unit = {},operationId: String=UUID.randomUUID().toString()): CodexFlowResult {
        val id=operationId
        for(target in client.operationTargets(threadId))require(journal.records(target).none{it.id==id&&it.migrationReadOnly}) { "迁入的原操作只能查询回执，原输入未改变" }
        source(client,id,origin,content)
        progress("核对电脑与聊天…")
        var (workspace,history)=authority(client,threadId,projectId,guard,reuseConfirmed=true)
        val policy=access(client,workspace,history,projectId)
        require(SessionPolicies.compose(policy) && (threadId!=null || SessionPolicies.createThread(policy)) &&
            (threadId!=null || projectId!=null || SessionPolicies.createProjectless(policy))) { "当前连接不能创建或发送" }
        validateReplyAuthority(workspace,history,mode,expectedTurnId,expectedPlanId)
        if(threadId==null && "threads:create:staged" in workspace.capabilities) {
            val intent=CreationIntent(client.id,id,projectId,content.text,content.files.map { it.manifest },content.settings)
            var record=journal.prepare(client,null,"creation",intent.body(),origin.key,origin.version,sharingScope=content.sharingScope)
            try {
                check(guard()); record=journal.markPreparing(record.target,id); progress("电脑准备聊天…")
                val prepared=commands.prepareCreation(client,intent)
                record=journal.observed(record.target,id,prepared.phase,encodeCreation(prepared),prepared.error)
                if(prepared.phase!="prepared") return CodexFlowResult(record,prepared.thread)
                return finishCreation(client,record,content.files,guard,progress)
            } catch(failure: Exception) { return CodexFlowResult(journal.uncertain(record.target,id,failure.message)) }
        }
        var thread=threadId
        var created: MobileThread?=null
        if(thread==null) {
            val createId=commands.creationOperationId(client,id)
            val intent=JSONObject().put("hostId",client.id).put("clientOperationId",createId).put("projectId",projectId ?: JSONObject.NULL)
            var record=journal.prepare(client,null,"thread-create",intent,origin.key,origin.version,if(createId!=id)id else null,sharingScope=content.sharingScope)
            try {
                check(guard()); record=journal.markSubmitting(record.target,createId); progress("创建聊天…")
                created=commands.createThread(client,projectId,createId)
                journal.observed(record.target,createId,"confirmed",encodeThread(created)); thread=created.id
                val fresh=authority(client,thread,projectId,guard,reuseConfirmed=true); workspace=fresh.first; history=fresh.second
            } catch(failure: Exception) { return CodexFlowResult(journal.uncertain(record.target,createId,failure.message)) }
        }
        val exactThread=thread!!
        if("runtime:control-required" in workspace.capabilities && history?.runtime?.canControl!=true) {
            require(SessionPolicies.desktop(policy,"desktop:open")) { "原桌面尚未开放主动打开" }
            val openId=UUID.randomUUID().toString()
            var opening=journal.prepare(client,exactThread,"open",JSONObject().put("hostId",client.id).put("threadId",exactThread).put("clientOperationId",openId),origin.key,origin.version,id)
            try {
                check(guard()); opening=journal.markSubmitting(opening.target,openId); progress("正在原桌面打开…")
                var result=commands.open(client,exactThread,openId)
                opening=journal.observed(opening.target,openId,result.phase,encodeOpen(result),result.error)
                // Opening is an accepted asynchronous command. Observe that same
                // receipt without another POST; uncertain results remain query-only.
                val deadline=System.nanoTime()+35_000_000_000L
                while(result.phase=="opening" && guard() && System.nanoTime()<deadline) {
                    delay(350)
                    opening=queryOpen(client,opening).record
                    result=CodexCommandCodec.open(JSONObject(opening.result!!))
                }
                if(result.phase!="opened") return CodexFlowResult(opening,created)
                val fresh=authority(client,exactThread,projectId,guard,reuseConfirmed=true); workspace=fresh.first; history=fresh.second
                require(history?.runtime?.canControl==true && SessionPolicies.writable(access(client,workspace,history,projectId))) { "原 owner 尚未确认控制权" }
            } catch(failure: Exception) { return CodexFlowResult(journal.uncertain(opening.target,openId,failure.message),created) }
        }
        require(SessionPolicies.writable(access(client,workspace,history,projectId))) { "当前聊天不能执行" }
        val attachmentIds=content.files.mapIndexed { index,file ->
            check(guard()) { "目标或草稿已改变，未提交" }; progress("上传附件 ${index+1}/${content.files.size} · ${file.manifest.name}")
            commands.upload(client,exactThread,file.manifest,files.bytes(file.manifest)).id
        }
        // The final request is immutable, including the returned IDs of the exact source bytes.
        val reply=ReplyIntent(client.id,exactThread,id,content.text,attachmentIds,mode,expectedTurnId,expectedPlanId,content.settings)
        var record=journal.prepare(client,exactThread,"reply",reply.body(),origin.key,origin.version,sharingScope=content.sharingScope)
        try {
            check(guard()) { "目标或草稿已改变，未提交" }
            val fresh=authority(client,exactThread,projectId,guard,reuseConfirmed=true)
            require(SessionPolicies.writable(access(client,fresh.first,fresh.second,projectId))) { "控制权已变化，未提交" }
            validateReplyAuthority(fresh.first,fresh.second,mode,expectedTurnId,expectedPlanId)
            record=journal.markSubmitting(record.target,id); progress("提交给 ${client.name}…")
            val receipt=commands.send(client,reply)
            return CodexFlowResult(journal.observed(record.target,id,receipt.state,encodeReceipt(receipt),receipt.error),created)
        } catch(failure: Exception) {
            val failed=if(record.submissionAttempted) journal.uncertain(record.target,id,failure.message)
                else journal.replyNotSubmitted(record.target,id,failure.message)
            return CodexFlowResult(failed,created)
        }
    }
    private fun validateReplyAuthority(workspace: MobileWorkspace,history: MobileHistory?,mode: String,expectedTurnId: String?,expectedPlanId: String?) {
        require(expectedPlanId==null || history?.runtime?.proposedPlan?.id==expectedPlanId && !SessionPolicies.running(history.runtime.turnId,history.runtime.state)) { "计划已变化，请重新查看" }
        if(mode in listOf("queue","steer")) require(mode in workspace.capabilities && expectedTurnId!=null && history?.turnId==expectedTurnId && history.runtime?.turnId==expectedTurnId &&
            SessionPolicies.running(history.runtime?.turnId,history.runtime?.state)) { "任务或执行能力已变化，未排队或引导" }
    }
    private suspend fun finishCreation(client: MobileClient,record: SavedOperation,sourceFiles: List<LocalDraftFile>,
        guard: ()->Boolean,progress: (String)->Unit,explicitContinuation: Boolean=false): CodexFlowResult {
        require(record.kind=="creation" && mayContinueCreation(record) && (!record.submissionAttempted || explicitContinuation))
        require(record.target in client.operationTargets(null)) { "原创建记录不属于当前电脑目标" }
        var current=record
        try {
            creationAuthority(client,record,guard)
            val uploaded=record.result?.let { CodexCommandCodec.creation(JSONObject(it)).uploaded }.orEmpty()
            sourceFiles.filter { it.manifest.id !in uploaded }.forEachIndexed { index,file ->
                check(guard()); progress("上传首条附件 ${index+1}/${sourceFiles.size} · ${file.manifest.name}")
                commands.uploadCreation(client,record.id,file.manifest,files.bytes(file.manifest))
            }
            // Preparation and upload can outlive a capability or project change.
            // Recheck the complete target before making the first input attempt.
            creationAuthority(client,record,guard)
            if(record.submissionAttempted) {
                check(guard())
                val fresh=commands.creation(client,record.id,inspectOnly=true)
                current=journal.observed(record.target,record.id,fresh.phase,encodeCreation(fresh),fresh.error)
                if(!mayContinueCreation(current)) return CodexFlowResult(current,fresh.thread)
            }
            check(guard())
            current=if(current.submissionAttempted) journal.markCreationRetrySubmitting(record.target,record.id) else journal.markSubmitting(record.target,record.id)
            progress("提交新聊天…")
            val result=commands.submitCreation(client,record.id)
            return CodexFlowResult(journal.observed(record.target,record.id,result.phase,encodeCreation(result),result.error),result.thread)
        } catch(failure: Exception) { return CodexFlowResult(journal.uncertain(current.target,current.id,failure.message)) }
    }
    private suspend fun creationAuthority(client: MobileClient,record: SavedOperation,guard: ()->Boolean) {
        val project=JSONObject(record.intent).optional("projectId")
        val (workspace,history)=authority(client,null,project,guard)
        val policy=access(client,workspace,history,project)
        require("threads:create:staged" in workspace.capabilities && SessionPolicies.compose(policy) &&
            SessionPolicies.createThread(policy) && (project!=null || SessionPolicies.createProjectless(policy))) { "电脑已撤回创建能力，原首条尚未提交" }
    }
    suspend fun query(client: MobileClient,record: SavedOperation): CodexFlowResult {
        require(record.target in client.operationTargets(if(record.kind in listOf("reply","open","fork")) JSONObject(record.intent).getString("threadId") else null))
        return when(record.kind) {
            "chat-action" -> NativeChatActionFlow(journal,commands).query(client,record)
            "fork" -> NativeThreadForkFlow(journal,reads,commands).query(client,record)
            "project-create" -> commands.createdProject(client,record.id).let { CodexFlowResult(journal.observed(record.target,record.id,"confirmed",encodeProject(it)),project=it) }
            "reply" -> queryReply(client,record)
            "thread-create" -> commands.createdThread(client,record.id).let { CodexFlowResult(journal.observed(record.target,record.id,"confirmed",encodeThread(it)),it) }
            "creation" -> commands.creation(client,record.id,inspectOnly=true).let { CodexFlowResult(journal.observed(record.target,record.id,it.phase,encodeCreation(it),it.error),it.thread) }
            "open" -> queryOpen(client,record)
            else -> throw CodexCommandError("此创建结果需在电脑核对；原操作不会重放")
        }
    }
    /** Foreground/background observers serialize by full target and original operation.
     * Gates exist only while readers are active; unrelated targets never wait on one network read. */
    private suspend fun queryOpen(client: MobileClient,record: SavedOperation): CodexFlowResult {
        val key=record.target to record.id
        val gate=synchronized(openObservations) { openObservations.getOrPut(key) { OpenObservation() }.also { it.readers++ } }
        return try { gate.mutex.withLock {
            val current=journal.records(record.target).single { it.id==record.id }
            require(current.kind=="open" && current.intentSha256==record.intentSha256) { "原打开意图不匹配，只能查询原记录" }
            if(current.phase=="opened" && current.result!=null) CodexFlowResult(current)
            else {
                val result=commands.open(client,JSONObject(current.intent).getString("threadId"),current.id,queryOnly=true)
                CodexFlowResult(journal.observed(current.target,current.id,result.phase,encodeOpen(result),result.error))
            }
        } } finally { synchronized(openObservations) { if(--gate.readers==0) openObservations.remove(key) } }
    }
    suspend fun continuePrepared(client: MobileClient,record: SavedOperation,guard: ()->Boolean,progress: (String)->Unit): CodexFlowResult {
        require(record.kind=="creation" && !record.detached && mayContinueCreation(record)) { "原首条结果待核对，只能查询原记录" }
        val manifest=JSONObject(record.intent).getJSONArray("attachments").objects().map { f -> LocalDraftFile(CreationFile(f.getString("id"),f.getString("name"),f.getString("mediaType"),f.getLong("sizeBytes"),f.getString("sha256"))) }
        return finishCreation(client,record,manifest,guard,progress,explicitContinuation=true)
    }
    fun mayContinueCreation(record: SavedOperation): Boolean {
        if(record.kind!="creation" || record.detached || record.migrationReadOnly)return false
        val result=record.result?.let { CodexCommandCodec.creation(JSONObject(it)) }
        return ReplyRecoveryRules.mayContinueCreation(record.phase,record.submissionAttempted,result?.diagnostic?.disposition,
                result?.preparation?.preparationPhase,result?.preparation?.preparedThreadId)
    }
    private suspend fun queryReply(client: MobileClient,record: SavedOperation): CodexFlowResult {
        val thread=JSONObject(record.intent).getString("threadId")
        try {
            val receipt=commands.receipt(client,thread,record.id)
            return CodexFlowResult(journal.observed(record.target,record.id,receipt.state,encodeReceipt(receipt),receipt.error))
        } catch(cancel: CancellationException) { throw cancel }
        catch(missing: Exception) {
            val current=journal.records(record.target).single { it.id==record.id }
            val recovery=current.replyRecovery
            if(recovery==null || !recovery.matchesObservation(client,current)) throw missing
            try {
                val inspection=commands.replyDeliveryStatus(recovery.capturedObservationClient(client,current),thread,record.id)
                if(inspection.getString("status")=="accepted" && inspection.optJSONObject("receipt")!=null) {
                    val receipt=CodexCommandCodec.receipt(inspection.getJSONObject("receipt"))
                    return CodexFlowResult(journal.observed(record.target,record.id,receipt.state,encodeReceipt(receipt),receipt.error))
                }
                // Read-only accepted evidence is valid even after an uncertain receipt
                // or consumed retry. Neither case may acquire negative retry authority.
                if(!recovery.matches(client,current) || recovery.consumed || current.result!=null)throw missing
                return CodexFlowResult(journal.observeReplyInspection(client,record.target,record.id,inspection))
            } catch(cancel: CancellationException) { throw cancel }
            catch(failure: Exception) { journal.invalidateReplyInspection(record.target,record.id);throw failure }
        }
    }
    /** A user action only. A fresh authority read is followed by durable one-time consumption. */
    suspend fun retryReply(client: MobileClient,record: SavedOperation,guard: ()->Boolean,progress: (String)->Unit): CodexFlowResult {
        var current=journal.records(record.target).single { it.id==record.id }
        val recovery=current.replyRecovery ?: error("旧操作未捕获恢复授权，只能查询原回执")
        require(recovery.mayRetry(client,current)) { "原消息仍待核对，不能重新提交" }
        val captured=recovery.capturedClient(client,current)
        val input=CodexCommandCodec.validate(JSONObject(current.intent),"replyRequest")
        val intent=ReplyIntent(input.getString("hostId"),input.getString("threadId"),current.id,input.getString("text"),input.getJSONArray("attachmentIds").strings(),input.getString("mode"),
            input.optional("expectedTurnId"),input.optional("expectedPlanId"),GenerationSettings(input.optional("model"),input.optional("effort"),input.optional("permissionMode"),input.optional("speed"),input.optional("collaborationMode")))
        val (workspace,history)=authority(captured,intent.threadId,null,guard)
        require(SessionPolicies.writable(access(captured,workspace,history,workspace.threads.firstOrNull { it.id==intent.threadId }?.projectId))) { "当前原桌面尚不能执行" }
        validateReplyAuthority(workspace,history,intent.mode,intent.expectedTurnId,intent.expectedPlanId)
        if(recovery.phase=="strong-not-accepted") {
            try {
                val fresh=commands.replyDeliveryStatus(captured,intent.threadId,current.id)
                current=journal.observeReplyInspection(client,current.target,current.id,fresh)
            } catch(cancel: CancellationException) { throw cancel }
            catch(failure: Exception) { journal.invalidateReplyInspection(current.target,current.id);throw failure }
        }
        check(guard()) { "当前目标已变化，没有重试原消息" }
        current=journal.consumeReplyRetry(client,current.target,current.id)
        progress("提交原消息…")
        try {
            check(guard()) { "当前目标已变化，仅查询原消息" }
            val receipt=commands.send(captured,intent)
            return CodexFlowResult(journal.observed(current.target,current.id,receipt.state,encodeReceipt(receipt),receipt.error))
        } catch(cancel: CancellationException) { journal.uncertain(current.target,current.id,"原消息提交结果待核对");throw cancel }
        catch(failure: Exception) { return CodexFlowResult(journal.uncertain(current.target,current.id,failure.message)) }
    }
    suspend fun originalReplyContent(client: MobileClient,record: SavedOperation): CodexContent = withContext(Dispatchers.IO) {
        val current=journal.records(record.target).single { it.id==record.id }
        require(current.replyRecovery?.mayRetry(client,current)==true) { "原输入未提交尚未得到确认，原消息和当前草稿均已保留" }
        val content=replyRecordContent(client,current)
        content.files.forEach { files.bytes(it.manifest) }
        content
    }
    /** Immutable display snapshot; it neither grants editing nor reads/uploads attachment bytes. */
    suspend fun replyRecordContent(client: MobileClient,record: SavedOperation): CodexContent = withContext(Dispatchers.IO) {
        val current=journal.records(record.target).single { it.id==record.id }
        require(current.kind=="reply" && current.intentSha256==record.intentSha256 &&
            current.target in client.operationTargets(JSONObject(current.intent).getString("threadId"))) { "原记录目标或不可变内容不匹配" }
        val source=JSONObject(storage.read("submission:${record.id}") ?: error("原始附件或草稿快照已不可用，原记录仍保留"))
        require(source.getString("hostId")==client.id && source.getString("sourceKey")==current.sourceKey && source.getString("sourceVersion")==current.sourceVersion)
        val sourceTarget=HostReadCodec.target(source.getJSONObject("target"))!!
        require(RuntimeIdentity.matches(sourceTarget,client.scope)) { "原附件快照属于其他运行目标" }
        val capturedTarget=CodexCommandCodec.target(sourceTarget).toString()
        val input=JSONObject(current.intent)
        val manifests=source.getJSONArray("files").objects().map { file ->
            CodexCommandCodec.validate(file,"creationFile")
            NativePhotoOrigins.attach(LocalDraftFile(CreationFile(file.getString("id"),file.getString("name"),file.getString("mediaType"),file.getLong("sizeBytes"),file.getString("sha256"))),source.optJSONObject("photoSources"))
        }
        require(manifests.size==input.getJSONArray("attachmentIds").length())
        val settings=GenerationSettings(source.optional("model"),source.optional("effort"),source.optional("permissionMode"),source.optional("speed"),source.optional("collaborationMode"))
        val captured=ReplyIntent(client.id,input.getString("threadId"),current.id,source.getString("text"),input.getJSONArray("attachmentIds").strings(),input.getString("mode"),
            input.optional("expectedTurnId"),input.optional("expectedPlanId"),settings)
        val original=ReplyRecoveryRules.canonicalPayload(capturedTarget,current.intent)
        require(original!=null && original==ReplyRecoveryRules.canonicalPayload(capturedTarget,captured.body().toString())) { "原内容与提交指纹不匹配，当前草稿未改变" }
        CodexContent(captured.text,manifests,settings)
    }
    suspend fun detach(client: MobileClient,record: SavedOperation): SavedOperation {
        // Local persistence releases waiting even when the Host cannot acknowledge it.
        val detached=journal.detach(record.target,record.id)
        if(record.kind=="creation"&&!record.migrationReadOnly) try {
            val result=commands.detachCreation(client,record.id)
            return journal.observed(record.target,record.id,result.phase,encodeCreation(result),result.error)
        } catch(cancel: CancellationException) { throw cancel }
        catch(failure: Exception) {
            return journal.detachedCreationFailure(detached,failure.message ?: "结束等待通知未确认，原操作仍保留")
        }
        return detached
    }
}

internal fun encodeThread(t: MobileThread)=JSONObject().put("id",t.id).put("hostId",t.hostId).put("title",t.title).put("state",t.state).put("projectId",t.projectId ?: JSONObject.NULL).put("projectIds",JSONArray(t.projectIds)).put("hasUnreadTurn",t.unread).put("readState",t.readState?.json())
internal fun encodeReceipt(r: DeliveryReceipt)=JSONObject().put("hostId",r.hostId).put("threadId",r.threadId).put("clientMessageId",r.operationId).put("state",r.state).put("turnId",r.turnId ?: JSONObject.NULL).put("error",r.error ?: JSONObject.NULL).put("supersededByTurnId",r.supersededByTurnId ?: JSONObject.NULL).put("text",r.text ?: JSONObject.NULL).put("attachmentIds",JSONArray(r.attachmentIds))
    .put("noticeContext",r.noticeContext ?: JSONObject.NULL).put("queueSource",r.queueSource ?: JSONObject.NULL)
    .put("queuePresent",r.queuePresent ?: JSONObject.NULL).put("canExecuteNow",r.canExecuteNow ?: JSONObject.NULL)
    .put("queueDisposition",r.queueDisposition ?: JSONObject.NULL).put("queueSyncError",r.queueSyncError ?: JSONObject.NULL).put("laterTurnId",r.laterTurnId ?: JSONObject.NULL)
internal fun encodeCreation(r: DesktopCreation)=JSONObject().put("hostId",r.hostId).put("clientOperationId",r.operationId).put("projectId",r.projectId ?: JSONObject.NULL).put("phase",r.phase).put("threadId",r.threadId ?: JSONObject.NULL).put("error",r.error ?: JSONObject.NULL).put("uploaded",JSONArray(r.uploaded)).put("thread",r.thread?.let(::encodeThread) ?: JSONObject.NULL).put("detached",r.detached).apply { r.preparation?.let { put("preparation",it.body()) } }
internal fun encodeOpen(r: DesktopOpen)=JSONObject().put("hostId",r.hostId).put("threadId",r.threadId).put("clientOperationId",r.operationId).put("phase",r.phase).put("error",r.error ?: JSONObject.NULL)

internal fun encodeProject(p: MobileProject)=JSONObject().put("id",p.id).put("hostId",p.hostId).put("name",p.name).put("roots",JSONArray(p.roots))
