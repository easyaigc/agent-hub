package com.agenthub.android

import androidx.compose.runtime.*
import com.agenthub.core.*
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

// Provider-neutral presentation data. Original IDs/content are retained for actions and recovery.
data class MobileMessage(val id: String, val role: String, val text: String, val status: String? = null,
    val kind: String? = null, val detail: String? = null, val runId: String? = null,
    val replyPhase: String? = null, val replyMessageId: String? = null, val files: List<String> = emptyList(),
    val attachments: List<MobileFile> = emptyList(), val blocks: String? = null)
enum class ConnectionPhase { CONNECTING, WAITING, FAILED }
data class MobileClient(val id: String, val name: String, val scope: TargetScope? = null,
    val endpoint: String? = null, val online: Boolean = false, val mode: String = "history",
    val isDemo: Boolean = endpoint == null && scope == null, val readerId: String? = null,
    val requiresReadTarget: Boolean = false, val codexDirect: PairedCodexConnection? = null,
    val connectionPhase: ConnectionPhase = ConnectionPhase.CONNECTING,
    val agentConnection: AgentConnection? = null,
    val storageIdentity: MobileStorageIdentity? = null) {
    val isConnecting get() = !isDemo && !online && connectionPhase != ConnectionPhase.FAILED
    val connectionLabel get() = if(online) "已连接" else if(isDemo) "离线 · 查看缓存" else when(connectionPhase) {
        ConnectionPhase.CONNECTING -> "正在连接…"
        ConnectionPhase.WAITING -> "正在重新连接…"
        ConnectionPhase.FAILED -> "连接未完成 · 查看缓存"
    }
    val sourceLabel get() = if (isDemo) "界面体验 · 本地示例" else when (mode) {
        "desktop-runtime" -> "原桌面连接"; "easywork-runtime" -> "EasyWork 连接"; "managed-runtime" -> "托管连接"; "shared-runtime" -> "共享连接"; else -> "历史连接"
    }
}
data class PendingAgentLink(val presentation: String=UUID.randomUUID().toString(),val payload: String)
data class DraftDestination(val key: String,val selection: Long)
internal data class CreationWaitContext(val key: String,val record: SavedOperation,val client: MobileClient,val selection: Long,val navigation: Long,val revision: Long)
data class ComposerActionContext(val key: String,val client: MobileClient,val threadId: String,val selection: Long,
    val navigation: Long,val connectionRevision: Long,val turnId: String?,val state: String,val action: String,val draftVersion: String?,
    val taskUserMessageId: String?,val taskRunId: String?)
data class SidebarDisplayState(val recentExpanded: Boolean=false,val projectsExpanded: Boolean=true,val expandedLists: Set<String> = emptySet(),
    val pinnedSectionExpanded: Boolean=true,val recentSectionExpanded: Boolean=true)
data class ChatManagementContext(val key: String,val client: MobileClient,val thread: MobileThread,val selection: Long,val revision: Long)
data class MobileChatSearchContext(val key: String,val client: MobileClient,val threadId: String,val selection: Long,val revision: Long)
data class MobileChatSearchSelection(val context: MobileChatSearchContext,val message: MobileMessage,val query: String,
    val history: List<MobileMessage>,val complete: Boolean,val token: String=UUID.randomUUID().toString())

/** One bounded projection, including full content so reused IDs cannot hide edits. */
internal class NativeTimelineCache {
    private data class Input(val target: String, val messages: List<MobileMessage>, val limit: Int,
        val run: String?, val running: Boolean, val focus: String?)
    private var input: Input? = null
    private var value: TimelineProjection? = null
    fun project(target: String, messages: List<MobileMessage>, limit: Int, run: String?, running: Boolean, focus: String?): TimelineProjection {
        val next=Input(target,messages,limit,run,running,focus)
        if(input==next) value?.let { return it }
        val values=messages.map { TimelineMessage(it.id,it.role,it.text,it.kind,it.status,it.runId,it.replyPhase,it.replyMessageId) }
        val result=MessageProjection.focus(MessageProjection.project(values,limit,run,running),values,focus)
        input=next.copy(messages=messages.toList());value=result
        return result
    }
}

