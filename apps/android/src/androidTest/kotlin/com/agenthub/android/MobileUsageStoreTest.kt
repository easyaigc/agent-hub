package com.agenthub.android

import com.agenthub.core.TargetScope
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.Assert.*

/** Real Store scheduling/projection with a controlled read boundary; never a DEV/model claim. */
class MobileUsageStoreTest {
    private val epoch=1793149200000L
    private fun client(id:String="a")=MobileClient(id,"Usage $id",TargetScope("codex",null,null,id,"workspace","runtime"),"https://fixture.invalid",true,"desktop-runtime")
    private fun response(client:MobileClient,amount:String="7",scope:String="wallet"):String {
        val at="2026-10-28T01:00:00.000Z";val until="2026-10-28T01:01:00.000Z"
        val metric=JSONObject().put("id","balance").put("label","Credits").put("kind","balance")
            .put("observedAt",at).put("validUntil",until).put("appliesTo",JSONObject().put("kind","all"))
            .put("unit",JSONObject().put("kind","credits").put("code","credit").put("label","credits"))
            .put("reading",JSONObject().put("state","amount").put("amount",amount).put("entitlement","finite")).put("expiresAt",JSONObject.NULL)
        return JSONObject().put("schemaVersion",1).put("target",usageTarget(client)).put("requestId","read")
            .put("status","ok").put("snapshot",JSONObject().put("snapshotId","snapshot-$amount").put("sampledAt",at)
                .put("source",JSONObject().put("adapterId","codex").put("scopeRef",scope).put("scopeLabel","Personal").put("planLabel","Pro"))
                .put("coverage","complete").put("issues",JSONArray()).put("buckets",JSONArray().put(JSONObject().put("id","pool").put("label",JSONObject.NULL)
                    .put("sharedPoolRef",JSONObject.NULL).put("combination",JSONObject().put("kind","provider-defined")).put("metrics",JSONArray().put(metric))))).toString()
    }
    private suspend fun until(condition:()->Boolean) { withTimeout(2000) { while(!condition())delay(1) } }
    private fun run(block:suspend CoroutineScope.()->Unit)=runBlocking(Dispatchers.Main.immediate) { withTimeout(5000) { block() } }
    @Test fun forceCannotBypassMinimumOrCreateASecondRead()=run {
        var count=0;val c=client();val store=MobileUsageStore(clock={epoch}) { count++;response(it) }
        store.refresh(c);repeat(4){store.refresh(c,true)}
        assertEquals(1,count);assertEquals("7",store.value(c).getString("balance"))
    }
    @Test fun allStoresShareTwoSlotsAndHiddenQueuedConsumerNeverReads()=run {
        val release=CompletableDeferred<Unit>();val starts=mutableListOf<String>();var active=0;var peak=0
        fun store()=MobileUsageStore(clock={epoch}) { c -> starts.add(c.id);active++;peak=maxOf(peak,active);try {release.await();response(c)}finally {active--} }
        val stores=List(4){store()};val clients=List(4){client(it.toString())}
        var entered=0
        val jobs=MutableList(2){i->launch { entered++;stores[i].refresh(clients[i]) }}
        try {
            until { starts.size==2 }
            jobs.add(launch { entered++;stores[2].refresh(clients[2]) })
            until { entered==3 };assertEquals(2,starts.size)
            jobs[2].cancelAndJoin();release.complete(Unit);jobs.take(2).joinAll()
            stores[3].refresh(clients[3]);assertEquals(setOf("0","1","3"),starts.toSet());assertEquals(2,peak)
        } finally { jobs.forEach {it.cancel()};release.complete(Unit);jobs.joinAll() }
    }
    @Test fun oneHiddenSummaryDoesNotCancelTheFooterOrDetailsSharedRead()=run {
        val release=CompletableDeferred<Unit>();var reads=0;val c=client()
        val store=MobileUsageStore(clock={epoch}) { reads++;release.await();response(it) }
        var entered=0
        val sameTarget=c.copy(scope=TargetScope("codex","legacy-metadata","changed-metadata",c.id,"workspace","runtime"))
        val summary=launch {entered++;store.refresh(c)};val footer=launch {entered++;store.refresh(sameTarget)};val details=launch {entered++;store.refresh(c,true)}
        try { until {reads==1 && entered==3};assertEquals(1,reads);summary.cancelAndJoin();assertTrue(footer.isActive);release.complete(Unit);joinAll(footer,details);assertEquals("7",store.value(c).getString("balance")) }
        finally {listOf(summary,footer,details).forEach {it.cancel()};release.complete(Unit)}
    }
    @Test fun cancelledOldCallbackCannotOverwriteNewWalletSample()=run {
        val late=CompletableDeferred<Unit>();var now=epoch;var count=0;val c=client()
        val store=MobileUsageStore(clock={now}) {
            count++;if(count==1)withContext(NonCancellable){late.await();response(it,"1")}else response(it,"9","new-wallet")
        }
        val old=launch {store.refresh(c)}
        try { until {count==1};old.cancelAndJoin();now+=5000;store.refresh(c,true);assertEquals("9",store.value(c).getString("balance"));late.complete(Unit);yield();assertEquals("9",store.value(c).getString("balance")) }
        finally {old.cancel();late.complete(Unit)}
    }
    @Test fun httpDenialAndBareOfflineWithdrawOnlyUsageValues()=run {
        val c=client();val original=c.copy();var now=epoch;var failure:Exception?=null
        val store=MobileUsageStore(clock={now}) { failure?.let {throw it};response(it) }
        for((error,reason) in listOf(CodexCommandError("401",401) to "http-unauthenticated",CodexCommandError("403",403) to "http-forbidden",java.io.IOException("offline") to "source-unconfirmed")) {
            failure=null;now+=5000;store.refresh(c);assertEquals("7",store.value(c).getString("balance"))
            failure=error;now+=5000;store.refresh(c,true);assertFalse(store.value(c).has("balance"));assertEquals(reason,store.value(c).getString("reason"));assertEquals(original,c)
        }
    }
    @Test fun oneForbiddenMetricKeepsItsValidSiblingAndExpirationNeverInventsZero()=run {
        var now=epoch;val c=client()
        val store=MobileUsageStore(clock={now}) {
            val wire=JSONObject(response(it));val snapshot=wire.getJSONObject("snapshot");snapshot.put("coverage","partial")
            val metrics=snapshot.getJSONArray("buckets").getJSONObject(0).getJSONArray("metrics")
            val unavailable=JSONObject(metrics.getJSONObject(0).toString()).put("id","other").put("reading",JSONObject().put("state","unavailable").put("reason","forbidden"))
            metrics.put(unavailable);wire.toString()
        }
        store.refresh(c);assertEquals("7",store.value(c).getString("balance"));assertTrue(store.value(c).getBoolean("partial"));assertEquals("forbidden",store.value(c).getJSONArray("metrics").getJSONObject(1).getString("value"))
        now+=120000;assertTrue(store.cached(c));assertEquals("7",store.value(c).getString("balance"))
        assertFalse(store.value(client("other-target")).has("balance"))
    }
    @Test fun wrongResponseTargetWithdrawsAndNewSourceNeverMergesOldBalance()=run {
        var now=epoch;var phase=0;val c=client()
        val store=MobileUsageStore(clock={now}) { when(phase) {
            0->response(it,"7","old-wallet")
            1->response(client("wrong-target"),"8","old-wallet")
            else->response(it,"9","new-wallet")
        } }
        store.refresh(c);phase=1;now+=5000;store.refresh(c,true)
        assertEquals("wrong-target",store.value(c).getString("reason"));assertFalse(store.value(c).has("metrics"))
        phase=2;now+=5000;store.refresh(c,true)
        assertEquals("9",store.value(c).getString("balance"));assertEquals("new-wallet",store.value(c).getJSONObject("source").getString("scopeRef"))
    }

