package com.agenthub.core

import kotlin.io.encoding.Base64
import kotlin.test.*
import kotlinx.serialization.json.*

/** ASCII/exact-match test double; actual native Unicode adapters consume all vectors separately. */
private object LiteralSearchText: ChatSearchText {
    override fun first(text: String,query: String)=ranges(text,query).firstOrNull()
    override fun ranges(text: String,query: String): List<ChatSearchSpan> {
        if(query.isEmpty())return emptyList()
        val ranges=mutableListOf<ChatSearchSpan>();var position=0
        while(position<text.length) {
            val index=text.indexOf(query,position,ignoreCase=true)
            if(index<0)break
            ranges+=ChatSearchSpan(index,index+query.length);position=index+query.length
        }
        return ranges
    }
    override fun offset(text: String,start: Int,characters: Int): Int {
        var position=start
        repeat(kotlin.math.abs(characters)) {
            if(characters<0) {
                if(position==0)return 0
                position--;if(position>0&&text[position].code in 0xDC00..0xDFFF&&text[position-1].code in 0xD800..0xDBFF)position--
            } else {
                if(position==text.length)return text.length
                val pair=text[position].code in 0xD800..0xDBFF&&position+1<text.length&&text[position+1].code in 0xDC00..0xDFFF
                position+=if(pair)2 else 1
            }
        }
        return position
    }
}

class ChatSearchRulesTest {
    @Test fun commonSnippetGoldenSubsetRetainsExactTextAndOffsets() {
        val vectors=Json.parseToJsonElement(Base64.decode(stateVectorBase64).decodeToString()).jsonObject.getValue("chatSearch").jsonArray
        var checked=0
        for(value in vectors) {
            val row=value.jsonObject
            if(row.getValue("nativeOnly").jsonPrimitive.boolean)continue
            val text=row.getValue("text").jsonPrimitive.content;val query=row.getValue("query").jsonPrimitive.content
            val snippet=ChatSearchRules.snippet(text,query,LiteralSearchText)
            assertEquals(row.getValue("expectedSnippet").jsonPrimitive.content,snippet.text)
            fun json(spans: List<ChatSearchSpan>)=JsonArray(spans.map { JsonArray(listOf(JsonPrimitive(it.start),JsonPrimitive(it.end))) })
            assertEquals(row.getValue("expectedRanges"),json(ChatSearchRules.ranges(text,query,LiteralSearchText)))
            assertEquals(row.getValue("expectedSnippetRanges"),json(snippet.spans));checked++
        }
        assertEquals(9,checked)
    }
    @Test fun documentsIncludeAllKnownContentWithoutChangingOriginalParts() {
        val parts=listOf("正文\n原行","file.pdf","tool-name","{ input }","output","+ diff")
        assertEquals("text\ndetail\nlegacy.txt\nimage.png\n"+parts.joinToString("\n"),
            ChatSearchRules.document("text","detail",listOf("legacy.txt"),listOf("image.png"),parts))
        assertEquals("\n",ChatSearchRules.document("\n",null,emptyList(),emptyList(),emptyList()))
    }
    @Test fun pageProjectionRejectsCyclesAndNeverMutatesItsInput() {
        val previous=listOf("newer","duplicate");val incoming=listOf("older","duplicate","older")
        val page=ChatSearchRules.page(previous,incoming,"cursor-a",emptyList())
        assertEquals(listOf("older","duplicate","newer"),page.messageIds);assertTrue(page.valid)
        assertFalse(ChatSearchRules.page(page.messageIds,listOf("lost"),"cursor-a",page.seenCursors).valid)
        assertFalse(ChatSearchRules.page(previous,incoming,"",emptyList()).valid)
        assertTrue(ChatSearchRules.page(page.messageIds,emptyList(),null,page.seenCursors).valid)
        assertEquals(listOf("newer","duplicate"),previous);assertEquals(3,incoming.size)
        assertTrue(ChatSearchRules.resultsCurrent("needle"," needle ","source-a","source-a"))
        assertFalse(ChatSearchRules.resultsCurrent("old","new","source-a","source-a"))
        assertFalse(ChatSearchRules.resultsCurrent("needle","needle","source-a","source-b"))
    }
    @Test fun invalidZeroLengthAndSplitSurrogateNativeRangesNeverHighlight() {
        val broken=object: ChatSearchText {
            override fun first(text: String,query: String)=ChatSearchSpan(1,2)
            override fun ranges(text: String,query: String)=listOf(ChatSearchSpan(0,0),ChatSearchSpan(1,2),ChatSearchSpan(-1,1),ChatSearchSpan(0,99))
            override fun offset(text: String,start: Int,characters: Int)=if(characters<0)0 else text.length
        }
        assertFalse(ChatSearchRules.matches("🚀", "q",broken));assertEquals(emptyList(),ChatSearchRules.ranges("🚀","q",broken))
    }
    @Test fun focusedToolErrorAndCompactionKeepOtherGroupsAndFinalActions() {
        fun message(id: String,role: String,kind: String?=null)=TimelineMessage(id,role,"original $id",kind,"completed","run",null,null)
        val messages=listOf(message("t1","tool"),message("t2","tool"),message("t3","tool"),message("a","assistant"))
        val base=MessageProjection.project(messages,20,null,false)
        val focus=MessageProjection.focus(base,messages,"t2")
        assertEquals(listOf("t1","t2","t3","a","a"),focus.rows.map { it.id })
        assertEquals(listOf("tools","message","tools","message","replyActions"),focus.rows.map { it.kind })
        assertEquals(base.rows.last().text,focus.rows.last().text)
        for(kind in listOf("error","contextCompaction")) {
            val values=listOf(message("e1","tool",kind),message("e2","tool",kind))
            val selected=MessageProjection.focus(MessageProjection.project(values,20,null,false),values,"e1")
            assertEquals("message",selected.rows.first().kind);assertEquals("e1",selected.rows.first().id)
        }
    }
}
