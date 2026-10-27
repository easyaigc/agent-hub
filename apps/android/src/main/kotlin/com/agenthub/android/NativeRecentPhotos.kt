package com.agenthub.android

import android.Manifest
import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Size
import kotlinx.coroutines.*

internal data class NativeRecentPhoto(val uri: Uri,val addedAtSeconds: Double?=null,val name: String?=null,val sortAddedSeconds: Long?=addedAtSeconds?.toLong()) {
    val id get()=uri.toString()
    val cursor get()=NativePhotoCursor(sortAddedSeconds,requireNotNull(uri.lastPathSegment?.toLongOrNull()))
}
internal interface NativeRecentPhotoSource {
    fun access(): NativePhotoAccess
    fun permissions(): Array<String>
    fun observe(changed: ()->Unit): ()->Unit
    suspend fun snapshot(limit: Int=30): List<NativeRecentPhoto>
    suspend fun page(after: NativePhotoCursor?,limit: Int=60): NativePhotoPage {
        check(after==null) {"Photo catalogue paging is unavailable for this source"}
        val values=snapshot(limit)
        return NativePhotoPage(values,if(values.size==limit)values.last().cursor else null)
    }
    suspend fun thumbnail(photo: NativeRecentPhoto): Bitmap?
    suspend fun preview(photo: NativeRecentPhoto): Bitmap?=thumbnail(photo)
    suspend fun input(photo: NativeRecentPhoto): LocalAttachmentInput
}
internal enum class NativePhotoAccess { NONE, LIMITED, FULL }

