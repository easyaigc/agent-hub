package com.agenthub.android

import android.system.Os
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import javax.crypto.AEADBadTagException

/** Real synthetic files, AndroidKeyStore, Session quiescence and raw Frozen.
 * These auxiliary Native cases are not Main UI, real Host, natural process
 * death, full migration, provider permission, or syscall-fault injection proof.
 * Every method is UNEXECUTED in the source candidate.
 */
@RunWith(AndroidJUnit4::class)
class NativeComputerFileSnapshotTest {
    private suspend fun fixture(work: suspend (NativeComputerFileSnapshotFixture)->Unit) {
        val owned=NativeComputerFileSnapshotFixture();var primary: Throwable?=null
        try {owned.prepare();work(owned)}catch(error: Throwable){primary=error;throw error}
        finally {
            try {owned.cleanup()}catch(cleanup: Throwable){val original=primary;if(original==null)throw cleanup else if(original!==cleanup)original.addSuppressed(cleanup)}
        }
    }
    /** Environmental fsync/Keystore/FD errors are not accepted as negative-case
     * success. Assertions, cancellation and unexpected platform failures escape.
     */
    private fun refused(fragment: String?=null,work: ()->Unit): Throwable {
        val failure=try {work();null}catch(error: Throwable){error}
        assertTrue("Expected precise source refusal; unexpected ${failure?.javaClass?.name}",
            failure is IllegalArgumentException||failure is IllegalStateException)
        if(fragment!=null)assertTrue(failure?.message.orEmpty().contains(fragment))
        return requireNotNull(failure)
    }

    @Test fun boundedDirectoryOverflowClosesItsActualStreamAndExpiredRealOwnerCannotEnumerate()=runBlocking(Dispatchers.IO) {fixture {f->
        val probe=f.probeFolder();for(index in 0..2)f.probeFile(probe,"entry-$index",byteArrayOf(index.toByte()))
        val lease=f.freeze();val state=f.state();val before=f.fixtureFds()
        val files=NativeMigrationComputerFileSourceFiles {lease.requireRawWriters();Unit}
        try {
            val directory=files.directory(probe,privateMode=true);val held=f.fixtureFds()
            assertTrue(held.size>before.size)
            refused("F59 source directory entry budget exceeded") {directory.names(2)}
            assertEquals(held,f.fixtureFds()) // DirectoryStream FD closed, fixed directory FD still owned.
            assertEquals(listOf("entry-0","entry-1","entry-2"),directory.names(3))
            assertEquals(state,f.state())
            f.invalidateActualOwner()
            refused("Migration owner is no longer quiescent") {directory.names(3)}
            assertEquals(held,f.fixtureFds())
        }finally {files.closeOwned()}
        assertEquals(before,f.fixtureFds());assertEquals(state,f.state())
    }}

    @Test fun replacedRecordIdentityIsRejectedWithoutReopeningOrChangingTheReplacement()=runBlocking(Dispatchers.IO) {fixture {f->
        val probe=f.probeFolder();val raw=ByteArray(129){it.toByte()}
        val original=f.probeFile(probe,"record",raw);val replacement=f.probeFile(probe,"replacement",raw)
        val lease=f.freeze();val before=f.fixtureFds()
        val files=NativeMigrationComputerFileSourceFiles {lease.requireRawWriters();Unit}
        try {
            val directory=files.directory(probe,true);val reference=files.reference(directory,"record",1024)
            val observed=reference.bytes();try {assertArrayEquals(raw,observed)}finally {observed.fill(0)}
            Files.move(replacement.toPath(),original.toPath(),StandardCopyOption.REPLACE_EXISTING)
            val changed=f.state();refused {reference.bytes().fill(0)}
            assertEquals(changed,f.state())
        }finally {files.closeOwned()}
        assertEquals(before,f.fixtureFds())
    }}

