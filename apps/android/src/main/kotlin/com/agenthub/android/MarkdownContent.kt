package com.agenthub.android

import androidx.compose.ui.text.style.TextOverflow

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.*
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.commonmark.node.*
import org.commonmark.node.Text as MarkdownText
import org.commonmark.parser.Parser
import org.commonmark.parser.IncludeSourceSpans
import org.commonmark.ext.gfm.tables.*
import org.commonmark.ext.gfm.strikethrough.*

internal val LocalMarkdownLinkHandler = staticCompositionLocalOf<((String) -> Unit)?> { null }

internal object NativeMarkdown {
    private val parser = Parser.builder().includeSourceSpans(IncludeSourceSpans.BLOCKS).extensions(listOf(TablesExtension.create(), StrikethroughExtension.create())).build()
    fun parse(source: String): Node = parser.parse(source)
    // Match iOS MarkdownBody's raw triple-backtick payload. CommonMark's literal
    // normalizes CRLF, strips fence indentation and invents a final LF at EOF.
    fun codeBody(node: FencedCodeBlock, source: String): String {
        if (node.fenceCharacter != "`" || node.openingFenceLength != 3) return node.literal
        val spans = node.sourceSpans
        val opening = spans.firstOrNull() ?: return node.literal
        val lineEnd = opening.inputIndex + opening.length
        val newline = source.indexOf('\n', lineEnd)
        if (newline < 0) return ""
        val start = newline + 1
        val closingLength = node.closingFenceLength ?: return source.substring(start)
        val closing = spans.last()
        val end = source.indexOf("`".repeat(closingLength), closing.inputIndex)
        if (end < start || end >= closing.inputIndex + closing.length) return node.literal
        return source.substring(start, end)
    }
    fun children(node: Node): List<Node> = buildList { var child = node.firstChild; while (child != null) { add(child); child = child.next } }
    fun inline(node: Node, linkColor: Color, onLink: ((String,String)->Unit)? = null, anchorPrefix: String="", openingAnchor: String?=null,imageLabel: String="图片：",linkedImageLabel: String="查看图片："): AnnotatedString = buildAnnotatedString {
        var linkIndex=0
        fun visit(n: Node) {
            fun nested() { children(n).forEach(::visit) }
            when (n) {
                is MarkdownText -> append(n.literal)
                is Code -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = linkColor.copy(alpha=.07f))) { append(n.literal) }
                is StrongEmphasis -> withStyle(SpanStyle(fontWeight=FontWeight.SemiBold)) { nested() }
                is Emphasis -> withStyle(SpanStyle(fontStyle=FontStyle.Italic)) { nested() }
                is Strikethrough -> withStyle(SpanStyle(textDecoration=TextDecoration.LineThrough)) { nested() }
                is SoftLineBreak, is HardLineBreak -> append("\n")
                is Link -> {
                    val anchor=anchorPrefix+":"+linkIndex++
                    if (onLink != null) {
                        withLink(LinkAnnotation.Clickable(anchor, TextLinkStyles(style = SpanStyle(color=linkColor, textDecoration=TextDecoration.Underline,background=if(openingAnchor==anchor)linkColor.copy(alpha=.15f) else Color.Transparent))) {
                            onLink(n.destination,anchor)
                        }) { nested() }
                    } else if(MessageLinkDestination.resolve(n.destination).kind in listOf("web","external") &&
                        runCatching { java.net.URI(n.destination).scheme?.lowercase() }.getOrNull() in listOf("http","https","mailto")) {
                        // Runtime plan text without a message identity retains its safe system-link behavior.
                        withLink(LinkAnnotation.Url(n.destination,TextLinkStyles(style=SpanStyle(color=linkColor,textDecoration=TextDecoration.Underline)))) { nested() }
                    } else nested()
                }
                is Image -> {
                    val imageAnchor=anchorPrefix+":image:"+linkIndex++
                    if(onLink != null) withLink(LinkAnnotation.Clickable(imageAnchor,TextLinkStyles(style=SpanStyle(color=linkColor))) { onLink(n.destination,imageAnchor) }) { append(linkedImageLabel); nested() }
                    else { append(imageLabel); nested() }
                }
                is HtmlInline -> append(n.literal)
                else -> nested()
            }
        }
        visit(node)
    }
}

