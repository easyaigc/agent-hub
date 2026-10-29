package com.agenthub.android

import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import java.io.DataOutputStream
import java.io.File
import java.util.UUID

/** Real Keystore/export/candidate/publication. Producer validation is an independent prerequisite. */
class NativeMigrationTextFrameTest {
    @get:Rule(order=0) val foreground: org.junit.rules.TestRule=NativeTestForeground()
    @get:Rule(order=1) val ui=createAndroidComposeRule<MainActivity>()
    private lateinit var owned: OwnedControlAcceptance
    private val password="owned bounded UTF8 text migration"
    @Before fun setup(){ui.waitUntil(30_000){!ui.activity.session.restoring};ui.runOnIdle{owned=OwnedControlAcceptance(ui.activity,"migration-text-frames")}}
    @After fun finish(){if(::owned.isInitialized)ui.runOnIdle{owned.session.leaveForeground();owned.verifyDefault()}}
    private fun file()=File(owned.folder,"text-${UUID.randomUUID()}.migration")
    private fun source(): Pair<NativeSessionStorage,MobileClient> {
        val source=owned.storage.child(ui.activity,"text-source-"+UUID.randomUUID().toString().take(8))
        val value=MigrationActivationFixtures.payload();val rows=value.getJSONObject("records");rows.keys().forEach {source.write(it,rows.getString(it))}
        return source to MigrationActivationFixtures.client(value)
    }
    private fun producer(name: String,raw: String) {
        val type=Class.forName("com.agenthub.android.$name");val companion=type.getField("Companion").get(null)
        companion.javaClass.getDeclaredMethod("validateEncoded",String::class.java).invoke(companion,raw)
    }
    private fun exactPad(raw: String,bytes: Int)=raw+" ".repeat(bytes-raw.toByteArray(Charsets.UTF_8).size)
    private fun write(records: NativeMigrationStreamRecords,header: JSONObject=records.header(ui.activity.packageName),order: List<String> =records.frames().keys.toList()): File=file().also {target->
        target.outputStream().use {output->NativeMigrationStreamCrypto.write(output,password) {clear->
            val bytes=header.toString().toByteArray(Charsets.UTF_8);val data=DataOutputStream(clear);data.writeInt(bytes.size);data.write(bytes)
            order.forEach {records.frames().getValue(it).copy(data)}
        }}
    }
    private fun small(): NativeMigrationStreamRecords {
        val rows=MigrationActivationFixtures.payload().getJSONObject("records")
        return NativeMigrationStreamRecords(NativeMigrationTextMap.copy(rows.keys().asSequence().associateWith {rows.getString(it)}),emptyMap(),4)
    }
    @Test fun nearMaximumActualP9OriginalsAboveEightMiBStayExactAfterColdCandidateAndActivation() {
        val (source,client)=source();val identity=NotificationArchiveFixtures.identity();val authority=NotificationArchiveFixtures.authority(client)
        source.write(NativeMigrationForeground.IDENTITY,identity.raw);val target=NativeChatNoticeTarget.from(client.scope!!)
        val digests=linkedMapOf<String,String>();var originalBytes=0L
        fun save(name: String,raw: String,limit: Int,producerName: String) {
            val value=exactPad(raw,limit-256);producer(producerName,value)
            digests[name]=NotificationArchiveFixtures.hash(value);originalBytes+=value.toByteArray(Charsets.UTF_8).size;source.write(name,value)
        }
        save(NativeMigrationForeground.JOURNAL,NotificationArchiveFixtures.v1(identity,target,authority.fingerprint),4*1024*1024,"NativeChatNotificationForegroundStore")
        save(NativeMigrationForeground.RECOVERY,NotificationArchiveFixtures.v2(identity,target,authority.fingerprint,"unknown"),4*1024*1024,"NativeChatNotificationRecoveryStore")
        save(NativeMigrationForeground.AUTHORIZATIONS,authority.raw,1024*1024,"NativeChatNotificationAuthorizations")
        assertTrue(originalBytes>8*1024*1024);val before=MigrationActivationFixtures.tree(source)
        // The bounded legacy inline exporter still refuses the aggregate, rather than raising its cap.
        assertTrue(runCatching {NativeMigrationExport(ui.activity,source).capture()}.isFailure)
        val envelope=file();NativeMigrationExport(ui.activity,source).sealFile(envelope,password)
        val store=NativeMigrationCandidateStore(ui.activity,owned.storage);val receipt=store.stageFile(UUID.randomUUID().toString(),envelope,password)
        val local=owned.storage.child(ui.activity,"migration-candidates");val candidateBefore=MigrationActivationFixtures.tree(local)
        val cold=NativeMigrationCandidateStore(ui.activity,owned.storage.reopen(ui.activity));assertEquals(receipt,cold.currentReceipt())
        NativeMigrationStreamCandidate(ui.activity,owned.storage).read(receipt) {records->
            assertEquals(4,records.version);assertTrue(records.text is NativeMigrationTextMap)
            assertTrue(records.header(ui.activity.packageName).toString().toByteArray().size<128*1024)
            assertTrue(records.textFrames().values.sumOf {it.size.toLong()}>8*1024*1024)
        }
        assertEquals(receipt,store.stageFile(receipt.id,envelope,password));assertEquals(candidateBefore,MigrationActivationFixtures.tree(local))
        NativeMigrationActivation(ui.activity,owned.storage,NativeMigrationNotificationActivationLease(owned.storage,receipt){true}).activate(receipt)
        val current=owned.storage.reopen(ui.activity);val audit=NativeMigrationNotificationArchives.readOnlyArchives(current).single()
        for((name,kind)in listOf(NativeMigrationForeground.JOURNAL to NativeMigrationNotificationArchives.Original.FOREGROUND_V1,
            NativeMigrationForeground.RECOVERY to NativeMigrationNotificationArchives.Original.RECOVERY_V2,
            NativeMigrationForeground.AUTHORIZATIONS to NativeMigrationNotificationArchives.Original.AUTHORIZATIONS_V1))
            assertEquals(digests.getValue(name),NotificationArchiveFixtures.hash(requireNotNull(audit.readOnly(kind))))
        NativeMigrationForeground.ACTIVE_NAMES.forEach {assertNull(current.read(it))}
        assertTrue(NativeMigrationNotificationArchives.requiresExplicitSetup(current));assertFalse(NativeChatNotificationStore(current).preferences().enabled)
        assertEquals(before,MigrationActivationFixtures.tree(source));assertEquals(candidateBefore,MigrationActivationFixtures.tree(local))
        File(owned.folder,"maximum-text-frames.json").writeText(JSONObject().put("originalBytes",originalBytes).put("heapLimit",Runtime.getRuntime().maxMemory())
            .put("originalCiphertextPreserved",true).put("candidateAndScratchUnchanged",true).put("remoteWrites",0).toString())
    }
    @Test fun authenticatedInvalidUtf8DuplicateJsonReorderedAndFutureFramesPreserveOriginalSlot() {
        val good=small();val store=NativeMigrationCandidateStore(ui.activity,owned.storage);val prior=store.stageFile(UUID.randomUUID().toString(),write(good),password)
        val local=owned.storage.child(ui.activity,"migration-candidates");val before=MigrationActivationFixtures.tree(local)
        fun raw(bytes: ByteArray): NativeMigrationStreamRecords {
            val probe=NativeMigrationProbe();probe.write(bytes);val frames=good.textFrames().toMutableMap()
            frames["metadata"]=NativeMigrationBlob(bytes.size,probe.sha256(),probe.prefix()){it.write(bytes)}
            return good.copy(text=NativeMigrationTextMap(frames))
        }
        val bad=listOf(write(raw(byteArrayOf(0xc3.toByte(),0x28))),write(raw("{\"version\":1,\"version\":1}".toByteArray())),
            write(good,order=good.frames().keys.reversed()),write(good,header=good.header(ui.activity.packageName).put("version",5)))
        for(input in bad) {
            assertTrue(runCatching {store.stageFile(UUID.randomUUID().toString(),input,password,prior)}.isFailure)
            assertEquals(before,MigrationActivationFixtures.tree(local));assertEquals(prior,store.currentReceipt())
        }
        // Pending atomic state must remain untouched; no reader may recover it to make the test pass.
        val pending=File(local.coordinationKey,NotificationArchiveFixtures.hash("candidate-v1")+".bin.new").apply {writeText("owned pending candidate")}
        val pendingBefore=MigrationActivationFixtures.tree(local)
        assertTrue(runCatching {store.currentReceipt()}.isFailure);assertTrue(runCatching {store.stageFile(prior.id,write(good),password)}.isFailure)
        assertEquals(pendingBefore,MigrationActivationFixtures.tree(local));assertTrue(pending.delete())
        val saved=JSONObject(requireNotNull(local.read("candidate-v1")));val first="migration-stream:${saved.getString("generation")}:0"
        val chunk=File(local.coordinationKey,NotificationArchiveFixtures.hash(first)+".bin.bak").apply {writeText("owned pending frame")}
        val chunkBefore=MigrationActivationFixtures.tree(local);assertTrue(runCatching {store.currentReceipt()}.isFailure)
        assertEquals(chunkBefore,MigrationActivationFixtures.tree(local));assertTrue(chunk.delete())
        assertEquals(prior,store.currentReceipt())
    }
    @Test fun v3AndLegacySlotsRemainReadableAndOnlyExplicitReplacementSelectsV4() {
        val (source,_)=source();val store=NativeMigrationCandidateStore(ui.activity,owned.storage)
        val legacy=file().apply {writeBytes(NativeMigrationExport(ui.activity,source).seal(password))}
        val original=store.stageFile(UUID.randomUUID().toString(),legacy,password);assertEquals(original,store.currentReceipt());store.readArchive(original)
        val v3=small().let {it.copy(text=it.text.toMap(),version=3)}
        val old=store.stageFile(UUID.randomUUID().toString(),write(v3),password,original)
        assertEquals(old,store.currentReceipt());NativeMigrationStreamCandidate(ui.activity,owned.storage).read(old){assertEquals(3,it.version)}
        val newer=file().also {NativeMigrationExport(ui.activity,source).sealFile(it,password)}
        val before=MigrationActivationFixtures.tree(owned.storage.child(ui.activity,"migration-candidates"))
        assertTrue(runCatching {store.stageFile(UUID.randomUUID().toString(),newer,password)}.isFailure)
        assertEquals(before,MigrationActivationFixtures.tree(owned.storage.child(ui.activity,"migration-candidates")))
        val selected=store.stageFile(UUID.randomUUID().toString(),newer,password,old)
        NativeMigrationStreamCandidate(ui.activity,owned.storage).read(selected){assertEquals(4,it.version)}
        assertEquals(selected,store.currentReceipt())
    }
    @Test fun textQuotasRejectBeforeReadingAndSparseIndexesDoNotLoadUnrelatedValues() {
        var reads=0
        fun descriptor(size: Int)=NativeMigrationBlob(size,"0".repeat(64),ByteArray(minOf(44,size))){reads++;error("Quota checking must not load frames")}
        assertTrue(runCatching {NativeMigrationStreamRecords(NativeMigrationTextMap(mapOf("metadata" to descriptor(8*1024*1024+1))),emptyMap(),4)}.isFailure)
        assertTrue(runCatching {NativeMigrationStreamRecords(NativeMigrationTextMap((0 until 25).associate {"frame:$it" to descriptor(8*1024*1024)}),emptyMap(),4)}.isFailure)
        assertEquals(0,reads)
        val map=NativeMigrationTextMap(mapOf("first" to descriptor(5),"other" to descriptor(7)))
        assertEquals(setOf("first","other"),map.keys);assertTrue("other" in map);val alias=NativeMigrationTextMap.copy(map);alias.alias(map,"other","renamed")
        assertEquals(3,alias.size);assertEquals(0,reads)
        assertTrue(runCatching {alias.getValue("renamed")}.isFailure);assertEquals(1,reads)
    }
}
