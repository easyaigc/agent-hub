package com.agenthub.android

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.core.content.FileProvider
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.*
import java.io.File
import java.nio.charset.CodingErrorAction

class ResourceFileProvider: FileProvider()

internal object NativeResourceIntents {
    fun launch(context: Context,resource: MobileSession.PreparedMessageResource,share: Boolean,title: String) {
        val intent=view(context,resource,share)
        context.startActivity(if(share)Intent.createChooser(intent,title).apply {
            clipData=intent.clipData;addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } else intent)
    }
    fun view(context: Context,resource: MobileSession.PreparedMessageResource,share: Boolean): Intent {
        val file=resource.local.canonicalFile
        val root=File(context.cacheDir,"resources").canonicalFile
        require(file.isFile&&file.path.startsWith(root.path+File.separator))
        val uri=FileProvider.getUriForFile(context,context.packageName+".resources",file,resource.name)
        return Intent(if(share)Intent.ACTION_SEND else Intent.ACTION_VIEW).apply {
            type=resource.mediaType
            if(share)putExtra(Intent.EXTRA_STREAM,uri) else setDataAndType(uri,resource.mediaType)
            clipData=ClipData.newUri(context.contentResolver,resource.name,uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
}
private data class ResourceDisplay(val text: String?=null,val image: Bitmap?=null)
private fun display(resource: MobileSession.PreparedMessageResource): ResourceDisplay {
    val file=resource.local
    if(resource.mediaType.startsWith("image/")) {
        val bounds=BitmapFactory.Options().apply { inJustDecodeBounds=true }
        BitmapFactory.decodeFile(file.path,bounds)
        if(bounds.outWidth !in 1..32768||bounds.outHeight !in 1..32768||bounds.outWidth.toLong()*bounds.outHeight>100_000_000L)return ResourceDisplay()
        var sample=1
        while(maxOf(bounds.outWidth,bounds.outHeight)/sample>2048)sample*=2
        val bitmap=BitmapFactory.decodeFile(file.path,BitmapFactory.Options().apply { inSampleSize=sample }) ?: return ResourceDisplay()
        val orientation=runCatching { androidx.exifinterface.media.ExifInterface(file).getAttributeInt(androidx.exifinterface.media.ExifInterface.TAG_ORIENTATION,1) }.getOrDefault(1)
        val oriented=NativeImageOrientation.apply(bitmap,orientation)
        if(oriented!==bitmap)bitmap.recycle()
        return ResourceDisplay(image=oriented)
    }
    if((resource.mediaType.startsWith("text/")||resource.mediaType in listOf("application/json","application/xml"))&&file.length()<=512*1024) {
        val decoder=Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        return ResourceDisplay(text=file.inputStream().use { java.io.InputStreamReader(it,decoder).readText() })
    }
    return ResourceDisplay()
}

/** Native read-only preview. Unsupported/large formats retain explicit system open/share. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun NativeResourcePreview(resource: MobileSession.PreparedMessageResource,close: ()->Unit,current: ()->Boolean,retry: (() -> Unit)?=null,refreshing: Boolean=false) {
    if(FilePreviewRegistry.find(resource.name,resource.mediaType)!=null) {
        key(resource) { NativeMarkdownFilePreview(resource,current,close,retry,refreshing) };return
    }
    val context=LocalContext.current
    val shareTitle=productText("保存或分享文件")
    var systemFailure by remember(resource) { mutableStateOf(false) }
    val state=rememberModalBottomSheetState(skipPartiallyExpanded=true)
    val content by produceState<ResourceDisplay?>(null,resource) {
        val result=withContext(Dispatchers.IO) {
            try { display(resource) } catch(cancel: CancellationException) { throw cancel }
            catch(_: Exception) { ResourceDisplay() } catch(_: OutOfMemoryError) { ResourceDisplay() }
        }
        withContext(Dispatchers.Main.immediate) { value=result }
    }
    fun external(share: Boolean) {
        if(!current())return
        try {
            NativeResourceIntents.launch(context,resource,share,shareTitle)
        } catch(_: android.content.ActivityNotFoundException) { systemFailure=true }
          catch(_: SecurityException) { systemFailure=true }
          catch(_: IllegalArgumentException) { systemFailure=true }
    }
    ModalBottomSheet(onDismissRequest=close,sheetState=state,containerColor=MaterialTheme.colorScheme.surface,
        contentWindowInsets={ WindowInsets.safeDrawing }) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(.9f).testTag("resource-preview")) {
            Row(Modifier.fillMaxWidth().padding(horizontal=8.dp),horizontalArrangement=Arrangement.SpaceBetween) {
                IconAction("close",productText("关闭"),"resource-preview-close",onClick=close)
                IconAction("share",shareTitle,"resource-preview-share") { external(true) }
            }
            SelectionContainer {
                Text(resource.name,Modifier.fillMaxWidth().heightIn(max=120.dp).verticalScroll(rememberScrollState()).padding(horizontal=16.dp,vertical=8.dp)
                    .testTag("resource-preview-name"),fontSize=17.sp,fontWeight=FontWeight.SemiBold)
            }
            HorizontalDivider()
            val value=content
            when {
                value==null -> Box(Modifier.fillMaxWidth().weight(1f),contentAlignment=Alignment.Center) { CircularProgressIndicator(Modifier.testTag("resource-preview-loading")) }
                value.text!=null -> SelectionContainer(Modifier.weight(1f).fillMaxWidth()) {
                    Text(value.text,Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp).testTag("resource-preview-text"),fontSize=16.sp,lineHeight=24.sp)
                }
                value.image!=null -> {
                    var scale by remember(resource) { mutableFloatStateOf(1f) }
                    var x by remember(resource) { mutableFloatStateOf(0f) };var y by remember(resource) { mutableFloatStateOf(0f) }
                    Box(Modifier.fillMaxWidth().weight(1f).clipToBounds().pointerInput(resource) {
                        detectTransformGestures { _,pan,zoom,_->scale=(scale*zoom).coerceIn(1f,6f);val dx=size.width*(scale-1f)/2;val dy=size.height*(scale-1f)/2;x=(x+pan.x).coerceIn(-dx,dx);y=(y+pan.y).coerceIn(-dy,dy) }
                    }) { Image(value.image.asImageBitmap(),resource.name,Modifier.fillMaxSize().graphicsLayer { scaleX=scale;scaleY=scale;translationX=x;translationY=y }.testTag("resource-preview-image"),contentScale=ContentScale.Fit) }
                }
                else -> Box(Modifier.fillMaxWidth().weight(1f).padding(24.dp),contentAlignment=Alignment.Center) {
                    Text(productText("此文件无法直接预览，可保存或使用其他应用打开。"),Modifier.testTag("resource-preview-external-format"))
                }
            }
            TextButton(onClick={ external(false) },modifier=Modifier.fillMaxWidth().heightIn(min=48.dp).testTag("resource-preview-open-with")) { Text(productText("用其他应用打开")) }
        }
    }
    if(systemFailure)AlertDialog(onDismissRequest={ systemFailure=false },title={ Text(productText("无法打开链接")) },
        text={ Text(productText("手机上没有可打开此链接的应用。")) },confirmButton={ TextButton(onClick={ systemFailure=false }) { Text(productText("好")) } })
}
