package com.agenthub.android

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import android.provider.DocumentsContract
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/** Narrow AX driver for two named Main selectors. V2 independently pins only system control
 * roles; owned UUID rows stay unknown until observed at runtime. No guessed IDs/default device,
 * arbitrary caption, shell or result hook. Never emits a hierarchy/password/other row text. */
internal class NativeMigrationMainSaf(private val run: String,val kind: NativeMigrationPickerKind,
    private val pump: ()->Unit) {
    private val i=InstrumentationRegistry.getInstrumentation()
    private val app=i.targetContext
    private val test=i.context
    // New framework protocol has its own admission/profile/source seal. Legacy maps stay fixed.
    private val frameworkV4=InstrumentationRegistry.getArguments().getString("migrationMainSafFrameworkV4")=="1"
    // Target instrumentation has a DISTINCT UID: never read/write the runner sandbox here.
    val providerRelativeDirectory="files/${if(frameworkV4)"p7-main-saf-owned-v3" else "p7-main-saf-owned-v1"}/$run"
    private val title="AH_P7_${run.replace("-","")}"
    private val authority="${test.packageName}.${if(frameworkV4)"p7-main-saf-v3" else "p7-main-saf"}"
    val documentId="p7-${if(kind==NativeMigrationPickerKind.IMPORT)"input" else "output"}-$run"
    val uri: Uri=DocumentsContract.buildDocumentUri(authority,documentId)
    val filename="agent-hub-p7-${kind.name.lowercase(java.util.Locale.ROOT)}-$run.codexmigration"
    private val profile: JSONObject
    private val docs: String
    private val docsUid: Int
    private var oldFlags: Int?=null
    private val providerAuditRequired=true
    private var expectedOutput: Pair<Long,String>?=null
    private var originalPickerId: String?=null
    private var ownedDirectoryWindow: Int?=null
    private val profileV2 get()=profile.optString("schema") in setOf("p7-main-saf-docsui-role-profile-v2","p7-main-saf-docsui-framework-profile-v4")
    // Actual null resource IDs are observations, never invented ID defaults.
    private val runtimeOwnedIds=linkedMapOf<String,String?>()
    private val runtimeOwnedWindows=linkedMapOf<String,Int>()
    private val runtimeOwnedTextSha256=linkedMapOf<String,String>()
    private var directoryTextSha256: String?=null
    private val runtimeFreshActions=linkedSetOf<String>()
    private var selectedOwnedRootWindow: Int?=null
    private enum class Step { SHOW_ROOTS,CHOOSE_ROOT,CHOOSE_DOCUMENT,SET_FILENAME,SAVE,OPEN }
    private val attemptedSteps=linkedSetOf<Step>()
    private var actionIssued=false
    private var callbackObserved=false
    private var closed=false
    private val admission: JSONObject
    private val expectedInput: Pair<Long,String>?
    init {
        require(UUID.fromString(run).toString()==run)
        check(BuildConfig.DEBUG&&app.packageName=="com.easyai.agenthub.android.acceptance"&&test.packageName=="${app.packageName}.test")
        admission=NativeMigrationMainSafAdmission.accept(run,kind.name)
        if(frameworkV4)check(kind in setOf(NativeMigrationPickerKind.EXPORT,NativeMigrationPickerKind.IMPORT))
        val args=InstrumentationRegistry.getArguments()
        check(args.getString("migrationMainSafOwnedLease")=="1")
        profile=JSONObject(checkNotNull(args.getString("migrationMainSafProfile")){"Independently observed current DocsUI profile required"})
        check((profile.optString("schema")=="p7-main-saf-docsui-framework-profile-v4")==frameworkV4)
        val keys=setOf("package","rootsButtonId","rootTitleId","directoryTitleId","documentTitleId","fileNameId","saveId","openButtonId")
        docs=profile.getString("package");check(docs in setOf("com.android.documentsui","com.google.android.documentsui"))
        check(NativeMigrationMainSaf.fingerprintText(checkNotNull(args.getString("migrationMainSafProfile")))==admission.getJSONObject("binding").getString("profileSha256"))
        if(profileV2)validateV2Profile() else {
            check(profile.keys().asSequence().all {it in keys})
            for(key in keys-"package"-"openButtonId")id(key)
            if(profile.has("openButtonId"))id("openButtonId")
        }
        val info=checkNotNull(app.packageManager.resolveActivity(Intent(if(kind==NativeMigrationPickerKind.IMPORT)Intent.ACTION_OPEN_DOCUMENT else Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE).setType("application/octet-stream"),PackageManager.MATCH_DEFAULT_ONLY)).activityInfo
        check(info.packageName==docs&&info.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM!=0)
        docsUid=info.applicationInfo.uid;check(docsUid==admission.getJSONObject("binding").getInt("docsUid"));check(docsUid!=Process.myUid()&&test.applicationInfo.uid!=Process.myUid()&&test.applicationInfo.uid!=docsUid)
        if(profileV2)verifyV2SystemApk()
        @Suppress("DEPRECATION")
        val provider=app.packageManager.getProviderInfo(ComponentName(test.packageName,
            "com.agenthub.android.${if(frameworkV4)"OwnedMainMigrationDocumentsV3" else "OwnedMainMigrationDocuments"}"),0)
        check(provider.authority==authority&&provider.packageName==test.packageName&&provider.applicationInfo.uid==test.applicationInfo.uid&&
            provider.processName=="${test.packageName}:${if(frameworkV4)"p7MainSafV3" else "p7MainSaf"}"&&provider.exported&&provider.grantUriPermissions&&provider.readPermission=="android.permission.MANAGE_DOCUMENTS"&&provider.writePermission=="android.permission.MANAGE_DOCUMENTS")
        if(frameworkV4) {
            @Suppress("DEPRECATION")
            val controller=app.packageManager.getProviderInfo(ComponentName(test.packageName,"com.agenthub.android.OwnedMainMigrationControlV4"),0)
            check(controller.authority=="${test.packageName}.p7-main-saf-control-v4"&&controller.packageName==test.packageName&&
                controller.applicationInfo.uid==test.applicationInfo.uid&&controller.processName=="${test.packageName}:p7MainSafControlV4"&&
                controller.exported&&!controller.grantUriPermissions&&controller.readPermission=="android.permission.DUMP"&&controller.writePermission=="android.permission.DUMP")
        }
        check(permission(Intent.FLAG_GRANT_READ_URI_PERMISSION)==PackageManager.PERMISSION_DENIED&&permission(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)==PackageManager.PERMISSION_DENIED)
        // Root must preflight a fresh exact run. Framework-v4 prepares via the Test-UID component;
        // its original immutable receipt and actual controller owner are independently joined.
        // This token is an intent/binding requirement, NOT proof of provider I/O or absence.
        check(args.getString("migrationMainSafRun")==run&&args.getString("migrationMainSafProviderPrepared")=="1")
        expectedInput=if(kind==NativeMigrationPickerKind.IMPORT) {
            val size=checkNotNull(args.getString("migrationMainSafInputBytes")).toLong()
            val sha=checkNotNull(args.getString("migrationMainSafInputSha256"))
            check(size in 1..2*1024*1024&&sha.matches(Regex("[a-f0-9]{64}")))
            check(admission.getJSONObject("binding").getLong("inputBytes")==size&&admission.getJSONObject("binding").getString("inputSha256")==sha)
            size to sha
        } else null
    }

    fun enableAccessibility() {
        check(oldFlags==null);val service=i.uiAutomation.serviceInfo;oldFlags=service.flags
        service.flags=service.flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS;i.uiAutomation.serviceInfo=service
    }
    private fun id(key: String): String=profile.getString(key).also {check(it.matches(Regex("(?:${Regex.escape(docs)}|android):id/[A-Za-z0-9_]+")))}
    private fun validateV2Profile() {
        check(profile.keys().asSequence().toSet()==setOf("schema","package","systemUid","systemApkSha256","api","controlObservationSha256","navigation","directoryTitleId","fileNameId","saveId","openButton","owned"))
        check(docs=="com.google.android.documentsui"&&profile.getInt("api")==33&&android.os.Build.VERSION.SDK_INT==33)
        check(profile.getInt("systemUid")==admission.getJSONObject("binding").getInt("docsUid"))
        for(key in listOf("systemApkSha256","controlObservationSha256"))check(profile.getString(key).matches(Regex("[a-f0-9]{64}")))
        check(id("directoryTitleId")=="$docs:id/breadcrumb_text"&&id("fileNameId")=="android:id/title"&&id("saveId")=="android:id/button1")
        val nav=profile.getJSONObject("navigation")
        check(nav.keys().asSequence().toSet()==setOf("state","role","buttonBounds","toolbarBounds"))
        check(nav.getString("state")=="OBSERVED"&&nav.getString("role")=="toolbar-show-roots-en-v1")
        for(key in listOf("buttonBounds","toolbarBounds")) {
            val a=nav.getJSONArray(key);check(a.length()==4)
            for(j in 0..3)check(a.getInt(j) in 0..4096)
            check(a.getInt(0)<a.getInt(2)&&a.getInt(1)<a.getInt(3))
        }
        val b=nav.getJSONArray("buttonBounds");val t=nav.getJSONArray("toolbarBounds")
        check(b.getInt(0)==t.getInt(0)&&b.getInt(1)==t.getInt(1)&&b.getInt(3)==t.getInt(3)&&b.getInt(2)<t.getInt(2))
        val open=profile.getJSONObject("openButton")
        check(open.keys().asSequence().toSet()==setOf("state","resourceId")&&open.getString("state")=="UNKNOWN"&&open.isNull("resourceId"))
        val owned=profile.getJSONObject("owned");check(owned.keys().asSequence().toSet()==setOf("rootTitle","documentTitle"))
        for(key in listOf("rootTitle","documentTitle")) {
            val row=owned.getJSONObject(key)
            check(row.keys().asSequence().toSet()==setOf("state","resourceId")&&row.getString("state")=="RUNTIME_REQUIRED"&&row.isNull("resourceId"))
        }
    }
    private fun verifyV2SystemApk() {
        val info=app.packageManager.getApplicationInfo(docs,0)
        check(info.uid==docsUid&&info.uid==profile.getInt("systemUid")&&info.flags and ApplicationInfo.FLAG_SYSTEM!=0)
        val file=File(info.sourceDir);check(file.isFile&&file.length() in 1..64L*1024*1024)
        val digest=MessageDigest.getInstance("SHA-256");var size=0L
        file.inputStream().use {input->val bytes=ByteArray(65536);while(true){val n=input.read(bytes);if(n<0)break;size+=n;check(size<=64L*1024*1024);digest.update(bytes,0,n)}}
        check(size==file.length()&&digest.digest().joinToString("") {"%02x".format(it)}==profile.getString("systemApkSha256"))
    }
    private fun rectIs(node: AccessibilityNodeInfo,key: String): Boolean {
        val r=android.graphics.Rect();node.getBoundsInScreen(r);val a=profile.getJSONObject("navigation").getJSONArray(key)
        return listOf(r.left,r.top,r.right,r.bottom)==(0..3).map {a.getInt(it)}
    }
    /** Only this observed framework navigation role can lack an ID. No arbitrary caption. */
    private fun navigationRole(n: AccessibilityNodeInfo): Boolean {
        if(n.className?.toString()!="android.widget.ImageButton"||!n.viewIdResourceName.isNullOrEmpty()||
            n.contentDescription?.toString()!="Show roots"||!n.isClickable||!n.isEnabled||n.isEditable||n.isLongClickable||
            n.actions and AccessibilityNodeInfo.ACTION_CLICK==0||!rectIs(n,"buttonBounds"))return false
        val acquired=mutableListOf<AccessibilityNodeInfo>()
        try {
            val toolbar=n.parent?:return false;acquired+=toolbar
            val collapsing=toolbar.parent?:return false;acquired+=collapsing
            val bar=collapsing.parent?:return false;acquired+=bar
            val child=toolbar.getChild(0)?:return false;acquired+=child
            return acquired.all {it.refresh()&&it.packageName?.toString()==docs&&it.windowId==n.windowId}&&child==n&&
                toolbar.viewIdResourceName=="$docs:id/toolbar"&&toolbar.className?.toString()=="android.view.ViewGroup"&&rectIs(toolbar,"toolbarBounds")&&
                collapsing.viewIdResourceName=="$docs:id/collapsing_toolbar"&&collapsing.className?.toString()=="android.widget.FrameLayout"&&
                bar.viewIdResourceName=="$docs:id/app_bar"&&bar.className?.toString()=="android.widget.LinearLayout"
        }finally{@Suppress("DEPRECATION")acquired.forEach {if(it!==n)it.recycle()}}
    }
    private fun breadcrumbRole(n: AccessibilityNodeInfo): Boolean {
        val acquired=mutableListOf<AccessibilityNodeInfo>()
        try {
            val row=n.parent?:return false;acquired+=row
            val list=row.parent?:return false;acquired+=list
            return acquired.all {it.refresh()&&it.packageName?.toString()==docs&&it.windowId==n.windowId}&&
                row.className?.toString()=="android.widget.LinearLayout"&&list.viewIdResourceName=="$docs:id/horizontal_breadcrumb"&&
                list.className?.toString()=="androidx.recyclerview.widget.RecyclerView"
        }finally{@Suppress("DEPRECATION")acquired.forEach {it.recycle()}}
    }
    private fun ownedRole(n: AccessibilityNodeInfo,key: String,text: String?): Boolean {
        val expected=if(key=="rootTitleId")title else filename
        if(text!=expected||n.text?.toString()!=expected||n.className?.toString()!="android.widget.TextView"||n.isEditable)return false
        if(key=="documentTitleId"&&(selectedOwnedRootWindow!=n.windowId||ownedDirectoryWindow!=n.windowId))return false
        val actual=n.viewIdResourceName?.takeIf {it.isNotEmpty()}
        if(actual!=null&&!actual.matches(Regex("(?:${Regex.escape(docs)}|android):id/[A-Za-z0-9_]+")))return false
        return !runtimeOwnedIds.containsKey(key)||runtimeOwnedIds[key]==actual&&runtimeOwnedWindows[key]==n.windowId
    }
    private fun bindOwnedRole(key: String,leaf: AccessibilityNodeInfo,window: Int) {
        if(!profileV2||key !in setOf("rootTitleId","documentTitleId"))return
        check(ownedRole(leaf,key,if(key=="rootTitleId")title else filename))
        check(!runtimeOwnedIds.containsKey(key));runtimeOwnedIds[key]=leaf.viewIdResourceName?.takeIf {it.isNotEmpty()};runtimeOwnedWindows[key]=window
        runtimeOwnedTextSha256[key]=fingerprintText(checkNotNull(leaf.text).toString())
    }
    private fun runtimeRole(key: String)=JSONObject().put("state",if(runtimeOwnedIds.containsKey(key))"BOUND" else if(key=="documentTitleId"&&kind==NativeMigrationPickerKind.EXPORT)"NOT_APPLICABLE" else "UNKNOWN")
        .put("resourceId",runtimeOwnedIds[key]?:JSONObject.NULL).put("resourceIdObserved",runtimeOwnedIds.containsKey(key))
        .put("className",if(runtimeOwnedIds.containsKey(key))"android.widget.TextView" else JSONObject.NULL)
        .put("windowId",runtimeOwnedWindows[key]?:JSONObject.NULL).put("observedTextSha256",runtimeOwnedTextSha256[key]?:JSONObject.NULL)
        .put("finalFreshPathChecked",key in runtimeFreshActions)
    private fun permission(flags: Int)=app.checkUriPermission(uri,Process.myPid(),Process.myUid(),flags)
    private fun owner(): String? {
        val root=i.uiAutomation.rootInActiveWindow?:return null
        try{return if(root.refresh())root.packageName?.toString() else null}finally{@Suppress("DEPRECATION")root.recycle()}
    }
    private fun <T> nodes(block: (List<AccessibilityNodeInfo>)->T): T {
        val root=checkNotNull(i.uiAutomation.rootInActiveWindow);val all=mutableListOf<AccessibilityNodeInfo>()
        fun visit(n: AccessibilityNodeInfo,depth: Int) {
            all+=n;check(depth<=40&&all.size<=2048&&n.packageName?.toString()==docs&&n.windowId==root.windowId)
            for(index in 0 until n.childCount)n.getChild(index)?.let {visit(it,depth+1)}
        }
        try {check(root.refresh()&&root.packageName?.toString()==docs&&root.windowId>=0&&root.isVisibleToUser);visit(root,0);return block(all)}
        finally{@Suppress("DEPRECATION")all.forEach {it.recycle()};if(all.none {it===root}){@Suppress("DEPRECATION")root.recycle()}}
    }
    private fun matches(n: AccessibilityNodeInfo,key: String,text: String?=null,editable: Boolean=false): Boolean {
        if(!n.refresh()||n.packageName?.toString()!=docs||!n.isVisibleToUser||n.isPassword)return false
        if(profileV2) {
            if(key=="rootsButtonId")return navigationRole(n)
            if(key in setOf("rootTitleId","documentTitleId"))return !editable&&ownedRole(n,key,text)
            if(key=="openButtonId")return false // unobserved; callback-only, never guess/click Open.
            val role=when(key){"directoryTitleId"->"android.widget.TextView";"fileNameId"->"android.widget.EditText";"saveId"->"android.widget.Button";else->return false}
            if(n.className?.toString()!=role)return false
            if(key=="directoryTitleId"&&!breadcrumbRole(n))return false
            if(key=="fileNameId"&&n.text?.toString()!=filename)return false
            if(key=="saveId"&&(n.text?.toString()!="SAVE"||!n.isClickable||!n.isEnabled))return false
        }
        return n.viewIdResourceName==id(key)&&(if(editable)n.isEditable else !n.isEditable)&&(text==null||n.text?.toString()==text)
    }
    private fun one(all: List<AccessibilityNodeInfo>,key: String,text: String?=null,editable: Boolean=false)=all.count {matches(it,key,text,editable)}==1
    private fun directory(all: List<AccessibilityNodeInfo>)=one(all,"directoryTitleId",title)&&
        ownedDirectoryWindow?.let {it==all.first().windowId}!=false&&
        (!profileV2||selectedOwnedRootWindow==all.first().windowId)
    private fun stepContext(all: List<AccessibilityNodeInfo>,step: Step): Boolean=when(step) {
        Step.SHOW_ROOTS->!one(all,"rootTitleId",title)&&one(all,"rootsButtonId")
        Step.CHOOSE_ROOT->one(all,"rootTitleId",title)
        Step.CHOOSE_DOCUMENT->directory(all)&&one(all,"documentTitleId",filename)
        Step.SET_FILENAME->directory(all)&&one(all,"fileNameId",editable=true)
        Step.SAVE->directory(all)&&one(all,"fileNameId",editable=true)&&
            all.single {matches(it,"fileNameId",editable=true)}.text?.toString()==filename&&one(all,"saveId")
        Step.OPEN->directory(all)&&one(all,"documentTitleId",filename)&&one(all,"openButtonId")&&
            path(all.single {matches(it,"documentTitleId",filename)},all.first()) {row,_->row.dropLast(1).any {it.isSelected}}
    }
    /** Retain the entire leaf->root relation, not just a clickable parent from another row.
     * Equality is AccessibilityNodeInfo's remote-node identity, not text/package similarity. */
    private fun <T> path(leaf: AccessibilityNodeInfo,root: AccessibilityNodeInfo,block: (List<AccessibilityNodeInfo>,Int)->T): T {
        val chain=mutableListOf(leaf);val acquired=mutableListOf<AccessibilityNodeInfo>()
        try {
            while(chain.last()!=root) {
                check(chain.size<=40)
                val parent=checkNotNull(chain.last().parent);if(parent!==root&&parent!==leaf)acquired+=parent
                check(parent.refresh()&&parent.packageName?.toString()==docs&&parent.windowId==root.windowId&&chain.none {it==parent})
                chain+=parent
            }
            check(chain.all {it.refresh()&&it.packageName?.toString()==docs&&it.windowId==root.windowId})
            val clickable=chain.indexOfFirst {it.isClickable};return block(chain,clickable)
        }finally{@Suppress("DEPRECATION")acquired.forEach {it.recycle()}}
    }
    /** One typed action only. The final read occurs AFTER the original Main owner guard.
     * Any window/leaf/path/context change throws; it never navigates or tries another node. */
    private fun action(observed: List<AccessibilityNodeInfo>,step: Step,end: Long,guard: ()->Unit) {
        // The original select deadline gates new input; it cannot cancel an issued Binder call.
        fun beforeDeadline()=check(SystemClock.elapsedRealtime()<end) {"Original picker deadline elapsed; preserve operation without replay"}
        beforeDeadline()
        check(attemptedSteps.add(step)&&stepContext(observed,step))
        val key=when(step) {
            Step.SHOW_ROOTS->"rootsButtonId";Step.CHOOSE_ROOT->"rootTitleId";Step.CHOOSE_DOCUMENT->"documentTitleId"
            Step.SET_FILENAME->"fileNameId";Step.SAVE->"saveId";Step.OPEN->"openButtonId"
        }
        val text=when(step){Step.CHOOSE_ROOT->title;Step.CHOOSE_DOCUMENT->filename;else->null}
        val editable=step==Step.SET_FILENAME
        val oldRoot=observed.first();val oldWindow=oldRoot.windowId
        val oldLeaf=observed.single {matches(it,key,text,editable)}
        bindOwnedRole(key,oldLeaf,oldWindow)
        val oldField=if(step in setOf(Step.SET_FILENAME,Step.SAVE))observed.single {matches(it,"fileNameId",editable=true)}.text?.toString() else null
        path(oldLeaf,oldRoot) {oldPath,oldClick->
            beforeDeadline()
            guard()
            beforeDeadline()
            if(profileV2)verifyV2SystemApk()
            beforeDeadline()
            // A new active-root acquisition is mandatory, even when two readiness samples matched.
            nodes {fresh->
                beforeDeadline() // Fresh-root acquisition/traversal may have blocked.
                val root=fresh.first()
                check(root==oldRoot&&root.windowId==oldWindow&&stepContext(fresh,step))
                val leaf=fresh.single {matches(it,key,text,editable)};check(leaf==oldLeaf)
                if(step in setOf(Step.SET_FILENAME,Step.SAVE))check(fresh.single {matches(it,"fileNameId",editable=true)}.text?.toString()==oldField)
                path(leaf,root) {chain,click->
                    check(chain.size==oldPath.size&&chain.indices.all {chain[it]==oldPath[it]}&&click==oldClick)
                    if(step==Step.OPEN) {
                        val document=fresh.single {matches(it,"documentTitleId",filename)}
                        path(document,root) {row,_->check(row.dropLast(1).any {it.isSelected})}
                    }
                    check(editable||(click in 0..6&&click<chain.lastIndex)) {"Clickable target must stay in the exact leaf row, below root"}
                    val target=if(editable)leaf else chain.getOrNull(click) ?: error("Exact leaf has no clickable ancestor")
                    // Last refresh validates the exact root, synthetic context, leaf ID and relation.
                    check(root.refresh()&&root==oldRoot&&root.windowId==oldWindow&&root.isVisibleToUser&&stepContext(fresh,step))
                    check(matches(leaf,key,text,editable)&&leaf.windowId==oldWindow&&target.refresh()&&target.windowId==oldWindow&&target.packageName?.toString()==docs&&target.isVisibleToUser&&target.isEnabled)
                    beforeDeadline()
                    path(leaf,root) {last,lastClick->
                        check(last.size==chain.size&&last.indices.all {last[it]==chain[it]}&&lastClick==click)
                        val active=checkNotNull(i.uiAutomation.rootInActiveWindow)
                        try {check(active.refresh()&&active==root&&active.windowId==oldWindow&&active.packageName?.toString()==docs&&active.isVisibleToUser)}
                        finally {if(fresh.none {it===active}){@Suppress("DEPRECATION")active.recycle()}}
                        beforeDeadline()
                        // These are the final refreshed leaf/context/path objects used for input.
                        check(matches(leaf,key,text,editable)&&stepContext(fresh,step)&&target.isEnabled&&target.isVisibleToUser&&target.windowId==oldWindow)
                        check(target==last[if(editable)0 else lastClick]&&last.all {it.windowId==oldWindow&&it.packageName?.toString()==docs})
                        if(editable) {
                            val arguments=Bundle().apply {putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,filename)}
                            check(leaf.isEnabled)
                            beforeDeadline()
                            check(leaf.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,arguments))
                        } else {
                            check(target.isClickable)
                            beforeDeadline()
                            check(target.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                        }
                    }
                }
            }
        }
        if(profileV2) {
            if(step==Step.CHOOSE_ROOT)selectedOwnedRootWindow=oldWindow
            if(key in setOf("rootTitleId","documentTitleId"))runtimeFreshActions+=key
        }
    }
    /** Shared original-request deadline; no action is retried, and no fallback directory/search. */
    fun select(request: NativeMigrationPicker,guard: ()->Unit) {
        check(!actionIssued&&request.kind==kind&&request.launched);originalPickerId=request.id;actionIssued=true
        val end=SystemClock.elapsedRealtime()+30_000
        fun waitFor(ready: (List<AccessibilityNodeInfo>)->Boolean,act: ((List<AccessibilityNodeInfo>)->Unit)?=null) {
            var stable=0
            while(SystemClock.elapsedRealtime()<end) {
                guard();val current=owner();check(current==null||current==docs||current==app.packageName)
                if(current==docs) {
                    var done=false
                    nodes {all->stable=if(ready(all))stable+1 else 0;if(stable>=2){guard();check(SystemClock.elapsedRealtime()<end);act?.invoke(all);done=true}}
                    if(done)return
                }else stable=0
                pump();SystemClock.sleep(40)
            }
            error("Original owned system picker did not stabilize; retain same operation without replay")
        }
        waitFor({all->one(all,"rootTitleId",title)||one(all,"rootsButtonId")}) {all->
            if(!one(all,"rootTitleId",title))action(all,Step.SHOW_ROOTS,end,guard)
        }
        waitFor({all->one(all,"rootTitleId",title)}) {all->action(all,Step.CHOOSE_ROOT,end,guard)}
        waitFor({all->directory(all)}) {all->
            check(ownedDirectoryWindow==null);ownedDirectoryWindow=all.first().windowId
            if(profileV2)directoryTextSha256=fingerprintText(checkNotNull(all.single {matches(it,"directoryTitleId",title)}.text).toString())
        }
        if(kind==NativeMigrationPickerKind.IMPORT) {
            waitFor({all->one(all,"directoryTitleId",title)&&one(all,"documentTitleId",filename)}) {all->action(all,Step.CHOOSE_DOCUMENT,end,guard)}
            if(!profileV2&&profile.has("openButtonId")) {
                var returned=false
                while(SystemClock.elapsedRealtime()<end) {
                    val current=owner();check(current==null||current==docs||current==app.packageName)
                    if(current==app.packageName){returned=true;break}
                    if(current==docs&&nodes {stepContext(it,Step.OPEN)}) {
                        waitFor({stepContext(it,Step.OPEN)}) {action(it,Step.OPEN,end,guard)};returned=true;break
                    }
                    pump();SystemClock.sleep(40)
                }
                check(returned) {"Original open action result unknown; no second selection"}
            }
        } else {
            waitFor({all->one(all,"directoryTitleId",title)&&one(all,"fileNameId",editable=true)}) {all->action(all,Step.SET_FILENAME,end,guard)}
            waitFor({all->one(all,"directoryTitleId",title)&&one(all,"fileNameId",editable=true)&&
                all.single {matches(it,"fileNameId",editable=true)}.text?.toString()==filename&&one(all,"saveId")}) {all->action(all,Step.SAVE,end,guard)}
        }
        // After final action only observe; changed/unknown caller is never driven again.
        while(SystemClock.elapsedRealtime()<end){val current=owner();check(current==null||current==docs||current==app.packageName);if(current==app.packageName)return;pump();SystemClock.sleep(40)}
        error("Original picker callback unknown; preserve provider, grant and owners")
    }
    fun verifyCallback(expected: Pair<Long,String>) {
        check(actionIssued&&!callbackObserved&&owner()==app.packageName)
        val flags=if(kind==NativeMigrationPickerKind.IMPORT)Intent.FLAG_GRANT_READ_URI_PERMISSION else Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        check(permission(flags)==PackageManager.PERMISSION_GRANTED) {"Exact URI needs a real OS grant"}
        check(expected.first in 1..2*1024*1024&&expected.second.matches(Regex("[a-f0-9]{64}")))
        if(kind==NativeMigrationPickerKind.IMPORT)check(expectedInput==expected) else expectedOutput=expected
        // The test cannot read another UID's provider audit. Root independently joins its
        // exact create/open/close records and payload fingerprint AFTER this worker exits.
        callbackObserved=true
    }
    fun inputFingerprint()=checkNotNull(expectedInput)
    fun summary()=JSONObject().put("admission",admission).put("run",run).put("kind",kind.name).put("authority",authority).put("documentId",documentId).put("uri",uri.toString())
        .put("providerPackage",test.packageName).put("providerUid",test.applicationInfo.uid).put("docsPackage",docs).put("docsUid",docsUid)
        .put("pickerActionSteps",org.json.JSONArray(attemptedSteps.map {it.name})).put("pickerId",originalPickerId ?: JSONObject.NULL).put("filename",filename).put("actionIssued",actionIssued).put("callbackAndGrantObserved",callbackObserved)
        .put("expectedBytes",(expectedInput?:expectedOutput)?.first ?: JSONObject.NULL).put("expectedSha256",(expectedInput?:expectedOutput)?.second ?: JSONObject.NULL)
        .put("providerAuditRequired",providerAuditRequired).put("providerAuditVerified",false)
        .put("providerMaterialRetained",true).put("providerRelativeDirectory",providerRelativeDirectory).put("postRuleProcessExitVerified",false)
        .apply {if(frameworkV4)put("protocolVersion","p7-main-saf-framework-v4").put("controllerAuthority","${test.packageName}.p7-main-saf-control-v4")}
        .apply {if(profileV2)put("runtimeOwnedRoles",JSONObject().put("directoryWindowId",ownedDirectoryWindow?:JSONObject.NULL)
            .put("directoryTextSha256",directoryTextSha256?:JSONObject.NULL).put("profileSha256",admission.getJSONObject("binding").getString("profileSha256"))
            .put("rootTitle",runtimeRole("rootTitleId")).put("documentTitle",runtimeRole("documentTitleId")))}
    fun restoreAccessibilityFlags() {check(!closed);closed=true;oldFlags?.let {flags->val service=i.uiAutomation.serviceInfo;service.flags=flags;i.uiAutomation.serviceInfo=service}}
    companion object {
        fun fingerprintText(value: String)=MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") {"%02x".format(it)}
        fun fingerprint(file: File): Pair<Long,String> {
            val digest=MessageDigest.getInstance("SHA-256");var size=0L
            file.inputStream().use {input->val block=ByteArray(65536);while(true){val n=input.read(block);if(n<0)break;size+=n;check(size<=2*1024*1024);digest.update(block,0,n)}}
            return size to digest.digest().joinToString("") {"%02x".format(it)}
        }
    }
}

