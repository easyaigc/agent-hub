package com.agenthub.android

import android.content.Intent
import android.graphics.Bitmap
import android.provider.Settings
import androidx.test.core.app.ActivityScenario
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import org.junit.runner.RunWith
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.UUID

/** New task-owned installation. Fresh explicit binding, no original-store mount or input send.
 * A phone content/UI witness; original desktop owner execution needs independent evidence.
 */
@RunWith(AndroidJUnit4::class)
class MobileIsolatedSubagentReadLiveTest {
    @get:Rule val ui=createEmptyComposeRule()
    @get:Rule val limit=Timeout.seconds(420)
    private val i get()=InstrumentationRegistry.getInstrumentation()
    private val context get()=i.targetContext
    private fun digest(bytes: ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)}
    private fun digest(text: String)=digest(text.toByteArray(Charsets.UTF_8))
    private fun uuid(value: String)=value.also { require(UUID.fromString(it).toString()==it) }
    private fun hex(value: String)=value.also { require(it.matches(Regex("[a-f0-9]{64}"))) }
    private fun fileDigest(file: File): String {
        val hash=MessageDigest.getInstance("SHA-256")
        file.inputStream().use { stream -> val bytes=ByteArray(65536);while(true){val n=stream.read(bytes);if(n<0)break;hash.update(bytes,0,n)} }
        return hash.digest().joinToString(""){"%02x".format(it)}
    }
    private fun privateJson(file: File,expectedSha: String): JSONObject {
        require(file.canonicalFile==file.absoluteFile&&!Files.isSymbolicLink(file.toPath()))
        val fd=android.system.Os.open(file.path,android.system.OsConstants.O_RDONLY or android.system.OsConstants.O_NOFOLLOW,0)
        val bytes=try {
            val before=android.system.Os.fstat(fd)
            require(android.system.OsConstants.S_ISREG(before.st_mode)&&before.st_uid==android.os.Process.myUid()&&
                before.st_nlink==1L&&before.st_mode and 0x3f==0&&before.st_size in 1..65536)
            val raw=ByteArray(before.st_size.toInt());var offset=0
            while(offset<raw.size){val count=android.system.Os.read(fd,raw,offset,raw.size-offset);require(count>0);offset+=count}
            require(android.system.Os.read(fd,ByteArray(1),0,1)==0)
            val after=android.system.Os.fstat(fd);val path=android.system.Os.lstat(file.path)
            require(before.st_dev==after.st_dev&&before.st_ino==after.st_ino&&before.st_size==after.st_size&&before.st_mtime==after.st_mtime&&
                path.st_dev==before.st_dev&&path.st_ino==before.st_ino&&path.st_size==before.st_size&&path.st_mode==before.st_mode)
            raw
        } finally {android.system.Os.close(fd)}
        require(digest(bytes)==hex(expectedSha))
        return JSONObject(bytes.toString(Charsets.UTF_8))
    }
    private fun plan(): Pair<JSONObject,File> {
        val args=InstrumentationRegistry.getArguments()
        require(args.getString("q02LeaseReady")=="1") { "Explicit controller device lease required" }
        require(BuildConfig.DEBUG&&context.packageName=="com.easyai.agenthub.android.acceptance")
        require(i.context.packageName==context.packageName+".test")
        val run=uuid(checkNotNull(args.getString("q02Run")))
        val folder=File(context.filesDir.canonicalFile,"acceptance/q02-real-dev/$run")
        require(folder.canonicalFile==folder.absoluteFile&&folder.isDirectory)
        val p=privateJson(File(folder,"plan.json"),checkNotNull(args.getString("q02PlanSha256")))
        require(p.getInt("version")==2&&p.getString("run")==run&&p.getBoolean("isolatedTargetApproved"))
        require(p.getString("startupMode")=="new-isolated-installation"&&args.getString("q02IsolatedProfile")=="1")
        require(p.getBoolean("newBindingAuthorized")&&args.getString("q02NewBindingAuthorized")=="1")
        require(uuid(p.getString("leaseId"))==args.getString("q02LeaseId"))
        require(p.getInt("api")==android.os.Build.VERSION.SDK_INT)
        val configuration=context.resources.configuration
        require(configuration.orientation==p.getInt("orientation"))
        require(configuration.locales[0].toLanguageTag()==p.getString("languageTag"))
        require(configuration.fontScale==p.getDouble("fontScale").toFloat())
        require(digest(checkNotNull(Settings.Secure.getString(context.contentResolver,Settings.Secure.ANDROID_ID)))==hex(p.getString("installationAndroidIdSha256")))
        require(fileDigest(File(context.applicationInfo.sourceDir))==hex(p.getString("appSha256")))
        require(fileDigest(File(i.context.applicationInfo.sourceDir))==hex(p.getString("runnerSha256")))
        require(p.getString("provider") in listOf("codex","easywork"))
        require(p.getString("mode") in listOf("static","growth"))
        require(args.getString("q02UiInteractionsAuthorized")=="1"&&p.getBoolean("uiInteractionsApproved"))
        require(p.getString("clipboardPolicy")=="empty-only"&&p.getString("copyMenuLabel")==context.getString(android.R.string.copy))
        require(p.getInt("waitSeconds") in 1..180)
        // Control Host and Provider identity are distinct; the UUID-only recorder is Codex-only.
        uuid(p.getString("controlHostId"));hex(p.getString("localMappingSha256"))
        require(p.getString("controlHostId")==args.getString("q02AuthorizedControlHostId"))
        require(p.getString("threadId").isNotBlank())
        if(p.getString("provider")=="codex")uuid(p.getString("threadId"))
        if(p.getString("mode")=="growth") {
            require(args.getString("q02GrowthAuthorized")=="1")
            require(p.getString("modelOperationId").isNotBlank()&&p.getString("modelOperationId")==args.getString("q02ModelOperationId"))
        }
        val target=checkNotNull(HostReadCodec.target(p.getJSONObject("target")))
        require(target.agentId==p.getString("provider"))
        if(target.agentId=="codex") {
            uuid(target.hostId)
            require(target.workspaceId==target.hostId&&target.runtimeId=="desktop-runtime")
        }
        require(com.agenthub.core.DomainContract.validate(p.getJSONObject("target").toString(),"target")==null)
        val excluded=p.getJSONArray("excludedCodingThreadIds").strings()
        require(excluded.size==6&&excluded.distinct().size==6&&p.getString("threadId") !in excluded)
        val before=p.getJSONObject("before")
        require(sameScope(HostReadCodec.target(before.getJSONObject("target")),target))
        for(key in listOf("threadId","messageId","rootRunId","childId"))require(before.getString(key)==p.getString(key)&&p.getString(key).isNotBlank())
        require(before.getInt("detailUtf8Bytes")>0&&before.getInt("detailUtf16Units")>0&&before.getString("status").isNotBlank())
        for(key in listOf("detailSha256","prefixSha256","middleSha256","suffixSha256","sourceFileSha256"))hex(before.getString(key))
        require(before.has("historyRevision")&&before.has("epoch")&&before.has("cursor"))
        return p to folder
    }
    private fun publish(folder: File,name: String,value: JSONObject) {
        val file=File(folder,name);require(!file.exists()) { "Prior result must be reconciled, not overwritten" }
        check(file.createNewFile());android.system.Os.chmod(file.path,0x180)
        file.writeText(value.toString(),Charsets.UTF_8)
    }
    private fun windowHash(text: String,start: Int,count: Int): String {
        val a=text.offsetByCodePoints(0,start);val b=text.offsetByCodePoints(a,count);return digest(text.substring(a,b))
    }
    private fun checkBody(text: String,expected: JSONObject) {
        assertEquals("Q02 UTF-8 length",expected.getInt("detailUtf8Bytes"),text.toByteArray(Charsets.UTF_8).size)
        assertEquals("Q02 UTF-16 length",expected.getInt("detailUtf16Units"),text.length)
        assertEquals("Q02 complete digest",hex(expected.getString("detailSha256")),digest(text))
        val points=text.codePointCount(0,text.length);val width=minOf(512,points)
        assertEquals("Q02 prefix",hex(expected.getString("prefixSha256")),windowHash(text,0,width))
        assertEquals("Q02 middle",hex(expected.getString("middleSha256")),windowHash(text,(points-width)/2,width))
        assertEquals("Q02 suffix",hex(expected.getString("suffixSha256")),windowHash(text,points-width,width))
    }
    @Test fun newInstallationProductionCardExpandsAndRefreshesWithoutSendingInput() {
        val (p,folder)=plan()
        val pairing=privateJson(File(folder,"new-binding-input.json"),p.getString("pairingFileSha256")).getString("input")
        require(digest(pairing)==p.getString("pairingIntentSha256"))
        val targetScope=checkNotNull(HostReadCodec.target(p.getJSONObject("target")))
        val permit=Q02RealReadInstallation.Permit(p.getString("run"),p.getString("leaseId"),p.getString("installationAndroidIdSha256"),
            p.getString("appSha256"),p.getString("runnerSha256"),p.getString("signerSha256"),p.getInt("api"),
            android.os.SystemClock.elapsedRealtime()+15*60_000,p.getString("controlHostId"),p.getString("localMappingSha256"),targetScope,p.getString("endpoint"),
            p.getJSONArray("allowedEndpoints").strings(),p.getString("certificateSha256"),p.getString("pairingIntentSha256"),if(p.getString("provider")=="easywork")p.getString("desktopPublicKeySha256") else null)
        lateinit var profile: Q02RealReadInstallation.Profile
        try {i.runOnMainSync {
            val lifecycle=ActivityLifecycleMonitorRegistry.getInstance()
            require(Stage.values().filter {it!=Stage.DESTROYED}.all {stage->lifecycle.getActivitiesInStage(stage).none {it is MainActivity}}) {
                "Existing Activity owner must be left untouched; no new profile launch in this process"
            }
            profile=Q02RealReadInstallation.prepare(context,permit)
        }} catch(error: Throwable) {
            publish(folder,"profile-prepare-failed.json",Q02RealReadInstallation.preparationProof().put("failureClass",error.javaClass.name))
            throw error
        }
        publish(folder,"profile-prepared.json",profile.proof().put("newModelInputs",0))
        var scenario: ActivityScenario<Q02RealReadActivity>?=null
        try {
            val launched=ActivityScenario.launch<Q02RealReadActivity>(Intent(context,Q02RealReadActivity::class.java).setAction(Intent.ACTION_MAIN))
            scenario=launched
            lateinit var main: Q02RealReadActivity
            launched.onActivity {main=it}
            val session=main.session
            ui.waitUntil(30000){!session.restoring}
            require(session.clients.none {!it.isDemo}&&session.operationRecords.isEmpty()&&session.text.isEmpty())
            publish(folder,"empty-installation-before-new-binding.json",profile.proof().put("savedClients",0).put("originalCredentialsCopied",false))
            // New device claim/binding uses the original production path. No token/grant is
            // loaded from the phone's ordinary installation or the host connection catalogue.
            runBlocking {withTimeout(120_000){withContext(Dispatchers.Main){Q02RealReadInstallation.pairFreshTarget(profile,pairing)}}}
            ui.waitUntil(30000){!session.loading&&!session.historyLoading}
            profile.checkOnlyApprovedTarget()
            val thread=p.getString("threadId")
            require(thread !in p.getJSONArray("excludedCodingThreadIds").strings())
            var pages=0
            while(session.threads.none {it.id==thread}&&session.workspace?.nextThreadCursor!=null&&pages<10) {
                ui.runOnIdle {session.refresh(more=true)}
                ui.waitUntil(30000){!session.loading};pages++
            }
            ui.runOnIdle {session.selectThread(session.threads.single {it.id==thread})}
            inspectProductionCard(p,folder,main,profile)
        } finally {
            scenario?.close()
            if(profile.destroyed)runBlocking {withContext(Dispatchers.Main){Q02RealReadInstallation.close(profile)}}
            publish(folder,"profile-resource-state.json",profile.proof().put("retentionReason","Controller must save evidence and revoke only this newly created binding before deleting this profile/alias and new-binding-input.json"))
        }
    }
    private fun inspectProductionCard(p: JSONObject,folder: File,main: Q02RealReadActivity,profile: Q02RealReadInstallation.Profile) {
        val session=main.session
        val expectedScope=profile.permit.target
        ui.waitUntil(30000){!session.restoring&&!session.historyLoading}
        fun target() {
            profile.checkOnlyApprovedTarget()
            require(sameScope(session.selectedClient.scope,expectedScope)&&session.selectedClient.id==expectedScope.hostId)
            require(session.selectedThread==p.getString("threadId")) { "Production selection must remain on the frozen business parent" }
        }
        target()
        require(session.runtime?.canControl==true) { "History alone is not an original owner readiness proof" }
        val key=session.targetKey;val draft=digest(session.text)
        fun intents()=session.operationRecords.map { it.target+"/"+it.id+"/"+it.intentSha256 }.sorted()
        val initialIntents=intents()
        require(session.operationRecords.all { it.phase in listOf("confirmed","failed","rejected")||it.detached })
        val diagnosticTimingSupported=p.getString("provider")=="codex"
        if(diagnosticTimingSupported)NativeReadAcceptanceTiming.configure(profile.runtimeContext,Intent().putExtra(NativeReadAcceptanceTiming.ENABLED,true)
            .putExtra(NativeReadAcceptanceTiming.HOST,session.selectedClient.id).putExtra(NativeReadAcceptanceTiming.RUN,p.getString("run")))
        val timing=File(profile.runtimeContext.cacheDir,"native-read-${p.getString("run")}.jsonl")
        fun applied()=if(!diagnosticTimingSupported||!timing.exists())0 else timing.readLines().count { line -> runCatching {val row=JSONObject(line);row.optString("event")=="history-applying"&&row.optString("threadId")==p.getString("threadId")}.getOrDefault(false) }
        val id=p.getString("messageId");val detailTag="subagent-detail-$id";val toggleTag="subagent-expand-$id"
        fun card()=session.messages.single { it.id==id&&it.kind=="subAgent" }.also { require(it.runId==p.getString("rootRunId")) }
        fun rendered()=ui.onNodeWithTag(detailTag).fetchSemanticsNode().config[SemanticsProperties.Text].single().text
        val expandedState=p.getString("expandedStateDescription");val collapsedState=p.getString("collapsedStateDescription")
        require(expandedState.isNotBlank()&&collapsedState.isNotBlank()&&expandedState!=collapsedState)
        fun state()=ui.onNodeWithTag(toggleTag).fetchSemanticsNode().config[SemanticsProperties.StateDescription]
        var expectedToggleState: String?=null
        fun toggle(expected: String) {
            expectedToggleState=expected
            target();card()
            ui.onNodeWithTag(toggleTag).performScrollTo().performTouchInput {click()}
            // The same LazyColumn item can leave the composed viewport after its height changes.
            ui.onNodeWithTag("message-list").performScrollToNode(hasTestTag(toggleTag))
            target();card()
            ui.onNodeWithTag(toggleTag).assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription,expected))
        }
        fun ownFocusedActivity() {
            target()
            i.runOnMainSync {
                require(ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).singleOrNull()===main)
                require(main.packageName==context.packageName&&!main.isFinishing&&!main.isDestroyed)
                require(main.window.decorView.isAttachedToWindow&&main.hasWindowFocus()&&main.window.decorView.hasWindowFocus())
            }
        }
        fun shot(name: String) {
            ui.waitUntil(5000){i.uiAutomation.rootInActiveWindow?.packageName?.toString()==context.packageName}
            val root=checkNotNull(i.uiAutomation.rootInActiveWindow)
            require(root.packageName?.toString()==context.packageName)
            val nodes=org.json.JSONArray()
            fun visit(node: android.view.accessibility.AccessibilityNodeInfo,depth: Int) {
                require(depth<=64&&nodes.length()<4096) { "Explicit AX capture bound exceeded" }
                require(node.packageName?.toString() in listOf(null,context.packageName))
                val bounds=android.graphics.Rect();node.getBoundsInScreen(bounds)
                val text=node.text?.toString().orEmpty();val description=node.contentDescription?.toString().orEmpty()
                nodes.put(JSONObject().put("className",node.className?.toString()).put("viewId",node.viewIdResourceName)
                    .put("bounds",bounds.toShortString()).put("textSha256",digest(text)).put("textUtf8Bytes",text.toByteArray(Charsets.UTF_8).size)
                    .put("contentDescriptionSha256",digest(description)).put("visible",node.isVisibleToUser)
                    .put("enabled",node.isEnabled).put("clickable",node.isClickable).put("selected",node.isSelected).put("scrollable",node.isScrollable))
                for(index in 0 until node.childCount)node.getChild(index)?.let { child ->try{visit(child,depth+1)}finally{child.recycle()} }
            }
            try{visit(root,0)}finally{root.recycle()}
            require(i.uiAutomation.rootInActiveWindow?.packageName?.toString()==context.packageName)
            val file=File(folder,"$name.png");require(!file.exists());check(file.createNewFile());android.system.Os.chmod(file.path,0x180)
            val image=checkNotNull(i.uiAutomation.takeScreenshot())
            try { file.outputStream().use {check(image.compress(Bitmap.CompressFormat.PNG,100,it))} } finally { image.recycle() }
            require(i.uiAutomation.rootInActiveWindow?.packageName?.toString()==context.packageName)
            publish(folder,"$name-ax.json",JSONObject().put("capturedUtcMillis",System.currentTimeMillis()).put("package",context.packageName)
                .put("screenshotSha256",fileDigest(file)).put("textStoredAsHashes",true).put("nodes",nodes)
                .put("fullLongBodyVisualCoverageVerified",false))
        }
        fun failureCapture(name: String) {
            ownFocusedActivity()
            ui.waitUntil(5000){i.uiAutomation.rootInActiveWindow?.packageName?.toString()==context.packageName}
            val root=checkNotNull(i.uiAutomation.rootInActiveWindow)
            require(root.packageName?.toString()==context.packageName)
            val activeWindowId=root.windowId
            val nodes=org.json.JSONArray()
            fun visit(node: android.view.accessibility.AccessibilityNodeInfo,depth: Int) {
                require(depth<=64&&nodes.length()<4096) { "Explicit AX capture bound exceeded" }
                require(node.packageName?.toString() in listOf(null,context.packageName))
                val bounds=android.graphics.Rect();node.getBoundsInScreen(bounds)
                val text=node.text?.toString().orEmpty();val description=node.contentDescription?.toString().orEmpty()
                nodes.put(JSONObject().put("className",node.className?.toString()).put("viewId",node.viewIdResourceName)
                    .put("bounds",bounds.toShortString()).put("textSha256",digest(text)).put("textUtf8Bytes",text.toByteArray(Charsets.UTF_8).size)
                    .put("contentDescriptionSha256",digest(description)).put("visible",node.isVisibleToUser)
                    .put("enabled",node.isEnabled).put("clickable",node.isClickable).put("selected",node.isSelected).put("scrollable",node.isScrollable))
                for(index in 0 until node.childCount)node.getChild(index)?.let { child ->try{visit(child,depth+1)}finally{child.recycle()} }
            }
            try{visit(root,0)}finally{root.recycle()}
            ownFocusedActivity()
            require(i.uiAutomation.rootInActiveWindow?.windowId==activeWindowId)
            val view=main.window.decorView
            require(view.width in 1..4096&&view.height in 1..4096)
            val image=Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888)
            val latch=java.util.concurrent.CountDownLatch(1);var copyResult=-1
            try {
                i.runOnMainSync {android.view.PixelCopy.request(main.window,image,{copyResult=it;latch.countDown()},android.os.Handler(android.os.Looper.getMainLooper()))}
                require(latch.await(5,java.util.concurrent.TimeUnit.SECONDS)&&copyResult==android.view.PixelCopy.SUCCESS)
                ownFocusedActivity()
                require(i.uiAutomation.rootInActiveWindow?.packageName?.toString()==context.packageName&&i.uiAutomation.rootInActiveWindow?.windowId==activeWindowId)
                val file=File(folder,"$name.png");require(!file.exists());check(file.createNewFile());android.system.Os.chmod(file.path,0x180)
                file.outputStream().use {check(image.compress(Bitmap.CompressFormat.PNG,100,it))}
            } finally {image.recycle()}
            val file=File(folder,"$name.png")
            publish(folder,"$name-ax.json",JSONObject().put("capturedUtcMillis",System.currentTimeMillis()).put("package",context.packageName)
                .put("screenshotSha256",fileDigest(file)).put("textStoredAsHashes",true).put("nodes",nodes)
                .put("fullLongBodyVisualCoverageVerified",false).put("imageSource","PixelCopy exact original Q02 Activity window").put("activeWindowId",activeWindowId))
        }
        fun failureDiagnostic(failure: Throwable) {
            val value=JSONObject().put("originalFailureClass",failure.javaClass.name).put("originalFailureMessageSha256",digest(failure.message.orEmpty()))
                .put("messageId",id).put("rootRunId",p.getString("rootRunId")).put("expectedTargetKey",key)
                .put("toggleTag",toggleTag).put("detailTag",detailTag).put("expectedToggleState",expectedToggleState?:JSONObject.NULL)
                .put("capturedElapsedRealtimeMs",android.os.SystemClock.elapsedRealtime()).put("newModelInputs",0)
            val own=runCatching {ownFocusedActivity();true}.getOrDefault(false)
            value.put("ownFocusedTargetActivity",own).put("noWholeDisplayFallback",true)
            if(own) {
                ui.runOnIdle {
                    target()
                    value.put("actualTargetKey",session.targetKey).put("selectedThread",session.selectedThread)
                        .put("initialDraftSha256",draft).put("actualDraftSha256",digest(session.text))
                        .put("initialOriginalIntents",org.json.JSONArray(initialIntents)).put("actualOriginalIntents",org.json.JSONArray(intents()))
                        .put("originalCardCount",session.messages.count {it.id==id&&it.kind=="subAgent"})
                }
                val tags=org.json.JSONArray()
                for(tag in listOf(toggleTag,detailTag)) {
                    val row=JSONObject().put("tag",tag)
                    runCatching {
                        ownFocusedActivity()
                        val found=ui.onAllNodesWithTag(tag).fetchSemanticsNodes()
                        row.put("count",found.size).put("states",org.json.JSONArray(found.map {node ->
                            if(node.config.contains(SemanticsProperties.StateDescription))node.config[SemanticsProperties.StateDescription] else JSONObject.NULL
                        }))
                    }.onFailure {row.put("readFailureClass",it.javaClass.name)}
                    tags.put(row)
                }
                value.put("exactTags",tags)
                runCatching {failureCapture("failure-original-card")}.onFailure {value.put("captureSkippedOrFailedClass",it.javaClass.name)}
            }
            publish(folder,"failure-original-card.json",value)
        }
        fun snapshot(expected: JSONObject,stage: String) {
            target();val c=card();val detail=checkNotNull(c.detail)
            require(c.status==expected.getString("status"));checkBody(detail,expected)
            checkBody(rendered(),expected)
            var header: MobileHistory?=null
            ui.waitUntil(15000) {
                runCatching {
                    NativeSessionStorage.ExistingReader.open(profile.runtimeContext,"q02-session",profile.alias).use { reader ->
                        val envelope=JSONObject(reader.text("history:$key",1024*1024))
                        require(envelope.getInt("version")==2&&envelope.getString("targetKey")==key)
                        val page=MobileSnapshotCodec.history(envelope.getJSONObject("history").toString())
                        require(page.threadId==p.getString("threadId")&&sameScope(page.scope,expectedScope))
                        require(page.historyRevision==if(expected.isNull("historyRevision"))null else expected.getLong("historyRevision"))
                        if(!expected.isNull("epoch"))require(page.epoch==expected.getString("epoch"))
                        if(!expected.isNull("cursor"))require(checkNotNull(page.cursor)>=expected.getLong("cursor"))
                        header=page
                    }
                    true
                }.getOrDefault(false)
            }
            val layouts=mutableListOf<TextLayoutResult>()
            ui.onNodeWithTag(detailTag).performSemanticsAction(SemanticsActions.GetTextLayoutResult){assertTrue(it(layouts))}
            assertFalse("Q02 original layout overflow",layouts.single().hasVisualOverflow)
            assertEquals(detail.length,layouts.single().getLineEnd(layouts.single().lineCount-1,visibleEnd=false))
            publish(folder,"$stage.json",JSONObject().put("messageId",id).put("rootRunId",c.runId).put("status",c.status)
                .put("childId",p.getString("childId")).put("detailSha256",digest(detail)).put("detailUtf8Bytes",detail.toByteArray().size)
                .put("historyRevision",checkNotNull(header).historyRevision?:JSONObject.NULL).put("epoch",header?.epoch).put("cursor",header?.cursor)
                .put("runtimeTurnId",session.runtime?.turnId).put("parentCanControl",session.runtime?.canControl)
                .put("historyApplicationsObserved",if(diagnosticTimingSupported)applied() else JSONObject.NULL).put("nativeTimingRecorderSupported",diagnosticTimingSupported).put("phoneContentMatched",true)
                .put("layoutLines",layouts.single().lineCount).put("layoutEndUtf16",layouts.single().getLineEnd(layouts.single().lineCount-1,visibleEnd=false))
                .put("independentChildOwnerExecutionVerified",false))
        }
        var originalFailure: Throwable?=null
        try {
            ui.waitUntil(30000){runCatching {card().detail?.isNotEmpty()==true}.getOrDefault(false)}
            ui.onNodeWithTag("message-list").performScrollToNode(hasTestTag(toggleTag))
            // Actual SDK touch injection: a physical permission refusal is a failure, no semantics-click fallback.
            if(state()==expandedState)toggle(collapsedState) else require(state()==collapsedState)
            toggle(expandedState)
            snapshot(p.getJSONObject("before"),"expanded-before");shot("expanded-before")
            toggle(collapsedState);shot("collapsed")
            toggle(expandedState)
            publish(folder,"ready-for-desktop-after.json",JSONObject().put("provider",p.getString("provider")).put("initialContentVerified",true))
            val afterFile=File(folder,"desktop-after.json")
            ui.waitUntil(p.getInt("waitSeconds")*1000L){afterFile.isFile&&File(folder,"desktop-after.sha256").isFile}
            val hashFile=File(folder,"desktop-after.sha256")
            require(hashFile.canonicalFile==hashFile.absoluteFile&&!Files.isSymbolicLink(hashFile.toPath())&&hashFile.length() in 64..65)
            val after=privateJson(afterFile,hashFile.readText().trim())
            require(sameScope(HostReadCodec.target(after.getJSONObject("target")),expectedScope))
            require(after.getString("threadId")==p.getString("threadId")&&after.getString("messageId")==id&&after.getString("rootRunId")==p.getString("rootRunId"))
            require(after.getString("childId")==p.getString("childId"))
            if(p.getString("mode")=="growth") {
                require(after.getString("detailSha256")!=p.getJSONObject("before").getString("detailSha256"))
                require(after.getInt("detailUtf8Bytes")>p.getJSONObject("before").getInt("detailUtf8Bytes"))
            }
            fun cacheGeneration()=NativeSessionStorage.ExistingReader.open(profile.runtimeContext,"q02-session",profile.alias).use {
                JSONObject(it.text("history:$key",1024*1024)).getString("generation")
            }
            val priorGeneration=cacheGeneration()
            val count=applied()
            ui.onNodeWithTag("chat-menu").performTouchInput {click()};ui.onNodeWithTag("refresh-history").performTouchInput {click()}
            ui.waitUntil(45000){(if(diagnosticTimingSupported)applied()>count else runCatching {cacheGeneration()!=priorGeneration}.getOrDefault(false))&&!session.historyLoading&&runCatching {digest(card().detail.orEmpty())==after.getString("detailSha256")}.getOrDefault(false)}
            ui.onNodeWithTag("message-list").performScrollToNode(hasTestTag(detailTag))
            require(state()==expandedState) { "Production refresh lost the original card expansion state" }
            snapshot(after,"expanded-after-refresh");shot("expanded-after-refresh")
            Q02NativeSubagentInteractions(ui,main,folder,p,after,
                {target();require(card().status==after.getString("status")&&digest(checkNotNull(card().detail))==after.getString("detailSha256"));
                    require(session.targetKey==key&&digest(session.text)==draft&&intents()==initialIntents)},
                ::shot,{name,value->publish(folder,name,value)}).verify()
            require(session.targetKey==key&&digest(session.text)==draft&&intents()==initialIntents)
            profile.checkOnlyApprovedTarget()
            publish(folder,"complete.json",JSONObject().put("passed",true).put("provider",p.getString("provider")).put("productionActivity",main.javaClass.name)
                .put("taskOwnedInstallation",true).put("selectedDraftAndObservedOperationIntentsPreserved",true)
                .put("ordinaryInstallationMounted",false).put("nativeSystemIntegrationsCovered",false).put("newModelInputs",0)
                .put("actualTouchAndProductionRefresh",true).put("nativeLongBodySelectionCopyAndReturns",true).put("nativeTimingRecorderSupported",diagnosticTimingSupported)
                .put("providerReadRequestCorrelationExternal",!diagnosticTimingSupported).put("independentDesktopOwnerGateStillRequired",true))
        } catch(failure: Throwable) {
            originalFailure=failure
            runCatching {failureDiagnostic(failure)}
            throw failure
        } finally {
            // Preserve the original failure even if the unchanged cleanup/invariant checks also fail.
            try {
                NativeReadAcceptanceTiming.configure(profile.runtimeContext,null)
                require(session.targetKey==key&&digest(session.text)==draft&&intents()==initialIntents)
                profile.checkOnlyApprovedTarget()
            } catch(cleanup: Throwable) {
                val original=originalFailure
                if(original==null)throw cleanup else original.addSuppressed(cleanup)
            }
        }
    }
}
