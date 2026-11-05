package com.agenthub.android

import android.os.Process
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructStat
import java.io.File
import java.io.FileDescriptor

/** F59 migration reads only. All native handles have an owner before open;
 * directory handles stay bound for the callback and regular files are reopened
 * one at a time with the same captured identity and full ciphertext hash.
 * No create/read-write/rename/unlink/lock/AtomicFile path exists here.
 */
internal class NativeMigrationComputerFileSourceFiles(private val authority: ()->Unit) {
    private data class Identity(val dev: Long,val inode: Long,val uid: Int,val gid: Int,val mode: Int,
        val links: Long,val size: Long,val modified: Long,val changed: Long)
    private class Owned<R: Any>(private val release: (R)->Unit) {
        var value: R?=null
        var closeAttempted=false
        fun close() {
            check(!closeAttempted);closeAttempted=true
            value?.let(release)
        }
    }
    private val thread=Thread.currentThread()
    private val owned=mutableListOf<Owned<*>>()
    private var open=true
    fun current() {check(open&&Thread.currentThread()===thread);authority()}
    fun <R: Any> own(acquire: ()->R,release: (R)->Unit): R {
        current()
        val record=Owned(release);owned.add(record) // Before any native handle can be acquired.
        try {return acquire().also {record.value=it}}
        catch(error: Throwable){owned.remove(record);throw error}
    }
    private fun <R: Any,T> using(acquire: ()->R,release: (R)->Unit,consume: (R)->T): T {
        current()
        val record=Owned(release);owned.add(record)
        var primary: Throwable?=null
        try {
            val value=acquire();record.value=value
            return consume(value)
        }catch(error: Throwable){primary=error;throw error}
        finally {
            try {record.close();owned.remove(record)}catch(close: Throwable) {
                val first=primary?:close;if(first!==close)first.addSuppressed(close)
                NativeMigrationReadLifetime().hold(record,first) // Exact owner retained; never retry close, including EINTR.
            }
        }
    }
    fun closeOwned(prior: Throwable?=null) {
        check(Thread.currentThread()===thread)
        if(!open)return
        open=false
        var first=prior;var failed=false
        for(record in owned.asReversed())if(!record.closeAttempted) {
            try {record.close()}catch(error: Throwable){failed=true;if(first==null)first=error else if(first!==error)first.addSuppressed(error)}
        }
        if(failed)NativeMigrationReadLifetime().hold(this,requireNotNull(first))
        owned.clear()
    }
    private fun identity(value: StructStat)=Identity(value.st_dev,value.st_ino,value.st_uid,value.st_gid,value.st_mode,
        value.st_nlink,value.st_size,value.st_mtime,value.st_ctime)
    private fun stat(file: File): StructStat?=try {Os.lstat(file.path)}catch(error: ErrnoException) {
        if(error.errno==OsConstants.ENOENT)null else throw error
    }
    fun requireAbsent(file: File) {current();require(file.absoluteFile==file.canonicalFile&&stat(file)==null)}
    private fun directoryStat(file: File,privateMode: Boolean): StructStat {
        require(file.absoluteFile==file.canonicalFile&&!java.nio.file.Files.isSymbolicLink(file.toPath()))
        return Os.lstat(file.path).also {
            require(OsConstants.S_ISDIR(it.st_mode)&&it.st_uid==Process.myUid()&&it.st_gid==Process.myUid()&&
                (it.st_mode and 0x002)==0&&(!privateMode||(it.st_mode and 0xfff)==0x1c0))
        }
    }
    private fun directoryIdentity(value: StructStat)=listOf(value.st_dev,value.st_ino,value.st_uid.toLong(),value.st_gid.toLong(),value.st_mode.toLong())
    inner class Directory internal constructor(val file: File,private val privateMode: Boolean) {
        private val initial=directoryIdentity(directoryStat(file,privateMode))
        private val descriptor=own({Os.open(file.path,OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW or OsConstants.O_NONBLOCK,0)},{Os.close(it)})
        init {unchanged()}
        fun unchanged() {
            current();require(directoryIdentity(directoryStat(file,privateMode))==initial&&directoryIdentity(Os.fstat(descriptor))==initial)
        }
        fun names(maximum: Int): List<String> {
            unchanged();require(maximum>=0)
            val values=using({java.nio.file.Files.newDirectoryStream(file.toPath())},{it.close()}) {stream->
                val result=mutableListOf<String>()
                for(entry in stream) {
                    unchanged()
                    require(result.size<maximum) {"F59 source directory entry budget exceeded"}
                    result.add(entry.fileName.toString())
                }
                result.sorted()
            }
            unchanged();return values
        }
        fun synchronize() {unchanged();Os.fsync(descriptor);unchanged()}
    }
    fun directory(file: File,privateMode: Boolean=false)=Directory(file,privateMode)
    inner class Reference internal constructor(private val directory: Directory,val file: File,private val maximum: Int,
        private val zeroLength: Boolean=false) {
        private fun regular(): Identity {
            directory.unchanged()
            require(file.absoluteFile==file.canonicalFile&&file.parentFile==directory.file&&!java.nio.file.Files.isSymbolicLink(file.toPath()))
            val value=Os.lstat(file.path)
            require(OsConstants.S_ISREG(value.st_mode)&&value.st_uid==Process.myUid()&&value.st_gid==Process.myUid()&&
                (value.st_mode and 0xfff)==0x180&&value.st_nlink==1L&&
                (if(zeroLength)value.st_size==0L else value.st_size in 29..maximum.toLong()))
            return identity(value)
        }
        private val original=regular()
        val size get()=original.size
        private var digest: String?=null
        fun unchanged() {current();require(regular()==original)}
        /** The exact writer lock is metadata-only: opening/closing a second FD
         * could release a cooperating process record lock. Never open it here. */
        fun requireControl() {check(zeroLength);unchanged()}
        fun ciphertextSha256(): String {check(!zeroLength);return requireNotNull(digest)}
        fun bytes(synchronize: Boolean=false): ByteArray {
            check(!zeroLength);unchanged()
            val raw=using({Os.open(file.path,OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW or OsConstants.O_NONBLOCK,0)},
                {Os.close(it)}) {fd: FileDescriptor->
                require(identity(Os.fstat(fd))==original)
                val bytes=ByteArray(original.size.toInt());var offset=0
                try {
                    while(offset<bytes.size){val count=Os.read(fd,bytes,offset,bytes.size-offset);require(count>0);offset+=count}
                    require(Os.read(fd,ByteArray(1),0,1)==0)
                    if(synchronize)Os.fsync(fd) // O_RDONLY only. Unsupported/failed fsync refuses the snapshot.
                    require(identity(Os.fstat(fd))==original);unchanged();bytes
                }catch(error: Throwable){bytes.fill(0);throw error}
            } // Return is impossible until the actual descriptor close returned successfully.
            try {
                val actual=ComputerFileCodec.hash(raw);digest?.let {require(it==actual)};digest=actual
                return raw
            }catch(error: Throwable){raw.fill(0);throw error}
        }
        fun verify() {val bytes=bytes();bytes.fill(0)}
    }
    fun reference(directory: Directory,name: String,maximum: Int,control: Boolean=false): Reference {
        current();require(name.isNotEmpty()&&name !in setOf(".","..")&&name.none {it=='/'||it=='\\'||it=='\u0000'})
        return Reference(directory,File(directory.file,name),maximum,control)
    }
}
