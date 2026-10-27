package com.agenthub.android

import android.content.Intent
import android.net.Uri
import android.graphics.Typeface
import android.widget.TextView
import android.text.method.ScrollingMovementMethod
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import androidx.core.view.doOnLayout
import com.agenthub.core.FilePreviewRules
import kotlinx.coroutines.*
import org.commonmark.node.Node
import org.commonmark.node.Code
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.IndentedCodeBlock
import org.commonmark.node.Heading
import org.commonmark.node.Text as MarkdownText
import org.commonmark.ext.gfm.tables.TableBlock
import org.commonmark.ext.gfm.tables.TableHead
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest

/** New file types register a renderer; neither renderer nor document owns Host credentials. */
internal object FilePreviewRegistry {
    data class Renderer(val id: String,val source: Boolean,val outline: Boolean,val search: Boolean,val load: (File)->NativePreviewDocument)
    val renderers=listOf(Renderer("markdown",true,true,true,::loadPreviewDocument))
    fun find(name: String,mediaType: String)=renderers.firstOrNull { it.id==FilePreviewRules.renderer(name,mediaType) }
}

internal data class PreviewHeading(val title: String,val anchor: String,val block: Int,val level: Int)
internal data class NativePreviewDocument(val source: String,val renderSource: String,val digest: String,val blocks: List<Node>,val headings: List<PreviewHeading>,val searchable: List<String>)

internal fun loadPreviewDocument(file: File): NativePreviewDocument {
    val output=java.io.ByteArrayOutputStream();val buffer=ByteArray(16_384)
    file.inputStream().use { input -> while(true) { val count=input.read(buffer);if(count<0)break
        require(output.size()+count<=FilePreviewRules.maximumMarkdownBytes) { "文件较大，请使用其他应用打开或分享原文件。" };output.write(buffer,0,count) } }
    val bytes=output.toByteArray()
    val utf16=bytes.size>=2 && (bytes[0]==0xFF.toByte() && bytes[1]==0xFE.toByte() || bytes[0]==0xFE.toByte() && bytes[1]==0xFF.toByte())
    val source=try { (if(utf16) Charsets.UTF_16 else Charsets.UTF_8).newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
    } catch(error: java.nio.charset.CharacterCodingException) { throw IllegalArgumentException("暂不支持此文件的文本编码，请使用其他应用打开或分享原文件。",error) }
    require(!source.contains('\u0000')) { "暂不支持此文件的文本编码，请使用其他应用打开或分享原文件。" }
    val renderSource=source.removePrefix("\uFEFF")
    val document=NativeMarkdown.parse(renderSource)
    val pending=java.util.ArrayDeque<Pair<Node,Int>>();pending.add(document to 0);var count=0
    while(pending.isNotEmpty()) { val (node,depth)=pending.removeLast();count++
        require(count<=20_000 && depth<=64) { "文档结构较复杂，请使用其他应用打开或分享原文件。" }
        if(node is TableBlock) {
            val rows=NativeMarkdown.children(node).flatMap { NativeMarkdown.children(it) }
            require(rows.size<=1000 && rows.all { NativeMarkdown.children(it).size<=32 }) { "表格较大，请使用其他应用打开或分享原文件。" }
        }
        NativeMarkdown.children(node).forEach { pending.add(it to depth+1) }
    }
    val blocks=NativeMarkdown.children(document)
    val headings=mutableListOf<PreviewHeading>();val anchors=mutableMapOf<String,Int>()
    fun plain(node: Node): String = when(node) { is MarkdownText -> node.literal;is Code -> node.literal;is FencedCodeBlock -> node.literal;is IndentedCodeBlock -> node.literal;else -> NativeMarkdown.children(node).joinToString(" ") { plain(it) } }
    fun visit(node: Node,index: Int) {
        if(node is Heading) { val title=plain(node);val base=title.lowercase().filter { it.isLetterOrDigit() || it=='-' || it=='_' || it.isWhitespace() }.map { if(it.isWhitespace()) '-' else it }.joinToString("").ifEmpty { "section" }
            val duplicate=anchors[base]?:0;anchors[base]=duplicate+1
            headings.add(PreviewHeading(title,if(duplicate==0)base else "$base-$duplicate",index,node.level)) }
        NativeMarkdown.children(node).forEach { visit(it,index) }
    }
    blocks.forEachIndexed { index,node -> visit(node,index) }
    val digest=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    return NativePreviewDocument(source,renderSource,digest,blocks,headings,blocks.map(::plain))
}

private object PreviewReadingPositions {
    private val positions=linkedMapOf<String,Pair<Int,Int>>()
    fun get(key: String)=positions[key]?: (0 to 0)
    fun save(key: String,index: Int,offset: Int) { positions[key]=index to offset;if(positions.size>128)positions.remove(positions.keys.first()) }
}

