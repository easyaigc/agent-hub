package com.agenthub.android

import androidx.compose.runtime.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Persistence belongs to the existing migration/storage owner. This stage defines no new stored Schema. */
internal interface NativeVoicePreferenceStore {
    suspend fun load(): VoiceInputController.Provider?
    suspend fun save(value: VoiceInputController.Provider)
}

@Stable internal class NativeVoiceSettings(private val tasks: CoroutineScope) {
    enum class PreferenceStatus { SESSION_ONLY, LOADING, SAVING, PERSISTED, FAILED }
    enum class PreferenceFailure { READ, WRITE }
    var defaultProvider by mutableStateOf(VoiceInputController.Provider.DESKTOP);private set
    var preferenceStatus by mutableStateOf(PreferenceStatus.SESSION_ONLY);private set
    var preferenceError by mutableStateOf<PreferenceFailure?>(null);private set
    var preferenceBound by mutableStateOf(false);private set
    val persisted get()=preferenceStatus==PreferenceStatus.PERSISTED
    val preferenceFailure get()=preferenceError!=null
    var capabilities by mutableStateOf<NativeVoiceCapability?>(null);private set
    var checking by mutableStateOf(false);private set
    var inspector: (suspend ()->NativeVoiceCapability)?=null
    private var store: NativeVoicePreferenceStore?=null
    private var revision=0L
    private data class Selection(val value: VoiceInputController.Provider,val revision: Long)
    private var pending: Selection?=null
    private var dirty=false
    private var readNeeded=true
    private var writeAllowed=false
    private var preferencesPaused=false
    private val preferenceIO=Mutex()
    private var preferenceJob: Job?=null
    private var preferenceOwner: Any?=null
    private var preferenceOperation: PreferenceStatus?=null
    private var probe: Job?=null
    private var probeOwner: Any?=null
    fun bind(value: NativeVoicePreferenceStore) {
        check(store==null) { "Voice preference storage is already bound" }
        attach(value,true)
    }
    fun bindIfMissing(create: ()->NativeVoicePreferenceStore)=bindIfMissing(true,create)
    fun bindIfMissing(load: Boolean,create: ()->NativeVoicePreferenceStore): Boolean {
        if(store!=null)return false
        attach(create(),load);return true
    }
    private fun attach(value: NativeVoicePreferenceStore,load: Boolean) {
        store=value;preferenceBound=true;readNeeded=true;writeAllowed=dirty;preferencesPaused=!load
        if(load)resumePreferences()
    }
    fun select(value: VoiceInputController.Provider) {
        defaultProvider=value;revision++;dirty=true;pending=Selection(value,revision);preferenceError=null
        preferenceStatus=preferenceOperation ?: PreferenceStatus.SESSION_ONLY;writeAllowed=true
        startPreferences()
    }
    /** Initial/foreground recovery only resumes unfinished work; repeated configure does not rebind or reread. */
    fun resumePreferences(): Job? { preferencesPaused=false;return if(readNeeded)startPreferences() else preferenceJob }
    /** Read back after recovery/failure. An unknown write is verified before any explicit retry. */
    fun refreshPreferences(): Job? { readNeeded=true;return startPreferences() }
    fun retryPreferences(): Job? { readNeeded=true;writeAllowed=dirty;return startPreferences() }
    private fun startPreferences(): Job? {
        val backend=store ?: return null
        if(preferencesPaused)return null
        preferenceJob?.takeIf { it.isActive }?.let { return it }
        val owner=Any();preferenceOwner=owner
        preferenceJob=tasks.launch {
            fun current()=preferenceOwner===owner && isActive
            try {
                while(current()) {
                    if(readNeeded) {
                        val started=revision;preferenceOperation=PreferenceStatus.LOADING;preferenceStatus=PreferenceStatus.LOADING
                        val saved=try { withContext(Dispatchers.IO) { preferenceIO.withLock { backend.load() } } }
                            catch(cancel: CancellationException) { throw cancel }
                            catch(_: Exception) { if(current()) { preferenceError=PreferenceFailure.READ;preferenceStatus=PreferenceStatus.FAILED;writeAllowed=false };return@launch }
                        if(!current())return@launch
                        preferenceOperation=null
                        readNeeded=false
                        val effective=saved ?: VoiceInputController.Provider.DESKTOP
                        if(!dirty && revision==started)defaultProvider=effective
                        if(defaultProvider==effective) {
                            dirty=false;pending=null;preferenceError=null;preferenceStatus=PreferenceStatus.PERSISTED
                        }else if(dirty) {
                            pending=Selection(defaultProvider,revision)
                            preferenceStatus=if(writeAllowed)PreferenceStatus.SAVING else PreferenceStatus.FAILED
                        }
                    }
                    val requested=pending?.takeIf { dirty && writeAllowed } ?: break
                    pending=null;preferenceOperation=PreferenceStatus.SAVING;preferenceStatus=PreferenceStatus.SAVING
                    try {
                        withContext(Dispatchers.IO) { preferenceIO.withLock { backend.save(requested.value) } }
                        if(!current())return@launch
                        preferenceOperation=null
                        if(defaultProvider==requested.value && (pending==null || pending?.value==requested.value)) {
                            dirty=false;pending=null;preferenceError=null;preferenceStatus=PreferenceStatus.PERSISTED
                        }
                    }catch(cancel: CancellationException) { throw cancel }
                    catch(_: Exception) {
                        if(current()) { pending=Selection(defaultProvider,revision);readNeeded=true;writeAllowed=false;preferenceError=PreferenceFailure.WRITE;preferenceStatus=PreferenceStatus.FAILED }
                        return@launch
                    }
                }
            }catch(cancel: CancellationException) {
                if(preferenceOwner===owner) {
                    readNeeded=true
                    if(preferenceOperation==PreferenceStatus.SAVING) { writeAllowed=false;preferenceError=PreferenceFailure.WRITE;preferenceStatus=PreferenceStatus.FAILED }
                    else preferenceStatus=PreferenceStatus.SESSION_ONLY
                }
                throw cancel
            }finally { if(preferenceOwner===owner) { preferenceJob=null;preferenceOwner=null;preferenceOperation=null } }
        }
        return preferenceJob
    }
    fun pausePreferences() {
        val wasSaving=preferenceOperation==PreferenceStatus.SAVING
        preferencesPaused=true;preferenceOwner=null;preferenceJob?.cancel();preferenceJob=null;preferenceOperation=null;readNeeded=true
        if(wasSaving) { pending=Selection(defaultProvider,revision);dirty=true;writeAllowed=false;preferenceError=PreferenceFailure.WRITE }
        preferenceStatus=if(wasSaving)PreferenceStatus.FAILED else PreferenceStatus.SESSION_ONLY
    }
    fun refreshCapabilities(): Job? {
        if(probe?.isActive==true)return probe
        val read=inspector ?: return null;val owner=Any();probeOwner=owner;checking=true
        probe=tasks.launch {
            try { val result=read();if(probeOwner===owner)capabilities=result }
            catch(cancel: CancellationException) { throw cancel }
            catch(_: Exception) { if(probeOwner===owner)capabilities=NativeVoiceCapability.unconfirmed() }
            finally { if(probeOwner===owner) { checking=false;probe=null;probeOwner=null } }
        }
        return probe
    }
    fun leave() { probeOwner=null;probe?.cancel();probe=null;checking=false;capabilities=null }
}
