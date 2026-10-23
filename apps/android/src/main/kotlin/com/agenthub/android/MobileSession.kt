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
}
data class PendingAgentLink(val presentation: String=UUID.randomUUID().toString(),val payload: String)
data class DraftDestination(val key: String,val selection: Long)
data class SidebarDisplayState(val recentExpanded: Boolean=false,val projectsExpanded: Boolean=true,val expandedLists: Set<String> = emptySet())

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
    private fun registry(reader: HostReads?,commands: CodexCommands?)=AgentRegistry(reader?:NativeHostReads { storage?.token(it).orEmpty() },commands?:NativeCodexCommands { storage?.token(it).orEmpty() }).apply { easyWorkAgent?.let { register("easywork",it,it) } }
    private var providers=registry(reader,commandProvider)
    private var reads: HostReads = providers
    private var commands: CodexCommands = providers
    private var execution = storage?.let { NativeCodexFlow(it,reads,commands) }
    private val freshWorkspaces=mutableStateMapOf<String,Boolean>()
    private val freshHistories=mutableStateMapOf<String,Boolean>()
    private val draftVersions=mutableStateMapOf<String,String>()
    private val draftFiles=mutableStateMapOf<String,List<LocalDraftFile>>()
    private val generation=mutableStateMapOf<String,GenerationSettings>()
    private val generationPreferences=mutableStateMapOf<String,GenerationSettings>()
    private val dismissedReceiptFailures=mutableStateMapOf<String,Set<String>>()
    private val modelErrors=mutableStateMapOf<String,String>()
    val modelError get()=modelErrors[clientKey]
    private val preferredModes=mutableStateMapOf<String,String>()
    private val newProjects=mutableStateMapOf<String,String?>()
    private val operations=mutableStateMapOf<String,List<SavedOperation>>()
    private val busyTargets=mutableStateMapOf<String,String>()
    private val busyOwners=mutableMapOf<String,String>()
    private val busyCommands=mutableMapOf<String,String>()
    private val stoppingTargets=mutableStateMapOf<String,Boolean>()
    private val answeringTargets=mutableStateMapOf<String,Boolean>()
    private data class ForkNavigation(val id: String,val selection: Long,val draft: String)
    private val forkNavigation=mutableMapOf<String,ForkNavigation>()
    private val forkOptions=mutableStateMapOf<String,ThreadForkOptions>()
    private val forkOptionErrors=mutableStateMapOf<String,String>()
    private val forkOptionsLoading=mutableStateMapOf<String,Boolean>()
    private val forkRequests=mutableStateMapOf<String,String>()
    private val modelsByClient=mutableStateMapOf<String,List<CodexModel>>()
    private val observingOperations=mutableMapOf<String,Job>()
    private var eventJob: Job?=null
    private var foreground=true
    var importing by mutableStateOf(false)
        private set
    private val realClients = mutableStateListOf<MobileClient>()
    val clients: List<MobileClient> get() = realClients.toList() + if (demo) DemoMobileData.clients else emptyList()
    var demo by mutableStateOf(false)
    var selectedClient by mutableStateOf(DemoMobileData.clients.first())
        private set
    val hasClient get() = demo && selectedClient.isDemo || realClients.any { it.id == selectedClient.id }
    private val selectedThreads = mutableStateMapOf<String, String>()
    var selectedThread: String
        get() = selectedThreads[clientKey] ?: if (selectedClient.isDemo) "demo-chat" else ""
        set(value) { selectedThreads[clientKey] = value; persistMetadata() }
    var appearance by mutableStateOf("system")
    var search by mutableStateOf("")
    private var errorMessage by mutableStateOf<String?>(null)
    private var readErrorActive=false
    var error: String?
        get()=errorMessage
        set(value) { errorMessage=value;readErrorActive=false }
    private fun readError(message: String,userInitiated: Boolean=false) {
        if(error==null || readErrorActive || userInitiated) { error=message;readErrorActive=true }
    }
    private fun clearReadError(userInitiated: Boolean) { if(userInitiated || readErrorActive) error=null }
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
    private val loadingTargets = mutableStateMapOf<String, Boolean>()
    private val historyTargets = mutableStateMapOf<String, Boolean>()
    private val expandedProjects = mutableStateMapOf<String, Set<String>>()
    private val sidebarStates = mutableStateMapOf<String, SidebarDisplayState>()
    private val revisions = mutableMapOf<String, Long>()
    private var selectionRevision = 0L
    private var historyJob: Job? = null
    private var workspaceJob: Job? = null
    private var foregroundJob: Job? = null
    fun enterForeground() {
        tasks.launch { easyWorkLogin?.foreground() }
        foreground=true; freshWorkspaces.clear(); freshHistories.clear()
        realClients.toList().forEach { replaceClient(it.copy(online=false,connectionPhase=ConnectionPhase.CONNECTING)) }
        if(!restoring) { refresh(); loadHistory() }
        foregroundJob?.cancel()
        foregroundJob=tasks.launch {
            while(isActive) {
                delay(15_000)
                if(hasClient && selectedClient.isConnecting) { refresh(); loadHistory() }
            }
        }
    }
    fun leaveForeground() { voice?.suspendRecording();easyWorkLogin?.background();foreground=false; foregroundJob?.cancel(); foregroundJob=null; eventJob?.cancel(); eventJob=null; observingOperations.values.forEach { it.cancel() }; observingOperations.clear(); freshWorkspaces.clear(); freshHistories.clear() }
    var visibleMessageLimit by mutableStateOf(60)
        private set
    var revealedEarlier by mutableStateOf(false)
        private set
    private val clientKey get() = selectedClient.cacheKey(null)
    val sidebarDisplay get() = sidebarStates[clientKey] ?: SidebarDisplayState()
    fun toggleSidebarRecent() { sidebarStates[clientKey]=sidebarDisplay.copy(recentExpanded=!sidebarDisplay.recentExpanded); persistMetadata() }
    fun toggleSidebarProjects() { sidebarStates[clientKey]=sidebarDisplay.copy(projectsExpanded=!sidebarDisplay.projectsExpanded); persistMetadata() }
    fun toggleSidebarList(project: String) { val lists=sidebarDisplay.expandedLists; sidebarStates[clientKey]=sidebarDisplay.copy(expandedLists=if(project in lists) lists-project else lists+project); persistMetadata() }
    val targetKey: String get() = selectedClient.cacheKey(selectedThread.takeIf { it.isNotEmpty() }, if(selectedThread.isEmpty()) newProjects[clientKey] else null)
    val workspace get() = workspaces[clientKey]
    val projects get() = if (selectedClient.isDemo && demo) DemoMobileData.projects.filter { it.hostId == selectedClient.id } else workspace?.projects.orEmpty()
    val threads get() = if (selectedClient.isDemo && demo) DemoMobileData.threads.filter { it.hostId == selectedClient.id } else workspace?.threads.orEmpty()
    val loading get() = loadingTargets[clientKey] == true
    val historyLoading get() = historyTargets[targetKey] == true
    val historyCursor get() = historyPages[targetKey]?.nextCursor
    val sourceLabel get() = if (selectedClient.isDemo) "界面体验 · 本地示例" else when (selectedClient.mode) {
        "desktop-runtime" -> "原桌面连接"; "easywork-runtime" -> "EasyWork 连接"; "managed-runtime" -> "托管连接"; "shared-runtime" -> "共享连接"; else -> "历史连接"
    }
    val selectedTitle get() = threads.firstOrNull { it.id == selectedThread }?.title ?: "新聊天"
    var runtimeState: String
        get() = runs[targetKey] ?: historyPages[targetKey]?.state ?: "idle"
        set(value) { runs[targetKey] = value }
    var running: Boolean
        get() = if(selectedClient.isDemo) runtimeState=="running" else SessionPolicies.running(runtime?.turnId,runtime?.state)
        set(value) { runtimeState = if (value) "running" else "idle" }
    var text: String
        get() = drafts[targetKey].orEmpty()
        set(value) { drafts[targetKey] = value; draftVersions[targetKey]=UUID.randomUUID().toString(); persistMetadata() }
    val messages: List<MobileMessage> get() = histories[targetKey] ?: if (selectedClient.isDemo && demo && selectedThread == "demo-chat") DemoMobileData.messages else emptyList()
    val runtime get() = historyPages[targetKey]?.runtime
    val attachments get() = draftFiles[targetKey].orEmpty()
    val generationSettings get() = generation[targetKey] ?: GenerationSettings()
    val models get() = modelsByClient[clientKey].orEmpty()
    val selectedModel get() = models.firstOrNull { it.id==(generationSettings.model ?: runtime?.settings?.model) }
        ?: if(generationSettings.model==null && runtime?.settings?.model==null) models.firstOrNull { it.isDefault } else null
    val modelLabel get() = selectedModel?.name ?: generationSettings.model ?: runtime?.settings?.model ?: "电脑默认"
    val selectedEffort get() = generationSettings.effort ?: runtime?.settings?.effort ?: selectedModel?.defaultEffort
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
    val activeOperationRecords get() = operationRecords.filter { it.kind!="fork" && !it.detached && it.sourceKey==targetKey && it.phase !in listOf("completed","cancelled","interrupted","confirmed","opened","queued","submitted") && !(it.kind=="reply" && it.phase=="failed" && it.result!=null) }
    val detachedCreations get() = operationRecords.filter { it.kind=="creation" && it.detached && it.sourceKey==targetKey }.takeLast(3)
    private val pendingRecords get() = operationRecords.filter { it.kind!="fork" && !it.detached && it.sourceKey==targetKey && it.phase in listOf("ready","preparing","prepared","submitting","received","uncertain","outcome-unknown","opening","submitted") && (it.kind!="reply" || it.phase !in listOf("submitted")) }
    private val access: SessionAccess get() {
        val local=demo && selectedClient.isDemo
        val fresh=freshWorkspaces[clientKey]==true && foreground && execution!=null && "commands:target-bound:v1" in workspace?.capabilities.orEmpty()
        return SessionAccess(local,local || selectedClient.online && fresh,
            if(local) null else if(fresh) workspace?.runtimeConnected else false,
            if(local) emptyList() else if(fresh) workspace?.capabilities.orEmpty() else emptyList(),
            selectedThread.isNotEmpty(),newProjects[clientKey]!=null || threads.any { it.id==selectedThread && it.projectId!=null },
            selectedThread.isEmpty() && newProjects.containsKey(clientKey) && newProjects[clientKey]==null,
            local || freshHistories[targetKey]==true && runtime?.canControl==true,false)
    }
    private val submission: SubmissionState get() = SubmissionState(historyLoading,busyTargets.containsKey(targetKey),stoppingTargets[targetKey]==true,
        pendingRecords.any { it.kind in listOf("reply","open") },pendingRecords.any { it.kind in listOf("creation","thread-create") },importing,running,replyMode)
    private fun submitAllowed(text: String, count: Int) = codexStorageConflictMessage == null && SessionPolicies.submit(access,submission,text,count)
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
    val canCompose get() = codexStorageConflictMessage == null && SessionPolicies.compose(access)
    val canCreateThread get() = codexStorageConflictMessage == null && SessionPolicies.createThread(access) && access.online
    val canCreateProjectless get() = codexStorageConflictMessage == null && SessionPolicies.createProjectless(access) && access.online
    val canCreateProject get()=codexStorageConflictMessage == null && access.online&&"projects:create" in workspace?.capabilities.orEmpty()
    val canConfigureGeneration get() = SessionPolicies.configureGeneration(access,submission)
    val canSelectPlan get() = SessionPolicies.selectPlan(access,submission)
    val replyModes get() = SessionPolicies.replyModes(access)
    val queuedReplies get() = runtime?.receipts?.filter { it.state=="queued" }.orEmpty()
    val receiptNotices get() = runtime?.receipts.orEmpty().let { list -> SessionPolicies.noticeIndices(list.map { ReceiptState(it.operationId,it.state,it.supersededByTurnId) },dismissedReceiptFailures[targetKey].orEmpty().toList(),pendingRecords.firstOrNull()?.id).map { list[it] } }
    val timeline get() = MessageProjection.project(messages.map {
        TimelineMessage(it.id, it.role, it.text, it.kind, it.status, it.runId, it.replyPhase, it.replyMessageId)
    }, visibleMessageLimit, historyPages[targetKey]?.turnId, running || runtimeState == "paused")
    val canRunAgain get() = SessionPolicies.recoveryPrerequisites(selectedThread.isNotEmpty(),running,runtimeState,runtime!=null) &&
        messages.any { it.role=="user" } && submitAllowed(MobileRules.runAgainText(false),0)
    fun runAgain(restart: Boolean) { if(canRunAgain) send(CodexContent(MobileRules.runAgainText(restart),emptyList(),GenerationSettings())) }
    val decision: ComposerDecision get() {
        val input = ComposerInput(submission.stopping,running,submission.sending,submission.creationPending,text,attachments.size,
            submitAllowed(text,attachments.size),SessionPolicies.stop(running,SessionPolicies.writable(access),access.online,submission.stopping),
            (selectedClient.isDemo && demo && !running || SessionPolicies.recoveryPrerequisites(selectedThread.isNotEmpty(),running,runtimeState,runtime!=null)) &&
                messages.any { it.role=="user" } && submitAllowed(MobileRules.runAgainText(false),0),runtimeState,runtime?.receipts?.lastOrNull()?.state,emptyList())
        return MobileRules.composer(if (MobileRules.needsRecoveryHistory(input)) input.withMessages(messages.map { ComposerMessage(it.role, it.status) }) else input)
    }
    fun projectExpanded(id: String) = id in expandedProjects[clientKey].orEmpty()
    fun toggleProject(id: String) { expandedProjects[clientKey] = expandedProjects[clientKey].orEmpty().let { if (id in it) it - id else it + id }; persistMetadata() }
    fun changeAppearance(value: String) { require(value in listOf("system", "light", "dark")); appearance = value; persistMetadata() }
    fun setDemoMode(value: Boolean) {
        demo = value
        if (!hasClient) select(if (value) DemoMobileData.clients.first() else realClients.firstOrNull() ?: DemoMobileData.clients.first())
        persistMetadata()
    }
    fun select(client: MobileClient) {
        voice?.suspendRecording();aiSharingRequest=null
        selectionRevision++; workspaceJob?.cancel(); historyJob?.cancel(); eventJob?.cancel()
        selectedClient = realClients.firstOrNull { it.id==client.id && sameScope(it.scope,client.scope) } ?: client; search = ""; error = null; visibleMessageLimit = 60; revealedEarlier = false
        persistMetadata()
        if (!client.isDemo) { refresh(); loadCachedHistory(); loadHistory(); loadOperations() }
    }
    fun selectThread(thread: MobileThread) {
        voice?.suspendRecording();aiSharingRequest=null
        require(thread.hostId == selectedClient.id && threads.any { it.id == thread.id })
        selectionRevision++; historyJob?.cancel(); eventJob?.cancel(); selectedThread = thread.id
        error = null; visibleMessageLimit = 60; revealedEarlier = false
        if (!selectedClient.isDemo) { loadCachedHistory(); loadHistory(); loadOperations() }
    }
    fun performAction() {
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
            selectionRevision++; historyJob?.cancel(); eventJob?.cancel(); selectedThread=""; newProjects[clientKey]=projectId
            error=null; visibleMessageLimit=60; revealedEarlier=false; inheritGeneration(); persistMetadata(); loadOperations(); loadModels(); return
        }
        if (!demo) return
        selectionRevision++; historyJob?.cancel(); eventJob?.cancel()
        selectedThread = "draft-${UUID.randomUUID()}"; histories[targetKey] = emptyList(); runtimeState = "idle"
        visibleMessageLimit = 60; revealedEarlier = false
    }
    fun refresh(more: Boolean = false, userInitiated: Boolean = false) {
        val client = selectedClient
        if (client.isDemo || !hasClient || loading || incomingEasyWorkLink!=null) return
        val key = clientKey; val revision = revisions[client.id] ?: 0
        val old = workspace
        workspaceJob = tasks.launch {
            loadingTargets[key] = true
            try {
                val result = reads.workspace(client, if (more) old?.nextProjectCursor else null, if (more) old?.nextThreadCursor else null)
                if (!currentWorkspace(client, key, revision)) return@launch
                require(result.hostId == client.id && (!client.requiresReadTarget || result.scope != null) && (client.scope == null || result.scope == null || sameScope(client.scope, result.scope))) { "电脑身份不匹配" }
                // Each page is merged only with that same scope; refresh replaces removed entries.
                var merged = if (more && old != null) result.copy(
                    projects = (old.projects + result.projects).distinctBy { it.id },
                    threads = (old.threads + result.threads).distinctBy { it.id }) else result
                merged = merged.copy(wire=encodeWorkspace(merged).toString())
                workspaces[key] = merged
                freshWorkspaces[key]=true
                replaceClient(selectedClient.copy(online = true, mode = result.mode))
                clearReadError(userInitiated)
                persistMetadata(); startEvents(); loadModels()
            } catch (cancel: CancellationException) { throw cancel }
            catch (failure: Exception) { if (currentWorkspace(client, key, revision)) { recordConnectionFailure(client,failure); if(userInitiated) readError("刷新失败，请重试。\n"+(failure.message ?: "读取失败"),true); persistMetadata() } }
            finally { loadingTargets[key] = false }
        }
    }
    // Workspace freshness belongs to the connection; selecting one of its threads does not invalidate it.
    private fun currentWorkspace(client: MobileClient, key: String, revision: Long) = selectedClient.id == client.id && clientKey == key && (revisions[client.id] ?: 0) == revision && sameScope(selectedClient.scope,client.scope)
    private fun recordConnectionFailure(client: MobileClient,failure: Exception) {
        freshWorkspaces[client.cacheKey(null)]=false
        val transient=(failure as? HostReadError)?.transient==true
        val latest=realClients.firstOrNull { it.id==client.id&&sameScope(it.scope,client.scope) }?:client
        replaceClient(latest.copy(online=false,connectionPhase=if(transient) ConnectionPhase.WAITING else ConnectionPhase.FAILED))
        if(!transient) readError(failure.message ?: "读取失败，保留缓存")
    }
    fun loadHistory(more: Boolean = false, userInitiated: Boolean = false): Job? {
        val client = selectedClient; val thread = selectedThread; val key = targetKey
        if (client.isDemo || incomingEasyWorkLink!=null || thread.isEmpty() || thread.startsWith("draft-") || historyLoading) return null
        if(!more && userInitiated) { pagedSelections.remove(key); loadedHistorySelections.remove(key) }
        val selection = selectionRevision; val revision = revisions[client.id] ?: 0
        val cursor = if (more) historyCursor ?: return null else null
        historyJob = tasks.launch {
            historyTargets[key] = true
            try {
                val result = reads.history(client, thread, cursor)
                if (targetKey != key || selectionRevision != selection || (revisions[client.id] ?: 0) != revision) return@launch
                require(result.hostId == client.id && result.threadId == thread && (!client.requiresReadTarget || result.scope != null) && (client.scope == null || result.scope == null || sameScope(client.scope, result.scope))) { "电脑或对话身份不匹配" }
                val previous = histories[key].orEmpty()
                val previousPage=historyPages[key]
                val sameReadGeneration=loadedHistorySelections[key]==(selection to revision)
                if(more)require(MobileHistoryWindowPolicy.acceptsEarlierPage(previousPage,result,sameReadGeneration)) { "聊天历史已变化，请刷新后重试。" }
                val compatiblePrefix=MobileHistoryWindowPolicy.compatiblePrefix(previousPage,result,previous,sameReadGeneration)
                val paged = com.agenthub.core.ReadingPolicy.preserveHistoryPrefix(!result.runtimePresent||result.windowed,
                    pagedSelections.containsKey(key),pagedSelections[key]==selection && (!result.windowed || sameReadGeneration),
                    !result.windowed || compatiblePrefix)
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
                replaceClient(selectedClient.copy(online=true))
                persistHistory(key, client, thread, page, merged)
                startEvents()
            } catch (cancel: CancellationException) { throw cancel }
            catch (failure: Exception) { if (targetKey == key && selectionRevision == selection && (revisions[client.id] ?: 0)==revision) { recordConnectionFailure(client,failure); if(more) readError("加载更早消息失败，请重试。\n"+(failure.message ?: "读取失败"),true) else if(userInitiated) readError("加载历史失败，请重试。\n"+(failure.message ?: "读取失败"),true) } }
            finally { historyTargets[key] = false }
        }
        return historyJob
    }
    fun earlierMessages(): Job? { if (timeline.hasEarlierMessages) { visibleMessageLimit += 60; revealedEarlier = true; return null } else return loadHistory(more = true) }
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
    private suspend fun acceptFlow(client: MobileClient,result: CodexFlowResult) {
        val record=result.record; val flow=execution ?: return
        operations[record.target]=flow.journal.records(record.target)
        val origin=record.sourceKey
        val unchanged=if(record.kind=="fork") origin!=null && !record.detached && forkNavigation[origin]?.let {
            it.id==record.id && it.selection==selectionRevision && it.draft==version(origin)
        }==true else !record.detached && origin!=null && draftVersions[origin]==record.sourceVersion
        val clientCache=client.cacheKey(null)
        result.project?.let { project -> workspaces[clientCache]?.let { old->val updated=old.copy(projects=(old.projects+project).distinctBy { it.id });workspaces[clientCache]=updated.copy(wire=encodeWorkspace(updated).toString()) } }
        result.thread?.let { thread ->
            workspaces[clientCache]?.let { old -> val updated=old.copy(threads=(listOf(thread)+old.threads).distinctBy { it.id }); workspaces[clientCache]=updated.copy(wire=encodeWorkspace(updated).toString()) }
            // A late creation may be shown in the sidebar; it cannot replace a newer draft.
            if(unchanged && targetKey==origin && selectedClient.id==client.id && sameScope(selectedClient.scope,client.scope)) {
                selectedThread=thread.id; selectionRevision++; newProjects.remove(clientCache)
            }
        }
        if(unchanged && (record.kind=="reply" && record.phase in listOf("received","queued","submitted","completed") || record.kind=="creation" && record.phase=="confirmed")) {
            drafts.remove(origin); draftFiles.remove(origin); draftVersions[origin!!]=UUID.randomUUID().toString()
            generation.remove(origin); scrollToLatestRevision++
        }
        persistMetadata()
        if(selectedClient.id==client.id && sameScope(selectedClient.scope,client.scope)) { loadOperations(); refresh(); loadHistory() }
    }
    private fun observeOperation(client: MobileClient,record: SavedOperation) {
        val flow=execution ?: return; val operationKey=record.target+":"+record.id
        if(!foreground || record.detached || record.kind=="thread-create" || record.phase !in listOf("submitting","received","uncertain","outcome-unknown","submitted","opening","preparing") ||
            record.kind=="reply" && record.phase=="submitted" || observingOperations[operationKey]?.isActive==true) return
        observingOperations[operationKey]=tasks.launch {
            var current=record
            while(isActive && foreground && !current.detached && current.phase in listOf("submitting","received","uncertain","outcome-unknown","submitted","opening","preparing")) {
                delay(1500)
                try {
                    val latest=flow.journal.records(current.target).first { it.id==current.id }
                    if(latest.detached) break
                    val result=flow.query(client,latest); current=result.record
                    acceptFlow(client,result)
                    if(current.kind=="reply" && current.phase in listOf("queued","submitted","completed")) break
                } catch(cancel: CancellationException) { throw cancel }
                catch(failure: Exception) {
                    if(failure is IllegalArgumentException || failure is org.json.JSONException || failure is CodexCommandError && failure.status in listOf(400,401,403,404,409,413)) break
                    delay(2000)
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
        tasks.launch {
            try {
                val result=flow.submit(client,thread,project,origin,configured,mode,turn,expectedPlanId,
                    guard={ foreground && selectionRevision==selection && targetKey==key && version(key)==draftVersion &&
                        (revisions[client.id] ?: 0)==connectionRevision && sameScope(selectedClient.scope,client.scope) && aiSharing.permits(sharing) },
                    progress={ updateBusy(key,owner,it); loadOperations() },operationId=operationId)
                acceptFlow(client,result)
            } catch(cancel: CancellationException) { throw cancel }
            catch(failure: Exception) { if(targetKey==key && busyOwners[key]==owner) error=failure.message ?: "提交未完成，内容已保留" }
            finally { endBusy(key,owner); loadOperations() }
        }
    }
    fun queryOperation(record: SavedOperation) {
        val flow=execution ?: return; val client=selectedClient; val key=targetKey
        if(operationRecords.none { it.id==record.id && it.target==record.target } || busyTargets.containsKey(key)) return
        val owner=beginBusy(key,record.id,"查询原操作记录…")
        tasks.launch { try { acceptFlow(client,flow.query(client,record)) }
            catch(cancel: CancellationException) { throw cancel }
            catch(failure: Exception) { if(targetKey==key && busyOwners[key]==owner) error=failure.message ?: "查询未完成，原记录已保留" }
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
                drafts[key]=content.text;draftFiles[key]=content.files;generation[key]=content.settings
                draftVersions[key]=UUID.randomUUID().toString();persistMetadata()
                check(detached.detached)
            } catch(cancel: CancellationException) { throw cancel }
            catch(failure: Exception) { if(targetKey==key && busyOwners[key]==owner) error=failure.message ?: "原内容未恢复，当前草稿已保留" }
            finally { endBusy(key,owner);loadOperations() }
        }
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
        } catch(failure: Exception) { if(selectedClient.id==client.id) error=failure.message ?: "结束等待未完成" } }
    }
    fun setReplyMode(mode: String) { if(mode in replyModes && !submission.sending) { preferredModes[targetKey]=mode; persistMetadata() } }
    fun configure(settings: GenerationSettings) {
        if(!canConfigureGeneration) return
        generation[targetKey]=settings
        generationPreferences[clientKey]=settings.copy(collaborationMode=null)
        draftVersions[targetKey]=UUID.randomUUID().toString(); persistMetadata()
    }
    fun setPlanMode(enabled: Boolean) {
        if(!canSelectPlan) return
        generation[targetKey]=generationSettings.copy(collaborationMode=if(enabled) "plan" else "default")
        draftVersions[targetKey]=UUID.randomUUID().toString(); persistMetadata()
    }
    fun canRespondToPlan(plan: MobileProposedPlan)=freshHistories[targetKey]==true && SessionPolicies.writable(access) && SessionPolicies.respondToPlan(runtime?.proposedPlan?.id,plan.id,running,SessionPolicies.supports(access,"plan-mode"),selectedThread.isNotEmpty(),submitAllowed("实施此计划。",0))
    fun implementPlan(plan: MobileProposedPlan) { if(canRespondToPlan(plan)) send(CodexContent("PLEASE IMPLEMENT THIS PLAN:\n"+plan.text,emptyList(),generationSettings.copy(collaborationMode="default")),plan.id) }
    fun revisePlan(plan: MobileProposedPlan,feedback: String) { if(canRespondToPlan(plan) && feedback.isNotBlank()) send(CodexContent(feedback,emptyList(),generationSettings.copy(collaborationMode="plan")),plan.id) }
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
        if(!SessionPolicies.stop(running,SessionPolicies.writable(access),access.online,submission.stopping)) return
        stoppingTargets[key]=true
        tasks.launch { try { commands.stop(client,thread,turn); if(targetKey==key) loadHistory() }
            catch(failure: Exception) { if(targetKey==key) error=failure.message ?: "停止未确认，请刷新任务" }
            finally { stoppingTargets.remove(key) } }
    }
    fun cancelQueue(receipt: DeliveryReceipt) {
        val client=selectedClient; val key=targetKey
        if(!access.online || !SessionPolicies.writable(access) || queuedReplies.none { it.operationId==receipt.operationId && it.threadId==selectedThread } || busyTargets.containsKey(key)) return
        val owner=beginBusy(key,receipt.operationId,"取消排队…")
        tasks.launch { try { commands.cancelQueue(client,receipt.threadId,receipt.operationId); if(targetKey==key) loadHistory() }
            catch(failure: Exception) { if(targetKey==key) error=failure.message ?: "取消未确认，请刷新队列" }
            finally { endBusy(key,owner) } }
    }
    fun isAnswering(approval: MobileApproval)=answeringTargets["$targetKey:${approval.id}"]==true
    fun canAnswer(approval: MobileApproval)=freshHistories[targetKey]==true && access.online && SessionPolicies.writable(access) && SessionPolicies.supports(access,"approvals") && !isAnswering(approval) && runtime?.approvals?.any { it.id==approval.id && it.turnId==approval.turnId }==true
    fun answer(approval: MobileApproval,decision: String,answers: Map<String,List<String>>,expectedTarget: String) {
        if(expectedTarget!=targetKey || !canAnswer(approval)) return
        if(approval.kind=="question") {
            if(decision=="skip") { if(approval.interactionMode!="async") return }
            else if(decision!="" || answers.keys!=approval.questions.map { it.id }.toSet() || answers.values.any { it.isEmpty() || it.any(String::isBlank) }) return
        } else if(decision !in approval.decisions) return
        val sharing=if(AISharingRules.requiresContentGrant(decision)) requireSharing(runtime?.settings?.model) ?: return else null
        val client=selectedClient; val key=targetKey; val requestKey="$key:${approval.id}"
        answeringTargets[requestKey]=true
        tasks.launch { try { check(sharing==null || aiSharing.permits(sharing));commands.answer(client,approval.threadId,approval.turnId,approval.id,decision,answers); if(targetKey==key) loadHistory() }
            catch(failure: Exception) { if(targetKey==key) error=failure.message ?: "回复未确认，请刷新请求" }
            finally { answeringTargets.remove(requestKey) } }
    }
    fun attachmentDestination()=DraftDestination(targetKey,selectionRevision)
    fun isCurrent(destination: DraftDestination)=destination.key==targetKey && destination.selection==selectionRevision
    suspend fun addAttachment(name: String,mediaType: String,bytes: ByteArray,destination: DraftDestination=attachmentDestination()) {
        if(!isCurrent(destination)) return
        val flow=execution ?: return; val key=destination.key; val selected=destination.selection
        require(canCompose && attachments.size<10 && !submission.sending && !submission.creationPending) { "当前无法添加附件" }
        importing=true
        try {
            val file=flow.files.import(name,mediaType,bytes)
            if(key==targetKey && selectionRevision==selected) { draftFiles[key]=draftFiles[key].orEmpty()+file; draftVersions[key]=UUID.randomUUID().toString(); persistMetadata() }
        } finally { importing=false }
    }
    fun removeAttachment(id: String) { if(!submission.sending) { draftFiles[targetKey]=attachments.filter { it.manifest.id!=id }; draftVersions[targetKey]=UUID.randomUUID().toString(); persistMetadata() } }
    suspend fun downloadResource(file: MobileFile,cache: java.io.File): java.io.File {
        val client=selectedClient
        require(client.scope!=null&&file.hostId==client.id&&file.threadId==selectedThread&&messages.any { file in it.attachments }) { "文件不属于当前电脑和对话" }
        return commands.downloadResource(client,file,cache)
    }
    private fun startEvents() {
        if(!foreground || incomingEasyWorkLink!=null || selectedClient.isDemo || selectedThread.isEmpty() || "events" !in workspace?.capabilities.orEmpty() || "commands:target-bound:v1" !in workspace?.capabilities.orEmpty() || eventJob?.isActive==true) return
        val client=selectedClient; val thread=selectedThread; val key=targetKey; val selection=selectionRevision; val revision=revisions[client.id] ?: 0
        eventJob=tasks.launch {
            var epoch=historyPages[key]?.epoch.orEmpty(); var cursor=historyPages[key]?.cursor ?: 0
            while(isActive && foreground && targetKey==key && selectionRevision==selection && (revisions[client.id] ?: 0)==revision) {
                try {
                    val batch=commands.events(client,thread,epoch,cursor)
                    if(!foreground || targetKey!=key || selectionRevision!=selection || (revisions[client.id] ?: 0)!=revision) break
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
                                pagedSelections[key]==selection && sameReadGeneration,compatiblePrefix)
                            val incoming=page.messages.map { it.id }.toSet()
                            val merged=if(retain)histories[key].orEmpty().takeWhile { it.id !in incoming }+page.messages else page.messages
                            val stored=if(retain)page.copy(nextCursor=prior?.nextCursor,wire=JSONObject(page.wire).put("nextCursor",prior?.nextCursor).toString()) else page
                            if(page.windowed&&!retain)pagedSelections.remove(key)
                            historyPages[key]=stored; histories[key]=merged; runs.remove(key)
                            loadedHistorySelections[key]=selection to revision
                            freshHistories[key]=batch.ready!=false
                            persistHistory(key,client,thread,stored,merged)
                            operations[NativeOperationJournal.target(client,thread)].orEmpty().filter { it.kind=="reply" }.forEach { saved ->
                                page.runtime?.receipts?.firstOrNull { it.operationId==saved.id }?.let { receipt -> execution?.journal?.observed(saved.target,saved.id,receipt.state,encodeReceipt(receipt),receipt.error) }
                            }
                        }
                    }
                    if(decision.reload) { freshHistories[key]=false; refresh(); loadHistory() }
                    if(batch.ready==true&&batch.snapshots.isNotEmpty()) {
                        replaceClient(selectedClient.copy(online=true))
                        if(freshWorkspaces[clientKey]!=true)refresh()
                    }
                    loadOperations(); delay(if(batch.snapshots.isEmpty()) 300 else 50)
                } catch(cancel: CancellationException) { throw cancel }
                catch(failure: Exception) { if(foreground&&targetKey==key&&selectionRevision==selection&&(revisions[client.id]?:0)==revision) { freshHistories[key]=false; recordConnectionFailure(client,HostReadError("正在重新连接电脑",failure,true)) }; delay(2000) }
            }
        }
    }
    suspend fun connect(name: String, address: String, secret: String, editing: MobileClient? = null): MobileClient {
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
        select(remembered); return remembered
    }
    private fun replaceClient(client: MobileClient): MobileClient {
        val index = realClients.indexOfFirst { it.id == client.id }
        val prior=realClients.getOrNull(index)
        val remembered=if(client.storageIdentity==null && prior?.storageIdentity!=null && sameScope(client.scope,prior.scope)) client.copy(storageIdentity=prior.storageIdentity) else client
        if (index < 0) realClients.add(remembered) else realClients[index] = remembered
        if (selectedClient.id == client.id) selectedClient = remembered
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
        if(uri.scheme in listOf("easywork-host","easywork")&&uri.host=="pair") { incomingEasyWorkLink=PendingAgentLink(payload=uri.toString());selectionRevision++;workspaceJob?.cancel();historyJob?.cancel();eventJob?.cancel();return }
        if(uri.scheme=="com.easyai.remote"&&uri.host=="oauth")tasks.launch { try { easyWorkLogin?.callback(uri) } catch(failure: Exception) { error=failure.message?:"登录未完成" } }
    }
    internal fun storedCredential(id: String): String = storage?.token(id).orEmpty()
    suspend fun connectKnownCodex(host: DiscoveredCodexHost) {
        var client = realClients.firstOrNull { it.id == host.id && it.scope?.agentId == "codex" } ?: error("原连接已移除")
        val selection = selectionRevision; val sourceKey = targetKey; val sourceVersion = version(sourceKey)
        if (host.reachable && client.codexDirect != null) {
            val direct = client.codexDirect!!
            val descriptor = CodexDiscoveryAPI.discover(host.endpoint, CodexPublicSource(direct.target, direct.fingerprint, direct.routes.map { it.endpoint }))
            require(updateCodexRoute(client, host.endpoint, descriptor)) { "连接已变化，原连接已保留" }
            client = realClients.first { it.id == host.id }
        }
        val revision = revisions[host.id] ?: 0
        val snapshot = reads.workspace(client)
        require(selectionRevision == selection && targetKey == sourceKey && version(sourceKey) == sourceVersion && (revisions[host.id] ?: 0) == revision && realClients.any { it == client }) { "所选目标或连接已改变" }
        val connected = replaceClient(client.copy(online = true))
        workspaces[connected.cacheKey(null)] = snapshot
        select(connected)
    }
    suspend fun connectCodexDiscovered(host: DiscoveredCodexHost): MobileClient {
        val store = storage ?: error("本地安全存储不可用")
        val prior = realClients.firstOrNull { it.id == host.id }
        val revision = revisions[host.id] ?: 0
        val selection = selectionRevision; val sourceKey = targetKey; val sourceVersion = version(sourceKey)
        val identity = EasyWorkIdentity.load(store)
        val authorized = CodexDiscoveryAPI.authorize(host, identity, store) { descriptor ->
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
            codexDirect=PairedCodexConnection(target, authorized.descriptor.fingerprint, routes), storageIdentity=prior?.storageIdentity)
        // No suspension between the final generation check, atomic credential/client publication and UI commit.
        CodexConnectionVault(store).save(client, authorized.token)
        revisions[host.id] = revision + 1
        val remembered = replaceClient(client)
        workspaces[remembered.cacheKey(null)] = authorized.workspace
        select(remembered); discovery?.scan()
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
                val descriptor = NativeHostReads { "" }.descriptor(client)
                val merged = (listOf(route) + descriptor.routes + offer.routes).distinctBy { it.endpoint }.take(32)
                client = client.copy(codexDirect = direct.copy(routes = merged))
                val snapshot = NativeHostReads { offer.token!! }.workspace(client)
                require(snapshot.mode == "desktop-runtime" && sameScope(snapshot.scope, offer.target)) { "电脑运行时身份不匹配" }
                require((revisions[client.id] ?: 0) == revision) { "连接已改变，请重试" }
                withContext(Dispatchers.IO) { storage?.setToken(client.id, offer.token!!) }
                client = client.copy(online = true)
                revisions[client.id] = revision + 1; client = replaceClient(client)
                workspaces[client.cacheKey(null)] = snapshot
                select(client); discovery?.scan(); return client
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
        val direct = current.codexDirect?.copy(routes = (listOf(preferred) + descriptor.routes + current.codexDirect.routes).distinctBy { it.endpoint }.take(32))
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
            loadingTargets[clientKey]=false;historyTargets[targetKey]=false
            refresh();loadHistory()
        }
        return true
    }
    fun removeClient(id: String) {
        val removed=realClients.firstOrNull { it.id==id } ?: return
        // A removed private client must not be recovered from its atomic credential record after a crash.
        if(removed.agentConnection==null) try { storage?.setToken(id,"") } catch(_: Exception) { storageFailure();return }
        revisions[id] = (revisions[id] ?: 0) + 1
        realClients.removeAll { it.id == id }
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
        if (storage == null || restoring) return
        val json = JSONObject().put("version", 1).put("appearance", appearance).put("demo", demo).put("selected", selectedClient.id)
            .put("clients", JSONArray(realClients.map(::encodeClient)))
            .put("drafts", JSONObject(drafts.toMap())).put("selectedThreads", JSONObject(selectedThreads.toMap()))
            .put("draftVersions",JSONObject(draftVersions.toMap())).put("newProjects",JSONObject(newProjects.toMap()))
            .put("draftFiles",JSONObject(draftFiles.mapValues { JSONArray(it.value.map { f -> f.manifest.body() }) }))
            .put("generation",JSONObject(generation.mapValues { it.value.append(JSONObject()) })).put("replyModes",JSONObject(preferredModes.toMap()))
            .put("generationPreferences",JSONObject(generationPreferences.mapValues { it.value.append(JSONObject()) }))
            .put("dismissedReceiptFailures",JSONObject(dismissedReceiptFailures.mapValues { JSONArray(it.value.toList()) }))
            .put("expanded", JSONObject(expandedProjects.mapValues { JSONArray(it.value.toList()) }))
            .put("sidebar", JSONObject(sidebarStates.mapValues { (_,state) -> JSONObject().put("recent",state.recentExpanded).put("projects",state.projectsExpanded).put("lists",JSONArray(state.expandedLists.toList())) }))
            .put("workspaces", JSONObject(workspaces.mapValues { JSONObject(it.value.wire) }))
        storage.enqueue("metadata", json.toString()) { storageFailure() }
    }
    private fun persistHistory(key: String, client: MobileClient, thread: String, result: MobileHistory, messages: List<MobileMessage>) {
        if (storage == null) return
        val snapshot=messages.toList()
        storage.enqueueSnapshot("history:$key",{ NativeHistoryCache(storage).save(key,client,thread,result,snapshot) }) { storageFailure() }
    }

    private fun loadCachedHistory() {
        val storage = storage ?: return; val key = targetKey; val selection = selectionRevision
        if (histories.containsKey(key) || selectedThread.isEmpty()) return
        tasks.launch {
            try {
                val page = withContext(Dispatchers.IO) { NativeHistoryCache(storage).read(key) } ?: return@launch
                if (targetKey == key && selectionRevision == selection && !histories.containsKey(key) && page.hostId == selectedClient.id && page.threadId == selectedThread && (!selectedClient.requiresReadTarget || sameScope(page.scope, selectedClient.scope))) { histories[key] = page.messages; historyPages[key] = page }
            } catch (_: Exception) { if (targetKey == key) error = "本地缓存无法读取，原缓存已保留" }
            catch (_: OutOfMemoryError) { if(targetKey == key) error = "本地缓存内存不足，原缓存已保留" }
        }
    }
    fun restore() {
        if (storage == null) return
        tasks.launch {
            try {
                aiSharing.restore()
                val (json, privateClients) = withContext(Dispatchers.IO) { storage.metadata() to CodexConnectionVault(storage).clients() }
                if (json != null) {
                    require(json.getInt("version") == 1)
                    val storageKeys=listOf("drafts","draftVersions","draftFiles","selectedThreads","newProjects","generation","generationPreferences","replyModes","dismissedReceiptFailures","expanded","sidebar","workspaces").flatMap { field -> json.optJSONObject(field)?.keys()?.asSequence()?.toList().orEmpty() }.distinct()
                    realClients.clear(); realClients.addAll(json.getJSONArray("clients").objects().map(::decodeClient).map { it.copy(online = false, storageIdentity=it.storageIdentity?.withKeys(storageKeys)) })
                    appearance = json.getString("appearance"); demo = json.getBoolean("demo")
                    json.getJSONObject("drafts").let { v -> v.keys().forEach { drafts[it] = v.getString(it) } }
                    json.optJSONObject("draftVersions")?.let { v -> v.keys().forEach { draftVersions[it]=v.getString(it) } }
                    json.optJSONObject("newProjects")?.let { v -> v.keys().forEach { newProjects[it]=v.optional(it) } }
                    json.optJSONObject("draftFiles")?.let { v -> v.keys().forEach { key -> draftFiles[key]=v.getJSONArray(key).objects().map { f -> LocalDraftFile(CreationFile(f.getString("id"),f.getString("name"),f.getString("mediaType"),f.getLong("sizeBytes"),f.getString("sha256"))) } } }
                    json.optJSONObject("generation")?.let { v -> v.keys().forEach { key -> val s=v.getJSONObject(key); generation[key]=GenerationSettings(s.optional("model"),s.optional("effort"),s.optional("permissionMode"),s.optional("speed"),s.optional("collaborationMode")) } }
                    json.optJSONObject("generationPreferences")?.let { v -> v.keys().forEach { key -> val g=v.getJSONObject(key); generationPreferences[key]=GenerationSettings(g.optional("model"),g.optional("effort"),g.optional("permissionMode"),g.optional("speed")) } }
                    json.optJSONObject("dismissedReceiptFailures")?.let { v -> v.keys().forEach { key -> dismissedReceiptFailures[key]=v.getJSONArray(key).strings().toSet() } }
                    json.optJSONObject("replyModes")?.let { v -> v.keys().forEach { preferredModes[it]=v.getString(it) } }
                    json.getJSONObject("selectedThreads").let { v -> v.keys().forEach { selectedThreads[it] = v.getString(it) } }
                    json.getJSONObject("expanded").let { v -> v.keys().forEach { expandedProjects[it] = v.getJSONArray(it).strings().toSet() } }
                    json.optJSONObject("sidebar")?.let { v -> v.keys().forEach { key -> val state=v.getJSONObject(key); sidebarStates[key]=SidebarDisplayState(state.getBoolean("recent"),state.getBoolean("projects"),state.getJSONArray("lists").strings().toSet()) } }
                    json.getJSONObject("workspaces").let { v -> v.keys().forEach { workspaces[it] = MobileSnapshotCodec.workspace(v.getJSONObject(it).toString()) } }
                    selectedClient = clients.firstOrNull { it.id == json.getString("selected") } ?: clients.firstOrNull() ?: DemoMobileData.clients.first()
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
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { error = "本地连接与缓存无法读取，原文件已保留" }
            finally { restoring = false }
            if (hasClient && !selectedClient.isDemo) { loadCachedHistory(); refresh(); loadHistory(); loadOperations() }
        }
    }
    internal fun resetAcceptance(reader: HostReads? = null,commandProvider: CodexCommands? = null) {
        aiSharing.clearAcceptance();aiSharingRequest=null
        tasks.coroutineContext.cancelChildren(); pagedSelections.clear(); realClients.clear(); selectedThreads.clear(); drafts.clear(); histories.clear(); historyPages.clear(); runs.clear(); workspaces.clear(); loadingTargets.clear(); historyTargets.clear(); expandedProjects.clear(); sidebarStates.clear(); revisions.clear()
        selectedClient = DemoMobileData.clients.first(); selectionRevision++; visibleMessageLimit = 60; revealedEarlier = false
        demo = false; appearance = "system"; restoring = false; error = null; providers=registry(reader,commandProvider); reads=providers
        busyOwners.clear(); busyCommands.clear(); freshWorkspaces.clear(); freshHistories.clear(); draftVersions.clear(); draftFiles.clear(); generation.clear(); generationPreferences.clear(); dismissedReceiptFailures.clear(); modelErrors.clear(); preferredModes.clear(); newProjects.clear(); operations.clear(); busyTargets.clear(); stoppingTargets.clear(); answeringTargets.clear(); modelsByClient.clear(); foreground=true; importing=false
        commands=providers; execution=storage?.let { NativeCodexFlow(it,reads,commands) }; eventJob=null
        observingOperations.clear()
        forkNavigation.clear();forkOptions.clear();forkOptionErrors.clear();forkOptionsLoading.clear();forkRequests.clear()
    }
    internal fun installAcceptanceHistory(messages: List<MobileMessage>, cursor: String? = null) { histories[targetKey] = messages; visibleMessageLimit = 60; revealedEarlier = false }
}

internal fun encodeClient(client: MobileClient) = JSONObject().put("id", client.id).put("name", client.name).put("endpoint", client.endpoint).put("requiresReadTarget", client.requiresReadTarget).put("codexDirect", client.codexDirect?.let(::encodeCodexDirect)).put("agentConnection",client.agentConnection?.json())
    .put("storageIdentity",client.storageIdentity?.json() ?: JSONObject.NULL).put("mode", client.mode).put("readerId", client.readerId).put("scope", client.scope?.let { s -> JSONObject().put("agentId", s.agentId).put("hostId", s.hostId).put("workspaceId", s.workspaceId).put("runtimeId", s.runtimeId).apply { if (s.accountAuthority != null) put("account", JSONObject().put("authority", s.accountAuthority).put("subject", s.accountSubject)) } })
internal fun decodeClient(json: JSONObject) = MobileClient(json.getString("id"), json.getString("name"), HostReadCodec.target(json.optJSONObject("scope")), json.getString("endpoint"), false, json.getString("mode"), false, json.optional("readerId"), json.optBoolean("requiresReadTarget"), json.optJSONObject("codexDirect")?.let(::decodeCodexDirect),agentConnection=json.optJSONObject("agentConnection")?.let(AgentConnection::decode),storageIdentity=json.optJSONObject("storageIdentity")?.let(MobileStorageIdentity::decode) ?: if(json.has("storageIdentity")) null else HostReadCodec.target(json.optJSONObject("scope"))?.takeIf { it.agentId=="codex" }?.let { MobileStorageIdentity(it,emptyList()) }).also { require(it.codexDirect == null || sameScope(it.scope, it.codexDirect.target));require(it.agentConnection==null||sameScope(it.scope,it.agentConnection.target)) }
internal fun encodeWorkspace(w: MobileWorkspace) = JSONObject(w.wire).put("projects",JSONArray(w.projects.map { JSONObject().put("id",it.id).put("hostId",it.hostId).put("name",it.name).put("roots",JSONArray(it.roots)) })).put("threads",JSONArray(w.threads.map { JSONObject().put("id",it.id).put("hostId",it.hostId).put("title",it.title).put("state",it.state).put("projectId",it.projectId).put("projectIds",JSONArray(it.projectIds)).put("hasUnreadTurn",it.unread) }))
internal fun encodeMessage(m: MobileMessage) = JSONObject().put("id", m.id).put("role", m.role).put("text", m.text).put("status", m.status).put("kind", m.kind).put("detail", m.detail).put("runId", m.runId).put("replyPhase", m.replyPhase).put("replyMessageId", m.replyMessageId).put("files", JSONArray(m.files)).put("blocks", m.blocks?.let(::JSONArray)).put("attachments", JSONArray(m.attachments.map { f -> JSONObject().put("id", f.id).put("name", f.name).put("mediaType", f.mediaType).put("sizeBytes", f.sizeBytes).put("hostId", f.hostId).put("threadId", f.threadId).put("sha256", f.sha256) }))

object DemoMobileData {
    val clients = listOf(MobileClient("demo-mac", "我的 MacBook", online = true), MobileClient("demo-pc", "办公电脑", online = true), MobileClient("demo-home", "家里的 Mac"))
    val projects = listOf(MobileProject("mobile", "demo-mac", "Agent Hub", emptyList()), MobileProject("api", "demo-mac", "api-service", emptyList()), MobileProject("design", "demo-mac", "design-system", emptyList()), MobileProject("web", "demo-pc", "web-studio", emptyList()))
    val threads = listOf("设计移动端导航", "梳理设备配对流程", "优化查询性能", "统一组件间距", "核对审批流程", "补齐安卓页面", "验收长列表").mapIndexed { index, title -> MobileThread(if (index == 0) "demo-chat" else "demo-thread-$index", "demo-mac", title, if (index == 4) "waiting-for-approval" else "idle", if (index < 2) "mobile" else if (index == 2) "api" else "design", unread = index == 3) } + MobileThread("demo-chat", "demo-pc", "设计网页布局", "idle", "web")
    val messages = listOf(MobileMessage("demo-user", "user", "照着 ChatGPT APP 来做界面，右上角能切换不同的 Codex 客户端。"),
        MobileMessage("demo-assistant", "assistant", "可以，我们保留熟悉的聊天结构。\n\n1. 左侧栏：选择项目、继续对话或新建对话。\n2. 聊天区：保留清晰的消息排版和底部输入框。\n3. 右上角：切换任务所在的 Agent 客户端。\n\n当前对话属于 Agent Hub。", "completed"))
}
