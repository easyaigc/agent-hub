package com.agenthub.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.agenthub.core.TargetScope
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Production coordinator/decoder ports with synthetic bytes only. No Activity, Session,
 * SourceStore, Keystore, HTTP, model or default-profile access. These are auxiliary tests;
 * they do not establish that a real Host/Agent supports the advertised contract.
 */
@RunWith(AndroidJUnit4::class)
class NativeUploadCenterBoundaryTest {
    private class Journal : NativeUploadJournal {
        @Volatile var value:String?=null
        var failWrites=false
        @Volatile var beforeWrite:((String)->Unit)?=null
        override fun requireNetworkOwner() {} // Synthetic in-memory owner only.
        override fun read()=value
        override fun write(value:String) {
            if(failWrites)throw IOException("synthetic journal fault")
            beforeWrite?.invoke(value)
            this.value=value
        }
    }
    private class Fixture {
        val bytes="ABCDEF".toByteArray()
        val original=bytes.copyOf()
        val client=MobileClient("synthetic-host","Synthetic Host",
            TargetScope("codex",null,null,"synthetic-host","synthetic-workspace","desktop-runtime"),"https://synthetic.invalid",isDemo=false)
        val manifest=NativeUploadManifest(ComputerFileManifest(client.id,"47e4fbc2-3fd3-4a34-b64b-bf0941cd4166",
            "sample.bin","application/octet-stream",bytes.size.toLong(),ComputerFileCodec.hash(bytes)),"file-transfer")
        val journal=Journal()
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
        var authorized=true
        var committed=0L
        var phase="uploading"
        var firstChunk:CompletableDeferred<Unit>?=null
        var releaseChunk:CompletableDeferred<Unit>?=null
        var loseComplete=false
        var resolveCount=0
        val actions=mutableListOf<Pair<NativeUploadAction,Long>>()
        fun receipt():NativeUploadReceipt {
            val result=if(phase=="completed")ComputerFileReceipt(manifest.file,"completed","synthetic-destination","2026-01-01T00:00:00Z").json() else JSONObject.NULL
            return NativeUploadReceipt.decode(JSONObject().put("version",1).put("manifest",manifest.json()).put("phase",phase)
                .put("committedBytes",committed).put("maxChunkBytes",2).put("createdAt","2026-01-01T00:00:00Z")
                .put("updatedAt","2026-01-01T00:00:00Z").put("result",result),manifest)
        }
        val port=object:AgentCommands by NativeCodexCommands({error("Unexpected credential read")}) {
            override suspend fun uploadCapability(client:MobileClient,manifest:NativeUploadManifest)=NativeUploadCapability(2,true,"codex-upload-v1")
            override suspend fun uploadRequest(client:MobileClient,manifest:NativeUploadManifest,action:NativeUploadAction,offset:Long,bytes:ByteArray?):NativeUploadReceipt {
                assertEquals(this@Fixture.manifest,manifest)
                actions.add(action to offset)
                if(action!=NativeUploadAction.STATUS) {
                    val row=JSONObject(checkNotNull(journal.value)).getJSONArray("jobs").objects()
                        .single { it.getString("key")==this@Fixture.key }
                    assertEquals(action.name.lowercase(),row.getString("pending")) // Durable exact intent precedes wire dispatch.
                }
                when(action) {
                    NativeUploadAction.CHUNK->{
                        assertEquals(committed,offset)
                        assertArrayEquals(this@Fixture.bytes.copyOfRange(offset.toInt(),offset.toInt()+checkNotNull(bytes).size),bytes)
                        if(offset==0L&&releaseChunk!=null)withContext(NonCancellable) {
                            firstChunk?.complete(Unit);releaseChunk!!.await();committed=2
                        } else committed+=checkNotNull(bytes).size
                    }
                    NativeUploadAction.COMPLETE->{if(loseComplete)throw IOException("synthetic lost reply");phase="completed"}
                    NativeUploadAction.CANCEL->phase="cancelled"
                    else->Unit
                }
                return receipt()
            }
        }
        val center=NativeUploadCenter(journal,scope,{if(it.matches(client))client else null},{
            val captured=authorized;val current:()->Boolean={captured&&authorized};current
        },{_,_->resolveCount++;val reader:(Long,Int)->ByteArray={offset,n->bytes.copyOfRange(offset.toInt(),offset.toInt()+n)};reader},{port})
        val key=NativeUploadRow.key(NativeUploadTarget.capture(client),manifest)
        suspend fun begin():Deferred<JSONObject> = scope.async {
            center.transfer(client,manifest,null,{offset,n->bytes.copyOfRange(offset.toInt(),offset.toInt()+n)},port) {error("Unexpected legacy upload")}
        }
        suspend fun until(predicate:()->Boolean)=withTimeout(5_000) {while(!predicate())delay(10)}
        suspend fun close() {scope.coroutineContext[Job]!!.cancelAndJoin();assertArrayEquals(original,bytes)}
    }
    @Test fun pauseLateAckThenResumeQueriesSameIdAndUsesConfirmedOffset()=runBlocking {
        withTimeout(15_000) {withContext(Dispatchers.Main.immediate) {
            val f=Fixture()
            try {
                f.firstChunk=CompletableDeferred();f.releaseChunk=CompletableDeferred()
                val result=f.begin();f.firstChunk!!.await()
                f.center.control(f.key,"pause");f.until {f.center.rows[f.key]?.paused==true}
                f.releaseChunk!!.complete(Unit)
                delay(60)
                assertEquals(0L,f.center.rows.getValue(f.key).committed) // Retired child reply is never presentation authority.
                assertFalse(result.isCompleted)
                f.center.control(f.key,"resume")
                result.await()
                assertEquals(listOf(0L,2L,4L),f.actions.filter {it.first==NativeUploadAction.CHUNK}.map {it.second})
                val firstChunk=f.actions.indexOfFirst {it.first==NativeUploadAction.CHUNK}
                assertEquals(NativeUploadAction.STATUS,f.actions[firstChunk+1].first)
                assertEquals(1,f.actions.count {it.first==NativeUploadAction.PREPARE})
                assertEquals(1,f.actions.count {it.first==NativeUploadAction.COMPLETE})
                assertEquals("completed",f.center.rows.getValue(f.key).phase)
            } finally {f.close()}
        }}
    }
    @Test fun unknownCompleteReadOnlyQueryDoesNotReplayPublicationOrBusiness()=runBlocking {
        withTimeout(15_000) {withContext(Dispatchers.Main.immediate) {
            val f=Fixture();f.loseComplete=true
            try {
                val result=f.begin();f.until {f.center.rows[f.key]?.phase=="waiting"}
                val before=f.actions.size
                f.center.control(f.key,"query");f.until {f.center.rows[f.key]?.paused==true}
                assertEquals(listOf(NativeUploadAction.STATUS),f.actions.drop(before).map {it.first})
                assertEquals("complete",f.center.rows.getValue(f.key).pending)
                assertFalse(result.isCompleted)
                f.phase="completed" // Same original server receipt becomes available, without another completion request.
                f.center.control(f.key,"query");result.await()
                assertEquals(1,f.actions.count {it.first==NativeUploadAction.COMPLETE})
                assertEquals(0,f.actions.count {it.first==NativeUploadAction.CANCEL})
            } finally {f.close()}
        }}
    }
    @Test fun failedDurableIntentMakesZeroRemoteCallsAndKeepsOriginalBytes()=runBlocking {
        withTimeout(15_000) {withContext(Dispatchers.Main.immediate) {
            val f=Fixture();f.journal.failWrites=true
            try {
                val result=f.begin();val outcome=runCatching {result.await()}
                assertTrue(outcome.isFailure);assertTrue(f.actions.isEmpty());assertNull(f.journal.value)
                assertNotNull(f.center.storageError)
            } finally {f.close()}
        }}
    }
    @Test fun retiredTargetCannotPublishLateChunkOrContinueDispatch()=runBlocking {
        withTimeout(15_000) {withContext(Dispatchers.Main.immediate) {
            val f=Fixture()
            try {
                f.firstChunk=CompletableDeferred();f.releaseChunk=CompletableDeferred()
                val result=f.begin();f.firstChunk!!.await()
                f.authorized=false;f.releaseChunk!!.complete(Unit)
                assertTrue(runCatching {result.await()}.isFailure)
                assertEquals(0L,f.center.rows.getValue(f.key).committed)
                assertEquals(1,f.actions.count {it.first==NativeUploadAction.CHUNK})
                assertEquals(0,f.actions.count {it.first==NativeUploadAction.COMPLETE})
            } finally {f.close()}
        }}
    }
    @Test fun restoredQueryDoesNotReadSourceOrRestartOriginalSend()=runBlocking {
        withTimeout(15_000) {withContext(Dispatchers.Main.immediate) {
            val f=Fixture()
            try {
                val row=NativeUploadRow(f.key,NativeUploadTarget.capture(f.client),f.manifest,null,pending="chunk",
                    capability=NativeUploadCapability(2,true,"codex-upload-v1"))
                f.journal.value=JSONObject().put("version",1).put("jobs",org.json.JSONArray().put(row.json())).toString()
                f.center.restore();assertTrue(f.actions.isEmpty())
                f.committed=f.bytes.size.toLong();f.phase="completed"
                f.center.control(f.key,"query");f.until {f.center.rows[f.key]?.phase=="completed"}
                assertEquals(listOf(NativeUploadAction.STATUS),f.actions.map {it.first})
                assertEquals(0,f.resolveCount)
            } finally {f.close()}
        }}
    }

