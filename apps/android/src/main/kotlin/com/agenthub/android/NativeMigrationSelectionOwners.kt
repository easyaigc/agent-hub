package com.agenthub.android

/** Actual retained platform owners, never constructed from portable audit data. */
internal data class NativeMigrationSelectionOwners(val document: NativeDocumentPicker?,val photo: NativePhotoPicker?,val camera: NativeCameraCapture?) {
    val recoveries get()=listOfNotNull(document?.recovery,photo?.recovery,camera?.recovery)
    suspend fun seal(operation: String) {
        val sealed=mutableListOf<NativeSelectionRecovery>()
        try {for(owner in recoveries){owner.sealMigrationInputs(operation);sealed+=owner}}
        catch(error: Throwable){sealed.asReversed().forEach {owner->runCatching {owner.resumeMigrationInputs(operation)}.exceptionOrNull()?.let(error::addSuppressed)};throw error}
    }
    suspend fun resume(operation: String){recoveries.forEach {it.resumeMigrationInputs(operation)}}
    suspend fun awaitReturns(operation: String){recoveries.forEach {it.awaitMigrationReturnAudits(operation)}}
    fun requireRetirable(operation: String){recoveries.forEach {it.requireRetirableForMigration(operation)}}
    fun retire(operation: String){requireRetirable(operation);recoveries.forEach {it.retireForMigration(operation)}}
    fun rebind(session: MobileSession,operation: String) {
        document?.rebindAfterMigration(session,operation);photo?.rebindAfterMigration(session,operation);camera?.rebindAfterMigration(session,operation)
    }
}
