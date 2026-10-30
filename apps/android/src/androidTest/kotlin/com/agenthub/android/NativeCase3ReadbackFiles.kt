package com.agenthub.android

import android.content.Context
import android.system.Os
import android.system.OsConstants
import android.system.StructStat
import java.io.File
import java.io.FileOutputStream
import java.nio.channels.FileLock
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS

/** All source data descriptors are read-only. Only the pre-existing zero-byte lock is opened RW,
 * without CREATE/truncation/write, to exclude cooperating production raw writers. Root must also
 * independently establish that the original instrumentation worker exited and no store is active. */
internal class NativeCase3ReadbackFiles(private val context: Context) {
    private val p=NativeCase3ReadbackContract
    val method=File(context.filesDir,p.METHOD_PATH)
    val fixture=File(method,p.FIXTURE)
    val output=File(context.filesDir,p.OUTPUT_PATH)
    private val ancestors=(listOf(context.filesDir)+listOf("acceptance","acceptance/P7-voice-interaction",
        "acceptance/P7-voice-interaction/"+p.RUN).map {File(context.filesDir,it)})
    private var pins: List<StructStat>?=null
    private var outputPin: StructStat?=null
    private var dataRootPin: StructStat?=null
    private var deleting=false
    private fun same(a: StructStat,b: StructStat)=a.st_dev==b.st_dev&&a.st_ino==b.st_ino&&a.st_mode==b.st_mode&&
        a.st_uid==b.st_uid&&a.st_gid==b.st_gid&&a.st_nlink==b.st_nlink&&a.st_size==b.st_size&&a.st_mtime==b.st_mtime&&a.st_ctime==b.st_ctime
    private fun directory(file: File): StructStat {
        val s=Os.lstat(file.path);require(OsConstants.S_ISDIR(s.st_mode)&&s.st_uid==p.UID&&s.st_gid==p.UID&&s.st_mode==16832)
        return s
    }
    /** Only the exact Android files Context root may use standard 0771. No descendant exception. */
    private fun frameworkFilesRoot(file: File): StructStat {
        val expected=context.filesDir;val data=File(context.applicationInfo.dataDir)
        require(file==expected&&!Files.isSymbolicLink(expected.toPath())&&!Files.isSymbolicLink(data.toPath()))
        require(expected.name=="files"&&expected.canonicalFile==File(data.canonicalFile,"files"))
        val parent=directory(data) // The app data enclosure remains exact private 0700.
        dataRootPin?.let {require(same(it,parent))}
        val parentFd=Os.open(data.path,OsConstants.O_RDONLY or OsConstants.O_NONBLOCK or OsConstants.O_NOFOLLOW,0)
        try {
            require(same(parent,Os.fstat(parentFd)))
            val before=Os.lstat(expected.path)
            require(OsConstants.S_ISDIR(before.st_mode)&&before.st_uid==p.UID&&before.st_gid==p.UID&&
                (before.st_mode and 0xfff) in setOf(0x1c0,0x1f9)) // exact 0700/0771, no special bits
            val fd=Os.open(expected.path,OsConstants.O_RDONLY or OsConstants.O_NONBLOCK or OsConstants.O_NOFOLLOW,0)
            try {
                require(same(before,Os.fstat(fd)))
                require(expected.canonicalFile==File(data.canonicalFile,"files"))
                require(same(before,Os.lstat(expected.path))&&same(before,Os.fstat(fd)))
                require(same(parent,Os.fstat(parentFd))&&same(parent,directory(data)))
                if(dataRootPin==null)dataRootPin=parent
                return before
            }finally {Os.close(fd)}
        }finally {Os.close(parentFd)}
    }
    private fun ancestor(index: Int,file: File)=if(index==0)frameworkFilesRoot(file)else directory(file)
    fun pinAncestors() {require(pins==null);pins=ancestors.mapIndexed(::ancestor);fence()}
    fun fence() {val expected=requireNotNull(pins);ancestors.forEachIndexed {i,f->require(same(expected[i],ancestor(i,f)))}}
    private fun exact(e: NativeCase3ReadbackContract.Entry,s: StructStat) {
        require(s.st_dev==p.DEVICE&&s.st_ino==e.inode&&s.st_mode==e.mode&&s.st_uid==p.UID&&s.st_gid==p.UID&&
            s.st_nlink==e.links.toLong()&&s.st_size==e.bytes&&s.st_mtime==e.time&&s.st_ctime==e.time) {"Source55 inventory mismatch: "+e.path}
    }
    private fun sourceParents(relative: String) {
        var parent=File(relative).parent.orEmpty()
        while(true) {
            val e=p.entries.single {it.path==parent};val f=if(parent.isEmpty())method else File(method,parent)
            val fd=Os.open(f.path,OsConstants.O_RDONLY or OsConstants.O_NONBLOCK or OsConstants.O_NOFOLLOW,0)
            try {
                for(stat in listOf(Os.fstat(fd),Os.lstat(f.path))) {
                    if(!deleting)exact(e,stat)else require(stat.st_dev==p.DEVICE&&stat.st_ino==e.inode&&
                        stat.st_mode==e.mode&&stat.st_uid==p.UID&&stat.st_gid==p.UID)
                }
            }finally{Os.close(fd)}
            if(parent.isEmpty())break
            parent=File(parent).parent.orEmpty()
        }
    }
    private fun outputFence() {
        val pin=requireNotNull(outputPin);val stat=directory(output)
        require(pin.st_dev==stat.st_dev&&pin.st_ino==stat.st_ino)
        require(output.canonicalFile==File(File(context.filesDir,"acceptance").canonicalFile,output.name))
    }
    private fun path(e: NativeCase3ReadbackContract.Entry)=if(e.path.isEmpty())method else File(method,e.path)
    private fun directoryInventory(e: NativeCase3ReadbackContract.Entry) {
        val f=path(e);val fd=Os.open(f.path,OsConstants.O_RDONLY or OsConstants.O_NONBLOCK or OsConstants.O_NOFOLLOW,0)
        try {
            exact(e,Os.fstat(fd));exact(e,Os.lstat(f.path))
            val expected=p.entries.filter {it.path.isNotEmpty()&&File(it.path).parent.orEmpty()==e.path}.map {File(it.path).name}.toSet()
            require(requireNotNull(f.list()).toSet()==expected) {"Unexpected/missing source member"}
            exact(e,Os.fstat(fd));exact(e,Os.lstat(f.path))
        }finally {Os.close(fd)}
    }
    fun source(e: NativeCase3ReadbackContract.Entry): ByteArray {
        require(!e.directory);fence();sourceParents(e.path);val f=path(e)
        val fd=Os.open(f.path,OsConstants.O_RDONLY or OsConstants.O_NONBLOCK or OsConstants.O_NOFOLLOW,0)
        var bytes: ByteArray?=null
        try {
            exact(e,Os.fstat(fd));exact(e,Os.lstat(f.path))
            val b=ByteArray(e.bytes.toInt());bytes=b;var at=0
            while(at<b.size){val n=Os.read(fd,b,at,b.size-at);require(n>0);at+=n}
            require(Os.read(fd,ByteArray(1),0,1)==0&&p.sha(b)==e.sha)
            exact(e,Os.fstat(fd));exact(e,Os.lstat(f.path));sourceParents(e.path);fence();return b
        }catch(t: Throwable){bytes?.fill(0);throw t}finally {Os.close(fd)}
    }
    fun source(relative: String)=source(p.entries.single {it.path==relative})
    fun inventory() {
        fence();p.entries.forEach {e->if(e.directory)directoryInventory(e)else source(e).fill(0)};fence()
    }
    fun <T> frozen(action: ()->T): T {
        inventory()
        val e=p.entries.single {it.path.endsWith("/voice-recordings-writer-v1.lock")}
        val fd=Os.open(path(e).path,OsConstants.O_RDWR or OsConstants.O_NONBLOCK or OsConstants.O_NOFOLLOW,0)
        // The FD constructor does not create or truncate. No writes are made to this stream.
        FileOutputStream(fd).use {stream->
            exact(e,Os.fstat(fd));exact(e,Os.lstat(path(e).path))
            val lock: FileLock=requireNotNull(stream.channel.tryLock()) {"Original raw writer remains active"}
            lock.use {require(it.isValid);inventory();return action()}
        }
    }
    private fun privateOutputParent() {directory(File(context.filesDir,"acceptance"))}
    fun newOutput() {
        privateOutputParent();require(Files.notExists(output.toPath(),NOFOLLOW_LINKS)) {"Prior read attempt exists; no query/replay"}
        require(output.mkdir());Os.chmod(output.path,448);outputPin=directory(output);outputFence()
    }
    fun existingOutput(expected: Set<String>) {
        privateOutputParent();outputPin=directory(output);outputFence();require(requireNotNull(output.list()).toSet()==expected)
    }
    fun writeNew(name: String,bytes: ByteArray) {
        require(name in setOf("read-intent.json","original.wav","read-receipt.json","cleanup-intent.json","cleanup-receipt.json"))
        outputFence();val f=File(output,name)
        val fd=Os.open(f.path,OsConstants.O_WRONLY or OsConstants.O_CREAT or OsConstants.O_EXCL or OsConstants.O_NOFOLLOW,384)
        FileOutputStream(fd).use {out->out.write(bytes);out.fd.sync()}
        val checked=outputBytes(name,bytes.size)
        try {require(checked.contentEquals(bytes))}finally{checked.fill(0)}
        outputFence();syncDirectory(output)
    }
    fun outputBytes(name: String,limit: Int): ByteArray {
        require(name in setOf("read-intent.json","original.wav","read-receipt.json","cleanup-intent.json","cleanup-receipt.json"))
        outputFence();val f=File(output,name);val fd=Os.open(f.path,OsConstants.O_RDONLY or OsConstants.O_NONBLOCK or OsConstants.O_NOFOLLOW,0)
        var bytes: ByteArray?=null
        try {
            val before=Os.fstat(fd);require(OsConstants.S_ISREG(before.st_mode)&&before.st_uid==p.UID&&before.st_gid==p.UID&&
                before.st_mode==33152&&before.st_nlink==1L&&before.st_size in 1..limit.toLong())
            val b=ByteArray(before.st_size.toInt());bytes=b;var at=0
            while(at<b.size){val n=Os.read(fd,b,at,b.size-at);require(n>0);at+=n}
            require(Os.read(fd,ByteArray(1),0,1)==0&&same(before,Os.fstat(fd))&&same(before,Os.lstat(f.path)))
            outputFence();return b
        }catch(t: Throwable){bytes?.fill(0);throw t}finally{Os.close(fd)}
    }
    private fun syncDirectory(f: File) {
        val before=directory(f)
        val fd=Os.open(f.path,OsConstants.O_RDONLY or OsConstants.O_NONBLOCK or OsConstants.O_NOFOLLOW,0)
        try {require(same(before,Os.fstat(fd)));Os.fsync(fd);require(same(before,Os.fstat(fd))&&same(before,directory(f)))}finally{Os.close(fd)}
    }
    /** Called only after typed ACK, a second authenticated read and complete preflight.
     * Never recurse or follow a caller-supplied path. Key deletion happens after these exact files. */
    fun removeExactFixture() {
        inventory();deleting=true
        p.entries.filter {!it.directory&&it.path.startsWith(p.FIXTURE+"/")}.forEach {e->
            source(e).fill(0);sourceParents(e.path);Os.remove(path(e).path)
            require(Files.notExists(path(e).toPath(),NOFOLLOW_LINKS))
        }
        p.entries.filter {it.directory&&(it.path==p.FIXTURE||it.path.startsWith(p.FIXTURE+"/"))}
            .sortedByDescending {it.path.length}.forEach {e->
                sourceParents(e.path);val f=path(e);val s=directory(f)
                require(s.st_dev==p.DEVICE&&s.st_ino==e.inode&&requireNotNull(f.list()).isEmpty())
                Os.remove(f.path);require(Files.notExists(f.toPath(),NOFOLLOW_LINKS))
            }
        syncDirectory(method);requireFixtureRemoved()
    }
    fun requireFixtureRemoved() {
        fence();val e=p.entries.first();val s=directory(method)
        require(s.st_dev==p.DEVICE&&s.st_ino==e.inode&&requireNotNull(method.list()).toSet()==setOf("intent.json","result.json"))
        require(Files.notExists(fixture.toPath(),NOFOLLOW_LINKS))
        source("intent.json").fill(0);source("result.json").fill(0)
    }
}
