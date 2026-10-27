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
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
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
    fun find(file: MobileFile)=renderers.firstOrNull { it.id==FilePreviewRules.renderer(file.name,file.mediaType) }
}

internal data class PreviewHeading(val title: String,val anchor: String,val block: Int,val level: Int)
internal data class NativePreviewDocument(val source: String,val digest: String,val blocks: List<Node>,val headings: List<PreviewHeading>,val searchable: List<String>)

internal fun loadPreviewDocument(file: File): NativePreviewDocument {
    val output=java.io.ByteArrayOutputStream();val buffer=ByteArray(16_384)
    file.inputStream().use { input -> while(true) { val count=input.read(buffer);if(count<0)break
        require(output.size()+count<=FilePreviewRules.maximumMarkdownBytes) { "文件较大，请使用其他应用打开或分享原文件。" };output.write(buffer,0,count) } }
    val bytes=output.toByteArray()
    val utf16=bytes.size>=2 && (bytes[0]==0xFF.toByte() && bytes[1]==0xFE.toByte() || bytes[0]==0xFE.toByte() && bytes[1]==0xFF.toByte())
    val source=(if(utf16) Charsets.UTF_16 else Charsets.UTF_8).newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
    require(!source.contains('\u0000')) { "暂不支持此文件的文本编码，请使用其他应用打开或分享原文件。" }
    val document=NativeMarkdown.parse(source)
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
    return NativePreviewDocument(source,digest,blocks,headings,blocks.map(::plain))
}

private object PreviewReadingPositions {
    private val positions=linkedMapOf<String,Pair<Int,Int>>()
    fun get(key: String)=positions[key]?: (0 to 0)
    fun save(key: String,index: Int,offset: Int) { positions[key]=index to offset;if(positions.size>128)positions.remove(positions.keys.first()) }
}

