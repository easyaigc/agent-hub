package com.agenthub.android

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.text.AnnotatedString
import androidx.test.platform.app.InstrumentationRegistry
import com.agenthub.core.TargetScope
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/** Actual production Compose/Session with an in-memory read port; no HTTP, disk Session or model. */
class NativeSubagentHistoryUiTest {
    @get:Rule(order=0) val timeout: org.junit.rules.TestRule=org.junit.rules.Timeout.seconds(60)
    @get:Rule(order=1) val ui=createAndroidComposeRule<NativeSavedVoicePanelActivity>()
    @get:Rule(order=2) val methodName=org.junit.rules.TestName()
    private lateinit var session: MobileSession
    private val port=HistoryPort()
    private val commands=NoWrites()
    private val parentDraft="Owned parent draft 100% %@\n"
    private val text get()=NativeL10n(ui.activity,"en")
    private val clipboard=object: ClipboardManager {
        private var value: AnnotatedString?=null
        override fun setText(annotatedString: AnnotatedString) {value=annotatedString}
        override fun getText(): AnnotatedString?=value
    }
    private fun <T> main(block: suspend ()->T): T=runBlocking {withContext(Dispatchers.Main) {block()}}
    private fun exists(tag: String)=ui.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
    @Before fun setup() {
        port.lineageSupported=methodName.methodName!="oldHostNeverOffersLineageOrSendsNewQuery"

        val i=InstrumentationRegistry.getInstrumentation()
        require(i.targetContext.packageName=="com.easyai.agenthub.android.acceptance")
        require(i.context.packageName==i.targetContext.packageName+".test")
        main {
            // Existing constructor injection only: no acceptance state setters or production hook.
            session=MobileSession(reader=port,commandProvider=commands)
            session.changeLanguage("en")
            session.connect("Owned F20","https://f20-owned.invalid","")
        }
        ui.waitUntil(5_000) {!session.loading && session.threads.isNotEmpty()}
        main {session.selectThread(session.threads.single())}
        ui.waitUntil(5_000) {!session.historyLoading && session.messages.any {it.id=="owned-activity"}}
        main {
            session.text=parentDraft
            ui.activity.setContent {
                ProductTextProvider("en") {MaterialTheme {CompositionLocalProvider(LocalClipboardManager provides clipboard) {
                    NativeSubagentHistoryHost(session) {
                        Column {
                            Text(session.text,Modifier.testTag("owned-parent-draft"))
                            session.messages.firstOrNull {it.id=="owned-activity"}?.let {ToolGroup("owned-parent-tools",listOf(it))}
                        }
                    }
                }}}
            }
        }
        ui.waitForIdle();assertEquals(false,session.runtime?.canControl)
    }
    @After fun finish() {
        if(::session.isInitialized)main {ui.activity.setContent {};session.leaveForeground()
            withTimeout(10_000) {session.abandonMigrationRestore()}}
        assertTrue("No production command or model execution is permitted",commands.invoked.isEmpty())
        assertEquals(0,port.activeReads)
    }
    private fun open() {
        ui.onNodeWithTag("tool-toggle-owned-activity").performClick()
        ui.onNodeWithTag("tool-output-owned-activity").assertExists()
        ui.onNodeWithTag("subagent-history-owned-activity").performClick()
        ui.waitUntil(5_000) {exists("subagent-history-message-a-latest") && !exists("subagent-history-loading")}
    }
    private fun choose(child: String) {
        ui.onNodeWithTag("subagent-child-picker").assertIsEnabled().performClick()
        ui.onNodeWithTag("subagent-child-$child").performClick()
    }
    private fun unchangedParent() {
        assertEquals(parentDraft,session.text);assertEquals("owned-parent",session.selectedThread)
        assertEquals(false,session.runtime?.canControl)
        assertTrue(session.operationRecords.isEmpty());assertTrue(commands.invoked.isEmpty())
    }
    @Test fun toolEntryChildChoiceEarlierPageAndSourceLabelsUseReadOnlyPort() {
        open()
        ui.onNodeWithTag("subagent-history-source").assertTextEquals(text.tr("最近保存的记录"))
        ui.onNodeWithTag("subagent-history-more").performClick()
        ui.waitUntil(5_000) {exists("subagent-history-message-a-earlier") && !exists("subagent-history-loading")}
        ui.onAllNodesWithTag("subagent-history-message-a-latest").assertCountEquals(1)
        ui.onNodeWithTag("subagent-history-more").assertDoesNotExist()
        choose("b")
        ui.waitUntil(5_000) {exists("subagent-history-message-b-latest") && !exists("subagent-history-loading")}
        ui.onNodeWithTag("subagent-history-source").assertTextEquals(text.tr("实时记录"))
        ui.onNodeWithTag("subagent-history-message-a-earlier").assertDoesNotExist()
        assertEquals(listOf(null to null,"a" to HistoryPort.CURSOR,"b" to null),port.calls.map {it.childId to it.cursor})
        unchangedParent()
    }
    @Test fun failedChildReadStillAllowsSelectingAnotherChild() {
        open();port.failBeta=true;choose("b")
        ui.waitUntil(5_000) {exists("subagent-history-error") && !exists("subagent-history-loading")}
        ui.onNodeWithTag("subagent-child-picker").assertIsEnabled().assertTextEquals("Child Beta")
        choose("a")
        ui.waitUntil(5_000) {exists("subagent-history-message-a-latest") && !exists("subagent-history-loading")}
        ui.onNodeWithTag("subagent-history-error").assertDoesNotExist()
        ui.onNodeWithTag("subagent-history-source").assertTextEquals(text.tr("最近保存的记录"))
        assertEquals(listOf(null to null,"b" to null,"a" to null),port.calls.map {it.childId to it.cursor})
        unchangedParent()
    }
    @Test fun closingReadOnlyHistoryReturnsToOriginalParentDraftWithoutSendControls() {
        val key=session.targetKey;val parent=session.messages.toList()
        val files=session.attachments.toList();val operations=session.operationRecords.toList()
        open()
        ui.onNodeWithTag("subagent-detail-owned-activity").assertExists()
        ui.onNodeWithTag("composer").assertDoesNotExist()
        ui.onNodeWithTag("send-message").assertDoesNotExist()
        ui.onAllNodesWithText(text.tr("发送消息")).assertCountEquals(0)
        ui.onNodeWithTag("subagent-detail-close").performClick()
        ui.waitUntil(5_000) {!exists("subagent-detail-owned-activity")}
        ui.onNodeWithTag("owned-parent-draft").assertTextEquals(parentDraft)
        ui.onNodeWithTag("tool-output-owned-activity").assertExists()
        assertEquals(key,session.targetKey);assertEquals(parent,session.messages)
        assertEquals(files,session.attachments);assertEquals(operations,session.operationRecords)
        assertEquals(1,port.calls.size);unchangedParent()
    }

