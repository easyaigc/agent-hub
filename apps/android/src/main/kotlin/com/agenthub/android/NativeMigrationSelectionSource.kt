package com.agenthub.android

import android.content.Context
import android.os.Process
import android.system.Os
import android.system.OsConstants
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.security.MessageDigest
import java.util.UUID

internal enum class NativeMigrationSelectionNode(val suffix: String) { JOURNAL(""),PAYLOADS("-payloads"),OUTPUTS("-outputs") }
internal data class NativeMigrationSelectionSourceNode(val present: Boolean,val records: NativeMigrationStreamRecords?)
internal data class NativeMigrationSelectionRawStatus(val targetScope: String,val present: Boolean)
/** Authenticated source component, not a producer lease, publication permit or executable selection. */
internal data class NativeMigrationSelectionSource(val kind: NativeMigrationSelectionJournal.Kind,
    val journal: NativeMigrationSelectionSourceNode,val payloads: NativeMigrationSelectionSourceNode,
    val outputs: NativeMigrationSelectionSourceNode?,val raw: NativeMigrationSelectionRawStatus?,val unchanged: ()->Unit)

internal object NativeMigrationSelectionOutputClaims {
    fun validate(text: Map<String,String>,binary: Map<String,NativeMigrationBlob>) {
        require(binary.isEmpty()&&text.size<=128)
        for((name,raw)in text) {
            require(name.startsWith("output-v1-"));val id=name.removePrefix("output-v1-");require(UUID.fromString(id).toString()==id)
            val value=StrictMigrationJson.parse(raw.toByteArray(Charsets.UTF_8))
            require(value.keys().asSequence().toSet()==setOf("version","id")&&value.get("version")==1&&value.get("id")==id)
        }
    }
}

/** Raw inventory never samples a hash to infer external writer closure. Nonempty originals refuse. */
internal class NativeMigrationSelectionRawInventory(context: Context,outputKey: String) {
    companion object {
        fun scope(key: String)=MessageDigest.getInstance("SHA-256").digest(key.toByteArray()).take(16).joinToString(""){"%02x".format(it)}
    }
    private data class Stamp(val dev: Long,val inode: Long,val uid: Int,val mode: Int,val links: Long,val size: Long,val modified: Long)
    private val files=context.filesDir
    private val data=File(context.applicationInfo.dataDir)
    private val parent=File(files,"camera-output-v1")
    private val expected=scope(outputKey)
    private fun stamp(file: File,directory: Boolean,privateMode: Boolean=true): Stamp {
        require(!Files.isSymbolicLink(file.toPath()))
        val s=Os.lstat(file.path)
        require(s.st_uid==Process.myUid()&&(!privateMode||s.st_mode and 0x3f==0)&&(if(directory)OsConstants.S_ISDIR(s.st_mode)else OsConstants.S_ISREG(s.st_mode)&&s.st_nlink==1L))
        return Stamp(s.st_dev,s.st_ino,s.st_uid,s.st_mode,s.st_nlink,s.st_size,s.st_mtime)
    }
    private val dataStamp=stamp(data,true)
    private val filesStamp=stamp(files,true,false)
    private fun inventory(): Map<String,Stamp> {
        require(stamp(data,true)==dataStamp&&files.canonicalPath.startsWith(data.canonicalPath+File.separator))
        require(stamp(files,true,false).let {it.dev==filesStamp.dev&&it.inode==filesStamp.inode&&it.uid==filesStamp.uid&&it.mode==filesStamp.mode})
        if(Files.notExists(parent.toPath(),NOFOLLOW_LINKS))return emptyMap()
        val result=linkedMapOf("." to stamp(parent,true));val scopes=requireNotNull(parent.listFiles());require(scopes.size<=256)
        for(folder in scopes.sortedBy {it.name}) {
            require(folder.name.matches(Regex("[a-f0-9]{32}"))&&folder.canonicalFile==File(files.canonicalFile,"camera-output-v1/"+folder.name))
            result[folder.name]=stamp(folder,true)
            val entries=requireNotNull(folder.listFiles());require(entries.size<=128)
            for(file in entries.sortedBy {it.name}) {
                require(file.name.startsWith("photo-")&&file.name.endsWith(".jpg"))
                val id=file.name.removePrefix("photo-").removeSuffix(".jpg");require(UUID.fromString(id).toString()==id)
                result[folder.name+"/"+file.name]=stamp(file,false)
            }
        }
        return result
    }
    private val original=inventory()
    fun requirePortableEmpty() {
        // Other nonempty scopes also lack a proved ownership/closure explanation. Never omit them.
        if(original.keys.any {it.contains('/')})throw NativeMigrationExport.IncompleteScope(setOf("camera-outputs"))
        unchanged()
    }
    fun unchanged(){require(inventory()==original) {"Camera raw inventory changed; originals retained"}}
    fun status()=NativeMigrationSelectionRawStatus(expected,expected in original)
}
