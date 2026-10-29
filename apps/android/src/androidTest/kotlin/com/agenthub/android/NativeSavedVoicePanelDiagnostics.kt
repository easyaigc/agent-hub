package com.agenthub.android

import android.graphics.Rect
import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.IdentityHashMap

/** Exact-package AND exact-window observation. The incoming root is borrowed; every newly
 * acquired child/relation/window is released even when a framework read returns after deadline. */
internal class NativeSavedVoicePanelDiagnostics(private val folder: File) {
    private var axSnapshots=0;private var layoutSnapshots=0;private var textSnapshots=0;private var bytesWritten=0L
    private class Owned(private val borrowed: AccessibilityNodeInfo) {
        private val nodes=IdentityHashMap<AccessibilityNodeInfo,Boolean>()
        private val windows=IdentityHashMap<AccessibilityWindowInfo,Boolean>()
        var failures=0;private set
        fun node(n: AccessibilityNodeInfo?)=n?.also {if(it!==borrowed)nodes[it]=true}
        fun window(w: AccessibilityWindowInfo?)=w?.also {windows[it]=true}
        fun close() {
            nodes.keys.forEach {try{it.recycle()}catch(_: Throwable){failures++}}
            windows.keys.forEach {try{it.recycle()}catch(_: Throwable){failures++}}
        }
        fun summary()=JSONObject().put("acquiredNodes",nodes.size).put("acquiredWindows",windows.size)
            .put("releaseFailures",failures).put("borrowedRootRecycled",false)
    }
    private fun write(kind: String,index: Int,tag: String,data: JSONObject,budget: NativeSavedVoiceReadBudget): JSONObject {
        require(tag.matches(Regex("[A-Za-z0-9_-]{1,100}")))
        fun receipt(reason: String)=JSONObject().put("version",1).put("tag",tag).put("complete",false)
            .put("reason",reason).put("timing",budget.timing()).put("lateContentDiscarded",true)
        var encoded=try {
            if(!budget.active())NativeSavedVoiceDiagnosticJson.encode(receipt("deadline-exceeded"),null)
            else NativeSavedVoiceDiagnosticJson.encode(data,budget)
        }catch(t: Throwable){NativeSavedVoiceDiagnosticJson.encode(receipt(if(budget.exceeded)"deadline-exceeded"else"generation-or-encoding-limit").put("failureClass",t.javaClass.name),null)}
        // Late framework results are never serialized; if the encoding itself missed the deadline,
        // publish only non-content timing. A blocking framework/filesystem call is not interruptible.
        if(!budget.active())encoded=NativeSavedVoiceDiagnosticJson.encode(receipt("deadline-exceeded"),null)
        require(bytesWritten+encoded.size<=8L*1024*1024)
        val file=File(folder,"%s-%03d-%s.json".format(kind,index,tag))
        if(!NativeSavedVoiceDiagnosticJson.immutable(file,encoded,if(budget.active())budget else null)) {
            encoded=NativeSavedVoiceDiagnosticJson.encode(receipt("deadline-during-write"),null)
            NativeSavedVoiceDiagnosticJson.immutable(file,encoded)
        }
        bytesWritten+=encoded.size
        val value=JSONObject(String(encoded,Charsets.UTF_8))
        return JSONObject().put("file",file.name).put("bytes",encoded.size).put("complete",value.optBoolean("complete",false))
            .put("elapsedMs",budget.timing().getLong("elapsedMs")).put("deadlineExceeded",budget.exceeded)
    }
    fun buttonLayouts(tag: String,expected: String,rows: JSONArray,complete: Boolean,budget: NativeSavedVoiceReadBudget): JSONObject {
        require(++layoutSnapshots<=96)
        return write("button-layout",layoutSnapshots,tag,JSONObject().put("version",1).put("tag",tag)
            .put("expected",budget.clip(expected,2048)).put("complete",complete&&!budget.truncated).put("layouts",rows),budget)
    }
    fun textLayout(tag: String,expected: String,rows: JSONArray,complete: Boolean,budget: NativeSavedVoiceReadBudget): JSONObject {
        require(++textSnapshots<=160)
        return write("layout",textSnapshots,tag,JSONObject().put("version",1).put("tag",tag)
            .put("expected",budget.clip(expected,2048)).put("complete",complete&&!budget.truncated).put("layouts",rows),budget)
    }
    fun ax(root: AccessibilityNodeInfo?,tag: String,expected: String,enabled: Boolean,selected: Boolean?,serviceFlags: Int): JSONObject {
        require(++axSnapshots<=64);val budget=NativeSavedVoiceReadBudget();val target=NativeRealFileAsrContract.PACKAGE
        val data=JSONObject().put("version",1).put("tag",tag).put("expected",budget.clip(expected,2048)).put("expectedEnabled",enabled)
            .put("expectedSelected",selected ?: JSONObject.NULL).put("uiAutomationServiceFlags",serviceFlags)
        if(root==null)return write("ax-owner",axSnapshots,tag,data.put("complete",false).put("observation","root-unavailable"),budget)
        val owned=Owned(root);var complete=true
        fun <T> read(block: ()->T)=budget.read(block)?.value
        fun same(value: CharSequence?,constant: String)=value!=null&&value.length==constant.length&&constant.indices.all {value[it]==constant[it]}
        val rootWindow=read {root.windowId}
        fun verified(n: AccessibilityNodeInfo)=rootWindow!=null&&rootWindow>=0&&read {n.windowId}==rootWindow&&read {same(n.packageName,target)}==true
        fun meta(n: AccessibilityNodeInfo): JSONObject {
            val result=JSONObject().put("package",budget.clip(read {n.packageName},128) ?: JSONObject.NULL).put("windowId",read {n.windowId} ?: JSONObject.NULL)
                .put("viewId",budget.clip(read {n.viewIdResourceName},160) ?: JSONObject.NULL).put("class",budget.clip(read {n.className},160) ?: JSONObject.NULL)
            val screen=Rect();val parent=Rect();read {n.getBoundsInScreen(screen);n.getBoundsInParent(parent)}
            return result.put("screen",JSONArray(listOf(screen.left,screen.top,screen.right,screen.bottom)))
                .put("parent",JSONArray(listOf(parent.left,parent.top,parent.right,parent.bottom)))
        }
        fun linked(n: AccessibilityNodeInfo?): Any {
            if(n==null)return JSONObject.NULL
            val value=meta(n)
            if(verified(n))value.put("text",budget.clip(read {n.text})).put("contentDescription",budget.clip(read {n.contentDescription}))
            else {complete=false;value.put("contentOmitted",if(read {n.windowId}==rootWindow)"same-window-package-unverified"else"foreign-or-unknown-window")}
            return value
        }
        try {
            data.put("root",meta(root))
            if(!verified(root)){complete=false;data.put("observation","unverified-root-metadata-only")}
            else {
                val owners=mutableListOf<Pair<AccessibilityNodeInfo,List<JSONObject>>>();val stopped=JSONArray();var searched=0
                fun search(n: AccessibilityNodeInfo,depth: Int,parents: List<JSONObject>) {
                    if(!budget.active()||depth>32||searched>=512){complete=false;return};searched++
                    if(!verified(n)){complete=false;if(stopped.length()<8)stopped.put(meta(n).put("contentAndRecursionOmitted",true));return}
                    if(read {n.viewIdResourceName}==tag)owners.add(n to parents)
                    val count=read {n.childCount}?:return
                    if(count>512)complete=false
                    for(i in 0 until minOf(count,512)) {
                        if(!budget.active())return
                        val child=read {owned.node(n.getChild(i))}
                        if(child==null)complete=false else search(child,depth+1,parents+meta(n))
                    }
                }
                search(root,0,emptyList());var dumped=0;var descendantMatch=false
                fun dump(n: AccessibilityNodeInfo,depth: Int,path: String): JSONObject {
                    if(!budget.active()||depth>16||dumped>=48){complete=false;return JSONObject().put("path",path).put("truncated",true)};dumped++
                    val value=meta(n).put("path",path)
                    if(!verified(n)){complete=false;return value.put("contentAndRecursionOmitted",true)}
                    val text=budget.clip(read {n.text});val description=budget.clip(read {n.contentDescription})
                    val spoken=listOfNotNull(text,description).joinToString(" ");if(depth>0&&spoken.contains(expected))descendantMatch=true
                    value.put("text",text).put("contentDescription",description).put("hint",budget.clip(read {n.hintText})).put("error",budget.clip(read {n.error}))
                        .put("enabled",read {n.isEnabled}).put("visible",read {n.isVisibleToUser}).put("selected",read {n.isSelected})
                        .put("checkable",read {n.isCheckable}).put("checked",read {n.isChecked}).put("clickable",read {n.isClickable})
                        .put("focusable",read {n.isFocusable}).put("focused",read {n.isFocused}).put("accessibilityFocused",read {n.isAccessibilityFocused})
                        .put("importantForAccessibility",read {n.isImportantForAccessibility}).put("scrollable",read {n.isScrollable}).put("editable",read {n.isEditable})
                        .put("labelFor",linked(read {owned.node(n.labelFor)})).put("labeledBy",linked(read {owned.node(n.labeledBy)}))
                    if(Build.VERSION.SDK_INT>=28)value.put("heading",read {n.isHeading}).put("screenReaderFocusable",read {n.isScreenReaderFocusable})
                        .put("paneTitle",budget.clip(read {n.paneTitle})).put("tooltip",budget.clip(read {n.tooltipText}))
                    if(Build.VERSION.SDK_INT>=30)value.put("stateDescription",budget.clip(read {n.stateDescription}))
                    val actions=read {n.actionList}.orEmpty();if(actions.size>8)complete=false
                    value.put("actionCount",actions.size).put("actions",JSONArray(actions.take(8).map {JSONObject().put("id",it.id).put("label",budget.clip(it.label,96))}))
                    val count=read {n.childCount}?:0;value.put("childCount",count);val children=JSONArray()
                    for(i in 0 until minOf(count,48)) {
                        if(!budget.active()||dumped>=48){complete=false;break}
                        val child=read {owned.node(n.getChild(i))}
                        if(child==null)complete=false else children.put(dump(child,depth+1,"$path/$i"))
                    }
                    if(count>48)complete=false
                    return value.put("children",children)
                }
                val snapshots=JSONArray()
                for((owner,parents)in owners.take(4)) {
                    if(!budget.active())break
                    val tree=dump(owner,0,"owner").put("ancestorMetadata",JSONArray(parents))
                    val ownText=listOfNotNull(tree.optString("text",null),tree.optString("contentDescription",null)).joinToString(" ")
                    tree.put("exactOwnerContainsExpected",if(ownText.contains(expected))true else if(budget.truncated)JSONObject.NULL else false)
                    val window=read {owned.window(owner.window)}
                    if(window!=null&&read {window.id}==rootWindow&&verified(owner)) {
                        val bounds=Rect();read {window.getBoundsInScreen(bounds)}
                        tree.put("window",JSONObject().put("id",read {window.id}).put("type",read {window.type}).put("layer",read {window.layer})
                            .put("active",read {window.isActive}).put("focused",read {window.isFocused})
                            .put("bounds",JSONArray(listOf(bounds.left,bounds.top,bounds.right,bounds.bottom))).put("title",budget.clip(read {window.title},160)))
                    }else {complete=false;tree.put("windowContentOmitted",true)}
                    snapshots.put(tree)
                }
                if(owners.size>4)complete=false
                data.put("searchedNodes",searched).put("exactOwnerCount",owners.size).put("dumpedNodes",dumped).put("stoppedScopes",stopped)
                    .put("anyDescendantContainsExpected",if(descendantMatch)true else if(!complete||budget.truncated)JSONObject.NULL else false).put("owners",snapshots)
            }
        }catch(t: Throwable){complete=false;data.put("observationFailure",t.javaClass.name)}
        finally {owned.close();if(owned.failures>0)complete=false}
        return write("ax-owner",axSnapshots,tag,data.put("complete",complete&&budget.active()&&!budget.truncated).put("resources",owned.summary()),budget)
    }
}
