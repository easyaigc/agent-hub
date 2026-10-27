package com.agenthub.android

import androidx.test.platform.app.InstrumentationRegistry
import org.commonmark.ext.gfm.tables.TableBlock
import org.commonmark.node.HtmlBlock
import org.junit.Test
import org.junit.Assert.*
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/** Actual Android parser/decoder and bounded files; no app session or external resource requests. */
class NativeFilePreviewDocumentTest {
    private fun <T> file(bytes: ByteArray, check: (File)->T): T {
        val path=File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,"owned-preview-${UUID.randomUUID()}.md")
        try { path.writeBytes(bytes);return check(path) } finally { assertTrue(path.delete()) }
    }
    private fun reject(bytes: ByteArray)=file(bytes) { path ->
        try { loadPreviewDocument(path);fail("Invalid document accepted") }catch(_: IllegalArgumentException) { }
    }
    @Test fun gfmOriginalCrlfDuplicateChineseAnchorsAndRawHtmlStayNativeData() {
        val source="# 标题\r\n\r\n| a | b |\r\n| --- | --- |\r\n| a\\|b | `code` |\r\n\r\n- [x] checked\r\n\r\n# 标题\r\n\r\n<script>synthetic()</script>\r\n\r\n```txt\r\nfirst\r\nsecond\r\n```\r\n"
        file(source.toByteArray()) { path ->
            val doc=loadPreviewDocument(path);assertEquals(source,doc.source)
            assertEquals(listOf("标题","标题-1"),doc.headings.map { it.anchor })
            assertTrue(doc.blocks.any { it is TableBlock });assertTrue(doc.blocks.any { it is HtmlBlock })
            assertEquals(MessageDigest.getInstance("SHA-256").digest(path.readBytes()).joinToString("") { "%02x".format(it) },doc.digest)
            val fence=doc.blocks.filterIsInstance<org.commonmark.node.FencedCodeBlock>().single()
            assertEquals("first\r\nsecond\r\n",NativeMarkdown.codeBody(fence,doc.source))
        }
    }
    @Test fun utf16AndUtf8BomAreReadableWithoutLosingOriginalSource() {
        val source="# 中文\r\n\r\n原文\r\n\r\n```txt\r\nfirst\r\nsecond\r\n```\r\n"
        file(source.toByteArray(Charsets.UTF_16)) { assertEquals(source,loadPreviewDocument(it).source) }
        file(byteArrayOf(0xEF.toByte(),0xBB.toByte(),0xBF.toByte())+source.toByteArray()) {
            val doc=loadPreviewDocument(it);assertEquals("\uFEFF"+source,doc.source);assertEquals("中文",doc.headings.first().title)
            assertEquals("first\r\nsecond\r\n",NativeMarkdown.codeBody(doc.blocks.filterIsInstance<org.commonmark.node.FencedCodeBlock>().single(),doc.renderSource))
        }
    }
    @Test fun malformedEncodingNulByteLimitAndDeepStructureRejectBeforeRendering() {
        reject(byteArrayOf(0xc3.toByte(),0x28));reject("# text\u0000secret".toByteArray())
        reject(ByteArray(2*1024*1024+1) { 65 });reject(("> ".repeat(70)+"# deep").toByteArray())
    }
}