    @Test fun unavailableToolOffersExplicitParentChatReadAndPreservesParentDraft() {
        port.typedFailure=404
        ui.onNodeWithTag("tool-toggle-owned-activity").performClick()
        ui.onNodeWithTag("subagent-history-owned-activity").performClick()
        ui.waitUntil(5_000) {exists("subagent-parent-lineage") && !exists("subagent-history-loading")}
        assertEquals(1,port.calls.size);assertNull(port.calls.single().association)
        ui.onNodeWithTag("subagent-history-message-a-latest").assertDoesNotExist()
        ui.onNodeWithTag("subagent-parent-lineage").performClick()
        ui.waitUntil(5_000) {exists("subagent-history-message-a-latest") && !exists("subagent-history-loading")}
        ui.onNodeWithTag("subagent-history-source").assertTextEquals(text.tr("本聊天的子任务 · 最近保存的记录"))
        ui.onNodeWithTag("subagent-history-more").performClick()
        ui.waitUntil(5_000) {exists("subagent-history-message-a-earlier") && !exists("subagent-history-loading")}
        choose("b")
        ui.waitUntil(5_000) {exists("subagent-history-message-b-latest") && !exists("subagent-history-loading")}
        assertTrue(port.calls.drop(1).all {it.association=="parent-thread-lineage"})
        ui.onNodeWithTag("send-reply").assertDoesNotExist()
        ui.onNodeWithTag("subagent-detail-close").performClick()
        ui.onNodeWithTag("owned-parent-draft").assertTextEquals(parentDraft);unchangedParent()
    }
    @Test fun forbiddenToolReadNeverOffersParentLineageFallback() {
        port.typedFailure=403
        ui.onNodeWithTag("tool-toggle-owned-activity").performClick()
        ui.onNodeWithTag("subagent-history-owned-activity").performClick()
        ui.waitUntil(5_000) {exists("subagent-history-error") && !exists("subagent-history-loading")}
        ui.onNodeWithTag("subagent-parent-lineage").assertDoesNotExist()
        assertEquals(1,port.calls.size);assertNull(port.calls.single().association);unchangedParent()
    }

