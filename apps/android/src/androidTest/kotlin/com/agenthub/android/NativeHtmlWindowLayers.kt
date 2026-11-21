package com.agenthub.android

import android.graphics.Rect
import android.os.Looper
import android.os.Process
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import android.webkit.WebView
import org.json.JSONArray
import org.json.JSONObject

/** Bounded, read-only current-process geometry. Never reads user text, draws a view or captures pixels. */
internal object NativeHtmlWindowLayers {
    fun snapshot(owner: View,semantics: JSONArray): JSONObject {
        check(Looper.myLooper()==Looper.getMainLooper())
        check(android.os.Build.VERSION.SDK_INT>=29)
        val root=owner.rootView;val uid=Process.myUid();val pkg=owner.context.packageName
        check(root.context.applicationInfo.uid==uid&&owner.context.applicationInfo.uid==uid)
        val out=JSONObject().put("uid",uid).put("package",pkg).put("semanticOwnerClass",owner.javaClass.name)
            .put("semanticOwnerIdentity",System.identityHashCode(owner)).put("semanticRootIdentity",System.identityHashCode(root))
            .put("semanticRootToken",root.windowToken?.toString()).put("semantics",semantics)
            .put("note","Native hierarchy bounds/z are diagnostic only; Compose render-node placement/clipping is not inferred from them.")
        val windows=WindowInspector.getGlobalWindowViews().filter { it.context.applicationInfo.uid==uid&&it.context.packageName==pkg }.take(8)
        out.put("ownWindows",JSONArray(windows.map { v -> JSONObject().put("identity",System.identityHashCode(v))
            .put("class",v.javaClass.name).put("focus",v.hasWindowFocus()).put("shown",v.isShown).put("attached",v.isAttachedToWindow)
            .put("token",v.windowToken?.toString()).put("isSemanticRoot",v===root) }))
        val rows=JSONArray();var truncated=false
        fun walk(v: View,depth: Int,parent: Int?,index: Int) {
            if(rows.length()>=128||depth>12) { truncated=true;return }
            val id=System.identityHashCode(v);val xy=IntArray(2);v.getLocationOnScreen(xy)
            val visible=Rect();val hasVisible=v.getGlobalVisibleRect(visible)
            val row=JSONObject().put("identity",id).put("parentIdentity",parent).put("childIndex",index).put("depth",depth)
                .put("class",v.javaClass.name).put("viewId",v.id).put("isSemanticOwner",v===owner)
                .put("measured",JSONArray(listOf(v.measuredWidth,v.measuredHeight))).put("layout",JSONArray(listOf(v.left,v.top,v.right,v.bottom)))
                .put("screen",JSONArray(listOf(xy[0],xy[1],v.width,v.height))).put("visibleRootRect",if(hasVisible)visible.toShortString() else null)
                .put("z",v.z).put("elevation",v.elevation).put("translationZ",v.translationZ).put("alpha",v.alpha)
                .put("translation",JSONArray(listOf(v.translationX,v.translationY))).put("scale",JSONArray(listOf(v.scaleX,v.scaleY)))
                .put("shown",v.isShown).put("visibility",v.visibility).put("windowFocus",v.hasWindowFocus()).put("hardware",v.isHardwareAccelerated)
                .put("layerType",v.layerType).put("opaque",v.isOpaque).put("willNotDraw",v.willNotDraw()).put("clipToOutline",v.clipToOutline)
                .put("clipBounds",v.clipBounds?.toShortString()).put("backgroundClass",v.background?.javaClass?.name)
                .put("scroll",JSONArray(listOf(v.scrollX,v.scrollY)))
            if(v is WebView)row.put("webProgress",v.progress).put("webContentHeight",v.contentHeight).put("webScheme",v.url?.substringBefore(':'))
                .put("layoutParams",v.layoutParams?.let { org.json.JSONObject().put("width",it.width).put("height",it.height) } ?: org.json.JSONObject.NULL)
            rows.put(row)
            if(v is ViewGroup) {
                row.put("childCount",v.childCount).put("clipChildren",v.clipChildren).put("clipToPadding",v.clipToPadding)
                for(child in 0 until v.childCount) { if(rows.length()>=128) { truncated=true;break };walk(v.getChildAt(child),depth+1,id,child) }
            }
        }
        walk(root,0,null,0)
        return out.put("views",rows).put("truncated",truncated)
    }
}