private class PreviewSurfaceLease(var active: Boolean=true)
private class NativeSourcePosition(var offset: Int=0)

/** Receives one already authorized immutable copy; renderers never read Host state or credentials. */
@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun NativeMarkdownFilePreview(resource: MobileSession.PreparedMessageResource,current: ()->Boolean,onDismiss: ()->Unit,onRetry: (() -> Unit)?,refreshing: Boolean=false) {
    val context=LocalContext.current;val scope=rememberCoroutineScope();val t=currentProductText()
    val isCurrent by rememberUpdatedState(current)
    val lease=remember(resource) { PreviewSurfaceLease() }
    DisposableEffect(lease) { onDispose { lease.active=false } }
    fun alive()=lease.active&&runCatching { isCurrent() }.getOrDefault(false)
    val readingScope=remember(resource) { resource.local.canonicalPath }
    var failure by remember(resource) { mutableStateOf<String?>(null) }
    var document by remember(resource) { mutableStateOf<NativePreviewDocument?>(null) }
    var source by remember(resource) { mutableStateOf(false) };var outline by remember(resource) { mutableStateOf(false) }
    var searching by remember(resource) { mutableStateOf(false) };var query by remember(resource) { mutableStateOf("") }
    var matches by remember(resource) { mutableStateOf<List<Int>>(emptyList()) };var hit by remember(resource) { mutableIntStateOf(0) }
    var linkError by remember(resource) { mutableStateOf<String?>(null) }
    val clipboard=LocalClipboardManager.current
    val sourceColor=MaterialTheme.colorScheme.onBackground.toArgb()
    LaunchedEffect(resource) {
        try {
            if(!alive()) { failure="目标已变化，请重新打开预览。";return@LaunchedEffect }
            val loaded=withContext(Dispatchers.IO) { FilePreviewRegistry.find(resource.name,resource.mediaType)?.load?.invoke(resource.local) ?: error("Unsupported renderer") }
            currentCoroutineContext().ensureActive();if(alive())document=loaded
        } catch(cancel: CancellationException) { throw cancel }
          catch(error: Exception) {
            failure=error.message?.takeIf { it in setOf("文件较大，请使用其他应用打开或分享原文件。","暂不支持此文件的文本编码，请使用其他应用打开或分享原文件。","文档结构较复杂，请使用其他应用打开或分享原文件。","表格较大，请使用其他应用打开或分享原文件。") } ?: "暂时无法预览文件，请重试。"
        } catch(_: OutOfMemoryError) { failure="文档结构较复杂，请使用其他应用打开或分享原文件。" }
    }
    val readingKey=readingScope+":"+(document?.digest?:"loading")
    val saved=remember(readingKey) { PreviewReadingPositions.get(readingKey) }
    val list=remember(readingKey) { LazyListState(saved.first,saved.second) }
    val sourcePosition=remember(readingKey) { NativeSourcePosition() }
    val persistable=document!=null
    DisposableEffect(readingKey,list) { onDispose { if(persistable)PreviewReadingPositions.save(readingKey,list.firstVisibleItemIndex,list.firstVisibleItemScrollOffset) } }
    LaunchedEffect(query,document) {
        matches=emptyList();hit=0
        if(query.isEmpty())return@LaunchedEffect
        delay(120);val value=document?:return@LaunchedEffect
        val result=withContext(Dispatchers.Default) { value.searchable.mapIndexedNotNull { index,text -> index.takeIf { text.contains(query,ignoreCase=true) } } }
        if(alive()&&document===value) { matches=result;if(result.isNotEmpty()) { source=false;list.scrollToItem(result.first()) } }
    }
    fun externalFile(share: Boolean) {
        if(!alive())return
        try { NativeResourceIntents.launch(context,resource,share,t.tr("保存或分享文件")) }
        catch(_: android.content.ActivityNotFoundException) { linkError="手机上没有可打开此链接的应用。" }
        catch(_: SecurityException) { linkError="手机上没有可打开此链接的应用。" }
        catch(_: IllegalArgumentException) { linkError="手机上没有可打开此链接的应用。" }
    }
    val openLink: (String)->Unit=remember(document,list,lease) { { reference ->
        if(alive())when(FilePreviewRules.linkKind(reference)) {
            "anchor" -> {
                val anchor=Uri.decode(reference.drop(1));val value=document;val heading=value?.headings?.firstOrNull { it.anchor==anchor }
                if(heading!=null)scope.launch { if(alive()&&document===value) { source=false;list.scrollToItem(heading.block) } } else linkError="文档中没有找到此章节。"
            }
            "web","external" -> try {
                when(MessageLinkDestination.resolve(reference).kind) {
                    "web" -> androidx.browser.customtabs.CustomTabsIntent.Builder().setShowTitle(true).build().launchUrl(context,Uri.parse(reference))
                    "external" -> context.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(reference)).addCategory(Intent.CATEGORY_BROWSABLE))
                    else -> linkError="此引用尚未由电脑提供为可访问资源，可在源码中查看原链接。"
                }
            } catch(_: android.content.ActivityNotFoundException) { linkError="手机上没有可打开此链接的应用。" }
              catch(_: SecurityException) { linkError="手机上没有可打开此链接的应用。" }
              catch(_: IllegalArgumentException) { linkError="手机上没有可打开此链接的应用。" }
            else -> linkError="此引用尚未由电脑提供为可访问资源，可在源码中查看原链接。"
        }
    } }
    Dialog(onDismissRequest=onDismiss,properties=DialogProperties(usePlatformDefaultWidth=false,decorFitsSystemWindows=false)) {
        Surface(Modifier.fillMaxSize().safeDrawingPadding().testTag("file-preview-screen"),color=MaterialTheme.colorScheme.background) {
            Column {
                Row(Modifier.fillMaxWidth().padding(horizontal=8.dp),verticalAlignment=Alignment.CenterVertically) {
                    IconAction("close",t.tr("完成"),"file-preview-close",onClick=onDismiss)
                    Text(t.tr("预览"),Modifier.weight(1f),maxLines=1,overflow=TextOverflow.Ellipsis)
                    IconAction("search",t.tr("搜索"),"file-preview-search",document!=null&&alive()) { searching=!searching;if(!searching)query="" }
                    IconAction("doc",t.tr("目录"),"file-preview-outline",document?.headings?.isNotEmpty()==true&&alive()) { outline=true }
                    if(onRetry!=null)IconAction("refresh",t.tr("刷新"),"file-preview-refresh",alive()&&!refreshing) { if(alive()&&!refreshing)onRetry() }
                    IconAction("share",t.tr("保存或分享文件"),"file-preview-share",alive()) { externalFile(true) }
                }
                SelectionContainer {
                    Text(resource.name,Modifier.fillMaxWidth().heightIn(max=120.dp).verticalScroll(rememberScrollState()).padding(horizontal=16.dp,vertical=8.dp)
                        .testTag("file-preview-name"),fontSize=17.sp,fontWeight=androidx.compose.ui.text.font.FontWeight.SemiBold)
                }
                if(refreshing)Row(Modifier.fillMaxWidth().padding(horizontal=16.dp,vertical=8.dp).testTag("file-preview-refreshing")
                    .semantics {liveRegion=LiveRegionMode.Polite},verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    CircularProgressIndicator(Modifier.size(16.dp),strokeWidth=2.dp)
                    Text(t.tr("正在准备文件…"),fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
                }
                val value=document
                if(value!=null) {
                    FlowRow(Modifier.fillMaxWidth().padding(horizontal=16.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected=!source,onClick={if(alive())source=false},label={Text(t.tr("预览"))},modifier=Modifier.testTag("file-preview-rendered"))
                        FilterChip(selected=source,onClick={if(alive())source=true},label={Text(t.tr("源码"))},modifier=Modifier.testTag("file-preview-raw"))
                        if(source)IconAction("copy",t.tr("复制源码"),"file-preview-copy-source",alive()) { if(alive()&&document===value)clipboard.setText(AnnotatedString(value.source)) }
                    }
                    if(searching)Row(Modifier.fillMaxWidth().padding(horizontal=16.dp),verticalAlignment=Alignment.CenterVertically) {
                        OutlinedTextField(query,{if(alive())query=it},Modifier.weight(1f).testTag("file-preview-query"),singleLine=true,label={Text(t.tr("搜索文件"))})
                        Text("${if(matches.isEmpty())0 else hit+1}/${matches.size}",Modifier.padding(8.dp).testTag("file-preview-hit-count"),fontSize=12.sp)
                        IconAction("down",t.tr("下一项"),"file-preview-next-hit",matches.isNotEmpty()&&alive()) { if(alive()&&matches.isNotEmpty()) { hit=(hit+1)%matches.size;scope.launch { if(alive()) {source=false;list.scrollToItem(matches[hit])} } } }
                    }
                    HorizontalDivider()
                    if(source)AndroidView(factory={
                        val savedOffset=sourcePosition.offset
                        TextView(it).apply {
                            typeface=Typeface.MONOSPACE;textSize=16f;setTextIsSelectable(true);movementMethod=ScrollingMovementMethod.getInstance();setPadding(32,24,32,24)
                            doOnLayout {view->if(view.isAttachedToWindow&&alive()&&document===value)view.scrollTo(0,savedOffset)}
                        }
                    },onReset=null,onRelease={view->sourcePosition.offset=view.scrollY},
                        update={if(it.text.toString()!=value.source)it.text=value.source;it.setTextColor(sourceColor)},modifier=Modifier.weight(1f).fillMaxWidth().testTag("file-preview-source"))
                    else CompositionLocalProvider(LocalMarkdownLinkHandler provides openLink) {
                        SelectionContainer(Modifier.weight(1f).fillMaxWidth()) {
                            LazyColumn(state=list,contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(16.dp),modifier=Modifier.testTag("file-preview-content")) {
                                itemsIndexed(value.blocks) { index,node ->
                                    Column(Modifier.fillMaxWidth().background(if(index in matches)MaterialTheme.colorScheme.primary.copy(alpha=.1f) else androidx.compose.ui.graphics.Color.Transparent).testTag("file-preview-block-$index")) {
                                        if(node is TableBlock)PreviewNativeTable(node,index) else MarkdownBlock(node,"file-preview-native-$index",value.renderSource)
                                    }
                                }
                            }
                        }
                    }
                } else if(failure!=null)Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),verticalArrangement=Arrangement.Center,horizontalAlignment=Alignment.CenterHorizontally) {
                    Text(t.tr(failure!!),Modifier.testTag("file-preview-error"))
                    if(onRetry!=null)TextButton(onClick={if(alive()&&!refreshing)onRetry()},enabled=alive()&&!refreshing,modifier=Modifier.heightIn(min=48.dp).testTag("file-preview-retry")) {Text(t.tr("重试"))}
                    TextButton(onClick={externalFile(false)},enabled=alive(),modifier=Modifier.heightIn(min=48.dp).testTag("file-preview-open-with")) {Text(t.tr("用其他应用打开"))}
                } else Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center) {CircularProgressIndicator(Modifier.testTag("file-preview-loading"))}
            }
        }
        if(outline)AlertDialog(onDismissRequest={outline=false},title={Text(t.tr("目录"))},text={
            LazyColumn {items(document?.headings.orEmpty()) { heading ->
                TextButton(onClick={if(alive()) {outline=false;source=false;scope.launch {if(alive())list.scrollToItem(heading.block)}}},modifier=Modifier.padding(start=((heading.level-1)*8).dp).testTag("file-preview-heading-${heading.anchor}")) {Text(heading.title)}
            }}
        },confirmButton={TextButton(onClick={outline=false}){Text(t.tr("完成"))}})
        if(linkError!=null)AlertDialog(modifier=Modifier.testTag("file-preview-link-error"),onDismissRequest={linkError=null},title={Text(t.tr("无法打开链接"))},text={Text(t.tr(linkError!!))},confirmButton={TextButton(onClick={linkError=null}){Text(t.tr("好"))}})
    }
}

