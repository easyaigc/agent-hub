package com.agenthub.android

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.*
import java.io.ByteArrayInputStream
import kotlin.math.sqrt

/** Local read-only sources. No command, transport, or upload is exposed to the viewer. */
internal class NativeAttachmentImage(val id: String,val name: String,val load: suspend ()->Bitmap?,val thumbnail: suspend ()->Bitmap?={null})
internal object NativeAttachmentImageDecoder {
    suspend fun decode(bytes: ByteArray): Bitmap?=withContext(Dispatchers.Default) {
        require(bytes.size<=20*1024*1024)
        val bounds=BitmapFactory.Options().apply { inJustDecodeBounds=true }
        BitmapFactory.decodeByteArray(bytes,0,bytes.size,bounds)
        if(bounds.outWidth<=0||bounds.outHeight<=0)return@withContext null
        val ratio=maxOf(maxOf(bounds.outWidth,bounds.outHeight)/4096.0,sqrt(bounds.outWidth.toDouble()*bounds.outHeight/8_000_000.0))
        var sample=1;while(sample<ratio)sample*=2
        ensureActive()
        val image=BitmapFactory.decodeByteArray(bytes,0,bytes.size,BitmapFactory.Options().apply { inSampleSize=sample }) ?: return@withContext null
        val orientation=try { ExifInterface(ByteArrayInputStream(bytes)).getAttributeInt(ExifInterface.TAG_ORIENTATION,1) }catch(_: Exception) { 1 }
        NativeImageOrientation.apply(image,orientation)
    }
}

/** Only the settled page reads full pixels; page/window retirement cancels its original read. */
@Composable internal fun NativeAttachmentImageViewer(items: List<NativeAttachmentImage>,initialId: String,
    current: ()->Boolean,close: ()->Unit,footer: @Composable (NativeAttachmentImage,()->Boolean)->Unit={_,_->}) {
    var attached by remember { mutableStateOf(true) }
    DisposableEffect(Unit) { onDispose { attached=false } }
    fun alive()=attached&&current()
    if(items.isEmpty()) { LaunchedEffect(Unit) { if(alive())close() };return }
    val pager=rememberPagerState(initialPage=items.indexOfFirst { it.id==initialId }.coerceAtLeast(0),pageCount={items.size})
    val scope=rememberCoroutineScope();val t=currentProductText();val minis=rememberLazyListState()
    LaunchedEffect(pager.currentPage) { minis.animateScrollToItem(pager.currentPage) }
    val active=items[pager.currentPage.coerceIn(items.indices)]
    LaunchedEffect(items.map { it.id }) { if(pager.currentPage>=items.size)pager.scrollToPage(0) }
    Dialog(onDismissRequest={if(alive())close()},properties=DialogProperties(usePlatformDefaultWidth=false,decorFitsSystemWindows=false)) {
        Surface(Modifier.fillMaxSize().testTag("attachment-image-viewer"),color=Color.Black,contentColor=Color.White) {
            Column(Modifier.safeDrawingPadding()) {
                Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                    TextButton(onClick={if(alive())close()},modifier=Modifier.heightIn(min=48.dp).testTag("attachment-image-back")) { Text(t.tr("返回"),color=Color.White) }
                    Text("${pager.currentPage+1} / ${items.size}",Modifier.weight(1f).testTag("attachment-image-counter"))
                    TextButton(onClick={if(alive())close()},modifier=Modifier.heightIn(min=48.dp).testTag("attachment-image-done")) { Text(t.tr("完成"),color=Color.White) }
                }
                Text(active.name,Modifier.fillMaxWidth().padding(horizontal=16.dp).testTag("attachment-image-name"),maxLines=2,overflow=TextOverflow.Ellipsis)
                HorizontalPager(pager,Modifier.weight(1f).fillMaxWidth(),key={items[it].id}) { index ->
                    val item=items[index]
                    key(item.id) { NativeAttachmentImagePage(item,pager.settledPage==index&&pager.currentPage==index,
                        { alive()&&items.getOrNull(pager.currentPage)?.id==item.id }) }
                }
                Text(t.tr("左右滑动切换 · 双击或双指缩放"),Modifier.padding(horizontal=16.dp),color=Color.LightGray)
                LazyRow(state=minis,modifier=Modifier.fillMaxWidth().height(64.dp),horizontalArrangement=Arrangement.spacedBy(8.dp),contentPadding=PaddingValues(horizontal=16.dp)) {
                    itemsIndexed(items,key={_,item->item.id}) { index,item ->
                        val miniature by produceState<Bitmap?>(null,item) { try { value=item.thumbnail() }catch(cancel: CancellationException) {throw cancel}catch(_: Exception) {} }
                        Box(Modifier.size(48.dp).border(if(item.id==active.id)2.dp else 0.dp,Color.White).testTag("attachment-image-page-${item.id}")
                            .semantics {contentDescription=item.name;role=Role.Button}.clickable {if(alive())scope.launch { if(alive())pager.scrollToPage(index) }},contentAlignment=Alignment.Center) {
                            miniature?.let {Image(it.asImageBitmap(),null,Modifier.fillMaxSize(),contentScale=ContentScale.Crop)} ?: Text("${index+1}",color=Color.White)
                        }
                    }
                }
                footer(active) { alive()&&items.getOrNull(pager.currentPage)?.id==active.id }
            }
        }
    }
}

