package com.agenthub.android

/** Reuses the real Session lease and frozen installation writer. No new owner or pending ledger. */
internal object NativeMigrationPrivateClaimBoundary {
    fun contains(names: Set<String>)=names.any(NativeMigrationPrivateClaims::belongsToFamily)
    fun requireCurrent(storage: NativeSessionStorage,names: Set<String>,owner: NativeMigrationOwnerLease?) {
        if(!contains(names))return
        val lease=requireNotNull(owner){"Private claim originals require the actual migration Session owner"}
        check(lease.storage===storage){"Private claim originals belong to another installation"}
        lease.requireCurrent()
        lease.requireRawWriters() // Exact original installation/operation, held through publication.
    }
}