    private fun completedReceipt(manifest:NativeUploadManifest):NativeUploadReceipt = NativeUploadReceipt.decode(
        JSONObject().put("version",1).put("manifest",manifest.json()).put("phase","completed")
            .put("committedBytes",manifest.file.sizeBytes).put("maxChunkBytes",2)
            .put("createdAt","2026-01-01T00:00:00Z").put("updatedAt","2026-01-01T00:00:00Z")
            .put("result",ComputerFileReceipt(manifest.file,"completed","synthetic-destination","2026-01-01T00:00:00Z").json()),manifest)

    @Test fun legacyWaitingBehindTwoSlotsCannotDispatchAfterOriginExpires()=runBlocking {
        withTimeout(15_000) {withContext(Dispatchers.Main.immediate) {
            val f=Fixture()
            val releaseFirst=CompletableDeferred<Unit>();val releaseSecond=CompletableDeferred<Unit>()
            try {
                val first=f.manifest.copy(file=f.manifest.file.copy(id=UUID.randomUUID().toString()))
                val second=f.manifest.copy(file=f.manifest.file.copy(id=UUID.randomUUID().toString()))
                val legacy=f.manifest.copy(file=f.manifest.file.copy(id=UUID.randomUUID().toString()))
                val bothHeld=CompletableDeferred<Unit>();val originChecked=CompletableDeferred<Unit>()
                var holders=0;var maxHolders=0;var legacyCalls=0;var originValid=true
                val port=object:AgentCommands by f.port {
                    override suspend fun uploadCapability(client:MobileClient,manifest:NativeUploadManifest):NativeUploadCapability? =
                        if(manifest.id==legacy.id)null else NativeUploadCapability(2,false,"codex-upload-v1")
                    override suspend fun uploadRequest(client:MobileClient,manifest:NativeUploadManifest,action:NativeUploadAction,offset:Long,bytes:ByteArray?):NativeUploadReceipt {
                        assertEquals(NativeUploadAction.PREPARE,action)
                        assertTrue(manifest==first||manifest==second)
                        holders++;maxHolders=maxOf(maxHolders,holders)
                        try {
                            if(holders==2)bothHeld.complete(Unit)
                            (if(manifest==first)releaseFirst else releaseSecond).await()
                            return completedReceipt(manifest)
                        } finally {holders--}
                    }
                }
                fun begin(m:NativeUploadManifest)=f.scope.async {
                    f.center.transfer(f.client,m,null,{offset,n->f.bytes.copyOfRange(offset.toInt(),offset.toInt()+n)},port) {error("Unexpected blocker fallback")}
                }
                val firstResult=begin(first);val secondResult=begin(second)
                bothHeld.await();assertEquals(2,holders)
                val result=f.scope.async(NativeUploadOrigin(UUID.randomUUID().toString(),0,1,null,{
                    val valid=originValid;originChecked.complete(Unit);valid
                })) {
                    f.center.transfer(f.client,legacy,null,{_,_->error("Legacy path must not read a range")},port) {
                        legacyCalls++
                        ComputerFileReceipt(legacy.file,"completed","synthetic-destination","2026-01-01T00:00:00Z").json()
                    }
                }
                originChecked.await()
                // All coroutines here use Main.immediate. Let the current legacy check return
                // and reach its first suspension: the two already held semaphore permits.
                yield()
                assertEquals(2,holders);assertEquals(0,legacyCalls);assertFalse(result.isCompleted)
                originValid=false // Keep target lease and caller alive; retire ONLY the input guard.
                assertTrue(f.authorized);assertTrue(result.isActive)
                releaseFirst.complete(Unit)
                val rejected=withTimeout(5_000) {runCatching {result.await()}}
                assertTrue(rejected.isFailure)
                assertTrue(rejected.exceptionOrNull() is IllegalStateException)
                assertEquals("No post-dispatch rejection may substitute for this zero count",0,legacyCalls)
                val key=NativeUploadRow.key(NativeUploadTarget.capture(f.client),legacy)
                val kept=f.center.rows.getValue(key)
                assertTrue(kept.legacy);assertEquals("unknown",kept.phase);assertNull(kept.legacyResultWire)
                releaseSecond.complete(Unit);firstResult.await();secondResult.await()
                assertEquals(2,maxHolders);assertEquals(0,holders)
            } finally {
                releaseFirst.complete(Unit);releaseSecond.complete(Unit);f.close()
            }
        }}
    }

