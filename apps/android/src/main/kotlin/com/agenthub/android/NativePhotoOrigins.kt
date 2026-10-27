package com.agenthub.android

import android.net.Uri
import org.json.JSONObject

/** Local MediaStore selection hints only. Never part of a Host manifest or command fingerprint.
 * A future cross-device migration activation must drop these device-local hints. */
internal object NativePhotoOrigins {
    fun valid(value: String): Boolean {
        if(value.length>512)return false
        val uri=Uri.parse(value)
        return uri.scheme=="content"&&uri.authority=="media"&&uri.query==null&&uri.fragment==null&&
            uri.pathSegments.size==4&&uri.pathSegments.take(3)==listOf("external","images","media")&&
            uri.lastPathSegment?.toLongOrNull()?.let {it>=0&&uri.toString()=="content://media/external/images/media/$it"}==true
    }
    fun encode(files: List<LocalDraftFile>)=JSONObject().apply {
        files.forEach {file->file.photoSourceId?.let {source->require(file.manifest.mediaType.startsWith("image/")&&valid(source));put(file.manifest.id,source)}}
    }
    fun attach(file: LocalDraftFile,origins: JSONObject?): LocalDraftFile {
        if(origins==null||!origins.has(file.manifest.id))return file
        val source=origins.get(file.manifest.id) as String
        require(file.manifest.mediaType.startsWith("image/")&&valid(source))
        return file.copy(photoSourceId=source)
    }
    fun validate(origins: JSONObject?,files: List<LocalDraftFile>) {
        if(origins==null)return
        require(origins.length()<=10_000)
        origins.keys().forEach {id->val file=files.firstOrNull {it.manifest.id==id} ?: error("Unknown local photo origin");attach(file,origins)}
    }
}
