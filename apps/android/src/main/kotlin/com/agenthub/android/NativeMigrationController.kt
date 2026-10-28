package com.agenthub.android

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.*
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.File
import java.util.UUID

internal enum class NativeMigrationPhase { IDLE,CHECKING,PREPARING_EXPORT,CHOOSE_EXPORT,CHOOSE_IMPORT,STAGING,PREVIEW,APPLYING,RESTORING,DONE,FAILED,UNKNOWN }
internal enum class NativeMigrationResult { SAVED,CANCELLED,READY,IMPORTED,PREVIOUS_IMPORT,INVALID,UNSUPPORTED,NEW_INSTALLATION,SAVE_FAILED,RESTORE_FAILED,UNKNOWN,NOT_READY }
internal enum class NativeMigrationPickerKind { IMPORT,EXPORT }
internal data class NativeMigrationPicker(val id: String,val kind: NativeMigrationPickerKind,val launched: Boolean=false)
internal data class NativeMigrationPreview(val computers: Int,val drafts: Int,val operations: Int,val attachments: Int,val notificationArchives: Int)

/** Main state owner. The existing exporter/candidate/activation are the only data/migration rules. */
@Stable internal class NativeMigrationController(context: Context) {
    private val context=context.applicationContext
    private val documents=NativeMigrationDocuments(this.context)
    private val tasks=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private var host: NativeMigrationLifecycle?=null
    private var job: Job?=null
    private var working by mutableStateOf(false)
    private var hostRevision by mutableStateOf(0L)
    @Volatile private var cancelRequested=false
    @Volatile private var activeDocumentId: String?=null
    private var uncertainInput: File?=null
    private val issued=NativeMigrationPickerKind.entries.associateWith {java.util.ArrayDeque<NativeMigrationPicker>()}
    private var pickerPassword=""
    private var candidate: NativeMigrationCandidateStore.Receipt?=null
    private var candidateStorage: NativeSessionStorage?=null
    private var exportFile: File?=null
    private var pendingOwner: NativeMigrationOwnerLease?=null
    private var ownerOutcome: NativeMigrationResult?=null
    private var afterRestore: (()->Unit)?=null
    private var uncertainReceipt: NativeMigrationCandidateStore.Receipt?=null
    var presented by mutableStateOf(false);private set
    var phase by mutableStateOf(NativeMigrationPhase.IDLE);private set
    var result by mutableStateOf<NativeMigrationResult?>(null);private set
    var password by mutableStateOf("")
    var preview by mutableStateOf<NativeMigrationPreview?>(null);private set
    var picker by mutableStateOf<NativeMigrationPicker?>(null);private set
    var replaceConfirmation by mutableStateOf(false);private set
    val busy get()=phase in setOf(NativeMigrationPhase.CHECKING,NativeMigrationPhase.PREPARING_EXPORT,NativeMigrationPhase.STAGING,NativeMigrationPhase.APPLYING,NativeMigrationPhase.RESTORING)
    val ready get()=hostRevision.let {host?.ready==true && !working && pendingOwner==null&&picker==null&&phase!=NativeMigrationPhase.UNKNOWN}
    val passwordValid get()=NativeMigrationCrypto.acceptsPassword(password)
    val canImport get()=ready&&host?.canImport==true
    val canDismiss get()=!working&&picker==null&&pendingOwner==null
    val needsOwnerRestore get()=pendingOwner!=null&&phase!=NativeMigrationPhase.UNKNOWN
    fun attach(value: NativeMigrationLifecycle){host=value;hostRevision++;if(pendingOwner!=null&&phase!=NativeMigrationPhase.UNKNOWN)resumeOwner()}
    fun detach(value: NativeMigrationLifecycle){if(host===value){host=null;hostRevision++}}
    fun show(){presented=true;if(!working&&picker==null&&pendingOwner==null)recoverCandidate()}
    fun close(){if(!canDismiss)return;presented=false;password="";pickerPassword="";replaceConfirmation=false}
    private fun launch(block: suspend ()->Unit) {
        check(!working);cancelRequested=false;working=true
        val startedHost=host
        job=tasks.launch {try{block()}catch(failure: Exception){phase=NativeMigrationPhase.FAILED;result=error(failure)}finally{
            working=false
            if(host!=null&&host!==startedHost&&needsOwnerRestore)resumeOwner()
        }}
    }
    private fun error(failure: Throwable)=when(failure) {
        is CancellationException->NativeMigrationResult.CANCELLED
        is NativeMigrationActivation.ExistingInstallation->NativeMigrationResult.NEW_INSTALLATION
        is NativeMigrationExport.IncompleteScope,is NativeSessionStorage.IncompleteMigrationInventory->NativeMigrationResult.UNSUPPORTED
        is NativeMigrationCandidateStore.InvalidCandidate->NativeMigrationResult.INVALID
        else->NativeMigrationResult.SAVE_FAILED
    }
    private fun preview(receipt: NativeMigrationCandidateStore.Receipt,storage: NativeSessionStorage): NativeMigrationPreview {
        fun from(records: Map<String,String>): NativeMigrationPreview {
            val metadata=JSONObject(records.getValue("metadata"))
            return NativeMigrationPreview(metadata.getJSONArray("clients").length(),metadata.getJSONObject("drafts").length(),
                records.filterKeys {it.startsWith("operations:")}.values.sumOf {JSONObject(it).getJSONArray("operations").length()},
                records.keys.count {it.startsWith("draft-file:")},records[NativeMigrationNotificationArchives.RECORD]?.let {JSONObject(it).getJSONArray("installations").length()}?:0)
        }
        val stream=NativeMigrationStreamCandidate(context,storage)
        return if(stream.isStream(receipt))stream.read(receipt){from(it.text)}else from(NativeMigrationCandidateStore(context,storage).readArchive(receipt).text)
    }
    fun recoverCandidate() {
        val owner=host?.takeIf {it.ready}?:run {result=NativeMigrationResult.NOT_READY;return}
        val storage=owner.currentStorage();val wasUnknown=phase==NativeMigrationPhase.UNKNOWN;launch {
            phase=NativeMigrationPhase.CHECKING
            try {
                val value=withContext(Dispatchers.IO){NativeMigrationCandidateStore(context,storage).currentReceipt()}
                if(value!=null) {val info=withContext(Dispatchers.IO){preview(value,storage)};candidate=value;candidateStorage=storage;preview=info;phase=NativeMigrationPhase.PREVIEW;result=NativeMigrationResult.READY}
                else {candidate=null;candidateStorage=null;preview=null;val applied=withContext(Dispatchers.IO){storage.migrationPublicationReceipt()};phase=NativeMigrationPhase.IDLE;result=if(applied!=null)NativeMigrationResult.PREVIOUS_IMPORT else null}
                uncertainInput?.let {saved->withContext(NonCancellable+Dispatchers.IO){documents.remove(saved)}};uncertainInput=null
            }catch(failure: Exception){phase=if(wasUnknown)NativeMigrationPhase.UNKNOWN else NativeMigrationPhase.FAILED;result=if(wasUnknown)NativeMigrationResult.UNKNOWN else error(failure)}
        }
    }
    fun export() {
        val owner=host?.takeIf {ready&&passwordValid}?:return;val passphrase=password;password="";val id=UUID.randomUUID().toString();afterRestore=null
        launch {
            phase=NativeMigrationPhase.PREPARING_EXPORT;result=null;var lease: NativeMigrationOwnerLease?=null
            val file=withContext(Dispatchers.IO){documents.file(id,"export")}
            try {
                val held=owner.quiesce(id);lease=held;held.requireCurrent()
                withContext(Dispatchers.IO){NativeMigrationExport(context,held.storage).sealFile(file,passphrase)}
                currentCoroutineContext().ensureActive();held.requireCurrent();exportFile=file
                afterRestore={phase=NativeMigrationPhase.CHOOSE_EXPORT;picker=NativeMigrationPicker(id,NativeMigrationPickerKind.EXPORT)}
            }catch(failure: Exception){withContext(NonCancellable+Dispatchers.IO){runCatching {documents.remove(file)}};phase=NativeMigrationPhase.FAILED;result=error(failure)}
            finally {if(lease!=null)finishOwner(lease)}
        }
    }
    fun chooseImport(replace: Boolean=false) {
        if(!canImport||!passwordValid)return
        if(candidate!=null&&!replace){replaceConfirmation=true;return}
        replaceConfirmation=false;pickerPassword=password;password="";phase=NativeMigrationPhase.CHOOSE_IMPORT
        picker=NativeMigrationPicker(UUID.randomUUID().toString(),NativeMigrationPickerKind.IMPORT)
    }
    fun declineReplacement(){replaceConfirmation=false}
    fun claimPicker(): NativeMigrationPicker?=picker?.takeUnless {it.launched}?.also {request->
        check(issued.getValue(request.kind).size<16);issued.getValue(request.kind).addLast(request);picker=request.copy(launched=true)
    }
    fun completePicker(kind: NativeMigrationPickerKind,uri: Uri?){issued.getValue(kind).pollFirst()?.let {receivePicker(it.id,kind,uri)}}
    fun pickerFailed(id: String){if(picker?.id==id){issued.values.forEach {queue->queue.removeIf {it.id==id}};picker=null;pickerPassword="";phase=NativeMigrationPhase.FAILED;result=NativeMigrationResult.SAVE_FAILED}}
    fun receivePicker(id: String,kind: NativeMigrationPickerKind,uri: Uri?) {
        if(picker?.id!=id||picker?.kind!=kind)return
        picker=null
        if(uri==null){pickerPassword="";result=NativeMigrationResult.CANCELLED;phase=if(candidate!=null)NativeMigrationPhase.PREVIEW else NativeMigrationPhase.IDLE
            exportFile?.let {file->tasks.launch(Dispatchers.IO){runCatching {documents.remove(file)}}};exportFile=null;return}
        if(kind==NativeMigrationPickerKind.EXPORT)saveExport(id,uri)else stageImport(id,uri)
    }
    private fun saveExport(id: String,uri: Uri) {
        val file=exportFile?:return;launch {
            phase=NativeMigrationPhase.PREPARING_EXPORT;activeDocumentId=id
            try {val caller=currentCoroutineContext();withContext(Dispatchers.IO){documents.saveOutput(id,file,uri){caller.ensureActive();check(!cancelRequested)}};phase=NativeMigrationPhase.DONE;result=NativeMigrationResult.SAVED}
            catch(failure: Exception){phase=NativeMigrationPhase.FAILED;result=error(failure)}
            finally {activeDocumentId=null;withContext(NonCancellable+Dispatchers.IO){runCatching {documents.remove(file)}};exportFile=null}
        }
    }
    private fun stageImport(id: String,uri: Uri) {
        val owner=host?.takeIf {it.ready&&it.canImport}?:run {pickerPassword="";phase=NativeMigrationPhase.FAILED;result=NativeMigrationResult.NOT_READY;return}
        val passphrase=pickerPassword;pickerPassword="";val storage=owner.currentStorage();val replaced=candidate
        launch {
            phase=NativeMigrationPhase.STAGING;result=null;var file: File?=null;activeDocumentId=id
            try {
                val caller=currentCoroutineContext()
                val input=withContext(Dispatchers.IO){documents.copyInput(id,uri){caller.ensureActive();check(!cancelRequested)}};file=input
                val value=withContext(Dispatchers.IO){NativeMigrationCandidateStore(context,storage).stageFile(id,input,passphrase,replaced){caller.ensureActive();check(!cancelRequested)}}
                val info=withContext(Dispatchers.IO){preview(value,storage)};candidate=value;candidateStorage=storage;preview=info;phase=NativeMigrationPhase.PREVIEW;result=NativeMigrationResult.READY
            }catch(failure: Exception) {
                // A lost staging acknowledgement is observed, never automatically restaged with a new ID.
                val observed=withContext(NonCancellable+Dispatchers.IO){runCatching {NativeMigrationCandidateStore(context,storage).currentReceipt()}}
                val value=observed.getOrNull()?.takeIf {it.id==id}
                if(value!=null){candidate=value;candidateStorage=storage;preview=withContext(NonCancellable+Dispatchers.IO){preview(value,storage)};phase=NativeMigrationPhase.PREVIEW;result=NativeMigrationResult.READY}
                else {phase=if(observed.isFailure)NativeMigrationPhase.UNKNOWN else NativeMigrationPhase.FAILED;result=if(observed.isFailure)NativeMigrationResult.UNKNOWN else error(failure)}
            }finally {activeDocumentId=null;file?.let {saved->if(phase==NativeMigrationPhase.UNKNOWN)uncertainInput=saved else withContext(NonCancellable+Dispatchers.IO){runCatching {documents.remove(saved)}}}}
        }
    }
    fun confirmImport() {
        val owner=host?.takeIf {canImport}?:return;val receipt=candidate?:return;val expected=candidateStorage?:return;val id=UUID.randomUUID().toString()
        if(owner.currentStorage()!==expected){result=NativeMigrationResult.NOT_READY;return};afterRestore=null
        launch {
            phase=NativeMigrationPhase.APPLYING;result=null;var lease: NativeMigrationOwnerLease?=null;var uncertain=false
            try {
                val held=owner.quiesce(id);lease=held;held.requireCurrent();check(held.storage===expected)
                val caller=currentCoroutineContext()
                withContext(Dispatchers.IO){NativeMigrationActivation(context,held.storage,held.notification(receipt)).activate(receipt){caller.ensureActive();check(!cancelRequested);held.requireCurrent()}}
                candidate=null;preview=null;result=NativeMigrationResult.IMPORTED;afterRestore={phase=NativeMigrationPhase.DONE}
            }catch(failure: Exception) {
                val held=lease;val observed=if(held!=null)withContext(NonCancellable+Dispatchers.IO){runCatching {held.storage.migrationPublicationReceipt()}}else Result.success(null)
                if(observed.getOrNull()==receipt){candidate=null;preview=null;result=NativeMigrationResult.IMPORTED;afterRestore={phase=NativeMigrationPhase.DONE}}
                else if(observed.isFailure){uncertain=true;uncertainReceipt=receipt;pendingOwner=lease;phase=NativeMigrationPhase.UNKNOWN;result=NativeMigrationResult.UNKNOWN}
                else {phase=NativeMigrationPhase.FAILED;result=error(failure)}
            }finally {if(lease!=null&&!uncertain)finishOwner(lease)}
        }
    }
    fun checkUnknown() {
        if(working)return
        val lease=pendingOwner?:run {recoverCandidate();return};val receipt=uncertainReceipt?:return
        launch {
            val observed=withContext(Dispatchers.IO){runCatching {lease.storage.migrationPublicationReceipt()}}
            if(observed.isFailure){phase=NativeMigrationPhase.UNKNOWN;result=NativeMigrationResult.UNKNOWN;return@launch}
            if(observed.getOrNull()==receipt){candidate=null;preview=null;result=NativeMigrationResult.IMPORTED;afterRestore={phase=NativeMigrationPhase.DONE}}
            else {result=NativeMigrationResult.CANCELLED;afterRestore={phase=NativeMigrationPhase.PREVIEW}}
            uncertainReceipt=null;finishOwner(lease)
        }
    }
    private suspend fun finishOwner(lease: NativeMigrationOwnerLease) {
        if(result!=NativeMigrationResult.RESTORE_FAILED)ownerOutcome=result
        pendingOwner=lease;phase=NativeMigrationPhase.RESTORING
        val owner=host?:return
        try {
            val required=lease.requiresExplicitEnrollment ?: withContext(NonCancellable+Dispatchers.IO){NativeMigrationNotificationArchives.requiresExplicitSetup(lease.storage.reopen(context))}.also {lease.requiresExplicitEnrollment=it}
            withContext(NonCancellable+Dispatchers.Main.immediate){owner.restore(lease,required)}
            if(host!==owner)return
            // Candidate slots belong to the installation generation. Rebind only an exact,
            // fully validated original after the Activity replaces the storage handle.
            val fresh=owner.currentStorage()
            val observed=withContext(NonCancellable+Dispatchers.IO){NativeMigrationCandidateStore(context,fresh).currentReceipt()}
            candidate=observed;candidateStorage=if(observed!=null)fresh else null
            preview=observed?.let {withContext(NonCancellable+Dispatchers.IO){preview(it,fresh)}}
            pendingOwner=null
            result=ownerOutcome;ownerOutcome=null
            phase=if(candidate!=null)NativeMigrationPhase.PREVIEW else if(result==NativeMigrationResult.IMPORTED)NativeMigrationPhase.DONE else NativeMigrationPhase.IDLE
            afterRestore?.invoke();afterRestore=null
        }catch(_: Exception){phase=NativeMigrationPhase.FAILED;result=NativeMigrationResult.RESTORE_FAILED}
    }
    fun resumeOwner() {val lease=pendingOwner?:return;if(working)return;launch {finishOwner(lease)}}
    fun cancel() {
        val request=picker
        if(request!=null){picker=null;pickerPassword="";phase=if(candidate!=null)NativeMigrationPhase.PREVIEW else NativeMigrationPhase.IDLE;result=NativeMigrationResult.CANCELLED
            exportFile?.let {file->tasks.launch(Dispatchers.IO){runCatching {documents.remove(file)}}};exportFile=null;return}
        if(!working){password="";result=NativeMigrationResult.CANCELLED;presented=false;return}
        cancelRequested=true;job?.cancel();val active=activeDocumentId;tasks.launch(Dispatchers.IO){active?.let(documents::cancel)}
    }
}