private data class ParsedMarkdown(val source: String, val document: Node)

@Composable internal fun MarkdownContent(text: String, tag: String) {
    // produceState retains its prior value until the next parse completes. Keep
    // source offsets and their document in one snapshot during streaming updates.
    val parsed by produceState<ParsedMarkdown?>(null, text) {
        val result=withContext(Dispatchers.Default) { ParsedMarkdown(text, NativeMarkdown.parse(text)) }
        withContext(Dispatchers.Main.immediate) { value=result }
    }
    val notice=LocalNativeNoticeReadContext.current
    val codeLabel=productText("代码")
    val noticeDocument=remember(parsed,text,notice?.original,tag,codeLabel) {
        parsed?.takeIf { it.source==text }?.let { NativeMarkdownNoticeProjection.document(notice?.original,it.source,it.document,tag,codeLabel) }
    }
    NativeNoticeReadProjection(noticeDocument)
    val open=LocalMessageLinkOpen.current
    // Preserve rendered block/scroll ownership during an async parse, but disable
    // callbacks from the earlier rendered source until the current document is ready.
    val renderedOpen=remember(parsed?.source,text,open) { if(parsed?.source!=text) ({ _: String,_: String -> Unit }) else open }
    CompositionLocalProvider(LocalMessageLinkOpen provides renderedOpen) {
    SelectionContainer(Modifier.testTag(tag)) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val document = parsed
            if (document == null) Text(text, fontSize = 17.sp, lineHeight = 24.sp)
            else NativeMarkdown.children(document.document).forEachIndexed { index, block -> MarkdownBlock(block, "$tag-$index", document.source) }
        }
    }
    }
}

