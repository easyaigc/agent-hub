package com.agenthub.android

import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.compose.material3.MaterialTheme
import android.content.Intent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.LinkAnnotation
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class MobileResourceUiTest {
    @get:Rule(order=0) val foreground: org.junit.rules.TestRule=NativeTestForeground()
    @get:Rule(order=1) val ui=createAndroidComposeRule<MainActivity>()
    private lateinit var owned: OwnedControlAcceptance
    private lateinit var activity: MainActivity
    private val session get()=owned.session
    private val shared=mutableListOf<ByteArray>()
    private val monitor=object: android.app.Instrumentation.ActivityMonitor() {
        override fun onStartActivity(intent: Intent?): android.app.Instrumentation.ActivityResult? {
            if(::owned.isInitialized) File(owned.folder,"activity-intents.jsonl").appendText(JSONObject().put("action",intent?.action)
                .put("keys",JSONArray(intent?.extras?.keySet()?.toList().orEmpty())).toString()+"\n")
            if(intent?.action !in listOf(Intent.ACTION_CHOOSER,"miui.intent.action.MIUI_CHOOSER"))return null
            @Suppress("DEPRECATION") val send=intent!!.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return null
            @Suppress("DEPRECATION") val uri=send.getParcelableExtra<android.net.Uri>(Intent.EXTRA_STREAM) ?: return null
            if(uri.authority!=activity.packageName+".resources")return null
            assertEquals(Intent.ACTION_SEND,send.action);assertEquals("content",uri.scheme)
            assertEquals(Intent.FLAG_GRANT_READ_URI_PERMISSION,send.flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION))
            assertEquals(uri,send.clipData!!.getItemAt(0).uri);assertFalse(send.hasExtra(android.provider.Browser.EXTRA_HEADERS))
            val data=activity.contentResolver.openInputStream(uri)!!.use { it.readBytes() };shared.add(data)
            return android.app.Instrumentation.ActivityResult(android.app.Activity.RESULT_CANCELED,null)
        }
    }
    private fun main(block: suspend ()->Unit)=runBlocking { withContext(Dispatchers.Main) { block() } }
    @Before fun setup() {
        ui.waitUntil(30_000) { !ui.activity.session.restoring }
        activity=ui.activity
        InstrumentationRegistry.getInstrumentation().addMonitor(monitor)
        ui.runOnIdle {
            owned=OwnedControlAcceptance(activity,"resource-ui");session.resetAcceptance();session.changeLanguage("en")
            session.changeAppearance(InstrumentationRegistry.getArguments().getString("viewportTheme") ?: "light")
        }
    }
    @After fun finish() {
        InstrumentationRegistry.getInstrumentation().removeMonitor(monitor)
        ui.runOnIdle { if(::owned.isInitialized) { session.leaveForeground();owned.verifyDefault() } }
    }
    private fun connect(f: ResourceUiFixture) {
        main { session.connect("Owned resources",f.server.endpoint,"") }
        ui.waitUntil(10_000) { !session.loading&&session.threads.isNotEmpty() }
        main { session.selectThread(session.threads.first()) }
        ui.waitUntil(10_000) { !session.historyLoading&&session.messages.any { it.id=="old-assistant" } }
        f.client=session.selectedClient
        main { session.text="Original resource draft 100% %@" }
    }
    private fun paragraph(index: Int=0)="markdown-old-assistant-"+index
    private fun layout(index: Int=0): TextLayoutResult {
        var result: TextLayoutResult?=null
        ui.onNodeWithTag(paragraph(index)).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { get->val out=mutableListOf<TextLayoutResult>();get(out);result=out.single() }
        return checkNotNull(result)
    }
    private fun tap(index: Int=0) {
        val node=ui.onNodeWithTag(paragraph(index)).performScrollTo().assertIsDisplayed()
        val text=layout(index)
        node.performTouchInput { click(text.getBoundingBox(2).center) }
    }
    private fun preview() { ui.waitUntil(10_000) { ui.onAllNodesWithTag("resource-preview").fetchSemanticsNodes().size==1 } }
    private fun closePreview() {
        ui.onNodeWithTag("resource-preview-close").performClick()
        ui.waitUntil(5_000) { ui.onAllNodesWithTag("resource-preview").fetchSemanticsNodes().isEmpty() }
    }
    private fun sharePreview(f: ResourceUiFixture,expected: ByteArray) {
        ui.onNodeWithTag("resource-preview-share").performClick()
        try { ui.waitUntil(5_000) { shared.size==1 };assertArrayEquals(expected,shared.single()) }
        catch(error: Throwable) {
            val diagnostic=JSONObject().put("capturedIntents",shared.size).put("selectedThread",session.selectedThread)
            main {
                val matches=File(activity.cacheDir,"resources").walkTopDown().filter { it.isFile&&it.name==f.name }.toList()
                diagnostic.put("matchingFiles",matches.size)
                try {
                    val intent=NativeResourceIntents.view(activity,MobileSession.PreparedMessageResource(f.name,f.mediaType,matches.first()),true)
                    diagnostic.put("directIntentCreated",true).put("action",intent.action).put("type",intent.type)
                } catch(failure: Exception) { diagnostic.put("directIntentCreated",false).put("failureClass",failure.javaClass.name).put("failure",failure.message) }
            }
            File(owned.folder,"share-failure.json").writeText(diagnostic.toString())
            runCatching { owned.dialogShot("share-failure-own-window") };throw error
        }
    }
    @Test fun resourceCardShareAvoidsFloatingLatestHitRegionAfterLongContentScroll() {
        ResourceUiFixture().use { f->
            f.execution.state="idle";f.execution.turn=null
            f.execution.streamText=(1..60).joinToString("\n\n") { "Owned long source paragraph $it 100% %@" }+"\n\n[Owned resource]("+f.reference+")"
            connect(f)
            val card=ui.onNodeWithTag("resource-card-share-ui-one").performScrollTo().assertIsEnabled()
            val viewport=ui.onNodeWithTag("message-list")
            val desiredY=viewport.fetchSemanticsNode().boundsInRoot.bottom-34f*activity.resources.displayMetrics.density
            val delta=card.fetchSemanticsNode().boundsInRoot.center.y-desiredY
            viewport.performSemanticsAction(SemanticsActions.ScrollBy) { scroll->assertTrue(scroll(0f,delta)) }
            var last: String?=null;var stableSince=0L
            ui.waitUntil(8_000) {
                val now=android.os.SystemClock.uptimeMillis();val bounds=card.fetchSemanticsNode().boundsInRoot.toString()
                if(bounds!=last) { last=bounds;stableSince=now };now-stableSince>=700
            }
            val shareBounds=card.fetchSemanticsNode().boundsInRoot
            val overlay=ui.onAllNodesWithTag("scroll-latest").fetchSemanticsNodes().singleOrNull()?.boundsInRoot
            val covered=overlay?.contains(shareBounds.center)==true
            File(owned.folder,"floating-resource-hit-region.json").writeText(JSONObject().put("shareBounds",shareBounds.toString()).put("latestBounds",overlay?.toString())
                .put("shareCenterCovered",covered).put("modelInputs",0).toString())
            owned.nodeShot(ui.onNodeWithTag("product-surface"),"resource-and-floating-latest-hit-region")
            assertFalse("Floating return-to-latest must not cover the resource share hit target",covered)
            card.performClick();ui.waitUntil(5_000) { shared.size==1 };assertArrayEquals(f.bytes,shared.single())
            assertEquals("Original resource draft 100% %@",session.text);assertTrue(f.server.requests.all { it.method=="GET" })
        }
    }
    @Test fun reusedMessageCompositionReactivatesOnlyFreshResourceCallbacks() {
        ResourceUiFixture().use { f->
            connect(f);val original=session.messages.single { it.id=="old-assistant" };val owner=session.messageLinkContext(original)
            val draft=session.text;val files=session.attachments
            var mounted by mutableStateOf(true)
            var currentOpen: ((String,String,MessageResourceAction)->Unit)?=null
            ui.runOnIdle { activity.setContent { ProductTextProvider("en") { MaterialTheme {
                ReusableContentHost(mounted) { MessageLinkOpening(session,original) {
                    val callback=LocalMessageResourceOpen.current;SideEffect { currentOpen=callback };MessageResourceCards(original)
                } }
            } } } }
            val tag="resource-card-share-ui-one"
            ui.onNodeWithTag(tag).assertIsEnabled();var retired=checkNotNull(currentOpen)
            ui.onNodeWithTag(tag).performClick();ui.waitUntil(5_000) { shared.size==1 };ui.waitForIdle()
            assertEquals(1,f.binaries());assertArrayEquals(f.bytes,shared.single())
            var oldCallbackInvoked=0
            repeat(3) { index->
                val before=f.resourceReads();val count=shared.size
                ui.runOnIdle { mounted=false };ui.onNodeWithTag(tag).assertDoesNotExist()
                ui.runOnIdle { mounted=true };ui.onNodeWithTag(tag).assertIsEnabled();ui.waitForIdle()
                ui.runOnIdle { assertTrue(session.messageLinkCurrent(owner))
                    retired.invoke("codex-attachment://open?id=ui-one","resource-card:ui-one",MessageResourceAction.Share);oldCallbackInvoked++
                }
                ui.waitForIdle();assertEquals(before,f.resourceReads());assertEquals(count,shared.size)
                retired=checkNotNull(currentOpen)
                ui.onNodeWithTag(tag).performClick()
                try { ui.waitUntil(5_000) { shared.size==count+1 } }
                catch(error: Throwable) {
                    File(owned.folder,"reused-composition-failure.json").writeText(JSONObject().put("iteration",index).put("originalContextCurrent",session.messageLinkCurrent(owner))
                        .put("sharesBefore",count).put("sharesAfter",shared.size).put("resourceReadsBefore",before).put("resourceReadsAfter",f.resourceReads()).toString());throw error
                }
                assertArrayEquals(f.bytes,shared.last());assertEquals(1,f.binaries())
            }
            owned.nodeShot(ui.onNodeWithTag("resource-card-ui-one"),"reactivated-original-resource-card")
            assertEquals(draft,session.text);assertEquals(files,session.attachments);assertEquals(original,session.messages.single { it.id==original.id })
            assertTrue(f.server.requests.all { it.method=="GET" })
            File(owned.folder,"reused-composition-proof.json").writeText(JSONObject().put("reactivations",3).put("shares",shared.size)
                .put("binaryDownloads",f.binaries()).put("retiredCallbacksInvoked",oldCallbackInvoked).put("actualAppCallbacksCaptured",true)
                .put("retiredCallbackResourceReads",0).put("draftAndFilesPreserved",true).put("defaultMaterialLightComponentOnly",true).toString())
        }
    }
    @Test fun exactClickedParagraphWaitsThenNativePreviewAndShareKeepRawBytes() {
        ResourceUiFixture().use { f->
            val began=CountDownLatch(1);val release=CountDownLatch(1);f.binaryGate={ began.countDown();release.await(10,TimeUnit.SECONDS) }
            connect(f)
            try {
                tap()
                assertTrue(began.await(5,TimeUnit.SECONDS))
                ui.onNodeWithTag(paragraph()+"-link-wait").assertIsDisplayed()
                ui.onNodeWithTag(paragraph(1)+"-link-wait").assertDoesNotExist()
                val first=layout().layoutInput.text
                assertTrue(first.spanStyles.any { it.item.background.alpha>0f })
                val other=layout(1).layoutInput.text
                assertFalse(other.spanStyles.any { it.item.background.alpha>0f })
                owned.nodeShot(ui.onNodeWithTag("message-old-assistant"),"original-paragraph-wait")
                assertEquals("Original resource draft 100% %@",session.text)
                release.countDown();preview()
                ui.onNodeWithTag("resource-preview-text").assertTextEquals(String(f.bytes,Charsets.UTF_8))
                ui.onNodeWithTag("resource-preview-name").assertTextEquals(f.name)
                owned.dialogShot("original-resource-text")
                sharePreview(f,f.bytes)
                assertTrue(f.server.requests.all { it.method=="GET" });assertEquals(1,f.binaries());assertEquals("Original resource draft 100% %@",session.text)
                closePreview()
            } finally { release.countDown() }
        }
    }
    @Test fun sameResourceBackgroundVersionKeepsOpenedBytesAndNextOpenReadsNewVersion() {
        ResourceUiFixture().use { f->connect(f);val old=f.bytes.copyOf();tap();preview()
            ui.onNodeWithTag("resource-preview-text").assertTextEquals(String(old,Charsets.UTF_8))
            f.bytes="New resource version 100% %@\n新的原文".toByteArray();f.version="two";f.updateLinks()
            main { session.loadHistory() }
            ui.waitUntil(10_000) { !session.historyLoading&&session.messages.any { it.attachments.any { file->file.id=="ui-two" } } }
            ui.onNodeWithTag("resource-preview-text").assertTextEquals(String(old,Charsets.UTF_8))
            sharePreview(f,old)
            closePreview();tap();preview()
            ui.onNodeWithTag("resource-preview-text").assertTextEquals(String(f.bytes,Charsets.UTF_8));assertEquals(2,f.binaries())
            owned.dialogShot("current-resource-version")
            assertTrue(f.server.requests.all { it.method=="GET" });assertEquals("Original resource draft 100% %@",session.text)
        }
    }
    @Test fun cancelledOldReadAndCapturedLinkCannotReviveAfterMessageRoundTrip() {
        ResourceUiFixture().use { f->
            val started=CountDownLatch(1);val release=CountDownLatch(1);f.binaryGate={ started.countDown();release.await(10,TimeUnit.SECONDS) }
            connect(f);val original=f.execution.streamText!!
            val old=layout().layoutInput.text.getLinkAnnotations(0,layout().layoutInput.text.length).single().item as LinkAnnotation.Clickable
            try {
                tap();assertTrue(started.await(5,TimeUnit.SECONDS))
                f.execution.streamText="[Changed](https://example.com/)";main { session.loadHistory() }
                ui.waitUntil(10_000) { !session.historyLoading&&session.messages.any { it.text==f.execution.streamText } }
                f.execution.streamText=original;main { session.loadHistory() }
                ui.waitUntil(10_000) { !session.historyLoading&&session.messages.any { it.text==original } }
                release.countDown()
                ui.waitUntil(5_000) { ui.onAllNodesWithTag(paragraph()+"-link-wait").fetchSemanticsNodes().isEmpty() }
                ui.onNodeWithTag("resource-preview").assertDoesNotExist()
                val before=f.resourceReads()
                main { old.linkInteractionListener!!.onClick(old) }
                assertEquals(before,f.resourceReads());ui.onNodeWithTag("resource-preview").assertDoesNotExist()
                assertEquals("Original resource draft 100% %@",session.text);assertTrue(f.server.requests.all { it.method=="GET" })
            } finally { release.countDown() }
        }
    }
    @Test fun providerRawErrorAndExactLinkCopyStayLiteral() {
        ResourceUiFixture().use { f->
            f.status=403;f.rawError="关闭";connect(f);tap()
            ui.waitUntil(10_000) { ui.onAllNodesWithTag("message-link-error").fetchSemanticsNodes().size==1 }
            ui.onNode(hasText("关闭") and hasAnyAncestor(hasTestTag("message-link-error"))).assertIsDisplayed()
            owned.dialogShot("literal-provider-error")
            lateinit var manager: android.content.ClipboardManager
            ui.runOnIdle { manager=activity.getSystemService(android.content.ClipboardManager::class.java) }
            val previous=manager.primaryClip
            val eligible=previous!=null||android.os.Build.VERSION.SDK_INT>=28
            try {
                if(eligible) {
                    ui.onNodeWithTag("message-link-copy").performClick()
                    ui.waitUntil(5_000) { manager.primaryClip?.getItemAt(0)?.text?.toString()==f.reference }
                } else ui.onNodeWithTag("message-link-dismiss").performClick()
            } finally {
                if(previous!=null)manager.setPrimaryClip(previous) else if(android.os.Build.VERSION.SDK_INT>=28)manager.clearPrimaryClip()
            }
            File(owned.folder,"raw-link-copy-proof.json").writeText(JSONObject().put("eligible",eligible).put("exactOriginalReference",eligible)
                .put("clipboardRestored",eligible).put("api26WithoutPriorClipNotAttempted",!eligible).toString())
            assertEquals("Original resource draft 100% %@",session.text);assertEquals(0,f.binaries());assertTrue(f.server.requests.all { it.method=="GET" })
        }
    }
    @Test fun longRawFilenameRemainsCompleteAndPrimaryControlsStayReachable() {
        ResourceUiFixture().use { f->
            f.name="Original_%@_100%_".repeat(13)+"file.txt";assertTrue(f.name.toByteArray().size<=255)
            fun step(value: String) { File(owned.folder,"long-name-steps.txt").appendText(value+"\n") }
            connect(f);step("connected");tap();step("link-activated");preview();step("preview-visible")
            val title=ui.onNodeWithTag("resource-preview-name").assertTextEquals(f.name);step("name-matched")
            var text: TextLayoutResult?=null
            title.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { get->val out=mutableListOf<TextLayoutResult>();get(out);text=out.single() }
            assertEquals(f.name,checkNotNull(text).layoutInput.text.text)
            assertFalse((0 until text!!.lineCount).any { text!!.isLineEllipsized(it) })
            val range=title.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
            val needed=range.maxValue()>0f;step("before-title-scroll:"+needed)
            if(needed) { title.performTouchInput { swipeUp() };assertTrue(range.value()>0f) };step("after-title-scroll")
            val density=activity.resources.displayMetrics.density
            for(tag in listOf("resource-preview-close","resource-preview-share","resource-preview-open-with")) {
                step("before-control:"+tag)
                val node=ui.onNodeWithTag(tag).assertIsDisplayed().assertIsEnabled().fetchSemanticsNode()
                assertTrue(node.boundsInRoot.height/density>=47.5f);step("after-control:"+tag)
            }
            step("before-dialog-shot");owned.dialogShot("long-original-resource-name");step("after-dialog-shot")
            File(owned.folder,"long-resource-title-proof.json").writeText(JSONObject().put("fullNameMatched",true).put("ellipsized",false)
                .put("scrollNeeded",needed).put("scrolledWhenNeeded",!needed||range.value()>0f).put("primaryControlsAtLeast48dp",true).toString())
            assertTrue(f.server.requests.all { it.method=="GET" });assertEquals("Original resource draft 100% %@",session.text)
        }
    }
    @Test fun directResourceCardSharePreparesOnceWithoutOpeningPreview() {
        ResourceUiFixture().use { f->
            val began=CountDownLatch(1);val release=CountDownLatch(1);f.binaryGate={ began.countDown();release.await(10,TimeUnit.SECONDS) }
            connect(f)
            val share=ui.onNodeWithTag("resource-card-share-ui-one").performScrollTo().assertIsDisplayed()
            val old=share.fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
            try {
                share.performClick();assertTrue(began.await(5,TimeUnit.SECONDS))
                ui.onNodeWithTag("resource-card-wait-ui-one").assertIsDisplayed()
                ui.onNodeWithTag("resource-card-preview-ui-one").assertIsNotEnabled();share.assertIsNotEnabled()
                share.performTouchInput { click(center) };main { old() };assertEquals(1,f.binaries())
                owned.nodeShot(ui.onNodeWithTag("message-old-assistant"),"direct-resource-card-wait")
                release.countDown();ui.waitUntil(10_000) { shared.size==1 }
                assertArrayEquals(f.bytes,shared.single());ui.onNodeWithTag("resource-preview").assertDoesNotExist()
                ui.waitUntil(5_000) { ui.onAllNodesWithTag("resource-card-wait-ui-one").fetchSemanticsNodes().isEmpty() }
                share.assertIsEnabled();assertEquals("Original resource draft 100% %@",session.text)
                assertTrue(f.server.requests.all { it.method=="GET" });assertEquals(1,f.binaries())
            } finally { release.countDown() }
        }
    }
    @Test fun resourceCardRawNameTargetsAndOldVersionCallbackAreProtected() {
        ResourceUiFixture().use { f->
            f.name="关闭 100% %@ "+"long-original-name-".repeat(9)+".txt";connect(f)
            ui.onNodeWithTag("resource-card-name-ui-one",useUnmergedTree=true).assertTextEquals(f.name)
            val share=ui.onNodeWithTag("resource-card-share-ui-one").performScrollTo()
            val old=share.fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
            val density=activity.resources.displayMetrics.density
            for(tag in listOf("resource-card-preview-ui-one","resource-card-share-ui-one")) {
                val bounds=ui.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
                assertTrue(bounds.height>=48*density-1);assertTrue(bounds.width>=48*density-1)
            }
            owned.nodeShot(ui.onNodeWithTag("message-old-assistant"),"direct-resource-card-ready")
            f.version="two";f.bytes="New card version 100% %@".toByteArray();f.updateLinks();main { session.loadHistory() }
            ui.waitUntil(10_000) { !session.historyLoading&&session.messages.any { it.attachments.any { file->file.id=="ui-two" } } }
            val before=f.resourceReads()
            var dispatch="original-click-callback-invoked"
            try { main { old() } }
            catch(error: IllegalStateException) {
                // A retired native modifier is no longer an invocable UI control.
                // Record this separately, without claiming the App callback ran.
                assertEquals("Cannot read CompositionLocal because the Modifier node is not currently attached.",error.message)
                assertTrue(error.stackTrace.any { it.className=="androidx.compose.foundation.AbstractClickableNode"&&it.methodName=="playClickSound" })
                dispatch="retired-native-node-rejected-before-app-callback"
            }
            File(owned.folder,"retired-card-dispatch.json").writeText(JSONObject().put("dispatch",dispatch)
                .put("resourceGetsAdded",f.resourceReads()-before).put("shareIntentsAdded",shared.size).toString())
            assertEquals(before,f.resourceReads());assertTrue(shared.isEmpty())
            ui.onNodeWithTag("resource-card-preview-ui-two").performScrollTo().performClick();preview()
            ui.onNodeWithTag("resource-preview-name").assertTextEquals(f.name)
            ui.onNodeWithTag("resource-preview-text").assertTextEquals(String(f.bytes,Charsets.UTF_8))
            assertEquals("Original resource draft 100% %@",session.text);assertTrue(f.server.requests.all { it.method=="GET" })
        }
    }
    @Test fun fileNamesWithoutAttachmentClaimsDoNotGrantPreviewOrSharing() {
        ResourceUiFixture().use { f->f.includeAttachment=false;f.name="Metadata 关闭 100% %@";connect(f)
            ui.onNodeWithText("File · "+f.name).performScrollTo().assertIsDisplayed()
            ui.onAllNodesWithTag("resource-card-ui-one").assertCountEquals(0)
            ui.onAllNodesWithTag("resource-card-preview-ui-one").assertCountEquals(0)
            ui.onAllNodesWithTag("resource-card-share-ui-one").assertCountEquals(0)
            assertEquals(0,f.resourceReads());assertEquals("Original resource draft 100% %@",session.text)
        }
    }
    @Test fun resourceIdentityRoundTripAndFileProviderNeverExposeOtherPrivatePaths() {
        val file=MobileFile("opaque","关闭","text/plain",1,"h","t","a".repeat(64),"stable-resource")
        val message=MobileMessage("m","assistant","raw",attachments=listOf(file))
        assertEquals(message,HostReadCodec.message(encodeMessage(message)));assertEquals("stable-resource",file.resourceIdentity)
        for(bad in listOf<Any>(1,"")) {
            val value=encodeMessage(message);value.getJSONArray("attachments").getJSONObject(0).put("resourceId",bad)
            var rejected=false;try { HostReadCodec.message(value) }catch(_: Exception) { rejected=true };assertTrue(rejected)
        }
        val context=activity
        @Suppress("DEPRECATION") val info=context.packageManager.getProviderInfo(android.content.ComponentName(context,ResourceFileProvider::class.java),android.content.pm.PackageManager.GET_META_DATA)
        assertFalse(info.exported);assertTrue(info.grantUriPermissions);assertEquals(context.packageName+".resources",info.authority)
        val outside=File(context.cacheDir,"outside-resource-"+java.util.UUID.randomUUID()).apply { writeText("synthetic private bytes") }
        try {
            var rejected=false
            try { NativeResourceIntents.view(context,MobileSession.PreparedMessageResource("private","text/plain",outside),true) }catch(_: IllegalArgumentException) { rejected=true }
            assertTrue(rejected)
            rejected=false
            try { androidx.core.content.FileProvider.getUriForFile(context,context.packageName+".resources",outside) }catch(_: IllegalArgumentException) { rejected=true }
            assertTrue(rejected)
        } finally { outside.delete() }
    }
    @Test fun imagePreviewUsesBoundedNativeDecodeAndAllExifTransformsKeepOriginalPixels() {
        val original=android.graphics.Bitmap.createBitmap(2,3,android.graphics.Bitmap.Config.ARGB_8888)
        val pixels=intArrayOf(0xffff0000.toInt(),0xff00ff00.toInt(),0xff0000ff.toInt(),0xffffff00.toInt(),0xffffffff.toInt(),0xff000000.toInt())
        original.setPixels(pixels,0,2,0,0,2,3)
        val expected=listOf(listOf(0,1,2,3,4,5),listOf(1,0,3,2,5,4),listOf(5,4,3,2,1,0),listOf(4,5,2,3,0,1),
            listOf(0,2,4,1,3,5),listOf(4,2,0,5,3,1),listOf(5,3,1,4,2,0),listOf(1,3,5,0,2,4))
        for(index in 1..8) {
            val bitmap=NativeImageOrientation.apply(original,index)
            val actual=IntArray(6);bitmap.getPixels(actual,0,bitmap.width,0,0,bitmap.width,bitmap.height)
            assertArrayEquals(expected[index-1].map { pixels[it] }.toIntArray(),actual)
            if(bitmap!==original)bitmap.recycle()
        }
        val data=java.io.ByteArrayOutputStream().also { original.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }.toByteArray();original.recycle()
        ResourceUiFixture().use { f->f.bytes=data;f.mediaType="image/png";f.name="关闭.png";f.updateLinks();connect(f);tap();preview()
            try {
                ui.waitUntil(5_000) { ui.onAllNodesWithTag("resource-preview-image").fetchSemanticsNodes().size==1 }
            } catch(error: Throwable) {
                val candidates=File(activity.cacheDir,"resources").walkTopDown().filter { it.isFile&&it.name==f.name }.map { file->
                    val options=android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds=true }
                    android.graphics.BitmapFactory.decodeFile(file.path,options)
                    JSONObject().put("name",file.name).put("size",file.length()).put("width",options.outWidth).put("height",options.outHeight)
                }.toList()
                File(owned.folder,"image-failure-diagnostic.json").writeText(JSONObject().put("expectedMediaType",f.mediaType)
                    .put("currentAttachmentMediaType",session.messages.firstOrNull { it.id=="old-assistant" }?.attachments?.firstOrNull()?.mediaType)
                    .put("files",JSONArray(candidates)).toString())
                runCatching { owned.dialogShot("failed-native-resource-image") };throw error
            }
            ui.onNodeWithTag("resource-preview-name").assertTextEquals("关闭.png");owned.dialogShot("native-resource-image")
            assertEquals(1,f.binaries());assertTrue(f.server.requests.all { it.method=="GET" })
        }
    }
}

