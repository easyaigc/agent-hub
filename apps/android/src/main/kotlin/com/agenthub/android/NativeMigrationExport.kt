package com.agenthub.android

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Internal export boundary. Unsupported records reject the whole archive; no best-effort backup. */
internal class NativeMigrationExport(private val context: Context,private val storage: NativeSessionStorage) {
    internal class IncompleteScope(val scopes: Set<String>): IllegalStateException("本机迁移包含尚未支持的资料，原数据未更改。")
    private fun requireSupportedScopes(records: Map<String,String>,allowProvisional: Boolean=false,allowInbox: Boolean=false,allowSelections: Boolean=false) {
        val scopes=storage.migrationPendingChildScopes().toMutableSet()
        if(allowInbox)scopes.remove("external-inbox")
        if(allowSelections)scopes.removeAll(NativeMigrationSelectionJournal.Kind.values().map {it.namespace}.toSet()+NativeMigrationPhotoLocalKind.values().map {it.namespace})
        if(!allowProvisional&&NativeMigrationProvisionalVoice.liveIds(records).isNotEmpty())scopes.add("provisional-voice")
        if(scopes.isNotEmpty())throw IncompleteScope(scopes)
    }
    /** Encrypted file publication only; no plaintext scratch or whole binary/Base64 allocation. */
    fun sealFile(destination: java.io.File, password: String,owner: NativeMigrationOwnerLease?=null,beforePublication: ()->Unit={}) {
        require(NativeMigrationCrypto.acceptsPassword(password))
        require(!destination.canonicalPath.startsWith(context.noBackupFilesDir.canonicalPath + java.io.File.separator))
        storage.migrationStreamSnapshot(::legacyNames,NativeMigrationEasyWorkArchives::legacyNames) { records ->
          NativeMigrationOriginals.withChildren(context,storage,records,owner) {originals,originalsUnchanged->
            requireSupportedScopes(records.text,allowProvisional=true,allowInbox=true,allowSelections=true)
            owner?.let {it.requireCurrent();check(it.storage===storage);it.requireRawWriters()}
            val projected=NativeMigrationProvisionalVoice.project(context,storage,originals,owner)
            val portable=projected.records.copy(text=NativeMigrationEasyWorkBusiness.projectSource(NativeMigrationEasyWorkArchives.projectSource(NativeMigrationForeground.projectSource(projected.records.text)),projected.records.text))
            NativeMigrationCandidateStore(context, storage).validateStreamRecords(portable)
            val atomic = android.util.AtomicFile(destination); val output = atomic.startWrite()
            try {
                NativeMigrationStreamCrypto.write(output, password) { NativeMigrationStreamArchive.write(it, portable, context.packageName) }
                beforePublication() // Fault injection precedes every production publication fence.
                requireSupportedScopes(records.text,allowProvisional=true,allowInbox=true,allowSelections=true);projected.unchanged();originalsUnchanged()
                owner?.requireRawWriters()
                atomic.finishWrite(output)
            } catch (failure: Throwable) { atomic.failWrite(output); throw failure }
          }
        }
    }
    fun seal(password: String): ByteArray {
        require(NativeMigrationCrypto.acceptsPassword(password))
        val clear=capture()
        try{return NativeMigrationCrypto.seal(clear,password)}finally{clear.fill(0)}
    }
    internal fun capture(): ByteArray {
        val original=storage.migrationTypedArchiveSnapshot(::legacyNames,resolveModernAliases=NativeMigrationEasyWorkArchives::legacyNames)
        requireSupportedScopes(original.text)
        NativeMigrationPrivateClaimBoundary.requireCurrent(storage,original.text.keys+original.binary.keys,null)
        val records=original.copy(text=NativeMigrationEasyWorkBusiness.projectSource(NativeMigrationEasyWorkArchives.projectSource(NativeMigrationForeground.projectSource(original.text)),original.text))
        val clear=records.json(context.packageName).toString().toByteArray(Charsets.UTF_8)
        try {
            require(clear.size<=NativeMigrationCandidateStore.MAX_PAYLOAD_BYTES)
            NativeMigrationCandidateStore(context,storage).validateArchive(clear)
            requireSupportedScopes(original.text)
            return clear
        }catch(error: Exception){clear.fill(0);throw error}
    }
    private fun legacyNames(read: (String)->String?)=NativeMigrationExistingAliases.root(read)

}
