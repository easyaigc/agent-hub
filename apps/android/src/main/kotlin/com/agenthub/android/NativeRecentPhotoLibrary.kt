package com.agenthub.android

import androidx.compose.runtime.*
import com.agenthub.core.AttachmentPreparationRules
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

internal class NativeRecentPhotoHint(val photo: NativeRecentPhoto)

/** Device-local library projection only: no Host, upload or model command interface. */
internal class NativeRecentPhotoLibrary(val source: NativeRecentPhotoSource,private val storage: NativeSessionStorage,
    private val nowSeconds: ()->Double={ System.currentTimeMillis()/1000.0 }) {
    private val tasks=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private var active=false
    private var epoch=0L
    private var observer: (()->Unit)?=null
    private var refreshJob: Job?=null
    private var expiryJob: Job?=null
    private var restoreJob: Job?=null
    private val writes=Mutex()
    private var writeRevision=0L
    private var ignored=emptyList<String>()
    private var baseline: Set<String>?=null
    private var surface: String?=null
    var access by mutableStateOf(source.access())
        private set
    var recent by mutableStateOf<List<NativeRecentPhoto>>(emptyList())
        private set
    var suggestion by mutableStateOf<NativeRecentPhotoHint?>(null)
        private set
    var revision by mutableIntStateOf(0)
        private set
    var ready by mutableStateOf(false)
        private set
    var loading by mutableStateOf(false)
        private set
    var readFailed by mutableStateOf(false)
        private set
    var preferencesFailed by mutableStateOf(false)
        private set
    var saving by mutableStateOf(false)
        private set
    init { retryPreferences() }
    fun retryPreferences() {
        if(restoreJob?.isActive==true||saving)return
        stopReading();ready=false;preferencesFailed=false;baseline=null;suggestion=null
        val restore=storage.beginRestore()
        restoreJob=tasks.launch {
            try {
                val restored=withContext(Dispatchers.IO) {
                    storage.read(PREFERENCES)?.let { raw ->
                        val value=JSONObject(raw)
                        require(value.keys().asSequence().toSet()==setOf("version","ignored")&&value.getInt("version")==1)
                        val rows=value.getJSONArray("ignored");require(rows.length()<=256)
                        (0 until rows.length()).map { index -> rows.getString(index).also { id ->
                            require(id.matches(Regex("content://media/external/images/media/[0-9]{1,20}")))
                        } }.also { require(it.distinct().size==it.size) }
                    } ?: emptyList()
                }
                storage.finishRestore(restore) { ignored=restored;ready=true }
                startReading()
            }catch(cancel: CancellationException) { throw cancel }
             catch(failure: Exception) { preferencesFailed=true;ready=false }
        }
    }
    fun setActive(value: Boolean) {
        val current=source.access()
        if(active==value&&access==current)return
        active=value
        stopReading()
        if(access!=current)baseline=null
        access=current;recent=emptyList();suggestion=null
        if(active)startReading()
    }
    private fun stopReading() {
        epoch++;refreshJob?.cancel();refreshJob=null;expiryJob?.cancel();expiryJob=null
        observer?.invoke();observer=null;loading=false
    }
    private fun startReading() {
        if(!active||!ready||preferencesFailed)return
        val current=source.access()
        if(current!=access) { baseline=null;suggestion=null;access=current }
        if(access==NativePhotoAccess.NONE) { recent=emptyList();return }
        if(observer==null)try { observer=source.observe(::refresh) }catch(failure: Exception) { readFailed=true;return }
        refresh()
    }
    fun refresh() {
        if(!active||!ready||preferencesFailed)return
        val current=source.access()
        if(current!=access) {
            stopReading();access=current;baseline=null;suggestion=null;recent=emptyList()
            startReading();return
        }
        if(access==NativePhotoAccess.NONE)return
        refreshJob?.cancel();val flight=++epoch;loading=true;readFailed=false
        refreshJob=tasks.launch {
            try {
                delay(250)
                val snapshot=source.snapshot(64)
                ensureActive();if(!active||flight!=epoch||source.access()!=access)return@launch
                val previous=baseline;baseline=snapshot.map { it.id }.toSet()
                recent=snapshot.take(30);revision++
                val existing=suggestion
                if(existing!=null&&(!qualifies(existing.photo,true)||existing.photo.id !in baseline.orEmpty()))suggestion=null
                if(suggestion==null&&previous!=null&&access==NativePhotoAccess.FULL) {
                    recent.firstOrNull { it.id !in previous&&qualifies(it,true) }?.let { photo ->
                        suggestion=NativeRecentPhotoHint(photo);expire(suggestion!!)
                    }
                }
            }catch(cancel: CancellationException) { throw cancel }
             catch(failure: Exception) { if(active&&flight==epoch) { readFailed=true;recent=emptyList();suggestion=null } }
            finally { if(flight==epoch)loading=false }
        }
    }
    private fun qualifies(photo: NativeRecentPhoto,known: Boolean)=AttachmentPreparationRules.canSuggest(true,access==NativePhotoAccess.FULL,known,
        photo.id in ignored,photo.addedAtSeconds,nowSeconds())
    private fun expire(hint: NativeRecentPhotoHint) {
        expiryJob?.cancel()
        expiryJob=tasks.launch {
            while(active&&suggestion===hint) {
                delay(1_000)
                if(suggestion===hint&&!qualifies(hint.photo,true))suggestion=null
            }
        }
    }
    fun claimSurface(owner: String) { surface=owner }
    fun releaseSurface(owner: String) { if(surface==owner)surface=null }
    fun current(hint: NativeRecentPhotoHint,owner: String)=active&&ready&&!preferencesFailed&&surface==owner&&suggestion===hint&&qualifies(hint.photo,true)
    fun dismiss(hint: NativeRecentPhotoHint,owner: String): Boolean {
        if(!current(hint,owner))return false
        markImported(hint.photo);return true
    }
    /** Called after that original draft's encrypted file and metadata have been confirmed. */
    fun markImported(photo: NativeRecentPhoto) {
        if(!ready||preferencesFailed)return
        if(suggestion?.photo?.id==photo.id) { suggestion=null;expiryJob?.cancel();expiryJob=null }
        if(photo.id !in ignored)ignored=(ignored+photo.id).takeLast(256)
        val write=++writeRevision;saving=true
        tasks.launch { writes.withLock {
            try {
                if(!ready||preferencesFailed)return@withLock
                val value=JSONObject().put("version",1).put("ignored",JSONArray(ignored)).toString()
                withContext(Dispatchers.IO) { storage.write(PREFERENCES,value) }
            }catch(cancel: CancellationException) { throw cancel }
             catch(failure: Exception) { stopReading();preferencesFailed=true;ready=false;suggestion=null;recent=emptyList() }
            finally { if(write==writeRevision)saving=false }
        } }
    }
    fun observeOriginalImport(job: Deferred<Unit>,session: MobileSession,destination: AttachmentImportDestination,beforeIds: Set<String>,photo: NativeRecentPhoto) {
        tasks.launch {
            try {
                job.await()
                val added=session.inboxFiles(destination).filter { it.id !in beforeIds }
                if(added.isNotEmpty()&&session.confirmInboxFiles(destination,added))markImported(photo)
            }catch(cancel: CancellationException) { throw cancel }
             catch(failure: Exception) { /* Failed/retired imports keep the original hint eligible. */ }
        }
    }
    companion object { const val PREFERENCES="preferences";const val PREFERENCES_FAILURE="最近图片记录暂时无法读取，原有记录已保留。请重试。" }
}