    @Test fun absentNamespaceAndChildKeyCannotBeCreatedByAnExistingOnlySnapshot()=runBlocking(Dispatchers.IO) {fixture {f->
        val lease=f.freeze();val before=f.state();val descriptors=f.fixtureFds()
        f.assertPlannedAliasAbsent(f.childAlias);f.assertPlannedAliasAbsent(f.wrongAlias)
        f.snapshot {source->assertFalse(source.present);assertTrue(source.graph.journals.isEmpty());assertTrue(source.chunks.isEmpty())}
        assertEquals(before,f.state());assertEquals(descriptors,f.fixtureFds());f.assertPlannedAliasAbsent(f.childAlias)
        val missing=File(f.noBackup,"not-created")
        var unexpected: NativeSessionStorage?=null
        try {
            refused("Existing namespace unavailable") {unexpected=NativeSessionStorage.existingComputerFileSourceDecoder(f.context,"not-created",f.wrongAlias)}
        }finally {unexpected?.retireWriter()}
        assertFalse(Files.exists(missing.toPath(),java.nio.file.LinkOption.NOFOLLOW_LINKS));f.assertPlannedAliasAbsent(f.wrongAlias)
        check(f.childFolder.mkdir());Os.chmod(f.childFolder.path,0x1c0) // Deliberate incomplete source, no key.
        val incomplete=f.state()
        refused("Existing installation key required") {f.storage.migrationComputerFileSourceSnapshot(f.context,lease,f.limits){error("Missing key must not reach callback")}}
        assertEquals(incomplete,f.state());assertEquals(descriptors,f.fixtureFds());f.assertPlannedAliasAbsent(f.childAlias)
    }}

    @Test fun unknownOriginalUsesRealCiphertextAndItsCallbacksRefuseAfterTheLeaseScopeReturns()=runBlocking(Dispatchers.IO) {fixture {f->
        val original=f.produce();f.freeze();val before=f.state();val descriptors=f.fixtureFds();val cipher=f.childCipherPins()
        lateinit var late: NativeMigrationBlob;lateinit var lateText: Map<String,String>
        f.snapshot {source->
            assertTrue(source.present);assertNull(source.generation);assertEquals(cipher,source.ciphertextPins())
            assertTrue(source.graph.absentCompletedChunks.isEmpty());assertEquals(setOf(f.binaryName),source.chunks.keys)
            val decoded=NativeComputerFileJournal.decode(f.journalName,source.journals.getValue(f.journalName),f.target)
            assertEquals(listOf(original),decoded.files)
            late=source.chunks.getValue(f.binaryName);lateText=source.journals
            val output=ByteArrayOutputStream();late.verifiedCopy(output);assertArrayEquals(f.bytes,output.toByteArray())
            source.unchanged()
        }
        assertEquals(descriptors,f.fixtureFds());assertEquals(before,f.state())
        val lateOutput=ByteArrayOutputStream()
        refused {late.verifiedCopy(lateOutput)};assertEquals(0,lateOutput.size())
        refused {lateText.getValue(f.journalName)}
        assertEquals(descriptors,f.fixtureFds());assertEquals(before,f.state());f.assertOnlyPlannedAliasesAdded()
        val sentinel=IllegalStateException("owned-consumer-sentinel")
        val failure=try {f.snapshot {source->
            assertEquals(cipher,source.ciphertextPins())
            source.chunks.getValue(f.binaryName).verifiedCopy(ByteArrayOutputStream())
            throw sentinel
        };null}catch(error: Throwable){error}
        assertSame(sentinel,failure)
        assertEquals(descriptors,f.fixtureFds());assertEquals(before,f.state())
        // Only a fully closed read can be opened again under this same actual owner.
        f.snapshot {source->assertEquals(cipher,source.ciphertextPins());source.unchanged()}
        assertEquals(descriptors,f.fixtureFds());assertEquals(before,f.state())
        var exercisedLiveCallbacks=false;val liveOutput=ByteArrayOutputStream()
        refused("Migration owner is no longer quiescent") {f.snapshot {source->
            val liveBlob=source.chunks.getValue(f.binaryName);val liveText=source.journals
            f.invalidateActualOwner()
            refused("Migration owner is no longer quiescent") {liveBlob.verifiedCopy(liveOutput)}
            assertEquals(0,liveOutput.size())
            refused("Migration owner is no longer quiescent") {liveText.getValue(f.journalName)}
            exercisedLiveCallbacks=true
            source.unchanged()
        }}
        assertTrue(exercisedLiveCallbacks);assertEquals(0,liveOutput.size())
        assertEquals(descriptors,f.fixtureFds());assertEquals(before,f.state());f.assertOnlyPlannedAliasesAdded()
    }}