@Composable internal fun RemoteFilePreview(session: MobileSession,file: MobileFile,onDismiss: () -> Unit) {
    val context=LocalContext.current;val scope=rememberCoroutineScope()
    val target=session.targetKey
    var local by remember(target,file) { mutableStateOf<File?>(null) }
    var failure by remember(target,file) { mutableStateOf<String?>(null) }
    var document by remember(target,file) { mutableStateOf<NativePreviewDocument?>(null) }
    var source by remember { mutableStateOf(false) };var outline by remember { mutableStateOf(false) }
    var searching by remember { mutableStateOf(false) };var query by remember { mutableStateOf("") }
    var matches by remember { mutableStateOf<List<Int>>(emptyList()) };var hit by remember { mutableIntStateOf(0) }
    var linkError by remember { mutableStateOf<String?>(null) }
    val clipboard=LocalClipboardManager.current
    val sourceColor=MaterialTheme.colorScheme.onBackground.toArgb()
    LaunchedEffect(target,file) {
        try {
            val cache=File(context.cacheDir,"previews").apply { mkdirs() }
            val downloaded=session.downloadResource(file,cache)
            currentCoroutineContext().ensureActive();if(target!=session.targetKey)return@LaunchedEffect
            local=downloaded
            FilePreviewRegistry.find(file)?.let { renderer -> document=withContext(Dispatchers.Default) { renderer.load(downloaded) } }
        } catch(cancel: CancellationException) { throw cancel } catch(error: Exception) { failure=error.message?:"暂时无法预览文件" }
    }
    val readingKey=target+":"+file.id+":"+(document?.digest?:"")
    val saved=remember(readingKey) { PreviewReadingPositions.get(readingKey) }
    val list=remember(readingKey) { LazyListState(saved.first,saved.second) }
    DisposableEffect(readingKey,list) { onDispose { PreviewReadingPositions.save(readingKey,list.firstVisibleItemIndex,list.firstVisibleItemScrollOffset) } }
    LaunchedEffect(query,document) {
        if(query.isEmpty()) { matches=emptyList();return@LaunchedEffect }
        delay(120)
        val value=document?:return@LaunchedEffect
        matches=withContext(Dispatchers.Default) { value.searchable.mapIndexedNotNull { index,text -> index.takeIf { text.contains(query,ignoreCase=true) } } }
        hit=0;if(matches.isNotEmpty()) { source=false;list.scrollToItem(matches.first()) }
    }
    fun externalFile(share: Boolean) {
        val selected=local?:return
        try {
            val uri=FileProvider.getUriForFile(context,context.packageName+".preview-files",selected)
            val intent=Intent(if(share) Intent.ACTION_SEND else Intent.ACTION_VIEW).apply {
                if(share) { type=file.mediaType;putExtra(Intent.EXTRA_STREAM,uri) } else setDataAndType(uri,file.mediaType)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                clipData=android.content.ClipData.newRawUri(file.name,uri)
            }
            context.startActivity(Intent.createChooser(intent,if(share)"保存或分享文件" else "用其他应用打开"))
        } catch(_: Exception) { linkError="手机上没有可打开此文件的应用，可保存后在其他设备查看。" }
    }
    val openLink: (String)->Unit = remember(document,list) { { reference ->
        when(FilePreviewRules.linkKind(reference)) {
            "anchor" -> { val anchor=Uri.decode(reference.drop(1));val heading=document?.headings?.firstOrNull { it.anchor==anchor }
                if(heading!=null)scope.launch { source=false;list.scrollToItem(heading.block) } else linkError="文档中没有找到此章节。" }
            "web","external" -> try { context.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(reference))) } catch(_: Exception) { linkError="手机上没有可打开此链接的应用。" }
            else -> linkError="此引用尚未由电脑提供为可访问资源，可在源码中查看原链接。"
        }
    } }
    Dialog(onDismissRequest=onDismiss,properties=DialogProperties(usePlatformDefaultWidth=false,decorFitsSystemWindows=false)) {
        Surface(Modifier.fillMaxSize().safeDrawingPadding().testTag("file-preview-screen"),color=MaterialTheme.colorScheme.background) {
            Column {
                Row(Modifier.fillMaxWidth().padding(horizontal=8.dp),verticalAlignment=Alignment.CenterVertically) {
                    TextButton(onClick=onDismiss,modifier=Modifier.testTag("file-preview-close")) { Text("完成") }
                    Text(file.name,Modifier.weight(1f),maxLines=1,overflow=TextOverflow.Ellipsis)
                    if(document!=null) {
                        TextButton(onClick={ searching=!searching;if(!searching)query="" },modifier=Modifier.testTag("file-preview-search")) { Text("搜索") }
                        TextButton(onClick={ outline=true },enabled=document!!.headings.isNotEmpty(),modifier=Modifier.testTag("file-preview-outline")) { Text("目录") }
                    }
                    TextButton(onClick={ externalFile(true) },enabled=local!=null) { Text("分享") }
                }
                val value=document
                if(value!=null) {
                    Row(Modifier.fillMaxWidth().padding(horizontal=16.dp)) {
                        FilterChip(selected=!source,onClick={source=false},label={ Text("预览") },modifier=Modifier.testTag("file-preview-rendered"))
                        Spacer(Modifier.width(8.dp));FilterChip(selected=source,onClick={source=true},label={ Text("源码") },modifier=Modifier.testTag("file-preview-raw"))
                        if(source) { Spacer(Modifier.weight(1f));TextButton(onClick={ clipboard.setText(AnnotatedString(value.source)) },modifier=Modifier.testTag("file-preview-copy-source")) { Text("复制源码") } }
                    }
                    if(searching) Row(Modifier.fillMaxWidth().padding(horizontal=16.dp),verticalAlignment=Alignment.CenterVertically) {
                        OutlinedTextField(query,{query=it},Modifier.weight(1f).testTag("file-preview-query"),singleLine=true,label={Text("搜索文件")})
                        Text("${if(matches.isEmpty())0 else hit+1}/${matches.size}",Modifier.padding(8.dp),fontSize=12.sp)
                        TextButton(enabled=matches.isNotEmpty(),onClick={ hit=(hit+1)%matches.size;scope.launch {source=false;list.scrollToItem(matches[hit])} }) { Text("下一项") }
                    }
                    HorizontalDivider()
                    if(source) AndroidView(factory={ TextView(it).apply {
                        text=value.source;typeface=Typeface.MONOSPACE;textSize=16f;setTextIsSelectable(true);movementMethod=ScrollingMovementMethod.getInstance();setPadding(32,24,32,24)
                    } },update={ it.setTextColor(sourceColor) },modifier=Modifier.weight(1f).fillMaxWidth().testTag("file-preview-source"))
                    else CompositionLocalProvider(LocalMarkdownLinkHandler provides openLink) {
                        SelectionContainer(Modifier.weight(1f).fillMaxWidth()) {
                            LazyColumn(state=list,contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(16.dp),modifier=Modifier.testTag("file-preview-content")) {
                                itemsIndexed(value.blocks) { index,node ->
                                    Column(Modifier.fillMaxWidth().background(if(index in matches)MaterialTheme.colorScheme.primary.copy(alpha=.1f) else androidx.compose.ui.graphics.Color.Transparent).testTag("file-preview-block-$index")) {
                                        if(node is TableBlock) PreviewNativeTable(node,index)
                                        else MarkdownBlock(node,"file-preview-native-$index",value.source)
                                    }
                                }
                            }
                        }
                    }
                } else if(failure!=null || local!=null) Column(Modifier.fillMaxSize().padding(24.dp),verticalArrangement=Arrangement.Center,horizontalAlignment=Alignment.CenterHorizontally) {
                    Text(failure?:"此文件可使用其他应用打开。",Modifier.testTag("file-preview-error"))
                    TextButton(enabled=local!=null,onClick={externalFile(false)}) { Text("用其他应用打开") }
                } else Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center) { CircularProgressIndicator() }
            }
        }
        if(outline) AlertDialog(onDismissRequest={outline=false},title={Text("目录")},text={
            LazyColumn { items(document?.headings.orEmpty()) { heading -> TextButton(onClick={outline=false;source=false;scope.launch {list.scrollToItem(heading.block)}},modifier=Modifier.padding(start=((heading.level-1)*8).dp).testTag("file-preview-heading-${heading.block}")) { Text(heading.title) } } }
        },confirmButton={TextButton(onClick={outline=false}){Text("完成")}})
        if(linkError!=null) AlertDialog(onDismissRequest={linkError=null},title={Text("无法打开链接")},text={Text(linkError!!)},confirmButton={TextButton(onClick={linkError=null}){Text("好")}})
    }
}

