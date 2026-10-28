package com.agenthub.android

import android.view.View
import android.view.ViewTreeObserver
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.*
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.findViewTreeLifecycleOwner

/** Stable content slot; projection/observer changes never recreate drafts, focus or disclosure.
 * A null port installs no native window/lifecycle observer and produces no report or I/O. */
@Composable internal fun NativeNoticeVisibilityPanel(binding: NativePanelNoticeVisibility?,original: NativeNoticePanelOriginal?,
    document: NativeNoticeReadDocument?=null,content: @Composable ()->Unit) {
    val source=remember { NativeNoticeReadLayoutSource() }
    val context=remember(source,original) { NativeNoticeReadContext(source,original) }
    CompositionLocalProvider(LocalNativeNoticeReadContext provides context) {
        NativeNoticeReadProjection(document)
        Box(Modifier.fillMaxWidth().onGloballyPositioned(source::rootPlaced).semantics { this[NativeNoticeReadStatusKey]={ source.status() } }) { content() }
    }
    if(binding!=null && original!=null)key(binding,original) {
        NativeNoticeVisibilityObserver(binding,original,NativeNoticeVisibilityMatch.notices(binding,original),source)
    } else SideEffect { source.bind(null,null,null) }
}

private fun noticeWindowBounds(view: View): Rect {
    val root=view.rootView;val screen=android.graphics.Rect();view.getWindowVisibleDisplayFrame(screen)
    val offset=IntArray(2);root.getLocationOnScreen(offset)
    return Rect(maxOf(0,screen.left-offset[0]).toFloat(),maxOf(0,screen.top-offset[1]).toFloat(),
        minOf(root.width,screen.right-offset[0]).toFloat(),minOf(root.height,screen.bottom-offset[1]).toFloat())
}
private fun Rect.positive()=listOf(left,top,right,bottom).all { it.isFinite() } && width>0 && height>0

/** Only a translation is supported by this renderer. Rotated/scaled/unknown geometry fails closed. */
private fun nativeTextOrigin(slot: NativeNoticeReadLayoutSource.Slot): Offset? {
    val c=slot.coordinates ?: return null;val r=slot.result ?: return null
    if(!c.isAttached || c.size!=r.size)return null
    val origin=c.localToWindow(Offset.Zero);val x=c.localToWindow(Offset(r.size.width.toFloat(),0f))
    val y=c.localToWindow(Offset(0f,r.size.height.toFloat()))
    return origin.takeIf { listOf(it.x,it.y,x.x,x.y,y.x,y.y).all(Float::isFinite) &&
        kotlin.math.abs(x.x-it.x-r.size.width)<.01f && kotlin.math.abs(x.y-it.y)<.01f &&
        kotlin.math.abs(y.x-it.x)<.01f && kotlin.math.abs(y.y-it.y-r.size.height)<.01f }
}

