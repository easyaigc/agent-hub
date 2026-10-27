package com.agenthub.android

import androidx.compose.runtime.*
import kotlinx.coroutines.CancellationException
import java.util.UUID

/** One panel owns the ordered local selection. Only explicit Add prepares original bytes. */
internal class NativePhotoStaging(private val original: NativePhotoRequest) {
    val selected=mutableStateListOf<NativeRecentPhoto>()
    var saving by mutableStateOf(false);private set
    var failure by mutableStateOf<String?>(null);private set
    private val ids=mutableMapOf<String,String>()
    private val inputs=mutableMapOf<String,LocalAttachmentInput>()
    fun toggle(photo: NativeRecentPhoto,alreadyAdded: Boolean,remaining: Int) {
        if(saving||alreadyAdded)return
        if(selected.any {it.id==photo.id}) {selected.removeAll {it.id==photo.id};failure=null;return}
        if(selected.size>=remaining) {failure="最多添加 5 个附件，请先移除部分附件。";return}
        selected.add(photo);failure=null
    }
    suspend fun add(session: MobileSession,source: NativeRecentPhotoSource,library: NativeRecentPhotoLibrary?,current: ()->Boolean): Boolean {
        if(saving||selected.isEmpty()||!current()||!session.acceptsAttachmentAction(original))return false
        saving=true;failure=null
        val captured=selected.toList()
        try {
            val missing=captured.filter {photo->session.attachments.none {it.photoSourceId==photo.id}}
            if(session.attachments.size+missing.size>5) {failure="最多添加 5 个附件，请先移除部分附件。";return false}
            if(missing.isNotEmpty()) {
                val loaders=missing.map {photo->suspend {
                    inputs[photo.id] ?: source.input(photo).copy(fixedId=ids.getOrPut(photo.id) {UUID.randomUUID().toString()},photoSourceId=photo.id).also {inputs[photo.id]=it}
                }}
                val destination=original.destination.copy(requestId=UUID.randomUUID().toString())
                try {session.importAttachments(destination,loaders,original.errorGeneration,maximum=5,reportFailure=false)?.await()}
                catch(cancel: CancellationException) {throw cancel}
                catch(_: Exception) {failure="无法读取所选照片"}
            }
            if(!current()||!session.acceptsAttachmentAction(original))return false
            for(photo in captured) {
                val file=session.attachments.firstOrNull {it.photoSourceId==photo.id} ?: continue
                if(session.confirmPhotoFiles(original.destination,listOf(file))&&current()) {
                    selected.removeAll {it.id==photo.id};inputs.remove(photo.id);ids.remove(photo.id);library?.markImported(photo)
                }
            }
            if(selected.isNotEmpty())failure="无法读取所选照片"
            return selected.isEmpty()&&current()
        }catch(cancel: CancellationException) {throw cancel}
         catch(_: Exception) {if(current())failure="附件读取失败，原草稿已保留";return false}
        finally {saving=false}
    }
}