/** Main-thread native state; public rules and projection come from the shared core. */
@Stable
class MobileSession(private val storage: NativeSessionStorage? = null, reader: HostReads? = null, commandProvider: CodexCommands? = null) {
    var voice by mutableStateOf<VoiceInputController?>(null)
        private set
    fun configureVoice(context: android.content.Context) {
        if(voice==null && storage!=null) voice=VoiceInputController(context.applicationContext,VoiceRecordingStore(context.applicationContext,storage)) { providers }
    }
    val canUseVoice get()=canCompose && foreground && !selectedClient.isDemo && transferStatus==null && !importing
    fun beginVoice() {
        val controller=voice ?: return
        if(!canUseVoice || controller.active) return
        val consent=requireSharing("codex-dictation") ?: return
        val original=VoiceDraft(selectedClient,consent,targetKey,version(targetKey),"voice-v1:"+voiceSHA((consent.key+"\u0000"+targetKey).toByteArray(Charsets.UTF_8)),selectedThread.takeIf { it.isNotEmpty() },text)
        controller.start(original,allowed={ foreground && targetKey==original.key && version(original.key)==original.version &&
            sameScope(selectedClient.scope,original.client.scope) && selectedClient.codexDirect?.fingerprint==original.certificate && aiSharing.permits(consent) },apply={ draft,transcript->
            if(foreground && targetKey==draft.key && version(draft.key)==draft.version && aiSharing.permits(draft.scope)) {
                val separator=if(draft.text.isEmpty() || draft.text.last().isWhitespace()) "" else "\n"
                text=draft.text+separator+transcript;true
            } else false
        })
    }
    val aiSharing=AISharingConsent(storage)
    var aiSharingRequest by mutableStateOf<AISharingRequest?>(null)
        private set
    private fun requireSharing(model: String? = null,original: String? = null,useCurrentModel: Boolean = true): AISharingScope? {
        val target=selectedClient.scope ?: run { error="电脑运行目标尚未就绪";return null }
        val captured=try { original?.let { AISharingScope.decode(JSONObject(it)) } ?: AISharingScope(target,
            AISharingRules.model(model ?: if(useCurrentModel) runtime?.settings?.model ?: selectedModel?.id else null)) }
        catch(e: Exception) { error="原共享许可范围无法核对，请查询原回执。";return null }
        if(!RuntimeIdentity.matches(target,captured.target)) { error="原输入的授权目标已变化，请查询原回执。";return null }
        if(aiSharing.permits(captured)) return captured
        aiSharingRequest=AISharingRequest(captured,targetKey,selectedClient.name,agentTitle,
            when(captured.model) { "codex-dictation"->"Codex 语音转写";"follow-computer"->"跟随电脑端配置";else->models.firstOrNull { it.id==captured.model }?.name ?: captured.model })
        return null
    }
    fun dismissAISharing() { if(!aiSharing.busy) aiSharingRequest=null }
    fun allowAISharing() {
        val request=aiSharingRequest ?: return
        if(aiSharing.busy || request.targetKey!=targetKey || !RuntimeIdentity.matches(request.scope.target,selectedClient.scope)) { dismissAISharing();return }
        tasks.launch {
            try { aiSharing.allow(request);if(aiSharingRequest===request) aiSharingRequest=null }
            catch(e: Exception) { error=aiSharing.failure }
        }
    }
    fun revokeAISharing(id: String) {
        if(aiSharing.busy) return
        tasks.launch { try { aiSharing.revoke(id) } catch(e: Exception) { error=aiSharing.failure } }
    }
    var discovery: CodexHostDiscovery? by mutableStateOf(null)
    var easyWorkDiscovery: EasyWorkDiscovery? by mutableStateOf(null)
    var connectionBusy by mutableStateOf(false)
    var projectBusy by mutableStateOf(false)
    var incomingEasyWorkLink: PendingAgentLink? by mutableStateOf(null)
    val easyWorkLogin=storage?.let(::EasyWorkLogin)
    private val easyWorkDeviceAuthorization=storage?.let(::EasyWorkDeviceAuthorization)
    private val easyWorkSessions=storage?.let { EasyWorkSessions(it,easyWorkLogin!!) }
    private val easyWorkAgent=easyWorkSessions?.let(::EasyWorkAgentClient)
    private val tasks = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    internal val desktopSuggestionReader=NativeDesktopSuggestionReader(tasks)
    private val suggestionReadAhead=NativeSuggestionReadAhead(tasks)
    private var suggestionNeedsFreshHistory by mutableStateOf(false)
    private val textRevisions=mutableStateMapOf<String,Long>()
    private val handledSuggestions=mutableStateMapOf<String,Set<String>>()
    private var suggestionUndo by mutableStateOf<MobileSuggestionUndo?>(null)
    private var suggestionSurfaceOwner: String?=null
    private var suggestionSurfaceNavigation: String?=null
    private var suggestionSurfaceInteractive=false
    private var suggestionReadsVisible=false
    private fun registry(reader: HostReads?,commands: CodexCommands?)=AgentRegistry(reader?:NativeHostReads { storage?.token(it).orEmpty() },commands?:NativeCodexCommands { storage?.token(it).orEmpty() }).apply { easyWorkAgent?.let { register("easywork",it,it) } }
    private var providers=registry(reader,commandProvider)
    private var reads: HostReads = providers
    private var commands: CodexCommands = providers
    private var execution = storage?.let { NativeCodexFlow(it,reads,commands).apply { confirmedReplyReads=::confirmedReplyReads } }
    private val freshWorkspaces=mutableStateMapOf<String,Boolean>()
    private val freshHistories=mutableStateMapOf<String,Boolean>()
    private val draftVersions=mutableStateMapOf<String,String>()
    private val draftFiles=mutableStateMapOf<String,List<LocalDraftFile>>()
    private val attachmentImportGenerations=mutableStateMapOf<String,String>()
    private val attachmentPreparation=LocalAttachmentPreparation(tasks,{ execution?.files },::currentAttachmentImport,
        { draftFiles[it].orEmpty().size },{ destination,file ->
            val old=draftFiles[destination.key].orEmpty()
            val same=old.firstOrNull { it.manifest.id==file.manifest.id }
            require(same==null||same==file) { "原附件清单已改变" }
            if(same==null) {
                draftFiles[destination.key]=old+file
                draftVersions[destination.key]=UUID.randomUUID().toString();persistMetadata()
            }
        },{ file -> draftFiles.values.any { values -> values.any { it.manifest==file.manifest } } })
    private val generation=mutableStateMapOf<String,GenerationSettings>()
    private val generationPreferences=mutableStateMapOf<String,GenerationSettings>()
    private val dismissedReceiptFailures=mutableStateMapOf<String,Set<String>>()
    private val modelErrors=mutableStateMapOf<String,String>()
    val modelError get()=modelErrors[clientKey]
    private val preferredModes=mutableStateMapOf<String,String>()
    private val newProjects=mutableStateMapOf<String,String?>()
    private val operations=mutableStateMapOf<String,List<SavedOperation>>()
    // Presentation while the immutable journal is being saved/authority is read.
    // This snapshot is never used to issue, retry, or restore a command.
    private val preparingCreationMessages=mutableStateMapOf<String,MobileMessage>()
    private var creationMessageCache: Pair<Triple<String,String,String>,MobileMessage>?=null
    private var pendingCreationWait by mutableStateOf<CreationWaitContext?>(null)
    private val busyTargets=mutableStateMapOf<String,String>()
    private val busyOwners=mutableMapOf<String,String>()
    private val busyCommands=mutableMapOf<String,String>()
    private val queueActionOwners=mutableStateMapOf<String,String>()
    private val replyRecordQueryOwners=mutableStateMapOf<Pair<String,String>,String>()
    private val replyRecordQueryErrors=mutableStateMapOf<Pair<String,String>,String>()
    private class StopOwner(val client: MobileClient,val thread: String,val turn: String,val selection: Long,val navigation: Long,val revision: Long)
    private val stoppingTargets=mutableStateMapOf<String,StopOwner>()
    private data class ApprovalAnswerKey(val target: String,val thread: String,val turn: String,val approval: String)
    private val answeringTargets=mutableStateMapOf<ApprovalAnswerKey,String>()
    private data class ForkNavigation(val id: String,val selection: Long,val draft: String)
    private val forkNavigation=mutableMapOf<String,ForkNavigation>()
    private val forkOptions=mutableStateMapOf<String,ThreadForkOptions>()
    private val forkOptionErrors=mutableStateMapOf<String,String>()
    private val forkOptionsLoading=mutableStateMapOf<String,Boolean>()
    private val forkRequests=mutableStateMapOf<String,String>()
    private val modelsByClient=mutableStateMapOf<String,List<CodexModel>>()
    private val observingOperations=mutableMapOf<String,Job>()
    private val connectionSuccesses=mutableMapOf<String,Long>()
    private val conversationSyncIssues=mutableStateMapOf<String,ConversationSyncIssue>()
    private val conversationSnapshotRevisions=mutableMapOf<String,Long>()
    private val usefulConversationProgress=ConversationUsefulProgress()
    private fun conversationProgressKey(key: String,client: MobileClient)="$key:$selectionRevision:${revisions[client.id] ?: 0}"
    internal val conversationSyncIssue: ConversationSyncIssue? get()=conversationSyncIssues[targetKey].takeUnless { selectedClient.isDemo || selectedThread.isEmpty() }
    private fun receivedConversationSnapshot(key: String,client: MobileClient,page: MobileHistory,proofIsFresh: Boolean=true) {
        receivePhoneReading(client,page,proofIsFresh)
        if(proofIsFresh&&key==targetKey&&page.runtimePresent)suggestionNeedsFreshHistory=false
        val state=page.synchronization?.state
        val historyFresh=state in listOf("history","live-window") && page.synchronization?.checkedAt!=null
        val unavailable=state=="unavailable" || client.mode=="desktop-runtime" && page.runtime?.canControl==false && page.runtime.state=="unknown"
        if(proofIsFresh && (historyFresh || !unavailable))conversationSnapshotRevisions[key]=(conversationSnapshotRevisions[key] ?: 0)+1
        usefulConversationProgress.accepted(conversationProgressKey(key,client),state in listOf("history","live-window"),page.synchronization?.checkedAt,unavailable,proofIsFresh)
        when {
            historyFresh->{
                val liveWindow=state=="live-window"
                conversationSyncIssues[key]=ConversationSyncIssue(if(liveWindow)
                    "当前进度正从电脑实时同步；完整桌面状态暂不可用，较早历史按需加载。"
                    else "已从电脑同步最新历史进度，正在重试实时连接。",true,liveWindow)
            }
            unavailable->{
                if(conversationSyncIssues[key]==null || conversationSyncIssues[key]?.historyFresh==true)
                    conversationSyncIssues[key]=ConversationSyncIssue("未能取得此对话的最新进度，当前显示缓存。正在自动重试。")
            }
            else->conversationSyncIssues.remove(key)
        }
    }
    private var eventJob: Job?=null
    private var eventReadOwner: Any?=null
    private var networkRecoveryJob: Job?=null
    private var networkReadPath: String?=null
    private var historyRecoveryJob: Job?=null
    private var historyRecoveryIdentity: Triple<String,Long,Long>?=null
    private var readyHistorySelection: Triple<String,Long,Long>?=null
    private var foreground=true
    val importing: Boolean get()=attachmentPreparation.preparing(targetKey,attachmentImportGenerations[targetKey])||documentPicker?.recovery?.preparing(targetKey,attachmentImportGenerations[targetKey])==true||photoPicker?.recovery?.preparing(targetKey,attachmentImportGenerations[targetKey])==true||cameraCapture?.recovery?.preparing(targetKey,attachmentImportGenerations[targetKey])==true
    internal val attachmentRecoveryBusy: Boolean get()=documentPicker?.recovery?.let { it.loading||it.busy }==true||photoPicker?.recovery?.let { it.loading||it.busy }==true||cameraCapture?.recovery?.let { it.loading||it.busy }==true
    internal val phoneReading=NativePhoneReadingStore(storage,tasks,::syncPhoneReadAcknowledgements)
    private var phoneReadingProof by mutableStateOf<NativeReadingProof?>(null)
    private val phoneReadingFingerprint=NativeLegacyReadingFingerprint()
    private var phoneReadingEpoch=0L
    private val phoneReadingAcknowledgementOwners=mutableMapOf<String,String>()
    private val phoneReadingAcknowledgements=mutableMapOf<String,Job>()
    private var phoneReadingUncovered by mutableStateOf(false)
    private var phoneReadingRetry by mutableStateOf(0L)
    private var attemptedPhoneReading: NativeVisiblePhoneReading?=null
    private var cachedPhoneReading: NativeVisiblePhoneReading?=null
    private var phoneWorkspaceHead: Pair<String,ThreadReadPosition?>?=null
    internal val phoneReadingError get()=phoneReading.failure
    internal val phoneUnreadCount get()=phoneReading.snapshot.count
    internal val phoneUnreadKnown get()=phoneReading.ready
    fun unreadConversationCount(client: MobileClient): Int? = if(client.isDemo)0 else if(!phoneReading.ready)null else client.scope?.let(phoneReading.snapshot::count)
    fun showsPhoneUnread(thread: MobileThread): Boolean = if(selectedClient.isDemo)thread.showsUnread else
        thread.state !in listOf("running","waiting-for-approval") && selectedClient.scope?.let {
            thread.hostId==selectedClient.id && phoneReading.snapshot.isUnread(it,thread.id)
        }==true
    internal fun setPhoneReadingSurface(uncovered: Boolean) {
        phoneReadingUncovered=uncovered;phoneReading.lifecycle.foreground(foreground);phoneReading.lifecycle.uncovered(uncovered)
        if(!uncovered) { phoneReading.lifecycle.leave();attemptedPhoneReading=null;cachedPhoneReading=null;phoneReadingFingerprint.clear() }
    }
    internal fun retryPhoneReading() { attemptedPhoneReading=null;phoneReadingRetry++;reconcilePhoneReading() }
    internal val visiblePhoneReading: NativeVisiblePhoneReading? get() {
        val client=selectedClient;val target=client.scope ?: return null
        if(selectedThread.isEmpty())return null
        // Earlier pages/cache can add display rows, but only an accepted latest network page supplies reading proof.
        val proof=phoneReadingProof?.takeIf { it.threadId==selectedThread && sameScope(it.target,target) } ?: return null
        if(!localReady || !foreground || !phoneReadingUncovered || client.isDemo || !client.online || selectedThread.isEmpty() ||
            historyLoading || freshWorkspaces[clientKey]!=true || freshHistories[targetKey]!=true ||
            readyHistorySelection!=Triple(targetKey,selectionRevision,revisions[client.id] ?: 0) ||
            proof.selection!=selectionRevision || proof.connectionRevision!=(revisions[client.id] ?: 0) || proof.epoch!=phoneReadingEpoch)return null
        val position=NativePhoneReadingRules.latest(listOf(currentChat?.readState?.latest,proof.state?.latest,
            phoneReading.heads[ThreadReadingIdentity.key(target,selectedThread)])) ?: return null
        val message=messages.lastOrNull { it.id==position.messageId && it.role=="assistant" &&
            it.status !in listOf("inProgress","running","streaming","unknown") } ?: return null
        val content=NativeVisiblePhoneReading(client,targetKey,selectedThread,selectionRevision,revisions[client.id] ?: 0,phoneReadingRetry,position,NativeReadingBody(message.id,message.text,message.status))
        cachedPhoneReading?.let { if(it==content)return it }
        if(!position.matches(message.id,message.text,message.status))return null
        cachedPhoneReading=content;return content
    }
    internal fun observeVisiblePhoneReading(content: NativeVisiblePhoneReading,visible: Boolean) {
        if(visiblePhoneReading!=content)return
        if(!visible) { phoneReading.lifecycle.leave();attemptedPhoneReading=null;return }
        if(attemptedPhoneReading==content)return
        val reader=content.client.readerId?.let(::nativeReaderId) ?: return
        attemptedPhoneReading=content;phoneReading.visible(content,reader)
    }
    private fun reconcilePhoneReading() {
        if(!localReady)return
        val head=targetKey to workspaces[clientKey]?.threads?.firstOrNull { it.id==selectedThread }?.readState?.latest
        if(phoneWorkspaceHead!=head) { phoneWorkspaceHead=head;phoneReading.lifecycle.leave();attemptedPhoneReading=null }
        val clients=realClients.filter { !it.isDemo && it.scope!=null }
        val values=clients.flatMap { client ->
            val workspace=workspaces[client.cacheKey(null)]
            if(workspace==null || !sameScope(workspace.scope,client.scope))emptyList() else workspace.threads.map { thread ->
                NativeReadingSnapshot(client.scope!!,thread.id,thread.readState?.latest,thread.unread,client.readerId?.let(::nativeReaderId),thread.readState)
            }
        }.toMutableList()
        val legacy=mutableListOf<NativeLegacyReading>()
        phoneReadingProof?.takeIf { proof -> clients.any { sameScope(it.scope,proof.target) } }?.let { proof ->
            val client=clients.first { sameScope(it.scope,proof.target) }
            val source=workspaces[client.cacheKey(null)]?.threads?.firstOrNull { it.id==proof.threadId }?.unread ?: false
            if(proof.state!=null)values.add(NativeReadingSnapshot(proof.target,proof.threadId,proof.state.latest,source,client.readerId?.let(::nativeReaderId),proof.state))
            else proof.legacy?.let { legacy.add(it.copy(sourceUnread=source)) }
        }
        phoneReading.reconcile(clients.mapNotNull { it.scope },values,legacy)
    }
    private fun receivePhoneReading(client: MobileClient,page: MobileHistory,fresh: Boolean) {
        if(client.id!=selectedClient.id || !sameScope(client.scope,selectedClient.scope) || page.threadId!=selectedThread)return
        // A replacement/cache snapshot cannot borrow the last latest-page proof, even if its body is identical.
        if(!fresh || page.scope==null || !sameScope(page.scope,client.scope) || page.historyWindowKind=="earlier") {
            releasePhoneReadingProof();return
        }
        val target=client.scope ?: return
        val key=ThreadReadingIdentity.key(target,page.threadId)
        phoneReading.focus(key)
        val legacy=if(page.readState==null)page.messages.lastOrNull { it.role=="assistant" && it.status !in listOf("inProgress","running","streaming","unknown") }?.let {
            NativeLegacyReading(target,page.threadId,it.id,phoneReadingFingerprint.version(key,it.id,it.text,it.status ?: "completed"),false)
        }else null
        val next=NativeReadingProof(target,page.threadId,page.readState,legacy,selectionRevision,revisions[client.id] ?: 0,phoneReadingEpoch)
        val changed=phoneReadingProof!=next
        val visibleBodyChanged=cachedPhoneReading?.body?.let { body -> page.messages.none(body::matches) }==true
        if(changed || visibleBodyChanged) { phoneReading.lifecycle.leave();attemptedPhoneReading=null;cachedPhoneReading=null }
        phoneReadingProof=next
        if(changed)reconcilePhoneReading() else syncPhoneReadAcknowledgements()
    }
    private fun releasePhoneReadingProof() {
        phoneReadingProof=null;phoneReadingFingerprint.clear();attemptedPhoneReading=null;cachedPhoneReading=null
        phoneReading.lifecycle.leave();phoneReading.focus(null)
    }
    private fun currentPhoneReadingClient(client: MobileClient,revision: Long,epoch: Long): Boolean = localReady && foreground && epoch==phoneReadingEpoch &&
        realClients.any { it.id==client.id && sameScope(it.scope,client.scope) && it.endpoint==client.endpoint &&
            it.codexDirect?.fingerprint==client.codexDirect?.fingerprint && it.readerId==client.readerId && it.agentConnection==client.agentConnection } &&
        (revisions[client.id] ?: 0)==revision && freshWorkspaces[client.cacheKey(null)]==true
    private fun syncPhoneReadAcknowledgements() {
        val ledger=phoneReading.ledger ?: return
        if(!localReady || !foreground)return
        tasks.launch {
            val pending=try { withContext(Dispatchers.IO) { ledger.pendingReads() } }catch(_: Exception) { return@launch }
            pending.forEach { request ->
                val client=realClients.firstOrNull { sameScope(it.scope,request.target) && it.online &&
                    it.readerId?.let(::nativeReaderId)==request.readerId && "threads:read-state" in workspaces[it.cacheKey(null)]?.capabilities.orEmpty() } ?: return@forEach
                val key=ThreadReadingIdentity.key(request.target,request.threadId)
                if(phoneReadingAcknowledgements[key]?.isActive==true)return@forEach
                val revision=revisions[client.id] ?: 0;val epoch=phoneReadingEpoch;val owner=UUID.randomUUID().toString()
                phoneReadingAcknowledgementOwners[key]=owner
                phoneReadingAcknowledgements[key]=tasks.launch {
                    try {
                        if(!currentPhoneReadingClient(client,revision,epoch))return@launch
                        val acknowledgement=commands.acknowledgeRead(client,request)
                        if(!currentPhoneReadingClient(client,revision,epoch))return@launch
                        val value=withContext(Dispatchers.IO) { ledger.acknowledge(request,acknowledgement) }
                        if(currentPhoneReadingClient(client,revision,epoch))phoneReading.publish(value)
                    }catch(cancel: CancellationException) { throw cancel }
                    catch(failure: CodexCommandError) {
                        if(failure.status==409 && currentPhoneReadingClient(client,revision,epoch)) {
                            val value=withContext(Dispatchers.IO) { ledger.settleStaleAcknowledgement(request) };phoneReading.publish(value)
                        }
                    }catch(_: Exception) { /* Keep the exact local read pending until a fresh authenticated reconciliation. */ }
                    finally {
                        if(phoneReadingAcknowledgementOwners[key]==owner) {
                            phoneReadingAcknowledgementOwners.remove(key);phoneReadingAcknowledgements.remove(key)
                            if(isActive && currentPhoneReadingClient(client,revision,epoch)) {
                                val newer=try { withContext(Dispatchers.IO) { ledger.pendingReads().any {
                                    ThreadReadingIdentity.key(it.target,it.threadId)==key && it.position!=request.position
                                } } }catch(_: Exception) { false }
                                if(newer)syncPhoneReadAcknowledgements()
                            }
                        }
                    }
                }
            }
        }
    }
    private val realClients = mutableStateListOf<MobileClient>()
    val clients: List<MobileClient> get() = realClients.toList() + if (demo) DemoMobileData.clients else emptyList()
    var demo by mutableStateOf(false)
    var selectedClient by mutableStateOf(DemoMobileData.clients.first())
        private set
    val hasClient get() = demo && selectedClient.isDemo || realClients.any { it.id == selectedClient.id }
    private val selectedThreads = mutableStateMapOf<String, String>()
    var selectedThread: String
        get() = selectedThreads[clientKey] ?: if (selectedClient.isDemo) "demo-chat" else ""
        set(value) { updateReadingSelection(value); persistMetadata() }
    private fun updateReadingSelection(thread: String) { selectedThreads[clientKey]=thread; synchronizeReadingPresentation() }
    var language by mutableStateOf("system")
        private set
    var appearance by mutableStateOf("system")
    var search by mutableStateOf("")
    private var errorRevision=0L
    private var errorMessage by mutableStateOf<String?>(null)
    var errorTitle by mutableStateOf(FailureCopy.defaultTitle)
        private set
    private var readErrorActive=false
    private var readErrorKind by mutableStateOf<com.agenthub.core.MobileConnectionFailureKind?>(null)
    private var localErrorProductKey by mutableStateOf<String?>(null)
    val errorProductKey get()=localErrorProductKey ?: readErrorKind?.let(FailureCopy::message)
    var error: String?
        get()=errorMessage
        set(value) { localErrorProductKey=null;errorRevision++;errorMessage=value;errorTitle=FailureCopy.defaultTitle;readErrorActive=false;readErrorKind=null }
    private fun readError(failure: Exception,prefix: String="",userInitiated: Boolean=false,fallback: String="读取失败") {
        if(error==null || readErrorActive || userInitiated) {
            val display=NativeOperationFailure.describe(failure,fallback)
            error=(if(display.kind==null) prefix else "")+display.message;errorTitle=display.title;readErrorKind=display.kind;readErrorActive=true
        }
    }
    private fun clearReadError(userInitiated: Boolean) { if(userInitiated || readErrorActive) error=null }
    var localRecoveryRequired by mutableStateOf(false)
        private set
    internal var localRecoveryAllowsAttachmentStaging by mutableStateOf(false)
        private set
    internal val canReceiveOriginalAttachmentResults get()=(!restoring&&!localRecoveryRequired)||localRecoveryAllowsAttachmentStaging
    private val localReady get()=!restoring&&!localRecoveryRequired
    private var restoreJob: Job?=null
    var restoring by mutableStateOf(storage != null)
        private set
    private val drafts = mutableStateMapOf<String, String>()
    private val histories = mutableStateMapOf<String, List<MobileMessage>>()
    private val historyPages = mutableStateMapOf<String, MobileHistory>()
    private val pagedSelections = mutableMapOf<String, Long>()
    // Network reads only, never restored from disk. Selection + connection revision
    // bound the V9 no-revision compatibility path to this actual reading generation.
    private val loadedHistorySelections = mutableMapOf<String, Pair<Long, Long>>()
    private val runs = mutableStateMapOf<String, String>()
    private val workspaces = mutableStateMapOf<String, MobileWorkspace>()
    private class WorkspaceReadOwner(val navigation: Long,val connectionRevision: Long,val managementRevision: Long)
    private val workspaceManagementRevisions=mutableStateMapOf<String,Long>()
    private val loadingTargets = mutableStateMapOf<String,WorkspaceReadOwner>()
    private class HistoryReadOwner(val selection: Long,val connectionRevision: Long)
    private val historyTargets = mutableStateMapOf<String,HistoryReadOwner>()
    private val expandedProjects = mutableStateMapOf<String, Set<String>>()
    private val sidebarStates = mutableStateMapOf<String, SidebarDisplayState>()
    private val revisions = mutableMapOf<String, Long>()
    private var selectionRevision = 0L
    private var clientNavigationRevision = 0L
    private var historyJob: Job? = null
    private var workspaceJob: Job? = null
    private var foregroundJob: Job? = null
    fun enterForeground() {
        tasks.launch { easyWorkLogin?.foreground() }
        foreground=true;phoneReading.lifecycle.foreground(true); freshWorkspaces.clear(); freshHistories.clear()
        realClients.toList().forEach { replaceClient(it.copy(online=false,connectionPhase=ConnectionPhase.CONNECTING)) }
        if(localReady) { refresh(); loadHistory() }
        foregroundJob?.cancel()
        foregroundJob=tasks.launch {
            while(isActive) {
                delay(15_000)
                if(hasClient && selectedClient.isConnecting) { refresh(); loadHistory() }
            }
        }
    }
    internal fun observeNetworkReadPath(signature: String,baseline: Boolean=false) {
        require(signature.isNotEmpty()&&signature.length<=512)
        val previous=networkReadPath;networkReadPath=signature
        if(!baseline&&previous!=null&&previous!=signature)recoverNetworkReads()
    }
    internal fun recoverNetworkReads() {
        if(!foreground||!hasClient||selectedClient.isDemo||incomingEasyWorkLink!=null||networkRecoveryJob?.isActive==true)return
        val client=selectedClient;val key=clientKey;val navigation=clientNavigationRevision;val revision=revisions[client.id] ?: 0
        networkRecoveryJob=tasks.launch {
            // Coalesce one platform path-update burst. Never cancel the parent
            // scope or a command/POST; original intent and operation ID survive.
            delay(200)
            if(!foreground||incomingEasyWorkLink!=null||clientKey!=key||clientNavigationRevision!=navigation||(revisions[client.id] ?: 0)!=revision)return@launch
            workspaceJob?.cancel();loadingTargets.remove(key)
            historyJob?.cancel();historyTargets.remove(targetKey)
            eventReadOwner=null;eventJob?.cancel();eventJob=null
            historyRecoveryJob?.cancel();historyRecoveryJob=null
            val progressKey=conversationProgressKey(targetKey,selectedClient)
            usefulConversationProgress.invalidateUsefulClock(progressKey)
            restartOperationReadObservers()
            refresh();if(usefulConversationProgress.lastUsefulAt(progressKey)==null)loadHistory();startEvents();loadOperations()
        }
    }
    fun leaveForeground() { phoneReading.lifecycle.foreground(false);attemptedPhoneReading=null;cachedPhoneReading=null;phoneReadingFingerprint.clear();phoneReadingAcknowledgements.values.forEach { it.cancel() };phoneReadingAcknowledgements.clear();phoneReadingAcknowledgementOwners.clear(); recentPhotos?.setActive(false);desktopSuggestionReader.clear();suggestionReadAhead.cancel();suggestionSurfaceInteractive=false;pendingCreationWait=null;voice?.suspendRecording();easyWorkLogin?.background();foreground=false;networkRecoveryJob?.cancel();networkRecoveryJob=null;eventReadOwner=null; foregroundJob?.cancel(); foregroundJob=null; eventJob?.cancel(); eventJob=null; historyRecoveryJob?.cancel(); historyRecoveryJob=null; observingOperations.values.forEach { it.cancel() }; observingOperations.clear(); freshWorkspaces.clear(); freshHistories.clear() }
    var visibleMessageLimit by mutableStateOf(ReadingPolicy.initialMessageLimit().toInt())
        private set
    var revealedEarlier by mutableStateOf(false)
        private set
    // SwiftUI resets this presentation only when the complete target changes,
    // independently of a new network navigation or connection revision.
    private var presentationTargetKey=targetKey
    private fun synchronizeReadingPresentation() {
        val key=targetKey
        if(presentationTargetKey!=key) { releasePhoneReadingProof();presentationTargetKey=key;visibleMessageLimit=ReadingPolicy.initialMessageLimit().toInt();revealedEarlier=false }
        phoneReading.focus(selectedClient.scope?.takeIf { selectedThread.isNotEmpty() }?.let { ThreadReadingIdentity.key(it,selectedThread) })
    }
    private val clientKey get() = selectedClient.cacheKey(null)
    val sidebarDisplay get() = sidebarStates[clientKey] ?: SidebarDisplayState()
    fun toggleSidebarRecent() { sidebarStates[clientKey]=sidebarDisplay.copy(recentExpanded=!sidebarDisplay.recentExpanded); persistMetadata() }
    fun toggleSidebarProjects() { sidebarStates[clientKey]=sidebarDisplay.copy(projectsExpanded=!sidebarDisplay.projectsExpanded); persistMetadata() }
    fun toggleSidebarPinnedSection() { sidebarStates[clientKey]=sidebarDisplay.copy(pinnedSectionExpanded=!sidebarDisplay.pinnedSectionExpanded);persistMetadata() }
    fun toggleSidebarRecentSection() { sidebarStates[clientKey]=sidebarDisplay.copy(recentSectionExpanded=!sidebarDisplay.recentSectionExpanded);persistMetadata() }
    fun toggleSidebarList(project: String) { val lists=sidebarDisplay.expandedLists; sidebarStates[clientKey]=sidebarDisplay.copy(expandedLists=if(project in lists) lists-project else lists+project); persistMetadata() }
    val targetKey: String get() = selectedClient.cacheKey(selectedThread.takeIf { it.isNotEmpty() }, if(selectedThread.isEmpty()) newProjects[clientKey] else null)
    private var locatedChatSearch by mutableStateOf<MobileChatSearchSelection?>(null)
    private var pendingChatSearch by mutableStateOf<MobileChatSearchSelection?>(null)
    val chatSearchJump get()=locatedChatSearch?.takeIf { chatSearchCurrent(it.context) }
    val pendingChatSearchToken get()=pendingChatSearch?.token
    val canSearchHistory get()=hasClient&&selectedThread.isNotEmpty()&&!selectedThread.startsWith("draft-")
    fun captureChatSearch(): MobileChatSearchContext? = if(!canSearchHistory)null else
        MobileChatSearchContext(targetKey,selectedClient,selectedThread,selectionRevision,revisions[selectedClient.id] ?: 0)
    fun chatSearchCurrent(context: MobileChatSearchContext): Boolean = targetKey==context.key&&selectionRevision==context.selection&&
        (revisions[context.client.id] ?: 0)==context.revision&&selectedClient.endpoint==context.client.endpoint&&
        (selectedClient.scope==null&&context.client.scope==null||sameScope(selectedClient.scope,context.client.scope))
    suspend fun completeChatSearchHistory(context: MobileChatSearchContext): List<MobileMessage> {
        if(!chatSearchCurrent(context))throw CancellationException()
        if(context.client.isDemo)return messages
        var cursor: String?=null
        var seen=emptyList<String>()
        var collected=emptyList<MobileMessage>()
        do {
            currentCoroutineContext().ensureActive()
            val page=reads.searchHistory(context.client,context.threadId,cursor)
            if(!chatSearchCurrent(context))throw CancellationException()
            require(page.hostId==context.client.id&&page.threadId==context.threadId&&
                (!context.client.requiresReadTarget||page.scope!=null)&&
                (context.client.scope==null||page.scope==null||sameScope(context.client.scope,page.scope))) { "电脑或对话身份不匹配" }
            val order=ChatSearchRules.page(collected.map { it.id },page.messages.map { it.id },page.nextCursor,seen)
            require(order.valid) { "电脑返回的历史分页无效" }
            val values=(page.messages+collected).distinctBy { it.id }.associateBy { it.id }
            collected=order.messageIds.map(values::getValue);seen=order.seenCursors;cursor=page.nextCursor
        } while(cursor!=null)
        return collected
    }
    fun prepareChatSearchSelection(context: MobileChatSearchContext,message: MobileMessage,query: String,
        history: List<MobileMessage>,complete: Boolean) {
        if(chatSearchCurrent(context)&&history.any { it.id==message.id }&&ChatSearchRules.query(query).isNotEmpty())
            pendingChatSearch=MobileChatSearchSelection(context,message,ChatSearchRules.query(query),history.toList(),complete)
    }
    /** Apply after native sheet dismissal. Search data never adopts execution/runtime state. */
    fun applyPendingChatSearch() {
        val selection=pendingChatSearch ?: return
        pendingChatSearch=null
        if(!chatSearchCurrent(selection.context))return
        val context=selection.context;val ids=selection.history.map { it.id }.toSet()
        val merged=selection.history+messages.filter { it.id !in ids }
        histories[context.key]=merged
        val page=historyPages[context.key] ?: run {
            val wire=JSONObject().put("hostId",context.client.id).put("threadId",context.threadId).put("messages",JSONArray())
            context.client.scope?.let { wire.put("readTarget",CodexCommandCodec.target(it)) }
            MobileHistory(context.client.id,context.threadId,emptyList(),null,context.client.scope,"unknown",null,wire.toString())
        }
        val stored=if(selection.complete)page.copy(nextCursor=null,wire=JSONObject(page.wire).put("nextCursor",JSONObject.NULL).toString()) else page
        historyPages[context.key]=stored
        persistHistory(context.key,context.client,context.threadId,stored,merged)
        pagedSelections[context.key]=context.selection
        loadedHistorySelections[context.key]=context.selection to context.revision
        val index=merged.indexOfFirst { it.id==selection.message.id }
        if(index>=0)visibleMessageLimit=maxOf(visibleMessageLimit,merged.size-index)
        revealedEarlier=true;locatedChatSearch=selection
    }
    data class WorkspaceArchiveContext(val client: MobileClient,val key: String,val navigation: Long,val revision: Long)
    private val workspaceArchiveBusy=mutableStateMapOf<String,String>()
    val archiveBatchRecords get()=operationRecords.filter { it.kind=="workspace-archive" }.reversed()
    val archiveBatchBusy get()=workspaceArchiveBusy.containsKey(clientKey)
    val supportsBatchArchive get()=!selectedClient.isDemo&&"threads:archive:unloaded" in workspace?.capabilities.orEmpty()
    val workspaceArchivePendingIds get()=workspaceArchivePending()
    private fun workspaceArchivePending(excluding: String? = null)=archiveBatchRecords.filter { it.id!=excluding }.flatMap { record ->
        val request=WorkspaceArchiveCodec.request(JSONObject(record.intent))
        if(record.result==null) { if(record.phase in listOf("ready","submitting","uncertain","outcome-unknown"))request.ids else emptyList() }
        else WorkspaceArchiveCodec.receipt(JSONObject(record.result)).items.filter { com.agenthub.core.WorkspaceArchiveRules.pending(it.state) }.map { it.id }
    }.toSet()
    fun captureWorkspaceArchive()=WorkspaceArchiveContext(selectedClient,clientKey,clientNavigationRevision,revisions[selectedClient.id] ?: 0)
    private fun archiveContextCurrent(context: WorkspaceArchiveContext)=foreground&&clientKey==context.key&&clientNavigationRevision==context.navigation&&
        (revisions[selectedClient.id] ?: 0)==context.revision&&selectedClient.endpoint==context.client.endpoint&&sameScope(selectedClient.scope,context.client.scope)
    fun canBatchArchive(thread: MobileThread)=localReady&&WorkspaceArchiveRules.eligible(
        thread.hostId==selectedClient.id&&threads.any { it.id==thread.id&&it.hostId==thread.hostId },thread.archived==true,
        chatActionBusy.containsKey(selectedClient.cacheKey(thread.id)),pendingChatActions(thread.id).isNotEmpty(),thread.id in workspaceArchivePendingIds)
    fun archiveWorkspace(context: WorkspaceArchiveContext,ids: List<String>): Job? {
        val flow=execution ?: return null
        if(!archiveContextCurrent(context)||!selectedClient.online||!supportsBatchArchive||archiveBatchBusy||ids.isEmpty()||ids.size>100||ids.distinct().size!=ids.size||
            ids.any { id->threads.none { it.id==id&&canBatchArchive(it) } })return null
        val client=context.client;val request=WorkspaceArchiveRequest(client.id,client.scope ?: return null,UUID.randomUUID().toString(),ids.toList())
        val labels=threads.filter { it.id in ids }.associate { it.id to it.title };workspaceArchiveBusy[context.key]=request.operationId
        return tasks.launch {
            try {
                val result=NativeWorkspaceArchiveFlow(flow.journal,commands).submit(client,request,labels) {
                    archiveContextCurrent(context)&&selectedClient.online&&supportsBatchArchive&&ids.all { id->threads.any { it.id==id&&it.archived!=true } }&&
                        ids.none { it in workspaceArchivePending(request.operationId)||pendingChatActions(it).isNotEmpty()||chatActionBusy.containsKey(selectedClient.cacheKey(it)) }
                }
                acceptWorkspaceArchive(client,result.record)
            } catch(cancel: CancellationException) { throw cancel }
            catch(failure: Exception) { if(archiveContextCurrent(context))error=failure.message ?: "批量归档结果待核对" }
            finally { if(workspaceArchiveBusy[context.key]==request.operationId)workspaceArchiveBusy.remove(context.key);loadOperations() }
        }
    }
    fun queryWorkspaceArchive(record: SavedOperation): Job? {
        val flow=execution ?: return null;val context=captureWorkspaceArchive();val client=context.client
        if(record.kind!="workspace-archive"||archiveBatchBusy||archiveBatchRecords.none { it.id==record.id&&it.target==record.target })return null
        workspaceArchiveBusy[context.key]=record.id
        return tasks.launch {
            try { acceptWorkspaceArchive(client,NativeWorkspaceArchiveFlow(flow.journal,commands).query(client,record).record) }
            catch(cancel: CancellationException) { throw cancel }
            catch(failure: Exception) { if(archiveContextCurrent(context))error=failure.message ?: "批量归档结果仍未确认" }
            finally { if(workspaceArchiveBusy[context.key]==record.id)workspaceArchiveBusy.remove(context.key);loadOperations() }
        }
    }
    private suspend fun acceptWorkspaceArchive(client: MobileClient,record: SavedOperation) {
        val flow=execution ?: return;operations[record.target]=flow.journal.records(record.target)
        if(selectedClient.id!=client.id||selectedClient.endpoint!=client.endpoint||!sameScope(selectedClient.scope,client.scope))return
        val receipt=record.result?.let { WorkspaceArchiveCodec.receipt(JSONObject(it)) }
        val completed=receipt?.items.orEmpty().filter { it.state=="completed" }.map { it.id }.toSet()
        if(completed.isNotEmpty()) {
            workspaceManagementRevisions[clientKey]=(workspaceManagementRevisions[clientKey] ?: 0)+1
            workspaces[clientKey]?.let { prior ->
                val changed=prior.copy(threads=prior.threads.map { if(it.id in completed)it.copy(archived=true)else it },pinnedThreadIds=prior.pinnedThreadIds?.filterNot { it in completed })
                workspaces[clientKey]=changed.copy(wire=encodeWorkspace(changed).toString())
            }
            if(selectedThread in completed) { selectionRevision++;selectedThread="";eventReadOwner=null;eventJob?.cancel();eventJob=null }
            persistMetadata();refresh()
        }
    }
    private val chatActionBusy=mutableStateMapOf<String,String>()
    val supportsChatManagement get()=!selectedClient.isDemo&&"threads:manage" in workspace?.capabilities.orEmpty()
    val currentChat get()=threads.firstOrNull { it.id==selectedThread&&it.hostId==selectedClient.id }
    val currentChatPinned get()=selectedThread in workspace?.pinnedThreadIds.orEmpty()
    val chatActionRecords get()=operationRecords.filter { it.kind=="chat-action" }
    internal fun chatActionRecordDescription(record: SavedOperation,t: NativeL10n): String {
        val request=ChatActionCodec.request(JSONObject(record.intent))
        return t.tr("查询聊天操作记录，%@，对话 %@，操作 %@",chatActionRecordLabel(record,t),request.threadId,request.operationId)
    }
    internal fun chatActionRecordLabel(record: SavedOperation,t: NativeL10n): String {
        val request=ChatActionCodec.request(JSONObject(record.intent))
        val action=when(request.action) { "pin"->"置顶";"unpin"->"取消置顶";"archive"->"归档";"unarchive"->"恢复对话";else->"删除" }
        val title=threads.firstOrNull { it.hostId==request.hostId&&it.id==request.threadId }?.title ?: request.threadId
        return t.tr(action)+" · "+request.operationId.take(8)+" · "+title
    }
    fun pendingChatActions(threadId: String? = null)=chatActionRecords.filter {
        ChatManagementRules.pending(it.phase)&&(threadId==null||JSONObject(it.intent).getString("threadId")==threadId)
    }
    private fun managementAllowed(thread: MobileThread,ownId: String? = null): Boolean {
        val key=selectedClient.cacheKey(thread.id)
        return localReady&&execution!=null&&selectedClient.scope!=null&&ChatManagementRules.canManage(selectedClient.isDemo,selectedClient.online,
            supportsChatManagement,thread.hostId==selectedClient.id&&threads.any { it.id==thread.id&&it.hostId==thread.hostId },
            archiveBatchBusy||chatActionBusy[key]?.let { it!=ownId }==true,pendingChatActions(thread.id).any { it.id!=ownId }||workspaceArchivePendingIds.contains(thread.id))
    }
    fun canManageChat(thread: MobileThread?)=thread!=null&&managementAllowed(thread)
    fun captureChatManagement(): ChatManagementContext? {
        val thread=currentChat ?: return null
        if(!canManageChat(thread))return null
        return ChatManagementContext(targetKey,selectedClient,thread,selectionRevision,revisions[selectedClient.id] ?: 0)
    }
    private fun managementCurrent(context: ChatManagementContext)=targetKey==context.key&&selectionRevision==context.selection&&
        (revisions[context.client.id] ?: 0)==context.revision&&selectedClient.endpoint==context.client.endpoint&&
        sameScope(selectedClient.scope,context.client.scope)
    internal fun chatTaskStatus(thread: MobileThread,t: NativeL10n): String {
        val selected=thread.hostId==selectedClient.id&&thread.id==selectedThread
        val state=if(selected)runtime?.state ?: thread.state else thread.state
        val unconfirmed=selected&&(loadedHistorySelections[targetKey]!=(selectionRevision to (revisions[selectedClient.id] ?: 0))||conversationSyncIssue!=null)
        val status=ChatManagementRules.taskStatus(state,thread.lastTurnStatus,thread.archived==true,selectedClient.online,unconfirmed)
        val text=when(status.code) { "running"->"执行中";"approval"->"等待审批";"failed"->"执行失败";"stopped"->"已停止";"completed"->"已完成";"idle"->"空闲";else->"状态未确认" }
        return if(status.lastKnown)t.tr("上次状态：%@",t.tr(text)) else t.tr(text)
    }
    internal fun chatLifecycleStatus(thread: MobileThread,t: NativeL10n)=t.tr("%@ · 任务状态：%@",t.tr(if(thread.archived==true)"已归档" else "未归档"),chatTaskStatus(thread,t))
    fun manageChat(action: String,context: ChatManagementContext? = captureChatManagement()) {
        val captured=context ?: return;val flow=execution ?: return
        if(!managementCurrent(captured)||!managementAllowed(captured.thread)||action !in listOf("pin","unpin","archive","unarchive","delete")||
            action in listOf("pin","unpin")&&currentChat?.archived==true)return
        val id=UUID.randomUUID().toString();chatActionBusy[captured.key]=id
        tasks.launch {
            try {
                val request=ChatActionRequest(captured.client.id,captured.client.scope!!,captured.thread.id,id,action)
                val result=NativeChatActionFlow(flow.journal,commands).submit(captured.client,request,captured.key,captured.selection.toString()) {
                    foreground&&managementCurrent(captured)&&managementAllowed(captured.thread,id)
                }
                acceptChatAction(captured.client,result.record,captured)
            } catch(cancel: CancellationException) { throw cancel }
            catch(failure: Exception) { if(managementCurrent(captured))error=failure.message ?: "操作结果待确认，请查询原记录" }
            finally { if(chatActionBusy[captured.key]==id)chatActionBusy.remove(captured.key);loadOperations() }
        }
    }
    fun queryChatAction(record: SavedOperation): kotlinx.coroutines.Job? {
        val flow=execution ?: return null;val client=selectedClient
        if(record.kind!="chat-action"||chatActionRecords.none { it.id==record.id&&it.target==record.target })return null
        val request=ChatActionCodec.request(JSONObject(record.intent));val key=client.cacheKey(request.threadId)
        val observer=currentChat?.let { ChatManagementContext(targetKey,client,it,selectionRevision,revisions[client.id] ?: 0) }
        if(chatActionBusy.containsKey(key))return null
        chatActionBusy[key]=record.id
        return tasks.launch {
            try { acceptChatAction(client,NativeChatActionFlow(flow.journal,commands).query(client,record).record,observer) }
            catch(cancel: CancellationException) { throw cancel }
            catch(failure: Exception) { if(observer!=null&&managementCurrent(observer)&&targetKey==record.sourceKey)error=failure.message ?: "原操作结果仍未确认" }
            finally { if(chatActionBusy[key]==record.id)chatActionBusy.remove(key);loadOperations() }
        }
    }
    fun endChatActionWaiting(record: SavedOperation) {
        val flow=execution ?: return
        if(record.kind!="chat-action"||chatActionRecords.none { it.id==record.id&&it.target==record.target })return
        tasks.launch { try {
            flow.journal.detach(record.target,record.id)
            operations[record.target]=flow.journal.records(record.target)
        } catch(failure: Exception) { if(targetKey==record.sourceKey)error=failure.message ?: "未能保存结束等待，原记录保留" } }
    }
    private suspend fun acceptChatAction(client: MobileClient,record: SavedOperation,observer: ChatManagementContext?=null) {
        val flow=execution ?: return
        operations[record.target]=flow.journal.records(record.target)
        val request=ChatActionCodec.request(JSONObject(record.intent))
        if(selectedClient.id!=client.id||selectedClient.endpoint!=client.endpoint||!sameScope(selectedClient.scope,request.target))return
        if(record.phase=="confirmed") {
            workspaceManagementRevisions[clientKey]=(workspaceManagementRevisions[clientKey] ?: 0)+1
            workspaces[clientKey]?.let { prior ->
                val threads=if(request.action=="delete")prior.threads.filterNot { it.id==request.threadId } else prior.threads.map {
                    if(it.id==request.threadId&&request.action in listOf("archive","unarchive"))it.copy(archived=request.action=="archive") else it
                }
                val changed=prior.copy(threads=threads)
                workspaces[clientKey]=changed.copy(wire=encodeWorkspace(changed).toString())
            }
            if(request.action=="delete"&&selectedThread==request.threadId&&targetKey==record.sourceKey) {
                selectionRevision++;selectedThread="";eventReadOwner=null;eventJob?.cancel();eventJob=null
            }
            persistMetadata();refresh()
        } else if(record.error!=null&&observer!=null&&managementCurrent(observer)&&targetKey==record.sourceKey)error=record.error
    }
    fun runningConversationCount(client: MobileClient): Int? {
        val snapshot=workspaces[client.cacheKey(null)]
        val conversations=if(client.isDemo && demo) DemoMobileData.threads else snapshot?.threads.orEmpty()
        return WorkspaceSidebarRules.runningCount(client.id,client.online,client.isDemo,snapshot!=null,snapshot?.mode,
            conversations.map { ConversationActivity(it.id,it.hostId,it.state) })
    }
    val workspace get() = workspaces[clientKey]
    val projects get() = if (selectedClient.isDemo && demo) DemoMobileData.projects.filter { it.hostId == selectedClient.id } else workspace?.projects.orEmpty()
    val threads get() = if (selectedClient.isDemo && demo) DemoMobileData.threads.filter { it.hostId == selectedClient.id } else workspace?.threads.orEmpty()
    val loading get() = loadingTargets[clientKey]?.let { it.navigation==clientNavigationRevision && it.connectionRevision==(revisions[selectedClient.id] ?: 0)&&it.managementRevision==(workspaceManagementRevisions[clientKey] ?: 0) }==true
    val historyLoading get() = historyTargets[targetKey]?.let { it.selection==selectionRevision && it.connectionRevision==(revisions[selectedClient.id] ?: 0) }==true
    val historyCursor get() = historyPages[targetKey]?.nextCursor
    val sourceLabel get()=selectedClient.sourceLabel
    val selectedTitle get() = threads.firstOrNull { it.id == selectedThread }?.title ?: "新聊天"
    var runtimeState: String
        get() = runs[targetKey] ?: historyPages[targetKey]?.state ?: "idle"
        set(value) { runs[targetKey] = value }
    var running: Boolean
        get() = if(selectedClient.isDemo) runtimeState=="running" else SessionPolicies.running(runtime?.turnId,runtime?.state)
        set(value) { runtimeState = if (value) "running" else "idle" }
    var text: String
        get() = drafts[targetKey].orEmpty()
        set(value) {
            val key=targetKey
            if(drafts[key]==value)return
            if(suggestionUndo?.applied?.targetKey==key)suggestionUndo=null
            textRevisions[key]=(textRevisions[key] ?: 0)+1
            if(value.isEmpty())handledSuggestions.remove(key)
            drafts[key]=value;draftVersions[key]=UUID.randomUUID().toString();persistMetadata()
        }
    val messages: List<MobileMessage> get() = histories[targetKey] ?: if (selectedClient.isDemo && demo && selectedThread == "demo-chat") DemoMobileData.messages else emptyList()
    val runtime get() = historyPages[targetKey]?.runtime
    internal val suggestionNavigation get()="$clientNavigationRevision:$selectionRevision:${revisions[selectedClient.id] ?: 0}"
    private fun suggestionCandidate(online: Boolean): SuggestionScope? {
        val client=selectedClient;val workspace=workspace ?: return null;val target=workspace.scope ?: return null
        val runtime=runtime ?: return null;val source=runtime.suggestionSourceTurnId?.takeIf { it.isNotEmpty() } ?: return null
        if(!foreground||!localReady||client.isDemo||incomingEasyWorkLink!=null||selectedThread.isEmpty()||
            online&&!client.online||!RuntimeIdentity.matches(target,client.scope)||runtime.state!="idle"||runtime.canControl!=true)return null
        val displayed="desktop:suggestions:displayed:v1" in workspace.capabilities
        val codex=workspace.mode=="desktop-runtime"&&target.agentId=="codex"&&
            (displayed||"desktop:suggestions:native-version:v1" in workspace.capabilities)
        val easyWork=workspace.mode=="easywork-runtime"&&target.agentId=="easywork"&&
            "desktop:suggestions:runtime-version:v1" in workspace.capabilities
        if(!codex&&!easyWork)return null
        return SuggestionScope(target,selectedThread,source,suggestionNavigation,
            "${client.endpoint.orEmpty()}:${client.codexDirect?.fingerprint.orEmpty()}",codex&&displayed,codex&&!displayed,easyWork)
    }
    internal val nextSuggestionScope: SuggestionScope? get() = suggestionCandidate(true)?.takeIf {
        !suggestionNeedsFreshHistory&&readyHistorySelection==Triple(targetKey,selectionRevision,revisions[selectedClient.id] ?: 0)&&
            freshHistories[targetKey]==true
    }
    internal val suggestionPresentationScope: SuggestionScope? get() {
        nextSuggestionScope?.let { return it }
        if(selectedClient.connectionPhase==ConnectionPhase.FAILED||
            !(suggestionNeedsFreshHistory||selectedClient.connectionPhase==ConnectionPhase.WAITING)||
            readyHistorySelection!=Triple(targetKey,selectionRevision,revisions[selectedClient.id] ?: 0))return null
        return suggestionCandidate(false)?.takeIf { desktopSuggestionReader.visible(it)!=null }
    }
    internal val suggestionDraft get()=SuggestionDraft(targetKey,text,(textRevisions[targetKey] ?: 0).toString())
    internal val availableSuggestion: MobileDesktopSuggestion? get() {
        val scope=suggestionPresentationScope ?: return null
        return desktopSuggestionReader.visible(scope)?.takeUnless {
            DesktopSuggestionRules.handledVersionKey(targetKey,scope.sourceTurnId,it.sharedValue(false)) in handledSuggestions[targetKey].orEmpty()
        }
    }
    internal fun claimSuggestionSurface(owner: String,navigation: String=suggestionNavigation) {
        if(navigation!=suggestionNavigation)return
        if(suggestionSurfaceOwner==owner)return
        suggestionSurfaceOwner=owner;suggestionSurfaceNavigation=navigation;suggestionSurfaceInteractive=false;suggestionReadsVisible=false
    }
    internal fun releaseSuggestionSurface(owner: String) {
        if(suggestionSurfaceOwner!=owner)return
        val navigation=suggestionSurfaceNavigation
        suggestionSurfaceOwner=null;suggestionSurfaceInteractive=false;suggestionReadsVisible=false
        if(navigation==suggestionNavigation)desktopSuggestionReader.suspendKeeping(suggestionPresentationScope)
        else if(desktopSuggestionReader.scope?.navigation==navigation)desktopSuggestionReader.clear()
        if(navigation!=null)suggestionReadAhead.cancel(navigation)
    }
    internal fun reconcileSuggestionSurface(owner: String,interactive: Boolean,previewOpen: Boolean) {
        if(suggestionSurfaceOwner!=owner||suggestionSurfaceNavigation!=suggestionNavigation)return
        suggestionSurfaceInteractive=interactive&&foreground&&voice?.active!=true
        val scope=suggestionPresentationScope;val client=selectedClient
        val enabled=suggestionSurfaceInteractive&&!previewOpen&&nextSuggestionScope!=null
        suggestionReadsVisible=suggestionSurfaceInteractive&&!previewOpen
        if(!suggestionReadsVisible||scope==null&&suggestionCandidate(false)==null)suggestionReadAhead.cancel()
        desktopSuggestionReader.reconcile(scope,enabled) {
            checkNotNull(scope)
            suggestionReadAhead.read(scope) { reads.nextTurnSuggestion(client,scope) }
        }
    }
    private fun prepareSuggestionRead() {
        if(suggestionSurfaceOwner!=null&&!suggestionReadsVisible)return
        val scope=suggestionCandidate(false) ?: return;val client=selectedClient
        suggestionReadAhead.prepare(scope,{ expected,response ->
            if(DesktopSuggestionRules.sameScope(nextSuggestionScope,expected))desktopSuggestionReader.seed(response,expected)
        }) { reads.nextTurnSuggestion(client,scope) }
        if(DesktopSuggestionRules.sameScope(nextSuggestionScope,scope))
            suggestionReadAhead.completed(scope)?.let { desktopSuggestionReader.seed(it,scope) }
    }
    internal fun suggestionIntent(): MobileSuggestionAction? {
        if(!suggestionSurfaceInteractive||suggestionSurfaceNavigation!=suggestionNavigation)return null
        val scope=nextSuggestionScope ?: return null;val value=availableSuggestion ?: return null
        return MobileSuggestionAction(scope,value,suggestionDraft)
    }
    internal fun suggestionActionCurrent(action: MobileSuggestionAction): Boolean =
        foreground&&suggestionSurfaceInteractive&&suggestionSurfaceNavigation==suggestionNavigation&&voice?.active!=true&&
            DesktopSuggestionRules.proposedText("replace",action.scope,nextSuggestionScope,
                action.suggestion.sharedValue(false),availableSuggestion?.sharedValue(),action.draft,suggestionDraft,
                System.currentTimeMillis().toDouble(),availableSuggestion?.actualTextSha256.orEmpty())!=null
    internal fun canApplySuggestion(action: MobileSuggestionAction): Boolean =
        suggestionActionCurrent(action)&&canCompose&&pendingRecords.isEmpty()&&transferStatus==null&&!importing
    internal fun adoptSuggestion(action: MobileSuggestionAction,mode: String): Boolean {
        if(!canApplySuggestion(action))return false
        val value=availableSuggestion ?: return false
        val proposed=DesktopSuggestionRules.proposedText(mode,action.scope,nextSuggestionScope,action.suggestion.sharedValue(false),
            value.sharedValue(),action.draft,suggestionDraft,System.currentTimeMillis().toDouble(),value.actualTextSha256) ?: return false
        text=proposed
        handledSuggestions[targetKey]=handledSuggestions[targetKey].orEmpty()+action.handledKey
        suggestionUndo=MobileSuggestionUndo(action.draft.text,suggestionDraft,action.handledKey)
        return true
    }
    internal fun ignoreSuggestion(action: MobileSuggestionAction): Boolean {
        if(!canApplySuggestion(action))return false
        handledSuggestions[targetKey]=handledSuggestions[targetKey].orEmpty()+action.handledKey;return true
    }
    internal val validSuggestionUndo get()=suggestionUndo?.takeIf {
        DesktopSuggestionRules.restoredText(it.original,it.applied,suggestionDraft)!=null
    }
    internal fun undoSuggestion(undo: MobileSuggestionUndo): Boolean {
        if(!foreground||validSuggestionUndo!==undo)return false
        val restored=DesktopSuggestionRules.restoredText(undo.original,undo.applied,suggestionDraft) ?: return false
        text=restored;handledSuggestions[targetKey]=handledSuggestions[targetKey].orEmpty()-undo.handledKey;suggestionUndo=null;return true
    }
    val attachments get() = draftFiles[targetKey].orEmpty()
    val generationSettings get() = generation[targetKey] ?: GenerationSettings()
    val models get() = modelsByClient[clientKey].orEmpty()
    val selectedModel get() = models.firstOrNull { it.id==(generationSettings.model ?: runtime?.settings?.model) }
    val modelLabel get() = selectedModel?.name ?: generationSettings.model ?: runtime?.settings?.model ?: "电脑默认"
    val selectedEffort get() = generationSettings.effort ?: runtime?.settings?.effort
    val generationDisplay get() = SessionPolicies.generationDisplay(selectedThread.isNotEmpty(),
        generationSettings.model,generationSettings.effort,runtime?.settings?.model,runtime?.settings?.effort)
    val displayedModelLabel get() = generationDisplay.model.let { id -> models.firstOrNull { it.id==id }?.name ?: id }
        ?: if(selectedThread.isEmpty()) "跟随电脑" else "同步中"
    val displayedEffort get() = generationDisplay.effort
    val selectedSpeed get() = generationSettings.speed ?: runtime?.settings?.speed ?: selectedModel?.defaultSpeed
    val selectedPermission get() = generationSettings.permissionMode ?: runtime?.settings?.permissionMode
    val permissionOptions get()=commands.permissionOptions(selectedClient)
    val agentTitle get()=commands.agentTitle(selectedClient)
    val selectedCollaboration get() = generationSettings.collaborationMode ?: runtime?.collaborationMode ?: "default"
    val supportsGeneration get() = "generation-settings" in workspace?.capabilities.orEmpty()
    val supportsGenerationSpeed get() = "generation-speed" in workspace?.capabilities.orEmpty()
    val replyMode get() = preferredModes[targetKey] ?: "queue"
    val transferStatus get() = busyTargets[targetKey]
    val operationRecords get() = (selectedClient.operationTargets(selectedThread.takeIf { it.isNotEmpty() }) + selectedClient.operationTargets(null)).distinct().flatMap { operations[it].orEmpty() }.distinctBy { it.target to it.id }
    private val latestSourceCreation get()=operationRecords.lastOrNull { it.kind=="creation"&&it.sourceKey==targetKey }
    internal val pendingCreationMessage: PendingCreationMessage? get() {
        if(selectedThread.isNotEmpty() || selectedClient.isDemo)return null
        val preparing=preparingCreationMessages[targetKey]
        // Select the latest creation before hiding terminal records, so an older
        // rejected message cannot reappear after a newer creation is confirmed.
        val record=if(preparing!=null)operationRecords.lastOrNull { it.kind=="creation"&&it.sourceKey==targetKey&&it.id==preparing.id }
            else latestSourceCreation
        if(record==null)return preparing?.let { PendingCreationMessage(it,"preparing") }
        if(record.detached || record.phase !in listOf("ready","preparing","prepared","submitting","received","uncertain","outcome-unknown","opening","submitted","rejected"))return null
        val cacheKey=Triple(record.target,record.id,record.intentSha256)
        val content=creationMessageCache?.takeIf { it.first==cacheKey }?.second ?: JSONObject(record.intent).let { intent ->
            MobileMessage(record.id,"user",intent.getString("text"),files=intent.getJSONArray("attachments").objects().map { it.getString("name") })
        }.also { creationMessageCache=cacheKey to it }
        return PendingCreationMessage(content,record.phase)
    }
    val activeOperationRecords get() = operationRecords.filter { it.kind !in listOf("fork","chat-action","workspace-archive") && !it.detached && it.sourceKey==targetKey && it.phase !in listOf("completed","cancelled","interrupted","confirmed","opened","queued","submitted") && !(it.kind=="reply" && it.phase=="failed" && it.result!=null) }
    val detachedReplies get() = operationRecords.filter { it.kind=="reply" && it.detached && it.sourceKey==targetKey }
    private fun replyRecordKey(record: SavedOperation)=record.target to record.id
    private fun currentDetachedReply(record: SavedOperation,expectedTarget: String)=expectedTarget==targetKey && record.sourceKey==expectedTarget &&
        detachedReplies.any { it.target==record.target && it.id==record.id && it.intentSha256==record.intentSha256 }
    fun replyRecordQuerying(record: SavedOperation)=replyRecordQueryOwners.containsKey(replyRecordKey(record))
    fun replyRecordQueryError(record: SavedOperation)=replyRecordQueryErrors[replyRecordKey(record)]
    fun canQueryReplyRecord(record: SavedOperation,expectedTarget: String)=localReady && selectedClient.online &&
        currentDetachedReply(record,expectedTarget) && !replyRecordQuerying(record)
    suspend fun replyRecordContent(record: SavedOperation,expectedTarget: String): CodexContent {
        require(currentDetachedReply(record,expectedTarget)) { "原发送记录已离开当前目标" }
        return (execution ?: error("原记录暂不可读")).replyRecordContent(selectedClient,record)
    }
    fun queryReplyRecord(record: SavedOperation,expectedTarget: String) {
        val flow=execution ?: return
        if(!canQueryReplyRecord(record,expectedTarget))return
        val client=selectedClient;val selection=selectionRevision;val navigation=clientNavigationRevision
        val revision=revisions[client.id] ?: 0;val recordKey=replyRecordKey(record);val owner=UUID.randomUUID().toString()
        replyRecordQueryOwners[recordKey]=owner;replyRecordQueryErrors.remove(recordKey)
        fun current()=currentDetachedReply(record,expectedTarget) && selectionRevision==selection && clientNavigationRevision==navigation &&
            (revisions[client.id] ?: 0)==revision && sameScope(selectedClient.scope,client.scope) &&
            selectedClient.endpoint==client.endpoint && selectedClient.codexDirect?.fingerprint==client.codexDirect?.fingerprint
        tasks.launch {
            try { if(current() && replyRecordQueryOwners[recordKey]==owner)acceptFlow(client,flow.query(client,record)) }
            catch(cancel: CancellationException) { throw cancel }
            catch(failure: Exception) { if(current() && replyRecordQueryOwners[recordKey]==owner)
                replyRecordQueryErrors[recordKey]=failure.message ?: "查询未完成，原记录已保留" }
            finally { if(replyRecordQueryOwners[recordKey]==owner)replyRecordQueryOwners.remove(recordKey) }
        }
    }
    val detachedCreations get() = operationRecords.filter { it.kind=="creation" && it.detached && it.sourceKey==targetKey }.takeLast(3)
    private val pendingRecords get(): List<SavedOperation> {
        val rejected=latestSourceCreation?.takeIf { !it.detached&&it.phase=="rejected" }
        return operationRecords.filter { it.kind !in listOf("fork","chat-action","workspace-archive") && !it.detached && it.sourceKey==targetKey &&
            (it.phase in listOf("ready","preparing","prepared","submitting","received","uncertain","outcome-unknown","opening","submitted") ||
                rejected?.let { original -> it.id==original.id&&it.target==original.target }==true) && (it.kind!="reply" || it.phase!="submitted") }
    }
    private val creationWorkPending get()=pendingRecords.any { it.kind in listOf("creation","thread-create")&&it.phase!="rejected" }
    private val access: SessionAccess get() {
        if(!localReady) return SessionAccess(false,false,false,emptyList(),false,false,false,false,false)
        val local=demo && selectedClient.isDemo
        val fresh=freshWorkspaces[clientKey]==true && foreground && execution!=null && "commands:target-bound:v1" in workspace?.capabilities.orEmpty()
        return SessionAccess(local,local || selectedClient.online && fresh,
            if(local) null else if(fresh) workspace?.runtimeConnected else false,
            if(local) emptyList() else if(fresh) workspace?.capabilities.orEmpty() else emptyList(),
            selectedThread.isNotEmpty(),newProjects[clientKey]!=null || threads.any { it.id==selectedThread && it.projectId!=null },
            selectedThread.isEmpty() && newProjects.containsKey(clientKey) && newProjects[clientKey]==null,
            local || freshHistories[targetKey]==true && runtime?.canControl==true,false)
    }
    // Presentation readiness belongs to this navigation and connection revision;
    // a later catch-up, paging or explicit refresh must not disable a ready composer.
    private val initialHistoryLoading get()=historyLoading&&readyHistorySelection!=Triple(targetKey,selectionRevision,revisions[selectedClient.id] ?: 0)
    private fun confirmedReplyReads(client: MobileClient,thread: String): Pair<MobileWorkspace,MobileHistory>? {
        if(!foreground||client.id!=selectedClient.id||thread!=selectedThread||client.endpoint!=selectedClient.endpoint||
            !sameScope(client.scope,selectedClient.scope)||freshWorkspaces[clientKey]!=true||freshHistories[targetKey]!=true||
            readyHistorySelection!=Triple(targetKey,selectionRevision,revisions[client.id] ?: 0)||!isWritable)return null
        val currentWorkspace=workspace ?: return null
        val currentHistory=historyPages[targetKey] ?: return null
        if(currentWorkspace.runtimeConnected!=true||currentHistory.runtime?.canControl!=true)return null
        return currentWorkspace to currentHistory
    }
    private val submission: SubmissionState get() = SubmissionState(initialHistoryLoading,busyTargets.containsKey(targetKey),stoppingCurrentTurn(),
        pendingRecords.any { it.kind in listOf("reply","open") },pendingRecords.any { it.kind in listOf("creation","thread-create") },importing,running,replyMode)
    private fun submitAllowed(text: String, count: Int) = currentChat?.archived!=true && codexStorageConflictMessage == null && SessionPolicies.submit(access,submission,text,count)
    private val storageRootAmbiguous get() = selectedClient.storageIdentity?.ambiguous(selectedClient,null,null) == true
    private val storageSelectionThread get() = if(storageRootAmbiguous) null else selectedThread.takeIf { it.isNotEmpty() }
    private val storageSelectionProject get() = if(storageRootAmbiguous || selectedThread.isNotEmpty()) null else newProjects[clientKey]
    val codexLegacyStorageChoices: List<String> get() {
        if(selectedClient.scope?.agentId != "codex") return emptyList()
        return selectedClient.storageIdentity?.choices(selectedClient,storageSelectionThread,storageSelectionProject).orEmpty().takeIf { it.size > 1 }.orEmpty()
    }
    val codexStorageConflictMessage: String? get() {
        val identity=selectedClient.storageIdentity ?: return null
        if(selectedClient.scope?.agentId != "codex") return null
        val ambiguous=storageRootAmbiguous || identity.ambiguous(selectedClient,selectedThread.takeIf { it.isNotEmpty() },if(selectedThread.isEmpty()) newProjects[clientKey] else null)
        val scope=selectedClient.scope
        val unselected=operationRecords.any { record ->
            val origin=record.sourceKey
            !record.detached && origin!=null && origin!=targetKey && scope!=null &&
                RuntimeIdentity.compatibleCacheKeys(scope,selectedThread.takeIf { it.isNotEmpty() },
                    if(selectedThread.isEmpty()) newProjects[clientKey] else null,listOf(origin)).isNotEmpty() &&
                record.phase in listOf("ready","preparing","prepared","submitting","received","uncertain","outcome-unknown","opening")
        }
        return if(ambiguous || unselected) "这台电脑的旧草稿或原回执范围有冲突，全部记录已保留。核对原记录后再提交；不会覆盖当前草稿或重放未知操作。" else null
    }
    fun selectCodexLegacyStorage(key: String) {
        val client=selectedClient;val identity=client.storageIdentity ?: return
        if(key !in codexLegacyStorageChoices) return
        val chosen=client.copy(storageIdentity=identity.selecting(client,storageSelectionThread,storageSelectionProject,key))
        replaceClient(chosen);select(chosen);persistMetadata()
    }
    val isWritable get() = currentChat?.archived!=true && SessionPolicies.writable(access)
    val canCompose get() = currentChat?.archived!=true && codexStorageConflictMessage == null && SessionPolicies.compose(access)
    val canCreateThread get() = codexStorageConflictMessage == null && SessionPolicies.createThread(access) && access.online
    val canCreateProjectless get() = codexStorageConflictMessage == null && SessionPolicies.createProjectless(access) && access.online
    val canCreateProject get()=localReady&&codexStorageConflictMessage == null && access.online&&"projects:create" in workspace?.capabilities.orEmpty()
    val canConfigureGeneration get() = canCompose && SessionPolicies.configureGeneration(access,submission)
    val canSelectPlan get() = canCompose && SessionPolicies.selectPlan(access,submission)
    val canSelectReplyMode get() = canCompose && !submission.sending && !submission.replyPending && !submission.creationPending
    val replyModes get() = SessionPolicies.replyModes(access)
    val queuedReplies get() = runtime?.receipts?.filter { it.hostId==selectedClient.id&&it.threadId==selectedThread&&
        QueueRules.active(it.state,it.queueSource,it.queuePresent,it.queueDisposition) }.orEmpty()
    val receiptNotices get() = runtime?.receipts.orEmpty().let { list -> SessionPolicies.noticeIndices(list.map { ReceiptState(it.operationId,it.state,it.supersededByTurnId,it.queueSyncError) },dismissedReceiptFailures[targetKey].orEmpty().toList(),pendingRecords.firstOrNull()?.id).map { list[it] } }
    private val timelineCache=NativeTimelineCache()
    val timeline get() = timelineCache.project(targetKey,messages,visibleMessageLimit,historyPages[targetKey]?.turnId,
        running||runtimeState=="paused",chatSearchJump?.message?.id)
    val canStop get() = SessionPolicies.stop(running,isWritable,access.online,submission.stopping)
    val canRunAgain get() = SessionPolicies.recoveryPrerequisites(selectedThread.isNotEmpty(),running,runtimeState,runtime!=null) &&
        messages.any { it.role=="user" } && submitAllowed(MobileRules.runAgainText(false),0)
    private val controlTaskUserMessageId get()=messages.lastOrNull { it.role=="user" }?.id
    private val controlTaskRunId get()=messages.lastOrNull { it.runId!=null }?.runId
    fun composerActionContext()=ComposerActionContext(targetKey,selectedClient,selectedThread,selectionRevision,
        clientNavigationRevision,revisions[selectedClient.id] ?: 0,runtime?.turnId,runtimeState,decision.action,draftVersions[targetKey],
        controlTaskUserMessageId,controlTaskRunId)
    fun composerActionCurrent(context: ComposerActionContext)=foreground && context.key==targetKey && context.threadId==selectedThread &&
        context.selection==selectionRevision && context.navigation==clientNavigationRevision && context.connectionRevision==(revisions[selectedClient.id] ?: 0) &&
        context.client.id==selectedClient.id && sameScope(context.client.scope,selectedClient.scope) && context.client.endpoint==selectedClient.endpoint &&
        context.client.codexDirect?.fingerprint==selectedClient.codexDirect?.fingerprint && context.turnId==runtime?.turnId &&
        context.state==runtimeState && context.action==decision.action && context.draftVersion==draftVersions[targetKey] &&
        context.taskUserMessageId==controlTaskUserMessageId && context.taskRunId==controlTaskRunId
    fun runAgain(restart: Boolean,context: ComposerActionContext=composerActionContext()) {
        if(composerActionCurrent(context) && canRunAgain) send(CodexContent(MobileRules.runAgainText(restart),emptyList(),generationSettings))
    }
    fun stopTask(context: ComposerActionContext=composerActionContext()) {
        if(!composerActionCurrent(context) || !canStop) return
        if(selectedClient.isDemo) { if(demo) runtimeState="interrupted" } else stop()
    }
    val decision: ComposerDecision get() {
        val input = ComposerInput(submission.stopping,running,submission.sending,creationWorkPending,text,attachments.size,
            submitAllowed(text,attachments.size),SessionPolicies.stop(running,isWritable,access.online,submission.stopping),
            (selectedClient.isDemo && demo && !running || SessionPolicies.recoveryPrerequisites(selectedThread.isNotEmpty(),running,runtimeState,runtime!=null)) &&
                messages.any { it.role=="user" } && submitAllowed(MobileRules.runAgainText(false),0),runtimeState,runtime?.receipts?.lastOrNull()?.state,emptyList())
        return MobileRules.composer(if (MobileRules.needsRecoveryHistory(input)) input.withMessages(messages.map { ComposerMessage(it.role, it.status) }) else input)
    }
    fun projectExpanded(id: String) = id in expandedProjects[clientKey].orEmpty()
    fun toggleProject(id: String) { expandedProjects[clientKey] = expandedProjects[clientKey].orEmpty().let { if (id in it) it - id else it + id }; persistMetadata() }
    fun changeLanguage(value: String) { require(NativeAppLanguage.entries.any { it.value==value });language=value;persistMetadata() }
    fun changeAppearance(value: String) { require(value in listOf("system", "light", "dark")); appearance = value; persistMetadata() }
    fun setDemoMode(value: Boolean) {
        demo = value
        if (!hasClient) select(if (value) DemoMobileData.clients.first() else realClients.firstOrNull() ?: DemoMobileData.clients.first())
        persistMetadata()
    }
    fun select(client: MobileClient)=selectClient(client,refreshAutomatically=true)
    private fun selectClient(client: MobileClient,refreshAutomatically: Boolean) {
        releasePhoneReadingProof()
        voice?.suspendRecording();aiSharingRequest=null
        clientNavigationRevision++;selectionRevision++;networkRecoveryJob?.cancel();networkRecoveryJob=null; workspaceJob?.cancel(); historyJob?.cancel(); eventJob?.cancel(); historyRecoveryJob?.cancel()
        selectedClient = realClients.firstOrNull { it.id==client.id && sameScope(it.scope,client.scope) } ?: client; search = ""; error = null; synchronizeReadingPresentation()
        persistMetadata()
        if (!client.isDemo) { if(refreshAutomatically)refresh(); loadCachedHistory(); if(refreshAutomatically)loadHistory(); loadOperations() }
    }
    private fun selectVerifiedClient(client: MobileClient) {
        val key=client.cacheKey(null);val verified=workspaces[key] ?: error("连接快照尚未就绪")
        require(verified.hostId==client.id && (!client.requiresReadTarget || verified.scope!=null) &&
            (client.scope==null || verified.scope==null || sameScope(client.scope,verified.scope))) { "电脑运行目标不匹配" }
        freshWorkspaces[key]=true;recordConnectionSuccess(client);reconcilePhoneReading()
        selectClient(client,refreshAutomatically=false)
        freshHistories[targetKey]=false
        val events=selectedThread.isNotEmpty() && verified.capabilities.containsAll(listOf("events","commands:target-bound:v1"))
        if(events)startEvents(initialReadGraceMillis=8_000) else loadHistory()
        loadModels()
    }
    fun selectThread(thread: MobileThread) {
        voice?.suspendRecording();aiSharingRequest=null
        require(thread.hostId == selectedClient.id && threads.any { it.id == thread.id })
        if(selectedThread==thread.id) {
            // Reopening the current chat is presentation navigation. Keep its
            // read generation, in-flight GET and expanded reading window.
            error=null
            if(!selectedClient.isDemo) { loadHistory(); loadOperations() }
            return
        }
        selectionRevision++; historyJob?.cancel(); eventJob?.cancel(); historyRecoveryJob?.cancel(); updateReadingSelection(thread.id)
        error = null
        if (!selectedClient.isDemo) { loadCachedHistory(); loadHistory(); loadOperations() }
        // Start navigation reads before serializing the local metadata snapshot.
        // Draft edits and durable-before-send intents keep their own save paths.
        persistMetadata()
    }
    fun performAction(context: ComposerActionContext=composerActionContext()) {
        if(!composerActionCurrent(context)) return
        val action = decision
        if (!action.enabled) return
        if(!selectedClient.isDemo) {
            when(action.action) { "stop"->stop(); "send"->send(); "resume"->send(CodexContent(MobileRules.runAgainText(false),emptyList(),generationSettings)) }
            return
        }
        if(!demo) return
        when (action.action) {
            "stop" -> runtimeState = "interrupted"
            "send", "resume" -> {
                val content = if (action.action == "resume") MobileRules.runAgainText(false) else text
                histories[targetKey] = messages + MobileMessage("demo-${UUID.randomUUID()}", "user", content)
                text = ""; runtimeState = "running"
                scrollToLatestRevision++
            }
        }
    }
    var scrollToLatestRevision by mutableLongStateOf(0)
        private set
    fun newChat(projectId: String? = null) {
        voice?.suspendRecording();aiSharingRequest=null
        if(!selectedClient.isDemo) {
            if(!canCreateThread || projectId==null && !canCreateProjectless || projectId!=null && projects.none { it.id==projectId }) return
            selectionRevision++; historyJob?.cancel(); eventJob?.cancel(); historyRecoveryJob?.cancel(); selectedThread=""; newProjects[clientKey]=projectId
            error=null; synchronizeReadingPresentation(); inheritGeneration(); persistMetadata(); loadOperations(); loadModels(); return
        }
        if (!demo) return
        selectionRevision++; historyJob?.cancel(); eventJob?.cancel(); historyRecoveryJob?.cancel()
        selectedThread = "draft-${UUID.randomUUID()}"; histories[targetKey] = emptyList(); runtimeState = "idle"
        synchronizeReadingPresentation()
    }
    fun refresh(more: Boolean = false, userInitiated: Boolean = false) {
        val client = selectedClient
        if (!localReady || client.isDemo || !hasClient || loading || incomingEasyWorkLink!=null) return
        val key = clientKey; val revision = revisions[client.id] ?: 0
        val old = workspace
        val owner=WorkspaceReadOwner(clientNavigationRevision,revision,workspaceManagementRevisions[key] ?: 0)
        workspaceJob = tasks.launch {
            val successAtStart=connectionSuccesses[key] ?: 0
            loadingTargets[key] = owner
            try {
                val result = reads.workspace(client, if (more) old?.nextProjectCursor else null, if (more) old?.nextThreadCursor else null)
                if (!currentWorkspace(client, key, owner)) return@launch
                require(result.hostId == client.id && (!client.requiresReadTarget || result.scope != null) && (client.scope == null || result.scope == null || sameScope(client.scope, result.scope))) { "电脑身份不匹配" }
                // Each page is merged only with that same scope; refresh replaces removed entries.
                var merged = if (more && old != null) MobileWorkspaceMetadata.mergePage(old,result) else result
                merged = merged.copy(wire=encodeWorkspace(merged).toString())
                workspaces[key] = merged
                reconcilePhoneReading()
                freshWorkspaces[key]=true
                recordConnectionSuccess(client)
                replaceClient(selectedClient.copy(online = true, mode = result.mode))
                clearReadError(userInitiated)
                persistMetadata(); startEvents(); loadModels()
            } catch (cancel: CancellationException) { throw cancel }
            catch (failure: Exception) { if (currentWorkspace(client, key, owner)) { recordConnectionFailure(client,failure,successAtStart); if(userInitiated) readError(failure,"刷新失败，请重试。\n",true); persistMetadata() } }
            finally { if(loadingTargets[key]===owner)loadingTargets.remove(key) }
        }
    }
    // A client navigation/connection owns its catalog. Selecting only one of
    // its threads keeps that owner and its valid connection proof intact.
    private fun currentWorkspace(client: MobileClient,key: String,owner: WorkspaceReadOwner) =
        selectedClient.id==client.id && clientKey==key && clientNavigationRevision==owner.navigation &&
        (revisions[client.id] ?: 0)==owner.connectionRevision && (workspaceManagementRevisions[key] ?: 0)==owner.managementRevision && loadingTargets[key]===owner && sameScope(selectedClient.scope,client.scope)
    private fun recordConnectionSuccess(client: MobileClient) {
        val key=client.cacheKey(null)
        connectionSuccesses.keys.retainAll(realClients.map { it.cacheKey(null) }.toSet()+key)
        connectionSuccesses[key]=(connectionSuccesses[key] ?: 0)+1
    }
    private fun recordConnectionFailure(client: MobileClient,failure: Exception,successAtStart: Long? = null,displayError: Boolean=true) {
        val transient=(failure as? HostReadError)?.transient==true
        // A newer validated response disproves this older transport inference.
        // Authentication, identity and protocol failures always fail closed.
        if(transient && successAtStart!=null && (connectionSuccesses[client.cacheKey(null)] ?: 0)>successAtStart)return
        if(client.id==selectedClient.id&&sameScope(client.scope,selectedClient.scope)) {
            suggestionNeedsFreshHistory=true
            if(!transient) { desktopSuggestionReader.clear();suggestionReadAhead.cancel() }
        }
        freshWorkspaces[client.cacheKey(null)]=false
        val latest=realClients.firstOrNull { it.id==client.id&&sameScope(it.scope,client.scope) }?:client
        replaceClient(latest.copy(online=false,connectionPhase=if(transient) ConnectionPhase.WAITING else ConnectionPhase.FAILED))
        if(!transient&&displayError) readError(failure,fallback="读取失败，保留缓存")
    }
    fun loadHistory(more: Boolean = false, userInitiated: Boolean = false): Job? {
        val acceptanceTiming=NativeReadAcceptanceTiming.capture()
        val client = selectedClient; val thread = selectedThread; val key = targetKey
        if (client.isDemo || incomingEasyWorkLink!=null || thread.isEmpty() || thread.startsWith("draft-") || historyLoading) return null
        if(!more && userInitiated) { pagedSelections.remove(key); loadedHistorySelections.remove(key) }
        val selection = selectionRevision; val revision = revisions[client.id] ?: 0
        if(!more)prepareSuggestionRead()
        val cursor = if (more) historyCursor ?: return null else null
        val owner=HistoryReadOwner(selection,revision)
        historyJob = tasks.launch {
            val successAtStart=connectionSuccesses[client.cacheKey(null)] ?: 0
            val snapshotAtStart=conversationSnapshotRevisions[key] ?: 0
            historyTargets[key] = owner
            try {
                val result = reads.history(client, thread, cursor)
                if (targetKey != key || selectionRevision != selection || (revisions[client.id] ?: 0) != revision || historyTargets[key]!==owner) return@launch
                require(result.hostId == client.id && result.threadId == thread && (!client.requiresReadTarget || result.scope != null) && (client.scope == null || result.scope == null || sameScope(client.scope, result.scope))) { "电脑或对话身份不匹配" }
                acceptanceTiming?.record(NativeReadAcceptanceTiming.Point.HISTORY_APPLYING,result.hostId,result.threadId)
                val previous = histories[key].orEmpty()
                val previousPage=historyPages[key]
                val sameReadGeneration=loadedHistorySelections[key]==(selection to revision)
                if(more)require(MobileHistoryWindowPolicy.acceptsEarlierPage(previousPage,result,sameReadGeneration)) { "聊天历史已变化，请刷新后重试。" }
                val compatiblePrefix=MobileHistoryWindowPolicy.compatiblePrefix(previousPage,result,previous,sameReadGeneration)
                val paged = com.agenthub.core.ReadingPolicy.preserveHistoryPrefix(!result.runtimePresent||result.windowed,
                    pagedSelections.containsKey(key),pagedSelections[key]==selection && (!result.windowed || sameReadGeneration),
                    !result.windowed || compatiblePrefix) || result.windowed&&chatSearchJump?.context?.key==key
                val incomingIds=result.messages.map { it.id }.toSet()
                val merged = when {
                    more && (!result.runtimePresent || result.windowed) -> (result.messages + previous).distinctBy { it.id }
                    !more && paged -> previous.takeWhile { it.id !in incomingIds } + result.messages
                    else -> result.messages
                }
                val page=when {
                    more&&result.windowed&&previousPage!=null -> previousPage.copy(nextCursor=result.nextCursor,historyRevision=result.historyRevision,wire=JSONObject(previousPage.wire).put("nextCursor",result.nextCursor).toString())
                    !more&&paged -> result.copy(nextCursor=previousPage?.nextCursor,wire=JSONObject(result.wire).put("nextCursor",previousPage?.nextCursor).toString())
                    else -> result
                }
                if(!more&&result.windowed&&!paged)pagedSelections.remove(key)
                if(more && (!result.runtimePresent || result.windowed)) pagedSelections[key]=selection
                if (more) { visibleMessageLimit += merged.size - previous.size; revealedEarlier = true }
                historyPages[key] = page; histories[key] = merged; runs.remove(key)
                loadedHistorySelections[key]=selection to revision
                clearReadError(userInitiated)
                freshHistories[key]=true
                if(!more) {
                    readyHistorySelection=Triple(key,selection,revision)
                    receivedConversationSnapshot(key,client,result)
                }
                recordConnectionSuccess(client)
                replaceClient(selectedClient.copy(online=true))
                if(!more)prepareSuggestionRead()
                persistHistory(key, client, thread, page, merged)
                startEvents()
            } catch (cancel: CancellationException) { throw cancel }
            catch (failure: Exception) { if (targetKey == key && selectionRevision == selection && (revisions[client.id] ?: 0)==revision && historyTargets[key]===owner) { recordConnectionFailure(client,failure,successAtStart); if(more) readError(failure,"加载更早消息失败，请重试。\n",true) else { if((failure as? HostReadError)?.transient!=true || userInitiated || (conversationSnapshotRevisions[key] ?: 0)==snapshotAtStart)conversationSyncIssues[key]=ConversationSyncIssue(conversationReadFailure(failure));if(userInitiated) readError(failure,"加载历史失败，请重试。\n",true) } } }
            finally { if(historyTargets[key]===owner)historyTargets.remove(key) }
        }
        return historyJob
    }
    fun earlierMessages(): Job? { if (timeline.hasEarlierMessages) { visibleMessageLimit += ReadingPolicy.earlierMessageIncrement().toInt(); revealedEarlier = true; return null } else return loadHistory(more = true) }
    private fun version(key: String)=draftVersions[key] ?: UUID.randomUUID().toString().also { draftVersions[key]=it }
    private fun loadOperations() {
        val flow=execution ?: return; val client=selectedClient
        if(client.isDemo) return
        val targets=(client.operationTargets(null)+client.operationTargets(selectedThread.takeIf { it.isNotEmpty() })).distinct()
        tasks.launch {
            try { targets.forEach { target -> val records=flow.journal.records(target); operations[target]=records; records.forEach { observeOperation(client,it) } } }
            catch(failure: Exception) { if(selectedClient.id==client.id) error=failure.message ?: "本地提交记录无法读取" }
        }
    }
    private suspend fun acceptFlow(client: MobileClient,result: CodexFlowResult,presentationCurrent: ()->Boolean={true}) {
        val record=result.record; val flow=execution ?: return
        if(record.kind=="workspace-archive") { acceptWorkspaceArchive(client,record);return }
        if(record.kind=="chat-action") { acceptChatAction(client,record);return }
        operations[record.target]=flow.journal.records(record.target)
        val origin=record.sourceKey
        val receipt=if(record.kind=="reply" && record.result!=null) CodexCommandCodec.receipt(JSONObject(record.result)).also {
            val intent=JSONObject(record.intent)
            require(it.hostId==client.id && it.threadId==intent.getString("threadId") && it.operationId==record.id &&
                record.target in client.operationTargets(it.threadId)) { "原消息回执目标不匹配" }
        } else null
        val accepted=receipt?.let { ReplyRecoveryRules.acceptedReceipt(it.state,it.queueSource,it.queuePresent,it.queueDisposition) }==true
        if(origin!=null && receipt?.let { ReplyRecoveryRules.releasesReplyWait(it.state,it.queueSource,it.queuePresent,it.queueDisposition) }==true && busyCommands[origin]==record.id)
            busyOwners[origin]?.let { endBusy(origin,it) }
        val unchanged=presentationCurrent() && (if(record.kind=="fork") origin!=null && !record.detached && forkNavigation[origin]?.let {
            it.id==record.id && it.selection==selectionRevision && it.draft==version(origin)
        }==true else !record.detached && origin!=null && draftVersions[origin]==record.sourceVersion)
        val clientCache=client.cacheKey(null)
        result.project?.let { project -> workspaces[clientCache]?.let { old->val updated=old.copy(projects=(old.projects+project).distinctBy { it.id });workspaces[clientCache]=updated.copy(wire=encodeWorkspace(updated).toString()) } }
        result.thread?.let { thread ->
            workspaces[clientCache]?.let { old -> val updated=old.copy(threads=(listOf(thread)+old.threads).distinctBy { it.id }); workspaces[clientCache]=updated.copy(wire=encodeWorkspace(updated).toString()) }
            // A late creation may be shown in the sidebar; it cannot replace a newer draft.
            if(unchanged && targetKey==origin && selectedClient.id==client.id && sameScope(selectedClient.scope,client.scope)) {
                selectedThread=thread.id; selectionRevision++; newProjects.remove(clientCache)
            }
        }
        if(unchanged && (record.kind=="reply" && accepted || record.kind=="creation" && record.phase=="confirmed")) {
            drafts.remove(origin); draftFiles.remove(origin);attachmentImportGenerations.remove(origin); draftVersions[origin!!]=UUID.randomUUID().toString()
            if(receipt?.state!="queued") generation.remove(origin)
            scrollToLatestRevision++
        }
        persistMetadata()
        if(selectedClient.id==client.id && sameScope(selectedClient.scope,client.scope)) { loadOperations(); refresh(); loadHistory() }
    }
    private fun restartOperationReadObservers() {
        val active=observingOperations.filterValues { it.isActive }.keys
        val records=operations.values.flatten().filter { it.target+":"+it.id in active }.distinctBy { it.target to it.id }
        observingOperations.values.forEach { it.cancel() };observingOperations.clear()
        records.forEach { record ->
            val intent=JSONObject(record.intent)
            val thread=if(record.kind in listOf("reply","open","fork"))intent.getString("threadId") else null
            // Reuse only a still-configured exact original target, including a
            // background chat. Querying its original ID never resubmits intent.
            realClients.firstOrNull { it.id==intent.getString("hostId")&&record.target in it.operationTargets(thread) }
                ?.let { observeOperation(it,record) }
        }
    }
    private fun observeOperation(client: MobileClient,record: SavedOperation) {
        val flow=execution ?: return; val operationKey=record.target+":"+record.id
        if(!foreground || record.detached || record.kind=="thread-create" || record.phase !in listOf("submitting","received","uncertain","outcome-unknown","submitted","opening","preparing") ||
            record.kind=="reply" && record.phase=="submitted" || observingOperations[operationKey]?.isActive==true) return
        observingOperations[operationKey]=tasks.launch {
            var current=record
            val reply=record.kind=="reply";var queryDelay=0L
            while(isActive && foreground && !current.detached && current.phase in listOf("submitting","received","uncertain","outcome-unknown","submitted","opening","preparing")) {
                if(reply)queryDelay=ReplyRecoveryRules.nextReplyQueryDelayMillis(queryDelay)
                delay(if(reply)queryDelay else 1500)
                try {
                    val latest=flow.journal.records(current.target).first { it.id==current.id }
                    if(latest.detached) break
                    val result=flow.query(client,latest); current=result.record
                    acceptFlow(client,result)
                    if(current.kind=="reply" && current.phase in listOf("queued","submitted","completed")) break
                } catch(cancel: CancellationException) { throw cancel }
                catch(failure: Exception) {
                    if(failure is IllegalArgumentException || failure is org.json.JSONException || failure is CodexCommandError &&
                        (failure.status in listOf(400,401,403,409,413) || !reply && failure.status==404)) break
                    if(!reply)delay(2000)
                }
            }
        }
    }
    val forkRecords get()=operationRecords.filter { it.kind=="fork" && it.sourceKey==targetKey }
    val forkBusy get()=forkRequests.containsKey(targetKey)
    private fun forkBaseReason()=ThreadForkRules.baseReason(!selectedClient.isDemo && selectedClient.scope?.agentId=="codex" && codexStorageConflictMessage==null,
        workspace?.mode=="desktop-runtime" && workspace?.runtimeConnected!=false,workspace?.capabilities.orEmpty(),selectedClient.online)
    fun forkReason(turnId: String?,environment: String)=ThreadForkRules.reason(turnId,forkBaseReason(),
        forkBusy || forkRecords.any { !it.detached && ThreadForkRules.pending(it.phase) },forkOptions[targetKey]?.availability(),
        environment,"threads:fork:worktree" in workspace?.capabilities.orEmpty(),forkOptionErrors[targetKey])
    fun loadForkOptions(refresh: Boolean=false) {
        val client=selectedClient;val key=targetKey;val thread=selectedThread
        if(thread.isEmpty() || forkBaseReason()!=null || forkOptionsLoading[key]==true ||
            !refresh && (forkOptions.containsKey(key)||forkOptionErrors.containsKey(key))) return
        forkOptionsLoading[key]=true
        tasks.launch {
            try {
                val options=commands.forkOptions(client,thread)
                require(options.hostId==client.id && options.threadId==thread) { "分支选项目标不匹配" }
                if(realClients.any { it.id==client.id && sameScope(it.scope,client.scope) }) { forkOptions[key]=options;forkOptionErrors.remove(key) }
            } catch(cancel: CancellationException) { throw cancel }
            catch(failure: Exception) { forkOptions.remove(key);forkOptionErrors[key]=failure.message ?: "读取分支选项失败" }
            finally { forkOptionsLoading.remove(key) }
        }
    }
    fun forkThread(turnId: String?,environment: String) {
        if(forkReason(turnId,environment)!=null) return
        val sharing=requireSharing() ?: return
        val flow=execution ?: return;val client=selectedClient;val key=targetKey;val thread=selectedThread
        val selection=selectionRevision;val id=UUID.randomUUID().toString();val draft=version(key);val context=NativeForkContext.capture(client)
        forkRequests[key]=id;forkNavigation[key]=ForkNavigation(id,selection,draft)
        tasks.launch {
            try {
                val result=NativeThreadForkFlow(flow.journal,reads,commands).submit(client,ThreadForkRequest(client.id,thread,turnId!!,id,environment),SubmissionOrigin(key,draft),
                    { foreground && targetKey==key && selectionRevision==selection && context.sameConnection(selectedClient) && aiSharing.permits(sharing) },sharingScope=sharing.json().toString())
                acceptFlow(client,result)
            } catch(cancel: CancellationException) { throw cancel }
            catch(failure: Exception) { if(targetKey==key) error=failure.message ?: "分支未确认，请查询原回执" }
            finally { if(forkRequests[key]==id) forkRequests.remove(key);loadOperations() }
        }
    }
    fun queryFork(record: SavedOperation) {
        val flow=execution ?: return;val client=selectedClient;val key=targetKey
        if(forkBusy || forkRecords.none { it.id==record.id && it.target==record.target }) return
        val mayNavigate=record.phase=="confirmed" || !record.detached
        if(mayNavigate) forkNavigation[key]=ForkNavigation(record.id,selectionRevision,version(key))
        forkRequests[key]=record.id
        tasks.launch {
            try {
                val original=if(record.phase=="confirmed" && record.detached) flow.journal.activateConfirmedFork(record.target,record.id) else record
                acceptFlow(client,flow.query(client,original))
            } catch(cancel: CancellationException) { throw cancel }
            catch(failure: Exception) { if(targetKey==key) error=failure.message ?: "原分支回执尚未确认" }
            finally { if(forkRequests[key]==record.id) forkRequests.remove(key);loadOperations() }
        }
    }
    private fun beginBusy(key: String,operationId: String,label: String): String {
        val owner=UUID.randomUUID().toString(); busyOwners[key]=owner; busyCommands[key]=operationId; busyTargets[key]=label; return owner
    }
    private fun updateBusy(key: String,owner: String,label: String) { if(busyOwners[key]==owner) busyTargets[key]=label }
    private fun endBusy(key: String,owner: String) { if(busyOwners[key]==owner) { busyOwners.remove(key); busyCommands.remove(key); busyTargets.remove(key) } }
    private fun send(override: CodexContent? = null,expectedPlanId: String? = null) {
        val flow=execution ?: return; val client=selectedClient; val key=targetKey; val selection=selectionRevision
        val content=override ?: CodexContent(text,attachments,generationSettings)
        if(!submitAllowed(content.text,content.files.size)) return
        val thread=selectedThread.takeIf { it.isNotEmpty() }; val project=if(thread==null) newProjects[clientKey] else threads.firstOrNull { it.id==thread }?.projectId
        val draftVersion=version(key); val origin=SubmissionOrigin(key,if(override==null) draftVersion else UUID.randomUUID().toString())
        val mode=if(running) SessionPolicies.activeReplyMode(access,replyMode) else "next-turn"
        val sharing=requireSharing(if(mode=="steer") runtime?.settings?.model else content.settings.model ?: generationSettings.model ?: runtime?.settings?.model ?: selectedModel?.id) ?: return
        val configured=content.copy(settings=if(mode=="steer") GenerationSettings() else content.settings,sharingScope=sharing.json().toString())
        val turn=runtime?.turnId; val connectionRevision=revisions[client.id] ?: 0
        val operationId=UUID.randomUUID().toString(); val owner=beginBusy(key,operationId,"正在保存提交意图…"); error=null
        if(thread==null)preparingCreationMessages[key]=MobileMessage(operationId,"user",configured.text,files=configured.files.map { it.manifest.name })
        tasks.launch {
            try {
                val result=flow.submit(client,thread,project,origin,configured,mode,turn,expectedPlanId,
                    guard={ foreground && selectionRevision==selection && targetKey==key && version(key)==draftVersion &&
                        (revisions[client.id] ?: 0)==connectionRevision && sameScope(selectedClient.scope,client.scope) && aiSharing.permits(sharing) },
                    progress={ updateBusy(key,owner,it); loadOperations() },operationId=operationId)
                acceptFlow(client,result)
            } catch(cancel: CancellationException) { throw cancel }
            catch(failure: Exception) { if(targetKey==key && busyOwners[key]==owner) error=failure.message ?: "提交未完成，内容已保留" }
            finally {
                endBusy(key,owner); loadOperations()
                if(preparingCreationMessages[key]?.id==operationId)preparingCreationMessages.remove(key)
            }
        }
    }
    fun queryOperation(record: SavedOperation) {
        val flow=execution ?: return; val client=selectedClient; val key=targetKey
        if(operationRecords.none { it.id==record.id && it.target==record.target } || busyTargets.containsKey(key)) return
        val context=if(record.kind=="creation") CreationWaitContext(key,record,client,selectionRevision,clientNavigationRevision,revisions[client.id] ?: 0) else null
        if(context!=null && (!creationTargetCurrent(context) || record.sourceKey!=key || operationRecords.none {
            it.kind=="creation"&&it.id==record.id&&it.target==record.target&&it.intentSha256==record.intentSha256&&it.sourceKey==key
        }))return
        val errorAtStart=errorRevision
        fun current()=if(context!=null)creationTargetCurrent(context) else targetKey==key
        val owner=beginBusy(key,record.id,"查询原操作记录…")
        tasks.launch { try {
                if(context!=null && (!current() || busyOwners[key]!=owner))return@launch
                val result=flow.query(client,record)
                // The receipt remains durable even after navigation changes. Only the original
                // current query may navigate or release the unchanged editable draft.
                if(context!=null)acceptFlow(client,result) { current() && busyOwners[key]==owner }
                else acceptFlow(client,result)
            }
            catch(cancel: CancellationException) { throw cancel }
            catch(failure: Exception) { if(current() && busyOwners[key]==owner && (context==null || errorRevision==errorAtStart)) error=failure.message ?: "查询未完成，原记录已保留" }
            finally { endBusy(key,owner) } }
    }
    fun continueCreation(record: SavedOperation) {
        val flow=execution ?: return; val client=selectedClient; val key=targetKey; val selection=selectionRevision
        if(!flow.mayContinueCreation(record) || record.sourceKey!=key || operationRecords.none { it.id==record.id && it.target==record.target } || busyTargets.containsKey(key)) return
        val sharing=requireSharing(JSONObject(record.intent).optional("model"),record.sharingScope,false) ?: return
        val owner=beginBusy(key,record.id,"继续提交原意图…")
        tasks.launch { try { acceptFlow(client,flow.continuePrepared(client,record,{ foreground && targetKey==key && selectionRevision==selection && busyOwners[key]==owner && aiSharing.permits(sharing) },{ updateBusy(key,owner,it) })) }
            catch(cancel: CancellationException) { throw cancel }
            catch(failure: Exception) { if(targetKey==key && busyOwners[key]==owner) error=failure.message ?: "原意图未提交" }
            finally { endBusy(key,owner) } }
    }
    fun mayContinueCreation(record: SavedOperation)=execution?.mayContinueCreation(record)==true
    fun mayRetryReply(record: SavedOperation)=foreground && selectedClient.online && record.sourceKey==targetKey &&
        record.replyRecovery?.mayRetry(selectedClient,record)==true && !busyTargets.containsKey(targetKey)
    fun retryReply(record: SavedOperation) {
        val flow=execution ?: return;val client=selectedClient;val key=targetKey;val selection=selectionRevision
        if(!mayRetryReply(record) || operationRecords.none { it.id==record.id && it.target==record.target }) return
        val sharing=requireSharing(JSONObject(record.intent).optional("model"),record.sharingScope,false) ?: return
        val revision=revisions[client.id] ?: 0
        val owner=beginBusy(key,record.id,"核对原消息投递状态…");error=null
        tasks.launch {
            try { acceptFlow(client,flow.retryReply(client,record,
                { foreground && targetKey==key && selectionRevision==selection && busyOwners[key]==owner &&
                    (revisions[client.id] ?: 0)==revision && sameScope(selectedClient.scope,client.scope) && selectedClient.codexDirect?.fingerprint==client.codexDirect?.fingerprint && aiSharing.permits(sharing) },
                { updateBusy(key,owner,it) })) }
            catch(cancel: CancellationException) { throw cancel }
            catch(failure: Exception) { if(targetKey==key && busyOwners[key]==owner) error=failure.message ?: "原消息结果仍待核对，只能查询原记录" }
            finally { endBusy(key,owner);loadOperations() }
        }
    }
    fun editOriginalReply(record: SavedOperation) {
        val flow=execution ?: return;val client=selectedClient;val key=targetKey;val selection=selectionRevision
        if(!mayRetryReply(record)) return
        if(text.isNotEmpty() || attachments.isNotEmpty()) { error="请先处理当前草稿，再恢复原消息。当前草稿已保留。";return }
        val owner=beginBusy(key,record.id,"恢复原消息内容…");val draftVersion=version(key)
        tasks.launch {
            try {
                val content=flow.originalReplyContent(client,record)
                if(targetKey!=key || selectionRevision!=selection || version(key)!=draftVersion || text.isNotEmpty() || attachments.isNotEmpty()) return@launch
                val detached=flow.journal.detach(record.target,record.id)
                operations[record.target]=flow.journal.records(record.target)
                if(targetKey!=key || selectionRevision!=selection || version(key)!=draftVersion || text.isNotEmpty() || attachments.isNotEmpty()) return@launch
                drafts[key]=content.text;draftFiles[key]=content.files;generation[key]=content.settings;attachmentImportGenerations.remove(key)
                draftVersions[key]=UUID.randomUUID().toString();persistMetadata()
                check(detached.detached)
            } catch(cancel: CancellationException) { throw cancel }
            catch(failure: Exception) { if(targetKey==key && busyOwners[key]==owner) error=failure.message ?: "原内容未恢复，当前草稿已保留" }
            finally { endBusy(key,owner);loadOperations() }
        }
    }
    internal fun creationWaitContext(record: SavedOperation): CreationWaitContext? =
        CreationWaitContext(targetKey,record,selectedClient,selectionRevision,clientNavigationRevision,revisions[selectedClient.id] ?: 0).takeIf(::mayEndCreationWait)
    private fun creationTargetCurrent(context: CreationWaitContext)=localReady && foreground && context.key==targetKey && context.selection==selectionRevision &&
        context.navigation==clientNavigationRevision && context.revision==(revisions[selectedClient.id] ?: 0) && context.client.id==selectedClient.id &&
        sameScope(context.client.scope,selectedClient.scope) && context.client.endpoint==selectedClient.endpoint && context.client.codexDirect?.fingerprint==selectedClient.codexDirect?.fingerprint
    internal fun mayEndCreationWait(context: CreationWaitContext): Boolean {
        if(!creationTargetCurrent(context))return false
        val original=context.record
        return original.kind=="creation" && original.sourceKey==context.key && operationRecords.any {
            it.id==original.id && it.target==original.target && it.intentSha256==original.intentSha256 && it.sourceKey==context.key && !it.detached &&
                it.phase in listOf("ready","preparing","prepared","submitting","received","uncertain","outcome-unknown","opening","submitted","rejected") &&
                (it.phase!="rejected" || latestSourceCreation?.let { latest -> latest.id==it.id&&latest.target==it.target }==true)
        }
    }
    internal fun returnRejectedCreation(context: CreationWaitContext) {
        val flow=execution ?: return;val original=context.record
        if(original.phase!="rejected" || !mayEndCreationWait(context) || latestSourceCreation?.let { it.id==original.id&&it.target==original.target&&it.phase=="rejected" }!=true)return
        val draftVersion=version(context.key)
        tasks.launch {
            try {
                val dismissed=flow.journal.dismissRejectedCreation(original)
                operations[original.target]=flow.journal.records(original.target)
                if(dismissed.detached && dismissed.phase=="rejected" && creationTargetCurrent(context) && version(context.key)==draftVersion) {
                    // No content/settings/attachments are copied over the editable draft.
                    draftVersions[context.key]=UUID.randomUUID().toString();persistMetadata()
                }
            } catch(cancel: CancellationException) { throw cancel }
            catch(failure: Exception) { if(creationTargetCurrent(context) && operationRecords.any { it.id==original.id&&it.target==original.target&&it.phase=="rejected"&&!it.detached })
                error=failure.message ?: "返回草稿未完成，原记录与当前草稿已保留" }
        }
    }
    internal val creationWaitRequest get()=pendingCreationWait?.takeIf(::mayEndCreationWait)
    internal fun requestCreationWait(record: SavedOperation) { creationWaitContext(record)?.let { pendingCreationWait=it } }
    internal fun continueCreationWaiting(context: CreationWaitContext) { if(pendingCreationWait===context)pendingCreationWait=null }
    internal fun endCreationWait(context: CreationWaitContext) {
        if(pendingCreationWait!==context || !mayEndCreationWait(context))return
        // Consume the exact displayed request before the journal's first suspension.
        // Cancelled, replaced and duplicate callbacks cannot notify the Host.
        pendingCreationWait=null;detachOperation(context.record)
    }
    fun detachOperation(record: SavedOperation) {
        val flow=execution ?: return; val client=selectedClient
        if(operationRecords.none { it.id==record.id && it.target==record.target }) return
        tasks.launch { try {
            val detached=flow.journal.detach(record.target,record.id)
            operations[record.target]=flow.journal.records(record.target)
            record.sourceKey?.let { key ->
                if(busyCommands[key] in listOf(record.id,record.parentOperationId).filterNotNull()) { busyOwners[key]?.let { endBusy(key,it) } }
                if(targetKey==key) { draftVersions[key]=UUID.randomUUID().toString(); persistMetadata() }
            }
            // Persisting detach first permits another draft even if the Host is offline.
            flow.detach(client,detached); operations[record.target]=flow.journal.records(record.target)
        } catch(cancel: CancellationException) { throw cancel }
        catch(failure: Exception) { if(selectedClient.id==client.id) error=failure.message ?: "结束等待未完成" } }
    }
    fun setReplyMode(mode: String,expectedTarget: String=targetKey) { if(expectedTarget==targetKey && mode in replyModes && canSelectReplyMode) { preferredModes[targetKey]=mode; persistMetadata() } }
    /** UI callbacks carry their target, then edit only the current form value without suspension. */
    internal fun updateGeneration(expectedTarget: String,edit: GenerationPreferenceEdit,change: (GenerationSettings)->GenerationSettings?) {
        if(expectedTarget!=targetKey || !canConfigureGeneration) return
        val current=generationSettings
        val updated=change(current) ?: return
        val preferenceChanged=rememberGeneration(updated,edit)
        if(updated!=current) { generation[targetKey]=updated;draftVersions[targetKey]=UUID.randomUUID().toString() }
        if(updated!=current || preferenceChanged)persistMetadata()
    }
    private fun rememberGeneration(settings: GenerationSettings,edit: GenerationPreferenceEdit): Boolean {
        fun values(value: GenerationSettings)=GenerationPreferenceValues(value.model,value.effort,value.permissionMode,value.speed)
        val previous=generationPreferences[clientKey]
        val next=GenerationPreferenceRules.remember(values(previous ?: GenerationSettings()),values(settings),edit)
        val saved=GenerationSettings(next.model,next.effort,next.permissionMode,next.speed)
        generationPreferences[clientKey]=saved
        return previous!=saved
    }
    fun configure(settings: GenerationSettings) {
        if(!canConfigureGeneration) return
        generation[targetKey]=settings
        rememberGeneration(settings,GenerationPreferenceEdit.ALL)
        draftVersions[targetKey]=UUID.randomUUID().toString(); persistMetadata()
    }
    fun setPlanMode(enabled: Boolean) {
        if(!canSelectPlan) return
        generation[targetKey]=generationSettings.copy(collaborationMode=if(enabled) "plan" else "default")
        draftVersions[targetKey]=UUID.randomUUID().toString(); persistMetadata()
    }
    fun planActionDestination()=DraftDestination(targetKey,selectionRevision)
    fun canRespondToPlan(plan: MobileProposedPlan,destination: DraftDestination=planActionDestination())=isCurrent(destination) && runtime?.proposedPlan==plan && freshHistories[targetKey]==true && isWritable && SessionPolicies.respondToPlan(runtime?.proposedPlan?.id,plan.id,running,SessionPolicies.supports(access,"plan-mode"),selectedThread.isNotEmpty(),submitAllowed("实施此计划。",0))
    fun implementPlan(plan: MobileProposedPlan,destination: DraftDestination=planActionDestination()) { if(canRespondToPlan(plan,destination)) send(CodexContent("PLEASE IMPLEMENT THIS PLAN:\n"+plan.text,emptyList(),generationSettings.copy(collaborationMode="default")),plan.id) }
    fun revisePlan(plan: MobileProposedPlan,feedback: String,destination: DraftDestination=planActionDestination()) { if(canRespondToPlan(plan,destination) && feedback.isNotBlank()) send(CodexContent(feedback,emptyList(),generationSettings.copy(collaborationMode="plan")),plan.id) }

