package com.agenthub.android

import android.os.SystemClock
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.layout.positionInWindow
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*

/** Observe the test scene's actual pointer route. Never consumes input or changes Compose state.
 * Compose 1.12.1 dispatches TouchInput directly to its owner View, so Window.Callback alone
 * cannot prove delivery. Initial and Final passes show delivery and consumption independently.
 */
internal class P8FormTouchProbe {
    private val lock=Any()
    private var origin=Offset.Zero
    private var windowOrigin=Offset.Zero
    private var width=0
    private var height=0
    private var armed=false
    private var dropped=0
    private val events=mutableListOf<JSONObject>()
    private val lifecycleEvents=mutableListOf<JSONObject>()
    private val focusEvents=mutableListOf<JSONObject>()
    val modifier: Modifier get()=Modifier.onGloballyPositioned {c->synchronized(lock) {origin=c.positionInRoot();windowOrigin=c.positionInWindow();width=c.size.width;height=c.size.height}}
        .pointerInput(this) {
            synchronized(lock) {armed=true}
            try {awaitPointerEventScope {while(true) {record(awaitPointerEvent(PointerEventPass.Initial),"Initial");record(awaitPointerEvent(PointerEventPass.Final),"Final")}}}
            finally {synchronized(lock) {armed=false}}
        }
    private fun record(event: PointerEvent,pass: String)=synchronized(lock) {
        for(c in event.changes) {
            if(events.size>=32){dropped++;continue}
            events+=JSONObject().put("pass",pass).put("type",event.type.toString()).put("pointerId",c.id.value).put("pressed",c.pressed).put("previousPressed",c.previousPressed)
                .put("consumed",c.isConsumed).put("inputUptimeMs",c.uptimeMillis).put("observedUptimeMs",SystemClock.uptimeMillis())
                .put("localX",c.position.x).put("localY",c.position.y).put("rootX",origin.x+c.position.x).put("rootY",origin.y+c.position.y)
        }
    }
    fun lifecycle(name: String)=synchronized(lock) {if(lifecycleEvents.size<16)lifecycleEvents+=JSONObject().put("event",name).put("elapsedMs",SystemClock.elapsedRealtime())else dropped++}
    fun focus(focused: Boolean)=synchronized(lock) {if(focusEvents.size<16)focusEvents+=JSONObject().put("focused",focused).put("elapsedMs",SystemClock.elapsedRealtime())else dropped++}
    fun reset()=synchronized(lock) {events.clear()}
    fun snapshot(): JSONObject=synchronized(lock) {JSONObject().put("armed",armed).put("sceneRootX",origin.x).put("sceneRootY",origin.y).put("sceneWindowX",windowOrigin.x).put("sceneWindowY",windowOrigin.y).put("sceneWidthPx",width).put("sceneHeightPx",height).put("dropped",dropped).put("events",JSONArray(events.toList())).put("lifecycleEvents",JSONArray(lifecycleEvents.toList())).put("windowFocusEvents",JSONArray(focusEvents.toList()))}
    fun assertOneActualTapInside(button: Rect)=synchronized(lock) {
        assertTrue("Non-consuming scene pointer observer must be armed",armed);assertEquals("Pointer diagnostics remain bounded without losing this tap",0,dropped)
        assertTrue("Original Activity must remain resumed through this button wait",lifecycleEvents.none {it.getString("event") in setOf("ON_PAUSE","ON_STOP","ON_DESTROY")})
        assertTrue("Original window must retain focus through this button wait",focusEvents.none {!it.getBoolean("focused")})
        val initial=events.filter {it.getString("pass")=="Initial"}
        val down=initial.filter {it.getBoolean("pressed")&&!it.getBoolean("previousPressed")};val up=initial.filter {!it.getBoolean("pressed")&&it.getBoolean("previousPressed")}
        assertEquals("Exactly one real down event",1,down.size);assertEquals("Exactly one real up event",1,up.size);assertEquals(down.single().getLong("pointerId"),up.single().getLong("pointerId"))
        for(e in down+up) {assertTrue("Actual root pointer must lie inside fully visible original button",e.getDouble("rootX")>=button.left&&e.getDouble("rootX")<=button.right&&e.getDouble("rootY")>=button.top&&e.getDouble("rootY")<=button.bottom)
            assertTrue("Actual pointer must match the original button center",kotlin.math.abs(e.getDouble("rootX")-button.center.x)<=1.5&&kotlin.math.abs(e.getDouble("rootY")-button.center.y)<=1.5)
        }
    }
}
