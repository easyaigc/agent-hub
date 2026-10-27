package com.agenthub.android

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.agenthub.core.TargetScope
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.UUID

/** Real native HTTP/cache boundaries against synthetic sources, never a desktop/model proof. */
class MobileResourceDownloadTest {
    @get:Rule(order=0) val foreground: org.junit.rules.TestRule=NativeTestForeground()
    @get:Rule(order=1) val ui=createAndroidComposeRule<MainActivity>()
    private lateinit var owned: OwnedControlAcceptance
    private lateinit var cache: File
    private val commands=NativeCodexCommands { "owned-resource-token" }
    private fun hash(bytes: ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun file(bytes: ByteArray="first".toByteArray(),id: String="version-one",thread: String="owned-thread")=
        MobileFile(id,"owned-report.txt","text/plain",bytes.size.toLong(),"owned-host",thread,hash(bytes))
    private fun client(server: CommandFixture,runtime: String="owned-runtime",workspace: String="owned-workspace")=
        MobileClient("owned-host","Owned resource",TargetScope("codex",null,null,"owned-host",workspace,runtime),server.endpoint,true,"desktop-runtime",false,requiresReadTarget=true)
    private fun wire(file: MobileFile)=JSONObject().put("id",file.id).put("hostId",file.hostId).put("threadId",file.threadId)
        .put("name",file.name).put("mediaType",file.mediaType).put("sizeBytes",file.sizeBytes).put("sha256",file.sha256)
    private fun resolution(client: MobileClient,original: MobileFile,current: MobileFile=original)=JSONObject()
        .put("hostId",client.id).put("threadId",original.threadId).put("sourceAttachmentId",original.id)
        .put("readTarget",CodexCommandCodec.target(client.scope!!)).put("attachment",wire(current))
    private fun params(request: WireRequest)=request.pathWithQuery.substringAfter('?').split('&').associate {
        java.net.URLDecoder.decode(it.substringBefore('='),"UTF-8") to java.net.URLDecoder.decode(it.substringAfter('='),"UTF-8")
    }
    private fun getOnly(server: CommandFixture) {
        for(request in server.requests) {
            assertEquals("GET",request.method);assertEquals("Bearer owned-resource-token",request.headers["authorization"])
            if(request.path=="/v1/attachments") {
                val target=JSONObject(params(request).getValue("target"))
                CodexCommandCodec.validate(target,"target");assertEquals("owned-host",target.getString("hostId"))
            } else assertFalse("Resolve GET exact query whitelist",params(request).containsKey("target"))
        }
    }
    private suspend fun failure(block: suspend ()->Unit): Exception {
        try { block() } catch(error: Exception) { return error }
        throw AssertionError("Expected resource boundary rejection")
    }
    @Before fun setup() {
        ui.waitUntil(30_000) { !ui.activity.session.restoring }
        ui.runOnIdle { owned=OwnedControlAcceptance(ui.activity,"resource-download");owned.session.resetAcceptance() }
        cache=File(ui.activity.cacheDir,"resource-native-"+UUID.randomUUID()).apply { mkdirs() }
    }
    @After fun finish() { ui.runOnIdle { if(::owned.isInitialized) { owned.session.leaveForeground();owned.verifyDefault() } } }

    @Test fun messageLinkResolvesOnlyExactOriginalClaimsAndTarget()=runBlocking {
        val reference="file:///owned/report%20name.txt#L2"
        assertTrue(MessageLinkDestination.sameAttachmentId("opaque-id","opaque-id"))
        assertFalse(MessageLinkDestination.sameAttachmentId("opaque-id","OPAQUE-ID"))
        val canonical="00000001-0001-0001-0001-00000000000a"
        assertTrue(MessageLinkDestination.sameAttachmentId(canonical,canonical.uppercase()))
        assertFalse(MessageLinkDestination.sameAttachmentId(canonical,"1-1-1-1-a"))
        assertFalse(MessageLinkDestination.sameAttachmentId(canonical,null))
        lateinit var c: MobileClient
        CommandFixture { req->
            assertEquals("/v1/attachments/resolve-link",req.path)
            assertEquals(mapOf("hostId" to "owned-host","threadId" to "owned-thread","messageId" to "owned-message","reference" to reference),params(req))
            WireResponse(body=resolution(c,file()).put("messageId","owned-message").put("reference",reference).toString())
        }.use { server->c=client(server)
            assertEquals(file(),commands.resolveMessageFile(c,"owned-thread","owned-message",reference));getOnly(server)
        }
    }
    @Test fun malformedOrRetargetedMessageClaimsNeverBecomeDownloads()=runBlocking {
        val reference="sandbox:/owned/report.txt"
        lateinit var c: MobileClient;var mutate: (JSONObject)->Unit={}
        CommandFixture { WireResponse(body=resolution(c,file()).put("messageId","owned-message").put("reference",reference).also(mutate).toString()) }.use { server->
            c=client(server)
            val cases=listOf<(JSONObject)->Unit>(
                { it.put("hostId","other") },{ it.put("threadId","other") },{ it.put("messageId","other") },{ it.put("reference","/other") },
                { it.put("hostId",123) },{ it.getJSONObject("readTarget").put("runtimeId","other") },
                { it.getJSONObject("readTarget").put("workspaceId","other") },{ it.getJSONObject("readTarget").put("agentId","other") },
                { it.getJSONObject("readTarget").put("runtimeId",123) },
                { it.getJSONObject("attachment").put("hostId","other") },{ it.getJSONObject("attachment").put("threadId","other") },
                { it.getJSONObject("attachment").put("id","../escape") },{ it.getJSONObject("attachment").put("name","../escape") },
                { it.getJSONObject("attachment").put("name","..\\escape") },{ it.getJSONObject("attachment").put("sizeBytes",20L*1024*1024+1) },
                { it.getJSONObject("attachment").put("sizeBytes","5") },{ it.getJSONObject("attachment").put("sha256","ABC") })
            for(change in cases) { mutate=change;assertTrue(failure { commands.resolveMessageFile(c,"owned-thread","owned-message",reference) } is CodexCommandError) }
            assertTrue(cache.listFiles().orEmpty().isEmpty());getOnly(server)
        }
    }
    @Test fun currentVersionIsResolvedBeforeCacheAndCorruptCacheIsRepaired()=runBlocking {
        var bytes="first".toByteArray();var current=file(bytes);lateinit var c: MobileClient
        CommandFixture { req->if(req.path.endsWith("/resolve"))WireResponse(body=resolution(c,file(),current).toString()) else WireResponse(raw=bytes) }.use { server->
            c=client(server);val first=commands.downloadResource(c,file(),cache);assertEquals("first",first.readText())
            bytes="new current body".toByteArray();current=file(bytes,"version-two")
            val second=commands.downloadResource(c,file(),cache);assertEquals("new current body",second.readText());assertNotEquals(first,second)
            assertEquals(second,commands.downloadResource(c,file(),cache))
            assertEquals(2,server.requests.count { it.path=="/v1/attachments" })
            second.writeText("corrupt cached bytes")
            assertEquals("new current body",commands.downloadResource(c,file(),cache).readText())
            assertEquals(4,server.requests.count { it.path.endsWith("/resolve") });assertEquals(3,server.requests.count { it.path=="/v1/attachments" });getOnly(server)
        }
    }
    @Test fun removedCurrentSourceCannotResurrectAValidCacheButOldHostKeepsExactVersion()=runBlocking {
        var status=200;lateinit var c: MobileClient
        CommandFixture { req->if(req.path.endsWith("/resolve"))WireResponse(status,if(status==200)resolution(c,file()).toString() else "{\"error\":\"owned removed\"}") else WireResponse(raw="first".toByteArray()) }.use { server->
            c=client(server);val original=commands.downloadResource(c,file(),cache)
            status=410;val error=failure { commands.downloadResource(c,file(),cache) };assertEquals(410,(error as CodexCommandError).status)
            assertEquals("first",original.readText());assertEquals(1,server.requests.count { it.path=="/v1/attachments" })
            status=404;assertEquals(original,commands.downloadResource(c,file(),cache));assertEquals(1,server.requests.count { it.path=="/v1/attachments" });getOnly(server)
        }
    }
    @Test fun wrongCurrentVersionClaimsAreRejectedBeforeCachedBytes()=runBlocking {
        lateinit var c: MobileClient;var mutate: (JSONObject)->Unit={}
        CommandFixture { req->if(req.path.endsWith("/resolve"))WireResponse(body=resolution(c,file()).also(mutate).toString()) else WireResponse(raw="first".toByteArray()) }.use { server->
            c=client(server);commands.downloadResource(c,file(),cache)
            for(change in listOf<(JSONObject)->Unit>({ it.put("sourceAttachmentId","other") },{ it.getJSONObject("readTarget").put("runtimeId","other") },
                { it.getJSONObject("attachment").put("name","other.txt") },{ it.getJSONObject("attachment").put("mediaType","image/png") },
                { it.getJSONObject("attachment").put("sha256","invalid") })) {
                mutate=change;failure { commands.downloadResource(c,file(),cache) }
            }
            assertEquals(1,server.requests.count { it.path=="/v1/attachments" });getOnly(server)
        }
    }
    @Test fun versionRaceRetriesOnlyOneReadAndRepeatedRaceStops()=runBlocking {
        var downloads=0;var always=false;lateinit var c: MobileClient
        CommandFixture { req->
            if(req.path.endsWith("/resolve")) WireResponse(body=resolution(c,file()).toString())
            else { downloads++;if(always||downloads==1)WireResponse(409,"{\"error\":\"owned changed\"}") else WireResponse(raw="first".toByteArray()) }
        }.use { server->
            c=client(server);assertEquals("first",commands.downloadResource(c,file(),cache).readText());assertEquals(2,downloads)
            always=true
            val separate=File(cache,"second-race")
            assertEquals(409,(failure { commands.downloadResource(c,file(),separate) } as CodexCommandError).status)
            assertEquals(4,downloads);assertEquals(4,server.requests.count { it.path.endsWith("/resolve") });getOnly(server)
        }
    }
    @Test fun runtimeChangeBetweenResolutionAndBinaryReadCannotBypassOriginalTarget()=runBlocking {
        lateinit var c: MobileClient;var resolves=0
        CommandFixture { req->
            if(req.path.endsWith("/resolve")) {
                resolves++
                val response=resolution(if(resolves==1)c else c.copy(scope=c.scope!!.let { TargetScope(it.agentId,it.accountAuthority,it.accountSubject,it.hostId,it.workspaceId,"restarted-runtime") }),file())
                WireResponse(body=response.toString())
            } else {
                val expected=JSONObject(params(req).getValue("target"))
                assertEquals("owned-runtime",expected.getString("runtimeId"))
                WireResponse(409,"{\"error\":\"owned runtime changed\"}")
            }
        }.use { server->
            c=client(server);failure { commands.downloadResource(c,file(),cache) }
            assertEquals(2,resolves);assertEquals(1,server.requests.count { it.path=="/v1/attachments" })
            assertTrue(cache.walkTopDown().none { it.isFile });getOnly(server)
        }
    }
    @Test fun corruptOrTruncatedBytesNeverPublishAndRedirectsNeverForwardCredentials()=runBlocking {
        var payload="wrong".toByteArray();lateinit var c: MobileClient
        CommandFixture { req->if(req.path.endsWith("/resolve"))WireResponse(body=resolution(c,file()).toString()) else WireResponse(raw=payload) }.use { server->
            c=client(server)
            for(bytes in listOf("wrong".toByteArray(),"firs".toByteArray(),"first extra".toByteArray())) {
                payload=bytes;failure { commands.downloadResource(c,file(),cache) }
                assertTrue(cache.walkTopDown().none { it.isFile })
            };getOnly(server)
        }
        CommandFixture { WireResponse() }.use { other->
            lateinit var redirectClient: MobileClient
            CommandFixture { req->if(req.path.endsWith("/resolve"))WireResponse(body=resolution(redirectClient,file()).toString()) else WireResponse(302,headers=mapOf("Location" to other.endpoint+"/capture")) }.use { server->
                redirectClient=client(server);assertEquals(302,(failure { commands.downloadResource(redirectClient,file(),cache) } as CodexCommandError).status)
                assertTrue(other.requests.isEmpty());getOnly(server)
            }
        }
    }
    @Test fun targetThreadAndVersionIsolateCacheButCodexAccountMetadataDoesNotRetarget()=runBlocking {
        lateinit var active: MobileClient;var thread="owned-thread"
        CommandFixture { req->val original=file(thread=thread);if(req.path.endsWith("/resolve"))WireResponse(body=resolution(active,original).toString()) else WireResponse(raw="first".toByteArray()) }.use { server->
            active=client(server);val first=commands.downloadResource(active,file(),cache)
            active=active.copy(scope=active.scope!!.let { TargetScope(it.agentId,"new-authority","new-subject",it.hostId,it.workspaceId,it.runtimeId) })
            assertEquals(first,commands.downloadResource(active,file(),cache));assertEquals(1,server.requests.count { it.path=="/v1/attachments" })
            active=client(server,runtime="other-runtime");val runtime=commands.downloadResource(active,file(),cache)
            active=client(server,workspace="other-workspace");val workspace=commands.downloadResource(active,file(),cache)
            thread="other-thread";val otherThread=commands.downloadResource(active,file(thread=thread),cache)
            assertEquals(4,setOf(first,runtime,workspace,otherThread).size);assertEquals(4,server.requests.count { it.path=="/v1/attachments" });getOnly(server)
        }
    }
    @Test fun zeroAndMaximumSizedFilesStreamAndPublishOnlyExactBytes()=runBlocking {
        var bytes=ByteArray(0);var manifest=file(bytes,"empty-version");lateinit var c: MobileClient
        CommandFixture { req->if(req.path.endsWith("/resolve"))WireResponse(body=resolution(c,manifest).toString()) else WireResponse(raw=bytes) }.use { server->
            c=client(server);val empty=commands.downloadResource(c,manifest,cache);assertEquals(0L,empty.length());assertEquals(hash(bytes),hash(empty.readBytes()))
            bytes=ByteArray(20*1024*1024) { (it%251).toByte() };manifest=file(bytes,"maximum-version")
            val largest=commands.downloadResource(c,manifest,cache);assertEquals(20L*1024*1024,largest.length())
            val digest=MessageDigest.getInstance("SHA-256");largest.inputStream().use { input->val buffer=ByteArray(16_384);while(true) { val n=input.read(buffer);if(n<0)break;digest.update(buffer,0,n) } }
            assertEquals(manifest.sha256,digest.digest().joinToString("") { "%02x".format(it) })
            assertEquals(2,server.requests.count { it.path=="/v1/attachments" });assertTrue(cache.walkTopDown().none { it.name.startsWith(".resource-") });getOnly(server)
        }
    }
    @Test fun resourceQueryExceptionsDoNotRemoveOriginalControlTargetsOrReplayWrites()=runBlocking {
        val intent=ReplyIntent("owned-host","owned-thread","owned-synthetic-operation","synthetic boundary body",emptyList(),"next-turn")
        val receipt=JSONObject().put("hostId",intent.hostId).put("threadId",intent.threadId).put("clientMessageId",intent.operationId)
            .put("state","received").put("turnId",JSONObject.NULL).put("error",JSONObject.NULL)
        CommandFixture { req->
            assertTrue(req.path in listOf("/v1/replies","/v1/receipts"))
            val target=JSONObject(params(req).getValue("target"));assertEquals("owned-runtime",target.getString("runtimeId"))
            WireResponse(body=receipt.toString())
        }.use { server->
            val c=client(server)
            assertEquals("received",commands.send(c,intent).state)
            assertEquals("received",commands.receipt(c,intent.threadId,intent.operationId).state)
            assertEquals(1,server.requests.count { it.method=="POST" })
            assertEquals(intent.body().toString(),JSONObject(String(server.requests.first().body,Charsets.UTF_8)).toString())
            failure { commands.send(c.copy(id="other-host"),intent) }
            assertEquals(2,server.requests.size)
        }
    }
    @Test fun cancelledBinaryReadRemovesUnpublishedTemporaryAndNeverRetries()=runBlocking {
        val bodyReady=CountDownLatch(1);val release=CountDownLatch(1);lateinit var c: MobileClient
        CommandFixture { req->if(req.path.endsWith("/resolve"))WireResponse(body=resolution(c,file()).toString())
            else WireResponse(raw="first".toByteArray(),beforeBody={ bodyReady.countDown();release.await(10,TimeUnit.SECONDS) }) }.use { server->
            c=client(server)
            val job=async(Dispatchers.Default) { commands.downloadResource(c,file(),cache) }
            try {
                assertTrue(bodyReady.await(5,TimeUnit.SECONDS))
                withTimeout(5_000) { while(cache.walkTopDown().none { it.isFile&&it.name.startsWith(".resource-") })delay(20) }
                job.cancelAndJoin()
                withTimeout(5_000) { while(cache.walkTopDown().any { it.isFile })delay(20) }
                assertEquals(1,server.requests.count { it.path=="/v1/attachments" });getOnly(server)
            } finally { release.countDown();job.cancelAndJoin() }
        }
    }
}
