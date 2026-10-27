package com.agenthub.android

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/** Only this encrypted claim can retire an output. Unclaimed files are never swept. */
internal class NativeCameraOutputs(context: Context,private val storage: NativeSessionStorage) {
    private val context=context.applicationContext
    private val scope=MessageDigest.getInstance("SHA-256").digest(storage.coordinationKey.toByteArray()).take(16).joinToString("") { "%02x".format(it) }
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
    suspend fun prepare(id: String): NativeCameraOutput = withContext(Dispatchers.IO) {
        storage.operationTransaction {
            require(!claimed(id)) { "Camera output was already prepared" }
            val output=output(id);check(directory.isDirectory||directory.mkdirs())
            check(output.file.createNewFile()) { "Unknown existing camera output was preserved" }
            try { storage.write(name(id),JSONObject().put("version",1).put("id",id).toString());output }
            catch(error: Exception) { if(output.file.canonicalFile==File(directory.canonicalFile,output.file.name))output.file.delete();throw error }
        }
    }
    suspend fun beforeRead(id: String) = withContext(Dispatchers.IO) {
        storage.operationTransaction {
            check(claimed(id)) { NativeFileIntakeFailure.UNAVAILABLE }
            val output=output(id);revoke(id)
            check(output.file.isFile) { NativeFileIntakeFailure.UNAVAILABLE }
        }
    }
    fun revoke(id: String) { context.revokeUriPermission(output(id).uri,Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
    suspend fun cleanup(id: String) = withContext(Dispatchers.IO) {
        storage.operationTransaction {
            revoke(id)
            if(!claimed(id))return@operationTransaction
            val output=output(id)
            if(output.file.exists())check(output.file.isFile&&output.file.delete()) { "Owned camera output removal was not confirmed" }
            storage.remove(name(id));check(storage.read(name(id))==null)
        }
    }
}
