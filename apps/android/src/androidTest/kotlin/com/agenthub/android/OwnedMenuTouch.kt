package com.agenthub.android

import android.graphics.Rect
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import java.io.File

/** One SDK pointer gesture, bounded to an observed popup row owned by this acceptance package. */
internal fun touchOwnedMenu(label: String,folder: File,name: String) {
    val i=InstrumentationRegistry.getInstrumentation();val pkg=i.targetContext.packageName
    require(pkg=="com.easyai.agenthub.android.acceptance");require(name.matches(Regex("[a-zA-Z0-9_-]+")))
    val root=checkNotNull(i.uiAutomation.rootInActiveWindow);assertEquals(pkg,root.packageName?.toString())
    fun find(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if(node.packageName?.toString()!=pkg)return null
        if(node.text?.toString()==label&&node.isVisibleToUser)return node
        for(n in 0 until node.childCount)node.getChild(n)?.let { find(it)?.let { row->return row } }
        return null
    }
    var row=find(root)
    while(row!=null&&!row.isClickable&&row.actionList.none { it.id==AccessibilityNodeInfo.ACTION_CLICK })row=row.parent
    val chosen=checkNotNull(row);assertTrue(chosen.refresh());assertEquals(pkg,chosen.packageName?.toString());assertTrue(chosen.isEnabled&&chosen.isVisibleToUser)
    val bounds=Rect();chosen.getBoundsInScreen(bounds);val window=Rect();root.getBoundsInScreen(window)
    assertFalse(bounds.isEmpty);assertTrue(window.contains(bounds));val x=bounds.exactCenterX();val y=bounds.exactCenterY()
    File(folder,"$name-pointer.json").writeText(JSONObject().put("method","Instrumentation SDK screen-coordinate pointer down/up once").put("package",pkg).put("label",label).put("rowBounds",bounds.flattenToString()).put("windowBounds",window.flattenToString()).put("x",x).put("y",y).toString())
    val downTime=SystemClock.uptimeMillis()
    for(action in listOf(MotionEvent.ACTION_DOWN,MotionEvent.ACTION_UP)) {
        assertEquals(pkg,i.uiAutomation.rootInActiveWindow?.packageName?.toString())
        val event=MotionEvent.obtain(downTime,SystemClock.uptimeMillis(),action,x,y,0)
        try { event.source=InputDevice.SOURCE_TOUCHSCREEN;i.sendPointerSync(event) }finally{event.recycle()}
        if(action==MotionEvent.ACTION_DOWN)Thread.sleep(80)
    }
}

/** Native accessibility action is recorded independently of SDK screen-pointer injection. */
internal fun activateOwnedMenu(label: String,folder: File,name: String) {
    val i=InstrumentationRegistry.getInstrumentation();val pkg=i.targetContext.packageName
    require(pkg=="com.easyai.agenthub.android.acceptance");require(name.matches(Regex("[a-zA-Z0-9_-]+")))
    fun find(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if(node.packageName?.toString()!=pkg)return null
        if(node.text?.toString()==label&&node.isVisibleToUser)return node
        for(n in 0 until node.childCount)node.getChild(n)?.let { find(it)?.let { row->return row } }
        return null
    }
    var root: AccessibilityNodeInfo?=null;var row: AccessibilityNodeInfo?=null
    val deadline=SystemClock.uptimeMillis()+10_000
    while(row==null&&SystemClock.uptimeMillis()<deadline) {
        root=i.uiAutomation.rootInActiveWindow
        if(root?.packageName?.toString()==pkg)row=find(checkNotNull(root))
        if(row==null)Thread.sleep(100)
    }
    while(row!=null&&!row.isClickable&&row.actionList.none { it.id==AccessibilityNodeInfo.ACTION_CLICK })row=row.parent
    val chosen=checkNotNull(row);assertTrue(chosen.refresh());assertEquals(pkg,chosen.packageName?.toString());assertTrue(chosen.isEnabled&&chosen.isVisibleToUser)
    val bounds=Rect();chosen.getBoundsInScreen(bounds);val window=Rect();checkNotNull(root).getBoundsInScreen(window)
    assertFalse(bounds.isEmpty);assertTrue(window.contains(bounds));assertEquals(pkg,i.uiAutomation.rootInActiveWindow?.packageName?.toString())
    val result=chosen.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    File(folder,"$name-sdk-action.json").writeText(JSONObject().put("method","AccessibilityNodeInfo.ACTION_CLICK once").put("package",pkg).put("label",label).put("rowBounds",bounds.flattenToString()).put("windowBounds",window.flattenToString()).put("actionAccepted",result).put("systemPointerVerified",false).put("talkBackSpeechVerified",false).toString())
    assertTrue("Owned native menu action accepted",result)
}
