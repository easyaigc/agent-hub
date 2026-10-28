package com.agenthub.android

import android.view.View
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.*
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit

internal data class NativeNoticeReadContext(val source: NativeNoticeReadLayoutSource,val original: NativeNoticePanelOriginal?)
internal val LocalNativeNoticeReadContext=staticCompositionLocalOf<NativeNoticeReadContext?> { null }
/** Read-only native-test diagnostics: no original text, answers, target IDs or credentials. */
internal val NativeNoticeReadStatusKey=SemanticsPropertyKey<()->Map<String,Any>>("NativeNoticeReadStatus")
private val noNoticeTextLayout: (TextLayoutResult)->Unit={}

/** The rendered original text only. Answers remain ordinary BasicTextFields outside this port. */
@Composable internal fun NativeNoticeReadText(part: String,text: String,modifier: Modifier=Modifier,
    color: Color=Color.Unspecified,fontSize: TextUnit=TextUnit.Unspecified,fontWeight: FontWeight?=null,
    fontFamily: FontFamily?=null,lineHeight: TextUnit=TextUnit.Unspecified,softWrap: Boolean=true,
    maxLines: Int=Int.MAX_VALUE,overflow: TextOverflow=TextOverflow.Clip) =
    NativeNoticeReadText(part,remember(text) { AnnotatedString(text) },modifier,color,fontSize,fontWeight,fontFamily,lineHeight,softWrap,maxLines,overflow)

@Composable internal fun NativeNoticeReadText(part: String,text: AnnotatedString,modifier: Modifier=Modifier,
    color: Color=Color.Unspecified,fontSize: TextUnit=TextUnit.Unspecified,fontWeight: FontWeight?=null,
    fontFamily: FontFamily?=null,lineHeight: TextUnit=TextUnit.Unspecified,softWrap: Boolean=true,
    maxLines: Int=Int.MAX_VALUE,overflow: TextOverflow=TextOverflow.Clip) {
    val source=LocalNativeNoticeReadContext.current?.source;val view=LocalView.current;val owner=remember { Any() }
    DisposableEffect(source,part,view) { source?.attach(part,owner,view);onDispose { source?.detach(part,owner) } }
    val measured=if(source==null)modifier else modifier.onGloballyPositioned { source.placed(part,owner,it) }
    val laidOut: (TextLayoutResult)->Unit=if(source==null)noNoticeTextLayout else { value -> source.laidOut(part,owner,value) }
    Text(text,measured,color=color,fontSize=fontSize,fontWeight=fontWeight,
        fontFamily=fontFamily,lineHeight=lineHeight,softWrap=softWrap,maxLines=maxLines,overflow=overflow,
        onTextLayout=laidOut)
}

@Composable internal fun NativeNoticeReadProjection(document: NativeNoticeReadDocument?) {
    val source=LocalNativeNoticeReadContext.current?.source;val owner=remember { Any() }
    DisposableEffect(source,document) { source?.install(owner,document);onDispose { source?.uninstall(owner) } }
}

/** This lives in the mounted content slot, not the Session. Retain coverage across scroll only,
 * never across original/layout/window/focus/cover replacement. All methods are Main-owned. */
