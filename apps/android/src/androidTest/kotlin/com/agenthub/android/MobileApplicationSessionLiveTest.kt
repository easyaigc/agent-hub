package com.agenthub.android
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.json.JSONObject
import org.json.JSONArray
import org.junit.*
import org.junit.Assert.*
import java.io.File

/** Opt-in real original-owner session grant; exactly one native action, never retries. */
class MobileApplicationSessionLiveTest {
    @get:Rule(order=0) val foreground: org.junit.rules.TestRule=NativeTestForeground()
    @get:Rule(order=1) val ui=createAndroidComposeRule<MainActivity>()
    private val args get()=InstrumentationRegistry.getArguments()
    private lateinit var owned: OwnedControlAcceptance
    private val session get()=owned.session
    private fun main(block: suspend ()->Unit)=runBlocking { withContext(Dispatchers.Main) { block() } }
    @Before fun setup() {
        ui.waitUntil(30_000) { !ui.activity.session.restoring }
        ui.runOnIdle { owned=OwnedControlAcceptance(ui.activity,"application-session");session.resetAcceptance() }
    }
    @After fun finish() { ui.runOnIdle { if(::owned.isInitialized) { session.leaveForeground();owned.verifyDefault() } } }
    @Test fun originalOwnerApplicationSessionGrantLive() {
        val name=args.getString("appSessionLiveFixture")
        org.junit.Assume.assumeTrue("Requires one fresh owned original-desktop approval",name!=null)
        require(name!!.matches(Regex("app34-[a-f0-9]{8}\\.json")))
        exerciseFile(name)
    }
    @Test fun syntheticConsentRequiresReconfirmationAndSendsOneExactDecision() {
        ExecutionFixture(applicationProtocol=true).use { f ->
            val approval=JSONObject().put("id","approval-id").put("threadId","fixture-thread").put("turnId","approval-turn")
                .put("kind","approval").put("title","Owned synthetic app").put("detail","Original scope")
                .put("questions",JSONArray()).put("decisions",JSONArray(listOf("accept","acceptAlways","decline","cancel")))
                .put("decisionLabels",JSONObject().put("accept","本会话允许").put("acceptAlways","始终允许").put("decline","拒绝").put("cancel","取消"))
            f.approvals=JSONArray().put(approval)
            val target=JSONObject().put("agentId","codex").put("hostId","fixture-host").put("workspaceId","fixture-workspace").put("runtimeId",f.runtimeId)
            val fixture=JSONObject().put("endpoint",f.server.endpoint).put("token","").put("hostId","fixture-host").put("threadId","fixture-thread")
                .put("target",target).put("nonce","synthetic").put("model","fixture-model")
                .put("runtime",JSONObject().put("state","idle").put("turnId",JSONObject.NULL).put("canControl",true).put("approvals",JSONArray().put(approval)).put("plan",JSONArray()).put("receipts",JSONArray()))
            val name="app34-"+java.util.UUID.randomUUID().toString().take(8)+".json"
            File(ui.activity.filesDir,"acceptance/"+name).writeText(fixture.toString())
            exerciseFile(name)
            assertEquals(1,f.server.requests.count { it.method=="POST"&&it.path=="/v1/approvals" })
        }
    }
    private fun exerciseFile(name: String) {
        val file=File(ui.activity.filesDir,"acceptance/$name")
        val fixture=JSONObject(file.readText())
        require(java.net.URI(fixture.getString("endpoint")).host=="127.0.0.1")
        val threadId=fixture.getString("threadId");val hostId=fixture.getString("hostId")
        val token=fixture.getString("token");val target=fixture.getJSONObject("target")
        val expected=MobileRuntime.decode(fixture.getJSONObject("runtime"),hostId,threadId).approvals.single()
        require(expected.threadId==threadId&&"accept" in expected.decisions)
        require(expected.decisionLabels["accept"]=="本会话允许")
        var commandInvocations=0
        val commands=object: CodexCommands by NativeCodexCommands({ token }) {
            override suspend fun answer(client: MobileClient,selected: String,turn: String,approval: String,decision: String,answers: Map<String,List<String>>) {
                check(commandInvocations==0);commandInvocations++
                File(owned.folder,"live-command-intent.json").writeText(JSONObject().put("hostId",client.id).put("threadId",selected)
                    .put("turnId",turn).put("approvalId",approval).put("decision",decision).put("attempts",1).put("automaticReplay",false).toString())
                NativeCodexCommands { token }.answer(client,selected,turn,approval,decision,answers)
            }
        }
        val native=NativeHostReads { token }
        val reads=object: HostReads by native {
            override suspend fun workspace(client: MobileClient,projectCursor: String?,threadCursor: String?): MobileWorkspace {
                val value=native.workspace(client,projectCursor,threadCursor)
                check(value.hostId==hostId&&value.mode=="desktop-runtime"&&value.runtimeConnected!=false&&"approvals" in value.capabilities)
                val scope=checkNotNull(value.scope)
                check(scope.agentId=="codex"&&scope.hostId==target.getString("hostId")&&scope.workspaceId==target.getString("workspaceId")&&scope.runtimeId==target.getString("runtimeId"))
                val own=value.threads.filter { it.id==threadId };check(own.size==1)
                // Scope the test menu to its fresh chat; real history/approval/command data is unchanged.
                return value.copy(projects=emptyList(),threads=own,nextProjectCursor=null,nextThreadCursor=null,pinnedThreadIds=emptyList(),sidebar=null)
            }
            override suspend fun history(client: MobileClient,selected: String,cursor: String?): MobileHistory {
                check(selected==threadId);return native.history(client,selected,cursor)
            }
        }
        try {
            main { session.resetAcceptance(reads,commands);session.changeLanguage("en")
                session.changeAppearance(args.getString("viewportTheme") ?: "light")
                session.connect("Android APP34 original-owner DEV",fixture.getString("endpoint"),token) }
            ui.waitUntil(30_000) { !session.loading&&session.threads.any { it.id==threadId } }
            ui.runOnIdle { session.selectThread(session.threads.single { it.id==threadId }) }
            ui.waitUntil(30_000) { !session.historyLoading&&session.runtime?.approvals?.any { it.id==expected.id }==true }
            val actual=session.runtime!!.approvals.single { it.id==expected.id }
            assertTrue("Current complete native DTO must match the original owned request",actual==expected)
            assertTrue(session.canAnswer(actual,session.approvalActionDestination()))
            main { session.text="APP34 unsent original draft "+fixture.getString("nonce") }
            val draft=session.text
            for(id in listOf("accept","acceptAlways","decline","cancel"))ui.onNodeWithTag("approval-"+id).assertExists()
            ui.onNodeWithTag("approval-accept").performScrollTo().assertIsEnabled()
                .assertTextEquals(NativeL10n(ui.activity,"en").tr("本会话允许"))
            owned.nodeShot(ui.onNodeWithTag("approval-"+actual.id),"application-original-owner-live-before-session-grant")
            // First click opens the product's content-sharing consent; no original operation is sent.
            ui.onNodeWithTag("approval-accept").performClick()
            ui.waitUntil(10_000) { session.aiSharingRequest!=null }
            val grant=checkNotNull(session.aiSharingRequest)
            assertEquals(session.targetKey,grant.targetKey)
            assertTrue(AISharingScope(checkNotNull(session.selectedClient.scope),fixture.getString("model")).matches(grant.scope))
            assertEquals(0,commandInvocations);assertFalse(session.isAnswering(actual))
            owned.dialogShot("original-target-sharing-consent")
            ui.onNodeWithTag("ai-sharing-allow").performScrollTo().performClick()
            ui.waitUntil(10_000) { session.aiSharingRequest==null&&!session.aiSharing.busy }
            assertEquals(0,commandInvocations);assertTrue(session.aiSharing.permits(grant.scope));assertEquals(draft,session.text)
            // Consent completion and the Android Dialog's window return are separate events.
            ui.waitUntil(10_000) {
                session.aiSharingRequest==null&&!session.aiSharing.busy&&
                    ui.onAllNodesWithTag("sheet-ai-sharing").fetchSemanticsNodes().isEmpty()&&
                    ui.activity.window.decorView.hasWindowFocus()
            }
            val button=ui.onNodeWithTag("approval-accept").performScrollTo().assertIsDisplayed().assertIsEnabled()
            val instrumentation=InstrumentationRegistry.getInstrumentation()
            var last="";var since=0L
            ui.waitUntil(10_000) {
                val root=instrumentation.uiAutomation.rootInActiveWindow
                if(root==null) { last="";since=0L;false } else {
                    assertEquals(instrumentation.targetContext.packageName,root.packageName?.toString())
                    val bounds=button.fetchSemanticsNode().boundsInRoot;val value=bounds.toString()
                    val now=android.os.SystemClock.uptimeMillis()
                    if(value!=last) { last=value;since=now }
                    bounds.width>0&&bounds.height>0&&now-since>=700L&&session.canAnswer(actual)&&
                        ui.activity.window.decorView.hasWindowFocus()
                }
            }
            assertEquals(expected,session.runtime!!.approvals.single { it.id==expected.id })
            File(owned.folder,"native-window-before-original-action.json").writeText(JSONObject()
                .put("mainWindowFocused",true).put("sharingDialogAbsent",true).put("boundsStableMs",700)
                .put("originalDtoMatched",true).put("originalTargetKey",session.targetKey).put("commandInvocationsBefore",commandInvocations).toString())
            assertEquals(0,commandInvocations)
            button.performClick()
            ui.waitUntil(35_000) { !session.isAnswering(actual)&&session.runtime?.approvals?.none { it.id==actual.id }==true }
            assertNull("Original approval command must have a known acknowledgement",session.error)
            assertEquals(draft,session.text)
            assertEquals(1,commandInvocations)
            File(owned.folder,"live-native-proof.json").writeText(JSONObject().put("originalDtoMatched",true).put("realConsentUi",true).put("nativeMainWindowStable",true).put("noAutomaticOperationAfterConsent",true).put("oneCommandInvocation",commandInvocations==1).put("nativeDecision","accept")
                .put("draftPreserved",true).put("requestRetired",true).put("newModelInputsFromPhone",0).put("automaticReplay",false).toString())
        } finally {
            File(owned.folder,"live-final-state.json").writeText(JSONObject().put("commandInvocations",commandInvocations)
                .put("error",session.error).put("sharingRequest",session.aiSharingRequest!=null)
                .put("sharingBusy",session.aiSharing.busy).put("sharingFailure",session.aiSharing.failure)
                .put("targetKey",session.targetKey).put("expectedApprovalPresent",session.runtime?.approvals?.any { it==expected }==true)
                .put("canAnswer",session.canAnswer(expected)).put("answering",session.isAnswering(expected))
                .put("readOnlyDiagnostic",true).toString())
            file.delete()
        }
    }
}
