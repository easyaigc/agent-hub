package com.agenthub.android

/** Bounded, view-owned projection. Only hashes/UTF-16 coverage are retained here, never answers,
 * transcripts or another persistent read ledger. Original models remain with the mounted renderer. */
internal data class NativeNoticeReadPart(val id: String,val version: String,val length: Int)
internal data class NativeNoticeReadDocument(val original: NativeNoticePanelOriginal,val parts: List<NativeNoticeReadPart>) {
    companion object {
        const val MAX_PARTS=1024
        const val MAX_CHARACTERS=131_072
        fun create(original: NativeNoticePanelOriginal,texts: List<Pair<String,String>>): NativeNoticeReadDocument? {
            if(texts.sumOf { it.second.length.toLong() }>MAX_CHARACTERS || texts.none { it.second.isNotBlank() })return null
            val parts=texts.filter { it.second.isNotEmpty() }.map { NativeNoticeReadPart(it.first,readingSHA(it.second),it.second.length) }
            if(parts.isEmpty() || parts.size>MAX_PARTS || parts.map { it.id }.distinct().size!=parts.size || parts.any { it.id.isEmpty() || it.id.length>1024 })return null
            return NativeNoticeReadDocument(original,parts)
        }
    }
}

/** An interval is added only from a native text layout's fully visible glyph boxes. Scrolling does
 * not imply any interval between two samples. The caller resets on layout/window/frame replacement. */
internal class NativeNoticeReadCoverage {
    enum class Reset { EXPLICIT, DOCUMENT, LAYOUT, BINDING, WINDOW, ROOT, ATTACH, DETACH, COORDINATES, TEXT, INCOMPLETE }
    private var document: NativeNoticeReadDocument?=null
    private var required=emptyMap<String,NativeNoticeReadPart>()
    private val covered=linkedMapOf<String,java.util.BitSet>()
    private val layouts=linkedMapOf<String,Any>()
    private val resets=java.util.EnumMap<Reset,Long>(Reset::class.java)
    var revision=0L;private set
    fun install(value: NativeNoticeReadDocument?) {
        if(document==value)return
        document=value;required=value?.parts?.associateBy { it.id }.orEmpty();layouts.clear();reset(Reset.DOCUMENT)
    }
    fun reset(reason: Reset=Reset.EXPLICIT) { revision++;covered.clear();resets[reason]=(resets[reason] ?: 0L)+1L }
    fun layout(part: String,identity: Any) {
        val previous=layouts.put(part,identity)
        if(previous!==identity)reset(Reset.LAYOUT)
    }
    fun remove(part: String) { if(layouts.remove(part)!=null)reset(Reset.DETACH) }
    fun accept(part: NativeNoticeReadPart,start: Int,end: Int): Boolean {
        if(required[part.id]!=part || layouts[part.id]==null || start<0 || end<=start || end>part.length)return false
        covered.getOrPut(part.id) { java.util.BitSet(part.length) }.set(start,end);return true
    }
    fun complete(): Boolean=document?.parts?.takeIf { it.isNotEmpty() }?.all { (covered[it.id]?.nextClearBit(0) ?: 0)>=it.length }==true
    fun contains(part: NativeNoticeReadPart,start: Int,end: Int): Boolean = required[part.id]==part && layouts[part.id]!=null &&
        start>=0 && end>=start && end<=part.length && (covered[part.id]?.nextClearBit(start) ?: start)>=end
    fun coveredCharacters(part: NativeNoticeReadPart)=if(required[part.id]==part)covered[part.id]?.cardinality() ?: 0 else 0
    fun resetCounts(): Map<String,Long> = resets.mapKeys { it.key.name.lowercase() }
    fun part(id: String)=required[id]
    fun coveredCharacters()=covered.values.sumOf { it.cardinality() }
}
