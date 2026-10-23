package com.agenthub.android

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URI
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

interface AgentFrameTransport {
    suspend fun send(bytes: ByteArray)
    suspend fun receive(): ByteArray
    suspend fun acknowledge(bytes: ByteArray) {}
    fun close()
}
/** TLS exceptions belong to one paired private endpoint and one certificate. */
object EasyWorkHttp {
    const val MAX_FRAME=32*1024*1024
    private val cleanup=java.util.concurrent.Executors.newSingleThreadExecutor { task->Thread(task,"easywork-network-cleanup").apply { isDaemon=true } }
    fun client(endpoint: String,pin: String? = null,capture: ((String)->Unit)? = null): OkHttpClient {
        val builder=OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
            .connectTimeout(8,TimeUnit.SECONDS).readTimeout(35,TimeUnit.SECONDS).callTimeout(40,TimeUnit.SECONDS)
        if(pin!=null||capture!=null) {
            val local=CodexEndpoints.local(endpoint);val host=URI(local).host.lowercase().trim('[',']')
            require(pin==null||pin.matches(Regex("[a-fA-F0-9]{64}"))) { "证书指纹无效" }
            val trust=object: X509TrustManager {
                override fun getAcceptedIssuers()=emptyArray<X509Certificate>()
                override fun checkClientTrusted(chain: Array<X509Certificate>,authType: String) = throw java.security.cert.CertificateException("Client trust is not supported")
                override fun checkServerTrusted(chain: Array<X509Certificate>,authType: String) {
                    if(chain.isEmpty()) throw java.security.cert.CertificateException("Missing paired certificate")
                    chain.first().checkValidity()
                    val fingerprint=easyHash(chain.first().encoded)
                    if(pin!=null&&fingerprint!=pin.lowercase()) throw java.security.cert.CertificateException("Paired certificate changed")
                    capture?.invoke(fingerprint)
                }
            }
            val context=SSLContext.getInstance("TLS");context.init(null,arrayOf(trust),SecureRandom())
            builder.sslSocketFactory(context.socketFactory,trust).hostnameVerifier { supplied,_ -> supplied.lowercase().trim('[',']')==host }
        }
        return builder.build()
    }
    suspend fun request(client: OkHttpClient,url: String,body: JSONObject?=null,token: String?=null,limit: Int=MAX_FRAME,timeoutSeconds: Long=40,form: Map<String,String>?=null,method: String?=null,rawBody: ByteArray?=null,headers: Map<String,String> = emptyMap()): JSONObject {
        val builder=Request.Builder().url(url).header("Accept","application/json")
        require(token==null||headers.keys.none { it.equals("authorization",true) })
        headers.forEach { (key,value)->builder.header(key,value) }
        if(token!=null) { require(token.isNotEmpty()&&token.none { it=='\r'||it=='\n' });builder.header("Authorization","Bearer $token") }
        if(body!=null) builder.post(easyCanonical(body).toRequestBody("application/json; charset=utf-8".toMediaType()))
        if(form!=null) { require(body==null);builder.post(FormBody.Builder().apply { form.forEach { (key,value)->add(key,value) } }.build()) }
        if(method!=null) {
            require(method in setOf("GET","POST","DELETE")&&body==null&&form==null)
            require(method!="GET"||rawBody==null)
            builder.method(method,rawBody?.toRequestBody("application/json; charset=utf-8".toMediaType()))
        } else require(rawBody==null)
        val call=client.newCall(builder.build());call.timeout().timeout(timeoutSeconds,TimeUnit.SECONDS)
        return suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object: Callback {
                override fun onFailure(call: Call,e: IOException) { if(continuation.isActive) continuation.resumeWithException(HostReadError("正在重新连接电脑",e,true)) }
                override fun onResponse(call: Call,response: Response) {
                    response.use { try {
                        if(!continuation.isActive) return
                        if(!response.isSuccessful&&headers.containsKey("DPoP")) {
                            val source=response.body.source();source.request(4096)
                            val errorBytes=source.buffer.readByteArray(minOf(source.buffer.size,4096))
                            val code=runCatching { JSONObject(String(errorBytes,Charsets.UTF_8)).getJSONObject("error").getString("code") }.getOrNull()
                            throw EasyWorkDeviceAuthorizationError(response.code,code?.takeIf { it.matches(Regex("[a-z_]{1,80}")) }?:"request_rejected")
                        }
                        require(response.isSuccessful) { "电脑请求被拒绝（${response.code}）" }
                        val data=ByteArrayOutputStream()
                        response.body.byteStream().use { input ->
                            val buffer=ByteArray(16_384)
                            while(continuation.isActive) { val n=input.read(buffer);if(n<0)break;require(data.size()+n<=limit) { "电脑消息超过接收上限" };data.write(buffer,0,n) }
                        }
                        if(continuation.isActive) continuation.resume(if(data.size()==0) JSONObject() else JSONObject(data.toString("UTF-8")))
                    } catch(e: Exception) { if(continuation.isActive) continuation.resumeWithException(e) } }
                }
            })
        }
    }
    fun dispose(client: OkHttpClient) {
        client.dispatcher.cancelAll()
        // Closing a pooled TLS socket sends close_notify. Keep that network I/O
        // off Android's main thread, including after a successful pinned read.
        val release=Runnable { try { client.connectionPool.evictAll() } finally { client.dispatcher.executorService.shutdown() } }
        if(android.os.Looper.myLooper()==android.os.Looper.getMainLooper())cleanup.execute(release) else release.run()
    }
}