@Composable private fun PreviewNativeTable(table: TableBlock,index: Int) {
    val colors=MaterialTheme.colorScheme;val handler=LocalMarkdownLinkHandler.current
    val imageLabel=productText("图片：");val linkedImageLabel=productText("查看图片：")
    val rows=remember(table) { NativeMarkdown.children(table).flatMap { NativeMarkdown.children(it) } }
    val cells=remember(table,handler,imageLabel,linkedImageLabel) { rows.map { row -> NativeMarkdown.children(row).map { NativeMarkdown.inline(it,androidx.compose.ui.graphics.Color(0xFF007AFF),handler?.let { open -> { reference: String,_: String -> open(reference) } },imageLabel=imageLabel,linkedImageLabel=linkedImageLabel) } } }
    val widths=remember(cells) { (0..<(cells.maxOfOrNull {it.size}?:0)).map { column ->
        ((cells.take(100).maxOfOrNull {it.getOrNull(column)?.length?:0}?:0)*7).coerceIn(120,300).dp } }
    Column(Modifier.testTag("file-preview-table-$index"),verticalArrangement=Arrangement.spacedBy(5.dp)) {
        Text(productText("左右滑动查看完整表格"),fontSize=12.sp,color=colors.onSurfaceVariant)
        Column(Modifier.horizontalScroll(rememberScrollState()).testTag("file-preview-table-scroll-$index")) {
            rows.forEachIndexed { rowIndex,row ->
                Row(Modifier.background(if(row.parent is TableHead)colors.surface else colors.background)) {
                    cells[rowIndex].forEachIndexed { column,cell -> Text(cell,Modifier.width(widths[column]).padding(10.dp),fontSize=15.sp,fontWeight=if(row.parent is TableHead)androidx.compose.ui.text.font.FontWeight.SemiBold else androidx.compose.ui.text.font.FontWeight.Normal) }
                }
                HorizontalDivider()
            }
        }
    }
}
