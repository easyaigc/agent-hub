package com.agenthub.core

/** Inner framing only. These descriptors are inert audit data, never source
 * authentication, publication lineage, active identity or retry permission.
 * The full journal/row/chunk graph still needs ComputerFileMigrationRules and
 * native byte/full-original validation at both semantic boundaries.
 */
enum class ComputerFileInnerKind(val code: Int) { JOURNAL(1), CHUNK(2) }
data class ComputerFileInnerDescriptor(val generation: String?,val kind: ComputerFileInnerKind,
    val entry: ComputerFileMigrationEntry)
data class ComputerFileInnerBudget(val maximumRecords: Int,val maximumPayloadBytes: Long,
    val maximumManifestBytes: Long,val maximumFileBytes: Long)
data class ComputerFileInnerMetrics(val recordCount: Int,val payloadBytes: Long,
    val manifestBytes: Long,val fileBytes: Long)

/** Independent v1 inner payload, not v4, a password envelope or a Host wire.
 * Individual F59 entries retain their existing journal/chunk size limits.
 * The aggregate and encoded lengths are Long, with explicit caller budgets;
 * no replacement fixed 192 MiB/Int total is introduced.
 */
object ComputerFileInnerPayloadRules {
    const val DOMAIN="agent-hub/f59-inner-payload/v1"
    const val VERSION=1
    const val MAX_NAME_BYTES=128
    const val FOOTER_BYTES=84L
    const val BODY_PREFIX_BYTES=16L
    const val HEADER_BYTES=56L // fixed30-byte ASCII domain; no optional fields
    private val hash=Regex("[a-f0-9]{64}")
    private val uuid=Regex("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")
    private val journal=Regex("computer-files-v1:[a-f0-9]{64}")

    fun validateBudget(value: ComputerFileInnerBudget) {
        require(value.maximumRecords in 1..ComputerFileMigrationRules.MAX_JOURNALS+ComputerFileMigrationRules.MAX_CHUNK_DESCRIPTORS) {"inner-record-budget"}
        require(value.maximumPayloadBytes in 1..ComputerFileMigrationRules.MAX_SAFE_BYTES&&
            value.maximumManifestBytes in 1..ComputerFileMigrationRules.MAX_SAFE_BYTES&&
            value.maximumFileBytes in 1..ComputerFileMigrationRules.MAX_SAFE_BYTES) {"inner-byte-budget"}
    }
    fun validateDescriptor(value: ComputerFileInnerDescriptor) {
        require(value.generation==null||uuid.matches(value.generation)) {"inner-generation"}
        val entry=value.entry
        require(entry.name.length in 1..MAX_NAME_BYTES&&entry.name.all {it.code in 1..127}&&hash.matches(entry.sha256)) {"inner-entry"}
        when(value.kind) {
            ComputerFileInnerKind.JOURNAL->require(journal.matches(entry.name)&&entry.size in 1..ComputerFileMigrationRules.MAX_JOURNAL_BYTES) {"inner-journal"}
            ComputerFileInnerKind.CHUNK->{
                val parts=entry.name.split(':');require(parts.size==3&&parts[0]=="computer-file-original"&&uuid.matches(parts[1])) {"inner-chunk-name"}
                val index=parts[2].toIntOrNull()
                require(index!=null&&index in 0 until ComputerFileMigrationRules.MAX_ORIGINAL_CHUNKS&&index.toString()==parts[2]&&
                    entry.size in 1..ComputerFileMigrationRules.CHUNK_BYTES) {"inner-chunk"}
            }
        }
    }
    fun compare(first: ComputerFileInnerDescriptor,second: ComputerFileInnerDescriptor): Int {
        val generation=(first.generation?:"").compareTo(second.generation?:"")
        if(generation!=0)return generation
        val kind=first.kind.code.compareTo(second.kind.code)
        return if(kind!=0)kind else first.entry.name.compareTo(second.entry.name)
    }
    fun add(value: Long,increment: Long,maximum: Long): Long {
        require(value>=0&&increment>=0&&maximum in 1..ComputerFileMigrationRules.MAX_SAFE_BYTES&&value<=maximum-increment) {"inner-length-or-budget"}
        return value+increment
    }
    fun metrics(entries: List<ComputerFileInnerDescriptor>,budget: ComputerFileInnerBudget): ComputerFileInnerMetrics {
        validateBudget(budget)
        require(entries.size in 1..budget.maximumRecords) {"inner-record-count"}
        var payload=0L;var metadata=HEADER_BYTES;var journals=0;var chunks=0
        for((index,value)in entries.withIndex()) {
            validateDescriptor(value)
            if(index>0)require(compare(entries[index-1],value)<0) {"inner-duplicate-or-order"}
            if(value.kind==ComputerFileInnerKind.JOURNAL)journals++ else chunks++
            require(journals<=ComputerFileMigrationRules.MAX_JOURNALS&&chunks<=ComputerFileMigrationRules.MAX_CHUNK_DESCRIPTORS) {"inner-domain-record-count"}
            payload=add(payload,value.entry.size.toLong(),budget.maximumPayloadBytes)
            metadata=add(metadata,1L+(if(value.generation==null)0L else 16L)+1L+2L+value.entry.name.length+8L+32L,budget.maximumManifestBytes)
        }
        val prefixes=entries.size.toLong()*BODY_PREFIX_BYTES
        val file=add(add(add(metadata,prefixes,budget.maximumFileBytes),payload,budget.maximumFileBytes),FOOTER_BYTES,budget.maximumFileBytes)
        return ComputerFileInnerMetrics(entries.size,payload,metadata,file)
    }
}
