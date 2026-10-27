package com.agenthub.android

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.*
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.*

internal data class NativePhotoCursor(val addedSeconds: Long?,val id: Long) {init {require(id>=0)}}
internal data class NativePhotoPage(val photos: List<NativeRecentPhoto>,val next: NativePhotoCursor?)

/** Keyset pages, one read at a time. A new catalogue retires the exact old read. */
internal class NativePhotoCatalog(private val source: NativeRecentPhotoSource,private val scope: CoroutineScope) {
    var photos by mutableStateOf<List<NativeRecentPhoto>>(emptyList());private set
    var next by mutableStateOf<NativePhotoCursor?>(null);private set
    var loading by mutableStateOf(false);private set
    var failed by mutableStateOf(false);private set
    var finished by mutableStateOf(false);private set
    var mediaRevision by mutableIntStateOf(0);private set
    private var revision=0L;private var job: Job?=null
    fun refresh() {mediaRevision++;revision++;job?.cancel();photos=emptyList();next=null;failed=false;finished=false;loading=false;loadMore()}
    fun loadMore(retry: Boolean=false) {
        if(loading||finished||failed&&!retry)return
        val original=revision;val cursor=next;loading=true;failed=false
        job=scope.launch {
            try {
                val page=source.page(cursor)
                ensureActive();if(original!=revision)return@launch
                require(page.photos.size<=64&&page.photos.map {it.id}.distinct().size==page.photos.size)
                require(page.next==null||page.photos.isNotEmpty()&&page.next!=cursor&&page.next==page.photos.last().cursor)
                val existing=photos.map {it.id}.toSet();val added=page.photos.filter {it.id !in existing}
                require(page.next==null||added.isNotEmpty())
                photos=photos+added;next=page.next;finished=page.next==null
            }catch(cancel: CancellationException) {throw cancel}
             catch(_: Exception) {if(original==revision)failed=true}
            finally {if(original==revision)loading=false}
        }
    }
    fun close() {revision++;job?.cancel();job=null;loading=false}
}

@Composable internal fun NativePhotoCatalogView(source: NativeRecentPhotoSource,staging: NativePhotoStaging,enabled: Boolean,current: ()->Boolean,
    added: (NativeRecentPhoto)->Boolean,toggle: (NativeRecentPhoto)->Unit,add: ()->Unit,back: ()->Unit,cancel: ()->Unit,manage: ()->Unit,
    preview: (NativeRecentPhoto,List<NativeRecentPhoto>)->Unit) {
    val scope=rememberCoroutineScope();val catalog=remember(source) {NativePhotoCatalog(source,scope)}
    val lifecycle=LocalLifecycleOwner.current
    var active by remember {mutableStateOf(lifecycle.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))}
    var access by remember {mutableStateOf(source.access())}
    var attached by remember(source,lifecycle) {mutableStateOf(true)}
    fun valid()=attached&&active&&current()
    DisposableEffect(source,lifecycle) {
        fun refresh() {if(active) {access=source.access();catalog.refresh()}}
        val observer=LifecycleEventObserver {_,event->
            if(event==Lifecycle.Event.ON_RESUME) {active=true;refresh()}
            else if(event==Lifecycle.Event.ON_PAUSE||event==Lifecycle.Event.ON_STOP) {active=false;catalog.close()}
        }
        val stop=runCatching {source.observe {scope.launch {refresh()}}}.getOrNull()
        lifecycle.lifecycle.addObserver(observer)
        onDispose {attached=false;runCatching {stop?.invoke()};lifecycle.lifecycle.removeObserver(observer);catalog.close()}
    }
    Dialog(onDismissRequest={if(valid())back()},properties=DialogProperties(usePlatformDefaultWidth=false,decorFitsSystemWindows=false)) {
        Surface(Modifier.fillMaxSize().testTag("attachment-photo-catalog"),color=MaterialTheme.colorScheme.background) {
            Column(Modifier.safeDrawingPadding()) {
                Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                    TextButton(onClick={if(valid())back()},modifier=Modifier.heightIn(min=48.dp).testTag("photo-catalog-back")){Text(productText("返回"))}
                    Text(productText("选择图片"),Modifier.weight(1f))
                    TextButton(onClick={if(valid())cancel()},modifier=Modifier.heightIn(min=48.dp).testTag("photo-catalog-cancel")){Text(productText("取消"))}
                }
                BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                    val columns=((maxWidth.value-12)/108).toInt().coerceIn(1,3)
                    LazyVerticalGrid(GridCells.Fixed(columns),Modifier.fillMaxSize().testTag("attachment-photo-grid"),contentPadding=PaddingValues(3.dp),horizontalArrangement=Arrangement.spacedBy(3.dp),verticalArrangement=Arrangement.spacedBy(3.dp)) {
                        items(catalog.photos,key={it.id}) {photo->
                            RecentPhotoThumbnail(source,photo,catalog.mediaRevision,enabled&&valid(),staging.selected.indexOfFirst {it.id==photo.id}.takeIf {it>=0}?.plus(1),added(photo),
                                preview={if(valid()&&enabled)preview(photo,catalog.photos)},select={if(valid()&&enabled)toggle(photo)},modifier=Modifier.fillMaxWidth().aspectRatio(1f),tagPrefix="catalog-photo")
                        }
                        item(span={GridItemSpan(maxLineSpan)}) {
                            Column(Modifier.fillMaxWidth(),horizontalAlignment=Alignment.CenterHorizontally) {
                                when {
                                    catalog.loading->CircularProgressIndicator(Modifier.padding(16.dp).testTag("photo-catalog-loading"))
                                    catalog.failed->Unit
                                    !catalog.finished->{LaunchedEffect(catalog.next) {if(valid())catalog.loadMore()};TextButton(onClick={if(valid())catalog.loadMore()},modifier=Modifier.heightIn(min=48.dp).testTag("photo-catalog-more")){Text(productText("加载更多"))}}
                                    catalog.photos.isEmpty()->Text(productText("暂无可访问的图片"),Modifier.padding(16.dp).testTag("photo-catalog-empty"))
                                }
                            }
                        }
                    }
                }
                if(catalog.failed)Row(Modifier.fillMaxWidth().padding(horizontal=12.dp,vertical=8.dp).testTag("photo-catalog-failure-bar"),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    Text(productText("无法读取图片目录，请重试。"),Modifier.weight(1f),color=MaterialTheme.colorScheme.error,maxLines=2,overflow=TextOverflow.Ellipsis)
                    TextButton(onClick={if(valid())catalog.loadMore(retry=true)},modifier=Modifier.heightIn(min=48.dp).testTag("photo-catalog-retry")){Text(productText("重试"))}
                }
                if(access==NativePhotoAccess.LIMITED)TextButton(onClick={if(valid()&&enabled)manage()},modifier=Modifier.heightIn(min=48.dp).testTag("photo-catalog-manage")){Text(productText("管理可访问的照片"))}
                PhotoSelectionFooter(staging,enabled&&valid(),{if(valid())add()},tag="photo-catalog-add")
            }
        }
    }
}
