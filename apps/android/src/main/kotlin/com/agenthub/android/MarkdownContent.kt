package com.agenthub.android

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
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
import org.commonmark.ext.gfm.tables.*
import org.commonmark.ext.gfm.strikethrough.*

internal object NativeMarkdown {
    private val parser = Parser.builder().extensions(listOf(TablesExtension.create(), StrikethroughExtension.create())).build()
    fun parse(source: String): Node = parser.parse(source)
    fun children(node: Node): List<Node> = buildList { var child = node.firstChild; while (child != null) { add(child); child = child.next } }
    fun inline(node: Node, linkColor: Color): AnnotatedString = buildAnnotatedString {
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
                    val uri = runCatching { java.net.URI(n.destination) }.getOrNull()
                    if (uri?.scheme?.lowercase() in listOf("https", "http", "mailto")) {
                        withLink(LinkAnnotation.Url(n.destination, TextLinkStyles(style = SpanStyle(color=linkColor, textDecoration=TextDecoration.Underline)))) { nested() }
                    } else nested()
                }
                is Image -> { append("图片："); nested() }
                is HtmlInline -> append(n.literal)
                else -> nested()
            }
        }
        visit(node)
    }
}

@Composable internal fun MarkdownContent(text: String, tag: String) {
    val parsed by produceState<Node?>(null, text) { value = withContext(Dispatchers.Default) { NativeMarkdown.parse(text) } }
    SelectionContainer(Modifier.testTag(tag)) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val document = parsed
            if (document == null) Text(text, fontSize = 17.sp, lineHeight = 24.sp)
            else NativeMarkdown.children(document).forEachIndexed { index, block -> MarkdownBlock(block, "$tag-$index") }
        }
    }
}

@Composable private fun MarkdownBlock(node: Node, tag: String) {
    val colors = MaterialTheme.colorScheme
    val inline = NativeMarkdown.inline(node, Color(0xFF007AFF))
    when (node) {
        is Heading -> Text(inline, fontSize = when (node.level) { 1 -> 22.sp; 2 -> 20.sp; else -> 17.sp }, fontWeight = FontWeight.SemiBold, lineHeight = 28.sp, modifier = Modifier.padding(top=4.dp).testTag(tag))
        is FencedCodeBlock -> CodeContent(node.literal, node.info.orEmpty(), tag)
        is IndentedCodeBlock -> CodeContent(node.literal, "", tag)
        is BlockQuote -> Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).testTag(tag)) {
            Spacer(Modifier.width(3.dp).fillMaxHeight().background(colors.onSurfaceVariant.copy(alpha=.4f)))
            CompositionLocalProvider(LocalContentColor provides colors.onSurfaceVariant) { Column(Modifier.padding(start=10.dp), verticalArrangement=Arrangement.spacedBy(8.dp)) { NativeMarkdown.children(node).forEachIndexed { i, block -> MarkdownBlock(block,"$tag-$i") } } }
        }
        is BulletList, is OrderedList -> Column(verticalArrangement=Arrangement.spacedBy(6.dp)) {
            NativeMarkdown.children(node).forEachIndexed { index, child ->
                Row { Text(if (node is OrderedList) "${node.markerStartNumber + index}." else "•", Modifier.width(26.dp), fontSize=16.sp); Column(Modifier.weight(1f), verticalArrangement=Arrangement.spacedBy(6.dp)) { NativeMarkdown.children(child).forEachIndexed { i, block -> MarkdownBlock(block,"$tag-$index-$i") } } }
            }
        }
        is TableBlock -> MarkdownTable(node, tag)
        is ThematicBreak -> HorizontalDivider(Modifier.padding(vertical=4.dp))
        is HtmlBlock -> Text(node.literal, fontFamily=FontFamily.Monospace, fontSize=13.sp)
        else -> Text(inline, fontSize = 17.sp, lineHeight = 24.sp, modifier = Modifier.testTag(tag))
    }
}
@Composable private fun MarkdownTable(node: TableBlock, tag: String) {
    val colors = MaterialTheme.colorScheme
    val rows = NativeMarkdown.children(node).flatMap { NativeMarkdown.children(it) }
    val columns = rows.maxOfOrNull { NativeMarkdown.children(it).size } ?: 0
    val widths = (0 until columns).map { 156.dp }
    Column(Modifier.testTag("$tag-table").horizontalScroll(rememberScrollState()).border(1.dp, colors.onSurface.copy(alpha=.12f), RoundedCornerShape(8.dp)).clip(RoundedCornerShape(8.dp))) {
        rows.forEach { row ->
            Row(Modifier.background(if (row.parent is TableHead) colors.surface else Color.Transparent)) {
                NativeMarkdown.children(row).forEachIndexed { index, cell -> Text(NativeMarkdown.inline(cell,Color(0xFF007AFF)),Modifier.width(widths[index]).padding(10.dp),fontSize=15.sp, fontWeight=if (row.parent is TableHead) FontWeight.SemiBold else FontWeight.Normal) }
            }
            HorizontalDivider(color=colors.onSurface.copy(alpha=.12f))
        }
    }
}
@Composable internal fun CodeContent(text: String, language: String, tag: String) {
    val colors = MaterialTheme.colorScheme
    val clipboard = LocalClipboardManager.current
    var copied by remember(text) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(colors.surface).testTag("$tag-code")) {
        Row(Modifier.fillMaxWidth().padding(start=12.dp), horizontalArrangement=Arrangement.SpaceBetween) {
            Text(language.ifEmpty { "代码" }, Modifier.padding(top=12.dp), fontSize=12.sp, color=colors.onSurfaceVariant)
            TextButton(onClick={ clipboard.setText(AnnotatedString(text)); copied=true },modifier=Modifier.testTag("$tag-copy-code")) { Text(if(copied) "已复制" else "复制",fontSize=12.sp) }
        }
        Column(Modifier.horizontalScroll(rememberScrollState()).padding(12.dp)) {
            if (language == "diff") text.lines().forEach { line -> Text(line, fontFamily=FontFamily.Monospace,fontSize=13.sp,color=when { line.startsWith("+") -> Color(0xFF248545); line.startsWith("-") -> Color(0xFFC43838); line.startsWith("@@") -> Color(0xFF487AA8); else -> colors.onSurface }) }
            else Text(text, fontFamily=FontFamily.Monospace,fontSize=16.sp,lineHeight=22.sp,softWrap=false)
        }
    }
}
