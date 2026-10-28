package com.agenthub.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.security.cert.CertificateException
import java.util.UUID
import javax.net.ssl.SSLException

/** Real production OkHttp transports against a coordinator-owned synthetic loopback fixture.
 * No in-memory AgentFrameTransport, Activity, stored identity, Desktop or model is used.
 * Requires p8TransportConfig and an exact device/package/time lease; never self-installs.
 */
@RunWith(AndroidJUnit4::class)
class NativeEasyWorkTransportBoundaryTest {
    private class Fixture {
        val config=JSONObject(requireNotNull(InstrumentationRegistry.getArguments().getString("p8TransportConfig")) { "Exact leased loopback fixture config is required" })
        private val asset=InstrumentationRegistry.getInstrumentation().context.assets.open("easywork-crypto-vector.json").use { it.readBytes() }
        val credential=EasyWorkCredential.decode(JSONObject(String(asset,Charsets.UTF_8)).getJSONObject("credential"))
        val target get()=credential.binding.target
        val caseId=UUID.randomUUID().toString()
        val tls=origin("tlsOrigin","https");val relay=origin("relayOrigin","http");val control=origin("controlOrigin","http")
        val pin=config.getString("certificateSha256")
        val lanCandidate get()=AgentCandidate("p8-real-lan","lan-wss",tls,pin)
        val relayCandidate get()=AgentCandidate("p8-real-relay","relay-http",relay)
        init {
            require(config.getInt("version")==1&&config.getString("fixture")=="p8-synthetic-loopback-transport")
            UUID.fromString(config.getString("instanceId"))
            require(config.getString("sourceFixtureSha256")==easyHash(asset))
            require(config.getString("syntheticBearer")=="public-p8-loopback-transport-token")
            require(pin.matches(Regex("[a-f0-9]{64}")))
            require(setOf(URI(tls).port,URI(relay).port,URI(control).port).size==3)
            require(credential.binding.id=="binding-vector"&&credential.binding.desktopId=="desktop-vector"&&credential.binding.mobileId=="mobile-vector")
        }
        private fun origin(key: String,scheme: String): String {
            val value=config.getString(key);val uri=URI(value)
            require(uri.scheme==scheme&&uri.host=="127.0.0.1"&&uri.port in 1..65535&&uri.rawUserInfo==null&&uri.rawQuery==null&&uri.rawFragment==null&&uri.rawPath in listOf("","/")) { "Only exact synthetic loopback origins are allowed" }
            return value.trimEnd('/')
        }
        suspend fun api(path: String,body: JSONObject?=null): JSONObject=withContext(Dispatchers.IO) {
            require(path in setOf("/health","/begin-case","/stats","/end-case"))
            val connection=URL(control+path).openConnection() as HttpURLConnection
            try {
                connection.connectTimeout=1500;connection.readTimeout=1500;connection.instanceFollowRedirects=false
                connection.setRequestProperty("X-P8-Fixture",config.getString("instanceId"))
                if(body!=null) {
                    val bytes=body.toString().toByteArray(Charsets.UTF_8);connection.requestMethod="POST";connection.doOutput=true
                    connection.setRequestProperty("Content-Type","application/json");connection.setFixedLengthStreamingMode(bytes.size)
                    connection.outputStream.use { it.write(bytes) }
                }
                check(connection.responseCode==200) { "Synthetic fixture control rejected" }
                val bytes=connection.inputStream.use { it.readBytes() };require(bytes.size<=65536)
                JSONObject(String(bytes,Charsets.UTF_8))
            } finally { connection.disconnect() }
        }
        suspend fun begin(mode: String) {
            val health=api("/health")
            require(health.getString("fixture")=="p8-synthetic-loopback-transport"&&health.getString("instanceId")==config.getString("instanceId")&&health.getBoolean("onlyLoopback"))
            require(!health.getBoolean("realDesktop")&&!health.getBoolean("realModel"))
            for(key in listOf("sourceFixtureSha256","tlsOrigin","relayOrigin","controlOrigin","certificateSha256"))require(health.getString(key)==config.getString(key))
            assertEquals(caseId,api("/begin-case",JSONObject().put("caseId",caseId).put("mode",mode)).getString("caseId"))
        }
        suspend fun stats(): JSONObject=api("/stats").also { assertEquals(caseId,it.getString("caseId"));assertEquals(0,it.getInt("realDesktopRequests"));assertEquals(0,it.getInt("realModelInputs"));assertEquals(0,it.getJSONArray("errors").length()) }
        suspend fun end() { api("/end-case",JSONObject().put("caseId",caseId)) }
        fun session(preference: String="automatic")=EasyWorkRemoteSession(AgentConnection(target.scope(),listOf(lanCandidate,relayCandidate),"synthetic-loopback-only",preference),credential,{ config.getString("syntheticBearer") })
        fun protocol(stats: JSONObject)=stats.getJSONArray("protocol").objects()
    }
    private suspend fun scenario(mode: String="normal",block: suspend (Fixture)->Unit) {
        val f=Fixture();f.begin(mode)
        try { withTimeout(10_000) { block(f) } }
        finally { withContext(NonCancellable) { withTimeout(2000) { f.end() } } }
    }
    private fun assertActualTlsFailure(failure: Throwable?) {
        assertNotNull(failure);assertFalse("A deadline is not certificate rejection",failure is CancellationException)
        assertTrue("Actual TLS/certificate cause required, got ${failure?.javaClass?.name}",generateSequence(failure) { it.cause }.take(12).any { it is SSLException||it is CertificateException })
    }
    private suspend fun awaitAcknowledgements(f: Fixture): JSONObject=withTimeout(2500) {
        while(true) { val stats=f.stats();if(stats.getJSONArray("acknowledgements").length()>=2)return@withTimeout stats;delay(25) }
        @Suppress("UNREACHABLE_CODE") error("No acknowledgements")
    }
    @Test(timeout=15_000) fun pinnedTlsDescriptorAndEncryptedWssUseProductionOkHttp()=runBlocking {
        scenario { f->
            val host=EasyWorkPairing.readHost(f.tls,f.pin,f.target,f.credential.binding.desktopPublicKey)
            assertEquals(f.target,host.target)
            val session=f.session("lan-only")
            try {
                assertEquals("lan-wss",session.request("runtime/context").getString("path"))
                assertEquals("lan-wss",session.activeCandidate?.transportId)
                val stats=f.stats();assertTrue(stats.getInt("hostReads")>=1);assertEquals(1,stats.getInt("upgrades"));assertEquals(listOf("runtime/context"),f.protocol(stats).map { it.getString("method") })
            } finally { session.close() }
        }
    }
    @Test(timeout=15_000) fun changedPinIsRejectedByActualTlsBeforeProtocolTraffic()=runBlocking {
        scenario { f->
            val failure=runCatching { EasyWorkPairing.readHost(f.tls,"0".repeat(64),f.target,f.credential.binding.desktopPublicKey) }.exceptionOrNull()
            assertActualTlsFailure(failure)
            val stats=f.stats();assertEquals(0,stats.getInt("hostReads"));assertEquals(0,stats.getInt("upgrades"));assertEquals(0,f.protocol(stats).size)
        }
    }
    @Test(timeout=15_000) fun wrongDescriptorTargetIsRejectedAfterPinnedHttpsBeforeWss()=runBlocking {
        scenario("wrong-descriptor-target") { f->
            val failure=runCatching { EasyWorkPairing.readHost(f.tls,f.pin,f.target,f.credential.binding.desktopPublicKey) }.exceptionOrNull()
            assertTrue(failure is IllegalArgumentException)
            val stats=f.stats();assertEquals(1,stats.getInt("hostReads"));assertEquals(0,stats.getInt("upgrades"));assertEquals(0,f.protocol(stats).size)
        }
    }
    @Test(timeout=15_000) fun relayHttpActuallyPostsPollsAndAcknowledgesEncryptedFrames()=runBlocking {
        scenario { f->
            val session=f.session("relay-only")
            try {
                assertEquals("relay-http",session.request("runtime/context").getString("path"))
                val stats=awaitAcknowledgements(f);assertTrue(stats.getInt("relayPosts")>=2);assertTrue(stats.getInt("relayPolls")>=1)
                assertEquals(0,stats.getInt("upgrades"));assertEquals(1,f.protocol(stats).size)
                assertTrue(stats.getInt("authorizationRequests")>=3)
            } finally { session.close() }
        }
    }
    @Test(timeout=15_000) fun relayHttpsSelfSignedCertificateIsRejectedBySystemTrust()=runBlocking {
        scenario { f->
            val transport=RelayFrameTransport(AgentCandidate("p8-relay-https-negative","relay-http",f.tls),f.credential.binding.id,{ f.config.getString("syntheticBearer") })
            try {
                assertActualTlsFailure(runCatching { transport.api("/bindings") }.exceptionOrNull())
                assertEquals(0,f.stats().getInt("authorizationRequests"))
            } finally { transport.close() }
        }
    }
    @Test(timeout=15_000) fun lostReadReplyRetriesTheSameRequestFromRealWssToRelay()=runBlocking {
        scenario("drop-read") { f->
            val session=f.session()
            try {
                assertEquals("relay-http",session.request("thread/read",JSONObject().put("threadId","synthetic-thread"),"read-"+f.caseId).getString("path"))
                val rows=f.protocol(f.stats());assertEquals(listOf("lan-wss","relay-http"),rows.map { it.getString("path") })
                assertEquals(listOf("thread/read","thread/read"),rows.map { it.getString("method") });assertEquals(rows[0].getString("requestId"),rows[1].getString("requestId"));assertEquals(rows[0].getString("wireRequestHash"),rows[1].getString("wireRequestHash"))
            } finally { session.close() }
        }
    }
    private suspend fun writeRecovery(mode: String,unknown: Boolean,reverse: Boolean=false) = scenario(mode) { f->
        val session=f.session();val operation="operation-"+f.caseId
        try {
            val result=runCatching { session.request("turn/start",JSONObject().put("threadId","synthetic-thread").put("clientRequestId",operation).put("text","public synthetic fixture input; never a model"),"write-"+f.caseId) }
            if(unknown)assertTrue(result.exceptionOrNull() is EasyWorkRejection)
            else assertEquals("original-synthetic-once",result.getOrThrow().getString("receipt"))
            val stats=f.stats();val rows=f.protocol(stats)
            assertEquals(listOf("turn/start","command/status"),rows.map { it.getString("method") })
            assertEquals(if(reverse) listOf("relay-http","lan-wss") else listOf("lan-wss","relay-http"),rows.map { it.getString("path") })
            assertEquals(operation,rows[0].getString("operationId"));assertEquals(operation,rows[1].getString("commandId"))
            if(reverse) { assertEquals(1,stats.getInt("relayResponseDrops"));assertEquals(1,stats.getInt("upgradeRejections")) }
        } finally { session.close() }
    }
    @Test(timeout=15_000) fun lostWriteReplyOnlyQueriesOriginalOperationAcrossWssAndRelay()=runBlocking { writeRecovery("drop-write",false) }
    @Test(timeout=15_000) fun unknownWriteResultOverRealTransportsNeverReplaysInput()=runBlocking { writeRecovery("drop-write-unknown",true) }
    @Test(timeout=15_000) fun droppedRelayPostResponseQueriesOriginalOperationOverActualWss()=runBlocking { writeRecovery("relay-drop-write",false,true) }
    @Test(timeout=15_000) fun wrongEncryptedResponseTargetIsRejectedOverPinnedWss()=runBlocking {
        scenario("wrong-response-target") { f->
            val session=f.session("lan-only")
            try {
                val failure=runCatching { session.request("thread/read",JSONObject().put("threadId","synthetic-thread")) }.exceptionOrNull()
                assertTrue(failure is IllegalArgumentException);assertTrue(failure?.message?.contains("运行时身份不匹配")==true)
                val stats=f.stats();assertTrue(stats.getInt("upgrades")>=1);assertTrue(f.protocol(stats).isNotEmpty());assertTrue(f.protocol(stats).all { it.getString("method")=="thread/read" })
            } finally { session.close() }
        }
    }
    @Test(timeout=15_000) fun relayFiltersForeignBindingWithoutAcknowledgingItsEnvelope()=runBlocking {
        scenario("relay-foreign-binding") { f->
            val session=f.session("relay-only")
            try {
                assertEquals("relay-http",session.request("runtime/context").getString("path"))
                val stats=awaitAcknowledgements(f);assertEquals(1,stats.getInt("foreignDelivered"));assertEquals(0,stats.getInt("foreignAcknowledged"))
                assertTrue(stats.getJSONArray("acknowledgements").objects().all { it.getString("bindingId")==f.credential.binding.id })
            } finally { session.close() }
        }
    }
    @Test(timeout=15_000) fun relayRedirectCannotForwardItsSyntheticAuthorization()=runBlocking {
        scenario("relay-redirect") { f->
            val transport=RelayFrameTransport(f.relayCandidate,f.credential.binding.id,{ f.config.getString("syntheticBearer") })
            try {
                val failure=runCatching { transport.api("/bindings") }.exceptionOrNull()
                assertTrue(failure is IllegalArgumentException);assertTrue(failure?.message?.contains("302")==true)
                val stats=f.stats();assertEquals(1,stats.getInt("authorizationRequests"));assertEquals(0,stats.getInt("redirectedRequests"))
            } finally { transport.close() }
        }
    }
}
