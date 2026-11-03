package com.agenthub.android

import android.content.Context
import android.content.ContextWrapper
import android.os.Bundle
import android.os.SystemClock
import android.graphics.Rect
import android.view.KeyEvent
import android.view.KeyCharacterMap
import android.view.InputDevice
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.agenthub.core.TargetScope
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.TestName
import org.junit.rules.TestRule
import org.junit.runners.model.Statement
import java.io.File
import java.nio.file.Files
import java.util.UUID

/** Inherited Main lifecycle, original sheet/controller/system host, fresh private installation.
 * No setContent, adopted/default session, fabricated preview/result, registry result or remote Host.
 * Prepared-candidate cases are deliberately NOT SAF selection/save evidence. Run each method in a
 * fresh process with a new migrationInboxRun UUID; existing owner/router fences remain authoritative.
 */
class NativeMigrationMainUiTest {
    @get:Rule(order=0) val name=TestName()
    @get:Rule(order=1) val privateLaunch=TestRule { base,_ -> object: Statement() {
        override fun evaluate()=ownedCase(base)
    }}
    private suspend fun prepare() {
        fixture=MigrationInboxFixture();fixture.fresh();folderOwned=true
        val base=fixture.context("source")
        val cache=File(fixture.folder,"main-ui-cache").apply {check(mkdir());android.system.Os.chmod(path,0x1c0)}
        context=object:ContextWrapper(base) {
            override fun getApplicationContext(): Context=this
            override fun getCacheDir()=cache
        }
        storageAcquisitionStarted=true
        storage=NativeSessionStorage(context,"installation",fixture.alias("source"))
        val blocked=when(name.methodName) {
            "actualMainEntryPasswordAndCloseRoutesKeepThePrivateOwner"->false
            "actualMainExistingConnectionDisablesImportWithoutRemovingIt"->true
            "actualMainRecoversEncryptedCandidateAndCancelReopenKeepIt"->false
            "actualMainConfirmPublishesPreparedCandidateAndRestoresOnlyOnce"->false
            else->error("Only an explicit finite Main migration UI selector is allowed")
        }
        val original=metadata("original owned local draft")
        if(blocked)original.getJSONArray("clients").put(encodeClient(MobileClient(
            "owned-main-ui-${fixture.run}","Owned offline connection",TargetScope("codex",null,null,
                "owned-main-ui-${fixture.run}","owned-workspace-${fixture.run}","owned-runtime-${fixture.run}"),
            "https://127.0.0.1:1",false,"history",false)))
        originalMetadata=original.toString();storage.write("metadata",originalMetadata)
        if(name.methodName in setOf("actualMainRecoversEncryptedCandidateAndCancelReopenKeepIt",
                "actualMainConfirmPublishesPreparedCandidateAndRestoresOnlyOnce")) {
            incomingMetadata=metadata("imported owned local draft").toString()
            val envelope=JSONObject().put("format","agent-hub/android-migration").put("version",1)
                .put("applicationId",context.packageName).put("records",JSONObject().put("metadata",incomingMetadata))
            // Real encrypted candidate parsing/validation before launch, never UI state assignment.
            prepared=NativeMigrationCandidateStore(context,storage).stage(UUID.randomUUID().toString(),
                NativeMigrationCrypto.seal(envelope.toString().toByteArray(),fixture.password),fixture.password)
        }
        acquisitionStarted=true
        initial=MobileSession(storage) // Register immediately on constructor return, before any restore.
        withContext(Dispatchers.Main.immediate) {
            configureOwnedSession(initial)
            initial.restore()
        }
        withContext(Dispatchers.Main.immediate) {
            withTimeout(30_000){while(initial.restoring||!initial.phoneReading.ready)delay(20)}
        }
    }
    @get:Rule(order=2) val ui=createAndroidComposeRule<NativeMigrationInboxOwnerActivity>()
    private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
    private class ConfiguredOwner(val session: MobileSession,val deny: P8MainDenyPorts) {
        var configured=false
    }
    private val owners=mutableListOf<ConfiguredOwner>()
    private val axWaits=JSONArray()
    private var nativeBackProof: JSONObject?=null
    private var folderOwned=false
    private var storageAcquisitionStarted=false
    private var storageWriterRetired=false
    private var acquisitionStarted=false
    private var installationPrepared=false
    private var installationReleased=false
    private var activityAcquired=false
    private var activityClosed=false
    private var cleanupAttempted=false
    private var postJoinProviderCheckPassed=false
    private var retiredOwner: MobileSession?=null
    private var mainTransferredOriginal=false
    private var stage="ADMISSION"
    private data class PageScope(val session: MobileSession,val controller: NativeMigrationController,val storage: String,val target: String,val ordinal: Int)
    private var page: PageScope?=null
    private var pageOrdinal=0
    private lateinit var fixture: MigrationInboxFixture
    private lateinit var context: Context
    private lateinit var storage: NativeSessionStorage
    private lateinit var initial: MobileSession
    private lateinit var originalMetadata: String
    private var incomingMetadata=""
    private var prepared: NativeMigrationCandidateStore.Receipt?=null
    private var completed=false
    private var retirementConfirmed=false
    private val draftKey get()="owned-main-ui-draft-${fixture.run}"
    private fun metadata(draft: String)=JSONObject().put("version",1).put("appearance","light").put("language","en")
        .put("demo",true).put("selected",DemoMobileData.clients.first().id).put("clients",JSONArray())
        .put("drafts",JSONObject().put(draftKey,draft)).put("draftVersions",JSONObject().put(draftKey,"owned-main-ui-version"))
        .put("draftFiles",JSONObject()).put("selectedThreads",JSONObject()).put("expanded",JSONObject()).put("workspaces",JSONObject())

