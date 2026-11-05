package com.agenthub.android

import java.io.Closeable
import java.io.File
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardOpenOption.*
import java.nio.file.attribute.PosixFilePermissions

/** Production writer ownership, including an OS advisory lock across cooperating app processes. */
internal class NativeVoiceRawGate(private val identity: (File)->Any={requireNotNull(Files.readAttributes(it.toPath(),BasicFileAttributes::class.java,NOFOLLOW_LINKS).fileKey())},private val validate: (File)->Unit) {
    class Busy: IllegalStateException("录音仍在写入或收尾，请结束录音后再迁移。原录音未更改。")
    private class Handle(val channel: FileChannel,val lock: FileLock,val fileKey: Any,private val strictClose: (()->Unit)?=null): Closeable {
        override fun close(){strictClose?.let {it();return};try{lock.release()}finally{channel.close()}}
    }
    private class State(val handle: Handle,val frozen: Any?=null,val writers: MutableMap<String,Any> = mutableMapOf())
    private val monitor=Any()
    private val states=mutableMapOf<String,State>()
    private fun open(file: File,shared: Boolean): Handle {
        val channel=FileChannel.open(file.toPath(),setOf<java.nio.file.OpenOption>(CREATE,READ,WRITE,NOFOLLOW_LINKS),PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
        try {
            validate(file);val key=identity(file)
            val lock=channel.tryLock(0,Long.MAX_VALUE,shared)?:throw Busy()
            try {validate(file);check(identity(file)==key);return Handle(channel,lock,key)}
            catch(error: Throwable){lock.release();throw error}
        }
        catch(error: Throwable){channel.close();if(error is java.nio.channels.OverlappingFileLockException)throw Busy();throw error}
    }
    /** F59 acquisition only: every actual raw/wrapper/lock owner exists before
     * acquisition. Even failure BEFORE Writer returns retains an ambiguous close. */
    private fun openComputerFiles(file: File): Handle {
        val raw=ComputerFileFdOwners.acquireResource(
            {android.system.Os.open(file.path,android.system.OsConstants.O_RDWR or android.system.OsConstants.O_CREAT or
                android.system.OsConstants.O_NOFOLLOW or android.system.OsConstants.O_NONBLOCK,0x180)},
            {android.system.Os.close(it)})
        var wrapper: ComputerFileFdOwners.Resource<java.io.FileInputStream>?=null
        var locked: ComputerFileFdOwners.Resource<FileLock>?=null
        fun closeOwned() {
            // Attempt all actual closes before constructing aggregated diagnostics.
            val lockFailure: Throwable?=try {locked?.close();null}catch(error: Throwable){error}
            val wrapperFailure: Throwable?=try {wrapper?.close();null}catch(error: Throwable){error}
            val rawFailure: Throwable?=try {raw.close();null}catch(error: Throwable){error}
            val first=lockFailure?:wrapperFailure?:rawFailure
            if(first!=null){if(wrapperFailure!=null&&wrapperFailure!==first)first.addSuppressed(wrapperFailure)
                if(rawFailure!=null&&rawFailure!==first)first.addSuppressed(rawFailure);throw first}
        }
        try {
            val original=android.system.Os.fstat(raw.value)
            fun bound() {
                validate(file)
                val path=android.system.Os.lstat(file.path);val fd=android.system.Os.fstat(raw.value)
                for(actual in listOf(path,fd))check(android.system.OsConstants.S_ISREG(actual.st_mode)&&actual.st_uid==android.os.Process.myUid()&&
                    actual.st_nlink==1L&&actual.st_size==0L&&actual.st_mode and 0x3f==0&&
                    actual.st_dev==original.st_dev&&actual.st_ino==original.st_ino&&actual.st_uid==original.st_uid&&
                    actual.st_gid==original.st_gid&&actual.st_mode==original.st_mode)
            }
            bound();val key=identity(file)
            val stream=ComputerFileFdOwners.acquireResource({java.io.FileInputStream(raw.value)},{it.close()}).also {wrapper=it}
            // Borrowed read channel supports shared lock; only raw owns the descriptor.
            val lease=ComputerFileFdOwners.acquireResource({stream.value.channel.tryLock(0,Long.MAX_VALUE,true)?:throw Busy()},{it.release()}).also {locked=it}
            bound();check(identity(file)==key)
            return Handle(stream.value.channel,lease.value,key,::closeOwned)
        }catch(error: Throwable) {
            try {closeOwned()}catch(close: Throwable){if(close!==error)error.addSuppressed(close)}
            throw error
        }
    }
    class Writer internal constructor(private val validateWriter: ()->Unit,private val closeWriter: ()->Unit): Closeable {
        fun requireCurrent()=validateWriter()
        override fun close()=closeWriter()
    }
    class Frozen internal constructor(private val gate: NativeVoiceRawGate,private val key: String,private val token: Any,private val owner: Any,private val operation: String): Closeable {
        fun requireCurrent(actual: Any,id: String,expectedLock: File?=null) {
            check(actual===owner&&id==operation&&(expectedLock==null||expectedLock.canonicalPath==key))
            gate.requireFrozen(key,token)
        }
        override fun close()=gate.releaseFrozen(key,token)
    }
    private fun requireFrozen(key: String,token: Any)=synchronized(monitor) {
        val state=checkNotNull(states[key]);check(state.frozen===token&&state.handle.lock.isValid)
        val file=File(key);validate(file);check(identity(file)==state.handle.fileKey)
    }
    private fun releaseFrozen(key: String,token: Any)=synchronized(monitor) {
        val state=states[key]
        if(state?.frozen===token){states.remove(key);state.handle.close()}
    }
    /** F59 branch only; normal voice/inbox writer() remains unchanged below.
     * Publication/allocation failure also closes the exact newly acquired handle. */
    internal fun computerFileWriter(lockFile: File,record: String): Writer = synchronized(monitor) {
        val key=lockFile.canonicalPath;val previous=states[key]
        if(previous?.frozen!=null||previous?.writers?.containsKey(record)==true)throw Busy()
        previous?.let {validate(lockFile);check(identity(lockFile)==it.handle.fileKey)}
        var opened: Handle?=null;var joined: State?=null;val token=Any()
        try {
            val state=previous?:State(openComputerFiles(lockFile).also {opened=it})
            joined=state
            if(previous==null)states[key]=state
            state.writers[record]=token
            Writer({synchronized(monitor) {
                check(states[key]===state&&state.writers[record]===token&&state.handle.lock.isValid)
                validate(lockFile);check(identity(lockFile)==state.handle.fileKey)
            }},{synchronized(monitor) {
                if(state.writers[record]===token) {
                    state.writers.remove(record)
                    if(state.writers.isEmpty()&&states[key]===state){states.remove(key);state.handle.close()}
                }
            }})
        }catch(error: Throwable) {
            joined?.let {state->
                if(state.writers[record]===token)state.writers.remove(record)
                if(previous==null&&states[key]===state)states.remove(key)
            }
            try {opened?.close()}catch(close: Throwable){if(close!==error)error.addSuppressed(close)}
            throw error
        }
    }
    fun writer(lockFile: File,record: String): Writer = synchronized(monitor) {
        val key=lockFile.canonicalPath;val previous=states[key]
        if(previous?.frozen!=null||previous?.writers?.containsKey(record)==true)throw Busy()
        previous?.let {validate(lockFile);check(identity(lockFile)==it.handle.fileKey)}
        val state=previous?:State(open(lockFile,true)).also {states[key]=it}
        val token=Any();state.writers[record]=token
        Writer({synchronized(monitor) {
            check(states[key]===state&&state.writers[record]===token&&state.handle.lock.isValid)
            validate(lockFile);check(identity(lockFile)==state.handle.fileKey)
        }},{synchronized(monitor) {
            if(state.writers[record]===token) {
                state.writers.remove(record)
                if(state.writers.isEmpty()&&states[key]===state){states.remove(key);state.handle.close()}
            }
        }})
    }
    fun freeze(lockFile: File,owner: Any,operation: String): Frozen = synchronized(monitor) {
        val key=lockFile.canonicalPath;if(states.containsKey(key))throw Busy()
        val token=Any();val state=State(open(lockFile,false),token);states[key]=state
        Frozen(this,key,token,owner,operation)
    }
}
