package com.agenthub.android

import android.system.Os
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.agenthub.core.ComputerFileFamilyChild
import com.agenthub.core.ComputerFileFamilySourcePointer
import com.agenthub.core.ComputerFileFamilyTerminalObservation
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files

/** UNEXECUTED candidate. Real synthetic Keystore/files/Session raw Frozen,
 * without Main UI, Host/model commands, default namespace or activation.
 * Platform/close errors are failures, never assumptions or negative passes.
 */
@RunWith(AndroidJUnit4::class)
class NativeComputerFileFamilySnapshotTest {
    private suspend fun fixture(work: suspend (NativeComputerFileSnapshotFixture)->Unit) {
        val f=NativeComputerFileSnapshotFixture();var primary: Throwable?=null
        try {f.prepare();work(f)}catch(error: Throwable){primary=error;throw error}
        finally {
            try {f.cleanup()}catch(cleanup: Throwable) {
                val original=primary;if(original==null)throw cleanup else if(original!==cleanup)original.addSuppressed(cleanup)
            }
        }
    }
    private fun refused(fragment: String?=null,work: ()->Unit): Throwable {
        val failure=try {work();null}catch(error: Throwable){error}
        assertTrue("Expected exact source refusal; unexpected ${failure?.javaClass?.name}",
            failure is IllegalArgumentException||failure is IllegalStateException)
        if(fragment!=null)assertTrue(failure?.message.orEmpty().contains(fragment))
        return requireNotNull(failure)
    }

    @Test fun originalAndTwoPublishedGenerationsKeepEverySameNamedOriginalAndReceiptSeparate()=runBlocking(Dispatchers.IO) {fixture {f->
        val originalBytes=ByteArray(257){((it*3+11) and 255).toByte()}
        val firstBytes=ByteArray(257){((it*7+23) and 255).toByte()}
        val currentBytes=ByteArray(257){((it*13+41) and 255).toByte()}
        val original=f.produce(ComputerFilePhase.COMPLETED,payload=originalBytes)
        val first=f.prepareNextFamilyGeneration();val old=f.produce(payload=firstBytes)
        val second=f.prepareNextFamilyGeneration();val current=f.produce(payload=currentBytes);f.freeze()
        val expectedRows=mapOf<String?,PendingComputerFile>(null to original,first to old,second to current)
        val expectedBytes=mapOf<String?,ByteArray>(null to originalBytes,first to firstBytes,second to currentBytes)
        assertEquals(3,expectedRows.values.map {it.sha256}.toSet().size)
        assertEquals(ComputerFilePhase.COMPLETED,original.phase);assertNotNull(original.receipt)
        assertEquals(ComputerFilePhase.UNKNOWN,old.phase);assertNull(old.receipt)
        assertEquals(ComputerFilePhase.UNKNOWN,current.phase);assertNull(current.receipt)
        val before=f.state();val descriptors=f.fixtureFds()
        f.family {family->
            assertEquals(listOf<String?>(null)+listOf(first,second).sorted(),family.generations)
            assertEquals(second,family.currentGeneration)
            for(id in family.generations) {
                val member=family.member(id);assertTrue(member.present);assertEquals(id,member.generation)
                assertEquals(listOf(expectedRows.getValue(id)),NativeComputerFileJournal.decode(f.journalName,member.journals.getValue(f.journalName),f.target).files)
                assertEquals(setOf(f.binaryName),member.chunks.keys)
                val output=ByteArrayOutputStream();member.chunks.getValue(f.binaryName).verifiedCopy(output)
                assertArrayEquals(expectedBytes.getValue(id),output.toByteArray());assertTrue(member.graph.absentCompletedChunks.isEmpty())
            }
            // Three authenticated sources are preserved, never coalesced by
            // equal target/journal/original IDs into the active generation.
            val pins=family.ciphertextPins();assertTrue(pins.keys.any {it.startsWith("original-root/")})
            assertTrue(pins.keys.any {it.startsWith("generation-$first/")})
            assertTrue(pins.keys.any {it.startsWith("generation-$second/")})
            family.unchanged()
        }
        assertEquals(before,f.state());assertEquals(descriptors,f.fixtureFds());f.assertNoPorts()
    }}

