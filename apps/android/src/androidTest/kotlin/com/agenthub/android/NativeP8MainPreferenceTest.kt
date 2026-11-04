package com.agenthub.android

import android.content.Intent
import androidx.compose.ui.test.*
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.agenthub.core.TargetScope
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.security.KeyStore
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.CopyOnWriteArrayList
import java.security.MessageDigest

/** SOURCE ONLY: no build/run claim; requires the two reviewed seams and debug manifest entry.
 * Actual production Main lifecycle SUBCLASS, real AgentHubApp and real session save/restore.
 * Native observers/notification/media/voice assembly are intentionally excluded. */
class NativeP8MainPreferenceTest {
    @get:Rule val ui=createEmptyComposeRule()
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private val actual get()=instrumentation.targetContext
    private lateinit var admission: NativeP8MainAdmission.Accepted
    private var currentEntry: Entry?=null
    private var originalAutomation: android.app.UiAutomation?=null
    private var automationPreparation: JSONObject?=null
    private enum class Outcome { NOT_STARTED,UNKNOWN,CONFIRMED }
    private class Failures {
        var first: Throwable?=null;private set
        fun add(error: Throwable) {val original=first;if(original==null)first=error else if(original!==error&&original.suppressed.none {it===error})original.addSuppressed(error)}
        fun throwIfAny(){first?.let {throw it}}
        fun capture(action: ()->Unit){try{action()}catch(error: Throwable){add(error)}}
    }
    /** One record per exact operation: UNKNOWN never re-enters its action. */
    private class Ledger {
        private val outcomes=linkedMapOf<String,Outcome>()
        fun confirmed(vararg names: String)=names.all {outcomes[it]==Outcome.CONFIRMED}
        fun step(name: String,errors: Failures,ready: Boolean=true,action: ()->Unit): Boolean {
            val current=outcomes.getOrPut(name){Outcome.NOT_STARTED}
            if(current!=Outcome.NOT_STARTED)return current==Outcome.CONFIRMED
            if(!ready)return false
            outcomes[name]=Outcome.UNKNOWN
            try {action();outcomes[name]=Outcome.CONFIRMED;return true}catch(error: Throwable){errors.add(error);return false}
        }
        fun summary()=outcomes.mapValues {it.value.name}
    }
    private class CaseOwners(val run: String) {
        val pid=android.os.Process.myPid();val nonce=NativeChatNotificationColdStart.processNonce
        val ledger=Ledger();val entries=mutableListOf<Entry>();val activities=mutableListOf<ActivityOwner>()
        var context: P8MainFilesContext?=null;var keys: KeyStore?=null;var storage: NativeSessionStorage?=null
        var fence: P8MainScopeBoundary?=null
        var expectedClient: MobileClient?=null
        val nativeAxReadiness=JSONArray() // Fixed-tag counts only, including a failed final sample.
        val nativeWindows=mutableListOf<OwnedWindow>() // Original View/Activity references survive unknown outcomes.
        var windowOwnerFailure: JSONObject?=null // First failure-only local metadata, never an ownership substitute.
        var windowBindingFailure: JSONObject?=null // Cached existing binding reads only; never an ownership substitute.
    }
    private class ActivityOwner(val owner: CaseOwners) {
        val ledger=Ledger();var scenario: ActivityScenario<P8MainPreferenceActivity>?=null
        var ticket: P8MainActivityOwners.Launch?=null
        val closed get()=ledger.confirmed("instance-prepared","launch-returned","closed","instances-closed")
    }
    private class Entry(val owner: CaseOwners,val session: MobileSession,val storage: NativeSessionStorage,val context: P8MainFilesContext,
        val migration: NativeMigrationController?,val ports: P8MainDenyPorts,val activity: ActivityOwner?=null,
        val instance: P8MainActivityOwners.Instance?=null) {
        val ledger=Ledger();var sessionJob: Job?=null;var migrationJob: Job?=null
        var io: NativeMigrationIoWork?=null;var writer: ExecutorService?=null
        val released get()=ledger.confirmed("leave","session-joined","io-closed","io-idle","migration-joined","writer-drained","writer-retired","writer-terminated","port-evidence")
    }
    companion object {
        // Test-only strong references, including exact unknown FDs/scenarios/Jobs/executors.
        // No retry/clear API: unknown ownership survives this test instance until its original PID exits.
        private val retainedUntilProcessExit=CopyOnWriteArrayList<CaseOwners>()
    }
    private fun main(block: ()->Unit)=instrumentation.runOnMainSync(block)
    private fun awaitStore(store: NativeSessionStorage)=runBlocking {withTimeout(10000){store.awaitQueuedWrites()}}
    @Suppress("UNCHECKED_CAST") private fun addOwnedClient(session: MobileSession,client: MobileClient) {
        (MobileSession::class.java.getDeclaredField("realClients").apply{isAccessible=true}.get(session) as MutableList<MobileClient>).add(client)
    }
    private fun preference(store: NativeSessionStorage,id: String): String {
        awaitStore(store)
        val clients=checkNotNull(store.metadata()).getJSONArray("clients")
        return (0 until clients.length()).map {clients.getJSONObject(it)}.single {it.getString("id")==id}.getJSONObject("agentConnection").getString("preference")
    }
    private fun closeActivity(activity: ActivityOwner,errors: Failures) {
        activity.ledger.step("closed",errors,ready=activity.ledger.confirmed("launch-returned")) {checkNotNull(activity.scenario).close()}
        activity.ledger.step("instances-closed",errors,ready=activity.ledger.confirmed("closed")) {
            main {P8MainActivityOwners.close(checkNotNull(activity.ticket))}
        }
    }
    /** Capture returned owners directly from the creation fence, including partial/failed launch.
     * Unbound/unknown construction remains a fence failure, never an invented Entry or release. */
    private fun captureActivityEntries(owner: CaseOwners) = main {
        owner.activities.forEach {activityOwner->activityOwner.ticket?.instances?.forEach {instance->
            instance.bindings.forEach {binding->
                if(owner.entries.none {it.session===binding.session})owner.entries.add(Entry(owner,binding.session,binding.storage,
                    binding.context,binding.migration,binding.ports,activityOwner,instance))
            }
        }}
    }
    private fun activityProof(owner: CaseOwners): JSONArray {
        var value: JSONArray?=null
        main {value=JSONArray(owner.activities.map {JSONObject(P8MainActivityOwners.summary(it.ticket)?:emptyMap<String,Any>())})}
        return checkNotNull(value)
    }
    private fun assertEntry(entry: Entry) {
        check(entry.owner.pid==android.os.Process.myPid()&&entry.owner.nonce==NativeChatNotificationColdStart.processNonce)
        val activityOwner=checkNotNull(entry.activity)
        checkNotNull(activityOwner.scenario).onActivity {activity->
            val instance=checkNotNull(entry.instance)
            try {
            P8MainActivityOwners.requireCurrent(instance)
            check(instance.launch===activityOwner.ticket&&activity===instance.activity&&activity.ownership===instance)
            val binding=instance.bindings.single()
            check(binding.portsInstalled&&binding.session===entry.session&&binding.storage===entry.storage&&binding.context===entry.context&&
                binding.migration===entry.migration&&binding.ports===entry.ports)
            check(activity.session===entry.session&&activity.ownedStorage===entry.storage&&activity.ownedContext===entry.context&&
                activity.migration===entry.migration&&activity.deny===entry.ports&&entry.session.migrationStorage===entry.storage)
            check(entry.context.run==entry.owner.run&&entry.context.root==checkNotNull(entry.owner.context).root&&
                entry.context.alias==entry.owner.context!!.alias)
            } catch(error: Throwable){P8MainActivityOwners.failed(instance,error);throw error}
        }
    }
    /** Every UI/native measurement/action uses the same exact original binding before and after.
     * A failed post-check is attached to the original failure, not discarded or turned into success. */
    private fun <T> owned(entry: Entry=checkNotNull(currentEntry),action: ()->T): T {
        assertEntry(entry);var first: Throwable?=null
        try {return action()}catch(error: Throwable){first=error;throw error}
        finally {try {assertEntry(entry)}catch(error: Throwable){if(first==null)throw error else if(first!==error)first.addSuppressed(error)}}
    }
    /** Preparation only: same public flags=0 connection, never an owner/root/Compose proof. */
    private fun prepareOriginalAutomation() {
        check(originalAutomation==null&&automationPreparation==null&&NativeChatNotificationColdStart.activityEntries.get()==0)
        val started=android.os.SystemClock.elapsedRealtime()
        val automation=checkNotNull(instrumentation.getUiAutomation(0)) {"Original flags=0 UiAutomation connection unknown"}
        originalAutomation=automation // Retain even if quiet fails; no reconnect or alternate flags.
        automation.waitForIdle(100L,1000L) // Public event quiet only; an exception is not swallowed.
        val finished=android.os.SystemClock.elapsedRealtime()
        check(finished-started in 0L..1499L) {"UiAutomation preparation exceeded cooperative budget"}
        check(NativeChatNotificationColdStart.activityEntries.get()==0)
        automationPreparation=JSONObject().put("schema","p8-original-ui-automation-preparation-v1")
            .put("flags",0).put("quietMs",100).put("globalMs",1000).put("budgetMs",1500)
            .put("startedElapsedMs",started).put("finishedElapsedMs",finished).put("elapsedMs",finished-started)
            .put("beforeAnyActivity",true).put("eventQuietObserved",true).put("rootIdentityProven",false)
            .put("pid",android.os.Process.myPid()).put("processNonce",NativeChatNotificationColdStart.processNonce)
    }
    /** One cooperative deadline per original field input, never reset by preparation or waiting. */
    private class InputBudget {
        val started=android.os.SystemClock.elapsedRealtime()
        val deadline=started+5000L
        fun withinDeadline(): Long {
            val now=android.os.SystemClock.elapsedRealtime()
            check(now<deadline) {"Original field input deadline elapsed; preserve UNKNOWN without replay"}
            return now
        }
    }
    private class ActivityDecorOwner(val activity: P8MainPreferenceActivity,val window: android.view.Window,
        val decor: android.view.View,val token: android.os.IBinder)
    private class OwnedWindow(val entry: Entry,val activity: P8MainPreferenceActivity,val activityDecorOwner: ActivityDecorOwner,val decor: android.view.View,
        val windowId: Int,val id: String,val proof: JSONObject) {
        val inputs=JSONArray()
    }
    /** Failure-only public metadata: no children/content, AX nodes, hidden access or token serialization.
     * Public getters have cooperative bounds, not a hard wall-clock cancellation guarantee. */
    private fun retainWindowOwnerFailure(entry: Entry,roots: List<android.view.View>,refused: android.view.View,primary: Throwable) {
        if(entry.owner.windowOwnerFailure!=null)return
        val started=android.os.SystemClock.elapsedRealtime();val deadline=started+500L
        val record=JSONObject().put("schema","p8-window-owner-failure-metadata-v1").put("failureOnly",true)
            .put("ownershipProven",false).put("contentInspected",false).put("axNodeAcquisitions",0)
            .put("startedElapsedMs",started).put("budgetMs",500).put("rootLimit",4).put("contextLimit",16)
        entry.owner.windowOwnerFailure=record
        fun budget(){check(android.os.SystemClock.elapsedRealtime()<deadline) {"Window owner metadata budget exhausted"}}
        fun <T> read(action: ()->T): T {budget();val value=action();budget();return value}
        fun className(value: Any): String=value.javaClass.name.let {if(it.matches(Regex("[A-Za-z0-9_.$]{1,96}")))it else "CLASS_NAME_UNAVAILABLE"}
        try {
            read {check(android.os.Looper.myLooper()===android.os.Looper.getMainLooper())}
            val instance=read {checkNotNull(entry.instance)};read {P8MainActivityOwners.requireCurrent(instance)}
            read {check(currentEntry===entry&&instance.activity.ownership===instance)}
            val activity=read {instance.activity};val activityWindow=read {activity.window}
            val activityDecor=read {activityWindow.decorView}
            val activityParams=read {activityDecor.layoutParams as? android.view.WindowManager.LayoutParams}
            val activityType=read {activityParams?.type};val activityToken=read {activityParams?.token}
            val activityWindowToken=read {activityDecor.windowToken}
            val activityApplicationToken=read {activityDecor.applicationWindowToken}
            val rows=JSONArray();check(roots.size<=4&&roots.distinct().size==roots.size)
            for(root in roots) {
                val rootClass=read {className(root)}
                val params=read {root.layoutParams as? android.view.WindowManager.LayoutParams}
                val type=read {params?.type};val token=read {params?.token}
                val classes=JSONArray();val seen=mutableListOf<android.content.Context>()
                var context=read {root.context};var exact=false;var cycle=false;var terminated=false
                for(depth in 0 until 16) {
                    classes.put(read {className(context)})
                    if(context===activity){exact=true;terminated=true;break}
                    if(seen.any {it===context}){cycle=true;terminated=true;break};seen+=context
                    val base=read {(context as? android.content.ContextWrapper)?.baseContext}
                    if(base==null){terminated=true;break};context=base
                }
                val attached=read {root.isAttachedToWindow};val self=read {root.rootView===root}
                val visible=read {root.visibility==android.view.View.VISIBLE}
                val windowVisible=read {root.windowVisibility==android.view.View.VISIBLE}
                val shown=read {root.isShown};val focused=read {root.hasWindowFocus()}
                val windowToken=read {root.windowToken};val applicationToken=read {root.applicationWindowToken}
                budget()
                rows.put(JSONObject().put("rootClass",rootClass).put("contextClasses",classes)
                    .put("contextCycle",cycle).put("contextTerminated",terminated).put("exactActivityInContextChain",exact)
                    .put("refusedRoot",root===refused).put("originalActivityDecor",root===activityDecor)
                    .put("attached",attached).put("rootViewIsSelf",self).put("visible",visible)
                    .put("windowVisible",windowVisible).put("shown",shown).put("focused",focused)
                    .put("layoutParamsPresent",params!=null).put("windowType",type ?: JSONObject.NULL)
                    .put("originalActivityType",activityType ?: JSONObject.NULL)
                    .put("tokenPresent",token!=null).put("originalActivityTokenPresent",activityToken!=null)
                    .put("sameNonNullActivityParamsToken",token!=null&&activityToken!=null&&token===activityToken)
                    .put("windowTokenPresent",windowToken!=null).put("sameNonNullActivityWindowToken",windowToken!=null&&windowToken===activityWindowToken)
                    .put("applicationWindowTokenPresent",applicationToken!=null)
                    .put("sameNonNullActivityApplicationWindowToken",applicationToken!=null&&applicationToken===activityApplicationToken))
            }
            read {P8MainActivityOwners.requireCurrent(instance)}
            read {check(currentEntry===entry&&instance.activity===activity)}
            budget();record.put("roots",rows).put("rootCount",rows.length()).put("outcome","OBSERVED")
        } catch(error: Throwable) {
            record.put("outcome","UNKNOWN").put("errorClass",className(error))
            if(error!==primary) {
                val safe=IllegalStateException("Window owner metadata error class: "+className(error));safe.stackTrace=emptyArray()
                primary.addSuppressed(safe)
            }
        } finally {
            val finished=android.os.SystemClock.elapsedRealtime()
            record.put("finishedElapsedMs",finished).put("elapsedMs",finished-started).put("overBudget",finished>=deadline)
            if(finished>=deadline)record.put("outcome","UNKNOWN")
            if(record.toString().toByteArray(Charsets.UTF_8).size>8192) {
                record.remove("roots");record.put("outcome","UNKNOWN").put("metadataByteLimit",8192).put("metadataQuotaExceeded",true)
            }
        }
    }
    /** Retain the exact Activity window/decor/token once, before the original form binding.
     * DecorContext need not wrap an Activity; object identity is the authority for this root. */
    private fun captureActivityDecorOwner(entry: Entry): ActivityDecorOwner {
        check(android.os.Looper.myLooper()===android.os.Looper.getMainLooper())
        val instance=checkNotNull(entry.instance);P8MainActivityOwners.requireCurrent(instance)
        val activity=instance.activity
        check(currentEntry===entry&&activity.ownership===instance&&instance.launch===entry.activity?.ticket)
        val window=activity.window;val decor=window.decorView
        val params=checkNotNull(decor.layoutParams as? android.view.WindowManager.LayoutParams)
        check(decor.isAttachedToWindow&&decor.rootView===decor&&params.type==android.view.WindowManager.LayoutParams.TYPE_BASE_APPLICATION)
        return ActivityDecorOwner(activity,window,decor,checkNotNull(params.token))
    }
    /** Public API29 local-process metadata only; no child/content scan or AX windows list.
     * Only the retained Activity decor may bypass the Context chain. Every other root must
     * be an exact-Activity application dialog with the same non-null original params token. */
    private fun focusedOwnedDecor(entry: Entry,owner: ActivityDecorOwner,original: android.view.View?=null): Pair<android.view.View?,Int> {
        check(android.os.Looper.myLooper()===android.os.Looper.getMainLooper())
        val instance=checkNotNull(entry.instance);val activity=instance.activity
        P8MainActivityOwners.requireCurrent(instance)
        check(currentEntry===entry&&activity.ownership===instance&&instance.launch===entry.activity?.ticket)
        check(entry.owner.pid==android.os.Process.myPid()&&entry.owner.nonce==NativeChatNotificationColdStart.processNonce)
        fun requireOriginalDecor() {
            check(owner.activity===activity&&owner.window===activity.window&&owner.decor===owner.window.decorView) {"Original Activity window/decor changed"}
            val params=checkNotNull(owner.decor.layoutParams as? android.view.WindowManager.LayoutParams)
            check(owner.decor.isAttachedToWindow&&owner.decor.rootView===owner.decor&&
                params.type==android.view.WindowManager.LayoutParams.TYPE_BASE_APPLICATION&&params.token===owner.token) {"Original Activity decor/token detached or changed"}
        }
        requireOriginalDecor()
        val roots=android.view.inspector.WindowInspector.getGlobalWindowViews()
        check(roots.size<=8) {"Local decor metadata inventory exceeded bound"}
        val attached=roots.filter {it.isAttachedToWindow}
        check(attached.size<=4&&attached.distinct().size==attached.size&&attached.any {it===owner.decor}) {"Original Activity missing from bounded attached decor inventory"}
        for(root in attached) {
            check(root.rootView===root) {"WindowInspector returned a non-decor root"}
            val params=root.layoutParams as? android.view.WindowManager.LayoutParams
            var exact=root===owner.decor
            if(!exact) {
                var context: android.content.Context=root.context
                val seen=mutableListOf<android.content.Context>()
                for(depth in 0 until 16) {
                    if(context===activity){exact=true;break}
                    check(seen.none {it===context}) {"Decor Context cycle"};seen+=context
                    context=(context as? android.content.ContextWrapper)?.baseContext ?: break
                }
            }
            val expectedType=if(root===owner.decor)android.view.WindowManager.LayoutParams.TYPE_BASE_APPLICATION else android.view.WindowManager.LayoutParams.TYPE_APPLICATION
            if(!exact||params==null||params.type!=expectedType||params.token!==owner.token) {
                val primary=IllegalStateException("Attached decor is not the retained original Activity decor or its exact application dialog")
                retainWindowOwnerFailure(entry,attached,root,primary)
                throw primary
            }
        }
        val focused=attached.filter {it.visibility==android.view.View.VISIBLE&&it.windowVisibility==android.view.View.VISIBLE&&it.isShown&&it.hasWindowFocus()}
        check(focused.size<=1) {"Multiple original focused decors"}
        if(original!=null)check(attached.any {it===original}&&focused.singleOrNull()===original) {"Original focused decor changed or detached"}
        requireOriginalDecor();P8MainActivityOwners.requireCurrent(instance)
        return focused.singleOrNull() to attached.size
    }
    /** Only primitive facts from a completed original binding iteration; no extra native reads. */
    private data class WindowBindingSample(val request: Int,val finishedElapsedMs: Long,val attachedOwnerCount: Int,
        val focusedDecorPresent: Boolean,val axNodePresent: Boolean?,val nonNegativeAxWindowId: Boolean)
    /** Bind once before field input; a missing focus/AX ID may only wait read-only within this budget. */
    private fun bindOwnedWindow(entry: Entry,inputBudget: InputBudget): OwnedWindow {
        val started=inputBudget.started;val deadline=inputBudget.deadline
        var requests=0;var original: android.view.View?=null
        var activityDecorOwner: ActivityDecorOwner?=null
        var phase="before-original-binding";var lastCompleteSample: WindowBindingSample?=null
        fun budget(){check(android.os.SystemClock.elapsedRealtime()<deadline&&requests<64) {"Owned focused window binding remains UNKNOWN"}}
        try {
            while(true) {
                budget();requests++
                var selected: android.view.View?=null;var id: Int?=null;var attachedCount=0;var axNodePresent: Boolean?=null
                owned(entry) {main {
                    inputBudget.withinDeadline()
                    phase="local-original-owner"
                    if(activityDecorOwner==null)activityDecorOwner=captureActivityDecorOwner(entry)
                    phase="local-focus-metadata"
                    val observation=focusedOwnedDecor(entry,checkNotNull(activityDecorOwner),original);selected=observation.first;attachedCount=observation.second
                    val decor=selected
                    if(decor!=null) {
                        if(original==null)original=decor else check(original===decor)
                        inputBudget.withinDeadline()
                        phase="original-decor-ax-read"
                        val node=decor.createAccessibilityNodeInfo()
                        axNodePresent=node!=null
                        try {
                            if(node!=null) {
                                check(node.packageName?.toString()==actual.packageName) {"Owned decor AX package changed"}
                                if(node.windowId>=0)id=node.windowId
                            }
                        } finally {@Suppress("DEPRECATION") node?.recycle()}
                        check(focusedOwnedDecor(entry,checkNotNull(activityDecorOwner),decor).first===decor)
                    }
                }}
                phase="sample-postcheck"
                val finished=android.os.SystemClock.elapsedRealtime();check(finished<deadline) {"Owned window binding exceeded original budget"}
                lastCompleteSample=WindowBindingSample(requests,finished,attachedCount,selected!=null,axNodePresent,id!=null)
                if(id!=null) {
                    phase="binding-proof"
                    val instance=checkNotNull(entry.instance);val bindingId=UUID.randomUUID().toString()
                    val proof=JSONObject().put("schema","p8-owned-focused-window-binding-v2")
                        .put("ownerPolicy","retained-activity-decor-and-exact-dialog-token-v1")
                        .put("originalActivityDecorRetained",true).put("originalActivityTokenRetained",true).put("bindingId",bindingId)
                        .put("ownedRun",entry.owner.run).put("pid",entry.owner.pid).put("processNonce",entry.owner.nonce)
                        .put("launchOrdinal",entry.owner.activities.indexOf(entry.activity)).put("windowId",checkNotNull(id))
                        .put("source","WindowInspector.getGlobalWindowViews").put("attachedOwnerCount",attachedCount)
                        .put("startedElapsedMs",started).put("boundElapsedMs",finished).put("elapsedMs",finished-started)
                        .put("requests",requests).put("requestLimit",64).put("deadlineMs",5000).put("beforeFirstFieldInput",true)
                    val binding=OwnedWindow(entry,instance.activity,checkNotNull(activityDecorOwner),checkNotNull(selected),checkNotNull(id),bindingId,proof)
                    proof.put("inputOperations",binding.inputs);entry.owner.nativeWindows.add(binding)
                    requireOwnedWindow(binding)
                    val bound=android.os.SystemClock.elapsedRealtime();check(bound<deadline) {"Owned window binding exceeded original budget after final owner check"}
                    proof.put("boundElapsedMs",bound).put("elapsedMs",bound-started)
                    return binding
                }
                phase="read-only-wait"
                budget();Thread.sleep(maxOf(1L,minOf(50L,deadline-android.os.SystemClock.elapsedRealtime())))
            }
        } catch(error: Throwable) {
            // This assertion has already failed. Serialize cached primitive facts only: no new
            // WindowInspector/Activity/View/AX access and no new wait or input is performed here.
            try {
                if(entry.owner.windowBindingFailure==null) {
                    val sample=lastCompleteSample
                    val errorClass=error.javaClass.name.let {if(it.matches(Regex("[A-Za-z0-9_.$]{1,96}")))it else "CLASS_NAME_UNAVAILABLE"}
                    val record=JSONObject().put("schema","p8-window-binding-failure-cached-v1").put("failureOnly",true)
                        .put("ownershipProven",false).put("contentInspected",false).put("additionalWindowInventoryReads",0)
                        .put("additionalAxNodeAcquisitions",0).put("metadataByteLimit",2048).put("phase",phase)
                        .put("requests",requests).put("requestLimit",64).put("deadlineMs",5000).put("startedElapsedMs",started)
                        .put("originalActivityDecorRetained",activityDecorOwner!=null).put("originalFocusedDecorRetained",original!=null)
                        .put("errorClass",errorClass).put("lastCompleteSample",sample?.let {JSONObject()
                            .put("request",it.request).put("finishedElapsedMs",it.finishedElapsedMs).put("attachedOwnerCount",it.attachedOwnerCount)
                            .put("focusedDecorPresent",it.focusedDecorPresent).put("axNodePresent",it.axNodePresent?:JSONObject.NULL)
                            .put("nonNegativeAxWindowId",it.nonNegativeAxWindowId)}?:JSONObject.NULL)
                    check(record.toString().toByteArray(Charsets.UTF_8).size<=2048)
                    entry.owner.windowBindingFailure=record
                }
            } catch(record: Throwable) {
                if(record!==error) {
                    val safe=IllegalStateException("Window binding cached metadata unavailable");safe.stackTrace=emptyArray()
                    error.addSuppressed(safe)
                }
            }
            throw error
        }
    }
    private fun requireOwnedWindow(binding: OwnedWindow) = main {
        val entry=binding.entry;val instance=checkNotNull(entry.instance)
        check(binding.activity===instance.activity&&currentEntry===entry)
        check(focusedOwnedDecor(entry,binding.activityDecorOwner,binding.decor).first===binding.decor)
        val node=checkNotNull(binding.decor.createAccessibilityNodeInfo()) {"Original decor AX ID became unknown"}
        try {check(node.packageName?.toString()==actual.packageName&&node.windowId==binding.windowId) {"Original decor AX window changed"}}
        finally {@Suppress("DEPRECATION") node.recycle()}
        check(focusedOwnedDecor(entry,binding.activityDecorOwner,binding.decor).first===binding.decor)
    }
    private fun <T> windowOwned(binding: OwnedWindow,action: ()->T): T = owned(binding.entry) {
        requireOwnedWindow(binding);var first: Throwable?=null
        try {action()}catch(error: Throwable){first=error;throw error}
        finally {try {requireOwnedWindow(binding)}catch(error: Throwable){
            if(first==null)throw error else if(first!==error) {
                // Preserve the primary identity. Secondary framework failures may carry a tree.
                val metadata=IllegalStateException("Owned window post-check failed: ${error.javaClass.name}")
                metadata.stackTrace=emptyArray()
                first.addSuppressed(metadata) // No original message, cause, suppressed chain or stack.
            }
        }}
    }
    /** One original field operation only. The UNKNOWN record is never removed or re-entered. */
    private fun fieldInput(binding: OwnedWindow,name: String,inputBudget: InputBudget=InputBudget(),action: (()->Unit)->Unit) {
        val expected=listOf("name-replacement","name-ime","address-replacement","address-ime")
        check(binding.inputs.length()<expected.size&&name==expected[binding.inputs.length()])
        val record=JSONObject().put("name",name).put("startedElapsedMs",inputBudget.started)
            .put("deadlineElapsedMs",inputBudget.deadline).put("deadlineMs",5000)
            .put("beforeElapsedMs",JSONObject.NULL).put("inputCallElapsedMs",JSONObject.NULL).put("outcome","UNKNOWN")
        binding.inputs.put(record)
        inputBudget.withinDeadline()
        windowOwned(binding) {
            record.put("beforeElapsedMs",inputBudget.withinDeadline()) // The window/owner guard may have blocked.
            action {
                check(record.isNull("inputCallElapsedMs")) {"Original field input gate cannot be re-entered"}
                record.put("inputCallElapsedMs",inputBudget.withinDeadline())
            }
            check(!record.isNull("inputCallElapsedMs")) {"Original input requires its immediate deadline gate"}
            inputBudget.withinDeadline()
        }
        record.put("afterElapsedMs",inputBudget.withinDeadline()).put("outcome","CONFIRMED")
    }
    private fun cleanupEntry(entry: Entry,errors: Failures) {
        // Known session/IO/controller handles can quiesce once even if Activity close is unknown.
        // Retirement and stable evidence wait for that UI owner plus every producer to be confirmed.
        val state=entry.ledger
        state.step("leave",errors){main {entry.session.leaveForeground()}}
        state.step("session-joined",errors){
            val tasks=MobileSession::class.java.getDeclaredField("tasks").apply{isAccessible=true}.get(entry.session) as CoroutineScope
            entry.sessionJob=checkNotNull(tasks.coroutineContext[Job])
            runBlocking {withTimeout(10000){withContext(Dispatchers.Main.immediate){entry.sessionJob!!.cancelAndJoin()}}}
        }
        state.step("io-closed",errors){entry.io=checkNotNull(entry.session.codexMigrationContext[NativeMigrationIoWork]);entry.io!!.close()}
        state.step("io-idle",errors,ready=state.confirmed("io-closed")){runBlocking {withTimeout(10000){entry.io!!.awaitIdle()}}}
        state.step("migration-joined",errors){entry.migration?.let {migration->
            val scope=NativeMigrationController::class.java.getDeclaredField("tasks").apply{isAccessible=true}.get(migration) as CoroutineScope
            entry.migrationJob=checkNotNull(scope.coroutineContext[Job])
            runBlocking {withTimeout(10000){withContext(Dispatchers.Main.immediate){entry.migrationJob!!.cancelAndJoin()}}}
        }}
        val producersJoined=(entry.activity?.closed!=false)&&state.confirmed("session-joined","io-closed","io-idle","migration-joined")
        state.step("writer-drained",errors,ready=producersJoined){awaitStore(entry.storage)}
        state.step("writer-retired",errors,ready=state.confirmed("writer-drained")){
            entry.writer=NativeSessionStorage::class.java.getDeclaredField("writes").apply{isAccessible=true}.get(entry.storage) as ExecutorService
            entry.storage.retireWriter()
        }
        state.step("writer-terminated",errors,ready=state.confirmed("writer-retired")){check(entry.writer!!.awaitTermination(10,TimeUnit.SECONDS)) {"Owned writer completion unknown"}}
        state.step("port-evidence",errors,ready=producersJoined){assertAndExportPorts(entry)}
    }
    private fun closeTracked(entry: Entry) {
        val errors=Failures()
        if(currentEntry===entry){errors.capture {assertEntry(entry)};currentEntry=null}
        entry.activity?.let {closeActivity(it,errors)};captureActivityEntries(entry.owner);cleanupEntry(entry,errors)
        if(!entry.released)errors.add(IllegalStateException("Owner incomplete; retain original PID ${entry.owner.pid}: ${entry.ledger.summary()}"))
        errors.throwIfAny()
        // Keep records, including confirmed handles. Never remove an entry to simulate release.
    }
    private fun status(phase: String,context: P8MainFilesContext,details: String="") {
        admission.emit(phase,JSONObject().put("ownedRun",context.run).put("ownedScope",context.root.path).put("ownedAlias",context.alias)
            .put("pid",android.os.Process.myPid()).put("uid",android.os.Process.myUid()).put("processNonce",NativeChatNotificationColdStart.processNonce)
            .put("component",P8MainPreferenceActivity::class.java.name).put("coverage","Main lifecycle subclass; native assembly excluded; no shipping pixels")
            .put("defaultSelections",NativeChatNotificationColdStart.defaultSelections.get()).put("details",details))
    }
    private fun assertAndExportPorts(entry: Entry) {
        val calls=entry.ports.snapshot();val expected=checkNotNull(entry.owner.expectedClient)
        fun fields(target: TargetScope?)=target?.let {listOf(it.agentId,it.accountAuthority,it.accountSubject,it.hostId,it.workspaceId,it.runtimeId)}
        fun targetHash(target: TargetScope?)=MessageDigest.getInstance("SHA-256").digest((fields(target)?.let {JSONArray(it).toString()}?:"null").toByteArray(Charsets.UTF_8)).joinToString(""){"%02x".format(it)}
        // UsageViews:55–68 polls the visible saved-client row; MobileSession:583–609 sweeps directories.
        // Both are real local read attempts, rejected here rather than delegated or cache-prefilled.
        val unexpected=calls.filterNot {it.provider=="easywork"&&it.method in setOf("workspace","usage")&&it.clientId==expected.id&&
            fields(it.target)==fields(expected.scope)&&it.projectCursor==null&&it.threadCursor==null}
        val errors=Failures()
        errors.capture {check(entry.ports.overflow.get()==0&&unexpected.isEmpty()) {"Unexpected typed attempts: ${unexpected.map {it.provider+"/"+it.method}}; overflow=${entry.ports.overflow.get()}"}}
        errors.capture {check(entry.context.denied.isEmpty()) {"Runtime-context system/resolver forwarding was attempted"}}
        errors.capture {
            val counts=calls.groupingBy {it.provider+"/"+it.method+"/"+targetHash(it.target)}.eachCount()
            status("typed-port-proof-before-owner-release",entry.context,JSONObject().put("counts",JSONObject(counts))
                .put("allowed","exact synthetic EasyWork target/client: workspace(null cursors) directory sweep; usage visible-client presentation poll")
                .put("workspaceRejections",calls.count {it.method=="workspace"}).put("usageRejections",calls.count {it.method=="usage"})
                .put("expectedTargetSha256",targetHash(expected.scope)).put("expectedClientId",expected.id)
                .put("unexpected",unexpected.size).put("overflow",entry.ports.overflow.get()).put("contextDeniedCount",entry.context.denied.size)
                .put("contextDeniedLabels",JSONArray(entry.context.denied.take(16))).put("contextServiceForwardingByFixture",false)
                .put("externalNetworkProof",false).put("ledger",JSONObject(entry.ledger.summary())).toString())
        }
        errors.throwIfAny()
    }
    private fun launch(owner: CaseOwners): Entry {
        check(currentEntry==null)
        val activityOwner=ActivityOwner(owner);owner.activities.add(activityOwner)
        val errors=Failures()
        activityOwner.ledger.step("instance-prepared",errors){main {activityOwner.ticket=P8MainActivityOwners.prepare(owner.run)}};errors.throwIfAny()
        activityOwner.ledger.step("launch-returned",errors){activityOwner.scenario=ActivityScenario.launch(Intent(actual,P8MainPreferenceActivity::class.java).setAction(Intent.ACTION_MAIN).putExtra(P8MainPreferenceActivity.RUN,owner.run))}
        // Capture registry owners even if launch threw; the finalizer repeats only this idempotent observation.
        errors.capture {captureActivityEntries(owner)};errors.throwIfAny()
        var result: Entry?=null
        activityOwner.ledger.step("owner-captured",errors){checkNotNull(activityOwner.scenario).onActivity {activity->
            val instance=activity.ownership;P8MainActivityOwners.requireCurrent(instance)
            check(instance.launch===activityOwner.ticket&&activityOwner.ticket!!.instances.single()===instance)
            result=owner.entries.single {it.instance===instance&&it.session===activity.session}
            assertTrue(activity.preInitDefaultFamiliesAbsent&&activity.afterCreateDefaultFamiliesAbsent)
        }};errors.throwIfAny()
        val entry=checkNotNull(result);currentEntry=entry;assertEntry(entry)
        owned(entry){ui.waitUntil(10000){!entry.session.restoring}}
        owned(entry){ui.runOnIdle {assertTrue(entry.session.demo);assertTrue(entry.session.selectedClient.isDemo);assertNull(entry.session.discovery);assertNull(entry.session.easyWorkDiscovery);assertNull(entry.session.chatNotifications);assertNull(entry.session.voice)}}
        status("inherited-Main-restored",entry.context)
        return entry
    }
    private fun openEditor(id: String) {
        owned {ui.onNodeWithTag("client-selector").assertIsDisplayed().performClick()}
        owned {ui.onNodeWithTag("edit-client-$id").performScrollTo().performClick()}
        owned {ui.onNodeWithTag("connection-preference").performScrollTo().assertIsEnabled()}
    }
    /** Full visible geometry is distinct from the complete semantics text. Single-line inputs
     * intentionally scroll horizontally; this does not claim all their glyphs fit at once. */
    private fun whole(tag: String,unmerged: Boolean=false,scroll: Boolean=true): SemanticsNodeInteraction {
        val node=ui.onNodeWithTag(tag,useUnmergedTree=unmerged)
        if(scroll)owned {node.performScrollTo()}
        owned {node.assertIsDisplayed()}
        val raw=owned {node.getUnclippedBoundsInRoot()};val visible=owned {node.fetchSemanticsNode().boundsInRoot}
        val density=owned {ui.density.density}
        assertTrue("Full width reachable: $tag",visible.width>=(raw.right.value-raw.left.value)*density-1.5f)
        assertTrue("Full height reachable: $tag",visible.height>=(raw.bottom.value-raw.top.value)*density-1.5f)
        return node
    }
    private fun completeInput(tag: String,value: String,label: String) {
        owned {whole(tag).assertContentDescriptionEquals(label).assert(SemanticsMatcher("Complete original input, including tail") {node->
            node.config.getOrNull(SemanticsProperties.EditableText)==AnnotatedString(value)&&
                node.config.getOrNull(SemanticsProperties.InputText)==AnnotatedString(value)
        })}
    }
    private fun fullTargetLayout(tag: String,value: String,multiline: Boolean=false) {
        val layouts=mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        owned {ui.onNodeWithTag(tag,useUnmergedTree=true).performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) {assertTrue(it(layouts))}}
        assertEquals(1,layouts.size);val layout=layouts.single()
        assertEquals(value,layout.layoutInput.text.text)
        assertEquals("Actual Compose text uses the instance override",2f,layout.layoutInput.density.fontScale,0f)
        assertTrue(layout.lineCount>0);if(multiline)assertTrue("Long target really wraps: $tag",layout.lineCount>1)
        assertFalse("No visual target overflow: $tag",layout.hasVisualOverflow)
        (0 until layout.lineCount).forEach {assertFalse("Visual ellipsis differs from full semantics: $tag",layout.isLineEllipsized(it))}
        assertEquals(value.length,layout.getLineEnd(layout.lineCount-1,visibleEnd=false))
    }
    /** A post-action ownership failure must still recycle the exact newly returned AX handle. */
    private fun axNode(action: ()->android.view.accessibility.AccessibilityNodeInfo?): android.view.accessibility.AccessibilityNodeInfo? {
        var acquired: android.view.accessibility.AccessibilityNodeInfo?=null
        try {owned {acquired=action()};return acquired}
        catch(error: Throwable){@Suppress("DEPRECATION") acquired?.recycle();throw error}
    }
    // Only these fourteen original measurements may publish count-only readiness evidence.
    private val nativeAxFields=listOf("easywork-name")+listOf("agent","authority","subject","host","workspace","runtime").flatMap {
        listOf("easywork-target-label-$it","easywork-target-$it")
    }+"easywork-manual-address"
    private class AxPending(val state: String): Exception()
    private data class AxGeometry(val window: android.graphics.Rect,val field: android.graphics.Rect,val readable: Int)
    /** Failure-only metadata observation. This cannot satisfy or retry nativeAxComplete.
     * No text/contentDescription, unknown tags, other windows, or input actions are read/exported.
     * One synchronous root, with a cooperative deadline around every owned AX call; a blocked
     * platform call cannot be cancelled safely and is reported UNKNOWN after it returns. */
    private fun nativeAxFailureDiagnostic(binding: OwnedWindow,tag: String,anchor: Int?,windowId: Int?,lastCompleteSample: JSONObject?,primary: Throwable) {
        val entry=binding.entry;check(currentEntry===entry)
        val started=android.os.SystemClock.elapsedRealtime();val deadline=started+1500L
        val idKey="androidx.compose.ui.semantics.id";val tagKey="androidx.compose.ui.semantics.testTag"
        var visited=0;var idDeclared=0;var tagDeclared=0;var exactIds=0;var exactTags=0
        var completed=false;var limit="none";var stage="entry";var diagnosticError: Throwable?=null
        val candidates=JSONArray();val rootFacts=JSONObject()
        val composeIds=JSONObject();val activityWindow=JSONObject().put("state","UNKNOWN").put("windowId",JSONObject.NULL)
            .put("equalsOriginalActiveWindow",JSONObject.NULL).put("errorClass",JSONObject.NULL)
        val dialogWindow=JSONObject().put("state","UNKNOWN").put("windowId",JSONObject.NULL)
            .put("source","original-retained-focused-decor").put("errorClass",JSONObject.NULL)
        val diagnostic=JSONObject().put("schema","p8-native-ax-failure-diagnostic-v3").put("field",tag)
            .put("windowBindingId",binding.id).put("ownedRun",entry.owner.run).put("pid",entry.owner.pid).put("processNonce",entry.owner.nonce)
            .put("expectedAnchor",anchor?:JSONObject.NULL).put("windowId",windowId?:JSONObject.NULL)
            .put("primaryErrorClass",primary.javaClass.name).put("lastCompleteSample",lastCompleteSample?:JSONObject.NULL)
        fun suppress(error: Throwable) {
            if(error!==primary) {
                // Framework failures may contain a semantics tree. Export only the class.
                val safe=IllegalStateException("AX failure diagnostic error class: "+error.javaClass.name)
                safe.stackTrace=emptyArray();primary.addSuppressed(safe)
            }
        }
        fun budget() {
            if(android.os.SystemClock.elapsedRealtime()>=deadline) {limit="time";throw IllegalStateException("AX failure diagnostic budget exhausted")}
        }
        fun <T> read(action: ()->T): T {
            budget()
            val result=windowOwned(binding) {budget();val value=action();budget();value}
            budget();return result
        }
        fun sameWindow(node: android.view.accessibility.AccessibilityNodeInfo)=read {
            check(node.packageName?.toString()==actual.packageName&&node.windowId==checkNotNull(windowId)) {"AX failure diagnostic target changed"}
        }
        fun <T> nodeRead(node: android.view.accessibility.AccessibilityNodeInfo,action: ()->T): T {
            sameWindow(node);val result=read(action);sameWindow(node);return result
        }
        fun acquire(action: ()->android.view.accessibility.AccessibilityNodeInfo?): android.view.accessibility.AccessibilityNodeInfo? {
            var acquired: android.view.accessibility.AccessibilityNodeInfo?=null
            try {read {acquired=action()};return acquired}
            catch(error: Throwable){@Suppress("DEPRECATION") acquired?.recycle();throw error}
        }
        fun publicClass(node: android.view.accessibility.AccessibilityNodeInfo)=nodeRead(node) {
            // Export only fixed public framework roles; never arbitrary class/metadata strings.
            when(node.className?.toString()) {
                "android.view.View"->"android.view.View"
                "android.view.ViewGroup"->"android.view.ViewGroup"
                "android.widget.EditText"->"android.widget.EditText"
                "android.widget.TextView"->"android.widget.TextView"
                "android.widget.Button"->"android.widget.Button"
                "android.widget.ScrollView"->"android.widget.ScrollView"
                "android.widget.FrameLayout"->"android.widget.FrameLayout"
                "android.widget.LinearLayout"->"android.widget.LinearLayout"
                else->"other"
            }
        }
        try {
            check(tag in nativeAxFields) {"AX failure diagnostic fixed field unknown"}
            read {check(windowId==binding.windowId)} // Also revalidates original decor/focus/AX ID.
            dialogWindow.put("state","OBSERVED").put("windowId",binding.windowId)
            stage="compose-identity"
            var mergedId: Int?=null;var unmergedId: Int?=null
            // Each tree mode is observed once for this fixed tag. No semantics config/value read.
            for(unmerged in listOf(false,true)) {
                val observation=JSONObject().put("state","UNKNOWN").put("semanticsId",JSONObject.NULL)
                    .put("equalsOriginalAnchor",JSONObject.NULL).put("errorClass",JSONObject.NULL)
                composeIds.put(if(unmerged)"unmerged" else "merged",observation)
                try {
                    val id=read {
                        val matches=ui.onAllNodesWithTag(tag,useUnmergedTree=unmerged).fetchSemanticsNodes()
                        check(matches.size==1) {"AX diagnostic fixed-tag count mismatch"}
                        matches.single().id
                    }
                    if(unmerged)unmergedId=id else mergedId=id
                    observation.put("state","OBSERVED").put("semanticsId",id)
                        .put("equalsOriginalAnchor",if(anchor==null)JSONObject.NULL else id==anchor)
                } catch(error: Throwable) {
                    observation.put("errorClass",error.javaClass.name)
                    if(diagnosticError==null)diagnosticError=error;suppress(error)
                }
            }
            composeIds.put("mergedEqualsUnmerged",if(mergedId==null||unmergedId==null)JSONObject.NULL else mergedId==unmergedId)
            stage="original-activity-window"
            try {
                var measuredWindowId: Int?=null
                read {checkNotNull(entry.activity?.scenario).onActivity {activity->
                    val instance=checkNotNull(entry.instance)
                    budget();P8MainActivityOwners.requireCurrent(instance);budget()
                    check(activity===instance.activity&&activity.ownership===instance) {"AX diagnostic original Activity changed"}
                    // This is only the original Activity window, never an inferred Dialog window.
                    val info=checkNotNull(activity.window.decorView.createAccessibilityNodeInfo()) {"AX diagnostic Activity window node unknown"}
                    try {
                        budget();measuredWindowId=info.windowId;budget()
                        P8MainActivityOwners.requireCurrent(instance);budget()
                    } finally {@Suppress("DEPRECATION") info.recycle()}
                }}
                val id=checkNotNull(measuredWindowId);check(id>=0) {"AX diagnostic Activity window ID unknown"}
                activityWindow.put("state","OBSERVED").put("windowId",id)
                    .put("equalsOriginalActiveWindow",if(windowId==null)JSONObject.NULL else id==windowId)
            } catch(error: Throwable) {
                activityWindow.put("errorClass",error.javaClass.name)
                if(diagnosticError==null)diagnosticError=error;suppress(error)
            }
            check(anchor!=null&&windowId!=null) {"AX failure diagnostic original target unknown"}
            stage="root-acquisition"
            val root=checkNotNull(acquire {checkNotNull(originalAutomation).rootInActiveWindow}) {"AX failure diagnostic root unknown"}
            try {
                sameWindow(root)
                rootFacts.put("samePackage",true).put("sameWindow",true).put("activeWindowId",nodeRead(root) {root.windowId})
                stage="root-refresh"
                val rootRefreshed=nodeRead(root) {root.refresh()}
                rootFacts.put("refreshReturned",rootRefreshed)
                check(rootRefreshed) {"AX failure diagnostic root refresh unknown"}
                stage="optional-window-info"
                var window: android.view.accessibility.AccessibilityWindowInfo?=null
                try {
                    nodeRead(root) {window=root.window}
                    val originalWindow=window
                    rootFacts.put("windowInfoAvailable",originalWindow!=null)
                    if(originalWindow!=null) {
                        val active=nodeRead(root) {check(originalWindow.id==windowId);originalWindow.isActive}
                        rootFacts.put("active",active);check(active) {"AX failure diagnostic window no longer active"}
                    }
                    // getWindow() is nullable metadata. The retained focused decor plus
                    // exact root package/window ID guard every permitted observation.
                } finally {@Suppress("DEPRECATION") window?.recycle()}
                stage="root-bounds"
                val windowBounds=android.graphics.Rect();nodeRead(root) {root.getBoundsInScreen(windowBounds)}
                rootFacts.put("visible",nodeRead(root) {root.isVisibleToUser}).put("publicClass",publicClass(root))
                fun visit(node: android.view.accessibility.AccessibilityNodeInfo,depth: Int) {
                    budget();sameWindow(node)
                    check(depth<=40&&visited<2048) {"AX failure diagnostic traversal bound"};visited++
                    val declarations=nodeRead(node) {node.availableExtraData}
                    val hasId=declarations.contains(idKey);val hasTag=declarations.contains(tagKey)
                    if(hasId)idDeclared++;if(hasTag)tagDeclared++
                    var id: Int?=null
                    if(hasId) {
                        check(nodeRead(node) {node.refreshWithExtraData(idKey,android.os.Bundle())}) {"AX failure diagnostic ID refresh unknown"}
                        id=nodeRead(node) {
                            check(node.extras.containsKey(idKey)&&node.extras.get(idKey) is Int) {"AX failure diagnostic ID metadata unknown"}
                            node.extras.getInt(idKey)
                        }
                        if(id==anchor)exactIds++
                    }
                    if(hasTag) {
                        check(nodeRead(node) {node.refreshWithExtraData(tagKey,android.os.Bundle())}) {"AX failure diagnostic tag refresh unknown"}
                        // Compare in place. Unknown tag strings are never retained or exported.
                        val exactTag=nodeRead(node) {node.extras.getCharSequence(tagKey)?.toString()==tag}
                        if(exactTag) {
                            exactTags++
                            if(candidates.length()<4) {
                                val bounds=android.graphics.Rect();nodeRead(node) {node.getBoundsInScreen(bounds)}
                                val visible=nodeRead(node) {node.isVisibleToUser}
                                val visibleBounds=android.graphics.Rect(bounds)
                                val hasVisibleBounds=visible&&!visibleBounds.isEmpty&&visibleBounds.intersect(windowBounds)
                                val readable=nodeRead(node) {(if(node.isScreenReaderFocusable)1 else 0)+(if(node.isFocusable)2 else 0)+(if(node.isEditable)4 else 0)}
                                candidates.put(JSONObject().put("semanticsId",id?:JSONObject.NULL).put("publicClass",publicClass(node))
                                    .put("readable",readable).put("visible",visible).put("enabled",nodeRead(node) {node.isEnabled})
                                    .put("visibleBounds",if(hasVisibleBounds)JSONArray(listOf(visibleBounds.left,visibleBounds.top,visibleBounds.right,visibleBounds.bottom)) else JSONObject.NULL))
                            }
                        }
                    }
                    val count=nodeRead(node) {node.childCount};check(count in 0..2048) {"AX failure diagnostic child count bound"}
                    for(index in 0 until count) {
                        budget();sameWindow(node)
                        if(depth>=40) {limit="depth";throw IllegalStateException("AX failure diagnostic depth exhausted")}
                        if(visited>=2048) {limit="nodes";throw IllegalStateException("AX failure diagnostic nodes exhausted")}
                        val child=checkNotNull(acquire {node.getChild(index)}) {"AX failure diagnostic child unknown"}
                        try {sameWindow(node);visit(child,depth+1)}finally {@Suppress("DEPRECATION") child.recycle()}
                    }
                }
                stage="bounded-traversal"
                visit(root,0);sameWindow(root);budget()
            } finally {@Suppress("DEPRECATION") root.recycle()}
            read {Unit};stage="complete";completed=diagnosticError==null
        } catch(error: Throwable) {completed=false;if(diagnosticError==null)diagnosticError=error;suppress(error)}
        val actualElapsedMs=android.os.SystemClock.elapsedRealtime()-started
        val overBudget=actualElapsedMs>=1500L
        if(overBudget&&diagnosticError==null) {
            completed=false;limit="time"
            val error=IllegalStateException("AX failure diagnostic budget exceeded after final check")
            diagnosticError=error;suppress(error)
        }
        diagnostic.put("outcome",if(completed)"OBSERVED" else "UNKNOWN").put("complete",completed)
            .put("visitedNodes",visited).put("idExtraDeclared",idDeclared).put("tagDeclared",tagDeclared)
            .put("exactIDCount",exactIds).put("exactTagCount",exactTags).put("candidates",candidates).put("root",rootFacts)
            .put("composeIds",composeIds).put("activityWindow",activityWindow).put("dialogWindow",dialogWindow)
            .put("candidateCap",4).put("nodeCap",2048).put("depthCap",40).put("deadlineMs",1500)
            .put("limit",limit).put("stage",stage).put("actualElapsedMs",actualElapsedMs).put("overBudget",overBudget)
            .put("failureCount",if(diagnosticError==null)0 else 1)
            .put("errorClass",diagnosticError?.javaClass?.name?:JSONObject.NULL)
        // A separate failed-run bundle key cannot enter the Host's exact success/phase contracts.
        try {owned(entry) {instrumentation.sendStatus(0,android.os.Bundle().apply {putString("p8AxFailureDiagnostic",diagnostic.toString())})}}
        catch(error: Throwable) {suppress(error)}
    }
    /** Read-only exact-anchor readiness, never a business-action retry. Every attempt obtains and
     * refreshes its own active root, then releases every returned handle before another attempt.
     * Null root may wait only under the original pre-input decor/window binding, as may explicit
     * stale refresh, absent anchor, or AX value lag. Every other acquisition, owner/window/tag/
     * structure/label/geometry error fails immediately; no other window is adopted. */
    private fun nativeAxComplete(binding: OwnedWindow,tag: String,value: String,label: String?=null) {
        check(tag in nativeAxFields)
        val entry=checkNotNull(currentEntry);check(entry===binding.entry);val owner=entry.owner
        val idKey="androidx.compose.ui.semantics.id";val tagKey="androidx.compose.ui.semantics.testTag"
        val started=android.os.SystemClock.elapsedRealtime();val deadline=started+5000L
        var anchor: Int?=null;var windowId: Int?=binding.windowId;var samples=0;var rootRequests=0
        var visited=0;var idExtras=0;var matched=0;var readyTotal=0
        var stage="compose-anchor";var lastState="fatal";var passed=false;var timedOut=false;var finished: Long?=null
        var firstGeometry: AxGeometry?=null
        var lastCompleteSample: JSONObject?=null // Diagnostic only; never part of the exact success proof.
        val readySamples=mutableListOf<Int>()
        val pendingCounts=linkedMapOf("root-pending" to 0,"anchor-absent" to 0,"node-refresh-pending" to 0,"value-pending" to 0)
        fun withinDeadline(): Long {
            val now=android.os.SystemClock.elapsedRealtime()
            if(now>=deadline) {
                timedOut=true;lastState="timeout"
                throw AssertionError("Exact native AX readiness deadline: $tag")
            }
            return now
        }
        fun <T> read(action: ()->T): T {
            withinDeadline();val result=windowOwned(binding) {withinDeadline();action()};withinDeadline();return result
        }
        fun acquire(action: ()->android.view.accessibility.AccessibilityNodeInfo?): android.view.accessibility.AccessibilityNodeInfo? {
            var node: android.view.accessibility.AccessibilityNodeInfo?=null
            try {read {node=action()};return node}
            catch(error: Throwable){@Suppress("DEPRECATION") node?.recycle();throw error}
        }
        fun composeAnchor() {
            read {
                val node=ui.onNodeWithTag(tag).fetchSemanticsNode()
                check(node.id==checkNotNull(anchor)) {"Original Compose anchor changed: $tag"}
                val config=node.config
                if(label!=null) {
                    check(config.getOrNull(SemanticsProperties.EditableText)==AnnotatedString(value)&&
                        config.getOrNull(SemanticsProperties.InputText)==AnnotatedString(value)&&
                        config.getOrNull(SemanticsProperties.ContentDescription)==listOf(label)) {"Original Compose input changed: $tag"}
                } else check(config.getOrNull(SemanticsProperties.Text)==listOf(AnnotatedString(value))) {"Original Compose text changed: $tag"}
            }
        }
        fun sample(): AxGeometry? {
            stage="root-acquisition";withinDeadline();rootRequests++
            val root=acquire {checkNotNull(originalAutomation).rootInActiveWindow} ?: throw AxPending("root-pending")
            try {
                fun own(node: android.view.accessibility.AccessibilityNodeInfo) = read {
                    check(node.packageName?.toString()==actual.packageName&&node.windowId==checkNotNull(windowId)) {"AX package/window changed: $tag"}
                }
                // Inspect metadata only until exact current-app/window ownership is established.
                val originalWindow=read {
                    check(root.packageName?.toString()==actual.packageName&&root.windowId>=0) {"Wrong active AX app/window: $tag"}
                    root.windowId
                }
                check(windowId==originalWindow) {"Active AX window changed between fresh samples: $tag"}
                fun refresh(node: android.view.accessibility.AccessibilityNodeInfo,key: String?=null) {
                    withinDeadline();own(node)
                    val refreshed=read {if(key==null)node.refresh() else node.refreshWithExtraData(key,android.os.Bundle())}
                    own(node);withinDeadline()
                    if(!refreshed)throw AxPending("node-refresh-pending")
                }
                stage="root-refresh";refresh(root)
                val windowBounds=android.graphics.Rect();read {root.getBoundsInScreen(windowBounds)}
                check(read {root.isVisibleToUser}&&!windowBounds.isEmpty) {"Invisible or empty active AX window: $tag"}
                fun nativeId(node: android.view.accessibility.AccessibilityNodeInfo): Int? {
                    refresh(node)
                    if(!read {node.availableExtraData.contains(idKey)})return null
                    idExtras++;refresh(node,idKey)
                    return read {
                        check(node.extras.containsKey(idKey)&&node.extras.get(idKey) is Int) {"Malformed declared AX ID extra: $tag"}
                        node.extras.getInt(idKey)
                    }
                }
                var geometry: AxGeometry?=null;var valueReady=true;var associatedLabels=0
                fun visit(node: android.view.accessibility.AccessibilityNodeInfo,depth: Int,parentId: Int?) {
                    stage="tree-structure";withinDeadline();own(node)
                    check(depth<=40&&++visited<=2048) {"AX tree bound exceeded: $tag"}
                    val id=nativeId(node)
                    if(id==anchor) {
                        check(++matched==1) {"Multiple exact native AX anchors: $tag"}
                        stage="anchor-tag";check(read {node.availableExtraData.contains(tagKey)}) {"Exact anchor lacks declared AX testTag: $tag"}
                        refresh(node,tagKey)
                        read {check(node.extras.getCharSequence(tagKey)?.toString()==tag) {"Exact AX testTag changed: $tag"}}
                        stage="anchor-semantics"
                        val readable=read {
                            check(node.isVisibleToUser&&node.isEnabled) {"Exact AX anchor hidden or disabled: $tag"}
                            check(node.isScreenReaderFocusable||node.isFocusable||node.isEditable) {"Exact AX anchor has no readable semantics: $tag"}
                            (if(node.isScreenReaderFocusable)1 else 0)+(if(node.isFocusable)2 else 0)+(if(node.isEditable)4 else 0)
                        }
                        val bounds=android.graphics.Rect();read {node.getBoundsInScreen(bounds)}
                        check(!bounds.isEmpty&&windowBounds.contains(bounds)) {"Exact AX anchor outside active window: $tag"}
                        if(label!=null) {
                            read {check(node.isEditable) {"Exact AX input is not editable: $tag"}}
                            associatedLabels+=read {if(node.contentDescription?.toString()==label)1 else 0}
                            check(associatedLabels<=1) {"Duplicate exact native input label: $tag"}
                        }
                        // Exact Compose still contains the expected original value. Only its AX publication may lag.
                        valueReady=read {node.text?.toString()==value}
                        geometry=AxGeometry(android.graphics.Rect(windowBounds),android.graphics.Rect(bounds),readable)
                    }
                    // Associate the exact virtual label during this one bounded traversal.
                    // No prepass acquires children outside the same depth/node budget.
                    if(label!=null&&parentId==anchor&&id?.toLong()==checkNotNull(anchor).toLong()+2_000_000_000L) {
                        read {check(node.contentDescription?.toString()==label) {"Exact AX input label changed: $tag"}}
                        associatedLabels++
                        check(associatedLabels<=1) {"Duplicate exact native input label: $tag"}
                    }
                    val count=read {node.childCount};check(count in 0..2048) {"AX child count exceeds bound: $tag"}
                    for(k in 0 until count) {
                        withinDeadline();check(depth<40&&visited<2048) {"AX child acquisition would exceed tree bound: $tag"}
                        val child=checkNotNull(acquire {node.getChild(k)}) {"AX child acquisition unknown: $tag"}
                        try {visit(child,depth+1,id)}finally {@Suppress("DEPRECATION") child.recycle()}
                    }
                }
                visit(root,0,null)
                lastCompleteSample=JSONObject().put("sample",samples).put("visitedNodes",visited)
                    .put("idExtraNodes",idExtras).put("anchorCount",matched)
                // Structural facts already observed must fail before any later pending refresh.
                if(matched==1&&label!=null)check(associatedLabels==1) {"Complete native label must belong once to exact input: $tag"}
                stage="sample-postcheck";composeAnchor();refresh(root)
                read {
                    val after=android.graphics.Rect();root.getBoundsInScreen(after)
                    check(after==windowBounds) {"Active AX window bounds changed during sample: $tag"}
                }
                withinDeadline()
                if(matched==0)throw AxPending("anchor-absent")
                check(matched==1) {"One exact native AX anchor required: $tag"}
                if(!valueReady)throw AxPending("value-pending")
                return checkNotNull(geometry)
            } finally {@Suppress("DEPRECATION") root.recycle()}
        }
        var failure: Throwable?=null
        try {
            anchor=read {ui.onNodeWithTag(tag).fetchSemanticsNode().id};check(checkNotNull(anchor)>0)
            composeAnchor()
            while(true) {
                withinDeadline();check(samples<64) {"Exact native AX sample cap exceeded: $tag"}
                samples++;visited=0;idExtras=0;matched=0;lastState="fatal";composeAnchor()
                val geometry=try {sample()}catch(pending: AxPending) {
                    // Only our explicit return-value states may wait. Suppressed owner failures never retry.
                    check(pending.suppressed.isEmpty());assertEntry(entry);requireOwnedWindow(binding);composeAnchor();withinDeadline()
                    check(pending.state in pendingCounts);lastState=pending.state
                    pendingCounts[pending.state]=checkNotNull(pendingCounts[pending.state])+1
                    null
                }
                if(geometry!=null) {
                    readyTotal++;lastState="ready"
                    if(firstGeometry==null)firstGeometry=geometry
                    else check(firstGeometry==geometry) {"Exact AX geometry/readable semantics changed between ready samples: $tag"}
                    readySamples.add(samples)
                    if(readySamples.size==2) {
                        composeAnchor();assertEntry(entry);finished=withinDeadline();stage="complete";passed=true;return
                    }
                } else {readySamples.clear();firstGeometry=null}
                stage="read-only-sample-interval";withinDeadline()
                Thread.sleep(maxOf(1L,minOf(100L,deadline-android.os.SystemClock.elapsedRealtime())))
            }
        } catch(error: Throwable) {
            failure=error;if(!timedOut)lastState="fatal"
            // The exact AX assertion has already failed. Diagnostic observations cannot recover it.
            try {nativeAxFailureDiagnostic(binding,tag,anchor,windowId,lastCompleteSample,error)}
            catch(record: Throwable) {if(record!==error&&error.suppressed.none {it===record})error.addSuppressed(record)}
            throw error
        }
        finally {
            // This is saved by the existing owner-finalizer on both success and failure. No tree/value/label/credential data.
            val diagnostic=JSONObject().put("schema","p8-native-ax-readiness-v3").put("field",tag)
                .put("windowBindingId",binding.id).put("expectedAnchor",anchor?:JSONObject.NULL).put("windowId",windowId?:JSONObject.NULL)
                .put("samples",samples).put("freshRootRequests",rootRequests).put("matchedSamples",readySamples.size)
                .put("readySamplesTotal",readyTotal).put("readySampleNumbers",JSONArray(readySamples))
                .put("visitedNodes",visited).put("idExtraNodes",idExtras).put("anchorCount",matched)
                .put("pendingCounts",JSONObject(pendingCounts)).put("lastState",lastState).put("stage",stage)
                .put("timeout",timedOut).put("passed",passed).put("elapsedMs",(finished?:android.os.SystemClock.elapsedRealtime())-started)
                .put("nodeCap",2048).put("sampleLimit",64).put("deadlineMs",5000).put("intervalMs",100)
            try {owner.nativeAxReadiness.put(diagnostic)}catch(record: Throwable) {
                if(failure==null)throw record else if(failure!==record)failure.addSuppressed(record)
            }
        }
    }
    private fun awaitImeHidden(entry: Entry,binding: OwnedWindow) {
        check(entry===binding.entry)
        ui.waitUntil(10000) {
            var hidden=false
            windowOwned(binding){main {hidden=binding.decor.rootWindowInsets?.isVisible(android.view.WindowInsets.Type.ime())==false}}
            hidden
        }
    }
    private fun savedName(entry: Entry,id: String,expected: String) {
        awaitStore(entry.storage)
        val rows=checkNotNull(entry.storage.metadata()).getJSONArray("clients")
        assertEquals(expected,(0 until rows.length()).map {rows.getJSONObject(it)}.single {it.getString("id")==id}.getString("name"))
        owned(entry){ui.runOnIdle {assertEquals(expected,entry.session.clients.single {it.id==id}.name)}}
    }
    private fun shortViewport(entry: Entry): JSONObject {
        var facts: JSONObject?=null
        owned(entry){checkNotNull(entry.activity?.scenario).onActivity {activity->
            check(activity===entry.instance?.activity)
            val config=activity.resources.configuration
            assertEquals(2f,config.fontScale,0f)
            assertEquals(android.content.res.Configuration.ORIENTATION_LANDSCAPE,config.orientation)
            assertTrue("A new Root-admitted run requires an actually short owned display",config.screenHeightDp in 180..400&&config.screenWidthDp>config.screenHeightDp)
            facts=JSONObject().put("fontScale",config.fontScale).put("orientation","landscape")
                .put("windowWidthDp",config.screenWidthDp).put("windowHeightDp",config.screenHeightDp)
        }}
        // Observe the real ModalBottomSheet window, rather than treating resource qualifiers as its size.
        val sheet=owned(entry){ui.onNodeWithTag("sheet-add").assertIsDisplayed().fetchSemanticsNode().boundsInWindow}
        val sheetHeight=sheet.height/owned(entry){ui.density.density}
        assertTrue("Actual sheet must be short",sheetHeight in 120f..400f)
        val page=owned(entry){ui.onNodeWithTag("connection-page").fetchSemanticsNode()}
        assertTrue("Actual form owns vertical scrolling",page.config.contains(androidx.compose.ui.semantics.SemanticsActions.ScrollBy))
        return checkNotNull(facts).put("sheetHeightDp",sheetHeight)
    }
    private fun longFields(entry: Entry,client: MobileClient,scope: TargetScope,renamed: String,english: Boolean): JSONObject {
        val facts=shortViewport(entry)
        val firstInputBudget=InputBudget() // Starts before binding and every whole preparation for this input.
        completeInput("easywork-name",client.name,if(english)"Name" else "名称")
        val binding=bindOwnedWindow(entry,firstInputBudget)
        fieldInput(binding,"name-replacement",firstInputBudget) {gate->
            val node=whole("easywork-name");gate();node.performTextReplacement(renamed)
        }
        fieldInput(binding,"name-ime") {gate->
            val node=ui.onNodeWithTag("easywork-name");gate();node.performImeAction()
        };awaitImeHidden(entry,binding)
        completeInput("easywork-name",renamed,if(english)"Name" else "名称");savedName(entry,client.id,renamed)
        nativeAxComplete(binding,"easywork-name",renamed,if(english)"Name" else "名称")
        // The layout may wrap a label/value; its semantics must keep every original character.
        val values=listOf("agent" to scope.agentId,"authority" to scope.accountAuthority.orEmpty(),"subject" to scope.accountSubject.orEmpty(),
            "host" to scope.hostId,"workspace" to scope.workspaceId,"runtime" to scope.runtimeId)
        val labels=if(english)listOf("Agent clients","Account authority","Account","Computer","Workspace","Runtime")
            else listOf("Agent 客户端","账号发行方","账号","电脑","工作区","运行时")
        values.forEachIndexed {index,(id,value)->
            owned {whole("easywork-target-label-$id",true).assertTextEquals(labels[index])}
            fullTargetLayout("easywork-target-label-$id",labels[index])
            nativeAxComplete(binding,"easywork-target-label-$id",labels[index])
            owned {whole("easywork-target-$id",true).assertTextEquals(value)}
            fullTargetLayout("easywork-target-$id",value,multiline=id!="agent")
            nativeAxComplete(binding,"easywork-target-$id",value)
        }
        owned {whole("easywork-connection-method").performClick()}
        owned {whole("easywork-method-手动连接").assertIsEnabled().assertHasClickAction().performClick()}
        val address="https://owned-address.main-ui.invalid/"+"long-address-segment-".repeat(8)+"TAIL-%25-END"
        fieldInput(binding,"address-replacement") {gate->
            val node=whole("easywork-manual-address");gate();node.performTextReplacement(address)
        }
        fieldInput(binding,"address-ime") {gate->
            val node=ui.onNodeWithTag("easywork-manual-address");gate();node.performImeAction()
        };awaitImeHidden(entry,binding)
        completeInput("easywork-manual-address",address,if(english)"Computer address" else "电脑地址")
        nativeAxComplete(binding,"easywork-manual-address",address,if(english)"Computer address" else "电脑地址")
        owned {whole("easywork-manual-connect").assertIsEnabled().assertHasClickAction()} // Never submit.
        owned {whole("connection-preference").performClick()};options("automatic",english)
        owned {instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)};owned {ui.waitForIdle()}
        listOf("automatic","lan-only","relay-only").forEach {owned {ui.onNodeWithTag("connection-preference-$it").assertDoesNotExist()}}
        owned {ui.onNodeWithTag("connection-page").assertIsDisplayed()}
        // A second native Back dismisses the sheet. No target or draft is re-submitted.
        owned {instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)};owned {ui.waitForIdle()}
        owned {ui.onNodeWithTag("connection-page").assertDoesNotExist()};owned {ui.onNodeWithTag("client-selector").assertIsDisplayed()}
        savedName(entry,client.id,renamed)
        check(entry.owner.nativeAxReadiness.length()==nativeAxFields.size&&nativeAxFields.size==14)
        return facts.put("longNameLength",renamed.length).put("manualAddressLength",address.length).put("longTargetFields",values.size)
            .put("nameAutosaved",true).put("targetValuesExact",true).put("manualAddressSemanticsExact",true)
            .put("scrollReachedRuntime",true).put("popupBackDismissed",true).put("sheetBackDismissed",true)
            .put("composeFontScale",2).put("multilineTargetFields",5).put("noTargetEllipsis",true)
            .put("nativeAxReadback",true).put("nativeAxFields",entry.owner.nativeAxReadiness.length()).put("manualAddressSubmitted",false).put("talkBackExecuted",false)
    }
    private fun options(expected: String,english: Boolean) {
        val labels=if(english)mapOf("automatic" to "Automatic","lan-only" to "Direct only","relay-only" to "Relay only")
            else mapOf("automatic" to "自动","lan-only" to "仅直连","relay-only" to "仅中继")
        labels.forEach {(value,label)->
            owned {
                val row=whole("connection-preference-$value").assertIsEnabled().assertHasClickAction().assertTextContains(label)
                if(value==expected)row.assertIsSelected()else row.assertIsNotSelected()
            }
        }
    }
    @Test fun savedPreferenceThroughMainSubclassMenuPersistsWithoutNativeRuntime() {
        // Admission precedes every owned fixture/KeyStore/Session/Main acquisition.
        admission=NativeP8MainAdmission.admit()
        prepareOriginalAutomation()
        val cases=JSONArray()
        for(index in 0..1) {
            val row=admission.case(index)
            cases.put(runCase(row.getString("run"),row.getString("language"),row.getString("appearance")))
        }
        admission.finish(cases)
    }
    private fun runCase(run: String,language: String,appearance: String): JSONObject {
        check(UUID.fromString(run).toString()==run)
        val owner=CaseOwners(run);val errors=Failures()
        val seedPorts=P8MainDenyPorts();lateinit var seed: MobileSession
        val longPart="owned-long-field-".repeat(8)+"TAIL-END"
        val scope=TargetScope("easywork","https://issuer.main-ui.invalid/$longPart","synthetic-account-$run-$longPart","synthetic-host-$run-$longPart","synthetic-workspace-$run-$longPart","synthetic-runtime-$run-$longPart")
        val connection=AgentConnection(scope,listOf(AgentCandidate("direct","lan-wss","https://192.0.2.6:7443","a".repeat(64)),AgentCandidate("relay","relay-http","https://relay.main-ui.invalid")),"synthetic-ref-$run")
        val client=MobileClient("p8-main-$run","Saved computer 中文 %@ "+"long-name-".repeat(10)+"TAIL-END",scope,connection.candidates.first().endpoint,false,"easywork-runtime",false,requiresReadTarget=true,agentConnection=connection)
        owner.expectedClient=client
        val originalDraft="Owned Main menu draft 中文 😀 %@";val credentialName="easywork-credential:synthetic-ref-$run"
        val credential="Inert synthetic credential; never parse or send";val sentinelName="p8-owned-cache-sentinel";val sentinel="Synthetic local cache sentinel"
        var credentialBefore="";var journalName="";var journalBefore="";var selectedBefore="";var draftMapBefore="";var workspacesBefore=""
        val renamedName=client.name+" · renamed in short viewport"
        check(renamedName.length in 128..256)
        var stress: JSONObject?=null
        var bodyPassed=false;var completeInstanceSet=false;var activityOwnership: JSONObject?=null
        try {
            owner.ledger.step("acquisition-status",errors){admission.emit("before-scope-acquisition",JSONObject()
                .put("ownedRun",run).put("expectedOwnedScope",java.io.File(actual.noBackupFilesDir,"p8-main-preference-$run").path)
                .put("expectedOwnedAlias","p8-main-preference-$run").put("pid",owner.pid).put("processNonce",owner.nonce))};errors.throwIfAny()
            owner.ledger.step("scope-created",errors){owner.context=P8MainFilesContext.create(actual,run)};errors.throwIfAny()
            val context=checkNotNull(owner.context)
            owner.ledger.step("scope-pinned",errors){owner.fence=P8MainScopeBoundary(actual,context);owner.fence!!.pin()};errors.throwIfAny()
            owner.ledger.step("created-status",errors){status("scope-created-before-key-and-Main",context)};errors.throwIfAny()
            owner.ledger.step("keys-open",errors){owner.keys=KeyStore.getInstance("AndroidKeyStore");owner.keys!!.load(null)};errors.throwIfAny()
            owner.ledger.step("alias-new",errors){check(!owner.keys!!.containsAlias(context.alias)) {"Exact UUID alias already exists; preserve it"}};errors.throwIfAny()
            owner.ledger.step("store-created",errors){owner.storage=NativeSessionStorage(context,"session",context.alias)};errors.throwIfAny()
            val store=checkNotNull(owner.storage)
            owner.ledger.step("seed-created",errors){main {
                seed=MobileSession(store);owner.entries.add(Entry(owner,seed,store,context,null,seedPorts))
                seed.resetAcceptance(seedPorts,seedPorts,acceptanceEasyWorkPorts=seedPorts to seedPorts);seed.leaveForeground()
                // A saved Demo selection is valid only while Demo mode is enabled; use the product API.
                addOwnedClient(seed,client);seed.setDemoMode(true);seed.text=originalDraft;seed.changeLanguage(language);seed.changeAppearance(appearance)
                // This real product save API owns the preference and metadata persistence.
                seed.setEasyWorkPreference(client,"automatic")
            }};errors.throwIfAny()
            store.write(credentialName,credential);store.write(sentinelName,sentinel)
            val journal=NativeOperationJournal(store)
            val record=runBlocking {val prepared=journal.prepare(client,"owned-menu-thread","reply",ReplyIntent(client.id,"owned-menu-thread",UUID.randomUUID().toString(),"Owned synthetic immutable unknown input",emptyList(),"next-turn").body());journal.markSubmitting(prepared.target,prepared.id);journal.uncertain(prepared.target,prepared.id,"Synthetic unknown acknowledgement")}
            journalName="operations:"+record.target;journalBefore=checkNotNull(store.read(journalName));credentialBefore=checkNotNull(store.read(credentialName))
            awaitStore(store)
            val before=checkNotNull(store.metadata());selectedBefore=before.getString("selected");draftMapBefore=before.getJSONObject("drafts").toString();workspacesBefore=before.getJSONObject("workspaces").toString()
            closeTracked(owner.entries.last())
            var entry=launch(owner)
            fun assertProtectedOriginals() {
                awaitStore(entry.storage)
                val metadata=checkNotNull(entry.storage.metadata())
                assertTrue(metadata.getBoolean("demo"));assertEquals(selectedBefore,metadata.getString("selected"));assertEquals(draftMapBefore,metadata.getJSONObject("drafts").toString());assertEquals(workspacesBefore,metadata.getJSONObject("workspaces").toString())
                assertEquals(credentialBefore,entry.storage.read(credentialName));assertEquals(journalBefore,entry.storage.read(journalName));assertEquals(sentinel,entry.storage.read(sentinelName))
                owned(entry){ui.runOnIdle {
                    assertTrue(entry.session.demo);assertTrue(entry.session.selectedClient.isDemo);assertEquals(selectedBefore,entry.session.selectedClient.id);assertEquals(originalDraft,entry.session.text)
                    val live=entry.session.clients.single {it.id==client.id};assertEquals(connection.copy(preference=live.agentConnection!!.preference),live.agentConnection)
                    assertTrue(entry.session.operationRecords.isEmpty());assertNull(entry.session.chatNotifications)
                }}
                assertTrue(entry.context.denied.isEmpty());P8MainFilesContext.assertDefaultFamiliesAbsent(actual)
            }
            openEditor(client.id)
            stress=longFields(entry,client,scope,renamedName,language=="en");assertProtectedOriginals()
            openEditor(client.id);owned {whole("connection-preference").performClick()};options("automatic",language=="en")
            for(next in listOf("lan-only","relay-only","automatic","relay-only")) {
                owned {ui.onNodeWithTag("connection-preference-$next").performClick()}
                listOf("automatic","lan-only","relay-only").forEach {owned {ui.onNodeWithTag("connection-preference-$it").assertDoesNotExist()}}
                assertEquals(next,preference(entry.storage,client.id));assertProtectedOriginals()
                owned {ui.onNodeWithTag("cancel-client-form").performClick()};owned {ui.onNodeWithTag("edit-client-${client.id}").performScrollTo().performClick()}
                owned {ui.onNodeWithTag("connection-preference").performScrollTo().performClick()};options(next,language=="en")
            }
            closeTracked(entry)
            // New Activity + new session/store handle; real restore reads the encrypted saved choice.
            entry=launch(owner);assertEquals("relay-only",preference(entry.storage,client.id));assertProtectedOriginals()
            owned {ui.runOnIdle {assertEquals(language,entry.session.language);assertEquals(appearance,entry.session.appearance)}}
            savedName(entry,client.id,renamedName)
            openEditor(client.id);shortViewport(entry)
            completeInput("easywork-name",renamedName,if(language=="en")"Name" else "名称")
            owned {whole("connection-preference").performClick()};options("relay-only",language=="en")
            stress!!.put("nameRestoredInNewActivity",true)
            closeTracked(entry);bodyPassed=true
        } catch(error: Throwable) {errors.add(error)} finally {
            // Observe all acquisition records before any cleanup, including launch/preparation failure.
            // UNKNOWN steps keep their original outcome and are never re-entered.
            currentEntry=null
            errors.capture {captureActivityEntries(owner)}
            owner.activities.forEach {closeActivity(it,errors)}
            owner.entries.forEach {cleanupEntry(it,errors)}
            val acquisitionsKnown=owner.ledger.confirmed("scope-created","scope-pinned","keys-open","alias-new","store-created","seed-created")&&
                owner.activities.all {it.ledger.confirmed("instance-prepared","launch-returned","owner-captured")}
            errors.capture {main {
                val tickets=owner.activities.mapNotNull {it.ticket}
                completeInstanceSet=P8MainActivityOwners.complete(tickets)
                activityOwnership=JSONObject().put("schema","p8-finite-activity-ownership-v1")
                    .put("launches",owner.activities.size).put("activities",tickets.sumOf {it.instances.size})
                    .put("bindings",tickets.sumOf {ticket->ticket.instances.sumOf {it.bindings.size}}).put("entries",owner.entries.size)
                    .put("completeInstanceSet",completeInstanceSet).put("allActivitiesClosed",owner.activities.all {it.closed})
            }}
            val ownersClosed=acquisitionsKnown&&completeInstanceSet&&owner.activities.all {it.closed}&&owner.entries.all {it.released}&&!P8MainMarkerRead.hasUnknown(run)
            if(!ownersClosed)errors.add(IllegalStateException("Unconfirmed owner retained to original PID ${owner.pid}/${owner.nonce}; no retry"))
            owner.ledger.step("final-defaults",errors,ready=ownersClosed){P8MainFilesContext.assertDefaultFamiliesAbsent(actual)}
            owner.ledger.step("final-status",errors,ready=owner.context!=null){status("owners-confirmed-or-retained-before-scope-cleanup",owner.context!!,
                JSONObject().put("ownerLedger",JSONObject(owner.ledger.summary())).put("entryLedgers",JSONArray(owner.entries.map {JSONObject(it.ledger.summary())}))
                    .put("activityLedgers",JSONArray(owner.activities.map {JSONObject(it.ledger.summary())}))
                    .put("activityInstances",activityProof(owner))
                    .put("completeInstanceSet",completeInstanceSet).put("acquisitionsKnown",acquisitionsKnown).put("ownersClosed",ownersClosed)
                    .put("stableFinalDefaultFamiliesConfirmed",owner.ledger.confirmed("final-defaults")).put("bodyPassed",bodyPassed)
                    .put("nativeAxReadiness",owner.nativeAxReadiness).put("nativeAxPreparation",checkNotNull(automationPreparation))
                    .put("nativeWindowBindings",JSONArray(owner.nativeWindows.map {it.proof}))
                    .also {details->
                        owner.windowOwnerFailure?.let {details.put("nativeWindowOwnerFailure",it)}
                        owner.windowBindingFailure?.let {details.put("nativeWindowBindingFailure",it)}
                    }.toString())}
            val deletionReady=bodyPassed&&errors.first==null&&ownersClosed&&owner.ledger.confirmed("scope-created","scope-pinned","alias-new","store-created","seed-created","final-defaults","final-status")
            owner.ledger.step("scope-removed",errors,ready=deletionReady){owner.fence!!.removeConfirmedOwnedScope {facts->
                status("complete-bounded-scope-inventory-before-delete",owner.context!!,JSONObject(facts).toString())
            }}
            owner.ledger.step("alias-removed",errors,ready=owner.ledger.confirmed("scope-removed","alias-new")){
                owner.keys!!.deleteEntry(owner.context!!.alias);check(!owner.keys!!.containsAlias(owner.context!!.alias))
            }
            if(!owner.ledger.confirmed("scope-removed","alias-removed")) {
                retainedUntilProcessExit.add(owner)
                owner.ledger.step("retained-status",errors,ready=owner.context!=null){status("retained-to-original-process-exit",owner.context!!,
                    JSONObject().put("originalPid",owner.pid).put("originalNonce",owner.nonce).put("ownerLedger",JSONObject(owner.ledger.summary()))
                        .put("entryLedgers",JSONArray(owner.entries.map {JSONObject(it.ledger.summary())}))
                        .put("activityInstances",activityProof(owner))
                        .put("completeInstanceSet",completeInstanceSet).put("scopeReads",owner.fence?.let {JSONObject(it.retentionSummary())}).put("markerReadUnknowns",JSONArray(P8MainMarkerRead.retainedSummary(run)))
                        .put("stableFinalDefaultFamiliesConfirmed",owner.ledger.confirmed("final-defaults")).toString())}
            }
        }
        errors.throwIfAny()
        check(bodyPassed&&owner.ledger.confirmed("final-defaults","scope-removed","alias-removed")&&!P8MainMarkerRead.hasUnknown(run))
        return JSONObject().put("run",run).put("language",language).put("appearance",appearance)
            .put("choices",JSONArray(listOf("lan-only","relay-only","automatic","relay-only")))
            .put("restoredPreference","relay-only").put("stress",checkNotNull(stress)).put("activityOwnership",checkNotNull(activityOwnership)).put("protectedOriginalsUnchanged",true)
            .put("stableFinalDefaultsConfirmed",true).put("scopeRemoved",true).put("aliasRemoved",true)
    }
}