typealias EasyWorkRequestHeaders = suspend (String,String,ByteArray?)->Map<String,String>

class LANFrameTransport(candidate: AgentCandidate,bindingId: String,headers: Map<String,String> = emptyMap()): AgentFrameTransport {
    private val endpoint=CodexEndpoints.local(candidate.endpoint)
    private val client=EasyWorkHttp.client(endpoint,candidate.peerFingerprint ?: error("缺少配对证书"))
    private val input=Channel<ByteArray>(64)
    private val buffered=AtomicLong()
    private val ready=CompletableDeferred<Unit>()
    @Volatile private var closed=false
    private val socket: WebSocket
    init {
        val url=URI(endpoint).let { URI("wss",null,it.host,it.port,"/channel","bindingId="+java.net.URLEncoder.encode(bindingId,"UTF-8"),null).toString() }
        socket=client.newWebSocket(Request.Builder().url(url).apply { headers.forEach { (key,value)->header(key,value) } }.build(),object: WebSocketListener() {
            override fun onOpen(webSocket: WebSocket,response: Response) { ready.complete(Unit) }
            override fun onMessage(webSocket: WebSocket,text: String) = accept(text.toByteArray(Charsets.UTF_8))
            override fun onMessage(webSocket: WebSocket,bytes: ByteString) = accept(bytes.toByteArray())
            override fun onFailure(webSocket: WebSocket,t: Throwable,response: Response?) { fail(HostReadError("正在重新连接电脑",t,true)) }
            override fun onClosed(webSocket: WebSocket,code: Int,reason: String) { fail(HostReadError("电脑通道已关闭",transient=true)) }
        })
    }
    private fun accept(bytes: ByteArray) {
        if(bytes.size>EasyWorkHttp.MAX_FRAME) { fail(IllegalStateException("电脑消息超过接收上限"));socket.cancel();return }
        if(buffered.addAndGet(bytes.size.toLong())>2L*EasyWorkHttp.MAX_FRAME||input.trySend(bytes).isFailure) {
            buffered.addAndGet(-bytes.size.toLong());fail(IllegalStateException("电脑消息超过接收上限"));socket.cancel()
        }
    }
    private fun fail(error: Throwable) { ready.completeExceptionally(error);input.close(error) }
    override suspend fun send(bytes: ByteArray) {
        require(bytes.size<=EasyWorkHttp.MAX_FRAME);withTimeout(8_000) { ready.await() }
        check(!closed&&socket.send(String(bytes,Charsets.UTF_8))) { "电脑通道已关闭" }
    }
    override suspend fun receive(): ByteArray = input.receive().also { buffered.addAndGet(-it.size.toLong()) }
    override fun close() { closed=true;socket.cancel();input.cancel();EasyWorkHttp.dispose(client) }
}