    @Test fun typedInventoryRetainsAbsentEmptyLiveAndExactRetiredRootWithOriginalPins()=runBlocking(Dispatchers.IO) {fixture {f->
        val retiredBytes = ByteArray(NativeComputerFileStore.CHUNK_BYTES) { ((it * 17 + 3) and 255).toByte() }
        val remainingBytes = ByteArray(257) { ((it * 11 + 7) and 255).toByte() }
        val remainingName = "computer-file-original:${f.id}:1"
        val original = f.producePartialRetirement(retiredBytes, remainingBytes)
        val originalPins=f.childCipherPins()
        val absent=f.prepareNextFamilyGeneration()
        val empty=f.prepareNextFamilyGeneration()
        // Create the fixture child and key by the normal producer first, then
        // remove only its exact synthetic journal before any source freeze.
        f.produce(ComputerFilePhase.COMPLETED,retireChunk=true)
        val journalFile=ComputerFileCodec.hash(f.journalName.toByteArray(Charsets.UTF_8))+".bin"
        assertEquals(setOf(journalFile),f.childCipherPins().keys)
        Files.delete(File(f.childFolder,journalFile).toPath())
        assertTrue(f.childCipherPins().isEmpty())
        val live=f.prepareNextFamilyGeneration()
        val current=f.produce()
        val livePins=f.childCipherPins()
        val pointerName=ComputerFileCodec.hash("migration-active-generation-v1".toByteArray(Charsets.UTF_8))+".bin"
        val pointerPin=ComputerFileCodec.hash(File(File(f.noBackup,f.namespace),pointerName).readBytes())
        f.freeze()
        val before=f.state();val descriptors=f.fixtureFds()
        lateinit var originalFamily: NativeMigrationComputerFileFamilySnapshot
        lateinit var frozenJournalSha256: String
        val inventory = f.family { family ->
            originalFamily = family
            val sourceRoot = family.member(null)
            val rawJournal = sourceRoot.journals.getValue(f.journalName)
            val frozenOriginal = NativeComputerFileJournal.decode(f.journalName, rawJournal, f.target).files.single()
            assertEquals(original, frozenOriginal)
            assertEquals("completed", frozenOriginal.receipt?.phase)
            assertEquals(original.manifest(f.target.scope.hostId), frozenOriginal.receipt?.file)
            frozenJournalSha256 = ComputerFileCodec.hash(rawJournal.toByteArray(Charsets.UTF_8))
            assertEquals(listOf(f.binaryName), sourceRoot.graph.absentCompletedChunks)
            assertEquals(setOf(remainingName), sourceRoot.chunks.keys)
            val output = ByteArrayOutputStream()
            sourceRoot.chunks.getValue(remainingName).verifiedCopy(output)
            assertArrayEquals(remainingBytes, output.toByteArray())
            family.inertInventory()
        }
        val members=inventory.members
        assertEquals(listOf<String?>(null)+listOf(absent,empty,live).sorted(),members.map {it.generation})
        val byId=members.associateBy {it.generation}
        assertEquals(ComputerFileFamilyChild.ABSENT,byId.getValue(absent).child)
        assertEquals(ComputerFileFamilyChild.PRESENT_EMPTY,byId.getValue(empty).child)
        val root=byId.getValue(null)
        assertEquals(ComputerFileFamilyChild.PRESENT_WITH_RECORDS,root.child)
        assertEquals(originalPins.getValue(journalFile),root.journals.single().sourceCiphertextSha256)
        assertEquals(frozenJournalSha256, root.journals.single().entry.sha256)
        val remaining = root.chunks.single()
        assertEquals(remainingName, remaining.entry.name)
        assertEquals(remainingBytes.size, remaining.entry.size)
        assertEquals(ComputerFileCodec.hash(remainingBytes), remaining.entry.sha256)
        val remainingFile = ComputerFileCodec.hash(remainingName.toByteArray(Charsets.UTF_8)) + ".bin"
        assertEquals(originalPins.getValue(remainingFile), remaining.sourceCiphertextSha256)
        val retired=root.retired.single()
        assertEquals(f.journalName,retired.journalName)
        assertEquals(frozenJournalSha256, retired.terminalJournalSha256)
        assertEquals(original.id,retired.originalId);assertEquals(0,retired.index)
        assertEquals(f.binaryName,retired.entry.name)
        assertEquals(retiredBytes.size, retired.entry.size)
        assertEquals(ComputerFileCodec.hash(retiredBytes), retired.entry.sha256)
        assertEquals(ComputerFileFamilyTerminalObservation.SOURCE_FILE_AND_DIRECTORIES_SYNCED,retired.observation)
        val active=byId.getValue(live)
        assertEquals(f.binaryName,active.chunks.single().entry.name)
        assertEquals(current.chunks.single().sha256,active.chunks.single().entry.sha256)
        val chunkFile=ComputerFileCodec.hash(f.binaryName.toByteArray(Charsets.UTF_8))+".bin"
        assertEquals(livePins.getValue(chunkFile),active.chunks.single().sourceCiphertextSha256)
        assertTrue(active.retired.isEmpty())
        assertEquals(ComputerFileFamilySourcePointer.Observed(live,pointerPin),inventory.sourcePointer)
        refused {originalFamily.inertInventory()}
        assertEquals(before,f.state());assertEquals(descriptors,f.fixtureFds());f.assertNoPorts()
    }}

