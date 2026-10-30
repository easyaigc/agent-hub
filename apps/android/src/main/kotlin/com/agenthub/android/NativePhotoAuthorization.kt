package com.agenthub.android

import androidx.compose.runtime.*
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject

/** One durable first request per native permission group. It never reads media or submits input. */
internal class NativePhotoAuthorization(private val storage: NativeSessionStorage,permissions: Array<String>) {
    private val permissions=permissions.sorted()
    @Volatile private var migrationOperation: String?=null
    @Volatile private var retired=false
    private var resumedOperation: String?=null
    private val entryOpen get()=migrationOperation==null&&!retired
    internal val migrationStorage get()=storage
    internal val migrationIdle get()=!retired&&!busy
    internal fun sealMigrationInputs(operation: String){check(android.os.Looper.myLooper()==android.os.Looper.getMainLooper());check(entryOpen);if(!migrationIdle)throw NativeInboxOwnership.Busy();migrationOperation=operation}
    internal fun requireMigrationSealed(operation: String){check(!retired&&migrationOperation==operation&&!busy)}
    internal fun resumeMigrationInputs(operation: String){if(migrationOperation==null&&resumedOperation==operation)return;requireMigrationSealed(operation);migrationOperation=null;resumedOperation=operation}
    internal fun retireForMigration(operation: String){requireMigrationSealed(operation);retired=true;storage.retireWriter()}
    var busy by mutableStateOf(false)
        private set
    var failed by mutableStateOf(false)
        private set
    private var owner: String?=null
    internal var launchId: String? by mutableStateOf(null)
        private set
    private var launched=false
    private val record="attempt-"+when {
        "android.permission.READ_MEDIA_VISUAL_USER_SELECTED" in permissions -> "selected"
        "android.permission.READ_MEDIA_IMAGES" in permissions -> "images"
        else -> "external"
    }
    suspend fun claim(id: String,automatic: Boolean): Boolean {
        if(!entryOpen||busy||permissions.isEmpty())return false
        busy=true;owner=id;failed=false
        var claimed=false
        try {
            claimed=withContext(Dispatchers.IO+NonCancellable) {
                NativeInboxOwnership.writing(storage){storage.operationTransaction {
                    val attempted=storage.read(record)?.let { raw ->
                        val value=JSONObject(raw)
                        require(value.keys().asSequence().toSet()==setOf("version","permissions","attempted")&&value.getInt("version")==1)
                        val rows=value.getJSONArray("permissions")
                        require((0 until rows.length()).map { rows.getString(it) }==permissions&&value.getBoolean("attempted"))
                        true
                    } ?: false
                    if(automatic&&attempted)false else {
                        // Persist before the OS can take focus; process death never repeats a first prompt.
                        storage.write(record,JSONObject().put("version",1).put("permissions",JSONArray(permissions)).put("attempted",true).toString())
                        true
                    }
                }}
            }
            currentCoroutineContext().ensureActive()
            return claimed
        } catch(cancel: CancellationException) { claimed=false;throw cancel }
          catch(failure: Exception) { claimed=false;failed=true;return false }
        finally { if(!claimed&&owner==id) { owner=null;busy=false } }
    }
    fun requestLaunch(id: String) { if(entryOpen&&owner==id&&busy)launchId=id }
    fun takeLaunch(id: String): Array<String>? {
        if(!entryOpen||launchId!=id||owner!=id||launched)return null
        launched=true;return permissions.toTypedArray()
    }
    fun complete() { if(!entryOpen)return;owner=null;busy=false;launchId=null;launched=false }
    fun launchFailed() { if(!entryOpen)return;complete();failed=true }
}

/** Registered outside the sheet so closing it cannot discard the permission completion. */
@Composable internal fun NativePhotoAuthorizationResult(session: MobileSession) {
    val authorization=session.photoAuthorization ?: return
    key(authorization) {
        val launcher=rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            authorization.complete();session.recentPhotos?.refresh()
        }
        val id=authorization.launchId
        LaunchedEffect(id) {
            if(id==null)return@LaunchedEffect
            val permissions=authorization.takeLaunch(id) ?: return@LaunchedEffect
            try { launcher.launch(permissions) }catch(failure: Exception) { authorization.launchFailed() }
        }
    }
}
