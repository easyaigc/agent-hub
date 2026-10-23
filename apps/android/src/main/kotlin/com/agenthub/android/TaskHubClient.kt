package com.agenthub.android

import com.agenthub.core.TaskHubContract
import com.agenthub.core.TaskHubRules
import com.agenthub.core.TaskHubSSEFramer
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONObject
import java.net.URI
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal class TaskHubHTTPError(val status: Int, message: String): Exception(message)
internal interface TaskHubTransport {
    suspend fun request(path: String, method: String="GET", body: String?=null, operationId: String?=null, query: Map<String,String> = emptyMap()): String
    suspend fun events(projectId: String,cursor: String?,changed: (String,String,Long)->Unit)
}
internal interface TaskHubUploadTransport: TaskHubTransport {
    suspend fun uploadFile(projectId: String, ticketId: String?, operationId: String, file: java.io.File, contentType: String, sha256: String): String
}
internal interface TaskHubDownloadTransport: TaskHubTransport {
    suspend fun downloadAttachment(id: String,maximumBytes: Int): ByteArray
}

/** Pinned, explicit business endpoint; redirects and HTTP automatic write retries are disabled. */
internal class TaskHubClient(val binding: TaskHubBinding, private val deviceId: String,private val token: String="",dns: Dns=Dns.SYSTEM): TaskHubUploadTransport,TaskHubDownloadTransport {
    init { taskHubUUID(deviceId);require(token.none { it=='\r' || it=='\n' }) }
    private val client: OkHttpClient
    init {
        val expected=binding.fingerprint.lowercase()
        val manager=object: X509TrustManager {
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            override fun checkClientTrusted(chain: Array<out X509Certificate>?,type: String?) { throw java.security.cert.CertificateException("Client TLS unsupported") }
            override fun checkServerTrusted(chain: Array<out X509Certificate>?,type: String?) {
                val leaf=chain?.firstOrNull() ?: throw java.security.cert.CertificateException("Missing peer certificate")
                leaf.checkValidity()
                val actual=taskHubSha(leaf.encoded)
                if(!MessageDigest.isEqual(actual.toByteArray(),expected.toByteArray())) throw java.security.cert.CertificateException("Task Hub certificate changed")
            }
        }
        val ssl=SSLContext.getInstance("TLS").apply { init(null,arrayOf(manager),SecureRandom()) }
        val host=URI(binding.endpoint).host.removeSurrounding("[","]")
        client=OkHttpClient.Builder().dns(dns).sslSocketFactory(ssl.socketFactory,manager)
            .hostnameVerifier { actual,_ -> actual.removeSurrounding("[","]").equals(host,true) }
            .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
            .authenticator(Authenticator.NONE).proxyAuthenticator(Authenticator.NONE)
            .connectTimeout(20,TimeUnit.SECONDS).readTimeout(20,TimeUnit.SECONDS).callTimeout(180,TimeUnit.SECONDS).build()
    }
    private suspend fun <T> connection(path: String,method: String,body: String?,operationId: String?,query: Map<String,String>,
        stream: Boolean=false, read: (Response,()->Boolean)->T): T {
        require(TaskHubRules.validPath(path) && method in listOf("GET","POST","PUT","PATCH","DELETE"))
        operationId?.let(::taskHubUUID)
        require(body==null || body.toByteArray(Charsets.UTF_8).size<=4*1024*1024)
        val url=(binding.endpoint.trimEnd('/')+"/api/v1/"+path).toHttpUrl().newBuilder()
        for((name,value) in query.toSortedMap()) url.addQueryParameter(name,value)
        val request=Request.Builder().url(url.build()).header("Accept",if(stream) "text/event-stream" else "application/json")
            .header("X-TaskHub-Device-Id",deviceId)
        // Public descriptor verification must precede disclosure of an account credential.
        if(token.isNotEmpty() && path!="service") request.header("Authorization","Bearer $token")
        if(operationId!=null) request.header("X-TaskHub-Operation-Id",operationId)
        if(stream) request.header("Last-Event-ID",body ?: "0")
        val bytes=body?.takeUnless { stream }?.toByteArray(Charsets.UTF_8)
        val payload=if(method in listOf("POST","PUT","PATCH") || bytes!=null) (bytes ?: ByteArray(0)).toRequestBody("application/json".toMediaType()) else null
        request.method(method,payload)
        return execute(request.build(),stream,read)
    }
    private suspend fun <T> execute(request: Request,stream: Boolean=false,read: (Response,()->Boolean)->T): T = suspendCancellableCoroutine { continuation ->
        val activeClient=if(stream) client.newBuilder().readTimeout(180,TimeUnit.SECONDS).build() else client
        val call=activeClient.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object: Callback {
            override fun onFailure(call: Call,error: IOException) { if(continuation.isActive) continuation.resumeWithException(error) }
            override fun onResponse(call: Call,response: Response) {
                try {
                    response.use {
                        if(!continuation.isActive) return
                        if(response.code !in 200..299) {
                            val wire=response.body.byteStream().use { NativeWireReader.read(it,65536) { continuation.isActive } }
                            val message=runCatching { JSONObject(wire).optString("message") }.getOrNull()?.takeIf { it.isNotEmpty() }
                            throw TaskHubHTTPError(response.code,message ?: "主服务请求失败（${response.code}）")
                        }
                        val value=read(response) { continuation.isActive }
                        if(continuation.isActive) continuation.resume(value)
                    }
                } catch(error: Exception) { if(continuation.isActive) continuation.resumeWithException(error) }
            }
        })
    }
    override suspend fun request(path: String,method: String,body: String?,operationId: String?,query: Map<String,String>): String =
        connection(path,method,body,operationId,query) { response,active -> response.body.byteStream().use { NativeWireReader.read(it,4*1024*1024,active) } }
    override suspend fun uploadFile(projectId: String,ticketId: String?,operationId: String,file: java.io.File,contentType: String,sha256: String): String {
        taskHubUUID(projectId);taskHubUUID(operationId);ticketId?.let(::taskHubUUID)
        require(file.isFile && file.length() in 1..21L*1024*1024 && sha256.matches(Regex("[a-f0-9]{64}")))
        val size=file.length()
        val body=object: RequestBody() {
            override fun contentType()=contentType.toMediaType()
            override fun contentLength()=size
            override fun isOneShot()=true
            override fun writeTo(sink: okio.BufferedSink) {
                val digest=MessageDigest.getInstance("SHA-256");var written=0L
                file.inputStream().use { input -> val buffer=ByteArray(65536)
                    while(true) { val n=input.read(buffer);if(n<0) break;written+=n;if(written>size) throw IOException("Upload changed");digest.update(buffer,0,n);sink.write(buffer,0,n) }
                }
                if(written!=size || digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }!=sha256) throw IOException("原上传文件已变化")
            }
        }
        val url=(binding.endpoint+"/api/v1/attachments").toHttpUrl().newBuilder().addQueryParameter("projectId",projectId)
        if(ticketId!=null) url.addQueryParameter("ticketId",ticketId)
        val request=Request.Builder().url(url.build()).post(body).header("Accept","application/json")
            .header("X-TaskHub-Device-Id",deviceId).header("X-TaskHub-Operation-Id",operationId)
        require(token.isNotEmpty());request.header("Authorization","Bearer $token")
        return execute(request.build()) { response,active -> response.body.byteStream().use { NativeWireReader.read(it,512*1024,active) } }
    }
    suspend fun verify() {
        val service=TaskHubService.decode(request("service"))
        require(binding.service.same(service)) { "任务主服务身份已变化，请重新选择；原凭据未转移" }
    }
    override suspend fun downloadAttachment(id: String,maximumBytes: Int): ByteArray {
        taskHubUUID(id);require(maximumBytes in 1..20*1024*1024)
        return connection("attachments/$id/file","GET",null,null,emptyMap()) { response,active ->
            val size=response.body.contentLength()
            require(size<0 || size<=maximumBytes) { "附件超过大小限制" }
            val out=java.io.ByteArrayOutputStream(if(size>0) size.toInt() else 8192)
            response.body.byteStream().use { input -> val buffer=ByteArray(65536)
                while(true) {
                    if(!active()) throw kotlinx.coroutines.CancellationException()
                    val count=input.read(buffer);if(count<0) break
                    require(out.size()+count<=maximumBytes) { "附件超过大小限制" };out.write(buffer,0,count)
                }
            }
            out.toByteArray()
        }
    }
    override suspend fun events(projectId: String,cursor: String?,changed: (String,String,Long)->Unit) {
        taskHubUUID(projectId);require(cursor==null || TaskHubRules.validCursor(cursor))
        connection("events","GET",cursor ?: "0",null,mapOf("projectId" to projectId),true) { c,active ->
            val framer=TaskHubSSEFramer()
            c.body.byteStream().use { stream ->
                val buffer=ByteArray(8192)
                while(active()) {
                    val count=stream.read(buffer);if(count<0) break
                    for(index in 0 until count) {
                        if(!active()) throw kotlinx.coroutines.CancellationException()
                        framer.consume(buffer[index])?.let { event ->
                            require(event.cursor!=null && TaskHubContract.validate(event.data,"event")==null) { "实时业务事件身份无效" }
                            val value=JSONObject(event.data)
                            changed(event.cursor!!,value.getString("taskId"),value.getLong("revision"))
                        }
                    }
                }
            }
        }
    }
}
