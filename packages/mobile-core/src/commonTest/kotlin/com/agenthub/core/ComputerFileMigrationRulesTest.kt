package com.agenthub.core

import kotlin.test.*

/** Shared semantic checks; no byte/OS/native acceptance claim. */
class ComputerFileMigrationRulesTest {
    private val journal=ComputerFileMigrationEntry("computer-files-v1:"+"a".repeat(64),256,"b".repeat(64))
    private val id="00000000-0000-4000-8000-000000000001"
    private val chunk=ComputerFileMigrationEntry("computer-file-original:$id:0",64,"c".repeat(64))
    private fun row(phase: String="UNKNOWN",completed: Boolean=false)=ComputerFileMigrationRow(
        journal.name,id,phase,64,"d".repeat(64),completed,listOf(chunk))
    private fun result(rows: List<ComputerFileMigrationRow>,present: List<ComputerFileMigrationEntry>,
        journals: List<ComputerFileMigrationEntry> = listOf(journal),budget: Long=1024)=
        ComputerFileMigrationRules.validate(journals,rows,present,2,4096,budget)

    @Test fun missingUnknownOriginalCannotBecomeACompleteMigratedGraph() {
        assertNull(result(listOf(row()),listOf(chunk)))
        assertEquals("unconfirmed-original-absent",result(listOf(row()),emptyList()))
        assertEquals("unconfirmed-original-absent",result(listOf(row("READY")),emptyList()))
        assertNull(result(listOf(row("COMPLETED",true)),emptyList()))
        assertEquals("terminal-receipt-required",result(listOf(row("COMPLETED")),emptyList()))
        assertEquals("terminal-receipt-required",result(listOf(row("UNKNOWN",true)),emptyList()))
    }

    @Test fun partialCaptureStaysPartialAndEverySurvivingChunkIsRequired() {
        val partial=row("CAPTURE_FAILED").copy(sizeBytes=128,sha256=null)
        assertNull(result(listOf(partial),listOf(chunk)))
        assertEquals("unconfirmed-original-absent",result(listOf(partial),emptyList()))
        assertEquals("sealed-original-length-mismatch",result(listOf(partial.copy(sha256="d".repeat(64))),listOf(chunk)))
    }

    @Test fun sameTransferCannotAliasAnotherTargetJournalOrDifferentPayload() {
        val other=journal.copy(name="computer-files-v1:"+"e".repeat(64))
        val journals=listOf(journal,other).sortedBy {it.name}
        assertEquals("wrong-journal-or-duplicate-transfer",result(listOf(row(),row().copy(journalName=other.name)),listOf(chunk),journals))
        assertEquals("orphan-or-changed-original",result(listOf(row()),listOf(chunk.copy(sha256="f".repeat(64)))))
        assertEquals("wrong-journal-or-duplicate-transfer",result(listOf(row().copy(journalName=other.name)),listOf(chunk)))
    }

    @Test fun extraUnindexedBytesAndDuplicateDescriptorsCannotBeSilentlyDropped() {
        val orphan=chunk.copy(name="computer-file-original:00000000-0000-4000-8000-000000000002:0")
        assertEquals("orphan-or-changed-original",result(listOf(row()),listOf(chunk,orphan).sortedBy {it.name}))
        assertEquals("duplicate-or-unordered-chunk",result(listOf(row()),listOf(chunk,chunk)))
        assertEquals("duplicate-or-unordered-journal",result(listOf(row()),listOf(chunk),listOf(journal,journal)))
    }

    @Test fun aggregateBudgetUsesLongAndRejectsTheNextByteAcrossIntRange() {
        // Descriptor-only 3GiB; no synthetic multi-gigabyte input is allocated.
        val pieces=(0 until 3072).map {index->chunk.copy(name="computer-file-original:$id:$index",size=1_048_576)}
        val bytes=3L*1024*1024*1024
        val original=row().copy(sizeBytes=bytes,chunks=pieces)
        val present=pieces.sortedBy {it.name}
        assertNull(result(listOf(original),present,budget=bytes))
        assertEquals("original-byte-budget",result(listOf(original),present,budget=bytes-1))
        assertEquals("original-byte-budget",result(listOf(original),present,budget=Int.MAX_VALUE.toLong()))
    }

    @Test fun originalChunkCountAndCanonicalIndicesHaveTheSameFiniteBoundary() {
        val pieces=(0 until 4096).map {index->chunk.copy(name="computer-file-original:$id:$index",size=1)}
        val original=row().copy(sizeBytes=4096,chunks=pieces)
        assertNull(ComputerFileMigrationRules.validate(listOf(journal),listOf(original),pieces.sortedBy {it.name},2,65536,8192))
        val tooMany=pieces+chunk.copy(name="computer-file-original:$id:4096",size=1)
        assertEquals("original-chunk-count",ComputerFileMigrationRules.validate(listOf(journal),
            listOf(original.copy(sizeBytes=4097,chunks=tooMany)),tooMany.sortedBy {it.name},2,65536,8192))
        for(index in listOf("00","01","4096","10000")) {
            val changed=chunk.copy(name="computer-file-original:$id:$index")
            assertEquals("invalid-original-chunk",result(listOf(row().copy(chunks=listOf(changed))),listOf(changed)))
        }
    }
}