    @Test fun nonNullCodexPairRebuildSharesFlightCacheThrottleAndTrustChangesIsolate()=run {
        val c=client();val release=CompletableDeferred<Unit>();var count=0
        fun scope(display:String)=TargetScope("codex","legacy-display",display,c.id,"workspace","runtime")
        fun paired(display:String)=PairedCodexConnection(scope(display),"a".repeat(64),listOf(CodexRoute("https://fixture.invalid","lan")),listOf("https://system.fixture.invalid"))
        val first=c.copy(scope=scope("old"),codexDirect=paired("old"))
        val rebuilt=c.copy(scope=scope("new"),codexDirect=paired("new"))
        assertNotSame(first.codexDirect!!.target,rebuilt.codexDirect!!.target)
        assertEquals(first.codexDirect,rebuilt.codexDirect)
        val store=MobileUsageStore(clock={epoch}) { count++;release.await();response(it) }
        var entered=0;val jobs=listOf(launch{entered++;store.refresh(first)},launch{entered++;store.refresh(rebuilt,true)})
        try {
            until {entered==2 && count>=1};assertEquals("Rebuilt paired target must join the same flight",1,count)
            release.complete(Unit);jobs.joinAll();assertEquals("7",store.value(rebuilt).getString("balance"))
            store.refresh(rebuilt,true);assertEquals(1,count)
            val changes=listOf(first.copy(codexDirect=paired("old").copy(fingerprint="b".repeat(64))),
                first.copy(codexDirect=paired("old").copy(routes=listOf(CodexRoute("https://other.fixture.invalid","vpn")))),
                first.copy(codexDirect=paired("old").copy(systemEndpoints=listOf("https://other-system.fixture.invalid"))))
            for((index,changed) in changes.withIndex()) {
                assertFalse(store.value(changed).has("balance"));store.refresh(changed,true);assertEquals(index+2,count)
            }
            assertEquals("old",first.codexDirect.target.accountSubject)
            assertEquals("new",rebuilt.codexDirect.target.accountSubject)
        } finally {jobs.forEach {it.cancel()};release.complete(Unit);jobs.joinAll()}
    }
    @Test fun nonNullEasyWorkRebuildSharesFlightCacheThrottleAndAccountCredentialPeerIsolate()=run {
        fun scope(subject:String)=TargetScope("easywork","https://fixture.invalid",subject,"a","workspace","runtime")
        val original=AgentConnection(scope("owner-a"),listOf(AgentCandidate("lan","lan-wss","wss://fixture.invalid/remote","a".repeat(64),"lan")),"ref-a")
        val restored=AgentConnection.decode(JSONObject(original.json().toString()))
        assertNotSame(original.target,restored.target);assertEquals(original,restored)
        val first=client().copy(scope=scope("owner-a"),agentConnection=original)
        val rebuilt=client().copy(scope=scope("owner-a"),agentConnection=restored)
        val release=CompletableDeferred<Unit>();var count=0
        val store=MobileUsageStore(clock={epoch}){count++;release.await();response(it)}
        var entered=0;val jobs=listOf(launch{entered++;store.refresh(first)},launch{entered++;store.refresh(rebuilt,true)})
        try {
            until {entered==2 && count>=1};assertEquals("Rebuilt persisted connection must join the same flight",1,count)
            release.complete(Unit);jobs.joinAll();assertEquals("7",store.value(rebuilt).getString("balance"));store.refresh(rebuilt,true);assertEquals(1,count)
            val changes=listOf(first.copy(agentConnection=original.copy(credentialRef="ref-b")),
                first.copy(scope=scope("owner-b"),agentConnection=original.copy(target=scope("owner-b"))),
                first.copy(agentConnection=original.copy(candidates=original.candidates.map {it.copy(peerFingerprint="b".repeat(64))})))
            for((index,changed) in changes.withIndex()) {assertFalse(store.value(changed).has("balance"));store.refresh(changed,true);assertEquals(index+2,count)}
            assertEquals("owner-a",original.target.accountSubject);assertEquals("ref-a",original.credentialRef)
        } finally {jobs.forEach {it.cancel()};release.complete(Unit);jobs.joinAll()}
    }