@Composable private fun NativeNoticeVisibilityObserver(binding: NativePanelNoticeVisibility,original: NativeNoticePanelOriginal,
    notices: List<NativeReadingNotice>,source: NativeNoticeReadLayoutSource) {
    val view=LocalView.current
    val fences=remember { notices.map { NativeNoticeVisibilityFence(binding,original,it) } }
    var windowReady by remember { mutableStateOf(false) }
    var sampleGeneration by remember { mutableLongStateOf(0L) }
    fun retract() { fences.forEach { it.update(false) } }
    DisposableEffect(view,binding,original) {
        val lifecycle=view.findViewTreeLifecycleOwner()?.lifecycle
        var window: Rect?=null;var rootSize: Pair<Int,Int>?=null
        fun refresh() {
            val next=noticeWindowBounds(view);val size=view.rootView.width to view.rootView.height
            val ready=binding.foreground && binding.uncovered && view.isAttachedToWindow && view.hasWindowFocus() &&
                lifecycle?.currentState?.isAtLeast(Lifecycle.State.RESUMED)==true
            // A notice-row refresh replaces fences, not this exact original's native scene.
            // Window/frame continuity is source-owned so late metadata can use prior real coverage.
            source.window(ready,next,view.rootView,view.windowToken)
            if(windowReady!=ready || window!=next || rootSize!=size) {
                retract();windowReady=ready;window=next;rootSize=size;sampleGeneration++
            }
        }
        val changed: ()->Unit={ retract();sampleGeneration++ }
        val observer=LifecycleEventObserver { _,_->refresh() }
        val focus=ViewTreeObserver.OnWindowFocusChangeListener { refresh() }
        val layout=ViewTreeObserver.OnGlobalLayoutListener { refresh() }
        val attach=object: View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View)=refresh()
            override fun onViewDetachedFromWindow(v: View) { retract();source.window(false,noticeWindowBounds(view),view.rootView,null);windowReady=false;sampleGeneration++ }
        }
        source.listeners.add(changed)
        source.bind(if(binding.foreground && binding.uncovered)binding.renderEpoch else null,original,view)
        lifecycle?.addObserver(observer);view.viewTreeObserver.addOnWindowFocusChangeListener(focus)
        view.viewTreeObserver.addOnGlobalLayoutListener(layout);view.addOnAttachStateChangeListener(attach);refresh()
        onDispose {
            source.listeners.remove(changed)
            try { fences.forEach { it.close() } } finally {
                lifecycle?.removeObserver(observer)
                if(view.viewTreeObserver.isAlive) {
                    view.viewTreeObserver.removeOnWindowFocusChangeListener(focus);view.viewTreeObserver.removeOnGlobalLayoutListener(layout)
                }
                view.removeOnAttachStateChangeListener(attach)
            }
        }
    }
    LaunchedEffect(windowReady,sampleGeneration) {
        if(!windowReady) { retract();return@LaunchedEffect }
        val revision=source.revision
        withFrameNanos { };withFrameNanos { }
        if(revision!=source.revision || !windowReady)return@LaunchedEffect
        val window=noticeWindowBounds(view)
        val root=source.root
        if(root?.isAttached!=true) { retract();source.coverage.reset(NativeNoticeReadCoverage.Reset.ROOT);return@LaunchedEffect }
        // Scrolling the mounted original fully out of the clip revokes its live proof, while
        // preserving ranges already witnessed in this same document/layout/window.
        if(!root.boundsInWindow().intersect(window).positive()) { retract();return@LaunchedEffect }
        val parts=source.prepare() ?: run { retract();source.coverage.reset(NativeNoticeReadCoverage.Reset.INCOMPLETE);return@LaunchedEffect }
        var anyVisible=false;var queried=0
        for((part,slot) in parts) {
            val c=slot.coordinates!!;val r=slot.result!!;val origin=nativeTextOrigin(slot) ?: continue
            val clip=c.boundsInWindow().intersect(window)
            if(!clip.positive() || r.lineCount==0)continue
            val localClip=Rect(clip.left-origin.x,clip.top-origin.y,clip.right-origin.x,clip.bottom-origin.y)
            val prefixCovered: (Int,Int)->Boolean={ start,end->source.coverage.contains(part,start,end) }
            // Narrow by visible lines; horizontal/bidi code still checks individual glyph boxes.
            val first=r.getLineForVerticalPosition(clip.top-origin.y);val last=r.getLineForVerticalPosition(clip.bottom-origin.y)
            val start=r.getLineStart(first);val end=r.getLineEnd(last,false).coerceAtMost(part.length)
            for(index in start until end) {
                if(queried==8192) {
                    queried=0;withFrameNanos { }
                    if(revision!=source.revision || !windowReady || !view.hasWindowFocus())return@LaunchedEffect
                }
                queried++
                val witness=nativeNoticeGlyphWitness(r,index,localClip,prefixCovered) ?: continue
                source.coverage.accept(part,index,index+1);anyVisible=anyVisible || witness==NativeNoticeGlyphWitness.INK
            }
        }
        if(revision==source.revision && windowReady && view.hasWindowFocus() && anyVisible && source.coverage.complete())
            fences.forEach { it.update(true) }
    }
}
