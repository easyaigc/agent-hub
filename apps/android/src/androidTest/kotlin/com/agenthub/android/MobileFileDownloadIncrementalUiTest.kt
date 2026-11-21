package com.agenthub.android

import android.text.format.Formatter
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.platform.app.InstrumentationRegistry
import com.agenthub.core.RuntimeIdentity
import com.agenthub.core.TargetScope
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.TestName
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Production reader/session/Compose boundaries against task-owned synthetic bytes only.
 * Empty acceptance Activity: no default Session, clipboard, mic, desktop or model startup. */
class MobileFileDownloadIncrementalUiTest {
    @get:Rule(order=0) val timeout: org.junit.rules.TestRule=org.junit.rules.Timeout.seconds(120)
    @get:Rule(order=1) val ui=createAndroidComposeRule<NativeSavedVoicePanelActivity>()
    @get:Rule(order=2) val testName=TestName()
    private lateinit var storage: NativeSessionStorage
    private lateinit var session: MobileSession
    private lateinit var folder: File
    private lateinit var cache: File
    private lateinit var ownedKeyAlias: String
    private val tasks=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private val fixtures=mutableListOf<ResourceUiFixture>()
    private val gates=mutableListOf<CountDownLatch>()
    private val observations=JSONArray()
    private var complete=false
    private var copied: AnnotatedString?=null
    private val clipboard=object: ClipboardManager {
        override fun setText(annotatedString: AnnotatedString) {copied=annotatedString}
        override fun getText(): AnnotatedString?=copied
    }
    private val childReference="child%20file.md#deep-target"
    private val missingReference="missing source.md#nowhere"
    private fun hash(bytes: ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {"%02x".format(it)}
    private fun <T> main(block: suspend ()->T): T=runBlocking {withContext(Dispatchers.Main) {block()}}
    private fun gate()=CountDownLatch(1).also(gates::add)
    private fun await(gate: CountDownLatch) {check(gate.await(15,TimeUnit.SECONDS)) {"Owned synthetic gate timed out"}}
    private fun count(f: ResourceUiFixture,path: String)=f.server.requests.count {it.path==path}
    private fun field(tag: String)=ui.onNodeWithTag(tag).fetchSemanticsNode().config[SemanticsProperties.Text].joinToString {it.text}
    private fun shown(text: String)=runCatching {ui.onNodeWithText(text).assertIsDisplayed();true}.getOrDefault(false)
    @Before fun setup() {
        val i=InstrumentationRegistry.getInstrumentation()
        require(i.targetContext.packageName=="com.easyai.agenthub.android.acceptance")
        require(i.context.packageName==i.targetContext.packageName+".test")
        val lease=checkNotNull(InstrumentationRegistry.getArguments().getString("f32IncrementalRun"))
        require(UUID.fromString(lease).toString()==lease)
        val namespace="P3-f32-"+UUID.randomUUID()
        folder=File(ui.activity.filesDir,"acceptance/P3-f32/$lease/${testName.methodName}")
        require(!folder.exists());check(folder.mkdirs())
        cache=File(ui.activity.cacheDir,namespace).also {check(it.mkdir())}
        ownedKeyAlias=namespace+"-key"
        // This empty Activity never starts a normal Session restore. Initialize only the
        // dedicated synthetic fixture before observing its state; no default reader is used.
        main {storage=NativeSessionStorage(ui.activity,namespace,ownedKeyAlias);session=MobileSession(storage)
            session.resetAcceptance();session.changeLanguage("en")}
    }
    @After fun finish() {
        var failure:Throwable?=null
        fun attempt(block:()->Unit) {try {block()}catch(error:Throwable) {
            if(failure==null)failure=error else failure!!.addSuppressed(error)
        }}
        var testScopeJoined=false;var sessionJoined=false;var writesDrained=false;var writerRetired=false
        var listenersClosed=0;var cachesRemoved=false
        // Each stage is independent: a producer/drain failure must not skip listener closure
        // or the FAILED_OR_UNKNOWN index, and unknown producers never authorize deletion.
        gates.forEach {gate->attempt {gate.countDown()}}
        if(::session.isInitialized)attempt {main {ui.activity.setContent {};session.leaveForeground()}}
        attempt {runBlocking {withTimeout(10_000) {tasks.coroutineContext[Job]!!.cancelAndJoin()}};testScopeJoined=true}
        if(::session.isInitialized)attempt {main {withTimeout(10_000) {session.abandonMigrationRestore()}};sessionJoined=true}
        if(testScopeJoined&&sessionJoined&&::storage.isInitialized) {
            attempt {main {withTimeout(10_000) {storage.awaitQueuedWrites()}};writesDrained=true}
            if(writesDrained)attempt {storage.retireWriter();writerRetired=true}
        }
        try {
            fixtures.forEach {f->attempt {f.close();listenersClosed++}}
            if(testScopeJoined&&sessionJoined&&writerRetired)attempt {
                fixtures.forEach {f ->
                    // Only this fixture's authenticated synthetic runtime directory.
                    runCatching {f.client}.getOrNull()?.scope?.let {target ->
                        val root=File(ui.activity.cacheDir,"resources/codex-"+hash(RuntimeIdentity.readingKey(target,"fixture-thread").toByteArray()))
                        if(root.isDirectory && root.canonicalFile.parentFile==File(ui.activity.cacheDir,"resources").canonicalFile) {
                            check(!java.nio.file.Files.isSymbolicLink(root.toPath()))
                            check(root.walkTopDown().none {java.nio.file.Files.isSymbolicLink(it.toPath())})
                            check(root.deleteRecursively());check(!root.exists())
                        }
                    }
                }
                if(::cache.isInitialized) {
                    check(!java.nio.file.Files.isSymbolicLink(cache.toPath()))
                    check(cache.walkTopDown().none {java.nio.file.Files.isSymbolicLink(it.toPath())})
                    check(cache.deleteRecursively());check(!cache.exists())
                }
                cachesRemoved=true
            }
        } finally {
            if(::folder.isInitialized)attempt {
                val passed=complete&&failure==null&&testScopeJoined&&sessionJoined&&writesDrained&&writerRetired&&listenersClosed==fixtures.size&&cachesRemoved
                val result=JSONObject().put("completed",passed).put("bodyCompleted",complete).put("observations",observations)
                    .put("testScopeJoined",testScopeJoined).put("sessionCreated",::session.isInitialized)
                    .put("sessionJoined",sessionJoined).put("writesDrained",writesDrained).put("writerRetired",writerRetired)
                    .put("listenersCreated",fixtures.size).put("listenersClosed",listenersClosed).put("cachesRemoved",cachesRemoved)
                    .put("cleanupFailureType",failure?.javaClass?.name?:JSONObject.NULL)
                    .put("defaultStorageAccessed",false).put("systemClipboardAccessed",false)
                    .put("micHostBusinessModelCalls",0).put("syntheticHttpFixture",true)
                    .put("retainedOwnedStorage",if(::storage.isInitialized)storage.coordinationKey else JSONObject.NULL)
                    .put("retainedOwnedKeyAlias",if(::ownedKeyAlias.isInitialized)ownedKeyAlias else JSONObject.NULL)
                    .put("retainedOwnedCache",if(!cachesRemoved&&::cache.isInitialized)cache.absolutePath else JSONObject.NULL)
                    .put("releaseCondition","Root verifies producer termination and exact owned namespace/key family before removal; no default profile cleanup")
                    .put("outcome",if(passed)"NATIVE_FIXTURE_INCREMENT_PASSED_REAL_DEV_SEPARATE"else"FAILED_OR_UNKNOWN")
                File(folder,"result.json").apply {check(createNewFile());android.system.Os.chmod(path,0x180);writeText(result.toString())}
            }
        }
        failure?.let {throw it}
    }
    private fun fixture()=ResourceUiFixture().also {f ->
        f.name="owned-parent.md";f.mediaType="text/markdown"
        f.bytes=("# Root document\n\n[Open child]("+childReference+")\n").toByteArray()
        val child=("# Child start\n\n"+(0 until 50).joinToString("\n\n") {"Owned filler $it"}+
            "\n\n## Deep target\n\n[Missing](<"+missingReference+">)\n").toByteArray()
        f.documentFiles=mapOf("child%20file.md" to ResourceUiDocument("owned-child","child file.md",child))
        fixtures.add(f)
    }
    private fun connect(f: ResourceUiFixture) {
        main {session.connect("Owned F32",f.server.endpoint,"")}
        ui.waitUntil(10_000) {!session.loading && session.threads.isNotEmpty()}
        main {session.selectThread(session.threads.first())}
        ui.waitUntil(10_000) {!session.historyLoading && session.messages.any {it.id=="old-assistant"}}
        f.client=session.selectedClient;main {session.text="Owned unsent draft 100% %@"}
    }
    private fun renderMessage()=main {ui.activity.setContent {
        ProductTextProvider("en") {MaterialTheme {CompositionLocalProvider(LocalClipboardManager provides clipboard) {
            session.messages.firstOrNull {it.id=="old-assistant"}?.let {message ->
                Column {MessageLinkOpening(session,message) {MessageResourceCards(message)}}
            }
        }}}
    }}
    private fun open() {
        ui.onNodeWithTag("resource-card-preview-ui-one").performClick()
        ui.waitUntil(15_000) {shown("Root document")}
    }
    private fun tapLink(label: String) {
        val node=ui.onNodeWithTag("file-preview-native-1").performScrollTo();var layout: TextLayoutResult?=null
        node.assertTextEquals(label)
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) {get ->
            val values=mutableListOf<TextLayoutResult>();assertTrue(get(values));layout=values.single()
        }
        // These paragraphs contain exactly one link; use its real native glyph hit target.
        node.performTouchInput {click(checkNotNull(layout).getBoundingBox(1).center)}
    }
    private fun updateStatus(f: ResourceUiFixture,status: String) {
        f.messageStatus=status;main {session.loadHistory(userInitiated=true)}
        ui.waitUntil(10_000) {!session.historyLoading && session.messages.any {it.id=="old-assistant" && it.status==status}}
    }
    private fun verifyReadOnly(f: ResourceUiFixture) {
        assertEquals("Owned unsent draft 100% %@",session.text)
        assertTrue(f.server.requests.all {it.method=="GET"});assertTrue(session.operationRecords.isEmpty())
        f.server.requests.filter {it.path=="/v1/attachments/resolve-document-link"}.forEach {request ->
            val q=request.pathWithQuery.substringAfter('?').split('&').associate {
                java.net.URLDecoder.decode(it.substringBefore('='),"UTF-8") to java.net.URLDecoder.decode(it.substringAfter('='),"UTF-8")
            }
            assertEquals("fixture-thread",q["threadId"]);assertEquals("ui-one",q["sourceAttachmentId"])
            assertTrue(q.getValue("sourceSha256").matches(Regex("[a-f0-9]{64}")))
        }
    }
    @Test fun actualByteStagesRenderAndStalledUiRateExpiresToZero() {
        var progress by mutableStateOf<ResourceDownloadProgress?>(null)
        main {ui.activity.setContent {ProductTextProvider("en") {MaterialTheme {NativeResourceDownloadProgress(progress,"owned-progress")}}}}
        val bytes=ByteArray(8192) {((it*7)%251).toByte()}
        val target=TargetScope("codex",null,null,"progress-host","owned-workspace","owned-runtime-"+UUID.randomUUID())
        val client=MobileClient("progress-host","Owned progress",target,"https://example.invalid",true)
        val file=MobileFile("progress-file","owned.bin","application/octet-stream",bytes.size.toLong(),client.id,"owned-thread",hash(bytes))
        val first=gate();val second=gate();val verify=gate();val entered=gate();var offset=0
        val input=object: InputStream() {
            override fun read(): Int=error("Buffered reads required")
            override fun read(buffer: ByteArray,off: Int,len: Int): Int {
                if(offset==bytes.size)return -1
                if(offset==0) {entered.countDown();await(first)} else await(second)
                val count=minOf(4096,len,bytes.size-offset);bytes.copyInto(buffer,off,offset,offset+count);offset+=count;return count
            }
        }
        val callback: ResourceDownloadProgressHandler={value ->
            main {progress=value}
            if(value.phase==ResourceDownloadPhase.Verifying)await(verify)
        }
        ui.onNodeWithTag("owned-progress-phase").assertTextEquals(NativeL10n(ui.activity,"en").tr("正在准备文件…"))
        val pending=tasks.async(Dispatchers.IO) {CodexResources.downloadSnapshot(client,file,cache,callback,resolve={file},fetch={current,destination ->
            val operation=currentCoroutineContext()[Job]!!
            CodexResources.write(input,{operation.isActive},current,destination,callback)
        })}
        ui.waitUntil(5_000) {entered.count==0L && progress?.observedAtNanos?.let {System.nanoTime()-it>=150_000_000L}==true};first.countDown()
        ui.waitUntil(5_000) {progress?.receivedBytes==4096L}
        ui.onNodeWithTag("owned-progress-phase").assertTextEquals(NativeL10n(ui.activity,"en").tr("正在下载…"))
        ui.onNodeWithTag("owned-progress-percent").assertTextEquals("50%")
        ui.onNodeWithTag("owned-progress-bytes").assertTextEquals(Formatter.formatShortFileSize(ui.activity,4096)+" / "+Formatter.formatShortFileSize(ui.activity,8192))
        val zero=Formatter.formatShortFileSize(ui.activity,0)+"/s"
        assertNotEquals(zero,field("owned-progress-speed"))
        ui.waitUntil(4_000) {runCatching {field("owned-progress-speed")==zero}.getOrDefault(false)}
        assertEquals(4096L,progress?.receivedBytes);observations.put("visible-rate-expired-without-invented-bytes")
        second.countDown();ui.waitUntil(5_000) {progress?.phase==ResourceDownloadPhase.Verifying}
        ui.onNodeWithTag("owned-progress-phase").assertTextEquals(NativeL10n(ui.activity,"en").tr("正在校验文件…"))
        ui.onNodeWithTag("owned-progress-percent").assertTextEquals("100%")
        ui.onNodeWithTag("owned-progress-speed").assertDoesNotExist();verify.countDown()
        val result=runBlocking {withTimeout(10_000) {pending.await()}};assertArrayEquals(bytes,result.local.readBytes())
        runBlocking {CodexResources.downloadSnapshot(client,file,cache,{main {progress=it}},resolve={file},fetch={_,_->error("Cache hit must not fetch bytes")})}
        ui.waitUntil(3_000) {progress?.phase==ResourceDownloadPhase.Cached}
        ui.onNodeWithTag("owned-progress-phase").assertTextEquals(NativeL10n(ui.activity,"en").tr("正在打开缓存文件…"))
        ui.onNodeWithTag("owned-progress-speed").assertDoesNotExist();complete=true
    }
    @Test fun relativeChildAnchorAndFailureCopyUseOriginalReference() {
        val f=fixture();connect(f);renderMessage();open();tapLink("Open child")
        ui.waitUntil(15_000) {shown("Deep target")}
        assertFalse("Initial anchor must move past the child's top",shown("Child start"))
        ui.onAllNodesWithTag("file-preview-name").onLast().assertTextEquals("child file.md")
        ui.onAllNodesWithTag("file-preview-close").onLast().performClick()
        ui.waitUntil(5_000) {ui.onAllNodesWithTag("file-preview-screen").fetchSemanticsNodes().size==1}
        f.bytes=("# Root document\n\n[Missing](<"+missingReference+">)\n").toByteArray()
        // Reopen the newly authorized parent snapshot; the failed link must not be normalized before copying.
        ui.onNodeWithTag("file-preview-close").performClick();updateStatus(f,"streaming");open();tapLink("Missing")
        ui.waitUntil(5_000) {ui.onAllNodesWithTag("file-preview-link-error").fetchSemanticsNodes().isNotEmpty()}
        ui.onNodeWithTag("file-preview-link-copy").performClick();assertEquals(missingReference,copied?.text)
        verifyReadOnly(f);observations.put("relative-child-anchor-and-private-clipboard-copy");complete=true
    }
    @Test fun changedRemoteSourceAndTamperedLocalSourceAreRejected() {
        val f=fixture();connect(f)
        val original=f.bytes.copyOf()
        val owner=main {session.messageLinkContext(session.messages.single {it.id=="old-assistant"})}
        val source=main {session.prepareMessageResource(owner,f.reference,cache)}
        f.bytes="# Different remote source\n".toByteArray()
        val remote=runCatching {main {session.prepareDocumentResource(owner,source,childReference,source.sourceFile!!.sha256,cache)}}
        assertTrue(remote.isFailure);assertEquals(1,count(f,"/v1/attachments/resolve-document-link"))
        val binaries=f.binaries();source.local.writeText("Tampered owned local bytes")
        val local=runCatching {main {session.prepareDocumentResource(owner,source,childReference,source.sourceFile!!.sha256,cache)}}
        assertTrue(local.isFailure);assertEquals(1,count(f,"/v1/attachments/resolve-document-link"));assertEquals(binaries,f.binaries())
        f.bytes=original;source.local.writeBytes(original)
        val entered=gate();val release=gate();f.documentResolveGate={entered.countDown();await(release)}
        val pending=tasks.async {runCatching {session.prepareDocumentResource(owner,source,childReference,source.sourceFile!!.sha256,cache)}}
        ui.waitUntil(5_000) {entered.count==0L};source.local.writeText("Owned source changed while resolver was pending");release.countDown()
        assertTrue(runBlocking {withTimeout(10_000) {pending.await()}}.isFailure)
        assertEquals(2,count(f,"/v1/attachments/resolve-document-link"))
        verifyReadOnly(f);observations.put("source-hash-refusal-before-and-after-resolver-await");complete=true
    }
    @Test fun lateDocumentResolutionCannotCrossTargetOrReplaceNewDraft() {
        val f=fixture();connect(f)
        val owner=main {session.messageLinkContext(session.messages.single {it.id=="old-assistant"})}
        val source=main {session.prepareMessageResource(owner,f.reference,cache)}
        val entered=gate();val release=gate();f.documentResolveGate={entered.countDown();await(release)}
        val pending=tasks.async {runCatching {session.prepareDocumentResource(owner,source,childReference,source.sourceFile!!.sha256,cache)}}
        ui.waitUntil(5_000) {entered.count==0L};val binaries=f.binaries()
        val other=MobileClient("other-owned","Other synthetic target",TargetScope("codex",null,null,"other-owned","other-workspace","other-runtime"),isDemo=true)
        main {session.select(other);session.text="New target draft"};release.countDown()
        assertTrue(runBlocking {withTimeout(10_000) {pending.await()}}.isFailure)
        assertEquals("other-owned",session.selectedClient.id);assertEquals("New target draft",session.text)
        assertEquals(binaries,f.binaries());assertTrue(f.server.requests.all {it.method=="GET"})
        assertTrue(session.operationRecords.isEmpty());observations.put("late-source-resolution-kept-new-target-draft");complete=true
    }
    @Test fun statusOnlyUpdatesKeepPreviewAndInFlightChildOpenValid() {
        val f=fixture();connect(f);renderMessage();open()
        updateStatus(f,"streaming");ui.onNodeWithText("Root document").assertIsDisplayed()
        val entered=gate();val release=gate();f.documentResolveGate={entered.countDown();await(release)}
        val childEntered=gate();val childRelease=gate();f.binaryGate={childEntered.countDown();await(childRelease)}
        tapLink("Open child");ui.waitUntil(5_000) {entered.count==0L}
        updateStatus(f,"completed");ui.onNodeWithText("Root document").assertIsDisplayed();release.countDown()
        ui.waitUntil(5_000) {childEntered.count==0L}
        updateStatus(f,"inProgress");ui.onNodeWithText("Root document").assertIsDisplayed();childRelease.countDown()
        // Required success regression: a status-only revision cannot retire the immutable document read.
        ui.waitUntil(15_000) {shown("Deep target")}
        ui.onNodeWithTag("file-preview-link-error").assertDoesNotExist()
        assertEquals(1,count(f,"/v1/attachments/resolve-document-link"));verifyReadOnly(f)
        observations.put("benign-parent-status-before-and-during-child-open");complete=true
    }
}