    @Test fun orphanedOriginalInOldRootRefusesTheWholeFamilyWhileCurrentEmptyChildStaysAbsent()=runBlocking(Dispatchers.IO) {fixture {f->
        f.orphan();f.prepareNextFamilyGeneration();f.freeze();val before=f.state();val descriptors=f.fixtureFds()
        f.snapshot {assertFalse(it.present)}
        var reached=false
        refused("F59 portable graph") {f.family {reached=true}}
        assertFalse(reached);assertEquals(before,f.state());assertEquals(descriptors,f.fixtureFds());f.assertNoPorts()
    }}

    @Test fun peerFromNoRetainedGenerationRefusesBeforeAnyFamilyConsumerOrMissingKeyCreation()=runBlocking(Dispatchers.IO) {fixture {f->
        f.prepareNextFamilyGeneration();val peer=f.unindexedFamilyPeer();f.freeze()
        val before=f.state();val descriptors=f.fixtureFds();f.assertAllFamilyChildAliasesAbsent()
        var reached=false
        refused("Unindexed F59 source family peer retained") {f.family {reached=true}}
        assertFalse(reached);assertTrue(peer.isDirectory);f.assertAllFamilyChildAliasesAbsent()
        assertEquals(before,f.state());assertEquals(descriptors,f.fixtureFds());f.assertNoPorts()
    }}

    @Test fun replacingOldGenerationDirectoryWithSameNameInvalidatesTheHeldRealDirectory()=runBlocking(Dispatchers.IO) {fixture {f->
        val old=f.prepareNextFamilyGeneration();f.prepareNextFamilyGeneration();f.freeze();val descriptors=f.fixtureFds()
        lateinit var changed: Map<String,NativeComputerFileSnapshotFixture.State>
        refused {f.family {family->
            val source=f.generationFolder(old);val backup=File(f.probeFolder(),"old-generation")
            Files.move(source.toPath(),backup.toPath());assertTrue(source.mkdir());Os.chmod(source.path,0x1c0)
            changed=f.state();refused {family.unchanged()};assertEquals(changed,f.state())
        }} // Automatic final validation must also reject the held old-generation identity.
        assertEquals(changed,f.state());assertEquals(descriptors,f.fixtureFds());f.assertNoPorts()
    }}

    @Test fun perMemberValidRecordsCannotMultiplyTheSingleFamilyJournalBudget()=runBlocking(Dispatchers.IO) {fixture {f->
        f.produce();f.prepareNextFamilyGeneration();f.produce();f.freeze()
        val before=f.state();val descriptors=f.fixtureFds()
        val limit=NativeMigrationComputerFileSnapshot.Limits(NativeMigrationComputerFileGraph.Limits(1,16,4L*1024*1024),8L*1024*1024)
        var reached=false
        refused("F59 family journal budget exceeded") {f.family(NativeMigrationComputerFileFamilySnapshot.Limits(limit)) {reached=true}}
        assertFalse(reached);assertEquals(before,f.state());assertEquals(descriptors,f.fixtureFds());f.assertNoPorts()
    }}

    @Test fun consumerFailureReturnsAllFamilyFdsAndOldCallbacksExpireWithoutLosingTheOriginalOwner()=runBlocking(Dispatchers.IO) {fixture {f->
        f.produce();f.prepareNextFamilyGeneration();f.produce();f.freeze();val before=f.state();val descriptors=f.fixtureFds()
        val sentinel=IllegalStateException("synthetic family consumer failure")
        lateinit var late: NativeMigrationBlob;lateinit var oldFamily: NativeMigrationComputerFileFamilySnapshot
        val actual=try {f.family {family->oldFamily=family;late=family.member(null).chunks.getValue(f.binaryName);throw sentinel};null}
            catch(error: Throwable){error}
        assertSame(sentinel,actual);assertEquals(descriptors,f.fixtureFds());assertEquals(before,f.state())
        val output=ByteArrayOutputStream();refused {late.verifiedCopy(output)};assertEquals(0,output.size())
        refused {oldFamily.generations};f.family {it.unchanged()}
        assertEquals(descriptors,f.fixtureFds());assertEquals(before,f.state());f.assertNoPorts()
    }}

    @Test fun absentChildrenInEveryGenerationRemainAbsentAndLiveOwnerInvalidationRejectsOldAndNewReads()=runBlocking(Dispatchers.IO) {fixture {f->
        f.prepareNextFamilyGeneration();f.prepareNextFamilyGeneration();f.freeze()
        val before=f.state();val descriptors=f.fixtureFds();f.assertAllFamilyChildAliasesAbsent()
        var exercised=false
        refused("Migration owner is no longer quiescent") {f.family {family->
            assertEquals(3,family.generations.size)
            family.generations.forEach {assertFalse(family.member(it).present)}
            f.invalidateActualOwner()
            refused("Migration owner is no longer quiescent") {family.generations}
            exercised=true
        }} // The old callbacks and the automatic final source validation both refuse.
        assertTrue("Absent-member and invalidated-owner assertions must actually run",exercised)
        assertEquals(before,f.state());assertEquals(descriptors,f.fixtureFds());f.assertAllFamilyChildAliasesAbsent();f.assertNoPorts()
    }}
}
