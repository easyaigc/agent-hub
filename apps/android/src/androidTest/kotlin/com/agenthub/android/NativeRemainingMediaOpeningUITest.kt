package com.agenthub.android

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.text.AnnotatedString
import androidx.test.platform.app.InstrumentationRegistry
import com.agenthub.core.TargetScope
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/** Shipping UI/Session, synthetic original message and local PNG only. No transport or persisted Session. */
class NativeRemainingMediaOpeningUITest {
    @get:Rule(order=0) val timeout: org.junit.rules.TestRule = org.junit.rules.Timeout.seconds(60)
    @get:Rule(order=1) val ui = createAndroidComposeRule<ComponentActivity>()
    private lateinit var session: MobileSession
    private lateinit var port: MediaPort
    private var mounted by mutableStateOf(true)
    private var clipboardWrites = 0
    private val clipboard = object : ClipboardManager {
        override fun setText(annotatedString: AnnotatedString) { clipboardWrites++; error("No clipboard writes") }
        override fun getText(): AnnotatedString? = null
    }
    private fun <T> main(block: suspend () -> T): T = runBlocking { withContext(Dispatchers.Main) { block() } }
    private fun exists(tag: String) = ui.onAllNodesWithTag(tag, useUnmergedTree=true).fetchSemanticsNodes().isNotEmpty()
    private fun ready() {
        ui.waitUntil(5000) { !session.loading && session.threads.isNotEmpty() }
        main { session.selectThread(session.threads.single()) }
        ui.waitUntil(5000) { !session.historyLoading && session.messages.any { it.id=="media-original" } }
    }
    @Before fun setup() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        require(context.packageName=="com.easyai.agenthub.android.remaining20261121.acceptance")
        require(InstrumentationRegistry.getInstrumentation().context.packageName==context.packageName+".test")
        port = MediaPort(File(context.cacheDir,"resources/media-opening-"+UUID.randomUUID()))
        main {
            session = MobileSession(reader=port, commandProvider=port)
            session.connect("Owned media", "https://media-owned.invalid", "")
        }
        ready()
        main {
            session.text = "Unsent media draft 100% %@"
            ui.activity.setContent { ProductTextProvider("en") { MaterialTheme {
                CompositionLocalProvider(LocalClipboardManager provides clipboard) {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        if (mounted) session.messages.firstOrNull { it.id=="media-original" }?.let { message ->
                            MessageLinkOpening(session,message) {
                                MarkdownContent(message.text,"media-body")
                                MessageResourceCards(message)
                            }
                        }
                    }
                }
            } } }
        }
        ui.waitUntil(5000) { exists("media-body-0-media-0-image") }
    }
    @After fun cleanup() {
        if (::port.isInitialized) port.gate?.complete(Unit)
        if (::session.isInitialized) main {
            ui.activity.setContent {}
            session.leaveForeground()
            withTimeout(10000) { session.abandonMigrationRestore() }
        }
        if (::port.isInitialized) {
            ui.waitUntil(5000) { port.active.get()==0 }
            assertTrue(port.denied.isEmpty())
            // Only the UUID directory this fixture created, never the shared resources root.
            assertFalse(java.nio.file.Files.isSymbolicLink(port.folder.toPath()))
            assertTrue(port.folder.deleteRecursively())
            assertFalse(port.folder.exists())
        }
        assertEquals(0,clipboardWrites)
    }
    // Each case performs a separate single tap. SelectionContainer owns double-taps;
    // advancing the injected event timestamp prevents the next case becoming one.
    private fun touch(tag: String) = ui.onNodeWithTag(tag).performScrollTo().assertIsEnabled().performTouchInput {
        advanceEventTime(android.view.ViewConfiguration.getDoubleTapTimeout().toLong()+1L)
        click()
    }
    private fun closePreview() {
        ui.onNodeWithTag("resource-preview-close").assertIsDisplayed().performTouchInput { click() }
        ui.waitUntil(5000) { !exists("resource-preview") }
    }
    @Test fun bodyAndAttachmentTouchPrepareOriginalPngThenRetainOnlyCurrentOwnerThumbnail() {
        assertEquals(0,port.downloads.get()); assertTrue(port.resolved.isEmpty())
        ui.onNodeWithTag("media-body-0-media-0-thumbnail",useUnmergedTree=true).assertDoesNotExist()
        ui.onNodeWithTag("resource-card-thumbnail-media-file",useUnmergedTree=true).assertDoesNotExist()
        touch("media-body-0-media-0-image")
        ui.waitUntil(5000) { exists("resource-preview-image") && exists("media-body-0-media-0-thumbnail") }
        ui.onNodeWithTag("resource-preview-image").assertIsDisplayed()
        ui.runOnIdle { assertEquals(listOf("media-original" to "/owned/image.png"),port.resolved.toList()) }
        closePreview()
        ui.onNodeWithTag("media-body-0-media-0-thumbnail",useUnmergedTree=true).performScrollTo().assertIsDisplayed()
        // The attachment action must also traverse the unmodified original DTO path.
        touch("resource-card-preview-media-file")
        ui.waitUntil(5000) { exists("resource-preview-image") && exists("resource-card-thumbnail-media-file") }
        closePreview()
        ui.onNodeWithTag("resource-card-thumbnail-media-file",useUnmergedTree=true).performScrollTo().assertIsDisplayed()
        assertEquals(2,port.downloads.get()); assertEquals(1,port.resolved.size)
        val owner = main { session.messageLinkContext(session.messages.single()) }
        main { port.caption="Changed original"; session.loadHistory() }
        ui.waitUntil(5000) { !session.historyLoading && session.messages.any { it.text.contains("Changed original") } }
        ui.waitUntil(5000) { !exists("media-body-0-media-0-thumbnail") && !exists("resource-card-thumbnail-media-file") }
        main { assertFalse(session.messageLinkCurrent(owner)) }
        assertEquals("Unsent media draft 100% %@",session.text)
        // A new runtime is a different target even when the Host ID is unchanged.
        main { port.runtime="runtime-b"; session.connect("Owned media B","https://media-owned.invalid","") }
        ready()
        ui.onNodeWithTag("media-body-0-media-0-thumbnail",useUnmergedTree=true).assertDoesNotExist()
        ui.onNodeWithTag("resource-card-thumbnail-media-file",useUnmergedTree=true).assertDoesNotExist()
        assertEquals(2,port.downloads.get())
        assertTrue(session.operationRecords.isEmpty())
    }
    @Test fun latePreparationCannotOpenOrSeedThumbnailsAfterMessageTargetOrRowRetires() {
        // Two stable endpoints/targets, never mutate the live binding in place.
        port.bindStableTargets()
        val firstClient=main { session.selectedClient }
        val firstDraft=main { session.text }
        fun diagnostics(change: String,phase: String): String = main {
            val message=session.messages.singleOrNull()
            "case=$change phase=$phase mounted=$mounted loading=${session.historyLoading}"+
                " selected=${port.bindingLabel(session.selectedClient)} bindingMatches=${port.matchesBinding(session.selectedClient)}"+
                " threadMatches=${session.selectedThread=="media-thread"}"+
                " messagePresent=${message?.id=="media-original"}"+
                " ownerCurrent=${message?.let {session.messageLinkCurrent(session.messageLinkContext(it))}}"+
                " active=${port.active.get()} finished=${port.finished.get()} downloads=${port.downloads.get()}"+
                " resolves=${port.resolved.size} resolveAttempts=${port.resolveAttempts.get()}"+
                " rejected=${port.rejected.toList()} trace=${port.trace.toList()}"
        }
        fun waitAt(change: String,phase: String,predicate: ()->Boolean) {
            try { ui.waitUntil(5000,predicate) }
            catch(error: Throwable) { throw AssertionError(diagnostics(change,phase),error) }
        }
        fun readyAt(change: String) {
            try { ready() }
            catch(error: Throwable) { throw AssertionError(diagnostics(change,"connected-history-ready"),error) }
        }
        for (change in listOf("message","target","row")) {
            val doneBefore=port.finished.get()
            val readsBefore=port.downloads.get()
            val owner=main {
                check(port.matchesBinding(session.selectedClient)) { "case=$change before-touch binding mismatch: ${port.bindingLabel(session.selectedClient)}" }
                session.messageLinkContext(session.messages.single()).also { check(session.messageLinkCurrent(it)) }
            }
            waitAt(change,"current-markdown-ready") {
                ui.onAllNodes(hasTestTag("media-body-0-media-0-image") and hasText(port.caption)).fetchSemanticsNodes().isNotEmpty()
            }
            port.mark("$change:before-touch")
            port.gate=CompletableDeferred()
            touch("media-body-0-media-0-image")
            waitAt(change,"download-entered") { port.active.get()==1 && port.downloads.get()==readsBefore+1 }
            port.mark("$change:download-entered")
            when (change) {
                "message" -> {
                    main { port.caption += " changed"; session.loadHistory() }
                    waitAt(change,"message-replaced") { !session.historyLoading && session.messages.any { it.text.contains(port.caption) } }
                    main { assertFalse(session.messageLinkCurrent(owner)) }
                }
                "target" -> {
                    // Existing normal connection path creates a second client. Both
                    // endpoints continue serving their own original immutable target.
                    main { session.connect("Owned media B",MediaPort.SECOND_ENDPOINT,"") }
                    readyAt(change)
                    main {
                        check(port.matchesBinding(session.selectedClient)) { "case=$change connected binding mismatch: ${port.bindingLabel(session.selectedClient)}" }
                        assertNotEquals(firstClient.id,session.selectedClient.id)
                        assertFalse(sameScope(firstClient.scope,session.selectedClient.scope))
                        assertFalse(session.messageLinkCurrent(owner))
                        session.text="Unsent media target B 100% %@"
                    }
                }
                else -> ui.runOnIdle { mounted=false }
            }
            val currentDraft=main { session.text }
            ui.waitForIdle()
            port.mark("$change:release-original")
            checkNotNull(port.gate).complete(Unit)
            waitAt(change,"original-read-finished") { port.finished.get()==doneBefore+1 && port.active.get()==0 }
            main { yield() }; ui.waitForIdle()
            ui.onNodeWithTag("resource-preview").assertDoesNotExist()
            ui.onNodeWithTag("message-link-error").assertDoesNotExist()
            ui.onNodeWithTag("media-body-0-media-0-thumbnail",useUnmergedTree=true).assertDoesNotExist()
            ui.onNodeWithTag("resource-card-thumbnail-media-file",useUnmergedTree=true).assertDoesNotExist()
            main { assertEquals(currentDraft,session.text); assertTrue(session.operationRecords.isEmpty()) }
            assertTrue(diagnostics(change,"no-fixture-rejection"),port.rejected.isEmpty())
            assertTrue(port.denied.isEmpty()); assertEquals(0,clipboardWrites)
            ui.runOnIdle { mounted=true }; port.gate=null
            waitAt(change,"row-ready") {
                ui.onAllNodes(hasTestTag("media-body-0-media-0-image") and hasText(port.caption)).fetchSemanticsNodes().isNotEmpty()
            }
            port.mark("$change:completed")
        }
        assertEquals(3,port.downloads.get()); assertEquals(3,port.resolved.size)
        // Normal selection back to A proves old late completions did not replace
        // either target's draft. No new resource touch or fourth download.
        main { session.select(firstClient) }; readyAt("restore-A")
        main { assertTrue(sameScope(firstClient.scope,session.selectedClient.scope)); assertEquals(firstDraft,session.text) }
        ui.onNodeWithTag("resource-preview").assertDoesNotExist()
        ui.onNodeWithTag("media-body-0-media-0-thumbnail",useUnmergedTree=true).assertDoesNotExist()
        ui.onNodeWithTag("resource-card-thumbnail-media-file",useUnmergedTree=true).assertDoesNotExist()
        assertEquals(3,port.downloads.get()); assertTrue(session.operationRecords.isEmpty())
        // This is late preparation, not a pause inside an already-running BitmapFactory decode.
    }

    /** Small constructor-injected port following NativeSubagentHistoryUiTest's in-memory pattern. */
    private class MediaPort(val folder: File) : HostReads, CodexCommands {
        companion object { const val SECOND_ENDPOINT="https://media-second-owned.invalid" }
        var runtime="runtime-a"
        private var stableTargets=false
        private val firstTarget get()=TargetScope("codex",null,null,host,"media-workspace","runtime-a")
        private val secondTarget get()=TargetScope("codex",null,null,host+"-b","media-workspace-b","runtime-b")
        val resolveAttempts=AtomicInteger()
        val rejected=CopyOnWriteArrayList<String>()
        val trace=CopyOnWriteArrayList<String>()
        fun mark(value: String) { trace.add(value);while(trace.size>48)trace.removeAt(0) }
        fun bindStableTargets() { check(runtime=="runtime-a");stableTargets=true }
        fun bindingLabel(client: MobileClient)=when(client.endpoint) {
            "https://media-owned.invalid"->"A";SECOND_ENDPOINT->"B";else->"unknown"
        }
        private fun boundTarget(client: MobileClient): TargetScope = if(!stableTargets)target else when(bindingLabel(client)) {
            "A"->firstTarget;"B"->secondTarget;else->error("Unregistered synthetic endpoint")
        }
        fun matchesBinding(client: MobileClient)=runCatching {
            val expected=boundTarget(client)
            client.id==expected.hostId && sameScope(client.scope,expected)
        }.getOrDefault(false)
        private fun validateRead(client: MobileClient,stage: String) {
            if(!matchesBinding(client)) { rejected.add("$stage:${bindingLabel(client)}:binding-mismatch");error("Synthetic $stage target binding mismatch") }
        }
        var caption="Owned PNG"
        private val host="media-owned-"+UUID.randomUUID()
        private val target get()=TargetScope("codex",null,null,host,"media-workspace",runtime)
        val denied=CopyOnWriteArrayList<String>()
        val resolved=CopyOnWriteArrayList<Pair<String,String>>()
        val downloads=AtomicInteger(); val active=AtomicInteger(); val finished=AtomicInteger()
        var gate: CompletableDeferred<Unit>?=null
        private val png=ByteArrayOutputStream().use { out ->
            val bitmap=Bitmap.createBitmap(3,2,Bitmap.Config.ARGB_8888)
            try { bitmap.eraseColor(android.graphics.Color.MAGENTA); check(bitmap.compress(Bitmap.CompressFormat.PNG,100,out)); out.toByteArray() }
            finally { bitmap.recycle() }
        }
        init { check(!folder.exists() && folder.mkdirs()) }
        private fun file(scope: TargetScope=target)=MobileFile("media-file","owned.png","image/png",png.size.toLong(),scope.hostId,"media-thread",
            MessageDigest.getInstance("SHA-256").digest(png).joinToString("") { "%02x".format(it) })
        override suspend fun workspace(client: MobileClient,projectCursor: String?,threadCursor: String?): MobileWorkspace {
            val scope=boundTarget(client)
            // An initial connect candidate has no scope; ordinary selected reads must match.
            if(stableTargets && client.scope!=null)validateRead(client,"workspace")
            return MobileWorkspace(scope.hostId,"Owned media","desktop-runtime",emptyList(),listOf(MobileThread("media-thread",scope.hostId,"Media","idle")),
                null,null,scope,emptyList(),"{}",runtimeConnected=true)
        }
        override suspend fun history(client: MobileClient,threadId: String,cursor: String?): MobileHistory {
            if(stableTargets)validateRead(client,"history")
            check(threadId=="media-thread")
            val scope=boundTarget(client)
            val message=MobileMessage("media-original","assistant","![$caption](/owned/image.png)",attachments=listOf(file(scope)))
            return MobileHistory(scope.hostId,threadId,listOf(message),null,scope,"idle",null,"{}")
        }
        override suspend fun resolveMessageFile(client: MobileClient,threadId: String,messageId: String,reference: String): MobileFile {
            resolveAttempts.incrementAndGet();mark("resolve:${bindingLabel(client)}")
            validateRead(client,"resolve")
            check(threadId=="media-thread" && messageId=="media-original" && reference=="/owned/image.png")
            resolved.add(messageId to reference);return file(boundTarget(client))
        }
        override suspend fun downloadResourceWithProgress(client: MobileClient,file: MobileFile,cache: File,progress: ResourceDownloadProgressHandler?): ResourceDownloadSnapshot {
            mark("download-attempt:${bindingLabel(client)}")
            validateRead(client,"download")
            val sourceMatches=file==this.file(boundTarget(client))
            val cacheMatches=folder.parentFile.canonicalFile==cache.canonicalFile
            if(!sourceMatches || !cacheMatches) {
                rejected.add("download:${bindingLabel(client)}:source=$sourceMatches,cache=$cacheMatches")
                error("Synthetic immutable source/cache mismatch")
            }
            val id=downloads.incrementAndGet();active.incrementAndGet();mark("download-entered:${bindingLabel(client)}:$id")
            val capturedGate=gate
            try {
                // Keep the source/client/file captured before switching selection; do
                // not reinterpret A's late response against B's newly selected binding.
                return withContext(NonCancellable+Dispatchers.IO) {
                    capturedGate?.await()
                    val local=File(folder,"snapshot-$id.png");check(local.createNewFile());local.writeBytes(png)
                    ResourceDownloadSnapshot(file,local)
                }
            } finally { active.decrementAndGet();finished.incrementAndGet();mark("download-finished:$id") }
        }
        private fun forbid(name: String): Nothing { denied.add(name);error("Media fixture forbids $name") }
        override suspend fun models(client: MobileClient)=emptyList<CodexModel>()
        override suspend fun events(client: MobileClient,threadId: String,epoch: String,cursor: Long): EventBatch=forbid("events")
        override suspend fun send(client: MobileClient,intent: ReplyIntent): DeliveryReceipt=forbid("send")
        override suspend fun receipt(client: MobileClient,threadId: String,operationId: String): DeliveryReceipt=forbid("receipt")
        override suspend fun createThread(client: MobileClient,projectId: String?,operationId: String): MobileThread=forbid("createThread")
        override suspend fun prepareCreation(client: MobileClient,intent: CreationIntent): DesktopCreation=forbid("prepareCreation")
        override suspend fun creation(client: MobileClient,operationId: String,inspectOnly: Boolean): DesktopCreation=forbid("creation")
        override suspend fun submitCreation(client: MobileClient,operationId: String): DesktopCreation=forbid("submitCreation")
        override suspend fun detachCreation(client: MobileClient,operationId: String): DesktopCreation=forbid("detachCreation")
        override suspend fun open(client: MobileClient,threadId: String,operationId: String,queryOnly: Boolean): DesktopOpen=forbid("open")
        override suspend fun stop(client: MobileClient,threadId: String,turnId: String): Unit=forbid("stop")
        override suspend fun cancelQueue(client: MobileClient,threadId: String,operationId: String): Unit=forbid("cancelQueue")
        override suspend fun answer(client: MobileClient,threadId: String,turnId: String,approvalId: String,decision: String,answers: Map<String,List<String>>): Unit=forbid("answer")
        override suspend fun upload(client: MobileClient,threadId: String,file: CreationFile,bytes: ByteArray): MobileFile=forbid("upload")
        override suspend fun uploadCreation(client: MobileClient,operationId: String,file: CreationFile,bytes: ByteArray): Unit=forbid("uploadCreation")
    }
}
