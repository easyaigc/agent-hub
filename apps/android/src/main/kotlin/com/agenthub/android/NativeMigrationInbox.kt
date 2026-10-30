package com.agenthub.android

import java.io.OutputStream

/** Existing v1 bytes and identity domain remain unchanged through the common inert engine. */
internal object NativeMigrationInbox {
    const val RECORD="migration-external-inbox-v1"
    const val TEXT="migration-external-inbox-text-v1:"
    const val BINARY="migration-external-inbox-binary-v1:"
    private val archive=NativeMigrationInertArchive(RECORD,TEXT,BINARY,"agent-hub/inert-external-inbox/v1",NativeMigrationInboxGraph::validate)
    fun sha(bytes: ByteArray)=NativeMigrationInertArchive.sha(bytes)
    fun legacyNames(raw: String)=archive.legacyNames(raw)
    fun validate(records: Map<String,String>,binary: Map<String,NativeMigrationBlob>)=archive.validate(records,binary)
    fun append(source: NativeMigrationStreamRecords,child: NativeMigrationStreamRecords?)=archive.append(source,child)
    fun merge(source: NativeMigrationStreamRecords,destination: NativeMigrationStreamRecords)=archive.merge(source,destination)
    fun descriptors(binary: Map<String,String>)=archive.descriptors(binary)
    fun read(storage: NativeSessionStorage)=archive.read(storage)
    fun text(storage: NativeSessionStorage,original: NativeMigrationInertArchive.Original,index: Int)=archive.text(storage,original,index)
    fun copy(storage: NativeSessionStorage,original: NativeMigrationInertArchive.Original,index: Int,output: OutputStream)=archive.copy(storage,original,index,output)
}