    @Test fun pendingIntentWriteCannotDispatchPrepareAfterOnlyOriginExpires()=runBlocking {
        withTimeout(15_000) {withContext(Dispatchers.Main.immediate) {
            val f=Fixture();val entered=CountDownLatch(1);val release=CountDownLatch(1)
            var originValid=true
            try {
                f.journal.beforeWrite={value->
                    val jobs=JSONObject(value).getJSONArray("jobs")
                    if(entered.count>0&&jobs.objects().any {it.optional("pending")=="prepare"}) {
                        entered.countDown()
                        if(!release.await(5,TimeUnit.SECONDS))throw IOException("Synthetic intent barrier timed out")
                    }
                }
                val result=f.scope.async(NativeUploadOrigin(UUID.randomUUID().toString(),0,1,null,{originValid})) {
                    f.center.transfer(f.client,f.manifest,null,{offset,n->f.bytes.copyOfRange(offset.toInt(),offset.toInt()+n)},f.port) {error("Unexpected legacy upload")}
                }
                f.until {entered.count==0L}
                assertTrue(f.authorized);assertTrue(result.isActive);assertTrue(f.actions.isEmpty())
                originValid=false;release.countDown()
                f.until {f.center.rows[f.key]?.error!=null||result.isCompleted}
                assertTrue("Only origin expired; target lease stays live",f.authorized)
                assertEquals("prepare",f.center.rows.getValue(f.key).pending)
                val saved=JSONObject(checkNotNull(f.journal.value)).getJSONArray("jobs").getJSONObject(0)
                assertEquals(f.manifest,NativeUploadManifest.decode(saved.getJSONObject("manifest")))
                assertEquals("prepare",saved.getString("pending"))
                assertEquals(0,f.actions.count {it.first==NativeUploadAction.PREPARE})
                assertTrue(f.actions.isEmpty())
            } finally {release.countDown();f.journal.beforeWrite=null;f.close()}
        }}
    }

