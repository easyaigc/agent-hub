package com.agenthub.android

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Only this encrypted claim can retire an output. Unclaimed files are never swept. */
internal class NativeCameraOutputs(context: Context,private val storage: NativeSessionStorage) {
    private val context=context.applicationContext
    private val scope=NativeMigrationSelectionRawInventory.scope(storage.coordinationKey)
    private val directory=File(this.context.filesDir,"camera-output-v1/$scope")
    private fun name(id: String): String { require(UUID.fromString(id).toString()==id);return "output-v1-$id" }
    fun output(id: String): NativeCameraOutput {
        name(id);val expected=File(context.filesDir.canonicalFile,"camera-output-v1/$scope")
        require(directory.canonicalFile==expected) { NativeFileIntakeFailure.UNAVAILABLE }
        val file=File(directory,"photo-$id.jpg")
        require(file.canonicalFile==File(expected,file.name)) { NativeFileIntakeFailure.UNAVAILABLE }
        return NativeCameraOutput(FileProvider.getUriForFile(context,context.packageName+".resources",file),file)
    }
    private fun claimed(id: String): Boolean {
        val raw=storage.read(name(id)) ?: return false
        val value=JSONObject(raw);require(value.keys().asSequence().toSet()==setOf("version","id")&&value.getInt("version")==1&&value.getString("id")==id)
        return true
    }
    private suspend fun prepareOriginal(id: String): NativeCameraOutput = withContext(Dispatchers.IO) {
        storage.operationTransaction {
            require(!claimed(id)) { "Camera output was already prepared" }
            val output=output(id);check(directory.isDirectory||directory.mkdirs())
            check(output.file.createNewFile()) { "Unknown existing camera output was preserved" }
            try { storage.write(name(id),JSONObject().put("version",1).put("id",id).toString());output }
            catch(error: Exception) { if(output.file.canonicalFile==File(directory.canonicalFile,output.file.name))output.file.delete();throw error }
        }
    }
    private suspend fun beforeReadOriginal(id: String) = withContext(Dispatchers.IO) {
        storage.operationTransaction {
            check(claimed(id)) { NativeFileIntakeFailure.UNAVAILABLE }
            val output=output(id);revokeOriginal(id)
            check(output.file.isFile) { NativeFileIntakeFailure.UNAVAILABLE }
        }
    }
    private fun revokeOriginal(id: String) { context.revokeUriPermission(output(id).uri,Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
    private suspend fun cleanupOriginal(id: String) = withContext(Dispatchers.IO) {
        storage.operationTransaction {
            revokeOriginal(id)
            if(!claimed(id))return@operationTransaction
            val output=output(id)
            if(output.file.exists())check(output.file.isFile&&output.file.delete()) { "Owned camera output removal was not confirmed" }
            storage.remove(name(id));check(storage.read(name(id))==null)
        }
    }
    suspend fun prepare(id: String): NativeCameraOutput = NativeInboxOwnership.writing(storage){prepareOriginal(id)}
    suspend fun beforeRead(id: String) = NativeInboxOwnership.writing(storage){beforeReadOriginal(id)}
    suspend fun cleanup(id: String) = NativeInboxOwnership.writing(storage){cleanupOriginal(id)}
    suspend fun revoke(id: String) = NativeInboxOwnership.writing(storage){withContext(Dispatchers.IO){revokeOriginal(id)}}
    /** A callback or cooperative lock cannot prove an external camera descriptor has closed. */
    internal fun requireNoOriginalForMigration() {
        check(android.os.Looper.myLooper()!=android.os.Looper.getMainLooper())
        require(directory.canonicalFile==File(context.filesDir.canonicalFile,"camera-output-v1/$scope"))
        val path=directory.toPath()
        if(java.nio.file.Files.notExists(path,java.nio.file.LinkOption.NOFOLLOW_LINKS))return
        require(!java.nio.file.Files.isSymbolicLink(path))
        val stat=android.system.Os.lstat(directory.path)
        require(android.system.OsConstants.S_ISDIR(stat.st_mode)&&stat.st_uid==android.os.Process.myUid()&&stat.st_mode and 0x3f==0)
        if(requireNotNull(directory.listFiles()).isNotEmpty())throw NativeInboxOwnership.Busy()
    }

}