    @Test fun oldHostNeverOffersLineageOrSendsNewQuery() {
        assertFalse(session.supportsParentLineageHistory);port.typedFailure=404
        ui.onNodeWithTag("tool-toggle-owned-activity").performClick()
        ui.onNodeWithTag("subagent-history-owned-activity").performClick()
        ui.waitUntil(5_000) {exists("subagent-history-error") && !exists("subagent-history-loading")}
        ui.onNodeWithTag("subagent-parent-lineage").assertDoesNotExist()
        ui.onNodeWithTag("subagent-parent-lineage-unsupported").assertTextEquals(text.tr("这台电脑尚不支持按父聊天查看子任务，可返回查看原工具摘要。"))
        assertTrue(port.calls.isNotEmpty() && port.calls.all {it.association==null});unchangedParent()
    }
    @Test fun lineageDomainPortCannotClaimDesktopRuntimeSource() {
        val owner=main {checkNotNull(session.captureSubagentHistory(session.messages.first {it.id=="owned-activity"}))}
        port.lineageSource="desktop-runtime"
        val result=main {runCatching {session.readSubagentHistory(owner,null,null,true)}}
        assertTrue(result.isFailure);unchangedParent()
    }
    @Test fun sameTargetReselectionRetiresLateLineageReadWithoutTouchingDraft() {
        val gate=CompletableDeferred<Unit>();port.lineageGate=gate
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
        val owner=main {checkNotNull(session.captureSubagentHistory(session.messages.first {it.id=="owned-activity"}))}
        val pending=main {scope.async {runCatching {session.readSubagentHistory(owner,null,null,true)}}}
        try {
            ui.waitUntil(5_000) {port.activeReads==1}
            val key=session.targetKey
            main {session.select(session.selectedClient)}
            assertEquals(key,session.targetKey)
            assertFalse(session.parentLineageCurrent(owner))
            gate.complete(Unit)
            val result=runBlocking {withTimeout(5_000) {pending.await()}}
            assertTrue(result.isFailure)
            ui.waitUntil(5_000) {!session.loading && !session.historyLoading && session.runtime?.canControl==false}
            unchangedParent()
        } finally {
            gate.complete(Unit)
            runBlocking {withTimeout(10_000) {scope.cancel();scope.coroutineContext[Job]?.join()}}
            port.lineageGate=null
        }
    }

