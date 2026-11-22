package com.agenthub.android

import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.util.UUID
import org.commonmark.node.Link
import org.commonmark.node.Node

/** Private SOURCE proposal. Root must review/adopt/build and select each fresh I/O window.
 * No pairing/new-thread/prompt/skip/upload entry. Read/draft phases allow zero business inputs.
 * F58-answer is separately gated: one real production UI click, with desktop reconciliation.
 * Wire command ID/raw transport receipt are NOT UI-observable and never invented here.
 * No phase is executed by source delivery; Root releases any future I/O/model allowance.
 * Reading resources uses ordinary production download acknowledgments.
 * Existing pair/namespace only; one MobileSession owns its channels and is fully retired finally.
 * route is independent of every old F31 receipt/case/assertion. F31/F59 remain unchanged.
 */
class R3EasyWorkRemainingEntryLiveTest {
    @get:Rule(order=0) val timeout:org.junit.rules.TestRule=org.junit.rules.Timeout.seconds(40)
    @get:Rule(order=1) val ui=createAndroidComposeRule<ComponentActivity>()
    private val context get()=ui.activity
    private lateinit var cfg:JSONObject
    private lateinit var storage:NativeSessionStorage
    private lateinit var client:MobileClient
    private lateinit var credential:EasyWorkCredential // Never printed, serialized, or compared with assertEquals.
    private var session:MobileSession?=null
    private var folder:File?=null
    private var deadline=0L
    private val clipboard=object:ClipboardManager {
        private var value:AnnotatedString?=null
        override fun getText()=value
        override fun hasText()=value!=null
        override fun setText(annotatedString:AnnotatedString){value=annotatedString}
    }
    private fun remaining()=(deadline-SystemClock.elapsedRealtime()).coerceAtLeast(1)
    private fun <T> main(block:suspend ()->T):T=runBlocking {
        withTimeout(remaining()){withContext(Dispatchers.Main.immediate){block()}}
    }
    private fun waitFor(condition:()->Boolean)=ui.waitUntil(remaining(),condition)
    private fun shown(tag:String)=ui.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
    private fun clickStable(tag:String) {
        val node=ui.onNodeWithTag(tag).performScrollTo().assertIsDisplayed().assertIsEnabled()
        var previous="";var since=0L
        waitFor {
            val bounds=node.fetchSemanticsNode().boundsInRoot;val current=bounds.toString()
            val now=SystemClock.elapsedRealtime()
            if(current!=previous){previous=current;since=now}
            bounds.width>0&&bounds.height>0&&now-since>=700
        }
        node.performClick()
    }
    private fun hash(value:ByteArray)=ComputerFileCodec.hash(value)
    private fun target()=EasyWorkTarget.decode(cfg.getJSONObject("expectedFullEasyWorkTarget"))
    private fun matches(value:MobileClient)=!value.isDemo&&value.scope?.agentId=="easywork"&&
        sameScope(value.scope,target().scope())&&value.agentConnection?.let {sameScope(it.target,target().scope())}==true
    private fun save(name:String,value:JSONObject) {
        val file=File(checkNotNull(folder),name)
        require(file.createNewFile()) {"Reconcile original case; evidence is immutable"}
        file.setReadable(false,false);file.setWritable(false,false)
        file.setReadable(true,true);file.setWritable(true,true);file.writeText(value.toString())
    }
    private fun phase(value:String) {
        Assume.assumeTrue("Select exactly one new phase/method",cfg.getString("phase")==value)
        if(value=="F58-answer")answerWindow()
        val id=cfg.getString("caseId");UUID.fromString(id)
        if(value=="F58-answer") require(id in cfg.getJSONArray("proposedAnswerCaseIds").strings())
        else require(id==cfg.getJSONObject("proposedCaseIds").getString(value))
        val parent=File(context.filesDir,"r3-remaining-entry-evidence").apply {mkdirs()}
        val own=File(parent,id);require(!own.exists()&&own.mkdir()) {"Case already consumed; do not replay"}
        folder=own
        save("intent.json",JSONObject().put("caseId",id).put("phase",value)
            .put("window",cfg.getString("executionWindowId")).put("clientId",client.id)
            .put("bindingId",credential.binding.id).put("agentBusinessInputsAllowed",if(value=="F58-answer")1 else 0)
            .put("agentQuestionAnswersAllowed",if(value=="F58-answer")1 else 0)
            .put("agentPromptInputsAllowed",0).put("agentOriginsAllowed",0).put("agentChildCreationAllowed",0)
            .put("maxAllowedAsyncResumptions",if(value=="F58-answer")cfg.getJSONObject("questionAnswer").getInt("maxAllowedAsyncResumptions") else 0))
    }
    @Before fun existingOwnedPairOnly() {
        val input=InstrumentationRegistry.getArguments().getString("r3RemainingEntryConfig")
        Assume.assumeTrue("Needs a separately selected Root window",input!=null)
        cfg=JSONObject(input!!);require(cfg.getBoolean("enabled"))
        require(cfg.getString("phase") in setOf("route","F20-child","F32-F72-link","F36-reported","F36-unconfirmed","F58-draft","F58-answer","F22-compression","F20-child-link","F32-F72-message-link","transport-peer-close","F20-child-history-only","resolve-receipt-loss"))
        require(cfg.getBoolean("previousNativeProcessEnded")&&cfg.getBoolean("pairingCompletedInSeparateRootWindow"))
        UUID.fromString(cfg.getString("executionWindowId"))
        require(context.packageName=="com.easyai.agenthub.android.remaining20261121.acceptance"&&android.os.Process.myUid()==10234)
        val namespace=cfg.getString("namespace");require(namespace.startsWith("r2-live-")&&namespace==cfg.getString("keyAlias"))
        val existing=File(context.noBackupFilesDir,namespace)
        require(java.nio.file.Files.isDirectory(existing.toPath(),java.nio.file.LinkOption.NOFOLLOW_LINKS)&&!java.nio.file.Files.isSymbolicLink(existing.toPath()))
        val keys=java.security.KeyStore.getInstance("AndroidKeyStore").apply {load(null)}
        require(keys.containsAlias(namespace)) {"Existing paired key required; never generate another"}
        val budget=cfg.getLong("maxNativeMs");require(budget in 6_000..40_000)
        deadline=SystemClock.elapsedRealtime()+budget-5_000 // Reserve full lifecycle cancellation within 40s.
        val endpoint=cfg.getString("endpoint");require(CodexEndpoints.local(endpoint)==endpoint)
        val expected=target()
        require(expected.desktopDeviceId==cfg.getString("expectedHostId")&&expected.workspaceId==cfg.getString("expectedWorkspaceId")&&expected.runtimeId==cfg.getString("expectedRuntimeId"))
        require(cfg.getString("expectedCertificateSHA256").matches(Regex("[a-fA-F0-9]{64}")))
        val proof=cfg.getJSONObject("rootSourceProof")
        for(key in listOf("loadedBuildProofSHA256","threadOwnershipProofSHA256"))require(proof.getString(key).matches(Regex("[a-f0-9]{64}")))
        // Ordinary writable production API, after exact existing directory/key admission above.
        storage=NativeSessionStorage(context,namespace,namespace)
        main {withContext(Dispatchers.IO) {
            val clients=checkNotNull(storage.metadata()).getJSONArray("clients").objects().map(::decodeClient).filterNot {it.isDemo}
            require(clients.size==1&&matches(clients.single())) {"Namespace must contain only this owned target"}
            client=clients.single();require(client.id==cfg.getString("expectedClientId")&&client.codexDirect==null)
            val connection=checkNotNull(client.agentConnection)
            require(connection.preference=="automatic")
            require(connection.candidates.filter {it.transportId=="lan-wss"}.all {it.peerFingerprint?.lowercase()==cfg.getString("expectedCertificateSHA256").lowercase()})
            credential=EasyWorkCredential.decode(JSONObject(checkNotNull(storage.read("easywork-credential:${connection.credentialRef}"))))
            require(credential.binding.target==expected&&credential.binding.desktopPublicKey==cfg.getString("expectedDesktopPublicKey"))
            require(credential.binding.id==cfg.getString("expectedBindingId")&&credential.binding.mobileId==cfg.getString("expectedMobileDeviceId"))
            require(hash(easyBytes(credential.binding.mobilePublicKey))==cfg.getString("expectedMobilePublicKeySHA256"))
        }}
    }
    @After fun retireSoleOwner() {
        val owned=session;session=null
        runBlocking {withTimeout(5_000){withContext(Dispatchers.Main.immediate) {
            owned?.closeComputerFiles();owned?.leaveForeground();owned?.abandonMigrationRestore()
        }}}
        // Preserve namespace/key/grant, caches, drafts and unknown results. Root owns eventual cleanup.
    }
    private fun restore():MobileSession {
        require(session==null)
        val owned=main {MobileSession(storage).also {session=it;it.leaveForeground();it.restore()}}
        waitFor {!owned.restoring}
        main {
            require(!owned.localRecoveryRequired&&!owned.migrationPaused&&owned.migrationStorage===storage)
            require(owned.clients.filterNot {it.isDemo}.single().let {it.id==client.id&&matches(it)})
        }
        return owned
    }
    private fun selected(evidencePrefix:String=""):MobileSession {
        val owned=restore()
        val current=main {owned.clients.single(::matches)}
        val connection=checkNotNull(current.agentConnection)
        val endpoint=cfg.getString("endpoint")
        if(connection.candidates.firstOrNull {it.transportId=="lan-wss"}?.endpoint!=endpoint) {
            // Authenticated runtime discovery may advertise the desktop LAN address again.
            // Restore this window's pinned transport through the ordinary API, never an old
            // transfer query, new pairing, direct metadata edit or a replayed route test case.
            val host=main {EasyWorkPairing.readHost(endpoint,cfg.getString("expectedCertificateSHA256"),target(),cfg.getString("expectedDesktopPublicKey"),5)}
            val candidates=EasyWorkPairing.directCandidates(listOf(endpoint),fingerprint=host.fingerprint)
            main {
                require(!owned.usageForeground&&current.id==client.id&&matches(current))
                require(owned.updateEasyWorkCandidates(current,credential.binding,candidates))
                withContext(Dispatchers.IO){storage.awaitQueuedWrites()}
            }
            save("${evidencePrefix}phase-route-refresh.json",JSONObject()
                .put("priorEndpoint",connection.candidates.firstOrNull {it.transportId=="lan-wss"}?.endpoint)
                .put("endpoint",endpoint).put("sameBindingId",credential.binding.id)
                .put("sameClientTargetPair",true).put("pairingRequests",0).put("transferRequests",0))
        }
        main {owned.enterForeground();owned.select(owned.clients.single(::matches))}
        val thread=cfg.getString("threadId");require(thread.isNotBlank())
        fun observe(name:String) {
            val error=owned.error.orEmpty()
            val hints=listOf("网络","超时","认证","身份","字段","格式","目标","连接","权限",
                "JSON","timeout","401","403","404","signature","certificate","target","schema")
                .filter {error.contains(it,ignoreCase=true)}
            save(name,JSONObject().put("online",owned.selectedClient.online).put("loading",owned.loading)
                .put("historyLoading",owned.historyLoading).put("expectedClientSelected",owned.selectedClient.id==client.id)
                .put("sameTarget",matches(owned.selectedClient)).put("expectedThreadPresent",owned.threads.any {it.id==thread})
                .put("threadCount",owned.threads.size).put("expectedThreadSelected",owned.selectedThread==thread)
                .put("runtimePresent",owned.runtime!=null).put("runtimeState",owned.runtime?.state)
                .put("connectionPhase",owned.selectedClient.connectionPhase.toString())
                .put("lastConnectionReadFailure",owned.lastConnectionReadFailure?.let(::JSONObject) ?: JSONObject.NULL)
                .put("errorPresent",error.isNotEmpty()).put("errorLength",error.length)
                .put("errorSHA256",if(error.isEmpty())JSONObject.NULL else hash(error.toByteArray()))
                .put("errorKnownHints",JSONArray(hints))) // Never export an arbitrary error/response body.
        }
        // Catalog refresh can overlap an already usable current chat. Match the
        // actual sidebar/selectThread gate; history and action freshness remain checked below.
        try {waitFor {owned.selectedClient.id==client.id&&matches(owned.selectedClient)&&
            owned.selectedClient.online&&owned.threads.any {it.id==thread&&it.hostId==client.id}}}
        finally {observe("${evidencePrefix}selected-workspace-observation.json")}
        main {owned.selectThread(owned.threads.single {it.id==thread})}
        try {waitFor {!owned.historyLoading&&owned.runtime!=null&&owned.selectedThread==thread}}
        finally {observe("${evidencePrefix}selected-history-observation.json")}
        require(matches(owned.selectedClient)&&owned.selectedClient.id==client.id)
        return owned
    }
    private fun render(content:@Composable ()->Unit) {
        ui.setContent {ProductTextProvider("en") {MaterialTheme {
            CompositionLocalProvider(LocalClipboardManager provides clipboard,content=content)
        }}}
    }
    private fun resourcePath(spec:JSONObject):File {
        val name=spec.getString("name");require(name==File(name).name&&name.startsWith("r3-remaining-")&&name !in listOf("",".",".."))
        val id=spec.getString("id");require(id.isNotBlank())
        require(spec.getLong("sizeBytes") in 1..2_097_152)
        require(spec.getString("sha256").matches(Regex("[a-f0-9]{64}")))
        val scope=easyHash(easyCanonical(JSONArray(listOf(CodexCommandCodec.target(client.scope!!),cfg.getString("threadId")))).toByteArray())
        return File(File(context.cacheDir,"resources"),"agent-$scope/${easyHash(id.toByteArray())}/$name")
    }
    private fun fresh(spec:JSONObject) {require(!resourcePath(spec).exists()) {"New owned resource required; no old-cache acceptance"}}
    private fun verified(spec:JSONObject) {
        val file=resourcePath(spec)
        main {withContext(Dispatchers.IO) {
            require(file.isFile&&file.length()==spec.getLong("sizeBytes")&&hash(file.readBytes())==spec.getString("sha256"))
        }}
        save("resource-${hash(spec.getString("id").toByteArray())}.json",JSONObject()
            .put("resourceId",spec.getString("id")).put("sizeBytes",file.length()).put("sha256",spec.getString("sha256")))
    }
    private fun screen(spec:JSONObject)=hasTestTag("file-preview-screen") and
        hasAnyDescendant(hasTestTag("file-preview-name") and hasText(spec.getString("name")))
    private fun preview(spec:JSONObject) {
        val name=hasTestTag("file-preview-name") and hasText(spec.getString("name"))
        waitFor {ui.onAllNodes(name).fetchSemanticsNodes().size==1&&!shown("file-preview-loading")}
        ui.onNode(name).assertIsDisplayed()
        ui.onNode(hasText(spec.getString("previewText"),substring=true) and hasAnyAncestor(screen(spec))).assertExists()
        verified(spec)
    }
    private fun closePreview(spec:JSONObject)=ui.onNode(hasTestTag("file-preview-close") and hasAnyAncestor(screen(spec))).performClick()
    private fun links(node:Node):List<Link> = buildList {
        if(node is Link)add(node)
        NativeMarkdown.children(node).forEach {addAll(links(it))}
    }
    @Test fun normalRouteRefresh_samePairNoTransferReceipt() {
        phase("route")
        val before=client;val connection=checkNotNull(before.agentConnection)
        // One original-pinned descriptor read; no pairing or discovery-provided extra address.
        val host=main {EasyWorkPairing.readHost(cfg.getString("endpoint"),cfg.getString("expectedCertificateSHA256"),target(),cfg.getString("expectedDesktopPublicKey"),5)}
        val candidates=EasyWorkPairing.directCandidates(listOf(cfg.getString("endpoint")),fingerprint=host.fingerprint)
        val owned=restore()
        save("route-intent.json",JSONObject().put("oldEndpoint",connection.candidates.first {it.transportId=="lan-wss"}.endpoint)
            .put("newEndpoint",cfg.getString("endpoint")).put("sameBindingId",credential.binding.id))
        main {
            require(!owned.usageForeground)
            val current=owned.clients.single(::matches);require(current.agentConnection==connection)
            require(owned.updateEasyWorkCandidates(current,credential.binding,candidates))
            withContext(Dispatchers.IO){storage.awaitQueuedWrites()}
        }
        val after=main {withContext(Dispatchers.IO) {
            checkNotNull(storage.metadata()).getJSONArray("clients").objects().map(::decodeClient).single(::matches)
        }}
        val live=checkNotNull(after.agentConnection)
        require(after.id==before.id&&sameScope(after.scope,before.scope)&&sameScope(live.target,connection.target))
        require(live.credentialRef==connection.credentialRef&&live.preference==connection.preference&&live.candidates.first()==candidates.single())
        main {withContext(Dispatchers.IO) {
            require(EasyWorkCredential.decode(JSONObject(checkNotNull(storage.read("easywork-credential:${live.credentialRef}"))))==credential)
        }}
        save("result.json",JSONObject().put("sameClientTargetPair",true).put("routeUpdated",true).put("transferRequests",0))
    }
    @Test fun f20_realChildHistoryAndOwnedResource() {
        phase("F20-child")
        val owned=selected();val child=cfg.getJSONObject("child");val resource=child.getJSONObject("resource");fresh(resource)
        val messageId=cfg.getString("messageId")
        waitFor {owned.messages.any {it.id==messageId&&it.kind=="subAgent"}}
        val draft=main {owned.text};val parent=owned.selectedThread
        render {NativeSubagentHistoryHost(owned) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                owned.messages.singleOrNull {it.id==messageId}?.let {MessageContent(owned,it,false,owned.targetKey)}
            }
        }}
        ui.onNodeWithTag("subagent-history-$messageId").performScrollTo().performClick()
        waitFor {shown("subagent-detail-$messageId")&&!shown("subagent-history-loading")}
        ui.onNodeWithTag("subagent-history-error").assertDoesNotExist()
        if(child.getBoolean("requirePicker")) {
            ui.onNodeWithTag("subagent-child-picker").performClick()
            ui.onNodeWithTag("subagent-child-${child.getString("childId")}").performClick()
        }
        val childMessage=child.getString("messageId")
        waitFor {!shown("subagent-history-loading")}
        ui.onNodeWithTag("subagent-history-error").assertDoesNotExist()
        val expectedIds=child.getJSONArray("expectedMessageIds").strings()
        require(expectedIds.isNotEmpty()&&expectedIds.distinct()==expectedIds&&childMessage in expectedIds&&expectedIds.all {it.isNotBlank()})
        expectedIds.forEach {id->
            ui.onNodeWithTag("subagent-history-body").performScrollToNode(hasTestTag("subagent-history-message-$id"))
            ui.onNodeWithTag("subagent-history-message-$id").assertIsDisplayed()
        }
        val markers=child.getJSONArray("expectedTextMarkers").objects();require(markers.size==3)
        markers.forEach {marker->
            val id=marker.getString("messageId");val text=marker.getString("text")
            require(id in expectedIds&&text.isNotBlank())
            ui.onNodeWithTag("subagent-history-body").performScrollToNode(hasTestTag("subagent-history-message-$id"))
            ui.onNode(hasText(text,substring=true) and hasAnyAncestor(hasTestTag("subagent-history-message-$id")))
                .performScrollTo().assertIsDisplayed()
        }
        save("child-visible-evidence.json",JSONObject().put("expectedMessageIds",JSONArray(expectedIds))
            .put("markerSHA256",JSONArray(markers.map {hash(it.getString("text").toByteArray())}))
            .put("desktopFullListAndOrderProofRequired",true))
        ui.onNodeWithTag("subagent-history-source").assertExists()
        ui.onNodeWithTag("subagent-history-body").performScrollToNode(hasTestTag("resource-card-preview-${resource.getString("id")}"))
        ui.onNodeWithTag("resource-card-preview-${resource.getString("id")}").performClick();preview(resource)
        closePreview(resource)
        ui.onNodeWithTag("subagent-detail-close").performClick()
        waitFor {!shown("subagent-detail-$messageId")}
        main {require(owned.selectedThread==parent&&owned.text==draft)}
        save("result.json",JSONObject().put("childId",child.getString("childId")).put("childMessageId",childMessage)
            .put("returnedToSameParent",true).put("draftPreserved",true).put("codexPaginationRetested",false))
    }
    @Test fun f32_f72_realDocumentRelativeLink() {
        phase("F32-F72-link")
        val owned=selected();val doc=cfg.getJSONObject("document")
        val root=doc.getJSONObject("root");val child=doc.getJSONObject("child");fresh(root);fresh(child)
        val messageId=cfg.getString("messageId")
        waitFor {owned.messages.any {it.id==messageId&&it.attachments.any {f->f.id==root.getString("id")}}}
        val attachment=main {owned.messages.single {it.id==messageId}.attachments.single {it.id==root.getString("id")}}
        require(attachment.sha256==root.getString("sha256")&&attachment.sizeBytes==root.getLong("sizeBytes")&&attachment.name==root.getString("name"))
        val ownerThread=owned.selectedThread;val draft=main {owned.text}
        render {Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            owned.messages.singleOrNull {it.id==messageId}?.let {MessageContent(owned,it,false,owned.targetKey)}
        }}
        ui.onNodeWithTag("resource-card-preview-${root.getString("id")}").performScrollTo().performClick();preview(root)
        val blockIndex=doc.getInt("linkBlockIndex");val linkIndex=doc.getInt("linkIndex")
        val reference=doc.getString("reference")
        require(MessageLinkDestination.documentReference(reference)&&android.net.Uri.parse(reference).scheme==null&&
            !reference.startsWith('/')) // ../sibling is valid inside the authenticated project root; server enforces boundary.
        val original=main {withContext(Dispatchers.IO) {resourcePath(root).readText(Charsets.UTF_8)}}
        val blocks=NativeMarkdown.children(NativeMarkdown.parse(original))
        require(blockIndex in blocks.indices)
        val originalLinks=links(blocks[blockIndex]);require(linkIndex in originalLinks.indices&&originalLinks[linkIndex].destination==reference)
        val tag="file-preview-native-$blockIndex";var layout:TextLayoutResult?=null
        ui.onNodeWithTag("file-preview-content").performScrollToNode(hasTestTag(tag))
        val node=ui.onNodeWithTag(tag)
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) {get->
            val values=mutableListOf<TextLayoutResult>();require(get(values));layout=values.single()
        }
        val text=checkNotNull(layout).layoutInput.text
        val range=text.getLinkAnnotations(0,text.length).single {(it.item as? LinkAnnotation.Clickable)?.tag=="$tag:${doc.getInt("linkIndex")}"}
        require(text.subSequence(range.start,range.end).text==doc.getString("linkLabel"))
        // Native glyph hit target, never invoke a captured resolver/callback as UI proof.
        node.performTouchInput {click(checkNotNull(layout).getBoundingBox(range.start).center)}
        preview(child);closePreview(child)
        waitFor {ui.onAllNodesWithTag("file-preview-name").fetchSemanticsNodes().size==1}
        ui.onNodeWithTag("file-preview-name").assertTextEquals(root.getString("name"))
        closePreview(root)
        main {require(owned.selectedThread==ownerThread&&owned.text==draft)}
        save("result.json",JSONObject().put("rootResourceId",root.getString("id")).put("childResourceId",child.getString("id"))
            .put("nativeLinkActivated",true).put("draftPreserved",true).put("clipboardSystemAccess",false))
    }
    /** Source proposal: Root must first observe an actual runtime-owned terminal compression
     * watermark while THIS run remains active. No compress action or business input is sent here.
     * Raw compression branch/cursor stay in desktop snapshot proof, not MobileMessage.detail.
     */
    @Test fun f22_latestCompletedCompressionAndNormalSessionReopen() {
        phase("F22-compression")
        val spec=cfg.getJSONObject("compression")
        val run=spec.getString("runId");val block=spec.getString("blockRef")
        require(run.isNotBlank()&&block.isNotBlank())
        require(spec.getString("desktopSnapshotProofSHA256").matches(Regex("[a-f0-9]{64}")))
        fun projected(owned:MobileSession):MobileMessage?=owned.messages.singleOrNull {message ->
            message.kind=="contextCompaction"&&message.runId==run&&message.id.startsWith("easywork-compaction.")&&
                message.detail?.let {raw->JSONObject(raw).let {event ->
                    event.optString("type")=="context_compression_block_created"&&event.optString("blockRef")==block
                }}==true
        }
        fun observe(owned:MobileSession,name:String):MobileMessage {
            // Restored rows alone cannot supply selectedConversationNetworkSnapshot freshness.
            waitFor {owned.selectedConversationNetworkSnapshot!=null&&owned.runtime?.turnId==run&&
                owned.running&&projected(owned)?.status=="completed"}
            val message=main {
                require(owned.selectedThread==cfg.getString("threadId")&&matches(owned.selectedClient))
                require(owned.runtime?.turnId==run&&owned.running)
                require(owned.messages.none {it.kind=="contextCompaction"&&it.runId==run&&it.status=="inProgress"})
                checkNotNull(projected(owned)).also {require(it.status=="completed")}
            }
            val event=JSONObject(checkNotNull(message.detail))
            if(event.has("traceId"))require(event.getString("traceId")==run)
            renderState(owned) // Only swaps the session in the already-mounted production app.
            val tag="compaction-${message.id}"
            ui.onNodeWithTag("message-list").performScrollToNode(hasTestTag(tag))
            ui.onNodeWithTag(tag).assertIsDisplayed().assertTextEquals(NativeL10n(context,owned.language).tr("已压缩上下文"))
            main {
                val network=checkNotNull(owned.selectedConversationNetworkSnapshot)
                require(network.threadId==cfg.getString("threadId")&&matches(network.client))
                save(name,JSONObject().put("threadId",network.threadId).put("runId",message.runId)
                    .put("messageId",message.id).put("blockRef",event.getString("blockRef"))
                    .put("status",message.status).put("eventType",event.getString("type"))
                    .put("historyEpoch",network.epoch).put("historyCursor",network.cursor)
                    .put("historyRevision",network.historyRevision).put("freshNetworkSnapshot",true)
                    .put("runtimeState",owned.runtime?.state)
                    .put("desktopSnapshotProofSHA256",spec.getString("desktopSnapshotProofSHA256")))
            }
            return message
        }
        val owned=selected();val draft=main {owned.text}
        f22VisibleSession=owned
        render {f22VisibleSession?.let {AgentHubApp(it)}}
        val first=observe(owned,"f22-before-reopen.json")
        ui.runOnIdle {f22VisibleSession=null};ui.waitForIdle()
        // Normal production full release is joined before a new owner is constructed.
        main {
            require(session===owned)
            owned.closeComputerFiles();owned.leaveForeground();owned.abandonMigrationRestore()
            session=null
        }
        val reopened=selected("f22-reopen-")
        val second=observe(reopened,"f22-after-reopen.json")
        require(first.id==second.id&&first.runId==second.runId&&first.status==second.status)
        main {require(reopened.text==draft)}
        save("f22-result.json",JSONObject().put("sameThreadRunBlockAfterSessionReopen",true)
            .put("draftPreserved",true).put("businessInputs",0).put("hostRestartTested",false)
            .put("androidProcessRestartTested",false).put("desktopRawCompressionCursorProofRequired",true))
    }
    private var f22VisibleSession by mutableStateOf<MobileSession?>(null)
    private fun renderState(owned:MobileSession) {ui.runOnIdle {f22VisibleSession=owned}}

    // Test-only link variants. Original attachment methods above remain historical and unchanged.
    private fun scopedLinkFiles(spec:JSONObject):List<File> {
        val name=spec.getString("name");val nonce=cfg.getString("qaNonce")
        require(nonce.matches(Regex("[a-f0-9]{32}")))
        require(name==File(name).name&&name.startsWith("r3-remaining-")&&name.contains(nonce.take(12)))
        require(spec.getLong("sizeBytes") in 1..2_097_152&&spec.getString("sha256").matches(Regex("[a-f0-9]{64}")))
        val scope=easyHash(easyCanonical(JSONArray(listOf(CodexCommandCodec.target(client.scope!!),cfg.getString("threadId")))).toByteArray())
        val resources=File(context.cacheDir,"resources");val root=File(resources,"agent-$scope")
        val noFollow=java.nio.file.LinkOption.NOFOLLOW_LINKS
        for(dir in listOf(context.cacheDir,resources,root)) {
            require(!java.nio.file.Files.isSymbolicLink(dir.toPath()))
            if(!java.nio.file.Files.exists(dir.toPath(),noFollow))return emptyList()
            require(java.nio.file.Files.isDirectory(dir.toPath(),noFollow))
        }
        // Exactly this authenticated parent-thread scope, one resource hash directory deep.
        // Probe only the expected basename; never walk another scope or read unrelated files.
        return buildList {
            java.nio.file.Files.newDirectoryStream(root.toPath()).use {entries->
                var count=0
                for(dir in entries) {
                    require(++count<=4096)
                    require(!java.nio.file.Files.isSymbolicLink(dir))
                    if(!dir.fileName.toString().matches(Regex("[a-f0-9]{64}")))continue
                    if(!java.nio.file.Files.isDirectory(dir,noFollow))continue
                    val path=dir.resolve(name)
                    require(!java.nio.file.Files.isSymbolicLink(path))
                    if(java.nio.file.Files.exists(path,noFollow)) {
                        require(java.nio.file.Files.isRegularFile(path,noFollow));add(path.toFile())
                    }
                }
            }
        }
    }
    private fun freshLinkFile(spec:JSONObject) {
        val count=main {withContext(Dispatchers.IO) {scopedLinkFiles(spec).size}}
        if(count==0)return
        require(cfg.opt("allowReconciledOwnedLinkCache")==true) {"Original owned nonce cache already exists; reconcile, never erase/replay"}
        require(count==1) {"Owned link cache must be unique"}
        linkFileBytes(spec,beforeCache=true)
    }
    private fun linkFileBytes(spec:JSONObject,beforeCache:Boolean=false):ByteArray=main {withContext(Dispatchers.IO) {
        val proof=if(beforeCache) {
            require(cfg.getString("phase")=="F32-F72-message-link"&&cfg.opt("allowReconciledOwnedLinkCache")==true)
            cfg.getJSONObject("reconciledOwnedLinkCacheProofs").getString(spec.getString("name")).also {
                require(it.matches(Regex("[a-f0-9]{64}"))) {"Root-owned cache proof SHA256 required"}
            }
        } else null
        val file=scopedLinkFiles(spec).single();val path=file.toPath()
        val uid=android.os.Process.myUid()
        require(android.system.Os.lstat(file.path).st_uid==uid) {"Owned link cache UID mismatch"}
        val noFollow=java.nio.file.LinkOption.NOFOLLOW_LINKS
        val before=java.nio.file.Files.readAttributes(path,java.nio.file.attribute.BasicFileAttributes::class.java,noFollow)
        require(before.isRegularFile&&before.size()==spec.getLong("sizeBytes"))
        val bytes=ByteArray(before.size().toInt())
        java.nio.file.Files.newByteChannel(path,java.nio.file.StandardOpenOption.READ,noFollow).use {channel->
            val buffer=java.nio.ByteBuffer.wrap(bytes)
            while(buffer.hasRemaining())require(channel.read(buffer)>0)
            require(channel.read(java.nio.ByteBuffer.allocate(1))==-1)
        }
        val after=java.nio.file.Files.readAttributes(path,java.nio.file.attribute.BasicFileAttributes::class.java,noFollow)
        require(before.fileKey()==after.fileKey()&&before.size()==after.size()&&before.lastModifiedTime()==after.lastModifiedTime())
        require(android.system.Os.lstat(file.path).st_uid==uid) {"Owned link cache UID changed"}
        require(hash(bytes)==spec.getString("sha256"))
        // No resourceId is fabricated from the cache directory hash.
        val prefix=if(beforeCache)"cache-before-" else "link-file-"
        save("$prefix${spec.getString("name")}.json",JSONObject().put("name",file.name)
            .put("cacheScope",file.parentFile.parentFile.name).put("cacheEntry",file.parentFile.name+"/"+file.name)
            .put("matchedFiles",1).put("sizeBytes",bytes.size).put("sha256",hash(bytes))
            .put("uid",uid).put("beforeCache",beforeCache).apply {
                if(proof!=null)put("reconciledOwnedLinkCacheProofSHA256",proof)
            })
        bytes
    }}
    private fun previewLinkFile(spec:JSONObject):ByteArray {
        val name=hasTestTag("file-preview-name") and hasText(spec.getString("name"))
        try {waitFor {(ui.onAllNodes(name).fetchSemanticsNodes().size==1&&!shown("file-preview-loading"))||shown("message-link-error")}}
        finally {
            val error=ui.onAllNodes(hasAnyAncestor(hasTestTag("message-link-error")),useUnmergedTree=true).fetchSemanticsNodes()
                .flatMap {it.config.getOrNull(SemanticsProperties.Text).orEmpty()}.joinToString("\n") {it.text}
            val hints=listOf("认证","目录","资源","不存在","不支持","文件","原消息","身份","更新","连接","超时",
                "invalid_params","unknown_message","resource_reference_not_found","file_not_found","messageId")
                .filter {error.contains(it,ignoreCase=true)}
            save("preview-${spec.getString("name")}-observation.json",JSONObject()
                .put("expectedNamePresent",ui.onAllNodes(name).fetchSemanticsNodes().size==1)
                .put("loadingPresent",shown("file-preview-loading")).put("messageLinkErrorPresent",shown("message-link-error"))
                .put("errorLength",error.length).put("errorSHA256",hash(error.toByteArray())).put("knownErrorHints",JSONArray(hints)))
        }
        require(!shown("message-link-error")) {"Real message-link failure; see sanitized preview observation"}
        ui.onNode(name).assertIsDisplayed()
        ui.onAllNodes(hasText(spec.getString("previewText"),substring=true) and hasAnyAncestor(screen(spec))).onFirst().assertIsDisplayed()
        return linkFileBytes(spec)
    }
    private fun clickOriginalLink(source:String,prefix:String,scrollTag:String,spec:JSONObject,witness:String) {
        val reference=spec.getString("reference");val label=spec.getString("label")
        require(MessageLinkDestination.documentReference(reference)&&android.net.Uri.parse(reference).scheme==null&&!reference.startsWith('/'))
        val matches=NativeMarkdown.children(NativeMarkdown.parse(source)).withIndex().flatMap {block->
            links(block.value).withIndex().filter {it.value.destination==reference}.map {Triple(block.index,it.index,block.value)}
        }
        val match=matches.single()
        require(match.third is org.commonmark.node.Paragraph||match.third is org.commonmark.node.Heading)
        val tag="$prefix-${match.first}";var layout:TextLayoutResult?=null
        if(scrollTag=="real-message-link-body")ui.onNodeWithTag(tag).performScrollTo()
        else ui.onNodeWithTag(scrollTag).performScrollToNode(hasTestTag(tag))
        val node=ui.onNodeWithTag(tag).assertIsDisplayed()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) {get->
            val values=mutableListOf<TextLayoutResult>();require(get(values));layout=values.single()
        }
        val rendered=checkNotNull(layout).layoutInput.text
        val range=rendered.getLinkAnnotations(0,rendered.length).single {
            (it.item as? LinkAnnotation.Clickable)?.tag=="$tag:${match.second}"
        }
        require(rendered.subSequence(range.start,range.end).text==label)
        save("$witness-link-intent.json",JSONObject().put("nativeTag",tag).put("reference",reference)
            .put("label",label).put("sourceSHA256",hash(source.toByteArray())).put("renderedTextSHA256",hash(rendered.text.toByteArray())))
        node.performTouchInput {click(checkNotNull(layout).getBoundingBox(range.start).center)}
    }
    @Test fun transport_peerCloseCompletesBeforeHandshakeTimeout() {
        phase("transport-peer-close")
        val candidate=AgentCandidate("owned-close-boundary","lan-wss",cfg.getString("endpoint"),cfg.getString("expectedCertificateSHA256"))
        // A fresh nonexistent binding asks the real, pinned Task DEV to reject the
        // upgrade. No encrypted frame, business request, pairing or model is sent.
        val transport=LANFrameTransport(candidate,"unbound-"+cfg.getString("caseId"))
        val started=SystemClock.elapsedRealtime()
        try {
            val failure=runBlocking {withTimeout(minOf(remaining(),5_000)) {
                runCatching {transport.receive()}.exceptionOrNull()
            }}
            require(failure is HostReadError)
            val closed=generateSequence(failure as Throwable) {it.cause}.filterIsInstance<EasyWorkChannelClosed>().single()
            require(closed.closeCode==1008)
            save("result.json",JSONObject().put("peerCloseCode",closed.closeCode)
                .put("elapsedMs",SystemClock.elapsedRealtime()-started).put("encryptedFramesSent",0).put("businessRequests",0))
        } finally {transport.close();runBlocking {withTimeout(remaining()) {transport.awaitClosedForMigration()}}}
    }
    @Test fun f32_f72_realMessageLinkAndDocumentSibling() {
        phase("F32-F72-message-link")
        val owned=selected();val doc=cfg.getJSONObject("documentLink")
        val root=doc.getJSONObject("root");val notes=doc.getJSONObject("notes")
        freshLinkFile(root);freshLinkFile(notes)
        fun original()=owned.messages.filter {it.role=="assistant"&&
            it.replyMessageId==doc.getString("originalReplyMessageId")&&it.runId==doc.getString("rootRunId")&&
            links(NativeMarkdown.parse(it.text)).any {link->link.destination==doc.getJSONObject("messageLink").getString("reference")}}
        waitFor {original().size==1};val message=main {original().single()}
        val thread=owned.selectedThread;val draft=main {owned.text}
        save("original-message.json",JSONObject().put("projectedMessageId",message.id).put("replyMessageId",message.replyMessageId)
            .put("runId",message.runId).put("textSHA256",hash(message.text.toByteArray())))
        render {Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).testTag("real-message-link-body")) {
            // Production chat renders the current authenticated row. Holding its
            // first metadata snapshot here can make the strict owner guard reject
            // a click after an ordinary history refresh, while the real chat has
            // already recomposed with that row's current metadata.
            owned.messages.singleOrNull {it.id==message.id}?.let {current->
                require(current.text==message.text&&current.replyMessageId==message.replyMessageId&&current.runId==message.runId)
                MessageContent(owned,current,false,owned.targetKey)
            }
        }}
        main {
            val current=owned.messages.single {it.id==message.id}
            save("live-source-observation.json",JSONObject()
                .put("sameOriginalText",current.text==message.text).put("sameStatus",current.status==message.status)
                .put("sameAttachments",current.attachments==message.attachments).put("sameFiles",current.files==message.files)
                .put("sameBlocks",current.blocks==message.blocks)
                .put("firstSnapshotOwnerCurrent",owned.messageLinkCurrent(owned.messageLinkContext(message)))
                .put("currentRowOwnerCurrent",owned.messageLinkCurrent(owned.messageLinkContext(current))))
        }
        clickOriginalLink(message.text,"markdown-${message.id}","real-message-link-body",doc.getJSONObject("messageLink"),"parent")
        val rootBytes=previewLinkFile(root)
        clickOriginalLink(String(rootBytes,Charsets.UTF_8),"file-preview-native","file-preview-content",doc.getJSONObject("siblingLink"),"sibling")
        previewLinkFile(notes);closePreview(notes)
        waitFor {ui.onAllNodesWithTag("file-preview-name").fetchSemanticsNodes().size==1}
        ui.onNodeWithTag("file-preview-name").assertTextEquals(root.getString("name"));closePreview(root)
        main {require(owned.selectedThread==thread&&owned.text==draft)}
        save("result.json",JSONObject().put("projectedMessageId",message.id).put("nativeMessageLinkActivated",true)
            .put("nativeSiblingLinkActivated",true).put("returnedToSameParent",true).put("draftPreserved",true))
    }
    /** Independent EasyWork history observation; resource-link preview is a separate case. */
    @Test fun f20_realCompleteChildHistoryWithoutFilePreview() {
        phase("F20-child-history-only")
        val owned=selected();val child=cfg.getJSONObject("childLink")
        val card=child.getString("parentCardMessageId")
        val ids=child.getJSONArray("expectedMessageIds").strings()
        val markers=child.getJSONArray("expectedTextMarkers").objects()
        require(ids.size==6&&ids.distinct()==ids&&ids.all {it.isNotBlank()}&&markers.size==3)
        require(markers.all {it.getString("messageId") in ids&&it.getString("text").isNotBlank()})
        waitFor {owned.messages.any {it.id==card&&it.kind=="subAgent"&&it.runId==child.getString("rootRunId")}}
        val parent=owned.selectedThread;val draft=main {owned.text}
        render {ProductTextProvider(owned.language) {NativeSubagentHistoryHost(owned) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                owned.messages.singleOrNull {it.id==card}?.let {MessageContent(owned,it,false,owned.targetKey)}
            }
        }}}
        ui.onNodeWithTag("subagent-history-$card").performScrollTo().performClick()
        try {waitFor {shown("subagent-detail-$card")&&!shown("subagent-history-loading")}}
        finally {
            save("history-detail-observation.json",JSONObject().put("detailPresent",shown("subagent-detail-$card"))
                .put("loadingPresent",shown("subagent-history-loading")).put("errorPresent",shown("subagent-history-error"))
                .put("sameParent",owned.selectedThread==parent).put("sameTarget",matches(owned.selectedClient))
                .put("online",owned.selectedClient.online).put("lastConnectionReadFailure",owned.lastConnectionReadFailure?.let(::JSONObject)?:JSONObject.NULL))
        }
        ui.onNodeWithTag("subagent-history-error").assertDoesNotExist()
        ids.forEach {id ->
            ui.onNodeWithTag("subagent-history-body").performScrollToNode(hasTestTag("subagent-history-message-$id"))
            ui.onNodeWithTag("subagent-history-message-$id").assertIsDisplayed()
        }
        markers.forEach {marker ->
            val id=marker.getString("messageId")
            ui.onNodeWithTag("subagent-history-body").performScrollToNode(hasTestTag("subagent-history-message-$id"))
            ui.onNode(hasText(marker.getString("text"),substring=true) and hasAnyAncestor(hasTestTag("subagent-history-message-$id")))
                .performScrollTo().assertIsDisplayed()
        }
        ui.onNodeWithTag("subagent-history-source").assertIsDisplayed()
            .assertTextEquals(NativeL10n(context,owned.language).tr("最近保存的记录"))
        save("history-visible-evidence.json",JSONObject().put("observedProjectedMessageIds",JSONArray(ids))
            .put("cardMessageId",card).put("childId",child.getString("childId")).put("source","saved-history")
            .put("markerSHA256",JSONArray(markers.map {hash(it.getString("text").toByteArray())})))
        ui.onNodeWithTag("subagent-detail-close").performClick();waitFor {!shown("subagent-detail-$card")}
        main {require(owned.selectedThread==parent&&matches(owned.selectedClient)&&owned.text==draft)}
        save("result.json",JSONObject().put("nativeHistoryEntryActivated",true).put("source","saved-history")
            .put("visibleOriginalRecordCount",ids.size).put("visiblePublicMarkerCount",markers.size)
            .put("returnedToSameParent",true).put("draftPreserved",true).put("draftLengthBefore",draft.length)
            .put("draftWasNonEmpty",draft.isNotEmpty()).put("explicitResourceActions",0).put("childFilePreviewAttempted",false)
            .put("resourceDownloadsInitiatedByThisMethod",0).put("modelInputs",0))
    }

    /** Optional future real boundary. Requires the separately adopted durable resolve/WRITE fixes.
     * One actual allocation response is lost. No manual command/status call, fake reply or download.
     */
    @Test fun resolve_realLostAllocationReplyUsesOriginalReceipt() {
        phase("resolve-receipt-loss")
        require(session==null&&cfg.getBoolean("allowControlledResolveReplyLoss"))
        require(cfg.getBoolean("exclusiveLegacyBindingWindow"))
        require(credential.grantRef==null&&credential.binding.json.optJSONObject("deviceAuthorization")==null) {
            "Only the original legacy grant can use this LAN-only boundary; never downgrade"
        }
        val spec=cfg.getJSONObject("resolveReceipt")
        require(spec.getBoolean("serverDurableResolveReceipts")&&spec.getBoolean("androidResolveWriteRecovery"))
        for(key in listOf("serverSourceProofSHA256","androidSourceProofSHA256"))
            require(spec.getString(key).matches(Regex("[a-f0-9]{64}")))
        val sourceProof=cfg.getJSONObject("rootSourceProof")
        require(spec.getString("serverSourceProofSHA256")==sourceProof.getString("serverBuildProofSHA256"))
        require(spec.getString("androidSourceProofSHA256")==sourceProof.getString("loadedBuildProofSHA256"))
        require(spec.getString("method")=="resource/resolve")
        val operation=spec.getString("operationId");UUID.fromString(operation)
        require(operation!=cfg.getString("caseId"))
        val thread=cfg.getString("threadId");val message=spec.getString("messageId");val reference=spec.getString("reference")
        require(thread.isNotBlank()&&message.isNotBlank()&&reference.isNotBlank())
        val bound=target();val endpoint=cfg.getString("endpoint");val pin=cfg.getString("expectedCertificateSHA256").lowercase()
        val params=JSONObject().put("threadId",thread).put("messageId",message).put("reference",reference)
        val requestHash=easyHash(easyCanonical(JSONObject().put("target",bound.json()).put("method","resource/resolve").put("params",params)).toByteArray())
        val file=spec.getJSONObject("file")
        require(file.getLong("sizeBytes") in 1..2_097_152&&file.getString("sha256").matches(Regex("[a-f0-9]{64}")))
        fun metadata(value:JSONObject):JSONObject {
            val a=value.getJSONObject("attachment");val transfer=value.getJSONObject("transfer")
            require(a.getString("resourceId").isNotBlank()&&transfer.getString("transferId").isNotBlank())
            require(a.getString("name")==file.getString("name")&&a.getLong("size")==file.getLong("sizeBytes")&&a.getString("sha256")==file.getString("sha256"))
            return JSONObject().put("resourceId",a.getString("resourceId")).put("transferId",transfer.getString("transferId"))
                .put("name",a.getString("name")).put("sizeBytes",a.getLong("size")).put("sha256",a.getString("sha256"))
                .put("contentType",a.getString("contentType"))
        }
        val transports=java.util.concurrent.CopyOnWriteArrayList<AgentFrameTransport>()
        val allocated=java.util.concurrent.atomic.AtomicReference<JSONObject>()
        val receiptAllocation=java.util.concurrent.atomic.AtomicReference<JSONObject>()
        val resolveReplies=java.util.concurrent.atomic.AtomicInteger()
        val receiptReplies=java.util.concurrent.atomic.AtomicInteger()
        val registry=AgentTransportRegistry().apply {
            register("lan-wss") {candidate,binding,_,authorize ->
                // reachable() may discover more addresses: reject them BEFORE constructing a socket.
                require(candidate.endpoint==endpoint&&candidate.peerFingerprint.equals(pin,ignoreCase=true))
                require(binding.id==credential.binding.id&&binding.target==bound&&authorize==null)
                val native=LANFrameTransport(candidate,binding.id)
                transports.add(native)
                val inspector=EasyWorkCrypto(credential,binding) // inbound open only; no seal/send/new socket
                object:AgentFrameTransport by native {
                    override suspend fun receive():ByteArray {
                        val frame=native.receive()
                        val payload=inspector.open(frame).payload
                        if(payload.optString("kind")=="protocol") {
                            val envelope=payload.getJSONObject("envelope")
                            if(envelope.optString("kind")=="response"&&envelope.optBoolean("ok")) {
                                val value=envelope.optJSONObject("result")
                                if(envelope.optString("requestId")==operation) {
                                    val observed=metadata(checkNotNull(value))
                                    if(resolveReplies.incrementAndGet()==1) {
                                        allocated.set(observed)
                                        save("allocation-observed-before-loss.json",JSONObject().put("operationId",operation)
                                            .put("requestHash",requestHash).put("allocation",observed).put("replyWithheld",true))
                                        native.close()
                                        // A cancellation would bypass the production recovery catch and waste30s.
                                        throw java.io.IOException("Controlled first resolve reply loss")
                                    }
                                }
                                if(value!=null&&value.optString("operationId")==operation) {
                                    require(envelope.getString("requestId")!=operation)
                                    require(value.getString("method")=="resource/resolve"&&value.getString("requestHash")==requestHash)
                                    val actualTarget=EasyWorkTarget.decode(value.getJSONObject("target"));require(actualTarget==bound)
                                    require(value.getString("state")=="completed"&&value.getString("deviceId")==credential.binding.mobileId)
                                    val response=value.getJSONObject("response")
                                    require(response.getInt("protocolVersion")==2&&response.getString("kind")=="response"&&
                                        response.getString("requestId")==operation&&response.getBoolean("ok"))
                                    val observed=metadata(response.getJSONObject("result"))
                                    require(easyCanonical(observed)==easyCanonical(checkNotNull(allocated.get())))
                                    receiptAllocation.set(observed)
                                    if(receiptReplies.incrementAndGet()==1)
                                        save("original-command-receipt-observed.json",JSONObject().put("operationId",value.getString("operationId"))
                                            .put("method",value.getString("method")).put("requestHash",value.getString("requestHash"))
                                            .put("targetSHA256",hash(easyCanonical(actualTarget.json()).toByteArray()))
                                            .put("state",value.getString("state")).put("allocation",observed))
                                }
                            }
                        }
                        return frame
                    }
                }
            }
        }
        val connection=checkNotNull(client.agentConnection).copy(preference="lan-only",candidates=listOf(
            AgentCandidate("r3-resolve-"+cfg.getString("caseId"),"lan-wss",endpoint,pin)))
        save("resolve-boundary-intent.json",JSONObject().put("operationId",operation).put("method","resource/resolve")
            .put("threadId",thread).put("messageId",message).put("referenceSHA256",hash(reference.toByteArray()))
            .put("requestHash",requestHash).put("serverSourceProofSHA256",spec.getString("serverSourceProofSHA256"))
            .put("androidSourceProofSHA256",spec.getString("androidSourceProofSHA256"))
            .put("allocatingResolveCallsAllowed",1).put("inboundRepliesToDrop",1).put("downloadsAllowed",0).put("modelInputs",0))
        val remote=EasyWorkRemoteSession(connection,credential,{error("LAN-only legacy boundary must not request login")},registry)
        val recovered=try {
            val reply=runBlocking {withTimeout(remaining()) {remote.request("resource/resolve",params,operation)}}
            require(resolveReplies.get()>=1&&receiptReplies.get()>=1) {
                "A repeated resolve with deduped IDs is not receipt recovery"
            }
            val observed=metadata(reply)
            require(easyCanonical(observed)==easyCanonical(checkNotNull(allocated.get()))&&
                easyCanonical(observed)==easyCanonical(checkNotNull(receiptAllocation.get())))
            observed
        } finally {
            runBlocking {withContext(NonCancellable) {withTimeout(5_000) {
                try {remote.closeAndJoinForMigration()}
                finally {
                    val closes=transports.map {runCatching {it.close()}}
                    val drains=transports.map {runCatching {it.awaitClosedForMigration()}}
                    closes.forEach {it.getOrThrow()};drains.forEach {it.getOrThrow()}
                }
            }}}
        }
        save("result.json",JSONObject().put("operationId",operation).put("method","resource/resolve")
            .put("requestHash",requestHash).put("allocation",recovered).put("droppedResolveReplies",1)
            .put("resolveSuccessRepliesObserved",resolveReplies.get()).put("originalReceiptRepliesObserved",receiptReplies.get())
            .put("sameOriginalAllocation",true).put("allCreatedTransportsClosed",true).put("resourceDownloads",0).put("modelInputs",0))
    }

    @Test fun f20_realChildHistoryAndTextResourceLink() {
        phase("F20-child-link")
        val owned=selected();val child=cfg.getJSONObject("childLink");val file=child.getJSONObject("file")
        freshLinkFile(file)
        val card=child.getString("parentCardMessageId")
        waitFor {owned.messages.any {it.id==card&&it.kind=="subAgent"&&it.runId==child.getString("rootRunId")}}
        val thread=owned.selectedThread;val draft=main {owned.text}
        render {NativeSubagentHistoryHost(owned) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                owned.messages.singleOrNull {it.id==card}?.let {MessageContent(owned,it,false,owned.targetKey)}
            }
        }}
        ui.onNodeWithTag("subagent-history-$card").performScrollTo().performClick()
        try {waitFor {shown("subagent-detail-$card")&&!shown("subagent-history-loading")}}
        finally {
            save("child-detail-observation.json",JSONObject().put("detailPresent",shown("subagent-detail-$card"))
                .put("loadingPresent",shown("subagent-history-loading")).put("errorPresent",shown("subagent-history-error"))
                .put("sameParent",owned.selectedThread==thread).put("supportsHistory",owned.supportsSubagentHistory)
                .put("online",owned.selectedClient.online).put("historyLoading",owned.historyLoading)
                .put("lastConnectionReadFailure",owned.lastConnectionReadFailure?.let(::JSONObject) ?: JSONObject.NULL))
        }
        ui.onNodeWithTag("subagent-history-error").assertDoesNotExist()
        val ids=child.getJSONArray("expectedMessageIds").strings()
        require(ids.isNotEmpty()&&ids.distinct()==ids&&ids.all {it.isNotBlank()})
        val observed=mutableListOf<String>()
        ids.forEach {id->
            ui.onNodeWithTag("subagent-history-body").performScrollToNode(hasTestTag("subagent-history-message-$id"))
            val row=ui.onNodeWithTag("subagent-history-message-$id").assertIsDisplayed().fetchSemanticsNode()
            require(row.config[SemanticsProperties.TestTag]=="subagent-history-message-$id");observed.add(id)
        }
        val markers=child.getJSONArray("expectedTextMarkers").objects();require(markers.size==3)
        markers.forEach {marker->
            val id=marker.getString("messageId");require(id in observed&&marker.getString("text").isNotBlank())
            ui.onNodeWithTag("subagent-history-body").performScrollToNode(hasTestTag("subagent-history-message-$id"))
            ui.onNode(hasText(marker.getString("text"),substring=true) and hasAnyAncestor(hasTestTag("subagent-history-message-$id")))
                .performScrollTo().assertIsDisplayed()
        }
        ui.onNodeWithTag("subagent-history-source").assertExists()
        val id=child.getString("linkMessageId");require(id in observed)
        save("child-visible-evidence.json",JSONObject().put("observedProjectedMessageIds",JSONArray(observed))
            .put("cardMessageId",card).put("childId",child.getString("childId"))
            .put("markerSHA256",JSONArray(markers.map {hash(it.getString("text").toByteArray())})))
        clickOriginalLink(child.getString("publicLinkMessageText"),"markdown-$id","subagent-history-body",child.getJSONObject("link"),"child")
        previewLinkFile(file);closePreview(file)
        ui.onNodeWithTag("subagent-detail-close").performClick();waitFor {!shown("subagent-detail-$card")}
        main {require(owned.selectedThread==thread&&owned.text==draft)}
        save("result.json",JSONObject().put("cardMessageId",card).put("linkMessageId",id)
            .put("nativeChildLinkActivated",true).put("returnedToSameParent",true).put("draftPreserved",true))
    }

    @Test fun f36_reportedSelectedCompletedRootRun()=speed("F36-reported","reported")
    @Test fun f36_unconfirmedSelectedRootRun()=speed("F36-unconfirmed","unconfirmed")
    private fun speed(phaseName:String,status:String) {
        phase(phaseName)
        val owned=selected();val spec=cfg.getJSONObject("speed")
        require(spec.getString("status")==status)
        waitFor {owned.runtime?.generationSpeed?.runId==spec.getString("runId")}
        val report=main {checkNotNull(owned.runtime?.generationSpeed)}
        require(report.runId==spec.getString("runId")&&report.requested==spec.optional("requested")&&report.status==status)
        if(status=="reported") {
            require(report.actual==spec.getString("actual"))
        } else require(report.actual==null&&!spec.has("actual"))
        val preference=main {owned.generationSettings}
        render {GenerationPage(owned)}
        ui.onNodeWithTag("last-requested-speed").performScrollTo().assertTextEquals(spec.getString("requestedUi"))
        ui.onNodeWithTag("last-actual-speed").performScrollTo().assertTextEquals(spec.getString("actualUi"))
        main {require(owned.generationSettings==preference&&owned.runtime?.generationSpeed==report)}
        save("result.json",report.json().put("generationPreferenceUnchanged",true).put("matchedRootSelectedRunId",true).put("actualDiffersFromRequested",report.actual!=null&&report.actual!=report.requested))
    }
    @Test fun f58_realQuestionsLocalDraftsNeverSubmit() {
        phase("F58-draft")
        val owned=selected();val q=cfg.getJSONObject("questions")
        val expectedIds=q.getJSONArray("expectedPendingApprovalIds").strings()
        require(expectedIds.size==2&&expectedIds.first()==q.getString("approvalId")&&expectedIds.last()==q.getString("secondApprovalId"))
        fun pending()=owned.runtime?.approvals.orEmpty().filter {it.kind=="question"&&it.interactionMode=="async"}
        waitFor {pending().map {it.id}==expectedIds}
        val approval=main {pending().single {it.id==q.getString("approvalId")}}
        require(approval.turnId==q.getString("runId")&&approval.threadId==owned.selectedThread)
        val first=approval.questions.single {it.id==q.getString("firstQuestionId")}
        val second=approval.questions.single {it.id==q.getString("secondQuestionId")}
        require(approval.questions.size==2&&first.id!=second.id&&first.custom&&second.custom&&!first.secret&&!second.secret)
        val other=main {pending().single {it.id==q.getString("secondApprovalId")}}
        require(other.id!=approval.id&&other.questions.size==1&&other.questions.single().id==q.getString("thirdQuestionId"))
        if(q.getBoolean("expectStillRunning"))require(owned.running&&owned.runtime?.turnId==approval.turnId)
        val draft=main {owned.text};val before=expectedIds.toList()
        render {ExecutionPanels(owned,autoPresentQuestions=false)}
        ui.onNodeWithTag("open-async-questions").performClick()
        val index=expectedIds.indexOf(approval.id);repeat(index){ui.onNodeWithTag("async-question-next").performClick()}
        ui.onNodeWithTag("async-question-status").assertTextEquals(q.getString("statusUi"))
        val a=q.getString("localDraftA");val b=q.getString("localDraftB")
        require(a.isNotEmpty()&&b.isNotEmpty()&&a.length<=256&&b.length<=256)
        fun field(id:String)=ui.onNodeWithTag("question-custom-$id")
        field(first.id).performScrollTo().assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText,AnnotatedString("")))
        field(second.id).performScrollTo().assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText,AnnotatedString("")))
        field(first.id).performScrollTo().performTextInput(a)
        field(second.id).performScrollTo().performTextInput(b)
        field(first.id).performScrollTo().assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText,AnnotatedString(a)))
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        val otherIndex=expectedIds.indexOf(other.id)
        repeat(kotlin.math.abs(otherIndex-index)) {
            ui.onNodeWithTag(if(otherIndex>index)"async-question-next" else "async-question-prev").performClick()
        }
        ui.onNodeWithTag("question-title-${other.questions.single().id}").assertIsDisplayed()
        repeat(kotlin.math.abs(otherIndex-index)) {
            ui.onNodeWithTag(if(otherIndex>index)"async-question-prev" else "async-question-next").performClick()
        }
        field(first.id).performScrollTo().assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText,AnnotatedString(a)))
        field(second.id).performScrollTo().assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText,AnnotatedString(b)))
        ui.onNodeWithTag("close-async-questions").performClick()
        ui.onNodeWithTag("answer-question").assertDoesNotExist()
        ui.onNodeWithTag("open-async-questions").performClick()
        field(second.id).performScrollTo().assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText,AnnotatedString(b)))
        field(first.id).performScrollTo().assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText,AnnotatedString(a)))
        field(first.id).performTextClearance();field(second.id).performScrollTo().performTextClearance()
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        ui.onNodeWithTag("close-async-questions").performClick()
        main {require(pending().map {it.id}==before&&owned.text==draft)}
        save("result.json",JSONObject().put("approvalId",approval.id).put("runId",approval.turnId)
            .put("questionIds",JSONArray(listOf(first.id,second.id))).put("localDraftRoundTrip",true)
            .put("answerOrSkipSubmitted",false).put("serverVerbatimAnswerAcceptance",false))
    }
    private fun answerWindow() {
        val answer=cfg.getJSONObject("questionAnswer")
        require(cfg.getBoolean("allowQuestionAnswer")&&answer.getBoolean("enabled"))
        val packageId=answer.getString("qaPackageId")
        require(packageId.startsWith("root-next-increment-qa-"))
        UUID.fromString(packageId.removePrefix("root-next-increment-qa-"))
        require(answer.getString("windowProofSHA256").matches(Regex("[a-f0-9]{64}")))
        require(answer.getString("executionWindowId")==cfg.getString("executionWindowId"))
        require(answer.getInt("maxQuestionSubmissions")==1&&answer.getInt("maxAllowedAsyncResumptions") in 0..2)
        require(answer.getInt("maxRootRunsInWholeQaPackage") in 1..3)
        require(answer.getInt("maxOriginsInWholeQaPackage")==1&&answer.getInt("maxChildrenInWholeQaPackage") in 0..1)
        require(answer.getString("threadId")==cfg.getString("threadId"))
        require(answer.getJSONArray("forbiddenPriorCaseIds").strings().none {it==cfg.getString("caseId")})
        require(answer.getBoolean("rootVerifiedFreshNotQ04OrPriorCase"))
        val actionId=answer.getString("actionIntentId");UUID.fromString(actionId)
        require(actionId!=cfg.getString("caseId")&&actionId==cfg.getJSONObject("proposedAnswerActionIds").getString(cfg.getString("caseId")))
    }
    /** Root-selected existing UI + independent desktop reconciliation, not a transport hook.
     * answer() returns Unit; only the local action intent ID and current UI state are observable.
     * Desktop must match questionResponse verbatim, async status, stable resolved dedupe and runs.
     */
    @Test fun f58_answerExistingExactSession_onceUiOnly() {
        phase("F58-answer")
        val spec=cfg.getJSONObject("questionAnswer")
        val owned=selected()
        fun pending()=owned.runtime?.approvals.orEmpty().filter {it.kind=="question"&&it.interactionMode=="async"}
        waitFor {pending().any {it.id==spec.getString("approvalId")}}
        val approval=main {pending().single {it.id==spec.getString("approvalId")}}
        require(approval.id.startsWith("question.")&&approval.threadId==spec.getString("threadId")&&approval.turnId==spec.getString("runId"))
        val identity=JSONObject(String(easyBytes(approval.id.removePrefix("question.")),Charsets.UTF_8))
        for(key in listOf("threadId","runId","sessionId","toolCallId"))require(identity.getString(key)==spec.getString(key))
        require(identity.getBoolean("async"))
        val answers=spec.getJSONArray("answers").objects()
        require(answers.map {it.getString("questionId")}==approval.questions.map {it.id}) // Actual one/two/N questions, no fixed count.
        val values=answers.associate {answer->
            val id=answer.getString("questionId");val text=answer.getString("text")
            require(text.startsWith(' ')&&text.endsWith(' ')&&'\n' in text&&text.isNotBlank()&&text.length<=512)
            require(text.contains(cfg.getString("caseId"))) // Own synthetic text, not user content or a prior QA input.
            val question=approval.questions.single {it.id==id};require(question.custom&&!question.secret)
            id to listOf(text)
        }
        val params=EasyWorkQuestionProjection.resolution(approval.threadId,approval.turnId,approval.id,"",values)
        val requestHash=easyHash(easyCanonical(JSONObject().put("target",target().json())
            .put("method","serverRequest/resolve").put("params",params)).toByteArray())
        val actionId=spec.getString("actionIntentId")
        val dedupe="question:${approval.turnId}:${spec.getString("toolCallId")}:resolved"
        save("answer-intent.json",JSONObject().put("caseActionIntentId",actionId).put("approvalId",approval.id).put("threadId",approval.threadId)
            .put("runId",approval.turnId).put("sessionId",spec.getString("sessionId")).put("toolCallId",spec.getString("toolCallId"))
            .put("expectedRequestHash",requestHash).put("serverDedupeKeyForIndependentLookup",dedupe)
            .put("wireCommandId",JSONObject.NULL).put("rawTransportReceipt",JSONObject.NULL).put("wireReceiptObservability","not-observable-through-production-UI")
            .put("answers",JSONArray(answers.map {
                val text=it.getString("text");JSONObject().put("questionId",it.getString("questionId"))
                    .put("utf16Length",text.length).put("utf8Bytes",text.toByteArray().size).put("sha256",hash(text.toByteArray()))
            })).put("maxQuestionSubmissions",1).put("maxAllowedAsyncResumptions",spec.getInt("maxAllowedAsyncResumptions")))
        render {
            ExecutionPanels(owned,autoPresentQuestions=false)
            if(owned.aiSharingRequest!=null)NativeSheetHost("ai-sharing",owned,null,
                owned::dismissAISharing,{}, {})
        }
        ui.onNodeWithTag("open-async-questions").performClick()
        val ids=main {pending().map {it.id}};repeat(ids.indexOf(approval.id)) {ui.onNodeWithTag("async-question-next").performClick()}
        answers.forEach {answer->
            val field=ui.onNodeWithTag("question-custom-${answer.getString("questionId")}")
            field.performScrollTo()
            val prior=field.fetchSemanticsNode().config[SemanticsProperties.EditableText].text
            if(prior.isNotEmpty()) {
                val known=checkNotNull(spec.optJSONObject("priorUnsubmittedDraftSHA256"))
                require(known.getString(answer.getString("questionId"))==hash(prior.toByteArray())) {
                    "Preserve any draft not independently identified as this task's unsubmitted synthetic text"
                }
            }
            field.performTextReplacement(answer.getString("text"))
            field.assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText,AnnotatedString(answer.getString("text"))))
        }
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        val sharing=main {AISharingScope(checkNotNull(owned.selectedClient.scope),
            com.agenthub.core.AISharingRules.model(owned.runtime?.settings?.model))}
        require(sharing.model==spec.getString("expectedConsentModel"))
        if(!main {owned.aiSharing.permits(sharing)}) {
            require(spec.getBoolean("allowInitialAISharingConsent"))
            save("ui-sharing-intent.json",JSONObject().put("caseActionIntentId",actionId)
                .put("model",sharing.model).put("localPermissionOnly",true).put("questionSubmissions",0))
            // The ordinary first click opens content-sharing permission and cannot submit.
            ui.onNodeWithTag("answer-question").performScrollTo().assertIsEnabled().performClick()
            waitFor {owned.aiSharingRequest!=null}
            main {
                val request=checkNotNull(owned.aiSharingRequest)
                require(request.targetKey==owned.targetKey&&request.scope.matches(sharing))
                require(!owned.isAnswering(approval)&&pending().any {it==approval})
            }
            try {
                clickStable("ai-sharing-allow")
                // Disposal of the actual product sheet and durable grant are required.
                // Window focus is diagnostic: this ComponentActivity also hosts the test IME.
                waitFor {owned.aiSharingRequest==null&&!owned.aiSharing.busy&&
                    !shown("sheet-ai-sharing")&&owned.aiSharing.permits(sharing)}
            } finally {
                save("ui-sharing-observation.json",JSONObject().put("caseActionIntentId",actionId)
                    .put("sharingRequestPresent",owned.aiSharingRequest!=null)
                    .put("busy",owned.aiSharing.busy).put("permitted",owned.aiSharing.permits(sharing))
                    .put("storageFailurePresent",owned.aiSharing.failure!=null)
                    .put("sheetPresent",shown("sheet-ai-sharing"))
                    .put("activityWindowFocused",ui.activity.window.decorView.hasWindowFocus()))
            }
            main {require(owned.aiSharing.permits(sharing)&&pending().any {it==approval})}
            answers.forEach {answer->
                ui.onNodeWithTag("question-custom-${answer.getString("questionId")}").performScrollTo()
                    .assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText,AnnotatedString(answer.getString("text"))))
            }
            save("ui-sharing-granted.json",JSONObject().put("caseActionIntentId",actionId)
                .put("productionConsentUi",true).put("noAutomaticSubmission",true).put("draftsPreserved",true))
        }
        main {require(pending().any {it==approval}&&owned.canAnswer(approval)&&owned.aiSharing.permits(sharing))}
        save("ui-submit-intent.json",JSONObject().put("caseActionIntentId",actionId).put("expectedRequestHash",requestHash).put("maximumClicks",1))
        var clickReturned=false;var answeringEnded=false
        try {
            clickStable("answer-question") // once, no retry loop
            clickReturned=true
            save("ui-submit-attempted.json",JSONObject().put("caseActionIntentId",actionId).put("clicks",1))
            waitFor {!owned.isAnswering(approval)&&
                (pending().none {it.id==approval.id}||owned.error!=null)};answeringEnded=true
        } finally {
            // Observed UI only: !isAnswering or pending disappearance never fabricates acceptance.
            save("answer-ui-observation.json",JSONObject().put("caseActionIntentId",actionId)
                .put("threadId",approval.threadId).put("runId",approval.turnId).put("sessionId",spec.getString("sessionId"))
                .put("toolCallId",spec.getString("toolCallId")).put("questionIds",JSONArray(approval.questions.map {it.id}))
                .put("clickReturned",clickReturned).put("answeringEndedObserved",answeringEnded).put("currentlyAnswering",owned.isAnswering(approval))
                .put("approvalStillPending",pending().any {it.id==approval.id}).put("uiErrorPresent",owned.error!=null)
                .put("sharingRequestPresent",owned.aiSharingRequest!=null).put("sharingPermitted",owned.aiSharing.permits(sharing))
                .put("sameTargetThread",owned.selectedThread==approval.threadId&&matches(owned.selectedClient))
                .put("currentRuntimeRunId",owned.runtime?.turnId).put("currentRuntimeState",owned.runtime?.state)
                .put("wireCommandId",JSONObject.NULL).put("rawTransportReceipt",JSONObject.NULL).put("transportAccepted",JSONObject.NULL)
                .put("wireReceiptObservability","not-observable-through-production-UI")
                .put("serverDedupeKeyForIndependentLookup",dedupe).put("desktopIndependentReconciliationRequired",true)
                .put("nativeDeclaresWholeF58Passed",false).put("unknownMustNeverResubmit",true))
        }
        require(clickReturned&&answeringEnded&&owned.error==null&&pending().none {it.id==approval.id}&&
            owned.selectedThread==approval.threadId&&matches(owned.selectedClient))
        // No refresh/answer retry loop or second submit. Root/R2 reads ORIGINAL thread/run/session/
        // toolCall/questionResponse and stable dedupe after unknown outcomes, before any next case.
    }
}
