package com.agenthub.android

import org.junit.Test
import org.junit.Assert.*

class NativeMarkdownNoticeProjectionTest {
    private fun document(text: String)=NativeMarkdownNoticeProjection.document(
        NativeNoticePanelOriginal.Plan(MobileProposedPlan("request","run",text)),text,NativeMarkdown.parse(text),"plan","Code")
    @Test fun projectedTextIsBoundToTheActualOriginalSource() {
        val original=NativeNoticePanelOriginal.Plan(MobileProposedPlan("request","run","Old original"))
        assertNull(NativeMarkdownNoticeProjection.document(original,"New text",NativeMarkdown.parse("New text"),"plan","Code"))
    }
    @Test fun unsupportedHiddenLinkOrImageDestinationsRemainUnread() {
        assertNull(document("[Visible label](https://example.invalid/hidden-original)"))
        assertNull(document("![Alternative](https://example.invalid/hidden-image)"))
    }
    @Test fun codeKeepsOriginalPayloadAndEveryListAndTableLeafIsDeclared() {
        val text="# Heading\n\n- First\n- Second\n\n```diff\n+ added\r\n- removed\n```\n\n| A | B |\n| --- | --- |\n| C | D |"
        val parts=checkNotNull(document(text)).parts
        assertTrue(parts.any { it.version==readingSHA("+ added\r\n- removed\n") })
        for(value in listOf("Heading","First","Second","A","B","C","D"))assertTrue(value,parts.any { it.version==readingSHA(value) })
        assertEquals(2,parts.count { it.id.contains("marker") });assertEquals(1,parts.count { it.id.endsWith("code-text") })
    }
    @Test fun inlineFormattingUsesTheActualRendererProjectionWithoutInventingRawMarkup() {
        val parts=checkNotNull(document("**Original bold** with `source()`")).parts
        assertEquals(listOf(readingSHA("Original bold with source()")),parts.map { it.version })
    }
    @Test fun missingSemanticTextAndOversizedProjectionNeverBecomeAnEmptySuccess() {
        assertNull(document("> ".repeat(100)+"Nested"));assertNull(document("---"));assertNull(document("```\n\n```"));assertNull(document("x".repeat(NativeNoticeReadDocument.MAX_CHARACTERS+1)))
    }
}
