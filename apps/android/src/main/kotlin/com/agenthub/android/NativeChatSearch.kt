package com.agenthub.android

import android.icu.text.BreakIterator
import android.icu.text.Collator
import android.icu.text.RuleBasedCollator
import android.icu.text.SearchIterator
import android.icu.text.StringSearch
import android.icu.lang.UCharacter
import com.agenthub.core.*
import org.json.JSONArray
import java.text.StringCharacterIterator
import java.text.Normalizer
import java.util.Locale

/** System Unicode services only; shared rules own document composition and presentation. */
internal object NativeChatSearchText: ChatSearchText {
    private fun fold(value: String): String = Normalizer.normalize(UCharacter.foldCase(value,true),Normalizer.Form.NFD).filter {
        Character.getType(it) !in listOf(Character.NON_SPACING_MARK.toInt(),Character.COMBINING_SPACING_MARK.toInt(),Character.ENCLOSING_MARK.toInt())
    }
    private val collators=ThreadLocal.withInitial {
        (Collator.getInstance(Locale.ROOT) as RuleBasedCollator).apply {
            strength=Collator.PRIMARY;decomposition=Collator.CANONICAL_DECOMPOSITION
        }
    }
    private fun search(text: String,query: String): StringSearch? {
        if(text.isEmpty()||query.isEmpty())return null
        return try { StringSearch(query,StringCharacterIterator(text),collators.get()) }
        catch(_: IllegalArgumentException) { null } // A pattern of only ignorable collation weights.
    }
    override fun first(text: String,query: String): ChatSearchSpan? {
        val search=search(text,query) ?: return null
        val expected=fold(query)
        if(expected.isEmpty())return null
        var start=search.first()
        while(start!=SearchIterator.DONE) {
            val end=start+search.matchLength
            if(end>start&&fold(text.substring(start,end))==expected)return ChatSearchSpan(start,end)
            start=search.next()
        }
        return null
    }
    override fun ranges(text: String,query: String): List<ChatSearchSpan> {
        val search=search(text,query) ?: return emptyList()
        val ranges=mutableListOf<ChatSearchSpan>()
        val expected=fold(query)
        if(expected.isEmpty())return emptyList()
        var start=search.first()
        while(start!=SearchIterator.DONE) {
            val end=start+search.matchLength
            // Primary collation also ignores width; Foundation's requested options do not.
            // Verify candidates with canonical/case/diacritic folding while retaining ICU's original offsets.
            if(end>start&&fold(text.substring(start,end))==expected)ranges+=ChatSearchSpan(start,end)
            start=search.next()
        }
        return ranges
    }
    override fun offset(text: String,start: Int,characters: Int): Int {
        val boundaries=BreakIterator.getCharacterInstance(Locale.ROOT).apply { setText(text) }
        var position=start.coerceIn(0,text.length)
        repeat(kotlin.math.abs(characters)) {
            val next=if(characters<0)boundaries.preceding(position) else boundaries.following(position)
            if(next==BreakIterator.DONE)return if(characters<0)0 else text.length
            position=next
        }
        return position
    }
}

internal fun MobileMessage.searchableText(): String {
    val parts=blocks?.let { JSONArray(it).objects() }.orEmpty().flatMap { part -> when(part.optString("type")) {
        "text","diff" -> listOf(part.optString("text"))
        "resource" -> listOf(part.optString("name"))
        "tool" -> listOf(part.optString("name"),part.optional("input").orEmpty(),part.optional("output").orEmpty())
        else -> emptyList()
    } }
    return ChatSearchRules.document(text,detail,files,attachments.map { it.name },parts)
}
