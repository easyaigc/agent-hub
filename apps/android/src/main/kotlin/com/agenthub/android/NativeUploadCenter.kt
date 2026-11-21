package com.agenthub.android

import android.os.SystemClock
import androidx.compose.runtime.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import org.json.JSONObject

internal data class NativeUploadRow(val key:String,val target:NativeUploadTarget,val manifest:NativeUploadManifest,
    val sourceFileId:String?,val phase:String="queued",val receiptWire:String?=null,val pending:String?=null,
    val paused:Boolean=false,val stop:Boolean=false,val cancelRequested:Boolean=false,val capability:NativeUploadCapability?=null,
    val batchId:String?=null,val index:Int=0,val count:Int=1,val batchBytes:Long?=null,val error:String?=null,
    val legacy:Boolean=false,val legacyResultWire:String?=null,val rate:Double=0.0,val sample:Long=0) {
    val receipt get()=receiptWire?.let {NativeUploadReceipt.decode(StrictMigrationJson.parse(it.toByteArray(Charsets.UTF_8)),manifest)}
    val committed get()=receipt?.committedBytes?:0L
    val terminal get()=phase in setOf("completed","cancelled")
    fun json()=JSONObject().put("key",key).put("target",target.json()).put("manifest",manifest.json())
        .put("sourceFileId",sourceFileId?:JSONObject.NULL).put("phase",phase).put("receipt",receiptWire?:JSONObject.NULL)
        .put("pending",pending?:JSONObject.NULL).put("paused",paused).put("stop",stop).put("cancelRequested",cancelRequested)
        .put("protocol",capability?.protocol?:JSONObject.NULL).put("maxChunkBytes",capability?.maxChunkBytes?:JSONObject.NULL)
        .put("remoteCancel",capability?.remoteCancel?:false).put("batchId",batchId?:JSONObject.NULL)
        .put("index",index).put("count",count).put("batchBytes",batchBytes?:JSONObject.NULL).put("legacy",legacy).put("legacyResult",legacyResultWire?:JSONObject.NULL)
    companion object {
        fun decode(v:JSONObject):NativeUploadRow {
            require(v.keys().asSequence().toSet()==setOf("key","target","manifest","sourceFileId","phase","receipt","pending","paused","stop","cancelRequested","protocol","maxChunkBytes","remoteCancel","batchId","index","count","batchBytes","legacy","legacyResult"))
            for(k in listOf("paused","stop","cancelRequested","remoteCancel","legacy"))require(v.get(k) is Boolean)
            for(k in listOf("sourceFileId","receipt","pending","protocol","batchId","legacyResult"))require(v.get(k)==JSONObject.NULL||v.get(k) is String)
            val target=NativeUploadTarget.decode(v.getJSONObject("target"))
            val m=NativeUploadManifest.decode(v.getJSONObject("manifest"));require(m.file.hostId==target.clientId)
            val cap=v.optional("protocol")?.let {NativeUploadCapability(uploadLong(v.get("maxChunkBytes")).also {n->require(n in 1..1024*1024)}.toInt(),v.getBoolean("remoteCancel"),it)}
            require(cap!=null||v.isNull("maxChunkBytes")&&!v.getBoolean("remoteCancel"))
            val phase=v.getString("phase");require(phase in setOf("queued","uploading","verifying","waiting","paused","stopped","unknown","cancel-pending","completed","cancelled"))
            val row=NativeUploadRow(v.getString("key"),target,m,v.optional("sourceFileId"),phase,v.optional("receipt"),v.optional("pending"),
                v.getBoolean("paused"),v.getBoolean("stop"),v.getBoolean("cancelRequested"),cap,v.optional("batchId"),uploadLong(v.get("index")).also {require(it<32)}.toInt(),uploadLong(v.get("count")).also {require(it in 1..32)}.toInt(),
                if(v.isNull("batchBytes"))null else uploadLong(v.get("batchBytes")),legacy=v.getBoolean("legacy"),legacyResultWire=v.optional("legacyResult"))
            require(row.key==key(target,m)&&row.index>=0&&row.count in 1..32&&row.index<row.count)
            require(row.pending==null||row.pending in setOf("prepare","chunk","complete","cancel"))
            row.receipt?.result?.let {NativeUploadJournalCodec.resultFields(m,it)}
            require(row.legacy||row.legacyResultWire==null)
            if(row.legacy) {
                require(row.receiptWire==null&&row.pending==null&&row.capability==null&&row.phase!="cancelled")
                if(row.phase=="completed") {
                    val result=StrictMigrationJson.parse(checkNotNull(row.legacyResultWire).toByteArray(Charsets.UTF_8))
                    validateNativeUploadResult(m,result);NativeUploadJournalCodec.resultFields(m,result)
                } else require(row.legacyResultWire==null)
            } else if(row.terminal)require(row.receipt?.phase==row.phase)
            return row
        }
        fun key(t:NativeUploadTarget,m:NativeUploadManifest)=ComputerFileCodec.hash((t.key+"\u0000"+t.certificate.orEmpty()+"\u0000"+m.purpose+"\u0000"+m.id+"\u0000"+m.threadId.orEmpty()).toByteArray())
    }
}