@Composable internal fun MarkdownBlock(node: Node, tag: String, source: String) {
    val colors = MaterialTheme.colorScheme
    when (node) {
        is Heading -> Column { NativeNoticeReadText(tag,rememberInline(node,tag), fontSize = when (node.level) { 1 -> 22.sp; 2 -> 20.sp; else -> 17.sp }, fontWeight = FontWeight.SemiBold, lineHeight = 28.sp, modifier = Modifier.padding(top=4.dp).testTag(tag));MessageLinkWait(tag) }
        is FencedCodeBlock -> CodeContent(NativeMarkdown.codeBody(node, source), node.info.orEmpty(), tag)
        is IndentedCodeBlock -> CodeContent(node.literal, "", tag)
        is BlockQuote -> Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).testTag(tag)) {
            Spacer(Modifier.width(3.dp).fillMaxHeight().background(colors.onSurfaceVariant.copy(alpha=.4f)))
            CompositionLocalProvider(LocalContentColor provides colors.onSurfaceVariant) { Column(Modifier.padding(start=10.dp), verticalArrangement=Arrangement.spacedBy(8.dp)) { NativeMarkdown.children(node).forEachIndexed { i, block -> MarkdownBlock(block,"$tag-$i",source) } } }
        }
        is BulletList, is OrderedList -> Column(verticalArrangement=Arrangement.spacedBy(6.dp)) {
            NativeMarkdown.children(node).forEachIndexed { index, child ->
                Row { NativeNoticeReadText("$tag-marker-$index",if (node is OrderedList) "${node.markerStartNumber + index}." else "•", Modifier.width(26.dp), fontSize=16.sp); Column(Modifier.weight(1f), verticalArrangement=Arrangement.spacedBy(6.dp)) { NativeMarkdown.children(child).forEachIndexed { i, block -> MarkdownBlock(block,"$tag-$index-$i",source) } } }
            }
        }
        is TableBlock -> MarkdownTable(node, tag)
        is ThematicBreak -> HorizontalDivider(Modifier.padding(vertical=4.dp))
        is HtmlBlock -> NativeNoticeReadText(tag,node.literal, fontFamily=FontFamily.Monospace, fontSize=13.sp)
        else -> Column { NativeNoticeReadText(tag,rememberInline(node,tag), fontSize = 17.sp, lineHeight = 24.sp, modifier = Modifier.testTag(tag));MessageLinkWait(tag) }
    }
}
// Container blocks render their children and must not first flatten the entire
// subtree. Parsed nodes are immutable after publication to the composition.
@Composable private fun rememberInline(node: Node,tag: String): AnnotatedString {
    val messageOpen=LocalMessageLinkOpen.current
    val fileOpen=LocalMarkdownLinkHandler.current
    val open: ((String,String)->Unit)?=remember(messageOpen,fileOpen) { fileOpen?.let { handler -> { reference: String,_: String -> handler(reference) } } ?: messageOpen }
    val opening=if(fileOpen==null)LocalOpeningMessageAnchor.current else null
    val imageLabel=productText("图片：");val linkedImageLabel=productText("查看图片：")
    return remember(node,open,opening,tag,imageLabel,linkedImageLabel) { NativeMarkdown.inline(node, Color(0xFF007AFF),open,tag,opening,imageLabel,linkedImageLabel) }
}
@Composable private fun MarkdownTable(node: TableBlock, tag: String) {
    val colors = MaterialTheme.colorScheme
    val rows = NativeMarkdown.children(node).flatMap { NativeMarkdown.children(it) }
    val columns = rows.maxOfOrNull { NativeMarkdown.children(it).size } ?: 0
    val widths = (0 until columns).map { 156.dp }
    Column(Modifier.testTag("$tag-table").horizontalScroll(rememberScrollState()).border(1.dp, colors.onSurface.copy(alpha=.12f), RoundedCornerShape(8.dp)).clip(RoundedCornerShape(8.dp))) {
        rows.forEachIndexed { rowIndex,row ->
            Row(Modifier.background(if (row.parent is TableHead) colors.surface else Color.Transparent)) {
                NativeMarkdown.children(row).forEachIndexed { index, cell -> val cellTag="$tag-$rowIndex-$index";Column(Modifier.width(widths[index])) { NativeNoticeReadText(cellTag,rememberInline(cell,cellTag),Modifier.padding(10.dp),fontSize=15.sp, fontWeight=if (row.parent is TableHead) FontWeight.SemiBold else FontWeight.Normal);MessageLinkWait(cellTag) } }
            }
            HorizontalDivider(color=colors.onSurface.copy(alpha=.12f))
        }
    }
}
@Composable internal fun CodeContent(text: String, language: String, tag: String) {
    val colors = MaterialTheme.colorScheme
    val clipboard = LocalClipboardManager.current
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(colors.surface).testTag("$tag-code")) {
        DisableSelection {
        Row(Modifier.fillMaxWidth().padding(start=12.dp,end=LocalMessageActionEndInset.current), horizontalArrangement=Arrangement.SpaceBetween) {
            NativeNoticeReadText("$tag-language",if(language.isEmpty())productText("代码") else language, Modifier.weight(1f).padding(top=12.dp), fontSize=12.sp, color=colors.onSurfaceVariant,maxLines=1,overflow=TextOverflow.Ellipsis)
            TextButton(onClick={ clipboard.setText(AnnotatedString(text)) },modifier=Modifier.heightIn(min=MobileDesign.androidIconTouchSize.dp).testTag("$tag-copy-code")) { Text(productText("复制"),fontSize=12.sp) }
        }
        }
        SelectionContainer(Modifier.testTag("$tag-code-body")) {
        Column(Modifier.horizontalScroll(rememberScrollState()).testTag("$tag-code-scroll").padding(12.dp)) {
            if (language == "diff") {
                // Keep one selectable string. Splitting CRLF into separate Text
                // nodes makes native cross-line Copy invent LF separators.
                val highlighted = remember(text, colors.onSurface) { buildAnnotatedString {
                    append(text)
                    var start = 0
                    while (start < text.length) {
                        val end = text.indexOf('\n', start).let { if (it < 0) text.length else it }
                        val color = when {
                            text.startsWith("+", start) -> Color(0xFF248545)
                            text.startsWith("-", start) -> Color(0xFFC43838)
                            text.startsWith("@@", start) -> Color(0xFF487AA8)
                            else -> colors.onSurface
                        }
                        if (end > start) addStyle(SpanStyle(color=color), start, end)
                        start = end + 1
                    }
                } }
                NativeNoticeReadText("$tag-code-text",highlighted, fontFamily=FontFamily.Monospace,fontSize=13.sp,softWrap=false)
            }
            else NativeNoticeReadText("$tag-code-text",text, fontFamily=FontFamily.Monospace,fontSize=16.sp,lineHeight=22.sp,softWrap=false)
        }
        }
    }
}
