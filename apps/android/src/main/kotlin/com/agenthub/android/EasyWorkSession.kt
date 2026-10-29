package com.agenthub.android

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

class EasyWorkRemoteSession(val connection: AgentConnection,private val credential: EasyWorkCredential,private val token: suspend ()->String,
    private val transports: AgentTransportRegistry = AgentTransportRegistry(),private val authorize: EasyWorkRequestHeaders?=null,ownerContext: CoroutineContext=EmptyCoroutineContext) {
    val target=credential.binding.target
    private val migrationContext=ownerContext.easyWorkOwnerContext()
    private val tasks=CoroutineScope(SupervisorJob()+Dispatchers.IO+migrationContext)
    private val connecting=Mutex()
    private val sending=Mutex()
    private val routeLock=Any()
    @Volatile private var route: AgentFrameTransport?=null
    @Volatile private var crypto: EasyWorkCrypto?=null
    @Volatile private var closed=false
    @Volatile private var confirmed=false
    @Volatile private var lastError: Throwable?=null
    @Volatile var epoch=UUID.randomUUID().toString(); private set
    @Volatile var activeCandidate: AgentCandidate?=null; private set
    @Volatile var candidates=connection.candidates
    private val sequence=AtomicLong(1)
    private val pending=ConcurrentHashMap<String,CompletableDeferred<JSONObject>>()
    private val threadSequences=ConcurrentHashMap<String,Long>()
    private val eventChanges=EasyWorkEventSignal()
    private val subscriptions=ConcurrentHashMap<String,String>()
    private val subscribedThreads=ConcurrentHashMap.newKeySet<String>()
    private val syncReads=Mutex()
    private val threadSyncs=linkedMapOf<String,EasyWorkThreadSync>()
    private val received=mutableMapOf<String,Pair<Long,MutableSet<Long>>>()
    private var pump: Job?=null
    init { require(sameScope(target.scope(),connection.target)) { "连接运行时身份不匹配" };require(credential.grantRef==null||authorize!=null) { "设备授权不可降级为旧登录凭据" };require(credential.binding.json.optJSONObject("deviceAuthorization")==null||credential.grantRef!=null) { "设备授权凭据缺失，请重新配对" } }
    private fun disconnect() = synchronized(routeLock) {
        val previous=route;route=null;crypto=null;confirmed=false;pump?.cancel();pump=null;previous?.close()
        synchronized(threadSyncs) { threadSyncs.clear() }
        eventChanges.signal()
    }
    private val retirement=ownerContext[NativeEasyWorkMigrationOwners]?.register({stop()},{tasks.coroutineContext[Job]!!.join()})
    private fun stop() {synchronized(routeLock){closed=true;disconnect()};tasks.cancel();pending.values.forEach { it.cancel() };pending.clear() }
    fun close() {retirement?.retire()?:stop()}
    suspend fun closeAndJoinForMigration(){close();retirement?.await()?:tasks.coroutineContext[Job]!!.join()}
    private suspend fun connect(excluding: String? = null) = connecting.withLock {
        check(!closed)
        if(route!=null&&confirmed&&(excluding==null||activeCandidate?.id!=excluding))return@withLock
        disconnect()
        var options=candidates.filter { connection.preference!="lan-only"||it.transportId=="lan-wss" }
            .filter { connection.preference!="relay-only"||it.transportId=="relay-http" }
        if(connection.preference=="automatic") options=options.sortedBy { if(it.transportId=="lan-wss") 0 else 1 }
        val excludedEndpoint=options.firstOrNull { it.id==excluding }?.endpoint
        options=EasyWorkPairing.reachable(options,credential.binding)
        if(excluding!=null) options=options.filter { it.id!=excluding&&it.endpoint!=excludedEndpoint }
        var failure: Throwable=HostReadError("电脑暂无可用连接",transient=true)
        for(candidate in options) {
            try {
                val binding=if(candidate.transportId=="relay-http") credential.relayBinding?:credential.binding else credential.binding
                require(binding.target==target) { "双通道运行时身份不匹配" }
                val channel=EasyWorkCrypto(credential,binding)
                val transport=transports.make(candidate,binding,token,authorize)
                val attemptEpoch=synchronized(routeLock) {
                    if(closed){transport.close();throw CancellationException("EasyWork session is closed")}
                    route=transport;crypto=channel;sequence.set(1);confirmed=false;lastError=null;activeCandidate=candidate;epoch=UUID.randomUUID().toString()
                    epoch.also {value->pump=tasks.launch {receive(transport,channel,value)}}
                }
                withTimeout(8_000) {
                    publish(JSONObject().put("kind","key-confirmation").put("value",channel.confirmation))
                    while(!confirmed&&lastError==null&&!closed)delay(25)
                    if(!confirmed)throw lastError?:HostReadError("电脑握手未确认",transient=true)
                }
                return@withLock
            } catch(cancel: CancellationException) { val rejected=lastError as? EasyWorkDeviceAuthorizationError;disconnect();if(!currentCoroutineContext().isActive||closed)throw cancel;if(rejected!=null)throw rejected;failure=HostReadError("电脑握手超时",transient=true) }
            catch(e: Exception) { failure=lastError as? EasyWorkDeviceAuthorizationError?:e;disconnect();if(failure is EasyWorkDeviceAuthorizationError)throw failure }
        }
        throw failure
    }
    private suspend fun publish(payload: JSONObject) = sending.withLock {
        check(!closed);val transport=route?:throw HostReadError("电脑连接中断",transient=true)
        val channel=crypto?:throw HostReadError("电脑握手未确认",transient=true)
        transport.send(channel.seal(payload,sequence.getAndIncrement()))
    }
    private suspend fun receive(transport: AgentFrameTransport,channel: EasyWorkCrypto,pumpEpoch: String) {
        try {
            while(currentCoroutineContext().isActive&&!closed&&epoch==pumpEpoch) {
                val frame=transport.receive();val opened=channel.open(frame)
                if(epoch!=pumpEpoch||closed)return
                val replay=synchronized(received) {
                    val old=received[opened.peerSession]?: (0L to mutableSetOf<Long>())
                    if(opened.sequence in old.second||opened.sequence<=old.first-2000) true
                    else { val highest=maxOf(old.first,opened.sequence);val window=old.second.filterTo(mutableSetOf()) { it>highest-2000 };window.add(opened.sequence);received[opened.peerSession]=highest to window;false }
                }
                if(replay) { transport.acknowledge(frame);continue }
                val payload=opened.payload
                when(payload.getString("kind")) {
                    "key-confirmation" -> { require(payload.getString("value")==channel.confirmation) { "电脑握手密钥不匹配" };confirmed=true }
                    "protocol" -> {
                        val message=payload.getJSONObject("envelope")
                        require(message.getInt("protocolVersion")==2) { "电脑协议版本不匹配" }
                        if(!confirmed) { transport.acknowledge(frame);continue }
                        message.optJSONObject("target")?.let { require(EasyWorkTarget.decode(it)==target) { "电脑消息运行时身份不匹配" } }
                        when(message.getString("kind")) {
                            "response" -> pending[message.getString("requestId")]?.complete(message)
                            "event" -> {
                                require(EasyWorkTarget.decode(message.getJSONObject("target"))==target)
                                val thread=message.getJSONObject("payload").getString("threadId");val cursor=message.getLong("sequence")
                                require(cursor in 0..9007199254740991L);threadSequences.merge(thread,cursor,::maxOf)
                                eventChanges.signal()
                            }
                            else -> error("电脑返回消息类型无效")
                        }
                    }
                    else -> error("电脑加密消息类型无效")
                }
                transport.acknowledge(frame)
            }
        } catch(cancel: CancellationException) { throw cancel }
        catch(e: Exception) {
            synchronized(routeLock) {
                if(!closed&&epoch==pumpEpoch) {lastError=e;confirmed=false;route=null;transport.close();eventChanges.signal()}
            }
        }
    }
    suspend fun synchronizedThread(threadId: String): JSONObject = syncReads.withLock {
        currentCoroutineContext().ensureActive();check(!closed)
        connect()
        var readEpoch=epoch
        var previous=synchronized(threadSyncs) { threadSyncs[threadId] }?:EasyWorkThreadSync()
        var value=request("thread/sync",previous.parameters(threadId))
        if(readEpoch!=epoch) {
            previous=EasyWorkThreadSync();readEpoch=epoch
            value=request("thread/sync",previous.parameters(threadId))
        }
        if(readEpoch!=epoch)throw HostReadError("电脑连接已更新，请重新读取历史",transient=true)
        val next=try { previous.merging(value,threadId) }
        catch(_: InvalidThreadDelta) {
            // Retry only the read with no known messages. Never replay input.
            val empty=EasyWorkThreadSync();readEpoch=epoch
            empty.merging(request("thread/sync",empty.parameters(threadId)),threadId)
        }
        currentCoroutineContext().ensureActive()
        if(closed||readEpoch!=epoch)throw HostReadError("电脑连接已更新，请重新读取历史",transient=true)
        synchronized(threadSyncs) {
            val budget=4L*1024*1024
            threadSyncs.remove(threadId)
            if(next.first.cacheWeight<=budget)threadSyncs[threadId]=next.first
            while(threadSyncs.size>4 || threadSyncs.values.sumOf { it.cacheWeight }>budget)
                threadSyncs.remove(threadSyncs.keys.first())
        }
        next.second
    }
    /** Wire method whitelist is internal to this adapter; native UI uses named domain operations. */
    suspend fun request(method: String,params: JSONObject=JSONObject(),id: String=UUID.randomUUID().toString()): JSONObject =
        withContext(migrationContext){requestOwned(method,params,id)}
    private suspend fun requestOwned(method: String,params: JSONObject,id: String): JSONObject {
        require(method in READ_METHODS||method in WRITE_METHODS) { "未注册此电脑操作" }
        val response=CompletableDeferred<JSONObject>()
        require(pending.putIfAbsent(id,response)==null) { "同一操作正在等待电脑回执" }
        val request=JSONObject().put("protocolVersion",2).put("kind","request").put("requestId",id).put("target",target.json()).put("method",method).put("params",params)
        try {
            repeat(2) { attempt ->
                connect();val currentRoute=activeCandidate?.id;val requestEpoch=epoch
                try {
                    publish(JSONObject().put("kind","protocol").put("envelope",request))
                    val deadline=System.nanoTime()+30_000_000_000L
                    while(!response.isCompleted&&lastError==null&&!closed&&System.nanoTime()<deadline)delay(25)
                    check(!closed)
                    if(response.isCompleted) {
                        val v=response.await()
                        return result(v)
                    }
                    throw lastError?:HostReadError("电脑操作结果未确认",transient=true)
                } catch(e: EasyWorkRejection) { throw e }
                catch(e: EasyWorkDeviceAuthorizationError) { throw e }
                catch(cancel: CancellationException) { throw cancel }
                catch(e: Exception) {
                    if(epoch==requestEpoch)disconnect()
                    if(attempt==1)throw e
                    if(connection.preference=="automatic")connect(currentRoute) else connect()
                    if(method !in READ_METHODS) {
                        val operation=params.optional("clientRequestId")?:params.optional("operationId")?:params.optional("guidanceId")?:id
                        val status=request("command/status",JSONObject().put("commandId",operation))
                        if(!status.has("state")||status.optString("state") in listOf("submitting","uncertain"))throw EasyWorkRejection("操作结果仍不确定，请查询状态并核对电脑结果。原操作不会自动重放。")
                        val expectedHash=easyHash(easyCanonical(JSONObject().put("target",target.json()).put("method",method).put("params",params)).toByteArray())
                        require(status.getString("operationId")==operation&&status.getString("method")==method&&status.getString("requestHash")==expectedHash&&EasyWorkTarget.decode(status.getJSONObject("target"))==target) { "原操作回执身份或内容不匹配" }
                        return result(status.getJSONObject("response"))
                    }
                    // Only reads retry. Writes return a verified receipt and never replay.
                }
            }
            throw HostReadError("电脑操作结果未确认",transient=true)
        } finally { pending.remove(id,response);response.cancel() }
    }
    private fun result(v: JSONObject): JSONObject {
        if(!v.getBoolean("ok"))throw EasyWorkRejection(v.optJSONObject("error")?.optString("message")?:"电脑拒绝了请求")
        val value=v.opt("result")
        return if(value is JSONObject)value else if(value==null||value===JSONObject.NULL)JSONObject() else JSONObject().put("value",value)
    }
    suspend fun events(threadId: String,after: Long): Long {
        val deadline=System.nanoTime()+12_000_000_000L
        do {
            currentCoroutineContext().ensureActive()
            if(closed)throw CancellationException("Session closed")
            val observed=eventChanges.revision
            if(route==null||lastError!=null)connect()
            for(previous in subscribedThreads.toList().filter { it!=threadId }) {
                request("thread/unsubscribe",JSONObject().put("threadId",previous))
                currentCoroutineContext().ensureActive()
                subscribedThreads.remove(previous);subscriptions.remove(previous);threadSequences.remove(previous)
            }
            if(subscriptions[threadId]!=epoch) {
                subscribedThreads.add(threadId)
                request("thread/subscribe",JSONObject().put("threadId",threadId).put("afterSequence",after))
                currentCoroutineContext().ensureActive();subscriptions[threadId]=epoch
                threadSequences.merge(threadId,after,::maxOf)
            }
            if((threadSequences[threadId]?:after)>after)break
            val remaining=(deadline-System.nanoTime())/1_000_000L
            eventChanges.awaitChange(observed,remaining)
        } while(System.nanoTime()<deadline&&!closed)
        return threadSequences[threadId]?:after
    }
    companion object {
        private val READ_METHODS=setOf("usage/read","runtime/context","runtime/summary","project/list","thread/list","thread/search","thread/read","thread/history","thread/suggestion/read","thread/sync","thread/subscribe","thread/unsubscribe","model/list","queue/list","queue/status","filesystem/access-status","filesystem/roots","filesystem/list-directories","filesystem/list-entries","voice/readiness","resource/read","resource/download","resource/resolve","resource/transfer-list","resource/upload-status","command/status")
        private val WRITE_METHODS=setOf("filesystem/request-access","project/create","thread/create","turn/start","turn/cancel","turn/steer","queue/cancel","queue/execute","plan/action","approval/respond","serverRequest/resolve","resource/upload","resource/upload-chunk","resource/upload-complete","resource/download-complete")
    }
}
class EasyWorkRejection(message: String): Exception(message)