    @Test fun sharedLoadingInvalidatesObserversBeforeAndAfterRead()=run {
        val c=client();val release=CompletableDeferred<Unit>()
        val store=MobileUsageStore(clock={epoch}) {release.await();response(it)}
        var changes=0
        val observer=androidx.compose.runtime.snapshots.SnapshotStateObserver {it()}
        val owner=Any();val onChanged:(Any)->Unit={changes++}
        fun observe() { observer.observeReads(owner,onChanged) {store.refreshing(c)} }
        observer.start()
        val job=launch(start=CoroutineStart.LAZY){store.refresh(c)}
        try {
            observe();assertFalse(store.refreshing(c));job.start()
            until {store.refreshing(c)}
            androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
            assertTrue("An automatic/shared read must invalidate a visible refresh button",changes>0)
            changes=0;observe();release.complete(Unit);job.join()
            until {!store.refreshing(c)}
            androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
            assertTrue("Completion must re-enable all visible refresh consumers",changes>0)
        } finally {release.complete(Unit);job.cancelAndJoin();observer.stop();observer.clear()}
    }

    @Test fun threeConsumersShareLoadingAndExactFiveSecondRefreshBoundary()=run {
        val c=client();var now=epoch;var count=0;val release=CompletableDeferred<Unit>()
        val store=MobileUsageStore(clock={now}) {count++;if(count==1)release.await();response(it)}
        var entered=0
        val jobs=listOf(launch {entered++;store.refresh(c)},launch {entered++;store.refresh(c)},launch {entered++;store.refresh(c,true)})
        try {
            until {entered==3 && count==1}
            assertTrue(store.refreshing(c));assertFalse(store.value(c).has("balance"))
            release.complete(Unit);jobs.joinAll();until {!store.refreshing(c)}
            assertEquals(1,count);assertEquals("7",store.value(c).getString("balance"))
            now=epoch+4999;store.refresh(c,true);assertEquals(1,count)
            now=epoch+5000;store.refresh(c,true);assertEquals(2,count)
        } finally {release.complete(Unit);jobs.forEach {it.cancelAndJoin()}}
    }