    @Before fun ready() {
        // Framework application is unchanged; runtime installation/cache are isolated before Main.
        assertSame(fixture.app.applicationContext,ui.activity.applicationContext)
        ui.waitUntil(30_000){ui.activity.session.migrationReady}
        ui.runOnIdle {
            assertSame(initial,ui.activity.session)
            assertEquals(1,owners.size)
            assertEquals(storage.coordinationKey,ui.activity.session.migrationStorage.coordinationKey)
            assertTrue(ui.activity.session.selectedClient.isDemo)
        }
        assertTrue(File(context.cacheDir,"migration-ui-v1").isDirectory)
        assertEquals(File(fixture.folder,"main-ui-cache").canonicalFile,context.cacheDir.canonicalFile)
        assertEquals(File(fixture.folder,"source").canonicalFile,context.noBackupFilesDir.canonicalFile)
        assertEquals(fixture.app.filesDir.canonicalFile,context.filesDir.canonicalFile)
        assertEquals(File(fixture.folder,"source/installation").canonicalPath,storage.coordinationKey)
        // Main may save its ordinary startup metadata. Compare UI effects to the fully started
        // installation while independently checking the seeded draft/connection survived startup.
        runBlocking {storage.awaitQueuedWrites()}
        originalMetadata=checkNotNull(storage.read("metadata"))
        val baseline=JSONObject(originalMetadata)
        assertEquals("original owned local draft",baseline.getJSONObject("drafts").getString(draftKey))
        assertEquals(if(name.methodName=="actualMainExistingConnectionDisablesImportWithoutRemovingIt")1 else 0,
            baseline.getJSONArray("clients").length())
        assertNoPorts()
    }
    /** Each initial/fresh Session gets its own fixed deny registry BEFORE any restore. */
    private fun configureOwnedSession(value: MobileSession) {
        check(android.os.Looper.myLooper()==android.os.Looper.getMainLooper())
        val existing=owners.singleOrNull {it.session===value}
        if(existing!=null){check(existing.configured&&value.migrationStorage.coordinationKey==storage.coordinationKey);return} // Main revisits the prepared initial owner.
        val owned=ConfiguredOwner(value,P8MainDenyPorts());owners+=owned // Before the first throwing configuration/restore.
        check(value.migrationStorage.coordinationKey==storage.coordinationKey)
        value.resetAcceptance(owned.deny,owned.deny,acceptanceEasyWorkPorts=owned.deny to owned.deny)
        owned.configured=true
    }
    private fun assertDiscoveryAbsent(value: MobileSession) {
        assertNull(value.discovery);assertNull(value.easyWorkDiscovery)
        assertTrue(owners.single {it.session===value}.configured)
    }
    private fun assertNoPorts() {
        // Discovery is separately disabled at Main construction/start. These probes only cover
        // provider business/operation/receipt/model calls, for BOTH initial and fresh registries.
        check(owners.isNotEmpty())
        owners.forEach {owned->
            val allowWorkspace=owned.session===initial&&name.methodName=="actualMainExistingConnectionDisablesImportWithoutRemovingIt"
            assertTrue("Only the exact owned initial DENIED workspace read is allowed",owned.deny.snapshot().all {
                allowWorkspace&&it.method=="workspace"&&it.provider=="codex"&&it.clientId=="owned-main-ui-${fixture.run}"&&
                    it.target==TargetScope("codex",null,null,"owned-main-ui-${fixture.run}","owned-workspace-${fixture.run}","owned-runtime-${fixture.run}")
            })
            assertEquals(0,owned.deny.overflow.get())
            assertDiscoveryAbsent(owned.session)
        }
    }
    private fun openFromMain() {
        ui.onNodeWithTag("chat-menu").performClick()
        ui.onNodeWithTag("open-settings").performClick()
        openFromSettings()
    }
    private fun openFromSettings() {
        ui.onNodeWithTag("sheet-settings").assertExists()
        ui.onNodeWithTag("settings-migration").performScrollTo().performClick()
        ui.waitUntil(30_000){ui.activity.migration.presented&&ui.activity.migration.ready&&!ui.activity.migration.busy}
        ui.onNodeWithTag("sheet-migration").assertExists()
        ui.onNodeWithTag("migration-password").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password))
        ui.runOnIdle {assertTrue(ui.activity.migration.password.isEmpty())}
        bindPage()
        awaitPage("导出加密迁移文件",false,passwordLength=0)
    }
    private fun bindPage(expected: MobileSession?=null)=ui.runOnIdle {
        val value=ui.activity.session
        if(expected!=null)assertSame(expected,value)
        assertTrue(owners.any {it.session===value&&it.configured})
        assertEquals(storage.coordinationKey,value.migrationStorage.coordinationKey)
        page=PageScope(value,ui.activity.migration,value.migrationStorage.coordinationKey,value.targetKey,++pageOrdinal)
    }
    /** Scalar identity only; never inspect/dump password-bearing semantics or native text. */
    private fun pageState(scope: PageScope,expectedLength: Int?): JSONObject {
        var result: JSONObject?=null
        instrumentation.runOnMainSync {
            val activity=ui.activity;val current=activity.session;val controller=activity.migration
            val identity=current===scope.session&&controller===scope.controller&&
                current.migrationStorage.coordinationKey==scope.storage&&scope.storage==storage.coordinationKey&&
                current.targetKey==scope.target&&!activity.isDestroyed&&!activity.isFinishing
            check(identity){"Private migration page owner changed; no input sent"}
            assertDiscoveryAbsent(current)
            result=JSONObject().put("identityMatches",identity)
                .put("domainReady",controller.presented&&controller.ready&&!controller.busy&&controller.picker==null)
                .put("passwordLengthMatches",expectedLength==null||controller.password.length==expectedLength)
        }
        return checkNotNull(result)
    }
    private fun <T> control(label: AccessibilityNodeInfo,body: (AccessibilityNodeInfo)->T): T? {
        var current=label;val parents=mutableListOf<AccessibilityNodeInfo>()
        try {
            if(!current.refresh()||current.packageName?.toString()!=context.packageName)return null
            repeat(6){if(!current.isClickable)current.parent?.let {
                parents+=it;current=it
                if(!current.refresh()||current.packageName?.toString()!=context.packageName)return null
            }}
            if(!current.refresh()||current.packageName?.toString()!=context.packageName||!current.isVisibleToUser||!current.isClickable)return null
            return body(current)
        }finally {@Suppress("DEPRECATION") parents.forEach {it.recycle()}}
    }
    /** Reacquire/refresh the exact active App window and owned nodes until two matching samples.
     * Counts/public labels only; every acquired node/ancestor is recycled even on failure.
     */
    private fun awaitPage(copy: String?=null,enabled: Boolean?=null,passwordLength: Int?=null,
        requireField: Boolean=true,nativeAction: ((List<AccessibilityNodeInfo>)->Unit)?=null) {
        val scope=checkNotNull(page);val deadline=SystemClock.elapsedRealtime()+15_000
        val label=copy?.let {NativeL10n(context,"en").tr(it)}
        var samples=0;var stable=0;var matched=false;var actionAccepted=false;var last=JSONObject()
        try {
            while(SystemClock.elapsedRealtime()<deadline) {
                val state=pageState(scope,passwordLength);last=state
                val root=instrumentation.uiAutomation.rootInActiveWindow
                val nodes=mutableListOf<AccessibilityNodeInfo>()
                fun visit(node: AccessibilityNodeInfo,depth: Int) {
                    nodes+=node
                    check(depth<=40&&nodes.size<=2048){"Native accessibility bound exceeded"}
                    check(node.packageName?.toString()==context.packageName){"Unexpected native owner; no input sent"}
                    for(index in 0 until node.childCount)node.getChild(index)?.let {visit(it,depth+1)}
                }
                try {
                    if(root!=null&&root.refresh()) {
                        check(root.packageName?.toString()==context.packageName){"Unexpected active App window; no input sent"}
                        val bounds=Rect();root.getBoundsInScreen(bounds)
                        val windowReady=root.isVisibleToUser&&!bounds.isEmpty&&root.windowId>=0
                        visit(root,0)
                        val fresh=nodes.filter {it.refresh()}
                        check(fresh.all {it.packageName?.toString()==context.packageName})
                        val fields=fresh.filter {it.isEditable&&it.isPassword&&it.isVisibleToUser&&it.isEnabled}
                        // Short circuit excludes all editable/password text/contentDescription.
                        val labels=if(label==null)emptyList()else fresh.filter {!it.isEditable&&!it.isPassword&&it.isVisibleToUser&&it.text?.toString()==label}
                        val correct=labels.count {control(it){node->node.isEnabled==enabled}==true}
                        state.put("windowReady",windowReady).put("nodeCount",fresh.size)
                            .put("enabledVisiblePasswordFields",fields.size).put("publicLabelCount",labels.size).put("expectedEnabledLabelCount",correct)
                        val nativeReady=windowReady&&(!requireField||fields.size==1)&&(label==null||(labels.size==1&&correct==1))
                        samples++
                        stable=if(nativeReady&&state.getBoolean("domainReady")&&state.getBoolean("passwordLengthMatches"))stable+1 else 0
                        if(stable>=2) {
                            val current=pageState(scope,passwordLength)
                            check(current.getBoolean("domainReady")&&current.getBoolean("passwordLengthMatches")&&root.refresh()&&root.packageName?.toString()==context.packageName)
                            nativeAction?.invoke(fresh);actionAccepted=nativeAction!=null;matched=true
                        }
                    }else {stable=0;state.put("windowReady",false);samples++}
                }finally {
                    @Suppress("DEPRECATION") nodes.forEach {it.recycle()}
                    if(root!=null&&nodes.none {it===root}){@Suppress("DEPRECATION")root.recycle()}
                }
                if(matched)return
                ui.mainClock.advanceTimeByFrame();instrumentation.waitForIdleSync();SystemClock.sleep(40)
            }
            error("Owned migration AX controls did not stabilize; inspect scalar counts only")
        }finally {
            axWaits.put(JSONObject().put("page",scope.ordinal).put("knownActionLabel",label ?: JSONObject.NULL)
                .put("expectedEnabled",enabled ?: JSONObject.NULL).put("samples",samples).put("matched",matched)
                .put("nativeActionAccepted",actionAccepted).put("lastCountsAndState",last))
        }
    }
    /** Native password edit without value-bearing hierarchy, screenshots or logs. */
    private fun password(value: String) {
        awaitPage {all->
            val field=all.single {it.isEditable&&it.isPassword&&it.isVisibleToUser&&it.isEnabled}
            check(field.refresh()&&field.packageName?.toString()==context.packageName)
            check(field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,value)
            }))
        }
        awaitPage(passwordLength=value.length)
    }
    private fun actionEnabled(copy: String,expected: Boolean)=awaitPage(copy,expected)
    private fun clickAction(tag: String,copy: String,scroll: Boolean=false) {
        if(scroll)ui.onNodeWithTag(tag).performScrollTo()
        awaitPage(copy,true,requireField=false) {all->
            val label=NativeL10n(context,"en").tr(copy)
            val node=all.single {!it.isEditable&&!it.isPassword&&it.isVisibleToUser&&it.text?.toString()==label}
            check(node.refresh()&&node.text?.toString()==label)
            check(control(node){button->button.isEnabled&&button.performAction(AccessibilityNodeInfo.ACTION_CLICK)}==true)
        }
    }
    private fun unchanged()=runBlocking {
        withContext(Dispatchers.Main.immediate){assertSame(initial,ui.activity.session);assertEquals(1,owners.size)}
        storage.awaitQueuedWrites()
        assertEquals(originalMetadata,storage.read("metadata"))
        assertNull(storage.migrationPublicationReceipt())
        assertEquals(prepared,NativeMigrationCandidateStore(context,storage).currentReceipt())
        assertNoPorts()
    }
    private fun preparedPreview() {
        ui.waitUntil(30_000){ui.activity.migration.phase==NativeMigrationPhase.PREVIEW&&!ui.activity.migration.busy}
        ui.runOnIdle {
            assertEquals(NativeMigrationPreview(0,1,0,0,0),ui.activity.migration.preview)
            assertEquals(NativeMigrationResult.READY,ui.activity.migration.result)
            assertTrue(ui.activity.migration.password.isEmpty())
        }
        ui.onNodeWithTag("migration-preview").performScrollTo().assertExists()
        awaitPage("确认导入",true,passwordLength=0,requireField=false)
        ui.onNodeWithTag("migration-confirm").assertIsEnabled()
        unchanged()
    }
    /** One physical BACK pair; rejected or unknown input is never retried. */
    private fun nativeBack() {
        awaitPage("导出加密迁移文件",true,passwordLength=12)
        check(instrumentation.uiAutomation.rootInActiveWindow?.let {root->try {
            root.refresh()&&root.isVisibleToUser&&root.packageName?.toString()==context.packageName
        }finally {@Suppress("DEPRECATION")root.recycle()}}==true)
        val downTime=SystemClock.uptimeMillis()
        check(downTime>0)
        var downAccepted=false
        var upAccepted=false
        var upTime=downTime
        var failure: Throwable?=null
        try {
            downAccepted=instrumentation.uiAutomation.injectInputEvent(KeyEvent(downTime,downTime,
                KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_BACK,0,0,KeyCharacterMap.VIRTUAL_KEYBOARD,0,0,InputDevice.SOURCE_KEYBOARD),true)
            check(downAccepted){"Native BACK down rejected; no retry"}
        }catch(error: Throwable){failure=error}
        finally {
            // Release the same attempted key even if down rejected/threw; never send another down.
            upTime=SystemClock.uptimeMillis()
            try {
                check(upTime>=downTime)
                upAccepted=instrumentation.uiAutomation.injectInputEvent(KeyEvent(downTime,upTime,
                    KeyEvent.ACTION_UP,KeyEvent.KEYCODE_BACK,0,0,KeyCharacterMap.VIRTUAL_KEYBOARD,0,0,InputDevice.SOURCE_KEYBOARD),true)
                check(upAccepted){"Native BACK up rejected; no retry"}
            }catch(error: Throwable){if(failure==null)failure=error else failure?.addSuppressed(error)}
            nativeBackProof=JSONObject().put("downTime",downTime).put("downEventTime",downTime).put("upEventTime",upTime)
                .put("downAccepted",downAccepted).put("upAccepted",upAccepted).put("singleDownAndUp",true)
                .put("virtualKeyboard",true).put("sourceKeyboard",true).put("failure",failure?.javaClass?.simpleName ?: JSONObject.NULL)
        }
        failure?.let {throw it}
    }
    @Test fun actualMainEntryPasswordAndCloseRoutesKeepThePrivateOwner() {
        openFromMain()
        ui.onNodeWithTag("migration-export").assertIsNotEnabled()
        ui.onNodeWithTag("migration-import").assertIsNotEnabled()
        password("x".repeat(11));actionEnabled("导出加密迁移文件",false);actionEnabled("导入加密迁移文件",false)
        password("x".repeat(12));actionEnabled("导出加密迁移文件",true);actionEnabled("导入加密迁移文件",true)
        clickAction("appearance-back","设置")
        ui.onNodeWithTag("sheet-settings").assertExists()
        openFromSettings();password("x".repeat(12))
        clickAction("sheet-done","完成")
        ui.onNodeWithTag("sheet-migration").assertDoesNotExist();ui.onNodeWithTag("sheet-settings").assertDoesNotExist()
        ui.runOnIdle {assertFalse(ui.activity.migration.presented);assertTrue(ui.activity.migration.password.isEmpty());assertSame(initial,ui.activity.session)}
        openFromMain();password("x".repeat(12))
        clickAction("migration-cancel","取消",scroll=true)
        ui.onNodeWithTag("sheet-settings").assertExists()
        openFromSettings();password("y".repeat(12))
        actionEnabled("导出加密迁移文件",true)
        ui.runOnIdle {assertEquals(12,ui.activity.migration.password.length)}
        nativeBack()
        ui.waitUntil(10_000){!ui.activity.migration.presented}
        ui.onNodeWithTag("sheet-migration").assertDoesNotExist()
        ui.runOnIdle {assertTrue(ui.activity.migration.password.isEmpty());assertSame(initial,ui.activity.session)}
        openFromMain();awaitPage("导出加密迁移文件",false,passwordLength=0)
        clickAction("sheet-done","完成")
        unchanged();completed=true
    }
    @Test fun actualMainExistingConnectionDisablesImportWithoutRemovingIt() {
        openFromMain();password("x".repeat(12))
        actionEnabled("导出加密迁移文件",true);actionEnabled("导入加密迁移文件",false)
        ui.runOnIdle {assertTrue(ui.activity.session.migrationReady);assertFalse(ui.activity.session.migrationCanImport);assertFalse(ui.activity.migration.canImport);assertNull(ui.activity.migration.picker)}
        clickAction("appearance-back","设置")
        unchanged();assertEquals(1,JSONObject(storage.read("metadata")!!).getJSONArray("clients").length());completed=true
    }
    @Test fun actualMainRecoversEncryptedCandidateAndCancelReopenKeepIt() {
        openFromMain();preparedPreview()
        clickAction("migration-cancel","取消",scroll=true)
        ui.onNodeWithTag("sheet-settings").assertExists()
        unchanged();openFromSettings();preparedPreview()
        clickAction("sheet-done","完成");unchanged();completed=true
    }
    @Test fun actualMainConfirmPublishesPreparedCandidateAndRestoresOnlyOnce() {
        openFromMain();preparedPreview()
        clickAction("migration-confirm","确认导入",scroll=true)
        ui.waitUntil(60_000){ui.activity.migration.result==NativeMigrationResult.IMPORTED&&
            ui.activity.migration.phase==NativeMigrationPhase.DONE&&!ui.activity.migration.busy&&ui.activity.session.migrationReady}
        val fresh=ui.activity.session
        assertNotSame(initial,fresh)
        // Main constructs/restores this actual fresh Session; its guarded fixed fixture hook has
        // installed a separate deny registry BEFORE restore. Empty clients/vault are extra gates.
        assertEquals(2,owners.size);assertDiscoveryAbsent(fresh);assertNoPorts()
        assertTrue(fresh.clients.all {it.isDemo});assertTrue(fresh.selectedClient.isDemo)
        assertEquals(0,fresh.migrationStorage.metadata()!!.getJSONArray("clients").length())
        assertTrue(CodexConnectionVault(fresh.migrationStorage).clients().isEmpty())
        val receipt=runBlocking {withContext(Dispatchers.IO){fresh.migrationStorage.migrationPublicationReceipt()}}
        assertEquals(prepared,receipt)
        assertEquals("imported owned local draft",fresh.migrationStorage.metadata()!!.getJSONObject("drafts").getString(draftKey))
        bindPage(fresh) // Expected actual Main replacement; subsequent AX actions bind to this owner.
        ui.onNodeWithTag("migration-preview").assertDoesNotExist()
        ui.onNodeWithTag("migration-result").performScrollTo().assertTextContains(NativeL10n(context,"en").tr("连接、草稿和回执已恢复。通知需另行明确开启。"))
        clickAction("sheet-done","完成")
        openFromMain()
        ui.waitUntil(30_000){ui.activity.migration.result==NativeMigrationResult.PREVIOUS_IMPORT&&!ui.activity.migration.busy}
        ui.runOnIdle {assertSame(fresh,ui.activity.session);assertEquals(NativeMigrationResult.PREVIOUS_IMPORT,ui.activity.migration.result);assertNull(ui.activity.migration.preview)}
        assertEquals(receipt,fresh.migrationStorage.migrationPublicationReceipt())
        clickAction("sheet-done","完成");assertNoPorts();completed=true
    }
    /** Owns prepare + Activity launch + body + Activity-rule close. @After is deliberately absent. */
    private fun ownedCase(base: Statement) {
        var primary: Throwable?=null;var cleanupFailure: Throwable?=null
        try {
            val app=instrumentation.targetContext
            check(BuildConfig.DEBUG&&BuildConfig.APPLICATION_ID=="com.easyai.agenthub.android.acceptance"&&app.packageName==BuildConfig.APPLICATION_ID)
            instrumentation.runOnMainSync {
                val activities=Stage.values().filter {it!=Stage.DESTROYED}.flatMap {ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(it)}
                check(activities.none {it is MainActivity}) {"Existing Activity owner retained"}
                NativeChatNotificationColdStart.requireUnusedAcceptanceOwner(app)
            }
            runBlocking {withContext(Dispatchers.IO) {
                check(!NativeNaturalEntryFence.hasMaterial(app.noBackupFilesDir)){"Existing P9 natural entry retained"}
                check(Files.notExists(File(app.noBackupFilesDir,NativeChatNotificationColdRouterFiles.NAMESPACE).toPath(),java.nio.file.LinkOption.NOFOLLOW_LINKS)){"Existing cold router material retained"}
            }}
            stage="PREPARE";runBlocking {prepare()}
            stage="INSTALLATION"
            NativeMigrationInboxOwnerActivity.prepare(fixture.run,context,initial,discoveryEnabled=false,
                sessionConfiguration=NativeMigrationPrivateSessionConfiguration(::configureOwnedSession))
            installationPrepared=true;stage="ACTIVITY_AND_BODY";base.evaluate();stage="BODY_RETURNED"
        }catch(failure: Throwable){primary=failure;throw failure}
        finally {
            try {
                runBlocking {retireKnownOwner()}
                if(installationPrepared){NativeMigrationInboxOwnerActivity.release(fixture.run);installationReleased=true}
                if(owners.isNotEmpty()){assertNoPorts();postJoinProviderCheckPassed=true}
            }catch(failure: Throwable){cleanupFailure=failure}
            try {record(primary,cleanupFailure)}catch(failure: Throwable){
                val prior=cleanupFailure
                if(prior==null)cleanupFailure=failure else prior.addSuppressed(failure)
            }
            cleanupFailure?.let {failure->if(primary!=null)primary.addSuppressed(failure)else throw failure}
        }
    }
    private suspend fun retireKnownOwner() {
        check(!cleanupAttempted){"No repeated owner retirement"};cleanupAttempted=true
        if(!acquisitionStarted) {
            if(storageAcquisitionStarted) {
                check(::storage.isInitialized){"Storage constructor outcome unknown; private material retained"}
                withContext(Dispatchers.IO){withTimeout(30_000){storage.awaitQueuedWrites()};storage.retireWriter()}
                storageWriterRetired=true
            }
            return
        }
        check(::initial.isInitialized){"Session constructor outcome unknown; private material retained"}
        val activity=if(installationPrepared)NativeMigrationInboxOwnerActivity.activity(fixture.run)else null
        activityAcquired=activity!=null
        check(!installationPrepared||activity!=null){"Activity acquisition outcome unknown; prepared owner retained"}
        val ending=withContext(Dispatchers.Main.immediate) {
            val controller=activity?.currentMigrationOrNull()
            if(controller!=null) {
                check(!controller.busy&&controller.phase!=NativeMigrationPhase.UNKNOWN&&controller.picker==null&&!controller.needsOwnerRestore&&controller.canDismiss)
                controller.close()
            }
            activity?.currentSessionOrNull() ?: initial
        }
        check(owners.isNotEmpty()&&owners.all {it.configured}){"Private Session configuration incomplete; retain original owners"}
        check(owners.any {it.session===ending}){"Unknown Main Session; retain all owners"}
        // A successful original Main transfer already retired initial; never retire it twice.
        if(ending!==initial)withContext(Dispatchers.Main.immediate) {
            check(name.methodName=="actualMainConfirmPublishesPreparedCandidateAndRestoresOnlyOnce"&&prepared!=null)
            check(owners.size==2&&ending.migrationReady&&initial.migrationPaused)
            check(activity?.currentMigrationOrNull()?.result in setOf(NativeMigrationResult.IMPORTED,NativeMigrationResult.PREVIOUS_IMPORT))
            check(ending.migrationStorage.migrationPublicationReceipt()==prepared)
            mainTransferredOriginal=true
        }else check(owners.size==1){"Uncommitted fresh owner outcome unknown; retain all owners"}
        if(activity!=null) {
            withContext(Dispatchers.Main.immediate){check(!activity.isChangingConfigurations);if(!activity.isDestroyed)activity.finish()}
            withTimeout(15_000){while(!withContext(Dispatchers.Main.immediate){activity.isDestroyed})delay(20)}
            activityClosed=true
        }
        withContext(Dispatchers.Main.immediate) {
            check(!ending.migrationPaused){"Unfinished migration owner; private installation retained"}
            withTimeout(30_000){while(!ending.migrationReady)delay(20)}
            val operation=UUID.randomUUID().toString();val lease=ending.quiesceForMigration(operation)
            withContext(NonCancellable+Dispatchers.IO){lease.releaseRawWriters()}
            ending.retireForMigration(operation);ending.voice?.retireForMigration();ending.questionVoice?.retireForMigration()
            retiredOwner=ending;retirementConfirmed=true;storageWriterRetired=true
        }
        check(activityClosed||!installationPrepared){"Activity launch outcome unknown; retain prepared owner"}
    }
    private fun record(primary: Throwable?,cleanup: Throwable?) {
        var snapshot=emptyList<ConfiguredOwner>()
        instrumentation.runOnMainSync {snapshot=owners.toList()}
        val probes=JSONArray()
        snapshot.forEachIndexed {index,owned->probes.put(JSONObject().put("owner",index).put("configured",owned.configured)
            .put("attempts",owned.deny.snapshot().size).put("overflow",owned.deny.overflow.get())
            .put("retiredByThisRule",owned.session===retiredOwner).put("transferredByMain",owned.session===initial&&mainTransferredOriginal))}
        val result=JSONObject().put("case",name.methodName).put("stage",stage).put("completed",completed)
            .put("testFailed",primary!=null).put("cleanupFailed",cleanup!=null).put("folderOwned",folderOwned)
            .put("storageAcquisitionStarted",storageAcquisitionStarted).put("storageWriterRetired",storageWriterRetired)
            .put("acquisitionStarted",acquisitionStarted).put("knownOwnerCount",snapshot.size).put("ownerSnapshotOnly",cleanup!=null)
            .put("privateSessionRetired",retirementConfirmed).put("activityAcquired",activityAcquired).put("activityClosed",activityClosed)
            .put("postJoinProviderCheckPassed",postJoinProviderCheckPassed)
            .put("installationPrepared",installationPrepared).put("installationReleased",installationReleased)
            .put("privateMaterialRetained",true).put("preparedCandidate",prepared!=null).put("inheritedMainAssembly",true)
            .put("discoveryDisableRequested",true).put("providerProbes",probes).put("axWaits",axWaits)
            .put("nativeBack",nativeBackProof ?: JSONObject.NULL)
            .put("safAccepted",false).put("physicalDeviceAccepted",false).put("shippingTransportAccepted",false).put("isolatedCompose",false)
        // Also emit only scalars if admission/fresh() failed before a new directory was owned.
        instrumentation.sendStatus(0,Bundle().apply {putString("p7MainUiCleanup",result.toString())})
        if(!folderOwned)return
        val record=File(fixture.folder,"main-ui-result.json")
        check(!Files.isSymbolicLink(record.toPath())&&record.createNewFile())
        record.writeText(result.toString());android.system.Os.chmod(record.path,0x180)
        java.io.FileOutputStream(record,true).use {it.fd.sync()}
    }
}
