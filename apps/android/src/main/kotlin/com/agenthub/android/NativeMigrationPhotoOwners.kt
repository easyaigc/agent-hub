package com.agenthub.android

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** Fixed device-local bookkeeping nodes. Neither schema supplies an OS permission or media ID authority. */
internal enum class NativeMigrationPhotoLocalKind(val namespace: String) { RECENTS("recent-photos"),PERMISSION("photo-permission") }

/** Actual Session actors and their exact stores. No caller-supplied ready boolean or portable owner. */
internal class NativeMigrationPhotoOwners(private val session: MobileSession,private val recent: NativeRecentPhotoLibrary?,private val permission: NativePhotoAuthorization?) {
    val kinds get()=buildList {if(recent!=null)add(NativeMigrationPhotoLocalKind.RECENTS);if(permission!=null)add(NativeMigrationPhotoLocalKind.PERMISSION)}
    fun store(root: NativeSessionStorage,kind: NativeMigrationPhotoLocalKind): NativeSessionStorage {
        check(session.migrationStorage===root)
        return when(kind) {
            NativeMigrationPhotoLocalKind.RECENTS->{check(session.recentPhotos===recent);requireNotNull(recent).migrationStorage}
            NativeMigrationPhotoLocalKind.PERMISSION->{check(session.photoAuthorization===permission);requireNotNull(permission).migrationStorage}
        }.also {check(it.coordinationKey==root.migrationPhotoLocalKey(kind))}
    }
    suspend fun seal(operation: String) {
        val sealed=mutableListOf<NativeMigrationPhotoLocalKind>()
        try {
            for(kind in kinds){when(kind){NativeMigrationPhotoLocalKind.RECENTS->recent!!.sealMigrationInputs(operation);NativeMigrationPhotoLocalKind.PERMISSION->permission!!.sealMigrationInputs(operation)};sealed+=kind}
            recent?.awaitMigrationSealed(operation)
        }catch(error: Throwable){
            withContext(NonCancellable){for(kind in sealed.asReversed())try{resume(kind,operation)}catch(cleanup: Throwable){error.addSuppressed(cleanup)}}
            throw error
        }
    }
    fun requireSealed(kind: NativeMigrationPhotoLocalKind,operation: String) {
        when(kind){NativeMigrationPhotoLocalKind.RECENTS->recent!!.requireMigrationSealed(operation);NativeMigrationPhotoLocalKind.PERMISSION->permission!!.requireMigrationSealed(operation)}
    }
    private suspend fun resume(kind: NativeMigrationPhotoLocalKind,operation: String) {
        when(kind){NativeMigrationPhotoLocalKind.RECENTS->recent!!.resumeMigrationInputs(operation);NativeMigrationPhotoLocalKind.PERMISSION->permission!!.resumeMigrationInputs(operation)}
    }
    suspend fun resume(operation: String) {
        var failed: Throwable?=null
        for(kind in kinds)try{resume(kind,operation)}catch(error: Throwable){if(failed==null)failed=error else failed.addSuppressed(error)}
        failed?.let {throw it}
    }
    fun retire(operation: String) {
        kinds.forEach {requireSealed(it,operation)}
        recent?.retireForMigration(operation);permission?.retireForMigration(operation)
    }
}
