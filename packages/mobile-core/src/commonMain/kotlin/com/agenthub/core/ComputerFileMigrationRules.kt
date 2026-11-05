package com.agenthub.core

/** Typed portable descriptors. Native adapters must strictly decode/authenticate the
 * original journal, bind source frames, and authenticate their bytes before publication.
 * These values contain no OS handles or keys; descriptor validation is not byte validation.
 */
data class ComputerFileMigrationEntry(val name: String,val size: Int,val sha256: String)
data class ComputerFileMigrationRow(val journalName: String,val id: String,val phase: String,
    val sizeBytes: Long,val sha256: String?,val matchingCompletedReceipt: Boolean,
    val chunks: List<ComputerFileMigrationEntry>)

/** Common semantic closure for inert F59 originals. No input phase grants retry,
 * upload, source cleanup, OS permission or active-store restoration.
 * Schema checks alone cannot establish these joins or the original full-file digest.
 */
object ComputerFileMigrationRules {
    private val hash=Regex("[a-f0-9]{64}")
    private val uuid=Regex("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")
    private val journal=Regex("computer-files-v1:[a-f0-9]{64}")
    private val phases=setOf("CAPTURING","CAPTURE_FAILED","READY","UNKNOWN","COMPLETED")
    const val CHUNK_BYTES=1_048_576
    const val MAX_JOURNAL_BYTES=900_000
    const val MAX_JOURNALS=4096
    const val MAX_CHUNK_DESCRIPTORS=65536
    const val MAX_ORIGINAL_CHUNKS=4096
    const val MAX_SAFE_BYTES=9_007_199_254_740_991L

    /** A missing retired chunk is an observation, not proof of any cancellation.
     * The native caller additionally needs the exact durable source journal barrier.
     */
    fun mayObserveMissingOriginal(phase: String,matchingCompletedReceipt: Boolean): Boolean =
        phase=="COMPLETED"&&matchingCompletedReceipt

    /** null means only this typed descriptor graph is structurally closed.
     * Byte authentication, actual source lease/lifetime and publication stay native.
     */
    fun validate(journals: List<ComputerFileMigrationEntry>,rows: List<ComputerFileMigrationRow>,
        present: List<ComputerFileMigrationEntry>,maximumJournals: Int,maximumChunkDescriptors: Int,
        maximumBinaryBytes: Long): String? = runCatching {
        require(maximumJournals in 1..MAX_JOURNALS&&maximumChunkDescriptors in 1..MAX_CHUNK_DESCRIPTORS&&
            maximumBinaryBytes in 1..MAX_SAFE_BYTES) {"invalid-resource-budget"}
        require(journals.size<=maximumJournals&&present.size<=maximumChunkDescriptors&&
            rows.size<=maximumJournals*32) {"descriptor-count-budget"}
        require(journals.map {it.name}==journals.map {it.name}.distinct().sorted()) {"duplicate-or-unordered-journal"}
        require(present.map {it.name}==present.map {it.name}.distinct().sorted()) {"duplicate-or-unordered-chunk"}
        require(journals.all {journal.matches(it.name)&&it.size in 1..MAX_JOURNAL_BYTES&&hash.matches(it.sha256)}) {
            "invalid-journal-descriptor"
        }
        val journalNames=journals.map {it.name}.toSet()
        val ids=mutableSetOf<String>();val referenced=linkedMapOf<String,ComputerFileMigrationEntry>()
        val allowedMissing=mutableSetOf<String>()
        for(row in rows) {
            require(row.journalName in journalNames&&uuid.matches(row.id)&&ids.add(row.id)) {"wrong-journal-or-duplicate-transfer"}
            require(row.phase in phases&&row.sizeBytes in 1..MAX_SAFE_BYTES) {"invalid-original-state"}
            require(row.sha256==null||hash.matches(row.sha256)) {"invalid-original-hash"}
            if(row.phase in setOf("READY","UNKNOWN","COMPLETED"))require(row.sha256!=null) {"sealed-original-hash-required"}
            require((row.phase=="COMPLETED")==row.matchingCompletedReceipt) {"terminal-receipt-required"}
            require(row.chunks.size<=MAX_ORIGINAL_CHUNKS) {"original-chunk-count"}
            require(row.chunks.size<=maximumChunkDescriptors-referenced.size) {"chunk-descriptor-budget"}
            var bytes=0L
            row.chunks.forEachIndexed {index,entry->
                require(entry.name=="computer-file-original:${row.id}:$index"&&entry.size in 1..CHUNK_BYTES&&hash.matches(entry.sha256)) {
                    "invalid-original-chunk"
                }
                require(bytes<=row.sizeBytes-entry.size) {"original-length-overflow"};bytes+=entry.size
                require(referenced.put(entry.name,entry)==null) {"duplicate-original-chunk"}
                if(mayObserveMissingOriginal(row.phase,row.matchingCompletedReceipt))allowedMissing.add(entry.name)
            }
            if(row.sha256!=null)require(bytes==row.sizeBytes) {"sealed-original-length-mismatch"}
        }
        var total=0L
        for(entry in present) {
            require(referenced[entry.name]==entry) {"orphan-or-changed-original"}
            require(total<=maximumBinaryBytes-entry.size) {"original-byte-budget"};total+=entry.size
        }
        require((referenced.keys-present.map {it.name}.toSet()).all {it in allowedMissing}) {"unconfirmed-original-absent"}
    }.fold({null},{it.message?:"invalid-descriptor-graph"})
}
