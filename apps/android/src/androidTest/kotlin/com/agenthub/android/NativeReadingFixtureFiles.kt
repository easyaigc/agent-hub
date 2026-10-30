package com.agenthub.android

import android.os.Process
import android.system.Os
import android.system.OsConstants
import java.io.File

/** Public Android APIs only; never follow a replacement link or fsync a non-directory. */
internal object NativeReadingFixtureFiles {
    fun syncDirectory(root: File) {
        val fd=Os.open(root.path,OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW or OsConstants.O_NONBLOCK,0)
        try {
            val opened=Os.fstat(fd);val current=Os.lstat(root.path)
            require(OsConstants.S_ISDIR(opened.st_mode)&&opened.st_mode==16832&&opened.st_uid==Process.myUid())
            require(current.st_dev==opened.st_dev&&current.st_ino==opened.st_ino&&current.st_mode==opened.st_mode&&current.st_uid==opened.st_uid)
            Os.fsync(fd)
        }finally {Os.close(fd)}
    }
}