fun interface EasyWorkChannels { suspend fun session(client: MobileClient): EasyWorkRemoteSession }

class EasyWorkSessions(private val storage: NativeSessionStorage,private val login: EasyWorkLogin,private val ownerContext: CoroutineContext=EmptyCoroutineContext): EasyWorkChannels {
    private val deviceAuthorization=EasyWorkDeviceAuthorization(storage)
    private val lock=Mutex()
    private val sessions=mutableMapOf<String,Pair<String,EasyWorkRemoteSession>>()
    override suspend fun session(client: MobileClient): EasyWorkRemoteSession = lock.withLock {
        NativeMigrationEasyWorkGate.requireReady(client)
        val connection=client.agentConnection?:error("缺少电脑连接")
        val credential=withContext(Dispatchers.IO) { EasyWorkCredential.decode(JSONObject(storage.read("easywork-credential:${connection.credentialRef}")?:error("配对授权已失效"))) }
        val signature=easyCanonical(connection.json().put("candidates",org.json.JSONArray()).put("credentialRevision",easyHash(credential.json().toString().toByteArray())))
        sessions[client.id]?.let { (old,value)->if(old==signature) { value.candidates=connection.candidates;return@withLock value } else value.close() }
        val authorization: EasyWorkRequestHeaders?=credential.grantRef?.let { ref->{ method,url,body->deviceAuthorization.headers(ref,credential.binding,method,url,body) } }
        val value=EasyWorkRemoteSession(connection,credential,{ login.validToken(credential.accessToken,credential.binding.target) },authorize=authorization,ownerContext=ownerContext);sessions[client.id]=signature to value;value
    }
    suspend fun remove(id: String)=lock.withLock { sessions.remove(id)?.second?.close() }
    suspend fun closeAll()=lock.withLock { sessions.values.forEach { it.second.close() };sessions.clear() }
}
