package com.agenthub.android

import org.junit.Test
import org.junit.Assert.*

/** Gap, identity, disclosure/reflow and capacity counterexamples for the production coverage core. */
class NativeNoticeReadCoverageTest {
    private val original=NativeNoticePanelOriginal.Plan(MobileProposedPlan("request","run","Original plan"))
    private fun document(vararg fields: Pair<String,String>)=NativeNoticeReadDocument.create(original,fields.toList())!!
    private fun ready(value: NativeNoticeReadDocument)=NativeNoticeReadCoverage().apply {
        install(value);value.parts.forEach { layout(it.id,Any()) }
    }
    @Test fun topAndBottomNeverFillAnUnobservedMiddleEvenWithRepeatedText() {
        val d=document("body" to "same".repeat(100));val c=ready(d);val p=d.parts.single()
        assertTrue(c.accept(p,0,100));assertTrue(c.accept(p,300,400));assertFalse(c.complete())
        c.accept(p,200,300);assertFalse(c.complete());c.accept(p,100,200);assertTrue(c.complete())
    }
    @Test fun everyDeclaredSemanticPartIsRequiredIncludingUnmountedCode() {
        val d=document("title" to "Heading","code" to "long code","tail" to "Tail");val c=ready(d)
        for(p in d.parts.filter { it.id!="code" })c.accept(p,0,p.length)
        assertFalse(c.complete());val code=d.parts[1];c.accept(code,0,code.length);assertTrue(c.complete())
        c.remove("code");assertFalse(c.complete())
    }
    @Test fun layoutReplacementResetsAllEarlierPartsAndTheSameLayoutDoesNot() {
        val d=document("one" to "First","two" to "Second");val c=NativeNoticeReadCoverage();c.install(d)
        val layout=Any();c.layout("one",layout);c.layout("two",Any());d.parts.forEach { c.accept(it,0,it.length) };assertTrue(c.complete())
        c.layout("one",layout);assertTrue(c.complete());c.layout("two",Any());assertFalse(c.complete())
        c.accept(d.parts[1],0,d.parts[1].length);assertFalse(c.complete())
    }
    @Test fun aDifferentRunRequestOrOriginalDocumentCannotBorrowCoverage() {
        val d=document("body" to "Same text");val c=ready(d);val part=d.parts.single();c.accept(part,0,part.length);assertTrue(c.complete())
        for(value in listOf(original.value.copy(turnId="new-run"),original.value.copy(id="new-request"),original.value.copy(text="New original"))) {
            c.install(NativeNoticeReadDocument.create(NativeNoticePanelOriginal.Plan(value),listOf("body" to "Same text")))
            assertFalse(c.complete())
        }
    }
    @Test fun malformedWrongVersionAndOutOfBoundsRangesFailClosed() {
        val d=document("body" to "Emoji 😀");val c=ready(d);val p=d.parts.single()
        assertFalse(c.accept(p.copy(version="0".repeat(64)),0,p.length));assertFalse(c.accept(p,-1,2))
        assertFalse(c.accept(p,0,p.length+1));assertFalse(c.accept(p,2,2));assertFalse(c.complete())
        c.accept(p,0,p.length);assertTrue(c.complete());c.reset();assertFalse(c.complete())
    }
    @Test fun capacityAndDuplicateIdsDoNotProduceAnEmptyOrPartialSuccessfulDocument() {
        assertNull(NativeNoticeReadDocument.create(original,emptyList()))
        assertNull(NativeNoticeReadDocument.create(original,listOf("body" to " \n\t")))
        assertNull(NativeNoticeReadDocument.create(original,listOf("body" to "one","body" to "two")))
        assertNull(NativeNoticeReadDocument.create(original,listOf("body" to "x".repeat(NativeNoticeReadDocument.MAX_CHARACTERS+1))))
        assertNull(NativeNoticeReadDocument.create(original,(0..NativeNoticeReadDocument.MAX_PARTS).map { "$it" to "x" }))
    }
    @Test fun formattingPrefixCannotBridgeAGapOrUseAnotherLayoutOrVersion() {
        val d=document("body" to "Original middle tail\n");val c=ready(d);val p=d.parts.single()
        c.accept(p,0,8);c.accept(p,15,p.length-1)
        assertFalse(c.contains(p,0,p.length-1));assertTrue(c.contains(p,15,p.length-1))
        c.accept(p,8,15);assertTrue(c.contains(p,0,p.length-1));assertFalse(c.complete())
        assertFalse(c.contains(p.copy(version="different"),0,1));assertFalse(c.contains(p,-1,0));assertFalse(c.contains(p,0,p.length+1))
        c.layout(p.id,Any());assertFalse(c.contains(p,0,p.length-1));assertFalse(c.complete())
    }
}