class RelayFrameTransport(candidate: AgentCandidate,private val bindingId: String?,private val token: suspend ()->String,private val authorize: EasyWorkRequestHeaders?=null): AgentFrameTransport {
    private val base=NativeHostReads.baseAddress(candidate.endpoint).toString().trimEnd('/')
    private val client=EasyWorkHttp.client(base)
    private val pending=ArrayDeque<JSONObject>()
    private val acknowledgements=linkedMapOf<String,JSONObject>()
    private val acknowledgementScope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private var acknowledgementTask: Job?=null
    @Volatile private var acknowledgementFailure: Throwable?=null
    @Volatile private var closed=false
    suspend fun api(path: String,body: JSONObject?=null): JSONObject {
        require(path in setOf("/relay/envelopes","/relay/pending","/relay/acknowledgements","/pairing/claim","/bindings"))
        currentCoroutineContext().ensureActive()
        check(!closed)
        acknowledgementFailure?.let { throw it }
        if(authorize!=null) {
            require(path in setOf("/relay/envelopes","/relay/pending","/relay/acknowledgements","/bindings"))
            val url="$base/v2/remote-access$path";val method=if(body==null)"GET" else "POST"
            val bytes=body?.let { easyCanonical(it).toByteArray(Charsets.UTF_8) }
            val headers=authorize.invoke(method,url,bytes)
            currentCoroutineContext().ensureActive();check(!closed)
            return EasyWorkHttp.request(client,url,method=method,rawBody=bytes,headers=headers)
        }
        val access=token();currentCoroutineContext().ensureActive();check(!closed)
        return EasyWorkHttp.request(client,"$base/v1/remote-access$path",body,access)
    }
    override suspend fun send(bytes: ByteArray) { api("/relay/envelopes",JSONObject(String(bytes,Charsets.UTF_8))) }
    override suspend fun receive(): ByteArray {
        require(bindingId!=null)
        while(!closed) {
            currentCoroutineContext().ensureActive();acknowledgementFailure?.let { throw it }
            val frame=synchronized(pending) { if(pending.isEmpty()) null else pending.removeFirst() }
            if(frame!=null)return easyCanonical(frame).toByteArray()
            val incoming=api("/relay/pending").optJSONArray("envelopes")?.objects()?.filter { it.optString("bindingId")==bindingId }.orEmpty()
            if(closed)throw CancellationException("电脑通道已关闭")
            synchronized(pending) { pending.addAll(incoming) }
            if(synchronized(pending) { pending.isEmpty() })delay(250)
        }
        throw CancellationException("电脑通道已关闭")
    }
    override suspend fun acknowledge(bytes: ByteArray) {
        currentCoroutineContext().ensureActive();check(!closed);acknowledgementFailure?.let { throw it }
        val value=JSONObject(String(bytes,Charsets.UTF_8));val header=JSONObject()
        val expectedBinding=requireNotNull(bindingId)
        require(value.opt("bindingId")==expectedBinding) { "中继确认的绑定身份不匹配" }
        for(key in listOf("bindingId","senderDeviceId","senderSessionId")) {
            val text=value.get(key);require(text is String && text.isNotEmpty() && text.length<=256)
            header.put(key,text)
        }
        val raw=value.get("sequence");require(raw is Number)
        val number=raw.toDouble();require(number.isFinite() && number>=1 && number<=9007199254740991.0 && number%1.0==0.0)
        header.put("sequence",number.toLong())
        val key=easyCanonical(header)
        synchronized(acknowledgements) {
            check(!closed);acknowledgementFailure?.let { throw it }
            if(key !in acknowledgements && acknowledgements.size>=1000) {
                val error=HostReadError("中继确认队列已满，需要重新连接",transient=true)
                acknowledgementFailure=error;throw error
            }
            acknowledgements[key]=header
            if(acknowledgementTask==null)acknowledgementTask=acknowledgementScope.launch { flushAcknowledgements() }
        }
    }
    private suspend fun flushAcknowledgements() {
        try {
            delay(50)
            while(!closed) {
                val batch=synchronized(acknowledgements) { acknowledgements.entries.take(1000).map { it.key to it.value } }
                if(batch.isEmpty())break
                api("/relay/acknowledgements",JSONObject().put("envelopes",JSONArray(batch.map { it.second })))
                synchronized(acknowledgements) { batch.forEach { acknowledgements.remove(it.first) } }
            }
        } catch(cancel: CancellationException) { throw cancel }
        catch(error: Exception) { if(!closed)acknowledgementFailure=error }
        finally {
            synchronized(acknowledgements) {
                acknowledgementTask=null
                if(!closed && acknowledgementFailure==null && acknowledgements.isNotEmpty())
                    acknowledgementTask=acknowledgementScope.launch { flushAcknowledgements() }
            }
        }
    }
    override fun close() {
        closed=true;acknowledgementScope.cancel()
        synchronized(acknowledgements) { acknowledgementTask?.cancel();acknowledgementTask=null;acknowledgements.clear() }
        synchronized(pending) { pending.clear() }
        EasyWorkHttp.dispose(client)
    }
}

class AgentTransportRegistry {
    private val factories=mutableMapOf<String,suspend (AgentCandidate,EasyWorkBinding,suspend ()->String,EasyWorkRequestHeaders?)->AgentFrameTransport>(
        "lan-wss" to { c,b,_,a->LANFrameTransport(c,b.id,a?.invoke("GET",CodexEndpoints.local(c.endpoint)+"/channel",null).orEmpty()) },
        "relay-http" to { c,b,t,a->RelayFrameTransport(c,b.id,t,a) })
    fun register(id: String,factory: suspend (AgentCandidate,EasyWorkBinding,suspend ()->String,EasyWorkRequestHeaders?)->AgentFrameTransport) { factories[id]=factory }
    suspend fun make(candidate: AgentCandidate,binding: EasyWorkBinding,token: suspend ()->String,authorize: EasyWorkRequestHeaders?=null)=factories[candidate.transportId]?.invoke(candidate,binding,token,authorize) ?: error("未注册此连接方式")
}
