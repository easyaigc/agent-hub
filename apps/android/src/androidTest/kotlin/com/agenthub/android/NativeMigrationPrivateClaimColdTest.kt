package com.agenthub.android

import android.os.Process
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

/** Ordered independent instrumentation processes under one parent-supplied run. No UI/Host action. */
class NativeMigrationPrivateClaimColdTest {
    @get:org.junit.Rule val owner: org.junit.rules.TestRule=NativePrivateClaimFixtureOwnerRule()
    @Test fun stageFreshClaimCandidateForIndependentReader()=runBlocking {
        val f=NativeMigrationPrivateClaimFixtures();f.fresh();val source=f.seedSource()
        val raw=f.intent();val name=f.seedClaim(source,raw);val local=f.intent("local-original-claim-host",null)
        val envelope=f.export(source);val destination=f.storage("destination");val localName=f.seedClaim(destination,local)
        val receipt=NativeMigrationCandidateStore(f.context("destination"),destination).stageFile(UUID.randomUUID().toString(),envelope,f.password)
        assertEquals(f.exportCiphertext,f.tree(source))
        val candidate=destination.child(f.context("destination"),"migration-candidates")
        f.publish(f.coldHeader(receipt).put("originalClaims",JSONObject(mapOf(name to raw,localName to local)))
            .put("sourceCiphertext",JSONObject(f.tree(source))).put("destinationCiphertext",JSONObject(f.tree(destination)))
            .put("candidateCiphertext",JSONObject(f.tree(candidate))).put("remoteCalls",f.remoteCalls.get()))
        check(envelope.delete())
    }
    @Test fun readSameClaimCandidateAndActivateWithoutPairingUnderDifferentPid()=runBlocking {
        val f=NativeMigrationPrivateClaimFixtures();val proof=f.proof();assertNotEquals(proof.getInt("writerPid"),Process.myPid())
        require(proof.keys().asSequence().toSet()==setOf("version","kind","run","writerPid","candidateId","candidateSha256","recordCount","originalClaims","sourceCiphertext","destinationCiphertext","candidateCiphertext","remoteCalls"))
        val source=f.storage("source");val destination=f.storage("destination");val staging=destination.child(f.context("destination"),"migration-candidates")
        assertEquals(f.hashes(proof.getJSONObject("sourceCiphertext")),f.tree(source));assertEquals(f.hashes(proof.getJSONObject("destinationCiphertext")),f.tree(destination))
        val originalCandidate=f.tree(staging);assertEquals(f.hashes(proof.getJSONObject("candidateCiphertext")),originalCandidate)
        val receipt=f.receipt(proof);assertEquals(receipt,NativeMigrationCandidateStore(f.context("destination"),destination).currentReceipt())
        val generation=f.owned(destination){lease->NativeMigrationActivation(f.context("destination"),destination,lease.notification(receipt),lease).activate(receipt)}
        val current=destination.reopen(f.context("destination"));f.inspect(current,f.hashes(proof.getJSONObject("originalClaims")))
        current.write("later-private-claim-draft","cold independent draft")
        assertEquals(originalCandidate,f.tree(staging));assertEquals(f.hashes(proof.getJSONObject("sourceCiphertext")),f.tree(source))
        val after=f.tree(current);assertEquals(generation,NativeMigrationActivation(f.context("destination"),current).activate(receipt));assertEquals(after,f.tree(current))
        val result=File(f.folder,"activated-proof.json");check(result.createNewFile())
        java.io.FileOutputStream(result).use {out->
            out.write(JSONObject().put("version",1).put("kind","private-claims-activated-v1").put("run",f.run).put("readerPid",Process.myPid())
                .put("generation",generation).put("ciphertext",JSONObject(after)).put("remoteCalls",f.remoteCalls.get()).toString().toByteArray(Charsets.UTF_8));out.fd.sync()
        }
    }
    @Test fun readActivatedClaimAuditAgainWithoutRestagingOrReplaying() {
        val f=NativeMigrationPrivateClaimFixtures();val original=f.proof();val result=File(f.folder,"activated-proof.json")
        require(result.isFile&&!java.nio.file.Files.isSymbolicLink(result.toPath())&&result.length() in 1..128*1024)
        val activated=StrictMigrationJson.parse(result.readBytes())
        require(activated.keys().asSequence().toSet()==setOf("version","kind","run","readerPid","generation","ciphertext","remoteCalls"))
        require(activated.get("version")==1&&activated.get("kind")=="private-claims-activated-v1"&&activated.get("run")==f.run)
        assertNotEquals(activated.getInt("readerPid"),Process.myPid());assertNotEquals(original.getInt("writerPid"),Process.myPid())
        val root=f.storage("destination");val before=f.tree(root);assertEquals(f.hashes(activated.getJSONObject("ciphertext")),before)
        val receipt=f.receipt(original);assertEquals(receipt,NativeMigrationCandidateStore(f.context("destination"),root).currentReceipt())
        f.inspect(root,f.hashes(original.getJSONObject("originalClaims")));assertEquals("cold independent draft",root.read("later-private-claim-draft"))
        assertEquals(activated.getString("generation"),NativeMigrationActivation(f.context("destination"),root).activate(receipt));assertEquals(before,f.tree(root))
        assertEquals(f.hashes(original.getJSONObject("candidateCiphertext")),f.tree(root.child(f.context("destination"),"migration-candidates")))
        assertEquals(0,f.remoteCalls.get())
    }
}
