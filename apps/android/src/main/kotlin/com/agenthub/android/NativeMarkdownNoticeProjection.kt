package com.agenthub.android

import androidx.compose.ui.graphics.Color
import org.commonmark.node.*
import org.commonmark.node.Text as MarkdownText
import org.commonmark.ext.gfm.tables.*
import org.commonmark.ext.gfm.strikethrough.Strikethrough

/** Enumerate every supported rendered semantic leaf before any visibility can complete. Containers
 * never flatten their descendants twice. Hidden link/image destinations and unknown nodes have no
 * safe original-text witness in this renderer and deliberately leave the notice unread. */
internal object NativeMarkdownNoticeProjection {
    fun document(original: NativeNoticePanelOriginal?,source: String,node: Node,tag: String,codeLabel: String): NativeNoticeReadDocument? {
        if(original !is NativeNoticePanelOriginal.Plan || original.value.text!=source)return null
        val parts=mutableListOf<Pair<String,String>>();var characters=0L;var supported=true;var originalPayload=false;var visited=0
        fun add(id: String,text: String,payload: Boolean=true) {
            if(text.isEmpty())return
            characters+=text.length
            if(parts.size>=NativeNoticeReadDocument.MAX_PARTS || characters>NativeNoticeReadDocument.MAX_CHARACTERS) { supported=false;return }
            if(payload && text.isNotBlank())originalPayload=true
            parts+=id to text
        }
        fun inlineKnown(n: Node,depth: Int=0): Boolean {
            if(depth>64 || ++visited>8192)return false
            return when(n) {
            is MarkdownText,is Code,is SoftLineBreak,is HardLineBreak,is HtmlInline -> true
            is Paragraph,is Heading,is StrongEmphasis,is Emphasis,is Strikethrough,is TableCell -> NativeMarkdown.children(n).all { inlineKnown(it,depth+1) }
            else -> false
            }
        }
        fun inline(n: Node,id: String,depth: Int) {
            if(!supported)return
            if(!inlineKnown(n,depth))supported=false
            else add(id,NativeMarkdown.inline(n,Color(0xFF007AFF)).text)
        }
        fun block(n: Node,id: String,depth: Int=0) {
            if(!supported)return
            if(depth>64 || ++visited>8192) { supported=false;return }
            when(n) {
                is Heading,is Paragraph -> inline(n,id,depth)
                is FencedCodeBlock -> { add("$id-language",n.info.orEmpty().ifEmpty { codeLabel },false);add("$id-code-text",NativeMarkdown.codeBody(n,source)) }
                is IndentedCodeBlock -> { add("$id-language",codeLabel,false);add("$id-code-text",n.literal) }
                is HtmlBlock -> add(id,n.literal)
                is BlockQuote -> NativeMarkdown.children(n).forEachIndexed { i,child -> block(child,"$id-$i",depth+1) }
                is BulletList,is OrderedList -> NativeMarkdown.children(n).forEachIndexed { i,child ->
                    if(child !is ListItem)supported=false
                    add("$id-marker-$i",if(n is OrderedList)"${n.markerStartNumber+i}." else "•",false)
                    NativeMarkdown.children(child).forEachIndexed { j,leaf -> block(leaf,"$id-$i-$j",depth+1) }
                }
                is TableBlock -> NativeMarkdown.children(n).flatMap { NativeMarkdown.children(it) }.forEachIndexed { i,row ->
                    if(row !is TableRow)supported=false
                    NativeMarkdown.children(row).forEachIndexed { j,cell -> inline(cell,"$id-$i-$j",depth+3) }
                }
                is ThematicBreak -> Unit
                else -> supported=false
            }
        }
        NativeMarkdown.children(node).forEachIndexed { i,child -> block(child,"$tag-$i") }
        return if(supported && originalPayload)NativeNoticeReadDocument.create(original,parts) else null
    }
}
