package com.agenthub.android

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.*
import kotlinx.coroutines.*
import java.util.UUID

internal val LocalExternalAttachmentSurface=staticCompositionLocalOf<String?> { null }

internal data class InboxPresentation(val id: String=UUID.randomUUID().toString(),val batches: List<InboxBatch>,val notice: String?=null)

/** Retained with the native Session. Incoming files do not choose a chat or execute a command. */
internal class NativeExternalAttachments(context: Context,private val storage: NativeSessionStorage,private var uiForeground: ()->Boolean,private val migrationEmpty: Boolean) {
    constructor(context: Context,storage: NativeSessionStorage,uiForeground: ()->Boolean):this(context,storage,uiForeground,false)
    private val context=context.applicationContext
    private val store=NativeAttachmentInboxStore(storage)
    private val tasks=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private val ownedJobs=java.util.concurrent.ConcurrentHashMap.newKeySet<Job>()
    @Volatile private var migrationOperation: String?=null
    @Volatile private var retired=false
    private fun launchOwned(work: suspend CoroutineScope.()->Unit): Job {
        check(migrationOperation==null&&!retired)
        val job=tasks.launch(start=CoroutineStart.LAZY,block=work);ownedJobs.add(job)
        job.invokeOnCompletion {ownedJobs.remove(job)};job.start();return job
    }
    internal val migrationIdle get()=!loading&&!receiving&&!adding&&ownedJobs.none {!it.isCompleted}
    internal fun sealMigrationInputs(operation: String) {
        check(android.os.Looper.myLooper()==android.os.Looper.getMainLooper());check(!retired&&migrationOperation==null)
        if(!migrationIdle)throw NativeInboxOwnership.Busy()
        migrationOperation=operation
    }
    internal fun requireMigrationSealed(operation: String){check(!retired&&migrationOperation==operation&&ownedJobs.none {!it.isCompleted})}
    internal fun resumeMigrationInputs(operation: String){check(migrationOperation==operation);migrationOperation=null}
    internal suspend fun freezeForMigration(root: NativeSessionStorage,operation: String): NativeInboxOwnership.Lease {
        requireMigrationSealed(operation)
        var acquired: NativeInboxOwnership.Lease?=null
        try {
            withContext(NonCancellable+Dispatchers.IO){acquired=NativeInboxOwnership.Lease.acquire(root,storage,operation,this@NativeExternalAttachments)}
            currentCoroutineContext().ensureActive();return requireNotNull(acquired)
        }catch(error: Throwable){withContext(NonCancellable+Dispatchers.IO){acquired?.close()};throw error}
    }
    internal fun rebindAfterMigration(operation: String,foreground: ()->Boolean) {
        requireMigrationSealed(operation);uiForeground=foreground;migrationOperation=null
    }
    internal fun retireForMigration(){check(ownedJobs.none {!it.isCompleted});retired=true;tasks.cancel();storage.retireWriter();receivedSources.clear()}
    private var snapshot by mutableStateOf(InboxSnapshot(-1,emptyList()))
    private val active=mutableStateListOf<String>()
    private val dismissed=mutableSetOf<String>()
    private val receivedSources=mutableMapOf<String,List<android.net.Uri>>()
    private var request by mutableLongStateOf(0)
    private var handledRequest=0L
    private var surface: String?=null
    fun claimSurface(owner: String) { surface=owner }
    fun releaseSurface(owner: String) { if(surface==owner)surface=null }
    private fun ownsSurface(owner: String?)=migrationOperation==null&&!retired&&owner!=null&&surface==owner&&uiForeground()
    private var notice by mutableStateOf<String?>(null)
    var presentation by mutableStateOf<InboxPresentation?>(null)
        private set
    var adding by mutableStateOf(false)
        private set
    var failureKey by mutableStateOf<String?>(null)
        private set
    internal var lastFailure: Throwable?=null
        private set
    var loading by mutableStateOf(true)
        private set
    val receiving get()=active.isNotEmpty()
    val pending get()=snapshot.records.filter { it.phase!="consumed"&&it.id !in active }.map { if(it.phase=="receiving")it.copy(phase="failed")else it }
    val available get()=pending.isNotEmpty()||notice!=null
    val wantsPresentation get()=presentation==null&&!loading&&available&&(notice!=null||request>handledRequest||pending.any { it.id !in dismissed })
    init { if(migrationEmpty){snapshot=InboxSnapshot(0,emptyList());loading=false}else launchOwned {
        try { store.cleanupConsumed() }catch(cancel: CancellationException) { throw cancel }catch(error: Throwable) { notice=failure(error) }
        refresh()
    } }
    private fun failure(error: Throwable): String {
        lastFailure=error
        return (error as? NativeFileIntakeFailure)?.key ?: NativeFileIntakeFailure.UNAVAILABLE
    }
    private suspend fun refresh() {
        try {
            val next=store.snapshot()
            if(next.revision>=snapshot.revision) {
                snapshot=next
                next.records.filter { it.phase=="consumed" }.forEach { receivedSources.remove(it.id) }
            }
        } catch(cancel: CancellationException) { throw cancel }
          catch(error: Throwable) { notice=failure(error) }
        finally { loading=false }
    }
    /** True means this is a file intent, including a rejected one. Other links use the old route. */
    fun receive(intent: Intent,operationId: String): Boolean {
        if(migrationOperation!=null||retired)return false
        val sources=try { NativeFileIntake.sharedFiles(intent) ?: return false }
            catch(error: Exception) { notice=failure(error);return true }
        beginReceive(operationId,sources)
        return true
    }
    private fun beginReceive(operationId: String,sources: List<android.net.Uri>): Job? {
        if(migrationOperation!=null||retired||operationId in active)return null
        receivedSources[operationId]=sources.toList();active+=operationId
        return launchOwned {
            try { store.stage(operationId,sources,context.contentResolver) }
            catch(cancel: CancellationException) { throw cancel }
            catch(error: Throwable) {
                refresh()
                val key=failure(error)
                if(snapshot.records.none { it.id==operationId })notice=key
            }
            // Publish the final durable phase before making this batch presentable.
            // Otherwise the receiving snapshot can briefly become a failed presentation.
            finally { try { refresh() }finally { active.remove(operationId) } }
        }
    }
    fun canRetry(id: String)=receivedSources.containsKey(id)
    fun requestPresentation(owner: String?) { if(ownsSurface(owner))request++ }
    fun presentIfWanted(owner: String?): Boolean {
        if(!ownsSurface(owner)||!wantsPresentation)return false
        presentation=InboxPresentation(batches=pending.toList(),notice=notice);notice=null;failureKey=null;handledRequest=request
        return true
    }
    private fun finishPresentation(): Boolean {
        if(adding)return false
        presentation?.batches?.forEach { dismissed+=it.id }
        presentation=null;failureKey=null;return true
    }
    fun dismiss(owner: String?): Boolean = ownsSurface(owner)&&finishPresentation()
    fun retry(original: InboxPresentation,owner: String?) {
        if(!ownsSurface(owner)||adding||presentation?.id!=original.id)return
        launchOwned {
            val jobs=original.batches.filter { it.phase!="pending" }.mapNotNull { batch -> receivedSources[batch.id]?.let { beginReceive(batch.id,it) } }
            jobs.forEach { it.join() };refresh()
            if(presentation?.id==original.id) { presentation=null;request++ }
        }
    }
    fun discard(original: InboxPresentation,owner: String?) {
        if(!ownsSurface(owner)||adding||receiving||presentation?.id!=original.id)return
        adding=true;failureKey=null
        launchOwned {
            try {
                for(batch in original.batches)store.discard(batch.id)
                refresh();if(presentation?.id==original.id) { adding=false;finishPresentation() }
            } catch(cancel: CancellationException) { throw cancel }
              catch(error: Throwable) { if(presentation?.id==original.id)failureKey=failure(error) }
            finally { adding=false }
        }
    }
    fun add(original: InboxPresentation,session: MobileSession,owner: String?) {
        if(!ownsSurface(owner)||adding||presentation?.id!=original.id||session.restoring||session.localRecoveryRequired||session.importing||!session.canCompose)return
        val requestedDestination=try { session.attachmentImportDestination() }catch(error: Exception) { failureKey=failure(error);return }
        val binding=InboxBinding(requestedDestination.key,requestedDestination.generation)
        val batches=original.batches.filter { it.phase=="pending" }
        if(batches.isEmpty())return
        adding=true;failureKey=null
        launchOwned {
            try {
                for(batch in batches) {
                    val destination=requestedDestination.copy(maximum=batch.maximum)
                    val bound=store.bind(batch,binding)
                    if(bound.phase=="consumed")continue
                    val prior=session.inboxFiles(destination)
                    val missing=mutableListOf<CreationFile>()
                    for(file in bound.readyFiles) {
                        val existing=prior.firstOrNull { it.id==file.id }
                        require(existing==null||existing==file) { NativeFileIntakeFailure.CHANGED }
                        if(existing==null)missing+=file else {
                            try { session.verifyInboxFile(file) }
                            catch(_: LocalDraftSourceMissing) { session.repairInboxFile(destination,file,store.payload(bound,file)) }
                        }
                    }
                    if(missing.isNotEmpty()) {
                        val job=session.importAttachments(destination,missing.map { file -> { store.payload(bound,file) } })
                            ?: throw NativeFileIntakeFailure(NativeFileIntakeFailure.UNAVAILABLE)
                        job.await()
                    }
                    if(!session.confirmInboxFiles(destination,bound.readyFiles))throw NativeFileIntakeFailure(NativeFileIntakeFailure.UNAVAILABLE)
                    store.consume(bound,binding)
                }
                refresh();if(presentation?.id==original.id) { adding=false;finishPresentation() }
            } catch(cancel: CancellationException) { throw cancel }
              catch(error: Throwable) { if(presentation?.id==original.id)failureKey=failure(error);refresh() }
            finally { adding=false }
        }
    }
}
