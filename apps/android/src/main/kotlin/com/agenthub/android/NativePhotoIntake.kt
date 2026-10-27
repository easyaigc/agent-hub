package com.agenthub.android

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.*
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/** User-selected photos follow iOS local PNG/JPEG preparation; ordinary files stay byte-exact. */
internal object NativePhotoIntake {
    const val UNREADABLE="无法读取这张图片，请选择可预览的静态图片。"
    const val MAXIMUM_PHOTOS=5
    suspend fun read(resolver: ContentResolver,uri: Uri): LocalAttachmentInput {
        val input=try { NativeFileIntake.read(resolver,uri) }catch(error: NativeFileIntakeFailure) {
            if(error.key==NativeFileIntakeFailure.TOO_LARGE)throw error
            throw NativeFileIntakeFailure(UNREADABLE,error)
        }
        return normalize(input)
    }
    suspend fun normalize(input: LocalAttachmentInput): LocalAttachmentInput = withContext(Dispatchers.IO) {
        var decoded: Bitmap?=null;var oriented: Bitmap?=null
        try {
            currentCoroutineContext().ensureActive()
            if(input.bytes.isEmpty()||input.bytes.size>NativeFileIntake.MAXIMUM_BYTES)throw NativeFileIntakeFailure(NativeFileIntakeFailure.TOO_LARGE)
            if(!input.mediaType.startsWith("image/"))throw NativeFileIntakeFailure(UNREADABLE)
            val bounds=BitmapFactory.Options().apply { inJustDecodeBounds=true };BitmapFactory.decodeByteArray(input.bytes,0,input.bytes.size,bounds)
            if(bounds.outWidth<=0||bounds.outHeight<=0||bounds.outWidth.toLong()*bounds.outHeight>100_000_000)throw NativeFileIntakeFailure(UNREADABLE)
            decoded=BitmapFactory.decodeByteArray(input.bytes,0,input.bytes.size) ?: throw NativeFileIntakeFailure(UNREADABLE)
            val orientation=ByteArrayInputStream(input.bytes).use { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION,ExifInterface.ORIENTATION_NORMAL) }
            oriented=NativeImageOrientation.apply(decoded,orientation)
            currentCoroutineContext().ensureActive()
            val png=input.name.substringAfterLast('.',"").equals("png",ignoreCase=true)
            val output=object: ByteArrayOutputStream() {
                override fun write(bytes: ByteArray,offset: Int,length: Int) {
                    if(length>NativeFileIntake.MAXIMUM_BYTES-size())throw NativeFileIntakeFailure(NativeFileIntakeFailure.TOO_LARGE)
                    super.write(bytes,offset,length)
                }
                override fun write(value: Int) {
                    if(size()>=NativeFileIntake.MAXIMUM_BYTES)throw NativeFileIntakeFailure(NativeFileIntakeFailure.TOO_LARGE)
                    super.write(value)
                }
            }
            if(!oriented.compress(if(png)Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG,92,output))throw NativeFileIntakeFailure(UNREADABLE)
            if(output.size()>NativeFileIntake.MAXIMUM_BYTES)throw NativeFileIntakeFailure(NativeFileIntakeFailure.TOO_LARGE)
            currentCoroutineContext().ensureActive()
            val base=input.name.substringBeforeLast('.',input.name).ifBlank { "image" }.take(508)
            LocalAttachmentInput(base+if(png)".png"else".jpg",if(png)"image/png"else"image/jpeg",output.toByteArray())
        }catch(cancel: CancellationException) { throw cancel }
         catch(error: NativeFileIntakeFailure) { throw error }
         catch(error: Exception) { throw NativeFileIntakeFailure(UNREADABLE,error) }
         catch(error: OutOfMemoryError) { throw NativeFileIntakeFailure(UNREADABLE,error) }
        finally { if(oriented!==decoded)oriented?.recycle();decoded?.recycle() }
    }
}
