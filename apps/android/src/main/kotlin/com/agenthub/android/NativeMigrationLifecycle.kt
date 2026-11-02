package com.agenthub.android

import kotlinx.coroutines.ensureActive

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
    internal var strictRestore: NativeMigrationExistingPolicy.Restore?=null;private set
    private var strictRawReleased=false
    internal fun prepareStorageRestore(){if(strictRestore==null)strictRestore=storage.prepareMigrationRestore(this)}
    internal var requiresExplicitEnrollment: Boolean?=null
    internal fun requireRawWriters(): NativeVoiceRawGate.Frozen {requireCurrent();return requireNotNull(rawWriters).also {NativeVoiceRawOwnership.requireFrozen(storage,operationId,it)}}
    internal fun requireInboxWriters(key: String): NativeInboxOwnership.Lease {requireCurrent();return requireNotNull(inboxWriters).also {it.requireCurrent(storage,operationId,key)}}
    internal fun requireSelectionWriters(kind: NativeMigrationSelectionJournal.Kind): NativeInboxOwnership.SelectionLease {
        requireCurrent();return selectionWriters.single {it.kind==kind}.also {it.requireCurrent(storage,operationId)}
    }
    internal fun requireLocalPhotoWriters(kind: NativeMigrationPhotoLocalKind,key: String): NativeInboxOwnership.LocalPhotoLease {
        requireCurrent();return localPhotoWriters.single {it.kind==kind}.also {it.requireCurrent(storage,operationId,key)}
    }
    internal fun prepareAndReleaseForRestore() {
        var first: Throwable?=null
        try{prepareStorageRestore()}catch(error: Throwable){first=error}
        try{releaseRawWriters(first)}catch(error: Throwable){
            if(first==null)first=error else if(first!==error)first.addSuppressed(error)
        }
        first?.let {throw it}
    }
    internal fun releaseRawWriters(prior: Throwable?=null){
        if(strictRawReleased)return
        var failure: Throwable?=null
        for(close in localPhotoWriters.asReversed().map {{it.close()}}+selectionWriters.asReversed().map {{it.close()}}+listOf({inboxWriters?.close();Unit},{rawWriters?.close();Unit})) {
            try{close()}catch(error: Throwable){if(failure==null)failure=error else failure.addSuppressed(error)}
        }
        failure?.let {error->
            val original=prior?:error;if(original!==error)original.addSuppressed(error)
            if(storage.needsMigrationGenerationGrant)NativeMigrationReadLifetime().hold(this,original)
            throw original
        }
        if(storage.needsMigrationGenerationGrant)strictRawReleased=true
        strictRestore?.let {it.policy.released(it,this)}
    }
    fun requireCurrent(){check(quiescent()) {"Migration owner is no longer quiescent"}}
    fun notification(receipt: NativeMigrationCandidateStore.Receipt): NativeMigrationNotificationActivationLease {
        requireCurrent();return NativeMigrationNotificationActivationLease(storage,receipt,quiescent)
    }
}

/** IO-only installation preparation; Main receives already opened exact-generation children. */
internal fun prepareNativeMigrationStorage(context: android.content.Context,original: NativeSessionStorage,previousInbox: String?=null): NativeSessionStorage {
    check(android.os.Looper.myLooper()!=android.os.Looper.getMainLooper())
    return original.reopen(context).also {store->prepareNativeMigrationChildren(context,store,previousInbox)}
}

internal fun prepareNativeMigrationChildren(context: android.content.Context,store: NativeSessionStorage,previousInbox: String?=null) {
    check(android.os.Looper.myLooper()!=android.os.Looper.getMainLooper())
    run {
        if(previousInbox!=null&&store.migrationInboxKey()!=previousInbox)store.migrationInboxSnapshot(context){check(it.records==null) {"Fresh imported inbox is not empty; original retained"}}
        for(name in listOf("external-inbox","recent-photos","photo-permission"))store.prepareChild(context,name)
        for(name in listOf("document-results","photo-results","camera-results")) {
            val results=store.prepareChild(context,name);results.prepareChild(context,"payloads")
            if(name=="camera-results")results.prepareChild(context,"outputs")
        }
    }
}

/** Shared by Activity and cold native fixture: release has already been confirmed by Controller. */
internal suspend fun restoreNativeMigrationSession(context: android.content.Context,original: MobileSession,lease: NativeMigrationOwnerLease,
    create: (NativeSessionStorage)->MobileSession={MobileSession(it)},beforeCommit: suspend ()->Unit={}): MobileSession {
    check(android.os.Looper.myLooper()==android.os.Looper.getMainLooper())
    check(original.ownsMigrationLease(lease))
    var opened: NativeSessionStorage?=null
    val store=try {
        kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable+kotlinx.coroutines.Dispatchers.IO) {
            // Capture ownership before a cancelled return to Main can discard the result.
            lease.storage.openMigrationRestore(context,lease).also {opened=it}
        }
    }catch(error: Throwable) {
        opened?.let {captured->
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    if(captured.needsMigrationGenerationGrant)NativeMigrationReadLifetime().hold(captured,error)
                    try{captured.retireWriter()}catch(close: Throwable) {
                        if(close!==error)error.addSuppressed(close)
                        NativeMigrationReadLifetime().hold(captured,error)
                    }
                }
            }
        }
        throw error
    }
    val fresh=try{create(store)}catch(error: Throwable){
        kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                if(store.needsMigrationGenerationGrant)NativeMigrationReadLifetime().hold(store,error)
                try{store.retireWriter()}catch(close: Throwable) {
                    if(close!==error)error.addSuppressed(close)
                    NativeMigrationReadLifetime().hold(store,error)
                }
            }
        }
        throw error
    }
    try {
        val (inboxKey,selectionKeys)=kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO){
            prepareNativeMigrationChildren(context,store,lease.inboxWriters?.childKey)
            store.migrationInboxKey() to NativeMigrationSelectionJournal.Kind.entries.associateWith(store::migrationSelectionKeys)
        }
        if(store.needsMigrationGenerationGrant)fresh.restoreBoundMigration(lease)else fresh.restoreForMigration()
        original.awaitSelectionReturnsForMigration(lease.operationId)
        kotlinx.coroutines.currentCoroutineContext().ensureActive()
        beforeCommit()
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO){store.verifyMigrationRestore(lease)}
        val selections=original.takeSelectionsForMigration(lease,selectionKeys)
        fresh.adoptInboxForMigration(original.takeInboxForMigration(lease,inboxKey),lease.operationId)
        fresh.adoptSelectionsForMigration(selections,lease.operationId)
        fresh.completeMigrationStorageRestore(lease)
        // Reading now belongs to the ACTIVE Session's background worker. Its
        // retryable failure must not abandon an already published generation.
        // Keep this ownership publication free of another cancellable wait.
        original.retireForMigration(lease.operationId)
        original.voice?.retireForMigration();original.questionVoice?.retireForMigration()
        lease.restoredStorage=store
        return fresh
    }catch(error: Throwable){
        try{
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable+kotlinx.coroutines.Dispatchers.Main.immediate){fresh.abandonMigrationRestore()}
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable+kotlinx.coroutines.Dispatchers.IO){store.awaitQueuedWrites();store.retireWriter()}
        }
        catch(close: Throwable){if(close!==error)error.addSuppressed(close);kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable+kotlinx.coroutines.Dispatchers.IO){NativeMigrationReadLifetime().hold(fresh,error)}}
        throw error
    }
}