/** One encrypted journal, exact original sources, confirmed-byte progress, no business dispatch.
 * Restore never starts network or retries a mutating operation. All controls run on Main.
 */
internal interface NativeUploadJournal {
    fun read():String?
    fun write(value:String)
    fun requireNetworkOwner()
}
internal class NativeUploadCenter(private val journal:NativeUploadJournal,private val scope:CoroutineScope,
    private val clientFor:(NativeUploadTarget)->MobileClient?,private val leaseFor:(MobileClient)->()->Boolean,
    private val resolveSource:suspend (MobileClient,NativeUploadRow)->((Long,Int)->ByteArray),
    private val commands:()->AgentCommands) {
    constructor(storage:NativeSessionStorage,scope:CoroutineScope,clientFor:(NativeUploadTarget)->MobileClient?,
        leaseFor:(MobileClient)->()->Boolean,resolveSource:suspend (MobileClient,NativeUploadRow)->((Long,Int)->ByteArray),commands:()->AgentCommands):
        this(object:NativeUploadJournal {
            override fun read()=storage.read(RECORD)
            override fun write(value:String) { storage.write(RECORD,value) }
            override fun requireNetworkOwner()=storage.requireRawWriterOwner()
        },scope,clientFor,leaseFor,resolveSource,commands)
    companion object {const val RECORD=NativeUploadJournalCodec.RECORD}
    val rows=mutableStateMapOf<String,NativeUploadRow>()
    var storageError by mutableStateOf<String?>(null);private set
    private val lock=Mutex();private val slots=Semaphore(2);private var loaded=false
    private class Run(val result:CompletableDeferred<JSONObject> = CompletableDeferred(),val signal:Channel<Unit> = Channel(Channel.CONFLATED)) {
        var job:Job?=null;var request:Deferred<NativeUploadReceipt>?=null;var generation=0L;var queryOnly=false
    }
    private val active=mutableMapOf<String,Run>()
    suspend fun restore()=lock.withLock {
        if(loaded)return@withLock
        try {
            val raw=withContext(Dispatchers.IO){journal.read()}
            val restored=raw?.let(NativeUploadJournalCodec::decode).orEmpty()
            rows.clear()
            rows.putAll(restored.associate {it.key to if(it.terminal)it else it.copy(paused=true,
                phase=if(it.legacy)"unknown" else if(it.cancelRequested)"cancel-pending" else "paused")})
            loaded=true;storageError=null
        } catch(failure:Exception) {storageError="上传记录无法读取，原文件已保留；未新建或覆盖任务。";throw failure}
    }
    private suspend fun change(key:String,expected:Run?=null,generation:Long?=null,update:(NativeUploadRow)->NativeUploadRow):NativeUploadRow?=lock.withLock {
        check(loaded)
        if(expected!=null&&(active[key]!==expected||generation!=null&&expected.generation!=generation))return@withLock null
        val old=rows[key]?:return@withLock null;val value=update(old)
        run {
            val all=rows.toMutableMap().apply {put(key,value)}.values
            val encoded=NativeUploadJournalCodec.encode(all.toList())
            try {withContext(NonCancellable+Dispatchers.IO){journal.write(encoded)}}
            catch(failure:Exception) {loaded=false;storageError="上传记录保存未确认，原件及原意图保留；请核对本机记录。";throw failure}
        }
        rows[key]=value;value
    }
    private suspend fun register(row:NativeUploadRow):NativeUploadRow {
        lock.withLock {require(rows.size<NativeUploadJournalCodec.MAX_JOBS||rows.containsKey(row.key));rows[row.key]?.let {require(it.manifest==row.manifest&&it.target==row.target);return it};rows[row.key]=row}
        // No remote mutation before this encrypted intent write succeeds.
        return change(row.key){it}?:error("上传记录不可用")
    }
    private fun start(row:NativeUploadRow,port:AgentCommands,client:MobileClient,source:((Long,Int)->ByteArray)?,origin:NativeUploadOrigin?,legacy:(suspend ()->JSONObject)?=null,queryOnly:Boolean=false):Run {
        require(row.target.matches(client)&&row.manifest.file.hostId==client.id)
        active[row.key]?.let {return it}
        val owner=Run();owner.queryOnly=queryOnly;active[row.key]=owner
        owner.job=scope.launch {
            try {
                val targetCurrent=leaseFor(client)
                val current:()->Boolean={targetCurrent()&&(origin?.current?.invoke()!=false)}
                if(legacy!=null) {
                    check(current()&&(origin?.current?.invoke()!=false))
                    val value=slots.withPermit {journal.requireNetworkOwner();check(current());legacy()}
                    check(current()&&(origin?.current?.invoke()!=false))
                    validateNativeUploadResult(row.manifest,value)
                    change(row.key,owner){it.copy(phase="completed",legacyResultWire=value.toString())};owner.result.complete(value);return@launch
                }
                var readSource=source // Status/cancel do not require reopening a missing original.
                while(isActive) {
                    val saved=rows.getValue(row.key)
                    if(!current()||(origin?.current?.invoke()==false)) {
                        change(row.key,owner){it.copy(phase="paused",paused=true,error="原目标、连接或输入已变化；原上传已保留。",rate=0.0)}
                        throw CancellationException("Original upload authority changed")
                    }
                    if(saved.phase=="cancelled")error("电脑已确认取消；原输入和附件保留，不能自动重建旧上传。")
                    if(saved.terminal) {owner.result.complete(checkNotNull(saved.receipt?.result));break}
                    if(saved.paused||saved.stop) {owner.signal.receive();continue}
                    val generation=owner.generation
                    var diagnosticStage="upload-status"
                    try {
                        // Any earlier mutation (including pause/restart) is reconciled first.
                        val receipt=if(owner.queryOnly||saved.pending!=null||saved.receipt!=null) request(port,client,saved,NativeUploadAction.STATUS,null,owner,generation,current)
                            else {diagnosticStage="upload-prepare";request(port,client,saved,NativeUploadAction.PREPARE,null,owner,generation,current)}
                        val accepted=accept(row.key,owner,generation,receipt)?:continue
                        if(accepted.terminal)continue
                        // Read-only controls never dispatch. A publication already in progress is
                        // query-only. An explicit resume may use the same idempotent action/ID only
                        // after this exact original receipt says publication has not started.
                        val publicationStarted=JSONObject(receipt.wire).optBoolean("publicationStarted",false)
                        if(owner.queryOnly||saved.pending in setOf("complete","cancel")&&(receipt.phase=="verifying"||publicationStarted)) {
                            change(row.key,owner,generation){it.copy(phase=if(it.cancelRequested)"cancel-pending" else if(owner.queryOnly&&it.pending==null)"paused" else "unknown",paused=true,error=if(it.pending==null)"已核对原上传；可从确认位置继续。" else "原操作结果待核对；仅查询原上传，不重放。",rate=0.0,sample=0)}
                            continue
                        }
                        if(accepted.cancelRequested) {
                            require(accepted.capability?.remoteCancel==true)
                            diagnosticStage="upload-cancel"
                            val terminal=request(port,client,accepted,NativeUploadAction.CANCEL,null,owner,generation,current)
                            accept(row.key,owner,generation,terminal);continue
                        }
                        if(receipt.phase=="verifying") {delay(500);continue}
                        val offset=receipt.committedBytes
                        if(offset<saved.manifest.file.sizeBytes) {
                            diagnosticStage="upload-source"
                            val n=minOf(receipt.maxChunkBytes.toLong(),checkNotNull(saved.capability).maxChunkBytes.toLong(),saved.manifest.file.sizeBytes-offset).toInt()
                            val reader=readSource?:resolveSource(client,accepted).also {readSource=it}
                            val chunk=withContext(Dispatchers.IO){reader(offset,n)}
                            try {
                                require(chunk.size==n&&current()&&(origin?.current?.invoke()!=false))
                                diagnosticStage="upload-chunk"
                                val next=request(port,client,accepted,NativeUploadAction.CHUNK,chunk,owner,generation,current)
                                require(next.committedBytes>=offset+n)
                                accept(row.key,owner,generation,next)
                            } finally {chunk.fill(0)}
                        } else {
                            diagnosticStage="upload-complete"
                            val done=request(port,client,accepted,NativeUploadAction.COMPLETE,null,owner,generation,current)
                            accept(row.key,owner,generation,done)
                        }
                    } catch(cancel:CancellationException) {
                        currentCoroutineContext().ensureActive() // Pause cancels only its bounded request child.
                    } catch(failure:Exception) {
                        if(owner.generation==generation) {
                            ComputerFileFailureDiagnostic.record(diagnosticStage,failure)
                            change(row.key,owner,generation){it.copy(phase=if(it.cancelRequested)"cancel-pending" else "waiting",error="连接或原回执待确认；保留原ID和文件，继续前先查询。",rate=0.0,sample=0)}
                            owner.signal.receive() // No polling/retry loop on a failed network path.
                        }
                    }
                }
            } catch(failure:Throwable) {
                val latest=rows[row.key]
                if(loaded&&latest!=null&&!latest.terminal&&!latest.paused&&!latest.stop)withContext(NonCancellable) {
                    try {change(row.key,owner){it.copy(phase=if(it.legacy)"unknown" else "paused",paused=true,rate=0.0,sample=0,
                        error=if(it.legacy)"旧接口结果待核对，不支持续传；请查询原业务回执。" else "原件或上传结果待核对，任务及原输入已保留。")}}
                    catch(_:Exception) {/* Original encrypted journal remains; storageError is already visible. */}
                }
                if(!owner.result.isCompleted)owner.result.completeExceptionally(failure)
                if(failure is CancellationException)throw failure
            } finally {if(active[row.key]===owner)active.remove(row.key);owner.signal.close()}
        }
        return owner
    }
    private suspend fun request(port:AgentCommands,client:MobileClient,row:NativeUploadRow,action:NativeUploadAction,
        bytes:ByteArray?,owner:Run,generation:Long,current:()->Boolean):NativeUploadReceipt {
        journal.requireNetworkOwner()
        check(current())
        if(action!=NativeUploadAction.STATUS) {
            val pending=when(action){NativeUploadAction.PREPARE->"prepare";NativeUploadAction.CHUNK->"chunk";NativeUploadAction.COMPLETE->"complete";NativeUploadAction.CANCEL->"cancel";else->error("read")}
            checkNotNull(change(row.key,owner,generation){it.copy(pending=pending)})
        }
        check(owner.generation==generation&&current())
        return slots.withPermit {supervisorScope {
            journal.requireNetworkOwner()
            check(owner.generation==generation&&current())
            val child=async {port.uploadRequest(client,row.manifest,action,row.receipt?.committedBytes?:0,bytes)}
            owner.request=child
            try {child.await().also {if(owner.generation!=generation||!current())throw CancellationException("Retired upload request")}}
            finally {if(owner.request===child)owner.request=null}
        }}
    }
    private suspend fun accept(key:String,owner:Run,generation:Long,receipt:NativeUploadReceipt):NativeUploadRow? =
        change(key,owner,generation) {old->
            require(receipt.manifest==old.manifest&&receipt.committedBytes>=old.committed)
            val now=SystemClock.elapsedRealtime();val elapsed=now-old.sample
            val rate=if(old.sample>0&&elapsed>0)(receipt.committedBytes-old.committed)*1000.0/elapsed else 0.0
            old.copy(receiptWire=receipt.wire,pending=old.pending?.takeIf {it in setOf("complete","cancel")&&receipt.phase !in setOf("completed","cancelled")},phase=receipt.phase,error=null,rate=rate,sample=now,
                cancelRequested=old.cancelRequested&&receipt.phase !in setOf("completed","cancelled"))
        }
    suspend fun transfer(client:MobileClient,m:NativeUploadManifest,sourceId:String?,source:(Long,Int)->ByteArray,
        port:AgentCommands,legacy:suspend ()->JSONObject):JSONObject {
        restore();m.validate();require(m.file.hostId==client.id);journal.requireNetworkOwner()
        val target=NativeUploadTarget.capture(client);val key=NativeUploadRow.key(target,m)
        val old=rows[key]
        require(old==null||old.target.matches(client)&&old.manifest==m&&old.sourceFileId==sourceId) {"原上传内容或目标已改变，未继续上传。"}
        if(old?.terminal==true) {
            require(old.phase=="completed") {"电脑已确认取消；原输入保留，不自动新建上传。"}
            check(leaseFor(client)())
            val result=old.receipt?.result?:JSONObject(checkNotNull(old.legacyResultWire));validateNativeUploadResult(m,result);return result
        }
        val cap=port.uploadCapability(client,m)
        require(old==null||cap!=null&&!old.legacy) {"原上传仍需核对；不降级重传旧文件。"}
        val caller=currentCoroutineContext();val incoming=caller[NativeUploadOrigin]
        val origin=NativeUploadOrigin(incoming?.operationId?:m.id,incoming?.index?:0,incoming?.count?:1,incoming?.totalBytes) {caller.isActive&&(incoming?.current?.invoke()!=false)}
        val row=register(old?:NativeUploadRow(key,target,m,sourceId,capability=cap,legacy=cap==null,
            batchId=origin?.operationId,index=origin?.index?:0,count=origin?.count?:1,batchBytes=origin?.totalBytes))
        if(row.terminal)return row.receipt?.result?:error("旧整文件回执不能恢复，请核对原业务记录。")
        val owner=start(row,port,client,source,origin,if(cap==null)legacy else null)
        try {return owner.result.await()}
        catch(cancel:CancellationException){control(key,"stop");throw cancel}
    }
    fun control(key:String,action:String) {scope.launch {try {
        restore();val old=rows[key]?:return@launch;if(old.terminal)return@launch
        require(action in setOf("pause","resume","query","stop","cancel"))
        if(old.legacy&&action !in setOf("stop"))return@launch
        if(action=="cancel"&&old.capability?.remoteCancel!=true)return@launch
        val row=change(key) {it.copy(paused=action in setOf("pause","stop"),stop=action=="stop",
            cancelRequested=it.cancelRequested||action=="cancel",phase=when(action){"pause"->"paused";"stop"->"stopped";"cancel"->"cancel-pending";else->"queued"},rate=0.0,sample=0)}?:return@launch
        val owner=active[key]
        if(action in setOf("pause","stop","cancel")) {owner?.generation=(owner?.generation?:0)+1;owner?.request?.cancel()}
        if(action=="stop")owner?.job?.cancel() // Local only; original caller ends, never submits a reply.
        else if(action !in setOf("pause")) {
            if(owner!=null){owner.queryOnly=action=="query";owner.signal.trySend(Unit)}
            else {
                val client=clientFor(row.target)?:return@launch
                start(row,commands(),client,null,null,queryOnly=action=="query") // File only; never restored business dispatch.
            }
        }
    } catch(cancel:CancellationException) {throw cancel}
      catch(_:Exception) {storageError="上传控制未确认，原记录及文件已保留；请重新核对。"}
    }}
    fun targetInvalidated() {for(key in active.keys.toList())control(key,"pause")}
    fun networkRecovered() {for(row in rows.values.filter {it.phase=="waiting"&&!it.paused&&!it.stop&&!it.cancelRequested})control(row.key,"resume")}
    fun dismissTerminal(key:String) {scope.launch {
        try {restore();lock.withLock {
            val row=rows[key]?:return@withLock
            if(!row.terminal||active.containsKey(key))return@withLock
            val encoded=NativeUploadJournalCodec.encode(rows.values.filter {it.key!=key})
            withContext(NonCancellable+Dispatchers.IO){journal.write(encoded)}
            rows.remove(key) // Only this display journal entry; never source files or business receipts.
        }}catch(cancel:CancellationException){throw cancel}
        catch(_:Exception){loaded=false;storageError="已完成任务卡清理未确认；原文件和业务回执保留。"}
    }}
}
