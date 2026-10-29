package com.agenthub.android

/** Registration is by stable provider ID. Display names never dispatch operations. */
class AgentRegistry(private val codexReads: AgentReads,private val codexCommands: AgentCommands): AgentReads,AgentCommands {
    private val providers=mutableMapOf("codex" to (codexReads to codexCommands))
    fun register(id: String,reads: AgentReads,commands: AgentCommands) { require(id.isNotBlank()&&id !in providers);providers[id]=reads to commands }
    private fun provider(client: MobileClient,presentationOnly: Boolean=false): Pair<AgentReads,AgentCommands> {
        if(!presentationOnly)NativeMigrationEasyWorkGate.requireReady(client)
        val id=client.agentConnection?.target?.agentId?:client.scope?.agentId?:"codex"
        require(client.agentConnection==null||sameScope(client.scope,client.agentConnection.target)) { "客户端完整目标不匹配" }
        return providers[id]?:error("未注册此 Agent")
    }
    fun initialCodexReads(secret: String): AgentReads=if(codexReads is NativeHostReads)NativeHostReads { secret } else codexReads
    override suspend fun acknowledgeRead(client: MobileClient,request: NativePendingRead)=provider(client).second.acknowledgeRead(client,request)
    override suspend fun archiveWorkspace(client: MobileClient,request: WorkspaceArchiveRequest)=provider(client).second.archiveWorkspace(client,request)
    override suspend fun workspaceArchiveStatus(client: MobileClient,request: WorkspaceArchiveRequest)=provider(client).second.workspaceArchiveStatus(client,request)
    override suspend fun chatAction(client: MobileClient,request: ChatActionRequest)=provider(client).second.chatAction(client,request)
    override suspend fun chatActionStatus(client: MobileClient,threadId: String,operationId: String)=provider(client).second.chatActionStatus(client,threadId,operationId)
    override suspend fun voiceReadiness(client: MobileClient)=provider(client).second.voiceReadiness(client)
    override suspend fun voiceStatus(client: MobileClient,request: VoiceManifest)=provider(client).second.voiceStatus(client,request)
    override suspend fun prepareVoice(client: MobileClient,request: VoiceManifest)=provider(client).second.prepareVoice(client,request)
    override suspend fun uploadVoice(client: MobileClient,request: VoiceManifest,bytes: ByteArray)=provider(client).second.uploadVoice(client,request,bytes)
    override suspend fun workspace(client: MobileClient,projectCursor: String?,threadCursor: String?)=provider(client).first.workspace(client,projectCursor,threadCursor)
    override suspend fun history(client: MobileClient,threadId: String,cursor: String?)=provider(client).first.history(client,threadId,cursor)
    override suspend fun searchHistory(client: MobileClient,threadId: String,cursor: String?)=provider(client).first.searchHistory(client,threadId,cursor)
    override suspend fun nextTurnSuggestion(client: MobileClient,scope: com.agenthub.core.SuggestionScope)=provider(client).first.nextTurnSuggestion(client,scope)
    override fun creationOperationId(client: MobileClient,parent: String)=provider(client).second.creationOperationId(client,parent)
    override fun permissionOptions(client: MobileClient)=provider(client,presentationOnly=true).second.permissionOptions(client)
    override fun agentTitle(client: MobileClient)=provider(client,presentationOnly=true).second.agentTitle(client)
    override suspend fun createProject(client: MobileClient,name: String,directory: String,operationId: String)=provider(client).second.createProject(client,name,directory,operationId)
    override suspend fun createdProject(client: MobileClient,operationId: String)=provider(client).second.createdProject(client,operationId)
    override suspend fun createdThread(client: MobileClient,operationId: String)=provider(client).second.createdThread(client,operationId)
    override suspend fun forkOptions(client: MobileClient,threadId: String)=provider(client).second.forkOptions(client,threadId)
    override suspend fun forkThread(client: MobileClient,request: ThreadForkRequest)=provider(client).second.forkThread(client,request)
    override suspend fun forkStatus(client: MobileClient,threadId: String,operationId: String)=provider(client).second.forkStatus(client,threadId,operationId)
    override suspend fun usage(client: MobileClient)=provider(client).second.usage(client)
    override suspend fun models(client: MobileClient)=provider(client).second.models(client)
    override suspend fun events(client: MobileClient, threadId: String, epoch: String, cursor: Long)=provider(client).second.events(client,threadId,epoch,cursor)
    override suspend fun send(client: MobileClient, intent: ReplyIntent)=provider(client).second.send(client,intent)
    override suspend fun receipt(client: MobileClient, threadId: String, operationId: String)=provider(client).second.receipt(client,threadId,operationId)
    override suspend fun replyDeliveryStatus(client: MobileClient,threadId: String,operationId: String)=provider(client).second.replyDeliveryStatus(client,threadId,operationId)
    override suspend fun createThread(client: MobileClient, projectId: String?, operationId: String)=provider(client).second.createThread(client,projectId,operationId)
    override suspend fun prepareCreation(client: MobileClient, intent: CreationIntent)=provider(client).second.prepareCreation(client,intent)
    override suspend fun creation(client: MobileClient, operationId: String, inspectOnly: Boolean)=provider(client).second.creation(client,operationId,inspectOnly)
    override suspend fun submitCreation(client: MobileClient, operationId: String)=provider(client).second.submitCreation(client,operationId)
    override suspend fun detachCreation(client: MobileClient, operationId: String)=provider(client).second.detachCreation(client,operationId)
    override suspend fun open(client: MobileClient, threadId: String, operationId: String, queryOnly: Boolean)=provider(client).second.open(client,threadId,operationId,queryOnly)
    override suspend fun stop(client: MobileClient, threadId: String, turnId: String)=provider(client).second.stop(client,threadId,turnId)
    override suspend fun executeQueue(client: MobileClient, threadId: String, operationId: String, expectedTurnId: String?)=provider(client).second.executeQueue(client,threadId,operationId,expectedTurnId)
    override suspend fun cancelQueue(client: MobileClient, threadId: String, operationId: String)=provider(client).second.cancelQueue(client,threadId,operationId)
    override suspend fun answer(client: MobileClient, threadId: String, turnId: String, approvalId: String, decision: String, answers: Map<String,List<String>>)=provider(client).second.answer(client,threadId,turnId,approvalId,decision,answers)
    override suspend fun upload(client: MobileClient, threadId: String, file: CreationFile, bytes: ByteArray)=provider(client).second.upload(client,threadId,file,bytes)
    override suspend fun resolveMessageFile(client: MobileClient,threadId: String,messageId: String,reference: String)=provider(client).second.resolveMessageFile(client,threadId,messageId,reference)
    override suspend fun downloadResource(client: MobileClient,file: MobileFile,cache: java.io.File)=provider(client).second.downloadResource(client,file,cache)
    override suspend fun uploadCreation(client: MobileClient, operationId: String, file: CreationFile, bytes: ByteArray)=provider(client).second.uploadCreation(client,operationId,file,bytes)
}
