package com.agenthub.android

import androidx.test.platform.app.InstrumentationRegistry
import com.agenthub.core.*
import org.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class MobileChatSearchRulesTest {
    @get:Rule val foreground: org.junit.rules.TestRule=NativeTestForeground()
    @Test fun actualAndroidUnicodeAndSnippetAdaptersUseOriginalUtf16Ranges() {
        val context=InstrumentationRegistry.getInstrumentation().context
        val rows=JSONObject(context.assets.open("state-v1.json").bufferedReader().use { it.readText() }).getJSONArray("chatSearch").objects()
        fun spans(values: List<ChatSearchSpan>)=values.map { listOf(it.start,it.end) }
        for(row in rows) {
            val text=row.getString("text");val query=row.getString("query");val name=row.getString("name")
            fun expected(key: String)=row.getJSONArray(key).let { values -> (0 until values.length()).map { i ->
                val span=values.getJSONArray(i);listOf(span.getInt(0),span.getInt(1)) } }
            val snippet=ChatSearchRules.snippet(text,query,NativeChatSearchText)
            assertEquals(name,expected("expectedRanges"),spans(ChatSearchRules.ranges(text,query,NativeChatSearchText)))
            assertEquals(name,row.getString("expectedSnippet"),snippet.text)
            assertEquals(name,expected("expectedSnippetRanges"),spans(snippet.spans))
            assertEquals(name,expected("expectedRanges").isNotEmpty(),ChatSearchRules.matches(text,query,NativeChatSearchText))
        }
        assertEquals(17,rows.size)
    }
    @Test fun structuredToolDiffResourceAndLegacyAttachmentsRemainSearchable() {
        val blocks=JSONArray().put(JSONObject().put("type","text").put("text","BLOCK_BODY"))
            .put(JSONObject().put("type","resource").put("name","RESOURCE_NAME"))
            .put(JSONObject().put("type","tool").put("name","TOOL_NAME").put("input","INPUT").put("output","OUTPUT"))
            .put(JSONObject().put("type","diff").put("text","+ DIFF"))
            .put(JSONObject().put("type","extension").put("namespace","opaque").put("data",JSONObject().put("hidden","NOT_SEARCH_TEXT")))
        val message=MobileMessage("m","tool","BODY",detail="DETAIL",files=listOf("FILE"),attachments=listOf(
            MobileFile("f","ATTACHMENT","text/plain",1,"h","t","0".repeat(64))),blocks=blocks.toString())
        assertEquals("BODY\nDETAIL\nFILE\nATTACHMENT\nBLOCK_BODY\nRESOURCE_NAME\nTOOL_NAME\nINPUT\nOUTPUT\n+ DIFF",message.searchableText())
    }
}
