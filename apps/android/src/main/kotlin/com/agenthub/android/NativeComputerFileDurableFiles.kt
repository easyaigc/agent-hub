package com.agenthub.android

import android.os.Process
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructStat
import java.io.File
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.security.MessageDigest

internal class ComputerFilePersistenceFailure(cause: Throwable): IllegalStateException(ComputerFileCopy.LOCAL_SAVE_PENDING,cause)

/** F59 ciphertext port only. No AtomicFile recovery/rollback or implicit deletion.
 * Callers hold the root snapshot fence and storage ioLock. An exclusive kernel
 * lock also serializes cooperating app processes. Same-UID hostile code is not
 * sandboxed by advisory locking. Every failed operation leaves its exact files.
 */
internal class NativeComputerFileDurableFiles(private val folder: File,private val authority: ()->Unit) {
    private data class Identity(val dev: Long,val inode: Long,val uid: Int,val gid: Int,val mode: Int,
        val links: Long,val size: Long,val modified: Long,val changed: Long)
    private data class Record(val identity: Identity,val bytes: ByteArray)
    private class Lease(val check: ()->Unit,val sync: ()->Unit)
    private val held=ThreadLocal<Lease>()
    private fun identity(s: StructStat)=Identity(s.st_dev,s.st_ino,s.st_uid,s.st_gid,s.st_mode,s.st_nlink,s.st_size,s.st_mtime,s.st_ctime)
    private fun stat(file: File): StructStat?=try {Os.lstat(file.path)}catch(failure: ErrnoException) {
        if(failure.errno==OsConstants.ENOENT)null else throw failure
    }
    private fun directory(file: File): StructStat {
        require(file.absoluteFile==file.canonicalFile&&!java.nio.file.Files.isSymbolicLink(file.toPath()))
        return Os.lstat(file.path).also {require(OsConstants.S_ISDIR(it.st_mode)&&it.st_uid==Process.myUid())}
    }
    private fun sameDirectory(expected: StructStat,actual: StructStat) {
        require(OsConstants.S_ISDIR(actual.st_mode)&&expected.st_dev==actual.st_dev&&expected.st_ino==actual.st_ino&&
            expected.st_uid==actual.st_uid&&expected.st_gid==actual.st_gid&&expected.st_mode==actual.st_mode)
    }
    private fun regular(s: StructStat,maximum: Int,minimum: Int=29): Identity {
        require(OsConstants.S_ISREG(s.st_mode)&&s.st_uid==Process.myUid()&&s.st_gid==Process.myUid()&&
            (s.st_mode and 0xfff)==0x180&&s.st_nlink==1L&&s.st_size in minimum.toLong()..maximum.toLong())
        return identity(s)
    }
    private fun <T> durable(work: ()->T): T {
        try {return work()}catch(failure: Throwable) {
            if(failure is ComputerFileResourceCloseUnknown||failure is ComputerFilePersistenceFailure)throw failure
            throw ComputerFilePersistenceFailure(failure)
        }
    }
    private fun lease()=requireNotNull(held.get()).also {authority();it.check();ComputerFileFdOwners.requireReleased()}
    private fun base(name: String): File {
        require(name.matches(Regex("computer-files-v1:[a-f0-9]{64}"))||
            name.matches(Regex("computer-file-original:[a-f0-9-]{36}:[0-9]{1,4}")))
        val hash=MessageDigest.getInstance("SHA-256").digest(name.toByteArray(Charsets.UTF_8)).joinToString("") {"%02x".format(it)}
        return File(folder,"$hash.bin")
    }
    private fun siblings(base: File)=listOf(File(base.path+".new"),File(base.path+".bak"),File(base.path+".f59-new"))
    private fun settled(base: File) {require(siblings(base).all {stat(it)==null}) {ComputerFileCopy.LOCAL_SAVE_PENDING}}
    private fun readBytes(fd: FileDescriptor,size: Int): ByteArray {
        val bytes=ByteArray(size);var at=0
        try {
            while(at<size){val count=Os.read(fd,bytes,at,size-at);require(count>0);at+=count}
            require(Os.read(fd,ByteArray(1),0,1)==0);return bytes
        }catch(failure: Throwable){bytes.fill(0);throw failure}
    }
    /** Exact private object and ciphertext; fsync is a throwing syscall, not FileUtils.sync's boolean. */
    private fun snapshot(file: File,maximum: Int,synchronize: Boolean): Record? {
        val before=stat(file)?:return null
        val expected=regular(before,maximum)
        return ComputerFileFdOwners.raw(file.path,OsConstants.O_RDWR or OsConstants.O_NOFOLLOW or OsConstants.O_NONBLOCK) {fd->
            require(regular(Os.fstat(fd),maximum)==expected)
            val bytes=readBytes(fd,expected.size.toInt())
            try {
                if(synchronize)Os.fsync(fd)
                require(regular(Os.fstat(fd),maximum)==expected&&regular(requireNotNull(stat(file)),maximum)==expected)
                Record(expected,bytes)
            }catch(failure: Throwable){bytes.fill(0);throw failure}
        }
    }
    fun <T> transaction(work: ()->T): T {
        check(held.get()==null);authority();ComputerFileFdOwners.requireReleased()
        val parent=requireNotNull(folder.parentFile);val initial=directory(folder);val parentInitial=directory(parent)
        require(initial.st_gid==Process.myUid()&&(initial.st_mode and 0xfff)==0x1c0)
        return ComputerFileFdOwners.directory(folder.path) {directoryFd->
            ComputerFileFdOwners.directory(parent.path) {parentFd->
                val verifyDirectories={
                    authority();sameDirectory(initial,Os.fstat(directoryFd));sameDirectory(initial,directory(folder))
                    sameDirectory(parentInitial,Os.fstat(parentFd));sameDirectory(parentInitial,directory(parent))
                }
                verifyDirectories()
                val control=File(folder,"computer-files-writer-v1.lock")
                stat(control)?.let {regular(it,0,0)}
                ComputerFileFdOwners.raw(control.path,OsConstants.O_RDWR or OsConstants.O_CREAT or OsConstants.O_NOFOLLOW or OsConstants.O_NONBLOCK,0x180) {raw->
                    val locked=regular(Os.fstat(raw),0,0);require(regular(requireNotNull(stat(control)),0,0)==locked)
                    // Public Android FD constructor borrows raw; wrapper/channel and raw have distinct owners.
                    ComputerFileFdOwners.resource({FileOutputStream(raw)},{it.close()}) {wrapper->
                        ComputerFileFdOwners.resource({requireNotNull(wrapper.channel.tryLock()) {ComputerFileCopy.LOCAL_BUSY}},{it.release()}) {lock->
                            val current={verifyDirectories();check(lock.isValid);require(regular(Os.fstat(raw),0,0)==locked&&regular(requireNotNull(stat(control)),0,0)==locked)}
                            val lease=Lease(current,{current();Os.fsync(directoryFd);Os.fsync(parentFd);current()})
                            held.set(lease)
                            try {current();work().also {current()}}finally {held.remove()}
                        }
                    }
                }
            }
        }
    }
    /** No .new/.bak recovery. Reads also re-establish file+directory durability before publication. */
    fun read(name: String,maximum: Int): ByteArray? = durable {
        val lease=lease();val base=base(name);settled(base)
        val record=snapshot(base,maximum,true)
        try {settled(base);lease.sync();record?.bytes}
        catch(failure: Throwable){record?.bytes?.fill(0);throw failure}
    }
    fun write(name: String,bytes: ByteArray) = durable {
        require(bytes.size in 29..2*1024*1024)
        val lease=lease();val base=base(name);settled(base)
        val previous=snapshot(base,2*1024*1024,false)
        val temporary=File(base.path+".f59-new")
        try {
            val written=ComputerFileFdOwners.raw(temporary.path,OsConstants.O_RDWR or OsConstants.O_CREAT or OsConstants.O_EXCL or OsConstants.O_NOFOLLOW or OsConstants.O_NONBLOCK,0x180) {fd->
                require(regular(Os.fstat(fd),0,0)==regular(requireNotNull(stat(temporary)),0,0))
                var offset=0
                while(offset<bytes.size){val count=Os.write(fd,bytes,offset,bytes.size-offset);require(count>0);offset+=count}
                Os.fsync(fd)
                val exact=regular(Os.fstat(fd),bytes.size)
                require(exact.size==bytes.size.toLong())
                Os.lseek(fd,0L,OsConstants.SEEK_SET)
                val observed=readBytes(fd,bytes.size)
                try {require(observed.contentEquals(bytes))}finally {observed.fill(0)}
                require(regular(Os.fstat(fd),bytes.size)==exact&&regular(requireNotNull(stat(temporary)),bytes.size)==exact)
                exact
            } // close must return successfully BEFORE rename; unknown close keeps .f59-new.
            lease.check();require(stat(File(base.path+".new"))==null&&stat(File(base.path+".bak"))==null)
            val current=snapshot(base,2*1024*1024,false)
            try {require(if(previous==null)current==null else current!=null&&current.identity==previous.identity&&current.bytes.contentEquals(previous.bytes))}
            finally {current?.bytes?.fill(0)}
            val staged=snapshot(temporary,bytes.size,true)
            try {require(staged!=null&&staged.identity==written&&staged.bytes.contentEquals(bytes))}finally {staged?.bytes?.fill(0)}
            lease.check();Os.rename(temporary.path,base.path) // Any error propagates; no failWrite, rollback, deletion or retry.
            val published=regular(requireNotNull(stat(base)),bytes.size)
            // Our rename may update ctime. Bind the same inode/metadata/length,
            // then require the complete post-rename identity to remain stable.
            require(written.copy(changed=published.changed)==published);settled(base)
            val installed=snapshot(base,bytes.size,true)
            try {require(installed!=null&&installed.identity==published&&installed.bytes.contentEquals(bytes))}finally {installed?.bytes?.fill(0)}
            lease.sync();settled(base);require(regular(requireNotNull(stat(base)),bytes.size)==published)
        }finally {previous?.bytes?.fill(0)}
    }
    /** Public Os.remove on a prevalidated regular file, under cooperating-writer
     * exclusion. This pathname operation is not an atomic inode-conditional
     * unlink against hostile same-UID code. No ignored delete boolean. */
    fun remove(name: String,expectedCiphertextSha256: String?) = durable {
        val lease=lease();val base=base(name);settled(base)
        val current=snapshot(base,2*1024*1024,true)
        try {
            if(current!=null) {
                require(expectedCiphertextSha256!=null&&hash(current.bytes)==expectedCiphertextSha256)
                require(regular(requireNotNull(stat(base)),2*1024*1024)==current.identity)
                lease.check();Os.remove(base.path)
            }else require(expectedCiphertextSha256==null)
            lease.sync();require(stat(base)==null&&siblings(base).all {stat(it)==null}) {ComputerFileCopy.CLEANUP_PENDING}
        }finally {current?.bytes?.fill(0)}
    }
    private fun hash(bytes: ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {"%02x".format(it)}
}
