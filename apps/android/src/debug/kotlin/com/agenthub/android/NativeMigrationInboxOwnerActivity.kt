package com.agenthub.android

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Looper
import android.os.Process
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.util.UUID

/** Same Main lifecycle and owner; only the local installation is supplied before launch. */
class NativeMigrationInboxOwnerActivity : MainActivity() {
    private class Installation(val run: String,val context: Context,val session: MobileSession)
    companion object {
        private var active: Installation?=null
        internal fun prepare(run: String,context: Context,session: MobileSession) {
            check(Looper.myLooper()!=Looper.getMainLooper())
            require(UUID.fromString(run).toString()==run)
            require(context.packageName=="com.easyai.agenthub.android.acceptance"&&context.applicationContext===context)
            require(context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE!=0&&context.applicationInfo.uid==Process.myUid())
            val data=File(context.applicationInfo.dataDir).canonicalFile
            val files=context.filesDir.canonicalFile
            require(files.parentFile==data)
            val parent=File(files,"acceptance/P7-inbox-$run")
            val expected=File(parent,"source")
            require(context.noBackupFilesDir.canonicalFile==expected)
            for(file in listOf(data,File(files,"acceptance"),parent,expected)) {
                val stat=Os.lstat(file.path)
                require(OsConstants.S_ISDIR(stat.st_mode)&&stat.st_uid==Process.myUid()&&stat.st_mode and 0x3f==0)
            }
            require(File(session.migrationStorage.coordinationKey).canonicalFile==File(expected,"installation"))
            synchronized(this){check(active==null);active=Installation(run,context,session)}
        }
        internal fun release(run: String) = synchronized(this) {
            check(active?.run==run);active=null
        }
    }
    private var captured: Installation?=null
    private fun installation(): Installation=captured ?: synchronized(Companion) {
        checkNotNull(active){"An explicit private migration installation must be prepared before launch"}
    }.also {captured=it}
    override fun runtimeContext(): Context=installation().context
    override fun createInitialSession(context: Context): InitialSession {
        val original=installation();check(context===original.context)
        return InitialSession(original.session,false)
    }
}
