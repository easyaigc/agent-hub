package com.agenthub.android

import android.app.Instrumentation
import android.graphics.Rect
import android.graphics.RectF
import android.os.IBinder
import android.os.Looper
import android.os.Process
import android.view.Display
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.view.inspector.WindowInspector
import android.webkit.WebView
import org.json.JSONArray
import org.json.JSONObject

/** Synthetic anchor fixture only. No injection API, cross-process inspection or product policy changes. */
internal class NativeHtmlAnchorTarget private constructor(
    private val instrumentation: Instrumentation,private val web: WebView,
    private val initial: Proof,val x: Float,val y: Float,private val report: JSONObject
) {
    private data class Proof(val root: View,val token: IBinder,val rootBounds: RectF,
        val webBounds: RectF,val visible: RectF,val safe: RectF,val insets: String)
    fun verify(stage: String) {
        val now=inspect(instrumentation,web,report,stage,allowContentScroll=stage=="after-${android.view.MotionEvent.ACTION_UP}")
        check(now.root===initial.root&&now.token===initial.token&&now==initial) { "Window/token/geometry/insets changed; targeted input unavailable" }
        check(now.visible.contains(x,y)&&now.safe.contains(x,y)) { "Point left visible safe region" }
        check(frontmost(now.root,x,y)===web) { "Point is not on the frontmost native WebView" }
        report.getJSONArray("verifiedStages").put(stage)
    }
    companion object {
        private fun rect(value: RectF)=JSONArray(listOf(value.left,value.top,value.right,value.bottom))
        private fun visible(view: View): RectF {
            val clip=Rect();check(view.getGlobalVisibleRect(clip)) { "View has no visible root-local region" }
            // SDK View.getGlobalVisibleRect is in root coordinates, not screen coordinates.
            val offset=IntArray(2);view.rootView.getLocationOnScreen(offset);clip.offset(offset[0],offset[1])
            return RectF(clip)
        }
        private fun frontmost(view: View,x: Float,y: Float): View? {
            if(!view.isShown||view.alpha<=0f)return null
            val bounds=runCatching { visible(view) }.getOrNull() ?: return null
            if(!bounds.contains(x,y))return null
            if(view is WebView)return view
            if(view is ViewGroup) {
                val ordered=(0 until view.childCount).sortedWith(compareByDescending<Int> { view.getChildAt(it).z }.thenByDescending { it })
                for(index in ordered)frontmost(view.getChildAt(index),x,y)?.let { return it }
            }
            return view
        }
        private fun inspect(i: Instrumentation,web: WebView,report: JSONObject,stage: String,allowContentScroll: Boolean=false): Proof {
            check(Looper.myLooper()==Looper.getMainLooper())
            check(android.os.Build.VERSION.SDK_INT>=30) { "Safe WindowInsets inspection requires API30" }
            val owner=i.targetContext.packageName;val uid=Process.myUid()
            val origin=IntArray(2);web.getLocationOnScreen(origin)
            val state=JSONObject().put("stage",stage).put("uid",uid).put("pid",Process.myPid()).put("targetUid",i.targetContext.applicationInfo.uid)
                .put("package",owner).put("originalPoint",JSONArray(listOf(origin[0]+30,origin[1]+30)))
            report.getJSONArray("nativeSnapshots").put(state)
            check(uid==i.targetContext.applicationInfo.uid&&web.context.applicationInfo.uid==uid) { "Instrumentation/WebView target uid mismatch" }
            val roots=WindowInspector.getGlobalWindowViews().filter {
                it.isAttachedToWindow&&it.isShown&&it.hasWindowFocus()&&it.windowVisibility==View.VISIBLE&&it.context.packageName==owner
            }
            state.put("focusedOwnRoots",roots.size)
            check(roots.size==1) { "No unique focused attached shown own window" }
            val root=roots.single();val token=checkNotNull(root.windowToken)
            state.put("rootClass",root.javaClass.name).put("rootIdentity",System.identityHashCode(root))
                .put("token",token.toString()).put("tokenClass",token.javaClass.name).put("tokenIdentity",System.identityHashCode(token))
                .put("rootUid",root.context.applicationInfo.uid).put("rootFocus",root.hasWindowFocus()).put("webFocus",web.hasWindowFocus())
                .put("rootAttached",root.isAttachedToWindow).put("rootShown",root.isShown)
            check(root.rootView===root&&web.rootView===root&&web.windowToken===token&&web.isAttachedToWindow&&web.isShown&&web.hasWindowFocus()&&web.isEnabled)
            check(root.context.applicationInfo.uid==uid) { "Window context is not current uid" }
            check(root.display?.displayId==Display.DEFAULT_DISPLAY&&web.display?.displayId==Display.DEFAULT_DISPLAY) { "Only verified default-display targeted events supported" }
            check((root.layoutParams as? WindowManager.LayoutParams)?.flags?.and(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)==0) { "Window not verified touchable" }
            var ancestor: View?=web
            while(ancestor!=null) { check(ancestor.matrix.isIdentity&&ancestor.alpha==1f);ancestor=ancestor.parent as? View }
            state.put("rootScroll",JSONArray(listOf(root.scrollX,root.scrollY))).put("webScroll",JSONArray(listOf(web.scrollX,web.scrollY)))
                .put("contentScrollAllowed",allowContentScroll)
            check(root.scrollX==0&&root.scrollY==0) { "Window root scrolled; target geometry invalid" }
            check(web.scrollX==0&&(if(allowContentScroll)web.scrollY>=0 else web.scrollY==0)) {
                "Unexpected WebView scroll for this input phase"
            }
            val rootXY=IntArray(2);root.getLocationOnScreen(rootXY)
            val rootBounds=RectF(rootXY[0].toFloat(),rootXY[1].toFloat(),(rootXY[0]+root.width).toFloat(),(rootXY[1]+root.height).toFloat())
            val webBounds=RectF(origin[0].toFloat(),origin[1].toFloat(),(origin[0]+web.width).toFloat(),(origin[1]+web.height).toFloat())
            val clip=visible(web);check(clip.intersect(rootBounds))
            val wi=checkNotNull(root.rootWindowInsets) { "No real window insets" }
            val bars=wi.getInsets(WindowInsets.Type.systemBars())
            val cutout=wi.getInsets(WindowInsets.Type.displayCutout())
            val ime=wi.getInsets(WindowInsets.Type.ime())
            val stable=wi.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            val gestures=wi.getInsets(WindowInsets.Type.mandatorySystemGestures())
            fun edges(v: android.graphics.Insets)=JSONArray(listOf(v.left,v.top,v.right,v.bottom))
            val all=JSONObject().put("systemBars",edges(bars)).put("cutout",edges(cutout)).put("ime",edges(ime))
                .put("stableBarsCutout",edges(stable)).put("mandatoryGestures",edges(gestures)).put("imeVisible",wi.isVisible(WindowInsets.Type.ime()))
            val safe=RectF(rootBounds)
            safe.left+=maxOf(bars.left,cutout.left,ime.left,stable.left,gestures.left)
            safe.top+=maxOf(bars.top,cutout.top,ime.top,stable.top,gestures.top)
            safe.right-=maxOf(bars.right,cutout.right,ime.right,stable.right,gestures.right)
            safe.bottom-=maxOf(bars.bottom,cutout.bottom,ime.bottom,stable.bottom,gestures.bottom)
            state.put("rootBounds",rect(rootBounds)).put("webBounds",rect(webBounds)).put("webVisible",rect(clip))
                .put("insets",all).put("safeBounds",rect(safe)).put("originalPointInsideSafe",safe.contains(origin[0]+30f,origin[1]+30f))
            check(!safe.isEmpty) { "No safe window area" }
            return Proof(root,token,rootBounds,webBounds,clip,safe,all.toString())
        }
        fun diagnose(i: Instrumentation,web: WebView,report: JSONObject) {
            if(!report.has("nativeSnapshots"))report.put("nativeSnapshots",JSONArray())
            inspect(i,web,report,"before-teardown",allowContentScroll=true)
        }
        fun prepare(i: Instrumentation,web: WebView,anchor: JSONObject,outer: JSONObject,report: JSONObject): NativeHtmlAnchorTarget {
            report.put("nativeSnapshots",JSONArray()).put("verifiedStages",JSONArray()).put("anchorDom",anchor).put("outerDom",outer)
            val proof=inspect(i,web,report,"prepare")
            check(outer.getString("scheme")=="https:"&&outer.getDouble("scrollX")==0.0&&outer.getDouble("scrollY")==0.0)
            check(anchor.getString("position")=="fixed")
            val ow=outer.getDouble("width");val oh=outer.getDouble("height")
            val iw=anchor.getDouble("width");val ih=anchor.getDouble("height")
            val frame=outer.getJSONObject("frame")
            check(ow>0&&oh>0&&iw>0&&ih>0&&frame.getDouble("width")>0&&frame.getDouble("height")>0)
            val sx=web.width/ow;val sy=web.height/oh
            val ix=frame.getDouble("width")/iw;val iy=frame.getDouble("height")/ih
            fun screen(x: Double,y: Double)=Pair((proof.webBounds.left+(frame.getDouble("left")+x*ix)*sx).toFloat(),
                (proof.webBounds.top+(frame.getDouble("top")+y*iy)*sy).toFloat())
            val ar=anchor.getJSONObject("rect");val tl=screen(ar.getDouble("left"),ar.getDouble("top"));val br=screen(ar.getDouble("right"),ar.getDouble("bottom"))
            val intersection=RectF(tl.first,tl.second,br.first,br.second)
            check(intersection.intersect(proof.visible)&&intersection.intersect(proof.safe)) { "Anchor/visible/safe intersection empty" }
            val margin=maxOf(8f,4f*web.resources.displayMetrics.density);intersection.inset(margin,margin)
            report.put("safeAnchorIntersection",rect(intersection)).put("marginPx",margin)
            check(!intersection.isEmpty) { "No safe interior point in anchor intersection" }
            val hits=anchor.getJSONArray("hits")
            val candidates=(0 until hits.length()).map { hits.getJSONObject(it) }.map {
                val cssX=it.getDouble("x");val cssY=it.getDouble("y");screen(cssX,cssY) to it
            }.filter { intersection.contains(it.first.first,it.first.second) }
            val chosen=candidates.minByOrNull { (it.first.first-intersection.centerX()).let { d->d*d }+(it.first.second-intersection.centerY()).let { d->d*d } }
                ?: error("No DOM-hit-verified sample inside safe anchor intersection")
            report.put("chosenScreenPoint",JSONArray(listOf(chosen.first.first,chosen.first.second))).put("chosenDomHit",chosen.second)
            check(frontmost(proof.root,chosen.first.first,chosen.first.second)===web) { "Chosen point covered by another native View" }
            return NativeHtmlAnchorTarget(i,web,proof,chosen.first.first,chosen.first.second,report)
        }
    }
}
