package com.agenthub.android

import android.app.Activity
import android.app.Instrumentation
import android.app.UiAutomation
import android.accessibilityservice.AccessibilityServiceInfo
import android.graphics.Rect
import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.JUnitCore
import org.junit.runner.Request

/** Acceptance-only self-target tool, contract4. Public accessibility ACTION_CLICK, never touch injection. One fixed JUnit, at most three roles:
 * one OWN label pick, one optional publicly proved system confirmation, one Pause.
 * No Activity/Session/job/SAF callback, P9 permission use, payload/URI or network access.
 * Parent verifies actual selected machine and same Main PID/start/Activity before/after;
 * values supplied here are bindings, never claimed helper-side /proc/selection observations.
 */
class OwnedF59RealUiTransferWatcher : Instrumentation() {
    private val app="com.easyai.agenthub.android.acceptance"
    private val self="com.easyai.agenthub.android.acceptance.coldexaminer"
    private lateinit var contract:JSONObject
    private lateinit var evidenceDir:File
    private lateinit var automation:UiAutomation
    private var onceCommitted=false
    @Volatile private var stopping=false
    @Volatile private var actualfilePickStarted=false
    @Volatile private var systemConfirmStarted=false
    @Volatile private var pauseStarted=false
    @Volatile private var actualPausedProved=false
    @Volatile private var phase="ADMISSION"
    @Volatile private var observed=JSONObject().put("status","NO_ACCESSIBILITY_ACTION")
    private var deadline=0L
    private var snapshotReads=0
    private var priorAutomationFlags:Int?=null
    private fun keys(v:JSONObject)=v.keys().asSequence().toSet()
    private fun uuid(v:String)=v.also {val u=UUID.fromString(it);check(u.toString()==it&&u.version()==4&&u.variant()==2)}
    private fun hex(v:String)=v.also {check(it.matches(Regex("[a-f0-9]{64}")))}
    private fun identifier(v:Any):String {check(v is String&&v.isNotBlank()&&v.length<=512&&v.none {it.isISOControl()});return v}
    override fun onCreate(arguments:Bundle?) {
        super.onCreate(arguments)
        try {
            val a=checkNotNull(arguments)
            val allowed=setOf("enable","run","operation","nonce","fileBytes","fileSHA256","appPackage",
                "expectedAppPID","expectedAppStartTicks","expectedAppUID","pickerPackage",
                "targetCanonical","targetSHA256","parentPlanSHA256")
            check(a.keySet()==allowed&&allowed.all {a.get(it) is String})
            fun value(k:String)=a.getString(k)!!.also {check(it.toByteArray(Charsets.UTF_8).size in 1..8192)}
            check(android.os.Build.VERSION.SDK_INT==37&&context.packageName==self&&targetContext.packageName==self&&value("enable")=="true"&&value("appPackage")==app)
            val run=uuid(value("run"));val operation=uuid(value("operation"))
            val nonce=value("nonce");check(nonce.matches(Regex("[a-f0-9]{32}")))
            check(value("fileBytes")=="67108864");val fileSHA=hex(value("fileSHA256"))
            val targetRaw=value("targetCanonical");val targetSHA=hex(value("targetSHA256"));check(sha(targetRaw)==targetSHA)
            val target=JSONObject(targetRaw);check(keys(target)==setOf("clientId","scope","certificate"))
            val scope=target.getJSONObject("scope");check(keys(scope)==setOf("agentId","hostId","workspaceId","runtimeId"))
            for(k in listOf("agentId","hostId","workspaceId","runtimeId"))identifier(scope.get(k))
            check(scope.getString("agentId")=="codex"&&identifier(target.get("clientId"))==scope.getString("hostId"))
            check(target.get("certificate") is String);hex(target.getString("certificate"))
            val pid=value("expectedAppPID").toInt();val ticks=value("expectedAppStartTicks").toLong();val uid=value("expectedAppUID").toInt()
            check(pid>0&&ticks>0&&uid>0&&pid!=Process.myPid()&&uid!=Process.myUid())
            val picker=value("pickerPackage");check(picker in setOf("com.android.documentsui","com.google.android.documentsui"))
            contract=JSONObject().put("run",run).put("operation",operation).put("nonce",nonce)
                .put("schema",4).put("helperContract","F59_ACCESSIBILITY_LABEL_PICK_OPTIONAL_CONFIRM_PAUSE_V4")
                .put("maxInputRoles",3).put("maxSnapshotReads",900).put("actionKind","ACCESSIBILITY_ACTION_CLICK").put("touchInjectionUsed",false)
                .put("inputRoleOrder",org.json.JSONArray(listOf("file-pick","optional-system-confirm","pause")))
                .put("fileName","AH_F59_${nonce}.bin").put("fileBytes",67108864L).put("fileSHA256",fileSHA)
                .put("machineTarget",target).put("machineTargetRawSHA256",targetSHA)
                .put("parentPlanSHA256",hex(value("parentPlanSHA256"))).put("appPackage",app)
                .put("parentExpectedAppPID",pid).put("parentExpectedAppStartTicks",ticks).put("parentExpectedAppUID",uid)
                .put("pickerPackage",picker)
            start()
        }catch(t:Throwable){finish(Activity.RESULT_CANCELED,Bundle().apply {
            putString("f59Failure","ADMISSION_FAILED_NO_ACCESSIBILITY_ACTION");putString("f59FailureClass",safeClass(t))
        })}
    }
    private fun state()=JSONObject().put("actualfilePickStarted",actualfilePickStarted)
        .put("systemConfirmStarted",systemConfirmStarted).put("pauseStarted",pauseStarted)
        .put("actualInputRolesStarted",listOf(actualfilePickStarted,systemConfirmStarted,pauseStarted).count {it})
        .put("actualPausedProved",actualPausedProved).put("snapshotReads",snapshotReads)
        .put("phase",phase).put("helperPID",Process.myPid()).put("helperUID",Process.myUid())
    private fun emit(stage:String,details:JSONObject) {
        val v=JSONObject().put("schema",4).put("stage",stage).put("run",contract.getString("run"))
            .put("nonce",contract.getString("nonce")).put("contractSHA256",sha(contract.toString()))
            .put("machineTargetRawSHA256",contract.getString("machineTargetRawSHA256"))
            .put("parentPlanSHA256",contract.getString("parentPlanSHA256")).put("state",state()).put("details",details)
        sendStatus(2,Bundle().apply {putString("f59PickerAccessibilityFlowV4",v.toString())})
    }
    private fun saveNew(role:String,value:JSONObject) {
        check(role in roles)
        val f=File(evidenceDir,contract.getString("nonce")+"."+role+".json")
        val fd=Os.open(f.path,OsConstants.O_WRONLY or OsConstants.O_CREAT or OsConstants.O_EXCL or OsConstants.O_NOFOLLOW,0x180)
        try {
            val bytes=value.toString().toByteArray(Charsets.UTF_8);var at=0
            while(at<bytes.size){val n=Os.write(fd,bytes,at,bytes.size-at);check(n>0);at+=n}
            val st=Os.fstat(fd);check(OsConstants.S_ISREG(st.st_mode)&&st.st_uid==Process.myUid()&&st.st_nlink==1L&&(st.st_mode and 0x1ff)==0x180&&st.st_size==bytes.size.toLong())
            Os.fsync(fd)
        }finally {Os.close(fd)}
        val dir=Os.open(evidenceDir.path,OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW,0)
        try {
            val st=Os.fstat(dir)
            check(OsConstants.S_ISDIR(st.st_mode)&&st.st_uid==Process.myUid()&&(st.st_mode and 0x1ff)==0x1c0)
            Os.fsync(dir)
        }finally {Os.close(dir)}
    }
    private fun durableOnce() {
        evidenceDir=File(context.filesDir,"f59-two-gesture-once-v1")
        if(!evidenceDir.exists())Os.mkdir(evidenceDir.path,0x1c0)
        val s=Os.lstat(evidenceDir.path);check(OsConstants.S_ISDIR(s.st_mode)&&s.st_uid==Process.myUid()&&(s.st_mode and 0x1ff)==0x1c0)
        // SAME directory and old roles: schema4 never bypasses any prior schema2/schema3 nonce.
        // An existing intent also rejects before any new input; O_EXCL remains the race guard.
        for(role in roles) {
            val f=File(evidenceDir,contract.getString("nonce")+"."+role+".json")
            try {Os.lstat(f.path);throw GuardFailure(Code.NONCE_ALREADY_EXISTS)}
            catch(e:android.system.ErrnoException){check(e.errno==OsConstants.ENOENT)}
        }
        saveNew("intent",JSONObject().put("schema",4).put("contract",contract).put("state",state()))
        onceCommitted=true
    }
    override fun onStart() {
        super.onStart();var passed=false;var finalAttempted=false
        try {
            check(active==null);active=this
            val r=JUnitCore().run(Request.method(PickerConfirmPause::class.java,"accessibilityPickLabelConfirmIfProvenThenPauseOnce"))
            val junitPassed=r.runCount==1&&r.failureCount==0&&r.ignoreCount==0
            var closeoutFailure:Throwable?=null
            try {restoreAutomationFlags()}catch(t:Throwable){closeoutFailure=t}
            passed=junitPassed&&actualPausedProved&&closeoutFailure==null
            stopping=true
            val failure=r.failures.firstOrNull()?.exception?:closeoutFailure
            val result=JSONObject().put("junitRunCount",r.runCount).put("junitFailureCount",r.failureCount)
                .put("junitIgnored",r.ignoreCount).put("junitPassed",junitPassed).put("helperPassed",passed).put("observation",observed)
                .put("state",state()).put("actualPausedProved",actualPausedProved).put("transferIdBound",false)
                .put("failurePhase",if(passed)JSONObject.NULL else phase)
                .put("failureCode",if(passed)JSONObject.NULL else if(closeoutFailure!=null)"OBSERVATION_FLAGS_RESTORE_FAILED" else failureCode(failure))
                .put("failureClass",if(passed)JSONObject.NULL else safeClass(failure))
            if(onceCommitted)saveNew("final",result)
            finalAttempted=true;emit("FINAL",result)
        }catch(t:Throwable){
            stopping=true;passed=false
            if(::contract.isInitialized&&!finalAttempted){finalAttempted=true;emit("FINAL",JSONObject()
                .put("status",if(actualfilePickStarted||systemConfirmStarted||pauseStarted)"UNKNOWN_NO_REPLAY" else "FAILED_NO_ACCESSIBILITY_ACTION")
                .put("state",state()).put("failurePhase",phase).put("failureCode",failureCode(t)).put("failureClass",safeClass(t)))}
        }finally {
            stopping=true;active=null
            try {restoreAutomationFlags()}catch(_:Throwable){passed=false}
            // Public Instrumentation.finish disconnects this UiAutomation; framework target is only coldexaminer.
            finish(if(passed)Activity.RESULT_OK else Activity.RESULT_CANCELED,Bundle().apply {
                putString("f59Scope","UP_TO_THREE_ACCESSIBILITY_ACTIONS_UI_PAUSED_ONLY_PARENT_MANIFEST_REQUIRED")
            })
        }
    }
    class PickerConfirmPause {
        @Test(timeout=50_000) fun accessibilityPickLabelConfirmIfProvenThenPauseOnce() {
            val i=checkNotNull(active);i.deadline=SystemClock.uptimeMillis()+45_000
            i.phase="INTENT";i.durableOnce()
            i.automation=i.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
            val info=checkNotNull(i.automation.serviceInfo);i.priorAutomationFlags=info.flags
            info.flags=info.flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            i.automation.serviceInfo=info // connection-local observation flag, no system setting write
            i.phase="PICKER_ADMISSION"
            val initial=i.pickerTarget(null)
            // Unique exact visible/enabled OWN filename row established before READY and before input.
            i.emit("READY",initial.json().put("uniqueOwnFileVisible",true).put("uiAutomationConnected",true))
            i.phase="FILE_PICK"
            val selected=i.accessibilityClickOnce("file-pick",initial.windowId){dispatch->i.pickerTarget(initial.windowId,dispatch)}
            check(selected.getString("actionKind")=="ACCESSIBILITY_ACTION_CLICK"&&selected.getBoolean("accessibilityActionAccepted"))
            i.phase="AWAIT_MAIN"
            val mainWindow=i.awaitMainOrConfirm(initial.windowId)
            i.phase="WAIT_PARTIAL"
            i.observed=i.watchPauseAndProve(mainWindow)
            guard(i.actualPausedProved,Code.PAUSED_NOT_PROVEN)
        }
    }
    private fun live(){guard(!stopping&&!Thread.currentThread().isInterrupted&&SystemClock.uptimeMillis()<deadline,Code.EXPIRED_OR_STOPPING)}
    private fun freshRoot():AccessibilityNodeInfo? {
        live();snapshotReads++;guard(snapshotReads<=900,Code.SNAPSHOT_BUDGET)
        return automation.rootInActiveWindow
    }
    private fun restoreAutomationFlags() {
        val flags=priorAutomationFlags?:return
        priorAutomationFlags=null // one attempt; finish releases the connection even on setter failure
        val info=checkNotNull(automation.serviceInfo);info.flags=flags;automation.serviceInfo=info
    }
    private data class Tap(val windowId:Int,val owner:String,val labelPath:String,val clickablePath:String,
                           val matchedLabels:List<String>,val capturedPhase:String,
                           val bounds:Rect,val ancestor:Rect,val pointX:Float,val pointY:Float,
                           val captured:Long,val signature:String,val cardPath:String,val cardBounds:Rect,
                           val percent:Int?=null,val selectedCount:Int?=null,val selectionEvidence:String?=null) {
        fun sameDispatch(other:Tap)=windowId==other.windowId&&owner==other.owner&&
            labelPath==other.labelPath&&clickablePath==other.clickablePath&&matchedLabels==other.matchedLabels&&
            capturedPhase==other.capturedPhase&&bounds==other.bounds&&ancestor==other.ancestor&&
            pointX==other.pointX&&pointY==other.pointY&&signature==other.signature&&cardPath==other.cardPath&&
            cardBounds==other.cardBounds&&selectedCount==other.selectedCount&&selectionEvidence==other.selectionEvidence
        fun json()=JSONObject().put("windowId",windowId).put("ownerPackage",owner)
            .put("labelNodePath",labelPath).put("clickableAncestorPath",clickablePath)
            .put("matchedPublicLabels",org.json.JSONArray(matchedLabels)).put("capturedPhase",capturedPhase)
            .put("labelBounds",rectJSON(bounds)).put("clickableAncestorBounds",rectJSON(ancestor))
            .put("windowCheckPointX",pointX).put("windowCheckPointY",pointY)
            .put("capturedUptimeMs",captured).put("windowAndNodeSignature",signature)
            .put("cardPath",cardPath).put("cardBounds",rectJSON(cardBounds))
            .put("percent",percent?:JSONObject.NULL).put("globalSelectedCount",selectedCount?:JSONObject.NULL)
            .put("selectionEvidence",selectionEvidence?:JSONObject.NULL)
            .put("coverageScope","independent public higher-window/active/focused guard; no passive same-window AX rectangle occlusion inference")
            .put("inputCoordinatesUsed",false)
    }
    /** Bounded structure only. Never enumerates text/description of unrelated nodes.
     * Text access is restricted to framework queries for own nonce or fixed progress/control strings.
     */
    private class Window(val root:AccessibilityNodeInfo,val owner:String,val expectedWindow:Int?,
                         val coverage:(Int,Float,Float)->String,val capturedPhase:String,val ownLabel:String):AutoCloseable {
        val nodes=ArrayList<AccessibilityNodeInfo>();val parents=ArrayList<Int>();val slots=ArrayList<Int>()
        val captured=SystemClock.uptimeMillis();val id=root.windowId
        init {try {visit(root,-1,0);guard(root.packageName?.toString()==owner&&id>=0&&(expectedWindow==null||id==expectedWindow),Code.OWNER_OR_WINDOW_CHANGED)}catch(t:Throwable){close();throw t}}
        private fun visit(n:AccessibilityNodeInfo,p:Int,slot:Int) {
            if(nodes.size>=128){n.recycle();throw GuardFailure(Code.NODE_BUDGET)}
            val at=nodes.size;nodes.add(n);parents.add(p);slots.add(slot)
            for(k in 0 until n.childCount)n.getChild(k)?.let {visit(it,at,k)}
        }
        fun shown(i:Int)=nodes[i].windowId==id&&nodes[i].packageName?.toString()==owner&&nodes[i].isVisibleToUser
        fun visible(i:Int)=shown(i)&&nodes[i].isEnabled
        private fun labels(n:AccessibilityNodeInfo)=listOfNotNull(n.text?.toString(),n.contentDescription?.toString()).toSet()
        private fun query(seed:String,enabled:Boolean=true,accept:(String)->Boolean):Set<Int> {
            val found=root.findAccessibilityNodeInfosByText(seed)?:emptyList();val out=linkedSetOf<Int>()
            try {
                guard(found.size<=128,Code.QUERY_BUDGET)
                for(n in found){val index=nodes.indexOfFirst {it==n};if(index>=0&&shown(index)&&(!enabled||nodes[index].isEnabled)&&labels(n).any(accept))out.add(index)}
            }finally {found.forEach {it.recycle()}}
            return out
        }
        fun exact(s:String,enabled:Boolean=true)=query(s,enabled){it==s}
        fun progress():Map<Int,Int> {
            val out=linkedMapOf<Int,Int>()
            for(i in query("% · ",false){it.matches(Regex("[0-9]{1,3}% · .+ / .+"))}) {
                val values=labels(nodes[i]).filter {it.matches(Regex("[0-9]{1,3}% · .+ / .+"))}.toSet()
                check(values.size==1);out[i]=values.single().substringBefore('%').toInt()
            }
            return out
        }
        fun inside(i:Int,ancestor:Int):Boolean {var at=i;while(at>=0){if(at==ancestor)return true;at=parents[at]};return false}
        fun common(i:Int,j:Int):Int {var at=i;while(at>=0&&!inside(j,at))at=parents[at];check(at>=0);return at}
        fun card(i:Int,j:Int):Int? {
            val c=common(i,j)
            if(c==0||nodes[c].collectionInfo!=null||nodes[c].className?.toString() in
                setOf("android.widget.GridView","android.widget.ListView","androidx.recyclerview.widget.RecyclerView"))return null
            return c
        }
        fun path(i:Int):String {val out=ArrayList<Int>();var at=i;while(at>=0){out.add(slots[at]);at=parents[at]};return out.asReversed().joinToString("/")}
        fun nodeBox(i:Int)=Rect().also {nodes[i].getBoundsInScreen(it)}
        private fun toolbarAncestor(i:Int):Boolean {
            var at=parents[i]
            while(at>=0) {
                if(nodes[at].className?.toString() in setOf("android.widget.Toolbar","androidx.appcompat.widget.Toolbar"))return true
                at=parents[at]
            }
            return false
        }
        fun clickable(leaf:Int):Int {var at=leaf;while(at>=0&&!nodes[at].isClickable)at=parents[at];guard(at>=0&&visible(at),Code.NO_CLICKABLE_ANCESTOR);return at}
        private fun collection(leaf:Int):Int {
            var at=leaf
            while(at>=0) {
                if(nodes[at].collectionInfo!=null||nodes[at].className?.toString() in setOf("android.widget.GridView","android.widget.ListView","androidx.recyclerview.widget.RecyclerView"))return at
                at=parents[at]
            }
            throw GuardFailure(Code.SELECTION_STRUCTURE_UNPROVEN)
        }
        /** Public selection only. No view reflection, resource-id guesses or private activated state. */
        fun selectedConfirmation(own:Int):Tap? {
            val row=clickable(own);val list=collection(own)
            var at=own;val evidence=linkedSetOf<String>()
            while(at>=0&&inside(at,row)) {
                if(shown(at)&&nodes[at].isSelected)evidence.add("PUBLIC_SELECTED")
                if(shown(at)&&nodes[at].collectionItemInfo?.isSelected==true)evidence.add("COLLECTION_ITEM_SELECTED")
                at=parents[at]
            }
            val checked=nodes.indices.filter {shown(it)&&inside(it,row)&&nodes[it].isCheckable&&nodes[it].isChecked}
            guard(checked.size<=1,Code.SELECTION_AMBIGUOUS)
            if(checked.isNotEmpty())evidence.add("OWN_ROW_CHECKED_CONTROL")
            val counts=linkedMapOf<Int,Int>()
            for(seed in listOf("selected","Selected","已选择","已选中")) {
                for(i in query(seed,false){selectionCount(it)!=null}) {
                    if(inside(i,list))continue // filenames/row counters are not global selection evidence
                    val values=labels(nodes[i]).mapNotNull {selectionCount(it)}.toSet()
                    guard(values.size==1,Code.SELECTION_COUNT_AMBIGUOUS);counts[i]=values.single()
                }
            }
            val controls=confirmLabels.flatMap {exact(it)}.toSet().filter {c->
                !inside(c,list)&&!inside(c,row)&&visible(c)&&
                    (nodes[c].className?.toString() in setOf("android.widget.Button","android.widget.ImageButton")||toolbarAncestor(c))
            }
            // Incomplete evidence may be a still-pending direct callback: wait only within the shared5s transition.
            guard(counts.isEmpty()||(counts.size==1&&counts.values.single()==1),Code.SELECTION_COUNT_NOT_ONE)
            guard(controls.size<=1,Code.CONFIRM_NOT_UNIQUE)
            if(evidence.isEmpty()||counts.isEmpty()||controls.isEmpty())return null
            guard(counts.size==1&&counts.values.single()==1,Code.SELECTION_COUNT_NOT_ONE)
            guard(controls.size==1,Code.CONFIRM_NOT_UNIQUE)
            val proof=evidence.sorted().joinToString("|")+"|OWN_ROW="+path(row)+"|GLOBAL_COUNT_NODE="+path(counts.keys.single())+"|GLOBAL_COUNT=1"
            return target(controls.single(),selectedCount=1,selectionEvidence=proof)
        }
        fun target(leaf:Int,within:Int=0,percent:Int?=null,selectedCount:Int?=null,selectionEvidence:String?=null):Tap {
            guard(visible(leaf),Code.TARGET_DISABLED);var b=leaf
            while(b>=0&&!nodes[b].isClickable)b=parents[b]
            guard(b>=0&&inside(b,within)&&visible(b)&&nodes[b].refresh()&&nodes[leaf].refresh()&&visible(b)&&visible(leaf),Code.NO_CLICKABLE_ANCESTOR)
            val box=Rect();nodes[b].getBoundsInScreen(box);val textBox=Rect();nodes[leaf].getBoundsInScreen(textBox)
            val frame=Rect();root.getBoundsInScreen(frame)
            guard(!box.isEmpty&&!textBox.isEmpty&&box.contains(textBox)&&frame.contains(box),Code.NO_SAFE_LABEL_POINT)
            val x=textBox.exactCenterX();val y=textBox.exactCenterY()
            guard(x>textBox.left&&x<textBox.right&&y>textBox.top&&y<textBox.bottom,Code.NO_SAFE_LABEL_POINT)
            // ACTION_CLICK targets a semantic node. Passive AX rectangles within this
            // same window do not establish a pixel occluder. Keep independent window layers.
            val coverageSignature=coverage(id,x,y)
            guard(SystemClock.uptimeMillis()-captured<=150,Code.STALE_SNAPSHOT)
            val matched=labels(nodes[leaf]).filter {it==ownLabel||it in confirmLabels||
                it in setOf("暂停","Pause","继续","Continue")}.sorted()
            guard(matched.isNotEmpty(),Code.ACTION_LABEL_UNPROVEN)
            val signature=sha(owner+"|"+capturedPhase+"|"+path(leaf)+"|"+path(b)+"|"+matched.joinToString("|")+"|"+
                textBox+"|"+box+"|"+path(within)+"|"+nodeBox(within)+"|"+coverageSignature+"|"+(selectionEvidence?:""))
            return Tap(id,owner,path(leaf),path(b),matched,capturedPhase,textBox,box,x,y,captured,
                signature,path(within),nodeBox(within),percent,selectedCount,selectionEvidence)
        }
        /** The caller can dispatch only its freshly resolved closed role, before Window.close. */
        fun performAccessibilityClick(t:Tap):Boolean {
            guard(t.windowId==id&&t.owner==owner&&t.capturedPhase==capturedPhase,Code.TARGET_CHANGED)
            val leaf=nodes.indices.singleOrNull {path(it)==t.labelPath}?:throw GuardFailure(Code.TARGET_CHANGED)
            val within=nodes.indices.singleOrNull {path(it)==t.cardPath}?:throw GuardFailure(Code.TARGET_CHANGED)
            val fresh=target(leaf,within,t.percent,t.selectedCount,t.selectionEvidence)
            guard(fresh.sameDispatch(t),Code.TARGET_CHANGED)
            val ancestor=clickable(leaf)
            guard(path(ancestor)==t.clickablePath&&inside(ancestor,within)&&visible(ancestor)&&
                nodes[ancestor].actionList.any {it.id==AccessibilityNodeInfo.ACTION_CLICK},Code.NO_ACCESSIBILITY_CLICK_ACTION)
            guard(SystemClock.uptimeMillis()-captured<=150,Code.STALE_SNAPSHOT)
            return nodes[ancestor].performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }
        override fun close(){nodes.asReversed().forEach {it.recycle()};nodes.clear();parents.clear();slots.clear()}
    }
    private fun coverage(windowId:Int,x:Float,y:Float):String {
        live();val windows=automation.windows?:emptyList()
        try {
            guard(windows.size in 1..16&&windows.map {it.id}.toSet().size==windows.size,Code.WINDOW_STACK_UNOBSERVED)
            val own=windows.singleOrNull {it.id==windowId}?:throw GuardFailure(Code.WINDOW_STACK_UNOBSERVED)
            val ownBox=Rect();own.getBoundsInScreen(ownBox)
            guard(own.type==AccessibilityWindowInfo.TYPE_APPLICATION&&own.isActive&&own.isFocused&&ownBox.contains(x.toInt(),y.toInt()),Code.OWNER_OR_WINDOW_CHANGED)
            for(w in windows)if(w.id!=windowId&&w.layer>own.layer) {
                val b=Rect();w.getBoundsInScreen(b)
                guard(!b.isEmpty&&!b.contains(x.toInt(),y.toInt()),Code.OCCLUSION_UNPROVEN)
            }
            return sha(windows.sortedBy {it.id}.joinToString("|"){w->val b=Rect();w.getBoundsInScreen(b);"${w.id}:${w.type}:${w.layer}:$b:${w.isActive}:${w.isFocused}"})
        }finally {windows.forEach {it.recycle()}}
    }
    private fun pickerTarget(expectedWindow:Int?,dispatch:((Window,Tap)->Unit)?=null):Tap {
        live();val root=freshRoot()?:throw GuardFailure(Code.NO_ROOT)
        Window(root,contract.getString("pickerPackage"),expectedWindow,::coverage,phase,contract.getString("fileName")).use {w->
            val own=w.exact(contract.getString("fileName"));guard(own.size==1,Code.OWN_LABEL_NOT_UNIQUE)
            return w.target(own.single()).also {dispatch?.invoke(w,it)}
        }
    }
    private fun confirmationTarget(pickerWindow:Int,dispatch:((Window,Tap)->Unit)?=null):Tap? {
        val root=freshRoot()?:throw GuardFailure(Code.NO_ROOT)
        Window(root,contract.getString("pickerPackage"),pickerWindow,::coverage,phase,contract.getString("fileName")).use {w->
            val own=w.exact(contract.getString("fileName"));guard(own.size==1,Code.OWN_LABEL_NOT_UNIQUE)
            return w.selectedConfirmation(own.single())?.also {dispatch?.invoke(w,it)}
        }
    }
    private fun partialTarget(expectedWindow:Int,dispatch:((Window,Tap)->Unit)?=null):Tap? {
        live();val root=freshRoot()?:throw GuardFailure(Code.NO_ROOT)
        Window(root,app,expectedWindow,::coverage,phase,contract.getString("fileName")).use {w->
            val own=w.exact(contract.getString("fileName"));guard(own.size<=1,Code.OWN_LABEL_NOT_UNIQUE)
            if(own.isEmpty())return null
            val controls=w.exact("暂停")+w.exact("Pause");val progress=w.progress();val hits=ArrayList<Tap>()
            for(c in controls) {
                val card=w.card(c,own.single())?:continue
                val pc=progress.filterKeys {w.inside(it,card)}
                if(controls.count {w.inside(it,card)}!=1||pc.size!=1)continue
                val percent=pc.values.single();if(percent !in 1..99)continue
                hits.add(w.target(c,card,percent))
            }
            guard(hits.size<=1,Code.PARTIAL_AMBIGUOUS);return hits.singleOrNull()?.also {dispatch?.invoke(w,it)}
        }
    }
    private fun accessibilityClickOnce(role:String,window:Int,resolve:(((Window,Tap)->Unit)?)->Tap):JSONObject {
        guard(role in setOf("file-pick","system-confirm","pause"),Code.INVALID_ROLE);live()
        guard(when(role) {
            "file-pick"->!actualfilePickStarted&&!systemConfirmStarted&&!pauseStarted
            "system-confirm"->actualfilePickStarted&&!systemConfirmStarted&&!pauseStarted
            else->actualfilePickStarted&&!pauseStarted
        },Code.ROLE_ALREADY_ATTEMPTED)
        val prior=resolve(null);guard(prior.windowId==window&&prior.capturedPhase==phase,Code.TARGET_CHANGED)
        // Same immutable nonce/role files and O_EXCL: no action before this durable intent.
        saveNew(role+"-started",JSONObject().put("role",role).put("status","ENTERING_ACCESSIBILITY_ACTION")
            .put("actionKind","ACCESSIBILITY_ACTION_CLICK").put("target",prior.json())
            .put("stateBeforeDispatch",state()).put("missingResultMeansUnknown",true))
        var result:JSONObject?=null
        val resolved=resolve {w,fresh->
            guard(result==null&&fresh.windowId==window&&fresh.sameDispatch(prior)&&fresh.capturedPhase==phase,Code.TARGET_CHANGED)
            live();guard(SystemClock.uptimeMillis()-fresh.captured<=150,Code.STALE_SNAPSHOT)
            when(role){"file-pick"->actualfilePickStarted=true;"system-confirm"->systemConfirmStarted=true;else->pauseStarted=true}
            observed=JSONObject().put("status","UNKNOWN_NO_REPLAY").put("state",state())
            val at=SystemClock.uptimeMillis()
            val accepted=w.performAccessibilityClick(fresh)
            val value=JSONObject().put("role",role).put("actionKind","ACCESSIBILITY_ACTION_CLICK")
                .put("accessibilityActionAccepted",accepted).put("target",fresh.json())
                .put("snapshotToActionMs",at-fresh.captured).put("state",state()).put("touchInjectionUsed",false)
            saveNew(role+"-result",value);result=value
            guard(accepted,Code.ACCESSIBILITY_ACTION_NOT_ACCEPTED)
        }
        guard(resolved.sameDispatch(prior),Code.TARGET_CHANGED)
        return result?:throw GuardFailure(Code.ACCESSIBILITY_ACTION_RESULT_UNKNOWN)
    }
    private fun awaitMainOrConfirm(pickerWindow:Int):Int {
        val until=minOf(deadline,SystemClock.uptimeMillis()+5_000);var polls=0
        while(SystemClock.uptimeMillis()<until&&polls++<100) {
            live();val root=freshRoot();var picker=false
            if(root!=null)try {
                val owner=root.packageName?.toString()
                if(owner==app){guard(root.windowId>=0,Code.OWNER_OR_WINDOW_CHANGED);return root.windowId}
                guard(owner==contract.getString("pickerPackage")&&root.windowId==pickerWindow,Code.OWNER_OR_WINDOW_CHANGED)
                picker=true
            }finally {root.recycle()}
            if(picker&&!systemConfirmStarted) {
                val proof=confirmationTarget(pickerWindow)
                if(proof!=null) {
                    phase="SYSTEM_CONFIRM"
                    accessibilityClickOnce("system-confirm",pickerWindow){dispatch->confirmationTarget(pickerWindow,dispatch)?:throw GuardFailure(Code.SELECTION_NOT_PROVEN)}
                    phase="AWAIT_MAIN_AFTER_CONFIRM"
                    // Same until/deadline: no second5s, no second confirm or file tap.
                }
            }
            SystemClock.sleep(50)
        }
        throw GuardFailure(if(systemConfirmStarted)Code.MAIN_NOT_OBSERVED else Code.SELECTION_NOT_PROVEN)
    }
    private fun watchPauseAndProve(mainWindow:Int):JSONObject {
        var polls=0
        while(SystemClock.uptimeMillis()<deadline&&polls++<900) {
            live();val target=partialTarget(mainWindow)
            if(target!=null) {
                phase="PAUSE"
                val result=accessibilityClickOnce("pause",mainWindow){dispatch->partialTarget(mainWindow,dispatch)?:throw GuardFailure(Code.PARTIAL_CHANGED)}
                val actual=result.getJSONObject("target")
                val b=actual.getJSONArray("cardBounds")
                val actualCardBounds=Rect(b.getInt(0),b.getInt(1),b.getInt(2),b.getInt(3))
                phase="VERIFY_PAUSED"
                while(SystemClock.uptimeMillis()<deadline) {
                    live();val proof=pausedProof(mainWindow,actual.getString("cardPath"),actualCardBounds)
                    if(proof!=null) {
                        live()
                        actualPausedProved=true
                        return JSONObject().put("status","ACTUAL_PAUSED_UI_REQUIRES_PARENT_ORIGINAL_MANIFEST")
                            .put("pauseAccessibilityAction",result).put("pausedUIProof",proof).put("polls",polls)
                            .put("actualPausedProved",actualPausedProved).put("transferIdBound",false)
                    }
                    SystemClock.sleep(50)
                }
                throw GuardFailure(Code.PAUSED_NOT_PROVEN)
            }
            SystemClock.sleep(50)
        }
        throw GuardFailure(Code.PARTIAL_NOT_OBSERVED)
    }
    private fun pausedProof(mainWindow:Int,partialCardPath:String,partialCardBounds:Rect):JSONObject? {
        val root=freshRoot()?:return null
        Window(root,app,mainWindow,::coverage,phase,contract.getString("fileName")).use {w->
            val own=w.exact(contract.getString("fileName"));guard(own.size<=1,Code.OWN_LABEL_NOT_UNIQUE)
            if(own.isEmpty())return null
            val continues=w.exact("继续")+w.exact("Continue")
            val paused=w.exact("已暂停",false)+w.exact("Paused",false)
            val pause=w.exact("暂停",false)+w.exact("Pause",false)
            val matches=ArrayList<JSONObject>()
            for(c in continues) {
                val card=w.card(c,own.single())?:continue;if(w.path(card)!=partialCardPath)continue
                if(continues.count {w.inside(it,card)}!=1||paused.count {w.inside(it,card)}!=1||pause.any {w.inside(it,card)})continue
                val target=w.target(c,card) // enabled Continue, same-window coverage, no input
                matches.add(JSONObject().put("windowId",mainWindow).put("fileName",contract.getString("fileName"))
                    .put("sameCardPath",partialCardPath).put("beforeCardBounds",rectJSON(partialCardBounds))
                    .put("afterCardBounds",rectJSON(w.nodeBox(card))).put("pausedTextCount",1)
                    .put("continueEnabledCount",1).put("visiblePauseCountIncludingDisabled",0)
                    .put("continueTargetObservation",target.json()))
            }
            guard(matches.size<=1,Code.PAUSED_AMBIGUOUS);return matches.singleOrNull()
        }
    }
    private enum class Code {
        EXPIRED_OR_STOPPING,SNAPSHOT_BUDGET,NODE_BUDGET,QUERY_BUDGET,OWNER_OR_WINDOW_CHANGED,
        TARGET_DISABLED,NO_CLICKABLE_ANCESTOR,NO_SAFE_LABEL_POINT,OCCLUSION_UNPROVEN,
        WINDOW_STACK_UNOBSERVED,STALE_SNAPSHOT,TARGET_CHANGED,OWN_LABEL_NOT_UNIQUE,
        SELECTION_STRUCTURE_UNPROVEN,SELECTION_AMBIGUOUS,SELECTION_COUNT_AMBIGUOUS,
        SELECTION_COUNT_NOT_ONE,CONFIRM_NOT_UNIQUE,SELECTION_NOT_PROVEN,MAIN_NOT_OBSERVED,
        PARTIAL_AMBIGUOUS,PARTIAL_NOT_OBSERVED,PARTIAL_CHANGED,PAUSED_AMBIGUOUS,PAUSED_NOT_PROVEN,
        INVALID_ROLE,ROLE_ALREADY_ATTEMPTED,NO_ACCESSIBILITY_CLICK_ACTION,ACTION_LABEL_UNPROVEN,
        ACCESSIBILITY_ACTION_NOT_ACCEPTED,ACCESSIBILITY_ACTION_RESULT_UNKNOWN,NO_ROOT,NONCE_ALREADY_EXISTS
    }
    private class GuardFailure(val code:Code):RuntimeException()
    companion object {
        @Volatile private var active:OwnedF59RealUiTransferWatcher?=null
        private val roles=setOf("intent","file-pick-started","file-pick-result","system-confirm-started",
            "system-confirm-result","pause-started","pause-result","final")
        private val confirmLabels=setOf("Open","OPEN","Select","SELECT","打开","选择","使用此文件","Use this file")
        private fun sha(v:String)=MessageDigest.getInstance("SHA-256").digest(v.toByteArray(Charsets.UTF_8)).joinToString(""){"%02x".format(it)}
        private fun guard(value:Boolean,code:Code){if(!value)throw GuardFailure(code)}
        private fun rectJSON(r:Rect)=org.json.JSONArray(listOf(r.left,r.top,r.right,r.bottom))
        private fun selectionCount(value:String):Int? {
            val patterns=listOf(Regex("([0-9]{1,2}) (?:items? |files? )?selected",RegexOption.IGNORE_CASE),
                Regex("Selected: ([0-9]{1,2})",RegexOption.IGNORE_CASE),Regex("已(?:选择|选中) ?([0-9]{1,2}) ?(?:项|个文件)"))
            return patterns.firstNotNullOfOrNull {it.matchEntire(value)?.groupValues?.get(1)?.toIntOrNull()}
        }
        private fun failureCode(t:Throwable?):String=when(t) {
            is GuardFailure->t.code.name
            is org.junit.runners.model.TestTimedOutException->"JUNIT_TIMEOUT"
            is android.system.ErrnoException->"FILESYSTEM_FAILURE_NO_REPLAY"
            is SecurityException->"SECURITY_DENIED_NO_REPLAY"
            is InterruptedException->"INTERRUPTED_NO_REPLAY"
            else->"UNCLASSIFIED_GUARD_NO_REPLAY"
        }
        private fun safeClass(t:Throwable?):String=when(t) {
            is GuardFailure->"GuardFailure";is org.junit.runners.model.TestTimedOutException->"TestTimedOutException"
            is android.system.ErrnoException->"ErrnoException";is SecurityException->"SecurityException"
            is InterruptedException->"InterruptedException";is IllegalStateException->"IllegalStateException"
            is AssertionError->"AssertionError";else->"OtherOrMissingFailure"
        }
    }
}
