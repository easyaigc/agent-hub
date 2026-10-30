package com.agenthub.android

import android.os.Looper
import android.os.Process
import android.system.Os
import android.system.OsConstants
import java.io.Closeable
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*

/** Independent inbox authority; shares only the tested kernel/local exclusion implementation with raw voice. */
internal object NativeInboxOwnership {
    class Busy: IllegalStateException("附件收件箱仍在处理，请完成后再迁移。原附件未更改。")
    internal fun requireControlFile(file: File) {
        val s=Os.lstat(file.path);require(OsConstants.S_ISREG(s.st_mode)&&s.st_uid==Process.myUid()&&s.st_nlink==1L&&s.st_size==0L&&s.st_mode and 0x3f==0)
    }
    private val gate=NativeVoiceRawGate(validate=::requireControlFile,identity={file->Os.lstat(file.path).let {it.st_dev to it.st_ino}})
    internal fun closeSelections(leases: List<SelectionLease>)=closeAll(leases)
    internal fun closeLocalPhotos(leases: List<LocalPhotoLease>)=closeAll(leases)
    private fun closeAll(leases: List<Closeable>) {
        io();var failure: Throwable?=null
        for(held in leases.asReversed())try{held.close()}catch(error: Throwable){if(failure==null)failure=error else failure.addSuppressed(error)}
        failure?.let {throw it}
    }
    private fun io(){check(Looper.myLooper()!=Looper.getMainLooper())}
    private fun file(storage: NativeSessionStorage)=File(storage.coordinationKey+".inbox-writer-v1.lock")
    private fun writer(storage: NativeSessionStorage): NativeVoiceRawGate.Writer {
        io();storage.requireInboxWriterOwner()
        return try {gate.writer(file(storage),UUID.randomUUID().toString())}catch(_: NativeVoiceRawGate.Busy){throw Busy()}
    }
    suspend fun <T> writing(storage: NativeSessionStorage,work: suspend ()->T): T {
        var held: NativeVoiceRawGate.Writer?=null
        try {
            withContext(Dispatchers.IO){held=writer(storage);held!!.requireCurrent()}
            currentCoroutineContext().ensureActive();return work()
        }finally {withContext(NonCancellable+Dispatchers.IO){held?.close()}}
    }
    /** Same kernel/local gate as inbox operations; only the actual selection owner can acquire it. */
    class SelectionLease private constructor(private val root: NativeSessionStorage,val operation: String,
        internal val owner: NativeSelectionRecovery,private val stores: List<NativeSessionStorage>,
        private val frozen: List<NativeVoiceRawGate.Frozen>) : Closeable {
        val kind get()=owner.migrationKind
        val keys get()=stores.map {it.coordinationKey}
        fun requireCurrent(actualRoot: NativeSessionStorage,id: String) {
            io();check(root===actualRoot&&operation==id&&keys==root.migrationSelectionKeys(kind))
            owner.requireMigrationSealed(operation)
            check(!owner.migrationReturnPending) {"Original selection return invalidated the migration lease"}
            stores.zip(frozen).forEach {(store,held)->held.requireCurrent(store,operation,file(store))}
        }
        override fun close() {
            io();var failed: Throwable?=null
            frozen.asReversed().forEach {held->try{held.close()}catch(error: Throwable){if(failed==null)failed=error else failed!!.addSuppressed(error)}}
            failed?.let {throw it}
        }
        companion object {
            fun acquire(root: NativeSessionStorage,operation: String,owner: NativeSelectionRecovery): SelectionLease {
                io();require(UUID.fromString(operation).toString()==operation);owner.requireMigrationSealed(operation)
                val stores=owner.migrationStores(root);check(stores.map {it.coordinationKey}==root.migrationSelectionKeys(owner.migrationKind))
                owner.requireNoCameraWriterForMigration()
                val frozen=mutableListOf<NativeVoiceRawGate.Frozen>()
                try {
                    for(store in stores){store.requireInboxWriterOwner();frozen+=gate.freeze(file(store),store,operation)}
                    return SelectionLease(root,operation,owner,stores,frozen.toList())
                }catch(error: Throwable){frozen.asReversed().forEach {runCatching {it.close()}};if(error is NativeVoiceRawGate.Busy)throw Busy();throw error}
            }
        }
    }
    class LocalPhotoLease private constructor(private val root: NativeSessionStorage,val operation: String,val kind: NativeMigrationPhotoLocalKind,
        private val owners: NativeMigrationPhotoOwners,private val storage: NativeSessionStorage,private val frozen: NativeVoiceRawGate.Frozen): Closeable {
        val key get()=storage.coordinationKey
        fun requireCurrent(actualRoot: NativeSessionStorage,id: String,expected: String) {
            io();check(root===actualRoot&&operation==id&&key==expected&&owners.store(root,kind)===storage)
            owners.requireSealed(kind,operation);frozen.requireCurrent(storage,operation,file(storage))
        }
        override fun close(){io();frozen.close()}
        companion object {
            fun acquire(root: NativeSessionStorage,operation: String,owners: NativeMigrationPhotoOwners,kind: NativeMigrationPhotoLocalKind): LocalPhotoLease {
                io();require(UUID.fromString(operation).toString()==operation);owners.requireSealed(kind,operation)
                val storage=owners.store(root,kind);storage.requireInboxWriterOwner()
                return try {LocalPhotoLease(root,operation,kind,owners,storage,gate.freeze(file(storage),storage,operation))}catch(_: NativeVoiceRawGate.Busy){throw Busy()}
            }
        }
    }
    class Lease private constructor(private val root: NativeSessionStorage,private val child: NativeSessionStorage,
        val operation: String,internal val owner: NativeExternalAttachments,private val frozen: NativeVoiceRawGate.Frozen): Closeable {
        val childKey get()=child.coordinationKey
        fun requireCurrent(actualRoot: NativeSessionStorage,id: String,key: String) {
            io();check(root===actualRoot&&operation==id&&key==childKey&&root.migrationInboxKey()==childKey)
            owner.requireMigrationSealed(operation)
            frozen.requireCurrent(child,operation,file(child))
        }
        override fun close(){io();frozen.close()}
        companion object {
            fun acquire(root: NativeSessionStorage,child: NativeSessionStorage,operation: String,owner: NativeExternalAttachments): Lease {
                io();require(UUID.fromString(operation).toString()==operation);owner.requireMigrationSealed(operation)
                child.requireInboxWriterOwner();check(root.migrationInboxKey()==child.coordinationKey)
                val frozen=try {gate.freeze(file(child),child,operation)}catch(_: NativeVoiceRawGate.Busy){throw Busy()}
                return Lease(root,child,operation,owner,frozen)
            }
        }
    }
}
