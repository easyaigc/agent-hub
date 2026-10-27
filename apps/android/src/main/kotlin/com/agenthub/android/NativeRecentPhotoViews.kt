package com.agenthub.android

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.*

@Composable internal fun NativeRecentPhotoLifecycle(library: NativeRecentPhotoLibrary?) {
    if(library==null)return
    val lifecycle=LocalLifecycleOwner.current
    DisposableEffect(library,lifecycle) {
        library.setActive(lifecycle.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
        val observer=LifecycleEventObserver { _,event ->
            if(event==Lifecycle.Event.ON_RESUME) { library.setActive(true);library.refresh() }
            else if(event==Lifecycle.Event.ON_PAUSE||event==Lifecycle.Event.ON_STOP)library.setActive(false)
        }
        lifecycle.lifecycle.addObserver(observer)
        onDispose { lifecycle.lifecycle.removeObserver(observer);library.setActive(false) }
    }
}

@Composable internal fun NativeRecentPhotoImage(source: NativeRecentPhotoSource,photo: NativeRecentPhoto,revision: Int,modifier: Modifier,scale: ContentScale=ContentScale.Crop) {
    val image by produceState<android.graphics.Bitmap?>(null,photo,revision) {
        value=null
        try { value=source.thumbnail(photo) }
        catch(cancel: CancellationException) { throw cancel }
        catch(failure: Exception) { /* No original bytes are read for a placeholder. */ }
    }
    Box(modifier.clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.onSurface.copy(alpha=.06f)),contentAlignment=Alignment.Center) {
        image?.let { Image(it.asImageBitmap(),null,Modifier.fillMaxSize(),contentScale=scale) } ?: ProductIcon("photo",color=MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private class NativeRecentPhotoAction(val hint: NativeRecentPhotoHint,val original: NativePhotoRequest)

@Composable internal fun NativeRecentPhotoHintView(session: MobileSession,library: NativeRecentPhotoLibrary,previewChanged: (Boolean)->Unit) {
    val hint=library.suggestion ?: return
    val surface=remember(session,session.targetKey,session.suggestionNavigation) { java.util.UUID.randomUUID().toString() }
    var preview by remember(surface) { mutableStateOf<NativeRecentPhotoAction?>(null) }
    var queued by remember(surface) { mutableStateOf<NativeRecentPhotoAction?>(null) }
    val text=currentProductText();val focus=LocalFocusManager.current;val keyboard=LocalSoftwareKeyboardController.current
    val focused=LocalWindowInfo.current.isWindowFocused
    DisposableEffect(library,surface) { library.claimSurface(surface);onDispose { library.releaseSurface(surface);previewChanged(false) } }
    SideEffect { previewChanged(preview!=null||queued!=null) }
    fun valid(action: NativeRecentPhotoAction)=library.current(action.hint,surface)&&session.acceptsAttachmentAction(action.original)&&
        session.documentPicker?.pending==null&&session.photoPicker?.pending==null&&session.cameraCapture?.pending==null
    fun capture(): NativeRecentPhotoAction? {
        if(queued!=null||!library.current(hint,surface)||!session.canCompose||session.importing)return null
        return NativeRecentPhotoAction(hint,NativePhotoRequest(session.attachmentImportDestination(),session.attachmentErrorGeneration,false))
    }
    fun add(original: NativeRecentPhotoAction?) {
        if(original==null||!valid(original)||queued!=null)return
        preview=null;queued=original
    }
    LaunchedEffect(library.suggestion,session.targetKey,session.suggestionNavigation) {
        preview?.let { if(!valid(it))preview=null }
    }
    val next=queued
    LaunchedEffect(next,focused) {
        if(next==null||!focused)return@LaunchedEffect
        withFrameNanos { }
        if(queued!==next)return@LaunchedEffect
        queued=null
        if(!valid(next))return@LaunchedEffect
        val destination=next.original.destination
        val before=session.inboxFiles(destination).map { it.id }.toSet()
        val job=session.importAttachments(destination,listOf({
            try { library.source.input(next.hint.photo).copy(photoSourceId=next.hint.photo.id) }
            catch(failure: NativeFileIntakeFailure) { throw IllegalStateException(text.tr(failure.key),failure) }
        }),errorAtStart=next.original.errorGeneration)
        if(job!=null)library.observeOriginalImport(job,session,destination,before,next.hint.photo)
    }
    val enabled=!session.importing&&!session.localRecoveryRequired&&queued==null
    Row(Modifier.fillMaxWidth().padding(horizontal=12.dp).floatingProduct(16f).padding(8.dp).testTag("recent-attachment-hint"),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        val label=productText("预览最近图片")
        Box(Modifier.testTag("recent-attachment-preview-open").semantics { contentDescription=label;role=Role.Button }.clickable(enabled=enabled) {
            capture()?.let { focus.clearFocus(force=true);keyboard?.hide();preview=it }
        }) { NativeRecentPhotoImage(library.source,hint.photo,library.revision,Modifier.size(48.dp,52.dp)) }
        Column(Modifier.weight(1f)) {
            Text(productText("要添加这张图片吗？"),fontSize=15.sp)
            Text(productText("最近新增的图片"),fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Button(onClick={ add(capture()) },enabled=enabled,modifier=Modifier.heightIn(min=48.dp).testTag("recent-attachment-add")) { Text(productText("添加"),fontSize=17.sp) }
        IconAction("close",productText("忽略这张图片"),"recent-attachment-dismiss",enabled) { library.dismiss(hint,surface) }
    }
    preview?.let { original -> RecentPhotoPreview(library,original.hint.photo,enabled&&valid(original),current={valid(original)&&preview===original},close={ if(preview===original)preview=null },add={ add(original) }) }
}

@Composable private fun RecentPhotoPreview(library: NativeRecentPhotoLibrary,photo: NativeRecentPhoto,enabled: Boolean,current: ()->Boolean,close: ()->Unit,add: ()->Unit) {
    val name=photo.name ?: productText("最近图片")
    val items=remember(library,photo,name) { listOf(NativeAttachmentImage(photo.id,name,load={library.source.preview(photo)},thumbnail={library.source.thumbnail(photo)})) }
    val currentEnabled by rememberUpdatedState(enabled)
    NativeAttachmentImageViewer(items,photo.id,current,close) { _,valid ->
        Column(Modifier.fillMaxWidth().testTag("recent-attachment-preview")) {
            Text(productText("添加后保存在手机，发送消息时再上传。"),Modifier.padding(horizontal=16.dp),color=androidx.compose.ui.graphics.Color.LightGray)
            Button(onClick={if(valid()&&currentEnabled)add()},enabled=enabled,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp).testTag("recent-attachment-preview-add")) { Text(productText("添加到当前草稿")) }
        }
    }
}
