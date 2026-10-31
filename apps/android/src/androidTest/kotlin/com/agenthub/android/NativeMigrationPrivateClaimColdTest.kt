package com.agenthub.android

import android.os.Process
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

/** Ordered actual Native PIDs. New v2 proof refuses old/partial runs; no SDK/driver is implied. */
class NativeMigrationPrivateClaimColdTest {
    @get:org.junit.Rule val owner: org.junit.rules.TestRule=NativePrivateClaimFixtureOwnerRule()
    @Test fun stageFreshClaimCandidateForIndependentReader()=runBlocking {
        val c=NativeMigrationPrivateClaimColdFixtures();val f=c.f;c.fresh();val source=f.seedSource()
        val raw=f.intent();val name=f.seedClaim(source,raw);val local=f.intent("local-original-claim-host",null)
        val envelope=f.export(source);val destination=f.storage("destination");val localName=f.seedClaim(destination,local)
        val receipt=NativeMigrationCandidateStore(f.context("destination"),destination).stageFile(UUID.randomUUID().toString(),envelope,f.password)
        c.privateFreshNamespaces();c.rejectMissingMaterial();c.rejectMissingBaseWithoutGetter();c.rejectSidecarWithoutRepair()
        c.view("source").using {src->c.view("destination").using {dst->
            dst.originalCandidate(dst.generation,receipt)
            c.publish(f.proofFile(),f.coldHeader(receipt).put("version",2).put("kind","private-claims-cold-v2")
                .put("candidateOriginGeneration",dst.generation?:JSONObject.NULL)
                .put("originalClaims",JSONObject(mapOf(name to raw,localName to local)))
                .put("sourceCiphertext",JSONObject(src.familyCiphertext())).put("destinationCiphertext",JSONObject(dst.familyCiphertext()))
                .put("sourceNamespaces",org.json.JSONArray(src.namespaces())).put("destinationNamespaces",org.json.JSONArray(dst.namespaces()))
                .put("candidateCiphertext",JSONObject(dst.originalCandidateCiphertext(dst.generation)))
                .put("operations",JSONObject(c.operations(src.records))).put("remoteCalls",f.remoteCalls.get()))
        }}
        check(envelope.delete());destination.retireWriter()
    }
    @Test fun readSameClaimCandidateAndActivateWithoutPairingUnderDifferentPid()=runBlocking {
        val c=NativeMigrationPrivateClaimColdFixtures();val f=c.f;val proof=c.proof()
        assertNotEquals(proof.getInt("writerPid"),Process.myPid())
        c.rejectInvalidOwnerAndAtomicFault(proof)
        val receipt=f.receipt(proof)
        val generation=c.activateAndRestore(proof)
        c.assertDestinationOriginals(proof)
        c.view("source").using {src->c.view("destination").using {dst->
            assertEquals(f.hashes(proof.getJSONObject("sourceCiphertext")),src.familyCiphertext())
            assertEquals(f.hashes(proof.getJSONObject("candidateCiphertext")),dst.originalCandidateCiphertext(c.origin(proof)))
            val names=dst.namespaces()
            assertTrue(runCatching {dst.originalCandidate(generation,receipt)}.isFailure)
            assertEquals(names,dst.namespaces())
            dst.originalCandidate(c.origin(proof),receipt);assertEquals(generation,dst.published(receipt));c.inspect(dst,proof)
            assertEquals("cold independent draft",dst.records.text["later-private-claim-draft"])
            c.publish(File(f.folder,"activated-proof.json"),JSONObject().put("version",2).put("kind","private-claims-activated-v2").put("run",f.run)
                .put("readerPid",Process.myPid()).put("generation",generation).put("ciphertext",JSONObject(dst.familyCiphertext())).put("namespaces",org.json.JSONArray(dst.namespaces())).put("remoteCalls",f.remoteCalls.get()))
        }}
    }
    @Test fun readActivatedClaimAuditAgainWithoutRestagingOrReplaying() {
        val c=NativeMigrationPrivateClaimColdFixtures();val f=c.f;val original=c.proof();val activated=c.read(File(f.folder,"activated-proof.json"))
        require(activated.keys().asSequence().toSet()==setOf("version","kind","run","readerPid","generation","ciphertext","namespaces","remoteCalls"))
        require(activated.get("version")==2&&activated.get("kind")=="private-claims-activated-v2"&&activated.get("run")==f.run&&activated.get("remoteCalls")==0)
        assertNotEquals(activated.getInt("readerPid"),Process.myPid());assertNotEquals(original.getInt("writerPid"),Process.myPid())
        c.view("source").using {src->c.view("destination").using {dst->
            c.assertDestinationOriginals(original)
            val before=dst.familyCiphertext()
            assertEquals(f.hashes(activated.getJSONObject("ciphertext")),before)
            assertEquals(activated.getJSONArray("namespaces").strings(),dst.namespaces())
            assertEquals(original.getJSONArray("sourceNamespaces").strings(),src.namespaces())
            assertEquals(f.hashes(original.getJSONObject("sourceCiphertext")),src.familyCiphertext())
            assertEquals(f.hashes(original.getJSONObject("candidateCiphertext")),dst.originalCandidateCiphertext(c.origin(original)))
            dst.originalCandidate(c.origin(original),f.receipt(original));c.inspect(dst,original)
            assertEquals("cold independent draft",dst.records.text["later-private-claim-draft"])
            assertEquals(activated.getString("generation"),dst.published(f.receipt(original)))
            assertEquals(before,dst.familyCiphertext());assertEquals(0,f.remoteCalls.get())
            c.publish(c.thirdProof(),JSONObject().put("version",2).put("run",f.run).put("writerPid",original.getInt("writerPid"))
                .put("readerPid",activated.getInt("readerPid")).put("thirdPid",Process.myPid()).put("originalReceipt",true).put("ciphertextsUnchanged",true))
        }}
    }
}
