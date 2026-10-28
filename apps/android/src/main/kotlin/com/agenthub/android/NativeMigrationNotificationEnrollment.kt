package com.agenthub.android

import androidx.compose.runtime.*
import kotlinx.coroutines.*

/** Production coordinator starts closed. Only a durable explicit enabled preference releases
 * a migrated installation; opening settings, foregrounding or reading archives cannot do so. */
internal class NativeMigrationNotificationEnrollment(private val storage: NativeSessionStorage,private val scope: CoroutineScope) {
    @Volatile var allowed=false;private set
    var initialized by mutableStateOf(false);private set
    var required by mutableStateOf(false);private set
    var failed by mutableStateOf(false);private set
    private var revision=0L
    private var job: Job?=null
    fun refresh(changed: ()->Unit) {
        val epoch=++revision;allowed=false;initialized=false;job?.cancel()
        job=scope.launch {
            try {
                val needsSetup=withContext(Dispatchers.IO) {storage.snapshotTransaction {
                    NativeMigrationNotificationArchives.requiresExplicitSetup(storage)&&!NativeChatNotificationStore(storage).preferences().enabled
                }}
                if(epoch!=revision)return@launch
                required=needsSetup;failed=false;allowed=!needsSetup;initialized=true;changed()
            }catch(cancel: CancellationException){throw cancel}
            catch(_: Exception){if(epoch==revision){allowed=false;failed=true;changed()}}
        }
    }
    fun dispose(){revision++;allowed=false;initialized=false;job?.cancel();job=null}
}
