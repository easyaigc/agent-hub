package com.agenthub.android

import androidx.compose.ui.text.AnnotatedString
import org.junit.Assert.*
import org.junit.Test

class P8TextVisibilityTest {
    private fun full(text: String="EasyWork",clip: P8TextRect=P8TextRect(0f,0f,163f,66f)): P8TextObservation {
        val unit=161.85715f/text.length
        return P8TextObservation(text,163,66,clip,listOf(P8TextLine(0,text.length,P8TextRect(0f,0f,161.85715f,66f),false)),text.indices.map {P8TextCharacter(it,0,P8TextRect(it*unit,0f,(it+1)*unit,66f))})
    }
    private fun reject(o: P8TextObservation,fragment: String,expected: String="EasyWork") {assertTrue(p8TextVisibilityFailures(expected,o,false).any {it.contains(fragment)})}
    @Test fun measured163PixelContentFitsEvenWhenParagraphConstraintIs388() {assertEquals(emptyList<String>(),p8TextVisibilityFailures("EasyWork",full(),false))}
    @Test fun measured220PixelContentFitsWithTheSame388PixelParagraphConstraint() {
        val base=full();val factor=218.10637f/161.85715f
        fun scale(r: P8TextRect)=P8TextRect(r.left*factor,0f,r.right*factor,89f)
        val sample=base.copy(width=220,height=89,visible=P8TextRect(0f,0f,220f,89f),lines=base.lines.map {it.copy(bounds=scale(it.bounds))},characters=base.characters.map {it.copy(bounds=scale(it.bounds))})
        assertEquals(emptyList<String>(),p8TextVisibilityFailures("EasyWork",sample,false))
    }
    @Test fun contiguousWrappedTextChecksEveryLineAndEveryCharacter() {
        val text="abcdefgh";val lines=listOf(P8TextLine(0,4,P8TextRect(0f,0f,80f,30f),false),P8TextLine(4,8,P8TextRect(0f,30f,80f,60f),false))
        val chars=text.indices.map {P8TextCharacter(it,it/4,P8TextRect(it%4*20f,it/4*30f,(it%4+1)*20f,(it/4+1)*30f))}
        assertEquals(emptyList<String>(),p8TextVisibilityFailures(text,P8TextObservation(text,80,60,P8TextRect(0f,0f,80f,60f),lines,chars),true))
    }
    @Test fun surrogateAndCombiningUnitsRetainExactOffsets() {assertEquals(emptyList<String>(),p8TextVisibilityFailures("A😀é",full("A😀é"),false))}
    @Test fun originalLastCharacterCannotDisappear()=reject(full("EasyWor"),"complete original")
    @Test fun originalMiddleCannotBeRemovedKeepingEndpoints()=reject(full("EayWork"),"complete original")
    @Test fun missingMiddleCharacterBoundsFailWithFullLayoutText()=reject(full().let {it.copy(characters=it.characters.filter {c->c.offset!=3})},"middle or end offsets")
    @Test fun lastLineCannotStopBeforeOriginalEnd()=reject(full().let {it.copy(lines=listOf(it.lines.single().copy(end=7)))},"end characters")
    @Test fun skippedMiddleLineRangeFails()=reject(full().copy(lines=listOf(P8TextLine(0,3,P8TextRect(0f,0f,60f,30f),false),P8TextLine(4,8,P8TextRect(0f,30f,80f,66f),false))),"omits/repeats")
    @Test fun ellipsisFailsEvenWithAllOffsetsPresent()=reject(full().let {it.copy(lines=listOf(it.lines.single().copy(ellipsized=true)))},"ellipsized")
    @Test fun actualAncestorClipRejectsOnePixelOfHorizontalLoss()=reject(full(clip=P8TextRect(0f,0f,160.85715f,66f)),"actual visible clip")
    @Test fun bottomClipRejectsOnePixelOfVerticalLoss()=reject(full(clip=P8TextRect(0f,0f,163f,65f)),"actual visible clip")
    @Test fun glyphOverhangFailsEvenIfLineBoundsFit()=reject(full().let {it.copy(characters=it.characters.map {c->if(c.offset==3)c.copy(bounds=c.bounds.copy(left=-1f))else c})},"character 3 exceeds")
    @Test fun omittedWrappedLastLineFails()=reject(full().copy(lines=listOf(P8TextLine(0,4,P8TextRect(0f,0f,80f,30f),false))),"end characters")
    @Test fun multilineRequiresMoreThanOneActualLine() {assertTrue(p8TextVisibilityFailures("EasyWork",full(),true).contains("original identifier did not wrap"))}
    @Test fun invisibleWholeCodePointFails()=reject(full().let {it.copy(characters=it.characters.map {c->if(c.offset==3)c.copy(bounds=c.bounds.copy(right=c.bounds.left))else c})},"no visible box")
    @Test fun nonfiniteGeometryCannotPass()=reject(full().let {it.copy(characters=it.characters.map {c->if(c.offset==3)c.copy(bounds=c.bounds.copy(left=Float.NaN))else c})},"character 3 exceeds")
    @Test fun annotationMetadataMayDifferWhileBothTextChannelsStayExact() {
        val annotated=AnnotatedString.Builder("short").apply {addStringAnnotation("synthetic-ime","composition",0,5)}.toAnnotatedString()
        assertNotEquals(AnnotatedString("short"),annotated)
        assertTrue(p8ExactInput("short",annotated.text,annotated.text))
        assertFalse(p8ExactInput("short","shrt",annotated.text));assertFalse(p8ExactInput("short",annotated.text,"shOrt"));assertFalse(p8ExactInput("short",annotated.text,null))
    }
}
