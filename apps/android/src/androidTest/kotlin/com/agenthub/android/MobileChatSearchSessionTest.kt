package com.agenthub.android

import androidx.test.platform.app.InstrumentationRegistry
import com.agenthub.core.TargetScope
import kotlinx.coroutines.*
import org.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class MobileChatSearchSessionTest {
    @get:Rule val foreground: org.junit.rules.TestRule=NativeTestForeground()
    private val target=TargetScope("codex",null,null,"search-host","workspace","runtime")
    private fun workspace()=JSONObject().put("hostId",target.hostId).put("name","search fixture").put("connectionMode","history-only")
        .put("readTarget",CodexCommandCodec.target(target)).put("capabilities",JSONArray()).put("projects",JSONArray())
        .put("threads",JSONArray(listOf("thread","other").map { JSONObject().put("id",it).put("hostId",target.hostId).put("title",it).put("state","idle") }))
    private fun page(ids: List<String>,cursor: String?=null,window: Boolean=false)=JSONObject().put("hostId",target.hostId).put("threadId","thread")
        .put("readTarget",CodexCommandCodec.target(target)).put("messages",JSONArray(ids.map {
            JSONObject().put("id",it).put("role","assistant").put("text","$it NEEDLE original")
        })).put("nextCursor",cursor ?: JSONObject.NULL).also {
            if(window)it.put("historyWindow",JSONObject().put("version",1).put("kind","latest")).put("historyRevision",1)
        }
    private suspend fun ready(server: CommandFixture,storage: NativeSessionStorage?=null,waitHistory: Boolean=true): MobileSession {
        val session=withContext(Dispatchers.Main) { MobileSession(storage,NativeHostReads { "" }).also { it.restore() } }
        withTimeout(5_000) { while(withContext(Dispatchers.Main) { session.restoring })delay(10) }
        withContext(Dispatchers.Main) { session.connect("search fixture",server.endpoint,"");session.selectThread(session.threads.first { it.id=="thread" }) }
        if(waitHistory)withTimeout(5_000) { while(withContext(Dispatchers.Main) { session.messages.isEmpty()||session.historyLoading })delay(10) }
        return session
    }
    private suspend fun stop(session: MobileSession,server: CommandFixture) {
        withContext(Dispatchers.Main) { session.leaveForeground() }
        assertTrue("Search cannot send execution or management writes",server.requests.all { it.method=="GET" })
    }
    @Test fun fullReadUsesIndependentEndpointAndSelectionPersistsWithoutChangingDraftOrAuthority()=runBlocking {
        val namespace="search-session-"+UUID.randomUUID()
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val storage=NativeSessionStorage(context,namespace)
        CommandFixture { request -> when(request.path) {
            "/v1/workspace" -> WireResponse(body=workspace().toString())
            "/v1/history" -> WireResponse(body=page(listOf("m2","m3"),"normal-older",true).toString())
            "/v1/threads/search-history" -> WireResponse(body=if("cursor=" in request.pathWithQuery)page(listOf("m0","m1","m2")).toString() else page(listOf("m2","m3"),"search-older").toString())
            else -> WireResponse(404)
        } }.use { server ->
            val session=ready(server,storage)
            try {
                val captured=withContext(Dispatchers.Main) { session.text="unsent draft";session.captureChatSearch()!! }
                val values=withContext(Dispatchers.Main) { session.completeChatSearchHistory(captured) }
                assertEquals(listOf("m0","m1","m2","m3"),values.map { it.id })
                withContext(Dispatchers.Main) {
                    assertEquals(listOf("m2","m3"),session.messages.map { it.id });assertEquals("normal-older",session.historyCursor)
                    session.prepareChatSearchSelection(captured,values.first()," NEEDLE ",values,true)
                    assertNull(session.chatSearchJump);assertEquals(listOf("m2","m3"),session.messages.map { it.id })
                    session.applyPendingChatSearch()
                    assertEquals("m0",session.chatSearchJump?.message?.id);assertNull(session.historyCursor)
                    assertEquals("unsent draft",session.text);assertFalse(session.canCompose)
                }
                withTimeout(5_000) { while(NativeHistoryCache(storage).read(captured.key)?.messages?.size!=values.size)delay(10) }
                val reopened=NativeHistoryCache(NativeSessionStorage(context,namespace)).read(captured.key)!!
                assertEquals(values.map { it.id },reopened.messages.map { it.id });assertNull(reopened.nextCursor)
                assertEquals(2,server.requests.count { it.path=="/v1/threads/search-history" })
                assertTrue(server.requests.filter { it.path=="/v1/threads/search-history" }.none { "window=" in it.pathWithQuery })
            } finally { stop(session,server) }
        }
    }
    @Test fun repeatedCursorRejectsCompleteReadAndRetainsOriginalCacheAndDraft()=runBlocking {
        CommandFixture { request -> WireResponse(body=when(request.path) {
            "/v1/workspace" -> workspace().toString()
            "/v1/threads/search-history" -> page(listOf("untrusted-partial"),"cycle").toString()
            else -> if("threadId=other" in request.pathWithQuery)page(emptyList()).put("threadId","other").toString() else page(listOf("cached")).toString()
        }) }.use { server ->
            val session=ready(server)
            try {
                val context=withContext(Dispatchers.Main) { session.text="keep";session.captureChatSearch()!! }
                try { withContext(Dispatchers.Main) { session.completeChatSearchHistory(context) };fail("Cycle must fail") } catch(_: IllegalArgumentException) { }
                withContext(Dispatchers.Main) { assertEquals(listOf("cached"),session.messages.map { it.id });assertEquals("keep",session.text);assertNull(session.chatSearchJump) }
                assertEquals(2,server.requests.count { it.path=="/v1/threads/search-history" })
            } finally { stop(session,server) }
        }
    }
    @Test fun responseHostThreadAndMachineMustMatchEvenForReadOnlySearch()=runBlocking {
        val mode=AtomicInteger(0)
        CommandFixture { request -> WireResponse(body=when(request.path) {
            "/v1/workspace" -> workspace()
            "/v1/threads/search-history" -> page(listOf("wrong")).also { value -> when(mode.get()) {
                0 -> value.put("hostId","other")
                1 -> value.put("threadId","other")
                2 -> value.put("readTarget",CodexCommandCodec.target(TargetScope("codex",null,null,target.hostId,"workspace","other-runtime")))
                else -> value.put("readTarget",CodexCommandCodec.target(TargetScope("codex","historical","other-account",target.hostId,"workspace","runtime")))
            } }
            else -> page(listOf("cached"))
        }.toString()) }.use { server ->
            val session=ready(server)
            try {
                val context=withContext(Dispatchers.Main) { session.captureChatSearch()!! }
                repeat(3) { mode.set(it);try { withContext(Dispatchers.Main) { session.completeChatSearchHistory(context) };fail("Wrong target must fail") } catch(_: Exception) { } }
                mode.set(3);assertEquals(listOf("wrong"),withContext(Dispatchers.Main) { session.completeChatSearchHistory(context) }.map { it.id })
                withContext(Dispatchers.Main) { assertEquals(listOf("cached"),session.messages.map { it.id }) }
            } finally { stop(session,server) }
        }
    }
    @Test fun lateCompleteReadCannotOverwriteNewTargetDraft()=runBlocking {
        val started=CountDownLatch(1);val release=CountDownLatch(1)
        CommandFixture { request -> WireResponse(body=when(request.path) {
            "/v1/workspace" -> workspace().toString()
            "/v1/threads/search-history" -> { started.countDown();check(release.await(5,TimeUnit.SECONDS));page(listOf("late")).toString() }
            else -> if("threadId=other" in request.pathWithQuery)page(emptyList()).put("threadId","other").toString() else page(listOf("cached")).toString()
        }) }.use { server ->
            val session=ready(server)
            try {
                val context=withContext(Dispatchers.Main) { session.captureChatSearch()!! }
                val job=async(Dispatchers.Main) { session.completeChatSearchHistory(context) }
                check(withContext(Dispatchers.IO) { started.await(3,TimeUnit.SECONDS) })
                withContext(Dispatchers.Main) { session.selectThread(session.threads.first { it.id=="other" });session.text="new target draft" }
                release.countDown()
                try { job.await();fail("Late target result must cancel") } catch(_: CancellationException) { }
                withContext(Dispatchers.Main) { assertEquals("new target draft",session.text);assertNull(session.chatSearchJump);assertTrue(session.messages.isEmpty()) }
            } finally { release.countDown();stop(session,server) }
        }
    }
    @Test fun pendingSelectionCannotApplyToNewTargetAfterSheetDismissal()=runBlocking {
        CommandFixture { request -> WireResponse(body=if(request.path=="/v1/workspace")workspace().toString() else if("threadId=other" in request.pathWithQuery)page(emptyList()).put("threadId","other").toString() else page(listOf("cached")).toString()) }.use { server ->
            val session=ready(server)
            try { withContext(Dispatchers.Main) {
                val context=session.captureChatSearch()!!
                session.prepareChatSearchSelection(context,session.messages.single(),"NEEDLE",session.messages,false)
                session.selectThread(session.threads.first { it.id=="other" });session.text="later draft";session.applyPendingChatSearch()
                assertNull(session.pendingChatSearchToken);assertNull(session.chatSearchJump);assertEquals("later draft",session.text);assertTrue(session.messages.isEmpty())
            } } finally { stop(session,server) }
        }
    }
    @Test fun searchCanSeedCacheBeforeInitialWindowAndLateWindowRetainsLocatedMessage()=runBlocking {
        val started=CountDownLatch(1);val release=CountDownLatch(1)
        CommandFixture { request -> WireResponse(body=when(request.path) {
            "/v1/workspace" -> workspace().toString()
            "/v1/history" -> { started.countDown();check(release.await(5,TimeUnit.SECONDS));page(listOf("latest"),"normal-older",true).toString() }
            "/v1/threads/search-history" -> page(listOf("old","latest")).toString()
            else -> "{}"
        }) }.use { server ->
            val storage=NativeSessionStorage(InstrumentationRegistry.getInstrumentation().targetContext,"search-early-"+UUID.randomUUID())
            val session=ready(server,storage,false)
            try {
                check(withContext(Dispatchers.IO) { started.await(3,TimeUnit.SECONDS) })
                val context=withContext(Dispatchers.Main) { session.captureChatSearch()!! }
                val history=withContext(Dispatchers.Main) { session.completeChatSearchHistory(context) }
                withContext(Dispatchers.Main) { session.prepareChatSearchSelection(context,history.first(),"NEEDLE",history,true);session.applyPendingChatSearch() }
                release.countDown()
                withTimeout(5_000) { while(withContext(Dispatchers.Main) { session.historyLoading })delay(10) }
                withContext(Dispatchers.Main) { assertEquals(listOf("old","latest"),session.messages.map { it.id });assertEquals("old",session.chatSearchJump?.message?.id);assertFalse(session.canCompose) }
                withTimeout(5_000) { while(NativeHistoryCache(storage).read(context.key)?.messages?.size!=2)delay(10) }
            } finally { release.countDown();stop(session,server) }
        }
    }
}
