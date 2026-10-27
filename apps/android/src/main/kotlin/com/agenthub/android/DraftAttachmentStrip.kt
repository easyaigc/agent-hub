package com.agenthub.android

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.text.format.Formatter
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

private val draftThumbnailWork=Semaphore(2)

/** Local draft display and explicit read-only preview; never an upload. */
@Composable internal fun DraftAttachmentStrip(session: MobileSession,interactive: Boolean) {
    val destination=session.attachmentDestination()
    key(destination.key,destination.selection) {
        var preview by remember { mutableStateOf<String?>(null) }
        val images=session.attachments.filter { it.manifest.mediaType.startsWith("image/") }
        val signature=images.map { it.manifest }
        val sources=remember(signature,destination) { images.map { file -> NativeAttachmentImage(file.manifest.id,file.manifest.name,load={ session.draftAttachmentImage(file,destination) },thumbnail={ session.draftAttachmentThumbnail(file,destination)?.let { BitmapFactory.decodeByteArray(it,0,it.size) } }) } }
        LaunchedEffect(signature) { if(preview!=null&&images.none { it.manifest.id==preview })preview=images.firstOrNull()?.manifest?.id }
        val opened=preview
        if(opened!=null&&sources.isNotEmpty())key(destination,opened) {
            NativeAttachmentImageViewer(sources,opened,{session.isCurrent(destination)&&preview==opened},close={if(preview==opened)preview=null}) { active,valid ->
                val original=images.firstOrNull { it.manifest.id==active.id }
                if(original!=null) androidx.compose.material3.TextButton(onClick={if(valid()&&preview==opened&&session.isCurrent(destination))session.removeAttachment(original.manifest.id,destination)},enabled=interactive&&session.canRemoveDraftAttachment,
                    modifier=Modifier.fillMaxWidth().heightIn(min=48.dp).testTag("draft-image-preview-remove")) { Text(productText("移除 %@",original.manifest.name),color=androidx.compose.ui.graphics.Color(0xFFFF6B6B),maxLines=2,overflow=TextOverflow.Ellipsis) }
            }
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal=16.dp),
            horizontalArrangement=Arrangement.spacedBy(8.dp),verticalAlignment=Alignment.CenterVertically) {
            session.attachments.forEach { file -> key(file.manifest.id,file.manifest.sha256) {
                DraftAttachment(session,file,destination,interactive) { preview=file.manifest.id }
            } }
        }
    }
}

@Composable private fun DraftAttachment(session: MobileSession,file: LocalDraftFile,destination: DraftDestination,interactive: Boolean,preview: ()->Unit) {
    val context=LocalContext.current
    val thumbnail by produceState<Bitmap?>(null,session,file,destination) {
        if(file.manifest.mediaType.startsWith("image/")) {
            value=try {
                draftThumbnailWork.withPermit {
                    val bytes=session.draftAttachmentThumbnail(file,destination)
                    if(bytes==null) null else withContext(Dispatchers.Default) { BitmapFactory.decodeByteArray(bytes,0,bytes.size) }
                }
            } catch(cancel: CancellationException) { throw cancel }
            catch(_: Exception) { null }
        }
    }
    val colors=MaterialTheme.colorScheme
    Row(Modifier.background(colors.surface,RoundedCornerShape(12.dp)).padding(start=8.dp).testTag("draft-attachment-${file.manifest.id}"),
        horizontalArrangement=Arrangement.spacedBy(6.dp),verticalAlignment=Alignment.CenterVertically) {
        val image=thumbnail
        if(image!=null) Image(image.asImageBitmap(),contentDescription=null,contentScale=ContentScale.Crop,
            modifier=Modifier.size(48.dp).clip(RoundedCornerShape(6.dp)).testTag("draft-attachment-image-${file.manifest.id}").clickable(onClick=preview))
        else ProductIcon("document",Modifier.size(48.dp).testTag("draft-attachment-document-${file.manifest.id}").then(if(file.manifest.mediaType.startsWith("image/"))Modifier.clickable(onClick=preview) else Modifier))
        Column {
            Text(file.manifest.name,fontSize=12.sp,maxLines=1,overflow=TextOverflow.Ellipsis)
            Text(Formatter.formatShortFileSize(context,file.manifest.sizeBytes),fontSize=11.sp,color=colors.onSurfaceVariant,
                modifier=Modifier.testTag("draft-attachment-size-${file.manifest.id}"))
        }
        IconAction("close",productText("移除 %@",file.manifest.name),"draft-attachment-remove-${file.manifest.id}",interactive&&session.canRemoveDraftAttachment) {
            session.removeAttachment(file.manifest.id,destination)
        }
    }
}
