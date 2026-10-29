package com.agenthub.android

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import android.provider.DocumentsContract
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.agenthub.core.TargetScope
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID

/** Prepared REAL SAF UI tests. No FileProvider, fabricated result, registry replacement or shell.
 * DocsUI IDs/location are supplied only after the device owner observes them. Transport is an owned
 * loopback component fixture, not a real Host/model. No screenshots, AX dumps or password logging. */
class NativeMigrationSystemPickerUiTest {
    @get:Rule(order=0) val timeout: org.junit.rules.TestRule=org.junit.rules.Timeout.seconds(240)
    @get:Rule(order=1) val foreground: org.junit.rules.TestRule=NativeTestForeground()
    @get:Rule(order=2) val ui=createAndroidComposeRule<MainActivity>()
    private val i get()=InstrumentationRegistry.getInstrumentation()
    private val app="com.easyai.agenthub.android.acceptance"
    private val nonce=UUID.randomUUID().toString()
    private val host=UUID.randomUUID().toString()
    private val thread=UUID.randomUUID().toString()
    private val scope=TargetScope("codex",null,null,host,UUID.randomUUID().toString(),UUID.randomUUID().toString())
    private val unknownId=UUID.randomUUID().toString()
    private val unsentId=UUID.randomUUID().toString()
    private val sourceVersion=UUID.randomUUID().toString()
    private val originalDraft="Owned unsent original $nonce 100% %@"
    private val originalInput="Owned immutable original $nonce"
    private var pass=Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also {SecureRandom().nextBytes(it)})
    private lateinit var owned: OwnedControlAcceptance
    private lateinit var previous: MainActivity.AcceptanceSessionState
    private lateinit var server: CommandFixture
    private lateinit var client: MobileClient
    private lateinit var source: NativeSessionStorage
    private lateinit var sourceFile: LocalDraftFile
    private lateinit var raw: File
    private lateinit var profile: JSONObject
    private lateinit var tree: Uri
    private lateinit var docs: String
    private var treeName=""
    private var currentDraftKey=""
    private var exportName=""
    private var exported: Uri?=null
    private var exportFingerprint: Pair<Long,String>?=null
    private var sourceOperations=""
    private val sourceSubmissions=linkedMapOf<String,String>()
    private val sessions=linkedSetOf<MobileSession>()
    private val requests=JSONArray()
    private var activePicker: NativeMigrationPicker?=null
    private var pickerReadinessDeadline=0L
    private data class OriginalPickerScope(val activity: MainActivity,val controller: NativeMigrationController,val session: MobileSession,
        val storage: String,val target: String,val id: String,val kind: NativeMigrationPickerKind)
    private var originalPickerScope: OriginalPickerScope?=null
    private data class SensitiveAppScope(val session: MobileSession,val controller: NativeMigrationController,val storage: String,val target: String,
        val kind: NativeMigrationPickerKind,val label: String,val pageDeadline: Long)
    private var sensitiveScope: SensitiveAppScope?=null
    private var sensitivePageOrdinal=0
    private var sensitiveInputDeadline=0L
    private var completed=false
    private var originalsVerified=false
    private var originalAccessibilityFlags: Int?=null
    private val target get()=client.cacheKey(thread)
    private fun <T> main(body: ()->T): T {var result: T?=null;i.runOnMainSync {result=body()};@Suppress("UNCHECKED_CAST") return result as T}
    private fun await(label: String,ms: Long=30_000,condition: ()->Boolean) {
        val end=SystemClock.elapsedRealtime()+ms
        do {
            // Drive this App's real Compose recomposition without password-bearing semantics
            // dumps. External DocsUI is never replaced or advanced by this test clock.
            ui.mainClock.advanceTimeByFrame();i.waitForIdleSync()
            if(condition())return
            SystemClock.sleep(40)
        }while(SystemClock.elapsedRealtime()<end)
        assertTrue(label,condition())
    }
    private fun owner(): String? {
        val root=i.uiAutomation.rootInActiveWindow ?: return null
        try {return if(root.refresh())root.packageName?.toString() else null}
        finally {@Suppress("DEPRECATION") root.recycle()}
    }
    private fun awaitOwner(expected: String) {await("Exact native owner must become available",15_000){owner()==expected}}
    private fun appOwner(){assertEquals("Only the independent acceptance App may receive input",app,owner())}
    private fun id(name: String): String=profile.getString(name).also {value->
        require(value.matches(Regex("(?:${Regex.escape(docs)}|android):id/[a-zA-Z0-9_]+"))) {"Observed DocsUI resource ID required"}
    }
    private fun <T> nodes(expected: String,body: (List<AccessibilityNodeInfo>)->T): T {
        val root=checkNotNull(i.uiAutomation.rootInActiveWindow){"Native root unavailable"}
        val all=mutableListOf<AccessibilityNodeInfo>()
        fun visit(n: AccessibilityNodeInfo,depth: Int) {
            check(depth<=40&&all.size<2048){"Native accessibility bound exceeded"};check(n.packageName?.toString()==expected)
            all+=n;for(index in 0 until n.childCount)n.getChild(index)?.let {visit(it,depth+1)}
        }
        try {check(root.refresh()&&root.packageName?.toString()==expected){"Unexpected native owner; no input sent"};visit(root,0);return body(all)}
        finally {@Suppress("DEPRECATION") all.forEach {it.recycle()};if(all.none{it===root}){@Suppress("DEPRECATION") root.recycle()}}
    }
    private fun nativeAction(expected: String,match: (AccessibilityNodeInfo)->Boolean,action: Int,args: Bundle?=null,ancestor: Boolean=false,
        observed: List<AccessibilityNodeInfo>?=null,beforeInput: ()->Unit={}) {
        fun act(all: List<AccessibilityNodeInfo>) {
            val candidates=all.filter {it.isVisibleToUser&&match(it)};check(candidates.size==1){"Expected one observed native control; no hierarchy/content logged"}
            var chosen=candidates.single();val parents=mutableListOf<AccessibilityNodeInfo>()
            try {
                if(ancestor)repeat(6){if(!chosen.isClickable)chosen.parent?.let {p->check(p.packageName?.toString()==expected);parents+=p;chosen=p}}
                beforeInput();check(owner()==expected&&chosen.refresh()&&chosen.isEnabled){"Native owner/control changed; no input sent"}
                check(chosen.performAction(action,args)){"Native control rejected action"}
            }finally {@Suppress("DEPRECATION") parents.forEach {it.recycle()}}
        }
        if(observed==null)nodes(expected,::act)else act(observed)
    }
    private fun clickId(key: String)=nativeAction(docs,{it.viewIdResourceName==id(key)},AccessibilityNodeInfo.ACTION_CLICK,ancestor=true)
    private fun setId(key: String,value: String)=nativeAction(docs,{it.viewIdResourceName==id(key)&&it.isEditable},AccessibilityNodeInfo.ACTION_SET_TEXT,
        Bundle().apply {putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,value)})
    private fun password() {
        val scope=checkNotNull(sensitiveScope);sensitiveInputDeadline=SystemClock.elapsedRealtime()+30_000
        awaitSensitiveApp(scope,"password-input",sensitiveInputDeadline,{state->state.getBoolean("domainReady")&&state.getInt("visibleEditablePasswords")==1&&state.getInt("enabledVisibleEditablePasswords")==1}) {all->
            nativeAction(app,{it.isEditable&&it.isPassword},AccessibilityNodeInfo.ACTION_SET_TEXT,
                Bundle().apply {putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,pass)},observed=all,beforeInput={requireSensitiveInput(scope,sensitiveInputDeadline)})
        }
        awaitSensitiveApp(scope,"password-valid",sensitiveInputDeadline,{it.getBoolean("passwordValid")})
    }
    private fun clickSensitiveAppButton(copy: String) {
        val scope=checkNotNull(sensitiveScope);check(NativeL10n(ui.activity,"en").tr(copy)==scope.label)
        awaitSensitiveApp(scope,"action-input",sensitiveInputDeadline,{state->state.getBoolean("domainReady")&&state.getBoolean("passwordValid")&&state.getInt("visibleActionLabels")==1&&state.getInt("enabledClickableActionLabels")==1}) {all->
            nativeAction(app,{!it.isPassword&&!it.isEditable&&it.text?.toString()==scope.label},AccessibilityNodeInfo.ACTION_CLICK,ancestor=true,
                observed=all,beforeInput={requireSensitiveInput(scope,sensitiveInputDeadline)})
        }
        // This is the existing post-action clearing wait. No AX/Compose semantics are inspected.
        val end=SystemClock.elapsedRealtime()+30_000
        do {
            val state=sensitiveIdentity(scope,stableSession=false)
            check(state.getBoolean("identityMatches")){"Sensitive case/storage/target changed after action; stop"}
            val actual=owner();check(actual==app||actual==docs){"Unknown or third owner after sensitive action; stop"}
            if(main {scope.controller.password.isEmpty()})return
            ui.mainClock.advanceTimeByFrame();i.waitForIdleSync();SystemClock.sleep(40)
        }while(SystemClock.elapsedRealtime()<end)
        error("Password not retired within original clearing budget; no further input")
    }
    private fun sensitiveIdentity(scope: SensitiveAppScope,stableSession: Boolean=true): JSONObject=main {
        val session=ui.activity.session;val storage=session.migrationStorage.coordinationKey;val root=owned.storage.coordinationKey
        val sameSession=session===scope.session;val sameStorage=storage==scope.storage;val sameTarget=session.targetKey==scope.target
        val sameController=ui.activity.migration===scope.controller;val ownedStorage=storage==root||storage.startsWith(root+"-")
        val alive=!ui.activity.isFinishing&&!ui.activity.isDestroyed
        JSONObject().put("sameSession",sameSession).put("sameStorage",sameStorage).put("sameTarget",sameTarget).put("sameController",sameController).put("ownedCaseStorage",ownedStorage).put("activityAlive",alive)
            .put("identityMatches",(!stableSession||sameSession)&&sameStorage&&sameTarget&&sameController&&ownedStorage&&alive)
            .put("domainReady",scope.controller.presented&&scope.controller.ready&&!scope.controller.busy&&scope.controller.picker==null)
            .put("passwordValid",scope.controller.passwordValid)
    }
    private fun requireSensitiveInput(scope: SensitiveAppScope,deadline: Long) {
        check(SystemClock.elapsedRealtime()<deadline&&owner()==app){"Sensitive input budget/owner changed; no input sent"}
        check(sensitiveIdentity(scope).getBoolean("identityMatches")){"Sensitive case/storage/target changed; no input sent"}
    }
    private fun clickableAppControl(node: AccessibilityNodeInfo): Boolean {
        var chosen=node;val parents=mutableListOf<AccessibilityNodeInfo>()
        try {
            repeat(6) {if(!chosen.isClickable)chosen.parent?.let {parent->parents+=parent;check(parent.refresh()&&parent.packageName?.toString()==app);chosen=parent}}
            return chosen.refresh()&&chosen.packageName?.toString()==app&&chosen.isClickable&&chosen.isVisibleToUser&&chosen.isEnabled
        }finally {@Suppress("DEPRECATION") parents.forEach {it.recycle()}}
    }
    private fun awaitSensitiveApp(scope: SensitiveAppScope,stage: String,deadline: Long,ready: (JSONObject)->Boolean,action: ((List<AccessibilityNodeInfo>)->Unit)?=null) {
        require(stage.matches(Regex("[a-z-]{1,32}")))
        val begun=SystemClock.elapsedRealtime();var samples=0;var matched=false;var actionAccepted=false
        var first: JSONObject?=null;var last=JSONObject();var ownerState="UNOBSERVED"
        val record=File(owned.folder,"sensitive-app-readiness-$sensitivePageOrdinal-$stage.json")
        fun save() {record.writeText(JSONObject().put("stage",stage).put("knownActionLabel",scope.label).put("startedElapsedRealtimeMs",begun).put("observedElapsedRealtimeMs",SystemClock.elapsedRealtime())
            .put("deadlineMs",deadline).put("samples",samples).put("ownerState",ownerState).put("matched",matched).put("nativeActionAccepted",actionAccepted)
            .put("firstCountsAndState",first ?: JSONObject.NULL).put("lastCountsAndState",last).put("noInputValueHierarchyOrScreenshot",true).toString())}
        try {
            while(SystemClock.elapsedRealtime()<deadline) {
                val actual=owner();ownerState=when(actual){app->"APP";null->"UNKNOWN";else->"THIRD"}
                check(actual==app){"Unknown or third sensitive-page owner; stop input"}
                val state=sensitiveIdentity(scope);last=state;check(state.getBoolean("identityMatches")){"Sensitive case/storage/target changed; stop input"}
                nodes(app) {all->
                    val fresh=all.filter {it.refresh()}
                    // Never read text/contentDescription from a password or editable node.
                    val fields=fresh.filter {it.isEditable&&it.isPassword}
                    val labels=fresh.filter {!it.isPassword&&!it.isEditable&&it.text?.toString()==scope.label&&it.isVisibleToUser}
                    state.put("nativeNodeCount",fresh.size).put("editableNodes",fresh.count {it.isEditable}).put("passwordNodes",fresh.count {it.isPassword})
                        .put("visibleEditableNodes",fresh.count {it.isEditable&&it.isVisibleToUser}).put("editablePasswords",fields.size)
                        .put("visibleEditablePasswords",fields.count {it.isVisibleToUser}).put("enabledVisibleEditablePasswords",fields.count {it.isVisibleToUser&&it.isEnabled})
                        .put("visibleActionLabels",labels.size).put("enabledClickableActionLabels",labels.count {clickableAppControl(it)})
                    if(first==null)first=JSONObject(state.toString());samples++;save()
                    if(ready(state)) {
                        requireSensitiveInput(scope,deadline)
                        action?.invoke(all);actionAccepted=action!=null;matched=true
                    }
                }
                if(matched)return
                ui.mainClock.advanceTimeByFrame();i.waitForIdleSync();SystemClock.sleep(40)
            }
            error("Sensitive AX controls not ready within original phase budget; inspect count/state evidence, no guessed cause")
        }finally {save()}
    }
    private fun fingerprint(input: InputStream): Pair<Long,String> {
        val digest=MessageDigest.getInstance("SHA-256");val chunk=ByteArray(65536);var size=0L
        input.use {while(true){val n=it.read(chunk);if(n<0)break;digest.update(chunk,0,n);size+=n}}
        return size to digest.digest().joinToString(""){"%02x".format(it)}
    }
    private fun fingerprint(uri: Uri)=fingerprint(checkNotNull(i.targetContext.contentResolver.openInputStream(uri)))
    private fun children(): List<Pair<String,Uri>> {
        val resolver=i.targetContext.contentResolver;val parent=DocumentsContract.getTreeDocumentId(tree)
        val uri=DocumentsContract.buildChildDocumentsUriUsingTree(tree,parent)
        return checkNotNull(resolver.query(uri,arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,DocumentsContract.Document.COLUMN_DISPLAY_NAME),null,null,null)).use {cursor->
            val rows=mutableListOf<Pair<String,Uri>>()
            while(cursor.moveToNext()){check(rows.size<4){"Owned UUID directory must not contain unrelated files"};rows+=cursor.getString(1) to DocumentsContract.buildDocumentUriUsingTree(tree,cursor.getString(0))};rows
        }
    }
    private fun validateEnvironment() {
        check(i.targetContext.packageName==app&&i.context.packageName=="$app.test")
        check(i.targetContext.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE!=0)
        val defaults=NativeSessionStorage(i.targetContext)
        check(defaults.metadata()?.optJSONArray("clients")?.length() in listOf(null,0)&&CodexConnectionVault(defaults).clients().isEmpty()){ "Fresh independent acceptance installation required; do not run against an original paired installation" }
        val args=InstrumentationRegistry.getArguments();check(args.getString("migrationSystemPickerOwnedLease")=="1"){"Parent device lease opt-in required"}
        val run=checkNotNull(args.getString("migrationSafRun"));check(UUID.fromString(run).toString()==run)
        profile=args.getString("migrationSafProfile")?.let(::JSONObject) ?: run {
            // Actually observed by the parent on fresh API37 emulator-5564, probe e1c1e56c.
            // This identifies controls only; it is not an App result or cross-device pass.
            check(android.os.Build.VERSION.SDK_INT==37){"Observe and supply this API/device DocsUI profile first"}
            JSONObject().put("package","com.google.android.documentsui").put("fileNameId","android:id/title")
                .put("saveId","android:id/button1").put("directoryTitleId","com.google.android.documentsui:id/breadcrumb_text")
        }
        check(profile.keys().asSequence().all {it in setOf("package","fileNameId","saveId","directoryTitleId","searchId","searchInputId","openButtonId","cancelBackCount")})
        docs=profile.getString("package");check(docs in setOf("com.android.documentsui","com.google.android.documentsui"))
        for(action in listOf(Intent.ACTION_CREATE_DOCUMENT,Intent.ACTION_OPEN_DOCUMENT)) {
            val intent=Intent(action).addCategory(Intent.CATEGORY_OPENABLE).setType("application/octet-stream")
            val info=checkNotNull(i.targetContext.packageManager.resolveActivity(intent,PackageManager.MATCH_DEFAULT_ONLY)).activityInfo
            check(info.packageName==docs&&info.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM!=0&&info.applicationInfo.uid!=Process.myUid())
        }
        for(key in listOf("fileNameId","saveId","directoryTitleId"))id(key)
        for(key in listOf("searchId","searchInputId","openButtonId"))if(profile.has(key))id(key)
        // Historical observed counts remain valid profile metadata, never an input instruction.
        if(profile.has("cancelBackCount"))check(profile.getInt("cancelBackCount") in 1..3)
        treeName="agent-hub-picker-$run"
        tree=Uri.parse(checkNotNull(args.getString("migrationSafTreeUri")));check(tree.scheme=="content"&&DocumentsContract.isTreeUri(tree))
        val provider=checkNotNull(i.targetContext.packageManager.resolveContentProvider(checkNotNull(tree.authority),0))
        check(provider.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM!=0&&provider.applicationInfo.uid!=Process.myUid())
        val directory=DocumentsContract.buildDocumentUriUsingTree(tree,DocumentsContract.getTreeDocumentId(tree))
        checkNotNull(i.targetContext.contentResolver.query(directory,arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME,DocumentsContract.Document.COLUMN_MIME_TYPE),null,null,null)).use {
            check(it.moveToFirst()&&it.getString(0)==treeName&&it.getString(1)==DocumentsContract.Document.MIME_TYPE_DIR)
        }
        check(children().isEmpty()){ "Preselected UUID directory must be empty; no existing file is touched" }
        val info=i.uiAutomation.serviceInfo;originalAccessibilityFlags=info.flags;info.flags=info.flags or AccessibilityServiceInfoCompat.reportViewIds;i.uiAutomation.serviceInfo=info
    }
    private object AccessibilityServiceInfoCompat {const val reportViewIds=android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS}
    @Before fun setup() {
        check(i.targetContext.packageName==app);ui.waitUntil(30_000){!ui.activity.session.restoring};validateEnvironment()
        ui.runOnIdle {owned=OwnedControlAcceptance(ui.activity,"migration-system-picker")}
        server=CommandFixture {request->
            if(request.method!="GET")WireResponse(409) else when(request.path) {
                "/v1/workspace"->WireResponse(body=JSONObject().put("hostId",host).put("name","Owned picker fixture").put("connectionMode","desktop-runtime").put("readTarget",CodexCommandCodec.target(scope))
                    .put("capabilities",JSONArray()).put("projects",JSONArray()).put("threads",JSONArray()).toString())
                else->WireResponse(404)
            }
        }
        client=MobileClient(host,"Owned UUID picker source",scope,server.endpoint,false,"desktop-runtime",false)
        source=owned.storage
        raw=File(owned.folder,"raw-$nonce.bin")
        raw.outputStream().use {out->val block=ByteArray(65536){(it%251).toByte()};repeat(144){out.write(block)}}
        runBlocking {sourceFile=NativeDraftFiles(source).importFile("owned-$nonce.bin","application/octet-stream",raw)}
        val metadata=JSONObject().put("version",1).put("appearance","light").put("language","en").put("demo",false).put("selected",host)
            .put("clients",JSONArray().put(encodeClient(client))).put("drafts",JSONObject().put(target,originalDraft)).put("draftFiles",JSONObject().put(target,JSONArray().put(sourceFile.manifest.body())))
            .put("draftVersions",JSONObject().put(target,UUID.randomUUID().toString())).put("selectedThreads",JSONObject()).put("expanded",JSONObject()).put("workspaces",JSONObject())
        val operations=JSONArray()
        for((id,attempted)in listOf(unknownId to true,unsentId to false)) {
            val intent=ReplyIntent(host,thread,id,originalInput,listOf(sourceFile.manifest.id),"next-turn").body().toString()
            operations.put(SavedOperation(id,"reply",target,intent,MigrationActivationFixtures.sha(intent.toByteArray()),if(attempted)"uncertain"else"ready",submissionAttempted=attempted,sourceKey=target,sourceVersion=sourceVersion).body())
            val submission=JSONObject().put("hostId",host).put("target",CodexCommandCodec.target(scope)).put("sourceKey",target).put("sourceVersion",sourceVersion).put("text",originalInput).put("files",JSONArray().put(sourceFile.manifest.body())).toString()
            sourceSubmissions[id]=submission;source.write("submission:$id",submission)
        }
        sourceOperations=JSONObject().put("version",1).put("target",target).put("operations",operations).toString()
        source.write("operations:$target",sourceOperations);source.write("metadata",metadata.toString())
        main {owned.session.restore()};await("Owned source restores"){main {!owned.session.restoring&&owned.session.phoneReading.ready}}
        main {previous=ui.activity.adoptSessionForAcceptance(owned.session);sessions+=owned.session}
        awaitReady();main {ui.activity.session.text="Owned current unsent $nonce";currentDraftKey=ui.activity.session.targetKey}
        exportName="agent-hub-migration-$nonce.codexmigration"
    }
    private fun awaitReady(){await("Actual migration owner ready"){main {ui.activity.session.migrationReady&&ui.activity.migration.ready}}}
    private fun openPage(kind: NativeMigrationPickerKind) {
        awaitOwner(app);appOwner();ui.onNodeWithTag("chat-menu").performClick();appOwner();ui.onNodeWithTag("open-settings").performClick()
        val label=NativeL10n(ui.activity,"en").tr(if(kind==NativeMigrationPickerKind.EXPORT)"导出加密迁移文件"else"导入加密迁移文件")
        val scope=main {val session=ui.activity.session;check(sessions.any {it===session});SensitiveAppScope(session,ui.activity.migration,session.migrationStorage.coordinationKey,session.targetKey,kind,label,SystemClock.elapsedRealtime()+30_000)}
        sensitiveScope=scope;sensitivePageOrdinal++
        appOwner();ui.onNodeWithTag("settings-migration").performScrollTo().performClick()
        awaitSensitiveApp(scope,"page",scope.pageDeadline,{state->state.getBoolean("domainReady")&&state.getInt("visibleEditablePasswords")==1&&state.getInt("enabledVisibleEditablePasswords")==1&&state.getInt("visibleActionLabels")==1})
    }
    @Suppress("UNCHECKED_CAST") private fun issuedOnMain(kind: NativeMigrationPickerKind): List<String> {
        val field=NativeMigrationController::class.java.getDeclaredField("issued").apply {isAccessible=true}
        return (field.get(ui.activity.migration) as Map<NativeMigrationPickerKind,java.util.ArrayDeque<NativeMigrationPicker>>).getValue(kind).map {it.id}
    }
    private fun issued(kind: NativeMigrationPickerKind)=main {issuedOnMain(kind)}
    private fun launch(kind: NativeMigrationPickerKind): NativeMigrationPicker {
        check(sensitiveScope?.kind==kind)
        password();clickSensitiveAppButton(if(kind==NativeMigrationPickerKind.EXPORT)"导出加密迁移文件"else"导入加密迁移文件")
        await("Actual original picker launches"){main {ui.activity.migration.picker?.let {it.kind==kind&&it.launched}==true}}
        val request=main {
            val activity=ui.activity;val controller=activity.migration;val session=activity.session;val request=checkNotNull(controller.picker)
            val submitted=checkNotNull(sensitiveScope)
            check(controller===submitted.controller&&session.migrationStorage.coordinationKey==submitted.storage&&session.targetKey==submitted.target&&
                request.kind==kind&&request.launched&&issuedOnMain(kind)==listOf(request.id)){"Submitted target/original issued picker changed; no input sent"}
            originalPickerScope=OriginalPickerScope(activity,controller,session,session.migrationStorage.coordinationKey,session.targetKey,request.id,request.kind)
            request
        };activePicker=request;check(issued(kind)==listOf(request.id))
        // Owner and child readiness share the existing owner-wait budget; no new timer per phase.
        pickerReadinessDeadline=SystemClock.elapsedRealtime()+15_000
        awaitOriginalDocsReady(request,"owner",allowOpeningApp=true){true}
        requests.put(JSONObject().put("id",request.id).put("kind",kind.name).put("actualSystemOwner",docs).put("issuedOriginal",true));return request
    }
    private fun requireOriginalLive(request: NativeMigrationPicker) {
        val original=checkNotNull(originalPickerScope){"Original picker scope unavailable; no input sent"}
        check(activePicker?.id==request.id&&request.id==original.id&&request.kind==original.kind&&main {
            val activity=ui.activity;val session=activity.session
            activity===original.activity&&!activity.isFinishing&&!activity.isDestroyed&&activity.migration===original.controller&&session===original.session&&
                session.migrationStorage.coordinationKey==original.storage&&session.targetKey==original.target
        }){"Original Activity/controller/session/storage/target changed; no input sent"}
        check(cancellationCaller(request)==NativeSafCancellationObservation.Caller.ORIGINAL){"Original issued picker changed; no input sent"}
    }
    private fun requireOriginalInput(request: NativeMigrationPicker) {
        check(SystemClock.elapsedRealtime()<pickerReadinessDeadline){"Original readiness budget ended; no input sent"}
        requireOriginalLive(request);check(owner()==docs){"Unknown or changed native owner; no input sent"}
    }
    private fun awaitOriginalDocsReady(request: NativeMigrationPicker,stage: String,allowOpeningApp: Boolean=false,ready: ()->Boolean) {
        require(stage.matches(Regex("[a-z-]{1,32}")))
        val begun=SystemClock.elapsedRealtime();var samples=0;var nullSamples=0;var matched=false;var lastOwner: String?=null;var ownerObservedAt=0L
        try {
            while(SystemClock.elapsedRealtime()<pickerReadinessDeadline) {
                requireOriginalLive(request);lastOwner=owner();ownerObservedAt=SystemClock.elapsedRealtime();samples++
                if(lastOwner==null)nullSamples++
                check(lastOwner==null||lastOwner==docs||allowOpeningApp&&lastOwner==app){"Third native owner; stop original readiness observation"}
                if(lastOwner==docs&&ready()) {requireOriginalInput(request);matched=true;return}
                ui.mainClock.advanceTimeByFrame();i.waitForIdleSync();SystemClock.sleep(40)
            }
            error("Exact original DocsUI controls not ready within the existing owner budget; no input or relaunch")
        }finally {
            File(owned.folder,"system-picker-readiness-${request.id}-$stage.json").writeText(JSONObject().put("originalPickerId",request.id).put("kind",request.kind.name).put("stage",stage)
                .put("startedElapsedRealtimeMs",begun).put("observedElapsedRealtimeMs",SystemClock.elapsedRealtime()).put("originalOwnerDeadlineMs",pickerReadinessDeadline)
                .put("ownerBudgetMs",15_000).put("samples",samples).put("nullOwnerSamples",nullSamples).put("sameOriginalIdentityValidatedForSamples",samples)
                .put("matched",matched).put("lastObservedOwner",lastOwner ?: JSONObject.NULL).put("ownerObservedElapsedRealtimeMs",ownerObservedAt)
                .put("observationInsideTestMethod",true).put("postRuleRuntimeSurvivalVerified",false).put("noHierarchyOrPasswordCapture",true).toString())
        }
    }
    private fun visibleControl(node: AccessibilityNodeInfo,key: String)=node.refresh()&&node.viewIdResourceName==id(key)&&node.isVisibleToUser&&!node.isPassword
    private fun ownDirectoryReady(all: List<AccessibilityNodeInfo>)=all.count {visibleControl(it,"directoryTitleId")&&it.text?.toString()==treeName}==1
    private fun controlReady(all: List<AccessibilityNodeInfo>,key: String,editable: Boolean=false,enabled: Boolean=true,text: String?=null)=
        all.count {visibleControl(it,key)&&(!editable||it.isEditable)&&(!enabled||it.isEnabled)&&(text==null||it.text?.toString()==text)}==1
    private fun ownRowReady(all: List<AccessibilityNodeInfo>)=all.count {it.refresh()&&it.isVisibleToUser&&!it.isPassword&&it.text?.toString()==exportName}==1
    private fun cancellationCaller(request: NativeMigrationPicker)=main {
        val migration=ui.activity.migration;val current=migration.picker;val pending=issuedOnMain(request.kind)
        when {
            current?.id==request.id&&current.kind==request.kind&&current.launched&&pending==listOf(request.id)->NativeSafCancellationObservation.Caller.ORIGINAL
            current==null&&pending.isEmpty()&&!migration.busy&&migration.result==NativeMigrationResult.CANCELLED->NativeSafCancellationObservation.Caller.CANCELLED
            else->NativeSafCancellationObservation.Caller.UNKNOWN
        }
    }
    private fun cancelled(request: NativeMigrationPicker) {
        check(activePicker?.id==request.id)
        val begun=SystemClock.elapsedRealtime();val backs=JSONArray();var acceptedBacks=0;var attempts=0
        var nextBackAt=begun;var returnedToApp=false;var complete=false
        var lastOwner: String?=null;var caller=NativeSafCancellationObservation.Caller.UNKNOWN
        val record=File(owned.folder,"system-picker-cancellation-${request.id}.json")
        fun save() {record.writeText(JSONObject().put("originalPickerId",request.id).put("kind",request.kind.name).put("docsUi",docs)
            .put("startedElapsedRealtimeMs",begun).put("observedElapsedRealtimeMs",SystemClock.elapsedRealtime()).put("attemptedBacks",attempts).put("acceptedBacks",acceptedBacks)
            .put("backs",backs).put("returnedToApp",returnedToApp).put("actualOwner",lastOwner ?: JSONObject.NULL).put("caller",caller.name)
            .put("actualCancelledCallbackObserved",complete).put("originalCallerRetained",!complete).put("syntheticCallbackOrRelaunch",false).toString())}
        try {
            while(SystemClock.elapsedRealtime()-begun<15_000) {
                lastOwner=owner();caller=cancellationCaller(request);if(lastOwner==app)returnedToApp=true
                val next=NativeSafCancellationObservation.next(lastOwner,app,docs,caller,acceptedBacks,returnedToApp)
                save()
                when(next) {
                    NativeSafCancellationObservation.Next.COMPLETE->{complete=true;activePicker=null;save();break}
                    NativeSafCancellationObservation.Next.STOP_OWNER->error("Third native owner; stop input and preserve original picker")
                    NativeSafCancellationObservation.Next.STOP_CALLER->error("Unknown original caller; stop input and preserve receipt")
                    NativeSafCancellationObservation.Next.BACK->if(SystemClock.elapsedRealtime()>=nextBackAt) {
                        // Recheck both boundaries immediately before this one native BACK.
                        requireOriginalLive(request)
                        caller=cancellationCaller(request);lastOwner=owner()
                        check(NativeSafCancellationObservation.next(lastOwner,app,docs,caller,acceptedBacks,returnedToApp)==NativeSafCancellationObservation.Next.BACK){"Original picker/owner changed; no input sent"}
                        attempts++;check(attempts<=3)
                        val at=SystemClock.elapsedRealtime();val accepted=i.uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                        if(accepted)acceptedBacks++
                        backs.put(JSONObject().put("ordinal",attempts).put("elapsedRealtimeMs",at).put("ownerBefore",lastOwner).put("originalIdMatched",true).put("accepted",accepted))
                        nextBackAt=SystemClock.elapsedRealtime()+1_000;save();check(accepted){"Native BACK rejected; original result unknown, no retry"}
                    }
                    NativeSafCancellationObservation.Next.WAIT->Unit
                }
                ui.mainClock.advanceTimeByFrame();i.waitForIdleSync();SystemClock.sleep(40)
            }
            check(complete){"Bounded observation ended without actual cancellation; preserve original caller, no second picker"}
            check(request.id !in issued(request.kind));check(children().isEmpty());assertOriginals()
        }finally {save()}
    }
    private fun exportFile(): Pair<Uri,Pair<Long,String>> {
        openPage(NativeMigrationPickerKind.EXPORT);val request=launch(NativeMigrationPickerKind.EXPORT)
        val prepared=NativeMigrationDocuments(i.targetContext).file(request.id,"export");val expected=fingerprint(prepared.inputStream())
        check(expected.first>8L*1024*1024);exportFingerprint=expected
        awaitOriginalDocsReady(request,"export-form"){nodes(docs){all->ownDirectoryReady(all)&&controlReady(all,"fileNameId",editable=true)&&controlReady(all,"saveId",enabled=false)}}
        requireOriginalInput(request);setId("fileNameId",exportName)
        awaitOriginalDocsReady(request,"export-save"){nodes(docs){all->ownDirectoryReady(all)&&controlReady(all,"fileNameId",editable=true,text=exportName)&&controlReady(all,"saveId")}}
        requireOriginalInput(request);clickId("saveId");awaitOwner(app)
        await("Actual SAF export finishes",60_000){main {ui.activity.migration.result==NativeMigrationResult.SAVED&&!ui.activity.migration.busy}}
        check(request.id !in issued(request.kind));check(main {ui.activity.migration.picker==null});activePicker=null;main {sessions+=ui.activity.session}
        val rows=children();check(rows.size==1&&rows.single().first==exportName){"Save location must be the preselected owned UUID directory"}
        val uri=rows.single().second;exported=uri;check(fingerprint(uri)==expected);check(!prepared.exists());exportFingerprint=expected
        assertOriginals();return uri to expected
    }
    private fun blankTarget(): NativeSessionStorage {
        appOwner();ui.onNodeWithTag("sheet-done").performClick()
        // One target per case, beneath this case's UUID-owned root; keep the child suffix narrow.
        val storage=owned.storage.child(ui.activity,"picker-target");val metadata=JSONObject().put("version",1).put("appearance","light").put("language","en").put("demo",false).put("selected",UUID.randomUUID().toString())
            .put("clients",JSONArray()).put("drafts",JSONObject().put("owned-$nonce","Destination original draft")).put("selectedThreads",JSONObject()).put("expanded",JSONObject()).put("workspaces",JSONObject())
        storage.write("metadata",metadata.toString());val value=MobileSession(storage);main {value.restore()}
        await("Owned empty target restores"){main {!value.restoring&&value.phoneReading.ready}};main {ui.activity.adoptSessionForAcceptance(value);sessions+=value};awaitReady();return storage
    }
    private fun importFile(): NativeMigrationCandidateStore.Receipt {
        openPage(NativeMigrationPickerKind.IMPORT);val request=launch(NativeMigrationPickerKind.IMPORT)
        val observedSearch=profile.has("searchId")&&profile.has("searchInputId")
        awaitOriginalDocsReady(request,"import-directory"){nodes(docs){all->ownDirectoryReady(all)}}
        awaitOriginalDocsReady(request,"import-entry-or-search"){nodes(docs){all->ownDirectoryReady(all)&&(ownRowReady(all)||observedSearch&&controlReady(all,"searchId"))}}
        fun visibleTitle()=nodes(docs){all->ownRowReady(all)}
        if(!visibleTitle()) {
            check(observedSearch){"Observed DocsUI search controls required; no guessed navigation"}
            requireOriginalInput(request);clickId("searchId")
            awaitOriginalDocsReady(request,"import-search-input"){nodes(docs){all->controlReady(all,"searchInputId",editable=true)}}
            requireOriginalInput(request);setId("searchInputId",exportName)
            awaitOriginalDocsReady(request,"import-search-query"){nodes(docs){all->controlReady(all,"searchInputId",editable=true,text=exportName)}}
            for(action in listOf(KeyEvent.ACTION_DOWN,KeyEvent.ACTION_UP)){requireOriginalInput(request);check(i.uiAutomation.injectInputEvent(KeyEvent(action,KeyEvent.KEYCODE_ENTER),true))}
            awaitOriginalDocsReady(request,"import-document"){visibleTitle()}
        }
        requireOriginalInput(request);nativeAction(docs,{!it.isPassword&&it.text?.toString()==exportName},AccessibilityNodeInfo.ACTION_CLICK,ancestor=true)
        if(profile.has("openButtonId")) {
            val confirmDeadline=SystemClock.elapsedRealtime()+5_000
            await("Open result or observed confirm control",5_000) {
                val actual=owner();check(actual==app||actual==docs){"Unknown or third owner after original row selection; stop input"}
                if(actual==app)true else {requireOriginalLive(request);nodes(docs){all->controlReady(all,"openButtonId")}}
            }
            if(owner()==docs){check(SystemClock.elapsedRealtime()<confirmDeadline);requireOriginalLive(request);clickId("openButtonId")}
        }
        awaitOwner(app);await("Real OpenDocument stages preview",60_000){main {ui.activity.migration.phase==NativeMigrationPhase.PREVIEW&&!ui.activity.migration.busy}}
        check(request.id !in issued(request.kind));activePicker=null;val receipt=checkNotNull(NativeMigrationCandidateStore(ui.activity,ui.activity.session.migrationStorage).currentReceipt())
        check(receipt.id==request.id);check(fingerprint(checkNotNull(exported))==exportFingerprint)
        ui.onNodeWithTag("migration-preview").assertExists();return receipt
    }
    private fun assertOriginals() {
        originalsVerified=false
        val current=main {sessions.firstOrNull{it===ui.activity.session}?.migrationStorage}?:source.reopen(ui.activity)
        val sourceNow=if(main {ui.activity.session.migrationStorage.coordinationKey==source.coordinationKey})current else source.reopen(ui.activity)
        check(sourceNow.read("operations:$target")==sourceOperations)
        sourceSubmissions.forEach{(id,value)->check(sourceNow.read("submission:$id")==value)}
        check(sourceNow.metadata()!!.getJSONObject("drafts").getString(target)==originalDraft)
        check(sourceNow.metadata()!!.getJSONObject("drafts").getString(currentDraftKey)=="Owned current unsent $nonce")
        check(fingerprint(raw.inputStream())==(sourceFile.manifest.sizeBytes to sourceFile.manifest.sha256))
        check(server.requests.all {it.method=="GET"&&it.path=="/v1/workspace"}){"No operation query, preparation, submit, upload or replay is permitted"}
        originalsVerified=true
    }
    @Test fun actualSystemCreateAndOpenCancellationConsumeOnlyTheirOriginalIds() {
        openPage(NativeMigrationPickerKind.EXPORT);cancelled(launch(NativeMigrationPickerKind.EXPORT))
        main {sessions+=ui.activity.session};blankTarget();openPage(NativeMigrationPickerKind.IMPORT);cancelled(launch(NativeMigrationPickerKind.IMPORT));completed=true
    }
    @Test fun actualSystemExportOpenPreviewAndCancelKeepOriginalSourceAndDoNotPublish() {
        val (uri,bytes)=exportFile();val target=blankTarget();val before=checkNotNull(target.metadata()).getJSONObject("drafts").toString();val receipt=importFile()
        check(target.migrationPublicationReceipt()==null);check(target.metadata()!!.getJSONObject("drafts").toString()==before)
        appOwner();ui.onNodeWithTag("migration-cancel").performScrollTo().performClick()
        await("Preview cancellation closes without activation"){main {!ui.activity.migration.presented}}
        check(target.migrationPublicationReceipt()==null);check(target.metadata()!!.getJSONObject("drafts").toString()==before);check(NativeMigrationCandidateStore(ui.activity,target).currentReceipt()==receipt)
        check(fingerprint(uri)==bytes);assertOriginals();completed=true
    }
    @Test fun actualSafFileAboveEightMiBRequiresExplicitConfirmAndNeverReplaysUnknownOrUnsentInput() {
        val (uri,bytes)=exportFile();val target=blankTarget();val receipt=importFile();check(target.migrationPublicationReceipt()==null)
        val retained=main {ui.activity.migration};ui.activityRule.scenario.recreate();awaitOwner(app)
        await("Preview survives actual Activity recreation"){main {ui.activity.migration===retained&&ui.activity.migration.phase==NativeMigrationPhase.PREVIEW&&ui.activity.session.migrationReady}}
        check(NativeMigrationCandidateStore(ui.activity,ui.activity.session.migrationStorage).currentReceipt()==receipt)
        appOwner();ui.onNodeWithTag("migration-confirm").performScrollTo().performClick()
        await("Explicit actual UI confirmation restores",60_000){main {ui.activity.migration.phase==NativeMigrationPhase.DONE&&ui.activity.migration.result==NativeMigrationResult.IMPORTED}}
        val fresh=main {sessions+=ui.activity.session;ui.activity.session.migrationStorage};check(fresh.migrationPublicationReceipt()==receipt)
        val imported=JSONObject(checkNotNull(fresh.read("operations:${this.target}"))).getJSONArray("operations").objects()
        val originals=JSONObject(sourceOperations).getJSONArray("operations").objects()
        for(row in originals){val copy=imported.single {it.getString("id")==row.getString("id")};for(key in listOf("id","kind","target","intent","intentSha256","phase","submissionAttempted","sourceKey","sourceVersion"))check(copy.get(key)==row.get(key));check(copy.getBoolean("migrationReadOnly"))}
        check(fresh.metadata()!!.getJSONObject("drafts").getString(this.target)==originalDraft)
        check(fresh.metadata()!!.getJSONObject("drafts").getString(currentDraftKey)=="Owned current unsent $nonce")
        val probe=NativeMigrationProbe();runBlocking {NativeDraftFiles(fresh).copyTo(sourceFile.manifest,probe)}
        check(probe.size==sourceFile.manifest.sizeBytes&&probe.sha256()==sourceFile.manifest.sha256)
        check(fingerprint(uri)==bytes);assertOriginals();completed=true
    }
    @After fun finish() {
        if(!::owned.isInitialized){pass="";originalAccessibilityFlags?.let{flags->val info=i.uiAutomation.serviceInfo;info.flags=flags;i.uiAutomation.serviceInfo=info};return}
        main {ui.activity.migration.password=""};pass=""
        var removed=false
        var storageRemoved=false
        var retainedDocument: Uri?=null
        var retainedFingerprint: Pair<Long,String>?=null
        try {
            val closureOwner=owner()
            val pending=main {ui.activity.migration.picker!=null||ui.activity.migration.busy||ui.activity.migration.needsOwnerRestore}
            if(activePicker!=null||pending||closureOwner!=app) {
                completed=false
                File(owned.folder,"system-picker-caller-retained.json").writeText(JSONObject().put("originalPickerId",activePicker?.id ?: JSONObject.NULL)
                    .put("actualOwner",closureOwner ?: JSONObject.NULL).put("productCallerPending",pending).put("retainedStorage",owned.storage.coordinationKey)
                    .put("additionalBackOrControllerCancel",false).put("defaultOwnerRestored",false).put("retentionScope","storage/metadata only;JUnit Activity rule may finish caller/process")
                    .put("postRuleRuntimeSurvivalVerified",false).put("reason","Unresolved original;inspect preserved evidence;live window observation must occur inside the test method").toString())
                return
            }
            if(::previous.isInitialized) {
                // Never fabricate cancellation or replace an unresolved caller during cleanup.
                await("Owned migration must retire before restoring default owner"){main {!ui.activity.migration.busy&&ui.activity.migration.picker==null&&!ui.activity.migration.needsOwnerRestore}}
                main {ui.activity.migration.close();sessions+=ui.activity.session;ui.activity.restoreSessionForAcceptance(previous)}
            }
            runBlocking {withContext(Dispatchers.Main.immediate){for(value in sessions){value.chatNotifications?.let{it.dispose();it.stopAndJoinForMigration()};value.abandonMigrationRestore()}}}
            // Never delete a source selected by a user: only this case's newly created exact UUID
            // name under an initially empty, explicitly granted UUID directory is eligible.
            val matches=children().filter {it.first==exportName};check(matches.size<=1)
            if(matches.size==1){
                val selected=matches.single().second;check(exported==null||selected==exported)
                val observed=fingerprint(selected)
                if(exportFingerprint!=null&&observed==exportFingerprint){check(DocumentsContract.deleteDocument(i.targetContext.contentResolver,selected));removed=true}
                else {retainedDocument=selected;retainedFingerprint=observed;completed=false}
            }
            if(retainedDocument==null)check(children().isEmpty())
            else check(children().single().second==retainedDocument)
            if(::raw.isInitialized)raw.delete();main {owned.verifyDefault()}
            if(completed) {
                val root=File(owned.storage.coordinationKey).canonicalFile
                check(root.parentFile==i.targetContext.noBackupFilesDir.canonicalFile&&root.name.matches(Regex("P4-owned-control-[a-f0-9-]{36}")))
                check(!root.walkTopDown().any{java.nio.file.Files.isSymbolicLink(it.toPath())})
                sessions.map{it.migrationStorage}.distinctBy{it.coordinationKey}.forEach{it.retireWriter()}
                val roots=i.targetContext.noBackupFilesDir.listFiles().orEmpty().filter{it.name==root.name||it.name.startsWith(root.name+"-")}
                check(roots.size<=128)
                for(candidate in roots) {
                    check(candidate.canonicalFile.parentFile==i.targetContext.noBackupFilesDir.canonicalFile)
                    check(!candidate.walkTopDown().any{java.nio.file.Files.isSymbolicLink(it.toPath())})
                    check(candidate.deleteRecursively()&&!candidate.exists())
                }
                storageRemoved=true
            }
            File(owned.folder,"system-picker-result.json").writeText(JSONObject().put("completed",completed).put("kind",if(requests.length()>0)"real-system-SAF-UI"else"environment-precondition").put("realSystemPickerObserved",requests.length()>0).put("app",app).put("docsUi",docs).put("requests",requests)
                .put("exportBytes",exportFingerprint?.first?:0).put("exportSha256",exportFingerprint?.second).put("sourceBytesPreserved",originalsVerified).put("syntheticOriginals",true).put("realHostOrModel",false)
                .put("transport","owned loopback workspace component fixture").put("ownDocumentRemoved",removed).put("syntheticStorageRemoved",storageRemoved).put("syntheticStorageRetainedForFailure",!completed).put("retainedOwnedDocument",retainedDocument?.toString()).put("retainedDocumentBytes",retainedFingerprint?.first).put("retainedDocumentSha256",retainedFingerprint?.second).put("noPasswordOrScreenshots",true).put("noOperationQueriesOrWrites",server.requests.all{it.method=="GET"&&it.path=="/v1/workspace"}).toString())
            check(retainedDocument==null){"Owned document fingerprint changed; retained for parent diagnosis"}
        }catch(failure: Throwable){completed=false;throw failure}
        finally {
            if(::server.isInitialized)server.close()
            File(owned.folder,"system-picker-cleanup.json").writeText(JSONObject().put("completed",completed).put("requestedOwnName",exportName)
                .put("retainedSyntheticStoragePrefix",if(!storageRemoved)owned.storage.coordinationKey else JSONObject.NULL).put("ownTreeUri",if(::tree.isInitialized)tree.toString()else JSONObject.NULL).put("ownDocumentRemoved",removed).put("syntheticStorageRemoved",storageRemoved)
                .put("retainedDocument",retainedDocument?.toString()).put("retainedDocumentSha256",retainedFingerprint?.second).put("actualOwnerAtClosure",owner()).put("passwordCleared",true)
                .put("retentionScope","storage/metadata only;JUnit Activity rule may finish caller/process").put("postRuleRuntimeSurvivalVerified",false).toString())
            originalAccessibilityFlags?.let{flags->val info=i.uiAutomation.serviceInfo;info.flags=flags;i.uiAutomation.serviceInfo=info}
        }
    }
}