    private fun inheritGeneration() {
        val pending=pendingRecords
        if(SessionPolicies.inheritDefaults(selectedThread.isNotEmpty(),pending.any { it.kind in listOf("reply","open") },
            pending.any { it.kind in listOf("creation","thread-create") },text,attachments.size,null)) {
            generationPreferences[clientKey]?.let { generation[targetKey]=it.copy(collaborationMode=generationSettings.collaborationMode) }
        }
    }
    fun dismissReceiptFailure(receipt: DeliveryReceipt,expectedTarget: String) {
        if(expectedTarget!=targetKey || receipt.hostId!=selectedClient.id || receipt.threadId!=selectedThread || runtime?.receipts?.any { it.operationId==receipt.operationId && it.state=="failed" }!=true) return
        dismissedReceiptFailures[targetKey]=dismissedReceiptFailures[targetKey].orEmpty()+receipt.operationId
        persistMetadata()
    }
    fun retryModels()=loadModels()
    private fun loadModels() {
        val client=selectedClient; val key=clientKey
        if(client.isDemo || incomingEasyWorkLink!=null || !foreground || !client.online || freshWorkspaces[key]!=true || workspace?.capabilities.orEmpty().none { it in listOf("models:read","generation-settings") }) return
        tasks.launch { try { val models=commands.models(client); if(clientKey==key) { modelsByClient[key]=models; modelErrors.remove(key) } }
            catch(cancel: CancellationException) { throw cancel }
            catch(failure: Exception) { if(clientKey==key) modelErrors[key]=failure.message ?: "模型列表读取失败，请重试" } }
    }
    private fun stop() {
        val client=selectedClient; val thread=selectedThread; val turn=runtime?.turnId ?: return; val key=targetKey
        if(!SessionPolicies.stop(running,isWritable,access.online,submission.stopping)) return
        val owner=StopOwner(client,thread,turn,selectionRevision,clientNavigationRevision,revisions[client.id] ?: 0)
        val errorAtStart=errorRevision
        stoppingTargets[key]=owner
        fun current()=targetKey==key && stoppingTargets[key]===owner && stoppingCurrentTurn() &&
            selectionRevision==owner.selection && clientNavigationRevision==owner.navigation
        tasks.launch { try { commands.stop(client,thread,turn); if(current()) loadHistory() }
            catch(cancel: CancellationException) { throw cancel }
            catch(failure: Exception) { if(current() && errorRevision==errorAtStart) error=failure.message ?: "停止未确认，请刷新任务" }
            finally { if(stoppingTargets[key]===owner) stoppingTargets.remove(key) } }
    }
    private fun stoppingCurrentTurn()=stoppingTargets[targetKey]?.let { owner ->
        owner.thread==selectedThread && owner.turn==runtime?.turnId && owner.revision==(revisions[selectedClient.id] ?: 0) &&
            owner.client.id==selectedClient.id && sameScope(owner.client.scope,selectedClient.scope) && owner.client.endpoint==selectedClient.endpoint &&
            owner.client.codexDirect?.fingerprint==selectedClient.codexDirect?.fingerprint
    }==true
    val supportsQueueExecution get()=SessionPolicies.supports(access,"queue:execute")
    private fun currentQueueReceipt(receipt: DeliveryReceipt): DeliveryReceipt? {
        if(receipt.hostId!=selectedClient.id || receipt.threadId!=selectedThread || receipt.operationId.isBlank() ||
            !QueueRules.active(receipt.state,receipt.queueSource,receipt.queuePresent,receipt.queueDisposition)) return null
        return queuedReplies.firstOrNull { it.operationId==receipt.operationId }
    }
    fun queueActionPending(receipt: DeliveryReceipt)=queueActionOwners.containsKey("$targetKey:${receipt.operationId}")
    private fun queueActionAllowed(receipt: DeliveryReceipt,expectedTarget: String,execute: Boolean,expectedTurnId: String?,pending: Boolean): Boolean {
        if(expectedTarget!=targetKey || loadedHistorySelections[targetKey]!=(selectionRevision to (revisions[selectedClient.id] ?: 0)) ||
            execute && runtime?.turnId!=expectedTurnId) return false
        val current=currentQueueReceipt(receipt) ?: return false
        return QueueRules.actionAllowed(current.state,current.queueSource,current.queuePresent,current.queueDisposition,
            access.online,isWritable,pending,execute,supportsQueueExecution,current.canExecuteNow)
    }
    fun canExecuteQueue(receipt: DeliveryReceipt,expectedTarget: String,expectedTurnId: String?)=
        queueActionAllowed(receipt,expectedTarget,true,expectedTurnId,queueActionPending(receipt))
    fun canCancelQueue(receipt: DeliveryReceipt,expectedTarget: String)=
        queueActionAllowed(receipt,expectedTarget,false,null,queueActionPending(receipt))
    fun executeQueue(receipt: DeliveryReceipt,expectedTarget: String,expectedTurnId: String?)=queueAction(receipt,expectedTarget,true,expectedTurnId)
    fun cancelQueue(receipt: DeliveryReceipt,expectedTarget: String)=queueAction(receipt,expectedTarget,false,null)
    private fun queueAction(receipt: DeliveryReceipt,expectedTarget: String,execute: Boolean,expectedTurnId: String?) {
        if(!queueActionAllowed(receipt,expectedTarget,execute,expectedTurnId,queueActionPending(receipt))) return
        val sharing=if(execute) requireSharing(runtime?.settings?.model) ?: return else null
        val client=selectedClient;val key=targetKey;val selection=selectionRevision;val navigation=clientNavigationRevision
        val revision=revisions[client.id] ?: 0;val errorAtStart=errorRevision
        val pending="$key:${receipt.operationId}";val owner=UUID.randomUUID().toString()
        queueActionOwners[pending]=owner
        fun current()=targetKey==key && selectionRevision==selection && clientNavigationRevision==navigation &&
            (revisions[client.id] ?: 0)==revision && sameScope(selectedClient.scope,client.scope) &&
            selectedClient.endpoint==client.endpoint && selectedClient.codexDirect?.fingerprint==client.codexDirect?.fingerprint
        tasks.launch {
            try {
                if(!current() || queueActionOwners[pending]!=owner || !queueActionAllowed(receipt,key,execute,expectedTurnId,false) ||
                    sharing!=null && !aiSharing.permits(sharing)) return@launch
                if(execute) commands.executeQueue(client,receipt.threadId,receipt.operationId,expectedTurnId)
                else commands.cancelQueue(client,receipt.threadId,receipt.operationId)
                if(current()) loadHistory()
            } catch(cancel: CancellationException) { throw cancel }
            catch(failure: Exception) { if(current()) {
                if(errorRevision==errorAtStart) error=failure.message ?: "队列操作结果待核对，请刷新原任务"
                loadHistory()
            } }
            finally { if(queueActionOwners[pending]==owner)queueActionOwners.remove(pending) }
        }
    }
    fun approvalActionDestination()=DraftDestination(targetKey,selectionRevision)
    private fun knowsApproval(approval: MobileApproval,destination: DraftDestination)=isCurrent(destination) && approval.threadId==selectedThread && runtime?.approvals?.any { it==approval }==true
    private fun approvalAnswerKey(approval: MobileApproval)=ApprovalAnswerKey(targetKey,approval.threadId,approval.turnId,approval.id)
    fun isAnswering(approval: MobileApproval)=answeringTargets.containsKey(approvalAnswerKey(approval))
    fun canEditApproval(approval: MobileApproval,destination: DraftDestination=approvalActionDestination())=knowsApproval(approval,destination) && !isAnswering(approval)
    fun canAnswer(approval: MobileApproval,destination: DraftDestination=approvalActionDestination())=knowsApproval(approval,destination) && freshHistories[targetKey]==true && access.online && isWritable && SessionPolicies.supports(access,"approvals") && !isAnswering(approval)
    fun answer(approval: MobileApproval,decision: String,answers: Map<String,List<String>>,destination: DraftDestination) {
        if(!canAnswer(approval,destination)) return
        if(approval.kind=="question") {
            if(decision=="skip") { if(approval.interactionMode!="async") return }
            else if(decision!="" || answers.keys!=approval.questions.map { it.id }.toSet() || answers.values.any { it.isEmpty() || it.any(String::isBlank) }) return
        } else if(decision !in approval.decisions) return
        val capturedAnswers=answers.mapValues { it.value.toList() }
        val sharing=if(AISharingRules.requiresContentGrant(decision)) requireSharing(runtime?.settings?.model) ?: return else null
        val client=selectedClient; val requestKey=approvalAnswerKey(approval);val owner=UUID.randomUUID().toString()
        val connectionRevision=revisions[client.id] ?: 0;val certificate=client.codexDirect?.fingerprint;val errorAtStart=errorRevision
        fun current()=foreground && knowsApproval(approval,destination) && (revisions[client.id] ?: 0)==connectionRevision &&
            sameScope(client.scope,selectedClient.scope) && client.endpoint==selectedClient.endpoint && selectedClient.codexDirect?.fingerprint==certificate
        answeringTargets[requestKey]=owner
        tasks.launch { try {
            if(!current() || sharing!=null && !aiSharing.permits(sharing)) return@launch
            commands.answer(client,approval.threadId,approval.turnId,approval.id,decision,capturedAnswers)
            if(current()) loadHistory()
        } catch(failure: Exception) { if(current() && errorRevision==errorAtStart) error=failure.message ?: "回复未确认，请刷新请求" }
            finally { if(answeringTargets[requestKey]==owner) answeringTargets.remove(requestKey) } }
    }
    internal var externalInbox by mutableStateOf<NativeExternalAttachments?>(null)
        private set
    internal var recentPhotos: NativeRecentPhotoLibrary? by mutableStateOf(null)
        private set
    internal var photoAuthorization: NativePhotoAuthorization? by mutableStateOf(null)
        private set
    internal fun configureRecentPhotos(context: android.content.Context,source: NativeRecentPhotoSource=NativeRecentPhotos(context)) {
        if(recentPhotos==null&&storage!=null)recentPhotos=NativeRecentPhotoLibrary(source,storage.child(context,"recent-photos"))
        if(photoAuthorization==null&&storage!=null)photoAuthorization=NativePhotoAuthorization(storage.child(context,"photo-permission"),source.permissions())
    }
    internal var documentPicker: NativeDocumentPicker? by mutableStateOf(null)
        private set
    internal fun configureDocumentPicker(context: android.content.Context) { if(documentPicker==null)documentPicker=NativeDocumentPicker(context,this,storage?.child(context,"document-results")) }
    internal fun acceptsAttachmentAction(original: NativePhotoRequest): Boolean=currentAttachmentImport(original.destination)&&isCurrent(original.destination.presentation)&&canCompose&&!importing&&!localRecoveryRequired
    internal fun clearAttachmentDraft(original: NativePhotoRequest) {
        if(!acceptsAttachmentAction(original)||submission.sending)return
        val key=original.destination.key
        text="";draftFiles[key]=emptyList();attachmentImportGenerations.remove(key);draftVersions[key]=UUID.randomUUID().toString();persistMetadata()
    }
    internal val attachmentErrorGeneration get()=errorRevision
    internal fun photoSelectionFailure(original: NativePhotoRequest,message: String) { reportAttachmentImportFailure(original.destination,original.errorGeneration,IllegalStateException(message)) }
    internal var photoPicker: NativePhotoPicker? by mutableStateOf(null)
        private set
    internal var cameraCapture: NativeCameraCapture? by mutableStateOf(null)
        private set
    internal fun configureCameraCapture(context: android.content.Context,access: NativeCameraAccess=AndroidCameraAccess(context)) { if(cameraCapture==null)cameraCapture=NativeCameraCapture(context,this,storage?.child(context,"camera-results"),access) }
    internal fun configurePhotoPicker(context: android.content.Context) { if(photoPicker==null)photoPicker=NativePhotoPicker(context,this,storage?.child(context,"photo-results")) }
    internal fun configureExternalInbox(context: android.content.Context) {
        if(externalInbox==null&&storage!=null)externalInbox=NativeExternalAttachments(context.applicationContext,storage.child(context,"external-inbox")) { foreground }
    }
    internal fun inboxFiles(destination: AttachmentImportDestination): List<CreationFile> {
        require(currentAttachmentImport(destination)) { NativeFileIntakeFailure.CHANGED }
        return draftFiles[destination.key].orEmpty().map { it.manifest }
    }
    internal suspend fun verifyInboxFile(file: CreationFile) {
        val repository=storage ?: throw NativeFileIntakeFailure(NativeFileIntakeFailure.UNAVAILABLE)
        NativeDraftFiles(repository).bytes(file)
    }
    internal suspend fun repairInboxFile(destination: AttachmentImportDestination,expected: CreationFile,input: LocalAttachmentInput) {
        require(inboxFiles(destination).any { it==expected }) { NativeFileIntakeFailure.CHANGED }
        val store=execution?.files ?: throw NativeFileIntakeFailure(NativeFileIntakeFailure.UNAVAILABLE)
        val result=store.prepare(input.name,input.mediaType,input.bytes,expected.id)
        try {
            require(result.file.manifest==expected&&currentAttachmentImport(destination)) { NativeFileIntakeFailure.CHANGED }
            // This ID was already referenced by the original draft; never discard its repaired bytes.
        } finally { result.close() }
    }
    internal suspend fun confirmInboxFiles(destination: AttachmentImportDestination,expected: List<CreationFile>): Boolean {
        if(!currentAttachmentImport(destination))return false
        val repository=storage ?: return false
        repository.awaitQueuedWrites()
        val confirmed=withContext(Dispatchers.IO) {
            val metadata=repository.metadata() ?: return@withContext false
            if(metadata.optJSONObject("attachmentImportGenerations")?.optString(destination.key)!=destination.generation)return@withContext false
            val rows=metadata.optJSONObject("draftFiles")?.optJSONArray(destination.key) ?: return@withContext false
            val persisted=(0 until rows.length()).map { n -> rows.getJSONObject(n).let { row -> CreationFile(row.getString("id"),row.getString("name"),row.getString("mediaType"),row.getLong("sizeBytes"),row.getString("sha256")) } }
            if(!expected.all { file -> persisted.any { it==file } })return@withContext false
            for(file in expected)NativeDraftFiles(repository).bytes(file)
            true
        }
        return confirmed&&currentAttachmentImport(destination)
    }
    internal suspend fun confirmPhotoFiles(destination: AttachmentImportDestination,expected: List<LocalDraftFile>): Boolean {
        if(!confirmInboxFiles(destination,expected.map {it.manifest}))return false
        val origins=withContext(Dispatchers.IO) {storage?.metadata()?.optJSONObject("photoSources")}
        return currentAttachmentImport(destination)&&expected.all {it.photoSourceId!=null&&origins?.optional(it.manifest.id)==it.photoSourceId}
    }
    fun attachmentDestination()=DraftDestination(targetKey,selectionRevision)
    fun isCurrent(destination: DraftDestination)=destination.key==targetKey && destination.selection==selectionRevision
    fun attachmentImportDestination(): AttachmentImportDestination {
        require(localReady&&canCompose&&!submission.sending) { "当前无法添加附件" }
        val key=targetKey
        val generation=attachmentImportGenerations.getOrPut(key) { UUID.randomUUID().toString() }
        persistMetadata()
        return AttachmentImportDestination(key,generation,DraftDestination(key,selectionRevision),maximum=5)
    }
    private fun currentAttachmentImport(destination: AttachmentImportDestination)=localReady &&
        AttachmentPreparationRules.canCommit(destination.key,destination.key,destination.generation,
            attachmentImportGenerations[destination.key],false)
    internal val attachmentStorageReady get()=localReady
    internal suspend fun confirmAttachmentOrigin(destination: AttachmentImportDestination): Boolean {
        if(!currentAttachmentImport(destination))return false
        val repository=storage ?: return false
        repository.awaitQueuedWrites()
        return withContext(Dispatchers.IO) { repository.metadata()?.optJSONObject("attachmentImportGenerations")?.optString(destination.key)==destination.generation }&&currentAttachmentImport(destination)
    }
    private fun reportAttachmentImportFailure(destination: AttachmentImportDestination,errorAtStart: Long,failure: Exception) {
        if(currentAttachmentImport(destination)&&isCurrent(destination.presentation)&&errorRevision==errorAtStart) {
            error=failure.message ?: "附件读取失败，原草稿已保留"
            localErrorProductKey=(failure as? NativeAttachmentQuotaFailure)?.key
        }
    }
    private fun startAttachmentImport(destination: AttachmentImportDestination,loaders: List<suspend ()->LocalAttachmentInput>,errorAtStart: Long,maximum: Int=destination.maximum,reportFailure: Boolean=true)=
        attachmentPreparation.start(destination,loaders,maximum) { if(reportFailure)reportAttachmentImportFailure(destination,errorAtStart,it) }
    fun importAttachments(destination: AttachmentImportDestination,loaders: List<suspend ()->LocalAttachmentInput>,errorAtStart: Long=errorRevision,maximum: Int=destination.maximum,reportFailure: Boolean=true): Deferred<Unit>? {
        return try { startAttachmentImport(destination,loaders,errorAtStart,maximum,reportFailure) }
            catch(failure: Exception) { if(reportFailure)reportAttachmentImportFailure(destination,errorAtStart,failure);null }
    }
    suspend fun addAttachment(name: String,mediaType: String,bytes: ByteArray,destination: AttachmentImportDestination=attachmentImportDestination()) {
        val captured=bytes.copyOf()
        startAttachmentImport(destination,listOf({ LocalAttachmentInput(name,mediaType,captured) }),errorRevision)?.await()
    }
    val canRemoveDraftAttachment get()=localReady&&!submission.sending&&documentPicker?.recovery?.blocksRemoval(targetKey,attachmentImportGenerations[targetKey])!=true&&photoPicker?.recovery?.blocksRemoval(targetKey,attachmentImportGenerations[targetKey])!=true&&cameraCapture?.recovery?.blocksRemoval(targetKey,attachmentImportGenerations[targetKey])!=true
    fun removeAttachment(id: String)=removeAttachment(id,attachmentDestination())
    fun removeAttachment(id: String,destination: DraftDestination) {
        if(!canRemoveDraftAttachment||!isCurrent(destination)||attachments.none { it.manifest.id==id })return
        draftFiles[targetKey]=attachments.filter { it.manifest.id!=id };draftVersions[targetKey]=UUID.randomUUID().toString();persistMetadata()
    }
    /** A captured current draft can read its authenticated display cache, never submission bytes. */
    suspend fun draftAttachmentThumbnail(file: LocalDraftFile,destination: DraftDestination): ByteArray? {
        if(!localReady||!isCurrent(destination)||attachments.none { it.manifest==file.manifest })return null
        val bytes=execution?.files?.thumbnail(file.manifest) ?: return null
        return if(localReady&&isCurrent(destination)&&attachments.any { it.manifest==file.manifest })bytes else null
    }
    /** Explicit local preview reads the original encrypted bytes under the captured draft. */
    internal suspend fun draftAttachmentImage(file: LocalDraftFile,destination: DraftDestination): android.graphics.Bitmap? {
        if(!file.manifest.mediaType.startsWith("image/")||!localReady||!isCurrent(destination)||attachments.none { it.manifest==file.manifest })return null
        val bytes=execution?.files?.bytes(file.manifest) ?: return null
        val image=NativeAttachmentImageDecoder.decode(bytes)
        return if(localReady&&isCurrent(destination)&&attachments.any { it.manifest==file.manifest })image else null
    }
    internal data class MessageLinkContext(val key: String,val selection: Long,val connection: Long,val message: MobileMessage)
    internal fun messageLinkContext(message: MobileMessage)=MessageLinkContext(targetKey,selectionRevision,revisions[selectedClient.id] ?: 0,message)
    internal fun messageLinkCurrent(context: MessageLinkContext)=context.key==targetKey&&context.selection==selectionRevision&&
        context.connection==(revisions[selectedClient.id] ?: 0)&&messages.any { it==context.message }
    internal data class PreparedMessageResource(val name: String,val mediaType: String,val local: java.io.File)
    internal suspend fun prepareMessageResource(owner: MessageLinkContext,reference: String,cache: java.io.File): PreparedMessageResource {
        if(!messageLinkCurrent(owner))throw CodexCommandError("文件不属于当前电脑和对话",appOwnedCopy=true)
        val client=selectedClient;val thread=selectedThread
        val destination=MessageLinkDestination.resolve(reference)
        val file=when(destination.kind) {
            "attachment" -> owner.message.attachments.firstOrNull { candidate->
                MessageLinkDestination.sameAttachmentId(candidate.id,destination.attachmentId)
            } ?: throw CodexCommandError("此文件尚未由电脑提供，请刷新对话后重试。",appOwnedCopy=true)
            "computerFile" -> commands.resolveMessageFile(client,thread,owner.message.id,reference)
            else -> throw CodexCommandError("手机暂不支持此类链接，可复制后在对应应用中打开。",appOwnedCopy=true)
        }
        if(!messageLinkCurrent(owner)||file.hostId!=client.id||file.threadId!=thread)throw CodexCommandError("文件不属于当前电脑和对话",appOwnedCopy=true)
        val local=commands.downloadResource(client,file,cache)
        if(!messageLinkCurrent(owner))throw CodexCommandError("文件不属于当前电脑和对话",appOwnedCopy=true)
        return PreparedMessageResource(file.name,file.mediaType,local)
    }
    private fun startEvents(initialReadGraceMillis: Long=0) {
        if(!foreground || incomingEasyWorkLink!=null || selectedClient.isDemo || selectedThread.isEmpty() || "events" !in workspace?.capabilities.orEmpty() || "commands:target-bound:v1" !in workspace?.capabilities.orEmpty()) return
        val client=selectedClient; val thread=selectedThread; val key=targetKey; val selection=selectionRevision; val revision=revisions[client.id] ?: 0
        val recoveryIdentity=Triple(key,selection,revision)
        if(historyRecoveryJob?.isActive!=true || historyRecoveryIdentity!=recoveryIdentity) {
            historyRecoveryJob?.cancel();historyRecoveryIdentity=recoveryIdentity
            val progressKey=conversationProgressKey(key,client)
            val graceUntil=android.os.SystemClock.elapsedRealtime()+initialReadGraceMillis
            historyRecoveryJob=tasks.launch {
                // Independent of long polls and receipt observers. Repeated cached
                // checkedAt values cannot postpone recovery, and unsuccessful reads
                // remain bounded to the active/idle attempt interval too.
                var attemptedAt=if(initialReadGraceMillis>0)null else android.os.SystemClock.elapsedRealtime()
                while(isActive && foreground && incomingEasyWorkLink==null && targetKey==key && selectionRevision==selection && (revisions[client.id] ?: 0)==revision) {
                    val active=runtime?.state=="running" || pendingRecords.isNotEmpty() || conversationSyncIssues[key]!=null
                    val now=android.os.SystemClock.elapsedRealtime()
                    if(!historyLoading && OperationPolicies.historyCatchUpDue(now,
                        usefulConversationProgress.lastUsefulAt(progressKey) ?: -1,attemptedAt ?: -1,active,graceUntil)) {
                        attemptedAt=now
                        loadHistory()?.join()
                    }
                    delay(1_000)
                }
            }
        }
        if(eventJob?.isActive==true)return
        val stream=Any();eventReadOwner=stream
        eventJob=tasks.launch {
            var epoch=historyPages[key]?.epoch.orEmpty(); var cursor=historyPages[key]?.cursor ?: 0
            var retryDelay=1_000L
            while(isActive && foreground && eventReadOwner===stream && targetKey==key && selectionRevision==selection && (revisions[client.id] ?: 0)==revision) {
                val successAtStart=connectionSuccesses[client.cacheKey(null)] ?: 0
                val snapshotAtStart=conversationSnapshotRevisions[key] ?: 0
                try {
                    val batch=commands.events(client,thread,epoch,cursor)
                    if(!foreground || eventReadOwner!==stream || targetKey!=key || selectionRevision!=selection || (revisions[client.id] ?: 0)!=revision) break
                    require(batch.hostId==client.id&&batch.snapshots.all { page -> page.hostId==client.id&&page.threadId==thread&&(!client.requiresReadTarget||page.scope!=null)&&sameScope(page.scope,client.scope) }) { "事件快照不属于当前电脑、对话和完整目标" }
                    val decision=OperationPolicies.event(epoch,cursor,batch.epoch,batch.cursor,batch.reset,batch.ready!=false,batch.snapshots.size)
                    if(batch.ready==false) { freshHistories[key]=false; freshWorkspaces[clientKey]=false }
                    batch.capabilities?.let { caps -> workspace?.let { old -> workspaces[clientKey]=old.copy(capabilities=SessionPolicies.codexCapabilities(caps,old.mode)) } }
                    if(decision.apply) {
                        epoch=decision.epoch; cursor=decision.cursor
                        batch.snapshots.forEach { page ->
                            val prior=historyPages[key]
                            val sameReadGeneration=loadedHistorySelections[key]==(selection to revision)
                            val compatiblePrefix=MobileHistoryWindowPolicy.compatiblePrefix(prior,page,histories[key].orEmpty(),sameReadGeneration)
                            val retain=com.agenthub.core.ReadingPolicy.preserveHistoryPrefix(page.windowed,pagedSelections.containsKey(key),
                                pagedSelections[key]==selection && sameReadGeneration,compatiblePrefix) || page.windowed&&chatSearchJump?.context?.key==key
                            val incoming=page.messages.map { it.id }.toSet()
                            val merged=if(retain)histories[key].orEmpty().takeWhile { it.id !in incoming }+page.messages else page.messages
                            val unchangedMessages=histories[key]==merged
                            val stored=if(retain)page.copy(nextCursor=prior?.nextCursor,wire=JSONObject(page.wire).put("nextCursor",prior?.nextCursor).toString()) else page
                            if(page.windowed&&!retain)pagedSelections.remove(key)
                            historyPages[key]=stored; histories[key]=merged; runs.remove(key)
                            loadedHistorySelections[key]=selection to revision
                            freshHistories[key]=batch.ready!=false
                            if(batch.ready!=false)readyHistorySelection=Triple(key,selection,revision)
                            receivedConversationSnapshot(key,client,page,batch.ready!=false)
                            persistHistory(key,client,thread,stored,merged,reuseUnchangedFragments=unchangedMessages)
                            operations[NativeOperationJournal.target(client,thread)].orEmpty().filter { it.kind=="reply" }.forEach { saved ->
                                page.runtime?.receipts?.firstOrNull { it.operationId==saved.id }?.let { receipt -> execution?.journal?.observed(saved.target,saved.id,receipt.state,encodeReceipt(receipt),receipt.error) }
                            }
                        }
                    }
                    if(decision.reload && batch.snapshots.isEmpty()) { freshHistories[key]=false; refresh(); loadHistory() }
                    if(batch.ready==true&&batch.snapshots.isNotEmpty()) {
                        recordConnectionSuccess(client)
                        replaceClient(selectedClient.copy(online=true))
                        if(freshWorkspaces[clientKey]!=true)refresh()
                    }
                    retryDelay=1_000L
                    loadOperations(); delay(if(batch.snapshots.isEmpty()) 300 else 50)
                } catch(cancel: CancellationException) { throw cancel }
                catch(failure: Exception) {
                    val classified=eventReadFailure(failure)
                    if(foreground&&eventReadOwner===stream&&targetKey==key&&selectionRevision==selection&&(revisions[client.id]?:0)==revision) {
                        if(!classified.transient||(conversationSnapshotRevisions[key] ?: 0)<=snapshotAtStart)freshHistories[key]=false
                        recordConnectionFailure(client,classified,successAtStart,displayError=false)
                    }
                    delay(retryDelay);retryDelay=minOf(retryDelay*2,15_000L)
                }
            }
        }
    }
    suspend fun connect(name: String, address: String, secret: String, editing: MobileClient? = null): MobileClient {
        val acceptanceTiming=NativeReadAcceptanceTiming.capture();val startedAt=android.os.SystemClock.elapsedRealtimeNanos()
        require(name.trim().isNotEmpty() && name.length <= 256) { "请输入客户端名称" }
        NativeHostReads.baseAddress(address)
        require(secret.isEmpty() || secret.length in 32..512 && secret.none { it.isWhitespace() }) { "客户端密钥格式无效" }
        val generation = editing?.let { revisions[it.id] ?: 0 }
        val candidate = editing?.copy(name = name.trim(), endpoint = address.trim()) ?: MobileClient("", name.trim(), endpoint = address.trim(), isDemo = false)
        val transport = providers.initialCodexReads(secret)
        val result = transport.workspace(candidate)
        require(editing == null || result.hostId == editing.id) { "电脑身份不匹配，未修改原连接" }
        require(editing == null || (revisions[editing.id] ?: 0) == generation) { "连接已改变，请重试" }
        val scope = result.scope ?: TargetScope("codex", null, null, result.hostId, result.hostId, result.mode)
        val prior = realClients.firstOrNull { it.id == result.hostId && sameScope(it.scope, scope) }
        val client = candidate.copy(id = result.hostId, scope = scope, online = true, mode = result.mode,
            readerId = editing?.readerId ?: prior?.readerId ?: UUID.randomUUID().toString(), requiresReadTarget=result.scope != null)
        withContext(Dispatchers.IO) { storage?.setToken(client.id, secret) }
        revisions[client.id] = (revisions[client.id] ?: 0) + 1
        val remembered = replaceClient(client); workspaces[remembered.cacheKey(null)] = result
        selectVerifiedClient(remembered);acceptanceTiming?.record(NativeReadAcceptanceTiming.Point.CONNECTION_SUCCESS,remembered.id,startedAtNanos=startedAt); return remembered
    }
    private fun replaceClient(client: MobileClient): MobileClient {
        val index = realClients.indexOfFirst { it.id == client.id }
        val prior=realClients.getOrNull(index)
        val remembered=if(client.storageIdentity==null && prior?.storageIdentity!=null && sameScope(client.scope,prior.scope)) client.copy(storageIdentity=prior.storageIdentity) else client
        if (index < 0) realClients.add(remembered) else realClients[index] = remembered
        if (selectedClient.id == client.id) { selectedClient = remembered;synchronizeReadingPresentation() }
        if(prior!=remembered)reconcilePhoneReading()
        return remembered
    }
    suspend fun credential(id: String): String = withContext(Dispatchers.IO) { storage?.token(id).orEmpty() }
    suspend fun createProject(name: String,directory: String): Boolean {
        require(canCreateProject) { "此电脑尚未开放项目创建" };val flow=execution?:error("本地安全存储不可用")
        val client=selectedClient;val key=targetKey;val target=NativeOperationJournal.target(client,null)
        val previous=flow.journal.records(target).lastOrNull { it.kind=="project-create"&&!it.detached&&it.phase !in listOf("confirmed","failed","rejected") }
        require(previous==null) { "先查询原项目创建记录或结束等待，原操作不会重放。" }
        val workspace=reads.workspace(client);require(sameScope(workspace.scope,client.scope)&&workspace.runtimeConnected==true&&"projects:create" in workspace.capabilities)
        val id=UUID.randomUUID().toString();val intent=JSONObject().put("version",1).put("hostId",client.id).put("clientOperationId",id).put("name",name).put("directory",directory)
        var record=flow.journal.prepare(client,null,"project-create",intent,key,version(key));val owner=beginBusy(key,id,"添加项目…")
        try {
            require(targetKey==key&&sameScope(selectedClient.scope,client.scope)) { "目标已改变，原意图已保留" }
            record=flow.journal.markSubmitting(target,id)
            val project=commands.createProject(client,name,directory,id)
            acceptFlow(client,CodexFlowResult(flow.journal.observed(target,id,"confirmed",encodeProject(project)),project=project));return true
        } catch(cancel: CancellationException) { throw cancel }
        catch(failure: Exception) {
            val rejected=failure is AgentOperationNotSubmitted
            if(rejected) { // The adapter has not dispatched project/create.
                record=flow.journal.projectNotSubmitted(record.target,id,failure.message)
            } else record=flow.journal.uncertain(record.target,id,failure.message)
            operations[target]=flow.journal.records(target);throw failure
        } finally { endBusy(key,owner);loadOperations() }
    }
    suspend fun connectEasyWorkQR(text: String,name: String,onPending: (String)->Unit = {}): MobileClient {
        val presentation=incomingEasyWorkLink?.presentation
        if(android.net.Uri.parse(text.trim()).scheme=="easywork") {
            val offer=EasyWorkDeviceOffer.parse(text)
            return connectEasyWorkCode(name,offer.origin,offer.code,onPending).also { dismissIncomingLink(presentation) }
        }
        val identity=EasyWorkIdentity.load(storage?:error("本地安全存储不可用"))
        val (binding,candidates)=EasyWorkPairing.lan(text,android.os.Build.MODEL,identity)
        return saveEasyWork(name,binding,candidates).also { dismissIncomingLink(presentation) }
    }
    suspend fun connectEasyWorkCode(name: String,endpoint: String,code: String,onPending: (String)->Unit = {}): MobileClient {
        val offer=EasyWorkDeviceOffer(EasyWorkDeviceOffer.origin(endpoint),EasyWorkDeviceOffer.code(code))
        val (binding,ref)=easyWorkDeviceAuthorization!!.pair(offer,onPending)
        return saveEasyWork(name,binding,listOf(AgentCandidate("relay","relay-http",offer.origin)),grantRef=ref)
    }
    suspend fun connectEasyWorkRelay(name: String,endpoint: String,code: String,token: String?): MobileClient {
        val identity=EasyWorkIdentity.load(storage?:error("本地安全存储不可用"));val login=easyWorkLogin!!
        val access=login.validToken(token)
        val binding=EasyWorkPairing.relay(endpoint,code,identity) { access }
        return saveEasyWork(name,binding,listOf(AgentCandidate("relay","relay-http",NativeHostReads.baseAddress(endpoint).toString().trimEnd('/'))),access)
    }
    internal suspend fun saveEasyWork(name: String,binding: EasyWorkBinding,candidates: List<AgentCandidate>,token: String?=null,grantRef: String?=null): MobileClient {
        require(name.isNotBlank()&&name.length<=256);val store=storage?:error("本地安全存储不可用")
        val identity=EasyWorkIdentity.load(store);binding.verify(identity)
        val scope=binding.target.scope();val id="easywork-"+easyHash(easyCanonical(CodexCommandCodec.target(scope)).toByteArray())
        val existing=realClients.firstOrNull { it.id==id };val revision=revisions[id]?:0L
        val ref=existing?.agentConnection?.credentialRef?:"agent-$id"
        val previous=withContext(Dispatchers.IO) { store.read("easywork-credential:$ref") }
        val old=previous?.let { EasyWorkCredential.decode(JSONObject(it)) }
        require(old==null||old.binding.target==binding.target&&old.privateKey==identity.privateKey)
        val legacy=old?.takeIf { it.grantRef==null }
        val credential=if(grantRef!=null) EasyWorkCredential(binding,identity.privateKey,relayBinding=binding,grantRef=grantRef)
            else if(candidates.first().transportId=="relay-http") (legacy?:EasyWorkCredential(binding,identity.privateKey)).copy(relayBinding=binding,accessToken=token)
            else (legacy?:EasyWorkCredential(binding,identity.privateKey)).copy(binding=binding)
        val pin=candidates.firstOrNull { it.transportId=="lan-wss" }?.peerFingerprint
        val saved=existing?.agentConnection?.candidates.orEmpty().filter { (grantRef!=null||old?.grantRef==null)&&(it.transportId!="lan-wss"||pin==null||it.peerFingerprint==pin) }
        val merged=(candidates+saved).distinctBy { it.transportId to it.endpoint }.take(32)
        var connection=AgentConnection(scope,merged,ref,existing?.agentConnection?.preference?:"automatic")
        easyWorkSessions!!.remove(id)
        val authorization: EasyWorkRequestHeaders?=grantRef?.let { grant->{ method,url,body->easyWorkDeviceAuthorization!!.headers(grant,binding,method,url,body) } }
        val incoming=EasyWorkRemoteSession(connection.copy(candidates=candidates,preference="automatic"),credential,{ easyWorkLogin!!.validToken(token,binding.target) },authorize=authorization)
        try {
            val context=incoming.request("runtime/context");require(EasyWorkTarget.decode(context.getJSONObject("target"))==binding.target)
            if(grantRef!=null) {
                val routes=context.optJSONArray("connectionCandidates")?.objects().orEmpty();require(routes.size<=32)
                val authenticated=routes.flatMap { route->
                    require(route.getString("transportId")=="lan-wss")
                    EasyWorkPairing.directCandidates(listOf(route.getString("endpoint")),fingerprint=route.getString("peerFingerprint"))
                }
                connection=connection.copy(candidates=(authenticated+connection.candidates).distinctBy { it.transportId to it.endpoint }.take(32))
            }
        } finally { incoming.close() }
        require((revisions[id]?:0L)==revision) { "连接已改变，请重试" }
        var client=MobileClient(id,name.trim(),scope,candidates.first().endpoint,false,"easywork-runtime",false,requiresReadTarget=true,agentConnection=connection)
        withContext(Dispatchers.IO) { store.write("easywork-credential:$ref",credential.json().toString()) }
        try {
            val workspace=reads.workspace(client);require((revisions[id]?:0L)==revision&&sameScope(workspace.scope,scope))
            client=client.copy(online=true);replaceClient(client);revisions[id]=revision+1
            workspaces[MobileRules.cacheKey(id,scope,null,null)]=workspace;select(client);easyWorkDiscovery?.scan();return client
        } catch(failure: Exception) {
            easyWorkSessions.remove(id);withContext(NonCancellable+Dispatchers.IO) { if(previous==null)store.remove("easywork-credential:$ref") else store.write("easywork-credential:$ref",previous) };throw failure
        }
    }
    fun setEasyWorkPreference(client: MobileClient,preference: String) {
        require(preference in listOf("automatic","lan-only","relay-only"));val latest=realClients.firstOrNull { it.id==client.id }?:return
        val connection=latest.agentConnection?:return;replaceClient(latest.copy(agentConnection=connection.copy(preference=preference),online=false))
        val revision=(revisions[client.id]?:0)+1;revisions[client.id]=revision;persistMetadata()
        val oldWorkspace=workspaceJob.takeIf { selectedClient.id==client.id }
        val oldHistory=historyJob.takeIf { selectedClient.id==client.id }
        val oldEvents=eventJob.takeIf { selectedClient.id==client.id }
        tasks.launch {
            // Join the prior read before refreshing; its finalizer owns the loading flag.
            oldWorkspace?.cancelAndJoin();oldHistory?.cancelAndJoin();oldEvents?.cancelAndJoin()
            if(eventJob===oldEvents)eventJob=null
            easyWorkSessions?.remove(client.id)
            if(selectedClient.id==client.id&&revisions[client.id]==revision) { refresh();loadHistory() }
        }
    }
    fun renameEasyWork(client: MobileClient,name: String) {
        if(name.isBlank()||name.length>256)return
        realClients.firstOrNull { it.id==client.id&&it.agentConnection!=null }?.let { replaceClient(it.copy(name=name.trim()));persistMetadata() }
    }
    internal suspend fun updateEasyWorkCandidates(client: MobileClient,binding: EasyWorkBinding,candidates: List<AgentCandidate>): Boolean {
        val current=realClients.firstOrNull { it.id==client.id }?:return false;val connection=current.agentConnection?:return false
        if(!sameScope(connection.target,binding.target.scope()))return false
        val saved=withContext(Dispatchers.IO) { storage?.read("easywork-credential:${connection.credentialRef}") }?:return false
        if(EasyWorkCredential.decode(JSONObject(saved)).binding!=binding)return false
        val merged=(candidates+connection.candidates).distinctBy { it.transportId to it.endpoint }.take(32)
        if(merged!=connection.candidates) { replaceClient(current.copy(agentConnection=connection.copy(candidates=merged)));persistMetadata() }
        return true
    }
    suspend fun connectDiscoveredEasyWork(host: DiscoveredEasyWorkHost): MobileClient {
        val selection=selectionRevision; val sourceKey=targetKey; val sourceVersion=version(sourceKey)
        val identity=EasyWorkIdentity.load(storage?:error("本地安全存储不可用"))
        val (binding,candidates)=EasyWorkPairing.discovered(host.endpoint,android.os.Build.MODEL,identity,host.deviceId) {
            require(selectionRevision==selection && targetKey==sourceKey && version(sourceKey)==sourceVersion) { "所选目标或草稿已改变，未申请凭据" }
        }
        require(host.deviceId==null||host.deviceId==binding.target.desktopDeviceId) { "发现的电脑身份已变化" }
        require(selectionRevision==selection && targetKey==sourceKey && version(sourceKey)==sourceVersion) { "所选目标或草稿已改变，未替换连接" }
        return saveEasyWork(host.name,binding,candidates)
    }
    suspend fun connectKnownEasyWork(address: String): MobileClient {
        val selection=selectionRevision; val sourceKey=targetKey; val sourceVersion=version(sourceKey)
        val endpoint=CodexEndpoints.local(address)
        val known=realClients.firstOrNull { it.agentConnection?.target?.agentId=="easywork"&&it.agentConnection.candidates.any { c->c.transportId=="lan-wss"&&c.endpoint==endpoint } }?:error("首次连接请扫描电脑二维码")
        val snapshot=reads.workspace(known)
        require(selectionRevision==selection && targetKey==sourceKey && version(sourceKey)==sourceVersion && realClients.any { it==known }) { "所选目标、草稿或连接已改变" }
        val client=known.copy(online=true);replaceClient(client);workspaces[client.cacheKey(null)]=snapshot;select(client);return client
    }
    fun dismissIncomingLink(presentation: String?) { if(presentation!=null&&incomingEasyWorkLink?.presentation==presentation) { incomingEasyWorkLink=null;if(hasClient) { refresh();loadHistory() } } }
    fun receiveLink(uri: android.net.Uri) {
        if(uri.scheme in listOf("easywork-host","easywork")&&uri.host=="pair") { incomingEasyWorkLink=PendingAgentLink(payload=uri.toString());selectionRevision++;workspaceJob?.cancel();historyJob?.cancel();eventJob?.cancel();historyRecoveryJob?.cancel();return }
        if(uri.scheme=="com.easyai.remote"&&uri.host=="oauth")tasks.launch { try { easyWorkLogin?.callback(uri) } catch(failure: Exception) { error=failure.message?:"登录未完成" } }
    }
    internal fun storedCredential(id: String): String = storage?.token(id).orEmpty()
    suspend fun connectKnownCodex(host: DiscoveredCodexHost) {
        var client = realClients.firstOrNull { it.id == host.id && it.scope?.agentId == "codex" } ?: error("原连接已移除")
        val selection = selectionRevision; val sourceKey = targetKey; val sourceVersion = version(sourceKey)
        if (host.reachable && client.codexDirect != null) {
            val direct = client.codexDirect!!
            val descriptor = CodexDiscoveryAPI.discoverForConnection(host.endpoint, CodexPublicSource(direct.target, direct.fingerprint, direct.routes.map { it.endpoint }))
            require(updateCodexRoute(client, host.endpoint, descriptor)) { "连接已变化，原连接已保留" }
            client = realClients.first { it.id == host.id }
        }
        val revision = revisions[host.id] ?: 0
        val snapshot = reads.workspace(client)
        require(selectionRevision == selection && targetKey == sourceKey && version(sourceKey) == sourceVersion && (revisions[host.id] ?: 0) == revision && realClients.any { it == client }) { "所选目标或连接已改变" }
        val connected = replaceClient(client.copy(online = true))
        workspaces[connected.cacheKey(null)] = snapshot
        selectVerifiedClient(connected)
    }
    suspend fun connectCodexDiscovered(host: DiscoveredCodexHost, hostKey: String = ""): MobileClient {
        val store = storage ?: error("本地安全存储不可用")
        val prior = realClients.firstOrNull { it.id == host.id }
        val revision = revisions[host.id] ?: 0
        val selection = selectionRevision; val sourceKey = targetKey; val sourceVersion = version(sourceKey)
        val identity = EasyWorkIdentity.load(store)
        val authorized = CodexDiscoveryAPI.authorize(host, identity, store, hostKey) { descriptor ->
            require(prior == null || sameScope(prior.scope, descriptor.target)) { "电脑运行时身份已变化，原连接已保留" }
            require(prior?.codexDirect == null || prior.codexDirect.fingerprint == descriptor.fingerprint) { "已配对电脑证书发生变化，原连接已保留" }
            require((revisions[host.id] ?: 0) == revision && selectionRevision == selection && targetKey == sourceKey && version(sourceKey) == sourceVersion) { "连接或所选目标已改变，未申请凭据" }
        }
        val target = authorized.descriptor.target
        require(prior == null || sameScope(prior.scope, target)) { "电脑运行时身份已变化，原连接已保留" }
        require(prior?.codexDirect == null || prior.codexDirect.fingerprint == authorized.descriptor.fingerprint) { "已配对电脑证书发生变化，原连接已保留" }
        require((revisions[host.id] ?: 0) == revision && selectionRevision == selection && targetKey == sourceKey && version(sourceKey) == sourceVersion) { "连接或所选目标已改变，未替换原连接" }
        val routes = (listOf(CodexRoute(CodexEndpoints.local(host.endpoint), host.networkKind ?: "unknown")) + authorized.descriptor.routes).distinctBy { it.endpoint }.take(32)
        val client = MobileClient(id=host.id, name=prior?.name ?: authorized.descriptor.name, scope=target, endpoint=routes.first().endpoint,
            online=true, mode="desktop-runtime", isDemo=false, readerId=prior?.readerId ?: UUID.randomUUID().toString(), requiresReadTarget=true,
            codexDirect=PairedCodexConnection(target, authorized.descriptor.fingerprint, (routes + prior?.codexDirect?.routes.orEmpty()).distinctBy { it.endpoint }.take(32),
                com.agenthub.core.CodexRouteRules.mergeSystemEndpoints(prior?.codexDirect?.systemEndpoints.orEmpty(), authorized.descriptor.systemEndpoints, prior?.endpoint?.takeIf { address -> prior.codexDirect?.routes?.none { it.endpoint == address } != false })), storageIdentity=prior?.storageIdentity)
        // No suspension between the final generation check, atomic credential/client publication and UI commit.
        CodexConnectionVault(store).save(client, authorized.token)
        revisions[host.id] = revision + 1
        val remembered = replaceClient(client)
        workspaces[remembered.cacheKey(null)] = authorized.workspace
        selectVerifiedClient(remembered); discovery?.scan()
        return remembered
    }
    suspend fun connectCodexQR(text: String, name: String): MobileClient {
        val offer = CodexDescriptor.parsePairing(text)
        require(name.length <= 256) { "客户端名称过长" }
        val prior = realClients.firstOrNull { it.id == offer.target.hostId }
        require(prior == null || sameScope(prior.scope, offer.target)) { "电脑运行时身份发生变化，原连接已保留" }
        val revision = revisions[offer.target.hostId] ?: 0
        var last: Exception = HostReadError("无法连接电脑，请检查局域网或 VPN")
        for(route in offer.routes) {
            try {
                val direct = PairedCodexConnection(offer.target, offer.fingerprint, offer.routes)
                var client = MobileClient(offer.target.hostId, name.trim().ifEmpty { offer.name }, offer.target, route.endpoint, false, "desktop-runtime", false, prior?.readerId ?: UUID.randomUUID().toString(), true, direct)
                val descriptor = NativeHostReads { "" }.connectionDescriptor(client)
                val merged = (listOf(route) + descriptor.routes + offer.routes).distinctBy { it.endpoint }.take(32)
                client = client.copy(codexDirect = direct.copy(routes = merged))
                val snapshot = NativeHostReads { offer.token!! }.workspace(client)
                val supplied = NativeHostReads { offer.token!! }.connectionRoutes(client, offer.target)
                client = client.copy(codexDirect = direct.copy(routes = (merged + prior?.codexDirect?.routes.orEmpty()).distinctBy { it.endpoint }.take(32), systemEndpoints =
                    com.agenthub.core.CodexRouteRules.mergeSystemEndpoints(prior?.codexDirect?.systemEndpoints.orEmpty(), supplied, prior?.endpoint?.takeIf { address -> prior.codexDirect?.routes?.none { it.endpoint == address } != false })))
                require(snapshot.mode == "desktop-runtime" && sameScope(snapshot.scope, offer.target)) { "电脑运行时身份不匹配" }
                require((revisions[client.id] ?: 0) == revision) { "连接已改变，请重试" }
                withContext(Dispatchers.IO) { storage?.setToken(client.id, offer.token!!) }
                client = client.copy(online = true)
                revisions[client.id] = revision + 1; client = replaceClient(client)
                workspaces[client.cacheKey(null)] = snapshot
                selectVerifiedClient(client); discovery?.scan(); return client
            } catch(cancel: CancellationException) { throw cancel }
            catch(failure: Exception) { last = failure }
        }
        throw last
    }
    internal fun updateCodexRoute(original: MobileClient, endpoint: String, descriptor: CodexDescriptor): Boolean {
        val current = realClients.firstOrNull { it.id == original.id } ?: return false
        if(current.endpoint != original.endpoint || current.codexDirect != original.codexDirect || !sameScope(current.scope, original.scope) || !sameScope(descriptor.target, current.scope)) return false
        if(current.codexDirect != null && current.codexDirect.fingerprint != descriptor.fingerprint) return false
        val preferred=descriptor.routes.firstOrNull { it.endpoint==endpoint } ?: current.codexDirect?.routes?.firstOrNull { it.endpoint==endpoint } ?: CodexRoute(endpoint)
        val legacy = current.endpoint?.takeIf { address -> current.codexDirect?.routes?.none { it.endpoint == address } != false }
        val systems = com.agenthub.core.CodexRouteRules.mergeSystemEndpoints(current.codexDirect?.systemEndpoints.orEmpty(), descriptor.systemEndpoints, legacy)
        val directRoutes = ((if (endpoint in systems) emptyList() else listOf(preferred)) + descriptor.routes + current.codexDirect?.routes.orEmpty()).distinctBy { it.endpoint }.take(32)
        val direct = PairedCodexConnection(descriptor.target, descriptor.fingerprint, directRoutes, systems)
        val changed=endpoint != current.endpoint || direct != current.codexDirect
        val transportChanged=endpoint != current.endpoint
        val updated=current.copy(endpoint=endpoint,codexDirect=direct)
        if(changed) {
            try { storage?.let { CodexConnectionVault(it).updateClient(updated) } } catch(_: Exception) { storageFailure();return false }
            // Verified address hints do not change the authenticated transport or invalidate its in-flight reply.
            if(transportChanged) revisions[current.id] = (revisions[current.id] ?: 0) + 1
        }
        replaceClient(updated); persistMetadata()
        if(transportChanged && selectedClient.id==current.id) {
            workspaceJob?.cancel();historyJob?.cancel();eventJob?.cancel();eventJob=null
            freshWorkspaces[clientKey]=false;freshHistories[targetKey]=false
            loadingTargets.remove(clientKey);historyTargets.remove(targetKey)
            refresh();loadHistory()
        }
        return true
    }
    fun removeClient(id: String) {
        val removed=realClients.firstOrNull { it.id==id } ?: return
        // A removed private client must not be recovered from its atomic credential record after a crash.
        if(removed.agentConnection==null) try { storage?.setToken(id,"") } catch(_: Exception) { storageFailure();return }
        revisions[id] = (revisions[id] ?: 0) + 1
        connectionSuccesses.remove(removed.cacheKey(null))
        phoneReading.lifecycle.leave();attemptedPhoneReading=null
        phoneReadingAcknowledgements.values.forEach { it.cancel() };phoneReadingAcknowledgements.clear()
        realClients.removeAll { it.id == id };reconcilePhoneReading()
        if (selectedClient.id == id) select(realClients.firstOrNull() ?: DemoMobileData.clients.first())
        tasks.launch(Dispatchers.IO) {
            easyWorkSessions?.remove(id)
            removed?.agentConnection?.let { storage?.remove("easywork-credential:${it.credentialRef}") }
            if(removed.agentConnection!=null) storage?.setToken(id, "")
        }
        persistMetadata()
    }
    private fun storageFailure() { tasks.launch { error = "本地保存失败，请保留当前内容并检查手机存储" } }
    private fun persistMetadata() {
        if (storage == null || !localReady) return
        val json = JSONObject().put("version", 1).put("appearance", appearance).put("language",language).put("demo", demo).put("selected", selectedClient.id)
            .put("clients", JSONArray(realClients.map(::encodeClient)))
            .put("drafts", JSONObject(drafts.toMap())).put("selectedThreads", JSONObject(selectedThreads.toMap()))
            .put("draftVersions",JSONObject(draftVersions.toMap())).put("attachmentImportGenerations",JSONObject(attachmentImportGenerations.toMap())).put("newProjects",JSONObject(newProjects.toMap()))
            .put("draftFiles",JSONObject(draftFiles.mapValues { JSONArray(it.value.map { f -> f.manifest.body() }) })).put("photoSources",NativePhotoOrigins.encode(draftFiles.values.flatten()))
            .put("generation",JSONObject(generation.mapValues { it.value.append(JSONObject()) })).put("replyModes",JSONObject(preferredModes.toMap()))
            .put("generationPreferences",JSONObject(generationPreferences.mapValues { it.value.append(JSONObject()) }))
            .put("dismissedReceiptFailures",JSONObject(dismissedReceiptFailures.mapValues { JSONArray(it.value.toList()) }))
            .put("expanded", JSONObject(expandedProjects.mapValues { JSONArray(it.value.toList()) }))
            .put("sidebar", JSONObject(sidebarStates.mapValues { (_,state) -> JSONObject().put("recent",state.recentExpanded).put("projects",state.projectsExpanded).put("lists",JSONArray(state.expandedLists.toList()))
                .put("pinnedSection",state.pinnedSectionExpanded).put("recentSection",state.recentSectionExpanded) }))
            .put("workspaces", JSONObject(workspaces.mapValues { JSONObject(it.value.wire) }))
        storage.enqueue("metadata", json.toString()) { storageFailure() }
    }
    private fun persistHistory(key: String, client: MobileClient, thread: String, result: MobileHistory, messages: List<MobileMessage>, reuseUnchangedFragments: Boolean=false) {
        if (storage == null) return
        val snapshot=messages.toList()
        storage.enqueueSnapshot("history:$key",{ NativeHistoryCache(storage).save(key,client,thread,result,snapshot,reuseUnchangedFragments) }) { storageFailure() }
    }

