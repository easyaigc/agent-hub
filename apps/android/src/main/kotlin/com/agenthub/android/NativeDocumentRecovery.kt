package com.agenthub.android

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.ActivityResultRegistry
import androidx.compose.runtime.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** Reconciles only selected local files. There is no OS-request replay, upload or command interface. */
internal typealias NativeDocumentRecovery=NativeSelectionRecovery

internal class NativeSelectionRecovery(context: Context,@Volatile private var session: MobileSession,private val storage: NativeSessionStorage,private val picker: NativeSelectionOwner,private val kind: NativeSelectionKind=NativeSelectionKind.DOCUMENT,
    private val permissionRequested: (String)->Unit={},private val outputPrepared: (String,NativeCameraOutput)->Unit={ _,_ -> },private val cameraAccess: NativeCameraAccess=AndroidCameraAccess(context)) {
    private val context=context.applicationContext
    private val resolver=this.context.contentResolver
    private val tasks=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private val migrationRoot=session.migrationStorage
    private val journal=NativeDocumentJournal(storage)
    private val payloadStorage=storage.child(context,"payloads")
    private val outputStorage=if(kind==NativeSelectionKind.CAMERA)storage.child(context,"outputs")else null
    private val payloads=NativeAttachmentInboxStore(payloadStorage)
    private val cameraOutputs=outputStorage?.let {NativeCameraOutputs(context,it)}
    private val ownedJobs=java.util.concurrent.ConcurrentHashMap.newKeySet<Job>()
    @Volatile private var migrationOperation: String?=null
    @Volatile private var retired=false
    private var resumingOperation: String?=null
    private var resumedOperation: String?=null
    @Volatile internal var migrationReturnPending=false;private set
    private data class DeferredReturn(val id: String,val sources: List<String>?,val permission: Boolean?,val request: NativeDocumentRecord?,
        var event: NativeSelectionReturnAudit.Event?=null,var durable: Boolean=false,var accepted: Boolean=false)
    @Volatile internal var migrationReturnFailure: String?=null;private set
    private var uncapturedReturn=false
    @Volatile internal var migrationUncapturedRequestId: String?=null;private set
    private var uncapturedSources: List<Uri>?=null
    private var uncapturedPermission: Boolean?=null
    private var returnAuditFailed=false
    private val returnAuditJobs=java.util.concurrent.ConcurrentHashMap.newKeySet<Job>()
    private val deferredReturns=linkedMapOf<Pair<String,Boolean>,DeferredReturn>()
    private var deferredDetach=false
    private val entryOpen get()=migrationOperation==null&&resumingOperation==null&&!retired
    private fun launchOwned(work: suspend CoroutineScope.()->Unit): Job {
        check(migrationOperation==null&&!retired)
        val job=tasks.launch(start=CoroutineStart.LAZY,block=work);ownedJobs.add(job)
        job.invokeOnCompletion {ownedJobs.remove(job)};job.start();return job
    }
    internal val migrationKind get()=when(kind) {
        NativeSelectionKind.DOCUMENT->NativeMigrationSelectionJournal.Kind.DOCUMENT
        NativeSelectionKind.PHOTO->NativeMigrationSelectionJournal.Kind.PHOTO
        NativeSelectionKind.CAMERA->NativeMigrationSelectionJournal.Kind.CAMERA
    }
    internal val migrationIdle get()=!retired&&resumingOperation==null&&!loading&&!failed&&!busy&&!migrationReturnPending&&returnAuditJobs.none {!it.isCompleted}&&ownedJobs.none {!it.isCompleted}
    internal fun sealMigrationInputs(operation: String) {
        check(android.os.Looper.myLooper()==android.os.Looper.getMainLooper());check(entryOpen)
        if(!migrationIdle||picker.pending!=null||rows.any {it.awaitingResult||kind==NativeSelectionKind.CAMERA&&it.phase=="detached"})throw NativeInboxOwnership.Busy()
        migrationOperation=operation
    }
    internal fun requireMigrationSealed(operation: String) {
        check(!retired&&migrationOperation==operation&&ownedJobs.none {!it.isCompleted})
    }
    internal fun migrationStores(root: NativeSessionStorage): List<NativeSessionStorage> {
        check(session.migrationStorage===root)
        check(when(kind) {
            NativeSelectionKind.DOCUMENT->session.documentPicker?.recovery===this
            NativeSelectionKind.PHOTO->session.photoPicker?.recovery===this
            NativeSelectionKind.CAMERA->session.cameraCapture?.recovery===this
        }) {"Selection lease requires the actual Session owner"}
        return listOf(storage,payloadStorage)+listOfNotNull(outputStorage)
    }
    internal fun requireNoCameraWriterForMigration(){cameraOutputs?.requireNoOriginalForMigration()}
    internal suspend fun freezeForMigration(root: NativeSessionStorage,operation: String): NativeInboxOwnership.SelectionLease {
        requireMigrationSealed(operation);var acquired: NativeInboxOwnership.SelectionLease?=null
        try {
            withContext(NonCancellable+Dispatchers.IO){acquired=NativeInboxOwnership.SelectionLease.acquire(root,operation,this@NativeSelectionRecovery)}
            currentCoroutineContext().ensureActive();return requireNotNull(acquired)
        }catch(error: Throwable){withContext(NonCancellable+Dispatchers.IO){acquired?.close()};throw error}
    }
    private fun returnAuditFailure(reason: String) {migrationReturnFailure=reason;returnAuditFailed=true;failed=true}
    private fun retainUncapturedReturn(id: String,sources: List<Uri>?,permission: Boolean?,reason: String) {
        // If immutable snapshot allocation/iteration failed, retain the original callback object
        // and target identity for inspection. Never label it captured, persist a replacement or execute it.
        if(!uncapturedReturn){migrationUncapturedRequestId=id;uncapturedSources=sources;uncapturedPermission=permission}
        uncapturedReturn=true;returnAuditFailure(reason)
    }
    private suspend fun persistReturn(value: DeferredReturn) {
        if(value.durable)return
        if(value.event==null) {
            try {value.event=withContext(NonCancellable+Dispatchers.IO){NativeSelectionReturnAudit.capture(storage.coordinationKey,migrationKind,value.id,value.request,value.sources,value.permission)}}
            catch(error: NativeSelectionReturnAudit.CaptureLimit){returnAuditFailure(error.boundary);throw error}
            catch(error: Exception){returnAuditFailure("capture-failed");throw error}
            catch(error: OutOfMemoryError){returnAuditFailure("capture-memory");throw IllegalStateException("Original selection return remains unarchived",error)}
        }
        val accepted=try{withContext(NonCancellable+Dispatchers.IO){migrationRoot.persistSelectionReturn(context,requireNotNull(value.event))}}
            catch(error: Exception){returnAuditFailure("write-failed");throw error}
            catch(error: OutOfMemoryError){returnAuditFailure("write-memory");throw IllegalStateException("Original selection return remains unarchived",error)}
        value.accepted=accepted;value.durable=true
    }
    private fun receiveOriginal(value: DeferredReturn) {
        if(!value.accepted)return
        if(value.permission!=null)receivePermissionOriginal(value.id,requireNotNull(value.permission))
        else receiveSourcesOriginal(value.id,requireNotNull(value.sources).map(Uri::parse))
    }
    /** A callback during the actual migration seal is captured before child writes/provider/grants. */
    private fun admitReturn(id: String,sources: List<Uri>?=null,permission: Boolean?=null) {
        if(retired)return
        if(migrationOperation==null) {
            if(permission!=null)receivePermissionOriginal(id,permission)else receiveSourcesOriginal(id,requireNotNull(sources))
            return
        }
        // Invalidate before copying, validating or allocating the audit representation.
        migrationReturnPending=true;session.invalidateMigrationLease()
        if(uncapturedReturn)return
        try {
            val key=id to (permission!=null)
            if(key in deferredReturns)return
            val value=DeferredReturn(id,sources?.map(Uri::toString),permission,rows.firstOrNull {it.id==id})
            deferredReturns[key]=value
            val job=tasks.launch(start=CoroutineStart.LAZY) {
                try {persistReturn(value)}catch(cancel: CancellationException){throw cancel}catch(_: Exception){/* Exact original remains pending; failure already classified. */}
            }
            returnAuditJobs.add(job);job.invokeOnCompletion {returnAuditJobs.remove(job)};job.start()
        }catch(_: Exception){retainUncapturedReturn(id,sources,permission,"intent-capture-failed")}
         catch(_: OutOfMemoryError){retainUncapturedReturn(id,sources,permission,"intent-capture-memory")}
    }
    internal suspend fun awaitMigrationReturnAudits(operation: String) {
        requireMigrationSealed(operation)
        check(!uncapturedReturn) {"Original selection callback intent remains unarchived"}
        // Main may receive another actual registry callback while an IO admission is awaited.
        do {
            returnAuditJobs.toList().forEach {it.join()}
            // Reconcile only the same immutable local audit write. Never retry the OS/provider operation.
            for(value in deferredReturns.values.toList())persistReturn(value)
        }while(returnAuditJobs.any {!it.isCompleted}||deferredReturns.values.any {!it.durable})
        check(!uncapturedReturn) {"Original selection callback intent remains unarchived"}
        if(returnAuditFailed){returnAuditFailed=false;failed=false};migrationReturnFailure=null
    }
    internal suspend fun resumeMigrationInputs(operation: String) {
        if(migrationOperation==null&&resumedOperation==operation)return
        if(resumingOperation==null){awaitMigrationReturnAudits(operation);resumingOperation=operation;migrationOperation=null}
        check(resumingOperation==operation&&!retired)
        for((key,value)in deferredReturns.toList()) {
            check(value.durable);receiveOriginal(value);deferredReturns.remove(key)
        }
        migrationReturnPending=deferredReturns.isNotEmpty()
        if(deferredDetach){deferredDetach=false;clearRegistry()}else register()
        resumedOperation=operation;resumingOperation=null
    }
    internal fun rebindAfterMigration(replacement: MobileSession,operation: String) {
        requireMigrationSealed(operation);session=replacement
    }
    internal fun requireRetirableForMigration(operation: String) {
        requireMigrationSealed(operation);check(!uncapturedReturn&&returnAuditJobs.none {!it.isCompleted}&&deferredReturns.values.all {it.durable}) {"Original selection return is awaiting durable audit"}
    }
    internal fun retireForMigration(operation: String) {
        requireRetirableForMigration(operation);retired=true;clearRegistry();tasks.cancel();deferredReturns.clear();migrationReturnPending=false
        storage.retireWriter();payloadStorage.retireWriter();outputStorage?.retireWriter()
    }
    private fun clearRegistry() {
        launchers.values.forEach {it.unregister()};launchers.clear();permissionLaunchers.values.forEach {it.unregister()};permissionLaunchers.clear();registry=null;registryOwner=null
    }
    private val workers=Semaphore(1)
    private val warm=mutableMapOf<String,NativePhotoRequest>()
    private val jobs=mutableStateMapOf<String,AttachmentImportDestination?>()
    private val detaching=mutableSetOf<String>()
    private val holdOnReturn=mutableSetOf<String>()
    private val queuedReturns=mutableMapOf<String,List<Uri>>()
    private val rows=mutableStateListOf<NativeDocumentRecord>()
    private var registry: ActivityResultRegistry?=null
    private var registryOwner: Any?=null
    private val launchers=mutableMapOf<String,ActivityResultLauncher<Unit>>()
    private val permissionLaunchers=mutableMapOf<String,ActivityResultLauncher<String>>()
    private var loadJob: Job?=null
    private var launching: String?=null
    var loading by mutableStateOf(true)
        private set
    var failed by mutableStateOf(false)
        private set
    var revision by mutableIntStateOf(0)
        private set
    var permissionRevision by mutableIntStateOf(0)
        private set
    private var restoredWaitId by mutableStateOf<String?>(null)
    internal var beforeRefreshForAcceptance: (suspend ()->Unit)?=null
    val restoredWait get()=restoredWaitId!=null&&restoredWaitId==picker.pending?.id
    val failures get()=rows.filter { it.phase=="failed"||kind==NativeSelectionKind.CAMERA&&it.phase in listOf("detached","held") }
    val busy get()=launching!=null||jobs.isNotEmpty()
    val canBegin get()=entryOpen&&!loading&&!failed&&!busy
    fun isProcessing(id: String)=id in jobs
    fun preparing(key: String,generation: String?)=jobs.values.any { it?.key==key&&it.generation==generation }||
        rows.any { it.phase in listOf("selected","ready","hold-selected")&&it.destination.key==key&&it.destination.generation==generation }
    // The repository can contain a partially committed result before any process-local job exists.
    fun blocksRemoval(key: String,generation: String?)=loading||failed||preparing(key,generation)
    init { retryRestore() }
    fun retryRestore() {
        if(!entryOpen||loadJob?.isActive==true||jobs.isNotEmpty()||launching!=null)return
        loading=true;failed=false
        loadJob=launchOwned {
            try {
                replace(journal.restore())
                rows.singleOrNull { it.awaitingResult }?.let { picker.restoreWait(it);restoredWaitId=it.id }
                register()
            }catch(cancel: CancellationException) { throw cancel }
             catch(error: Exception) { failed=true }
             catch(error: OutOfMemoryError) { failed=true }
            finally { loading=false }
        }
    }
    val parentReady get()=session.attachmentStorageReady
    private fun replace(value: List<NativeDocumentRecord>) {
        require(value.all { it.sources.size<=minOf(kind.maximum,it.destination.maximum) }) { kind.tooMany }
        require(kind==NativeSelectionKind.CAMERA||value.none { it.phase in listOf("permission","detached","hold-selected","held") })
        rows.clear();rows.addAll(value);revision++
    }
    private suspend fun refresh() {
        beforeRefreshForAcceptance?.let {barrier->
            check(BuildConfig.DEBUG&&context.packageName=="com.easyai.agenthub.android.acceptance")
            barrier()
        }
        replace(journal.records());register()
    }
    fun attach(value: ActivityResultRegistry,owner: Any) {
        if(retired)return
        deferredDetach=false
        if(registry!==value) { launchers.values.forEach { it.unregister() };launchers.clear();permissionLaunchers.values.forEach { it.unregister() };permissionLaunchers.clear();registry=value }
        registryOwner=owner;register()
    }
    fun detach(value: ActivityResultRegistry,owner: Any) {
        if(registry!==value||registryOwner!==owner)return
        if(migrationOperation!=null){deferredDetach=true;return}
        clearRegistry()
    }
    private fun register() {
        val target=registry ?: return
        val ids=(rows.filter { it.phase!="consumed" }.map { it.id }+listOfNotNull(picker.pending?.id)).toSet()
        for(id in ids) {
            if(id !in launchers)launchers[id]=target.register("agent-hub:${kind.registryName}:v1:$id",kind.contract(cameraOutputs?.output(id)?.uri)) { uris -> receive(id,uris) }
            if(cameraOutputs!=null&&id !in permissionLaunchers)permissionLaunchers[id]=target.register("agent-hub:camera-permission:v1:$id",androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { granted -> receivePermission(id,granted) }
        }
        for(id in launchers.keys.filter { it !in ids })launchers.remove(id)?.unregister()
        for(id in permissionLaunchers.keys.filter { it !in ids })permissionLaunchers.remove(id)?.unregister()
    }
    suspend fun launch(original: NativePhotoRequest,text: NativeL10n) {
        if(!entryOpen||picker.pending!==original||original.launched||launching!=null)return
        launching=original.id;warm[original.id]=original
        var externalCall=false
        try {
            if(picker.retireBeforeLaunch(original)) {
                journal.records().firstOrNull { it.id==original.id }?.let { journal.update(it.id) { it.copy(phase="retired") };cameraOutputs?.cleanup(it.id);refresh() }
                return
            }
            check(session.confirmAttachmentOrigin(original.destination))
            if(cameraOutputs!=null)check(cameraAccess.available())
            val needsPermission=cameraOutputs!=null&&!cameraAccess.granted()
            val existing=journal.records().firstOrNull { it.id==original.id }
            if(existing==null)journal.prepare(original,if(needsPermission)"permission"else"prepared")
            else check(cameraOutputs!=null&&existing.phase=="prepared"&&!needsPermission&&existing.destination==original.destination)
            refresh()
            if(picker.retireBeforeLaunch(original)) { journal.update(original.id) { it.copy(phase="retired") };cameraOutputs?.cleanup(original.id);refresh();return }
            if(needsPermission) {
                permissionRequested(original.id);original.launched=true;externalCall=true
                checkNotNull(permissionLaunchers[original.id]).launch(android.Manifest.permission.CAMERA)
                return
            }
            if(cameraOutputs!=null) {
                outputPrepared(original.id,cameraOutputs.prepare(original.id))
            }
            journal.update(original.id) { it.copy(phase="launched") };refresh();register()
            val launcher=checkNotNull(launchers[original.id]);original.launched=true
            externalCall=true;launcher.launch(Unit)
        }catch(cancel: CancellationException) {
            if(!externalCall)withContext(NonCancellable) {
                runCatching { journal.update(original.id) { it.copy(phase="retired") };refresh() }.onFailure { failed=true }
                runCatching { cameraOutputs?.cleanup(original.id) }.onFailure { failed=true };picker.finish(original.id)
            }
            throw cancel
        }catch(error: Exception) {
            if(externalCall)restoredWaitId=original.id else {
                picker.selectionLaunchFailed(original,text)
                // A failed second write may leave a durable prepared intent. Keep it visible;
                // do not hide it and allow a new selection to silently replace the original.
                try {
                    refresh()
                    rows.singleOrNull { it.id==original.id&&it.awaitingResult }?.let { picker.restoreWait(it);restoredWaitId=it.id }
                }catch(readFailure: Exception) { failed=true }
            }
        }finally { if(launching==original.id)launching=null }
    }
    internal fun receivePermission(id: String,granted: Boolean) {admitReturn(id,permission=granted)}
    private fun receivePermissionOriginal(id: String,granted: Boolean) {
        if(cameraOutputs==null||id in jobs)return
        jobs[id]=warm[id]?.destination ?: rows.firstOrNull { it.id==id }?.destination
        launchOwned {
            val text=NativeL10n(context,session.language)
            try {
                val record=journal.records().single { it.id==id }
                if(record.phase!="permission")return@launchOwned
                if(!granted) {
                    journal.update(id) { it.copy(phase="failed") };cameraOutputs.cleanup(id)
                    (warm[id] ?: picker.pending?.takeIf { it.id==id })?.let { session.photoSelectionFailure(it,text.tr(NativeCameraCapture.PERMISSION)) }
                    picker.finish(id);if(restoredWaitId==id)restoredWaitId=null
                }else {
                    journal.update(id) { it.copy(phase="prepared") }
                    picker.pending?.takeIf { it.id==id }?.launched=false
                    if(restoredWaitId==id)restoredWaitId=null
                    permissionRevision++
                }
            }catch(cancel: CancellationException) { throw cancel }
             catch(error: Exception) { fail(id,error,text) }
            finally { jobs.remove(id);runCatching { refresh() } }
        }
    }
    fun receive(id: String,uris: List<Uri>) {admitReturn(id,sources=uris)}
    private fun receiveSourcesOriginal(id: String,uris: List<Uri>) {
        if(id in detaching) { queuedReturns.putIfAbsent(id,uris.map { Uri.parse(it.toString()) });return }
        if(id in jobs)return
        jobs[id]=warm[id]?.destination ?: rows.firstOrNull { it.id==id }?.destination
        launchOwned {
            val text=NativeL10n(context,session.language)
            try {
                val record=journal.records().single { it.id==id }
                if(record.terminal) { cameraOutputs?.cleanup(id);return@launchOwned }
                // The first returned source is immutable. A duplicate is not a retry.
                if(record.sources.isNotEmpty())return@launchOwned
                if(uris.isEmpty()) {
                    journal.update(id) { it.copy(phase="consumed") };finish(record);return@launchOwned
                }
                val sources=uris.map { Uri.parse(it.toString()) }.distinct()
                if(sources.size>minOf(kind.maximum,record.destination.maximum))throw NativeFileIntakeFailure(if(record.destination.maximum==5&&kind==NativeSelectionKind.DOCUMENT)"最多添加 5 个附件，请先移除部分附件。" else kind.tooMany)
                require(sources.isNotEmpty()&&sources.all { it.scheme=="content"&&!it.authority.isNullOrBlank()&&it.fragment==null&&it.toString().length<=4096 })
                val strings=sources.map(Uri::toString);require(record.sources.isEmpty()||record.sources==strings)
                if(cameraOutputs!=null)require(strings==listOf(cameraOutputs.output(id).uri.toString()))
                val prior=resolver.persistedUriPermissions.filter { it.isReadPermission }.map { it.uri.toString() }.toSet()
                val release=if(cameraOutputs!=null)emptyList()else if(record.sources.isEmpty())strings.filter { it !in prior }else record.releaseReadGrants
                // Save the exact new read-grant claim before requesting persistence.
                val holding=cameraOutputs!=null&&(record.phase=="detached"||id in holdOnReturn)
                val selected=journal.update(id) { it.copy(phase=if(holding||cameraOutputs!=null&&it.phase=="detached")"hold-selected"else"selected",sources=strings,releaseReadGrants=release) }
                if(cameraOutputs==null)for(uri in sources)runCatching { resolver.takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                process(selected,text)
            }catch(cancel: CancellationException) { throw cancel }
             catch(error: Exception) { fail(id,error,text) }
             catch(error: OutOfMemoryError) { fail(id,IllegalStateException(NativeFileIntakeFailure.UNAVAILABLE,error),text) }
            finally { jobs.remove(id);runCatching { refresh() } }
        }
    }
    fun recoverReady(text: NativeL10n) {
        if(!entryOpen||loading||failed||!session.attachmentStorageReady)return
        for(record in rows.toList())if(record.phase in listOf("selected","ready","hold-selected")&&record.id !in jobs)run(record,text)
    }
    private fun run(record: NativeDocumentRecord,text: NativeL10n) {
        jobs[record.id]=record.destination
        launchOwned {
            try { process(record,text) }
            catch(cancel: CancellationException) { throw cancel }
            catch(error: Exception) { fail(record.id,error,text) }
            catch(error: OutOfMemoryError) { fail(record.id,IllegalStateException(NativeFileIntakeFailure.UNAVAILABLE,error),text) }
            finally { jobs.remove(record.id);runCatching { refresh() } }
        }
    }
    private suspend fun process(record: NativeDocumentRecord,text: NativeL10n)=workers.withPermit { processOne(record,text) }
    private suspend fun processOne(record: NativeDocumentRecord,text: NativeL10n) {
        if(record.sources.size>minOf(kind.maximum,record.destination.maximum))throw NativeFileIntakeFailure(if(record.destination.maximum==5&&kind==NativeSelectionKind.DOCUMENT)"最多添加 5 个附件，请先移除部分附件。" else kind.tooMany)
        val cached=payloads.snapshot().records.firstOrNull { it.id==record.id }
        if(cached?.phase !in listOf("pending","consumed"))cameraOutputs?.beforeRead(record.id)
        val batch=if(cached?.phase in listOf("pending","consumed"))cached!! else payloads.stage(record.id,record.sources.map(Uri::parse),resolver,maximum=record.destination.maximum) { kind.read(resolver,it) }
        if(batch.phase=="consumed") { journal.update(record.id) { it.copy(phase="consumed") };finish(record);return }
        val held=record.phase in listOf("hold-selected","held")
        journal.update(record.id) { it.copy(phase=if(held)"held"else"ready") };release(record);cameraOutputs?.cleanup(record.id)
        if(held)return
        if(!session.attachmentStorageReady)return
        val destination=record.destination;val binding=InboxBinding(destination.key,destination.generation)
        val bound=payloads.bind(batch,binding);val prior=session.inboxFiles(destination);val missing=mutableListOf<CreationFile>()
        for(file in bound.readyFiles) {
            val existing=prior.firstOrNull { it.id==file.id };require(existing==null||existing==file) { NativeFileIntakeFailure.CHANGED }
            if(existing==null)missing+=file else try { session.verifyInboxFile(file) }
            catch(_: LocalDraftSourceMissing) { session.repairInboxFile(destination,file,payloads.payload(bound,file)) }
        }
        if(missing.isNotEmpty()) {
            val job=session.importAttachments(destination,missing.map { file -> { payloads.payload(bound,file) } },warm[record.id]?.errorGeneration ?: Long.MIN_VALUE)
                ?: throw NativeFileIntakeFailure(NativeFileIntakeFailure.UNAVAILABLE)
            job.await()
        }
        check(session.confirmInboxFiles(destination,bound.readyFiles))
        payloads.consume(bound,binding);journal.update(record.id) { it.copy(phase="consumed") };finish(record)
    }
    private fun release(record: NativeDocumentRecord) { record.releaseReadGrants.forEach { uri -> runCatching { resolver.releasePersistableUriPermission(Uri.parse(uri),Intent.FLAG_GRANT_READ_URI_PERMISSION) } } }
    private suspend fun finish(record: NativeDocumentRecord) { release(record);cameraOutputs?.cleanup(record.id);warm.remove(record.id);holdOnReturn.remove(record.id);picker.finish(record.id);if(restoredWaitId==record.id)restoredWaitId=null }
    private suspend fun fail(id: String,error: Exception,text: NativeL10n) {
        runCatching { cameraOutputs?.revoke(id) }
        runCatching { journal.update(id) { if(it.terminal)it else it.copy(phase="failed") } }
        warm[id]?.let { session.photoSelectionFailure(it,text.tr((error as? NativeFileIntakeFailure)?.key ?: NativeFileIntakeFailure.UNAVAILABLE)) }
        picker.finish(id);if(restoredWaitId==id)restoredWaitId=null
    }
    fun retry(record: NativeDocumentRecord,text: NativeL10n) {
        if(!entryOpen||record.id in jobs||record.sources.isEmpty()||rows.none { it==record })return
        jobs[record.id]=record.destination
        launchOwned {
            try { process(journal.update(record.id) { check(it==record);it.copy(phase="selected") },text) }
            catch(cancel: CancellationException) { throw cancel }
            catch(error: Exception) { fail(record.id,error,text) }
            catch(error: OutOfMemoryError) { fail(record.id,IllegalStateException(NativeFileIntakeFailure.UNAVAILABLE,error),text) }
            finally { jobs.remove(record.id);runCatching { refresh() } }
        }
    }
    fun retire(id: String) {
        if(!entryOpen||id in jobs)return
        jobs[id]=rows.firstOrNull { it.id==id }?.destination ?: warm[id]?.destination
        launchOwned {
            try {
                val record=journal.records().singleOrNull { it.id==id }
                if(record!=null) {
                    payloads.snapshot().records.firstOrNull { it.id==id }?.let { payloads.discard(id) }
                    journal.update(id) { it.copy(phase="retired") };finish(record)
                }else picker.finish(id)
                refresh()
            }catch(error: Exception) { failed=true }
            finally { jobs.remove(id) }
        }
    }
    fun endWait(id: String) {
        if(!entryOpen)return
        if(cameraOutputs==null) { retire(id);return }
        if(id in jobs||picker.pending?.id!=id)return
        jobs[id]=rows.firstOrNull { it.id==id }?.destination ?: warm[id]?.destination;detaching+=id;holdOnReturn+=id
        launchOwned {
            try {
                val record=journal.records().single { it.id==id };check(record.awaitingResult)
                journal.update(id) { check(it.awaitingResult);it.copy(phase="detached") }
                warm.remove(id);picker.finish(id);if(restoredWaitId==id)restoredWaitId=null
                refresh()
            }catch(error: Exception) { failed=true }
            finally {
                jobs.remove(id);detaching.remove(id)
                queuedReturns.remove(id)?.let { receive(id,it) }
            }
        }
    }
}

/** Exceptional local recovery states are explicit; these controls never reopen the OS picker. */
@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun NativeDocumentRecoveryNotice(session: MobileSession,interactive: Boolean) {
    val maximum=minOf(160.dp,(androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp*.3f).dp)
    Column(Modifier.fillMaxWidth().heightIn(max=maximum).verticalScroll(rememberScrollState()).testTag("selection-recovery-notices")) {
        NativeSelectionRecoveryNotice(session.documentPicker,session.documentPicker?.recovery,NativeSelectionKind.DOCUMENT,interactive)
        NativeSelectionRecoveryNotice(session.photoPicker,session.photoPicker?.recovery,NativeSelectionKind.PHOTO,interactive)
        NativeSelectionRecoveryNotice(session.cameraCapture?.selectionOwner,session.cameraCapture?.recovery,NativeSelectionKind.CAMERA,interactive)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable private fun NativeSelectionRecoveryNotice(picker: NativeSelectionOwner?,recovery: NativeSelectionRecovery?,kind: NativeSelectionKind,interactive: Boolean) {
    if(picker==null||recovery==null)return
    val photo=kind==NativeSelectionKind.PHOTO
    val camera=kind==NativeSelectionKind.CAMERA
    val prefix=kind.registryName+"-recovery-"
    val text=currentProductText();val failure=recovery.failures.firstOrNull()
    if(!recovery.failed&&!(recovery.restoredWait&&picker.pending!=null)&&failure==null)return
    Column(Modifier.fillMaxWidth().padding(horizontal=18.dp,vertical=8.dp).testTag(prefix+"notice")) {
        if(recovery.failed) {
            Text(productText(if(camera)"拍照请求暂时无法读取，原记录已保留。"else if(photo)"照片选择记录暂时无法读取，原记录已保留。"else"文件选择记录暂时无法读取，原记录已保留。"),fontSize=13.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick=recovery::retryRestore,enabled=interactive&&!recovery.loading&&!recovery.busy,modifier=Modifier.heightIn(min=48.dp).testTag(prefix+"retry-read")) { Text(productText("重试")) }
        }else {
            if(recovery.restoredWait)picker.pending?.let { original ->
                Text(productText(if(camera)"系统拍照或授权结果尚未确认。"else if(photo)"系统照片选择结果尚未确认。"else"系统文件选择结果尚未确认。"),fontSize=13.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
                Text(productText(if(camera)"结束等待后会保留原记录，迟到结果不会自动加入草稿。"else if(photo)"结束等待后，迟到的照片结果不会加入草稿。"else"结束等待后，迟到的文件结果不会加入草稿。"),fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick={ recovery.endWait(original.id) },enabled=interactive&&!recovery.isProcessing(original.id),modifier=Modifier.heightIn(min=48.dp).testTag(prefix+"end-wait")) { Text(productText("结束等待")) }
            }
            if(failure!=null) {
                Text(productText(if(camera)"拍照结果尚未确认加入原草稿，原记录已保留。"else if(photo)"照片结果尚未加入原草稿，原照片记录已保留。"else"文件结果尚未加入原草稿，原文件记录已保留。"),fontSize=13.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
                FlowRow(horizontalArrangement=Arrangement.spacedBy(12.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                if(failure.sources.isNotEmpty())TextButton(onClick={ recovery.retry(failure,text) },enabled=interactive&&!recovery.isProcessing(failure.id),modifier=Modifier.heightIn(min=48.dp).testTag(prefix+"retry-files")) { Text(productText(if(camera&&failure.phase=="held")"加入原草稿"else"重试")) }
                TextButton(onClick={ recovery.retire(failure.id) },enabled=interactive&&!recovery.isProcessing(failure.id),modifier=Modifier.heightIn(min=48.dp).testTag(prefix+"discard")) { Text(productText(if(camera)"丢弃拍照结果"else if(photo)"丢弃照片结果"else"丢弃文件结果")) }
                }
            }
        }
    }
}

@Composable internal fun NativeDocumentRecoveryResult(picker: NativeSelectionOwner,recovery: NativeSelectionRecovery) {
    val registry=checkNotNull(LocalActivityResultRegistryOwner.current).activityResultRegistry
    val owner=remember(picker,registry) { Any() }
    DisposableEffect(recovery,registry,owner) { recovery.attach(registry,owner);onDispose { recovery.detach(registry,owner) } }
    val text=currentProductText();val focused=androidx.compose.ui.platform.LocalWindowInfo.current.isWindowFocused
    LaunchedEffect(recovery,focused,recovery.loading,recovery.revision,recovery.parentReady) { if(focused)recovery.recoverReady(text) }
    val original=picker.pending ?: return
    val dismissed=original.menuDismissed
    // Journal refreshes happen inside launch. They must not cancel that launch's effect.
    LaunchedEffect(original.id,focused,dismissed,recovery.loading,recovery.permissionRevision,recovery.parentReady) {
        if(original.launched||!focused||!dismissed||recovery.loading||!recovery.parentReady)return@LaunchedEffect
        withFrameNanos { }
        recovery.launch(original,text)
    }
}
