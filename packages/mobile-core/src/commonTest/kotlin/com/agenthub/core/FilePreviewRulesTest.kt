package com.agenthub.core

import kotlin.test.*

class FilePreviewRulesTest {
    @Test fun explicitMarkdownTypesAndBoundedParsing() {
        assertEquals("markdown", FilePreviewRules.renderer("REPORT.MD", "application/octet-stream"))
        assertEquals("markdown", FilePreviewRules.renderer("report", "text/markdown; charset=utf-8"))
        assertEquals("system", FilePreviewRules.renderer("main.swift", "text/plain"))
        assertEquals("system", FilePreviewRules.renderer("report.md.png", "image/png"))
        assertTrue(FilePreviewRules.canParse(0))
        assertTrue(FilePreviewRules.canParse(2L * 1024 * 1024))
        assertFalse(FilePreviewRules.canParse(2L * 1024 * 1024 + 1))
        assertFalse(FilePreviewRules.canParse(-1))
    }
    @Test fun documentLinksCannotAcquireComputerOrActiveContentAccess() {
        for (value in listOf("../secret.md", "file:///etc/passwd", "sandbox:/report.md", "javascript:alert(1)",
            "data:text/html,test", "https://user:password@example.com", "http://localhost:8080", "http://127.0.0.1/", "http://[::1]/", "https://example.com\\@localhost/"))
            assertEquals("unavailable", FilePreviewRules.linkKind(value), value)
        assertEquals("anchor", FilePreviewRules.linkKind("#章节"))
        assertEquals("web", FilePreviewRules.linkKind("https://example.com/doc#chapter"))
        assertEquals("external", FilePreviewRules.linkKind("mailto:team@example.com"))
    }
}
