package com.agenthub.android

import androidx.test.platform.app.InstrumentationRegistry
import com.agenthub.core.SuggestionScope
import com.agenthub.core.TargetScope
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/** Native coroutine/state boundaries with held providers. No model, real owner or rendered UI. */
class MobileSuggestionReaderTest {
    private val i get()=InstrumentationRegistry.getInstrumentation()
    private lateinit var tasks: CoroutineScope
    private val gates=mutableListOf<CompletableDeferred<Unit>>()
    private val aheads=mutableListOf<NativeSuggestionReadAhead>()
    private val readers=mutableListOf<NativeDesktopSuggestionReader>()
    private var defaultMetadata: String?=null
    private val target=TargetScope("codex",null,null,"reader-fixture","workspace","runtime")
    private fun scope(thread: String="a",navigation: String="nav")=SuggestionScope(target,thread,"source-$thread",navigation,"connection",false,true,false)
    private fun response(scope: SuggestionScope,text: String="next",expiry: Double=System.currentTimeMillis()+6_000.0)=
        MobileDesktopSuggestionResponse(1,scope.target,scope.threadId,scope.sourceTurnId,true,
            MobileDesktopSuggestion(text,text,MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) },
                Instant.ofEpochMilli(expiry.toLong()).toString(),"native-version"),"native-trace")
    private fun <T> main(block: suspend ()->T): T=runBlocking { withContext(Dispatchers.Main) { block() } }
    private fun gate()=CompletableDeferred<Unit>().also { gates.add(it) }
    private fun ahead(now: ()->Double={ System.currentTimeMillis().toDouble() })=NativeSuggestionReadAhead(tasks,now).also { aheads.add(it) }
    private fun reader()=NativeDesktopSuggestionReader(tasks).also { readers.add(it) }
    private fun await(timeout: Long=6_000,condition: ()->Boolean) {
        val deadline=android.os.SystemClock.elapsedRealtime()+timeout
        while(!condition()&&android.os.SystemClock.elapsedRealtime()<deadline)Thread.sleep(20)
        assertTrue("Native state must reach its expected owned result",condition())
    }
    @Before fun before() {
        require(i.targetContext.packageName=="com.easyai.agenthub.android.acceptance")
        defaultMetadata=NativeSessionStorage(i.targetContext).metadata()?.toString()
        tasks=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    }
    @After fun after() {
        main { gates.forEach { it.complete(Unit) };readers.forEach { it.clear() };aheads.forEach { it.cancel() };tasks.cancel() }
        assertEquals(defaultMetadata,NativeSessionStorage(i.targetContext).metadata()?.toString())
        val run=InstrumentationRegistry.getArguments().getString("readerRun") ?: "reader"
        require(run.matches(Regex("[a-zA-Z0-9_-]{1,80}")))
        File(i.targetContext.filesDir,"acceptance/P5-reader/$run").apply { mkdirs() }.resolve(UUID.randomUUID().toString()+".json")
            .writeText(JSONObject().put("defaultMetadataUnchanged",true).put("newModelInputs",0).put("posts",0)
                .put("actualOwnerVerified",false).put("renderedUiVerified",false).toString())
    }
    @Test fun twoConsumersShareOneReadAndOneCancellationKeepsTheOther() {
        val s=scope();val broker=ahead();val release=gate();val calls=AtomicInteger()
        val fetch: suspend ()->MobileDesktopSuggestionResponse={ calls.incrementAndGet();release.await();response(s) }
        val first=main { tasks.async { broker.read(s,fetch) } };val second=main { tasks.async { broker.read(s,fetch) } }
        await { calls.get()==1 };first.cancel();main { first.join() };assertTrue(second.isActive)
        release.complete(Unit);assertEquals("next",main { second.await() }.suggestion!!.text);assertEquals(1,calls.get())
    }
    @Test fun childProviderCancellationDoesNotEndTheStillCurrentReader() {
        val s=scope();val reader=reader();val calls=AtomicInteger()
        main { reader.reconcile(s,true) {
            if(calls.incrementAndGet()==1)throw CancellationException("Retired shared provider")
            response(s,"recovered-current-reader")
        } }
        await { main { reader.visible(s)?.text=="recovered-current-reader" } }
        assertEquals(2,calls.get());main { reader.clear() };assertNull(main { reader.visible(s) })
    }
    @Test fun heldEditorCallbackCannotRestoreReplacedOrRevisitedTextAndDoesNotDropConsecutiveEdits() {
        var draft=com.agenthub.core.SuggestionDraft("target","", "0");var navigation="first";var writes=0
        val editor=NativeComposerTextOwner(draft,navigation)
        val captured: (String)->Boolean={ text -> editor.input(androidx.compose.ui.text.input.TextFieldValue(text),draft,navigation) {
            writes++;com.agenthub.core.SuggestionDraft("target",it,writes.toString()).also { draft=it }
        } }
        assertTrue(captured("first"));assertTrue(captured("second"));assertEquals("second",draft.text);assertEquals(2,writes)
        draft=com.agenthub.core.SuggestionDraft("target","independent replacement","new-revision")
        assertFalse(captured("late keyboard"));assertEquals("independent replacement",draft.text);assertEquals(2,writes)
        val current=NativeComposerTextOwner(draft,navigation)
        navigation="revisited"
        assertFalse(current.input(androidx.compose.ui.text.input.TextFieldValue("late revisited"),draft,navigation) { error("No stale write") })
    }
    @Test fun retiringAnOldViewOnlyCancelsItsOriginalNavigationPrefetch() {
        val s=scope(navigation="new-navigation");val broker=ahead();val release=gate();val published=AtomicInteger()
        main { broker.prepare(s,{ _,_->published.incrementAndGet() }) { release.await();response(s,"new-prefetch") } }
        main { broker.cancel("old-navigation");release.complete(Unit) }
        await { published.get()==1 };assertEquals("new-prefetch",main { broker.completed(s) }!!.suggestion!!.text)
        main { broker.cancel("new-navigation") };assertNull(main { broker.completed(s) })
    }
    @Test fun failedPrefetchSharesOneFallbackAndDirectFailureHasNoFallback() {
        val s=scope();val broker=ahead();val prepared=gate();val fallback=gate();val pre=AtomicInteger();val normal=AtomicInteger();val overlap=AtomicInteger()
        main { broker.prepare(s,{ _,_->error("Failed prefetch cannot publish") }) { pre.incrementAndGet();prepared.await();throw HostReadError("Prefetch unavailable") } }
        val fetch: suspend ()->MobileDesktopSuggestionResponse={ normal.incrementAndGet();fallback.await();response(s,"fallback") }
        val a=main { tasks.async { broker.read(s,fetch) } };val b=main { tasks.async { broker.read(s,fetch) } }
        prepared.complete(Unit);await { normal.get()==1 }
        main { broker.prepare(s,{ _,_->error("Must keep fallback ownership") }) { overlap.incrementAndGet();response(s) } }
        assertEquals(0,overlap.get());fallback.complete(Unit)
        assertEquals("fallback",main { a.await() }.suggestion!!.text);assertEquals("fallback",main { b.await() }.suggestion!!.text)
        assertEquals(1,pre.get());assertEquals(1,normal.get())
        val direct=AtomicInteger()
        val failure=main { runCatching { broker.read(s) { direct.incrementAndGet();throw HostReadError("Direct failure") } }.exceptionOrNull() }
        assertTrue(failure is HostReadError);assertEquals(1,direct.get())
        main { broker.prepare(s,{ _,_->error("Mismatch cannot publish") }) { throw DesktopSuggestionSourceMismatch() } }
        val mismatch=main { runCatching { broker.read(s) { overlap.incrementAndGet();response(s) } }.exceptionOrNull() }
        assertTrue(mismatch is DesktopSuggestionSourceMismatch);assertEquals(0,overlap.get())
    }
    @Test fun abandonedAndSupersededPrefetchCannotPublishALateNoncancellableResult() {
        val a=scope();val b=scope("b");val broker=ahead();val late=gate();val started=AtomicInteger();val published=mutableListOf<String>()
        main { broker.prepare(a,{ s,_->published.add(s.threadId) }) { started.incrementAndGet();withContext(NonCancellable) { late.await() };response(a) } }
        val one=main { tasks.async { broker.read(a) { error("No fallback") } } };val two=main { tasks.async { broker.read(a) { error("No fallback") } } }
        await { started.get()==1 };one.cancel();two.cancel();main { one.join();two.join() }
        main { broker.prepare(b,{ s,_->published.add(s.threadId) }) { response(b,"current-b") } }
        late.complete(Unit);await { main { published==listOf("b") } }
        assertEquals("current-b",main { broker.completed(b) }!!.suggestion!!.text)
    }
    @Test fun expiredValidPrefetchReadsFreshButInvalidHashIsNotDisguisedAsExpiry() {
        val s=scope();val broker=ahead { 1000.0 };val reads=AtomicInteger()
        main { broker.prepare(s,{ _,_-> }) { response(s,"expired",900.0) } }
        await { main { broker.completed(s)==null } }
        val fresh=main { broker.read(s) { reads.incrementAndGet();response(s,"fresh",2000.0) } }
        assertEquals("fresh",fresh.suggestion!!.text);assertEquals(1,reads.get())
        val bad=response(s,"invalid",900.0).let { it.copy(suggestion=it.suggestion!!.copy(contentSha256="0".repeat(64))) }
        main { broker.prepare(s,{ _,_-> }) { bad } }
        val result=main { broker.read(s) { error("Invalid hash cannot start a fresh-read fallback") } }
        assertFalse(result.suggestion!!.isValid(1000.0))
    }
    @Test fun sameVersionLeaseRenewalStoresNewExpiryAndOverlayCannotRenewAgain() {
        val s=scope();val reader=reader();val calls=AtomicInteger()
        val fetch: suspend ()->MobileDesktopSuggestionResponse={ calls.incrementAndGet();response(s,"same-version",System.currentTimeMillis()+3_000.0) }
        main { reader.reconcile(s,true,fetch) };await { main { reader.visible(s)!=null } }
        val original=main { reader.suggestion!! }
        repeat(10) { main { reader.reconcile(s,true,fetch) } };assertEquals(1,calls.get())
        await { calls.get()>=2&&main { reader.suggestion!!.expirationMillis!!>original.expirationMillis!! } }
        val renewed=main { reader.suggestion!! };assertTrue(original.sameVersion(renewed))
        Thread.sleep(1200);assertNotNull(main { reader.visible(s) })
        main { reader.reconcile(s,false,fetch) };val stopped=calls.get();val expiry=main { reader.suggestion!!.expirationMillis!! }
        val wait=(expiry-System.currentTimeMillis()+150).toLong().coerceAtLeast(0);Thread.sleep(wait)
        await { main { reader.visible(s)==null&&reader.suggestion==null } };assertEquals(stopped,calls.get())
    }
    @Test fun lateResponseCannotReplaceARevisitedScopeOrReturnAfterClear() {
        val old=scope();val newer=scope(navigation="new-navigation");val reader=reader();val late=gate();val entered=AtomicInteger()
        main { reader.reconcile(old,true) { entered.incrementAndGet();withContext(NonCancellable) { late.await() };response(old,"old") } }
        await { entered.get()==1 };main { reader.reconcile(newer,true) { response(newer,"new") } }
        await { main { reader.visible(newer)?.text=="new" } };late.complete(Unit)
        Thread.sleep(100);assertEquals("new",main { reader.visible(newer) }!!.text)
        main { reader.clear() };assertNull(main { reader.suggestion });assertNull(main { reader.scope })
    }
    @Test fun ordinaryReadFailureClearsAndRecoversWithoutChangingScope() {
        val s=scope();val broker=ahead();val reader=reader();val calls=AtomicInteger()
        main { reader.reconcile(s,true) { broker.read(s) {
            when(calls.incrementAndGet()) { 1->response(s,"first");2->throw HostReadError("Read unavailable",transient=true);else->response(s,"recovered") }
        } } }
        await { main { reader.visible(s)?.text=="first" } }
        await { calls.get()==2&&main { reader.suggestion==null } }
        await { main { reader.visible(s)?.text=="recovered" } };assertEquals(3,calls.get())
    }
    @Test fun invalidSourceStopsUntilContextChangesAndCannotRestartOnRecompose() {
        val s=scope();val reader=reader();val calls=AtomicInteger();var valid=false
        val fetch: suspend ()->MobileDesktopSuggestionResponse={ calls.incrementAndGet();if(valid)response(s,"valid")else throw DesktopSuggestionSourceMismatch() }
        main { reader.reconcile(s,true,fetch) };await { calls.get()==1 };Thread.sleep(50)
        repeat(10) { main { reader.reconcile(s,true,fetch) } };assertEquals(1,calls.get());assertNull(main { reader.suggestion })
        main { reader.reconcile(s,false,fetch);valid=true;reader.reconcile(s,true,fetch) }
        await { main { reader.visible(s)?.text=="valid" } };assertEquals(2,calls.get())
    }
    @Test fun emptyContextReconciliationDoesNotPublishAnotherEmptyValue() {
        val reader=reader();val changes=AtomicInteger();val owner=Any()
        val observer=androidx.compose.runtime.snapshots.SnapshotStateObserver { it() }
        try {
            main {
                observer.start();observer.observeReads(owner,{ _: Any -> changes.incrementAndGet() }) { reader.suggestion }
                repeat(10) { reader.reconcile(null,false) { error("Empty source cannot read") } }
                androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
            }
            assertEquals(0,changes.get())
        } finally { main { observer.stop();observer.clear() } }
    }
}
