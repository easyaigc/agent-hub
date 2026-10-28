package com.agenthub.android

import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/** Full legal2048 source receipts, original audit and independent dedup; no OS post/Host/model. */
class NativeMigrationNotificationCapacityTest {
    @get:Rule(order=0)val foreground: org.junit.rules.TestRule=NativeTestForeground()
    @get:Rule(order=1)val ui=createAndroidComposeRule<MainActivity>()
    private lateinit var owned: OwnedControlAcceptance
    private val password="owned notification capacity migration password"
    private val now=java.time.Instant.parse("2026-10-28T12:00:00Z").toEpochMilli()
    private data class Seed(val source: NativeSessionStorage,val event: NativeChatNotificationEvent,val raw: String)
    @Before fun setup(){ui.waitUntil(30_000){!ui.activity.session.restoring};ui.runOnIdle{owned=OwnedControlAcceptance(ui.activity,"migration-notification-capacity")}}
    @After fun finish(){if(::owned.isInitialized)ui.runOnIdle{owned.session.leaveForeground();owned.verifyDefault()}}
    private fun tree(storage: NativeSessionStorage)=File(storage.coordinationKey).let {root->root.walkTopDown().filter{it.isFile}.associate {file->
        val digest=MessageDigest.getInstance("SHA-256");file.inputStream().use {input->val buffer=ByteArray(65536);while(true){val n=input.read(buffer);if(n<0)break;digest.update(buffer,0,n)}}
        file.relativeTo(root).path to digest.digest().joinToString(""){"%02x".format(it)}
    }}
    private fun receipts(event: NativeChatNotificationEvent,lease: NativeChatNoticeLease,count: Int,prefix: String,at: Long=now)=JSONArray().also {array->
        repeat(count){index->array.put(NativeChatNotificationCodec.receipt(NativeChatNoticeReceipt(
            event.copy(id=readingSHA("$prefix:$index"),occurredAt=java.time.Instant.ofEpochMilli(at).toString()),lease,UUID.randomUUID().toString(),at+NativeChatNotificationStore.TTL_MILLIS,"unknown")))}
    }
    private fun seed(count: Int=2048): Seed {
        val source=owned.storage.child(ui.activity,"capacity-source-${UUID.randomUUID().toString().take(8)}");val value=MigrationActivationFixtures.payload();val rows=value.getJSONObject("records");rows.keys().forEach{source.write(it,rows.getString(it))}
        val event=NativeChatNotificationEvent(readingSHA("old:0"),NativeChatNoticeTarget.from(MigrationActivationFixtures.client(value).scope!!),NativeChatNoticeKind.UNCERTAIN,
            java.time.Instant.ofEpochMilli(now).toString(),"synthetic-thread",operationId="original-unknown-operation")
        val store=NativeChatNotificationStore(source);store.update{it.copy(enabled=true)};val lease=store.bind(event.target,"original-capacity-credential")
        val raw=JSONObject(source.read(NativeMigrationDeviceState.NOTIFICATIONS)!!).put("receipts",receipts(event,lease,count,"old")).toString()
        NativeChatNotificationStore.validateEncoded(raw);source.write(NativeMigrationDeviceState.NOTIFICATIONS,raw)
        return Seed(source,event,raw)
    }
    private fun stage(source: NativeSessionStorage,destination: NativeSessionStorage=owned.storage): NativeMigrationCandidateStore.Receipt {
        val file=File(owned.folder,"capacity-${UUID.randomUUID()}.migration");NativeMigrationExport(ui.activity,source).sealFile(file,password)
        return NativeMigrationCandidateStore(ui.activity,destination).stageFile(UUID.randomUUID().toString(),file,password)
    }
    private fun activate(seed: Seed): NativeSessionStorage {
        NativeMigrationActivation(ui.activity,owned.storage).activate(stage(seed.source));return owned.storage.reopen(ui.activity)
    }
    @Test fun legalMaximumSourceKeepsAllOriginalsAndEveryActiveSlotAvailableForNewEvents() {
        val seed=seed();val original=tree(seed.source);val current=activate(seed)
        val index=current.read(NativeMigrationNotificationTombstones.RECORD)!!;assertEquals(2048,NativeMigrationNotificationTombstones.decode(index).size)
        val raw=JSONObject(current.read(NativeMigrationDeviceState.NOTIFICATIONS)!!);assertEquals(1,raw.getInt("version"));assertEquals(0,raw.getJSONArray("receipts").length());assertEquals(0,raw.getJSONArray("leases").length())
        val audit=JSONObject(current.read(NativeMigrationDeviceState.AUDIT)!!);assertEquals(3,audit.getInt("policyVersion"));assertEquals(seed.raw,audit.getJSONArray("entries").getJSONObject(0).getString("notifications"))
        val store=NativeChatNotificationStore(current);store.update{it.copy(enabled=true)};val lease=store.bind(seed.event.target,"fresh-capacity-credential")
        assertNull(store.claim(seed.event,lease,now));assertFalse(NativeMigrationNotificationTombstones.contains(current,seed.event.copy(target=seed.event.target.copy(runtimeId="other-exact-runtime"))))
        assertNotNull(store.claim(seed.event.copy(id=readingSHA("brand-new-first-event")),lease,now))
        assertEquals(1,JSONObject(current.read(NativeMigrationDeviceState.NOTIFICATIONS)!!).getJSONArray("receipts").length())
        assertEquals(index,current.read(NativeMigrationNotificationTombstones.RECORD));assertEquals(original,tree(seed.source))
        File(owned.folder,"full-receipt-capacity.json").writeText(JSONObject().put("sourceReceipts",2048).put("preservedOriginalReceipts",2048).put("dedupKeys",2048)
            .put("inheritedActiveSlots",0).put("firstNewEventClaimed",true).put("oldEventBlocked",true).put("notificationRecordVersion",1).put("heapLimitBytes",Runtime.getRuntime().maxMemory()).put("remoteWrites",0).toString())
    }
    @Test fun oldPolicy2FullSentinelFailureReproducesThenFreshMigrationUpgradesWithoutRewritingOldOriginal() {
        val seed=seed();val oldId=UUID.randomUUID().toString();val audit=JSONObject().put("version",1).put("policyVersion",2).put("entries",JSONArray().put(JSONObject()
            .put("candidateId",oldId).put("archiveSha256",readingSHA("old-failed-candidate-77458")).put("notifications",seed.raw)))
        val sentinel=JSONObject(seed.raw).put("leases",JSONArray());sentinel.getJSONObject("preferences").put("enabled",false)
        sentinel.getJSONArray("receipts").objects().forEach{it.put("expiresAt",9_007_199_254_740_991L).put("consumed",true)}
        seed.source.write(NativeMigrationDeviceState.AUDIT,audit.toString());seed.source.write(NativeMigrationDeviceState.NOTIFICATIONS,sentinel.toString())
        val old=NativeChatNotificationStore(seed.source);old.update{it.copy(enabled=true)};val lease=old.bind(seed.event.target,"rebound-old-policy2-credential")
        val failure=runCatching{old.claim(seed.event.copy(id=readingSHA("fresh-event-old-sentinel-full")),lease,now)}.exceptionOrNull()
        assertEquals("Notification receipt capacity reached",failure?.message);val before=tree(seed.source);val failedState=seed.source.read(NativeMigrationDeviceState.NOTIFICATIONS)!!
        val current=activate(seed);assertEquals(before,tree(seed.source));val upgraded=JSONObject(current.read(NativeMigrationDeviceState.AUDIT)!!)
        assertEquals(3,upgraded.getInt("policyVersion"));assertEquals(audit.getJSONArray("entries").getJSONObject(0).toString(),upgraded.getJSONArray("entries").getJSONObject(0).toString())
        assertEquals(failedState,upgraded.getJSONArray("entries").getJSONObject(1).getString("notifications"))
        assertEquals(2048,NativeMigrationNotificationTombstones.decode(current.read(NativeMigrationNotificationTombstones.RECORD)!!).size)
        val store=NativeChatNotificationStore(current);store.update{it.copy(enabled=true)};val fresh=store.bind(seed.event.target,"after-policy2-upgrade")
        assertNotNull(store.claim(seed.event.copy(id=readingSHA("fresh-event-after-policy3")),fresh,now));assertNull(store.claim(seed.event,fresh,now))
    }
    @Test fun normalActiveCapacityAndTtlAreUnchangedWhileImportedIdsSurviveTtlAndSecondMigration() {
        val seed=seed();val current=activate(seed);val store=NativeChatNotificationStore(current);store.update{it.copy(enabled=true)};val lease=store.bind(seed.event.target,"full-new-active-credential")
        val audit=current.read(NativeMigrationDeviceState.AUDIT)!!;val index=current.read(NativeMigrationNotificationTombstones.RECORD)!!
        val full=JSONObject(current.read(NativeMigrationDeviceState.NOTIFICATIONS)!!).put("receipts",receipts(seed.event,lease,2048,"new-active"))
        NativeChatNotificationStore.validateEncoded(full.toString());current.write(NativeMigrationDeviceState.NOTIFICATIONS,full.toString())
        assertEquals("Notification receipt capacity reached",runCatching{store.claim(seed.event.copy(id=readingSHA("one-over-normal-active-cap")),lease,now)}.exceptionOrNull()?.message)
        val later=now+2*NativeChatNotificationStore.TTL_MILLIS
        assertNull(store.claim(seed.event.copy(occurredAt=java.time.Instant.ofEpochMilli(later).toString()),lease,later))
        val newest=seed.event.copy(id=readingSHA("new-after-normal-ttl"),occurredAt=java.time.Instant.ofEpochMilli(later).toString())
        assertNotNull(store.claim(newest,lease,later));assertEquals(1,JSONObject(current.read(NativeMigrationDeviceState.NOTIFICATIONS)!!).getJSONArray("receipts").length())
        assertEquals(audit,current.read(NativeMigrationDeviceState.AUDIT));assertEquals(index,current.read(NativeMigrationNotificationTombstones.RECORD))
        val destination=NativeSessionStorage(ui.activity,"P7-capacity-remigration-${UUID.randomUUID()}");val next=stage(current,destination);NativeMigrationActivation(ui.activity,destination).activate(next)
        val latest=destination.reopen(ui.activity);assertEquals(2049,NativeMigrationNotificationTombstones.decode(latest.read(NativeMigrationNotificationTombstones.RECORD)!!).size)
        val cold=NativeChatNotificationStore(latest);cold.update{it.copy(enabled=true)};val fresh=cold.bind(seed.event.target,"third-capacity-credential")
        assertNull(cold.claim(seed.event.copy(occurredAt=newest.occurredAt),fresh,later));assertNull(cold.claim(newest,fresh,later))
        assertNotNull(cold.claim(newest.copy(id=readingSHA("brand-new-third-installation")),fresh,later))
    }
    @Test fun futureMissingTruncatedOrExtraTombstoneIndexRejectsBeforeReplacingCandidate() {
        val seed=seed(1);val current=activate(seed);val other=NativeSessionStorage(ui.activity,"P7-capacity-schema-${UUID.randomUUID()}")
        val first=stage(current,other);val local=other.child(ui.activity,"migration-candidates");val before=tree(local)
        val bytes=NativeMigrationExport(ui.activity,current).capture();val original=try{JSONObject(String(bytes,Charsets.UTF_8))}finally{bytes.fill(0)}
        for(kind in listOf("version","missing","truncated","extra-key","unknown-field","duplicate")) {
            val value=JSONObject(original.toString());val records=value.getJSONObject("records");val index=JSONObject(records.getString(NativeMigrationNotificationTombstones.RECORD));val keys=index.getJSONArray("eventKeys")
            when(kind){
                "version"->index.put("version",2)
                "truncated"->index.put("eventKeys",JSONArray())
                "extra-key"->index.put("eventKeys",JSONArray((keys.strings()+readingSHA("not-in-any-original")).sorted()))
                "unknown-field"->index.put("receipts",JSONArray())
                "duplicate"->keys.put(keys.getString(0))
                else->Unit
            }
            if(kind=="missing")records.remove(NativeMigrationNotificationTombstones.RECORD)else records.put(NativeMigrationNotificationTombstones.RECORD,index.toString())
            val envelope=NativeMigrationCrypto.seal(value.toString().toByteArray(),password)
            assertTrue(kind,runCatching{NativeMigrationCandidateStore(ui.activity,other).stage(UUID.randomUUID().toString(),envelope,password,first)}.isFailure);assertEquals(before,tree(local))
        }
        val liveIndex=current.read(NativeMigrationNotificationTombstones.RECORD)!!;current.write(NativeMigrationNotificationTombstones.RECORD,JSONObject(liveIndex).put("version",2).toString())
        val store=NativeChatNotificationStore(current);store.update{it.copy(enabled=true)};val lease=store.bind(seed.event.target,"future-index-credential");val unchanged=tree(current)
        assertTrue(runCatching{store.claim(seed.event.copy(id=readingSHA("new-event-with-future-index")),lease,now)}.isFailure);assertEquals(unchanged,tree(current))
    }
}
