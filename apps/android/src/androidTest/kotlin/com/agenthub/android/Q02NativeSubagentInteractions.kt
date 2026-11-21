package com.agenthub.android

import android.content.ClipboardManager
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import kotlin.math.abs

/** Actual SDK gestures on the unchanged production UI; no replacement clipboard or content. */
internal class Q02NativeSubagentInteractions(
    private val ui: ComposeTestRule,
    private val main: Q02RealReadActivity,
    private val folder: File,
    private val plan: JSONObject,
    private val expected: JSONObject,
    private val current: () -> Unit,
    private val shot: (String) -> Unit,
    private val publish: (String, JSONObject) -> Unit,
) {
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private val automation get()=instrumentation.uiAutomation
    private val density get()=main.resources.displayMetrics.density
    private val tag="subagent-detail-"+plan.getString("messageId")
    private val toggle="subagent-expand-"+plan.getString("messageId")
    private fun text()=ui.onNodeWithTag(tag)
    private fun hash(value: String)=MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).joinToString(""){"%02x".format(it)}
    private fun original(): String {
        current()
        val value=text().fetchSemanticsNode().config[SemanticsProperties.Text].single().text
        check(hash(value)==expected.getString("detailSha256")) { "Frozen complete body changed during UI interaction" }
        check(ui.onNodeWithTag(toggle).fetchSemanticsNode().config[SemanticsProperties.StateDescription]==plan.getString("expandedStateDescription"))
        return value
    }
    private fun layout(): TextLayoutResult {
        val values=mutableListOf<TextLayoutResult>()
        text().performSemanticsAction(SemanticsActions.GetTextLayoutResult) { check(it(values)) }
        return values.single().also { check(!it.hasVisualOverflow) }
    }
    private data class View(val layout: TextLayoutResult,val top: Float,val first: Int,val last: Int)
    private fun view(): View {
        original()
        val layout=layout()
        val bounds=text().getUnclippedBoundsInRoot()
        val viewport=ui.onNodeWithTag("message-list").getUnclippedBoundsInRoot()
        val top=bounds.top.value*density
        val visible=(0 until layout.lineCount).filter {
            top+layout.getLineTop(it)>=viewport.top.value*density+1f&&
                top+layout.getLineBottom(it)<=viewport.bottom.value*density-1f
        }
        return View(layout,top,visible.firstOrNull() ?: -1,visible.lastOrNull() ?: -1)
    }
    private fun scroll(up: Boolean) {
        ui.onNodeWithTag("message-list").performTouchInput {
            val a=Offset(width*.5f,height*(if(up).80f else .25f))
            val b=Offset(width*.5f,height*(if(up).25f else .80f))
            swipe(a,b,450)
        }
        ui.waitForIdle()
    }
    private fun seek(line: Int): View {
        repeat(32) {
            val v=view()
            if(line in v.first..v.last&&v.first>=0)return v
            val viewport=ui.onNodeWithTag("message-list").getUnclippedBoundsInRoot()
            scroll(v.top+v.layout.getLineTop(line)>viewport.top.value*density)
        }
        error("Actual bounded scroll did not expose the requested original line")
    }
    private fun capture(name: String) {
        shot(name)
        check(folder.listFiles().orEmpty().filter {it.extension=="png"}.sumOf {it.length()}<=16L*1024*1024) {
            "Explicit native PNG evidence budget exceeded; full visual coverage is unverified"
        }
    }
    private fun coverEveryLine() {
        val first=seek(0);val count=first.layout.lineCount
        val viewport=ui.onNodeWithTag("message-list").getUnclippedBoundsInRoot()
        check(first.layout.size.height>(viewport.bottom.value-viewport.top.value)*density) {
            "This case requires an actual long body spanning multiple viewports"
        }
        val seen=BooleanArray(count);val frames=JSONArray()
        repeat(32) { index ->
            val v=view();check(v.first>=0&&v.last>=v.first&&v.layout.lineCount==count)
            val name="long-viewport-"+index.toString().padStart(2,'0')
            capture(name)
            for(line in v.first..v.last)seen[line]=true
            frames.put(JSONObject().put("frame",name).put("firstFullyVisibleLine",v.first).put("lastFullyVisibleLine",v.last)
                .put("startUtf16",v.layout.getLineStart(v.first)).put("endUtf16",v.layout.getLineEnd(v.last,false))
                .put("bodySha256",expected.getString("detailSha256")))
            publish("$name-range.json",frames.getJSONObject(frames.length()-1))
            if(seen.all {it}) {
                check(frames.length()>1)
                publish("long-visual-coverage.json",JSONObject().put("bodySha256",expected.getString("detailSha256"))
                    .put("layoutLines",count).put("frames",frames).put("allOriginalLinesFullyVisible",true)
                    .put("sdkScrollGestures",true).put("fullLongBodyVisualCoverageVerified",true))
                return
            }
            scroll(true)
        }
        error("Explicit viewport capture limit reached; no partial coverage pass")
    }
    private fun ownedWindow() {
        val root=checkNotNull(automation.rootInActiveWindow)
        try {check(root.packageName?.toString()==main.packageName)} finally {root.recycle()}
    }
    private fun back() {
        ownedWindow()
        val now=SystemClock.uptimeMillis()
        for(action in listOf(KeyEvent.ACTION_DOWN,KeyEvent.ACTION_UP)) {
            check(automation.injectInputEvent(KeyEvent(now,now+if(action==KeyEvent.ACTION_UP)80 else 0,action,KeyEvent.KEYCODE_BACK,0),true))
        }
        ui.waitForIdle();ownedWindow();current()
    }
    private fun tap(tag: String) { ownedWindow();ui.onNodeWithTag(tag).assertIsDisplayed().assertIsEnabled().performTouchInput {click()};ui.waitForIdle() }
    private fun anchor(v: View)=JSONObject().put("firstVisibleLine",v.first).put("lastVisibleLine",v.last).put("textTopPx",v.top)
    private fun sameAnchor(before: View) {
        val after=view()
        check(before.first==after.first&&before.last==after.last&&abs(before.top-after.top)<=2f) { "Return changed the original expanded-body reading position" }
    }
    private fun returns() {
        val before=view()
        tap("chat-menu");ui.onNodeWithTag("refresh-history").assertIsDisplayed();capture("menu-before-back")
        back();ui.onNodeWithTag("refresh-history").assertDoesNotExist();sameAnchor(before);capture("menu-return")
        publish("menu-return.json",anchor(before).put("sdkBack",true).put("sameExpandedBodyAndViewport",true))
        // Real production page, same exact parent only. No query, other chat or settings input.
        tap("chat-menu");tap("chat-search-open")
        ui.onNodeWithTag("chat-search-query").assertExists();capture("same-parent-search-page")
        var presses=0
        while(ui.onAllNodesWithTag("chat-search-query").fetchSemanticsNodes().isNotEmpty()&&presses<2) {back();presses++}
        ui.onNodeWithTag("chat-search-query").assertDoesNotExist();sameAnchor(before);capture("page-return")
        publish("page-return.json",anchor(before).put("page","same-parent-chat-search").put("sdkBackCount",presses)
            .put("searchInputs",0).put("sameExpandedBodyAndViewport",true))
    }
    private fun handles(endOnly: Boolean=false)=ui.onAllNodes(SemanticsMatcher("Actual subagent selection handle") { n ->
        n.config.any {it.key.name=="SelectionHandleInfo"&&it.value.toString().contains("visible=true")&&(!endOnly||it.value.toString().contains("SelectionEnd"))}
    },useUnmergedTree=true)
    private fun handleHash()=hash(handles(true).fetchSemanticsNodes().single().config.joinToString {it.key.name+"="+it.value})
    private fun emptyClipUnchanged(ownedTimestamp: Long?,changes: Int,hasClip: Boolean)=ownedTimestamp==null&&changes==0&&!hasClip
    private fun applicationWindowOwned(type: Int,rootPackage: String?,expectedPackage: String)=type==AccessibilityWindowInfo.TYPE_APPLICATION&&rootPackage==expectedPackage
    // Metadata only, and only after the existing application-window/root owner gate.
    private data class CopyProbe(
        val index: Int,val parent: Int,val windowId: Int,val actionPackage: String?,val viewId: String,
        val textIsCopy: Boolean,val hasText: Boolean,val descriptionIsCopy: Boolean,
        val visible: Boolean,val enabled: Boolean,val clickable: Boolean,val childCount: Int,
        val left: Int,val top: Int,val right: Int,val bottom: Int,
    )
    private fun linkedCopyParent(nodes: List<CopyProbe>,leafIndex: Int,windowId: Int,expectedPackage: String): Pair<Int?,String> {
        val leaf=nodes[leafIndex]
        if(leaf.windowId!=windowId)return null to "leaf-window"
        if(leaf.viewId!="android:id/floating_toolbar_menu_item_text"||!leaf.textIsCopy)return null to "leaf-label-or-id"
        if(!leaf.visible||!leaf.enabled||leaf.clickable||leaf.childCount!=0)return null to "leaf-state"
        if(leaf.left>=leaf.right||leaf.top>=leaf.bottom)return null to "leaf-bounds"
        if(leaf.actionPackage !in listOf(expectedPackage,"android","com.android.systemui"))return null to "leaf-package"
        var child=leaf
        repeat(3) {
            val parent=nodes.getOrNull(child.parent) ?: return null to "no-near-click-parent"
            if(parent.index>=child.index||parent.windowId!=windowId)return null to "parent-window-or-cycle"
            if(parent.actionPackage!=leaf.actionPackage||!parent.visible||!parent.enabled)return null to "parent-package-or-state"
            if(parent.left>child.left||parent.top>child.top||parent.right<child.right||parent.bottom<child.bottom)return null to "parent-bounds"
            if(parent.clickable) {
                if(!parent.descriptionIsCopy||parent.hasText)return null to "parent-copy-description"
                if(parent.viewId.isNotEmpty()&&!parent.viewId.startsWith("android:id/floating_toolbar"))return null to "parent-resource"
                // A whole toolbar containing another action must not stand in for its Copy button.
                val descendants=nodes.filter {node ->
                    var cursor=node.parent
                    var inside=false
                    repeat(64) {if(cursor==parent.index)inside=true;cursor=nodes.getOrNull(cursor)?.parent ?: -1}
                    inside
                }
                if(descendants.filter {it.visible&&it.hasText}.map {it.index}!=listOf(leaf.index)||
                    descendants.any {it.visible&&it.clickable})return null to "ambiguous-parent-subtree"
                return parent.index to "nearest-click-parent"
            }
            child=parent
        }
        return null to "no-near-click-parent"
    }
    private var lastCopyProbe: JSONObject?=null
    private fun copyAction(): JSONObject? {
        ui.runOnIdle {
            current()
            val resumed=ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
            check(resumed.size==1&&resumed.single()===main&&main.javaClass==Q02RealReadActivity::class.java&&
                !main.isDestroyed&&!main.isFinishing&&main.profile.session===main.session) {"Copy has no unique resumed Q02 owner"}
        }
        val active=checkNotNull(automation.rootInActiveWindow)
        val activeId=try {check(active.packageName?.toString()==main.packageName&&active.windowId>=0);active.windowId} finally {active.recycle()}
        val trace=JSONObject().put("activeWindowId",activeId).put("ownerPackage",main.packageName)
            .put("observedAtUptimeMs",SystemClock.uptimeMillis())
        val observedWindows=JSONArray();trace.put("ownedWindows",observedWindows);lastCopyProbe=trace
        var skippedType=0;var skippedOwner=0;var missingRoot=0
        val candidates=mutableListOf<JSONObject>()
        val windows=automation.windows
        try {for(window in windows) {
            if(window.type!=AccessibilityWindowInfo.TYPE_APPLICATION) {skippedType++;continue}
            val root=window.root
            if(root==null) {missingRoot++;continue}
            try {
                if(!applicationWindowOwned(window.type,root.packageName?.toString(),main.packageName)) {skippedOwner++;continue}
                check(window.id>=0&&root.windowId==window.id)
                val windowBounds=android.graphics.Rect();window.getBoundsInScreen(windowBounds)
                val nodes=mutableListOf<CopyProbe>()
                val nodeTrace=JSONArray();val links=JSONArray()
                val wt=JSONObject().put("id",window.id).put("type",window.type).put("rootOwnerSha256",hash(root.packageName.toString()))
                    .put("nodes",nodeTrace).put("links",links)
                observedWindows.put(wt)
                var count=0;var exactLabels=0;var oldDirect=0
                fun visit(n: AccessibilityNodeInfo,depth: Int,parent: Int) {
                    check(depth<=64&&++count<=4096)
                    val id=n.viewIdResourceName.orEmpty()
                    val bounds=android.graphics.Rect();n.getBoundsInScreen(bounds)
                    val index=nodes.size
                    val probe=CopyProbe(index,parent,n.windowId,n.packageName?.toString(),id,
                        n.text?.toString()==plan.getString("copyMenuLabel"),!n.text.isNullOrEmpty(),
                        n.contentDescription?.toString()==plan.getString("copyMenuLabel"),
                        n.isVisibleToUser,n.isEnabled,n.isClickable,n.childCount,bounds.left,bounds.top,bounds.right,bounds.bottom)
                    nodes+=probe
                    if(probe.textIsCopy)exactLabels++
                    if((probe.textIsCopy||probe.descriptionIsCopy||id.contains("floating_toolbar"))&&nodeTrace.length()<64)
                        nodeTrace.put(JSONObject().put("index",index).put("parent",parent).put("windowId",n.windowId)
                            .put("viewId",id).put("packageSha256",hash(n.packageName?.toString().orEmpty()))
                            .put("textSha256",hash(n.text?.toString().orEmpty())).put("descriptionSha256",hash(n.contentDescription?.toString().orEmpty()))
                            .put("visible",n.isVisibleToUser).put("enabled",n.isEnabled).put("clickable",n.isClickable).put("childCount",n.childCount))
                    if(n.windowId==window.id&&n.isVisibleToUser&&n.isEnabled&&n.isClickable&&
                        n.text?.toString()==plan.getString("copyMenuLabel")&&
                        (id.contains("floating_toolbar")||id=="android:id/copy")&&
                        n.packageName?.toString() in listOf(main.packageName,"android","com.android.systemui")) {
                        val r=android.graphics.Rect();n.getBoundsInScreen(r);check(!r.isEmpty&&windowBounds.contains(r))
                        candidates+=JSONObject().put("ownerPackage",root.packageName.toString()).put("actionPackage",n.packageName.toString())
                            .put("activeWindowId",activeId).put("copyWindowId",window.id).put("copyWindowType",window.type)
                            .put("resumedActivity",main.javaClass.name).put("viewId",id)
                            .put("left",r.left).put("top",r.top).put("right",r.right).put("bottom",r.bottom)
                        oldDirect++
                    }
                    for(index in 0 until n.childCount)n.getChild(index)?.let {child->try{visit(child,depth+1,probe.index)}finally{child.recycle()}}
                }
                visit(root,0,-1)
                for(leaf in nodes.filter {it.textIsCopy&&!it.clickable}) {
                    val (parentIndex,reason)=linkedCopyParent(nodes,leaf.index,window.id,main.packageName)
                    if(links.length()<64)links.put(JSONObject().put("leafIndex",leaf.index).put("parentIndex",parentIndex ?: JSONObject.NULL).put("reason",reason))
                    if(parentIndex==null)continue
                    val parent=nodes[parentIndex]
                    val r=android.graphics.Rect(parent.left,parent.top,parent.right,parent.bottom)
                    check(!r.isEmpty&&windowBounds.contains(r))
                    candidates+=JSONObject().put("ownerPackage",root.packageName.toString()).put("actionPackage",parent.actionPackage)
                        .put("activeWindowId",activeId).put("copyWindowId",window.id).put("copyWindowType",window.type)
                        .put("resumedActivity",main.javaClass.name).put("viewId",parent.viewId)
                        .put("matchKind",reason).put("labelViewId",leaf.viewId).put("labelIndex",leaf.index).put("actionIndex",parent.index)
                        .put("left",r.left).put("top",r.top).put("right",r.right).put("bottom",r.bottom)
                }
                wt.put("visited",count).put("exactCopyLabels",exactLabels).put("oldDirectCandidates",oldDirect)
                    .put("nodeMetadataCapped",nodeTrace.length()==64).put("linkMetadataCapped",links.length()==64)
            } finally {root.recycle()}
        }} finally {windows.forEach {it.recycle()};trace.put("unownedWindowTypesSkipped",skippedType)
            .put("unownedRootsSkipped",skippedOwner).put("rootsUnavailable",missingRoot).put("candidates",candidates.size)}
        check(candidates.size<=1) {"Multiple native Copy candidates; no touch"}
        return candidates.singleOrNull()
    }
    private fun touchCopy(beforeTouch: ()->Unit): JSONObject {
        val flags=automation.serviceInfo.flags
        var failure: Throwable?=null
        try {
            automation.serviceInfo=automation.serviceInfo.apply {this.flags=flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS}
            var found: JSONObject?=null
            ui.waitUntil(5000) {found=copyAction();found!=null}
            val observed=checkNotNull(found)
            // Non-focusable toolbars may be a separate APPLICATION window. Re-prove its
            // continued existence, owner and sole exact Copy action; active Root stays ours.
            val action=checkNotNull(copyAction())
            check(action.getInt("copyWindowId")==observed.getInt("copyWindowId")&&action.getString("viewId")==observed.getString("viewId"))
            check(action.optString("matchKind")==observed.optString("matchKind")&&
                action.optString("labelViewId")==observed.optString("labelViewId")&&
                action.optInt("labelIndex",-1)==observed.optInt("labelIndex",-1)&&action.optInt("actionIndex",-1)==observed.optInt("actionIndex",-1))
            val x=(action.getInt("left")+action.getInt("right"))/2f;val y=(action.getInt("top")+action.getInt("bottom"))/2f
            ownedWindow()
            beforeTouch() // Last clipboard fence, with no further idle/frame yield before the pointer.
            val now=SystemClock.uptimeMillis()
            for((kind,at) in listOf(MotionEvent.ACTION_DOWN to now,MotionEvent.ACTION_UP to now+80)) {
                val event=MotionEvent.obtain(now,at,kind,x,y,0).apply {source=InputDevice.SOURCE_TOUCHSCREEN}
                try {check(automation.injectInputEvent(event,true))} finally {event.recycle()}
            }
            return action.put("sdkTargetedTouch",true).put("emptyClipboardGuardBeforeTouch",true)
        } catch(error: Throwable) {
            failure=error
            try {
                val diagnostic=JSONObject().put("failureClass",error.javaClass.name).put("failureMessageSha256",hash(error.message.orEmpty()))
                    .put("lastCopyProbe",lastCopyProbe ?: JSONObject.NULL).put("foreignWindowContentRead",false)
                    .put("unprovenWindowOwnershipStillRejected",true)
                check(diagnostic.toString().toByteArray(Charsets.UTF_8).size<=64*1024)
                publish("copy-candidate-diagnostic.json",diagnostic)
            } catch(diagnosticError: Throwable) {error.addSuppressed(diagnosticError)}
            throw error
        }
        finally {
            try {
                automation.serviceInfo=automation.serviceInfo.apply {this.flags=flags}
                check(automation.serviceInfo.flags==flags) {"Owned AX observer flags did not restore"}
            } catch(error: Throwable) {val original=failure;if(original!=null)original.addSuppressed(error) else throw error}
        }
    }
    private fun mapClipDescription(description: android.content.ClipDescription?): Q02ClipDescription? {
        if(description==null)return null
        check(description.mimeTypeCount in 1..8)
        val label=description.label?.toString();check(label==null||label.length<=4096)
        val mimes=(0 until description.mimeTypeCount).map {description.getMimeType(it).also {mime->check(mime.length<=128)}}.sorted()
        return Q02ClipDescription(description.timestamp,hash(if(label==null)"label:null" else "label:text:"+label),
            mimes,description.classificationStatus,description.isStyledText)
    }
    private fun payloadMetadata(description: android.content.ClipDescription?,origin: String): Q02ClipMetadata {
        val elapsed=SystemClock.uptimeMillis();val wall=System.currentTimeMillis();val focused=main.hasWindowFocus()
        if(!focused)return Q02ClipMetadata(elapsed,wall,false,null,"focus-lost",origin=origin)
        return try {
            val value=mapClipDescription(description)
            Q02ClipMetadata(elapsed,wall,main.hasWindowFocus(),value,if(value==null)"payload-description-unavailable" else null,origin=origin)
        } catch(error: Throwable) {Q02ClipMetadata(elapsed,wall,focused,null,error.javaClass.name,origin=origin)}
    }
    private fun clipboardPresence(clipboard: ClipboardManager): Q02ClipPresence {
        val elapsed=SystemClock.uptimeMillis();val wall=System.currentTimeMillis();val focused=main.hasWindowFocus()
        if(!focused)return Q02ClipPresence(elapsed,wall,false,null,null,null,"focus-lost")
        return try {
            val before=clipboard.hasPrimaryClip()
            val description=mapClipDescription(clipboard.primaryClipDescription)
            val after=clipboard.hasPrimaryClip();val focus=main.hasWindowFocus()
            val error=when {
                !focus -> "focus-lost"
                before!=after||before!=(description!=null) -> "presence-or-description-inconsistent"
                else -> null
            }
            Q02ClipPresence(elapsed,wall,focus,before,after,description,error)
        } catch(error: Throwable) {Q02ClipPresence(elapsed,wall,main.hasWindowFocus(),null,null,null,error.javaClass.name)}
    }
    private fun clipboardMetadata(clipboard: ClipboardManager,origin: String="description-getter"): Q02ClipMetadata {
        val value=clipboardPresence(clipboard)
        return Q02ClipMetadata(value.elapsed,value.wall,value.focused,value.description,value.error,origin=origin,
            emptyObserved=if(value.error==null)value.knownEmpty() else null)
    }
    private fun presenceJson(value: Q02ClipPresence?): Any=value?.let {
        JSONObject().put("elapsed",it.elapsed).put("wall",it.wall).put("focused",it.focused)
            .put("hasBefore",it.hasBefore ?: JSONObject.NULL).put("hasAfter",it.hasAfter ?: JSONObject.NULL)
            .put("knownState",it.knownState()).put("knownEmpty",it.knownEmpty()).put("error",it.error ?: JSONObject.NULL)
            .put("description",it.description?.let {d->JSONObject().put("timestamp",d.timestamp).put("labelSha256",d.labelSha256)
                .put("mimeTypes",JSONArray(d.mimeTypes)).put("classification",d.classification).put("styled",d.styled)} ?: JSONObject.NULL)
    } ?: JSONObject.NULL
    private fun clipboardTrace(guard: Q02ClipboardEpochGuard): JSONObject=JSONObject()
        .put("notifications",guard.count()).put("metadataOnly",true).put("bodyStored",false)
        .put("events",JSONArray().apply {guard.trace().forEach {row->
            put(JSONObject().put("elapsed",row.elapsed).put("wall",row.wall).put("focused",row.focused)
                .put("origin",row.origin).put("emptyObserved",row.emptyObserved ?: JSONObject.NULL).put("validationAccepted",row.validationAccepted ?: JSONObject.NULL).put("validationCheckpoint",row.validationCheckpoint).put("afterOwnClearRequest",row.afterOwnClearRequest).put("error",row.error ?: JSONObject.NULL).put("description",row.description?.let {d->
                    JSONObject().put("timestamp",d.timestamp).put("labelSha256",d.labelSha256)
                        .put("mimeTypes",JSONArray(d.mimeTypes)).put("classification",d.classification).put("styled",d.styled)
                } ?: JSONObject.NULL))
        }})
    private fun copyMarkerLines() {
        val flags=automation.serviceInfo.flags
        var failure: Throwable?=null
        try {
            automation.serviceInfo=automation.serviceInfo.apply {this.flags=flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS}
            copyMarkerLinesWithObserver()
        } catch(error: Throwable) {failure=error;throw error}
        finally {
            try {
                automation.serviceInfo=automation.serviceInfo.apply {this.flags=flags}
                check(automation.serviceInfo.flags==flags) {"Owned selection AX flags did not restore"}
            } catch(error: Throwable) {val original=failure;if(original!=null)original.addSuppressed(error) else throw error}
        }
    }
    private fun copyMarkerLinesWithObserver() {
        check(expected.getString("status")=="completed") {"Copy requires the same child's frozen completed output"}
        val selection=expected.getJSONObject("copySelection")
        check(selection.getString("sourceFileSha256")==expected.getString("sourceFileSha256")&&
            selection.getString("detailSha256")==expected.getString("detailSha256"))
        val marker=selection.getString("marker");check(marker.matches(Regex("CHILD_[A-Za-z0-9_]{8,96}")))
        val body=original();val start=selection.getInt("startUtf16");val end=selection.getInt("endUtf16")
        check(start>=0&&end in start+1..body.length)
        val lines=Regex("(?m)^([ \\t]*[0-9]{1,3}[^A-Za-z0-9\\r\\n]*"+Regex.escape(marker)+"[^\\r\\n]*)$").findAll(body).toList()
        check(lines.size==96&&lines.map {Regex("[0-9]{1,3}").find(it.value)!!.value.toInt()}==(1..96).toList())
        val touched=lines.filter {it.range.first<end&&it.range.last>=start}
        check(touched.size>=2&&start>=touched.first().range.first&&end<=touched.last().range.last+1)
        val selected=body.substring(start,end)
        check(hash(selected)==selection.getString("sha256")&&selected.all {it=='\n'||it=='\r'||it=='\t'||it.code in 32..126})
        val line=layout().getLineForOffset(start);seek(line)
        val geometry=view();check(geometry.layout.getLineForOffset(end-1)<=geometry.last) {"Both marker endpoints must be actually visible"}
        val initial=geometry.layout.getWordBoundary(start+1);check(initial.start==start&&initial.end<end)
        val clipboard=main.getSystemService(ClipboardManager::class.java)
        // Do not inspect or overwrite a prior unknown/sensitive clip. No sentinel is installed.
        ui.runOnIdle {check(main.hasWindowFocus());check(!clipboard.hasPrimaryClip()) {"Existing clipboard entry preserved; copy is not admissible"}}
        publish("clipboard-before.json",JSONObject().put("empty",true).put("priorContentRead",false))
        var ownedTimestamp: Long?=null;val epochGuard=Q02ClipboardEpochGuard()
        var ownedEpoch: Q02ClipEpoch?=null;var ownedValue: String?=null
        var exactSelectionMatched=false;var cleanupEpochRechecked=false
        var emptyPreserved=false;var ownedClipCleared=false;var restored=false
        var immediateEmpty: Q02ClipPresence?=null;var closeBefore: Q02ClipPresence?=null;var closeAfter: Q02ClipPresence?=null
        var observerRemoveAttempted=false;var observerRemoved=false;var closeVerified=false
        var primaryFailure: Throwable?=null
        val listener=ClipboardManager.OnPrimaryClipChangedListener {
            val value=clipboardMetadata(clipboard,"callback")
            epochGuard.record(if(android.os.Looper.myLooper()==android.os.Looper.getMainLooper())value else value.copy(error="callback-not-main"))
        }
        try {
            ui.runOnIdle {clipboard.addPrimaryClipChangedListener(listener)}
            text().performTouchInput {longClick(geometry.layout.getBoundingBox(start+1).center)}
            ui.waitUntil(5000) {handles().fetchSemanticsNodes().size==2&&handles(true).fetchSemanticsNodes().size==1}
            val before=handleHash();capture("selection-start")
            val delta=geometry.layout.getCursorRect(end).bottomLeft-geometry.layout.getCursorRect(initial.end).bottomLeft
            check(delta.y>geometry.layout.getLineBottom(line)-geometry.layout.getLineTop(line))
            handles(true)[0].performTouchInput {swipe(center,center+delta,600)}
            ui.waitUntil(5000) {handles(true).fetchSemanticsNodes().size==1&&handleHash()!=before}
            publish("selection-drag.json",JSONObject().put("startUtf16",start).put("endUtf16",end)
                .put("initialWordStart",initial.start).put("initialWordEnd",initial.end).put("deltaXPx",delta.x).put("deltaYPx",delta.y)
                .put("handleBeforeSha256",before).put("handleAfterSha256",handleHash()).put("sdkHandleDrag",true))
            capture("selection-cross-line")
            val action=touchCopy {
                // Clipboard Binder reads need no main-thread dispatch. Keep this fence
                // after the final fresh window proof, without another idle/frame yield.
                check(main.hasWindowFocus()) {"Clipboard empty-state proof requires the current focused Q02 owner"}
                check(emptyClipUnchanged(ownedTimestamp,epochGuard.count(),clipboard.hasPrimaryClip())) {"Clipboard changed before Copy; unknown content preserved without touching Copy"}
                epochGuard.arm(System.currentTimeMillis(),SystemClock.uptimeMillis())
            };publish("native-copy-action.json",action)
            ui.waitUntil(5000) {var changed=false;ui.runOnIdle {changed=epochGuard.count()>0};changed}
            ownedWindow()
            ui.runOnIdle {
                val epoch=epochGuard.requireEpoch(clipboardMetadata(clipboard))
                val clip=checkNotNull(clipboard.primaryClip)
                // Bind the returned payload snapshot itself, before any item/text access.
                epochGuard.requireEpoch(payloadMetadata(clip.description,"candidate-payload"),epoch)
                check(clip.itemCount==1&&clip.getItemAt(0).uri==null&&clip.getItemAt(0).intent==null)
                val value=checkNotNull(clip.getItemAt(0).text?.toString())
                epochGuard.requireEpoch(clipboardMetadata(clipboard),epoch)
                // This checkpoint is cleanup-only. A wrong selection remains a test failure.
                if(q02UniqueMarkerSlice(body,marker,value)) {
                    ownedEpoch=epoch;ownedTimestamp=epoch.timestamp;ownedValue=value
                }
                check(value==selected) {"Native copied range differs from frozen marker bytes"}
                check(ownedValue!=null) {"Copied value has no unique original-marker cleanup ownership"}
                exactSelectionMatched=true
            }
            publish("clipboard-copy.json",JSONObject().put("copiedSha256",hash(selected)).put("utf8Bytes",selected.toByteArray().size)
                .put("startUtf16",start).put("endUtf16",end).put("numberedMarkerLines",touched.size)
                .put("sdkLongPressAndHandleDrag",true).put("sdkOwnedToolbarCopy",true).put("systemClipboardMatched",true)
                .put("expectedFromIndependentFrozenDescriptor",true).put("replacementClipboard",false))
        } catch(error: Throwable) {
            primaryFailure=error
            try {publish("selection-copy-failure.json",JSONObject().put("failureClass",error.javaClass.name).put("ownedCopyVerified",exactSelectionMatched).put("cleanupOwnedOriginalSlice",ownedValue!=null))}
            catch(evidence: Throwable) {error.addSuppressed(evidence)}
            throw error
        } finally {
            var cleanupFailure: Throwable?=null
            try {
                if(ownedTimestamp==null)ownedWindow() // A background false-negative cannot prove untouched empty.
                ui.runOnIdle {
                    check(main.hasWindowFocus()) {"Clipboard restoration proof requires the focused Q02 owner"}
                    val epoch=ownedEpoch;val value=ownedValue
                    if(epoch!=null&&value!=null) {
                        // Late classification callbacks are validated by epoch/metadata, never by count equality.
                        epochGuard.requireEpoch(clipboardMetadata(clipboard),epoch)
                        val clip=checkNotNull(clipboard.primaryClip)
                        epochGuard.requireEpoch(payloadMetadata(clip.description,"cleanup-payload"),epoch)
                        if(clip.itemCount==1&&clip.getItemAt(0).uri==null&&clip.getItemAt(0).intent==null&&
                            clip.getItemAt(0).text?.toString()==value&&q02UniqueMarkerSlice(body,marker,value)) {
                            epochGuard.requireEpoch(clipboardMetadata(clipboard),epoch)
                            cleanupEpochRechecked=true;epochGuard.markOwnClearRequest();clipboard.clearPrimaryClip();ownedClipCleared=true;immediateEmpty=clipboardPresence(clipboard)
                        }
                    }
                }
            } catch(error: Throwable) {cleanupFailure=error}
            finally {
                // Drain already queued main-thread callbacks, remove only this listener,
                // then take a distinct metadata-only close snapshot. No second clear.
                try {
                    ui.runOnIdle {
                        closeBefore=clipboardPresence(clipboard)
                        observerRemoveAttempted=true
                        try {clipboard.removePrimaryClipChangedListener(listener);observerRemoved=true}
                        finally {closeAfter=clipboardPresence(clipboard)}
                    }
                } catch(error: Throwable) {val prior=cleanupFailure;if(prior!=null)prior.addSuppressed(error) else cleanupFailure=error}
                finally {
                    // UI dispatch itself can fail. Still attempt exact listener removal;
                    // missing close snapshots remain unknown and can never pass restoration.
                    if(!observerRemoveAttempted) {
                        observerRemoveAttempted=true
                        try {clipboard.removePrimaryClipChangedListener(listener);observerRemoved=true}
                        catch(error: Throwable) {val prior=cleanupFailure;if(prior!=null)prior.addSuppressed(error) else cleanupFailure=error}
                    }
                }
                try {
                    epochGuard.requireClosedEmpty(immediateEmpty,closeBefore,closeAfter,observerRemoved,ownedClipCleared)
                    closeVerified=true;restored=true;emptyPreserved=!ownedClipCleared
                } catch(error: Throwable) {val prior=cleanupFailure;if(prior!=null)prior.addSuppressed(error) else cleanupFailure=error}
                // Publish restoration even when metadata refusal prevented any payload read/clear.
                try {
                    publish("clipboard-restoration.json",JSONObject().put("originalWasEmpty",true).put("originalEmptyPreserved",emptyPreserved)
                        .put("ownedClipClearPerformed",ownedClipCleared).put("verifiedOwnedClipCleared",ownedClipCleared&&restored)
                        .put("restorationVerified",restored).put("unknownEntryOverwrittenByCleanup",false)
                        .put("ownClearRequestIssued",epochGuard.clearRequested())
                        .put("immediateEmptyAfterClear",if(ownedClipCleared&&immediateEmpty?.knownState()==true)immediateEmpty!!.knownEmpty() else JSONObject.NULL)
                        .put("immediateObservation",presenceJson(immediateEmpty)).put("observerCloseBefore",presenceJson(closeBefore))
                        .put("observerCloseAfter",presenceJson(closeAfter)).put("observerRemoveAttempted",observerRemoveAttempted)
                        .put("observerRemoved",observerRemoved).put("observerCloseVerifiedEmpty",closeVerified)
                        .put("observedChanges",epochGuard.count()).put("priorContentRead",false)
                        .put("cleanupFreshEpochRechecked",cleanupEpochRechecked).put("exactSelectionMatched",exactSelectionMatched).put("cleanupOwnedOriginalSlice",ownedValue!=null))
                } catch(error: Throwable) {val prior=cleanupFailure;if(prior!=null)prior.addSuppressed(error) else cleanupFailure=error}
                try {publish("clipboard-observer-metadata.json",clipboardTrace(epochGuard))}
                catch(error: Throwable) {val prior=cleanupFailure;if(prior!=null)prior.addSuppressed(error) else cleanupFailure=error}
            }
            if(!restored&&cleanupFailure==null)cleanupFailure=IllegalStateException("Own clipboard restore not verified; unknown content was preserved")
            cleanupFailure?.let {error->val originalFailure=primaryFailure;if(originalFailure!=null)originalFailure.addSuppressed(error) else throw error}
        }
        ownedWindow();original();capture("copy-return")
    }
    fun verify() {
        check(plan.getBoolean("uiInteractionsApproved")&&plan.getString("clipboardPolicy")=="empty-only")
        coverEveryLine();returns();copyMarkerLines()
        publish("native-interactions-complete.json",JSONObject().put("fullLongBodyVisualCoverageVerified",true)
            .put("nativeMultilineSelectionAndClipboardVerified",true).put("menuBackVerified",true).put("sameParentPageReturnVerified",true)
            .put("newModelInputs",0).put("bodySha256",expected.getString("detailSha256")))
    }
}
