package com.agenthub.android

import androidx.activity.compose.setContent
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.agenthub.core.*
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import java.time.Instant
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/** Product Compose/session with held read-only source; synthetic transport is not real owner proof. */
class MobileSuggestionComposerTest {
    private val args get()=InstrumentationRegistry.getArguments()
    @get:Rule(order=-1) val viewport: org.junit.rules.TestRule=NativeApprovalViewportRule(
        { args.getString("viewportOrientation") ?: "" },
        { java.io.File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir,"acceptance/P5-composer-viewport/${args.getString("viewportRun") ?: "fixture"}").apply { mkdirs() } },{"suggestion"})
    @get:Rule(order=0) val foreground: org.junit.rules.TestRule=NativeTestForeground()
    @get:Rule(order=1) val ui=createAndroidComposeRule<MainActivity>()
    private lateinit var owned: OwnedControlAcceptance
    private val session get()=owned.session
    private val target=TargetScope("codex",null,null,"suggestion-fixture","workspace",UUID.randomUUID().toString())
    private var body="保留中文、换行与标点。\nNext step from the desktop."
    private var source="turn-original"
    private var lease=6_000L
    private var failHistory=false
    private var readGate: CompletableDeferred<Unit>?=null
    private var historyGate: CompletableDeferred<Unit>?=null
    private val reads=AtomicInteger()
    private var writes=0
    private fun <T> main(block: suspend ()->T): T=runBlocking { withContext(Dispatchers.Main) { block() } }
    private fun response(s: SuggestionScope): MobileDesktopSuggestionResponse {
        val text=body;val hash=MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
        return MobileDesktopSuggestionResponse(1,target,s.threadId,s.sourceTurnId,true,
            MobileDesktopSuggestion("same-provider-id",text,hash,Instant.now().plusMillis(lease).toString(),"native-version"),"native-trace")
    }
    @Before fun setup() {
        require(InstrumentationRegistry.getInstrumentation().targetContext.packageName=="com.easyai.agenthub.android.acceptance")
        ui.waitUntil(30_000) { !ui.activity.session.restoring }
        val reader=object: HostReads {
            override suspend fun workspace(client: MobileClient,projectCursor: String?,threadCursor: String?)=HostReadCodec.workspace(JSONObject()
                .put("hostId",target.hostId).put("name","Suggestion fixture").put("connectionMode","desktop-runtime").put("readTarget",CodexCommandCodec.target(target))
                .put("agentConnectionState",JSONObject().put("runtimeConnected",true))
                .put("capabilities",JSONArray(listOf("reply","models:read","attachments:upload","generation-settings","commands:target-bound:v1","desktop:suggestions:native-version:v1")))
                .put("projects",JSONArray()).put("threads",JSONArray(listOf("a","b").map { JSONObject().put("id",it).put("hostId",target.hostId).put("title",it).put("state","idle") })).toString())
            override suspend fun history(client: MobileClient,threadId: String,cursor: String?): MobileHistory {
                historyGate?.await()
                if(failHistory)throw HostReadError("Synthetic interruption",transient=true)
                return HostReadCodec.history(JSONObject().put("hostId",target.hostId).put("threadId",threadId).put("readTarget",CodexCommandCodec.target(target)).put("messages",JSONArray())
                    .put("runtime",JSONObject().put("state","idle").put("turnId",JSONObject.NULL).put("suggestionSourceTurnId",source)
                        .put("plan",JSONArray()).put("error",JSONObject.NULL).put("approvals",JSONArray()).put("receipts",JSONArray()).put("canControl",true)).toString())
            }
            override suspend fun nextTurnSuggestion(client: MobileClient,scope: SuggestionScope): MobileDesktopSuggestionResponse {
                reads.incrementAndGet();val captured=response(scope);readGate?.await();return captured
            }
        }
        val commands=object: CodexCommands by NativeCodexCommands({ "" }) {
            override suspend fun models(client: MobileClient)=listOf(CodexModel("fixture-model","Fixture",true,"low",listOf("low"),"standard",listOf("standard")))
            override suspend fun send(client: MobileClient,intent: ReplyIntent): DeliveryReceipt { writes++;error("Suggestion interaction must never send") }
        }
        ui.runOnIdle {
            owned=OwnedControlAcceptance(ui.activity,"suggestion-ui");session.resetAcceptance(reader,commands)
            session.changeAppearance(args.getString("viewportTheme") ?: "system")
            java.io.File(owned.folder,"actual-profile.json").writeText(JSONObject().put("appearance",session.appearance)
                .put("fontScale",ui.density.fontScale).put("orientation",ui.activity.resources.configuration.orientation).toString())
        }
        main { session.connect("Suggestion fixture","https://fixture.invalid","") }
        ui.waitUntil(10_000) { session.hasClient&&!session.loading&&session.threads.size==2 }
        select("a");waitHint()
    }
    private fun select(id: String) {
        ui.runOnIdle { session.selectThread(session.threads.single { it.id==id }) }
        ui.waitUntil(10_000) { !session.historyLoading&&session.selectedThread==id&&session.canCompose }
    }
    private fun waitHint() {
        try { ui.waitUntil(10_000) { main { session.availableSuggestion!=null&&session.suggestionIntent()!=null } } }
        catch(failure: Throwable) {
            java.io.File(owned.folder,"hint-timeout.json").writeText(main { JSONObject().put("reads",reads.get())
                .put("liveScope",session.nextSuggestionScope?.navigation).put("presentationScope",session.suggestionPresentationScope?.navigation)
                .put("readerScope",session.desktopSuggestionReader.scope?.navigation).put("storedSuggestion",session.desktopSuggestionReader.suggestion?.toString())
                .put("visible",session.availableSuggestion?.toString()).put("text",session.text).put("online",session.selectedClient.online).toString() })
            throw failure
        }
    }
    private fun tap(tag: String) {
        val node=ui.onNodeWithTag(tag).performScrollTo().assertIsDisplayed()
        var previous: Pair<androidx.compose.ui.geometry.Rect,Int>?=null;var stableAt=android.os.SystemClock.elapsedRealtime()
        ui.waitUntil(5_000) {
            val position=node.fetchSemanticsNode().boundsInRoot to ui.activity.window.decorView.height
            val now=android.os.SystemClock.elapsedRealtime()
            if(position!=previous) { previous=position;stableAt=now }
            now-stableAt>=350
        }
        node.performClick();ui.waitForIdle()
    }
    private fun shot(tag: String,name: String) {
        if(tag!="suggestion-preview") { owned.nodeShot(ui.onNodeWithTag(tag),name);return }
        ui.waitForIdle();val i=InstrumentationRegistry.getInstrumentation();var window: android.view.Window?=null
        androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.isRoot())
            .inRoot(androidx.test.espresso.matcher.RootMatchers.isDialog()).check { view,error ->
                if(error!=null)throw error
                assertEquals(i.targetContext.packageName,view.context.packageName)
                fun find(v: android.view.View) {
                    if(v is androidx.compose.ui.window.DialogWindowProvider)window=v.window
                    if(v is android.view.ViewGroup)for(n in 0 until v.childCount)find(v.getChildAt(n))
                };find(view.rootView);assertTrue(window!!.decorView.rootView===view.rootView)
            }
        val w=checkNotNull(window);var image: android.graphics.Bitmap?=null
        i.runOnMainSync { assertTrue(w.decorView.hasWindowFocus());image=android.graphics.Bitmap.createBitmap(w.decorView.width,w.decorView.height,android.graphics.Bitmap.Config.ARGB_8888) }
        val bitmap=checkNotNull(image);val done=java.util.concurrent.CountDownLatch(1);var result=-1
        try {
            i.runOnMainSync { android.view.PixelCopy.request(w,bitmap,{result=it;done.countDown()},android.os.Handler(android.os.Looper.getMainLooper())) }
            assertTrue(done.await(10,java.util.concurrent.TimeUnit.SECONDS));assertEquals(android.view.PixelCopy.SUCCESS,result)
            java.io.File(owned.folder,"$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }
            java.io.File(owned.folder,"$name-window.json").writeText(JSONObject().put("method","PixelCopy.owned-dialog-window").put("package",i.targetContext.packageName).put("pixelCopyResult",result).toString())
        }finally { bitmap.recycle() }
    }
    @After fun finish() {
        if(!::owned.isInitialized)return
        main { readGate?.complete(Unit);historyGate?.complete(Unit);session.leaveForeground();owned.verifyDefault() }
        assertEquals(0,writes)
    }
    @Test fun greyTextIsNotInputAndAdoptionUndoOnlyChangeLocalText() {
        ui.onNodeWithTag("message-input").assertTextEquals("");ui.onNodeWithTag("send-message").assertIsNotEnabled()
        ui.onNodeWithTag("suggestion-text").assertTextEquals(body);shot("composer","ghost-empty-send-disabled")
        tap("suggestion-details");ui.onNodeWithTag("suggestion-full-text").assertTextEquals(body)
        shot("suggestion-preview","full-selectable-original-text")
        ui.onNodeWithTag("suggestion-details-close").performClick();assertEquals("",session.text)
        tap("suggestion-adopt");ui.onNodeWithTag("message-input").assertTextEquals(body)
        assertEquals(body,session.text);assertEquals(0,writes);shot("composer","adopted-local-text")
        tap("suggestion-undo");assertEquals("",session.text);waitHint()
        ui.onNodeWithTag("send-message").assertIsNotEnabled()
    }
    @Test fun englishSuggestionControlsKeepOriginalProviderTextAndOnlyEditTheLocalDraft() {
        main { session.changeLanguage("en");lease=60_000 }
        ui.waitUntil(8_000) { main { (session.availableSuggestion?.expirationMillis ?: 0.0)>System.currentTimeMillis()+50_000 } }
        ui.onNodeWithTag("suggestion-text").assertTextEquals(body)
        ui.onNodeWithTag("suggestion-adopt").assertTextEquals("Use")
        tap("suggestion-details")
        ui.onNodeWithTag("suggestion-full-text").assertTextEquals(body)
        ui.onNodeWithTag("suggestion-apply").assertTextEquals("Use in composer")
        shot("suggestion-preview","english-original-source-details")
        tap("suggestion-apply");assertEquals(body,session.text)
        ui.onNodeWithTag("suggestion-undo").assertTextEquals("Undo")
        tap("suggestion-undo");assertEquals("",session.text);assertEquals(0,writes)
    }
    @Test fun retiredNativeEditorActionsCannotRestoreReplacedOrRevisitedText() {
        val node=ui.onNodeWithTag("message-input").fetchSemanticsNode()
        val old=node.config[androidx.compose.ui.semantics.SemanticsActions.SetText].action!!
        ui.runOnIdle { old(androidx.compose.ui.text.AnnotatedString("first"));old(androidx.compose.ui.text.AnnotatedString("second")) }
        assertEquals("second",session.text)
        main { session.text="Independent replacement" };ui.waitForIdle()
        assertNotEquals(node.id,ui.onNodeWithTag("message-input").fetchSemanticsNode().id)
        ui.runOnIdle { old(androidx.compose.ui.text.AnnotatedString("late original input")) };assertEquals("Independent replacement",session.text)
        val beforeRevisit=ui.onNodeWithTag("message-input").fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsActions.SetText].action!!
        select("b");select("a");waitHint()
        ui.runOnIdle { beforeRevisit(androidx.compose.ui.text.AnnotatedString("late revisited input")) }
        assertEquals("Independent replacement",session.text);assertEquals(0,writes)
    }
    @Test fun oldVisibleGhostButtonCannotAdoptAReplacementVersion() {
        val node=ui.onNodeWithTag("suggestion-adopt").fetchSemanticsNode()
        val old=node.config[androidx.compose.ui.semantics.SemanticsActions.OnClick].action!!
        val original=main { session.suggestionIntent()!! }
        main { body="A replacement version with the same provider ID" }
        ui.waitUntil(8_000) { main { session.availableSuggestion?.text==body } };ui.waitForIdle()
        assertNotEquals(node.id,ui.onNodeWithTag("suggestion-adopt").fetchSemanticsNode().id)
        ui.runOnIdle {
            val rejection=runCatching { old() }.exceptionOrNull()
            if(rejection!=null)assertTrue(rejection is IllegalStateException&&rejection.message.orEmpty().contains("not currently attached"))
            assertFalse(session.adoptSuggestion(original,"fill"))
        };assertEquals("",session.text)
        val beforeClear=ui.onNodeWithTag("suggestion-adopt").fetchSemanticsNode()
        val beforeClearClick=beforeClear.config[androidx.compose.ui.semantics.SemanticsActions.OnClick].action!!
        val beforeClearIntent=main { session.suggestionIntent()!! }
        main { session.text="Typed then explicitly cleared";session.text="" };ui.waitForIdle()
        assertNotEquals(beforeClear.id,ui.onNodeWithTag("suggestion-adopt").fetchSemanticsNode().id)
        ui.runOnIdle {
            val rejection=runCatching { beforeClearClick() }.exceptionOrNull()
            if(rejection!=null)assertTrue(rejection is IllegalStateException&&rejection.message.orEmpty().contains("not currently attached"))
            assertFalse(session.adoptSuggestion(beforeClearIntent,"fill"))
        };assertEquals("",session.text)
        tap("suggestion-adopt");assertEquals(body,session.text);assertEquals(0,writes)
    }
    @Test fun appendReplaceConfirmationAndCancelPreserveOriginalUntilExplicitAction() {
        // This case measures multi-dialog controls, not the production six-second
        // expiry (covered separately). Slow native animations must not race its fixture lease.
        main { lease=60_000 }
        ui.waitUntil(8_000) { main { (session.availableSuggestion?.expirationMillis ?: 0.0)>System.currentTimeMillis()+50_000 } }
        ui.onNodeWithTag("message-input").performTextReplacement("Original draft")
        tap("suggestion-entry");tap("suggestion-replace")
        ui.onNodeWithTag("suggestion-cancel-replace").performClick();assertEquals("Original draft",session.text)
        shot("suggestion-preview","replace-cancel-preserves-draft")
        tap("suggestion-apply");assertEquals("Original draft\n\n$body",session.text)
        tap("suggestion-undo");assertEquals("Original draft",session.text)
        tap("suggestion-entry");tap("suggestion-replace")
        ui.onNodeWithTag("suggestion-confirm-replace").performClick();assertEquals(body,session.text)
        ui.onNodeWithTag("message-input").performTextReplacement("New independent edit")
        assertNull(main { session.validSuggestionUndo });ui.onNodeWithTag("suggestion-undo").assertDoesNotExist()
    }
    @Test fun ignoredVersionStaysHiddenOnLeaseRenewalButNewHashAndExplicitClearRevive() {
        tap("suggestion-details");tap("suggestion-ignore")
        val start=reads.get();ui.waitUntil(8_000) { reads.get()>start }
        assertNull(main { session.availableSuggestion });ui.onNodeWithTag("suggestion-text").assertDoesNotExist()
        main { body="New text with the same provider ID" };waitHint();ui.onNodeWithTag("suggestion-text").assertTextEquals(body)
        tap("suggestion-details");tap("suggestion-ignore")
        ui.onNodeWithTag("message-input").performTextReplacement("Edit");ui.onNodeWithTag("message-input").performTextClearance();waitHint()
        ui.onNodeWithTag("suggestion-text").assertTextEquals(body)
    }
    @Test fun capturedActionsCannotAffectAnotherOrRevisitedTargetAndOldSurfaceCannotReleaseNew() {
        val old=main { session.suggestionIntent()!! };select("b");waitHint()
        main { assertFalse(session.adoptSuggestion(old,"fill"));assertFalse(session.ignoreSuggestion(old)) }
        assertEquals("",session.text);select("a");waitHint()
        main { assertFalse(session.adoptSuggestion(old,"replace"));assertFalse(session.ignoreSuggestion(old))
            session.claimSuggestionSurface("old-view");session.claimSuggestionSurface("new-view")
            session.reconcileSuggestionSurface("new-view",true,false);session.releaseSuggestionSurface("old-view")
            assertNotNull(session.suggestionIntent());session.releaseSuggestionSurface("new-view") }
    }
    @Test fun sidebarPausesWithoutExtendingLeaseAndUndoSurvivesReaderExpiryAndRecomposition() {
        val action=main { session.suggestionIntent()!! };tap("suggestion-adopt")
        val undo=main { session.validSuggestionUndo!! }
        main { session.desktopSuggestionReader.clear() }
        ui.runOnIdle { ui.activity.setContent { AgentHubApp(session) } }
        ui.waitForIdle();assertSame(undo,main { session.validSuggestionUndo })
        tap("suggestion-undo");waitHint()
        ui.onNodeWithTag("open-sidebar").performClick();ui.waitForIdle()
        ui.onNodeWithTag("sidebar-backdrop").assertIsDisplayed()
        val expires=main { session.availableSuggestion!!.expirationMillis!! }
        val before=reads.get();Thread.sleep((expires-System.currentTimeMillis()+300).coerceAtLeast(0.0).toLong())
        ui.waitForIdle();assertNull(main { session.availableSuggestion });assertEquals(before,reads.get())
        assertFalse(main { session.adoptSuggestion(action,"fill") });assertEquals("",session.text)
    }
    @Test fun workspaceRecoveryAloneCannotReviveAdoptionBeforeFreshRuntime() {
        val old=main { session.suggestionIntent()!! };failHistory=true
        main { session.loadHistory(userInitiated=false)?.join() }
        ui.waitUntil(5_000) { main { session.nextSuggestionScope==null } }
        assertNotNull(main { session.availableSuggestion });assertFalse(main { session.adoptSuggestion(old,"fill") })
        main { session.refresh() };ui.waitUntil(5_000) { !session.loading&&session.selectedClient.online };assertNull(main { session.nextSuggestionScope })
        failHistory=false;main { session.loadHistory()?.join() };waitHint()
        assertNotNull(main { session.nextSuggestionScope })
    }
    @Test fun focusedPlainTabAdoptsAndModifiedTabDoesNot() {
        ui.onNodeWithTag("message-input").performScrollTo().performClick()
        ui.onNodeWithTag("message-input").performKeyInput { keyDown(Key.ShiftLeft);pressKey(Key.Tab);keyUp(Key.ShiftLeft) }
        assertEquals("",session.text)
        ui.onNodeWithTag("message-input").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.RequestFocus) { it() }
        ui.onNodeWithTag("message-input").assertIsFocused();waitHint();ui.waitForIdle()
        ui.onNodeWithTag("message-input").performKeyInput { pressKey(Key.Tab) };ui.waitForIdle()
        assertEquals(body,session.text);ui.onNodeWithTag("message-input").assertIsFocused();assertEquals(0,writes)
    }
    @Test fun renewedLeaseKeepsPreviewButSourceAndDraftChangesDismissIt() {
        tap("suggestion-details");ui.onNodeWithTag("suggestion-preview").assertIsDisplayed();ui.waitForIdle();val count=reads.get()
        Thread.sleep(2_300);assertEquals(count,reads.get());ui.onNodeWithTag("suggestion-preview").assertIsDisplayed()
        main { session.text="New edit while old window is open" }
        ui.waitUntil(5_000) { ui.onAllNodesWithTag("suggestion-preview").fetchSemanticsNodes().isEmpty() }
        tap("suggestion-entry")
        main { source="replacement-source";session.loadHistory()?.join() }
        ui.waitUntil(5_000) { ui.onAllNodesWithTag("suggestion-preview").fetchSemanticsNodes().isEmpty() }
        assertEquals("New edit while old window is open",session.text);waitHint()
        shot("composer","new-source-independent-draft")
    }
    @Test fun localAdoptionAndUndoKeepOriginalFilesAndIndependentSettings() {
        ui.waitUntil(10_000) { session.models.isNotEmpty() }
        val settings=GenerationSettings("fixture-model","low","read-only","standard","default")
        main {
            session.text="Original text and files";session.configure(settings)
            session.importAttachments(session.attachmentImportDestination(),listOf({ LocalAttachmentInput("original.txt","text/plain","Original encrypted bytes".toByteArray()) }))!!.await()
        }
        val files=session.attachments.map { it.manifest };assertEquals(1,files.size);val snapshot=main { session.suggestionDraft }
        tap("suggestion-entry");tap("suggestion-apply")
        assertEquals("${snapshot.text}\n\n$body",session.text);assertEquals(files,session.attachments.map { it.manifest });assertEquals(settings,session.generationSettings)
        val changed=settings.copy(effort=null)
        main { session.configure(changed) };tap("suggestion-undo")
        assertEquals(snapshot.text,session.text);assertEquals(files,session.attachments.map { it.manifest });assertEquals(changed,session.generationSettings)
        assertArrayEquals("Original encrypted bytes".toByteArray(),main { NativeDraftFiles(owned.storage).bytes(files.single()) })
        val i=InstrumentationRegistry.getInstrumentation()
        i.runOnMainSync { androidx.core.view.SoftwareKeyboardControllerCompat(ui.activity.window.decorView).hide() }
        ui.waitUntil(5_000) { androidx.core.view.ViewCompat.getRootWindowInsets(ui.activity.window.decorView)?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime())!=true }
        val controls=ui.onNodeWithTag("bottom-controls")
        controls.performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.ScrollBy) { it(0f,-1_000_000f) };ui.waitForIdle()
        shot("bottom-controls","original-files-settings-after-undo-top")
        ui.onNodeWithTag("composer").performScrollTo().assertIsDisplayed();ui.waitForIdle()
        shot("bottom-controls","original-files-settings-after-undo-bottom")
        shot("composer","exact-undo-with-original-files-settings");assertEquals(0,writes)
    }
    @Test fun cachedPrefetchWaitsForFreshHistoryBeforePublicationAndAdoption() {
        val old=main { session.suggestionIntent()!! };select("b");waitHint()
        val gate=CompletableDeferred<Unit>();val count=reads.get()
        main { historyGate=gate;session.selectThread(session.threads.single { it.id=="a" }) }
        ui.waitUntil(5_000) { reads.get()>count };ui.waitForIdle()
        assertNull(main { session.nextSuggestionScope });assertNull(main { session.availableSuggestion })
        assertFalse(main { session.adoptSuggestion(old,"fill") });assertEquals("",session.text)
        val prepared=reads.get();assertEquals(count+1,prepared)
        main { historyGate=null;gate.complete(Unit) };waitHint()
        assertEquals(prepared,reads.get());assertEquals(body,main { session.availableSuggestion!!.text });assertEquals(0,writes)
        shot("composer","prefetch-only-after-original-runtime-confirmed")
    }
    @Test fun attachmentPopupRemainsOwnedWhenTheGhostLeaseExpires() {
        tap("suggestion-adopt");tap("suggestion-undo");waitHint()
        tap("composer-add")
        ui.onNodeWithTag("attachment-panel-list").performScrollToNode(hasTestTag("choose-files"))
        ui.waitUntil(5_000) { runCatching { ui.onNodeWithTag("choose-files").assertIsDisplayed();true }.getOrDefault(false) }
        val expiry=main { session.availableSuggestion!!.expirationMillis!! };val count=reads.get()
        Thread.sleep((expiry-System.currentTimeMillis()+300).coerceAtLeast(0.0).toLong());ui.waitForIdle()
        assertNull(main { session.availableSuggestion });assertEquals(count,reads.get())
        ui.onNodeWithTag("choose-files").assertIsDisplayed();ui.onNodeWithTag("choose-files").onParent().onChildren().filter(hasTestTag("toggle-plan-mode")).assertCountEquals(0)
        owned.dialogShot("expired-ghost-keeps-owned-attachment-panel")
    }
    @Test fun recentPhotoPreviewRemainsOwnedAndSuspendsDesktopSuggestionRenewal() {
        val photos=HintPhotoSource(ui.activity)
        main { photos.rows=listOf(photos.photo(1));session.configureRecentPhotos(ui.activity,photos) }
        val library=session.recentPhotos!!
        try {
            ui.waitUntil(10_000) { library.ready&&library.revision>0&&!library.loading }
            main { photos.publish(listOf(photos.photo(2))+photos.rows) }
            ui.waitUntil(10_000) { library.suggestion!=null };val expiry=main { session.availableSuggestion!!.expirationMillis!! }
            ui.onNodeWithTag("recent-attachment-preview-open").performClick();ui.onNodeWithTag("recent-attachment-preview").assertExists();val count=reads.get()
            Thread.sleep((expiry-System.currentTimeMillis()+300).coerceAtLeast(0.0).toLong());ui.waitForIdle()
            assertNull(main { session.availableSuggestion });assertEquals(count,reads.get());assertEquals(0,photos.inputCalls.get());assertTrue(session.attachments.isEmpty());assertEquals("",session.text);assertEquals(0,writes)
            ui.onNodeWithTag("recent-attachment-preview").assertExists();owned.dialogShot("photo-preview-after-desktop-lease-expired")
            ui.onNodeWithTag("recent-attachment-preview-close").performClick();waitHint();assertTrue(reads.get()>count)
        }finally { main { library.setActive(false) };photos.cleanup() }
    }
    @Test fun longDetailsRemainReadableSelectableWithReachableDraftControls() {
        main { lease=120_000;body=(0 until 16).joinToString("\n") { "Line $it — 中文原文 and full native text." };session.text="Original long-view draft" }
        ui.waitUntil(10_000) { main { session.availableSuggestion?.text==body } };tap("suggestion-entry")
        val text=ui.onNodeWithTag("suggestion-full-text");val viewport=ui.onNodeWithTag("suggestion-body")
        val layouts=mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        text.performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val layout=layouts.single();assertEquals(body,layout.layoutInput.text.text);assertFalse(layout.hasVisualOverflow)
        val covered=mutableSetOf<Int>();var scans=0
        while(covered.size<layout.lineCount&&scans<160) {
            val frame=viewport.fetchSemanticsNode().boundsInRoot;val origin=text.getUnclippedBoundsInRoot().top.value*ui.density.density
            val lineHeight=(0 until layout.lineCount).maxOf { layout.getLineBottom(it)-layout.getLineTop(it) }
            assertTrue("The short viewport must fit a complete native text line",frame.height+1.5f>=lineHeight)
            for(line in 0 until layout.lineCount)if(origin+layout.getLineTop(line)>=frame.top-1.5f&&origin+layout.getLineBottom(line)<=frame.bottom+1.5f)covered+=line
            if(scans==0)shot("suggestion-preview","long-text-first-window")
            scans++
            if(covered.size<layout.lineCount) {
                // Use the measured next line instead of fling velocity, which can
                // skip whole lines in a one-line large-font viewport.
                val next=(0 until layout.lineCount).first { it !in covered }
                val delta=origin+layout.getLineTop(next)-frame.top
                viewport.performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.ScrollBy) { it(0f,delta) };ui.waitForIdle()
            }
        }
        java.io.File(owned.folder,"long-read-coverage.json").writeText(JSONObject().put("lineCount",layout.lineCount).put("covered",JSONArray(covered.sorted())).put("scans",scans).put("syntheticFixtureLeaseMillis",120_000).put("productionLeaseChanged",false).toString())
        assertEquals("Every logical layout line can be fully read",layout.lineCount,covered.size)
        for(tag in listOf("suggestion-apply","suggestion-replace","suggestion-ignore")) {
            val button=ui.onNodeWithTag(tag).performScrollTo().assertIsDisplayed().assertIsEnabled()
            assertTrue(button.getUnclippedBoundsInRoot().let { it.bottom.value-it.top.value>=43.5f })
        }
        shot("suggestion-preview","long-text-last-window-and-controls")
        viewport.performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.ScrollBy) { it(0f,-1_000_000f) };ui.waitForIdle()
        val frame=viewport.fetchSemanticsNode().boundsInRoot;val origin=text.getUnclippedBoundsInRoot().top.value*ui.density.density
        viewport.performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.ScrollBy) { it(0f,origin-frame.top) };ui.waitForIdle()
        text.performTouchInput { longClick(layout.getBoundingBox(2).center) }
        ui.waitUntil(5_000) { ui.onAllNodes(SemanticsMatcher("Native visible selection handle") { n -> n.config.any { it.key.name=="SelectionHandleInfo"&&it.value.toString().contains("visible=true") } },useUnmergedTree=true).fetchSemanticsNodes().size==2 }
        shot("suggestion-preview","long-text-native-selection");assertEquals("Original long-view draft",session.text);assertEquals(0,writes)
    }
}
