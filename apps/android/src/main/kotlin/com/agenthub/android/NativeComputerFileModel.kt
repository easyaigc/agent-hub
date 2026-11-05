package com.agenthub.android

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.OutputStream

internal data class ComputerFileLocalIssue(val receipt: ComputerFileReceipt,val key: String,val terminalSaveConfirmed: Boolean,val queryRequired: Boolean)

/** One captured computer target. Remote confirmation, durable terminal receipt and
 * local retirement are separate. Ending local waiting never cancels the Host. */
internal class NativeComputerFileModel(private val context: Context,val client: MobileClient,
    private val root: NativeSessionStorage,private val commands: AgentCommands,private val scope: CoroutineScope,
    private val current: ()->Boolean) {
    val presentationId=java.util.UUID.randomUUID().toString()
    val target=ComputerFileTarget.capture(client)
    var catalog by mutableStateOf<ComputerFileCatalog?>(null);private set
    var pending by mutableStateOf<List<PendingComputerFile>>(emptyList());private set
    private val knownReceived=mutableStateMapOf<String,ComputerFileReceipt>()
    val localIssues=mutableStateMapOf<String,ComputerFileLocalIssue>()
    val completed get()=(knownReceived.values+catalog?.files.orEmpty().filter {it.phase=="completed"}).distinctBy {it.file.id}
    var busy by mutableStateOf(false);private set
    var activity by mutableStateOf<String?>(null);private set
    var activityFile by mutableStateOf<String?>(null);private set
    var error by mutableStateOf<String?>(null);private set
    var detail by mutableStateOf<String?>(null);private set
    var progress by mutableLongStateOf(0L);private set
    var total by mutableLongStateOf(0L);private set
    var available by mutableStateOf(false);private set
    var journalUsable by mutableStateOf(false);private set
    private var storage: NativeComputerFileStore?=null
    private val mutex=Mutex()
    private var job: Job?=null
    private var epoch=0L
    val isCurrent get()=current()
    val maximum get()=minOf(catalog?.maxFileBytes?:ComputerFileCodec.MAX_FILE_BYTES,ComputerFileCodec.MAX_FILE_BYTES)
    val canChoose get()=isCurrent&&!busy&&available&&journalUsable&&catalog!=null&&!ComputerFileFdOwners.hasUnresolved()
    private fun message(failure: Throwable)=ComputerFileCopy.message(failure)
    fun received(file: PendingComputerFile)=file.phase==ComputerFilePhase.COMPLETED||ComputerFileCopy.receiptSavePending(file.error)||knownReceived.containsKey(file.id)
    private fun visible(ticket: Long)=epoch==ticket&&isCurrent
    private fun progress(ticket: Long): (Long)->Unit={count->scope.launch(Dispatchers.Main.immediate) {if(visible(ticket)&&busy)progress=count};Unit}
    private fun describe(ticket: Long): (String,Long)->Unit={name,size->scope.launch(Dispatchers.Main.immediate) {
        if(visible(ticket)&&busy){activity="正在读取 %@…";activityFile=name;total=size}
    };Unit}
    private fun remember(receipt: ComputerFileReceipt,ticket: Long) {
        require(receipt.phase=="completed")
        knownReceived[receipt.file.id]?.let {require(it==receipt) {ComputerFileCopy.CONFIRMATION_CONFLICT}}
        if(visible(ticket)){knownReceived[receipt.file.id]=receipt;publish(receipt)}
    }
    private suspend fun reload(store: NativeComputerFileStore,ticket: Long) {
        val saved=withContext(Dispatchers.IO){store.load()}
        if(visible(ticket)) {
            pending=saved;journalUsable=true
            saved.filter {it.phase==ComputerFilePhase.COMPLETED}.forEach {row->remember(requireNotNull(row.receipt),ticket)}
        }
    }
    private fun start(work: suspend (NativeComputerFileStore,Long)->Unit) {
        if(busy||!isCurrent)return
        busy=true;detail=null;val ticket=++epoch
        job=scope.launch {
            try {mutex.withLock {
                check(isCurrent)
                val store=storage?:withContext(Dispatchers.IO){NativeComputerFileStore.open(context,root,target)}.also {storage=it}
                if(!journalUsable)reload(store,ticket)
                work(store,ticket)
            }}catch(cancelled: CancellationException){throw cancelled}
            catch(failure: Exception){if(visible(ticket)){error=message(failure);detail=(failure as? CodexCommandError)?.takeIf {!it.appOwnedCopy}?.message?.take(8192)}}
            finally {if(epoch==ticket){busy=false;activity=null;activityFile=null;job=null}}
        }
    }
    private suspend fun issue(store: NativeComputerFileStore,receipt: ComputerFileReceipt,key: String,
        terminalSaveConfirmed: Boolean,queryRequired: Boolean,ticket: Long,cause: Throwable) {
        // Best-effort local annotation cannot turn a known receipt into UNKNOWN.
        try {withContext(NonCancellable+Dispatchers.IO){store.error(receipt.file.id,key)}}catch(saveFailure: Throwable){cause.addSuppressed(saveFailure)}
        if(visible(ticket)) {remember(receipt,ticket);localIssues[receipt.file.id]=ComputerFileLocalIssue(receipt,key,terminalSaveConfirmed,queryRequired);error=key;detail=null}
        try {reload(store,ticket)}catch(readFailure: Throwable) {
            cause.addSuppressed(readFailure)
            if(visible(ticket)){journalUsable=false;error=key}
        }
    }
    private suspend fun retireLocal(store: NativeComputerFileStore,receipt: ComputerFileReceipt,ticket: Long,knownSaved: Boolean=false) {
        remember(receipt,ticket)
        try {
            withContext(NonCancellable+Dispatchers.IO){store.releaseCompleted(receipt.file.id)}
        }catch(failure: Throwable) {
            val saved=knownSaved||((failure as? ComputerFileRetirementFailure)?.terminalBarrierConfirmed==true)
            issue(store,receipt,ComputerFileCopy.CLEANUP_PENDING,saved,false,ticket,failure);return
        }
        if(visible(ticket)) {
            localIssues.remove(receipt.file.id)
            if(catalog!=null)knownReceived.remove(receipt.file.id)
            if(error in setOf(ComputerFileCopy.CLEANUP_PENDING,ComputerFileCopy.RECEIPT_SAVE_PENDING))error=null
        }
        try {reload(store,ticket)}catch(failure: Throwable){issue(store,receipt,ComputerFileCopy.CLEANUP_PENDING,true,false,ticket,failure)}
    }
    private suspend fun acceptCompleted(store: NativeComputerFileStore,receipt: ComputerFileReceipt,expected: ComputerFileManifest,ticket: Long) {
        receipt.validate(expected);require(receipt.phase=="completed");remember(receipt,ticket)
        val committed=try {
            withContext(NonCancellable+Dispatchers.IO){store.apply(receipt)}
        }catch(failure: Throwable) {
            // No original is deleted when terminal persistence has not returned.
            // Original UNKNOWN remains query-only after restart if this receipt was not durable.
            issue(store,receipt,ComputerFileCopy.RECEIPT_SAVE_PENDING,false,true,ticket,failure);return
        }
        retireLocal(store,receipt,ticket,knownSaved=committed?.phase==ComputerFilePhase.COMPLETED)
    }
    fun refresh()=start {store,ticket->
        activity="正在查询传输记录…"
        val value=try {commands.computerFiles(client)}catch(cancelled: CancellationException){throw cancelled}
        catch(failure: Exception) {
            if(visible(ticket)) {
                if((failure as? CodexCommandError)?.status==404){available=false;error="此电脑尚未支持文件互传，请更新电脑连接程序。"}
                else error=ComputerFileCopy.REFRESH_FAILED
                detail=(failure as? CodexCommandError)?.takeIf {!it.appOwnedCopy}?.message?.take(8192)
            };return@start
        }
        if(!isCurrent)return@start
        val local=pending.associateBy {it.id}
        val relevant=value.files.mapNotNull {receipt->local[receipt.file.id]?.let {row->receipt.validate(row.manifest(client.id));receipt to row}}
        catalog=value;available=true;error=null
        for((receipt,row) in relevant) {
            if(receipt.phase=="completed")acceptCompleted(store,receipt,row.manifest(client.id),ticket)
            else if(received(row)&&visible(ticket))error=ComputerFileCopy.CONFIRMATION_CONFLICT
        }
        try {reload(store,ticket)}catch(failure: Exception) {
            if(visible(ticket)){journalUsable=false;if(error==null)error=ComputerFileCopy.REFRESH_FAILED}
        }
    }
    private suspend fun deliver(store: NativeComputerFileStore,id: String,ticket: Long) {
        ComputerFileFdOwners.requireReleased()
        val row=withContext(Dispatchers.IO){store.load().single {it.id==id}}
        check(row.phase==ComputerFilePhase.READY&&!received(row)&&isCurrent)
        total=row.sizeBytes;progress=0;activityFile=row.name;activity="正在核对手机原件…"
        val task=currentCoroutineContext()
        withContext(Dispatchers.IO) {store.upload(row).copyTo(object:OutputStream(){override fun write(b: Int) {};override fun write(b: ByteArray,o: Int,n: Int) {}},{task.isActive},progress(ticket))}
        task.ensureActive();check(isCurrent)
        val claimed=withContext(NonCancellable+Dispatchers.IO){store.claim(id)}
        task.ensureActive();check(isCurrent)
        activity="正在上传 %@…";progress=0
        val receipt=try {
            var value=commands.prepareComputerFile(client,claimed.manifest(client.id))
            if(value.phase!="completed"){task.ensureActive();check(isCurrent);value=commands.uploadComputerFile(client,store.upload(claimed),progress(ticket))}
            value.also {it.validate(claimed.manifest(client.id));require(it.phase=="completed")}
        }catch(failure: Throwable) {
            try {withContext(NonCancellable+Dispatchers.IO){store.error(id,message(failure))}}catch(saveFailure: Throwable){failure.addSuppressed(saveFailure)}
            throw failure
        }
        // Local terminal/cleanup failures are outside the remote-uncertain catch.
        acceptCompleted(store,receipt,claimed.manifest(client.id),ticket)
    }
    fun importFiles(uris: List<Uri>) {
        if(!canChoose)return
        start {store,ticket->
            require(uris.size in 1..32) {"一次最多选择 32 个文件。"}
            for(uri in uris) {
                currentCoroutineContext().ensureActive();check(isCurrent)
                var originalSealed=false
                try {
                    progress=0;total=0;activityFile=null;activity="正在读取所选文件…"
                    val row=store.capture(uri,maximum,progress(ticket),describe(ticket));originalSealed=true
                    reload(store,ticket);deliver(store,row.id,ticket)
                }catch(cancelled: CancellationException){throw cancelled}
                catch(failure: Exception) {
                    if(!isCurrent)return@start
                    reload(store,ticket)
                    val text=message(failure)
                    error=if(!originalSealed&&text==ComputerFileCopy.UNKNOWN)"原件读取未完成，已保存的内容保留在手机。" else text
                    detail=(failure as? CodexCommandError)?.takeIf {!it.appOwnedCopy}?.message?.take(8192)
                }
                if(ComputerFileFdOwners.hasUnresolved())break
                reload(store,ticket)
            }
        }
    }
    private suspend fun queryOriginal(store: NativeComputerFileStore,id: String,expected: ComputerFileManifest,confirmed: Boolean,ticket: Long) {
        activity="正在查询电脑接收结果…"
        val receipt=try {commands.computerFile(client,id)}catch(failure: CodexCommandError) {
            if(failure.status==404)throw IllegalStateException(if(confirmed)ComputerFileCopy.CONFIRMATION_CONFLICT else "电脑尚无原传输回执；请保留手机上的剩余内容并继续查询，不会重新上传。",failure)
            throw failure
        }
        receipt.validate(expected)
        if(receipt.phase=="completed")acceptCompleted(store,receipt,expected,ticket)
        else if(visible(ticket))error=if(confirmed)ComputerFileCopy.CONFIRMATION_CONFLICT else "电脑仍在等待或处理原传输；请继续查询，不会重新上传。"
    }
    fun retry(id: String)=start {store,ticket->
        val row=withContext(Dispatchers.IO){store.load().singleOrNull {it.id==id}}
        if(row==null) {
            val issue=localIssues[id]?:return@start
            if(issue.queryRequired)queryOriginal(store,id,issue.receipt.file,true,ticket)
            else retireLocal(store,issue.receipt,ticket,knownSaved=issue.terminalSaveConfirmed)
        }else when {
            row.phase==ComputerFilePhase.COMPLETED->retireLocal(store,requireNotNull(row.receipt),ticket,
                knownSaved=localIssues[id]?.terminalSaveConfirmed==true)
            received(row)->queryOriginal(store,id,row.manifest(client.id),true,ticket)
            row.phase==ComputerFilePhase.UNKNOWN->queryOriginal(store,id,row.manifest(client.id),false,ticket)
            row.phase==ComputerFilePhase.READY->deliver(store,id,ticket)
            else->error="原件读取未完成，已保存的内容保留在手机。"
        }
        try {reload(store,ticket)}catch(failure: Exception) {if(visible(ticket)){journalUsable=false;if(error==null)error=ComputerFileCopy.REFRESH_FAILED}}
    }
    private fun publish(receipt: ComputerFileReceipt) {
        val old=catalog?:return
        catalog=old.copy(files=listOf(receipt)+old.files.filterNot {it.file.id==receipt.file.id})
    }
    fun cancelLocalWait() {
        val ticket=++epoch;job?.cancel();job=null;busy=false;activity=null;activityFile=null;error="本地等待已结束；远端操作未取消。已清理的原件不会恢复，剩余内容和原回执需继续核对。"
        scope.launch {
            try {mutex.withLock {storage?.let {reload(it,ticket)}}}
            catch(cancelled: CancellationException){throw cancelled}
            catch(failure: Exception){if(visible(ticket)){journalUsable=false;error=if(knownReceived.isEmpty())message(failure) else ComputerFileCopy.CLEANUP_PENDING}}
        }
    }
    fun targetChanged() {cancelLocalWait();available=false;error="电脑目标已变化，请关闭后重新打开文件互传。"}
}
