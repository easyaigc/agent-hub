package com.agenthub.android

/** Test-only geometry, shared verbatim by native observation and auxiliary JVM counterexamples.
 * Rectangles are local physical pixels. Paragraph constraint width is deliberately not ink width.
 */
internal data class P8TextRect(val left: Float,val top: Float,val right: Float,val bottom: Float) {
    fun valid()=listOf(left,top,right,bottom).all {it.isFinite()}&&left<=right&&top<=bottom
    fun inside(other: P8TextRect)=valid()&&other.valid()&&left>=other.left-0.05f&&top>=other.top-0.05f&&right<=other.right+0.05f&&bottom<=other.bottom+0.05f
}
internal data class P8TextLine(val start: Int,val end: Int,val bounds: P8TextRect,val ellipsized: Boolean)
internal data class P8TextCharacter(val offset: Int,val line: Int,val bounds: P8TextRect)
internal data class P8TextObservation(val text: String,val width: Int,val height: Int,val visible: P8TextRect,val lines: List<P8TextLine>,val characters: List<P8TextCharacter>)
internal fun p8ExactInput(expected: String,editable: String?,input: String?)=editable==expected&&input==expected

internal fun p8TextVisibilityFailures(expected: String,o: P8TextObservation,multiline: Boolean): List<String> = buildList {
    if(o.text!=expected)add("layout text differs from complete original UTF-16 content")
    val layout=P8TextRect(0f,0f,o.width.toFloat(),o.height.toFloat())
    if(o.width<=0||o.height<=0||!o.visible.valid()||o.visible.left>=o.visible.right||o.visible.top>=o.visible.bottom)add("invalid layout/visible clip")
    if(o.lines.isEmpty())add("no actual text lines")
    if(multiline&&o.lines.size<2)add("original identifier did not wrap")
    var end=0
    o.lines.forEachIndexed {index,line->
        if(line.start!=end||line.end<line.start||line.end>o.text.length)add("line $index omits/repeats a character range")
        end=line.end
        if(line.ellipsized)add("line $index is ellipsized")
        if(line.bounds.top>=line.bounds.bottom||!line.bounds.inside(layout)||!line.bounds.inside(o.visible))add("line $index exceeds layout/actual visible clip")
        if(index>0&&line.bounds.top<o.lines[index-1].bounds.bottom-0.05f)add("line $index overlaps prior line")
    }
    if(end!=o.text.length)add("last line omits original end characters")
    if(o.characters.map {it.offset}!=o.text.indices.toList())add("character bounds omit/repeat/reorder middle or end offsets")
    o.characters.forEach {c->
        val line=o.lines.getOrNull(c.line)
        if(line==null||c.offset !in line.start until line.end)add("character ${c.offset} has wrong line/range")
        if(!c.bounds.inside(layout)||!c.bounds.inside(o.visible)||line!=null&&!c.bounds.inside(line.bounds))add("character ${c.offset} exceeds line/layout/actual visible clip")
    }
    // A zero-width combining/joiner/surrogate unit is legal; a whole visible code point
    // without any nonzero box is not. Every UTF-16 unit still has a checked box above.
    var offset=0
    while(offset<o.text.length) {
        val cp=Character.codePointAt(o.text,offset);val length=Character.charCount(cp);val type=Character.getType(cp)
        val ink=!(Character.isWhitespace(cp)||Character.isISOControl(cp)||type==Character.FORMAT.toInt()||type==Character.NON_SPACING_MARK.toInt()||type==Character.ENCLOSING_MARK.toInt())
        if(ink&&o.characters.none {it.offset in offset until offset+length&&it.bounds.right>it.bounds.left&&it.bounds.bottom>it.bounds.top})add("code point at $offset has no visible box")
        offset+=length
    }
}
