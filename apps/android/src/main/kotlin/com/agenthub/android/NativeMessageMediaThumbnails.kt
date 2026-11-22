package com.agenthub.android

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.runtime.mutableStateMapOf
import java.io.File
import java.util.ArrayDeque
import kotlinx.coroutines.*

/** Only explicitly opened, already authenticated images enter this cache. No network access. */
internal object NativeMessageMediaThumbnails {
    const val maximumEntries=8
    const val maximumEdge=640
    // Decode is serialized to bound transient memory too. A source may be malformed/huge.
    @Synchronized fun decode(file: File): Bitmap? {
        val bounds=BitmapFactory.Options().apply { inJustDecodeBounds=true }
        BitmapFactory.decodeFile(file.path,bounds)
        val sample=sample(bounds.outWidth,bounds.outHeight)?:return null
        val bitmap=BitmapFactory.decodeFile(file.path,BitmapFactory.Options().apply { inSampleSize=sample;inPreferredConfig=Bitmap.Config.ARGB_8888 })?:return null
        val orientation=runCatching { androidx.exifinterface.media.ExifInterface(file).getAttributeInt(androidx.exifinterface.media.ExifInterface.TAG_ORIENTATION,1) }.getOrDefault(1)
        val result=NativeImageOrientation.apply(bitmap,orientation)
        if(result!==bitmap)bitmap.recycle()
        return result
    }
    /** Optional thumbnail failure never changes an already opened preview. Caller fences exact owner/view. */
    suspend fun <T> publishOpened(decode: ()->T?,current: ()->Boolean,publish: (T)->Unit) {
        if(!current())return
        val thumbnail=try { withContext(Dispatchers.IO) { decode() } }
            catch(cancel: CancellationException) { throw cancel }
            catch(_: Exception) { null } catch(_: OutOfMemoryError) { null }
        currentCoroutineContext().ensureActive()
        if(thumbnail!=null&&current())publish(thumbnail)
    }
    internal fun sample(width: Int,height: Int): Int? {
        if(width !in 1..32768||height !in 1..32768||width.toLong()*height>100_000_000L)return null
        var sample=1
        while((maxOf(width,height).toLong()+sample-1)/sample>maximumEdge)sample*=2
        return sample
    }
 }

/** Per-message-owner cache; disposal/switching target releases all references. */
internal class NativeMessageMediaSnapshots {
    val images=mutableStateMapOf<String,Bitmap>()
    // SnapshotStateMap iteration order is unspecified. Track last publication order separately.
    private val publicationOrder=ArrayDeque<String>()
    fun put(reference: String,bitmap: Bitmap) {
        publicationOrder.remove(reference)
        while(publicationOrder.size>=NativeMessageMediaThumbnails.maximumEntries)images.remove(publicationOrder.removeFirst())
        publicationOrder.addLast(reference)
        images[reference]=bitmap
        // Do not recycle replaced/evicted bitmaps: a composed frame may still reference them.
    }
}
internal val LocalNativeMessageMediaSnapshots=androidx.compose.runtime.staticCompositionLocalOf<NativeMessageMediaSnapshots?> { null }
