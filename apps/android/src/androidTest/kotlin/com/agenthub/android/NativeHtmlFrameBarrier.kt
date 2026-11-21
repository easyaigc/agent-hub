package com.agenthub.android

import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.ViewTreeObserver
import org.json.JSONObject

/** One requested own-window draw/commit after Compose semantics idle; no delay or capture retry. */
internal class NativeHtmlFrameBarrier(private val root: View) {
    val evidence=JSONObject()
    private var observer: ViewTreeObserver?=null
    private var drawListener: ViewTreeObserver.OnDrawListener?=null
    private var commit: Runnable?=null
    private var cancelled=false
    private var drew=false
    fun start(ready: ()->Unit,unavailable: (String)->Unit) {
        check(Looper.myLooper()==Looper.getMainLooper())
        check(root.isAttachedToWindow&&root.isShown&&root.hasWindowFocus()&&root.isHardwareAccelerated) { "Own window has no attached focused hardware renderer" }
        val tree=root.viewTreeObserver;observer=tree
        evidence.put("armedElapsedNanos",SystemClock.elapsedRealtimeNanos()).put("hardwareAccelerated",true)
        val listener=ViewTreeObserver.OnDrawListener {
            if(!cancelled&&!drew) {
                drew=true;evidence.put("drawElapsedNanos",SystemClock.elapsedRealtimeNanos())
                // OnDrawListener cannot be removed from inside its onDraw callback.
                root.post { drawListener?.let { if(tree.isAlive)tree.removeOnDrawListener(it) };drawListener=null }
            }
        }
        val callback=Runnable {
            if(!cancelled) {
                evidence.put("commitElapsedNanos",SystemClock.elapsedRealtimeNanos())
                if(!drew)unavailable("Frame committed without the armed own-window draw")
                else if(!root.isAttachedToWindow||!root.isShown||!root.hasWindowFocus())unavailable("Own window lost identity/focus before committed copy")
                else ready()
            }
        }
        drawListener=listener;commit=callback
        tree.addOnDrawListener(listener);tree.registerFrameCommitCallback(callback)
        root.postInvalidateOnAnimation()
    }
    fun cancel() {
        check(Looper.myLooper()==Looper.getMainLooper());cancelled=true
        observer?.let { tree -> if(tree.isAlive) {
            drawListener?.let(tree::removeOnDrawListener)
            commit?.let(tree::unregisterFrameCommitCallback)
        } }
        drawListener=null;commit=null
    }
}
