package com.agenthub.android

import android.os.Process
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.util.UUID

/** One non-portable coordination file per installation, outside the raw-original directory. */
internal object NativeVoiceRawOwnership {
    private const val LOCK="voice-recordings-writer-v1.lock"
    private val gate=NativeVoiceRawGate(validate={file->
        val stat=Os.lstat(file.path)
        require(OsConstants.S_ISREG(stat.st_mode)&&stat.st_uid==Process.myUid()&&stat.st_nlink==1L&&stat.st_size==0L&&stat.st_mode and 0x3f==0)
    },identity={file->val stat=Os.lstat(file.path);stat.st_dev to stat.st_ino})
    private fun requireIo(){check(android.os.Looper.myLooper()!=android.os.Looper.getMainLooper()) {"Raw ownership IO must not run on Main"}}
    private fun lock(raw: File): File {
        val parent=requireNotNull(raw.absoluteFile.parentFile).canonicalFile
        require(raw.canonicalFile==File(parent,"voice-recordings-v1"))
        return File(parent,LOCK)
    }
    fun writer(file: File): NativeVoiceRawGate.Writer {
        requireIo()
        require(file.name.endsWith(".wav"));val id=file.name.removeSuffix(".wav");require(UUID.fromString(id).toString()==id)
        return gate.writer(lock(requireNotNull(file.parentFile)),id)
    }
    fun requireRoot(storage: NativeSessionStorage,raw: File) {
        val parent=requireNotNull(File(storage.coordinationKey).parentFile).canonicalFile
        require(raw.absoluteFile.parentFile.canonicalFile==parent&&raw.canonicalFile==File(parent,"voice-recordings-v1"))
    }
    fun requireFrozen(storage: NativeSessionStorage,operation: String,frozen: NativeVoiceRawGate.Frozen) {
        requireIo()
        val raw=File(requireNotNull(File(storage.coordinationKey).parentFile),"voice-recordings-v1")
        frozen.requireCurrent(storage,operation,lock(raw))
        val stat=Os.lstat(lock(raw).path);require(OsConstants.S_ISREG(stat.st_mode)&&stat.st_uid==Process.myUid()&&stat.st_nlink==1L&&stat.st_size==0L&&stat.st_mode and 0x3f==0)
    }
    fun freeze(storage: NativeSessionStorage,operation: String): NativeVoiceRawGate.Frozen {
        requireIo()
        storage.requireRawWriterOwner()
        require(UUID.fromString(operation).toString()==operation)
        val raw=File(requireNotNull(File(storage.coordinationKey).parentFile),"voice-recordings-v1")
        return gate.freeze(lock(raw),storage,operation)
    }
}
