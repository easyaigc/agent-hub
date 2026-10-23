package com.agenthub.core

/** UTF-16 offsets into the original text. Native Unicode matching never rewrites content. */
class ChatSearchSpan(val start: Int,val end: Int)
interface ChatSearchText {
    fun first(text: String,query: String): ChatSearchSpan?
    fun ranges(text: String,query: String): List<ChatSearchSpan>
    fun offset(text: String,start: Int,characters: Int): Int
}
class ChatSearchSnippet(val text: String,val spans: List<ChatSearchSpan>)
class ChatSearchPageOrder(val messageIds: List<String>,val seenCursors: List<String>,val valid: Boolean)

/** Shared search presentation and pagination; platform interfaces own Unicode and I/O. */
object ChatSearchRules {
    fun query(value: String): String = value.trim()
    fun resultsCurrent(resultQuery: String,value: String,resultSourceVersion: String,sourceVersion: String): Boolean =
        query(resultQuery)==query(value)&&resultSourceVersion.isNotEmpty()&&resultSourceVersion==sourceVersion
    fun document(text: String,detail: String?,files: List<String>,attachments: List<String>,parts: List<String>): String =
        (listOf(text,detail.orEmpty())+files+attachments+parts).filter { it.isNotEmpty() }.joinToString("\n")
    private fun valid(text: String,span: ChatSearchSpan): Boolean {
        fun boundary(index: Int)=index==0||index==text.length||
            !(text[index-1].code in 0xD800..0xDBFF&&text[index].code in 0xDC00..0xDFFF)
        return span.start>=0&&span.end>span.start&&span.end<=text.length&&boundary(span.start)&&boundary(span.end)
    }
    private fun first(text: String,value: String,native: ChatSearchText): ChatSearchSpan? =
        if(query(value).isEmpty())null else native.first(text,query(value))?.takeIf { valid(text,it) }
    fun ranges(text: String,value: String,native: ChatSearchText): List<ChatSearchSpan> {
        val query=query(value)
        if(query.isEmpty())return emptyList()
        return native.ranges(text,query).filter { valid(text,it) }
            .distinctBy { it.start to it.end }.sortedWith(compareBy({ it.start },{ it.end }))
    }
    fun matches(text: String,value: String,native: ChatSearchText): Boolean = first(text,value,native)!=null
    fun longMessage(text: String,native: ChatSearchText): Boolean = native.offset(text,0,300)<text.length
    fun snippet(text: String,value: String,native: ChatSearchText): ChatSearchSnippet {
        val match=first(text,value,native)
        val start=if(match==null)0 else native.offset(text,match.start,-60).coerceIn(0,match.start)
        val end=if(match==null)native.offset(text,0,240).coerceIn(0,text.length)
            else native.offset(text,match.end,180).coerceIn(match.end,text.length)
        val snippet=(if(start==0)"" else "…")+text.substring(start,end)+(if(end==text.length)"" else "…")
        return ChatSearchSnippet(snippet,ranges(snippet,value,native))
    }
    fun page(previous: List<String>,incoming: List<String>,nextCursor: String?,seenCursors: List<String>): ChatSearchPageOrder {
        if(nextCursor!=null&&(nextCursor.isEmpty()||nextCursor in seenCursors))
            return ChatSearchPageOrder(previous,seenCursors,false)
        val ids=incoming.toSet()
        return ChatSearchPageOrder(incoming.distinct()+previous.filter { it !in ids }.distinct(),
            if(nextCursor==null)seenCursors else seenCursors+nextCursor,true)
    }
}
