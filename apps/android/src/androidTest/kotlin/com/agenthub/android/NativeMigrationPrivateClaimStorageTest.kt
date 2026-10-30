package com.agenthub.android

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

/** Select one method per fresh parent-supplied privateClaimMigrationRun. Unexecuted source fixture. */
class NativeMigrationPrivateClaimStorageTest {
    @get:org.junit.Rule val owner: org.junit.rules.TestRule=NativePrivateClaimFixtureOwnerRule()
    @Test fun streamRoundTripKeepsSourceDestinationAndUnknownOperationsReadOnly()=runBlocking {
        NativeMigrationClientGraphAssertions.verify()
        val f=NativeMigrationPrivateClaimFixtures();f.fresh();val source=f.seedSource()
        val sourceRaw=f.intent();val sourceName=f.seedClaim(source,sourceRaw);val operations=f.operations(source)
        val envelope=f.export(source);assertEquals(f.exportCiphertext,f.tree(source))
        val destination=f.storage("destination");val local=f.intent("local-original-claim-host",null);val localName=f.seedClaim(destination,local)
        val candidates=NativeMigrationCandidateStore(f.context("destination"),destination)
        val receipt=candidates.stageFile(UUID.randomUUID().toString(),envelope,f.password)
        val staging=destination.child(f.context("destination"),"migration-candidates");val stagedBefore=f.tree(staging)
        val generation=f.owned(destination){lease->
            NativeMigrationActivation(f.context("destination"),destination,lease.notification(receipt),lease).activate(receipt)
        }
        val current=destination.reopen(f.context("destination"));f.inspect(current,mapOf(sourceName to sourceRaw,localName to local))
        val restored=f.operations(current);assertEquals(operations.keys,restored.keys)
        for((name,rows)in operations) {
            val actual=restored.getValue(name);assertEquals(rows.keys().asSequence().toSet(),actual.keys().asSequence().toSet())
            for(id in rows.keys()) {
                val expected=rows.getJSONObject(id);val row=actual.getJSONObject(id)
                for(key in expected.keys())assertEquals("$name/$id/$key",expected.get(key),row.get(key))
            }
        }
        assertEquals(f.exportCiphertext,f.tree(source));assertEquals(stagedBefore,f.tree(staging))
        current.write("later-private-claim-draft","independent later draft");val after=f.tree(current)
        assertEquals(generation,NativeMigrationActivation(f.context("destination"),current).activate(receipt));assertEquals(after,f.tree(current))
        File(f.folder,"warm-result.json").writeText(JSONObject().put("candidateId",receipt.id).put("sourceUnchangedAfterQuiesce",true)
            .put("destinationOriginalInUnion",true).put("immutableUnknownOperationsExact",true).put("incomingActiveClaimKeys",0)
            .put("networkQueriesPostsOrReplay",f.remoteCalls.get()).toString())
    }
    @Test fun ownerlessAndStaleLeaseRefuseWithoutPublishing()=runBlocking {
        val f=NativeMigrationPrivateClaimFixtures();f.fresh();val source=f.seedSource();f.seedClaim(source)
        val output=File(f.folder,"prior.encrypted").apply {writeText("previous published file")};val before=f.tree(source)
        assertTrue(runCatching {NativeMigrationExport(f.context("source"),source).sealFile(output,f.password)}.isFailure)
        assertTrue(runCatching {NativeMigrationExport(f.context("source"),source).capture()}.isFailure)
        assertEquals(before,f.tree(source));assertEquals("previous published file",output.readText())
        f.owned(source){lease->
            val wrong=f.storage("destination")
            assertTrue(runCatching {NativeMigrationPrivateClaimBoundary.requireCurrent(wrong,setOf(NativeMigrationPrivateClaims.RECORD),lease)}.isFailure)
            val sourceBefore=f.tree(source);NativeMigrationExport(f.context("source"),source).sealFile(output,f.password,lease)
            val good=output.readBytes()
            val stale=NativeMigrationOwnerLease(source,lease.operationId,lease.rawWriters){false}
            assertTrue(runCatching {NativeMigrationExport(f.context("source"),source).sealFile(output,f.password,stale)}.isFailure)
            assertArrayEquals(good,output.readBytes());assertEquals(sourceBefore,f.tree(source))
        }
    }
    @Test fun badEncryptedClaimRecordKeepsOriginalAndPublishedFile()=runBlocking {
        val f=NativeMigrationPrivateClaimFixtures();f.fresh();val source=f.seedSource()
        val bad=JSONObject(f.intent()).put("phase","unproven-future-result").toString();val name=f.seedClaim(source,bad)
        val output=File(f.folder,"prior.encrypted").apply {writeText("previous published file")}
        f.owned(source){lease->
            val before=f.tree(source)
            assertTrue(runCatching {NativeMigrationExport(f.context("source"),source).sealFile(output,f.password,lease)}.isFailure)
            assertEquals(bad,source.read(name));assertEquals(before,f.tree(source));assertEquals("previous published file",output.readText())
        }
    }
    @Test fun malformedOrOrphanClaimsRejectAndKeepPriorCandidate()=runBlocking {
        val f=NativeMigrationPrivateClaimFixtures();f.fresh();val source=f.seedSource();val raw=f.intent();f.seedClaim(source,raw)
        val envelope=f.export(source);val destination=f.storage("destination");val candidate=NativeMigrationCandidateStore(f.context("destination"),destination)
        val receipt=candidate.stageFile(UUID.randomUUID().toString(),envelope,f.password);val candidateBefore=f.tree(destination.child(f.context("destination"),"migration-candidates"))
        val portable=NativeMigrationStreamCandidate(f.context("destination"),destination).read(receipt) {archive->
            NativeMigrationStreamRecords(archive.text.toMap(),archive.binary,4)
        }
        // Positive full-graph control prevents an unrelated invalid metadata fixture hiding a gap.
        candidate.validateStreamRecords(portable)
        val emptyTargetBefore=f.tree(destination)
        assertTrue(runCatching {NativeMigrationActivation(f.context("destination"),destination).activate(receipt)}.isFailure)
        assertEquals(emptyTargetBefore,f.tree(destination));assertEquals(candidateBefore,f.tree(destination.child(f.context("destination"),"migration-candidates")))
        val alias=portable.text.keys.single {it.startsWith(NativeMigrationPrivateClaims.TEXT)}
        val malformed=listOf(portable.text-alias,portable.text+mapOf(alias to raw+" "),portable.text+mapOf(NativeMigrationPrivateClaims.TEXT+"a".repeat(64)+":9" to raw))
        for(rows in malformed) {
            val bad=File(f.folder,"bad.encrypted")
            bad.outputStream().use {out->NativeMigrationStreamCrypto.write(out,f.password){NativeMigrationStreamArchive.write(it,portable.copy(text=rows),f.app.packageName)}}
            assertTrue(runCatching {candidate.stageFile(UUID.randomUUID().toString(),bad,f.password,receipt)}.isFailure)
            assertEquals(receipt,candidate.currentReceipt());assertEquals(candidateBefore,f.tree(destination.child(f.context("destination"),"migration-candidates")))
            check(bad.delete())
        }
        assertEquals(raw,source.reopen(f.context("source")).read(f.name(raw)));assertEquals(f.exportCiphertext,f.tree(source))
    }
    @Test fun legacyUnidentifiedClaimAndUnknownRootScopeStayUntouched()=runBlocking {
        val f=NativeMigrationPrivateClaimFixtures();f.fresh();val source=f.seedSource();f.legacyClaim(source,f.intent())
        val output=File(f.folder,"original-publication.encrypted").apply {writeText("previous file")}
        f.owned(source){lease->
            val before=f.tree(source)
            assertTrue(runCatching {NativeMigrationExport(f.context("source"),source).sealFile(output,f.password,lease)}.isFailure)
            assertEquals(before,f.tree(source));assertEquals("previous file",output.readText())
        }
        val unknown=f.storage("destination");unknown.write("unrecognized-claim-extension","{\"version\":1}")
        f.owned(unknown){lease->
            val before=f.tree(unknown)
            assertTrue(runCatching {NativeMigrationExport(f.context("destination"),unknown).sealFile(output,f.password,lease)}.isFailure)
            assertEquals(before,f.tree(unknown));assertEquals("previous file",output.readText())
        }
    }
    @Test fun publicationFaultAndMissingDestinationOwnerPreserveOriginalGeneration()=runBlocking {
        val f=NativeMigrationPrivateClaimFixtures();f.fresh();val source=f.seedSource();val raw=f.intent();f.seedClaim(source,raw)
        val envelope=f.export(source);val destination=f.storage("destination");val local=f.intent("local-original-claim-host");f.seedClaim(destination,local)
        val receipt=NativeMigrationCandidateStore(f.context("destination"),destination).stageFile(UUID.randomUUID().toString(),envelope,f.password)
        val beforeOwner=f.tree(destination)
        assertTrue(runCatching {NativeMigrationActivation(f.context("destination"),destination).activate(receipt)}.isFailure)
        assertEquals(beforeOwner,f.tree(destination))
        f.owned(destination){lease->
            val before=f.tree(destination)
            assertTrue(runCatching {NativeMigrationActivation(f.context("destination"),destination,lease.notification(receipt),lease).activate(receipt){point->if(point==0)error("controlled final publication fault")}}.isFailure)
            assertEquals(before,f.tree(destination));assertEquals(local,destination.read(f.name(local)));assertNull(destination.publishedMigration(receipt.id,receipt.sha256,receipt.recordCount))
            destination.write("migration-private-claims-v2","{\"version\":2}")
            val unknownBefore=f.tree(destination)
            assertTrue(runCatching {NativeMigrationActivation(f.context("destination"),destination,lease.notification(receipt),lease).activate(receipt)}.isFailure)
            assertEquals(unknownBefore,f.tree(destination));assertEquals("{\"version\":2}",destination.read("migration-private-claims-v2"))
        }
        assertEquals(f.exportCiphertext,f.tree(source))
    }
}
