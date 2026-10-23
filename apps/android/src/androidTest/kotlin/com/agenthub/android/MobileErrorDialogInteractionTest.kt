package com.agenthub.android

import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

/** Actual modal-window input: outside acknowledgement must match the existing iOS alert. */
@RunWith(AndroidJUnit4::class)
class MobileErrorDialogInteractionTest {
    @get:Rule(order=0) val foreground: org.junit.rules.TestRule=NativeTestForeground()
    @get:Rule(order=1) val ui=createAndroidComposeRule<MainActivity>()
    @get:Rule(order=2) val diagnostics: org.junit.rules.TestRule=NativeTestDiagnostics()
    @Test fun outsideTouchKeepsFailureUntilExplicitAcknowledgement() {
        val session=ui.activity.session;val reason="synthetic acknowledgement required";var x=0f;var y=0f;var height=0
        ui.runOnIdle {
            ui.activity.readNetworkMonitor.stop();session.resetAcceptance();session.leaveForeground();session.text="retained synthetic draft";session.error=reason
            val view=ui.activity.window.decorView;val origin=IntArray(2);view.getLocationOnScreen(origin)
            @Suppress("DEPRECATION") val display=ui.activity.windowManager.defaultDisplay.displayId
            assertEquals("Inject only into this primary-display acceptance surface",0,display)
            x=origin[0]+view.width*.5f;y=origin[1]+view.height*.2f;height=view.height
        }
        val dialog=ui.onNode(isDialog()).fetchSemanticsNode()
        assertTrue("Synthetic alert is centered well below the owned app backdrop point",dialog.size.height<height*.5f)
        ui.onNodeWithTag("dismiss-error").assertIsDisplayed()
        val automation=InstrumentationRegistry.getInstrumentation().uiAutomation;val down=SystemClock.uptimeMillis()
        fun send(action: Int) {
            val event=MotionEvent.obtain(down,SystemClock.uptimeMillis(),action,x,y,0).apply { source=InputDevice.SOURCE_TOUCHSCREEN }
            try { assertTrue("Native owned-surface input must be delivered",automation.injectInputEvent(event,true)) } finally { event.recycle() }
        }
        try { send(MotionEvent.ACTION_DOWN);SystemClock.sleep(80) } finally { send(MotionEvent.ACTION_UP) }
        ui.waitForIdle();assertEquals(reason,session.error);assertEquals("retained synthetic draft",session.text)
        ui.onNodeWithTag("dismiss-error").assertIsDisplayed().performClick();ui.waitUntil(5_000) { session.error==null }
        assertEquals("retained synthetic draft",session.text);assertTrue(session.operationRecords.isEmpty())
    }
    @After fun stop() { ui.runOnIdle { ui.activity.session.error=null;ui.activity.session.leaveForeground() } }
}
