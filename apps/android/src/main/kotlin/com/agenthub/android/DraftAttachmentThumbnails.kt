package com.agenthub.android

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import kotlinx.coroutines.CancellationException
import org.json.JSONObject
import java.security.MessageDigest

/** Optional installation-local display cache. It never supplies submission bytes or authority. */
internal object DraftAttachmentThumbnails {
    private const val EDGE=128
    private const val MAX_PNG=96*1024
    const val MAX_LEGACY_SOURCE=1024*1024L
    private fun key(file: CreationFile)="draft-thumbnail-v1:${file.id}"
    fun discardPrepared(storage: NativeSessionStorage,file: CreationFile) {
        val raw=storage.read(key(file)) ?: return
        require(JSONObject(raw).getJSONObject("file").toString()==file.body().toString())
        storage.remove(key(file))
    }
    private fun sha(bytes: ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    data class Cached(val found: Boolean,val png: ByteArray?)
    fun read(storage: NativeSessionStorage,file: CreationFile): Cached = try {
        val raw=storage.read(key(file))
        if(raw==null||raw.length>160*1024) Cached(false,null) else {
            Cached(true,validate(JSONObject(raw),file))
        }
    } catch(cancel: CancellationException) { throw cancel }
      catch(_: Exception) { Cached(false,null) }

    /** The archive and ordinary preview share the same bounded, authenticated manifest checks. */
    internal fun validate(value: JSONObject,file: CreationFile): ByteArray? {
        require(value.toString().length<=160*1024&&value.keys().asSequence().toSet()==setOf("version","edge","file","png","thumbnailSha256"))
        require(value.get("version")==1&&value.get("edge")==EDGE&&value.getJSONObject("file").toString()==file.body().toString())
        if(value.isNull("png")){require(value.isNull("thumbnailSha256"));return null}
        val encoded=value.get("png") as String;val bytes=Base64.decode(encoded,Base64.NO_WRAP)
        require(bytes.size in 1..MAX_PNG&&Base64.encodeToString(bytes,Base64.NO_WRAP)==encoded&&sha(bytes)==value.get("thumbnailSha256"))
        require(bytes.take(8)==listOf(137,80,78,71,13,10,26,10).map{it.toByte()})
        val bounds=BitmapFactory.Options().apply {inJustDecodeBounds=true}
        BitmapFactory.decodeByteArray(bytes,0,bytes.size,bounds)
        require(bounds.outWidth in 1..EDGE&&bounds.outHeight in 1..EDGE)
        return bytes
    }

    fun storeIfMissing(storage: NativeSessionStorage,file: CreationFile,source: ByteArray) {
        if(!file.mediaType.startsWith("image/"))return
        try {
            if(read(storage,file).found)return
            require(source.size.toLong()==file.sizeBytes&&sha(source)==file.sha256)
            val png=make(source)
            val value=JSONObject().put("version",1).put("edge",EDGE).put("file",file.body())
                .put("png",png?.let { Base64.encodeToString(it,Base64.NO_WRAP) } ?: JSONObject.NULL)
                .put("thumbnailSha256",png?.let(::sha) ?: JSONObject.NULL)
            storage.write(key(file),value.toString())
        } catch(cancel: CancellationException) { throw cancel }
          catch(_: Exception) { /* A failed optional cache cannot discard a valid original attachment. */ }
    }
    private fun make(source: ByteArray): ByteArray? {
        val bounds=BitmapFactory.Options().apply { inJustDecodeBounds=true }
        BitmapFactory.decodeByteArray(source,0,source.size,bounds)
        if(bounds.outWidth !in 1..32768||bounds.outHeight !in 1..32768||bounds.outWidth.toLong()*bounds.outHeight>100_000_000L)return null
        var sample=1
        while(maxOf(bounds.outWidth,bounds.outHeight)/sample>EDGE)sample*=2
        val image=BitmapFactory.decodeByteArray(source,0,source.size,BitmapFactory.Options().apply { inSampleSize=sample }) ?: return null
        val oriented=oriented(image,source)
        return try {
            java.io.ByteArrayOutputStream().also { oriented.compress(Bitmap.CompressFormat.PNG,100,it) }.toByteArray().takeIf { it.size in 1..MAX_PNG }
        }finally{if(oriented!==image)oriented.recycle();image.recycle()}
    }
    private fun oriented(image: Bitmap,source: ByteArray): Bitmap {
        val orientation=try {
            java.io.ByteArrayInputStream(source).use { androidx.exifinterface.media.ExifInterface(it).getAttributeInt(androidx.exifinterface.media.ExifInterface.TAG_ORIENTATION,1) }
        }catch(_: Exception){1}
        return NativeImageOrientation.apply(image,orientation)
    }
}
