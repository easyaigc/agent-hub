package com.agenthub.android

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
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

internal enum class NativeAttachmentAction { CAMERA, PHOTOS, FILES, PASTE, RECEIVED, RECENT, CLEAR }
internal class NativeAttachmentPanelAction(val original: NativePhotoRequest,val kind: NativeAttachmentAction,val photo: NativeRecentPhoto?=null)

/** Explicit local preparation with the same sections/order as iOS; system permissions remain native. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun NativeAttachmentPanel(session: MobileSession,original: NativePhotoRequest,close: ()->Unit,action: (NativeAttachmentPanelAction)->Unit) {
    val context=LocalContext.current;val colors=MaterialTheme.colorScheme
    val library=session.recentPhotos?.source ?: remember(context) { NativeRecentPhotos(context) }
    var revision by remember { mutableIntStateOf(0) }
    var access by remember { mutableStateOf(library.access()) }
    var rows by remember { mutableStateOf<List<NativeRecentPhoto>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var localPermissionPending by remember { mutableStateOf(false) }
    val authorization=session.photoAuthorization
    val permissionPending=localPermissionPending||authorization?.busy==true
    val tasks=rememberCoroutineScope()
    val staging=remember(original.id) {NativePhotoStaging(original)}
    var preview by remember(original.id) {mutableStateOf<NativeRecentPhoto?>(null)}
    var previewPhotos by remember(original.id) {mutableStateOf<List<NativeRecentPhoto>>(emptyList())}
    var browsing by remember(original.id) {mutableStateOf(false)}
    var mounted by remember(original.id) {mutableStateOf(true)}
    DisposableEffect(original.id) {onDispose {mounted=false}}
    fun current()=mounted&&session.isCurrent(original.destination.presentation)
    fun added(photo: NativeRecentPhoto)=session.attachments.any {it.photoSourceId==photo.id}&&staging.selected.none {it.id==photo.id}
    fun toggle(photo: NativeRecentPhoto)=staging.toggle(photo,added(photo),maxOf(0,5-session.attachments.size))
    fun addSelection() {if(!staging.saving)tasks.launch {if(staging.add(session,library,session.recentPhotos,::current)&&current())close()}}
    val lifecycle=LocalLifecycleOwner.current
    var active by remember(lifecycle) { mutableStateOf(lifecycle.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    val permission=rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { localPermissionPending=false;authorization?.complete();session.recentPhotos?.refresh();revision++ }
    suspend fun requestPermission(automatic: Boolean) {
        if(!active||!session.acceptsAttachmentAction(original)||permissionPending)return
        if(authorization!=null&&!authorization.claim(original.id,automatic))return
        if(!active||!session.acceptsAttachmentAction(original)) { authorization?.complete();return }
        if(authorization!=null) { authorization.requestLaunch(original.id);return }
        localPermissionPending=true
        try { permission.launch(library.permissions()) }
        catch(failure: Exception) { localPermissionPending=false;authorization?.complete() }
    }
    DisposableEffect(lifecycle,library) {
        val observer=LifecycleEventObserver { _,event ->
            if(event==Lifecycle.Event.ON_RESUME) { active=true;revision++ }
            else if(event==Lifecycle.Event.ON_PAUSE||event==Lifecycle.Event.ON_STOP)active=false
        }
        lifecycle.lifecycle.addObserver(observer)
        onDispose { lifecycle.lifecycle.removeObserver(observer) }
    }
    DisposableEffect(library,access,active) {
        val stop=if(active&&access!=NativePhotoAccess.NONE)runCatching { library.observe { revision++ } }.getOrNull() else null
        onDispose { stop?.invoke() }
    }
    // Release is observed in the original request, before any queued OS action runs.
    DisposableEffect(original.id) { onDispose { original.menuDismissed=true } }
    LaunchedEffect(revision,active) {
        access=library.access();rows=emptyList();failed=false
        if(!active||access==NativePhotoAccess.NONE) { loading=false;return@LaunchedEffect }
        loading=true
        try { delay(150);rows=library.snapshot() }
        catch(cancel: CancellationException) { throw cancel }
        catch(failure: Exception) { access=library.access();failed=true }
        finally { if(isActive)loading=false }
    }
    val enabled=active&&session.acceptsAttachmentAction(original)&&!permissionPending&&!session.attachmentRecoveryBusy&&!staging.saving
    fun choose(kind: NativeAttachmentAction,photo: NativeRecentPhoto?=null) {
        if(enabled)action(NativeAttachmentPanelAction(original,kind,photo))
    }
    val state=rememberModalBottomSheetState(skipPartiallyExpanded=true)
    ModalBottomSheet(onDismissRequest=close,sheetState=state,containerColor=colors.surface,contentWindowInsets={ WindowInsets.safeDrawing },
        properties=ModalBottomSheetProperties(isAppearanceLightStatusBars=colors.background!=androidx.compose.ui.graphics.Color.Black,isAppearanceLightNavigationBars=colors.background!=androidx.compose.ui.graphics.Color.Black)) {
        SheetSystemBars()
        // The sheet must own focus before a native permission window is requested.
        val focused=LocalWindowInfo.current.isWindowFocused
        LaunchedEffect(original.id,active,focused) {
            if(!active||!focused||authorization==null||library.access()!=NativePhotoAccess.NONE)return@LaunchedEffect
            withFrameNanos { }
            requestPermission(automatic=true)
        }
        Column(Modifier.fillMaxWidth().fillMaxHeight(.88f).testTag("attachment-panel")) {
            Row(Modifier.fillMaxWidth().padding(horizontal=16.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                Text(productText("添加附件"),Modifier.weight(1f).testTag("attachment-panel-title"),fontSize=17.sp,fontWeight=FontWeight.SemiBold)
                TextButton(onClick=close,modifier=Modifier.heightIn(min=48.dp).testTag("attachment-panel-close")) { Text(productText("关闭")) }
            }
            HorizontalDivider(color=colors.onSurface.copy(alpha=.06f))
            LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("attachment-panel-list"),contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                item {
                    if(session.recentPhotos?.preferencesFailed==true) {
                        Text(productText(NativeRecentPhotoLibrary.PREFERENCES_FAILURE),fontSize=13.sp,color=colors.onSurfaceVariant)
                        TextButton(onClick={ session.recentPhotos?.retryPreferences() },modifier=Modifier.testTag("recent-preferences-retry")) { Text(productText("重试")) }
                    }
                    Text(productText("最近图片"),fontSize=13.sp,color=colors.onSurfaceVariant,modifier=Modifier.padding(bottom=8.dp))
                    when {
                        access==NativePhotoAccess.NONE -> {
                            Text(productText("允许读取照片后可显示最近图片，也可以继续使用系统选图器。"),fontSize=13.sp,color=colors.onSurfaceVariant)
                            Column(Modifier.fillMaxWidth()) {
                                if(authorization==null||authorization.failed)TextButton(onClick={ tasks.launch { requestPermission(automatic=false) } },enabled=enabled,modifier=Modifier.testTag("recent-photo-permission")) { Text(productText(if(authorization?.failed==true)"重试" else "允许读取照片")) }
                                TextButton(onClick={ context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:"+context.packageName))) },enabled=!permissionPending,modifier=Modifier.testTag("recent-photo-settings")) { Text(productText("打开系统设置")) }
                            }
                        }
                        loading -> CircularProgressIndicator(Modifier.size(24.dp).testTag("recent-photos-loading"),strokeWidth=2.dp)
                        failed -> {
                            Text(productText("无法读取所选照片"),fontSize=13.sp,color=colors.onSurfaceVariant)
                            TextButton(onClick={ revision++ },modifier=Modifier.testTag("recent-photos-retry")) { Text(productText("重试")) }
                        }
                        rows.isEmpty() -> Text(productText("暂无可访问的图片"),fontSize=13.sp,color=colors.onSurfaceVariant,modifier=Modifier.testTag("recent-photos-empty"))
                        else -> LazyRow(Modifier.height(104.dp).testTag("recent-attachments-strip"),horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                            items(rows,key={ it.uri.toString() }) { photo ->
                                RecentPhotoThumbnail(library,photo,revision,enabled,staging.selected.indexOfFirst {it.id==photo.id}.takeIf {it>=0}?.plus(1),added(photo),preview={if(enabled) {previewPhotos=rows.toList();preview=photo}},select={if(enabled)toggle(photo)})
                            }
                        }
                    }
                    if(access==NativePhotoAccess.LIMITED)TextButton(onClick={ if(enabled)tasks.launch { requestPermission(automatic=false) } },enabled=enabled,modifier=Modifier.testTag("recent-photo-manage")) { Text(productText("管理可访问的照片")) }
                    Text(productText("添加后保存在手机，发送消息时再上传。"),fontSize=12.sp,color=colors.onSurfaceVariant,modifier=Modifier.padding(top=8.dp))
                }
                item {
                    Surface(shape=RoundedCornerShape(12.dp),color=colors.background) {
                        Column {
                            if(session.externalInbox?.available==true)AttachmentPanelRow("tray", "来自其他应用的附件","open-external-attachments",enabled) { choose(NativeAttachmentAction.RECEIVED) }
                            AttachmentPanelRow("camera","拍照","take-photo",enabled&&session.cameraCapture!=null) { choose(NativeAttachmentAction.CAMERA) }
                            AttachmentPanelRow("photo","选择图片","choose-photos",enabled&&(access!=NativePhotoAccess.NONE||session.photoPicker!=null&&session.photoPicker?.recovery?.canBegin!=false)) { if(access!=NativePhotoAccess.NONE)browsing=true else choose(NativeAttachmentAction.PHOTOS) }
                            if(access!=NativePhotoAccess.NONE)AttachmentPanelRow("photo","使用系统选图器","choose-system-photos",enabled&&session.photoPicker!=null&&session.photoPicker?.recovery?.canBegin!=false) {choose(NativeAttachmentAction.PHOTOS)}
                            AttachmentPanelRow("doc","选择文件","choose-files",enabled&&session.documentPicker!=null&&session.documentPicker?.recovery?.canBegin!=false) { choose(NativeAttachmentAction.FILES) }
                            AttachmentPanelRow("clipboard","粘贴图片或文件","paste-files",enabled) { choose(NativeAttachmentAction.PASTE) }
                        }
                    }
                    Text(productText("复制文件或图片后点粘贴；若来源应用未提供文件，请使用分享或用其他应用打开。"),fontSize=12.sp,color=colors.onSurfaceVariant,modifier=Modifier.padding(top=8.dp))
                    Text(productText("仅在你添加后保存在手机，点击发送才上传。"),fontSize=12.sp,color=colors.onSurfaceVariant,modifier=Modifier.padding(top=8.dp))
                }
                item {
                    Surface(shape=RoundedCornerShape(12.dp),color=colors.background) {
                        AttachmentPanelRow("trash","清空草稿","clear-composer-draft",enabled,destructive=true) { choose(NativeAttachmentAction.CLEAR) }
                    }
                }
            }
            PhotoSelectionFooter(staging,enabled,::addSelection)
        }
    }
    if(browsing)NativePhotoCatalogView(library,staging,enabled,::current,::added,::toggle,::addSelection,back={browsing=false},cancel=close,
        manage={tasks.launch {requestPermission(automatic=false)}},preview={photo,photos->if(enabled) {previewPhotos=photos.toList();preview=photo}})
    preview?.let {opened ->
        val photos=previewPhotos.ifEmpty {listOf(opened)}
        val name=productText("最近图片")
        val items=remember(photos,library,name) {photos.map {photo->NativeAttachmentImage(photo.id,photo.name ?: name,load={library.preview(photo)},thumbnail={library.thumbnail(photo)})}}
        NativeAttachmentImageViewer(items,opened.id,{current()&&preview===opened},close={if(preview===opened)preview=null}) {item,valid->
            val photo=photos.first {it.id==item.id};val order=staging.selected.indexOfFirst {it.id==photo.id}.takeIf {it>=0}?.plus(1)
            Column(Modifier.fillMaxWidth()) {
                TextButton(onClick={if(valid()&&enabled)toggle(photo)},enabled=enabled&&!added(photo),modifier=Modifier.fillMaxWidth().heightIn(min=48.dp).testTag("photo-preview-select")) {
                    Text(productText(if(added(photo))"已添加" else if(order!=null)"已选择" else "选择此图")+(order?.let {" ($it)"} ?: ""),color=androidx.compose.ui.graphics.Color.White)
                }
                PhotoSelectionFooter(staging,enabled,::addSelection,tag="photo-preview-add")
            }
        }
    }
}

@Composable internal fun PhotoSelectionFooter(staging: NativePhotoStaging,enabled: Boolean,add: ()->Unit,tag: String="photo-selection-add") {
    Column(Modifier.fillMaxWidth().padding(horizontal=16.dp)) {
        staging.failure?.let {Text(productText(it),color=MaterialTheme.colorScheme.error,modifier=Modifier.testTag("photo-selection-error").semantics {liveRegion=LiveRegionMode.Polite})}
        Button(onClick=add,enabled=enabled&&staging.selected.isNotEmpty()&&!staging.saving,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp).testTag(tag)) {
            Text(if(staging.saving)productText("正在准备文件…") else productText("添加")+" (${staging.selected.size})")
        }
    }
}

@Composable private fun AttachmentPanelRow(icon: String,key: String,tag: String,enabled: Boolean,destructive: Boolean=false,action: ()->Unit) {
    val color=if(destructive)MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
    Row(Modifier.fillMaxWidth().heightIn(min=48.dp).testTag(tag).semantics { role=Role.Button }.clickable(enabled=enabled,onClick=action).padding(horizontal=16.dp,vertical=12.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
        ProductIcon(icon,Modifier.testTag("$tag-icon"),color.copy(alpha=if(enabled)1f else .38f))
        Text(productText(key),fontSize=17.sp,color=color.copy(alpha=if(enabled)1f else .38f))
    }
}

@Composable internal fun RecentPhotoThumbnail(library: NativeRecentPhotoSource,photo: NativeRecentPhoto,revision: Int,enabled: Boolean,order: Int?,added: Boolean,preview: ()->Unit,select: ()->Unit,modifier: Modifier=Modifier.size(104.dp),tagPrefix: String="recent-photo") {
    val previewLabel=productText("预览图片");val selectionLabel=productText(if(added)"已添加" else if(order!=null)"已选择" else "选择此图")
    Box(modifier.clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.onSurface.copy(alpha=.06f))) {
        NativeRecentPhotoImage(library,photo,revision,Modifier.fillMaxSize().testTag(tagPrefix+"-"+photo.uri.lastPathSegment).semantics {contentDescription=photo.name ?: previewLabel;role=Role.Button}.clickable(enabled=enabled,onClick=preview))
        Box(Modifier.align(Alignment.TopEnd).size(48.dp).testTag(tagPrefix+"-select-"+photo.uri.lastPathSegment)
            .semantics {contentDescription=selectionLabel;if(order!=null)stateDescription=order.toString()}
            .toggleable(value=added||order!=null,enabled=enabled&&!added,role=Role.Checkbox,onValueChange={select()}),contentAlignment=Alignment.Center) {
            Box(Modifier.size(26.dp).background(if(added||order!=null)MaterialTheme.colorScheme.primary else androidx.compose.ui.graphics.Color.Black.copy(alpha=.45f),androidx.compose.foundation.shape.CircleShape)
                .border(1.dp,androidx.compose.ui.graphics.Color.White,androidx.compose.foundation.shape.CircleShape),contentAlignment=Alignment.Center) {
                Text(if(added)"✓" else order?.toString() ?: "",color=if(added||order!=null)MaterialTheme.colorScheme.onPrimary else androidx.compose.ui.graphics.Color.White,fontSize=13.sp)
            }
        }
    }
}