@Composable private fun NativeAttachmentImagePage(item: NativeAttachmentImage,active: Boolean,current: ()->Boolean) {
    val latestCurrent by rememberUpdatedState(current)
    val t=currentProductText()
    var revision by remember { mutableIntStateOf(0) }
    var image by remember { mutableStateOf<Bitmap?>(null) };var failed by remember { mutableStateOf(false) }
    var zoom by remember { mutableFloatStateOf(1f) };var offset by remember { mutableStateOf(Offset.Zero) }
    LaunchedEffect(active,revision) {
        image=null;failed=false;zoom=1f;offset=Offset.Zero
        if(active)try { val loaded=item.load();ensureActive();if(latestCurrent()) { image=loaded;failed=loaded==null } }
        catch(cancel: CancellationException) { throw cancel }
        catch(_: Exception) { if(latestCurrent())failed=true }
    }
    BoxWithConstraints(Modifier.fillMaxSize(),contentAlignment=Alignment.Center) {
        val pixels=constraints
        fun constrain(value: Offset)=Offset(value.x.coerceIn(-pixels.maxWidth*(zoom-1)/2,pixels.maxWidth*(zoom-1)/2),value.y.coerceIn(-pixels.maxHeight*(zoom-1)/2,pixels.maxHeight*(zoom-1)/2))
        fun scale(value: Float) { if(latestCurrent()) { zoom=value.coerceIn(1f,6f);offset=constrain(offset) } }
        val bitmap=image
        if(bitmap!=null) {
            var gesture=Modifier.pointerInput(item.id) { detectTapGestures(onDoubleTap={scale(if(zoom>1f)1f else 2f)}) }
            gesture=gesture.pointerInput(item.id,active) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed=false)
                    var transforming=false;var travelled=Offset.Zero
                    do {
                        val event=awaitPointerEvent()
                        travelled+=event.calculatePan()
                        if(event.changes.count {it.pressed}>1||zoom>1f&&travelled.getDistance()>viewConfiguration.touchSlop)transforming=true
                        if(transforming&&latestCurrent()) {
                            scale(zoom*event.calculateZoom());offset=constrain(offset+event.calculatePan())
                            event.changes.forEach {it.consume()}
                        }
                    }while(event.changes.any {it.pressed})
                }
            }
            Image(bitmap.asImageBitmap(),item.name,Modifier.fillMaxSize().then(gesture).graphicsLayer {scaleX=zoom;scaleY=zoom;translationX=offset.x;translationY=offset.y}.testTag("attachment-image-pixels"),contentScale=ContentScale.Fit)
            Row(Modifier.align(Alignment.BottomCenter),verticalAlignment=Alignment.CenterVertically) {
                TextButton(onClick={scale(zoom-.5f)},enabled=zoom>1f,modifier=Modifier.sizeIn(minWidth=48.dp,minHeight=48.dp).testTag("attachment-image-zoom-out").semantics {contentDescription=t.tr("缩小图片")}) { Text("−",color=Color.White) }
                Text("${(zoom*100).toInt()}%",Modifier.testTag("attachment-image-zoom"),color=Color.White)
                TextButton(onClick={scale(zoom+.5f)},enabled=zoom<6f,modifier=Modifier.sizeIn(minWidth=48.dp,minHeight=48.dp).testTag("attachment-image-zoom-in").semantics {contentDescription=t.tr("放大图片")}) { Text("+",color=Color.White) }
            }
        } else if(failed)Column(horizontalAlignment=Alignment.CenterHorizontally) {
            Text(productText("图片加载失败"),Modifier.testTag("attachment-image-error"),color=Color.White)
            TextButton(onClick={if(latestCurrent())revision++},modifier=Modifier.heightIn(min=48.dp).testTag("attachment-image-retry")) { Text(productText("重试"),color=Color.White) }
        } else if(active)CircularProgressIndicator(Modifier.testTag("attachment-image-loading"),color=Color.White)
    }
}
