package com.agenthub.android

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.test.ext.junit.rules.ActivityScenarioRule
import org.json.JSONObject
import org.junit.Assert.*
import java.io.File

/** Compare actual clipped and unclipped text/button bounds after native bring-into-view. */
internal fun verifyApprovalViewport(ui: AndroidComposeTestRule<ActivityScenarioRule<MainActivity>,MainActivity>,approval: MobileApproval,folder: File,label: String,capture: (String)->Unit={}) {
    val geometry=JSONObject().put("approvalId",approval.id).put("turnId",approval.turnId).put("messageViewport",ui.onNodeWithTag("message-list").getUnclippedBoundsInRoot().toString()).put("approvalBounds",ui.onNodeWithTag("approval-${approval.id}").getUnclippedBoundsInRoot().toString())
    fun full(node: SemanticsNodeInteraction,name: String) {
        node.assertIsDisplayed()
        val raw=node.getUnclippedBoundsInRoot();val visible=node.fetchSemanticsNode().boundsInRoot;val density=ui.density.density
        val expectedWidth=(raw.right.value-raw.left.value)*density;val expectedHeight=(raw.bottom.value-raw.top.value)*density
        geometry.put(name,JSONObject().put("visibleWidth",visible.width).put("visibleHeight",visible.height).put("fullWidth",expectedWidth).put("fullHeight",expectedHeight))
        val overlaps=ui.onAllNodesWithTag("scroll-latest").fetchSemanticsNodes().count {it.boundsInRoot.overlaps(visible)}
        geometry.getJSONObject(name).put("floatingLatestOverlaps",overlaps)
        File(folder,"$label-geometry.json").writeText(geometry.toString())
        assertEquals("$name must avoid the floating return-to-latest control",0,overlaps)
        assertTrue("$name must retain its whole text/target rectangle: $visible vs $raw",visible.width>=expectedWidth-1.5f&&visible.height>=expectedHeight-1.5f)
    }
    for(tag in listOf("permission-settings","generation-settings","reply-mode-queue","reply-mode-steer")) {
        if(ui.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()) {
            val node=ui.onNodeWithTag(tag);full(node,"control-$tag");assertTrue("Native control keeps44dp touch height",node.getUnclippedBoundsInRoot().let {it.bottom.value-it.top.value}>=43.5f)
        }
    }
    val sequential=ui.density.fontScale>1f
    geometry.put("visibilityScope",if(sequential)"Large content: title and each choice fully readable through native scrolling; not all simultaneously" else "Title and all choices simultaneously visible")
    if(sequential) {
        for(text in listOf("需要审批",approval.title)) {
            ui.onNodeWithText(text,useUnmergedTree=true).performScrollTo()
            full(ui.onNodeWithText(text,useUnmergedTree=true),if(text==approval.title)"original-title" else "heading")
            capture(if(text==approval.title)"original-title" else "heading")
        }
    }
    approval.decisions.forEach { choice ->
        ui.onNodeWithTag("approval-$choice").performScrollTo().assertIsEnabled()
        if(!sequential) {
            full(ui.onNodeWithText("需要审批",useUnmergedTree=true),"heading-after-$choice")
            full(ui.onNodeWithText(approval.title,useUnmergedTree=true),"original-title-after-$choice")
        }
        (if(sequential)listOf(choice)else approval.decisions).forEach {
            val button=ui.onNodeWithTag("approval-$it");full(button,"choice-$it-after-$choice")
            assertTrue("Approval choice keeps44dp touch target",button.getUnclippedBoundsInRoot().let {it.bottom.value-it.top.value>=43.5f&&it.right.value-it.left.value>=43.5f})
        }
        capture("choice-$choice")
    }
}

/** Locks only a chosen acceptance viewport before Activity launch and releases it after the test. */
internal class NativeApprovalViewportRule(private val orientation: ()->String,private val folder: ()->File,private val label: ()->String): org.junit.rules.ExternalResource() {
    private var held=false
    private val i get()=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
    private fun release() {
        if(!held)return
        val ok=i.uiAutomation.setRotation(android.app.UiAutomation.ROTATION_UNFREEZE);held=false
        File(folder(),"viewport-${label()}-restored.json").writeText(JSONObject().put("restored",ok).toString());assertTrue(ok)
    }
    override fun before() {
        val value=orientation();if(value.isEmpty())return;require(value in listOf("portrait","landscape"))
        try {
            held=i.uiAutomation.setRotation(if(value=="landscape")android.app.UiAutomation.ROTATION_FREEZE_90 else android.app.UiAutomation.ROTATION_FREEZE_0);assertTrue(held)
            // Application resources may still describe the previous Activity before launch.
            // setRotation is acknowledged here; the launched Activity verifies its own configuration.
            i.waitForIdleSync()
            File(folder(),"viewport-${label()}-frozen.json").writeText(JSONObject().put("orientation",value).put("scope","fixed viewport, not configuration-change recovery").toString())
        } catch(failure: Throwable) { try {release()}catch(cleanup: Throwable){failure.addSuppressed(cleanup)};throw failure }
    }
    override fun after()=release()
}
