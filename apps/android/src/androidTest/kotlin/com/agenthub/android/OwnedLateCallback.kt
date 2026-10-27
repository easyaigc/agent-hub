package com.agenthub.android

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import org.json.JSONObject
import org.junit.Assert.*
import java.io.File

/** Delays only the unchanged real response; controlled local navigation is separate from UI taps. */
internal fun verifyOwnedLateCallback(ui: ComposeContentTestRule,owned: OwnedControlAcceptance,projectId: String,nonce: String,approval: MobileApproval,evidence: File,control: (String,JSONObject)->JSONObject) {
    val session=owned.session;val a=checkNotNull(session.threads.singleOrNull { it.id==session.selectedThread });val before=session.text
    fun message()=JSONObject().put("threadId",a.id).put("approvalId",approval.id)
    fun node(tag: String,name: String) {
        owned.nodeShot(ui.onNodeWithTag(tag).onParent(),name)
        for(ext in listOf(".png","-window.json"))File(owned.folder,name+ext).copyTo(File(evidence,name+ext))
    }
    control("callback-held",message())
    ui.runOnIdle { session.newChat(projectId);session.text="Independent callback B $nonce" };val b=session.targetKey
    node("composer","late-callback-B")
    ui.runOnIdle { session.selectThread(a) }
    ui.waitUntil(30_000) { !session.historyLoading&&session.runtime?.approvals?.none { it.id==approval.id }==true }
    val newer="Newer callback error $nonce";val draft="Newer callback A draft $nonce"
    ui.runOnIdle { session.text=draft;session.error=newer }
    control("release-callback",message())
    val until=System.currentTimeMillis()+1500
    while(System.currentTimeMillis()<until) { ui.waitForIdle();assertEquals(draft,session.text);assertEquals(newer,session.error);Thread.sleep(50) }
    ui.onNodeWithTag("session-error").assertIsDisplayed();node("session-error","late-callback-keeps-newer-error")
    ui.onNodeWithTag("dismiss-error").performClick();ui.onNodeWithTag("message-input").performTextReplacement(before)
    ui.runOnIdle { session.newChat(projectId) };assertEquals(b,session.targetKey);assertEquals("Independent callback B $nonce",session.text)
    ui.runOnIdle { session.selectThread(a) };ui.waitUntil(30_000) { !session.historyLoading&&session.runtime!=null }
    assertEquals(before,session.text);assertNull(session.error)
    control("callback-complete",message().put("draftProtected",true).put("newerErrorProtected",true).put("bDraftProtected",true))
}
