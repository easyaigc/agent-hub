package com.agenthub.android

import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/** Production reading/P9 parsers and stores through the existing migration; no OS post or Host. */
class NativeMigrationDeviceStateTest {
    @get:Rule(order=0)val foreground: org.junit.rules.TestRule=NativeTestForeground()
    @get:Rule(order=1)val ui=createAndroidComposeRule<MainActivity>()
    private lateinit var owned: OwnedControlAcceptance
    private val password="owned device state migration password"
    private val now=java.time.Instant.parse("2026-10-28T12:00:00Z").toEpochMilli()
    private data class Seed(val source: NativeSessionStorage,val client: MobileClient,val event: NativeChatNotificationEvent,
        val lease: NativeChatNoticeLease,val receipt: NativeChatNoticeReceipt,val read: ThreadReadPosition,val latest: ThreadReadPosition,
        val readerId: String,val reading: String,val notifications: String)
    @Before fun setup(){ui.waitUntil(30_000){!ui.activity.session.restoring};ui.runOnIdle{owned=OwnedControlAcceptance(ui.activity,"migration-device-state")}}
    @After fun finish(){if(::owned.isInitialized)ui.runOnIdle{owned.session.leaveForeground();owned.verifyDefault()}}
    private fun tree(storage: NativeSessionStorage)=File(storage.coordinationKey).let {root->root.walkTopDown().filter{it.isFile}.associate {file->
        val digest=MessageDigest.getInstance("SHA-256");file.inputStream().use {input->val buffer=ByteArray(65536);while(true){val n=input.read(buffer);if(n<0)break;digest.update(buffer,0,n)}}
        file.relativeTo(root).path to digest.digest().joinToString(""){"%02x".format(it)}
    }}
    private fun seed(): Seed {
        val source=owned.storage.child(ui.activity,"device-source");val value=MigrationActivationFixtures.payload();val rows=value.getJSONObject("records");rows.keys().forEach{source.write(it,rows.getString(it))}
        val client=MigrationActivationFixtures.client(value);val target=client.scope!!;val thread="synthetic-thread"
        val read=ThreadReadPosition(2,"old-original-reply",ThreadReadPosition.contentVersion("old-original-reply","Old visible reply","completed"));val latest=ThreadReadPosition(3,"new-original-reply",ThreadReadPosition.contentVersion("new-original-reply","New unread reply","completed"));val reader=readingSHA("source-device-reader")
        val event=NativeChatNotificationEvent(readingSHA("source-unknown-notice"),NativeChatNoticeTarget.from(target),NativeChatNoticeKind.UNCERTAIN,
            java.time.Instant.ofEpochMilli(now).toString(),threadId=thread,operationId="original-unknown-operation",taskSummary="Original unknown result")
        val ledger=NativeReadingLedger(source);ledger.reconcile(listOf(target),listOf(NativeReadingSnapshot(target,thread,read,true)))
        ledger.receive(NativeReadingEvent(event.id,target,thread,event.kind.wire,operationId=event.operationId))
        val notifications=NativeChatNotificationStore(source);notifications.update{it.copy(enabled=true,timeSensitive=true,foregroundBanner=false)}
        val lease=notifications.bind(event.target,"original-device-credential-ref");val receipt=notifications.claim(event,lease,now)!!;notifications.finish(receipt.ticket,false)
        val lifecycle=NativeReadingLifecycle();lifecycle.present(target,thread,read,setOf(event.id));lifecycle.foreground(true);lifecycle.uncovered(true)
        val visible=lifecycle.visible(target,thread,read.messageId,"Old visible reply","completed",true)!!
        ledger.readVisible(target,thread,reader,lifecycle,visible)
        ledger.receive(NativeReadingEvent(readingSHA("new-completed"),target,thread,"completed",latest))
        return Seed(source,client,event,lease,receipt,read,latest,reader,source.read(NativeMigrationDeviceState.READING)!!,source.read(NativeMigrationDeviceState.NOTIFICATIONS)!!)
    }
    private fun stage(source: NativeSessionStorage,destination: NativeSessionStorage=owned.storage): NativeMigrationCandidateStore.Receipt {
        val file=File(owned.folder,"device-${UUID.randomUUID()}.migration");NativeMigrationExport(ui.activity,source).sealFile(file,password)
        return NativeMigrationCandidateStore(ui.activity,destination).stageFile(UUID.randomUUID().toString(),file,password)
    }
    @Test fun activationPreservesWatermarksAndSeenUnknownAuditWhileResettingAllLocalAuthority() {
        val source=seed();val before=tree(source.source);val receipt=stage(source.source);val local=owned.storage.child(ui.activity,"migration-candidates");val candidateBefore=tree(local)
        val generation=NativeMigrationActivation(ui.activity,owned.storage).activate(receipt);val current=owned.storage.reopen(ui.activity)
        val raw=JSONObject(current.read(NativeMigrationDeviceState.READING)!!);assertEquals(0,raw.getJSONArray("scopes").length());val row=raw.getJSONArray("rows").getJSONObject(0)
        assertFalse(row.has("pendingReader"));assertEquals(source.read.json().toString(),row.getJSONObject("read").toString());assertEquals(source.latest.json().toString(),row.getJSONObject("latest").toString())
        assertTrue(row.getJSONObject("notices").getJSONObject(source.event.id).getBoolean("seen"));assertTrue(NativeReadingLedger(current).pendingReads().isEmpty())
        val ledger=NativeReadingLedger(current);assertEquals(0,ledger.snapshot().count);ledger.reconcile(listOf(source.client.scope!!),emptyList());assertEquals(1,ledger.snapshot().count)
        assertFalse(ledger.mayPresent(NativeReadingEvent(source.event.id,source.client.scope!!,"synthetic-thread",source.event.kind.wire,operationId=source.event.operationId)))
        val notifications=NativeChatNotificationStore(current);assertFalse(notifications.preferences().enabled);assertFalse(notifications.current(source.lease));assertNull(notifications.ticket(source.receipt.ticket,now))
        assertTrue(notifications.preferences().timeSensitive);assertFalse(notifications.preferences().foregroundBanner)
        val state=JSONObject(current.read(NativeMigrationDeviceState.NOTIFICATIONS)!!);assertEquals(0,state.getJSONArray("leases").length());assertEquals(0,state.getJSONArray("receipts").length())
        assertTrue(NativeMigrationNotificationTombstones.contains(current,source.event))
        val audit=JSONObject(current.read(NativeMigrationDeviceState.AUDIT)!!).getJSONArray("entries").getJSONObject(0)
        assertEquals(source.reading,audit.getString("reading"));assertEquals(source.notifications,audit.getString("notifications"));assertEquals(receipt.id,audit.getString("candidateId"));assertEquals(receipt.sha256,audit.getString("archiveSha256"))
        notifications.update{it.copy(enabled=true)};val fresh=notifications.bind(source.event.target,"new-installation-credential-ref");assertTrue(notifications.current(fresh))
        assertNull(notifications.claim(source.event,fresh,now){true})
        val later=now+2*NativeChatNotificationStore.TTL_MILLIS
        assertNull(notifications.claim(source.event.copy(occurredAt=java.time.Instant.ofEpochMilli(later).toString()),fresh,later){true})
        assertNull(notifications.ticket(source.receipt.ticket,later))
        val newEvent=source.event.copy(id=readingSHA("new-distinct-event"),occurredAt=java.time.Instant.ofEpochMilli(later).toString())
        assertNotNull(notifications.claim(newEvent,fresh,later){true})
        val after=tree(current);assertEquals(generation,NativeMigrationActivation(ui.activity,owned.storage).activate(receipt));assertEquals(after,tree(current));assertEquals(before,tree(source.source));assertEquals(candidateBefore,tree(local))
        File(owned.folder,"device-state-activation.json").writeText(JSONObject().put("originalWatermarksAndSeen",true).put("oldReadersCleared",true).put("unknownOriginalAudit",true)
            .put("notificationsDisabledAndUnbound",true).put("oldTicketsInert",true).put("newRegistrationCannotAlertOldEvent",true).put("newEventAllowed",true).put("sourceAndCandidatePreserved",true).put("remoteWrites",0).toString())
    }
    @Test fun futureSchemasForeignScopesAndConflictingNoticeIntentRejectWithoutReplacingOriginalCandidate() {
        val source=seed();val first=stage(source.source);val local=owned.storage.child(ui.activity,"migration-candidates");val before=tree(local)
        val original=JSONObject(String(NativeMigrationExport(ui.activity,source.source).capture(),Charsets.UTF_8))
        for(kind in listOf("reading-version","notification-version","sound-schema","foreign-scope","notice-conflict")) {
            val value=JSONObject(original.toString());val records=value.getJSONObject("records")
            val name=if(kind in listOf("notification-version","sound-schema"))NativeMigrationDeviceState.NOTIFICATIONS else NativeMigrationDeviceState.READING
            val body=JSONObject(records.getString(name))
            when(kind){
                "reading-version","notification-version"->body.put("version",2)
                "sound-schema"->body.getJSONObject("preferences").put("soundSchema",2)
                "foreign-scope"->body.put("scopes",org.json.JSONArray().put("f".repeat(64)))
                else->body.getJSONArray("rows").getJSONObject(0).getJSONObject("notices").getJSONObject(source.event.id).put("operationId","different-original-intent")
            }
            records.put(name,body.toString());val envelope=NativeMigrationCrypto.seal(value.toString().toByteArray(),password)
            assertTrue(kind,runCatching{NativeMigrationCandidateStore(ui.activity,owned.storage).stage(UUID.randomUUID().toString(),envelope,password,first)}.isFailure);assertEquals(before,tree(local))
        }
    }
    @Test fun eachPrecommitFailureKeepsSourceAndAddedAuditAtomicThenOriginalReceiptIsIdempotent() {
        val source=seed();val receipt=stage(source.source);val before=tree(owned.storage);val sourceBefore=tree(source.source)
        for(at in listOf(1,receipt.recordCount+1,receipt.recordCount+2,0)) {
            assertTrue(runCatching{NativeMigrationActivation(ui.activity,owned.storage).activate(receipt){if(it==at)throw java.io.IOException("owned device migration interruption")}}.isFailure)
            assertEquals(before,tree(owned.storage));assertEquals(sourceBefore,tree(source.source))
        }
        val generation=NativeMigrationActivation(ui.activity,owned.storage).activate(receipt);val current=owned.storage.reopen(ui.activity)
        assertEquals(receipt.recordCount+2,File(current.coordinationKey,"migration-generation-$generation").listFiles().orEmpty().count{it.name.endsWith(".bin")})
        current.write("later-draft","independent draft");assertEquals(generation,NativeMigrationActivation(ui.activity,owned.storage).activate(receipt));assertEquals("independent draft",current.read("later-draft"))
    }
    @Test fun repeatedMigrationPreservesPriorAuditAndPermanentSeenDedupWithoutOldAuthority() {
        val source=seed();val first=stage(source.source);NativeMigrationActivation(ui.activity,owned.storage).activate(first);val current=owned.storage.reopen(ui.activity)
        val originalAudit=current.read(NativeMigrationDeviceState.AUDIT)!!;val currentBefore=tree(current)
        val destination=NativeSessionStorage(ui.activity,"P7-device-remigration-${UUID.randomUUID()}")
        val next=stage(current,destination);NativeMigrationActivation(ui.activity,destination).activate(next);val latest=destination.reopen(ui.activity)
        val audit=JSONObject(latest.read(NativeMigrationDeviceState.AUDIT)!!);assertEquals(2,audit.getJSONArray("entries").length())
        assertEquals(JSONObject(originalAudit).getJSONArray("entries").getJSONObject(0).toString(),audit.getJSONArray("entries").getJSONObject(0).toString())
        val store=NativeChatNotificationStore(latest);store.update{it.copy(enabled=true)};val lease=store.bind(source.event.target,"third-installation-ref");assertNull(store.claim(source.event,lease,now){true})
        assertNull(store.ticket(source.receipt.ticket,now));assertTrue(NativeReadingLedger(latest).pendingReads().isEmpty());assertEquals(currentBefore,tree(current))
    }
    @Test fun unseenUnknownNoticeRemainsUnreadButItsOriginalAlertAndTicketCannotReplay() {
        val source=seed();val event=source.event.copy(id=readingSHA("still-unseen-unknown"),operationId="another-original-operation")
        NativeReadingLedger(source.source).receive(NativeReadingEvent(event.id,source.client.scope!!,"synthetic-thread",event.kind.wire,operationId=event.operationId))
        val original=NativeChatNotificationStore(source.source).claim(event,source.lease,now)!!;NativeChatNotificationStore(source.source).finish(original.ticket,false)
        val receipt=stage(source.source);NativeMigrationActivation(ui.activity,owned.storage).activate(receipt);val current=owned.storage.reopen(ui.activity)
        val ledger=NativeReadingLedger(current);ledger.reconcile(listOf(source.client.scope!!),emptyList());assertEquals(1,ledger.snapshot().count)
        assertTrue(ledger.notices(source.client.scope!!,"synthetic-thread").any{it.id==event.id&&!it.seen})
        val store=NativeChatNotificationStore(current);store.update{it.copy(enabled=true)};val lease=store.bind(event.target,"new-unseen-device-ref")
        assertNull(store.claim(event,lease,now){true});assertNull(store.ticket(original.ticket,now))
        val audit=JSONObject(current.read(NativeMigrationDeviceState.AUDIT)!!).getJSONArray("entries").getJSONObject(0)
        assertEquals(source.source.read(NativeMigrationDeviceState.READING),audit.getString("reading"));assertEquals(source.source.read(NativeMigrationDeviceState.NOTIFICATIONS),audit.getString("notifications"))
    }
    @Test fun fullAuditCapacityRejectsWithoutPruningPriorOriginalsOrPublishingPartialState() {
        val source=seed();val entries=org.json.JSONArray()
        repeat(16){entries.put(JSONObject().put("candidateId",UUID.randomUUID().toString()).put("archiveSha256",readingSHA("old audit $it")).put("reading",source.reading).put("notifications",source.notifications))}
        val audit=JSONObject().put("version",1).put("policyVersion",2).put("entries",entries).toString();source.source.write(NativeMigrationDeviceState.AUDIT,audit)
        val receipt=stage(source.source);val before=tree(owned.storage);val original=tree(source.source)
        assertTrue(runCatching{NativeMigrationActivation(ui.activity,owned.storage).activate(receipt)}.isFailure)
        assertEquals(before,tree(owned.storage));assertEquals(original,tree(source.source));assertEquals(audit,source.source.read(NativeMigrationDeviceState.AUDIT))
    }
}