internal class NativeNoticeReadLayoutSource {
    private data class Geometry(val bounds: Rect,val x: Float,val y: Float,val width: Int,val height: Int)
    internal class Slot(val view: View) {
        var coordinates: LayoutCoordinates?=null;var result: TextLayoutResult?=null;var version: String?=null
        internal var geometry: Any?=null
    }
    val listeners=linkedSetOf<()->Unit>()
    val coverage=NativeNoticeReadCoverage()
    private val slots=linkedMapOf<String,MutableMap<Any,Slot>>()
    private var documentOwner: Any?=null
    private var document: NativeNoticeReadDocument?=null
    private var epoch: Any?=null
    private var original: NativeNoticePanelOriginal?=null
    private var view: View?=null
    private data class Window(val token: Any?,val root: View,val ready: Boolean,val bounds: Rect,val width: Int,val height: Int)
    private var window: Window?=null
    var revision=0L;private set
    var root: LayoutCoordinates?=null;private set
    private var rootGeometry: Geometry?=null
    private fun geometry(c: LayoutCoordinates): Geometry?=if(!c.isAttached)null else c.positionInWindow().let {
        Geometry(c.boundsInWindow(),it.x,it.y,c.size.width,c.size.height)
    }
    private fun change(reset: Boolean=false,reason: NativeNoticeReadCoverage.Reset=NativeNoticeReadCoverage.Reset.EXPLICIT,mutation: ()->Unit={}) {
        // Revoke the old atomic view guard before publishing a different projection/layout.
        listeners.toList().forEach { it() };if(reset)coverage.reset(reason);mutation();revision++
    }
    fun reset()=change(true)
    fun bind(epoch: Any?,original: NativeNoticePanelOriginal?,view: View?) {
        if(this.epoch===epoch && this.original==original && this.view===view)return
        change(true,NativeNoticeReadCoverage.Reset.BINDING) { this.epoch=epoch;this.original=original;this.view=view;window=null;coverage.install(validDocument()) }
    }
    fun window(ready: Boolean,bounds: Rect,root: View,token: Any?) {
        val next=Window(token,root,ready,bounds,root.width,root.height)
        if(window!=next)change(true,NativeNoticeReadCoverage.Reset.WINDOW) { window=next }
    }
    private fun validDocument()=document?.takeIf { epoch!=null && original!=null && it.original==original }
    fun install(owner: Any,value: NativeNoticeReadDocument?) {
        change(true,NativeNoticeReadCoverage.Reset.DOCUMENT) { documentOwner=owner;document=value;coverage.install(validDocument()) }
    }
    fun uninstall(owner: Any) { if(documentOwner===owner)change(true,NativeNoticeReadCoverage.Reset.DOCUMENT) { documentOwner=null;document=null;coverage.install(null) } }
    fun rootPlaced(value: LayoutCoordinates) {
        val next=geometry(value)
        if(rootGeometry!=next)change(rootGeometry?.width!=next?.width || rootGeometry?.height!=next?.height ||
            next==null,NativeNoticeReadCoverage.Reset.ROOT) { root=value;rootGeometry=next }
        else root=value
    }
    fun attach(part: String,owner: Any,view: View) {
        if(part !in slots && slots.size>=NativeNoticeReadDocument.MAX_PARTS)return
        change(true,NativeNoticeReadCoverage.Reset.ATTACH) { slots.getOrPut(part) { linkedMapOf() }[owner]=Slot(view) }
    }
    fun detach(part: String,owner: Any) {
        val values=slots[part] ?: return
        if(owner in values)change(true,NativeNoticeReadCoverage.Reset.DETACH) { values.remove(owner);if(values.isEmpty())slots.remove(part);coverage.remove(part) }
    }
    fun placed(part: String,owner: Any,value: LayoutCoordinates) {
        val slot=slots[part]?.get(owner) ?: return;val next=geometry(value)
        if(slot.geometry!=next || slot.coordinates!==value)change(slot.coordinates!=null && slot.coordinates!==value,NativeNoticeReadCoverage.Reset.COORDINATES) { slot.coordinates=value;slot.geometry=next }
    }
    fun laidOut(part: String,owner: Any,value: TextLayoutResult) {
        val slot=slots[part]?.get(owner) ?: return
        if(slot.result!==value)change(true,NativeNoticeReadCoverage.Reset.TEXT) {
            slot.result=value;slot.version=value.layoutInput.text.text.takeIf { it.length<=NativeNoticeReadDocument.MAX_CHARACTERS }?.let(::readingSHA)
        }
    }
    fun prepare(): List<Pair<NativeNoticeReadPart,Slot>>? {
        val document=validDocument() ?: return null
        val result=mutableListOf<Pair<NativeNoticeReadPart,Slot>>()
        for(part in document.parts) {
            val slot=slots[part.id]?.values?.singleOrNull() ?: return null
            val layout=slot.result ?: return null;val c=slot.coordinates ?: return null
            if(slot.view!==view || !c.isAttached || slot.version!=part.version || layout.layoutInput.text.length!=part.length ||
                c.size!=layout.size)return null
            result+=part to slot
        }
        // Register every current layout before accumulating any character. A newly unfolded or
        // reflowed part resets the entire projection, including earlier visible segments.
        result.forEach { (part,slot)->coverage.layout(part.id,slot.result!!) }
        return result
    }
    fun status(): Map<String,Any> {
        val parts=validDocument()?.parts.orEmpty()
        val measured=parts.mapNotNull { slots[it.id]?.values?.singleOrNull() }
        // Bounded, on-demand scalar diagnostics. Read the renderer's existing layout only; retain
        // no text/answers/extra document and expose no original IDs or character values.
        val missingKinds=linkedMapOf<String,Int>();val detail=mutableListOf<Map<String,Any>>()
        parts.forEachIndexed { ordinal,part ->
            val slot=slots[part.id]?.values?.singleOrNull()
            val text=slot?.result?.layoutInput?.text?.takeIf { slot.version==part.version && it.length==part.length }
            val kinds=linkedMapOf<String,Int>();val gaps=mutableListOf<List<Int>>();var start=-1
            for(index in 0 until part.length) {
                if(!coverage.contains(part,index,index+1)) {
                    if(start<0)start=index
                    val kind=when(val c=text?.get(index)) {
                        null->"unknown"; '\r','\n'->"lineFeed"; '\t'->"tab"
                        else->when { c.isWhitespace()->"space";Character.getType(c)==Character.FORMAT.toInt()->"format";Character.isSurrogate(c)->"surrogate";else->"glyph" }
                    }
                    kinds[kind]=(kinds[kind] ?: 0)+1;missingKinds[kind]=(missingKinds[kind] ?: 0)+1
                } else if(start>=0) { if(gaps.size<8)gaps+=listOf(start,index);start=-1 }
            }
            if(start>=0 && gaps.size<8)gaps+=listOf(start,part.length)
            if(ordinal<64)detail+=mapOf("ordinal" to ordinal,"characters" to part.length,"coveredCharacters" to coverage.coveredCharacters(part),"missingKinds" to kinds,"firstMissingRanges" to gaps)
        }
        return mapOf("supportedProjection" to (validDocument()!=null),"parts" to parts.size,"registeredParts" to measured.size,
            "layouts" to measured.count { it.result!=null },"detached" to measured.count { it.coordinates?.isAttached!=true },
            "differentWindow" to measured.count { it.view!==view },
            "layoutSizeMismatch" to measured.count { it.result!=null && it.coordinates!=null && it.result!!.size!=it.coordinates!!.size },
            "versionMismatch" to parts.count { slots[it.id]?.values?.singleOrNull()?.version!=it.version },
            "characters" to parts.sumOf { it.length },"coveredCharacters" to coverage.coveredCharacters(),
            "complete" to coverage.complete(),"revision" to revision,"coverageRevision" to coverage.revision,
            "coverageResets" to coverage.resetCounts(),"missingKinds" to missingKinds,"partDetails" to detail,"omittedPartDetails" to (parts.size-detail.size))
    }
}
