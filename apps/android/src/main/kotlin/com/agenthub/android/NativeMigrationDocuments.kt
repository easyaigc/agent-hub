package com.agenthub.android

import android.content.Context
import android.net.Uri
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** SAF streams only encrypted envelopes. No plaintext export/attachment scratch or source-URI deletion. */
internal class NativeMigrationDocuments(context: Context) {
    private val context=context.applicationContext
    private val directory=File(context.cacheDir,"migration-ui-v1").apply {check(mkdirs()||isDirectory);require(canonicalFile==File(context.cacheDir.canonicalFile,name));android.system.Os.chmod(path,0x1c0)}
    private val cancellations=ConcurrentHashMap<String,CancellationSignal>()
    private val descriptors=ConcurrentHashMap<String,ParcelFileDescriptor>()
    fun file(id: String,kind: String): File {
        require(UUID.fromString(id).toString()==id&&kind in setOf("input","export"))
        return File(directory,"migration-$kind-$id.codexmigration").also {require(it.canonicalFile==File(directory.canonicalFile,it.name))}
    }
    fun remove(file: File) {
        require(requireNotNull(file.parentFile).canonicalFile==directory.canonicalFile&&file.name.matches(Regex("migration-(input|export)-[a-f0-9-]{36}\\.codexmigration")))
        check(file.delete()||!file.exists())
    }
    fun cancel(id: String) {cancellations[id]?.cancel();runCatching {descriptors[id]?.close()}}
    private fun uri(value: Uri){require(value.scheme=="content"&&!value.authority.isNullOrBlank())}
    fun copyInput(id: String,source: Uri,current: ()->Unit): File {
        uri(source);val target=file(id,"input");check(!target.exists());val signal=CancellationSignal();check(cancellations.putIfAbsent(id,signal)==null)
        try {
            val descriptor=requireNotNull(context.contentResolver.openFileDescriptor(source,"r",signal));descriptors[id]=descriptor
            ParcelFileDescriptor.AutoCloseInputStream(descriptor).use {input->target.outputStream().use {output->
                var total=0L;val bytes=ByteArray(65536)
                while(true){current();val n=input.read(bytes);if(n<0)break;total+=n;require(total<=NativeMigrationCrypto.MAX_FILE_BYTES);output.write(bytes,0,n)}
                require(total>0);output.flush()
            }}
            android.system.Os.chmod(target.path,0x180);current();return target
        }catch(failure: Throwable){runCatching {remove(target)};throw failure}
        finally {descriptors.remove(id);cancellations.remove(id)}
    }
    fun saveOutput(id: String,source: File,destination: Uri,current: ()->Unit) {
        uri(destination);require(source==file(id,"export")&&source.isFile&&source.length() in 1..NativeMigrationCrypto.MAX_FILE_BYTES.toLong())
        val signal=CancellationSignal();check(cancellations.putIfAbsent(id,signal)==null)
        try {
            current();val descriptor=requireNotNull(context.contentResolver.openFileDescriptor(destination,"wt",signal));descriptors[id]=descriptor
            ParcelFileDescriptor.AutoCloseOutputStream(descriptor).use {output->source.inputStream().use {input->
                val bytes=ByteArray(65536);var total=0L
                while(true){current();val n=input.read(bytes);if(n<0)break;total+=n;require(total<=NativeMigrationCrypto.MAX_FILE_BYTES);output.write(bytes,0,n)}
                require(total==source.length());output.flush();current()
            }}
        }finally {descriptors.remove(id);cancellations.remove(id)}
    }
}
