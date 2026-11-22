package com.agenthub.android

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Only this adapter knows EasyWork protocol v2 method names and provider content parts. */
class EasyWorkAgentClient(private val sessions: EasyWorkChannels): AgentReads,AgentCommands,AgentTaskNotifications {
    private val pushRegistrationCapabilities=object:LinkedHashMap<String,Boolean>(16,.75f,true) {
        override fun removeEldestEntry(eldest:MutableMap.MutableEntry<String,Boolean>?)=size>32
    }
    private suspend fun supportsTaskPushRegistration(channel:EasyWorkRemoteSession):Boolean {
        val key=easyHash(easyCanonical(channel.target.json()).toByteArray())+":"+channel.epoch
        synchronized(pushRegistrationCapabilities) { pushRegistrationCapabilities[key] }?.let { return it }
        val context=channel.request("runtime/context")
        require(EasyWorkTarget.decode(context.getJSONObject("target"))==channel.target)
        val supported="notifications:registration:fcm:v1" in context.getJSONArray("capabilities").strings()
        if(key!=easyHash(easyCanonical(channel.target.json()).toByteArray())+":"+channel.epoch)return false
        synchronized(pushRegistrationCapabilities) { pushRegistrationCapabilities[key]=supported };return supported
    }
    override suspend fun supportsTaskPushRegistration(client:MobileClient)=supportsTaskPushRegistration(channel(client))
    private val generatedCapabilities=object: LinkedHashMap<String,Boolean>(16,.75f,true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String,Boolean>?)=size>32
    }
    override suspend fun registerTaskNotifications(client: MobileClient,registration: NativeTaskNotificationRegistration): NativeTaskNotificationStatus {
        require(registration.deviceId.matches(Regex("[A-Za-z0-9._-]{1,128}")) && registration.deviceId !in setOf("__proto__","constructor","prototype"))
        require(registration.publicKey.matches(Regex("[A-Za-z0-9+/]{43}=")))
        val channel=channel(client);val target=channel.target.scope();val preferences=NativeTaskNotificationCodec.preferences(registration.preferences.json())
        val value=JSONObject().put("version",1).put("deviceId",registration.deviceId).put("target",CodexCommandCodec.target(target))
            .put("publicKey",registration.publicKey).put("preferences",preferences.json())
        registration.push?.let { push->
            require(supportsTaskPushRegistration(channel)) { "此电脑版本暂未支持后台提醒。" }
            value.put("pushProvider","fcm").put("deviceToken",push.token)
        }
        return NativeTaskNotificationCodec.status(channel.request("notification/register",JSONObject().put("registration",value)),target)
    }
    override suspend fun updateTaskNotificationPreferences(client: MobileClient,deviceId: String,preferences: NativeTaskNotificationPreferences): NativeTaskNotificationStatus {
        require(deviceId.matches(Regex("[A-Za-z0-9._-]{1,128}")))
        val channel=channel(client);val target=channel.target.scope();val checked=NativeTaskNotificationCodec.preferences(preferences.json())
        return NativeTaskNotificationCodec.status(channel.request("notification/preferences",JSONObject().put("deviceId",deviceId).put("target",CodexCommandCodec.target(target)).put("preferences",checked.json())),target)
    }
    override suspend fun taskNotificationEvents(client: MobileClient,deviceId: String,after: String): NativeTaskNotificationFeed {
        require(deviceId.matches(Regex("[A-Za-z0-9._-]{1,128}")) && (after.isEmpty() || com.agenthub.core.TaskNotificationRules.eventId(after)))
        val channel=channel(client)
        return NativeTaskNotificationCodec.feed(channel.request("notification/events",JSONObject().put("deviceId",deviceId).put("after",after)),channel.target.scope())
    }
    override suspend fun unregisterTaskNotifications(client: MobileClient,deviceId: String) {
        require(deviceId.matches(Regex("[A-Za-z0-9._-]{1,128}")))
        channel(client).request("notification/unregister",JSONObject().put("deviceId",deviceId))
    }
    override suspend fun taskNotificationStatus(client: MobileClient,deviceId: String): NativeTaskNotificationStatus {
        require(deviceId.matches(Regex("[A-Za-z0-9._-]{1,128}")))
        val channel=channel(client)
        return NativeTaskNotificationCodec.status(channel.request("notification/status",JSONObject().put("deviceId",deviceId)),channel.target.scope())
    }
    override suspend fun nextTurnSuggestion(client: MobileClient,scope: com.agenthub.core.SuggestionScope): MobileDesktopSuggestionResponse = boundedDesktopSuggestionRead {
        require(scope.target.agentId=="easywork"&&sameScope(client.scope,scope.target)) { "桌面建议目标不匹配" }
        val c=channel(client)
        require(c.target.desktopDeviceId==scope.target.hostId&&c.target.workspaceId==scope.target.workspaceId&&
            c.target.runtimeId==scope.target.runtimeId&&c.target.authority==scope.target.accountAuthority&&
            c.target.subject==scope.target.accountSubject) { "桌面建议运行时不匹配" }
        val result=c.request("thread/suggestion/read",JSONObject().put("threadId",scope.threadId).put("sourceTurnId",scope.sourceTurnId))
        // The encrypted response envelope has already authenticated this exact runtime/account target.
        MobileDesktopSuggestionResponse.decode(JSONObject(result.toString()).put("target",CodexCommandCodec.target(scope.target)),scope)
    }
    private val resolvedResources=object: LinkedHashMap<String,MobileFile>(64,.75f,true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String,MobileFile>?)=size>256
    }
    override fun agentTitle(client: MobileClient)="EasyWork"
    override fun permissionOptions(client: MobileClient)=listOf(AgentPermissionOption("confirm_every_operation","逐项确认","每项受权限管理的工具操作都请求确认",appOwnedCopy=true),AgentPermissionOption("confirm_high_risk","高风险确认","按 EasyWork 风险策略对高风险操作请求确认",appOwnedCopy=true),AgentPermissionOption("allow_all","允许已授权操作","免去任务工具确认，仍受设备授权和 Host 边界约束",true,appOwnedCopy=true))
    private suspend fun channel(client: MobileClient): EasyWorkRemoteSession {NativeMigrationEasyWorkGate.requireReady(client);return sessions.session(client)}
    private fun project(client: MobileClient,v: JSONObject)=MobileProject(v.getString("id"),client.id,v.getString("name"),listOfNotNull(v.optional("workspacePath")))
    override suspend fun createProject(client: MobileClient,name: String,directory: String,operationId: String): MobileProject {
        require(name.isNotBlank()&&name.length<=500&&directory.isNotBlank()&&directory.length<=4096)
        val c=channel(client)
        val params=try {
        val access=c.request("filesystem/access-status").optJSONObject("access")
        if(access?.optString("status")!="granted") { c.request("filesystem/request-access");error("请在 EasyWork 桌面确认目录授权，然后重试。") }
        val roots=c.request("filesystem/roots").getJSONArray("roots").objects()
        val requested=if(directory.startsWith('/'))java.nio.file.Paths.get(directory).normalize().toString() else {
            val root=c.request("runtime/context").getString("workspaceDirectory");require(root.startsWith('/')) { "此电脑未提供默认目录，请填写完整目录路径" }
            val path=java.nio.file.Paths.get(root,directory).normalize().toString();require(path==root||path.startsWith(root.trimEnd('/')+"/")) { "相对路径不能超出电脑工作目录" };path
        }
        fun contains(root: JSONObject)=root.getString("displayPath").let { requested==it||requested.startsWith(it.trimEnd('/')+"/") }
        var selected=roots.filter(::contains).maxByOrNull { it.getString("displayPath").length }?:error("目录不在此设备已授权范围中")
        val seen=mutableSetOf<String>()
        while(selected.getString("displayPath")!=requested) {
            require(seen.add(selected.getString("directoryRef"))) { "电脑目录引用没有前进" }
            selected=c.request("filesystem/list-directories",JSONObject().put("directoryRef",selected.getString("directoryRef"))).getJSONArray("directories").objects().firstOrNull(::contains)?:error("请选择已存在且已授权的目录")
        }
        JSONObject().put("name",name).put("directoryRef",selected.getString("directoryRef")).put("clientRequestId",operationId)
        } catch(cancel: kotlinx.coroutines.CancellationException) { throw cancel } catch(failure: Exception) { throw AgentOperationNotSubmitted(failure.message,failure) }
        return project(client,c.request("project/create",params,operationId).getJSONObject("project"))
    }
    override suspend fun createdProject(client: MobileClient,operationId: String): MobileProject {
        val status=channel(client).request("command/status",JSONObject().put("commandId",operationId))
        require(status.optString("method")=="project/create"&&status.optString("operationId")==operationId) { "项目创建结果尚未确认，请核对电脑项目列表。原操作已保留。" }
        require(EasyWorkTarget.decode(status.getJSONObject("target")).scope().let { sameScope(it,client.scope) }) { "项目创建回执不属于此完整目标" }
        return project(client,status.optJSONObject("response")?.optJSONObject("result")?.optJSONObject("project")?:error("项目创建未完成，原操作已保留"))
    }
    private fun state(active: JSONObject?): String = when(active?.optString("status")) {
        null,"completed"->"idle";"running","queued","starting","pending"->"running";"paused","interrupted","cancelled","failed"->active.getString("status");else->"unknown"
    }
    private fun conversation(client: MobileClient,v: JSONObject)=MobileThread(v.getString("id"),client.id,v.optional("title")?:v.optional("firstUserMessageText")?:"新对话",state(v.optJSONObject("activeRun")),v.optional("projectId"),unread=v.optBoolean("hasUnreadTurn"))
    override suspend fun workspace(client: MobileClient,projectCursor: String?,threadCursor: String?): MobileWorkspace {
        val c=channel(client);val context=c.request("runtime/context")
        require(EasyWorkTarget.decode(context.getJSONObject("target"))==c.target)
        val learned=client.agentConnection?.let { EasyWorkAuthenticatedCandidates.read(context,c.target,it) }
        learned?.let { c.candidates=it.candidates }
        val projects=c.request("project/list").getJSONArray("projects").objects().map { MobileProject(it.getString("id"),client.id,it.optional("name")?:"项目",listOfNotNull(it.optional("workspacePath"))) }
        val listed=c.request("thread/list",JSONObject().put("limit",100).apply { threadCursor?.let { put("cursor",it) } })
        val threads=listed.getJSONArray("threads").objects().map { conversation(client,it) }
        val observed=context.getJSONObject("state");val caps=context.getJSONArray("capabilities").strings().toMutableList()
        // This handshake has verified the full runtime target, independent of its path.
        if(observed.optBoolean("runtimeConnected"))caps.add("commands:target-bound:v1")
        val wire=MobileSnapshotCodec.header(client).put("name",client.name).put("connectionMode","easywork-runtime").put("projects",JSONArray(projects.map { JSONObject().put("id",it.id).put("hostId",client.id).put("name",it.name).put("roots",JSONArray(it.roots)) })).put("threads",JSONArray(threads.map(::encodeThread))).put("nextThreadCursor",listed.optional("nextCursor")).put("capabilities",JSONArray(caps.distinct())).put("runtimeConnected",observed.getBoolean("runtimeConnected"))
        return MobileSnapshotCodec.workspace(wire.toString()).copy(authenticatedConnection=learned)
    }
    override suspend fun usage(client: MobileClient):String {
        val id=java.util.UUID.randomUUID().toString()
        val value=channel(client).request("usage/read",JSONObject().put("schemaVersion",1).put("requestId",id).put("target",usageTarget(client)))
        require(value.optString("requestId")==id)
        return value.toString()
    }
    override suspend fun models(client: MobileClient): List<CodexModel> {
        val v=channel(client).request("model/list");return v.getJSONArray("models").objects().map { CodexModel(it.getString("id"),it.getString("name"),it.getBoolean("isDefault"),it.optional("defaultEffort"),it.optJSONArray("supportedEfforts")?.strings().orEmpty(),it.optional("defaultSpeed"),it.optJSONArray("supportedSpeeds")?.strings().orEmpty()) }
    }
    private fun resource(client: MobileClient,thread: String,v: JSONObject): MobileFile? {
        val id=v.optional("resourceId")?:return null;val hash=v.optional("sha256")?:return null
        require(hash.matches(Regex("[a-f0-9]{64}")));val size=v.optLong("size");require(size>=0)
        return MobileFile(id,v.optional("name")?:"文件",v.optional("contentType")?:"application/octet-stream",size,client.id,thread,hash)
    }
    private fun messageParts(client: MobileClient,thread: String,original: JSONObject): List<MobileMessage> {
        val id=original.getString("id");val role=original.getString("role");val status=original.optional("status")
        return original.getJSONArray("content").objects().flatMapIndexed { index,part ->
            val type=part.getString("type");val data=JSONObject(part.toString());part.optJSONObject("data")?.let { p->p.keys().forEach { data.put(it,p.get(it)) } }
            val key="$id:$index";val extension=JSONObject().put("type","extension").put("namespace","easywork.agent-protocol-v2.$type").put("data",data)
            val blocks=JSONArray().put(extension)
            when(type) {
                "text"->listOf(MobileMessage(key,role,data.optString("text"),status,blocks=JSONArray().put(JSONObject().put("type","text").put("text",data.optString("text"))).toString()))
                "reasoning"->listOf(MobileMessage(key,"tool","",if(status=="complete")"completed" else if(status in listOf("running","streaming"))"inProgress" else status,"reasoning",data.optString("text"),blocks=blocks.toString()))
                "plan-artifact"->listOf(MobileMessage(key,"assistant",data.optString("markdown"),data.optional("status")?:status,"plan"))
                "token-usage"->emptyList()
                "agent-debug"-> {
                    val diagnostics=data.optJSONArray("contextCompressionSampling")?.objects().orEmpty().mapIndexedNotNull { offset,item->
                        val confirmed=item.optString("projectionStatus") in listOf("created","regenerated","repaired")
                        if(!confirmed&&item.optString("action")!="compression_started")null else MobileMessage("$key:compression:$offset","tool",if(confirmed)"上下文已压缩" else "上下文压缩",if(confirmed)"completed" else if(data.optString("status")=="running")"inProgress" else "interrupted","contextCompaction",easyCanonical(item))
                    }
                    diagnostics+if(data.optString("status")=="failed"&&data.has("errorMessage"))listOf(MobileMessage(key,"tool",data.getString("errorMessage"),"failed","tool")) else emptyList()
                }
                "context-compression","context-compaction","contextCompaction"->listOf(MobileMessage(key,"tool",data.optional("summary")?:"上下文压缩",data.optional("status")?:status,"contextCompaction",data.optional("detail")))
                "attachment"->listOfNotNull(resource(client,thread,data)?.let { MobileMessage(key,role,"",status,attachments=listOf(it),blocks=JSONArray().put(JSONObject().put("type","resource").put("id",it.id).put("name",it.name).put("mediaType",it.mediaType)).toString()) })
                else-> {
                    val args=data.optJSONObject("args");val text=args?.optional("target")?:args?.optional("description")?:data.optional("toolName")?:data.optional("name")?:type
                    val toolState=if(type=="tool-call") if(data.optBoolean("isError"))"failed" else if(data.has("result"))"completed" else if(status=="interrupted")"interrupted" else "inProgress" else data.optional("status")?:"completed"
                    val patch=(args?.optional("patch")?:args?.optional("stdin"))?.takeIf { it.startsWith("*** Begin Patch") };val diff=data.optJSONObject("result")?.optional("diff")?:patch
                    val isDiff=type in listOf("diff","file-change","fileChange")||type=="tool-call"&&diff!=null
                    val detail=if(isDiff)diff?:data.optional("diff")?:data.optional("text")?:easyCanonical(data) else easyCanonical(data)
                    if(type=="tool-call")blocks.put(JSONObject().put("type","tool").put("id",data.optional("toolCallId")?:key).put("name",text).put("status",toolState).put("input",args?.let(::easyCanonical)).put("output",data.opt("result")?.let(::easyCanonical)))
                    if(isDiff)blocks.put(JSONObject().put("type","diff").put("id",key).put("text",detail))
                    listOf(MobileMessage(key,"tool",text,toolState,if(isDiff)"fileChange" else "tool",detail,blocks=blocks.toString()))
                }
            }.map { it.copy(runId=original.optional("sourceRunId"),replyMessageId=id) }
        }
    }
    override suspend fun subagentHistory(client: MobileClient,request: MobileSubagentHistoryRequest): MobileSubagentHistory {
        val c=channel(client);val page=c.synchronizedThread(request.parentThreadId)
        val selected=EasyWorkSubagentHistory.select(page,request)
        val messages=EasyWorkSubagentHistory.originals(selected).flatMap { messageParts(client,request.parentThreadId,it) }.toMutableList()
        selected.run.optJSONObject("error")?.optional("message")?.takeIf { it.isNotEmpty() }?.let {
            messages.add(MobileMessage(selected.id+":error","tool",it,"failed","turnFailure",runId=selected.id))
        }
        val scope=easyHash(easyCanonical(JSONObject().put("target",CodexCommandCodec.target(checkNotNull(client.scope)))
            .put("parentThreadId",request.parentThreadId).put("messageId",request.messageId).put("childId",selected.id)
            .put("rootRunId",selected.root).put("branchRevision",selected.revision)).toByteArray())
        val window=EasyWorkSubagentHistory.page(messages,scope,request.cursor)
        val title=c.subagentActivities(page.getJSONObject("snapshot"),request.parentThreadId).firstOrNull { it.id==request.messageId }?.title
            ?:selected.run.optJSONObject("input")?.optional("title")?:selected.id
        val status=when(val value=selected.run.optString("status","unknown")) { "running","starting","in_progress"->"inProgress";"cancelled"->"interrupted";else->value }
        return MobileSubagentHistory(client.id,checkNotNull(client.scope),request.parentThreadId,request.messageId,
            listOf(MobileSubagentChild(selected.id,title,status)),selected.id,"saved-history",window.messages,window.next)
    }
    override suspend fun history(client: MobileClient,threadId: String,cursor: String?): MobileHistory {
        val c=channel(client)
        val latestPage=c.synchronizedThread(threadId)
        val revision=latestPage.getLong("branchRevision").also { require(it>=0) }
        val value=if(cursor==null)latestPage else {
            val page=c.request("thread/history",JSONObject().put("threadId",threadId).put("before",cursor).put("limit",60))
            require(c.synchronizedThread(threadId).getLong("branchRevision")==revision) { "聊天历史已变化，请刷新后重试。" }
            page
        }
        val snapshot=value.optJSONObject("snapshot")?:JSONObject();val thread=snapshot.optJSONObject("thread")?:JSONObject().put("id",threadId);require(thread.getString("id")==threadId)
        val active=snapshot.optJSONObject("activeRun");val latest=snapshot.optJSONObject("latestRun");val control=snapshot.optJSONObject("runControl")
        val permission=snapshot.optJSONObject("pendingPermissionConfirmation")
        val questions=EasyWorkQuestionProjection.approvals(snapshot,threadId)
        val state=if(permission!=null||questions.any { it.optString("interactionMode")!="async" })"waiting-for-approval" else if(control?.optString("phase")=="paused")"paused" else if(active!=null)state(active) else latest?.optString("status")?.takeIf { it in listOf("failed","cancelled","interrupted") }?:"idle"
        val approvals=JSONArray()
        if(permission!=null) {
            val identity=JSONObject();for(key in listOf("threadId","runId","toolCallId","operationId","requestHash"))identity.put(key,permission.getString(key).also { require(it.isNotEmpty()) });require(identity.getString("threadId")==threadId)
            val summary=permission.optJSONArray("argsSummary")?.objects()?.joinToString("\n") { (mapOf("command" to "命令","workdir" to "目录","shell" to "终端")[it.optString("name")]?:it.optString("name"))+": "+it.optString("value") }.orEmpty()
            approvals.put(JSONObject().put("id","approval."+easyBase64(easyCanonical(identity).toByteArray())).put("threadId",threadId).put("turnId",identity.getString("runId")).put("kind","command").put("title",permission.optional("title")?:"操作需要确认").put("detail",permission.optional("message")?:permission.optional("description")?:summary.ifEmpty { "请确认此操作。" }).put("questions",JSONArray()).put("decisions",JSONArray(listOf("approve","deny"))))
        }
        questions.forEach { approvals.put(it) }
        var messages=value.getJSONArray("messages").objects().flatMap { messageParts(client,threadId,it) }
        if(cursor==null)messages=EasyWorkCompressionProjection.applying(snapshot,threadId,revision,messages)
        if(cursor==null)messages=messages+c.subagentActivities(snapshot,threadId).map {
            MobileMessage(it.id,"tool",it.title,it.status,"subAgent",it.detail,runId=it.rootRunId)
        }
        if(cursor==null&&active==null)messages=messages.map { m->
            if(m.role!="assistant")m else {
                val files=Regex("\\]\\(([^\\s\\)]+)\\)").findAll(m.text).mapNotNull { match->
                    val reference=UriReference.decode(match.groupValues[1]);if(reference.startsWith("http:")||reference.startsWith("https:"))null else reference
                }.distinct().toList().mapNotNull { reference->
                    val cacheKey=easyCanonical(JSONArray(listOf(CodexCommandCodec.target(client.scope!!),threadId,m.replyMessageId,m.runId,reference)))
                    val cached=synchronized(resolvedResources) { resolvedResources[cacheKey] }
                    try { cached?:resource(client,threadId,c.request("resource/resolve",JSONObject().put("threadId",threadId).put("reference",reference)).getJSONObject("attachment"))?.also { synchronized(resolvedResources) { resolvedResources[cacheKey]=it } } }
                    catch(cancel: kotlinx.coroutines.CancellationException) { throw cancel } catch(_: Exception) { null }
                }.toList();if(files.isEmpty())m else m.copy(attachments=(m.attachments+files).distinctBy { it.id })
            }
        }
        val plan=snapshot.optJSONObject("currentPlan")
        // A recorded run is authoritative for every field. Missing legacy values stay
        // unknown rather than inheriting today's model, effort, permission or speed.
        val preferences=latest?.optJSONObject("settings") ?: if(latest==null) thread.optJSONObject("generationPreferences") else null
        val model=preferences?.optJSONObject("modelRef");val settings=JSONObject().put("model",model?.let { it.getString("providerId")+"/"+it.getString("modelId") }).put("effort",preferences?.optJSONObject("reasoning")?.optional("effort")).put("permissionMode",preferences?.optional("permissionMode")).put("speed",preferences?.optional("speed"))
        val receipts=snapshot.optJSONArray("queueReceipts")?.objects()?.map { q->
            encodeReceipt(easyWorkQueueReceipt(client.id,threadId,q))
        }?.toMutableList()?:mutableListOf()
        if(active==null&&latest?.optString("status") in listOf("failed","cancelled","interrupted")) receipts.add(encodeReceipt(DeliveryReceipt(client.id,threadId,latest!!.getString("id"),latest.getString("status"),latest.getString("id"),latest.optional("error"),null,null,emptyList())))
        val runtime=if(cursor!=null)null else JSONObject().put("state",state).put("suggestionSourceTurnId",if(state=="idle")snapshot.optional("suggestionSourceTurnId")else null).put("turnId",active?.optional("runId")?:active?.optional("id")?:control?.optional("runId")?.takeIf { control.optString("phase") in listOf("paused","pausing","resuming") }).put("plan",JSONArray(plan?.optJSONArray("steps")?.objects()?.map { JSONObject().put("step",it.optString("title")).put("status",if(it.optString("status")=="in_progress")"inProgress" else it.optString("status","pending")) }.orEmpty())).put("error",if(active==null)latest?.optional("error") else null).put("approvals",approvals).put("receipts",JSONArray(receipts)).put("canControl",true).put("collaborationMode",if((active?.optional("agentRunMode")?:preferences?.optional("agentRunMode"))=="plan")"plan" else "default").put("generationSettings",settings).put("generationSpeed",EasyWorkTaskSpeed.project(latest)?.json()).apply {
            if(plan!=null&&plan.optString("status") in listOf("ready","saved"))put("proposedPlan",JSONObject().put("id","easywork-plan."+easyBase64(easyCanonical(JSONObject().put("id",plan.getString("planId")).put("version",plan.getInt("version"))).toByteArray())).put("turnId",plan.optString("createdRunId")).put("text",plan.optString("markdown")))
        }
        val wire=MobileSnapshotCodec.header(client).put("threadId",threadId).put("messages",JSONArray(messages.map(::encodeMessage))).put("nextCursor",if(value.optBoolean("hasEarlier"))value.optional("before") else null).put("runtime",runtime).put("epoch",c.epoch).put("historyWindow",true).put("historyRevision",revision)
        return MobileSnapshotCodec.history(wire.toString())
    }
    override suspend fun events(client: MobileClient,threadId: String,epoch: String,cursor: Long): EventBatch {
        val c=channel(client);val eventEpoch=c.target.runtimeId+":"+c.epoch;val next=c.events(threadId,if(epoch==eventEpoch)cursor else 0)
        val receivedEpoch=c.target.runtimeId+":"+c.epoch
        if(!com.agenthub.core.OperationPolicies.eventNeedsHistory(epoch,receivedEpoch,cursor,next))
            return EventBatch(client.id,receivedEpoch,next,false,true,null,emptyList())
        return EventBatch(client.id,receivedEpoch,next,epoch!=receivedEpoch,true,null,listOf(history(client,threadId,null)))
    }
    override fun creationOperationId(client: MobileClient,parent: String)="create-$parent"
    override suspend fun createThread(client: MobileClient,projectId: String?,operationId: String): MobileThread=conversation(client,channel(client).request("thread/create",JSONObject().put("clientRequestId",operationId).apply { projectId?.let { put("projectId",it) } },operationId).getJSONObject("thread"))
    override suspend fun createdThread(client: MobileClient,operationId: String): MobileThread {
        val v=channel(client).request("command/status",JSONObject().put("commandId",operationId))
        require(v.optString("method")=="thread/create"&&v.optString("operationId")==operationId&&sameScope(EasyWorkTarget.decode(v.getJSONObject("target")).scope(),client.scope)) { "聊天创建回执不属于此完整目标" }
        return conversation(client,v.optJSONObject("response")?.optJSONObject("result")?.optJSONObject("thread")?:error("创建结果未确认，请核对桌面。原消息已保留"))
    }
    override suspend fun send(client: MobileClient,intent: ReplyIntent): DeliveryReceipt {
        require(intent.hostId==client.id&&intent.mode in listOf("next-turn","queue","steer")&&(intent.settings.speed==null||intent.settings.speed in listOf("standard","fast","ultrafast")))
        val c=channel(client);val parts=JSONArray();if(intent.text.isNotEmpty())parts.put(JSONObject().put("type","text").put("data",JSONObject().put("text",intent.text)))
        for(id in intent.attachmentIds) {
            val record=c.request("resource/read",JSONObject().put("resourceId",id)).getJSONObject("transfer");require(record.getString("threadId")==intent.threadId)
            val attachment=JSONObject();for(key in listOf("name","resourceId","resourceUri","workspaceRelativePath","absolutePath","size","sha256"))if(record.has(key))attachment.put(key,record.get(key))
            val type=record.optString("contentType","application/octet-stream");attachment.put("id",record.getString("transferId")).put("contentType",type).put("kind",if(type.startsWith("image/"))"image" else "file");parts.put(JSONObject().put("type","attachment").put("data",attachment))
        }
        val settings=intent.settings.append(JSONObject());settings.remove("collaborationMode");intent.settings.collaborationMode?.let { settings.put("mode",if(it=="plan")"plan" else "normal") }
        val params=JSONObject().put("threadId",intent.threadId);val method: String
        if(intent.mode=="steer") { method="turn/steer";params.put("turnId",intent.expectedTurnId?:error("运行任务身份缺失")).put("guidanceId",intent.operationId).put("parts",parts).put("message",intent.text) }
        else {
            params.put("clientRequestId",intent.operationId)
            if(intent.expectedPlanId!=null) {
                require(intent.expectedPlanId.startsWith("easywork-plan."));val plan=JSONObject(String(easyBytes(intent.expectedPlanId.removePrefix("easywork-plan."))))
                if(intent.settings.collaborationMode=="plan") { method="turn/start";params.put("revisionOfPlanId",plan.getString("id")).put("expectedPlanVersion",plan.getInt("version")).put("parts",parts).put("settings",settings) }
                else { method="plan/action";settings.remove("mode");params.put("planId",plan.getString("id")).put("expectedVersion",plan.getInt("version")).put("action","approve-current").put("settings",settings) }
            } else { method="turn/start";params.put("parts",parts).put("mode",intent.mode).put("settings",settings) }
        }
        val result=c.request(method,params,intent.operationId)
        return EasyWorkReceiptNormalization.decode(result,intent.operationId,client.id,intent.threadId,"submitted",intent.expectedTurnId)
    }
    override suspend fun receipt(client: MobileClient,threadId: String,operationId: String): DeliveryReceipt {
        val c=channel(client)
        fun originalOwner() {
            require(sameScope(client.scope,c.target.scope())&&client.agentConnection?.let {
                easyCanonical(it.json().put("candidates",JSONArray()))==easyCanonical(c.connection.json().put("candidates",JSONArray()))
            }==true) { "原操作回执身份或内容不匹配" }
        }
        fun targetMatches(value: JSONObject) {
            if(value.has("target")&&value.opt("target")!==JSONObject.NULL)
                require(value.optJSONObject("target")?.let { EasyWorkTarget.decode(it)==c.target }==true) { "原操作回执身份或内容不匹配" }
            if(value.has("hostId")&&value.opt("hostId")!==JSONObject.NULL)
                require(value.opt("hostId")==c.target.desktopDeviceId) { "原操作回执身份或内容不匹配" }
        }
        suspend fun originalQueueReceipt(): DeliveryReceipt? {
            originalOwner()
            val queued=c.originalQueueStatus(threadId,operationId)
            originalOwner()
            if(queued==null)return null
            targetMatches(queued)
            require(queued.opt("clientMessageId")==operationId&&queued.opt("threadId")==threadId) { "原操作回执身份或内容不匹配" }
            return EasyWorkReceiptNormalization.decode(queued,operationId,client.id,threadId,"uncertain")
        }
        originalOwner()
        val v=c.request("command/status",JSONObject().put("commandId",operationId))
        originalOwner();targetMatches(v)
        val state=v.opt("state") as? String
        require(state!=null&&state.isNotEmpty()) { "Host 尚未找到此操作回执，请先核对桌面历史。原消息和操作 ID 已保留" }
        if(v.has("method")&&v.opt("method")!==JSONObject.NULL)
            require(v.opt("method") is String) { "电脑回执格式无效" }
        for(key in listOf("operationId","clientMessageId"))if(v.has(key)&&v.opt(key)!==JSONObject.NULL)
            require(v.opt(key)==operationId) { "原操作回执身份或内容不匹配" }
        if(v.has("threadId")&&v.opt("threadId")!==JSONObject.NULL)
            require(v.opt("threadId")==threadId) { "原操作回执身份或内容不匹配" }
        val rawResponse=v.opt("response")
        require(rawResponse==null||rawResponse===JSONObject.NULL||rawResponse is JSONObject) { "电脑回执格式无效" }
        val response=rawResponse as? JSONObject;val rawResult=response?.opt("result")
        require(rawResult==null||rawResult===JSONObject.NULL||rawResult is JSONObject) { "电脑回执格式无效" }
        val result=rawResult as? JSONObject
        if(result!=null) {
            targetMatches(result)
            for(key in listOf("operationId","clientMessageId"))if(result.has(key)&&result.opt(key)!==JSONObject.NULL)
                require(result.opt(key)==operationId) { "原操作回执身份或内容不匹配" }
            require(result.opt("threadId").let { it==null||it===JSONObject.NULL||it==threadId }) { "原操作回执身份或内容不匹配" }
        }
        if(result?.opt("state")=="queued" || result?.opt("queueSource") is String) {
            require(result?.opt("threadId")==threadId) { "原操作回执身份或内容不匹配" }
            return requireNotNull(originalQueueReceipt()) { "电脑回执格式无效" }
        }
        // The queue can persist the original input before the command ACK. Only
        // this exact missing-result turn/start queries its original admission.
        if(result==null&&v.opt("method")=="turn/start"&&state in listOf("submitting","uncertain")) {
            require(v.opt("threadId")==threadId) { "原操作回执身份或内容不匹配" }
            try {originalQueueReceipt()?.let { return it }}
            catch(error: EasyWorkRejection) { if(error.code !in setOf("method_unavailable","queue_unavailable"))throw error }
        }
        // No result is not a completed input. Retain UNKNOWN and no borrowed run,
        // even if an outer RPC claims completed; an actual object keeps old ACK rules.
        val fallback=if(result!=null&&state=="completed")"submitted" else "uncertain"
        return EasyWorkReceiptNormalization.decode(result ?: JSONObject(),operationId,client.id,threadId,fallback,
            fallbackError=response?.optJSONObject("error")?.opt("message") as? String)
    }
    override suspend fun stop(client: MobileClient,threadId: String,turnId: String) {
        require(threadId.isNotBlank() && turnId.isNotBlank())
        // Only a JSON boolean confirms cancellation; JSONObject.getBoolean also coerces strings.
        require(channel(client).request("turn/cancel",JSONObject().put("threadId",threadId).put("turnId",turnId)).opt("cancelled")==true)
    }
    override suspend fun executeQueue(client: MobileClient,threadId: String,operationId: String,expectedTurnId: String?) {
        require(threadId.isNotBlank() && operationId.isNotBlank() && (expectedTurnId==null || expectedTurnId.isNotBlank()))
        val result=channel(client).request("queue/execute",JSONObject().put("threadId",threadId).put("messageId",operationId)
            .apply { expectedTurnId?.let { put("expectedRunId",it) } })
        require(result.opt("state") in listOf("submitted","completed"))
    }
    override suspend fun cancelQueue(client: MobileClient,threadId: String,operationId: String) {
        require(threadId.isNotBlank() && operationId.isNotBlank())
        require(channel(client).request("queue/cancel",JSONObject().put("threadId",threadId).put("messageId",operationId)).opt("cancelled")==true)
    }
    override suspend fun answer(client: MobileClient,threadId: String,turnId: String,approvalId: String,decision: String,answers: Map<String,List<String>>) {
        val c=channel(client);val result: JSONObject
        if(approvalId.startsWith("question.")) {
            val params=EasyWorkQuestionProjection.resolution(threadId,turnId,approvalId,decision,answers)
            result=c.request("serverRequest/resolve",params)
            require(result.optBoolean(if(decision in listOf("deny","skip"))"abandoned"else "accepted"))
            return
        } else { require(approvalId.startsWith("approval."));val identity=JSONObject(String(easyBytes(approvalId.removePrefix("approval."))));require(identity.getString("threadId")==threadId&&identity.getString("runId")==turnId)
            result=c.request("approval/respond",JSONObject().put("threadId",threadId).put("serverRequestId",approvalId).put("decision",if(decision in listOf("approve","accept"))"approve" else "deny"))
        }
        require(result.optBoolean("accepted")||result.optBoolean("denied"))
    }
    override suspend fun computerFiles(client:MobileClient):ComputerFileCatalog {
        val c=channel(client);val context=c.request("runtime/context")
        require(EasyWorkTarget.decode(context.getJSONObject("target"))==c.target&&sameScope(c.target.scope(),client.scope))
        check("file-transfers" in context.getJSONArray("capabilities").strings()) {"此连接尚未支持文件互传，请更新电脑连接程序。"}
        return EasyWorkFileTransfers.catalog(c.request("resource/transfer-list"),client.id,c.uploadMobileDeviceId)
    }
    override suspend fun receiveComputerFile(client:MobileClient,file:IncomingComputerFile,directory:File,current:()->Boolean,progress:(Long)->Unit):File {
        val c=channel(client)
        check(current());file.validate(client.id,c.uploadMobileDeviceId)
        val fresh=computerFiles(client).incomingFiles?.single {it.id==file.id}?:error("电脑未提供独立收件目录。")
        require(fresh.sameFile(file)&&fresh.phase !in setOf("failed","cancelled"))
        return EasyWorkFileTransfers.receive(fresh,directory,current,progress) {method,params,id->
            check(current()&&sameScope(c.target.scope(),client.scope))
            val reply=c.request(method,params,id?:UUID.randomUUID().toString())
            if(method=="command/status"&&reply.has("response")) {
                val original=JSONObject().put("resourceId",fresh.resourceId).put("transferId",fresh.id)
                val hash=easyHash(easyCanonical(JSONObject().put("target",c.target.json()).put("method","resource/download-complete").put("params",original)).toByteArray())
                require(reply.getString("requestHash")==hash&&EasyWorkTarget.decode(reply.getJSONObject("target"))==c.target)
            }
            reply
        }
    }
    override suspend fun uploadCapability(client:MobileClient,manifest:NativeUploadManifest):NativeUploadCapability? {
        if(manifest.purpose!="attachment"||manifest.file.sizeBytes==0L)return null // Preserve original zero-byte path without inventing resume support.
        val c=channel(client);val context=c.request("runtime/context")
        require(EasyWorkTarget.decode(context.getJSONObject("target"))==c.target&&sameScope(c.target.scope(),client.scope))
        // Cancel is independently negotiated; old targets retain their existing upload path.
        return NativeUploadCapability(192*1024,"file-transfers" in context.getJSONArray("capabilities").strings(),"easywork-attachment-v1")
    }
    override suspend fun uploadRequest(client:MobileClient,m:NativeUploadManifest,action:NativeUploadAction,offset:Long,bytes:ByteArray?):NativeUploadReceipt {
        m.validate();require(m.purpose=="attachment"&&m.file.hostId==client.id&&m.file.sizeBytes<=20*1024*1024)
        val c=channel(client);require(sameScope(c.target.scope(),client.scope));val device=c.uploadMobileDeviceId
        fun transfer(value:JSONObject,completed:Boolean=false)=EasyWorkFileTransfers.uploadTransfer(value,m,device,completed)
        var final:JSONObject?=null
        when(action) {
            NativeUploadAction.PREPARE->{
                val value=c.request("resource/upload",JSONObject().put("transferId",m.id).put("name",m.file.name).put("mediaType",m.file.mediaType)
                    .put("size",m.file.sizeBytes).put("expectedSha256",m.file.sha256).put("purpose","attachment").put("threadId",m.threadId),"upload-${m.id}")
                transfer(value)
            }
            NativeUploadAction.CHUNK->{
                require(bytes!=null&&bytes.isNotEmpty()&&bytes.size<=192*1024&&offset>=0&&offset+bytes.size<=m.file.sizeBytes)
                val value=c.request("resource/upload-chunk",JSONObject().put("transferId",m.id).put("offset",offset).put("dataBase64",easyBase64(bytes)),
                    "chunk-${m.id}-$offset-${easyHash(bytes).take(12)}")
                require(uploadLong(value.get("nextOffset"))==offset+bytes.size&&value.get("complete") is Boolean&&value.getBoolean("complete")== (offset+bytes.size==m.file.sizeBytes))
            }
            NativeUploadAction.COMPLETE->{final=c.request("resource/upload-complete",JSONObject().put("transferId",m.id),"complete-${m.id}");transfer(final!!,true)}
            NativeUploadAction.CANCEL->{
                val context=c.request("runtime/context")
                require(EasyWorkTarget.decode(context.getJSONObject("target"))==c.target&&sameScope(c.target.scope(),client.scope))
                final=EasyWorkFileTransfers.cancel(m,device,context.getJSONArray("capabilities").strings().toSet()) {method,params,id->c.request(method,params,id)}
                // Completion won: decode its original resource, never resend COMPLETE or bytes.
            }
            NativeUploadAction.STATUS->Unit
        }
        val value=final?:c.request("resource/upload-status",JSONObject().put("transferId",m.id))
        return EasyWorkFileTransfers.uploadReceipt(value,m,device)
    }

    override suspend fun upload(client: MobileClient,threadId: String,file: CreationFile,bytes: ByteArray): MobileFile {
        require(bytes.size<=20*1024*1024&&bytes.size.toLong()==file.sizeBytes&&easyHash(bytes)==file.sha256)
        val c=channel(client);c.request("resource/upload",JSONObject().put("transferId",file.id).put("name",file.name).put("mediaType",file.mediaType).put("size",bytes.size).put("expectedSha256",file.sha256).put("purpose","attachment").put("threadId",threadId),"upload-${file.id}")
        val status=c.request("resource/upload-status",JSONObject().put("transferId",file.id));var offset=(status.optJSONObject("transfer")?:status).optInt("transferredBytes")
        require(offset in 0..bytes.size)
        while(offset<bytes.size) { val end=minOf(offset+192*1024,bytes.size);c.request("resource/upload-chunk",JSONObject().put("transferId",file.id).put("offset",offset).put("dataBase64",easyBase64(bytes.copyOfRange(offset,end))),"chunk-${file.id}-$offset");offset=end }
        val completed=c.request("resource/upload-complete",JSONObject().put("transferId",file.id),"complete-${file.id}")
        return (resource(client,threadId,completed.getJSONObject("attachment"))?:error("电脑未确认附件")).also { require(it.sha256==file.sha256&&it.sizeBytes==file.sizeBytes) }
    }
    override suspend fun resolveMessageFile(client: MobileClient,threadId: String,messageId: String,reference: String): MobileFile {
        val c=channel(client);val context=c.request("runtime/context")
        require(EasyWorkTarget.decode(context.getJSONObject("target"))==c.target)
        if("attachments:resolve-message-link:v1" !in context.getJSONArray("capabilities").strings())
            throw CodexCommandError("当前连接程序不支持原消息文件解析，请更新电脑连接程序后重试。",appOwnedCopy=true)
        return EasyWorkDocumentFileResolution.message(client,threadId,messageId,reference) { method,params -> c.request(method,params) }
    }
    override suspend fun resolveDocumentFile(client: MobileClient,file: MobileFile,reference: String,sourceSha256: String): MobileFile {
        require(file.hostId==client.id&&file.sha256==sourceSha256)
        val c=channel(client);val context=c.request("runtime/context")
        require(EasyWorkTarget.decode(context.getJSONObject("target"))==c.target)
        if(EasyWorkDocumentFileResolution.capability !in context.getJSONArray("capabilities").strings())
            throw CodexCommandError("当前连接程序不支持文档内文件链接，请更新电脑连接程序后重试。",appOwnedCopy=true)
        val authorized=if(file.documentSource==null) {
            val original=requireNotNull(file.documentMessageSource)
            resolveMessageFile(client,file.threadId,original.messageId,original.reference).also {
                require(it.hostId==file.hostId&&it.threadId==file.threadId&&it.sha256==sourceSha256&&it.sizeBytes==file.sizeBytes)
            }
        } else file
        return EasyWorkDocumentFileResolution.document(client,c.target.json(),authorized,reference,sourceSha256) { method,params -> c.request(method,params) }
    }
    override suspend fun downloadResource(client: MobileClient,file: MobileFile,cache: File): File = downloadResourceWithProgress(client,file,cache,null).local
    override suspend fun downloadResourceWithProgress(client: MobileClient,file: MobileFile,cache: File,
        progress: ResourceDownloadProgressHandler?): ResourceDownloadSnapshot {
        require(file.hostId==client.id&&file.sizeBytes in 0..20L*1024*1024)
        progress?.invoke(ResourceDownloadProgress(ResourceDownloadPhase.Preparing))
        val scope=easyHash(easyCanonical(JSONArray(listOf(CodexCommandCodec.target(client.scope!!),file.threadId))).toByteArray());val root=File(cache,"agent-$scope/${easyHash(file.id.toByteArray())}").apply { mkdirs() }
        val destination=File(root,File(file.name).name.also { require(it.isNotBlank()&&it !in listOf(".","..")) })
        if(destination.isFile&&destination.length()==file.sizeBytes&&withContext(Dispatchers.IO) { easyHash(destination.readBytes()) }==file.sha256) {
            progress?.invoke(ResourceDownloadProgress(ResourceDownloadPhase.Cached,file.sizeBytes,file.sizeBytes))
            return ResourceDownloadSnapshot(file,destination)
        }
        val report=ResourceDownloadReporter(file.sizeBytes,progress);report.downloading(0,force=true)
        val c=channel(client);val record=c.request("resource/read",JSONObject().put("resourceId",file.id)).getJSONObject("transfer");require(record.getString("threadId")==file.threadId)
        val transfer=record.getString("transferId");val bytes=java.io.ByteArrayOutputStream()
        while(bytes.size().toLong()<file.sizeBytes) { val response=c.request("resource/download",JSONObject().put("resourceId",file.id).put("transferId",transfer).put("offset",bytes.size()).put("length",minOf(192*1024L,file.sizeBytes-bytes.size()).toInt()));val chunk=easyBytes(response.getString("dataBase64"));require(response.getInt("offset")==bytes.size()&&chunk.isNotEmpty()&&bytes.size()+chunk.size<=file.sizeBytes);bytes.write(chunk);report.downloading(bytes.size().toLong()) }
        val data=bytes.toByteArray();report.verifying(data.size.toLong());require(easyHash(data)==file.sha256) { "文件完整性校验失败" }
        withContext(Dispatchers.IO) { val temp=File(root,".${UUID.randomUUID()}.tmp");try { temp.writeBytes(data);require(temp.renameTo(destination)) } finally { temp.delete() } }
        c.request("resource/download-complete",JSONObject().put("resourceId",file.id).put("transferId",transfer),"download-complete-$transfer");return ResourceDownloadSnapshot(file,destination)
    }
    override suspend fun prepareCreation(client: MobileClient,intent: CreationIntent): DesktopCreation=error("此电脑不使用分阶段桌面创建")
    override suspend fun creation(client: MobileClient,operationId: String,inspectOnly: Boolean): DesktopCreation=error("此电脑不使用分阶段桌面创建")
    override suspend fun submitCreation(client: MobileClient,operationId: String): DesktopCreation=error("此电脑不使用分阶段桌面创建")
    override suspend fun detachCreation(client: MobileClient,operationId: String): DesktopCreation=error("此电脑不使用分阶段桌面创建")
    override fun requiresConfirmedDesktopOpenReceipt(client: MobileClient) = true
    override suspend fun open(client: MobileClient,threadId: String,operationId: String,queryOnly: Boolean): DesktopOpen {
        require(threadId.isNotEmpty() && operationId.isNotEmpty())
        val c=channel(client)
        require(sameScope(c.target.scope(),client.scope)) { "打开请求完整目标不匹配" }
        if(!queryOnly) {
            val context=c.request("runtime/context")
            require(EasyWorkTarget.decode(context.getJSONObject("target"))==c.target &&
                "desktop:open" in context.getJSONArray("capabilities").strings()) { "此电脑未开放主动打开" }
        }
        val value=c.request(if(queryOnly)"thread/open-status" else "thread/open",
            JSONObject().put("threadId",threadId).put("clientOperationId",operationId),
            if(queryOnly)UUID.randomUUID().toString() else operationId)
        require(value.getString("hostId")==c.target.desktopDeviceId && value.getString("threadId")==threadId &&
            value.getString("clientOperationId")==operationId && value.getString("phase") in listOf("opened","outcome-unknown","rejected")) { "打开回执原目标或操作不匹配" }
        require(!value.has("error") || value.isNull("error") || value.get("error") is String) { "打开回执错误字段无效" }
        return CodexCommandCodec.open(JSONObject().put("hostId",client.id).put("threadId",threadId)
            .put("clientOperationId",operationId).put("phase",value.getString("phase"))
            .put("error",if(value.has("error"))value.get("error") else JSONObject.NULL))
    }
    override suspend fun uploadCreation(client: MobileClient,operationId: String,file: CreationFile,bytes: ByteArray): Unit=error("此电脑不使用分阶段桌面创建")
}
private object UriReference { fun decode(value: String): String=android.net.Uri.decode(value) }

/** Provider host identity is local; the original client message/thread and queue evidence stay exact. */
internal fun easyWorkQueueReceipt(hostId: String,threadId: String,value: JSONObject): DeliveryReceipt {
    require(value.getString("threadId")==threadId) { "队列回执目标不匹配" }
    return CodexCommandCodec.receipt(JSONObject(value.toString()).put("hostId",hostId)
        .put("turnId",value.optional("turnId") ?: JSONObject.NULL).put("error",value.optional("error") ?: JSONObject.NULL))
}
