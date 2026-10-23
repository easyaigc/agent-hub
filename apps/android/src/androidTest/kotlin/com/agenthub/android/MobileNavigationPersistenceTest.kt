package com.agenthub.android

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Native encrypted storage FIFO proves read scheduling without timing thresholds or private-field hooks. */
@RunWith(AndroidJUnit4::class)
class MobileNavigationPersistenceTest {
    @get:Rule(order=0) val foreground: org.junit.rules.TestRule=NativeTestForeground()
    @get:Rule(order=1) val ui=createAndroidComposeRule<MainActivity>()
    @get:Rule(order=2) val diagnostics: org.junit.rules.TestRule=NativeTestDiagnostics()
    private lateinit var storage: NativeSessionStorage
    private lateinit var subject: MobileSession
    private val host="navigation-"+UUID.randomUUID()
    private val failures=AtomicReference<Exception>()
    private var onRead: suspend (String)->Unit={}
    private val releases=mutableListOf<CompletableDeferred<Unit>>()
    private val diskReleases=mutableListOf<CountDownLatch>()

    private fun main(block: suspend ()->Unit)=runBlocking { withContext(Dispatchers.Main) { block() } }
    private fun gate()=CompletableDeferred<Unit>().also { releases.add(it) }
    private fun thread(id: String)=subject.threads.single { it.id==id }
    private fun savedThread(): String {
        val selected=storage.metadata()!!.getJSONObject("selectedThreads")
        assertEquals(1,selected.length())
        return selected.getString(selected.keys().next())
    }
    private fun drain() {
        val done=CountDownLatch(1)
        storage.enqueueSnapshot("native-navigation-drain-"+UUID.randomUUID(),{ done.countDown() },failures::set)
        assertTrue("Native storage FIFO did not drain",done.await(15,TimeUnit.SECONDS))
        failures.get()?.let { throw it }
    }
    @Before fun setup() {
        storage=NativeSessionStorage(ui.activity,"navigation-"+UUID.randomUUID())
        val reader=object: HostReads {
            override suspend fun workspace(client: MobileClient,projectCursor: String?,threadCursor: String?)=
                HostReadCodec.workspace(JSONObject().put("hostId",host).put("name","synthetic navigation reads")
                    .put("connectionMode","history-only").put("projects",JSONArray())
                    .put("threads",JSONArray(listOf("a","b").map { JSONObject().put("hostId",host).put("id",it).put("title",it).put("state","idle") })).toString())
            override suspend fun history(client: MobileClient,threadId: String,cursor: String?): MobileHistory {
                onRead(threadId)
                return HostReadCodec.history(JSONObject().put("hostId",host).put("threadId",threadId)
                    .put("messages",JSONArray().put(JSONObject().put("id","row-$threadId").put("role","assistant").put("text","synthetic $threadId").put("status","completed"))).toString())
            }
        }
        subject=MobileSession(storage,reader=reader)
        main { subject.resetAcceptance(reader);subject.connect("synthetic navigation","https://fixture.invalid","") }
        ui.waitUntil(10_000) { !subject.loading&&subject.hasClient }
        main { subject.selectThread(thread("a"));subject.text="original a draft" }
        ui.waitUntil(10_000) { !subject.historyLoading&&subject.messages.singleOrNull()?.id=="row-a" }
        drain();assertEquals("a",savedThread())
    }
    @After fun stop() {
        releases.forEach { it.complete(Unit) };diskReleases.forEach { it.countDown() }
        if(::subject.isInitialized)main { subject.leaveForeground() }
    }
    @Test fun navigationStartsHistoryBeforeEnqueueingItsMetadataSnapshot() {
        val blocked=CountDownLatch(1);val release=CountDownLatch(1).also { diskReleases.add(it) }
        storage.enqueueSnapshot("native-navigation-block",{ blocked.countDown();check(release.await(15,TimeUnit.SECONDS)) },failures::set)
        assertTrue(blocked.await(10,TimeUnit.SECONDS))
        val observed=AtomicReference<String>();val readMarker=CountDownLatch(1);val history=gate()
        onRead={ id->if(id=="b") {
            storage.enqueueSnapshot("native-read-start",{ observed.set(savedThread());readMarker.countDown() },failures::set)
            history.await()
        } }
        try {
            main { subject.selectThread(thread("b")) }
            release.countDown();assertTrue(readMarker.await(15,TimeUnit.SECONDS));drain()
            assertEquals("Read must be scheduled before the navigation snapshot enters the native FIFO","a",observed.get())
            assertEquals("Navigation remains durable before its pending GET completes","b",savedThread())
            assertEquals("b",subject.selectedThread);assertTrue(subject.historyLoading);assertTrue(subject.operationRecords.isEmpty())
        } finally { release.countDown();history.complete(Unit) }
    }
    @Test fun immediateDraftEditRemainsDurableWhileNavigationReadIsPending() {
        val history=gate();onRead={ if(it=="b")history.await() }
        main { subject.selectThread(thread("b"));subject.text="new b draft" }
        drain();assertEquals("b",savedThread())
        val drafts=storage.metadata()!!.getJSONObject("drafts")
        val values=drafts.keys().asSequence().map { drafts.getString(it) }.toSet()
        assertTrue(values.containsAll(listOf("original a draft","new b draft")))
        history.complete(Unit);ui.waitUntil(10_000) { !subject.historyLoading&&subject.messages.singleOrNull()?.id=="row-b" }
        drain();assertEquals("new b draft",subject.text);assertEquals("b",savedThread());assertTrue(subject.operationRecords.isEmpty())
    }
    @Test fun rapidReturnKeepsOriginalTargetAndDraftDespiteLateOtherHistory() {
        val entered=CompletableDeferred<Unit>();val history=gate()
        onRead={ if(it=="b")withContext(NonCancellable) { entered.complete(Unit);history.await() } }
        main { subject.selectThread(thread("b"));subject.text="separate b draft" }
        runBlocking { withTimeout(10_000) { entered.await() } }
        main { subject.selectThread(thread("a")) }
        ui.waitUntil(10_000) { !subject.historyLoading&&subject.messages.singleOrNull()?.id=="row-a" }
        history.complete(Unit);main { yield() };drain()
        assertEquals("a",subject.selectedThread);assertEquals("original a draft",subject.text);assertEquals("a",savedThread())
        assertEquals(listOf("row-a"),subject.messages.map { it.id });assertTrue(subject.operationRecords.isEmpty())
        val drafts=storage.metadata()!!.getJSONObject("drafts")
        assertTrue(drafts.keys().asSequence().map { drafts.getString(it) }.toSet().contains("separate b draft"))
    }
}