    private class HistoryPort: HostReads {
        companion object {const val CURSOR="owned-opaque:a +&"}
        private val target=TargetScope("codex",null,null,"owned-f20-"+UUID.randomUUID(),"owned-workspace","desktop-runtime")
        private val activity=MobileMessage("owned-activity","tool","Owned child task","completed","subAgent","Original parent tool detail")
        val calls=CopyOnWriteArrayList<MobileSubagentHistoryRequest>()
        var failBeta=false
        var typedFailure: Int?=null
        var lineageSupported=true
        var lineageSource: String?=null
        var lineageGate: CompletableDeferred<Unit>?=null
        var activeReads=0;private set
        override suspend fun workspace(client: MobileClient,projectCursor: String?,threadCursor: String?)=
            MobileWorkspace(target.hostId,"Owned F20","desktop-runtime",emptyList(),
                listOf(MobileThread("owned-parent",target.hostId,"Owned parent","idle")),null,null,target,
                (listOf("subagents:history:read:v1")+if(lineageSupported)listOf("subagents:history:parent-lineage:v1")else emptyList()),"{}",runtimeConnected=true)
        override suspend fun history(client: MobileClient,threadId: String,cursor: String?): MobileHistory {
            check(threadId=="owned-parent")
            val runtime=MobileRuntime("idle",null,emptyList(),null,emptyList(),emptyList(),false,null,null,GenerationSettings())
            return MobileHistory(target.hostId,threadId,listOf(activity),null,target,"idle",null,"{}",runtimePresent=true,runtime=runtime)
        }
        override suspend fun subagentHistory(client: MobileClient,request: MobileSubagentHistoryRequest): MobileSubagentHistory {
            activeReads++
            try {
                check(client.id==target.hostId && sameScope(client.scope,target))
                check(request.parentThreadId=="owned-parent" && request.messageId==activity.id)
                calls.add(request)
                if(request.association==null)typedFailure?.let {throw HostReadError("Owned typed history unavailable",status=it)}
                else {
                    check(request.association=="parent-thread-lineage")
                    lineageGate?.let {withContext(NonCancellable) {it.await()}}
                }
                val child=request.childId?:"a"
                if(child=="b" && failBeta)throw HostReadError("Owned child read failed")
                check(child in listOf("a","b") && (request.cursor==null || child=="a" && request.cursor==CURSOR))
                val latest=MobileMessage("$child-latest","assistant","Owned $child latest record","completed")
                val messages=if(request.cursor==null)listOf(latest) else listOf(MobileMessage("a-earlier","assistant","Owned a earlier record","completed"),latest)
                return MobileSubagentHistory(target.hostId,target,request.parentThreadId,request.messageId,
                    listOf(MobileSubagentChild("a","Child Alpha","completed"),MobileSubagentChild("b","Child Beta","completed")),
                    child,if(request.association!=null)lineageSource?:"saved-history" else if(child=="a")"saved-history" else "desktop-runtime",messages,
                    if(child=="a" && request.cursor==null)CURSOR else null,request.association?:"typed-tool")
            } finally {activeReads--}
        }
    }
    /** No NativeCommands delegation: unexpected control attempts terminate locally. */
    private class NoWrites: CodexCommands {
        val invoked=CopyOnWriteArrayList<String>()
        private fun denied(name: String): Nothing {invoked.add(name);error("Owned UI forbids $name")}
        override suspend fun models(client: MobileClient)=emptyList<CodexModel>()
        override suspend fun events(client: MobileClient,threadId: String,epoch: String,cursor: Long): EventBatch=denied("events")
        override suspend fun send(client: MobileClient,intent: ReplyIntent): DeliveryReceipt=denied("send")
        override suspend fun receipt(client: MobileClient,threadId: String,operationId: String): DeliveryReceipt=denied("receipt")
        override suspend fun createThread(client: MobileClient,projectId: String?,operationId: String): MobileThread=denied("createThread")
        override suspend fun prepareCreation(client: MobileClient,intent: CreationIntent): DesktopCreation=denied("prepareCreation")
        override suspend fun creation(client: MobileClient,operationId: String,inspectOnly: Boolean): DesktopCreation=denied("creation")
        override suspend fun submitCreation(client: MobileClient,operationId: String): DesktopCreation=denied("submitCreation")
        override suspend fun detachCreation(client: MobileClient,operationId: String): DesktopCreation=denied("detachCreation")
        override suspend fun open(client: MobileClient,threadId: String,operationId: String,queryOnly: Boolean): DesktopOpen=denied("open")
        override suspend fun stop(client: MobileClient,threadId: String,turnId: String): Unit=denied("stop")
        override suspend fun cancelQueue(client: MobileClient,threadId: String,operationId: String): Unit=denied("cancelQueue")
        override suspend fun answer(client: MobileClient,threadId: String,turnId: String,approvalId: String,decision: String,answers: Map<String,List<String>>): Unit=denied("answer")
        override suspend fun upload(client: MobileClient,threadId: String,file: CreationFile,bytes: ByteArray): MobileFile=denied("upload")
        override suspend fun uploadCreation(client: MobileClient,operationId: String,file: CreationFile,bytes: ByteArray): Unit=denied("uploadCreation")
    }
}