internal class ResourceUiFixture: java.io.Closeable {
    val execution=ExecutionFixture()
    @Volatile var bytes="Native original resource 100% %@\n关闭\n".toByteArray()
    @Volatile var version="one"
    @Volatile var name="关闭"
    @Volatile var mediaType="text/plain"
    @Volatile var status=200
    @Volatile var rawError="owned resource error"
    @Volatile var binaryGate: (()->Unit)?=null
    @Volatile var includeAttachment=true
    lateinit var client: MobileClient
    val reference get()="codex-attachment://ui-"+version
    private fun hash()=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun file()=JSONObject().put("id","ui-"+version).put("hostId","fixture-host").put("threadId","fixture-thread").put("name",name)
        .put("mediaType",mediaType).put("sizeBytes",bytes.size).put("sha256",hash()).put("resourceId","stable-ui-resource")
    fun updateLinks() { execution.streamText="[Owned first]("+reference+")\n\n[Owned second]("+reference+")" }
    init { updateLinks() }
    val server=CommandFixture { request->
        val query=request.pathWithQuery.substringAfter('?').split('&').associate { java.net.URLDecoder.decode(it.substringBefore('='),"UTF-8") to java.net.URLDecoder.decode(it.substringAfter('='),"UTF-8") }
        when(request.path) {
            "/v1/attachments/resolve" -> if(status!=200)WireResponse(status,JSONObject().put("error",rawError).toString())
                else WireResponse(body=JSONObject().put("hostId","fixture-host").put("threadId","fixture-thread").put("sourceAttachmentId",query.getValue("id"))
                    .put("readTarget",CodexCommandCodec.target(client.scope!!)).put("attachment",file()).toString())
            "/v1/attachments" -> WireResponse(raw=bytes,beforeBody=binaryGate)
            else -> execution.resourceUiResponse(request).let { response->
                if(request.path!="/v1/history")response else {
                    val body=JSONObject(response.body)
                    body.getJSONArray("messages").objects().first { it.getString("id")=="old-assistant" }.put("attachments",if(includeAttachment)JSONArray().put(file()) else JSONArray())
                        .put("files",if(includeAttachment)JSONArray() else JSONArray().put(name))
                    response.copy(body=body.toString())
                }
            }
        }
    }
    fun binaries()=server.requests.count { it.path=="/v1/attachments" }
    fun resourceReads()=server.requests.count { it.path.startsWith("/v1/attachments") }
    override fun close() { server.close();execution.close() }
}