    private fun loadCachedHistory() {
        val acceptanceTiming=NativeReadAcceptanceTiming.capture()
        val storage = storage ?: return; val key = targetKey; val selection = selectionRevision
        if (histories.containsKey(key) || selectedThread.isEmpty()) return
        tasks.launch {
            try {
                val page = withContext(Dispatchers.IO) { NativeHistoryCache(storage).read(key) } ?: return@launch
                if (targetKey == key && selectionRevision == selection && !histories.containsKey(key) && page.hostId == selectedClient.id && page.threadId == selectedThread && (!selectedClient.requiresReadTarget || sameScope(page.scope, selectedClient.scope))) { releasePhoneReadingProof();acceptanceTiming?.record(NativeReadAcceptanceTiming.Point.CACHE_APPLYING,page.hostId,page.threadId); histories[key] = page.messages; historyPages[key] = page }
            } catch (_: Exception) { if (targetKey == key) error = "本地缓存无法读取，原缓存已保留" }
            catch (_: OutOfMemoryError) { if(targetKey == key) error = "本地缓存内存不足，原缓存已保留" }
        }
    }
    fun retryLocalRecovery() { if(localRecoveryRequired&&!restoring) restore() }
    fun restore() {
        val storage=storage ?: return
        if(restoreJob?.isActive==true) return
        phoneReadingEpoch++;phoneReading.reset();phoneReadingProof=null;phoneReadingFingerprint.clear();attemptedPhoneReading=null;cachedPhoneReading=null;phoneWorkspaceHead=null
        phoneReadingAcknowledgements.values.forEach { it.cancel() };phoneReadingAcknowledgements.clear()
        restoring=true;localRecoveryAllowsAttachmentStaging=false
        val generation=storage.beginRestore()
        restoreJob=tasks.launch {
            var authenticatedParentRead=false
            try {
                aiSharing.restore()
                val applyState=withContext(Dispatchers.IO) {
                    val raw=storage.read("metadata")
                    authenticatedParentRead=raw!=null
                    val json=raw?.let(::JSONObject)
                    val privateClients=CodexConnectionVault(storage).clients()
                    val realClients=mutableListOf<MobileClient>()
                    var appearance="system";var language="system";var demo=false
                    var selectedClient=DemoMobileData.clients.first()
                    val drafts=this@MobileSession.drafts.toMutableMap().apply { clear() }
                    val draftVersions=this@MobileSession.draftVersions.toMutableMap().apply { clear() }
                    val newProjects=this@MobileSession.newProjects.toMutableMap().apply { clear() }
                    val draftFiles=this@MobileSession.draftFiles.toMutableMap().apply { clear() }
                    val attachmentImportGenerations=this@MobileSession.attachmentImportGenerations.toMutableMap().apply { clear() }
                    val generation=this@MobileSession.generation.toMutableMap().apply { clear() }
                    val generationPreferences=this@MobileSession.generationPreferences.toMutableMap().apply { clear() }
                    val dismissedReceiptFailures=this@MobileSession.dismissedReceiptFailures.toMutableMap().apply { clear() }
                    val preferredModes=this@MobileSession.preferredModes.toMutableMap().apply { clear() }
                    val selectedThreads=this@MobileSession.selectedThreads.toMutableMap().apply { clear() }
                    val expandedProjects=this@MobileSession.expandedProjects.toMutableMap().apply { clear() }
                    val sidebarStates=this@MobileSession.sidebarStates.toMutableMap().apply { clear() }
                    val workspaces=this@MobileSession.workspaces.toMutableMap().apply { clear() }
                    if (json != null) {
                        require(json.getInt("version") == 1)
                        val storageKeys=listOf("drafts","draftVersions","draftFiles","attachmentImportGenerations","selectedThreads","newProjects","generation","generationPreferences","replyModes","dismissedReceiptFailures","expanded","sidebar","workspaces").flatMap { field -> json.optJSONObject(field)?.keys()?.asSequence()?.toList().orEmpty() }.distinct()
                        realClients.clear(); realClients.addAll(json.getJSONArray("clients").objects().map(::decodeClient).map { it.copy(online = false, storageIdentity=it.storageIdentity?.withKeys(storageKeys)) })
                        appearance = json.getString("appearance");language=NativeAppLanguage.from(json.optString("language","system")).value; demo = json.getBoolean("demo")
                        json.getJSONObject("drafts").let { v -> v.keys().forEach { drafts[it] = v.getString(it) } }
                        json.optJSONObject("draftVersions")?.let { v -> v.keys().forEach { draftVersions[it]=v.getString(it) } }
                        json.optJSONObject("attachmentImportGenerations")?.let { v -> v.keys().forEach { attachmentImportGenerations[it]=v.getString(it) } }
                        json.optJSONObject("newProjects")?.let { v -> v.keys().forEach { newProjects[it]=v.optional(it) } }
                        json.optJSONObject("draftFiles")?.let { v -> v.keys().forEach { key -> draftFiles[key]=v.getJSONArray(key).objects().map { f -> NativePhotoOrigins.attach(LocalDraftFile(CreationFile(f.getString("id"),f.getString("name"),f.getString("mediaType"),f.getLong("sizeBytes"),f.getString("sha256"))),json.optJSONObject("photoSources")) } } }
                        NativePhotoOrigins.validate(json.optJSONObject("photoSources"),draftFiles.values.flatten())
                        json.optJSONObject("generation")?.let { v -> v.keys().forEach { key -> val s=v.getJSONObject(key); generation[key]=GenerationSettings(s.optional("model"),s.optional("effort"),s.optional("permissionMode"),s.optional("speed"),s.optional("collaborationMode")) } }
                        json.optJSONObject("generationPreferences")?.let { v -> v.keys().forEach { key -> val g=v.getJSONObject(key); generationPreferences[key]=GenerationSettings(g.optional("model"),g.optional("effort"),g.optional("permissionMode"),g.optional("speed")) } }
                        json.optJSONObject("dismissedReceiptFailures")?.let { v -> v.keys().forEach { key -> dismissedReceiptFailures[key]=v.getJSONArray(key).strings().toSet() } }
                        json.optJSONObject("replyModes")?.let { v -> v.keys().forEach { preferredModes[it]=v.getString(it) } }
                        json.getJSONObject("selectedThreads").let { v -> v.keys().forEach { selectedThreads[it] = v.getString(it) } }
                        json.getJSONObject("expanded").let { v -> v.keys().forEach { expandedProjects[it] = v.getJSONArray(it).strings().toSet() } }
                        json.optJSONObject("sidebar")?.let { v -> v.keys().forEach { key -> val state=v.getJSONObject(key); sidebarStates[key]=SidebarDisplayState(state.getBoolean("recent"),state.getBoolean("projects"),state.getJSONArray("lists").strings().toSet(),
                            state.optBoolean("pinnedSection",true),state.optBoolean("recentSection",true)) } }
                        json.getJSONObject("workspaces").let { v -> v.keys().forEach { workspaces[it] = MobileSnapshotCodec.workspace(v.getJSONObject(it).toString()) } }
                        selectedClient = (realClients+if(demo) DemoMobileData.clients else emptyList()).firstOrNull { it.id == json.getString("selected") } ?: (realClients+if(demo) DemoMobileData.clients else emptyList()).firstOrNull() ?: DemoMobileData.clients.first()
                    }
                    // The credential and verified client survive even if the later ordinary metadata write never ran.
                    privateClients.forEach { verified ->
                        val index=realClients.indexOfFirst { it.id==verified.id }
                        if(index<0) realClients.add(verified.copy(online=false)) else {
                            val prior=realClients[index]
                            require(sameScope(prior.scope,verified.scope) && (prior.codexDirect==null || prior.codexDirect.fingerprint==verified.codexDirect?.fingerprint))
                            // Connection+credential are authoritative together; newer UI name/storage choices remain ordinary metadata.
                            realClients[index]=prior.copy(scope=verified.scope,endpoint=verified.endpoint,codexDirect=verified.codexDirect,
                                mode=verified.mode,isDemo=false,online=false,requiresReadTarget=true,readerId=prior.readerId ?: verified.readerId)
                        }
                    }
                    if (json == null && privateClients.isNotEmpty()) selectedClient = realClients.first()
                    else realClients.firstOrNull { it.id==selectedClient.id }?.let { selectedClient=it }

                    val publish: ()->Unit = {
                        this@MobileSession.realClients.apply { clear();addAll(realClients) }
                        this@MobileSession.appearance=appearance;this@MobileSession.language=language;this@MobileSession.demo=demo
                        this@MobileSession.drafts.apply { clear();putAll(drafts) }
                        this@MobileSession.draftVersions.apply { clear();putAll(draftVersions) }
                        this@MobileSession.newProjects.apply { clear();putAll(newProjects) }
                        this@MobileSession.draftFiles.apply { clear();putAll(draftFiles) }
                        this@MobileSession.attachmentImportGenerations.apply { clear();putAll(attachmentImportGenerations) }
                        this@MobileSession.generation.apply { clear();putAll(generation) }
                        this@MobileSession.generationPreferences.apply { clear();putAll(generationPreferences) }
                        this@MobileSession.dismissedReceiptFailures.apply { clear();putAll(dismissedReceiptFailures) }
                        this@MobileSession.preferredModes.apply { clear();putAll(preferredModes) }
                        this@MobileSession.selectedThreads.apply { clear();putAll(selectedThreads) }
                        this@MobileSession.expandedProjects.apply { clear();putAll(expandedProjects) }
                        this@MobileSession.sidebarStates.apply { clear();putAll(sidebarStates) }
                        this@MobileSession.workspaces.apply { clear();putAll(workspaces) }
                        this@MobileSession.selectedClient=selectedClient
                    }
                    publish
                }
                // Publication and write authorization happen only after the complete snapshot and vault decode.
                storage.finishRestore(generation,applyState)
                localRecoveryRequired=false;error=null
            } catch (cancel: CancellationException) { localRecoveryRequired=true;throw cancel }
            catch (failure: Exception) {
                // Known authenticated-data format failures can still seal an original
                // result in its independent journal. Crypto/I/O failures retain the
                // original OS callback until a successful explicit parent retry.
                localRecoveryAllowsAttachmentStaging=authenticatedParentRead&&(failure is org.json.JSONException||failure is IllegalArgumentException)
                localRecoveryRequired=true;error="本地连接与缓存无法读取，原文件已保留"
            }
            finally { restoring=false;synchronizeReadingPresentation();reconcilePhoneReading() }
            if (localReady && hasClient && !selectedClient.isDemo) { loadCachedHistory();refresh();loadHistory();loadOperations() }
        }
    }
    internal fun resetAcceptance(reader: HostReads? = null,commandProvider: CodexCommands? = null) {
        phoneReadingEpoch++;phoneReading.reset();phoneReadingProof=null;phoneReadingFingerprint.clear();phoneReadingAcknowledgements.clear();phoneReadingAcknowledgementOwners.clear();attemptedPhoneReading=null;cachedPhoneReading=null;phoneWorkspaceHead=null;phoneReadingRetry++;phoneReadingUncovered=false
        workspaceManagementRevisions.clear();workspaceArchiveBusy.clear();chatActionBusy.clear();locatedChatSearch=null;pendingChatSearch=null
        aiSharing.clearAcceptance();aiSharingRequest=null
        desktopSuggestionReader.clear();suggestionReadAhead.cancel();suggestionSurfaceOwner=null;suggestionSurfaceInteractive=false;suggestionNeedsFreshHistory=false;suggestionUndo=null;textRevisions.clear();handledSuggestions.clear();clientNavigationRevision++;tasks.coroutineContext.cancelChildren(); pagedSelections.clear(); realClients.clear(); selectedThreads.clear(); drafts.clear(); histories.clear(); historyPages.clear(); runs.clear(); workspaces.clear(); loadingTargets.clear(); historyTargets.clear(); expandedProjects.clear(); sidebarStates.clear(); revisions.clear()
        selectedClient = DemoMobileData.clients.first(); selectionRevision++; visibleMessageLimit = ReadingPolicy.initialMessageLimit().toInt(); revealedEarlier = false
        // Existing native fixtures own Chinese app copy; language tests explicitly override it.
        demo = false; appearance = "system";language="zh-Hans"; restoring = false; localRecoveryRequired=false;localRecoveryAllowsAttachmentStaging=false; error = null; providers=registry(reader,commandProvider); reads=providers
        queueActionOwners.clear();replyRecordQueryOwners.clear();replyRecordQueryErrors.clear();busyOwners.clear(); busyCommands.clear(); freshWorkspaces.clear(); freshHistories.clear();attachmentPreparation.reset();attachmentImportGenerations.clear(); draftVersions.clear(); draftFiles.clear(); generation.clear(); generationPreferences.clear(); dismissedReceiptFailures.clear(); modelErrors.clear(); preferredModes.clear(); newProjects.clear(); operations.clear(); preparingCreationMessages.clear(); creationMessageCache=null; pendingCreationWait=null; busyTargets.clear(); stoppingTargets.clear(); answeringTargets.clear(); modelsByClient.clear(); foreground=true
        commands=providers; execution=storage?.let { NativeCodexFlow(it,reads,commands).apply { confirmedReplyReads=::confirmedReplyReads } }; eventJob=null;eventReadOwner=null;historyRecoveryJob=null;networkRecoveryJob=null;networkReadPath=null
        observingOperations.clear();connectionSuccesses.clear();conversationSyncIssues.clear();conversationSnapshotRevisions.clear();usefulConversationProgress.clear();historyRecoveryIdentity=null;readyHistorySelection=null;loadedHistorySelections.clear()
        forkNavigation.clear();forkOptions.clear();forkOptionErrors.clear();forkOptionsLoading.clear();forkRequests.clear();presentationTargetKey=targetKey
    }
    internal fun installAcceptanceHistory(messages: List<MobileMessage>, cursor: String? = null) { histories[targetKey] = messages; visibleMessageLimit = ReadingPolicy.initialMessageLimit().toInt(); revealedEarlier = false;presentationTargetKey=targetKey }
}

