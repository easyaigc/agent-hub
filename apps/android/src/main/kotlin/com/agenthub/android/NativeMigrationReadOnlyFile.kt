package com.agenthub.android

import android.os.Process
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.security.MessageDigest

/** Existing owned ciphertext only. No AtomicFile, mkdir, key generation, link following or repair. */
internal class NativeMigrationReadOnlyFile(private val folder: File,val file: File,private val maximum: Int) {
    internal data class Identity(val dev: Long,val inode: Long,val uid: Int,val mode: Int,val links: Long,val size: Long,val modified: Long)
    data class Read<T>(val value: T,val ciphertextSha256: String)
    private fun identity(stat: android.system.StructStat)=Identity(stat.st_dev,stat.st_ino,stat.st_uid,stat.st_mode,stat.st_nlink,stat.st_size,stat.st_mtime)
    private val directory=directory()
    val identity=regular()
    private fun directory(): Pair<Long,Long> {
        require(!Files.isSymbolicLink(folder.toPath()))
        val stat=Os.lstat(folder.path);require(OsConstants.S_ISDIR(stat.st_mode)&&stat.st_uid==Process.myUid()&&stat.st_mode and 0x3f==0)
        return stat.st_dev to stat.st_ino
    }
    private fun regular(): Identity {
        require(file.parentFile.canonicalFile==folder.canonicalFile&&!Files.isSymbolicLink(file.toPath()))
        val stat=Os.lstat(file.path)
        require(OsConstants.S_ISREG(stat.st_mode)&&stat.st_uid==Process.myUid()&&stat.st_mode and 0x3f==0&&stat.st_nlink==1L&&stat.st_size in 29..maximum.toLong())
        return identity(stat)
    }
    fun unchanged(){require(directory()==directory&&regular()==identity)}
    fun <T> read(complete: Boolean=true,consume: (InputStream)->T): Read<T> {
        unchanged();val fd=Os.open(file.path,OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW,0)
        try {
            require(identity(Os.fstat(fd))==identity)
            val hash=MessageDigest.getInstance("SHA-256");var count=0L
            val input=object: InputStream() {
                override fun read(): Int {val one=ByteArray(1);return if(read(one,0,1)<0)-1 else one[0].toInt() and 255}
                override fun read(bytes: ByteArray,offset: Int,length: Int): Int {
                    require(offset>=0&&length>=0&&offset<=bytes.size-length);if(length==0)return 0
                    val n=Os.read(fd,bytes,offset,length)
                    if(n==0)return -1
                    count+=n;require(count<=identity.size);hash.update(bytes,offset,n);return n
                }
            }
            val value=consume(input)
            if(complete)require(input.read()==-1&&count==identity.size)
            require(identity(Os.fstat(fd))==identity);unchanged()
            return Read(value,hash.digest().joinToString(""){"%02x".format(it)})
        }finally {Os.close(fd)}
    }
}

internal class NativeMigrationReadOnlyDirectory(private val folder: File) {
    private fun identity(): Pair<Long,Long> {
        require(!Files.isSymbolicLink(folder.toPath()))
        val s=Os.lstat(folder.path);require(OsConstants.S_ISDIR(s.st_mode)&&s.st_uid==Process.myUid()&&s.st_mode and 0x3f==0)
        return s.st_dev to s.st_ino
    }
    private val original=identity()
    private fun names(): List<String> {
        require(identity()==original)
        val files=requireNotNull(folder.listFiles());require(files.size<=512)
        return files.map {it.name.also {name->require(name.matches(Regex("[a-f0-9]{64}\\.bin")))}}.sorted()
    }
    private val names=names()
    val files=names.associateWith {name->NativeMigrationReadOnlyFile(folder,File(folder,name),23*1024*1024)}
    init {require(files.values.sumOf {it.identity.size}<=NativeMigrationStreamRecords.MAX_TOTAL_BYTES+512L*131_072)}
    fun unchanged(){require(names()==names);files.values.forEach {it.unchanged()}}
}

internal data class NativeMigrationInboxChild(val key: String,val present: Boolean,val records: NativeMigrationStreamRecords?,val unchanged: ()->Unit)
