package com.agenthub.android

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import org.junit.Assert.*

/** Real Android Compose text shaping and production witness function, without an Activity, fake
 * glyph boxes, OS permission, Host or model. These geometry probes do not replace native UI guards. */
class NativeNoticeTextWitnessTest {
    private val original=NativeNoticePanelOriginal.Plan(MobileProposedPlan("request","run","Original"))
    private fun layout(text: String,width: Int=240,wrap: Boolean=true,lines: Int=Int.MAX_VALUE,overflow: TextOverflow=TextOverflow.Clip): TextLayoutResult {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val density=Density(context.resources.displayMetrics.density,context.resources.configuration.fontScale)
        return TextMeasurer(createFontFamilyResolver(context),density,LayoutDirection.Ltr).measure(
            AnnotatedString(text),TextStyle(fontSize=14.sp),overflow=overflow,softWrap=wrap,maxLines=lines,constraints=Constraints(maxWidth=width))
    }
    private class Reading(val document: NativeNoticeReadDocument,val layout: TextLayoutResult) {
        val part=document.parts.single()
        val coverage=NativeNoticeReadCoverage().apply { install(document);layout(part.id,this@Reading.layout) }
        var formatting=0
        fun sample(clip: Rect) {
            val prefix: (Int,Int)->Boolean={start,end->coverage.contains(part,start,end)}
            for(index in 0 until part.length) {
                val witness=nativeNoticeGlyphWitness(layout,index,clip,prefix) ?: continue
                if(witness==NativeNoticeGlyphWitness.LINE_FORMATTING)formatting++
                coverage.accept(part,index,index+1)
            }
        }
        fun all()=Rect(0f,0f,layout.size.width.toFloat(),layout.size.height.toFloat())
    }
    private fun reading(text: String,layout: TextLayoutResult=layout(text))=Reading(NativeNoticeReadDocument.create(original,listOf("body" to text))!!,layout)
    @Test fun wrappedLineEndSpacesAndFinalLinefeedRequireRealLineWitnesses() {
        val text="Original immutable paragraph. ".repeat(30)+"\n"
        val value=reading(text);assertTrue(value.layout.lineCount>5)
        value.sample(value.all());assertTrue(value.formatting>1)
        assertEquals(text.length,value.coverage.coveredCharacters());assertTrue(value.coverage.complete())
    }
    @Test fun horizontalTerminalLinefeedCannotBridgeAnUnseenMiddle() {
        val text="abcdefghijklmnopqrstuvwxyz ".repeat(12)+"\n"
        val value=reading(text,layout(text,width=20_000,wrap=false));val full=value.all();val width=120f
        assertTrue(full.width>width*3)
        value.sample(Rect(0f,0f,width,full.bottom));value.sample(Rect(full.right-width,0f,full.right,full.bottom))
        assertFalse(value.coverage.complete());assertFalse(value.coverage.contains(value.part,text.lastIndex,text.length))
        var left=0f
        while(left<full.right) { value.sample(Rect(left,0f,minOf(left+width,full.right),full.bottom));left+=width*.5f }
        value.sample(Rect(full.right-width,0f,full.right,full.bottom))
        assertTrue(value.formatting>0);assertTrue(value.coverage.complete())
    }
    @Test fun ellipsisAndMaxLineClippingCannotBorrowFormattingOrHiddenGlyphs() {
        val text="Original hidden middle and trailing whitespace. ".repeat(30)+"\n"
        for(overflow in listOf(TextOverflow.Ellipsis,TextOverflow.Clip)) {
            val value=reading(text,layout(text,lines=1,overflow=overflow));value.sample(value.all())
            assertFalse(value.coverage.complete());assertFalse(value.coverage.contains(value.part,text.lastIndex,text.length))
        }
    }
    @Test fun blankLineFormattingStillRequiresItsOwnVisibleVerticalGeometry() {
        val text="First original.\n \t \nLast original.\n";val value=reading(text);val r=value.layout
        assertTrue(r.lineCount>=3)
        val blank=r.getLineStart(1);val end=r.getLineEnd(1,false)
        value.sample(Rect(0f,0f,r.size.width.toFloat(),r.getLineBottom(0)))
        assertFalse(value.coverage.contains(value.part,blank,end));assertFalse(value.coverage.complete())
        value.sample(value.all());assertTrue(value.coverage.contains(value.part,blank,end));assertTrue(value.coverage.complete())
    }
}
