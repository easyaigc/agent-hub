package com.agenthub.android

import android.view.View
import android.view.ViewTreeObserver
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.*
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.findViewTreeLifecycleOwner

/** Conservative whole-content gate. Oversized/partially clipped cards remain unread. Same-window
 * overlays require the explicit uncovered input; geometry cannot discover arbitrary draw occlusion. */
@Composable internal fun NativeNoticeVisibilityPanel(binding: NativePanelNoticeVisibility?,original: NativeNoticePanelOriginal,
    content: @Composable ()->Unit) {
    if(binding==null) { content();return }
    val notices=NativeNoticeVisibilityMatch.notices(binding,original)
    if(notices.isEmpty()) { content();return }
    key(binding,original,notices) {
        val view=LocalView.current
        val fences=remember { notices.map { NativeNoticeVisibilityFence(binding,original,it) } }
        var geometry by remember { mutableStateOf(false) }
        var windowReady by remember { mutableStateOf(false) }
        var frameGeneration by remember { mutableLongStateOf(0L) }
        val placed=remember { arrayOfNulls<LayoutCoordinates>(1) }
        fun retract() { fences.forEach { it.update(false) } }
        fun measure(coordinates: LayoutCoordinates?) {
            var shown=false
            if(coordinates?.isAttached==true) {
                val clipped=coordinates.boundsInWindow();val origin=coordinates.positionInWindow();val full=coordinates.size
                val root=view.rootView;val screen=android.graphics.Rect();view.getWindowVisibleDisplayFrame(screen)
                val offset=IntArray(2);root.getLocationOnScreen(offset)
                val left=maxOf(0,screen.left-offset[0]);val top=maxOf(0,screen.top-offset[1])
                val right=minOf(root.width,screen.right-offset[0]);val bottom=minOf(root.height,screen.bottom-offset[1])
                shown=full.width>0 && full.height>0 && clipped.width>0 && clipped.height>0 &&
                    origin.x>=left && origin.y>=top && origin.x+full.width<=right && origin.y+full.height<=bottom &&
                    kotlin.math.abs(clipped.left-origin.x)<.01f && kotlin.math.abs(clipped.top-origin.y)<.01f &&
                    kotlin.math.abs(clipped.width-full.width)<.01f && kotlin.math.abs(clipped.height-full.height)<.01f
            }
            if(!shown)retract()
            if(geometry!=shown) { geometry=shown;frameGeneration++ }
        }
        DisposableEffect(view,binding,original) {
            val lifecycle=view.findViewTreeLifecycleOwner()?.lifecycle
            fun refresh() {
                val ready=view.isAttachedToWindow && view.hasWindowFocus() && lifecycle?.currentState?.isAtLeast(Lifecycle.State.RESUMED)==true
                if(!ready)retract()
                windowReady=ready;frameGeneration++
            }
            val observer=LifecycleEventObserver { _,_->refresh() }
            val focus=ViewTreeObserver.OnWindowFocusChangeListener { refresh() }
            val layout=ViewTreeObserver.OnGlobalLayoutListener { measure(placed[0]) }
            val attach=object: View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View)=refresh()
                override fun onViewDetachedFromWindow(v: View) { retract();windowReady=false;frameGeneration++ }
            }
            lifecycle?.addObserver(observer);view.viewTreeObserver.addOnWindowFocusChangeListener(focus);view.viewTreeObserver.addOnGlobalLayoutListener(layout);view.addOnAttachStateChangeListener(attach);refresh()
            onDispose {
                fences.forEach { it.close() };lifecycle?.removeObserver(observer)
                if(view.viewTreeObserver.isAlive) { view.viewTreeObserver.removeOnWindowFocusChangeListener(focus);view.viewTreeObserver.removeOnGlobalLayoutListener(layout) }
                view.removeOnAttachStateChangeListener(attach)
            }
        }
        LaunchedEffect(geometry,windowReady,frameGeneration) {
            if(!geometry || !windowReady || !binding.foreground || !binding.uncovered) { retract();return@LaunchedEffect }
            // A late event for an already mounted panel still needs two current native layout frames.
            withFrameNanos { };withFrameNanos { }
            if(geometry && windowReady)fences.forEach { it.update(true) }
        }
        Box(Modifier.fillMaxWidth().onGloballyPositioned { coordinates -> placed[0]=coordinates;measure(coordinates) }) { content() }
    }
}
