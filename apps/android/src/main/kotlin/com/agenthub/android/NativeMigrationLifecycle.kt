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
    internal val rawWriters: NativeVoiceRawGate.Frozen?=null,internal val inboxWriters: NativeInboxOwnership.Lease?=null,
    internal val selectionWriters: List<NativeInboxOwnership.SelectionLease> = emptyList(),
    internal val localPhotoWriters: List<NativeInboxOwnership.LocalPhotoLease> = emptyList(),private val quiescent: ()->Boolean) {
    /** Main publishes the completed owner replacement once; rotation may then re-observe it. */
    internal var restoredStorage: NativeSessionStorage?=null
    internal var requiresExplicitEnrollment: Boolean?=null
    internal fun requireRawWriters(): NativeVoiceRawGate.Frozen {requireCurrent();return requireNotNull(rawWriters).also {NativeVoiceRawOwnership.requireFrozen(storage,operationId,it)}}
    internal fun requireInboxWriters(key: String): NativeInboxOwnership.Lease {requireCurrent();return requireNotNull(inboxWriters).also {it.requireCurrent(storage,operationId,key)}}
    internal fun requireSelectionWriters(kind: NativeMigrationSelectionJournal.Kind): NativeInboxOwnership.SelectionLease {
        requireCurrent();return selectionWriters.single {it.kind==kind}.also {it.requireCurrent(storage,operationId)}
    }
    internal fun requireLocalPhotoWriters(kind: NativeMigrationPhotoLocalKind,key: String): NativeInboxOwnership.LocalPhotoLease {
        requireCurrent();return localPhotoWriters.single {it.kind==kind}.also {it.requireCurrent(storage,operationId,key)}
    }
    internal fun releaseRawWriters(){
        var failure: Throwable?=null
        for(close in localPhotoWriters.asReversed().map {{it.close()}}+selectionWriters.asReversed().map {{it.close()}}+listOf({inboxWriters?.close();Unit},{rawWriters?.close();Unit})) {
            try{close()}catch(error: Throwable){if(failure==null)failure=error else failure.addSuppressed(error)}
        }
        failure?.let {throw it}
    }
    fun requireCurrent(){check(quiescent()) {"Migration owner is no longer quiescent"}}
    fun notification(receipt: NativeMigrationCandidateStore.Receipt): NativeMigrationNotificationActivationLease {
        requireCurrent();return NativeMigrationNotificationActivationLease(storage,receipt,quiescent)
    }
}

/** IO-only installation preparation; Main receives already opened exact-generation children. */
internal fun prepareNativeMigrationStorage(context: android.content.Context,original: NativeSessionStorage,previousInbox: String?=null): NativeSessionStorage {
    check(android.os.Looper.myLooper()!=android.os.Looper.getMainLooper())
    return original.reopen(context).also {store->
        if(previousInbox!=null&&store.migrationInboxKey()!=previousInbox)store.migrationInboxSnapshot(context){check(it.records==null) {"Fresh imported inbox is not empty; original retained"}}
        for(name in listOf("external-inbox","recent-photos","photo-permission"))store.prepareChild(context,name)
        for(name in listOf("document-results","photo-results","camera-results")) {
            val results=store.prepareChild(context,name);results.prepareChild(context,"payloads")
            if(name=="camera-results")results.prepareChild(context,"outputs")
        }
    }
}
