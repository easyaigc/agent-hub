package com.agenthub.core

import kotlin.test.*

/** UNEXECUTED candidate; descriptor arithmetic, never large streamed data,
 * authenticated-encryption, native bytes or full migration acceptance.
 */
class ComputerFileInnerPayloadRulesTest {
    private val id="00000000-0000-4000-8000-000000000001"
    private val hash="a".repeat(64)
    private val journal=ComputerFileInnerDescriptor(null,ComputerFileInnerKind.JOURNAL,
        ComputerFileMigrationEntry("computer-files-v1:"+"0".repeat(64),2,hash))
    private fun chunk(index: Int,generation: String?=null)=ComputerFileInnerDescriptor(generation,ComputerFileInnerKind.CHUNK,
        ComputerFileMigrationEntry("computer-file-original:$id:$index",ComputerFileMigrationRules.CHUNK_BYTES,hash))
    private fun budget(payload: Long=4L*1024*1024*1024)=ComputerFileInnerBudget(4096,payload,1024L*1024,5L*1024*1024*1024)

    @Test fun aggregateOverIntDoesNotAllocateThePayloadOrBecomeAnIntTotal() {
        val chunks=(0..2048).map {chunk(it)}.sortedWith {a,b->ComputerFileInnerPayloadRules.compare(a,b)}
        val metrics=ComputerFileInnerPayloadRules.metrics(listOf(journal)+chunks,budget())
        assertEquals(2_148_532_226L,metrics.payloadBytes)
        assertTrue(metrics.payloadBytes>Int.MAX_VALUE.toLong())
        assertTrue(metrics.fileBytes>metrics.payloadBytes)
        assertFailsWith<IllegalArgumentException> {
            ComputerFileInnerPayloadRules.metrics(listOf(journal)+chunks,budget(Int.MAX_VALUE.toLong()))
        }
    }
    @Test fun sameNameIsSeparateOnlyAcrossExplicitGenerationsAndOrderIsCanonical() {
        val second=journal.copy(generation=id)
        assertEquals(2,ComputerFileInnerPayloadRules.metrics(listOf(journal,second),budget()).recordCount)
        assertFailsWith<IllegalArgumentException> {ComputerFileInnerPayloadRules.metrics(listOf(journal,journal),budget())}
        assertFailsWith<IllegalArgumentException> {ComputerFileInnerPayloadRules.metrics(listOf(second,journal),budget())}
    }
    @Test fun transportDescriptorRulesDoNotAuthorizeAnOrphanOrAnUnknownRetry() {
        val descriptor=chunk(0)
        ComputerFileInnerPayloadRules.metrics(listOf(descriptor),budget())
        // Descriptor framing is deliberately weaker than the semantic source
        // graph. Actual byte/archive callers must still run that independent gate.
        assertEquals("orphan-or-changed-original",ComputerFileMigrationRules.validate(emptyList(),emptyList(),
            listOf(descriptor.entry),1,1,ComputerFileMigrationRules.CHUNK_BYTES.toLong()))
    }
    @Test fun pathNamesNoncanonicalIndicesAndInflatedPerChunkSizesRemainRejected() {
        for(value in listOf(chunk(0).copy(entry=chunk(0).entry.copy(name="file:///synthetic/source")),
            chunk(0).copy(entry=chunk(0).entry.copy(name="computer-file-original:$id:00")),
            chunk(0).copy(entry=chunk(0).entry.copy(size=ComputerFileMigrationRules.CHUNK_BYTES+1)))) {
            assertFailsWith<IllegalArgumentException> {ComputerFileInnerPayloadRules.metrics(listOf(value),budget())}
        }
    }
    @Test fun safeIntegerLimitAndSignedOverflowCannotBeTurnedIntoAValidBudget() {
        val maximum=ComputerFileMigrationRules.MAX_SAFE_BYTES
        assertEquals(maximum,ComputerFileInnerPayloadRules.add(maximum-1,1,maximum))
        assertFailsWith<IllegalArgumentException> {ComputerFileInnerPayloadRules.add(maximum,1,maximum)}
        assertFailsWith<IllegalArgumentException> {ComputerFileInnerPayloadRules.add(1,Long.MAX_VALUE,maximum)}
        assertFailsWith<IllegalArgumentException> {ComputerFileInnerPayloadRules.metrics(listOf(journal),budget().copy(maximumFileBytes=Long.MAX_VALUE))}
    }
}