/** One shared synthetic metadata shape; the Main assertions and the real crypto producer use it. */
internal object NativeMigrationMainSafMetadata {
    fun value(run: String,draft: String): JSONObject {
        require(UUID.fromString(run).toString()==run)
        val key="owned-main-ui-draft-$run"
        return JSONObject().put("version",1).put("appearance","light").put("language","en")
            .put("demo",true).put("selected",DemoMobileData.clients.first().id).put("clients",org.json.JSONArray())
            .put("drafts",JSONObject().put(key,draft)).put("draftVersions",JSONObject().put(key,"owned-main-ui-version"))
            .put("draftFiles",JSONObject()).put("selectedThreads",JSONObject()).put("expanded",JSONObject()).put("workspaces",JSONObject())
    }
}

/** Native verifies the immutable finite Host intent against actual installed APKs and process.
 * Hashes are bindings, not a substitute for the Host's SDK/source verification or provider audit. */
internal object NativeMigrationMainSafAdmission {
    private fun sha(bytes: ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {"%02x".format(it)}
    fun facts(): JSONObject {
        val pid=Process.myPid()
        val before=File("/proc/$pid/stat").readText();val ticks=before.substringAfterLast(") ").split(" ")[19]
        check(ticks.matches(Regex("[0-9]{1,24}")))
        val boot=File("/proc/sys/kernel/random/boot_id").readText().trim();check(UUID.fromString(boot).toString()==boot)
        val argv=File("/proc/$pid/cmdline").readBytes();check(argv.isNotEmpty()&&argv.last()==0.toByte()&&argv.dropLastWhile {it==0.toByte()}.toByteArray().contentEquals("com.easyai.agenthub.android.acceptance".toByteArray()))
        check(File("/proc/$pid/stat").readText().substringAfterLast(") ").split(" ")[19]==ticks)
        return JSONObject().put("pid",pid).put("uid",Process.myUid()).put("startTicks",ticks).put("bootUuid",boot).put("argvSha256",sha(argv))
    }
    fun accept(run: String,phase: String): JSONObject {
        val i=InstrumentationRegistry.getInstrumentation();val app=i.targetContext;val test=i.context
        check(BuildConfig.DEBUG&&app.packageName=="com.easyai.agenthub.android.acceptance"&&test.packageName=="${app.packageName}.test")
        check(app.applicationInfo.uid==Process.myUid()&&test.applicationInfo.uid!=Process.myUid())
        val args=InstrumentationRegistry.getArguments();val raw=checkNotNull(args.getString("migrationMainSafAdmissionJson"))
        check(raw.toByteArray().size<=8192&&sha(raw.toByteArray())==args.getString("migrationMainSafAdmissionSha256"))
        val intent=JSONObject(raw)
        val legacyKeys=setOf("schema","profile","run","phase","operationId","selector","uid","testUid","docsUid","bootUuid",
            "appApkSha256","testApkSha256","certificateSha256","sdkSealSha256","workspaceReviewSha256","sourceGuardSha256","profileSha256","expiresWallMillis","inputBytes","inputSha256","transportContractReviewSha256")
        val frameworkV4=args.getString("migrationMainSafFrameworkV4")=="1"
        val keys=if(frameworkV4)legacyKeys+setOf("controllerOperationId","controllerIntentSha256","controllerReceiptSha256","controllerPayloadAckSha256","sourceProducerProofSha256") else legacyKeys
        check(intent.keys().asSequence().toSet()==keys&&intent.getString("schema")==(if(frameworkV4)"p7-main-saf-admission-v4" else "p7-main-saf-admission-v1"))
        check(intent.getString("profile")==(if(frameworkV4)"p7-main-saf-framework-v4" else "p7-main-saf-v2"))
        if(frameworkV4) {
            check(phase in setOf("EXPORT","IMPORT")) {"No producer or legacy fallback in framework-v4"}
            if(phase=="IMPORT") {
                check(intent.getString("controllerPayloadAckSha256")!="0".repeat(64)&&intent.getString("sourceProducerProofSha256")!="0".repeat(64))
            } else check(intent.getString("controllerPayloadAckSha256")=="0".repeat(64)&&intent.getString("sourceProducerProofSha256")=="0".repeat(64))
            check(UUID.fromString(intent.getString("controllerOperationId")).toString()==intent.getString("controllerOperationId"))
        }
        check(intent.getString("run")==run&&intent.getString("phase")==phase&&UUID.fromString(intent.getString("operationId")).toString()==intent.getString("operationId"))
        val selector=when(phase) {
            "PRODUCER"->"NativeMigrationMainSafProducerTest#produceOnlySyntheticImportCiphertext"
            "IMPORT"->"NativeMigrationMainUiTest#actualMainSafOpenStagesOnlyOwnedEncryptedDocument"
            "EXPORT"->"NativeMigrationMainUiTest#actualMainSafCreateWritesOnlyOwnedEncryptedDocument"
            else->error("Finite P7 phase required")
        }
        check(intent.getString("selector")=="com.agenthub.android.$selector")
        check(intent.getInt("uid")==Process.myUid()&&intent.getInt("testUid")==test.applicationInfo.uid&&intent.getInt("docsUid") !in setOf(Process.myUid(),test.applicationInfo.uid))
        val now=System.currentTimeMillis();check(intent.getLong("expiresWallMillis") in now..now+600_000)
        for(key in keys.filter {it.endsWith("Sha256")&&(it!="inputSha256"||phase=="IMPORT")})check(intent.getString(key).matches(Regex("[a-f0-9]{64}")))
        if(phase=="IMPORT")check(intent.getLong("inputBytes") in 1..2*1024*1024) else check(intent.isNull("inputBytes")&&intent.isNull("inputSha256"))
        fun apk(packageName: String): Pair<String,String> {
            @Suppress("DEPRECATION") val p=app.packageManager.getPackageInfo(packageName,PackageManager.GET_SIGNING_CERTIFICATES)
            val info=checkNotNull(p.applicationInfo);check(info.splitSourceDirs.isNullOrEmpty())
            val signing=checkNotNull(p.signingInfo);val signatures=signing.apkContentsSigners;check(signatures.size==1)
            val file=File(info.sourceDir);val digest=MessageDigest.getInstance("SHA-256")
            file.inputStream().use {input->val block=ByteArray(65536);while(true){val n=input.read(block);if(n<0)break;digest.update(block,0,n)}}
            return digest.digest().joinToString("") {"%02x".format(it)} to sha(signatures.single().toByteArray())
        }
        // This profile is initially API33 only; GET_SIGNING_CERTIFICATES is available there.
        check(android.os.Build.VERSION.SDK_INT==33)
        for((packageName,key) in listOf(app.packageName to "appApkSha256",test.packageName to "testApkSha256")) {
            val (digest,cert)=apk(packageName);check(digest==intent.getString(key)&&cert==intent.getString("certificateSha256"))
        }
        val facts=facts();check(facts.getString("bootUuid")==intent.getString("bootUuid"))
        val result=JSONObject().put("binding",intent).put("admissionSha256",args.getString("migrationMainSafAdmissionSha256")).put("nativeFacts",facts)
        i.sendStatus(0,Bundle().apply {putString("p7MainSafAdmission",result.toString())})
        return result
    }
    fun durable(file: File,value: ByteArray) {
        check(!java.nio.file.Files.exists(file.toPath(),java.nio.file.LinkOption.NOFOLLOW_LINKS)&&file.createNewFile())
        android.system.Os.chmod(file.path,0x180)
        java.io.FileOutputStream(file).use {it.write(value);it.fd.sync()}
        syncDirectory(checkNotNull(file.parentFile))
    }
    fun syncDirectory(folder: File) {
        val fd=android.system.Os.open(folder.path,android.system.OsConstants.O_RDONLY or android.system.OsConstants.O_NOFOLLOW or android.system.OsConstants.O_NONBLOCK,0)
        try {
            val opened=android.system.Os.fstat(fd);val current=android.system.Os.lstat(folder.path)
            check(android.system.OsConstants.S_ISDIR(opened.st_mode)&&android.system.OsConstants.S_ISDIR(current.st_mode)&&
                opened.st_uid==android.os.Process.myUid()&&opened.st_dev==current.st_dev&&opened.st_ino==current.st_ino) {
                "Exact owned directory descriptor required"
            }
            android.system.Os.fsync(fd)
        } finally {android.system.Os.close(fd)}
    }
}

/** Separate one-shot process, no Activity/Session/storage owners, no SAF or test-UID access.
 * Success publishes only size/hash/facts, after both ciphertext and receipt FDs are closed. */
class NativeMigrationMainSafProducerTest {
    @org.junit.Test fun produceOnlySyntheticImportCiphertext() {
        val fixture=MigrationInboxFixture();val admission=NativeMigrationMainSafAdmission.accept(fixture.run,"PRODUCER")
        val base=File(fixture.app.filesDir,"p7-main-saf-producer-v1")
        check(!java.nio.file.Files.isSymbolicLink(base.toPath()))
        if(!base.exists()){check(base.mkdir());android.system.Os.chmod(base.path,0x1c0);NativeMigrationMainSafAdmission.syncDirectory(fixture.app.filesDir)}
        check(base.isDirectory&&base.canonicalFile==base.absoluteFile)
        val st=android.system.Os.lstat(base.path);check(st.st_uid==Process.myUid()&&st.st_mode and 0x1ff==0x1c0)
        val folder=File(base,fixture.run);check(!java.nio.file.Files.exists(folder.toPath(),java.nio.file.LinkOption.NOFOLLOW_LINKS)&&folder.mkdir());android.system.Os.chmod(folder.path,0x1c0);NativeMigrationMainSafAdmission.syncDirectory(base)
        NativeMigrationMainSafAdmission.durable(File(folder,"intent.json"),admission.toString().toByteArray())
        val envelope=JSONObject().put("format","agent-hub/android-migration").put("version",1).put("applicationId",fixture.app.packageName)
            .put("records",JSONObject().put("metadata",NativeMigrationMainSafMetadata.value(fixture.run,"imported owned SAF draft").toString()))
        val plaintext=envelope.toString().toByteArray();val bytes=try {NativeMigrationCrypto.seal(plaintext,fixture.password)}finally {plaintext.fill(0)}
        val file=File(folder,"input.bin")
        try {check(bytes.size in 1..2*1024*1024);NativeMigrationMainSafAdmission.durable(file,bytes)}finally {bytes.fill(0)}
        val (size,sha)=NativeMigrationMainSaf.fingerprint(file)
        val proof=JSONObject().put("schema","p7-main-saf-producer-v1").put("run",fixture.run).put("admission",admission)
            .put("bytes",size).put("sha256",sha).put("ciphertextFdClosed",true).put("directorySynced",true)
            .put("sessionOwnersAcquired",0).put("storageOwnersAcquired",0).put("processExitVerified",false)
        NativeMigrationMainSafAdmission.durable(File(folder,"producer.json"),proof.toString().toByteArray())
        InstrumentationRegistry.getInstrumentation().sendStatus(0,Bundle().apply {putString("p7MainSafProducer",proof.toString())})
    }
}
