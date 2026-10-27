package com.agenthub.android

import android.content.ClipData
import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream

/** Only these app-authored failures are localized; Provider diagnostics remain in the cause. */
internal class NativeFileIntakeFailure(val key: String,cause: Throwable?=null): Exception(key,cause) {
    companion object {
        const val UNSUPPORTED="剪贴板中没有可读取的文件或图片。请在来源应用中选择分享或用其他应用打开。"
        const val TOO_LARGE="附件为空或超过 20 MB，无法添加。"
        const val UNAVAILABLE="暂时无法读取共享文件，请稍后重试。"
        const val CHANGED="共享文件已变化或不可读取，请重新分享。"
    }
}

/** Explicit native clipboard/file boundary. Never coerce text, fetch URLs, upload or submit. */
internal object NativeFileIntake {
    const val MAXIMUM_BYTES=20*1024*1024
    fun clipboardFiles(clip: ClipData?): List<Uri> {
        if(clip==null||!com.agenthub.core.AttachmentPreparationRules.canReserve(0,0,clip.itemCount))throw NativeFileIntakeFailure(NativeFileIntakeFailure.UNSUPPORTED)
        // Copy exact source URIs now; async loaders never re-read a changed clipboard.
        return (0 until clip.itemCount).map { index ->
            val uri=clip.getItemAt(index).uri ?: throw NativeFileIntakeFailure(NativeFileIntakeFailure.UNSUPPORTED)
            Uri.parse(uri.toString()).also { requireContentFile(it) }
        }
    }
    /** Read only named Android file actions; connection URLs keep their existing route. */
    fun sharedFiles(intent: android.content.Intent): List<Uri>? {
        val action=intent.action
        if(action==android.content.Intent.ACTION_VIEW&&intent.data?.scheme!="content")return null
        if(action !in listOf(android.content.Intent.ACTION_SEND,android.content.Intent.ACTION_SEND_MULTIPLE,android.content.Intent.ACTION_VIEW))return null
        try {
            @Suppress("DEPRECATION")
            val sources: List<Uri> = when(action) {
                android.content.Intent.ACTION_VIEW -> listOfNotNull(intent.data)
                android.content.Intent.ACTION_SEND -> {
                    val value=if(android.os.Build.VERSION.SDK_INT>=33)intent.getParcelableExtra(android.content.Intent.EXTRA_STREAM,Uri::class.java)
                        else intent.getParcelableExtra<android.os.Parcelable>(android.content.Intent.EXTRA_STREAM)?.let { it as? Uri ?: throw NativeFileIntakeFailure(NativeFileIntakeFailure.CHANGED) }
                    if(value!=null)listOf(value)else clipboardFiles(intent.clipData)
                }
                else -> {
                    val values=if(android.os.Build.VERSION.SDK_INT>=33)intent.getParcelableArrayListExtra(android.content.Intent.EXTRA_STREAM,Uri::class.java)
                        else intent.getParcelableArrayListExtra<android.os.Parcelable>(android.content.Intent.EXTRA_STREAM)?.map { it as? Uri ?: throw NativeFileIntakeFailure(NativeFileIntakeFailure.CHANGED) }
                    values?.toList() ?: clipboardFiles(intent.clipData)
                }
            }
            require(com.agenthub.core.AttachmentPreparationRules.canReserve(0,0,sources.size))
            return sources.map { Uri.parse(it.toString()).also(::requireContentFile) }
        } catch(failure: Exception) { throw NativeFileIntakeFailure(NativeFileIntakeFailure.CHANGED,failure) }
    }
    private fun requireContentFile(uri: Uri) {
        if(uri.scheme!="content"||uri.authority.isNullOrBlank())throw NativeFileIntakeFailure(NativeFileIntakeFailure.UNSUPPORTED)
    }
    suspend fun read(resolver: ContentResolver,uri: Uri): LocalAttachmentInput = withContext(Dispatchers.IO) {
        requireContentFile(uri)
        try {
            currentCoroutineContext().ensureActive()
            var name: String?=null;var declaredSize: Long?=null
            resolver.query(uri,arrayOf(OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE),null,null,null)?.use { cursor ->
                if(cursor.moveToFirst()) {
                    val n=cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);if(n>=0&&!cursor.isNull(n))name=cursor.getString(n)
                    val s=cursor.getColumnIndex(OpenableColumns.SIZE);if(s>=0&&!cursor.isNull(s))cursor.getLong(s).takeIf { it>=0 }?.let { declaredSize=it }
                }
            }
            if(declaredSize?.let { it<=0||it>MAXIMUM_BYTES }==true)throw NativeFileIntakeFailure(NativeFileIntakeFailure.TOO_LARGE)
            val media=resolver.getType(uri)?.takeIf { it.isNotBlank() } ?: "application/octet-stream"
            val extension=MimeTypeMap.getSingleton().getExtensionFromMimeType(media)?.takeIf { it.matches(Regex("[A-Za-z0-9]{1,12}")) }
            val fallback="attachment"+(extension?.let { ".$it" } ?: "")
            val fileName=name?.substringAfterLast('/')?.substringAfterLast('\\')?.filter { it>=' '&&it!='\u007f' }?.trim()?.take(512)?.takeIf { it.isNotEmpty() } ?: fallback
            val bytes=resolver.openInputStream(uri)?.use { input ->
                val output=ByteArrayOutputStream();val buffer=ByteArray(16_384)
                while(true) {
                    currentCoroutineContext().ensureActive();val n=input.read(buffer);if(n<0)break
                    if(output.size()+n>MAXIMUM_BYTES)throw NativeFileIntakeFailure(NativeFileIntakeFailure.TOO_LARGE)
                    output.write(buffer,0,n)
                }
                output.toByteArray()
            } ?: throw NativeFileIntakeFailure(NativeFileIntakeFailure.UNAVAILABLE)
            currentCoroutineContext().ensureActive()
            if(bytes.isEmpty())throw NativeFileIntakeFailure(NativeFileIntakeFailure.TOO_LARGE)
            if(declaredSize?.let { it!=bytes.size.toLong() }==true)throw NativeFileIntakeFailure(NativeFileIntakeFailure.CHANGED)
            LocalAttachmentInput(fileName,media,bytes)
        } catch(cancel: CancellationException) { throw cancel }
          catch(failure: NativeFileIntakeFailure) { throw failure }
          catch(failure: Exception) { throw NativeFileIntakeFailure(NativeFileIntakeFailure.UNAVAILABLE,failure) }
          catch(failure: OutOfMemoryError) { throw NativeFileIntakeFailure(NativeFileIntakeFailure.UNAVAILABLE,failure) }
    }
}