/** Photo-library metadata/thumbnails only. Original image bytes are read after an explicit tap. */
internal class NativeRecentPhotos(context: Context): NativeRecentPhotoSource {
    private val context=context.applicationContext
    private val resolver=this.context.contentResolver
    override fun access(): NativePhotoAccess = when {
        context.checkSelfPermission(if(Build.VERSION.SDK_INT>=33)Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE)==PackageManager.PERMISSION_GRANTED -> NativePhotoAccess.FULL
        Build.VERSION.SDK_INT>=34&&context.checkSelfPermission(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)==PackageManager.PERMISSION_GRANTED -> NativePhotoAccess.LIMITED
        else -> NativePhotoAccess.NONE
    }
    override fun permissions(): Array<String> = when {
        Build.VERSION.SDK_INT>=34 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES,Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        Build.VERSION.SDK_INT>=33 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES)
        else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }
    override fun observe(changed: ()->Unit): ()->Unit {
        val observer=object: ContentObserver(Handler(Looper.getMainLooper())) { override fun onChange(selfChange: Boolean) { changed() } }
        resolver.registerContentObserver(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,true,observer)
        return { resolver.unregisterContentObserver(observer) }
    }
    override suspend fun snapshot(limit: Int): List<NativeRecentPhoto> = page(null,limit).photos
    override suspend fun page(after: NativePhotoCursor?,limit: Int): NativePhotoPage {
        require(limit in 1..64)
        if(access()==NativePhotoAccess.NONE)return NativePhotoPage(emptyList(),null)
        val result=cancellableRead { signal ->
            val rows=mutableListOf<NativeRecentPhoto>()
            val selection=NativePhotoQuery.selection(after,Build.VERSION.SDK_INT)
            val query=Bundle().apply {
                putString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER,NativePhotoQuery.ORDER)
                putInt(ContentResolver.QUERY_ARG_LIMIT,limit+1)
                selection.first?.let {putString(ContentResolver.QUERY_ARG_SQL_SELECTION,it)}
                if(selection.second.isNotEmpty())putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS,selection.second)
            }
            resolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,arrayOf(MediaStore.Images.Media._ID,MediaStore.Images.Media.DATE_ADDED,MediaStore.Images.Media.DISPLAY_NAME),query,signal)?.use { cursor ->
                val column=cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val added=cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
                val name=cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                while(rows.size<limit+1&&cursor.moveToNext()) {
                    signal.throwIfCanceled();val rawAdded=if(cursor.isNull(added))null else cursor.getLong(added)
                    rows.add(NativeRecentPhoto(ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,cursor.getLong(column)),
                        rawAdded?.takeIf {it>0}?.toDouble(),if(cursor.isNull(name))null else cursor.getString(name),rawAdded))
                }
            }
            val visible=rows.take(limit)
            NativePhotoPage(visible,if(rows.size>limit)visible.last().cursor else null)
        }
        return if(access()==NativePhotoAccess.NONE)NativePhotoPage(emptyList(),null) else result
    }
    override suspend fun thumbnail(photo: NativeRecentPhoto): Bitmap? {
        if(access()==NativePhotoAccess.NONE)return null
        requireOwnedMediaUri(photo)
        return cancellableRead { signal ->
            val image=if(Build.VERSION.SDK_INT>=29)resolver.loadThumbnail(photo.uri,Size(THUMBNAIL_EDGE,THUMBNAIL_EDGE),signal)
                else @Suppress("DEPRECATION") MediaStore.Images.Thumbnails.getThumbnail(resolver,ContentUris.parseId(photo.uri),MediaStore.Images.Thumbnails.MINI_KIND,null)
            if(image==null)null else {
                val scale=minOf(1f,THUMBNAIL_EDGE.toFloat()/maxOf(image.width,image.height))
                if(scale>=1f)image else Bitmap.createScaledBitmap(image,maxOf(1,(image.width*scale).toInt()),maxOf(1,(image.height*scale).toInt()),true).also { image.recycle() }
            }
        }
    }
    override suspend fun preview(photo: NativeRecentPhoto): Bitmap? {
        requireOwnedMediaUri(photo)
        if(access()==NativePhotoAccess.NONE)return null
        val bytes=withContext(Dispatchers.IO) {
            resolver.openInputStream(photo.uri)?.use { stream ->
                val out=java.io.ByteArrayOutputStream();val buffer=ByteArray(32*1024)
                while(true) { ensureActive();val n=stream.read(buffer);if(n<0)break;require(out.size()+n<=20*1024*1024);out.write(buffer,0,n) }
                out.toByteArray()
            }
        } ?: return null
        val image=NativeAttachmentImageDecoder.decode(bytes)
        return if(access()!=NativePhotoAccess.NONE)image else null
    }
    override suspend fun input(photo: NativeRecentPhoto): LocalAttachmentInput {
        requireOwnedMediaUri(photo)
        if(access()==NativePhotoAccess.NONE)throw NativeFileIntakeFailure(NativePhotoIntake.UNREADABLE)
        return NativePhotoIntake.read(resolver,photo.uri)
    }
    private fun requireOwnedMediaUri(photo: NativeRecentPhoto) {
        require(photo.uri.scheme=="content"&&photo.uri.authority=="media"&&photo.uri.pathSegments.size==4&&
            photo.uri.pathSegments.take(3)==listOf("external","images","media")&&photo.uri.lastPathSegment?.toLongOrNull()?.let { it>=0 }==true)
    }
    private suspend fun <T> cancellableRead(read: (CancellationSignal)->T): T = coroutineScope {
        suspendCancellableCoroutine { continuation ->
            val signal=CancellationSignal()
            val job=launch(Dispatchers.IO) {
                try {
                    val result=read(signal)
                    continuation.resumeWith(Result.success(result))
                }catch(failure: Exception) { continuation.resumeWith(Result.failure(failure)) }
            }
            continuation.invokeOnCancellation { signal.cancel();job.cancel() }
        }
    }
    companion object { const val MAXIMUM_PHOTOS=30;const val THUMBNAIL_EDGE=180 }
}

/** Native query boundary; shared with SQLite integration checks, not a remote command. */
internal object NativePhotoQuery {
    fun selection(after: NativePhotoCursor?,sdk: Int): Pair<String?,Array<String>> {
        val filter=mutableListOf<String>();val arguments=mutableListOf<String>()
        if(sdk>=29)filter.add(MediaStore.Images.Media.IS_PENDING+" = 0")
        if(sdk>=30)filter.add(MediaStore.Images.Media.IS_TRASHED+" = 0")
        if(after!=null) {
            if(after.addedSeconds==null) {
                filter.add("("+MediaStore.Images.Media.DATE_ADDED+" IS NULL AND "+MediaStore.Images.Media._ID+" < ?)")
                arguments.add(after.id.toString())
            } else {
                filter.add("("+MediaStore.Images.Media.DATE_ADDED+" < ? OR "+MediaStore.Images.Media.DATE_ADDED+" IS NULL OR ("+MediaStore.Images.Media.DATE_ADDED+" = ? AND "+MediaStore.Images.Media._ID+" < ?))")
                arguments.addAll(listOf(after.addedSeconds.toString(),after.addedSeconds.toString(),after.id.toString()))
            }
        }
        return filter.takeIf {it.isNotEmpty()}?.joinToString(" AND ") to arguments.toTypedArray()
    }
    const val ORDER="date_added DESC, _id DESC"
}
