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
    /** Read-only platform AX join: exact current app root + original Compose semantics ID + tag.
     * No accessibility focus/action or TalkBack service is simulated. Missing native data fails. */
    private fun nativeAxComplete(tag: String,value: String,label: String?=null) {
        val anchor=owned {ui.onNodeWithTag(tag).fetchSemanticsNode().id}
        val idKey="androidx.compose.ui.semantics.id";val tagKey="androidx.compose.ui.semantics.testTag"
        var retained: android.view.accessibility.AccessibilityNodeInfo?=null
        ui.waitUntil(5000) {
            val root=axNode {instrumentation.uiAutomation.rootInActiveWindow}
            if(root==null)false else {
                var keep=false
                try {
                    if(!owned {root.packageName?.toString()==actual.packageName})false
                    else {retained=root;keep=true;true}
                } finally {if(!keep){@Suppress("DEPRECATION") root.recycle()}}
            }
        }
        val root=checkNotNull(retained);var matched=0;var visited=0
        try {
            owned {check(root.refresh()&&root.packageName?.toString()==actual.packageName)}
            val windowId=owned {root.windowId};val windowBounds=android.graphics.Rect();owned {root.getBoundsInScreen(windowBounds)}
            fun own(node: android.view.accessibility.AccessibilityNodeInfo)=owned {node.packageName?.toString()==actual.packageName&&node.windowId==windowId}
            fun nativeId(node: android.view.accessibility.AccessibilityNodeInfo): Int? {
                owned {check(own(node)&&node.refresh()&&own(node))}
                if(!owned {node.availableExtraData.contains(idKey)})return null
                owned {check(node.refreshWithExtraData(idKey,android.os.Bundle())&&own(node)&&node.extras.containsKey(idKey))}
                return owned {node.extras.getInt(idKey)}
            }
            fun visit(node: android.view.accessibility.AccessibilityNodeInfo) {
                check(++visited<=2048&&own(node));val id=nativeId(node)
                if(id==anchor) {
                    matched++;owned {check(node.availableExtraData.contains(tagKey))}
                    owned {check(node.refreshWithExtraData(tagKey,android.os.Bundle())&&own(node)&&node.extras.getCharSequence(tagKey)?.toString()==tag)}
                    owned {assertEquals(value,node.text?.toString());assertTrue(node.isVisibleToUser&&node.isEnabled)}
                    owned {assertTrue("Native AX speaks or edits this original value: $tag",node.isScreenReaderFocusable||node.isFocusable||node.isEditable)}
                    val bounds=android.graphics.Rect();owned {node.getBoundsInScreen(bounds)}
                    assertTrue("Complete native field bounds inside active app window: $tag",!bounds.isEmpty&&windowBounds.contains(bounds))
                    if(label!=null) {
                        owned {assertTrue(node.isEditable)}
                        var associated=owned {if(node.contentDescription?.toString()==label)1 else 0}
                        for(k in 0 until (owned {node.childCount})) {
                            val child=axNode {node.getChild(k)}?:continue
                            try {if(owned {own(child)&&child.contentDescription?.toString()==label&&nativeId(child)?.toLong()==anchor.toLong()+2_000_000_000L})associated++}
                            finally {@Suppress("DEPRECATION") child.recycle()}
                        }
                        assertEquals("Complete native label belongs to exact input: $tag",1,associated)
                    }
                }
                for(k in 0 until (owned {node.childCount})) {
                    val child=axNode {node.getChild(k)}?:continue
                    try {visit(child)}finally {@Suppress("DEPRECATION") child.recycle()}
                }
            }
            visit(root);assertEquals("One exact native AX anchor: $tag",1,matched)
            owned {assertEquals("Compose anchor stayed unchanged",anchor,ui.onNodeWithTag(tag).fetchSemanticsNode().id)}
            owned {check(root.refresh()&&root.packageName?.toString()==actual.packageName&&root.windowId==windowId)}
        } finally {@Suppress("DEPRECATION") root.recycle()}
    }
    private fun awaitImeHidden(entry: Entry) {
        ui.waitUntil(10000) {
            var hidden=false
            owned(entry){checkNotNull(entry.activity?.scenario).onActivity {activity->
                check(activity===entry.instance?.activity)
                hidden=activity.window.decorView.rootWindowInsets?.isVisible(android.view.WindowInsets.Type.ime())==false
            }}
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
        completeInput("easywork-name",client.name,if(english)"Name" else "名称")
        owned {whole("easywork-name").performTextReplacement(renamed)}
        owned {ui.onNodeWithTag("easywork-name").performImeAction()};awaitImeHidden(entry)
        completeInput("easywork-name",renamed,if(english)"Name" else "名称");savedName(entry,client.id,renamed)
        nativeAxComplete("easywork-name",renamed,if(english)"Name" else "名称")
        // The layout may wrap a label/value; its semantics must keep every original character.
        val values=listOf("agent" to scope.agentId,"authority" to scope.accountAuthority.orEmpty(),"subject" to scope.accountSubject.orEmpty(),
            "host" to scope.hostId,"workspace" to scope.workspaceId,"runtime" to scope.runtimeId)
        val labels=if(english)listOf("Agent clients","Account authority","Account","Computer","Workspace","Runtime")
            else listOf("Agent 客户端","账号发行方","账号","电脑","工作区","运行时")
        values.forEachIndexed {index,(id,value)->
            owned {whole("easywork-target-label-$id",true).assertTextEquals(labels[index])}
            fullTargetLayout("easywork-target-label-$id",labels[index])
            nativeAxComplete("easywork-target-label-$id",labels[index])
            owned {whole("easywork-target-$id",true).assertTextEquals(value)}
            fullTargetLayout("easywork-target-$id",value,multiline=id!="agent")
            nativeAxComplete("easywork-target-$id",value)
        }
        owned {whole("easywork-connection-method").performClick()}
        owned {whole("easywork-method-手动连接").assertIsEnabled().assertHasClickAction().performClick()}
        val address="https://owned-address.main-ui.invalid/"+"long-address-segment-".repeat(8)+"TAIL-%25-END"
        owned {whole("easywork-manual-address").performTextReplacement(address)}
        owned {ui.onNodeWithTag("easywork-manual-address").performImeAction()};awaitImeHidden(entry)
        completeInput("easywork-manual-address",address,if(english)"Computer address" else "电脑地址")
        nativeAxComplete("easywork-manual-address",address,if(english)"Computer address" else "电脑地址")
        owned {whole("easywork-manual-connect").assertIsEnabled().assertHasClickAction()} // Never submit.
        owned {whole("connection-preference").performClick()};options("automatic",english)
        owned {instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)};owned {ui.waitForIdle()}
        listOf("automatic","lan-only","relay-only").forEach {owned {ui.onNodeWithTag("connection-preference-$it").assertDoesNotExist()}}
        owned {ui.onNodeWithTag("connection-page").assertIsDisplayed()}
        // A second native Back dismisses the sheet. No target or draft is re-submitted.
        owned {instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)};owned {ui.waitForIdle()}
        owned {ui.onNodeWithTag("connection-page").assertDoesNotExist()};owned {ui.onNodeWithTag("client-selector").assertIsDisplayed()}
        savedName(entry,client.id,renamed)
        return facts.put("longNameLength",renamed.length).put("manualAddressLength",address.length).put("longTargetFields",values.size)
            .put("nameAutosaved",true).put("targetValuesExact",true).put("manualAddressSemanticsExact",true)
            .put("scrollReachedRuntime",true).put("popupBackDismissed",true).put("sheetBackDismissed",true)
            .put("composeFontScale",2).put("multilineTargetFields",5).put("noTargetEllipsis",true)
            .put("nativeAxReadback",true).put("nativeAxFields",14).put("manualAddressSubmitted",false).put("talkBackExecuted",false)
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
                    .put("stableFinalDefaultFamiliesConfirmed",owner.ledger.confirmed("final-defaults")).put("bodyPassed",bodyPassed).toString())}
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