    @Test fun removingConfirmedCancelledCardFreesCapacityButUnknownIsImmutable()=runBlocking {
        withTimeout(15_000) {withContext(Dispatchers.Main.immediate) {
            val f=Fixture();val g=Fixture()
            try {
                val target=NativeUploadTarget.capture(f.client)
                val cancelled=(0 until 32).map {
                    val m=f.manifest.copy(file=f.manifest.file.copy(id=UUID.randomUUID().toString()))
                    val receipt=NativeUploadReceipt.decode(JSONObject(completedReceipt(m).wire)
                        .put("phase","cancelled").put("reason","expired").put("result",JSONObject.NULL),m)
                    // F59 remoteCancel remains false. This synthetic terminal models a verified
                    // server expiry, never a fabricated successful mobile cancel operation.
                    NativeUploadRow(NativeUploadRow.key(target,m),target,m,null,phase="cancelled",receiptWire=receipt.wire,
                        capability=NativeUploadCapability(2,false,"codex-upload-v1"))
                }
                f.journal.value=NativeUploadJournalCodec.encode(cancelled)
                f.center.restore();assertEquals(32,f.center.rows.size);assertTrue(f.actions.isEmpty())
                val dropped=cancelled.first()
                f.center.dismissTerminal(dropped.key)
                f.until {f.center.rows.size==31}
                val result=f.begin();result.await()
                assertEquals("completed",f.center.rows.getValue(f.key).phase)
                assertEquals(32,f.center.rows.size);assertFalse(f.center.rows.containsKey(dropped.key))
                cancelled.drop(1).forEach { old ->
                    val actual=f.center.rows.getValue(old.key)
                    assertEquals(old.target.json().toString(),actual.target.json().toString())
                    assertEquals(old.copy(target=actual.target),actual)
                }
                assertEquals(1,f.actions.count {it.first==NativeUploadAction.PREPARE})
                val persisted=NativeUploadJournalCodec.decode(checkNotNull(f.journal.value))
                assertEquals(32,persisted.size);assertTrue(persisted.none {it.key==dropped.key})
                cancelled.drop(1).forEach { old ->
                    val actual=persisted.single {it.key==old.key}
                    assertEquals(old.target.json().toString(),actual.target.json().toString())
                    assertEquals(old.copy(target=actual.target),actual)
                }

                val unknown=NativeUploadRow(g.key,NativeUploadTarget.capture(g.client),g.manifest,null,
                    phase="unknown",pending="chunk",capability=NativeUploadCapability(2,false,"codex-upload-v1"))
                g.journal.value=NativeUploadJournalCodec.encode(listOf(unknown));g.center.restore()
                val beforeRow=g.center.rows.getValue(g.key);val beforeWire=g.journal.value
                g.center.dismissTerminal(g.key);yield()
                assertEquals(beforeRow,g.center.rows.getValue(g.key));assertEquals(beforeWire,g.journal.value)
                assertEquals("chunk",g.center.rows.getValue(g.key).pending);assertTrue(g.actions.isEmpty())
            } finally {f.close();g.close()}
        }}
    }
}