@Composable private fun PreviewNativeTable(table: TableBlock,index: Int) {
    val colors=MaterialTheme.colorScheme;val handler=LocalMarkdownLinkHandler.current
    val rows=remember(table) { NativeMarkdown.children(table).flatMap { NativeMarkdown.children(it) } }
    val cells=remember(table,handler) { rows.map { row -> NativeMarkdown.children(row).map { NativeMarkdown.inline(it,androidx.compose.ui.graphics.Color(0xFF007AFF),handler) } } }
    val widths=remember(cells) { (0..<(cells.maxOfOrNull {it.size}?:0)).map { column ->
        ((cells.take(100).maxOfOrNull {it.getOrNull(column)?.length?:0}?:0)*7).coerceIn(120,300).dp } }
    Column(Modifier.testTag("file-preview-table-$index"),verticalArrangement=Arrangement.spacedBy(5.dp)) {
        Text("左右滑动查看完整表格",fontSize=12.sp,color=colors.onSurfaceVariant)
        Column(Modifier.horizontalScroll(rememberScrollState())) {
            rows.forEachIndexed { rowIndex,row ->
                Row(Modifier.background(if(row.parent is TableHead)colors.surface else colors.background)) {
                    cells[rowIndex].forEachIndexed { column,cell -> Text(cell,Modifier.width(widths[column]).padding(10.dp),fontSize=15.sp,fontWeight=if(row.parent is TableHead)androidx.compose.ui.text.font.FontWeight.SemiBold else androidx.compose.ui.text.font.FontWeight.Normal) }
                }
                HorizontalDivider()
            }
        }
    }
}
