package com.agenthub.android

import android.content.Intent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.LinkAnnotation
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.commonmark.node.Link
import org.commonmark.node.Paragraph
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.security.MessageDigest

/** Opt-in actual original-owner/model file link through native UI. Never submits model input. */
class MobileResourceLiveTest {
    @get:Rule(order=0) val foreground: org.junit.rules.TestRule=NativeTestForeground()
    @get:Rule(order=1) val ui=createAndroidComposeRule<MainActivity>()
    private fun hash(bytes: ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun main(block: suspend ()->Unit)=runBlocking { withContext(Dispatchers.Main) { block() } }
    private fun awaitLocalDraftReady(owned: OwnedControlAcceptance,phase: String) {
        val session=owned.session;val destination=session.attachmentDestination()
        fun state()=JSONObject().put("canCompose",session.canCompose).put("loading",session.loading).put("historyLoading",session.historyLoading).put("importing",session.importing)
            .put("restoring",session.restoring).put("runtimeCanControl",session.runtime?.canControl).put("runtimeConnected",session.workspace?.runtimeConnected).put("online",session.selectedClient.online)
            .put("capabilities",org.json.JSONArray(session.workspace?.capabilities.orEmpty())).put("archived",session.currentChat?.archived).put("storageConflict",session.codexStorageConflictMessage).put("error",session.error)
        if(!session.canCompose)File(owned.folder,phase+"-readiness-before.json").writeText(state().toString())
        try { ui.waitUntil(20_000) { session.isCurrent(destination)&&session.canCompose&&!session.importing&&!session.restoring&&session.error==null } }
        finally { File(owned.folder,phase+"-readiness-after.json").writeText(state().put("sameOriginalDestination",session.isCurrent(destination)).toString()) }
    }
    private fun pasteOriginalFile(owned: OwnedControlAcceptance,fixture: JSONObject) {
        val activity=ui.activity;val session=owned.session
        lateinit var clipboard: android.content.ClipboardManager;var previous: android.content.ClipData?=null;var touched=false
        main { clipboard=activity.getSystemService(android.content.ClipboardManager::class.java);previous=clipboard.primaryClip }
        val eligible=previous!=null||android.os.Build.VERSION.SDK_INT>=28
        val folder=File(activity.cacheDir,"resources/paste-live-"+fixture.getString("nonce")).apply { mkdirs() }
        val local=File(folder,fixture.getString("resourceName"))
        require(local.canonicalFile.parentFile==folder.canonicalFile)
        try {
            val bytes=android.util.Base64.decode(fixture.getString("pasteBodyBase64"),android.util.Base64.NO_WRAP)
            assertEquals(fixture.getString("resourceSha256"),hash(bytes));local.writeBytes(bytes)
            val uri=androidx.core.content.FileProvider.getUriForFile(activity,activity.packageName+".resources",local)
            val key=session.targetKey;val draft=session.text;val files=session.attachments
            if(eligible) {
                val readyDestination=session.attachmentDestination()
                if(!session.canCompose)File(owned.folder,"paste-ready-before-wait.json").writeText(JSONObject().put("canCompose",session.canCompose).put("loading",session.loading).put("historyLoading",session.historyLoading).put("importing",session.importing).put("restoring",session.restoring).put("runtimeCanControl",session.runtime?.canControl).put("online",session.selectedClient.online).put("error",session.error).toString())
                ui.waitUntil(10_000) { session.isCurrent(readyDestination)&&session.canCompose }
                assertTrue("Original live target must permit local draft composition",session.canCompose)
                main { clipboard.setPrimaryClip(android.content.ClipData.newRawUri("Owned original file paste",uri));touched=true }
                File(owned.folder,"paste-window-before-action.json").writeText(JSONObject().put("activePackage",InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow?.packageName?.toString()).put("canCompose",session.canCompose).put("hasError",session.error!=null).toString())
                ui.onNodeWithTag("composer-add").assertIsEnabled().performSemanticsAction(SemanticsActions.OnClick) { it() }
                ui.waitUntil(5_000) { ui.onAllNodesWithTag("paste-files").fetchSemanticsNodes().isNotEmpty() }
                ui.onNodeWithTag("paste-files").assertIsEnabled().assertTextContains("Paste images or files").performSemanticsAction(SemanticsActions.OnClick) { it() }
                ui.waitUntil(15_000) { !session.importing&&session.attachments.size==files.size+1 }
                val added=session.attachments.single { candidate->files.none { it.manifest.id==candidate.manifest.id } }
                assertEquals(fixture.getString("resourceName"),added.manifest.name)
                assertArrayEquals(bytes,runBlocking { NativeDraftFiles(owned.storage).bytes(added.manifest) })
                ui.waitUntil(10_000) { owned.storage.read("metadata")?.contains(added.manifest.id)==true }
                assertEquals(key,session.targetKey);assertEquals(draft,session.text);assertTrue(session.operationRecords.isEmpty())
                owned.nodeShot(ui.onNodeWithTag("product-surface"),"actual-original-target-file-paste")
                main { session.removeAttachment(added.manifest.id,session.attachmentDestination()) }
                assertEquals(files,session.attachments)
            }
            File(owned.folder,"actual-native-file-paste-proof.json").writeText(JSONObject().put("originalTargetMatched",true)
                .put("systemClipboardAttempted",eligible).put("exactLocalBytesAndNameMatched",eligible).put("sourceSha256",hash(bytes)).put("durableDraftMetadataConfirmed",eligible)
                .put("api26EmptyClipboardProtected",!eligible).put("nativeSemanticsActivation",eligible).put("pointerActivation",false)
                .put("examinerRemovedOnlyAddedLocalFile",eligible).put("originalDraftTextPreserved",true).put("newModelInputs",0).put("uploadOrSubmit",false).toString())
        } finally {
            main { if(touched) { if(previous!=null)clipboard.setPrimaryClip(previous!!)else if(android.os.Build.VERSION.SDK_INT>=28)clipboard.clearPrimaryClip() } }
            assertTrue(!local.exists()||local.delete());assertTrue(!folder.exists()||folder.delete())
        }
    }
    private fun preparePhotoOnOriginalTarget(owned: OwnedControlAcceptance) {
        val activity=ui.activity;val session=owned.session;awaitLocalDraftReady(owned,"photo");val key=session.targetKey;val text=session.text;val originals=session.attachments
        val folder=File(activity.cacheDir,"resources/photo-live-"+java.util.UUID.randomUUID()).apply { mkdirs() }
        val file=File(folder,"原照片 100% %@.png")
        val bitmap=android.graphics.Bitmap.createBitmap(3,2,android.graphics.Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(0xff3587c9.toInt());file.outputStream().use { assertTrue(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)) }
            val uri=androidx.core.content.FileProvider.getUriForFile(activity,activity.packageName+".resources",file)
            val picker=NativePhotoPicker(activity,session)
            main { assertTrue(picker.begin(session.attachmentDestination()));picker.complete(picker.pending!!,listOf(uri),NativeL10n(activity,"en")) }
            ui.waitUntil(15_000) { !session.importing&&session.attachments.size==originals.size+1 }
            val added=session.attachments.single { candidate->originals.none { it.manifest.id==candidate.manifest.id } };assertEquals("原照片 100% %@.png",added.manifest.name);assertEquals("image/png",added.manifest.mediaType)
            val normalized=runBlocking { NativeDraftFiles(owned.storage).bytes(added.manifest) };val image=android.graphics.BitmapFactory.decodeByteArray(normalized,0,normalized.size)
            try { assertTrue(bitmap.sameAs(image)) }finally { image.recycle() }
            ui.waitUntil(10_000) { owned.storage.read("metadata")?.contains(added.manifest.id)==true }
            assertEquals(key,session.targetKey);assertEquals(text,session.text);assertTrue(session.operationRecords.isEmpty());owned.nodeShot(ui.onNodeWithTag("product-surface"),"actual-original-target-photo-prepared")
            main { session.removeAttachment(added.manifest.id,session.attachmentDestination()) };assertEquals(originals,session.attachments)
            File(owned.folder,"actual-photo-preparation-proof.json").writeText(JSONObject().put("originalLiveTargetMatched",true).put("nativePhotoControllerAndResolver",true)
                .put("normalizedPixelsNameMimeAndEncryptedBytesMatched",true).put("durableOriginalDraftMetadataConfirmed",true).put("originalTextAndFilesRestored",true)
                .put("systemPositiveSelection",false).put("source","same-App examiner-created PNG").put("newModelInputs",0).put("uploadOrSubmit",false).toString())
        }finally { bitmap.recycle();assertTrue(!file.exists()||file.delete());assertTrue(folder.delete()) }
    }
    private fun receiveIndependentFilesOnOriginalTarget(owned: OwnedControlAcceptance) {
        val activity=ui.activity;val session=owned.session;val instrumentation=InstrumentationRegistry.getInstrumentation()
        val nonce=java.util.UUID.randomUUID().toString().replace("-","");val sourcePackage=instrumentation.context.packageName
        fun source(mode: String) {
            val intent=Intent().setComponent(android.content.ComponentName(sourcePackage,"com.agenthub.android.OwnedExternalSourceActivity"))
                .putExtra("nonce",nonce).putExtra("mode",mode).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            main { activity.startActivity(intent) }
        }
        val key=session.targetKey;val draft=session.text;val original=session.attachments
        try {
            source("multiple")
            val inbox=session.externalInbox!!
            ui.waitUntil(20_000) { inbox.presentation?.batches?.any { batch->batch.readyFiles.any { it.name.startsWith("inbox-source-$nonce-") } }==true }
            assertTrue(activity.session===session);assertTrue(ui.activity===activity)
            ui.onNodeWithTag("sheet-title").assertTextEquals("Files from other apps")
            val reportUri=android.provider.DocumentsContract.buildDocumentUri(sourcePackage+".owned-documents","inbox-source-$nonce-report.txt")
            val reportBytes=activity.contentResolver.openInputStream(reportUri)!!.use { it.readBytes() };val report=JSONObject(String(reportBytes))
            assertEquals(activity.packageManager.getApplicationInfo(sourcePackage,0).uid,report.getInt("sourceUid"))
            assertNotEquals(android.os.Process.myUid(),report.getInt("sourceUid"));assertNotEquals(android.os.Process.myPid(),report.getInt("sourcePid"))
            val expected=report.getJSONArray("files");assertEquals(2,expected.length())
            for(n in 0 until expected.length()) {
                val uri=android.net.Uri.parse(expected.getJSONObject(n).getString("uri"))
                assertEquals(android.content.pm.PackageManager.PERMISSION_GRANTED,activity.checkUriPermission(uri,android.os.Process.myPid(),android.os.Process.myUid(),Intent.FLAG_GRANT_READ_URI_PERMISSION))
                assertEquals(android.content.pm.PackageManager.PERMISSION_DENIED,activity.checkUriPermission(uri,android.os.Process.myPid(),android.os.Process.myUid(),Intent.FLAG_GRANT_WRITE_URI_PERMISSION))
            }
            File(owned.folder,"actual-independent-source-report.json").writeBytes(reportBytes);owned.dialogShot("actual-independent-inbox-files")
            assertEquals(original,session.attachments);assertEquals(draft,session.text);assertEquals(key,session.targetKey)
            awaitLocalDraftReady(owned,"inbox-add")
            ui.onNodeWithTag("external-attachment-add").performScrollTo().performClick()
            ui.waitUntil(20_000) { !inbox.adding&&inbox.pending.isEmpty()&&session.attachments.size==original.size+2 }
            val added=session.attachments.filter { file->original.none { it.manifest.id==file.manifest.id } }
            assertEquals((0 until expected.length()).map { expected.getJSONObject(it).getString("name") },added.map { it.manifest.name })
            for(n in 0 until expected.length()) {
                val row=expected.getJSONObject(n);val bytes=runBlocking { NativeDraftFiles(owned.storage).bytes(added[n].manifest) }
                assertEquals(row.getString("sha256"),hash(bytes));assertEquals(row.getInt("size"),bytes.size)
            }
            assertEquals(key,session.targetKey);assertEquals(draft,session.text);assertTrue(session.operationRecords.isEmpty())
            owned.nodeShot(ui.onNodeWithTag("product-surface"),"actual-independent-files-original-draft")
            File(owned.folder,"actual-independent-inbox-proof.json").writeText(JSONObject().put("originalLiveTargetMatched",true).put("actualIndependentSourceUid",true)
                .put("actualSystemIntentReceiver",true).put("sameWarmActivityAndSession",true).put("readOnlyUriGrants",true)
                .put("fileOrderNamesAndBytesMatched",true).put("durableOriginalDraftConfirmedBeforeConsume",true).put("originalTextPreserved",true)
                .put("newModelInputs",0).put("uploadOrSubmit",false).put("sourceReportSha256",hash(reportBytes)).toString())
            main { for(file in added)session.removeAttachment(file.manifest.id,session.attachmentDestination()) }
            assertEquals(original,session.attachments)
        } finally {
            source("cleanup")
            ui.waitUntil(10_000) { activity.hasWindowFocus() }
        }
    }
    @Test fun originalModelResourcePreviewAndControlledShareLive() {
        val args=InstrumentationRegistry.getArguments()
        val name=args.getString("resourceLiveFixture")
        Assume.assumeTrue("Requires owned actual model reply and controlled resource",name!=null)
        require(name!!.matches(Regex("resource4c-[a-f0-9]{8}\\.json")))
        ui.waitUntil(30_000) { !ui.activity.session.restoring }
        val activity=ui.activity
        val owned=OwnedControlAcceptance(activity,"resource-live")
        val session=owned.session
        val fixture=JSONObject(File(activity.filesDir,"acceptance/"+name).readText())
        val token=fixture.getString("token");val thread=fixture.getString("threadId");val messageId=fixture.getString("messageId")
        val expectedSha=fixture.getString("resourceSha256");val expectedSize=fixture.getInt("resourceSizeBytes")
        var shareReads=0
        val monitor=object: android.app.Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: Intent?): android.app.Instrumentation.ActivityResult? {
                if(intent?.action !in listOf(Intent.ACTION_CHOOSER,"miui.intent.action.MIUI_CHOOSER"))return null
                @Suppress("DEPRECATION") val send=intent!!.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return null
                @Suppress("DEPRECATION") val uri=send.getParcelableExtra<android.net.Uri>(Intent.EXTRA_STREAM) ?: return null
                if(uri.authority!=activity.packageName+".resources")return null
                assertEquals("content",uri.scheme);assertEquals(Intent.ACTION_SEND,send.action)
                assertEquals(Intent.FLAG_GRANT_READ_URI_PERMISSION,send.flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION))
                assertEquals(uri,send.clipData!!.getItemAt(0).uri);assertFalse(send.hasExtra(android.provider.Browser.EXTRA_HEADERS))
                val bytes=activity.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
                assertEquals(expectedSize,bytes.size);assertEquals(expectedSha,hash(bytes));shareReads++
                return android.app.Instrumentation.ActivityResult(android.app.Activity.RESULT_CANCELED,null)
            }
        }
        val instrumentation=InstrumentationRegistry.getInstrumentation();instrumentation.addMonitor(monitor)
        var originalActivitySession: MainActivity.AcceptanceSessionState?=null
        try {
            main {
                session.resetAcceptance(NativeHostReads { token },NativeCodexCommands { token })
                session.changeLanguage("en");session.changeAppearance("light")
                originalActivitySession=activity.adoptSessionForAcceptance(session)
                session.connect("Owned original resource read",fixture.getString("endpoint"),token)
            }
            ui.waitUntil(30_000) { !session.loading&&session.threads.any { it.id==thread } }
            main { session.selectThread(session.threads.single { it.id==thread }) }
            ui.waitUntil(30_000) { !session.historyLoading&&session.messages.any { it.id==messageId } }
            assertEquals(fixture.getString("hostId"),session.selectedClient.id)
            assertTrue(sameScope(HostReadCodec.target(fixture.getJSONObject("target")),session.selectedClient.scope))
            if(fixture.optBoolean("singleCompletedResourceReply")) {
                fun matches()=session.messages.filter { value->value.role=="assistant"&&value.kind=="agentMessage"&&
                    value.replyPhase=="final_answer"&&value.attachments.any { it.sha256==expectedSha } }
                ui.waitUntil(15_000) { matches().size==1 }
                assertEquals(messageId,matches().single().id)
                assertFalse(matches().single().id.startsWith("history-fallback:"))
                File(owned.folder,"original-resource-alias-proof.json").writeText(JSONObject().put("completedFinalResourceReplies",1)
                    .put("originalCanonicalMessageMatched",true).put("provisionalFinalReplyAbsent",true).put("newModelInputs",0).toString())
            }
            val message=session.messages.single { it.id==messageId }
            assertEquals(fixture.getString("messageTextSha256"),hash(message.text.toByteArray(Charsets.UTF_8)))
            main { session.text="Unsent original resource draft "+fixture.getString("nonce") }
            val originalDraft=session.text;val originalFiles=session.attachments
            fixture.optJSONObject("originalToolAudit")?.let { expected ->
                val tool=session.messages.single { it.id==expected.getString("id") }
                assertEquals("tool",tool.role);assertTrue(tool.kind in listOf("mcpToolCall","dynamicToolCall"))
                assertEquals(expected.getString("textSha256"),hash(tool.text.toByteArray(Charsets.UTF_8)))
                assertEquals(expected.getString("detailSha256"),hash(tool.detail.orEmpty().toByteArray(Charsets.UTF_8)))
                assertEquals("completed",tool.status)
                val row=session.timeline.rows.single { value->value.kind=="tools"&&value.indices.any { session.messages[it].id==tool.id } }
                if(row.indices.size>1) {
                    val group="tool-group-toggle-"+row.id
                    ui.onNodeWithTag("message-list").performScrollToNode(hasTestTag(group))
                    val node=ui.onNodeWithTag(group)
                    if(node.fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.StateDescription]=="Collapsed")node.performClick()
                }
                val toggle="tool-toggle-"+tool.id
                ui.onNodeWithTag("message-list").performScrollToNode(hasTestTag(toggle))
                ui.onNodeWithTag(toggle).assertContentDescriptionEquals(expected.getString("title")).performClick()
                val detail="tool-detail-"+tool.id
                ui.onNodeWithTag("message-list").performScrollToNode(hasTestTag(detail))
                ui.onNodeWithTag(toggle).assert(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.StateDescription,"Succeeded, Expanded"))
                ui.onNode(hasText(tool.text) and hasAnyAncestor(hasTestTag(detail))).assertExists()
                tool.detail?.takeIf { it.isNotEmpty() }?.let { raw->ui.onNode(hasText(raw) and hasAnyAncestor(hasTestTag(detail))).assertExists() }
                owned.nodeShot(ui.onNodeWithTag(detail),"actual-original-tool-detail")
                main { session.changeLanguage("zh-Hans") }
                ui.onNodeWithTag(toggle).assertContentDescriptionEquals(expected.getString("title"))
                    .assert(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.StateDescription,"成功，已展开"))
                ui.onNodeWithTag(detail).assertExists()
                main { session.changeLanguage("en") }
                assertEquals(originalDraft,session.text);assertEquals(originalFiles,session.attachments)
                assertEquals(tool,session.messages.single { it.id==tool.id })
                File(owned.folder,"original-tool-native-proof.json").writeText(JSONObject().put("originalTargetMatched",true)
                    .put("originalStableToolIdMatched",true).put("rawProviderTextAndDetailPreserved",true).put("twoLanguageStatusAndDisclosure",true)
                    .put("originalDraftAndFilesPreserved",true).put("newModelInputs",0).toString())
            }
            if(args.getString("photoPreparation")=="1")preparePhotoOnOriginalTarget(owned)
            if(args.getString("pasteFiles")=="1")pasteOriginalFile(owned,fixture)
            if(args.getString("inboxFiles")=="1") {
                ui.waitUntil(20_000) { !session.loading&&!session.historyLoading&&session.externalInbox?.loading==false }
                receiveIndependentFilesOnOriginalTarget(owned)
            }
            val document=NativeMarkdown.parse(message.text)
            val blocks=NativeMarkdown.children(document)
            fun links(node: org.commonmark.node.Node): List<Link> = buildList {
                if(node is Link)add(node)
                NativeMarkdown.children(node).forEach { addAll(links(it)) }
            }
            val index=blocks.indexOfFirst { it is Paragraph&&links(it).any { link->link.destination==fixture.getString("reference") } }
            assertTrue("Actual model must provide the original clickable resource paragraph",index>=0)
            val linkIndex=links(blocks[index]).indexOfFirst { it.destination==fixture.getString("reference") }
            val tag="markdown-"+messageId+"-"+index
            val mounted=JSONObject()
            main {
                mounted.put("selectedThread",session.selectedThread).put("messagePresent",session.messages.any { it.id==messageId })
                    .put("timelineRows",org.json.JSONArray(session.timeline.rows.map { it.id+":"+it.kind }))
            }
            File(owned.folder,"original-resource-mount-checkpoint.json").writeText(mounted.toString())
            // LazyColumn may have composed only the later reply actions. Mount
            // the exact original message before waiting for its async Markdown.
            ui.onNodeWithTag("message-list").performScrollToNode(hasTestTag("message-"+messageId))
            ui.waitUntil(15_000) { ui.onAllNodesWithTag(tag).fetchSemanticsNodes().size==1 }
            val node=ui.onNodeWithTag(tag).performScrollTo().assertIsDisplayed()
            var layout: TextLayoutResult?=null
            node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { get->val out=mutableListOf<TextLayoutResult>();get(out);layout=out.single() }
            val range=layout!!.layoutInput.text.getLinkAnnotations(0,layout!!.layoutInput.text.length).single { (it.item as? LinkAnnotation.Clickable)?.tag==tag+":"+linkIndex }
            node.performTouchInput { click(layout!!.getBoundingBox(range.start).center) }
            ui.waitUntil(30_000) { ui.onAllNodesWithTag("resource-preview-text").fetchSemanticsNodes().size==1 }
            ui.onNodeWithTag("resource-preview-name").assertTextEquals(fixture.getString("resourceName"))
            val text=ui.onNodeWithTag("resource-preview-text").fetchSemanticsNode().config[SemanticsProperties.Text].single().text
            assertEquals(expectedSha,hash(text.toByteArray(Charsets.UTF_8)));assertEquals(expectedSize,text.toByteArray(Charsets.UTF_8).size)
            assertEquals(originalDraft,session.text);assertEquals(originalFiles,session.attachments)
            owned.dialogShot("actual-model-resource-preview")
            ui.onNodeWithTag("resource-preview-share").performClick()
            ui.waitUntil(10_000) { shareReads==1 }
            assertEquals(originalDraft,session.text);assertEquals(originalFiles,session.attachments)
            val cards=args.getString("resourceCards")=="1"
            if(cards) {
                ui.onNodeWithTag("resource-preview-close").performClick()
                ui.waitUntil(5_000) { ui.onAllNodesWithTag("resource-preview").fetchSemanticsNodes().isEmpty() }
                val file=session.messages.single { it.id==messageId }.attachments.single { it.name==fixture.getString("resourceName") }
                val shareNode=ui.onNode(hasTestTag("resource-card-share-"+file.id) and hasAnyAncestor(hasTestTag("message-"+messageId))).performScrollTo()
                try {
                    var stableSince=0L;var previousBounds: String?=null
                    ui.waitUntil(8_000) {
                        val bounds=shareNode.fetchSemanticsNode().boundsInRoot
                        var focused=false;var windowHeight=0
                        ui.runOnUiThread { focused=activity.window.decorView.hasWindowFocus();windowHeight=activity.window.decorView.height }
                        val signature="$bounds/$windowHeight";val now=android.os.SystemClock.uptimeMillis()
                        if(!focused||signature!=previousBounds) { stableSince=now;previousBounds=signature }
                        focused&&bounds.width>0&&bounds.height>0&&now-stableSince>=700
                    }
                    val shareBounds=shareNode.fetchSemanticsNode().boundsInRoot
                    val latestBounds=ui.onAllNodesWithTag("scroll-latest").fetchSemanticsNodes().singleOrNull()?.boundsInRoot
                    File(owned.folder,"actual-card-before-share-hit-region.json").writeText(JSONObject().put("shareBounds",shareBounds.toString())
                        .put("latestBounds",latestBounds?.toString()).put("shareCenterCovered",latestBounds?.contains(shareBounds.center)==true).toString())
                    shareNode.assertIsEnabled().performClick()
                    ui.waitUntil(10_000) { shareReads==2 }
                } catch(error: Throwable) {
                    val diagnostic=JSONObject().put("shares",shareReads)
                    main { diagnostic.put("originalContextCurrent",session.messageLinkCurrent(session.messageLinkContext(message)))
                        .put("originalMessageStillEqual",session.messages.singleOrNull { it.id==messageId }==message)
                        .put("historyLoading",session.historyLoading).put("online",session.selectedClient.online).put("windowFocused",activity.window.decorView.hasWindowFocus()) }
                    runCatching { diagnostic.put("disabled",shareNode.fetchSemanticsNode().config.contains(SemanticsProperties.Disabled)) }
                    File(owned.folder,"actual-card-share-failure.json").writeText(diagnostic.toString())
                    runCatching { File(owned.folder,"actual-card-share-tree.txt").writeText(ui.onRoot().printToString()) }
                    runCatching { owned.nodeShot(ui.onNode(hasTestTag("resource-card-"+file.id) and hasAnyAncestor(hasTestTag("message-"+messageId))),"actual-card-share-failure") }
                    throw error
                }
                ui.onNodeWithTag("resource-preview").assertDoesNotExist()
                ui.onNode(hasTestTag("resource-card-preview-"+file.id) and hasAnyAncestor(hasTestTag("message-"+messageId))).performScrollTo().performClick()
                ui.waitUntil(15_000) { ui.onAllNodesWithTag("resource-preview-text").fetchSemanticsNodes().size==1 }
                val cardText=ui.onNodeWithTag("resource-preview-text").fetchSemanticsNode().config[SemanticsProperties.Text].single().text
                assertEquals(expectedSha,hash(cardText.toByteArray(Charsets.UTF_8)))
                assertEquals(originalDraft,session.text);assertEquals(originalFiles,session.attachments)
                owned.dialogShot("actual-model-resource-card-preview")
            }
            File(owned.folder,"actual-resource-native-proof.json").writeText(JSONObject()
                .put("originalTargetMatched",true).put("exactOriginalMessageMatched",true).put("nativeLinkActivated",true)
                .put("previewTextSha256",expectedSha).put("previewBytes",expectedSize).put("controlledFileProviderReadSha256",expectedSha)
                .put("nativeResourceCardPreviewAndDirectShare",cards)
                .put("readOnlyGrant",true).put("systemShareIntentIntercepted",true).put("actualRecipientAccepted",false)
                .put("originalDraftAndFilesPreserved",true).put("newModelInputs",0).put("automaticReplay",false).toString())
        } finally {
            instrumentation.removeMonitor(monitor)
            main { session.leaveForeground();originalActivitySession?.let { activity.restoreSessionForAcceptance(it) } };owned.verifyDefault()
        }
    }
}
