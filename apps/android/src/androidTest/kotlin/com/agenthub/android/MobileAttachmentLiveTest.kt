package com.agenthub.android

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.ParcelFileDescriptor
import androidx.compose.ui.test.*
import androidx.activity.compose.setContent
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.net.HttpURLConnection
import java.net.URI

/** Opt-in owned physical-device acceptance. Each new input needs a one-use independent lease. */
class MobileAttachmentLiveTest {
    @get:Rule(order=0) val foreground: org.junit.rules.TestRule=NativeTestForeground()
    @get:Rule(order=1) val ui=createAndroidComposeRule<MainActivity>()
    private lateinit var activity: MainActivity
    private var tracked: MainActivity?=null
    private var isolated: MobileSession?=null
    private val session get()=isolated ?: activity.session
    private var defaultDrafts=emptyMap<String,String?>()
    private val i get()=InstrumentationRegistry.getInstrumentation()
    private val config by lazy { JSONObject(File(i.targetContext.filesDir,"mobile-attachment-config.json").readText()) }
    private val nonce get()=config.getString("nonce")
    private val storage get()=NativeSessionStorage(activity,if(config.optBoolean("systemPicker")) "P4-attachment-picker-"+nonce else "mobile-v1")
    private fun main(block: suspend ()->Unit)=runBlocking { withContext(Dispatchers.Main) { block() } }
    private fun bind() {
        require(i.targetContext.packageName=="com.easyai.agenthub.android.acceptance")
        activity=ui.activity;tracked=activity
        if(config.optBoolean("systemPicker")) {
            ui.waitUntil(30_000) { !activity.session.restoring }
            ui.runOnIdle {
                activity.readNetworkMonitor.stop();activity.session.discovery?.stop();activity.session.easyWorkDiscovery?.stop();activity.session.leaveForeground()
                val metadata=NativeSessionStorage(activity).metadata()
                defaultDrafts=listOf("drafts","draftFiles","draftVersions","attachmentImportGenerations").associateWith { metadata?.opt(it)?.toString() }
                isolated=MobileSession(storage);activity.setContent { AgentHubApp(session) { } };session.restore()
            }
            ui.waitUntil(30_000) { !session.restoring }
        }
    }
    private fun control(route: String,value: JSONObject): JSONObject {
        require(route in listOf("warm-ready","warm-reply-ready","cold-preflight","claim-case","case-complete","picker-gate"))
        val uri=URI(config.getString("control"));require(uri.scheme=="http"&&uri.host=="127.0.0.1"&&uri.rawPath.isEmpty())
        val http=uri.resolve("/$route").toURL().openConnection() as HttpURLConnection
        try {
            http.requestMethod="POST";http.instanceFollowRedirects=false;http.connectTimeout=10_000;http.readTimeout=60_000
            http.setRequestProperty("Connection","close");http.setRequestProperty("Authorization","Bearer "+config.getString("controlKey"));http.setRequestProperty("Content-Type","application/json")
            val bytes=value.put("nonce",nonce).toString().toByteArray();http.doOutput=true;http.setFixedLengthStreamingMode(bytes.size)
            http.outputStream.use { it.write(bytes) };assertEquals("Independent exact attachment witness",200,http.responseCode)
            return JSONObject(http.inputStream.bufferedReader().use { it.readText() })
        }finally{http.disconnect()}
    }
    private fun evidenceFolder()=File(i.targetContext.filesDir,"acceptance/P4-attachment-live/$nonce").apply { mkdirs() }
    private fun shot(name: String) {
        ui.waitForIdle();i.waitForIdleSync()
        assertEquals(i.targetContext.packageName,i.uiAutomation.rootInActiveWindow?.packageName?.toString())
        ui.waitUntil(10_000) { var focused=false;i.runOnMainSync { focused=activity.window.decorView.hasWindowFocus() };focused }
        var bitmap: Bitmap?=null
        i.runOnMainSync { assertTrue(activity.window.decorView.hasWindowFocus());bitmap=Bitmap.createBitmap(activity.window.decorView.width,activity.window.decorView.height,Bitmap.Config.ARGB_8888) }
        val image=checkNotNull(bitmap);val done=java.util.concurrent.CountDownLatch(1);var result=-1
        try {
            i.runOnMainSync { android.view.PixelCopy.request(activity.window,image,{ value->result=value;done.countDown() },android.os.Handler(android.os.Looper.getMainLooper())) }
            assertTrue(done.await(10,java.util.concurrent.TimeUnit.SECONDS));assertEquals(android.view.PixelCopy.SUCCESS,result)
            File(evidenceFolder(),"$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG,100,it) }
            File(evidenceFolder(),"$name-window.json").writeText(JSONObject().put("method","PixelCopy.window").put("package",i.targetContext.packageName).put("activity",activity.javaClass.name)
                .put("width",image.width).put("height",image.height).put("pixelCopyResult",result).put("targetKey",session.targetKey).put("threadId",session.selectedThread).toString())
        }finally{image.recycle()}
    }
    private fun settings() {
        ui.waitUntil(45_000) { session.canConfigureGeneration&&session.models.isNotEmpty() }
        ui.onNodeWithTag("generation-settings").performClick()
        ui.selectNativeGeneration(config.getString("model"),"xhigh","fast","read-only")
        ui.onNodeWithTag("sheet-done").performClick()
        assertEquals(GenerationSettings(config.getString("model"),"xhigh","read-only","fast"),session.generationSettings)
    }
    private fun allowCurrentOriginalSharing() {
        val scope=AISharingScope(checkNotNull(session.selectedClient.scope),config.getString("model"))
        if(session.aiSharing.permits(scope))return
        val target=session.targetKey;val text=session.text;val files=originals(session.attachments);val records=session.operationRecords.map { it.id }.toSet()
        ui.onNodeWithTag("send-message").assertIsEnabled().performClick();ui.waitUntil(10_000) { session.aiSharingRequest!=null }
        assertEquals(target,session.aiSharingRequest!!.targetKey);assertTrue(scope.matches(session.aiSharingRequest!!.scope))
        assertEquals(text,session.text);assertEquals(files.toString(),originals(session.attachments).toString());assertEquals(records,session.operationRecords.map { it.id }.toSet())
        ui.onNodeWithTag("ai-sharing-allow").performScrollTo().performClick();ui.waitUntil(10_000) { session.aiSharingRequest==null&&!session.aiSharing.busy }
        assertTrue(session.aiSharing.permits(scope));assertEquals(target,session.targetKey);assertEquals(text,session.text)
        assertEquals(files.toString(),originals(session.attachments).toString());assertEquals(records,session.operationRecords.map { it.id }.toSet());assertNull(session.transferStatus)
    }
    private fun originals(files: List<LocalDraftFile>): JSONArray {
        val result=JSONArray()
        main { for(file in files) {
            val bytes=NativeDraftFiles(storage).bytes(file.manifest)
            result.put(JSONObject().put("file",file.manifest.body()).put("bytes",android.util.Base64.encodeToString(bytes,android.util.Base64.NO_WRAP)))
        } }
        return result
    }
    private fun prepare(index: Int) {
        val row=config.getJSONArray("cases").getJSONObject(index)
        assertTrue(session.attachments.isEmpty())
        if(config.optBoolean("systemPicker"))pickOwnedDocument(row)
        main {
            if(!config.optBoolean("systemPicker"))session.addAttachment(row.getString("fileName"),"text/plain",row.getString("fileText").toByteArray(Charsets.UTF_8))
            if(index==2) {
                val image=Bitmap.createBitmap(1100,260,Bitmap.Config.ARGB_8888);image.eraseColor(Color.WHITE)
                Canvas(image).drawText(row.getString("imageCode"),60f,165f,Paint(Paint.ANTI_ALIAS_FLAG).apply { color=Color.BLACK;textSize=100f;typeface=Typeface.create(Typeface.MONOSPACE,Typeface.BOLD) })
                val bytes=try { java.io.ByteArrayOutputStream().also { image.compress(Bitmap.CompressFormat.PNG,100,it) }.toByteArray() }finally{image.recycle()}
                session.addAttachment(row.getString("imageName"),"image/png",bytes)
            }
        }
        ui.onNodeWithTag("message-input").performTextReplacement(row.getString("text"))
        assertEquals(row.getString("text"),session.text)
        assertEquals(if(index==2)2 else 1,session.attachments.size)
        for(file in session.attachments)ui.onNodeWithTag("draft-attachment-${file.manifest.id}").assertIsDisplayed()
        if(index<2) { assertTrue(session.text.isEmpty());assertEquals("send",session.decision.action);assertTrue(session.decision.enabled) }
    }
    @After fun preserveDefaultDrafts() {
        if(!config.optBoolean("systemPicker")||!::activity.isInitialized)return
        main { session.leaveForeground() }
        val metadata=NativeSessionStorage(activity).metadata()
        for((key,value) in defaultDrafts)assertEquals("System picker preserves default $key",value,metadata?.opt(key)?.toString())
        File(evidenceFolder(),"default-storage-proof.json").writeText(JSONObject().put("draftsPreserved",true).put("draftFilesPreserved",true).put("draftVersionsPreserved",true).put("attachmentImportGenerationsPreserved",true).toString())
        i.runOnMainSync { listOfNotNull(activity,tracked).distinct().forEach { if(!it.isDestroyed&&!it.isFinishing)it.finish() } }
    }
    private fun awaitSaved() {
        val key=session.targetKey
        ui.waitUntil(20_000) { val saved=storage.metadata();saved?.optJSONObject("draftFiles")?.optJSONArray(key)?.toString()==JSONArray(session.attachments.map { it.manifest.body() }).toString()&&saved.optJSONObject("drafts")?.optString(key)==session.text&&saved.optJSONObject("generation")?.optJSONObject(key)?.optString("permissionMode")=="read-only" }
    }
    private fun pickOwnedDocument(row: JSONObject,beforeReturn: (() -> Unit)?=null,expectImport: Boolean=true,cancel: Boolean=false) {
        val handlers=setOf(config.getString("pickerPackage"))
        require(handlers.single().matches(Regex("[a-zA-Z0-9_.]+")))
        File(evidenceFolder(),"step.txt").writeText("opening-picker")
        ui.onNodeWithTag("composer-add").performClick();ui.onNodeWithTag("choose-files").performClick()
        var root: android.view.accessibility.AccessibilityNodeInfo?=null
        fun refresh(): Boolean {
            root=i.uiAutomation.rootInActiveWindow
            return root?.packageName?.toString() in handlers
        }
        val deadline=System.currentTimeMillis()+20_000
        while(!refresh()&&System.currentTimeMillis()<deadline)Thread.sleep(100)
        File(evidenceFolder(),"step.txt").writeText("picker-active=${root?.packageName}")
        assertTrue("Only the system picker launched by our owned App",refresh())
        val picker=checkNotNull(root).packageName.toString()
        fun nodes(): List<android.view.accessibility.AccessibilityNodeInfo> {
            if(!refresh())return emptyList();assertEquals(picker,root!!.packageName.toString())
            val list=mutableListOf<android.view.accessibility.AccessibilityNodeInfo>()
            fun visit(n: android.view.accessibility.AccessibilityNodeInfo) { if(n.packageName?.toString()!=picker)return;list.add(n);for(j in 0 until n.childCount)n.getChild(j)?.let { visit(it) } }
            visit(root!!);return list
        }
        fun click(node: android.view.accessibility.AccessibilityNodeInfo) {
            var n: android.view.accessibility.AccessibilityNodeInfo?=node
            while(n!=null&&!n.isClickable&&n.actionList.none { it.id==android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK })n=n.parent
            assertNotNull("Owned picker control advertises ACTION_CLICK",n)
            assertEquals(picker,n!!.packageName.toString());assertTrue(n.isEnabled&&n.isVisibleToUser)
            assertTrue(n.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK))
        }
        val label="Agent Hub acceptance files"
        fun findNode(predicate: (android.view.accessibility.AccessibilityNodeInfo)->Boolean): android.view.accessibility.AccessibilityNodeInfo {
            val end=System.currentTimeMillis()+15_000
            while(System.currentTimeMillis()<end) { nodes().firstOrNull(predicate)?.let { return it };Thread.sleep(100) }
            File(evidenceFolder(),"picker-controls.txt").writeText(nodes().joinToString("\n") { "${it.viewIdResourceName}|${it.text}|${it.contentDescription}|${it.isClickable}" })
            throw AssertionError("Expected owned system picker control not found")
        }
        if(cancel) {
            assertTrue(refresh())
            assertTrue(i.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK))
            awaitOwnedPickerReturn();return
        }
        // DocumentsUI reopens its last location. Do not reopen an already selected provider;
        // its drawer may briefly expose retired nodes while the transition is running.
        val currentNodes=nodes()
        val alreadyOwned=currentNodes.any { it.text?.toString()==label }&&currentNodes.any { it.text?.toString()==row.getString("fileName") }
        if(!alreadyOwned) {
            val drawer=findNode { it.contentDescription?.toString() in listOf("Show roots","显示根目录","Open navigation drawer","打开导航抽屉","显示导航栏") }
            click(drawer)
            click(findNode {
                var current: android.view.accessibility.AccessibilityNodeInfo?=it
                var clickable=false
                while(current!=null&&current.packageName?.toString()==picker) { if(current.isClickable)clickable=true;current=current.parent }
                it.text?.toString()==label&&clickable
            })
        }
        File(evidenceFolder(),"picker-location-${row.getInt("index")}.json").writeText(JSONObject().put("pickerPackage",picker).put("existingOwnedLocation",alreadyOwned).put("syntheticTitle",row.getString("fileName")).toString())
        var file: android.view.accessibility.AccessibilityNodeInfo?=null
        val end=System.currentTimeMillis()+15_000
        while(file==null&&System.currentTimeMillis()<end) { file=nodes().firstOrNull { it.text?.toString()==row.getString("fileName") };if(file==null)Thread.sleep(100) }
        assertNotNull(file);beforeReturn?.invoke()
        val chosen=findNode { it.text?.toString()==row.getString("fileName") }
        val chain=JSONArray();var ancestor: android.view.accessibility.AccessibilityNodeInfo?=chosen
        while(ancestor!=null&&ancestor.packageName?.toString()==picker) {
            val bounds=android.graphics.Rect();ancestor.getBoundsInScreen(bounds)
            chain.put(JSONObject().put("id",ancestor.viewIdResourceName).put("class",ancestor.className?.toString()).put("clickable",ancestor.isClickable).put("enabled",ancestor.isEnabled).put("visible",ancestor.isVisibleToUser).put("bounds",bounds.flattenToString()).put("actions",JSONArray(ancestor.actionList.map { it.id })))
            ancestor=ancestor.parent
        }
        File(evidenceFolder(),"picker-owned-row-trace.json").writeText(JSONObject().put("syntheticTitle",row.getString("fileName")).put("pickerPackage",picker).put("ancestors",chain).toString())
        click(chosen)
        // Some DocumentsUI versions keep multiple selection open until their explicit Open action.
        val confirmUntil=System.currentTimeMillis()+2000
        while(refresh()&&System.currentTimeMillis()<confirmUntil) {
            val confirm=nodes().firstOrNull { it.isEnabled&&it.text?.toString() in listOf("OPEN","Open","打开","选择","SELECT") }
            if(confirm!=null) { click(confirm);break };Thread.sleep(100)
        }
        if(!expectImport) { awaitOwnedPickerReturn();return }
        ui.waitUntil(20_000) { session.attachments.any { it.manifest.name==row.getString("fileName") } }
        val actual=session.attachments.single { it.manifest.name==row.getString("fileName") }
        main { assertArrayEquals(row.getString("fileText").toByteArray(Charsets.UTF_8),NativeDraftFiles(storage).bytes(actual.manifest)) }
        File(evidenceFolder(),"picker-${row.getInt("index")}.json").writeText(JSONObject().put("pickerPackage",picker).put("provider","test-only owned documents").put("name",actual.manifest.name).put("size",row.getString("fileText").toByteArray(Charsets.UTF_8).size).put("sdkAccessibilityClick",true).toString())
    }
    private fun awaitOwnedPickerReturn() {
        val end=System.currentTimeMillis()+20_000
        while(i.uiAutomation.rootInActiveWindow?.packageName?.toString()!=i.targetContext.packageName&&System.currentTimeMillis()<end)Thread.sleep(100)
        assertEquals(i.targetContext.packageName,i.uiAutomation.rootInActiveWindow?.packageName?.toString())
        i.waitForIdleSync();ui.waitForIdle()
    }
    private fun pickerEdges() {
        val project=config.getString("projectId");val original=session.targetKey
        val text="Unsent picker ownership $nonce";ui.onNodeWithTag("message-input").performTextReplacement(text)
        val records=session.operationRecords.map { it.id }.toSet();val beforeFiles=originals(session.attachments).toString();val beforeError=session.error
        val row=config.getJSONArray("cases").getJSONObject(0)
        fun unchanged() {
            assertEquals(original,session.targetKey);assertEquals(text,session.text);assertEquals(beforeFiles,originals(session.attachments).toString())
            assertEquals(records,session.operationRecords.map { it.id }.toSet());assertEquals(beforeError,session.error)
        }
        pickOwnedDocument(row,cancel=true);unchanged();shot("picker-cancel-preserves-draft")
        // A -> B -> A retains the valid original draft generation, matching iOS.
        fun originalFile() {
            assertEquals(original,session.targetKey);assertEquals(text,session.text)
            assertEquals(records,session.operationRecords.map { it.id }.toSet());assertEquals(beforeError,session.error)
            assertEquals(1,session.attachments.size)
            val file=session.attachments.single()
            assertEquals(row.getString("fileName"),file.manifest.name)
            main { assertArrayEquals(row.getString("fileText").toByteArray(Charsets.UTF_8),NativeDraftFiles(storage).bytes(file.manifest)) }
        }
        pickOwnedDocument(row,beforeReturn={ i.runOnMainSync { session.newChat(null);session.newChat(project) } })
        originalFile();shot("picker-late-return-restores-original-draft")
        ui.runOnIdle { session.removeAttachment(session.attachments.single().manifest.id) };unchanged()
        fun gate(action: String)=control("picker-gate",JSONObject().put("action",action))
        gate("arm")
        try {
            pickOwnedDocument(row,expectImport=false)
            val end=System.currentTimeMillis()+15_000
            while(!gate("status").getBoolean("started")&&System.currentTimeMillis()<end)Thread.sleep(100)
            assertTrue("Synthetic provider holds an actual product content read",gate("status").getBoolean("started"))
            i.runOnMainSync { session.newChat(null);session.newChat(project) }
            gate("release")
            val done=System.currentTimeMillis()+15_000
            while(!gate("status").getBoolean("finished")&&System.currentTimeMillis()<done)Thread.sleep(100)
            assertTrue(gate("status").getBoolean("finished"));i.waitForIdleSync();ui.waitForIdle()
            ui.waitUntil(20_000) { !session.importing&&session.attachments.size==1 }
            val stableUntil=System.currentTimeMillis()+1500
            while(System.currentTimeMillis()<stableUntil) { originalFile();i.waitForIdleSync();Thread.sleep(50) }
            originalFile();shot("picker-late-read-restores-original-draft")
            ui.runOnIdle { session.removeAttachment(session.attachments.single().manifest.id) };unchanged()
        }finally{gate("release")}
        File(evidenceFolder(),"picker-edges.json").writeText(JSONObject().put("cancelPreservesDraft",true).put("lateReturnKeepsOriginalDraft",true).put("lateReadKeepsOriginalDraft",true).put("businessInputCount",0).put("defaultStorageUntouched",true).toString())
        ui.onNodeWithTag("message-input").performTextReplacement("")
    }

    /** Export only the original failed examiner attempt; no editing, consent, reset or send. */
    @Test fun readOnlyOriginalAttachmentRecords() {
        bind();val name=config.getJSONArray("cases").getJSONObject(1).getString("fileName")
        fun matches()=session.operationRecords.filter { record ->
            if(record.kind!="reply") false else storage.read("submission:${record.id}")?.let { raw ->
                JSONObject(raw).getJSONArray("files").objects().any { it.getString("name")==name }
            }==true
        }
        ui.waitUntil(30_000) { matches().size==1 }
        val record=matches().single();assertEquals(config.getString("firstThreadId"),session.selectedThread)
        val source=JSONObject(checkNotNull(storage.read("submission:${record.id}")))
        assertEquals("",source.getString("text"));assertTrue(record.submissionAttempted)
        File(evidenceFolder(),"unexecuted-reply-native.json").writeText(JSONObject().put("record",record.body()).put("source",source)
            .put("threadId",session.selectedThread).put("hostId",session.selectedClient.id).put("pid",android.os.Process.myPid()).toString())
    }
    @Test fun originalAttachmentCase() {
        try { runAttachmentCase() }catch(failure: Throwable) { File(evidenceFolder(),"failure.txt").writeText(failure.stackTraceToString());throw failure }
    }
    private fun runAttachmentCase() {
        File(evidenceFolder(),"step.txt").writeText("bind")
        bind();File(evidenceFolder(),"step.txt").writeText("bound")
        val stage=InstrumentationRegistry.getArguments().getString("attachmentStage")!!.toInt()
        assertEquals(stage,config.getInt("activeCase"));require(stage in -2..2)
        if(stage==-1) {
            ui.runOnIdle { session.resetAcceptance() }
            main { session.connect("Android 附件 DEV",config.getString("endpoint"),config.getString("token")) }
            ui.waitUntil(45_000) { session.hasClient&&!session.loading&&session.workspace?.hostId==config.getString("hostId")&&session.projects.any { it.id==config.getString("projectId") } }
            ui.runOnIdle { session.newChat(config.getString("projectId")) };settings()
            val text="Attachment sharing preflight unsent $nonce"
            ui.onNodeWithTag("message-input").performTextReplacement(text)
            val records=session.operationRecords.map { it.id }
            ui.onNodeWithTag("send-message").performClick();ui.waitUntil(10_000) { session.aiSharingRequest!=null }
            assertEquals(text,session.text);assertEquals(records,session.operationRecords.map { it.id });assertNull(session.transferStatus)
            ui.onNodeWithTag("ai-sharing-allow").performScrollTo().performClick();ui.waitUntil(10_000) { session.aiSharingRequest==null&&!session.aiSharing.busy }
            assertEquals(text,session.text);assertEquals(records,session.operationRecords.map { it.id });assertNull(session.transferStatus)
            ui.onNodeWithTag("message-input").performTextReplacement("")
            if(config.optBoolean("pickerEdges"))pickerEdges()
            prepare(0)
            val files=originals(session.attachments);val key=session.targetKey
            // Observe the actual completed save, rather than sleeping before process death.
            ui.waitUntil(15_000) {
                val saved=storage.metadata()
                saved?.optJSONObject("draftFiles")?.optJSONArray(key)?.toString()==JSONArray(session.attachments.map { it.manifest.body() }).toString()&&
                    saved.optJSONObject("drafts")?.optString(key)==""&&saved.optJSONObject("generation")?.optJSONObject(key)?.optString("permissionMode")=="read-only"
            }
            val value=JSONObject().put("pid",android.os.Process.myPid()).put("targetKey",key).put("files",files).put("text",session.text).put("settings",session.generationSettings.append(JSONObject()))
            File(evidenceFolder(),"warm-original.json").writeText(value.toString())
            control("warm-ready",value);shot("warm-unsent-pure-file");return
        }
        if(stage==-2) {
            ui.waitUntil(45_000) { session.selectedThread==config.getString("firstThreadId")&&session.canCompose&&!session.loading }
            settings();prepare(1);awaitSaved()
            val value=JSONObject().put("pid",android.os.Process.myPid()).put("targetKey",session.targetKey).put("threadId",session.selectedThread).put("text",session.text).put("files",originals(session.attachments)).put("settings",session.generationSettings.append(JSONObject()))
            File(evidenceFolder(),"warm-reply-original.json").writeText(value.toString());control("warm-reply-ready",value);shot("warm-unsent-file-reply");return
        }
        if(stage==1&&config.optBoolean("resumeRemaining",false)) {
            ui.runOnIdle { session.resetAcceptance() }
            main { session.connect("Android attachment remaining DEV",config.getString("endpoint"),config.getString("token")) }
            ui.waitUntil(45_000) { session.hasClient&&!session.loading&&session.workspace?.hostId==config.getString("hostId")&&session.threads.any { it.id==config.getString("firstThreadId") } }
            ui.runOnIdle { session.selectThread(session.threads.single { it.id==config.getString("firstThreadId") }) }
        }
        ui.waitUntil(45_000) { session.hasClient&&!session.loading&&session.workspace?.hostId==config.getString("hostId")&&session.canCompose }
        if(stage==0) {
            val warm=JSONObject(File(evidenceFolder(),"warm-original.json").readText())
            assertNotEquals(warm.getInt("pid"),android.os.Process.myPid());assertEquals(warm.getString("targetKey"),session.targetKey)
            assertTrue(session.selectedThread.isEmpty());assertTrue(session.text.isEmpty());assertEquals(1,session.attachments.size)
            assertEquals(warm.getJSONArray("files").toString(),originals(session.attachments).toString())
            assertEquals(warm.getJSONObject("settings").toString(),session.generationSettings.append(JSONObject()).toString())
            assertEquals("send",session.decision.action);assertTrue(session.decision.enabled)
            ui.onNodeWithTag("draft-attachment-${session.attachments.single().manifest.id}").assertIsDisplayed();shot("cold-restored-pure-file-original-bytes")
            if(config.getBoolean("preflightOnly")) { control("cold-preflight",JSONObject().put("pid",android.os.Process.myPid()).put("targetKey",session.targetKey).put("files",originals(session.attachments)));return }
        } else {
            if(stage==1&&config.optBoolean("systemPicker")) {
                val warm=JSONObject(File(evidenceFolder(),"warm-reply-original.json").readText())
                assertNotEquals(warm.getInt("pid"),android.os.Process.myPid());assertEquals(warm.getString("targetKey"),session.targetKey)
                assertEquals(config.getString("firstThreadId"),session.selectedThread);assertTrue(session.text.isEmpty())
                assertEquals(warm.getJSONArray("files").toString(),originals(session.attachments).toString())
                assertEquals(warm.getJSONObject("settings").toString(),session.generationSettings.append(JSONObject()).toString())
                ui.onNodeWithTag("draft-attachment-${session.attachments.single().manifest.id}").assertIsDisplayed();shot("cold-restored-file-only-reply")
            } else {
                if(stage==1) { assertEquals(config.getString("firstThreadId"),session.selectedThread);settings() }
                if(stage==2) { ui.runOnIdle { session.newChat(config.getString("projectId")) };settings() }
                prepare(stage)
            }
        }
        if(stage==1&&config.optBoolean("resumeRemaining",false))allowCurrentOriginalSharing()
        assertTrue(session.aiSharing.permits(AISharingScope(checkNotNull(session.selectedClient.scope),config.getString("model"))))
        val row=config.getJSONArray("cases").getJSONObject(stage);val files=originals(session.attachments);val target=session.targetKey
        val before=session.operationRecords.map { it.id }.toSet()
        val lease=control("claim-case",JSONObject().put("index",stage).put("targetKey",target).put("threadId",session.selectedThread).put("text",session.text).put("files",files).put("pid",android.os.Process.myPid()))
        assertTrue(lease.getBoolean("ready"));ui.runOnIdle { assertEquals(target,session.targetKey);assertEquals(row.getString("text"),session.text) }
        ui.onNodeWithTag("send-message").assertIsEnabled().performClick()
        try { ui.waitUntil(180_000) { session.messages.any { it.role=="assistant"&&it.replyPhase=="final_answer"&&it.text.trim()==row.getString("expected") }&&!session.running&&session.transferStatus==null } }
        catch(failure: ComposeTimeoutException) { shot("case-$stage-unresolved");throw AssertionError("Leased original unresolved; never replay: ${session.runtimeState}; ${session.error}",failure) }
        val record=session.operationRecords.single { it.id !in before&&it.kind==(if(stage==1)"reply" else "creation") }
        assertTrue(record.submissionAttempted);assertTrue(session.text.isEmpty());assertTrue(session.attachments.isEmpty())
        val source=JSONObject(checkNotNull(storage.read("submission:${record.id}")))
        assertEquals(row.getString("text"),source.getString("text"))
        assertEquals(JSONArray(files.objects().map { it.getJSONObject("file") }).toString(),source.getJSONArray("files").toString())
        control("case-complete",JSONObject().put("index",stage).put("threadId",session.selectedThread).put("record",record.body()).put("source",source).put("pid",android.os.Process.myPid()))
        ui.onNodeWithTag("message-list").performScrollToNode(hasText(row.getString("expected")))
        ui.onAllNodesWithText(row.getString("expected")).onLast().assertIsDisplayed();shot("case-$stage-actual-content")
    }
}
