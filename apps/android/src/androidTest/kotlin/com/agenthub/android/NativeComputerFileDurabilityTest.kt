package com.agenthub.android

import android.os.Process
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.security.MessageDigest
import java.util.UUID

/** Real Android filesystem checks of the production F59 ciphertext port.
 * Opaque test bytes are not a Keystore/encryption test. A new engine instance is
 * not process death or a power cut, and controlled sidecars are not injected
 * fsync/rename/close failures. These checks never create a session or contact a Host.
 */
@RunWith(AndroidJUnit4::class)
class NativeComputerFileDurabilityTest {
    private val name="computer-files-v1:"+"a".repeat(64)
    private fun bytes(seed: Int)=ByteArray(257) {index->((index*31+seed) and 255).toByte()}
    private fun hash(value: ByteArray)=MessageDigest.getInstance("SHA-256").digest(value).joinToString("") {"%02x".format(it)}

    private data class ObjectState(val device: Long,val inode: Long,val uid: Int,val gid: Int,
        val mode: Int,val links: Long,val size: Long,val modified: Long,val changed: Long,
        val contentSha256: String?,val linkTarget: String?)

    private fun state(file: File): ObjectState {
        val value=Os.lstat(file.path)
        return ObjectState(value.st_dev,value.st_ino,value.st_uid,value.st_gid,value.st_mode,
            value.st_nlink,value.st_size,value.st_mtime,value.st_ctime,
            if(OsConstants.S_ISREG(value.st_mode))hash(file.readBytes())else null,
            if(OsConstants.S_ISLNK(value.st_mode))Os.readlink(file.path)else null)
    }
    private fun contents(folder: File): Map<String,ObjectState> =
        requireNotNull(folder.listFiles()).associate {it.name to state(it)}
    private fun absent(file: File): Boolean=try {Os.lstat(file.path);false}
        catch(error: ErrnoException){if(error.errno==OsConstants.ENOENT)true else throw error}

    private class Fixture {
        private val cache=InstrumentationRegistry.getInstrumentation().targetContext.cacheDir.canonicalFile
        val root=File(cache,"f59-durability-"+UUID.randomUUID().toString())
        init {
            check(root.parentFile==cache&&root.mkdir())
            Os.chmod(root.path,0x1c0)
        }
        fun folder(label: String): File=File(root,label).also {
            require(label.matches(Regex("[a-z0-9-]+"))&&it.mkdir())
            Os.chmod(it.path,0x1c0)
        }
        fun engine(folder: File)=NativeComputerFileDurableFiles(folder) {
            check(folder.parentFile==root&&root.absoluteFile==root.canonicalFile&&folder.absoluteFile==folder.canonicalFile)
            check(Os.lstat(root.path).st_uid==Process.myUid())
        }
        fun external(label: String,bytes: ByteArray)=File(root,label).also {
            require(label.matches(Regex("[a-z0-9-]+"))&&it.createNewFile())
            it.outputStream().use {out->out.write(bytes)}
            Os.chmod(it.path,0x180)
        }
        fun assertNoOpenFixtureDescriptors() {
            // Query actual OS links. Owner counters alone do not establish closure.
            val links=requireNotNull(File("/proc/self/fd").listFiles())
            for(link in links) {
                val target=try {Os.readlink(link.path)}catch(error: ErrnoException) {
                    if(error.errno==OsConstants.ENOENT)continue else throw error
                }
                check(target!=root.path&&!target.startsWith(root.path+"/")) {
                    "Owned fixture descriptor still open: ${link.name}; retain ${root.path}"
                }
            }
        }
        fun cleanup() {
            ComputerFileFdOwners.requireReleased()
            assertNoOpenFixtureDescriptors()
            check(root.parentFile==cache&&root.absoluteFile==root.canonicalFile)
            fun removeOwned(file: File) {
                check(file.path==root.path||file.path.startsWith(root.path+"/"))
                val value=Os.lstat(file.path)
                check(value.st_uid==Process.myUid())
                // lstat and Files.delete do not follow a fixture's test symlink.
                if(OsConstants.S_ISDIR(value.st_mode))requireNotNull(file.listFiles()).forEach(::removeOwned)
                Files.delete(file.toPath())
            }
            removeOwned(root)
            check(!Files.exists(root.toPath(),java.nio.file.LinkOption.NOFOLLOW_LINKS))
        }
    }
    private fun fixture(work: (Fixture)->Unit) {
        ComputerFileFdOwners.requireReleased()
        val value=Fixture()
        var primary: Throwable?=null
        try {work(value)}catch(error: Throwable){primary=error;throw error}
        finally {
            try {value.cleanup()}catch(cleanup: Throwable) {
                val failure=primary
                if(failure==null)throw cleanup else failure.addSuppressed(cleanup)
            }
        }
    }
    private fun baseAfterWrite(folder: File)=requireNotNull(folder.listFiles()).single {it.name.endsWith(".bin")}
    private fun expectPersistenceRefusal(work: ()->Unit) {
        val failure=runCatching(work).exceptionOrNull()
        assertTrue("Expected a fail-closed persistence refusal, got ${failure?.javaClass?.name}",failure is ComputerFilePersistenceFailure)
        ComputerFileFdOwners.requireReleased()
    }
    private fun refuseAll(engine: NativeComputerFileDurableFiles,folder: File,expected: Map<String,ObjectState>) {
        expectPersistenceRefusal {engine.transaction {engine.read(name,1024)}}
        assertEquals(expected,contents(folder))
        expectPersistenceRefusal {engine.transaction {engine.write(name,bytes(2))}}
        assertEquals(expected,contents(folder))
        expectPersistenceRefusal {engine.transaction {engine.remove(name,null)}}
        assertEquals(expected,contents(folder))
    }

