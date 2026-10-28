package com.agenthub.android

import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import org.json.*
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.security.KeyStore
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import javax.crypto.Cipher
import javax.crypto.SecretKey

/** Exact encrypted source/export/import and native history graph; synthetic content, no Host writes. */
class NativeMigrationExportTest {
    @get:Rule(order=0)val foreground: org.junit.rules.TestRule=NativeTestForeground()
    @get:Rule(order=1)val ui=createAndroidComposeRule<MainActivity>()
    private lateinit var owned: OwnedControlAcceptance
    private lateinit var client: MobileClient
    private lateinit var key: String
    private val thread="synthetic-thread"
    @Before fun setup(){ui.waitUntil(30_000){!ui.activity.session.restoring};ui.runOnIdle{owned=OwnedControlAcceptance(ui.activity,"migration-export")};val payload=MigrationActivationFixtures.payload();client=MigrationActivationFixtures.client(payload);key=client.cacheKey(thread);val records=payload.getJSONObject("records");records.keys().forEach{owned.storage.write(it,records.getString(it))}}
    @After fun finish(){if(::owned.isInitialized)ui.runOnIdle{owned.session.leaveForeground();owned.verifyDefault()}}
    private fun wire(count: Int=70,label: String="original")=JSONObject().put("hostId",client.id).put("threadId",thread).put("readTarget",encodeTarget(client.scope!!))
        .put("messages",JSONArray((0 until count).map{index->JSONObject().put("id","message-$index").put("role","assistant").put("text","$label 中文 😀 100% %@\n$index").put("kind",if(index==33)"contextCompaction" else "agentMessage").put("status","completed")}))
        .put("nextCursor","original-next").put("epoch","original-epoch").put("cursor",9)
    private fun history(count: Int=70,label: String="original"): MobileHistory {val page=HostReadCodec.history(wire(count,label).toString());NativeHistoryCache(owned.storage).save(key,client,thread,page,page.messages);return page}
    private fun captured(): Map<String,String> {val clear=NativeMigrationExport(ui.activity,owned.storage).capture();try{return NativeMigrationCandidateStore(ui.activity,owned.storage).validate(clear)}finally{clear.fill(0)}}
    private fun file(name: String)=File(owned.storage.coordinationKey,MigrationActivationFixtures.sha(name.toByteArray())+".bin")
    private fun rejected(block: ()->Unit){assertTrue("Incomplete export must fail",runCatching(block).isFailure)}
    @Test fun automaticExportImportsAllOriginalHistoryFragmentsAndKeepsImmutableInputObserveOnly(){
        val original=history();val names=owned.storage.migrationNames();val records=owned.storage.migrationSnapshot(names);val before=MigrationActivationFixtures.tree(owned.storage)
        val encrypted=NativeMigrationExport(ui.activity,owned.storage).seal("owned export password");assertFalse(String(encrypted).contains("original unsent text"));assertEquals(before,MigrationActivationFixtures.tree(owned.storage))
        val destination=owned.storage.child(ui.activity,"export-roundtrip");val receipt=NativeMigrationCandidateStore(ui.activity,destination).stage(UUID.randomUUID().toString(),encrypted,"owned export password")
        assertEquals(records,NativeMigrationCandidateStore(ui.activity,destination).read(receipt));NativeMigrationActivation(ui.activity,destination).activate(receipt);val reopened=destination.reopen(ui.activity)
        val page=NativeHistoryCache(reopened).read(key)!!;assertEquals(original.messages,page.messages);assertEquals(original.nextCursor,page.nextCursor);assertEquals(original.epoch,page.epoch);assertEquals(original.cursor,page.cursor)
        for((name,text)in records)if(name.startsWith("history:"))assertEquals(text,reopened.read(name))
        val operations=JSONObject(reopened.read("operations:$key")!!).getJSONArray("operations");assertTrue(operations.getJSONObject(0).getBoolean("migrationReadOnly"));assertEquals(before,MigrationActivationFixtures.tree(owned.storage))
    }
    @Test fun exactLegacyMetadataReferencesRecoverOldHashedRecordsWithoutRewritingThem(){
        history();val records=owned.storage.migrationSnapshot(owned.storage.migrationNames())
        val secret=KeyStore.getInstance("AndroidKeyStore").apply{load(null)}.getKey("agent-hub-mobile-v1",null) as SecretKey
        for((name,text)in records){val cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,secret);cipher.updateAAD(name.toByteArray());file(name).writeBytes(byteArrayOf(1)+cipher.iv+cipher.doFinal(text.toByteArray()))}
        val before=MigrationActivationFixtures.tree(owned.storage);rejected{owned.storage.migrationNames()};assertEquals(records,captured());assertEquals(before,MigrationActivationFixtures.tree(owned.storage))
    }
    @Test fun missingOrOrphanFragmentsAndWrongRuntimeRejectWithoutChangingAnySource(){
        history();val name="history:$key";val original=owned.storage.read(name)!!;val index=JSONObject(original);val part=index.getJSONArray("parts").getString(0);val text=owned.storage.read(part)!!
        owned.storage.remove(part);var before=MigrationActivationFixtures.tree(owned.storage);rejected{captured()};assertEquals(before,MigrationActivationFixtures.tree(owned.storage));owned.storage.write(part,text)
        val orphan="$name:part:${UUID.randomUUID()}:0";owned.storage.write(orphan,"[]");before=MigrationActivationFixtures.tree(owned.storage);rejected{captured()};assertEquals(before,MigrationActivationFixtures.tree(owned.storage));owned.storage.remove(orphan)
        val wrong=JSONObject(original);wrong.getJSONObject("history").getJSONObject("readTarget").put("runtimeId","another-runtime");owned.storage.write(name,wrong.toString());before=MigrationActivationFixtures.tree(owned.storage);rejected{captured()};assertEquals(before,MigrationActivationFixtures.tree(owned.storage))
    }
    @Test fun duplicateFieldsWrongCountAndCrossTargetPartCannotBecomeAnImportCandidate(){
        history();val name="history:$key";val original=owned.storage.read(name)!!;val index=JSONObject(original);val part=index.getJSONArray("parts").getString(0);val text=owned.storage.read(part)!!
        owned.storage.write(part,"[{\"id\":\"first\",\"id\":\"replacement\"}]");rejected{captured()};owned.storage.write(part,text)
        owned.storage.write(name,JSONObject(original).put("messageCount","70").toString());rejected{captured()}
        owned.storage.write(name,JSONObject(original).put("messageCount",69).toString());rejected{captured()}
        val wrong=JSONObject(original);wrong.getJSONArray("parts").put(0,"history:other:part:${index.getString("generation")}:0");owned.storage.write(name,wrong.toString());val before=MigrationActivationFixtures.tree(owned.storage);rejected{captured()};assertEquals(before,MigrationActivationFixtures.tree(owned.storage))
    }
    @Test fun unknownRecordsPendingAtomicFilesAndUnsupportedAudioNeverProducePartialSuccess(){
        history();val exporter=NativeMigrationExport(ui.activity,owned.storage)
        owned.storage.write("future-record","{\"version\":1}");var before=MigrationActivationFixtures.tree(owned.storage);rejected{exporter.capture()};assertEquals(before,MigrationActivationFixtures.tree(owned.storage));owned.storage.remove("future-record")
        val pending=File(file("metadata").path+".bak");pending.writeBytes(file("metadata").readBytes());before=MigrationActivationFixtures.tree(owned.storage);rejected{exporter.capture()};assertEquals(before,MigrationActivationFixtures.tree(owned.storage));assertTrue(pending.delete())
        owned.storage.write("voice-future-index-v2","[]");before=MigrationActivationFixtures.tree(owned.storage);rejected{exporter.capture()};assertEquals(before,MigrationActivationFixtures.tree(owned.storage));rejected{exporter.seal("too-short")};assertEquals(before,MigrationActivationFixtures.tree(owned.storage))
    }
    @Test fun concurrentHistoryReplacementCannotBlendAnIndexAndItsOriginalFragments(){
        history(35,"initial");val errors=ConcurrentLinkedQueue<Throwable>();val writer=Thread{try{repeat(20){history(35,"revision-$it")}}catch(e: Throwable){errors.add(e)}}
        writer.start();repeat(20){val records=captured();val page=NativeHistoryCache.decode(key){records[it]}!!;assertEquals(35,page.messages.size);val labels=page.messages.map{it.text.substringBefore(" 中文")}.distinct();assertEquals(1,labels.size)}
        writer.join(30_000);assertFalse(writer.isAlive);assertTrue(errors.toString(),errors.isEmpty());assertEquals(35,NativeHistoryCache(owned.storage).read(key)!!.messages.size)
    }
}