    @Test fun controlledSidecarAndAuthenticatedOrphanRejectTheWholeSourceWithoutRepair()=runBlocking(Dispatchers.IO) {
        for(orphan in listOf(false,true))fixture {f->
            f.produce()
            if(orphan)f.orphan()else f.writeOwned(File(f.childFolder,ComputerFileCodec.hash(f.journalName.toByteArray())+".bin.f59-new"),ByteArray(37){3})
            f.freeze();val before=f.state();val descriptors=f.fixtureFds()
            refused(if(orphan)"orphan-or-changed-original"else"Unknown F59 source entry") {f.snapshot {error("Rejected full inventory cannot reach callback")}}
            assertEquals(before,f.state());assertEquals(descriptors,f.fixtureFds())
        }
    }

    @Test fun wrongInstalledKeyAndReplacedActualGenerationAreBothRejected()=runBlocking(Dispatchers.IO) {
        fixture {f->
            f.produce();f.overwriteJournalWithWrongInstalledKey();f.freeze();val before=f.state();val descriptors=f.fixtureFds()
            val failure=try {f.snapshot {error("Wrong-key source must not reach callback")};null}catch(error: Throwable){error}
            assertTrue("A wrong-key native GCM tag must fail, not an arbitrary platform failure",failure is AEADBadTagException)
            assertEquals(before,f.state());assertEquals(descriptors,f.fixtureFds())
        }
        fixture {f->
            f.prepareGeneration();f.produce();f.freeze();val descriptors=f.fixtureFds();var entered=false;var mutated: Map<String,NativeComputerFileSnapshotFixture.State>?=null
            val active=File(f.storage.coordinationKey,"migration-generation-"+requireNotNull(f.generation))
            val saved=File(active.parentFile,"owned-replaced-generation-${f.run}")
            try {
                refused {f.snapshot {source->
                    entered=true;assertEquals(f.generation,source.generation)
                    Files.move(active.toPath(),saved.toPath());check(active.mkdir());Os.chmod(active.path,0x1c0)
                    mutated=f.state();source.unchanged()
                }}
                assertTrue(entered);assertNotNull(mutated);assertEquals(mutated,f.state());assertEquals(descriptors,f.fixtureFds())
            }finally {
                if(Files.exists(saved.toPath(),java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                    check(requireNotNull(active.list()).isEmpty());Files.delete(active.toPath());Files.move(saved.toPath(),active.toPath())
                }
            }
        }
    }

    @Test fun completedPartialRetirementRequiresTheRealReadonlyFileAndDirectoryBarriers()=runBlocking(Dispatchers.IO) {fixture {f->
        val original=f.produce(ComputerFilePhase.COMPLETED,retireChunk=true);f.freeze()
        val before=f.state();val descriptors=f.fixtureFds();val cipher=f.childCipherPins()
        // No Assume/catch fallback. Unsupported O_RDONLY fsync is a real test failure.
        f.snapshot {source->
            assertEquals(cipher,source.ciphertextPins());assertTrue(source.chunks.isEmpty())
            assertEquals(listOf(f.binaryName),source.graph.absentCompletedChunks)
            assertEquals(listOf(original),NativeComputerFileJournal.decode(f.journalName,source.journals.getValue(f.journalName),f.target).files)
            source.unchanged()
        }
        assertEquals(before,f.state());assertEquals(descriptors,f.fixtureFds())
    }}

    @Test fun missingUnknownAndWrongTerminalReceiptCannotBeAcceptedAsRetiredOriginals()=runBlocking(Dispatchers.IO) {
        for(badReceipt in listOf(false,true))fixture {f->
            f.produce(if(badReceipt)ComputerFilePhase.COMPLETED else ComputerFilePhase.UNKNOWN,retireChunk=true,badReceipt=badReceipt)
            f.freeze();val before=f.state();val descriptors=f.fixtureFds()
            refused(if(badReceipt)"电脑返回的文件回执不匹配"else"unconfirmed-original-absent") {f.snapshot {error("Invalid original cannot reach callback")}}
            assertEquals(before,f.state());assertEquals(descriptors,f.fixtureFds())
        }
    }
}
