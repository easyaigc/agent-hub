package com.agenthub.android

import androidx.test.platform.app.InstrumentationRegistry
import com.agenthub.core.TargetScope
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import java.time.Instant
import java.util.UUID

/** Compiled native auxiliary scenarios use real isolated Keystore/AtomicFile; execution is parent-lease only. */

class NativeChatNotificationRecoveryTest {
    @get:Rule val timeout: org.junit.rules.TestRule = org.junit.rules.Timeout.seconds(90)
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var namespace: String
    private lateinit var f: RecoveryNativeFixture
    @Before fun prepare() = runBlocking {
        require(context.packageName == "com.easyai.agenthub.android.acceptance")
        namespace = "P9-recovery-v2-${UUID.randomUUID()}"
        f = withContext(Dispatchers.IO) { RecoveryNativeFixture(context, namespace) }
    }
    @After fun cleanup() { if (::namespace.isInitialized) java.io.File(context.noBackupFilesDir, namespace).deleteRecursively() }
    private fun scenario(action: suspend () -> Unit) = runBlocking { withContext(Dispatchers.IO) { action() } }
    @Test fun frozenCrossLanguageCanonicalVectors() = scenario {
        val vectors = JSONObject(InstrumentationRegistry.getInstrumentation().context.assets.open("notification-recovery-v2.json").bufferedReader().use { it.readText() }).getJSONArray("vectors")
        for (index in 0 until vectors.length()) { val v=vectors.getJSONObject(index); check(NativeChatRecoveryIntent.parse(v.getJSONObject("intent")).fingerprint==v.getString("expectedFingerprint")) }
    }
    @Test fun lostAckColdRestartQueriesOriginalOnly() = scenario {
        f.loseAck=true; f.sync(); val original=f.submitted.single(); f.loseAck=false; f.sync()
        check(f.submitted.size==1 && f.queried.all{it.encoded()==original.encoded()})
        val reopened=NativeSessionStorage(context,namespace); val view=NativeChatNotificationRecoveryStore(reopened).view(NativeChatNotificationIdentity.load(reopened),f.target)
        check(view.operations.single().state=="applied")
    }
    @Test fun journalIdentityAndOriginalRemainEncrypted() = scenario {
        f.loseAck=true; f.sync(); val original=f.submitted.single()
        val bytes=java.io.File(context.noBackupFilesDir,namespace).walkTopDown().filter{it.isFile}.map{it.readBytes().toString(Charsets.ISO_8859_1)}.toList()
        check(bytes.isNotEmpty() && bytes.none{it.contains(original.operationId)||it.contains(original.publicKey)||it.contains("fixture-token")})
    }
    @Test fun absentDoesNotAuthorizeNewWrite() = scenario {
        f.loseAck=true; f.saveReceipt=false; f.sync(); f.notices.update{it.copy(soundEnabled=false)}
        repeat(2){f.sync()}; check(f.submitted.size==1 && f.view().operations.single().state=="unknown")
    }
    @Test fun separateOffPreservesUnknownV1Record() = scenario {
        f.legacy.beginMutation(f.identity(),f.snapshot(),"register",f.notices.preferences(),f.now)
        val raw=f.storage.read(NativeChatNotificationForegroundStore.RECORD); f.notices.update{it.copy(enabled=false)}
        check(f.off().state=="disabled" && f.storage.read(NativeChatNotificationForegroundStore.RECORD)==raw)
    }
    @Test fun duplicateHumanOffUsesOneOriginalId() = scenario {
        f.notices.update{it.copy(enabled=false)}; val id=UUID.randomUUID().toString(); f.off(action=id); f.off(action=id)
        check(f.submitted.size==1 && f.queried.single().operationId==id)
    }
    @Test fun oldCredentialAndNewEpochCannotResubmit() = scenario {
        f.loseAck=true; f.sync(); check(f.sync(snapshot=f.snapshot(credentialEpoch="rotated"))?.state=="original-credential-recovery-required")
        f.epoch=UUID.randomUUID().toString(); check(f.sync()?.state=="epoch-recovery-required" && f.submitted.size==1)
    }
    @Test fun actionableStaleWaitsWithoutAdvancing() = scenario {
        val e=f.event();f.events.add(e);f.disposition[e.id]="stale";f.sync()
        check(f.view().after==0L && f.view().outcomes.single().state=="waiting-host" && f.delivered.isEmpty())
    }
    @Test fun restoredOwnerRefreshesOnceWithFreshActiveSet() = scenario {
        val e=f.event();f.events.add(e);f.disposition[e.id]="stale";f.sync();f.disposition[e.id]="eligible";f.active=setOf(e.id);f.sync();f.sync()
        check(f.delivered==listOf(e.id)&&f.activeSets.first()==setOf(e.id))
    }
    @Test fun filteredOriginalStillUsesP7Port() = scenario {
        val e=f.event();f.events.add(e);f.disposition[e.id]="filtered";f.sync();check(f.delivered==listOf(e.id))
    }
    @Test fun interruptedPortOutcomeIsNeverReplayed() = scenario {
        f.events.add(f.event());f.failReceive=true;check(runCatching{f.sync()}.isFailure);f.failReceive=false;f.sync()
        check(f.delivered.size==1&&f.view().outcomes.single().state=="unknown-interrupted-no-replay")
    }
    @Test fun paginationKeepsCapturedHighWater() = scenario {
        f.pageSize=1;f.events.addAll(listOf(f.event(1),f.event(2)));f.sync();f.events.add(f.event(3));f.sync()
        check(f.queries.last().through==2L&&f.view().after==2L)
    }
    @Test fun acceptedGapPagedResetKeepsOldCursorUntilComplete() = scenario {
        f.events.addAll(listOf(f.event(1),f.event(2)));f.pageSize=1;f.gap=true;val offer=requireNotNull(f.sync()?.gap)
        val action=UUID.randomUUID().toString();f.controller().acceptGap(f.snapshot(),offer,action){true};f.sync()
        check(f.view().after==0L&&f.view().resetAfter==1L);f.sync();check(f.view().after==2L&&f.view().resetId==null)
    }
    @Test fun snapshotHashMismatchDoesNotPublishBaseline() = scenario {
        f.events.add(f.event());f.gap=true;f.badSnapshotHash=true;val offer=requireNotNull(f.sync()?.gap)
        f.controller().acceptGap(f.snapshot(),offer,UUID.randomUUID().toString()){true};check(runCatching{f.sync()}.isFailure);check(f.view().after==0L)
    }
    @Test fun unknownSchemaIsPreservedAndNotActivated() = scenario {
        f.sync();val raw=JSONObject(requireNotNull(f.storage.read(NativeChatNotificationRecoveryStore.RECORD))).put("version",99).toString()
        f.storage.write(NativeChatNotificationRecoveryStore.RECORD,raw);check(runCatching{f.sync()}.isFailure);check(f.storage.read(NativeChatNotificationRecoveryStore.RECORD)==raw)
    }
}