    @Test fun lateOtherRuntimeWithSameDisplayIdCannotChangeSelectedRuntimeValueOrLoading()=run {
        val a=client();val scope=a.scope!!
        val b=a.copy(scope=TargetScope(scope.agentId,scope.accountAuthority,scope.accountSubject,scope.hostId,scope.workspaceId,"new-runtime"))
        val release=CompletableDeferred<Unit>();var oldStarted=false
        val store=MobileUsageStore(clock={epoch}) {
            if(it.scope!!.runtimeId==a.scope!!.runtimeId) {oldStarted=true;release.await();response(it,"1")}
            else response(it,"9","new-wallet")
        }
        val old=launch {store.refresh(a)}
        try {
            until {oldStarted};assertTrue(store.refreshing(a));assertFalse(store.refreshing(b))
            assertFalse(store.value(b).has("balance"));store.refresh(b)
            assertEquals("9",store.value(b).getString("balance"));assertFalse(store.refreshing(b))
            release.complete(Unit);old.join()
            assertEquals("1",store.value(a).getString("balance"));assertEquals("9",store.value(b).getString("balance"))
            assertEquals("new-wallet",store.value(b).getJSONObject("source").getString("scopeRef"))
        } finally {release.complete(Unit);old.cancelAndJoin()}
    }

    @Test fun partialRefreshKeepsValidSiblingWithReasonButUnattestedWholeFailureWithdraws()=run {
        val c=client();var now=epoch;var phase=0
        val store=MobileUsageStore(clock={now}) {
            if(phase==2)throw java.io.IOException("synthetic offline")
            val wire=JSONObject(response(it));val snapshot=wire.getJSONObject("snapshot")
            val metrics=snapshot.getJSONArray("buckets").getJSONObject(0).getJSONArray("metrics")
            val sibling=JSONObject(metrics.getJSONObject(0).toString()).put("id","secondary")
            if(phase==1) {
                snapshot.put("coverage","partial")
                sibling.put("reading",JSONObject().put("state","unavailable").put("reason","forbidden"))
            }
            metrics.put(sibling);wire.toString()
        }
        store.refresh(c);assertFalse(store.value(c).getBoolean("partial"))
        phase=1;now+=5000;store.refresh(c,true)
        val partial=store.value(c)
        assertTrue(partial.getBoolean("partial"));assertEquals("7",partial.getString("balance"))
        assertEquals("forbidden",partial.getJSONArray("metrics").getJSONObject(1).getString("value"))
        phase=2;now+=5000;store.refresh(c,true)
        assertEquals("source-unconfirmed",store.value(c).getString("reason"))
        assertEquals("offline",store.value(c).getString("failureReason"))
        assertFalse(store.value(c).has("balance"));assertFalse(store.value(c).has("metrics"))
    }

}