internal fun encodeClient(client: MobileClient) = JSONObject().put("id", client.id).put("name", client.name).put("endpoint", client.endpoint).put("requiresReadTarget", client.requiresReadTarget).put("codexDirect", client.codexDirect?.let(::encodeCodexDirect)).put("agentConnection",client.agentConnection?.json())
    .put("storageIdentity",client.storageIdentity?.json() ?: JSONObject.NULL).put("mode", client.mode).put("readerId", client.readerId).put("scope", client.scope?.let { s -> JSONObject().put("agentId", s.agentId).put("hostId", s.hostId).put("workspaceId", s.workspaceId).put("runtimeId", s.runtimeId).apply { if (s.accountAuthority != null) put("account", JSONObject().put("authority", s.accountAuthority).put("subject", s.accountSubject)) } })
internal fun decodeClient(json: JSONObject) = MobileClient(json.getString("id"), json.getString("name"), HostReadCodec.target(json.optJSONObject("scope")), json.getString("endpoint"), false, json.getString("mode"), false, json.optional("readerId"), json.optBoolean("requiresReadTarget"), json.optJSONObject("codexDirect")?.let(::decodeCodexDirect),agentConnection=json.optJSONObject("agentConnection")?.let(AgentConnection::decode),storageIdentity=json.optJSONObject("storageIdentity")?.let(MobileStorageIdentity::decode) ?: if(json.has("storageIdentity")) null else HostReadCodec.target(json.optJSONObject("scope"))?.takeIf { it.agentId=="codex" }?.let { MobileStorageIdentity(it,emptyList()) }).also { require(it.codexDirect == null || sameScope(it.scope, it.codexDirect.target));require(it.agentConnection==null||sameScope(it.scope,it.agentConnection.target)) }
internal fun encodeWorkspace(w: MobileWorkspace) = MobileWorkspaceMetadata.encode(w)
internal fun encodeMessage(m: MobileMessage) = JSONObject().put("id", m.id).put("role", m.role).put("text", m.text).put("status", m.status).put("kind", m.kind).put("detail", m.detail).put("runId", m.runId).put("replyPhase", m.replyPhase).put("replyMessageId", m.replyMessageId).put("files", JSONArray(m.files)).put("blocks", m.blocks?.let(::JSONArray)).put("attachments", JSONArray(m.attachments.map { f -> JSONObject().put("id", f.id).put("name", f.name).put("mediaType", f.mediaType).put("sizeBytes", f.sizeBytes).put("hostId", f.hostId).put("threadId", f.threadId).put("sha256", f.sha256).put("resourceId",f.resourceId) }))

