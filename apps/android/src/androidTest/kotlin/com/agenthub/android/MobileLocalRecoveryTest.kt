package com.agenthub.android

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Real encrypted fixture files only: never alter the default app namespace or shared key alias. */
class MobileLocalRecoveryTest {
    @get:Rule val foreground: org.junit.rules.TestRule=NativeTestForeground()
    private val context=InstrumentationRegistry.getInstrumentation().targetContext
    private fun sha(bytes: ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    @Test fun failedMetadataReadDoesNotAuthorizeSubsequentMetadataReplacement()=runBlocking {
        val id=UUID.randomUUID().toString().replace("-","")
        val namespace="P4-read-failure-$id";require(namespace.startsWith("P4-read-failure-"))
        val storage=NativeSessionStorage(context,namespace,"p4_read_failure_$id")
        val malformed="{P4_OWNED_UNREADABLE_METADATA"
        storage.write("metadata",malformed)
        val file=File(File(context.noBackupFilesDir,namespace),sha("metadata".toByteArray())+".bin")
        val original=file.readBytes()
        val session=withContext(Dispatchers.Main) { MobileSession(storage).also { it.restore() } }
        try {
            withTimeout(5_000) { while(withContext(Dispatchers.Main) { session.restoring })delay(10) }
            assertNotNull("The actual restore must report unreadable metadata",session.error)
            withContext(Dispatchers.Main) { session.changeAppearance("dark") }
            val barrier=CountDownLatch(1)
            storage.enqueueSnapshot("owned-proof-barrier",{ barrier.countDown() },{ barrier.countDown() })
            assertTrue("Wait for the same serial writer after the local UI change",barrier.await(5,TimeUnit.SECONDS))
            val after=file.readBytes()
            println("P4_RECOVERY_METADATA_FINGERPRINTS before=${sha(original)} after=${sha(after)}")
            assertArrayEquals("A metadata read failure must preserve the encrypted original across later local UI changes",original,after)
            assertEquals(malformed,storage.read("metadata"))
        } finally { withContext(Dispatchers.Main) { session.leaveForeground() } }
    }
    private fun owned(): Pair<NativeSessionStorage,File> {
        val id=UUID.randomUUID().toString().replace("-","")
        val namespace="P4-read-failure-$id"
        return NativeSessionStorage(context,namespace,"p4_read_failure_$id") to
            File(File(context.noBackupFilesDir,namespace),sha("metadata".toByteArray())+".bin")
    }
    private suspend fun restored(storage: NativeSessionStorage): MobileSession {
        val session=withContext(Dispatchers.Main) { MobileSession(storage).also { it.restore() } }
        withTimeout(5_000) { while(withContext(Dispatchers.Main) { session.restoring })delay(10) }
        return session
    }
    private fun drain(storage: NativeSessionStorage) {
        val latch=CountDownLatch(1);storage.enqueueSnapshot("test-drain-"+UUID.randomUUID(),{ latch.countDown() },{ latch.countDown() })
        assertTrue(latch.await(5,TimeUnit.SECONDS))
    }
    private fun metadata()=org.json.JSONObject().put("version",1).put("appearance","dark").put("demo",true)
        .put("selected",DemoMobileData.clients.first().id).put("clients",org.json.JSONArray())
        .put("drafts",org.json.JSONObject().put("unapplied-draft","preserved"))
        .put("selectedThreads",org.json.JSONObject()).put("expanded",org.json.JSONObject()).put("workspaces",org.json.JSONObject())
    @Test fun lateDecodeFailurePublishesNothingAndRetryRestoresOriginalDraftThenSavesNormally()=runBlocking {
        val (storage,file)=owned()
        val healthy=restored(storage)
        withContext(Dispatchers.Main) { healthy.setDemoMode(true);healthy.text="original owned draft";healthy.changeAppearance("dark") }
        drain(storage);val valid=file.readBytes();val json=checkNotNull(storage.metadata())
        withContext(Dispatchers.Main) { healthy.leaveForeground() }
        json.put("workspaces",org.json.JSONObject().put("bad",org.json.JSONObject()))
        storage.write("metadata",json.toString());val failed=file.readBytes()
        val session=restored(storage)
        try {
            assertTrue(session.localRecoveryRequired);assertFalse(session.demo);assertEquals("system",session.appearance)
            assertFalse(session.canCompose);assertFalse(session.canCreateThread);assertFalse(session.isWritable)
            withContext(Dispatchers.Main) { session.changeAppearance("light");session.enterForeground() }
            assertArrayEquals(failed,file.readBytes())
            // Repair only this test's unique encrypted fixture; the app itself never overwrites a failed file.
            val atomic=android.util.AtomicFile(file);val out=atomic.startWrite();out.write(valid);atomic.finishWrite(out)
            withContext(Dispatchers.Main) { session.retryLocalRecovery();session.retryLocalRecovery() }
            withTimeout(5_000) { while(withContext(Dispatchers.Main) { session.restoring })delay(10) }
            assertFalse(session.localRecoveryRequired);assertNull(session.error);assertTrue(session.demo)
            assertEquals("dark",session.appearance);assertEquals("original owned draft",session.text)
            withContext(Dispatchers.Main) { session.text="new owned draft after recovery" }
            drain(storage)
            val restarted=restored(storage)
            assertFalse(restarted.localRecoveryRequired);assertEquals("new owned draft after recovery",restarted.text)
            withContext(Dispatchers.Main) { restarted.leaveForeground() }
        } finally { withContext(Dispatchers.Main) { session.leaveForeground() } }
    }
    @Test fun sharedDirectoryBlocksOtherInstanceWritesDeletesAndOldQueuedSnapshotsAfterRetry() {
        val (storage,file)=owned();storage.write("metadata",metadata().toString());val original=file.readBytes()
        val id=file.parentFile!!.name.removePrefix("P4-read-failure-")
        val other=NativeSessionStorage(context,file.parentFile!!.name,"p4_read_failure_$id")
        val started=CountDownLatch(1);val release=CountDownLatch(1);val ended=CountDownLatch(1);val rejected=CountDownLatch(1)
        storage.enqueueSnapshot("old-snapshot",{ started.countDown();check(release.await(5,TimeUnit.SECONDS));storage.write("metadata","old snapshot") },{ rejected.countDown() })
        assertTrue(started.await(5,TimeUnit.SECONDS))
        val generation=other.beginRestore()
        assertThrows(IllegalStateException::class.java) { storage.write("metadata","overwrite") }
        assertThrows(IllegalStateException::class.java) { other.remove("metadata") }
        assertArrayEquals(original,file.readBytes())
        other.finishRestore(generation) { }
        release.countDown()
        storage.enqueueSnapshot("barrier",{ ended.countDown() },{ ended.countDown() })
        assertTrue(ended.await(5,TimeUnit.SECONDS));assertTrue(rejected.await(5,TimeUnit.SECONDS))
        assertArrayEquals(original,file.readBytes())
        other.write("metadata",metadata().put("appearance","light").toString())
        assertEquals("light",storage.metadata()!!.getString("appearance"))
    }
    @Test fun oldQueuedMetadataIsRejectedAfterSuccessfulRestoreInsteadOfOverwritingRecoveredState() {
        val (storage,file)=owned();storage.write("metadata",metadata().toString());val original=file.readBytes()
        val started=CountDownLatch(1);val release=CountDownLatch(1);val rejected=CountDownLatch(1)
        storage.enqueueSnapshot("hold-own-worker",{ started.countDown();check(release.await(5,TimeUnit.SECONDS)) },{ throw AssertionError(it) })
        assertTrue(started.await(5,TimeUnit.SECONDS))
        storage.enqueue("metadata","obsolete snapshot",{ rejected.countDown() })
        val generation=storage.beginRestore();storage.finishRestore(generation) { }
        release.countDown();drain(storage)
        assertTrue(rejected.await(5,TimeUnit.SECONDS));assertArrayEquals(original,file.readBytes())
    }
    @Test fun authenticationFailureKeepsEncryptedBytesAndDoesNotCreateWritableEmptyState()=runBlocking {
        val (storage,file)=owned();storage.write("metadata",metadata().toString());val original=file.readBytes()
        val wrong=NativeSessionStorage(context,file.parentFile!!.name,"wrong_"+UUID.randomUUID().toString().replace("-",""))
        val session=restored(wrong)
        try {
            assertTrue(session.localRecoveryRequired)
            withContext(Dispatchers.Main) { session.setDemoMode(true);session.text="must not replace original" }
            assertFalse(session.canCompose);assertArrayEquals(original,file.readBytes())
            assertThrows(IllegalStateException::class.java) { wrong.write("metadata","empty") };Unit
        } finally { withContext(Dispatchers.Main) { session.leaveForeground() } }
    }

}
