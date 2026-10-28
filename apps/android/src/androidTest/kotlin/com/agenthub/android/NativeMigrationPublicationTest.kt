package com.agenthub.android

import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import kotlinx.coroutines.runBlocking
import org.json.*
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Actual Keystore, AtomicFile and worker boundaries in owned installation namespaces. No remote commands. */
class NativeMigrationPublicationTest {
    @get:Rule(order=0)val foreground: org.junit.rules.TestRule=NativeTestForeground()
    @get:Rule(order=1)val ui=createAndroidComposeRule<MainActivity>()
    private lateinit var owned: OwnedControlAcceptance
    @Before fun setup(){ui.waitUntil(30_000){!ui.activity.session.restoring};ui.runOnIdle{owned=OwnedControlAcceptance(ui.activity,"migration-publication")}}
    @After fun finish(){if(::owned.isInitialized)ui.runOnIdle{owned.session.leaveForeground();owned.verifyDefault();assertTrue(owned.session.operationRecords.isEmpty())}}
    private fun hash(bytes: ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)}
    private fun snapshot(store: NativeSessionStorage)=File(store.coordinationKey).let {root->root.walkTopDown().filter{it.isFile}.associate {it.relativeTo(root).path to hash(it.readBytes())}}
    private fun records()=mapOf("metadata" to "{\"version\":1,\"label\":\"imported 100% %@ 😀\"}","submission:original" to "original immutable input", "draft-file:original" to "original image bytes")
    private fun publish(store: NativeSessionStorage,step: (Int)->Unit={})=store.publishMigration(UUID.randomUUID().toString(),hash("owned candidate".toByteArray()),records(),step)
    @Test fun completeGenerationPublishesOnceAndRejectsOldWritersWithoutChangingOriginalIntent(){
        val old=owned.storage;old.write("metadata","original installation");old.write("local-only","original local note");val before=snapshot(old)
        publish(old);val imported=old.reopen(ui.activity)
        assertEquals(records()["metadata"],imported.read("metadata"));assertEquals(records()["submission:original"],imported.read("submission:original"));assertNull(imported.read("local-only"))
        for((name,digest)in before)assertEquals(digest,hash(File(old.coordinationKey,name).readBytes()))
        assertTrue(runCatching {old.write("metadata","late old overwrite")}.isFailure);assertTrue(runCatching {old.remove("submission:original")}.isFailure);assertTrue(runCatching{old.beginRestore()}.isFailure);assertTrue(runCatching{old.child(ui.activity,"late-child").write("late","stale child")}.isFailure)
        imported.write("editable","new local draft");assertEquals("new local draft",imported.reopen(ui.activity).read("editable"))
    }
    @Test fun everyStagingFailurePreservesOriginalCiphertextAndReleasesOnlyItsOwnTemporaryFiles(){
        val store=owned.storage;store.write("metadata","original");store.write("submission:original","original intent");val before=snapshot(store)
        for(boundary in listOf(1,2,3,0)){
            var reached=false;assertTrue(runCatching {publish(store){if(it==boundary){reached=true;throw java.io.IOException("Owned interrupted stage")}}}.isFailure);assertTrue(reached)
            assertEquals(before,snapshot(store));assertEquals("original",store.reopen(ui.activity).read("metadata"));assertEquals("original intent",store.read("submission:original"))
        }
        assertTrue(runCatching{publish(store){if(it==2)throw OutOfMemoryError("Owned memory fault")}}.isFailure);assertEquals(before,snapshot(store))
    }
    @Test fun queuedSnapshotFromOldGenerationCannotLandAfterPublication(){
        val old=owned.storage;old.write("metadata","original");val entered=CountDownLatch(1);val release=CountDownLatch(1);val rejected=CountDownLatch(1)
        old.enqueueSnapshot("metadata",{entered.countDown();check(release.await(10,TimeUnit.SECONDS));old.write("metadata","late queued old state")},{rejected.countDown()})
        try {assertTrue(entered.await(10,TimeUnit.SECONDS));publish(old)}finally{release.countDown()}
        assertTrue(rejected.await(10,TimeUnit.SECONDS));runBlocking{old.awaitQueuedWrites()};assertEquals(records()["metadata"],old.reopen(ui.activity).read("metadata"))
    }
    @Test fun newGenerationHasFreshChildAuthorityAndCorruptPointerNeverFallsBack(){
        val old=owned.storage;old.write("metadata","old");val child=old.child(ui.activity,"photo-permission");child.write("attempt-images","original installation attempt")
        publish(old);val current=old.reopen(ui.activity);val fresh=current.child(ui.activity,"photo-permission");assertNotEquals(child.coordinationKey,fresh.coordinationKey);assertNull(fresh.read("attempt-images"));assertEquals("original installation attempt",child.read("attempt-images"))
        val pointer=File(old.coordinationKey,hash("migration-active-generation-v1".toByteArray())+".bin");val bytes=pointer.readBytes();pointer.writeBytes(bytes.copyOf().apply{this[lastIndex]=(this[lastIndex].toInt() xor 1).toByte()})
        assertTrue(runCatching{current.read("metadata")}.isFailure);assertTrue(runCatching{current.reopen(ui.activity).read("metadata")}.isFailure);assertTrue(runCatching{current.reopen(ui.activity).child(ui.activity,"photo-permission").read("attempt-images")}.isFailure)
        assertTrue(runCatching{current.write("metadata","fallback overwrite")}.isFailure);pointer.writeBytes(bytes)
        assertEquals(records()["metadata"],current.reopen(ui.activity).read("metadata"));assertTrue(snapshot(current).isNotEmpty())
    }
    @Test fun sameCandidateReceiptIsIdempotentAndCannotReplayOverLaterEdits(){
        val old=owned.storage;old.write("metadata","original");val id=UUID.randomUUID().toString();val digest=hash(byteArrayOf(2));val generation=old.publishMigration(id,digest,records())
        val current=old.reopen(ui.activity);current.write("metadata","later imported draft edit");val before=snapshot(current)
        assertEquals(generation,current.publishMigration(id,digest,records()));assertEquals(generation,old.publishMigration(id,digest,records()));assertEquals(before,snapshot(current))
        assertTrue(runCatching{current.publishMigration(id,digest,records()+mapOf("metadata" to "changed payload"))}.isFailure);assertEquals(before,snapshot(current))
    }
    @Test fun invalidPublicationDoesNotCreateAnActiveGeneration(){
        val store=owned.storage;store.write("metadata","original");val before=snapshot(store)
        for(values in listOf(emptyMap(),mapOf("only-file" to "missing metadata"),mapOf("metadata" to "new","migration-active-generation-v1" to "injected"))){assertTrue(runCatching{store.publishMigration(UUID.randomUUID().toString(),hash(byteArrayOf(1)),values)}.isFailure);assertEquals(before,snapshot(store))}
    }
}