    @Test fun actualWriteReplacementAndIndependentEngineReadKeepExactCiphertext()=fixture {f->
        val folder=f.folder("roundtrip");val writer=f.engine(folder)
        val original=bytes(1);writer.transaction {writer.write(name,original)}
        val base=baseAfterWrite(folder);val first=state(base)
        assertArrayEquals(original,base.readBytes())
        assertEquals(0x180,first.mode and 0xfff);assertEquals(1L,first.links)
        val reader=f.engine(folder)
        assertArrayEquals(original,reader.transaction {reader.read(name,1024)})
        assertEquals(first,state(base))
        val replacement=bytes(2);writer.transaction {writer.write(name,replacement)}
        assertArrayEquals(replacement,reader.transaction {reader.read(name,1024)})
        assertArrayEquals(replacement,base.readBytes())
        assertEquals(setOf(base.name,"computer-files-writer-v1.lock"),contents(folder).keys)
        assertEquals(0x180,state(base).mode and 0xfff);assertEquals(1L,state(base).links)
        f.assertNoOpenFixtureDescriptors()
    }

    @Test fun everyControlledSidecarRefusesReadWriteAndRemoveWithoutRecovery()=fixture {f->
        for((index,suffix) in listOf(".new",".bak",".f59-new").withIndex()) {
            val folder=f.folder("sidecar-$index");val engine=f.engine(folder)
            engine.transaction {engine.write(name,bytes(1))}
            val base=baseAfterWrite(folder);val sidecar=File(base.path+suffix)
            sidecar.outputStream().use {it.write(bytes(3))};Os.chmod(sidecar.path,0x180)
            val before=contents(folder)
            refuseAll(engine,folder,before)
            assertArrayEquals(bytes(1),base.readBytes());assertArrayEquals(bytes(3),sidecar.readBytes())
        }
        f.assertNoOpenFixtureDescriptors()
    }

    @Test fun wrongOrMissingCipherDigestCannotRetireTheOriginal()=fixture {f->
        val folder=f.folder("wrong-digest");val engine=f.engine(folder)
        engine.transaction {engine.write(name,bytes(1))};val before=contents(folder)
        expectPersistenceRefusal {engine.transaction {engine.remove(name,hash(bytes(2)))}}
        assertEquals(before,contents(folder))
        expectPersistenceRefusal {engine.transaction {engine.remove(name,null)}}
        assertEquals(before,contents(folder))
        val second=f.engine(folder);assertArrayEquals(bytes(1),second.transaction {second.read(name,1024)})
        f.assertNoOpenFixtureDescriptors()
    }

    @Test fun matchingCipherDigestRetiresOnlyTheExactRecordAndConfirmsAbsence()=fixture {f->
        val folder=f.folder("confirmed-delete");val engine=f.engine(folder)
        engine.transaction {engine.write(name,bytes(1))};val base=baseAfterWrite(folder)
        val independentName="computer-files-v1:"+"b".repeat(64)
        engine.transaction {engine.write(independentName,bytes(2))}
        val other=requireNotNull(folder.listFiles()).single {it.name.endsWith(".bin")&&it!=base}
        val retained=state(other)
        engine.transaction {engine.remove(name,hash(bytes(1)))}
        assertTrue(absent(base));assertEquals(retained,state(other))
        val reopened=f.engine(folder);assertNull(reopened.transaction {reopened.read(name,1024)})
        // Already-absent plus no expected ciphertext is a confirmed local no-op.
        reopened.transaction {reopened.remove(name,null)}
        expectPersistenceRefusal {reopened.transaction {reopened.remove(name,hash(bytes(1)))}}
        assertEquals(retained,state(other));assertTrue(absent(base))
        f.assertNoOpenFixtureDescriptors()
    }

