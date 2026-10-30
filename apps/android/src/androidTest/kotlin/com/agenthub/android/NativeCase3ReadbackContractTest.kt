package com.agenthub.android

import org.junit.Assert.*
import org.junit.Test

class NativeCase3ReadbackContractTest {
    private val p=NativeCase3ReadbackContract
    private val receipt="a".repeat(64)
    private fun ack()=listOf("schema=1","profile="+p.PROFILE,"readReceiptSha256="+receipt,"inventorySha256="+p.inventorySha,
        "sourceArchiveSha256="+p.ARCHIVE_SHA,"sourceIndexSha256="+p.INDEX_SHA,"originalProofSha256="+p.ORIGINAL_SHA,
        "wavSha256="+p.WAV_SHA,"wavBytes=3244","rootCaptureSha256="+"b".repeat(64),"rootFullWavCaptured=true",
        "rootAllOriginalStringsCompared=true","rootSourceCipherArchiveCaptured=true","rootIndependentDecryption=false",
        "rootWorkerExitConfirmed=true","rootAuthorizesExactFixtureAndKeyCleanup=true").joinToString("\n",postfix="\n")
    private fun rejects(value: String,expected: String=receipt) {
        try {NativeCase3ReadbackContract.CleanupAck.parse(value.toByteArray(),expected);fail("ACK accepted")}
        catch(expectedFailure: IllegalArgumentException) { /* exact refusal */ }
    }
    @Test fun acceptsOnlyBoundCompleteParentCapture() {
        val result=NativeCase3ReadbackContract.CleanupAck.parse(ack().toByteArray(),receipt)
        assertEquals(receipt,result.receiptSha);assertEquals("b".repeat(64),result.rootCaptureSha)
    }
    @Test fun refusesMissingParentCaptureExitOrCleanupAuthority() {
        for(field in listOf("rootFullWavCaptured","rootAllOriginalStringsCompared","rootSourceCipherArchiveCaptured",
            "rootWorkerExitConfirmed","rootAuthorizesExactFixtureAndKeyCleanup"))rejects(ack().replace(field+"=true",field+"=false"))
        rejects(ack().replace("rootIndependentDecryption=false","rootIndependentDecryption=true"))
    }
    @Test fun refusesAnotherReceiptProfileAudioOrInventory() {
        rejects(ack(),"c".repeat(64))
        for(value in listOf(p.PROFILE,p.inventorySha,p.ARCHIVE_SHA,p.INDEX_SHA,p.ORIGINAL_SHA,p.WAV_SHA,"wavBytes=3244"))
            rejects(ack().replace(value,value+"0"))
    }
    @Test fun refusesDuplicateUnknownOrderAndNoncanonicalEncoding() {
        rejects(ack()+"schema=1\n");rejects(ack()+"alias=other\n");rejects(ack().trimEnd())
        rejects(ack().replace("\n","\r\n"));rejects(ack().replace("schema=1\nprofile="+p.PROFILE,"profile="+p.PROFILE+"\nschema=1"))
        rejects(ack().replace("rootCaptureSha256="+"b".repeat(64),"rootCaptureSha256="+"B".repeat(64)))
    }
    @Test fun fixedInventoryHasOneFixtureAndNoRawWavOrAliasInput() {
        assertEquals(15,p.entries.size);assertEquals(16641L,p.entries.filter {!it.directory}.sumOf {it.bytes})
        assertEquals(p.entries.size,p.entries.map {it.path}.toSet().size)
        assertFalse(p.entries.any {it.path.endsWith(".wav")})
        assertTrue(p.entries.filter {!it.directory}.all {it.links==1&&p.hash(it.sha)})
    }
}
