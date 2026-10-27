package com.agenthub.android

import android.os.Build
import android.os.ParcelFileDescriptor
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import java.io.File

/** Real native prompt, owned SDK only. Never grants photo access or operates a user's phone permission UI. */
class MobilePhotoAuthorizationSystemTest {
    @get:Rule(order=0)val foreground: org.junit.rules.TestRule=NativeTestForeground()
    @get:Rule(order=1)val ui=createAndroidComposeRule<MainActivity>()
    private val i=InstrumentationRegistry.getInstrumentation()
    private fun nodes(owner: String): List<AccessibilityNodeInfo> {
        val root=i.uiAutomation.rootInActiveWindow ?: return emptyList()
        if(root.packageName?.toString()!=owner)return emptyList()
        val result=mutableListOf<AccessibilityNodeInfo>()
        fun visit(n: AccessibilityNodeInfo) { result.add(n);for(k in 0 until n.childCount)n.getChild(k)?.let(::visit) };visit(root);return result
    }
    @Test fun firstPanelRequestsOnceAndDenialLeavesSystemPickerAvailableWithoutReopeningPrompt() {
        require(Build.HARDWARE in listOf("ranchu","goldfish"))
        require(InstrumentationRegistry.getArguments().getString("ownedPhotoPermission")=="1")
        ui.waitUntil(30_000) { !ui.activity.session.restoring };val activity=ui.activity
        val source=NativeRecentPhotos(activity);assertEquals(NativePhotoAccess.NONE,source.access())
        val a=RecentPhotoHintAcceptance(activity,{ condition -> ui.waitUntil(20_000,condition) },source)
        var permissionOwner: String?=null
        try {
            val authorization=a.session.photoAuthorization!!;val draft=a.session.text
            Thread.sleep(350);assertFalse(authorization.busy);assertNull(authorization.launchId)
            ui.onNodeWithTag("composer-add").performClick()
            val allowed=setOf("com.android.permissioncontroller","com.google.android.permissioncontroller","com.android.packageinstaller","com.google.android.packageinstaller")
            var owner: String?=null
            // Advance the Compose frame clock while waiting for the sheet's deferred native launch.
            try { ui.waitUntil(15_000) { owner=i.uiAutomation.rootInActiveWindow?.packageName?.toString();owner in allowed } }
            finally { File(a.owned.folder,"photo-permission-launch-state.json").writeText(JSONObject().put("owner",owner).put("busy",authorization.busy).put("launchIdPresent",authorization.launchId!=null).put("failed",authorization.failed).toString()) }
            val pkg=checkNotNull(owner);assertTrue("Exact system permission owner required",pkg in allowed);permissionOwner=pkg
            val info=activity.packageManager.getApplicationInfo(pkg,0)
            assertTrue(info.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM!=0);assertNotEquals(activity.applicationInfo.uid,info.uid)
            val state=ParcelFileDescriptor.AutoCloseInputStream(i.uiAutomation.executeShellCommand("dumpsys activity activities")).use { it.bufferedReader().readText() }
            File(a.owned.folder,"photo-permission-activities.txt").writeText(state)
            val exact=state.split(Regex("\\* Hist\\s+#\\d+:" )).firstOrNull { it.substringBefore('\n').contains("$pkg/")&&it.contains(Regex("launchedFromPackage="+Regex.escape(activity.packageName)+"(?:\\s|$)"))&&it.contains(Regex("launchedFromUid="+activity.applicationInfo.uid+"(?:\\s|$)")) }
            File(a.owned.folder,"photo-permission-caller.json").writeText(JSONObject().put("owner",pkg).put("caller",activity.packageName).put("exactCaller",exact!=null).put("systemUid",info.uid).put("permissionGroup",source.permissions().toList()).toString())
            assertNotNull("Permission request must be launched by this acceptance application",exact)
            assertTrue(authorization.busy);val controls=nodes(pkg)
            File(a.owned.folder,"photo-permission-controls.txt").writeText(controls.joinToString("\n") { "${it.viewIdResourceName}|${it.text}" })
            val deny=controls.filter { it.isVisibleToUser&&it.isEnabled&&it.viewIdResourceName?.substringAfterLast('/')=="permission_deny_button" }
            assertEquals("Only the exact native deny action is eligible",1,deny.size)
            var target: AccessibilityNodeInfo?=deny.single();while(target!=null&&target.actionList.none { it.id==AccessibilityNodeInfo.ACTION_CLICK })target=target.parent
            assertEquals(pkg,checkNotNull(target).packageName?.toString());assertTrue(target.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            ui.waitUntil(15_000) { !authorization.busy&&i.uiAutomation.rootInActiveWindow?.packageName?.toString()==activity.packageName }
            assertEquals(NativePhotoAccess.NONE,source.access());ui.onNodeWithTag("recent-photo-permission").assertDoesNotExist()
            ui.onNodeWithTag("recent-photo-settings").assertIsEnabled();ui.onNodeWithTag("attachment-panel-list").performScrollToNode(hasTestTag("choose-photos"));ui.onNodeWithTag("choose-photos").assertIsEnabled()
            a.owned.dialogShot("first-request-denied")
            ui.onNodeWithTag("attachment-panel-close").performClick();ui.onNodeWithTag("composer-add").performClick()
            Thread.sleep(650);ui.waitForIdle();assertFalse(authorization.busy);assertNull(authorization.launchId)
            ui.onNodeWithTag("attachment-panel").assertIsDisplayed();ui.onNodeWithTag("recent-photo-permission").assertDoesNotExist();a.owned.dialogShot("denied-reopen-no-prompt")
            ui.onNodeWithTag("attachment-panel-close").performClick();assertEquals(draft,a.session.text);assertTrue(a.session.attachments.isEmpty())
            File(a.owned.folder,"native-photo-authorization-proof.json").writeText(JSONObject().put("firstPanelAutomatic",true).put("denied",true).put("reopenDidNotRequest",true).put("draftPreserved",true).put("uploads",0).put("modelInputs",0).toString())
        } finally {
            if(permissionOwner?.let { nodes(it).isNotEmpty() }==true)i.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
            a.finish()
        }
    }
}