    @Test fun nonPrivateRecordModeRefusesAllOperationsWithoutChangingTheRecord()=fixture {f->
        val folder=f.folder("record-mode");val engine=f.engine(folder)
        engine.transaction {engine.write(name,bytes(1))};val base=baseAfterWrite(folder)
        Os.chmod(base.path,0x1a4) // 0644 is deliberately outside the private-record contract.
        val before=contents(folder);refuseAll(engine,folder,before)
        assertArrayEquals(bytes(1),base.readBytes());assertEquals(0x1a4,state(base).mode and 0xfff)
        f.assertNoOpenFixtureDescriptors()
    }

    @Test fun symlinkRecordCannotReadReplaceOrDeleteItsOwnedExternalTarget()=fixture {f->
        val folder=f.folder("symlink");val engine=f.engine(folder)
        engine.transaction {engine.write(name,bytes(1))};val base=baseAfterWrite(folder)
        val external=f.external("external-symlink-target",bytes(7));val untouched=state(external)
        Files.delete(base.toPath());Os.symlink(external.path,base.path)
        val before=contents(folder);refuseAll(engine,folder,before)
        assertEquals(untouched,state(external));assertArrayEquals(bytes(7),external.readBytes())
        assertEquals(external.path,Os.readlink(base.path))
        f.assertNoOpenFixtureDescriptors()
    }

    @Test fun hardLinkedRecordIsRejectedAndBothOwnedNamesKeepTheSameBytes()=fixture {f->
        val folder=f.folder("hardlink");val engine=f.engine(folder)
        engine.transaction {engine.write(name,bytes(1))};val base=baseAfterWrite(folder)
        val alias=File(f.root,"external-hardlink-name");Os.link(base.path,alias.path)
        val aliasBefore=state(alias);val before=contents(folder)
        assertEquals(2L,aliasBefore.links);assertEquals(aliasBefore.inode,state(base).inode)
        refuseAll(engine,folder,before)
        assertEquals(aliasBefore,state(alias));assertArrayEquals(bytes(1),alias.readBytes())
        f.assertNoOpenFixtureDescriptors()
    }

    @Test fun truncatedRecordAndInvalidNewCiphertextAreNotSilentlyRepaired()=fixture {f->
        val folder=f.folder("bounds");val engine=f.engine(folder)
        engine.transaction {engine.write(name,bytes(1))};val base=baseAfterWrite(folder)
        val intact=contents(folder)
        expectPersistenceRefusal {engine.transaction {engine.write(name,ByteArray(28))}}
        assertEquals(intact,contents(folder))
        // Controlled malformed on-disk input; not a simulated torn fsync.
        base.outputStream().use {it.write(ByteArray(28) {3})};Os.chmod(base.path,0x180)
        val malformed=contents(folder);refuseAll(engine,folder,malformed)
        assertEquals(28L,base.length());assertArrayEquals(ByteArray(28) {3},base.readBytes())
        f.assertNoOpenFixtureDescriptors()
    }

    @Test fun competingEngineCannotEnterHeldKernelLockAndOriginalWriterRemainsUsable()=fixture {f->
        val folder=f.folder("writer-lock");val outer=f.engine(folder);val contender=f.engine(folder)
        outer.transaction {outer.write(name,bytes(1))}
        outer.transaction {
            val before=contents(folder)
            val failure=runCatching {contender.transaction {error("Competing writer entered its body")}}.exceptionOrNull()
            assertTrue("Expected the actual kernel/channel lock refusal, not a test-body error",
                failure is OverlappingFileLockException||failure?.message==ComputerFileCopy.LOCAL_BUSY)
            assertEquals(before,contents(folder))
            assertArrayEquals(bytes(1),outer.read(name,1024))
            outer.write(name,bytes(2))
        }
        // A real subsequent lock acquisition and OS descriptor inspection each
        // check a different observable; neither is a process-death claim.
        assertArrayEquals(bytes(2),contender.transaction {contender.read(name,1024)})
        ComputerFileFdOwners.requireReleased();f.assertNoOpenFixtureDescriptors()
    }
}
