package com.agenthub.android

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.text.TextLayoutResult

internal enum class NativeNoticeGlyphWitness { INK, SPACE, LINE_FORMATTING }

/** A local-layout witness, independent of scroll offsets. Android's visible line end excludes
 * trailing wrap spaces and linefeeds: those offsets have no reliable painted glyph box. They
 * require this exact line's already observed prefix AND its visible terminal glyph (or a visible
 * blank line). No missing glyph, hidden ellipsis or earlier layout can be filled by formatting. */
internal fun nativeNoticeGlyphWitness(result: TextLayoutResult,index: Int,clip: Rect,
    prefixCovered: (Int,Int)->Boolean): NativeNoticeGlyphWitness? {
    if(index !in result.layoutInput.text.indices || !listOf(clip.left,clip.top,clip.right,clip.bottom).all(Float::isFinite) || clip.width<=0 || clip.height<=0)return null
    val line=result.getLineForOffset(index);val visibleEnd=result.getLineEnd(line,true)
    if((result.isLineEllipsized(line) || result.didOverflowHeight && line==result.lineCount-1) && index>=visibleEnd)return null
    fun visibleBox(offset: Int): Rect? {
        val box=result.getBoundingBox(offset)
        val top=if(box.height>0)box.top else result.getLineTop(line);val bottom=if(box.height>0)box.bottom else result.getLineBottom(line)
        return box.takeIf { listOf(it.left,it.right,top,bottom).all(Float::isFinite) && it.right>=it.left && bottom>top &&
            it.left>=clip.left && it.right<=clip.right && top>=clip.top && bottom<=clip.bottom }
    }
    val char=result.layoutInput.text[index]
    if(char.isWhitespace() && index>=visibleEnd && index<result.getLineEnd(line,false)) {
        val start=result.getLineStart(line)
        if(!prefixCovered(start,visibleEnd))return null
        val top=result.getLineTop(line);val bottom=result.getLineBottom(line)
        if(!top.isFinite() || !bottom.isFinite() || bottom<=top || top<clip.top || bottom>clip.bottom)return null
        if(visibleEnd>start) {
            if(visibleBox(visibleEnd-1)==null)return null
        } else {
            val x=result.getLineLeft(line)
            if(!x.isFinite() || x<clip.left || x>clip.right)return null
        }
        return NativeNoticeGlyphWitness.LINE_FORMATTING
    }
    val box=visibleBox(index) ?: return null
    return if(box.width>0 && !char.isWhitespace())NativeNoticeGlyphWitness.INK else NativeNoticeGlyphWitness.SPACE
}
