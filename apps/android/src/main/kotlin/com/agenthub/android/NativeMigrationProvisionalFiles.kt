package com.agenthub.android

import android.content.Context
import android.os.Process
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.util.UUID

/** Read-only raw inventory: never makes a directory, repairs a WAV, follows a link or opens a writer. */
internal class NativeMigrationProvisionalFiles(context: Context,private val storage: NativeSessionStorage,known: Set<String>,private val owner: NativeMigrationOwnerLease?) {
    data class Original(val kind: String,val size: Int,val sha256: String,val blob: NativeMigrationBlob?)
    private data class Identity(val dev: Long,val inode: Long,val size: Long,val modified: Long,val mode: Int)
    private val root=File(context.noBackupFilesDir,"voice-recordings-v1").also {NativeVoiceRawOwnership.requireRoot(storage,it)}
    private fun writerFrozen(){val held=checkNotNull(owner);check(held.storage===storage);held.requireRawWriters()}
    private val present=Files.exists(root.toPath(),NOFOLLOW_LINKS)
    private val directory=if(present)directory()else null
    private fun identity(stat: android.system.StructStat)=Identity(stat.st_dev,stat.st_ino,stat.st_size,stat.st_mtime,stat.st_mode)
    private fun directory(): Identity {
        require(!Files.isSymbolicLink(root.toPath())&&root.canonicalFile==File(root.parentFile.canonicalFile,root.name))
        val stat=Os.lstat(root.path);require(OsConstants.S_ISDIR(stat.st_mode)&&stat.st_uid==Process.myUid()&&stat.st_mode and 0x3f==0)
        return identity(stat)
    }
    private fun names(): Set<String> = if(present) {
        require(directory()==directory);val names=(root.list()?:error("Raw voice inventory unavailable")).toSet();require(names.size<=128)
        names.onEach {name->require(name.endsWith(".wav"));val id=name.removeSuffix(".wav");require(UUID.fromString(id).toString()==id)}
    }else {require(Files.notExists(root.toPath(),NOFOLLOW_LINKS));emptySet()}
    private val names=names()
    val ids=names.map {it.removeSuffix(".wav")}.toSet()
    private val checks=mutableListOf<()->Unit>()
    private val originals: Map<String,Original>
    init {
        if(ids.isNotEmpty())writerFrozen()
        require(ids.all {it in known}) {"Unindexed raw recording retained; complete migration refused"}
        var total=0L
        for(id in ids) {val size=Os.lstat(File(root,"$id.wav").path).st_size;require(size in 0..NativeMigrationProvisionalVoice.MAX_RAW_BYTES.toLong());total=Math.addExact(total,size);require(total<=NativeMigrationStreamRecords.MAX_TOTAL_BYTES)}
        originals=ids.associateWith {id->
            val file=File(root,"$id.wav");val captured=identity(Os.lstat(file.path));val probe=NativeMigrationProbe()
            copy(file,captured,probe)
            val size=probe.size.toInt();val sha=probe.sha256();val prefix=probe.prefix()
            checks.add {val current=NativeMigrationProbe();copy(file,captured,current);require(current.size==size.toLong()&&current.sha256()==sha&&current.prefix().contentEquals(prefix))}
            Original(if(size==0)"empty"else"raw",size,sha,if(size==0)null else NativeMigrationBlob(size,sha,prefix){copy(file,captured,it)})
        }
        unchanged()
    }
    private fun copy(file: File,expected: Identity,output: OutputStream) {
        writerFrozen()
        require(directory()==directory)
        val fd=Os.open(file.path,OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW,0);val buffer=ByteArray(65536)
        try {
            val stat=Os.fstat(fd)
            require(OsConstants.S_ISREG(stat.st_mode)&&stat.st_uid==Process.myUid()&&stat.st_nlink==1L&&stat.st_mode and 0x3f==0&&stat.st_size in 0..NativeMigrationProvisionalVoice.MAX_RAW_BYTES.toLong()&&identity(stat)==expected)
            var remaining=stat.st_size
            while(remaining>0) {val n=Os.read(fd,buffer,0,minOf(remaining,buffer.size.toLong()).toInt());require(n>0);output.write(buffer,0,n);remaining-=n}
            require(Os.read(fd,buffer,0,1)==0&&identity(Os.fstat(fd))==expected)
            val after=Os.lstat(file.path);require(identity(after)==expected&&after.st_nlink==1L&&after.st_uid==Process.myUid()&&OsConstants.S_ISREG(after.st_mode))
            require(directory()==directory)
        }finally{buffer.fill(0);Os.close(fd)}
    }
    fun original(id: String)=originals[id]?:Original("absent",0,NativeMigrationProvisionalVoice.sha(byteArrayOf()),null).also {
        require(UUID.fromString(id).toString()==id&&Files.notExists(File(root,"$id.wav").toPath(),NOFOLLOW_LINKS))
    }
    fun unchanged() {
        if(ids.isNotEmpty())writerFrozen()
        require(names()==names)
        checks.forEach {it()}
    }
}
