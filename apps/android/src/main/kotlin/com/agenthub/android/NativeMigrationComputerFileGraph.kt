package com.agenthub.android

import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStream
import java.security.MessageDigest
import com.agenthub.core.ComputerFileMigrationEntry
import com.agenthub.core.ComputerFileMigrationRow
import com.agenthub.core.ComputerFileMigrationRules

/** Preparation for the F59 portable domain, deliberately disconnected from activation/export.
 * Inputs must be exhaustively authenticated and retained under the actual source writer freeze.
 * No device key/ciphertext/URI is accepted; no result here confers upload, retry or cleanup authority.
 * A future streaming container must admit these limits explicitly, rather than widening v4 in place.
 */
internal object NativeMigrationComputerFileGraph {
    data class Limits(val maximumJournals: Int,val maximumChunkDescriptors: Int,val maximumBinaryBytes: Long) {
        init {
            require(maximumJournals in 1..ComputerFileMigrationRules.MAX_JOURNALS&&
                maximumChunkDescriptors in 1..ComputerFileMigrationRules.MAX_CHUNK_DESCRIPTORS&&
                maximumBinaryBytes in 1..ComputerFileMigrationRules.MAX_SAFE_BYTES)
        }
    }
    data class Entry(val name: String,val size: Int,val sha256: String) {
        fun json()=JSONObject().put("name",name).put("size",size).put("sha256",sha256)
    }
    /** Descriptors only. Exact clear journal and chunk frames stay in the encrypted container.
     * An absent chunk is structurally legal only for an exact COMPLETED source row.
     * The independent source reader must still establish the actual journal barrier.
     */
    data class RetiredChunk(val journalName: String,val originalId: String,val index: Int,val part: ComputerFileMigrationEntry)
    data class Graph(val journals: List<Entry>,val chunks: List<Entry>,val retiredChunks: List<RetiredChunk>) {
        val absentCompletedChunks: List<String> get()=retiredChunks.map {it.part.name}
        fun json()=JSONObject().put("schema","agent-hub/computer-file-originals/v1")
            .put("sourcePlatform","android").put("destinationPolicy","inert")
            .put("journalFormat","agent-hub/android-computer-files-v1")
            .put("journals",JSONArray(journals.map {it.json()})).put("chunks",JSONArray(chunks.map {it.json()}))
            .put("absentCompletedChunks",JSONArray(absentCompletedChunks))
    }
    private fun digest(hash: MessageDigest)=hash.digest().joinToString("") {"%02x".format(it)}
    fun validate(journals: Map<String,String>,chunks: Map<String,NativeMigrationBlob>,limits: Limits): Graph {
        require(journals.size<=limits.maximumJournals&&chunks.size<=limits.maximumChunkDescriptors)
        val nativeRows=mutableListOf<ComputerFileMigrationRow>();var descriptors=0
        val journalEntries=mutableListOf<Entry>();val chunkEntries=mutableListOf<Entry>();var actualBytes=0L
        // Lazy source maps stay lazy: retain only bounded typed descriptors,
        // never every decoded journal/error/binding/raw String at the same time.
        for(name in journals.keys.sorted()) {
            val raw=journals.getValue(name)
            val decoded=NativeComputerFileJournal.decode(name,raw,null)
            for(row in decoded.files) {
                require(row.chunks.size<=limits.maximumChunkDescriptors-descriptors)
                descriptors+=row.chunks.size
                nativeRows.add(ComputerFileMigrationRow(name,row.id,row.phase.name,row.sizeBytes,row.sha256,
                    row.phase==ComputerFilePhase.COMPLETED&&row.receipt?.phase=="completed",
                    row.chunks.mapIndexed {index,part->ComputerFileMigrationEntry("computer-file-original:${row.id}:$index",part.bytes,part.sha256)}))
            }
            val bytes=raw.toByteArray(Charsets.UTF_8)
            try {journalEntries.add(Entry(name,bytes.size,ComputerFileCodec.hash(bytes)))}finally {bytes.fill(0)}
        }
        val sharedError=ComputerFileMigrationRules.validate(
            journalEntries.map {ComputerFileMigrationEntry(it.name,it.size,it.sha256)},nativeRows,
            chunks.toSortedMap().map {(name,blob)->ComputerFileMigrationEntry(name,blob.size,blob.sha256)},
            limits.maximumJournals,limits.maximumChunkDescriptors,limits.maximumBinaryBytes)
        require(sharedError==null) {"F59 portable graph: $sharedError"}
        val expected=linkedSetOf<String>();val missing=linkedMapOf<String,RetiredChunk>()
        for(row in nativeRows) {
                val fullHash=MessageDigest.getInstance("SHA-256");var copied=0L;var complete=true
                row.chunks.forEachIndexed {index,part->
                    val logical="computer-file-original:${row.id}:$index"
                    require(expected.add(logical)&&expected.size<=limits.maximumChunkDescriptors)
                    val blob=chunks[logical]
                    if(blob==null) {
                        require(ComputerFileMigrationRules.mayObserveMissingOriginal(row.phase,row.matchingCompletedReceipt)) {
                            "Unconfirmed F59 original is absent"
                        }
                        missing[logical]=RetiredChunk(row.journalName,row.id,index,part);complete=false
                    }else {
                        require(blob.size==part.size&&blob.sha256==part.sha256&&blob.prefix.size==minOf(44,part.size))
                        require(actualBytes<=limits.maximumBinaryBytes-part.size)
                        actualBytes+=part.size
                        // Bound memory per chunk; never allocate the complete file or Base64 it.
                        blob.verifiedCopy(object: OutputStream() {
                            override fun write(value: Int) {fullHash.update(value.toByte());copied++}
                            override fun write(value: ByteArray,offset: Int,count: Int) {
                                fullHash.update(value,offset,count);copied+=count
                            }
                        })
                        chunkEntries.add(Entry(logical,blob.size,blob.sha256))
                    }
                }
                if(complete&&row.sha256!=null)require(copied==row.sizeBytes&&digest(fullHash)==row.sha256)
        }
        require(chunks.keys==expected-missing.keys) {"Orphan or unidentified F59 original"}
        return Graph(journalEntries,chunkEntries.sortedBy {it.name},missing.values.sortedBy {it.part.name})
    }
}