object DemoMobileData {
    val clients = listOf(MobileClient("demo-mac", "我的 MacBook", online = true), MobileClient("demo-pc", "办公电脑", online = true), MobileClient("demo-home", "家里的 Mac"))
    val projects = listOf(MobileProject("mobile", "demo-mac", "Agent Hub", emptyList()), MobileProject("api", "demo-mac", "api-service", emptyList()), MobileProject("design", "demo-mac", "design-system", emptyList()), MobileProject("web", "demo-pc", "web-studio", emptyList()))
    val threads = listOf("设计移动端导航", "梳理设备配对流程", "优化查询性能", "统一组件间距", "核对审批流程", "补齐安卓页面", "验收长列表").mapIndexed { index, title -> MobileThread(if (index == 0) "demo-chat" else "demo-thread-$index", "demo-mac", title, if (index == 4) "waiting-for-approval" else "idle", if (index < 2) "mobile" else if (index == 2) "api" else "design", unread = index == 3) } + MobileThread("demo-chat", "demo-pc", "设计网页布局", "idle", "web")
    val messages = listOf(MobileMessage("demo-user", "user", "照着 ChatGPT APP 来做界面，右上角能切换不同的 Codex 客户端。"),
        MobileMessage("demo-assistant", "assistant", "可以，我们保留熟悉的聊天结构。\n\n1. 左侧栏：选择项目、继续对话或新建对话。\n2. 聊天区：保留清晰的消息排版和底部输入框。\n3. 右上角：切换任务所在的 Agent 客户端。\n\n当前对话属于 Agent Hub。", "completed"))
}
