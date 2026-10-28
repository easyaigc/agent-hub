package com.agenthub.android

/** Main-owned actual Session boundary. Implementations stop/join real workers and flush local writes. */
internal interface NativeMigrationLifecycle {
    val ready: Boolean
    val canImport: Boolean
    fun currentStorage(): NativeSessionStorage
    suspend fun quiesce(operationId: String): NativeMigrationOwnerLease
    /** Always restore a fresh handle/Session after retirement, including export/cancel/failure. */
    suspend fun restore(lease: NativeMigrationOwnerLease,requiresExplicitEnrollment: Boolean)
}

/** Captured installation only. No wire/remote operation is exposed by this lease. */
internal class NativeMigrationOwnerLease(val storage: NativeSessionStorage,val operationId: String,
    private val quiescent: ()->Boolean) {
    /** Main publishes the completed owner replacement once; rotation may then re-observe it. */
    internal var restoredStorage: NativeSessionStorage?=null
    internal var requiresExplicitEnrollment: Boolean?=null
    fun requireCurrent(){check(quiescent()) {"Migration owner is no longer quiescent"}}
    fun notification(receipt: NativeMigrationCandidateStore.Receipt): NativeMigrationNotificationActivationLease {
        requireCurrent();return NativeMigrationNotificationActivationLease(storage,receipt,quiescent)
    }
}

/** IO-only installation preparation; Main receives already opened exact-generation children. */
internal fun prepareNativeMigrationStorage(context: android.content.Context,original: NativeSessionStorage): NativeSessionStorage {
    check(android.os.Looper.myLooper()!=android.os.Looper.getMainLooper())
    return original.reopen(context).also {store->
        for(name in listOf("external-inbox","recent-photos","photo-permission"))store.prepareChild(context,name)
        for(name in listOf("document-results","photo-results","camera-results")) {
            val results=store.prepareChild(context,name);results.prepareChild(context,"payloads")
            if(name=="camera-results")results.prepareChild(context,"outputs")
        }
    }
}
