package com.agenthub.android

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.security.MessageDigest

/** Real, independently selected desktop history. All identifiers arrive privately at runtime. */
@RunWith(AndroidJUnit4::class)
class MobileRealReadTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    @Test fun nativeReadMatchesIndependentlyObservedDesktopReply() {
        val args=InstrumentationRegistry.getArguments()
        val endpoint=args.getString("realReadEndpoint") ?: error("A real readonly Host endpoint is required")
        val thread=args.getString("realReadThread") ?: error("An independently selected thread is required")
        val replyHash=args.getString("realReadReplyHash") ?: error("An independent reply hash is required")
        val session=ui.activity.session
        ui.runOnIdle { session.resetAcceptance() }
        ui.onNodeWithTag("onboarding-add").performClick()
        ui.onNodeWithTag("codex-connection-method").performClick()
        ui.onNodeWithTag("codex-method-手动连接").performClick()
        ui.onNodeWithTag("client-name").performTextReplacement("本机只读 DEV")
        ui.onNodeWithTag("client-address").performTextReplacement(endpoint)
        ui.onNodeWithTag("connect-client").performScrollTo().performClick()
        ui.waitUntil(60_000) { session.hasClient && !session.loading }
        assertEquals("history-only",session.selectedClient.mode)
        assertFalse(session.selectedClient.isDemo)
        ui.onNodeWithTag("sheet-done").performClick()
        ui.onNodeWithTag("open-sidebar").performClick()
        if(session.threads.size>5) {
            ui.onNodeWithTag("sidebar-list").performScrollToNode(hasTestTag("sidebar-more-recent"))
            ui.onNodeWithTag("sidebar-more-recent").performClick()
        }
        ui.onNodeWithTag("sidebar-list").performScrollToNode(hasTestTag("recent-thread-$thread"))
        ui.onNodeWithTag("recent-thread-$thread").performClick()
        ui.waitUntil(60_000) { session.selectedThread==thread && session.messages.isNotEmpty() && !session.historyLoading }
        fun sha(text: String)=MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        val reply=session.messages.single { it.role=="assistant" && sha(it.text)==replyHash }
        ui.onNodeWithTag("message-list").performScrollToNode(hasTestTag("message-${reply.id}"))
        ui.onNodeWithTag("message-${reply.id}").assertIsDisplayed()
        ui.onNodeWithTag("message-input").performTextInput("只留在当前模拟器的草稿")
        ui.onNodeWithTag("send-message").assertIsNotEnabled()
        ui.onNodeWithTag("readonly-notice").assertIsDisplayed()
        assertEquals(thread,session.selectedThread)
        // No screenshots or conversation exports: only the private independent digest is compared.
    }
}
