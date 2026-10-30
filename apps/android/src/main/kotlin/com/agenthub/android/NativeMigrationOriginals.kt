package com.agenthub.android

import android.content.Context

/** One fixed source graph for export and destination preservation. All native owners stay separate. */
internal object NativeMigrationOriginals {
    /** Only this exact authenticated generation publication may change the parent pointer while
     * old child FD/inventory lifetimes finish. It grants no child writer or callback authority. */
    class Publication(private val storage: NativeSessionStorage,private val receipt: NativeMigrationCandidateStore.Receipt) {
        private var generation: String?=null
        fun confirm(value: String) {
            check(generation==null);require(storage.publishedMigration(receipt.id,receipt.sha256,receipt.recordCount)==value);generation=value
        }
        fun matches(root: NativeSessionStorage): Boolean = root===storage&&generation?.let {value->storage.publishedMigration(receipt.id,receipt.sha256,receipt.recordCount)==value}==true
    }
    private val kinds=NativeMigrationSelectionJournal.Kind.values().toList()
    private fun present(value: NativeMigrationSelectionSource)=value.journal.present||value.payloads.present||value.outputs?.present==true||value.raw?.present==true
    fun <T> withChildren(context: Context,storage: NativeSessionStorage,source: NativeMigrationStreamRecords,owner: NativeMigrationOwnerLease?,publication: Publication?=null,
        consume: (NativeMigrationStreamRecords,()->Unit)->T): T = storage.migrationInboxSnapshot(context,publication) {inbox->
        val selections=mutableListOf<NativeMigrationSelectionSource>()
        val localPhotos=mutableListOf<NativeMigrationPhotoLocalSource>()
        val photoKinds=NativeMigrationPhotoLocalKind.values().toList()
        fun requireOwners() {
            owner?.let {it.requireCurrent();check(it.storage===storage)}
            if(inbox.present)requireNotNull(owner).requireInboxWriters(inbox.key)
            for(selection in selections)if(present(selection))requireNotNull(owner).requireSelectionWriters(selection.kind)
            for(photo in localPhotos)if(photo.present)requireNotNull(owner).requireLocalPhotoWriters(photo.kind,photo.key)
            NativeMigrationPrivateClaimBoundary.requireCurrent(storage,source.text.keys+source.binary.keys,owner)
        }
        fun capture(index: Int): T {
            if(index<kinds.size)return storage.migrationSelectionSnapshot(context,kinds[index],publication) {selection->
                selections.add(selection);try{capture(index+1)}finally{selections.removeAt(selections.lastIndex)}
            }
            val photoIndex=index-kinds.size
            if(photoIndex<photoKinds.size)return storage.migrationPhotoLocalSnapshot(context,photoKinds[photoIndex],publication) {photo->
                localPhotos.add(photo);try{capture(index+1)}finally{localPhotos.removeAt(localPhotos.lastIndex)}
            }
            requireOwners()
            val claims=NativeMigrationPrivateClaims.projectSource(source)
            var records=if(inbox.present)NativeMigrationInbox.append(claims,inbox.records)else claims
            for(selection in selections)if(present(selection))records=NativeMigrationSelections.append(records,selection)
            for(photo in localPhotos)if(photo.present)records=NativeMigrationPhotoLocals.append(records,photo)
            val unchanged={inbox.unchanged();selections.forEach {it.unchanged()};localPhotos.forEach {it.unchanged()};requireOwners();Unit}
            return consume(records,unchanged)
        }
        capture(0)
    }
    /** Exact legacy archive aliases only. Unknown destination legacy files still reject the inventory. */
    private fun legacyNames(read: (String)->String?): Set<String> = buildSet {
        add("metadata");add(NativeVoicePreferenceRecord.RECORD);add(NativeSelectionReturnAudit.RECORD)
        add(NativeMigrationInbox.RECORD);read(NativeMigrationInbox.RECORD)?.let {addAll(NativeMigrationInbox.legacyNames(it))}
        addAll(NativeMigrationSelections.legacyNames(read));addAll(NativeMigrationPhotoLocals.legacyNames(read))
        addAll(NativeMigrationPrivateClaims.legacyNames(read))
    }
    fun <T> withDestination(context: Context,storage: NativeSessionStorage,owner: NativeMigrationOwnerLease?,publication: Publication,consume: (NativeMigrationStreamRecords,()->Unit)->T): T =
        storage.migrationOptionalStreamSnapshot(::legacyNames,NativeMigrationEasyWorkArchives::legacyNames) {root->
            // Placeholder is never merged; it only lets the same child graph represent an empty root.
            withChildren(context,storage,root?:NativeMigrationStreamRecords(mapOf("metadata" to "{}"),emptyMap(),4),owner,publication,consume)
        }
    fun merge(source: NativeMigrationStreamRecords,destination: NativeMigrationStreamRecords): NativeMigrationStreamRecords {
        val claims=NativeMigrationPrivateClaims.merge(source,destination)
        val archives=NativeMigrationPhotoLocals.merge(NativeMigrationSelections.merge(NativeMigrationInbox.merge(claims,destination),destination),destination)
        return archives.copy(text=NativeSelectionReturnAudit.merge(archives.text,destination.text))
    }
}
