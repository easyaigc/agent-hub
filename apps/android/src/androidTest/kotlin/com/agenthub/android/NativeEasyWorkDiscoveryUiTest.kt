package com.agenthub.android

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.*
import androidx.test.platform.app.InstrumentationRegistry
import com.agenthub.core.TargetScope
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.ExternalResource
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import java.io.File
import java.nio.file.Files
import java.util.UUID

/** Actual product sheets/Session/Keystore. Inactive discovery receives synthetic UI
 * snapshots; these methods do not run NSD, credential-probe IO or a network/model task.
 */
class NativeEasyWorkDiscoveryUiTest {
    private val i get()=InstrumentationRegistry.getInstrumentation()
    private val id=UUID.randomUUID().toString()
    private lateinit var build: JSONObject
    private var protectedDefaults=emptyMap<String,String>()
    @get:Rule(order=0) val identity=object: ExternalResource() {override fun before() {
        require(i.targetContext.packageName=="com.easyai.agenthub.android.acceptance"&&i.context.packageName=="com.easyai.agenthub.android.acceptance.test")
        build=JSONObject(requireNotNull(InstrumentationRegistry.getArguments().getString("p8DiscoveryUiBuild")))
        for(key in listOf("sourceSha256","appSha256","runnerSha256"))require(build.getString(key).matches(Regex("[a-f0-9]{64}")))
        assertEquals(build.getString("appSha256"),sha(File(i.targetContext.applicationInfo.sourceDir)))
        assertEquals(build.getString("runnerSha256"),sha(File(i.context.applicationInfo.sourceDir)))
        protectedDefaults=defaults()
    }}
    @get:Rule(order=1) val ui=createAndroidComposeRule<ComponentActivity>()
    @get:Rule(order=2) val outcome=object: TestWatcher() {
        override fun succeeded(description: Description)=record(description,"passed")
        override fun failed(error: Throwable,description: Description)=record(description,"failed",error.javaClass.name)
    }
    private lateinit var session: MobileSession
    private lateinit var store: NativeSessionStorage
    private lateinit var discovery: EasyWorkDiscovery
    private lateinit var client: MobileClient
    private lateinit var folder: File
    private val originalDraft="Independent original draft 中文 😀 %@"
    private val originalGrant="Synthetic protected authorization record, never an issued credential"
    private val clicks=mutableListOf<String>()
    private val page=mutableStateOf("clients")
    private val editing=mutableStateOf<MobileClient?>(null)
    private var layoutProbes=0
    private fun sha(file: File)=file.inputStream().use {input->val digest=java.security.MessageDigest.getInstance("SHA-256");val buffer=ByteArray(65536);while(true){val n=input.read(buffer);if(n<0)break;digest.update(buffer,0,n)};digest.digest().joinToString("") {"%02x".format(it)}}
    private fun defaults(): Map<String,String> {
        val base=i.targetContext.noBackupFilesDir.toPath();if(!Files.exists(base))return emptyMap()
        val result=linkedMapOf<String,String>();var roots=0
        Files.newDirectoryStream(base,"mobile-v1*").use {entries->for(root in entries) {
            val name=root.fileName.toString();if(name!="mobile-v1"&&!name.startsWith("mobile-v1-"))continue
            check(++roots<=64);check(!Files.isSymbolicLink(root))
            Files.walk(root).use {walk->val iterator=walk.iterator();while(iterator.hasNext()) {
                val path=iterator.next();check(!Files.isSymbolicLink(path));if(!Files.isRegularFile(path))continue
                check(result.size<1024);result[base.relativize(path).toString()]=sha(path.toFile())
            }}
        }}
        check(easyCanonical(JSONObject(result)).toByteArray().size<=262144);return result
    }
    @Suppress("UNCHECKED_CAST") private fun clients()=MobileSession::class.java.getDeclaredField("realClients").apply {isAccessible=true}.get(session) as MutableList<MobileClient>
    private fun persist() {MobileSession::class.java.getDeclaredMethod("persistMetadata").apply {isAccessible=true}.invoke(session)}
    private fun live()=session.clients.single {it.id==client.id}
    private fun noRuntimeSession() {
        val pool=MobileSession::class.java.getDeclaredField("easyWorkSessions").apply {isAccessible=true}.get(session)!!
        val entries=EasyWorkSessions::class.java.getDeclaredField("sessions").apply {isAccessible=true}.get(pool) as Map<*,*>
        assertTrue(entries.isEmpty());assertTrue(session.selectedClient.isDemo)
    }
    @Before fun setup() {
        folder=File(i.targetContext.filesDir,"acceptance/p8-discovery-ui/$id").apply {check(!exists()&&mkdirs())}
        store=NativeSessionStorage(i.targetContext,"p8-discovery-ui-$id","p8-discovery-ui-$id")
        store.write("protected-synthetic-grant",originalGrant)
        val scope=TargetScope("easywork","https://public-synthetic.invalid/"+"a".repeat(48),"subject-"+"s".repeat(56),"host-"+"h".repeat(56),"workspace-"+"w".repeat(56),"runtime-"+"r".repeat(56))
        val connection=AgentConnection(scope,listOf(AgentCandidate("direct","lan-wss","https://192.0.2.1:7443","a".repeat(64)),AgentCandidate("relay","relay-http","https://synthetic.invalid")),"unused-synthetic-ref")
        client=MobileClient("synthetic-ui-$id","Original saved computer",scope,connection.candidates.first().endpoint,false,"easywork-runtime",false,agentConnection=connection)
        ui.runOnIdle {
            session=MobileSession(store);session.resetAcceptance();session.leaveForeground();clients().add(client);session.text=originalDraft;persist()
            discovery=EasyWorkDiscovery(i.targetContext,session,store);session.easyWorkDiscovery=discovery
            discovery.offerVPN(emptyList()) // Rebuild only. start()/scan() are never invoked by setup.
            assertEquals(client.name,discovery.hosts.single().name);noRuntimeSession()
        }
    }
    @After fun finish() {
        if(::session.isInitialized)ui.runOnIdle {
            ui.activity.setContent {};discovery.stop();session.easyWorkDiscovery=null;session.leaveForeground()
            assertEquals(originalDraft,session.text);assertTrue(session.operationRecords.isEmpty());assertFalse(session.connectionBusy);noRuntimeSession()
        }
        if(::store.isInitialized) {runBlocking {store.awaitQueuedWrites()};assertEquals(originalGrant,store.read("protected-synthetic-grant"))}
        assertEquals(protectedDefaults,defaults())
    }
    private fun record(description: Description,status: String,error: String?=null) {
        if(!::folder.isInitialized)return
        File(folder,"result.json").writeText(JSONObject().put("caseId",id).put("method",description.methodName).put("junitResult",status).put("failureClass",error)
            .put("build",build).put("clickTags",JSONArray(clicks)).put("textLayoutProbes",layoutProbes)
            .put("lane","actual production UI with synthetic saved metadata and inactive discovery snapshots")
            .put("networkOrModelAcceptance",false).put("scanOrStorageRaceAcceptance",false).toString())
    }
    private fun showSheets() {ui.runOnIdle {ui.activity.setContent {ProductTextProvider(session.language) {MaterialTheme {Surface {
        NativeSheetHost(page.value,session,editing.value,close={page.value="closed"},navigate={page.value=it},edit={editing.value=it;page.value="add"})
    }}}}}}
    private fun touch(tag: String,scroll: Boolean=true) {
        val node=ui.onNodeWithTag(tag);if(scroll)node.performScrollTo();node.assertIsDisplayed().assertIsEnabled().assertHasClickAction()
        val raw=node.getUnclippedBoundsInRoot();val bounds=node.fetchSemanticsNode().boundsInRoot
        assertTrue("Full target visible: $tag",bounds.width>=(raw.right.value-raw.left.value)*ui.density.density-1.5f&&bounds.height>=(raw.bottom.value-raw.top.value)*ui.density.density-1.5f)
        node.performTouchInput {click(center)};clicks+=tag;ui.waitForIdle()
    }
    private fun preference(value: String,current: String) {
        touch("connection-preference");ui.onNodeWithTag("connection-preference-$current").assertIsSelected()
        for(other in listOf("automatic","lan-only","relay-only").filter {it!=current})ui.onNodeWithTag("connection-preference-$other").assertIsNotSelected()
        touch("connection-preference-$value");ui.runOnIdle {assertEquals(value,live().agentConnection!!.preference)}
    }
    private fun rowName(expected: String): SemanticsNodeInteraction {
        val tag="easywork-discovered-${client.id}"
        ui.onNodeWithTag(tag).performScrollTo().assertIsDisplayed()
        return ui.onNode(hasText(expected) and hasAnyAncestor(hasTestTag(tag)),useUnmergedTree=true).assertTextEquals(expected)
    }
    @Test fun renameUsesSavedNameBeforeAnotherScanAndAfterLateDiscovery() {
        showSheets();touch("edit-client-${client.id}")
        val renamed="Human renamed 中文 %@"
        val field=ui.onNodeWithTag("easywork-name").performScrollTo();field.performTextReplacement(renamed);field.performImeAction()
        ui.runOnIdle {assertEquals(renamed,live().name);assertEquals(client.name,discovery.hosts.single().name)}
        rowName(renamed) // Counterexample: original UI still renders discovery.hosts' old name.
        ui.onNodeWithText(client.name,useUnmergedTree=true).assertDoesNotExist()
        ui.runOnIdle {discovery.offerVPN(listOf(DiscoveredEasyWorkHost("late-unrelated","Late discovery","https://192.0.2.2:7443","other-synthetic-device",networkKind="vpn")))}
        rowName(renamed);touch("cancel-client-form",false)
        ui.onNodeWithTag("client-${client.id}").assertTextContains(renamed)
        touch("edit-client-${client.id}");ui.onNodeWithTag("easywork-name").assertTextEquals(renamed)
        ui.runOnIdle {assertEquals(client.agentConnection,live().agentConnection);assertEquals(client.scope,live().scope)}
    }
    @Test fun preferenceSelectionSurvivesLateRowsAndEditorRoundTrip() {
        showSheets();touch("edit-client-${client.id}")
        preference("relay-only","automatic")
        ui.runOnIdle {discovery.offerVPN(listOf(DiscoveredEasyWorkHost("late","Old observed name","https://192.0.2.3:7443",client.scope!!.hostId,known=true)))}
        rowName(client.name);preference("lan-only","relay-only");preference("automatic","lan-only")
        touch("cancel-client-form",false);touch("edit-client-${client.id}")
        touch("connection-preference");ui.onNodeWithTag("connection-preference-automatic").assertIsSelected();touch("connection-preference-automatic")
        runBlocking {store.awaitQueuedWrites()}
        val saved=JSONObject(requireNotNull(store.read("metadata"))).getJSONArray("clients").objects().single()
        assertEquals("automatic",saved.getJSONObject("agentConnection").getString("preference"))
        ui.runOnIdle {assertEquals(client.agentConnection!!.candidates,live().agentConnection!!.candidates);assertEquals(client.agentConnection!!.credentialRef,live().agentConnection!!.credentialRef)}
    }
    @Test fun removeRequiresConfirmationAndLateKnownRowsStayAbsent() {
        showSheets();touch("remove-client-${client.id}");ui.onNodeWithText("移除 ${client.name} 的连接？电脑上的项目和对话会保留。",useUnmergedTree=true).assertExists()
        ui.onNodeWithText("取消").performTouchInput {click(center)};ui.waitForIdle();ui.runOnIdle {assertEquals(client.id,live().id)}
        touch("remove-client-${client.id}");touch("confirm-remove-client",false)
        ui.onNodeWithTag("client-${client.id}").assertDoesNotExist();ui.runOnIdle {assertTrue(session.clients.isEmpty());assertTrue(discovery.hosts.any {it.id==client.id})}
        touch("add-client");touch("agent-provider-easywork")
        ui.onNodeWithTag("easywork-discovered-${client.id}").assertDoesNotExist()
        ui.runOnIdle {discovery.offerVPN(listOf(DiscoveredEasyWorkHost(client.id,client.name,client.endpoint!!,client.scope!!.hostId,known=true)))}
        ui.onNodeWithTag("easywork-discovered-${client.id}").assertDoesNotExist()
        ui.runOnIdle {assertTrue(session.clients.isEmpty())}
    }
    /** Synthetic imported marker/journal fixture in the existing UI harness, not an export/import
     * or real pairing proof. The old credential is deliberately inert and never decoded.
     */
    @Test fun importedSavedRowOnlyOffersExplicitPairingWithoutAChannelOrOriginalMutation() {
        val normal=client;val scope=checkNotNull(normal.scope)
        val credentialName="easywork-credential:"+checkNotNull(normal.agentConnection).credentialRef
        val originalCredential=JSONObject().put("fixture","original inert credential, never decoded")
            .put("target",CodexCommandCodec.target(scope)).toString()
        store.write(credentialName,originalCredential)
        val journal=NativeOperationJournal(store);val thread="owned-imported-row-thread"
        val saved=runBlocking {
            val prepared=journal.prepare(normal,thread,"reply",ReplyIntent(normal.id,thread,UUID.randomUUID().toString(),"owned synthetic unknown input",emptyList(),"next-turn").body())
            journal.markSubmitting(prepared.target,prepared.id)
            journal.uncertain(prepared.target,prepared.id,"owned synthetic acknowledgement missing")
        }
        val journalName="operations:"+saved.target
        // Seed the imported read-only flag explicitly; this method tests the UI gate, not migration.
        val importedJournal=JSONObject(checkNotNull(store.read(journalName)))
        importedJournal.getJSONArray("operations").getJSONObject(0).put("migrationReadOnly",true)
        store.write(journalName,importedJournal.toString())
        val originalJournal=checkNotNull(store.read(journalName))
        val originalIdentity=store.read("easywork-device-identity")
        val permissions=listOf(android.Manifest.permission.CAMERA,CodexHostDiscovery.LOCAL_PERMISSION)
        val originalPermissions=permissions.associateWith {i.targetContext.checkSelfPermission(it)}
        val marker=MigratedEasyWorkConnection.decode(JSONObject().put("version",1).put("provider","easywork")
            .put("archiveId","8".repeat(64)).put("target",CodexCommandCodec.target(scope))
            .put("requiresPairing",true).put("credentialState","none").put("preference","relay-only")
            .put("candidateOrigins",JSONArray(listOf("https://synthetic.invalid"))))
        val imported=normal.copy(agentConnection=null,codexDirect=null,storageIdentity=null,
            online=false,requiresReadTarget=true,migrationEasyWork=marker)
        var originalSelection="";var originalTarget=""
        ui.runOnIdle {
            originalSelection=session.selectedClient.id;originalTarget=session.targetKey
            clients().clear();clients().add(imported);client=imported;persist();noRuntimeSession()
        }
        showSheets();ui.onNodeWithTag("client-${client.id}").assertIsDisplayed()
        touch("add-client");touch("agent-provider-easywork")
        rowName(client.name) // Imported saved rows have no agentConnection and must remain visible.
        touch("easywork-discovered-${client.id}")
        ui.onNodeWithTag("easywork-scan").performScrollTo().assertIsDisplayed().assertIsEnabled()
        ui.onNodeWithTag("easywork-qr-description").performScrollTo().assertIsDisplayed().assertIsEnabled()
        ui.onNodeWithTag("easywork-pair-error").assertDoesNotExist()
        ui.onNodeWithTag("easywork-connecting").assertDoesNotExist()
        ui.onNodeWithTag("easywork-name").performScrollTo().assertTextEquals(client.name)
        ui.onNodeWithTag("easywork-pair-lan").performScrollTo().assertIsDisplayed().assertIsNotEnabled()
        touch("cancel-client-form",false);touch("edit-client-${client.id}")
        ui.onNodeWithTag("easywork-migration-pairing-required").performScrollTo().assertIsDisplayed()
        val targetFields=listOf("agent" to ("Agent 客户端" to scope.agentId),"authority" to ("账号发行方" to scope.accountAuthority!!),"subject" to ("账号" to scope.accountSubject!!),"host" to ("电脑" to scope.hostId),"workspace" to ("工作区" to scope.workspaceId),"runtime" to ("运行时" to scope.runtimeId))
        assertEquals(listOf(scope.agentId,scope.accountAuthority,scope.accountSubject,scope.hostId,scope.workspaceId,scope.runtimeId),
            listOf(marker.target.agentId,marker.target.accountAuthority,marker.target.accountSubject,marker.target.hostId,marker.target.workspaceId,marker.target.runtimeId))
        for((key,field) in targetFields) {
            ui.onNodeWithTag("easywork-target-label-$key").performScrollTo().assertTextEquals(field.first)
            val value=ui.onNodeWithTag("easywork-target-$key")
            fullText(value,field.second)
            value.assert(SemanticsMatcher.keyNotDefined(SemanticsActions.OnClick)).assert(SemanticsMatcher.keyNotDefined(SemanticsActions.SetText))
        }
        ui.onNodeWithTag("easywork-qr-description").performScrollTo().assertIsDisplayed().assertIsEnabled()
        ui.onNodeWithTag("easywork-pair-lan").performScrollTo().assertIsDisplayed().assertIsNotEnabled()
        ui.onNodeWithTag("easywork-pair-error").assertDoesNotExist();ui.onNodeWithTag("easywork-connecting").assertDoesNotExist()
        File(folder,"imported-readonly-target-fields.json").writeText(JSONObject().put("fieldCount",targetFields.size).put("fields",JSONObject(targetFields.associate {it.first to easyHash(it.second.second.toByteArray())})).put("lane","actual imported edit UI; complete text/layout and readonly semantics; empty explicit-pair action disabled").toString())
        ui.runOnIdle {
            assertEquals(originalSelection,session.selectedClient.id);assertEquals(originalTarget,session.targetKey)
            assertEquals(originalDraft,session.text);assertFalse(session.connectionBusy);noRuntimeSession()
            assertEquals(marker,live().migrationEasyWork);assertNull(live().agentConnection)
        }
        runBlocking {store.awaitQueuedWrites()}
        assertEquals(originalCredential,store.read(credentialName));assertEquals(originalJournal,store.read(journalName))
        assertEquals(originalIdentity,store.read("easywork-device-identity"))
        assertEquals(originalPermissions,permissions.associateWith {i.targetContext.checkSelfPermission(it)})
        val original=runBlocking {journal.records(saved.target)}.single()
        assertTrue(original.migrationReadOnly);assertEquals("uncertain",original.phase)
        assertEquals(saved.id,original.id);assertEquals(saved.intent,original.intent);assertEquals(saved.intentSha256,original.intentSha256)
        // A permission/scanner window must not have been opened just by changing form method.
        var nullOwnerSamples=0;var unrefreshedSamples=0;var observedOwnWindow=false
        // The first UiAutomation connection can precede its active-window callback.
        // Wait read-only for this exact App; an explicit other owner fails immediately.
        ui.waitUntil(5_000) {
            val window=i.uiAutomation.rootInActiveWindow
            if(window==null) {nullOwnerSamples++;false} else try {
                if(!window.refresh()||window.packageName==null) {unrefreshedSamples++;false}
                else {assertEquals(i.targetContext.packageName,window.packageName.toString());observedOwnWindow=true;true}
            } finally {@Suppress("DEPRECATION") window.recycle()}
        }
        assertTrue(observedOwnWindow)
        File(folder,"original-active-window-proof.json").writeText(JSONObject().put("ownAppObserved",observedOwnWindow)
            .put("nullOwnerSamples",nullOwnerSamples).put("unrefreshedSamples",unrefreshedSamples)
            .put("readOnlyObservation",true).put("timeoutMs",5_000).put("thirdOwnerAccepted",false).toString())
        File(folder,"imported-row-local-pairing-proof.json").writeText(JSONObject()
            .put("caseId",id).put("originalOperationId",saved.id).put("originalIntentSha256",saved.intentSha256)
            .put("credentialBytesUnchanged",true).put("readonlyUnknownJournalUnchanged",true)
            .put("currentDraftAndTargetUnchanged",true).put("runtimePoolEmpty",true).put("identityUnchanged",true)
            .put("permissionsUnchanged",true).put("explicitQrUiOnly",true)
            .put("boundary","Synthetic imported row/journal and inert credential in actual product UI;not migration/pairing/Host/model acceptance").toString())
    }
    private fun fullText(node: SemanticsNodeInteraction,expected: String,requireWrap: Boolean=false) {
        node.performScrollTo().assertIsDisplayed().assertTextEquals(expected)
        val layouts=mutableListOf<TextLayoutResult>();node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) {assertTrue(it(layouts))}
        assertEquals(1,layouts.size);val layout=layouts.single();assertEquals(expected,layout.layoutInput.text.text)
        val measured=JSONObject().put("expectedHash",easyHash(expected.toByteArray())).put("utf16Length",expected.length)
            .put("hasVisualOverflow",layout.hasVisualOverflow).put("didOverflowWidth",layout.didOverflowWidth).put("didOverflowHeight",layout.didOverflowHeight)
            .put("width",layout.size.width).put("height",layout.size.height).put("paragraphWidth",layout.multiParagraph.width).put("paragraphHeight",layout.multiParagraph.height)
            .put("fontSize",layout.layoutInput.style.fontSize.toString()).put("lineHeight",layout.layoutInput.style.lineHeight.toString())
            .put("density",layout.layoutInput.density.density).put("fontScale",layout.layoutInput.density.fontScale)
            .put("softWrap",layout.layoutInput.softWrap).put("maxLines",layout.layoutInput.maxLines)
            .put("constraintMaxWidth",layout.layoutInput.constraints.maxWidth).put("constraintMaxHeight",layout.layoutInput.constraints.maxHeight)
            .put("lineCount",layout.lineCount).put("lines",JSONArray((0 until layout.lineCount).map {line->JSONObject()
                .put("start",layout.getLineStart(line)).put("end",layout.getLineEnd(line,visibleEnd=false)).put("left",layout.getLineLeft(line)).put("right",layout.getLineRight(line))
                .put("top",layout.getLineTop(line)).put("bottom",layout.getLineBottom(line)).put("ellipsized",layout.isLineEllipsized(line))}))
        File(folder,"layout-measurement-${UUID.randomUUID()}.json").writeText(measured.toString())
        assertFalse(layout.hasVisualOverflow);assertEquals(expected.length,layout.getLineEnd(layout.lineCount-1,visibleEnd=false))
        val b=node.fetchSemanticsNode().boundsInRoot;val raw=node.getUnclippedBoundsInRoot();val frame=ui.onNodeWithTag("connection-page").fetchSemanticsNode().boundsInRoot
        val originX=raw.left.value*ui.density.density;val originY=raw.top.value*ui.density.density
        val clip=P8TextRect(maxOf(b.left,frame.left)-originX,maxOf(b.top,frame.top)-originY,minOf(b.right,frame.right)-originX,minOf(b.bottom,frame.bottom)-originY)
        fun box(r: androidx.compose.ui.geometry.Rect)=P8TextRect(r.left,r.top,r.right,r.bottom)
        val observation=P8TextObservation(expected,layout.size.width,layout.size.height,clip,(0 until layout.lineCount).map {line->P8TextLine(layout.getLineStart(line),layout.getLineEnd(line,visibleEnd=false),P8TextRect(layout.getLineLeft(line),layout.getLineTop(line),layout.getLineRight(line),layout.getLineBottom(line)),layout.isLineEllipsized(line))},expected.indices.map {offset->P8TextCharacter(offset,layout.getLineForOffset(offset),box(layout.getBoundingBox(offset)))})
        val failures=p8TextVisibilityFailures(expected,observation,requireWrap);layoutProbes++
        File(folder,"text-$layoutProbes.json").writeText(JSONObject().put("expectedHash",easyHash(expected.toByteArray())).put("utf16Length",expected.length).put("lineCount",layout.lineCount).put("failures",JSONArray(failures)).toString())
        assertTrue("Entire original text must fit the actual visible clip: $failures",failures.isEmpty())
    }
    @Test fun longSavedRowsAndTargetFieldsRemainUsableInShortViewport() {
        val enteredName="Long saved computer 中文 %@ "+"name ".repeat(16)
        val longName=enteredName.trim() // Saved names normalize boundary whitespace; internal content stays exact.
        ui.runOnIdle {session.renameEasyWork(client,enteredName);assertEquals(longName,live().name);ui.activity.setContent {val density=LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density,1.6f)) {ProductTextProvider(session.language) {MaterialTheme {Surface(Modifier.width(320.dp).height(380.dp)) {ConnectionPage(session,client) {error("No pairing submission belongs to this UI case")}}}}}
        }}
        runBlocking {store.awaitQueuedWrites()}
        val saved=JSONObject(requireNotNull(store.read("metadata"))).getJSONArray("clients").objects().single()
        assertEquals(longName,saved.getString("name"))
        File(folder,"saved-name-normalization.json").writeText(JSONObject().put("enteredNameHash",easyHash(enteredName.toByteArray())).put("savedNameHash",easyHash(saved.getString("name").toByteArray())).put("normalization","boundary whitespace trim only").put("enteredUtf16Length",enteredName.length).put("savedUtf16Length",longName.length).toString())
        fullText(rowName(longName),longName,requireWrap=true)
        // These five fixture identifiers are deliberately long in this fixed short viewport.
        // The fixed provider "easywork" must remain fully visible without requiring a wrap.
        for((key,value) in listOf("agent" to client.scope!!.agentId,"authority" to client.scope!!.accountAuthority!!,"subject" to client.scope!!.accountSubject!!,"host" to client.scope!!.hostId,"workspace" to client.scope!!.workspaceId,"runtime" to client.scope!!.runtimeId))fullText(ui.onNodeWithTag("easywork-target-$key"),value,requireWrap=key!="agent")
        preference("relay-only","automatic")
        touch("easywork-connection-method");ui.onNodeWithTag("easywork-method-自动发现").assertIsSelected();touch("easywork-method-手动连接")
        val address=ui.onNodeWithTag("easywork-manual-address").performScrollTo();address.performTextReplacement("invalid://synthetic.invalid/"+"path/".repeat(48));address.performImeAction()
        touch("easywork-connection-method");ui.onNodeWithTag("easywork-method-手动连接").assertIsSelected();touch("easywork-method-自动发现")
        fullText(rowName(longName),longName,requireWrap=true)
        ui.runOnIdle {assertEquals("relay-only",live().agentConnection!!.preference);assertEquals(client.scope,live().scope)}
    }
}
